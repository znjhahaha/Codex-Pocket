use super::*;
use std::collections::BTreeMap;

#[cfg(windows)]
#[path = "release_trace.rs"]
mod release_trace;

/// Read-only diagnostic for a live, explicitly marked disposable fixture. It
/// exercises the supervisor's release proof without issuing native/control RPC.
#[cfg(windows)]
#[test]
#[ignore = "requires a live marked handoff fixture"]
fn combined_handoff_release_evidence_probe() {
    let root = PathBuf::from(std::env::var_os("POCKET_HANDOFF_DIAGNOSTIC_FIXTURE").expect("explicit fixture root"));
    storage::unredirected(&root).unwrap();
    assert!(root.is_absolute() && root.join(".fixture-owner").is_file());
    let state = root.join("install/isolated state");
    let status = super::super::read_status(&state).unwrap().unwrap();
    assert_eq!(status.install_root, root.join("install"));
    assert_eq!(status.state_directory, state);
    let journal = maintenance::read_status(&state).unwrap().unwrap();
    for identity in [&journal.request.supervisor, &journal.request.coordinator].into_iter().chain(journal.request.child.iter()) {
        assert!(identity.executable.starts_with(&root), "fixture identity escaped root");
    }
    let mut request = journal.request;
    request.action = Action::Release;
    match release_proven(&state, &request) {
        Ok(()) => println!("release-evidence=accepted"),
        Err(error) => panic!("release-evidence=rejected: {error:#}"),
    }
}

struct Files;
/// Reads only the selected on-disk checkpoint; no control RPC or process action.
#[cfg(windows)]
#[test]
#[ignore = "requires an explicitly selected real update state"]
fn readonly_failed_checkpoint_diagnostic() {
    let state = PathBuf::from(std::env::var_os("POCKET_QA_CHECKPOINT_STATE").unwrap());
    let s = super::super::read_status(&state).unwrap().unwrap();
    let mut a = Windows;
    let d = load(&mut a, &s).unwrap();
    let disk = agent_record(&mut a, &state).unwrap();
    for id in [&s.operation_id, &d.active_id] {
        let op = disk.operations.get(id).unwrap();
        let cp = op.receipt.checkpoint.as_ref().unwrap();
        let bytes = a.read(&state.join("handoff").join(format!("{}.json",cp.id)),MAX_IMAGE).unwrap();
        let envelope: ImageEnvelope = serde_json::from_slice(&bytes).unwrap();
        let entries = envelope.snapshot["ledger"]["entries"].as_array().unwrap();
        let result = image(&mut a, &state, cp, &d.computer_id);
        println!("operation={id} phase={} release_input={} shutdown={} entries={} unknown={} submitted={} image={}",
            op.receipt.phase,op.release_input.is_some(),op.receipt.shutdown_requested,entries.len(),
            entries.iter().filter(|e|e["status"]=="Unknown").count(),
            entries.iter().filter(|e|e["status"]=="Submitted").count(),
            result.map(|_|"accepted".to_string()).unwrap_or_else(|e|format!("{e:#}")));
    }
    println!("cancelled_source={}", cancelled_before_release(&mut a,&s)
        .map(|_|"verified".to_owned()).unwrap_or_else(|e|format!("{e:#}")));
}

fn unknown_entry() -> Value {
    json!({"key":{"caller_id":"fixture","command_id":"retained-unknown"},"fingerprint":vec![7;32],
        "status":"Unknown","generation":1,"created_at":1,"finished_at":null})
}

#[test]
fn checkpoint_preserves_identified_unknown_tombstones_without_allowing_submitted_writes() {
    let dir = state();
    let mut snap = snapshot(dir.path());
    snap["ledger"]["entries"] = json!([unknown_entry()]);
    let cp = write_image(dir.path(), snap.clone());
    let read = image(&mut Files, dir.path(), &cp, "fixture-computer").unwrap();
    assert_eq!(read["ledger"],snap["ledger"]);
    successor(&snap,&read).unwrap();
    for replacement in [json!([]), json!([{ "status":"Accepted" }])] {
        let mut changed=read.clone();changed["ledger"]["entries"]=replacement;
        assert!(successor(&snap,&changed).is_err());
    }
    snap["ledger"]["entries"][0]["status"]=json!("Submitted");
    let cp=write_image(dir.path(),snap);
    assert!(image(&mut Files,dir.path(),&cp,"fixture-computer").is_err());
}
#[cfg(windows)]
#[test]
fn release_ack_retries_real_private_journal_sharing_failure_without_releasing_again() {
    use std::os::windows::fs::OpenOptionsExt;
    struct LockedAck {fake:Fake,hold:Option<std::fs::File>,reads:u32}
    impl Adapter for LockedAck {
        fn capture(&mut self,pid:u32)->Result<ProcessIdentity>{self.fake.capture(pid)}
        fn presence(&mut self,p:&ProcessIdentity)->maintenance::Presence{self.fake.presence(p)}
        fn read(&mut self,p:&Path,n:u64)->Result<Vec<u8>>{self.fake.read(p,n)}
        fn rpc(&mut self,s:&Path,m:&str,p:Value)->Result<Value>{self.fake.rpc(s,m,p)}
        fn spawn(&mut self,s:&UpgradeStatus,g:&str,c:&CheckpointRef,r:bool)->Result<u32>{self.fake.spawn(s,g,c,r)}
        fn submit(&mut self,s:&Path,r:&Request)->Result<()>{self.fake.submit(s,r)}
        fn tick(&mut self){}
        fn ack(&mut self,state:&Path)->Result<Option<Journal>> {
            self.reads+=1;
            if self.hold.is_some(){
                let result=maintenance::read_status(state);
                let error=result.as_ref().unwrap_err();
                assert_eq!(error.downcast_ref::<std::io::Error>().and_then(|e|e.raw_os_error()),Some(32));
                println!("releasing_supervisor: real private maintenance journal read -> OS32");
                self.hold.take();return result;
            }
            self.fake.ack(state)
        }
    }
    let mut fake=Fake::new();let s=fake.s.clone();let mut d=begin(&mut fake,&s).unwrap();
    ack(&mut fake,&s,&d,Action::Enter).unwrap();
    fake.live.insert(d.current.agent.pid,maintenance::Presence::Gone);
    d.stage="releasing_supervisor".into();
    let path=storage::control(&s.state_directory).join("maintenance.json");
    maintenance::write_private(&path,fake.journal.as_ref().unwrap()).unwrap();
    let hold=std::fs::OpenOptions::new().read(true).share_mode(0).open(&path).unwrap();
    let mut a=LockedAck{fake,hold:Some(hold),reads:0};
    let result=ack(&mut a,&s,&d,Action::Release);
    if let Err(error)=&result {maintenance::release_diagnostic(&s.state_directory,&request(&s,&d,Action::Release),"coordinator",&d.stage,error,json!({})).unwrap();}
    assert!(result.is_ok(),"a temporary OS32 read entered rollback after the exact agent had exited: {result:?}");
    assert_eq!(a.reads,2);
    assert_eq!(a.fake.events.iter().filter(|e|e.as_str()=="maintenance/Release").count(),1);
    assert!(!a.fake.rpc_requests.iter().any(|(m,_)|m=="upgrade/release"));
}
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
    reject_one_release: bool,
    submit_lock_conflicts: u32,
    submit_access_denied: bool,
    submit_attempts: u32,
    defer_owned_recovery: bool,
    pending_maintenance: Option<Request>,
    _root: tempfile::TempDir,
    s: UpgradeStatus,
    current: Owner,
    identities: BTreeMap<u32, ProcessIdentity>,
    live: BTreeMap<u32, maintenance::Presence>,
    receipts: BTreeMap<String, Receipt>,
    prior_remotes: BTreeMap<String, bool>,
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
    blocked_quiesce: bool,
    draining_quiesce: bool,
    cancel_reply_fault: Option<&'static str>,
    maintenance_cancel_error: bool,
    rpc_requests: Vec<(String, Value)>,
}
impl Fake {
    fn new() -> Self {
        let root = state();
        storage::private_dir(root.path()).unwrap();
        storage::private_dir(&storage::control(root.path())).unwrap();
        storage::private_dir(&root.path().join("handoff")).unwrap();
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
            reject_one_release: false,
            submit_lock_conflicts: 0,
            submit_access_denied: false,
            submit_attempts: 0,
            defer_owned_recovery: false,
            pending_maintenance: None,
            _root: root,
            s,
            current,
            identities,
            live,
            receipts: BTreeMap::new(),
            prior_remotes: BTreeMap::new(),
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
            blocked_quiesce: false,
            draining_quiesce: false,
            cancel_reply_fault: None,
            maintenance_cancel_error: false,
            rpc_requests: vec![],
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
            snap["ledger"]["entries"] = json!([unknown_entry()]);
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
                    json!({"receipt":r,"priorRemote":self.prior_remotes.get(id).copied().unwrap_or(false),"releaseInput":null}),
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
        self.submit_attempts += 1;
        if self.submit_access_denied {return Err(std::io::Error::from_raw_os_error(5).into())}
        if self.submit_lock_conflicts > 0 {self.submit_lock_conflicts -= 1;return Err(std::io::Error::from_raw_os_error(33).into())}
        if r.action == Action::Release && std::mem::take(&mut self.reject_one_release) {bail!("fixture first release rejected")}
        ensure!(self.pending_maintenance.as_ref().is_none_or(|pending| pending == r), "Another maintenance request is pending");
        if self.defer_owned_recovery && r.action == Action::Recover
            && self.journal.as_ref().is_some_and(|j| j.phase == maintenance::Phase::Owned && j.request.coordinator == r.coordinator) {
            self.pending_maintenance = Some(r.clone());
            return Ok(());
        }
        self.events.push(format!("maintenance/{:?}", r.action));
        if r.action == Action::Cancel && self.maintenance_cancel_error {
            bail!("fixture maintenance cancel unavailable");
        }
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
        self.rpc_requests.push((method.into(), p.clone()));
        match method {
            "status" | "helper/health" => {
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
                let cp = if self.blocked_quiesce {
                    None
                } else {
                    Some(self.cp())
                };
                let r = Receipt {
                    protocol: 1,
                    id: p["id"].as_str().unwrap().into(),
                    generation: self.current.run.clone(),
                    phase: if self.draining_quiesce {
                        "draining"
                    } else if self.blocked_quiesce {
                        "failed"
                    } else {
                        "ready"
                    }
                    .into(),
                    safe_to_exit: !self.blocked_quiesce,
                    native_tasks_preserved: !self.blocked_quiesce,
                    remote_enabled: false,
                    accepting_writes: false,
                    unconfirmed_writes: u64::from(self.blocked_quiesce),
                    blockers: if self.blocked_quiesce {
                        let mut blockers = vec![
                            "unconfirmed_writes".into(),
                            "runtime_inventory_unverified".into(),
                        ];
                        if !self.draining_quiesce {
                            blockers.push("checkpoint_failed".into());
                        }
                        blockers
                    } else {
                        vec![]
                    },
                    checkpoint: cp,
                    shutdown_requested: false,
                };
                self.prior_remotes.insert(r.id.clone(), self.remote);
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
                ensure!(
                    p["fromGeneration"] == self.current.run,
                    "stale cancel owner"
                );
                let id = p["id"].as_str().unwrap();
                let prior_remote = self.prior_remotes.get(id).copied().unwrap_or(false);
                let r = self.receipts.get_mut(id).context("missing")?;
                ensure!(
                    p["checkpoint"].is_null() || p["checkpoint"] == json!(r.checkpoint),
                    "wrong cancel checkpoint"
                );
                ensure!(
                    !matches!(r.phase.as_str(), "released" | "release_pending"),
                    "late cancel"
                );
                r.phase = "cancelled".into();
                r.safe_to_exit = false;
                r.accepting_writes = true;
                r.remote_enabled = prior_remote;
                self.remote = prior_remote;
                let mut reply = json!(r);
                self.persist_agent();
                // Only the newly fenced recovery operation has this fault;
                // the initial cancellation really did restore admission.
                if id != self.s.operation_id {
                    match self.cancel_reply_fault {
                        Some("lost") => bail!("fixture cancel reply lost"),
                        Some("id") => reply["id"] = json!(uuid::Uuid::new_v4().to_string()),
                        Some("owner") => {
                            reply["generation"] = json!(uuid::Uuid::new_v4().to_string())
                        }
                        Some("gate") => reply["acceptingWrites"] = json!(false),
                        Some("safe") => reply["safeToExit"] = json!(true),
                        Some("shutdown") => reply["shutdownRequested"] = json!(true),
                        _ => {}
                    }
                }
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
fn cancelled_inventory_fixture() -> (Fake, UpgradeStatus, Durable) {
    let mut f=Fake::new();f.unknown_inventory=true;
    let mut s=f.s.clone();
    let mut d=begin(&mut f,&s).unwrap();
    ack(&mut f,&s,&d,Action::Enter).unwrap();
    ready(&mut f,&s,&d).unwrap();
    get_receipt(&mut f,&s,&d,"upgrade/cancel",None).unwrap();
    d.active_id=d.verify_id.clone();
    ready(&mut f,&s,&d).unwrap();
    cancel_recovery_inventory(&mut f,&s,&d).unwrap();
    save_stage(&mut f,&s,&mut d,"recovery_verifying").unwrap();
    f.live.insert(d.coordinator.pid,maintenance::Presence::Gone);
    s.phase=super::super::Phase::Failed;
    s.failure=Some(super::super::Failure::RollbackFailed);
    (f,s,d)
}
#[test]
fn cancelled_pre_release_recovery_reads_the_source_without_replaying_or_fencing() {
    let (mut f,s,_)=cancelled_inventory_fixture();
    f.events.clear();
    let handoff=std::fs::read(s.state_directory.join("handoff/state.json")).unwrap();
    let context=std::fs::read(context_path(&s)).unwrap();
    let v=cancelled_before_release(&mut f,&s).unwrap();
    assert_eq!(v.generation,s.from_generation);
    assert!(v.native_tasks_preserved);
    assert!(f.events.iter().all(|event|matches!(event.as_str(),"helper/health"|"upgrade/capabilities"|"upgrade/status"|"routes/read")));
    assert_eq!(std::fs::read(s.state_directory.join("handoff/state.json")).unwrap(),handoff);
    assert_eq!(std::fs::read(context_path(&s)).unwrap(),context);
}
#[test]
fn cancelled_recovery_rejects_release_spawn_live_coordinator_and_identity_uncertainty() {
    for fault in ["release","shutdown","spawn","stage","coordinator_alive","coordinator_unknown","agent_identity","pending_maintenance","cancel_missing"] {
        let (mut f,s,mut d)=cancelled_inventory_fixture();
        match fault {
            "release"=>{
                let path=s.state_directory.join("handoff/state.json");
                let mut record:Value=storage::read(&path).unwrap();
                record["operations"][&s.operation_id]["releaseInput"]=json!(f.receipts[&s.operation_id].checkpoint);
                storage::atomic(&path,&record).unwrap();
            }
            "shutdown"=>{f.receipts.get_mut(&s.operation_id).unwrap().shutdown_requested=true;f.persist_agent();}
            "spawn"=>{d.spawn_pid=Some(20);f.save(&s,&d).unwrap();}
            "stage"=>{d.stage="releasing_agent".into();f.save(&s,&d).unwrap();}
            "coordinator_alive"=>{f.live.insert(d.coordinator.pid,maintenance::Presence::Alive);}
            "coordinator_unknown"=>{f.live.insert(d.coordinator.pid,maintenance::Presence::Unknown);}
            "agent_identity"=>f.bad_identity=true,
            "pending_maintenance"=>std::fs::write(storage::control(&s.state_directory).join("maintenance-request.json"),b"pending").unwrap(),
            "cancel_missing"=>{f.journal.as_mut().unwrap().phase=maintenance::Phase::Owned;}
            _=>unreachable!(),
        }
        f.events.clear();
        assert!(cancelled_before_release(&mut f,&s).is_err(),"{fault}");
        assert!(!f.events.iter().any(|e|e.starts_with("spawn/")||e.starts_with("maintenance/")||matches!(e.as_str(),"upgrade/quiesce"|"upgrade/cancel"|"upgrade/release")));
    }
}
#[test]
fn recovery_can_verify_and_cancel_a_checkpoint_with_retained_unknown_tombstones() {
    let mut f=Fake::new();f.unknown_inventory=true;
    let s=f.s.clone();let mut d=begin(&mut f,&s).unwrap();
    ack(&mut f,&s,&d,Action::Enter).unwrap();
    ready(&mut f,&s,&d).unwrap();
    save_stage(&mut f,&s,&mut d,"quiescing").unwrap();
    assert_eq!(rollback(&mut f,&s).unwrap().generation,s.from_generation);
    let restored=load(&mut f,&s).unwrap();
    let read=image(&mut f,&s.state_directory,restored.ready.as_ref().unwrap(),&restored.computer_id).unwrap();
    assert_eq!(read["ledger"]["entries"],json!([unknown_entry()]));
    assert_eq!(f.journal.as_ref().unwrap().phase,maintenance::Phase::Cancelled);
    assert!(!f.events.iter().any(|e|e.starts_with("spawn/")||e=="upgrade/release"));
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
fn combined_handoff_owned_recovery_must_not_leave_a_conflicting_release_mailbox() {
    let mut f = Fake::new();
    let s = f.s.clone();
    let d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    f.defer_owned_recovery = true;
    ack(&mut f, &s, &d, Action::Recover).unwrap();
    assert!(f.pending_maintenance.is_none(), "An already-owned recovery returned before its queued request was consumed; Release now conflicts");
    assert_eq!(f.journal.as_ref().unwrap().phase, maintenance::Phase::Owned);
}

#[test]
fn combined_handoff_owned_recovery_does_not_acknowledge_an_uncertain_owner() {
    for supervisor in [true, false] {
        let mut f = Fake::new();
        let s = f.s.clone();
        let d = begin(&mut f, &s).unwrap();
        ack(&mut f, &s, &d, Action::Enter).unwrap();
        let identity = if supervisor { &d.current.supervisor } else { &d.coordinator };
        f.live.insert(identity.pid, maintenance::Presence::Unknown);
        let submitted = f.submit_attempts;
        assert!(ack(&mut f, &s, &d, Action::Recover).is_err());
        assert_eq!(f.submit_attempts, submitted);
        assert_eq!(f.journal.as_ref().unwrap().phase, maintenance::Phase::Owned);
        assert!(f.pending_maintenance.is_none());
    }
}

#[test]
fn combined_handoff_release_failure_recovers_the_old_generation_without_resending_native_release() {
    let mut f=Fake::new();let mut s=f.s.clone();let mut d=begin(&mut f,&s).unwrap();
    let original=d.current.clone();
    ack(&mut f,&s,&d,Action::Enter).unwrap();
    let cp=ready(&mut f,&s,&d).unwrap();d.ready=Some(cp.clone());
    s.checkpoint=Some(checkpoint(&s,&cp));f.save(&s,&d).unwrap();
    f.reject_one_release=true;f.defer_owned_recovery=true;
    assert!(stopped(&mut f,&s,&mut d).is_err());
    assert_eq!(f.presence(&original.agent),maintenance::Presence::Gone);
    s.phase=super::super::Phase::RollingBack;
    assert_eq!(rollback(&mut f,&s).unwrap().generation,s.from_generation);
    assert!(f.pending_maintenance.is_none());
    assert_eq!(f.presence(&original.supervisor),maintenance::Presence::Gone);
    assert_eq!(f.current.generation,s.from_generation);
    assert_eq!(f.rpc_requests.iter().filter(|(method,_)|method=="upgrade/release").count(),1);
}

#[cfg(windows)]
#[test]
fn combined_handoff_submit_retries_lock_contention_but_not_permission_failure() {
    let mut f=Fake::new();let s=f.s.clone();let d=begin(&mut f,&s).unwrap();
    f.submit_lock_conflicts=1;
    ack(&mut f,&s,&d,Action::Enter).unwrap();
    assert_eq!(f.submit_attempts,2);
    assert_eq!(f.events.iter().filter(|event|event.as_str()=="maintenance/Enter").count(),1);
    f.submit_access_denied=true;
    let attempts=f.submit_attempts;
    assert!(ack(&mut f,&s,&d,Action::Cancel).is_err());
    assert_eq!(f.submit_attempts,attempts+1);
    assert_eq!(f.journal.as_ref().unwrap().phase,maintenance::Phase::Owned);
}

#[cfg(windows)]
#[test]
fn combined_handoff_release_intent_survives_temporary_checkpoint_sharing_conflict() {
    release_contention_case(false);
}
#[cfg(windows)]
#[test]
fn combined_handoff_changed_checkpoint_after_contention_never_releases_owner() {
    release_contention_case(true);
}
#[cfg(windows)]
fn release_contention_case(tamper:bool) {
    use std::os::windows::fs::OpenOptionsExt;
    let mut f = Fake::new();
    let mut s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    // This protocol fixture has no live source child. u32::MAX is rejected by
    // Win32 as an absent process; no real process is started, stopped or adopted.
    d.current.agent.pid = u32::MAX;
    let cp = f.cp();
    d.ready = Some(cp.clone());
    d.final_checkpoint = Some(cp.clone());
    f.receipts.insert(d.active_id.clone(), Receipt {protocol:1,id:d.active_id.clone(),generation:d.current.run.clone(),phase:"released".into(),safe_to_exit:true,native_tasks_preserved:true,remote_enabled:false,accepting_writes:false,unconfirmed_writes:0,blockers:vec![],checkpoint:Some(cp.clone()),shutdown_requested:true});
    f.persist_agent();
    f.save(&s,&d).unwrap();
    s.phase = super::super::Phase::Releasing;
    s.checkpoint = Some(checkpoint(&s,&cp));
    storage::atomic(&storage::control(&s.state_directory).join("upgrade.json"),&s).unwrap();
    let release = request(&s,&d,Action::Release);
    let mut enter = release.clone();enter.action=Action::Enter;
    maintenance::write_private(&storage::control(&s.state_directory).join("maintenance.json"),&Journal {request:enter,phase:maintenance::Phase::Owned}).unwrap();
    maintenance::submit(&s.state_directory,&release).unwrap();
    let path=s.state_directory.join("handoff").join(format!("{}.json",cp.id));
    let hold=std::fs::OpenOptions::new().read(true).share_mode(0).open(&path).unwrap();
    let mut gate=maintenance::Gate::default();
    let poll=|gate:&mut maintenance::Gate| gate.test_poll_with(&s.state_directory,Some(&release.from_generation),None,|_|Ok(release.supervisor.clone()),|p|if release.child.as_ref()==Some(p){maintenance::Presence::Gone}else{maintenance::Presence::Alive});
    poll(&mut gate);
    assert!(gate.blocks_respawn());
    assert_eq!(gate.journal.as_ref().unwrap().phase,maintenance::Phase::Owned);
    assert!(storage::control(&s.state_directory).join("maintenance-request.json").exists(), "A temporary proof read failure erased the only durable release intent");
    drop(hold);
    if tamper {std::fs::write(&path,b"changed immutable checkpoint").unwrap();}
    poll(&mut gate);
    if tamper {
        assert_eq!(gate.journal.as_ref().unwrap().phase,maintenance::Phase::Owned);
        assert_eq!(gate.error,Some(maintenance::Failure::RequestRejected));
        assert!(gate.blocks_respawn());
    } else {
        assert_eq!(gate.journal.as_ref().unwrap().phase,maintenance::Phase::Released);
        assert!(gate.error.is_none());
    }
    assert!(!storage::control(&s.state_directory).join("maintenance-request.json").exists());
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
fn owner_verification_uses_health_without_requesting_native_lifecycle() {
    struct HealthOnly(Fake);
    impl Adapter for HealthOnly {
        fn capture(&mut self, p:u32)->Result<ProcessIdentity>{self.0.capture(p)}
        fn presence(&mut self,p:&ProcessIdentity)->maintenance::Presence{self.0.presence(p)}
        fn read(&mut self,p:&Path,n:u64)->Result<Vec<u8>>{self.0.read(p,n)}
        fn rpc(&mut self,s:&Path,m:&str,p:Value)->Result<Value>{
            ensure!(m!="status","owner verification requested an OS/native lifecycle scan");
            if m=="helper/health" {
                ensure!(p==json!({}),"health must be a parameterless read");
                self.0.rpc(s,"status",p)
            }else{self.0.rpc(s,m,p)}
        }
        fn spawn(&mut self,s:&UpgradeStatus,g:&str,c:&CheckpointRef,r:bool)->Result<u32>{self.0.spawn(s,g,c,r)}
        fn submit(&mut self,s:&Path,r:&Request)->Result<()>{self.0.submit(s,r)}
        fn ack(&mut self,s:&Path)->Result<Option<Journal>>{self.0.ack(s)}
        fn locks_free(&mut self,s:&UpgradeStatus)->Result<()>{self.0.locks_free(s)}
        fn tick(&mut self){}
    }
    let mut a=HealthOnly(Fake::new());let mut s=a.0.s.clone();
    let mut d=begin(&mut a,&s).expect("identity read must not depend on OS scan latency");
    ack(&mut a,&s,&d,Action::Enter).unwrap();let cp=ready(&mut a,&s,&d).unwrap();
    d.ready=Some(cp.clone());a.save(&s,&d).unwrap();s.checkpoint=Some(checkpoint(&s,&cp));
    stopped(&mut a,&s,&mut d).unwrap();launch(&mut a,&s,&mut d,&s.to_generation).unwrap();
    assert!(verify(&mut a,&s,&mut d,&s.to_generation).unwrap().routes_read);
    assert_eq!(d.stage,"verified");
    assert!(a.0.events.iter().any(|m|m=="upgrade/quiesce"));
    assert!(a.0.events.iter().any(|m|m=="upgrade/release"));
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
    assert_eq!(
        f.journal.as_ref().unwrap().phase,
        maintenance::Phase::Cancelled
    );
    assert_eq!(
        f.rpc(&s.state_directory, "status", json!({})).unwrap()["acceptingWrites"],
        true
    );
    let failed = load(&mut f, &s).unwrap();
    assert_eq!(
        failed.ready, d.ready,
        "retain the earlier checkpoint without declaring recovery verified"
    );
    assert_eq!(failed.stage, "recovery_verifying");
    assert!(!f
        .events
        .iter()
        .any(|v| v.starts_with("spawn/") || v == "upgrade/release"));
}
#[test]
fn timed_out_recovery_inventory_cancels_draining_operation_and_maintenance() {
    let mut f = Fake::new();
    f.remote = true;
    let s = f.s.clone();
    let mut d = begin(&mut f, &s).unwrap();
    ack(&mut f, &s, &d, Action::Enter).unwrap();
    save_stage(&mut f, &s, &mut d, "quiescing").unwrap();
    f.blocked_quiesce = true;
    f.draining_quiesce = true;
    assert!(ready(&mut f, &s, &d).is_err());
    assert_eq!(f.receipts[&d.active_id].phase, "draining");
    assert!(!f.remote);
    assert!(rollback(&mut f, &s).is_err());
    let failed = load(&mut f, &s).unwrap();
    assert_eq!(failed.stage, "recovery_verifying");
    assert!(failed.ready.is_none() && failed.final_checkpoint.is_none());
    assert_eq!(
        f.journal.as_ref().unwrap().phase,
        maintenance::Phase::Cancelled
    );
    let restored = f.rpc(&s.state_directory, "status", json!({})).unwrap();
    assert_eq!(restored["acceptingWrites"], true);
    assert_eq!(restored["remoteEnabled"], true);
    for receipt in f.receipts.values() {
        assert_eq!(receipt.phase, "cancelled");
        assert_eq!(receipt.unconfirmed_writes, 1);
        assert_eq!(
            receipt.blockers,
            vec!["unconfirmed_writes", "runtime_inventory_unverified"]
        );
        assert!(
            !receipt.safe_to_exit && !receipt.shutdown_requested && receipt.checkpoint.is_none()
        );
    }
    assert!(!f
        .events
        .iter()
        .any(|e| e.starts_with("spawn/") || e == "upgrade/release" || e == "maintenance/Release"));
}
#[test]
fn failed_recovery_inventory_restores_gates_without_claiming_handoff() {
    for remote in [false, true] {
        let mut f = Fake::new();
        f.remote = remote;
        let s = f.s.clone();
        let mut d = begin(&mut f, &s).unwrap();
        ack(&mut f, &s, &d, Action::Enter).unwrap();
        save_stage(&mut f, &s, &mut d, "quiescing").unwrap();
        f.blocked_quiesce = true;
        assert!(ready(&mut f, &s, &d).is_err());
        let original = f.receipts[&d.active_id].clone();
        assert_eq!(original.unconfirmed_writes, 1);
        assert!(original.checkpoint.is_none());

        assert!(rollback(&mut f, &s).is_err());
        let failed = load(&mut f, &s).unwrap();
        assert_eq!(failed.stage, "recovery_verifying");
        assert!(failed.ready.is_none() && failed.final_checkpoint.is_none());
        assert!(s.checkpoint.is_none() && s.verification.is_none());
        assert_ne!(failed.active_id, d.active_id);
        for id in [&d.active_id, &failed.active_id] {
            let receipt = &f.receipts[id];
            assert_eq!(receipt.phase, "cancelled", "fence {id} must be restored");
            assert!(
                receipt.accepting_writes && !receipt.safe_to_exit && !receipt.shutdown_requested
            );
            assert_eq!(receipt.remote_enabled, remote);
            assert_eq!(receipt.unconfirmed_writes, original.unconfirmed_writes);
            assert_eq!(receipt.blockers, original.blockers);
            assert!(receipt.checkpoint.is_none());
        }
        let restored = f.rpc(&s.state_directory, "status", json!({})).unwrap();
        assert_eq!(restored["acceptingWrites"], true);
        assert_eq!(restored["remoteEnabled"], remote);
        let journal = f.journal.as_ref().unwrap();
        assert_eq!(journal.phase, maintenance::Phase::Cancelled);
        assert_eq!(journal.request, request(&s, &failed, Action::Enter));
        assert!(f.rpc_requests.iter().any(|(method, p)| {
            method == "upgrade/cancel" && *p == params(&failed.active_id, &failed.current.run, None)
        }));
        let persisted = agent_record(&mut f, &s.state_directory).unwrap();
        assert_eq!(
            persisted.operations[&failed.active_id].receipt.blockers,
            original.blockers
        );
        assert_eq!(
            persisted.operations[&failed.active_id]
                .receipt
                .unconfirmed_writes,
            1
        );
        assert!(!f.events.iter().any(|e| e.starts_with("spawn/")
            || e == "upgrade/release"
            || e == "maintenance/Release"));
        // Another failed recovery also restores gates and keeps every receipt.
        assert!(rollback(&mut f, &s).is_err());
        assert_eq!(f.receipts.len(), 3);
        assert!(f
            .receipts
            .values()
            .all(|r| r.phase == "cancelled" && r.unconfirmed_writes == 1));
        assert_eq!(
            f.journal.as_ref().unwrap().phase,
            maintenance::Phase::Cancelled
        );
    }
}
#[test]
fn failed_recovery_inventory_cancellation_uncertainty_remains_failure() {
    for fault in [
        "lost",
        "id",
        "owner",
        "gate",
        "safe",
        "shutdown",
        "maintenance",
    ] {
        let mut f = Fake::new();
        let s = f.s.clone();
        let mut d = begin(&mut f, &s).unwrap();
        ack(&mut f, &s, &d, Action::Enter).unwrap();
        save_stage(&mut f, &s, &mut d, "quiescing").unwrap();
        f.blocked_quiesce = true;
        assert!(ready(&mut f, &s, &d).is_err());
        f.cancel_reply_fault = Some(fault);
        f.maintenance_cancel_error = fault == "maintenance";
        let error = rollback(&mut f, &s).unwrap_err();
        let details = format!("{error:#}");
        assert!(
            details.contains("Agent is not safe to release"),
            "{fault}: {details}"
        );
        assert!(
            details.contains("cancel"),
            "{fault}: cancellation uncertainty must be reported: {details}"
        );
        assert!(
            f.events.iter().any(|e| e == "maintenance/Cancel"),
            "{fault}: maintenance cleanup must still be attempted"
        );
        assert_eq!(load(&mut f, &s).unwrap().stage, "recovery_verifying");
        assert!(!f.events.iter().any(|e| e.starts_with("spawn/")
            || e == "upgrade/release"
            || e == "maintenance/Release"));
    }
}
#[test]
fn recovery_cancellation_uncertainty_cannot_claim_verified_inventory() {
    for fault in [
        "lost",
        "id",
        "owner",
        "gate",
        "safe",
        "shutdown",
        "maintenance",
    ] {
        let mut f = Fake::new();
        let s = f.s.clone();
        begin(&mut f, &s).unwrap();
        f.cancel_reply_fault = Some(fault);
        f.maintenance_cancel_error = fault == "maintenance";
        assert!(
            rollback(&mut f, &s).is_err(),
            "{fault}: cancellation must be confirmed even after a valid inventory"
        );
        let failed = load(&mut f, &s).unwrap();
        assert_eq!(failed.stage, "recovery_verifying");
        assert!(failed.ready.is_some() && failed.final_checkpoint.is_none());
        assert!(s.checkpoint.is_none() && s.verification.is_none());
        assert!(f.events.iter().any(|e| e == "maintenance/Cancel"));
        assert!(!f.events.iter().any(|e| e.starts_with("spawn/")
            || e == "upgrade/release"
            || e == "maintenance/Release"));
    }
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
