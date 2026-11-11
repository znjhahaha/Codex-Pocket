//! Private supervisor startup regressions. All paths and failures are disposable;
//! no installed supervisor, agent, tray, or model is started.
use super::*;
use serde_json::json;
use startup_failure::LaunchFailureCode;

fn tray_fixture(valid_inventory: bool) -> (tempfile::TempDir, PathBuf, PathBuf) {
    use sha2::{Digest, Sha256};
    let dir = crate::test_support::tempdir();
    let bin = dir.path().join("bin");
    let state = dir.path().join("state");
    std::fs::create_dir_all(&bin).unwrap();
    std::fs::create_dir_all(&state).unwrap();
    storage::set_desired(&state, false).unwrap();
    let bytes = b"not an executable";
    std::fs::write(bin.join("pocket-desktop.exe"), bytes).unwrap();
    storage::atomic(&bin.join("windows-manifest.json"), &json!({"files":[{
        "path":"pocket-desktop.exe", "bytes":bytes.len(),
        "sha256":if valid_inventory {format!("{:x}",Sha256::digest(bytes))} else {"0".repeat(64)}
    }]})).unwrap();
    (dir, bin.join("pocket-agent.exe"), state)
}

#[test]
fn tray_verification_failure_does_not_block_background_or_change_pause() {
    let (_dir, agent, state) = tray_fixture(false);
    let desired = std::fs::read(storage::control(&state).join("desired.json")).unwrap();
    let now = Instant::now();
    let mut tray = TrayStartup::new(now);
    let mut spawn_called = false;
    tray.poll(
        now,
        None,
        || tray_command(&agent, &state),
        |_| {
            spawn_called = true;
            Ok(())
        },
    );
    assert!(!spawn_called, "unverified tray must never be launched");
    assert_eq!(
        tray.failure.as_ref().unwrap().code,
        LaunchFailureCode::TrayVerificationFailed
    );
    assert!(maintenance::Gate::default().respawn_ready(false, true));
    assert_eq!(
        std::fs::read(storage::control(&state).join("desired.json")).unwrap(),
        desired
    );
}

#[test]
fn tray_spawn_failure_is_bounded_and_preserves_os_error() {
    let (_dir, agent, state) = tray_fixture(true);
    let now = Instant::now();
    let mut tray = TrayStartup::new(now);
    let mut spawn_calls = 0;
    for seconds in [0, 1, 4, 5, 6, 14, 15, 16, 1000] {
        tray.poll(
            now + Duration::from_secs(seconds),
            Some("a".repeat(64)),
            || tray_command(&agent, &state),
            |command| {
                spawn_calls += 1;
                command.spawn().map(|_| ())
            },
        );
    }
    assert_eq!(spawn_calls, 3);
    assert_eq!(tray.attempts, 3);
    let failure = tray.failure.as_ref().unwrap();
    assert_eq!(failure.code, LaunchFailureCode::TraySpawnFailed);
    assert!(failure.os_code.is_some());
    assert!(!failure.retryable);
    assert_eq!(failure.supervisor_pid, std::process::id());
    let wire = serde_json::to_string(failure).unwrap();
    assert!(!wire.contains(&state.to_string_lossy().to_string()));
    assert!(!wire.contains("not an executable"));
}

#[test]
fn tray_retry_success_clears_failure_and_never_duplicates_dispatch() {
    let now = Instant::now();
    let mut tray = TrayStartup::new(now);
    tray.poll(
        now,
        None,
        || Err(anyhow::anyhow!("private path and token")),
        |_| panic!("verification failed"),
    );
    assert!(tray.failure.is_some());
    tray.poll(
        now + Duration::from_secs(5),
        None,
        || Ok(Command::new("unused")),
        |_| Ok(()),
    );
    assert!(tray.failure.is_none());
    assert_eq!(tray.attempts, 2);
    tray.poll(
        now + Duration::from_secs(1000),
        None,
        || panic!("already dispatched"),
        |_| panic!("already dispatched"),
    );
}

#[test]
fn tray_verification_retries_wait_five_then_ten_seconds_and_exhaust() {
    let (_dir, agent, state) = tray_fixture(false);
    let now = Instant::now();
    let mut tray = TrayStartup::new(now);
    let mut verification_calls = 0;
    for (milliseconds, attempts) in [
        (0, 1),
        (4_999, 1),
        (5_000, 2),
        (14_999, 2),
        (15_000, 3),
        (60_000, 3),
    ] {
        tray.poll(
            now + Duration::from_millis(milliseconds),
            None,
            || {
                verification_calls += 1;
                tray_command(&agent, &state)
            },
            |_| panic!("unverified tray must not spawn"),
        );
        assert_eq!(tray.attempts, attempts, "deadline {milliseconds}");
        assert_eq!(verification_calls, attempts, "deadline {milliseconds}");
        let failure = tray.failure.as_ref().unwrap();
        assert_eq!(failure.code, LaunchFailureCode::TrayVerificationFailed);
        assert_eq!(failure.retryable, attempts < 3);
    }
}

#[test]
fn launch_failure_wire_contract_preserves_wrapped_os_code_without_child_claim() {
    for (code, phase, wire_code) in [
        (
            LaunchFailureCode::TrayVerificationFailed,
            "tray_verify",
            "tray_verification_failed",
        ),
        (
            LaunchFailureCode::TraySpawnFailed,
            "tray_spawn",
            "tray_spawn_failed",
        ),
        (
            LaunchFailureCode::HelperSpawnFailed,
            "helper_spawn",
            "helper_spawn_failed",
        ),
    ] {
        let error = anyhow::Error::from(std::io::Error::from_raw_os_error(5))
            .context("private path")
            .context("private token");
        let operation_id = uuid::Uuid::new_v4().to_string();
        let failure = startup_failure::LaunchFailure::new(
            code,
            operation_id.clone(),
            None,
            error.as_ref(),
            true,
        );
        let wire = serde_json::to_value(&failure).unwrap();
        assert_eq!(
            wire,
            json!({
                "schema": 1, "operationId": operation_id,
                "supervisorPid": std::process::id(), "runtimeGeneration": null,
                "time": failure.time, "phase": phase, "code": wire_code,
                "osCode": 5, "retryable": true,
            })
        );
        assert!(failure.time > 1_000_000_000_000);
        assert!(serde_json::from_value::<startup_failure::LaunchFailure>(wire.clone()).is_ok());
        assert!(serde_json::from_value::<startup_failure::StartupFailure>(wire.clone()).is_err());
        let mut with_private_error = wire;
        with_private_error["rawError"] = json!("private token");
        assert!(
            serde_json::from_value::<startup_failure::LaunchFailure>(with_private_error).is_err()
        );
    }
}

#[test]
fn legacy_status_defaults_launch_fields_and_keeps_child_startup_evidence_separate() {
    let mut legacy = serde_json::to_value(Status::default()).unwrap();
    let object = legacy.as_object_mut().unwrap();
    object.remove("trayStartupAttempts");
    object.remove("trayStartupFailure");
    object.remove("helperSpawnFailure");
    let status: Status = serde_json::from_value(legacy).unwrap();
    let wire = serde_json::to_value(status).unwrap();
    assert_eq!(wire["trayStartupAttempts"], 0);
    for field in ["startupFailure", "trayStartupFailure", "helperSpawnFailure"] {
        assert_eq!(wire[field], serde_json::Value::Null);
        assert!(wire.as_object().unwrap().contains_key(field));
    }
}

#[test]
fn helper_spawn_failure_keeps_native_os_error_and_pause_preference() {
    let (_dir, agent, state) = tray_fixture(false);
    let desired = std::fs::read(storage::control(&state).join("desired.json")).unwrap();
    let mut command = agent_command(&agent, &state).unwrap();
    let mut status = Status::default();
    let error =
        spawn_agent(&mut command, &mut status, "operation", Some("b".repeat(64))).unwrap_err();
    let wire = serde_json::to_value(&status).unwrap();
    assert_eq!(wire["helperSpawnFailure"]["code"], "helper_spawn_failed");
    assert_eq!(
        wire["helperSpawnFailure"]["osCode"],
        error.raw_os_error().unwrap()
    );
    assert_eq!(wire["helperSpawnFailure"]["operationId"], "operation");
    assert_eq!(
        wire["helperSpawnFailure"]["runtimeGeneration"],
        "b".repeat(64)
    );
    assert_eq!(
        wire["helperSpawnFailure"]["supervisorPid"],
        std::process::id()
    );
    assert!(status.child_pid.is_none());
    let heartbeat = storage::control(&state).join("supervisor.json");
    storage::atomic(&heartbeat, &status).unwrap();
    assert_eq!(
        serde_json::to_value(storage::read::<Status>(&heartbeat).unwrap()).unwrap(),
        wire
    );
    assert_eq!(
        std::fs::read(storage::control(&state).join("desired.json")).unwrap(),
        desired
    );
}

#[test]
fn successful_helper_creation_clears_only_its_spawn_failure() {
    let (_dir, agent, state) = tray_fixture(false);
    let mut status = Status::default();
    let _ = spawn_agent(
        &mut agent_command(&agent, &state).unwrap(),
        &mut status,
        "failed",
        None,
    );
    assert!(!serde_json::to_value(&status).unwrap()["helperSpawnFailure"].is_null());
    status.tray_startup_attempts = 3;
    status.tray_startup_failure = Some(startup_failure::LaunchFailure::new(
        LaunchFailureCode::TrayVerificationFailed,
        "tray-operation".into(),
        None,
        &std::io::Error::from_raw_os_error(5),
        false,
    ));
    status.startup_failure = Some(
        serde_json::from_value(json!({
            "schema": 1, "operationId": "child-operation", "pid": 42,
            "runtimeGeneration": null, "time": 1_000_000_000_000_u64,
            "phase": "config", "code": "config_load_failed", "osCode": 5,
        }))
        .unwrap(),
    );
    let independent_failures = serde_json::to_value(&status).unwrap();
    // Listing this test binary does not run tests or start any product process.
    let mut command = Command::new(std::env::current_exe().unwrap());
    command
        .arg("--list")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    process::hidden(&mut command);
    let mut child = spawn_agent(&mut command, &mut status, "success", None).unwrap();
    assert!(child.wait().unwrap().success());
    assert!(serde_json::to_value(&status).unwrap()["helperSpawnFailure"].is_null());
    let wire = serde_json::to_value(status).unwrap();
    for field in [
        "startupFailure",
        "trayStartupAttempts",
        "trayStartupFailure",
    ] {
        assert_eq!(
            wire[field], independent_failures[field],
            "unrelated diagnostic {field}"
        );
    }
}
