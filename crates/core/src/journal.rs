use crate::directory::RuntimeId;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::VecDeque;
use uuid::Uuid;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Cursor {
    pub runtime: RuntimeId,
    pub epoch: Uuid,
    pub sequence: u64,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct NativeEvent {
    pub native_thread_id: Option<String>,
    pub native_turn_id: Option<String>,
    pub native_item_id: Option<String>,
    pub payload: Value,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct EventEnvelope {
    pub cursor: Cursor,
    pub event: NativeEvent,
}
#[derive(Clone, Copy, Debug)]
pub struct JournalConfig {
    pub max_events: usize,
    pub max_bytes: usize,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum JournalError {
    InvalidConfig,
    InvalidIdentity,
    SequenceExhausted,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Append {
    Stored(Cursor),
    DroppedOversized(Cursor),
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Replay {
    Events(Vec<EventEnvelope>),
    SnapshotRequired,
}
/// Volatile journal for exactly one runtime. Every construction creates a fresh
/// random epoch; a caller must pair a state snapshot with `cursor()` before
/// streaming/replaying. There is no cross-process journal restoration.
///
/// Bytes are the sum of `serde_json::to_vec(EventEnvelope).len()` for retained
/// events, including cursor/runtime/epoch/native IDs and JSON escaping. This is
/// a serialized size bound, not an allocator/heap-memory bound.
///
/// Oversized events consume a sequence and discard the older retained prefix.
/// Every replay crossing that missing event requires a new state snapshot.
/// At u64::MAX, appends fail without mutation until `reset_epoch()`; the caller
/// must then send snapshots to clients before resuming their incremental feed.
pub struct EventJournal {
    runtime: RuntimeId,
    epoch: Uuid,
    sequence: u64,
    floor: u64,
    config: JournalConfig,
    events: VecDeque<(EventEnvelope, usize)>,
    bytes: usize,
}
impl EventJournal {
    pub fn new(runtime: RuntimeId, config: JournalConfig) -> Result<Self, JournalError> {
        if config.max_events == 0 || config.max_bytes == 0 {
            return Err(JournalError::InvalidConfig);
        }
        if !runtime.is_valid() {
            return Err(JournalError::InvalidIdentity);
        }
        Ok(Self {
            runtime,
            config,
            epoch: Uuid::new_v4(),
            sequence: 0,
            floor: 0,
            events: VecDeque::new(),
            bytes: 0,
        })
    }
    pub fn cursor(&self) -> Cursor {
        Cursor {
            runtime: self.runtime.clone(),
            epoch: self.epoch,
            sequence: self.sequence,
        }
    }
    pub fn append(&mut self, event: NativeEvent) -> Result<Append, JournalError> {
        let sequence = self
            .sequence
            .checked_add(1)
            .ok_or(JournalError::SequenceExhausted)?;
        let cursor = Cursor {
            sequence,
            ..self.cursor()
        };
        let envelope = EventEnvelope {
            cursor: cursor.clone(),
            event,
        };
        let size = serde_json::to_vec(&envelope)
            .expect("event envelope contains serializable JSON fields")
            .len();
        self.sequence = sequence;
        if size > self.config.max_bytes {
            self.events.clear();
            self.bytes = 0;
            self.floor = sequence;
            return Ok(Append::DroppedOversized(cursor));
        }
        while self.events.len() >= self.config.max_events
            || size > self.config.max_bytes - self.bytes
        {
            let (removed, bytes) = self
                .events
                .pop_front()
                .expect("nonempty when count/byte capacity is exceeded");
            self.bytes -= bytes;
            self.floor = removed.cursor.sequence;
        }
        self.bytes += size;
        self.events.push_back((envelope, size));
        Ok(Append::Stored(cursor))
    }
    /// Cursor means "last event already observed". A cursor at the retention
    /// floor is valid, below it is a gap; future/runtime/epoch mismatches fail.
    pub fn replay(&self, cursor: &Cursor) -> Replay {
        if cursor.runtime != self.runtime
            || cursor.epoch != self.epoch
            || cursor.sequence > self.sequence
            || cursor.sequence < self.floor
        {
            return Replay::SnapshotRequired;
        }
        Replay::Events(
            self.events
                .iter()
                .filter(|(e, _)| e.cursor.sequence > cursor.sequence)
                .map(|(e, _)| e.clone())
                .collect(),
        )
    }
    pub fn retained_bytes(&self) -> usize {
        self.bytes
    }
    pub fn reset_epoch(&mut self) -> Cursor {
        let previous = self.epoch;
        loop {
            self.epoch = Uuid::new_v4();
            if self.epoch != previous {
                break;
            }
        }
        self.sequence = 0;
        self.floor = 0;
        self.bytes = 0;
        self.events.clear();
        self.cursor()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sequence_overflow_requires_new_epoch() {
        // Private state permits reaching the otherwise impractical u64 boundary.
        let mut j = EventJournal::new(
            RuntimeId {
                computer_id: "pc".into(),
                runtime_id: "r".into(),
            },
            JournalConfig {
                max_events: 1,
                max_bytes: 4096,
            },
        )
        .unwrap();
        j.sequence = u64::MAX - 1;
        assert!(
            matches!(j.append(NativeEvent { native_thread_id: None, native_turn_id: None, native_item_id: None, payload: Value::Null }), Ok(Append::Stored(c)) if c.sequence == u64::MAX)
        );
        let last = j.cursor();
        assert_eq!(
            j.append(NativeEvent {
                native_thread_id: None,
                native_turn_id: None,
                native_item_id: None,
                payload: Value::Null
            }),
            Err(JournalError::SequenceExhausted)
        );
        assert_eq!(j.cursor(), last);
        assert_eq!(j.reset_epoch().sequence, 0);
        assert!(j
            .append(NativeEvent {
                native_thread_id: None,
                native_turn_id: None,
                native_item_id: None,
                payload: Value::Null
            })
            .is_ok());
    }
}
