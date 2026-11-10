//! Supervisor respawn ownership only. This is NOT agent quiescence or safe exit.
use crate::storage;
use anyhow::{bail, ensure, Context, Result};
use serde::{Deserialize, Serialize};
use std::{
    fs::File,
    io::Read,
    path::{Path, PathBuf},
};

pub const PROTOCOL: u32 = 1;
const JOURNAL: &str = "maintenance.json";
const MAILBOX: &str = "maintenance-request.json";

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProcessIdentity {
    pub pid: u32,
    pub created_at: u64,
    pub executable: PathBuf,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Action {
    Enter,
    Cancel,
    Recover,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Request {
    pub protocol: u32,
    pub operation_id: String,
    pub from_generation: String,
    pub supervisor: ProcessIdentity,
    pub coordinator: ProcessIdentity,
    pub child: Option<ProcessIdentity>,
    pub action: Action,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Owned,
    Cancelled,
    Recovered,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Journal {
    pub request: Request,
    pub phase: Phase,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Presence {
    Alive,
    Gone,
    Unknown,
}

struct Observation<'a> {
    generation: &'a str,
    operation_id: &'a str,
    supervisor: &'a ProcessIdentity,
    child: Option<&'a ProcessIdentity>,
}

fn transition(
    journal: &mut Option<Journal>,
    request: Option<&Request>,
    observed: &Observation<'_>,
    probe: impl Fn(&ProcessIdentity) -> Presence,
) -> Result<()> {
    if let Some(j) = journal.as_ref() {
        validate(&j.request)?;
        ensure!(j.request.action == Action::Enter, "Invalid journal intent");
        if j.request.supervisor != *observed.supervisor {
            // A terminal gate does not prove the old supervisor's child exited.
            // Never lose that distinction when recovering a supervisor crash.
            ensure!(
                j.request
                    .child
                    .as_ref()
                    .is_none_or(|p| probe(p) == Presence::Gone),
                "Previous supervisor child may still be running"
            );
        }
        if j.phase == Phase::Owned {
            ensure!(
                j.request.supervisor == *observed.supervisor
                    && j.request.from_generation == observed.generation,
                "Maintenance owner changed"
            );
            ensure!(
                observed.child.is_none() || observed.child == j.request.child.as_ref(),
                "Owned child changed"
            );
        }
    }
    let Some(r) = request else {
        if let Some(j) = journal.as_mut().filter(|j| j.phase == Phase::Owned) {
            // Absence means the EXACT process ended or its PID was reused.
            // Access denied / unavailable identity is never a crash proof.
            if probe(&j.request.coordinator) == Presence::Gone {
                j.phase = Phase::Recovered;
            }
        }
        return Ok(());
    };
    validate(r)?;
    ensure!(
        r.operation_id == observed.operation_id && r.from_generation == observed.generation,
        "Stale operation or source generation"
    );
    ensure!(
        r.supervisor == *observed.supervisor,
        "Supervisor identity changed"
    );
    ensure!(
        probe(&r.coordinator) == Presence::Alive,
        "Coordinator identity unverified"
    );
    if let Some(j) = journal.as_ref().filter(|j| j.phase == Phase::Owned) {
        ensure!(
            r.operation_id == j.request.operation_id
                && r.from_generation == j.request.from_generation,
            "Another operation owns maintenance"
        );
    }
    match r.action {
        Action::Enter => {
            if let Some(j) = journal
                .as_ref()
                .filter(|j| j.request.operation_id == r.operation_id)
            {
                ensure!(
                    same_owner(&j.request, r),
                    "Duplicate operation has another owner"
                );
                return Ok(()); // A cancelled/recovered Enter must not reacquire.
            }
            ensure!(
                journal.as_ref().is_none_or(|j| j.phase != Phase::Owned),
                "Maintenance busy"
            );
            ensure!(r.child.as_ref() == observed.child, "Child identity changed");
            ensure!(
                r.child.as_ref().is_none_or(|p| probe(p) == Presence::Alive),
                "Child unverified"
            );
            *journal = Some(Journal {
                request: r.clone(),
                phase: Phase::Owned,
            });
        }
        Action::Cancel => {
            let j = journal.as_mut().context("No owned operation")?;
            ensure!(same_owner(&j.request, r), "Stale cancellation");
            if j.phase == Phase::Owned {
                j.phase = Phase::Cancelled;
            }
        }
        Action::Recover => {
            let j = journal.as_mut().context("No operation to recover")?;
            ensure!(
                j.request.operation_id == r.operation_id
                    && j.request.from_generation == r.from_generation
                    && j.request.supervisor == r.supervisor,
                "Stale recovery"
            );
            if j.phase == Phase::Owned && same_owner(&j.request, r) {
                return Ok(());
            }
            ensure!(
                j.phase != Phase::Cancelled && probe(&j.request.coordinator) == Presence::Gone,
                "Previous coordinator is not proven gone"
            );
            ensure!(r.child.as_ref() == observed.child, "Recovery child changed");
            ensure!(
                r.child.as_ref().is_none_or(|p| probe(p) == Presence::Alive),
                "Recovery child unverified"
            );
            j.request = r.clone();
            j.request.action = Action::Enter;
            j.phase = Phase::Owned;
        }
    }
    Ok(())
}

fn same_owner(a: &Request, b: &Request) -> bool {
    a.operation_id == b.operation_id
        && a.from_generation == b.from_generation
        && a.supervisor == b.supervisor
        && a.coordinator == b.coordinator
        && a.child == b.child
}

fn validate(r: &Request) -> Result<()> {
    ensure!(
        r.protocol == PROTOCOL && uuid::Uuid::parse_str(&r.operation_id).is_ok(),
        "Invalid maintenance request"
    );
    ensure!(
        r.from_generation.len() == 64
            && r.from_generation
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)),
        "Invalid generation"
    );
    for p in [&r.supervisor, &r.coordinator]
        .into_iter()
        .chain(r.child.iter())
    {
        ensure!(p.pid != 0 && p.created_at != 0, "Invalid process identity");
        storage::plain(&p.executable)?;
    }
    ensure!(
        r.supervisor.pid != r.coordinator.pid
            && r.child
                .as_ref()
                .is_none_or(|p| p.pid != r.supervisor.pid && p.pid != r.coordinator.pid),
        "Process roles overlap"
    );
    Ok(())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Failure {
    RequestRejected,
    JournalInvalid,
    IdentityUnavailable,
    StorageUnavailable,
}

/// Heartbeat diagnostics contain no capability token and make no handoff claim.
#[derive(Default)]
pub struct Gate {
    pub journal: Option<Journal>,
    pub error: Option<Failure>,
    blocked: bool,
}
impl Gate {
    pub fn blocks_respawn(&self) -> bool {
        self.blocked
    }
    pub fn respawn_ready(&self, child_present: bool, retry_due: bool) -> bool {
        !child_present && retry_due && !self.blocks_respawn()
    }

    /// Never propagate maintenance errors out of the supervisor's ownership loop.
    pub fn poll(&mut self, state: &Path, generation: Option<&str>, child_pid: Option<u32>) {
        self.poll_with(
            state,
            generation,
            child_pid,
            ProcessIdentity::capture,
            presence,
        );
    }
    fn poll_with(
        &mut self,
        state: &Path,
        generation: Option<&str>,
        child_pid: Option<u32>,
        capture: impl Fn(u32) -> Result<ProcessIdentity>,
        probe: impl Fn(&ProcessIdentity) -> Presence,
    ) {
        match self.poll_inner(state, generation, child_pid, capture, probe) {
            Ok((journal, error)) => {
                self.blocked = journal.as_ref().is_some_and(|j| j.phase == Phase::Owned);
                self.journal = journal;
                self.error = error;
            }
            Err(failure) => {
                self.blocked = true;
                self.error = Some(failure);
            }
        }
    }

    fn poll_inner(
        &self,
        state: &Path,
        generation: Option<&str>,
        child_pid: Option<u32>,
        capture: impl Fn(u32) -> Result<ProcessIdentity>,
        probe: impl Fn(&ProcessIdentity) -> Presence,
    ) -> std::result::Result<(Option<Journal>, Option<Failure>), Failure> {
        let control = storage::control(state);
        let journal_path = control.join(JOURNAL);
        let mailbox = control.join(MAILBOX);
        let present = |path: &Path| -> std::result::Result<bool, Failure> {
            storage::unredirected(path).map_err(|_| Failure::StorageUnavailable)?;
            path.try_exists().map_err(|_| Failure::StorageUnavailable)
        };
        if !present(&journal_path)? && !present(&mailbox)? {
            return if self.journal.is_some() {
                Err(Failure::JournalInvalid)
            } else {
                Ok((None, None))
            };
        }
        let _lock = lock(&control).map_err(|_| Failure::StorageUnavailable)?;
        let mut journal: Option<Journal> = if present(&journal_path)? {
            Some(read_private(&journal_path).map_err(|_| Failure::JournalInvalid)?)
        } else if self.journal.is_some() {
            return Err(Failure::JournalInvalid);
        } else {
            None
        };
        let supervisor = capture(std::process::id()).map_err(|_| Failure::IdentityUnavailable)?;
        let child = child_pid
            .map(capture)
            .transpose()
            .map_err(|_| Failure::IdentityUnavailable)?;
        let generation = generation.ok_or(Failure::IdentityUnavailable)?;
        let operation = super::upgrade::read_status(state).map_err(|_| Failure::JournalInvalid)?;
        let observed = Observation {
            generation,
            operation_id: operation
                .as_ref()
                .map(|s| s.operation_id.as_str())
                .unwrap_or(""),
            supervisor: &supervisor,
            child: child.as_ref(),
        };
        let before = journal.clone();
        transition(&mut journal, None, &observed, &probe).map_err(|_| Failure::JournalInvalid)?;
        let mut error = None;
        let has_request = present(&mailbox)?;
        if has_request {
            let request: Request = read_private(&mailbox).map_err(|_| Failure::RequestRejected)?;
            let mut next = journal.clone();
            if operation
                .as_ref()
                .is_none_or(|s| s.from_generation != request.from_generation || s.phase.terminal())
                && request.action != Action::Cancel
            {
                error = Some(Failure::RequestRejected);
            } else if transition(&mut next, Some(&request), &observed, probe).is_err() {
                error = Some(Failure::RequestRejected);
            } else {
                journal = next;
            }
        }
        // Intent is durable BEFORE the loop observes the new respawn gate.
        // If removal crashes, replay is idempotent against the persisted journal.
        if journal != before {
            write_private(
                &journal_path,
                journal.as_ref().ok_or(Failure::JournalInvalid)?,
            )
            .map_err(|_| Failure::StorageUnavailable)?;
        }
        if has_request {
            std::fs::remove_file(&mailbox).map_err(|_| Failure::StorageUnavailable)?;
        }
        Ok((journal, error))
    }
}

/// Queue a request under the same lock used by the supervisor. A different
/// pending click cannot overwrite a request the supervisor has not acknowledged.
/// Callers must wait for maintenance.json to acknowledge their exact identity.
pub fn submit(state: &Path, request: &Request) -> Result<()> {
    validate(request)?;
    let control = storage::control(state);
    storage::private_dir(&control)?;
    let _lock = lock(&control)?;
    let path = control.join(MAILBOX);
    if path.try_exists()? {
        ensure!(
            read_private::<Request>(&path)? == *request,
            "Another maintenance request is pending"
        );
        return Ok(());
    }
    write_private(&path, request)
}

pub fn read_status(state: &Path) -> Result<Option<Journal>> {
    let path = storage::control(state).join(JOURNAL);
    storage::unredirected(&path)?;
    if !path.try_exists()? {
        return Ok(None);
    }
    let journal: Journal = read_private(&path)?;
    validate(&journal.request)?;
    ensure!(
        journal.request.action == Action::Enter,
        "Invalid journal intent"
    );
    Ok(Some(journal))
}

fn lock(control: &Path) -> Result<storage::Lock> {
    let path = control.join("maintenance.lock");
    storage::unredirected(&path)?;
    if path.try_exists()? {
        open_private(&path)?;
    }
    let lock = storage::Lock::acquire(&path)?;
    open_private(&path)?;
    Ok(lock)
}
fn read_private<T: serde::de::DeserializeOwned>(path: &Path) -> Result<T> {
    let mut file = open_private(path)?;
    let mut bytes = Vec::new();
    Read::by_ref(&mut file)
        .take(16385)
        .read_to_end(&mut bytes)?;
    ensure!(bytes.len() <= 16384, "Maintenance file exceeds limit");
    Ok(serde_json::from_slice(&bytes)?)
}
fn write_private<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    if path.try_exists()? {
        open_private(path)?;
    }
    storage::atomic(path, value)?;
    open_private(path)?;
    Ok(())
}
fn open_private(path: &Path) -> Result<File> {
    storage::unredirected(path)?;
    let file = File::open(path)?;
    ensure!(
        file.metadata()?.is_file(),
        "Maintenance capability must be a file"
    );
    authenticate_file(&file)?;
    Ok(file)
}

impl ProcessIdentity {
    /// Read-only evidence, not a process handle permitting termination.
    pub fn capture(pid: u32) -> Result<Self> {
        inspect(pid)?.context("Process is no longer running")
    }
}
fn presence(expected: &ProcessIdentity) -> Presence {
    match inspect(expected.pid) {
        Ok(Some(actual)) if actual == *expected => Presence::Alive,
        Ok(_) => Presence::Gone,
        Err(_) => Presence::Unknown,
    }
}

#[cfg(windows)]
fn token_user(process: windows_sys::Win32::Foundation::HANDLE) -> Result<Vec<usize>> {
    use std::{
        os::windows::io::{FromRawHandle, OwnedHandle},
        ptr::null_mut,
    };
    use windows_sys::Win32::{Security::*, System::Threading::*};
    unsafe {
        let mut token = null_mut();
        ensure!(
            OpenProcessToken(process, TOKEN_QUERY, &mut token) != 0,
            "Cannot query process owner"
        );
        let _token = OwnedHandle::from_raw_handle(token);
        let mut size = 0;
        GetTokenInformation(token, TokenUser, null_mut(), 0, &mut size);
        ensure!(size > 0, "Missing process owner");
        let mut data = vec![0usize; (size as usize).div_ceil(std::mem::size_of::<usize>())];
        ensure!(
            GetTokenInformation(token, TokenUser, data.as_mut_ptr().cast(), size, &mut size) != 0,
            "Cannot read process owner"
        );
        Ok(data)
    }
}

/// Authenticate the opened file, not only its directory or a status PID.
/// Only current-user-owned files with exclusively current-user allow ACEs are
/// capabilities. Broad, null or foreign ACLs are refused without repairing them.
#[cfg(windows)]
fn authenticate_file(file: &File) -> Result<()> {
    use std::{
        os::windows::io::AsRawHandle,
        ptr::{addr_of_mut, null_mut},
    };
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::{Authorization::*, *},
        System::Threading::GetCurrentProcess,
    };
    struct Descriptor(PSECURITY_DESCRIPTOR);
    impl Drop for Descriptor {
        fn drop(&mut self) {
            unsafe {
                LocalFree(self.0);
            }
        }
    }
    unsafe {
        let user = token_user(GetCurrentProcess())?;
        let sid = (*user.as_ptr().cast::<TOKEN_USER>()).User.Sid;
        let mut owner = null_mut();
        let mut dacl = null_mut();
        let mut descriptor = null_mut();
        let result = GetSecurityInfo(
            file.as_raw_handle(),
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &mut owner,
            null_mut(),
            &mut dacl,
            null_mut(),
            &mut descriptor,
        );
        ensure!(result == 0, "Cannot authenticate maintenance file");
        let _descriptor = Descriptor(descriptor);
        ensure!(
            !owner.is_null() && EqualSid(owner, sid) != 0 && !dacl.is_null(),
            "Maintenance file is not current-user private"
        );
        ensure!(
            (*dacl).AceCount > 0 && IsValidAcl(dacl) != 0,
            "Invalid private ACL"
        );
        for index in 0..(*dacl).AceCount {
            let mut ace = null_mut();
            ensure!(
                GetAce(dacl, u32::from(index), &mut ace) != 0,
                "Cannot read private ACL"
            );
            // ACCESS_ALLOWED_ACE_TYPE (0); object/callback/group ACEs are refused.
            ensure!(
                (*ace.cast::<ACE_HEADER>()).AceType == 0,
                "Unsupported private ACE"
            );
            let allowed = ace.cast::<ACCESS_ALLOWED_ACE>();
            ensure!(
                EqualSid(addr_of_mut!((*allowed).SidStart).cast(), sid) != 0,
                "Maintenance file grants another principal access"
            );
        }
    }
    Ok(())
}

#[cfg(windows)]
fn inspect(pid: u32) -> Result<Option<ProcessIdentity>> {
    use std::os::windows::io::{FromRawHandle, OwnedHandle};
    use windows_sys::Win32::{Foundation::*, Security::*, System::Threading::*};
    ensure!(pid != 0, "Invalid process PID");
    unsafe {
        let handle = OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
            0,
            pid,
        );
        if handle.is_null() {
            let error = std::io::Error::last_os_error();
            if error.raw_os_error() == Some(ERROR_INVALID_PARAMETER as i32) {
                return Ok(None);
            }
            return Err(error.into());
        }
        let _handle = OwnedHandle::from_raw_handle(handle);
        match WaitForSingleObject(handle, 0) {
            WAIT_OBJECT_0 => return Ok(None),
            WAIT_TIMEOUT => {}
            _ => bail!("Process liveness unavailable"),
        }
        let user = token_user(GetCurrentProcess())?;
        let other = token_user(handle)?;
        ensure!(
            EqualSid(
                (*user.as_ptr().cast::<TOKEN_USER>()).User.Sid,
                (*other.as_ptr().cast::<TOKEN_USER>()).User.Sid
            ) != 0,
            "Process does not belong to current user"
        );
        let mut created: FILETIME = std::mem::zeroed();
        let mut exit = created;
        let mut kernel = created;
        let mut cpu = created;
        ensure!(
            GetProcessTimes(handle, &mut created, &mut exit, &mut kernel, &mut cpu) != 0,
            "Process creation time unavailable"
        );
        let mut path = vec![0u16; 32768];
        let mut length = path.len() as u32;
        ensure!(
            QueryFullProcessImageNameW(handle, 0, path.as_mut_ptr(), &mut length) != 0,
            "Process executable unavailable"
        );
        let executable = PathBuf::from(String::from_utf16(&path[..length as usize])?);
        // Query methods race with a natural exit; do not manufacture live proof.
        ensure!(
            WaitForSingleObject(handle, 0) == WAIT_TIMEOUT,
            "Process ended during identity query"
        );
        Ok(Some(ProcessIdentity {
            pid,
            created_at: (u64::from(created.dwHighDateTime) << 32)
                | u64::from(created.dwLowDateTime),
            executable,
        }))
    }
}
#[cfg(not(windows))]
fn authenticate_file(_: &File) -> Result<()> {
    bail!("Private maintenance protocol requires Windows")
}
#[cfg(not(windows))]
fn inspect(_: u32) -> Result<Option<ProcessIdentity>> {
    bail!("Process identity protocol requires Windows")
}

#[cfg(test)]
mod tests {
    use super::*;
    fn identity(pid: u32) -> ProcessIdentity {
        ProcessIdentity {
            pid,
            created_at: u64::from(pid),
            executable: std::env::temp_dir().join(format!("maintenance-fixture-{pid}.exe")),
        }
    }
    fn request() -> Request {
        Request {
            protocol: 1,
            operation_id: "550e8400-e29b-41d4-a716-446655440000".into(),
            from_generation: "a".repeat(64),
            supervisor: identity(1),
            coordinator: identity(2),
            child: Some(identity(3)),
            action: Action::Enter,
        }
    }
    fn observed(r: &Request) -> Observation<'_> {
        Observation {
            generation: &r.from_generation,
            operation_id: &r.operation_id,
            supervisor: &r.supervisor,
            child: r.child.as_ref(),
        }
    }
    #[test]
    fn maintenance_duplicate_clicks_keep_one_owner() {
        let r = request();
        let mut journal = None;
        transition(&mut journal, Some(&r), &observed(&r), |_| Presence::Alive).unwrap();
        let first = journal.clone();
        transition(&mut journal, Some(&r), &observed(&r), |_| Presence::Alive).unwrap();
        assert_eq!(journal.as_ref().map(|j| j.phase), Some(Phase::Owned));
        assert_eq!(journal, first);
    }
    #[test]
    fn maintenance_rejects_stale_operation_and_current_generation_mismatch() {
        let r = request();
        let mut journal = None;
        let mut stale = r.clone();
        stale.operation_id = "550e8400-e29b-41d4-a716-446655440001".into();
        assert!(transition(&mut journal, Some(&stale), &observed(&r), |_| {
            Presence::Alive
        })
        .is_err());
        stale = r.clone();
        stale.from_generation = "b".repeat(64);
        assert!(transition(&mut journal, Some(&stale), &observed(&r), |_| {
            Presence::Alive
        })
        .is_err());
        assert!(journal.is_none());
    }
    #[test]
    fn maintenance_coordinator_crash_recovers_without_changing_child_identity() {
        let r = request();
        let mut journal = Some(Journal {
            request: r.clone(),
            phase: Phase::Owned,
        });
        transition(&mut journal, None, &observed(&r), |p| {
            if p == &r.coordinator {
                Presence::Gone
            } else {
                Presence::Alive
            }
        })
        .unwrap();
        assert_eq!(journal.as_ref().unwrap().phase, Phase::Recovered);
        assert_eq!(journal.as_ref().unwrap().request.child, r.child);
    }
    #[test]
    fn maintenance_unknown_coordinator_never_releases_owner() {
        let r = request();
        let mut journal = Some(Journal {
            request: r.clone(),
            phase: Phase::Owned,
        });
        transition(&mut journal, None, &observed(&r), |_| Presence::Unknown).unwrap();
        assert_eq!(journal.as_ref().unwrap().phase, Phase::Owned);
    }
    #[test]
    fn maintenance_exact_identity_fences_pid_reuse_and_wrong_supervisor() {
        let r = request();
        for field in [0, 1, 2] {
            let mut wrong = r.clone();
            match field {
                0 => wrong.supervisor.created_at += 1,
                1 => wrong.child.as_mut().unwrap().created_at += 1,
                _ => wrong.child.as_mut().unwrap().executable = identity(9).executable,
            }
            let mut journal = None;
            assert!(transition(&mut journal, Some(&wrong), &observed(&r), |_| {
                Presence::Alive
            })
            .is_err());
            assert!(journal.is_none());
        }
        let mut journal = None;
        assert!(transition(&mut journal, Some(&r), &observed(&r), |p| {
            if p == &r.coordinator {
                Presence::Gone
            } else {
                Presence::Alive
            }
        })
        .is_err());
    }
    #[test]
    fn maintenance_stale_cancel_and_competing_click_cannot_replace_owner() {
        let r = request();
        let mut journal = Some(Journal {
            request: r.clone(),
            phase: Phase::Owned,
        });
        let original = journal.clone();
        let mut wrong = r.clone();
        wrong.action = Action::Cancel;
        wrong.coordinator = identity(4);
        assert!(transition(&mut journal, Some(&wrong), &observed(&r), |_| {
            Presence::Alive
        })
        .is_err());
        wrong = r.clone();
        wrong.operation_id = "550e8400-e29b-41d4-a716-446655440001".into();
        assert!(
            transition(&mut journal, Some(&wrong), &observed(&wrong), |_| {
                Presence::Alive
            })
            .is_err()
        );
        assert_eq!(journal, original);
    }
    #[test]
    fn maintenance_cancel_is_idempotent_and_enter_replay_does_not_reacquire() {
        let r = request();
        let mut journal = Some(Journal {
            request: r.clone(),
            phase: Phase::Owned,
        });
        let mut cancel = r.clone();
        cancel.action = Action::Cancel;
        for _ in 0..2 {
            transition(&mut journal, Some(&cancel), &observed(&r), |_| {
                Presence::Alive
            })
            .unwrap();
        }
        transition(&mut journal, Some(&r), &observed(&r), |_| Presence::Alive).unwrap();
        assert_eq!(journal.as_ref().unwrap().phase, Phase::Cancelled);
        assert_eq!(journal.as_ref().unwrap().request.child, r.child);
    }
    #[test]
    fn maintenance_recovery_requires_dead_old_and_live_new_coordinator() {
        let r = request();
        let original = Some(Journal {
            request: r.clone(),
            phase: Phase::Owned,
        });
        let mut recover = r.clone();
        recover.action = Action::Recover;
        recover.coordinator = identity(4);
        for old in [Presence::Alive, Presence::Unknown] {
            let mut journal = original.clone();
            assert!(
                transition(&mut journal, Some(&recover), &observed(&r), |p| {
                    if p == &r.coordinator {
                        old
                    } else {
                        Presence::Alive
                    }
                })
                .is_err()
            );
            assert_eq!(journal, original);
        }
        let mut journal = original;
        let probe = |p: &ProcessIdentity| {
            if p == &r.coordinator {
                Presence::Gone
            } else {
                Presence::Alive
            }
        };
        transition(&mut journal, Some(&recover), &observed(&r), probe).unwrap();
        transition(&mut journal, Some(&recover), &observed(&r), probe).unwrap();
        assert_eq!(
            journal.as_ref().unwrap().request.coordinator,
            recover.coordinator
        );
        assert_eq!(journal.as_ref().unwrap().phase, Phase::Owned);
    }
    #[test]
    fn maintenance_natural_child_exit_remains_suppressed_until_cancel_or_crash() {
        let r = request();
        let mut journal = Some(Journal {
            request: r.clone(),
            phase: Phase::Owned,
        });
        let mut obs = observed(&r);
        obs.child = None;
        transition(&mut journal, None, &obs, |_| Presence::Alive).unwrap();
        assert_eq!(journal.as_ref().unwrap().phase, Phase::Owned);
        let mut cancel = r.clone();
        cancel.action = Action::Cancel;
        transition(&mut journal, Some(&cancel), &obs, |_| Presence::Alive).unwrap();
        assert_eq!(journal.as_ref().unwrap().phase, Phase::Cancelled);
    }
    #[test]
    fn maintenance_owned_journal_cannot_be_adopted_by_restarted_supervisor() {
        let r = request();
        let mut journal = Some(Journal {
            request: r.clone(),
            phase: Phase::Owned,
        });
        let changed = identity(5);
        let mut obs = observed(&r);
        obs.supervisor = &changed;
        assert!(transition(&mut journal, None, &obs, |_| Presence::Gone).is_err());
        assert_eq!(journal.as_ref().unwrap().phase, Phase::Owned);
    }
    #[test]
    fn maintenance_terminal_journal_cannot_spawn_beside_previous_supervisors_live_child() {
        let r = request();
        let changed = identity(5);
        let mut obs = observed(&r);
        obs.supervisor = &changed;
        obs.child = None;
        for phase in [Phase::Cancelled, Phase::Recovered] {
            for previous_child in [Presence::Alive, Presence::Unknown] {
                let mut journal = Some(Journal {
                    request: r.clone(),
                    phase,
                });
                assert!(transition(&mut journal, None, &obs, |_| previous_child).is_err());
            }
            let mut journal = Some(Journal {
                request: r.clone(),
                phase,
            });
            transition(&mut journal, None, &obs, |_| Presence::Gone).unwrap();
            assert_eq!(journal.as_ref().unwrap().phase, phase);
        }
    }
    #[test]
    fn maintenance_rejects_invalid_protocol_generation_and_overlapping_roles() {
        for field in 0..4 {
            let mut r = request();
            match field {
                0 => r.protocol = 2,
                1 => r.operation_id = "bad".into(),
                2 => r.from_generation = "A".repeat(64),
                _ => r.coordinator = r.supervisor.clone(),
            }
            assert!(validate(&r).is_err());
        }
    }

    #[cfg(windows)]
    fn fixture() -> (tempfile::TempDir, Request) {
        use super::super::upgrade::{Phase as UpgradePhase, UpgradeStatus};
        let dir = crate::test_support::tempdir();
        storage::private_dir(&storage::control(dir.path())).unwrap();
        let mut r = request();
        r.supervisor = identity(std::process::id());
        let status = UpgradeStatus {
            schema: 1,
            operation_id: r.operation_id.clone(),
            install_root: dir.path().into(),
            state_directory: dir.path().into(),
            from_generation: r.from_generation.clone(),
            to_generation: "b".repeat(64),
            phase: UpgradePhase::Quiescing,
            updated_at: 100,
            checkpoint: None,
            verification: None,
            failure: None,
        };
        storage::atomic(&storage::control(dir.path()).join("upgrade.json"), &status).unwrap();
        (dir, r)
    }
    #[cfg(windows)]
    fn poll_fixture(
        gate: &mut Gate,
        state: &Path,
        r: &Request,
        child_present: bool,
        coordinator: Presence,
    ) {
        gate.poll_with(
            state,
            Some(&r.from_generation),
            child_present.then(|| r.child.as_ref().unwrap().pid),
            |pid| {
                Ok(if pid == std::process::id() {
                    r.supervisor.clone()
                } else {
                    r.child.as_ref().unwrap().clone()
                })
            },
            |p| {
                if p == &r.coordinator {
                    coordinator
                } else {
                    Presence::Alive
                }
            },
        );
    }
    #[cfg(windows)]
    #[test]
    fn maintenance_private_mailbox_is_serial_and_journal_is_supervisor_owned() {
        let (dir, r) = fixture();
        submit(dir.path(), &r).unwrap();
        submit(dir.path(), &r).unwrap();
        let mut other = r.clone();
        other.action = Action::Cancel;
        assert!(submit(dir.path(), &other).is_err());
        assert!(read_status(dir.path()).unwrap().is_none()); // Queue is not acknowledgement.
        let mut gate = Gate::default();
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        assert_eq!(gate.error, None);
        assert!(gate.blocks_respawn());
        assert_eq!(
            read_status(dir.path()).unwrap().unwrap().phase,
            Phase::Owned
        );
        assert!(!storage::control(dir.path()).join(MAILBOX).exists());
        let bytes = std::fs::read(storage::control(dir.path()).join(JOURNAL)).unwrap();
        submit(dir.path(), &r).unwrap();
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        assert_eq!(
            std::fs::read(storage::control(dir.path()).join(JOURNAL)).unwrap(),
            bytes
        );
    }
    #[cfg(windows)]
    #[test]
    fn maintenance_loop_gates_respawn_and_crash_recovers_live_child() {
        let (dir, r) = fixture();
        let mut gate = Gate::default();
        assert!(gate.respawn_ready(false, true));
        assert!(!gate.respawn_ready(true, true));
        assert!(!gate.respawn_ready(false, false));
        submit(dir.path(), &r).unwrap();
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        assert!(!gate.respawn_ready(false, true));
        poll_fixture(&mut gate, dir.path(), &r, false, Presence::Alive);
        assert!(!gate.respawn_ready(false, true));
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Gone);
        assert_eq!(gate.error, None);
        assert_eq!(gate.journal.as_ref().unwrap().phase, Phase::Recovered);
        assert!(!gate.respawn_ready(true, true));
        assert!(gate.respawn_ready(false, true));
        assert_eq!(gate.journal.as_ref().unwrap().request.child, r.child);
        let mut reopened = Gate::default();
        poll_fixture(&mut reopened, dir.path(), &r, true, Presence::Gone);
        assert!(!reopened.blocks_respawn());
    }
    #[cfg(windows)]
    #[test]
    fn maintenance_missing_or_corrupt_journal_cannot_drop_owned_gate() {
        for corrupt in [false, true] {
            let (dir, r) = fixture();
            let mut gate = Gate::default();
            submit(dir.path(), &r).unwrap();
            poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
            let path = storage::control(dir.path()).join(JOURNAL);
            if corrupt {
                std::fs::write(&path, b"{broken").unwrap();
            } else {
                std::fs::remove_file(&path).unwrap();
            }
            poll_fixture(&mut gate, dir.path(), &r, true, Presence::Gone);
            assert_eq!(gate.error, Some(Failure::JournalInvalid));
            assert!(!gate.respawn_ready(false, true));
            assert_eq!(gate.journal.as_ref().unwrap().phase, Phase::Owned);
        }
    }
    #[cfg(windows)]
    #[test]
    fn maintenance_current_generation_is_running_source_not_installed_target() {
        let (dir, r) = fixture();
        submit(dir.path(), &r).unwrap();
        let mut gate = Gate::default();
        gate.poll_with(
            dir.path(),
            Some(&"b".repeat(64)),
            Some(3),
            |pid| {
                Ok(if pid == std::process::id() {
                    r.supervisor.clone()
                } else {
                    r.child.as_ref().unwrap().clone()
                })
            },
            |_| Presence::Alive,
        );
        assert_eq!(gate.error, Some(Failure::RequestRejected));
        assert!(gate.journal.is_none());
        assert!(gate.respawn_ready(false, true));
    }
    #[cfg(windows)]
    #[test]
    fn maintenance_missing_running_generation_never_acknowledges() {
        let (dir, r) = fixture();
        submit(dir.path(), &r).unwrap();
        let mut gate = Gate::default();
        gate.poll_with(
            dir.path(),
            None,
            None,
            |_| Ok(r.supervisor.clone()),
            |_| Presence::Alive,
        );
        assert_eq!(gate.error, Some(Failure::IdentityUnavailable));
        assert!(gate.blocks_respawn());
        assert!(read_status(dir.path()).unwrap().is_none());
    }
    #[cfg(windows)]
    #[test]
    fn maintenance_terminal_upgrade_cannot_acquire_but_owned_cancel_can_finish() {
        use super::super::upgrade::{Phase as UpgradePhase, UpgradeStatus};
        let (dir, r) = fixture();
        let mut gate = Gate::default();
        submit(dir.path(), &r).unwrap();
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        let path = storage::control(dir.path()).join("upgrade.json");
        let mut upgrade: UpgradeStatus = storage::read(&path).unwrap();
        upgrade.phase = UpgradePhase::WaitingRestart;
        storage::atomic(&path, &upgrade).unwrap();
        let mut cancel = r.clone();
        cancel.action = Action::Cancel;
        submit(dir.path(), &cancel).unwrap();
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        assert_eq!(gate.error, None);
        assert_eq!(gate.journal.as_ref().unwrap().phase, Phase::Cancelled);
        assert!(!gate.respawn_ready(true, true));
        let (dir, r) = fixture();
        let path = storage::control(dir.path()).join("upgrade.json");
        let mut upgrade: UpgradeStatus = storage::read(&path).unwrap();
        upgrade.phase = UpgradePhase::WaitingRestart;
        storage::atomic(&path, &upgrade).unwrap();
        submit(dir.path(), &r).unwrap();
        let mut gate = Gate::default();
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        assert_eq!(gate.error, Some(Failure::RequestRejected));
        assert!(gate.journal.is_none());
    }
    #[cfg(windows)]
    #[test]
    fn maintenance_lock_contention_and_failed_mailbox_cleanup_do_not_acknowledge() {
        let (dir, r) = fixture();
        submit(dir.path(), &r).unwrap();
        let mut gate = Gate::default();
        let guard = lock(&storage::control(dir.path())).unwrap();
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        assert_eq!(gate.error, Some(Failure::StorageUnavailable));
        assert!(gate.blocks_respawn());
        assert!(read_status(dir.path()).unwrap().is_none());
        drop(guard);
        // A reader without delete sharing prevents the mailbox acknowledgement
        // from being removed, after ownership was already durably recorded.
        use std::os::windows::fs::OpenOptionsExt;
        let reader = std::fs::OpenOptions::new()
            .read(true)
            .share_mode(3)
            .open(storage::control(dir.path()).join(MAILBOX))
            .unwrap();
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        assert_eq!(gate.error, Some(Failure::StorageUnavailable));
        assert!(gate.blocks_respawn());
        assert_eq!(
            read_status(dir.path()).unwrap().unwrap().phase,
            Phase::Owned
        );
        drop(reader);
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        assert_eq!(gate.error, None);
        assert!(gate.blocks_respawn());
        assert!(!storage::control(dir.path()).join(MAILBOX).exists());
    }
    #[cfg(windows)]
    #[test]
    fn maintenance_failed_cancel_persistence_keeps_ownership_until_durable_replay() {
        use std::os::windows::fs::OpenOptionsExt;
        let (dir, r) = fixture();
        let mut gate = Gate::default();
        submit(dir.path(), &r).unwrap();
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        let mut cancel = r.clone();
        cancel.action = Action::Cancel;
        submit(dir.path(), &cancel).unwrap();
        let reader = std::fs::OpenOptions::new()
            .read(true)
            .share_mode(3)
            .open(storage::control(dir.path()).join(JOURNAL))
            .unwrap();
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        assert_eq!(gate.error, Some(Failure::StorageUnavailable));
        assert!(gate.blocks_respawn());
        assert_eq!(gate.journal.as_ref().unwrap().phase, Phase::Owned);
        assert_eq!(
            read_status(dir.path()).unwrap().unwrap().phase,
            Phase::Owned
        );
        assert!(storage::control(dir.path()).join(MAILBOX).exists());
        drop(reader);
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        assert_eq!(gate.error, None);
        assert_eq!(gate.journal.as_ref().unwrap().phase, Phase::Cancelled);
        assert!(!gate.respawn_ready(true, true));
        assert!(gate.respawn_ready(false, true));
    }
    #[cfg(windows)]
    #[test]
    fn maintenance_unverified_identity_does_not_unblock_or_acknowledge() {
        let (dir, r) = fixture();
        submit(dir.path(), &r).unwrap();
        let mut gate = Gate::default();
        gate.poll_with(
            dir.path(),
            Some(&r.from_generation),
            Some(3),
            |_| bail!("access denied"),
            |_| Presence::Unknown,
        );
        assert_eq!(gate.error, Some(Failure::IdentityUnavailable));
        assert!(!gate.respawn_ready(false, true));
        assert!(read_status(dir.path()).unwrap().is_none());
        assert!(storage::control(dir.path()).join(MAILBOX).exists());
    }
    #[cfg(windows)]
    #[test]
    fn maintenance_process_probe_fences_creation_time_and_path_without_spawning() {
        let actual = ProcessIdentity::capture(std::process::id()).unwrap();
        assert_eq!(presence(&actual), Presence::Alive);
        let mut reused = actual.clone();
        reused.created_at += 1;
        assert_eq!(presence(&reused), Presence::Gone);
        reused = actual;
        reused.executable = PathBuf::from("C:/different.exe");
        assert_eq!(presence(&reused), Presence::Gone);
        assert!(ProcessIdentity::capture(0).is_err());
    }
    #[cfg(windows)]
    #[test]
    fn maintenance_file_authentication_rejects_broad_acl_without_repair() {
        use std::{os::windows::ffi::OsStrExt, ptr::null_mut};
        use windows_sys::Win32::{
            Foundation::LocalFree,
            Security::{Authorization::*, *},
        };
        let (dir, r) = fixture();
        submit(dir.path(), &r).unwrap();
        let path = storage::control(dir.path()).join(MAILBOX);
        assert!(read_private::<Request>(&path).is_ok());
        let original = std::fs::read(&path).unwrap();
        unsafe {
            let sddl: Vec<u16> = "D:P(A;;FA;;;WD)".encode_utf16().chain(Some(0)).collect();
            let mut descriptor = null_mut();
            assert_ne!(
                ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    sddl.as_ptr(),
                    SDDL_REVISION_1,
                    &mut descriptor,
                    null_mut()
                ),
                0
            );
            let mut present = 0;
            let mut defaulted = 0;
            let mut dacl = null_mut();
            assert_ne!(
                GetSecurityDescriptorDacl(descriptor, &mut present, &mut dacl, &mut defaulted),
                0
            );
            let name: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
            assert_eq!(
                SetNamedSecurityInfoW(
                    name.as_ptr(),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                    null_mut(),
                    null_mut(),
                    dacl,
                    null_mut()
                ),
                0
            );
            LocalFree(descriptor);
        }
        assert!(read_private::<Request>(&path).is_err());
        assert!(submit(dir.path(), &r).is_err());
        let mut gate = Gate::default();
        poll_fixture(&mut gate, dir.path(), &r, true, Presence::Alive);
        assert!(gate.blocks_respawn());
        assert!(gate.journal.is_none());
        assert_eq!(std::fs::read(&path).unwrap(), original);
        assert!(read_private::<Request>(&path).is_err());
    }
}
