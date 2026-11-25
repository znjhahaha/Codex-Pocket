//! Ownership of the three Pocket background locks, independent of parentage.
use super::*;

fn pair_owns_locks(supervisor: &ProcessIdentity, agent: &ProcessIdentity, locks: &[Vec<(u32, u64)>; 3]) -> bool {
    locks[0] == [(supervisor.pid, supervisor.created_at)]
        && locks[1] == locks[0]
        && locks[2] == [(agent.pid, agent.created_at)]
}

#[derive(Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Notice {
    id: String,
    operation: String,
    from: String,
    to: String,
    owners: Vec<ProcessIdentity>,
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Acknowledgement { notice_id: String, shown_at: u64 }
fn notice_path(state: &Path, operation: &str) -> PathBuf {
    updates::directory(state).join(format!("restart-lock-notice-{operation}.json"))
}
pub(super) fn announced(state: &Path, operation: &str) -> bool {
    storage::read::<Notice>(&notice_path(state, operation)).is_ok_and(|n| n.operation == operation)
}
pub(super) fn notice_id(state: &Path, operation: &str) -> Option<String> {
    storage::read::<Notice>(&notice_path(state, operation)).ok().filter(|n| n.operation == operation).map(|n| n.id)
}
fn acknowledgement_path(state: &Path, operation: &str) -> PathBuf {
    updates::directory(state).join(format!("restart-lock-shown-{operation}.json"))
}
pub(super) fn acknowledge(state: &Path, operation: &str, id: &str) -> Result<()> {
    ensure!(upgrade::local::canonical_uuid(operation) && upgrade::local::canonical_uuid(id), "Invalid restart notice");
    let _lock = storage::Lock::acquire(&updates::directory(state).join(format!("restart-notice-{operation}.lock")))?;
    let status = updates::read_status(state)?;
    let notice: Notice = storage::read(&notice_path(state, operation))?;
    ensure!(status.explicit_install && matches!(status.phase.as_str(), "staged" | "waiting_idle" | "waiting_cold_start")
        && status.operation_id == operation && notice.operation == operation && notice.id == id
        && status.generation.as_ref() == Some(&notice.to), "Restart notice changed");
    let path = acknowledgement_path(state, operation);
    if storage::read::<Acknowledgement>(&path).is_ok_and(|a| a.notice_id == id) { return Ok(()); }
    storage::atomic(&path, &Acknowledgement { notice_id: id.into(), shown_at: storage::now() })
}
fn notice_ready(state: &Path, notice: Notice, now: u64) -> Result<bool> {
    let path = notice_path(state, &notice.operation);
    if path.try_exists()? {
        let previous: Notice = storage::read(&path)?;
        if previous.operation == notice.operation && previous.from == notice.from
            && previous.to == notice.to && previous.owners == notice.owners {
            return Ok(storage::read::<Acknowledgement>(&acknowledgement_path(state, &notice.operation))
                .is_ok_and(|ack| ack.notice_id == previous.id && now.saturating_sub(ack.shown_at) >= 5));
        }
    }
    // The timer starts only after the visible management window acknowledges
    // painting this exact notice. A different owner requires a new notice ID.
    storage::atomic(&path, &notice)?;
    Ok(false)
}

/// Explicit install recovery after the supervisor and helper ceased to be a pair.
/// Names, stale heartbeats and parent PIDs alone never identify a lock owner.
pub(super) fn ready(root: &Path, state: &Path, bin: &Path, status: &updates::UpdateStatus,
    supervisor: &ProcessIdentity, agent: &ProcessIdentity) -> Result<bool> {
    ensure!(apply_requested(state, status), "restart_owner_changed");
    let paths = [root.join("control/supervisor.lock"), storage::control(state).join("supervisor.lock"), state.join("agent.lock")];
    let mut locks: [Vec<(u32, u64)>; 3] = Default::default();
    let mut owners = Vec::new();
    for (index, path) in paths.iter().enumerate() {
        match storage::Lock::acquire(path) {
            Ok(_free) => continue,
            Err(error) if lock_contended(&error) => {},
            Err(error) => return Err(error),
        }
        locks[index] = holders(path)?;
        ensure!(locks[index].len() == 1, "restart_owner_changed");
        let (pid, created_at) = locks[index][0];
        let owner = ProcessIdentity::capture(pid)?;
        let name = if index == 2 { "pocket-agent.exe" } else { "pocket-supervisor.exe" };
        ensure!(owner.created_at == created_at && owner.executable == launcher::verified_exe(bin, name)?, "restart_owner_changed");
        if !owners.contains(&owner) { owners.push(owner); }
    }
    ensure!(pair_owns_locks(supervisor, agent, &locks), "restart_owner_changed");
    let notice = Notice { id: uuid::Uuid::new_v4().to_string(), operation: status.operation_id.clone(), from: crate::installation::generation(bin)?,
        to: status.generation.clone().context("restart_candidate_changed")?, owners };
    notice_ready(state, notice, storage::now())
}

#[cfg(windows)]
fn holders(path: &Path) -> Result<Vec<(u32, u64)>> {
    use std::{os::windows::ffi::OsStrExt, ptr::null};
    use windows_sys::Win32::{Foundation::{ERROR_MORE_DATA, ERROR_SUCCESS}, System::RestartManager::*};
    struct Session(u32);
    impl Drop for Session { fn drop(&mut self) { unsafe { RmEndSession(self.0); } } }
    let check = |code| -> Result<()> { ensure!(code == ERROR_SUCCESS, "restart_lock_query_failed"); Ok(()) };
    storage::unredirected(path)?;
    let path: Vec<u16> = path.as_os_str().encode_wide().chain([0]).collect();
    let mut key = [0u16; CCH_RM_SESSION_KEY as usize + 1];
    let mut handle = 0;
    unsafe { check(RmStartSession(&mut handle, 0, key.as_mut_ptr()))?; }
    let session = Session(handle);
    unsafe { check(RmRegisterResources(session.0, 1, [path.as_ptr()].as_ptr(), 0, null(), 0, null()))?; }
    let mut apps = vec![RM_PROCESS_INFO::default(); 4];
    for _ in 0..3 {
        let (mut needed, mut count, mut reboot) = (0, apps.len() as u32, 0);
        let result = unsafe { RmGetList(session.0, &mut needed, &mut count, apps.as_mut_ptr(), &mut reboot) };
        if result == ERROR_MORE_DATA {
            ensure!(needed <= 64, "restart_lock_query_failed");
            apps.resize(needed as usize, RM_PROCESS_INFO::default());
            continue;
        }
        check(result)?;
        return Ok(apps.into_iter().take(count as usize).map(|row| (row.Process.dwProcessId,
            (u64::from(row.Process.ProcessStartTime.dwHighDateTime) << 32) | u64::from(row.Process.ProcessStartTime.dwLowDateTime))).collect());
    }
    anyhow::bail!("restart_lock_query_failed")
}
#[cfg(not(windows))]
fn holders(_: &Path) -> Result<Vec<(u32, u64)>> { anyhow::bail!("Windows process restart required") }

#[cfg(test)]
mod tests {
    use super::*;
    fn owner(pid: u32, name: &str) -> ProcessIdentity {
        ProcessIdentity { pid, created_at: u64::from(pid) + 100, executable: PathBuf::from(name) }
    }
    #[test]
    fn detached_healthy_agent_and_current_supervisor_are_owned_by_their_locks() {
        let supervisor=owner(10,"pocket-supervisor.exe");
        let agent=owner(20,"pocket-agent.exe");
        assert!(pair_owns_locks(&supervisor,&agent,&[vec![(10,110)],vec![(10,110)],vec![(20,120)]]));
    }
    #[test]
    fn lock_recovery_rejects_pid_reuse_missing_or_unexpected_holders() {
        let supervisor=owner(10,"pocket-supervisor.exe");
        let agent=owner(20,"pocket-agent.exe");
        for locks in [
            [vec![(10,109)],vec![(10,110)],vec![(20,120)]],
            [vec![(10,110)],vec![],vec![(20,120)]],
            [vec![(10,110)],vec![(10,110)],vec![(20,120),(30,130)]],
            [vec![(10,110)],vec![(20,120)],vec![(20,120)]],
        ] { assert!(!pair_owns_locks(&supervisor,&agent,&locks)); }
    }
    #[test]
    fn force_stop_waits_for_notice_and_resets_when_owner_changes() {
        let dir=crate::test_support::tempdir();
        storage::private_dir(&updates::directory(dir.path())).unwrap();
        let notice=|pid| Notice { id:uuid::Uuid::new_v4().to_string(),operation:"operation".into(),from:"old".into(),to:"new".into(),owners:vec![owner(pid,"pocket-agent.exe")] };
        let now=100;
        assert!(!notice_ready(dir.path(),notice(20),now).unwrap());
        assert!(announced(dir.path(),"operation"));
        assert!(!notice_ready(dir.path(),notice(20),now+3600).unwrap(),"Elapsed time without a displayed notice never authorizes termination");
        let id=notice_id(dir.path(),"operation").unwrap();
        storage::atomic(&acknowledgement_path(dir.path(),"operation"),&Acknowledgement{notice_id:id,shown_at:now}).unwrap();
        assert!(!notice_ready(dir.path(),notice(20),now+4).unwrap());
        assert!(notice_ready(dir.path(),notice(20),now+5).unwrap());
        assert!(!notice_ready(dir.path(),notice(21),now+6).unwrap());
        assert!(!notice_ready(dir.path(),notice(21),now+3600).unwrap(),"A prior owner's acknowledgement cannot authorize a replacement");
        assert!(!announced(dir.path(),"other-operation"));
    }
    #[cfg(windows)]
    #[test]
    fn restart_manager_reports_the_actual_locked_file_owner() {
        let dir=crate::test_support::tempdir();
        let path=dir.path().join("owner.lock");
        let _lock=storage::Lock::acquire(&path).unwrap();
        let identity=ProcessIdentity::capture(std::process::id()).unwrap();
        assert_eq!(holders(&path).unwrap(),vec![(identity.pid,identity.created_at)]);
    }

    #[test]
    fn acknowledgement_is_bound_to_the_active_notice_and_idempotent() {
        let dir=crate::test_support::tempdir();
        let state=dir.path();
        storage::private_dir(&updates::directory(state)).unwrap();
        let operation=uuid::Uuid::new_v4().to_string();
        let id=uuid::Uuid::new_v4().to_string();
        let mut status=updates::UpdateStatus { operation_id:operation.clone(), explicit_install:true,
            generation:Some("new".into()), phase:"waiting_cold_start".into(), ..Default::default() };
        storage::atomic(&updates::directory(state).join("status.json"),&status).unwrap();
        let notice=Notice {id:id.clone(),operation:operation.clone(),from:"old".into(),to:"new".into(),owners:vec![owner(20,"pocket-agent.exe")]};
        assert!(!notice_ready(state,notice,100).unwrap());
        assert!(acknowledge(state,&operation,&uuid::Uuid::new_v4().to_string()).is_err());
        assert!(!acknowledgement_path(state,&operation).exists());
        acknowledge(state,&operation,&id).unwrap();
        storage::atomic(&acknowledgement_path(state,&operation),&Acknowledgement {notice_id:id.clone(),shown_at:123}).unwrap();
        acknowledge(state,&operation,&id).unwrap();
        assert_eq!(storage::read::<Acknowledgement>(&acknowledgement_path(state,&operation)).unwrap().shown_at,123);
        status.generation=Some("replacement".into());
        storage::atomic(&updates::directory(state).join("status.json"),&status).unwrap();
        assert!(acknowledge(state,&operation,&id).is_err());
        status.generation=Some("new".into());
        status.phase="succeeded".into();
        storage::atomic(&updates::directory(state).join("status.json"),&status).unwrap();
        assert!(acknowledge(state,&operation,&id).is_err());
    }
}
