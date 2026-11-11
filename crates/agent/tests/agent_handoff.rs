//! Pure handoff tests: no listeners, native calls, installed state or models.
pub use pocket_agent::{auth, storage};
#[path = "../src/handoff.rs"]
mod handoff;

use handoff::*;
use pocket_core::{directory::RuntimeId, journal::*, ledger::*};
use serde_json::{Value, json};
use std::collections::BTreeMap;
use std::sync::Arc;
use uuid::Uuid;

fn generation() -> Uuid {
    Uuid::from_u128(1)
}
fn params(id: u128) -> Params {
    Params {
        id: Uuid::from_u128(id),
        from_generation: generation(),
        checkpoint: None,
    }
}
fn setup() -> (tempfile::TempDir, Arc<Handoff>) {
    let dir = tempfile::tempdir().unwrap();
    let h = Handoff::open(dir.path(), generation(), true, true).unwrap();
    (dir, h)
}
fn snapshot() -> Snapshot {
    let ledger = CommandLedger::new(
        RuntimeId {
            computer_id: "pc".into(),
            runtime_id: "broker".into(),
        },
        LedgerConfig {
            capacity: 50000,
            terminal_ttl: u64::MAX,
        },
        1,
        1,
    )
    .unwrap();
    Snapshot {
        computer_id: "pc".into(),
        ledger: serde_json::from_str(&ledger.snapshot_json()).unwrap(),
        events: BTreeMap::new(),
        runtimes: BTreeMap::new(),
        pending: json!({}),
        ownership: json!([]),
        question_receipts: json!({"records":[]}),
        protected_files: BTreeMap::new(),
        blockers: Default::default(),
    }
}
fn ready(h: &Handoff, id: u128) -> (Params, Receipt) {
    let receipt = h.quiesce(&params(id), snapshot()).unwrap();
    assert_eq!(receipt.phase, Phase::Ready);
    let mut p = params(id);
    p.checkpoint = receipt.checkpoint.clone();
    (p, receipt)
}

fn runtime(lifecycle: Lifecycle, active: Option<bool>) -> RuntimeIdentity {
    RuntimeIdentity {
        runtime_id: "r".into(),
        store: "store".into(),
        connection_id: "connection".into(),
        native_process: Some(pocket_core::native_process::NativeProcess {
            pid: 17,
            created_at: 123,
        }),
        lifecycle,
        inventory_verified: true,
        active,
        host_cursor: if lifecycle == Lifecycle::ManagedHostExternal {
            Some(HostCursor {
                generation: "host-generation".into(),
                after: 5,
            })
        } else {
            None
        },
        host_process: if lifecycle == Lifecycle::ManagedHostExternal {
            Some(pocket_core::native_process::NativeProcess {
                pid: 18,
                created_at: 124,
            })
        } else {
            None
        },
        reconciliation_required: false,
    }
}
fn with_runtime(r: RuntimeIdentity) -> Snapshot {
    let mut s = snapshot();
    let journal = EventJournal::new(
        RuntimeId {
            computer_id: "pc".into(),
            runtime_id: r.runtime_id.clone(),
        },
        JournalConfig {
            max_events: 2,
            max_bytes: 8192,
        },
    )
    .unwrap();
    s.events.insert(
        r.runtime_id.clone(),
        EventArchive::capture(&journal, r.host_cursor.clone()),
    );
    s.runtimes.insert(r.runtime_id.clone(), r);
    s
}

#[test]
fn exact_wire_and_strict_params() {
    let (_dir, h) = setup();
    let caps = h.capabilities(&json!({})).unwrap();
    assert_eq!(
        HandoffError::new("write_fenced").wire()["code"],
        "write_fenced"
    );
    assert_eq!(
        caps,
        json!({"protocol":1,"supported":true,"generation":generation(),"owner":"pocket-agent","methods":METHODS,"checkpoint":"sha256","shutdown":"after_reply_flush","writeFence":true,"preservesIdentity":true})
    );
    assert!(h.capabilities(&Value::Null).is_err());
    let value = serde_json::to_value(params(2)).unwrap();
    assert_eq!(Params::parse(&value).unwrap(), params(2));
    for value in [
        json!({}),
        json!({"id":Uuid::new_v4(),"fromGeneration":generation()}),
        json!({"id":"BAD","fromGeneration":generation(),"checkpoint":null}),
        json!({"id":Uuid::new_v4().simple().to_string(),"fromGeneration":generation(),"checkpoint":null}),
        json!({"id":Uuid::new_v4(),"fromGeneration":generation(),"checkpoint":null,"force":true}),
    ] {
        assert_eq!(Params::parse(&value).unwrap_err().code, "invalid_params");
    }
    let (_, r) = ready(&h, 2);
    assert_eq!(
        serde_json::to_value(&r).unwrap().as_object().unwrap().len(),
        12
    );
}

#[test]
fn quiesce_fences_and_status_finishes_original_write_without_replay() {
    let (_dir, h) = setup();
    let permit = h.admit_write().unwrap();
    let token = permit.token();
    let r = h.quiesce(&params(2), snapshot()).unwrap();
    assert_eq!(r.phase, Phase::Draining);
    assert!(!r.safe_to_exit);
    assert!(!h.remote_enabled());
    assert!(h.admit_write().is_err());
    drop(permit); // Dropping a sent/abandoned write is never a receipt.
    assert_eq!(h.status(&params(2)).unwrap().unconfirmed_writes, 1);
    h.confirm_write(token).unwrap();
    let r = h.quiesce(&params(2), snapshot()).unwrap();
    assert_eq!(r.phase, Phase::Ready);
    assert!(r.safe_to_exit);
    assert_eq!(h.quiesce(&params(2), snapshot()).unwrap(), r);
}

#[test]
fn release_requires_exact_checkpoint_and_reply_flush_before_shutdown() {
    let (_dir, h) = setup();
    assert!(h.release(&params(2), snapshot()).is_err());
    let (p, _) = ready(&h, 2);
    let mut changed = p.clone();
    changed.checkpoint.as_mut().unwrap().hash = "0".repeat(64);
    assert_eq!(
        h.release(&changed, snapshot()).unwrap_err().code,
        "checkpoint_mismatch"
    );
    let r = h.release(&p, snapshot()).unwrap();
    assert_eq!(r.phase, Phase::ReleasePending);
    assert!(!*h.shutdown_receiver().borrow());
    assert_eq!(h.release(&p, snapshot()).unwrap(), r);
    assert!(h.cancel(&p).is_err());
    assert!(h.release_flushed(&changed).is_err());
    assert!(!*h.shutdown_receiver().borrow());
    h.release_flushed(&p).unwrap();
    assert!(*h.shutdown_receiver().borrow());
    h.release_flushed(&p).unwrap();
    assert_eq!(h.status(&params(2)).unwrap().phase, Phase::Released);
}

#[test]
fn cancelled_uuid_cannot_reopen_or_release_and_restores_prior_remote_gate() {
    for remote in [true, false] {
        let dir = tempfile::tempdir().unwrap();
        let h = Handoff::open(dir.path(), generation(), true, remote).unwrap();
        ready(&h, 2);
        let r = h.cancel(&params(2)).unwrap();
        assert_eq!(r.phase, Phase::Cancelled);
        assert!(!r.safe_to_exit);
        assert_eq!(h.remote_enabled(), remote);
        assert!(h.accepting_writes());
        assert_eq!(h.cancel(&params(2)).unwrap(), r);
        assert!(h.quiesce(&params(2), snapshot()).is_err());
        assert!(h.release(&params(2), snapshot()).is_err());
        ready(&h, 3);
        assert_eq!(h.status(&params(2)).unwrap().phase, Phase::Cancelled);
        assert!(!h.accepting_writes());
        assert!(
            h.cancel(&Params {
                checkpoint: r.checkpoint,
                ..params(2)
            })
            .is_ok()
        );
        assert!(!h.accepting_writes());
    }
}

#[test]
fn owner_generation_and_concurrent_operation_conflicts_are_fail_closed() {
    let (_dir, h) = setup();
    let stale = Params {
        from_generation: Uuid::from_u128(99),
        ..params(2)
    };
    assert_eq!(
        h.quiesce(&stale, snapshot()).unwrap_err().code,
        "stale_generation"
    );
    ready(&h, 2);
    assert_eq!(
        h.quiesce(&params(3), snapshot()).unwrap_err().code,
        "handoff_conflict"
    );
    let dir = tempfile::tempdir().unwrap();
    let external = Handoff::open(dir.path(), generation(), false, true).unwrap();
    assert_eq!(
        external.capabilities(&json!({})).unwrap()["supported"],
        false
    );
    assert_eq!(
        external.quiesce(&params(2), snapshot()).unwrap_err().code,
        "unsupported"
    );
    assert!(external.accepting_writes());
}

#[test]
fn submitted_and_unknown_ledger_writes_block_until_real_terminal_evidence() {
    for status in [Status::Submitted, Status::Unknown] {
        let (_dir, h) = setup();
        let mut s = snapshot();
        let mut ledger = CommandLedger::restore_json(
            &s.ledger.to_string(),
            RuntimeId {
                computer_id: "pc".into(),
                runtime_id: "broker".into(),
            },
            LedgerConfig {
                capacity: 50000,
                terminal_ttl: u64::MAX,
            },
            1,
            1,
        )
        .unwrap();
        let key = CommandKey {
            caller_id: "phone".into(),
            command_id: "ORIGINAL".into(),
        };
        ledger.begin(key.clone(), b"original body", 1).unwrap();
        if status == Status::Unknown {
            ledger.finish(&key, status.clone(), 1).unwrap();
        }
        s.ledger = serde_json::from_str(&ledger.snapshot_json()).unwrap();
        let r = h.quiesce(&params(2), s.clone()).unwrap();
        assert!(!r.safe_to_exit);
        assert!(r.blockers.contains("unconfirmed_writes"));
        ledger
            .finish(
                &key,
                Status::Accepted {
                    native_result: json!({"originalReceipt":17}),
                },
                1,
            )
            .unwrap();
        s.ledger = serde_json::from_str(&ledger.snapshot_json()).unwrap();
        let r = h.quiesce(&params(2), s.clone()).unwrap();
        let image = CheckpointStore::new(h.state_dir())
            .verify(r.checkpoint.as_ref().unwrap(), "pc")
            .unwrap();
        assert_eq!(image.snapshot.ledger, s.ledger);
    }
}

#[test]
fn event_archive_retention_and_checkpoint_round_trip() {
    let (_dir, h) = setup();
    let mut s = with_runtime(runtime(Lifecycle::DesktopExternal, Some(true)));
    let mut journal = EventJournal::new(
        RuntimeId {
            computer_id: "pc".into(),
            runtime_id: "r".into(),
        },
        JournalConfig {
            max_events: 2,
            max_bytes: 8192,
        },
    )
    .unwrap();
    for n in 0..5 {
        journal
            .append(NativeEvent {
                native_thread_id: Some("t".into()),
                native_turn_id: None,
                native_item_id: None,
                payload: json!({"n":n}),
            })
            .unwrap();
    }
    let archive = EventArchive::capture(&journal, None);
    assert_eq!(archive.floor, 3);
    assert_eq!(archive.events.len(), 2);
    s.events.insert("r".into(), archive);
    let r = h.quiesce(&params(2), s.clone()).unwrap();
    let image = CheckpointStore::new(h.state_dir())
        .verify(r.checkpoint.as_ref().unwrap(), "pc")
        .unwrap();
    assert_eq!(image.snapshot, s);
    let mut old = journal.cursor();
    old.sequence = 2;
    assert_eq!(
        image.snapshot.events["r"].replay(&old),
        Replay::SnapshotRequired
    );
    old.sequence = 3;
    assert!(
        matches!(image.snapshot.events["r"].replay(&old),Replay::Events(events) if events.len()==2)
    );
    assert!(
        CheckpointStore::new(h.state_dir())
            .verify(r.checkpoint.as_ref().unwrap(), "other")
            .is_err()
    );
}

#[test]
fn every_owned_lifecycle_requires_audited_identity_and_legacy_activity() {
    for lifecycle in [
        Lifecycle::DesktopExternal,
        Lifecycle::ManagedHostExternal,
        Lifecycle::CliDaemonExternal,
        Lifecycle::LegacyCliAgentOwned,
        Lifecycle::Unknown,
    ] {
        for active in [Some(true), Some(false), None] {
            let (_dir, h) = setup();
            let r = h
                .quiesce(&params(2), with_runtime(runtime(lifecycle, active)))
                .unwrap();
            let expected = lifecycle != Lifecycle::Unknown
                && (lifecycle != Lifecycle::LegacyCliAgentOwned || active == Some(false));
            assert_eq!(r.safe_to_exit, expected, "{lifecycle:?} {active:?}");
            assert_eq!(r.native_tasks_preserved, expected);
        }
    }
    for defect in 0..4 {
        let (_dir, h) = setup();
        let mut r = runtime(Lifecycle::ManagedHostExternal, Some(true));
        match defect {
            0 => r.native_process = None,
            1 => r.inventory_verified = false,
            2 => r.host_cursor = None,
            _ => r.reconciliation_required = true,
        }
        let r = h.quiesce(&params(2), with_runtime(r)).unwrap();
        assert!(!r.safe_to_exit);
        assert!(!r.native_tasks_preserved);
    }
}

#[test]
fn release_rechecks_runtime_identity_after_ready() {
    let (_dir, h) = setup();
    let s = with_runtime(runtime(Lifecycle::DesktopExternal, Some(true)));
    let r = h.quiesce(&params(2), s.clone()).unwrap();
    let p = Params {
        checkpoint: r.checkpoint,
        ..params(2)
    };
    let mut replaced = s.clone();
    replaced
        .runtimes
        .get_mut("r")
        .unwrap()
        .native_process
        .as_mut()
        .unwrap()
        .created_at += 1;
    assert!(
        h.release(&p, replaced).is_err(),
        "PID reuse must invalidate ready proof"
    );
    assert!(!*h.shutdown_receiver().borrow());
    h.cancel(&params(2)).unwrap();
}

#[test]
fn failed_cancel_never_opens_gates_and_retry_recovers() {
    let (dir, h) = setup();
    ready(&h, 2);
    let path = dir.path().join("handoff/state.json");
    let backup = dir.path().join("handoff/state.saved");
    std::fs::rename(&path, &backup).unwrap();
    std::fs::create_dir(&path).unwrap();
    assert_eq!(h.cancel(&params(2)).unwrap_err().code, "unavailable");
    assert!(!h.accepting_writes());
    assert!(!h.remote_enabled());
    assert_eq!(h.status(&params(2)).unwrap().phase, Phase::Ready);
    std::fs::remove_dir(&path).unwrap();
    std::fs::rename(&backup, &path).unwrap();
    assert_eq!(h.cancel(&params(2)).unwrap().phase, Phase::Cancelled);
    assert!(h.accepting_writes());
    assert!(h.remote_enabled());
}

#[test]
fn checkpoint_or_state_failure_remains_fenced_and_can_progress_again() {
    let (dir, h) = setup();
    h.begin_quiesce(&params(2)).unwrap();
    let path = dir.path().join("handoff/state.json");
    let backup = dir.path().join("handoff/state.saved");
    std::fs::rename(&path, &backup).unwrap();
    std::fs::create_dir(&path).unwrap();
    let failed = h.progress(&params(2), snapshot()).unwrap();
    assert_eq!(failed.phase, Phase::Failed);
    assert!(!failed.safe_to_exit);
    assert!(failed.checkpoint.is_none());
    assert!(!h.accepting_writes());
    assert!(!h.remote_enabled());
    std::fs::remove_dir(&path).unwrap();
    std::fs::rename(&backup, &path).unwrap();
    let r = h.quiesce(&params(2), snapshot()).unwrap();
    assert_eq!(r.phase, Phase::Ready);
}

#[test]
fn checkpoint_hash_generation_summary_and_protected_state_are_checked() {
    let (dir, h) = setup();
    std::fs::write(dir.path().join("identity.json"), b"DO NOT CHANGE").unwrap();
    let store = CheckpointStore::new(dir.path());
    let mut s = snapshot();
    s.protected_files.insert(
        "identity.json".into(),
        store.protected_hash("identity.json").unwrap(),
    );
    let r = h.quiesce(&params(2), s).unwrap();
    let cp = r.checkpoint.unwrap();
    let path = store.path(&cp);
    let original = std::fs::read(&path).unwrap();
    let mut wrong = cp.clone();
    wrong.generation = Uuid::from_u128(5);
    assert!(store.verify(&wrong, "pc").is_err());
    let mut image: Value = serde_json::from_slice(&original).unwrap();
    image["summaryHash"] = json!("a".repeat(64));
    let bytes = serde_json::to_vec(&image).unwrap();
    std::fs::write(&path, &bytes).unwrap();
    use sha2::Digest;
    wrong = cp.clone();
    wrong.hash = hex::encode(sha2::Sha256::digest(&bytes));
    assert!(store.verify(&cp, "pc").is_err());
    assert!(store.verify(&wrong, "pc").is_err());
    std::fs::write(&path, &original).unwrap();
    std::fs::write(dir.path().join("identity.json"), b"CHANGED").unwrap();
    assert!(store.verify(&cp, "pc").is_err());
}

#[test]
fn successor_checks_generation_hash_native_host_identity_and_original_ledger() {
    let (dir, h) = setup();
    let s = with_runtime(runtime(Lifecycle::ManagedHostExternal, Some(true)));
    let r = h.quiesce(&params(2), s.clone()).unwrap();
    let p = Params {
        checkpoint: r.checkpoint,
        ..params(2)
    };
    let release = h.release(&p, s.clone()).unwrap();
    h.release_flushed(&p).unwrap();
    let cp = release.checkpoint.unwrap();
    assert!(Handoff::open(dir.path(), Uuid::from_u128(6), true, true).is_err());
    let mut current = s.clone();
    current.runtimes.get_mut("r").unwrap().connection_id = "new-connection".into();
    current
        .runtimes
        .get_mut("r")
        .unwrap()
        .host_cursor
        .as_mut()
        .unwrap()
        .after += 1;
    current.events.get_mut("r").unwrap().host_cursor = current.runtimes["r"].host_cursor.clone();
    current.ledger["last_now"] = json!(2); // Native broker restore advances time.
    let next =
        Handoff::open_successor(dir.path(), Uuid::from_u128(6), true, true, &cp, &current).unwrap();
    assert!(next.accepting_writes());
    for defect in 0..5 {
        let mut current = s.clone();
        match defect {
            0 => {
                current
                    .runtimes
                    .get_mut("r")
                    .unwrap()
                    .native_process
                    .as_mut()
                    .unwrap()
                    .pid += 1
            }
            1 => {
                current
                    .runtimes
                    .get_mut("r")
                    .unwrap()
                    .host_cursor
                    .as_mut()
                    .unwrap()
                    .generation = "REPLACED".into()
            }
            2 => {
                current
                    .runtimes
                    .get_mut("r")
                    .unwrap()
                    .host_cursor
                    .as_mut()
                    .unwrap()
                    .after = 0
            }
            3 => current.ledger["generation"] = json!(2),
            _ => current.runtimes.clear(),
        }
        assert!(s.verify_successor(&current).is_err());
    }
}

#[test]
fn successor_restores_released_effective_remote_gate_instead_of_startup_preference() {
    for prior_remote in [false, true] {
        let (dir, h) = setup();
        h.apply_remote_preference(prior_remote, |_| {});
        let (p, _) = ready(&h, 2);
        let released = h.release(&p, snapshot()).unwrap();
        h.release_flushed(&p).unwrap();
        let cp = released.checkpoint.unwrap();
        let next =
            Handoff::open_fenced(dir.path(), Uuid::from_u128(6), true, !prior_remote, &cp).unwrap();
        assert!(!next.accepting_writes());
        next.complete_successor(&cp, &snapshot()).unwrap();
        assert_eq!(
            next.remote_enabled(),
            prior_remote,
            "restore the released effective gate"
        );
    }
}

#[test]
fn exhaustive_six_action_schedules_and_duplicate_callbacks() {
    fn permutations(prefix: &mut Vec<u8>, left: &mut Vec<u8>, output: &mut Vec<Vec<u8>>) {
        if left.is_empty() {
            output.push(prefix.clone());
            return;
        }
        for i in 0..left.len() {
            let v = left.remove(i);
            prefix.push(v);
            permutations(prefix, left, output);
            prefix.pop();
            left.insert(i, v);
        }
    }
    let mut schedules = Vec::new();
    permutations(&mut vec![], &mut vec![0, 1, 2, 3, 4, 5], &mut schedules);
    assert_eq!(schedules.len(), 720);
    for schedule in schedules {
        let (_dir, h) = setup();
        let mut permit = None;
        let mut release_params = None;
        for action in &schedule {
            match action {
                0 => {
                    permit = h.admit_write().ok();
                }
                1 => {
                    if let Ok(r) = h.quiesce(&params(2), snapshot()) {
                        if r.phase == Phase::Ready {
                            release_params = Some(Params {
                                checkpoint: r.checkpoint,
                                ..params(2)
                            });
                        }
                    }
                }
                2 => {
                    if let Some(p) = permit.take() {
                        p.confirm().unwrap();
                    }
                    if h.status(&params(2))
                        .is_ok_and(|r| matches!(r.phase, Phase::Draining | Phase::Failed))
                    {
                        if let Ok(r) = h.progress(&params(2), snapshot()) {
                            if r.phase == Phase::Ready {
                                release_params = Some(Params {
                                    checkpoint: r.checkpoint,
                                    ..params(2)
                                });
                            }
                        }
                    }
                }
                3 => {
                    let _ = h.cancel(&params(2));
                }
                4 => {
                    if let Some(p) = &release_params {
                        let _ = h.release(p, snapshot());
                    }
                }
                5 => {
                    if let Some(p) = &release_params {
                        let _ = h.release_flushed(p);
                    }
                }
                _ => unreachable!(),
            }
            if *h.shutdown_receiver().borrow() {
                assert!(!h.accepting_writes(), "{schedule:?}");
                assert!(permit.is_none(), "{schedule:?}");
            }
            if let Ok(r) = h.status(&params(2)) {
                if r.safe_to_exit {
                    assert!(!h.accepting_writes(), "{schedule:?}");
                    assert_eq!(r.unconfirmed_writes, 0, "{schedule:?}");
                    assert!(r.checkpoint.is_some());
                }
                if r.phase == Phase::Released {
                    let p = release_params.as_ref().unwrap();
                    h.release_flushed(p).unwrap();
                    h.release_flushed(p).unwrap();
                    assert!(h.cancel(p).is_err());
                }
            }
        }
    }
}

#[test]
fn real_concurrent_quiesce_write_and_cancel_are_linearizable() {
    for _ in 0..16 {
        let (_dir, h) = setup();
        let gate = Arc::new(std::sync::Barrier::new(3));
        let q = std::thread::spawn({
            let h = h.clone();
            let gate = gate.clone();
            move || {
                gate.wait();
                h.quiesce(&params(2), snapshot())
            }
        });
        let w = std::thread::spawn({
            let h = h.clone();
            let gate = gate.clone();
            move || {
                gate.wait();
                h.admit_write()
            }
        });
        gate.wait();
        let q = q.join().unwrap().unwrap();
        let w = w.join().unwrap();
        match w {
            Ok(p) => {
                assert_eq!(q.phase, Phase::Draining);
                p.confirm().unwrap();
            }
            Err(e) => {
                assert_eq!(e.code, "write_fenced");
                assert_eq!(q.phase, Phase::Ready);
            }
        }
        let gate = Arc::new(std::sync::Barrier::new(3));
        let q = std::thread::spawn({
            let h = h.clone();
            let gate = gate.clone();
            move || {
                gate.wait();
                h.quiesce(&params(2), snapshot())
            }
        });
        let c = std::thread::spawn({
            let h = h.clone();
            let gate = gate.clone();
            move || {
                gate.wait();
                h.cancel(&params(2))
            }
        });
        gate.wait();
        let _ = q.join().unwrap();
        c.join().unwrap().unwrap();
        assert!(h.accepting_writes());
        assert!(h.remote_enabled());
        assert!(!*h.shutdown_receiver().borrow());
    }
}

#[test]
fn unconfirmed_question_or_pending_response_blocks_even_with_terminal_ledger() {
    for phase in ["dispatching", "forwarded", "awaiting_confirmation"] {
        let (_dir, h) = setup();
        let mut s = snapshot();
        s.question_receipts = json!({"records":[{"phase":phase}]});
        let r = h.quiesce(&params(2), s).unwrap();
        assert!(!r.safe_to_exit);
        assert!(r.blockers.contains("unconfirmed_writes"));
        let mut s = snapshot();
        s.question_receipts = json!({"records":[{"phase":"confirmed"}]});
        assert!(h.quiesce(&params(2), s).unwrap().safe_to_exit);
    }
    let (_dir, h) = setup();
    let mut s = snapshot();
    s.pending = json!({"p":{"command":{"caller_id":"phone","command_id":"original"}}});
    assert!(!h.quiesce(&params(2), s).unwrap().safe_to_exit);
}

#[test]
fn flush_rechecks_final_checkpoint_and_failure_never_notifies() {
    let (dir, h) = setup();
    let (p, _) = ready(&h, 2);
    let r = h.release(&p, snapshot()).unwrap();
    let store = CheckpointStore::new(dir.path());
    let path = store.path(r.checkpoint.as_ref().unwrap());
    let bytes = std::fs::read(&path).unwrap();
    std::fs::write(&path, b"corrupted after response").unwrap();
    assert!(h.release_flushed(&p).is_err());
    assert!(!*h.shutdown_receiver().borrow());
    assert!(!h.accepting_writes());
    std::fs::write(&path, &bytes).unwrap();
    h.release_flushed(&p).unwrap();
    assert!(*h.shutdown_receiver().borrow());
}

#[test]
fn recovery_never_infers_flush_and_successor_adoption_is_durable() {
    let (dir, h) = setup();
    let (p, _) = ready(&h, 2);
    let r = h.release(&p, snapshot()).unwrap();
    let recovering = Handoff::open(dir.path(), generation(), true, true).unwrap();
    assert!(!recovering.accepting_writes());
    assert!(!*recovering.shutdown_receiver().borrow());
    assert_eq!(
        recovering.status(&params(2)).unwrap().phase,
        Phase::ReleasePending
    );
    recovering.release_flushed(&p).unwrap();
    let new_generation = Uuid::from_u128(6);
    let _adopted = Handoff::open_successor(
        dir.path(),
        new_generation,
        true,
        true,
        r.checkpoint.as_ref().unwrap(),
        &snapshot(),
    )
    .unwrap();
    // Once an upgrade has been adopted, normal subsequent restarts don't
    // re-adopt an obsolete checkpoint after the new process has accepted writes.
    assert!(
        Handoff::open(dir.path(), Uuid::from_u128(7), true, true)
            .unwrap()
            .accepting_writes()
    );
}

#[test]
fn prior_generation_ids_cannot_shutdown_successor_even_with_rewritten_generation() {
    let (dir, h) = setup();
    let (p, _) = ready(&h, 2);
    let r = h.release(&p, snapshot()).unwrap();
    h.release_flushed(&p).unwrap();
    let next_gen = Uuid::from_u128(6);
    let next = Handoff::open_successor(
        dir.path(),
        next_gen,
        true,
        true,
        r.checkpoint.as_ref().unwrap(),
        &snapshot(),
    )
    .unwrap();
    let forged = Params {
        from_generation: next_gen,
        ..p.clone()
    };
    assert!(next.release_flushed(&forged).is_err());
    assert!(!*next.shutdown_receiver().borrow());
    assert!(
        next.status(&Params {
            checkpoint: None,
            ..forged.clone()
        })
        .is_err()
    );
    assert!(
        next.cancel(&Params {
            checkpoint: None,
            ..forged
        })
        .is_err()
    );
    assert!(next.accepting_writes());
}

#[test]
fn remote_gate_sync_host_registry_and_duplicate_shutdown_notification() {
    let (dir, h) = setup();
    h.register_host_descriptor("r", &dir.path().join("host.json"))
        .unwrap();
    assert_eq!(h.host_descriptor("r"), Some(dir.path().join("host.json")));
    let mut enabled = true;
    h.sync_remote_gate(|v| enabled = v);
    assert!(enabled);
    let (p, _) = ready(&h, 2);
    h.sync_remote_gate(|v| enabled = v);
    assert!(!enabled);
    assert!(
        h.register_host_descriptor("r", &dir.path().join("changed.json"))
            .is_err()
    );
    h.release(&p, snapshot()).unwrap();
    let mut shutdown = h.shutdown_receiver();
    h.release_flushed(&p).unwrap();
    assert!(*shutdown.borrow_and_update());
    h.release_flushed(&p).unwrap();
    assert!(!shutdown.has_changed().unwrap());
}

#[test]
fn startup_fence_verification_adoption_and_rollback_reuse_same_release_file() {
    let (dir, h) = setup();
    let (p, _) = ready(&h, 2);
    let release = h.release(&p, snapshot()).unwrap();
    h.release_flushed(&p).unwrap();
    let cp = release.checkpoint.unwrap();
    assert_eq!(
        Handoff::recovery_checkpoint(dir.path()).unwrap(),
        Some(cp.clone())
    );
    let boot = Handoff::open_fenced(dir.path(), Uuid::from_u128(6), true, true, &cp).unwrap();
    assert!(!boot.accepting_writes());
    assert!(!boot.remote_enabled());
    assert!(boot.admit_write().is_err());
    boot.register_host_descriptor("r", &dir.path().join("host.json"))
        .unwrap();
    let mut bad = snapshot();
    bad.computer_id = "REPLACED".into();
    assert!(boot.complete_successor(&cp, &bad).is_err());
    assert!(!boot.accepting_writes());
    boot.complete_successor(&cp, &snapshot()).unwrap();
    assert!(boot.accepting_writes());
    assert!(Handoff::recovery_checkpoint(dir.path()).unwrap().is_none());
    assert!(boot.recovery_snapshot().is_some());
    let rollback = Handoff::open_fenced(dir.path(), Uuid::from_u128(7), true, true, &cp).unwrap();
    rollback.complete_successor(&cp, &snapshot()).unwrap();
    assert!(rollback.accepting_writes());
    assert!(CheckpointStore::new(dir.path()).verify(&cp, "pc").is_ok());
    assert!(
        Handoff::open(dir.path(), Uuid::from_u128(8), true, true)
            .unwrap()
            .accepting_writes()
    );
}

#[test]
fn startup_environment_contract_is_strict_without_changing_process_environment() {
    let (_dir, h) = setup();
    let (p, _) = ready(&h, 2);
    let cp = p.checkpoint.unwrap();
    let text = serde_json::to_string(&cp).unwrap();
    assert!(StartupRequest::parse(None, None).unwrap().is_none());
    assert_eq!(
        StartupRequest::parse(Some(&text), None)
            .unwrap()
            .unwrap()
            .mode,
        StartupMode::Successor
    );
    assert_eq!(
        StartupRequest::parse(Some(&text), Some("rollback"))
            .unwrap()
            .unwrap()
            .checkpoint,
        cp
    );
    for (cp, mode) in [
        (None, Some("rollback")),
        (Some("invalid"), None),
        (Some(text.as_str()), Some("force")),
        (Some("{}"), None),
    ] {
        assert!(StartupRequest::parse(cp, mode).is_err());
    }
    let mut extra: Value = serde_json::from_str(&text).unwrap();
    extra["path"] = json!("external");
    assert!(StartupRequest::parse(Some(&extra.to_string()), None).is_err());
}

#[test]
fn remote_preference_update_is_atomic_with_fence_and_preserves_user_stop() {
    for enabled in [false, true] {
        let (_dir, h) = setup();
        let flag = Arc::new(std::sync::atomic::AtomicBool::new(true));
        h.apply_remote_preference(enabled, |v| {
            flag.store(v, std::sync::atomic::Ordering::SeqCst)
        });
        assert_eq!(h.remote_enabled(), enabled);
        let gate = Arc::new(std::sync::Barrier::new(3));
        let q = std::thread::spawn({
            let h = h.clone();
            let gate = gate.clone();
            let flag = flag.clone();
            move || {
                gate.wait();
                h.begin_quiesce(&params(2)).unwrap();
                h.sync_remote_gate(|v| flag.store(v, std::sync::atomic::Ordering::SeqCst));
            }
        });
        let poll = std::thread::spawn({
            let h = h.clone();
            let gate = gate.clone();
            let flag = flag.clone();
            move || {
                gate.wait();
                h.apply_remote_preference(enabled, |v| {
                    flag.store(v, std::sync::atomic::Ordering::SeqCst)
                });
            }
        });
        gate.wait();
        q.join().unwrap();
        poll.join().unwrap();
        assert!(!flag.load(std::sync::atomic::Ordering::SeqCst));
        for _ in 0..4 {
            h.apply_remote_preference(true, |v| flag.store(v, std::sync::atomic::Ordering::SeqCst));
        }
        assert!(!flag.load(std::sync::atomic::Ordering::SeqCst));
        h.cancel(&params(2)).unwrap();
        h.sync_remote_gate(|v| flag.store(v, std::sync::atomic::Ordering::SeqCst));
        assert_eq!(flag.load(std::sync::atomic::Ordering::SeqCst), enabled);
    }
}

#[test]
fn concurrent_duplicate_and_conflicting_quiesces_have_one_checkpoint_owner() {
    for same in [true, false] {
        let (_dir, h) = setup();
        let gate = Arc::new(std::sync::Barrier::new(3));
        let a = std::thread::spawn({
            let h = h.clone();
            let gate = gate.clone();
            move || {
                gate.wait();
                h.quiesce(&params(2), snapshot())
            }
        });
        let b = std::thread::spawn({
            let h = h.clone();
            let gate = gate.clone();
            move || {
                gate.wait();
                h.quiesce(&params(if same { 2 } else { 3 }), snapshot())
            }
        });
        gate.wait();
        let a = a.join().unwrap();
        let b = b.join().unwrap();
        if same {
            assert_eq!(a.unwrap(), b.unwrap());
        } else {
            assert_ne!(a.is_ok(), b.is_ok());
        }
        assert!(!h.accepting_writes());
        assert!(!*h.shutdown_receiver().borrow());
    }
}

#[test]
fn concurrent_release_cancel_and_flush_cannot_reopen_a_committed_shutdown() {
    for _ in 0..4 {
        let (_dir, h) = setup();
        let (p, _) = ready(&h, 2);
        let gate = Arc::new(std::sync::Barrier::new(3));
        let r = std::thread::spawn({
            let h = h.clone();
            let p = p.clone();
            let gate = gate.clone();
            move || {
                gate.wait();
                h.release(&p, snapshot())
            }
        });
        let c = std::thread::spawn({
            let h = h.clone();
            let p = p.clone();
            let gate = gate.clone();
            move || {
                gate.wait();
                h.cancel(&p)
            }
        });
        gate.wait();
        let r = r.join().unwrap();
        let c = c.join().unwrap();
        assert_ne!(r.is_ok(), c.is_ok());
        if r.is_ok() {
            assert!(!h.accepting_writes());
            assert!(!*h.shutdown_receiver().borrow());
            let gate = Arc::new(std::sync::Barrier::new(3));
            let f = std::thread::spawn({
                let h = h.clone();
                let p = p.clone();
                let gate = gate.clone();
                move || {
                    gate.wait();
                    h.release_flushed(&p)
                }
            });
            let c = std::thread::spawn({
                let h = h.clone();
                let p = p.clone();
                let gate = gate.clone();
                move || {
                    gate.wait();
                    h.cancel(&p)
                }
            });
            gate.wait();
            f.join().unwrap().unwrap();
            assert!(c.join().unwrap().is_err());
            assert!(!h.accepting_writes());
            assert!(*h.shutdown_receiver().borrow());
        } else {
            assert!(h.accepting_writes());
            assert!(!*h.shutdown_receiver().borrow());
        }
    }
}

#[test]
fn snapshot_rejects_omitted_runtime_events_and_wrong_host_cursor() {
    let mut s = with_runtime(runtime(Lifecycle::ManagedHostExternal, Some(true)));
    s.events.clear();
    assert!(s.validate().is_err());
    let mut s = with_runtime(runtime(Lifecycle::ManagedHostExternal, Some(true)));
    s.events
        .get_mut("r")
        .unwrap()
        .host_cursor
        .as_mut()
        .unwrap()
        .after += 1;
    assert!(s.validate().is_err());
}

#[cfg(windows)]
#[test]
fn checkpoint_storage_rejects_reparse_points_before_writing() {
    let dir = tempfile::tempdir().unwrap();
    let outside = tempfile::tempdir().unwrap();
    let link = dir.path().join("handoff");
    // A junction is available without symlink privileges on Windows.
    let status = std::process::Command::new("cmd")
        .args(["/c", "mklink", "/J"])
        .arg(&link)
        .arg(outside.path())
        .output()
        .unwrap();
    assert!(status.status.success());
    assert!(Handoff::open(dir.path(), generation(), true, true).is_err());
    let s = snapshot();
    assert!(
        CheckpointStore::new(dir.path())
            .save(generation(), s)
            .is_err()
    );
    assert!(std::fs::read_dir(outside.path()).unwrap().next().is_none());
    // Remove only this test's nonrecursive junction. The outside fixture lives on.
    std::fs::remove_dir(&link).unwrap();
}
