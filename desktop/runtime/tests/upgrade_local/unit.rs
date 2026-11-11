use super::*;
use std::collections::BTreeMap;

struct Files;
impl Adapter for Files {
    fn capture(&mut self, pid: u32) -> Result<ProcessIdentity> {
        Ok(ProcessIdentity {
            pid,
            created_at: pid as u64,
            executable: std::env::temp_dir().join("fixture.exe"),
        })
    }
    fn presence(&mut self, _: &ProcessIdentity) -> maintenance::Presence {
        maintenance::Presence::Alive
    }
    fn read(&mut self, p: &Path, limit: u64) -> Result<Vec<u8>> {
        let b = std::fs::read(p)?;
        ensure!(b.len() as u64 <= limit, "limit");
        Ok(b)
    }
    fn rpc(&mut self, _: &Path, _: &str, _: Value) -> Result<Value> {
        bail!("not used")
    }
    fn spawn(&mut self, _: &UpgradeStatus, _: &str, _: &CheckpointRef, _: bool) -> Result<u32> {
        bail!("not used")
    }
}
fn snapshot(state: &Path) -> Value {
    json!({"computerId":"fixture-computer","ledger":{"generation":1,"last_now":1,"entries":[]},"events":{},"runtimes":{},"pending":{},"ownership":[],"questionReceipts":{"records":[]},"protectedFiles":{"config.json":hash(&std::fs::read(state.join("config.json")).unwrap())},"blockers":[]})
}
fn write_image(state: &Path, snap: Value) -> CheckpointRef {
    std::fs::create_dir_all(state.join("handoff")).unwrap();
    let cp = CheckpointRef {
        id: uuid::Uuid::new_v4().to_string(),
        generation: uuid::Uuid::new_v4().to_string(),
        hash: String::new(),
    };
    let b=serde_json::to_vec(&json!({"protocol":1,"id":cp.id,"generation":cp.generation,"snapshot":snap,"summaryHash":hash(&serde_json::to_vec(&snap).unwrap())})).unwrap();
    std::fs::write(state.join("handoff").join(format!("{}.json", cp.id)), &b).unwrap();
    CheckpointRef {
        hash: hash(&b),
        ..cp
    }
}
fn state() -> tempfile::TempDir {
    let d = crate::test_support::tempdir();
    storage::atomic(
        &d.path().join("config.json"),
        &json!({"computerId":"fixture-computer"}),
    )
    .unwrap();
    d
}

fn generation(root: &Path, payload: &str) -> String {
    generation_layout(root, payload, true)
}
fn generation_layout(root: &Path, payload: &str, modern: bool) -> String {
    let mut names = vec![
        "pocket-agent.exe",
        "pocket-supervisor.exe",
        "pocket-bridge.exe",
        "pocket-upgrade.exe",
    ];
    if modern {
        names.extend([
            "pocket-runtime-host.exe",
            "pocket-p2p.dll",
            "datachannel.dll",
            "P2P-THIRD-PARTY.txt",
        ]);
    }
    generation_with_names(root, payload, &names)
}
fn generation_with_names(root: &Path, payload: &str, names: &[&str]) -> String {
    let files: Vec<_> = names
        .iter()
        .map(|n| json!({"path":n,"bytes":payload.len(),"sha256":hash(payload.as_bytes())}))
        .collect();
    let bytes = serde_json::to_vec(&json!({"version":"fixture","files":files})).unwrap();
    let g = hash(&bytes);
    let bin = root.join("bin").join(&g);
    std::fs::create_dir_all(&bin).unwrap();
    std::fs::write(bin.join("windows-manifest.json"), bytes).unwrap();
    for name in names {
        std::fs::write(bin.join(name), payload).unwrap();
    }
    g
}
#[test]
fn complete_new_manifest_checks_optional_components_before_handoff() {
    for component in RUNTIME_PAYLOAD {
        let f = Fake::new();
        std::fs::write(
            f.s.install_root
                .join("bin")
                .join(&f.s.to_generation)
                .join(component),
            b"corrupt",
        )
        .unwrap();
        assert!(super::super::verified_generation(&f.s.install_root, &f.s.to_generation).is_err());
    }
}
#[test]
fn legacy_inventory_remains_valid_but_safe_handoff_is_explicitly_unsupported() {
    let mut f = Fake::new();
    let old = generation_layout(&f.s.install_root, "legacy", false);
    let mut s = f.s.clone();
    s.from_generation = old.clone();
    assert!(super::super::verified_generation(&s.install_root, &old).is_ok());
    assert!(begin(&mut f, &s).unwrap_err().is::<Unsupported>());
    assert!(f.events.is_empty());
    assert!(!LocalBackend.supports_handoff(&s).unwrap());
}
#[test]
fn runtime_transport_group_is_atomic_without_fixing_the_total_file_count() {
    let dir = state();
    let core = [
        "pocket-agent.exe",
        "pocket-supervisor.exe",
        "pocket-bridge.exe",
        "pocket-upgrade.exe",
    ];
    for count in 0..=4 {
        let mut names = core.to_vec();
        names.extend(&RUNTIME_PAYLOAD[..count]);
        let g = generation_with_names(dir.path(), "payload", &names);
        assert_eq!(
            super::super::verified_generation(dir.path(), &g).is_ok(),
            count == 0 || count == 4
        );
    }
    let mut names = core.to_vec();
    names.extend(RUNTIME_PAYLOAD);
    names.push("future-extra.txt");
    let g = generation_with_names(dir.path(), "future", &names);
    assert!(super::super::verified_generation(dir.path(), &g).is_ok());
}
#[test]
fn complete_inventory_refuses_traversal_case_aliases_and_unlisted_payloads() {
    for bad_path in [
        "../outside.exe",
        "CON",
        "pocket-agent.exe:stream",
        "folder\\payload.exe",
        "POCKET-AGENT.EXE",
    ] {
        let f = Fake::new();
        let bin = f.s.install_root.join("bin").join(&f.s.to_generation);
        let mut m: Value = storage::read(&bin.join("windows-manifest.json")).unwrap();
        m["files"]
            .as_array_mut()
            .unwrap()
            .push(json!({"path":bad_path,"sha256":"a".repeat(64),"bytes":0}));
        storage::atomic(&bin.join("windows-manifest.json"), &m).unwrap();
        assert!(verify_generation_inventory(&bin).is_err());
    }
    let f = Fake::new();
    let bin = f.s.install_root.join("bin").join(&f.s.to_generation);
    std::fs::write(bin.join("unlisted.dll"), b"unlisted").unwrap();
    assert!(verify_generation_inventory(&bin).is_err());
}
struct Fake {
    _root: tempfile::TempDir,
    s: UpgradeStatus,
    current: Owner,
    identities: BTreeMap<u32, ProcessIdentity>,
    live: BTreeMap<u32, maintenance::Presence>,
    receipts: BTreeMap<String, Receipt>,
    journal: Option<Journal>,
    remote: bool,
    events: Vec<String>,
    bad_routes: bool,
    bad_identity: bool,
    start_error: bool,
    recovery_checkpoint: Option<CheckpointRef>,
    stale_status_generation: bool,
    fenced_health: bool,
    require_owned_inventory: bool,
    unknown_inventory: bool,
}
impl Fake {
    fn new() -> Self {
        let root = state();
        storage::private_dir(root.path()).unwrap();
        storage::private_dir(&storage::control(root.path())).unwrap();
        let old = generation(root.path(), "old-fixture");
        let new = generation(root.path(), "new-fixture");
        let s = UpgradeStatus {
            schema: 1,
            operation_id: uuid::Uuid::new_v4().to_string(),
            install_root: root.path().into(),
            state_directory: root.path().into(),
            from_generation: old.clone(),
            to_generation: new.clone(),
            phase: super::super::Phase::Requested,
            updated_at: 1,
            checkpoint: None,
            verification: None,
            failure: None,
            bridge_restart_required: true,
        };
        let identity = |pid: u32, g: &str, n: &str| ProcessIdentity {
            pid,
            created_at: pid as u64,
            executable: root.path().join("bin").join(g).join(n),
        };
        let current = Owner {
            supervisor: identity(10, &old, "pocket-supervisor.exe"),
            agent: identity(11, &old, "pocket-agent.exe"),
            generation: old,
            run: uuid::Uuid::new_v4().to_string(),
        };
        let mut identities = BTreeMap::new();
        for p in [&current.supervisor, &current.agent] {
            identities.insert(p.pid, p.clone());
        }
        let coordinator = identity(std::process::id(), &new, "pocket-upgrade.exe");
        identities.insert(coordinator.pid, coordinator);
        let live = identities
            .keys()
            .map(|p| (*p, maintenance::Presence::Alive))
            .collect();
        let mut f = Self {
            _root: root,
            s,
            current,
            identities,
            live,
            receipts: BTreeMap::new(),
            journal: None,
            remote: false,
            events: vec![],
            bad_routes: false,
            bad_identity: false,
            start_error: false,
            recovery_checkpoint: None,
            stale_status_generation: false,
            fenced_health: false,
            require_owned_inventory: false,
            unknown_inventory: false,
        };
        f.heartbeat();
        f
    }
    fn heartbeat(&mut self) {
        storage::atomic(&storage::control(&self.s.state_directory).join("supervisor.json"),&json!({"pid":self.current.supervisor.pid,"childPid":self.current.agent.pid,"runtime":{"generation":self.current.generation,"processCreatedAt":self.current.supervisor.created_at,"executableSha256":manifest_digest(&self.s,&self.current.generation,"pocket-supervisor.exe").unwrap()},"maintenanceProtocol":1})).unwrap();
    }
    fn cp(&mut self) -> CheckpointRef {
        let mut snap = snapshot(&self.s.state_directory);
        if self.unknown_inventory {
            snap["ledger"]["entries"] = json!([{"status":"Unknown"}]);
        }
        let mut cp = write_image(&self.s.state_directory, snap.clone());
        cp.generation = self.current.run.clone();
        let v = json!({"protocol":1,"id":cp.id,"generation":cp.generation,"snapshot":snap,"summaryHash":hash(&serde_json::to_vec(&snap).unwrap())});
        let bytes = serde_json::to_vec(&v).unwrap();
        cp.hash = hash(&bytes);
        std::fs::write(
            self.s
                .state_directory
                .join("handoff")
                .join(format!("{}.json", cp.id)),
            bytes,
        )
        .unwrap();
        cp
    }
    fn persist_agent(&self) {
        let operations: BTreeMap<_, _> = self
            .receipts
            .iter()
            .map(|(id, r)| {
                (
                    id,
                    json!({"receipt":r,"priorRemote":false,"releaseInput":null}),
                )
            })
            .collect();
        storage::atomic(&self.s.state_directory.join("handoff/state.json"),&json!({"protocol":1,"generation":self.current.run,"operations":operations,"recoveryCheckpoint":self.recovery_checkpoint})).unwrap();
    }
    fn capabilities_value(&self, run: &str) -> Value {
        json!({"protocol":1,"supported":true,"generation":run,"owner":"pocket-agent","methods":METHODS,"checkpoint":"sha256","shutdown":"after_reply_flush","writeFence":true,"preservesIdentity":true})
    }
}
impl Adapter for Fake {
    fn capture(&mut self, pid: u32) -> Result<ProcessIdentity> {
        ensure!(
            self.live.get(&pid) == Some(&maintenance::Presence::Alive),
            "gone"
        );
        let mut p = self.identities.get(&pid).context("unknown")?.clone();
        if self.bad_identity && pid == 11 {
            p.created_at += 1;
        }
        Ok(p)
    }
    fn presence(&mut self, p: &ProcessIdentity) -> maintenance::Presence {
        if self.live.get(&p.pid) == Some(&maintenance::Presence::Unknown) {
            return maintenance::Presence::Unknown;
        }
        if self
            .identities
            .get(&p.pid)
            .is_some_and(|actual| actual != p)
        {
            return maintenance::Presence::Gone;
        }
        self.live
            .get(&p.pid)
            .copied()
            .unwrap_or(maintenance::Presence::Unknown)
    }
    fn read(&mut self, p: &Path, n: u64) -> Result<Vec<u8>> {
        Files.read(p, n)
    }
    fn tick(&mut self) {}
    fn locks_free(&mut self, _: &UpgradeStatus) -> Result<()> {
        ensure!(
            self.live.get(&self.current.agent.pid) == Some(&maintenance::Presence::Gone)
                && self.live.get(&self.current.supervisor.pid)
                    == Some(&maintenance::Presence::Gone),
            "owner alive"
        );
        self.events.push("locks_free".into());
        Ok(())
    }
    fn submit(&mut self, _: &Path, r: &Request) -> Result<()> {
        self.events.push(format!("maintenance/{:?}", r.action));
        let mut enter = r.clone();
        enter.action = Action::Enter;
        let phase = match r.action {
            Action::Enter | Action::Recover => maintenance::Phase::Owned,
            Action::Cancel => maintenance::Phase::Cancelled,
            Action::Release => {
                ensure!(
                    self.live.get(&self.current.agent.pid) == Some(&maintenance::Presence::Gone),
                    "child alive"
                );
                self.live
                    .insert(self.current.supervisor.pid, maintenance::Presence::Gone);
                maintenance::Phase::Released
            }
        };
        self.journal = Some(Journal {
            request: enter,
            phase,
        });
        Ok(())
    }
    fn ack(&mut self, _: &Path) -> Result<Option<Journal>> {
        Ok(self.journal.clone())
    }
    fn rpc(&mut self, _: &Path, method: &str, p: Value) -> Result<Value> {
        self.events.push(method.into());
        match method {
            "status" => {
                let run = if self.stale_status_generation {
                    uuid::Uuid::new_v4().to_string()
                } else {
                    self.current.run.clone()
                };
                let accepting = !self.fenced_health
                    && !self.receipts.values().any(|r| {
                        r.generation == self.current.run
                            && !matches!(r.phase.as_str(), "cancelled" | "released")
                    });
                Ok(
                    json!({"protocol":1,"computerId":"fixture-computer","updatedAt":storage::now(),"remoteControlProtocol":1,"remoteEnabled":self.remote,"acceptingWrites":accepting,"handoffCapabilities":self.capabilities_value(&run),"helperRuntime":{"pid":self.current.agent.pid,"processIdentity":{"pid":self.current.agent.pid,"createdAt":self.current.agent.created_at},"generation":self.current.generation,"executableSha256":manifest_digest(&self.s,&self.current.generation,"pocket-agent.exe").unwrap(),"controlProtocol":1}}),
                )
            }
            "routes/read" => Ok(
                json!({"routePreferences":{"policy":if self.bad_routes && self.current.generation==self.s.to_generation{"changed"}else{"lan"},"routes":[]}}),
            ),
            "upgrade/capabilities" => Ok(self.capabilities_value(&self.current.run)),
            "upgrade/quiesce" => {
                ensure!(
                    !self.require_owned_inventory
                        || self.journal.as_ref().is_some_and(|j| {
                            j.phase == maintenance::Phase::Owned
                                && j.request.supervisor == self.current.supervisor
                        }),
                    "Recovery inventory requires owned maintenance"
                );
                ensure!(
                    p["checkpoint"].is_null() && p["fromGeneration"] == self.current.run,
                    "wire"
                );
                let cp = self.cp();
                let r = Receipt {
                    protocol: 1,
                    id: p["id"].as_str().unwrap().into(),
                    generation: self.current.run.clone(),
                    phase: "ready".into(),
                    safe_to_exit: true,
                    native_tasks_preserved: true,
                    remote_enabled: false,
                    accepting_writes: false,
                    unconfirmed_writes: 0,
                    blockers: vec![],
                    checkpoint: Some(cp),
                    shutdown_requested: false,
                };
                self.receipts.insert(r.id.clone(), r.clone());
                self.remote = false;
                self.persist_agent();
                Ok(json!(r))
            }
            "upgrade/status" => self
                .receipts
                .get(p["id"].as_str().unwrap())
                .map(|r| json!(r))
                .context("missing operation"),
            "upgrade/release" => {
                let id = p["id"].as_str().unwrap().to_owned();
                let old = self.receipts.get(&id).context("missing")?.clone();
                ensure!(p["checkpoint"] == json!(old.checkpoint), "wrong input");
                let cp = self.cp();
                self.recovery_checkpoint = Some(cp.clone());
                let mut r = old;
                r.phase = "released".into();
                r.checkpoint = Some(cp);
                r.shutdown_requested = true;
                self.receipts.insert(id, r.clone());
                self.persist_agent();
                self.live
                    .insert(self.current.agent.pid, maintenance::Presence::Gone);
                Ok(json!(r))
            }
            "upgrade/cancel" => {
                let r = self
                    .receipts
                    .get_mut(p["id"].as_str().unwrap())
                    .context("missing")?;
                ensure!(
                    !matches!(r.phase.as_str(), "released" | "release_pending"),
                    "late cancel"
                );
                r.phase = "cancelled".into();
                r.safe_to_exit = false;
                r.accepting_writes = true;
                r.remote_enabled = false;
                self.remote = false;
                let reply = json!(r);
                self.persist_agent();
                Ok(reply)
            }
            _ => bail!("unexpected method"),
        }
    }
    fn spawn(&mut self, s: &UpgradeStatus, g: &str, cp: &CheckpointRef, _: bool) -> Result<u32> {
        self.events.push(format!("spawn/{g}"));
        if self.start_error {
            bail!("fixture start failed");
        }
        cp.validate()?;
        let pid = if g == s.to_generation { 20 } else { 30 };
        let make = |pid, n: &str| ProcessIdentity {
            pid,
            created_at: pid as u64,
            executable: s.install_root.join("bin").join(g).join(n),
        };
        self.current = Owner {
            supervisor: make(pid, "pocket-supervisor.exe"),
            agent: make(pid + 1, "pocket-agent.exe"),
            generation: g.into(),
            run: uuid::Uuid::new_v4().to_string(),
        };
        for p in [&self.current.supervisor, &self.current.agent] {
            self.identities.insert(p.pid, p.clone());
            self.live.insert(p.pid, maintenance::Presence::Alive);
        }
        self.persist_agent();
        self.heartbeat();
        Ok(pid)
    }
}
#[test]
fn source_status_capabilities_and_admission_must_match_the_live_owner() {
    for fenced in [false, true] {
        let mut f = Fake::new();
        let s = f.s.clone();
        f.fenced_health = fenced;
        f.stale_status_generation = !fenced;
        assert!(begin(&mut f, &s).is_err());
        assert!(!f.events.iter().any(|v| v.starts_with("maintenance/")));
    }
}
#[test]
fn startup_request_is_consumed_by_durable_adoption_even_without_a_health_poll() {
    let mut f = Fake::new();
    let s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    d.ready = Some(ready(&mut f, &s, &d).unwrap());
    f.save(&s, &d).unwrap();
    stopped(&mut f, &s, &mut d).unwrap();
    let cp = d.final_checkpoint.clone().unwrap();
    assert!(startup_request_pending(&mut f, &s.state_directory, &cp).unwrap());
    // This is the exact disk commit complete_successor makes, with no status
    // observation by the supervisor. Later business writes must not reapply cp.
    f.current.run = uuid::Uuid::new_v4().to_string();
    f.persist_agent();
    std::fs::write(
        s.state_directory.join("config.json"),
        b"new business configuration",
    )
    .unwrap();
    assert!(!startup_request_pending(&mut f, &s.state_directory, &cp).unwrap());
}
#[test]
fn normal_successor_respawn_may_have_a_new_run_before_its_first_journal_write() {
    let mut f = Fake::new();
    let mut s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    d.ready = Some(ready(&mut f, &s, &d).unwrap());
    s.checkpoint = Some(checkpoint(&s, d.ready.as_ref().unwrap()));
    f.save(&s, &d).unwrap();
    stopped(&mut f, &s, &mut d).unwrap();
    launch(&mut f, &s, &mut d, &s.to_generation).unwrap();
    // Handoff::open(new run) updates memory; only a later quiesce/write persists
    // that run. The durable predecessor adoption has already succeeded.
    f.current.run = uuid::Uuid::new_v4().to_string();
    d.current = f.current.clone();
    d.active_id = uuid::Uuid::new_v4().to_string();
    assert!(verify(&mut f, &s, &mut d, &s.to_generation).is_ok());
    assert_eq!(
        agent_record(&mut f, &s.state_directory).unwrap().generation,
        d.current.run
    );
}
#[test]
fn startup_request_remains_pending_until_flushed_receipt_and_adoption_are_checked() {
    let mut f = Fake::new();
    let s = f.s.clone();
    let cp = f.cp();
    let id = uuid::Uuid::new_v4().to_string();
    f.receipts.insert(
        id.clone(),
        Receipt {
            protocol: 1,
            id: id.clone(),
            generation: cp.generation.clone(),
            phase: "release_pending".into(),
            safe_to_exit: true,
            native_tasks_preserved: true,
            remote_enabled: false,
            accepting_writes: false,
            unconfirmed_writes: 0,
            blockers: vec![],
            checkpoint: Some(cp.clone()),
            shutdown_requested: false,
        },
    );
    f.recovery_checkpoint = Some(cp.clone());
    f.persist_agent();
    assert!(startup_request_pending(&mut f, &s.state_directory, &cp).is_err());
    let r = f.receipts.get_mut(&id).unwrap();
    r.phase = "released".into();
    r.shutdown_requested = true;
    f.persist_agent();
    assert!(startup_request_pending(&mut f, &s.state_directory, &cp).unwrap());
    let mut record: Value = serde_json::from_slice(
        &std::fs::read(s.state_directory.join("handoff/state.json")).unwrap(),
    )
    .unwrap();
    record["generation"] = json!("not-a-run-id");
    storage::atomic(&s.state_directory.join("handoff/state.json"), &record).unwrap();
    assert!(startup_request_pending(&mut f, &s.state_directory, &cp).is_err());
}
#[test]
fn superseded_checkpoint_env_is_scrubbed_so_main_can_recover_the_latest_release() {
    let mut f = Fake::new();
    let s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    d.ready = Some(ready(&mut f, &s, &d).unwrap());
    f.save(&s, &d).unwrap();
    stopped(&mut f, &s, &mut d).unwrap();
    let old = d.final_checkpoint.unwrap();
    f.current.run = uuid::Uuid::new_v4().to_string();
    let new = f.cp();
    let id = uuid::Uuid::new_v4().to_string();
    let mut r = f.receipts.values().next().unwrap().clone();
    r.id = id.clone();
    r.generation = new.generation.clone();
    r.checkpoint = Some(new.clone());
    f.receipts.insert(id, r);
    f.recovery_checkpoint = Some(new.clone());
    f.persist_agent();
    assert!(!startup_request_pending(&mut f, &s.state_directory, &old).unwrap());
    assert!(startup_request_pending(&mut f, &s.state_directory, &new).unwrap());
}
#[test]
fn modern_inventory_cannot_declare_an_empty_runtime_component() {
    let f = Fake::new();
    let bin = f.s.install_root.join("bin").join(&f.s.to_generation);
    let mut m: Value = storage::read(&bin.join("windows-manifest.json")).unwrap();
    std::fs::write(bin.join("pocket-p2p.dll"), []).unwrap();
    let entry = m["files"]
        .as_array_mut()
        .unwrap()
        .iter_mut()
        .find(|e| e["path"] == "pocket-p2p.dll")
        .unwrap();
    entry["bytes"] = json!(0);
    entry["sha256"] = json!(hash(&[]));
    storage::atomic(&bin.join("windows-manifest.json"), &m).unwrap();
    assert!(verify_generation_inventory(&bin).is_err());
}
#[test]
fn completed_rollback_cannot_use_a_fenced_or_stale_status_as_health() {
    let mut f = Fake::new();
    let s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    d.ready = Some(ready(&mut f, &s, &d).unwrap());
    f.save(&s, &d).unwrap();
    rollback(&mut f, &s).unwrap();
    f.fenced_health = true;
    assert!(rollback(&mut f, &s).is_err());
    f.fenced_health = false;
    f.stale_status_generation = true;
    assert!(rollback(&mut f, &s).is_err());
}
#[test]
fn main_status_shape_checks_creation_hash_generation_capabilities_and_gate_types() {
    let mut f = Fake::new();
    let s = f.s.clone();
    let agent = f.current.agent.clone();
    let run = f.current.run.clone();
    let generation = f.current.generation.clone();
    let health = f.rpc(&s.state_directory, "status", json!({})).unwrap();
    validate_health(&s, &agent, &generation, &run, &health).unwrap();
    for n in 0..6 {
        let mut bad = health.clone();
        match n {
            0 => bad["helperRuntime"]["processIdentity"]["createdAt"] = json!(agent.created_at + 1),
            1 => bad["helperRuntime"]["generation"] = json!("a".repeat(64)),
            2 => bad["helperRuntime"]["executableSha256"] = json!("b".repeat(64)),
            3 => bad["handoffCapabilities"]["generation"] = json!(uuid::Uuid::new_v4().to_string()),
            4 => bad["acceptingWrites"] = json!("true"),
            _ => {
                bad["helperRuntime"]["processIdentity"] = Value::Null;
                bad["helperRuntime"]["processCreatedAt"] = json!(agent.created_at);
            }
        }
        assert!(validate_health(&s, &agent, &generation, &run, &bad).is_err());
    }
}
#[test]
fn full_local_sequence_orders_maintenance_final_bytes_exit_locks_and_verification() {
    let mut f = Fake::new();
    let s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    let cp = ready(&mut f, &s, &d).unwrap();
    d.ready = Some(cp.clone());
    f.save(&s, &d).unwrap();
    let mut s = s;
    s.checkpoint = Some(checkpoint(&s, &cp));
    stopped(&mut f, &s, &mut d).unwrap();
    assert_ne!(d.final_checkpoint.as_ref().unwrap().id, cp.id);
    launch(&mut f, &s, &mut d, &s.to_generation).unwrap();
    assert!(
        verify(&mut f, &s, &mut d, &s.to_generation)
            .unwrap()
            .routes_read
    );
    let position = |m: &str| f.events.iter().position(|v| v == m).unwrap();
    assert!(position("maintenance/Enter") < position("upgrade/quiesce"));
    assert!(position("upgrade/release") < position("maintenance/Release"));
    assert!(position("maintenance/Release") < position("locks_free"));
    assert!(!f.remote);
    assert_eq!(d.stage, "verified");
}
#[test]
fn candidate_routes_failure_gracefully_rolls_back_and_repeated_rollback_is_read_only() {
    let mut f = Fake::new();
    let mut s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    let cp = ready(&mut f, &s, &d).unwrap();
    d.ready = Some(cp.clone());
    s.checkpoint = Some(checkpoint(&s, &cp));
    f.save(&s, &d).unwrap();
    stopped(&mut f, &s, &mut d).unwrap();
    launch(&mut f, &s, &mut d, &s.to_generation).unwrap();
    f.bad_routes = true;
    assert!(verify(&mut f, &s, &mut d, &s.to_generation).is_err());
    assert_eq!(rollback(&mut f, &s).unwrap().generation, s.from_generation);
    let before = f.events.len();
    rollback(&mut f, &s).unwrap();
    assert!(!f.events[before..]
        .iter()
        .any(|v| v.starts_with("spawn/") || v == "upgrade/release"));
}
#[test]
fn source_pid_reuse_and_unknown_liveness_never_start_another_generation() {
    let mut f = Fake::new();
    let s = f.s.clone();
    f.bad_identity = true;
    assert!(begin(&mut f, &s).is_err());
    assert!(!f.events.iter().any(|v| v.starts_with("maintenance/")));
    f.bad_identity = false;
    begin(&mut f, &s).unwrap();
    f.live.insert(11, maintenance::Presence::Unknown);
    assert!(rollback(&mut f, &s).is_err());
    assert!(!f.events.iter().any(|v| v.starts_with("spawn/")));
}
#[test]
fn interrupted_ready_cancels_old_fence_and_repeated_recovery_never_spawns() {
    let mut f = Fake::new();
    let mut s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    let cp = ready(&mut f, &s, &d).unwrap();
    d.ready = Some(cp.clone());
    s.checkpoint = Some(checkpoint(&s, &cp));
    save_stage(&mut f, &s, &mut d, "ready").unwrap();
    assert_eq!(rollback(&mut f, &s).unwrap().generation, s.from_generation);
    assert!(!f.events.iter().any(|v| v.starts_with("spawn/")));
    rollback(&mut f, &s).unwrap();
}
#[test]
fn recovery_inventory_keeps_maintenance_until_health_and_routes_are_verified() {
    let mut f = Fake::new();
    let s = f.s.clone();
    begin(&mut f, &s).unwrap();
    f.require_owned_inventory = true;
    assert!(rollback(&mut f, &s).unwrap().native_tasks_preserved);
    let fence = f
        .events
        .iter()
        .rposition(|v| v == "upgrade/quiesce")
        .unwrap();
    let cancel = f
        .events
        .iter()
        .rposition(|v| v == "maintenance/Cancel")
        .unwrap();
    let routes = f.events.iter().rposition(|v| v == "routes/read").unwrap();
    assert!(fence < routes && routes < cancel);
    assert!(load(&mut f, &s).unwrap().ready.is_some());
}
#[test]
fn recovery_rechecks_live_unknown_writes_instead_of_reusing_a_ready_snapshot() {
    let mut f = Fake::new();
    let s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    d.ready = Some(ready(&mut f, &s, &d).unwrap());
    save_stage(&mut f, &s, &mut d, "ready").unwrap();
    f.unknown_inventory = true;
    assert!(
        rollback(&mut f, &s).is_err(),
        "stale ready image cannot prove current safety"
    );
    assert_eq!(f.journal.as_ref().unwrap().phase, maintenance::Phase::Owned);
    assert!(!f
        .events
        .iter()
        .any(|v| v.starts_with("spawn/") || v == "upgrade/release"));
}
#[test]
fn lost_release_recovery_checks_final_snapshot_against_the_ready_snapshot() {
    let mut f = Fake::new();
    let mut s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    let cp = ready(&mut f, &s, &d).unwrap();
    d.ready = Some(cp.clone());
    s.checkpoint = Some(checkpoint(&s, &cp));
    save_stage(&mut f, &s, &mut d, "releasing_agent").unwrap();
    let run = f.current.run.clone();
    f.rpc(
        &s.state_directory,
        "upgrade/release",
        params(&d.active_id, &run, Some(&cp)),
    )
    .unwrap();
    // A validly hashed final image still must preserve the original ledger.
    let mut changed = snapshot(&s.state_directory);
    changed["ledger"]["generation"] = json!(2);
    let mut final_cp = write_image(&s.state_directory, changed.clone());
    final_cp.generation = run;
    let bytes = serde_json::to_vec(&json!({"protocol":1,"id":final_cp.id,"generation":final_cp.generation,"snapshot":changed,"summaryHash":hash(&serde_json::to_vec(&changed).unwrap())})).unwrap();
    final_cp.hash = hash(&bytes);
    std::fs::write(
        s.state_directory
            .join("handoff")
            .join(format!("{}.json", final_cp.id)),
        bytes,
    )
    .unwrap();
    f.receipts.get_mut(&d.active_id).unwrap().checkpoint = Some(final_cp.clone());
    f.recovery_checkpoint = Some(final_cp);
    f.persist_agent();
    let before = f.events.len();
    assert!(
        rollback(&mut f, &s).is_err(),
        "lost response cannot bypass ready/final comparison"
    );
    assert!(!f.events[before..]
        .iter()
        .any(|v| v.starts_with("spawn/") || v == "upgrade/release" || v == "maintenance/Release"));
    assert_eq!(f.journal.as_ref().unwrap().phase, maintenance::Phase::Owned);
}
#[test]
fn unflushed_release_recovery_never_resends_release_or_starts_an_owner() {
    let mut f = Fake::new();
    let s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    let cp = ready(&mut f, &s, &d).unwrap();
    d.ready = Some(cp.clone());
    save_stage(&mut f, &s, &mut d, "releasing_agent").unwrap();
    let receipt = f.receipts.get_mut(&d.active_id).unwrap();
    receipt.phase = "release_pending".into();
    f.persist_agent();
    let before = f.events.len();
    assert!(rollback(&mut f, &s).is_err());
    assert!(!f.events[before..]
        .iter()
        .any(|v| v == "upgrade/release" || v.starts_with("spawn/")));
    assert_eq!(f.journal.as_ref().unwrap().phase, maintenance::Phase::Owned);
}
#[test]
fn interrupted_flushed_release_recovers_final_bytes_from_agent_journal() {
    let mut f = Fake::new();
    let mut s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    let cp = ready(&mut f, &s, &d).unwrap();
    d.ready = Some(cp.clone());
    s.checkpoint = Some(checkpoint(&s, &cp));
    save_stage(&mut f, &s, &mut d, "releasing_agent").unwrap();
    let run = f.current.run.clone();
    f.rpc(
        &s.state_directory,
        "upgrade/release",
        params(&d.active_id, &run, Some(&cp)),
    )
    .unwrap();
    assert_eq!(rollback(&mut f, &s).unwrap().generation, s.from_generation);
    assert!(f.events.iter().any(|v| v == "maintenance/Release"));
}
#[test]
fn known_spawn_failure_can_restore_old_generation_but_unrecorded_spawn_cannot() {
    let mut f = Fake::new();
    let mut s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    let cp = ready(&mut f, &s, &d).unwrap();
    d.ready = Some(cp.clone());
    s.checkpoint = Some(checkpoint(&s, &cp));
    f.save(&s, &d).unwrap();
    stopped(&mut f, &s, &mut d).unwrap();
    f.start_error = true;
    assert!(launch(&mut f, &s, &mut d, &s.to_generation).is_err());
    f.start_error = false;
    assert_eq!(rollback(&mut f, &s).unwrap().generation, s.from_generation);
    save_stage(&mut f, &s, &mut d, "spawning_new").unwrap();
    d.spawn_pid = None;
    d.spawn_identity = None;
    f.save(&s, &d).unwrap();
    let before = f.events.len();
    assert!(rollback(&mut f, &s).is_err());
    assert!(!f.events[before..].iter().any(|v| v.starts_with("spawn/")));
}
#[test]
fn release_proof_requires_matching_final_ref_and_flushed_durable_receipt() {
    let mut f = Fake::new();
    let s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    let cp = f.cp();
    d.final_checkpoint = Some(cp.clone());
    f.live
        .insert(d.current.agent.pid, maintenance::Presence::Gone);
    let r = Receipt {
        protocol: 1,
        id: d.active_id.clone(),
        generation: d.current.run.clone(),
        phase: "release_pending".into(),
        safe_to_exit: true,
        native_tasks_preserved: true,
        remote_enabled: false,
        accepting_writes: false,
        unconfirmed_writes: 0,
        blockers: vec![],
        checkpoint: Some(cp),
        shutdown_requested: false,
    };
    f.receipts.insert(r.id.clone(), r);
    f.persist_agent();
    let req = request(&s, &d, Action::Release);
    assert!(release_proven_with(&mut f, &s, &d, &req).is_err());
    let r = f.receipts.get_mut(&d.active_id).unwrap();
    r.phase = "released".into();
    r.shutdown_requested = true;
    f.persist_agent();
    release_proven_with(&mut f, &s, &d, &req).unwrap();
    let mut wrong = req;
    wrong.coordinator.created_at += 1;
    assert!(release_proven_with(&mut f, &s, &d, &wrong).is_err());
}
#[test]
fn retained_event_gaps_and_wrong_cursor_identity_cannot_be_checkpointed() {
    let dir = state();
    let mut snap = snapshot(dir.path());
    let cursor = json!({"runtime":{"computer_id":"fixture-computer","runtime_id":"r"},"epoch":uuid::Uuid::new_v4().to_string(),"sequence":2});
    snap["events"] = json!({"r":{"cursor":cursor,"floor":0,"events":[{"cursor":cursor,"event":{}}],"hostCursor":null}});
    let cp = write_image(dir.path(), snap);
    assert!(image(&mut Files, dir.path(), &cp, "fixture-computer").is_err());
}
#[test]
fn successor_control_health_without_durable_adoption_is_not_verification() {
    let mut f = Fake::new();
    let mut s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    let cp = ready(&mut f, &s, &d).unwrap();
    d.ready = Some(cp.clone());
    s.checkpoint = Some(checkpoint(&s, &cp));
    f.save(&s, &d).unwrap();
    stopped(&mut f, &s, &mut d).unwrap();
    launch(&mut f, &s, &mut d, &s.to_generation).unwrap();
    f.recovery_checkpoint = None;
    f.persist_agent();
    assert!(verify(&mut f, &s, &mut d, &s.to_generation).is_err());
}
#[test]
fn reads_actual_checkpoint_bytes_and_rejects_tampering() {
    let dir = state();
    let cp = write_image(dir.path(), snapshot(dir.path()));
    image(&mut Files, dir.path(), &cp, "fixture-computer").unwrap();
    std::fs::write(
        dir.path().join("handoff").join(format!("{}.json", cp.id)),
        b"{}",
    )
    .unwrap();
    assert!(image(&mut Files, dir.path(), &cp, "fixture-computer").is_err());
}
#[test]
fn rejects_unknown_writes_even_if_receipt_claims_zero() {
    let dir = state();
    for status in ["Submitted", "Unknown"] {
        let mut snap = snapshot(dir.path());
        snap["ledger"]["entries"] = json!([{"status":status}]);
        let cp = write_image(dir.path(), snap);
        assert!(image(&mut Files, dir.path(), &cp, "fixture-computer").is_err());
    }
}
#[test]
fn rejects_unconfirmed_questions_and_pending_responses() {
    let dir = state();
    for pending in [true, false] {
        let mut snap = snapshot(dir.path());
        if pending {
            snap["pending"] = json!({"request":{"command":"original"}});
        } else {
            snap["questionReceipts"] = json!({"records":[{"phase":"unknown"}]});
        }
        let cp = write_image(dir.path(), snap);
        assert!(image(&mut Files, dir.path(), &cp, "fixture-computer").is_err());
    }
}
#[test]
fn protected_paths_and_file_hashes_are_independently_checked() {
    let dir = state();
    let mut snap = snapshot(dir.path());
    snap["protectedFiles"]["../outside"] = json!("a".repeat(64));
    let cp = write_image(dir.path(), snap);
    assert!(image(&mut Files, dir.path(), &cp, "fixture-computer").is_err());
    let cp = write_image(dir.path(), snapshot(dir.path()));
    std::fs::write(dir.path().join("config.json"), b"changed").unwrap();
    assert!(image(&mut Files, dir.path(), &cp, "fixture-computer").is_err());
}
#[test]
fn canonical_refs_reject_pid_like_uuid_case_and_paths() {
    assert!(!canonical_uuid("00000000-0000-0000-0000-000000000000"));
    assert!(!canonical_uuid("550E8400-E29B-41D4-A716-446655440000"));
    for id in ["../../outside", "550e8400e29b41d4a716446655440000"] {
        assert!(CheckpointRef {
            id: id.into(),
            generation: uuid::Uuid::new_v4().to_string(),
            hash: "a".repeat(64)
        }
        .validate()
        .is_err());
    }
}
#[test]
fn summary_hash_preserves_nested_struct_field_order() {
    let bytes=br#"{"protocol":1,"snapshot":{"computerId":"c","events":{"r":{"cursor":{"runtime":{"computer_id":"c","runtime_id":"r"},"epoch":"x","sequence":1}}}},"summaryHash":"x"}"#;
    assert_eq!(snapshot_bytes(bytes).unwrap(),br#"{"computerId":"c","events":{"r":{"cursor":{"runtime":{"computer_id":"c","runtime_id":"r"},"epoch":"x","sequence":1}}}}"#);
}
#[test]
fn successor_checks_native_creation_store_host_cursor_and_ledger() {
    let dir = state();
    let mut old = snapshot(dir.path());
    old["runtimes"] = json!({"r":{"runtimeId":"r","store":"store","nativeProcess":{"pid":1,"createdAt":1},"hostProcess":{"pid":2,"createdAt":2},"lifecycle":"managed_host_external","hostCursor":{"generation":"host","after":10}}});
    successor(&old, &old).unwrap();
    for key in [
        "store",
        "nativeProcess",
        "hostProcess",
        "lifecycle",
        "hostCursor",
    ] {
        let mut new = old.clone();
        new["runtimes"]["r"][key] = json!("changed");
        assert!(successor(&old, &new).is_err());
    }
    let mut new = old.clone();
    new["ledger"]["generation"] = json!(2);
    assert!(successor(&old, &new).is_err());
}
#[test]
fn receipt_cannot_authorize_exit_with_unknown_writes_or_unverified_tasks() {
    let cp = CheckpointRef {
        id: uuid::Uuid::new_v4().to_string(),
        generation: uuid::Uuid::new_v4().to_string(),
        hash: "a".repeat(64),
    };
    let r = Receipt {
        protocol: 1,
        id: uuid::Uuid::new_v4().to_string(),
        generation: cp.generation.clone(),
        phase: "ready".into(),
        safe_to_exit: true,
        native_tasks_preserved: true,
        remote_enabled: false,
        accepting_writes: false,
        unconfirmed_writes: 0,
        blockers: vec![],
        checkpoint: Some(cp),
        shutdown_requested: false,
    };
    r.safe().unwrap();
    let mut pending = r.clone();
    pending.unconfirmed_writes = 1;
    assert!(pending.safe().is_err());
    let mut unverified = r.clone();
    unverified.native_tasks_preserved = false;
    assert!(unverified.safe().is_err());
    let mut open = r;
    open.accepting_writes = true;
    assert!(open.safe().is_err());
}
#[cfg(windows)]
#[test]
fn authenticated_agent_files_allow_system_without_relaxing_maintenance_acl() {
    use std::os::windows::process::CommandExt;
    let dir = state();
    storage::private_dir(dir.path()).unwrap();
    let path = dir.path().join("agent-private.json");
    storage::atomic(&path, &json!({"protocol":1})).unwrap();
    let grant = |principal: &str| {
        let output = Command::new("icacls.exe")
            .arg(&path)
            .args(["/grant", principal])
            .creation_flags(0x08000000)
            .output()
            .unwrap();
        assert!(output.status.success());
    };
    grant("*S-1-5-18:F");
    assert!(
        maintenance::open_private(&path).is_err(),
        "mailbox policy remains user only"
    );
    assert!(
        Windows.read(&path, 1024).is_ok(),
        "canonical agent ACL permits SYSTEM"
    );
    grant("*S-1-1-0:R");
    assert!(
        Windows.read(&path, 1024).is_err(),
        "ordinary other principals remain rejected"
    );
}
#[cfg(windows)]
#[test]
fn actual_private_framed_transport_is_authenticated_and_read_only() {
    let dir = state();
    storage::private_dir(dir.path()).unwrap();
    let token = "a".repeat(64);
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    storage::atomic(
        &dir.path().join("control.json"),
        &json!({"protocol":1,"token":token,"port":listener.local_addr().unwrap().port()}),
    )
    .unwrap();
    let worker = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        let mut n = [0; 4];
        socket.read_exact(&mut n).unwrap();
        let mut b = vec![0; u32::from_be_bytes(n) as usize];
        socket.read_exact(&mut b).unwrap();
        let v: Value = serde_json::from_slice(&b).unwrap();
        assert_eq!(v["token"], token);
        assert_eq!(v["method"], "upgrade/capabilities");
        assert_eq!(v["params"], json!({}));
        let b = serde_json::to_vec(&json!({"result":{"protocol":1}})).unwrap();
        socket.write_all(&(b.len() as u32).to_be_bytes()).unwrap();
        socket.write_all(&b).unwrap();
        socket.flush().unwrap();
    });
    assert_eq!(
        Windows
            .rpc(dir.path(), "upgrade/capabilities", json!({}))
            .unwrap(),
        json!({"protocol":1})
    );
    worker.join().unwrap();
}

#[cfg(windows)]
#[test]
fn isolated_process_fixture() {
    let Some(root) = std::env::var_os("POCKET_UPGRADE_PROCESS_FIXTURE") else {
        return;
    };
    let root = PathBuf::from(root);
    storage::private_dir(&root).unwrap();
    storage::private_dir(&root.join("desktop")).unwrap();
    storage::private_dir(&root.join("owner")).unwrap();
    let _user = storage::Lock::acquire(&root.join("owner/supervisor.lock")).unwrap();
    let _state = storage::Lock::acquire(&root.join("desktop/supervisor.lock")).unwrap();
    let _agent = storage::Lock::acquire(&root.join("agent.lock")).unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    storage::atomic(
        &root.join("control.json"),
        &json!({"protocol":1,"port":listener.local_addr().unwrap().port(),"token":"a".repeat(64)}),
    )
    .unwrap();
    let end = Instant::now() + Duration::from_secs(12);
    loop {
        if let Ok((mut stream, _)) = listener.accept() {
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut size = [0; 4];
            stream.read_exact(&mut size).unwrap();
            let mut b = vec![0; u32::from_be_bytes(size) as usize];
            stream.read_exact(&mut b).unwrap();
            let r: Value = serde_json::from_slice(&b).unwrap();
            assert_eq!(r["token"], "a".repeat(64));
            assert_eq!(r["method"], "upgrade/release");
            let b = serde_json::to_vec(&json!({"result":{"flushed":true}})).unwrap();
            stream.write_all(&(b.len() as u32).to_be_bytes()).unwrap();
            stream.write_all(&b).unwrap();
            stream.flush().unwrap();
            return;
        }
        if Instant::now() >= end {
            panic!("Timed out waiting for authenticated fixture release");
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}
#[cfg(windows)]
#[test]
fn real_win32_creation_time_natural_exit_and_double_lock_handoff() {
    let dir = state();
    let child = Command::new(std::env::current_exe().unwrap())
        .arg("--exact")
        .arg("supervisor::upgrade::local::tests::isolated_process_fixture")
        .arg("--nocapture")
        .env("POCKET_UPGRADE_PROCESS_FIXTURE", dir.path())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    let expected = ProcessIdentity::capture(child.id()).unwrap();
    let mut reused = expected.clone();
    reused.created_at += 1;
    assert_eq!(maintenance::presence(&reused), maintenance::Presence::Gone);
    let end = Instant::now() + Duration::from_secs(6);
    while !dir.path().join("control.json").exists() {
        assert!(Instant::now() < end);
        std::thread::sleep(Duration::from_millis(20));
    }
    for path in [
        "owner/supervisor.lock",
        "desktop/supervisor.lock",
        "agent.lock",
    ] {
        assert!(storage::Lock::acquire(&dir.path().join(path)).is_err());
    }
    let reply = match Windows.rpc(dir.path(), "upgrade/release", json!({})) {
        Ok(reply) => reply,
        Err(error) => {
            let output = child.wait_with_output().unwrap();
            panic!(
                "Fixture release failed ({error}); child status {}; stdout: {}; stderr: {}",
                output.status,
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr),
            );
        }
    };
    assert_eq!(reply, json!({"flushed":true}));
    gone(&mut Windows, &expected).unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(
        output.status.success(),
        "Fixture child failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let guards: Vec<_> = [
        "owner/supervisor.lock",
        "desktop/supervisor.lock",
        "agent.lock",
    ]
    .iter()
    .map(|p| storage::Lock::acquire(&dir.path().join(p)).unwrap())
    .collect();
    assert_eq!(guards.len(), 3);
}
