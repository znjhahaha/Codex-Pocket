use super::*;

#[test]
fn assistant_attachment_native_start_and_steer_preserve_images_text_and_at_most_once_receipts() {
    let mut f=Fixture::new();let path=f.root.path().join("input.png");
    std::fs::write(&path,b"\x89PNG\r\n\x1a\nfixture").unwrap();
    let image=AssistantInput::Image{id:uuid::Uuid::new_v4().to_string(),name:"input.png".into(),path:path.clone()};
    let text=AssistantInput::Text{id:uuid::Uuid::new_v4().to_string(),name:"notes.txt".into(),text:"fixture original body".into()};
    let mut request=f.req("attachment-native",Role::Execution);request.inputs=vec![image.clone(),text];
    let mapping=f.executor.start(request).unwrap();
    let params=&f.executor.runtime.calls.iter().find(|(_,method,_)|method=="turn/start").unwrap().2;
    assert_eq!(params["input"][1],json!({"type":"localImage","path":path}));
    assert!(params["input"][2]["text"].as_str().unwrap().contains("fixture original body"));
    let inputs=vec![image];
    assert_eq!(f.executor.append_inputs("attachment-correction",&mapping,"Use this version",&inputs).unwrap(),AppendOutcome::Delivered);
    assert_eq!(f.executor.append_inputs("attachment-correction",&mapping,"Use this version",&inputs).unwrap(),AppendOutcome::Delivered);
    let calls=f.executor.runtime.calls.iter().filter(|(_,method,_)|method=="turn/steer").collect::<Vec<_>>();
    assert_eq!(calls.len(),1);assert_eq!(calls[0].2["input"][1]["type"],"localImage");
}
use serde_json::json;
#[path = "tests/terminal_generation.rs"]
mod terminal_generation;
#[path = "tests/effort.rs"]
mod effort;
#[derive(Clone)]
struct Fake {
    generation: String,
    receipt_queries: Vec<Value>,
    generation_after_receipt: Option<(Value, String)>,
    calls: Vec<(Value, String, Value)>,
    receipts: BTreeMap<String, RuntimeReceipt>,
    answers: Vec<(Value, Value)>,
    lose_method: Option<String>,
    lose_answer: bool,
    models: Value,
    configured_model: Option<String>,
    replay_batches: std::collections::VecDeque<RuntimeEvents>,
    turns: BTreeMap<String, Vec<Value>>,
    batch: Vec<Value>,
    online: bool,
    reconciliation_required:bool,
    scoped_admission:bool,
    refuse_admission:bool,
    unsupported_method: Option<String>,
    reject_steer_before_dispatch: bool,
    recovery_snapshot: Option<Value>,
    answer_evidence_records: Vec<pocket_runtime_ipc::client::AnswerEvidence>,
    answer_evidence_fault: Option<&'static str>,
}
impl Default for Fake {
    fn default() -> Self {
        Self {
            generation: "host-1".into(),
            receipt_queries: vec![],
            generation_after_receipt: None,
            calls: vec![],
            receipts: BTreeMap::new(),
            answers: vec![],
            lose_method: None,
            lose_answer: false,
            models: json!({"data":[{"id":"m","model":"m","hidden":false,"isDefault":true}],"nextCursor":null}),
            configured_model: None,
            replay_batches: Default::default(),
            turns: BTreeMap::new(),
            batch: vec![],
            online: true,
            reconciliation_required:false,scoped_admission:false,refuse_admission:false,
            unsupported_method: None,
            reject_steer_before_dispatch: false,
            recovery_snapshot: None,
            answer_evidence_records: vec![],
            answer_evidence_fault: None,
        }
    }
}
impl RuntimeIo for Fake {
    fn supports(&self, method: &str, _: &Value) -> bool {
        method == "turn/steer" && self.unsupported_method.as_deref() != Some(method)
    }
    fn request(&mut self, id: Value, method: &str, params: Value) -> Result<Value> {
        if self.refuse_admission && matches!(method,"thread/start"|"turn/start") {
            return Err(pocket_runtime_ipc::client::AdmissionRejected.into());
        }
        if method == "turn/steer" && self.reject_steer_before_dispatch {
            return Err(RuntimeUnsupported {
                method: method.into(),
            }
            .into());
        }
        if self.unsupported_method.as_deref() == Some(method) {
            return Err(RuntimeUnsupported {
                method: method.into(),
            }
            .into());
        }
        self.calls.push((id.clone(), method.into(), params.clone()));
        let n = self.calls.len();
        let response = match method {
            "config/read" => json!({"config":{"model":self.configured_model}}),
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
            "turn/steer" => json!({"turnId":params["expectedTurnId"]}),
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
                generation: self.generation.clone(),
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
        self.receipt_queries.push(id.clone());
        let receipt = self
            .receipts
            .get(&native_key(&id)?)
            .cloned()
            .unwrap_or(RuntimeReceipt {
                generation: self.generation.clone(),
                state: "unknown".into(),
                response: None,
            });
        if self.generation_after_receipt.as_ref().is_some_and(|(after, _)| *after == id) {
            self.generation = self.generation_after_receipt.take().unwrap().1;
        }
        Ok(receipt)
    }
    fn answer(&mut self, id: Value, result: Value) -> Result<()> {
        self.answers.push((id, result));
        if std::mem::take(&mut self.lose_answer) {
            anyhow::bail!("Lost answer receipt");
        }
        Ok(())
    }
    fn historical_receipt(&mut self, id: Value, _: &str) -> Result<RuntimeReceipt> {
        self.receipt(id)
    }
    fn recovery(&mut self) -> Result<Value> {
        self.recovery_snapshot.clone().context("Fixture recovery not supplied")
    }
    fn answer_evidence(&mut self, offset:usize, digest:&str)->Result<pocket_runtime_ipc::client::AnswerEvidencePage> {
        use pocket_runtime_ipc::client::{AnswerEvidencePage, ANSWER_EVIDENCE_PAGE_SIZE};
        if self.answer_evidence_fault==Some("unsupported") { anyhow::bail!("Legacy answer evidence unsupported"); }
        let total=self.answer_evidence_records.len();
        let records=self.answer_evidence_records.iter().skip(offset).take(ANSWER_EVIDENCE_PAGE_SIZE).cloned().collect::<Vec<_>>();
        let end=offset+records.len();
        let mut page=AnswerEvidencePage {schema:1,namespace:"answer".into(),runtime_id:"runtime-1".into(),
            generation:self.generation.clone(),snapshot_digest:digest.into(),offset,total,
            next_offset:(end<total).then_some(end),records};
        match self.answer_evidence_fault {
            Some("namespace")=>page.namespace="command".into(),
            Some("host")=>page.generation="foreign".into(),
            Some("page-digest")=>page.snapshot_digest="b".repeat(64),
            Some("offset")=>page.offset+=1,
            Some("total")=>page.total+=1,
            Some("partial")=>{page.records.pop();},
            Some("next")=>page.next_offset=Some(end),
            Some("duplicate") if page.records.len()>1=>page.records[1]=page.records[0].clone(),
            Some("duplicate-across") if offset>0=>page.records[0]=self.answer_evidence_records[0].clone(),
            Some("id")=>page.records[0].request_id=json!("0"),
            Some("original-generation")=>page.records[0].generation="foreign".into(),
            Some("uncompleted")=>page.records[0].completed=false,
            Some("race")=>self.recovery_snapshot.as_mut().unwrap()["evidenceDigest"]=json!("b".repeat(64)),
            _=>{},
        }
        Ok(page)
    }
    fn events(&mut self, after: u64, _: usize) -> Result<RuntimeEvents> {
        if let Some(batch) = self.replay_batches.pop_front() {
            return Ok(batch);
        }
        let messages = std::mem::take(&mut self.batch);
        Ok(RuntimeEvents {
            generation: self.generation.clone(),
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
            generation: self.generation.clone(),
            online: self.online,
            initialized: true,
            protocol_verified: true,
            reconciliation_required: self.reconciliation_required,
            scoped_admission:self.scoped_admission,
        })
    }
}

struct Fixture {
    root: tempfile::TempDir,
    executor: HostExecutor<Fake>,
}

#[test]
fn rebuild_scoped_host_admission_keeps_generation_event_gap_and_unknown_fences() {
    let mut f=Fixture::new();
    f.executor.runtime.reconciliation_required=true;
    assert!(f.executor.start(f.req("old-host",Role::Coordinator)).is_err());
    assert!(f.executor.runtime.calls.is_empty());
    f.executor.runtime.scoped_admission=true;
    f.executor.event_gap=true;
    assert!(f.executor.start(f.req("gap",Role::Coordinator)).is_err());
    f.executor.event_gap=false;
    let mapping=f.executor.start(f.req("independent",Role::Coordinator)).unwrap();
    assert_eq!(mapping.host_generation,"host-1");
    f.executor.runtime.refuse_admission=true;
    assert!(f.executor.start(f.req("scoped-refusal",Role::Execution)).is_err());
    assert_eq!(generation_status(&mut f,"scoped-refusal","host-1").unwrap(),ExecutionStatus::NotDispatched);
    let count=f.executor.runtime.calls.len();
    f.executor.runtime.refuse_admission=false;
    assert!(f.executor.start(f.req("scoped-refusal",Role::Execution)).is_err());
    assert_eq!(f.executor.runtime.calls.len(),count,"Refusal requires explicit new attempt, never replay original");
}

#[test]
fn rebuild_scoped_generation_retains_old_unknown_and_starts_only_fresh_attempts() {
    let mut f=Fixture::new();let old=f.start("old",Role::Execution);
    f.executor.runtime.receipts.get_mut(&native_key(&rpc_id("old","turn")).unwrap()).unwrap().state="unknown".into();
    f.executor.runtime.receipts.get_mut(&native_key(&rpc_id("old","turn")).unwrap()).unwrap().response=None;
    let before=serde_json::to_value(&f.executor.state).unwrap();
    let mut f=generation_reopen(f,"host-2");
    f.executor.runtime.scoped_admission=true;f.executor.runtime.reconciliation_required=true;
    f.executor.runtime.recovery_snapshot=Some(json!({"generation":"host-2","sequence":20,"commandCount":2,"answerCount":0,
        "evidenceDigest":"a".repeat(64),"pendingRequests":[],"runs":[{"generation":"host-1","threadId":old.thread_id,"turnId":old.turn_id,"state":"unknown"}]}));
    assert!(f.executor.adopt_scoped_generation().unwrap());
    for field in ["operations","threads","effects","answers","streaming","streaming_order","streaming_stopped"] {
        assert_eq!(serde_json::to_value(&f.executor.state).unwrap()[field],before[field]);
    }
    assert_eq!(generation_status(&mut f,"old","host-1").unwrap(),ExecutionStatus::Unknown);
    let mut fresh=f.req("fresh",Role::Coordinator);fresh.host_generation="host-2".into();
    assert!(f.executor.start(fresh.clone()).is_ok());
    let calls=f.executor.runtime.calls.len();
    let mut replay=f.req("old",Role::Execution);replay.host_generation="host-2".into();
    assert!(f.executor.start(replay).is_err());assert_eq!(calls,f.executor.runtime.calls.len());
    let foreign=RuntimeEvent {sequence:21,message:json!({"method":"turn/completed","params":{"threadId":old.thread_id,"turn":{"id":old.turn_id,"status":"completed","items":[]}}})};
    assert!(f.executor.ingest("host-2",foreign).unwrap().observations.is_empty());
}

#[test]
fn rebuild_scoped_generation_missing_original_receipt_and_failed_save_preserve_fence() {
    for fail_save in [false,true] {
        let mut f=Fixture::new();f.start("old",Role::Execution);
        let mut f=generation_reopen(f,"host-2");
        f.executor.runtime.scoped_admission=true;f.executor.runtime.reconciliation_required=true;
        f.executor.runtime.recovery_snapshot=Some(json!({"generation":"host-2","sequence":1,"commandCount":2,"answerCount":0,
            "evidenceDigest":"a".repeat(64),"pendingRequests":[],"runs":[]}));
        if fail_save {f.executor.db.execute_batch("CREATE TRIGGER reject_scope BEFORE UPDATE ON assistant_executor_state BEGIN SELECT RAISE(FAIL,'blocked'); END;").unwrap();}
        else {f.executor.runtime.receipts.remove(&native_key(&rpc_id("old","turn")).unwrap());}
        let before=serde_json::to_value(&f.executor.state).unwrap();
        assert!(f.executor.adopt_scoped_generation().is_err());
        assert_eq!(serde_json::to_value(&f.executor.state).unwrap(),before);
    }
}

fn generation_reopen(f: Fixture, generation: &str) -> Fixture {
    let Fixture { root, executor } = f;
    let mut runtime = executor.runtime.clone();
    runtime.generation = generation.into();
    let policy = executor.policy.clone();
    drop(executor);
    let executor = HostExecutor::open(&root.path().join("adapter.db"), runtime, policy).unwrap();
    Fixture { root, executor }
}

fn generation_status(f: &mut Fixture, id: &str, generation: &str) -> Result<ExecutionStatus> {
    f.executor.status(StatusRequest {
        operation_id: id.into(), mapping: None, host_generation: generation.into(),
    })
}

fn generation_missing_receipts(f: &mut Fixture, id: &str) {
    for phase in ["thread", "turn"] {
        f.executor.runtime.receipts.insert(native_key(&rpc_id(id, phase)).unwrap(), RuntimeReceipt {
            generation: f.executor.runtime.generation.clone(), state: "missing".into(), response: None,
        });
    }
}

#[test]
fn v070_generation_clean_executor_resets_cursor_before_first_dispatch() {
    let mut f = Fixture::new();
    f.executor.state.cursor = 137;
    f.executor.save().unwrap();
    let mut f = generation_reopen(f, "host-2");
    assert_eq!(f.executor.state.generation, "host-2");
    assert_eq!(f.executor.cursor(), 0);
    assert!(f.executor.runtime.calls.is_empty());
    let mut request = f.req("first-new-generation", Role::Coordinator);
    request.host_generation = "host-2".into();
    let mapping = f.executor.start(request).unwrap();
    let calls = f.executor.runtime.calls.len();
    let f = generation_reopen(f, "host-2");
    assert_eq!(f.executor.state.operations["first-new-generation"].mapping.as_ref(), Some(&mapping));
    assert_eq!(f.executor.runtime.calls.len(), calls);
}

#[test]
fn v070_generation_offline_preflight_rejection_is_durable_without_health() {
    let mut f = Fixture::new();
    f.executor.runtime.online = false;
    let request = f.req("offline-preflight", Role::Coordinator);
    assert!(f.executor.start(request.clone()).is_err());
    assert_eq!(generation_status(&mut f, "offline-preflight", "host-1").unwrap(), ExecutionStatus::NotDispatched);
    let mut f = generation_reopen(f, "host-1");
    assert_eq!(generation_status(&mut f, "offline-preflight", "host-1").unwrap(), ExecutionStatus::NotDispatched);
    f.executor.runtime.online = true;
    assert!(f.executor.start(request).is_err());
    assert!(f.executor.runtime.calls.is_empty());
}

#[test]
fn v070_generation_legacy_absence_requires_transition_and_exact_receipts() {
    let mut f = Fixture::new();
    generation_missing_receipts(&mut f, "missing-legacy");
    assert_eq!(generation_status(&mut f, "missing-legacy", "host-1").unwrap(), ExecutionStatus::Unknown);
    let mut f = generation_reopen(f, "host-2");
    assert_eq!(generation_status(&mut f, "missing-legacy", "host-2").unwrap(), ExecutionStatus::Unknown);
    generation_missing_receipts(&mut f, "missing-legacy");
    assert_eq!(generation_status(&mut f, "missing-legacy", "host-2").unwrap(), ExecutionStatus::NotDispatched);
    assert!(f.executor.runtime.calls.is_empty());
    assert!(f.executor.runtime.receipt_queries.contains(&rpc_id("missing-legacy", "thread")));
    assert!(f.executor.runtime.receipt_queries.contains(&rpc_id("missing-legacy", "turn")));
    let mut f = generation_reopen(f, "host-2");
    f.executor.runtime.online = false;
    assert_eq!(generation_status(&mut f, "missing-legacy", "host-2").unwrap(), ExecutionStatus::NotDispatched);
    assert_eq!(generation_status(&mut f, "missing-legacy", "host-1").unwrap(), ExecutionStatus::Unknown);
    f.executor.runtime.online = true;
    let mut request = f.req("missing-legacy", Role::Coordinator);
    request.host_generation = "host-2".into();
    assert!(f.executor.start(request).is_err());
    assert!(f.executor.runtime.calls.is_empty(), "Reconciliation must not make an operation dispatchable");
}

#[test]
fn v070_generation_three_generations_bind_intent_to_current_receipt_witness() {
    for intermediate_reopen in [false, true] {
        let mut f = Fixture::new(); // durable executor A
        f.executor.state.cursor = 137;
        f.executor.save().unwrap();
        if intermediate_reopen {
            f = generation_reopen(f, "host-2");
        }
        let mut f = generation_reopen(f, "host-3"); // live Host C, Service expects B
        assert_eq!(f.executor.cursor(), 0);
        generation_missing_receipts(&mut f, "overnight");
        assert_eq!(generation_status(&mut f, "overnight", "host-2").unwrap(), ExecutionStatus::NotDispatched);
        let proof = serde_json::to_value(&f.executor.state).unwrap()["not_dispatched"]["overnight"].clone();
        assert_eq!(proof["expected_generation"], "host-2");
        assert_eq!(proof["generation"], "host-3");
        assert_eq!(f.executor.runtime.receipt_queries, vec![rpc_id("overnight", "thread"), rpc_id("overnight", "turn")]);

        // A later clean restart keeps the proof and permits only new explicit IDs.
        let mut f = generation_reopen(f, "host-4");
        assert_eq!(f.executor.state.generation, "host-4");
        assert_eq!(serde_json::to_value(&f.executor.state).unwrap()["not_dispatched"]["overnight"], proof);
        f.executor.runtime.online = false;
        f.executor.runtime.receipts.clear();
        f.executor.runtime.receipt_queries.clear();
        assert_eq!(generation_status(&mut f, "overnight", "host-2").unwrap(), ExecutionStatus::NotDispatched);
        for other_generation in ["host-1", "host-3", "host-4"] {
            assert_eq!(generation_status(&mut f, "overnight", other_generation).unwrap(), ExecutionStatus::Unknown);
        }
        assert!(f.executor.runtime.receipt_queries.is_empty());
        f.executor.runtime.online = true;
        for generation in ["host-2", "host-4"] {
            let mut req = f.req("overnight", Role::Coordinator);
            req.host_generation = generation.into();
            assert!(f.executor.start(req.clone()).is_err());
            req.thread_id = Some("old-thread".into());
            assert!(f.executor.resume(req).is_err());
        }
        assert!(f.executor.runtime.calls.is_empty());
        let mut req = f.req("explicit-new-intent", Role::Coordinator);
        req.host_generation = "host-4".into();
        f.executor.start(req).unwrap();
        assert_eq!(generation_status(&mut f, "overnight", "host-2").unwrap(), ExecutionStatus::NotDispatched);
        let f = generation_reopen(f, "host-5");
        assert_eq!(f.executor.state.generation, "host-4", "Accepted history still fences adoption");
    }
}

#[test]
fn v070_generation_three_generations_old_proof_format_keeps_expected_identity() {
    let f = Fixture::new();
    let mut f = generation_reopen(f, "host-2");
    generation_missing_receipts(&mut f, "old-proof");
    assert_eq!(generation_status(&mut f, "old-proof", "host-2").unwrap(), ExecutionStatus::NotDispatched);
    let mut saved = serde_json::to_value(&f.executor.state).unwrap();
    saved["not_dispatched"]["old-proof"].as_object_mut().unwrap().remove("expected_generation");
    f.executor.state = serde_json::from_value(saved).unwrap();
    f.executor.save().unwrap();
    let mut f = generation_reopen(f, "host-3");
    assert_eq!(f.executor.state.generation, "host-3");
    f.executor.runtime.online = false;
    assert_eq!(generation_status(&mut f, "old-proof", "host-2").unwrap(), ExecutionStatus::NotDispatched);
    assert_eq!(generation_status(&mut f, "old-proof", "host-3").unwrap(), ExecutionStatus::Unknown);
    assert!(f.executor.runtime.calls.is_empty());
}

#[test]
fn v070_generation_three_generations_current_absence_cannot_clear_nonempty_journal() {
    let f = Fixture::new();
    let mut f = generation_reopen(f, "host-3");
    let mut req = f.req("accepted-current", Role::Coordinator);
    req.host_generation = "host-3".into();
    let mapping = f.executor.start(req).unwrap();
    let calls = f.executor.runtime.calls.len();
    generation_missing_receipts(&mut f, "missing-B");
    assert_eq!(generation_status(&mut f, "missing-B", "host-2").unwrap(), ExecutionStatus::Unknown);
    assert!(f.executor.state.not_dispatched.is_empty());
    assert_eq!(f.executor.runtime.calls.len(), calls);
    assert_eq!(f.executor.state.operations["accepted-current"].mapping.as_ref(), Some(&mapping));
}

#[test]
fn v070_generation_three_generations_requires_live_witness_through_both_receipts() {
    for phase in ["thread", "turn"] {
        let f = Fixture::new();
        let mut f = generation_reopen(f, "host-3");
        generation_missing_receipts(&mut f, "host-race");
        f.executor.runtime.generation_after_receipt = Some((rpc_id("host-race", phase), "host-4".into()));
        let status = generation_status(&mut f, "host-race", "host-2");
        assert!(status.is_err() || status.unwrap() == ExecutionStatus::Unknown);
        assert!(f.executor.state.not_dispatched.is_empty());
        assert!(f.executor.runtime.calls.is_empty());
    }
}

#[test]
fn v070_generation_nonempty_executor_preserves_accepted_mapping_and_receipts() {
    let mut f = Fixture::new();
    let mapping = f.start("already-accepted", Role::Execution);
    let before = serde_json::to_value(&f.executor.state).unwrap();
    let calls = f.executor.runtime.calls.len();
    let mut f = generation_reopen(f, "host-2");
    assert_eq!(f.executor.state.generation, "host-1");
    for field in ["operations", "threads", "effects", "answers"] {
        assert_eq!(serde_json::to_value(&f.executor.state).unwrap()[field], before[field]);
    }
    assert_eq!(generation_status(&mut f, "already-accepted", "host-1").unwrap(), ExecutionStatus::Unknown);
    assert_eq!(f.executor.state.operations["already-accepted"].mapping.as_ref(), Some(&mapping));
    assert_eq!(f.executor.runtime.calls.len(), calls);
}

#[test]
fn v070_generation_orphan_effect_cannot_be_reclassified_as_preflight_rejection() {
    let mut f = Fixture::new();
    f.executor.state.effects.insert(native_key(&rpc_id("orphan", "turn")).unwrap(), Effect {
        fingerprint: "prior-attempt".into(), accepted: false, rejected: false,
    });
    f.executor.save().unwrap();
    f.executor.runtime.online = false;
    assert!(f.executor.start(f.req("orphan", Role::Coordinator)).is_err());
    assert_eq!(generation_status(&mut f, "orphan", "host-1").unwrap(), ExecutionStatus::Unknown);
    assert!(!f.executor.state.operations.contains_key("orphan"));
}

#[test]
fn v070_generation_partial_receipts_and_mapping_never_prove_absence() {
    let f = Fixture::new();
    let mut f = generation_reopen(f, "host-2");
    for expected in ["host-2", "absent-host-B"] {
    for phase in ["thread", "turn"] {
        for state in ["unknown", "submitted", "completed", "invalid", "wrong-generation", "missing-with-response", "absent-host-B"] {
            let id = format!("legacy-{expected}-{phase}-{state}");
            generation_missing_receipts(&mut f, &id);
            f.executor.runtime.receipts.insert(native_key(&rpc_id(&id, phase)).unwrap(), RuntimeReceipt {
                generation: match state { "wrong-generation" => "host-1", "absent-host-B" => "absent-host-B", _ => "host-2" }.into(),
                state: if state.starts_with("missing-") || state == "wrong-generation" || state == "absent-host-B" { "missing" } else { state }.into(),
                response: (state == "missing-with-response").then(|| json!({"id":rpc_id(&id,phase),"result":{}})),
            });
            assert_eq!(generation_status(&mut f, &id, expected).unwrap(), ExecutionStatus::Unknown, "{expected} {phase} {state}");
        }
    }
    }
    generation_missing_receipts(&mut f, "mapped");
    let status = f.executor.status(StatusRequest {
        operation_id: "mapped".into(), host_generation: "host-2".into(),
        mapping: Some(NativeMapping { host_generation: "host-2".into(), thread_id: "accepted-thread".into(), turn_id: "accepted-turn".into() }),
    }).unwrap();
    assert_eq!(status, ExecutionStatus::Unknown);
    assert!(f.executor.state.not_dispatched.is_empty());
    assert!(f.executor.runtime.calls.is_empty());
}

#[test]
fn v070_generation_any_old_native_history_blocks_adoption() {
    let mut source = Fixture::new();
    source.start("old", Role::Execution);
    source.executor.state.answers.insert("old-answer".into(), Effect { fingerprint: "old".into(), accepted: false, rejected: false });
    source.executor.state.streaming.insert("old:item".into(), "old text".into());
    source.executor.state.streaming_order.insert("old".into(), vec!["item".into()]);
    source.executor.state.streaming_stopped.insert("old".into());
    let source = serde_json::to_value(&source.executor.state).unwrap();
    for field in ["operations", "threads", "effects", "answers", "streaming", "streaming_order", "streaming_stopped"] {
        let mut f = Fixture::new();
        let mut state = serde_json::to_value(&f.executor.state).unwrap();
        state[field] = source[field].clone();
        state["cursor"] = json!(137);
        f.executor.state = serde_json::from_value(state).unwrap();
        f.executor.save().unwrap();
        let mut f = generation_reopen(f, "host-2");
        assert_eq!(f.executor.state.generation, "host-1", "{field}");
        assert_eq!(f.executor.cursor(), 137);
        assert_eq!(serde_json::to_value(&f.executor.state).unwrap()[field], source[field]);
        generation_missing_receipts(&mut f, "missing-old");
        assert_eq!(generation_status(&mut f, "missing-old", "host-2").unwrap(), ExecutionStatus::Unknown);
        assert!(f.executor.runtime.calls.is_empty());
    }
}

#[test]
fn v070_generation_failed_proof_save_never_clears_unknown() {
    let f = Fixture::new();
    let mut f = generation_reopen(f, "host-3");
    generation_missing_receipts(&mut f, "legacy-save-failure");
    f.executor.db.execute_batch("CREATE TRIGGER fail_proof BEFORE UPDATE ON assistant_executor_state BEGIN SELECT RAISE(FAIL,'proof save failed'); END;").unwrap();
    assert!(generation_status(&mut f, "legacy-save-failure", "host-2").is_err());
    assert!(f.executor.state.not_dispatched.is_empty());
    let saved: String = f.executor.db.query_row("SELECT data FROM assistant_executor_state", [], |r| r.get(0)).unwrap();
    assert_eq!(serde_json::from_str::<Value>(&saved).unwrap()["not_dispatched"], json!({}));
    f.executor.runtime.online = false;
    assert_eq!(generation_status(&mut f, "legacy-save-failure", "host-2").unwrap(), ExecutionStatus::Unknown);
    f.executor.db.execute_batch("DROP TRIGGER fail_proof").unwrap();
    f.executor.runtime.online = true;
    assert_eq!(generation_status(&mut f, "legacy-save-failure", "host-2").unwrap(), ExecutionStatus::NotDispatched);
    assert!(f.executor.runtime.calls.is_empty());
}

#[test]
fn v070_generation_failed_preflight_save_retains_unknown() {
    let mut f = Fixture::new();
    f.executor.runtime.online = false;
    f.executor.db.execute_batch("CREATE TRIGGER fail_preflight BEFORE UPDATE ON assistant_executor_state BEGIN SELECT RAISE(FAIL,'rejection save failed'); END;").unwrap();
    assert!(f.executor.start(f.req("preflight-save-failure", Role::Coordinator)).is_err());
    assert!(f.executor.state.operations.is_empty());
    assert_eq!(generation_status(&mut f, "preflight-save-failure", "host-1").unwrap(), ExecutionStatus::Unknown);
    assert!(f.executor.runtime.calls.is_empty());
}

#[test]
fn v070_generation_failed_transition_save_preserves_original_generation() {
    let mut f = Fixture::new();
    f.executor.state.cursor = 137;
    f.executor.save().unwrap();
    f.executor.db.execute_batch("CREATE TRIGGER fail_transition BEFORE UPDATE ON assistant_executor_state BEGIN SELECT RAISE(FAIL,'transition save failed'); END;").unwrap();
    let Fixture { root, executor } = f;
    let mut runtime = executor.runtime.clone();
    runtime.generation = "host-2".into();
    let policy = executor.policy.clone();
    drop(executor);
    assert!(HostExecutor::open(&root.path().join("adapter.db"), runtime, policy).is_err());
    let db = Connection::open(root.path().join("adapter.db")).unwrap();
    let data: String = db.query_row("SELECT data FROM assistant_executor_state", [], |row| row.get(0)).unwrap();
    let saved: Value = serde_json::from_str(&data).unwrap();
    assert_eq!(saved["generation"], "host-1");
    assert_eq!(saved["cursor"], 137);
    assert!(saved["clean_generation"].is_null());
}

#[test]
fn v070_housekeeping_tools_are_typed_bounded_and_coordinator_only() {
    let mut f = Fixture::new();
    f.start("coordinator", Role::Coordinator);
    f.start("worker", Role::Execution);
    let starts = f
        .executor
        .runtime
        .calls
        .iter()
        .filter(|(_, method, _)| method == "thread/start")
        .map(|(_, _, p)| p)
        .collect::<Vec<_>>();
    let names = starts[0]["dynamicTools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["name"].as_str().unwrap())
        .collect::<Vec<_>>();
    for name in [
        "assistant_schedule_create",
        "assistant_schedule_delete",
        "assistant_schedule_list",
        "assistant_memory_read",
        "assistant_memory_add",
        "assistant_memory_update",
        "assistant_memory_delete",
    ] {
        assert!(names.contains(&name), "Coordinator missing {name}");
    }
    assert_eq!(starts[1]["dynamicTools"].as_array().unwrap().len(), 1);
    assert_eq!(starts[1]["dynamicTools"][0]["name"], "assistant_read_main");
    let args = json!({"user_message_id":"user:handoff","timezone":"UTC","kind":{"type":"weekly","weekday":0,"hour":9,"minute":30},"prompt":"Read status","directory":f.root.path(),"writes":false});
    assert!(protocol::decode_tool("assistant_schedule_create", &args).is_ok());
    for (key, value) in [
        (
            "kind",
            json!({"type":"weekly","weekday":7,"hour":9,"minute":30}),
        ),
        ("kind", json!({"type":"once","at_ms":-1})),
        (
            "kind",
            json!({"type":"interval","anchor_ms":0,"every_ms":999}),
        ),
        (
            "kind",
            json!({"type":"daily","hour":9,"minute":30,"zone_rules":{}}),
        ),
        ("writes", json!("true")),
        ("zone", json!({"name":"UTC","initial_offset_seconds":0})),
    ] {
        let mut bad = args.clone();
        bad[key] = value;
        assert!(
            protocol::decode_tool("assistant_schedule_create", &bad).is_err(),
            "Accepted {bad}"
        );
    }
    assert!(protocol::decode_tool("assistant_memory_read", &json!({"limit":0})).is_err());
    assert!(protocol::decode_tool("assistant_memory_read", &json!({"limit":101})).is_err());
    assert!(
        protocol::decode_tool(
            "assistant_memory_add",
            &json!({"user_message_id":"user:x","text":"x".repeat(16385)})
        )
        .is_err()
    );
}

#[test]
fn v070_housekeeping_existing_coordinator_revision_one_requires_successor_tools() {
    let mut f = Fixture::new();
    let m = f.start("old-coordinator", Role::Coordinator);
    assert!(!f.executor.needs_tool_upgrade(&m.thread_id).unwrap());
    f.executor
        .state
        .threads
        .get_mut(&m.thread_id)
        .unwrap()
        .tool_revision = 1;
    assert!(f.executor.needs_tool_upgrade(&m.thread_id).unwrap());
}

#[test]
fn v070_default_model_inherits_custom_provider_without_directory_lookup() {
    let mut f = Fixture::new();
    f.executor.runtime.models = json!({"data":[],"nextCursor":null});
    let request = f.req("custom-default", Role::Coordinator);
    assert!(f.executor.start(request).is_ok());
    assert!(
        f.executor
            .runtime
            .calls
            .iter()
            .all(|(_, method, _)| method != "model/list")
    );
    for (_, method, params) in &f.executor.runtime.calls {
        if matches!(method.as_str(), "thread/start" | "turn/start") {
            assert!(
                params["model"].is_null(),
                "inherit the selected native profile"
            );
        }
    }
}

#[test]
fn v070_explicit_existing_provider_model_uses_authoritative_config() {
    let mut f = Fixture::new();
    f.executor.runtime.configured_model = Some("custom-solver".into());
    f.executor.runtime.models = json!({"data":[],"nextCursor":null});
    let mut req = f.req("explicit-custom", Role::Coordinator);
    req.settings.model = Some("custom-solver".into());
    assert!(f.executor.start(req).is_ok());
    assert!(
        f.executor
            .runtime
            .calls
            .iter()
            .all(|(_, m, _)| m != "model/list")
    );
}

#[test]
fn v070_streaming_requires_exact_turn_identity_and_ignores_replay_and_late_deltas() {
    let mut f = Fixture::new();
    let m = f.start("stream", Role::Coordinator);
    let wrong = NativeMapping {
        turn_id: "other".into(),
        ..m.clone()
    };
    assert!(
        f.event(
            1,
            Value::Null,
            "item/agentMessage/delta",
            &wrong,
            json!({"itemId":"item","delta":"wrong"})
        )
        .streams
        .is_empty()
    );
    assert_eq!(
        f.event(
            2,
            Value::Null,
            "item/agentMessage/delta",
            &m,
            json!({"itemId":"item","delta":"actual"})
        )
        .streams[0]
            .text,
        "actual"
    );
    assert!(
        f.event(
            2,
            Value::Null,
            "item/agentMessage/delta",
            &m,
            json!({"itemId":"item","delta":"actual"})
        )
        .streams
        .is_empty()
    );
    assert_eq!(f.executor.streaming_text()[0].text, "actual");
    f.event(3,Value::Null,"turn/completed",&m,json!({"turn":{"id":m.turn_id,"status":"completed","items":[{"id":"item","type":"agentMessage","text":"actual"}]}}));
    assert!(
        f.event(
            4,
            Value::Null,
            "item/agentMessage/delta",
            &m,
            json!({"itemId":"item","delta":"late"})
        )
        .streams
        .is_empty()
    );
    assert!(f.executor.streaming_text().is_empty());
}
#[test]
fn v070_review_stream_overflow_does_not_block_controls() {
    let mut f = Fixture::new();
    let m = f.start("large-worker", Role::Execution);
    let other = f.start("control-coordinator", Role::Coordinator);
    f.event(
        1,
        Value::Null,
        "item/agentMessage/delta",
        &m,
        json!({"itemId":"large","delta":"x".repeat(65536)}),
    );
    let approval = json!({"id":"approval","method":"item/commandExecution/requestApproval","params":{"threadId":m.thread_id,"turnId":m.turn_id,"itemId":"approval","command":"write report"}});
    f.executor.runtime.replay_batches.push_back(RuntimeEvents {
        generation:"host-1".into(), next_after:4, snapshot_required:false,
        events:vec![
            RuntimeEvent{sequence:2,message:json!({"method":"item/agentMessage/delta","params":{"threadId":m.thread_id,"turnId":m.turn_id,"itemId":"large","delta":"资料"}})},
            RuntimeEvent{sequence:3,message:approval.clone()},
            RuntimeEvent{sequence:4,message:json!({"method":"turn/completed","params":{"threadId":other.thread_id,"turn":{"id":other.turn_id,"status":"completed","items":[{"type":"agentMessage","id":"final","text":"Control completed"}]}}})},
        ], pending_requests:vec![approval],
    });
    let result = f
        .executor
        .poll_events(256)
        .expect("Text limits must not block control events");
    assert_eq!(f.executor.cursor(), 4);
    assert!(
        result
            .observations
            .iter()
            .any(|o| matches!(o.status, ExecutionStatus::Waiting(..)))
    );
    assert!(
        result
            .observations
            .iter()
            .any(|o| matches!(o.status, ExecutionStatus::Completed(..)))
    );
    let text = &f.executor.streaming_text()[0].text;
    assert!(text.len() <= 65536);
    assert!(text.contains("truncated"));
    f.event(5,Value::Null,"turn/completed",&m,json!({"turn":{"id":m.turn_id,"status":"completed","items":[{"type":"agentMessage","id":"large","text":"资料".repeat(40000)}]}}));
    assert!(f.executor.streaming_text().is_empty());
}

#[test]
fn v070_review_stream_item_order_survives_executor_restart() {
    let mut f = Fixture::new();
    let m = f.start("ordered-stream", Role::Coordinator);
    f.event(
        1,
        Value::Null,
        "item/agentMessage/delta",
        &m,
        json!({"itemId":"z-first","delta":"First"}),
    );
    let result = f.event(
        2,
        Value::Null,
        "item/agentMessage/delta",
        &m,
        json!({"itemId":"a-second","delta":"Second"}),
    );
    assert_eq!(result.streams[0].text, "First\n\nSecond");
    let runtime = f.executor.runtime.clone();
    let policy = f.executor.policy.clone();
    drop(f.executor);
    f.executor = HostExecutor::open(&f.root.path().join("adapter.db"), runtime, policy).unwrap();
    assert_eq!(f.executor.streaming_text()[0].text, "First\n\nSecond");
    assert_eq!(
        f.event(
            3,
            Value::Null,
            "item/agentMessage/delta",
            &m,
            json!({"itemId":"z-first","delta":" again"})
        )
        .streams[0]
            .text,
        "First again\n\nSecond"
    );
}

#[test]
fn v070_review_stream_gap_snapshot_cursor_boundary() {
    let mut f = Fixture::new();
    let m = f.start("gap-stream", Role::Coordinator);
    f.event(
        1,
        Value::Null,
        "item/agentMessage/delta",
        &m,
        json!({"itemId":"z-first","delta":"A"}),
    );
    // The native snapshot already includes C emitted after the gap watermark.
    // Consuming C again would duplicate it; ignoring native items loses B.
    f.executor.runtime.turns.insert(m.thread_id.clone(),vec![
        json!({"id":"foreign-turn","status":"inProgress","items":[{"type":"agentMessage","id":"foreign","text":"Must not recover this turn"}]}),
        json!({"id":m.turn_id,"status":"inProgress","items":[{"type":"agentMessage","id":"z-first","text":"ABC"},{"type":"agentMessage","id":"a-second","text":"Second"}]}),
    ]);
    f.executor.runtime.replay_batches.extend([
        RuntimeEvents{generation:"host-1".into(),next_after:10,snapshot_required:true,events:vec![],pending_requests:vec![]},
        RuntimeEvents{generation:"host-1".into(),next_after:11,snapshot_required:false,events:vec![RuntimeEvent{sequence:11,message:json!({"method":"item/agentMessage/delta","params":{"threadId":m.thread_id,"turnId":m.turn_id,"itemId":"z-first","delta":"C"}})}],pending_requests:vec![]},
        RuntimeEvents{generation:"host-1".into(),next_after:11,snapshot_required:false,events:vec![],pending_requests:vec![]},
        RuntimeEvents{generation:"host-1".into(),next_after:11,snapshot_required:false,events:vec![],pending_requests:vec![]},
    ]);
    f.executor.poll_events(256).unwrap();
    f.status("gap-stream", Some(m.clone()));
    assert_eq!(f.executor.streaming_text()[0].text, "ABC\n\nSecond");
    assert_eq!(f.executor.cursor(), 11);
    assert!(!f.executor.event_gap);
    assert_eq!(
        f.event(
            12,
            Value::Null,
            "item/agentMessage/delta",
            &m,
            json!({"itemId":"z-first","delta":"D"})
        )
        .streams[0]
            .text,
        "ABCD\n\nSecond"
    );
    let runtime = f.executor.runtime.clone();
    let policy = f.executor.policy.clone();
    drop(f.executor);
    f.executor = HostExecutor::open(&f.root.path().join("adapter.db"), runtime, policy).unwrap();
    assert_eq!(f.executor.streaming_text()[0].text, "ABCD\n\nSecond");
}

#[test]
fn v070_review_stream_gap_failed_save_keeps_cursor_and_restart_fence() {
    let mut f = Fixture::new();
    let m = f.start("gap-save", Role::Coordinator);
    f.event(
        1,
        Value::Null,
        "item/agentMessage/delta",
        &m,
        json!({"itemId":"answer","delta":"A"}),
    );
    f.executor.runtime.turns.insert(m.thread_id.clone(),vec![json!({"id":m.turn_id,"status":"inProgress","items":[{"id":"answer","type":"agentMessage","text":"AB"}]})]);
    f.executor.runtime.replay_batches.extend([
        RuntimeEvents {
            generation: "host-1".into(),
            next_after: 10,
            snapshot_required: true,
            events: vec![],
            pending_requests: vec![],
        },
        RuntimeEvents {
            generation: "host-1".into(),
            next_after: 10,
            snapshot_required: false,
            events: vec![],
            pending_requests: vec![],
        },
    ]);
    f.executor.db.execute_batch("CREATE TRIGGER fail_gap_commit BEFORE UPDATE ON assistant_executor_state WHEN json_extract(OLD.data,'$.streaming_gap')=1 AND json_extract(NEW.data,'$.streaming_gap')=0 BEGIN SELECT RAISE(FAIL,'snapshot commit failed'); END;").unwrap();
    assert!(f.executor.poll_events(256).is_err());
    assert_eq!(f.executor.cursor(), 1);
    assert_eq!(f.executor.state.streaming["gap-save:answer"], "A");
    assert!(f.executor.streaming_text().is_empty());
    assert!(f.executor.event_gap);
    let saved: String = f
        .executor
        .db
        .query_row("SELECT data FROM assistant_executor_state", [], |r| {
            r.get(0)
        })
        .unwrap();
    assert_eq!(serde_json::from_str::<Value>(&saved).unwrap()["cursor"], 1);
    f.executor
        .db
        .execute_batch("DROP TRIGGER fail_gap_commit")
        .unwrap();
    let runtime = f.executor.runtime.clone();
    let policy = f.executor.policy.clone();
    drop(f.executor);
    f.executor = HostExecutor::open(&f.root.path().join("adapter.db"), runtime, policy).unwrap();
    assert!(f.executor.event_gap);
    assert!(f.executor.streaming_text().is_empty());
    f.executor.poll_events(256).unwrap();
    assert_eq!(f.executor.streaming_text()[0].text, "AB");
    assert!(!f.executor.event_gap);
}

#[test]
fn v070_review_stream_gap_restores_pending_control_and_ignores_read_reply_watermarks() {
    let mut f = Fixture::new();
    let m = f.start("gap-control", Role::Coordinator);
    f.event(
        1,
        Value::Null,
        "item/agentMessage/delta",
        &m,
        json!({"itemId":"answer","delta":"A"}),
    );
    f.executor.runtime.turns.insert(m.thread_id.clone(),vec![json!({"id":m.turn_id,"status":"inProgress","items":[{"id":"answer","type":"agentMessage","text":"AB"}]})]);
    let pending = json!({"id":"held-input","method":"item/tool/requestUserInput","params":{"threadId":m.thread_id,"turnId":m.turn_id,"questions":[{"id":"q","question":"Continue?"}]}});
    f.executor.runtime.replay_batches.extend([
        RuntimeEvents{generation:"host-1".into(),next_after:10,snapshot_required:true,events:vec![],pending_requests:vec![pending.clone()]},
        RuntimeEvents{generation:"host-1".into(),next_after:11,snapshot_required:false,events:vec![RuntimeEvent{sequence:11,message:json!({"id":"assistant-read:reply","result":{"thread":{"id":m.thread_id}}})}],pending_requests:vec![pending.clone()]},
        RuntimeEvents{generation:"host-1".into(),next_after:11,snapshot_required:false,events:vec![],pending_requests:vec![pending]},
    ]);
    let result = f.executor.poll_events(256).unwrap();
    assert_eq!(f.executor.streaming_text()[0].text, "AB");
    assert_eq!(f.executor.cursor(), 11);
    assert!(
        result
            .observations
            .iter()
            .any(|o| matches!(o.status, ExecutionStatus::Waiting(..)))
    );
    assert_eq!(f.executor.pending_requests().len(), 1);
    f.event(12,Value::Null,"turn/completed",&m,json!({"turn":{"id":m.turn_id,"status":"completed","items":[{"type":"agentMessage","id":"answer","text":"AB"}]}}));
    let runtime = f.executor.runtime.clone();
    let policy = f.executor.policy.clone();
    drop(f.executor);
    f.executor = HostExecutor::open(&f.root.path().join("adapter.db"), runtime, policy).unwrap();
    assert!(
        f.event(
            13,
            Value::Null,
            "item/agentMessage/delta",
            &m,
            json!({"itemId":"answer","delta":"late"})
        )
        .streams
        .is_empty()
    );
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
            inputs:vec![],
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
fn collaboration_native_append_reconciles_lost_receipt_without_replay() {
    let mut f = Fixture::new();
    let mapping = f.start("task", Role::Execution);
    f.executor.runtime.lose_method = Some("turn/steer".into());
    assert!(
        f.executor
            .append("follow-up", &mapping, "Also cover retries")
            .is_err()
    );
    assert_eq!(
        f.executor
            .append("follow-up", &mapping, "Also cover retries")
            .unwrap(),
        AppendOutcome::Delivered
    );
    assert_eq!(
        f.executor
            .runtime
            .calls
            .iter()
            .filter(|(_, method, _)| method == "turn/steer")
            .count(),
        1
    );
}

#[test]
fn collaboration_native_append_requires_capability_and_current_turn() {
    let mut f = Fixture::new();
    let mapping = f.start("task", Role::Execution);
    f.executor.runtime.unsupported_method = Some("turn/steer".into());
    assert_eq!(
        f.executor
            .append("follow-up", &mapping, "Also cover retries")
            .unwrap(),
        AppendOutcome::Unsupported
    );
    assert!(
        !f.executor
            .runtime
            .calls
            .iter()
            .any(|(_, method, _)| method == "turn/steer")
    );
    f.executor.runtime.unsupported_method = None;
    let mut stale = mapping.clone();
    stale.turn_id = "unrelated-turn".into();
    assert!(f.executor.append("stale", &stale, "Do not send").is_err());
    assert!(
        !f.executor
            .runtime
            .calls
            .iter()
            .any(|(_, method, _)| method == "turn/steer")
    );
}

#[test]
fn collaboration_append_rejection_receipt_is_final_without_resend() {
    let mut f = Fixture::new();
    let mapping = f.start("task", Role::Execution);
    f.executor.runtime.lose_method = Some("turn/steer".into());
    let _ = f.executor.append("follow", &mapping, "Correction");
    let id = rpc_id("follow", "append");
    f.executor.runtime.receipts.insert(
        native_key(&id).unwrap(),
        RuntimeReceipt {
            generation: "host-1".into(),
            state: "completed".into(),
            response: Some(
                json!({"id":id,"error":{"code":-32602,"message":"Expected turn already stopped"}}),
            ),
        },
    );
    let outcome = f
        .executor
        .append_status("follow", &mapping, "Correction")
        .unwrap();
    assert_eq!(format!("{outcome:?}"), "Rejected");
    assert_eq!(
        format!(
            "{:?}",
            f.executor.append("follow", &mapping, "Correction").unwrap()
        ),
        "Rejected"
    );
    assert_eq!(
        f.executor
            .runtime
            .calls
            .iter()
            .filter(|(_, m, _)| m == "turn/steer")
            .count(),
        1
    );
}

#[test]
fn collaboration_append_predispatch_rejection_survives_restart() {
    let mut f = Fixture::new();
    let mapping = f.start("task", Role::Execution);
    f.executor.runtime.reject_steer_before_dispatch = true;
    let outcome = f.executor.append("follow", &mapping, "Correction").unwrap();
    assert_eq!(format!("{outcome:?}"), "Rejected");
    let runtime = f.executor.runtime.clone();
    let policy = f.executor.policy.clone();
    drop(f.executor);
    let mut executor =
        HostExecutor::open(&f.root.path().join("adapter.db"), runtime, policy).unwrap();
    assert_eq!(
        format!(
            "{:?}",
            executor
                .append_status("follow", &mapping, "Correction")
                .unwrap()
        ),
        "Rejected"
    );
    assert!(
        !executor
            .runtime
            .calls
            .iter()
            .any(|(_, m, _)| m == "turn/steer")
    );
}

#[test]
fn collaboration_append_receipt_identity_and_uncertainty_remain_fenced() {
    let mut f = Fixture::new();
    let mapping = f.start("task", Role::Execution);
    f.executor.runtime.lose_method = Some("turn/steer".into());
    let _ = f.executor.append("follow", &mapping, "Correction");
    let id = rpc_id("follow", "append");
    let key = native_key(&id).unwrap();
    for receipt in [
        RuntimeReceipt {
            generation: "wrong".into(),
            state: "completed".into(),
            response: Some(json!({"id":id,"error":{}})),
        },
        RuntimeReceipt {
            generation: "host-1".into(),
            state: "completed".into(),
            response: Some(json!({"id":"wrong","error":{}})),
        },
        RuntimeReceipt {
            generation: "host-1".into(),
            state: "submitted".into(),
            response: Some(json!({"id":id,"error":{}})),
        },
    ] {
        f.executor.runtime.receipts.insert(key.clone(), receipt);
        assert!(
            f.executor
                .append_status("follow", &mapping, "Correction")
                .is_err()
        );
    }
    f.executor.runtime.receipts.remove(&key);
    assert_eq!(
        f.executor
            .append_status("follow", &mapping, "Correction")
            .unwrap(),
        AppendOutcome::Uncertain
    );
    assert_eq!(
        f.executor.append("follow", &mapping, "Correction").unwrap(),
        AppendOutcome::Uncertain
    );
    assert_eq!(
        f.executor
            .runtime
            .calls
            .iter()
            .filter(|(_, m, _)| m == "turn/steer")
            .count(),
        1
    );
}

#[test]
fn collaboration_legacy_resume_preserves_installed_tool_revision() {
    let mut f = Fixture::new();
    let mapping = f.start("legacy", Role::Execution);
    f.executor
        .state
        .threads
        .get_mut(&mapping.thread_id)
        .unwrap()
        .tool_revision = 0;
    f.executor
        .runtime
        .turns
        .get_mut(&mapping.thread_id)
        .unwrap()[0]["status"] = json!("interrupted");
    let mut request = f.req("resume-legacy", Role::Execution);
    request.thread_id = Some(mapping.thread_id.clone());
    f.executor.resume(request).unwrap();
    assert_eq!(
        f.executor.state.threads[&mapping.thread_id].tool_revision,
        0
    );
    assert_eq!(
        f.executor.state.operations["resume-legacy"].tool_revision,
        0
    );
    let resume = f
        .executor
        .runtime
        .calls
        .iter()
        .find(|(_, m, _)| m == "thread/resume")
        .unwrap();
    assert!(resume.2.get("dynamicTools").is_none());
    assert!(
        !f.executor
            .can_resume(
                &mapping.thread_id,
                Role::Execution,
                Some(f.root.path()),
                true
            )
            .unwrap()
    );
}

#[test]
fn collaboration_member_reuse_rechecks_native_stop_and_write_policy() {
    let mut f = Fixture::new();
    let mapping = f.start("task", Role::Execution);
    let root = f.root.path().to_owned();
    assert!(
        !f.executor
            .can_resume(&mapping.thread_id, Role::Execution, Some(&root), true)
            .unwrap()
    );
    f.executor
        .runtime
        .turns
        .get_mut(&mapping.thread_id)
        .unwrap()[0]["status"] = json!("completed");
    assert!(
        f.executor
            .can_resume(&mapping.thread_id, Role::Execution, Some(&root), true)
            .unwrap()
    );
    assert!(
        !f.executor
            .can_resume(&mapping.thread_id, Role::Execution, Some(&root), false)
            .unwrap()
    );
    assert!(
        !f.executor
            .can_resume(&mapping.thread_id, Role::Coordinator, Some(&root), true)
            .unwrap()
    );
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
    assert_eq!(
        f.executor
            .runtime
            .calls
            .iter()
            .find(|(_, method, _)| method == "thread/start")
            .unwrap()
            .2["dynamicTools"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| t["name"].as_str().unwrap())
            .collect::<Vec<_>>(),
        vec!["assistant_read_main"]
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
    assert_eq!(shared.pending_requests().unwrap().iter().filter(|p| p.mapping == m).count(), 1,
        "Completion is not an authoritative request resolution");
    let projection = shared.ingest("host-1", RuntimeEvent { sequence:4,
        message:json!({"method":"serverRequest/resolved","params":{"requestId":"native-question","threadId":m.thread_id}})
    }).unwrap();
    assert!(projection.observations.is_empty(), "Resolution cannot reopen the completed execution");
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
        inputs:vec![],
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
        inputs:vec![],
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
    let offered = |name: &str| {
        thread.2["dynamicTools"]
            .as_array()
            .unwrap()
            .iter()
            .any(|tool| tool["name"] == name)
    };
    assert!(offered("assistant_dispatch_session"));
    assert!(!offered("assistant_create_member"), "members are no longer offered to new threads");
    let turn = executor
        .runtime
        .calls
        .iter()
        .find(|(_, m, _)| m == "turn/start")
        .unwrap();
    assert_eq!(turn.2["sandboxPolicy"]["type"], "readOnly");
}

#[test]
fn coordinator_sandbox_is_read_only_regardless_of_request() {
    let f = Fixture::new();
    let directory = f.root.path().to_owned();
    let mut coordinator = f.req("lead", Role::Coordinator);
    coordinator.writes = true;
    let thread = protocol::thread_params(&coordinator, &directory, None, false);
    assert_eq!(thread["sandbox"], "read-only");
    let turn = protocol::turn_params(&coordinator, "thread-1", &directory, None);
    assert_eq!(turn["sandboxPolicy"]["type"], "readOnly");
    assert!(turn["sandboxPolicy"].get("writableRoots").is_none());
    // A job keeps exactly what it was granted.
    let job = f.req("job", Role::Execution);
    assert_eq!(protocol::thread_params(&job, &directory, None, false)["sandbox"], "workspace-write");
    assert_eq!(protocol::turn_params(&job, "thread-2", &directory, None)["sandboxPolicy"]["type"], "workspaceWrite");
    // And the executor still refuses a writing coordinator outright.
    let mut f = f;
    assert!(f.executor.start(coordinator).is_err());
}

#[test]
fn new_coordinator_threads_get_only_the_thin_tool_set() {
    let f = Fixture::new();
    let thread = protocol::thread_params(&f.req("lead", Role::Coordinator), f.root.path(), None, false);
    let names: Vec<&str> = thread["dynamicTools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|tool| tool["name"].as_str().unwrap())
        .collect();
    for expected in [
        "assistant_dispatch_session",
        "assistant_steer_session",
        "assistant_control_session",
        "assistant_list_jobs",
        "assistant_request_input",
        "assistant_read_main",
        "assistant_memory_read",
        "assistant_memory_add",
        "assistant_memory_update",
        "assistant_memory_delete",
        "assistant_schedule_list",
        "assistant_schedule_create",
        "assistant_schedule_delete",
    ] {
        assert!(names.contains(&expected), "{expected} missing");
    }
    assert_eq!(names.len(), 13, "{names:?}");
    for tool in protocol::super_pa_tools() {
        let input = &tool["inputSchema"];
        assert_eq!(input["additionalProperties"], false);
        assert!(!conforms(&json!({"rpc":"inject"}), input, input));
    }
    let s = schema("v2/ThreadStartParams");
    assert!(conforms(&thread, &s, &s), "thin tools must be valid native thread parameters");
    assert_eq!(TOOL_REVISION, 5);
}

#[test]
fn thin_tools_decode_strictly_and_legacy_tool_names_still_decode() {
    let dispatch = json!({"title":"修复登录超时","prompt":"Fix it","directory":"C:/work","writes":true});
    assert_eq!(
        protocol::decode_tool("assistant_dispatch_session", &dispatch).unwrap(),
        ToolCall::DispatchSession {
            title: "修复登录超时".into(),
            prompt: "Fix it".into(),
            directory: PathBuf::from("C:/work"),
            writes: true,
            use_worktree: None,
            model: None,
        }
    );
    let mut full = dispatch.clone();
    full["use_worktree"] = json!(false);
    full["model"] = json!("gpt-fast");
    assert!(matches!(
        protocol::decode_tool("assistant_dispatch_session", &full).unwrap(),
        ToolCall::DispatchSession { use_worktree: Some(false), model: Some(model), .. } if model == "gpt-fast"
    ));
    for bad in [
        json!({"title":"t","prompt":"p","directory":"d"}),
        json!({"title":"t","prompt":"p","directory":"d","writes":"yes"}),
        json!({"title":"t","prompt":"p","directory":"d","writes":true,"approve":true}),
        json!({"title":"t","prompt":"p","directory":"d","writes":true,"use_worktree":"no"}),
        json!({"title":"t","prompt":"p","directory":"d","writes":true,"model":""}),
        json!({"title":"","prompt":"p","directory":"d","writes":true}),
    ] {
        assert!(protocol::decode_tool("assistant_dispatch_session", &bad).is_err(), "{bad}");
    }
    assert_eq!(
        protocol::decode_tool("assistant_steer_session", &json!({"task_id":"t1","instruction":"faster"})).unwrap(),
        ToolCall::SteerSession { task_id: "t1".into(), instruction: "faster".into() }
    );
    assert!(protocol::decode_tool("assistant_steer_session", &json!({"task_id":"t1"})).is_err());
    for (action, expected) in [("status", SessionControl::Status), ("stop", SessionControl::Stop)] {
        assert_eq!(
            protocol::decode_tool("assistant_control_session", &json!({"task_id":"t1","action":action})).unwrap(),
            ToolCall::ControlSession { task_id: "t1".into(), action: expected }
        );
    }
    for action in ["approve", "resume", "retry", "merge"] {
        assert!(protocol::decode_tool("assistant_control_session", &json!({"task_id":"t1","action":action})).is_err());
    }
    assert_eq!(
        protocol::decode_tool("assistant_list_jobs", &json!({"limit":30})).unwrap(),
        ToolCall::ListJobs { limit: 30 }
    );
    assert!(protocol::decode_tool("assistant_list_jobs", &json!({"limit":31})).is_err());

    // Calls recorded by earlier coordinator threads replay to the same values as before.
    assert_eq!(
        protocol::decode_tool("assistant_create_task", &json!({"prompt":"p","directory":"d","writes":false})).unwrap(),
        ToolCall::CreateTask { prompt: "p".into(), directory: PathBuf::from("d"), writes: false }
    );
    assert!(protocol::decode_tool("assistant_create_task", &json!({"prompt":"p","directory":"d","writes":false,"title":"t"})).is_err());
    assert_eq!(protocol::decode_tool("assistant_progress", &json!({"task_id":"t"})).unwrap(), ToolCall::Progress { task_id: "t".into() });
    assert_eq!(protocol::decode_tool("assistant_pause_task", &json!({"task_id":"t"})).unwrap(), ToolCall::PauseTask { task_id: "t".into() });
    assert_eq!(protocol::decode_tool("assistant_list_sessions", &json!({"limit":5})).unwrap(), ToolCall::ListSessions { limit: 5 });
    assert_eq!(protocol::decode_tool("assistant_summarize", &json!({"text":"s"})).unwrap(), ToolCall::Summarize { text: "s".into() });
    assert!(matches!(
        protocol::decode_tool("assistant_create_member", &json!({"name":"n","duty":"d","directory":"x"})).unwrap(),
        ToolCall::Collaboration(CollaborationTool::CreateMember { .. })
    ));
    assert!(matches!(
        protocol::decode_tool("assistant_send_to_member", &json!({"member_id":"m","message":"hi"})).unwrap(),
        ToolCall::Collaboration(CollaborationTool::SendToMember { .. })
    ));
    assert!(matches!(
        protocol::decode_tool("assistant_memory_read", &json!({"limit":5})).unwrap(),
        ToolCall::Housekeeping(_)
    ));
    assert!(protocol::decode_tool("assistant_unknown", &json!({})).is_err());
}
