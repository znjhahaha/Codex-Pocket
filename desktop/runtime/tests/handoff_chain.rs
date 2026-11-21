//! Actual copied supervisor/coordinator/agent binaries, empty native inventory,
//! private temporary state, no network route or model. Requires a freshly built
//! agent through POCKET_HANDOFF_CHAIN_AGENT; absence is an explicit failure.
#![cfg(windows)]
use anyhow::{bail, ensure, Context, Result};
use pocket_desktop_runtime::{
    storage,
    supervisor::{maintenance, upgrade},
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    thread,
    time::{Duration, Instant},
};
#[path = "../test_support/mod.rs"]
mod test_support;

fn digest(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
fn process_gone(expected: &maintenance::ProcessIdentity) -> Result<bool> {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, WAIT_OBJECT_0, WAIT_TIMEOUT},
        System::Threading::{OpenProcess, WaitForSingleObject, PROCESS_SYNCHRONIZE},
    };
    unsafe {
        let handle = OpenProcess(PROCESS_SYNCHRONIZE, 0, expected.pid);
        if handle.is_null() {
            let e = std::io::Error::last_os_error();
            ensure!(e.raw_os_error() == Some(87), "process liveness unavailable");
            return Ok(true);
        }
        let result = WaitForSingleObject(handle, 0);
        CloseHandle(handle);
        ensure!(
            matches!(result, WAIT_OBJECT_0 | WAIT_TIMEOUT),
            "process wait unavailable"
        );
        Ok(result == WAIT_OBJECT_0)
    }
}
fn rpc(state: &Path, method: &str, params: Value) -> Result<Value> {
    let endpoint: Value = storage::read(&state.join("control.json"))?;
    let port = endpoint["port"].as_u64().context("port")? as u16;
    let mut stream = std::net::TcpStream::connect_timeout(
        &([127, 0, 0, 1], port).into(),
        Duration::from_secs(2),
    )?;
    stream.set_read_timeout(Some(Duration::from_secs(10)))?;
    stream.set_write_timeout(Some(Duration::from_secs(2)))?;
    let request =
        serde_json::to_vec(&json!({"token":endpoint["token"],"method":method,"params":params}))?;
    stream.write_all(&(request.len() as u32).to_be_bytes())?;
    stream.write_all(&request)?;
    let mut n = [0; 4];
    stream.read_exact(&mut n)?;
    let size = u32::from_be_bytes(n) as usize;
    ensure!(size <= 1024 * 1024, "response limit");
    let mut bytes = vec![0; size];
    stream.read_exact(&mut bytes)?;
    let response: Value = serde_json::from_slice(&bytes)?;
    // Never print endpoint, request or server text.
    ensure!(
        response.get("error").is_none(),
        "fixture control rejected {method}"
    );
    response.get("result").cloned().context("result")
}
fn wait_for<T>(mut probe: impl FnMut() -> Option<T>) -> T {
    let end = Instant::now() + Duration::from_secs(60);
    loop {
        if let Some(value) = probe() {
            return value;
        }
        assert!(Instant::now() < end, "fixture deadline");
        thread::sleep(Duration::from_millis(100));
    }
}
struct Fixture {
    root: tempfile::TempDir,
    state: PathBuf,
    old: String,
    new: String,
    source: Child,
}
impl Fixture {
    fn command(root: &Path, exe: &Path) -> Command {
        let mut c = Command::new(exe);
        c.current_dir(root).env_clear();
        for key in [
            "SystemRoot",
            "WINDIR",
            "PATH",
            "PATHEXT",
            "COMSPEC",
            "SYSTEMDRIVE",
        ] {
            if let Some(value) = std::env::var_os(key) {
                c.env(key, value);
            }
        }
        for (key, name) in [
            ("HOME", "home"),
            ("USERPROFILE", "home"),
            ("APPDATA", "home/AppData/Roaming"),
            ("LOCALAPPDATA", "home/AppData/Local"),
            ("CODEX_HOME", "codex-home"),
            ("TEMP", "tmp"),
            ("TMP", "tmp"),
        ] {
            c.env(key, root.join(name));
        }
        c.stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x08000000);
        c
    }
    fn generation(root: &Path, label: &str, agent: &Path) -> String {
        let staged = root.join(label);
        fs::create_dir(&staged).unwrap();
        let trace = std::env::var_os("POCKET_HANDOFF_TRACE_COORDINATOR").map(PathBuf::from);
        for (name, source) in [
            ("pocket-agent.exe", agent),
            (
                "pocket-supervisor.exe",
                Path::new(env!("CARGO_BIN_EXE_pocket-supervisor")),
            ),
            (
                "pocket-upgrade.exe",
                trace.as_deref().unwrap_or(Path::new(env!("CARGO_BIN_EXE_pocket-upgrade"))),
            ),
        ] {
            fs::copy(source, staged.join(name)).unwrap();
        }
        // Unused inventory files are explicitly inert fixtures. Their bytes
        // are hashed; none is ever loaded, executed or claimed as native proof.
        for name in [
            "pocket-bridge.exe",
            "pocket-runtime-host.exe",
            "pocket-p2p.dll",
            "datachannel.dll",
            "P2P-THIRD-PARTY.txt",
        ] {
            fs::write(staged.join(name), b"inert handoff fixture").unwrap();
        }
        let mut files = Vec::new();
        for name in [
            "pocket-agent.exe",
            "pocket-supervisor.exe",
            "pocket-upgrade.exe",
            "pocket-bridge.exe",
            "pocket-runtime-host.exe",
            "pocket-p2p.dll",
            "datachannel.dll",
            "P2P-THIRD-PARTY.txt",
        ] {
            let bytes = fs::read(staged.join(name)).unwrap();
            files.push(json!({"path":name,"bytes":bytes.len(),"sha256":digest(&bytes)}));
        }
        let manifest =
            serde_json::to_vec(&json!({"version":"fixture","label":label,"files":files})).unwrap();
        let generation = digest(&manifest);
        fs::write(staged.join("windows-manifest.json"), manifest).unwrap();
        fs::create_dir_all(root.join("bin")).unwrap();
        fs::rename(staged, root.join("bin").join(&generation)).unwrap();
        generation
    }
    fn new() -> Self {
        let agent = PathBuf::from(
            std::env::var_os("POCKET_HANDOFF_CHAIN_AGENT")
                .expect("set POCKET_HANDOFF_CHAIN_AGENT to the freshly built agent"),
        );
        let root = test_support::tempdir();
        storage::private_dir(root.path()).unwrap();
        fs::write(root.path().join(".fixture-owner"), b"handoff-chain-test").unwrap();
        for name in ["home/AppData/Local", "home/AppData/Roaming", "codex-home", "tmp"] {
            storage::private_dir(&root.path().join(name)).unwrap();
        }
        let old = Self::generation(root.path(), "old", &agent);
        let new = Self::generation(root.path(), "new", &agent);
        // Validate the harness job before creating any background process.
        let mut independent = Self::command(
            root.path(),
            &root
                .path()
                .join("bin")
                .join(&new)
                .join("pocket-upgrade.exe"),
        );
        use std::os::windows::process::CommandExt;
        independent.creation_flags(0x08000000 | 0x01000000);
        let mut probe = independent.spawn().expect(
            "fixture harness must permit CREATE_BREAKAWAY_FROM_JOB before starting a background",
        );
        probe.wait().unwrap();
        let state = root.path().join("中文 state");
        storage::private_dir(&state).unwrap();
        let old_bin = root.path().join("bin").join(&old);
        assert!(
            Self::command(root.path(), &old_bin.join("pocket-agent.exe"))
                .args(["init", "--state"])
                .arg(&state)
                .status()
                .unwrap()
                .success()
        );
        let mut config: Value = storage::read(&state.join("config.json")).unwrap();
        config["runtimeDirectories"] = json!([]);
        config["codexHomes"] = json!([]);
        config["relayUrl"] = json!("");
        config["routePreferences"] =
            json!({"policy":"automatic","autoLan":false,"lan":null,"gateway":null,"routes":[]});
        storage::atomic(&state.join("config.json"), &config).unwrap();
        storage::set_desired(&state, false).unwrap();
        storage::private_dir(&root.path().join("control")).unwrap();
        storage::private_dir(&storage::control(&state).join("connectivity")).unwrap();
        let mut cmd = Self::command(root.path(), &old_bin.join("pocket-supervisor.exe"));
        cmd.arg("--state")
            .arg(&state)
            .arg("--agent")
            .arg(old_bin.join("pocket-agent.exe"))
            .arg("--owner-directory")
            .arg(root.path().join("control"));
        cmd.stderr(Stdio::from(
            fs::File::create(root.path().join("source-supervisor.stderr.log")).unwrap(),
        ));
        let source = cmd.spawn().unwrap();
        let f = Self {
            root,
            state,
            old,
            new,
            source,
        };
        f.healthy(&f.old);
        storage::atomic(&f.root.path().join("desktop-install.json"),&json!({"version":3,"installRoot":f.root.path(),"bridge":f.root.path().join("pocket-launcher.exe"),"generation":f.new,"releaseHash":f.new,"stateDirectory":f.state})).unwrap();
        f
    }
    fn healthy(&self, generation: &str) -> Value {
        wait_for(|| {
            if generation == self.old
                && !self.state.join("handoff/state.json").exists()
                && process_gone(&maintenance::ProcessIdentity {
                    pid: self.source.id(),
                    created_at: 1,
                    executable: self.root.path().join("missing"),
                })
                .unwrap()
            {
                let startup = storage::read::<Value>(&self.state.join("startup-failure.json")).ok();
                let heartbeat =
                    storage::read::<Value>(&storage::control(&self.state).join("supervisor.json"))
                        .ok();
                panic!(
                    "fixture source exited; startup phase {:?} code {:?}; supervisor phase {:?}",
                    startup.as_ref().map(|s| &s["phase"]),
                    startup.as_ref().map(|s| &s["code"]),
                    heartbeat.as_ref().map(|s| &s["phase"])
                );
            }
            let status = rpc(&self.state, "status", json!({})).ok()?;
            let heartbeat: Value =
                storage::read(&storage::control(&self.state).join("supervisor.json")).ok()?;
            (status["acceptingWrites"] == true
                && status["helperRuntime"]["generation"] == generation
                && heartbeat["runtime"]["generation"] == generation)
                .then_some(status)
        })
    }
    fn run(&self, id: &str) -> upgrade::UpgradeStatus {
        let mut cmd = Self::command(
            self.root.path(),
            &self
                .root
                .path()
                .join("bin")
                .join(&self.new)
                .join("pocket-upgrade.exe"),
        );
        if std::env::var_os("POCKET_HANDOFF_TRACE_COORDINATOR").is_some() {
            cmd.args(["supervisor::upgrade::local::tests::release_trace::actual_release_trace_coordinator", "--exact", "--ignored", "--nocapture", "--test-threads=1"])
                .env("POCKET_TRACE_FIXTURE", self.root.path())
                .env("POCKET_TRACE_OPERATION", id)
                .stdout(Stdio::from(fs::File::create(self.root.path().join(format!("coordinator-{id}.stdout.log"))).unwrap()));
        } else {
            cmd.arg("--install-root")
                .arg(self.root.path())
                .arg("--state")
                .arg(&self.state)
                .arg("--operation-id")
                .arg(id);
        }
        cmd.stderr(Stdio::from(
            fs::File::create(
                self.root
                    .path()
                    .join(format!("coordinator-{id}.stderr.log")),
            )
            .unwrap(),
        ));
        let mut c = cmd.spawn().unwrap();
        // A coordinator includes a 45s successor-adoption wait plus separate
        // quiesce/release/verify waits. The harness deadline must cover them.
        let started = Instant::now();
        let mut last_phase = None;
        let exit = loop {
            if let Some(exit) = c.try_wait().unwrap() {
                break exit;
            }
            if let Ok(Some(status)) = upgrade::read_status(&self.state) {
                if last_phase != Some(status.phase) {
                    eprintln!("coordinator {id}: {:?} at {:.1}s", status.phase, started.elapsed().as_secs_f32());
                    last_phase = Some(status.phase);
                }
            }
            if started.elapsed() >= Duration::from_secs(180) {
                let health = rpc(&self.state, "helper/health", json!({})).ok().map(|v| json!({
                    "acceptingWrites":v["acceptingWrites"],"remoteEnabled":v["remoteEnabled"],"helperRuntime":v["helperRuntime"]}));
                storage::atomic(&self.root.path().join("coordinator-timeout.json"), &json!({
                    "status":upgrade::read_status(&self.state).ok().flatten(),"health":health})).unwrap();
                panic!("coordinator exceeded all bounded protocol waits; isolated diagnostics retained");
            }
            thread::sleep(Duration::from_millis(100));
        };
        let result = upgrade::read_status(&self.state).unwrap().unwrap();
        assert!(
            matches!(
                result.phase,
                upgrade::Phase::Succeeded | upgrade::Phase::RolledBack
            ),
            "coordinator {exit}; phase {:?}; failure {:?}",
            result.phase,
            result.failure
        );
        result
    }
    fn shutdown(&self) -> Result<()> {
        shutdown_fixture(self.root.path(), &self.state, &self.new)
    }
    fn cleanup(&self) -> Result<()> {
        // Coordinators deliberately break away from the harness job. On a
        // failed assertion, clean only copied images beneath this marked root.
        // The installer API rechecks SID, creation time and image on its handle.
        let script = r#"
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($env:POCKET_CHAIN_FIXTURE)
if([IO.File]::ReadAllText((Join-Path $root '.fixture-owner')) -ne 'handoff-chain-test'){throw 'fixture marker missing'}
. (Join-Path $env:POCKET_CHAIN_SCRIPTS 'windows-install.ps1')
. (Join-Path $env:POCKET_CHAIN_SCRIPTS 'windows-upgrade-fixture-processes.ps1')
Initialize-PocketFixtureProcessSnapshot
for($attempt=0;$attempt -lt 10;$attempt++){
 $rows=@([PocketFixtureSnapshot]::Read($root))
 if($rows.Count -eq 0){[IO.File]::WriteAllText((Join-Path $root 'fixture-finished.txt'),'No executable beneath this private fixture root remains.');exit 0}
 foreach($row in @($rows|Sort-Object @{Expression={if($_.Name -eq 'pocket-supervisor.exe'){0}elseif($_.Name -eq 'pocket-upgrade.exe'){1}else{2}}})){
  $identity=Get-PocketInstallProcessIdentity $row.ProcessId
  if($null -eq $identity){continue}
  if(-not $identity.executable.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'fixture identity escaped root'}
  $captured=@{pid=$identity.pid;createdAt=$identity.createdAt;executable=$identity.executable;sha256=(Get-PocketFileHash $identity.executable)}
  Stop-PocketInstallProcess $captured
 }
 Start-Sleep -Milliseconds 100
}
throw 'fixture cleanup incomplete'
"#;
        let mut c = Self::command(self.root.path(), &storage::powershell());
        let output = c
            .env("POCKET_CHAIN_FIXTURE", self.root.path())
            .env(
                "POCKET_CHAIN_SCRIPTS",
                Path::new(env!("CARGO_MANIFEST_DIR")).join("../../scripts"),
            )
            .args([
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                script,
            ])
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output()?;
        fs::write(self.root.path().join("cleanup.log"), &output.stderr)?;
        ensure!(output.status.success(), "fixture cleanup failed");
        Ok(())
    }
}
fn shutdown_fixture(root: &Path, state: &Path, target: &str) -> Result<()> {
    let health = rpc(state, "status", json!({}))?;
    let heartbeat: Value = storage::read(&storage::control(state).join("supervisor.json"))?;
    let owner =
        maintenance::ProcessIdentity::capture(heartbeat["pid"].as_u64().context("pid")? as u32)?;
    let child = maintenance::ProcessIdentity::capture(
        health["helperRuntime"]["pid"].as_u64().context("child")? as u32,
    )?;
    let generation = health["helperRuntime"]["generation"]
        .as_str()
        .context("generation")?;
    if generation == target {
        bail!("cleanup requires old fixture generation");
    }
    // A separate durable operation gracefully exits only these copied files.
    let coordinator = upgrade::Coordinator::request(root, state, generation)?;
    let mut status = coordinator.status().clone();
    drop(coordinator);
    status.phase = upgrade::Phase::Quiescing;
    storage::atomic(&storage::control(state).join("upgrade.json"), &status)?;
    let mut r = maintenance::Request {
        protocol: 1,
        operation_id: status.operation_id.clone(),
        from_generation: generation.into(),
        supervisor: owner.clone(),
        coordinator: maintenance::ProcessIdentity::capture(std::process::id())?,
        child: Some(child.clone()),
        action: maintenance::Action::Enter,
    };
    maintenance::submit(state, &r)?;
    wait_for(|| {
        maintenance::read_status(state)
            .ok()
            .flatten()
            .filter(|j| j.phase == maintenance::Phase::Owned && j.request == r)
    });
    let p = json!({"id":status.operation_id,"fromGeneration":health["handoffCapabilities"]["generation"],"checkpoint":null});
    let ready = rpc(state, "upgrade/quiesce", p.clone())?;
    let mut release = p;
    release["checkpoint"] = ready["checkpoint"].clone();
    rpc(state, "upgrade/release", release)?;
    wait_for(|| process_gone(&child).unwrap().then_some(()));
    // Cleanup is intentionally terminal: no product release proof is faked.
    // The idle supervisor needs its own real release context; let the actual
    // coordinator recover/start old, then use the recorded context below.
    let final_ref: Value = storage::read(&state.join("handoff/state.json"))?;
    let cp = final_ref["recoveryCheckpoint"].clone();
    let context = json!({"protocol":1,"operationId":status.operation_id,"fromGeneration":generation,"toGeneration":target,
            "source":{"supervisor":owner,"agent":child,"generation":generation,"run":cp["generation"]},
            "current":{"supervisor":owner,"agent":child,"generation":generation,"run":cp["generation"]},
            "coordinator":r.coordinator,"priorRemote":false,"routes":{"routePreferences":ready["routePreferences"]},"computerId":health["computerId"],"ready":ready["checkpoint"],"finalCheckpoint":cp,
            "activeId":status.operation_id,"verifyId":status.operation_id,"rollbackId":status.operation_id,"stage":"released","spawnPid":null,"spawnIdentity":null});
    storage::atomic(
        &storage::control(state).join("upgrade-local.json"),
        &context,
    )?;
    status.checkpoint = Some(upgrade::Checkpoint {
        operation_id: status.operation_id.clone(),
        generation: generation.into(),
        saved_sha256: cp["hash"].as_str().context("hash")?.into(),
        reread_sha256: cp["hash"].as_str().unwrap().into(),
        pending_writes: 0,
        writes_fenced: true,
        native_tasks_preserved: true,
    });
    status.phase = upgrade::Phase::Releasing;
    storage::atomic(&storage::control(state).join("upgrade.json"), &status)?;
    r.action = maintenance::Action::Release;
    maintenance::submit(state, &r)?;
    wait_for(|| process_gone(&owner).unwrap().then_some(()));
    Ok(())
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let cleanup = self.cleanup();
        if thread::panicking() || cleanup.is_err() {
            eprintln!(
                "Fixture diagnostics directory: {}; cleanup: {:?}",
                self.root.path().display(),
                cleanup
            );
            self.root.disable_cleanup(true);
        }
        if !thread::panicking() {
            assert!(
                cleanup.is_ok(),
                "fixture cleanup must complete: {cleanup:?}"
            );
        }
    }
}
#[test]
#[ignore = "requires a freshly built agent; run with POCKET_HANDOFF_CHAIN_AGENT and --ignored"]
fn diagnose_actual_release_handoff() {
    assert!(std::env::var_os("POCKET_HANDOFF_TRACE_COORDINATOR").is_some());
    let mut f = Fixture::new();
    f.root.disable_cleanup(true);
    let coordinator = upgrade::Coordinator::request(f.root.path(), &f.state, &f.old).unwrap();
    let id = coordinator.status().operation_id.clone();
    drop(coordinator);
    let result = f.run(&id);
    storage::atomic(&f.root.path().join("trace-result.json"), &json!({"operationId":id,"phase":result.phase,"failure":result.failure})).unwrap();
    eprintln!("Release diagnostic fixture: {}; phase {:?}; failure {:?}", f.root.path().display(), result.phase, result.failure);
    assert!(f.root.path().join("release-trace.jsonl").is_file(), "release stage was not reached");
}

#[test]
#[ignore = "requires a freshly built agent; run with POCKET_HANDOFF_CHAIN_AGENT and --ignored"]
fn actual_supervisor_coordinator_agent_handoff_and_recovery() {
    let mut f = Fixture::new();
    let original = fs::read(f.state.join("config.json")).unwrap();
    let c = upgrade::Coordinator::request(f.root.path(), &f.state, &f.old).unwrap();
    let id = c.status().operation_id.clone();
    drop(c);
    let result = f.run(&id);
    assert_eq!(result.phase, upgrade::Phase::Succeeded);
    let status = f.healthy(&f.new);
    assert_eq!(status["remoteEnabled"], false);
    assert_eq!(status["runtimes"], json!([]));
    assert!(wait_for(|| f.source.try_wait().unwrap()).success());
    assert_eq!(fs::read(f.state.join("config.json")).unwrap(), original);
    // Use the real recovery path on the existing completed adapter context.
    let mut interrupted = result;
    interrupted.phase = upgrade::Phase::RollingBack;
    storage::atomic(
        &storage::control(&f.state).join("upgrade.json"),
        &interrupted,
    )
    .unwrap();
    assert_eq!(f.run(&id).phase, upgrade::Phase::RolledBack);
    let status = f.healthy(&f.old);
    assert_eq!(status["remoteEnabled"], false);
    assert_eq!(fs::read(f.state.join("config.json")).unwrap(), original);
    f.shutdown().unwrap();
}

#[test]
#[ignore = "requires a freshly built agent; run with POCKET_HANDOFF_CHAIN_AGENT and --ignored"]
fn actual_two_consecutive_handoffs_and_second_generation_rollback() {
    let mut f = Fixture::new();
    let original = fs::read(f.state.join("config.json")).unwrap();
    let agent = PathBuf::from(std::env::var_os("POCKET_HANDOFF_CHAIN_AGENT").unwrap());
    let third = Fixture::generation(f.root.path(), "third", &agent);
    for (source, target) in [(f.old.clone(), f.new.clone()), (f.new.clone(), third)] {
        f.new = target.clone();
        let record_path = f.root.path().join("desktop-install.json");
        let mut record: Value = storage::read(&record_path).unwrap();
        record["generation"] = json!(target);
        record["releaseHash"] = json!(target);
        storage::atomic(&record_path, &record).unwrap();
        let c = upgrade::Coordinator::request(f.root.path(), &f.state, &source).unwrap();
        let id = c.status().operation_id.clone();
        drop(c);
        eprintln!("handoff {source} -> {target}: {}", f.root.path().display());
        let result = f.run(&id);
        assert_eq!(result.phase, upgrade::Phase::Succeeded);
        let status = f.healthy(&target);
        assert_eq!(status["remoteEnabled"], false);
        assert_eq!(status["runtimes"], json!([]));
        assert_eq!(fs::read(f.state.join("config.json")).unwrap(), original);
        if source != f.old {
            let mut interrupted = result;
            interrupted.phase = upgrade::Phase::RollingBack;
            storage::atomic(
                &storage::control(&f.state).join("upgrade.json"),
                &interrupted,
            )
            .unwrap();
            assert_eq!(f.run(&id).phase, upgrade::Phase::RolledBack);
            f.healthy(&source);
        }
    }
    f.shutdown().unwrap();
}
