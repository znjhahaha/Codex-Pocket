//! Bounded, atomic recovery evidence. Records are reserved before any native write.
use crate::protocol::{MAX_FRAME, Receipt, key};
use crate::queries::{PreparedRequest, Queries, read_only};
use anyhow::{Context, Result, ensure};
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
// Keep all 4096 legacy receipts, including ones whose original method is no
// longer knowable, and leave a bounded 4096-record budget for new effects.
// Queries use a separate volatile cache and cannot consume this budget.
const MAX_RECORDS: usize = pocket_runtime_ipc::MAX_RECOVERY_RECORDS;
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
    #[serde(default)]
    pub scope: Option<crate::admission::Scope>,
    #[serde(default)]
    pub method: Option<String>,
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
    // Trusted correlation lives only in this Host generation, never on disk.
    reads: BTreeMap<String, ReadEvidence>,
    queries: Queries,
    pub(crate) threads: BTreeMap<String, crate::admission::Scope>,
    pub(crate) ended_generations: std::collections::BTreeSet<String>,
}

struct ReadEvidence {
    runtime_id: String,
    generation: String,
    thread_id: String,
    runs: Vec<(String, RunRecord)>,
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
            // Schema 3 is needed only once an ID is reused across generations.
            // Older Hosts must fail closed rather than discard archived answers.
            schema: if state.answers.keys().any(|id| id.starts_with("g:")) { 3 } else { 2 },
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
        ensure!(matches!(self.schema, 2 | 3), "Invalid recovery schema");
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
                            scope: None,
                            method: None,
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

fn answer_identity<'a>(stored: &'a str, generation: &str) -> Result<&'a str> {
    if let Some(scoped) = stored.strip_prefix("g:") {
        let (owner, id) = scoped.split_once(':').context("Invalid archived answer identity")?;
        ensure!(owner == generation, "Archived answer generation mismatch");
        Ok(id)
    } else {
        Ok(stored)
    }
}

impl Store {
    pub fn open(path: &Path, runtime_id: &str, generation: &str) -> Result<Self> {
        let mut migrated = false;
        let state = if path.try_exists()? {
            let value: Value =
                serde_json::from_slice(&crate::client::read_private(path, MAX_STATE)?)
                    .map_err(|_| anyhow::anyhow!("Invalid recovery data"))?;
            let mut state = match value["schema"].as_u64() {
                Some(2 | 3) => serde_json::from_value::<Evidence>(value)
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
            reads: BTreeMap::new(),
            queries: Queries::default(),
            threads: BTreeMap::new(),
            ended_generations: Default::default(),
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
            self.queries = Queries::default();
            self.reads.clear();
            self.threads.clear();
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
                record.scope = None;
                record.method = None;
            }
            self.state.generation = generation.into();
        }
        self.save()
    }
    /// Called only after the original descriptor's two creation identities end.
    /// This releases concurrency resources, never the original result receipt.
    pub(crate) fn release_predecessor(
        &mut self,
        previous: &crate::protocol::Descriptor,
    ) -> Result<()> {
        ensure!(
            previous.runtime_id == self.state.runtime_id
                && previous.generation == self.state.generation,
            "Predecessor recovery identity mismatch"
        );
        for (pid, process) in [
            (previous.pid, &previous.host_process),
            (previous.native_pid, &previous.native_process),
        ] {
            ensure!(
                process
                    .as_ref()
                    .is_some_and(|p| p.pid == pid && p.created_at != 0 && p.terminated()),
                "Predecessor process lifetime is not ended"
            );
        }
        self.ended_generations.insert(previous.generation.clone());
        self.ended_generations.extend(
            self.state
                .commands
                .values()
                .chain(self.state.answers.values())
                .map(|r| r.generation.clone()),
        );
        self.ended_generations
            .extend(self.state.runs.values().map(|r| r.generation.clone()));
        self.ended_generations
            .extend(self.state.pending.values().map(|r| r.generation.clone()));
        Ok(())
    }
    pub(crate) fn snapshot(&self) -> Value {
        let mut value = self.state.snapshot();
        value["releasedGenerations"] = json!(self.ended_generations);
        value
    }
    pub(crate) fn busy_resources(&self) -> bool {
        let live = |generation: &str| {
            generation == self.state.generation || !self.ended_generations.contains(generation)
        };
        // A reconciliation flag without a verified predecessor still fences.
        (self.state.reconciliation_required && self.ended_generations.is_empty())
            || self
                .state
                .runs
                .values()
                .any(|r| live(&r.generation) && r.state != "completed")
            || self.state.pending.values().any(|p| live(&p.generation))
            || self
                .state
                .commands
                .values()
                .chain(self.state.answers.values())
                .any(|r| live(&r.generation) && !r.completed)
    }
    pub fn save(&self) -> Result<()> {
        self.state.validate()?;
        let data = serde_json::to_vec(&Evidence::capture(&self.state))?;
        ensure!(data.len() as u64 <= MAX_STATE, "Recovery capacity exceeded");
        pocket_agent::storage::atomic_write(&self.path, &data)
    }
    pub fn receipt(&self, id: &str) -> Receipt {
        // A legacy effect/unknown ID always takes precedence over read caching.
        if self.state.commands.contains_key(id) {
            self.state.receipt(id, false)
        } else {
            self.queries
                .receipt(id)
                .unwrap_or_else(|| self.state.receipt(id, false))
        }
    }
    pub fn reserve_request(&mut self, value: &Value) -> Result<PreparedRequest> {
        let id = key(&value["id"]).expect("validated request ID");
        if !self.state.commands.contains_key(&id) && read_only(value) {
            return self.queries.reserve(value, &self.state.generation);
        }
        ensure!(self.queries.receipt(&id).is_none(), "conflict");
        let scope = self.request_scope(value);
        let receipt = self.state.reserve(id.clone(), value, false)?;
        if receipt.is_none() {
            let r = self.state.commands.get_mut(&id).unwrap();
            r.scope = scope;
            r.method = value["method"].as_str().map(str::to_owned);
        }
        Ok(PreparedRequest {
            receipt,
            native: value.clone(),
            durable: true,
        })
    }
    pub fn note_read(&mut self, value: &Value) {
        if value["method"] != "thread/read"
            || value["params"]["includeTurns"] != true
            || self.reads.len() >= 128
        {
            return;
        }
        let (Some(id), Some(thread_id)) = (key(&value["id"]), value["params"]["threadId"].as_str())
        else {
            return;
        };
        let runs = self
            .state
            .runs
            .iter()
            .filter(|(_, run)| run.thread_id == thread_id && run.state == "unknown")
            .map(|(key, run)| (key.clone(), run.clone()))
            .collect();
        self.reads.insert(
            id,
            ReadEvidence {
                runtime_id: self.state.runtime_id.clone(),
                generation: self.state.generation.clone(),
                thread_id: thread_id.into(),
                runs,
            },
        );
    }
    pub fn observe_native(&mut self, value: Value) -> Result<Value> {
        let value = self.queries.observe(value);
        self.observe_scope(&value)?;
        let read = if value.get("method").is_none() {
            key(&value["id"]).and_then(|id| self.reads.remove(&id))
        } else {
            None
        };
        let event = self.state.push(value.clone())?;
        if let Some(read) = read {
            if read.runtime_id == self.state.runtime_id
                && read.generation == self.state.generation
                && value.get("error").is_none()
                && value["result"]["thread"]["id"] == read.thread_id
            {
                if let Some(turns) = value["result"]["thread"]["turns"].as_array() {
                    for (key, expected) in read.runs {
                        let matches: Vec<_> = turns
                            .iter()
                            .filter(|turn| turn["id"] == expected.turn_id)
                            .collect();
                        if matches.len() != 1
                            || !matches!(
                                matches[0]["status"].as_str(),
                                Some("completed" | "interrupted" | "failed")
                            )
                        {
                            continue;
                        }
                        if let Some(run) = self.state.runs.get_mut(&key) {
                            if run.generation == expected.generation
                                && run.thread_id == expected.thread_id
                                && run.turn_id == expected.turn_id
                                && run.state == "unknown"
                            {
                                run.state = "completed".into();
                            }
                        }
                    }
                }
            }
            // Reads resolve only their exactly matched runs. Unknown commands,
            // answers and old pending questions remain fenced without proof.
            self.state.reconciliation_required =
                self.state.runs.values().any(|r| r.state == "unknown")
                    || self
                        .state
                        .pending
                        .values()
                        .any(|p| p.generation != self.state.generation)
                    || self
                        .state
                        .commands
                        .values()
                        .chain(self.state.answers.values())
                        .any(|c| c.generation != self.state.generation && !c.completed);
        }
        Ok(event)
    }
}

impl Recovery {
    pub(crate) fn current_answer_receipt(&self, id: &str) -> Receipt {
        let receipt = self.receipt(id, true);
        if receipt.generation == self.generation {
            receipt
        } else {
            Receipt { generation: self.generation.clone(), state: "missing".into(), response: None }
        }
    }

    /// A fresh current native question may reuse a predecessor's request ID.
    /// Archive only its receipt identity; keep its state and original generation.
    pub(crate) fn reserve_answer(&mut self, id: String, value: &Value) -> Result<Option<Receipt>> {
        if let Some(previous) = self.answers.get(&id).filter(|r| r.generation != self.generation) {
            ensure!(self.pending.get(&id).is_some_and(|p| p.generation == self.generation
                && p.request.get("method").is_some()), "stale_request");
            ensure!(self.commands.len() + self.answers.len() < MAX_RECORDS, "capacity");
            let archived = format!("g:{}:{id}", previous.generation);
            ensure!(!self.answers.contains_key(&archived), "conflict");
            let previous = self.answers.remove(&id).unwrap();
            self.answers.insert(archived, previous);
        }
        self.reserve(id, value, true)
    }

    /// Digest the persisted allowlist, including identities, even when event
    /// sequence does not advance for a command/answer completion.
    pub fn evidence_digest(&self) -> Result<String> {
        let bytes = serde_json::to_vec(&Evidence::capture(self))?;
        Ok(Sha256::digest(bytes)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect())
    }

    pub fn answer_evidence(
        &self,
        generation: &str,
        offset: usize,
        snapshot_digest: &str,
    ) -> Result<pocket_runtime_ipc::client::AnswerEvidencePage> {
        use pocket_runtime_ipc::client::{
            ANSWER_EVIDENCE_PAGE_SIZE, AnswerEvidence, AnswerEvidencePage,
        };
        ensure!(
            generation == self.generation && snapshot_digest == self.evidence_digest()?,
            "Answer evidence snapshot changed"
        );
        ensure!(
            offset <= self.answers.len() && self.answers.len() <= MAX_RECORDS,
            "Answer evidence offset invalid"
        );
        let records = self
            .answers
            .iter()
            .skip(offset)
            .take(ANSWER_EVIDENCE_PAGE_SIZE)
            .map(|(stored, record)| {
                let id = answer_identity(stored, &record.generation)?;
                let request_id = if let Some(text) = id.strip_prefix("s:") {
                    json!(text)
                } else if let Some(number) = id.strip_prefix("n:") {
                    serde_json::from_str(number)?
                } else {
                    anyhow::bail!("Invalid historical answer identity")
                };
                ensure!(
                    key(&request_id).as_deref() == Some(id),
                    "Historical answer identity is not canonical"
                );
                Ok(AnswerEvidence {
                    generation: record.generation.clone(),
                    request_id,
                    completed: record.completed,
                })
            })
            .collect::<Result<Vec<_>>>()?;
        let end = offset + records.len();
        Ok(AnswerEvidencePage {
            schema: 1,
            namespace: "answer".into(),
            runtime_id: self.runtime_id.clone(),
            generation: self.generation.clone(),
            snapshot_digest: snapshot_digest.into(),
            offset,
            total: self.answers.len(),
            next_offset: (end < self.answers.len()).then_some(end),
            records,
        })
    }

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
        let mut answer_ids = std::collections::BTreeSet::new();
        for (stored, command, answer) in self.commands.iter().map(|(k,v)|(k,v,false))
            .chain(self.answers.iter().map(|(k,v)|(k,v,true))) {
            let id = if answer { answer_identity(stored, &command.generation)? } else { stored.as_str() };
            if answer {
                ensure!(answer_ids.insert((&command.generation, id)), "Duplicate answer generation/identity");
            }
            ensure!(
                id.len() <= 258
                    && (id.starts_with("s:") || id.starts_with("n:"))
                    && uuid::Uuid::parse_str(&command.generation).is_ok(),
                "Invalid receipt identity"
            );
            if let Some(reply) = &command.reply {
                ensure!(
                    reply.is_object() && key(&reply["id"]).as_deref() == Some(id),
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
                scope: None,
                method: None,
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
        self.reconciliation_required
            || self.runs.values().any(|r| r.state != "completed")
            || !self.pending.is_empty()
            || self
                .commands
                .values()
                .chain(self.answers.values())
                .any(|c| !c.completed)
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
            self.observe_run(
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
                true,
            )?;
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

    pub(crate) fn observe_run(&mut self, run: RunRecord, notification: bool) -> Result<()> {
        let id = format!("{}:{}:{}", run.generation, run.thread_id, run.turn_id);
        if self.runs.contains_key(&id) && !notification {
            return Ok(());
        }
        if !self.runs.contains_key(&id) && self.runs.len() >= MAX_RUNS {
            let old = self
                .runs
                .iter()
                .find(|(_, r)| r.state == "completed")
                .map(|(id, _)| id.clone());
            if let Some(old) = old {
                self.runs.remove(&old);
            } else {
                anyhow::bail!("Run recovery capacity exceeded");
            }
        }
        self.runs.insert(id, run);
        Ok(())
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
        let unconfirmed_writes = self
            .commands
            .values()
            .chain(self.answers.values())
            .filter(|record| !record.completed)
            .count();
        // receipt() calls every incomplete reservation unknown, even in the
        // current generation. Completed cold tombstones still count zero.
        // Runs and pending native requests are separate snapshot evidence.
        json!({"generation":self.generation,"sequence":self.sequence,"reconciliationRequired":self.reconciliation_required,
            "evidenceDigest":self.evidence_digest().ok(),
            "runs":self.runs.values().collect::<Vec<_>>(),"pendingRequests":self.pending.values().map(|p| if p.request.get("method").is_some() {json!({"generation":p.generation,"request":p.request})} else {json!({"generation":p.generation,"requestId":p.request["id"],"state":"unknown"})}).collect::<Vec<_>>(),
            "commandCount":self.commands.len(),"answerCount":self.answers.len(),
            "unconfirmedWrites":unconfirmed_writes,"unknownOperations":unconfirmed_writes})
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn answer_evidence_pagination_binds_completion_without_event_sequence_or_payload() {
        let root = tempfile::tempdir().unwrap();
        let generation = uuid::Uuid::new_v4().to_string();
        let mut store = Store::open(
            &root.path().join("recovery.json"),
            "evidence-fixture",
            &generation,
        )
        .unwrap();
        for id in 0..65 {
            let key = key(&json!(id)).unwrap();
            store
                .state
                .reserve(
                    key.clone(),
                    &json!({"id":id,"result":"private-answer-sentinel"}),
                    true,
                )
                .unwrap();
            store
                .state
                .complete(&key, json!({"id":id,"result":{"written":true}}), true);
        }
        let digest = store.state.evidence_digest().unwrap();
        let first = store
            .state
            .answer_evidence(&generation, 0, &digest)
            .unwrap();
        assert_eq!(first.records.len(), 64);
        assert_eq!(first.next_offset, Some(64));
        let second = store
            .state
            .answer_evidence(&generation, 64, &digest)
            .unwrap();
        assert_eq!(second.records.len(), 1);
        assert!(second.next_offset.is_none());
        assert!(
            !serde_json::to_string(&first)
                .unwrap()
                .contains("private-answer-sentinel")
        );
        let sequence = store.state.sequence;
        store
            .state
            .reserve("s:new".into(), &json!({"id":"new","result":{}}), true)
            .unwrap();
        assert_eq!(store.state.sequence, sequence);
        assert!(
            store
                .state
                .answer_evidence(&generation, 64, &digest)
                .is_err()
        );
        assert_ne!(store.state.evidence_digest().unwrap(), digest);
        assert!(
            store
                .state
                .answer_evidence("foreign", 0, &store.state.evidence_digest().unwrap())
                .is_err()
        );
    }

    use super::*;

    fn assert_lifecycle_counts(store: &Store, commands: usize, answers: usize, incomplete: usize) {
        let snapshot = store.state.snapshot();
        assert_eq!(snapshot["generation"], store.state.generation);
        assert_eq!(snapshot["sequence"], store.state.sequence);
        assert_eq!(snapshot["commandCount"], commands);
        assert_eq!(snapshot["answerCount"], answers);
        assert_eq!(snapshot["unconfirmedWrites"], incomplete);
        assert_eq!(snapshot["unknownOperations"], incomplete);
    }

    #[test]
    fn lifecycle_snapshot_completed_receipts_count_zero_after_reload() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state");
        let old = uuid::Uuid::new_v4().to_string();
        let new = uuid::Uuid::new_v4().to_string();
        let mut store = Store::open(&path, "fixture", &old).unwrap();
        assert_lifecycle_counts(&store, 0, 0, 0);
        for (id, response) in [
            ("success", json!({"id":"success","result":{}})),
            ("failure", json!({"id":"failure","error":{"code":"failed"}})),
            (
                "evicted",
                json!({"id":"evicted","result":"x".repeat(MAX_FRAME)}),
            ),
        ] {
            let key = format!("s:{id}");
            store
                .state
                .reserve(key.clone(), &json!({"id":id}), false)
                .unwrap();
            store.state.push(response).unwrap();
            assert_eq!(store.state.receipt(&key, false).state, "completed");
        }
        assert_eq!(
            store.state.receipt("s:evicted", false).response.unwrap()["error"]["code"],
            "result_evicted"
        );
        // A command ID and an answer ID occupy independent receipt maps.
        store
            .state
            .reserve(
                "s:success".into(),
                &json!({"id":"success","result":{}}),
                true,
            )
            .unwrap();
        store.state.complete(
            "s:success",
            json!({"id":"success","result":{"written":true}}),
            true,
        );
        assert_lifecycle_counts(&store, 3, 1, 0);
        assert!(!store.state.busy());
        store.save().unwrap();
        let mut cold = Store::open(&path, "fixture", &new).unwrap();
        cold.begin_generation(&new).unwrap();
        assert!(
            cold.state
                .commands
                .values()
                .chain(cold.state.answers.values())
                .all(|r| r.completed && r.reply.is_none() && r.digest.is_none())
        );
        assert_lifecycle_counts(&cold, 3, 1, 0);
        assert!(!cold.state.busy());
    }

    #[test]
    fn lifecycle_snapshot_retains_unknown_receipts_across_generations_and_reads() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state");
        let old = uuid::Uuid::new_v4().to_string();
        let new = uuid::Uuid::new_v4().to_string();
        let mut store = Store::open(&path, "fixture", &old).unwrap();
        for answer in [false, true] {
            store
                .state
                .reserve("s:shared".into(), &json!({"id":"shared"}), answer)
                .unwrap();
            store
                .state
                .reserve("s:done".into(), &json!({"id":"done"}), answer)
                .unwrap();
            store
                .state
                .complete("s:done", json!({"id":"done","result":{}}), answer);
        }
        assert_lifecycle_counts(&store, 2, 2, 2);
        store.save().unwrap();
        let mut cold = Store::open(&path, "fixture", &new).unwrap();
        cold.begin_generation(&new).unwrap();
        assert_lifecycle_counts(&cold, 2, 2, 2);
        for answer in [false, true] {
            let receipt = cold.state.receipt("s:shared", answer);
            assert_eq!(receipt.state, "unknown");
            assert_eq!(receipt.generation, old);
            cold.state
                .complete("s:shared", json!({"id":"shared","result":{}}), answer);
        }
        let read = json!({"id":"read","method":"thread/read","params":{"threadId":"thread","includeTurns":true}});
        cold.state.reserve("s:read".into(), &read, false).unwrap();
        cold.note_read(&read);
        cold.state
            .reserve("n:77".into(), &json!({"id":77,"result":{}}), true)
            .unwrap();
        assert_lifecycle_counts(&cold, 3, 3, 4);
        cold.observe_native(json!({"id":"read","result":{"thread":{"id":"thread","turns":[]}}}))
            .unwrap();
        assert_lifecycle_counts(&cold, 3, 3, 3);
        cold.state
            .complete("n:77", json!({"id":77,"result":{"written":true}}), true);
        assert_lifecycle_counts(&cold, 3, 3, 2);
        assert!(cold.state.reconciliation_required && cold.state.busy());
        cold.save().unwrap();
        assert_lifecycle_counts(&Store::open(&path, "fixture", &new).unwrap(), 3, 3, 2);
    }

    #[test]
    fn lifecycle_snapshot_keeps_runs_and_questions_separate_from_receipts() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state");
        let old = uuid::Uuid::new_v4().to_string();
        let new = uuid::Uuid::new_v4().to_string();
        let mut store = Store::open(&path, "fixture", &old).unwrap();
        store.state.push(json!({"method":"turn/started","params":{"threadId":"thread","turn":{"id":"turn"}}})).unwrap();
        store
            .state
            .push(json!({"id":77,"method":"item/tool/requestUserInput","params":{}}))
            .unwrap();
        assert_lifecycle_counts(&store, 0, 0, 0);
        assert!(store.state.busy());
        store.save().unwrap();
        let mut cold = Store::open(&path, "fixture", &new).unwrap();
        cold.begin_generation(&new).unwrap();
        let snapshot = cold.state.snapshot();
        assert_eq!(snapshot["runs"][0]["state"], "unknown");
        assert_eq!(snapshot["pendingRequests"][0]["state"], "unknown");
        assert_lifecycle_counts(&cold, 0, 0, 0);
        assert!(cold.state.reconciliation_required && cold.state.busy());
    }

    #[test]
    fn lifecycle_snapshot_legacy_migration_preserves_completion_evidence() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state");
        let old = uuid::Uuid::new_v4().to_string();
        let new = uuid::Uuid::new_v4().to_string();
        let records = json!({
            "s:done":{"generation":old,"digest":null,"reply":{"id":"done","result":{}}},
            "s:unknown":{"generation":old,"digest":null,"reply":null}
        });
        let legacy = json!({"schema":1,"runtime_id":"fixture","generation":old,"sequence":0,
            "events":[],"commands":records,"answers":records,"runs":{},"pending":{},"reconciliation_required":false});
        pocket_agent::storage::atomic_write(&path, &serde_json::to_vec(&legacy).unwrap()).unwrap();
        let mut store = Store::open(&path, "fixture", &new).unwrap();
        store.begin_generation(&new).unwrap();
        assert_lifecycle_counts(&store, 2, 2, 2);
        assert_eq!(store.state.receipt("s:done", false).state, "completed");
        assert_eq!(store.state.receipt("s:done", true).state, "completed");
        assert_lifecycle_counts(&Store::open(&path, "fixture", &new).unwrap(), 2, 2, 2);
    }

    #[test]
    fn cold_run_requires_correlated_native_identity_and_keeps_unknown_commands_answers() {
        for variant in [
            "wrong-id",
            "wrong-thread",
            "active",
            "duplicate-turn",
            "native-error",
            "valid",
        ] {
            let dir = tempfile::tempdir().unwrap();
            let old = uuid::Uuid::new_v4().to_string();
            let current = uuid::Uuid::new_v4().to_string();
            let mut store =
                Store::open(&dir.path().join("evidence"), "fixture-runtime", &old).unwrap();
            store.state.push(json!({"method":"turn/started","params":{"threadId":"thread","turn":{"id":"turn"}}})).unwrap();
            store.begin_generation(&current).unwrap();
            let read = json!({"id":"read","method":"thread/read","params":{"threadId":"thread","includeTurns":true}});
            store.state.reserve("s:read".into(), &read, false).unwrap();
            store.note_read(&read);
            let mut response = json!({"id":"read","result":{"thread":{"id":"thread","turns":[{"id":"turn","status":"completed"}]}}});
            match variant {
                "wrong-id" => response["id"] = json!("unrelated"),
                "wrong-thread" => response["result"]["thread"]["id"] = json!("other"),
                "active" => {
                    response["result"]["thread"]["turns"][0]["status"] = json!("inProgress")
                }
                "duplicate-turn" => response["result"]["thread"]["turns"]
                    .as_array_mut()
                    .unwrap()
                    .push(json!({"id":"turn","status":"completed"})),
                "native-error" => response = json!({"id":"read","error":{"code":"read_failed"}}),
                _ => {}
            }
            store.observe_native(response).unwrap();
            assert_eq!(
                store.state.runs.values().next().unwrap().state,
                if variant == "valid" {
                    "completed"
                } else {
                    "unknown"
                },
                "{variant}"
            );
            assert_eq!(
                store.state.reconciliation_required,
                variant != "valid",
                "{variant}"
            );
        }
        let dir = tempfile::tempdir().unwrap();
        let old = uuid::Uuid::new_v4().to_string();
        let new = uuid::Uuid::new_v4().to_string();
        let path = dir.path().join("evidence");
        let mut store = Store::open(&path, "runtime", &old).unwrap();
        store.state.push(json!({"method":"turn/started","params":{"threadId":"thread","turn":{"id":"turn"}}})).unwrap();
        store
            .state
            .reserve(
                "s:unknown-command".into(),
                &json!({"id":"unknown-command"}),
                false,
            )
            .unwrap();
        store
            .state
            .reserve("n:77".into(), &json!({"id":77,"result":{}}), true)
            .unwrap();
        store.begin_generation(&new).unwrap();
        let request = json!({"id":"read","method":"thread/read","params":{"threadId":"thread","includeTurns":true}});
        store
            .state
            .reserve("s:read".into(), &request, false)
            .unwrap();
        store.note_read(&request);
        store.observe_native(json!({"id":"read","result":{"thread":{"id":"thread","turns":[{"id":"turn","status":"completed"}]}}})).unwrap();
        assert_eq!(store.state.runs.values().next().unwrap().state, "completed");
        assert!(store.state.reconciliation_required && store.state.busy());
        store.save().unwrap();
        assert!(Store::open(&path, "runtime", &new).unwrap().state.busy());
    }
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
