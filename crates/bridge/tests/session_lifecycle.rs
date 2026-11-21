use serde_json::{Value, json};
use std::{path::PathBuf, process::Stdio, time::Duration};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::TcpStream,
    process::Command,
};

#[tokio::test]
async fn schema_completion_notifies_idle_connected_helper_without_desktop_message() {
    exercise_readiness(true, false, false).await;
}

#[tokio::test]
async fn native_response_notifies_ready_without_initialized_notification() {
    exercise_readiness(false, false, false).await;
}

#[tokio::test]
async fn failed_schema_export_recovers_on_same_native_process_after_recheck() {
    exercise_readiness(false, true, false).await;
}

#[tokio::test]
async fn startup_environment_fences_real_bridge_before_readiness_and_releases_after_receipt() {
    exercise_readiness(true, false, true).await;
}

async fn exercise_readiness(explicit_notification: bool, retry_schema: bool, startup: bool) {
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
    let operation=uuid::Uuid::new_v4().to_string();
    let release=root.join("release.json");
    let mut command=Command::new(env!("CARGO_BIN_EXE_pocket-bridge"));
    command.env_remove("POCKET_UPDATE_OPERATION").env_remove("POCKET_UPDATE_RELEASE_FILE");
    if startup {command.env("POCKET_UPDATE_OPERATION",&operation).env("POCKET_UPDATE_RELEASE_FILE",&release);}
    let mut bridge = command.args(["--original"])
        .arg(&native)
        .args(["--", "app-server"])
        .env("POCKET_RUNTIME_DIR", &root)
        .env("CODEX_HOME", &root)
        .env("POCKET_TEST_SCHEMA_GATE", &gate)
        .env("POCKET_TEST_SCHEMA_FAIL_ONCE", if retry_schema { root.join("failed-once") } else { root.join("missing-parent/disabled") })
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
    assert_eq!(initial["result"]["startupBarrierProtocol"],1);
    if startup {
        assert_eq!(initial["result"]["maintenance"]["startupFenced"],true);
        assert_eq!(initial["result"]["maintenance"]["operationId"],operation);
        desktop.write_all(b"{\"id\":99,\"method\":\"turn/start\",\"params\":{}}\n").await.unwrap();
        let denied=tokio::time::timeout(Duration::from_secs(3),output.next_line()).await.unwrap().unwrap().unwrap();
        assert!(serde_json::from_str::<Value>(&denied).unwrap().get("error").is_some());
    }
    if !retry_schema { assert_eq!(initial["result"]["schemaState"], "initializing"); }
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
    let completion: &[u8] = if explicit_notification { b"{\"method\":\"initialized\"}\n" } else { b"{\"id\":3,\"method\":\"model/list\",\"params\":{}}\n" };
    desktop
        .write_all(completion)
        .await
        .unwrap();
    tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            let event: Value =
                serde_json::from_str(&events.next_line().await.unwrap().unwrap()).unwrap();
            if event["method"] == "pocket/runtimeStatus"
                && event["params"]["initialized"] == true
            {
                if !retry_schema { assert_eq!(event["params"]["schemaPending"], true); }
                break;
            }
        }
    })
    .await
    .expect("handshake completion must be pushed without another client input");
    if retry_schema {
        // The first export failed; no native process restart or desktop input can repair it.
        let failed = tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                writer.write_all(b"{\"id\":4,\"method\":\"pocket/status\",\"params\":{}}\n").await.unwrap();
                let status = loop {
                    let value: Value = serde_json::from_str(&events.next_line().await.unwrap().unwrap()).unwrap();
                    if value["id"] == 4 { break value["result"].clone(); }
                };
                if status["schemaState"] == "error" { break status; }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        }).await.unwrap();
        assert_eq!(failed["schemaState"], "error");
        assert_eq!(failed["nativeCapabilities"], json!({}));
        tokio::time::sleep(Duration::from_millis(5100)).await;
        // A burst of authenticated status checks still schedules only one retry.
        for id in 10..26 {
            writer.write_all(format!("{}\n",json!({"id":id,"method":"pocket/status","params":{}})).as_bytes()).await.unwrap();
        }
    }
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
    assert_eq!(status["nativeProcess"], initial["result"]["nativeProcess"]);
    assert_eq!(status["initialized"], true);
    assert!(status["nativeCapabilities"]["thread/start"].is_array());
    if startup {
        std::fs::write(&release,json!({"schema":1,"operationId":operation,"released":true}).to_string()).unwrap();
        writer.write_all(b"{\"id\":90,\"method\":\"pocket/status\",\"params\":{}}\n").await.unwrap();
        let released=tokio::time::timeout(Duration::from_secs(3),async {loop {
            let v:Value=serde_json::from_str(&events.next_line().await.unwrap().unwrap()).unwrap();
            if v["id"]==90 {break v;}
        }}).await.unwrap();
        assert!(released["result"]["maintenance"].is_null());
    }
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
