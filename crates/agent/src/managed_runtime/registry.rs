use super::{Installation, ManagedRuntime, SelectedProfile, installation::physical};
use anyhow::{Context, Result, bail, ensure};
use fs2::FileExt;
use pocket_core::native_process::NativeProcess;
use pocket_runtime_ipc::{Descriptor, HostClient};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{File, OpenOptions},
    path::{Path, PathBuf},
    process::Stdio,
    time::Duration,
};

#[derive(Clone, Debug)]
pub struct Registry {
    root: PathBuf,
}
impl Registry {
    pub fn new(root: impl AsRef<Path>) -> Result<Self> {
        ensure!(
            root.as_ref().is_absolute(),
            "Absolute private registry required"
        );
        crate::storage::private_directory(root.as_ref())?;
        Ok(Self {
            root: physical(root.as_ref(), true)?,
        })
    }
    pub fn profile_directory(&self, profile: &SelectedProfile) -> Result<PathBuf> {
        Ok(self.root.join(home_key(profile.config_home())?))
    }
}
fn home_key(home: &Path) -> Result<String> {
    Ok(format!(
        "{:x}",
        Sha256::digest(pocket_runtime_ipc::support::normalized_home(home)?.as_bytes())
    ))
}

/// Written and flushed before CreateProcess. Missing identities are uncertainty,
/// never authorization to start a replacement. No command bodies or credentials.
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Ownership {
    schema: u32,
    home_key: String,
    config_home: PathBuf,
    native_binary: PathBuf,
    installation_generation: String,
    reservation: String,
    phase: String,
    host_process: Option<NativeProcess>,
    descriptor_generation: Option<String>,
}
impl Ownership {
    fn read(path: &Path, profile: &SelectedProfile) -> Result<Self> {
        let record: Self = serde_json::from_slice(&pocket_runtime_ipc::support::read_private(path, 16384)?)
            .map_err(|_| anyhow::anyhow!("Invalid startup ownership; inspect startup.json and original processes, do not delete the descriptor"))?;
        ensure!(
            record.schema == 1
                && record.home_key == home_key(profile.config_home())?
                && physical(&record.config_home, true)? == profile.home
                && physical(&record.native_binary, false)? == profile.binary
                && uuid::Uuid::parse_str(&record.reservation).is_ok()
                && record.installation_generation.len() == 64
                && record
                    .installation_generation
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit()),
            "Startup ownership conflicts with selected local profile; inspect startup.json before repair"
        );
        match record.phase.as_str() {
            "reserved" => ensure!(
                record.host_process.is_none() && record.descriptor_generation.is_none(),
                "Contradictory reservation"
            ),
            "spawned" | "ready" => {
                let host = record
                    .host_process
                    .as_ref()
                    .context("Host creation time missing; replacement is unsafe")?;
                ensure!(
                    host.pid != 0 && host.created_at != 0,
                    "Host creation time missing; replacement is unsafe"
                );
                ensure!(
                    if record.phase == "ready" {
                        record
                            .descriptor_generation
                            .as_ref()
                            .is_some_and(|g| uuid::Uuid::parse_str(g).is_ok())
                    } else {
                        record.descriptor_generation.is_none()
                    },
                    "Contradictory startup generation"
                );
            }
            _ => bail!("Unknown startup phase; inspect startup ownership before repair"),
        }
        Ok(record)
    }
    fn save(&self, path: &Path) -> Result<()> {
        crate::storage::atomic_write(path, &serde_json::to_vec(self)?)
    }
    fn matches(&self, descriptor: &Descriptor) -> Result<()> {
        ensure!(
            self.host_process == descriptor.host_process
                && self.phase != "reserved"
                && (self.phase != "ready"
                    || self.descriptor_generation.as_deref() == Some(&descriptor.generation)),
            "Descriptor/startup ownership mismatch; inspect original Host and native identities before repair"
        );
        Ok(())
    }
}

async fn acquire(path: &Path) -> Result<File> {
    let mut options = OpenOptions::new();
    options.create(true).truncate(false).read(true).write(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        options.share_mode(3);
    }
    let file = options.open(path)?;
    crate::storage::verify_physical_file(path, &file)?;
    ensure!(file.metadata()?.is_file(), "Invalid registry lock");
    let until = tokio::time::Instant::now() + Duration::from_secs(30);
    loop {
        match file.try_lock_exclusive() {
            Ok(()) => return Ok(file),
            Err(error) if error.raw_os_error() == fs2::lock_contended_error().raw_os_error() => {
                ensure!(
                    tokio::time::Instant::now() < until,
                    "Another launcher holds ensure.lock; retry after it finishes"
                );
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
            Err(error) => return Err(error).context("Acquire private ensure lock"),
        }
    }
}

pub(super) async fn ensure(
    installation: &Installation,
    registry: &Registry,
    profile: &SelectedProfile,
) -> Result<ManagedRuntime> {
    // Revalidate physical paths every time, including after a controller restart.
    ensure!(
        physical(profile.config_home(), true)? == profile.home
            && physical(profile.native_binary(), false)? == profile.binary,
        "Selected profile path changed"
    );
    let host_binary = installation.verify()?;
    physical(&registry.root, true)?;
    let directory = registry.profile_directory(profile)?;
    crate::storage::private_directory(&directory)?;
    physical(&directory, true)?;
    let lock_path = directory.join("ensure.lock");
    let ownership_path = directory.join("startup.json");
    let descriptor_path = directory.join("descriptor.json");
    let artifacts = ownership_path.try_exists()?
        || descriptor_path.try_exists()?
        || directory.join("host.lock").try_exists()?
        || directory.join("recovery.json").try_exists()?;
    ensure!(
        !artifacts || lock_path.try_exists()?,
        "ensure.lock ownership evidence missing; inspect this private profile registry before repair"
    );
    let _lock = acquire(&lock_path).await?;
    let has_descriptor = descriptor_path.try_exists()?;
    let has_owner = ownership_path.try_exists()?;
    let previous = if has_owner {
        Some(Ownership::read(&ownership_path, profile)?)
    } else {
        None
    };
    if has_descriptor {
        let mut owner = previous.context("Descriptor exists without startup ownership; replacement is unsafe, inspect original processes")?;
        let descriptor =
            pocket_runtime_ipc::load_descriptor(&descriptor_path, profile.config_home())?;
        owner.matches(&descriptor)?;
        if let Ok(client) = HostClient::attach(&descriptor).await {
            if owner.phase == "spawned" {
                owner.phase = "ready".into();
                owner.descriptor_generation = Some(descriptor.generation.clone());
                owner.save(&ownership_path)?;
            }
            return ManagedRuntime::open(descriptor_path, descriptor, client).await;
        }
        require_ended(&descriptor)?;
        // Both original identities ended; leave their descriptor in place for
        // the Host's own second guard and cold journal recovery.
    } else {
        ensure!(
            previous.is_none(),
            "Startup reservation has no descriptor; native ownership is uncertain. Inspect startup.json and original Host process; do not delete records to force a restart"
        );
        ensure!(
            !directory.join("host.lock").try_exists()?
                && !directory.join("recovery.json").try_exists()?,
            "Host state exists without descriptor/ownership; inspect original processes before repair"
        );
    }
    // Check the Host's independent lifetime lock before reserving a replacement.
    let host_lock_path = directory.join("host.lock");
    let host_lock = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(&host_lock_path)?;
    crate::storage::verify_physical_file(&host_lock_path, &host_lock)?;
    host_lock
        .try_lock_exclusive()
        .context("Host lock is live or inaccessible; replacement is unsafe")?;
    let mut owner = Ownership {
        schema: 1,
        home_key: home_key(profile.config_home())?,
        config_home: profile.home.clone(),
        native_binary: profile.binary.clone(),
        installation_generation: installation.generation().into(),
        reservation: uuid::Uuid::new_v4().to_string(),
        phase: "reserved".into(),
        host_process: None,
        descriptor_generation: None,
    };
    owner.save(&ownership_path)?; // No spawn before durable reservation.
    drop(host_lock);
    // Keep an opened executable guard across spawn, rejecting replacement in
    // the Windows launch window. Recheck the manifest/hash under that guard.
    let mut executable_options = OpenOptions::new();
    executable_options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        executable_options.share_mode(1);
    }
    let _executable_guard = executable_options.open(&host_binary)?;
    installation.verify()?;
    let mut command = tokio::process::Command::new(&host_binary);
    command
        .arg("--state")
        .arg(&directory)
        .arg("--descriptor")
        .arg(&descriptor_path)
        .arg("--binary")
        .arg(profile.native_binary())
        .arg("--config-home")
        .arg(profile.config_home())
        .env("CODEX_HOME", profile.config_home())
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(false);
    #[cfg(windows)]
    command.creation_flags(0x08000000);
    let mut child = command
        .spawn()
        .context("Host launch failed; reservation retained for ownership inspection")?;
    let pid = child
        .id()
        .context("Host PID unavailable; reservation retained")?;
    owner.host_process = Some(NativeProcess::capture(pid).context(
        "Host creation identity unavailable; reservation retained, do not launch another Host",
    )?);
    owner.phase = "spawned".into();
    owner.save(&ownership_path)?;
    let until = tokio::time::Instant::now() + Duration::from_secs(25);
    loop {
        if descriptor_path.try_exists()? {
            let descriptor =
                pocket_runtime_ipc::load_descriptor(&descriptor_path, profile.config_home())?;
            // During replacement this may still be the original descriptor.
            if descriptor.host_process == owner.host_process {
                owner.matches(&descriptor)?;
                if let Ok(client) = HostClient::attach(&descriptor).await {
                    owner.phase = "ready".into();
                    owner.descriptor_generation = Some(descriptor.generation.clone());
                    owner.save(&ownership_path)?;
                    return ManagedRuntime::open(descriptor_path, descriptor, client).await;
                }
            }
        }
        ensure!(
            child.try_wait()?.is_none(),
            "Host exited during startup; inspect startup.json/descriptor.json and original native process. No automatic restart"
        );
        ensure!(
            tokio::time::Instant::now() < until,
            "Host initialization deadline exceeded; reservation retained. Inspect original Host/native identities before retrying"
        );
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
}

fn require_ended(descriptor: &Descriptor) -> Result<()> {
    for (name, pid, identity) in [
        ("Host", descriptor.pid, &descriptor.host_process),
        ("native", descriptor.native_pid, &descriptor.native_process),
    ] {
        let identity = identity.as_ref().with_context(|| format!("{name} creation identity missing; cannot prove exit, inspect original process before repair"))?;
        ensure!(
            identity.pid == pid && identity.created_at != 0 && identity.terminated(),
            "Original {name} process is alive or its exit cannot be proven; inspect PID {pid} and its recorded creation time. Descriptor removal cannot authorize restart"
        );
    }
    Ok(())
}
