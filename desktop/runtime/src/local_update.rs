//! One explicit installer transaction, independent of the old supervisor's
//! schema and automatic-update preference. Startup resumes only this operation.
use crate::{launcher, storage, updates, supervisor::upgrade};
use anyhow::{ensure, Context, Result};
use serde_json::json;
use std::{path::{Path, PathBuf}, process::{Command, Stdio}, time::Duration};

#[path = "legacy_restart.rs"]
mod legacy_restart;

fn journal(state: &Path, operation: &str) -> PathBuf {
    updates::directory(state).join(format!("local-coordinator-{operation}.json"))
}
pub fn resumable_update(status: &updates::UpdateStatus) -> bool {
    status.explicit_install && matches!(status.phase.as_str(), "staged" | "waiting_idle" | "waiting_cold_start" | "switching" | "restarting_legacy" | "cold_starting")
}
pub(crate) fn restart_requested(state: &Path, status: &updates::UpdateStatus) -> bool {
    storage::read::<serde_json::Value>(&updates::directory(state).join("restart-request.json"))
        .is_ok_and(|r| r["operationId"] == status.operation_id && r["generation"].as_str() == status.generation.as_deref()
            && status.generation.is_some())
}
pub(crate) fn apply_requested(state: &Path, status: &updates::UpdateStatus) -> bool {
    status.explicit_install || restart_requested(state, status)
}
/// Persist the user's intent separately: older brokers strictly deserialize
/// UpdateStatus, and a UI click must never relax a network offer's expiry.
pub fn request_restart(root: &Path, state: &Path, operation: &str) -> Result<()> {
    {
        let _worker=storage::Lock::acquire(&updates::directory(state).join("worker.lock"))?;
        let mut s=updates::read_status(state)?;
        ensure!(s.operation_id==operation, "Update selection changed; refresh and retry");
        let retry=s.phase=="failed" && s.failure.as_deref()==Some("legacy_restart_unverified");
        ensure!(retry || matches!(s.phase.as_str(),"staged"|"waiting_idle"|"waiting_cold_start"|"restarting_legacy"|"switching"|"cold_starting"), "No downloaded update ready to restart");
        if !restart_requested(state,&s) {
            let (mut bin, recorded)=launcher::resolve_release(root)?;
            ensure!(recorded==state,"Installation changed");
            if matches!(s.phase.as_str(),"switching"|"cold_starting") {
                let old=s.previous_record.as_ref().context("Previous installation missing")?;
                let generation=old["generation"].as_str().filter(|g|g.len()==64&&g.bytes().all(|c|c.is_ascii_hexdigit())).context("Invalid previous generation")?;
                ensure!(old["installRoot"].as_str().map(Path::new)==Some(root)
                    && old["stateDirectory"].as_str().map(Path::new)==Some(state)
                    && old["releaseHash"]==generation,"Previous installation changed");
                bin=root.join("bin").join(generation);
                ensure!(crate::installation::generation(&bin)?==generation,"Previous manifest changed");
            }
            let manifest:serde_json::Value=storage::read(&bin.join("windows-manifest.json"))?;
            let signed:serde_json::Value=storage::read(&updates::directory(state).join("offer.json"))?;
            let offer=updates::verify_offer_for(&updates::trust(&bin,state)?,&serde_json::to_vec(&signed)?,
                manifest["version"].as_str().context("Missing installed version")?,storage::now(),s.explicit_install)?;
            ensure!(s.generation.as_deref()==Some(&offer.generation) && s.version.as_deref()==Some(&offer.version),"Staged update changed");
            upgrade::local::verify_generation_inventory(&root.join("bin").join(&offer.generation))?;
            storage::atomic(&updates::directory(state).join("restart-request.json"),
                &json!({"operationId":s.operation_id,"generation":s.generation,"requestedAt":storage::now()}))?;
        }
        if retry { s.failure=None; updates::save(state,&mut s,"waiting_idle")?; }
    }
    let result=dispatch_from(root,state,false,Some(operation));
    if result.is_err() {
        // Failures before process spawn (including recovery registration) must
        // leave a retryable button, without overwriting a live owner's journal.
        if let Ok(_owner)=storage::Lock::acquire(&coordinator_lock(state,operation)) {
            if updates::read_status(state).is_ok_and(|s|s.operation_id==operation) {
                storage::atomic(&journal(state,operation),&json!({"phase":"waiting_login","reason":"independent_dispatch_failed","updatedAt":storage::now()}))?;
            }
        }
    }
    result
}
fn cold_admitted(state: &Path, s: &updates::UpdateStatus) -> Result<bool> {
    if matches!(s.phase.as_str(), "cold_starting" | "restarting_legacy") { return Ok(true); } // Already committed; finish or roll back.
    Ok(matches!(s.phase.as_str(),"staged" | "waiting_idle" | "waiting_cold_start") &&
        (apply_requested(state,s) || updates::settings(state)?.automatic))
}

/// Supported recovery for an old coordinator stuck after a cancelled inventory.
/// The old outer process observes a terminal update and exits by itself. Its
/// OS lock, not a PID kill or deleted lock file, gates the replacement owner.
pub fn recover_cancelled_handoff(root: &Path, state: &Path, operation: &str) -> Result<()> {
    ensure!(upgrade::local::canonical_uuid(operation), "Invalid installer operation");
    let _recovery=storage::Lock::acquire(&updates::directory(state).join(format!("recovery-{operation}.lock")))?;
    let s=updates::read_status(state)?;
    ensure!(s.operation_id==operation && apply_requested(state,&s), "Recovery selection or authorization changed");
    if restart_requested(state,&s) && matches!(s.phase.as_str(),"waiting_cold_start"|"restarting_legacy"|"cold_starting"|"succeeded") {
        return run(root,state,operation);
    }
    {
        let _worker=storage::Lock::acquire(&updates::directory(state).join("worker.lock"))?;
        let _install=updates::InstallMutex::acquire(root)?;
        let mut s=updates::read_status(state)?;
        ensure!(s.operation_id==operation && apply_requested(state,&s)
            && (s.phase=="switching"
                || s.phase=="failed" && s.failure.as_deref()==Some("handoff_recovery_required")
                || s.phase=="rolled_back" && s.failure.as_deref()==Some("handoff_rolled_back")),
            "Update is not awaiting cancelled-handoff recovery");
        let old=updates::restorable_record(root,&s)?;
        ensure!(old["installRoot"].as_str().map(Path::new)==Some(root)
            && old["stateDirectory"].as_str().map(Path::new)==Some(state)
            && old["releaseHash"]==old["generation"], "Previous installation changed");
        let h=upgrade::read_status(state)?.context("Handoff journal missing")?;
        ensure!(s.handoff_operation_id.as_deref()==Some(&h.operation_id)
            && s.generation.as_deref()==Some(&h.to_generation) && old["generation"]==h.from_generation,
            "Handoff operation differs");
        legacy_restart::verify_candidate(&root.join("bin").join(&h.from_generation),state,&s)?;
        upgrade::recover_cancelled(root,state,&h.operation_id)?;
        updates::restore_record(root,&s)?;
        s.failure=Some("handoff_rolled_back".into());
        updates::save(state,&mut s,"rolled_back")?;
    }
    let _owner=wait_for_coordinator(state,operation)?;
    {
        let _worker=storage::Lock::acquire(&updates::directory(state).join("worker.lock"))?;
        let mut s=updates::read_status(state)?;
        ensure!(s.operation_id==operation && s.phase=="rolled_back"
            && s.failure.as_deref()==Some("handoff_rolled_back"), "Recovery update changed");
        let (bin,recorded)=launcher::resolve_release(root)?;
        let h=upgrade::read_status(state)?.context("Handoff journal missing")?;
        ensure!(recorded==state && h.phase==upgrade::Phase::RolledBack
            && s.handoff_operation_id.as_deref()==Some(&h.operation_id)
            && crate::installation::generation(&bin)?==h.from_generation,
            "Recovered installation changed");
        let candidate=legacy_restart::verify_candidate(&bin,state,&s)?;
        let exe=launcher::verified_exe(&candidate,"pocket-upgrade.exe")?;
        startup(operation,Some(&startup_command(&exe,root,state,operation)?))?;
        // Retain explicit authorization for recovery by an older signed target
        // coordinator too. This intent never changes offer trust or expiry.
        storage::atomic(&updates::directory(state).join("restart-request.json"),
            &json!({"operationId":operation,"generation":s.generation,"requestedAt":storage::now()}))?;
        s.failure=None;
        s.handoff_operation_id=None;
        updates::save(state,&mut s,"waiting_cold_start")?;
    }
    run_owned(root,state,operation)
}
fn wait_for_coordinator(state: &Path, operation: &str) -> Result<storage::Lock> {
    let deadline=std::time::Instant::now()+Duration::from_secs(30);
    loop {
        match storage::Lock::acquire(&coordinator_lock(state,operation)) {
            Ok(owner)=>return Ok(owner),
            Err(e) if lock_contended(&e) && std::time::Instant::now()<deadline=>
                std::thread::sleep(Duration::from_millis(100)),
            Err(e)=>return Err(e.context("Previous local coordinator has not relinquished ownership; retry recovery")),
        }
    }
}
fn current(state: &Path, operation: &str) -> Result<Option<updates::UpdateStatus>> {
    ensure!(upgrade::local::canonical_uuid(operation), "Invalid installer operation");
    let s=updates::read_status(state)?;
    if s.operation_id != operation || !(resumable_update(&s) || cold_admitted(state,&s)? ||
        restart_requested(state,&s) && matches!(s.phase.as_str(),"switching")) { return Ok(None) }
    Ok(Some(s))
}
fn startup_command(executable: &Path, root: &Path, state: &Path, operation: &str) -> Result<String> {
    ensure!(upgrade::local::canonical_uuid(operation),"Invalid installer operation");
    let mut parts=Vec::new();
    for value in [executable,root,state] {
        storage::plain(value)?;
        let value=value.to_str().context("Startup path is not Unicode")?;
        ensure!(!value.contains(['"','\r','\n']),"Invalid startup path");
        parts.push(value);
    }
    // Windows filenames cannot contain quotes. These paths are arguments to
    // CreateProcess, never PowerShell or cmd expressions.
    // Run registry values have a 260-character command limit. Recover root and
    // state from the verified generation instead of repeating both long paths.
    let command=format!("\"{}\" --local-update {}",parts[0],operation);
    ensure!(command.encode_utf16().count()<=260,"Installer recovery command exceeds Windows startup limit");
    Ok(command)
}
fn startup(operation: &str, command: Option<&str>) -> Result<()> {
    ensure!(upgrade::local::canonical_uuid(operation),"Invalid installer operation");
    #[cfg(windows)] {
        let system=std::env::var_os("SystemRoot").context("System root missing")?;
        let mut reg=Command::new(PathBuf::from(system).join("System32/reg.exe"));
        reg.args([if command.is_some(){"ADD"}else{"DELETE"},r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
            "/v",&format!("CodexPocketUpdate-{operation}"),"/f"]);
        if let Some(value)=command {reg.args(["/t","REG_SZ","/d",value]);}
        crate::process::hidden(&mut reg);
        let status=reg.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).status()?;
        // Deleting an already absent entry is idempotent.
        ensure!(command.is_none()||status.success(),"Cannot register installer recovery");
        Ok(())
    }
    #[cfg(not(windows))] {let _=command;anyhow::bail!("Local installer is Windows-only")}
}

fn coordinator_lock(state: &Path, operation: &str) -> PathBuf {
    updates::directory(state).join(format!("local-{operation}.lock"))
}
pub fn lock_contended(error: &anyhow::Error) -> bool {
    error.downcast_ref::<std::io::Error>().is_some_and(|io| {
        io.kind() == std::io::ErrorKind::WouldBlock ||
            io.raw_os_error().is_some() && io.raw_os_error() == fs2::lock_contended_error().raw_os_error()
    })
}

fn coordinator_command(executable: &Path, root: &Path, state: &Path, operation: &str) -> Command {
    let mut command = Command::new(executable);
    command.current_dir(root).arg("--local-update").arg(root).arg(state).arg(operation)
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    crate::process::hidden(&mut command);
    command
}

/// A login or UI startup delegates a pending cold update to its coordinator.
/// During recovery the trust anchor remains the previous installed generation.
pub(crate) fn background_update_command(root: &Path, state: &Path, installed: &Path) -> Result<Option<Command>> {
    let s = match updates::read_status(state) {
        Ok(status) => status,
        Err(error) => {
            // The installer independently records a successful, authenticated
            // boot. A broken optional update file cannot promote a candidate.
            let verified: serde_json::Value = storage::read(&root.join("installer-lifecycle.json"))?;
            let generation = installed.file_name().and_then(|name| name.to_str());
            if verified["schema"] == 1 && verified["phase"] == "verified"
                && verified["targetGeneration"].as_str() == generation
                && verified["installRoot"].as_str().map(Path::new) == Some(root)
                && verified["stateDirectory"].as_str().map(Path::new) == Some(state)
                && cold_journals_clear(root, state)? {
                return Ok(None);
            }
            return Err(error);
        }
    };
    if !cold_admitted(state,&s)? { return Ok(None) }
    if s.phase != "restarting_legacy" && !cold_journals_clear(root,state)? { return Ok(None) }
    let anchor = if s.phase == "cold_starting" {
        let old = s.previous_record.as_ref().context("Missing previous install record")?;
        let generation = old["generation"].as_str().context("Missing previous generation")?;
        ensure!(generation.len()==64 && generation.bytes().all(|b| b.is_ascii_hexdigit())
            && old["releaseHash"]==generation && old["installRoot"].as_str().map(Path::new)==Some(root)
            && old["stateDirectory"].as_str().map(Path::new)==Some(state),"Invalid previous installation");
        let bin=root.join("bin").join(generation);
        ensure!(crate::installation::generation(&bin)?==generation,"Previous manifest changed");
        bin
    } else { installed.to_path_buf() };
    let candidate = (|| -> Result<PathBuf> {
        let digest = s.generation.as_deref().context("Candidate missing")?;
        ensure!(digest.len()==64 && digest.bytes().all(|b| b.is_ascii_hexdigit()), "Invalid generation");
        let target = root.join("bin").join(digest);
        let policy = updates::trust(&anchor,state)?;
        let manifest: serde_json::Value = storage::read(&anchor.join("windows-manifest.json"))?;
        use std::io::Read;
        let mut bytes=Vec::new();
        storage::open_physical(&updates::directory(state).join("offer.json"))?.take(updates::MAX_OFFER+1).read_to_end(&mut bytes)?;
        let offer=updates::verify_offer_for(&policy,&bytes,manifest["version"].as_str().context("Missing version")?,storage::now(),s.explicit_install)?;
        ensure!(offer.generation==digest && s.version.as_deref()==Some(offer.version.as_str()) && crate::installation::generation(&target)?==digest,"Staged generation changed");
        upgrade::local::verify_generation_inventory(&target)?;
        launcher::verified_exe(&target,"pocket-upgrade.exe")
    })();
    match candidate {
        Ok(exe) => Ok(Some(coordinator_command(&exe,root,state,&s.operation_id))),
        Err(error) if matches!(s.phase.as_str(), "cold_starting" | "restarting_legacy") => Err(error.context("Interrupted update requires recovery")),
        Err(_) => {
            // Keep the working backend bootable if staged bytes were lost.
            // Do not change a newer or already-switching operation.
            let _worker=storage::Lock::acquire(&updates::directory(state).join("worker.lock"))?;
            let mut same=updates::read_status(state)?;
            ensure!(same.operation_id==s.operation_id && same.phase==s.phase && same.generation==s.generation,"Update changed before boot");
            same.failure=Some("staged_candidate_unavailable".into());
            updates::save(state,&mut same,"failed")?;
            Ok(None)
        }
    }
}

pub fn dispatch(root: &Path,state: &Path) -> Result<()> {
    dispatch_from(root, state, true, None)
}
pub fn resume_from_manager(root: &Path,state: &Path) -> Result<()> {
    dispatch_from(root, state, false, None)
}
fn dispatch_from(root: &Path,state: &Path, installer_job: bool, expected: Option<&str>) -> Result<()> {
    let s=updates::read_status(state)?;
    ensure!(expected.is_none_or(|id| id==s.operation_id),"Update selection changed; refresh and retry");
    ensure!(resumable_update(&s) || restart_requested(state,&s),"No requested update transaction");
    // Serialize queue publication with the actual coordinator. In particular,
    // running the installer again must not overwrite an active wait reason.
    let _owner = match storage::Lock::acquire(&coordinator_lock(state, &s.operation_id)) {
        Ok(owner) => owner,
        Err(error) if lock_contended(&error) => return Ok(()),
        Err(error) => return Err(error),
    };
    let Some(s) = current(state, &s.operation_id)? else { return Ok(()) };
    let generation=s.generation.as_deref().context("Candidate generation missing")?;
    ensure!(generation.len()==64&&generation.bytes().all(|b|b.is_ascii_hexdigit()),"Invalid candidate generation");
    let bin=root.join("bin").join(generation);
    ensure!(crate::installation::generation(&bin)?==generation,"Candidate manifest changed");
    let executable=launcher::verified_exe(&bin,"pocket-upgrade.exe")?;
    let recovery=startup_command(&executable,root,state,&s.operation_id)?;
    startup(&s.operation_id,Some(&recovery))?;
    storage::atomic(&journal(state,&s.operation_id),&json!({"phase":"queued","updatedAt":storage::now()}))?;
    drop(_owner);
    let dispatched=if installer_job {
        crate::update_task::coordinator(&executable,root,&s.operation_id,false)
    } else {
        coordinator_command(&executable,root,state,&s.operation_id).spawn().map(|_|()).map_err(Into::into)
    };
    if dispatched.is_err() {
        storage::atomic(&journal(state,&s.operation_id),&json!({"phase":"waiting_login","reason":"independent_dispatch_failed","updatedAt":storage::now()}))?;
        anyhow::bail!("Installer was staged; independent coordinator needs next login")
    }
    Ok(())
}

// These are the exact same exclusive locks the supervisor and agent hold.
// Missing RPC or a stale heartbeat never authorizes publication. Retain the
// guards across the install-record write so an old owner cannot race it.
fn cold_owner_locks(root: &Path, state: &Path) -> Result<Option<Vec<storage::Lock>>> {
    let paths = [
        root.join("control/supervisor.lock"),
        storage::control(state).join("supervisor.lock"),
        state.join("agent.lock"),
    ];
    let mut proof = Vec::new();
    for path in paths {
        storage::private_dir(path.parent().context("Missing owner directory")?)?;
        match storage::Lock::acquire(&path) {
            Ok(lock) => proof.push(lock),
            Err(error) if error.downcast_ref::<std::io::Error>().is_some_and(|e|
                e.raw_os_error() == fs2::lock_contended_error().raw_os_error()) => return Ok(None),
            Err(error) => return Err(error),
        }
    }
    Ok(Some(proof))
}

fn cold_journals_clear(root: &Path, state: &Path) -> Result<bool> {
    Ok(other_journals_clear(root, state)? && upgrade::local::cold_agent_boot_allowed(state)?)
}
fn other_journals_clear(root: &Path, state: &Path) -> Result<bool> {
    let pending_installer = root.join("installation-pending.json");
    let maintenance_request = storage::control(state).join("maintenance-request.json");
    for path in [pending_installer, maintenance_request] {
        storage::unredirected(&path)?;
        if path.try_exists()? { return Ok(false) }
    }
    use crate::supervisor::maintenance::{self, Phase};
    Ok(upgrade::read_status(state)?.is_none_or(|h| matches!(h.phase,
        upgrade::Phase::Succeeded | upgrade::Phase::RolledBack | upgrade::Phase::WaitingRestart))
        && maintenance::read_status(state)?.is_none_or(|j| j.phase != Phase::Owned)
        && crate::native_update::read_status(state)?.is_none_or(|n| matches!(n.phase.as_str(), "succeeded" | "rolled_back")))
}

// Return true if the explicit coordinator now owns the startup (including a
// resumed intent). A busy live broker keeps its original handoff path.
fn cold_start(root: &Path, state: &Path, operation: &str) -> Result<bool> {
    use std::io::Read;
    use serde_json::Value;
    let _worker = storage::Lock::acquire(&updates::directory(state).join("worker.lock"))?;
    let _install = updates::InstallMutex::acquire(root)?;
    storage::private_dir(&root.join("control"))?;
    let _handoff = storage::Lock::acquire(&root.join("control/upgrade.lock"))?;
    let Some(mut status) = current(state, operation)? else { return Ok(false) };
    if matches!(status.phase.as_str(), "switching" | "restarting_legacy") { return Ok(false) }
    let target = status.generation.as_deref().context("Missing staged generation")?.to_owned();
    let (installed, installed_state) = launcher::resolve_release(root)?;
    ensure!(installed_state == state, "Installation state changed");
    let installed_generation = crate::installation::generation(&installed)?;
    if status.phase == "cold_starting" && status.failure.as_deref() == Some("cold_rollback_pending") {
        let old = status.previous_record.as_ref().context("Missing cold predecessor")?;
        let predecessor = old["generation"].as_str().context("Missing previous generation")?;
        ensure!(predecessor.len() == 64 && predecessor != target && predecessor.bytes().all(|b|
            b.is_ascii_digit() || (b'a'..=b'f').contains(&b)), "Invalid cold predecessor");
        if predecessor == installed_generation && cold_verified(root, state, &installed_generation) {
            status.failure = Some("cold_startup_failed".into());
            updates::save(state, &mut status, "rolled_back")?;
        } else if storage::now().saturating_sub(status.updated_at) >= 30 {
            if let Some(owners) = cold_owner_locks(root, state)? {
                if cold_journals_clear(root, state)? {
                    // A crash may have left either side of the install pointer
                    // write. Validate both records before retrying the old binary.
                    let prior_bin = root.join("bin").join(predecessor);
                    ensure!(crate::installation::generation(&prior_bin)? == predecessor, "Previous manifest changed");
                    return restore_cold_program(root, state, &mut status, &prior_bin, owners);
                }
            }
        }
        return Ok(true);
    }
    if status.phase == "cold_starting" && status.failure.as_deref() == Some("cold_launch_pending") {
        if installed_generation == target && cold_verified(root, state, &target) {
            status.failure = None;
            updates::save(state, &mut status, "succeeded")?;
            return Ok(true);
        }
        // The supervisor and agent use nonblocking owner locks. Polling those
        // locks while either process is acquiring them can abort a valid boot.
        if storage::now().saturating_sub(status.updated_at) < 30 { return Ok(true) }
    }
    let Some(owners) = cold_owner_locks(root, state)? else { return Ok(status.phase == "cold_starting") };
    if !cold_journals_clear(root, state)? { return Ok(status.phase == "cold_starting") }

    let path = root.join("desktop-install.json");
    let recorded: Value = storage::read(&path)?;
    let old = if status.phase == "cold_starting" {
        status.previous_record.clone().context("Missing cold predecessor")?
    } else { recorded.clone() };
    let prior = old["generation"].as_str().context("Missing previous generation")?;
    ensure!([prior, &target].iter().all(|g| g.len() == 64
        && g.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))), "Invalid cold generations");
    ensure!(prior != target && old["stateDirectory"].as_str().map(Path::new) == Some(state)
        && old["installRoot"].as_str().map(Path::new) == Some(root), "Invalid cold predecessor");
    let prior_bin = root.join("bin").join(prior);
    ensure!(crate::installation::generation(&prior_bin)? == prior, "Previous manifest changed");
    let next_bin = root.join("bin").join(&target);
    ensure!(crate::installation::generation(&next_bin)? == target, "Staged manifest changed");
    let policy = updates::trust(&prior_bin, state)?;
    let signed = storage::open_physical(&updates::directory(state).join("offer.json"))?;
    let mut bytes = Vec::new();
    signed.take(updates::MAX_OFFER + 1).read_to_end(&mut bytes)?;
    let manifest: Value = storage::read(&prior_bin.join("windows-manifest.json"))?;
    let offer = updates::verify_offer_for(&policy, &bytes,
        manifest["version"].as_str().context("Missing installed version")?, storage::now(), status.explicit_install)?;
    ensure!(offer.generation == target && status.version.as_deref() == Some(offer.version.as_str()), "Signed cold candidate differs");
    upgrade::local::verify_generation_inventory(&next_bin)?;
    // A rollback is a program switch only, never a database snapshot restore.
    // Validate that the prior owner can still be launched before publication.
    for name in ["pocket-agent.exe", "pocket-supervisor.exe"] {
        launcher::verified_exe(&prior_bin, name)?;
    }
    let prior_hash = status.previous_record_hash.clone();
    let target_hash = status.candidate_record_hash.clone();
    let next = updates::prepare_switch(&mut status, old.clone(), &target)?;
    ensure!(recorded == old || (status.phase == "cold_starting" && recorded == next), "Installation changed during cold startup");
    if status.phase == "cold_starting" {
        ensure!(prior_hash == status.previous_record_hash && target_hash == status.candidate_record_hash, "Cold startup intent changed");
    } else {
        updates::save(state, &mut status, "cold_starting")?;
    }
    if status.failure.as_deref() == Some("cold_launch_pending") {
        // The previous attempt did not leave either a verified successor or an
        // owner of the state. Do not retry it indefinitely after a failed boot.
        return restore_cold_program(root, state, &mut status, &prior_bin, owners);
    }
    status.failure = Some("cold_launch_pending".into());
    updates::save(state, &mut status, "cold_starting")?;
    if recorded != next { storage::atomic(&path, &next)?; }
    // The successor takes these same locks. Release them only after the
    // signed generation has become the launcher's selected target.
    drop(owners);
    if launcher::spawn_supervisor(&next_bin, state).is_err() {
        if let Some(owners) = cold_owner_locks(root, state)? {
            return restore_cold_program(root, state, &mut status, &prior_bin, owners);
        }
    }
    Ok(true)
}

fn restore_cold_program(root: &Path, state: &Path, status: &mut updates::UpdateStatus,
    prior_bin: &Path, owners: Vec<storage::Lock>) -> Result<bool> {
    status.failure = Some("cold_rollback_pending".into());
    updates::save(state, status, "cold_starting")?;
    updates::restore_record(root, status)?;
    drop(owners);
    // A failed spawn leaves the durable restore intent in place. A later poll
    // retries only after the grace period and after proving all owners absent.
    let _ = launcher::spawn_supervisor(prior_bin, state);
    Ok(true)
}

fn cold_verified(root: &Path, state: &Path, generation: &str) -> bool {
    use serde_json::{json, Value};
    use crate::supervisor::maintenance::ProcessIdentity;
    (|| -> Result<()> {
        let health = crate::native_update::authenticated_helper(root, state, generation)?;
        let supervisor: Value = storage::read(&storage::control(state).join("supervisor.json"))?;
        let pid = supervisor["pid"].as_u64().and_then(|v| u32::try_from(v).ok()).context("Missing supervisor PID")?;
        let process = ProcessIdentity::capture(pid)?;
        let bin = root.join("bin").join(generation);
        let manifest: Value = storage::read(&bin.join("windows-manifest.json"))?;
        ensure!(process.executable == launcher::verified_exe(&bin, "pocket-supervisor.exe")?
            && supervisor["runtime"]["processCreatedAt"] == process.created_at
            && supervisor["runtime"]["generation"] == generation
            && supervisor["runtime"]["buildId"] == manifest["buildId"]
            && supervisor["childPid"] == health["helperRuntime"]["pid"]
            && health["helperRuntime"]["buildId"] == manifest["buildId"]
            && health["helperRuntime"]["version"] == manifest["version"]
            && health["protocol"] == 1 && health["acceptingWrites"] == true
            && (storage::local_app_data().is_ok_and(|local|root!=local.join("CodexPocket"))
                || !manifest["files"].as_array().is_some_and(|files|files.iter().any(|f|f["path"]=="pocket-native-proxy.exe"))
                || supervisor["nativeEntryReady"]==true),
            "Cold startup identity not verified");
        ensure!(crate::process::local_control(state, "routes/read", json!({}))?["routePreferences"].is_object(),
            "Cold startup could not read saved routes");
        Ok(())
    })().is_ok()
}

/// A signed explicit installer can replace a running candidate whose embedded
/// build identity disproves successful activation. This only admits staging;
/// the normal fenced restart still protects native tasks and outstanding work.
pub(crate) fn cold_build_mismatch(root:&Path,state:&Path,status:&updates::UpdateStatus)->Result<bool> {
    if status.phase!="cold_starting" || status.failure.as_deref()!=Some("cold_launch_pending")
        || storage::now().saturating_sub(status.updated_at)<30 || !cold_journals_clear(root,state)? {return Ok(false)}
    let Some(generation)=status.generation.as_deref() else {return Ok(false)};
    let (bin,recorded)=launcher::resolve_release(root)?;
    if recorded!=state || crate::installation::generation(&bin)?!=generation {return Ok(false)}
    let health=crate::native_update::authenticated_helper(root,state,generation)?;
    let supervisor:serde_json::Value=storage::read(&storage::control(state).join("supervisor.json"))?;
    let pid=supervisor["pid"].as_u64().and_then(|n|u32::try_from(n).ok()).context("Missing supervisor PID")?;
    let process=crate::supervisor::maintenance::ProcessIdentity::capture(pid)?;
    ensure!(process.executable==launcher::verified_exe(&bin,"pocket-supervisor.exe")?
        && supervisor["runtime"]["processCreatedAt"]==process.created_at
        && supervisor["runtime"]["generation"]==generation
        && supervisor["childPid"]==health["helperRuntime"]["pid"],"Cold owner identity changed");
    let manifest:serde_json::Value=storage::read(&bin.join("windows-manifest.json"))?;
    Ok(health["protocol"]==1 && health["acceptingWrites"]==true
        && embedded_build_differs(&manifest,&health["helperRuntime"],&supervisor["runtime"]))
}

fn embedded_build_differs(manifest:&serde_json::Value,helper:&serde_json::Value,supervisor:&serde_json::Value)->bool {
    let Some(expected)=manifest["buildId"].as_str().filter(|s|!s.is_empty()) else {return false};
    [helper,supervisor].iter().any(|runtime|runtime["buildId"].as_str().is_some_and(|actual|!actual.is_empty()&&actual!=expected))
}

#[test]
fn build_recovery_requires_observed_mismatch_not_missing_health() {
    let manifest=json!({"buildId":"release"});
    assert!(!embedded_build_differs(&manifest,&json!({}),&json!({})));
    assert!(!embedded_build_differs(&manifest,&json!({"buildId":"release"}),&json!({"buildId":"release"})));
    assert!(embedded_build_differs(&manifest,&json!({"buildId":"development"}),&json!({"buildId":"release"})));
    assert!(embedded_build_differs(&manifest,&json!({"buildId":"release"}),&json!({"buildId":"wrong"})));
}

fn advance(root: &Path,state: &Path,operation: &str) -> Result<(bool, Option<&'static str>)> {
    let Some(before)=current(state,operation)? else{return Ok((true, None))};
    updates::recover_unrequested_switch(root,state)?;
    if current(state,operation)?.is_none(){return Ok((true, None))}
    if let Some(mut h)=upgrade::read_status(state)? {
        if before.phase=="switching" && before.generation.as_deref()==Some(&h.to_generation) && !h.phase.terminal() {
            // The handoff owner lock excludes a live coordinator. After a crash
            // the same journal resumes through the existing rollback protocol.
            h=upgrade::run(root,state,&h.operation_id)?;
        }
        updates::finish_handoff(root,state,&h)?;
    }
    if before.phase == "restarting_legacy" || before.phase == "waiting_cold_start" && apply_requested(state,&before)
        || apply_requested(state,&before) && matches!(before.phase.as_str(),"staged"|"waiting_idle") {
        legacy_restart::advance(root, state, operation)?;
    }
    if cold_start(root, state, operation)? { return Ok((current(state, operation)?.is_none(), None)) }
    let Some(s)=current(state,operation)? else{return Ok((true, None))};
    let reason = (apply_requested(state,&s) && matches!(s.phase.as_str(), "staged" | "waiting_idle" | "waiting_cold_start"))
        .then(|| legacy_restart::wait_reason(state, operation).unwrap_or("restart_fence_pending"));
    Ok((current(state, operation)?.is_none(), reason))
}

/// An explicit, signed installer can deliver the management window before
/// the live helper is able to hand off. This never selects a helper or changes
/// the install pointer. Commands in the window still use the live installation.
pub fn dashboard_binary(root: &Path, state: &Path, installed: &Path) -> Result<Option<PathBuf>> {
    let status = updates::read_status(state)?;
    if !status.explicit_install || !(matches!(status.phase.as_str(), "staged" | "waiting_idle" | "waiting_cold_start" | "restarting_legacy") || retryable_handoff(&status)) {
        return Ok(None);
    }
    let (selected, recorded_state) = launcher::resolve_release(root)?;
    ensure!(selected == installed && recorded_state == state, "Dashboard installation changed");
    let manifest: serde_json::Value = storage::read(&installed.join("windows-manifest.json"))?;
    let baseline = manifest["version"].as_str().context("Missing installed version")?;
    // The dashboard targets the local-control API implemented by 0.7.0 and
    // newer. Still older installations keep the original UI during staging.
    if semver::Version::parse(baseline)? < semver::Version::new(0, 7, 0) {
        return Ok(None);
    }
    use std::io::Read;
    let mut signed = Vec::new();
    storage::open_physical(&updates::directory(state).join("offer.json"))?
        .take(updates::MAX_OFFER + 1).read_to_end(&mut signed)?;
    let policy = updates::trust(installed, state)?;
    let offer = updates::verify_offer_for(&policy, &signed, baseline, storage::now(), true)?;
    ensure!(status.generation.as_deref() == Some(&offer.generation)
        && status.version.as_deref() == Some(&offer.version), "Dashboard offer differs from the queued update");
    let target = root.join("bin").join(&offer.generation);
    ensure!(crate::installation::generation(&target)? == offer.generation, "Dashboard manifest changed");
    Ok(Some(launcher::verified_exe(&target, "pocket-desktop.exe")?))
}

fn retryable_handoff(status: &updates::UpdateStatus) -> bool {
    status.phase=="failed" && status.failure.as_deref()==Some("legacy_restart_unverified")
}

pub fn is_staged_dashboard(state: &Path, current: &Path, installed: &Path) -> bool {
    updates::read_status(state).is_ok_and(|status| status.explicit_install
        && (matches!(status.phase.as_str(), "staged" | "waiting_idle" | "waiting_cold_start" | "switching" | "restarting_legacy" | "cold_starting") || retryable_handoff(&status))
        && status.generation.as_deref().is_some_and(|generation| installed.parent()
            .is_some_and(|bins| current == bins.join(generation).join("pocket-desktop.exe"))))
}

pub fn public_status(state: &Path,operation: &str) -> serde_json::Value {
    if !upgrade::local::canonical_uuid(operation){return serde_json::Value::Null}
    storage::read::<serde_json::Value>(&journal(state,operation)).map(|s|{
        let mut public=json!({"phase":s["phase"],"reason":s["reason"]});
        if s["reason"]=="restart_lock_owner_active" {
            if let Some(id)=legacy_restart::notice_id(state,operation){public["noticeId"]=json!(id);}
        }
        public
    }).unwrap_or(serde_json::Value::Null)
}

/// The visible management window acknowledges the exact lock notice it painted.
/// This is not a new approval; the signed explicit installer already owns the restart.
pub fn acknowledge_restart_notice(state:&Path,operation:&str,notice_id:&str)->Result<()> {
    legacy_restart::acknowledge_notice(state,operation,notice_id)
}

pub fn run(root: &Path,state: &Path,operation: &str) -> Result<()> {
    ensure!(upgrade::local::canonical_uuid(operation),"Invalid installer operation");
    storage::private_dir(&updates::directory(state))?;
    let _owner=storage::Lock::acquire(&coordinator_lock(state, operation))?;
    run_owned(root,state,operation)
}
fn run_owned(root: &Path,state: &Path,operation: &str) -> Result<()> {
    let mut failures=0;
    let mut verifying_since=None;
    // Run outside the short-lived installer job. A pre-existing dashboard may
    // predate cooperative self-restart and must also move on silent installs.
    let mut dashboard_done=false;
    let mut presented_restart_notice=None;
    loop {
        if !dashboard_done && updates::read_status(state).is_ok_and(|s| s.operation_id == operation && s.explicit_install) {
            dashboard_done = crate::ui_update::open(root, state, true).is_ok();
        }
        match advance(root,state,operation) {
            Ok((true, _))=>{
                let completed=updates::read_status(state)?;
                if completed.operation_id==operation && apply_requested(state,&completed)
                    && matches!(completed.phase.as_str(),"succeeded"|"rolled_back") {
                    crate::ui_update::restart_after_update(root,state)?;
                }
                startup(operation,None)?;
                crate::update_task::coordinator(&std::env::current_exe()?,root,operation,true)?;
                storage::atomic(&journal(state,operation),&json!({"phase":if completed.phase=="failed" {"failed"}else{"finished"},
                    "reason":completed.failure,"updatedAt":storage::now()}))?;
                ensure!(completed.phase!="failed", "Local update failed; inspect its durable status");
                return Ok(())
            },
            Ok((false, wait_reason))=>{
                failures=0;
                let s=updates::read_status(state)?;
                let (phase, reason) = if s.phase == "cold_starting" {
                    let since = verifying_since.get_or_insert_with(std::time::Instant::now);
                    if s.failure.as_deref() == Some("cold_rollback_pending") {
                        ("verifying_rollback", Some("previous_helper_not_verified"))
                    } else if since.elapsed() >= Duration::from_secs(60) {
                        ("verifying_startup", Some("live_owner_not_verified"))
                    } else { ("verifying_startup", None) }
                } else { verifying_since = None; ("waiting", wait_reason) };
                storage::atomic(&journal(state,operation),&json!({"phase":phase,"reason":reason,"updatedAt":storage::now()}))?;
                if reason == Some("restart_lock_owner_active") {
                    if let Some(notice)=legacy_restart::notice_id(state,operation) {
                        // Lock takeover must display its notice even if the dashboard
                        // was hidden in the tray or Setup was started silently.
                        if presented_restart_notice.as_ref()!=Some(&notice)
                            && crate::ui_update::open(root,state,false).is_ok() {
                            presented_restart_notice=Some(notice);
                        }
                    }
                }
            },
            Err(error)=>{
                // Save only fixed failure classifications, never private RPC bodies.
                let stage = error.chain().find_map(|e| legacy_restart::failure_stage(&e.to_string()));
                failures+=1;
                storage::atomic(&journal(state,operation),&json!({"phase":if failures>=6{"waiting_login"}else{"waiting"},"reason":stage.unwrap_or("local_install_unavailable"),"updatedAt":storage::now()}))?;
                if failures>=6 {anyhow::bail!("Installer coordination needs retry")}
            }
        }
        std::thread::sleep(Duration::from_secs(5));
    }
}

#[cfg(test)] mod tests {
    use super::*;
    #[test]
    fn terminal_recovery_lets_the_old_local_owner_exit_before_rearming() {
        let root=crate::test_support::tempdir();let state=root.path().join("state");
        let mut s=updates::UpdateStatus{explicit_install:true,generation:Some("a".repeat(64)),..Default::default()};
        updates::save(&state,&mut s,"switching").unwrap();
        let lock=storage::Lock::acquire(&coordinator_lock(&state,&s.operation_id)).unwrap();
        let old_state=state.clone();let operation=s.operation_id.clone();
        let old=std::thread::spawn(move||{
            let _owner=lock;
            while current(&old_state,&operation).unwrap().is_some() {
                std::thread::sleep(Duration::from_millis(10));
            }
        });
        s.failure=Some("handoff_rolled_back".into());updates::save(&state,&mut s,"rolled_back").unwrap();
        let _new_owner=wait_for_coordinator(&state,&s.operation_id).unwrap();
        old.join().unwrap();
        assert_eq!(updates::read_status(&state).unwrap().phase,"rolled_back");
        assert!(coordinator_lock(&state,&s.operation_id).exists());
    }
    #[test] fn failed_handoff_keeps_the_signed_dashboard_for_retry() {
        let root=crate::test_support::tempdir();let state=root.path().join("state");
        let installed=root.path().join("bin").join("b".repeat(64));
        let current=root.path().join("bin").join("a".repeat(64)).join("pocket-desktop.exe");
        let mut s=updates::UpdateStatus {explicit_install:true,generation:Some("a".repeat(64)),failure:Some("legacy_restart_unverified".into()),..Default::default()};
        updates::save(&state,&mut s,"failed").unwrap();
        assert!(retryable_handoff(&s));assert!(is_staged_dashboard(&state,&current,&installed));
        s.failure=Some("staged_candidate_unavailable".into());updates::save(&state,&mut s,"failed").unwrap();
        assert!(!retryable_handoff(&s));assert!(!is_staged_dashboard(&state,&current,&installed));
    }
    #[test] fn manual_restart_resumes_only_its_operation_without_changing_trust_or_automatic_setting() {
        let root=crate::test_support::tempdir(); let state=root.path().join("state");
        updates::set_automatic(&state,false).unwrap();
        let mut s=updates::UpdateStatus{phase:"staged".into(),generation:Some("a".repeat(64)),..Default::default()};
        updates::save(&state,&mut s,"staged").unwrap();
        storage::atomic(&updates::directory(&state).join("restart-request.json"),
            &json!({"operationId":s.operation_id,"generation":s.generation,"requestedAt":storage::now()})).unwrap();
        assert!(current(&state,&s.operation_id).unwrap().is_some());
        assert!(!updates::settings(&state).unwrap().automatic);
        assert!(!updates::read_status(&state).unwrap().explicit_install);
        s.operation_id=uuid::Uuid::new_v4().to_string();
        updates::save(&state,&mut s,"staged").unwrap();
        assert!(current(&state,&s.operation_id).unwrap().is_none());
    }
    #[test] fn prior_or_finished_installer_never_resumes_another_operation() {
        let root=crate::test_support::tempdir();let state=root.path().join("state");
        storage::private_dir(&updates::directory(&state)).unwrap();
        let mut s=updates::UpdateStatus{explicit_install:true,phase:"staged".into(),..Default::default()};
        let path=updates::directory(&state).join("status.json");
        storage::atomic(&path,&s).unwrap();
        assert!(current(&state,&s.operation_id).unwrap().is_some());
        assert!(advance(root.path(),&state,&uuid::Uuid::new_v4().to_string()).unwrap().0);
        for phase in ["succeeded","failed","waiting_restart","rolled_back"] {
            s.phase=phase.into();storage::atomic(&path,&s).unwrap();
            assert!(advance(root.path(),&state,&s.operation_id).unwrap().0);
        }
        s.phase="staged".into();s.explicit_install=false;storage::atomic(&path,&s).unwrap();
        updates::set_automatic(&state,false).unwrap();
        assert!(advance(root.path(),&state,&s.operation_id).unwrap().0);
    }
    #[test]
    fn duplicate_dispatch_does_not_replace_a_live_coordinators_journal() {
        let tmp = crate::test_support::tempdir();
        let state = tmp.path();
        let op = uuid::Uuid::new_v4().to_string();
        let dir = updates::directory(state);
        storage::private_dir(&dir).unwrap();
        let status = updates::UpdateStatus { operation_id: op.clone(), explicit_install: true,
            phase: "waiting_idle".into(), generation: Some("a".repeat(64)), ..Default::default() };
        storage::atomic(&dir.join("status.json"), &status).unwrap();
        storage::atomic(&journal(state, &op), &json!({"phase":"waiting","reason":"runtime_activity_unverified"})).unwrap();
        let _owner = storage::Lock::acquire(&dir.join(format!("local-{op}.lock"))).unwrap();
        // No package should even be opened (the test has no candidate files).
        dispatch(tmp.path(), state).unwrap();
        assert_eq!(public_status(state, &op), json!({"phase":"waiting","reason":"runtime_activity_unverified"}));
    }
    #[test]
    fn coordinator_child_runs_outside_the_manager_installation() {
        let temp = crate::test_support::tempdir();
        let root = temp.path().join("product");
        let coordinator = root.join("bin/generation/pocket-upgrade.exe");
        let command = coordinator_command(&coordinator, &root, &root.join("agent"), "fixture");
        assert_eq!(command.get_current_dir(), Some(root.as_path()));
    }
    #[test] fn startup_paths_are_literal_and_operation_scoped() {
        let dir=crate::test_support::tempdir();let root=dir.path().join("space & ($data)");
        let op=uuid::Uuid::new_v4().to_string();
        let command=startup_command(&root.join("upgrade.exe"),&root,&root.join("state"),&op).unwrap();
        assert!(command.contains("space & ($data)"));assert!(command.ends_with(&op));
        assert!(startup_command(&root.join("upgrade.exe"),&root,&root.join("state"),"bad").is_err());
    }
}

#[cfg(test)]
mod cold_activation_tests {
    use super::*;

    #[test]
    fn cold_activation_abandons_neither_an_active_handoff_nor_its_mailbox() {
        let temp = crate::test_support::tempdir();
        let root = temp.path();
        let state = root.join("agent");
        storage::private_dir(&storage::control(&state)).unwrap();
        assert!(cold_journals_clear(root, &state).unwrap());
        let mailbox = storage::control(&state).join("maintenance-request.json");
        std::fs::write(&mailbox, b"pending action").unwrap();
        assert!(!cold_journals_clear(root, &state).unwrap());
        assert_eq!(std::fs::read(&mailbox).unwrap(), b"pending action");
    }

    #[test]
    fn missing_candidate_leaves_the_predecessor_bootable() {
        let tmp = crate::test_support::tempdir();
        let root = tmp.path(); let state = root.join("agent");
        let mut s = updates::UpdateStatus { explicit_install: true, generation: Some("a".repeat(64)), ..Default::default() };
        updates::save(&state, &mut s, "waiting_idle").unwrap();
        assert!(background_update_command(root, &state, &root.join("absent" )).unwrap().is_none());
        let status = updates::read_status(&state).unwrap();
        assert_eq!(status.phase,"failed");
        assert_eq!(status.failure.as_deref(),Some("staged_candidate_unavailable"));
        assert_eq!(status.operation_id,s.operation_id);
        assert!(!root.join("desktop-install.json").exists());
    }

    /// Run with explicit old/new signed payloads in a disposable installation.
    /// The live Codex instance is a read-only pipe peer; never replaced here.
    #[cfg(windows)]
    #[test]
    #[ignore = "requires staged release payloads and official desktop running"]
    fn real_signed_cold_boot_preserves_unknown_receipts_and_detects_official_codex() {
        signed_cold_boot(false, false);
    }

    #[cfg(windows)]
    #[test]
    #[ignore = "requires staged release payloads; run alone"]
    fn real_signed_cold_boot_rolls_back_when_candidate_exits_before_ready() {
        signed_cold_boot(true, false);
    }

    #[cfg(windows)]
    #[test]
    #[ignore = "requires signed payloads; normal background launch wins the login race"]
    fn background_open_before_coordinator_boots_the_signed_candidate() {
        signed_cold_boot(false, true);
    }

    #[cfg(windows)]
    fn signed_cold_boot(failed_start: bool, background_first: bool) {
        use serde_json::{json, Value};
        use std::{fs, time::Instant};
        let from = PathBuf::from(std::env::var_os("POCKET_QA_OLD_PAYLOAD").unwrap());
        let source = PathBuf::from(std::env::var_os("POCKET_QA_NEW_PAYLOAD").unwrap());
        let offer = source.parent().unwrap().join("pocket-install-offer.json");
        let temp = crate::test_support::tempdir();
        let root = temp.path(); let state = root.join("agent");
        storage::private_dir(&state).unwrap();
        let manifest: Value = storage::read(&from.join("windows-manifest.json")).unwrap();
        let before = format!("{:x}", <sha2::Sha256 as sha2::Digest>::digest(fs::read(from.join("windows-manifest.json")).unwrap()));
        let bin = root.join("bin").join(&before);
        storage::private_dir(&bin).unwrap();
        for name in ["windows-manifest.json", "windows-update-trust.json", "pocket-agent.exe", "pocket-supervisor.exe"] {
            fs::copy(from.join(name), bin.join(name)).unwrap();
        }
        let mut init = Command::new(bin.join("pocket-agent.exe"));
        init.args(["init", "--state"]).arg(&state);
        crate::process::run_bounded(init, 20).unwrap();
        let config_path = state.join("config.json");
        let mut config: Value = storage::read(&config_path).unwrap();
        let isolated_home = root.join("codex-fixture");
        storage::private_dir(&isolated_home).unwrap();
        config["runtimeDirectories"] = json!([]);
        config["codexHomes"] = json!([isolated_home]);
        storage::atomic(&config_path, &config).unwrap();
        storage::private_dir(&storage::control(&state)).unwrap();
        storage::atomic(&storage::control(&state).join("desired.json"), &json!({"enabled":false,"changedAt":1})).unwrap();
        let computer = config["computerId"].as_str().unwrap();
        let ledger = json!({"version":1,"runtime":{"computer_id":computer,"runtime_id":"broker"},
            "generation":1,"last_now":storage::now(),"entries":[{"key":{"caller_id":"test","command_id":"known-unknown"},
            "fingerprint":vec![1;32],"status":"Unknown","generation":1,"created_at":1,"finished_at":null}]});
        storage::atomic(&state.join("commands.json"), &ledger).unwrap();
        let install_path = root.join("desktop-install.json");
        let original = json!({"version":3,"installRoot":root,"bridge":root.join("pocket-launcher.exe"),"stateDirectory":state,"generation":before,"releaseHash":before});
        storage::atomic(&install_path, &original).unwrap();
        updates::set_automatic(&state, false).unwrap();
        updates::stage_local(root, &state, &source, &offer, None).unwrap();
        let pending = updates::read_status(&state).unwrap();
        let operation = pending.operation_id; let target = pending.generation.unwrap();
        let guard = storage::Lock::acquire(&state.join("agent.lock")).unwrap();
        assert!(!cold_start(root, &state, &operation).unwrap());
        assert_eq!(storage::read::<Value>(&install_path).unwrap(), original);
        drop(guard);
        // A separate QA process owns the fixture exclusively. Terminate only
        // this fixture's exact executable paths before the temp directory drops.
        struct FixtureProcesses(Vec<PathBuf>);
        impl Drop for FixtureProcesses {
            fn drop(&mut self) {
                let mut stop = Command::new(storage::powershell());
                stop.args(["-NoProfile", "-Command", r#"
                    $bins = @($env:POCKET_QA_NEW_BIN, $env:POCKET_QA_OLD_BIN)
                    Get-Process -Name pocket-agent,pocket-supervisor,pocket-runtime-host,pocket-upgrade -ErrorAction SilentlyContinue |
                    Where-Object { $_.Path -and (Split-Path -LiteralPath $_.Path) -in $bins } |
                    Stop-Process -ErrorAction Stop
                "#]).env("POCKET_QA_NEW_BIN", &self.0[0]).env("POCKET_QA_OLD_BIN", &self.0[1]);
                let _ = crate::process::run_bounded(stop, 10);
            }
        }
        let _fixture_processes = FixtureProcesses(vec![root.join("bin").join(&target), bin.clone()]);
        // SAFETY: run this ignored test alone in its dedicated test executable.
        // Only this process's children inherit the disposable Codex config.
        unsafe { std::env::set_var("CODEX_HOME", &isolated_home); }
        if failed_start {
            // The candidate publication and launch intent were committed, but
            // the candidate exited before acquiring a durable owner lock.
            let mut intent = updates::read_status(&state).unwrap();
            let record = updates::prepare_switch(&mut intent, original.clone(), &target).unwrap();
            intent.phase = "cold_starting".into();
            intent.failure = Some("cold_launch_pending".into());
            intent.updated_at = storage::now();
            storage::atomic(&updates::directory(&state).join("status.json"), &intent).unwrap();
            storage::atomic(&install_path, &record).unwrap();
            let next_bin = root.join("bin").join(&target);
            let resumed = launcher::background_command(&next_bin, &state).unwrap();
            assert_eq!(resumed.get_program(), next_bin.join("pocket-upgrade.exe"),
                "a normal startup during publication must resume verification, not start an unverified helper");
            // During the launch grace, polling must not open any owner lock.
            // A directory here makes an attempted open fail observably.
            let owner_lock = root.join("control/supervisor.lock");
            fs::remove_file(&owner_lock).unwrap();
            fs::create_dir(&owner_lock).unwrap();
            assert!(cold_start(root, &state, &operation).unwrap());
            fs::remove_dir(&owner_lock).unwrap();
            assert_eq!(storage::read::<Value>(&install_path).unwrap(), record);
            intent.updated_at = storage::now() - 60;
            storage::atomic(&updates::directory(&state).join("status.json"), &intent).unwrap();
            let config_after_launch = fs::read(&config_path).unwrap();
            // Recovery invocation may start after a crash between persisting
            // rollback intent and restoring the install pointer or spawning.
            intent.failure = Some("cold_rollback_pending".into());
            storage::atomic(&updates::directory(&state).join("status.json"), &intent).unwrap();
            assert!(cold_start(root, &state, &operation).unwrap());
            let result = updates::read_status(&state).unwrap();
            assert_eq!(result.phase, "cold_starting", "rollback is not complete before the old helper is verified");
            assert_eq!(result.failure.as_deref(), Some("cold_rollback_pending"));
            assert_eq!(storage::read::<Value>(&install_path).unwrap(), original);
            let until = Instant::now() + Duration::from_secs(25);
            loop {
                assert!(cold_start(root, &state, &operation).unwrap());
                let result = updates::read_status(&state).unwrap();
                if result.phase == "rolled_back" {
                    assert_eq!(result.failure.as_deref(), Some("cold_startup_failed"));
                    let health = crate::native_update::authenticated_helper(root, &state, &before).unwrap();
                    assert_eq!(health["helperRuntime"]["buildId"], manifest["buildId"]);
                    break;
                }
                assert!(Instant::now() < until, "predecessor failed authenticated startup after program rollback");
                std::thread::sleep(Duration::from_millis(500));
            }
            assert_eq!(fs::read(&config_path).unwrap(), config_after_launch);
            assert_eq!(storage::read::<Value>(&state.join("commands.json")).unwrap()["entries"], ledger["entries"]);
            println!("Program restored to {}; configuration and Unknown receipt retained", manifest["buildId"]);
            return;
        }
        let mut coordinator = if background_first {
            // The scheduled launcher or UI has arrived before the Run entry.
            // Do not start any coordinator beforehand, or this cannot detect the race.
            let child = launcher::background_command(&bin, &state).unwrap().spawn().unwrap();
            let until = Instant::now() + Duration::from_secs(25);
            loop {
                if let Ok(h) = crate::process::local_control(&state, "status", json!({})) {
                    assert_eq!(h["helperRuntime"]["generation"], target, "ordinary startup launched the predecessor");
                    break;
                }
                assert!(Instant::now() < until, "background handshake missing");
                std::thread::sleep(Duration::from_millis(250));
            }
            child
        } else {
            // Packaged coordinator in its login command form, without a Run entry.
            let executable = launcher::verified_exe(&root.join("bin").join(&target), "pocket-upgrade.exe").unwrap();
            Command::new(executable).args(["--local-update", &operation]).spawn().unwrap()
        };
        let until = Instant::now() + Duration::from_secs(90);
        loop {
            let status = updates::read_status(&state).unwrap();
            let exit = coordinator.try_wait().unwrap();
            if status.phase == "succeeded" && exit.is_some_and(|e| e.success()) { break; }
            if status.phase == "rolled_back" || exit.is_some() || Instant::now() >= until {
                for path in [state.join("startup-failure.json"), storage::control(&state).join("supervisor.json")] {
                    eprintln!("fixture {}: {:?}", path.display(), storage::read::<Value>(&path));
                }
                panic!("signed coordinator failed to finish: phase={}, reason={:?}, exit={exit:?}", status.phase, status.failure);
            }
            std::thread::sleep(Duration::from_millis(500));
        }
        assert_eq!(public_status(&state, &operation)["phase"], "finished");
        let health = crate::native_update::authenticated_helper(root, &state, &target).unwrap();
        assert_eq!(health["nativeObservation"]["desktop"]["state"], "detected");
        assert_eq!(health["helperRuntime"]["version"], updates::read_status(&state).unwrap().version.unwrap());
        assert_ne!(health["helperRuntime"]["version"], manifest["version"]);
        assert_eq!(storage::read::<Value>(&config_path).unwrap(), config);
        assert_eq!(storage::read::<Value>(&state.join("commands.json")).unwrap()["entries"], ledger["entries"]);
        println!("Signed payload activated from {} to {}; existing official desktop detected, Unknown receipt retained", manifest["version"], health["helperRuntime"]["version"]);
    }

    #[test]
    fn cold_activation_needs_all_three_owner_locks() {
        let temp = crate::test_support::tempdir();
        let root = temp.path();
        let state = root.join("agent");
        storage::private_dir(&state).unwrap();
        let paths = [root.join("control/supervisor.lock"), storage::control(&state).join("supervisor.lock"), state.join("agent.lock")];
        for path in &paths {
            storage::private_dir(path.parent().unwrap()).unwrap();
            let old = storage::Lock::acquire(path).unwrap();
            assert!(cold_owner_locks(root, &state).unwrap().is_none());
            drop(old);
        }
        let proof = cold_owner_locks(root, &state).unwrap().unwrap();
        for path in &paths { assert!(storage::Lock::acquire(path).is_err()); }
        drop(proof);
        assert!(cold_owner_locks(root, &state).unwrap().is_some());
    }
}
