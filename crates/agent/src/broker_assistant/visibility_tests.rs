use super::*;
use crate::broker::tests::{fixture, loaded, peer};

const COORDINATOR: &str = "01a1ddbc-a4db-7090-831c-05ff76f6cef9";

fn saved_identity(b: &Broker) {
    let root = b.state_dir.join("assistant");
    std::fs::create_dir_all(&root).unwrap();
    storage::atomic_write(&root.join("profile.json"), &serde_json::to_vec(&Profile {
        schema: 1, config_home: b.state_dir.clone(),
        native_binary: root.join("unavailable.exe"), source_runtime_id: "old-host".into(),
    }).unwrap()).unwrap();
    let db = rusqlite::Connection::open(root.join("executor.db")).unwrap();
    db.execute_batch("CREATE TABLE assistant_executor_state(singleton INTEGER PRIMARY KEY, data TEXT NOT NULL)").unwrap();
    db.execute("INSERT INTO assistant_executor_state VALUES(1,?1)", [json!({
        "runtime_id":"old-host",
        "operations":{
            "completed":{"role":"Coordinator","thread_id":COORDINATOR,"mapping":{"thread_id":COORDINATOR}},
            "partial":{"role":"Coordinator","thread_id":"partial-coordinator","mapping":null},
            "old":{"role":"Coordinator","thread_id":null,"mapping":{"thread_id":"old-coordinator"}},
            "worker":{"role":"Execution","thread_id":"execution","mapping":{"thread_id":"execution"}}
        },
        "threads":{ "retained-coordinator":{"role":"Coordinator"}, "execution":{"role":"Execution"} }
    }).to_string()]).unwrap();
}

fn approval(thread: &str, id: &str) -> Value {
    json!({"id":id,"method":"item/tool/requestUserInput","params":{
        "threadId":thread,"turnId":"turn","itemId":"item","questions":[{"id":"q"}]
    }})
}

#[tokio::test]
async fn coordinator_visibility_directory_filters_roles_and_preserves_native_cursor() {
    let (_dir, b) = fixture().await;
    saved_identity(&b);
    let mut native = peer(&b, "ordinary-cli", "cli").await;
    let params = json!({"archived":true});
    let listing = b.threads(&params);
    let serve = async {
        loaded(&mut native, &[COORDINATOR, "execution", "unnamed"]).await;
        let req = native.recv().await;
        assert_eq!(req["method"], "thread/list");
        assert_eq!(req["params"]["archived"], true);
        native.reply(&req, json!({"data":[
            {"id":COORDINATOR,"name":null,"preview":"User handoffs: private JSON"},
            {"id":"old-coordinator"},{"id":"partial-coordinator"},{"id":"retained-coordinator"},
            {"id":"execution","name":null}, {"id":"unnamed","name":null,"preview":"User handoffs: ordinary text"}
        ],"nextCursor":"native-page-2"})).await;
    };
    let (result, _) = tokio::join!(listing, serve);
    let result = result.unwrap();
    assert_eq!(result["data"].as_array().unwrap().iter().map(|r|r["id"].as_str().unwrap()).collect::<Vec<_>>(), vec!["execution", "unnamed"]);
    let store = storage::config_store(&b.state_dir).unwrap();
    assert_eq!(result["nextCursor"][&store], "native-page-2");
    assert_eq!(result["assistantInternalThreads"]["authoritative"], true);
    assert_eq!(result["hiddenThreadsAuthoritative"], true);
    assert!(result["hiddenThreads"].as_array().unwrap().iter().any(|v| v["runtimeId"] == "ordinary-cli"
        && v["configStoreId"] == store && v["threadId"] == COORDINATOR && v["role"] == "assistant_coordinator"));
    assert_eq!(result["assistantInternalThreads"]["bindings"][0]["configStoreId"], store);
    assert!(result["assistantInternalThreads"]["bindings"][0]["threadIds"].as_array().unwrap().contains(&json!(COORDINATOR)));
    assert!(b.assistant.lock().await.is_none());
}

#[tokio::test]
async fn coordinator_visibility_metadata_binds_cached_ids_to_store_and_runtime() {
    let (_dir, b) = fixture().await;
    saved_identity(&b);
    let _native = peer(&b, "ordinary-cli", "cli").await;
    let _foreign = peer(&b, "foreign-cli", "cli").await;
    b.state.lock().await.runtimes.get_mut("foreign-cli").unwrap().store = "foreign-store".into();
    let hello = b.hello().await;
    let rows = hello["runtimes"].as_array().unwrap();
    let own = rows.iter().find(|v| v["id"] == "ordinary-cli").unwrap();
    let foreign = rows.iter().find(|v| v["id"] == "foreign-cli").unwrap();
    assert_eq!(own["assistantInternalThreads"]["authoritative"], true);
    let binding = &own["assistantInternalThreads"]["bindings"][0];
    assert_eq!(binding["role"], "Coordinator");
    assert_eq!(binding["configStoreId"], own["configStoreId"]);
    assert!(binding["runtimeIds"].as_array().unwrap().contains(&json!("old-host")));
    assert!(binding["runtimeIds"].as_array().unwrap().contains(&json!("ordinary-cli")));
    assert!(!binding["runtimeIds"].as_array().unwrap().contains(&json!("foreign-cli")));
    assert!(!binding["threadIds"].as_array().unwrap().contains(&json!("execution")));
    assert_eq!(foreign["assistantInternalThreads"]["bindings"], json!([]));
    assert_eq!(foreign["hiddenThreads"], json!([]));
    assert!(own["hiddenThreads"].as_array().unwrap().iter().any(|v| v["runtimeId"] == "old-host" && v["threadId"] == COORDINATOR));
    let read = b.assistant_read(&json!({})).await.unwrap();
    assert_eq!(read["hiddenThreadsAuthoritative"], true);
    assert!(read["hiddenThreads"].as_array().unwrap().iter().any(|v| v["configStoreId"] == own["configStoreId"] && v["threadId"] == COORDINATOR));
    let path = b.state_dir.join("assistant/executor.db");
    let db = rusqlite::Connection::open(path).unwrap();
    db.execute("UPDATE assistant_executor_state SET data='invalid'", []).unwrap();
    let hello = b.hello().await;
    assert!(hello["runtimes"].as_array().unwrap().iter().all(|v| v["assistantInternalThreads"]["authoritative"] == false));
}

#[tokio::test]
async fn coordinator_visibility_sync_hides_internal_pending_and_replay_without_erasing_evidence() {
    let (_dir, b) = fixture().await;
    saved_identity(&b);
    let _native = peer(&b, "ordinary-cli", "cli").await;
    let cursor;
    {
        let mut s = b.state.lock().await;
        cursor = cursor_value(&s.runtimes["ordinary-cli"].journal.cursor());
        for thread in [COORDINATOR, "execution"] {
            let event = approval(thread, thread);
            s.runtimes.get_mut("ordinary-cli").unwrap().journal.append(JournalEvent {
                native_thread_id:Some(thread.into()), native_turn_id:None, native_item_id:None, payload:event.clone()
            }).unwrap();
            s.pending.insert(thread.into(), Pending {runtime:"ordinary-cli".into(), native_id:json!(thread), event,
                command:None,response:None,winner_received:false});
        }
    }
    let sync = b.sync(&json!({"cursors":{"ordinary-cli":cursor}})).await.unwrap();
    assert_eq!(sync["pendingRequests"].as_array().unwrap().len(), 1);
    assert_eq!(sync["events"].as_array().unwrap().len(), 1);
    assert_eq!(sync["events"][0]["event"]["params"]["threadId"], "execution");
    assert_eq!(sync["cursors"]["ordinary-cli"]["sequence"].as_u64(), cursor["sequence"].as_u64().map(|n|n+2));
    assert_eq!(b.state.lock().await.pending.len(), 2);
}

#[tokio::test]
async fn coordinator_visibility_manual_resume_and_rename_are_rejected_on_same_store_cli() {
    let (_dir, b) = fixture().await;
    saved_identity(&b);
    let mut native = peer(&b, "ordinary-cli", "cli").await;
    for method in ["thread/resume", "thread/name/set", "turn/start", "thread/fork"] {
        assert!(b.assistant_before_manual_write("ordinary-cli",method,&json!({"threadId":COORDINATOR}),"manual").await.is_err(), "{method}");
    }
    native.assert_idle().await;
}

#[tokio::test]
async fn coordinator_visibility_blocks_direct_native_and_product_access_before_io() {
    let (_dir, b) = fixture().await;
    saved_identity(&b);
    let mut native = peer(&b, "ordinary-cli", "cli").await;
    for method in ["thread/read", "thread/resume", "thread/name/set", "thread/fork", "turn/start", "thread/archive", "thread/queue/add"] {
        let error = b.request("phone", "codex/request", json!({"runtimeId":"ordinary-cli","commandId":method,"method":method,"params":{"threadId":COORDINATOR}})).await.unwrap_err();
        assert!(error.to_string().contains("internal coordinator"), "{method}: {error}");
    }
    for method in ["thread/access/read", "thread/access/update", "thread/access/cancel", "thread/permissions/update", "thread/edit/prepare", "thread/edit/revert", "thread/context/read", "thread/media/read", "workspace/read"] {
        let error = b.request("phone", method, json!({"runtimeId":"ordinary-cli","threadId":COORDINATOR,"commandId":method})).await.unwrap_err();
        assert!(error.to_string().contains("internal coordinator"), "{method}: {error}");
    }
    native.assert_idle().await;
}

#[tokio::test]
async fn coordinator_visibility_is_scoped_to_store_and_retains_execution_takeover_policy() {
    let (_dir, b) = fixture().await;
    saved_identity(&b);
    let home = tempfile::tempdir().unwrap();
    let (connected, mut native) = crate::native::test_peer::connect("other-account","cli",home.path(),b.native_events.clone()).await;
    b.add_runtime(connected).await.unwrap();
    b.reject_coordinator_access("other-account",Some(COORDINATOR)).await.unwrap();
    b.assistant_before_manual_write("other-account","turn/start",&json!({"threadId":COORDINATOR}),"manual").await.unwrap();
    let snapshot = json!({"enabled":true,"runtimeId":"host","operations":[{"role":"Execution","mapping":{"thread_id":"execution"}}]});
    assert_eq!(manual_write_policy(true,&snapshot,"host","turn/start",&json!({"threadId":"execution"})),ManualWrite::Takeover);
    native.assert_idle().await;
}

#[tokio::test]
async fn coordinator_visibility_filters_raw_lists_and_keeps_empty_page_cursor() {
    let (_dir, b) = fixture().await;
    saved_identity(&b);
    let mut native = peer(&b,"ordinary-cli","cli").await;
    for (method, rows) in [("thread/list",json!([{"id":COORDINATOR}])), ("thread/loaded/list",json!([COORDINATOR,"execution","unnamed"]))] {
        let params = json!({"runtimeId":"ordinary-cli","method":method,"params":{}});
        let serve = async {
            let request = native.recv().await;
            assert_eq!(request["method"],method);
            native.reply(&request,json!({"data":rows,"nextCursor":"next-native-page"})).await;
        };
        let (response,_) = tokio::join!(b.native_request("phone",&params),serve);
        let response = response.unwrap();
        assert_eq!(response["nextCursor"],"next-native-page");
        if method=="thread/list" { assert_eq!(response["data"],json!([])); }
        else { assert_eq!(response["data"],json!(["execution","unnamed"])); }
    }
}

#[tokio::test]
async fn coordinator_visibility_live_events_and_loaded_inventory_keep_internal_journal_only() {
    let (_dir, b) = fixture().await;
    saved_identity(&b);
    let mut native = peer(&b,"ordinary-cli","cli").await;
    let before = b.sync(&json!({})).await.unwrap();
    let mut notifications = b.subscribe();
    native.send(json!({"method":"thread/started","params":{"thread":{"id":COORDINATOR,"preview":"private"}}})).await;
    native.send(approval(COORDINATOR,"internal-request")).await;
    native.send(json!({"method":"serverRequest/resolved","params":{"requestId":"internal-request"}})).await;
    native.send(approval("execution","execution-request")).await;
    let event = tokio::time::timeout(Duration::from_secs(2),notifications.recv()).await.unwrap().unwrap();
    assert_eq!(event["params"]["event"]["params"]["threadId"],"execution");
    let snapshot = b.sync(&json!({"cursors":before["cursors"]})).await.unwrap();
    assert_eq!(snapshot["events"].as_array().unwrap().len(),1);
    assert_eq!(snapshot["pendingRequests"].as_array().unwrap().len(),1);
    let state = b.state.lock().await;
    assert!(state.runtimes["ordinary-cli"].loaded.contains(COORDINATOR));
    assert_eq!(b.public_runtime_values(&state)[0]["loadedThreadIds"],json!([]));
    assert_eq!(state.runtimes["ordinary-cli"].journal.cursor().sequence,4);
}

#[tokio::test]
async fn coordinator_visibility_old_pending_cannot_be_answered_but_execution_can() {
    let (_dir, b) = fixture().await;
    saved_identity(&b);
    let mut native = peer(&b,"ordinary-cli","cli").await;
    {
        let mut s = b.state.lock().await;
        for thread in [COORDINATOR,"execution"] {
            s.pending.insert(thread.into(),Pending { runtime:"ordinary-cli".into(),native_id:json!(thread),
                event:approval(thread,thread),command:None,response:None,winner_received:false });
        }
    }
    let response = json!({"answers":{"q":{"answers":["chosen"]}}});
    let key = CommandKey {caller_id:"phone".into(),command_id:"answer".into()};
    let error = b.approve(&json!({"runtimeId":"ordinary-cli","requestId":COORDINATOR,"response":response}),&key).await.unwrap_err();
    assert!(error.to_string().contains("Assistant page"));
    assert!(b.state.lock().await.pending[COORDINATOR].command.is_none());
    native.assert_idle().await;
    b.approve(&json!({"runtimeId":"ordinary-cli","requestId":"execution","response":response}),&key).await.unwrap();
    assert_eq!(native.recv().await["id"],"execution");
}

#[tokio::test]
async fn coordinator_visibility_question_receipts_remain_private_after_runtime_detaches() {
    let (_dir,b) = fixture().await;
    saved_identity(&b);
    {
        let mut state = b.state.lock().await;
        for thread in [COORDINATOR,"execution"] {
            let key=CommandKey {caller_id:"phone".into(),command_id:thread.into()};
            state.questions.reserve(key,"detached",&storage::config_store(&b.state_dir).unwrap(),&approval(thread,thread),&json!({"answers":{"q":{"answers":["chosen"]}}})).unwrap();
        }
    }
    let data=b.request("phone","questions/list",json!({})).await.unwrap();
    assert_eq!(data["data"].as_array().unwrap().len(),1);
    assert_eq!(data["data"][0]["threadId"],"execution");
    assert_eq!(b.state.lock().await.questions.list("phone",None).len(),2);
}

#[tokio::test]
async fn coordinator_visibility_read_is_nonmutating_and_corruption_does_not_unhide_threads() {
    let (_dir,b) = fixture().await;
    saved_identity(&b);
    let mut native=peer(&b,"ordinary-cli","cli").await;
    let path=b.state_dir.join("assistant/executor.db");
    let before=std::fs::read(&path).unwrap();
    assert_eq!(crate::assistant_product::coordinator_threads(&b.state_dir.join("assistant")).unwrap().len(),4);
    assert_eq!(std::fs::read(&path).unwrap(),before);
    let db=rusqlite::Connection::open(&path).unwrap();
    db.execute("UPDATE assistant_executor_state SET data='invalid json'",[]).unwrap();
    assert!(b.sync(&json!({})).await.is_err());
    assert!(b.reject_coordinator_access("ordinary-cli",Some(COORDINATOR)).await.is_err());
    native.assert_idle().await;
}
