use pocket_agent::{auth::DeviceStore, config::Config, storage};
use serde_json::{Value, json};
use std::process::Command;

fn fixture() -> tempfile::TempDir {
    let dir = tempfile::tempdir().unwrap();
    Config::create("wss://example.invalid/pocket".into())
        .unwrap()
        .save(dir.path())
        .unwrap();
    let mut devices = DeviceStore::default();
    devices
        .add("phone".into(), "Phone".into(), "private-test-secret", 12)
        .unwrap();
    storage::atomic_write(
        &dir.path().join("devices.json"),
        &serde_json::to_vec(&devices).unwrap(),
    )
    .unwrap();
    dir
}
fn cli(dir: &std::path::Path, args: &[&str]) -> (bool, Value) {
    let output = Command::new(env!("CARGO_BIN_EXE_pocket-agent"))
        .args(args)
        .arg("--state")
        .arg(dir)
        .arg("--json")
        .output()
        .unwrap();
    assert!(
        output.stderr.is_empty(),
        "structured errors must not print private error chains"
    );
    let text = String::from_utf8(output.stdout).unwrap();
    assert!(!text.contains("private-test-secret"));
    (
        output.status.success(),
        serde_json::from_str(&text).expect("exactly one JSON response"),
    )
}
#[test]
fn v02_cli_stopped_ignores_even_recent_connected_file() {
    let dir = fixture();
    storage::atomic_write(
        &dir.path().join("status.json"),
        &serde_json::to_vec(
            &json!({"connected":true,"state":"connected","updatedAt":storage::now()}),
        )
        .unwrap(),
    )
    .unwrap();
    let (ok, value) = cli(dir.path(), &["status"]);
    assert!(ok);
    assert_eq!(value["protocol"], 1);
    assert_eq!(value["state"], "stopped");
    assert_eq!(value["connected"], false);
    assert!(value["runtimes"].is_array());
    assert!(value["discoveryErrors"].is_array());
    assert!(value["updatedAt"].is_u64());
}
#[test]
fn v02_cli_devices_revoke_and_pair_contract() {
    let dir = fixture();
    assert_eq!(
        cli(dir.path(), &["devices"]),
        (
            true,
            json!({"data":[{"id":"phone","name":"Phone","createdAt":12}]})
        )
    );
    assert_eq!(
        cli(dir.path(), &["revoke", "--device-id", "phone"]),
        (true, json!({"revoked":true,"deviceId":"phone"}))
    );
    assert_eq!(
        cli(dir.path(), &["revoke", "--device-id", "phone"]).1["revoked"],
        false
    );
    let (ok, pair) = cli(dir.path(), &["pair"]);
    assert!(ok);
    assert_eq!(pair.as_object().unwrap().len(), 4);
    assert!(pair["expiresAt"].as_u64().unwrap() > storage::now());
    for key in ["qrPath", "pngPath", "payloadPath"] {
        assert!(std::path::Path::new(pair[key].as_str().unwrap()).is_file());
    }
    assert_eq!(pair["payloadPath"].as_str(),dir.path().join("pairing-qr.json").to_str());
}
#[test]
fn v02_cli_invalid_parameters_are_nonzero_redacted_json() {
    let dir = fixture();
    for args in [
        vec!["revoke"],
        vec!["revoke", "--device-id"],
        vec!["status", "--unknown"],
        vec!["devices", "--state"],
    ] {
        let (ok, error) = cli(dir.path(), &args);
        assert!(!ok);
        assert_eq!(error["error"]["code"], "invalid_params");
    }
}
#[tokio::test(flavor = "multi_thread")]
async fn v02_live_cli_mutates_broker_and_reports_real_connection() {
    let dir = fixture();
    let lock = pocket_agent::local::try_lock(dir.path()).unwrap().unwrap();
    let (broker, _) =
        pocket_agent::broker::Broker::open(Config::load(dir.path()).unwrap(), dir.path().into())
            .unwrap();
    let server = pocket_agent::local::Server::start(dir.path(), broker.clone())
        .await
        .unwrap();
    broker.connection.activity();
    assert_eq!(cli(dir.path(), &["status"]).1["connected"], true);
    assert_eq!(
        cli(dir.path(), &["revoke", "--device-id", "phone"]).1["revoked"],
        true
    );
    assert!(!broker.device_exists("phone").await);
    assert_eq!(cli(dir.path(), &["devices"]).1["data"], json!([]));
    broker.connection.set("reconnecting");
    assert_eq!(cli(dir.path(), &["status"]).1["connected"], false);
    assert!(cli(dir.path(), &["pair"]).0);
    drop(server);
    drop(lock);
    assert_eq!(cli(dir.path(), &["status"]).1["state"], "stopped");
}
#[test]
fn v02_locked_unresponsive_helper_never_edits_devices_offline() {
    let dir = fixture();
    let _lock = pocket_agent::local::try_lock(dir.path()).unwrap().unwrap();
    assert!(!cli(dir.path(), &["revoke", "--device-id", "phone"]).0);
    let saved: Value =
        serde_json::from_slice(&std::fs::read(dir.path().join("devices.json")).unwrap()).unwrap();
    assert!(saved["devices"].get("phone").is_some());
    let (_, status) = cli(dir.path(), &["status"]);
    assert_eq!(status["connected"], false);
    assert_eq!(status["discoveryErrors"][0]["code"], "control_unavailable");
}
