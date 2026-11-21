use super::*;
fn gate(root: &Path) -> Gate {
    Gate {
        schema: 1,
        operation_id: uuid::Uuid::new_v4().to_string(),
        phase: GatePhase::Closing,
        allowed_generation: None,
        release_file: root.join("state/release.json"),
    }
}
fn record(root: &Path) -> Registration {
    let id = uuid::Uuid::new_v4().to_string();
    let generation = "a".repeat(64);
    Registration {
        schema: 1,
        runtime_id: id.clone(),
        install_root: root.into(),
        generation: generation.clone(),
        bridge: NativeProcess {
            pid: 100,
            created_at: 10,
        },
        bridge_executable: root.join("bin").join(generation).join("pocket-bridge.exe"),
        descriptor: root.join("runtimes").join(format!("{id}.json")),
        operation_id: None,
        release_file: None,
        phase: RegistrationPhase::Starting,
        native: None,
    }
}
#[test]
fn admission_lock_spans_pre_spawn_registration_and_descriptor_visibility() {
    let root = tempfile::tempdir().unwrap();
    let install = Installation::open(root.path()).unwrap();
    let mut r = record(root.path());
    let startup = install.startup(&r.generation, None).unwrap();
    startup.register(&r).unwrap();
    assert!(
        install.update().is_err(),
        "updater must not see a gap between spawn and registration"
    );
    r.phase = RegistrationPhase::Registered;
    r.native = Some(NativeProcess {
        pid: 200,
        created_at: 20,
    });
    assert!(
        startup.registered(&r).is_err(),
        "descriptor must be visible before completion"
    );
    fs::create_dir(root.path().join("runtimes")).unwrap();
    physical::atomic(&r.descriptor,&serde_json::json!({"pid":100,"runtimeId":r.runtime_id,"startupAdmissionProtocol":1,"startupAdmission":r})).unwrap();
    startup.registered(&r).unwrap();
    drop(startup);
    let update = install.update().unwrap();
    assert_eq!(update.registrations().unwrap(), vec![r]);
    assert!(install.startup(&"a".repeat(64), None).is_err());
}
#[test]
fn abandoned_pre_spawn_record_is_durable_and_closing_survives_owner_exit() {
    let root = tempfile::tempdir().unwrap();
    let install = Installation::open(root.path()).unwrap();
    let r = record(root.path());
    {
        let startup = install.startup(&r.generation, None).unwrap();
        startup.register(&r).unwrap();
    }
    {
        let update = install.update().unwrap();
        assert_eq!(update.registrations().unwrap(), vec![r.clone()]);
        update.set_gate(&gate(root.path())).unwrap();
    }
    assert!(
        Installation::open(root.path())
            .unwrap()
            .startup(&r.generation, None)
            .is_err()
    );
    assert_eq!(
        install.update().unwrap().registrations().unwrap()[0].phase,
        RegistrationPhase::Starting
    );
}
#[test]
fn candidate_requires_exact_operation_generation_and_unreleased_receipt() {
    let root = tempfile::tempdir().unwrap();
    let install = Installation::open(root.path()).unwrap();
    let mut g = gate(root.path());
    {
        let update = install.update().unwrap();
        update.set_gate(&g).unwrap();
        g.phase = GatePhase::Starting;
        g.allowed_generation = Some("b".repeat(64));
        update.set_gate(&g).unwrap();
    }
    let ctx = StartupContext {
        operation_id: g.operation_id.clone(),
        release_file: g.release_file.clone(),
    };
    assert!(install.startup(&"b".repeat(64), None).is_err());
    assert!(install.startup(&"a".repeat(64), Some(&ctx)).is_err());
    assert!(
        install
            .startup(
                &"b".repeat(64),
                Some(&StartupContext {
                    operation_id: uuid::Uuid::new_v4().to_string(),
                    ..ctx.clone()
                })
            )
            .is_err()
    );
    drop(install.startup(&"b".repeat(64), Some(&ctx)).unwrap());
    physical::atomic(
        &g.release_file,
        &serde_json::json!({"schema":1,"operationId":g.operation_id,"released":true}),
    )
    .unwrap();
    assert!(
        install.startup(&"b".repeat(64), Some(&ctx)).is_err(),
        "published starting must first reconcile released gate"
    );
    {
        let update = install.update().unwrap();
        let mut closing = g.clone();
        closing.phase = GatePhase::Closing;
        closing.allowed_generation = None;
        assert!(update.set_gate(&closing).is_err());
        g.phase = GatePhase::Released;
        update.set_gate(&g).unwrap();
    }
    drop(install.startup(&"b".repeat(64), None).unwrap());
    drop(install.startup(&"b".repeat(64), Some(&ctx)).unwrap());
}
#[test]
fn new_operation_only_replaces_a_released_gate_and_corruption_fails_closed() {
    let root = tempfile::tempdir().unwrap();
    let install = Installation::open(root.path()).unwrap();
    let g = gate(root.path());
    let update = install.update().unwrap();
    update.set_gate(&g).unwrap();
    assert!(update.set_gate(&gate(root.path())).is_err());
    drop(update);
    fs::write(root.path().join("control/native-admission.json"), b"{").unwrap();
    assert!(install.startup(&"a".repeat(64), None).is_err());
    assert!(install.update().unwrap().gate().is_err());
}
#[test]
fn startup_context_without_durable_gate_never_authorizes_spawn() {
    let root = tempfile::tempdir().unwrap();
    let install = Installation::open(root.path()).unwrap();
    let g = gate(root.path());
    assert!(
        install
            .startup(
                &"a".repeat(64),
                Some(&StartupContext {
                    operation_id: g.operation_id,
                    release_file: g.release_file
                })
            )
            .is_err()
    );
}

#[test]
fn generation_switch_requires_returning_to_closed_admission() {
    let root = tempfile::tempdir().unwrap();
    let install = Installation::open(root.path()).unwrap();
    let mut g = gate(root.path());
    let update = install.update().unwrap();
    update.set_gate(&g).unwrap();
    g.phase = GatePhase::Starting;
    g.allowed_generation = Some("a".repeat(64));
    update.set_gate(&g).unwrap();
    let mut other = g.clone();
    other.allowed_generation = Some("b".repeat(64));
    assert!(
        update.set_gate(&other).is_err(),
        "a generation switch needs a closed-gate exit proof from the caller"
    );
    g.phase = GatePhase::Closing;
    g.allowed_generation = None;
    update.set_gate(&g).unwrap();
    update.set_gate(&other).unwrap();
}

#[cfg(windows)]
#[test]
fn live_lock_path_cannot_be_replaced_to_bypass_other_processes() {
    let root = tempfile::tempdir().unwrap();
    let install = Installation::open(root.path()).unwrap();
    let startup = install.startup(&"a".repeat(64), None).unwrap();
    let path = root.path().join("control/native-admission.lock");
    assert!(fs::remove_file(&path).is_err());
    assert!(fs::rename(&path, path.with_extension("replaced")).is_err());
    assert!(install.update().is_err());
    drop(startup);
    drop(install.update().unwrap());
}

#[test]
fn cancel_starting_only_removes_guard_owned_record_while_shared_is_held() {
    let root = tempfile::tempdir().unwrap();
    let install = Installation::open(root.path()).unwrap();
    let own = record(root.path());
    let other = record(root.path());
    let startup = install.startup(&own.generation, None).unwrap();
    let other_startup = install.startup(&other.generation, None).unwrap();
    startup.register(&own).unwrap();
    other_startup.register(&other).unwrap();
    assert!(startup.cancel_starting(&other).is_err());
    assert!(install.record_path(&own.runtime_id).unwrap().exists());
    assert!(install.record_path(&other.runtime_id).unwrap().exists());
    startup.cancel_starting(&own).unwrap();
    assert!(!install.record_path(&own.runtime_id).unwrap().exists());
    assert!(install.update().is_err(), "cancellation must retain shared");
    drop(startup);
    drop(other_startup);
    assert_eq!(install.update().unwrap().registrations().unwrap(), vec![other]);
}

#[test]
fn cancel_starting_preserves_record_changed_since_registration() {
    let root = tempfile::tempdir().unwrap();
    let install = Installation::open(root.path()).unwrap();
    let own = record(root.path());
    let startup = install.startup(&own.generation, None).unwrap();
    startup.register(&own).unwrap();
    let mut changed = own.clone();
    changed.bridge.created_at += 1;
    physical::atomic(&install.record_path(&own.runtime_id).unwrap(), &changed).unwrap();
    assert!(startup.cancel_starting(&own).is_err());
    assert!(startup.cancel_starting(&changed).is_err());
    drop(startup);
    assert_eq!(install.update().unwrap().registrations().unwrap(), vec![changed]);
}

#[test]
fn cancel_starting_never_removes_registered_native_evidence() {
    let root = tempfile::tempdir().unwrap();
    let install = Installation::open(root.path()).unwrap();
    let own = record(root.path());
    let startup = install.startup(&own.generation, None).unwrap();
    startup.register(&own).unwrap();
    let mut registered = own.clone();
    registered.phase = RegistrationPhase::Registered;
    registered.native = Some(NativeProcess { pid: 200, created_at: 20 });
    fs::create_dir(root.path().join("runtimes")).unwrap();
    physical::atomic(&registered.descriptor, &serde_json::json!({
        "pid": registered.bridge.pid, "runtimeId": registered.runtime_id,
        "startupAdmissionProtocol": 1, "startupAdmission": registered
    })).unwrap();
    startup.registered(&registered).unwrap();
    assert!(startup.cancel_starting(&own).is_err());
    assert!(startup.cancel_starting(&registered).is_err());
    drop(startup);
    assert_eq!(install.update().unwrap().registrations().unwrap(), vec![registered]);
}
