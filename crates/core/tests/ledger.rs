use pocket_core::{directory::RuntimeId, ledger::*};
use serde_json::{json, Value};
fn runtime() -> RuntimeId {
    RuntimeId {
        computer_id: "pc".into(),
        runtime_id: "r".into(),
    }
}
fn key(id: &str) -> CommandKey {
    CommandKey {
        caller_id: "device".into(),
        command_id: id.into(),
    }
}
fn config(capacity: usize) -> LedgerConfig {
    LedgerConfig {
        capacity,
        terminal_ttl: 10,
    }
}
fn ledger(cap: usize) -> CommandLedger {
    CommandLedger::new(runtime(), config(cap), 1, 0).unwrap()
}
fn accepted() -> Status {
    Status::Accepted {
        native_result: json!({"turnId":"native"}),
    }
}

fn assert_status_snapshot(l: &CommandLedger, command_key: &CommandKey, expected: Option<&Status>) {
    let before = l.snapshot_json();
    let stored: Option<&Status> = l.status(command_key);
    assert_eq!(stored, expected);
    assert_eq!(l.status(command_key), expected);
    assert_eq!(l.snapshot_json(), before);
}

#[test]
fn status_missing_does_not_reserve_key() {
    let mut l = ledger(1);
    assert_status_snapshot(&l, &key("missing"), None);
    assert_eq!(l.begin(key("missing"), b"body", 0), Ok(Begin::Execute));
}

#[test]
fn status_submitted_preserves_snapshot() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    assert_status_snapshot(&l, &key("a"), Some(&Status::Submitted));
}

#[test]
fn status_accepted_borrows_native_result() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    l.finish(&key("a"), accepted(), 1).unwrap();
    assert_status_snapshot(&l, &key("a"), Some(&accepted()));
    assert!(std::ptr::eq(
        l.status(&key("a")).unwrap(),
        l.status(&key("a")).unwrap()
    ));
}

#[test]
fn status_rejected_preserves_reason() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    let rejected = Status::Rejected {
        reason: "denied".into(),
    };
    l.finish(&key("a"), rejected.clone(), 1).unwrap();
    assert_status_snapshot(&l, &key("a"), Some(&rejected));
}

#[test]
fn status_restored_unknown_preserves_snapshot() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    let restored =
        CommandLedger::restore_json(&l.snapshot_json(), runtime(), config(1), 1, 1).unwrap();
    assert_status_snapshot(&restored, &key("a"), Some(&Status::Unknown));
}

#[test]
fn status_does_not_expire_terminal_at_current_clock() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    l.finish(&key("a"), accepted(), 1).unwrap();
    // finish ticks time but does not expire, leaving a stored terminal at its TTL boundary.
    l.finish(&key("a"), accepted(), 11).unwrap();
    assert_status_snapshot(&l, &key("a"), Some(&accepted()));
    assert_eq!(l.begin(key("b"), b"new", 11), Ok(Begin::Execute));
    assert_status_snapshot(&l, &key("a"), None);
}
#[test]
fn same_key_body_never_executes_twice_and_preserves_native_result() {
    let mut l = ledger(2);
    assert_eq!(l.begin(key("a"), b"body", 0), Ok(Begin::Execute));
    assert_eq!(
        l.begin(key("a"), b"body", 1),
        Ok(Begin::Existing(Status::Submitted))
    );
    l.finish(&key("a"), accepted(), 2).unwrap();
    assert_eq!(
        l.begin(key("a"), b"body", 3),
        Ok(Begin::Existing(accepted()))
    );
}
#[test]
fn different_body_under_same_key_conflicts() {
    let mut l = ledger(1);
    l.begin(key("a"), b"one", 0).unwrap();
    assert_eq!(l.begin(key("a"), b"two", 0), Err(LedgerError::BodyConflict));
}
#[test]
fn caller_namespaces_command_id() {
    let mut l = ledger(2);
    l.begin(key("a"), b"one", 0).unwrap();
    assert_eq!(
        l.begin(
            CommandKey {
                caller_id: "second".into(),
                command_id: "a".into()
            },
            b"two",
            0
        ),
        Ok(Begin::Execute)
    );
}
#[test]
fn fingerprint_is_sha256_of_exact_request_bytes() {
    assert_eq!(
        CommandLedger::fingerprint(b"abc"),
        [
            0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea, 0x41, 0x41, 0x40, 0xde, 0x5d, 0xae,
            0x22, 0x23, 0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c, 0xb4, 0x10, 0xff, 0x61,
            0xf2, 0x00, 0x15, 0xad
        ]
    );
    assert_ne!(
        CommandLedger::fingerprint(b"{}"),
        CommandLedger::fingerprint(b"{ }")
    );
}
#[test]
fn snapshot_precedes_write_and_restart_submitted_is_unknown() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    let saved = l.snapshot_json();
    let v: Value = serde_json::from_str(&saved).unwrap();
    assert_eq!(v["entries"][0]["status"], "Submitted");
    let mut restored = CommandLedger::restore_json(&saved, runtime(), config(1), 1, 1).unwrap();
    assert_eq!(
        restored.begin(key("a"), b"body", 1),
        Ok(Begin::Existing(Status::Unknown))
    );
}
#[test]
fn unknown_survives_ttl_generation_and_multiple_restarts() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    l.finish(&key("a"), Status::Unknown, 1).unwrap();
    l.advance_generation(9).unwrap();
    let mut l =
        CommandLedger::restore_json(&l.snapshot_json(), runtime(), config(1), 10, 1000).unwrap();
    assert_eq!(
        l.begin(key("a"), b"body", 2000),
        Ok(Begin::Existing(Status::Unknown))
    );
    assert_eq!(l.begin(key("b"), b"body", 2000), Err(LedgerError::Full));
}
#[test]
fn submitted_and_unknown_cannot_be_evicted_for_capacity() {
    for status in [Status::Submitted, Status::Unknown] {
        let mut l = ledger(1);
        l.begin(key("a"), b"body", 0).unwrap();
        if status == Status::Unknown {
            l.finish(&key("a"), status, 0).unwrap();
        }
        l.advance_generation(2).unwrap();
        assert_eq!(l.begin(key("b"), b"new", 100), Err(LedgerError::Full));
    }
}
#[test]
fn unexpired_terminal_is_retained_and_ttl_boundary_allows_retry() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    l.finish(&key("a"), accepted(), 2).unwrap();
    assert_eq!(l.begin(key("b"), b"body", 11), Err(LedgerError::Full));
    assert_eq!(
        l.begin(key("a"), b"body", 11),
        Ok(Begin::Existing(accepted()))
    );
    assert_eq!(l.begin(key("a"), b"body", 12), Ok(Begin::Execute));
}
#[test]
fn generation_expires_only_terminal_entries() {
    let mut l = ledger(2);
    l.begin(key("a"), b"body", 0).unwrap();
    l.finish(
        &key("a"),
        Status::Rejected {
            reason: "denied".into(),
        },
        0,
    )
    .unwrap();
    l.begin(key("b"), b"body", 0).unwrap();
    l.advance_generation(2).unwrap();
    assert_eq!(l.begin(key("a"), b"new", 0), Ok(Begin::Execute));
    assert_eq!(
        l.begin(key("b"), b"body", 0),
        Ok(Begin::Existing(Status::Submitted))
    );
}
#[test]
fn invalid_transitions_and_reconciliation() {
    let mut l = ledger(1);
    assert_eq!(
        l.finish(&key("a"), accepted(), 0),
        Err(LedgerError::Missing)
    );
    l.begin(key("a"), b"body", 0).unwrap();
    assert_eq!(
        l.finish(&key("a"), Status::Submitted, 0),
        Err(LedgerError::InvalidTransition)
    );
    l.finish(&key("a"), Status::Unknown, 0).unwrap();
    l.finish(&key("a"), accepted(), 1).unwrap();
    assert_eq!(
        l.finish(
            &key("a"),
            Status::Rejected {
                reason: "no".into()
            },
            1
        ),
        Err(LedgerError::InvalidTransition)
    );
}
#[test]
fn clock_generation_config_and_identity_validation() {
    assert!(matches!(
        CommandLedger::new(runtime(), config(0), 1, 0),
        Err(LedgerError::InvalidConfig)
    ));
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 5).unwrap();
    assert_eq!(
        l.begin(key("b"), b"body", 4),
        Err(LedgerError::ClockRegression)
    );
    assert_eq!(
        l.advance_generation(0),
        Err(LedgerError::GenerationRegression)
    );
    assert_eq!(
        l.begin(key(""), b"body", 5),
        Err(LedgerError::InvalidIdentity)
    );
}
#[test]
fn restore_rejects_duplicate_keys_invalid_status_time_digest_and_version() {
    let mut l = ledger(2);
    l.begin(key("a"), b"body", 0).unwrap();
    let base: Value = serde_json::from_str(&l.snapshot_json()).unwrap();
    let mut bad = Vec::new();
    let mut v = base.clone();
    let first = v["entries"][0].clone();
    v["entries"].as_array_mut().unwrap().push(first);
    bad.push(v);
    for (field, value) in [
        ("fingerprint", json!([1])),
        ("generation", json!(2)),
        ("created_at", json!(9)),
        ("finished_at", json!(1)),
        ("status", json!("Bogus")),
    ] {
        let mut v = base.clone();
        v["entries"][0][field] = value;
        bad.push(v);
    }
    let mut v = base.clone();
    v["version"] = json!(99);
    bad.push(v);
    let mut v = base.clone();
    v["surprise"] = json!(1);
    bad.push(v);
    for v in bad {
        assert!(
            matches!(
                CommandLedger::restore_json(&v.to_string(), runtime(), config(2), 1, 10),
                Err(LedgerError::InvalidSnapshot)
            ),
            "{v}"
        );
    }
}
#[test]
fn restore_rejects_wrong_runtime_capacity_and_regressions() {
    let mut l = ledger(2);
    l.begin(key("a"), b"body", 5).unwrap();
    l.begin(key("b"), b"body", 5).unwrap();
    let s = l.snapshot_json();
    let other = RuntimeId {
        computer_id: "other".into(),
        ..runtime()
    };
    assert!(CommandLedger::restore_json(&s, other, config(2), 1, 5).is_err());
    assert!(CommandLedger::restore_json(&s, runtime(), config(1), 1, 5).is_err());
    assert!(CommandLedger::restore_json(&s, runtime(), config(2), 0, 5).is_err());
    assert!(CommandLedger::restore_json(&s, runtime(), config(2), 1, 4).is_err());
}
#[test]
fn restore_preserves_terminal_and_expires_at_original_deadline() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    l.finish(&key("a"), accepted(), 2).unwrap();
    let mut l =
        CommandLedger::restore_json(&l.snapshot_json(), runtime(), config(1), 1, 11).unwrap();
    assert_eq!(
        l.begin(key("a"), b"body", 11),
        Ok(Begin::Existing(accepted()))
    );
    assert_eq!(l.begin(key("a"), b"body", 12), Ok(Begin::Execute));
}

#[test]
fn restore_rejects_extra_fields_inside_terminal_status() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    l.finish(&key("a"), accepted(), 1).unwrap();
    let mut v: Value = serde_json::from_str(&l.snapshot_json()).unwrap();
    v["entries"][0]["status"]["Accepted"]["extra"] = json!("must reject");
    assert!(matches!(
        CommandLedger::restore_json(&v.to_string(), runtime(), config(1), 1, 1),
        Err(LedgerError::InvalidSnapshot)
    ));
}
#[test]
fn repeated_terminal_result_does_not_extend_ttl() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    l.finish(&key("a"), accepted(), 1).unwrap();
    l.finish(&key("a"), accepted(), 10).unwrap();
    assert_eq!(l.begin(key("a"), b"body", 11), Ok(Begin::Execute));
}
#[test]
fn zero_ttl_and_maximum_timestamp_have_explicit_boundaries() {
    let mut l = CommandLedger::new(
        runtime(),
        LedgerConfig {
            capacity: 1,
            terminal_ttl: 0,
        },
        1,
        u64::MAX,
    )
    .unwrap();
    l.begin(key("a"), b"body", u64::MAX).unwrap();
    l.finish(&key("a"), accepted(), u64::MAX).unwrap();
    assert_eq!(l.begin(key("a"), b"body", u64::MAX), Ok(Begin::Execute));
    let mut l = CommandLedger::new(runtime(), config(1), 1, u64::MAX).unwrap();
    l.begin(key("a"), b"body", u64::MAX).unwrap();
    l.finish(&key("a"), accepted(), u64::MAX).unwrap();
    assert_eq!(
        l.begin(key("a"), b"body", u64::MAX),
        Ok(Begin::Existing(accepted()))
    );
}
#[test]
fn restore_rejects_terminal_time_before_creation_and_missing_finish() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 5).unwrap();
    l.finish(&key("a"), accepted(), 6).unwrap();
    let base: Value = serde_json::from_str(&l.snapshot_json()).unwrap();
    for finished in [json!(4), json!(7), Value::Null] {
        let mut v = base.clone();
        v["entries"][0]["finished_at"] = finished;
        assert!(matches!(
            CommandLedger::restore_json(&v.to_string(), runtime(), config(1), 1, 8),
            Err(LedgerError::InvalidSnapshot)
        ));
    }
}

#[test]
fn restore_rejects_terminal_from_impossible_older_generation() {
    let mut l = ledger(1);
    l.begin(key("a"), b"body", 0).unwrap();
    l.finish(&key("a"), accepted(), 1).unwrap();
    let mut v: Value = serde_json::from_str(&l.snapshot_json()).unwrap();
    v["entries"][0]["generation"] = json!(0);
    assert!(matches!(
        CommandLedger::restore_json(&v.to_string(), runtime(), config(1), 1, 1),
        Err(LedgerError::InvalidSnapshot)
    ));
}
