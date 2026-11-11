use super::*;

#[derive(Clone, Default)]
struct Fake {
    requests: Vec<StartRequest>,
    statuses: BTreeMap<String, ExecutionStatus>,
    responses: Vec<ResponseRequest>,
    replies: Vec<ToolResponse>,
    interrupts: Vec<NativeMapping>,
    queries: usize,
    lose_start: bool,
    lose_response: bool,
}
impl Fake {
    fn launch(&mut self, req: StartRequest) -> Result<NativeMapping> {
        let index = self.requests.len() + 1;
        let mapping = NativeMapping {
            host_generation: req.host_generation.clone(),
            thread_id: req.thread_id.clone().unwrap_or(format!("thread-{index}")),
            turn_id: format!("turn-{index}"),
        };
        self.statuses.insert(
            req.operation_id.clone(),
            ExecutionStatus::Running(mapping.clone()),
        );
        self.requests.push(req);
        if std::mem::take(&mut self.lose_start) {
            anyhow::bail!("lost start receipt");
        }
        Ok(mapping)
    }
}
impl Executor for Fake {
    fn start(&mut self, req: StartRequest) -> Result<NativeMapping> {
        assert!(req.thread_id.is_none());
        self.launch(req)
    }
    fn resume(&mut self, req: StartRequest) -> Result<NativeMapping> {
        assert!(req.thread_id.is_some());
        self.launch(req)
    }
    fn read(&mut self, req: ReadRequest) -> Result<Vec<SessionView>> {
        Ok(vec![SessionView {
            thread_id: match req {
                ReadRequest::Thread { thread_id, .. } => thread_id,
                ReadRequest::Sessions { .. } => "existing-desktop-thread".into(),
            },
            summary: "Existing original history".into(),
        }])
    }
    fn status(&mut self, req: StatusRequest) -> Result<ExecutionStatus> {
        self.queries += 1;
        Ok(self
            .statuses
            .get(&req.operation_id)
            .cloned()
            .unwrap_or(ExecutionStatus::Unknown))
    }
    fn validate_response(&mut self, _: &ResponseRequest) -> Result<()> {
        Ok(())
    }
    fn validate_tools(&mut self, _: &ToolResponse) -> Result<()> {
        Ok(())
    }
    fn respond(&mut self, req: ResponseRequest) -> Result<()> {
        self.responses.push(req);
        if std::mem::take(&mut self.lose_response) {
            anyhow::bail!("lost response receipt");
        }
        Ok(())
    }
    fn interrupt(&mut self, m: NativeMapping) -> Result<()> {
        self.interrupts.push(m);
        Ok(())
    }
    fn tools(&mut self, reply: ToolResponse) -> Result<()> {
        self.replies.push(reply);
        Ok(())
    }
}
struct Fixture {
    root: tempfile::TempDir,
    service: AssistantService<Fake>,
}
impl Fixture {
    fn new() -> Self {
        let root = tempfile::tempdir().unwrap();
        std::fs::create_dir(root.path().join("work")).unwrap();
        let service = AssistantService::open(
            &root.path().join("service.db"),
            "pc",
            "profile",
            "host-1",
            Fake::default(),
        )
        .unwrap();
        Self { root, service }
    }
    fn submit(&mut self) -> Operation {
        self.service
            .submit(
                "phone-1",
                "Build report",
                vec![self.root.path().join("work")],
                0,
            )
            .unwrap();
        self.service.tick(0).unwrap();
        self.op(Role::Coordinator)
    }
    fn op(&self, role: Role) -> Operation {
        self.service
            .operations()
            .into_iter()
            .find(|o| o.role == role && o.phase.live())
            .unwrap()
    }
    fn task(&mut self, id: &str, writes: bool) -> Task {
        let receipt = self
            .service
            .apply(
                id,
                Command::CreateTask {
                    prompt: id.into(),
                    directory: self.root.path().join("work"),
                    writes,
                    not_before_ms: 0,
                },
                0,
            )
            .unwrap();
        let CommandResult::Task(task) = receipt.result else {
            panic!()
        };
        task
    }
    fn observe(&mut self, op: &Operation, status: ExecutionStatus, event: &str, now: i64) {
        self.service
            .executor
            .statuses
            .insert(op.id.clone(), status.clone());
        self.service.observe(&op.id, status, event, now).unwrap();
    }
}

#[test]
fn phone_handoff_starts_native_coordinator_and_idle_never_calls_model() {
    let mut f = Fixture::new();
    for now in 0..100 {
        f.service.tick(now).unwrap();
    }
    assert!(f.service.executor.requests.is_empty());
    assert_eq!(f.service.executor.queries, 0);
    let op = f.submit();
    assert!(op.mapping.is_some());
    let req = &f.service.executor.requests[0];
    assert_eq!(req.role, Role::Coordinator);
    assert!(!req.writes && req.directory.is_none());
    assert!(req.prompt.contains("Build report"));
    f.service
        .submit(
            "phone-1",
            "Build report",
            vec![f.root.path().join("work")],
            0,
        )
        .unwrap();
    f.service.tick(1).unwrap();
    assert_eq!(f.service.executor.requests.len(), 1);
}

#[test]
fn phone_to_task_to_question_to_result_and_original_history_closes_loop() {
    let mut f = Fixture::new();
    let coordinator = f.submit();
    let scope = coordinator.mapping.clone().unwrap();
    let command = ToolCall::CreateTask {
        prompt: "Produce report.md".into(),
        directory: f.root.path().join("work"),
        writes: true,
    };
    let receipt = f
        .service
        .tool(scope.clone(), "create-1", command.clone(), 1)
        .unwrap();
    assert_eq!(
        f.service
            .tool(scope.clone(), "create-1", command, 1)
            .unwrap(),
        receipt
    );
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    f.service.tick(1).unwrap();
    let execution = f.op(Role::Execution);
    let mapping = execution.mapping.clone().unwrap();
    assert_ne!(mapping.thread_id, scope.thread_id);
    let progress = f
        .service
        .tool(
            scope.clone(),
            "progress",
            ToolCall::Progress {
                task_id: execution.task_id.clone().unwrap(),
            },
            1,
        )
        .unwrap();
    assert_eq!(progress["detail"], "Existing original history");
    let question = UserQuestion {
        request_id: "input-1".into(),
        kind: QuestionKind::Input,
        text: "Which report format?".into(),
    };
    f.observe(
        &execution,
        ExecutionStatus::Waiting(mapping.clone(), question),
        "question",
        2,
    );
    let task = f.service.tasks().unwrap().remove(0);
    assert_eq!(task.status, TaskStatus::WaitingUser);
    assert!(task.active_run_id.is_some());
    f.service
        .respond(
            "answer-1",
            &execution.id,
            "input-1",
            HumanResponse::Answer("Markdown".into()),
            3,
        )
        .unwrap();
    f.service
        .respond(
            "answer-1",
            &execution.id,
            "input-1",
            HumanResponse::Answer("Markdown".into()),
            3,
        )
        .unwrap();
    assert_eq!(f.service.executor.responses.len(), 1);
    assert_eq!(f.service.executor.responses[0].mapping, mapping);
    f.observe(
        &execution,
        ExecutionStatus::Completed(mapping.clone(), "report.md produced".into()),
        "complete",
        4,
    );
    assert_eq!(f.service.tasks().unwrap()[0].status, TaskStatus::Done);
    assert_eq!(
        f.service.read_session(&mapping.thread_id, 20).unwrap()[0].thread_id,
        mapping.thread_id
    );
    let events = f.service.events(0, 256).unwrap();
    assert_eq!(
        events
            .iter()
            .filter(|e| e.kind == "waiting_user" && e.notify)
            .count(),
        1
    );
    assert_eq!(
        events
            .iter()
            .filter(|e| e.kind == "completed" && e.notify)
            .count(),
        1
    );
    f.observe(
        &coordinator,
        ExecutionStatus::Completed(scope.clone(), "Summary sent".into()),
        "coordinator-done",
        4,
    );
    f.service.tick(5).unwrap();
    let resumed = f.op(Role::Coordinator);
    assert_eq!(resumed.mapping.as_ref().unwrap().thread_id, scope.thread_id);
    assert!(
        f.service
            .executor
            .requests
            .last()
            .unwrap()
            .prompt
            .contains("report.md produced")
    );
    f.observe(
        &resumed,
        ExecutionStatus::Completed(resumed.mapping.clone().unwrap(), "Finished".into()),
        "summary-done",
        6,
    );
    let count = f.service.executor.requests.len();
    for n in 7..30 {
        f.service.tick(n).unwrap();
    }
    assert_eq!(f.service.executor.requests.len(), count);
}

#[test]
fn tools_require_exact_current_scope_and_authorized_directory() {
    let mut f = Fixture::new();
    let op = f.submit();
    let scope = op.mapping.clone().unwrap();
    for bad in [
        NativeMapping {
            host_generation: "host-0".into(),
            ..scope.clone()
        },
        NativeMapping {
            thread_id: "other".into(),
            ..scope.clone()
        },
        NativeMapping {
            turn_id: "other".into(),
            ..scope.clone()
        },
    ] {
        assert!(
            f.service
                .tool(bad, "list", ToolCall::ListSessions { limit: 10 }, 1)
                .is_err()
        );
    }
    assert!(
        f.service
            .tool(
                scope.clone(),
                "escape",
                ToolCall::CreateTask {
                    prompt: "escape".into(),
                    directory: f.root.path().into(),
                    writes: true
                },
                1
            )
            .is_err()
    );
    assert!(
        f.service
            .tool(
                scope.clone(),
                "oversize",
                ToolCall::ListSessions { limit: 1000 },
                1
            )
            .is_err()
    );
    assert_eq!(
        f.service
            .tool(
                scope.clone(),
                "list",
                ToolCall::ListSessions { limit: 10 },
                1
            )
            .unwrap()[0]["thread_id"],
        "existing-desktop-thread"
    );
    assert!(
        serde_json::from_value::<ToolCall>(serde_json::json!({"Approve": {"approved": true}}))
            .is_err()
    );
    f.observe(
        &op,
        ExecutionStatus::Completed(scope.clone(), "done".into()),
        "done",
        2,
    );
    assert!(
        f.service
            .tool(
                scope,
                "late",
                ToolCall::Summarize {
                    text: "late".into()
                },
                3
            )
            .is_err()
    );
}

#[test]
fn native_approval_requires_explicit_human_and_kind_match() {
    let mut f = Fixture::new();
    f.task("work", false);
    f.service.tick(0).unwrap();
    let op = f.op(Role::Execution);
    let scope = op.mapping.clone().unwrap();
    f.observe(
        &op,
        ExecutionStatus::Waiting(
            scope,
            UserQuestion {
                request_id: "approval".into(),
                kind: QuestionKind::Approval,
                text: "Allow write?".into(),
            },
        ),
        "approval",
        1,
    );
    assert!(f.service.executor.responses.is_empty());
    assert!(
        f.service
            .respond(
                "bad",
                &op.id,
                "approval",
                HumanResponse::Answer("yes".into()),
                2
            )
            .is_err()
    );
    assert!(
        f.service
            .respond(
                "wrong-id",
                &op.id,
                "old",
                HumanResponse::Approval { approved: true },
                2
            )
            .is_err()
    );
    f.service
        .respond(
            "human",
            &op.id,
            "approval",
            HumanResponse::Approval { approved: false },
            2,
        )
        .unwrap();
    assert_eq!(
        f.service.executor.responses[0].response,
        HumanResponse::Approval { approved: false }
    );
}

#[test]
fn coordinator_input_holds_tool_call_until_human_response() {
    let mut f = Fixture::new();
    let op = f.submit();
    let scope = op.mapping.clone().unwrap();
    f.service
        .tool(
            scope.clone(),
            "question-call",
            ToolCall::RequestInput {
                request_id: "format".into(),
                text: "Which format?".into(),
            },
            1,
        )
        .unwrap();
    assert!(f.service.executor.replies.is_empty());
    f.service.tick(2).unwrap();
    assert_eq!(f.service.operations()[0].phase, OperationPhase::Waiting);
    assert!(
        f.service
            .tool(
                scope,
                "other",
                ToolCall::Summarize {
                    text: "cannot mutate while waiting".into()
                },
                2
            )
            .is_err()
    );
    f.service
        .respond(
            "answer",
            &op.id,
            "format",
            HumanResponse::Answer("markdown".into()),
            3,
        )
        .unwrap();
    assert!(f.service.executor.responses.is_empty());
    assert_eq!(f.service.executor.replies[0].call_id, "question-call");
    assert_eq!(f.service.executor.replies[0].result["answer"], "markdown");
}

#[test]
fn takeover_interrupts_coordinator_and_waiting_turns_and_release_never_requeues() {
    let mut f = Fixture::new();
    let coord = f.submit();
    let first = f.task("a", true);
    let queued = f.task("b", true);
    f.service.tick(0).unwrap();
    let execution = f.op(Role::Execution);
    f.observe(
        &execution,
        ExecutionStatus::Waiting(
            execution.mapping.clone().unwrap(),
            UserQuestion {
                request_id: "q".into(),
                kind: QuestionKind::Input,
                text: "Question".into(),
            },
        ),
        "q",
        1,
    );
    f.service.pause("takeover", 2).unwrap();
    assert_eq!(f.service.executor.interrupts.len(), 2);
    assert!(
        f.service
            .tasks()
            .unwrap()
            .iter()
            .all(|t| t.status == TaskStatus::Paused)
    );
    assert!(
        f.service
            .respond(
                "late",
                &execution.id,
                "q",
                HumanResponse::Answer("a".into()),
                3
            )
            .is_err()
    );
    f.service.release_takeover("release", 3).unwrap();
    for op in [coord, execution] {
        f.observe(
            &op,
            ExecutionStatus::Interrupted(op.mapping.clone().unwrap()),
            &format!("stopped:{}", op.id),
            4,
        );
    }
    let count = f.service.executor.requests.len();
    f.service.tick(5).unwrap();
    assert_eq!(f.service.executor.requests.len(), count);
    assert_eq!(
        f.service.store.task(&queued.id).unwrap().status,
        TaskStatus::Paused
    );
    f.service
        .apply("resume", Command::ResumeTask { task_id: first.id }, 6)
        .unwrap();
    f.service.tick(6).unwrap();
    assert_eq!(f.service.executor.requests.len(), count + 1);
}

#[test]
fn native_waiting_holds_all_three_slots() {
    let mut f = Fixture::new();
    for i in 0..4 {
        f.task(&format!("t{i}"), false);
    }
    f.service.tick(0).unwrap();
    assert_eq!(f.service.executor.requests.len(), 3);
    for op in f.service.operations() {
        f.observe(
            &op,
            ExecutionStatus::Waiting(
                op.mapping.clone().unwrap(),
                UserQuestion {
                    request_id: format!("q-{}", op.id),
                    kind: QuestionKind::Input,
                    text: "Need input".into(),
                },
            ),
            &format!("q:{}", op.id),
            1,
        );
    }
    f.service.tick(2).unwrap();
    assert_eq!(
        f.service
            .executor
            .requests
            .iter()
            .filter(|r| r.role == Role::Execution)
            .count(),
        3
    );
    assert_eq!(
        f.service
            .tasks()
            .unwrap()
            .iter()
            .filter(|t| t.active_run_id.is_some())
            .count(),
        3
    );
}

#[test]
fn lost_native_start_receipt_is_reconciled_without_duplicate_dispatch() {
    let mut f = Fixture::new();
    f.task("a", false);
    f.service.executor.lose_start = true;
    f.service.tick(0).unwrap();
    assert_eq!(f.service.executor.requests.len(), 1);
    assert_eq!(f.op(Role::Execution).phase, OperationPhase::Unknown);
    f.service.tick(1).unwrap();
    assert_eq!(f.service.executor.requests.len(), 1);
    assert_eq!(f.op(Role::Execution).phase, OperationPhase::Running);
}

#[test]
fn restart_unknown_blocks_fresh_work_and_not_dispatched_stays_paused() {
    let mut f = Fixture::new();
    let first = f.task("a", true);
    f.task("b", false);
    f.service.executor.lose_start = true;
    f.service.tick(0).unwrap();
    let op = f.op(Role::Execution);
    let mut fake = f.service.executor.clone();
    fake.statuses.clear();
    let db = f.root.path().join("service.db");
    drop(f.service);
    let mut reopened = AssistantService::open(&db, "pc", "profile", "host-1", fake).unwrap();
    reopened.tick(1).unwrap();
    assert_eq!(reopened.executor.requests.len(), 1);
    reopened
        .observe(
            &op.id,
            ExecutionStatus::NotDispatched,
            "proved-not-dispatched",
            2,
        )
        .unwrap();
    assert_eq!(
        reopened.store.task(&first.id).unwrap().status,
        TaskStatus::Paused
    );
    reopened.tick(3).unwrap();
    assert_eq!(reopened.executor.requests.len(), 2);
    assert_eq!(
        reopened.store.task(&first.id).unwrap().status,
        TaskStatus::Paused
    );
}

#[test]
fn service_owner_lock_and_profile_isolation() {
    let mut f = Fixture::new();
    f.task("a", false);
    let db = f.root.path().join("service.db");
    assert!(AssistantService::open(&db, "pc", "profile", "host-1", Fake::default()).is_err());
    let other = AssistantService::open(&db, "pc", "other", "host-1", Fake::default()).unwrap();
    assert_ne!(f.service.assistant_id(), other.assistant_id());
    assert!(other.tasks().unwrap().is_empty());
}

#[test]
fn settings_and_explicit_memory_crud_survive_reopen_without_text_copies() {
    let mut f = Fixture::new();
    let settings = Settings {
        personality: "Patient".into(),
        tone: "Warm".into(),
        model: Some("host-known-model".into()),
        timezone: "Asia/Shanghai".into(),
        notifications: NotificationSettings {
            completed: false,
            ..Default::default()
        },
    };
    f.service
        .update_settings("settings", settings.clone(), 0)
        .unwrap();
    let receipt = f
        .service
        .apply(
            "remember",
            Command::Remember {
                text: "unique-memory-old".into(),
            },
            0,
        )
        .unwrap();
    let CommandResult::Memory(id) = receipt.result else {
        panic!()
    };
    f.service
        .apply(
            "edit",
            Command::EditMemory {
                memory_id: id.clone(),
                text: "unique-memory-current".into(),
            },
            0,
        )
        .unwrap();
    assert!(
        !serde_json::to_string(&f.service.state)
            .unwrap()
            .contains("unique-memory")
    );
    let db = f.root.path().join("service.db");
    let fake = f.service.executor.clone();
    drop(f.service);
    let mut reopened = AssistantService::open(&db, "pc", "profile", "host-1", fake).unwrap();
    assert_eq!(reopened.settings(), &settings);
    assert_eq!(
        reopened.memories().unwrap()[0].text,
        "unique-memory-current"
    );
    reopened
        .apply("delete", Command::DeleteMemory { memory_id: id }, 1)
        .unwrap();
    assert!(reopened.memories().unwrap().is_empty());
    assert!(
        !serde_json::to_string(&reopened.state)
            .unwrap()
            .contains("unique-memory")
    );
}

#[test]
fn duplicate_events_notifications_and_wrong_native_mapping_are_fenced() {
    let mut f = Fixture::new();
    f.task("a", false);
    f.service.tick(0).unwrap();
    let op = f.op(Role::Execution);
    let mapping = op.mapping.clone().unwrap();
    assert!(
        f.service
            .observe(
                &op.id,
                ExecutionStatus::Completed(
                    NativeMapping {
                        turn_id: "wrong".into(),
                        ..mapping.clone()
                    },
                    "wrong".into()
                ),
                "wrong",
                1
            )
            .is_err()
    );
    f.observe(
        &op,
        ExecutionStatus::Completed(mapping.clone(), "done".into()),
        "done",
        2,
    );
    f.service
        .observe(
            &op.id,
            ExecutionStatus::Completed(mapping.clone(), "done".into()),
            "duplicate-native-event",
            3,
        )
        .unwrap();
    assert_eq!(
        f.service
            .events(0, 256)
            .unwrap()
            .iter()
            .filter(|e| e.kind == "completed")
            .count(),
        1
    );
    assert!(
        f.service
            .observe(
                &op.id,
                ExecutionStatus::Failed(mapping, "changed".into()),
                "changed",
                4
            )
            .is_err()
    );
    assert!(f.service.events(0, 1000).is_err());
}

#[test]
fn lost_human_response_is_never_replayed() {
    let mut f = Fixture::new();
    f.task("a", false);
    f.service.tick(0).unwrap();
    let op = f.op(Role::Execution);
    f.observe(
        &op,
        ExecutionStatus::Waiting(
            op.mapping.clone().unwrap(),
            UserQuestion {
                request_id: "q".into(),
                kind: QuestionKind::Input,
                text: "Question".into(),
            },
        ),
        "q",
        1,
    );
    f.service.executor.lose_response = true;
    assert!(
        f.service
            .respond(
                "answer",
                &op.id,
                "q",
                HumanResponse::Answer("answer".into()),
                2
            )
            .is_err()
    );
    assert!(
        f.service
            .respond(
                "answer",
                &op.id,
                "q",
                HumanResponse::Answer("answer".into()),
                3
            )
            .is_err()
    );
    assert_eq!(f.service.executor.responses.len(), 1);
    assert!(
        f.service
            .respond(
                "different-command",
                &op.id,
                "q",
                HumanResponse::Answer("answer".into()),
                4
            )
            .is_err()
    );
    assert_eq!(f.service.executor.responses.len(), 1);
}

#[test]
fn same_question_with_different_command_id_is_answered_only_once() {
    let mut f = Fixture::new();
    f.task("a", false);
    f.service.tick(0).unwrap();
    let op = f.op(Role::Execution);
    f.observe(
        &op,
        ExecutionStatus::Waiting(
            op.mapping.clone().unwrap(),
            UserQuestion {
                request_id: "q".into(),
                kind: QuestionKind::Input,
                text: "Question".into(),
            },
        ),
        "q",
        1,
    );
    f.service
        .respond(
            "one",
            &op.id,
            "q",
            HumanResponse::Answer("answer".into()),
            2,
        )
        .unwrap();
    f.service
        .respond(
            "two",
            &op.id,
            "q",
            HumanResponse::Answer("answer".into()),
            3,
        )
        .unwrap();
    assert_eq!(f.service.executor.responses.len(), 1);
}

#[test]
fn dynamic_question_and_notification_survive_restart_without_duplicate_notification() {
    let mut f = Fixture::new();
    let op = f.submit();
    f.service
        .tool(
            op.mapping.clone().unwrap(),
            "input-call",
            ToolCall::RequestInput {
                request_id: "q".into(),
                text: "Question".into(),
            },
            1,
        )
        .unwrap();
    let db = f.root.path().join("service.db");
    let fake = f.service.executor.clone();
    drop(f.service);
    let mut reopened = AssistantService::open(&db, "pc", "profile", "host-1", fake).unwrap();
    reopened.tick(2).unwrap();
    assert_eq!(reopened.operations()[0].phase, OperationPhase::Waiting);
    reopened
        .respond(
            "human",
            &op.id,
            "q",
            HumanResponse::Answer("answer".into()),
            3,
        )
        .unwrap();
    assert_eq!(reopened.executor.replies[0].result["answer"], "answer");
    assert_eq!(
        reopened
            .events(0, 256)
            .unwrap()
            .iter()
            .filter(|e| e.kind == "waiting_user" && e.notify)
            .count(),
        1
    );
}

#[test]
fn native_question_recovery_does_not_notify_twice() {
    let mut f = Fixture::new();
    f.task("a", false);
    f.service.tick(0).unwrap();
    let op = f.op(Role::Execution);
    f.observe(
        &op,
        ExecutionStatus::Waiting(
            op.mapping.clone().unwrap(),
            UserQuestion {
                request_id: "q".into(),
                kind: QuestionKind::Input,
                text: "Question".into(),
            },
        ),
        "q",
        1,
    );
    let db = f.root.path().join("service.db");
    let fake = f.service.executor.clone();
    drop(f.service);
    let mut reopened = AssistantService::open(&db, "pc", "profile", "host-1", fake).unwrap();
    reopened.tick(2).unwrap();
    assert_eq!(
        reopened
            .events(0, 256)
            .unwrap()
            .iter()
            .filter(|e| e.kind == "waiting_user" && e.notify)
            .count(),
        1
    );
}

#[test]
fn due_schedules_skip_offline_and_pause_misses_without_model_heartbeat() {
    let mut f = Fixture::new();
    f.service.tick(0).unwrap();
    let spec = ScheduleSpec {
        kind: ScheduleKind::Interval {
            anchor_ms: 1000,
            every_ms: 1000,
        },
        zone: ZoneRules::utc(),
        prompt: "timed".into(),
        directory: f.root.path().join("work"),
        writes: false,
    };
    let schedule = f.service.add_schedule("schedule", spec.clone(), 0).unwrap();
    assert_eq!(
        f.service.add_schedule("schedule", spec, 0).unwrap(),
        schedule
    );
    f.service.tick(1000).unwrap();
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    f.service.tick(1000).unwrap();
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    let db = f.root.path().join("service.db");
    let fake = f.service.executor.clone();
    drop(f.service);
    let mut reopened = AssistantService::open(&db, "pc", "profile", "host-1", fake).unwrap();
    reopened.tick(1_000_000).unwrap();
    assert_eq!(reopened.tasks().unwrap().len(), 1);
    assert_eq!(reopened.schedules()[0].next_ms, Some(1_001_000));
    assert_eq!(
        reopened
            .events(0, 256)
            .unwrap()
            .iter()
            .filter(|e| e.kind == "schedule_missed")
            .count(),
        1
    );
    reopened.remove_schedule("remove", &schedule.id).unwrap();
    reopened.remove_schedule("remove", &schedule.id).unwrap();
    assert!(reopened.schedules().is_empty());
}

#[test]
fn claim_save_crash_window_recovers_orphan_lease_without_dispatch() {
    let mut f = Fixture::new();
    f.task("a", true);
    let lease = f.service.store.claim_next(0).unwrap().unwrap();
    let db = f.root.path().join("service.db");
    drop(f.service);
    let mut reopened =
        AssistantService::open(&db, "pc", "profile", "host-1", Fake::default()).unwrap();
    assert_eq!(reopened.operations()[0].id, lease.run_id);
    reopened.tick(1).unwrap();
    assert!(reopened.executor.requests.is_empty());
    assert!(
        reopened
            .store
            .task(&lease.task.id)
            .unwrap()
            .active_run_id
            .is_some()
    );
}

#[test]
fn takeover_store_save_crash_is_completed_on_reopen() {
    let mut f = Fixture::new();
    let task = f.task("queued", false);
    f.service
        .store
        .apply("takeover-before-save", Command::TakeOver)
        .unwrap();
    let db = f.root.path().join("service.db");
    drop(f.service);
    let mut reopened =
        AssistantService::open(&db, "pc", "profile", "host-1", Fake::default()).unwrap();
    reopened.release_takeover("release", 1).unwrap();
    reopened.tick(2).unwrap();
    assert_eq!(
        reopened.store.task(&task.id).unwrap().status,
        TaskStatus::Paused
    );
    assert!(reopened.executor.requests.is_empty());
}

#[test]
fn paused_schedule_misses_notify_once_and_zone_refresh_preserves_name() {
    let mut f = Fixture::new();
    f.service.tick(0).unwrap();
    let spec = ScheduleSpec {
        kind: ScheduleKind::Interval {
            anchor_ms: 1000,
            every_ms: 1000,
        },
        zone: ZoneRules::utc(),
        prompt: "timed".into(),
        directory: f.root.path().join("work"),
        writes: false,
    };
    let s = f.service.add_schedule("schedule", spec, 0).unwrap();
    f.service.pause("pause", 0).unwrap();
    for now in [1000, 2000, 3000] {
        f.service.tick(now).unwrap();
    }
    assert!(f.service.tasks().unwrap().is_empty());
    assert_eq!(
        f.service
            .events(0, 256)
            .unwrap()
            .iter()
            .filter(|e| e.kind == "schedule_missed")
            .count(),
        1
    );
    let mut bad = ZoneRules::utc();
    bad.name = "other".into();
    assert!(
        f.service
            .update_schedule_zone("change", &s.id, bad, 3000)
            .is_err()
    );
    f.service
        .update_schedule_zone("refresh", &s.id, ZoneRules::utc(), 3000)
        .unwrap();
    assert_eq!(f.service.schedules()[0].next_ms, Some(4000));
}

#[test]
fn event_id_reuse_with_different_payload_is_rejected_before_transition() {
    let mut f = Fixture::new();
    f.task("a", false);
    f.service.tick(0).unwrap();
    let op = f.op(Role::Execution);
    let mapping = op.mapping.clone().unwrap();
    f.observe(
        &op,
        ExecutionStatus::Waiting(
            mapping.clone(),
            UserQuestion {
                request_id: "q".into(),
                kind: QuestionKind::Input,
                text: "Question".into(),
            },
        ),
        "event-1",
        1,
    );
    assert!(
        f.service
            .observe(
                &op.id,
                ExecutionStatus::Completed(mapping, "done".into()),
                "event-1",
                2
            )
            .is_err()
    );
    assert_eq!(
        f.service.tasks().unwrap()[0].status,
        TaskStatus::WaitingUser
    );
}
