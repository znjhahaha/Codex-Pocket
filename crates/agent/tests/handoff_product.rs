//! Real agent process acceptance in disposable state. No model, native runtime,
//! installed configuration, supervisor, CLI fallback or production socket is used.
//! Run: cargo test --locked --offline -p pocket-agent --test handoff_product -- --nocapture
use pocket_agent::{
    auth::DeviceStore,
    config::{Config, routes::RoutePreferences},
    storage,
};
use pocket_core::{
    directory::RuntimeId,
    ledger::{Begin, CommandKey, CommandLedger, LedgerConfig, Status},
};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    net::{SocketAddr, TcpStream},
    path::{Path, PathBuf},
    process::{Child, Command, ExitStatus, Stdio},
    sync::{Arc, Barrier, OnceLock},
    thread,
    time::{Duration, Instant},
};
use uuid::Uuid;

const IO_TIMEOUT: Duration = Duration::from_secs(10);
const START_TIMEOUT: Duration = Duration::from_secs(20);
const EXIT_TIMEOUT: Duration = Duration::from_secs(40);
const PROTECTED: &[&str] = &[
    "config.json",
    "devices.json",
    "identity/certificate.der",
    "identity/private-key.der",
    "drafts/unsent.json",
    "desktop/desired.json",
];

fn hash(path: &Path) -> String {
    hex::encode(Sha256::digest(fs::read(path).unwrap()))
}
fn read_json(path: &Path) -> Value {
    serde_json::from_slice(&fs::read(path).unwrap()).unwrap()
}
fn save_json(path: &Path, value: &Value) {
    storage::atomic_write(path, &serde_json::to_vec(value).unwrap()).unwrap();
}
// Preserve the identity file ACLs while replacing fixture bytes, so this probe
// exercises checkpoint validation instead of malformed file permissions.
fn rewrite_existing(path: &Path, bytes: &[u8]) {
    let mut file = OpenOptions::new()
        .write(true)
        .truncate(true)
        .open(path)
        .unwrap();
    file.write_all(bytes).unwrap();
    file.sync_all().unwrap();
}
fn params(generation: &Value) -> Value {
    json!({"id":Uuid::new_v4(),"fromGeneration":generation,"checkpoint":null})
}
fn success(value: Value) -> Value {
    assert!(value.get("error").is_none(), "local error: {value}");
    value
        .get("result")
        .expect("complete result envelope")
        .clone()
}
fn error(value: &Value, code: &str) {
    assert_eq!(value["error"]["code"], code, "{value}");
}
fn ledger_content(state: &Path) -> Value {
    let mut value = read_json(&state.join("commands.json"));
    value.as_object_mut().unwrap().remove("last_now");
    value
}
fn lock_available(state: &Path) -> bool {
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .open(state.join("agent.lock"))
        .unwrap();
    match fs2::FileExt::try_lock_exclusive(&file) {
        Ok(()) => {
            fs2::FileExt::unlock(&file).unwrap();
            true
        }
        Err(e)
            if e.kind() == std::io::ErrorKind::WouldBlock
                || e.raw_os_error() == fs2::lock_contended_error().raw_os_error() =>
        {
            false
        }
        Err(e) => panic!("unexpected fixture lock error: {e}"),
    }
}

#[derive(Clone)]
struct Local {
    port: u16,
    token: String,
}
impl Local {
    fn open(state: &Path) -> Option<Self> {
        let value: Value =
            serde_json::from_slice(&fs::read(state.join("control.json")).ok()?).ok()?;
        if value["protocol"] != 1 {
            return None;
        }
        Some(Self {
            port: u16::try_from(value["port"].as_u64()?).ok()?,
            token: value["token"].as_str()?.into(),
        })
    }
    fn stream(&self) -> std::io::Result<TcpStream> {
        let stream =
            TcpStream::connect_timeout(&SocketAddr::from(([127, 0, 0, 1], self.port)), IO_TIMEOUT)?;
        stream.set_read_timeout(Some(IO_TIMEOUT))?;
        stream.set_write_timeout(Some(IO_TIMEOUT))?;
        Ok(stream)
    }
    fn send(&self, stream: &mut TcpStream, method: &str, params: &Value) -> std::io::Result<()> {
        let bytes =
            serde_json::to_vec(&json!({"token":self.token,"method":method,"params":params}))
                .unwrap();
        stream.write_all(&(bytes.len() as u32).to_be_bytes())?;
        stream.write_all(&bytes)?;
        stream.flush()
    }
    fn receive(stream: &mut TcpStream) -> std::io::Result<Value> {
        let mut length = [0; 4];
        stream.read_exact(&mut length)?;
        let length = u32::from_be_bytes(length) as usize;
        if length == 0 || length > 256 * 1024 {
            return Err(std::io::Error::other("invalid local frame length"));
        }
        let mut bytes = vec![0; length];
        stream.read_exact(&mut bytes)?;
        serde_json::from_slice(&bytes).map_err(std::io::Error::other)
    }
    fn try_call(&self, method: &str, params: &Value) -> std::io::Result<Value> {
        let mut stream = self.stream()?;
        self.send(&mut stream, method, params)?;
        Self::receive(&mut stream)
    }
    fn call(&self, method: &str, params: &Value) -> Value {
        self.try_call(method, params).unwrap()
    }
    fn ok(&self, method: &str, params: &Value) -> Value {
        success(self.call(method, params))
    }
}

struct Fixture {
    temp: tempfile::TempDir,
    state: PathBuf,
    binary: PathBuf,
    baseline: BTreeMap<String, String>,
    ledger: Value,
    evidence: Vec<Value>,
    case: &'static str,
}
impl Fixture {
    fn new(case: &'static str) -> Self {
        let temp = tempfile::Builder::new()
            .prefix("pocket-handoff-product-")
            .tempdir()
            .unwrap();
        let state = temp.path().join("state");
        storage::private_directory(&state).unwrap();
        let config = Config {
            protocol: 1,
            computer_id: Uuid::new_v4().to_string(),
            computer_name: "Handoff Fixture".into(),
            relay_url: String::new(),
            room: Uuid::new_v4().simple().to_string(),
            helper_token: "fixture-helper-secret".into(),
            phone_token: "fixture-phone-secret".into(),
            runtime_directories: vec![],
            codex_homes: vec![],
            route_preferences: Some(RoutePreferences::default()),
        };
        config.save(&state).unwrap();
        let _identity =
            pocket_tunnel::ServerIdentity::load_or_create(state.join("identity")).unwrap();
        let mut devices = DeviceStore::default();
        devices
            .add(
                "fixture-phone".into(),
                "Fixture Phone".into(),
                "fixture-device-secret",
                12,
            )
            .unwrap();
        storage::atomic_write(
            &state.join("devices.json"),
            &serde_json::to_vec(&devices).unwrap(),
        )
        .unwrap();
        save_json(
            &state.join("drafts/unsent.json"),
            &json!({"schema":1,"text":"unsent fixture draft","attachments":[]}),
        );
        save_json(
            &state.join("desktop/desired.json"),
            &json!({"enabled":false}),
        );
        let mut ledger = CommandLedger::new(
            RuntimeId {
                computer_id: config.computer_id,
                runtime_id: "broker".into(),
            },
            LedgerConfig {
                capacity: 50000,
                terminal_ttl: u64::MAX,
            },
            1,
            1,
        )
        .unwrap();
        for (id, outcome) in [
            (
                "already-accepted",
                Status::Accepted {
                    native_result: json!({"fixture":true,"originalReceipt":"keep"}),
                },
            ),
            (
                "already-rejected",
                Status::Rejected {
                    reason: "Fixture native rejection".into(),
                },
            ),
        ] {
            let key = CommandKey {
                caller_id: "fixture-phone".into(),
                command_id: id.into(),
            };
            assert_eq!(
                ledger.begin(key.clone(), id.as_bytes(), 1).unwrap(),
                Begin::Execute
            );
            ledger.finish(&key, outcome, 1).unwrap();
        }
        storage::atomic_write(
            &state.join("commands.json"),
            ledger.snapshot_json().as_bytes(),
        )
        .unwrap();
        let bin_dir = temp.path().join("bin");
        fs::create_dir(&bin_dir).unwrap();
        let binary = bin_dir.join(if cfg!(windows) {
            "pocket-agent.exe"
        } else {
            "pocket-agent"
        });
        fs::copy(env!("CARGO_BIN_EXE_pocket-agent"), &binary).unwrap();
        for name in ["home", "appdata", "localappdata", "tmp", "codex-home"] {
            fs::create_dir(temp.path().join(name)).unwrap();
        }
        let baseline = PROTECTED
            .iter()
            .map(|name| (name.to_string(), hash(&state.join(name))))
            .collect();
        let saved_ledger = ledger_content(&state);
        let mut fixture = Self {
            temp,
            state,
            binary,
            baseline,
            ledger: saved_ledger,
            evidence: vec![],
            case,
        };
        fixture.record("fixture",json!({"binarySha256":hash(&fixture.binary),"runtimeDirectories":[],"codexHomes":[],"remoteDesired":false,"protectedSha256":fixture.baseline}));
        fixture
    }
    fn record(&mut self, stage: &str, evidence: Value) {
        self.evidence
            .push(json!({"stage":stage,"evidence":evidence}));
    }
    fn preserved(&self) {
        for (name, expected) in &self.baseline {
            assert_eq!(
                hash(&self.state.join(name)),
                *expected,
                "changed protected file: {name}"
            );
        }
        assert_eq!(
            ledger_content(&self.state),
            self.ledger,
            "original ledger keys/bodies/receipts must survive"
        );
        assert!(!self.state.join("assistant/profile.json").exists());
        assert!(!self.state.join("managed-runtimes").exists());
        assert!(!self.temp.path().join("codex-home/config.toml").exists());
    }
    fn spawn(&self, request: Option<(&Value, &str)>) -> Agent {
        let label = Uuid::new_v4().to_string();
        let stdout = self.temp.path().join(format!("{label}.stdout"));
        let stderr = self.temp.path().join(format!("{label}.stderr"));
        let mut command = Command::new(&self.binary);
        command
            .args(["run", "--supervised", "--state"])
            .arg(&self.state)
            .current_dir(self.temp.path())
            .env_clear();
        for key in [
            "SystemRoot",
            "WINDIR",
            "PATH",
            "PATHEXT",
            "COMSPEC",
            "SYSTEMDRIVE",
        ] {
            if let Some(value) = std::env::var_os(key) {
                command.env(key, value);
            }
        }
        for (key, name) in [
            ("USERPROFILE", "home"),
            ("HOME", "home"),
            ("APPDATA", "appdata"),
            ("LOCALAPPDATA", "localappdata"),
            ("TEMP", "tmp"),
            ("TMP", "tmp"),
            ("CODEX_HOME", "codex-home"),
        ] {
            command.env(key, self.temp.path().join(name));
        }
        if let Some((cp, mode)) = request {
            command
                .env("POCKET_AGENT_HANDOFF_CHECKPOINT", cp.to_string())
                .env("POCKET_AGENT_HANDOFF_MODE", mode);
        }
        command
            .stdin(Stdio::null())
            .stdout(Stdio::from(File::create(&stdout).unwrap()))
            .stderr(Stdio::from(File::create(&stderr).unwrap()));
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000);
        }
        Agent {
            child: command.spawn().unwrap(),
            state: self.state.clone(),
            stdout,
            stderr,
            exited: false,
        }
    }
    fn checkpoint(&self, cp: &Value) -> Value {
        let id = Uuid::parse_str(cp["id"].as_str().unwrap()).unwrap();
        let path = self.state.join("handoff").join(format!("{id}.json"));
        assert_eq!(hash(&path), cp["hash"].as_str().unwrap());
        let image = read_json(&path);
        assert_eq!(image["generation"], cp["generation"]);
        assert_eq!(
            image["snapshot"]["computerId"],
            read_json(&self.state.join("config.json"))["computerId"]
        );
        assert_eq!(image["snapshot"]["runtimes"], json!({}));
        assert_eq!(image["snapshot"]["events"], json!({}));
        assert_eq!(image["snapshot"]["pending"], json!({}));
        let protected = image["snapshot"]["protectedFiles"].as_object().unwrap();
        for name in PROTECTED {
            assert!(
                protected.contains_key(*name),
                "checkpoint contract omits {name}"
            );
            if *name != "config.json" {
                assert_eq!(
                    protected[*name], self.baseline[*name],
                    "checkpoint changed preserved evidence for {name}"
                );
            }
        }
        assert!(protected.contains_key("assistant/profile.json"));
        assert!(protected["assistant/profile.json"].is_null());
        assert!(!protected.keys().any(|name| name.starts_with("logs/")
            || name.starts_with("managed-runtimes/")
            || name == "agent.lock"));
        let mut ledger = image["snapshot"]["ledger"].clone();
        ledger.as_object_mut().unwrap().remove("last_now");
        assert_eq!(ledger, self.ledger);
        image
    }
    fn release(&mut self, agent: &mut Agent, local: &Local, operation: &Value) -> Value {
        let ready = local.ok("upgrade/quiesce", operation);
        assert_ready(&ready);
        assert_eq!(
            local.ok("upgrade/quiesce", operation),
            ready,
            "duplicate quiesce receipt"
        );
        let cp = ready["checkpoint"].clone();
        self.checkpoint(&cp);
        let mut release = operation.clone();
        release["checkpoint"] = cp;
        let receipt = local.ok("upgrade/release", &release);
        assert_eq!(receipt["phase"], "release_pending");
        assert_eq!(receipt["safeToExit"], true);
        assert_eq!(
            receipt["shutdownRequested"], false,
            "wire reply precedes post-flush shutdown"
        );
        let final_cp = receipt["checkpoint"].clone();
        assert_ne!(final_cp, release["checkpoint"]);
        self.checkpoint(&final_cp);
        self.record("fullReleaseReply", receipt.clone());
        let exit = agent.wait_exit(EXIT_TIMEOUT);
        assert!(exit.success(), "{}", agent.logs());
        self.assert_released(&release, &final_cp);
        self.record("naturalExit",json!({"pid":agent.child.id(),"success":true,"lockReleased":true,"controlRemoved":true}));
        final_cp
    }
    fn assert_released(&self, release: &Value, cp: &Value) {
        assert!(lock_available(&self.state));
        assert!(!self.state.join("control.json").exists());
        let journal = read_json(&self.state.join("handoff/state.json"));
        let entry = &journal["operations"][release["id"].as_str().unwrap()];
        assert_eq!(entry["receipt"]["phase"], "released");
        assert_eq!(entry["receipt"]["shutdownRequested"], true);
        assert_eq!(entry["releaseInput"], release["checkpoint"]);
        assert_eq!(entry["receipt"]["checkpoint"], *cp);
        assert_eq!(journal["recoveryCheckpoint"], *cp);
        self.preserved();
    }
    fn adopted(
        &mut self,
        local: &Local,
        cp: &Value,
        previous_generation: &Value,
        routes: &Value,
    ) -> Value {
        let caps = local.ok("upgrade/capabilities", &json!({}));
        assert_caps(&caps);
        assert_ne!(caps["generation"], *previous_generation);
        let journal = read_json(&self.state.join("handoff/state.json"));
        assert_eq!(journal["generation"], caps["generation"]);
        assert_eq!(journal["recoveryCheckpoint"], *cp);
        assert!(
            journal["operations"]
                .as_object()
                .unwrap()
                .values()
                .any(|v| v["receipt"]["phase"] == "released" && v["receipt"]["checkpoint"] == *cp)
        );
        assert_eq!(local.ok("routes/read", &json!({})), *routes);
        let status = local.ok("status", &json!({}));
        assert_empty_status(&status, true);
        self.preserved();
        self.record("adopted",json!({"generation":caps["generation"],"recoveryCheckpoint":cp,"status":status,"routes":routes}));
        caps["generation"].clone()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        {
            static DEFAULT_EVIDENCE: OnceLock<PathBuf> = OnceLock::new();
            let root = std::env::var_os("POCKET_HANDOFF_PRODUCT_EVIDENCE_ROOT")
                .map(PathBuf::from)
                .unwrap_or_else(|| {
                    DEFAULT_EVIDENCE
                        .get_or_init(|| {
                            let root = Path::new(env!("CARGO_MANIFEST_DIR"))
                                .parent()
                                .unwrap()
                                .parent()
                                .unwrap()
                                .join(".local/handoff-product-tests")
                                .join(Uuid::new_v4().simple().to_string());
                            eprintln!("Handoff product evidence: {}", root.display());
                            root
                        })
                        .clone()
                });
            fs::create_dir_all(&root).unwrap();
            save_json(
                &root.join(format!("{}-{}.json", self.case, Uuid::new_v4())),
                &json!({"case":self.case,"passed":!thread::panicking(),"events":self.evidence}),
            );
        }
    }
}
struct Agent {
    child: Child,
    state: PathBuf,
    stdout: PathBuf,
    stderr: PathBuf,
    exited: bool,
}
impl Agent {
    fn logs(&self) -> String {
        format!(
            "stdout: {}\nstderr: {}",
            fs::read_to_string(&self.stdout).unwrap_or_default(),
            fs::read_to_string(&self.stderr).unwrap_or_default()
        )
    }
    fn ready(&mut self) -> Local {
        let until = Instant::now() + START_TIMEOUT;
        loop {
            if let Some(exit) = self.child.try_wait().unwrap() {
                self.exited = true;
                panic!(
                    "fixture agent exited before control publication ({exit}): {}",
                    self.logs()
                );
            }
            if let Some(local) = Local::open(&self.state) {
                if local.try_call("upgrade/capabilities", &json!({})).is_ok() {
                    assert!(!lock_available(&self.state));
                    return local;
                }
            }
            assert!(Instant::now() < until, "startup timeout: {}", self.logs());
            thread::sleep(Duration::from_millis(40));
        }
    }
    fn wait_exit(&mut self, timeout: Duration) -> ExitStatus {
        let until = Instant::now() + timeout;
        loop {
            if let Some(exit) = self.child.try_wait().unwrap() {
                self.exited = true;
                return exit;
            }
            assert!(
                Instant::now() < until,
                "natural exit timeout: {}",
                self.logs()
            );
            thread::sleep(Duration::from_millis(20));
        }
    }
    fn rejected_start(&mut self) {
        let until = Instant::now() + EXIT_TIMEOUT;
        loop {
            assert!(
                !self.state.join("control.json").exists(),
                "failed adoption published a controller"
            );
            if let Some(exit) = self.child.try_wait().unwrap() {
                self.exited = true;
                assert!(!exit.success(), "invalid checkpoint was accepted");
                break;
            }
            assert!(
                Instant::now() < until,
                "failed startup did not return: {}",
                self.logs()
            );
            thread::sleep(Duration::from_millis(40));
        }
        assert!(lock_available(&self.state));
        self.rejection_record();
    }

    fn rejection_record(&self) -> Value {
        let record = read_json(&self.state.join("startup-failure.json"));
        assert_eq!(record["phase"], "handoff");
        assert_eq!(record["code"], "handoff_recovery_failed");
        assert_eq!(record["pid"], self.child.id());
        assert_eq!(record["schema"], 1);
        record
    }

    fn controller_or_rejection(&mut self) -> Option<Local> {
        let until = Instant::now() + EXIT_TIMEOUT;
        loop {
            if let Some(exit) = self.child.try_wait().unwrap() {
                self.exited = true;
                assert!(!exit.success(), "invalid checkpoint was accepted");
                assert!(lock_available(&self.state));
                self.rejection_record();
                return None;
            }
            if let Some(local) = Local::open(&self.state) {
                if local.try_call("upgrade/capabilities", &json!({})).is_ok() {
                    return Some(local);
                }
            }
            assert!(
                Instant::now() < until,
                "startup did not settle: {}",
                self.logs()
            );
            thread::sleep(Duration::from_millis(40));
        }
    }
}
impl Drop for Agent {
    fn drop(&mut self) {
        if self.exited || self.child.try_wait().ok().flatten().is_some() {
            return;
        }
        // Cleanup may address only this owned Child handle of the copied fixture
        // executable. Never search by name, touch installed state, or count this
        // emergency path as graceful acceptance.
        if let Some(local) = Local::open(&self.state) {
            let _ = (|| -> Option<()> {
                let caps = local.try_call("upgrade/capabilities", &json!({})).ok()?;
                let operation = params(&caps["result"]["generation"]);
                let ready = local.try_call("upgrade/quiesce", &operation).ok()?;
                let mut release = operation;
                release["checkpoint"] = ready["result"]["checkpoint"].clone();
                if release["checkpoint"].is_null() {
                    return None;
                }
                local.try_call("upgrade/release", &release).ok()?;
                Some(())
            })();
            let until = Instant::now() + Duration::from_secs(3);
            while Instant::now() < until {
                if self.child.try_wait().ok().flatten().is_some() {
                    return;
                }
                thread::sleep(Duration::from_millis(30));
            }
        }
        eprintln!(
            "FAILED acceptance: emergency cleanup of owned temporary child pid {}",
            self.child.id()
        );
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}
fn assert_caps(caps: &Value) {
    assert_eq!(caps["protocol"], 1);
    assert_eq!(caps["supported"], true);
    assert_eq!(caps["owner"], "pocket-agent");
    assert_eq!(caps["shutdown"], "after_reply_flush");
    assert_eq!(caps["writeFence"], true);
    assert!(
        !Uuid::parse_str(caps["generation"].as_str().unwrap())
            .unwrap()
            .is_nil()
    );
    assert_eq!(
        caps["methods"],
        json!([
            "upgrade/capabilities",
            "upgrade/quiesce",
            "upgrade/status",
            "upgrade/release",
            "upgrade/cancel"
        ])
    );
}
fn assert_empty_status(status: &Value, accepting: bool) {
    assert_eq!(status["runtimes"], json!([]));
    assert_eq!(status["discoveryErrors"], json!([]));
    assert_eq!(status["remoteEnabled"], false);
    assert_eq!(status["acceptingWrites"], accepting);
    assert_eq!(status["connected"], false);
}
fn assert_ready(receipt: &Value) {
    assert_eq!(receipt["phase"], "ready", "{receipt}");
    assert_eq!(receipt["safeToExit"], true);
    assert_eq!(receipt["nativeTasksPreserved"], true);
    assert_eq!(receipt["unconfirmedWrites"], 0);
    assert_eq!(receipt["blockers"], json!([]));
    assert_eq!(receipt["remoteEnabled"], false);
    assert_eq!(receipt["acceptingWrites"], false);
}

#[test]
fn release_successor_cancel_rollback_and_automatic_recovery() {
    let mut f = Fixture::new("release-successor-rollback");
    let mut source = f.spawn(None);
    let local = source.ready();
    let caps = local.ok("upgrade/capabilities", &json!({}));
    assert_caps(&caps);
    let routes = local.ok("routes/read", &json!({}));
    assert_eq!(routes["initialized"], true);
    assert_empty_status(&local.ok("status", &json!({})), true);
    f.preserved();
    let cp1 = f.release(&mut source, &local, &params(&caps["generation"]));
    let cp1_bytes = fs::read(
        f.state
            .join("handoff")
            .join(format!("{}.json", cp1["id"].as_str().unwrap())),
    )
    .unwrap();
    let mut bad = cp1.clone();
    bad["hash"] = json!("0".repeat(64));
    let mut rejected = f.spawn(Some((&bad, "successor")));
    rejected.rejected_start();
    f.preserved();
    f.record(
        "badHashRejected",
        json!({"noController":true,"lockReleased":true,"diagnostic":rejected.rejection_record()}),
    );
    let mut successor = f.spawn(Some((&cp1, "successor")));
    let live = successor.ready();
    let next = f.adopted(&live, &cp1, &caps["generation"], &routes);
    error(
        &live.call("upgrade/quiesce", &params(&caps["generation"])),
        "stale_generation",
    );
    let operation = params(&next);
    let ready = live.ok("upgrade/quiesce", &operation);
    assert_ready(&ready);
    error(&live.call("pair", &json!({})), "write_fenced");
    error(
        &live.call(
            "routes/configure",
            &json!({"routePreferences":RoutePreferences::default()}),
        ),
        "write_fenced",
    );
    thread::sleep(Duration::from_millis(650));
    assert_empty_status(&live.ok("status", &json!({})), false);
    assert_eq!(live.ok("upgrade/status", &operation), ready);
    let cancelled = live.ok("upgrade/cancel", &operation);
    assert_eq!(cancelled["phase"], "cancelled");
    assert_eq!(cancelled["remoteEnabled"], false);
    assert_eq!(cancelled["acceptingWrites"], true);
    assert_eq!(live.ok("upgrade/cancel", &operation), cancelled);
    error(
        &live.call("upgrade/quiesce", &operation),
        "handoff_conflict",
    );
    assert_empty_status(&live.ok("status", &json!({})), true);
    f.preserved();
    f.record(
        "cancelAndDuplicates",
        json!({"cancel":cancelled,"writeFence":true,"remoteStayedDisabledAfterPeriodicTicks":true}),
    );
    let cp2 = f.release(&mut successor, &live, &params(&next));
    let mut rollback = f.spawn(Some((&cp2, "rollback")));
    let live = rollback.ready();
    let rollback_generation = f.adopted(&live, &cp2, &next, &routes);
    let cp3 = f.release(&mut rollback, &live, &params(&rollback_generation));
    // A normal launch following release must discover the latest durable ref,
    // rather than rejecting every new run UUID or reusing cp1.
    let mut automatic = f.spawn(None);
    let live = automatic.ready();
    let generation = f.adopted(&live, &cp3, &rollback_generation, &routes);
    f.release(&mut automatic, &live, &params(&generation));
    assert_eq!(
        fs::read(
            f.state
                .join("handoff")
                .join(format!("{}.json", cp1["id"].as_str().unwrap()))
        )
        .unwrap(),
        cp1_bytes
    );
}

#[test]
fn authenticated_validation_and_concurrent_quiesce_are_deterministic() {
    let mut f = Fixture::new("authentication-concurrency");
    let mut agent = f.spawn(None);
    let local = agent.ready();
    let mut invalid = local.clone();
    invalid.token = "incorrect-fixture-token".into();
    assert!(
        invalid
            .try_call("upgrade/capabilities", &json!({}))
            .is_err(),
        "unauthenticated connection must close"
    );
    error(
        &local.call("upgrade/capabilities", &json!({"unexpected":true})),
        "invalid_params",
    );
    error(&local.call("upgrade/unknown", &json!({})), "unsupported");
    let generation = local.ok("upgrade/capabilities", &json!({}))["generation"].clone();
    let operation = params(&generation);
    for malformed in [
        json!({"id":operation["id"],"fromGeneration":generation}),
        json!({"id":"not-a-uuid","fromGeneration":generation,"checkpoint":null}),
        json!({"id":operation["id"],"fromGeneration":generation,"checkpoint":null,"extra":true}),
    ] {
        error(&local.call("upgrade/quiesce", &malformed), "invalid_params");
    }
    let barrier = Arc::new(Barrier::new(8));
    let workers = (0..8)
        .map(|_| {
            let local = local.clone();
            let operation = operation.clone();
            let barrier = barrier.clone();
            thread::spawn(move || {
                barrier.wait();
                local.ok("upgrade/quiesce", &operation)
            })
        })
        .collect::<Vec<_>>();
    let replies = workers
        .into_iter()
        .map(|w| w.join().unwrap())
        .collect::<Vec<_>>();
    assert!(
        replies
            .iter()
            .all(|v| v["id"] == operation["id"] && v["acceptingWrites"] == false)
    );
    // An overlapping capture may report draining. Repeated status converges to
    // one immutable ready checkpoint; no competing operation may enter.
    let ready = local.ok("upgrade/status", &operation);
    assert_ready(&ready);
    assert_eq!(local.ok("upgrade/quiesce", &operation), ready);
    error(
        &local.call("upgrade/quiesce", &params(&generation)),
        "handoff_conflict",
    );
    f.record(
        "concurrentQuiesce",
        json!({"requests":8,"responses":replies,"converged":ready}),
    );
    let mut wrong = operation.clone();
    wrong["checkpoint"] = ready["checkpoint"].clone();
    wrong["checkpoint"]["hash"] = json!("f".repeat(64));
    error(
        &local.call("upgrade/release", &wrong),
        "checkpoint_mismatch",
    );
    f.release(&mut agent, &local, &operation);
}

#[test]
fn actual_local_config_writes_racing_quiesce_match_checkpoint() {
    let mut f = Fixture::new("local-write-quiesce");
    let mut agent = f.spawn(None);
    let local = agent.ready();
    let generation = local.ok("upgrade/capabilities", &json!({}))["generation"].clone();
    let mut outcomes = vec![];
    for _ in 0..12 {
        let operation = params(&generation);
        let barrier = Arc::new(Barrier::new(2));
        let writer = {
            let local = local.clone();
            let barrier = barrier.clone();
            thread::spawn(move || {
                let mut prefs = RoutePreferences::default();
                prefs.policy = pocket_agent::config::routes::RoutePolicy::Public;
                barrier.wait();
                local.call("routes/configure", &json!({"routePreferences":prefs}))
            })
        };
        let quiesce = {
            let local = local.clone();
            let barrier = barrier.clone();
            let operation = operation.clone();
            thread::spawn(move || {
                barrier.wait();
                local.ok("upgrade/quiesce", &operation)
            })
        };
        let write = writer.join().unwrap();
        let first = quiesce.join().unwrap();
        let until = Instant::now() + IO_TIMEOUT;
        let ready = loop {
            let receipt = local.ok("upgrade/status", &operation);
            if receipt["phase"] == "ready" {
                break receipt;
            }
            assert_eq!(receipt["phase"], "draining", "{receipt}");
            assert!(
                Instant::now() < until,
                "completed local write left handoff stuck"
            );
            thread::sleep(Duration::from_millis(20));
        };
        assert_ready(&ready);
        let image = f.checkpoint(&ready["checkpoint"]);
        assert_eq!(
            image["snapshot"]["protectedFiles"]["config.json"],
            hash(&f.state.join("config.json"))
        );
        if write.get("error").is_some() {
            error(&write, "write_fenced");
            assert_eq!(
                read_json(&f.state.join("config.json"))["routePreferences"]["policy"],
                "automatic"
            );
        } else {
            assert_eq!(write["result"]["policy"], "public");
            assert_eq!(
                read_json(&f.state.join("config.json"))["routePreferences"]["policy"],
                "public"
            );
        }
        outcomes.push(json!({"write":write,"initialQuiescePhase":first["phase"],"ready":ready,"checkpointConfigMatchesDisk":true}));
        assert_eq!(
            local.ok("upgrade/cancel", &operation)["acceptingWrites"],
            true
        );
        local.ok(
            "routes/configure",
            &json!({"routePreferences":RoutePreferences::default()}),
        );
        f.preserved();
    }
    f.record(
        "actualWriteQuiesceRaces",
        json!({"iterations":12,"outcomes":outcomes}),
    );
    f.release(&mut agent, &local, &params(&generation));
}

struct JournalObstruction {
    path: PathBuf,
    backup: PathBuf,
}
impl JournalObstruction {
    fn new(state: &Path) -> Self {
        let path = state.join("handoff/state.json");
        let backup = state.join("handoff/state.saved-product-test");
        fs::rename(&path, &backup).unwrap();
        fs::create_dir(&path).unwrap();
        Self { path, backup }
    }
}
impl Drop for JournalObstruction {
    fn drop(&mut self) {
        fs::remove_dir(&self.path).unwrap();
        fs::rename(&self.backup, &self.path).unwrap();
    }
}

#[test]
fn failed_cancel_keeps_fence_until_durable_retry() {
    let mut f = Fixture::new("failed-cancel");
    let mut agent = f.spawn(None);
    let local = agent.ready();
    let generation = local.ok("upgrade/capabilities", &json!({}))["generation"].clone();
    let operation = params(&generation);
    assert_ready(&local.ok("upgrade/quiesce", &operation));
    {
        let _obstruction = JournalObstruction::new(&f.state);
        error(&local.call("upgrade/cancel", &operation), "unavailable");
        assert_empty_status(&local.ok("status", &json!({})), false);
        error(&local.call("pair", &json!({})), "write_fenced");
        assert!(!lock_available(&f.state));
    }
    let cancelled = local.ok("upgrade/cancel", &operation);
    assert_eq!(cancelled["phase"], "cancelled");
    assert_eq!(cancelled["acceptingWrites"], true);
    assert_eq!(local.ok("upgrade/cancel", &operation), cancelled);
    // A harmless real configuration write proves admission has reopened.
    assert!(
        local.ok(
            "routes/configure",
            &json!({"routePreferences":RoutePreferences::default()})
        )["initialized"]
            == true
    );
    f.preserved();
    f.record("failedCancelRecovered", cancelled);
    f.release(&mut agent, &local, &params(&generation));
}

#[test]
fn protected_file_mismatch_fences_startup_and_same_checkpoint_allows_rollback() {
    let mut f = Fixture::new("protected-mismatch-rollback");
    let mut source = f.spawn(None);
    let local = source.ready();
    let generation = local.ok("upgrade/capabilities", &json!({}))["generation"].clone();
    let routes = local.ok("routes/read", &json!({}));
    let cp = f.release(&mut source, &local, &params(&generation));
    let path = f.state.join("config.json");
    let saved = fs::read(&path).unwrap();
    let mut changed = read_json(&path);
    changed["computerName"] = json!("Mismatch Fixture");
    save_json(&path, &changed);
    let mut rejected = f.spawn(Some((&cp, "successor")));
    rejected.rejected_start();
    assert_eq!(
        read_json(&path),
        changed,
        "startup must not roll protected configuration backwards"
    );
    storage::atomic_write(&path, &saved).unwrap();
    f.preserved();
    let mut rollback = f.spawn(Some((&cp, "rollback")));
    let live = rollback.ready();
    let next = f.adopted(&live, &cp, &generation, &routes);
    f.release(&mut rollback, &live, &params(&next));
    f.record(
        "protectedMismatchRollback",
        json!({"invalidStartupNoController":true,"samePredecessorCheckpointReused":true,"diagnostic":rejected.rejection_record()}),
    );
}

#[test]
fn newer_ledger_is_not_overwritten_and_rollback_can_retry() {
    let mut f = Fixture::new("ledger-mismatch-rollback");
    let mut source = f.spawn(None);
    let local = source.ready();
    let generation = local.ok("upgrade/capabilities", &json!({}))["generation"].clone();
    let routes = local.ok("routes/read", &json!({}));
    let cp = f.release(&mut source, &local, &params(&generation));
    let path = f.state.join("commands.json");
    let saved = fs::read(&path).unwrap();
    let mut newer = read_json(&path);
    newer["entries"][0]["status"]["Accepted"]["native_result"]["originalReceipt"] =
        json!("newer-receipt");
    save_json(&path, &newer);
    let mut rejected = f.spawn(Some((&cp, "successor")));
    rejected.rejected_start();
    assert_eq!(
        read_json(&path)["entries"],
        newer["entries"],
        "old checkpoint must not overwrite newer receipts"
    );
    storage::atomic_write(&path, &saved).unwrap();
    f.preserved();
    let mut rollback = f.spawn(Some((&cp, "rollback")));
    let live = rollback.ready();
    let next = f.adopted(&live, &cp, &generation, &routes);
    f.release(&mut rollback, &live, &params(&next));
    f.record(
        "ledgerMismatchRollback",
        json!({"newerReceiptsPreserved":true,"samePredecessorCheckpointReused":true,"diagnostic":rejected.rejection_record()}),
    );
}

#[test]
fn concurrent_release_reuses_one_final_checkpoint() {
    let mut f = Fixture::new("concurrent-release");
    let mut agent = f.spawn(None);
    let local = agent.ready();
    let generation = local.ok("upgrade/capabilities", &json!({}))["generation"].clone();
    let operation = params(&generation);
    let ready = local.ok("upgrade/quiesce", &operation);
    assert_ready(&ready);
    let mut release = operation;
    release["checkpoint"] = ready["checkpoint"].clone();
    let barrier = Arc::new(Barrier::new(4));
    let workers = (0..4)
        .map(|_| {
            let local = local.clone();
            let release = release.clone();
            let barrier = barrier.clone();
            let mut stream = local.stream().unwrap();
            thread::spawn(move || {
                barrier.wait();
                local
                    .send(&mut stream, "upgrade/release", &release)
                    .and_then(|_| Local::receive(&mut stream))
            })
        })
        .collect::<Vec<_>>();
    let results = workers
        .into_iter()
        .map(|w| w.join().unwrap())
        .collect::<Vec<_>>();
    let replies = results
        .iter()
        .filter_map(|r| r.as_ref().ok())
        .collect::<Vec<_>>();
    assert!(
        !replies.is_empty(),
        "at least the releasing controller receives a full frame"
    );
    let accepted = replies
        .iter()
        .filter(|value| value.get("result").is_some())
        .collect::<Vec<_>>();
    assert!(
        !accepted.is_empty(),
        "one releasing controller must receive a full success frame"
    );
    for value in &replies {
        if value.get("error").is_some() {
            // A contender that reached the serializer before the first commit
            // may be refused. It did not commit a different release.
            error(value, "not_ready");
        } else {
            assert_eq!(
                value["result"], accepted[0]["result"],
                "duplicate releases must bind one final checkpoint"
            );
        }
    }
    let cp = accepted[0]["result"]["checkpoint"].clone();
    f.checkpoint(&cp);
    assert!(agent.wait_exit(EXIT_TIMEOUT).success());
    f.assert_released(&release, &cp);
    f.record("concurrentRelease",json!({"requests":4,"completeReplies":replies.len(),"successfulReplies":accepted.len(),"serializerNotReady":replies.len()-accepted.len(),"transportClosures":results.len()-replies.len(),"receipt":accepted[0]["result"],"naturalExit":true,"lockReleased":true}));
}

// Acceptance regressions remain enabled: replacing protected directory bytes
// must fence startup, release the failed process lock, and permit rollback after
// restoring the exact predecessor fixture bytes.
fn changed_directory_file_must_fence_successor(identity: bool) {
    let mut f = Fixture::new(if identity {
        "identity-mismatch"
    } else {
        "draft-mismatch"
    });
    let mut source = f.spawn(None);
    let local = source.ready();
    let generation = local.ok("upgrade/capabilities", &json!({}))["generation"].clone();
    let routes = local.ok("routes/read", &json!({}));
    let cp = f.release(&mut source, &local, &params(&generation));
    let names = if identity {
        vec!["identity/certificate.der", "identity/private-key.der"]
    } else {
        vec!["drafts/unsent.json"]
    };
    let originals = names
        .iter()
        .map(|name| (f.state.join(name), fs::read(f.state.join(name)).unwrap()))
        .collect::<Vec<_>>();
    if identity {
        let other = f.temp.path().join("replacement-fixture-identity");
        let _ = pocket_tunnel::ServerIdentity::load_or_create(&other).unwrap();
        for name in ["certificate.der", "private-key.der"] {
            rewrite_existing(
                &f.state.join("identity").join(name),
                &fs::read(other.join(name)).unwrap(),
            );
        }
        let _ = pocket_agent::startup_diagnostics::load_existing_identity(&f.state)
            .expect("replacement fixture identity must pass the real startup identity loader");
    } else {
        save_json(
            &f.state.join("drafts/unsent.json"),
            &json!({"schema":1,"text":"different unsent draft","attachments":[]}),
        );
    }
    let image = f.checkpoint(&cp);
    let changed_hashes = names
        .iter()
        .map(|name| (name.to_string(), hash(&f.state.join(name))))
        .collect::<BTreeMap<_, _>>();
    let mut candidate = f.spawn(Some((&cp, "successor")));
    let published = candidate.controller_or_rejection();
    f.record("changedDirectoryFileStartup", json!({
        "changedFileSha256":changed_hashes,
        "checkpointProtectedFiles":image["snapshot"]["protectedFiles"],
        "controllerPublished":published.is_some(),
        "startupGeneration":published.as_ref().map(|local|local.ok("upgrade/capabilities",&json!({}))["generation"].clone()),
        "diagnostic":if published.is_none(){Some(candidate.rejection_record())}else{None}
    }));
    // Restore fixture bytes before cleanup. Even the failed regression releases
    // its own process through the authenticated contract and returns naturally.
    for (path, bytes) in originals {
        if identity {
            rewrite_existing(&path, &bytes);
        } else {
            storage::atomic_write(&path, &bytes).unwrap();
        }
    }
    if let Some(local) = published {
        let next = local.ok("upgrade/capabilities", &json!({}))["generation"].clone();
        f.release(&mut candidate, &local, &params(&next));
        panic!(
            "successor published control and writes after replacing {names:?}; checkpoint is missing protected directory evidence"
        );
    }
    f.preserved();
    let mut rollback = f.spawn(Some((&cp, "rollback")));
    let local = rollback.ready();
    let next = f.adopted(&local, &cp, &generation, &routes);
    f.release(&mut rollback, &local, &params(&next));
}

#[test]
fn changed_computer_certificate_must_not_activate_successor() {
    changed_directory_file_must_fence_successor(true);
}

#[test]
fn changed_unsent_draft_must_not_activate_successor() {
    changed_directory_file_must_fence_successor(false);
}
