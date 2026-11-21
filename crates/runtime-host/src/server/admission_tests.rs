use super::capacity_tests::{empty_store, submit_request};
use super::*;

fn start(id: &str, root: &std::path::Path, writes: bool) -> Value {
    json!({"id":id,"method":"thread/start","params":{"cwd":root,"runtimeWorkspaceRoots":[root],
        "sandbox":if writes{"workspace-write"}else{"read-only"},"approvalPolicy":"on-request","approvalsReviewer":"user"}})
}
fn turn(id: &str, thread: &str, root: &std::path::Path, writes: bool) -> Value {
    let sandbox = if writes {
        json!({"type":"workspaceWrite","writableRoots":[root],"networkAccess":false,"excludeSlashTmp":true,"excludeTmpdirEnvVar":true})
    } else {
        json!({"type":"readOnly","networkAccess":false})
    };
    json!({"id":id,"method":"turn/start","params":{"threadId":thread,"cwd":root,"runtimeWorkspaceRoots":[root],
        "sandboxPolicy":sandbox,"approvalPolicy":"on-request","approvalsReviewer":"user","input":[]}})
}
async fn register(store: &mut Store, id: &str, thread: &str, root: &std::path::Path, writes: bool) {
    let (reply, frame) = submit_request(store, start(id, root, writes)).await;
    assert!(frame.is_some(), "{reply}");
    store.observe_native(json!({"id":id,"result":{"thread":{"id":thread},"cwd":root,"approvalPolicy":"on-request","approvalsReviewer":"user",
        "sandbox":{"type":if writes{"workspaceWrite"}else{"readOnly"}}}})).unwrap();
}

#[tokio::test]
async fn admission_legacy_unknown_does_not_block_fresh_readonly_conversation_or_replay_unknown() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = empty_store(&dir);
    store
        .state
        .reserve(
            "s:legacy".into(),
            &json!({"id":"legacy","method":"unknown-method","params":{}}),
            false,
        )
        .unwrap();
    store.save().unwrap();
    let old = store.state.commands["s:legacy"].generation.clone();
    drop(store);
    let mut store = empty_store(&dir);
    let next = uuid::Uuid::new_v4().to_string();
    store.begin_generation(&next).unwrap();
    assert!(store.state.reconciliation_required);
    register(&mut store, "new-thread", "fresh", dir.path(), false).await;
    let (reply, frame) =
        submit_request(&mut store, turn("new-turn", "fresh", dir.path(), false)).await;
    assert!(frame.is_some(), "{reply}");
    let (_, frame) = submit_request(
        &mut store,
        json!({"id":"legacy","method":"thread/start","params":{}}),
    )
    .await;
    assert!(frame.is_none());
    assert_eq!(store.state.commands["s:legacy"].generation, old);
    assert!(!store.state.commands["s:legacy"].completed);
    let (reply, frame) = submit_request(&mut store, start("writer", dir.path(), true)).await;
    assert!(frame.is_none());
    assert_eq!(reply["error"]["notDispatched"], true);
}

#[tokio::test]
async fn admission_scope_blocks_same_thread_owner_substitution_and_write_escalation() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = empty_store(&dir);
    register(&mut store, "create", "owned", dir.path(), false).await;
    store.state.reconciliation_required = true;
    for request in [
        turn("foreign", "foreign-thread", dir.path(), false),
        turn("escalate", "owned", dir.path(), true),
        json!({"id":"missing","method":"turn/start","params":{}}),
    ] {
        let (reply, frame) = submit_request(&mut store, request).await;
        assert!(frame.is_none());
        assert_eq!(reply["error"]["notDispatched"], true);
    }
    let (reply, frame) =
        submit_request(&mut store, turn("first", "owned", dir.path(), false)).await;
    assert!(frame.is_some(), "{reply}");
    let (reply, frame) =
        submit_request(&mut store, turn("second", "owned", dir.path(), false)).await;
    assert!(frame.is_none());
    assert_eq!(reply["error"]["notDispatched"], true);
}

#[tokio::test]
async fn admission_confirmed_scopes_allow_disjoint_writer_and_preserve_original_reservation() {
    let dir = tempfile::tempdir().unwrap();
    let a = dir.path().join("a");
    let b = dir.path().join("b");
    std::fs::create_dir(&a).unwrap();
    std::fs::create_dir(&b).unwrap();
    let mut store = empty_store(&dir);
    register(&mut store, "a-start", "thread-a", &a, true).await;
    let (_, frame) = submit_request(&mut store, turn("a-unknown", "thread-a", &a, true)).await;
    assert!(frame.is_some());
    store.state.reconciliation_required = true;
    let (_, frame) = submit_request(&mut store, start("same-writer", &a, true)).await;
    assert!(frame.is_none());
    let (_, frame) = submit_request(&mut store, start("parent-writer", dir.path(), true)).await;
    assert!(frame.is_none());
    register(&mut store, "b-start", "thread-b", &b, true).await;
    let (reply, frame) = submit_request(&mut store, turn("b-turn", "thread-b", &b, true)).await;
    assert!(frame.is_some(), "{reply}");
    assert!(!store.state.commands["s:a-unknown"].completed);
    let (_, frame) = submit_request(&mut store, turn("a-unknown", "thread-a", &a, true)).await;
    assert!(frame.is_none());
}

#[tokio::test]
async fn admission_only_native_create_receipt_registers_thread_and_reboot_forgets_scopes() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = empty_store(&dir);
    store.state.reconciliation_required = true;
    store
        .observe_native(json!({"id":"unsolicited","result":{"thread":{"id":"foreign"}}}))
        .unwrap();
    assert!(!store.admits_independent(&turn("foreign", "foreign", dir.path(), false)));
    register(&mut store, "create", "own", dir.path(), false).await;
    store.save().unwrap();
    drop(store);
    let mut cold = empty_store(&dir);
    cold.begin_generation(&uuid::Uuid::new_v4().to_string())
        .unwrap();
    assert!(!cold.admits_independent(&turn("cold", "own", dir.path(), false)));
    assert_eq!(cold.state.commands.len(), 1);
}

#[tokio::test]
async fn admission_permission_approval_invalidates_old_sandbox_scope_without_touching_receipts() {
    let dir = tempfile::tempdir().unwrap();
    let a = dir.path().join("a");
    let b = dir.path().join("b");
    std::fs::create_dir(&a).unwrap();
    std::fs::create_dir(&b).unwrap();
    let mut store = empty_store(&dir);
    register(&mut store, "create", "reader", &a, false).await;
    submit_request(&mut store, turn("running", "reader", &a, false)).await;
    store.observe_native(json!({"id":77,"method":"item/permissions/requestApproval","params":{"threadId":"reader","permissions":{"fileSystem":{}}}})).unwrap();
    store.state.reconciliation_required = true;
    assert!(!store.admits_independent(&start("other-writer", &b, true)));
    store.scope_before_answer(
        "n:77",
        &json!({"result":{"permissions":{"fileSystem":{"write":[b]}}}}),
    );
    assert!(!store.threads.contains_key("reader"));
    assert!(!store.state.commands["s:running"].completed);
    assert!(store.state.commands["s:running"].scope.is_none());
    assert!(store.admits_independent(&start("other-reader", &b, false)));
}

#[tokio::test]
async fn admission_turn_receipt_reserves_run_before_notification_and_does_not_infer_idle() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = empty_store(&dir);
    register(&mut store, "start", "thread", dir.path(), true).await;
    submit_request(&mut store, turn("running", "thread", dir.path(), true)).await;
    store
        .observe_native(
            json!({"id":"running","result":{"turn":{"id":"turn","status":"inProgress"}}}),
        )
        .unwrap();
    store.state.reconciliation_required = true;
    assert!(!store.admits_independent(&start("competing-writer", dir.path(), true)));
    assert!(!store.admits_independent(&turn("same-thread", "thread", dir.path(), true)));
    assert!(store.admits_independent(&json!({"id":"stop","method":"turn/interrupt","params":{"threadId":"thread","turnId":"turn"}})));
    store.observe_native(json!({"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"turn","status":"completed"}}})).unwrap();
    assert!(store.admits_independent(&turn("after-stop", "thread", dir.path(), true)));
}

#[tokio::test]
async fn admission_turn_receipt_recycles_only_terminal_run_capacity_before_notification() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = empty_store(&dir);
    register(&mut store, "start", "thread", dir.path(), true).await;
    for n in 0..256 {
        store.observe_native(json!({"method":"turn/completed","params":{"threadId":"thread","turn":{"id":format!("old-{n}"),"status":"completed"}}})).unwrap();
    }
    submit_request(&mut store, turn("running", "thread", dir.path(), true)).await;
    store
        .observe_native(
            json!({"id":"running","result":{"turn":{"id":"next","status":"inProgress"}}}),
        )
        .unwrap();
    store
        .save()
        .expect("A start reply before its notification must obey the existing run budget");
    assert_eq!(store.state.runs.len(), 256);
    assert_eq!(store.state.active_count(), 1);
}

#[cfg(windows)]
fn predecessor(store: &Store, root: &std::path::Path) -> Descriptor {
    let live = pocket_core::native_process::NativeProcess::capture(std::process::id()).unwrap();
    // PID reuse proves the recorded creation identity ended, not that the
    // currently running process with that number should be stopped.
    let ended = pocket_core::native_process::NativeProcess {
        created_at: live.created_at - 1,
        ..live
    };
    Descriptor {
        protocol: PROTOCOL,
        runtime_id: store.state.runtime_id.clone(),
        generation: store.state.generation.clone(),
        pid: ended.pid,
        native_pid: ended.pid,
        host_process: Some(ended.clone()),
        native_process: Some(ended),
        port: 1,
        token: "fixture-only".into(),
        config_home: root.into(),
        source: "cli".into(),
    }
}

#[cfg(windows)]
#[test]
fn admission_resource_release_requires_matching_owner_and_both_creation_identities_ended() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = empty_store(&dir);
    let original = predecessor(&store, dir.path());
    let live = pocket_core::native_process::NativeProcess::capture(std::process::id()).unwrap();
    let mut candidates = Vec::new();
    let mut d = original.clone();
    d.runtime_id = "replaced-owner".into();
    candidates.push(d);
    let mut d = original.clone();
    d.generation = uuid::Uuid::new_v4().to_string();
    candidates.push(d);
    let mut d = original.clone();
    d.host_process = Some(live.clone());
    candidates.push(d);
    let mut d = original.clone();
    d.native_process = Some(live);
    candidates.push(d);
    let mut d = original.clone();
    d.native_process = None;
    candidates.push(d);
    let mut d = original.clone();
    d.native_pid += 1;
    candidates.push(d);
    for d in candidates {
        assert!(store.release_predecessor(&d).is_err());
        assert!(store.ended_generations.is_empty());
    }
    store.release_predecessor(&original).unwrap();
}

#[cfg(windows)]
#[tokio::test]
async fn admission_ended_generation_releases_writer_without_completing_or_replaying_unknown() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = empty_store(&dir);
    store
        .state
        .reserve(
            "s:old-unknown".into(),
            &json!({"id":"old-unknown","method":"turn/start","params":{}}),
            false,
        )
        .unwrap();
    store.save().unwrap();
    let previous = predecessor(&store, dir.path());
    let before = std::fs::read(dir.path().join("recovery.json")).unwrap();
    store.release_predecessor(&previous).unwrap();
    assert_eq!(
        before,
        std::fs::read(dir.path().join("recovery.json")).unwrap()
    );
    store
        .begin_generation(&uuid::Uuid::new_v4().to_string())
        .unwrap();
    assert!(store.state.reconciliation_required);
    assert!(!store.busy_resources());
    register(&mut store, "new-thread", "fresh", dir.path(), true).await;
    let (reply, frame) =
        submit_request(&mut store, turn("new-turn", "fresh", dir.path(), true)).await;
    assert!(frame.is_some(), "{reply}");
    assert!(store.busy_resources());
    let (_, frame) = submit_request(&mut store, start("competing-writer", dir.path(), true)).await;
    assert!(
        frame.is_none(),
        "current generation Unknown still reserves its root"
    );
    let (_, frame) =
        submit_request(&mut store, turn("old-unknown", "fresh", dir.path(), true)).await;
    assert!(frame.is_none());
    assert_eq!(store.receipt("s:old-unknown").state, "unknown");
    assert_eq!(
        store.receipt("s:old-unknown").generation,
        previous.generation
    );
    assert_eq!(
        store.snapshot()["releasedGenerations"],
        json!([previous.generation])
    );
    store
        .observe_native(
            json!({"id":"new-turn","result":{"turn":{"id":"new-run","status":"completed"}}}),
        )
        .unwrap();
    assert!(!store.busy_resources());
}
