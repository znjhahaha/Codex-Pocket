//! Read-only lifecycle sampling against disposable Host/native fixture processes.
#![cfg(feature = "server")]

use pocket_runtime_ipc::{HostClient, load_descriptor, read_frame, write_frame};
use serde_json::{Value, json};
use std::time::Duration;
use tokio::{io::BufReader, net::TcpStream};

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn host_recovery_serializes_completed_and_withheld_receipt_counters() {
    let dir = tempfile::tempdir().unwrap();
    let descriptor_path = dir.path().join("registry/host.json");
    let mut command = tokio::process::Command::new(env!("CARGO_BIN_EXE_pocket-host-fixture"));
    command
        .arg("--host-test-driver")
        .arg(dir.path())
        .kill_on_drop(true);
    #[cfg(windows)]
    command.creation_flags(0x08000000);
    let mut child = command.spawn().unwrap();

    let evidence = async {
        let descriptor = tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                if let Ok(descriptor) = load_descriptor(&descriptor_path, dir.path()) {
                    break descriptor;
                }
                tokio::task::yield_now().await;
            }
        }).await?;
        let mut client = HostClient::attach(&descriptor).await?;
        let empty = client.recovery().await?;
        client.request(json!("question"), "fixture/question", json!({})).await?;
        let question = client.recovery().await?;
        let answer = client.respond(json!(77), json!({"result":{"decision":"accept"}})).await?;
        // This causal native read also drains the fixture's answer notifications.
        client.request(json!("count"), "fixture/count", json!({})).await?;
        let completed = client.recovery().await?;

        let mut raw = BufReader::new(TcpStream::connect(("127.0.0.1", descriptor.port)).await?);
        write_frame(raw.get_mut(), &json!({"token":descriptor.token,"generation":descriptor.generation})).await?;
        write_frame(raw.get_mut(), &json!({"id":"submit","method":"host/request","params":{
            "generation":descriptor.generation,"request":{"id":"withheld","method":"fixture/deferred","params":{}}
        }})).await?;
        let submitted = tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let value = read_frame(&mut raw).await?.ok_or_else(|| anyhow::anyhow!("fixture disconnected"))?;
                if value["id"] == "submit" {
                    break Ok::<Value, anyhow::Error>(value);
                }
            }
        }).await??;
        let receipt = client.receipt(json!("withheld")).await?;
        let health = client.health().await?;
        let pending = client.recovery().await?;
        let repeated = client.recovery().await?;
        Ok::<_, anyhow::Error>((descriptor, empty, question, answer, completed, submitted, receipt, health, pending, repeated))
    }.await;

    // Cleanup happens after sampling, by a fixture-only exit sentinel. No
    // shutdown request is used to discover whether the native runtime is idle.
    std::fs::write(dir.path().join("native-exit"), b"fixture cleanup").unwrap();
    assert!(
        tokio::time::timeout(Duration::from_secs(5), child.wait())
            .await
            .unwrap()
            .unwrap()
            .success()
    );
    let (
        descriptor,
        empty,
        question,
        answer,
        completed,
        submitted,
        receipt,
        health,
        pending,
        repeated,
    ) = evidence.unwrap();
    for snapshot in [&empty, &question, &completed] {
        assert_eq!(snapshot["generation"], descriptor.generation);
        assert_eq!(snapshot["unconfirmedWrites"], 0);
        assert_eq!(snapshot["unknownOperations"], 0);
    }
    assert_eq!(empty["commandCount"], 0);
    assert_eq!(empty["answerCount"], 0);
    assert_eq!(question["pendingRequests"].as_array().unwrap().len(), 1);
    assert_eq!(answer.state, "completed");
    assert_eq!(answer.response.unwrap()["result"]["written"], true);
    assert_eq!(completed["commandCount"], 2);
    assert_eq!(completed["answerCount"], 1);
    assert_eq!(submitted["result"]["state"], "submitted");
    assert_eq!(receipt.state, "unknown");
    assert_eq!(health.active_turns, 0);
    assert_eq!(health.pending_requests, 0);
    assert!(!health.reconciliation_required);
    assert_eq!(pending["generation"], descriptor.generation);
    assert_eq!(pending["commandCount"], 3);
    assert_eq!(pending["answerCount"], 1);
    assert_eq!(pending["unconfirmedWrites"], 1);
    assert_eq!(pending["unknownOperations"], 1);
    assert_eq!(pending, repeated);
}
