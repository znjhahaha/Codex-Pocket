use super::tests::{finish, fixture, loaded, peer};
use super::*;
use crate::native::test_peer;

#[tokio::test]
async fn owner_conflict_projection_distinguishes_uncertain_multiple_and_recovered() {
    for (competitor, expected) in [(false, "owner_uncertain"), (true, "multiple_owners")] {
        let (dir, b) = fixture().await;
        let mut native = peer(&b, "a", "desktop").await;
        let mut other = if competitor { Some(peer(&b, "b", "desktop").await) } else { None };
        {
            let mut state = b.state.lock().await;
            for runtime in state.runtimes.values_mut() {
                runtime.loaded.insert("t".into());
                runtime.inventory_ready = competitor;
            }
            // A connected transport with an unconfirmed native initialization
            // retains its claim, but is not an established online owner.
            if !competitor { state.runtimes.get_mut("a").unwrap().desktop_initialized = Some(false); }
            b.save_ownership(&mut state).unwrap();
        }
        let before = std::fs::read(dir.path().join("ownership.json")).unwrap();
        let list = tokio::spawn({ let b = b.clone(); async move { b.threads(&json!({})).await } });
        if competitor {
            loaded(&mut native, &["t"]).await;
            loaded(other.as_mut().unwrap(), &["t"]).await;
        }
        let request = native.recv().await;
        assert_eq!(request["method"], "thread/list");
        native.reply(&request, json!({"data":[{"id":"t"}],"nextCursor":null})).await;
        let rows = finish(list).await.unwrap();
        assert_eq!(rows["data"][0]["conflict"], true);
        assert_eq!(rows["data"][0]["ownershipReason"], expected);
        let access = b.access_snapshot(&json!({"runtimeId":"a","threadId":"t"})).await.unwrap();
        assert_eq!(access["ownershipReason"], expected);
        assert_eq!(access["reason"], expected);
        assert_eq!(access["canSend"], false);
        assert!(access["ownerRuntimeId"].is_null());
        if !competitor {
            assert_eq!(std::fs::read(dir.path().join("ownership.json")).unwrap(), before);
            // Only a complete fresh inventory restores this exact runtime.
            b.state.lock().await.runtimes.get_mut("a").unwrap().desktop_initialized = Some(true);
            let refresh = tokio::spawn({ let b = b.clone(); async move { b.refresh_loaded().await } });
            loaded(&mut native, &["t"]).await;
            refresh.await.unwrap().unwrap();
            let recovered = b.access_snapshot(&json!({"runtimeId":"a","threadId":"t"})).await.unwrap();
            assert_eq!(recovered["canSend"], true);
            assert_eq!(recovered["ownerRuntimeId"], "a");
            assert!(recovered["ownershipReason"].is_null());
        }
        native.assert_idle().await;
        if let Some(other) = other.as_mut() { other.assert_idle().await; }
    }
}

fn ready(caps: &crate::capabilities::Capabilities) -> Value {
    json!({"runtimeId":"a","initialized":true,"schemaPending":false,"schemaState":"ready","nativeCapabilities":caps})
}

async fn p2_pending_desktop(b: &Broker, id: &str) -> test_peer::Peer {
    let (mut connected, mut native) =
        test_peer::connect(id, "desktop", &b.state_dir, b.native_events.clone()).await;
    connected.capabilities = Default::default();
    let register = b.add_runtime(connected);
    let snapshot = async {
        let request = native.recv().await;
        assert_eq!(request["method"], "pocket/approvals/list");
        native.reply(&request, json!({"data":[]})).await;
    };
    let (result, _) = tokio::join!(register, snapshot);
    result.unwrap();
    native
}

fn p2_pending_status(id: &str) -> Value {
    json!({"runtimeId":id,"initialized":false,"schemaPending":true,"schemaState":"initializing","nativeCapabilities":{}})
}

async fn p2_observe_status(b: &Arc<Broker>, native: &mut test_peer::Peer, status: Value) {
    let task = tokio::spawn({
        let b = b.clone();
        async move { b.request("phone", "runtimes/list", json!({})).await }
    });
    let request = native.recv().await;
    assert_eq!(request["method"], "pocket/status");
    native.reply(&request, status).await;
    finish(task).await.unwrap();
}

async fn p2_reject_inventory(native: &mut test_peer::Peer, request: &Value) {
    assert_eq!(request["method"], "thread/loaded/list");
    native
        .send(json!({"id":request["id"],"error":{"code":-32000,"message":"Not initialized"}}))
        .await;
}

#[tokio::test]
async fn p2_uninitialized_inventory_rejection_cannot_mask_creation_not_ready() {
    let (_dir, b) = fixture().await;
    let mut native = p2_pending_desktop(&b, "a").await;
    let mut task = tokio::spawn({
        let b = b.clone();
        async move {
            b.request("phone", "codex/request", json!({"commandId":"not-ready","runtimeId":"a","method":"thread/start","params":{}})).await
        }
    });
    let status = native.recv().await;
    assert_eq!(status["method"], "pocket/status");
    native.reply(&status, p2_pending_status("a")).await;
    let result = tokio::select! {
        result = &mut task => result.unwrap(),
        request = native.recv() => {
            p2_reject_inventory(&mut native, &request).await;
            finish(task).await
        }
    };
    assert_eq!(
        crate::error::code(&result.unwrap_err()),
        Code::RuntimeUnavailable
    );
    native.assert_idle().await;
}

#[tokio::test]
async fn p2_confirmed_uninitialized_peer_does_not_block_ready_runtime_send() {
    let (_dir, b) = fixture().await;
    let mut cold = p2_pending_desktop(&b, "a").await;
    let mut warm = peer(&b, "b", "desktop").await;
    p2_observe_status(&b, &mut cold, p2_pending_status("a")).await;
    let mut task = tokio::spawn({
        let b = b.clone();
        async move {
            b.request("phone", "codex/request", json!({"commandId":"warm-send","runtimeId":"b","method":"turn/start","params":{"threadId":"warm-thread","input":[{"type":"text","text":"synthetic"}]}})).await
        }
    });
    let result = tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            tokio::select! {
                result = &mut task => break result.unwrap(),
                request = cold.recv() => p2_reject_inventory(&mut cold, &request).await,
                request = warm.recv() => match request["method"].as_str().unwrap() {
                    "thread/loaded/list" => warm.reply(&request, json!({"data":["warm-thread"],"nextCursor":null})).await,
                    "turn/start" => warm.reply(&request, json!({"turn":{"id":"warm-turn"}})).await,
                    method => panic!("unexpected ready runtime request: {method}"),
                },
            }
        }
    }).await.unwrap();
    assert_eq!(result.unwrap()["turn"]["id"], "warm-turn");
    cold.assert_idle().await;
    warm.assert_idle().await;
}

#[tokio::test]
async fn p2_skipped_inventory_preserves_persisted_cross_runtime_claim() {
    let (dir, b) = fixture().await;
    let mut cold = p2_pending_desktop(&b, "a").await;
    let mut warm = peer(&b, "b", "desktop").await;
    {
        let mut state = b.state.lock().await;
        state
            .runtimes
            .get_mut("a")
            .unwrap()
            .loaded
            .insert("shared".into());
        b.save_ownership(&mut state).unwrap();
    }
    p2_observe_status(&b, &mut cold, p2_pending_status("a")).await;
    let mut task = tokio::spawn({
        let b = b.clone();
        async move { b.refresh_loaded().await }
    });
    let result = tokio::select! {
        request = cold.recv() => {
            p2_reject_inventory(&mut cold, &request).await;
            task.await.unwrap()
        },
        request = warm.recv() => {
            assert_eq!(request["method"], "thread/loaded/list");
            warm.reply(&request, json!({"data":["shared"],"nextCursor":null})).await;
            task.await.unwrap()
        },
        result = &mut task => result.unwrap(),
    };
    result.unwrap();
    let key = ThreadKey {
        computer_id: b.config.computer_id.clone(),
        config_store_id: storage::config_store(dir.path()).unwrap(),
        native_thread_id: "shared".into(),
    };
    {
        let state = b.state.lock().await;
        assert!(state.runtimes["a"].loaded.contains("shared"));
        assert!(!state.runtimes["a"].inventory_ready);
        assert_eq!(
            b.directory(&state).unwrap().resolve_write(&key),
            Err(ResolveError::UncertainOwner)
        );
    }
    let (restored, _) = Broker::open(b.config.clone(), dir.path().into()).unwrap();
    assert_eq!(
        restored
            .directory(&*restored.state.lock().await)
            .unwrap()
            .resolve_write(&key),
        Err(ResolveError::UncertainOwner)
    );
    let send = tokio::spawn({
        let b = b.clone();
        async move {
            b.request("phone", "codex/request", json!({"commandId":"protected-send","runtimeId":"b","method":"turn/start","params":{"threadId":"shared","input":[]}})).await
        }
    });
    loaded(&mut warm, &["shared"]).await;
    assert!(finish(send).await.is_err());
    cold.assert_idle().await;
    warm.assert_idle().await;
}

#[tokio::test]
async fn p2_unproven_status_does_not_skip_failing_inventory() {
    for status in [
        json!({"runtimeId":"a","schemaPending":true,"schemaState":"initializing","nativeCapabilities":{}}),
        json!({"runtimeId":"a","initialized":true,"schemaPending":true,"schemaState":"initializing","nativeCapabilities":{}}),
        p2_pending_status("wrong-runtime"),
    ] {
        let (_dir, b) = fixture().await;
        let mut native = p2_pending_desktop(&b, "a").await;
        p2_observe_status(&b, &mut native, status).await;
        let task = tokio::spawn({
            let b = b.clone();
            async move { b.refresh_loaded().await }
        });
        let request = native.recv().await;
        p2_reject_inventory(&mut native, &request).await;
        assert!(task.await.unwrap().is_err());
        assert!(!b.state.lock().await.runtimes["a"].inventory_ready);
    }
}

#[tokio::test]
async fn p2_status_timeout_does_not_prove_uninitialized() {
    let (_dir, b) = fixture().await;
    let mut native = p2_pending_desktop(&b, "a").await;
    let task = tokio::spawn({
        let b = b.clone();
        async move { b.request("phone", "runtimes/list", json!({})).await }
    });
    assert_eq!(native.recv().await["method"], "pocket/status");
    tokio::time::timeout(Duration::from_secs(4), task)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    let inventory = tokio::spawn({
        let b = b.clone();
        async move { b.refresh_loaded().await }
    });
    let request = native.recv().await;
    p2_reject_inventory(&mut native, &request).await;
    assert!(inventory.await.unwrap().is_err());
}

#[tokio::test]
async fn p2_uninitialized_evidence_is_retired_when_same_connection_becomes_ready() {
    let (_dir, b) = fixture().await;
    let mut native = p2_pending_desktop(&b, "a").await;
    p2_observe_status(&b, &mut native, p2_pending_status("a")).await;
    let caps = crate::capabilities::Capabilities::from_schema(
        &serde_json::from_str(include_str!(
            "../../../bridge/tests/fixtures/ClientRequest.json"
        ))
        .unwrap(),
    );
    p2_observe_status(&b, &mut native, ready(&caps)).await;
    let inventory = tokio::spawn({
        let b = b.clone();
        async move { b.refresh_loaded().await }
    });
    loaded(&mut native, &["freshly-loaded"]).await;
    inventory.await.unwrap().unwrap();
    assert!(
        b.state.lock().await.runtimes["a"]
            .loaded
            .contains("freshly-loaded")
    );
}

#[tokio::test]
async fn p2_uninitialized_evidence_cannot_skip_replacement_inventory() {
    let (_dir, b) = fixture().await;
    let mut old = p2_pending_desktop(&b, "a").await;
    p2_observe_status(&b, &mut old, p2_pending_status("a")).await;
    let generation = b.state.lock().await.runtimes["a"].connection_id.clone();
    old.offline();
    let mut replacement = p2_pending_desktop(&b, "a").await;
    {
        let mut state = b.state.lock().await;
        assert!(!Broker::apply_desktop_status(
            &mut state,
            "a",
            &generation,
            &p2_pending_status("a")
        ));
    }
    let inventory = tokio::spawn({
        let b = b.clone();
        async move { b.refresh_loaded().await }
    });
    loaded(&mut replacement, &[]).await;
    inventory.await.unwrap().unwrap();
    assert!(b.state.lock().await.runtimes["a"].inventory_ready);
}

#[tokio::test]
async fn cold_desktop_becomes_sendable_without_a_desktop_turn() {
    let (_dir, b) = fixture().await;
    let mut native = peer(&b, "a", "desktop").await;
    let caps = {
        let mut state = b.state.lock().await;
        let r = state.runtimes.get_mut("a").unwrap();
        let mut caps = r.capabilities.clone();
        caps.0
            .get_mut("thread/start")
            .unwrap()
            .insert("historyMode".into());
        r.capabilities = Default::default();
        caps
    };
    let mut events = b.subscribe();
    native
        .send(json!({"method":"pocket/runtimeStatus","params":ready(&caps)}))
        .await;
    let changed = tokio::time::timeout(Duration::from_secs(2), events.recv())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(changed["method"], "runtimes/changed");
    assert!(
        changed["params"]["data"][0]["capabilities"]
            .as_array()
            .unwrap()
            .contains(&json!("thread/start"))
    );
    // This helper metadata must never enter the native conversation journal.
    assert_eq!(
        b.state.lock().await.runtimes["a"].journal.cursor().sequence,
        0
    );

    let task = tokio::spawn({
        let b = b.clone();
        async move {
            b.request("phone", "codex/request", json!({"commandId":"cold-create","runtimeId":"a","method":"thread/start","params":{}})).await
        }
    });
    loaded(&mut native, &[]).await;
    let start = native.recv().await;
    assert_eq!(start["method"], "thread/start");
    assert_eq!(start["params"]["historyMode"], "legacy");
    native
        .reply(&start, json!({"thread":{"id":"fresh","turns":[]}}))
        .await;
    assert_eq!(finish(task).await.unwrap()["thread"]["id"], "fresh");
    let access = b
        .request(
            "phone",
            "thread/access/read",
            json!({"runtimeId":"a","threadId":"fresh"}),
        )
        .await
        .unwrap();
    assert_eq!(access["canSend"], true);

    // The first read still receives the real legacy unmaterialized error.
    for method in ["thread/read", "thread/turns/list"] {
        let params = if method == "thread/read" {
            json!({"threadId":"fresh","includeTurns":true})
        } else {
            json!({"threadId":"fresh","limit":8,"sortDirection":"desc","itemsView":"full"})
        };
        let task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request(
                    "phone",
                    "codex/request",
                    json!({"runtimeId":"a","method":method,"params":params}),
                )
                .await
            }
        });
        let read = native.recv().await;
        let operation = if method == "thread/read" {
            "includeTurns"
        } else {
            method
        };
        native.send(json!({"id":read["id"],"error":{"code":-32600,"message":format!("thread fresh is not materialized yet; {operation} is unavailable before first user message")}})).await;
        let metadata = native.recv().await;
        assert_eq!(
            metadata["params"],
            json!({"threadId":"fresh","includeTurns":false})
        );
        native.reply(&metadata, json!({"thread":{"id":"fresh","status":{"type":"idle"},"historyMode":"legacy","preview":"","turns":[]}})).await;
        let result = finish(task).await.unwrap();
        assert_eq!(result["pocketHistoryState"], "awaiting_first_message");
        assert_eq!(result["pocketCursor"]["sequence"], 0);
    }
    let task = tokio::spawn({
        let b = b.clone();
        async move {
            b.request("phone", "codex/request", json!({"commandId":"phone-first-turn","runtimeId":"a","method":"turn/start","params":{"threadId":"fresh","input":[{"type":"text","text":"synthetic phone message"}]}})).await
        }
    });
    loaded(&mut native, &["fresh"]).await;
    let turn = native.recv().await;
    assert_eq!(turn["method"], "turn/start");
    native
        .reply(&turn, json!({"turn":{"id":"fake-turn","items":[]}}))
        .await;
    finish(task).await.unwrap();
    native.assert_idle().await;
}

#[tokio::test]
async fn runtime_list_recovers_capabilities_when_ready_event_precedes_registration() {
    let (_dir, b) = fixture().await;
    let mut native = peer(&b, "a", "desktop").await;
    let caps = {
        let mut state = b.state.lock().await;
        let r = state.runtimes.get_mut("a").unwrap();
        std::mem::take(&mut r.capabilities)
    };
    let task = tokio::spawn({
        let b = b.clone();
        async move { b.request("phone", "runtimes/list", json!({})).await }
    });
    let status = native.recv().await;
    assert_eq!(status["method"], "pocket/status");
    native.reply(&status, ready(&caps)).await;
    let list = finish(task).await.unwrap();
    assert!(
        list["data"][0]["capabilities"]
            .as_array()
            .unwrap()
            .contains(&json!("thread/start"))
    );
    native.assert_idle().await;
}

#[tokio::test]
async fn uninitialized_status_does_not_publish_native_capabilities() {
    let (_dir, b) = fixture().await;
    let mut native = peer(&b, "a", "desktop").await;
    let caps = {
        let mut state = b.state.lock().await;
        std::mem::take(&mut state.runtimes.get_mut("a").unwrap().capabilities)
    };
    let task = tokio::spawn({
        let b = b.clone();
        async move { b.request("phone", "runtimes/list", json!({})).await }
    });
    let status = native.recv().await;
    let mut pending = ready(&caps);
    pending["initialized"] = json!(false);
    native.reply(&status, pending).await;
    let list = finish(task).await.unwrap();
    assert!(
        !list["data"][0]["capabilities"]
            .as_array()
            .unwrap()
            .contains(&json!("thread/start"))
    );
    native.assert_idle().await;
}

#[tokio::test]
async fn late_initializing_snapshot_cannot_erase_ready_notification() {
    let (_dir, b) = fixture().await;
    let mut native = peer(&b, "a", "desktop").await;
    let caps = {
        let mut state = b.state.lock().await;
        std::mem::take(&mut state.runtimes.get_mut("a").unwrap().capabilities)
    };
    let task = tokio::spawn({
        let b = b.clone();
        async move { b.request("phone", "runtimes/list", json!({})).await }
    });
    let status = native.recv().await;
    let mut events = b.subscribe();
    native
        .send(json!({"method":"pocket/runtimeStatus","params":ready(&caps)}))
        .await;
    assert_eq!(events.recv().await.unwrap()["method"], "runtimes/changed");
    native.reply(&status, json!({"runtimeId":"a","initialized":false,"schemaState":"initializing","nativeCapabilities":{}})).await;
    let list = finish(task).await.unwrap();
    assert!(
        list["data"][0]["capabilities"]
            .as_array()
            .unwrap()
            .contains(&json!("thread/start"))
    );
    native.assert_idle().await;
}

#[tokio::test]
async fn creation_waits_for_initialization_instead_of_falling_back_to_paginated_history() {
    let (_dir, b) = fixture().await;
    let mut native = peer(&b, "a", "desktop").await;
    b.state
        .lock()
        .await
        .runtimes
        .get_mut("a")
        .unwrap()
        .capabilities = Default::default();
    let task = tokio::spawn({
        let b = b.clone();
        async move {
            b.request("phone", "codex/request", json!({"commandId":"early-create","runtimeId":"a","method":"thread/start","params":{}})).await
        }
    });
    let status = native.recv().await;
    native.reply(&status, json!({"runtimeId":"a","initialized":false,"schemaPending":true,"schemaState":"initializing","nativeCapabilities":{}})).await;
    // No business RPC is safe before the desktop handshake, including inventory.
    let result = tokio::time::timeout(Duration::from_secs(2), task)
        .await
        .expect("not-ready creation must reject before dispatching thread/start")
        .unwrap();
    assert_eq!(
        crate::error::code(&result.unwrap_err()),
        Code::RuntimeUnavailable
    );
    native.assert_idle().await;
}

#[tokio::test]
async fn capability_refresh_cannot_modify_a_replacement_connection() {
    let (_dir, b) = fixture().await;
    let mut old = peer(&b, "a", "desktop").await;
    let caps = {
        let mut state = b.state.lock().await;
        std::mem::take(&mut state.runtimes.get_mut("a").unwrap().capabilities)
    };
    let task = tokio::spawn({
        let b = b.clone();
        async move { b.request("phone", "runtimes/list", json!({})).await }
    });
    let status = old.recv().await;
    old.offline();
    let mut replacement = peer(&b, "a", "desktop").await;
    b.state
        .lock()
        .await
        .runtimes
        .get_mut("a")
        .unwrap()
        .capabilities = Default::default();
    old.reply(&status, ready(&caps)).await;
    let list = finish(task).await.unwrap();
    assert!(
        !list["data"][0]["capabilities"]
            .as_array()
            .unwrap()
            .contains(&json!("thread/start"))
    );
    replacement.assert_idle().await;
}

#[tokio::test]
async fn phone_resume_uses_metadata_only_when_local_schema_supports_it() {
    for supports_exclude in [false, true] {
        let (_dir, b) = fixture().await;
        let mut native = peer(&b, "a", "desktop").await;
        if !supports_exclude {
            b.state
                .lock()
                .await
                .runtimes
                .get_mut("a")
                .unwrap()
                .capabilities
                .0
                .get_mut("thread/resume")
                .unwrap()
                .remove("excludeTurns");
        }
        let task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request("phone", "codex/request", json!({"commandId":"phone-resume","runtimeId":"a","method":"thread/resume","params":{"threadId":"stored"}})).await
            }
        });
        loaded(&mut native, &[]).await;
        let proof = native.recv().await;
        assert_eq!(proof["method"], "thread/read");
        assert_eq!(
            proof["params"],
            json!({"threadId":"stored","includeTurns":false})
        );
        native
            .reply(&proof, json!({"thread":{"id":"stored"}}))
            .await;
        loaded(&mut native, &[]).await;
        let resume = native.recv().await;
        assert_eq!(resume["method"], "thread/resume");
        assert_eq!(
            resume["params"]["excludeTurns"],
            if supports_exclude {
                json!(true)
            } else {
                Value::Null
            }
        );
        native
            .reply(&resume, json!({"thread":{"id":"stored"}}))
            .await;
        finish(task).await.unwrap();
        native.assert_idle().await;
    }
}
