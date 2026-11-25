use super::*;

#[test]
fn assistant_attachment_large_text_messages_are_batched_without_dropping_content() {
    let mut f=Fixture::new();
    for n in 0..4 {
        let inputs=(0..4).map(|_|AssistantInput::Text{id:uuid::Uuid::new_v4().to_string(),name:"full.txt".into(),text:"\n".repeat(256*1024)}).collect();
        f.service.submit_with_inputs(&format!("large-{n}"),"Read full attachments",vec![f.root.path().join("work")],inputs,n).unwrap();
    }
    for n in 0..4 {
        f.service.tick(10+n*2).unwrap();
        let request=f.service.executor.requests.last().unwrap();
        let native=crate::assistant::inputs::native_input(&request.prompt,&request.inputs);
        assert!(serde_json::to_vec(&native).unwrap().len()<pocket_runtime_ipc::support::MAX_FRAME-256*1024);
        assert_eq!(request.inputs.len(),4);
        assert!(request.inputs.iter().all(|input|matches!(input,AssistantInput::Text{text,..} if text.len()==256*1024)));
        let op=f.op(Role::Coordinator);
        f.observe(&op,ExecutionStatus::Completed(op.mapping.clone().unwrap(),"read".into()),&format!("done-{n}"),11+n*2);
    }
    assert!(f.service.state.coordinator.inbox.is_empty());assert_eq!(f.service.executor.requests.len(),4);
}

#[test]
fn assistant_attachment_coordinator_forwarding_carries_current_handoff_inputs() {
    let mut f=Fixture::new();
    let input=AssistantInput::Text{id:uuid::Uuid::new_v4().to_string(),name:"notes.txt".into(),text:"exact entrusted instructions".into()};
    f.service.submit_with_inputs("with-input","Forward notes",vec![f.root.path().join("work")],vec![input.clone()],1).unwrap();
    f.service.tick(2).unwrap();let lead=f.op(Role::Coordinator);
    let member_id=member(&mut f,&lead,"reader");
    let receipt=f.service.tool(lead.mapping.clone().unwrap(),"forward",ToolCall::Collaboration(CollaborationTool::SendToMember {member_id,message:"Use the notes".into()}),3).unwrap();
    assert_eq!(f.service.delivery_inputs(receipt["deliveryId"].as_str().unwrap()),vec![input]);
}

#[test]
fn assistant_attachment_survives_reopen_and_is_consumed_by_coordinator_task_and_followup() {
    let mut f=Fixture::new();let path=f.root.path().join("image.png");
    std::fs::write(&path,b"\x89PNG\r\n\x1a\nfixture").unwrap();
    let image=AssistantInput::Image{id:uuid::Uuid::new_v4().to_string(),name:"diagram.png".into(),path};
    let roots=vec![f.root.path().join("work")];
    f.service.submit_with_inputs("attachment-message","Read diagram",roots.clone(),vec![image.clone()],1).unwrap();
    f.service.submit_with_inputs("attachment-message","Read diagram",roots,vec![image.clone()],2).unwrap();
    let mut f=reopen_fixture(f);f.service.tick(3).unwrap();let lead=f.op(Role::Coordinator);
    assert_eq!(f.service.executor.requests.last().unwrap().inputs,vec![image.clone()]);
    let value=f.service.tool(lead.mapping.clone().unwrap(),"create",ToolCall::CreateTask {
        prompt:"Process diagram".into(),directory:f.root.path().join("work"),writes:false},4).unwrap();
    let receipt:CommandReceipt=serde_json::from_value(value).unwrap();
    let CommandResult::Task(task)=receipt.result else {panic!()};
    f.service.tick(5).unwrap();let execution=f.op(Role::Execution);
    assert_eq!(f.service.executor.requests.iter().find(|r|r.operation_id==execution.id).unwrap().inputs,vec![image.clone()]);
    f.observe(&execution,ExecutionStatus::Completed(execution.mapping.clone().unwrap(),"done".into()),"done",6);
    let text=AssistantInput::Text{id:uuid::Uuid::new_v4().to_string(),name:"notes.txt".into(),text:"Keep the blue label".into()};
    f.service.submit_targeted_with_inputs("attachment-followup","Revise",None,Some(&task.id),false,vec![text.clone()],7).unwrap();
    f.service.tick(8).unwrap();
    let next=f.service.executor.requests.iter().rev().find(|r|r.role==Role::Execution).unwrap();
    assert_eq!(next.inputs,vec![image,text]);
    assert_eq!(f.service.executor.requests.iter().filter(|r|r.operation_id==lead.id).count(),1);
}

#[test]
fn rebuild_ended_generation_releases_writer_slot_without_completing_or_replaying_unknown() {
    let mut f = Fixture::new();
    let original = f.task("old writer", true);
    f.service.tick(1).unwrap();
    let op = f.op(Role::Execution);
    f.observe(&op, ExecutionStatus::Unknown, "uncertain", 2);
    assert!(
        f.service
            .release_ended_resources(&BTreeSet::from(["host-1".to_owned()]))
            .is_err()
    );
    f.service.generation = "host-2".into();
    f.service
        .release_ended_resources(&BTreeSet::from(["host-1".to_owned()]))
        .unwrap();
    let old = f.service.store.task(&original.id).unwrap();
    assert!(old.active_run_id.is_none());
    assert!(old.reconciliation_required);
    assert_eq!(
        f.service.state.operations[&op.id].phase,
        OperationPhase::Unknown
    );
    f.service
        .apply(
            "pause-old",
            Command::PauseTask {
                task_id: original.id.clone(),
            },
            3,
        )
        .unwrap();
    assert_eq!(
        f.service.domain_snapshot("local").unwrap()["tasks"][0]["state"],
        "needs_verification"
    );
    assert!(
        f.service
            .apply(
                "resume-old",
                Command::ResumeTask {
                    task_id: original.id.clone()
                },
                4
            )
            .is_err()
    );
    assert!(
        f.service
            .apply(
                "retry-old",
                Command::RetryTask {
                    task_id: original.id
                },
                4
            )
            .is_err()
    );
    let fresh = f.task("new independent writer", true);
    let previous_queries=f.service.executor.queries;
    f.service.tick(5).unwrap();
    assert_eq!(f.service.executor.queries,previous_queries,"ended attempts must not be polled into a fabricated result");
    let run=f.service.store.task(&fresh.id).unwrap().active_run_id.unwrap();
    assert_ne!(run,op.id);
    assert!(f.service.executor.requests.iter().any(|request|request.operation_id==run && request.host_generation=="host-2"));
    assert_eq!(f.service.state.operations[&op.id].phase,OperationPhase::Unknown);
    assert_eq!(
        f.service
            .executor
            .requests
            .iter()
            .filter(|r| r.operation_id == op.id)
            .count(),
        1
    );
}

#[test]
fn rebuild_unknown_coordinator_keeps_main_identity_and_late_receipt_cannot_replace_successor() {
    let mut f = Fixture::new();
    f.submit();
    let first = f.op(Role::Coordinator);
    let member = member(&mut f, &first, "researcher");
    f.observe(&first, ExecutionStatus::Unknown, "lost-first", 2);
    f.service
        .submit(
            "independent",
            "A different goal",
            vec![f.root.path().join("work")],
            3,
        )
        .unwrap();
    f.service.tick(3).unwrap();
    let next = f
        .service
        .operations()
        .into_iter()
        .find(|o| o.role == Role::Coordinator && o.id != first.id)
        .unwrap();
    assert_eq!(next.phase, OperationPhase::Running);
    let thread = next.mapping.as_ref().unwrap().thread_id.clone();
    assert_eq!(f.service.coordinator_thread_id(), Some(thread.as_str()));
    assert_eq!(
        f.service
            .members()
            .iter()
            .find(|m| m.id == member)
            .unwrap()
            .main_thread_id,
        thread
    );
    f.observe(
        &first,
        ExecutionStatus::Completed(
            first.mapping.clone().unwrap(),
            "Late original result".into(),
        ),
        "late",
        4,
    );
    assert_eq!(f.service.coordinator_thread_id(), Some(thread.as_str()));
    assert_eq!(
        f.service
            .executor
            .requests
            .iter()
            .filter(|r| r.operation_id == first.id)
            .count(),
        1
    );
    assert_eq!(
        f.service.domain_snapshot("local").unwrap()["conversationId"],
        format!("main:{}", f.service.assistant_id())
    );
}

#[test]
fn rebuild_child_task_is_distinct_from_continuation_and_cannot_gain_authority() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let parent = f.task("parent", false);
    let make = |writes| CollaborationTool::AssignTask {
        prompt: "child goal".into(),
        directory: parent.directory.clone(),
        writes,
        member_id: None,
        group_id: None,
        dependencies: vec![],
        completion_criteria: "Evidence delivered".into(),
        parent_task_id: Some(parent.id.clone()),
    };
    assert!(
        f.service
            .tool(
                lead.mapping.clone().unwrap(),
                "illegal-child",
                ToolCall::Collaboration(make(true)),
                2
            )
            .is_err()
    );
    let value = collaboration_call(&mut f, &lead, "child", make(false));
    let child = value["result"]["value"]["id"].as_str().unwrap();
    let view = f.service.domain_snapshot("local").unwrap();
    let child = view["tasks"]
        .as_array()
        .unwrap()
        .iter()
        .find(|t| t["id"] == child)
        .unwrap();
    assert_eq!(child["parentTaskId"], parent.id);
    assert!(child["continuesTaskId"].is_null());
}

#[test]
fn rebuild_migration_is_idempotent_and_retains_all_message_ids() {
    let mut f = Fixture::new();
    let entries = (0..350)
        .map(|n| ConversationEntry {
            id: format!("legacy-{n}"),
            role: "User".into(),
            text: format!("message-{n}"),
            at_ms: n,
            member_id: None,
            task_id: None,
            group_id: None,
            source_thread_id: None,
        })
        .collect::<Vec<_>>();
    f.service
        .import_legacy_conversation(entries.clone())
        .unwrap();
    f.service
        .import_legacy_conversation(entries.clone())
        .unwrap();
    let task = f.task("legacy-task", false);
    f.service
        .remember_with_source(
            "remember",
            "Short answers",
            "user:legacy-0",
            "personal",
            true,
            1,
        )
        .unwrap();
    let f = reopen_fixture(f);
    let total: i64 = f
        .service
        .store
        .db
        .query_row(
            "SELECT count(*) FROM assistant_v2_entities WHERE kind='message'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(total, 350);
    let snapshot = f.service.domain_snapshot("local").unwrap();
    assert_eq!(snapshot["schema"], 2);
    assert_eq!(snapshot["tasks"][0]["id"], task.id);
    assert_eq!(snapshot["memories"].as_array().unwrap().len(), 1);
    assert!(
        AssistantService::open(
            &f.root.path().join("service.db"),
            "pc",
            "profile",
            "host-1",
            Fake::default()
        )
        .is_err(),
        "v1 and v2 must share the same exclusive consumer lock"
    );
}

#[test]
fn rebuild_failed_attempt_retries_with_new_identity_and_retained_result() {
    let mut f = Fixture::new();
    let task = f.task("report", false);
    f.service.tick(1).unwrap();
    let op = f.op(Role::Execution);
    f.observe(
        &op,
        ExecutionStatus::Failed(op.mapping.clone().unwrap(), "partial draft saved".into()),
        "failed",
        2,
    );
    f.service
        .apply(
            "retry",
            Command::RetryTask {
                task_id: task.id.clone(),
            },
            3,
        )
        .unwrap();
    f.service
        .apply(
            "retry",
            Command::RetryTask {
                task_id: task.id.clone(),
            },
            3,
        )
        .unwrap();
    f.service.tick(4).unwrap();
    let attempts = f
        .service
        .operations()
        .into_iter()
        .filter(|o| o.task_id.as_ref() == Some(&task.id))
        .collect::<Vec<_>>();
    assert_eq!(attempts.len(), 2);
    assert_ne!(attempts[0].id, attempts[1].id);
    let view = f.service.domain_snapshot("local").unwrap();
    assert!(
        view["artifacts"]
            .as_array()
            .unwrap()
            .iter()
            .any(|a| a["version"] == op.id && a["partial"] == true)
    );
}

#[test]
fn rebuild_unknown_attempt_is_specific_attention_and_not_retried() {
    let mut f = Fixture::new();
    let task = f.task("unknown", true);
    f.service.tick(1).unwrap();
    let op = f.op(Role::Execution);
    f.observe(&op, ExecutionStatus::Unknown, "unknown", 2);
    assert!(
        f.service
            .apply(
                "retry",
                Command::RetryTask {
                    task_id: task.id.clone()
                },
                3
            )
            .is_err()
    );
    let snapshot = f.service.domain_snapshot("local").unwrap();
    assert!(
        snapshot["attention"]
            .as_array()
            .unwrap()
            .iter()
            .any(|a| a["attemptId"] == op.id && a["replayAllowed"] == false)
    );
    let next = f.task("same-dir-writer", true);
    f.service.tick(4).unwrap();
    assert!(
        !f.service
            .operations()
            .iter()
            .any(|o| o.task_id.as_deref() == Some(&next.id)),
        "original writer reservation remains"
    );
    assert_eq!(
        f.service
            .executor
            .requests
            .iter()
            .filter(|r| r.operation_id == op.id)
            .count(),
        1
    );
}

#[test]
fn rebuild_memory_history_keeps_revisions_while_deleted_text_is_not_context() {
    let mut f = Fixture::new();
    let receipt = f
        .service
        .remember_with_source("remember", "Brief answers", "user:1", "personal", true, 1)
        .unwrap();
    let CommandResult::Memory(id) = receipt.result else {
        panic!()
    };
    f.service
        .apply(
            "edit",
            Command::EditMemory {
                memory_id: id.clone(),
                text: "Detailed answers".into(),
            },
            2,
        )
        .unwrap();
    f.service.undo_memory("undo", &id, 3).unwrap();
    let view = f.service.domain_snapshot("local").unwrap();
    assert_eq!(view["memoryRevisions"].as_array().unwrap().len(), 3);
    assert_eq!(view["memories"][0]["state"], "deleted");
    assert_eq!(view["memories"][0]["authority"], false);
    assert!(f.service.memories().unwrap().is_empty());
}

#[test]
fn rebuild_stop_requested_is_not_stop_confirmation_and_seen_is_monotonic() {
    let mut f = Fixture::new();
    let task = f.task("work", false);
    f.service.tick(1).unwrap();
    let op = f.op(Role::Execution);
    f.service
        .apply(
            "stop",
            Command::PauseTask {
                task_id: task.id.clone(),
            },
            2,
        )
        .unwrap();
    assert_eq!(
        f.service.domain_snapshot("local").unwrap()["tasks"][0]["state"],
        "stop_requested"
    );
    f.observe(
        &op,
        ExecutionStatus::Interrupted(op.mapping.clone().unwrap()),
        "confirmed",
        3,
    );
    let view = f.service.domain_snapshot("local").unwrap();
    assert_eq!(view["tasks"][0]["state"], "paused");
    let seq = view["tasks"][0]["activitySequence"].as_u64().unwrap();
    f.service.mark_task_seen("local", &task.id, seq).unwrap();
    f.service.mark_task_seen("local", &task.id, 0).unwrap();
    assert_eq!(
        f.service.domain_snapshot("local").unwrap()["tasks"][0]["unread"],
        false
    );
}
