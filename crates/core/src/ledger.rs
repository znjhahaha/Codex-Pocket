use crate::directory::RuntimeId;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CommandKey {
    pub caller_id: String,
    pub command_id: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub enum Status {
    Submitted,
    Accepted { native_result: Value },
    Unknown,
    Rejected { reason: String },
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Begin {
    Execute,
    Existing(Status),
}
#[derive(Clone, Copy, Debug)]
pub struct LedgerConfig {
    pub capacity: usize,
    pub terminal_ttl: u64,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LedgerError {
    InvalidConfig,
    InvalidIdentity,
    BodyConflict,
    Full,
    ClockRegression,
    GenerationRegression,
    Missing,
    InvalidTransition,
    InvalidSnapshot,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Entry {
    key: CommandKey,
    fingerprint: [u8; 32],
    status: Status,
    generation: u64,
    created_at: u64,
    finished_at: Option<u64>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Snapshot {
    version: u32,
    runtime: RuntimeId,
    generation: u64,
    last_now: u64,
    entries: Vec<Entry>,
}
/// Single-writer deduplication state bound to a computer/runtime identity.
///
/// `begin` returning `Execute` reserves Submitted in memory. Durably and atomically
/// persist `snapshot_json()` BEFORE issuing any native write. A failed persist
/// must prevent that write; do not treat an Existing outcome as permission to
/// execute. Persist every later mutation, including native results, expiry and
/// generation changes. On restore all Submitted entries become Unknown.
///
/// Unknown has no TTL and cannot be re-executed. Only independent reconciliation
/// via `finish` can resolve it. Unexpired terminal entries are never evicted for
/// capacity. Terminal records expire at `finished_at + terminal_ttl` (using
/// subtraction to avoid overflow), or on a strictly newer generation. After
/// expiry a key may execute again: deduplication is bounded by that contract.
/// Caller timestamps use one monotonic, restart-stable unit and never decrease.
pub struct CommandLedger {
    runtime: RuntimeId,
    config: LedgerConfig,
    generation: u64,
    last_now: u64,
    entries: BTreeMap<CommandKey, Entry>,
}
impl CommandLedger {
    pub fn new(
        runtime: RuntimeId,
        config: LedgerConfig,
        generation: u64,
        now: u64,
    ) -> Result<Self, LedgerError> {
        if config.capacity == 0 {
            return Err(LedgerError::InvalidConfig);
        }
        if !runtime.is_valid() {
            return Err(LedgerError::InvalidIdentity);
        }
        Ok(Self {
            runtime,
            config,
            generation,
            last_now: now,
            entries: BTreeMap::new(),
        })
    }
    /// Borrow the stored status without advancing time, expiring or reserving entries.
    pub fn status(&self, key: &CommandKey) -> Option<&Status> {
        self.entries.get(key).map(|entry| &entry.status)
    }
    pub fn begin(&mut self, key: CommandKey, body: &[u8], now: u64) -> Result<Begin, LedgerError> {
        if key.caller_id.is_empty() || key.command_id.is_empty() {
            return Err(LedgerError::InvalidIdentity);
        }
        self.tick(now)?;
        self.expire();
        let fingerprint = Self::fingerprint(body);
        if let Some(entry) = self.entries.get(&key) {
            return if entry.fingerprint == fingerprint {
                Ok(Begin::Existing(entry.status.clone()))
            } else {
                Err(LedgerError::BodyConflict)
            };
        }
        if self.entries.len() >= self.config.capacity {
            return Err(LedgerError::Full);
        }
        self.entries.insert(
            key.clone(),
            Entry {
                key,
                fingerprint,
                status: Status::Submitted,
                generation: self.generation,
                created_at: now,
                finished_at: None,
            },
        );
        Ok(Begin::Execute)
    }
    /// Unknown may be reconciled, never reset to Submitted. Terminal results are
    /// immutable; repeating the same terminal result does not refresh its TTL.
    /// Completion is keyed only by CommandKey, without an execution-attempt token.
    /// Callers allowing expired keys to be reused must exclude delayed completions
    /// from prior uses. The broker keeps a fixed generation and maximum TTL so
    /// command keys remain reserved throughout its supported retention horizon.
    pub fn finish(
        &mut self,
        key: &CommandKey,
        status: Status,
        now: u64,
    ) -> Result<(), LedgerError> {
        self.tick(now)?;
        let entry = self.entries.get_mut(key).ok_or(LedgerError::Missing)?;
        if matches!(status, Status::Submitted) {
            return Err(LedgerError::InvalidTransition);
        }
        if matches!(
            entry.status,
            Status::Accepted { .. } | Status::Rejected { .. }
        ) {
            return if entry.status == status {
                Ok(())
            } else {
                Err(LedgerError::InvalidTransition)
            };
        }
        entry.finished_at = if matches!(status, Status::Unknown) {
            None
        } else {
            Some(now)
        };
        entry.status = status;
        // Reconciliation belongs to the generation in which it became terminal.
        if entry.finished_at.is_some() {
            entry.generation = self.generation;
        }
        Ok(())
    }
    pub fn advance_generation(&mut self, generation: u64) -> Result<(), LedgerError> {
        if generation < self.generation {
            return Err(LedgerError::GenerationRegression);
        }
        self.generation = generation;
        self.expire();
        Ok(())
    }
    pub fn snapshot_json(&self) -> String {
        serde_json::to_string(&Snapshot {
            version: 1,
            runtime: self.runtime.clone(),
            generation: self.generation,
            last_now: self.last_now,
            entries: self.entries.values().cloned().collect(),
        })
        .expect("JSON Value and bounded integer/string snapshot fields are serializable")
    }
    /// Parse and validate everything before accepting state. Capacity is checked
    /// before expiry, preventing malformed/overfull snapshots being hidden by TTL.
    /// Caller supplies the expected runtime and policy; changing TTL deliberately
    /// changes terminal retention. Submitted/Unknown remain protected regardless.
    pub fn restore_json(
        json: &str,
        runtime: RuntimeId,
        config: LedgerConfig,
        generation: u64,
        now: u64,
    ) -> Result<Self, LedgerError> {
        let snapshot: Snapshot =
            serde_json::from_str(json).map_err(|_| LedgerError::InvalidSnapshot)?;
        let mut ledger = Self::new(runtime, config, generation, now)?;
        if snapshot.version != 1
            || snapshot.runtime != ledger.runtime
            || snapshot.entries.len() > config.capacity
            || snapshot.last_now > now
            || snapshot.generation > generation
        {
            return Err(LedgerError::InvalidSnapshot);
        }
        for mut entry in snapshot.entries {
            let terminal = matches!(
                entry.status,
                Status::Accepted { .. } | Status::Rejected { .. }
            );
            if entry.key.caller_id.is_empty()
                || entry.key.command_id.is_empty()
                || entry.generation > snapshot.generation
                || (terminal && entry.generation != snapshot.generation)
                || entry.created_at > snapshot.last_now
                || terminal != entry.finished_at.is_some()
                || entry
                    .finished_at
                    .is_some_and(|t| t < entry.created_at || t > snapshot.last_now)
                || ledger.entries.contains_key(&entry.key)
            {
                return Err(LedgerError::InvalidSnapshot);
            }
            if matches!(entry.status, Status::Submitted) {
                entry.status = Status::Unknown;
            }
            ledger.entries.insert(entry.key.clone(), entry);
        }
        ledger.expire();
        Ok(ledger)
    }
    /// SHA-256 of exactly `body`, with no prefix, encoding conversion, JSON
    /// parsing, key ordering, whitespace removal or trailing newline added.
    /// Caller must include the operation, target and all execution-affecting
    /// fields in those bytes and keep that encoding stable across retries.
    pub fn fingerprint(body: &[u8]) -> [u8; 32] {
        Sha256::digest(body).into()
    }
    fn tick(&mut self, now: u64) -> Result<(), LedgerError> {
        if now < self.last_now {
            return Err(LedgerError::ClockRegression);
        }
        self.last_now = now;
        Ok(())
    }
    fn expire(&mut self) {
        self.entries.retain(|_, e| match e.finished_at {
            None => true,
            Some(finished) => {
                e.generation == self.generation
                    && self.last_now - finished < self.config.terminal_ttl
            }
        });
    }
}
