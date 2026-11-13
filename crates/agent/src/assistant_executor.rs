//! Product native protocol adapter for AssistantService. Host lifecycle and
//! transport stay behind RuntimeIo; only entrusted threads may be mutated.
use crate::assistant::*;
use anyhow::{Context, Result, ensure};
use rusqlite::{Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs::{File, OpenOptions},
    path::{Path, PathBuf},
    time::Duration,
};
#[path = "assistant_executor/events.rs"]
mod events;
#[path = "assistant_executor/permissions.rs"]
mod permissions;
#[path = "assistant_executor/protocol.rs"]
mod protocol;
#[path = "assistant_executor/shared.rs"]
mod shared;
pub(crate) use permissions::project_permission_reader;
pub use permissions::{
    ProjectPermissionProof, ProjectPermissionReader, register_project_permission_reader,
};
pub use protocol::dynamic_tools;
pub(crate) use protocol::normalize_input_answers;
pub use shared::SharedHostExecutor;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RuntimeHealth {
    pub runtime_id: String,
    pub generation: String,
    pub online: bool,
    pub initialized: bool,
    pub protocol_verified: bool,
    pub reconciliation_required: bool,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RuntimeReceipt {
    pub generation: String,
    pub state: String,
    pub response: Option<Value>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RuntimeEvent {
    pub sequence: u64,
    pub message: Value,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RuntimeEvents {
    pub generation: String,
    pub next_after: u64,
    pub snapshot_required: bool,
    pub events: Vec<RuntimeEvent>,
    pub pending_requests: Vec<Value>,
}
pub trait RuntimeIo {
    fn supports(&self, _method: &str, _parameters: &Value) -> bool {
        false
    }
    fn request(&mut self, stable_id: Value, method: &str, params: Value) -> Result<Value>;
    fn receipt(&mut self, stable_id: Value) -> Result<RuntimeReceipt>;
    fn answer(&mut self, native_id: Value, result: Value) -> Result<()>;
    fn events(&mut self, after: u64, limit: usize) -> Result<RuntimeEvents>;
    fn health(&mut self) -> Result<RuntimeHealth>;
    fn project_permissions(
        &mut self,
        _: &str,
        _: &str,
        _: &Path,
    ) -> Result<ProjectPermissionProof> {
        anyhow::bail!("Verified source project permissions unavailable")
    }
}
/// RuntimeIo emits this only before native dispatch when the complete params
/// fail the authenticated Capabilities check. Never infer it from error text.
#[derive(Clone, Debug)]
pub struct RuntimeUnsupported {
    pub method: String,
}
impl std::fmt::Display for RuntimeUnsupported {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Unsupported native assistant operation: {}", self.method)
    }
}
impl std::error::Error for RuntimeUnsupported {}
#[derive(Clone, Debug)]
pub struct DirectoryGrant {
    pub root: PathBuf,
    pub writable: bool,
}
#[derive(Clone, Debug)]
pub struct ExecutorPolicy {
    pub coordinator_directory: PathBuf,
    pub grants: Vec<DirectoryGrant>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct OperationRecord {
    pub operation_id: String,
    pub generation: String,
    pub role: Role,
    pub directory: PathBuf,
    pub writes: bool,
    pub fingerprint: String,
    pub thread_rpc_id: Value,
    pub turn_rpc_id: Value,
    pub thread_submitted: bool,
    pub turn_submitted: bool,
    pub thread_id: Option<String>,
    pub mapping: Option<NativeMapping>,
    pub rejected: bool,
    #[serde(default)]
    pub tool_revision: u32,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
struct ThreadOwner {
    role: Role,
    directory: PathBuf,
    writes: bool,
    generation: String,
    #[serde(default)]
    current_operation: Option<String>,
    #[serde(default)]
    tool_revision: u32,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
struct Effect {
    fingerprint: String,
    accepted: bool,
    #[serde(default)]
    rejected: bool,
}
#[derive(Default, Serialize, Deserialize)]
struct Durable {
    runtime_id: String,
    generation: String,
    cursor: u64,
    operations: BTreeMap<String, OperationRecord>,
    threads: BTreeMap<String, ThreadOwner>,
    effects: BTreeMap<String, Effect>,
    answers: BTreeMap<String, Effect>,
}
#[derive(Clone, Debug)]
pub struct PendingRequest {
    pub operation_id: String,
    pub mapping: NativeMapping,
    pub native_id: Value,
    pub handle: String,
    pub method: String,
    pub params: Value,
    pub call_id: Option<String>,
}
#[derive(Clone, Debug)]
pub struct StatusObservation {
    pub operation_id: String,
    pub status: ExecutionStatus,
    pub event_id: String,
}
#[derive(Clone, Debug)]
pub struct PendingToolCall {
    pub operation_id: String,
    pub scope: NativeMapping,
    pub native_id: Value,
    pub call_id: String,
    pub call: Option<ToolCall>,
    pub error: Option<String>,
}
#[derive(Clone, Debug, Default)]
pub struct EventProjection {
    pub observations: Vec<StatusObservation>,
    pub tools: Vec<PendingToolCall>,
    /// Calls absent from a successfully read authoritative pending snapshot.
    pub resolved_tools: Vec<(NativeMapping, String)>,
    pub pending_snapshot: bool,
}

pub struct HostExecutor<R: RuntimeIo> {
    runtime: R,
    policy: ExecutorPolicy,
    db: Connection,
    state: Durable,
    pending: BTreeMap<String, PendingRequest>,
    statuses: BTreeMap<String, ExecutionStatus>,
    event_gap: bool,
    _owner: File,
}
impl<R: RuntimeIo> HostExecutor<R> {
    pub fn open(path: &Path, mut runtime: R, mut policy: ExecutorPolicy) -> Result<Self> {
        policy.coordinator_directory = canonical(&policy.coordinator_directory)?;
        ensure!(policy.grants.len() <= 64, "Too many directory grants");
        for grant in &mut policy.grants {
            grant.root = canonical(&grant.root)?;
        }
        let path = if path.exists() {
            dunce::canonicalize(path)?
        } else {
            dunce::canonicalize(path.parent().context("Database parent required")?)?
                .join(path.file_name().context("Database filename required")?)
        };
        let owner = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(path.with_extension("executor-lock"))?;
        fs2::FileExt::try_lock_exclusive(&owner).context("HostExecutor already owned")?;
        let db = Connection::open(path)?;
        db.busy_timeout(Duration::from_secs(5))?;
        db.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; CREATE TABLE IF NOT EXISTS assistant_executor_state (singleton INTEGER PRIMARY KEY CHECK(singleton=1),data TEXT NOT NULL)")?;
        let saved: Option<String> = db
            .query_row(
                "SELECT data FROM assistant_executor_state WHERE singleton=1",
                [],
                |r| r.get(0),
            )
            .optional()?;
        let mut state: Durable = saved
            .map(|v| serde_json::from_str(&v))
            .transpose()?
            .unwrap_or_default();
        let health = runtime.health()?;
        valid_health(&health)?;
        if state.runtime_id.is_empty() {
            state.runtime_id = health.runtime_id.clone();
            state.generation = health.generation.clone();
        }
        // Persisted ownership belongs to this exact Host, never another profile.
        ensure!(
            state.runtime_id == health.runtime_id,
            "Runtime profile identity mismatch"
        );
        let instance = Self {
            runtime,
            policy,
            db,
            state,
            pending: BTreeMap::new(),
            statuses: BTreeMap::new(),
            event_gap: false,
            _owner: owner,
        };
        instance.save()?;
        Ok(instance)
    }
    pub fn operation_records(&self) -> Vec<OperationRecord> {
        self.state.operations.values().cloned().collect()
    }
    pub fn pending_requests(&self) -> Vec<PendingRequest> {
        self.pending.values().cloned().collect()
    }
    pub fn cursor(&self) -> u64 {
        self.state.cursor
    }
    pub fn runtime_mut(&mut self) -> &mut R {
        &mut self.runtime
    }
    /// Trusted controller only: root must come from an authorized native thread
    /// cwd read, never phone path JSON. writable=true is explicit current-user
    /// write authorization. Grants are local to this worker lifetime.
    pub fn authorize_directory(&mut self, root: &Path, writable: bool) -> Result<()> {
        let root = canonical(root)?;
        if let Some(grant) = self
            .policy
            .grants
            .iter_mut()
            .find(|grant| within(&root, &grant.root) && within(&grant.root, &root))
        {
            if writable {
                grant.writable = true;
            }
            return Ok(());
        }
        ensure!(
            self.policy.grants.len() < 64,
            "Directory authorization limit reached"
        );
        self.policy.grants.push(DirectoryGrant { root, writable });
        Ok(())
    }
    /// Replace locally verified grants after project authority is re-read.
    /// Unlike additions, this can revoke a previous write grant.
    pub fn replace_directory_grants(&mut self, grants: Vec<DirectoryGrant>) -> Result<()> {
        ensure!(grants.len() <= 64, "Directory authorization limit reached");
        let mut verified: Vec<DirectoryGrant> = vec![];
        for grant in grants {
            let root = canonical(&grant.root)?;
            if let Some(old) = verified.iter_mut().find(|g| g.root == root) {
                old.writable |= grant.writable;
            } else {
                verified.push(DirectoryGrant {
                    root,
                    writable: grant.writable,
                });
            }
        }
        self.policy.grants = verified;
        Ok(())
    }
    /// Main may reject a validation error only before any native answer attempt.
    pub fn has_tool_answer_attempt(&self, scope: &NativeMapping, call_id: &str) -> Result<bool> {
        let Some(p) = self
            .pending
            .values()
            .find(|p| p.mapping == *scope && p.call_id.as_deref() == Some(call_id))
        else {
            return Ok(true);
        };
        Ok(self
            .state
            .answers
            .contains_key(&hash(&(&p.mapping, &p.native_id))?))
    }
    fn save(&self) -> Result<()> {
        self.db.execute("INSERT INTO assistant_executor_state(singleton,data) VALUES(1,?1) ON CONFLICT(singleton) DO UPDATE SET data=excluded.data", [serde_json::to_string(&self.state)?])?;
        Ok(())
    }
    fn healthy(&mut self, expected: &str, writing: bool) -> Result<RuntimeHealth> {
        let h = self.runtime.health()?;
        valid_health(&h)?;
        ensure!(
            h.runtime_id == self.state.runtime_id
                && h.generation == expected
                && h.generation == self.state.generation,
            "Host generation/profile mismatch"
        );
        ensure!(
            h.online && h.initialized && h.protocol_verified,
            "Host offline, uninitialized or protocol unverified"
        );
        if writing {
            ensure!(
                !h.reconciliation_required && !self.event_gap,
                "Host/event stream requires reconciliation before new effects"
            );
        }
        Ok(h)
    }
    fn effect(&mut self, id: Value, method: &str, params: Value) -> Result<Value> {
        let key = native_key(&id)?;
        let hash = hash(&(method, &params))?;
        if let Some(previous) = self.state.effects.get(&key) {
            ensure!(
                previous.fingerprint == hash,
                "Stable native ID reused with different request"
            );
            return self
                .completed_receipt(&id)?
                .context("Native effect unresolved; never resend");
        }
        self.state.effects.insert(
            key.clone(),
            Effect {
                fingerprint: hash,
                accepted: false,
                rejected: false,
            },
        );
        self.save()?;
        let result = match self.runtime.request(id.clone(), method, params) {
            Ok(value) => value,
            Err(error) => {
                if error.downcast_ref::<RuntimeUnsupported>().is_some() {
                    self.state.effects.get_mut(&key).unwrap().rejected = true;
                    for op in self.state.operations.values_mut().filter(|op| {
                        op.mapping.is_none() && (op.thread_rpc_id == id || op.turn_rpc_id == id)
                    }) {
                        op.rejected = true;
                    }
                    self.save()?;
                }
                return Err(error);
            }
        };
        bounded_json(&result)?;
        self.state.effects.get_mut(&key).unwrap().accepted = true;
        self.save()?;
        Ok(result)
    }
    fn query(&mut self, method: &str, params: Value) -> Result<Value> {
        let id = json!(format!("assistant-read:{}", uuid::Uuid::new_v4()));
        let value = self.runtime.request(id, method, params)?;
        bounded_json(&value)?;
        Ok(value)
    }
    fn completed_receipt(&mut self, id: &Value) -> Result<Option<Value>> {
        let Some(response) = self.completed_envelope(id)? else {
            return Ok(None);
        };
        if let Some(error) = response.get("error") {
            anyhow::bail!("Native rejected request: {error}");
        }
        Ok(response.get("result").cloned())
    }

    fn completed_envelope(&mut self, id: &Value) -> Result<Option<Value>> {
        let receipt = self.runtime.receipt(id.clone())?;
        ensure!(
            receipt.generation == self.state.generation,
            "Receipt generation mismatch"
        );
        ensure!(
            matches!(
                receipt.state.as_str(),
                "missing" | "submitted" | "unknown" | "completed"
            ),
            "Invalid receipt state"
        );
        ensure!(
            receipt.response.is_none() || receipt.state == "completed",
            "Receipt payload without completion"
        );
        if receipt.state != "completed" {
            return Ok(None);
        }
        let Some(response) = receipt.response else {
            return Ok(None);
        };
        bounded_json(&response)?;
        ensure!(response["id"] == *id, "Receipt native ID mismatch");
        ensure!(
            response.is_object()
                && (response.get("result").is_some() ^ response.get("error").is_some()),
            "Invalid receipt envelope"
        );
        Ok(Some(response))
    }
    fn dispatch(&mut self, req: StartRequest, resume: bool) -> Result<NativeMapping> {
        small(&req.operation_id)?;
        small(&req.host_generation)?;
        bounded_text(&req.prompt, 65536)?;
        bounded_text(&req.instructions, 131072)?;
        req.settings.validate()?;
        self.healthy(&req.host_generation, true)?;
        ensure!(
            req.thread_id.is_some() == resume,
            "Start/resume thread mismatch"
        );
        let directory = match req.role {
            Role::Coordinator => {
                ensure!(
                    !req.writes && req.directory.is_none(),
                    "Coordinator must be read-only"
                );
                self.policy.coordinator_directory.clone()
            }
            Role::Execution => {
                let directory = canonical(
                    req.directory
                        .as_ref()
                        .context("Execution directory required")?,
                )?;
                ensure!(
                    self.policy
                        .grants
                        .iter()
                        .any(|g| within(&directory, &g.root)
                            && (!req.writes || g.writable)
                            && canonical(&g.root).is_ok_and(|actual| actual == g.root)),
                    "Execution directory/write not authorized"
                );
                directory
            }
        };
        let fingerprint = request_hash(&req)?;
        if let Some(saved) = self.state.operations.get(&req.operation_id) {
            ensure!(
                saved.fingerprint == fingerprint,
                "Service operation ID reused with different input"
            );
            if let Some(mapping) = &saved.mapping {
                return Ok(mapping.clone());
            }
            return self
                .recover_mapping(&req.operation_id)?
                .context("Original start unresolved; do not resubmit");
        }
        if let Some(thread) = &req.thread_id {
            let owner = self
                .state
                .threads
                .get(thread)
                .context("Unmanaged thread is observation-only")?;
            ensure!(
                owner.generation == req.host_generation
                    && owner.role == req.role
                    && owner.directory == directory
                    && owner.writes == req.writes,
                "Thread role/directory/permission changed"
            );
            if let Some(previous) = owner.current_operation.clone() {
                let mapping = self.state.operations[&previous]
                    .mapping
                    .clone()
                    .context("Previous turn mapping unresolved")?;
                let status = self.read_exact_status(&previous, &mapping)?;
                ensure!(
                    matches!(
                        status,
                        ExecutionStatus::Completed(..)
                            | ExecutionStatus::Failed(..)
                            | ExecutionStatus::Interrupted(..)
                    ),
                    "Previous turn is not proven stopped; resume could steer it"
                );
            }
            self.require_idle_thread(thread)?;
        }
        let model = self.select_model(req.settings.model.as_deref())?;
        let thread_rpc_id = rpc_id(&req.operation_id, "thread");
        let turn_rpc_id = rpc_id(&req.operation_id, "turn");
        let tool_revision = req
            .thread_id
            .as_ref()
            .map_or(1, |thread| self.state.threads[thread].tool_revision);
        let op = OperationRecord {
            operation_id: req.operation_id.clone(),
            generation: req.host_generation.clone(),
            role: req.role,
            directory: directory.clone(),
            writes: req.writes,
            fingerprint,
            thread_rpc_id: thread_rpc_id.clone(),
            turn_rpc_id: turn_rpc_id.clone(),
            thread_submitted: false,
            turn_submitted: false,
            thread_id: req.thread_id.clone(),
            mapping: None,
            rejected: false,
            tool_revision,
        };
        self.state.operations.insert(req.operation_id.clone(), op);
        self.save()?;
        self.state
            .operations
            .get_mut(&req.operation_id)
            .unwrap()
            .thread_submitted = true;
        self.save()?;
        let thread_params = protocol::thread_params(&req, &directory, &model, resume);
        let result = self.effect(
            thread_rpc_id,
            if resume {
                "thread/resume"
            } else {
                "thread/start"
            },
            thread_params,
        )?;
        ensure!(
            result["model"].as_str() == Some(model.as_str()),
            "Native changed the verified model"
        );
        ensure!(
            result["approvalPolicy"] == "on-request" && result["approvalsReviewer"] == "user",
            "Native did not retain manual approval policy"
        );
        ensure!(
            result["sandbox"]["type"]
                == if req.writes {
                    "workspaceWrite"
                } else {
                    "readOnly"
                },
            "Native did not retain assistant sandbox mode"
        );
        let native_cwd = PathBuf::from(
            result["cwd"]
                .as_str()
                .context("Native thread cwd missing")?,
        );
        ensure!(
            canonical(&native_cwd)? == directory,
            "Native thread cwd changed"
        );
        let thread = result["thread"]["id"]
            .as_str()
            .context("thread/start/resume missing actual native id")?
            .to_owned();
        small(&thread)?;
        if let Some(expected) = &req.thread_id {
            ensure!(*expected == thread, "Resumed another native thread");
        }
        if let Some(existing) = self.state.threads.get(&thread) {
            ensure!(
                resume && existing.role == req.role,
                "thread/start returned already owned thread"
            );
        }
        self.state.threads.insert(
            thread.clone(),
            ThreadOwner {
                role: req.role,
                directory: directory.clone(),
                writes: req.writes,
                generation: req.host_generation.clone(),
                current_operation: None,
                tool_revision,
            },
        );
        self.state
            .operations
            .get_mut(&req.operation_id)
            .unwrap()
            .thread_id = Some(thread.clone());
        self.save()?;
        self.healthy(&req.host_generation, true)?;
        ensure!(
            canonical(&directory)? == directory,
            "Directory changed before native turn"
        );
        if resume {
            self.require_idle_thread(&thread)?;
        }
        self.state
            .operations
            .get_mut(&req.operation_id)
            .unwrap()
            .turn_submitted = true;
        self.save()?;
        let result = self.effect(
            turn_rpc_id,
            "turn/start",
            protocol::turn_params(&req, &thread, &directory, &model),
        )?;
        let turn = result["turn"]["id"]
            .as_str()
            .context("turn/start missing actual native id")?
            .to_owned();
        small(&turn)?;
        let mapping = NativeMapping {
            host_generation: req.host_generation,
            thread_id: thread,
            turn_id: turn,
        };
        self.accept_mapping(&req.operation_id, mapping.clone())?;
        Ok(mapping)
    }
    fn accept_mapping(&mut self, id: &str, mapping: NativeMapping) -> Result<()> {
        let op = self.state.operations.get(id).context("Unowned operation")?;
        ensure!(
            mapping.host_generation == op.generation
                && op.thread_id.as_deref() == Some(&mapping.thread_id),
            "Mapping identity mismatch"
        );
        ensure!(
            !self
                .state
                .operations
                .values()
                .any(|o| o.operation_id != id && o.mapping.as_ref() == Some(&mapping)),
            "Native turn already mapped"
        );
        self.state.operations.get_mut(id).unwrap().mapping = Some(mapping.clone());
        if let Some(owner) = self.state.threads.get_mut(&mapping.thread_id) {
            owner.current_operation = Some(id.into());
        }
        self.statuses
            .insert(id.into(), ExecutionStatus::Running(mapping));
        self.save()
    }
    fn recover_mapping(&mut self, id: &str) -> Result<Option<NativeMapping>> {
        let mut op = self
            .state
            .operations
            .get(id)
            .cloned()
            .context("Unowned operation")?;
        if let Some(mapping) = op.mapping {
            return Ok(Some(mapping));
        }
        if op.thread_id.is_none() && op.thread_submitted {
            if let Some(value) = self.completed_receipt(&op.thread_rpc_id)? {
                let thread = value["thread"]["id"]
                    .as_str()
                    .context("Missing thread id in receipt")?
                    .to_owned();
                small(&thread)?;
                op.thread_id = Some(thread.clone());
                self.state.threads.insert(
                    thread,
                    ThreadOwner {
                        role: op.role,
                        directory: op.directory.clone(),
                        writes: op.writes,
                        generation: op.generation.clone(),
                        current_operation: None,
                        tool_revision: op.tool_revision,
                    },
                );
                self.state.operations.insert(id.into(), op.clone());
                self.save()?;
            }
        }
        if op.turn_submitted {
            if let Some(value) = self.completed_receipt(&op.turn_rpc_id)? {
                let turn = value["turn"]["id"]
                    .as_str()
                    .context("Missing turn id in receipt")?
                    .to_owned();
                small(&turn)?;
                let mapping = NativeMapping {
                    host_generation: op.generation,
                    thread_id: op
                        .thread_id
                        .context("Turn receipt without thread evidence")?,
                    turn_id: turn,
                };
                self.accept_mapping(id, mapping.clone())?;
                return Ok(Some(mapping));
            }
        }
        Ok(None)
    }
    fn select_model(&mut self, requested: Option<&str>) -> Result<String> {
        let mut cursor: Option<String> = None;
        let mut seen = std::collections::BTreeSet::new();
        let mut default = None;
        for _ in 0..8 {
            let page = self.query(
                "model/list",
                json!({"cursor":cursor,"limit":100,"includeHidden":false}),
            )?;
            let entries = page["data"]
                .as_array()
                .context("Invalid native model directory")?;
            ensure!(entries.len() <= 100, "Oversize native model page");
            for entry in entries {
                if entry["hidden"].as_bool() != Some(false) {
                    continue;
                }
                let model = entry["model"]
                    .as_str()
                    .context("Native model name missing")?;
                small(model)?;
                if requested == Some(model)
                    || requested.is_some_and(|s| entry["id"].as_str() == Some(s))
                {
                    return Ok(model.into());
                }
                if entry["isDefault"].as_bool() == Some(true) {
                    default = Some(model.to_owned());
                }
            }
            cursor = page["nextCursor"].as_str().map(str::to_owned);
            if cursor.is_none() {
                break;
            }
            ensure!(
                seen.insert(cursor.clone()),
                "Native model pagination repeats"
            );
        }
        ensure!(
            requested.is_none(),
            "Requested model not in native directory"
        );
        default.context("No verified native default model")
    }
    fn answer_pending(&mut self, pending: PendingRequest, result: Value) -> Result<()> {
        self.healthy(&pending.mapping.host_generation, false)?;
        ensure!(
            self.state
                .threads
                .get(&pending.mapping.thread_id)
                .and_then(|owner| owner.current_operation.as_deref())
                == Some(pending.operation_id.as_str()),
            "Native answer targets an old turn"
        );
        let key = hash(&(&pending.mapping, &pending.native_id))?;
        let fingerprint = hash(&result)?;
        if let Some(old) = self.state.answers.get(&key) {
            ensure!(
                old.fingerprint == fingerprint,
                "Native answer reused with different content"
            );
            ensure!(old.accepted, "Native answer unresolved; never resend");
            return Ok(());
        }
        self.state.answers.insert(
            key.clone(),
            Effect {
                fingerprint,
                accepted: false,
                rejected: false,
            },
        );
        self.save()?;
        self.runtime.answer(pending.native_id, result)?;
        self.state.answers.get_mut(&key).unwrap().accepted = true;
        self.save()
    }
    fn attempt(&mut self, req: StartRequest, resume: bool) -> Result<NativeMapping> {
        let result = self.dispatch(req.clone(), resume);
        // A locally rejected preflight that made no native effect has positive
        // evidence of non-dispatch, rather than leaking an unreconcilable lease.
        if result.is_err()
            && !self.state.operations.contains_key(&req.operation_id)
            && small(&req.operation_id).is_ok()
            && self.healthy(&req.host_generation, false).is_ok()
        {
            self.state.operations.insert(
                req.operation_id.clone(),
                OperationRecord {
                    operation_id: req.operation_id.clone(),
                    generation: req.host_generation.clone(),
                    role: req.role,
                    directory: req
                        .directory
                        .clone()
                        .unwrap_or(self.policy.coordinator_directory.clone()),
                    writes: req.writes,
                    fingerprint: request_hash(&req)?,
                    thread_rpc_id: rpc_id(&req.operation_id, "thread"),
                    turn_rpc_id: rpc_id(&req.operation_id, "turn"),
                    thread_submitted: false,
                    turn_submitted: false,
                    thread_id: None,
                    mapping: None,
                    rejected: true,
                    tool_revision: 1,
                },
            );
            self.save()?;
        }
        result
    }
}
impl<R: RuntimeIo> Executor for HostExecutor<R> {
    fn needs_tool_upgrade(&self, thread: &str) -> Result<bool> {
        Ok(self
            .state
            .threads
            .get(thread)
            .context("Unmanaged thread")?
            .tool_revision
            < 1)
    }
    fn append_status(
        &mut self,
        id: &str,
        mapping: &NativeMapping,
        message: &str,
    ) -> Result<AppendOutcome> {
        self.healthy(&mapping.host_generation, false)?;
        let native_id = rpc_id(id, "append");
        let Some(effect) = self.state.effects.get(&native_key(&native_id)?) else {
            return Ok(AppendOutcome::Rejected);
        };
        let params = json!({"threadId":mapping.thread_id,"expectedTurnId":mapping.turn_id,"input":[{"type":"text","text":message,"text_elements":[]}]});
        ensure!(
            effect.fingerprint == hash(&("turn/steer", &params))?,
            "Append identity reused with different content"
        );
        if effect.rejected {
            return Ok(AppendOutcome::Rejected);
        }
        let Some(envelope) = self.completed_envelope(&native_id)? else {
            return Ok(AppendOutcome::Uncertain);
        };
        if envelope.get("error").is_some() {
            return Ok(AppendOutcome::Rejected);
        }
        Ok(if envelope["result"]["turnId"] == mapping.turn_id {
            AppendOutcome::Delivered
        } else {
            AppendOutcome::Uncertain
        })
    }
    fn append(
        &mut self,
        id: &str,
        mapping: &NativeMapping,
        message: &str,
    ) -> Result<AppendOutcome> {
        bounded_text(id, 512)?;
        bounded_text(message, 65536)?;
        self.healthy(&mapping.host_generation, true)?;
        let params = json!({"threadId":mapping.thread_id,"expectedTurnId":mapping.turn_id,"input":[{"type":"text","text":message,"text_elements":[]}]});
        let native_id = rpc_id(id, "append");
        let key = native_key(&native_id)?;
        if self.state.effects.contains_key(&key) {
            return self.append_status(id, mapping, message);
        }
        if !self.runtime.supports("turn/steer", &params) {
            return Ok(AppendOutcome::Unsupported);
        }
        let operation = self
            .state
            .operations
            .values()
            .find(|op| op.mapping.as_ref() == Some(mapping))
            .cloned()
            .context("Append target is not managed")?;
        ensure!(
            self.state
                .threads
                .get(&mapping.thread_id)
                .and_then(|t| t.current_operation.as_ref())
                == Some(&operation.operation_id),
            "Append target is not the current turn"
        );
        ensure!(
            operation.role == Role::Execution,
            "Only execution tasks accept targeted input"
        );
        ensure!(
            self.policy
                .grants
                .iter()
                .any(|grant| within(&operation.directory, &grant.root)
                    && (!operation.writes || grant.writable)
                    && canonical(&grant.root).is_ok_and(|root| root == grant.root)),
            "Append directory/write authorization changed"
        );
        let value = match self.effect(native_id, "turn/steer", params) {
            Ok(value) => value,
            Err(error) if error.downcast_ref::<RuntimeUnsupported>().is_some() => {
                return Ok(AppendOutcome::Rejected);
            }
            Err(error) => return Err(error),
        };
        ensure!(
            value["turnId"] == mapping.turn_id,
            "Append receipt targets another turn"
        );
        Ok(AppendOutcome::Delivered)
    }
    fn can_resume(
        &mut self,
        thread: &str,
        role: Role,
        directory: Option<&Path>,
        writes: bool,
    ) -> Result<bool> {
        let Some(owner) = self.state.threads.get(thread).cloned() else {
            return Ok(false);
        };
        let Some(directory) = directory else {
            return Ok(false);
        };
        if owner.generation != self.state.generation
            || owner.role != role
            || owner.directory != canonical(directory)?
            || owner.writes != writes
            || owner.tool_revision < 1
        {
            return Ok(false);
        }
        if let Some(id) = owner.current_operation {
            let Some(mapping) = self
                .state
                .operations
                .get(&id)
                .and_then(|o| o.mapping.clone())
            else {
                return Ok(false);
            };
            if !matches!(
                self.read_exact_status(&id, &mapping)?,
                ExecutionStatus::Completed(..)
                    | ExecutionStatus::Failed(..)
                    | ExecutionStatus::Interrupted(..)
            ) {
                return Ok(false);
            }
        }
        self.require_idle_thread(thread)?;
        Ok(true)
    }
    fn start(&mut self, req: StartRequest) -> Result<NativeMapping> {
        self.attempt(req, false)
    }
    fn resume(&mut self, req: StartRequest) -> Result<NativeMapping> {
        self.attempt(req, true)
    }
    fn read(&mut self, req: ReadRequest) -> Result<Vec<SessionView>> {
        let generation = self.state.generation.clone();
        self.healthy(&generation, false)?;
        match req {
            ReadRequest::Sessions { limit } => {
                ensure!((1..=100).contains(&limit), "Invalid read limit");
                let value = self.query("thread/list", json!({"limit":limit,"archived":false}))?;
                value["data"]
                    .as_array()
                    .context("Invalid thread list")?
                    .iter()
                    .take(limit)
                    .map(protocol::session_view)
                    .collect()
            }
            ReadRequest::Thread { thread_id, limit } => {
                small(&thread_id)?;
                ensure!((1..=100).contains(&limit), "Invalid read limit");
                let meta = self.query(
                    "thread/read",
                    json!({"threadId":thread_id,"includeTurns":false}),
                )?;
                let mut view = protocol::session_view(&meta["thread"])?;
                ensure!(view.thread_id == thread_id, "Read thread identity mismatch");
                let turns=self.query("thread/turns/list",json!({"threadId":thread_id,"limit":limit,"sortDirection":"desc","itemsView":"full"}))?;
                let text = protocol::turn_summaries(&turns)?;
                if !text.is_empty() {
                    view.summary = text;
                }
                Ok(vec![view])
            }
        }
    }
    fn status(&mut self, req: StatusRequest) -> Result<ExecutionStatus> {
        let Some(op) = self.state.operations.get(&req.operation_id).cloned() else {
            return Ok(ExecutionStatus::Unknown);
        };
        if op.generation != req.host_generation || self.healthy(&op.generation, false).is_err() {
            return Ok(ExecutionStatus::Unknown);
        };
        if self.event_gap {
            return Ok(ExecutionStatus::Unknown);
        }
        if op.rejected {
            return Ok(ExecutionStatus::NotDispatched);
        }
        if let Some(supplied) = &req.mapping {
            ensure!(
                op.mapping.as_ref() == Some(supplied),
                "Supplied mapping not verified by executor"
            );
        }
        let recovered = self.recover_mapping(&req.operation_id);
        let mapping = match recovered {
            Ok(Some(m)) => m,
            Ok(None) => {
                if !op.turn_submitted && (op.thread_id.is_some() || !op.thread_submitted) {
                    return Ok(ExecutionStatus::NotDispatched);
                }
                return Ok(ExecutionStatus::Unknown);
            }
            Err(_) => return Ok(ExecutionStatus::Unknown),
        };
        if let Some(status) = self.statuses.get(&req.operation_id) {
            if matches!(
                status,
                ExecutionStatus::Completed(..)
                    | ExecutionStatus::Failed(..)
                    | ExecutionStatus::Interrupted(..)
            ) {
                return Ok(status.clone());
            }
        }
        self.read_exact_status(&req.operation_id, &mapping)
    }
    fn validate_response(&mut self, req: &ResponseRequest) -> Result<()> {
        small(&req.command_id)?;
        let p = self
            .pending
            .get(&req.request_id)
            .cloned()
            .context("Native request is not currently pending")?;
        ensure!(
            p.operation_id == req.operation_id && p.mapping == req.mapping && p.call_id.is_none(),
            "Answer target mismatch"
        );
        if self.state.operations[&p.operation_id].role == Role::Coordinator
            && matches!(req.response, HumanResponse::Approval { approved: true })
        {
            anyhow::bail!(
                "Read-only coordinator cannot receive permission expansion; entrust an execution task"
            );
        }
        protocol::human_response(&p, &req.response)?;
        ensure!(
            self.state
                .threads
                .get(&p.mapping.thread_id)
                .and_then(|owner| owner.current_operation.as_deref())
                == Some(p.operation_id.as_str()),
            "Native answer targets an old turn"
        );
        Ok(())
    }
    fn validate_tools(&mut self, response: &ToolResponse) -> Result<()> {
        let pending = self
            .pending
            .values()
            .find(|p| {
                p.mapping == response.scope && p.call_id.as_deref() == Some(&response.call_id)
            })
            .context("Dynamic call is not pending for this turn")?;
        ensure!(
            self.state
                .threads
                .get(&pending.mapping.thread_id)
                .and_then(|owner| owner.current_operation.as_deref())
                == Some(pending.operation_id.as_str()),
            "Dynamic answer targets an old turn"
        );
        protocol::tool_response(&response.result, true)?;
        Ok(())
    }
    fn respond(&mut self, req: ResponseRequest) -> Result<()> {
        self.validate_response(&req)?;
        let p = self
            .pending
            .get(&req.request_id)
            .cloned()
            .context("Native request is not currently pending")?;
        let response = protocol::human_response(&p, &req.response)?;
        self.answer_pending(p, response)
    }
    fn interrupt(&mut self, mapping: NativeMapping) -> Result<()> {
        self.healthy(&mapping.host_generation, false)?;
        let op = self
            .state
            .operations
            .values()
            .find(|o| o.mapping.as_ref() == Some(&mapping))
            .cloned()
            .context("Unmanaged turn is observation-only")?;
        self.effect(
            rpc_id(&op.operation_id, "interrupt"),
            "turn/interrupt",
            json!({"threadId":mapping.thread_id,"turnId":mapping.turn_id}),
        )?;
        Ok(())
    }
    fn tools(&mut self, response: ToolResponse) -> Result<()> {
        let p = self
            .pending
            .values()
            .find(|p| {
                p.mapping == response.scope && p.call_id.as_deref() == Some(&response.call_id)
            })
            .cloned()
            .context("Dynamic call is not pending for this turn")?;
        self.answer_pending(p, protocol::tool_response(&response.result, true)?)
    }
}
fn small(s: &str) -> Result<()> {
    bounded_text(s, 256)
}
fn bounded_text(s: &str, max: usize) -> Result<()> {
    ensure!(
        !s.is_empty() && s.len() <= max && !s.contains('\0'),
        "Invalid bounded text"
    );
    Ok(())
}
fn bounded_json(v: &Value) -> Result<()> {
    ensure!(
        serde_json::to_vec(v)?.len() <= 4 * 1024 * 1024,
        "Native payload too large"
    );
    Ok(())
}
fn hash<T: Serialize>(v: &T) -> Result<String> {
    Ok(format!("{:x}", Sha256::digest(serde_json::to_vec(v)?)))
}
fn request_hash(req: &StartRequest) -> Result<String> {
    hash(&(
        &req.operation_id,
        &req.host_generation,
        req.role,
        &req.thread_id,
        &req.prompt,
        &req.directory,
        req.writes,
        &req.settings,
        &req.instructions,
    ))
}
fn rpc_id(op: &str, phase: &str) -> Value {
    json!(format!(
        "assistant:{}:{phase}",
        format!("{:x}", Sha256::digest(op.as_bytes()))
    ))
}
fn native_key(v: &Value) -> Result<String> {
    match v {
        Value::String(s) => {
            small(s)?;
            Ok(format!("s:{s}"))
        }
        Value::Number(n) if n.is_i64() || n.is_u64() => Ok(format!("n:{n}")),
        _ => anyhow::bail!("Invalid native id"),
    }
}
fn canonical(p: &Path) -> Result<PathBuf> {
    ensure!(p.is_absolute(), "Path must be absolute");
    let p = dunce::canonicalize(p)?;
    ensure!(p.is_dir(), "Directory missing");
    Ok(p)
}
fn within(p: &Path, r: &Path) -> bool {
    #[cfg(windows)]
    {
        Path::new(&p.to_string_lossy().to_lowercase())
            .starts_with(Path::new(&r.to_string_lossy().to_lowercase()))
    }
    #[cfg(not(windows))]
    {
        p.starts_with(r)
    }
}
fn valid_health(h: &RuntimeHealth) -> Result<()> {
    small(&h.runtime_id)?;
    small(&h.generation)
}
#[cfg(test)]
#[path = "assistant_executor/tests.rs"]
mod tests;
