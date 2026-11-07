use pocket_core::{directory::RuntimeId, journal::*};
use serde_json::json;
fn runtime() -> RuntimeId {
    RuntimeId {
        computer_id: "pc".into(),
        runtime_id: "r".into(),
    }
}
fn event(payload: serde_json::Value) -> NativeEvent {
    NativeEvent {
        native_thread_id: Some("thread|原生".into()),
        native_turn_id: Some("turn/native".into()),
        native_item_id: Some("item:7".into()),
        payload,
    }
}
fn journal(count: usize, bytes: usize) -> EventJournal {
    EventJournal::new(
        runtime(),
        JournalConfig {
            max_events: count,
            max_bytes: bytes,
        },
    )
    .unwrap()
}
#[test]
fn fresh_epochs_differ_and_empty_replay_works() {
    let a = journal(2, 4096);
    let b = journal(2, 4096);
    assert_ne!(a.cursor().epoch, b.cursor().epoch);
    assert_eq!(a.replay(&a.cursor()), Replay::Events(vec![]));
}
#[test]
fn replay_preserves_native_identity_and_raw_payload() {
    let mut j = journal(2, 4096);
    let c = j.cursor();
    let e = event(json!({"unknown":{"x":[1,null,"字"]}}));
    j.append(e.clone()).unwrap();
    let Replay::Events(events) = j.replay(&c) else {
        panic!()
    };
    assert_eq!(events[0].event, e);
    assert_eq!(events[0].cursor.sequence, 1);
}
#[test]
fn count_eviction_demands_snapshot_for_gap_but_replays_boundary() {
    let mut j = journal(1, 4096);
    let start = j.cursor();
    j.append(event(json!(1))).unwrap();
    let first = j.cursor();
    j.append(event(json!(2))).unwrap();
    assert_eq!(j.replay(&start), Replay::SnapshotRequired);
    assert!(matches!(j.replay(&first),Replay::Events(v) if v.len()==1));
}
#[test]
fn wrong_runtime_epoch_and_future_cursor_require_snapshot() {
    let j = journal(2, 4096);
    let c = j.cursor();
    let mut wrong = c.clone();
    wrong.runtime.computer_id = "other".into();
    assert_eq!(j.replay(&wrong), Replay::SnapshotRequired);
    wrong = c.clone();
    wrong.runtime.runtime_id = "other".into();
    assert_eq!(j.replay(&wrong), Replay::SnapshotRequired);
    wrong = c.clone();
    wrong.epoch = uuid::Uuid::new_v4();
    assert_eq!(j.replay(&wrong), Replay::SnapshotRequired);
    wrong = c;
    wrong.sequence = 1;
    assert_eq!(j.replay(&wrong), Replay::SnapshotRequired);
}
#[test]
fn byte_accounting_counts_entire_serialized_envelope() {
    let mut j = journal(2, 4096);
    let c = j.cursor();
    j.append(event(json!("small"))).unwrap();
    let Replay::Events(events) = j.replay(&c) else {
        panic!()
    };
    let size = serde_json::to_vec(&events[0]).unwrap().len();
    assert_eq!(j.retained_bytes(), size);
    assert!(size > serde_json::to_vec(&events[0].event.payload).unwrap().len());
    let mut exact = journal(2, size);
    assert!(matches!(
        exact.append(event(json!("small"))),
        Ok(Append::Stored(_))
    ));
    let mut short = journal(2, size - 1);
    assert!(matches!(
        short.append(event(json!("small"))),
        Ok(Append::DroppedOversized(_))
    ));
}
#[test]
fn byte_eviction_creates_gap() {
    let mut probe = journal(3, 4096);
    let c = probe.cursor();
    probe.append(event(json!(1))).unwrap();
    let Replay::Events(events) = probe.replay(&c) else {
        panic!()
    };
    let size = serde_json::to_vec(&events[0]).unwrap().len();
    let mut j = journal(3, size);
    let start = j.cursor();
    j.append(event(json!(1))).unwrap();
    j.append(event(json!(2))).unwrap();
    assert_eq!(j.replay(&start), Replay::SnapshotRequired);
    assert_eq!(j.retained_bytes(), size);
}
#[test]
fn oversized_drop_consumes_cursor_and_invalidates_crossing_replay() {
    let mut j = journal(3, 600);
    let start = j.cursor();
    j.append(event(json!(1))).unwrap();
    let before = j.cursor();
    assert!(
        matches!(j.append(event(json!("x".repeat(2000)))),Ok(Append::DroppedOversized(c)) if c.sequence==2)
    );
    let after = j.cursor();
    assert_eq!(j.replay(&start), Replay::SnapshotRequired);
    assert_eq!(j.replay(&before), Replay::SnapshotRequired);
    j.append(event(json!(3))).unwrap();
    assert!(matches!(j.replay(&after),Replay::Events(v) if v.len()==1));
    assert!(j.retained_bytes() <= 600);
}
#[test]
fn reset_invalidates_old_cursor_and_clears_bytes() {
    let mut j = journal(2, 4096);
    j.append(event(json!(1))).unwrap();
    let old = j.cursor();
    let new = j.reset_epoch();
    assert_ne!(new.epoch, old.epoch);
    assert_eq!(new.sequence, 0);
    assert_eq!(j.retained_bytes(), 0);
    assert_eq!(j.replay(&old), Replay::SnapshotRequired);
}
#[test]
fn invalid_limits_and_runtime_are_rejected() {
    for config in [
        JournalConfig {
            max_events: 0,
            max_bytes: 1,
        },
        JournalConfig {
            max_events: 1,
            max_bytes: 0,
        },
    ] {
        assert!(matches!(
            EventJournal::new(runtime(), config),
            Err(JournalError::InvalidConfig)
        ));
    }
    assert!(matches!(
        EventJournal::new(
            RuntimeId {
                computer_id: "".into(),
                ..runtime()
            },
            JournalConfig {
                max_events: 1,
                max_bytes: 1
            }
        ),
        Err(JournalError::InvalidIdentity)
    ));
}
