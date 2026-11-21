#![cfg(windows)]
use pocket_bridge::registry::{Registry, publish_until_closed};
use serde_json::{Value, json};
use std::time::Duration;

async fn wait_descriptor(path: &std::path::Path) -> Value {
    tokio::time::timeout(Duration::from_secs(6), async {
        loop {
            if let Ok(bytes) = std::fs::read(path) {
                if let Ok(v) = serde_json::from_slice(&bytes) { return v; }
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }).await.expect("package-external registry must publish the live proxy")
}

#[tokio::test]
async fn proxy_can_start_before_backend_and_reregister_after_backend_restart() {
    let root = tempfile::tempdir().unwrap();
    let pipe = format!(r"\\.\pipe\pocket-registry-test-{}", uuid::Uuid::new_v4());
    let id = uuid::Uuid::new_v4().to_string();
    let path = root.path().join(format!("{id}.json"));
    let process = pocket_core::native_process::NativeProcess::capture(std::process::id()).unwrap();
    let record = json!({"protocol":1,"runtimeId":id,"pid":process.pid,"proxyProcess":process,
        "nativeProcess":process,"port":12345,"token":"0123456789abcdef0123456789abcdef",
        "binary":std::env::current_exe().unwrap(),"configHome":root.path(),"source":"desktop"});
    let publisher = publish_until_closed(pipe.clone(), record.clone());
    tokio::time::sleep(Duration::from_millis(80)).await;
    assert!(!path.exists(), "proxy must not publish filesystem state");
    let registry = Registry::start_at(pipe.clone(), root.path().to_owned()).unwrap();
    assert_eq!(wait_descriptor(&path).await["runtimeId"], id);
    drop(registry);
    tokio::time::sleep(Duration::from_millis(100)).await;
    let restarted = Registry::start_at(pipe, root.path().to_owned()).unwrap();
    assert_eq!(wait_descriptor(&path).await["proxyProcess"], record["proxyProcess"]);
    drop(publisher);
    tokio::time::timeout(Duration::from_secs(3), async {
        while path.exists() { tokio::time::sleep(Duration::from_millis(20)).await; }
    }).await.expect("disconnect removes only its own descriptor");
    drop(restarted);
}

#[tokio::test]
async fn registry_rejects_claimed_process_identity_and_traversal() {
    let root = tempfile::tempdir().unwrap();
    let pipe = format!(r"\\.\pipe\pocket-registry-test-{}", uuid::Uuid::new_v4());
    let _registry = Registry::start_at(pipe.clone(), root.path().to_owned()).unwrap();
    let publisher = publish_until_closed(pipe, json!({"protocol":1,"runtimeId":"../escaped",
        "pid":1,"proxyProcess":{"pid":1,"createdAt":1},"port":12345,"token":"secret"}));
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert_eq!(std::fs::read_dir(root.path()).unwrap().count(), 0);
    drop(publisher);
}
