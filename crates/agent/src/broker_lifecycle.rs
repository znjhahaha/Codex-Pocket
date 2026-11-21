//! Live, read-only evidence for the idle updater. The checkpoint transaction
//! revalidates these conditions after fencing writes; a snapshot is not a permit.
use super::*;

#[derive(Clone, Default)]
struct Activity {
    active: u64,
    pending: u64,
    writes: u64,
    unknown: u64,
}
impl Activity {
    fn add(&mut self, other: &Self) -> bool {
        let sums = (
            self.active.checked_add(other.active),
            self.pending.checked_add(other.pending),
            self.writes.checked_add(other.writes),
            self.unknown.checked_add(other.unknown),
        );
        if let (Some(active), Some(pending), Some(writes), Some(unknown)) = sums {
            *self = Self {
                active,
                pending,
                writes,
                unknown,
            };
            true
        } else {
            false
        }
    }
}
struct Probe {
    id: String,
    connection: String,
    rpc: Rpc,
    source: String,
    native: Option<NativeProcess>,
    home: PathBuf,
    role: Value,
    journal: Cursor,
    inventory: uuid::Uuid,
    host: Option<PathBuf>,
}
fn live(native: &Option<NativeProcess>) -> bool {
    native.as_ref().is_some_and(|p| {
        p.pid != 0
            && p.created_at != 0
            && !p.terminated()
            && NativeProcess::capture(p.pid).is_ok_and(|actual| actual == *p)
    })
}
fn host_activity(health: &pocket_runtime_ipc::Health, recovery: &Value) -> Result<Activity> {
    anyhow::ensure!(
        recovery["generation"] == health.generation && recovery["reconciliationRequired"] == false,
        "host_recovery_unverified"
    );
    let runs = recovery["runs"]
        .as_array()
        .context("host_runs_unavailable")?;
    let pending = recovery["pendingRequests"]
        .as_array()
        .context("host_pending_unavailable")?;
    anyhow::ensure!(
        runs.len() <= 256 && pending.len() <= 128,
        "host_inventory_oversized"
    );
    let mut out = Activity::default();
    let mut current_active = 0;
    let mut current_pending = 0;
    for run in runs {
        let generation = run["generation"]
            .as_str()
            .filter(|s| !s.is_empty())
            .context("host_run_identity_unavailable")?;
        match run["state"].as_str() {
            Some("active") => {
                anyhow::ensure!(generation == health.generation, "old_active_host_run");
                out.active += 1;
                current_active += 1;
            }
            Some("completed") => {}
            Some("unknown") => out.unknown += 1,
            _ => anyhow::bail!("host_run_state_unavailable"),
        }
    }
    for request in pending {
        let generation = request["generation"]
            .as_str()
            .filter(|s| !s.is_empty())
            .context("host_pending_identity_unavailable")?;
        out.pending += 1;
        if generation == health.generation {
            current_pending += 1;
        } else {
            out.unknown += 1;
        }
    }
    anyhow::ensure!(
        current_active == health.active_turns && current_pending == health.pending_requests,
        "host_activity_changed"
    );
    let commands = recovery["commandCount"]
        .as_u64()
        .context("host_commands_unavailable")?;
    let answers = recovery["answerCount"]
        .as_u64()
        .context("host_answers_unavailable")?;
    let records=commands.checked_add(answers).context("host_receipt_counts_invalid")?;
    anyhow::ensure!(records<=pocket_runtime_ipc::MAX_RECOVERY_RECORDS as u64,"host_receipt_history_oversized");
    // Protocol 2 exposes total records, which includes completed AND unknown
    // receipts. Zero records is complete evidence. Nonzero totals cannot prove
    // idle without authoritative pending/unknown counters from the same actor.
    if records > 0
        || recovery.get("unconfirmedWrites").is_some()
        || recovery.get("unknownOperations").is_some()
    {
        let writes = recovery["unconfirmedWrites"]
            .as_u64()
            .context("host_receipts_unverified")?;
        let unknown = recovery["unknownOperations"]
            .as_u64()
            .context("host_receipts_unverified")?;
        anyhow::ensure!(
            writes <= records && unknown <= records,
            "host_receipt_counts_invalid"
        );
        out.writes = writes;
        out.unknown = out
            .unknown
            .checked_add(unknown)
            .context("host_receipts_oversized")?;
    }
    Ok(out)
}

async fn probe_activity(probe: &Probe) -> Result<Activity> {
    anyhow::ensure!(
        probe.rpc.online() && live(&probe.native),
        "runtime_identity_unverified"
    );
    if let Some(cursor) = probe.rpc.managed_host_cursor() {
        anyhow::ensure!(
            probe.role["kind"] == "managedHost" && probe.role["generation"] == cursor.generation,
            "host_role_changed"
        );
        let path = probe.host.as_ref().context("host_unavailable")?;
        let descriptor = pocket_runtime_ipc::load_descriptor(path, &probe.home)?;
        anyhow::ensure!(
            descriptor.runtime_id == probe.id
                && descriptor.generation == cursor.generation
                && descriptor.native_process == probe.native,
            "host_identity_changed"
        );
        let mut client = pocket_runtime_ipc::HostClient::attach(&descriptor).await?;
        let health = client.health().await?;
        anyhow::ensure!(
            health.capabilities.survives_broker_disconnect && !health.reconciliation_required,
            "host_not_ready"
        );
        let recovery = client.recovery().await?;
        let activity = host_activity(&health, &recovery)?;
        let latest = client.health().await?;
        anyhow::ensure!(
            !latest.reconciliation_required
                && latest.active_turns == health.active_turns
                && latest.pending_requests == health.pending_requests
                && client.recovery().await? == recovery,
            "host_activity_changed"
        );
        anyhow::ensure!(
            serde_json::to_value(pocket_runtime_ipc::load_descriptor(path, &probe.home)?)?
                == serde_json::to_value(descriptor)?,
            "host_descriptor_changed"
        );
        return Ok(activity);
    }
    anyhow::ensure!(
        probe.role["kind"] != "managedHost",
        "host_cursor_unavailable"
    );
    if probe.source == "desktop" {
        let status = probe.rpc.call("pocket/managementStatus", json!({})).await?;
        anyhow::ensure!(
            status["protocol"] == 1 && status["initialized"] == true,
            "native_not_ready"
        );
        let active = status["activeTurns"]
            .as_u64()
            .context("active_count_unavailable")?;
        let pending = status["pendingQuestions"]
            .as_u64()
            .context("pending_count_unavailable")?;
        let writes = status["pendingWrites"]
            .as_u64()
            .context("write_count_unavailable")?;
        let unknown = status.get("unknownOperations").map(|v| v.as_u64().context("unknown_count_unavailable")).transpose()?.unwrap_or(0);
        anyhow::ensure!(
            status["busy"].as_bool() == Some(active != 0 || pending != 0 || writes != 0 || unknown != 0),
            "activity_inconsistent"
        );
        return Ok(Activity {
            active,
            pending,
            writes,
            unknown,
        });
    }
    anyhow::ensure!(
        probe.source == "cli" && probe.role["kind"] == "cliDaemon",
        "runtime_lifecycle_unverified"
    );
    let threads = loaded_threads(&probe.rpc).await?;
    let mut active = 0;
    for thread in &threads {
        let result = probe
            .rpc
            .call(
                "thread/read",
                json!({"threadId":thread,"includeTurns":false}),
            )
            .await?;
        anyhow::ensure!(result["thread"]["id"] == *thread, "thread_identity_changed");
        match result["thread"]["status"]["type"]
            .as_str()
            .or_else(|| result["thread"]["status"].as_str())
        {
            Some("idle") => {}
            // Codex rust-v0.170.0 app-server/src/thread_status.rs computes
            // SystemError only after excluding running turns and pending
            // approval/input guards. Accept that tagged status from a successful
            // read, never an RPC error or an unrecognized legacy status string.
            // Pocket writes/questions/Unknown receipts are checked separately.
            Some("systemError") if result["thread"]["status"].is_object() => {}
            Some("active") => active += 1,
            _ => anyhow::bail!("activity_unavailable"),
        }
    }
    anyhow::ensure!(
        loaded_threads(&probe.rpc).await? == threads,
        "inventory_changed"
    );
    Ok(Activity {
        active,
        ..Default::default()
    })
}
async fn loaded_threads(rpc: &Rpc) -> Result<BTreeSet<String>> {
    let mut cursor = Value::Null;
    let mut cursors = BTreeSet::new();
    let mut threads = BTreeSet::new();
    for _ in 0..100 {
        let mut params = json!({"limit":100});
        if !cursor.is_null() {
            params["cursor"] = cursor.clone();
        }
        let list = rpc.call("thread/loaded/list", params).await?;
        let page = list["data"].as_array().context("inventory_unavailable")?;
        anyhow::ensure!(page.len() <= 100, "inventory_oversized");
        for thread in page {
            let thread = thread
                .as_str()
                .filter(|t| crate::settings::valid_thread(t) && t.len() <= 256)
                .context("invalid_thread")?;
            anyhow::ensure!(threads.insert(thread.to_owned()), "duplicate_thread");
        }
        cursor = list.get("nextCursor").cloned().unwrap_or(Value::Null);
        if cursor.is_null() {
            return Ok(threads);
        }
        let next = cursor
            .as_str()
            .filter(|s| !s.is_empty() && s.len() <= 4096)
            .context("invalid_cursor")?;
        anyhow::ensure!(cursors.insert(next.to_owned()), "repeated_cursor");
    }
    anyhow::bail!("inventory_incomplete")
}

/// Empty runtime samples require an installation transaction, not a count.
/// Retain exclusive startup admission until the broker snapshot is rechecked.
struct EmptyProof {
    _gate: pocket_core::admission::UpdateGuard,
    ledger: Value,
    ownership: Value,
    exited: BTreeMap<String, ExitedConnection>,
}
#[derive(PartialEq, Eq)]
struct ExitedConnection { bridge: NativeProcess, native: NativeProcess, executable: PathBuf, descriptor: PathBuf }
fn empty_broker_verified(proof: &EmptyProof, state: &State, broker: &Broker) -> bool {
    let claims=broker.ownership_values(state);
    let ownership=serde_json::to_value(claims.values().collect::<Vec<_>>()).unwrap_or(Value::Null);
    let ledger=serde_json::from_str::<Value>(&state.ledger.snapshot_json()).unwrap_or(Value::Null);
    let questions=serde_json::to_value(&state.questions).unwrap_or(Value::Null);
    state.runtimes.is_empty() && !state.ownership_persist_failed && ledger==proof.ledger && ownership==proof.ownership
        && claims.iter().all(|(id,claim)| {
            claim.advertisement.source==RuntimeSource::Desktop
                && claim.advertisement.id.computer_id==broker.config.computer_id
                && claim.native_process.as_ref()==proof.exited.get(id).map(|p|&p.native)
                && claim.native_process.as_ref().is_some_and(NativeProcess::terminated)
        })
        && ledger["entries"].as_array().is_some_and(|entries|entries.iter().all(|e| {
            matches!(serde_json::from_value::<Status>(e["status"].clone()),Ok(Status::Accepted{..}|Status::Rejected{..}|Status::Unknown))
        }))
        && questions["records"].as_array().is_some_and(|records|records.iter().all(|r| {
            matches!(r["phase"].as_str(),Some("confirmed"|"answered_elsewhere"|"cancelled"))
        }))
}
fn empty_read(path: &std::path::Path) -> Result<Value> {
    pocket_core::admission::read_json(path, 4 * 1024 * 1024)
}
fn empty_path(value: &Value) -> Result<PathBuf> {
    let path = PathBuf::from(value.as_str().context("empty_path_missing")?);
    pocket_core::admission::validate_path(&path)?;
    Ok(path)
}
fn empty_identity(value: &Value) -> Result<NativeProcess> {
    let p: NativeProcess = serde_json::from_value(json!({"pid":value["pid"],"createdAt":value["createdAt"]}))?;
    anyhow::ensure!(p.pid != 0 && p.created_at != 0, "empty_identity_invalid");
    Ok(p)
}
fn empty_plan(root: &std::path::Path, plan: &Value, exited: &mut BTreeMap<String, ExitedConnection>) -> Result<()> {
    let mut images = vec![&plan["desktop"], &plan["bridge"], &plan["native"]];
    images.extend(plan["children"].as_array().context("empty_children_missing")?);
    let additional = plan["additional"].as_array().context("empty_connections_missing")?;
    anyhow::ensure!(additional.len() < 16, "empty_connection_limit");
    for c in additional { images.extend([&c["bridge"], &c["native"]]); }
    let mut identities = BTreeMap::new();
    for image in images {
        let p = empty_identity(&image["identity"])?;
        let path = empty_path(&image["identity"]["executable"])?;
        anyhow::ensure!(pocket_core::admission::valid_generation(image["sha256"].as_str().unwrap_or(""))
            && p.terminated(), "empty_tree_exit_unverified");
        if let Some(previous) = identities.insert(p.pid, (p.clone(), path.clone())) {
            anyhow::ensure!(previous == (p, path), "empty_identity_conflict");
        }
    }
    anyhow::ensure!((3..=128).contains(&identities.len()), "empty_tree_limit");
    let desktop = empty_identity(&plan["desktop"]["identity"])?;
    let edges = plan["ancestry"].as_array().context("empty_ancestry_missing")?;
    anyhow::ensure!(edges.len() == identities.len() - 1, "empty_ancestry_incomplete");
    let mut children = BTreeSet::new();
    for edge in edges {
        let child = empty_identity(&edge["child"])?;
        let parent = empty_identity(&edge["parent"])?;
        anyhow::ensure!(child != desktop && child.created_at > parent.created_at && children.insert(child.pid)
            && identities.get(&child.pid) == Some(&(child.clone(), empty_path(&edge["child"]["executable"])?))
            && identities.get(&parent.pid) == Some(&(parent.clone(), empty_path(&edge["parent"]["executable"])?)), "empty_ancestry_changed");
    }
    for connection in std::iter::once(plan).chain(additional.iter()) {
        let id = connection["runtimeId"].as_str().context("empty_runtime_missing")?;
        anyhow::ensure!(uuid::Uuid::parse_str(id).is_ok_and(|u|u.to_string()==id), "empty_runtime_invalid");
        let bridge = empty_path(&connection["bridge"]["identity"]["executable"])?;
        let generation = bridge.parent().and_then(|p|p.file_name()).and_then(|s|s.to_str()).context("empty_generation_missing")?;
        anyhow::ensure!(pocket_core::admission::valid_generation(generation)
            && bridge == root.join("bin").join(generation).join("pocket-bridge.exe")
            && empty_path(&connection["descriptor"])? == root.join("runtimes").join(format!("{id}.json")), "empty_connection_binding_changed");
        let fact = ExitedConnection {bridge:empty_identity(&connection["bridge"]["identity"])? ,native:empty_identity(&connection["native"]["identity"])? ,executable:bridge,descriptor:empty_path(&connection["descriptor"])?};
        if let Some(previous) = exited.insert(id.into(), fact) {
            anyhow::ensure!(Some(&previous) == exited.get(id), "empty_runtime_identity_conflict");
        }
    }
    Ok(())
}
fn empty_proof(state: &std::path::Path, exe: &std::path::Path) -> Result<EmptyProof> {
    use sha2::Digest;
    use pocket_core::admission::{self, GatePhase, Installation, RegistrationPhase};
    admission::validate_path(exe)?;
    let bin = exe.parent().context("empty_bin_missing")?;
    let generation = bin.file_name().and_then(|s|s.to_str()).context("empty_generation_missing")?;
    let bins = bin.parent().context("empty_bins_missing")?;
    let root = bins.parent().context("empty_root_missing")?;
    anyhow::ensure!(bins.file_name().is_some_and(|s|s=="bin") && admission::valid_generation(generation)
        && exe == bin.join("pocket-agent.exe") && admission::file_sha256(&bin.join("windows-manifest.json"))? == generation, "empty_installation_unverified");
    let manifest = empty_read(&bin.join("windows-manifest.json"))?;
    for name in ["pocket-agent.exe", "windows-install.ps1", "windows-common.ps1"] {
        let entries: Vec<_> = manifest["files"].as_array().context("empty_manifest_missing")?.iter().filter(|e|e["path"]==name).collect();
        anyhow::ensure!(entries.len()==1 && entries[0]["sha256"]==admission::file_sha256(&bin.join(name))?
            && entries[0]["bytes"].as_u64()==Some(std::fs::metadata(bin.join(name))?.len()), "empty_tool_changed");
    }
    let record = empty_read(&root.join("desktop-install.json"))?;
    anyhow::ensure!(empty_path(&record["installRoot"])? == root && empty_path(&record["stateDirectory"])? == state
        && record["generation"]==generation && record["releaseHash"]==generation
        && record["version"]==3 && record["singleEntry"]==true && empty_path(&record["bridge"])?==root.join("pocket-launcher.exe")
        && record["launcherHash"]==admission::file_sha256(&root.join("pocket-launcher.exe"))?, "empty_pointer_changed");
    let config = empty_read(&state.join("config.json"))?;
    anyhow::ensure!(config["runtimeDirectories"]==json!([root.join("runtimes")])
        && config["codexHomes"].as_array().is_some_and(Vec::is_empty), "empty_external_inventory_unverified");
    let guard = Installation::open(root)?.update()?;
    let gate = guard.gate()?.context("empty_gate_missing")?;
    anyhow::ensure!(gate.phase==GatePhase::Released && gate.release_file==state.join("desktop/updates").join(format!("native-release-{}.json",gate.operation_id))
        && admission::read_release(&gate.release_file,&gate.operation_id)?, "empty_release_unverified");
    if gate.allowed_generation.as_deref()!=Some(generation) {
        let switching=empty_read(&state.join("desktop/updates/status.json"))?;
        let handoff=empty_read(&state.join("desktop/upgrade.json"))?;
        anyhow::ensure!(matches!(switching["phase"].as_str(),Some("switching"|"succeeded")) && switching["generation"]==generation
            && switching["previousRecord"]["generation"].as_str()==gate.allowed_generation.as_deref()
            && empty_path(&switching["previousRecord"]["installRoot"])?==root
            && empty_path(&switching["previousRecord"]["stateDirectory"])?==state
            && handoff["operationId"]==switching["handoffOperationId"]
            && handoff["fromGeneration"].as_str()==gate.allowed_generation.as_deref() && handoff["toGeneration"]==generation
            && matches!(handoff["phase"].as_str(),Some("starting"|"verifying"|"succeeded"))
            && switching["candidateRecordHash"]==hex::encode(sha2::Sha256::digest(serde_json::to_vec(&record)?))
            && switching["previousRecordHash"]==hex::encode(sha2::Sha256::digest(serde_json::to_vec(&switching["previousRecord"])?))
            && empty_path(&handoff["installRoot"])?==root && empty_path(&handoff["stateDirectory"])?==state,"empty_generation_unverified");
    }
    let mut exited = BTreeMap::new();
    let mut gate_journal = false;
    let mut count = 0;
    for entry in std::fs::read_dir(state.join("desktop"))? {
        let path = entry?.path();
        let name = path.file_name().and_then(|n|n.to_str()).unwrap_or("");
        if name!="native-update.json" && !(name.starts_with("native-update-") && name.ends_with(".json")) {continue}
        count+=1; anyhow::ensure!(count<=256,"empty_archive_limit");
        let journal = empty_read(&path)?;
        let op = journal["operationId"].as_str().context("empty_operation_missing")?;
        anyhow::ensure!(uuid::Uuid::parse_str(op).is_ok_and(|u|u.to_string()==op)
            && journal["schema"]==1 && empty_path(&journal["installRoot"])?==root && empty_path(&journal["stateDirectory"])?==state, "empty_journal_binding_changed");
        // A queued successor can coexist with the last released proof. Never
        // interpret its unfinished tree as an exit proof or discard its records.
        if !matches!(journal["phase"].as_str(),Some("succeeded"|"rolled_back")) {continue}
        anyhow::ensure!(admission::read_release(&state.join("desktop/updates").join(format!("native-release-{op}.json")),op)?,"empty_journal_release_missing");
        if op==gate.operation_id {
            let expected=if journal["phase"]=="rolled_back"{&journal["fromGeneration"]}else{&journal["toGeneration"]};
            anyhow::ensure!(!gate_journal && expected.as_str()==gate.allowed_generation.as_deref(),"empty_gate_journal_ambiguous");gate_journal=true;
        }
        for name in ["original","stopping","verified"] {
            if !journal[name].is_null() {empty_plan(root,&journal[name],&mut exited)?;}
        }
    }
    anyhow::ensure!(gate_journal && !exited.is_empty(), "empty_full_tree_proof_missing");
    for registration in guard.registrations()? {
        let fact=exited.get(&registration.runtime_id).context("empty_registration_unknown")?;
        anyhow::ensure!(registration.phase==RegistrationPhase::Registered && registration.install_root==root
            && registration.native.as_ref()==Some(&fact.native) && registration.bridge==fact.bridge
            && registration.descriptor==fact.descriptor && registration.bridge_executable==fact.executable
            && registration.bridge_executable==root.join("bin").join(&registration.generation).join("pocket-bridge.exe"), "empty_registration_unverified");
        if let Some(operation)=&registration.operation_id {
            let receipt=state.join("desktop/updates").join(format!("native-release-{operation}.json"));
            anyhow::ensure!(registration.release_file.as_ref()==Some(&receipt) && admission::read_release(&receipt,operation)?,"empty_registration_operation_changed");
        } else {anyhow::ensure!(registration.release_file.is_none(),"empty_registration_operation_changed");}
    }
    let ledger = empty_read(&state.join("commands.json"))?;
    let ownership = empty_read(&state.join("ownership.json"))?;
    Ok(EmptyProof {_gate:guard,ledger,ownership,exited})
}
#[cfg(windows)]
fn empty_inventory(proof: EmptyProof, state: &std::path::Path, exe: &std::path::Path) -> Result<EmptyProof> {
    use std::{io::Read,os::windows::process::CommandExt,process::{Command,Stdio},time::Instant};
    let bin=exe.parent().context("empty_bin_missing")?;
    let root=bin.parent().and_then(|p|p.parent()).context("empty_root_missing")?;
    let powershell=PathBuf::from(std::env::var_os("SystemRoot").context("empty_system_root_missing")?).join("System32/WindowsPowerShell/v1.0/powershell.exe");
    let mut child=Command::new(powershell).args(["-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File"])
        .arg(bin.join("windows-install.ps1")).arg("-NativeUpdateMode").arg("Inventory")
        .arg("-InstallRoot").arg(root).arg("-StateDirectory").arg(state)
        .env_remove("POCKET_UPDATE_OPERATION").env_remove("POCKET_UPDATE_RELEASE_FILE").env_remove("PSModulePath")
        .stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null()).creation_flags(0x08000000).spawn()?;
    let end=Instant::now()+Duration::from_secs(8);
    loop {
        if let Some(exit)=child.try_wait()? {anyhow::ensure!(exit.success(),"empty_os_inventory_failed");break}
        if Instant::now()>=end {let _=child.kill();let _=child.wait();anyhow::bail!("empty_os_inventory_timeout")}
        std::thread::sleep(Duration::from_millis(25));
    }
    let mut bytes=Vec::new();child.stdout.take().context("empty_inventory_pipe_missing")?.take(1024*1024+1).read_to_end(&mut bytes)?;
    anyhow::ensure!(bytes.len()<=1024*1024,"empty_os_inventory_limit");
    let inventory:Value=serde_json::from_slice(&bytes)?;
    anyhow::ensure!(inventory["group"].as_array().is_some_and(Vec::is_empty)
        && inventory["descriptors"].as_array().is_some_and(Vec::is_empty),"empty_os_tree_present");
    Ok(proof)
}
#[cfg(windows)]
struct EmptyFlight { sample:futures_util::future::Shared<futures_util::future::BoxFuture<'static,Option<Arc<EmptyProof>>>> }
#[cfg(windows)]
fn empty_flights()-> &'static std::sync::Mutex<BTreeMap<PathBuf,std::sync::Weak<EmptyFlight>>> {
    static FLIGHTS:std::sync::OnceLock<std::sync::Mutex<BTreeMap<PathBuf,std::sync::Weak<EmptyFlight>>>>=std::sync::OnceLock::new();
    FLIGHTS.get_or_init(Default::default)
}
async fn empty_sample(state: PathBuf) -> Option<Arc<EmptyProof>> {
    #[cfg(windows)]
    {
        use futures_util::FutureExt;
        let flight={
            let mut flights=empty_flights().lock().ok()?;
            flights.retain(|_,flight|flight.strong_count()>0);
            if let Some(flight)=flights.get(&state).and_then(std::sync::Weak::upgrade){flight}else{
                let owned=state.clone();
                let sample=async move {tokio::task::spawn_blocking(move|| {
                    let exe=std::env::current_exe()?;
                    empty_inventory(empty_proof(&owned,&exe)?,&owned,&exe)
                }).await.ok().and_then(Result::ok).map(Arc::new)}.boxed().shared();
                let flight=Arc::new(EmptyFlight{sample});flights.insert(state,Arc::downgrade(&flight));flight
            }
        };
        // Only concurrent observations share this scan. The weak map retains
        // neither a completed proof nor its startup lock after callers return.
        flight.sample.clone().await
    }
    #[cfg(not(windows))]
    {let _=state;None}
}

impl Broker {
    pub async fn lifecycle_status(&self) -> Value {
        // Serialize only observers before taking the writer guard. Two status
        // requests must not report one another as a business write in flight.
        let _reader = self.lifecycle_reads.lock().await;
        let observed_at = crate::connection::now();
        let mut serializer = self.writes.try_lock().ok();
        let mut broker_busy = serializer.is_none();
        let handoff_permits = self.handoff.get().map(|h| h.unconfirmed_writes()).unwrap_or(0);
        let probes = {
            let state = self.state.lock().await;
            state
                .runtimes
                .iter()
                .map(|(id, r)| Probe {
                    id: id.clone(),
                    connection: r.connection_id.clone(),
                    rpc: r.rpc.clone(),
                    source: r.source.clone(),
                    native: r.native_process.clone(),
                    home: r.config_home.clone(),
                    role: serde_json::to_value(&r.role).unwrap_or(Value::Null),
                    journal: r.journal.cursor(),
                    inventory: r.inventory_revision,
                    host: self.handoff.get().and_then(|h| h.host_descriptor(id)),
                })
                .collect::<Vec<_>>()
        };
        let checked = futures_util::future::join_all(probes.iter().map(|probe| async {
            tokio::time::timeout(Duration::from_secs(3), probe_activity(probe))
                .await
                .ok()
                .and_then(Result::ok)
        }))
        .await;
        let empty = if probes.is_empty() && !broker_busy {
            // An empty tree is independently fenced by installation admission.
            // A slow read-only OS scan must not masquerade as a native write in
            // another status request. Reacquire the writer guard under the state
            // lock and recheck the exact persisted ledger/ownership below.
            drop(serializer.take());
            empty_sample(self.state_dir.clone()).await
        } else {None};
        let state = self.state.lock().await;
        if empty.is_some() {
            serializer=self.writes.try_lock().ok();broker_busy=serializer.is_none();
        }
        let mut verified = !checked.is_empty() || empty.is_some();
        let mut activity = Activity::default();
        for (probe, result) in probes.iter().zip(checked) {
            if state.runtimes.get(&probe.id).is_none_or(|r| {
                !r.rpc.online()
                    || r.connection_id != probe.connection
                    || r.native_process != probe.native
                    || r.config_home != probe.home
                    || r.source != probe.source
                    || serde_json::to_value(&r.role).ok().as_ref() != Some(&probe.role)
                    || r.inventory_revision != probe.inventory
                    || r.journal.cursor() != probe.journal
                    || !live(&r.native_process)
                    || self
                        .handoff
                        .get()
                        .and_then(|h| h.host_descriptor(&probe.id))
                        != probe.host
            }) {
                verified = false;
            }
            if result.as_ref().is_none_or(|value| !activity.add(value)) {
                verified = false;
            }
        }
        // A new runtime or unresolved persisted owner also invalidates the sample.
        if probes.len() != state.runtimes.len()
            || state.ownership_persist_failed
            || self.ownership_values(&state).iter().any(|(id, claim)| {
                !state.runtimes.contains_key(id)
                    && !claim
                        .native_process
                        .as_ref()
                        .is_some_and(|p| p.terminated())
            })
        {
            verified = false;
        }
        let ledger: Value =
            serde_json::from_str(&state.ledger.snapshot_json()).unwrap_or(Value::Null);
        if let Some(proof)=&empty {
            if !empty_broker_verified(proof,&state,self) {verified=false;}
        }
        let entries = ledger["entries"].as_array();
        // Only broker ledger tombstones can travel in a helper checkpoint. Host
        // ambiguity and unacknowledged question delivery still require reconciliation.
        let mut transferable = 0;
        let unknown = entries.map(|entries| entries.iter().filter(|entry| {
            let question_in_flight = serde_json::from_value::<CommandKey>(entry["key"].clone()).ok()
                .and_then(|key| state.questions.status(&key)).is_some_and(|receipt| matches!(
                    receipt["phase"].as_str(), Some("dispatching" | "forwarded" | "awaiting_confirmation")));
            let tombstone = entry["status"] == "Unknown";
            if tombstone && !question_in_flight { transferable += 1; }
            tombstone || question_in_flight
        }).count());
        let submitted = entries.map(|entries| {
            entries
                .iter()
                .filter(|e| e["status"] == "Submitted")
                .count()
        });
        if unknown.is_none() {
            verified = false;
        }
        activity.pending = activity.pending.max(state.pending.len() as u64);
        activity.writes = activity
            .writes
            .max(submitted.unwrap_or_default() as u64)
            .max(u64::from(broker_busy))
            .max(handoff_permits as u64)
            .max(self.handoff.get().map(|h| h.unconfirmed_writes()).unwrap_or(0) as u64);
        if !activity.add(&Activity {
            unknown: unknown.unwrap_or_default() as u64,
            ..Default::default()
        }) {
            verified = false;
        }
        let idle = verified
            && activity.active == 0
            && activity.pending == 0
            && activity.writes == 0
            && activity.unknown == 0;
        let result=json!({"observedAt":observed_at,"idle":idle,"checkpointableUnknownOperations":if verified {Some(transferable)} else {None},"nativeTasksPreserved":verified,
            "activeTurns":if verified {Some(activity.active)} else {None},"pendingQuestions":activity.pending,
            "pendingWrites":activity.writes,"unknownOperations":if verified {Some(activity.unknown)} else {None},"reason":if verified {None} else {Some("runtime_activity_unverified")}});
        // Release the writer guard before waking another read-only observer on
        // the state mutex. Actual writes still invalidate that observer's proof.
        drop(serializer);result
    }
}

#[cfg(all(test, windows))]
#[path = "broker_lifecycle/cli_tests.rs"]
mod cli_tests;

#[cfg(all(test, windows))]
#[path = "broker_lifecycle/empty_tests.rs"]
mod empty_tests;

#[cfg(all(test, windows))]
#[allow(dead_code)]
#[path = "managed_runtime/tests/fixture.rs"]
mod host_fixture;

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    use crate::native::{
        NativeRole,
        test_peer::{self, Peer},
    };

    async fn fixture() -> (tempfile::TempDir, Arc<Broker>) {
        super::super::tests::fixture().await
    }
    async fn peer(b: &Broker, id: &str, auxiliary: bool) -> Peer {
        let (mut connected, mut peer) =
            test_peer::connect(id, "desktop", &b.state_dir, b.native_events.clone()).await;
        connected.native_process = Some(NativeProcess::capture(std::process::id()).unwrap());
        if auxiliary {
            connected.role = NativeRole::DesktopAuxiliary;
        }
        if auxiliary {
            b.add_runtime(connected).await.unwrap();
            return peer;
        }
        let (registered, ()) = tokio::join!(b.add_runtime(connected), async {
            let request = peer.recv().await;
            assert_eq!(request["method"], "pocket/approvals/list");
            peer.reply(&request, json!({"data":[]})).await;
        });
        registered.unwrap();
        peer
    }
    fn idle() -> Value {
        json!({"protocol":1,"initialized":true,"activeTurns":0,"pendingQuestions":0,"pendingWrites":0,"busy":false})
    }
    async fn reply(peer: &mut Peer, value: Value) {
        let request = peer.recv().await;
        assert_eq!(request["method"], "pocket/managementStatus");
        peer.reply(&request, value).await;
    }
    async fn sample(b: &Broker, peer: &mut Peer, value: Value) -> Value {
        let (value, ()) = tokio::join!(b.lifecycle_status(), reply(peer, value));
        value
    }

    #[tokio::test]
    async fn verified_idle_and_auxiliary_pending_work_are_separate_from_directory_visibility() {
        let (_dir, b) = fixture().await;
        let mut desktop = peer(&b, "desktop", false).await;
        assert_eq!(sample(&b, &mut desktop, idle()).await["idle"], true);
        let mut aux = peer(&b, "browser", true).await;
        for (a, p, w) in [(1, 0, 0), (0, 1, 0), (0, 0, 1)] {
            let (value, (), ()) = tokio::join!(
                b.lifecycle_status(),
                reply(&mut desktop, idle()),
                reply(
                    &mut aux,
                    json!({"protocol":1,"initialized":true,"activeTurns":a,"pendingQuestions":p,"pendingWrites":w,"busy":true})
                )
            );
            assert_eq!(value["idle"], false, "{value}");
            assert_eq!(value["activeTurns"], a);
            assert_eq!(value["pendingQuestions"], p);
            assert_eq!(value["pendingWrites"], w);
        }
    }
    #[tokio::test]
    async fn concurrent_readers_do_not_count_each_other_as_pending_writes() {
        let (_dir,b)=fixture().await;
        let mut native=peer(&b,"desktop",false).await;
        let (first,second,())=tokio::join!(b.lifecycle_status(),b.lifecycle_status(),async {
            reply(&mut native,idle()).await;
            reply(&mut native,idle()).await;
        });
        for result in [first,second] {
            assert_eq!(result["pendingWrites"],0,"Read-only status must not manufacture work: {result}");
            assert_eq!(result["idle"],true);
        }
    }
    #[tokio::test]
    async fn broker_unknown_and_submitted_receipts_and_serializer_block_idle() {
        let (_dir, b) = fixture().await;
        let mut desktop = peer(&b, "r", false).await;
        let key = CommandKey {
            caller_id: "fixture".into(),
            command_id: "unknown".into(),
        };
        {
            let mut state = b.state.lock().await;
            state
                .ledger
                .begin(key.clone(), b"fixture", storage::now())
                .unwrap();
            state
                .ledger
                .finish(&key, Status::Unknown, storage::now())
                .unwrap();
        }
        let value = sample(&b, &mut desktop, idle()).await;
        assert_eq!(value["unknownOperations"], 1);
        assert_eq!(value["checkpointableUnknownOperations"], 1);
        assert_eq!(value["idle"], false);
        {
            let mut state = b.state.lock().await;
            state
                .ledger
                .finish(
                    &key,
                    Status::Accepted {
                        native_result: json!({}),
                    },
                    storage::now(),
                )
                .unwrap();
        }
        let guard = b.writes.lock().await;
        let value = sample(&b, &mut desktop, idle()).await;
        assert_eq!(value["pendingWrites"], 1);
        assert_eq!(value["idle"], false);
        drop(guard);
        assert_eq!(sample(&b, &mut desktop, idle()).await["idle"], true);
    }

    #[tokio::test]
    async fn disconnected_auxiliary_retains_lifecycle_obligation_after_restart() {
        let (dir, b) = fixture().await;
        let mut primary = peer(&b, "primary", false).await;
        let _aux = peer(&b, "browser", true).await;
        {
            let mut state = b.state.lock().await;
            b.save_ownership(&mut state).unwrap();
            state.runtimes.remove("browser");
            assert!(b.ownership_values(&state)["browser"].auxiliary);
        }
        assert_eq!(sample(&b, &mut primary, idle()).await["idle"], false);
        let config = b.config.clone();
        drop(b);
        let (restored, _) = Broker::open(config, dir.path().into()).unwrap();
        assert!(restored.state.lock().await.ownership["browser"].auxiliary);
        assert_eq!(restored.lifecycle_status().await["idle"], false);
    }
    #[tokio::test]
    async fn metadata_replacement_and_activity_during_probe_invalidate_idle() {
        for field in ["process", "home", "role", "inventory", "connection"] {
            let (_dir, b) = fixture().await;
            let mut desktop = peer(&b, "r", false).await;
            let (value, ()) = tokio::join!(b.lifecycle_status(), async {
                let request = desktop.recv().await;
                {
                    let mut state = b.state.lock().await;
                    let r = state.runtimes.get_mut("r").unwrap();
                    match field {
                        "process" => r.native_process = None,
                        "home" => r.config_home = PathBuf::from("C:/different"),
                        "role" => r.role = NativeRole::DesktopAuxiliary,
                        "inventory" => r.inventory_revision = uuid::Uuid::new_v4(),
                        _ => r.connection_id = uuid::Uuid::new_v4().to_string(),
                    }
                }
                desktop.reply(&request, idle()).await;
            });
            assert_eq!(value["idle"], false, "{field}: {value}");
            assert_eq!(value["nativeTasksPreserved"], false, "{field}: {value}");
        }
    }
    #[tokio::test]
    async fn unresolved_empty_owner_and_persistence_failure_do_not_claim_idle() {
        let (_dir, b) = fixture().await;
        let mut desktop = peer(&b, "r", false).await;
        {
            let mut state = b.state.lock().await;
            let r = &state.runtimes["r"];
            let claim = OwnershipClaim {
                auxiliary: false,
                native_process: r.native_process.clone(),
                advertisement: RuntimeAdvertisement {
                    id: RuntimeId {
                        computer_id: b.config.computer_id.clone(),
                        runtime_id: "unattached".into(),
                    },
                    source: RuntimeSource::Cli,
                    config_store_id: r.store.clone(),
                    online: false,
                    loaded_native_thread_ids: BTreeSet::new(),
                },
            };
            state.ownership.insert("unattached".into(), claim);
        }
        assert_eq!(sample(&b, &mut desktop, idle()).await["idle"], false);
        {
            let mut state = b.state.lock().await;
            state.ownership.clear();
            state.ownership_persist_failed = true;
        }
        assert_eq!(sample(&b, &mut desktop, idle()).await["idle"], false);
    }
    #[tokio::test]
    async fn malformed_or_overflowing_counts_are_unknown_and_never_panic() {
        let (_dir, b) = fixture().await;
        let mut desktop = peer(&b, "r", false).await;
        for value in [
            json!({"protocol":1,"initialized":true,"busy":false}),
            json!({"protocol":1,"initialized":true,"activeTurns":1,"pendingQuestions":0,"pendingWrites":0,"busy":false}),
            json!({"protocol":1,"initialized":true,"activeTurns":u64::MAX,"pendingQuestions":1,"pendingWrites":0,"busy":true}),
        ] {
            let value = sample(&b, &mut desktop, value).await;
            assert_eq!(value["idle"], false);
        }
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn host_receipt_without_active_turn_is_not_idle_evidence() {
        let f = host_fixture::Fixture::new();
        let (_dir, b) = fixture().await;
        let runtime = Arc::new(f.controller.ensure(&f.profile).await.unwrap());
        let h =
            crate::handoff::Handoff::open(&b.state_dir, uuid::Uuid::new_v4(), true, true).unwrap();
        h.register_host_descriptor(runtime.runtime_id(), &f.state().join("descriptor.json"))
            .unwrap();
        b.install_handoff(h).unwrap();
        b.add_runtime(runtime.attach(b.native_events.clone(), None).await.unwrap())
            .await
            .unwrap();
        assert_eq!(b.lifecycle_status().await["idle"], true);
        let task = tokio::spawn({
            let runtime = runtime.clone();
            async move {
                runtime
                    .request(json!("unconfirmed-fixture"), "fixture/deferred", json!({}))
                    .await
            }
        });
        tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                if std::fs::read_to_string(f.profile.config_home().join("fixture-methods"))
                    .unwrap_or_default()
                    .contains("fixture/deferred")
                {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        task.abort();
        let _ = task.await;
        let health = runtime.health().await.unwrap();
        assert_eq!(health.active_turns, 0);
        assert_eq!(health.pending_requests, 0);
        assert!(
            !health.reconciliation_required,
            "this fixture reproduces the missing Host receipt counter"
        );
        let value = b.lifecycle_status().await;
        assert_eq!(value["idle"], false, "{value}");
        // Fixture drop stops only its own fake native with the existing sentinel.
    }

    #[tokio::test]
    async fn transport_accepted_input_still_requires_its_native_item_receipt() {
        let (_dir, b) = fixture().await;
        let mut desktop = peer(&b, "r", false).await;
        let key = CommandKey {
            caller_id: "fixture".into(),
            command_id: "input".into(),
        };
        {
            let mut state = b.state.lock().await;
            let store = state.runtimes["r"].store.clone();
            state
                .ledger
                .begin(key.clone(), b"input", storage::now())
                .unwrap();
            state
                .ledger
                .finish(
                    &key,
                    Status::Accepted {
                        native_result: json!({"written":true}),
                    },
                    storage::now(),
                )
                .unwrap();
            state.questions.reserve(key.clone(),"r",&store,&json!({"id":77,"method":"item/tool/requestUserInput","params":{"threadId":"t","turnId":"u","itemId":"i","questions":[{"id":"q","isSecret":false}]}}),
                &json!({"answers":{"q":{"answers":["PRIVATE-FIXTURE"]}}})).unwrap();
            state.questions.forwarded(&key);
        }
        let value = sample(&b, &mut desktop, idle()).await;
        assert_eq!(value["idle"], false);
        assert_eq!(value["unknownOperations"], 1);
        assert!(!value.to_string().contains("PRIVATE-FIXTURE"));
    }
    #[tokio::test]
    async fn auxiliary_native_event_after_another_idle_probe_invalidates_the_whole_sample() {
        let (_dir, b) = fixture().await;
        let mut desktop = peer(&b, "r", false).await;
        let mut aux = peer(&b, "browser", true).await;
        let (value, ()) = tokio::join!(b.lifecycle_status(), async {
            let first = desktop.recv().await;
            let held = aux.recv().await;
            desktop.reply(&first, idle()).await;
            let before = b.state.lock().await.runtimes["r"].journal.cursor();
            desktop
                .send(json!({"method":"turn/started","params":{"threadId":"t","turn":{"id":"u"}}}))
                .await;
            tokio::time::timeout(Duration::from_secs(1), async {
                loop {
                    if b.state.lock().await.runtimes["r"].journal.cursor() != before {
                        break;
                    }
                    tokio::task::yield_now().await;
                }
            })
            .await
            .unwrap();
            aux.reply(&held, idle()).await;
        });
        assert_eq!(value["idle"], false);
        assert_eq!(value["nativeTasksPreserved"], false);
    }
    #[tokio::test]
    async fn runtime_set_replacement_and_counter_sum_overflow_do_not_pass() {
        let (_dir, b) = fixture().await;
        let mut one = peer(&b, "one", false).await;
        let mut two = peer(&b, "two", false).await;
        let (value, (), ()) = tokio::join!(
            b.lifecycle_status(),
            reply(
                &mut one,
                json!({"protocol":1,"initialized":true,"activeTurns":u64::MAX,"pendingQuestions":0,"pendingWrites":0,"busy":true})
            ),
            reply(
                &mut two,
                json!({"protocol":1,"initialized":true,"activeTurns":1,"pendingQuestions":0,"pendingWrites":0,"busy":true})
            )
        );
        assert_eq!(value["nativeTasksPreserved"], false);
        assert!(value["activeTurns"].is_null());
        let (value, ()) = tokio::join!(b.lifecycle_status(), async {
            let a = one.recv().await;
            let z = two.recv().await;
            {
                let mut state = b.state.lock().await;
                let r = state.runtimes.remove("one").unwrap();
                state.runtimes.insert("replacement".into(), r);
            }
            one.reply(&a, idle()).await;
            two.reply(&z, idle()).await;
        });
        assert_eq!(value["idle"], false);
    }
    #[tokio::test]
    async fn cli_inventory_is_rechecked_and_pagination_is_bounded() {
        for changed in [false, true] {
            let (_dir, b) = fixture().await;
            let (mut connected, mut peer) =
                test_peer::connect("cli", "cli", &b.state_dir, b.native_events.clone()).await;
            connected.native_process = Some(NativeProcess::capture(std::process::id()).unwrap());
            b.add_runtime(connected).await.unwrap();
            let (value, ()) = tokio::join!(b.lifecycle_status(), async {
                let first = peer.recv().await;
                assert_eq!(first["method"], "thread/loaded/list");
                peer.reply(&first, json!({"data":[],"nextCursor":null}))
                    .await;
                let last = peer.recv().await;
                assert_eq!(last["method"], "thread/loaded/list");
                peer.reply(
                    &last,
                    json!({"data":if changed{vec!["new-thread"]}else{vec![]},"nextCursor":null}),
                )
                .await;
            });
            assert_eq!(value["idle"], !changed, "{value}");
            let (value, ()) = tokio::join!(b.lifecycle_status(), async {
                let first = peer.recv().await;
                peer.reply(&first, json!({"data":[],"nextCursor":"repeat"}))
                    .await;
                let repeated = peer.recv().await;
                peer.reply(&repeated, json!({"data":[],"nextCursor":"repeat"}))
                    .await;
            });
            assert_eq!(value["idle"], false);
        }
    }
    #[test]
    fn host_receipt_counts_distinguish_completed_history_from_unconfirmed_effects() {
        let process = NativeProcess::capture(std::process::id()).unwrap();
        let health = pocket_runtime_ipc::Health {
            protocol: pocket_runtime_ipc::PROTOCOL,
            runtime_id: "host".into(),
            generation: "g".into(),
            pid: process.pid,
            native_pid: process.pid,
            host_process: Some(process.clone()),
            native_process: Some(process),
            initialized: true,
            capabilities: Default::default(),
            active_turns: 0,
            pending_requests: 0,
            reconciliation_required: false,
            scoped_admission: 0,
        };
        let mut snapshot = json!({"generation":"g","sequence":0,"reconciliationRequired":false,"runs":[],"pendingRequests":[],"commandCount":2,"answerCount":1});
        assert!(
            host_activity(&health, &snapshot).is_err(),
            "record totals alone never prove completed outcomes"
        );
        snapshot["unconfirmedWrites"] = json!(0);
        snapshot["unknownOperations"] = json!(0);
        let ready = host_activity(&health, &snapshot).unwrap();
        assert_eq!(ready.writes, 0);
        assert_eq!(ready.unknown, 0);
        snapshot["unknownOperations"] = json!(1);
        assert_eq!(host_activity(&health, &snapshot).unwrap().unknown, 1);
        snapshot["unknownOperations"] = json!(0);
        snapshot["unconfirmedWrites"] = json!(1);
        assert_eq!(host_activity(&health, &snapshot).unwrap().writes, 1);
        snapshot["unconfirmedWrites"] = json!(0);
        snapshot["pendingRequests"] =
            json!([{"generation":"old","state":"unknown","requestId":"native"}]);
        let old = host_activity(&health, &snapshot).unwrap();
        assert_eq!(old.pending, 1);
        assert_eq!(old.unknown, 1);
        snapshot["generation"] = json!("replacement");
        assert!(host_activity(&health, &snapshot).is_err());
    }
    #[test]
    fn lifecycle_process_child() {
        if std::env::var_os("POCKET_LIFECYCLE_FIXTURE_CHILD").is_some() {
            let _ = std::io::copy(&mut std::io::stdin(), &mut std::io::sink());
        }
    }

    #[test]
    fn review_host_completed_history_accepts_the_full_bounded_record_budget() {
        let process = NativeProcess::capture(std::process::id()).unwrap();
        let health = pocket_runtime_ipc::Health {
            protocol: pocket_runtime_ipc::PROTOCOL,
            runtime_id: "host".into(), generation: "g".into(),
            pid: process.pid, native_pid: process.pid,
            host_process: Some(process.clone()), native_process: Some(process),
            initialized: true, capabilities: Default::default(),
            active_turns: 0, pending_requests: 0, reconciliation_required: false, scoped_admission: 0,
        };
        for (commands, answers) in [(4096, 0), (4097, 0), (0, 4097), (8192, 0), (0, 8192), (4097, 4095)] {
            let snapshot = json!({"generation":"g","reconciliationRequired":false,"runs":[],"pendingRequests":[],
                "commandCount":commands,"answerCount":answers,"unconfirmedWrites":0,"unknownOperations":0});
            let result = host_activity(&health, &snapshot);
            assert!(result.is_ok(), "completed history {commands}+{answers} is inside the Host's 8192-record budget: {:?}", result.err());
            let activity = result.unwrap();
            assert_eq!((activity.active, activity.pending, activity.writes, activity.unknown), (0, 0, 0, 0));
        }
    }
    #[test]
    fn review_host_history_budget_rejects_excess_and_keeps_authoritative_unknowns() {
        let process = NativeProcess::capture(std::process::id()).unwrap();
        let health = pocket_runtime_ipc::Health {
            protocol: pocket_runtime_ipc::PROTOCOL,
            runtime_id: "host".into(), generation: "g".into(),
            pid: process.pid, native_pid: process.pid,
            host_process: Some(process.clone()), native_process: Some(process),
            initialized: true, capabilities: Default::default(),
            active_turns: 0, pending_requests: 0, reconciliation_required: false, scoped_admission: 0,
        };
        let base=json!({"generation":"g","reconciliationRequired":false,"runs":[],"pendingRequests":[],
            "commandCount":8192,"answerCount":0,"unconfirmedWrites":0,"unknownOperations":0});
        for (commands,answers) in [(8193,0),(0,8193),(4097,4096),(u64::MAX,1)] {
            let mut snapshot=base.clone();snapshot["commandCount"]=json!(commands);snapshot["answerCount"]=json!(answers);
            assert!(host_activity(&health,&snapshot).is_err(),"oversized combined history {commands}+{answers}");
        }
        for field in ["commandCount","answerCount","unconfirmedWrites","unknownOperations"] {
            let mut snapshot=base.clone();snapshot.as_object_mut().unwrap().remove(field);
            assert!(host_activity(&health,&snapshot).is_err(),"missing authoritative field {field}");
            let mut snapshot=base.clone();snapshot[field]=json!("0");
            assert!(host_activity(&health,&snapshot).is_err(),"nonnumeric authoritative field {field}");
        }
        for field in ["unconfirmedWrites","unknownOperations"] {
            let mut snapshot=base.clone();snapshot[field]=json!(8193);
            assert!(host_activity(&health,&snapshot).is_err(),"count exceeds actual record inventory: {field}");
        }
        let mut snapshot=base;snapshot["unconfirmedWrites"]=json!(1);snapshot["unknownOperations"]=json!(1);
        let activity=host_activity(&health,&snapshot).unwrap();
        assert_eq!((activity.writes,activity.unknown),(1,1),"historical capacity cannot erase an unconfirmed effect");
    }
    #[tokio::test]
    async fn native_exit_during_rpc_is_not_hidden_by_an_online_socket() {
        use std::os::windows::process::CommandExt;
        struct Child(std::process::Child);
        impl Drop for Child {
            fn drop(&mut self) {
                let _ = self.0.kill();
                let _ = self.0.wait();
            }
        }
        let mut child = Child(
            std::process::Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "broker::broker_lifecycle::tests::lifecycle_process_child",
                    "--nocapture",
                ])
                .env("POCKET_LIFECYCLE_FIXTURE_CHILD", "1")
                .stdin(std::process::Stdio::piped())
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .creation_flags(0x08000000)
                .spawn()
                .unwrap(),
        );
        let (_dir, b) = fixture().await;
        let mut desktop = peer(&b, "r", false).await;
        b.state
            .lock()
            .await
            .runtimes
            .get_mut("r")
            .unwrap()
            .native_process = Some(NativeProcess::capture(child.0.id()).unwrap());
        let (value, ()) = tokio::join!(b.lifecycle_status(), async {
            let request = desktop.recv().await;
            drop(child.0.stdin.take());
            assert!(child.0.wait().unwrap().success());
            desktop.reply(&request, idle()).await;
        });
        assert_eq!(value["idle"], false);
        assert_eq!(value["nativeTasksPreserved"], false);
    }
}
