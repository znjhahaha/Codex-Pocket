//! Current-user authenticated upgrade adapter. Native processes are queried only.
//! Installation paths are immutable; all mutable recovery context lives in state.
use super::{Checkpoint, HandoffBackend, UpgradeStatus, Verification};
use crate::{
    storage,
    supervisor::maintenance::{self, Action, Journal, ProcessIdentity, Request},
};
use anyhow::{bail, ensure, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    io::{Read, Write},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::{Duration, Instant},
};

const CONTEXT: &str = "upgrade-local.json";
const MAX_IMAGE: u64 = 64 * 1024 * 1024;
const METHODS: [&str; 5] = [
    "upgrade/capabilities",
    "upgrade/quiesce",
    "upgrade/status",
    "upgrade/release",
    "upgrade/cancel",
];
pub const CHECKPOINT_ENV: &str = "POCKET_AGENT_HANDOFF_CHECKPOINT";
pub const MODE_ENV: &str = "POCKET_AGENT_HANDOFF_MODE";
pub const OPERATION_ENV: &str = "POCKET_HANDOFF_OPERATION_ID";
pub const STARTUP_OPERATION_ENV: &str = "POCKET_AGENT_STARTUP_OPERATION_ID";
const RUNTIME_PAYLOAD: [&str; 4] = [
    "pocket-runtime-host.exe",
    "pocket-p2p.dll",
    "datachannel.dll",
    "P2P-THIRD-PARTY.txt",
];

/// Inventory size is not a version discriminator. Legacy inventories retain
/// their own file projection; the new runtime/transport group is all-or-none.
pub(crate) fn verify_generation_inventory(bin: &Path) -> Result<()> {
    let manifest: Value = storage::read(&bin.join("windows-manifest.json"))?;
    let entries = manifest["files"]
        .as_array()
        .context("Missing generation inventory")?;
    let mut names = BTreeSet::new();
    for entry in entries {
        let name = entry["path"].as_str().context("Inventory path missing")?;
        ensure!(
            safe_inventory_path(name) && names.insert(name.to_ascii_lowercase()),
            "Invalid or duplicate inventory path"
        );
        let expected = entry["sha256"]
            .as_str()
            .filter(|v| v.len() == 64 && v.bytes().all(|b| b.is_ascii_hexdigit()))
            .context("Invalid inventory digest")?;
        let length = entry["bytes"]
            .as_u64()
            .filter(|length| *length > 0)
            .context("Inventory length missing")?;
        let path = bin.join(name);
        storage::unredirected(&path)?;
        let file = std::fs::File::open(&path)?;
        ensure!(
            file.metadata()?.is_file() && file.metadata()?.len() == length,
            "Inventory file length changed"
        );
        let mut file = file.take(
            length
                .checked_add(1)
                .context("Inventory length exceeds limit")?,
        );
        let mut digest = Sha256::new();
        let mut buffer = [0u8; 65536];
        let mut read = 0u64;
        loop {
            let n = file.read(&mut buffer)?;
            if n == 0 {
                break;
            }
            read = read
                .checked_add(n as u64)
                .context("Inventory length overflow")?;
            digest.update(&buffer[..n]);
        }
        ensure!(
            read == length && format!("{:x}", digest.finalize()).eq_ignore_ascii_case(expected),
            "Generation inventory hash changed"
        );
    }
    for core in [
        "pocket-supervisor.exe",
        "pocket-agent.exe",
        "pocket-bridge.exe",
    ] {
        ensure!(names.contains(core), "Required generation component absent");
    }
    let group = RUNTIME_PAYLOAD
        .iter()
        .filter(|n| names.contains(&n.to_ascii_lowercase()))
        .count();
    ensure!(
        group == 0 || group == RUNTIME_PAYLOAD.len(),
        "Incomplete runtime/transport inventory"
    );
    // Check actual directory entries too: an unlisted DLL is not part of the
    // immutable release, and a junction is rejected before directory recursion.
    let mut directories = vec![bin.to_owned()];
    while let Some(dir) = directories.pop() {
        storage::unredirected(&dir)?;
        for entry in std::fs::read_dir(&dir)? {
            let path = entry?.path();
            storage::unredirected(&path)?;
            let metadata = std::fs::symlink_metadata(&path)?;
            if metadata.is_dir() {
                directories.push(path);
                continue;
            }
            let name = path
                .strip_prefix(bin)?
                .to_string_lossy()
                .replace('\\', "/")
                .to_ascii_lowercase();
            ensure!(
                metadata.is_file() && (name == "windows-manifest.json" || names.contains(&name)),
                "Unlisted generation file"
            );
        }
    }
    Ok(())
}
fn safe_inventory_path(name: &str) -> bool {
    !name.is_empty()
        && !name.eq_ignore_ascii_case("windows-manifest.json")
        && !name.contains("..")
        && name.split('/').all(|part| {
            if part.is_empty()
                || part.ends_with('.')
                || !part
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"_.-".contains(&b))
            {
                return false;
            }
            let stem = part.split('.').next().unwrap_or("").to_ascii_uppercase();
            !matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
                && !(stem.len() == 4
                    && (stem.starts_with("COM") || stem.starts_with("LPT"))
                    && matches!(stem.as_bytes()[3], b'1'..=b'9'))
        })
}
fn has_runtime_payload(bin: &Path) -> Result<bool> {
    let manifest: Value = storage::read(&bin.join("windows-manifest.json"))?;
    Ok(RUNTIME_PAYLOAD.iter().all(|name| {
        manifest["files"].as_array().is_some_and(|entries| {
            entries.iter().any(|e| {
                e["path"]
                    .as_str()
                    .is_some_and(|path| path.eq_ignore_ascii_case(name))
            })
        })
    }))
}

pub fn canonical_uuid(s: &str) -> bool {
    uuid::Uuid::parse_str(s).is_ok_and(|u| !u.is_nil() && u.to_string() == s)
}
fn hash(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CheckpointRef {
    pub id: String,
    pub generation: String,
    pub hash: String,
}
impl CheckpointRef {
    fn validate(&self) -> Result<()> {
        ensure!(
            canonical_uuid(&self.id)
                && canonical_uuid(&self.generation)
                && super::digest(&self.hash),
            "Invalid checkpoint reference"
        );
        Ok(())
    }
}
/// Explicit request values are re-applied only until durable adoption. A later
/// ordinary restart must not inherit a predecessor snapshot or attempt UUID.
#[derive(Clone, Debug)]
pub(crate) struct StartupEnvironment {
    pub checkpoint: CheckpointRef,
    mode: String,
    operation_id: Option<String>,
}
impl StartupEnvironment {
    pub(crate) fn from_env() -> Result<Option<Self>> {
        let read = |name| match std::env::var(name) {
            Ok(v) => Ok(Some(v)),
            Err(std::env::VarError::NotPresent) => Ok(None),
            Err(_) => bail!("Invalid handoff environment"),
        };
        Self::parse(
            read(CHECKPOINT_ENV)?.as_deref(),
            read(MODE_ENV)?.as_deref(),
            read(OPERATION_ENV)?.as_deref(),
        )
    }
    pub(crate) fn parse(
        checkpoint: Option<&str>,
        mode: Option<&str>,
        operation: Option<&str>,
    ) -> Result<Option<Self>> {
        let Some(text) = checkpoint else {
            ensure!(mode.is_none(), "Handoff mode lacks checkpoint");
            return Ok(None);
        };
        ensure!(text.len() <= 4096, "Checkpoint environment exceeds limit");
        let checkpoint: CheckpointRef = serde_json::from_str(text)?;
        checkpoint.validate()?;
        let mode = mode.unwrap_or("successor");
        ensure!(
            matches!(mode, "successor" | "rollback"),
            "Invalid handoff mode"
        );
        ensure!(
            operation.is_none_or(canonical_uuid),
            "Invalid handoff operation"
        );
        Ok(Some(Self {
            checkpoint,
            mode: mode.into(),
            operation_id: operation.map(str::to_owned),
        }))
    }
    pub(crate) fn apply_if_pending(&self, state: &Path, command: &mut Command) -> Result<()> {
        if startup_request_pending(&mut Windows, state, &self.checkpoint)? {
            command
                .env(CHECKPOINT_ENV, serde_json::to_string(&self.checkpoint)?)
                .env(MODE_ENV, &self.mode);
            if let Some(id) = &self.operation_id {
                command.env(OPERATION_ENV, id);
            }
        }
        Ok(())
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Receipt {
    protocol: u32,
    id: String,
    generation: String,
    phase: String,
    safe_to_exit: bool,
    native_tasks_preserved: bool,
    remote_enabled: bool,
    accepting_writes: bool,
    unconfirmed_writes: u64,
    blockers: Vec<String>,
    checkpoint: Option<CheckpointRef>,
    shutdown_requested: bool,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ImageEnvelope {
    protocol: u32,
    id: String,
    generation: String,
    snapshot: Value,
    summary_hash: String,
}
impl Receipt {
    fn bind(&self, id: &str, run: &str) -> Result<()> {
        ensure!(
            self.protocol == 1 && self.id == id && self.generation == run,
            "Stale handoff receipt"
        );
        ensure!(
            matches!(
                self.phase.as_str(),
                "draining" | "ready" | "failed" | "release_pending" | "released" | "cancelled"
            ),
            "Invalid handoff phase"
        );
        if let Some(cp) = &self.checkpoint {
            cp.validate()?;
            ensure!(cp.generation == run, "Checkpoint generation mismatch");
        }
        Ok(())
    }
    fn safe(&self) -> Result<&CheckpointRef> {
        ensure!(
            matches!(
                self.phase.as_str(),
                "ready" | "release_pending" | "released"
            ) && self.safe_to_exit
                && self.native_tasks_preserved
                && !self.remote_enabled
                && !self.accepting_writes
                && self.unconfirmed_writes == 0
                && self.blockers.is_empty(),
            "Agent is not safe to release"
        );
        self.checkpoint.as_ref().context("Checkpoint missing")
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentOperation {
    receipt: Receipt,
    prior_remote: bool,
    release_input: Option<CheckpointRef>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentRecord {
    protocol: u32,
    generation: String,
    operations: BTreeMap<String, AgentOperation>,
    #[serde(default)]
    recovery_checkpoint: Option<CheckpointRef>,
}
impl AgentRecord {
    fn validate(&self) -> Result<()> {
        ensure!(
            self.protocol == 1 && canonical_uuid(&self.generation),
            "Invalid agent startup record"
        );
        if let Some(cp) = &self.recovery_checkpoint {
            cp.validate()?;
        }
        for (id, o) in &self.operations {
            ensure!(
                canonical_uuid(id) && canonical_uuid(&o.receipt.generation),
                "Invalid durable operation identity"
            );
            o.receipt.bind(id, &o.receipt.generation)?;
            if let Some(input) = &o.release_input {
                input.validate()?;
                ensure!(
                    input.generation == o.receipt.generation,
                    "Release input generation changed"
                );
            }
            if o.receipt.phase == "cancelled" {
                ensure!(
                    !o.receipt.safe_to_exit
                        && o.receipt.accepting_writes
                        && o.receipt.remote_enabled == o.prior_remote,
                    "Invalid cancelled gate receipt"
                );
            }
        }
        Ok(())
    }
    fn released(&self, cp: &CheckpointRef) -> Result<()> {
        let receipt = &self
            .operations
            .values()
            .find(|o| o.receipt.phase == "released" && o.receipt.checkpoint.as_ref() == Some(cp))
            .context("Released predecessor absent")?
            .receipt;
        receipt.safe()?;
        ensure!(
            receipt.shutdown_requested && receipt.generation == cp.generation,
            "Predecessor release was not flushed"
        );
        Ok(())
    }
}
fn agent_record(a: &mut impl Adapter, state: &Path) -> Result<AgentRecord> {
    let d: AgentRecord = decode(a, &state.join("handoff/state.json"), MAX_IMAGE)?;
    d.validate()?;
    Ok(d)
}
fn startup_request_pending(a: &mut impl Adapter, state: &Path, cp: &CheckpointRef) -> Result<bool> {
    cp.validate()?;
    let d = agent_record(a, state)?;
    d.released(cp)?;
    ensure!(
        d.recovery_checkpoint.is_some(),
        "Durable startup reference absent"
    );
    if d.generation == cp.generation {
        ensure!(
            d.recovery_checkpoint.as_ref() == Some(cp)
                && d.operations
                    .values()
                    .all(|o| matches!(o.receipt.phase.as_str(), "released" | "cancelled")),
            "Predecessor still has an active handoff"
        );
        Ok(true)
    } else {
        // open_fenced does not write a new generation; complete_successor does.
        // Reading this commit before every spawn covers death before first health
        // polling and permits main to pick a newer released checkpoint on restart.
        Ok(false)
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Owner {
    supervisor: ProcessIdentity,
    agent: ProcessIdentity,
    generation: String,
    run: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Durable {
    protocol: u32,
    operation_id: String,
    from_generation: String,
    to_generation: String,
    source: Owner,
    current: Owner,
    coordinator: ProcessIdentity,
    prior_remote: bool,
    routes: Value,
    computer_id: String,
    ready: Option<CheckpointRef>,
    final_checkpoint: Option<CheckpointRef>,
    active_id: String,
    verify_id: String,
    rollback_id: String,
    stage: String,
    spawn_pid: Option<u32>,
    spawn_identity: Option<ProcessIdentity>,
}
fn context_path(s: &UpgradeStatus) -> PathBuf {
    storage::control(&s.state_directory).join(CONTEXT)
}
fn params(id: &str, run: &str, cp: Option<&CheckpointRef>) -> Value {
    json!({"id":id,"fromGeneration":run,"checkpoint":cp})
}

/// All production effects go through this seam so tests need no installation,
/// real daemon, CLI or model. The native implementation never has kill rights.
trait Adapter {
    fn capture(&mut self, pid: u32) -> Result<ProcessIdentity>;
    fn presence(&mut self, p: &ProcessIdentity) -> maintenance::Presence;
    fn read(&mut self, path: &Path, limit: u64) -> Result<Vec<u8>>;
    fn rpc(&mut self, state: &Path, method: &str, params: Value) -> Result<Value>;
    fn tick(&mut self) {
        std::thread::sleep(Duration::from_millis(100));
    }
    fn spawn(
        &mut self,
        s: &UpgradeStatus,
        generation: &str,
        cp: &CheckpointRef,
        remote: bool,
    ) -> Result<u32>;
    fn submit(&mut self, state: &Path, r: &Request) -> Result<()> {
        maintenance::submit(state, r)
    }
    fn ack(&mut self, state: &Path) -> Result<Option<Journal>> {
        maintenance::read_status(state)
    }
    fn save(&mut self, s: &UpgradeStatus, d: &Durable) -> Result<()> {
        maintenance::write_private(&context_path(s), d)
    }
    fn locks_free(&mut self, s: &UpgradeStatus) -> Result<()> {
        // Prove natural release of all owner locks; never remove a lock file.
        let paths = [
            s.install_root.join("control/supervisor.lock"),
            storage::control(&s.state_directory).join("supervisor.lock"),
            s.state_directory.join("agent.lock"),
        ];
        let mut guards = Vec::new();
        for path in paths {
            storage::unredirected(&path)?;
            guards.push(storage::Lock::acquire(&path)?);
        }
        Ok(())
    }
}
struct Windows;
impl Adapter for Windows {
    fn capture(&mut self, pid: u32) -> Result<ProcessIdentity> {
        ProcessIdentity::capture(pid)
    }
    fn presence(&mut self, p: &ProcessIdentity) -> maintenance::Presence {
        maintenance::presence(p)
    }
    fn read(&mut self, path: &Path, limit: u64) -> Result<Vec<u8>> {
        let mut f = maintenance::open_agent_private(path)?;
        ensure!(f.metadata()?.len() <= limit, "Private file exceeds limit");
        let mut bytes = Vec::new();
        Read::by_ref(&mut f)
            .take(limit + 1)
            .read_to_end(&mut bytes)?;
        ensure!(bytes.len() as u64 <= limit, "Private file exceeds limit");
        Ok(bytes)
    }
    fn rpc(&mut self, state: &Path, method: &str, params: Value) -> Result<Value> {
        // Never invoke the CLI fallback: it can modify an offline state.
        let endpoint: Value =
            serde_json::from_slice(&self.read(&state.join("control.json"), 4096)?)?;
        ensure!(endpoint["protocol"] == 1, "Unsupported local transport");
        let port = endpoint["port"]
            .as_u64()
            .filter(|p| *p > 0 && *p <= 65535)
            .context("Invalid local port")? as u16;
        let token = endpoint["token"]
            .as_str()
            .filter(|t| super::digest(t))
            .context("Invalid local authentication")?;
        let mut stream = std::net::TcpStream::connect_timeout(
            &([127, 0, 0, 1], port).into(),
            Duration::from_secs(1),
        )?;
        stream.set_read_timeout(Some(Duration::from_secs(5)))?;
        stream.set_write_timeout(Some(Duration::from_secs(5)))?;
        let body = serde_json::to_vec(&json!({"token":token,"method":method,"params":params}))?;
        stream.write_all(&(body.len() as u32).to_be_bytes())?;
        stream.write_all(&body)?;
        stream.flush()?;
        let mut size = [0; 4];
        stream.read_exact(&mut size)?;
        let n = u32::from_be_bytes(size) as usize;
        ensure!(n > 0 && n <= 1024 * 1024, "Invalid response length");
        let mut bytes = vec![0; n];
        stream.read_exact(&mut bytes)?;
        let reply: Value = serde_json::from_slice(&bytes)?;
        ensure!(
            reply.as_object().is_some_and(|o| o.len() == 1),
            "Invalid control envelope"
        );
        if let Some(e) = reply.get("error") {
            // Do not expose native/server text or secrets in diagnostics.
            if matches!(e["code"].as_str(), Some("unsupported" | "method_not_found"))
                || e["code"] == -32601
            {
                bail!(Unsupported);
            }
            bail!("Authenticated local request failed");
        }
        reply
            .get("result")
            .cloned()
            .context("Missing control result")
    }
    fn spawn(
        &mut self,
        s: &UpgradeStatus,
        generation: &str,
        cp: &CheckpointRef,
        _: bool,
    ) -> Result<u32> {
        let bin = super::verified_generation(&s.install_root, generation)?;
        let exe = crate::launcher::verified_exe(&bin, "pocket-supervisor.exe")?;
        let agent = crate::launcher::verified_exe(&bin, "pocket-agent.exe")?;
        let mut c = Command::new(exe);
        c.arg("--state")
            .arg(&s.state_directory)
            .arg("--agent")
            .arg(agent)
            .arg("--owner-directory")
            .arg(s.install_root.join("control"))
            .env(CHECKPOINT_ENV, serde_json::to_string(cp)?)
            .env(
                MODE_ENV,
                if generation == s.to_generation {
                    "successor"
                } else {
                    "rollback"
                },
            )
            .env(OPERATION_ENV, &s.operation_id)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            c.creation_flags(0x08000000 | 0x01000000);
        }
        Ok(c.spawn()?.id())
    }
}
#[derive(Debug)]
struct Unsupported;
impl std::fmt::Display for Unsupported {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Safe upgrade unsupported")
    }
}
impl std::error::Error for Unsupported {}

fn decode<T: serde::de::DeserializeOwned>(a: &mut impl Adapter, p: &Path, limit: u64) -> Result<T> {
    Ok(serde_json::from_slice(&a.read(p, limit)?)?)
}
fn capabilities(a: &mut impl Adapter, state: &Path) -> Result<String> {
    let c = a.rpc(state, "upgrade/capabilities", json!({}))?;
    capability_generation(&c)
}
fn capability_generation(c: &Value) -> Result<String> {
    if c["supported"] == false {
        bail!(Unsupported);
    }
    ensure!(
        c["protocol"] == 1
            && c["supported"] == true
            && c["owner"] == "pocket-agent"
            && c["checkpoint"] == "sha256"
            && c["shutdown"] == "after_reply_flush"
            && c["writeFence"] == true
            && c["preservesIdentity"] == true,
        "Invalid upgrade capabilities"
    );
    ensure!(
        c["methods"]
            .as_array()
            .is_some_and(|m| m.len() == METHODS.len()
                && METHODS.iter().all(|name| m.iter().any(|v| v == name))),
        "Incomplete upgrade capabilities"
    );
    let run = c["generation"]
        .as_str()
        .filter(|r| canonical_uuid(r))
        .context("Invalid agent generation")?;
    Ok(run.into())
}
fn checked_process(
    a: &mut impl Adapter,
    s: &UpgradeStatus,
    p: &ProcessIdentity,
    generation: &str,
    name: &str,
) -> Result<()> {
    let bin = super::verified_generation(&s.install_root, generation)?;
    ensure!(
        p.executable == crate::launcher::verified_exe(&bin, name)?,
        "Unexpected process image"
    );
    ensure!(a.capture(p.pid)? == *p, "Process identity changed");
    Ok(())
}
fn manifest_digest(s: &UpgradeStatus, generation: &str, name: &str) -> Result<String> {
    let m: Value = storage::read(
        &s.install_root
            .join("bin")
            .join(generation)
            .join("windows-manifest.json"),
    )?;
    m["files"]
        .as_array()
        .and_then(|entries| entries.iter().find(|e| e["path"] == name))
        .and_then(|entry| entry["sha256"].as_str())
        .filter(|digest| super::digest(digest))
        .map(str::to_owned)
        .context("Executable digest missing")
}
fn validate_health(
    s: &UpgradeStatus,
    agent: &ProcessIdentity,
    generation: &str,
    run: &str,
    health: &Value,
) -> Result<()> {
    ensure!(
        health["protocol"] == 1
            && health["remoteControlProtocol"] == 1
            && health["remoteEnabled"].is_boolean()
            && health["acceptingWrites"].is_boolean(),
        "Agent gate health incomplete"
    );
    ensure!(
        health["helperRuntime"]["pid"] == agent.pid
            && health["helperRuntime"]["processIdentity"]["pid"] == agent.pid
            && health["helperRuntime"]["processIdentity"]["createdAt"] == agent.created_at
            && health["helperRuntime"]["generation"] == generation
            && health["helperRuntime"]["executableSha256"]
                == manifest_digest(s, generation, "pocket-agent.exe")?
            && health["helperRuntime"]["controlProtocol"] == 1,
        "Agent control identity unverified"
    );
    ensure!(
        capability_generation(&health["handoffCapabilities"])? == run,
        "Status handoff generation differs from authenticated capabilities"
    );
    Ok(())
}
fn owner(a: &mut impl Adapter, s: &UpgradeStatus, generation: &str) -> Result<(Owner, Value)> {
    let h: Value = decode(
        a,
        &storage::control(&s.state_directory).join("supervisor.json"),
        16384,
    )?;
    let supervisor = a.capture(
        h["pid"]
            .as_u64()
            .and_then(|n| u32::try_from(n).ok())
            .context("Supervisor PID missing")?,
    )?;
    checked_process(a, s, &supervisor, generation, "pocket-supervisor.exe")?;
    ensure!(
        h["runtime"]["generation"] == generation
            && h["runtime"]["processCreatedAt"] == supervisor.created_at
            && h["runtime"]["executableSha256"]
                == manifest_digest(s, generation, "pocket-supervisor.exe")?,
        "Supervisor identity unverified"
    );
    if h["maintenanceProtocol"] != 1 {
        bail!(Unsupported);
    }
    let agent = a.capture(
        h["childPid"]
            .as_u64()
            .and_then(|n| u32::try_from(n).ok())
            .context("Agent child missing")?,
    )?;
    checked_process(a, s, &agent, generation, "pocket-agent.exe")?;
    let health = a.rpc(&s.state_directory, "status", json!({}))?;
    let run = capabilities(a, &s.state_directory)?;
    validate_health(s, &agent, generation, &run, &health)?;
    checked_process(a, s, &agent, generation, "pocket-agent.exe")?;
    Ok((
        Owner {
            supervisor,
            agent,
            generation: generation.into(),
            run,
        },
        health,
    ))
}
fn begin(a: &mut impl Adapter, s: &UpgradeStatus) -> Result<Durable> {
    let from = super::verified_generation(&s.install_root, &s.from_generation)?;
    let to = super::verified_generation(&s.install_root, &s.to_generation)?;
    if !has_runtime_payload(&from)? || !has_runtime_payload(&to)? {
        bail!(Unsupported);
    }
    let (source, health) = owner(a, s, &s.from_generation)?;
    ensure!(
        health["acceptingWrites"] == true,
        "Source admission is already fenced"
    );
    let coordinator = a.capture(std::process::id())?;
    // In production the independent coordinator itself must be a manifest file.
    checked_process(a, s, &coordinator, &s.to_generation, "pocket-upgrade.exe")?;
    let report = a.rpc(&s.state_directory, "routes/read", json!({}))?;
    let routes = json!({"routePreferences":report["routePreferences"]});
    ensure!(
        routes["routePreferences"].is_object(),
        "Route preferences missing"
    );
    let config: Value = decode(a, &s.state_directory.join("config.json"), 1024 * 1024)?;
    let computer_id = config["computerId"]
        .as_str()
        .filter(|v| !v.is_empty())
        .context("Computer identity missing")?
        .to_owned();
    ensure!(
        health["computerId"] == computer_id,
        "Source computer identity changed"
    );
    let prior_remote = health["remoteEnabled"]
        .as_bool()
        .context("Remote gate missing")?;
    let d = Durable {
        protocol: 1,
        operation_id: s.operation_id.clone(),
        from_generation: s.from_generation.clone(),
        to_generation: s.to_generation.clone(),
        current: source.clone(),
        source,
        coordinator,
        prior_remote,
        routes,
        computer_id,
        ready: None,
        final_checkpoint: None,
        active_id: s.operation_id.clone(),
        verify_id: uuid::Uuid::new_v4().to_string(),
        rollback_id: uuid::Uuid::new_v4().to_string(),
        stage: "prepared".into(),
        spawn_pid: None,
        spawn_identity: None,
    };
    a.save(s, &d)?;
    Ok(d)
}
fn load(a: &mut impl Adapter, s: &UpgradeStatus) -> Result<Durable> {
    let d: Durable = decode(a, &context_path(s), 1024 * 1024)?;
    ensure!(
        d.protocol == 1
            && d.operation_id == s.operation_id
            && d.from_generation == s.from_generation
            && d.to_generation == s.to_generation
            && canonical_uuid(&d.current.run)
            && canonical_uuid(&d.verify_id)
            && canonical_uuid(&d.rollback_id),
        "Invalid local recovery context"
    );
    for cp in [&d.ready, &d.final_checkpoint].into_iter().flatten() {
        cp.validate()?;
    }
    Ok(d)
}
fn save_stage(a: &mut impl Adapter, s: &UpgradeStatus, d: &mut Durable, stage: &str) -> Result<()> {
    d.stage = stage.into();
    a.save(s, d)
}
fn request(s: &UpgradeStatus, d: &Durable, action: Action) -> Request {
    Request {
        protocol: 1,
        operation_id: s.operation_id.clone(),
        from_generation: d.current.generation.clone(),
        supervisor: d.current.supervisor.clone(),
        coordinator: d.coordinator.clone(),
        child: Some(d.current.agent.clone()),
        action,
    }
}
fn ack(a: &mut impl Adapter, s: &UpgradeStatus, d: &Durable, action: Action) -> Result<()> {
    let r = request(s, d, action);
    a.submit(&s.state_directory, &r)?;
    let phase = match action {
        Action::Enter | Action::Recover => maintenance::Phase::Owned,
        Action::Cancel => maintenance::Phase::Cancelled,
        Action::Release => maintenance::Phase::Released,
    };
    let end = Instant::now() + Duration::from_secs(12);
    loop {
        if let Some(j) = a.ack(&s.state_directory)? {
            let mut expected = r.clone();
            expected.action = Action::Enter;
            if j.request == expected && j.phase == phase {
                return Ok(());
            }
        }
        ensure!(
            a.presence(&d.current.supervisor) == maintenance::Presence::Alive
                && Instant::now() < end,
            "Maintenance acknowledgement unavailable"
        );
        a.tick();
    }
}
fn ensure_owner(a: &mut impl Adapter, s: &UpgradeStatus, d: &Durable) -> Result<Value> {
    checked_process(
        a,
        s,
        &d.current.supervisor,
        &d.current.generation,
        "pocket-supervisor.exe",
    )?;
    checked_process(
        a,
        s,
        &d.current.agent,
        &d.current.generation,
        "pocket-agent.exe",
    )?;
    ensure!(
        capabilities(a, &s.state_directory)? == d.current.run,
        "Agent run changed"
    );
    let health = a.rpc(&s.state_directory, "status", json!({}))?;
    validate_health(
        s,
        &d.current.agent,
        &d.current.generation,
        &d.current.run,
        &health,
    )?;
    ensure!(
        health["computerId"] == d.computer_id,
        "Agent computer identity changed"
    );
    Ok(health)
}
fn get_receipt(
    a: &mut impl Adapter,
    s: &UpgradeStatus,
    d: &Durable,
    method: &str,
    cp: Option<&CheckpointRef>,
) -> Result<Receipt> {
    ensure_owner(a, s, d)?;
    let r: Receipt = serde_json::from_value(a.rpc(
        &s.state_directory,
        method,
        params(&d.active_id, &d.current.run, cp),
    )?)?;
    r.bind(&d.active_id, &d.current.run)?;
    Ok(r)
}

/// Hash the serialized snapshot bytes in their ORIGINAL struct field order.
/// Value reserialization would reorder cursor/event fields and reject real images.
fn snapshot_bytes(bytes: &[u8]) -> Result<Vec<u8>> {
    let mut depth = 0i32;
    let mut string = false;
    let mut escaped = false;
    let mut i = 0;
    while i < bytes.len() {
        let b = bytes[i];
        if string {
            if escaped {
                escaped = false;
            } else if b == b'\\' {
                escaped = true;
            } else if b == b'"' {
                string = false;
            }
            i += 1;
            continue;
        }
        if b == b'"' && depth == 1 && bytes[i..].starts_with(b"\"snapshot\"") {
            i += 10;
            while bytes.get(i).is_some_and(u8::is_ascii_whitespace) {
                i += 1;
            }
            ensure!(bytes.get(i) == Some(&b':'), "Invalid snapshot field");
            i += 1;
            while bytes.get(i).is_some_and(u8::is_ascii_whitespace) {
                i += 1;
            }
            ensure!(bytes.get(i) == Some(&b'{'), "Invalid snapshot object");
            let mut level = 0;
            let mut quoted = false;
            let mut escape = false;
            let mut compact = Vec::new();
            for &c in &bytes[i..] {
                if quoted {
                    compact.push(c);
                    if escape {
                        escape = false;
                    } else if c == b'\\' {
                        escape = true;
                    } else if c == b'"' {
                        quoted = false;
                    }
                } else {
                    if !c.is_ascii_whitespace() {
                        compact.push(c);
                    }
                    match c {
                        b'"' => quoted = true,
                        b'{' | b'[' => level += 1,
                        b'}' | b']' => {
                            level -= 1;
                            if level == 0 {
                                return Ok(compact);
                            }
                        }
                        _ => {}
                    }
                }
            }
            bail!("Truncated snapshot");
        }
        match b {
            b'"' => string = true,
            b'{' | b'[' => depth += 1,
            b'}' | b']' => depth -= 1,
            _ => {}
        };
        i += 1;
    }
    bail!("Snapshot absent")
}
fn image(a: &mut impl Adapter, state: &Path, cp: &CheckpointRef, computer: &str) -> Result<Value> {
    cp.validate()?;
    let bytes = a.read(
        &state.join("handoff").join(format!("{}.json", cp.id)),
        MAX_IMAGE,
    )?;
    ensure!(hash(&bytes) == cp.hash, "Checkpoint bytes changed");
    let v: ImageEnvelope = serde_json::from_slice(&bytes)?;
    ensure!(
        v.protocol == 1
            && v.id == cp.id
            && v.generation == cp.generation
            && v.summary_hash == hash(&snapshot_bytes(&bytes)?),
        "Checkpoint image mismatch"
    );
    let snap = &v.snapshot;
    ensure!(
        snap["computerId"] == computer
            && snap["blockers"].as_array().is_some_and(Vec::is_empty)
            && snap["ledger"]["entries"].is_array()
            && snap["pending"].is_object()
            && snap["ownership"].is_array()
            && snap["questionReceipts"]["records"].is_array(),
        "Invalid checkpoint snapshot"
    );
    ensure!(
        !snap["ledger"]["entries"]
            .as_array()
            .unwrap()
            .iter()
            .any(|e| matches!(
                e["status"].as_str(),
                Some("Submitted" | "Unknown" | "submitted" | "unknown")
            )),
        "Unconfirmed ledger writes"
    );
    ensure!(
        !snap["pending"]
            .as_object()
            .unwrap()
            .values()
            .any(|p| !p["command"].is_null()),
        "Unconfirmed approval response"
    );
    ensure!(
        snap["questionReceipts"]["records"]
            .as_array()
            .unwrap()
            .iter()
            .all(|r| matches!(
                r["phase"].as_str(),
                Some("confirmed" | "answered_elsewhere" | "cancelled")
            )),
        "Unconfirmed question response"
    );
    for (runtime, archive) in snap["events"]
        .as_object()
        .context("Event archive missing")?
    {
        let cursor = &archive["cursor"];
        ensure!(
            cursor["runtime"]["computer_id"] == computer
                && cursor["runtime"]["runtime_id"] == *runtime
                && cursor["epoch"].as_str().is_some_and(canonical_uuid),
            "Event cursor identity mismatch"
        );
        let end = cursor["sequence"]
            .as_u64()
            .context("Event cutoff missing")?;
        let mut sequence = archive["floor"]
            .as_u64()
            .context("Event retention floor missing")?;
        ensure!(sequence <= end, "Event floor exceeds cutoff");
        for envelope in archive["events"]
            .as_array()
            .context("Retained events missing")?
        {
            sequence = sequence
                .checked_add(1)
                .context("Event sequence exhausted")?;
            ensure!(
                envelope["cursor"]["runtime"] == cursor["runtime"]
                    && envelope["cursor"]["epoch"] == cursor["epoch"]
                    && envelope["cursor"]["sequence"] == sequence,
                "Retained event gap"
            );
        }
        ensure!(sequence == end, "Retained event cutoff mismatch");
    }
    let protected = snap["protectedFiles"]
        .as_object()
        .context("Protected inventory missing")?;
    ensure!(
        protected.contains_key("config.json"),
        "Config protection missing"
    );
    for (relative, want) in protected {
        ensure!(
            !relative.is_empty()
                && !relative.contains(['\\', ':'])
                && relative
                    .split('/')
                    .all(|c| !c.is_empty() && c != "." && c != ".."),
            "Invalid protected path"
        );
        let path = state.join(relative);
        storage::unredirected(&path)?;
        if want.is_null() {
            ensure!(!path.try_exists()?, "Protected file appeared");
        } else {
            ensure!(
                want.as_str().is_some_and(super::digest)
                    && hash(&a.read(&path, MAX_IMAGE)?) == *want,
                "Protected file changed"
            );
        }
    }
    let runtimes = snap["runtimes"]
        .as_object()
        .context("Runtime inventory missing")?;
    for (id, r) in runtimes {
        ensure!(
            r["runtimeId"] == *id
                && r["store"].as_str().is_some_and(|v| !v.is_empty())
                && r["inventoryVerified"] == true
                && r["reconciliationRequired"] == false,
            "Runtime unverified"
        );
        ensure!(
            matches!(
                r["lifecycle"].as_str(),
                Some(
                    "desktop_external"
                        | "managed_host_external"
                        | "cli_daemon_external"
                        | "legacy_cli_agent_owned"
                )
            ),
            "Unknown runtime lifecycle"
        );
        if r["lifecycle"] == "legacy_cli_agent_owned" {
            ensure!(r["active"] == false, "Legacy CLI active");
            continue;
        }
        native(a, &r["nativeProcess"])?;
        if r["lifecycle"] == "managed_host_external" {
            native(a, &r["hostProcess"])?;
            ensure!(
                r["hostCursor"]["generation"]
                    .as_str()
                    .is_some_and(|g| !g.is_empty())
                    && r["hostCursor"]["after"].is_u64(),
                "Host cursor missing"
            );
        }
    }
    Ok(snap.clone())
}
fn native(a: &mut impl Adapter, value: &Value) -> Result<()> {
    let pid = value["pid"]
        .as_u64()
        .and_then(|p| u32::try_from(p).ok())
        .context("Native PID missing")?;
    let p = a.capture(pid)?;
    ensure!(
        value["createdAt"] == p.created_at,
        "Native process identity changed"
    );
    Ok(())
}
fn successor(old: &Value, new: &Value) -> Result<()> {
    ensure!(
        old["computerId"] == new["computerId"] && old["protectedFiles"] == new["protectedFiles"],
        "Successor identity changed"
    );
    let mut x = old["ledger"].clone();
    let mut y = new["ledger"].clone();
    let before = x
        .as_object_mut()
        .context("Ledger missing")?
        .remove("last_now")
        .context("Ledger time missing")?;
    let after = y
        .as_object_mut()
        .context("Ledger missing")?
        .remove("last_now")
        .context("Ledger time missing")?;
    ensure!(
        x == y && before.as_u64().is_some() && after.as_u64() >= before.as_u64(),
        "Ledger changed during handoff"
    );
    for (id, r) in old["runtimes"]
        .as_object()
        .context("Runtime inventory missing")?
    {
        if r["lifecycle"] == "legacy_cli_agent_owned" && r["active"] == false {
            continue;
        }
        let n = &new["runtimes"][id];
        for key in [
            "runtimeId",
            "store",
            "nativeProcess",
            "hostProcess",
            "lifecycle",
        ] {
            ensure!(r[key] == n[key], "Successor runtime identity changed");
        }
        if !r["hostCursor"].is_null() {
            ensure!(
                r["hostCursor"]["generation"] == n["hostCursor"]["generation"]
                    && n["hostCursor"]["after"].as_u64() >= r["hostCursor"]["after"].as_u64(),
                "Host replay cursor regressed"
            );
        }
    }
    Ok(())
}
fn ready(a: &mut impl Adapter, s: &UpgradeStatus, d: &Durable) -> Result<CheckpointRef> {
    let mut r = get_receipt(a, s, d, "upgrade/quiesce", None)?;
    let end = Instant::now() + Duration::from_secs(12);
    while r.phase == "draining" && Instant::now() < end {
        a.tick();
        r = get_receipt(a, s, d, "upgrade/status", None)?;
    }
    let cp = r.safe()?.clone();
    ensure!(r.phase == "ready", "Handoff not ready");
    image(a, &s.state_directory, &cp, &d.computer_id)?;
    Ok(cp)
}
fn gone(a: &mut impl Adapter, p: &ProcessIdentity) -> Result<()> {
    let end = Instant::now() + Duration::from_secs(15);
    loop {
        match a.presence(p) {
            maintenance::Presence::Gone => return Ok(()),
            maintenance::Presence::Unknown => bail!("Process exit identity unavailable"),
            _ => {}
        }
        ensure!(Instant::now() < end, "Process has not exited gracefully");
        a.tick();
    }
}
fn stopped(a: &mut impl Adapter, s: &UpgradeStatus, d: &mut Durable) -> Result<()> {
    let mut expected = request(s, d, Action::Enter);
    expected.action = Action::Enter;
    ensure!(
        a.ack(&s.state_directory)?
            .is_some_and(|j| j.request == expected && j.phase == maintenance::Phase::Owned),
        "Maintenance ownership lost before release"
    );
    let cp = d.ready.clone().context("Ready checkpoint missing")?;
    image(a, &s.state_directory, &cp, &d.computer_id)?;
    save_stage(a, s, d, "releasing_agent")?;
    let r = get_receipt(a, s, d, "upgrade/release", Some(&cp))?;
    let final_cp = r.safe()?.clone();
    ensure!(
        matches!(r.phase.as_str(), "release_pending" | "released"),
        "Invalid release acknowledgement"
    );
    let before = image(a, &s.state_directory, &cp, &d.computer_id)?;
    let after = image(a, &s.state_directory, &final_cp, &d.computer_id)?;
    successor(&before, &after)?;
    d.final_checkpoint = Some(final_cp);
    save_stage(a, s, d, "waiting_agent_exit")?;
    gone(a, &d.current.agent)?;
    release_proven_with(a, s, d, &request(s, d, Action::Release))?;
    save_stage(a, s, d, "releasing_supervisor")?;
    ack(a, s, d, Action::Release)?;
    gone(a, &d.current.supervisor)?;
    a.locks_free(s)?;
    save_stage(a, s, d, "released")
}
fn checkpoint(s: &UpgradeStatus, cp: &CheckpointRef) -> Checkpoint {
    Checkpoint {
        operation_id: s.operation_id.clone(),
        generation: s.from_generation.clone(),
        saved_sha256: cp.hash.clone(),
        reread_sha256: cp.hash.clone(),
        pending_writes: 0,
        writes_fenced: true,
        native_tasks_preserved: true,
    }
}
fn launch(
    a: &mut impl Adapter,
    s: &UpgradeStatus,
    d: &mut Durable,
    generation: &str,
) -> Result<()> {
    gone(a, &d.current.agent)?;
    gone(a, &d.current.supervisor)?;
    a.locks_free(s)?;
    let cp = d
        .final_checkpoint
        .clone()
        .context("Final checkpoint missing")?;
    image(a, &s.state_directory, &cp, &d.computer_id)?;
    d.verify_id = uuid::Uuid::new_v4().to_string();
    save_stage(
        a,
        s,
        d,
        if generation == s.to_generation {
            "spawning_new"
        } else {
            "spawning_old"
        },
    )?;
    let pid = match a.spawn(s, generation, &cp, d.prior_remote) {
        Ok(pid) => pid,
        Err(e) => {
            save_stage(a, s, d, "released")?;
            return Err(e);
        }
    };
    d.spawn_pid = Some(pid);
    d.spawn_identity = Some(a.capture(pid)?);
    a.save(s, d)?;
    let end = Instant::now() + Duration::from_secs(45);
    loop {
        if let Ok((candidate, health)) = owner(a, s, generation) {
            ensure!(
                candidate.supervisor.pid == pid
                    && Some(&candidate.supervisor) == d.spawn_identity.as_ref()
                    && candidate.run != cp.generation,
                "Spawned owner mismatch"
            );
            let mut observed = d.clone();
            observed.current = candidate.clone();
            if health["acceptingWrites"] != true || adoption_proven(a, s, &observed).is_err() {
                ensure!(Instant::now() < end, "Successor adoption did not complete");
                a.tick();
                continue;
            }
            d.current = candidate;
            d.ready = None;
            d.active_id = d.verify_id.clone();
            save_stage(a, s, d, "started")?;
            return Ok(());
        }
        ensure!(Instant::now() < end, "Successor did not become healthy");
        a.tick();
    }
}
fn verify(
    a: &mut impl Adapter,
    s: &UpgradeStatus,
    d: &mut Durable,
    generation: &str,
) -> Result<Verification> {
    ensure_owner(a, s, d)?;
    ensure!(
        d.current.generation == generation,
        "Wrong successor generation"
    );
    adoption_proven(a, s, d)?;
    // Hold maintenance during snapshot/read verification as well as rollback.
    ack(a, s, d, Action::Enter)?;
    save_stage(a, s, d, "verifying")?;
    let cp = ready(a, s, d)?;
    d.ready = Some(cp.clone());
    a.save(s, d)?;
    ensure!(
        agent_record(a, &s.state_directory)?.generation == d.current.run,
        "Current run was not durably checkpointed"
    );
    adoption_proven(a, s, d)?;
    let current = image(a, &s.state_directory, &cp, &d.computer_id)?;
    let old = image(
        a,
        &s.state_directory,
        d.final_checkpoint
            .as_ref()
            .context("Final checkpoint absent")?,
        &d.computer_id,
    )?;
    successor(&old, &current)?;
    let routes = a.rpc(&s.state_directory, "routes/read", json!({}))?;
    ensure!(
        routes["routePreferences"] == d.routes["routePreferences"],
        "Route preferences changed"
    );
    let r = get_receipt(a, s, d, "upgrade/cancel", Some(&cp))?;
    ensure!(
        r.phase == "cancelled" && r.accepting_writes && r.remote_enabled == d.prior_remote,
        "Successor gate not restored"
    );
    let health = ensure_owner(a, s, d)?;
    ensure!(
        health["remoteEnabled"] == d.prior_remote && health["acceptingWrites"] == true,
        "Successor gate readback failed"
    );
    ensure!(
        a.rpc(&s.state_directory, "routes/read", json!({}))?["routePreferences"]
            == d.routes["routePreferences"],
        "Route readback changed"
    );
    ack(a, s, d, Action::Cancel)?;
    save_stage(a, s, d, "verified")?;
    Ok(Verification {
        operation_id: s.operation_id.clone(),
        generation: generation.into(),
        control_protocol: 1,
        routes_read: true,
        checkpoint_sha256: s.checkpoint.as_ref().map(|c| c.saved_sha256.clone()),
        native_tasks_preserved: true,
        components_verified: true,
    })
}
fn adoption_proven(a: &mut impl Adapter, s: &UpgradeStatus, d: &Durable) -> Result<()> {
    let cp = d
        .final_checkpoint
        .as_ref()
        .context("Final checkpoint missing")?;
    let disk = agent_record(a, &s.state_directory)?;
    ensure!(
        disk.generation != cp.generation && disk.recovery_checkpoint.as_ref() == Some(cp),
        "Successor adoption not persisted"
    );
    disk.released(cp)?;
    if disk.generation != d.current.run {
        // An ordinary respawn opens a fresh in-memory run without rewriting the
        // adoption journal. Never confuse that with an unfinished release/fence.
        ensure!(
            disk.operations
                .values()
                .filter(|o| o.receipt.generation == disk.generation)
                .all(|o| o.receipt.phase == "cancelled"),
            "Previous adopted run is not terminal"
        );
    }
    Ok(())
}

fn release_proven_with(
    a: &mut impl Adapter,
    s: &UpgradeStatus,
    d: &Durable,
    r: &Request,
) -> Result<()> {
    ensure!(
        r == &request(s, d, Action::Release),
        "Release owner mismatch"
    );
    ensure!(
        a.presence(&d.current.agent) == maintenance::Presence::Gone,
        "Agent still owns state"
    );
    let cp = d
        .final_checkpoint
        .as_ref()
        .context("Final checkpoint absent")?;
    image(a, &s.state_directory, cp, &d.computer_id)?;
    let agent: Value = decode(a, &s.state_directory.join("handoff/state.json"), MAX_IMAGE)?;
    let receipt = &agent["operations"][&d.active_id]["receipt"];
    ensure!(
        agent["protocol"] == 1
            && agent["generation"] == d.current.run
            && receipt["id"] == d.active_id
            && receipt["generation"] == d.current.run
            && receipt["phase"] == "released"
            && receipt["shutdownRequested"] == true
            && receipt["checkpoint"] == json!(cp),
        "Durable flushed release missing"
    );
    let durable: Receipt = serde_json::from_value(receipt.clone())?;
    durable.bind(&d.active_id, &d.current.run)?;
    durable.safe()?;
    Ok(())
}
pub(crate) fn release_proven(state: &Path, r: &Request) -> Result<()> {
    let s = super::read_status(state)?.context("Upgrade journal absent")?;
    ensure!(
        matches!(s.phase, super::Phase::Releasing | super::Phase::RollingBack),
        "No release intent"
    );
    let mut a = Windows;
    let d = load(&mut a, &s)?;
    release_proven_with(&mut a, &s, &d, r)
}
pub(crate) fn predecessor_release_proven(state: &Path, r: &Request) -> Result<()> {
    let s = super::read_status(state)?.context("Upgrade journal absent")?;
    ensure!(
        s.phase == super::Phase::RollingBack,
        "No interrupted release recovery"
    );
    let mut a = Windows;
    let d = load(&mut a, &s)?;
    let expected = request(&s, &d, Action::Release);
    let mut old = r.clone();
    old.coordinator = expected.coordinator.clone();
    ensure!(old == expected, "Previous release ownership mismatch");
    if r.coordinator != expected.coordinator {
        ensure!(
            a.presence(&r.coordinator) == maintenance::Presence::Gone
                && a.presence(&expected.coordinator) == maintenance::Presence::Alive,
            "Coordinator recovery identity uncertain"
        );
    }
    release_proven_with(&mut a, &s, &d, &expected)
}

/// Unit adapter keeps the existing coordinator entry points source compatible.
/// Unsupported legacy agents never enter maintenance and remain waiting_restart.
pub struct LocalBackend;
impl HandoffBackend for LocalBackend {
    fn supports_handoff(&mut self, s: &UpgradeStatus) -> Result<bool> {
        match begin(&mut Windows, s) {
            Ok(_) => Ok(true),
            Err(e) if e.is::<Unsupported>() => Ok(false),
            Err(e) => Err(e),
        }
    }
    fn quiesce(&mut self, s: &UpgradeStatus) -> Result<Checkpoint> {
        let mut a = Windows;
        let mut d = load(&mut a, s)?;
        save_stage(&mut a, s, &mut d, "entering")?;
        ack(&mut a, s, &d, Action::Enter)?;
        save_stage(&mut a, s, &mut d, "quiescing")?;
        let cp = ready(&mut a, s, &d)?;
        d.ready = Some(cp.clone());
        save_stage(&mut a, s, &mut d, "ready")?;
        Ok(checkpoint(s, &cp))
    }
    fn release_old(&mut self, s: &UpgradeStatus) -> Result<()> {
        let mut a = Windows;
        let mut d = load(&mut a, s)?;
        stopped(&mut a, s, &mut d)
    }
    fn released_checkpoint(&mut self, s: &UpgradeStatus) -> Result<Option<Checkpoint>> {
        let mut a = Windows;
        let d = load(&mut a, s)?;
        let cp = d.final_checkpoint.context("Final checkpoint missing")?;
        image(&mut a, &s.state_directory, &cp, &d.computer_id)?;
        Ok(Some(checkpoint(s, &cp)))
    }
    fn start_new(&mut self, s: &UpgradeStatus) -> Result<()> {
        let mut a = Windows;
        let mut d = load(&mut a, s)?;
        launch(&mut a, s, &mut d, &s.to_generation)
    }
    fn verify_new(&mut self, s: &UpgradeStatus) -> Result<Verification> {
        let mut a = Windows;
        let mut d = load(&mut a, s)?;
        verify(&mut a, s, &mut d, &s.to_generation)
    }
    fn rollback(&mut self, s: &UpgradeStatus) -> Result<Verification> {
        rollback(&mut Windows, s)
    }
}

fn rollback(a: &mut impl Adapter, s: &UpgradeStatus) -> Result<Verification> {
    let mut d = load(a, s)?;
    let new_coordinator = a.capture(std::process::id())?;
    checked_process(
        a,
        s,
        &new_coordinator,
        &s.to_generation,
        "pocket-upgrade.exe",
    )?;
    if d.coordinator != new_coordinator {
        ensure!(
            a.presence(&d.coordinator) == maintenance::Presence::Gone,
            "Previous coordinator identity uncertain"
        );
        d.coordinator = new_coordinator;
        a.save(s, &d)?;
    }
    if matches!(d.stage.as_str(), "spawning_new" | "spawning_old") {
        let target = if d.stage == "spawning_new" {
            &s.to_generation
        } else {
            &s.from_generation
        };
        let (live, _) = owner(a, s, target)?;
        ensure!(
            Some(live.supervisor.pid) == d.spawn_pid
                && Some(&live.supervisor) == d.spawn_identity.as_ref(),
            "Interrupted spawn identity unavailable"
        );
        d.current = live;
        d.active_id = d.verify_id.clone();
        d.ready = None;
        save_stage(a, s, &mut d, "candidate_unquiesced")?;
    }
    if matches!(d.stage.as_str(), "rolled_back" | "verified")
        && d.current.generation == s.from_generation
    {
        let health = ensure_owner(a, s, &d)?;
        ensure!(
            a.rpc(&s.state_directory, "routes/read", json!({}))?["routePreferences"]
                == d.routes["routePreferences"]
                && health["remoteEnabled"] == d.prior_remote
                && health["acceptingWrites"] == true,
            "Recovery readback changed"
        );
        if let Some(cp) = &d.ready {
            image(a, &s.state_directory, cp, &d.computer_id)?;
        }
        return Ok(Verification {
            operation_id: s.operation_id.clone(),
            generation: s.from_generation.clone(),
            control_protocol: 1,
            routes_read: true,
            checkpoint_sha256: s.checkpoint.as_ref().map(|c| c.saved_sha256.clone()),
            native_tasks_preserved: true,
            components_verified: true,
        });
    }
    let sup = a.presence(&d.current.supervisor);
    let child = a.presence(&d.current.agent);
    ensure!(
        sup != maintenance::Presence::Unknown && child != maintenance::Presence::Unknown,
        "Recovery owner unknown"
    );
    if sup == maintenance::Presence::Alive && child == maintenance::Presence::Alive {
        ensure_owner(a, s, &d)?;
        let existing = a.ack(&s.state_directory)?;
        let action = if existing
            .as_ref()
            .is_some_and(|j| j.request.supervisor == d.current.supervisor)
        {
            Action::Recover
        } else {
            Action::Enter
        };
        ack(a, s, &d, action)?;
        let r = get_receipt(a, s, &d, "upgrade/status", None);
        if let Ok(r) = r {
            if matches!(r.phase.as_str(), "release_pending" | "released") {
                // Never replay a release whose reply/flush is unknown. Only the
                // flushed disk receipt and exact process exit authorize release.
                gone(a, &d.current.agent)?;
                recover_final(a, s, &mut d)?;
                release_proven_with(a, s, &d, &request(s, &d, Action::Release))?;
                ack(a, s, &d, Action::Release)?;
                gone(a, &d.current.supervisor)?;
                a.locks_free(s)?;
                save_stage(a, s, &mut d, "released")?;
            } else if r.phase != "cancelled" {
                let cancelled = get_receipt(a, s, &d, "upgrade/cancel", None)?;
                ensure!(
                    cancelled.phase == "cancelled"
                        && cancelled.accepting_writes
                        && cancelled.remote_enabled == d.prior_remote,
                    "Cancellation not restored"
                );
            }
        } else {
            // Only a pre-quiesce context has no agent operation; errors after
            // side effects are uncertainty, never permission to reopen/start.
            ensure!(
                matches!(
                    d.stage.as_str(),
                    "prepared" | "entering" | "started" | "candidate_unquiesced"
                ),
                "Handoff status unavailable during recovery"
            );
        }
        if d.current.generation == s.from_generation
            && a.presence(&d.current.agent) == maintenance::Presence::Alive
        {
            let health = ensure_owner(a, s, &d)?;
            ensure!(
                health["remoteEnabled"] == d.prior_remote && health["acceptingWrites"] == true,
                "Old gate readback failed"
            );
            ensure!(
                a.rpc(&s.state_directory, "routes/read", json!({}))?["routePreferences"]
                    == d.routes["routePreferences"],
                "Old routes changed"
            );
            let previous = d
                .ready
                .as_ref()
                .map(|cp| image(a, &s.state_directory, cp, &d.computer_id))
                .transpose()?;
            // Cancellation may reopen writes, so an earlier ready image is not
            // proof of current safety. Take a fresh inventory under maintenance.
            d.verify_id = uuid::Uuid::new_v4().to_string();
            d.active_id = d.verify_id.clone();
            save_stage(a, s, &mut d, "recovery_verifying")?;
            let cp = ready(a, s, &d)?;
            let current = image(a, &s.state_directory, &cp, &d.computer_id)?;
            if let Some(previous) = previous {
                successor(&previous, &current)?;
            }
            d.ready = Some(cp.clone());
            a.save(s, &d)?;
            let cancelled = get_receipt(a, s, &d, "upgrade/cancel", Some(&cp))?;
            ensure!(
                cancelled.phase == "cancelled"
                    && cancelled.accepting_writes
                    && cancelled.remote_enabled == d.prior_remote,
                "Recovery inventory fence not cancelled"
            );
            let restored = ensure_owner(a, s, &d)?;
            ensure!(
                restored["acceptingWrites"] == true && restored["remoteEnabled"] == d.prior_remote,
                "Old admission readback failed"
            );
            ensure!(
                a.rpc(&s.state_directory, "routes/read", json!({}))?["routePreferences"]
                    == d.routes["routePreferences"],
                "Old routes readback changed"
            );
            ack(a, s, &d, Action::Cancel)?;
            save_stage(a, s, &mut d, "rolled_back")?;
            return Ok(Verification {
                operation_id: s.operation_id.clone(),
                generation: s.from_generation.clone(),
                control_protocol: 1,
                routes_read: true,
                checkpoint_sha256: s.checkpoint.as_ref().map(|c| c.saved_sha256.clone()),
                native_tasks_preserved: true,
                components_verified: true,
            });
        }
        if a.presence(&d.current.agent) == maintenance::Presence::Alive {
            d.active_id = d.rollback_id.clone();
            d.ready = None;
            save_stage(a, s, &mut d, "rollback_quiescing")?;
            let cp = ready(a, s, &d)?;
            d.ready = Some(cp);
            a.save(s, &d)?;
            stopped(a, s, &mut d)?;
        }
    } else if sup == maintenance::Presence::Alive {
        // Agent reply flush may have completed before coordinator persistence.
        recover_final(a, s, &mut d)?;
        ack(a, s, &d, Action::Recover)?;
        release_proven_with(a, s, &d, &request(s, &d, Action::Release))?;
        ack(a, s, &d, Action::Release)?;
        gone(a, &d.current.supervisor)?;
    } else {
        ensure!(
            child == maintenance::Presence::Gone,
            "Orphan agent still running"
        );
        recover_final(a, s, &mut d)?;
    }
    if matches!(
        d.stage.as_str(),
        "spawning_new" | "spawning_old" | "started" | "verifying" | "verified"
    ) && d.current.generation == s.from_generation
    {
        // Spawn with no recorded new owner is ambiguous even with a free lock:
        // a child may be between process creation and acquiring its lock.
        bail!("Interrupted successor identity requires recovery evidence");
    }
    launch(a, s, &mut d, &s.from_generation)?;
    verify(a, s, &mut d, &s.from_generation)
}
fn recover_final(a: &mut impl Adapter, s: &UpgradeStatus, d: &mut Durable) -> Result<()> {
    let v: Value = decode(a, &s.state_directory.join("handoff/state.json"), MAX_IMAGE)?;
    let r: Receipt = serde_json::from_value(v["operations"][&d.active_id]["receipt"].clone())?;
    r.bind(&d.active_id, &d.current.run)?;
    ensure!(
        v["protocol"] == 1
            && v["generation"] == d.current.run
            && r.phase == "released"
            && r.shutdown_requested,
        "Interrupted release not flushed"
    );
    let final_cp = r.safe()?.clone();
    ensure!(
        d.final_checkpoint.as_ref().is_none_or(|cp| cp == &final_cp),
        "Recovered final checkpoint changed"
    );
    let before = image(
        a,
        &s.state_directory,
        d.ready.as_ref().context("Ready checkpoint absent")?,
        &d.computer_id,
    )?;
    let after = image(a, &s.state_directory, &final_cp, &d.computer_id)?;
    successor(&before, &after)?;
    d.final_checkpoint = Some(final_cp);
    a.save(s, d)?;
    Ok(())
}

/// Successor supervisor pauses publication through the coordinator terminal
/// journal, even before its maintenance Enter arrives. Agent spawn is allowed.
pub(crate) fn routes_may_reconcile(state: &Path) -> bool {
    super::read_status(state)
        .is_ok_and(|s| s.is_none_or(|s| s.phase.terminal() && s.phase != super::Phase::Failed))
}

#[cfg(test)]
#[path = "../tests/upgrade_local/unit.rs"]
mod tests;
