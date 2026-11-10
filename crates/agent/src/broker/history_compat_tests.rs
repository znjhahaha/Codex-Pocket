use super::*;
use super::tests::{fixture, peer, loaded, finish};

#[tokio::test]
async fn phone_creation_pins_supported_legacy_history_without_changing_old_runtime() {
    for supports_history_mode in [false, true] {
        let (_dir, b) = fixture().await;
        let mut native = peer(&b, "a", "desktop").await;
        {
            let mut state=b.state.lock().await;
            let fields=state.runtimes.get_mut("a").unwrap().capabilities.0.entry("thread/start".into()).or_default();
            if supports_history_mode {fields.insert("historyMode".into());} else {fields.remove("historyMode");}
        }
        let task = tokio::spawn({let b=b.clone(); async move {
            b.request("phone", "codex/request", json!({"commandId":"create","runtimeId":"a","method":"thread/start","params":{}})).await
        }});
        loaded(&mut native, &[]).await;
        let request = native.recv().await;
        native.reply(&request,json!({"thread":{"id":"new","turns":[]}})).await;
        assert_eq!(finish(task).await.unwrap()["thread"]["id"],"new");
        assert_eq!(request["params"]["historyMode"], if supports_history_mode {json!("legacy")} else {Value::Null});
        native.assert_idle().await;
    }
}

#[tokio::test]
async fn empty_history_requires_native_unmaterialized_error_and_matching_metadata() {
    for (method, mismatch) in [("thread/read",false),("thread/turns/list",false),("thread/read",true)] {
        let (_dir,b)=fixture().await;
        let mut native=peer(&b,"a","desktop").await;
        let params=if method=="thread/read" {json!({"threadId":"fresh","includeTurns":true})}
            else {json!({"threadId":"fresh","limit":8,"sortDirection":"desc","itemsView":"full"})};
        let task=tokio::spawn({let b=b.clone();async move {
            b.request("phone","codex/request",json!({"runtimeId":"a","method":method,"params":params})).await
        }});
        let read=native.recv().await;
        let operation=if method=="thread/read" {"includeTurns"} else {"thread/turns/list"};
        native.send(json!({"id":read["id"],"error":{"code":-32600,"message":format!("thread fresh is not materialized yet; {operation} is unavailable before first user message")}})).await;
        let metadata=native.recv().await;
        assert_eq!(metadata["method"],"thread/read");
        assert_eq!(metadata["params"],json!({"threadId":"fresh","includeTurns":false}));
        // A concurrent event must not be hidden behind the later metadata cursor.
        let mut events=b.subscribe();
        native.send(json!({"method":"thread/tokenUsage/updated","params":{"threadId":"fresh","tokenUsage":{"total":{"totalTokens":1}}}})).await;
        events.recv().await.unwrap();
        native.reply(&metadata,json!({"thread":{"id":if mismatch {"other"} else {"fresh"},"status":{"type":"idle"},"historyMode":"legacy","preview":"","turns":[]}})).await;
        let result=finish(task).await;
        if mismatch {assert!(result.is_err());} else {
            let result=result.unwrap();
            assert_eq!(result["pocketHistoryState"],"awaiting_first_message");
            assert!(result["pocketCursor"].is_object());
            let sync=b.request("phone","sync",json!({})).await.unwrap();
            assert!(result["pocketCursor"]["sequence"].as_u64().unwrap()<sync["cursors"]["a"]["sequence"].as_u64().unwrap());
            if method=="thread/read" {assert_eq!(result["thread"]["turns"],json!([]));}
            else {assert_eq!(result["data"],json!([]));assert!(result["nextCursor"].is_null());}
        }
        native.assert_idle().await;
    }
}

#[tokio::test]
async fn unsupported_paginated_history_is_not_fabricated_as_empty() {
    let (_dir,b)=fixture().await;
    let mut native=peer(&b,"a","desktop").await;
    let task=tokio::spawn({let b=b.clone();async move {
        b.request("phone","codex/request",json!({"runtimeId":"a","method":"thread/read","params":{"threadId":"existing","includeTurns":true}})).await
    }});
    let read=native.recv().await;
    native.send(json!({"id":read["id"],"error":{"code":-32601,"message":"list_turns is not supported yet"}})).await;
    let result=finish(task).await;
    assert_eq!(crate::session::reply(json!("x"),result)["error"]["code"],"native_history_unavailable");
    native.assert_idle().await;
}
