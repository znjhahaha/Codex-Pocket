//! Exercises the real submit path with an in-memory native pipe, never a process.
use super::*;

fn legacy_full_store() -> (tempfile::TempDir, Store) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("recovery.json");
    let old = uuid::Uuid::new_v4().to_string();
    let mut records = serde_json::Map::new();
    for n in 0..4096 {
        records.insert(
            format!("s:legacy-{n}"),
            json!({"generation":old,"completed":true}),
        );
    }
    // Exact schema-2 cold evidence shape; legacy methods/bodies are unavailable.
    std::fs::write(
        &path,
        serde_json::to_vec(&json!({
            "schema":2,"runtime_id":"fixture","generation":old,"sequence":4099,
            "commands":records,"answers":{},"runs":{},"pending":{},"reconciliation_required":false
        }))
        .unwrap(),
    )
    .unwrap();
    let current = uuid::Uuid::new_v4().to_string();
    let mut store = Store::open(&path, "fixture", &current).unwrap();
    store.begin_generation(&current).unwrap();
    assert!(!store.state.reconciliation_required);
    (dir, store)
}

pub(super) async fn submit_request(store: &mut Store, request: Value) -> (Value, Option<Value>) {
    let (mut writer, native) = tokio::io::duplex(16384);
    let mut native = BufReader::new(native);
    let (reply, mut replies) = mpsc::channel(4);
    submit(&mut writer, store, request, &reply, Some(json!("outer")))
        .await
        .unwrap();
    drop(writer);
    let result = replies.try_recv().unwrap().value;
    let frame = read_frame(&mut native).await.unwrap();
    (result, frame)
}

#[tokio::test]
async fn receipt_capacity_full_legacy_ledger_allows_account_read_without_new_durable_record() {
    let (dir, mut store) = legacy_full_store();
    let before: Value =
        serde_json::from_slice(&std::fs::read(dir.path().join("recovery.json")).unwrap()).unwrap();
    let (result, native) = submit_request(
        &mut store,
        json!({"id":"account-now","method":"account/read","params":{"refreshToken":false}}),
    )
    .await;
    assert_eq!(result["result"]["state"], "submitted", "{result}");
    let native = native.unwrap();
    assert_eq!(native["method"], "account/read");
    let event = store
        .observe_native(json!({"id":native["id"],"result":{"account":{"type":"apiKey"}}}))
        .unwrap();
    assert_eq!(event["params"]["event"]["id"], "account-now");
    store.save().unwrap();
    let after: Value =
        serde_json::from_slice(&std::fs::read(dir.path().join("recovery.json")).unwrap()).unwrap();
    assert_eq!(after["commands"], before["commands"]);
    assert_eq!(store.state.snapshot()["unconfirmedWrites"], 0);
}

#[tokio::test]
async fn receipt_capacity_full_legacy_ledger_has_bounded_room_for_new_effect() {
    let (_dir, mut store) = legacy_full_store();
    let request = json!({"id":"new-effect","method":"turn/start","params":{"threadId":"thread"}});
    let (result, native) = submit_request(&mut store, request.clone()).await;
    assert_eq!(result["result"]["state"], "submitted", "{result}");
    assert_eq!(native, Some(request.clone()));
    assert_eq!(store.state.commands.len(), 4097);
    let (repeated, native) = submit_request(&mut store, request).await;
    assert_eq!(repeated["result"]["state"], "unknown");
    assert!(native.is_none(), "Unknown effects must never be resent");
    assert_eq!(store.state.receipt("s:legacy-0", false).state, "completed");
}

pub(super) fn empty_store(dir: &tempfile::TempDir) -> Store {
    Store::open(
        &dir.path().join("recovery.json"),
        "fixture",
        &uuid::Uuid::new_v4().to_string(),
    )
    .unwrap()
}

#[tokio::test]
async fn receipt_capacity_old_completed_and_unknown_ids_never_take_query_bypass() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = empty_store(&dir);
    for (id, completed) in [("old-completed", true), ("old-unknown", false)] {
        let value = json!({"id":id,"method":"turn/start","params":{}});
        let key = key(&value["id"]).unwrap();
        store.state.reserve(key.clone(), &value, false).unwrap();
        if completed {
            store
                .state
                .complete(&key, json!({"id":id,"result":{}}), false);
        }
    }
    store.save().unwrap();
    drop(store);
    let mut store = empty_store(&dir);
    store
        .begin_generation(&uuid::Uuid::new_v4().to_string())
        .unwrap();
    assert!(store.state.reconciliation_required);
    for (id, expected) in [("old-completed", "completed"), ("old-unknown", "unknown")] {
        let (result, native) = submit_request(
            &mut store,
            json!({"id":id,"method":"account/read","params":{"refreshToken":false}}),
        )
        .await;
        assert_eq!(result["result"]["state"], expected);
        assert!(native.is_none());
    }
    let (result, native) = submit_request(
        &mut store,
        json!({"id":"safe-evidence","method":"account/read","params":{"refreshToken":false}}),
    )
    .await;
    assert_eq!(result["result"]["state"], "submitted");
    assert!(native.is_some());
    assert!(store.state.reconciliation_required);
    for (id, method, params) in [
        ("refresh", "account/read", json!({"refreshToken":true})),
        ("resume", "thread/resume", json!({"threadId":"t"})),
        (
            "new-option",
            "thread/read",
            json!({"threadId":"t","forceResume":true}),
        ),
    ] {
        let (result, native) =
            submit_request(&mut store, json!({"id":id,"method":method,"params":params})).await;
        assert_eq!(result["error"]["code"], "reconciliation_required");
        assert!(native.is_none());
    }
    assert_eq!(store.state.commands.len(), 2);
    assert_eq!(store.state.snapshot()["unconfirmedWrites"], 1);
}

#[tokio::test]
async fn receipt_capacity_read_aliases_cannot_complete_or_conflict_with_effects() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = empty_store(&dir);
    let read = json!({"id":"reused","method":"account/read","params":{"refreshToken":false}});
    let (_, native) = submit_request(&mut store, read.clone()).await;
    let original = native.unwrap();
    let effect = json!({"id":"reused","method":"turn/start","params":{}});
    let (conflict, native) = submit_request(&mut store, effect.clone()).await;
    assert_eq!(conflict["error"]["code"], "conflict");
    assert!(native.is_none());
    let (_, native) = submit_request(&mut store, read).await;
    assert!(
        native.is_none(),
        "Pending read duplicates also wait for their receipt"
    );
    store
        .observe_native(json!({"id":original["id"],"result":{}}))
        .unwrap();
    assert_eq!(store.receipt("s:reused").state, "completed");
    for n in 0..128 {
        let admitted = store
            .reserve_request(&json!({"id":format!("poll-{n}"),"method":"model/list","params":{}}))
            .unwrap();
        store
            .observe_native(json!({"id":admitted.native["id"],"result":{}}))
            .unwrap();
    }
    let (result, native) = submit_request(&mut store, effect.clone()).await;
    assert_eq!(result["result"]["state"], "submitted");
    assert_eq!(native, Some(effect));
    store
        .observe_native(json!({"id":original["id"],"result":{"late":true}}))
        .unwrap();
    assert_eq!(
        store.receipt("s:reused").state,
        "unknown",
        "Late read must not acknowledge the write"
    );
    let (result, native) = submit_request(
        &mut store,
        json!({"id":original["id"],"method":"turn/start","params":{}}),
    )
    .await;
    assert_eq!(result["error"]["code"], "unsupported");
    assert!(native.is_none());
}

#[tokio::test]
async fn receipt_capacity_query_cache_is_not_persisted_or_a_cold_write_fence() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = empty_store(&dir);
    let old = store.state.generation.clone();
    for id in ["withheld-read", "completed-read"] {
        let (_, native) = submit_request(
            &mut store,
            json!({"id":id,"method":"account/read","params":{"refreshToken":false}}),
        )
        .await;
        if id == "completed-read" {
            store
                .observe_native(
                    json!({"id":native.unwrap()["id"],"result":{"private":"volatile-only"}}),
                )
                .unwrap();
        }
    }
    store.save().unwrap();
    let saved = std::fs::read_to_string(dir.path().join("recovery.json")).unwrap();
    for secret in [
        "withheld-read",
        "completed-read",
        "volatile-only",
        NATIVE_QUERY_PREFIX,
    ] {
        assert!(!saved.contains(secret));
    }
    assert_eq!(store.state.snapshot()["unconfirmedWrites"], 0);
    drop(store);
    let mut store = empty_store(&dir);
    let current = uuid::Uuid::new_v4().to_string();
    store.begin_generation(&current).unwrap();
    assert_ne!(current, old);
    assert!(!store.state.reconciliation_required);
    assert_eq!(store.receipt("s:withheld-read").state, "missing");
    let (result, native) = submit_request(
        &mut store,
        json!({"id":"withheld-read","method":"account/read","params":{"refreshToken":false}}),
    )
    .await;
    assert_eq!(result["result"]["state"], "submitted");
    assert!(
        native.is_some(),
        "Only classified reads may be queried anew after a cold restart"
    );
}

#[tokio::test]
async fn receipt_capacity_effect_save_failure_never_writes_native_stdin() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = empty_store(&dir);
    // Fault injection in this disposable fixture only: replacement cannot
    // rename the atomic temporary file over a directory.
    std::fs::create_dir(dir.path().join("recovery.json")).unwrap();
    let (mut writer, native) = tokio::io::duplex(16384);
    let mut native = BufReader::new(native);
    let (reply, mut replies) = mpsc::channel(4);
    let request = json!({"id":"save-failure","method":"turn/start","params":{}});
    assert!(
        submit(
            &mut writer,
            &mut store,
            request,
            &reply,
            Some(json!("outer"))
        )
        .await
        .is_err()
    );
    drop(writer);
    assert!(read_frame(&mut native).await.unwrap().is_none());
    assert!(
        replies.try_recv().is_err(),
        "No optimistic submitted receipt"
    );
}
