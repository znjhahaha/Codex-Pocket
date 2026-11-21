use super::*;

fn terminal_fixture() -> (Fixture, Vec<crate::assistant::Operation>) {
    let mut f=Fixture::new();
    let mapping=f.start("cold-completed",Role::Coordinator);
    f.executor.state.streaming_stopped.insert("cold-completed".into());
    f.executor.runtime.turns.get_mut(&mapping.thread_id).unwrap()[0]["status"]=json!("completed");
    f.executor.save().unwrap();
    let op=crate::assistant::Operation {
        id:"cold-completed".into(),role:Role::Coordinator,task_id:None,mapping:Some(mapping.clone()),
        expected_generation:"host-1".into(),phase:crate::assistant::OperationPhase::Completed,
        suspended:false,question:None,input_tool_call:None,held_inputs:vec![],result:Some("done".into()),
        authorized_directories:vec![],prompt:None,user_message_ids:vec![],
    };
    f.executor.runtime.recovery_snapshot=Some(json!({"generation":"host-2","sequence":0,"reconciliationRequired":false,
        "runs":[{"generation":"host-1","threadId":mapping.thread_id,"turnId":mapping.turn_id,"state":"completed"}],
        "pendingRequests":[],"commandCount":2,"answerCount":0,"unconfirmedWrites":0,"unknownOperations":0,"evidenceDigest":"a".repeat(64)}));
    (generation_reopen(f,"host-2"),vec![op])
}

#[test]
fn terminal_generation_retains_history_and_starts_successor_only_after_exact_proof() {
    let (mut f,operations)=terminal_fixture();
    let before=serde_json::to_value(&f.executor.state).unwrap();
    assert_eq!(f.executor.state.generation,"host-1");
    assert!(f.executor.reconcile_terminal_generation(&operations).unwrap());
    assert_eq!(f.executor.state.generation,"host-2");
    for field in ["operations","threads","effects","answers"] {
        assert_eq!(serde_json::to_value(&f.executor.state).unwrap()[field],before[field]);
    }
    assert!(f.executor.needs_tool_upgrade(&operations[0].mapping.as_ref().unwrap().thread_id).unwrap());
    let mut next=f.req("new-explicit-handoff",Role::Coordinator);next.host_generation="host-2".into();
    let mapping=f.executor.start(next).unwrap();
    assert_ne!(mapping.thread_id,operations[0].mapping.as_ref().unwrap().thread_id);
    assert_eq!(f.executor.state.operations.len(),2);
    assert!(!f.executor.reconcile_terminal_generation(&operations).unwrap());
}

#[test]
fn terminal_generation_unknown_pending_partial_and_wrong_proofs_never_advance() {
    for defect in ["missing-run","active-run","unknown-write","pending","old-snapshot","receipt-unknown","receipt-wrong-generation","service-unknown","held-unresolved","unknown-answer"] {
        let (mut f,mut operations)=terminal_fixture();
        match defect {
            "missing-run"=>f.executor.runtime.recovery_snapshot.as_mut().unwrap()["runs"]=json!([]),
            "active-run"=>f.executor.runtime.recovery_snapshot.as_mut().unwrap()["runs"][0]["state"]=json!("active"),
            "unknown-write"=>f.executor.runtime.recovery_snapshot.as_mut().unwrap()["unconfirmedWrites"]=json!(1),
            "pending"=>f.executor.runtime.recovery_snapshot.as_mut().unwrap()["pendingRequests"]=json!([{"requestId":7}]),
            "old-snapshot"=>f.executor.runtime.recovery_snapshot.as_mut().unwrap()["generation"]=json!("host-1"),
            "receipt-unknown"=>f.executor.runtime.receipts.get_mut(&native_key(&rpc_id("cold-completed","turn")).unwrap()).unwrap().state="unknown".into(),
            "receipt-wrong-generation"=>f.executor.runtime.receipts.get_mut(&native_key(&rpc_id("cold-completed","turn")).unwrap()).unwrap().generation="foreign".into(),
            "service-unknown"=>operations[0].phase=crate::assistant::OperationPhase::Unknown,
            "held-unresolved"=>operations[0].input_tool_call=Some("unresolved-call".into()),
            "unknown-answer"=>{f.executor.state.answers.insert("uncertain-answer".into(),Effect{fingerprint:"x".into(),accepted:false,rejected:false});},
            _=>unreachable!(),
        }
        let before=serde_json::to_value(&f.executor.state).unwrap();
        assert!(f.executor.reconcile_terminal_generation(&operations).is_err(),"accepted {defect}");
        assert_eq!(serde_json::to_value(&f.executor.state).unwrap(),before,"mutated {defect}");
        assert!(f.executor.runtime.calls.iter().all(|(_,method,_)|method!="thread/resume"));
    }
}

#[test]
fn terminal_generation_save_failure_keeps_original_in_memory_and_on_disk() {
    let (mut f,operations)=terminal_fixture();
    let before=serde_json::to_value(&f.executor.state).unwrap();
    f.executor.db.execute_batch("CREATE TRIGGER fail_terminal_migration BEFORE UPDATE ON assistant_executor_state BEGIN SELECT RAISE(FAIL,'migration save failed'); END").unwrap();
    assert!(f.executor.reconcile_terminal_generation(&operations).is_err());
    assert_eq!(serde_json::to_value(&f.executor.state).unwrap(),before);
    let saved:String=f.executor.db.query_row("SELECT data FROM assistant_executor_state",[],|r|r.get(0)).unwrap();
    assert_eq!(serde_json::from_str::<Value>(&saved).unwrap(),before);
}

#[test]
fn terminal_generation_local_accepted_answer_is_not_authoritative_written_evidence() {
    let (mut f, operations) = terminal_fixture();
    let mapping = operations[0].mapping.as_ref().unwrap();
    f.executor.state.answers.insert(hash(&(mapping, json!(7))).unwrap(), Effect {
        fingerprint: hash(&json!({"answer":"private-choice"})).unwrap(), accepted:true, rejected:false,
    });
    let before = serde_json::to_value(&f.executor.state).unwrap();
    assert!(f.executor.reconcile_terminal_generation(&operations).is_err(), "local accepted must not authorize a generation change");
    assert_eq!(serde_json::to_value(&f.executor.state).unwrap(), before);
}

#[test]
fn terminal_generation_orphan_rejected_effect_is_not_a_verified_inventory_entry() {
    let (mut f, operations) = terminal_fixture();
    f.executor.state.effects.insert(native_key(&rpc_id("orphan", "turn")).unwrap(), Effect {
        fingerprint:"orphan".into(), accepted:false, rejected:true,
    });
    let before = serde_json::to_value(&f.executor.state).unwrap();
    assert!(f.executor.reconcile_terminal_generation(&operations).is_err(), "rejected flag must not hide an orphan effect");
    assert_eq!(serde_json::to_value(&f.executor.state).unwrap(), before);
}

#[test]
fn terminal_generation_service_inventory_must_be_complete_in_both_directions() {
    for kind in ["current-unmapped-live", "missing-mapped-record"] {
        let (mut f, mut operations) = terminal_fixture();
        let mut extra = operations[0].clone();
        extra.id="missing-native-record".into();
        if kind=="current-unmapped-live" {
            extra.mapping=None;
            extra.expected_generation="host-2".into();
            extra.phase=crate::assistant::OperationPhase::Unknown;
        } else {
            extra.mapping.as_mut().unwrap().turn_id="unowned-turn".into();
        }
        operations.push(extra);
        let before = serde_json::to_value(&f.executor.state).unwrap();
        assert!(f.executor.reconcile_terminal_generation(&operations).is_err(), "inventory gap accepted: {kind}");
        assert_eq!(serde_json::to_value(&f.executor.state).unwrap(), before);
    }
}

fn terminal_answers_fixture(count:usize)->(Fixture,Vec<crate::assistant::Operation>) {
    let (mut f,operations)=terminal_fixture();
    let mapping=operations[0].mapping.as_ref().unwrap();
    for id in 0..count {
        f.executor.state.answers.insert(hash(&(mapping,json!(id))).unwrap(),Effect {
            fingerprint:hash(&json!({"answer":id})).unwrap(),accepted:id%2==0,rejected:false,
        });
        f.executor.runtime.answer_evidence_records.push(pocket_runtime_ipc::client::AnswerEvidence {
            generation: "host-1".into(), request_id:json!(id), completed:true,
        });
    }
    f.executor.runtime.recovery_snapshot.as_mut().unwrap()["answerCount"]=json!(count);
    (f,operations)
}

#[test]
fn terminal_generation_schema2_answer_keys_prove_only_terminal_and_keep_unknown_history() {
    let (mut f,operations)=terminal_answers_fixture(5);
    let before=serde_json::to_value(&f.executor.state).unwrap();
    // Cold command tombstones have no response; their terminal identity still counts.
    for receipt in f.executor.runtime.receipts.values_mut() {receipt.response=None;}
    assert!(f.executor.reconcile_terminal_generation(&operations).unwrap());
    for field in ["operations","threads","effects","answers"] {
        assert_eq!(serde_json::to_value(&f.executor.state).unwrap()[field],before[field]);
    }
    assert!(f.executor.current_generation_ready("host-2"));
    assert_eq!(f.executor.state.terminal_generations[0]["answerReceipts"].as_object().unwrap().len(),5);
}

#[test]
fn terminal_generation_answer_namespace_identity_partial_and_races_fail_closed() {
    for fault in ["unsupported","namespace","host","page-digest","offset","total","partial","next","duplicate","id","original-generation","uncompleted","race"] {
        let (mut f,operations)=terminal_answers_fixture(2);
        f.executor.runtime.answer_evidence_fault=Some(fault);
        let before=serde_json::to_value(&f.executor.state).unwrap();
        assert!(f.executor.reconcile_terminal_generation(&operations).is_err(),"accepted answer defect {fault}");
        assert_eq!(serde_json::to_value(&f.executor.state).unwrap(),before,"mutated {fault}");
        assert!(!f.executor.current_generation_ready("host-2"));
    }
}

#[test]
fn terminal_generation_complete_multi_page_answers_and_duplicate_across_pages() {
    for duplicate in [false,true] {
        let (mut f,operations)=terminal_answers_fixture(65);
        if duplicate {f.executor.runtime.answer_evidence_fault=Some("duplicate-across");}
        let before=serde_json::to_value(&f.executor.state).unwrap();
        let result=f.executor.reconcile_terminal_generation(&operations);
        if duplicate {assert!(result.is_err());assert_eq!(serde_json::to_value(&f.executor.state).unwrap(),before);}
        else {assert!(result.unwrap());assert_eq!(f.executor.state.terminal_generations[0]["answerReceipts"].as_object().unwrap().len(),65);}
    }
}

#[test]
fn terminal_generation_owner_and_missing_effect_inventories_never_advance() {
    for fault in ["owner","effect","missing-service"] {
        let (mut f,mut operations)=terminal_fixture();
        match fault {
            "owner"=>f.executor.state.threads.values_mut().next().unwrap().current_operation=Some("orphan".into()),
            "effect"=>{f.executor.state.effects.remove(&native_key(&rpc_id("cold-completed","turn")).unwrap());},
            "missing-service"=>operations.clear(),
            _=>unreachable!(),
        }
        let before=serde_json::to_value(&f.executor.state).unwrap();
        assert!(f.executor.reconcile_terminal_generation(&operations).is_err(),"accepted {fault}");
        assert_eq!(serde_json::to_value(&f.executor.state).unwrap(),before);
    }
}
