use super::*;
use crate::assistant_product::{Actor, AssistantTestIo};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

struct ReconnectingSourceReader {
    broker: Arc<Broker>,
    handle: tokio::runtime::Handle,
    inner: Arc<dyn ProjectPermissionReader>,
    changed: AtomicBool,
}
impl ProjectPermissionReader for ReconnectingSourceReader {
    fn read(&self, runtime: &str, thread: &str, cwd: &Path) -> Result<ProjectPermissionProof> {
        if !self.changed.swap(true, Ordering::SeqCst) {
            self.handle.block_on(async {
                self.broker
                    .state
                    .lock()
                    .await
                    .runtimes
                    .get_mut(runtime)
                    .unwrap()
                    .connection_id = "reconnected-source".into();
            });
        }
        self.inner.read(runtime, thread, cwd)
    }
}

#[test]
fn assistant_project_source_contract_accepts_pairs_and_rejects_phone_paths() {
    for method in ["assistant/send", "assistant/schedule/create"] {
        let mut p = json!({"runtimeId":"runtime","sourceRuntimeId":"source","threadId":"t","commandId":"selected"});
        assert!(Broker::validate_assistant(method, &p).is_ok(), "{method}");
        for bad in [json!(null), json!(""), json!(false)] {
            p["sourceRuntimeId"] = bad;
            assert!(Broker::validate_assistant(method, &p).is_err());
        }
        p["sourceRuntimeId"] = json!("source");
        p.as_object_mut().unwrap().remove("threadId");
        assert!(Broker::validate_assistant(method, &p).is_err());
        p["threadId"] = json!("t");
        p["directory"] = json!("C:/phone/path");
        assert!(Broker::validate_assistant(method, &p).is_err());
    }
}

// Full authenticated Broker action -> native Desktop cwd/permission RPCs ->
// product Actor. Only the assistant model transport is fake; no project row is
// inserted directly and no Host/native subprocess is started.
async fn cross_source_case(method: &str, bad_owner: bool, wrong_store: bool, reconnect: bool) {
    let (dir, b) = crate::broker::tests::fixture().await;
    let project = dir.path().join("project");
    std::fs::create_dir(&project).unwrap();
    let mut peer = b.assistant_source_test_peer("source", "desktop").await;
    let status = {
        let s = b.state.lock().await;
        let r = &s.runtimes["source"];
        json!({"runtimeId":"source","nativeProcess":r.native_process,
            "initialized":true,"schemaState":"ready","permissionProtocol":1,"nativeCapabilities":r.capabilities})
    };
    let source_reads = Arc::new(AtomicUsize::new(0));
    let status_reads = Arc::new(AtomicUsize::new(0));
    let revoked = Arc::new(AtomicBool::new(false));
    let (stop, mut stopped) = tokio::sync::oneshot::channel::<()>();
    let read_count = source_reads.clone();
    let owner_checks = status_reads.clone();
    let deny = revoked.clone();
    let root = project.clone();
    let serve = tokio::spawn(async move {
        loop {
            let r = tokio::select! { _ = &mut stopped => break, r = peer.recv() => r };
            let result = match r["method"].as_str().unwrap() {
                "thread/read" => {
                    read_count.fetch_add(1, Ordering::SeqCst);
                    assert_eq!(r["params"]["threadId"], "t");
                    json!({"thread":{"id":"t","cwd":root,"status":{"type":"idle"}}})
                }
                "pocket/status" => {
                    owner_checks.fetch_add(1, Ordering::SeqCst);
                    let mut v = status.clone();
                    if bad_owner {
                        v["nativeProcess"]["createdAt"] = json!(1);
                    }
                    v
                }
                "pocket/threadSettings" => json!({"threadId":"t","known":true,"settings":{
                    "sandboxPolicy":{"type":if deny.load(Ordering::SeqCst){"readOnly"}else{"workspaceWrite"}},
                    "approvalPolicy":"on-request","approvalsReviewer":"user","activePermissionProfile":null}}),
                other => panic!("Unexpected Desktop RPC {other}"),
            };
            peer.reply(&r, result).await;
        }
    });
    let io = AssistantTestIo::default();
    let source_reader = crate::assistant_executor::project_permission_reader(&b.state_dir)
        .unwrap()
        .unwrap();
    io.source_reader(if reconnect {
        Arc::new(ReconnectingSourceReader {
            broker: b.clone(),
            handle: tokio::runtime::Handle::current(),
            inner: source_reader,
            changed: AtomicBool::new(false),
        })
    } else {
        source_reader
    });
    let root = b.state_dir.join("assistant");
    let actor = Actor::start_with_io(
        root.clone(),
        "computer".into(),
        "profile".into(),
        io.clone(),
        None,
        Arc::new(|| 1_000_000),
    )
    .await
    .unwrap();
    storage::atomic_write(
        &root.join("profile.json"),
        &serde_json::to_vec(&Profile {
            schema: 1,
            config_home: b.state_dir.clone(),
            native_binary: root.join("unused-test.exe"),
            source_runtime_id: "runtime".into(),
        })
        .unwrap(),
    )
    .unwrap();
    *b.assistant.lock().await = Some(actor.clone());
    let mut p = if method == "assistant/send" {
        json!({"runtimeId":"runtime","sourceRuntimeId":"source","threadId":"t","commandId":"cross-send","message":"Read only this selected project"})
    } else {
        json!({"runtimeId":"runtime","sourceRuntimeId":"source","threadId":"t","commandId":"cross-schedule",
            "spec":{"prompt":"Read project status","writes":false,"zone":{"name":"UTC"},"kind":crate::assistant::ScheduleKind::Once { at_ms:2_000_000 }}})
    };
    p["sourceConfigStoreId"] = json!(if wrong_store {
        "wrong-store".to_owned()
    } else {
        b.state.lock().await.runtimes["source"].store.clone()
    });
    let result = b.assistant_write("phone", method, &p).await;
    let snapshot = actor
        .request("assistant/read", json!({}), "read-source".into(), None)
        .await
        .unwrap();
    if !bad_owner && !wrong_store && !reconnect && method == "assistant/send" {
        let mapping = serde_json::from_value::<crate::assistant::NativeMapping>(
            snapshot["operations"][0]["mapping"].clone(),
        )
        .unwrap();
        // A selected Desktop project must support an actual task, not just an
        // authorization row or a successful rejection of another tool.
        io.tool(
            json!(101),
            &mapping,
            "read-only-task",
            "assistant_create_task",
            json!({"prompt":"List immediate filenames","directory":project,"writes":false}),
        );
        let created = actor
            .request(
                "assistant/read",
                json!({}),
                "read-created-task".into(),
                None,
            )
            .await
            .unwrap();
        assert_eq!(created["tasks"][0]["status"], "running");
        assert_eq!(created["tasks"][0]["writes"], false);
        assert!(
            created["operations"]
                .as_array()
                .unwrap()
                .iter()
                .any(|op| op["role"] == "Execution")
        );
        assert_eq!(io.answers().last().unwrap().1["success"], true);
        revoked.store(true, Ordering::SeqCst);
        // Ordinary reads don't poll every idle project. Exercise the authority
        // boundary where a new scheduled write must refresh native permission.
        io.tool(
            json!(102),
            &mapping,
            "revoked-schedule-write",
            "assistant_schedule_create",
            json!({"user_message_id":"user:cross-send","timezone":"UTC",
                "kind":{"type":"once","at_ms":2_000_000},"prompt":"Write a report",
                "directory":project,"writes":true}),
        );
        let after = actor
            .request(
                "assistant/read",
                json!({}),
                "read-revoked-source".into(),
                None,
            )
            .await
            .unwrap();
        assert_eq!(
            after["projectAuthorization"][0]["writable"], false,
            "Live Desktop permission revocation must reach the assistant grant"
        );
        assert_eq!(after["schedules"], json!([]));
        assert_eq!(io.answers().last().unwrap().1["success"], false);
    }
    b.assistant.lock().await.take();
    drop(actor);
    tokio::time::timeout(Duration::from_secs(3), async {
        while io.owners() > 1 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    let _ = stop.send(());
    serve.await.unwrap();
    if bad_owner || wrong_store || reconnect {
        assert!(result.is_err());
        if bad_owner {
            assert!(
                status_reads.load(Ordering::SeqCst) > 0,
                "The proof must reach the source's native owner check"
            );
        }
        if wrong_store {
            assert_eq!(source_reads.load(Ordering::SeqCst), 0);
        }
        assert_eq!(snapshot["projectAuthorization"], json!([]));
        assert_eq!(snapshot["operations"], json!([]));
        return;
    }
    result.unwrap();
    assert_eq!(snapshot["runtimeId"], "runtime");
    assert_eq!(
        snapshot["projectAuthorization"][0]["sourceRuntimeId"],
        "source"
    );
    assert_eq!(snapshot["projectAuthorization"][0]["threadId"], "t");
    assert_eq!(
        snapshot["projectAuthorization"][0]["directory"],
        json!(project)
    );
    assert_eq!(snapshot["projectAuthorization"][0]["verified"], true);
    assert!(source_reads.load(Ordering::SeqCst) >= 3);
    assert!(
        !io.read_threads().iter().any(|t| t == "t"),
        "Desktop thread must be read on its source, not the assistant Host"
    );
    if method == "assistant/schedule/create" {
        assert_eq!(snapshot["schedules"].as_array().unwrap().len(), 1);
        assert_eq!(snapshot["projectAuthorization"][0]["writable"], false);
    }
}

#[tokio::test]
async fn assistant_project_source_broker_send_proves_desktop_cwd_without_injecting_rows() {
    cross_source_case("assistant/send", false, false, false).await;
}
#[tokio::test]
async fn assistant_project_source_broker_schedule_proves_desktop_readonly_scope() {
    cross_source_case("assistant/schedule/create", false, false, false).await;
}
#[tokio::test]
async fn assistant_project_source_changed_native_owner_cannot_authorize_or_dispatch() {
    cross_source_case("assistant/send", true, false, false).await;
}

#[tokio::test]
async fn assistant_project_source_stale_config_store_cannot_grant_or_dispatch() {
    cross_source_case("assistant/send", false, true, false).await;
}

#[tokio::test]
async fn assistant_project_source_reconnect_between_selection_and_actor_cannot_grant() {
    cross_source_case("assistant/send", false, false, true).await;
}
