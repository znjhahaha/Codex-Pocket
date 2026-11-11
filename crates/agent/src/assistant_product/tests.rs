use super::*;
use std::sync::atomic::AtomicI64;

#[derive(Default)]
struct State {
    calls: Vec<(Value, String, Value)>,
    receipts: BTreeMap<String, RuntimeReceipt>,
    hidden_receipts: BTreeSet<String>,
    threads: BTreeMap<String, Value>,
    permissions: BTreeMap<String, ProjectPermissionProof>,
    permission_reader: Option<Arc<dyn ProjectPermissionReader>>,
    turns: BTreeMap<String, Vec<Value>>,
    events: Vec<RuntimeEvent>,
    pending: BTreeMap<String, Value>,
    answers: Vec<(Value, Value)>,
    lose_method: Option<String>,
    unsupported: Option<String>,
    lose_answer: bool,
    ignore_interrupt: bool,
    offline: bool,
    fail_reads: bool,
    serial: u64,
}
#[derive(Clone, Default)]
struct Fake(Arc<Mutex<State>>);
impl Fake {
    fn selected(&self, id: &str, root: &Path) {
        let mut state = self.0.lock().unwrap();
        state.threads.insert(
            id.into(),
            json!({"id":id,"cwd":root,"preview":"Selected desktop project"}),
        );
        state.permissions.insert(id.into(), ProjectPermissionProof {
            runtime_id:"runtime".into(), generation:"source-generation".into(), thread_id:id.into(),
            cwd:root.into(), revision:"source-revision".into(),
            settings:crate::settings::canonical(&json!({"sandbox":{"type":"workspaceWrite"},"approvalPolicy":"on-request","approvalsReviewer":"user"})),
        });
    }
    fn emit(&self, id: Value, method: &str, mapping: &NativeMapping, more: Value) {
        let mut params = json!({"threadId":mapping.thread_id,"turnId":mapping.turn_id});
        params
            .as_object_mut()
            .unwrap()
            .extend(more.as_object().unwrap().clone());
        let v = json!({"id":id,"method":method,"params":params});
        let mut s = self.0.lock().unwrap();
        if !id.is_null() {
            s.pending.insert(key(&id), v.clone());
        }
        let sequence = s.events.len() as u64 + 1;
        s.events.push(RuntimeEvent {
            sequence,
            message: v,
        });
    }
    fn tool(&self, id: Value, m: &NativeMapping, call_id: &str, name: &str, arguments: Value) {
        self.emit(
            id,
            "item/tool/call",
            m,
            json!({"callId":call_id,"tool":name,"namespace":null,"arguments":arguments}),
        );
    }
    fn complete(&self, m: &NativeMapping, text: &str) {
        let turn = json!({"id":m.turn_id,"status":"completed","items":[{"type":"agentMessage","id":"result","text":text}]});
        {
            let mut s = self.0.lock().unwrap();
            *s.turns
                .get_mut(&m.thread_id)
                .unwrap()
                .iter_mut()
                .find(|t| t["id"] == m.turn_id)
                .unwrap() = turn.clone();
            s.pending.retain(|_, v| v["params"]["turnId"] != m.turn_id);
        }
        self.emit(Value::Null, "turn/completed", m, json!({"turn":turn}));
    }
    fn resolve(&self, id: Value) {
        let mut s = self.0.lock().unwrap();
        let p = s.pending.remove(&key(&id)).unwrap();
        let sequence = s.events.len() as u64 + 1;
        s.events.push(RuntimeEvent{sequence,message:json!({"method":"serverRequest/resolved","params":{"requestId":id,"threadId":p["params"]["threadId"]}})});
    }
    fn count(&self, method: &str) -> usize {
        self.0
            .lock()
            .unwrap()
            .calls
            .iter()
            .filter(|(_, m, _)| m == method)
            .count()
    }
    fn answers(&self) -> Vec<(Value, Value)> {
        self.0.lock().unwrap().answers.clone()
    }
}
impl RuntimeIo for Fake {
    fn project_permissions(
        &mut self,
        runtime: &str,
        thread: &str,
        cwd: &Path,
    ) -> Result<ProjectPermissionProof> {
        let reader = self.0.lock().unwrap().permission_reader.clone();
        if let Some(reader) = reader {
            return reader.read(runtime, thread, cwd);
        }
        let state = self.0.lock().unwrap();
        ensure!(
            !state.offline && !state.fail_reads,
            "Source permissions unavailable"
        );
        ensure!(runtime == "runtime", "Source runtime changed");
        state
            .permissions
            .get(thread)
            .cloned()
            .context("Source permission projection unavailable")
    }
    fn request(&mut self, id: Value, method: &str, p: Value) -> Result<Value> {
        let mut s = self.0.lock().unwrap();
        ensure!(!s.offline, "Fake offline");
        if s.unsupported.as_deref() == Some(method) {
            return Err(RuntimeUnsupported {
                method: method.into(),
            }
            .into());
        }
        ensure!(
            !(s.fail_reads && matches!(method, "thread/read" | "thread/turns/list")),
            "Fake read unavailable"
        );
        // Stable mutating IDs must never be submitted twice by product code.
        if !read_method(method) {
            ensure!(
                !s.calls.iter().any(|(old, _, _)| old == &id),
                "Duplicate native dispatch"
            );
        }
        s.calls.push((id.clone(), method.into(), p.clone()));
        s.serial += 1;
        let n = s.serial;
        let result = match method {
            "model/list" => {
                json!({"data":[{"id":"m","model":"m","hidden":false,"isDefault":true}],"nextCursor":null})
            }
            "thread/start" | "thread/resume" => {
                let thread = if method == "thread/start" {
                    format!("native-thread-{n}")
                } else {
                    p["threadId"].as_str().unwrap().into()
                };
                let meta = json!({"id":thread,"cwd":p["cwd"],"preview":"Native fixture thread"});
                s.threads.insert(thread, meta.clone());
                json!({"thread":meta,"model":p["model"],"cwd":p["cwd"],"approvalPolicy":"on-request","approvalsReviewer":"user","sandbox":{"type":if p["sandbox"]=="workspace-write"{"workspaceWrite"}else{"readOnly"}}})
            }
            "thread/read" => {
                let thread = p["threadId"].as_str().unwrap();
                let mut meta = s
                    .threads
                    .get(thread)
                    .context("Native thread missing")?
                    .clone();
                let active = s
                    .turns
                    .get(thread)
                    .is_some_and(|ts| ts.iter().any(|t| t["status"] == "inProgress"));
                meta["status"] = json!({"type":if active {"active"}else{"idle"},"activeFlags":[]});
                json!({"thread":meta})
            }
            "thread/list" => {
                json!({"data":s.threads.values().cloned().collect::<Vec<_>>(),"nextCursor":null})
            }
            "thread/turns/list" => {
                json!({"data":s.turns.get(p["threadId"].as_str().unwrap()).cloned().unwrap_or_default(),"nextCursor":null})
            }
            "turn/start" => {
                let turn =
                    json!({"id":format!("native-turn-{n}"),"items":[],"status":"inProgress"});
                s.turns
                    .entry(p["threadId"].as_str().unwrap().into())
                    .or_default()
                    .push(turn.clone());
                json!({"turn":turn})
            }
            "turn/interrupt" => {
                if s.ignore_interrupt {
                    anyhow::bail!("Interrupt outcome unknown; turn remains running");
                }
                if let Some(turn) = s
                    .turns
                    .get_mut(p["threadId"].as_str().unwrap())
                    .and_then(|ts| ts.iter_mut().find(|t| t["id"] == p["turnId"]))
                {
                    turn["status"] = json!("interrupted");
                }
                s.pending
                    .retain(|_, v| v["params"]["turnId"] != p["turnId"]);
                json!({})
            }
            _ => anyhow::bail!("Unexpected fake method: {method}"),
        };
        s.receipts.insert(
            key(&id),
            RuntimeReceipt {
                generation: "host".into(),
                state: "completed".into(),
                response: Some(json!({"id":id,"result":result})),
            },
        );
        if s.lose_method.as_deref() == Some(method) {
            s.lose_method = None;
            s.hidden_receipts.insert(key(&id));
            anyhow::bail!("Lost start receipt");
        }
        Ok(result)
    }
    fn receipt(&mut self, id: Value) -> Result<RuntimeReceipt> {
        let s = self.0.lock().unwrap();
        if s.hidden_receipts.contains(&key(&id)) {
            return Ok(RuntimeReceipt {
                generation: "host".into(),
                state: "unknown".into(),
                response: None,
            });
        }
        Ok(s.receipts
            .get(&key(&id))
            .cloned()
            .unwrap_or(RuntimeReceipt {
                generation: "host".into(),
                state: "missing".into(),
                response: None,
            }))
    }
    fn answer(&mut self, id: Value, result: Value) -> Result<()> {
        let mut s = self.0.lock().unwrap();
        ensure!(
            s.pending.contains_key(&key(&id)),
            "Answer original request is no longer pending"
        );
        ensure!(
            !s.answers.iter().any(|(old, _)| old == &id),
            "Duplicate native answer"
        );
        s.answers.push((id.clone(), result));
        if std::mem::take(&mut s.lose_answer) {
            anyhow::bail!("Lost answer receipt");
        }
        let p = s.pending.remove(&key(&id)).unwrap();
        let sequence = s.events.len() as u64 + 1;
        s.events.push(RuntimeEvent{sequence,message:json!({"method":"serverRequest/resolved","params":{"requestId":id,"threadId":p["params"]["threadId"]}})});
        Ok(())
    }
    fn events(&mut self, after: u64, limit: usize) -> Result<RuntimeEvents> {
        let s = self.0.lock().unwrap();
        ensure!(!s.offline, "Fake offline");
        let events: Vec<_> = s
            .events
            .iter()
            .filter(|e| e.sequence > after)
            .take(limit)
            .cloned()
            .collect();
        Ok(RuntimeEvents {
            generation: "host".into(),
            next_after: events.last().map_or(after, |e| e.sequence),
            snapshot_required: false,
            events,
            pending_requests: s.pending.values().cloned().collect(),
        })
    }
    fn health(&mut self) -> Result<RuntimeHealth> {
        Ok(RuntimeHealth {
            runtime_id: "runtime".into(),
            generation: "host".into(),
            online: !self.0.lock().unwrap().offline,
            initialized: true,
            protocol_verified: true,
            reconciliation_required: false,
        })
    }
}
struct Fixture {
    root: tempfile::TempDir,
    project: tempfile::TempDir,
    io: Fake,
    clock: Arc<AtomicI64>,
    actor: Option<Actor>,
    handoff: Option<Arc<crate::handoff::Handoff>>,
}
impl Fixture {
    async fn new(handoff: bool) -> Self {
        let root = tempfile::tempdir().unwrap();
        let project = tempfile::tempdir().unwrap();
        let io = Fake::default();
        io.selected("desktop-thread", project.path());
        let h = handoff.then(|| {
            crate::handoff::Handoff::open(
                &root.path().join("agent"),
                uuid::Uuid::new_v4(),
                true,
                true,
            )
            .unwrap()
        });
        let mut f = Self {
            root,
            project,
            io,
            clock: Arc::new(AtomicI64::new(1_000_000)),
            actor: None,
            handoff: h,
        };
        f.start().await;
        f
    }
    async fn start(&mut self) {
        let clock = self.clock.clone();
        self.actor = Some(
            Actor::start_with_io(
                self.root.path().join("assistant"),
                "computer".into(),
                "profile".into(),
                self.io.clone(),
                self.handoff.clone(),
                Arc::new(move || clock.load(Ordering::SeqCst)),
            )
            .await
            .unwrap(),
        );
    }
    async fn stop(&mut self) {
        let actor = self.actor.take().unwrap();
        let alive = actor.alive.clone();
        drop(actor);
        tokio::time::timeout(Duration::from_secs(3), async {
            while alive.load(Ordering::Acquire) {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
    }
    fn at(&self, at: i64) {
        self.clock.store(at, Ordering::SeqCst);
    }
    async fn call(&self, method: &str, p: Value, id: &str, directory: bool) -> Result<Value> {
        self.actor
            .as_ref()
            .unwrap()
            .request(
                method,
                p,
                id.into(),
                directory.then(|| self.project.path().into()),
            )
            .await
    }
    async fn read(&self) -> Value {
        self.call("assistant/read", json!({}), "read", false)
            .await
            .unwrap()
    }
    async fn send(&self, id: &str) -> Value {
        self.call(
            "assistant/send",
            json!({"message":"手机交办：准备成果","threadId":"desktop-thread"}),
            id,
            true,
        )
        .await
        .unwrap()["snapshot"]
            .clone()
    }
    fn op(v: &Value, role: &str) -> Value {
        v["operations"]
            .as_array()
            .unwrap()
            .iter()
            .rev()
            .find(|o| o["role"] == role)
            .unwrap()
            .clone()
    }
    fn mapping(op: &Value) -> NativeMapping {
        serde_json::from_value(op["mapping"].clone()).unwrap()
    }
    async fn create(&self, m: &NativeMapping, id: i64, prompt: &str) -> Value {
        self.io.tool(
            json!(id),
            m,
            &format!("call-{id}"),
            "assistant_create_task",
            json!({"prompt":prompt,"directory":self.project.path(),"writes":true}),
        );
        self.read().await
    }
}

#[test]
fn empty_product_snapshot_succeeds_with_bounded_event_page() {
    let root = tempfile::tempdir().unwrap();
    let executor = SharedHostExecutor::new(
        HostExecutor::open(
            &root.path().join("executor.db"),
            Fake::default(),
            ExecutorPolicy {
                coordinator_directory: root.path().into(),
                grants: vec![],
            },
        )
        .unwrap(),
    );
    let service = AssistantService::open(
        &root.path().join("assistant.db"),
        "computer",
        "profile",
        "host",
        executor.clone(),
    )
    .unwrap();
    let messages = messages_db(&root.path().join("messages.db")).unwrap();
    assert!(snapshot_value(&service, &executor, &messages, "runtime", None).is_ok());
}

#[tokio::test]
async fn actual_actor_phone_to_native_tool_question_human_answer_result_and_restart() {
    let mut f = Fixture::new(false).await;
    assert!(f.actor.as_ref().unwrap().is_alive());
    assert_eq!(f.io.count("turn/start"), 0);
    assert_eq!(f.read().await["projectAuthorization"], json!([]));
    let s = f.send("phone-1").await;
    let coord = Fixture::op(&s, "Coordinator");
    let cm = Fixture::mapping(&coord);
    assert_eq!(s["coordinatorThreadId"], cm.thread_id);
    let call =
        f.io.0
            .lock()
            .unwrap()
            .calls
            .iter()
            .find(|(_, m, _)| m == "thread/start")
            .unwrap()
            .2
            .clone();
    assert_eq!(call["sandbox"], "read-only");
    assert_eq!(call["dynamicTools"].as_array().unwrap().len(), 6);
    let s = f.create(&cm, 101, "写一份报告").await;
    let execution = Fixture::op(&s, "Execution");
    let em = Fixture::mapping(&execution);
    assert_ne!(cm.thread_id, em.thread_id);
    assert_eq!(s["tasks"][0]["status"], "running");
    f.at(1_001_000);
    f.io.emit(json!("native-input-7"),"item/tool/requestUserInput",&em,json!({"itemId":"question-item","questions":[{"id":"native-question","header":"格式","question":"什么格式？","isOther":false,"isSecret":false,"options":null}]}));
    f.io.tool(
        json!(102),
        &cm,
        "held-call",
        "assistant_request_input",
        json!({"request_id":"human-coord","text":"请确认报告用途"}),
    );
    let s = f.read().await;
    let e = Fixture::op(&s, "Execution");
    let c = Fixture::op(&s, "Coordinator");
    assert_eq!(e["phase"], "Waiting");
    assert_eq!(c["input_tool_call"], "held-call");
    assert_eq!(
        f.io.answers().len(),
        1,
        "held input must not be rejected or answered"
    );
    f.read().await;
    assert_eq!(f.io.answers().len(), 1);
    assert!(
        f.call(
            "assistant/respond",
            json!({"operationId":e["id"],"requestId":"wrong","response":{"Answer":"Markdown"}}),
            "wrong-answer",
            false
        )
        .await
        .is_err()
    );
    f.call("assistant/respond",json!({"operationId":e["id"],"requestId":e["question"]["request_id"],"response":{"Answer":"Markdown"}}),"answer-exec",false).await.unwrap();
    assert_eq!(
        f.io.answers()[1],
        (
            json!("native-input-7"),
            json!({"answers":{"native-question":{"answers":["Markdown"]}}})
        )
    );
    f.at(1_002_000);
    f.io.complete(&em, "任务成果：report.md");
    let s = f.read().await;
    assert_eq!(s["tasks"][0]["status"], "done");
    assert_eq!(f.io.count("turn/start"), 2);
    f.call(
        "assistant/respond",
        json!({"operationId":c["id"],"requestId":"human-coord","response":{"Answer":"周会汇报"}}),
        "answer-coord",
        false,
    )
    .await
    .unwrap();
    assert_eq!(f.io.answers()[2].0, json!(102));
    assert_eq!(f.io.answers()[2].1["success"], true);
    f.at(1_003_000);
    f.io.complete(&cm, "报告已完成，可查看 report.md");
    let s = f.read().await;
    assert_eq!(s["messages"][0]["role"], "User");
    assert_eq!(s["messages"][0]["atMs"], 1_000_000);
    assert_eq!(s["messages"][1]["role"], "Assistant");
    assert_eq!(s["messages"][1]["atMs"], 1_003_000);
    let turns = f.io.count("turn/start");
    f.read().await;
    assert_eq!(f.io.count("turn/start"), turns);
    f.stop().await;
    f.start().await;
    let s = f.read().await;
    assert_eq!(s["tasks"][0]["status"], "done");
    assert_eq!(s["messages"].as_array().unwrap().len(), 2);
    assert_eq!(f.io.count("turn/start"), turns);
    assert_eq!(s["projectAuthorization"][0]["verified"], true);
    f.stop().await;
}

#[tokio::test]
async fn uncertain_tool_answer_is_never_rejected_replaced_or_retried_even_after_restart() {
    let mut f = Fixture::new(true).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.0.lock().unwrap().lose_answer = true;
    f.io.tool(
        json!("lost-tool"),
        &cm,
        "lost-call",
        "assistant_summarize",
        json!({"text":"成果摘要"}),
    );
    let s = f.read().await;
    assert_eq!(f.io.answers().len(), 1);
    assert_eq!(s["reconciliationRequired"], true);
    assert_eq!(s["unconfirmedWrites"], 1);
    f.read().await;
    assert_eq!(f.io.answers().len(), 1);
    f.stop().await;
    f.start().await;
    let s = f.read().await;
    assert_eq!(f.io.answers().len(), 1);
    assert_eq!(
        s["reconciliationRequired"], true,
        "answer uncertainty must survive restart"
    );
    f.io.resolve(json!("lost-tool"));
    let s = f.read().await;
    assert_eq!(s["reconciliationRequired"], false);
    assert_eq!(
        s["ready"], true,
        "Resolved tool must retire deferred work too"
    );
    assert_eq!(f.io.answers().len(), 1);
    let generation = f
        .handoff
        .as_ref()
        .unwrap()
        .capabilities(&json!({}))
        .unwrap()["generation"]
        .as_str()
        .unwrap()
        .parse()
        .unwrap();
    assert_eq!(
        f.handoff
            .as_ref()
            .unwrap()
            .begin_quiesce(&crate::handoff::Params {
                id: uuid::Uuid::new_v4(),
                from_generation: generation,
                checkpoint: None
            })
            .unwrap()
            .unconfirmed_writes,
        0,
        "restarted actor must release its recovered handoff guard"
    );
    f.stop().await;
}

#[tokio::test]
async fn lost_start_receipt_handoff_blocks_new_work_then_read_releases_guard_without_resend() {
    let mut f = Fixture::new(true).await;
    f.io.0.lock().unwrap().lose_method = Some("turn/start".into());
    let s = f.send("phone").await;
    assert_eq!(s["reconciliationRequired"], true);
    assert_eq!(s["unconfirmedWrites"], 1);
    let count = f.io.count("turn/start");
    let h = f.handoff.as_ref().unwrap().clone();
    let generation = h.capabilities(&json!({})).unwrap()["generation"]
        .as_str()
        .unwrap()
        .parse()
        .unwrap();
    let p = crate::handoff::Params {
        id: uuid::Uuid::new_v4(),
        from_generation: generation,
        checkpoint: None,
    };
    h.begin_quiesce(&p).unwrap();
    assert!(f.send_result("new-phone").await.is_err());
    f.io.0.lock().unwrap().hidden_receipts.clear();
    let s = f.read().await;
    assert_eq!(s["reconciliationRequired"], false);
    assert_eq!(s["unconfirmedWrites"], 0);
    assert_eq!(f.io.count("turn/start"), count);
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.tool(
        json!(110),
        &cm,
        "fenced-create",
        "assistant_create_task",
        json!({"prompt":"fenced","directory":f.project.path(),"writes":true}),
    );
    let s = f.read().await;
    assert!(s["tasks"].as_array().unwrap().is_empty());
    assert!(f.io.answers().is_empty());
    f.stop().await;
}
impl Fixture {
    async fn send_result(&self, id: &str) -> Result<Value> {
        self.call(
            "assistant/send",
            json!({"message":"next","threadId":"desktop-thread"}),
            id,
            true,
        )
        .await
    }
}

#[tokio::test]
async fn internal_takeover_pauses_entrusted_work_and_release_does_not_rerun() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.create(&cm, 201, "task").await;
    let count = f.io.count("turn/start");
    let s = f
        .call("assistant/takeover", json!({}), "manual:turn-write", false)
        .await
        .unwrap()["snapshot"]
        .clone();
    assert_eq!(s["paused"], true);
    assert_eq!(s["tasks"][0]["status"], "paused");
    assert!(f.io.count("turn/interrupt") >= 2);
    f.read().await;
    f.call("assistant/resume", json!({}), "release", false)
        .await
        .unwrap();
    let s = f.read().await;
    assert_eq!(s["paused"], false);
    assert_eq!(s["tasks"][0]["status"], "paused");
    assert_eq!(f.io.count("turn/start"), count);
    f.stop().await;
    f.start().await;
    assert_eq!(f.io.count("turn/start"), count);
    f.stop().await;
}

#[tokio::test]
async fn finish_takeover_requires_stopped_evidence_and_replay_rechecks_it() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    let s = f.create(&cm, 801, "task").await;
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    f.io.0.lock().unwrap().ignore_interrupt = true;
    assert!(
        f.call("assistant/takeover", json!({}), "manual:unknown", false)
            .await
            .is_err(),
        "Manual write must wait while entrusted native turns are still live"
    );
    let s = f.read().await;
    assert_eq!(s["paused"], true);
    assert_eq!(s["tasks"][0]["status"], "paused");
    assert!(
        f.call("assistant/takeover", json!({}), "manual:unknown", false)
            .await
            .is_err()
    );
    f.io.complete(&cm, "coordinator stopped");
    f.io.complete(&em, "task stopped");
    f.call("assistant/takeover", json!({}), "manual:unknown", false)
        .await
        .unwrap();
    assert_eq!(f.io.count("turn/start"), 2);
    f.stop().await;
}

#[tokio::test]
async fn finish_takeover_replay_after_release_cannot_authorize_manual_write() {
    let mut f = Fixture::new(false).await;
    f.send("phone").await;
    f.call("assistant/takeover", json!({}), "manual:old", false)
        .await
        .unwrap();
    f.call("assistant/resume", json!({}), "release", false)
        .await
        .unwrap();
    f.send("new-phone").await;
    assert!(
        f.call("assistant/takeover", json!({}), "manual:old", false)
            .await
            .is_err(),
        "A historical pause receipt cannot authorize writing while scheduling is enabled again"
    );
    f.stop().await;
}

#[tokio::test]
async fn finish_readonly_project_rejects_writable_schedule_before_persistence() {
    let mut f = Fixture::new(false).await;
    f.io.0
        .lock()
        .unwrap()
        .permissions
        .get_mut("desktop-thread")
        .unwrap()
        .settings["sandboxPolicy"] = json!({"type":"readOnly"});
    assert!(f.call("assistant/schedule/create", json!({"threadId":"desktop-thread","spec":{"kind":{"Once":{"at_ms":1_001_000}},"zone":{"name":"UTC"},"prompt":"write","writes":true}}), "schedule-write", true).await.is_err());
    assert!(f.read().await["schedules"].as_array().unwrap().is_empty());
    f.stop().await;
}

#[tokio::test]
async fn finish_authoritative_pending_snapshot_removes_resolved_tool_without_event() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.0.lock().unwrap().lose_answer = true;
    f.io.tool(
        json!(802),
        &cm,
        "unknown-call",
        "assistant_summarize",
        json!({"text":"summary"}),
    );
    f.read().await;
    f.io.0.lock().unwrap().pending.remove(&key(&json!(802)));
    let s = f.read().await;
    assert_eq!(s["reconciliationRequired"], false);
    assert!(
        s["pendingRequests"].as_array().unwrap().is_empty(),
        "Resolved native call must leave the adapter pending set"
    );
    assert_eq!(
        s["ready"], true,
        "Resolved call must leave the worker deferred set"
    );
    assert_eq!(f.io.answers().len(), 1);
    f.stop().await;
}

#[tokio::test]
async fn finish_unknown_held_human_answer_clears_question_after_authoritative_resolution() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.tool(
        json!(803),
        &cm,
        "held-call",
        "assistant_request_input",
        json!({"request_id":"format","text":"Which format?"}),
    );
    let s = f.read().await;
    let op = Fixture::op(&s, "Coordinator");
    assert_eq!(op["input_tool_call"], "held-call");
    assert_eq!(op["phase"], "Waiting");
    f.io.0.lock().unwrap().lose_answer = true;
    let answer = json!({"operationId":op["id"],"requestId":op["question"]["request_id"],"response":{"Answer":"markdown"}});
    assert!(
        f.call("assistant/respond", answer.clone(), "human", false)
            .await
            .is_err()
    );
    f.stop().await;
    f.start().await;
    f.io.0.lock().unwrap().pending.remove(&key(&json!(803)));
    let s = f.read().await;
    let current = Fixture::op(&s, "Coordinator");
    assert!(
        current["question"].is_null(),
        "Resolved unknown answer must not leave a false waiting question"
    );
    assert!(current["input_tool_call"].is_null());
    assert!(
        f.call("assistant/respond", answer, "human-again", false)
            .await
            .is_err()
    );
    assert_eq!(f.io.answers().len(), 1);
    f.stop().await;
}

#[tokio::test]
async fn finish_held_call_resolved_while_actor_stopped_does_not_restore_false_question() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.tool(
        json!(804),
        &cm,
        "offline-held",
        "assistant_request_input",
        json!({"request_id":"format","text":"Which format?"}),
    );
    assert_eq!(
        Fixture::op(&f.read().await, "Coordinator")["phase"],
        "Waiting"
    );
    f.stop().await;
    f.io.0.lock().unwrap().pending.remove(&key(&json!(804)));
    f.start().await;
    let s = f.read().await;
    assert!(Fixture::op(&s, "Coordinator")["question"].is_null());
    assert!(f.io.answers().is_empty());
    f.stop().await;
}

#[tokio::test]
async fn finish_selected_readonly_or_unknown_project_never_grants_write() {
    for sandbox in [json!({"type":"readOnly"}), Value::Null] {
        let mut f = Fixture::new(false).await;
        f.io.0
            .lock()
            .unwrap()
            .permissions
            .get_mut("desktop-thread")
            .unwrap()
            .settings["sandboxPolicy"] = sandbox;
        let s = f.send("phone").await;
        assert_eq!(s["projectAuthorization"][0]["writable"], false);
        let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
        f.create(&cm, 805, "should not write").await;
        assert_eq!(f.io.count("turn/start"), 1);
        f.stop().await;
    }
}

#[tokio::test]
async fn finish_restored_write_grant_rechecks_native_permission_before_queued_dispatch() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    let s = f.create(&cm, 806, "first writer").await;
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    f.create(&cm, 807, "queued writer").await;
    let count = f.io.count("turn/start");
    f.stop().await;
    f.io.0
        .lock()
        .unwrap()
        .permissions
        .get_mut("desktop-thread")
        .unwrap()
        .settings["sandboxPolicy"] = json!({"type":"readOnly"});
    f.io.complete(&em, "first finished");
    f.start().await;
    let s = f.read().await;
    assert_eq!(f.io.count("turn/start"), count);
    assert_eq!(s["projectAuthorization"][0]["writable"], false);
    assert!(
        s["tasks"]
            .as_array()
            .unwrap()
            .iter()
            .any(|t| t["status"] == "paused")
    );
    f.stop().await;
}

#[tokio::test]
async fn finish_live_write_grant_revocation_blocks_second_writer() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    let s = f.create(&cm, 808, "first writer").await;
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    f.create(&cm, 809, "second writer").await;
    let count = f.io.count("turn/start");
    f.io.0
        .lock()
        .unwrap()
        .permissions
        .get_mut("desktop-thread")
        .unwrap()
        .settings["sandboxPolicy"] = json!({"type":"readOnly"});
    f.io.complete(&em, "finished");
    let s = f.read().await;
    assert_eq!(f.io.count("turn/start"), count);
    assert_eq!(s["projectAuthorization"][0]["writable"], false);
    f.stop().await;
}

#[tokio::test]
async fn restored_queued_task_revalidates_native_cwd_before_dispatch_and_bad_cwd_pauses() {
    for valid in [true, false] {
        let mut f = Fixture::new(false).await;
        let s = f.send("phone").await;
        let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
        f.create(&cm, 301, "first writer").await;
        let s = f.create(&cm, 302, "queued writer").await;
        assert_eq!(s["tasks"][1]["status"], "queued");
        let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
        f.stop().await;
        f.io.complete(&em, "first finished");
        let other = tempfile::tempdir().unwrap();
        if !valid {
            f.io.selected("desktop-thread", other.path());
        }
        f.start().await;
        let s = f.read().await;
        assert_eq!(s["projectAuthorization"][0]["verified"], valid);
        assert_eq!(
            s["tasks"][1]["status"],
            if valid { "running" } else { "paused" }
        );
        assert_eq!(
            f.io.count("turn/start"),
            if valid { 3 } else { 2 },
            "queued task must not become unknown through an empty grant policy"
        );
        f.stop().await;
    }
}

#[tokio::test]
async fn native_approval_is_manual_and_replayed_phone_commands_answer_only_once() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    let s = f.create(&cm, 401, "task").await;
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    f.io.emit(json!(402),"item/commandExecution/requestApproval",&em,json!({"itemId":"cmd","command":"write report","availableDecisions":["accept","decline"],"reason":"Approve write"}));
    let s = f.read().await;
    let e = Fixture::op(&s, "Execution");
    assert_eq!(e["question"]["kind"], "Approval");
    assert_eq!(f.io.answers().len(), 1);
    let p = json!({"operationId":e["id"],"requestId":e["question"]["request_id"],"response":{"Approval":{"approved":false}}});
    f.call("assistant/respond", p.clone(), "decline", false)
        .await
        .unwrap();
    f.call("assistant/respond", p, "decline-duplicate", false)
        .await
        .unwrap();
    assert_eq!(
        f.io.answers()[1],
        (json!(402), json!({"decision":"decline"}))
    );
    assert_eq!(f.io.answers().len(), 2);
    f.stop().await;
}

#[tokio::test]
async fn foreground_settings_memory_and_events_are_fresh_and_bounded() {
    let mut f = Fixture::new(false).await;
    let mut settings = Settings::default();
    settings.tone = "中文简洁".into();
    settings.timezone = "Asia/Shanghai".into();
    f.call(
        "assistant/settings/update",
        json!({"settings":settings}),
        "settings",
        false,
    )
    .await
    .unwrap();
    let s = f
        .call("assistant/settings/read", json!({}), "settings-read", false)
        .await
        .unwrap();
    assert_eq!(s["fresh"], true);
    assert_eq!(s["settings"]["tone"], "中文简洁");
    f.call(
        "assistant/memory/add",
        json!({"text":"明确记忆"}),
        "memory",
        false,
    )
    .await
    .unwrap();
    let s = f.read().await;
    let id = s["memories"][0]["id"].clone();
    f.call(
        "assistant/memory/update",
        json!({"memoryId":id,"text":"修改记忆"}),
        "edit",
        false,
    )
    .await
    .unwrap();
    for n in 0..270 {
        f.call(
            "assistant/settings/update",
            json!({"settings":settings}),
            &format!("settings-{n}"),
            false,
        )
        .await
        .unwrap();
    }
    let s = f.read().await;
    assert_eq!(s["events"].as_array().unwrap().len(), 256);
    let cursor = s["eventCursor"].as_u64().unwrap();
    assert!(s["events"][0]["sequence"].as_u64().unwrap() > 1);
    assert_eq!(s["fresh"], true);
    assert_eq!(f.actor.as_ref().unwrap().snapshot()["fresh"], false);
    let next = f
        .call(
            "assistant/read",
            json!({"after":cursor}),
            "after-read",
            false,
        )
        .await
        .unwrap();
    assert_eq!(next["events"], json!([]));
    assert!(
        f.call(
            "assistant/read",
            json!({"after":"invalid"}),
            "bad-read",
            false
        )
        .await
        .is_err()
    );
    f.stop().await;
    f.start().await;
    let s = f.read().await;
    assert_eq!(s["settings"]["tone"], "中文简洁");
    assert_eq!(s["memories"][0]["text"], "修改记忆");
    f.call(
        "assistant/memory/delete",
        json!({"memoryId":id}),
        "delete",
        false,
    )
    .await
    .unwrap();
    assert_eq!(f.read().await["memories"], json!([]));
    f.stop().await;
}

#[tokio::test]
async fn buffered_native_completion_precedes_next_user_message_and_replay_does_not_duplicate() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.complete(&cm, "previous result");
    let s = f.send("next-phone").await;
    let messages = s["messages"].as_array().unwrap();
    assert_eq!(
        messages
            .iter()
            .map(|m| m["role"].as_str().unwrap())
            .collect::<Vec<_>>(),
        vec!["User", "Assistant", "User"]
    );
    f.stop().await;
    f.start().await;
    f.send("next-phone").await;
    assert_eq!(f.read().await["messages"].as_array().unwrap().len(), 3);
    f.stop().await;
}

#[test]
fn host_reply_and_receipt_validate_identity_generation_and_unwrap_exactly_once() {
    assert_eq!(
        checked_envelope(
            &json!("rpc"),
            json!({"id":"rpc","result":{"thread":{"id":"native"}}})
        )
        .unwrap(),
        json!({"thread":{"id":"native"}})
    );
    for envelope in [
        json!({"id":"other","result":{}}),
        json!({"id":"rpc","error":{"code":-1}}),
        json!({"id":"rpc","result":{},"error":{}}),
        json!({"id":"rpc"}),
    ] {
        assert!(checked_envelope(&json!("rpc"), envelope).is_err());
    }
    let receipt = RuntimeReceipt {
        generation: "host".into(),
        state: "completed".into(),
        response: Some(json!({"id":"rpc","result":{}})),
    };
    assert_eq!(
        checked_receipt(&json!("rpc"), "host", receipt.clone())
            .unwrap()
            .response,
        receipt.response
    );
    assert!(checked_receipt(&json!("other"), "host", receipt.clone()).is_err());
    assert!(checked_receipt(&json!("rpc"), "other", receipt).is_err());
}

#[tokio::test]
async fn complete_params_unsupported_before_dispatch_is_typed_and_never_a_fake_success() {
    let mut f = Fixture::new(false).await;
    f.io.0.lock().unwrap().unsupported = Some("thread/start".into());
    let s = f.send("phone").await;
    assert_eq!(f.io.count("thread/start"), 0);
    assert_eq!(f.io.count("turn/start"), 0);
    assert_eq!(s["reconciliationRequired"], false);
    assert_eq!(s["unsupportedOperation"], "thread/start");
    assert_eq!(Fixture::op(&s, "Coordinator")["phase"], "Paused");
    f.stop().await;
}

#[tokio::test]
async fn actual_worker_remote_stop_preserves_timer_and_offline_occurrences_are_skipped() {
    let mut f = Fixture::new(true).await;
    f.call("assistant/schedule/create",json!({"threadId":"desktop-thread","spec":{"kind":{"Once":{"at_ms":1_001_000}},"zone":{"name":"Asia/Shanghai"},"prompt":"scheduled report","writes":true}}),"once",true).await.unwrap();
    f.handoff
        .as_ref()
        .unwrap()
        .apply_remote_preference(false, |_| {});
    f.at(1_001_000);
    let s = f.read().await;
    assert_eq!(s["tasks"][0]["status"], "running");
    assert!(f.actor.as_ref().unwrap().is_alive());
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    f.io.complete(&em, "scheduled result");
    f.read().await;
    f.call("assistant/schedule/create",json!({"threadId":"desktop-thread","spec":{"kind":{"Interval":{"anchor_ms":1_002_000,"every_ms":1000}},"zone":{"name":"UTC"},"prompt":"interval report","writes":true}}),"interval",true).await.unwrap();
    f.stop().await;
    f.at(1_010_000);
    f.start().await;
    let s = f.read().await;
    assert_eq!(s["tasks"].as_array().unwrap().len(), 1);
    assert!(
        s["schedules"]
            .as_array()
            .unwrap()
            .iter()
            .any(|v| v["missed"] == true)
    );
    f.call("assistant/pause", json!({}), "pause", false)
        .await
        .unwrap();
    f.at(1_011_000);
    f.read().await;
    let count = f.io.count("turn/start");
    f.call("assistant/resume", json!({}), "resume", false)
        .await
        .unwrap();
    assert_eq!(
        f.io.count("turn/start"),
        count,
        "releasing pause must not catch up a missed interval"
    );
    f.at(1_012_000);
    let s = f.read().await;
    assert_eq!(s["tasks"].as_array().unwrap().len(), 2);
    assert!(f.io.count("turn/start") > count);
    f.stop().await;
}

#[test]
fn local_timezone_rules_skip_dst_gap_and_choose_first_fold() {
    use chrono::TimeZone;
    let at = |date: &str| {
        chrono::DateTime::parse_from_rfc3339(date)
            .unwrap()
            .timestamp_millis()
    };
    let before = at("2026-03-07T12:00:00Z");
    let zone = zone_rules("America/New_York", before).unwrap();
    let mut spec = ScheduleSpec {
        kind: ScheduleKind::Daily {
            hour: 2,
            minute: 30,
        },
        zone,
        prompt: "report".into(),
        directory: PathBuf::from("D:\\project"),
        writes: false,
    };
    assert_eq!(
        spec.next_after(before).unwrap(),
        Some(at("2026-03-09T06:30:00Z"))
    );
    spec.kind = ScheduleKind::Daily {
        hour: 1,
        minute: 30,
    };
    assert_eq!(
        spec.next_after(at("2026-10-31T12:00:00Z")).unwrap(),
        Some(at("2026-11-01T05:30:00Z"))
    );
    let shanghai: chrono_tz::Tz = "Asia/Shanghai".parse().unwrap();
    assert_eq!(
        shanghai
            .timestamp_millis_opt(before)
            .single()
            .unwrap()
            .offset()
            .to_string(),
        "CST"
    );
    assert!(zone_rules("Not/AZone", before).is_err());
}

#[tokio::test]
async fn legacy_timeline_migration_and_command_replay_preserve_single_user_message() {
    let mut f = Fixture::new(false).await;
    f.stop().await;
    let db = Connection::open(f.root.path().join("assistant/messages.db")).unwrap();
    db.execute(
        "INSERT INTO messages(id,text,at_ms) VALUES('old','legacy',900000)",
        [],
    )
    .unwrap();
    drop(db);
    f.start().await;
    f.send("phone").await;
    f.stop().await;
    f.start().await;
    f.send("phone").await;
    let s = f.read().await;
    assert_eq!(
        s["messages"].as_array().unwrap().len(),
        2,
        "opening must not migrate an existing new user row a second time"
    );
    f.stop().await;
}

#[tokio::test]
async fn native_multi_question_answers_keep_each_question_id_and_real_values() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    let s = f.create(&cm, 601, "task").await;
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    f.io.emit(json!("multi"),"item/tool/requestUserInput",&em,json!({"itemId":"multi-item","questions":[{"id":"format","question":"format?"},{"id":"audience","question":"audience?"}]}));
    let s = f.read().await;
    let e = Fixture::op(&s, "Execution");
    f.call("assistant/respond",json!({"operationId":e["id"],"requestId":e["question"]["request_id"],"response":{"Answer":"{\"format\":[\"Markdown\"],\"audience\":[\"Team\"]}"}}),"multi-answer",false).await.unwrap();
    assert_eq!(
        f.io.answers()[1],
        (
            json!("multi"),
            json!({"answers":{"format":{"answers":["Markdown"]},"audience":{"answers":["Team"]}}})
        )
    );
    f.stop().await;
}

#[tokio::test]
async fn review_invalid_multi_answer_can_be_corrected_without_a_native_attempt() {
    let mut f = Fixture::new(false).await;
    let s = f.send("review-phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    let s = f.create(&cm, 701, "task").await;
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    f.io.emit(json!("review-multi"),"item/tool/requestUserInput",&em,json!({"itemId":"multi-item","questions":[{"id":"format","question":"format?"},{"id":"audience","question":"audience?"}]}));
    let s = f.read().await;
    let e = Fixture::op(&s, "Execution");
    let before = f.io.answers().len();
    assert!(f.call("assistant/respond",json!({"operationId":e["id"],"requestId":e["question"]["request_id"],"response":{"Answer":"Markdown"}}),"review-invalid",false).await.is_err());
    assert_eq!(f.io.answers().len(), before);
    f.call("assistant/respond",json!({"operationId":e["id"],"requestId":e["question"]["request_id"],"response":{"Answer":"{\"format\":[\"Markdown\"],\"audience\":[\"Team\"]}"}}),"review-corrected",false).await.expect("Local rejection must not occupy the question's answer intent");
    assert_eq!(f.io.answers().last().unwrap().0, json!("review-multi"));
    assert_eq!(f.io.answers().len(), before + 1);
    f.stop().await;
}

#[tokio::test]
async fn review_project_writes_use_source_projection_with_normal_native_thread_metadata() {
    let mut f = Fixture::new(false).await;
    assert!(
        f.io.0.lock().unwrap().threads["desktop-thread"]
            .get("sandboxPolicy")
            .is_none()
    );
    let s = f.send("review-real-policy").await;
    assert_eq!(
        s["projectAuthorization"][0]["writable"], true,
        "Native Thread metadata has no policy; the effective source projection must supply it"
    );
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    let s = f.create(&cm, 703, "authorized writer").await;
    assert_eq!(s["tasks"][0]["status"], "running");
    f.stop().await;
}

#[tokio::test]
async fn review_held_dynamic_question_and_native_question_keep_separate_answers() {
    let mut f = Fixture::new(false).await;
    let s = f.send("review-phone").await;
    let c = Fixture::op(&s, "Coordinator");
    let cm = Fixture::mapping(&c);
    f.io.tool(
        json!(702),
        &cm,
        "review-held-call",
        "assistant_request_input",
        json!({"request_id":"review-held-question","text":"held question"}),
    );
    assert_eq!(
        Fixture::op(&f.read().await, "Coordinator")["question"]["request_id"],
        "review-held-question"
    );
    f.io.emit(json!("review-native"),"item/tool/requestUserInput",&cm,json!({"itemId":"native-item","questions":[{"id":"native-q","question":"native question"}]}));
    let s = f.read().await;
    let waiting = Fixture::op(&s, "Coordinator");
    assert_eq!(
        waiting["question"]["request_id"], "review-held-question",
        "A native pending request must not overwrite a held dynamic question"
    );
    f.stop().await;
    f.start().await;
    assert_eq!(
        Fixture::op(&f.read().await, "Coordinator")["question"]["request_id"],
        "review-held-question",
        "Both pending channels must stay paired across worker restart"
    );
    assert!(f.call("assistant/respond",json!({"operationId":c["id"],"requestId":"s:review-native","response":{"Answer":"native answer"}}),"review-wrong-channel",false).await.is_err());
    f.call("assistant/respond",json!({"operationId":c["id"],"requestId":"review-held-question","response":{"Answer":"held answer"}}),"review-held-answer",false).await.unwrap();
    assert_eq!(f.io.answers().last().unwrap().0, json!(702));
    let s = f.read().await;
    let native = Fixture::op(&s, "Coordinator");
    assert_eq!(native["question"]["request_id"], "s:review-native");
    f.call("assistant/respond",json!({"operationId":c["id"],"requestId":native["question"]["request_id"],"response":{"Answer":"native answer"}}),"review-native-answer",false).await.unwrap();
    assert_eq!(
        f.io.answers().last().unwrap(),
        &(
            json!("review-native"),
            json!({"answers":{"native-q":{"answers":["native answer"]}}})
        )
    );
    f.stop().await;
}

#[cfg(windows)]
#[tokio::test]
async fn review_broker_worker_source_permission_wiring_revocation_and_restore() {
    let mut f = Fixture::new(false).await;
    let state = tempfile::tempdir().unwrap();
    let config = crate::config::Config::create("wss://example.org/pocket".into()).unwrap();
    let (broker, events) = crate::broker::Broker::open(config, state.path().into()).unwrap();
    let event_task = tokio::spawn(broker.clone().receive_events(events));
    let mut peer = broker
        .assistant_source_test_peer("runtime", "desktop")
        .await;
    let reader = project_permission_reader(state.path())
        .unwrap()
        .expect("Production registration missing");
    f.io.0.lock().unwrap().permission_reader = Some(reader);
    let settings = Arc::new(Mutex::new(
        json!({"sandboxPolicy":{"type":"workspaceWrite"},"approvalPolicy":"on-request","approvalsReviewer":"user","activePermissionProfile":null}),
    ));
    let native_settings = settings.clone();
    let root = f.project.path().to_owned();
    let count = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let reads = count.clone();
    let native_process =
        pocket_core::native_process::NativeProcess::capture(std::process::id()).unwrap();
    let expected_process = native_process.clone();
    let caps = crate::capabilities::Capabilities::from_schema(
        &serde_json::from_str(include_str!(
            "../../../bridge/tests/fixtures/ClientRequest.json"
        ))
        .unwrap(),
    );
    let source_task = tokio::spawn(async move {
        loop {
            let request = peer.recv().await;
            let result = match request["method"].as_str().unwrap() {
                "pocket/status" => {
                    json!({"runtimeId":"runtime","nativeProcess":native_process,"initialized":true,"schemaState":"ready","permissionProtocol":1,"nativeCapabilities":caps})
                }
                "thread/read" => {
                    assert_eq!(request["params"]["threadId"], "desktop-thread");
                    json!({"thread":{"id":"desktop-thread","cwd":root,"status":{"type":"idle"}}})
                }
                "pocket/threadSettings" => {
                    reads.fetch_add(1, Ordering::SeqCst);
                    let settings = native_settings.lock().unwrap().clone();
                    json!({"threadId":"desktop-thread","known":!settings.is_null(),"settings":settings})
                }
                method => panic!("Source permission read must not mutate the source: {method}"),
            };
            peer.reply(&request, result).await;
        }
    });
    let s = f.send("broker-proof-send").await;
    assert_eq!(s["projectAuthorization"][0]["writable"], true);
    assert_eq!(s["projectAuthorization"][0]["sourceRuntimeId"], "runtime");
    let generation = s["projectAuthorization"][0]["sourceGeneration"].clone();
    assert!(
        generation
            .as_str()
            .unwrap()
            .contains(&expected_process.created_at.to_string())
    );
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    let s = f.create(&cm, 910, "writer with actual source proof").await;
    assert_eq!(s["tasks"][0]["status"], "running");
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    f.create(&cm, 911, "queued writer").await;
    let starts = f.io.count("turn/start");
    settings.lock().unwrap()["sandboxPolicy"] = json!({"type":"readOnly"});
    f.io.complete(&em, "finished");
    let s = f.read().await;
    assert_eq!(s["projectAuthorization"][0]["writable"], false);
    assert_eq!(s["tasks"][1]["status"], "paused");
    assert_eq!(f.io.count("turn/start"), starts);
    f.stop().await;
    settings.lock().unwrap()["sandboxPolicy"] = json!({"type":"workspaceWrite"});
    f.start().await;
    assert_eq!(
        f.read().await["projectAuthorization"][0]["writable"],
        false,
        "Restored permission must not silently undo a durable revocation"
    );
    // Explicit source selection can establish a new grant. Unknown readback
    // after that selection must remove usable grants on queued work.
    f.send("broker-new-grant").await;
    let s = f
        .create(&cm, 912, "writer after deliberate reauthorization")
        .await;
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    f.create(&cm, 913, "queued while source goes unknown").await;
    let starts = f.io.count("turn/start");
    *settings.lock().unwrap() = Value::Null;
    f.io.complete(&em, "finished again");
    let s = f.read().await;
    assert_eq!(s["projectAuthorization"][0]["verified"], false);
    assert_eq!(f.io.count("turn/start"), starts);
    assert!(count.load(Ordering::SeqCst) >= 6);
    f.stop().await;
    source_task.abort();
    event_task.abort();
}

#[tokio::test]
async fn review_invalid_dynamic_answer_and_unoffered_approval_can_be_corrected() {
    let mut f = Fixture::new(false).await;
    let s = f.send("preflight-send").await;
    let c = Fixture::op(&s, "Coordinator");
    let cm = Fixture::mapping(&c);
    f.io.tool(
        json!(920),
        &cm,
        "escaped-answer-call",
        "assistant_request_input",
        json!({"request_id":"escaped-answer","text":"answer"}),
    );
    f.read().await;
    let p = json!({"operationId":c["id"],"requestId":"escaped-answer","response":{"Answer":"\\".repeat(40000)}});
    assert!(
        f.call("assistant/respond", p, "oversize-answer", false)
            .await
            .is_err()
    );
    assert!(
        f.io.answers().is_empty(),
        "Encoded oversize is local rejection before answer transport"
    );
    f.call("assistant/respond", json!({"operationId":c["id"],"requestId":"escaped-answer","response":{"Answer":"corrected"}}), "corrected-dynamic", false).await.unwrap();
    assert_eq!(f.io.answers().len(), 1);
    let s = f.create(&cm, 921, "approval task").await;
    let e = Fixture::op(&s, "Execution");
    let em = Fixture::mapping(&e);
    f.io.emit(
        json!(922),
        "item/commandExecution/requestApproval",
        &em,
        json!({"itemId":"cmd","availableDecisions":["decline"],"command":"write"}),
    );
    let e = Fixture::op(&f.read().await, "Execution");
    assert!(f.call("assistant/respond", json!({"operationId":e["id"],"requestId":e["question"]["request_id"],"response":{"Approval":{"approved":true}}}), "unoffered-approval", false).await.is_err());
    assert_eq!(f.io.answers().len(), 2);
    f.call("assistant/respond", json!({"operationId":e["id"],"requestId":e["question"]["request_id"],"response":{"Approval":{"approved":false}}}), "corrected-approval", false).await.unwrap();
    assert_eq!(
        f.io.answers().last().unwrap(),
        &(json!(922), json!({"decision":"decline"}))
    );
    f.stop().await;
}

#[tokio::test]
async fn review_worker_source_binding_changes_and_named_profiles_remove_write_grants() {
    for changed in [
        "generation",
        "thread",
        "cwd",
        "runtime",
        "revision",
        "named-profile",
    ] {
        let mut f = Fixture::new(false).await;
        let other = tempfile::tempdir().unwrap();
        let s = f.send("binding-send").await;
        let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
        let s = f.create(&cm, 930, "first writer").await;
        let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
        f.create(&cm, 931, "queued writer").await;
        let starts = f.io.count("turn/start");
        {
            let mut state = f.io.0.lock().unwrap();
            let proof = state.permissions.get_mut("desktop-thread").unwrap();
            match changed {
                "generation" => proof.generation = "replacement-generation".into(),
                "thread" => proof.thread_id = "other".into(),
                "cwd" => proof.cwd = other.path().into(),
                "runtime" => proof.runtime_id = "other".into(),
                "revision" => proof.revision.clear(),
                _ => proof.settings["activePermissionProfile"] = json!({"id":"named-profile"}),
            }
        }
        f.io.complete(&em, "done");
        let s = f.read().await;
        assert_eq!(
            f.io.count("turn/start"),
            starts,
            "{changed} cannot authorize another writer"
        );
        assert_eq!(s["tasks"][1]["status"], "paused");
        if changed == "named-profile" {
            assert_eq!(s["projectAuthorization"][0]["writable"], false);
        } else {
            assert_eq!(s["projectAuthorization"][0]["verified"], false);
        }
        assert!(
            f.call(
                "assistant/task/resume",
                json!({"taskId":s["tasks"][1]["id"]}),
                "binding-resume",
                false
            )
            .await
            .is_err()
        );
        f.stop().await;
        f.start().await;
        assert_eq!(
            f.io.count("turn/start"),
            starts,
            "Restoring must not bypass {changed}"
        );
        f.stop().await;
    }
}

#[tokio::test]
async fn idle_polls_are_coalesced_and_explicit_reads_wake_immediately() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    assert_eq!(s["fresh"], true);
    let reads = f.io.count("thread/turns/list");
    tokio::time::sleep(Duration::from_millis(1200)).await;
    let additional = f.io.count("thread/turns/list") - reads;
    assert!(
        additional <= 6,
        "500ms coalescing must bound native status reads; saw {additional}"
    );
    assert_eq!(f.io.count("turn/start"), 1);
    let started = std::time::Instant::now();
    f.read().await;
    assert!(
        started.elapsed() < Duration::from_millis(450),
        "explicit read should wake rather than wait for timer"
    );
    f.stop().await;
}

#[tokio::test]
async fn lost_create_tool_answer_fences_queued_dispatch_until_authoritative_resolution() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.0.lock().unwrap().lose_answer = true;
    let s = f.create(&cm, 701, "queued behind unknown answer").await;
    assert_eq!(s["tasks"][0]["status"], "queued");
    assert_eq!(f.io.count("turn/start"), 1);
    f.read().await;
    assert_eq!(f.io.answers().len(), 1);
    assert_eq!(f.io.count("turn/start"), 1);
    f.io.resolve(json!(701));
    let s = f.read().await;
    assert_eq!(s["tasks"][0]["status"], "running");
    assert_eq!(f.io.count("turn/start"), 2);
    f.stop().await;
}

#[tokio::test]
async fn selecting_many_threads_in_same_root_does_not_consume_directory_grants() {
    let mut f = Fixture::new(false).await;
    for n in 0..65 {
        let thread = format!("selected-{n}");
        f.io.selected(&thread, f.project.path());
        f.call(
            "assistant/send",
            json!({"message":"handoff","threadId":thread}),
            &format!("phone-{n}"),
            true,
        )
        .await
        .unwrap();
    }
    assert_eq!(
        f.read().await["projectAuthorization"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    f.stop().await;
    f.start().await;
    assert_eq!(
        f.read().await["projectAuthorization"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    f.stop().await;
}

#[test]
fn native_answer_receipt_requires_confirmed_written_result() {
    for response in [
        None,
        Some(json!({"id":7,"error":{"code":-1}})),
        Some(json!({"id":7,"result":{"written":false}})),
    ] {
        assert!(
            checked_answer(
                &json!(7),
                "host",
                RuntimeReceipt {
                    generation: "host".into(),
                    state: "completed".into(),
                    response
                }
            )
            .is_err()
        );
    }
    assert!(
        checked_answer(
            &json!(7),
            "host",
            RuntimeReceipt {
                generation: "host".into(),
                state: "completed".into(),
                response: Some(json!({"id":7,"result":{"written":true}}))
            }
        )
        .is_ok()
    );
}
