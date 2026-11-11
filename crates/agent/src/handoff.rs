//! Local-only Pocket agent upgrade transaction. Transport authentication and the
//! run lock belong to the caller. Never kills processes or issues native writes.
use pocket_core::{
    directory::RuntimeId,
    journal::{Cursor, EventEnvelope, EventJournal, Replay},
    ledger::{CommandLedger, LedgerConfig, Status},
    native_process::NativeProcess,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};
use tokio::sync::watch;
use uuid::Uuid;

pub const METHODS: [&str; 5] = [
    "upgrade/capabilities",
    "upgrade/quiesce",
    "upgrade/status",
    "upgrade/release",
    "upgrade/cancel",
];
const MAX_IMAGE: u64 = 128 * 1024 * 1024;
const MAX_OPERATIONS: usize = 4096;

// Every key describes a real file (None records its absence), so adapters can
// continue verifying protectedFiles without a new wire field or virtual path.
// Only drafts/ is enumerated; volatile runtime/transport state is never scanned.
const PROTECTED_FILES: &[(&str, u64)] = &[
    ("config.json", 1024 * 1024),
    ("devices.json", 1024 * 1024),
    ("session-access.json", 2 * 1024 * 1024),
    ("relay-config.json", 1024 * 1024),
    ("desktop-install.json", 1024 * 1024),
    ("identity/certificate.der", 64 * 1024),
    ("identity/private-key.der", 64 * 1024),
    ("assistant/profile.json", 16 * 1024),
    ("desktop/desired.json", 4096),
    ("drafts.json", 4 * 1024 * 1024),
    ("drafts/unsent.json", 4 * 1024 * 1024),
];
const MAX_DRAFT_DEPTH: usize = 4;
const MAX_DRAFT_ENTRIES: usize = 256;
const MAX_PROTECTED_FILE: u64 = 4 * 1024 * 1024;
const MAX_PROTECTED_BYTES: u64 = 16 * 1024 * 1024;
const MAX_PROTECTED_PATH: usize = 1024;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HandoffError {
    pub code: &'static str,
}
impl HandoffError {
    pub fn new(code: &'static str) -> Self {
        Self { code }
    }
    pub fn wire(&self) -> Value {
        let message = match self.code {
            "invalid_params" => "Invalid upgrade parameters",
            "unsupported" => "Safe agent handoff is unsupported",
            "stale_generation" => "Agent generation changed",
            "handoff_conflict" => "Upgrade operation conflicts with an existing handoff",
            "checkpoint_mismatch" => "Upgrade checkpoint does not match",
            "not_ready" => "Agent handoff is not ready",
            "write_fenced" => "Agent is quiescing; new writes are disabled",
            _ => "Agent handoff is unavailable",
        };
        json!({"code":self.code,"message":message})
    }
}
impl std::fmt::Display for HandoffError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.code)
    }
}
impl std::error::Error for HandoffError {}
type Result<T> = std::result::Result<T, HandoffError>;
fn error(code: &'static str) -> HandoffError {
    HandoffError::new(code)
}
fn digest(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}
fn reject_links(path: &Path) -> Result<()> {
    for ancestor in path.ancestors() {
        match std::fs::symlink_metadata(ancestor) {
            Ok(metadata) => {
                if metadata.is_symlink() {
                    return Err(error("unavailable"));
                }
                #[cfg(windows)]
                {
                    use std::os::windows::fs::MetadataExt;
                    if metadata.file_attributes() & 0x400 != 0 {
                        return Err(error("unavailable"));
                    }
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err(error("unavailable")),
        }
    }
    Ok(())
}

fn check_opened_path(
    path: &Path,
    file: &std::fs::File,
    directory: bool,
) -> std::io::Result<std::fs::Metadata> {
    let metadata = file.metadata()?;
    if (directory && !metadata.is_dir()) || (!directory && !metadata.is_file()) {
        return Err(std::io::Error::other("Unexpected protected file type"));
    }
    crate::storage::verify_physical_file(path, file).map_err(std::io::Error::other)?;
    #[cfg(windows)]
    {
        use std::os::windows::{fs::MetadataExt, io::AsRawHandle};
        use windows_sys::Win32::Storage::FileSystem::{
            BY_HANDLE_FILE_INFORMATION, GetFileInformationByHandle,
        };
        let mut info: BY_HANDLE_FILE_INFORMATION = unsafe { std::mem::zeroed() };
        if unsafe { GetFileInformationByHandle(file.as_raw_handle(), &mut info) } == 0 {
            return Err(std::io::Error::last_os_error());
        }
        if metadata.file_attributes() & 0x400 != 0 || (!directory && info.nNumberOfLinks != 1) {
            return Err(std::io::Error::other("Unsafe protected file"));
        }
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let expected = std::fs::symlink_metadata(path)?;
        if expected.is_symlink()
            || expected.dev() != metadata.dev()
            || expected.ino() != metadata.ino()
            || (!directory && metadata.nlink() != 1)
        {
            return Err(std::io::Error::other("Unsafe protected file"));
        }
    }
    Ok(metadata)
}
fn open_protected_directory(path: &Path) -> std::io::Result<std::fs::File> {
    let mut options = std::fs::OpenOptions::new();
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
    let file = options.open(path)?;
    check_opened_path(path, &file, true)?;
    Ok(file)
}
// Hold directory handles throughout each read. On Windows they deny delete
// sharing, pinning ancestors against junction/rename replacement while hashing.
fn pin_protected_parents(path: &Path) -> Result<Option<Vec<std::fs::File>>> {
    reject_links(path)?;
    let absolute = std::path::absolute(path).map_err(|_| error("unavailable"))?;
    let parents = absolute
        .parent()
        .ok_or_else(|| error("unavailable"))?
        .ancestors()
        .collect::<Vec<_>>();
    let mut pins = vec![];
    for parent in parents.into_iter().rev() {
        match open_protected_directory(parent) {
            Ok(file) => pins.push(file),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(_) => return Err(error("unavailable")),
        }
    }
    Ok(Some(pins))
}
fn protected_bytes(path: &Path, limit: u64) -> Result<Option<Vec<u8>>> {
    use std::io::Read;
    let Some(_pins) = pin_protected_parents(path)? else {
        return Ok(None);
    };
    let mut options = std::fs::OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        use windows_sys::Win32::Storage::FileSystem::*;
        options
            .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT);
    }
    let mut file = match options.open(path) {
        Ok(file) => file,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err(error("unavailable")),
    };
    let before = check_opened_path(path, &file, false).map_err(|_| error("unavailable"))?;
    if before.len() > limit {
        return Err(error("unavailable"));
    }
    let mut bytes = Vec::new();
    (&mut file)
        .take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| error("unavailable"))?;
    let after = check_opened_path(path, &file, false).map_err(|_| error("unavailable"))?;
    if bytes.len() as u64 > limit
        || bytes.len() as u64 != before.len()
        || before.len() != after.len()
        || before.modified().ok() != after.modified().ok()
    {
        return Err(error("unavailable"));
    }
    Ok(Some(bytes))
}
fn uuid_string(value: &Value) -> bool {
    value
        .as_str()
        .is_some_and(|s| Uuid::parse_str(s).is_ok_and(|u| u.to_string() == s && !u.is_nil()))
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CheckpointRef {
    pub id: Uuid,
    pub generation: Uuid,
    pub hash: String,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StartupMode {
    Successor,
    Rollback,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StartupRequest {
    pub mode: StartupMode,
    pub checkpoint: CheckpointRef,
}
impl StartupRequest {
    pub fn from_env() -> Result<Option<Self>> {
        let read = |key| match std::env::var(key) {
            Ok(v) => Ok(Some(v)),
            Err(std::env::VarError::NotPresent) => Ok(None),
            Err(_) => Err(error("invalid_params")),
        };
        let cp = read("POCKET_AGENT_HANDOFF_CHECKPOINT")?;
        let mode = read("POCKET_AGENT_HANDOFF_MODE")?;
        Self::parse(cp.as_deref(), mode.as_deref())
    }
    pub fn parse(checkpoint: Option<&str>, mode: Option<&str>) -> Result<Option<Self>> {
        let Some(text) = checkpoint else {
            return if mode.is_none() {
                Ok(None)
            } else {
                Err(error("invalid_params"))
            };
        };
        let mode = match mode {
            None | Some("successor") => StartupMode::Successor,
            Some("rollback") => StartupMode::Rollback,
            _ => return Err(error("invalid_params")),
        };
        let value: Value = serde_json::from_str(text).map_err(|_| error("invalid_params"))?;
        if !uuid_string(&value["id"]) || !uuid_string(&value["generation"]) {
            return Err(error("invalid_params"));
        }
        let cp: CheckpointRef =
            serde_json::from_value(value).map_err(|_| error("invalid_params"))?;
        if !cp.valid() {
            return Err(error("invalid_params"));
        }
        Ok(Some(Self {
            mode,
            checkpoint: cp,
        }))
    }
}
impl CheckpointRef {
    fn valid(&self) -> bool {
        !self.id.is_nil()
            && !self.generation.is_nil()
            && self.hash.len() == 64
            && self
                .hash
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Params {
    pub id: Uuid,
    pub from_generation: Uuid,
    pub checkpoint: Option<CheckpointRef>,
}
impl Params {
    pub fn parse(value: &Value) -> Result<Self> {
        let object = value.as_object().ok_or_else(|| error("invalid_params"))?;
        if object.len() != 3
            || !uuid_string(&value["id"])
            || !uuid_string(&value["fromGeneration"])
            || !object.contains_key("checkpoint")
        {
            return Err(error("invalid_params"));
        }
        if !value["checkpoint"].is_null()
            && (!uuid_string(&value["checkpoint"]["id"])
                || !uuid_string(&value["checkpoint"]["generation"]))
        {
            return Err(error("invalid_params"));
        }
        let p: Self = serde_json::from_value(value.clone()).map_err(|_| error("invalid_params"))?;
        p.validate()?;
        Ok(p)
    }
    fn validate(&self) -> Result<()> {
        if self.id.is_nil()
            || self.from_generation.is_nil()
            || self.checkpoint.as_ref().is_some_and(|c| !c.valid())
        {
            return Err(error("invalid_params"));
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Draining,
    Ready,
    ReleasePending,
    Released,
    Cancelled,
    Failed,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Receipt {
    pub protocol: u32,
    pub id: Uuid,
    pub generation: Uuid,
    pub phase: Phase,
    pub safe_to_exit: bool,
    pub native_tasks_preserved: bool,
    pub remote_enabled: bool,
    pub accepting_writes: bool,
    pub unconfirmed_writes: usize,
    pub blockers: BTreeSet<String>,
    pub checkpoint: Option<CheckpointRef>,
    pub shutdown_requested: bool,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Lifecycle {
    DesktopExternal,
    ManagedHostExternal,
    CliDaemonExternal,
    LegacyCliAgentOwned,
    Unknown,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HostCursor {
    pub generation: String,
    pub after: u64,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RuntimeIdentity {
    pub runtime_id: String,
    pub store: String,
    pub connection_id: String,
    pub native_process: Option<NativeProcess>,
    pub lifecycle: Lifecycle,
    pub inventory_verified: bool,
    pub active: Option<bool>,
    pub host_cursor: Option<HostCursor>,
    pub host_process: Option<NativeProcess>,
    pub reconciliation_required: bool,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EventArchive {
    pub cursor: Cursor,
    pub floor: u64,
    pub events: Vec<EventEnvelope>,
    pub host_cursor: Option<HostCursor>,
}
impl EventArchive {
    pub fn capture(journal: &EventJournal, host_cursor: Option<HostCursor>) -> Self {
        let cursor = journal.cursor();
        // Journal exposes replay, not its floor. Find the first valid cursor,
        // including an oversized-event gap, without editing the core journal.
        let (mut low, mut high) = (0, cursor.sequence);
        while low < high {
            let mid = low + (high - low) / 2;
            let probe = Cursor {
                sequence: mid,
                ..cursor.clone()
            };
            if matches!(journal.replay(&probe), Replay::SnapshotRequired) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        let events = match journal.replay(&Cursor {
            sequence: low,
            ..cursor.clone()
        }) {
            Replay::Events(v) => v,
            Replay::SnapshotRequired => unreachable!("current journal cursor must replay"),
        };
        Self {
            cursor,
            floor: low,
            events,
            host_cursor,
        }
    }
    pub fn replay(&self, cursor: &Cursor) -> Replay {
        if cursor.runtime != self.cursor.runtime
            || cursor.epoch != self.cursor.epoch
            || cursor.sequence < self.floor
            || cursor.sequence > self.cursor.sequence
        {
            return Replay::SnapshotRequired;
        }
        Replay::Events(
            self.events
                .iter()
                .filter(|e| e.cursor.sequence > cursor.sequence)
                .cloned()
                .collect(),
        )
    }
    fn validate(&self, computer: &str, runtime: &str) -> Result<()> {
        if self.cursor.runtime.computer_id != computer
            || self.cursor.runtime.runtime_id != runtime
            || self.cursor.epoch.is_nil()
            || self.floor > self.cursor.sequence
        {
            return Err(error("checkpoint_mismatch"));
        }
        let mut sequence = self.floor;
        for event in &self.events {
            sequence = sequence
                .checked_add(1)
                .ok_or_else(|| error("checkpoint_mismatch"))?;
            if event.cursor.runtime != self.cursor.runtime
                || event.cursor.epoch != self.cursor.epoch
                || event.cursor.sequence != sequence
            {
                return Err(error("checkpoint_mismatch"));
            }
        }
        if sequence != self.cursor.sequence {
            return Err(error("checkpoint_mismatch"));
        }
        Ok(())
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Snapshot {
    pub computer_id: String,
    pub ledger: Value,
    pub events: BTreeMap<String, EventArchive>,
    pub runtimes: BTreeMap<String, RuntimeIdentity>,
    pub pending: Value,
    pub ownership: Value,
    pub question_receipts: Value,
    /// Relative file -> hash, or absent. These files are never overwritten.
    pub protected_files: BTreeMap<String, Option<String>>,
    /// Collector failures independent of runtime/ledger validation.
    pub blockers: BTreeSet<String>,
}
impl Snapshot {
    pub fn validate(&self) -> Result<()> {
        if self.computer_id.is_empty()
            || !self.ledger["entries"].is_array()
            || !self.pending.is_object()
            || !self.ownership.is_array()
            || !self.question_receipts["records"].is_array()
        {
            return Err(error("checkpoint_mismatch"));
        }
        let generation = self.ledger["generation"]
            .as_u64()
            .ok_or_else(|| error("checkpoint_mismatch"))?;
        let now = self.ledger["last_now"]
            .as_u64()
            .ok_or_else(|| error("checkpoint_mismatch"))?;
        CommandLedger::restore_json(
            &self.ledger.to_string(),
            RuntimeId {
                computer_id: self.computer_id.clone(),
                runtime_id: "broker".into(),
            },
            LedgerConfig {
                capacity: 50_000,
                terminal_ttl: u64::MAX,
            },
            generation,
            now,
        )
        .map_err(|_| error("checkpoint_mismatch"))?;
        for (id, archive) in &self.events {
            if !self.runtimes.contains_key(id) {
                return Err(error("checkpoint_mismatch"));
            }
            archive.validate(&self.computer_id, id)?;
        }
        for (id, r) in &self.runtimes {
            if id != &r.runtime_id
                || id.is_empty()
                || r.store.is_empty()
                || r.connection_id.is_empty()
                || r.host_cursor
                    .as_ref()
                    .is_some_and(|c| c.generation.is_empty())
            {
                return Err(error("checkpoint_mismatch"));
            }
            match self.events.get(id) {
                Some(archive) if archive.host_cursor == r.host_cursor => {}
                None if !r.inventory_verified => {}
                _ => return Err(error("checkpoint_mismatch")),
            }
        }
        for (path, hash) in &self.protected_files {
            if !safe_relative(path)
                || hash.as_ref().is_some_and(|h| {
                    h.len() != 64
                        || !h
                            .bytes()
                            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                })
            {
                return Err(error("checkpoint_mismatch"));
            }
        }
        if self.protected_files.len() > PROTECTED_FILES.len() + MAX_DRAFT_ENTRIES {
            return Err(error("checkpoint_mismatch"));
        }
        Ok(())
    }
    fn unconfirmed(&self) -> usize {
        let ledger = self.ledger["entries"].as_array().map_or(0, |v| {
            v.iter()
                .filter(|e| {
                    matches!(
                        serde_json::from_value::<Status>(e["status"].clone()),
                        Ok(Status::Submitted | Status::Unknown)
                    )
                })
                .count()
        });
        let pending = self.pending.as_object().map_or(0, |v| {
            v.values()
                .filter(|p| !p["command"].is_null() || !p["response"].is_null())
                .count()
        });
        let questions = self.question_receipts["records"].as_array().map_or(0, |v| {
            v.iter()
                .filter(|r| {
                    !matches!(
                        r["phase"].as_str(),
                        Some("confirmed" | "answered_elsewhere" | "cancelled")
                    )
                })
                .count()
        });
        ledger
            .saturating_add(pending)
            .saturating_add(questions)
            .max(usize::from(self.blockers.contains("unconfirmed_writes")))
    }
    fn safety(&self) -> (BTreeSet<String>, bool) {
        let mut blockers = self.blockers.clone();
        let mut preserved = true;
        for r in self.runtimes.values() {
            if r.native_process
                .as_ref()
                .is_none_or(|p| p.pid == 0 || p.created_at == 0)
            {
                blockers.insert("runtime_identity_unverified".into());
                preserved = false;
            }
            if !r.inventory_verified {
                blockers.insert("runtime_inventory_unverified".into());
                preserved = false;
            }
            match r.lifecycle {
                Lifecycle::Unknown => {
                    blockers.insert("runtime_lifecycle_unverified".into());
                    preserved = false;
                }
                Lifecycle::LegacyCliAgentOwned if r.active != Some(false) => {
                    blockers.insert("legacy_cli_active".into());
                    preserved = false;
                }
                Lifecycle::ManagedHostExternal
                    if r.host_cursor.is_none()
                        || r.host_process
                            .as_ref()
                            .is_none_or(|p| p.pid == 0 || p.created_at == 0) =>
                {
                    blockers.insert("runtime_identity_unverified".into());
                    preserved = false;
                }
                _ => {}
            }
            if r.reconciliation_required {
                blockers.insert("reconciliation_required".into());
                preserved = false;
            }
        }
        if blockers
            .iter()
            .any(|b| b.starts_with("runtime_") || b == "reconciliation_required")
        {
            preserved = false;
        }
        (blockers, preserved)
    }
    /// A reconnect may change connectionId/agent journal epoch, never native
    /// ownership. Nothing here reissues ledger commands or restores credentials.
    pub fn verify_successor(&self, current: &Snapshot) -> Result<()> {
        self.validate()?;
        current.validate()?;
        if !current.safety().0.is_empty() || current.unconfirmed() > 0 {
            return Err(error("not_ready"));
        }
        if self.computer_id != current.computer_id
            || self.protected_files != current.protected_files
        {
            return Err(error("checkpoint_mismatch"));
        }
        for (id, old) in &self.runtimes {
            if old.lifecycle == Lifecycle::LegacyCliAgentOwned && old.active == Some(false) {
                continue;
            }
            let new = current
                .runtimes
                .get(id)
                .ok_or_else(|| error("checkpoint_mismatch"))?;
            if old.store != new.store
                || old.native_process != new.native_process
                || old.host_process != new.host_process
                || old.lifecycle != new.lifecycle
                || !new.inventory_verified
                || new.reconciliation_required
            {
                return Err(error("checkpoint_mismatch"));
            }
            match (&old.host_cursor, &new.host_cursor) {
                (Some(a), Some(b)) if a.generation == b.generation && b.after >= a.after => {}
                (None, None) => {}
                _ => return Err(error("checkpoint_mismatch")),
            }
        }
        // Preserve command generation and every original body/result, including
        // Unknown. A new run UUID must not expire terminal command keys.
        let mut old_ledger = self.ledger.clone();
        let mut new_ledger = current.ledger.clone();
        let old_now = old_ledger
            .as_object_mut()
            .unwrap()
            .remove("last_now")
            .unwrap();
        let new_now = new_ledger
            .as_object_mut()
            .unwrap()
            .remove("last_now")
            .unwrap();
        if old_ledger != new_ledger || new_now.as_u64() < old_now.as_u64() {
            return Err(error("checkpoint_mismatch"));
        }
        Ok(())
    }
}
fn safe_relative(path: &str) -> bool {
    !path.is_empty()
        && path.len() <= MAX_PROTECTED_PATH
        && !path.contains('\\')
        && path
            .split('/')
            .all(|s| !s.is_empty() && s != "." && s != ".." && !s.contains(':'))
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CheckpointImage {
    pub protocol: u32,
    pub id: Uuid,
    pub generation: Uuid,
    pub snapshot: Snapshot,
    pub summary_hash: String,
}
pub struct CheckpointStore {
    root: PathBuf,
}
impl CheckpointStore {
    pub fn new(state_dir: &Path) -> Self {
        Self {
            root: state_dir.into(),
        }
    }
    pub fn path(&self, checkpoint: &CheckpointRef) -> PathBuf {
        self.root
            .join("handoff")
            .join(format!("{}.json", checkpoint.id))
    }
    pub fn save(&self, generation: Uuid, snapshot: Snapshot) -> Result<CheckpointRef> {
        if generation.is_nil() {
            return Err(error("invalid_params"));
        }
        snapshot.validate()?;
        let summary_hash =
            digest(&serde_json::to_vec(&snapshot).map_err(|_| error("unavailable"))?);
        let image = CheckpointImage {
            protocol: 1,
            id: Uuid::new_v4(),
            generation,
            snapshot,
            summary_hash,
        };
        let bytes = serde_json::to_vec(&image).map_err(|_| error("unavailable"))?;
        if bytes.len() as u64 > MAX_IMAGE {
            return Err(error("unavailable"));
        }
        let cp = CheckpointRef {
            id: image.id,
            generation,
            hash: digest(&bytes),
        };
        reject_links(&self.path(&cp))?;
        crate::storage::atomic_write(&self.path(&cp), &bytes).map_err(|_| error("unavailable"))?;
        let readback = self.verify(&cp, &image.snapshot.computer_id)?;
        if readback != image {
            return Err(error("checkpoint_mismatch"));
        }
        Ok(cp)
    }
    pub fn verify(&self, cp: &CheckpointRef, computer: &str) -> Result<CheckpointImage> {
        if !cp.valid() {
            return Err(error("checkpoint_mismatch"));
        }
        reject_links(&self.path(cp))?;
        let bytes = crate::storage::read_physical(&self.path(cp), MAX_IMAGE)
            .map_err(|_| error("checkpoint_mismatch"))?;
        if digest(&bytes) != cp.hash {
            return Err(error("checkpoint_mismatch"));
        }
        let image: CheckpointImage =
            serde_json::from_slice(&bytes).map_err(|_| error("checkpoint_mismatch"))?;
        if image.protocol != 1
            || image.id != cp.id
            || image.generation != cp.generation
            || image.snapshot.computer_id != computer
            || image.summary_hash
                != digest(
                    &serde_json::to_vec(&image.snapshot)
                        .map_err(|_| error("checkpoint_mismatch"))?,
                )
        {
            return Err(error("checkpoint_mismatch"));
        }
        image.snapshot.validate()?;
        self.verify_protected(&image.snapshot)?;
        Ok(image)
    }
    pub fn protected_hash(&self, relative: &str) -> Result<Option<String>> {
        if !safe_relative(relative) {
            return Err(error("checkpoint_mismatch"));
        }
        let limit = PROTECTED_FILES
            .iter()
            .find(|(name, _)| *name == relative)
            .map_or(MAX_PROTECTED_FILE, |(_, limit)| *limit);
        Ok(protected_bytes(&self.root.join(relative), limit)?.map(|bytes| digest(&bytes)))
    }
    pub fn capture_protected(&self) -> Result<BTreeMap<String, Option<String>>> {
        let mut files = BTreeMap::new();
        let mut bytes = 0;
        for (name, limit) in PROTECTED_FILES {
            self.capture_file(name, *limit, &mut files, &mut bytes)?;
        }
        let mut entries = 0;
        self.capture_drafts("drafts", 0, &mut entries, &mut files, &mut bytes)?;
        Ok(files)
    }
    fn capture_file(
        &self,
        name: &str,
        limit: u64,
        files: &mut BTreeMap<String, Option<String>>,
        total: &mut u64,
    ) -> Result<()> {
        if !safe_relative(name) {
            return Err(error("unavailable"));
        }
        let bytes = protected_bytes(
            &self.root.join(name),
            limit.min(MAX_PROTECTED_BYTES - *total),
        )?;
        if let Some(bytes) = &bytes {
            *total += bytes.len() as u64;
        }
        files.insert(name.into(), bytes.map(|bytes| digest(&bytes)));
        Ok(())
    }
    fn capture_drafts(
        &self,
        relative: &str,
        depth: usize,
        count: &mut usize,
        files: &mut BTreeMap<String, Option<String>>,
        total: &mut u64,
    ) -> Result<()> {
        if depth > MAX_DRAFT_DEPTH {
            return Err(error("unavailable"));
        }
        let directory = self.root.join(relative);
        let Some(_pins) = pin_protected_parents(&directory.join(".entry"))? else {
            return Ok(());
        };
        let mut rows = Vec::new();
        for row in std::fs::read_dir(&directory).map_err(|_| error("unavailable"))? {
            *count += 1;
            if *count > MAX_DRAFT_ENTRIES {
                return Err(error("unavailable"));
            }
            rows.push(row.map_err(|_| error("unavailable"))?);
        }
        rows.sort_by_key(|entry| entry.file_name());
        for entry in rows {
            let name = entry
                .file_name()
                .into_string()
                .map_err(|_| error("unavailable"))?;
            let name = format!("{relative}/{name}");
            if !safe_relative(&name) {
                return Err(error("unavailable"));
            }
            let kind = entry.file_type().map_err(|_| error("unavailable"))?;
            if kind.is_dir() {
                self.capture_drafts(&name, depth + 1, count, files, total)?;
            } else if kind.is_file() {
                match files.get(&name) {
                    Some(Some(_)) => {}
                    Some(None) => return Err(error("unavailable")),
                    None => self.capture_file(&name, MAX_PROTECTED_FILE, files, total)?,
                }
            } else {
                return Err(error("unavailable"));
            }
        }
        Ok(())
    }
    fn verify_protected(&self, snapshot: &Snapshot) -> Result<()> {
        if PROTECTED_FILES
            .iter()
            .all(|(name, _)| snapshot.protected_files.contains_key(*name))
        {
            // Re-enumeration also checks additions/removals, not only the old
            // set of paths. A new draft can never disappear from the contract.
            if self
                .capture_protected()
                .map_err(|_| error("checkpoint_mismatch"))?
                != snapshot.protected_files
            {
                return Err(error("checkpoint_mismatch"));
            }
            return Ok(());
        }
        // Earlier product checkpoints only hashed root files. Do not pretend
        // those have the current identity/draft safety contract. Minimal pure
        // state-machine fixtures without product config remain supported.
        if snapshot.protected_files.contains_key("config.json")
            || self
                .root
                .join("config.json")
                .try_exists()
                .map_err(|_| error("unavailable"))?
        {
            return Err(error("unsupported"));
        }
        for (relative, expected) in &snapshot.protected_files {
            if &self.protected_hash(relative)? != expected {
                return Err(error("checkpoint_mismatch"));
            }
        }
        Ok(())
    }
    fn verify_stored(&self, cp: &CheckpointRef) -> Result<CheckpointImage> {
        reject_links(&self.path(cp))?;
        let bytes = crate::storage::read_physical(&self.path(cp), MAX_IMAGE)
            .map_err(|_| error("checkpoint_mismatch"))?;
        let image: CheckpointImage =
            serde_json::from_slice(&bytes).map_err(|_| error("checkpoint_mismatch"))?;
        self.verify(cp, &image.snapshot.computer_id)
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Operation {
    receipt: Receipt,
    prior_remote: bool,
    release_input: Option<CheckpointRef>,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Durable {
    protocol: u32,
    generation: Uuid,
    operations: BTreeMap<Uuid, Operation>,
    #[serde(default)]
    recovery_checkpoint: Option<CheckpointRef>,
}
struct Startup {
    checkpoint: CheckpointRef,
    remote: bool,
}
struct State {
    durable: Durable,
    writes: BTreeSet<Uuid>,
    remote: bool,
    accepting: bool,
    startup: Option<Startup>,
}
pub struct Handoff {
    root: PathBuf,
    pocket_owned: bool,
    state: Mutex<State>,
    shutdown: watch::Sender<bool>,
    host_descriptors: Mutex<BTreeMap<String, PathBuf>>,
    recovery: Mutex<Option<Snapshot>>,
}
impl Handoff {
    /// Caller must already hold the daemon lock and verify this is Pocket's own
    /// background. A different run with a released predecessor uses open_successor.
    pub fn open(
        root: &Path,
        generation: Uuid,
        pocket_owned: bool,
        remote: bool,
    ) -> Result<Arc<Self>> {
        Self::open_internal(root, generation, pocket_owned, remote, false)
    }
    pub fn open_successor(
        root: &Path,
        generation: Uuid,
        pocket_owned: bool,
        remote: bool,
        cp: &CheckpointRef,
        current: &Snapshot,
    ) -> Result<Arc<Self>> {
        let h = Self::open_fenced(root, generation, pocket_owned, remote, cp)?;
        h.complete_successor(cp, current)?;
        Ok(h)
    }
    /// Preserve the predecessor file while discovery runs under a closed gate.
    pub fn open_fenced(
        root: &Path,
        generation: Uuid,
        pocket_owned: bool,
        _remote: bool,
        cp: &CheckpointRef,
    ) -> Result<Arc<Self>> {
        if generation.is_nil() || generation == cp.generation || !cp.valid() {
            return Err(error("invalid_params"));
        }
        if !pocket_owned {
            return Err(error("unsupported"));
        }
        let mut durable = Self::read_durable(root)?.ok_or_else(|| error("checkpoint_mismatch"))?;
        Self::check_recovery(&durable, cp)?;
        // The released operation records the effective pre-fence gate. Startup
        // desired state may be stale and must not replace that handoff evidence.
        let remote = durable
            .operations
            .values()
            .find(|o| {
                o.receipt.phase == Phase::Released && o.receipt.checkpoint.as_ref() == Some(cp)
            })
            .ok_or_else(|| error("checkpoint_mismatch"))?
            .prior_remote;
        let verified = CheckpointStore::new(root).verify_stored(cp)?;
        durable.generation = generation;
        let (shutdown, _) = watch::channel(false);
        Ok(Arc::new(Self {
            root: root.into(),
            pocket_owned,
            state: Mutex::new(State {
                durable,
                writes: BTreeSet::new(),
                remote: false,
                accepting: false,
                startup: Some(Startup {
                    checkpoint: cp.clone(),
                    remote,
                }),
            }),
            shutdown,
            host_descriptors: Mutex::new(BTreeMap::new()),
            recovery: Mutex::new(Some(verified.snapshot)),
        }))
    }
    fn check_recovery(d: &Durable, cp: &CheckpointRef) -> Result<()> {
        if d.recovery_checkpoint.as_ref() != Some(cp)
            || !d.operations.values().any(|o| {
                o.receipt.phase == Phase::Released && o.receipt.checkpoint.as_ref() == Some(cp)
            })
            || d.operations
                .values()
                .any(|o| !matches!(o.receipt.phase, Phase::Released | Phase::Cancelled))
        {
            return Err(error("checkpoint_mismatch"));
        }
        Ok(())
    }
    pub fn recovery_checkpoint(root: &Path) -> Result<Option<CheckpointRef>> {
        let Some(d) = Self::read_durable(root)? else {
            return Ok(None);
        };
        let Some(cp) = d.recovery_checkpoint.as_ref() else {
            return Ok(None);
        };
        if d.generation != cp.generation {
            return Ok(None);
        }
        Self::check_recovery(&d, cp)?;
        Ok(Some(cp.clone()))
    }
    pub fn expected_checkpoint(&self) -> Option<CheckpointRef> {
        self.state
            .lock()
            .unwrap()
            .startup
            .as_ref()
            .map(|s| s.checkpoint.clone())
    }
    pub fn complete_successor(
        &self,
        cp: &CheckpointRef,
        current: &Snapshot,
    ) -> Result<CheckpointImage> {
        let mut s = self.state.lock().unwrap();
        let boot = s
            .startup
            .as_ref()
            .ok_or_else(|| error("handoff_conflict"))?;
        if &boot.checkpoint != cp || !s.writes.is_empty() {
            return Err(error("checkpoint_mismatch"));
        }
        let remote = boot.remote;
        let image = CheckpointStore::new(&self.root).verify(cp, &current.computer_id)?;
        image.snapshot.verify_successor(current)?;
        let disk = Self::read_durable(&self.root)?.ok_or_else(|| error("checkpoint_mismatch"))?;
        Self::check_recovery(&disk, cp)?;
        let mut next = disk;
        next.generation = s.durable.generation;
        self.persist(&next)?;
        *self.recovery.lock().unwrap() = Some(image.snapshot.clone());
        s.durable = next;
        s.startup = None;
        s.accepting = true;
        s.remote = remote;
        Ok(image)
    }
    /// Fenced startup returns the predecessor image already checked against its
    /// ref, summary and physical protected files. This supplies restore_assistant
    /// with the retained Host cursor; only complete_successor verifies live
    /// identities and opens writes. No native request is sent by this accessor.
    pub fn recovery_snapshot(&self) -> Option<Snapshot> {
        self.recovery.lock().unwrap().clone()
    }
    pub fn replay_recovery(&self, runtime: &str, cursor: &Cursor) -> Replay {
        // Before adoption only restore_assistant may use the verified image as
        // an attach hint; it is not yet proof of the current runtime inventory.
        if self.state.lock().unwrap().startup.is_some() {
            return Replay::SnapshotRequired;
        }
        let image = self.recovery.lock().unwrap();
        let Some(archive) = image.as_ref().and_then(|s| s.events.get(runtime)) else {
            return Replay::SnapshotRequired;
        };
        if cursor.sequence >= archive.cursor.sequence {
            return Replay::SnapshotRequired;
        }
        archive.replay(cursor)
    }
    fn read_durable(root: &Path) -> Result<Option<Durable>> {
        let path = root.join("handoff/state.json");
        reject_links(&path)?;
        if !path.try_exists().map_err(|_| error("unavailable"))? {
            return Ok(None);
        }
        let bytes =
            crate::storage::read_physical(&path, MAX_IMAGE).map_err(|_| error("unavailable"))?;
        let d: Durable = serde_json::from_slice(&bytes).map_err(|_| error("unavailable"))?;
        if d.protocol != 1
            || d.generation.is_nil()
            || d.operations.len() > MAX_OPERATIONS
            || d.operations.iter().any(|(id, o)| {
                id != &o.receipt.id
                    || id.is_nil()
                    || o.receipt.protocol != 1
                    || o.receipt.generation.is_nil()
            })
        {
            return Err(error("unavailable"));
        }
        let mut active = 0;
        for o in d.operations.values() {
            let r = &o.receipt;
            if r.checkpoint
                .as_ref()
                .is_some_and(|cp| !cp.valid() || cp.generation != r.generation)
            {
                return Err(error("unavailable"));
            }
            if matches!(
                r.phase,
                Phase::Ready | Phase::ReleasePending | Phase::Released
            ) && (!r.safe_to_exit
                || !r.native_tasks_preserved
                || r.checkpoint.is_none()
                || !r.blockers.is_empty()
                || r.unconfirmed_writes != 0
                || r.accepting_writes
                || r.remote_enabled)
            {
                return Err(error("unavailable"));
            }
            if matches!(r.phase, Phase::Draining | Phase::Failed | Phase::Cancelled)
                && r.safe_to_exit
            {
                return Err(error("unavailable"));
            }
            if matches!(r.phase, Phase::ReleasePending | Phase::Released)
                && o.release_input
                    .as_ref()
                    .is_none_or(|cp| !cp.valid() || cp.generation != r.generation)
            {
                return Err(error("unavailable"));
            }
            if r.generation == d.generation
                && !matches!(r.phase, Phase::Cancelled | Phase::Released)
            {
                active += 1;
            }
        }
        if active > 1 {
            return Err(error("unavailable"));
        }
        Ok(Some(d))
    }
    fn open_internal(
        root: &Path,
        generation: Uuid,
        pocket_owned: bool,
        remote: bool,
        successor: bool,
    ) -> Result<Arc<Self>> {
        if generation.is_nil() {
            return Err(error("invalid_params"));
        }
        let mut durable = Self::read_durable(root)?.unwrap_or(Durable {
            protocol: 1,
            generation,
            operations: BTreeMap::new(),
            recovery_checkpoint: None,
        });
        if durable.generation != generation {
            if !successor
                && durable.operations.values().any(|o| {
                    o.receipt.generation == durable.generation
                        && o.receipt.phase != Phase::Cancelled
                })
            {
                return Err(error("checkpoint_mismatch"));
            }
            durable.generation = generation;
        }
        // Persisted handoffs stay fenced on recovery; never infer a flush from
        // a pending release. Even Released requires an explicit callback here.
        let active = durable
            .operations
            .values()
            .any(|o| o.receipt.generation == generation && o.receipt.phase != Phase::Cancelled);
        let (shutdown, _) = watch::channel(false);
        let h = Arc::new(Self {
            root: root.into(),
            pocket_owned,
            state: Mutex::new(State {
                durable,
                writes: BTreeSet::new(),
                remote: remote && !active,
                accepting: !active,
                startup: None,
            }),
            shutdown,
            host_descriptors: Mutex::new(BTreeMap::new()),
            recovery: Mutex::new(None),
        });
        h.persist(&h.state.lock().unwrap().durable)?;
        Ok(h)
    }
    pub fn state_dir(&self) -> &Path {
        &self.root
    }
    pub fn remote_enabled(&self) -> bool {
        self.state.lock().unwrap().remote
    }
    pub fn accepting_writes(&self) -> bool {
        self.state.lock().unwrap().accepting
    }
    pub fn shutdown_receiver(&self) -> watch::Receiver<bool> {
        self.shutdown.subscribe()
    }
    /// The attaching main controller already has this trusted local descriptor.
    /// No descriptor path is accepted through the upgrade wire protocol.
    pub fn register_host_descriptor(&self, runtime: &str, path: &Path) -> Result<()> {
        let s = self.state.lock().unwrap();
        let recovering_released = s.durable.operations.values().any(|o| {
            o.receipt.generation == s.durable.generation && o.receipt.phase == Phase::Released
        }) && !*self.shutdown.borrow();
        if !s.accepting && !recovering_released && s.startup.is_none() {
            return Err(error("write_fenced"));
        }
        if runtime.is_empty() || !path.is_absolute() {
            return Err(error("invalid_params"));
        }
        self.host_descriptors
            .lock()
            .unwrap()
            .insert(runtime.into(), path.into());
        Ok(())
    }
    pub fn host_descriptor(&self, runtime: &str) -> Option<PathBuf> {
        self.host_descriptors.lock().unwrap().get(runtime).cloned()
    }
    /// Apply the effective gate while holding the transition lock, so a late
    /// quiesce completion cannot overwrite a successfully cancelled gate.
    pub fn sync_remote_gate(&self, apply: impl FnOnce(bool)) {
        let s = self.state.lock().unwrap();
        apply(s.remote);
    }
    /// Preference refresh and effective Connection gate publication are one
    /// transition. A periodic refresh can never reopen a quiescing controller.
    pub fn apply_remote_preference(&self, enabled: bool, apply: impl FnOnce(bool)) {
        let mut state = self.state.lock().unwrap();
        if state.accepting {
            state.remote = enabled;
        }
        apply(state.remote);
    }
    pub fn capabilities(&self, p: &Value) -> Result<Value> {
        if p.as_object().is_none_or(|o| !o.is_empty()) {
            return Err(error("invalid_params"));
        }
        Ok(
            json!({"protocol":1,"supported":self.pocket_owned,"generation":self.state.lock().unwrap().durable.generation,"owner":"pocket-agent","methods":METHODS,"checkpoint":"sha256","shutdown":"after_reply_flush","writeFence":true,"preservesIdentity":true}),
        )
    }
    fn check(&self, s: &State, p: &Params) -> Result<()> {
        p.validate()?;
        if !self.pocket_owned {
            return Err(error("unsupported"));
        }
        if p.from_generation != s.durable.generation {
            return Err(error("stale_generation"));
        }
        if p.checkpoint
            .as_ref()
            .is_some_and(|cp| cp.generation != p.from_generation)
        {
            return Err(error("checkpoint_mismatch"));
        }
        Ok(())
    }
    fn persist(&self, d: &Durable) -> Result<()> {
        let bytes = serde_json::to_vec(d).map_err(|_| error("unavailable"))?;
        let path = self.root.join("handoff/state.json");
        reject_links(&path)?;
        crate::storage::atomic_write(&path, &bytes).map_err(|_| error("unavailable"))?;
        let read =
            crate::storage::read_physical(&path, MAX_IMAGE).map_err(|_| error("unavailable"))?;
        if read != bytes {
            return Err(error("unavailable"));
        }
        Ok(())
    }
    pub fn admit_write(self: &Arc<Self>) -> Result<WritePermit> {
        let mut s = self.state.lock().unwrap();
        if !s.accepting {
            return Err(error("write_fenced"));
        }
        let token = Uuid::new_v4();
        s.writes.insert(token);
        Ok(WritePermit {
            handoff: self.clone(),
            token,
        })
    }
    pub fn confirm_write(&self, token: Uuid) -> Result<()> {
        let mut s = self.state.lock().unwrap();
        // Confirmation is idempotent; it never changes gates or a newer run.
        s.writes.remove(&token);
        Ok(())
    }
    /// Fence before waiting for Broker.writes or taking any snapshot.
    pub fn begin_quiesce(&self, p: &Params) -> Result<Receipt> {
        let mut s = self.state.lock().unwrap();
        self.check(&s, p)?;
        if p.checkpoint.is_some() {
            return Err(error("invalid_params"));
        }
        if let Some(o) = s.durable.operations.get(&p.id) {
            if o.receipt.generation != p.from_generation
                || matches!(
                    o.receipt.phase,
                    Phase::Cancelled | Phase::ReleasePending | Phase::Released
                )
            {
                return Err(error("handoff_conflict"));
            }
            if o.receipt.phase == Phase::Ready {
                CheckpointStore::new(&self.root)
                    .verify_stored(o.receipt.checkpoint.as_ref().unwrap())?;
            }
            return Ok(o.receipt.clone());
        }
        if s.durable
            .operations
            .values()
            .any(|o| o.receipt.phase != Phase::Cancelled && o.receipt.phase != Phase::Released)
            || s.durable.operations.len() >= MAX_OPERATIONS
            || !s.accepting
        {
            return Err(error("handoff_conflict"));
        }
        let prior_remote = s.remote;
        s.remote = false;
        s.accepting = false;
        let receipt = Receipt {
            protocol: 1,
            id: p.id,
            generation: p.from_generation,
            phase: Phase::Draining,
            safe_to_exit: false,
            native_tasks_preserved: false,
            remote_enabled: false,
            accepting_writes: false,
            unconfirmed_writes: s.writes.len(),
            blockers: BTreeSet::new(),
            checkpoint: None,
            shutdown_requested: false,
        };
        s.durable.operations.insert(
            p.id,
            Operation {
                receipt: receipt.clone(),
                prior_remote,
                release_input: None,
            },
        );
        if self.persist(&s.durable).is_err() {
            let o = s.durable.operations.get_mut(&p.id).unwrap();
            o.receipt.phase = Phase::Failed;
            o.receipt.blockers.insert("checkpoint_failed".into());
            return Err(error("unavailable"));
        }
        Ok(receipt)
    }
    pub fn quiesce(&self, p: &Params, snapshot: Snapshot) -> Result<Receipt> {
        let first = self.begin_quiesce(p)?;
        if first.phase == Phase::Ready {
            return Ok(first);
        }
        self.progress(p, snapshot)
    }
    /// status polling may call this after observing draining/failed. A cancelled
    /// or newer operation cannot be resurrected by a late collector completion.
    pub fn progress(&self, p: &Params, snapshot: Snapshot) -> Result<Receipt> {
        let mut s = self.state.lock().unwrap();
        self.check(&s, p)?;
        let o = s
            .durable
            .operations
            .get(&p.id)
            .ok_or_else(|| error("handoff_conflict"))?;
        if o.receipt.generation != p.from_generation {
            return Err(error("stale_generation"));
        }
        if let Some(cp) = &p.checkpoint {
            if Some(cp) != o.receipt.checkpoint.as_ref() {
                return Err(error("checkpoint_mismatch"));
            }
        }
        if o.receipt.phase == Phase::Ready {
            return Ok(o.receipt.clone());
        }
        if !matches!(o.receipt.phase, Phase::Draining | Phase::Failed) {
            return Err(error("handoff_conflict"));
        }
        let (mut blockers, preserved) = snapshot.safety();
        let unconfirmed = s.writes.len().saturating_add(snapshot.unconfirmed());
        if unconfirmed > 0 {
            blockers.insert("unconfirmed_writes".into());
        }
        let mut receipt = o.receipt.clone();
        receipt.phase = Phase::Draining;
        receipt.safe_to_exit = false;
        receipt.native_tasks_preserved = preserved;
        receipt.unconfirmed_writes = unconfirmed;
        receipt.blockers = blockers;
        receipt.checkpoint = None;
        if snapshot.validate().is_err() || receipt.blockers.contains("checkpoint_failed") {
            receipt.phase = Phase::Failed;
            receipt.blockers.insert("checkpoint_failed".into());
        } else if receipt.blockers.is_empty() {
            match CheckpointStore::new(&self.root).save(p.from_generation, snapshot) {
                Ok(cp) => {
                    receipt.phase = Phase::Ready;
                    receipt.safe_to_exit = true;
                    receipt.checkpoint = Some(cp);
                }
                Err(_) => {
                    receipt.phase = Phase::Failed;
                    receipt.blockers.insert("checkpoint_failed".into());
                }
            }
        }
        let mut next = s.durable.clone();
        next.operations.get_mut(&p.id).unwrap().receipt = receipt.clone();
        if self.persist(&next).is_err() {
            receipt.phase = Phase::Failed;
            receipt.safe_to_exit = false;
            receipt.checkpoint = None;
            receipt.blockers.insert("checkpoint_failed".into());
            s.durable.operations.get_mut(&p.id).unwrap().receipt = receipt.clone();
        } else {
            s.durable = next;
        }
        Ok(receipt)
    }
    pub fn status(&self, p: &Params) -> Result<Receipt> {
        let s = self.state.lock().unwrap();
        self.check(&s, p)?;
        let o = s
            .durable
            .operations
            .get(&p.id)
            .ok_or_else(|| error("handoff_conflict"))?;
        if o.receipt.generation != p.from_generation {
            return Err(error("stale_generation"));
        }
        if p.checkpoint
            .as_ref()
            .is_some_and(|cp| o.receipt.checkpoint.as_ref() != Some(cp))
        {
            return Err(error("checkpoint_mismatch"));
        }
        if matches!(
            o.receipt.phase,
            Phase::Ready | Phase::ReleasePending | Phase::Released
        ) {
            CheckpointStore::new(&self.root)
                .verify_stored(o.receipt.checkpoint.as_ref().unwrap())?;
        }
        Ok(o.receipt.clone())
    }
    pub fn release(&self, p: &Params, snapshot: Snapshot) -> Result<Receipt> {
        let mut s = self.state.lock().unwrap();
        self.check(&s, p)?;
        let o = s
            .durable
            .operations
            .get(&p.id)
            .ok_or_else(|| error("not_ready"))?;
        if o.receipt.generation != p.from_generation {
            return Err(error("stale_generation"));
        }
        let cp = p
            .checkpoint
            .as_ref()
            .ok_or_else(|| error("checkpoint_mismatch"))?;
        if matches!(o.receipt.phase, Phase::ReleasePending | Phase::Released) {
            if o.release_input.as_ref() != Some(cp) {
                return Err(error("checkpoint_mismatch"));
            }
            return Ok(o.receipt.clone());
        }
        if o.receipt.phase != Phase::Ready {
            return Err(error("not_ready"));
        }
        if o.receipt.checkpoint.as_ref() != Some(cp) {
            return Err(error("checkpoint_mismatch"));
        }
        let prior = CheckpointStore::new(&self.root).verify(cp, &snapshot.computer_id)?;
        prior.snapshot.verify_successor(&snapshot)?;
        snapshot.validate()?;
        let (mut blockers, preserved) = snapshot.safety();
        let unconfirmed = s.writes.len().saturating_add(snapshot.unconfirmed());
        if unconfirmed > 0 {
            blockers.insert("unconfirmed_writes".into());
        }
        if !blockers.is_empty() || !preserved {
            return Err(error("not_ready"));
        }
        let final_cp = CheckpointStore::new(&self.root).save(p.from_generation, snapshot)?;
        let mut next = s.durable.clone();
        next.recovery_checkpoint = Some(final_cp.clone());
        let o = next.operations.get_mut(&p.id).unwrap();
        o.release_input = Some(cp.clone());
        o.receipt.phase = Phase::ReleasePending;
        o.receipt.checkpoint = Some(final_cp);
        let receipt = o.receipt.clone();
        self.persist(&next)?;
        s.durable = next;
        Ok(receipt)
    }
    pub fn release_flushed(&self, p: &Params) -> Result<()> {
        let mut s = self.state.lock().unwrap();
        self.check(&s, p)?;
        let o = s
            .durable
            .operations
            .get(&p.id)
            .ok_or_else(|| error("not_ready"))?;
        if o.receipt.generation != p.from_generation {
            return Err(error("stale_generation"));
        }
        if o.release_input != p.checkpoint || p.checkpoint.is_none() {
            return Err(error("checkpoint_mismatch"));
        }
        if !matches!(o.receipt.phase, Phase::ReleasePending | Phase::Released) {
            return Err(error("not_ready"));
        }
        CheckpointStore::new(&self.root).verify_stored(
            o.receipt
                .checkpoint
                .as_ref()
                .ok_or_else(|| error("checkpoint_mismatch"))?,
        )?;
        if o.receipt.phase == Phase::ReleasePending {
            let mut next = s.durable.clone();
            let r = &mut next.operations.get_mut(&p.id).unwrap().receipt;
            r.phase = Phase::Released;
            r.shutdown_requested = true;
            self.persist(&next)?;
            s.durable = next;
        }
        self.shutdown.send_if_modified(|requested| {
            if *requested {
                false
            } else {
                *requested = true;
                true
            }
        });
        Ok(())
    }
    pub fn cancel(&self, p: &Params) -> Result<Receipt> {
        let mut s = self.state.lock().unwrap();
        self.check(&s, p)?;
        let o = s
            .durable
            .operations
            .get(&p.id)
            .ok_or_else(|| error("handoff_conflict"))?;
        if o.receipt.generation != p.from_generation {
            return Err(error("stale_generation"));
        }
        if p.checkpoint
            .as_ref()
            .is_some_and(|cp| o.receipt.checkpoint.as_ref() != Some(cp))
        {
            return Err(error("checkpoint_mismatch"));
        }
        if o.receipt.phase == Phase::Cancelled {
            return Ok(o.receipt.clone());
        }
        if !matches!(
            o.receipt.phase,
            Phase::Draining | Phase::Ready | Phase::Failed
        ) {
            return Err(error("handoff_conflict"));
        }
        let prior_remote = o.prior_remote;
        let mut next = s.durable.clone();
        let receipt = &mut next.operations.get_mut(&p.id).unwrap().receipt;
        receipt.phase = Phase::Cancelled;
        receipt.safe_to_exit = false;
        receipt.accepting_writes = true;
        receipt.remote_enabled = prior_remote;
        let receipt = receipt.clone();
        self.persist(&next)?;
        s.durable = next;
        s.remote = prior_remote;
        s.accepting = true;
        Ok(receipt)
    }
}

/// Admission and completion are distinct. Dropping this object deliberately
/// leaves its token unconfirmed; cancellation cannot manufacture a result.
pub struct WritePermit {
    handoff: Arc<Handoff>,
    token: Uuid,
}
impl WritePermit {
    pub fn token(&self) -> Uuid {
        self.token
    }
    pub fn confirm(self) -> Result<()> {
        self.handoff.confirm_write(self.token)
    }
}

#[cfg(test)]
mod protection_tests {
    use super::*;

    fn snapshot() -> Snapshot {
        let ledger = CommandLedger::new(
            RuntimeId {
                computer_id: "fixture".into(),
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
        Snapshot {
            computer_id: "fixture".into(),
            ledger: serde_json::from_str(&ledger.snapshot_json()).unwrap(),
            events: BTreeMap::new(),
            runtimes: BTreeMap::new(),
            pending: json!({}),
            ownership: json!([]),
            question_receipts: json!({"records":[]}),
            protected_files: BTreeMap::new(),
            blockers: BTreeSet::new(),
        }
    }

    #[test]
    fn fenced_boot_exposes_verified_predecessor_cursor_before_adoption() {
        let dir = tempfile::tempdir().unwrap();
        let from = Uuid::from_u128(1);
        let h = Handoff::open(dir.path(), from, true, false).unwrap();
        let cursor = HostCursor {
            generation: "retained-host-generation".into(),
            after: 123,
        };
        let mut image = snapshot();
        let journal = EventJournal::new(
            RuntimeId {
                computer_id: "fixture".into(),
                runtime_id: "host".into(),
            },
            pocket_core::journal::JournalConfig {
                max_events: 8,
                max_bytes: 8192,
            },
        )
        .unwrap();
        image.events.insert(
            "host".into(),
            EventArchive::capture(&journal, Some(cursor.clone())),
        );
        image.runtimes.insert(
            "host".into(),
            RuntimeIdentity {
                runtime_id: "host".into(),
                store: "fixture-store".into(),
                connection_id: "old-connection".into(),
                native_process: Some(NativeProcess {
                    pid: 17,
                    created_at: 123,
                }),
                lifecycle: Lifecycle::ManagedHostExternal,
                inventory_verified: true,
                active: Some(false),
                host_cursor: Some(cursor.clone()),
                host_process: Some(NativeProcess {
                    pid: 18,
                    created_at: 124,
                }),
                reconciliation_required: false,
            },
        );
        let mut p = Params {
            id: Uuid::from_u128(2),
            from_generation: from,
            checkpoint: None,
        };
        let ready = h.quiesce(&p, image.clone()).unwrap();
        assert_eq!(ready.phase, Phase::Ready);
        p.checkpoint = ready.checkpoint;
        let released = h.release(&p, image.clone()).unwrap();
        h.release_flushed(&p).unwrap();
        let cp = released.checkpoint.unwrap();
        let boot = Handoff::open_fenced(dir.path(), Uuid::from_u128(3), true, false, &cp).unwrap();
        assert_eq!(
            boot.recovery_snapshot()
                .and_then(|s| s.runtimes.get("host").and_then(|r| r.host_cursor.clone())),
            Some(cursor)
        );
        assert!(!boot.accepting_writes());
        assert!(!boot.remote_enabled());
        assert!(boot.admit_write().is_err());
        let mut wrong = image.clone();
        wrong
            .runtimes
            .get_mut("host")
            .unwrap()
            .native_process
            .as_mut()
            .unwrap()
            .created_at += 1;
        assert!(boot.complete_successor(&cp, &wrong).is_err());
        assert!(!boot.accepting_writes());
        boot.complete_successor(&cp, &image).unwrap();
        assert!(boot.accepting_writes());
        let mut corrupt = cp;
        corrupt.hash = "0".repeat(64);
        assert!(
            Handoff::open_fenced(dir.path(), Uuid::from_u128(4), true, false, &corrupt).is_err()
        );
    }

    #[test]
    fn protected_file_reader_rejects_hard_links() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("original"), b"identity").unwrap();
        std::fs::hard_link(dir.path().join("original"), dir.path().join("linked")).unwrap();
        assert!(
            CheckpointStore::new(dir.path())
                .protected_hash("linked")
                .is_err()
        );
    }

    #[test]
    fn protected_contract_bounds_bytes_and_records_absence() {
        let dir = tempfile::tempdir().unwrap();
        let store = CheckpointStore::new(dir.path());
        let files = store.capture_protected().unwrap();
        assert_eq!(files.len(), PROTECTED_FILES.len());
        assert!(files.values().all(Option::is_none));
        let mut image = snapshot();
        image.protected_files = files.clone();
        let cp = store.save(Uuid::from_u128(1), image).unwrap();
        std::fs::create_dir(dir.path().join("logs")).unwrap();
        std::fs::File::create(dir.path().join("logs/ignored"))
            .unwrap()
            .set_len(MAX_PROTECTED_BYTES + 1)
            .unwrap();
        assert_eq!(store.capture_protected().unwrap(), files);
        std::fs::create_dir(dir.path().join("identity")).unwrap();
        std::fs::File::create(dir.path().join("identity/certificate.der"))
            .unwrap()
            .set_len(64 * 1024 + 1)
            .unwrap();
        assert!(store.capture_protected().is_err());
        assert!(store.verify(&cp, "fixture").is_err());
        std::fs::remove_file(dir.path().join("identity/certificate.der")).unwrap();
        std::fs::create_dir(dir.path().join("drafts")).unwrap();
        for n in 0..5 {
            std::fs::File::create(dir.path().join(format!("drafts/{n}.json")))
                .unwrap()
                .set_len(MAX_PROTECTED_FILE)
                .unwrap();
        }
        assert!(store.capture_protected().is_err());
    }

    #[test]
    fn old_root_only_product_checkpoint_is_explicitly_unsupported() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("config.json"), b"fixture config").unwrap();
        let store = CheckpointStore::new(dir.path());
        let mut image = snapshot();
        image.protected_files.insert(
            "config.json".into(),
            store.protected_hash("config.json").unwrap(),
        );
        assert_eq!(
            store.save(Uuid::from_u128(1), image).unwrap_err().code,
            "unsupported"
        );
    }

    #[test]
    fn file_changes_after_fenced_preload_still_block_adoption() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir(dir.path().join("drafts")).unwrap();
        let draft = dir.path().join("drafts/unsent.json");
        std::fs::write(&draft, b"original draft").unwrap();
        let store = CheckpointStore::new(dir.path());
        let mut image = snapshot();
        image.protected_files = store.capture_protected().unwrap();
        let from = Uuid::from_u128(1);
        let h = Handoff::open(dir.path(), from, true, false).unwrap();
        let mut p = Params {
            id: Uuid::from_u128(2),
            from_generation: from,
            checkpoint: None,
        };
        p.checkpoint = h.quiesce(&p, image.clone()).unwrap().checkpoint;
        let cp = h.release(&p, image.clone()).unwrap().checkpoint.unwrap();
        h.release_flushed(&p).unwrap();
        let boot = Handoff::open_fenced(dir.path(), Uuid::from_u128(3), true, false, &cp).unwrap();
        assert!(boot.recovery_snapshot().is_some());
        std::fs::write(&draft, b"changed draft").unwrap();
        assert!(boot.complete_successor(&cp, &image).is_err());
        assert!(!boot.accepting_writes());
        assert_eq!(Handoff::recovery_checkpoint(dir.path()).unwrap(), Some(cp));
    }

    #[cfg(windows)]
    #[test]
    fn protected_contract_rejects_draft_junctions() {
        use std::os::windows::process::CommandExt;
        let dir = tempfile::tempdir().unwrap();
        let other = tempfile::tempdir().unwrap();
        std::fs::write(other.path().join("draft.json"), b"outside").unwrap();
        let link = dir.path().join("drafts");
        let result = std::process::Command::new("cmd.exe")
            .args(["/c", "mklink", "/J"])
            .arg(&link)
            .arg(other.path())
            .creation_flags(0x08000000)
            .output()
            .unwrap();
        assert!(result.status.success());
        assert!(
            CheckpointStore::new(dir.path())
                .capture_protected()
                .is_err()
        );
        std::fs::remove_dir(link).unwrap();
    }
}
