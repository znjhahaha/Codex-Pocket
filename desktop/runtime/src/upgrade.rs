//! Durable handoff coordinator. No installer mutation, process termination or model RPCs.
//! `HandoffBackend` is a trusted adapter: it must independently verify live identities,
//! fence writes, preserve native tasks, and make actions idempotent by operation ID.
//! Existing stdio bridges stay running and require a later desktop restart.
use crate::{launcher, storage};
use anyhow::{ensure, Context, Result};
use serde::{Deserialize, Serialize};
use std::{
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

pub const CONTROL_PROTOCOL: u32 = 1;
const SCHEMA: u32 = 1;
const JOURNAL: &str = "upgrade.json";
#[path = "upgrade_local.rs"]
pub mod local;
pub use local::LocalBackend;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Requested,
    Quiescing,
    Checkpointed,
    Releasing,
    Starting,
    Verifying,
    RollingBack,
    WaitingRestart,
    RolledBack,
    Succeeded,
    Failed,
}
impl Phase {
    pub fn terminal(self) -> bool {
        matches!(
            self,
            Self::WaitingRestart | Self::RolledBack | Self::Succeeded | Self::Failed
        )
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Checkpoint {
    pub operation_id: String,
    pub generation: String,
    pub saved_sha256: String,
    pub reread_sha256: String,
    pub pending_writes: u64,
    pub writes_fenced: bool,
    pub native_tasks_preserved: bool,
}
impl Checkpoint {
    fn valid(&self, s: &UpgradeStatus) -> bool {
        self.operation_id == s.operation_id
            && self.generation == s.from_generation
            && digest(&self.saved_sha256)
            && self.saved_sha256 == self.reread_sha256
            && self.pending_writes == 0
            && self.writes_fenced
            && self.native_tasks_preserved
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Verification {
    pub operation_id: String,
    pub generation: String,
    pub control_protocol: u32,
    pub routes_read: bool,
    pub checkpoint_sha256: Option<String>,
    pub native_tasks_preserved: bool,
    pub components_verified: bool,
}
impl Verification {
    fn valid(&self, s: &UpgradeStatus, generation: &str) -> bool {
        self.operation_id == s.operation_id
            && self.generation == generation
            && self.control_protocol == CONTROL_PROTOCOL
            && self.routes_read
            && self.native_tasks_preserved
            && self.components_verified
            && self.checkpoint_sha256 == s.checkpoint.as_ref().map(|c| c.saved_sha256.clone())
    }
}

/// Safe, fixed classifications; never persist adapter error text or control tokens.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Failure {
    HandoffUnsupported,
    CapabilityFailed,
    QuiesceFailed,
    CheckpointMismatch,
    ReleaseFailed,
    StartFailed,
    VerificationFailed,
    RollbackFailed,
    CoordinatorInterrupted,
    InstallationChanged,
    DispatchFailed,
    JournalInvalid,
    StartupContextInvalid,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct UpgradeStatus {
    pub schema: u32,
    pub operation_id: String,
    pub install_root: PathBuf,
    pub state_directory: PathBuf,
    pub from_generation: String,
    pub to_generation: String,
    pub phase: Phase,
    pub updated_at: u64,
    pub checkpoint: Option<Checkpoint>,
    pub verification: Option<Verification>,
    pub failure: Option<Failure>,
    /// Background handoff does not replace an existing desktop stdio bridge.
    #[serde(default = "bridge_restart_required")]
    pub bridge_restart_required: bool,
}
fn bridge_restart_required() -> bool {
    true
}

/// No method may kill a process, abort native work, replay pending writes, or
/// report a launch/HTTP 200 as verification. Rollback must safely cancel a fence
/// or quiesce the candidate, restore the old background, and verify it again.
/// The coordinator journals intent BEFORE every potentially changing action.
pub trait HandoffBackend {
    fn supports_handoff(&mut self, status: &UpgradeStatus) -> Result<bool>;
    fn quiesce(&mut self, status: &UpgradeStatus) -> Result<Checkpoint>;
    fn release_old(&mut self, status: &UpgradeStatus) -> Result<()>;
    /// Release may save a newer final snapshot than the ready checkpoint.
    fn released_checkpoint(&mut self, _: &UpgradeStatus) -> Result<Option<Checkpoint>> {
        Ok(None)
    }
    fn start_new(&mut self, status: &UpgradeStatus) -> Result<()>;
    fn verify_new(&mut self, status: &UpgradeStatus) -> Result<Verification>;
    fn rollback(&mut self, status: &UpgradeStatus) -> Result<Verification>;
}

fn digest(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn verified_generation(root: &Path, generation: &str) -> Result<PathBuf> {
    ensure!(digest(generation), "Invalid installation generation");
    let bin = root.join("bin").join(generation);
    storage::unredirected(&bin)?;
    ensure!(
        crate::installation::generation(&bin)? == generation,
        "Generation manifest changed"
    );
    local::verify_generation_inventory(&bin)?;
    Ok(bin)
}

fn installed(root: &Path, state: &Path) -> Result<String> {
    storage::unredirected(root)?;
    storage::unredirected(state)?;
    let (bin, recorded_state) = launcher::resolve_release(root)?;
    ensure!(recorded_state == state, "State does not match installation");
    ensure!(
        state.join("config.json").is_file(),
        "Initialized state required"
    );
    storage::unredirected(&state.join("config.json"))?;
    let generation = crate::installation::generation(&bin)?;
    verified_generation(root, &generation)?;
    Ok(generation)
}

fn validate(s: &UpgradeStatus, root: &Path, state: &Path) -> Result<()> {
    storage::unredirected(root)?;
    storage::unredirected(state)?;
    ensure!(
        s.schema == SCHEMA && local::canonical_uuid(&s.operation_id),
        "Invalid upgrade journal"
    );
    ensure!(
        s.install_root == root && s.state_directory == state,
        "Upgrade journal belongs to another installation"
    );
    ensure!(s.bridge_restart_required, "Bridge restart boundary missing");
    ensure!(
        digest(&s.from_generation)
            && digest(&s.to_generation)
            && s.from_generation != s.to_generation,
        "Invalid upgrade generations"
    );
    if let Some(c) = &s.checkpoint {
        ensure!(c.valid(s), "Invalid durable checkpoint");
    }
    if matches!(
        s.phase,
        Phase::Checkpointed
            | Phase::Releasing
            | Phase::Starting
            | Phase::Verifying
            | Phase::Succeeded
    ) {
        ensure!(s.checkpoint.is_some(), "Durable checkpoint required");
    }
    if matches!(s.phase, Phase::Succeeded | Phase::RolledBack) {
        let generation = if s.phase == Phase::Succeeded {
            &s.to_generation
        } else {
            &s.from_generation
        };
        ensure!(
            s.verification
                .as_ref()
                .is_some_and(|v| v.valid(s, generation)),
            "Durable verification required"
        );
    }
    Ok(())
}

/// Holds both the installation-wide AND state-wide OS locks for its lifetime.
/// Never removes a lock file: a crash releases the handle, not the recovery journal.
pub struct Coordinator {
    status: UpgradeStatus,
    journal: PathBuf,
    _root_lock: storage::Lock,
    _state_lock: storage::Lock,
}
impl Coordinator {
    fn locks(root: &Path, state: &Path) -> Result<(storage::Lock, storage::Lock, PathBuf)> {
        let owner = root.join("control");
        let control = storage::control(state);
        storage::private_dir(&owner)?;
        storage::private_dir(&control)?;
        let user_path = owner.join("upgrade.lock");
        let state_path = control.join("upgrade.lock");
        storage::unredirected(&user_path)?;
        storage::unredirected(&state_path)?;
        let user = storage::Lock::acquire(&user_path)?;
        let state_lock = storage::Lock::acquire(&state_path)?;
        Ok((user, state_lock, control.join(JOURNAL)))
    }

    pub fn request(root: &Path, state: &Path, from_generation: &str) -> Result<Self> {
        // Validate paths before creating either lock directory.
        storage::unredirected(root)?;
        storage::unredirected(state)?;
        let (root_lock, state_lock, journal) = Self::locks(root, state)?;
        let to_generation = installed(root, state)?;
        verified_generation(root, from_generation)?;
        ensure!(
            from_generation != to_generation,
            "Already installed generation; no handoff requested"
        );
        if journal.try_exists()? {
            let previous: UpgradeStatus = storage::read(&journal)?;
            validate(&previous, root, state)?;
            ensure!(
                previous.phase.terminal(),
                "An upgrade operation is already pending; resume it"
            );
            // Never overwrite rollback uncertainty with a fresh operation.
            ensure!(
                previous.phase != Phase::Failed,
                "Failed operation requires manual recovery"
            );
        }
        let s = UpgradeStatus {
            schema: SCHEMA,
            operation_id: uuid::Uuid::new_v4().to_string(),
            install_root: root.into(),
            state_directory: state.into(),
            from_generation: from_generation.into(),
            to_generation,
            phase: Phase::Requested,
            updated_at: storage::now(),
            checkpoint: None,
            verification: None,
            failure: None,
            bridge_restart_required: true,
        };
        storage::atomic(&journal, &s)?;
        Ok(Self {
            status: s,
            journal,
            _root_lock: root_lock,
            _state_lock: state_lock,
        })
    }

    pub fn open(root: &Path, state: &Path) -> Result<Self> {
        storage::unredirected(root)?;
        storage::unredirected(state)?;
        let (root_lock, state_lock, journal) = Self::locks(root, state)?;
        let status = storage::read(&journal)?;
        validate(&status, root, state)?;
        Ok(Self {
            status,
            journal,
            _root_lock: root_lock,
            _state_lock: state_lock,
        })
    }

    pub fn status(&self) -> &UpgradeStatus {
        &self.status
    }

    fn save(&mut self, phase: Phase, failure: Option<Failure>) -> Result<()> {
        let mut next = self.status.clone();
        next.phase = phase;
        next.failure = failure;
        next.updated_at = storage::now();
        validate(&next, &next.install_root, &next.state_directory)?;
        storage::atomic(&self.journal, &next)?;
        self.status = next;
        Ok(())
    }

    fn rollback(&mut self, b: &mut impl HandoffBackend, failure: Failure) -> Result<UpgradeStatus> {
        self.save(Phase::RollingBack, Some(failure))?;
        // Even after a coordinator crash this action must be idempotent and graceful.
        if verified_generation(&self.status.install_root, &self.status.from_generation).is_err() {
            self.save(Phase::Failed, Some(Failure::RollbackFailed))?;
        } else {
            match b.rollback(&self.status) {
                Ok(v) if v.valid(&self.status, &self.status.from_generation) => {
                    self.status.verification = Some(v);
                    self.save(Phase::RolledBack, Some(failure))?;
                }
                _ => {
                    self.status.verification = None;
                    self.save(Phase::Failed, Some(Failure::RollbackFailed))?;
                }
            }
        }
        Ok(self.status.clone())
    }

    pub fn execute(&mut self, b: &mut impl HandoffBackend) -> Result<UpgradeStatus> {
        if self.status.phase.terminal() {
            return Ok(self.status.clone());
        }
        // A persisted intent might already have executed before the crash. Never
        // replay a release/start or assume the old process still owns state.
        if self.status.phase != Phase::Requested {
            return self.rollback(b, Failure::CoordinatorInterrupted);
        }
        if installed(&self.status.install_root, &self.status.state_directory)
            .ok()
            .as_ref()
            != Some(&self.status.to_generation)
        {
            self.save(Phase::Failed, Some(Failure::InstallationChanged))?;
            return Ok(self.status.clone());
        }
        match b.supports_handoff(&self.status) {
            Ok(true) => {}
            Ok(false) => {
                self.save(Phase::WaitingRestart, Some(Failure::HandoffUnsupported))?;
                return Ok(self.status.clone());
            }
            Err(_) => {
                self.save(Phase::WaitingRestart, Some(Failure::CapabilityFailed))?;
                return Ok(self.status.clone());
            }
        }
        self.save(Phase::Quiescing, None)?;
        let checkpoint = match b.quiesce(&self.status) {
            Ok(c) if c.valid(&self.status) => c,
            Ok(_) => return self.rollback(b, Failure::CheckpointMismatch),
            Err(_) => return self.rollback(b, Failure::QuiesceFailed),
        };
        self.status.checkpoint = Some(checkpoint);
        self.save(Phase::Checkpointed, None)?;
        // The installation might have changed while the backend was saving.
        if installed(&self.status.install_root, &self.status.state_directory)
            .ok()
            .as_ref()
            != Some(&self.status.to_generation)
            || verified_generation(&self.status.install_root, &self.status.from_generation).is_err()
        {
            return self.rollback(b, Failure::InstallationChanged);
        }
        self.save(Phase::Releasing, None)?;
        if b.release_old(&self.status).is_err() {
            return self.rollback(b, Failure::ReleaseFailed);
        }
        match b.released_checkpoint(&self.status) {
            Ok(Some(c)) if c.valid(&self.status) => {
                self.status.checkpoint = Some(c);
            }
            Ok(None) => {}
            _ => return self.rollback(b, Failure::CheckpointMismatch),
        }
        self.save(Phase::Starting, None)?;
        if verified_generation(&self.status.install_root, &self.status.to_generation).is_err() {
            return self.rollback(b, Failure::InstallationChanged);
        }
        if b.start_new(&self.status).is_err() {
            return self.rollback(b, Failure::StartFailed);
        }
        self.save(Phase::Verifying, None)?;
        match b.verify_new(&self.status) {
            Ok(v) if v.valid(&self.status, &self.status.to_generation) => {
                self.status.verification = Some(v);
                self.save(Phase::Succeeded, None)?;
                Ok(self.status.clone())
            }
            _ => self.rollback(b, Failure::VerificationFailed),
        }
    }
}

/// Read-only API for the installation panel. Missing journal is not success.
pub fn read_status(state: &Path) -> Result<Option<UpgradeStatus>> {
    let path = storage::control(state).join(JOURNAL);
    storage::unredirected(&path)?;
    if !path.try_exists()? {
        return Ok(None);
    }
    let s: UpgradeStatus = storage::read(&path)?;
    validate(&s, &s.install_root, state)?;
    Ok(Some(s))
}

/// Queue and spawn the verified independent coordinator, outside all job objects.
/// UI exit does not cancel it. Never invoke during tests except in disposable fixtures.
pub fn request_upgrade(root: &Path, state: &Path, from_generation: &str) -> Result<UpgradeStatus> {
    let generation = installed(root, state)?;
    let executable =
        launcher::verified_exe(&root.join("bin").join(generation), "pocket-upgrade.exe")?;
    let c = Coordinator::request(root, state, from_generation)?;
    let mut status = c.status().clone();
    drop(c);
    if spawn(&executable, root, state, &status.operation_id).is_err() {
        let mut c = Coordinator::open(root, state)?;
        if c.status.operation_id == status.operation_id && c.status.phase == Phase::Requested {
            c.save(Phase::Failed, Some(Failure::DispatchFailed))?;
            status = c.status.clone();
        }
        return Ok(status);
    }
    Ok(status)
}

fn spawn(executable: &Path, root: &Path, state: &Path, operation_id: &str) -> Result<()> {
    let mut c = Command::new(executable);
    c.arg("--install-root")
        .arg(root)
        .arg("--state")
        .arg(state)
        .arg("--operation-id")
        .arg(operation_id)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // Break away from a caller job if permitted; failure is reported, never
        // silently attach the durable coordinator to a kill-on-close job.
        c.creation_flags(0x08000000 | 0x01000000);
    }
    c.spawn().context("Upgrade coordinator dispatch failed")?;
    Ok(())
}

/// Restart the same durable operation after a UI/coordinator restart.
pub fn resume_upgrade(root: &Path, state: &Path) -> Result<UpgradeStatus> {
    let c = Coordinator::open(root, state)?;
    let status = c.status().clone();
    drop(c);
    if status.phase.terminal() {
        return Ok(status);
    }
    let bin = verified_generation(root, &status.to_generation)?;
    let executable = launcher::verified_exe(&bin, "pocket-upgrade.exe")?;
    spawn(&executable, root, state, &status.operation_id)?;
    Ok(status)
}

pub fn run(root: &Path, state: &Path, operation_id: &str) -> Result<UpgradeStatus> {
    let mut c = Coordinator::open(root, state)?;
    ensure!(
        c.status().operation_id == operation_id,
        "Stale upgrade operation"
    );
    c.execute(&mut LocalBackend)
}
