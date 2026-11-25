use super::*;
use std::sync::atomic::AtomicI64;
#[path = "tests/conversations.rs"]
mod conversations;
#[path = "tests/effort.rs"]
mod effort;

#[tokio::test]
async fn model_patch_keeps_other_role_null_resets_and_legacy_full_update_remains_supported() {
    let mut f=Fixture::new(false).await;
    let set=f.call("assistant/settings/update",json!({"patch":{"coordinator_model":"m"}}),"chief",false).await.unwrap();
    assert_eq!(set["snapshot"]["settings"]["coordinator_model"],"m");
    assert!(set["snapshot"]["settings"]["model"].is_null());
    let preference=f.call("assistant/settings/update",json!({"patch":{"tone":"brief"}}),"tone",false).await.unwrap();
    assert_eq!(preference["snapshot"]["settings"]["coordinator_model"],"m");
    let reset=f.call("assistant/settings/update",json!({"patch":{"coordinator_model":null}}),"reset",false).await.unwrap();
    assert!(reset["snapshot"]["settings"]["coordinator_model"].is_null());
    assert!(f.call("assistant/settings/update",json!({"patch":{"model":"missing-model"}}),"invalid",false).await.is_err());
    assert!(f.read().await["settings"]["model"].is_null());
    let legacy=Settings {model:Some("retained-private-model".into()),..Settings::default()};
    f.call("assistant/settings/update",json!({"settings":legacy}),"legacy",false).await.unwrap();
    assert_eq!(f.io.count("turn/start"),0);
    f.stop().await;
}

#[tokio::test]
async fn pending_models_apply_once_before_first_dispatch_and_retained_read_never_calls_runtime() {
    let root=tempfile::tempdir().unwrap();
    retained::save_pending_settings(root.path(),"assistant:profile:",Some(json!({"configStoreId":"profile","commandId":"choose-model","settings":Settings {model:Some("m".into()),coordinator_model:Some("m".into()),..Settings::default()}}))).unwrap();
    let io=Fake::default();
    let actor=Actor::start_with_io(root.path().into(),"computer".into(),"profile".into(),io.clone(),None,Arc::new(||1_000_000)).await.unwrap();
    let ready=actor.request("assistant/read",json!({}),"first-read".into(),None).await.unwrap();
    assert_eq!(ready["settings"]["model"],"m");
    assert_eq!(ready["settings"]["coordinator_model"],"m");
    assert!(retained::pending_settings(root.path(),"assistant:profile:").unwrap().is_none());
    assert_eq!(io.count("turn/start"),0);
    actor.park_for_runtime_transition().await.unwrap();actor.stop_parked_worker().await.unwrap();
    let reads=io.count("model/list");
    let saved=retained::snapshot(root.path(),&json!({})).unwrap();
    assert_eq!(saved["settings"]["model"],"m");
    assert_eq!(io.count("model/list"),reads);
    assert!(!retained::needs_wake(root.path(),1_000_000).unwrap());
}

#[test]
fn deferred_host_preserves_online_scheduler_watermark() {
    let root=tempfile::tempdir().unwrap();
    let io=Fake::default();
    let clock=Arc::new(AtomicI64::new(1_000_000));
    let (frames,_)=tokio::sync::broadcast::channel(32);
    let c=clock.clone();
    let mut worker=Worker::open(root.path().into(),"computer".into(),"profile".into(),io.clone(),None,Arc::new(move||c.load(Ordering::SeqCst)),frames.clone()).unwrap();
    worker.service.add_schedule("schedule",ScheduleSpec {kind:ScheduleKind::Once{at_ms:1_010_000},zone:ZoneRules::utc(),prompt:"scheduled work".into(),directory:worker.workspace.clone(),writes:false},1_000_000).unwrap();
    drop(worker);
    clock.store(1_010_500,Ordering::SeqCst);
    let c=clock.clone();
    let mut worker=Worker::open(root.path().into(),"computer".into(),"profile".into(),io,None,Arc::new(move||c.load(Ordering::SeqCst)),frames).unwrap();
    worker.service.scheduler_started_at(1_000_000);
    worker.service.tick(1_010_500).unwrap();
    let runs=worker.service.schedule_runs().unwrap();
    assert_eq!(runs.len(),1);
    assert_ne!(runs[0].status,"skipped");
}

#[tokio::test]
async fn assistant_lifecycle_parks_single_consumer_and_stop_ack_releases_locks_with_clones_alive() {
    let root=tempfile::tempdir().unwrap();let io=Fake::default();
    let actor=Actor::start_with_io(root.path().into(),"pc".into(),"profile".into(),io.clone(),None,Arc::new(||1_000_000)).await.unwrap();
    let retained=actor.clone();
    assert!(!actor.park_for_runtime_transition().await.unwrap().is_empty());
    assert!(actor.request("assistant/send",json!({"message":"must not dispatch"}),"parked-message".into(),None).await.is_err());
    assert_eq!(io.count("turn/start"),0);
    actor.resume_after_runtime_check().await.unwrap();
    assert!(actor.request("assistant/read",json!({}),"read".into(),None).await.is_ok());
    actor.park_for_runtime_transition().await.unwrap();actor.stop_parked_worker().await.unwrap();
    assert!(!retained.is_alive());
    let next=Actor::start_with_io(root.path().into(),"pc".into(),"profile".into(),io.clone(),None,Arc::new(||1_000_001)).await.unwrap();
    assert_eq!(io.count("turn/start"),0);
    next.park_for_runtime_transition().await.unwrap();next.stop_parked_worker().await.unwrap();
}

#[tokio::test]
async fn assistant_attachment_product_acceptance_reaches_native_image_input_without_expiring_upload() {
    let mut f=Fixture::new(false).await;
    let path=f.root.path().join("durable-input.png");std::fs::write(&path,b"\x89PNG\r\n\x1a\nfixture").unwrap();
    let input=AssistantInput::Image{id:uuid::Uuid::new_v4().to_string(),name:"diagram.png".into(),path:path.clone()};
    let p=json!({"message":"Inspect image","retainedAttachments":[input]});
    f.call("assistant/send",p.clone(),"image-acceptance",false).await.unwrap();
    f.call("assistant/send",p,"image-acceptance",false).await.unwrap();
    let state=f.io.0.lock().unwrap();
    let calls=state.calls.iter().filter(|(_,method,_)|method=="turn/start").collect::<Vec<_>>();
    assert_eq!(calls.len(),1);assert_eq!(calls[0].2["input"][1],json!({"type":"localImage","path":path}));
    drop(state);f.stop().await;
}

#[path = "tests/response_latency.rs"]
mod response_latency;

#[tokio::test]
async fn terminal_generation_failed_proof_keeps_worker_ready_and_recovery_offers_fenced() {
    let mut f=Fixture::new(false).await;
    let first=f.send("terminal-before-cold-restart").await;
    let original=Fixture::op(&first,"Coordinator");
    f.io.complete(&Fixture::mapping(&original),"original finished");
    let completed=f.read().await;
    assert_eq!(Fixture::op(&completed,"Coordinator")["phase"],"Completed");
    f.stop().await;
    f.io.0.lock().unwrap().generation=Some("cold-new-host".into());
    f.start().await;
    for _ in 0..3 {
        let snapshot=f.read().await;
        assert_eq!(snapshot["executorGenerationReady"],false);
        assert_eq!(snapshot["ready"],false);
        assert_eq!(snapshot["reconciliationRequired"],true);
        let stage=snapshot["startupStages"].as_array().unwrap().iter().find(|s|s["id"]=="coordinator").unwrap();
        assert_eq!(stage["status"],"reconciling");
        assert!(snapshot["operations"].as_array().unwrap().iter().all(|op|op.get("handoffRecovery").is_none()));
        let retained=Fixture::op(&snapshot,"Coordinator");
        assert_eq!(retained["mapping"],original["mapping"]);
        assert_eq!(retained["expected_generation"],original["expected_generation"]);
        assert_eq!(retained["phase"],"Completed");
        assert_eq!(f.io.count("thread/start"),1);
        assert_eq!(f.io.count("turn/start"),1);
    }
    f.stop().await;
}

#[tokio::test]
async fn coordinator_recovery_retains_stopped_pending_and_explicitly_finishes_same_rejection() {
    let mut f = Fixture::new(false).await;
    let s = f.call("assistant/send", json!({"message":"Create a read-only task"}), "original-once", false).await.unwrap()["snapshot"].clone();
    let op = Fixture::op(&s, "Coordinator");
    let scope = Fixture::mapping(&op);
    f.io.0.lock().unwrap().answer_before_dispatch_error = true;
    f.io.tool(json!(1), &scope, "original-call", "assistant_create_task",
        json!({"prompt":"Ask RED or BLUE, list immediate filenames", "directory":f.project.path(), "writes":false}));
    let s = f.read().await;
    assert_eq!(s["reconciliationRequired"], true);
    assert!(f.io.answers().is_empty());
    let pending = f.io.0.lock().unwrap().pending["1"].clone();
    f.io.complete(&scope, "Submitted task");
    f.io.0.lock().unwrap().pending.insert("1".into(), pending);
    let s = f.read().await;
    assert_eq!(Fixture::op(&s, "Coordinator")["phase"], "Completed");
    assert_eq!(s["pendingRequests"].as_array().unwrap().len(), 1,
        "A terminal turn is not evidence that the Host retired its request");
    assert_eq!(s["pendingRequests"][0]["recoveryAction"], "reject_original_tool_answer");
    f.stop().await;
    f.start().await;
    for _ in 0..2 { f.read().await; }
    assert!(f.io.answers().is_empty(), "Reads/restart must never resend an unknown answer");
    let p = json!({"operationId":op["id"], "requestId":"n:1", "response":{"Approval":{"approved":false}}});
    f.call("assistant/respond", p.clone(), "explicit-reject", false).await.unwrap();
    f.call("assistant/respond", p, "explicit-reject", false).await.unwrap();
    let s = f.read().await;
    assert_eq!(s["reconciliationRequired"], false, "{s}");
    assert!(s["tasks"].as_array().unwrap().is_empty());
    assert_eq!(f.io.answers().len(), 1);
    assert_eq!(f.io.answers()[0].0, 1);
    assert_eq!(f.io.answers()[0].1["success"], false);
    assert_eq!(f.io.answers()[0].1["contentItems"][0]["text"],
        "{\"error\":\"Task directory not authorized by this handoff\"}");
    assert_eq!(f.io.count("thread/start"), 1);
    assert_eq!(f.io.count("turn/start"), 1);
    let db = Connection::open(f.root.path().join("assistant/messages.db")).unwrap();
    assert_eq!(db.query_row("SELECT COUNT(*) FROM product_answers", [], |r|r.get::<_,i64>(0)).unwrap(), 0);
    f.stop().await;
}

#[tokio::test]
async fn coordinator_recovery_confirmed_answer_receipt_clears_fence_without_resend() {
    let mut f = Fixture::new(false).await;
    let s = f.send("receipt-original").await;
    let scope = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.0.lock().unwrap().lose_answer = true;
    f.io.tool(json!(2), &scope, "confirmed-call", "assistant_list_sessions", json!({"limit":1}));
    assert_eq!(f.read().await["reconciliationRequired"], true);
    assert_eq!(f.io.answers().len(), 1);
    f.io.0.lock().unwrap().answer_receipts.insert("2".into(), RuntimeReceipt {
        generation:"host".into(), state:"completed".into(),
        response:Some(json!({"id":2,"result":{"written":true}})),
    });
    assert_eq!(f.read().await["reconciliationRequired"], false);
    f.stop().await;
    f.start().await;
    assert_eq!(f.read().await["reconciliationRequired"], false);
    assert_eq!(f.io.answers().len(), 1);
    assert_eq!(f.io.count("turn/start"), 1);
    f.stop().await;
}

#[tokio::test]
async fn coordinator_recovery_command_receipt_cannot_confirm_answer_zero() {
    let mut f = Fixture::new(false).await;
    let s = f.send("separate-answer-namespace").await;
    let scope = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.0.lock().unwrap().lose_answer = true;
    f.io.tool(json!(0), &scope, "answer-zero", "assistant_list_sessions", json!({"limit":1}));
    assert_eq!(f.read().await["reconciliationRequired"], true);
    f.io.0.lock().unwrap().receipts.insert("0".into(), RuntimeReceipt {
        generation:"host".into(), state:"completed".into(),
        response:Some(json!({"id":0,"result":{"written":true}})),
    });
    assert_eq!(f.read().await["reconciliationRequired"], true,
        "A command with the same ID and even written=true is not an answer receipt");
    assert_eq!(f.io.answers().len(), 1);
    f.stop().await;
}

#[tokio::test]
async fn coordinator_recovery_legacy_answer_capability_gap_never_resends_or_releases_fence() {
    let mut f = Fixture::new(false).await;
    let s = f.call("assistant/send", json!({"message":"Create a read-only task"}),
        "legacy-answer-capability", false).await.unwrap()["snapshot"].clone();
    let op = Fixture::op(&s, "Coordinator");
    let scope = Fixture::mapping(&op);
    {
        let mut state = f.io.0.lock().unwrap();
        state.answer_before_dispatch_error = true;
        state.answer_receipt_unavailable = true;
    }
    f.io.tool(json!(1), &scope, "legacy-call", "assistant_create_task",
        json!({"prompt":"Read files", "directory":f.project.path(), "writes":false}));
    assert_eq!(f.read().await["reconciliationRequired"], true);
    f.stop().await;
    f.start().await;
    for _ in 0..2 { assert_eq!(f.read().await["reconciliationRequired"], true); }
    let params = json!({"operationId":op["id"],"requestId":"n:1","response":{"Approval":{"approved":false}}});
    assert!(f.call("assistant/respond", params, "explicit-legacy-reject", false).await.is_err());
    let s = f.read().await;
    assert_eq!(s["reconciliationRequired"], true);
    assert!(f.io.answers().is_empty());
    assert!(s["tasks"].as_array().unwrap().is_empty());
    let db = Connection::open(f.root.path().join("assistant/messages.db")).unwrap();
    assert_eq!(db.query_row("SELECT COUNT(*) FROM product_answers", [], |r|r.get::<_,i64>(0)).unwrap(), 1);
    f.stop().await;
}

#[tokio::test]
async fn coordinator_recovery_rejects_unknown_invalid_receipts_and_permission_expansion() {
    let cases = [
        ("submitted", None, "host"),
        ("unknown", None, "host"),
        ("completed", None, "host"),
        ("completed", Some(json!({"id":1,"result":{"written":false}})), "host"),
        ("completed", Some(json!({"id":99,"result":{"written":true}})), "host"),
        ("missing", Some(json!({"id":1,"result":{"written":true}})), "host"),
        ("missing", None, "other-host"),
    ];
    for (state, response, generation) in cases {
        let mut f = Fixture::new(false).await;
        let s = f.call("assistant/send", json!({"message":"Create a read-only task"}), "once", false).await.unwrap()["snapshot"].clone();
        let op = Fixture::op(&s, "Coordinator");
        let scope = Fixture::mapping(&op);
        f.io.0.lock().unwrap().answer_before_dispatch_error = true;
        f.io.tool(json!(1), &scope, "original", "assistant_create_task",
            json!({"prompt":"Read files", "directory":f.project.path(), "writes":false}));
        f.read().await;
        f.io.0.lock().unwrap().answer_receipts.insert("1".into(), RuntimeReceipt {
            generation:generation.into(), state:state.into(), response,
        });
        for approved in [true, false] {
            let p = json!({"operationId":op["id"],"requestId":"n:1","response":{"Approval":{"approved":approved}}});
            assert!(f.call("assistant/respond", p, &format!("reject-{approved}"), false).await.is_err());
        }
        assert_eq!(f.read().await["reconciliationRequired"], true);
        assert!(f.io.answers().is_empty(), "{generation}/{state} is not proof of non-dispatch");
        assert_eq!(f.io.count("turn/start"), 1);
        f.stop().await;
    }
}

#[tokio::test]
async fn coordinator_recovery_durable_confirmation_failure_keeps_product_fence() {
    let mut f = Fixture::new(false).await;
    let s = f.send("confirmation-save").await;
    let scope = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.0.lock().unwrap().lose_answer = true;
    f.io.tool(json!(3), &scope, "save-call", "assistant_list_sessions", json!({"limit":1}));
    assert_eq!(f.read().await["reconciliationRequired"], true);
    let db = Connection::open(f.root.path().join("assistant/executor.db")).unwrap();
    db.execute_batch("CREATE TRIGGER fail_answer_confirm BEFORE UPDATE ON assistant_executor_state WHEN EXISTS(SELECT 1 FROM json_each(NEW.data,'$.answers') WHERE json_extract(value,'$.accepted')=1) BEGIN SELECT RAISE(FAIL,'answer confirmation persistence unavailable'); END;").unwrap();
    f.io.0.lock().unwrap().answer_receipts.insert("3".into(), RuntimeReceipt {
        generation:"host".into(), state:"completed".into(), response:Some(json!({"id":3,"result":{"written":true}})),
    });
    assert_eq!(f.read().await["reconciliationRequired"], true,
        "Do not remove the product fence before executor confirmation is durable");
    db.execute_batch("DROP TRIGGER fail_answer_confirm").unwrap();
    assert_eq!(f.read().await["reconciliationRequired"], false);
    assert_eq!(f.io.answers().len(), 1);
    f.stop().await;
}

#[tokio::test]
async fn coordinator_recovery_initial_confirmation_save_failure_keeps_answer_intent() {
    let mut f = Fixture::new(false).await;
    let s = f.send("initial-confirmation-save").await;
    let scope = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    let db = Connection::open(f.root.path().join("assistant/executor.db")).unwrap();
    db.execute_batch("CREATE TRIGGER fail_initial_confirm BEFORE UPDATE ON assistant_executor_state WHEN EXISTS(SELECT 1 FROM json_each(NEW.data,'$.answers') WHERE json_extract(value,'$.accepted')=1) BEGIN SELECT RAISE(FAIL,'initial answer confirmation unavailable'); END;").unwrap();
    f.io.tool(json!(4), &scope, "initial-save-call", "assistant_list_sessions", json!({"limit":1}));
    let s = f.read().await;
    assert_eq!(s["reconciliationRequired"], true, "An in-memory confirmation cannot release the durable product intent");
    assert_eq!(f.io.answers().len(), 1);
    db.execute_batch("DROP TRIGGER fail_initial_confirm").unwrap();
    assert_eq!(f.read().await["reconciliationRequired"], false);
    assert_eq!(f.io.answers().len(), 1);
    f.stop().await;
}

fn housekeeping_answer(io: &Fake, id: &Value) -> Value {
    let answer = io
        .answers()
        .into_iter()
        .find(|(native, _)| native == id)
        .unwrap()
        .1;
    assert_eq!(answer["success"], true, "{answer}");
    serde_json::from_str(answer["contentItems"][0]["text"].as_str().unwrap()).unwrap()
}

fn generation_fixture_state(path: &Path, table: &str, edit: impl FnOnce(&mut Value)) {
    let db = Connection::open(path).unwrap();
    let data: String = db.query_row(&format!("SELECT data FROM {table}"), [], |r| r.get(0)).unwrap();
    let mut value: Value = serde_json::from_str(&data).unwrap();
    edit(&mut value);
    db.execute(&format!("UPDATE {table} SET data=?1"), [value.to_string()]).unwrap();
}

#[tokio::test]
async fn v070_generation_actor_recovers_legacy_preflight_without_redispatch() {
    generation_actor_recovers_legacy_preflight("host", "host").await;
}

#[tokio::test]
async fn v070_generation_three_generations_actor_preserves_original_intent() {
    generation_actor_recovers_legacy_preflight("current-host-C", "next-host-D").await;
}

async fn generation_actor_recovers_legacy_preflight(current: &str, next: &str) {
    let mut f = Fixture::new(false).await;
    // Generate a real Service intent without any native mutating request.
    f.io.0.lock().unwrap().unsupported = Some("thread/start".into());
    let snapshot = f.send("legacy-first-message").await;
    let original = Fixture::op(&snapshot, "Coordinator");
    assert_eq!(original["expected_generation"], "host");
    assert!(original["mapping"].is_null());
    assert_eq!(f.io.count("thread/start"), 0);
    assert_eq!(f.io.count("turn/start"), 0);
    f.stop().await;
    // Reproduce the old version's exact persisted shape in this disposable
    // fixture: stale empty executor, newer unmapped Service Unknown intent.
    generation_fixture_state(&f.root.path().join("assistant/executor.db"), "assistant_executor_state", |state| {
        state["generation"] = json!("old-host");
        state["cursor"] = json!(137);
        for field in ["operations", "threads", "effects", "answers", "streaming", "streaming_order", "not_dispatched"] {
            state[field] = json!({});
        }
        state["streaming_stopped"] = json!([]);
        state["streaming_gap"] = json!(false);
        state["clean_generation"] = Value::Null;
    });
    generation_fixture_state(&f.root.path().join("assistant/assistant.db"), "assistant_service_state", |state| {
        state["operations"][original["id"].as_str().unwrap()]["phase"] = json!("Unknown");
    });
    f.io.0.lock().unwrap().unsupported = None;
    f.io.0.lock().unwrap().generation = Some(current.into());
    f.start().await;
    let snapshot = f.read().await;
    assert_eq!(snapshot["reconciliationRequired"], false, "{snapshot}");
    let recovered = Fixture::op(&snapshot, "Coordinator");
    assert_eq!(recovered["id"], original["id"]);
    assert_eq!(recovered["phase"], "Paused");
    assert!(recovered["mapping"].is_null());
    assert_eq!(recovered["user_message_ids"], original["user_message_ids"]);
    assert_eq!(recovered["expected_generation"], original["expected_generation"]);
    assert_eq!(f.io.count("turn/start"), 0);
    assert_eq!(f.io.count("thread/start"), 0);
    // The same user command ID and a second Actor restart are reconciliation
    // only. They must never re-enqueue or dispatch the original handoff.
    f.send("legacy-first-message").await;
    f.stop().await;
    f.io.0.lock().unwrap().generation = Some(next.into());
    f.start().await;
    let snapshot = f.read().await;
    assert_eq!(Fixture::op(&snapshot, "Coordinator")["phase"], "Paused");
    assert_eq!(Fixture::op(&snapshot, "Coordinator")["expected_generation"], original["expected_generation"]);
    assert_eq!(snapshot["reconciliationRequired"], false);
    assert_eq!(f.io.count("turn/start"), 0);
    assert_eq!(snapshot["messages"].as_array().unwrap().iter().filter(|m| m["id"] == "user:legacy-first-message").count(), 1);
    f.send("explicit-new-message").await;
    assert_eq!(f.io.count("turn/start"), 1, "Only a new explicit intent may dispatch");
    f.stop().await;
}

#[tokio::test]
async fn v070_generation_actor_keeps_nonempty_old_generation_unknown() {
    let mut f = Fixture::new(false).await;
    let snapshot = f.send("accepted-before-restart").await;
    let original = Fixture::op(&snapshot, "Coordinator");
    assert!(!original["mapping"].is_null());
    f.stop().await;
    f.io.0.lock().unwrap().generation = Some("new-host".into());
    f.start().await;
    for _ in 0..2 {
        let snapshot = f.read().await;
        assert_eq!(snapshot["reconciliationRequired"], true);
        let current = Fixture::op(&snapshot, "Coordinator");
        assert_eq!(current["id"], original["id"]);
        assert_eq!(current["mapping"], original["mapping"]);
        assert_eq!(current["phase"], "Unknown");
        assert_eq!(f.io.count("turn/start"), 1);
        assert_eq!(f.io.count("thread/start"), 1);
    }
    f.stop().await;
}

#[tokio::test]
async fn v070_review_committed_housekeeping_actor_keeps_original_call_until_receipt_recovers() {
    for schedule in [true, false] {
        let mut f = Fixture::new(true).await;
        let s = f.send("review-receipt").await;
        let op = Fixture::op(&s, "Coordinator");
        let lead = Fixture::mapping(&op);
        let journal = format!("tool:{}:receipt-crash", op["id"].as_str().unwrap());
        let db = Connection::open(f.root.path().join("assistant/assistant.db")).unwrap();
        let record = if schedule { "OLD.data" } else { "NEW.data" };
        db.execute_batch(&format!("CREATE TRIGGER fail_review_receipt BEFORE UPDATE ON assistant_service_state WHEN EXISTS(SELECT 1 FROM json_each({record},'$.journal') WHERE key='{journal}') BEGIN SELECT RAISE(FAIL,'review: committed effect, receipt unavailable'); END;")).unwrap();
        let (name, arguments, collection) = if schedule {
            (
                "assistant_schedule_create",
                json!({"user_message_id":"user:review-receipt","timezone":"UTC","kind":{"type":"once","at_ms":2_000_000},"prompt":"Read project status","directory":f.project.path(),"writes":false}),
                "schedules",
            )
        } else {
            (
                "assistant_memory_add",
                json!({"user_message_id":"user:review-receipt","text":"Include test evidence"}),
                "memories",
            )
        };
        let native = json!("review-receipt-native");
        f.io.tool(native.clone(), &lead, "receipt-crash", name, arguments);
        let s = f.read().await;
        assert_eq!(s[collection].as_array().unwrap().len(), 1);
        let identity = s[collection][0]["id"].clone();
        eprintln!(
            "committed {collection}: {}, actor answers: {:?}",
            identity,
            f.io.answers()
        );
        assert!(
            f.io.answers().is_empty(),
            "A committed local effect must not become success:false"
        );
        assert_eq!(s["reconciliationRequired"], true);
        f.read().await;
        assert!(f.io.answers().is_empty());
        assert!(f.io.0.lock().unwrap().pending.contains_key(&key(&native)));
        f.stop().await;
        db.execute_batch("DROP TRIGGER fail_review_receipt")
            .unwrap();
        drop(db);
        // Recovery sends only the held answer. Its lost receipt must still not resend.
        f.io.0.lock().unwrap().lose_answer = true;
        f.start().await;
        let s = f.read().await;
        assert_eq!(s[collection].as_array().unwrap().len(), 1);
        assert_eq!(s[collection][0]["id"], identity);
        assert_eq!(f.io.answers().len(), 1);
        housekeeping_answer(&f.io, &native);
        assert_eq!(s["reconciliationRequired"], true);
        f.read().await;
        f.stop().await;
        f.start().await;
        assert_eq!(f.read().await[collection][0]["id"], identity);
        assert_eq!(f.io.answers().len(), 1);
        f.io.resolve(native);
        assert_eq!(f.read().await["reconciliationRequired"], false);
        assert_eq!(f.io.answers().len(), 1);
        assert_eq!(f.io.count("turn/start"), 1);
        f.stop().await;
    }
}

#[tokio::test]
async fn v070_housekeeping_natural_tools_persist_schedule_and_memory_crud() {
    let mut f = Fixture::new(false).await;
    let s = f.send("housekeeping").await;
    let lead = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.tool(json!(9001), &lead, "schedule", "assistant_schedule_create",
        json!({"user_message_id":"user:housekeeping","timezone":"Asia/Shanghai","kind":{"type":"daily","hour":9,"minute":30},"prompt":"Read the project status","directory":f.project.path(),"writes":false}));
    let s = f.read().await;
    assert_eq!(s["schedules"].as_array().unwrap().len(), 1);
    let schedule = s["schedules"][0].clone();
    assert_eq!(schedule["spec"]["zone"]["name"], "Asia/Shanghai");
    assert_eq!(schedule["spec"]["zone"]["initial_offset_seconds"], 28800);
    assert_eq!(
        housekeeping_answer(&f.io, &json!(9001))["id"],
        schedule["id"]
    );
    f.io.tool(
        json!(9002),
        &lead,
        "schedule-list",
        "assistant_schedule_list",
        json!({"limit":20}),
    );
    f.read().await;
    assert_eq!(
        housekeeping_answer(&f.io, &json!(9002))["schedules"][0]["id"],
        schedule["id"]
    );
    f.io.tool(
        json!(9003),
        &lead,
        "memory",
        "assistant_memory_add",
        json!({"user_message_id":"user:housekeeping","text":"Use concise project reports"}),
    );
    let s = f.read().await;
    let memory = s["memories"][0]["id"].clone();
    assert_eq!(
        housekeeping_answer(&f.io, &json!(9003))["result"]["value"],
        memory
    );
    f.io.tool(
        json!(9004),
        &lead,
        "memory-read",
        "assistant_memory_read",
        json!({"limit":20}),
    );
    f.read().await;
    assert_eq!(
        housekeeping_answer(&f.io, &json!(9004))["memories"][0]["text"],
        "Use concise project reports"
    );
    f.io.tool(json!(9005), &lead, "memory-edit", "assistant_memory_update",
        json!({"user_message_id":"user:housekeeping","memory_id":memory,"text":"Include test evidence"}));
    f.read().await;
    f.stop().await;
    f.start().await;
    let s = f.read().await;
    assert_eq!(s["memories"][0]["text"], "Include test evidence");
    assert_eq!(s["schedules"][0]["id"], schedule["id"]);
    // The phone being absent does not stop the already entrusted scheduler.
    f.at(schedule["next_ms"].as_i64().unwrap());
    let s = f.read().await;
    assert_eq!(s["tasks"].as_array().unwrap().len(), 1);
    assert_eq!(s["tasks"][0]["prompt"], "Read the project status");
    assert_eq!(s["tasks"][0]["writes"], false);
    f.io.tool(
        json!(9006),
        &lead,
        "schedule-delete",
        "assistant_schedule_delete",
        json!({"user_message_id":"user:housekeeping","schedule_id":schedule["id"]}),
    );
    f.read().await;
    f.io.tool(
        json!(9007),
        &lead,
        "memory-delete",
        "assistant_memory_delete",
        json!({"user_message_id":"user:housekeeping","memory_id":memory}),
    );
    let s = f.read().await;
    assert!(s["schedules"].as_array().unwrap().is_empty());
    assert!(s["memories"].as_array().unwrap().is_empty());
    assert_eq!(f.io.answers().len(), 7);
    f.stop().await;
}

#[tokio::test]
async fn v070_housekeeping_unknown_effect_answers_reconcile_without_repeating_after_restart() {
    for schedule in [false, true] {
        let mut f = Fixture::new(true).await;
        let s = f.send("housekeeping").await;
        let lead = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
        let (tool, args, collection) = if schedule {
            (
                "assistant_schedule_create",
                json!({"user_message_id":"user:housekeeping","timezone":"UTC","kind":{"type":"once","at_ms":2_000_000},"prompt":"Read project","directory":f.project.path(),"writes":false}),
                "schedules",
            )
        } else {
            (
                "assistant_memory_add",
                json!({"user_message_id":"user:housekeeping","text":"Use test evidence"}),
                "memories",
            )
        };
        f.io.0.lock().unwrap().lose_answer = true;
        f.io.tool(
            json!("unknown-housekeeping"),
            &lead,
            "durable-local",
            tool,
            args.clone(),
        );
        let s = f.read().await;
        assert_eq!(s[collection].as_array().unwrap().len(), 1);
        let identity = s[collection][0]["id"].clone();
        assert_eq!(s["reconciliationRequired"], true);
        f.read().await;
        f.stop().await;
        f.start().await;
        f.io.tool(
            json!("unknown-housekeeping"),
            &lead,
            "durable-local",
            tool,
            args,
        );
        let s = f.read().await;
        assert_eq!(s[collection].as_array().unwrap().len(), 1);
        assert_eq!(s[collection][0]["id"], identity);
        assert_eq!(f.io.answers().len(), 1, "Unknown answer must not be resent");
        assert_eq!(s["reconciliationRequired"], true);
        f.io.resolve(json!("unknown-housekeeping"));
        let s = f.read().await;
        assert_eq!(s["reconciliationRequired"], false);
        assert_eq!(s["unconfirmedWrites"], 0);
        assert_eq!(f.io.answers().len(), 1);
        assert_eq!(f.io.count("turn/start"), 1);
        f.stop().await;
    }
}

#[tokio::test]
async fn v070_housekeeping_schedule_rejects_unknown_zone_and_revoked_project_writes() {
    let mut f = Fixture::new(false).await;
    let s = f.send("housekeeping").await;
    let lead = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    let foreign = tempfile::tempdir().unwrap();
    let base = json!({"user_message_id":"user:housekeeping","timezone":"UTC","kind":{"type":"daily","hour":9,"minute":30},"prompt":"Read project","directory":f.project.path(),"writes":false});
    let mut bad_zone = base.clone();
    bad_zone["timezone"] = json!("Not/AZone");
    let mut bad_directory = base.clone();
    bad_directory["directory"] = json!(foreign.path());
    for (n, args) in [(9010, bad_zone), (9011, bad_directory)] {
        f.io.tool(
            json!(n),
            &lead,
            &format!("bad-{n}"),
            "assistant_schedule_create",
            args,
        );
        let s = f.read().await;
        assert!(s["schedules"].as_array().unwrap().is_empty());
        assert_eq!(f.io.answers().last().unwrap().1["success"], false);
    }
    // Permission is re-read at creation, even though no scheduled task is due yet.
    f.io.0
        .lock()
        .unwrap()
        .permissions
        .get_mut("desktop-thread")
        .unwrap()
        .settings = crate::settings::canonical(
        &json!({"sandbox":{"type":"readOnly"},"approvalPolicy":"on-request","approvalsReviewer":"user"}),
    );
    let mut writes = base.clone();
    writes["writes"] = json!(true);
    f.io.tool(
        json!(9012),
        &lead,
        "revoked-write",
        "assistant_schedule_create",
        writes,
    );
    let s = f.read().await;
    assert!(s["schedules"].as_array().unwrap().is_empty());
    assert_eq!(f.io.answers().last().unwrap().1["success"], false);
    f.io.tool(
        json!(9013),
        &lead,
        "authorized-read",
        "assistant_schedule_create",
        base,
    );
    assert_eq!(f.read().await["schedules"].as_array().unwrap().len(), 1);
    f.stop().await;
}

#[tokio::test]
async fn v070_housekeeping_workers_and_foreign_user_messages_cannot_mutate_private_state() {
    let mut f = Fixture::new(false).await;
    let s = f.send("housekeeping").await;
    let lead = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    let s = f.create(&lead, 9020, "Read only entrusted work").await;
    let child = Fixture::mapping(&Fixture::op(&s, "Execution"));
    let calls = [
        (
            "assistant_schedule_create",
            json!({"user_message_id":"user:housekeeping","timezone":"UTC","kind":{"type":"once","at_ms":2_000_000},"prompt":"Task","directory":f.project.path(),"writes":false}),
        ),
        (
            "assistant_schedule_delete",
            json!({"user_message_id":"user:housekeeping","schedule_id":"x"}),
        ),
        ("assistant_schedule_list", json!({"limit":20})),
        ("assistant_memory_read", json!({"limit":20})),
        (
            "assistant_memory_add",
            json!({"user_message_id":"user:housekeeping","text":"Inferred worker preference"}),
        ),
        (
            "assistant_memory_update",
            json!({"user_message_id":"user:housekeeping","memory_id":"x","text":"Changed"}),
        ),
        (
            "assistant_memory_delete",
            json!({"user_message_id":"user:housekeeping","memory_id":"x"}),
        ),
    ];
    for (n, (name, args)) in calls.into_iter().enumerate() {
        f.io.tool(json!(9030 + n), &child, &format!("child-{n}"), name, args);
        f.read().await;
        assert_eq!(f.io.answers().last().unwrap().1["success"], false);
    }
    f.io.tool(
        json!(9040),
        &lead,
        "foreign-instruction",
        "assistant_memory_add",
        json!({"user_message_id":"user:untrusted","text":"Forged instruction"}),
    );
    let s = f.read().await;
    assert_eq!(f.io.answers().last().unwrap().1["success"], false);
    assert!(s["memories"].as_array().unwrap().is_empty());
    assert!(s["schedules"].as_array().unwrap().is_empty());
    f.stop().await;
}

#[tokio::test]
async fn v070_housekeeping_unknown_memory_read_never_replays_deleted_text_or_resends() {
    let mut f = Fixture::new(true).await;
    let s = f.send("housekeeping").await;
    let lead = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.call(
        "assistant/memory/add",
        json!({"text":"deleted-memory-sentinel"}),
        "add",
        false,
    )
    .await
    .unwrap();
    f.io.0.lock().unwrap().lose_answer = true;
    f.io.tool(
        json!("read-memory"),
        &lead,
        "read-memory",
        "assistant_memory_read",
        json!({"limit":20}),
    );
    assert_eq!(f.read().await["reconciliationRequired"], true);
    // Simulate explicit local deletion while the native answer is unresolved.
    let db = Connection::open(f.root.path().join("assistant/assistant.db")).unwrap();
    db.execute("DELETE FROM assistant_memories", []).unwrap();
    let state: String = db
        .query_row("SELECT data FROM assistant_service_state", [], |r| r.get(0))
        .unwrap();
    assert!(!state.contains("deleted-memory-sentinel"));
    drop(db);
    f.stop().await;
    f.start().await;
    f.io.tool(
        json!("read-memory"),
        &lead,
        "read-memory",
        "assistant_memory_read",
        json!({"limit":20}),
    );
    let s = f.read().await;
    assert!(s["memories"].as_array().unwrap().is_empty());
    assert_eq!(s["reconciliationRequired"], true);
    assert_eq!(f.io.answers().len(), 1);
    f.io.resolve(json!("read-memory"));
    assert_eq!(f.read().await["reconciliationRequired"], false);
    assert_eq!(f.io.answers().len(), 1);
    f.stop().await;
}

#[tokio::test]
async fn v070_startup_stages_are_separate_and_do_not_generate_an_idle_turn() {
    let mut f = Fixture::new(false).await;
    let snapshot = f.read().await;
    let stages = snapshot["startupStages"]
        .as_array()
        .expect("four startup stages");
    assert_eq!(
        stages
            .iter()
            .map(|s| s["id"].as_str().unwrap())
            .collect::<Vec<_>>(),
        vec!["runtime", "account", "capabilities", "coordinator"]
    );
    assert_eq!(stages[0]["status"], "ready");
    assert_eq!(stages[1]["status"], "ready");
    assert_eq!(stages[2]["status"], "ready");
    assert_eq!(stages[3]["status"], "ready");
    assert_eq!(stages[3]["sessionCreated"], false);
    assert_eq!(f.io.count("turn/start"), 0);
    f.stop().await;
}

#[tokio::test]
async fn v070_login_failure_recovers_using_existing_provider_without_dispatch_or_resend() {
    let mut f = Fixture::new(false).await;
    f.io.0.lock().unwrap().account_reply = Some(json!({"requiresOpenaiAuth":true,"account":null}));
    f.at(1_010_000);
    let snapshot = f.read().await;
    assert_eq!(snapshot["startupStages"][1]["status"], "blocked");
    assert_eq!(
        snapshot["startupStages"][1]["recoveryAction"],
        "login_with_codex_cli"
    );
    assert_eq!(snapshot["ready"], false);
    assert!(
        f.call(
            "assistant/send",
            json!({"message":"work"}),
            "before-login",
            false
        )
        .await
        .is_err()
    );
    assert_eq!(f.io.count("turn/start"), 0);
    f.io.0.lock().unwrap().account_reply = Some(json!({"requiresOpenaiAuth":false,"account":null}));
    f.at(1_020_000);
    assert_eq!(f.read().await["ready"], true);
    f.call(
        "assistant/send",
        json!({"message":"work"}),
        "after-login",
        false,
    )
    .await
    .unwrap();
    assert_eq!(f.io.count("turn/start"), 1);
    f.stop().await;
}

#[tokio::test]
async fn v070_stream_replay_restart_and_completion_keep_one_reply_identity() {
    let mut f = Fixture::new(false).await;
    let snapshot = f.send("streaming").await;
    let coordinator = Fixture::mapping(&Fixture::op(&snapshot, "Coordinator"));
    f.io.emit(
        Value::Null,
        "item/agentMessage/delta",
        &coordinator,
        json!({"itemId":"answer","delta":"First "}),
    );
    let first = f.read().await;
    assert!(
        first["runtimeEventCursor"].as_u64().unwrap()
            > snapshot["runtimeEventCursor"].as_u64().unwrap()
    );
    let reply = first["messages"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["role"] == "Assistant")
        .expect("stream visible");
    let id = reply["id"].clone();
    assert_eq!(reply["text"], "First ");
    f.stop().await;
    f.start().await;
    f.io.emit(
        Value::Null,
        "item/agentMessage/delta",
        &coordinator,
        json!({"itemId":"answer","delta":"result"}),
    );
    assert_eq!(f.read().await["messages"][1]["text"], "First result");
    f.io.complete(&coordinator, "First result");
    let finished = f.read().await;
    let replies = finished["messages"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|m| m["role"] == "Assistant")
        .collect::<Vec<_>>();
    assert_eq!(replies.len(), 1);
    assert_eq!(replies[0]["id"], id);
    assert_eq!(replies[0]["text"], "First result");
    f.stop().await;
}

#[test]
fn collaboration_public_answer_history_redacts_secret_answers() {
    let mapping = NativeMapping {
        host_generation: "g".into(),
        thread_id: "t".into(),
        turn_id: "turn".into(),
    };
    let pending = PendingRequest {
        operation_id: "op".into(),
        mapping,
        native_id: json!(1),
        handle: "1".into(),
        method: "item/tool/requestUserInput".into(),
        call_id: None,
        params: json!({"questions":[{"id":"public","question":"Format?","isSecret":false},{"id":"key","question":"Key?","isSecret":true}]}),
    };
    let answer =
        HumanResponse::Answer(json!({"public":["PDF"],"key":["SECRET-SENTINEL"]}).to_string());
    let history = safe_answer_history("op", "1", Some(&pending), None, &answer, 1).unwrap();
    assert!(history.to_string().contains("PDF"));
    assert!(!history.to_string().contains("SECRET-SENTINEL"));
    assert_eq!(history["item"]["status"], "completed");
}

#[tokio::test]
async fn collaboration_member_result_is_attributed_and_persists_without_lead_rewrite() {
    let mut f = Fixture::new(false).await;
    let snapshot = f.send("handoff").await;
    let lead = Fixture::mapping(&Fixture::op(&snapshot, "Coordinator"));
    f.io.tool(
        json!(801),
        &lead,
        "member",
        "assistant_create_member",
        json!({"name":"Backend","duty":"Own backend maintenance","directory":f.project.path()}),
    );
    let snapshot = f.read().await;
    let member = snapshot["members"][0]["id"].clone();
    f.io.tool(json!(802),&lead,"assign","assistant_assign_task",json!({"prompt":"Implement backend","directory":f.project.path(),"writes":true,"member_id":member,"completion_criteria":"Pass tests"}));
    let snapshot = f.read().await;
    let execution = Fixture::mapping(&Fixture::op(&snapshot, "Execution"));
    f.io.complete(&lead, "Delegated the work");
    f.io.complete(&execution, "Backend complete with tests");
    let snapshot = f.read().await;
    assert!(
        snapshot["messages"]
            .as_array()
            .unwrap()
            .iter()
            .any(|message| message["text"] == "Backend complete with tests"
                && message["authorName"] == "Backend"
                && message["memberId"] == member)
    );
    assert_eq!(f.io.count("turn/start"), 2);
    f.stop().await;
    f.start().await;
    let snapshot = f.read().await;
    assert_eq!(snapshot["members"][0]["id"], member);
    assert_eq!(
        snapshot["messages"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|m| m["text"] == "Backend complete with tests")
            .count(),
        1
    );
    f.stop().await;
}

#[tokio::test]
async fn collaboration_legacy_coordinator_gets_new_tools_without_losing_chat_history() {
    let mut f = Fixture::new(false).await;
    let snapshot = f.send("old-handoff").await;
    let old = Fixture::mapping(&Fixture::op(&snapshot, "Coordinator"));
    f.io.complete(&old, "Existing assistant history");
    f.read().await;
    f.stop().await;
    let path = f.root.path().join("assistant/executor.db");
    let db = Connection::open(path).unwrap();
    let data: String = db
        .query_row("SELECT data FROM assistant_executor_state", [], |r| {
            r.get(0)
        })
        .unwrap();
    let mut data: Value = serde_json::from_str(&data).unwrap();
    for owner in data["threads"].as_object_mut().unwrap().values_mut() {
        owner.as_object_mut().unwrap().remove("tool_revision");
    }
    db.execute(
        "UPDATE assistant_executor_state SET data=?1",
        [data.to_string()],
    )
    .unwrap();
    drop(db);
    f.start().await;
    assert_eq!(
        f.io.count("turn/start"),
        1,
        "restoring must not generate a turn"
    );
    let snapshot = f.send("new-handoff").await;
    let successor = Fixture::mapping(
        snapshot["operations"]
            .as_array()
            .unwrap()
            .iter()
            .find(|o| o["role"] == "Coordinator" && o["phase"] == "Running")
            .unwrap(),
    );
    assert_ne!(old.thread_id, successor.thread_id);
    assert!(
        snapshot["messages"]
            .as_array()
            .unwrap()
            .iter()
            .any(|message| message["text"] == "Existing assistant history")
    );
    let io = f.io.0.lock().unwrap();
    let latest = io
        .calls
        .iter()
        .rev()
        .find(|(_, method, _)| method == "thread/start")
        .unwrap();
    assert!(
        latest.2["dynamicTools"]
            .as_array()
            .unwrap()
            .iter()
            .any(|tool| tool["name"] == "assistant_dispatch_session")
    );
    drop(io);
    f.stop().await;
}

#[tokio::test]
async fn collaboration_timeline_pages_keep_stable_message_ids_without_overlap() {
    let mut f = Fixture::new(false).await;
    let db = Connection::open(f.root.path().join("assistant/messages.db")).unwrap();
    for n in 0..235 {
        db.execute(
            "INSERT INTO product_timeline(id,role,text,at_ms) VALUES(?1,'User',?2,?3)",
            params![format!("older-{n}"), format!("Message {n}"), n],
        )
        .unwrap();
    }
    drop(db);
    let snapshot = f.read().await;
    assert_eq!(snapshot["messages"].as_array().unwrap().len(), 200);
    let before = snapshot["nextBefore"].as_u64().unwrap();
    let page = f
        .call(
            "assistant/read",
            json!({"before":before,"limit":50}),
            "older",
            false,
        )
        .await
        .unwrap();
    let older = page["conversationPage"]["messages"].as_array().unwrap();
    assert_eq!(older.len(), 35);
    assert!(page["conversationPage"]["nextBefore"].is_null());
    assert!(older.iter().all(|entry| {
        !snapshot["messages"]
            .as_array()
            .unwrap()
            .iter()
            .any(|message| entry["id"] == message["id"])
    }));
    f.stop().await;
}

#[tokio::test]
async fn collaboration_actor_multiquestion_history_uses_accepted_answer_shape() {
    let mut f = Fixture::new(false).await;
    let snapshot = f.send("handoff").await;
    let lead = Fixture::mapping(&Fixture::op(&snapshot, "Coordinator"));
    let snapshot = f.create(&lead, 890, "Need public and secret input").await;
    let execution = Fixture::mapping(&Fixture::op(&snapshot, "Execution"));
    f.io.emit(
        json!("multi-history"),
        "item/tool/requestUserInput",
        &execution,
        json!({"itemId":"input","questions":[
            {"id":"public","header":"Format","question":"Format?","isSecret":false},
            {"id":"key","header":"Key","question":"Key?","isSecret":true}
        ]}),
    );
    let snapshot = f.read().await;
    let op = Fixture::op(&snapshot, "Execution");
    let answer = json!({"public":["PDF"],"key":["SECRET-SENTINEL"]}).to_string();
    let response = f.call("assistant/respond", json!({"operationId":op["id"],"requestId":op["question"]["request_id"],"response":{"Answer":answer}}), "answer", false).await.unwrap();
    let history = &response["snapshot"]["questionHistory"];
    assert_eq!(history.as_array().unwrap().len(), 1);
    assert!(history.to_string().contains("PDF"));
    assert!(!history.to_string().contains("SECRET-SENTINEL"));
    assert_eq!(
        f.io.answers().last().unwrap().1,
        json!({"answers":{"public":{"answers":["PDF"]},"key":{"answers":["SECRET-SENTINEL"]}}})
    );
    f.stop().await;
    f.start().await;
    assert_eq!(&f.read().await["questionHistory"], history);
    f.stop().await;
}

#[tokio::test]
async fn collaboration_actor_read_accepts_task_detail_and_validates_all_fields() {
    let mut f = Fixture::new(false).await;
    let snapshot = f.send("handoff").await;
    let lead = Fixture::mapping(&Fixture::op(&snapshot, "Coordinator"));
    let snapshot = f.create(&lead, 891, "Task detail").await;
    let task = snapshot["tasks"][0]["id"].clone();
    let response = f
        .call(
            "assistant/read",
            json!({"after":0,"before":0,"limit":1,"taskId":task}),
            "detail",
            false,
        )
        .await
        .unwrap();
    assert_eq!(response["taskDetail"]["task"]["id"], task);
    assert!(
        response["conversationPage"]["messages"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    for invalid in [
        json!({"limit":0}),
        json!({"limit":101}),
        json!({"before":-1}),
        json!({"after":null}),
        json!({"taskId":"../task"}),
        json!({"taskId":""}),
        json!({"taskId":42}),
        json!({"extra":0}),
    ] {
        assert!(
            f.call("assistant/read", invalid, "invalid", false)
                .await
                .is_err()
        );
    }
    f.stop().await;
}

#[tokio::test]
async fn collaboration_actor_append_receipt_recovery_never_duplicates_effects() {
    for rejected in [false, true] {
        let mut f = Fixture::new(false).await;
        let snapshot = f.send("handoff").await;
        let lead = Fixture::mapping(&Fixture::op(&snapshot, "Coordinator"));
        f.io.tool(json!(892), &lead, "assign", "assistant_assign_task",
            json!({"prompt":"Original task","directory":f.project.path(),"writes":false,"completion_criteria":"Report evidence"}));
        let snapshot = f.read().await;
        let op = Fixture::op(&snapshot, "Execution");
        let execution = Fixture::mapping(&op);
        let task = op["task_id"].clone();
        f.io.complete(&lead, "Delegated");
        f.read().await;
        {
            let mut io = f.io.0.lock().unwrap();
            io.supports_steer = true;
            io.reject_steer = rejected;
            io.lose_method = (!rejected).then(|| "turn/steer".into());
        }
        let response = f
            .call(
                "assistant/send",
                json!({"message":"Correction","taskId":task}),
                "follow",
                false,
            )
            .await
            .unwrap();
        assert_eq!(response["snapshot"]["deliveries"][0]["status"], "unknown");
        f.io.complete(&execution, "Original finished");
        let snapshot = f.read().await;
        assert_eq!(snapshot["tasks"].as_array().unwrap().len(), 1);
        assert_eq!(snapshot["deliveries"][0]["status"], "unknown");
        f.stop().await;
        f.start().await;
        assert_eq!(f.io.count("turn/steer"), 1);
        f.io.0.lock().unwrap().hidden_receipts.clear();
        let snapshot = f.read().await;
        assert_eq!(snapshot["deliveries"][0]["status"], "sent");
        assert_eq!(
            snapshot["tasks"].as_array().unwrap().len(),
            if rejected { 2 } else { 1 }
        );
        f.read().await;
        assert_eq!(f.io.count("turn/steer"), 1);
        assert_eq!(f.io.count("turn/start"), if rejected { 3 } else { 2 });
        f.stop().await;
    }
}

#[tokio::test]
async fn collaboration_actor_pause_prevents_fallback_after_rejected_append() {
    let mut f = Fixture::new(false).await;
    let snapshot = f.send("handoff").await;
    let lead = Fixture::mapping(&Fixture::op(&snapshot, "Coordinator"));
    let snapshot = f.create(&lead, 893, "Original").await;
    let task = snapshot["tasks"][0]["id"].clone();
    {
        let mut io = f.io.0.lock().unwrap();
        io.supports_steer = true;
        io.reject_steer = true;
    }
    f.call(
        "assistant/send",
        json!({"message":"Correction","taskId":task}),
        "follow",
        false,
    )
    .await
    .unwrap();
    f.call(
        "assistant/task/pause",
        json!({"taskId":task}),
        "pause",
        false,
    )
    .await
    .unwrap();
    f.io.0.lock().unwrap().hidden_receipts.clear();
    let snapshot = f.read().await;
    assert_eq!(snapshot["deliveries"][0]["status"], "paused");
    assert_eq!(snapshot["tasks"].as_array().unwrap().len(), 1);
    assert_eq!(f.io.count("turn/steer"), 1);
    f.stop().await;
}

#[tokio::test]
async fn collaboration_actor_reassignment_attributes_history_to_original_operation() {
    let mut f = Fixture::new(false).await;
    let snapshot = f.send("handoff").await;
    let lead = Fixture::mapping(&Fixture::op(&snapshot, "Coordinator"));
    for (id, name) in [(894, "A"), (895, "B")] {
        f.io.tool(
            json!(id),
            &lead,
            name,
            "assistant_create_member",
            json!({"name":name,"duty":"Maintain work","directory":f.project.path()}),
        );
        f.read().await;
    }
    let snapshot = f.read().await;
    let members = snapshot["members"].as_array().unwrap();
    let a = members.iter().find(|m| m["name"] == "A").unwrap()["id"].clone();
    let b = members.iter().find(|m| m["name"] == "B").unwrap()["id"].clone();
    f.io.tool(json!(896), &lead, "assign", "assistant_assign_task",
        json!({"prompt":"Task","directory":f.project.path(),"writes":false,"member_id":a,"completion_criteria":"Evidence"}));
    let snapshot = f.read().await;
    let original = Fixture::op(&snapshot, "Execution");
    let task = original["task_id"].clone();
    // A persisted historical row remains tied to its original run even though
    // the logical task is subsequently assigned to a different member.
    let db = Connection::open(f.root.path().join("assistant/messages.db")).unwrap();
    db.execute("INSERT INTO product_timeline(id,role,text,at_ms,operation_id) VALUES('original-history','Assistant','Original progress',1000000,?1)", [original["id"].as_str().unwrap()]).unwrap();
    drop(db);
    f.io.tool(
        json!(897),
        &lead,
        "reassign",
        "assistant_reassign_task",
        json!({"task_id":task,"member_id":b,"message":"Continue with B"}),
    );
    let snapshot = f.read().await;
    assert_eq!(snapshot["tasks"].as_array().unwrap().len(), 1);
    assert_eq!(snapshot["tasks"][0]["assignment"]["member_id"], b);
    let history = snapshot["messages"]
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["id"] == "original-history")
        .unwrap();
    assert_eq!(history["memberId"], a);
    assert_eq!(history["authorName"], "A");
    let page = f
        .call("assistant/read", json!({"limit":100}), "page", false)
        .await
        .unwrap();
    assert_eq!(
        page["conversationPage"]["messages"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["id"] == "original-history")
            .unwrap()["memberId"],
        a
    );
    f.stop().await;
    f.start().await;
    assert_eq!(
        f.read().await["messages"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["id"] == "original-history")
            .unwrap()["memberId"],
        a
    );
    f.stop().await;
}

#[derive(Default)]
struct RequestGate {
    entered: AtomicBool,
    released: Mutex<bool>,
    wake: std::sync::Condvar,
}
impl RequestGate {
    fn release(&self) { *self.released.lock().unwrap()=true; self.wake.notify_all(); }
}

#[tokio::test]
async fn dispatch_in_flight_actor_cache_reports_progress_without_ready_or_redispatch() {
    let mut f=Fixture::new(false).await;
    let gate=Arc::new(RequestGate::default());
    f.io.0.lock().unwrap().request_gate=Some(("thread/start".into(),gate.clone()));
    let actor=f.actor.as_ref().unwrap().clone();
    let sender=actor.clone();
    let directory=f.project.path().to_path_buf();
    let send=tokio::spawn(async move {sender.request("assistant/send",json!({"message":"Start once","threadId":"desktop-thread"}),"in-flight".into(),Some(directory)).await});
    tokio::time::timeout(Duration::from_secs(3),async {
        while !gate.entered.load(Ordering::Acquire) {tokio::time::sleep(Duration::from_millis(5)).await;}
    }).await.unwrap();
    let during=actor.snapshot();
    gate.release();
    send.await.unwrap().unwrap();
    actor.request("assistant/read",json!({}),"dispatch-barrier".into(),None).await.unwrap();
    let after=actor.snapshot();
    drop(actor);
    assert_eq!(f.io.count("thread/start"),1);
    assert_eq!(f.io.count("turn/start"),1);
    f.stop().await;
    assert_eq!(during["dispatchInFlight"],true);
    assert_eq!(during["ready"],false);
    assert_eq!(during["fresh"],false);
    assert_eq!(during["error"],Value::Null);
    assert_ne!(after["dispatchInFlight"],true);
}

#[derive(Default)]
struct State {
    models: Option<Value>,
    configured_model: Option<String>,
    request_gate: Option<(String, Arc<RequestGate>)>,
    generation: Option<String>,
    account_reply: Option<Value>,
    calls: Vec<(Value, String, Value)>,
    receipts: BTreeMap<String, RuntimeReceipt>,
    answer_receipts: BTreeMap<String, RuntimeReceipt>,
    answer_receipt_unavailable: bool,
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
    answer_before_dispatch_error: bool,
    ignore_interrupt: bool,
    offline: bool,
    fail_reads: bool,
    serial: u64,
    supports_steer: bool,
    reject_steer: bool,
}
#[derive(Clone, Default)]
pub(crate) struct Fake(Arc<Mutex<State>>);
impl Fake {
    pub(crate) fn source_reader(&self, reader: Arc<dyn ProjectPermissionReader>) {
        self.0.lock().unwrap().permission_reader = Some(reader);
    }
    pub(crate) fn read_threads(&self) -> Vec<String> {
        self.0.lock().unwrap().calls.iter().filter(|(_,m,_)| m == "thread/read")
            .filter_map(|(_,_,p)| p["threadId"].as_str().map(str::to_owned)).collect()
    }
    pub(crate) fn owners(&self) -> usize { Arc::strong_count(&self.0) }
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
    pub(crate) fn tool(&self, id: Value, m: &NativeMapping, call_id: &str, name: &str, arguments: Value) {
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
    pub(crate) fn answers(&self) -> Vec<(Value, Value)> {
        self.0.lock().unwrap().answers.clone()
    }
}
impl RuntimeIo for Fake {
    fn account(&mut self) -> Result<AccountReadiness> {
        Ok(self
            .0
            .lock()
            .unwrap()
            .account_reply
            .as_ref()
            .map(AccountReadiness::from_native)
            .unwrap_or(AccountReadiness::Ready))
    }
    fn supports(&self, method: &str, _: &Value) -> bool {
        method == "turn/steer" && self.0.lock().unwrap().supports_steer
    }
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
        let gate = self.0.lock().unwrap().request_gate.as_ref()
            .filter(|(m,_)|m==method).map(|(_,g)|g.clone());
        if let Some(gate)=gate {
            gate.entered.store(true,Ordering::Release);
            let released=gate.released.lock().unwrap();
            let (_released, timeout)=gate.wake.wait_timeout_while(released,Duration::from_secs(10),|r| !*r).unwrap();
            ensure!(!timeout.timed_out(),"Fixture request gate timed out");
        }
        let mut s = self.0.lock().unwrap();
        let generation = s.generation.clone().unwrap_or_else(|| "host".into());
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
        if method == "turn/steer" && s.reject_steer {
            s.receipts.insert(
                key(&id),
                RuntimeReceipt {
                    generation: generation.clone(),
                    state: "completed".into(),
                    response: Some(
                        json!({"id":id,"error":{"code":-32602,"message":"Turn already stopped"}}),
                    ),
                },
            );
            s.hidden_receipts.insert(key(&id));
            anyhow::bail!("Native rejected steer; receipt temporarily unavailable");
        }
        s.serial += 1;
        let n = s.serial;
        let result = match method {
            "config/read" => json!({"config":{"model":s.configured_model}}),
            "model/list" => {
                s.models.clone().unwrap_or_else(|| json!({"data":[{"id":"m","model":"m","hidden":false,"isDefault":true}],"nextCursor":null}))
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
            "turn/steer" => json!({"turnId":p["expectedTurnId"]}),
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
                generation,
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
                generation: s.generation.clone().unwrap_or_else(|| "host".into()),
                state: "unknown".into(),
                response: None,
            });
        }
        Ok(s.receipts
            .get(&key(&id))
            .cloned()
            .unwrap_or(RuntimeReceipt {
                generation: s.generation.clone().unwrap_or_else(|| "host".into()),
                state: "missing".into(),
                response: None,
            }))
    }
    fn answer_receipt(&mut self, id: Value) -> Result<RuntimeReceipt> {
        let s = self.0.lock().unwrap();
        ensure!(!s.answer_receipt_unavailable, "Authoritative answer receipt unavailable");
        Ok(s.answer_receipts.get(&key(&id)).cloned().unwrap_or(RuntimeReceipt {
            generation:s.generation.clone().unwrap_or_else(|| "host".into()),
            state:"missing".into(), response:None,
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
        if std::mem::take(&mut s.answer_before_dispatch_error) {
            anyhow::bail!("Answer transport refused before Host reservation");
        }
        s.answers.push((id.clone(), result));
        if std::mem::take(&mut s.lose_answer) {
            let generation = s.generation.clone().unwrap_or_else(|| "host".into());
            s.answer_receipts.insert(key(&id), RuntimeReceipt {
                generation, state:"unknown".into(), response:None,
            });
            anyhow::bail!("Lost answer receipt");
        }
        let generation = s.generation.clone().unwrap_or_else(|| "host".into());
        s.answer_receipts.insert(key(&id), RuntimeReceipt {
            generation, state:"completed".into(), response:Some(json!({"id":id,"result":{"written":true}})),
        });
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
            generation: s.generation.clone().unwrap_or_else(|| "host".into()),
            next_after: events.last().map_or(after, |e| e.sequence),
            snapshot_required: false,
            events,
            pending_requests: s.pending.values().cloned().collect(),
        })
    }
    fn health(&mut self) -> Result<RuntimeHealth> {
        let s = self.0.lock().unwrap();
        Ok(RuntimeHealth {
            runtime_id: "runtime".into(),
            generation: s.generation.clone().unwrap_or_else(|| "host".into()),
            online: !s.offline,
            initialized: true,
            protocol_verified: true,
            reconciliation_required: false,
            scoped_admission:false,
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
        let mut reply = self.actor
            .as_ref()
            .unwrap()
            .request(
                method,
                p,
                id.into(),
                directory.then(|| self.project.path().into()),
            )
            .await?;
        // Product acceptance no longer waits for native dispatch. Tests that
        // inspect execution state explicitly request a subsequent worker read.
        if !is_read(method) {
            reply["snapshot"] = self.actor.as_ref().unwrap()
                .request("assistant/read", json!({}), format!("barrier:{id}"), None).await?;
        }
        Ok(reply)
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
    let tool_names: Vec<_> = call["dynamicTools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|tool| tool["name"].as_str().unwrap())
        .collect();
    for required in [
        "assistant_dispatch_session",
        "assistant_steer_session",
        "assistant_control_session",
        "assistant_list_jobs",
        "assistant_request_input",
    ] {
        assert!(tool_names.contains(&required));
    }
    for retired in ["assistant_create_task", "assistant_assign_task", "assistant_create_member", "assistant_seal_group"] {
        assert!(!tool_names.contains(&retired), "{retired} must not be offered to a new coordinator");
    }
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
async fn lost_create_tool_answer_does_not_replay_committed_task_or_block_its_dispatch() {
    let mut f = Fixture::new(false).await;
    let s = f.send("phone").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.0.lock().unwrap().lose_answer = true;
    let s = f.create(&cm, 701, "queued behind unknown answer").await;
    assert_eq!(s["tasks"][0]["status"], "running");
    assert_eq!(f.io.count("turn/start"), 2);
    f.read().await;
    assert_eq!(f.io.answers().len(), 1);
    assert_eq!(f.io.count("turn/start"), 2);
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

#[path = "tests/product_flow.rs"]
mod product_flow;

#[tokio::test]
async fn thin_coordinator_dispatches_a_job_and_hears_only_its_settlement_through_the_actual_worker() {
    let f = Fixture::new(false).await;
    let s = f.send("phone-1").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.tool(
        json!(101),
        &cm,
        "call-dispatch",
        "assistant_dispatch_session",
        json!({"title":"整理季度报告","prompt":"写一份报告","directory":f.project.path(),"writes":true}),
    );
    let s = f.read().await;
    let job = &s["jobs"][0];
    assert_eq!(job["title"], "整理季度报告");
    assert_eq!(job["status"], "running");
    assert_eq!(job["workspaceMode"], "direct");
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    assert_eq!(job["threadId"], em.thread_id);
    assert_ne!(cm.thread_id, em.thread_id);
    assert_eq!(s["tasks"][0]["id"], job["taskId"]);
    // The coordinator's call was answered with the job's identity, not held until it ends.
    let answered = f.io.answers();
    assert!(
        answered.iter().any(|answer| answer.1.to_string().contains(job["taskId"].as_str().unwrap())),
        "{answered:?}"
    );

    f.at(1_001_000);
    f.io.complete(&em, "报告已完成：共 12 页");
    let s = f.read().await;
    assert_eq!(s["jobs"][0]["status"], "done");
    let settled: Vec<_> = s["events"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|event| event["kind"] == "job_settled")
        .collect();
    assert_eq!(settled.len(), 1);
    let detail: Value = serde_json::from_str(settled[0]["detail"].as_str().unwrap()).unwrap();
    assert_eq!(detail["taskId"], job["taskId"]);
    assert_eq!(detail["status"], "completed");
    assert_eq!(detail["summary"], "报告已完成：共 12 页");
    assert_eq!(settled[0]["wake_coordinator"], true);
}

/// Git on this worker's thread can outlast one read's 1.5 s budget; the phone would be
/// served its cache meanwhile, the test simply asks again.
async fn patient_read(f: &Fixture) -> Value {
    for _ in 0..20 {
        if let Ok(value) = f.call("assistant/read", json!({}), "read", false).await {
            return value;
        }
    }
    panic!("worker never answered a read")
}

fn project_git(dir: &Path, args: &[&str]) -> String {
    let output = std::process::Command::new("git")
        .arg("-C")
        .arg(dir)
        .args(["-c", "user.name=Test", "-c", "user.email=test@localhost", "-c", "commit.gpgsign=false"])
        .args(args)
        .output()
        .unwrap();
    assert!(output.status.success(), "git {args:?}: {}", String::from_utf8_lossy(&output.stderr));
    String::from_utf8_lossy(&output.stdout).trim().to_owned()
}

#[tokio::test]
async fn write_job_runs_on_its_own_branch_and_only_the_user_merges_it_through_the_actual_worker() {
    let f = Fixture::new(false).await;
    let project = dunce::canonicalize(f.project.path()).unwrap();
    project_git(&project, &["init", "-q"]);
    project_git(&project, &["config", "core.autocrlf", "false"]);
    std::fs::write(project.join("a.txt"), "one\n").unwrap();
    project_git(&project, &["add", "-A"]);
    project_git(&project, &["commit", "-q", "-m", "initial"]);

    let s = f.send("phone-1").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.tool(
        json!(101),
        &cm,
        "call-dispatch",
        "assistant_dispatch_session",
        json!({"title":"加一个文件","prompt":"Add b.txt","directory":f.project.path(),"writes":true}),
    );
    let s = patient_read(&f).await;
    let job = s["jobs"][0].clone();
    assert_eq!(job["workspaceMode"], "worktree", "{job}");
    assert_eq!(job["status"], "running", "the executor must accept the job's private directory");
    assert_eq!(job["directory"], json!(project));
    let task = job["taskId"].as_str().unwrap();
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    let cwd = {
        let io = f.io.0.lock().unwrap();
        let start = io.calls.iter().rev().find(|(_, method, _)| method == "thread/start").unwrap();
        PathBuf::from(start.2["cwd"].as_str().unwrap())
    };
    assert!(cwd.components().any(|part| part.as_os_str() == "worktrees"), "{cwd:?}");
    assert_ne!(dunce::canonicalize(&cwd).unwrap(), project);

    // The model has no tool for this, and the user cannot merge while the job runs.
    assert!(f.call("assistant/v2/task/merge", json!({"taskId":task}), "merge-early", false).await.is_err());
    std::fs::write(cwd.join("b.txt"), "two\n").unwrap();
    f.at(1_001_000);
    f.io.complete(&em, "已添加 b.txt");
    let s = patient_read(&f).await;
    assert_eq!(s["jobs"][0]["readyToMerge"], true);
    assert_eq!(s["jobs"][0]["diffStats"], json!({"files":1,"insertions":1,"deletions":0}));
    assert!(!project.join("b.txt").exists());

    let reply = f.call("assistant/v2/task/merge", json!({"taskId":task}), "merge-1", false).await.unwrap();
    assert_eq!(reply["accepted"], true);
    assert_eq!(reply["result"]["merge"]["outcome"], "merged");
    assert_eq!(std::fs::read_to_string(project.join("b.txt")).unwrap(), "two\n");
    assert_eq!(project_git(&project, &["status", "--porcelain"]), "");
    assert_eq!(reply["snapshot"]["jobs"][0]["mergedAtMs"], 1_001_000);
    assert!(!cwd.exists());
    let io = f.io.0.lock().unwrap();
    let offered = io
        .calls
        .iter()
        .filter(|(_, method, _)| method == "thread/start")
        .flat_map(|(_, _, params)| params["dynamicTools"].as_array().cloned().unwrap_or_default())
        .map(|tool| tool["name"].as_str().unwrap().to_owned())
        .collect::<Vec<_>>();
    assert!(!offered.is_empty());
    assert!(!offered.iter().any(|name| name.contains("merge") || name.contains("discard")), "{offered:?}");
}

fn drain(frames: &mut tokio::sync::broadcast::Receiver<Value>) -> Vec<Value> {
    std::iter::from_fn(|| frames.try_recv().ok()).collect()
}
fn kinds(frames: &[Value]) -> Vec<&str> {
    frames.iter().map(|frame| frame["kind"].as_str().unwrap()).collect()
}

#[tokio::test]
async fn coordinator_deltas_emit_stream_frames_while_worker_stdout_is_filtered() {
    let f = Fixture::new(false).await;
    let mut frames = f.actor.as_ref().unwrap().subscribe_stream();
    let s = f.send("phone-1").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.emit(Value::Null, "item/agentMessage/delta", &cm, json!({"itemId":"answer","delta":"已安排，"}));
    f.read().await;
    f.io.emit(Value::Null, "item/agentMessage/delta", &cm, json!({"itemId":"answer","delta":"稍后汇报"}));
    f.io.tool(
        json!(101),
        &cm,
        "call-dispatch",
        "assistant_dispatch_session",
        json!({"title":"跑回归测试","prompt":"Run the tests","directory":f.project.path(),"writes":false}),
    );
    let s = f.read().await;
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    let task = s["jobs"][0]["taskId"].clone();

    // The job talks, runs a command with secret-looking output, edits a file and starts agents.
    f.io.emit(Value::Null, "item/agentMessage/delta", &em, json!({"itemId":"job-answer","delta":"JOB-TRANSCRIPT-TEXT"}));
    f.io.emit(Value::Null, "item/started", &em, json!({"item":{"id":"c1","type":"commandExecution","command":"cargo test --workspace","aggregatedOutput":"STDOUT-SECRET-123"}}));
    f.io.emit(Value::Null, "item/commandExecution/outputDelta", &em, json!({"itemId":"c1","delta":"STDOUT-SECRET-456"}));
    f.io.emit(Value::Null, "item/completed", &em, json!({"item":{"id":"c1","type":"commandExecution","command":"cargo test --workspace","aggregatedOutput":"STDOUT-SECRET-789","exitCode":0}}));
    f.read().await;
    f.io.emit(Value::Null, "item/started", &em, json!({"item":{"id":"a1","type":"collabAgentToolCall","tool":"spawnAgent","prompt":"AGENT-PROMPT-TEXT",
        "agentsStates":{"child-1":{"status":"running","message":"AGENT-MESSAGE-TEXT"},"child-2":{"status":"pending_init"}}}}));
    tokio::time::sleep(Duration::from_millis(1100)).await;
    f.read().await;
    f.io.complete(&cm, "已安排，稍后汇报");
    f.read().await;

    let seen = drain(&mut frames);
    let all = serde_json::to_string(&seen).unwrap();
    for leaked in ["STDOUT-SECRET", "JOB-TRANSCRIPT-TEXT", "AGENT-PROMPT-TEXT", "AGENT-MESSAGE-TEXT"] {
        assert!(!all.contains(leaked), "{leaked} reached the phone's stream");
    }
    // Every frame is addressed and numbered without a gap.
    let epoch = seen[0]["epoch"].clone();
    for (index, frame) in seen.iter().enumerate() {
        assert_eq!(frame["sequence"], index as u64 + 1, "{frame}");
        assert_eq!(frame["epoch"], epoch);
        assert_eq!(frame["runtimeId"], s["runtimeId"]);
        assert!(frame["generation"].is_string());
    }
    // The coordinator's words arrive as deltas that rebuild its message, then one close.
    let spoken: String = seen
        .iter()
        .filter(|frame| frame["kind"] == "coordinator_delta")
        .map(|frame| {
            assert_eq!(frame["operationId"], Fixture::op(&s, "Coordinator")["id"]);
            assert_eq!(frame["reset"], false);
            frame["delta"].as_str().unwrap()
        })
        .collect();
    assert_eq!(spoken, "已安排，稍后汇报");
    assert_eq!(kinds(&seen).iter().filter(|kind| **kind == "coordinator_done").count(), 1);
    // The job is described by status and one line only.
    let stages: Vec<&Value> = seen.iter().filter(|frame| frame["kind"] == "job_stage").collect();
    assert!(stages.iter().all(|frame| frame["taskId"] == task && frame["threadId"] == em.thread_id));
    assert_eq!(stages[0]["status"], "running");
    assert!(stages.iter().any(|frame| frame["stage"] == "command" && frame["stageLabel"] == "cargo test --workspace"));
    let last = stages.last().unwrap();
    assert_eq!((last["stage"].as_str(), last["activeSubagents"].as_u64()), (Some("subagent"), Some(2)));
    assert!(stages.iter().all(|frame| frame["stageLabel"].as_str().unwrap().chars().count() <= 80));
}

#[tokio::test]
async fn attention_events_carry_original_request_id_without_rewording() {
    let f = Fixture::new(false).await;
    let mut frames = f.actor.as_ref().unwrap().subscribe_stream();
    let s = f.send("phone-1").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.tool(
        json!(101),
        &cm,
        "call-dispatch",
        "assistant_dispatch_session",
        json!({"title":"改配置","prompt":"Edit the config","directory":f.project.path(),"writes":true}),
    );
    let s = f.read().await;
    let execution = Fixture::op(&s, "Execution");
    let em = Fixture::mapping(&execution);
    drain(&mut frames);

    f.io.emit(
        json!("native-approval-7"),
        "item/commandExecution/requestApproval",
        &em,
        json!({"itemId":"cmd","command":"rm -rf build","reason":"APPROVAL-REASON-TEXT","startedAtMs":0}),
    );
    let s = f.read().await;
    let pending = s["pendingRequests"].as_array().unwrap();
    let request = pending.iter().find(|p| p["operationId"] == execution["id"]).unwrap()["requestId"].clone();
    let seen = drain(&mut frames);
    let opened: Vec<&Value> = seen.iter().filter(|frame| frame["kind"] == "attention_opened").collect();
    assert_eq!(opened.len(), 1, "{seen:?}");
    assert_eq!(opened[0]["requestId"], request, "the frame names the request the snapshot holds");
    assert_eq!(opened[0]["operationId"], execution["id"]);
    assert_eq!(opened[0]["taskId"], s["jobs"][0]["taskId"]);
    assert_eq!(opened[0]["questionKind"], "approval");
    let all = serde_json::to_string(&seen).unwrap();
    assert!(!all.contains("rm -rf build") && !all.contains("APPROVAL-REASON-TEXT"), "the request must not be restated: {all}");
    assert!(seen.iter().any(|frame| frame["kind"] == "job_stage" && frame["status"] == "waiting_approval"));
    // The coordinator was not started to relay it.
    assert_eq!(s["operations"].as_array().unwrap().iter().filter(|o| o["role"] == "Coordinator").count(), 1);

    f.io.resolve(json!("native-approval-7"));
    f.read().await;
    let seen = drain(&mut frames);
    let resolved: Vec<&Value> = seen.iter().filter(|frame| frame["kind"] == "attention_resolved").collect();
    assert_eq!(resolved.len(), 1, "{seen:?}");
    assert_eq!(resolved[0]["requestId"], request);
    assert!(seen.iter().any(|frame| frame["kind"] == "job_stage" && frame["status"] == "running"));
}

#[tokio::test]
async fn stream_epoch_changes_after_restart() {
    let mut f = Fixture::new(false).await;
    let mut frames = f.actor.as_ref().unwrap().subscribe_stream();
    let s = f.send("phone-1").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.emit(Value::Null, "item/agentMessage/delta", &cm, json!({"itemId":"answer","delta":"First "}));
    f.read().await;
    let before = drain(&mut frames);
    assert!(!before.is_empty());
    let (epoch, last) = (before[0]["epoch"].clone(), before.last().unwrap()["sequence"].as_u64().unwrap());
    assert!(last >= 1);

    f.stop().await;
    f.start().await;
    let mut frames = f.actor.as_ref().unwrap().subscribe_stream();
    f.io.emit(Value::Null, "item/agentMessage/delta", &cm, json!({"itemId":"answer","delta":"second"}));
    f.read().await;
    let after = drain(&mut frames);
    assert!(!after.is_empty());
    assert_ne!(after[0]["epoch"], epoch, "a restarted worker must not look like a continuation");
    assert_eq!(after[0]["sequence"], 1);
    // What the phone held is replaced: the first frame after a restart carries the whole text.
    let delta = after.iter().find(|frame| frame["kind"] == "coordinator_delta").unwrap();
    assert_eq!(delta["delta"], "First second");
}
