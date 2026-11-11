use super::*;
use serde_json::json;
#[derive(Clone)]
struct Fake {
    calls: Vec<(Value, String, Value)>,
    receipts: BTreeMap<String, RuntimeReceipt>,
    answers: Vec<(Value, Value)>,
    lose_method: Option<String>,
    lose_answer: bool,
    models: Value,
    turns: BTreeMap<String, Vec<Value>>,
    batch: Vec<Value>,
    online: bool,
    unsupported_method: Option<String>,
}
impl Default for Fake {
    fn default() -> Self {
        Self {
            calls: vec![],
            receipts: BTreeMap::new(),
            answers: vec![],
            lose_method: None,
            lose_answer: false,
            models: json!({"data":[{"id":"m","model":"m","hidden":false,"isDefault":true}],"nextCursor":null}),
            turns: BTreeMap::new(),
            batch: vec![],
            online: true,
            unsupported_method: None,
        }
    }
}
impl RuntimeIo for Fake {
    fn request(&mut self, id: Value, method: &str, params: Value) -> Result<Value> {
        if self.unsupported_method.as_deref() == Some(method) {
            return Err(RuntimeUnsupported {
                method: method.into(),
            }
            .into());
        }
        self.calls.push((id.clone(), method.into(), params.clone()));
        let n = self.calls.len();
        let response = match method {
            "model/list" => self.models.clone(),
            "thread/start" => {
                json!({"thread":{"id":format!("native-thread-{n}"),"preview":"thread"},"model":params["model"],"cwd":params["cwd"],"approvalPolicy":"on-request","approvalsReviewer":"user","sandbox":{"type":if params["sandbox"]=="workspace-write"{"workspaceWrite"}else{"readOnly"}}})
            }
            "thread/resume" => {
                json!({"thread":{"id":params["threadId"],"preview":"thread"},"model":params["model"],"cwd":params["cwd"],"approvalPolicy":"on-request","approvalsReviewer":"user","sandbox":{"type":if params["sandbox"]=="workspace-write"{"workspaceWrite"}else{"readOnly"}}})
            }
            "thread/read" => {
                let active = self
                    .turns
                    .get(params["threadId"].as_str().unwrap())
                    .is_some_and(|turns| turns.iter().any(|t| t["status"] == "inProgress"));
                json!({"thread":{"id":params["threadId"],"preview":"thread","status":{"type":if active {"active"}else{"idle"},"activeFlags":[]}}})
            }
            "turn/start" => {
                let turn =
                    json!({"id":format!("native-turn-{n}"),"items":[],"status":"inProgress"});
                self.turns
                    .entry(params["threadId"].as_str().unwrap().into())
                    .or_default()
                    .push(turn.clone());
                json!({"turn":turn})
            }
            "thread/turns/list" => {
                json!({"data":self.turns.get(params["threadId"].as_str().unwrap()).cloned().unwrap_or_default(),"nextCursor":null})
            }
            "thread/list" => {
                json!({"data":[{"id":"desktop-thread","preview":"existing history"}],"nextCursor":null})
            }
            _ => json!({}),
        };
        self.receipts.insert(
            native_key(&id)?,
            RuntimeReceipt {
                generation: "host-1".into(),
                state: "completed".into(),
                response: Some(json!({"id":id,"result":response})),
            },
        );
        if self.lose_method.as_deref() == Some(method) {
            self.lose_method = None;
            anyhow::bail!("Lost receipt");
        }
        Ok(response)
    }
    fn receipt(&mut self, id: Value) -> Result<RuntimeReceipt> {
        Ok(self
            .receipts
            .get(&native_key(&id)?)
            .cloned()
            .unwrap_or(RuntimeReceipt {
                generation: "host-1".into(),
                state: "unknown".into(),
                response: None,
            }))
    }
    fn answer(&mut self, id: Value, result: Value) -> Result<()> {
        self.answers.push((id, result));
        if std::mem::take(&mut self.lose_answer) {
            anyhow::bail!("Lost answer receipt");
        }
        Ok(())
    }
    fn events(&mut self, after: u64, _: usize) -> Result<RuntimeEvents> {
        let messages = std::mem::take(&mut self.batch);
        Ok(RuntimeEvents {
            generation: "host-1".into(),
            next_after: after + messages.len() as u64,
            snapshot_required: false,
            events: messages
                .into_iter()
                .enumerate()
                .map(|(i, message)| RuntimeEvent {
                    sequence: after + i as u64 + 1,
                    message,
                })
                .collect(),
            pending_requests: vec![],
        })
    }
    fn health(&mut self) -> Result<RuntimeHealth> {
        Ok(RuntimeHealth {
            runtime_id: "runtime-1".into(),
            generation: "host-1".into(),
            online: self.online,
            initialized: true,
            protocol_verified: true,
            reconciliation_required: false,
        })
    }
}

struct Fixture {
    root: tempfile::TempDir,
    executor: HostExecutor<Fake>,
}
impl Fixture {
    fn new() -> Self {
        let root = tempfile::tempdir().unwrap();
        let policy = ExecutorPolicy {
            coordinator_directory: root.path().into(),
            grants: vec![DirectoryGrant {
                root: root.path().into(),
                writable: true,
            }],
        };
        let executor =
            HostExecutor::open(&root.path().join("adapter.db"), Fake::default(), policy).unwrap();
        Self { root, executor }
    }
    fn req(&self, id: &str, role: Role) -> StartRequest {
        StartRequest {
            operation_id: id.into(),
            host_generation: "host-1".into(),
            role,
            thread_id: None,
            prompt: "Produce report".into(),
            directory: (role == Role::Execution).then(|| self.root.path().to_owned()),
            writes: role == Role::Execution,
            settings: Settings::default(),
            instructions: "Entrusted task instructions".into(),
        }
    }
    fn start(&mut self, id: &str, role: Role) -> NativeMapping {
        self.executor.start(self.req(id, role)).unwrap()
    }
    fn event(
        &mut self,
        seq: u64,
        id: Value,
        method: &str,
        m: &NativeMapping,
        more: Value,
    ) -> EventProjection {
        let mut params = json!({"threadId":m.thread_id,"turnId":m.turn_id});
        params
            .as_object_mut()
            .unwrap()
            .extend(more.as_object().unwrap().clone());
        self.executor
            .ingest(
                "host-1",
                RuntimeEvent {
                    sequence: seq,
                    message: json!({"id":id,"method":method,"params":params}),
                },
            )
            .unwrap()
    }
    fn status(&mut self, id: &str, m: Option<NativeMapping>) -> ExecutionStatus {
        self.executor
            .status(StatusRequest {
                operation_id: id.into(),
                mapping: m,
                host_generation: "host-1".into(),
            })
            .unwrap()
    }
}

#[test]
fn execution_sandbox_only_grants_canonical_authorized_root() {
    let mut f = Fixture::new();
    let m = f.start("task", Role::Execution);
    let turn = f
        .executor
        .runtime
        .calls
        .iter()
        .find(|(_, method, _)| method == "turn/start")
        .unwrap();
    assert_eq!(
        turn.2["sandboxPolicy"]["writableRoots"],
        json!([dunce::canonicalize(f.root.path()).unwrap()])
    );
    assert_eq!(turn.2["sandboxPolicy"]["excludeSlashTmp"], true);
    assert_eq!(turn.2["sandboxPolicy"]["excludeTmpdirEnvVar"], true);
    assert_eq!(turn.2["approvalsReviewer"], "user");
    assert_eq!(turn.2["approvalPolicy"], "on-request");
    assert!(
        !f.executor
            .runtime
            .calls
            .iter()
            .find(|(_, method, _)| method == "thread/start")
            .unwrap()
            .2
            .as_object()
            .unwrap()
            .contains_key("dynamicTools")
    );
    assert!(matches!(
        f.status("task", Some(m)),
        ExecutionStatus::Running(..)
    ));
}

#[test]
fn unlisted_model_and_untrusted_path_are_proven_not_dispatched() {
    let mut f = Fixture::new();
    let mut req = f.req("model", Role::Execution);
    req.settings.model = Some("invented-model".into());
    assert!(f.executor.start(req).is_err());
    assert!(
        !f.executor
            .runtime
            .calls
            .iter()
            .any(|(_, m, _)| m == "thread/start" || m == "turn/start")
    );
    assert_eq!(f.status("model", None), ExecutionStatus::NotDispatched);
    let other = tempfile::tempdir().unwrap();
    let mut req = f.req("directory", Role::Execution);
    req.directory = Some(other.path().into());
    assert!(f.executor.start(req).is_err());
    assert_eq!(f.status("directory", None), ExecutionStatus::NotDispatched);
}

#[test]
fn review_resume_refuses_a_new_manual_turn_after_the_owned_turn_stopped() {
    let mut f = Fixture::new();
    let old = f.start("entrusted", Role::Execution);
    let turns = f.executor.runtime.turns.get_mut(&old.thread_id).unwrap();
    turns[0]["status"] = json!("interrupted");
    turns.push(json!({"id":"manual-active","status":"inProgress","items":[]}));
    let before = f.executor.runtime.calls.len();
    let mut request = f.req("explicit-resume", Role::Execution);
    request.thread_id = Some(old.thread_id.clone());
    assert!(
        f.executor.resume(request).is_err(),
        "A new manual turn must keep the entrusted task paused"
    );
    assert!(
        !f.executor.runtime.calls[before..]
            .iter()
            .any(|(_, method, _)| matches!(method.as_str(), "thread/resume" | "turn/start"))
    );
    assert_eq!(
        f.status("explicit-resume", None),
        ExecutionStatus::NotDispatched
    );
    assert_eq!(
        f.executor.runtime.turns[&old.thread_id][1]["status"],
        "inProgress"
    );
}

#[test]
fn lost_turn_receipt_is_recovered_using_identical_id_without_resend() {
    let mut f = Fixture::new();
    f.executor.runtime.lose_method = Some("turn/start".into());
    let req = f.req("lost", Role::Execution);
    assert!(f.executor.start(req.clone()).is_err());
    let result = f.executor.start(req).unwrap();
    assert_eq!(
        f.executor
            .runtime
            .calls
            .iter()
            .filter(|(_, m, _)| m == "turn/start")
            .count(),
        1
    );
    assert!(matches!(
        f.status("lost", Some(result)),
        ExecutionStatus::Running(..)
    ));
}

#[test]
fn missing_and_cold_receipts_remain_unknown_across_reopen() {
    let mut f = Fixture::new();
    f.executor.runtime.lose_method = Some("turn/start".into());
    assert!(f.executor.start(f.req("lost", Role::Execution)).is_err());
    let turn_id = f.executor.operation_records()[0].turn_rpc_id.clone();
    f.executor.runtime.receipts.insert(
        native_key(&turn_id).unwrap(),
        RuntimeReceipt {
            generation: "host-1".into(),
            state: "completed".into(),
            response: None,
        },
    );
    let fake = f.executor.runtime.clone();
    let policy = f.executor.policy.clone();
    let path = f.root.path().join("adapter.db");
    drop(f.executor);
    let mut executor = HostExecutor::open(&path, fake, policy).unwrap();
    assert_eq!(
        executor
            .status(StatusRequest {
                operation_id: "lost".into(),
                mapping: None,
                host_generation: "host-1".into()
            })
            .unwrap(),
        ExecutionStatus::Unknown
    );
    assert_eq!(
        executor
            .runtime
            .calls
            .iter()
            .filter(|(_, m, _)| m == "turn/start")
            .count(),
        1
    );
}

#[test]
fn finish_malformed_completed_receipts_never_bind_unknown_turn() {
    for shape in ["both", "invalid_state", "wrong_id"] {
        let mut f = Fixture::new();
        f.executor.runtime.lose_method = Some("turn/start".into());
        assert!(
            f.executor
                .start(f.req("malformed", Role::Execution))
                .is_err()
        );
        let rpc = f.executor.operation_records()[0].turn_rpc_id.clone();
        let mut receipt = f.executor.runtime.receipts[&native_key(&rpc).unwrap()].clone();
        match shape {
            "both" => receipt.response.as_mut().unwrap()["error"] = Value::Null,
            "invalid_state" => receipt.state = "fabricated".into(),
            "wrong_id" => receipt.response.as_mut().unwrap()["id"] = json!("foreign"),
            _ => unreachable!(),
        }
        f.executor
            .runtime
            .receipts
            .insert(native_key(&rpc).unwrap(), receipt);
        assert_eq!(
            f.status("malformed", None),
            ExecutionStatus::Unknown,
            "{shape}"
        );
        assert!(f.executor.operation_records()[0].mapping.is_none());
        assert_eq!(
            f.executor
                .runtime
                .calls
                .iter()
                .filter(|(_, m, _)| m == "turn/start")
                .count(),
            1
        );
    }
}

#[test]
fn lost_thread_creation_does_not_automatically_start_a_turn() {
    let mut f = Fixture::new();
    f.executor.runtime.lose_method = Some("thread/start".into());
    assert!(
        f.executor
            .start(f.req("lost-thread", Role::Coordinator))
            .is_err()
    );
    f.status("lost-thread", None);
    assert_eq!(
        f.status("lost-thread", None),
        ExecutionStatus::NotDispatched
    );
    assert!(
        !f.executor
            .runtime
            .calls
            .iter()
            .any(|(_, m, _)| m == "turn/start")
    );
}

#[test]
fn resume_requires_owned_idle_thread_and_keeps_native_identity() {
    let mut f = Fixture::new();
    let m = f.start("coord", Role::Coordinator);
    let mut req = f.req("coord-2", Role::Coordinator);
    req.thread_id = Some(m.thread_id.clone());
    assert!(f.executor.resume(req.clone()).is_err());
    f.executor.runtime.turns.get_mut(&m.thread_id).unwrap()[0]["status"] = json!("completed");
    req.operation_id = "coord-3".into();
    let resumed = f.executor.resume(req).unwrap();
    assert_eq!(resumed.thread_id, m.thread_id);
    assert_ne!(resumed.turn_id, m.turn_id);
    let mut unowned = f.req("unowned", Role::Coordinator);
    unowned.thread_id = Some("desktop-thread".into());
    assert!(f.executor.resume(unowned).is_err());
}

#[test]
fn dynamic_call_uses_native_request_id_separate_from_call_id() {
    let mut f = Fixture::new();
    let m = f.start("coord", Role::Coordinator);
    let projection = f.event(
        1,
        json!(17),
        "item/tool/call",
        &m,
        json!({"callId":"call-1","tool":"assistant_list_sessions","arguments":{"limit":10}}),
    );
    assert!(matches!(
        projection.tools[0].call,
        Some(ToolCall::ListSessions { limit: 10 })
    ));
    f.executor
        .tools(ToolResponse {
            scope: m.clone(),
            call_id: "call-1".into(),
            result: json!({"sessions":[]}),
        })
        .unwrap();
    assert_eq!(f.executor.runtime.answers[0].0, 17);
    assert_eq!(
        f.executor.runtime.answers[0].1["contentItems"][0]["type"],
        "inputText"
    );
    f.executor
        .tools(ToolResponse {
            scope: m,
            call_id: "call-1".into(),
            result: json!({"sessions":[]}),
        })
        .unwrap();
    assert_eq!(f.executor.runtime.answers.len(), 1);
}

#[test]
fn malicious_dynamic_arguments_and_task_calls_are_rejected_without_approval() {
    let mut f = Fixture::new();
    let m = f.start("coord", Role::Coordinator);
    let p=f.event(1,json!("native"),"item/tool/call",&m,json!({"callId":"bad","tool":"assistant_list_sessions","arguments":{"limit":10,"rpc":"approve"}}));
    assert!(p.tools[0].error.is_some());
    f.executor
        .reject_tool(m, "bad", "Unknown argument")
        .unwrap();
    assert_eq!(f.executor.runtime.answers[0].1["success"], false);
    let task = f.start("task", Role::Execution);
    let p=f.event(2,json!("native-2"),"item/tool/call",&task,json!({"callId":"escape","tool":"assistant_create_task","arguments":{"prompt":"x","directory":f.root.path(),"writes":true}}));
    assert!(p.tools[0].error.is_some());
}

#[test]
fn approvals_and_multi_question_answers_preserve_native_ids_and_scope() {
    let mut f = Fixture::new();
    let m = f.start("task", Role::Execution);
    let p=f.event(1,json!(29),"item/commandExecution/requestApproval",&m,json!({"itemId":"command","startedAtMs":0,"command":"write","availableDecisions":["accept","decline"]}));
    let ExecutionStatus::Waiting(_, q) = &p.observations[0].status else {
        panic!()
    };
    assert!(f.executor.runtime.answers.is_empty());
    f.executor
        .respond(ResponseRequest {
            operation_id: "task".into(),
            command_id: "human".into(),
            mapping: m.clone(),
            request_id: q.request_id.clone(),
            response: HumanResponse::Approval { approved: true },
        })
        .unwrap();
    assert_eq!(
        f.executor.runtime.answers[0],
        (json!(29), json!({"decision":"accept"}))
    );
    let p=f.event(2,json!("ask-original"),"item/tool/requestUserInput",&m,json!({"itemId":"ask","isBlocking":true,"questions":[{"id":"a","header":"A","question":"First"},{"id":"b","header":"B","question":"Second"}]}));
    let ExecutionStatus::Waiting(_, q) = &p.observations[0].status else {
        panic!()
    };
    let request = ResponseRequest {
        operation_id: "task".into(),
        command_id: "human-multi".into(),
        mapping: m,
        request_id: q.request_id.clone(),
        response: HumanResponse::Answer("one answer for all".into()),
    };
    assert!(f.executor.respond(request.clone()).is_err());
    f.executor
        .respond(ResponseRequest {
            response: HumanResponse::Answer("{\"a\":[\"one\"],\"b\":[\"two\"]}".into()),
            ..request
        })
        .unwrap();
    assert_eq!(f.executor.runtime.answers[1].0, "ask-original");
    assert_eq!(
        f.executor.runtime.answers[1].1,
        json!({"answers":{"a":{"answers":["one"]},"b":{"answers":["two"]}}})
    );
}

#[test]
fn lost_answer_is_never_retried_and_unmanaged_threads_remain_observed() {
    let mut f = Fixture::new();
    let m = f.start("task", Role::Execution);
    let p = f.event(
        1,
        json!("q"),
        "item/fileChange/requestApproval",
        &m,
        json!({"itemId":"patch","startedAtMs":0}),
    );
    let ExecutionStatus::Waiting(_, q) = &p.observations[0].status else {
        panic!()
    };
    let req = ResponseRequest {
        operation_id: "task".into(),
        command_id: "human".into(),
        mapping: m,
        request_id: q.request_id.clone(),
        response: HumanResponse::Approval { approved: false },
    };
    f.executor.runtime.lose_answer = true;
    assert!(f.executor.respond(req.clone()).is_err());
    assert!(f.executor.respond(req).is_err());
    assert_eq!(f.executor.runtime.answers.len(), 1);
    let unowned = NativeMapping {
        host_generation: "host-1".into(),
        thread_id: "desktop".into(),
        turn_id: "turn".into(),
    };
    assert!(f.executor.interrupt(unowned.clone()).is_err());
    assert!(
        f.event(
            2,
            json!(30),
            "item/tool/requestUserInput",
            &unowned,
            json!({"questions":[]})
        )
        .observations
        .is_empty()
    );
    assert_eq!(
        f.executor.read(ReadRequest::Sessions { limit: 5 }).unwrap()[0].thread_id,
        "desktop-thread"
    );
}

#[test]
fn exact_turn_completion_and_reopen_are_reconciled_without_new_turn() {
    let mut f = Fixture::new();
    let m = f.start("task", Role::Execution);
    f.executor.runtime.turns.get_mut(&m.thread_id).unwrap()[0] = json!({"id":m.turn_id,"status":"completed","items":[{"type":"agentMessage","id":"result","text":"report.md produced"}]});
    let fake = f.executor.runtime.clone();
    let policy = f.executor.policy.clone();
    let path = f.root.path().join("adapter.db");
    drop(f.executor);
    let mut executor = HostExecutor::open(&path, fake, policy).unwrap();
    let status = executor
        .status(StatusRequest {
            operation_id: "task".into(),
            mapping: Some(m),
            host_generation: "host-1".into(),
        })
        .unwrap();
    assert!(matches!(status,ExecutionStatus::Completed(_,s) if s=="report.md produced"));
    assert_eq!(
        executor
            .runtime
            .calls
            .iter()
            .filter(|(_, m, _)| m == "turn/start")
            .count(),
        1
    );
}

#[test]
fn stale_event_and_generation_do_not_change_managed_status() {
    let mut f = Fixture::new();
    let m = f.start("task", Role::Execution);
    let p = f.event(
        10,
        Value::Null,
        "turn/completed",
        &m,
        json!({"turn":{"id":m.turn_id,"status":"interrupted","items":[]}}),
    );
    assert!(matches!(
        p.observations[0].status,
        ExecutionStatus::Interrupted(..)
    ));
    assert!(
        f.event(
            9,
            Value::Null,
            "turn/started",
            &m,
            json!({"turn":{"id":m.turn_id,"status":"inProgress","items":[]}})
        )
        .observations
        .is_empty()
    );
    assert!(f.executor.ingest("other-host",RuntimeEvent{sequence:11,message:json!({"method":"turn/started","params":{"threadId":m.thread_id,"turn":{"id":m.turn_id}}})}).is_err());
}

#[test]
fn service_with_product_adapter_closes_phone_task_question_result_loop() {
    let f = Fixture::new();
    let root = f.root.path().to_owned();
    let shared = SharedHostExecutor::new(f.executor);
    let mut service = AssistantService::open(
        &root.join("service.db"),
        "computer",
        "profile",
        "host-1",
        shared.clone(),
    )
    .unwrap();
    service
        .submit("phone", "Produce report", vec![root.clone()], 0)
        .unwrap();
    service.tick(0).unwrap();
    let coordinator = service
        .operations()
        .into_iter()
        .find(|o| o.role == Role::Coordinator)
        .unwrap();
    let scope = coordinator.mapping.unwrap();
    let event = RuntimeEvent {
        sequence: 1,
        message: json!({"id":100,"method":"item/tool/call","params":{"threadId":scope.thread_id,"turnId":scope.turn_id,"callId":"create","tool":"assistant_create_task","arguments":{"prompt":"Produce report","directory":root,"writes":true}}}),
    };
    let projection = shared.ingest("host-1", event).unwrap();
    let tool = &projection.tools[0];
    service
        .tool(
            tool.scope.clone(),
            &tool.call_id,
            tool.call.clone().unwrap(),
            1,
        )
        .unwrap();
    service.tick(1).unwrap();
    let task = service
        .operations()
        .into_iter()
        .find(|o| o.role == Role::Execution)
        .unwrap();
    let m = task.mapping.clone().unwrap();
    let projection=shared.ingest("host-1",RuntimeEvent{sequence:2,message:json!({"id":"native-question","method":"item/tool/requestUserInput","params":{"threadId":m.thread_id,"turnId":m.turn_id,"itemId":"q","isBlocking":true,"questions":[{"id":"format","header":"Format","question":"Which format?"}]}})}).unwrap();
    for observation in projection.observations {
        service
            .observe(
                &observation.operation_id,
                observation.status,
                &observation.event_id,
                2,
            )
            .unwrap();
    }
    let question = service
        .operations()
        .into_iter()
        .find(|o| o.id == task.id)
        .unwrap()
        .question
        .unwrap();
    service
        .respond(
            "human",
            &task.id,
            &question.request_id,
            HumanResponse::Answer("Markdown".into()),
            3,
        )
        .unwrap();
    let projection=shared.ingest("host-1",RuntimeEvent{sequence:3,message:json!({"method":"turn/completed","params":{"threadId":m.thread_id,"turn":{"id":m.turn_id,"status":"completed","items":[{"type":"agentMessage","id":"final","text":"report.md produced"}]}}})}).unwrap();
    for observation in projection.observations {
        service
            .observe(
                &observation.operation_id,
                observation.status,
                &observation.event_id,
                4,
            )
            .unwrap();
    }
    assert_eq!(service.tasks().unwrap()[0].status, TaskStatus::Done);
    assert_eq!(
        service.tasks().unwrap()[0].detail.as_deref(),
        Some("report.md produced")
    );
    assert_eq!(
        shared
            .pending_requests()
            .unwrap()
            .iter()
            .filter(|p| p.mapping == m)
            .count(),
        0
    );
}

fn schema(name: &str) -> Value {
    let source = match name {
        "v2/ModelListParams" => include_str!("fixtures/ModelListParams.json"),
        "v2/ThreadStartParams" => include_str!("fixtures/ThreadStartParams.json"),
        "v2/TurnStartParams" => include_str!("fixtures/TurnStartParams.json"),
        "DynamicToolCallResponse" => include_str!("fixtures/DynamicToolCallResponse.json"),
        "CommandExecutionRequestApprovalResponse" => {
            include_str!("fixtures/CommandExecutionRequestApprovalResponse.json")
        }
        "FileChangeRequestApprovalResponse" => {
            include_str!("fixtures/FileChangeRequestApprovalResponse.json")
        }
        "PermissionsRequestApprovalResponse" => {
            include_str!("fixtures/PermissionsRequestApprovalResponse.json")
        }
        "ToolRequestUserInputResponse" => {
            include_str!("fixtures/ToolRequestUserInputResponse.json")
        }
        _ => panic!("Unknown native schema"),
    };
    serde_json::from_str(source).unwrap()
}
// Validates the real schema branches used by the adapter, including refs,
// unions, required fields, enum/type and numeric/string bounds.
fn conforms(v: &Value, s: &Value, root: &Value) -> bool {
    if let Some(b) = s.as_bool() {
        return b;
    }
    if let Some(r) = s["$ref"].as_str() {
        return root
            .pointer(r.trim_start_matches('#'))
            .is_some_and(|d| conforms(v, d, root));
    }
    for key in ["anyOf", "oneOf"] {
        if let Some(options) = s[key].as_array() {
            let count = options.iter().filter(|d| conforms(v, d, root)).count();
            if (key == "anyOf" && count == 0) || (key == "oneOf" && count != 1) {
                return false;
            }
        }
    }
    if let Some(all) = s["allOf"].as_array() {
        if !all.iter().all(|d| conforms(v, d, root)) {
            return false;
        }
    }
    if let Some(values) = s["enum"].as_array() {
        if !values.contains(v) {
            return false;
        }
    }
    let type_ok = |t: &str| match t {
        "null" => v.is_null(),
        "string" => v.is_string(),
        "integer" => v.as_i64().is_some() || v.as_u64().is_some(),
        "number" => v.is_number(),
        "array" => v.is_array(),
        "object" => v.is_object(),
        "boolean" => v.is_boolean(),
        _ => true,
    };
    if let Some(t) = s["type"].as_str() {
        if !type_ok(t) {
            return false;
        }
    }
    if let Some(types) = s["type"].as_array() {
        if !types.iter().any(|t| t.as_str().is_some_and(type_ok)) {
            return false;
        }
    }
    if let Some(object) = v.as_object() {
        if let Some(required) = s["required"].as_array() {
            if !required
                .iter()
                .all(|k| object.contains_key(k.as_str().unwrap()))
            {
                return false;
            }
        }
        for (key, value) in object {
            if let Some(field) = s["properties"].get(key) {
                if !conforms(value, field, root) {
                    return false;
                }
            } else if s["additionalProperties"] == false {
                return false;
            } else if s["additionalProperties"].is_object()
                && !conforms(value, &s["additionalProperties"], root)
            {
                return false;
            }
        }
    }
    if let Some(array) = v.as_array() {
        if s.get("items").is_some() && !array.iter().all(|i| conforms(i, &s["items"], root)) {
            return false;
        }
    }
    if let Some(text) = v.as_str() {
        let len = text.chars().count() as u64;
        if s["maxLength"].as_u64().is_some_and(|max| len > max)
            || s["minLength"].as_u64().is_some_and(|min| len < min)
        {
            return false;
        }
    }
    if let Some(n) = v.as_f64() {
        if s["minimum"].as_f64().is_some_and(|m| n < m)
            || s["maximum"].as_f64().is_some_and(|m| n > m)
        {
            return false;
        }
    }
    true
}

#[test]
fn native_packets_and_tool_specs_conform_to_checked_in_app_server_schemas() {
    let mut f = Fixture::new();
    f.start("coord", Role::Coordinator);
    for (_, method, params) in &f.executor.runtime.calls {
        let name = match method.as_str() {
            "model/list" => "v2/ModelListParams",
            "thread/start" => "v2/ThreadStartParams",
            "turn/start" => "v2/TurnStartParams",
            _ => continue,
        };
        let s = schema(name);
        assert!(conforms(params, &s, &s), "{method} does not conform");
    }
    let s = schema("DynamicToolCallResponse");
    let response = protocol::tool_response(&json!({"answer":"explicit"}), true).unwrap();
    assert!(conforms(&response, &s, &s));
    for tool in dynamic_tools() {
        let input = &tool["inputSchema"];
        assert_eq!(input["additionalProperties"], false);
        assert!(!conforms(&json!({"rpc":"inject"}), input, input));
    }
}

#[test]
fn native_human_responses_conform_to_native_approval_and_input_schemas() {
    let mut f = Fixture::new();
    let m = f.start("task", Role::Execution);
    for (index, method, params, response, name) in [
        (
            1,
            "item/commandExecution/requestApproval",
            json!({"itemId":"c","startedAtMs":0}),
            HumanResponse::Approval { approved: true },
            "CommandExecutionRequestApprovalResponse",
        ),
        (
            2,
            "item/fileChange/requestApproval",
            json!({"itemId":"f","startedAtMs":0}),
            HumanResponse::Approval { approved: false },
            "FileChangeRequestApprovalResponse",
        ),
        (
            3,
            "item/permissions/requestApproval",
            json!({"itemId":"p","startedAtMs":0,"cwd":f.root.path(),"permissions":{"network":{"enabled":true}}}),
            HumanResponse::Approval { approved: false },
            "PermissionsRequestApprovalResponse",
        ),
        (
            4,
            "item/tool/requestUserInput",
            json!({"itemId":"q","isBlocking":true,"questions":[{"id":"native-question","header":"H","question":"Q"}]}),
            HumanResponse::Answer("human input".into()),
            "ToolRequestUserInputResponse",
        ),
    ] {
        let p = f.event(index, json!(index), method, &m, params);
        let ExecutionStatus::Waiting(_, q) = &p.observations[0].status else {
            panic!()
        };
        f.executor
            .respond(ResponseRequest {
                operation_id: "task".into(),
                command_id: format!("human-{index}"),
                mapping: m.clone(),
                request_id: q.request_id.clone(),
                response,
            })
            .unwrap();
        let result = &f.executor.runtime.answers.last().unwrap().1;
        let s = schema(name);
        assert!(conforms(result, &s, &s), "{name} mismatch");
    }
}

#[test]
fn resolving_one_of_multiple_pending_requests_preserves_other_question() {
    let mut f = Fixture::new();
    let m = f.start("task", Role::Execution);
    for n in 1..=2 {
        f.event(
            n,
            json!(n),
            "item/fileChange/requestApproval",
            &m,
            json!({"itemId":format!("p-{n}"),"startedAtMs":0}),
        );
    }
    let p=f.executor.ingest("host-1",RuntimeEvent{sequence:3,message:json!({"method":"serverRequest/resolved","params":{"threadId":m.thread_id,"requestId":1}})}).unwrap();
    assert!(
        matches!(&p.observations[0].status,ExecutionStatus::Waiting(_,q) if q.request_id=="n:2")
    );
}

#[test]
fn wrong_receipt_identity_cannot_bind_a_native_turn() {
    let mut f = Fixture::new();
    f.executor.runtime.lose_method = Some("turn/start".into());
    assert!(f.executor.start(f.req("lost", Role::Execution)).is_err());
    let id = f.executor.operation_records()[0].turn_rpc_id.clone();
    let receipt = f
        .executor
        .runtime
        .receipts
        .get_mut(&native_key(&id).unwrap())
        .unwrap();
    receipt.response.as_mut().unwrap()["id"] = json!("another-op");
    assert_eq!(f.status("lost", None), ExecutionStatus::Unknown);
    assert_eq!(
        f.executor
            .runtime
            .calls
            .iter()
            .filter(|(_, m, _)| m == "turn/start")
            .count(),
        1
    );
}

#[test]
fn read_only_coordinator_cannot_receive_manual_permission_expansion() {
    let mut f = Fixture::new();
    let m = f.start("coord", Role::Coordinator);
    let p=f.event(1,json!(7),"item/permissions/requestApproval",&m,json!({"itemId":"p","startedAtMs":0,"cwd":f.root.path(),"permissions":{"network":{"enabled":true}}}));
    let ExecutionStatus::Waiting(_, q) = &p.observations[0].status else {
        panic!()
    };
    assert!(
        f.executor
            .respond(ResponseRequest {
                operation_id: "coord".into(),
                command_id: "approve".into(),
                mapping: m,
                request_id: q.request_id.clone(),
                response: HumanResponse::Approval { approved: true }
            })
            .is_err()
    );
    assert!(f.executor.runtime.answers.is_empty());
}

#[test]
fn adapter_storage_does_not_duplicate_instructions_questions_answers_or_results() {
    let mut f = Fixture::new();
    let mut req = f.req("task", Role::Execution);
    req.instructions = "private-memory-value".into();
    let m = f.executor.start(req).unwrap();
    let p=f.event(1,json!(5),"item/tool/requestUserInput",&m,json!({"itemId":"q","isBlocking":true,"questions":[{"id":"q","header":"h","question":"private-question-value"}]}));
    let ExecutionStatus::Waiting(_, q) = &p.observations[0].status else {
        panic!()
    };
    f.executor
        .respond(ResponseRequest {
            operation_id: "task".into(),
            command_id: "human".into(),
            mapping: m,
            request_id: q.request_id.clone(),
            response: HumanResponse::Answer("private-answer-value".into()),
        })
        .unwrap();
    let data = serde_json::to_string(&f.executor.state).unwrap();
    for secret in [
        "private-memory-value",
        "private-question-value",
        "private-answer-value",
    ] {
        assert!(!data.contains(secret));
    }
}

#[test]
fn current_pending_snapshot_restores_question_after_executor_reopen() {
    let mut f = Fixture::new();
    let m = f.start("task", Role::Execution);
    let message = json!({"id":"native-q","method":"item/tool/requestUserInput","params":{"threadId":m.thread_id,"turnId":m.turn_id,"itemId":"q","isBlocking":true,"questions":[{"id":"q","header":"h","question":"question"}]}});
    f.executor
        .ingest(
            "host-1",
            RuntimeEvent {
                sequence: 1,
                message: message.clone(),
            },
        )
        .unwrap();
    let fake = f.executor.runtime.clone();
    let policy = f.executor.policy.clone();
    let path = f.root.path().join("adapter.db");
    drop(f.executor);
    let mut executor = HostExecutor::open(&path, fake, policy).unwrap();
    assert!(executor.pending_requests().is_empty());
    let p = executor
        .ingest(
            "host-1",
            RuntimeEvent {
                sequence: 0,
                message,
            },
        )
        .unwrap();
    assert!(
        matches!(&p.observations[0].status,ExecutionStatus::Waiting(_,q) if q.request_id=="s:native-q")
    );
}

#[test]
fn uncertain_tool_answer_cannot_be_replaced_by_validation_error() {
    let mut f = Fixture::new();
    let m = f.start("coord", Role::Coordinator);
    f.event(
        1,
        json!(8),
        "item/tool/call",
        &m,
        json!({"callId":"tool","tool":"assistant_list_sessions","arguments":{"limit":1}}),
    );
    assert!(!f.executor.has_tool_answer_attempt(&m, "tool").unwrap());
    f.executor.runtime.lose_answer = true;
    assert!(
        f.executor
            .tools(ToolResponse {
                scope: m.clone(),
                call_id: "tool".into(),
                result: json!({"data":[]})
            })
            .is_err()
    );
    assert!(f.executor.has_tool_answer_attempt(&m, "tool").unwrap());
    assert!(
        f.executor
            .reject_tool(m, "tool", "different error")
            .is_err()
    );
    assert_eq!(f.executor.runtime.answers.len(), 1);
}

#[test]
fn explicitly_selected_directories_can_be_added_and_write_upgraded_without_restart() {
    let root = tempfile::tempdir().unwrap();
    let project = tempfile::tempdir().unwrap();
    let policy = ExecutorPolicy {
        coordinator_directory: root.path().into(),
        grants: vec![],
    };
    let mut executor =
        HostExecutor::open(&root.path().join("adapter.db"), Fake::default(), policy).unwrap();
    executor.authorize_directory(project.path(), false).unwrap();
    assert!(!executor.policy.grants[0].writable);
    executor
        .authorize_directory(&project.path().join("."), false)
        .unwrap();
    assert_eq!(executor.policy.grants.len(), 1);
    assert!(!executor.policy.grants[0].writable);
    executor.authorize_directory(project.path(), true).unwrap();
    assert!(executor.policy.grants[0].writable);
    let mut shared = SharedHostExecutor::new(executor);
    let second = tempfile::tempdir().unwrap();
    shared.authorize_directory(second.path(), false).unwrap();
    let req = StartRequest {
        operation_id: "second-read".into(),
        host_generation: "host-1".into(),
        role: Role::Execution,
        thread_id: None,
        prompt: "read".into(),
        directory: Some(second.path().into()),
        writes: false,
        settings: Settings::default(),
        instructions: "read".into(),
    };
    assert!(shared.start(req).is_ok());
    assert!(
        shared
            .authorize_directory(Path::new("relative"), true)
            .is_err()
    );
}

#[test]
fn service_readonly_getters_report_pause_takeover_and_persistent_coordinator() {
    let f = Fixture::new();
    let root = f.root.path().to_owned();
    let shared = SharedHostExecutor::new(f.executor);
    let mut service = AssistantService::open(
        &root.join("service.db"),
        "computer",
        "profile",
        "host-1",
        shared,
    )
    .unwrap();
    assert!(!service.is_paused());
    assert!(service.coordinator_thread_id().is_none());
    service.submit("phone", "Coordinate", vec![], 0).unwrap();
    service.tick(0).unwrap();
    let thread = service.coordinator_thread_id().unwrap().to_owned();
    assert!(!thread.is_empty());
    service.pause("pause", 1).unwrap();
    assert!(service.is_paused());
    assert_eq!(service.coordinator_thread_id(), Some(thread.as_str()));
    service.release_takeover("release", 2).unwrap();
    assert!(!service.is_paused());
    assert_eq!(service.coordinator_thread_id(), Some(thread.as_str()));
}

#[test]
fn directory_grants_are_bounded_and_existing_root_does_not_consume_a_slot() {
    let root = tempfile::tempdir().unwrap();
    let policy = ExecutorPolicy {
        coordinator_directory: root.path().into(),
        grants: vec![],
    };
    let mut executor =
        HostExecutor::open(&root.path().join("adapter.db"), Fake::default(), policy).unwrap();
    for n in 0..64 {
        let p = root.path().join(format!("project-{n}"));
        std::fs::create_dir(&p).unwrap();
        executor.authorize_directory(&p, false).unwrap();
    }
    assert_eq!(executor.policy.grants.len(), 64);
    executor
        .authorize_directory(&root.path().join("project-0"), true)
        .unwrap();
    assert_eq!(executor.policy.grants.len(), 64);
    assert!(executor.policy.grants[0].writable);
    let extra = root.path().join("extra");
    std::fs::create_dir(&extra).unwrap();
    assert!(executor.authorize_directory(&extra, false).is_err());
}

#[test]
fn unsupported_full_params_are_not_dispatched_or_retried() {
    let mut f = Fixture::new();
    f.executor.runtime.unsupported_method = Some("thread/start".into());
    let error = f
        .executor
        .start(f.req("unsupported", Role::Coordinator))
        .unwrap_err();
    assert!(error.downcast_ref::<RuntimeUnsupported>().is_some());
    assert_eq!(
        f.status("unsupported", None),
        ExecutionStatus::NotDispatched
    );
    assert!(
        !f.executor
            .runtime
            .calls
            .iter()
            .any(|(_, method, _)| method == "thread/start" || method == "turn/start")
    );
}
#[test]
fn coordinator_uses_native_thread_and_turn_protocol_with_read_only_tools() {
    let root = tempfile::tempdir().unwrap();
    let policy = ExecutorPolicy {
        coordinator_directory: root.path().into(),
        grants: vec![],
    };
    let mut executor =
        HostExecutor::open(&root.path().join("adapter.db"), Fake::default(), policy).unwrap();
    let result = executor.start(StartRequest {
        operation_id: "op-1".into(),
        host_generation: "host-1".into(),
        role: Role::Coordinator,
        thread_id: None,
        prompt: "Handle phone command".into(),
        directory: None,
        writes: false,
        settings: Settings::default(),
        instructions: "read-only coordinator".into(),
    });
    assert!(
        result.is_ok(),
        "native adapter must start thread and turn: {result:?}"
    );
    let thread = executor
        .runtime
        .calls
        .iter()
        .find(|(_, m, _)| m == "thread/start")
        .unwrap();
    assert_eq!(thread.2["sandbox"], "read-only");
    assert_eq!(thread.2["dynamicTools"].as_array().unwrap().len(), 6);
    let turn = executor
        .runtime
        .calls
        .iter()
        .find(|(_, m, _)| m == "turn/start")
        .unwrap();
    assert_eq!(turn.2["sandboxPolicy"]["type"], "readOnly");
}
