use super::*;

pub(super) fn journal(root: &Path) -> Journal {
    let state = root.join("state");
    storage::private_dir(&storage::control(&state)).unwrap();
    let image = Image {
        identity: ProcessIdentity {
            pid: 42,
            created_at: 1,
            executable: root.join("fixture.exe"),
        },
        sha256: "c".repeat(64),
    };
    Journal {
        schema: 1,
        operation_id: uuid::Uuid::new_v4().to_string(),
        handoff_id: uuid::Uuid::new_v4().to_string(),
        install_root: root.into(),
        state_directory: state.clone(),
        from_generation: "a".repeat(64),
        to_generation: "b".repeat(64),
        phase: "verifying".into(),
        failure: None,
        original: None,
        stopping: None,
        verified: Some(Plan {
            additional: vec![],
            ancestry: vec![],
            desktop: image.clone(),
            bridge: image.clone(),
            native: image,
            children: vec![],
            descriptor: root.join("runtimes/test.json"),
            runtime_id: "test".into(),
        }),
        candidate_record: json!({"installRoot":root,"stateDirectory":state,"generation":"b".repeat(64),"releaseHash":"b".repeat(64),"futureField":{"keep":true}}),
        loaded_threads: vec!["saved-thread".into()],
    }
}

#[test]
fn release_requires_the_exact_durable_readiness_journal() {
    let root = crate::test_support::tempdir();
    let mut j = journal(root.path());
    save(&mut j, "verifying").unwrap();
    assert!(publish_release(&j).is_err());
    j.phase = "resuming".into();
    // An in-memory phase change must not publish a durable admission receipt.
    assert!(publish_release(&j).is_err());
    save(&mut j, "resuming").unwrap();
    let mut wrong = j.clone();
    wrong.verified.as_mut().unwrap().bridge.identity.created_at += 1;
    assert!(publish_release(&wrong).is_err());
    publish_release(&j).unwrap();
    publish_release(&j).unwrap();
    let v: Value = storage::read(&release_path(&j).unwrap()).unwrap();
    assert_eq!(
        v,
        json!({"schema":1,"operationId":j.operation_id,"released":true})
    );
    assert_eq!(read_status(&j.state_directory).unwrap().unwrap(), j);
}

#[test]
fn invalid_receipts_are_never_overwritten_or_treated_as_release() {
    let root = crate::test_support::tempdir();
    let mut j = journal(root.path());
    save(&mut j, "rollback_resuming").unwrap();
    let p = release_path(&j).unwrap();
    storage::private_dir(p.parent().unwrap()).unwrap();
    for value in [
        json!({"schema":2,"operationId":j.operation_id,"released":true}),
        json!({"schema":1,"operationId":uuid::Uuid::new_v4().to_string(),"released":true}),
        json!({"schema":1,"operationId":j.operation_id,"released":false}),
        json!({"schema":1,"operationId":j.operation_id,"released":true,"extra":true}),
    ] {
        storage::atomic(&p, &value).unwrap();
        let bytes = std::fs::read(&p).unwrap();
        assert!(released(&j).is_err());
        assert!(publish_release(&j).is_err());
        assert_eq!(std::fs::read(&p).unwrap(), bytes);
    }
    std::fs::write(&p, " ".repeat(4097)).unwrap();
    assert!(released(&j).is_err());
}

#[test]
fn already_published_release_cannot_reenter_stop_or_rollback() {
    let root = crate::test_support::tempdir();
    let mut j = journal(root.path());
    save(&mut j, "resuming").unwrap();
    storage::atomic(
        &root.path().join("desktop-install.json"),
        &j.candidate_record,
    )
    .unwrap();
    publish_release(&j).unwrap();
    for phase in [
        "waiting_idle",
        "stop_intent",
        "stopped",
        "starting",
        "verifying",
        "rolling_back",
        "rollback_stop_intent",
        "rollback_starting",
        "rollback_verifying",
        "rollback_helper",
    ] {
        save(&mut j, phase).unwrap();
        let err = run(root.path(), &j.state_directory, &j.handoff_id).unwrap_err();
        assert!(
            err.to_string().contains("native_release_phase_invalid"),
            "{phase}: {err:#}"
        );
    }
}

#[test]
fn installation_drift_is_rejected_before_any_native_action() {
    let root = crate::test_support::tempdir();
    let mut j = journal(root.path());
    save(&mut j, "waiting_idle").unwrap();
    let mut changed = j.candidate_record.clone();
    changed["futureField"] = json!({"changed":true});
    storage::atomic(&root.path().join("desktop-install.json"), &changed).unwrap();
    let err = run(root.path(), &j.state_directory, &j.handoff_id).unwrap_err();
    assert!(
        err.to_string().contains("native_installation_drift"),
        "{err:#}"
    );
    assert_eq!(
        storage::read::<Value>(&root.path().join("desktop-install.json")).unwrap(),
        changed
    );
    assert_eq!(
        read_status(&j.state_directory).unwrap().unwrap().phase,
        "waiting_idle"
    );
}

#[test]
fn supervisor_retries_native_recovery_after_reverse_helper_replaces_handoff() {
    let root = crate::test_support::tempdir();
    let mut j = journal(root.path());
    save(&mut j, "rollback_resuming").unwrap();
    // A concurrently running native consumer owns the operation. tick must
    // find it directly, without depending on the now-replaced helper journal.
    let _owner =
        storage::Lock::acquire(&storage::control(&j.state_directory).join("native-update.lock"))
            .unwrap();
    storage::atomic(
        &storage::control(&j.state_directory).join("upgrade.json"),
        &json!({"operationId":"different-reverse-handoff"}),
    )
    .unwrap();
    crate::updates::tick(root.path(), &j.state_directory).unwrap();
    assert_eq!(read_status(&j.state_directory).unwrap().unwrap(), j);
}

#[test]
fn environment_is_operation_bound_and_a_released_receipt_cannot_launch_candidates() {
    let root = crate::test_support::tempdir();
    let mut j = journal(root.path());
    let mut command = std::process::Command::new("not-executed");
    io::startup_environment(&mut command, &j).unwrap();
    let env: std::collections::BTreeMap<_, _> = command
        .get_envs()
        .map(|(k, v)| (k.to_owned(), v.map(ToOwned::to_owned)))
        .collect();
    assert_eq!(
        env[std::ffi::OsStr::new("POCKET_UPDATE_OPERATION")].as_deref(),
        Some(std::ffi::OsStr::new(&j.operation_id))
    );
    assert_eq!(
        env[std::ffi::OsStr::new("POCKET_UPDATE_RELEASE_FILE")].as_deref(),
        Some(release_path(&j).unwrap().as_os_str())
    );
    save(&mut j, "resuming").unwrap();
    publish_release(&j).unwrap();
    assert!(io::startup_environment(&mut command, &j).is_err());
}

#[test]
fn background_success_is_complete_without_native_restart_but_legacy_journal_is_visible() {
    let root = crate::test_support::tempdir();
    let mut j = journal(root.path());
    let directory = storage::control(&j.state_directory).join("updates");
    storage::private_dir(&directory).unwrap();
    let status = crate::updates::UpdateStatus {
        phase: "succeeded".into(),
        handoff_operation_id: Some(j.handoff_id.clone()),
        ..Default::default()
    };
    storage::atomic(&directory.join("status.json"), &status).unwrap();
    assert_eq!(
        crate::updates::public_status(&j.state_directory)["phase"],
        "succeeded"
    );
    save(&mut j, "waiting_idle").unwrap();
    assert_eq!(
        crate::updates::public_status(&j.state_directory)["phase"],
        "waiting_idle"
    );
    save(&mut j, "succeeded").unwrap();
    assert_eq!(
        crate::updates::public_status(&j.state_directory)["phase"],
        "succeeded"
    );
    std::fs::write(path(&j.state_directory), b"{").unwrap();
    assert_eq!(
        crate::updates::public_status(&j.state_directory)["phase"],
        "failed"
    );
}

// Only OS/helper/RPC boundaries are simulated. The coordinator, journal writes,
// receipt publication and terminal recovery state below are the production code.
struct ResumeFixture {
    current: Plan,
    helper: Value,
    management: Value,
    expected_helper: String,
    state: PathBuf,
    calls: Vec<String>,
    ready: bool,
    saved_read: bool,
    inventory_changed: bool,
    inventory_reads: usize,
    fail_resume: bool,
    empty_inventory: bool,
    original_tree_gone: bool,
    gate_released: bool,
}

impl ResumeBackend for ResumeFixture {
    fn complete_released(&mut self, j: &Journal) -> Result<bool> {
        ensure!(released(j)? && read_status(&self.state)?.as_ref()==Some(j), "completion journal/receipt changed");
        self.calls.push("complete_released".into());
        Ok(self.gate_released || self.original_tree_gone)
    }
    fn helper(&mut self, _: &Journal, generation: &str) -> Result<Value> {
        ensure!(
            generation == self.expected_helper,
            "wrong helper generation"
        );
        self.calls.push("helper".into());
        Ok(self.helper.clone())
    }
    fn inventory(&mut self, _: &Journal) -> Result<Option<Plan>> {
        self.calls.push("inventory".into());
        self.inventory_reads += 1;
        ensure!(
            !self.inventory_changed || self.inventory_reads == 1,
            "tree changed"
        );
        Ok((!self.empty_inventory).then(|| self.current.clone()))
    }
    fn read_ready(&mut self, p: &Plan) -> Result<Vec<String>> {
        ensure!(p == &self.current && self.ready, "native readiness failed");
        self.calls.push("read_ready".into());
        Ok(vec![])
    }
    fn image_ok(&mut self, image: &Image) -> Result<()> {
        ensure!(
            [
                &self.current.desktop,
                &self.current.bridge,
                &self.current.native
            ]
            .into_iter()
            .chain(self.current.children.iter())
            .any(|i| i == image),
            "native_identity_changed"
        );
        Ok(())
    }
    fn rpc(&mut self, p: &Plan, method: &str, params: Value) -> Result<Value> {
        ensure!(p == &self.current, "stale native RPC target");
        self.calls.push(method.into());
        match method {
            "pocket/managementStatus" => Ok(self.management.clone()),
            "thread/read" => {
                ensure!(self.saved_read, "saved thread unavailable");
                Ok(json!({"thread":{"id":params["threadId"]}}))
            }
            "pocket/maintenance/quiesce" => {
                let j = read_status(&self.state)?.unwrap();
                ensure!(!released(&j)?, "must not quiesce after release");
                self.management["maintenance"]["remainingMs"] = json!(30000);
                Ok(
                    json!({"protocol":1,"operationId":params["operationId"],"quiesced":true,"leaseMs":30000,"startupFenced":true}),
                )
            }
            "pocket/maintenance/resume" => {
                let j = read_status(&self.state)?.unwrap();
                ensure!(released(&j)?, "resume before durable release");
                ensure!(
                    j.verified.as_ref() == Some(&self.current),
                    "release used stale durable plan"
                );
                ensure!(!self.fail_resume, "resume reply lost");
                self.management["maintenance"] = Value::Null;
                Ok(json!({"operationId":params["operationId"],"quiesced":false}))
            }
            _ => anyhow::bail!("unexpected effectful RPC: {method}"),
        }
    }
}

fn resume_fixture(root: &Path, rollback: bool) -> (Journal, ResumeFixture) {
    use sha2::{Digest, Sha256};
    let mut j = journal(root);
    let manifest = b"{\"files\":[]}";
    let generation = format!("{:x}", Sha256::digest(manifest));
    let bin = root.join("bin").join(&generation);
    std::fs::create_dir_all(&bin).unwrap();
    std::fs::write(bin.join("windows-manifest.json"), manifest).unwrap();
    if rollback {
        j.from_generation = generation.clone();
    } else {
        j.to_generation = generation.clone();
    }
    j.candidate_record["generation"] = json!(j.to_generation);
    j.candidate_record["releaseHash"] = json!(j.to_generation);
    let p = j.verified.as_mut().unwrap();
    p.desktop.identity.pid = 100;
    p.bridge.identity.pid = 200;
    p.bridge.identity.executable = bin.join("pocket-bridge.exe");
    p.native.identity.pid = 300;
    let current = p.clone();
    let phase = if rollback {
        "rollback_resuming"
    } else {
        "resuming"
    };
    save(&mut j, phase).unwrap();
    storage::atomic(
        &root.join("desktop-install.json"),
        &if rollback {
            previous_record(&j)
        } else {
            j.candidate_record.clone()
        },
    )
    .unwrap();
    let f = ResumeFixture {
        helper: json!({"acceptingWrites":true,"runtimes":[{"id":current.runtime_id,"online":true,"initialized":true,"schemaState":"ready","inventoryReady":true}]}),
        management: json!({"protocol":1,"startupBarrierProtocol":1,"initialized":true,"busy":false,"activeTurns":0,"pendingQuestions":0,"pendingWrites":0,"unknownOperations":0,"maintenance":{"operationId":j.operation_id,"startupFenced":true,"remainingMs":0}}),
        current,
        expected_helper: generation,
        state: j.state_directory.clone(),
        calls: vec![],
        ready: true,
        saved_read: true,
        inventory_changed: false,
        inventory_reads: 0,
        fail_resume: false,
        empty_inventory: false,
        original_tree_gone: false,
        gate_released: false,
    };
    (j, f)
}

fn replace_bridge(f: &mut ResumeFixture) {
    f.current.bridge.identity.created_at += 1;
    f.current.native.identity.created_at += 1;
    f.current.runtime_id = "replacement".into();
    f.current.descriptor = f.current.descriptor.with_file_name("replacement.json");
    f.helper["runtimes"][0]["id"] = json!(f.current.runtime_id);
}

#[test]
fn resume_revalidates_after_a_recorded_child_exits_before_release() {
    for rollback in [false, true] {
        let root = crate::test_support::tempdir();
        let (mut j, mut f) = resume_fixture(root.path(), rollback);
        let mut gone = f.current.desktop.clone();
        gone.identity.pid = 400;
        j.verified.as_mut().unwrap().children.push(gone);
        let phase = j.phase.clone();
        save(&mut j, &phase).unwrap();
        let result = resume_verified_with(&mut j, rollback, &mut f);
        assert!(
            result.is_ok(),
            "exited child must not pin recovery forever: {result:?}"
        );
        assert!(released(&j).unwrap());
        assert_eq!(j.verified.as_ref(), Some(&f.current));
        assert_eq!(j.phase, if rollback { "rolled_back" } else { "succeeded" });
        assert!(f.calls.iter().any(|c| c == "read_ready"));
        assert!(f.calls.iter().any(|c| c == "thread/read"));
        assert!(f.inventory_reads >= 2);
    }
}

#[test]
fn resume_revalidates_same_operation_bridge_replacement_before_release() {
    let root = crate::test_support::tempdir();
    let (mut j, mut f) = resume_fixture(root.path(), false);
    replace_bridge(&mut f);
    let result = resume_verified_with(&mut j, false, &mut f);
    assert!(
        result.is_ok(),
        "same-operation replacement should recover: {result:?}"
    );
    assert_eq!(
        read_status(&j.state_directory).unwrap().unwrap().verified,
        Some(f.current)
    );
}

#[test]
fn resume_reconciles_released_successor_without_quiescing_live_work() {
    for rollback in [false, true] {
        let root = crate::test_support::tempdir();
        let (mut j, mut f) = resume_fixture(root.path(), rollback);
        publish_release(&j).unwrap();
        let receipt = std::fs::read(release_path(&j).unwrap()).unwrap();
        replace_bridge(&mut f);
        f.management["maintenance"] = Value::Null;
        f.management["busy"] = json!(true);
        f.management["activeTurns"] = json!(1);
        let result = resume_verified_with(&mut j, rollback, &mut f);
        assert!(
            result.is_ok(),
            "released successor must finish reconciliation: {result:?}"
        );
        assert_eq!(j.phase, if rollback { "rolled_back" } else { "succeeded" });
        assert_eq!(std::fs::read(release_path(&j).unwrap()).unwrap(), receipt);
        assert!(!f.calls.iter().any(|c| c.contains("maintenance/")));
    }
}

#[test]
fn review_post_receipt_original_tree_exit_finishes_the_same_operation() {
    for rollback in [false, true] {
        for gate_released in [false, true] {
            let root = crate::test_support::tempdir();
            let (mut j, mut f) = resume_fixture(root.path(), rollback);
            publish_release(&j).unwrap();
            let receipt = std::fs::read(release_path(&j).unwrap()).unwrap();
            let verified = j.verified.clone();
            f.empty_inventory = true;
            f.original_tree_gone = true;
            f.gate_released = gate_released;
            let result = resume_verified_with(&mut j, rollback, &mut f);
            assert!(result.is_ok(), "published operation must finish after its complete tree exits: {result:?}");
            assert_eq!(j.phase, if rollback { "rolled_back" } else { "succeeded" });
            assert_eq!(j.verified, verified, "completion must not adopt a new tree");
            assert_eq!(std::fs::read(release_path(&j).unwrap()).unwrap(), receipt);
            assert!(!f.calls.iter().any(|c| c == "helper" || c == "inventory" || c.contains("maintenance/")));
        }
    }
}

#[test]
fn review_post_receipt_released_gate_finishes_without_adopting_later_desktop() {
    for rollback in [false, true] {
        let root = crate::test_support::tempdir();
        let (mut j, mut f) = resume_fixture(root.path(), rollback);
        publish_release(&j).unwrap();
        let receipt = std::fs::read(release_path(&j).unwrap()).unwrap();
        let verified = j.verified.clone();
        f.gate_released = true;
        f.current.desktop.identity.created_at += 1;
        replace_bridge(&mut f);
        f.management["maintenance"] = Value::Null;
        f.management["busy"] = json!(true);
        f.management["activeTurns"] = json!(1);
        f.management["unknownOperations"] = json!(1);
        let result = resume_verified_with(&mut j, rollback, &mut f);
        assert!(result.is_ok(), "a completed gate must not pin the journal to an exited desktop: {result:?}");
        assert_eq!(j.verified, verified);
        assert_eq!(j.phase, if rollback { "rolled_back" } else { "succeeded" });
        assert_eq!(std::fs::read(release_path(&j).unwrap()).unwrap(), receipt);
        assert!(!f.calls.iter().any(|c| c == "helper" || c == "inventory" || c.contains("maintenance/")));
    }
}

#[test]
fn resume_rejects_unproven_current_tree_without_publishing_or_resuming() {
    for case in 0..8 {
        let root = crate::test_support::tempdir();
        let (mut j, mut f) = resume_fixture(root.path(), false);
        match case {
            0 => f.ready = false,
            1 => f.saved_read = false,
            2 => f.helper["runtimes"][0]["inventoryReady"] = json!(false),
            3 => f.helper["acceptingWrites"] = json!(false),
            4 => {
                f.management["maintenance"]["operationId"] = json!(uuid::Uuid::new_v4().to_string())
            }
            5 => f.management["maintenance"]["startupFenced"] = json!(false),
            6 => f.inventory_changed = true,
            _ => {
                std::fs::write(
                    f.current
                        .bridge
                        .identity
                        .executable
                        .parent()
                        .unwrap()
                        .join("windows-manifest.json"),
                    b"changed",
                )
                .unwrap();
            }
        }
        assert!(
            resume_verified_with(&mut j, false, &mut f).is_err(),
            "unverified case {case} released"
        );
        assert!(!released(&j).unwrap(), "case {case}");
        assert_eq!(
            read_status(&j.state_directory).unwrap().unwrap().phase,
            "resuming"
        );
        assert!(!f.calls.iter().any(|c| c == "pocket/maintenance/resume"));
    }
}

#[test]
fn resume_released_operation_rejects_another_desktop_or_barrier_owner() {
    for other_desktop in [false, true] {
        let root = crate::test_support::tempdir();
        let (mut j, mut f) = resume_fixture(root.path(), false);
        publish_release(&j).unwrap();
        if other_desktop {
            f.current.desktop.identity.created_at += 1;
            f.management["maintenance"] = Value::Null;
        } else {
            f.management["maintenance"]["operationId"] = json!(uuid::Uuid::new_v4().to_string());
        }
        assert!(resume_verified_with(&mut j, false, &mut f).is_err());
        assert!(released(&j).unwrap());
        assert_eq!(
            read_status(&j.state_directory).unwrap().unwrap().phase,
            "resuming"
        );
        assert!(!f.calls.iter().any(|c| c.contains("maintenance/")));
    }
}

#[test]
fn resume_revalidates_restarted_desktop_only_with_its_operation_fence() {
    let root = crate::test_support::tempdir();
    let (mut j, mut f) = resume_fixture(root.path(), false);
    let stale = j.verified.clone();
    f.current.desktop.identity.created_at += 1;
    replace_bridge(&mut f);
    f.management["maintenance"]["operationId"] = json!(uuid::Uuid::new_v4().to_string());
    assert!(resume_verified_with(&mut j, false, &mut f).is_err());
    assert!(!released(&j).unwrap());
    assert_eq!(
        read_status(&j.state_directory).unwrap().unwrap().verified,
        stale
    );
    f.management["maintenance"]["operationId"] = json!(j.operation_id);
    resume_verified_with(&mut j, false, &mut f).unwrap();
    assert_eq!(j.phase, "succeeded");
    assert_eq!(j.verified.as_ref(), Some(&f.current));
}

#[test]
fn resume_recovers_after_reply_loss_without_republishing_or_quiescing() {
    for rollback in [false, true] {
        let root = crate::test_support::tempdir();
        let (mut j, mut f) = resume_fixture(root.path(), rollback);
        f.fail_resume = true;
        let err = resume_verified_with(&mut j, rollback, &mut f).unwrap_err();
        assert_eq!(err.to_string(), "resume reply lost");
        assert!(released(&j).unwrap());
        let receipt = std::fs::read(release_path(&j).unwrap()).unwrap();
        j = read_status(&j.state_directory).unwrap().unwrap();
        replace_bridge(&mut f);
        f.fail_resume = false;
        f.management["maintenance"]["startupFenced"] = json!(false);
        f.calls.clear();
        resume_verified_with(&mut j, rollback, &mut f).unwrap();
        assert_eq!(j.phase, if rollback { "rolled_back" } else { "succeeded" });
        assert_eq!(std::fs::read(release_path(&j).unwrap()).unwrap(), receipt);
        assert!(!f.calls.iter().any(|c| c == "pocket/maintenance/quiesce"));
        assert!(f.calls.iter().any(|c| c == "pocket/maintenance/resume"));
    }
}

#[test]
fn resume_retries_a_transient_tree_change_from_fresh_inventory() {
    let root = crate::test_support::tempdir();
    let (mut j, mut f) = resume_fixture(root.path(), false);
    replace_bridge(&mut f);
    f.inventory_changed = true;
    let original = j.clone();
    assert!(resume_verified_with(&mut j, false, &mut f).is_err());
    assert!(!released(&j).unwrap());
    assert_eq!(read_status(&j.state_directory).unwrap().unwrap(), original);
    f.inventory_changed = false;
    j = read_status(&j.state_directory).unwrap().unwrap();
    resume_verified_with(&mut j, false, &mut f).unwrap();
    assert_eq!(j.phase, "succeeded");
}

#[test]
fn resume_rejects_invalid_receipt_before_contacting_native() {
    let root = crate::test_support::tempdir();
    let (mut j, mut f) = resume_fixture(root.path(), false);
    let p = release_path(&j).unwrap();
    storage::private_dir(p.parent().unwrap()).unwrap();
    storage::atomic(
        &p,
        &json!({"schema":1,"operationId":uuid::Uuid::new_v4().to_string(),"released":true}),
    )
    .unwrap();
    let receipt = std::fs::read(&p).unwrap();
    assert!(resume_verified_with(&mut j, false, &mut f).is_err());
    assert!(f.calls.is_empty());
    assert_eq!(std::fs::read(&p).unwrap(), receipt);
}
