use super::*;
use crate::native::test_peer::{self, Peer};

async fn fixture() -> (tempfile::TempDir, Arc<Broker>, Peer) {
    let (dir, broker) = super::super::tests::fixture().await;
    let (mut connected, peer) = test_peer::connect(
        "cli",
        "cli",
        &broker.state_dir,
        broker.native_events.clone(),
    )
    .await;
    connected.native_process = Some(NativeProcess::capture(std::process::id()).unwrap());
    broker.add_runtime(connected).await.unwrap();
    (dir, broker, peer)
}

fn thread(status: Value) -> Value {
    json!({"result":{"thread":{"id":"thread-1","status":status}}})
}

// Exercise the real RPC framing and broker snapshot. Only these read methods
// are permitted: a lifecycle check must not resume, replay or stop native work.
async fn sample(broker: &Broker, peer: &mut Peer, response: Value) -> (Value, usize) {
    let status = broker.lifecycle_status();
    tokio::pin!(status);
    let mut lists = 0;
    let mut reads = 0;
    let value = loop {
        tokio::select! {
            value = &mut status => break value,
            request = peer.recv() => match request["method"].as_str() {
                Some("thread/loaded/list") => {
                    lists += 1;
                    assert!(lists <= 2);
                    peer.reply(&request, json!({"data":["thread-1"],"nextCursor":null})).await;
                }
                Some("thread/read") => {
                    reads += 1;
                    assert_eq!(reads, 1);
                    assert_eq!(request["params"], json!({"threadId":"thread-1","includeTurns":false}));
                    let mut response = response.clone();
                    response["id"] = request["id"].clone();
                    peer.send(response).await;
                }
                _ => panic!("lifecycle attempted a non-read RPC"),
            }
        }
    };
    assert_eq!(reads, 1);
    (value, lists)
}

#[tokio::test]
async fn cli_system_error_is_a_verified_non_active_thread_status() {
    let (_dir, broker, mut peer) = fixture().await;
    let (value, lists) = sample(&broker, &mut peer, thread(json!({"type":"systemError"}))).await;
    assert_eq!(value["activeTurns"], 0, "{value}");
    assert_eq!(value["nativeTasksPreserved"], true);
    assert_eq!(value["idle"], true);
    assert_eq!(value["reason"], Value::Null);
    assert_eq!(lists, 2, "the loaded inventory must still be rechecked");
}

#[tokio::test]
async fn cli_errors_and_unrecognized_statuses_never_prove_idle() {
    for response in [
        json!({"error":{"code":-32603,"message":"systemError"}}),
        json!({"result":{"error":{"type":"systemError"}}}),
        thread(Value::Null),
        thread(json!({})),
        thread(json!({"type":"error"})),
        thread(json!({"type":"notLoaded"})),
        thread(json!({"type":"futureStatus"})),
        thread(json!("systemError")),
        json!({"result":{"thread":{"id":"different-thread","status":{"type":"systemError"}}}}),
    ] {
        let (_dir, broker, mut peer) = fixture().await;
        let (value, _) = sample(&broker, &mut peer, response).await;
        assert_eq!(value["idle"], false);
        assert_eq!(value["nativeTasksPreserved"], false);
        assert_eq!(value["activeTurns"], Value::Null);
        assert_eq!(value["reason"], "runtime_activity_unverified");
    }
}

#[tokio::test]
async fn cli_active_and_waiting_statuses_keep_blocking() {
    for flags in [
        json!([]),
        json!(["waitingOnApproval"]),
        json!(["waitingOnUserInput"]),
    ] {
        let (_dir, broker, mut peer) = fixture().await;
        let (value, lists) = sample(
            &broker,
            &mut peer,
            thread(json!({"type":"active","activeFlags":flags})),
        )
        .await;
        assert_eq!(value["idle"], false);
        assert_eq!(value["nativeTasksPreserved"], true);
        assert_eq!(value["activeTurns"], 1);
        assert_eq!(lists, 2);
    }
}

#[tokio::test]
async fn cli_system_error_keeps_pocket_writes_pending_and_unknown_receipts() {
    let (_dir, broker, mut peer) = fixture().await;
    let unknown = CommandKey {
        caller_id: "fixture".into(),
        command_id: "unknown".into(),
    };
    let submitted = CommandKey {
        caller_id: "fixture".into(),
        command_id: "submitted".into(),
    };
    {
        let mut state = broker.state.lock().await;
        state
            .ledger
            .begin(unknown.clone(), b"fixture", storage::now())
            .unwrap();
        state
            .ledger
            .finish(&unknown, Status::Unknown, storage::now())
            .unwrap();
        state
            .ledger
            .begin(submitted, b"fixture", storage::now())
            .unwrap();
        broker.save_ledger(&state).unwrap();
    }
    let before = std::fs::read(broker.state_dir.join("commands.json")).unwrap();
    let (value, _) = sample(&broker, &mut peer, thread(json!({"type":"systemError"}))).await;
    assert_eq!(value["activeTurns"], 0);
    assert_eq!(value["pendingWrites"], 1);
    assert_eq!(value["unknownOperations"], 1);
    assert_eq!(value["checkpointableUnknownOperations"], 1);
    assert_eq!(value["idle"], false);
    assert_eq!(
        std::fs::read(broker.state_dir.join("commands.json")).unwrap(),
        before
    );
    let state = broker.state.lock().await;
    let ledger: Value = serde_json::from_str(&state.ledger.snapshot_json()).unwrap();
    assert_eq!(ledger["entries"].as_array().unwrap().len(), 2);
    assert!(
        ledger["entries"]
            .as_array()
            .unwrap()
            .iter()
            .any(|row| row["status"] == "Unknown")
    );
}

#[tokio::test]
async fn cli_system_error_keeps_the_broker_write_fence() {
    let (_dir, broker, mut peer) = fixture().await;
    let _writer = broker.writes.lock().await;
    let (value, _) = sample(&broker, &mut peer, thread(json!({"type":"systemError"}))).await;
    assert_eq!(value["activeTurns"], 0);
    assert_eq!(value["pendingWrites"], 1);
    assert_eq!(value["idle"], false);
}

#[tokio::test]
async fn cli_system_error_keeps_unanswered_approvals() {
    let (_dir, broker, mut peer) = fixture().await;
    broker.state.lock().await.pending.insert("fixture-token".into(), super::super::Pending {
        runtime: "cli".into(), native_id: json!(7),
        event: json!({"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1"}}),
        command: None, response: None, winner_received: false,
    });
    let (value, _) = sample(&broker, &mut peer, thread(json!({"type":"systemError"}))).await;
    assert_eq!(value["activeTurns"], 0);
    assert_eq!(value["pendingQuestions"], 1);
    assert_eq!(value["idle"], false);
    assert_eq!(broker.state.lock().await.pending.len(), 1);
}

#[tokio::test]
async fn cli_system_error_does_not_confirm_a_delivered_answer() {
    let (_dir, broker, mut peer) = fixture().await;
    let key = CommandKey {
        caller_id: "fixture".into(),
        command_id: "answer".into(),
    };
    let before = {
        let mut state = broker.state.lock().await;
        let store = state.runtimes["cli"].store.clone();
        state
            .ledger
            .begin(key.clone(), b"fixture", storage::now())
            .unwrap();
        state
            .ledger
            .finish(&key, Status::Unknown, storage::now())
            .unwrap();
        state.questions.reserve(key.clone(), "cli", &store,
            &json!({"id":7,"method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"turn","itemId":"item","questions":[{"id":"q","isSecret":false}]}}),
            &json!({"answers":{"q":{"answers":["FIXTURE-PRIVATE-ANSWER"]}}})).unwrap();
        state.questions.forwarded(&key);
        serde_json::to_value(&state.questions).unwrap()
    };
    let (value, _) = sample(&broker, &mut peer, thread(json!({"type":"systemError"}))).await;
    assert_eq!(value["activeTurns"], 0);
    assert_eq!(value["unknownOperations"], 1);
    assert_eq!(value["checkpointableUnknownOperations"], 0);
    assert_eq!(value["idle"], false);
    assert!(!value.to_string().contains("FIXTURE-PRIVATE-ANSWER"));
    assert_eq!(
        serde_json::to_value(&broker.state.lock().await.questions).unwrap(),
        before
    );
}

#[tokio::test]
async fn cli_system_error_keeps_inventory_and_connection_rechecks() {
    for changed in ["inventory", "connection", "process"] {
        let (_dir, broker, mut peer) = fixture().await;
        let status = broker.lifecycle_status();
        tokio::pin!(status);
        let mut lists = 0;
        let mut reads = 0;
        let value = loop {
            tokio::select! {
                value = &mut status => break value,
                request = peer.recv() => match request["method"].as_str() {
                    Some("thread/loaded/list") => {
                        lists += 1;
                        assert!(lists <= 2);
                        if lists == 2 && changed != "inventory" {
                            let mut state = broker.state.lock().await;
                            let runtime = state.runtimes.get_mut("cli").unwrap();
                            if changed == "process" { runtime.native_process = None; }
                            else { runtime.connection_id = "replacement".into(); }
                        }
                        let ids = if lists == 2 && changed == "inventory" { vec!["thread-1", "thread-2"] } else { vec!["thread-1"] };
                        peer.reply(&request, json!({"data":ids,"nextCursor":null})).await;
                    }
                    Some("thread/read") => {
                        reads += 1;
                        assert_eq!(reads, 1);
                        peer.reply(&request, thread(json!({"type":"systemError"}))["result"].clone()).await;
                    }
                    _ => panic!("lifecycle attempted a non-read RPC"),
                }
            }
        };
        assert_eq!(lists, 2);
        assert_eq!(value["idle"], false);
        assert_eq!(value["nativeTasksPreserved"], false);
        assert_eq!(value["activeTurns"], Value::Null);
    }
}
