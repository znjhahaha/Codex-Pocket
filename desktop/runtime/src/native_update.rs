//! Post-checkpoint native restart. A sampled idle state is never a stop permit.
use crate::{
    storage,
    supervisor::{maintenance::ProcessIdentity, upgrade},
};
use anyhow::{ensure, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    path::{Path, PathBuf},
    time::{Duration, Instant},
};
#[cfg(all(test, windows))]
#[path = "native_update_actual.rs"]
mod actual_tests;
#[cfg(all(test,windows))]
#[path="native_admission_actual.rs"]
mod admission_actual;
#[path = "native_update_io.rs"]
mod io;
#[cfg(test)]
#[path = "native_update_recovery_tests.rs"]
mod recovery_tests;
pub(crate) fn authenticated_helper(root: &Path, state: &Path, generation: &str) -> Result<Value> {
    io::helper(root, state, generation)
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Image {
    pub identity: ProcessIdentity,
    pub sha256: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Plan {
    pub desktop: Image,
    pub bridge: Image,
    pub native: Image,
    pub children: Vec<Image>,
    pub descriptor: PathBuf,
    pub runtime_id: String,
    #[serde(default)]
    pub additional: Vec<Connection>,
    #[serde(default)]
    pub ancestry: Vec<ParentLink>,
}
#[derive(Clone,Debug,PartialEq,Eq,Serialize,Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
pub struct ParentLink {pub child:ProcessIdentity,pub parent:ProcessIdentity}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Connection {
    pub bridge: Image,
    pub native: Image,
    pub descriptor: PathBuf,
    pub runtime_id: String,
}
fn connections(p: &Plan) -> Vec<Plan> {
    let mut primary=p.clone();primary.additional.clear();
    let mut out=vec![primary];
    out.extend(p.additional.iter().map(|c| Plan {
        desktop:p.desktop.clone(),children:p.children.clone(),bridge:c.bridge.clone(),native:c.native.clone(),
        descriptor:c.descriptor.clone(),runtime_id:c.runtime_id.clone(),additional:vec![],ancestry:p.ancestry.clone()
    }));
    out
}

fn lease(status: &Value, operation: &str) -> bool {
    status["protocol"] == 1
        && status["initialized"] == true
        && status["busy"] == false
        && [
            "activeTurns",
            "pendingQuestions",
            "pendingWrites",
            "unknownOperations",
        ]
        .iter()
        .all(|key| status[key].as_u64() == Some(0))
        && status["maintenance"]["operationId"] == operation
        && status["maintenance"]["remainingMs"]
            .as_u64()
            .is_some_and(|n| (10_000..=30_000).contains(&n))
}

trait Backend {
    fn startup_fenced(&self) -> bool {
        false
    }
    fn idle(&mut self) -> Result<bool>;
    fn rpc(&mut self, plan: &Plan, method: &str, params: Value) -> Result<Value>;
    /// Open and retain exact current-user OS handles BEFORE the final lease read.
    fn prepare_stop(&mut self, plan: &Plan) -> Result<()>;
    fn stop(&mut self, plan: &Plan, deadline: Instant) -> Result<()>;
    fn persist_stop_intent(&mut self, plan: &Plan) -> Result<()>;
}

fn fenced_stop(b: &mut impl Backend, plan: &Plan, operation: &str) -> Result<()> {
    ensure!(
        upgrade::local::canonical_uuid(operation),
        "Invalid native operation"
    );
    let startup = b.startup_fenced();
    if !startup {
        ensure!(b.idle()?, "native_idle_unverified");
    }
    let result = (|| {
        // The live adapter closes installation-wide startup admission and
        // retains its exclusive guard before any per-Router proof is taken.
        b.prepare_stop(plan)?;
        for connection in connections(plan) {
        if startup {
            ensure!(
                startup_idle(
                    &b.rpc(&connection, "pocket/managementStatus", json!({}))?,
                    operation
                ),
                "candidate_startup_barrier_unverified"
            );
        } else {
            let q = b.rpc(
                &connection,
                "pocket/maintenance/quiesce",
                json!({"operationId":operation}),
            )?;
            ensure!(
                q["protocol"] == 1
                    && q["operationId"] == operation
                    && q["quiesced"] == true
                    && q["leaseMs"] == 30000,
                "native_barrier_unsupported"
            );
        }
        }
        if !startup {
            ensure!(b.idle()?, "native_became_busy");
        }
        b.persist_stop_intent(plan)?;
        // Renew after disk/identity work, then obtain independent actor evidence.
        if !startup {
            for connection in connections(plan) {
            b.rpc(
                &connection,
                "pocket/maintenance/quiesce",
                json!({"operationId":operation}),
            )?;
            }
        }
        let before = Instant::now();
        for connection in connections(plan) {
        let proof = b.rpc(&connection, "pocket/managementStatus", json!({}))?;
        ensure!(
            if startup {
                startup_idle(&proof, operation)
            } else {
                lease(&proof, operation)
            },
            "native_lease_unverified"
        );
        }
        ensure!(
            before.elapsed() < Duration::from_secs(2),
            "native_lease_stale"
        );
        b.stop(plan, before + Duration::from_secs(5))
    })();
    // A failed partial stop must not leave a surviving bridge fenced forever.
    // The lease also expires if this process crashes before resume.
    if !startup {
        for connection in connections(plan) {
        let _ = b.rpc(
            &connection,
            "pocket/maintenance/resume",
            json!({"operationId":operation}),
        );
        }
    }
    result
}
fn startup_proven(v: &Value, op: &str) -> bool {
    v["startupBarrierProtocol"] == 1
        && v["maintenance"]["startupFenced"] == true
        && v["maintenance"]["operationId"] == op
}
fn startup_idle(v: &Value, op: &str) -> bool {
    startup_proven(v, op)
        && v["protocol"] == 1
        && v["busy"] == false
        && [
            "activeTurns",
            "pendingQuestions",
            "pendingWrites",
            "unknownOperations",
        ]
        .iter()
        .all(|k| v[k].as_u64() == Some(0))
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Journal {
    pub schema: u32,
    pub operation_id: String,
    pub handoff_id: String,
    pub install_root: PathBuf,
    pub state_directory: PathBuf,
    pub from_generation: String,
    pub to_generation: String,
    pub phase: String,
    pub failure: Option<String>,
    pub original: Option<Plan>,
    pub stopping: Option<Plan>,
    pub verified: Option<Plan>,
    pub candidate_record: Value,
    pub loaded_threads: Vec<String>,
}
fn path(state: &Path) -> PathBuf {
    storage::control(state).join("native-update.json")
}
pub fn read_status(state: &Path) -> Result<Option<Journal>> {
    let path = path(state);
    storage::unredirected(&path)?;
    if !path.try_exists()? {
        return Ok(None);
    }
    let j: Journal = storage::read(&path)?;
    ensure!(
        j.schema == 1
            && j.state_directory == state
            && upgrade::local::canonical_uuid(&j.operation_id)
            && upgrade::local::canonical_uuid(&j.handoff_id),
        "native_journal_invalid"
    );
    ensure!(
        [
            "waiting_idle",
            "empty_resuming",
            "stop_intent",
            "stopped",
            "starting",
            "verifying",
            "resuming",
            "rolling_back",
            "rollback_stop_intent",
            "rollback_starting",
            "rollback_verifying",
            "rollback_helper",
            "rollback_resuming",
            "succeeded",
            "rolled_back",
            "recovery_required"
        ]
        .contains(&j.phase.as_str()),
        "native_phase_invalid"
    );
    for g in [&j.from_generation, &j.to_generation] {
        ensure!(
            g.len() == 64
                && g.bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)),
            "native_generation_invalid"
        );
    }
    Ok(Some(j))
}
fn save(j: &mut Journal, phase: &str) -> Result<()> {
    j.phase = phase.into();
    storage::atomic(&path(&j.state_directory), j)
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ReleaseReceipt {
    schema: u32,
    operation_id: String,
    released: bool,
}
fn release_path(j: &Journal) -> Result<PathBuf> {
    ensure!(
        upgrade::local::canonical_uuid(&j.operation_id) && j.state_directory.is_absolute(),
        "Invalid release receipt scope"
    );
    Ok(storage::control(&j.state_directory)
        .join("updates")
        .join(format!("native-release-{}.json", j.operation_id)))
}
fn released(j: &Journal) -> Result<bool> {
    let p = release_path(j)?;
    storage::unredirected(&p)?;
    if !p.try_exists()? {
        return Ok(false);
    }
    use std::io::Read;
    let mut bytes = Vec::new();
    storage::open_physical(&p)?
        .take(4097)
        .read_to_end(&mut bytes)?;
    ensure!(bytes.len() <= 4096, "Invalid native release receipt size");
    let r: ReleaseReceipt = serde_json::from_slice(&bytes)?;
    ensure!(
        r.schema == 1 && r.operation_id == j.operation_id && r.released,
        "Invalid native release receipt"
    );
    Ok(true)
}
fn publish_release(j: &Journal) -> Result<()> {
    ensure!(
        ["resuming", "rollback_resuming"].contains(&j.phase.as_str()) && j.verified.is_some(),
        "Native readiness must be durable before release"
    );
    ensure!(
        read_status(&j.state_directory)?.as_ref() == Some(j),
        "Native readiness journal changed before release"
    );
    if released(j)? {
        return Ok(());
    }
    let p = release_path(j)?;
    storage::private_dir(p.parent().context("Release parent missing")?)?;
    storage::atomic(
        &p,
        &ReleaseReceipt {
            schema: 1,
            operation_id: j.operation_id.clone(),
            released: true,
        },
    )?;
    ensure!(released(j)?, "Release receipt readback failed");
    Ok(())
}

struct Live<'a> {
    j: &'a mut Journal,
    batch: Option<io::Batch>,
    startup: bool,
}
impl Backend for Live<'_> {
    fn startup_fenced(&self) -> bool {
        self.startup
    }
    fn idle(&mut self) -> Result<bool> {
        let h = io::helper(
            &self.j.install_root,
            &self.j.state_directory,
            &self.j.to_generation,
        )?;
        Ok(crate::diagnostics::idle_for_update(&h, storage::now()))
    }
    fn rpc(&mut self, p: &Plan, m: &str, v: Value) -> Result<Value> {
        io::rpc(p, m, v)
    }
    fn prepare_stop(&mut self, p: &Plan) -> Result<()> {
        ensure!(
            io::inventory(
                &self.j.install_root,
                &self.j.state_directory,
                &self.j.to_generation
            )?
            .as_ref()
                == Some(p),
            "native_tree_changed"
        );
        self.batch = Some(io::Batch::open(p)?);
        Ok(())
    }
    fn persist_stop_intent(&mut self, p: &Plan) -> Result<()> {
        self.j.stopping = Some(p.clone());
        let phase = if self.j.phase == "rolling_back" {
            "rollback_stop_intent"
        } else {
            "stop_intent"
        };
        save(self.j, phase)
    }
    fn stop(&mut self, _: &Plan, d: Instant) -> Result<()> {
        self.batch
            .as_ref()
            .context("native_handles_missing")?
            .stop(d)
    }
}
fn originals_gone(p: &Plan) -> bool {
    use crate::supervisor::maintenance::{presence, Presence};
    io::images(p).into_iter()
        .all(|i| matches!(presence(&i.identity), Presence::Gone))
}
fn generation(plan: &Plan) -> Result<String> {
    crate::installation::generation(
        plan.bridge
            .identity
            .executable
            .parent()
            .context("bridge_parent_missing")?,
    )
}
fn verify_started(j: &Journal, g: &str) -> Result<Plan> {
    let mut b = LiveResume::default();
    let h = b.helper(j, &j.to_generation)?;
    let p = b.inventory(j)?.context("native_not_started")?;
    verify_ready_plan(j, g, &h, &p, &mut b)?;
    for c in connections(&p) {ensure!(startup_proven(&b.rpc(&c,"pocket/managementStatus",json!({}))?,&j.operation_id),"candidate_startup_barrier_missing");}
    Ok(p)
}

fn verify_ready_plan(
    j: &Journal,
    generation_expected: &str,
    helper: &Value,
    p: &Plan,
    b: &mut impl ResumeBackend,
) -> Result<()> {
    ensure!(helper["acceptingWrites"] == true, "helper_not_ready");
    for c in connections(p) {ensure!(generation(&c)?==generation_expected,"native_generation_changed");}
    if let Some(old) = &j.original {
        ensure!(
            p.desktop.identity != old.desktop.identity
                && p.bridge.identity != old.bridge.identity
                && p.native.identity != old.native.identity,
            "native_identity_not_fresh"
        );
        ensure!(
            p.desktop.identity.executable == old.desktop.identity.executable
                && p.desktop.sha256 == old.desktop.sha256,
            "desktop_image_changed"
        );
    }
    b.read_ready(p)?;
    for id in &j.loaded_threads {
        let read = b.rpc(
            p,
            "thread/read",
            json!({"threadId":id,"includeTurns":false}),
        )?;
        ensure!(
            read["thread"]["id"] == *id,
            "native_saved_thread_unreadable"
        );
    }
    for c in connections(p) {
    let runtime = helper["runtimes"]
        .as_array()
        .context("helper_inventory_missing")?
        .iter()
        .find(|r| r["id"] == c.runtime_id)
        .context("helper_runtime_missing")?;
    ensure!(
        runtime["online"] == true
            && runtime["initialized"] == true
            && runtime["schemaState"] == "ready"
            && runtime["inventoryReady"] == true,
        "helper_runtime_not_ready"
    );
    }
    for image in io::images(p) {
        b.image_ok(image)?;
    }
    Ok(())
}
fn wait_ready(j: &Journal, g: &str) -> Result<Plan> {
    let deadline = Instant::now() + Duration::from_secs(45);
    loop {
        match verify_started(j, g) {
            Ok(p) => return Ok(p),
            Err(e) => {
                if Instant::now() >= deadline {
                    return Err(e);
                }
            }
        }
        std::thread::sleep(Duration::from_millis(250));
    }
}

/// Called in a detached coordinator, so reverse helper handoff cannot kill the
/// process which is verifying rollback. Supervisor retains all other children.
pub fn run(root: &Path, state: &Path, handoff_id: &str) -> Result<()> {
    storage::private_dir(&storage::control(state))?;
    let _lock = storage::Lock::acquire(&storage::control(state).join("native-update.lock"))?;
    let _install = crate::updates::InstallMutex::acquire(root)?;
    if let Some(prior) = read_status(state)? {
        if prior.handoff_id != handoff_id {
            ensure!(
                ["succeeded", "rolled_back"].contains(&prior.phase.as_str()),
                "native_prior_recovery_pending"
            );
            let archive =
                storage::control(state).join(format!("native-update-{}.json", prior.handoff_id));
            ensure!(!archive.try_exists()?, "native_archive_exists");
            std::fs::rename(path(state), archive)?;
        }
    }
    let mut j = match read_status(state)? {
        Some(j) => {
            ensure!(
                j.install_root == root && j.handoff_id == handoff_id,
                "native_operation_changed"
            );
            j
        }
        None => {
            let h = upgrade::read_status(state)?.context("handoff_missing")?;
            ensure!(
                h.phase == upgrade::Phase::Succeeded && h.operation_id == handoff_id,
                "handoff_not_verified"
            );
            let record: Value = storage::read(&root.join("desktop-install.json"))?;
            ensure!(
                record["generation"] == h.to_generation,
                "installation_changed"
            );
            Journal {
                schema: 1,
                operation_id: uuid::Uuid::new_v4().to_string(),
                handoff_id: handoff_id.into(),
                install_root: root.into(),
                state_directory: state.into(),
                from_generation: h.from_generation,
                to_generation: h.to_generation,
                phase: "waiting_idle".into(),
                failure: None,
                original: None,
                stopping: None,
                verified: None,
                candidate_record: record,
                loaded_threads: vec![],
            }
        }
    };
    ensure!(
        j.candidate_record["installRoot"].as_str().map(Path::new) == Some(root)
            && j.candidate_record["stateDirectory"].as_str().map(Path::new) == Some(state)
            && j.candidate_record["generation"] == j.to_generation
            && j.candidate_record["releaseHash"] == j.to_generation,
        "native_installation_binding_invalid"
    );
    if ["succeeded", "rolled_back", "recovery_required"].contains(&j.phase.as_str()) {
        return Ok(());
    }
    // Admission cannot be revoked once the desktop (or a future child) has
    // observed the receipt. Recovery after publication only completes resume.
    ensure!(
        !released(&j)? || ["resuming", "rollback_resuming", "empty_resuming"].contains(&j.phase.as_str()),
        "native_release_phase_invalid"
    );
    let current: Value = storage::read(&root.join("desktop-install.json"))?;
    let prior = previous_record(&j);
    let expected = match j.phase.as_str() {
        "rolling_back" | "rollback_stop_intent" => {
            current == j.candidate_record || current == prior
        }
        "rollback_starting" | "rollback_verifying" | "rollback_helper" | "rollback_resuming" => {
            current == prior
        }
        _ => current == j.candidate_record,
    };
    ensure!(expected, "native_installation_drift");
    if j.phase=="empty_resuming" {
        io::admission::finish_empty(&j)?;
        return save(&mut j,"succeeded");
    }
    if j.phase == "waiting_idle" {
        let prepare = (|| -> Result<Option<Plan>> {
            let h = io::helper(root, state, &j.to_generation)?;
            let p = io::inventory(root, state, &j.to_generation)?;
            if let Some(p) = &p {
                ensure!(
                    generation(p)? == j.from_generation || generation(p)? == j.to_generation,
                    "native_predecessor_generation_unverified"
                );
                for c in connections(p) {
                ensure!(generation(&c)?==generation(p)?,"native_mixed_generations");
                let m = io::rpc(&c, "pocket/managementStatus", json!({}))?;
                ensure!(
                    m["startupBarrierProtocol"] == 1,
                    "native_rollback_startup_barrier_unsupported"
                );
                ensure!(m["startupAdmissionProtocol"]==1,"native_installation_admission_unavailable");
                }
            }
            ensure!(
                crate::diagnostics::idle_for_update(&h, storage::now()),
                "native_idle_unverified"
            );
            if let Some(p) = &p {
                j.loaded_threads = io::read_ready(p)?;
            }
            Ok(p)
        })();
        let p = match prepare {
            Ok(Some(p)) => p,
            Ok(None) => {
                save(&mut j,"empty_resuming")?;
                io::admission::finish_empty(&j)?;
                return save(&mut j,"succeeded");
            },
            Err(error) => {
                j.failure = Some(
                    if ["native_rollback_startup_barrier_unsupported","native_installation_admission_unavailable"].contains(&error.to_string().as_str()) {
                        error.to_string()
                    } else {
                        "native_identity_readiness_or_idle_unverified".into()
                    }
                    ,
                );
                return save(&mut j, "waiting_idle");
            }
        };
        if generation(&p)? == j.to_generation {
            save(&mut j,"waiting_idle")?;
            io::admission::finish_current(&j,&p)?;
            return save(&mut j, "succeeded");
        }
        upgrade::local::verify_generation_inventory(&root.join("bin").join(&j.from_generation))?;
        j.original = Some(p.clone());
        save(&mut j, "waiting_idle")?;
        let op = j.operation_id.clone();
        if fenced_stop(
            &mut Live {
                j: &mut j,
                batch: None,
                startup: false,
            },
            &p,
            &op,
        )
        .is_err()
        {
            j.failure = Some("native_barrier_or_identity_unverified".into());
            // A persisted intent could have stopped a subset. Never erase it.
            let unchanged = io::images(&p).into_iter()
                .all(|i| {
                    ProcessIdentity::capture(i.identity.pid).ok().as_ref() == Some(&i.identity)
                });
            let phase = if unchanged {
                "waiting_idle"
            } else {
                "stop_intent"
            };
            return save(&mut j, phase);
        }
        save(&mut j, "stopped")?;
    }
    if j.phase == "stop_intent" {
        if !originals_gone(j.original.as_ref().context("native_original_missing")?) {
            j.failure = Some("partial_native_stop_requires_recovery".into());
            return save(&mut j, "recovery_required");
        }
        save(&mut j, "stopped")?;
    }
    if j.phase == "stopped" {
        ensure!(
            originals_gone(j.original.as_ref().context("native_original_missing")?),
            "native_old_still_alive"
        );
        ensure!(
            io::inventory(root, state, &j.to_generation)?.is_none(),
            "native_new_process_appeared"
        );
        save(&mut j, "starting")?;
        if io::launch(&j).is_err() {
            save(&mut j, "rolling_back")?;
        } else {
            save(&mut j, "verifying")?;
        }
    }
    if j.phase == "starting" || j.phase == "verifying" {
        if let Ok(plan) = wait_ready(&j, &j.to_generation) {
            j.verified = Some(plan);
            save(&mut j, "resuming")?;
            return resume_verified(&mut j, false);
        }
        j.failure = Some("candidate_native_readiness_failed".into());
        save(&mut j, "rolling_back")?;
    }
    if j.phase == "rollback_stop_intent" {
        if !originals_gone(j.stopping.as_ref().context("rollback_stop_missing")?) {
            j.failure = Some("rollback_partial_stop_requires_recovery".into());
            return save(&mut j, "recovery_required");
        }
        save(&mut j, "rolling_back")?;
    }
    if j.phase == "rolling_back" {
        // Only a candidate with our persistent startup barrier can be stopped
        // during recovery. A running unfenced process is preserved.
        match io::inventory(root, state, &j.to_generation) {
            Ok(Some(p)) => {
                let op = j.operation_id.clone();
                let old = j.original.clone();
                if fenced_stop(
                    &mut Live {
                        j: &mut j,
                        batch: None,
                        startup: true,
                    },
                    &p,
                    &op,
                )
                .is_err()
                {
                    j.original = old;
                    j.failure = Some("rollback_native_busy_or_unverified".into());
                    return save(&mut j, "recovery_required");
                }
                j.original = old;
            }
            Ok(None) => {}
            Err(_) => {
                j.failure = Some("rollback_native_identity_unverified".into());
                return save(&mut j, "recovery_required");
            }
        }
        upgrade::local::verify_generation_inventory(&root.join("bin").join(&j.from_generation))?;
        let current: Value = storage::read(&root.join("desktop-install.json"))?;
        let prior = previous_record(&j);
        ensure!(
            current == j.candidate_record || current == prior,
            "rollback_installation_drift"
        );
        storage::atomic(&root.join("desktop-install.json"), &prior)?;
        save(&mut j, "rollback_starting")?;
        io::launch(&j)?;
        save(&mut j, "rollback_verifying")?;
    }
    if j.phase == "rollback_starting" || j.phase == "rollback_verifying" {
        // Recover a crash between the durable launch intent and process spawn.
        // An ambiguous or partially started tree is never launched over.
        if j.phase == "rollback_starting" && io::inventory(root, state, &j.to_generation)?.is_none()
        {
            io::launch(&j)?;
            save(&mut j, "rollback_verifying")?;
        }
        match wait_ready(&j, &j.from_generation) {
            Ok(p) => j.verified = Some(p),
            Err(_) => {
                j.failure = Some("previous_native_recovery_unverified".into());
                return save(&mut j, "recovery_required");
            }
        }
        save(&mut j, "rollback_helper")?;
    }
    if j.phase == "rollback_helper" {
        let current = upgrade::read_status(state)?.context("handoff_missing")?;
        let requested = current.operation_id == j.handoff_id;
        let result = if requested {
            // LocalBackend requires the coordinator itself to be the target
            // generation's verified image. This native coordinator is still
            // the failed generation; dispatch the ordinary independent helper
            // coordinator instead of weakening its self-identity check.
            upgrade::request_upgrade(root, state, &j.to_generation)?
        } else {
            ensure!(
                current.from_generation == j.to_generation
                    && current.to_generation == j.from_generation,
                "rollback_handoff_changed"
            );
            current
        };
        ensure!(result.from_generation==j.to_generation&&result.to_generation==j.from_generation,"rollback_handoff_changed");
        if !result.phase.terminal() {
            if !requested {io::dispatch_helper_recovery(&j,&result)?;}
            // Keep the durable rollback_helper phase. The existing supervisor
            // worker resumes it after independent helper completion, including
            // a coordinator crash; no new timeout or idle exception is added.
            return Ok(());
        }
        if result.phase != upgrade::Phase::Succeeded {
            j.failure = Some("previous_helper_recovery_unverified".into());
            return save(&mut j, "recovery_required");
        }
        io::helper(root, state, &j.from_generation)?;
        save(&mut j, "rollback_resuming")?;
    }
    if j.phase == "resuming" {
        resume_verified(&mut j, false)?;
    }
    if j.phase == "rollback_resuming" {
        resume_verified(&mut j, true)?;
    }
    Ok(())
}

// Recovery can inspect a replacement tree and release its admission, but has
// no launch/stop/rollback operation. Keep irreversible recovery out of this API.
trait ResumeBackend {
    fn helper(&mut self, j: &Journal, generation: &str) -> Result<Value>;
    fn inventory(&mut self, j: &Journal) -> Result<Option<Plan>>;
    fn read_ready(&mut self, p: &Plan) -> Result<Vec<String>>;
    fn image_ok(&mut self, image: &Image) -> Result<()>;
    fn rpc(&mut self, p: &Plan, method: &str, params: Value) -> Result<Value>;
    // A durable Released gate, or the verified tree's positive exit proof,
    // completes publication without adopting or contacting any later tree.
    fn complete_released(&mut self, _j: &Journal) -> Result<bool> { Ok(false) }
    fn finish_admission(&mut self,_j:&Journal)->Result<()> {Ok(())}
}

#[derive(Default)]
struct LiveResume { gate:Option<pocket_core::admission::UpdateGuard> }
impl ResumeBackend for LiveResume {
    fn helper(&mut self, j: &Journal, generation: &str) -> Result<Value> {
        io::helper(&j.install_root, &j.state_directory, generation)
    }
    fn inventory(&mut self, j: &Journal) -> Result<Option<Plan>> {
        let p=io::inventory(&j.install_root, &j.state_directory, &j.to_generation)?;
        if let (Some(p),Some(g))=(&p,&self.gate) {io::admission::verify_registered(j,p,g)?;}
        Ok(p)
    }
    fn read_ready(&mut self, p: &Plan) -> Result<Vec<String>> {
        io::read_ready(p)
    }
    fn image_ok(&mut self, image: &Image) -> Result<()> {
        io::image_ok(image)
    }
    fn rpc(&mut self, p: &Plan, method: &str, params: Value) -> Result<Value> {
        io::rpc(p, method, params)
    }
    fn complete_released(&mut self,j:&Journal)->Result<bool> {
        io::admission::finish_released(j,self.gate.as_ref().context("native_admission_guard_missing")?)
    }
    fn finish_admission(&mut self,j:&Journal)->Result<()> {io::admission::finish(j,self.gate.as_ref().context("native_admission_guard_missing")?)}
}

fn resume_verified(j: &mut Journal, rollback: bool) -> Result<()> {
    let gate=io::admission::resume_guard(j)?;
    resume_verified_with(j, rollback, &mut LiveResume{gate:Some(gate)})
}

fn resume_verified_with(j: &mut Journal, rollback: bool, b: &mut impl ResumeBackend) -> Result<()> {
    let phase = if rollback {
        "rollback_resuming"
    } else {
        "resuming"
    };
    ensure!(j.phase == phase, "native_resume_phase_invalid");
    let admitted = released(j)?;
    let previous = j.verified.as_ref().context("verified_native_missing")?;
    let expected = if rollback {
        &j.from_generation
    } else {
        &j.to_generation
    };
    if admitted {
        ensure!(read_status(&j.state_directory)?.as_ref()==Some(j),"Native readiness journal changed before completion");
        if b.complete_released(j)? {
            if !rollback {j.failure=None;}
            return save(j,if rollback {"rolled_back"} else {"succeeded"});
        }
    }
    let h = b.helper(j, expected)?;
    let p = b.inventory(j)?.context("native_not_started")?;
    // The receipt authorizes descendants of this verified Desktop. After
    // release, a different Desktop has no surviving startup fence to prove it
    // belongs to this operation, so it must not be silently adopted.
    if admitted {
        ensure!(
            p.desktop == previous.desktop,
            "native_released_desktop_changed"
        );
    } else {
        ensure!(
            p.desktop.identity.executable == previous.desktop.identity.executable
                && p.desktop.sha256 == previous.desktop.sha256,
            "desktop_image_changed"
        );
    }
    verify_ready_plan(j, expected, &h, &p, b)?;
    for c in connections(&p) {
    let m = b.rpc(&c, "pocket/managementStatus", json!({}))?;
    ensure!(
        m["protocol"] == 1 && m["startupBarrierProtocol"] == 1 && m["initialized"] == true,
        "native_resume_readiness_unverified"
    );
    if !admitted {
        ensure!(
            startup_proven(&m, &j.operation_id),
            "startup_barrier_released_early"
        );
    } else {
        ensure!(
            m["maintenance"].is_null()
                || (m["maintenance"]["operationId"] == j.operation_id
                    && m["maintenance"]["startupFenced"] == false),
            "native_resume_owner_changed"
        );
    }
    }
    // A removed/replaced child is harmless once the CURRENT complete tree is
    // authenticated and ready. Changes during that proof retry from inventory
    // on the next dispatch; no stale set becomes a permanent recovery key.
    ensure!(b.inventory(j)?.as_ref() == Some(&p), "native_tree_changed");
    ensure!(
        read_status(&j.state_directory)?.as_ref() == Some(j),
        "Native readiness journal changed before resume"
    );
    j.verified = Some(p.clone());
    save(j, phase)?;
    if !admitted {
        // Inventory and durable writes may be slow. Start the ordinary lease
        // afterwards; the persistent startup fence protects the work above.
        let before=Instant::now();
        for c in connections(&p) {
        let q = b.rpc(
            &c,
            "pocket/maintenance/quiesce",
            json!({"operationId":j.operation_id}),
        )?;
        ensure!(
            q["protocol"] == 1
                && q["quiesced"] == true
                && q["operationId"] == j.operation_id
                && q["leaseMs"] == 30000
                && q["startupFenced"] == true,
            "startup_release_lease_missing"
        );
        let m = b.rpc(&c, "pocket/managementStatus", json!({}))?;
        ensure!(
            startup_idle(&m, &j.operation_id) && lease(&m, &j.operation_id),
            "startup_release_not_idle"
        );
        }
        ensure!(before.elapsed()<Duration::from_secs(2),"startup_release_lease_stale");
        publish_release(j)?;
    }
    for c in connections(&p) {
    let m = b.rpc(&c,"pocket/managementStatus",json!({}))?;
    if m["maintenance"]["operationId"] == j.operation_id {
        let reply = b.rpc(
            &c,
            "pocket/maintenance/resume",
            json!({"operationId":j.operation_id}),
        )?;
        ensure!(
            reply["operationId"] == j.operation_id && reply["quiesced"] == false,
            "native_resume_unverified"
        );
    } else {
        ensure!(m["maintenance"].is_null(), "native_resume_owner_changed");
    }
    let m = b.rpc(&c, "pocket/managementStatus", json!({}))?;
    ensure!(m["maintenance"].is_null(), "native_barrier_not_released");
    }
    b.finish_admission(j)?;
    if !rollback {
        j.failure = None;
    }
    save(j, if rollback { "rolled_back" } else { "succeeded" })
}

fn previous_record(j: &Journal) -> Value {
    let mut prior = j.candidate_record.clone();
    prior["generation"] = json!(j.from_generation);
    prior["releaseHash"] = json!(j.from_generation);
    prior
}

/// A process-owned lock excludes simultaneous supervisor/UI/installer consumers.
pub fn dispatch(root: &Path, state: &Path, h: &upgrade::UpgradeStatus) -> Result<bool> {
    if h.phase != upgrade::Phase::Succeeded {
        return Ok(false);
    }
    if let Some(j) = read_status(state)? {
        if j.handoff_id != h.operation_id {
            ensure!(
                ["succeeded", "rolled_back"].contains(&j.phase.as_str()),
                "native_prior_recovery_pending"
            );
        } else if ["succeeded", "rolled_back", "recovery_required"].contains(&j.phase.as_str()) {
            return Ok(j.phase != "succeeded");
        }
    }
    // Automatic controls discovery of future offers. A helper switch already
    // accepted by the updater must finish its native/generation gate even when
    // automatic discovery is disabled (including an empty Desktop tree).
    spawn_coordinator(root, state, &h.operation_id, &h.to_generation)
}

/// Reverse helper handoff has its own operation ID. Keep recovering the native
/// operation even after that handoff replaces upgrade.json.
pub(crate) fn dispatch_pending(root: &Path, state: &Path, j: &Journal) -> Result<bool> {
    ensure!(
        j.install_root == root && j.state_directory == state,
        "native_operation_scope_changed"
    );
    if ["succeeded", "rolled_back", "recovery_required"].contains(&j.phase.as_str()) {
        return Ok(false);
    }
    spawn_coordinator(root, state, &j.handoff_id, &j.to_generation)
}
fn spawn_coordinator(
    root: &Path,
    state: &Path,
    handoff_id: &str,
    to_generation: &str,
) -> Result<bool> {
    let lock = storage::Lock::acquire(&storage::control(state).join("native-update.lock"));
    if lock.is_err() {
        return Ok(true);
    }
    drop(lock);
    let bin = root.join("bin").join(to_generation);
    let mut c =
        std::process::Command::new(crate::launcher::verified_exe(&bin, "pocket-upgrade.exe")?);
    c.arg("--native-restart")
        .arg("--install-root")
        .arg(root)
        .arg("--state")
        .arg(state)
        .arg("--operation-id")
        .arg(handoff_id)
        .env_remove("POCKET_UPDATE_OPERATION")
        .env_remove("POCKET_UPDATE_RELEASE_FILE")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null());
    crate::process::hidden(&mut c);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x08000000 | 0x01000000);
    }
    c.spawn()?;
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Fake {
        startup: bool,
        busy: bool,
        second_busy: bool,
        calls: Vec<String>,
        status: Value,
        identity: bool,
        stopped: bool,
    }
    fn fake() -> Fake {
        Fake {
            startup: false,
            busy: false,
            second_busy: false,
            calls: vec![],
            status: json!({"protocol":1,"initialized":true,"busy":false,"activeTurns":0,"pendingQuestions":0,"pendingWrites":0,"unknownOperations":0,"maintenance":{"operationId":"11111111-1111-4111-8111-111111111111","remainingMs":29000}}),
            identity: true,
            stopped: false,
        }
    }
    fn plan() -> Plan {
        let image = Image {
            identity: ProcessIdentity {
                pid: 42,
                created_at: 10,
                executable: "fixture.exe".into(),
            },
            sha256: "a".repeat(64),
        };
        Plan {
            desktop: image.clone(),
            bridge: image.clone(),
            native: image,
            children: vec![],
            descriptor: "fixture.json".into(),
            runtime_id: "fixture".into(),
            additional: vec![],
            ancestry: vec![],
        }
    }
    const OP: &str = "11111111-1111-4111-8111-111111111111";
    impl Backend for Fake {
        fn startup_fenced(&self) -> bool {
            self.startup
        }
        fn idle(&mut self) -> Result<bool> {
            let idle = if self.calls.is_empty() {
                !self.busy
            } else {
                !self.second_busy
            };
            self.calls.push("idle".into());
            Ok(idle)
        }
        fn rpc(&mut self, _: &Plan, method: &str, _: Value) -> Result<Value> {
            self.calls.push(method.into());
            Ok(if method == "pocket/managementStatus" {
                self.status.clone()
            } else {
                json!({"protocol":1,"operationId":OP,"quiesced":true,"leaseMs":30000})
            })
        }
        fn prepare_stop(&mut self, _: &Plan) -> Result<()> {
            self.calls.push("handles".into());
            ensure!(self.identity, "reused PID");
            Ok(())
        }
        fn persist_stop_intent(&mut self, _: &Plan) -> Result<()> {
            self.calls.push("intent".into());
            Ok(())
        }
        fn stop(&mut self, _: &Plan, deadline: Instant) -> Result<()> {
            assert!(deadline > Instant::now());
            self.calls.push("stop".into());
            self.stopped = true;
            Ok(())
        }
    }
    #[test]
    fn lease_is_mandatory_and_operation_bound() {
        let f = fake();
        assert!(lease(&f.status, OP));
        for key in [
            "activeTurns",
            "pendingQuestions",
            "pendingWrites",
            "unknownOperations",
        ] {
            let mut v = f.status.clone();
            v[key] = json!(1);
            assert!(!lease(&v, OP));
            v.as_object_mut().unwrap().remove(key);
            assert!(!lease(&v, OP));
        }
        let mut v = f.status;
        v["maintenance"]["remainingMs"] = json!(9999);
        assert!(!lease(&v, OP));
        assert!(!lease(&v, "other"));
        assert!(!lease(
            &json!({"protocol":1,"initialized":false,"busy":false}),
            OP
        ));
    }
    #[test]
    fn busy_unknown_changed_identity_or_lost_lease_never_stop() {
        for case in 0..6 {
            let mut f = fake();
            match case {
                0 => f.busy = true,
                1 => f.second_busy = true,
                2 => f.identity = false,
                3 => f.status["maintenance"] = Value::Null,
                4 => f.status["pendingQuestions"] = json!(1),
                _ => f.status["maintenance"]["operationId"] = json!("other"),
            };
            assert!(fenced_stop(&mut f, &plan(), OP).is_err());
            assert!(!f.stopped);
        }
    }
    #[test]
    fn renews_after_durable_intent_and_releases_on_success() {
        let mut f = fake();
        fenced_stop(&mut f, &plan(), OP).unwrap();
        assert_eq!(
            f.calls,
            vec![
                "idle",
                "handles",
                "pocket/maintenance/quiesce",
                "idle",
                "intent",
                "pocket/maintenance/quiesce",
                "pocket/managementStatus",
                "stop",
                "pocket/maintenance/resume"
            ]
        );
    }
    #[test]
    fn startup_recovery_requires_bound_zero_activity_and_never_resumes_on_failure() {
        for case in 0..8 {
            let mut f = fake();
            f.startup = true;
            f.status["startupBarrierProtocol"] = json!(1);
            f.status["initialized"] = json!(false);
            f.status["maintenance"]["startupFenced"] = json!(true);
            f.status["maintenance"]["remainingMs"] = json!(0);
            match case {
                0 => {}
                1 => f.status["unknownOperations"] = json!(1),
                2 => f.status["pendingQuestions"] = json!(1),
                3 => f.status["maintenance"]["operationId"] = json!("other"),
                4 => f.status["maintenance"]["startupFenced"] = json!(false),
                5 => f.identity = false,
                6 => {
                    f.status.as_object_mut().unwrap().remove("pendingWrites");
                }
                _ => f.status["startupBarrierProtocol"] = Value::Null,
            }
            let result = fenced_stop(&mut f, &plan(), OP);
            assert_eq!(result.is_ok(), case == 0);
            assert_eq!(f.stopped, case == 0);
            assert!(!f
                .calls
                .iter()
                .any(|c| c.contains("resume") || c.contains("quiesce") || c == "idle"));
        }
    }

    #[test]
    fn every_connection_must_be_quiesced_and_prove_idle_before_stop() {
        struct Multiple { inner:Fake, secondary_busy:bool, checked:Vec<String> }
        impl Backend for Multiple {
            fn idle(&mut self)->Result<bool>{Ok(true)}
            fn rpc(&mut self,p:&Plan,m:&str,v:Value)->Result<Value>{
                self.checked.push(format!("{}:{m}",p.runtime_id));
                let mut r=self.inner.rpc(p,m,v)?;
                if p.runtime_id=="secondary" && m=="pocket/managementStatus" && self.secondary_busy {r["pendingQuestions"]=json!(1);r["busy"]=json!(true);}
                Ok(r)
            }
            fn prepare_stop(&mut self,p:&Plan)->Result<()>{self.inner.prepare_stop(p)}
            fn persist_stop_intent(&mut self,p:&Plan)->Result<()>{self.inner.persist_stop_intent(p)}
            fn stop(&mut self,p:&Plan,d:Instant)->Result<()>{self.inner.stop(p,d)}
        }
        let mut p=plan();p.additional.push(Connection{bridge:p.bridge.clone(),native:p.native.clone(),descriptor:"second.json".into(),runtime_id:"secondary".into()});
        for busy in [true,false] {
            let mut b=Multiple{inner:fake(),secondary_busy:busy,checked:vec![]};
            assert_eq!(fenced_stop(&mut b,&p,OP).is_ok(),!busy);
            assert_eq!(b.inner.stopped,!busy);
            assert!(b.checked.contains(&"secondary:pocket/maintenance/quiesce".into()));
            assert!(b.checked.contains(&"secondary:pocket/managementStatus".into()));
            assert!(b.checked.contains(&"secondary:pocket/maintenance/resume".into()));
        }
    }
}
