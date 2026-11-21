//! Real isolated Bridge + synthetic native processes; never launches an installed Desktop.
#![cfg(windows)]
use pocket_core::{
    admission::{
        self, Gate, GatePhase, Installation, Registration, RegistrationPhase, UpdateGuard,
    },
    native_process::NativeProcess,
};
use serde_json::{Value, json};
use std::{
    fs,
    path::{Path, PathBuf},
    process::Stdio,
    sync::OnceLock,
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader, Lines},
    net::{
        TcpStream,
        tcp::{OwnedReadHalf, OwnedWriteHalf},
    },
    process::{Child, ChildStdin, Command},
};

fn native() -> &'static Path {
    static FIXTURE: OnceLock<(tempfile::TempDir, PathBuf)> = OnceLock::new();
    &FIXTURE
        .get_or_init(|| {
            let dir = tempfile::tempdir().unwrap();
            let exe = dir.path().join("fake-admission-native.exe");
            assert!(
                std::process::Command::new("rustc")
                    .arg(
                        Path::new(env!("CARGO_MANIFEST_DIR"))
                            .join("tests/fixtures/fake_admission_native.rs")
                    )
                    .args(["--edition=2024", "-o"])
                    .arg(&exe)
                    .status()
                    .unwrap()
                    .success()
            );
            (dir, exe)
        })
        .1
}
struct Fixture {
    root: tempfile::TempDir,
    bridge: PathBuf,
    generation: String,
    installation: Installation,
}
impl Fixture {
    fn new() -> Self {
        let root = tempfile::tempdir().unwrap();
        let bytes = fs::read(env!("CARGO_BIN_EXE_pocket-bridge")).unwrap();
        let staged = root.path().join("image.exe");
        fs::write(&staged, &bytes).unwrap();
        let manifest = json!({"files":[{"path":"pocket-bridge.exe","bytes":bytes.len(),"sha256":admission::file_sha256(&staged).unwrap()}]});
        let manifest_path = root.path().join("manifest.json");
        fs::write(&manifest_path, manifest.to_string()).unwrap();
        let generation = admission::file_sha256(&manifest_path).unwrap();
        let bin = root.path().join("bin").join(&generation);
        fs::create_dir_all(&bin).unwrap();
        let bridge = bin.join("pocket-bridge.exe");
        fs::rename(staged, &bridge).unwrap();
        fs::rename(manifest_path, bin.join("windows-manifest.json")).unwrap();
        let launcher = root.path().join("pocket-launcher.exe");
        fs::write(&launcher, b"synthetic launcher identity, never executed").unwrap();
        for dir in ["runtimes", "state", "markers"] {
            fs::create_dir(root.path().join(dir)).unwrap();
        }
        fs::write(root.path().join("desktop-install.json"),json!({"version":3,"installRoot":root.path(),"bridge":launcher,"stateDirectory":root.path().join("state"),"launcherHash":admission::file_sha256(&launcher).unwrap()}).to_string()).unwrap();
        let installation = Installation::open(root.path()).unwrap();
        Self {
            root,
            bridge,
            generation,
            installation,
        }
    }
    fn command(&self) -> Command {
        self.command_with_original(native())
    }
    fn command_with_original(&self, original: &Path) -> Command {
        let mut cmd = Command::new(&self.bridge);
        cmd.arg("--original")
            .arg(original)
            .arg("--")
            .env("POCKET_INSTALL_ROOT", self.root.path())
            .env("POCKET_RUNTIME_DIR", self.root.path().join("runtimes"))
            .env("CODEX_HOME", self.root.path().join("state"))
            .env("POCKET_ADMISSION_MARKERS", self.root.path().join("markers"))
            .env_remove("POCKET_UPDATE_OPERATION")
            .env_remove("POCKET_UPDATE_RELEASE_FILE")
            .env_remove("POCKET_ADMISSION_SCHEMA_WAIT")
            .env_remove("POCKET_ADMISSION_SCHEMA_FAIL")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        cmd
    }
    fn gate(&self) -> Gate {
        Gate {
            schema: 1,
            operation_id: uuid::Uuid::new_v4().to_string(),
            phase: GatePhase::Closing,
            allowed_generation: None,
            release_file: self.root.path().join("state/release.json"),
        }
    }
    fn markers(&self, kind: &str) -> Vec<PathBuf> {
        fs::read_dir(self.root.path().join("markers"))
            .unwrap()
            .filter_map(Result::ok)
            .map(|e| e.path())
            .filter(|p| p.file_name().unwrap().to_str().unwrap().starts_with(kind))
            .collect()
    }
    async fn update(&self) -> UpdateGuard {
        tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                if let Ok(g) = self.installation.update() {
                    break g;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("all registered startup/schema shared locks must eventually release")
    }
    async fn rejected(&self, mut cmd: Command, reason: &str) {
        let before = self.markers("").len();
        let out = tokio::time::timeout(Duration::from_secs(5), cmd.output())
            .await
            .unwrap()
            .unwrap();
        assert!(
            !out.status.success(),
            "must reject before any native spawn: {reason}"
        );
        assert!(
            String::from_utf8_lossy(&out.stderr).contains(reason),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        assert_eq!(
            self.markers("").len(),
            before,
            "no native subprocess can precede admission rejection"
        );
    }
}
struct Running {
    child: Child,
    desktop: Option<ChildStdin>,
    descriptor: Value,
    reader: Lines<BufReader<OwnedReadHalf>>,
    writer: OwnedWriteHalf,
}
impl Running {
    async fn start(f: &Fixture, mut cmd: Command) -> Self {
        let mut child = cmd.arg("app-server").spawn().unwrap();
        let pid = child.id().unwrap();
        let descriptor = tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let found = fs::read_dir(f.root.path().join("runtimes"))
                    .unwrap()
                    .filter_map(Result::ok)
                    .filter(|e| e.path().extension().is_some_and(|x| x == "json"))
                    .filter_map(|e| serde_json::from_slice::<Value>(&fs::read(e.path()).ok()?).ok())
                    .find(|v| v["pid"] == pid);
                if let Some(v) = found {
                    break v;
                }
                if let Some(status) = child.try_wait().unwrap() {
                    panic!("bridge exited before descriptor: {status}");
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        let stream = TcpStream::connect(("127.0.0.1", descriptor["port"].as_u64().unwrap() as u16))
            .await
            .unwrap();
        let (reader, mut writer) = stream.into_split();
        writer
            .write_all(format!("{}\n", json!({"token":descriptor["token"]})).as_bytes())
            .await
            .unwrap();
        let desktop = child.stdin.take();
        Self {
            child,
            desktop,
            descriptor,
            reader: BufReader::new(reader).lines(),
            writer,
        }
    }
    async fn rpc(&mut self, method: &str, params: Value) -> Value {
        let id = uuid::Uuid::new_v4().to_string();
        self.writer
            .write_all(format!("{}\n", json!({"id":id,"method":method,"params":params})).as_bytes())
            .await
            .unwrap();
        tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let value: Value = serde_json::from_str(
                    &self
                        .reader
                        .next_line()
                        .await
                        .unwrap()
                        .expect("bridge RPC EOF"),
                )
                .unwrap();
                if value["id"] == id {
                    break value;
                }
            }
        })
        .await
        .unwrap()
    }
    async fn schema_state(&mut self, expected: &str) {
        tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                if self.rpc("pocket/status", json!({})).await["result"]["schemaState"] == expected {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
    }
    async fn finish(mut self) {
        drop(self.desktop.take());
        assert!(
            tokio::time::timeout(Duration::from_secs(5), self.child.wait())
                .await
                .unwrap()
                .unwrap()
                .success()
        );
    }
}

async fn assert_failed_spawn_cancels_starting(argument: &str) {
    let f = Fixture::new();
    let invalid = f.root.path().join("invalid-native.exe");
    fs::write(&invalid, b"Not a Windows executable; no child can be created").unwrap();
    let mut cmd = f.command_with_original(&invalid);
    cmd.arg(argument);
    let out = tokio::time::timeout(Duration::from_secs(5), cmd.output())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(out.status.code(), Some(1));
    let error = String::from_utf8_lossy(&out.stderr);
    assert!(
        error.contains("os error 193") || error.contains("os error 216"),
        "Windows must reject image creation, not fail after spawning: {error}"
    );
    assert!(f.markers("").is_empty());
    assert!(
        f.update().await.registrations().unwrap().is_empty(),
        "a definite failed spawn must cancel only its own Starting registration"
    );
    let mut later = Running::start(&f, f.command()).await;
    later.schema_state("ready").await;
    assert_eq!(f.update().await.registrations().unwrap().len(), 1);
    later.finish().await;
    assert!(f.update().await.registrations().unwrap().is_empty());
}

#[tokio::test]
async fn app_server_failed_spawn_cancels_starting_registration() {
    assert_failed_spawn_cancels_starting("app-server").await;
}

#[tokio::test]
async fn passthrough_failed_spawn_cancels_starting_registration() {
    assert_failed_spawn_cancels_starting("--version").await;
}

#[tokio::test]
async fn installed_bridges_register_before_native_and_closing_blocks_later_old_desktop() {
    let f = Fixture::new();
    let mut a = Running::start(&f, f.command()).await;
    let mut b = Running::start(&f, f.command()).await;
    a.schema_state("ready").await;
    b.schema_state("ready").await;
    let update = f.update().await;
    let records = update.registrations().unwrap();
    assert_eq!(records.len(), 2);
    for running in [&mut a, &mut b] {
        let status = running.rpc("pocket/status", json!({})).await;
        let management = running.rpc("pocket/managementStatus", json!({})).await;
        let record: Registration =
            serde_json::from_value(running.descriptor["startupAdmission"].clone()).unwrap();
        assert_eq!(status["result"]["startupAdmissionProtocol"], 1);
        assert_eq!(
            management["result"]["startupAdmission"],
            running.descriptor["startupAdmission"]
        );
        assert_eq!(
            status["result"]["nativeProcess"],
            serde_json::to_value(&record.native).unwrap()
        );
        assert_eq!(
            record.bridge,
            NativeProcess::capture(running.child.id().unwrap()).unwrap()
        );
        assert!(records.contains(&record));
        let marker = f
            .root
            .path()
            .join("markers")
            .join(format!("native-{}", record.native.unwrap().pid));
        assert!(
            fs::read_to_string(marker)
                .unwrap()
                .contains(&record.runtime_id),
            "registration predates the first native instruction"
        );
    }
    let mut cmd = f.command();
    cmd.arg("app-server");
    f.rejected(cmd, "admission_startup_in_progress").await;
    update.set_gate(&f.gate()).unwrap();
    drop(update);
    let mut cmd = f.command();
    cmd.arg("app-server");
    f.rejected(cmd, "admission_closed").await;
    a.finish().await;
    b.finish().await;
    assert!(f.update().await.registrations().unwrap().is_empty());
}

#[tokio::test]
async fn candidate_requires_exact_context_and_releases_only_after_durable_receipt() {
    let f = Fixture::new();
    let mut gate = f.gate();
    {
        let update = f.update().await;
        update.set_gate(&gate).unwrap();
        gate.phase = GatePhase::Starting;
        gate.allowed_generation = Some(f.generation.clone());
        update.set_gate(&gate).unwrap();
    }
    let mut cmd = f.command();
    cmd.arg("app-server");
    f.rejected(cmd, "admission_candidate_unapproved").await;
    let mut cmd = f.command();
    cmd.arg("app-server")
        .env("POCKET_UPDATE_OPERATION", uuid::Uuid::new_v4().to_string())
        .env("POCKET_UPDATE_RELEASE_FILE", &gate.release_file);
    f.rejected(cmd, "admission_candidate_unapproved").await;
    let mut cmd = f.command();
    cmd.arg("app-server")
        .env("POCKET_UPDATE_OPERATION", &gate.operation_id)
        .env(
            "POCKET_UPDATE_RELEASE_FILE",
            f.root.path().join("state/wrong.json"),
        );
    f.rejected(cmd, "admission_candidate_unapproved").await;
    let mut cmd = f.command();
    cmd.env("POCKET_UPDATE_OPERATION", &gate.operation_id)
        .env("POCKET_UPDATE_RELEASE_FILE", &gate.release_file);
    let mut running = Running::start(&f, cmd).await;
    running.schema_state("ready").await;
    assert_eq!(
        running.rpc("pocket/status", json!({})).await["result"]["maintenance"]["startupFenced"],
        true
    );
    assert!(
        running
            .rpc("turn/start", json!({}))
            .await
            .get("error")
            .is_some()
    );
    running.desktop.as_mut().unwrap().write_all(b"{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"codex_desktop\"}}}\n").await.unwrap();
    let mut desktop_output = BufReader::new(running.child.stdout.take().unwrap()).lines();
    let reply = tokio::time::timeout(Duration::from_secs(5), desktop_output.next_line())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(serde_json::from_str::<Value>(&reply).unwrap()["id"], 1);
    running
        .desktop
        .as_mut()
        .unwrap()
        .write_all(b"{\"method\":\"initialized\"}\n")
        .await
        .unwrap();
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if running.rpc("pocket/status", json!({})).await["result"]["initialized"] == true {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    assert!(
        running
            .rpc(
                "pocket/maintenance/resume",
                json!({"operationId":gate.operation_id})
            )
            .await
            .get("error")
            .is_some()
    );
    // Startup fencing is required before initialize too; receipt is the irreversible boundary.
    {
        let update = f.update().await;
        fs::write(
            &gate.release_file,
            json!({"schema":1,"operationId":gate.operation_id,"released":true}).to_string(),
        )
        .unwrap();
        let mut closing = gate.clone();
        closing.phase = GatePhase::Closing;
        closing.allowed_generation = None;
        assert!(update.set_gate(&closing).is_err());
        gate.phase = GatePhase::Released;
        update.set_gate(&gate).unwrap();
    }
    assert!(running.rpc("pocket/status", json!({})).await["result"]["maintenance"].is_null());
    let mut later = Running::start(&f, f.command()).await;
    later.schema_state("ready").await;
    assert!(later.rpc("pocket/status", json!({})).await["result"]["maintenance"].is_null());
    running.finish().await;
    later.finish().await;
}

#[tokio::test]
async fn installed_candidate_cannot_bypass_router_fence_via_native_passthrough() {
    let f = Fixture::new();
    let mut gate = f.gate();
    {
        let update = f.update().await;
        update.set_gate(&gate).unwrap();
        gate.phase = GatePhase::Starting;
        gate.allowed_generation = Some(f.generation.clone());
        update.set_gate(&gate).unwrap();
    }
    let mut cmd = f.command();
    cmd.arg("exec")
        .env("POCKET_UPDATE_OPERATION", gate.operation_id)
        .env("POCKET_UPDATE_RELEASE_FILE", gate.release_file);
    f.rejected(cmd, "admission_passthrough_fenced").await;
}

#[tokio::test]
async fn schema_export_holds_shared_until_reaped_and_normal_exit_preserves_registration() {
    let f = Fixture::new();
    let release = f.root.path().join("schema-release");
    let mut cmd = f.command();
    cmd.env("POCKET_ADMISSION_SCHEMA_WAIT", &release);
    let mut running = Running::start(&f, cmd).await;
    tokio::time::timeout(Duration::from_secs(5), async {
        while f.markers("schema").is_empty() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    let locked = f.installation.update().is_err();
    drop(running.desktop.take());
    tokio::time::sleep(Duration::from_millis(100)).await;
    let kept_alive = running.child.try_wait().unwrap().is_none();
    fs::write(&release, b"release").unwrap();
    running.finish().await;
    assert!(
        locked,
        "schema spawn/exit must participate in installation admission"
    );
    assert!(
        kept_alive,
        "Bridge must reap schema before unregistering the last native evidence"
    );
    assert!(f.update().await.registrations().unwrap().is_empty());
}

#[tokio::test]
async fn closing_prevents_schema_retry_from_creating_new_native_process() {
    let f = Fixture::new();
    let mut cmd = f.command();
    cmd.env("POCKET_ADMISSION_SCHEMA_FAIL", "1");
    let mut running = Running::start(&f, cmd).await;
    running.schema_state("error").await;
    assert_eq!(f.markers("schema").len(), 1);
    {
        let update = f.update().await;
        update.set_gate(&f.gate()).unwrap();
    }
    tokio::time::sleep(Duration::from_millis(5100)).await;
    running.rpc("pocket/status", json!({})).await;
    running.schema_state("error").await;
    let spawned = f.markers("schema").len();
    running.finish().await;
    assert_eq!(
        spawned, 1,
        "readiness retry must obey the closed gate before native spawn"
    );
}

#[tokio::test]
async fn installed_binding_or_gate_failure_never_downgrades_to_unmanaged() {
    let f = Fixture::new();
    let mut cmd = f.command();
    cmd.arg("app-server")
        .env("POCKET_INSTALL_ROOT", f.root.path().join("wrong"));
    f.rejected(cmd, "admission_explicit_root_mismatch").await;
    let gate = f.gate();
    let mut cmd = f.command();
    cmd.arg("app-server")
        .env("POCKET_UPDATE_OPERATION", gate.operation_id)
        .env("POCKET_UPDATE_RELEASE_FILE", gate.release_file);
    f.rejected(cmd, "admission_update_gate_missing").await;
    fs::write(f.root.path().join("control/native-admission.json"), b"{").unwrap();
    let mut cmd = f.command();
    cmd.arg("app-server");
    f.rejected(cmd, "EOF").await;
    fs::write(f.root.path().join("pocket-launcher.exe"), b"changed").unwrap();
    let mut cmd = f.command();
    cmd.arg("app-server");
    f.rejected(cmd, "admission_launcher_changed").await;
}

// A synthetic process exercises the exact core pre-spawn guard without timing a tiny window.
#[test]
fn admission_lock_child() {
    let Some(root) = std::env::var_os("POCKET_ADMISSION_CHILD_ROOT") else {
        return;
    };
    let root = PathBuf::from(root);
    let installation = Installation::open(&root).unwrap();
    let generation = "a".repeat(64);
    let guard = installation.startup(&generation, None).unwrap();
    let id = uuid::Uuid::new_v4().to_string();
    guard
        .register(&Registration {
            schema: 1,
            runtime_id: id.clone(),
            install_root: root.clone(),
            generation: generation.clone(),
            bridge: NativeProcess::capture(std::process::id()).unwrap(),
            bridge_executable: root.join("bin").join(generation).join("pocket-bridge.exe"),
            descriptor: root.join("runtimes").join(format!("{id}.json")),
            operation_id: None,
            release_file: None,
            phase: RegistrationPhase::Starting,
            native: None,
        })
        .unwrap();
    fs::write(root.join("child-ready"), b"locked and durable").unwrap();
    let mut line = String::new();
    std::io::stdin().read_line(&mut line).unwrap();
    drop(guard);
}

#[tokio::test]
async fn pre_spawn_lock_is_cross_process_and_crash_leaves_uncertainty_and_durable_gate() {
    let f = Fixture::new();
    let mut child = Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "admission_lock_child", "--nocapture"])
        .env("POCKET_ADMISSION_CHILD_ROOT", f.root.path())
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    tokio::time::timeout(Duration::from_secs(5), async {
        while !f.root.path().join("child-ready").exists() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    assert!(
        f.installation.update().is_err(),
        "exclusive must conflict across real process handles"
    );
    child.kill().await.unwrap();
    child.wait().await.unwrap();
    {
        let update = f.update().await;
        let records = update.registrations().unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].phase, RegistrationPhase::Starting);
        assert!(records[0].bridge.terminated());
        update.set_gate(&f.gate()).unwrap();
    }
    let mut cmd = f.command();
    cmd.arg("app-server");
    f.rejected(cmd, "admission_closed").await;
    assert_eq!(
        Installation::open(f.root.path())
            .unwrap()
            .update()
            .unwrap()
            .registrations()
            .unwrap()
            .len(),
        1
    );
}
