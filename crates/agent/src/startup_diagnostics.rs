//! Bounded, credential-free evidence for `run` startup only.
//! A record is evidence for one operation/PID/generation, never proof of liveness.
use anyhow::{Result, bail, ensure};
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

pub const RECORD_FILE: &str = "startup-failure.json";
pub const MAX_RECORD_BYTES: u64 = 4096;
pub const OPERATION_ID_ENV: &str = "POCKET_AGENT_STARTUP_OPERATION_ID";
const RECORD_LOCK: &str = ".startup-diagnostics.lock";

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    StatePath,
    StateAccess,
    StateLock,
    Config,
    Identity,
    Broker,
    ControlBind,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Code {
    StatePathInvalid,
    StateAccessFailed,
    StateLockFailed,
    StateLockContended,
    ConfigLoadFailed,
    IdentityLoadFailed,
    BrokerOpenFailed,
    ControlBindFailed,
}
impl Phase {
    fn code(self) -> Code {
        match self {
            Self::StatePath => Code::StatePathInvalid,
            Self::StateAccess => Code::StateAccessFailed,
            Self::StateLock => Code::StateLockFailed,
            Self::Config => Code::ConfigLoadFailed,
            Self::Identity => Code::IdentityLoadFailed,
            Self::Broker => Code::BrokerOpenFailed,
            Self::ControlBind => Code::ControlBindFailed,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Record {
    pub schema: u32,
    pub operation_id: String,
    pub pid: u32,
    #[serde(deserialize_with = "required_option")]
    pub runtime_generation: Option<String>,
    /// Unix milliseconds. Correlation additionally requires operationId, not time alone.
    pub time: u64,
    pub phase: Phase,
    pub code: Code,
    #[serde(deserialize_with = "required_option")]
    pub os_code: Option<i32>,
}
fn required_option<'de, D, T>(deserializer: D) -> std::result::Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(deserializer)
}
fn valid_operation_id(value: &str) -> bool {
    value.len() == 36
        && uuid::Uuid::parse_str(value)
            .is_ok_and(|id| id.get_version_num() == 4 && id.to_string() == value)
}
fn valid_generation(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
impl Record {
    fn valid(&self) -> bool {
        self.schema == 1
            && self.pid != 0
            && self.time != 0
            && valid_operation_id(&self.operation_id)
            && self
                .runtime_generation
                .as_deref()
                .is_none_or(valid_generation)
            && (self.code == self.phase.code()
                || (self.phase == Phase::StateLock && self.code == Code::StateLockContended))
    }
    pub fn matches(&self, operation_id: &str, pid: u32, generation: Option<&str>) -> bool {
        self.valid()
            && self.operation_id == operation_id
            && self.pid == pid
            && self.runtime_generation.as_deref() == generation
    }
    pub fn parse(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() as u64 <= MAX_RECORD_BYTES,
            "Startup record exceeds limit"
        );
        let record: Self =
            serde_json::from_slice(bytes).map_err(|_| anyhow::anyhow!("Invalid startup record"))?;
        ensure!(record.valid(), "Invalid startup record");
        Ok(record)
    }
    pub fn json(&self) -> Result<String> {
        ensure!(self.valid(), "Invalid startup record");
        let json = serde_json::to_string(self)?;
        ensure!(
            json.len() as u64 <= MAX_RECORD_BYTES,
            "Startup record exceeds limit"
        );
        Ok(json)
    }
}

/// Deliberately discards the original error chain. Debug/Display are also sanitized.
#[derive(Debug)]
pub struct Failure(pub Record);
impl std::fmt::Display for Failure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Helper startup failed at {:?}", self.0.phase)
    }
}
impl std::error::Error for Failure {}

struct PrivateState {
    path: PathBuf,
    _directories: Vec<File>,
}
pub struct Attempt {
    operation_id: String,
    pid: u32,
    generation: Option<String>,
    state: Option<PrivateState>,
}
impl Attempt {
    /// Only a canonical v4 UUID is accepted from the supervisor; arbitrary env text is discarded.
    pub fn current() -> Self {
        use sha2::{Digest, Sha256};
        let operation_id = std::env::var(OPERATION_ID_ENV)
            .ok()
            .filter(|v| valid_operation_id(v))
            .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
        // Do not use installation::current(): that also reads the whole executable.
        let generation = std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(Path::to_owned))
            .and_then(|p| {
                crate::storage::read_physical(&p.join("windows-manifest.json"), 1024 * 1024).ok()
            })
            .map(|bytes| format!("{:x}", Sha256::digest(bytes)));
        Self {
            operation_id,
            pid: std::process::id(),
            generation,
            state: None,
        }
    }
    pub fn operation_id(&self) -> &str {
        &self.operation_id
    }

    pub fn step<T>(&self, phase: Phase, result: Result<T>) -> Result<T> {
        result.map_err(|error| self.fail(phase, phase.code(), &error))
    }
    fn fail(&self, phase: Phase, code: Code, error: &anyhow::Error) -> anyhow::Error {
        let record = Record {
            schema: 1,
            operation_id: self.operation_id.clone(),
            pid: self.pid,
            runtime_generation: self.generation.clone(),
            time: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis()
                .clamp(1, u64::MAX as u128) as u64,
            phase,
            code,
            os_code: error.chain().find_map(|e| {
                e.downcast_ref::<std::io::Error>()
                    .and_then(std::io::Error::raw_os_error)
            }),
        };
        if let Some(state) = &self.state {
            // Diagnostic I/O cannot replace the original stage/code or trigger repair.
            let _ = state.persist(&record);
        }
        Failure(record).into()
    }
    pub fn prepare_state(&mut self, state: &Path) -> Result<()> {
        let path = self.step(
            Phase::StatePath,
            std::path::absolute(state).map_err(Into::into),
        )?;
        let _existing = self.step(Phase::StatePath, pin_directories(&path))?;
        self.step(Phase::StateAccess, crate::storage::private_directory(&path))?;
        let directories = self.step(Phase::StatePath, pin_directories(&path))?;
        self.step(
            Phase::StateAccess,
            (|| {
                let mut probe = tempfile::NamedTempFile::new_in(&path)?;
                verify_regular(probe.path(), probe.as_file())?;
                probe.write_all(b"startup-diagnostics-probe")?;
                probe.as_file().sync_all()?;
                Ok(())
            })(),
        )?;
        // No persistence capability exists until private + physical + writable all pass.
        self.state = Some(PrivateState {
            path,
            _directories: directories,
        });
        Ok(())
    }
    pub fn lock_agent(&self) -> Result<File> {
        let state = self
            .state
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("Unverified state"))?;
        let file = self.step(
            Phase::StateLock,
            open_regular(&state.path.join("agent.lock"), true, true),
        )?;
        match fs2::FileExt::try_lock_exclusive(&file) {
            Ok(()) => Ok(file),
            Err(e) => {
                let contended = e.kind() == std::io::ErrorKind::WouldBlock
                    || e.raw_os_error() == fs2::lock_contended_error().raw_os_error();
                Err(self.fail(
                    Phase::StateLock,
                    if contended {
                        Code::StateLockContended
                    } else {
                        Code::StateLockFailed
                    },
                    &e.into(),
                ))
            }
        }
    }
    /// Best effort; success must not erase another startup operation's evidence.
    pub fn clear_own(&self) -> Result<bool> {
        let Some(state) = &self.state else {
            return Ok(false);
        };
        let _lock = state.diagnostic_lock()?;
        let Some(record) = read_record(&state.path)? else {
            return Ok(false);
        };
        if !record.matches(&self.operation_id, self.pid, self.generation.as_deref()) {
            return Ok(false);
        }
        fs::remove_file(state.path.join(RECORD_FILE))?;
        Ok(true)
    }
}
impl PrivateState {
    fn diagnostic_lock(&self) -> Result<File> {
        let file = open_regular(&self.path.join(RECORD_LOCK), true, true)?;
        ensure!(file.metadata()?.len() == 0, "Invalid diagnostic lock");
        fs2::FileExt::try_lock_exclusive(&file)?;
        Ok(file)
    }
    fn persist(&self, record: &Record) -> Result<()> {
        let bytes = record.json()?;
        let _lock = self.diagnostic_lock()?;
        let path = self.path.join(RECORD_FILE);
        match open_regular(&path, false, false) {
            Ok(_) => {}
            Err(e) if missing(&e) => {}
            Err(e) => return Err(e),
        }
        let mut staging = tempfile::NamedTempFile::new_in(&self.path)?;
        verify_regular(staging.path(), staging.as_file())?;
        staging.write_all(bytes.as_bytes())?;
        staging.as_file().sync_all()?;
        fs::rename(staging.path(), &path)?;
        #[cfg(unix)]
        File::open(&self.path)?.sync_all()?;
        Ok(())
    }
}
fn missing(error: &anyhow::Error) -> bool {
    error
        .downcast_ref::<std::io::Error>()
        .is_some_and(|e| e.kind() == std::io::ErrorKind::NotFound)
}
fn read_record(state: &Path) -> Result<Option<Record>> {
    let file = match open_regular(&state.join(RECORD_FILE), false, false) {
        Ok(file) => file,
        Err(e) if missing(&e) => return Ok(None),
        Err(e) => return Err(e),
    };
    ensure!(
        file.metadata()?.len() <= MAX_RECORD_BYTES,
        "Startup record exceeds limit"
    );
    let mut bytes = Vec::new();
    file.take(MAX_RECORD_BYTES + 1).read_to_end(&mut bytes)?;
    Record::parse(&bytes).map(Some)
}
/// A supervisor must supply its own observed child PID and expected generation/operation.
pub fn read_matching(
    state: &Path,
    operation_id: &str,
    pid: u32,
    generation: Option<&str>,
) -> Result<Option<Record>> {
    let _directories = pin_directories(state)?;
    Ok(read_record(state)?.filter(|r| r.matches(operation_id, pid, generation)))
}

fn plain_path(path: &Path) -> Result<()> {
    for ancestor in path.ancestors() {
        match fs::symlink_metadata(ancestor) {
            Ok(metadata) => {
                ensure!(!metadata.is_symlink(), "Unsafe state path");
                #[cfg(windows)]
                {
                    use std::os::windows::fs::MetadataExt;
                    ensure!(metadata.file_attributes() & 0x400 == 0, "Unsafe state path");
                }
                if ancestor != path {
                    ensure!(metadata.is_dir(), "Invalid state parent");
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.into()),
        }
    }
    Ok(())
}
fn pin_directories(path: &Path) -> Result<Vec<File>> {
    let absolute = std::path::absolute(path)?;
    plain_path(&absolute)?;
    let mut files = Vec::new();
    for ancestor in absolute.ancestors().collect::<Vec<_>>().into_iter().rev() {
        if !ancestor.try_exists()? {
            continue;
        }
        let mut options = OpenOptions::new();
        options.read(true);
        #[cfg(windows)]
        {
            use std::os::windows::fs::OpenOptionsExt;
            use windows_sys::Win32::Storage::FileSystem::*;
            options
                .access_mode(FILE_READ_ATTRIBUTES)
                .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE)
                .custom_flags(FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT);
        }
        let file = options.open(ancestor)?;
        ensure!(file.metadata()?.is_dir(), "Invalid state directory");
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            ensure!(
                file.metadata()?.file_attributes() & 0x400 == 0,
                "Unsafe state directory"
            );
        }
        crate::storage::verify_physical_file(ancestor, &file)?;
        files.push(file);
    }
    Ok(files)
}
fn open_regular(path: &Path, writable: bool, create: bool) -> Result<File> {
    plain_path(path)?;
    let mut options = OpenOptions::new();
    options
        .read(true)
        .write(writable)
        .create(create)
        .truncate(false);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        use windows_sys::Win32::Storage::FileSystem::*;
        options
            .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT);
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let file = options.open(path)?;
    verify_regular(path, &file)?;
    Ok(file)
}
fn verify_regular(path: &Path, file: &File) -> Result<()> {
    let metadata = file.metadata()?;
    ensure!(metadata.is_file(), "Invalid state file");
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        ensure!(metadata.nlink() == 1, "Unsafe state file");
    }
    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawHandle;
        use windows_sys::Win32::Storage::FileSystem::*;
        let mut info: BY_HANDLE_FILE_INFORMATION = unsafe { std::mem::zeroed() };
        if unsafe { GetFileInformationByHandle(file.as_raw_handle(), &mut info) } == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        ensure!(
            info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT == 0 && info.nNumberOfLinks == 1,
            "Unsafe state file"
        );
    }
    crate::storage::verify_physical_file(path, file)
}

/// `run` requires existing identity material. Windows handles deny delete sharing,
/// pinning every ancestor and both files across load_or_create's existence checks.
/// The tunnel loader validates its existing ACLs/certificate/key without rewriting them.
pub fn load_existing_identity(state: &Path) -> Result<pocket_tunnel::ServerIdentity> {
    let identity = state.join("identity");
    let _directories = pin_directories(&identity)?;
    let certificate = open_regular(&identity.join("certificate.der"), false, false)?;
    let key = open_regular(&identity.join("private-key.der"), false, false)?;
    for file in [&certificate, &key] {
        if file.metadata()?.len() == 0 || file.metadata()?.len() > 64 * 1024 {
            bail!("Invalid existing identity");
        }
    }
    let loaded = pocket_tunnel::ServerIdentity::load_or_create(&identity)?;
    drop((certificate, key));
    Ok(loaded)
}
