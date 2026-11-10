use pocket_runtime_host::client::{Discovery, HostClient, load_descriptor};
use pocket_runtime_host::{Descriptor, HostConfig, read_frame, write_frame};
use serde_json::json;
use std::{path::PathBuf, time::Duration};
use tokio::{io::BufReader, net::TcpStream};

fn config(dir: &std::path::Path) -> HostConfig {
    HostConfig {
        state: dir.join("state"),
        descriptor: dir.join("registry/host.json"),
        binary: PathBuf::from(env!("CARGO_BIN_EXE_pocket-host-fixture")),
        config_home: dir.into(),
        native_args: vec![],
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn client_discovers_same_native_and_answers_a_pending_question_once() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("registry/host.json");
    assert!(matches!(
        HostClient::discover(&path, dir.path()).await.unwrap(),
        Discovery::Missing
    ));
    let host = tokio::spawn(pocket_runtime_host::run(config(dir.path())));
    let d = descriptor(&path).await;
    let mut first = HostClient::attach(&d).await.unwrap();
    let started = first
        .request(
            json!("task"),
            "turn/start",
            json!({"threadId":"fixture-thread"}),
        )
        .await
        .unwrap();
    assert_eq!(started["result"]["writes"], 1);
    drop(first);
    let Discovery::Attached(mut second) = HostClient::discover(&path, dir.path()).await.unwrap()
    else {
        panic!("expected attachment")
    };
    let health = second.health().await.unwrap();
    assert_eq!(health.native_pid, d.native_pid);
    assert_eq!(health.active_turns, 1);
    assert!(pocket_runtime_host::run(config(dir.path())).await.is_err());
    assert_eq!(
        second
            .request(
                json!("task"),
                "turn/start",
                json!({"threadId":"fixture-thread"})
            )
            .await
            .unwrap()["result"]["writes"],
        1
    );
    assert_eq!(
        second.receipt(json!("task")).await.unwrap().state,
        "completed"
    );
    let replay = second
        .replay(second.generation().to_owned().as_str(), 0)
        .await
        .unwrap();
    assert!(
        replay
            .events
            .iter()
            .any(|e| e.event["method"] == "turn/started")
    );
    second
        .request(json!("question"), "fixture/question", json!({}))
        .await
        .unwrap();
    let snapshot = second.recovery().await.unwrap();
    assert_eq!(snapshot["pendingRequests"].as_array().unwrap().len(), 1);
    let receipt = second
        .respond(json!(77), json!({"result":{"decision":"accept"}}))
        .await
        .unwrap();
    assert_eq!(receipt.state, "completed");
    assert_eq!(
        second
            .respond(json!(77), json!({"result":{"decision":"accept"}}))
            .await
            .unwrap()
            .state,
        "completed"
    );
    let count = second
        .request(json!("count"), "fixture/count", json!({}))
        .await
        .unwrap();
    assert_eq!(count["result"]["answers"], 1);
    assert!(
        second
            .respond(json!(77), json!({"result":{"decision":"reject"}}))
            .await
            .is_err()
    );
    let mut client = HostClient::attach(&d).await.unwrap();
    assert!(
        client
            .respond(json!("phone-invented-id"), json!({"result":{}}))
            .await
            .is_err()
    );
    let mut client = HostClient::attach(&d).await.unwrap();
    client
        .request(json!("finish"), "fixture/finish", json!({}))
        .await
        .unwrap();
    client.shutdown_idle().await.unwrap();
    tokio::time::timeout(Duration::from_secs(5), host)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn stale_generation_pid_and_config_home_are_rejected_without_launching() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("registry/host.json");
    let host = tokio::spawn(pocket_runtime_host::run(config(dir.path())));
    let d = descriptor(&path).await;
    let mut stale = d.clone();
    stale.generation = uuid::Uuid::new_v4().to_string();
    assert!(HostClient::attach(&stale).await.is_err());
    let mut stale = d.clone();
    stale.native_process.as_mut().unwrap().created_at -= 1;
    assert!(HostClient::attach(&stale).await.is_err());
    let wrong = tempfile::tempdir().unwrap();
    assert!(load_descriptor(&path, wrong.path()).is_err());
    let mut raw = BufReader::new(TcpStream::connect(("127.0.0.1", d.port)).await.unwrap());
    write_frame(
        raw.get_mut(),
        &json!({"token":d.token,"generation":d.generation}),
    )
    .await
    .unwrap();
    write_frame(raw.get_mut(),&json!({"id":"stale","method":"host/request","params":{"generation":"old","request":{"id":"task","method":"turn/start","params":{}}}})).await.unwrap();
    assert_eq!(
        read_frame(&mut raw).await.unwrap().unwrap()["error"]["code"],
        "generation_mismatch"
    );
    let mut client = HostClient::attach(&d).await.unwrap();
    assert_eq!(
        client
            .request(json!("count"), "fixture/count", json!({}))
            .await
            .unwrap()["result"]["writes"],
        0
    );
    client.shutdown_idle().await.unwrap();
    tokio::time::timeout(Duration::from_secs(5), host)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert!(matches!(
        HostClient::discover(&path, dir.path()).await.unwrap(),
        Discovery::Unavailable { .. }
    ));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn unresolved_command_survives_native_exit_without_automatic_reissue() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("registry/host.json");
    let host = tokio::spawn(pocket_runtime_host::run(config(dir.path())));
    let d = descriptor(&path).await;
    let mut raw = BufReader::new(TcpStream::connect(("127.0.0.1", d.port)).await.unwrap());
    write_frame(
        raw.get_mut(),
        &json!({"token":d.token,"generation":d.generation}),
    )
    .await
    .unwrap();
    write_frame(
        raw.get_mut(),
        &json!({"id":"submit","method":"host/request","params":{"generation":d.generation,
        "request":{"id":"uncertain","method":"fixture/deferred","params":{}}}}),
    )
    .await
    .unwrap();
    loop {
        let value = read_frame(&mut raw).await.unwrap().unwrap();
        if value["id"] == "submit" {
            assert_eq!(value["result"]["state"], "submitted");
            break;
        }
    }
    let mut client = HostClient::attach(&d).await.unwrap();
    assert_eq!(
        client.receipt(json!("uncertain")).await.unwrap().state,
        "unknown"
    );
    assert!(
        client
            .request(json!("exit"), "fixture/exit", json!({}))
            .await
            .is_err()
    );
    tokio::time::timeout(Duration::from_secs(5), host)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    tokio::time::timeout(Duration::from_secs(5), async {
        while !d.native_process.as_ref().unwrap().terminated() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    let mut host = tokio::spawn(pocket_runtime_host::run(config(dir.path())));
    let next = tokio::select! {
        result=&mut host=>panic!("restart returned before discovery: {result:?}"),
        next=tokio::time::timeout(Duration::from_secs(5),async {loop {let next=descriptor(&path).await;if next.generation!=d.generation {break next;}tokio::task::yield_now().await;}})=>next.unwrap(),
    };
    let mut client = HostClient::attach(&next).await.unwrap();
    let receipt = client.receipt(json!("uncertain")).await.unwrap();
    assert_eq!(receipt.state, "unknown");
    assert_eq!(receipt.generation, d.generation);
    assert!(client.health().await.unwrap().reconciliation_required);
    assert!(
        client
            .request(json!("uncertain"), "fixture/deferred", json!({}))
            .await
            .is_err()
    );
    let mut client = HostClient::attach(&next).await.unwrap();
    assert_eq!(
        client
            .request(json!("new-count"), "fixture/count", json!({}))
            .await
            .unwrap()["result"]["writes"],
        0
    );
    assert!(
        client
            .replay(&d.generation, 0)
            .await
            .unwrap()
            .snapshot_required
    );
    client.shutdown_idle().await.unwrap();
    tokio::time::timeout(Duration::from_secs(5), host)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn cancelled_client_request_detaches_and_its_receipt_remains_unknown() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("registry/host.json");
    let host = tokio::spawn(pocket_runtime_host::run(config(dir.path())));
    let d = descriptor(&path).await;
    let mut first = HostClient::attach(&d).await.unwrap();
    let mut second = HostClient::attach(&d).await.unwrap();
    let mut request = Box::pin(first.request(json!("cancelled"), "fixture/deferred", json!({})));
    tokio::time::timeout(Duration::from_secs(5),async {
        loop {
            tokio::select! {
                result=&mut request=>panic!("deferred request unexpectedly replied: {result:?}"),
                receipt=second.receipt(json!("cancelled"))=>if receipt.unwrap().state=="unknown" {break;},
            }
        }
    }).await.unwrap();
    drop(request);
    assert!(first.health().await.is_err());
    let health = second.health().await.unwrap();
    assert_eq!(health.native_pid, d.native_pid);
    assert_eq!(
        second
            .request(json!("count"), "fixture/count", json!({}))
            .await
            .unwrap()["result"]["writes"],
        1
    );
    assert!(
        second
            .request(json!("cancelled"), "fixture/deferred", json!({}))
            .await
            .is_err()
    );
    let mut second = HostClient::attach(&d).await.unwrap();
    assert!(
        second
            .request(json!("exit"), "fixture/exit", json!({}))
            .await
            .is_err()
    );
    tokio::time::timeout(Duration::from_secs(5), host)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
}

async fn descriptor(path: &std::path::Path) -> Descriptor {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if let Ok(bytes) = std::fs::read(path) {
                return serde_json::from_slice(&bytes).unwrap();
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn private_native_content_stays_in_memory_and_cold_receipts_never_dispatch_again() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("registry/host.json");
    let recovery = dir.path().join("state/recovery.json");
    let host = tokio::spawn(pocket_runtime_host::run(config(dir.path())));
    let d = descriptor(&path).await;
    let mut client = HostClient::attach(&d).await.unwrap();
    let private = json!({"text":"fixture-private-output","token":d.token});
    let result = client
        .request(
            json!("private-result"),
            "fixture/private-result",
            private.clone(),
        )
        .await
        .unwrap();
    assert!(result["result"] == private);
    client
        .request(
            json!("secret-question"),
            "fixture/private-question",
            json!({}),
        )
        .await
        .unwrap();
    let answer = json!({"result":{"answers":{"pin":["1234"]}}});
    assert_eq!(
        client
            .respond(json!(77), answer.clone())
            .await
            .unwrap()
            .state,
        "completed"
    );
    assert_eq!(
        client
            .respond(json!(77), answer.clone())
            .await
            .unwrap()
            .state,
        "completed"
    );
    // A request after the echo is a deterministic actor barrier, without a sleep.
    let count = client
        .request(json!("count"), "fixture/count", json!({}))
        .await
        .unwrap();
    assert_eq!(count["result"]["answers"], 1);
    let replay = client.replay(&d.generation, 0).await.unwrap();
    let live = serde_json::to_string(&replay).unwrap();
    assert!(live.contains("fixture-private-output"));
    assert!(live.contains("fixture-private-question-text"));
    assert!(live.contains("1234"));
    let disk = std::fs::read_to_string(&recovery).unwrap();
    for content in [
        "fixture-private-output",
        "fixture-private-question-text",
        "1234",
        d.token.as_str(),
        "digest",
        "reply",
        "events",
        "params",
    ] {
        assert!(!disk.contains(content), "private content persisted");
    }
    // Leave a second question unresolved to test metadata-only cold recovery.
    client
        .request(
            json!("unanswered"),
            "fixture/private-question",
            json!({"requestId":78}),
        )
        .await
        .unwrap();
    assert!(
        client
            .request(json!("exit"), "fixture/exit", json!({}))
            .await
            .is_err()
    );
    tokio::time::timeout(Duration::from_secs(5), host)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    tokio::time::timeout(Duration::from_secs(5), async {
        while !d.native_process.as_ref().unwrap().terminated() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    let mut host = tokio::spawn(pocket_runtime_host::run(config(dir.path())));
    let next = tokio::select! {
        result=&mut host=>panic!("restart returned before discovery: {result:?}"),
        next=tokio::time::timeout(Duration::from_secs(5),async {loop {let next=descriptor(&path).await;if next.generation!=d.generation {break next;}tokio::task::yield_now().await;}})=>next.unwrap(),
    };
    let mut client = HostClient::attach(&next).await.unwrap();
    let receipt = client.receipt(json!("private-result")).await.unwrap();
    assert_eq!(receipt.state, "completed");
    assert!(receipt.response.is_none());
    assert_eq!(receipt.generation, d.generation);
    let snapshot = client.recovery().await.unwrap();
    let pending = &snapshot["pendingRequests"][0];
    assert_eq!(pending["requestId"], 78);
    assert_eq!(pending["state"], "unknown");
    assert_eq!(pending["generation"], d.generation);
    assert!(pending.get("request").is_none());
    assert!(
        !snapshot
            .to_string()
            .contains("fixture-private-question-text")
    );
    assert!(client.health().await.unwrap().reconciliation_required);
    assert!(
        client
            .replay(&d.generation, 0)
            .await
            .unwrap()
            .snapshot_required
    );
    // Both changed bodies and identical bodies remain fenced after restart.
    assert!(
        client
            .request(
                json!("private-result"),
                "fixture/private-result",
                json!({"text":"different"})
            )
            .await
            .is_err()
    );
    let mut client = HostClient::attach(&next).await.unwrap();
    assert!(client.respond(json!(78), answer.clone()).await.is_err());
    let mut client = HostClient::attach(&next).await.unwrap();
    assert!(client.respond(json!(77), answer).await.is_err());
    let mut client = HostClient::attach(&next).await.unwrap();
    let count = client
        .request(json!("new-count"), "fixture/count", json!({}))
        .await
        .unwrap();
    assert_eq!(count["result"]["writes"], 0);
    assert_eq!(count["result"]["answers"], 0);
    client.shutdown_idle().await.unwrap();
    tokio::time::timeout(Duration::from_secs(5), host)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
}

#[tokio::test]
async fn executable_reports_descriptor_failure_without_logging_token() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("host.json");
    let token = "b".repeat(64);
    std::fs::write(
        &path,
        serde_json::to_vec(&json!({token.clone(): true})).unwrap(),
    )
    .unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
    }
    let mut command = tokio::process::Command::new(env!("CARGO_BIN_EXE_pocket-runtime-host"));
    command
        .arg("--state")
        .arg(dir.path().join("state"))
        .arg("--descriptor")
        .arg(&path)
        .arg("--binary")
        .arg(env!("CARGO_BIN_EXE_pocket-host-fixture"))
        .arg("--config-home")
        .arg(dir.path());
    #[cfg(windows)]
    command.creation_flags(0x08000000);
    let output = command.output().await.unwrap();
    assert!(!output.status.success());
    assert!(!String::from_utf8_lossy(&output.stdout).contains(&token));
    assert!(!String::from_utf8_lossy(&output.stderr).contains(&token));
    assert!(!dir.path().join("state").exists()); // Rejected before any host/child launch.
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn status_advertises_private_capabilities_and_creation_identity() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("registry/host.json");
    let host = tokio::spawn(pocket_runtime_host::run(HostConfig {
        state: dir.path().join("state"),
        descriptor: path.clone(),
        binary: PathBuf::from(env!("CARGO_BIN_EXE_pocket-host-fixture")),
        config_home: dir.path().into(),
        native_args: vec![],
    }));
    let d = descriptor(&path).await;
    let mut stream = BufReader::new(TcpStream::connect(("127.0.0.1", d.port)).await.unwrap());
    write_frame(
        stream.get_mut(),
        &json!({"token":d.token,"generation":d.generation}),
    )
    .await
    .unwrap();
    write_frame(
        stream.get_mut(),
        &json!({"id":"health","method":"host/status"}),
    )
    .await
    .unwrap();
    let health = read_frame(&mut stream).await.unwrap().unwrap();
    write_frame(
        stream.get_mut(),
        &json!({"id":"stop","method":"host/shutdown"}),
    )
    .await
    .unwrap();
    let _ = read_frame(&mut stream).await;
    tokio::time::timeout(Duration::from_secs(5), host)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(health["result"]["capabilities"]["phoneNativeRpc"], false);
    assert_eq!(health["result"]["capabilities"]["persistedRecovery"], true);
    assert_eq!(health["result"]["nativeProcess"]["pid"], d.native_pid);
    assert!(
        health["result"]["nativeProcess"]["createdAt"]
            .as_u64()
            .unwrap()
            > 0
    );
    assert!(dir.path().join("state/recovery.json").is_file());
}
