use super::*;

#[test]
fn product_schedule_manual_run_replays_once_and_survives_restart() {
    let mut f = Fixture::new();
    let schedule = f.service.add_schedule("schedule", ScheduleSpec {
        kind: ScheduleKind::Daily { hour: 9, minute: 0 }, zone: ZoneRules::utc(),
        prompt: "Daily brief".into(), directory: f.root.path().join("work"), writes: false,
    }, 0).unwrap();
    f.service.run_schedule("manual", &schedule.id, 10).unwrap();
    f.service.run_schedule("manual", &schedule.id, 10).unwrap();
    let mut f = reopen_fixture(f);
    f.service.advance_schedules(11, 11).unwrap();
    let runs = f.service.schedule_runs().unwrap();
    assert_eq!(runs.len(), 1);
    assert!(runs[0].task_id.is_some());
    assert_eq!(f.service.tasks().unwrap().len(), 1);
    f.service.set_schedule_paused("pause", &schedule.id, true, 12).unwrap();
    assert!(f.service.schedules()[0].paused);
    assert!(f.service.schedules()[0].next_ms.is_none());
    f.service.set_schedule_paused("resume", &schedule.id, false, 13).unwrap();
    assert!(f.service.schedules()[0].next_ms.unwrap() > 13);
}

#[test]
fn product_missed_schedule_keeps_history_without_dispatch() {
    let mut f = Fixture::new();
    f.service.add_schedule("schedule", ScheduleSpec {kind: ScheduleKind::Once {at_ms: 1000},
        zone: ZoneRules::utc(), prompt: "Brief".into(), directory:f.root.path().join("work"),writes:false}, 0).unwrap();
    f.service.advance_schedules(100_000, 100_000).unwrap();
    assert!(f.service.tasks().unwrap().is_empty());
    assert_eq!(f.service.schedule_runs().unwrap()[0].status, "skipped");
    assert_eq!(Settings::default().timezone, "Asia/Shanghai");
}

#[test]
fn product_memory_provenance_and_undo_remove_future_context() {
    let mut f = Fixture::new();
    let receipt = f.service.remember_with_source("remember", "Prefer concise answers", "user:message", "personal", true, 1).unwrap();
    let CommandResult::Memory(id) = receipt.result else {panic!()};
    assert_eq!(f.service.memory_metadata()[&id].source, "user:message");
    f.service.undo_memory("undo", &id, 2).unwrap();
    f.service.undo_memory("undo", &id, 2).unwrap();
    assert!(f.service.memories().unwrap().is_empty());
    assert_eq!(f.service.memory_metadata()[&id].revoked_at_ms, Some(2));
}

#[test]
fn product_schedule_update_preserves_scope_and_rolls_back_failed_save() {
    let mut f=Fixture::new();
    let spec=ScheduleSpec {kind:ScheduleKind::Daily {hour:9,minute:0},zone:ZoneRules::utc(),prompt:"brief".into(),directory:f.root.path().join("work"),writes:false};
    let schedule=f.service.add_schedule("create",spec.clone(),0).unwrap();
    let mut wider=spec.clone();wider.writes=true;
    assert!(f.service.update_schedule("widen",&schedule.id,wider,"skip",1).is_err());
    f.service.store.db.execute_batch("CREATE TRIGGER fail_schedule_pause BEFORE UPDATE ON assistant_service_state BEGIN SELECT RAISE(FAIL,'simulated save failure'); END;").unwrap();
    assert!(f.service.set_schedule_paused("pause",&schedule.id,true,2).is_err());
    assert!(!f.service.schedules()[0].paused);
    f.service.store.db.execute_batch("DROP TRIGGER fail_schedule_pause").unwrap();
    let mut changed=spec;changed.prompt="updated brief".into();
    f.service.update_schedule("edit",&schedule.id,changed,"run_once",3).unwrap();
    assert_eq!(f.service.schedules()[0].spec.prompt,"updated brief");
    assert_eq!(f.service.schedules()[0].missed_policy,"run_once");
}

#[test]
fn product_memory_creation_and_provenance_are_atomic_and_replay_cannot_resurrect() {
    let mut f=Fixture::new();
    f.service.store.db.execute_batch("CREATE TRIGGER fail_memory_provenance BEFORE UPDATE ON assistant_service_state BEGIN SELECT RAISE(FAIL,'simulated save failure'); END;").unwrap();
    assert!(f.service.remember_with_source("remember","Prefer concise answers","user:source","personal",true,1).is_err());
    assert!(f.service.memories().unwrap().is_empty());
    f.service.store.db.execute_batch("DROP TRIGGER fail_memory_provenance").unwrap();
    let receipt=f.service.remember_with_source("remember","Prefer concise answers","user:source","personal",true,1).unwrap();
    let CommandResult::Memory(id)=receipt.result else {panic!()};
    f.service.undo_memory("undo",&id,2).unwrap();
    let mut f=reopen_fixture(f);
    f.service.remember_with_source("remember","Prefer concise answers","user:source","personal",true,1).unwrap();
    assert!(f.service.memories().unwrap().is_empty());
    assert_eq!(f.service.memory_metadata()[&id].revoked_at_ms,Some(2));
}

#[test]
fn product_revoked_auto_memory_cannot_be_silently_saved_under_new_command() {
    let mut f=Fixture::new();
    let receipt=f.service.remember_with_source("remember","Use short replies","user:1","personal",true,1).unwrap();
    let CommandResult::Memory(id)=receipt.result else {panic!()};
    f.service.undo_memory("undo",&id,2).unwrap();
    assert!(f.service.remember_with_source("another","Use short replies","user:2","personal",true,3).is_err());
    assert!(f.service.remember_with_source("manual","Use short replies","command:3","personal",false,4).is_ok());
}

#[test]
fn product_task_memory_opt_out_changes_next_context_and_records_supplied_ids() {
    let mut f=Fixture::new();
    f.service.remember_with_source("remember","Use short replies","user:1","personal",false,0).unwrap();
    let task=f.task("task",false);
    f.service.set_task_memories("without-memory",&task.id,false).unwrap();
    f.service.tick(1).unwrap();
    let execution=f.op(Role::Execution);
    assert!(f.service.memory_usage()[&execution.id].is_empty());
    assert!(!f.service.executor.requests.iter().find(|r|r.role==Role::Execution).unwrap().instructions.contains("Use short replies"));
}
