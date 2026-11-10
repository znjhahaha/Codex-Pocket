use pocket_runtime_ipc::{Descriptor, Discovery, HostClient, PROTOCOL, load_descriptor};
use serde_json::{Value, json};
use std::path::Path;

fn descriptor(home: &Path) -> Descriptor {
    Descriptor {
        protocol: PROTOCOL,
        runtime_id: "fixture-runtime".into(),
        generation: "00000000-0000-0000-0000-000000000001".into(),
        pid: 1,
        native_pid: 2,
        native_process: None,
        host_process: None,
        port: 1,
        token: "a".repeat(64), // Synthetic fixture, never a live descriptor.
        config_home: home.into(),
        source: "cli".into(),
    }
}

fn write_descriptor(path: &Path, value: &Value) {
    std::fs::write(path, serde_json::to_vec(value).unwrap()).unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600)).unwrap();
    }
}

#[test]
fn descriptor_wire_shape_and_configuration_validation_survive_extraction() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("host.json");
    let value = serde_json::to_value(descriptor(dir.path())).unwrap();
    assert_eq!(value["runtimeId"], "fixture-runtime");
    assert!(value.get("configHome").is_some());
    write_descriptor(&path, &value);
    let loaded = load_descriptor(&path, dir.path()).unwrap();
    assert_eq!(loaded.generation, "00000000-0000-0000-0000-000000000001");
    assert_eq!(loaded.protocol, 2);
    assert!(load_descriptor(Path::new("host.json"), dir.path()).is_err());
    let other = tempfile::tempdir().unwrap();
    assert!(load_descriptor(&path, other.path()).is_err());
}

#[test]
fn invalid_descriptor_fields_fail_closed_without_echoing_input() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("host.json");
    let value = serde_json::to_value(descriptor(dir.path())).unwrap();
    for (field, bad) in [
        ("protocol", json!(1)),
        ("port", json!(0)),
        ("pid", json!(0)),
        ("nativePid", json!(0)),
        ("runtimeId", json!("")),
        ("generation", json!("fixture-private-invalid")),
        ("token", json!("fixture-private-invalid")),
        ("source", json!("fixture-private-invalid")),
        ("configHome", json!("fixture-private-invalid")),
        ("fixture-private-invalid", json!(true)),
    ] {
        let mut malformed = value.clone();
        malformed[field] = bad;
        write_descriptor(&path, &malformed);
        let error = load_descriptor(&path, dir.path()).unwrap_err();
        assert!(!format!("{error:#}").contains("fixture-private-invalid"));
    }
}

#[tokio::test]
async fn missing_discovery_and_unverifiable_identity_do_not_launch_anything() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("host.json");
    assert!(matches!(
        HostClient::discover(&path, dir.path()).await.unwrap(),
        Discovery::Missing
    ));
    let value = descriptor(dir.path());
    write_descriptor(&path, &serde_json::to_value(&value).unwrap());
    assert!(HostClient::attach(&value).await.is_err());
    assert!(matches!(
        HostClient::discover(&path, dir.path()).await.unwrap(),
        Discovery::Unavailable { .. }
    ));
    assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
}

#[cfg(windows)]
#[tokio::test]
async fn reused_pid_creation_identity_is_rejected_before_connecting() {
    let dir = tempfile::tempdir().unwrap();
    let identity = pocket_core::native_process::NativeProcess::capture(std::process::id()).unwrap();
    let mut value = descriptor(dir.path());
    value.pid = identity.pid;
    value.native_pid = identity.pid;
    value.native_process = Some(identity.clone());
    value.host_process = Some(pocket_core::native_process::NativeProcess {
        created_at: identity.created_at + 1,
        ..identity
    });
    let error = HostClient::attach(&value).await.unwrap_err();
    assert_eq!(error.to_string(), "Process creation identity mismatch");
}
