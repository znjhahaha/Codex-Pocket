//! Bounded, atomic recovery evidence. Records are reserved before any native write.
use crate::protocol::{MAX_FRAME, Receipt, key};
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, VecDeque},
    path::{Path, PathBuf},
};

const MAX_STATE: u64 = 32 * 1024 * 1024;
const MAX_EVENTS: usize = 2048;
const MAX_BYTES: usize = 8 * 1024 * 1024;
const MAX_PENDING_BYTES: usize = 1024 * 1024;
const MAX_RECORDS: usize = 4096;
const MAX_RUNS: usize = 256;
const MAX_PENDING: usize = 128;
const PAGE_BYTES: usize = 2 * 1024 * 1024;

#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct CommandRecord {
    pub generation: String,
    // Body hashes and replies are process-local, including non-secret answers.
    pub digest: Option<[u8; 32]>,
    pub reply: Option<Value>,
    #[serde(default)]
    pub completed: bool,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct RunRecord {
    pub generation: String,
    pub thread_id: String,
    pub turn_id: String,
    pub state: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct PendingRequest {
    pub generation: String,
    pub request: Value,
}

// Deserialize is retained solely for the schema-1 migration. No serializer:
// persisting this in-memory journal directly must be a compile-time error.
#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Recovery {
    schema: u32,
    pub runtime_id: String,
    pub generation: String,
    pub sequence: u64,
    pub events: VecDeque<(u64, Value)>,
    pub commands: BTreeMap<String, CommandRecord>,
    pub answers: BTreeMap<String, CommandRecord>,
    pub runs: BTreeMap<String, RunRecord>,
    pub pending: BTreeMap<String, PendingRequest>,
    pub reconciliation_required: bool,
}

pub(crate) struct Store {
    pub state: Recovery,
    path: PathBuf,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct CommandEvidence {
    generation: String,
    completed: bool,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct PendingEvidence {
    generation: String,
    request_id: Value,
}
/// An explicit allowlist, never an arbitrary native JSON message.
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Evidence {
    schema: u32,
    runtime_id: String,
    generation: String,
    sequence: u64,
    commands: BTreeMap<String, CommandEvidence>,
    answers: BTreeMap<String, CommandEvidence>,
    runs: BTreeMap<String, RunRecord>,
    pending: BTreeMap<String, PendingEvidence>,
    reconciliation_required: bool,
}
impl Evidence {
    fn capture(state: &Recovery) -> Self {
        let records = |records: &BTreeMap<String, CommandRecord>| {
            records
                .iter()
                .map(|(id, r)| {
                    (
                        id.clone(),
                        CommandEvidence {
                            generation: r.generation.clone(),
                            completed: r.completed,
                        },
                    )
                })
                .collect()
        };
        Self {
            schema: 2,
            runtime_id: state.runtime_id.clone(),
            generation: state.generation.clone(),
            sequence: state.sequence,
            commands: records(&state.commands),
            answers: records(&state.answers),
            runs: state.runs.clone(),
            pending: state
                .pending
                .iter()
                .map(|(id, p)| {
                    (
                        id.clone(),
                        PendingEvidence {
                            generation: p.generation.clone(),
                            request_id: p.request["id"].clone(),
                        },
                    )
                })
                .collect(),
            reconciliation_required: state.reconciliation_required,
        }
    }
    fn recover(self) -> Result<Recovery> {
        ensure!(self.schema == 2, "Invalid recovery schema");
        let records = |records: BTreeMap<String, CommandEvidence>| {
            records
                .into_iter()
                .map(|(id, r)| {
                    (
                        id,
                        CommandRecord {
                            generation: r.generation,
                            digest: None,
                            reply: None,
                            completed: r.completed,
                        },
                    )
                })
                .collect()
        };
        Ok(Recovery {
            schema: 1,
            runtime_id: self.runtime_id,
            generation: self.generation,
            sequence: self.sequence,
            events: VecDeque::new(),
            commands: records(self.commands),
            answers: records(self.answers),
            runs: self.runs,
            pending: self
                .pending
                .into_iter()
                .map(|(id, p)| {
                    (
                        id,
                        PendingRequest {
                            generation: p.generation,
                            request: json!({"id":p.request_id}),
                        },
                    )
                })
                .collect(),
            reconciliation_required: self.reconciliation_required,
        })
    }
}
pub(crate) fn digest(value: &Value) -> Result<[u8; 32]> {
    Ok(Sha256::digest(serde_json::to_vec(value)?).into())
}
fn bytes<T: Serialize>(value: &T) -> usize {
    serde_json::to_vec(value).map_or(usize::MAX, |v| v.len())
}

impl Store {
    pub fn open(path: &Path, runtime_id: &str, generation: &str) -> Result<Self> {
        let mut migrated = false;
        let state = if path.try_exists()? {
            let value: Value =
                serde_json::from_slice(&crate::client::read_private(path, MAX_STATE)?)
                    .map_err(|_| anyhow::anyhow!("Invalid recovery data"))?;
            let mut state = match value["schema"].as_u64() {
                Some(2) => serde_json::from_value::<Evidence>(value)
                    .map_err(|_| anyhow::anyhow!("Invalid recovery data"))?
                    .recover()?,
                Some(1) => {
                    migrated = true;
                    serde_json::from_value::<Recovery>(value)
                        .map_err(|_| anyhow::anyhow!("Invalid recovery data"))?
                }
                _ => anyhow::bail!("Invalid recovery schema"),
            };
            state.validate()?;
            ensure!(
                state.runtime_id == runtime_id,
                "Recovery configuration mismatch"
            );
            if migrated {
                for record in state
                    .commands
                    .values_mut()
                    .chain(state.answers.values_mut())
                {
                    record.completed = record.reply.is_some();
                    record.reply = None;
                    record.digest = None;
                }
                state.events.clear();
                for pending in state.pending.values_mut() {
                    pending.request = json!({"id":pending.request["id"]});
                }
            }
            state
        } else {
            Recovery {
                schema: 1,
                runtime_id: runtime_id.into(),
                generation: generation.into(),
                sequence: 0,
                events: VecDeque::new(),
                commands: BTreeMap::new(),
                answers: BTreeMap::new(),
                runs: BTreeMap::new(),
                pending: BTreeMap::new(),
                reconciliation_required: false,
            }
        };
        let store = Self {
            state,
            path: path.into(),
        };
        // Replace legacy plaintext before any new native child can be launched.
        // Never back up the private bodies or reset their uncertain reservations.
        if migrated {
            store.save()?;
        }
        Ok(store)
    }
    pub fn begin_generation(&mut self, generation: &str) -> Result<()> {
        if self.state.generation != generation {
            for run in self.state.runs.values_mut().filter(|r| r.state == "active") {
                run.state = "unknown".into();
            }
            self.state.reconciliation_required =
                self.state.runs.values().any(|r| r.state == "unknown")
                    || !self.state.pending.is_empty()
                    || self.state.commands.values().any(|c| !c.completed)
                    || self.state.answers.values().any(|c| !c.completed);
            self.state.events.clear();
            for record in self
                .state
                .commands
                .values_mut()
                .chain(self.state.answers.values_mut())
            {
                record.digest = None;
                record.reply = None;
            }
            self.state.generation = generation.into();
        }
        self.save()
    }
    pub fn save(&self) -> Result<()> {
        self.state.validate()?;
        let data = serde_json::to_vec(&Evidence::capture(&self.state))?;
        ensure!(data.len() as u64 <= MAX_STATE, "Recovery capacity exceeded");
        pocket_agent::storage::atomic_write(&self.path, &data)
    }
}

impl Recovery {
    fn validate(&self) -> Result<()> {
        ensure!(
            self.schema == 1
                && !self.runtime_id.is_empty()
                && uuid::Uuid::parse_str(&self.generation).is_ok(),
            "Invalid recovery schema"
        );
        ensure!(
            self.events.len() <= MAX_EVENTS
                && bytes(&self.events) <= MAX_BYTES
                && self.commands.len() + self.answers.len() <= MAX_RECORDS
                && self.runs.len() <= MAX_RUNS
                && self.pending.len() <= MAX_PENDING
                && bytes(&self.pending) <= MAX_PENDING_BYTES,
            "Invalid recovery bounds"
        );
        let mut previous = None;
        for (sequence, event) in &self.events {
            ensure!(
                *sequence > 0
                    && *sequence <= self.sequence
                    && event.is_object()
                    && previous.is_none_or(|p: u64| p.checked_add(1) == Some(*sequence)),
                "Invalid replay sequence"
            );
            previous = Some(*sequence);
        }
        ensure!(
            previous.is_none_or(|p| p == self.sequence),
            "Invalid replay tail"
        );
        let mut result_bytes = 0;
        for (id, command) in self.commands.iter().chain(&self.answers) {
            ensure!(
                id.len() <= 258
                    && (id.starts_with("s:") || id.starts_with("n:"))
                    && uuid::Uuid::parse_str(&command.generation).is_ok(),
                "Invalid receipt identity"
            );
            if let Some(reply) = &command.reply {
                ensure!(
                    reply.is_object() && key(&reply["id"]).as_ref() == Some(id),
                    "Invalid receipt"
                );
                result_bytes += bytes(reply);
            }
        }
        ensure!(result_bytes <= MAX_BYTES, "Invalid receipt bounds");
        for (id, pending) in &self.pending {
            ensure!(
                key(&pending.request["id"]).as_ref() == Some(id)
                    && (pending.request["method"]
                        .as_str()
                        .is_some_and(|m| !m.is_empty())
                        || pending
                            .request
                            .as_object()
                            .is_some_and(|o| o.len() == 1 && o.contains_key("id")))
                    && uuid::Uuid::parse_str(&pending.generation).is_ok(),
                "Invalid pending request"
            );
        }
        for (id, run) in &self.runs {
            ensure!(
                id.len() <= 600
                    && !run.turn_id.is_empty()
                    && run.turn_id.len() <= 256
                    && run.thread_id.len() <= 256
                    && uuid::Uuid::parse_str(&run.generation).is_ok()
                    && ["active", "completed", "unknown"].contains(&run.state.as_str()),
                "Invalid run record"
            );
            ensure!(
                id == &format!("{}:{}:{}", run.generation, run.thread_id, run.turn_id),
                "Invalid run key"
            );
        }
        Ok(())
    }

    pub fn receipt(&self, id: &str, answer: bool) -> Receipt {
        let records = if answer {
            &self.answers
        } else {
            &self.commands
        };
        match records.get(id) {
            Some(record) => Receipt {
                generation: record.generation.clone(),
                state: if record.completed {
                    "completed"
                } else {
                    "unknown"
                }
                .into(),
                response: record.reply.clone(),
            },
            None => Receipt {
                generation: self.generation.clone(),
                state: "missing".into(),
                response: None,
            },
        }
    }

    pub fn reserve(&mut self, id: String, value: &Value, answer: bool) -> Result<Option<Receipt>> {
        let digest = digest(value)?;
        let records = if answer {
            &mut self.answers
        } else {
            &mut self.commands
        };
        if let Some(previous) = records.get(&id) {
            // A cold tombstone has no body hash to compare. It still fences the ID;
            // unknown or completed dispatch must never be sent again.
            ensure!(previous.digest.is_none_or(|d| d == digest), "conflict");
            return Ok(Some(self.receipt(&id, answer)));
        }
        ensure!(
            self.commands.len() + self.answers.len() < MAX_RECORDS,
            "capacity"
        );
        let records = if answer {
            &mut self.answers
        } else {
            &mut self.commands
        };
        records.insert(
            id,
            CommandRecord {
                generation: self.generation.clone(),
                digest: Some(digest),
                reply: None,
                completed: false,
            },
        );
        Ok(None)
    }

    pub fn complete(&mut self, id: &str, value: Value, answer: bool) {
        let used: usize = self
            .commands
            .values()
            .chain(self.answers.values())
            .filter_map(|r| r.reply.as_ref())
            .map(bytes)
            .sum();
        let records = if answer {
            &mut self.answers
        } else {
            &mut self.commands
        };
        if let Some(record) = records.get_mut(id) {
            if record.generation != self.generation || record.completed {
                return;
            }
            let size = bytes(&value);
            // Reserve space for a small eviction receipt for every admitted operation.
            record.reply = Some(
                if size < MAX_FRAME - 16384 && used + size <= MAX_BYTES - MAX_RECORDS * 256 {
                    value
                } else {
                    json!({"id":value["id"],"error":{"code":"result_evicted"}})
                },
            );
            record.completed = true;
        }
    }

    pub fn active_count(&self) -> usize {
        self.runs
            .values()
            .filter(|r| r.generation == self.generation && r.state == "active")
            .count()
    }
    pub fn pending_count(&self) -> usize {
        self.pending
            .values()
            .filter(|r| r.generation == self.generation)
            .count()
    }
    pub fn busy(&self) -> bool {
        self.active_count() > 0
            || self.pending_count() > 0
            || self
                .commands
                .values()
                .chain(self.answers.values())
                .any(|c| c.generation == self.generation && !c.completed)
    }

    pub fn push(&mut self, value: Value) -> Result<Value> {
        let method = value["method"].as_str().unwrap_or("");
        if ["turn/started", "turn/completed"].contains(&method)
            && let Some(turn_id) = value["params"]["turn"]["id"].as_str()
        {
            let thread_id = value["params"]["threadId"].as_str().unwrap_or("");
            ensure!(
                turn_id.len() <= 256 && thread_id.len() <= 256,
                "Native run identity exceeds budget"
            );
            let id = format!("{}:{thread_id}:{turn_id}", self.generation);
            if !self.runs.contains_key(&id) && self.runs.len() >= MAX_RUNS {
                if let Some(old) = self
                    .runs
                    .iter()
                    .find(|(_, r)| r.state == "completed")
                    .map(|(id, _)| id.clone())
                {
                    self.runs.remove(&old);
                } else {
                    ensure!(false, "Run recovery capacity exceeded");
                }
            }
            self.runs.insert(
                id,
                RunRecord {
                    generation: self.generation.clone(),
                    thread_id: thread_id.into(),
                    turn_id: turn_id.into(),
                    state: if method == "turn/started" {
                        "active"
                    } else {
                        "completed"
                    }
                    .into(),
                },
            );
        }
        if method == "serverRequest/resolved"
            && let Some(id) = key(&value["params"]["requestId"])
        {
            self.pending.remove(&id);
        }
        if !method.is_empty() && value.get("id").is_some() {
            if let Some(id) = key(&value["id"]) {
                ensure!(
                    self.pending.len() < MAX_PENDING || self.pending.contains_key(&id),
                    "Native request capacity exceeded"
                );
                self.pending.insert(
                    id,
                    PendingRequest {
                        generation: self.generation.clone(),
                        request: value.clone(),
                    },
                );
                ensure!(
                    bytes(&self.pending) <= MAX_PENDING_BYTES,
                    "Native request byte capacity exceeded"
                );
            }
        } else if method.is_empty()
            && let Some(id) = key(&value["id"])
        {
            self.complete(&id, value.clone(), false);
        }
        self.sequence = self
            .sequence
            .checked_add(1)
            .ok_or_else(|| anyhow::anyhow!("Replay sequence exhausted"))?;
        self.events.push_back((self.sequence, value.clone()));
        while self.events.len() > MAX_EVENTS || bytes(&self.events) > MAX_BYTES {
            self.events.pop_front();
        }
        Ok(
            json!({"method":"host/event","params":{"generation":self.generation,"sequence":self.sequence,"event":value}}),
        )
    }

    pub fn replay(&self, after: u64, generation: Option<&str>) -> Value {
        let gap = after > self.sequence
            || generation.is_some_and(|g| g != self.generation)
            || self
                .events
                .front()
                .is_some_and(|(s, _)| after.saturating_add(1) < *s)
            || (self.events.is_empty() && after < self.sequence);
        let mut page = Vec::new();
        let mut used = 0;
        let mut cursor = after;
        if !gap {
            for (sequence, event) in self.events.iter().filter(|(s, _)| *s > after) {
                let size = bytes(event) + 64;
                if used + size > PAGE_BYTES {
                    break;
                }
                used += size;
                cursor = *sequence;
                page.push(json!({"sequence":sequence,"event":event}));
            }
        }
        // A single event larger than the page budget requires a native snapshot.
        let gap = gap || (page.is_empty() && after < self.sequence);
        json!({"generation":self.generation,"sequence":self.sequence,"nextAfter":if gap{self.sequence}else{cursor},
            "hasMore":!gap && cursor<self.sequence,"snapshotRequired":gap,"events":if gap{vec![]}else{page},
            "pendingRequests":self.pending.values().filter(|p|p.generation==self.generation && p.request.get("method").is_some()).map(|p|p.request.clone()).collect::<Vec<_>>(),
            "reconciliationRequired":self.reconciliation_required})
    }
    pub fn snapshot(&self) -> Value {
        json!({"generation":self.generation,"sequence":self.sequence,"reconciliationRequired":self.reconciliation_required,
            "runs":self.runs.values().collect::<Vec<_>>(),"pendingRequests":self.pending.values().map(|p| if p.request.get("method").is_some() {json!({"generation":p.generation,"request":p.request})} else {json!({"generation":p.generation,"requestId":p.request["id"],"state":"unknown"})}).collect::<Vec<_>>(),
            "commandCount":self.commands.len(),"answerCount":self.answers.len()})
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn persisted_evidence_omits_private_content_and_all_body_digests() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state");
        let generation = uuid::Uuid::new_v4().to_string();
        let mut store = Store::open(&path, "fixture", &generation).unwrap();
        let command =
            json!({"id":"private-op","method":"turn/start","params":{"text":"private-prompt"}});
        store
            .state
            .reserve("s:private-op".into(), &command, false)
            .unwrap();
        store
            .state
            .push(json!({"id":"private-op","result":{"text":"private-result"}}))
            .unwrap();
        store.state.push(json!({"id":77,"method":"item/tool/requestUserInput","params":{"questions":[{"isSecret":true,"question":"private-question"}]}})).unwrap();
        let answer = json!({"id":77,"result":{"answers":{"pin":"1234"}}});
        let answer_digest = digest(&answer).unwrap();
        store.state.reserve("n:77".into(), &answer, true).unwrap();
        store
            .state
            .push(json!({"method":"fixture/private-event","params":{"text":"private-message"}}))
            .unwrap();
        store.save().unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        for forbidden in [
            "private-prompt",
            "private-result",
            "private-question",
            "1234",
            "private-message",
            "digest",
            "reply",
            "events",
            "params",
        ] {
            assert!(!text.contains(forbidden), "private field persisted");
        }
        assert!(!text.contains(&serde_json::to_string(&answer_digest).unwrap()));
        let mut cold = Store::open(&path, "fixture", &generation).unwrap();
        let receipt = cold.state.receipt("s:private-op", false);
        assert_eq!(receipt.state, "completed");
        assert!(receipt.response.is_none());
        assert_eq!(
            cold.state
                .reserve("n:77".into(), &answer, true)
                .unwrap()
                .unwrap()
                .state,
            "unknown"
        );
        assert!(cold.state.replay(0, Some(&generation))["snapshotRequired"] == true);
        assert!(
            !cold
                .state
                .snapshot()
                .to_string()
                .contains("private-question")
        );
    }

    #[test]
    fn legacy_snapshot_is_scrubbed_without_forgetting_uncertain_dispatch() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state");
        let generation = uuid::Uuid::new_v4().to_string();
        let command = json!({"id":"old","method":"turn/start","params":{"text":"legacy-private"}});
        let legacy = json!({"schema":1,"runtime_id":"fixture","generation":generation,"sequence":1,
            "events":[[1,{"method":"fixture/event","params":{"text":"legacy-private"}}]],
            "commands":{"s:old":{"generation":generation,"digest":digest(&command).unwrap(),"reply":null}},
            "answers":{},"runs":{},"pending":{"n:77":{"generation":generation,"request":{"id":77,"method":"item/tool/requestUserInput","params":{"text":"legacy-private"}}}},
            "reconciliation_required":false});
        pocket_agent::storage::atomic_write(&path, &serde_json::to_vec(&legacy).unwrap()).unwrap();
        let next = uuid::Uuid::new_v4().to_string();
        let mut store = Store::open(&path, "fixture", &next).unwrap();
        store.begin_generation(&next).unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(!text.contains("legacy-private"));
        assert!(!text.contains("digest"));
        assert_eq!(serde_json::from_str::<Value>(&text).unwrap()["schema"], 2);
        assert_eq!(
            store
                .state
                .reserve("s:old".into(), &command, false)
                .unwrap()
                .unwrap()
                .state,
            "unknown"
        );
        assert!(store.state.reconciliation_required);
        assert_eq!(store.state.pending.len(), 1);
    }

    #[test]
    fn schema_two_rejects_private_extensions_without_resetting_evidence() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state");
        let generation = uuid::Uuid::new_v4().to_string();
        let mut store = Store::open(&path, "fixture", &generation).unwrap();
        store
            .state
            .reserve("s:unknown".into(), &json!({"id":"unknown"}), false)
            .unwrap();
        store.save().unwrap();
        let original = std::fs::read(&path).unwrap();
        let mut value: Value = serde_json::from_slice(&original).unwrap();
        value["commands"]["s:unknown"]["reply"] = json!({"text":"private-injected"});
        let invalid = serde_json::to_vec(&value).unwrap();
        std::fs::write(&path, &invalid).unwrap();
        let error = Store::open(&path, "fixture", &generation).err().unwrap();
        assert!(!format!("{error:#}").contains("private-injected"));
        assert!(std::fs::read(&path).unwrap() == invalid);
        std::fs::write(&path, original).unwrap();
        assert_eq!(
            Store::open(&path, "fixture", &generation)
                .unwrap()
                .state
                .receipt("s:unknown", false)
                .state,
            "unknown"
        );
    }

    #[test]
    fn reservations_survive_restart_and_cannot_be_reissued() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("recovery.json");
        let generation = uuid::Uuid::new_v4().to_string();
        let mut store = Store::open(&path, "fixture", &generation).unwrap();
        let command = json!({"id":"operation","method":"turn/start","params":{}});
        store
            .state
            .reserve("s:operation".into(), &command, false)
            .unwrap();
        store.save().unwrap();
        let next = uuid::Uuid::new_v4().to_string();
        let mut recovered = Store::open(&path, "fixture", &next).unwrap();
        recovered.begin_generation(&next).unwrap();
        assert_eq!(
            recovered
                .state
                .reserve("s:operation".into(), &command, false)
                .unwrap()
                .unwrap()
                .state,
            "unknown"
        );
        assert!(recovered.state.reconciliation_required);
        assert_eq!(
            recovered
                .state
                .reserve("s:operation".into(), &json!({"different":true}), false)
                .unwrap()
                .unwrap()
                .state,
            "unknown"
        );
        assert!(Store::open(&path, "different-home", &next).is_err());
    }
    #[test]
    fn bounds_reject_new_ids_without_evicting_unknown_receipts() {
        let dir = tempfile::tempdir().unwrap();
        let mut store = Store::open(
            &dir.path().join("state"),
            "fixture",
            &uuid::Uuid::new_v4().to_string(),
        )
        .unwrap();
        for index in 0..MAX_RECORDS {
            store
                .state
                .reserve(format!("s:{index}"), &json!({"id":index}), false)
                .unwrap();
        }
        assert!(
            store
                .state
                .reserve("s:overflow".into(), &json!({}), false)
                .is_err()
        );
        assert_eq!(store.state.receipt("s:0", false).state, "unknown");
        store.save().unwrap();
    }
    #[test]
    fn invalid_state_is_not_reset_and_replay_reports_eviction() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state");
        let generation = uuid::Uuid::new_v4().to_string();
        let mut store = Store::open(&path, "fixture", &generation).unwrap();
        for _ in 0..MAX_EVENTS + 1 {
            store.state.push(json!({"method":"fixture/event"})).unwrap();
        }
        assert_eq!(store.state.events.len(), MAX_EVENTS);
        assert_eq!(
            store.state.replay(0, Some(&generation))["snapshotRequired"],
            true
        );
        assert_eq!(
            store.state.replay(1, Some(&generation))["snapshotRequired"],
            false
        );
        assert_eq!(store.state.replay(1, Some("old"))["snapshotRequired"], true);
        store.save().unwrap();
        let bytes = std::fs::read(&path).unwrap();
        std::fs::write(&path, b"{").unwrap();
        assert!(Store::open(&path, "fixture", &generation).is_err());
        assert_eq!(std::fs::read(&path).unwrap(), b"{");
        std::fs::write(&path, &bytes).unwrap();
        assert!(Store::open(&path, "fixture", &generation).is_ok());
    }
}
