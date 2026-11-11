use serde_json::{Value, json};
use std::{path::PathBuf, process::Stdio, time::Duration};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::TcpStream,
    process::Command,
};

#[tokio::test]
async fn schema_completion_notifies_idle_connected_helper_without_desktop_message() {
    let root = std::env::temp_dir().join(format!("pocket-lifecycle-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&root).unwrap();
    let native = root.join(if cfg!(windows) {
        "fake-native.exe"
    } else {
        "fake-native"
    });
    let source =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/fake_lifecycle_native.rs");
    assert!(
        Command::new("rustc")
            .arg(source)
            .arg("-o")
            .arg(&native)
            .status()
            .await
            .unwrap()
            .success()
    );
    let gate = root.join("schema-ready");
    let mut bridge = Command::new(env!("CARGO_BIN_EXE_pocket-bridge"))
        .args(["--original"])
        .arg(&native)
        .args(["--", "app-server"])
        .env("POCKET_RUNTIME_DIR", &root)
        .env("CODEX_HOME", &root)
        .env("POCKET_TEST_SCHEMA_GATE", &gate)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let mut desktop = bridge.stdin.take().unwrap();
    let mut output = BufReader::new(bridge.stdout.take().unwrap()).lines();
    let descriptor = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if let Some(path) = std::fs::read_dir(&root)
                .unwrap()
                .filter_map(Result::ok)
                .map(|e| e.path())
                .find(|p| p.extension().is_some_and(|x| x == "json"))
            {
                break serde_json::from_slice::<Value>(&std::fs::read(path).unwrap()).unwrap();
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
    let mut events = BufReader::new(reader).lines();
    writer
        .write_all(format!("{}\n", json!({"token":descriptor["token"]})).as_bytes())
        .await
        .unwrap();
    writer
        .write_all(b"{\"id\":1,\"method\":\"pocket/status\",\"params\":{}}\n")
        .await
        .unwrap();
    let initial: Value = serde_json::from_str(&events.next_line().await.unwrap().unwrap()).unwrap();
    assert_eq!(initial["result"]["schemaState"], "initializing");
    desktop
        .write_all(b"{\"id\":2,\"method\":\"initialize\",\"params\":{}}\n")
        .await
        .unwrap();
    let init = tokio::time::timeout(Duration::from_secs(3), output.next_line())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(serde_json::from_str::<Value>(&init).unwrap()["id"], 2);
    desktop
        .write_all(b"{\"method\":\"initialized\"}\n")
        .await
        .unwrap();
    tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            let event: Value =
                serde_json::from_str(&events.next_line().await.unwrap().unwrap()).unwrap();
            if event["method"] == "pocket/runtimeStatus"
                && event["params"]["initialized"] == true
            {
                assert_eq!(event["params"]["schemaPending"], true);
                break;
            }
        }
    })
    .await
    .expect("initialized notification must publish handshake completion");
    // Release schema export after initialization; there are no further inputs.
    std::fs::write(&gate, b"ready").unwrap();
    let status = tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            let event: Value =
                serde_json::from_str(&events.next_line().await.unwrap().unwrap()).unwrap();
            if event["method"] == "pocket/runtimeStatus"
                && event["params"]["schemaState"] == "ready"
            {
                break event["params"].clone();
            }
        }
    })
    .await
    .expect("schema completion must wake the bridge without another desktop RPC");
    assert_eq!(status["runtimeId"], descriptor["runtimeId"]);
    assert_eq!(status["initialized"], true);
    assert!(status["nativeCapabilities"]["thread/start"].is_array());
    // Graceful EOF reaps only this test's fake child.
    drop(desktop);
    assert!(
        tokio::time::timeout(Duration::from_secs(3), bridge.wait())
            .await
            .unwrap()
            .unwrap()
            .success()
    );
}
