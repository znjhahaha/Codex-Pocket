use super::*;

#[tokio::test]
async fn accepted_send_reply_does_not_wait_for_native_dispatch() {
    let mut f = Fixture::new(false).await;
    let gate = Arc::new(RequestGate::default());
    f.io.0.lock().unwrap().request_gate = Some(("thread/start".into(), gate.clone()));
    let actor = f.actor.as_ref().unwrap().clone();
    let directory = f.project.path().to_path_buf();
    let mut send = tokio::spawn(async move {
        actor.request_from_device("assistant/send", json!({"message":"Only once", "threadId":"desktop-thread"}),
            "prompt-receipt".into(), Some(directory), "phone".into()).await
    });
    tokio::time::timeout(Duration::from_secs(3), async {
        while !gate.entered.load(Ordering::Acquire) { tokio::time::sleep(Duration::from_millis(5)).await; }
    }).await.unwrap();
    let receipt = read_completed_command(&f.root.path().join("assistant"), "phone", "prompt-receipt").unwrap();
    let early = tokio::time::timeout(Duration::from_millis(200), &mut send).await;
    gate.release();
    let replied_before_release = early.is_ok();
    let reply = match early { Ok(value) => value.unwrap().unwrap(), Err(_) => send.await.unwrap().unwrap() };
    f.read().await;
    assert_eq!(f.io.count("thread/start"), 1);
    assert_eq!(f.io.count("turn/start"), 1);
    f.stop().await;
    assert!(receipt.is_some(), "Acceptance must already be durable");
    assert_eq!(reply["accepted"], true);
    assert!(replied_before_release, "Committed send acknowledgment waited for native dispatch");
}

#[tokio::test]
async fn read_wait_is_bounded_while_native_dispatch_is_blocked() {
    let mut f = Fixture::new(false).await;
    let gate = Arc::new(RequestGate::default());
    f.io.0.lock().unwrap().request_gate = Some(("thread/start".into(), gate.clone()));
    let actor = f.actor.as_ref().unwrap().clone();
    let sender = actor.clone();
    let send = tokio::spawn(async move {
        sender.request("assistant/send", json!({"message":"One request"}), "once".into(), None).await
    });
    tokio::time::timeout(Duration::from_secs(3), async {
        while !gate.entered.load(Ordering::Acquire) { tokio::time::sleep(Duration::from_millis(5)).await; }
    }).await.unwrap();
    let read = tokio::time::timeout(Duration::from_secs(3), actor.request("assistant/read", json!({}), "bounded-read".into(), None)).await;
    let cache = actor.snapshot();
    gate.release();
    send.await.unwrap().unwrap();
    f.read().await;
    drop(actor);
    f.stop().await;
    assert_eq!(cache["fresh"], false);
    assert_eq!(cache["snapshotSource"], "worker_cache");
    assert!(read.is_ok(), "Read waited beyond its budget instead of allowing the Broker cache fallback");
    assert!(read.unwrap().is_err());
}
