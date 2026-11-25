use super::{tests::{fixture, peer}, *};

#[tokio::test]
async fn internal_context_native_thread_start_opts_into_advertised_raw_events() {
    for supported in [true, false] {
        let (_dir, broker) = fixture().await;
        let mut native = peer(&broker, "context-runtime", "cli").await;
        if supported {
            broker.state.lock().await.runtimes.get_mut("context-runtime").unwrap().capabilities.0
                .entry("thread/start".into()).or_default().insert("experimentalRawEvents".into());
        } else {
            broker.state.lock().await.runtimes.get_mut("context-runtime").unwrap().capabilities.0
                .entry("thread/start".into()).or_default().remove("experimentalRawEvents");
        }
        let task = tokio::spawn({ let broker = broker.clone(); async move {
            broker.request("phone", "codex/request", json!({"commandId":"start-context","runtimeId":"context-runtime","method":"thread/start","params":{}})).await
        }});
        super::tests::loaded(&mut native, &[]).await;
        let start = native.recv().await;
        assert_eq!(start["method"], "thread/start");
        assert_eq!(start["params"]["experimentalRawEvents"] == true, supported);
        native.reply(&start, json!({"thread":{"id":"context-thread"}})).await;
        super::tests::finish(task).await.unwrap();
    }
}

#[tokio::test]
async fn internal_context_live_items_are_marked_before_journal_and_phone_delivery() {
    let (_dir, broker) = fixture().await;
    let mut native = peer(&broker, "context-runtime", "cli").await;
    let before = broker.sync(&json!({})).await.unwrap()["cursors"].clone();
    let mut events = broker.subscribe();
    let text = "<environment_context><cwd>/project</cwd></environment_context>";
    // Raw native provenance arrives before the UI lifecycle event, even when
    // the rollout writer has not materialized this new thread on disk yet.
    for (id, provenance, kind, hidden) in [
        ("generated", "harness", "environments.environment_context", true),
        ("authored", "user", "user.text", false),
    ] {
        native.send(json!({"method":"rawResponseItem/completed","params":{"threadId":"t","turnId":"u","item":{
            "id":format!("raw-{id}"),"type":"message","role":"user","content":[{"type":"input_text","text":text}],
            "internal_chat_message_metadata_passthrough":{"content_item_kinds":[kind],
                "content_item_metadata":[{"provenance":{"type":provenance}}]}
        }}})).await;
        for method in ["item/started", "item/completed"] {
            native.send(json!({"method":method,"params":{"threadId":"t","turnId":"u","item":{
                "id":id,"type":"userMessage","content":[{"type":"text","text":text}]
            }}})).await;
            let event = tokio::time::timeout(Duration::from_secs(3), async {
                loop {
                    let event = events.recv().await.unwrap();
                    if event["params"]["event"]["method"] == method { break event; }
                }
            }).await.unwrap();
            assert_eq!(event["params"]["event"]["params"]["item"]["pocketContextSource"] == "runtime-environment", hidden, "{id} {method}");
        }
    }
    let replay = broker.sync(&json!({"cursors":before})).await.unwrap();
    let rows = replay["events"].as_array().unwrap();
    let lifecycle: Vec<_> = rows.iter().filter(|row| matches!(row["event"]["method"].as_str(), Some("item/started" | "item/completed"))).collect();
    assert_eq!(lifecycle.len(), 4, "{replay}");
    for row in lifecycle {
        let item = &row["event"]["params"]["item"];
        assert_eq!(item["pocketContextSource"] == "runtime-environment", item["id"] == "generated");
    }
}
