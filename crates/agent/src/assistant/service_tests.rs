use super::*;

fn reopen_fixture(f: Fixture) -> Fixture {
    let Fixture { root, service } = f;
    let native = service.executor.clone();
    drop(service);
    let service = AssistantService::open(
        &root.path().join("service.db"),
        "pc",
        "profile",
        "host-1",
        native,
    )
    .unwrap();
    Fixture { root, service }
}

fn fail_pause_service_save(f: &Fixture, command_id: &str) {
    f.service
        .store
        .db
        .execute_batch(&format!(
            "CREATE TRIGGER fail_pause_save BEFORE UPDATE ON assistant_service_state
         WHEN EXISTS (SELECT 1 FROM json_each(NEW.data, '$.events')
                      WHERE json_extract(value, '$.id') = 'command:{command_id}')
         BEGIN SELECT RAISE(FAIL, 'simulated crash before final service save'); END;"
        ))
        .unwrap();
}

fn pause_crash_case(reassign: bool) {
    let mut f = Fixture::new();
    let lead = f.submit();
    let a = member(&mut f, &lead, "A");
    let b = member(&mut f, &lead, "B");
    let task = assigned(&mut f, &lead, "task", Some(&a), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted(
            "pending",
            "Extra work",
            Some(if reassign { &b } else { &a }),
            Some(&task),
            reassign,
            4,
        )
        .unwrap();
    fail_pause_service_save(&f, "human-pause-crash");
    assert!(
        f.service
            .apply(
                "human-pause-crash",
                Command::PauseTask {
                    task_id: task.clone()
                },
                5
            )
            .is_err()
    );
    assert_eq!(
        f.service.store.task(&task).unwrap().status,
        TaskStatus::Paused
    );
    f.service
        .store
        .db
        .execute_batch("DROP TRIGGER fail_pause_save")
        .unwrap();
    let mut f = reopen_fixture(f);
    f.observe(
        &original,
        ExecutionStatus::Interrupted(original.mapping.clone().unwrap()),
        "stopped-after-crash",
        6,
    );
    f.service.tick(7).unwrap();
    assert_eq!(
        f.service.deliveries()[0].status,
        "paused",
        "committed explicit pause must cancel old delivery after restart"
    );
    assert!(f.service.deliveries()[0].cancelled);
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    assert_eq!(f.service.executor.requests.len(), 2);
    assert_eq!(
        f.service
            .store
            .task(&task)
            .unwrap()
            .assignment
            .unwrap()
            .member_id,
        Some(a)
    );
    f.service
        .submit_targeted(
            "new-instruction",
            "Explicit new work",
            None,
            Some(&task),
            false,
            8,
        )
        .unwrap();
    f.service
        .apply("human-pause-crash", Command::PauseTask { task_id: task }, 9)
        .unwrap();
    assert_eq!(
        f.service.deliveries()[1].status,
        "queued",
        "replaying the committed pause must not cancel a new instruction"
    );
}

#[test]
fn backend_review_pause_crash_cancels_queued_supplement() {
    pause_crash_case(false);
}

#[test]
fn backend_review_pause_crash_cancels_pending_reassignment() {
    pause_crash_case(true);
}

#[test]
fn backend_review_internal_stop_crash_preserves_reassignment() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let a = member(&mut f, &lead, "A");
    let b = member(&mut f, &lead, "B");
    let task = assigned(&mut f, &lead, "task", Some(&a), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    fail_pause_service_save(&f, "reassign-stop:replace");
    assert!(
        f.service
            .submit_targeted("replace", "New approach", Some(&b), Some(&task), true, 4)
            .is_err()
    );
    f.service
        .store
        .db
        .execute_batch("DROP TRIGGER fail_pause_save")
        .unwrap();
    let mut f = reopen_fixture(f);
    f.observe(
        &original,
        ExecutionStatus::Interrupted(original.mapping.clone().unwrap()),
        "stopped-after-crash",
        5,
    );
    f.service.tick(6).unwrap();
    assert_eq!(f.service.deliveries()[0].status, "sent");
    assert_eq!(
        f.service
            .store
            .task(&task)
            .unwrap()
            .assignment
            .unwrap()
            .member_id,
        Some(b)
    );
    assert_eq!(f.service.tasks().unwrap().len(), 1);
}

fn correction_group(f: &mut Fixture) -> (String, String, String, Operation) {
    let lead = f.submit();
    let m = member(f, &lead, "Backend");
    let group = collaboration_call(
        f,
        &lead,
        "group",
        CollaborationTool::CreateGroup {
            title: "Corrected delivery".into(),
            requires_summary: true,
        },
    )["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let task = assigned(f, &lead, "original", Some(&m), Some(&group), vec![]);
    finish(f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    (group, task, m, original)
}

fn correction_barrier_case(outcome: AppendOutcome) {
    let mut f = Fixture::new();
    let (group, task, m, original) = correction_group(&mut f);
    f.service.executor.append_outcome = Some(outcome);
    f.service
        .submit_targeted(
            "correction",
            "Use the corrected result",
            Some(&m),
            Some(&task),
            false,
            4,
        )
        .unwrap();
    f.service.tick(5).unwrap();
    assert_eq!(f.service.deliveries()[0].status, "queued");
    let mut f = reopen_fixture(f);
    finish(&mut f, &original, 6);
    f.service.tick(7).unwrap();
    assert!(
        !f.service
            .events(0, 256)
            .unwrap()
            .iter()
            .any(|e| e.kind == "group_ready"),
        "queued in-flight correction must hold synthesis barrier"
    );
    assert_eq!(f.service.groups().unwrap()[0]["status"], "running");
    let correction = f.op(Role::Execution);
    assert_eq!(
        f.service
            .store
            .task(correction.task_id.as_ref().unwrap())
            .unwrap()
            .assignment
            .unwrap()
            .group_id
            .as_deref(),
        Some(group.as_str())
    );
    finish(&mut f, &correction, 8);
    f.service.tick(9).unwrap();
    let events = f.service.events(0, 256).unwrap();
    let ready: Vec<_> = events.iter().filter(|e| e.kind == "group_ready").collect();
    assert_eq!(ready.len(), 1);
    assert!(
        ready[0]
            .detail
            .contains(correction.task_id.as_ref().unwrap())
    );
    f.service.tick(10).unwrap();
    assert_eq!(
        f.service
            .events(0, 256)
            .unwrap()
            .iter()
            .filter(|e| e.kind == "group_ready")
            .count(),
        1
    );
}

#[test]
fn backend_review_unsupported_correction_holds_group_barrier() {
    correction_barrier_case(AppendOutcome::Unsupported);
}

#[test]
fn backend_review_rejected_correction_holds_group_barrier() {
    correction_barrier_case(AppendOutcome::Rejected);
}

#[test]
fn backend_review_settled_followup_stays_outside_original_group() {
    let mut f = Fixture::new();
    let (_group, task, m, original) = correction_group(&mut f);
    finish(&mut f, &original, 4);
    f.service.tick(5).unwrap();
    let synthesis = f.op(Role::Coordinator);
    finish(&mut f, &synthesis, 6);
    f.service
        .submit_targeted("later", "New follow-up", Some(&m), Some(&task), false, 7)
        .unwrap();
    f.service.tick(8).unwrap();
    let later = f.op(Role::Execution);
    assert!(
        f.service
            .store
            .task(later.task_id.as_ref().unwrap())
            .unwrap()
            .assignment
            .unwrap()
            .group_id
            .is_none()
    );
    finish(&mut f, &later, 9);
    f.service.tick(10).unwrap();
    assert_eq!(
        f.service
            .events(0, 256)
            .unwrap()
            .iter()
            .filter(|e| e.kind == "group_ready")
            .count(),
        1
    );
    assert!(
        f.service
            .conversation_page(None, 20)
            .unwrap()
            .to_string()
            .contains("New follow-up")
    );
}

fn delivery_order_case(legacy: bool, history: bool) {
    let mut f = Fixture::new();
    let lead = f.submit();
    let m = member(&mut f, &lead, "Member");
    let task = assigned(&mut f, &lead, "original", Some(&m), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted("z-first", "use A", Some(&m), Some(&task), false, 5)
        .unwrap();
    f.service
        .submit_targeted(
            "a-second",
            "correction: use B",
            Some(&m),
            Some(&task),
            false,
            if history { 5 } else { 6 },
        )
        .unwrap();
    if legacy {
        let saved: String = f
            .service
            .store
            .db
            .query_row("SELECT data FROM assistant_service_state", [], |r| r.get(0))
            .unwrap();
        let mut saved: serde_json::Value = serde_json::from_str(&saved).unwrap();
        saved["collaboration"]
            .as_object_mut()
            .unwrap()
            .remove("last_delivery_sequence");
        for delivery in saved["collaboration"]["deliveries"]
            .as_object_mut()
            .unwrap()
            .values_mut()
        {
            delivery.as_object_mut().unwrap().remove("sequence");
        }
        if !history {
            saved["collaboration"]["conversation"] = serde_json::json!([]);
        }
        f.service
            .store
            .db
            .execute(
                "UPDATE assistant_service_state SET data=?1",
                [serde_json::to_string(&saved).unwrap()],
            )
            .unwrap();
    }
    let mut f = reopen_fixture(f);
    assert_eq!(
        f.service
            .deliveries()
            .iter()
            .map(|d| d.id.as_str())
            .collect::<Vec<_>>(),
        ["z-first", "a-second"],
        "delivery IDs must not define submission order"
    );
    let serialized = serde_json::to_value(f.service.deliveries()).unwrap();
    assert!(serialized[0]["sequence"].as_u64().unwrap_or(0) > 0);
    assert!(
        serialized[1]["sequence"].as_u64().unwrap_or(0)
            > serialized[0]["sequence"].as_u64().unwrap_or(0)
    );
    f.service
        .submit_targeted(
            "a-second",
            "correction: use B",
            Some(&m),
            Some(&task),
            false,
            7,
        )
        .unwrap();
    finish(&mut f, &original, 8);
    f.service.tick(9).unwrap();
    let first = f.op(Role::Execution);
    assert!(
        f.service
            .store
            .task(first.task_id.as_ref().unwrap())
            .unwrap()
            .prompt
            .starts_with("use A")
    );
    finish(&mut f, &first, 10);
    f.service.tick(11).unwrap();
    let last = f.op(Role::Execution);
    assert!(
        f.service
            .store
            .task(last.task_id.as_ref().unwrap())
            .unwrap()
            .prompt
            .starts_with("correction: use B")
    );
    finish(&mut f, &last, 12);
    f.service
        .submit_targeted("0-third", "use C", Some(&m), Some(&task), false, 4)
        .unwrap();
    let next = serde_json::to_value(f.service.deliveries()).unwrap();
    assert_eq!(next[2]["id"], "0-third");
    assert!(next[2]["sequence"].as_u64().unwrap() > next[1]["sequence"].as_u64().unwrap());
    let f = reopen_fixture(f);
    assert_eq!(serde_json::to_value(f.service.deliveries()).unwrap(), next);
}

#[test]
fn backend_review_delivery_order_survives_restart_and_replay() {
    delivery_order_case(false, true);
}

#[test]
fn backend_review_legacy_delivery_order_uses_conversation_for_timestamp_ties() {
    delivery_order_case(true, true);
}

#[test]
fn backend_review_legacy_delivery_order_uses_timestamp_without_history() {
    delivery_order_case(true, false);
}

fn fallback_append_order_case(restart: bool) {
    let mut f = Fixture::new();
    let lead = f.submit();
    let m = member(&mut f, &lead, "Member");
    let task = assigned(&mut f, &lead, "original", Some(&m), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted("d1", "use A", Some(&m), Some(&task), false, 4)
        .unwrap();
    f.service
        .submit_targeted("d2", "use B", Some(&m), Some(&task), false, 5)
        .unwrap();
    f.service.tick(6).unwrap();
    finish(&mut f, &original, 7);
    f.service.tick(8).unwrap();
    let first = f.op(Role::Execution);
    let second_id = f.service.deliveries()[1].delivered_task_id.clone().unwrap();
    assert!(f.service.deliveries().iter().all(|d| d.status == "sent"));
    assert_eq!(
        f.service.store.task(&second_id).unwrap().status,
        TaskStatus::Queued
    );
    if restart {
        f = reopen_fixture(f);
    }
    f.service.executor.append_outcome = Some(AppendOutcome::Delivered);
    f.service.executor.appends.clear();
    f.service
        .submit_targeted(
            "d3",
            "correction: use C",
            Some(&m),
            first.task_id.as_deref(),
            false,
            9,
        )
        .unwrap();
    f.service.tick(10).unwrap();
    assert!(
        f.service.executor.appends.is_empty(),
        "C must not append to F1 ahead of the already sent but queued B fallback"
    );
    assert_eq!(f.service.deliveries()[2].status, "queued");
    finish(&mut f, &first, 11);
    f.service.tick(12).unwrap();
    let second = f.op(Role::Execution);
    assert_eq!(second.task_id.as_ref(), Some(&second_id));
    assert_eq!(
        f.service
            .operations()
            .iter()
            .filter(|o| o.role == Role::Execution && o.phase.live())
            .count(),
        1
    );
    finish(&mut f, &second, 13);
    f.service.tick(14).unwrap();
    let third = f.op(Role::Execution);
    assert!(
        f.service
            .store
            .task(third.task_id.as_ref().unwrap())
            .unwrap()
            .prompt
            .starts_with("correction: use C")
    );
    assert!(f.service.executor.appends.is_empty());
}

#[test]
fn backend_rereview_sent_fallback_blocks_later_successful_append() {
    fallback_append_order_case(false);
}

#[test]
fn backend_rereview_sent_fallback_blocks_later_successful_append_after_restart() {
    fallback_append_order_case(true);
}

fn anonymous_fallback_order_case(restart: bool) {
    let mut f = Fixture::new();
    let task = f.task("original", false);
    f.service.tick(1).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted("d1", "use A", None, Some(&task.id), false, 2)
        .unwrap();
    f.service
        .submit_targeted("d2", "use B", None, Some(&task.id), false, 3)
        .unwrap();
    finish(&mut f, &original, 4);
    // Persist both handoffs before any claim, as a legacy snapshot may already have done.
    f.service.advance_collaboration(5).unwrap();
    assert!(f.service.deliveries().iter().all(|d| d.status == "sent"));
    let deliveries = f.service.deliveries();
    if restart {
        f = reopen_fixture(f);
    }
    f.service.tick(6).unwrap();
    assert_eq!(
        f.service
            .operations()
            .iter()
            .filter(|o| o.role == Role::Execution && o.phase.live())
            .count(),
        1,
        "anonymous siblings must execute serially despite being read-only"
    );
    let first = f.op(Role::Execution);
    assert_eq!(first.task_id, deliveries[0].delivered_task_id);
    let queued = f
        .service
        .store
        .task(deliveries[1].delivered_task_id.as_ref().unwrap())
        .unwrap();
    assert_eq!(queued.status, TaskStatus::Queued);
    assert!(queued.active_run_id.is_none());
    // A new instruction targeting F1 is in the same anonymous continuation queue.
    f.service.executor.append_outcome = Some(AppendOutcome::Delivered);
    f.service.executor.appends.clear();
    f.service
        .submit_targeted("d3", "use C", None, first.task_id.as_deref(), false, 7)
        .unwrap();
    f.service.tick(8).unwrap();
    assert!(f.service.executor.appends.is_empty());
    finish(&mut f, &first, 9);
    f.service.tick(10).unwrap();
    let second = f.op(Role::Execution);
    assert_eq!(second.task_id, deliveries[1].delivered_task_id);
    assert_eq!(
        f.service
            .operations()
            .iter()
            .filter(|o| o.role == Role::Execution && o.phase.live())
            .count(),
        1
    );
    finish(&mut f, &second, 11);
    f.service.tick(12).unwrap();
    let third = f.op(Role::Execution);
    assert!(
        f.service
            .store
            .task(third.task_id.as_ref().unwrap())
            .unwrap()
            .prompt
            .starts_with("use C")
    );
}

#[test]
fn backend_rereview_anonymous_fallbacks_execute_in_order() {
    anonymous_fallback_order_case(false);
}

#[test]
fn backend_rereview_anonymous_fallbacks_execute_in_order_after_restart() {
    anonymous_fallback_order_case(true);
}

#[test]
fn backend_rereview_consecutive_native_appends_do_not_wait_for_turn_completion() {
    let mut f = Fixture::new();
    let task = f.task("original", false);
    f.service.tick(1).unwrap();
    let original = f.op(Role::Execution);
    f.service.executor.append_outcome = Some(AppendOutcome::Delivered);
    f.service
        .submit_targeted("z-first", "use A", None, Some(&task.id), false, 2)
        .unwrap();
    f.service.tick(3).unwrap();
    let mut f = reopen_fixture(f);
    f.service
        .submit_targeted("a-second", "use B", None, Some(&task.id), false, 4)
        .unwrap();
    f.service.tick(5).unwrap();
    assert_eq!(
        f.service
            .executor
            .appends
            .iter()
            .map(|(id, _, _)| id.as_str())
            .collect::<Vec<_>>(),
        ["z-first", "a-second"]
    );
    assert!(
        f.service
            .deliveries()
            .iter()
            .all(|d| d.status == "sent" && d.delivered_task_id.as_ref() == Some(&task.id))
    );
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    assert_eq!(f.op(Role::Execution).id, original.id);
}

fn legacy_snapshot(f: Fixture, edit: impl FnOnce(&mut serde_json::Value)) -> Fixture {
    let saved: String = f
        .service
        .store
        .db
        .query_row("SELECT data FROM assistant_service_state", [], |r| r.get(0))
        .unwrap();
    let mut saved: serde_json::Value = serde_json::from_str(&saved).unwrap();
    saved["collaboration"]
        .as_object_mut()
        .unwrap()
        .remove("last_delivery_sequence");
    for delivery in saved["collaboration"]["deliveries"]
        .as_object_mut()
        .unwrap()
        .values_mut()
    {
        delivery.as_object_mut().unwrap().remove("sequence");
    }
    edit(&mut saved);
    f.service
        .store
        .db
        .execute(
            "UPDATE assistant_service_state SET data=?1",
            [serde_json::to_string(&saved).unwrap()],
        )
        .unwrap();
    reopen_fixture(f)
}

fn assert_legacy_two_delivery_execution(mut f: Fixture, original: &Operation) {
    assert_eq!(
        f.service
            .deliveries()
            .iter()
            .map(|d| d.id.as_str())
            .collect::<Vec<_>>(),
        ["a-first", "z-second"],
        "partial or unrelated history must not invert distinct recoverable timestamps"
    );
    let migrated = serde_json::to_value(f.service.deliveries()).unwrap();
    f = reopen_fixture(f);
    assert_eq!(
        serde_json::to_value(f.service.deliveries()).unwrap(),
        migrated
    );
    finish(&mut f, original, 8);
    f.service.tick(9).unwrap();
    let first = f.op(Role::Execution);
    assert!(
        f.service
            .store
            .task(first.task_id.as_ref().unwrap())
            .unwrap()
            .prompt
            .starts_with("use A")
    );
    finish(&mut f, &first, 10);
    f.service.tick(11).unwrap();
    let second = f.op(Role::Execution);
    assert!(
        f.service
            .store
            .task(second.task_id.as_ref().unwrap())
            .unwrap()
            .prompt
            .starts_with("use B")
    );
}

#[test]
fn backend_rereview_legacy_partial_history_uses_recoverable_time_order() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let m = member(&mut f, &lead, "Member");
    let task = assigned(&mut f, &lead, "original", Some(&m), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted("a-first", "use A", Some(&m), Some(&task), false, 5)
        .unwrap();
    f.service
        .submit_targeted("z-second", "use B", Some(&m), Some(&task), false, 6)
        .unwrap();
    let f = legacy_snapshot(f, |saved| {
        saved["collaboration"]["conversation"]
            .as_array_mut()
            .unwrap()
            .retain(|entry| entry["id"] != "user:a-first")
    });
    assert_legacy_two_delivery_execution(f, &original);
}

#[test]
fn backend_rereview_legacy_unrelated_main_message_id_is_not_delivery_evidence() {
    let mut f = Fixture::new();
    // Ordinary and targeted submissions intentionally have separate replay journals.
    f.service
        .submit(
            "z-second",
            "Unrelated main message",
            vec![f.root.path().join("work")],
            0,
        )
        .unwrap();
    f.service.tick(1).unwrap();
    let lead = f.op(Role::Coordinator);
    let m = member(&mut f, &lead, "Member");
    let task = assigned(&mut f, &lead, "original", Some(&m), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted("a-first", "use A", Some(&m), Some(&task), false, 5)
        .unwrap();
    f.service
        .submit_targeted("z-second", "use B", Some(&m), Some(&task), false, 6)
        .unwrap();
    assert_eq!(
        f.service.conversation_entry("user:z-second").unwrap().text,
        "Unrelated main message"
    );
    let f = legacy_snapshot(f, |_| {});
    assert_legacy_two_delivery_execution(f, &original);
}

#[test]
fn backend_rereview_legacy_merges_missing_history_with_clock_rollback_evidence() {
    let mut f = Fixture::new();
    let task = f.task("original", false);
    // History proves A preceded C despite rollback; B has only a timestamp.
    f.service
        .submit_targeted("a", "A", None, Some(&task.id), false, 20)
        .unwrap();
    f.service
        .submit_targeted("b", "B", None, Some(&task.id), false, 15)
        .unwrap();
    f.service
        .submit_targeted("c", "C", None, Some(&task.id), false, 10)
        .unwrap();
    let f = legacy_snapshot(f, |saved| {
        saved["collaboration"]["conversation"]
            .as_array_mut()
            .unwrap()
            .retain(|entry| entry["id"] != "user:b")
    });
    assert_eq!(
        f.service
            .deliveries()
            .iter()
            .map(|d| d.id.as_str())
            .collect::<Vec<_>>(),
        ["b", "a", "c"],
        "merge available evidence deterministically without a pairwise cyclic comparator"
    );
    let migrated = serde_json::to_value(f.service.deliveries()).unwrap();
    let f = reopen_fixture(f);
    assert_eq!(
        serde_json::to_value(f.service.deliveries()).unwrap(),
        migrated
    );
}

fn legacy_fallback_causality_case(with_member: bool, depth: usize) {
    let mut f = Fixture::new();
    let lead = f.submit();
    let m = with_member.then(|| member(&mut f, &lead, "Member"));
    let task = assigned(&mut f, &lead, "original", m.as_deref(), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    finish(&mut f, &original, 4);
    let mut target = task;
    let mut expected = Vec::new();
    for index in 0..depth {
        let id = format!("parent-{index}");
        f.service
            .submit_targeted(
                &id,
                &format!("work {index}"),
                m.as_deref(),
                Some(&target),
                false,
                20 - index as i64 * 5,
            )
            .unwrap();
        f.service
            .advance_collaboration(30 + index as i64 * 3)
            .unwrap();
        target = f
            .service
            .deliveries()
            .last()
            .unwrap()
            .delivered_task_id
            .clone()
            .unwrap();
        expected.push(id);
        if index + 1 < depth {
            f.service.tick(31 + index as i64 * 3).unwrap();
            let op = f.op(Role::Execution);
            finish(&mut f, &op, 32 + index as i64 * 3);
        }
    }
    assert_eq!(
        f.service.store.task(&target).unwrap().status,
        TaskStatus::Queued
    );
    f.service
        .submit_targeted(
            "child",
            "final correction",
            m.as_deref(),
            Some(&target),
            false,
            10,
        )
        .unwrap();
    assert!(
        f.service
            .deliveries()
            .last()
            .unwrap()
            .operation_id
            .is_none()
    );
    expected.push("child".into());
    // Parent history was lost; the child's valid entry has a rolled-back clock.
    // The persisted fallback identities still prove parent-before-child causality.
    let f = legacy_snapshot(f, |saved| {
        saved["collaboration"]["conversation"]
            .as_array_mut()
            .unwrap()
            .retain(|entry| !entry["id"].as_str().unwrap().starts_with("user:parent-"));
    });
    let migrated = serde_json::to_value(f.service.deliveries()).unwrap();
    let mut f = reopen_fixture(f);
    assert_eq!(
        serde_json::to_value(f.service.deliveries()).unwrap(),
        migrated
    );
    f.service.tick(40).unwrap();
    assert_eq!(
        f.service.store.task(&target).unwrap().status,
        TaskStatus::Running,
        "migrated correction must not deadlock the queued fallback it needs to execute first"
    );
    assert_eq!(
        f.service
            .deliveries()
            .iter()
            .map(|d| d.id.clone())
            .collect::<Vec<_>>(),
        expected
    );
    let parent = f.op(Role::Execution);
    assert_eq!(parent.task_id.as_ref(), Some(&target));
    finish(&mut f, &parent, 41);
    f.service.tick(42).unwrap();
    let correction = f.op(Role::Execution);
    assert_ne!(correction.task_id, parent.task_id);
    assert!(
        f.service
            .store
            .task(correction.task_id.as_ref().unwrap())
            .unwrap()
            .prompt
            .starts_with("final correction")
    );
    assert_eq!(f.service.deliveries().last().unwrap().status, "sent");
    finish(&mut f, &correction, 43);
    let mut f = reopen_fixture(f);
    let starts = f.service.executor.requests.len();
    f.service.tick(44).unwrap();
    assert_eq!(f.service.executor.requests.len(), starts);
}

#[test]
fn backend_causality_legacy_member_fallback_precedes_its_correction_after_reopen() {
    for depth in [1, 2] {
        legacy_fallback_causality_case(true, depth);
    }
}

#[test]
fn backend_causality_legacy_anonymous_fallback_precedes_its_correction_after_reopen() {
    for depth in [1, 2] {
        legacy_fallback_causality_case(false, depth);
    }
}

#[test]
fn backend_witnessed_corrections_keep_order_when_parent_history_is_missing() {
    for with_member in [true, false] {
        for mixed in [false, true] {
            let mut f = Fixture::new();
            let lead = f.submit();
            let m = with_member.then(|| member(&mut f, &lead, "Member"));
            let task = assigned(&mut f, &lead, "original", m.as_deref(), None, vec![]);
            finish(&mut f, &lead, 2);
            f.service.tick(3).unwrap();
            let original = f.op(Role::Execution);
            finish(&mut f, &original, 4);
            f.service
                .submit_targeted("parent", "work A", m.as_deref(), Some(&task), false, 20)
                .unwrap();
            f.service.advance_collaboration(21).unwrap();
            let fallback = f.service.deliveries()[0].delivered_task_id.clone().unwrap();
            f.service
                .submit_targeted("child", "work B", m.as_deref(), Some(&fallback), false, 10)
                .unwrap();
            f.service
                .submit_targeted("later", "work C", m.as_deref(), Some(&task), false, 11)
                .unwrap();
            let f = legacy_snapshot(f, |saved| {
                saved["collaboration"]["conversation"]
                    .as_array_mut()
                    .unwrap()
                    .retain(|entry| entry["id"] != "user:parent");
                if mixed {
                    saved["collaboration"]["deliveries"]["parent"]["sequence"] =
                        serde_json::json!(1);
                    saved["collaboration"]["deliveries"]["later"]["sequence"] =
                        serde_json::json!(3);
                    saved["collaboration"]["last_delivery_sequence"] = serde_json::json!(3);
                }
            });
            let mut f = reopen_fixture(f);
            assert_eq!(
                f.service
                    .deliveries()
                    .iter()
                    .map(|d| d.id.as_str())
                    .collect::<Vec<_>>(),
                vec!["parent", "child", "later"],
                "causal and witnessed order must both survive migration"
            );
            for (index, prompt) in ["work A", "work B", "work C"].iter().enumerate() {
                let now = 30 + index as i64 * 2;
                f.service.tick(now).unwrap();
                let operation = f.op(Role::Execution);
                assert!(
                    f.service
                        .store
                        .task(operation.task_id.as_ref().unwrap())
                        .unwrap()
                        .prompt
                        .starts_with(prompt)
                );
                finish(&mut f, &operation, now + 1);
            }
            let mut f = reopen_fixture(f);
            let starts = f.service.executor.requests.len();
            f.service.tick(40).unwrap();
            assert_eq!(f.service.executor.requests.len(), starts);
        }
    }
}

#[test]
fn collaboration_member_creation_is_durable_and_tool_replay_is_idempotent() {
    let mut f = Fixture::new();
    let coordinator = f.submit();
    let call: ToolCall = serde_json::from_value(serde_json::json!({"Collaboration": {
        "action":"create_member", "name":"Backend", "duty":"Own backend maintenance",
        "directory":f.root.path().join("work")
    }}))
    .unwrap();
    let scope = coordinator.mapping.unwrap();
    let member = f
        .service
        .tool(scope.clone(), "member", call.clone(), 1)
        .unwrap();
    assert_eq!(f.service.tool(scope, "member", call, 1).unwrap(), member);
    let saved: String = f
        .service
        .store
        .db
        .query_row("SELECT data FROM assistant_service_state", [], |r| r.get(0))
        .unwrap();
    let saved: serde_json::Value = serde_json::from_str(&saved).unwrap();
    assert_eq!(
        saved["collaboration"]["members"].as_object().unwrap().len(),
        1
    );
    assert_eq!(member["name"], "Backend");
}

fn collaboration_call(
    f: &mut Fixture,
    coordinator: &Operation,
    id: &str,
    call: CollaborationTool,
) -> serde_json::Value {
    f.service
        .tool(
            coordinator.mapping.clone().unwrap(),
            id,
            ToolCall::Collaboration(call),
            1,
        )
        .unwrap()
}
fn member(f: &mut Fixture, coordinator: &Operation, id: &str) -> String {
    collaboration_call(
        f,
        coordinator,
        id,
        CollaborationTool::CreateMember {
            name: id.into(),
            duty: "Maintain this module".into(),
            directory: f.root.path().join("work"),
        },
    )["id"]
        .as_str()
        .unwrap()
        .into()
}
fn assigned(
    f: &mut Fixture,
    coordinator: &Operation,
    id: &str,
    member: Option<&str>,
    group: Option<&str>,
    dependencies: Vec<String>,
) -> String {
    let result = collaboration_call(
        f,
        coordinator,
        id,
        CollaborationTool::AssignTask {
            prompt: id.into(),
            directory: f.root.path().join("work"),
            writes: false,
            member_id: member.map(str::to_owned),
            group_id: group.map(str::to_owned),
            dependencies,
            completion_criteria: "Tests pass and explain the result".into(),
            parent_task_id: None,
        },
    );
    result["result"]["value"]["id"].as_str().unwrap().into()
}
fn finish(f: &mut Fixture, operation: &Operation, at: i64) {
    f.observe(
        operation,
        ExecutionStatus::Completed(
            operation.mapping.clone().unwrap(),
            "Done with evidence".into(),
        ),
        &format!("done:{}", operation.id),
        at,
    );
}

#[test]
fn collaboration_sealed_group_waits_for_all_results_and_wakes_once() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let group = collaboration_call(
        &mut f,
        &lead,
        "group",
        CollaborationTool::CreateGroup {
            title: "Joint delivery".into(),
            requires_summary: true,
        },
    )["id"]
        .as_str()
        .unwrap()
        .to_owned();
    assigned(&mut f, &lead, "first", None, Some(&group), vec![]);
    assigned(&mut f, &lead, "second", None, Some(&group), vec![]);
    f.service.tick(2).unwrap();
    assert_eq!(
        f.service.executor.requests.len(),
        1,
        "unsealed group must not dispatch"
    );
    collaboration_call(
        &mut f,
        &lead,
        "seal",
        CollaborationTool::SealGroup {
            group_id: group.clone(),
        },
    );
    finish(&mut f, &lead, 3);
    f.service.tick(4).unwrap();
    let executions: Vec<_> = f
        .service
        .operations()
        .into_iter()
        .filter(|o| o.role == Role::Execution)
        .collect();
    assert_eq!(executions.len(), 2);
    finish(&mut f, &executions[1], 5);
    f.service.tick(6).unwrap();
    assert_eq!(f.service.executor.requests.len(), 3);
    finish(&mut f, &executions[0], 7);
    f.service.tick(8).unwrap();
    assert_eq!(f.service.executor.requests.len(), 4);
    for now in 9..15 {
        f.service.tick(now).unwrap();
    }
    assert_eq!(
        f.service
            .events(0, 256)
            .unwrap()
            .iter()
            .filter(|e| e.kind == "group_ready")
            .count(),
        1
    );
    assert_eq!(f.service.executor.requests.len(), 4);
}

#[test]
fn collaboration_member_serializes_tasks_and_reuses_its_native_conversation() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let member = member(&mut f, &lead, "Backend");
    let first = assigned(&mut f, &lead, "first", Some(&member), None, vec![]);
    assigned(&mut f, &lead, "second", Some(&member), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let execution = f.op(Role::Execution);
    assert_eq!(execution.task_id.as_deref(), Some(first.as_str()));
    assert_eq!(f.service.executor.requests.len(), 2);
    finish(&mut f, &execution, 4);
    f.service.tick(5).unwrap();
    let next = f.op(Role::Execution);
    assert_ne!(next.task_id, execution.task_id);
    assert_eq!(
        next.mapping.as_ref().unwrap().thread_id,
        execution.mapping.as_ref().unwrap().thread_id
    );
    assert_eq!(
        f.service.executor.requests.len(),
        3,
        "direct result must not wake the lead"
    );
    assert_eq!(
        f.service.members()[0].thread_id,
        next.mapping.map(|m| m.thread_id)
    );
}

#[test]
fn collaboration_failed_prerequisite_settles_group_without_running_dependent_task() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let group = collaboration_call(
        &mut f,
        &lead,
        "group",
        CollaborationTool::CreateGroup {
            title: "Dependent work".into(),
            requires_summary: true,
        },
    )["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let first = assigned(&mut f, &lead, "first", None, Some(&group), vec![]);
    let second = assigned(&mut f, &lead, "second", None, Some(&group), vec![first]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let execution = f.op(Role::Execution);
    f.observe(
        &execution,
        ExecutionStatus::Failed(execution.mapping.clone().unwrap(), "Build failed".into()),
        "failure",
        4,
    );
    f.service.tick(5).unwrap();
    assert_eq!(
        f.service.store.task(&second).unwrap().status,
        TaskStatus::Failed
    );
    assert_eq!(
        f.service
            .operations()
            .iter()
            .filter(|o| o.role == Role::Execution)
            .count(),
        1
    );
    assert_eq!(
        f.service
            .events(0, 256)
            .unwrap()
            .iter()
            .filter(|e| e.kind == "group_ready")
            .count(),
        1
    );
}

#[test]
fn collaboration_targeted_followup_survives_restart_and_sends_after_original_stops() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let member = member(&mut f, &lead, "Backend");
    let task = assigned(&mut f, &lead, "first", Some(&member), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted(
            "follow-up",
            "Also cover retries",
            Some(&member),
            Some(&task),
            false,
            4,
        )
        .unwrap();
    f.service.tick(5).unwrap();
    assert_eq!(f.service.deliveries()[0].status, "queued");
    let Fixture { root, service } = f;
    let native = service.executor.clone();
    drop(service);
    let service = AssistantService::open(
        &root.path().join("service.db"),
        "pc",
        "profile",
        "host-1",
        native,
    )
    .unwrap();
    let mut f = Fixture { root, service };
    f.service.tick(6).unwrap();
    assert_eq!(f.service.deliveries()[0].status, "queued");
    finish(&mut f, &original, 7);
    f.service.tick(8).unwrap();
    assert_eq!(f.service.deliveries()[0].status, "sent");
    assert_eq!(f.service.tasks().unwrap().len(), 2);
    f.service
        .submit_targeted(
            "follow-up",
            "Also cover retries",
            Some(&member),
            Some(&task),
            false,
            9,
        )
        .unwrap();
    f.service.tick(10).unwrap();
    assert_eq!(f.service.tasks().unwrap().len(), 2);
    assert!(
        f.service
            .executor
            .requests
            .last()
            .unwrap()
            .prompt
            .contains("Also cover retries")
    );
}

#[test]
fn collaboration_reassign_waits_for_confirmed_stop_and_preserves_original_history() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let a = member(&mut f, &lead, "Backend");
    let b = member(&mut f, &lead, "Review");
    let task = assigned(&mut f, &lead, "first", Some(&a), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted(
            "reassign",
            "Reviewer should take over",
            Some(&b),
            Some(&task),
            true,
            4,
        )
        .unwrap();
    f.service.tick(5).unwrap();
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    assert_eq!(f.service.deliveries()[0].status, "queued");
    f.observe(
        &original,
        ExecutionStatus::Interrupted(original.mapping.clone().unwrap()),
        "stopped",
        6,
    );
    f.service.tick(7).unwrap();
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    let next = f.op(Role::Execution);
    assert_eq!(next.task_id, original.task_id);
    assert_ne!(next.id, original.id);
    assert_eq!(
        f.service.state.collaboration.operation_assignments[&original.id].member_id,
        Some(a)
    );
    assert_ne!(
        next.mapping.as_ref().unwrap().thread_id,
        original.mapping.as_ref().unwrap().thread_id
    );
    assert!(f.service.operations().iter().any(|op| op.id == original.id));
    assert_eq!(
        f.service
            .store
            .task(next.task_id.as_ref().unwrap())
            .unwrap()
            .assignment
            .unwrap()
            .member_id,
        Some(b)
    );
}

#[test]
fn collaboration_takeover_does_not_silently_resend_queued_followups_on_release() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let m = member(&mut f, &lead, "Backend");
    let task = assigned(&mut f, &lead, "first", Some(&m), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted("follow", "Extra work", Some(&m), Some(&task), false, 4)
        .unwrap();
    f.service.pause("pause", 5).unwrap();
    f.observe(
        &original,
        ExecutionStatus::Interrupted(original.mapping.clone().unwrap()),
        "stopped",
        6,
    );
    f.service.release_takeover("release", 7).unwrap();
    f.service.tick(8).unwrap();
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    assert_eq!(f.service.deliveries()[0].status, "paused");
}

#[test]
fn collaboration_full_task_text_is_preserved_when_background_context_is_large() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let m = member(&mut f, &lead, "Backend");
    let task = assigned(&mut f, &lead, "first", Some(&m), None, vec![]);
    let mut task = f.service.store.task(&task).unwrap();
    task.prompt = format!("{}CRITICAL-END", "文".repeat(18000));
    let prompt = f.service.task_prompt(&task).unwrap();
    assert!(prompt.starts_with(&task.prompt));
    assert!(prompt.len() <= 65536);
    assert!(prompt.contains("Tests pass and explain the result"));
}

#[test]
fn collaboration_reassign_retains_dependency_graph_and_group_barrier() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let a = member(&mut f, &lead, "Original");
    let b = member(&mut f, &lead, "Replacement");
    let group = collaboration_call(
        &mut f,
        &lead,
        "group",
        CollaborationTool::CreateGroup {
            title: "P then T then D".into(),
            requires_summary: true,
        },
    )["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let p = assigned(&mut f, &lead, "P", None, Some(&group), vec![]);
    let t = assigned(&mut f, &lead, "T", Some(&a), Some(&group), vec![p.clone()]);
    let d = assigned(&mut f, &lead, "D", None, Some(&group), vec![t.clone()]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let prerequisite = f.op(Role::Execution);
    assert_eq!(prerequisite.task_id.as_ref(), Some(&p));
    f.service
        .submit_targeted(
            "replace",
            "Use the revised approach",
            Some(&b),
            Some(&t),
            true,
            4,
        )
        .unwrap();
    f.service.tick(5).unwrap();
    assert_eq!(f.service.tasks().unwrap().len(), 3);
    let revised = f.service.store.task(&t).unwrap();
    assert_eq!(revised.status, TaskStatus::Queued);
    let assignment = revised.assignment.unwrap();
    assert_eq!(assignment.member_id, Some(b.clone()));
    assert_eq!(assignment.dependencies, vec![p]);
    assert_eq!(assignment.group_id.as_ref(), Some(&group));
    assert_eq!(
        assignment.completion_criteria,
        "Tests pass and explain the result"
    );
    assert_eq!(f.service.store.task(&d).unwrap().status, TaskStatus::Queued);
    assert!(
        f.service
            .events(0, 256)
            .unwrap()
            .iter()
            .all(|e| e.kind != "group_ready")
    );
    // Replay must not requeue a task whose replacement run has already started.
    finish(&mut f, &prerequisite, 6);
    f.service.tick(7).unwrap();
    let replacement = f.op(Role::Execution);
    assert_eq!(replacement.task_id.as_ref(), Some(&t));
    f.service
        .submit_targeted(
            "replace",
            "Use the revised approach",
            Some(&b),
            Some(&t),
            true,
            8,
        )
        .unwrap();
    f.service.tick(9).unwrap();
    assert_eq!(f.op(Role::Execution).id, replacement.id);
    finish(&mut f, &replacement, 10);
    f.service.tick(11).unwrap();
    let dependent = f.op(Role::Execution);
    assert_eq!(dependent.task_id.as_ref(), Some(&d));
    assert!(
        f.service
            .events(0, 256)
            .unwrap()
            .iter()
            .all(|e| e.kind != "group_ready")
    );
    finish(&mut f, &dependent, 12);
    f.service.tick(13).unwrap();
    f.service.tick(14).unwrap();
    assert_eq!(
        f.service
            .events(0, 256)
            .unwrap()
            .iter()
            .filter(|e| e.kind == "group_ready")
            .count(),
        1
    );
    assert_eq!(f.service.groups().unwrap()[0]["status"], "done");
}

#[test]
fn collaboration_reassign_rejects_terminal_tasks_without_pending_intent() {
    for failed in [false, true] {
        let mut f = Fixture::new();
        let lead = f.submit();
        let m = member(&mut f, &lead, "Member");
        let task = assigned(&mut f, &lead, "task", Some(&m), None, vec![]);
        finish(&mut f, &lead, 2);
        f.service.tick(3).unwrap();
        let op = f.op(Role::Execution);
        if failed {
            f.observe(
                &op,
                ExecutionStatus::Failed(op.mapping.clone().unwrap(), "Failed".into()),
                "failure",
                4,
            );
        } else {
            finish(&mut f, &op, 4);
        }
        let error = f
            .service
            .submit_targeted("replace", "Try again", Some(&m), Some(&task), true, 5)
            .unwrap_err();
        assert!(error.to_string().contains("follow-up"));
        assert!(f.service.deliveries().is_empty());
    }
}

#[test]
fn collaboration_anonymous_followup_lineage_survives_three_turns_and_restart() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let mut task = assigned(&mut f, &lead, "A", None, None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let mut op = f.op(Role::Execution);
    let thread = op.mapping.as_ref().unwrap().thread_id.clone();
    for (n, message) in ["B", "C"].into_iter().enumerate() {
        let at = 4 + n as i64 * 4;
        finish(&mut f, &op, at);
        f.service
            .submit_targeted(message, message, None, Some(&task), false, at + 1)
            .unwrap();
        f.service.tick(at + 2).unwrap();
        op = f.op(Role::Execution);
        task = op.task_id.clone().unwrap();
        assert_eq!(op.mapping.as_ref().unwrap().thread_id, thread);
    }
    let Fixture { root, service } = f;
    let native = service.executor.clone();
    drop(service);
    let mut service = AssistantService::open(
        &root.path().join("service.db"),
        "pc",
        "profile",
        "host-1",
        native,
    )
    .unwrap();
    service.tick(20).unwrap();
    assert_eq!(
        service
            .operations()
            .iter()
            .find(|o| o.id == op.id)
            .unwrap()
            .phase,
        OperationPhase::Running
    );
}

#[test]
fn collaboration_pause_tool_cancels_followup_after_confirmed_stop() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let m = member(&mut f, &lead, "Member");
    let task = assigned(&mut f, &lead, "task", Some(&m), None, vec![]);
    f.service.tick(2).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted("follow", "Extra work", Some(&m), Some(&task), false, 3)
        .unwrap();
    f.service.tick(4).unwrap();
    f.service
        .tool(
            lead.mapping.clone().unwrap(),
            "pause",
            ToolCall::PauseTask { task_id: task },
            5,
        )
        .unwrap();
    f.observe(
        &original,
        ExecutionStatus::Interrupted(original.mapping.clone().unwrap()),
        "stopped",
        6,
    );
    f.service.tick(7).unwrap();
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    assert_eq!(f.service.deliveries()[0].status, "paused");
}

#[test]
fn collaboration_takeover_crash_recovery_cancels_queued_delivery() {
    let mut f = Fixture::new();
    let task = f.task("task", false);
    f.service.tick(1).unwrap();
    let original = f.op(Role::Execution);
    finish(&mut f, &original, 2);
    f.service
        .submit_targeted("follow", "Extra work", None, Some(&task.id), false, 3)
        .unwrap();
    // The store committed takeover but the service did not save its cancellation yet.
    f.service
        .store
        .apply("pause-crash", Command::TakeOver)
        .unwrap();
    let Fixture { root, service } = f;
    let native = service.executor.clone();
    drop(service);
    let mut service = AssistantService::open(
        &root.path().join("service.db"),
        "pc",
        "profile",
        "host-1",
        native,
    )
    .unwrap();
    service.release_takeover("release", 4).unwrap();
    service.tick(5).unwrap();
    assert_eq!(service.tasks().unwrap().len(), 1);
    assert_eq!(service.deliveries()[0].status, "paused");
}

#[test]
fn collaboration_reassignment_store_receipt_replay_keeps_one_task_and_run() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let a = member(&mut f, &lead, "A");
    let b = member(&mut f, &lead, "B");
    let task = assigned(&mut f, &lead, "task", Some(&a), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted("replace", "New approach", Some(&b), Some(&task), true, 4)
        .unwrap();
    f.observe(
        &original,
        ExecutionStatus::Interrupted(original.mapping.clone().unwrap()),
        "stopped",
        5,
    );
    // Simulate a crash after the atomic task change, before delivery acknowledgement.
    let command = Command::ReassignTask {
        task_id: task.clone(),
        member_id: b.clone(),
        message: "New approach".into(),
    };
    let id = format!("reassign-task:{}", fingerprint(&"replace").unwrap());
    let receipt = f.service.store.apply(&id, command.clone()).unwrap();
    let Fixture { root, service } = f;
    let native = service.executor.clone();
    drop(service);
    let service = AssistantService::open(
        &root.path().join("service.db"),
        "pc",
        "profile",
        "host-1",
        native,
    )
    .unwrap();
    let mut f = Fixture { root, service };
    f.service.tick(6).unwrap();
    let replacement = f.op(Role::Execution);
    assert_eq!(replacement.task_id.as_ref(), Some(&task));
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    assert_eq!(f.service.store.apply(&id, command).unwrap(), receipt);
    assert_eq!(
        f.service.store.task(&task).unwrap().active_run_id.as_ref(),
        Some(&replacement.id)
    );
    assert_eq!(
        f.service
            .store
            .task(&task)
            .unwrap()
            .prompt
            .matches("New approach")
            .count(),
        1
    );
    assert_eq!(
        f.service.state.collaboration.operation_assignments[&original.id].member_id,
        Some(a)
    );
    assert_eq!(
        f.service.state.collaboration.operation_assignments[&replacement.id].member_id,
        Some(b)
    );
}

#[test]
fn collaboration_reassignment_completion_race_rejects_instead_of_reopening_task() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let m = member(&mut f, &lead, "Member");
    let task = assigned(&mut f, &lead, "task", Some(&m), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted("replace", "New approach", Some(&m), Some(&task), true, 4)
        .unwrap();
    finish(&mut f, &original, 5);
    f.service.tick(6).unwrap();
    assert_eq!(
        f.service.store.task(&task).unwrap().status,
        TaskStatus::Done
    );
    assert_eq!(f.service.deliveries()[0].status, "rejected");
    assert!(
        f.service.deliveries()[0]
            .detail
            .as_ref()
            .unwrap()
            .contains("follow-up")
    );
    assert_eq!(f.service.executor.requests.len(), 2);
}

#[test]
fn collaboration_anonymous_lineage_rejects_cycles_and_member_boundaries_before_dispatch() {
    for cycle in [true, false] {
        let mut f = Fixture::new();
        let lead = f.submit();
        let member = member(&mut f, &lead, "Member");
        let ancestor = assigned(
            &mut f,
            &lead,
            "ancestor",
            if cycle { None } else { Some(&member) },
            None,
            vec![],
        );
        finish(&mut f, &lead, 2);
        f.service.tick(3).unwrap();
        let op = f.op(Role::Execution);
        finish(&mut f, &op, 4);
        let task = f.task("invalid", false);
        let mut invalid = task.clone();
        invalid.assignment = Some(TaskAssignment {
            continue_from_task: Some(if cycle { task.id.clone() } else { ancestor }),
            ..Default::default()
        });
        f.service
            .store
            .db
            .execute(
                "UPDATE assistant_tasks SET data=?1 WHERE id=?2",
                rusqlite::params![serde_json::to_string(&invalid).unwrap(), task.id],
            )
            .unwrap();
        f.service.tick(5).unwrap();
        assert_eq!(f.service.executor.requests.len(), 2);
        assert_eq!(
            f.service.store.task(&task.id).unwrap().status,
            TaskStatus::Paused
        );
        assert!(
            f.service
                .store
                .task(&task.id)
                .unwrap()
                .active_run_id
                .is_none()
        );
    }
}

#[test]
fn collaboration_explicit_pause_cancels_pending_reassignment() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let m = member(&mut f, &lead, "Member");
    let task = assigned(&mut f, &lead, "task", Some(&m), None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.service
        .submit_targeted("replace", "New approach", Some(&m), Some(&task), true, 4)
        .unwrap();
    f.service
        .apply(
            "human-pause",
            Command::PauseTask {
                task_id: task.clone(),
            },
            5,
        )
        .unwrap();
    f.observe(
        &original,
        ExecutionStatus::Interrupted(original.mapping.clone().unwrap()),
        "stopped",
        6,
    );
    f.service.tick(7).unwrap();
    assert_eq!(f.service.deliveries()[0].status, "paused");
    assert_eq!(f.service.executor.requests.len(), 2);
}

#[test]
fn collaboration_running_continuation_keeps_immutable_ownership_after_ancestor_reassign() {
    let mut f = Fixture::new();
    let lead = f.submit();
    let member = member(&mut f, &lead, "Member");
    let a = assigned(&mut f, &lead, "A", None, None, vec![]);
    finish(&mut f, &lead, 2);
    f.service.tick(3).unwrap();
    let original = f.op(Role::Execution);
    f.observe(
        &original,
        ExecutionStatus::Interrupted(original.mapping.clone().unwrap()),
        "stopped",
        4,
    );
    f.service
        .submit_targeted("B", "Follow up", None, Some(&a), false, 5)
        .unwrap();
    f.service.tick(6).unwrap();
    let continuation = f.op(Role::Execution);
    f.service
        .submit_targeted(
            "replace-A",
            "New ownership",
            Some(&member),
            Some(&a),
            true,
            7,
        )
        .unwrap();
    f.service.tick(8).unwrap();
    f.service.tick(9).unwrap();
    finish(&mut f, &continuation, 10);
    assert_eq!(
        f.service
            .store
            .task(continuation.task_id.as_ref().unwrap())
            .unwrap()
            .status,
        TaskStatus::Done
    );
}

#[test]
fn collaboration_idle_ticks_do_not_rewrite_durable_conversation() {
    let mut f = Fixture::new();
    f.service.remember_entry(ConversationEntry {
        id: "history".into(),
        role: "Assistant".into(),
        text: "Large history".repeat(10000),
        at_ms: 0,
        member_id: None,
        task_id: None,
        group_id: None,
        source_thread_id: None,
    });
    f.service.save().unwrap();
    let observer = rusqlite::Connection::open(f.root.path().join("service.db")).unwrap();
    let version = || {
        observer
            .query_row("PRAGMA data_version", [], |row| row.get::<_, i64>(0))
            .unwrap()
    };
    let before = version();
    let updates = f.service.store.db.total_changes();
    for at in 1..=5 {
        f.service.tick(at).unwrap();
    }
    assert_eq!(
        version(),
        before,
        "Idle ticks should not commit unchanged service state"
    );
    assert_eq!(
        f.service.store.db.total_changes(),
        updates,
        "Even an identical UPDATE must not serialize history on idle ticks"
    );
}

#[derive(Clone, Default)]
struct Fake {
    project_grants: Vec<(PathBuf, bool)>,
    append_outcome: Option<AppendOutcome>,
    appends: Vec<(String, NativeMapping, String)>,
    requests: Vec<StartRequest>,
    statuses: BTreeMap<String, ExecutionStatus>,
    responses: Vec<ResponseRequest>,
    replies: Vec<ToolResponse>,
    interrupts: Vec<NativeMapping>,
    queries: usize,
    lose_start: bool,
    lose_response: bool,
    lose_tool_response: bool,
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
    fn validate_directory(&mut self, directory: &Path, writes: bool) -> Result<()> {
        let actual = canonical_directory(directory)?;
        ensure!(self.project_grants.iter().any(|(root, writable)| actual == *root && (!writes || *writable)), "Fake project grant missing");
        Ok(())
    }
    fn append(
        &mut self,
        id: &str,
        mapping: &NativeMapping,
        message: &str,
    ) -> Result<AppendOutcome> {
        self.appends
            .push((id.into(), mapping.clone(), message.into()));
        Ok(self
            .append_outcome
            .clone()
            .unwrap_or(AppendOutcome::Unsupported))
    }
    fn can_resume(&mut self, _: &str, _: Role, _: Option<&Path>, _: bool) -> Result<bool> {
        Ok(true)
    }
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
        if std::mem::take(&mut self.lose_tool_response) {
            anyhow::bail!("lost dynamic answer receipt");
        }
        Ok(())
    }
}
struct Fixture {
    root: tempfile::TempDir,
    service: AssistantService<Fake>,
}

#[test]
fn rebuild_unknown_branch_does_not_lock_main_or_unrelated_task() {
    let mut f=Fixture::new();
    let _original=f.task("first",false);
    f.service.tick(1).unwrap();
    let op=f.op(Role::Execution);
    f.observe(&op,ExecutionStatus::Unknown,"lost",2);
    let other=f.task("second",false);
    f.service.submit("new-message","New independent objective",vec![f.root.path().join("work")],3).unwrap();
    f.service.tick(4).unwrap();
    assert!(f.service.operations().iter().any(|o|o.role==Role::Coordinator),"Unknown branch blocked main conversation");
    assert!(f.service.operations().iter().any(|o|o.task_id.as_deref()==Some(&other.id)),"Unknown branch blocked unrelated task");
    assert_eq!(f.service.executor.requests.iter().filter(|r|r.operation_id==op.id).count(),1);
    assert_eq!(f.service.state.operations[&op.id].phase,OperationPhase::Unknown);
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

fn coordinator_handoffs(request: &StartRequest) -> serde_json::Value {
    let line = request.prompt.lines().next().unwrap();
    serde_json::from_str(line.strip_prefix("User handoffs: ").unwrap()).unwrap()
}

fn paused_unmapped_handoff(f: &mut Fixture, id: &str, roots: Vec<PathBuf>) -> Operation {
    f.service.submit(id, "Recover this exact original message", roots, 1).unwrap();
    f.service.executor.lose_start = true;
    f.service.tick(2).unwrap();
    let original = f.op(Role::Coordinator);
    assert!(original.mapping.is_none());
    f.observe(&original, ExecutionStatus::NotDispatched, "proven-absent", 3);
    f.service.operations().into_iter().find(|op| op.id == original.id).unwrap()
}

#[test]
fn handoff_recovery_reuses_original_message_once_and_retains_original_operation() {
    let mut f = Fixture::new();
    let root = canonical_directory(&f.root.path().join("work")).unwrap();
    f.service.executor.project_grants.push((root.clone(), false));
    let original = paused_unmapped_handoff(&mut f, "original-handoff", vec![root.clone()]);
    let messages = f.service.conversation_page(None, 100).unwrap();
    f.service.resume_coordinator_handoff("recover-one", &original.id, 4).unwrap();
    f.service.resume_coordinator_handoff("recover-one", &original.id, 5).unwrap();
    assert!(f.service.resume_coordinator_handoff("recover-other-id", &original.id, 5).is_err());
    assert_eq!(f.service.conversation_page(None, 100).unwrap(), messages);
    let mut f = reopen_fixture(f);
    f.service.resume_coordinator_handoff("recover-one", &original.id, 6).unwrap();
    f.service.tick(7).unwrap();
    assert_eq!(f.service.executor.requests.len(), 2);
    let next = f.op(Role::Coordinator);
    assert_ne!(next.id, original.id);
    let retained = f.service.operations().into_iter().find(|op| op.id == original.id).unwrap();
    assert_eq!(retained.phase, OperationPhase::Paused);
    assert!(retained.mapping.is_none());
    let handoffs = coordinator_handoffs(f.service.executor.requests.last().unwrap());
    assert_eq!(handoffs[0]["sourceEntryId"], "user:original-handoff");
    assert_eq!(handoffs[0]["message"], "Recover this exact original message");
    assert_eq!(handoffs[0]["authorizedDirectories"], serde_json::json!([root]));
    f.service.tick(8).unwrap();
    assert_eq!(f.service.executor.requests.len(), 2);
}

#[test]
fn handoff_recovery_checks_selected_roots_without_requiring_historical_grants() {
    let mut f = Fixture::new();
    let selected = canonical_directory(&f.root.path().join("work")).unwrap();
    f.service.executor.project_grants.push((selected.clone(), false));
    let original = paused_unmapped_handoff(&mut f, "selected-handoff", vec![selected]);
    let historical = f.root.path().join("revoked-historical-project");
    std::fs::create_dir(&historical).unwrap();
    f.service.state.operations.get_mut(&original.id).unwrap().authorized_directories.push(canonical_directory(&historical).unwrap());
    f.service.save().unwrap();
    f.service.resume_coordinator_handoff("recover-selected", &original.id, 4).unwrap();
    assert_eq!(f.service.state.coordinator.inbox.len(), 1);
    assert_eq!(f.service.state.coordinator.inbox[0].directories.len(), 1);
}

#[test]
fn handoff_recovery_unknown_or_mapped_or_revoked_never_dispatches() {
    for defect in ["unknown", "mapped", "revoked", "missing-entry", "missing-scope", "takeover"] {
        let mut f = Fixture::new();
        let root = canonical_directory(&f.root.path().join("work")).unwrap();
        f.service.executor.project_grants.push((root.clone(), false));
        let original = paused_unmapped_handoff(&mut f, "guarded-handoff", vec![root]);
        match defect {
            "unknown" => {f.service.executor.statuses.insert(original.id.clone(), ExecutionStatus::Unknown);},
            "mapped" => {f.service.state.operations.get_mut(&original.id).unwrap().mapping = Some(NativeMapping {host_generation:"host-1".into(), thread_id:"known-thread".into(), turn_id:"known-turn".into()});},
            "revoked" => f.service.executor.project_grants.clear(),
            "missing-entry" => {f.service.state.operations.get_mut(&original.id).unwrap().user_message_ids = vec!["user:unavailable".into()];},
            "missing-scope" => {f.service.state.operations.get_mut(&original.id).unwrap().prompt = Some("User handoffs: []\nbody".into());},
            "takeover" => f.service.state.paused = true,
            _ => unreachable!(),
        }
        let before = serde_json::to_value(&f.service.state).unwrap();
        assert!(f.service.resume_coordinator_handoff("blocked-recovery", &original.id, 4).is_err(), "accepted {defect}");
        assert_eq!(serde_json::to_value(&f.service.state).unwrap(), before);
        assert!(f.service.state.coordinator.inbox.is_empty());
        assert_eq!(f.service.executor.requests.len(), 1);
    }
}

#[test]
fn handoff_recovery_save_failure_preserves_original_fence_and_allows_same_intent() {
    let mut f = Fixture::new();
    let original = paused_unmapped_handoff(&mut f, "save-handoff", vec![]);
    let before = serde_json::to_value(&f.service.state).unwrap();
    f.service.store.db.execute_batch("CREATE TRIGGER fail_recovery_save BEFORE UPDATE ON assistant_service_state BEGIN SELECT RAISE(FAIL,'recovery save failed'); END").unwrap();
    assert!(f.service.resume_coordinator_handoff("recover-save", &original.id, 4).is_err());
    assert_eq!(serde_json::to_value(&f.service.state).unwrap(), before);
    f.service.store.db.execute_batch("DROP TRIGGER fail_recovery_save").unwrap();
    let mut f = reopen_fixture(f);
    f.service.resume_coordinator_handoff("recover-save", &original.id, 5).unwrap();
    assert_eq!(f.service.state.coordinator.inbox.len(), 1);
}

#[test]
fn handoff_recovery_does_not_adopt_a_later_unrelated_messages_project() {
    let mut f = Fixture::new();
    let selected = canonical_directory(&f.root.path().join("work")).unwrap();
    f.service.executor.project_grants.push((selected.clone(), false));
    let original = paused_unmapped_handoff(&mut f, "older-handoff", vec![selected.clone()]);
    let other = f.root.path().join("later-project");
    std::fs::create_dir(&other).unwrap();
    let other = canonical_directory(&other).unwrap();
    f.service.submit("later-handoff", "Read a different project", vec![other.clone()], 4).unwrap();
    f.service.tick(5).unwrap();
    let later = f.op(Role::Coordinator);
    finish(&mut f, &later, 6);
    f.service.resume_coordinator_handoff("recover-older", &original.id, 7).unwrap();
    f.service.tick(8).unwrap();
    let recovered = f.op(Role::Coordinator);
    assert_eq!(recovered.authorized_directories, vec![selected]);
    assert!(!recovered.authorized_directories.contains(&other));
}

#[test]
fn phone_project_handoff_exposes_exact_directory_to_native_task_tool() {
    let mut f = Fixture::new();
    let coordinator = f.submit();
    let request = &f.service.executor.requests[0];
    let handoffs = coordinator_handoffs(request);
    let root = canonical_directory(&f.root.path().join("work")).unwrap();
    assert_eq!(handoffs[0]["authorizedDirectories"], serde_json::json!([root]));
    assert_eq!(handoffs[0]["sourceEntryId"], "user:phone-1");
    assert!(!request.writes && request.directory.is_none());
    let directory = PathBuf::from(handoffs[0]["authorizedDirectories"][0].as_str().unwrap());
    let command = ToolCall::CreateTask {
        prompt: "List top-level file names without changes".into(),
        directory,
        writes: false,
    };
    let scope = coordinator.mapping.unwrap();
    let receipt = f.service.tool(scope.clone(), "selected-project-task", command.clone(), 2).unwrap();
    assert_eq!(f.service.tool(scope.clone(), "selected-project-task", command, 3).unwrap(), receipt);
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    assert!(f.service.tool(scope, "unselected-project-task", ToolCall::CreateTask {
        prompt: "Outside selected project".into(),
        directory: f.root.path().into(),
        writes: false,
    }, 4).is_err());
    assert_eq!(f.service.tasks().unwrap().len(), 1);
}

#[test]
fn phone_project_handoff_preserves_per_message_selection_across_restart() {
    let mut f = Fixture::new();
    let one = f.root.path().join("work");
    let two = f.root.path().join("second project");
    std::fs::create_dir(&two).unwrap();
    let fake_path = f.root.path().join("not selected");
    f.service.submit("selected-a", "Inspect project A", vec![one.clone()], 1).unwrap();
    f.service.submit("selected-b", "Inspect project B", vec![two.clone()], 2).unwrap();
    f.service.submit("unselected", &format!("A path mentioned in text: {}", fake_path.display()), vec![], 3).unwrap();
    let mut f = reopen_fixture(f);
    f.service.tick(4).unwrap();
    let handoffs = coordinator_handoffs(f.service.executor.requests.last().unwrap());
    assert_eq!(handoffs.as_array().unwrap().len(), 3);
    assert_eq!(handoffs[0]["authorizedDirectories"], serde_json::json!([canonical_directory(&one).unwrap()]));
    assert_eq!(handoffs[1]["authorizedDirectories"], serde_json::json!([canonical_directory(&two).unwrap()]));
    assert_eq!(handoffs[2]["authorizedDirectories"], serde_json::json!([]));
    let lead = f.op(Role::Coordinator);
    assert!(!lead.authorized_directories.contains(&fake_path));
    finish(&mut f, &lead, 5);
    f.service.submit("followup-without-selection", "Summarize progress", vec![], 6).unwrap();
    f.service.tick(7).unwrap();
    let followup = coordinator_handoffs(f.service.executor.requests.last().unwrap());
    assert_eq!(followup[0]["authorizedDirectories"], serde_json::json!([]), "Historical scope must not be presented as this message's selected project");
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

fn held_input_lifetime_fixture() -> (Fixture, Operation, String) {
    let mut f = Fixture::new();
    let op = f.submit();
    let question = format!("C:/完整路径/保留问题.txt\n{}", "选择甲或乙。".repeat(3000));
    f.service
        .tool(
            op.mapping.clone().unwrap(),
            "lifetime-call",
            ToolCall::RequestInput {
                request_id: "lifetime-question".into(),
                text: question.clone(),
            },
            1,
        )
        .unwrap();
    (f, op, question)
}

fn held_input_lifetime_json(service: &AssistantService<Fake>, id: &str) -> serde_json::Value {
    serde_json::to_value(
        service
            .operations()
            .into_iter()
            .find(|op| op.id == id)
            .unwrap(),
    )
    .unwrap()
}

#[test]
fn held_input_lifetime_terminal_retains_complete_identity_and_never_reopens_answer() {
    for terminal in ["completed", "failed", "interrupted"] {
        let (mut f, op, question) = held_input_lifetime_fixture();
        let mapping = op.mapping.clone().unwrap();
        let status = match terminal {
            "completed" => {
                ExecutionStatus::Completed(mapping.clone(), "Waiting for your choice".into())
            }
            "failed" => ExecutionStatus::Failed(mapping.clone(), "Native failure".into()),
            _ => ExecutionStatus::Interrupted(mapping.clone()),
        };
        f.observe(&op, status.clone(), "native-terminal", 2);
        let snapshot = held_input_lifetime_json(&f.service, &op.id);
        assert_eq!(
            snapshot["phase"],
            match terminal {
                "completed" => "Completed",
                "failed" => "Failed",
                _ => "Paused",
            }
        );
        assert!(snapshot["question"].is_null());
        let held = &snapshot["held_inputs"][0];
        assert!(
            held["question"]["text"].as_str() == Some(question.as_str()),
            "Terminal observation must retain the complete original question"
        );
        assert_eq!(held["question"]["request_id"], "lifetime-question");
        assert_eq!(held["operation_id"], op.id);
        assert_eq!(held["mapping"], serde_json::to_value(&mapping).unwrap());
        assert_eq!(held["call_id"], "lifetime-call");
        assert_eq!(held["native_terminal"], terminal);
        assert_eq!(held["resolution"], "unresolved");
        assert_eq!(held["answer_state"], "not_submitted");
        let db = f.root.path().join("service.db");
        let fake = f.service.executor.clone();
        drop(f.service);
        let mut reopened = AssistantService::open(&db, "pc", "profile", "host-1", fake).unwrap();
        reopened.tick(3).unwrap();
        assert_eq!(held_input_lifetime_json(&reopened, &op.id), snapshot);
        assert!(
            reopened
                .respond(
                    "late-answer",
                    &op.id,
                    "lifetime-question",
                    HumanResponse::Answer("甲".into()),
                    4
                )
                .is_err()
        );
        assert!(
            reopened
                .tool(
                    mapping.clone(),
                    "lifetime-call",
                    ToolCall::RequestInput {
                        request_id: "lifetime-question".into(),
                        text: question
                    },
                    4
                )
                .is_err()
        );
        assert!(
            reopened
                .observe(
                    &op.id,
                    ExecutionStatus::Running(mapping),
                    "false-revival",
                    4
                )
                .is_err()
        );
        assert!(reopened.executor.replies.is_empty());
        assert!(reopened.executor.responses.is_empty());
        assert!(reopened.executor.interrupts.is_empty());
        assert_eq!(reopened.executor.requests.len(), 1);
    }
}

#[test]
fn held_input_lifetime_authoritative_resolution_keeps_terminal_history() {
    let (mut f, op, question) = held_input_lifetime_fixture();
    let mapping = op.mapping.clone().unwrap();
    f.observe(
        &op,
        ExecutionStatus::Completed(mapping.clone(), "Waiting".into()),
        "terminal",
        2,
    );
    f.service
        .observe_tool_resolved(&mapping, "lifetime-call", 3)
        .unwrap();
    let snapshot = held_input_lifetime_json(&f.service, &op.id);
    assert!(snapshot["held_inputs"][0]["question"]["text"].as_str() == Some(question.as_str()));
    assert_eq!(snapshot["held_inputs"][0]["resolution"], "resolved");
    assert_eq!(snapshot["held_inputs"][0]["answer_state"], "not_submitted");
    assert_eq!(snapshot["held_inputs"][0]["native_terminal"], "completed");
    assert_eq!(snapshot["phase"], "Completed");
    assert!(snapshot["question"].is_null());
    assert!(snapshot["input_tool_call"].is_null());
    let events = f.service.events(0, 256).unwrap().len();
    f.service
        .observe_tool_resolved(&mapping, "lifetime-call", 4)
        .unwrap();
    assert_eq!(f.service.events(0, 256).unwrap().len(), events);
    let db = f.root.path().join("service.db");
    let fake = f.service.executor.clone();
    drop(f.service);
    let mut reopened = AssistantService::open(&db, "pc", "profile", "host-1", fake).unwrap();
    reopened.tick(5).unwrap();
    assert_eq!(held_input_lifetime_json(&reopened, &op.id), snapshot);
    assert_eq!(reopened.executor.requests.len(), 1);
    assert!(reopened.executor.replies.is_empty());
}

#[test]
fn held_input_lifetime_unknown_and_wrong_scope_never_retire_or_relabel_terminal() {
    let (mut f, op, question) = held_input_lifetime_fixture();
    f.observe(&op, ExecutionStatus::Unknown, "offline", 2);
    let before = held_input_lifetime_json(&f.service, &op.id);
    assert!(before["held_inputs"][0]["question"]["text"].as_str() == Some(question.as_str()));
    assert_eq!(before["held_inputs"][0]["resolution"], "unresolved");
    assert!(before["held_inputs"][0]["native_terminal"].is_null());
    assert!(
        f.service
            .respond(
                "offline-answer",
                &op.id,
                "lifetime-question",
                HumanResponse::Answer("甲".into()),
                3
            )
            .is_err()
    );
    let mapping = op.mapping.clone().unwrap();
    let mut wrong = mapping.clone();
    wrong.host_generation = "host-2".into();
    assert!(
        f.service
            .observe_tool_resolved(&wrong, "lifetime-call", 3)
            .is_err()
    );
    wrong = mapping.clone();
    wrong.turn_id = "other-turn".into();
    f.service
        .observe_tool_resolved(&wrong, "lifetime-call", 3)
        .unwrap();
    f.service
        .observe_tool_resolved(&mapping, "other-call", 3)
        .unwrap();
    assert_eq!(held_input_lifetime_json(&f.service, &op.id), before);
    f.observe(
        &op,
        ExecutionStatus::Failed(mapping, "failure".into()),
        "failure",
        4,
    );
    let terminal = held_input_lifetime_json(&f.service, &op.id);
    f.observe(&op, ExecutionStatus::Unknown, "late-disconnect", 5);
    assert_eq!(held_input_lifetime_json(&f.service, &op.id), terminal);
    assert_eq!(terminal["held_inputs"][0]["native_terminal"], "failed");
    assert!(f.service.executor.replies.is_empty());
}

#[test]
fn held_input_lifetime_same_generation_rejection_receipt_is_not_question_resolution() {
    let (mut f, op, _) = held_input_lifetime_fixture();
    let mapping = op.mapping.clone().unwrap();
    f.observe(
        &op,
        ExecutionStatus::Completed(mapping.clone(), "Waiting".into()),
        "terminal",
        2,
    );
    assert!(
        !f.service
            .tool_rejection_receipt("explicit-reject", &op.id, "n:4", Some(false))
            .unwrap()
    );
    assert!(
        !f.service
            .tool_rejection_receipt("explicit-reject", &op.id, "n:4", None)
            .unwrap()
    );
    assert_eq!(
        held_input_lifetime_json(&f.service, &op.id)["held_inputs"][0]["resolution"],
        "unresolved"
    );
    f.service
        .tool_rejection_receipt("explicit-reject", &op.id, "n:4", Some(true))
        .unwrap();
    assert!(
        f.service
            .tool_rejection_receipt("explicit-reject", &op.id, "n:4", None)
            .unwrap()
    );
    assert!(
        f.service
            .tool_rejection_receipt("explicit-reject", &op.id, "n:5", Some(true))
            .is_err()
    );
    assert_eq!(
        held_input_lifetime_json(&f.service, &op.id)["held_inputs"][0]["resolution"],
        "unresolved"
    );
    f.service
        .observe_tool_resolved(&mapping, "lifetime-call", 3)
        .unwrap();
    let current = held_input_lifetime_json(&f.service, &op.id);
    assert_eq!(current["held_inputs"][0]["resolution"], "resolved");
    assert_eq!(current["held_inputs"][0]["answer_state"], "not_submitted");
    assert_eq!(current["phase"], "Completed");
    assert!(f.service.executor.replies.is_empty());
}

#[test]
fn held_input_lifetime_unknown_answer_stays_unknown_after_terminal_resolution_and_restart() {
    let (mut f, op, _) = held_input_lifetime_fixture();
    let mapping = op.mapping.clone().unwrap();
    f.service.executor.lose_tool_response = true;
    assert!(
        f.service
            .respond(
                "uncertain-human",
                &op.id,
                "lifetime-question",
                HumanResponse::Answer("甲".into()),
                2
            )
            .is_err()
    );
    assert_eq!(f.service.executor.replies.len(), 1);
    f.observe(
        &op,
        ExecutionStatus::Completed(mapping.clone(), "Waiting".into()),
        "terminal",
        3,
    );
    assert_eq!(
        held_input_lifetime_json(&f.service, &op.id)["held_inputs"][0]["answer_state"],
        "unknown"
    );
    f.service
        .observe_tool_resolved(&mapping, "lifetime-call", 4)
        .unwrap();
    let snapshot = held_input_lifetime_json(&f.service, &op.id);
    assert_eq!(snapshot["held_inputs"][0]["resolution"], "resolved");
    assert_eq!(snapshot["held_inputs"][0]["answer_state"], "unknown");
    let db = f.root.path().join("service.db");
    let fake = f.service.executor.clone();
    drop(f.service);
    let mut reopened = AssistantService::open(&db, "pc", "profile", "host-1", fake).unwrap();
    reopened.tick(5).unwrap();
    assert_eq!(held_input_lifetime_json(&reopened, &op.id), snapshot);
    assert!(
        reopened
            .respond(
                "uncertain-human",
                &op.id,
                "lifetime-question",
                HumanResponse::Answer("甲".into()),
                6
            )
            .is_err()
    );
    assert!(
        reopened
            .respond(
                "different-command",
                &op.id,
                "lifetime-question",
                HumanResponse::Answer("甲".into()),
                6
            )
            .is_err()
    );
    assert_eq!(reopened.executor.replies.len(), 1);
    assert_eq!(reopened.executor.requests.len(), 1);
}

#[test]
fn held_input_lifetime_previous_questions_are_not_overwritten_or_evicted() {
    let (mut f, op, question) = held_input_lifetime_fixture();
    let mapping = op.mapping.clone().unwrap();
    f.service
        .respond(
            "human",
            &op.id,
            "lifetime-question",
            HumanResponse::Answer("甲".into()),
            2,
        )
        .unwrap();
    assert_eq!(
        held_input_lifetime_json(&f.service, &op.id)["held_inputs"][0]["answer_state"],
        "submitted"
    );
    f.service
        .observe_tool_resolved(&mapping, "lifetime-call", 3)
        .unwrap();
    for index in 1..32 {
        let call = format!("later-call-{index}");
        f.service
            .tool(
                mapping.clone(),
                &call,
                ToolCall::RequestInput {
                    request_id: format!("later-question-{index}"),
                    text: "Later question".into(),
                },
                4,
            )
            .unwrap();
        f.service.observe_tool_resolved(&mapping, &call, 5).unwrap();
    }
    let before = held_input_lifetime_json(&f.service, &op.id);
    assert_eq!(before["held_inputs"].as_array().unwrap().len(), 32);
    assert!(before["held_inputs"][0]["question"]["text"].as_str() == Some(question.as_str()));
    assert!(
        f.service
            .tool(
                mapping,
                "over-capacity",
                ToolCall::RequestInput {
                    request_id: "over-capacity-question".into(),
                    text: "Must not displace history".into()
                },
                6
            )
            .is_err()
    );
    assert_eq!(held_input_lifetime_json(&f.service, &op.id), before);
    assert_eq!(f.service.executor.replies.len(), 1);
    assert_eq!(f.service.executor.requests.len(), 1);
}

#[test]
fn held_input_lifetime_legacy_waiting_migrates_exact_question_and_unknown_answer() {
    let (mut f, op, question) = held_input_lifetime_fixture();
    f.service.executor.lose_tool_response = true;
    assert!(
        f.service
            .respond(
                "uncertain-human",
                &op.id,
                "lifetime-question",
                HumanResponse::Answer("甲".into()),
                2
            )
            .is_err()
    );
    let mut saved = serde_json::to_value(&f.service.state).unwrap();
    saved["operations"][&op.id]
        .as_object_mut()
        .unwrap()
        .remove("held_inputs");
    f.service
        .store
        .db
        .execute(
            "UPDATE assistant_service_state SET data=?1 WHERE assistant_id=?2",
            params![
                serde_json::to_string(&saved).unwrap(),
                f.service.assistant_id()
            ],
        )
        .unwrap();
    let db = f.root.path().join("service.db");
    let fake = f.service.executor.clone();
    drop(f.service);
    let mut reopened = AssistantService::open(&db, "pc", "profile", "host-1", fake).unwrap();
    reopened.tick(3).unwrap();
    let snapshot = held_input_lifetime_json(&reopened, &op.id);
    assert!(snapshot["held_inputs"][0]["question"]["text"].as_str() == Some(question.as_str()));
    assert_eq!(snapshot["held_inputs"][0]["call_id"], "lifetime-call");
    assert_eq!(snapshot["held_inputs"][0]["answer_state"], "unknown");
    assert_eq!(snapshot["held_inputs"][0]["resolution"], "unresolved");
    assert!(
        reopened
            .respond(
                "new-command",
                &op.id,
                "lifetime-question",
                HumanResponse::Answer("甲".into()),
                4
            )
            .is_err()
    );
    assert_eq!(reopened.executor.replies.len(), 1);
}

#[test]
fn held_input_lifetime_resolution_save_failure_does_not_retire_in_memory() {
    let (mut f, op, _) = held_input_lifetime_fixture();
    let mapping = op.mapping.clone().unwrap();
    f.observe(
        &op,
        ExecutionStatus::Completed(mapping.clone(), "Waiting".into()),
        "terminal",
        2,
    );
    let before = held_input_lifetime_json(&f.service, &op.id);
    f.service
        .store
        .db
        .execute_batch("PRAGMA query_only=ON")
        .unwrap();
    assert!(
        f.service
            .observe_tool_resolved(&mapping, "lifetime-call", 3)
            .is_err()
    );
    assert!(
        held_input_lifetime_json(&f.service, &op.id) == before,
        "Failed persistence must not retire the original held input"
    );
    f.service
        .store
        .db
        .execute_batch("PRAGMA query_only=OFF")
        .unwrap();
    f.service
        .observe_tool_resolved(&mapping, "lifetime-call", 4)
        .unwrap();
    assert_eq!(
        held_input_lifetime_json(&f.service, &op.id)["held_inputs"][0]["resolution"],
        "resolved"
    );
    assert_eq!(
        f.service
            .events(0, 256)
            .unwrap()
            .iter()
            .filter(|e| e.kind == "input_resolved")
            .count(),
        1
    );
}

#[test]
fn held_input_lifetime_legacy_terminal_missing_question_never_invents_identity() {
    let (mut f, op, _) = held_input_lifetime_fixture();
    f.observe(
        &op,
        ExecutionStatus::Completed(
            op.mapping.clone().unwrap(),
            "Waiting for an unspecified choice".into(),
        ),
        "terminal",
        2,
    );
    let mut saved = serde_json::to_value(&f.service.state).unwrap();
    saved["operations"][&op.id]
        .as_object_mut()
        .unwrap()
        .remove("held_inputs");
    f.service
        .store
        .db
        .execute(
            "UPDATE assistant_service_state SET data=?1 WHERE assistant_id=?2",
            params![
                serde_json::to_string(&saved).unwrap(),
                f.service.assistant_id()
            ],
        )
        .unwrap();
    let db = f.root.path().join("service.db");
    let fake = f.service.executor.clone();
    drop(f.service);
    let mut reopened = AssistantService::open(&db, "pc", "profile", "host-2", fake).unwrap();
    reopened.tick(3).unwrap();
    let snapshot = held_input_lifetime_json(&reopened, &op.id);
    assert_eq!(snapshot["held_inputs"], serde_json::json!([]));
    assert_eq!(snapshot["phase"], "Completed");
    assert_eq!(snapshot["input_tool_call"], "lifetime-call");
    assert!(snapshot["question"].is_null());
    assert!(
        reopened
            .respond(
                "late-answer",
                &op.id,
                "lifetime-question",
                HumanResponse::Answer("甲".into()),
                4
            )
            .is_err()
    );
    assert!(
        reopened
            .observe_tool_resolved(&op.mapping.unwrap(), "lifetime-call", 4)
            .is_err()
    );
    assert!(reopened.executor.replies.is_empty());
    assert_eq!(reopened.executor.requests.len(), 1);
}

#[test]
fn held_input_lifetime_reused_question_label_does_not_import_another_calls_answer() {
    let (mut f, op, _) = held_input_lifetime_fixture();
    let mapping = op.mapping.clone().unwrap();
    f.service
        .respond(
            "first-human",
            &op.id,
            "lifetime-question",
            HumanResponse::Answer("甲".into()),
            2,
        )
        .unwrap();
    f.service
        .observe_tool_resolved(&mapping, "lifetime-call", 3)
        .unwrap();
    f.service
        .tool(
            mapping.clone(),
            "distinct-call",
            ToolCall::RequestInput {
                request_id: "lifetime-question".into(),
                text: "Distinct question with the same display label".into(),
            },
            4,
        )
        .unwrap();
    f.observe(
        &op,
        ExecutionStatus::Completed(mapping, "Waiting".into()),
        "terminal",
        5,
    );
    let db = f.root.path().join("service.db");
    let fake = f.service.executor.clone();
    drop(f.service);
    let reopened = AssistantService::open(&db, "pc", "profile", "host-1", fake).unwrap();
    let snapshot = held_input_lifetime_json(&reopened, &op.id);
    assert_eq!(snapshot["held_inputs"][0]["answer_state"], "submitted");
    assert_eq!(snapshot["held_inputs"][1]["call_id"], "distinct-call");
    assert_eq!(
        snapshot["held_inputs"][1]["answer_state"], "not_submitted",
        "A semantic question label is not the complete native call identity"
    );
    assert_eq!(snapshot["phase"], "Completed");
    assert!(reopened.executor.replies.len() == 1);
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
fn restart_unknown_retains_its_attempt_and_not_dispatched_stays_paused() {
    let mut f = Fixture::new();
    let first = f.task("a", true);
    f.task("b", false);
    f.service.executor.lose_start = true;
    f.service.tick(0).unwrap();
    let op = f.service.operations().into_iter().find(|o|o.task_id.as_ref()==Some(&first.id)).unwrap();
    let mut fake = f.service.executor.clone();
    fake.statuses.clear();
    let db = f.root.path().join("service.db");
    drop(f.service);
    let mut reopened = AssistantService::open(&db, "pc", "profile", "host-1", fake).unwrap();
    reopened.tick(1).unwrap();
    assert_eq!(reopened.executor.requests.len(), 2);
    assert_eq!(reopened.executor.requests.iter().filter(|r|r.operation_id==op.id).count(),1);
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
        coordinator_model: None,
        effort: Some("high".into()),
        coordinator_effort: Some("low".into()),
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
fn v070_housekeeping_memory_reads_page_full_unicode_text_without_durable_copies() {
    let mut f = Fixture::new();
    let op = f.submit();
    let mut ids = vec![];
    let contents = ["资料".repeat(2000), "\u{1}".repeat(16384), "last-memory-sentinel".into()];
    for (n, value) in contents.iter().enumerate() {
        let CommandResult::Memory(id) = f.service.apply(&format!("memory-{n}"), Command::Remember {text:value.clone()}, 1).unwrap().result else {panic!()};
        ids.push(id);
    }
    let scope = op.mapping.clone().unwrap();
    let first = f.service.tool(scope.clone(), "page", ToolCall::Housekeeping(HousekeepingTool::MemoryRead {limit:2,after:None,memory_id:None,offset:0}), 2).unwrap();
    assert_eq!(first["memories"].as_array().unwrap().len(), 2);
    assert_eq!(first["nextAfter"], ids[1]);
    assert!(serde_json::to_vec(&first).unwrap().len() < 65536);
    let last = f.service.tool(scope.clone(), "last", ToolCall::Housekeeping(HousekeepingTool::MemoryRead {limit:2,after:Some(ids[1].clone()),memory_id:None,offset:0}), 2).unwrap();
    assert_eq!(last["memories"][0]["id"], ids[2]);
    assert!(last["nextAfter"].is_null());
    let mut all = String::new();
    let mut offset = 0;
    loop {
        let result = f.service.tool(scope.clone(), &format!("full-{offset}"), ToolCall::Housekeeping(HousekeepingTool::MemoryRead {limit:1,after:None,memory_id:Some(ids[0].clone()),offset}), 2).unwrap();
        all.push_str(result["memories"][0]["text"].as_str().unwrap());
        match result["memories"][0]["nextOffset"].as_u64() { Some(next) => offset=next as usize, None => break }
    }
    assert_eq!(all, contents[0]);
    assert!(f.service.tool(scope.clone(), "mid-character", ToolCall::Housekeeping(HousekeepingTool::MemoryRead {limit:1,after:None,memory_id:Some(ids[0].clone()),offset:1}), 2).is_err());
    f.service.tool(scope, "delete", ToolCall::Housekeeping(HousekeepingTool::MemoryDelete {user_message_id:"user:phone-1".into(),memory_id:ids[2].clone()}), 3).unwrap();
    let state = serde_json::to_string(&f.service.state).unwrap();
    assert!(!state.contains("last-memory-sentinel"));
    assert!(!state.contains("资料"));
    assert!(!state.contains("\\u0001"));
}

#[test]
fn v070_housekeeping_memory_effect_survives_crash_before_answer_journal() {
    for delete in [false, true] {
        let mut f = Fixture::new();
        let op = f.submit();
        let CommandResult::Memory(id) = f.service.apply("initial-memory", Command::Remember {text:"original".into()}, 1).unwrap().result else {panic!()};
        let call = ToolCall::Housekeeping(if delete {
            HousekeepingTool::MemoryDelete {user_message_id:"user:phone-1".into(),memory_id:id}
        } else {
            HousekeepingTool::MemoryUpdate {user_message_id:"user:phone-1".into(),memory_id:id,text:"updated-once".into()}
        });
        let key = format!("tool:{}:crash", op.id);
        f.service.store.db.execute_batch(&format!("CREATE TRIGGER fail_tool_journal BEFORE UPDATE ON assistant_service_state WHEN EXISTS(SELECT 1 FROM json_each(NEW.data,'$.journal') WHERE key='{key}') BEGIN SELECT RAISE(FAIL,'crash after committed memory effect'); END;")).unwrap();
        assert!(f.service.tool(op.mapping.clone().unwrap(), "crash", call.clone(), 2).is_err());
        assert!(f.service.executor.replies.is_empty());
        f.service.store.db.execute_batch("DROP TRIGGER fail_tool_journal").unwrap();
        let mut f = reopen_fixture(f);
        f.observe(&op, ExecutionStatus::Running(op.mapping.clone().unwrap()), "reconcile-original", 3);
        f.service.tool(op.mapping.clone().unwrap(), "crash", call, 4).unwrap();
        if delete {assert!(f.service.memories().unwrap().is_empty());}
        else {assert_eq!(f.service.memories().unwrap()[0].text,"updated-once");}
        assert_eq!(f.service.executor.replies.len(), 1);
        assert_eq!(f.service.executor.requests.len(), 1);
    }
}

#[test]
fn v070_housekeeping_schedule_creation_receipt_is_atomic_before_answer() {
    let mut f = Fixture::new();
    let op = f.submit();
    let directory = canonical_directory(&f.root.path().join("work")).unwrap();
    f.service.executor.project_grants.push((directory.clone(),false));
    let call = ToolCall::Housekeeping(HousekeepingTool::ScheduleCreate {user_message_id:"user:phone-1".into(),timezone:"Asia/Shanghai".into(),kind:SchedulePattern::Daily {hour:9,minute:30},prompt:"Read status".into(),directory,writes:false});
    let key = format!("tool:{}:crash", op.id);
    // The creation saved its raw-call receipt; fail the subsequent answer save.
    f.service.store.db.execute_batch(&format!("CREATE TRIGGER fail_second_save BEFORE UPDATE ON assistant_service_state WHEN EXISTS(SELECT 1 FROM json_each(OLD.data,'$.journal') WHERE key='{key}') BEGIN SELECT RAISE(FAIL,'crash after committed schedule'); END;")).unwrap();
    assert!(f.service.tool(op.mapping.clone().unwrap(),"crash",call.clone(),0).is_err());
    let created = f.service.schedules()[0].clone();
    f.service.store.db.execute_batch("DROP TRIGGER fail_second_save").unwrap();
    let mut f = reopen_fixture(f);
    f.observe(&op,ExecutionStatus::Running(op.mapping.clone().unwrap()),"reconcile-schedule",2000);
    // Revoked permissions cannot cause a committed receipt to be dispatched again.
    f.service.executor.project_grants.clear();
    f.service.tool(op.mapping.clone().unwrap(),"crash",call,2000).unwrap();
    assert_eq!(f.service.schedules().len(),1);
    assert_eq!(f.service.schedules()[0],created);
    assert_eq!(f.service.executor.replies.len(),1);
}

#[test]
fn v070_housekeeping_schedule_failed_persistence_cannot_report_or_fire_optimistic_effect() {
    let mut f = Fixture::new();
    let op = f.submit();
    let directory = canonical_directory(&f.root.path().join("work")).unwrap();
    f.service.executor.project_grants.push((directory.clone(),false));
    let call = ToolCall::Housekeeping(HousekeepingTool::ScheduleCreate {user_message_id:"user:phone-1".into(),timezone:"UTC".into(),kind:SchedulePattern::Once {at_ms:2000},prompt:"Read status".into(),directory,writes:false});
    let key = format!("tool:{}:create",op.id);
    f.service.store.db.execute_batch(&format!("CREATE TRIGGER fail_create BEFORE UPDATE ON assistant_service_state WHEN EXISTS(SELECT 1 FROM json_each(NEW.data,'$.journal') WHERE key='{key}') BEGIN SELECT RAISE(FAIL,'schedule persistence failed'); END;")).unwrap();
    assert!(f.service.tool(op.mapping.clone().unwrap(),"create",call.clone(),0).is_err());
    assert!(f.service.schedules().is_empty());
    assert!(f.service.executor.replies.is_empty());
    f.service.store.db.execute_batch("DROP TRIGGER fail_create").unwrap();
    f.service.tool(op.mapping.clone().unwrap(),"create",call,0).unwrap();
    let id=f.service.schedules()[0].id.clone();
    f.service.store.db.execute_batch("CREATE TRIGGER fail_delete BEFORE UPDATE ON assistant_service_state WHEN NOT EXISTS(SELECT 1 FROM json_each(NEW.data,'$.schedules')) BEGIN SELECT RAISE(FAIL,'schedule deletion failed'); END;").unwrap();
    assert!(f.service.remove_schedule("delete",&id).is_err());
    assert_eq!(f.service.schedules()[0].id,id);
    f.service.store.db.execute_batch("DROP TRIGGER fail_delete").unwrap();
    f.service.remove_schedule("delete",&id).unwrap();
    assert!(f.service.schedules().is_empty());
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

#[path = "service_product_tests.rs"]
mod product_tests;
#[path = "rebuild_tests.rs"]
mod rebuild_tests;

#[path = "service_jobs_tests.rs"]
mod jobs;
#[path = "service_conversation_tests.rs"]
mod conversations;

#[test]
fn legacy_main_conversation_has_persistent_metadata() {
    let f = Fixture::new();
    let view = f.service.domain_snapshot("phone").unwrap();
    let conversations = view["conversations"].as_array().expect("conversation inventory");
    assert_eq!(conversations.len(), 1);
    assert_eq!(conversations[0]["id"], format!("main:{}", f.service.assistant_id()));
    assert!(conversations[0]["createdAtMs"].is_i64());
    assert!(conversations[0]["updatedAtMs"].is_i64());
}
