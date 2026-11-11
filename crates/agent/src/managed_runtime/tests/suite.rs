use crate::managed_runtime::{Controller, Installation, Registry, SelectedProfile};
use pocket_runtime_ipc::{Descriptor, HostClient};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    path::{Path, PathBuf},
    sync::OnceLock,
    time::Duration,
};

fn native_fixture() -> &'static Path {
    static BINARY: OnceLock<PathBuf> = OnceLock::new();
    BINARY.get_or_init(|| {
        let target =
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/managed-runtime-fixtures");
        std::fs::create_dir_all(&target).unwrap();
        let binary = target.join("fake-native.exe");
        let output = std::process::Command::new("rustc")
            .arg("--edition=2024")
            .arg(
                PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                    .join("src/managed_runtime/tests/fake_native.rs"),
            )
            .arg("-o")
            .arg(&binary)
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        binary
    })
}
struct Fixture {
    dir: tempfile::TempDir,
    profile: SelectedProfile,
    controller: Controller,
    registry: Registry,
    bin: PathBuf,
    generation: String,
}
impl Drop for Fixture {
    fn drop(&mut self) {
        // Only fixture-private sentinel files: no process signals/kill commands.
        for home in [self.dir.path().join("home"), self.dir.path().join("other")] {
            if home.exists() {
                let _ = std::fs::write(home.join("fixture-stop"), b"stop fake peer");
            }
        }
        if let Ok(entries) = std::fs::read_dir(self.dir.path().join("registry")) {
            for entry in entries.flatten() {
                if let Ok(d) = pocket_runtime_ipc::load_descriptor(
                    &entry.path().join("descriptor.json"),
                    self.profile.config_home(),
                ) {
                    let until = std::time::Instant::now() + Duration::from_secs(2);
                    while !d.host_process.as_ref().is_some_and(|p| p.terminated())
                        && std::time::Instant::now() < until
                    {
                        std::thread::sleep(Duration::from_millis(10));
                    }
                }
            }
        }
    }
}
impl Fixture {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let home = dir.path().join("home");
        std::fs::create_dir(&home).unwrap();
        let bin = dir.path().join("bin");
        std::fs::create_dir(&bin).unwrap();
        let source = std::env::var_os("MANAGED_RUNTIME_TEST_HOST")
            .expect("build isolated runtime host and set MANAGED_RUNTIME_TEST_HOST");
        let bytes = std::fs::read(source).unwrap();
        std::fs::write(bin.join("pocket-runtime-host.exe"), &bytes).unwrap();
        let manifest = serde_json::to_vec(&json!({"schema":1,"product":"Codex Pocket","version":"0.6.3","files":[{"path":"pocket-runtime-host.exe","bytes":bytes.len(),"sha256":format!("{:x}",Sha256::digest(&bytes))}]})).unwrap();
        std::fs::write(bin.join("windows-manifest.json"), &manifest).unwrap();
        let generation = format!("{:x}", Sha256::digest(&manifest));
        let installation = Installation::new(&bin, generation.clone()).unwrap();
        let registry = Registry::new(dir.path().join("registry")).unwrap();
        let profile = SelectedProfile::new(&home, native_fixture()).unwrap();
        let controller = Controller::new(installation, registry.clone());
        Self {
            dir,
            profile,
            controller,
            registry,
            bin,
            generation,
        }
    }
    fn state(&self) -> PathBuf {
        self.registry.profile_directory(&self.profile).unwrap()
    }
}
async fn ended(d: &Descriptor) {
    tokio::time::timeout(Duration::from_secs(5), async {
        while !d.host_process.as_ref().unwrap().terminated()
            || !d.native_process.as_ref().unwrap().terminated()
        {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
}
fn read_json(path: &Path) -> Value {
    serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap()
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
