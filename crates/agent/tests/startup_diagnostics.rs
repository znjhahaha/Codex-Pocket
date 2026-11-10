//! Isolated early-failure fixtures only: never reach a running daemon or native model.
use pocket_agent::{
    config::Config,
    startup_diagnostics::{self, Attempt, Code, Failure, Phase, Record},
    storage,
};
use serde_json::Value;
use std::{fs, path::Path, process::Command};

fn failed_run(state: &Path) -> Value {
    let output = Command::new(env!("CARGO_BIN_EXE_pocket-agent"))
        .args(["run", "--supervised", "--state"])
        .arg(state)
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(output.stdout.is_empty());
    let stderr = String::from_utf8(output.stderr).unwrap();
    assert_eq!(stderr.lines().count(), 1);
    assert!(stderr.len() <= startup_diagnostics::MAX_RECORD_BYTES as usize + 1);
    assert!(!stderr.contains("private-fixture"));
    assert!(!stderr.contains(&state.to_string_lossy().to_string()));
    serde_json::from_str(&stderr).expect("one structured sanitized startup record")
}

fn configured() -> tempfile::TempDir {
    let dir = tempfile::tempdir().unwrap();
    storage::private_directory(dir.path()).unwrap();
    Config::create("wss://example.invalid/pocket".into())
        .unwrap()
        .save(dir.path())
        .unwrap();
    dir
}

#[test]
fn missing_and_partial_identities_are_never_initialized() {
    for partial in [false, true] {
        let dir = configured();
        let identity = dir.path().join("identity");
        if partial {
            storage::private_directory(&identity).unwrap();
            fs::write(
                identity.join("certificate.der"),
                b"private-fixture-certificate",
            )
            .unwrap();
        }
        let config = fs::read(dir.path().join("config.json")).unwrap();
        let record = failed_run(dir.path());
        assert_eq!(record["phase"], "identity");
        assert_eq!(record["code"], "identity_load_failed");
        assert_eq!(fs::read(dir.path().join("config.json")).unwrap(), config);
        assert!(!identity.join("private-key.der").exists());
        assert!(!identity.join("identity.lock").exists());
        if partial {
            assert_eq!(
                fs::read(identity.join("certificate.der")).unwrap(),
                b"private-fixture-certificate"
            );
        } else {
            assert!(!identity.exists());
        }
    }
}

#[test]
fn broker_failure_preserves_valid_identity_and_configuration() {
    let dir = configured();
    let identity = dir.path().join("identity");
    pocket_tunnel::ServerIdentity::load_or_create(&identity).unwrap();
    let before: Vec<_> = [
        "identity/certificate.der",
        "identity/private-key.der",
        "config.json",
    ]
    .into_iter()
    .map(|name| (name, fs::read(dir.path().join(name)).unwrap()))
    .collect();
    fs::write(
        dir.path().join("devices.json"),
        b"private-fixture-bad-store",
    )
    .unwrap();
    let record = failed_run(dir.path());
    assert_eq!(record["phase"], "broker");
    assert_eq!(record["code"], "broker_open_failed");
    for (name, bytes) in before {
        assert_eq!(fs::read(dir.path().join(name)).unwrap(), bytes);
    }
    assert_eq!(
        fs::read(dir.path().join("devices.json")).unwrap(),
        b"private-fixture-bad-store"
    );
    assert!(!dir.path().join("control.json").exists());
}

#[test]
fn lock_contention_has_its_own_code_and_does_not_touch_config() {
    let dir = configured();
    let lock = pocket_agent::local::try_lock(dir.path()).unwrap().unwrap();
    let config = fs::read(dir.path().join("config.json")).unwrap();
    let record = failed_run(dir.path());
    assert_eq!(record["phase"], "state_lock");
    assert_eq!(record["code"], "state_lock_contended");
    assert!(record["osCode"].is_i64());
    assert_eq!(fs::read(dir.path().join("config.json")).unwrap(), config);
    drop(lock);
}

#[test]
fn stage_records_extract_only_numeric_os_error_from_chains() {
    let dir = tempfile::tempdir().unwrap();
    let mut attempt = Attempt::current();
    attempt.prepare_state(dir.path()).unwrap();
    for (phase, code) in [
        (Phase::StatePath, Code::StatePathInvalid),
        (Phase::StateAccess, Code::StateAccessFailed),
        (Phase::StateLock, Code::StateLockFailed),
        (Phase::Config, Code::ConfigLoadFailed),
        (Phase::Identity, Code::IdentityLoadFailed),
        (Phase::Broker, Code::BrokerOpenFailed),
        (Phase::ControlBind, Code::ControlBindFailed),
    ] {
        let error = anyhow::Error::new(std::io::Error::from_raw_os_error(5))
            .context("private-fixture token wss://secret.invalid C:/private-fixture key");
        let error = attempt.step::<()>(phase, Err(error)).unwrap_err();
        let record = &error.downcast_ref::<Failure>().unwrap().0;
        assert_eq!(record.phase, phase);
        assert_eq!(record.code, code);
        assert_eq!(record.os_code, Some(5));
        assert!(!format!("{error:?}").contains("private-fixture"));
        let bytes = fs::read(dir.path().join(startup_diagnostics::RECORD_FILE)).unwrap();
        assert_eq!(Record::parse(&bytes).unwrap(), *record);
        let value: Value = serde_json::from_slice(&bytes).unwrap();
        let mut keys: Vec<_> = value
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect();
        keys.sort();
        assert_eq!(
            keys,
            [
                "code",
                "operationId",
                "osCode",
                "phase",
                "pid",
                "runtimeGeneration",
                "schema",
                "time"
            ]
        );
    }
}

#[test]
fn unverified_state_failures_stay_stderr_capable_without_persistence() {
    let attempt = Attempt::current();
    let error = attempt
        .step::<()>(
            Phase::StateAccess,
            Err(anyhow::anyhow!("private-fixture whoami or ACL details")),
        )
        .unwrap_err();
    let record = &error.downcast_ref::<Failure>().unwrap().0;
    assert_eq!(record.phase, Phase::StateAccess);
    assert_eq!(record.os_code, None);
    assert!(Record::parse(record.json().unwrap().as_bytes()).is_ok());
    assert!(!format!("{error:?}").contains("private-fixture"));
    assert!(!attempt.clear_own().unwrap());
}

#[test]
fn records_are_correlated_and_clear_only_the_own_operation() {
    let dir = tempfile::tempdir().unwrap();
    let mut first = Attempt::current();
    first.prepare_state(dir.path()).unwrap();
    let mut second = Attempt::current();
    second.prepare_state(dir.path()).unwrap();
    let error = first
        .step::<()>(Phase::Config, Err(anyhow::anyhow!("fixture")))
        .unwrap_err();
    let original = error.downcast_ref::<Failure>().unwrap().0.clone();
    let path = dir.path().join(startup_diagnostics::RECORD_FILE);
    assert!(!second.clear_own().unwrap());
    assert!(path.exists());
    assert!(!original.matches(
        first.operation_id(),
        original.pid.wrapping_add(1),
        original.runtime_generation.as_deref()
    ));
    assert!(!original.matches(first.operation_id(), original.pid, Some(&"a".repeat(64))));
    assert!(
        startup_diagnostics::read_matching(
            dir.path(),
            first.operation_id(),
            original.pid,
            original.runtime_generation.as_deref()
        )
        .unwrap()
        .is_some()
    );
    assert!(
        startup_diagnostics::read_matching(
            dir.path(),
            second.operation_id(),
            original.pid,
            original.runtime_generation.as_deref()
        )
        .unwrap()
        .is_none()
    );
    // Even an own operation ID cannot clear evidence carrying another PID/generation.
    for change in ["pid", "generation"] {
        let mut stale = original.clone();
        if change == "pid" {
            stale.pid = stale.pid.wrapping_add(1);
        } else {
            stale.runtime_generation = Some("b".repeat(64));
        }
        fs::write(&path, stale.json().unwrap()).unwrap();
        assert!(!first.clear_own().unwrap());
        assert!(path.exists());
    }
    fs::write(&path, original.json().unwrap()).unwrap();
    assert!(first.clear_own().unwrap());
    assert!(!path.exists());
    assert!(!first.clear_own().unwrap());
}

#[test]
fn parser_rejects_oversize_unknown_fields_and_mismatched_codes() {
    let dir = tempfile::tempdir().unwrap();
    let mut attempt = Attempt::current();
    attempt.prepare_state(dir.path()).unwrap();
    let error = attempt
        .step::<()>(Phase::Config, Err(anyhow::anyhow!("fixture")))
        .unwrap_err();
    let record = &error.downcast_ref::<Failure>().unwrap().0;
    let mut value = serde_json::to_value(record).unwrap();
    for key in value.as_object().unwrap().keys() {
        let mut incomplete = value.clone();
        incomplete.as_object_mut().unwrap().remove(key);
        assert!(
            Record::parse(&serde_json::to_vec(&incomplete).unwrap()).is_err(),
            "Missing field must fail: {key}"
        );
    }
    value["path"] = Value::String("private-fixture".into());
    let rejected = Record::parse(&serde_json::to_vec(&value).unwrap()).unwrap_err();
    assert!(!rejected.to_string().contains("private-fixture"));
    value.as_object_mut().unwrap().remove("path");
    value["code"] = Value::String("identity_load_failed".into());
    assert!(Record::parse(&serde_json::to_vec(&value).unwrap()).is_err());
    assert!(
        Record::parse(&vec![
            b' ';
            startup_diagnostics::MAX_RECORD_BYTES as usize + 1
        ])
        .is_err()
    );
    let path = dir.path().join(startup_diagnostics::RECORD_FILE);
    fs::write(
        &path,
        vec![b' '; startup_diagnostics::MAX_RECORD_BYTES as usize + 1],
    )
    .unwrap();
    assert!(
        startup_diagnostics::read_matching(
            dir.path(),
            attempt.operation_id(),
            record.pid,
            record.runtime_generation.as_deref()
        )
        .is_err()
    );
    assert!(attempt.clear_own().is_err());
    assert!(path.exists());
}

#[test]
fn persistence_failure_keeps_the_original_phase_and_sanitized_stderr_record() {
    let dir = tempfile::tempdir().unwrap();
    let mut attempt = Attempt::current();
    attempt.prepare_state(dir.path()).unwrap();
    fs::create_dir(dir.path().join(startup_diagnostics::RECORD_FILE)).unwrap();
    let error = attempt
        .step::<()>(
            Phase::ControlBind,
            Err(std::io::Error::from_raw_os_error(10048).into()),
        )
        .unwrap_err();
    let record = &error.downcast_ref::<Failure>().unwrap().0;
    assert_eq!(record.code, Code::ControlBindFailed);
    assert_eq!(record.os_code, Some(10048));
    assert!(record.json().is_ok());
    assert!(dir.path().join(startup_diagnostics::RECORD_FILE).is_dir());
}

#[test]
fn hardlinked_diagnostic_destination_is_never_replaced() {
    let dir = tempfile::tempdir().unwrap();
    let mut attempt = Attempt::current();
    attempt.prepare_state(dir.path()).unwrap();
    let external = dir.path().join("private-fixture");
    fs::write(&external, b"preserved").unwrap();
    let path = dir.path().join(startup_diagnostics::RECORD_FILE);
    fs::hard_link(&external, &path).unwrap();
    let error = attempt
        .step::<()>(Phase::Config, Err(anyhow::anyhow!("fixture")))
        .unwrap_err();
    assert_eq!(
        error.downcast_ref::<Failure>().unwrap().0.phase,
        Phase::Config
    );
    assert_eq!(fs::read(path).unwrap(), b"preserved");
    assert_eq!(fs::read(external).unwrap(), b"preserved");
}

#[test]
fn config_failure_is_persisted_without_rewriting_config_or_creating_identity() {
    let dir = tempfile::tempdir().unwrap();
    let original = br#"{"private-fixture":"wss://secret.invalid/?token=private-fixture"}"#;
    fs::write(dir.path().join("config.json"), original).unwrap();
    let record = failed_run(dir.path());
    assert_eq!(record["phase"], "config");
    assert_eq!(record["code"], "config_load_failed");
    assert_eq!(fs::read(dir.path().join("config.json")).unwrap(), original);
    assert!(!dir.path().join("identity").exists());
    let persisted: Value =
        serde_json::from_slice(&fs::read(dir.path().join("startup-failure.json")).unwrap())
            .unwrap();
    assert_eq!(persisted, record);
}

#[test]
fn invalid_state_is_stderr_only_and_does_not_change_the_target() {
    let dir = tempfile::tempdir().unwrap();
    let state = dir.path().join("private-fixture");
    fs::write(&state, b"preserved").unwrap();
    let record = failed_run(&state);
    assert_eq!(record["phase"], "state_path");
    assert_eq!(record["code"], "state_path_invalid");
    assert_eq!(fs::read(&state).unwrap(), b"preserved");
    assert_eq!(fs::read_dir(dir.path()).unwrap().count(), 1);
}
