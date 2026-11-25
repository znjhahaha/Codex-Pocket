use super::*;

fn send_in(f: &mut Fixture, conversation: &str, command: &str, now: i64) -> Operation {
    let root = f.root.path().join("work");
    f.service
        .in_conversation(conversation, |s| {
            s.submit(command, command, vec![root], now)
        })
        .unwrap();
    f.service.tick(now).unwrap();
    f.service
        .operations()
        .into_iter()
        .find(|op| {
            op.role == Role::Coordinator
                && op.phase.live()
                && f.service.origin_conversation("operation", &op.id) == conversation
        })
        .unwrap()
}

#[test]
fn conversations_create_send_and_restart_keep_distinct_identity() {
    let mut f = Fixture::new();
    let main = f.service.default_conversation_id();
    let original = f.submit();
    let chat = f
        .service
        .create_conversation("new", Some("Second"), 10)
        .unwrap();
    assert_eq!(
        f.service
            .create_conversation("new", Some("Second"), 20)
            .unwrap(),
        chat
    );
    assert!(
        f.service
            .create_conversation("new", Some("different"), 20)
            .is_err()
    );
    assert_eq!(f.service.conversation_thread_id(&chat.id), None);
    assert_eq!(
        f.service
            .in_conversation(&chat.id, |s| s.conversation_page(None, 10))
            .unwrap()["messages"],
        serde_json::json!([])
    );
    let next = send_in(&mut f, &chat.id, "new-message", 11);
    assert_ne!(
        original.mapping.as_ref().unwrap().thread_id,
        next.mapping.as_ref().unwrap().thread_id
    );
    let count = f.service.executor.requests.len();
    let root = f.root.path().join("work");
    f.service
        .in_conversation(&chat.id, |s| {
            s.submit("new-message", "new-message", vec![root.clone()], 15)
        })
        .unwrap();
    assert!(
        f.service
            .submit("new-message", "new-message", vec![root], 15)
            .is_err()
    );
    assert_eq!(f.service.executor.requests.len(), count);
    assert_eq!(f.service.selected_conversation_id(), main);
    let f = reopen_fixture(f);
    assert_eq!(f.service.conversations().len(), 2);
    assert_eq!(
        f.service.origin_conversation("operation", &next.id),
        chat.id
    );
    assert_eq!(
        f.service.conversation_thread_id(&main),
        original.mapping.as_ref().map(|m| m.thread_id.as_str())
    );
    assert_eq!(
        f.service.conversation_thread_id(&chat.id),
        next.mapping.as_ref().map(|m| m.thread_id.as_str())
    );
}

#[test]
fn old_task_result_wakes_only_its_origin_after_new_blank_chat() {
    let mut f = Fixture::new();
    let main = f.service.default_conversation_id();
    let lead = f.submit();
    let response = f
        .service
        .tool(
            lead.mapping.clone().unwrap(),
            "task",
            ToolCall::CreateTask {
                prompt: "old task".into(),
                directory: f.root.path().join("work"),
                writes: false,
            },
            1,
        )
        .unwrap();
    let task = response["result"]["value"]["id"]
        .as_str()
        .unwrap()
        .to_owned();
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let execution = f.op(Role::Execution);
    let chat = f.service.create_conversation("new", None, 4).unwrap();
    assert_eq!(
        f.service
            .tasks()
            .unwrap()
            .iter()
            .find(|t| t.id == task)
            .unwrap()
            .status,
        TaskStatus::Running
    );
    let next = send_in(&mut f, &chat.id, "second", 5);
    finish(&mut f, &next, 6);
    f.observe(
        &execution,
        ExecutionStatus::Completed(execution.mapping.clone().unwrap(), "old result".into()),
        "old-completion",
        7,
    );
    f.service.tick(8).unwrap();
    let report = f.service.executor.requests.last().unwrap();
    assert_eq!(
        f.service
            .origin_conversation("operation", &report.operation_id),
        main
    );
    assert!(report.prompt.contains("old result"));
    assert!(!report.prompt.contains("second"));
    let page = f
        .service
        .in_conversation(&chat.id, |s| s.conversation_page(None, 100))
        .unwrap();
    assert!(!page.to_string().contains("old result"));
    assert_eq!(f.service.origin_conversation("task", &task), main);
}

#[test]
fn interrupt_targets_exact_coordinator_preserves_tasks_and_queued_messages() {
    let mut f = Fixture::new();
    let main = f.service.default_conversation_id();
    let old = f.submit();
    f.task("running-task", false);
    let chat = f.service.create_conversation("new", None, 1).unwrap();
    let next = send_in(&mut f, &chat.id, "second", 2);
    let execution = f.op(Role::Execution);
    let root = f.root.path().join("work");
    f.service
        .in_conversation(&chat.id, |s| s.submit("queued", "queued", vec![root], 3))
        .unwrap();
    assert!(
        f.service
            .interrupt_conversation("wrong", &chat.id, &old.id, 4)
            .is_err()
    );
    assert!(f.service.executor.interrupts.is_empty());
    f.service
        .interrupt_conversation("stop", &chat.id, &next.id, 4)
        .unwrap();
    f.service
        .interrupt_conversation("stop", &chat.id, &next.id, 5)
        .unwrap();
    assert_eq!(
        f.service.executor.interrupts,
        vec![next.mapping.clone().unwrap()]
    );
    assert!(!f.service.is_paused());
    assert!(!f.service.state.operations[&old.id].suspended);
    assert!(!f.service.state.operations[&execution.id].suspended);
    assert_eq!(
        f.service.state.conversation_coordinators[&chat.id]
            .inbox
            .len(),
        1
    );
    assert_eq!(
        f.service.conversation_operation_id(&main),
        Some(old.id.as_str())
    );
    let mut f = reopen_fixture(f);
    f.observe(
        &next,
        ExecutionStatus::Interrupted(next.mapping.clone().unwrap()),
        "stopped",
        6,
    );
    f.service.tick(7).unwrap();
    let replacement = f.service.conversation_operation_id(&chat.id).unwrap();
    assert_ne!(replacement, next.id);
    assert_eq!(
        f.service.state.operations[&execution.id].phase,
        OperationPhase::Running
    );
}

#[test]
fn task_provenance_commits_before_crash_and_old_schema_migrates_without_rewriting_ids() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let existing = f.service.state.clone();
    let mut raw = serde_json::to_value(&existing).unwrap();
    for key in [
        "conversations",
        "conversation_coordinators",
        "conversation_origins",
    ] {
        raw.as_object_mut().unwrap().remove(key);
    }
    f.service
        .store
        .db
        .execute(
            "UPDATE assistant_service_state SET data=?1",
            [raw.to_string()],
        )
        .unwrap();
    let mut f = reopen_fixture(f);
    assert!(f.service.state.operations.contains_key(&lead.id));
    assert_eq!(
        f.service.origin_conversation("operation", &lead.id),
        f.service.default_conversation_id()
    );
    assert_eq!(f.service.state.journal.len(), existing.journal.len());
    let chat = f.service.create_conversation("new", None, 10).unwrap();
    let directory = f.root.path().join("work");
    let receipt = f
        .service
        .in_conversation(&chat.id, |s| {
            s.apply_bound_command(
                "task-atomic",
                Command::CreateTask {
                    prompt: "new task".into(),
                    directory,
                    writes: false,
                    not_before_ms: 0,
                },
            )
        })
        .unwrap();
    let CommandResult::Task(task) = receipt.result else {
        panic!()
    };
    let f = reopen_fixture(f);
    assert_eq!(f.service.origin_conversation("task", &task.id), chat.id);
}

#[test]
fn member_tasks_and_schedules_dispatch_in_their_own_chat() {
    let mut f = Fixture::new();
    let old = f.submit();
    let old_member = member(&mut f, &old, "Same name");
    let old_task = assigned(&mut f, &old, "old task", Some(&old_member), None, vec![]);
    finish(&mut f, &old, 1);
    let chat = f.service.create_conversation("new", None, 2).unwrap();
    let lead = send_in(&mut f, &chat.id, "new-message", 3);
    let new_member = member(&mut f, &lead, "Same name");
    let new_task = assigned(&mut f, &lead, "new task", Some(&new_member), None, vec![]);
    finish(&mut f, &lead, 4);
    f.service.tick(5).unwrap();
    for (task, conversation) in [
        (&old_task, f.service.default_conversation_id()),
        (&new_task, chat.id.clone()),
    ] {
        let op = f
            .service
            .operations()
            .into_iter()
            .find(|op| op.task_id.as_deref() == Some(task))
            .unwrap();
        assert_eq!(op.phase, OperationPhase::Running);
        assert_eq!(
            f.service.origin_conversation("operation", &op.id),
            conversation
        );
    }
    let directory = f.root.path().join("work");
    let schedule = f
        .service
        .in_conversation(&chat.id, |s| {
            s.add_schedule(
                "future",
                ScheduleSpec {
                    kind: ScheduleKind::Once { at_ms: 10 },
                    zone: ZoneRules::utc(),
                    prompt: "scheduled".into(),
                    directory,
                    writes: false,
                },
                6,
            )
        })
        .unwrap();
    let mut f = reopen_fixture(f);
    f.service.scheduler_started_at(6);
    f.service.tick(10).unwrap();
    let run = f
        .service
        .schedule_runs()
        .unwrap()
        .into_iter()
        .find(|r| r.schedule_id == schedule.id)
        .unwrap();
    assert_eq!(
        f.service
            .origin_conversation("task", run.task_id.as_ref().unwrap()),
        chat.id
    );
}
