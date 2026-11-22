use super::*;

fn dispatch(f: &mut Fixture, lead: &Operation, call: &str, title: &str, model: Option<&str>) -> String {
    let result = f
        .service
        .tool(
            lead.mapping.clone().unwrap(),
            call,
            ToolCall::DispatchSession {
                title: title.into(),
                prompt: format!("Do {call} completely"),
                directory: f.root.path().join("work"),
                writes: false,
                use_worktree: None,
                model: model.map(str::to_owned),
            },
            1,
        )
        .unwrap();
    assert_eq!(result["status"], "queued");
    assert_eq!(result["workspace_mode"], "direct");
    result["task_id"].as_str().unwrap().into()
}

fn running(f: &Fixture, task: &str) -> Operation {
    f.service
        .operations()
        .into_iter()
        .find(|o| o.task_id.as_deref() == Some(task) && o.phase.live())
        .unwrap()
}

fn coordinator_requests(f: &Fixture) -> Vec<&StartRequest> {
    f.service
        .executor
        .requests
        .iter()
        .filter(|r| r.role == Role::Coordinator)
        .collect()
}

#[test]
fn dispatch_session_returns_immediately_and_frees_coordinator() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let scope = lead.mapping.clone().unwrap();
    let task = dispatch(&mut f, &lead, "fix-login", "修复登录超时", None);
    // The answer is back before any native session exists for the job.
    assert_eq!(f.service.executor.requests.len(), 1);
    assert_eq!(f.service.executor.replies.len(), 1);
    assert_eq!(f.service.store.task(&task).unwrap().status, TaskStatus::Queued);
    // The same call replayed is the same job.
    assert_eq!(dispatch(&mut f, &lead, "fix-login", "修复登录超时", None), task);
    assert_eq!(f.service.tasks().unwrap().len(), 1);

    // The job starts on its own while the coordinator's turn is still open.
    f.service.tick(2).unwrap();
    assert_eq!(f.service.executor.requests.len(), 2);
    let started = &f.service.executor.requests[1];
    assert_eq!(started.role, Role::Execution);
    assert_eq!(started.prompt, "Do fix-login completely");
    let jobs = f.service.jobs().unwrap();
    assert_eq!(jobs.len(), 1);
    assert_eq!(jobs[0]["taskId"], task);
    assert_eq!(jobs[0]["title"], "修复登录超时");
    assert_eq!(jobs[0]["status"], "running");
    assert_eq!(jobs[0]["threadId"], "thread-2");

    // With its turn over, the coordinator takes the next message while the job keeps running.
    finish(&mut f, &lead, 3);
    f.service
        .submit("phone-2", "Another thing", vec![f.root.path().join("work")], 4)
        .unwrap();
    f.service.tick(4).unwrap();
    assert_eq!(coordinator_requests(&f).len(), 2);
    assert_eq!(running(&f, &task).phase, OperationPhase::Running);

    let lead = f.op(Role::Coordinator);
    let outside = f.root.path().join("elsewhere");
    std::fs::create_dir(&outside).unwrap();
    for (call, title, directory) in [
        ("outside", "ok".to_owned(), outside),
        ("long", "长".repeat(121), f.root.path().join("work")),
        ("blank", "  ".to_owned(), f.root.path().join("work")),
    ] {
        assert!(
            f.service
                .tool(
                    lead.mapping.clone().unwrap(),
                    call,
                    ToolCall::DispatchSession {
                        title,
                        prompt: "p".into(),
                        directory,
                        writes: false,
                        use_worktree: None,
                        model: None,
                    },
                    5,
                )
                .is_err(),
            "{call} must be refused"
        );
    }
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    // A scope from the finished turn can no longer dispatch.
    assert!(
        f.service
            .tool(
                scope,
                "stale",
                ToolCall::ListJobs { limit: 5 },
                6
            )
            .is_err()
    );
}

#[test]
fn steer_session_routes_running_and_idle_jobs_idempotently() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let scope = lead.mapping.clone().unwrap();
    let task = dispatch(&mut f, &lead, "report", "季度简报", None);
    f.service.tick(2).unwrap();
    let first = running(&f, &task);
    let steer = |f: &mut Fixture, call: &str, target: &str, text: &str, now: i64| {
        f.service.tool(
            scope.clone(),
            call,
            ToolCall::SteerSession {
                task_id: target.into(),
                instruction: text.into(),
            },
            now,
        )
    };

    // Running: the correction joins the current native turn.
    f.service.executor.append_outcome = Some(AppendOutcome::Delivered);
    let queued = steer(&mut f, "steer-1", &task, "Use the staging database", 3).unwrap();
    assert_eq!(queued["task_id"], task);
    f.service.tick(4).unwrap();
    assert_eq!(f.service.executor.appends.len(), 1);
    assert_eq!(f.service.executor.appends[0].1, first.mapping.clone().unwrap());
    assert_eq!(f.service.executor.appends[0].2, "Use the staging database");
    assert_eq!(f.service.deliveries()[0].status, "sent");
    steer(&mut f, "steer-1", &task, "Use the staging database", 5).unwrap();
    f.service.tick(6).unwrap();
    assert_eq!(f.service.executor.appends.len(), 1, "a replayed call must not steer twice");
    assert_eq!(f.service.executor.requests.len(), 2);

    // Idle: the follow-up continues in the job's own native session.
    finish(&mut f, &first, 7);
    steer(&mut f, "steer-2", &task, "Add a risks section", 8).unwrap();
    f.service.tick(9).unwrap();
    assert_eq!(f.service.executor.appends.len(), 1);
    let resumed = f.service.executor.requests.last().unwrap();
    assert_eq!(resumed.role, Role::Execution);
    assert_eq!(resumed.thread_id.as_deref(), Some("thread-2"));
    assert!(resumed.prompt.starts_with("Add a risks section"));
    steer(&mut f, "steer-2", &task, "Add a risks section", 10).unwrap();
    f.service.tick(11).unwrap();
    assert_eq!(f.service.executor.requests.len(), 3);

    // Still one job: the follow-up is its newest task, not a second card.
    let jobs = f.service.jobs().unwrap();
    assert_eq!(jobs.len(), 1);
    assert_eq!(jobs[0]["taskId"], task);
    assert_ne!(jobs[0]["activeTaskId"], task);
    assert_eq!(jobs[0]["status"], "running");
    assert_eq!(jobs[0]["threadId"], "thread-2");
    // Naming the follow-up task reaches the same job.
    let follow_up = jobs[0]["activeTaskId"].as_str().unwrap().to_owned();
    assert_eq!(steer(&mut f, "steer-3", &follow_up, "Shorter", 12).unwrap()["task_id"], task);

    // The coordinator was never restarted to carry these.
    assert_eq!(coordinator_requests(&f).len(), 1);
    assert!(steer(&mut f, "steer-missing", "no-such-task", "x", 13).is_err());
    let plain = f.task("plain", false);
    assert!(steer(&mut f, "steer-plain", &plain.id, "x", 14).is_err());
}

#[test]
fn control_session_reads_status_and_stops_only_on_native_evidence() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let scope = lead.mapping.clone().unwrap();
    let task = dispatch(&mut f, &lead, "build", "构建发布包", None);
    f.service.tick(2).unwrap();
    let op = running(&f, &task);
    let control = |f: &mut Fixture, call: &str, action: SessionControl, now: i64| {
        f.service
            .tool(
                scope.clone(),
                call,
                ToolCall::ControlSession {
                    task_id: task.clone(),
                    action,
                },
                now,
            )
            .unwrap()
    };
    assert_eq!(control(&mut f, "status-1", SessionControl::Status, 3)["status"], "running");
    assert!(f.service.executor.interrupts.is_empty());

    assert_eq!(control(&mut f, "stop-1", SessionControl::Stop, 4)["status"], "stop_requested");
    // The interrupt is repeated until native evidence arrives, always at this one turn.
    assert!(!f.service.executor.interrupts.is_empty());
    assert!(f.service.executor.interrupts.iter().all(|m| Some(m) == op.mapping.as_ref()));
    f.observe(&op, ExecutionStatus::Interrupted(op.mapping.clone().unwrap()), "stopped", 5);
    assert_eq!(control(&mut f, "status-2", SessionControl::Status, 6)["status"], "paused");
    let interrupts = f.service.executor.interrupts.len();
    // Stopping what has stopped is a read, not another interrupt.
    assert_eq!(control(&mut f, "stop-2", SessionControl::Stop, 7)["status"], "paused");
    assert_eq!(f.service.executor.interrupts.len(), interrupts);

    // A user's stop is recorded for the phone but does not make the coordinator speak.
    let settled = f.service.events(0, 256).unwrap().into_iter().find(|e| e.kind == "job_settled").unwrap();
    let detail: serde_json::Value = serde_json::from_str(&settled.detail).unwrap();
    assert_eq!(detail["status"], "stopped");
    assert!(!settled.wake_coordinator);
    let listed = f
        .service
        .tool(scope.clone(), "list", ToolCall::ListJobs { limit: 30 }, 8)
        .unwrap();
    assert_eq!(listed.as_array().unwrap().len(), 1);
    assert_eq!(listed[0]["taskId"], task);
}

#[test]
fn settled_jobs_wake_coordinator_with_bounded_summary_only() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let a = dispatch(&mut f, &lead, "job-a", "修复登录超时", None);
    let b = dispatch(&mut f, &lead, "job-b", "整理行业动态", None);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let (op_a, op_b) = (running(&f, &a), running(&f, &b));
    assert_eq!(coordinator_requests(&f).len(), 1);

    // A question from a job goes to the user; it must not start a coordinator turn.
    let question = UserQuestion {
        request_id: "native-approval-7".into(),
        kind: QuestionKind::Approval,
        text: "Run cargo test?".into(),
    };
    f.observe(&op_b, ExecutionStatus::Waiting(op_b.mapping.clone().unwrap(), question), "ask", 500);
    f.service.tick(600).unwrap();
    assert_eq!(coordinator_requests(&f).len(), 1);
    f.observe(&op_b, ExecutionStatus::Running(op_b.mapping.clone().unwrap()), "resumed", 700);

    let transcript = format!("{}\n\"quoted\" \\ tail", "x".repeat(60_000));
    f.observe(&op_a, ExecutionStatus::Completed(op_a.mapping.clone().unwrap(), transcript.clone()), "a-done", 1000);
    let events = f.service.events(0, 256).unwrap();
    let completed = events.iter().find(|e| e.kind == "completed" && e.operation_id.as_deref() == Some(&op_a.id)).unwrap();
    assert!(!completed.wake_coordinator, "the full result must never be the wake");
    let settled = events.iter().find(|e| e.kind == "job_settled").unwrap();
    assert!(settled.wake_coordinator);
    assert!(settled.detail.len() <= 2048, "{}", settled.detail.len());
    let detail: serde_json::Value = serde_json::from_str(&settled.detail).unwrap();
    assert_eq!(detail["kind"], "job_settled");
    assert_eq!(detail["taskId"], a);
    assert_eq!(detail["title"], "修复登录超时");
    assert_eq!(detail["status"], "completed");
    assert_eq!(detail["threadId"], op_a.mapping.as_ref().unwrap().thread_id);
    assert_eq!(detail["truncated"], true);
    assert!(detail["summary"].as_str().unwrap().len() <= 1000);
    assert!(detail["error"].is_null());

    // Inside the window nothing starts; the second result joins the same wake.
    f.service.tick(1100).unwrap();
    assert_eq!(coordinator_requests(&f).len(), 1);
    f.observe(&op_b, ExecutionStatus::Failed(op_b.mapping.clone().unwrap(), "e".repeat(5000)), "b-failed", 1200);
    f.service.tick(2400).unwrap();
    assert_eq!(coordinator_requests(&f).len(), 1);
    f.service.tick(2500).unwrap();
    let wakes = coordinator_requests(&f);
    assert_eq!(wakes.len(), 2, "both results arrive in one coordinator turn");
    let prompt = &wakes[1].prompt;
    assert!(prompt.contains(&a) && prompt.contains(&b));
    assert!(prompt.contains("job_settled"));
    assert!(!prompt.contains(&"x".repeat(1100)), "the transcript leaked into the coordinator");
    assert!(!prompt.contains(&"e".repeat(500)), "the full error leaked into the coordinator");
    assert!(prompt.contains("\nActive jobs: ["));
    assert!(!prompt.contains("Conversation members"));
    f.service.tick(9000).unwrap();
    assert_eq!(coordinator_requests(&f).len(), 2);

    // The job's own text is not copied into the main conversation as an assistant message.
    assert!(!f.service.direct_result(&f.service.operations().into_iter().find(|o| o.id == op_a.id).unwrap()));
}

#[test]
fn a_new_message_does_not_wait_for_the_settle_window() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let task = dispatch(&mut f, &lead, "job", "任务", None);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let op = running(&f, &task);
    f.observe(&op, ExecutionStatus::Completed(op.mapping.clone().unwrap(), "ok".into()), "done", 1000);
    f.service
        .submit("phone-2", "What happened?", vec![f.root.path().join("work")], 1001)
        .unwrap();
    f.service.tick(1001).unwrap();
    let wakes = coordinator_requests(&f);
    assert_eq!(wakes.len(), 2);
    assert!(wakes[1].prompt.contains("What happened?") && wakes[1].prompt.contains("job_settled"));
}

#[test]
fn active_jobs_summary_stays_bounded() {
    let mut f = Fixture::new();
    let lead = f.submit();
    for index in 0..40 {
        dispatch(&mut f, &lead, &format!("job-{index}"), &"很长的任务标题".repeat(15), None);
    }
    let summary = f.service.active_jobs_summary().unwrap();
    assert!(summary.len() <= 1200, "{}", summary.len());
    let listed: Vec<serde_json::Value> = serde_json::from_str(&summary).unwrap();
    let omitted = listed.last().unwrap()["omitted"].as_u64().unwrap() as usize;
    assert_eq!(listed.len() - 1 + omitted, 40);
    assert!(listed[0]["taskId"].is_string() && listed[0]["status"] == "queued");
    assert_eq!(
        f.service
            .tool(lead.mapping.clone().unwrap(), "list", ToolCall::ListJobs { limit: 30 }, 2)
            .unwrap()
            .as_array()
            .unwrap()
            .len(),
        30
    );
}

#[test]
fn coordinator_model_override_applies_only_to_coordinator_role() {
    let mut f = Fixture::new();
    let settings = Settings {
        model: Some("main-model".into()),
        coordinator_model: Some("fast-model".into()),
        ..Default::default()
    };
    f.service.update_settings("settings-1", settings, 0).unwrap();
    let lead = f.submit();
    let coordinator = &f.service.executor.requests[0];
    assert_eq!(coordinator.settings.model.as_deref(), Some("fast-model"));
    assert_eq!(coordinator.settings.coordinator_model, None);

    let plain = dispatch(&mut f, &lead, "plain", "默认模型", None);
    let chosen = dispatch(&mut f, &lead, "chosen", "指定模型", Some("special-model"));
    f.service.tick(2).unwrap();
    let model = |f: &Fixture, task: &str| {
        let op = running(f, task);
        f.service
            .executor
            .requests
            .iter()
            .find(|r| r.operation_id == op.id)
            .unwrap()
            .settings
            .model
            .clone()
    };
    assert_eq!(model(&f, &plain).as_deref(), Some("main-model"));
    assert_eq!(model(&f, &chosen).as_deref(), Some("special-model"));

    // Without an override the coordinator follows the main model, and stored settings
    // written before this field existed read and hash exactly as before.
    let default = Settings::default();
    assert!(serde_json::to_value(&default).unwrap().get("coordinator_model").is_none());
    assert_eq!(
        Settings { model: Some("m".into()), ..Default::default() }
            .for_role(Role::Coordinator, None)
            .model
            .as_deref(),
        Some("m")
    );
    let old: Settings = serde_json::from_str(
        r#"{"personality":"p","tone":"t","model":null,"timezone":"Asia/Shanghai","notifications":{"completed":true,"failed":true,"waiting_user":true}}"#,
    )
    .unwrap();
    assert_eq!(old.coordinator_model, None);
    assert!(
        Settings { coordinator_model: Some(" ".into()), ..Default::default() }
            .validate()
            .is_err()
    );
}
