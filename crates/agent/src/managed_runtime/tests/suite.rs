use crate::managed_runtime::{Controller, Installation, Registry, SelectedProfile};
use pocket_runtime_ipc::{Descriptor, HostClient};
use serde_json::{Value, json};
use std::{path::Path, time::Duration};

#[path = "fixture.rs"]
mod fixture;
use fixture::*;

fn msix_restore_fixture(f:&Fixture)->(SelectedProfile,SelectedProfile,Controller) {
    use crate::managed_runtime::discovery::MsixPackage;
    let root=f.dir.path().join("WindowsApps");
    let old=root.join("OpenAI.Codex_26.1103.7773.0_x64__2p2nqsd0c76g0/app/resources/codex.exe");
    let name="OpenAI.Codex_26.1106.6628.0_x64__2p2nqsd0c76g0".to_string();
    let package=MsixPackage{location:root.join(&name),full_name:name};
    let new=package.location.join("app/resources/codex.exe");
    for path in [&old,&new] {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::copy(native_fixture(),path).unwrap();
    }
    let mut registry=f.registry.clone();registry.msix_packages=Some(vec![package]);
    let controller=Controller::new(Installation::new(&f.bin,f.generation.clone()).unwrap(),registry);
    (SelectedProfile::new(f.profile.config_home(),old).unwrap(),SelectedProfile::new(f.profile.config_home(),new).unwrap(),controller)
}

#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn msix_restore_dead_original_owners_keep_unknown_recovery_and_use_new_binary() {
    let f=Fixture::new();let(old,new,controller)=msix_restore_fixture(&f);
    let first=controller.ensure(&old).await.unwrap();
    let descriptor=f.state().join("descriptor.json");
    let d=pocket_runtime_ipc::load_descriptor(&descriptor,old.config_home()).unwrap();
    let deferred=first.request(json!("unknown-msix"),"fixture/deferred",json!({}));
    tokio::pin!(deferred);
    tokio::select! {r=&mut deferred=>panic!("Fixture write unexpectedly completed: {r:?}"),_=tokio::time::sleep(Duration::from_millis(120))=>{}}
    assert_eq!(first.receipt(json!("unknown-msix")).await.unwrap().state,"unknown");
    let stop=old.config_home().join("fixture-stop");std::fs::write(&stop,b"stop only the fake native").unwrap();
    ended(&d).await;std::fs::remove_file(&stop).unwrap();
    std::fs::remove_file(old.native_binary()).unwrap();
    let journal=f.state().join("recovery.json");
    let uncertain=read_json(&journal)["commands"]["s:unknown-msix"].clone();assert!(uncertain.is_object());
    let trace=old.config_home().join("fixture-methods");let before_methods=std::fs::read_to_string(&trace).unwrap();
    let account=f.profile.config_home().join("fixture-account");std::fs::write(&account,b"preserve account").unwrap();
    let next=controller.ensure(&new).await.unwrap();
    assert_ne!(next.generation(),first.generation());
    assert_eq!(next.receipt(json!("unknown-msix")).await.unwrap().state,"unknown");
    assert!(next.request(json!("unknown-msix"),"fixture/info",json!({})).await.is_err());
    assert!(next.health().await.unwrap().reconciliation_required);
    next.request(json!("msix-read"),"thread/read",json!({"threadId":"fixture-thread","includeTurns":true})).await.unwrap();
    let methods=std::fs::read_to_string(&trace).unwrap();let new_methods=&methods[before_methods.len()..];
    assert!(!new_methods.contains("fixture/deferred"));assert!(!new_methods.contains("fixture/info"));
    assert!(!new_methods.contains("turn/start"));assert!(!new_methods.contains("thread/start"));
    assert!(next.health().await.unwrap().reconciliation_required);
    assert_eq!(read_json(&journal)["commands"]["s:unknown-msix"],uncertain);
    assert_eq!(read_json(&f.state().join("startup.json"))["nativeBinary"],json!(new.native_binary()));
    assert_eq!(std::fs::read(&account).unwrap(),b"preserve account");
    assert!(descriptor.exists());assert!(journal.exists());
    assert!(next.shutdown_idle().await.is_err());
}

#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn msix_restore_live_original_owner_never_overwrites_records_or_starts_another_host() {
    let f=Fixture::new();let(old,new,controller)=msix_restore_fixture(&f);
    let first=controller.ensure(&f.profile).await.unwrap();
    let descriptor=f.state().join("descriptor.json");let d=pocket_runtime_ipc::load_descriptor(&descriptor,old.config_home()).unwrap();
    let startup=f.state().join("startup.json");let mut owner=read_json(&startup);
    std::fs::remove_file(old.native_binary()).unwrap();owner["nativeBinary"]=json!(old.native_binary());
    crate::storage::atomic_write(&startup,&serde_json::to_vec(&owner).unwrap()).unwrap();
    let paths=[startup,descriptor,f.state().join("recovery.json")];
    let before:Vec<_>=paths.iter().map(|p|std::fs::read(p).unwrap()).collect();
    let error=controller.ensure(&new).await.err().unwrap();
    assert_eq!(error.downcast_ref::<crate::managed_runtime::discovery::RestoreFailure>(),Some(&crate::managed_runtime::discovery::RestoreFailure::OwnershipUnconfirmed));
    for (path,bytes) in paths.iter().zip(before) {assert_eq!(std::fs::read(path).unwrap(),bytes);}
    assert_eq!(first.health().await.unwrap().native_pid,d.native_pid);
    first.shutdown_idle().await.unwrap();ended(&d).await;
}

#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn msix_restore_missing_native_creation_identity_never_discards_recovery() {
    let f=Fixture::new();let(old,new,controller)=msix_restore_fixture(&f);
    let first=controller.ensure(&old).await.unwrap();
    let descriptor=f.state().join("descriptor.json");let d=pocket_runtime_ipc::load_descriptor(&descriptor,old.config_home()).unwrap();
    first.shutdown_idle().await.unwrap();ended(&d).await;
    std::fs::remove_file(old.native_binary()).unwrap();
    let mut value=read_json(&descriptor);
    for native in [None,Some(pocket_core::native_process::NativeProcess::capture(std::process::id()).unwrap())] {
        value["nativeProcess"]=json!(native);
        value["nativePid"]=json!(native.as_ref().map_or(d.native_pid,|p|p.pid));
        crate::storage::atomic_write(&descriptor,&serde_json::to_vec(&value).unwrap()).unwrap();
        let paths=[f.state().join("startup.json"),descriptor.clone(),f.state().join("recovery.json")];
        let before:Vec<_>=paths.iter().map(|p|std::fs::read(p).unwrap()).collect();
        let error=controller.ensure(&new).await.err().unwrap();
        assert_eq!(error.downcast_ref::<crate::managed_runtime::discovery::RestoreFailure>(),Some(&crate::managed_runtime::discovery::RestoreFailure::OwnershipUnconfirmed));
        for (path,bytes) in paths.iter().zip(before) {assert_eq!(std::fs::read(path).unwrap(),bytes);}
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn preparation_replay_excludes_rpc_replies_but_keeps_receipts_and_cursor() {
    let f = Fixture::new();
    let runtime = f.controller.ensure(&f.profile).await.unwrap();
    runtime
        .request(
            json!("account"),
            "account/read",
            json!({"refreshToken":false}),
        )
        .await
        .unwrap();
    let reads = runtime.events(0).await.unwrap();
    assert!(
        reads.events.is_empty(),
        "read replies must not enter the assistant event projection"
    );
    assert!(
        reads.next_after > 0,
        "filtered replies must still advance the replay cursor"
    );
    let receipt = runtime.receipt(json!("account")).await.unwrap();
    assert_eq!(receipt.state, "completed");
    assert_eq!(
        receipt.response.unwrap()["result"]["requiresOpenaiAuth"],
        true
    );
    runtime
        .request(json!("event"), "fixture/finish", json!({}))
        .await
        .unwrap();
    let events = runtime.events(reads.next_after).await.unwrap();
    assert_eq!(events.events.len(), 1);
    assert_eq!(events.events[0].event["method"], "turn/completed");
    assert!(events.next_after > events.events[0].sequence);
    let descriptor = pocket_runtime_ipc::load_descriptor(
        &f.state().join("descriptor.json"),
        f.profile.config_home(),
    )
    .unwrap();
    runtime.shutdown_idle().await.unwrap();
    ended(&descriptor).await;
}

#[test]
#[ignore = "invoked only as an isolated Controller subprocess by cross_process_ensure"]
fn controller_process_fixture() {
    let path = std::env::var("MANAGED_FIXTURE_ROOT").unwrap();
    let root = Path::new(&path);
    let generation = std::env::var("MANAGED_FIXTURE_GENERATION").unwrap();
    let binary = std::env::var("MANAGED_FIXTURE_NATIVE").unwrap();
    let result = std::env::var("MANAGED_FIXTURE_RESULT").unwrap();
    let controller = Controller::new(
        Installation::new(root.join("bin"), generation).unwrap(),
        Registry::new(root.join("registry")).unwrap(),
    );
    let profile = SelectedProfile::new(root.join("home"), Path::new(&binary)).unwrap();
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let host = runtime.block_on(controller.ensure(&profile)).unwrap();
    let health = runtime.block_on(host.health()).unwrap();
    std::fs::write(result, serde_json::to_vec(&health).unwrap()).unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn cross_process_ensure_reuses_one_host_after_both_controllers_exit() {
    let f = Fixture::new();
    let spawn = |name: &str| {
        let root = f.dir.path().to_owned();
        let generation = f.generation.clone();
        let binary = native_fixture().to_owned();
        let name = name.to_owned();
        async move {
            let mut command = tokio::process::Command::new(std::env::current_exe().unwrap());
            command
                .args([
                    "--ignored",
                    "--exact",
                    "suite::controller_process_fixture",
                    "--nocapture",
                ])
                .env("MANAGED_FIXTURE_ROOT", &root)
                .env("MANAGED_FIXTURE_GENERATION", generation)
                .env("MANAGED_FIXTURE_NATIVE", binary)
                .env("MANAGED_FIXTURE_RESULT", root.join(name));
            command
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .status()
                .await
        }
    };
    let (one, two) = tokio::time::timeout(Duration::from_secs(20), async {
        tokio::join!(spawn("one.json"), spawn("two.json"))
    })
    .await
    .expect("isolated controllers must exit promptly");
    for result in [one, two] {
        let result = result.unwrap();
        assert!(result.success(), "isolated controller exit: {result}");
    }
    let one = read_json(&f.dir.path().join("one.json"));
    let two = read_json(&f.dir.path().join("two.json"));
    assert_eq!(one["pid"], two["pid"]);
    assert_eq!(one["nativePid"], two["nativePid"]);
    assert_eq!(one["generation"], two["generation"]);
    let host = f.controller.ensure(&f.profile).await.unwrap();
    assert_eq!(
        host.health().await.unwrap().native_pid,
        one["nativePid"].as_u64().unwrap() as u32
    );
    let d = pocket_runtime_ipc::load_descriptor(
        &f.state().join("descriptor.json"),
        f.profile.config_home(),
    )
    .unwrap();
    host.shutdown_idle().await.unwrap();
    ended(&d).await;
}

#[cfg(windows)]
#[tokio::test]
async fn junction_homes_and_installations_are_rejected() {
    let f = Fixture::new();
    let link = f.dir.path().join("alias");
    let output = std::process::Command::new("cmd.exe")
        .args(["/C", "mklink", "/J"])
        .arg(&link)
        .arg(f.profile.config_home())
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(SelectedProfile::new(&link, native_fixture()).is_err());
    // Directory junction creation requires no symbolic-link privilege.
    let install_link = f.dir.path().join("linked-bin");
    assert!(
        std::process::Command::new("cmd.exe")
            .args(["/C", "mklink", "/J"])
            .arg(&install_link)
            .arg(&f.bin)
            .status()
            .unwrap()
            .success()
    );
    assert!(Installation::new(&install_link, &f.generation).is_err());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn single_home_concurrent_ensure_alias_other_home_and_drop() {
    let f = Fixture::new();
    let (a, b) = tokio::join!(
        f.controller.ensure(&f.profile),
        f.controller.ensure(&f.profile)
    );
    let a = a.unwrap();
    let b = b.unwrap();
    assert_eq!(a.generation(), b.generation());
    let d = pocket_runtime_ipc::load_descriptor(
        &f.state().join("descriptor.json"),
        f.profile.config_home(),
    )
    .unwrap();
    let info = a
        .request(json!("info"), "fixture/info", json!({}))
        .await
        .unwrap();
    assert_eq!(info["result"]["initialized"], 1);
    assert_eq!(info["result"]["writes"], 0);
    assert_eq!(info["result"]["ready"], true);
    assert_eq!(
        Path::new(info["result"]["home"].as_str().unwrap()),
        f.profile.config_home()
    );
    let alias = SelectedProfile::new(f.profile.config_home().join("."), native_fixture()).unwrap();
    assert_eq!(
        f.controller.ensure(&alias).await.unwrap().generation(),
        a.generation()
    );
    drop(a);
    drop(b);
    let new_controller = Controller::new(
        Installation::new(&f.bin, f.generation.clone()).unwrap(),
        f.registry.clone(),
    );
    let attached = new_controller.ensure(&f.profile).await.unwrap();
    assert_eq!(attached.health().await.unwrap().native_pid, d.native_pid);
    let other_home = f.dir.path().join("other");
    std::fs::create_dir(&other_home).unwrap();
    let other = SelectedProfile::new(&other_home, native_fixture()).unwrap();
    let other_runtime = f.controller.ensure(&other).await.unwrap();
    assert_ne!(
        other_runtime.health().await.unwrap().native_pid,
        d.native_pid
    );
    let od = pocket_runtime_ipc::load_descriptor(
        &f.registry
            .profile_directory(&other)
            .unwrap()
            .join("descriptor.json"),
        &other_home,
    )
    .unwrap();
    other_runtime.shutdown_idle().await.unwrap();
    ended(&od).await;
    attached.shutdown_idle().await.unwrap();
    ended(&d).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn real_events_stable_receipts_human_answer_independent_and_idle_guard() {
    let f = Fixture::new();
    let runtime = f.controller.ensure(&f.profile).await.unwrap();
    let d = pocket_runtime_ipc::load_descriptor(
        &f.state().join("descriptor.json"),
        f.profile.config_home(),
    )
    .unwrap();
    let (tx, mut rx) = tokio::sync::mpsc::channel(128);
    let connected = runtime.attach(tx, None).await.unwrap();
    let selected = SelectedProfile::from_runtime(&connected).unwrap();
    assert_eq!(
        selected.native_binary(),
        dunce::canonicalize(native_fixture()).unwrap()
    );
    assert_eq!(runtime.runtime_id(), connected.runtime_id);
    assert!(matches!(
        runtime.role(),
        crate::native::NativeRole::ManagedHost { .. }
    ));
    assert!(matches!(
        connected.role,
        crate::native::NativeRole::ManagedHost { .. }
    ));
    connected.rpc.activate_events();
    runtime
        .request(
            json!("task"),
            "turn/start",
            json!({"threadId":"fixture-thread"}),
        )
        .await
        .unwrap();
    runtime
        .request(
            json!("task"),
            "turn/start",
            json!({"threadId":"fixture-thread"}),
        )
        .await
        .unwrap();
    assert_eq!(
        runtime.receipt(json!("task")).await.unwrap().state,
        "completed"
    );
    assert!(runtime.shutdown_idle().await.is_err());
    let request = runtime.request(json!("human-op"), "fixture/waitForHuman", json!({}));
    let answer = async {
        tokio::time::timeout(Duration::from_secs(4), async {
            loop {
                if let Some(crate::native::NativeEvent::Message { value, .. }) = rx.recv().await {
                    if value["id"] == 77 {
                        break;
                    }
                }
            }
        })
        .await
        .unwrap();
        let receipt = runtime
            .answer(json!(77), json!({"result":{"answers":{}}}))
            .await
            .unwrap();
        assert_eq!(receipt.state, "completed");
    };
    let (request, ()) = tokio::join!(request, answer);
    assert_eq!(request.unwrap()["result"]["answered"], true);
    let events = runtime.events(0).await.unwrap();
    assert!(
        events
            .events
            .iter()
            .any(|e| e.event["method"] == "turn/started")
    );
    let mut independent = HostClient::attach(&d).await.unwrap();
    let deferred = runtime.request(json!("unknown"), "fixture/deferred", json!({}));
    tokio::pin!(deferred);
    tokio::select! { r=&mut deferred => panic!("deferred unexpectedly completed: {r:?}"), _=tokio::time::sleep(Duration::from_millis(120)) => {} }
    assert_eq!(
        runtime.receipt(json!("unknown")).await.unwrap().state,
        "unknown"
    );
    // Receipt reads do not queue behind the in-flight request. Release through fixture only.
    independent
        .request(json!("release"), "fixture/release", json!({}))
        .await
        .unwrap();
    deferred.await.unwrap();
    let info = independent
        .request(json!("info2"), "fixture/info", json!({}))
        .await
        .unwrap();
    assert_eq!(info["result"]["writes"], 2);
    assert_eq!(info["result"]["answers"], 1);
    assert!(
        runtime
            .request(json!("task"), "turn/start", json!({"threadId":"other"}))
            .await
            .is_err()
    );
    connected.rpc.detach_managed_host();
    drop(connected);
    assert_eq!(runtime.health().await.unwrap().native_pid, d.native_pid);
    independent
        .request(json!("finish"), "fixture/finish", json!({}))
        .await
        .unwrap();
    runtime.shutdown_idle().await.unwrap();
    ended(&d).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn cancelled_operation_keeps_same_receipt_and_never_rewrites_native() {
    let f = Fixture::new();
    let runtime = std::sync::Arc::new(f.controller.ensure(&f.profile).await.unwrap());
    let d = pocket_runtime_ipc::load_descriptor(
        &f.state().join("descriptor.json"),
        f.profile.config_home(),
    )
    .unwrap();
    let work = tokio::spawn({
        let runtime = runtime.clone();
        async move {
            runtime
                .request(json!("cancelled"), "fixture/deferred", json!({}))
                .await
        }
    });
    tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            if runtime.receipt(json!("cancelled")).await.unwrap().state == "unknown" {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    work.abort();
    let _ = work.await;
    let reattached = f.controller.ensure(&f.profile).await.unwrap();
    assert_eq!(reattached.generation(), runtime.generation());
    assert!(
        reattached
            .request(json!("cancelled"), "fixture/deferred", json!({}))
            .await
            .is_err()
    );
    assert!(reattached.shutdown_idle().await.is_err());
    let mut independent = HostClient::attach(&d).await.unwrap();
    let info = independent
        .request(json!("count"), "fixture/info", json!({}))
        .await
        .unwrap();
    assert_eq!(info["result"]["writes"], 1);
    independent
        .request(json!("release"), "fixture/release", json!({}))
        .await
        .unwrap();
    assert_eq!(
        reattached.receipt(json!("cancelled")).await.unwrap().state,
        "completed"
    );
    reattached.shutdown_idle().await.unwrap();
    ended(&d).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn unavailable_live_and_missing_ownership_identity_never_restart() {
    let f = Fixture::new();
    let runtime = f.controller.ensure(&f.profile).await.unwrap();
    let path = f.state().join("descriptor.json");
    let original = crate::storage::read_physical(&path, 16384).unwrap();
    let d: Descriptor = serde_json::from_slice(&original).unwrap();
    let free = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = free.local_addr().unwrap().port();
    drop(free);
    let mut tampered = read_json(&path);
    tampered["port"] = json!(port);
    crate::storage::atomic_write(&path, &serde_json::to_vec(&tampered).unwrap()).unwrap();
    let error = f
        .controller
        .ensure(&f.profile)
        .await
        .err()
        .unwrap()
        .to_string();
    assert!(error.contains("cannot be proven"), "{error}");
    for field in ["hostProcess", "nativeProcess"] {
        let mut tampered = read_json(&path);
        tampered[field] = Value::Null;
        crate::storage::atomic_write(&path, &serde_json::to_vec(&tampered).unwrap()).unwrap();
        assert!(f.controller.ensure(&f.profile).await.is_err());
        crate::storage::atomic_write(&path, &original).unwrap();
    }
    let owner_path = f.state().join("startup.json");
    let owner = std::fs::read(&owner_path).unwrap();
    std::fs::remove_file(&owner_path).unwrap();
    assert!(f.controller.ensure(&f.profile).await.is_err());
    crate::storage::atomic_write(&owner_path, &owner).unwrap();
    std::fs::remove_file(&path).unwrap();
    assert!(f.controller.ensure(&f.profile).await.is_err());
    crate::storage::atomic_write(&path, &original).unwrap();
    assert_eq!(runtime.health().await.unwrap().native_pid, d.native_pid);
    runtime.shutdown_idle().await.unwrap();
    ended(&d).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn cold_completed_receipts_have_no_invented_result_and_native_read_resolves_only_matching_run()
 {
    let f = Fixture::new();
    let first = f.controller.ensure(&f.profile).await.unwrap();
    let path = f.state().join("descriptor.json");
    let d = pocket_runtime_ipc::load_descriptor(&path, f.profile.config_home()).unwrap();
    first
        .request(json!("done"), "fixture/info", json!({}))
        .await
        .unwrap();
    first.shutdown_idle().await.unwrap();
    ended(&d).await;
    let journal = f.state().join("recovery.json");
    let mut evidence = read_json(&journal);
    let matched = format!("{}:fixture-thread:fixture-turn", d.generation);
    let unmatched = format!("{}:other-thread:other-turn", d.generation);
    evidence["runs"][&matched] = json!({"generation":d.generation,"threadId":"fixture-thread","turnId":"fixture-turn","state":"active"});
    evidence["runs"][&unmatched] = json!({"generation":d.generation,"threadId":"other-thread","turnId":"other-turn","state":"active"});
    crate::storage::atomic_write(&journal, &serde_json::to_vec(&evidence).unwrap()).unwrap();
    let next = f.controller.ensure(&f.profile).await.unwrap();
    assert_ne!(next.generation(), first.generation());
    let receipt = next.receipt(json!("done")).await.unwrap();
    assert_eq!(receipt.state, "completed");
    assert!(receipt.response.is_none());
    assert!(
        next.request(json!("done"), "fixture/info", json!({}))
            .await
            .is_err()
    );
    assert!(next.health().await.unwrap().reconciliation_required);
    next.request(
        json!("read"),
        "thread/read",
        json!({"threadId":"fixture-thread","includeTurns":true}),
    )
    .await
    .unwrap();
    let recovery = next.recovery().await.unwrap();
    assert!(
        recovery["runs"]
            .as_array()
            .unwrap()
            .iter()
            .any(|r| r["turnId"] == "fixture-turn" && r["state"] == "completed")
    );
    assert!(
        recovery["runs"]
            .as_array()
            .unwrap()
            .iter()
            .any(|r| r["turnId"] == "other-turn" && r["state"] == "unknown")
    );
    assert!(next.health().await.unwrap().reconciliation_required);
    assert!(next.shutdown_idle().await.is_err());
    // Fixture Drop stops only its fake peer; unresolved evidence stays on disk.
}

#[tokio::test]
async fn manifest_tampering_and_uncertain_reservation_fail_closed() {
    let f = Fixture::new();
    std::fs::write(f.bin.join("pocket-runtime-host.exe"), b"tampered").unwrap();
    assert!(f.controller.ensure(&f.profile).await.is_err());
    assert!(!f.state().join("startup.json").exists());
    assert!(SelectedProfile::new(Path::new("relative"), native_fixture()).is_err());
    let f = Fixture::new();
    let state = f.state();
    crate::storage::private_directory(&state).unwrap();
    std::fs::write(state.join("ensure.lock"), b"").unwrap();
    let key = state.file_name().unwrap().to_str().unwrap();
    crate::storage::atomic_write(&state.join("startup.json"),&serde_json::to_vec(&json!({"schema":1,"homeKey":key,"configHome":f.profile.config_home(),"nativeBinary":f.profile.native_binary(),"installationGeneration":f.generation,"reservation":uuid::Uuid::new_v4().to_string(),"phase":"reserved","hostProcess":null,"descriptorGeneration":null})).unwrap()).unwrap();
    let error = f
        .controller
        .ensure(&f.profile)
        .await
        .err()
        .unwrap()
        .to_string();
    assert!(error.contains("uncertain"), "{error}");
    assert!(!state.join("descriptor.json").exists());
}
#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn fenced_attach_existing_preserves_spawned_ownership_and_live_generation() {
    let f=Fixture::new();
    let first=f.controller.ensure(&f.profile).await.unwrap();
    let startup=f.state().join("startup.json");
    let mut owner=read_json(&startup);owner["phase"]=json!("spawned");owner["descriptorGeneration"]=Value::Null;
    crate::storage::atomic_write(&startup,&serde_json::to_vec_pretty(&owner).unwrap()).unwrap();
    let bytes=std::fs::read(&startup).unwrap();
    drop(first);
    let attached=f.controller.attach_existing(&f.profile).await.unwrap();
    assert_eq!(std::fs::read(&startup).unwrap(),bytes,"Attach-only must not repair spawned ownership behind the fence");
    let descriptor=pocket_runtime_ipc::load_descriptor(&f.state().join("descriptor.json"),f.profile.config_home()).unwrap();
    assert_eq!(attached.generation(),descriptor.generation);
    assert_eq!(attached.health().await.unwrap().host_process,descriptor.host_process);
    attached.shutdown_idle().await.unwrap();ended(&descriptor).await;
}

#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn fenced_attach_existing_never_replaces_dead_host_or_discards_unknown_commands() {
    let f=Fixture::new();let runtime=f.controller.ensure(&f.profile).await.unwrap();
    let descriptor_path=f.state().join("descriptor.json");
    let descriptor=pocket_runtime_ipc::load_descriptor(&descriptor_path,f.profile.config_home()).unwrap();
    let pending=tokio::spawn(async move {runtime.request(json!("fenced-unknown"),"fixture/deferred",json!({})).await});
    tokio::time::timeout(Duration::from_secs(5),async {
        while !read_json(&f.state().join("recovery.json"))["commands"]["s:fenced-unknown"].is_object() {tokio::time::sleep(Duration::from_millis(10)).await;}
    }).await.unwrap();
    pending.abort();let _=pending.await;
    std::fs::write(f.profile.config_home().join("fixture-stop"),b"stop isolated native").unwrap();ended(&descriptor).await;
    let paths=[f.state().join("startup.json"),descriptor_path,f.state().join("recovery.json")];
    let bytes:Vec<_>=paths.iter().map(|p|std::fs::read(p).unwrap()).collect();
    let trace=f.profile.config_home().join("fixture-methods");let methods=std::fs::read(&trace).unwrap();
    let error=f.controller.attach_existing(&f.profile).await.err().unwrap();
    assert_eq!(error.downcast_ref::<crate::managed_runtime::discovery::RestoreFailure>(),Some(&crate::managed_runtime::discovery::RestoreFailure::ProfileMigrationFenced));
    for (path,original) in paths.iter().zip(bytes) {assert_eq!(std::fs::read(path).unwrap(),original);}
    assert_eq!(std::fs::read(&trace).unwrap(),methods);
    assert_eq!(read_json(&f.state().join("recovery.json"))["commands"]["s:fenced-unknown"]["completed"],false);
}

#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn fenced_attach_existing_missing_registry_never_creates_reservation() {
    let f=Fixture::new();
    assert!(f.controller.attach_existing(&f.profile).await.is_err());
    assert!(!f.state().exists());
    assert!(!f.profile.config_home().join("fixture-methods").exists());
}

#[tokio::test(flavor="multi_thread",worker_threads=4)]
async fn fenced_attach_existing_rejects_ownership_binary_migration_before_package_discovery() {
    let f=Fixture::new();let (old,new,controller)=msix_restore_fixture(&f);
    let runtime=controller.ensure(&old).await.unwrap();
    let descriptor=pocket_runtime_ipc::load_descriptor(&f.state().join("descriptor.json"),old.config_home()).unwrap();
    runtime.shutdown_idle().await.unwrap();ended(&descriptor).await;drop(runtime);
    std::fs::remove_file(old.native_binary()).unwrap();
    let mut registry=f.registry.clone();registry.msix_packages=Some(vec![]);
    let controller=Controller::new(Installation::new(&f.bin,f.generation.clone()).unwrap(),registry);
    let paths=[f.state().join("startup.json"),f.state().join("descriptor.json"),f.state().join("recovery.json")];
    let bytes:Vec<_>=paths.iter().map(|p|std::fs::read(p).unwrap()).collect();
    // Running package discovery would report PackageMissing from this empty
    // inventory. A fenced attach must refuse retargeting before that query.
    let error=controller.attach_existing(&new).await.err().unwrap();
    assert_eq!(error.downcast_ref::<crate::managed_runtime::discovery::RestoreFailure>(),Some(&crate::managed_runtime::discovery::RestoreFailure::ProfileMigrationFenced));
    for (path,original) in paths.iter().zip(bytes) {assert_eq!(std::fs::read(path).unwrap(),original);}
}
