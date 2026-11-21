//! Explicit opt-in: copies actual Bridge plus a synthetic desktop/native into a
//! fresh private root. Every process touched is spawned by this test.
use super::*;
use sha2::{Digest, Sha256};
use std::{
    fs,
    process::{Child, Command, Stdio},
};
fn hash(p: &Path) -> String {
    format!("{:x}", Sha256::digest(fs::read(p).unwrap()))
}
struct Fixture {
    root: tempfile::TempDir,
    child: Child,
    plan: Plan,
    cleanup: io::Batch,
    journal: Option<Journal>,
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = self.cleanup.stop(Instant::now() + Duration::from_secs(5));
        let _ = self.child.wait();
    }
}
fn wait<T>(mut f: impl FnMut() -> Result<T>) -> T {
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        match f() {
            Ok(v) => return v,
            Err(e) => {
                if Instant::now() > deadline {
                    panic!("fixture timeout: {e}")
                }
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}
fn spawn() -> Fixture {
    spawn_with(false, true)
}
fn spawn_with(startup: bool, initialize: bool) -> Fixture {
    let root = crate::test_support::tempdir();
    let r = root.path();
    fs::write(r.join(".fixture-owner"), b"native-consumer-test").unwrap();
    let bridge = std::env::var_os("POCKET_TEST_BRIDGE").expect("explicit test bridge path");
    let fixture =
        std::env::var_os("POCKET_TEST_NATIVE_FIXTURE").expect("explicit synthetic native path");
    let schema =
        std::env::var_os("POCKET_TEST_NATIVE_SCHEMA").expect("explicit schema fixture path");
    for (source, target) in [
        (bridge.as_os_str(), "bridge.exe"),
        (fixture.as_os_str(), "desktop.exe"),
        (fixture.as_os_str(), "native.exe"),
    ] {
        fs::copy(source, r.join(target)).unwrap();
    }
    for d in ["runtimes", "home", "temp", "appdata", "localappdata"] {
        storage::private_dir(&r.join(d)).unwrap();
    }
    let manifest = serde_json::to_vec(&json!({"files":[{"path":"pocket-bridge.exe",
        "sha256":hash(&r.join("bridge.exe")),"bytes":fs::metadata(r.join("bridge.exe")).unwrap().len()}]})).unwrap();
    let generation = format!("{:x}", Sha256::digest(&manifest));
    let bin = r.join("bin").join(&generation);
    storage::private_dir(&bin).unwrap();
    fs::copy(r.join("bridge.exe"), bin.join("pocket-bridge.exe")).unwrap();
    fs::write(bin.join("windows-manifest.json"), manifest).unwrap();
    fs::write(r.join("pocket-launcher.exe"), b"inert stable identity fixture").unwrap();
    let record = json!({"version":3,"installRoot":r,"stateDirectory":r.join("state"),
        "generation":generation,"releaseHash":generation,"bridge":r.join("pocket-launcher.exe"),
        "launcherHash":hash(&r.join("pocket-launcher.exe"))});
    storage::atomic(&r.join("desktop-install.json"), &record).unwrap();
    let mut c = Command::new(r.join("desktop.exe"));
    c.args(["--desktop", "--fixture-root"])
        .arg(r)
        .arg("--bridge")
        .arg(bin.join("pocket-bridge.exe"))
        .arg("--native")
        .arg(r.join("native.exe"))
        .current_dir(r)
        .env("POCKET_RUNTIME_DIR", r.join("runtimes"))
        .env("POCKET_FIXTURE_ROOT", r)
        .env("POCKET_FIXTURE_SCHEMA", schema)
        .env("CODEX_HOME", r.join("home"))
        .env("HOME", r.join("home"))
        .env("USERPROFILE", r.join("home"))
        .env("APPDATA", r.join("appdata"))
        .env("LOCALAPPDATA", r.join("localappdata"))
        .env("POCKET_INSTALL_ROOT", r)
        .env("TEMP", r.join("temp"))
        .env("TMP", r.join("temp"))
        .env_remove("CODEX_CLI_PATH")
        .env_remove("POCKET_UPDATE_OPERATION")
        .env_remove("POCKET_UPDATE_RELEASE_FILE")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    let journal = if startup {
        let mut j = super::recovery_tests::journal(r);
        j.verified = None;
        j.to_generation = generation.clone();
        j.candidate_record = record;
        save(&mut j, "verifying").unwrap();
        let guard = pocket_core::admission::Installation::open(r).unwrap().update().unwrap();
        let gate = pocket_core::admission::Gate {schema:1,operation_id:j.operation_id.clone(),
            phase:pocket_core::admission::GatePhase::Closing,allowed_generation:None,
            release_file:release_path(&j).unwrap()};
        guard.set_gate(&gate).unwrap();
        guard.set_gate(&pocket_core::admission::Gate {phase:pocket_core::admission::GatePhase::Starting,
            allowed_generation:Some(generation),..gate}).unwrap();
        drop(guard);
        io::startup_environment(&mut c, &j).unwrap();
        Some(j)
    } else {
        None
    };
    if !initialize {
        c.arg("--skip-initialize");
    }
    crate::process::hidden(&mut c);
    let child = c.spawn().unwrap();
    let descriptor = wait(|| {
        fs::read_dir(r.join("runtimes"))?
            .filter_map(|e| e.ok())
            .map(|e| e.path())
            .find(|p| p.extension().is_some_and(|s| s == "json"))
            .context("descriptor pending")
    });
    let d: Value = storage::read(&descriptor).unwrap();
    let image = |pid| {
        let identity = ProcessIdentity::capture(pid).unwrap();
        Image {
            sha256: hash(&identity.executable),
            identity,
        }
    };
    let desktop = image(child.id());
    let bridge = image(d["pid"].as_u64().unwrap() as u32);
    let mut plan = Plan {
        additional: vec![],
        ancestry: vec![],
        desktop,
        bridge,
        native: Image {
            identity: ProcessIdentity {
                pid: 1,
                created_at: 1,
                executable: r.join("native.exe"),
            },
            sha256: hash(&r.join("native.exe")),
        },
        children: vec![],
        descriptor,
        runtime_id: d["runtimeId"].as_str().unwrap().into(),
    };
    let status = wait(|| io::rpc(&plan, "pocket/status", json!({})));
    plan.native = image(status["nativeProcess"]["pid"].as_u64().unwrap() as u32);
    let cleanup = io::Batch::open_fixture(&plan).unwrap();
    let f = Fixture {
        root,
        child,
        plan,
        cleanup,
        journal,
    };
    if initialize {
        wait(|| {
            let s = io::rpc(&f.plan, "pocket/managementStatus", json!({}))?;
            ensure!(
                s["initialized"] == true && s["busy"] == false,
                "initialization pending"
            );
            Ok(())
        });
    }
    f
}
struct Actual<'a> {
    f: &'a Fixture,
    batch: Option<io::Batch>,
    stopped: bool,
    startup: bool,
}
impl Backend for Actual<'_> {
    fn startup_fenced(&self) -> bool {
        self.startup
    }
    fn idle(&mut self) -> Result<bool> {
        let v = io::rpc(&self.f.plan, "pocket/managementStatus", json!({}))?;
        Ok(v["initialized"] == true && v["busy"] == false)
    }
    fn rpc(&mut self, p: &Plan, m: &str, v: Value) -> Result<Value> {
        io::rpc(p, m, v)
    }
    fn prepare_stop(&mut self, p: &Plan) -> Result<()> {
        self.batch = Some(io::Batch::open(p)?);
        Ok(())
    }
    fn persist_stop_intent(&mut self, p: &Plan) -> Result<()> {
        storage::atomic(&self.f.root.path().join("stop-intent.json"), p)
    }
    fn stop(&mut self, _: &Plan, d: Instant) -> Result<()> {
        self.batch.as_ref().unwrap().stop(d)?;
        self.stopped = true;
        Ok(())
    }
}
fn mode(f: &Fixture, value: &str, revision: u32) {
    storage::atomic(
        &f.root.path().join("desktop-command.json"),
        &json!({"mode":value,"revision":revision}),
    )
    .unwrap();
    wait(|| {
        let v = io::rpc(&f.plan, "pocket/managementStatus", json!({}))?;
        let expected = if value == "idle" {
            v["busy"] == false
        } else if value == "waiting" {
            v["pendingQuestions"].as_u64().unwrap_or(0) > 0
        } else {
            v["activeTurns"].as_u64().unwrap_or(0) > 0
        };
        ensure!(expected, "mode pending");
        Ok(())
    });
}
#[test]
#[ignore = "requires explicitly built isolated fixture and current real Bridge paths"]
fn actual_bridge_active_waiting_expiry_identity_and_idle_deferral() {
    let f = spawn();
    let op = uuid::Uuid::new_v4().to_string();
    let ids = wait(|| io::read_ready(&f.plan));
    assert_eq!(ids, vec!["fixture-thread"]);
    assert!(f.root.path().join("native-read-evidence.json").exists());
    for (i, state) in ["active", "waiting"].iter().enumerate() {
        mode(&f, state, i as u32 + 1);
        let mut actual = Actual {
            f: &f,
            batch: None,
            stopped: false,
            startup: false,
        };
        assert!(fenced_stop(&mut actual, &f.plan, &op).is_err());
        assert!(!actual.stopped);
        io::image_ok(&f.plan.native).unwrap();
    }
    mode(&f, "idle", 3);
    let mut wrong = f.plan.clone();
    wrong.native.identity.created_at += 1;
    assert!(io::Batch::open_fixture(&wrong).is_err());
    io::image_ok(&f.plan.native).unwrap();
    io::rpc(
        &f.plan,
        "pocket/maintenance/quiesce",
        json!({"operationId":op}),
    )
    .unwrap();
    assert!(lease(
        &io::rpc(&f.plan, "pocket/managementStatus", json!({})).unwrap(),
        &op
    ));
    std::thread::sleep(Duration::from_secs(31));
    assert!(!lease(
        &io::rpc(&f.plan, "pocket/managementStatus", json!({})).unwrap(),
        &op
    ));
    let mut actual = Actual {
        f: &f,
        batch: None,
        stopped: false,
        startup: false,
    };
    let error = fenced_stop(&mut actual, &f.plan, &op).unwrap_err();
    assert!(error
        .to_string()
        .starts_with("native_admission_journal_missing"), "{error:#}");
    assert!(!actual.stopped);
    assert!(actual.batch.is_none());
    assert!(!f.root.path().join("stop-intent.json").exists());
    for image in [&f.plan.desktop, &f.plan.bridge, &f.plan.native] {
        io::image_ok(image).unwrap();
    }
    assert!(
        io::rpc(&f.plan, "pocket/managementStatus", json!({})).unwrap()["maintenance"].is_null()
    );
    mode(&f, "active", 4);
    mode(&f, "idle", 5);
    println!("actual Bridge: active/waiting and sampled idle all defer; exact identities survive, ordinary lease resumed, subsequent fixture writes accepted; no native stop");
}

#[test]
#[ignore = "requires explicitly built isolated fixture and current real Bridge paths"]
fn actual_consumer_receipt_releases_only_after_verification_and_lease_resume() {
    let mut f = spawn_with(true, true);
    let mut j = f.journal.clone().unwrap();
    let m = io::rpc(&f.plan, "pocket/managementStatus", json!({})).unwrap();
    assert!(startup_idle(&m, &j.operation_id));
    let desktop: Value = storage::read(&f.root.path().join("desktop-env.json")).unwrap();
    assert_eq!(
        desktop,
        json!({"operation":j.operation_id,"release":release_path(&j).unwrap()})
    );
    let native: Value = storage::read(&f.root.path().join("native-env.json")).unwrap();
    assert_eq!(native, json!({"operation":null,"release":null}));
    assert!(publish_release(&j).is_err());
    io::rpc(
        &f.plan,
        "pocket/maintenance/quiesce",
        json!({"operationId":j.operation_id}),
    )
    .unwrap();
    // Ordinary lease expiry must not erase the persistent launch barrier.
    std::thread::sleep(Duration::from_secs(31));
    let m = io::rpc(&f.plan, "pocket/managementStatus", json!({})).unwrap();
    assert!(startup_idle(&m, &j.operation_id));
    assert!(!lease(&m, &j.operation_id));
    assert_eq!(wait(|| io::read_ready(&f.plan)), vec!["fixture-thread"]);
    j.verified = Some(f.plan.clone());
    save(&mut j, "resuming").unwrap();
    io::rpc(
        &f.plan,
        "pocket/maintenance/quiesce",
        json!({"operationId":j.operation_id}),
    )
    .unwrap();
    publish_release(&j).unwrap();
    let m = io::rpc(&f.plan, "pocket/managementStatus", json!({})).unwrap();
    assert_eq!(m["maintenance"]["startupFenced"], false);
    assert!(lease(&m, &j.operation_id));
    io::rpc(
        &f.plan,
        "pocket/maintenance/resume",
        json!({"operationId":j.operation_id}),
    )
    .unwrap();
    {
        let guard = pocket_core::admission::Installation::open(f.root.path()).unwrap().update().unwrap();
        io::admission::finish(&j, &guard).unwrap();
    }
    assert!(
        io::rpc(&f.plan, "pocket/managementStatus", json!({})).unwrap()["maintenance"].is_null()
    );
    mode(&f, "active", 1);
    mode(&f, "idle", 2);
    // The same long-lived desktop still has both environment variables. Its
    // later Bridge child must recognize the durable release without another
    // consumer resume call.
    let old = f.plan.clone();
    storage::atomic(
        &f.root.path().join("desktop-command.json"),
        &json!({"mode":"restart","revision":3}),
    )
    .unwrap();
    let (descriptor, d) = wait(|| {
        for file in fs::read_dir(f.root.path().join("runtimes"))? {
            let path = file?.path();
            if path.extension().is_none_or(|e| e != "json") {
                continue;
            }
            if let Ok(d) = storage::read::<Value>(&path) {
                if d["pid"] != old.bridge.identity.pid {
                    return Ok((path, d));
                }
            }
        }
        anyhow::bail!("new child pending")
    });
    let image = |pid| {
        let identity = ProcessIdentity::capture(pid).unwrap();
        Image {
            sha256: hash(&identity.executable),
            identity,
        }
    };
    let mut next = old.clone();
    next.descriptor = descriptor;
    next.runtime_id = d["runtimeId"].as_str().unwrap().into();
    next.bridge = image(d["pid"].as_u64().unwrap() as u32);
    let s = wait(|| io::rpc(&next, "pocket/status", json!({})));
    next.native = image(s["nativeProcess"]["pid"].as_u64().unwrap() as u32);
    f.cleanup = io::Batch::open_fixture(&next).unwrap();
    f.plan = next;
    wait(|| {
        io::read_ready(&f.plan)?;
        ensure!(
            io::rpc(&f.plan, "pocket/managementStatus", json!({}))?["maintenance"].is_null(),
            "later child still fenced"
        );
        Ok(())
    });
    assert_eq!(f.plan.desktop.identity, old.desktop.identity);
    assert_ne!(f.plan.bridge.identity, old.bridge.identity);
    assert_ne!(f.plan.native.identity, old.native.identity);
    mode(&f, "active", 4);
    mode(&f, "idle", 5);
    println!("actual consumer receipt: inherited env, native env clearing, >30s startup fence, durable publish, lease retained until explicit resume, later Bridge from same desktop inherits released admission; writes passed");
}

#[test]
#[ignore = "requires explicitly built isolated fixture and current real Bridge paths"]
fn actual_uninitialized_startup_candidate_defers_without_durable_update_journal() {
    let f = spawn_with(true, false);
    let j = f.journal.as_ref().unwrap();
    let m = io::rpc(&f.plan, "pocket/managementStatus", json!({})).unwrap();
    assert_eq!(m["initialized"], false);
    assert!(startup_idle(&m, &j.operation_id));
    fs::remove_file(path(&j.state_directory)).unwrap();
    let mut actual = Actual {
        f: &f,
        batch: None,
        stopped: false,
        startup: true,
    };
    assert!(fenced_stop(&mut actual, &f.plan, &uuid::Uuid::new_v4().to_string()).is_err());
    io::image_ok(&f.plan.native).unwrap();
    let error = fenced_stop(&mut actual, &f.plan, &j.operation_id).unwrap_err();
    assert!(error
        .to_string()
        .starts_with("native_admission_journal_missing"), "{error:#}");
    assert!(!actual.stopped);
    assert!(actual.batch.is_none());
    for image in [&f.plan.desktop, &f.plan.bridge, &f.plan.native] {
        io::image_ok(image).unwrap();
    }
    assert!(!release_path(j).unwrap().exists());
    println!("actual uninitialized candidate: matching and mismatched startup operation both preserved identities; missing durable update journal refused before handles; no stop or release receipt");
}
