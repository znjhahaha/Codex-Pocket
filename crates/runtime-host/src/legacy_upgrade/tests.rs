use super::*;
fn fixture() -> (Descriptor, Health, Value, Value, Value) {
    let generation = uuid::Uuid::new_v4().to_string();
    let old = uuid::Uuid::new_v4().to_string();
    let process = NativeProcess {
        pid: 10,
        created_at: 100,
    };
    let native = NativeProcess {
        pid: 11,
        created_at: 110,
    };
    let d = Descriptor {
        protocol: 2,
        runtime_id: "r".into(),
        generation: generation.clone(),
        pid: 10,
        native_pid: 11,
        host_process: Some(process.clone()),
        native_process: Some(native.clone()),
        port: 1,
        token: "a".repeat(64),
        config_home: std::env::temp_dir(),
        source: "cli".into(),
    };
    let h = Health {
        protocol: 2,
        runtime_id: "r".into(),
        generation: generation.clone(),
        pid: 10,
        native_pid: 11,
        host_process: Some(process),
        native_process: Some(native),
        initialized: true,
        capabilities: Default::default(),
        active_turns: 0,
        pending_requests: 0,
        reconciliation_required: true,
        scoped_admission: 0,
    };
    let disk = json!({"schema":2,"runtime_id":"r","generation":generation,"reconciliation_required":true,
        "commands":{"s:old":{"generation":old,"completed":false}},"answers":{},"runs":{},"pending":{}});
    let recovery = json!({"generation":generation,"reconciliationRequired":true,"commandCount":1,"answerCount":0,
        "unconfirmedWrites":1,"unknownOperations":1,"runs":[],"pendingRequests":[]});
    (d, h, disk, recovery, json!({"data":[],"nextCursor":null}))
}
#[test]
fn legacy_retirement_requires_cold_only_unknown_closed_writes_exact_owner_and_empty_native() {
    let (d, h, disk, recovery, loaded) = fixture();
    validate(&d, &h, &disk, &recovery, &loaded).unwrap();
    for fault in [
        "owner",
        "active",
        "pending",
        "open",
        "current",
        "loaded",
        "pagination",
        "count",
        "running",
        "unknown-answer",
    ] {
        let mut h = h.clone();
        let mut disk = disk.clone();
        let mut recovery = recovery.clone();
        let mut loaded = loaded.clone();
        match fault {
            "owner" => h.host_process.as_mut().unwrap().created_at += 1,
            "active" => h.active_turns = 1,
            "pending" => h.pending_requests = 1,
            "open" => h.reconciliation_required = false,
            "current" => disk["commands"]["s:old"]["generation"] = json!(d.generation),
            "loaded" => loaded["data"] = json!(["t"]),
            "pagination" => loaded["nextCursor"] = json!("next"),
            "count" => recovery["commandCount"] = json!(0),
            "running" => disk["runs"] = json!({"r":{"state":"unknown"}}),
            "unknown-answer" => {
                disk["answers"] = json!({"n:0":{"generation":d.generation,"completed":false}})
            }
            _ => unreachable!(),
        }
        assert!(
            validate(&d, &h, &disk, &recovery, &loaded).is_err(),
            "{fault}"
        );
    }
}
#[test]
fn process_guard_rejects_reused_identity_and_readonly_checks_do_not_terminate() {
    let actual = NativeProcess::capture(std::process::id()).unwrap();
    let guard = ProcessGuard::open(&actual, false).unwrap();
    assert!(!guard.ended().unwrap());
    assert!(guard.binary().unwrap().is_file());
    assert!(
        ProcessGuard::open(
            &NativeProcess {
                created_at: actual.created_at - 1,
                ..actual
            },
            false
        )
        .is_err()
    );
}

#[test]
fn immutable_archive_retry_allows_only_completed_probe_receipts_and_keeps_all_originals() {
    let (d, _, before, _, _) = fixture();
    let mut after = before.clone();
    let key = format!("s:legacy-upgrade-read:{}", uuid::Uuid::new_v4());
    after["commands"][&key] = json!({"generation":d.generation,"completed":true});
    assert!(archived_inventory_matches(&before, &after, &d.generation));
    after["commands"][&key]["completed"] = json!(false);
    assert!(!archived_inventory_matches(&before, &after, &d.generation));
    after = before.clone();
    after["commands"]["s:old"]["completed"] = json!(true);
    assert!(!archived_inventory_matches(&before, &after, &d.generation));
    after["commands"].as_object_mut().unwrap().remove("s:old");
    assert!(!archived_inventory_matches(&before, &after, &d.generation));
}

#[tokio::test]
#[ignore = "Explicit read-only installed Host evidence probe"]
async fn installed_legacy_upgrade_readonly_probe() {
    let root = std::env::var_os("POCKET_LEGACY_HOST_STATE").expect("explicit state path");
    let home = std::env::var_os("POCKET_LEGACY_CONFIG_HOME").expect("explicit config home");
    let generation =
        std::env::var("POCKET_LEGACY_HOST_GENERATION").expect("explicit expected generation");
    let root = std::path::PathBuf::from(root);
    let result = legacy_upgrade(
        &root,
        &root.join("descriptor.json"),
        Path::new(&home),
        &generation,
        false,
    )
    .await;
    match result {
        Ok(value) => println!("{value}"),
        Err(error) => panic!("Read-only legacy upgrade evidence: {error:#}"),
    }
}
