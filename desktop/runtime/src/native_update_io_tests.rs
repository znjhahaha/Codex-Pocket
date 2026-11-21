use super::*;

/// Read-only discriminating check against the retained real failing fixture.
/// No helper, native process, RPC, receipt or journal mutation occurs here.
#[cfg(windows)]
#[test]
#[ignore="requires an explicit retained marked reverse-helper fixture"]
fn reverse_helper_dispatch_uses_verified_committed_target_and_same_operation() {
    let root=PathBuf::from(std::env::var_os("POCKET_REVERSE_HELPER_DIAGNOSTIC_FIXTURE").expect("explicit fixture"));
    storage::unredirected(&root).unwrap();
    assert_eq!(std::fs::read(root.join(".fixture-owner")).unwrap(),b"native-consumer-combined");
    let state=root.join("install/isolated state");
    let j=read_status(&state).unwrap().unwrap();let h=upgrade::read_status(&state).unwrap().unwrap();
    assert_eq!(j.install_root,root.join("install"));assert_eq!(j.state_directory,state);
    let command=helper_recovery_command(&j,&h).unwrap();
    let expected=j.install_root.join("bin").join(&j.from_generation).join("pocket-upgrade.exe");
    assert_eq!(command.get_program(),expected);
    assert_ne!(command.get_program(),j.install_root.join("bin").join(&j.to_generation).join("pocket-upgrade.exe"));
    assert_eq!(command.get_args().collect::<Vec<_>>(),vec![std::ffi::OsStr::new("--install-root"),j.install_root.as_os_str(),std::ffi::OsStr::new("--state"),j.state_directory.as_os_str(),std::ffi::OsStr::new("--operation-id"),std::ffi::OsStr::new(&h.operation_id)]);
    let mut other=h.clone();other.install_root=root.join("foreign");assert!(helper_recovery_command(&j,&other).is_err());
    other=h.clone();other.to_generation=j.to_generation.clone();assert!(helper_recovery_command(&j,&other).is_err());
    other=h.clone();other.operation_id="not-an-operation".into();assert!(helper_recovery_command(&j,&other).is_err());
    println!("PASS reverse helper dispatch binds verified committed target image, exact operation and root; mismatches rejected without process effects");
}

fn rows() -> Vec<Value> {
    let path = std::env::temp_dir().join("never-executed.exe");
    let identity = |pid, birth| json!({"pid":pid,"createdAt":birth,"executable":path});
    vec![
        json!({"pid":100,"createdAt":100,"executable":path,"sha256":"test","name":"associated-desktop"}),
        json!({"pid":400,"createdAt":120,"executable":path,"sha256":"test","name":"pocket-bridge.exe","parentPid":100,"parentIdentity":identity(100,100)}),
        json!({"pid":500,"createdAt":130,"executable":path,"sha256":"test","name":"associated-native","parentPid":400,"parentIdentity":identity(400,120)}),
        json!({"pid":200,"createdAt":300,"executable":path,"sha256":"test","name":"desktop-child","parentPid":100,"parentIdentity":identity(100,100)}),
    ]
}
#[test]
fn complete_creation_bound_ancestry_is_accepted() {
    let v = rows();
    verify_ancestry(&v, &image(&v[0]).unwrap()).unwrap();
}
#[test]
fn reused_parent_does_not_own_an_older_live_orphan() {
    let mut v = rows();
    let mut orphan = v[3].clone();
    orphan["pid"] = json!(300);
    orphan["createdAt"] = json!(200);
    orphan["parentPid"] = json!(200);
    orphan["parentIdentity"] = serde_json::to_value(image(&v[3]).unwrap().identity).unwrap();
    v.push(orphan);
    assert!(verify_ancestry(&v, &image(&v[0]).unwrap()).is_err());
}
#[test]
fn missing_stale_duplicate_and_disconnected_parent_evidence_is_rejected() {
    for mutate in [
        |v: &mut Vec<Value>| {
            v[3]["parentIdentity"] = Value::Null;
        },
        |v: &mut Vec<Value>| {
            v[3]["parentIdentity"]["createdAt"] = json!(99);
        },
        |v: &mut Vec<Value>| {
            v[3]["parentIdentity"]["executable"] = json!("different");
        },
        |v: &mut Vec<Value>| {
            v[3]["parentPid"] = json!(999);
        },
        |v: &mut Vec<Value>| {
            v[3]["createdAt"] = json!(100);
        },
        |v: &mut Vec<Value>| {
            v.push(v[3].clone());
        },
        |v: &mut Vec<Value>| {
            v[3]["name"] = json!("unowned");
        },
    ] {
        let mut v = rows();
        mutate(&mut v);
        assert!(verify_ancestry(&v, &image(&v[0]).unwrap()).is_err());
    }
}

#[test]
fn complete_multi_bridge_projection_keeps_each_native_and_descriptor_bound() {
    let dir=crate::test_support::tempdir();let root=dir.path();
    std::fs::create_dir(root.join("runtimes")).unwrap();
    let mut v=rows();let mut bridge=v[1].clone();bridge["pid"]=json!(600);bridge["createdAt"]=json!(140);
    let mut native=v[2].clone();native["pid"]=json!(601);native["createdAt"]=json!(145);
    native["parentPid"]=json!(600);native["parentIdentity"]=serde_json::to_value(image(&bridge).unwrap().identity).unwrap();
    v.extend([bridge,native]);
    let descriptors:Vec<_>=[400,600].into_iter().map(|pid|{
        let p=root.join(format!("runtimes/{pid}.json"));
        storage::atomic(&p,&json!({"pid":pid,"protocol":1,"source":"desktop","binary":v[0]["executable"],"runtimeId":format!("runtime-{pid}")})).unwrap();p
    }).collect();
    let mut projection=json!({"group":v,"descriptors":descriptors});
    let p=inventory_projection(root,&projection).unwrap().unwrap();
    assert_eq!(p.additional.len(),1);assert_eq!(p.bridge.identity.pid,400);
    assert_eq!(p.additional[0].bridge.identity.pid,600);assert_eq!(p.additional[0].native.identity.pid,601);
    assert_eq!(images(&p).len(),6);
    projection["group"][5]["parentPid"]=json!(400);
    assert!(inventory_projection(root,&projection).is_err());
}

#[cfg(windows)]
#[test]
fn production_stop_defers_before_process_access_and_records_missing_admission() {
    use std::fs;
    let dir = crate::test_support::tempdir();
    let root = dir.path();
    let state = root.join("state");
    storage::private_dir(&storage::control(&state)).unwrap();
    fs::create_dir(root.join("runtimes")).unwrap();
    let manifest = b"{\"files\":[]}";
    let generation = format!("{:x}", Sha256::digest(manifest));
    let bin = root.join("bin").join(&generation);
    fs::create_dir_all(&bin).unwrap();
    fs::write(bin.join("windows-manifest.json"), manifest).unwrap();
    storage::atomic(&root.join("desktop-install.json"),&json!({
        "version":3,"installRoot":root,"stateDirectory":state,
        "generation":generation,"releaseHash":generation,"bridge":root.join("pocket-launcher.exe")
    })).unwrap();
    // No OS process owns these identities. Admission rejection must happen
    // before even querying them, let alone opening PROCESS_TERMINATE handles.
    let mut member = image(&rows()[0]).unwrap();
    member.identity.pid = 0;
    let p = Plan {
        additional: vec![],
        ancestry: vec![],
        desktop: member.clone(),
        bridge: member.clone(),
        native: member,
        children: vec![],
        descriptor: root.join("runtimes/fixture.json"),
        runtime_id: "fixture".into(),
    };
    let result = Batch::open(&p);
    assert!(result.is_err());
    assert_eq!(
        result.err().unwrap().to_string(),
        "native_admission_journal_missing"
    );
    let record: Value =
        storage::read(&storage::control(&state).join("native-stop-deferred.json")).unwrap();
    assert_eq!(
        record["reason"],
        "native_admission_journal_missing"
    );
    assert_eq!(record["phase"], "before_process_handles");
    assert_eq!(
        record["desktop"],
        serde_json::to_value(p.desktop.identity).unwrap()
    );
    assert!(!storage::control(&state).join("native-update.json").exists());
}
