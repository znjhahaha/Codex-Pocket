use super::*;
use crate::assistant_product::{Actor, AssistantTestIo};

struct Fixture {
    dir: tempfile::TempDir,
    broker: Arc<Broker>,
    peer: crate::native::test_peer::Peer,
    actor: Actor,
    io: AssistantTestIo,
    params: Value,
}
impl Fixture {
    async fn new() -> Self {
        let (dir, broker) = crate::broker::tests::fixture().await;
        let peer = broker.assistant_source_test_peer("source", "desktop").await;
        let io = AssistantTestIo::default();
        let root = broker.state_dir.join("assistant");
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
                config_home: broker.state_dir.clone(),
                native_binary: root.join("unused.exe"),
                source_runtime_id: "runtime".into(),
            })
            .unwrap(),
        )
        .unwrap();
        *broker.assistant.lock().await = Some(actor.clone());
        let store = broker.state.lock().await.runtimes["source"].store.clone();
        let params = json!({"runtimeId":"runtime","sourceRuntimeId":"source","sourceConfigStoreId":store,"threadId":"t"});
        Self {
            dir,
            broker,
            peer,
            actor,
            io,
            params,
        }
    }
    fn request(&self, device: &str, params: Value) -> tokio::task::JoinHandle<Result<Value>> {
        let broker = self.broker.clone();
        let device = device.to_owned();
        tokio::spawn(async move {
            broker
                .request(&device, "assistant/project/read", params)
                .await
        })
    }
    async fn reply(&mut self, policy: &str, failure: &str) {
        let status = {
            let state = self.broker.state.lock().await;
            let source = &state.runtimes["source"];
            json!({"runtimeId":"source","nativeProcess":source.native_process,"initialized":true,"schemaState":"ready","permissionProtocol":1,"nativeCapabilities":source.capabilities})
        };
        // The first read establishes cwd; the existing reader verifies it again
        // on both sides of the fresh Desktop permission snapshot.
        for (index, method) in [
            "thread/read",
            "pocket/status",
            "thread/read",
            "pocket/threadSettings",
            "thread/read",
        ]
        .iter()
        .enumerate()
        {
            let request = self.peer.recv().await;
            assert_eq!(request["method"], *method);
            let result = match *method {
                "pocket/status" => status.clone(),
                "pocket/threadSettings" => {
                    json!({"threadId":"t","known":failure!="unknown","settings":{
                    "sandboxPolicy":{"type":policy},"approvalPolicy":"on-request","approvalsReviewer":"user",
                    "activePermissionProfile":if failure=="named" {json!({"id":"custom"})}else{Value::Null}}})
                }
                _ => json!({"thread":{"id":"t","cwd":self.dir.path()}}),
            };
            if index == 4 {
                match failure {
                    "reconnect" => {
                        self.broker
                            .state
                            .lock()
                            .await
                            .runtimes
                            .get_mut("source")
                            .unwrap()
                            .connection_id = "replacement".into()
                    }
                    "owner" => self
                        .broker
                        .state
                        .lock()
                        .await
                        .runtimes
                        .get_mut("source")
                        .unwrap()
                        .loaded
                        .clear(),
                    "revoke" => {
                        self.broker.state.lock().await.devices.revoke("phone");
                    }
                    "assistant" => {
                        self.broker.assistant.lock().await.take();
                    }
                    "event" => {
                        let mut state = self.broker.state.lock().await;
                        state.runtimes.get_mut("source").unwrap().settings.event(&json!({"method":"thread/settings/updated","params":{"threadId":"t","threadSettings":{
                            "sandboxPolicy":{"type":"readOnly"},"approvalPolicy":"on-request","approvalsReviewer":"user","activePermissionProfile":null}}}));
                    }
                    _ => {}
                }
            }
            self.peer.reply(&request, result).await;
            if failure == "unknown" && index == 3 {
                break;
            }
        }
    }
    async fn stop(self) {
        self.broker.assistant.lock().await.take();
        drop(self.actor);
        tokio::time::timeout(Duration::from_secs(3), async {
            while self.io.owners() > 1 {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
    }
}

#[tokio::test]
async fn assistant_project_read_returns_fresh_permission_without_dispatch_or_receipts() {
    let mut f = Fixture::new().await;
    let ledger = std::fs::read(f.broker.state_dir.join("commands.json")).unwrap();
    let broker = f.broker.clone();
    let _writes = broker.writes.lock().await;
    let mut revisions = vec![];
    for (policy, failure, expected) in [
        ("workspaceWrite", "", true),
        ("readOnly", "", false),
        ("workspaceWrite", "named", false),
    ] {
        let request = f.request("phone", f.params.clone());
        f.reply(policy, failure).await;
        let result = request.await.unwrap().unwrap();
        assert_eq!(result["sourceRuntimeId"], "source");
        assert_eq!(
            result["sourceConfigStoreId"],
            f.params["sourceConfigStoreId"]
        );
        assert_eq!(result["threadId"], "t");
        assert_eq!(result["verified"], true);
        assert_eq!(result["writable"], expected);
        assert!(
            result["sourceGeneration"]
                .as_str()
                .is_some_and(|s| !s.is_empty())
        );
        revisions.push(result["permissionRevision"].clone());
        assert_eq!(result.as_object().unwrap().len(), 7);
    }
    assert_ne!(revisions[0], revisions[1]);
    assert_eq!(
        std::fs::read(f.broker.state_dir.join("commands.json")).unwrap(),
        ledger
    );
    for field in ["tasks", "operations", "projectAuthorization"] {
        assert_eq!(f.actor.snapshot()[field], json!([]));
    }
    assert!(f.io.read_threads().is_empty());
    assert!(f.io.answers().is_empty());
    f.peer.assert_idle().await;
    f.stop().await;
}

#[tokio::test]
async fn assistant_project_read_rejects_invalid_unpaired_and_wrong_store_before_native_io() {
    let mut f = Fixture::new().await;
    assert!(
        f.request("unpaired", f.params.clone())
            .await
            .unwrap()
            .is_err()
    );
    for field in [
        "runtimeId",
        "sourceRuntimeId",
        "sourceConfigStoreId",
        "threadId",
    ] {
        let mut missing = f.params.clone();
        missing.as_object_mut().unwrap().remove(field);
        assert!(f.request("phone", missing).await.unwrap().is_err());
        for invalid in [json!(null), json!(""), json!(false), json!("other")] {
            let mut params = f.params.clone();
            params[field] = invalid;
            assert!(f.request("phone", params).await.unwrap().is_err());
        }
    }
    for field in ["commandId", "cwd", "directory", "path", "writable"] {
        let mut params = f.params.clone();
        params[field] = json!("not-accepted");
        assert!(f.request("phone", params).await.unwrap().is_err());
    }
    f.peer.assert_idle().await;
    f.stop().await;
}

#[tokio::test]
async fn assistant_project_read_rejects_unknown_reconnect_owner_and_revoked_device() {
    for failure in ["unknown", "reconnect", "owner", "revoke", "assistant"] {
        let mut f = Fixture::new().await;
        let request = f.request("phone", f.params.clone());
        f.reply("workspaceWrite", failure).await;
        assert!(request.await.unwrap().is_err(), "{failure}");
        assert_eq!(f.actor.snapshot()["projectAuthorization"], json!([]));
        f.stop().await;
    }
}

#[tokio::test]
async fn assistant_project_read_late_native_snapshot_cannot_override_permission_event() {
    let mut f = Fixture::new().await;
    let request = f.request("phone", f.params.clone());
    f.reply("workspaceWrite", "event").await;
    let result = request.await.unwrap().unwrap();
    assert_eq!(result["verified"], true);
    assert_eq!(result["writable"], false);
    f.stop().await;
}

#[tokio::test]
async fn assistant_project_read_is_advertised_but_local_control_remains_rejected() {
    let f = Fixture::new().await;
    assert!(
        f.broker.hello().await["capabilities"]
            .as_array()
            .unwrap()
            .contains(&json!("assistant/project/read"))
    );
    // A temp loopback server in this test process exercises the real local
    // allowlist. It never attaches to QA or launches a Host/native subprocess.
    let _lock = crate::local::try_lock(&f.broker.state_dir)
        .unwrap()
        .unwrap();
    let server = crate::local::Server::start(&f.broker.state_dir, f.broker.clone())
        .await
        .unwrap();
    let error = crate::local::execute_with_params(
        &f.broker.state_dir,
        "assistant/project/read",
        None,
        f.params.clone(),
    )
    .await
    .unwrap_err();
    assert_eq!(crate::error::classification(&error), Some(Code::Rejected));
    drop(server);
    f.stop().await;
}
