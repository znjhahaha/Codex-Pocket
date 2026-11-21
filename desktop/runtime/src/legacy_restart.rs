//! One-time migration for the v1 broker which counts persisted Unknown receipts
//! as outstanding writes. The journal is written before fencing either owner.
use super::*;
use crate::supervisor::maintenance::{self, Presence, ProcessIdentity};
use pocket_core::ledger::{CommandKey, Status};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{collections::BTreeMap, io::Read};

#[path = "restart_preservation.rs"]
mod preservation;

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Intent {
    operation: String,
    fence: String,
    run: String,
    from: String,
    to: String,
    supervisor: ProcessIdentity,
    agent: ProcessIdentity,
    unknown: u64,
    stop_authorized: bool,
    #[serde(default)]
    manual_restart: bool,
    #[serde(default)]
    unknown_receipts: Option<Vec<(CommandKey, [u8; 32])>>,
    #[serde(default)]
    preservation: Option<preservation::Proof>,
}
fn private_json(path: &Path) -> Result<Value> {
    let file = maintenance::open_agent_private(path)?;
    let limit = 64 * 1024 * 1024;
    ensure!(file.metadata()?.len() <= limit, "restart_state_too_large");
    read_document(file.take(limit + 1))
}
fn read_document(reader: impl Read) -> Result<Value> {
    Ok(serde_json::from_reader(std::io::BufReader::new(reader))?)
}
fn tombstones(ledger: &Value, questions: &Value) -> Result<BTreeMap<CommandKey, [u8; 32]>> {
    ensure!(ledger["version"] == 1, "restart_ledger_unverified");
    let mut keys = BTreeMap::new();
    for row in ledger["entries"]
        .as_array()
        .context("restart_ledger_unverified")?
    {
        match serde_json::from_value::<Status>(row["status"].clone())? {
            Status::Submitted => anyhow::bail!("restart_write_pending"),
            Status::Unknown => {
                let key = serde_json::from_value(row["key"].clone())?;
                let fingerprint = serde_json::from_value(row["fingerprint"].clone())?;
                ensure!(
                    keys.insert(key, fingerprint).is_none(),
                    "restart_ledger_unverified"
                );
            }
            _ => {}
        }
    }
    ensure!(
        questions["records"]
            .as_array()
            .is_some_and(|items| items.iter().all(|q| matches!(
                q["phase"].as_str(),
                Some("confirmed" | "answered_elsewhere" | "cancelled")
            ))),
        "restart_question_pending"
    );
    Ok(keys)
}
fn fenced_only_unknown(r: &Value, n: u64) -> bool {
    n > 0
        && r["phase"] == "draining"
        && r["checkpoint"].is_null()
        && r["acceptingWrites"] == false
        && r["shutdownRequested"] == false
        && r["remoteEnabled"] == false
        && r["unconfirmedWrites"] == n
        && r["nativeTasksPreserved"] == true
        && r["blockers"] == json!(["unconfirmed_writes"])
}
fn manual_fence_ready(r: &Value, h: &Value, unknown: u64, now: u64) -> bool {
    let ready = fenced_only_unknown(r,unknown) || {
        r["phase"]=="ready" && r["safeToExit"]==true && r["checkpoint"].is_object()
            && r["unconfirmedWrites"]==0 && r["blockers"]==json!([])
            && r["nativeTasksPreserved"]==true && r["acceptingWrites"]==false
            && r["shutdownRequested"]==false && r["remoteEnabled"]==false
    };
    // The broker's fenced handoff inventories process ownership and confirms
    // external tasks survive disconnect. Unlike idle polling, this proof does
    // not require independent Codex tasks to end before Pocket can restart.
    ready && h["protocol"]==1 && h["acceptingWrites"]==false
        && h["handoffCapabilities"]["unconfirmedWrites"]==0
        && crate::connection::fresh(h["updatedAt"].as_u64().unwrap_or(0),now)
        && ["pendingWrites","pendingQuestions"].iter().all(|k|h["lifecycle"][k]==0)
}
fn reconciliation_fence_ready(manual: bool, r: &Value, h: &Value, now: u64) -> bool {
    manual
        && r["phase"] == "draining" && r["safeToExit"] == false
        && r["checkpoint"].is_null() && r["acceptingWrites"] == false
        && r["shutdownRequested"] == false && r["remoteEnabled"] == false
        && r["unconfirmedWrites"] == 0 && r["nativeTasksPreserved"] == false
        // The independent authenticated Host proof below supplies the exact
        // identity and inventory that an old broker lost with its subscription.
        // No other blocker (including an in-flight write) is covered by it.
        && r["blockers"].as_array().is_some_and(|items|!items.is_empty() && items.iter().all(|b|
            matches!(b.as_str(),Some("reconciliation_required"|"runtime_identity_unverified"|"runtime_inventory_unverified"|"runtime_lifecycle_unverified"))))
        && h["protocol"] == 1 && h["acceptingWrites"] == false
        && h["handoffCapabilities"]["writeFence"] == true
        && h["handoffCapabilities"]["preservesIdentity"] == true
        && h["handoffCapabilities"]["unconfirmedWrites"] == 0
        && crate::connection::fresh(h["updatedAt"].as_u64().unwrap_or(0), now)
        && ["pendingWrites", "pendingQuestions"].iter().all(|k| h["lifecycle"][k] == 0)
}
fn persisted_unknowns(state: &Path) -> Result<BTreeMap<CommandKey, [u8; 32]>> {
    tombstones(&private_json(&state.join("commands.json"))?,
        &private_json(&state.join("question-receipts.json"))?)
}
fn drained_fence_sample(mut sample:(Value,Value),mut refresh:impl FnMut()->Result<(Value,Value)>,budget:Duration)->Result<(Value,Value)> {
    let until=std::time::Instant::now()+budget;
    loop {
        let (r,h)=&sample;
        let busy=r["unconfirmedWrites"].as_u64().is_some_and(|n|n>0)
            ||h["lifecycle"]["pendingWrites"].as_u64().is_some_and(|n|n>0)
            ||h["handoffCapabilities"]["unconfirmedWrites"].as_u64().is_some_and(|n|n>0);
        // A passive status reader in older helpers holds the write serializer.
        // Wait only within this still-closed fence; never promote a busy sample.
        if !busy || !matches!(r["phase"].as_str(),Some("draining"|"ready"))
            ||r["acceptingWrites"]!=false||h["acceptingWrites"]!=false
            ||r["shutdownRequested"]!=false||std::time::Instant::now()>=until {break;}
        std::thread::sleep(Duration::from_millis(250));
        sample=refresh()?;
    }
    Ok(sample)
}
fn unchanged_unknowns(i: &Intent, receipts: &BTreeMap<CommandKey, [u8; 32]>) -> bool {
    receipts.len() as u64 == i.unknown && i.unknown_receipts.as_ref().is_none_or(|saved|
        saved.len() == receipts.len() && saved.iter().map(|(k,v)| (k,v)).eq(receipts.iter()))
}
fn bound_fence(i: &Intent, r: &Value, h: &Value) -> bool {
    r["protocol"] == 1 && r["id"] == i.fence && r["generation"] == i.run
        && h["acceptingWrites"] == false
        && h["handoffCapabilities"]["generation"] == i.run
        && h["handoffCapabilities"]["unconfirmedWrites"] == 0
        && h["helperRuntime"]["processIdentity"]["createdAt"] == i.agent.created_at
        && h["helperRuntime"]["pid"] == i.agent.pid
}
// Returns whether the additional independent-Host proof is mandatory. A saved
// stop authorization using that proof must pass it again after a coordinator crash.
fn preservation_required(i: &Intent, r: &Value, h: &Value,
    receipts: &BTreeMap<CommandKey, [u8; 32]>, now: u64) -> Result<bool> {
    ensure!(bound_fence(i,r,h) && unchanged_unknowns(i,receipts), "restart_activity_unverified");
    if i.preservation.is_some() {
        ensure!(i.unknown_receipts.is_some() && reconciliation_fence_ready(i.manual_restart,r,h,now),
            "restart_activity_unverified");
        return Ok(true);
    }
    let counts=&h["lifecycle"];
    let ordinary=if i.manual_restart { manual_fence_ready(r,h,i.unknown,now) } else {
        fenced_only_unknown(r,i.unknown) && counts["nativeTasksPreserved"]==true
            && counts["unknownOperations"]==i.unknown
            && crate::connection::fresh(counts["observedAt"].as_u64().unwrap_or(0),now)
            && ["activeTurns","pendingWrites","pendingQuestions"].iter().all(|k|counts[k]==0)
    };
    if ordinary { return Ok(false) }
    ensure!(i.unknown_receipts.is_some() && reconciliation_fence_ready(i.manual_restart,r,h,now),
        "restart_activity_unverified");
    Ok(true)
}
fn receipt_path(state: &Path) -> PathBuf {
    state.join("handoff/state.json")
}
// Offline equivalent of upgrade/cancel for exactly our own unfinished fence.
// The caller holds all three owner locks. No user operation receipt is edited.
fn cancel_offline(state: &Path, intent: &Intent) -> Result<()> {
    let path = receipt_path(state);
    let mut disk = private_json(&path)?;
    ensure!(
        disk["protocol"] == 1 && disk["generation"] == intent.run,
        "restart_fence_changed"
    );
    let Some(op) = disk["operations"].get_mut(&intent.fence) else {
        return Ok(());
    };
    let remote = op["priorRemote"]
        .as_bool()
        .context("restart_fence_changed")?;
    ensure!(op["releaseInput"].is_null(), "restart_fence_changed");
    let r = &mut op["receipt"];
    ensure!(
        r["protocol"] == 1
            && r["id"] == intent.fence
            && r["generation"] == intent.run
            && r["shutdownRequested"] == false,
        "restart_fence_changed"
    );
    if r["phase"] == "cancelled" {
        return Ok(());
    }
    ensure!(
        matches!(r["phase"].as_str(), Some("draining" | "failed" | "ready")),
        "restart_fence_changed"
    );
    r["phase"] = json!("cancelled");
    r["safeToExit"] = json!(false);
    r["acceptingWrites"] = json!(true);
    r["remoteEnabled"] = json!(remote);
    storage::atomic(&path, &disk)?;
    ensure!(private_json(&path)? == disk, "restart_fence_changed");
    Ok(())
}
fn pid(v: &Value) -> Result<u32> {
    v.as_u64()
        .and_then(|v| u32::try_from(v).ok())
        .context("restart_owner_changed")
}
pub(super) fn verify_candidate(bin: &Path, state: &Path, s: &updates::UpdateStatus) -> Result<PathBuf> {
    let target = s
        .generation
        .as_deref()
        .context("restart_candidate_changed")?;
    ensure!(
        target.len() == 64 && target.bytes().all(|b| b.is_ascii_hexdigit()),
        "restart_candidate_changed"
    );
    let policy = updates::trust(bin, state)?;
    let mut bytes = Vec::new();
    storage::open_physical(&updates::directory(state).join("offer.json"))?
        .take(updates::MAX_OFFER + 1)
        .read_to_end(&mut bytes)?;
    let m: Value = storage::read(&bin.join("windows-manifest.json"))?;
    let offer = updates::verify_offer_for(
        &policy,
        &bytes,
        m["version"].as_str().context("restart_candidate_changed")?,
        storage::now(),
        s.explicit_install,
    )?;
    let next = bin
        .parent()
        .context("restart_candidate_changed")?
        .join(target);
    ensure!(
        offer.generation == target
            && s.version.as_deref() == Some(&offer.version)
            && crate::installation::generation(&next)? == target,
        "restart_candidate_changed"
    );
    upgrade::local::verify_generation_inventory(&next)?;
    Ok(next)
}
fn intent_path(state: &Path, op: &str) -> PathBuf {
    updates::directory(state).join(format!("restart-{op}.json"))
}
pub(super) fn failure_stage(msg: &str) -> Option<&'static str> {
    [
        "restart_candidate_changed",
        "restart_owner_changed",
        "restart_fence_changed",
        "restart_activity_unverified",
        "restart_stop_failed",
        "restart_ledger_unverified",
        "restart_fence_unavailable",
    ]
    .into_iter()
    .find(|s| *s == msg)
}
pub(super) fn advance(root: &Path, state: &Path, operation: &str) -> Result<()> {
    let _worker = storage::Lock::acquire(&updates::directory(state).join("worker.lock"))?;
    let _install = updates::InstallMutex::acquire(root)?;
    let _upgrade = storage::Lock::acquire(&root.join("control/upgrade.lock"))?;
    let Some(mut s) = current(state, operation)? else {
        return Ok(());
    };
    // A signed explicit installer carries the same Pocket-only restart
    // authorization as the button. Neither grants control of native hosts.
    let manual_restart=apply_requested(state,&s);
    if !apply_requested(state,&s)
        || !(matches!(s.phase.as_str(), "waiting_cold_start" | "restarting_legacy")
            || manual_restart && matches!(s.phase.as_str(),"staged"|"waiting_idle"))
        || !other_journals_clear(root, state)?
    {
        return Ok(());
    }
    let (bin, recorded) = launcher::resolve_release(root)?;
    ensure!(recorded == state, "restart_owner_changed");
    let from = crate::installation::generation(&bin)?;
    let path = intent_path(state, operation);
    if s.phase != "restarting_legacy" {
        if cold_owner_locks(root, state)?.is_some()
            || !upgrade::local::cold_agent_boot_allowed(state)?
        {
            return Ok(());
        }
        let h = crate::native_update::authenticated_helper(root, state, &from)?;
        if !(updates::explicit_cold_required(&h, storage::now()) || manual_restart
            && h["protocol"]==1 && h["acceptingWrites"]==true
            && h["handoffCapabilities"]["writeFence"]==true
            && h["handoffCapabilities"]["preservesIdentity"]==true
            && h["handoffCapabilities"]["unconfirmedWrites"]==0
            && ["pendingWrites","pendingQuestions"].iter().all(|k| h["lifecycle"][k]==0)) {
            return Ok(());
        }
        verify_candidate(&bin, state, &s)?;
        let sup: Value = storage::read(&storage::control(state).join("supervisor.json"))?;
        let agent = ProcessIdentity::capture(pid(&h["helperRuntime"]["pid"])?)?;
        let supervisor = ProcessIdentity::capture(pid(&sup["pid"])?)?;
        ensure!(
            sup["childPid"] == agent.pid
                && sup["runtime"]["generation"] == from
                && sup["runtime"]["processCreatedAt"] == supervisor.created_at
                && supervisor.executable == launcher::verified_exe(&bin, "pocket-supervisor.exe")?
                && agent.executable == launcher::verified_exe(&bin, "pocket-agent.exe")?,
            "restart_owner_changed"
        );
        let run = h["handoffCapabilities"]["generation"]
            .as_str()
            .filter(|v| upgrade::local::canonical_uuid(v))
            .context("restart_fence_unavailable")?;
        let unknown_receipts = if manual_restart {
            Some(persisted_unknowns(state)?.into_iter().collect::<Vec<_>>())
        } else { None };
        let n = if let Some(receipts)=&unknown_receipts { receipts.len() as u64 }
            else {h["lifecycle"]["unknownOperations"].as_u64().context("restart_activity_unverified")?};
        let intent = Intent {
            operation: operation.into(),
            fence: uuid::Uuid::new_v4().to_string(),
            run: run.into(),
            from: from.clone(),
            to: s.generation.clone().unwrap(),
            supervisor,
            agent,
            unknown: n,
            stop_authorized: false,
            manual_restart,
            unknown_receipts,
            preservation: None,
        };
        storage::atomic(&path, &intent)?;
        updates::save(state, &mut s, "restarting_legacy")?;
    }
    let mut i: Intent = storage::read(&path)?;
    ensure!(
        i.operation == operation
            && i.from == from
            && Some(&i.to) == s.generation.as_ref()
            && upgrade::local::canonical_uuid(&i.run)
            && upgrade::local::canonical_uuid(&i.fence)
            && i.supervisor.executable == bin.join("pocket-supervisor.exe")
            && i.agent.executable == bin.join("pocket-agent.exe"),
        "restart_owner_changed"
    );
    if let Some(_owners) = cold_owner_locks(root, state)? {
        cancel_offline(state, &i)?;
        ensure!(
            upgrade::local::cold_agent_boot_allowed(state)?,
            "restart_fence_changed"
        );
        // Keep startup on the coordinator path until the signed cold boot commits.
        updates::save(state, &mut s, "waiting_cold_start")?;
        return Ok(());
    }
    if !i.stop_authorized || i.preservation.is_some() {
        let proof = (|| -> Result<Option<preservation::Proof>> {
            let method=if i.stop_authorized {"upgrade/status"} else {"upgrade/quiesce"};
            let mut r=upgrade::local::legacy_gate(state,method,&i.fence,&i.run)
                .context("restart_fence_unavailable")?;
            // Quiesce closes new writes immediately, but an already-held broker
            // serializer can transiently report one pending write. Read the same
            // operation while it drains; never reissue quiesce or ignore the count.
            let until=std::time::Instant::now()+Duration::from_secs(10);
            while r["phase"]=="draining" && r["unconfirmedWrites"].as_u64().is_some_and(|n|n>0)
                && std::time::Instant::now()<until {
                std::thread::sleep(Duration::from_millis(250));
                r=upgrade::local::legacy_gate(state,"upgrade/status",&i.fence,&i.run)?;
            }
            let receipts=persisted_unknowns(state)?;
            // Large persisted command results must not age the live sample.
            let h=crate::native_update::authenticated_helper(root,state,&from)?;
            let (r,h)=drained_fence_sample((r,h),||Ok((
                upgrade::local::legacy_gate(state,"upgrade/status",&i.fence,&i.run)?,
                crate::native_update::authenticated_helper(root,state,&from)?)),Duration::from_secs(10))?;
            if !preservation_required(&i,&r,&h,&receipts,storage::now())? { return Ok(None) }
            ensure!(apply_requested(state,&s), "restart_activity_unverified");
            let proof=preservation::prove(root,state,&h,[&i.supervisor,&i.agent])?;
            if let Some(saved)=&i.preservation { saved.require_retained(&proof)?; }
            // IPC and image checks can take time. Re-read the actual fence,
            // ledger and authenticated inventory before committing the stop.
            let receipts=persisted_unknowns(state)?;
            let r=upgrade::local::legacy_gate(state,"upgrade/status",&i.fence,&i.run)?;
            let h=crate::native_update::authenticated_helper(root,state,&from)?;
            let (r,h)=drained_fence_sample((r,h),||Ok((
                upgrade::local::legacy_gate(state,"upgrade/status",&i.fence,&i.run)?,
                crate::native_update::authenticated_helper(root,state,&from)?)),Duration::from_secs(10))?;
            ensure!(bound_fence(&i,&r,&h) && unchanged_unknowns(&i,&receipts)
                && reconciliation_fence_ready(i.manual_restart,&r,&h,storage::now()),
                "restart_activity_unverified");
            proof.verify_inventory(state,&h,[&i.supervisor,&i.agent])?;
            Ok(Some(proof))
        })();
        let proof=match proof {Ok(proof)=>proof,Err(error)=> {
            upgrade::local::legacy_gate(state, "upgrade/cancel", &i.fence, &i.run)?;
            let code=error.chain().map(|e|e.to_string()).find(|text|
                (text.starts_with("preservation_") || text.starts_with("restart_")) &&
                    text.bytes().all(|b|b.is_ascii_lowercase() || b==b'_')).unwrap_or("restart_proof_failed".into());
            storage::atomic(&updates::directory(state).join(format!("restart-failure-{operation}.json")),
                &json!({"operationId":operation,"reason":code,"at":storage::now()}))?;
            // The exact fence has been cancelled and the original broker is usable.
            // Do not fence it repeatedly for the same incompatible snapshot.
            s.failure = Some("legacy_restart_unverified".into());
            updates::save(state, &mut s, "failed")?;
            return Ok(());
        }};
        i.preservation=proof;
        i.stop_authorized = true;
        storage::atomic(&path, &i)?;
    }
    // Stop the respawner first. Never traverse child processes or stop the host.
    for owner in [&i.supervisor, &i.agent] {
        stop_owner(owner)?;
    }
    let _owners = cold_owner_locks(root, state)?.context("restart_owner_changed")?;
    cancel_offline(state, &i)?;
    ensure!(
        upgrade::local::cold_agent_boot_allowed(state)?,
        "restart_fence_changed"
    );
    updates::save(state, &mut s, "waiting_cold_start")
}
#[cfg(windows)]
fn stop_owner(owner: &ProcessIdentity) -> Result<()> {
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
    use windows_sys::Win32::{
        Foundation::*,
        System::{RemoteDesktop::ProcessIdToSessionId, Threading::*},
    };
    if maintenance::presence(owner) == Presence::Gone {
        return Ok(());
    }
    unsafe {
        let handle = OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE | PROCESS_TERMINATE,
            0,
            owner.pid,
        );
        ensure!(!handle.is_null(), "restart_stop_failed");
        let pinned = OwnedHandle::from_raw_handle(handle);
        let mut session = 0;
        let mut own_session = 0;
        ensure!(
            ProcessIdToSessionId(owner.pid, &mut session) != 0
                && ProcessIdToSessionId(std::process::id(), &mut own_session) != 0
                && session == own_session
                && ProcessIdentity::capture(owner.pid)? == *owner,
            "restart_owner_changed"
        );
        ensure!(
            TerminateProcess(pinned.as_raw_handle(), 0) != 0
                || WaitForSingleObject(handle, 0) == WAIT_OBJECT_0,
            "restart_stop_failed"
        );
        ensure!(
            WaitForSingleObject(handle, 5000) == WAIT_OBJECT_0,
            "restart_stop_failed"
        );
    }
    Ok(())
}
#[cfg(not(windows))]
fn stop_owner(_: &ProcessIdentity) -> Result<()> {
    anyhow::bail!("Windows process restart required")
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn missing_adapter_lifecycle_requires_the_independent_preservation_proof() {
        let (mut r,h)=reconciliation_fixture();
        r["blockers"]=json!(["reconciliation_required","runtime_inventory_unverified","runtime_lifecycle_unverified"]);
        assert!(reconciliation_fence_ready(true,&r,&h,100));
        r["blockers"]=json!(["runtime_lifecycle_unverified","pending_questions"]);
        assert!(!reconciliation_fence_ready(true,&r,&h,100));
    }
    #[test]
    fn final_fence_rechecks_transient_reader_contention_and_keeps_busy_writes() {
        let (r,mut h)=reconciliation_fixture();
        h["lifecycle"]["pendingWrites"]=json!(1);
        let mut calls=0;
        let mut ready=h.clone();ready["lifecycle"]["pendingWrites"]=json!(0);
        let sampled=drained_fence_sample((r.clone(),h.clone()),||{calls+=1;Ok((r.clone(),ready.clone()))},Duration::from_secs(1)).unwrap();
        assert_eq!(calls,1);
        assert!(reconciliation_fence_ready(true,&sampled.0,&sampled.1,100));
        let sampled=drained_fence_sample((r.clone(),h.clone()),||panic!("Zero budget cannot retry"),Duration::ZERO).unwrap();
        assert!(!reconciliation_fence_ready(true,&sampled.0,&sampled.1,100));
        let sampled=drained_fence_sample((r.clone(),ready.clone()),||panic!("A drained sample needs no retry"),Duration::from_secs(1)).unwrap();
        assert!(reconciliation_fence_ready(true,&sampled.0,&sampled.1,100));
        let mut changed=r.clone();changed["acceptingWrites"]=json!(true);
        let sampled=drained_fence_sample((changed,h),||panic!("An open fence must not wait"),Duration::from_secs(1)).unwrap();
        assert!(!reconciliation_fence_ready(true,&sampled.0,&sampled.1,100));
    }
    #[cfg(windows)]
    #[test]
    #[ignore="explicit bounded production fence proof; always cancels, never stops processes"]
    fn live_update_fence_proof_without_stopping() -> Result<()> {
        let root=PathBuf::from(std::env::var_os("POCKET_QA_PRESERVATION_ROOT").context("select root")?);
        let (bin,state)=launcher::resolve_release(&root)?;
        let from=crate::installation::generation(&bin)?;
        let h=crate::native_update::authenticated_helper(&root,&state,&from)?;
        let run=h["handoffCapabilities"]["generation"].as_str().context("generation")?.to_owned();
        let fence=uuid::Uuid::new_v4().to_string();
        let before=persisted_unknowns(&state)?;
        let outcome=(||->Result<()> {
            let mut r=upgrade::local::legacy_gate(&state,"upgrade/quiesce",&fence,&run)?;
            let until=std::time::Instant::now()+Duration::from_secs(10);
            while r["unconfirmedWrites"].as_u64().is_some_and(|v|v>0) && std::time::Instant::now()<until {
                std::thread::sleep(Duration::from_millis(250));
                r=upgrade::local::legacy_gate(&state,"upgrade/status",&fence,&run)?;
            }
            println!("fence_phase={} blockers={} in_flight={}",r["phase"],r["blockers"],r["unconfirmedWrites"]);
            let h=crate::native_update::authenticated_helper(&root,&state,&from)?;
            let (r,h)=drained_fence_sample((r,h),||Ok((
                upgrade::local::legacy_gate(&state,"upgrade/status",&fence,&run)?,
                crate::native_update::authenticated_helper(&root,&state,&from)?)),Duration::from_secs(10))?;
            println!("fence_evidence={}",json!({"phase":r["phase"],"safeToExit":r["safeToExit"],"hasCheckpoint":r["checkpoint"].is_object(),
                "acceptingWrites":r["acceptingWrites"],"remoteEnabled":r["remoteEnabled"],"shutdownRequested":r["shutdownRequested"],
                "nativeTasksPreserved":r["nativeTasksPreserved"],"unconfirmedWrites":r["unconfirmedWrites"],"helperAcceptingWrites":h["acceptingWrites"],
                "fresh":crate::connection::fresh(h["updatedAt"].as_u64().unwrap_or(0),storage::now()),"lifecycle":h["lifecycle"],
                "helperUnconfirmedWrites":h["handoffCapabilities"]["unconfirmedWrites"],"unknownUnchanged":before==persisted_unknowns(&state)?}));
            ensure!(reconciliation_fence_ready(true,&r,&h,storage::now()),"restart_activity_unverified");
            let sup:Value=storage::read(&storage::control(&state).join("supervisor.json"))?;
            let supervisor=ProcessIdentity::capture(pid(&sup["pid"])?)?;
            let agent=ProcessIdentity::capture(pid(&h["helperRuntime"]["pid"])?)?;
            let mut intent=Intent{operation:uuid::Uuid::new_v4().to_string(),fence:fence.clone(),run:run.clone(),from:from.clone(),to:from.clone(),
                supervisor:supervisor.clone(),agent:agent.clone(),unknown:before.len() as u64,stop_authorized:false,manual_restart:true,
                unknown_receipts:Some(before.clone().into_iter().collect()),preservation:None};
            if let Some(path)=std::env::var_os("POCKET_QA_RESTART_INTENT") {
                let failed:Intent=storage::read(Path::new(&path))?;
                println!("previous_intent={}",json!({"sameRun":failed.run==run,"sameAgent":failed.agent==agent,
                    "unknownUnchanged":unchanged_unknowns(&failed,&before),"manual":failed.manual_restart}));
                intent.unknown=failed.unknown;intent.unknown_receipts=failed.unknown_receipts;
            }
            ensure!(preservation_required(&intent,&r,&h,&before,storage::now())?,"expected independent proof");
            let proof=preservation::prove(&root,&state,&h,[&supervisor,&agent])?;
            let receipts=persisted_unknowns(&state)?;
            let r=upgrade::local::legacy_gate(&state,"upgrade/status",&fence,&run)?;
            let h=crate::native_update::authenticated_helper(&root,&state,&from)?;
            let (r,h)=drained_fence_sample((r,h),||Ok((
                upgrade::local::legacy_gate(&state,"upgrade/status",&fence,&run)?,
                crate::native_update::authenticated_helper(&root,&state,&from)?)),Duration::from_secs(10))?;
            println!("final_fence={}",json!({"bound":bound_fence(&intent,&r,&h),"unchanged":unchanged_unknowns(&intent,&receipts),
                "ready":reconciliation_fence_ready(true,&r,&h,storage::now()),"phase":r["phase"],"blockers":r["blockers"],
                "inFlight":r["unconfirmedWrites"],"helperInFlight":h["handoffCapabilities"]["unconfirmedWrites"],
                "pendingWrites":h["lifecycle"]["pendingWrites"],"pendingQuestions":h["lifecycle"]["pendingQuestions"]}));
            ensure!(bound_fence(&intent,&r,&h)&&unchanged_unknowns(&intent,&receipts)&&reconciliation_fence_ready(true,&r,&h,storage::now()),"restart_activity_unverified");
            proof.verify_inventory(&state,&h,[&supervisor,&agent])?;
            ensure!(before==persisted_unknowns(&state)?,"unknown_changed");
            Ok(())
        })();
        upgrade::local::legacy_gate(&state,"upgrade/cancel",&fence,&run)?;
        outcome?;
        println!("fence_proof=passed cancelled=true processes_stopped=0");Ok(())
    }
    fn intent_fixture() -> Intent {
        let owner=|pid| ProcessIdentity{pid,created_at:pid as u64,executable:PathBuf::from("owner.exe")};
        Intent{operation:"operation".into(),fence:"fence".into(),run:"run".into(),from:"old".into(),to:"new".into(),
            supervisor:owner(1),agent:owner(2),unknown:1,stop_authorized:false,manual_restart:true,
            unknown_receipts:Some(vec![(CommandKey{caller_id:"caller".into(),command_id:"unknown".into()},[1;32])]),
            preservation:None}
    }
    #[test]
    fn reconciliation_admission_binds_original_unknowns_owner_and_fence() {
        let mut i=intent_fixture();
        let (mut r,mut h)=reconciliation_fixture();
        r["protocol"]=json!(1); r["id"]=json!(i.fence); r["generation"]=json!(i.run);
        h["handoffCapabilities"]["generation"]=json!(i.run);
        h["helperRuntime"]=json!({"pid":2,"processIdentity":{"createdAt":2}});
        let receipts=i.unknown_receipts.clone().unwrap().into_iter().collect::<BTreeMap<_,_>>();
        assert!(preservation_required(&i,&r,&h,&receipts,100).unwrap());
        let saved=serde_json::to_vec(&i).unwrap();
        let loaded:Intent=serde_json::from_slice(&saved).unwrap();
        assert!(unchanged_unknowns(&loaded,&receipts));
        for fault in ["unknown_id","fingerprint","count","fence","generation","helper_pid","helper_creation"] {
            let mut receipts=receipts.clone();let mut r=r.clone();let mut h=h.clone();
            match fault {
                "unknown_id"=>{let (_,fingerprint)=receipts.pop_first().unwrap();receipts.insert(CommandKey{caller_id:"caller".into(),command_id:"replacement".into()},fingerprint);},
                "fingerprint"=>{*receipts.values_mut().next().unwrap()=[2;32];},
                "count"=>receipts.clear(),
                "fence"=>r["id"]=json!("other"),
                "generation"=>h["handoffCapabilities"]["generation"]=json!("other"),
                "helper_pid"=>h["helperRuntime"]["pid"]=json!(3),
                "helper_creation"=>h["helperRuntime"]["processIdentity"]["createdAt"]=json!(3),
                _=>unreachable!(),
            }
            assert!(preservation_required(&i,&r,&h,&receipts,100).is_err(),"{fault}");
        }
        i.manual_restart=false;
        assert!(preservation_required(&i,&r,&h,&receipts,100).is_err());
        i.manual_restart=true;i.unknown_receipts=None;
        assert!(preservation_required(&i,&r,&h,&receipts,100).is_err(),"old count-only intents cannot acquire this exception");
    }
    fn reconciliation_fixture() -> (Value, Value) {
        (json!({"phase":"draining","safeToExit":false,"checkpoint":null,"acceptingWrites":false,
            "shutdownRequested":false,"remoteEnabled":false,"unconfirmedWrites":0,
            "nativeTasksPreserved":false,"blockers":["reconciliation_required"]}),
         json!({"protocol":1,"updatedAt":100,"acceptingWrites":false,
            "handoffCapabilities":{"writeFence":true,"preservesIdentity":true,"unconfirmedWrites":0},
            "lifecycle":{"pendingWrites":0,"pendingQuestions":0,"activeTurns":null,"nativeTasksPreserved":false}}))
    }
    #[test]
    fn explicit_reconciliation_restart_can_proceed_to_independent_host_proof() {
        let (r,h)=reconciliation_fixture();
        assert!(!manual_fence_ready(&r,&h,6,100),"ordinary checkpoint rules stay unchanged");
        assert!(reconciliation_fence_ready(true,&r,&h,100),"RC19 reconciliation-only fence needs independent preservation proof");
    }
    #[test]
    fn disconnected_host_inventory_requires_independent_proof_without_inflight_writes() {
        let (mut r,h)=reconciliation_fixture();
        r["blockers"]=json!(["runtime_identity_unverified","runtime_inventory_unverified"]);
        assert!(reconciliation_fence_ready(true,&r,&h,100));
        r["unconfirmedWrites"]=json!(1);
        assert!(!reconciliation_fence_ready(true,&r,&h,100));
        r["unconfirmedWrites"]=json!(0);
        r["blockers"]=json!(["runtime_lifecycle_unverified"]);
        assert!(reconciliation_fence_ready(true,&r,&h,100));
    }
    #[test]
    fn reconciliation_restart_rejects_automatic_open_or_busy_fences() {
        let (r,h)=reconciliation_fixture();
        assert!(!reconciliation_fence_ready(false,&r,&h,100));
        assert!(!reconciliation_fence_ready(true,&r,&h,120));
        for (field,value) in [("acceptingWrites",json!(true)),("shutdownRequested",json!(true)),
            ("remoteEnabled",json!(true)),("unconfirmedWrites",json!(1)),
            ("blockers",json!(["reconciliation_required","checkpoint_failed"])),("phase",json!("failed"))] {
            let mut invalid=r.clone();invalid[field]=value;
            assert!(!reconciliation_fence_ready(true,&invalid,&h,100),"{field}");
        }
        for field in ["pendingWrites","pendingQuestions"] {
            let mut invalid=h.clone();invalid["lifecycle"][field]=json!(1);
            assert!(!reconciliation_fence_ready(true,&r,&invalid,100),"{field}");
        }
        let mut invalid=h.clone();invalid["handoffCapabilities"]["unconfirmedWrites"]=json!(1);
        assert!(!reconciliation_fence_ready(true,&r,&invalid,100));
        invalid=h.clone();invalid["acceptingWrites"]=json!(true);
        assert!(!reconciliation_fence_ready(true,&r,&invalid,100));
    }
    #[test]
    fn manual_restart_accepts_unknown_receipts_already_saved_in_a_ready_checkpoint() {
        let mut r=json!({"phase":"ready","safeToExit":true,"checkpoint":{"id":"checkpoint","generation":"run","hash":"saved"},"acceptingWrites":false,"shutdownRequested":false,"remoteEnabled":false,"unconfirmedWrites":0,"nativeTasksPreserved":true,"blockers":[]});
        let h=json!({"protocol":1,"updatedAt":100,"acceptingWrites":false,"handoffCapabilities":{"unconfirmedWrites":0},"lifecycle":{"pendingWrites":0,"pendingQuestions":0,"activeTurns":null,"unknownOperations":null,"nativeTasksPreserved":false}});
        assert!(manual_fence_ready(&r,&h,6,100),"RC11 saved all six Unknown receipts in its ready checkpoint");
        r["checkpoint"]=Value::Null;assert!(!manual_fence_ready(&r,&h,6,100));
        r["checkpoint"]=json!({"id":"checkpoint"});r["unconfirmedWrites"]=json!(1);
        assert!(!manual_fence_ready(&r,&h,6,100));
    }
    #[test]
    fn manual_restart_requires_fenced_preserved_tasks_and_no_inflight_writes() {
        let mut r=json!({"phase":"draining","checkpoint":null,"acceptingWrites":false,"shutdownRequested":false,"remoteEnabled":false,"unconfirmedWrites":6,"nativeTasksPreserved":true,"blockers":["unconfirmed_writes"]});
        let mut h=json!({"protocol":1,"updatedAt":100,"acceptingWrites":false,"handoffCapabilities":{"unconfirmedWrites":0},"lifecycle":{"pendingWrites":0,"pendingQuestions":0,"activeTurns":null,"nativeTasksPreserved":false,"reason":"runtime_activity_unverified"}});
        assert!(manual_fence_ready(&r,&h,6,100));
        assert!(!manual_fence_ready(&r,&h,5,100));
        assert!(!manual_fence_ready(&r,&h,6,120));
        r["nativeTasksPreserved"]=json!(false);assert!(!manual_fence_ready(&r,&h,6,100));
        r["nativeTasksPreserved"]=json!(true);
        for field in ["pendingWrites","pendingQuestions"] {
            h["lifecycle"][field]=json!(1);assert!(!manual_fence_ready(&r,&h,6,100));h["lifecycle"][field]=json!(0);
        }
        h["handoffCapabilities"]["unconfirmedWrites"]=json!(1);assert!(!manual_fence_ready(&r,&h,6,100));
    }
    #[test]
    fn reads_large_legacy_ledger_in_blocks() {
        use std::io::Cursor;
        struct Count<R>(R, usize);
        impl<R: Read> Read for Count<R> {
            fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
                self.1 += 1;
                self.0.read(bytes)
            }
        }
        let doc = json!({"result":"a".repeat(3_629_081)});
        let mut input = Count(Cursor::new(serde_json::to_vec(&doc).unwrap()), 0);
        assert_eq!(read_document(&mut input).unwrap(), doc);
        assert!(
            input.1 < 1024,
            "one-byte Windows file reads made live status expire"
        );
    }
    #[test]
    #[ignore = "read-only timing probe of an explicitly selected legacy ledger"]
    fn legacy_ledger_read_latency() {
        let state=PathBuf::from(std::env::var_os("POCKET_QA_LEDGER_STATE").unwrap());
        let p=state.join("commands.json");
        let before=std::time::Instant::now();
        let legacy:Value=serde_json::from_reader(maintenance::open_agent_private(&p).unwrap()).unwrap();
        let slow=before.elapsed(); let before=std::time::Instant::now();
        let buffered=private_json(&p).unwrap();
        let fast=before.elapsed();
        let q=private_json(&state.join("question-receipts.json")).unwrap();
        assert_eq!(tombstones(&legacy,&q).unwrap(),tombstones(&buffered,&q).unwrap());
        println!("legacy={slow:?} buffered={fast:?} unknown={}",tombstones(&buffered,&q).unwrap().len());
    }
    #[test]
    fn permits_only_persisted_tombstones() {
        let mut row = json!({"key":{"caller_id":"caller","command_id":"command"},"status":"Unknown","fingerprint":vec![1;32]});
        let q = json!({"records":[]});
        let ledger = |row: &Value| json!({"version":1,"entries":[row]});
        assert_eq!(tombstones(&ledger(&row), &q).unwrap().len(), 1);
        assert!(tombstones(&ledger(&row), &json!({"records":[{"phase":"dispatching"}]})).is_err());
        row["status"] = json!("Submitted");
        assert!(tombstones(&ledger(&row), &q).is_err());
        let mut r = json!({"phase":"draining","remoteEnabled":false,"acceptingWrites":false,"checkpoint":null,"shutdownRequested":false,"nativeTasksPreserved":true,"unconfirmedWrites":2,"blockers":["unconfirmed_writes"]});
        assert!(fenced_only_unknown(&r, 2));
        assert!(!fenced_only_unknown(&r, 1));
        r["blockers"] = json!(["reconciliation_required", "unconfirmed_writes"]);
        assert!(!fenced_only_unknown(&r, 2));
    }
}
