use pocket_agent::{auth, broker::Broker, config::Config, session, storage};
use pocket_tunnel::{read_json, write_json};
use serde_json::{Value, json};
use std::time::Duration;

fn invite(dir: &std::path::Path) {
    storage::atomic_write(
        &dir.join("pairing.json"),
        &serde_json::to_vec(&json!({
            "tokenHash": auth::hash("local-test-invitation"),
            "expiresAt": storage::now() + 300
        })).unwrap(),
    ).unwrap();
}

#[tokio::test]
async fn ninth_device_keeps_capacity_reason_through_the_real_pairing_wire_reply() {
    let dir = tempfile::tempdir().unwrap();
    let (broker, _events) = Broker::open(Config::create_local().unwrap(), dir.path().into()).unwrap();
    for i in 0..8 {
        invite(dir.path());
        broker.pair(&json!({
            "pairingToken": "local-test-invitation", "deviceId": format!("phone-{i}"), "deviceName": "QA phone"
        })).await.unwrap();
    }
    invite(dir.path());
    let (mut client, server) = tokio::io::duplex(65_536);
    let serving = tokio::spawn(session::serve(server, broker));
    write_json(&mut client, &json!({"id": "ninth", "method": "pair", "params": {
        "pairingToken": "local-test-invitation", "deviceId": "phone-8", "deviceName": "QA phone"
    }})).await.unwrap();
    let reply: Value = tokio::time::timeout(Duration::from_secs(5), read_json(&mut client))
        .await.unwrap().unwrap().unwrap();
    drop(client);
    tokio::time::timeout(Duration::from_secs(5), serving).await.unwrap().unwrap().unwrap();

    let stored: auth::DeviceStore = serde_json::from_slice(&std::fs::read(dir.path().join("devices.json")).unwrap()).unwrap();
    assert_eq!(stored.devices.len(), 8);
    assert!(!stored.devices.contains_key("phone-8"));
    let invitation: Value = serde_json::from_slice(&std::fs::read(dir.path().join("pairing.json")).unwrap()).unwrap();
    assert!(invitation["expiresAt"].as_u64().unwrap() > storage::now());
    assert_eq!(reply["id"], "ninth");
    assert_eq!(reply["error"]["code"], "device_limit_reached",
        "A capacity refusal must survive DeviceStore -> Broker -> session::serve");
    assert!(reply.get("result").is_none());
}
