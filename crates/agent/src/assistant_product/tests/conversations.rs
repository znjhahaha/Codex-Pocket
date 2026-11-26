use super::*;

#[test]
fn conversation_origin_backfill_rolls_back_a_failed_batch() {
    let root = tempfile::tempdir().unwrap();
    let (frames, _) = tokio::sync::broadcast::channel(32);
    let worker = Worker::open(
        root.path().into(), "computer".into(), "profile".into(),
        Fake::default(), None, Arc::new(|| 1_000_000), frames,
    ).unwrap();
    let db = &worker.messages;
    db.execute_batch("INSERT INTO product_timeline(id,role,text,at_ms) VALUES
        ('legacy-a','User','First',1),('legacy-b','User','Second',2);
        CREATE TRIGGER fail_origin_batch BEFORE INSERT ON product_conversation_origins
        WHEN (SELECT COUNT(*) FROM product_conversation_origins)>0
        BEGIN SELECT RAISE(ABORT,'fixture write failure'); END;").unwrap();
    assert!(super::super::conversations::sync_origins(&worker.service, db).is_err());
    assert_eq!(db.query_row("SELECT COUNT(*) FROM product_conversation_origins", [],
        |r| r.get::<_, i64>(0)).unwrap(), 0);
    db.execute_batch("DROP TRIGGER fail_origin_batch").unwrap();
    super::super::conversations::sync_origins(&worker.service, db).unwrap();
    let origins = db.prepare("SELECT conversation_id FROM product_conversation_origins ORDER BY id")
        .unwrap().query_map([], |r| r.get::<_, String>(0)).unwrap()
        .collect::<rusqlite::Result<Vec<_>>>().unwrap();
    assert_eq!(origins, vec![worker.service.default_conversation_id(); 2]);
}

#[tokio::test]
async fn product_conversations_select_page_replay_stream_and_reopen() {
    let mut f = Fixture::new(false).await;
    let old = f.send("main-message").await;
    let main = old["conversationId"].as_str().unwrap().to_owned();
    let lead = Fixture::op(&old, "Coordinator");
    let create = f
        .call(
            "assistant/v2/conversation/create",
            json!({"title":"Second"}),
            "new-chat",
            false,
        )
        .await
        .unwrap();
    let id = create["conversation"]["id"].as_str().unwrap().to_owned();
    let same = f
        .call(
            "assistant/v2/conversation/create",
            json!({"title":"Second"}),
            "new-chat",
            false,
        )
        .await
        .unwrap();
    assert_eq!(same["conversation"], create["conversation"]);
    let blank = f
        .call(
            "assistant/read",
            json!({"conversationId":id}),
            "blank",
            false,
        )
        .await
        .unwrap();
    assert_eq!(blank["messages"], json!([]));
    assert!(blank["coordinatorThreadId"].is_null());
    assert!(blank["activeCoordinatorOperationId"].is_null());
    assert_eq!(f.io.count("turn/start"), 1);
    let mut stream = f.actor.as_ref().unwrap().subscribe_stream();
    for n in 0..3 {
        f.call(
            "assistant/v2/send",
            json!({"conversationId":id,"message":format!("second-{n}")}),
            &format!("new-send-{n}"),
            false,
        )
        .await
        .unwrap();
    }
    f.call(
        "assistant/v2/send",
        json!({"conversationId":id,"message":"second-0"}),
        "new-send-0",
        false,
    )
    .await
    .unwrap();
    let selected = f
        .call(
            "assistant/v2/read",
            json!({"conversationId":id,"limit":2}),
            "selected",
            false,
        )
        .await
        .unwrap();
    assert_eq!(selected["conversationId"], id);
    let events=f.call("assistant/read",json!({"conversationId":id,"after":0}),"events",false).await.unwrap();
    assert!(events["events"].as_array().unwrap().iter().all(|event|event["conversationId"].is_string()));
    assert_eq!(selected["messages"].as_array().unwrap().len(), 2);
    assert!(
        selected["messages"]
            .as_array()
            .unwrap()
            .iter()
            .all(|m| m["conversationId"] == id)
    );
    assert_ne!(selected["coordinatorThreadId"], old["coordinatorThreadId"]);
    let older = f
        .call(
            "assistant/read",
            json!({"conversationId":id,"limit":2,"before":selected["nextBefore"]}),
            "older",
            false,
        )
        .await
        .unwrap();
    assert_eq!(older["messages"].as_array().unwrap().len(), 1);
    assert!(older["nextBefore"].is_null());
    assert_eq!(f.read().await["conversationId"], main);
    assert_eq!(f.read().await["messages"].as_array().unwrap().len(), 1);
    let inventory = f
        .call(
            "assistant/v2/conversation/list",
            json!({}),
            "inventory",
            false,
        )
        .await
        .unwrap();
    assert_eq!(inventory["data"].as_array().unwrap().len(), 2);
    assert!(
        f.call(
            "assistant/read",
            json!({"conversationId":"missing"}),
            "bad",
            false
        )
        .await
        .is_err()
    );
    assert!(
        f.call(
            "assistant/v2/conversation/interrupt",
            json!({"conversationId":id,"operationId":lead["id"]}),
            "wrong-stop",
            false
        )
        .await
        .is_err()
    );
    let target = selected["activeCoordinatorOperationId"].as_str().unwrap();
    let target_op = selected["operations"]
        .as_array()
        .unwrap()
        .iter()
        .find(|op| op["id"] == target)
        .unwrap();
    f.io.emit(
        Value::Null,
        "item/agentMessage/delta",
        &Fixture::mapping(target_op),
        json!({"itemId":"answer","delta":"only second chat"}),
    );
    f.call(
        "assistant/read",
        json!({"conversationId":id}),
        "delta-read",
        false,
    )
    .await
    .unwrap();
    let mut saw_delta = false;
    while let Ok(frame) = stream.try_recv() {
        if frame["operationId"] == target {
            assert_eq!(frame["conversationId"], id);
            saw_delta |= frame["kind"] == "coordinator_delta";
        }
    }
    assert!(saw_delta, "second conversation delta was not observed");
    f.call(
        "assistant/v2/conversation/interrupt",
        json!({"conversationId":id,"operationId":target}),
        "stop",
        false,
    )
    .await
    .unwrap();
    assert!(f.io.count("turn/interrupt") >= 1);
    assert!(
        f.io.0
            .lock()
            .unwrap()
            .calls
            .iter()
            .filter(|(_, method, _)| method == "turn/interrupt")
            .all(|(_, _, p)| p["threadId"] == selected["coordinatorThreadId"])
    );
    let now = f.read().await;
    assert_eq!(now["paused"], false);
    assert!(
        now["operations"]
            .as_array()
            .unwrap()
            .iter()
            .any(|op| op["id"] == lead["id"] && op["suspended"] == false)
    );
    let selected = f
        .call(
            "assistant/read",
            json!({"conversationId":id,"limit":2}),
            "before-restart",
            false,
        )
        .await
        .unwrap();
    f.stop().await;
    let db = Connection::open(f.root.path().join("assistant/messages.db")).unwrap();
    db.execute("DELETE FROM product_conversation_origins WHERE id IN (SELECT id FROM product_timeline WHERE operation_id=?1)",[target]).unwrap();
    drop(db);
    let retained = retained::snapshot(
        &f.root.path().join("assistant"),
        &json!({"conversationId":id,"limit":2}),
    )
    .unwrap();
    assert!(
        !retained::snapshot(&f.root.path().join("assistant"), &json!({})).unwrap()["messages"]
            .to_string()
            .contains("only second chat")
    );
    assert_eq!(
        retained["messages"]
            .as_array()
            .unwrap()
            .iter()
            .map(|m| &m["id"])
            .collect::<Vec<_>>(),
        selected["messages"]
            .as_array()
            .unwrap()
            .iter()
            .map(|m| &m["id"])
            .collect::<Vec<_>>()
    );
    f.start().await;
    let reopened = f
        .call(
            "assistant/read",
            json!({"conversationId":id,"limit":2}),
            "reopen",
            false,
        )
        .await
        .unwrap();
    assert_eq!(reopened["messages"], selected["messages"]);
    f.stop().await;
}

#[test]
fn lazy_conversation_create_preserves_retained_work_and_receipts() {
    let root = tempfile::tempdir().unwrap();
    let p = json!({"runtimeId":"scope","commandId":"create","title":"Before runtime"});
    let created =
        retained::create_conversation(root.path(), "computer", "profile", "phone", &p).unwrap();
    assert!(!root.path().join("executor.db").exists());
    assert!(!retained::needs_wake(root.path(), 1_000_000).unwrap());
    assert_eq!(created["snapshot"]["messages"], json!([]));
    assert_eq!(
        created["snapshot"]["conversations"]
            .as_array()
            .unwrap()
            .len(),
        2
    );
    let same =
        retained::create_conversation(root.path(), "computer", "profile", "phone", &p).unwrap();
    assert_eq!(same["conversation"], created["conversation"]);
    let (_, receipt) = command_receipts::read_completed_command(root.path(), "phone", "create")
        .unwrap()
        .unwrap();
    assert_eq!(receipt["conversation"], created["conversation"]);
    let db = Connection::open(root.path().join("assistant.db")).unwrap();
    let before = retained::state(root.path()).unwrap();
    let mut raw: Value = serde_json::from_str(
        &db.query_row("SELECT data FROM assistant_service_state", [], |r| {
            r.get::<_, String>(0)
        })
        .unwrap(),
    )
    .unwrap();
    raw["inbox"] = json!([{"id":"queued","message":"queued","directories":[],"recovery_of":null}]);
    db.execute(
        "UPDATE assistant_service_state SET data=?1",
        [raw.to_string()],
    )
    .unwrap();
    retained::create_conversation(
        root.path(),
        "computer",
        "profile",
        "phone",
        &json!({"runtimeId":"scope","commandId":"second"}),
    )
    .unwrap();
    let after = retained::state(root.path()).unwrap();
    assert_eq!(after["inbox"], raw["inbox"]);
    assert_eq!(after["operations"], before["operations"]);
    assert_eq!(after["journal"]["create"], before["journal"]["create"]);
}
