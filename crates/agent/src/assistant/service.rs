use super::*;
use anyhow::{Context, Result, ensure};
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs::{File, OpenOptions},
    path::{Path, PathBuf},
};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum OperationPhase {
    Unknown,
    Running,
    Waiting,
    Paused,
    Completed,
    Failed,
}
impl OperationPhase {
    fn live(&self) -> bool {
        matches!(self, Self::Unknown | Self::Running | Self::Waiting)
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Operation {
    pub id: String,
    pub role: Role,
    pub task_id: Option<String>,
    pub mapping: Option<NativeMapping>,
    pub expected_generation: String,
    pub phase: OperationPhase,
    pub suspended: bool,
    pub question: Option<UserQuestion>,
    /// A request_input tool is answered using its held dynamic-tool call, not
    /// fabricated as a native approval or native user-input request.
    #[serde(default)]
    pub input_tool_call: Option<String>,
    pub result: Option<String>,
    pub authorized_directories: Vec<PathBuf>,
    /// Durable coordinator handoff, without generated instructions/memory copies.
    #[serde(default)]
    pub prompt: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AssistantEvent {
    pub sequence: u64,
    pub id: String,
    pub operation_id: Option<String>,
    pub kind: String,
    pub detail: String,
    pub notify: bool,
    pub wake_coordinator: bool,
    pub at_ms: i64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct Inbox {
    id: String,
    message: String,
    directories: Vec<PathBuf>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
struct JournalEntry {
    fingerprint: String,
    result: serde_json::Value,
}
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
struct State {
    settings: Settings,
    coordinator_thread: Option<String>,
    coordinator_watermark: u64,
    operations: BTreeMap<String, Operation>,
    inbox: Vec<Inbox>,
    events: Vec<AssistantEvent>,
    journal: BTreeMap<String, JournalEntry>,
    schedules: BTreeMap<String, Schedule>,
    scheduled_commands: Vec<(String, Command)>,
    paused: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum ToolCall {
    ListSessions {
        limit: usize,
    },
    Progress {
        task_id: String,
    },
    CreateTask {
        prompt: String,
        directory: PathBuf,
        writes: bool,
    },
    PauseTask {
        task_id: String,
    },
    RequestInput {
        request_id: String,
        text: String,
    },
    Summarize {
        text: String,
    },
}

/// One serialized worker per profile; the file lock excludes concurrent service
/// owners, while SQLite leases also fence direct Store callers. Host owns all
/// actual native RPC and project permissions. No timers or background model loop.
pub struct AssistantService<E: Executor> {
    executor: E,
    store: AssistantStore,
    state: State,
    generation: String,
    opened_at: Option<i64>,
    _owner: File,
}
impl<E: Executor> AssistantService<E> {
    pub fn open(
        path: &Path,
        computer: &str,
        profile: &str,
        generation: &str,
        executor: E,
    ) -> Result<Self> {
        text(computer, 256)?;
        text(profile, 256)?;
        text(generation, 256)?;
        let identity = fingerprint(&(computer, profile))?;
        let canonical_parent =
            dunce::canonicalize(path.parent().context("Missing database parent")?)?;
        let path = if path.exists() {
            dunce::canonicalize(path)?
        } else {
            canonical_parent.join(path.file_name().context("Missing database filename")?)
        };
        let owner = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(path.with_extension(format!("{identity}.assistant-lock")))?;
        fs2::FileExt::try_lock_exclusive(&owner)
            .context("Assistant profile already has a service owner")?;
        let store = AssistantStore::open(&path, &identity)?;
        store.db.execute_batch("CREATE TABLE IF NOT EXISTS assistant_service_state (assistant_id TEXT PRIMARY KEY, data TEXT NOT NULL)")?;
        let saved: Option<String> = store
            .db
            .query_row(
                "SELECT data FROM assistant_service_state WHERE assistant_id=?1",
                [&store.assistant_id],
                |r| r.get(0),
            )
            .optional()?;
        let mut state: State = saved
            .map(|s| serde_json::from_str(&s))
            .transpose()?
            .unwrap_or_default();
        if store.taken_over()? {
            state.paused = true;
            state.inbox.clear();
            state.scheduled_commands.clear();
            state.coordinator_watermark = state.events.last().map_or(0, |e| e.sequence);
        }
        // Reopening does not imply native death. Reconcile original operation IDs
        // first, including the crash window between Store claim and service save.
        for op in state.operations.values_mut().filter(|o| o.phase.live()) {
            op.phase = OperationPhase::Unknown;
        }
        for task in store
            .tasks()?
            .into_iter()
            .filter(|t| t.active_run_id.is_some())
        {
            let id = task.active_run_id.clone().unwrap();
            let op = state.operations.entry(id.clone()).or_insert(Operation {
                id,
                role: Role::Execution,
                task_id: Some(task.id),
                mapping: None,
                expected_generation: generation.into(),
                phase: OperationPhase::Unknown,
                suspended: task.reconciliation_required,
                question: None,
                input_tool_call: None,
                result: None,
                authorized_directories: vec![],
                prompt: None,
            });
            op.suspended |= task.reconciliation_required || state.paused;
        }
        if state.paused {
            let mut store = store;
            for task in store.tasks()?.into_iter().filter(|t| {
                !matches!(
                    t.status,
                    TaskStatus::Done | TaskStatus::Failed | TaskStatus::Paused
                )
            }) {
                store.apply(
                    &format!("recover-pause:{}", task.id),
                    Command::PauseTask { task_id: task.id },
                )?;
            }
            for op in state.operations.values_mut().filter(|o| o.phase.live()) {
                op.suspended = true;
            }
            let service = Self {
                executor,
                store,
                state,
                generation: generation.into(),
                opened_at: None,
                _owner: owner,
            };
            service.save()?;
            return Ok(service);
        }
        let service = Self {
            executor,
            store,
            state,
            generation: generation.into(),
            opened_at: None,
            _owner: owner,
        };
        service.save()?;
        Ok(service)
    }

    pub fn assistant_id(&self) -> &str {
        self.store.assistant_id()
    }
    pub fn settings(&self) -> &Settings {
        &self.state.settings
    }
    pub fn is_paused(&self) -> bool {
        self.state.paused || self.store.taken_over().unwrap_or(true)
    }
    pub fn coordinator_thread_id(&self) -> Option<&str> {
        self.state.coordinator_thread.as_deref()
    }
    pub fn tasks(&self) -> Result<Vec<Task>> {
        self.store.tasks()
    }
    pub fn memories(&self) -> Result<Vec<Memory>> {
        self.store.memories()
    }
    pub fn operations(&self) -> Vec<Operation> {
        self.state.operations.values().cloned().collect()
    }
    pub fn schedules(&self) -> Vec<Schedule> {
        self.state.schedules.values().cloned().collect()
    }
    pub fn events(&self, after: u64, limit: usize) -> Result<Vec<AssistantEvent>> {
        ensure!(limit > 0 && limit <= 256, "Event page limit must be 1..256");
        Ok(self
            .state
            .events
            .iter()
            .filter(|e| e.sequence > after)
            .take(limit)
            .cloned()
            .collect())
    }
    /// Bounded newest page, in durable event order; does not mutate the cursor.
    pub fn latest_events(&self, limit: usize) -> Result<Vec<AssistantEvent>> {
        ensure!(limit > 0 && limit <= 256, "Event page limit must be 1..256");
        Ok(self.state.events[self.state.events.len().saturating_sub(limit)..].to_vec())
    }

    pub fn update_settings(&mut self, id: &str, settings: Settings, now: i64) -> Result<()> {
        settings.validate()?;
        clock(now)?;
        let hash = fingerprint(&settings)?;
        if self.replay(id, &hash)?.is_some() {
            return Ok(());
        }
        self.state.settings = settings;
        self.record(id, hash, serde_json::Value::Null);
        self.event(
            format!("settings:{id}"),
            None,
            "settings",
            "Assistant settings updated".into(),
            false,
            false,
            now,
        );
        self.save()
    }

    /// Explicit user/phone command. Direct controls/memory cannot be model tools.
    pub fn apply(&mut self, id: &str, command: Command, now: i64) -> Result<CommandReceipt> {
        clock(now)?;
        let hash = fingerprint(&command)?;
        if let Some(value) = self.replay(id, &hash)? {
            return Ok(serde_json::from_value(value)?);
        }
        let receipt = self.store.apply(id, command.clone())?;
        if matches!(command, Command::TakeOver) {
            self.state.paused = true;
            self.state.inbox.clear();
            self.state.coordinator_watermark = self.state.events.last().map_or(0, |e| e.sequence);
            self.state.scheduled_commands.clear();
            // Pausing all previously queued/waiting work is intentional. Release
            // is a gate change only; it cannot silently dispatch old work.
            for task in self.store.tasks()?.into_iter().filter(|t| {
                !matches!(
                    t.status,
                    TaskStatus::Done | TaskStatus::Failed | TaskStatus::Paused
                )
            }) {
                self.store.apply(
                    &format!("takeover:{id}:{}", task.id),
                    Command::PauseTask { task_id: task.id },
                )?;
            }
            for op in self
                .state
                .operations
                .values_mut()
                .filter(|o| o.phase.live())
            {
                op.suspended = true;
            }
        } else if matches!(command, Command::ReleaseTakeover) {
            self.state.paused = false;
        } else if let Command::PauseTask { task_id } = &command {
            for op in self
                .state
                .operations
                .values_mut()
                .filter(|o| o.task_id.as_ref() == Some(task_id) && o.phase.live())
            {
                op.suspended = true;
            }
        }
        self.record(id, hash, serde_json::to_value(&receipt)?);
        // Memory contents never enter this journal or event feed.
        self.event(
            format!("command:{id}"),
            None,
            "command",
            "User command applied".into(),
            false,
            false,
            now,
        );
        self.save()?;
        self.interrupt_suspended()?;
        Ok(receipt)
    }

    pub fn pause(&mut self, id: &str, now: i64) -> Result<()> {
        self.apply(id, Command::TakeOver, now).map(|_| ())
    }
    pub fn release_takeover(&mut self, id: &str, now: i64) -> Result<()> {
        self.apply(id, Command::ReleaseTakeover, now).map(|_| ())
    }

    pub fn submit(
        &mut self,
        id: &str,
        message: &str,
        directories: Vec<PathBuf>,
        now: i64,
    ) -> Result<()> {
        text(id, 256)?;
        text(message, 65536)?;
        clock(now)?;
        let hash = fingerprint(&(message, &directories))?;
        if self.replay(id, &hash)?.is_some() {
            return Ok(());
        }
        ensure!(
            !self.state.paused && !self.store.taken_over()?,
            "Assistant is paused"
        );
        ensure!(
            directories.len() <= 32 && self.state.inbox.len() < 128,
            "Assistant inbox or directory limit"
        );
        let directories = directories
            .iter()
            .map(|p| canonical_directory(p))
            .collect::<Result<Vec<_>>>()?;
        self.state.inbox.push(Inbox {
            id: id.into(),
            message: message.into(),
            directories,
        });
        self.record(id, hash, serde_json::Value::Null);
        self.event(
            format!("user:{id}"),
            None,
            "user_message",
            "Explicit user handoff queued".into(),
            false,
            false,
            now,
        );
        self.save()
    }

    /// Host supplies clock wakes; no idle model heartbeat. Work is bounded to
    /// three execution starts and one serialized coordinator start per tick.
    pub fn tick(&mut self, now: i64) -> Result<()> {
        clock(now)?;
        let opened_at = *self.opened_at.get_or_insert(now);
        // First reconcile uncertain accepted calls; never reconstruct dispatch
        // from a stored lease or retry start/resume after an error.
        let live: Vec<_> = self
            .state
            .operations
            .values()
            .filter(|o| o.phase.live())
            .cloned()
            .collect();
        for op in live {
            let status = self.executor.status(StatusRequest {
                operation_id: op.id.clone(),
                mapping: op.mapping.clone(),
                host_generation: op.expected_generation.clone(),
            });
            if let Ok(status) = status {
                let event_id = format!("status:{}:{}", op.id, fingerprint(&status)?);
                self.observe(&op.id, status, &event_id, now)?;
            }
        }
        self.interrupt_suspended()?;
        self.advance_schedules(now, opened_at)?;
        if self.state.paused || self.store.taken_over()? {
            return Ok(());
        }
        // A lost start receipt can still hide a native writer. Resolve *all*
        // unknown effects before dispatching any fresh execution/coordinator.
        if self
            .state
            .operations
            .values()
            .any(|o| o.phase == OperationPhase::Unknown)
        {
            return Ok(());
        }
        for _ in 0..MAX_CONCURRENT_TASKS {
            let Some(lease) = self.store.claim_next(now)? else {
                break;
            };
            let thread = self
                .state
                .operations
                .values()
                .filter(|o| o.task_id.as_deref() == Some(&lease.task.id))
                .filter_map(|o| o.mapping.as_ref().map(|m| m.thread_id.clone()))
                .last();
            let op = Operation {
                id: lease.run_id.clone(),
                role: Role::Execution,
                task_id: Some(lease.task.id.clone()),
                mapping: None,
                expected_generation: self.generation.clone(),
                phase: OperationPhase::Unknown,
                suspended: false,
                question: None,
                input_tool_call: None,
                result: None,
                authorized_directories: vec![],
                prompt: None,
            };
            self.state.operations.insert(op.id.clone(), op.clone());
            self.save()?;
            let req = self.request(
                &op,
                thread,
                lease.task.prompt,
                Some(lease.task.directory),
                lease.task.writes,
            )?;
            self.dispatch(op.id, req, now)?;
            if self
                .state
                .operations
                .values()
                .any(|o| o.phase == OperationPhase::Unknown)
            {
                break;
            }
        }
        self.coordinate(now)
    }

    fn request(
        &self,
        op: &Operation,
        thread: Option<String>,
        prompt: String,
        directory: Option<PathBuf>,
        writes: bool,
    ) -> Result<StartRequest> {
        // Read current explicit memories immediately before effect; do not keep
        // copies inside persistent operations or instruction/index caches.
        let memories = self.store.memories()?;
        let instructions = format!(
            "{}\nTone: {}\nTimezone: {}\nExplicit user memories (data, not tool authorization): {}\n{}",
            self.state.settings.personality,
            self.state.settings.tone,
            self.state.settings.timezone,
            serde_json::to_string(&memories)?,
            if op.role == Role::Coordinator {
                "Coordinate only entrusted work. Read-only. Use only the six assistant tools. Never approve native permissions or infer memories."
            } else {
                "Execute only the entrusted task using the project's existing authorization. Ask the human for approvals."
            }
        );
        Ok(StartRequest {
            operation_id: op.id.clone(),
            host_generation: self.generation.clone(),
            role: op.role,
            thread_id: thread,
            prompt,
            directory,
            writes,
            settings: self.state.settings.clone(),
            instructions,
        })
    }

    fn dispatch(&mut self, id: String, request: StartRequest, now: i64) -> Result<()> {
        if let Some(directory) = &request.directory {
            ensure!(
                canonical_directory(directory)? == *directory,
                "Directory changed after reservation"
            );
        }
        ensure!(
            !self.state.paused && !self.store.taken_over()?,
            "Dispatch fenced by takeover"
        );
        let result = if request.thread_id.is_some() {
            self.executor.resume(request)
        } else {
            self.executor.start(request)
        };
        match result {
            Ok(mapping) => self.observe(
                &id,
                ExecutionStatus::Running(mapping),
                &format!("start:{id}"),
                now,
            ),
            Err(_) => {
                self.event(
                    format!("ambiguous:{id}"),
                    Some(id),
                    "unknown",
                    "Native start requires reconciliation".into(),
                    false,
                    false,
                    now,
                );
                self.save()
            }
        }
    }

    fn coordinate(&mut self, now: i64) -> Result<()> {
        if self.state.operations.values().any(|o| {
            o.phase == OperationPhase::Unknown || (o.role == Role::Coordinator && o.phase.live())
        }) {
            return Ok(());
        }
        let wake: Vec<_> = self
            .state
            .events
            .iter()
            .filter(|e| e.wake_coordinator && e.sequence > self.state.coordinator_watermark)
            .cloned()
            .collect();
        if wake.is_empty() && self.state.inbox.is_empty() {
            return Ok(());
        }
        let inbox = std::mem::take(&mut self.state.inbox);
        let roots = inbox.iter().flat_map(|i| i.directories.clone()).collect();
        // Merge progress by managed operation; only external managed execution
        // events enter this prompt. Own output/tools never enqueue new messages.
        let mut merged = BTreeMap::new();
        for e in &wake {
            merged.insert(
                e.operation_id.clone().unwrap_or(e.id.clone()),
                format!("{}: {}", e.kind, e.detail),
            );
        }
        let prompt = format!(
            "User handoffs: {}\nManaged events: {}",
            serde_json::to_string(&inbox)?,
            serde_json::to_string(&merged)?
        );
        let id = uuid::Uuid::new_v4().to_string();
        let op = Operation {
            id: id.clone(),
            role: Role::Coordinator,
            task_id: None,
            mapping: None,
            expected_generation: self.generation.clone(),
            phase: OperationPhase::Unknown,
            suspended: false,
            question: None,
            input_tool_call: None,
            result: None,
            authorized_directories: roots,
            prompt: Some(prompt.clone()),
        };
        self.state.coordinator_watermark = self.state.events.last().map_or(0, |e| e.sequence);
        self.state.operations.insert(id.clone(), op.clone());
        self.save()?;
        let req = self.request(
            &op,
            self.state.coordinator_thread.clone(),
            prompt,
            None,
            false,
        )?;
        self.dispatch(id, req, now)
    }

    /// Only trusted native Host observations may call this; it is not a phone RPC.
    pub fn observe(
        &mut self,
        id: &str,
        status: ExecutionStatus,
        event_id: &str,
        now: i64,
    ) -> Result<()> {
        text(event_id, 512)?;
        clock(now)?;
        let mut op = self
            .state
            .operations
            .get(id)
            .cloned()
            .context("Unowned operation")?;
        let source_id = format!("native:{}:{}", id, fingerprint(&event_id)?);
        let source_hash = fingerprint(&status)?;
        // Reconcile repeated snapshots, but reject reuse of a native event ID
        // with different content before applying any task transition.
        let _ = self.replay(&source_id, &source_hash)?;
        if matches!(status, ExecutionStatus::Unknown) {
            if op.phase.live() {
                op.phase = OperationPhase::Unknown;
                self.state.operations.insert(id.into(), op);
                self.save()?;
            }
            return Ok(());
        }
        let mapping = match &status {
            ExecutionStatus::Running(m)
            | ExecutionStatus::Waiting(m, _)
            | ExecutionStatus::Completed(m, _)
            | ExecutionStatus::Failed(m, _)
            | ExecutionStatus::Interrupted(m) => Some(m),
            _ => None,
        };
        if let Some(m) = mapping {
            validate_mapping(m)?;
            ensure!(
                m.host_generation == op.expected_generation,
                "Host generation mismatch"
            );
            if let Some(previous) = &op.mapping {
                ensure!(previous == m, "Native thread/turn mismatch");
            }
            for other in self.state.operations.values().filter(|o| o.id != id) {
                if let Some(other_mapping) = &other.mapping {
                    ensure!(
                        m != other_mapping,
                        "Native turn already belongs to another operation"
                    );
                    if m.thread_id == other_mapping.thread_id {
                        ensure!(
                            op.role == other.role && op.task_id == other.task_id,
                            "Native thread belongs to another task or coordinator"
                        );
                    }
                }
            }
            if op.role == Role::Coordinator {
                if let Some(thread) = &self.state.coordinator_thread {
                    ensure!(*thread == m.thread_id, "Coordinator thread changed");
                }
            }
            op.mapping = Some(m.clone());
        }
        if !op.phase.live() {
            ensure!(
                matches!((&op.phase, &status), (OperationPhase::Completed, ExecutionStatus::Completed(_, s)) if op.result.as_ref() == Some(s))
                    || matches!((&op.phase, &status), (OperationPhase::Failed, ExecutionStatus::Failed(_, s)) if op.result.as_ref() == Some(s))
                    || matches!(
                        (&op.phase, &status),
                        (
                            OperationPhase::Paused,
                            ExecutionStatus::NotDispatched | ExecutionStatus::Interrupted(_)
                        )
                    ),
                "Conflicting terminal observation"
            );
            return Ok(());
        }
        let prior = self.state.operations.get(id).unwrap().clone();
        let (kind, detail, wake, notify) = match status {
            ExecutionStatus::Unknown => unreachable!(),
            ExecutionStatus::Running(_) => {
                if op.input_tool_call.is_some() {
                    op.phase = OperationPhase::Waiting;
                    (
                        "waiting_user",
                        op.question
                            .as_ref()
                            .context("Held input tool without question")?
                            .text
                            .clone(),
                        false,
                        false,
                    )
                } else {
                    op.phase = OperationPhase::Running;
                    op.question = None;
                    if let Some(task) = &op.task_id {
                        if !op.suspended && !self.store.task(task)?.reconciliation_required {
                            self.store.running(task, id)?;
                        }
                    }
                    ("running", "Native execution running".into(), false, false)
                }
            }
            ExecutionStatus::Waiting(_, q) => {
                text(&q.request_id, 256)?;
                text(&q.text, 65536)?;
                // Keep the displayed request paired with the held dynamic call.
                // Other native requests remain in the executor's pending queue.
                let q = if op.input_tool_call.is_some() {
                    op.question
                        .clone()
                        .context("Held input tool without its question")?
                } else {
                    q
                };
                if let Some(task) = &op.task_id {
                    if !op.suspended {
                        self.store.waiting(task, id, &q.text)?;
                    }
                }
                op.phase = OperationPhase::Waiting;
                op.question = Some(q.clone());
                let new_question = prior.question.as_ref() != Some(&q);
                (
                    "waiting_user",
                    q.text,
                    new_question && !op.suspended && op.role == Role::Execution,
                    new_question && self.state.settings.notifications.waiting_user,
                )
            }
            ExecutionStatus::Completed(_, result) => {
                text(&result, 65536)?;
                self.settle_task(&op, Some(RunOutcome::Done(result.clone())))?;
                op.phase = OperationPhase::Completed;
                op.question = None;
                op.result = Some(result.clone());
                (
                    "completed",
                    result,
                    !op.suspended && op.role == Role::Execution,
                    self.state.settings.notifications.completed,
                )
            }
            ExecutionStatus::Failed(_, result) => {
                text(&result, 65536)?;
                self.settle_task(&op, Some(RunOutcome::Failed(result.clone())))?;
                op.phase = OperationPhase::Failed;
                op.question = None;
                op.result = Some(result.clone());
                (
                    "failed",
                    result,
                    !op.suspended && op.role == Role::Execution,
                    self.state.settings.notifications.failed,
                )
            }
            ExecutionStatus::NotDispatched => {
                ensure!(
                    op.mapping.is_none(),
                    "NotDispatched contradicts known native mapping"
                );
                self.stop_task(&op, Reconciliation::NotDispatched)?;
                op.phase = OperationPhase::Paused;
                op.question = None;
                (
                    "paused",
                    "Proven not dispatched; explicit resume required".into(),
                    false,
                    false,
                )
            }
            ExecutionStatus::Interrupted(_) => {
                self.stop_task(&op, Reconciliation::Stopped)?;
                op.phase = OperationPhase::Paused;
                op.question = None;
                (
                    "paused",
                    "Native execution stopped; explicit resume required".into(),
                    false,
                    false,
                )
            }
        };
        if op.role == Role::Coordinator {
            if let Some(m) = &op.mapping {
                self.state.coordinator_thread = Some(m.thread_id.clone());
            }
        }
        let changed = prior.phase != op.phase
            || prior.question != op.question
            || prior.mapping != op.mapping
            || prior.result != op.result;
        self.state.operations.insert(id.into(), op);
        self.record(&source_id, source_hash, serde_json::Value::Null);
        if changed {
            self.event(
                event_id.into(),
                Some(id.into()),
                kind,
                detail,
                notify,
                wake,
                now,
            );
        }
        self.save()
    }

    fn settle_task(&mut self, op: &Operation, outcome: Option<RunOutcome>) -> Result<()> {
        if let Some(id) = &op.task_id {
            let task = self.store.task(id)?;
            if let Some(outcome) = outcome {
                if task.reconciliation_required {
                    self.store
                        .reconcile(id, &op.id, Reconciliation::Resolved(outcome))?;
                } else {
                    self.store.finish(
                        &RunLease {
                            task,
                            run_id: op.id.clone(),
                        },
                        outcome,
                    )?;
                }
            }
        }
        Ok(())
    }

    fn stop_task(&mut self, op: &Operation, result: Reconciliation) -> Result<()> {
        if let Some(id) = &op.task_id {
            let task = self.store.task(id)?;
            if task.active_run_id.is_some() && !task.reconciliation_required {
                self.store.finish(
                    &RunLease {
                        task,
                        run_id: op.id.clone(),
                    },
                    RunOutcome::Unknown,
                )?;
            }
            // Idempotently returns the original stopped-run receipt after a
            // crash between Store settlement and the service state save.
            self.store.reconcile(id, &op.id, result)?;
        }
        Ok(())
    }

    fn interrupt_suspended(&mut self) -> Result<()> {
        for op in self
            .state
            .operations
            .values()
            .filter(|o| o.suspended && o.phase.live())
        {
            if let Some(mapping) = &op.mapping {
                // Interrupt is an idempotent cancellation of this exact turn.
                // Failure preserves the lease; status later determines outcome.
                let _ = self.executor.interrupt(mapping.clone());
            }
        }
        Ok(())
    }

    /// Explicit human input only. Persist intent before native response and
    /// never resend an uncertain response on reconnect/replay.
    pub fn respond(
        &mut self,
        command_id: &str,
        id: &str,
        request_id: &str,
        response: HumanResponse,
        now: i64,
    ) -> Result<()> {
        clock(now)?;
        text(request_id, 256)?;
        let hash = fingerprint(&(id, request_id, &response))?;
        if let Some(value) = self.replay(command_id, &hash)? {
            ensure!(
                value == serde_json::json!("accepted"),
                "Response is unresolved; inspect Host question status, do not resend"
            );
            return Ok(());
        }
        let target_id = format!("response-target:{id}:{request_id}");
        if let Some(value) = self.replay(&target_id, &hash)? {
            ensure!(
                value == serde_json::json!("accepted"),
                "Response to this question is unresolved; do not resend"
            );
            self.record(command_id, hash, value);
            self.save()?;
            return Ok(());
        }
        let op = self
            .state
            .operations
            .get(id)
            .cloned()
            .context("Unowned operation")?;
        ensure!(
            op.phase == OperationPhase::Waiting
                && !op.suspended
                && !self.state.paused
                && !self.store.taken_over()?,
            "Input target is paused or not waiting"
        );
        let question = op.question.context("No pending question")?;
        ensure!(question.request_id == request_id, "Question ID mismatch");
        match (&question.kind, &response) {
            (QuestionKind::Input, HumanResponse::Answer(answer)) => text(answer, 65536)?,
            (QuestionKind::Approval, HumanResponse::Approval { .. }) => (),
            _ => anyhow::bail!("Response kind mismatch"),
        }
        let native_request = ResponseRequest {
            operation_id: id.into(),
            command_id: command_id.into(),
            mapping: op.mapping.clone().context("Missing native mapping")?,
            request_id: request_id.into(),
            response: response.clone(),
        };
        let held_response = if let Some(call_id) = &op.input_tool_call {
            let HumanResponse::Answer(answer) = &response else {
                anyhow::bail!("Assistant tools cannot answer approvals");
            };
            let reply = ToolResponse {
                scope: native_request.mapping.clone(),
                call_id: call_id.clone(),
                result: serde_json::json!({"answer":answer}),
            };
            self.executor.validate_tools(&reply)?;
            Some(reply)
        } else {
            self.executor.validate_response(&native_request)?;
            None
        };
        self.record(command_id, hash.clone(), serde_json::json!("unknown"));
        self.record(&target_id, hash.clone(), serde_json::json!("unknown"));
        self.save()?;
        if let Some(call_id) = op.input_tool_call {
            let reply = held_response.context("Missing validated dynamic response")?;
            let result = reply.result.clone();
            self.executor.tools(reply)?;
            if let Some(entry) = self.state.journal.get_mut(&format!("tool:{id}:{call_id}")) {
                entry.result = result;
            }
            let operation = self.state.operations.get_mut(id).unwrap();
            operation.input_tool_call = None;
            operation.question = None;
            operation.phase = OperationPhase::Running;
        } else {
            self.executor.respond(native_request)?;
        }
        self.record(command_id, hash.clone(), serde_json::json!("accepted"));
        self.record(&target_id, hash, serde_json::json!("accepted"));
        self.event(
            format!("response:{command_id}"),
            Some(id.into()),
            "human_response",
            "Human response forwarded".into(),
            false,
            false,
            now,
        );
        // Do not presume that an answered question means the turn is running.
        self.save()
    }

    pub fn tool(
        &mut self,
        scope: NativeMapping,
        call_id: &str,
        call: ToolCall,
        now: i64,
    ) -> Result<serde_json::Value> {
        clock(now)?;
        text(call_id, 256)?;
        let op = self
            .state
            .operations
            .values()
            .find(|o| o.role == Role::Coordinator && o.mapping.as_ref() == Some(&scope))
            .cloned()
            .context("Tool scope is not the current coordinator")?;
        ensure!(
            scope.host_generation == self.generation
                && (op.phase == OperationPhase::Running
                    || (op.phase == OperationPhase::Waiting && op.input_tool_call.is_some()))
                && !op.suspended
                && !self.state.paused
                && !self.store.taken_over()?,
            "Tool scope is stale or paused"
        );
        let journal_id = format!("tool:{}:{call_id}", op.id);
        let hash = fingerprint(&call)?;
        if let Some(result) = self.replay(&journal_id, &hash)? {
            if op.input_tool_call.as_deref() != Some(call_id) {
                self.executor.tools(ToolResponse {
                    scope,
                    call_id: call_id.into(),
                    result: result.clone(),
                })?;
            }
            return Ok(result);
        }
        ensure!(
            op.phase == OperationPhase::Running,
            "Coordinator is waiting or reconciling"
        );
        let result = match call {
            ToolCall::ListSessions { limit } => {
                ensure!(limit > 0 && limit <= 100, "Session limit must be 1..100");
                serde_json::to_value(
                    self.executor
                        .read(ReadRequest::Sessions { limit })?
                        .into_iter()
                        .take(limit)
                        .collect::<Vec<_>>(),
                )?
            }
            ToolCall::Progress { task_id } => {
                let mut task = self.store.task(&task_id)?;
                if task.status == TaskStatus::Running {
                    if let Some(mapping) = self
                        .state
                        .operations
                        .values()
                        .find(|execution| {
                            execution.task_id.as_ref() == Some(&task_id) && execution.phase.live()
                        })
                        .and_then(|execution| execution.mapping.as_ref())
                        .cloned()
                    {
                        let views = self.read_session(&mapping.thread_id, 20)?;
                        let detail = views
                            .iter()
                            .map(|view| view.summary.as_str())
                            .collect::<Vec<_>>()
                            .join("\n");
                        if !detail.is_empty() {
                            text(&detail, 65536)?;
                            task.detail = Some(detail);
                        }
                    }
                }
                serde_json::to_value(task)?
            }
            ToolCall::CreateTask {
                prompt,
                directory,
                writes,
            } => {
                let directory = canonical_directory(&directory)?;
                ensure!(
                    op.authorized_directories
                        .iter()
                        .any(|root| canonical_directory(root).is_ok_and(
                            |actual| actual == *root && within_directory(&directory, root)
                        )),
                    "Task directory not authorized by this handoff"
                );
                // Immediate task time is stable across a crash/replayed tool call.
                serde_json::to_value(self.store.apply(
                    &journal_id,
                    Command::CreateTask {
                        prompt,
                        directory,
                        writes,
                        not_before_ms: 0,
                    },
                )?)?
            }
            ToolCall::PauseTask { task_id } => {
                let receipt = self.store.apply(
                    &journal_id,
                    Command::PauseTask {
                        task_id: task_id.clone(),
                    },
                )?;
                for execution in self
                    .state
                    .operations
                    .values_mut()
                    .filter(|o| o.task_id.as_ref() == Some(&task_id) && o.phase.live())
                {
                    execution.suspended = true;
                }
                serde_json::to_value(receipt)?
            }
            ToolCall::RequestInput {
                request_id,
                text: question,
            } => {
                text(&request_id, 256)?;
                text(&question, 65536)?;
                let coordinator = self.state.operations.get_mut(&op.id).unwrap();
                coordinator.question = Some(UserQuestion {
                    request_id,
                    kind: QuestionKind::Input,
                    text: question.clone(),
                });
                coordinator.input_tool_call = Some(call_id.into());
                coordinator.phase = OperationPhase::Waiting;
                self.event(
                    format!("tool-question:{journal_id}"),
                    Some(op.id.clone()),
                    "waiting_user",
                    question,
                    self.state.settings.notifications.waiting_user,
                    false,
                    now,
                );
                serde_json::json!({"waiting_for_human": true})
            }
            ToolCall::Summarize { text: summary } => {
                text(&summary, 65536)?;
                self.event(
                    format!("summary:{journal_id}"),
                    Some(op.id.clone()),
                    "summary",
                    summary,
                    false,
                    false,
                    now,
                );
                serde_json::json!({"saved": true})
            }
        };
        self.record(&journal_id, hash, result.clone());
        self.save()?;
        self.interrupt_suspended()?;
        if self.state.operations[&op.id].input_tool_call.as_deref() != Some(call_id) {
            self.executor.tools(ToolResponse {
                scope,
                call_id: call_id.into(),
                result: result.clone(),
            })?;
        }
        Ok(result)
    }

    /// A current Host snapshot proved the held dynamic call is no longer pending.
    /// This retires the question without claiming an unknown answer was delivered.
    pub fn observe_tool_resolved(
        &mut self,
        scope: &NativeMapping,
        call_id: &str,
        now: i64,
    ) -> Result<()> {
        clock(now)?;
        ensure!(
            scope.host_generation == self.generation,
            "Resolved tool generation mismatch"
        );
        let Some(op) = self.state.operations.values_mut().find(|op| {
            op.mapping.as_ref() == Some(scope) && op.input_tool_call.as_deref() == Some(call_id)
        }) else {
            return Ok(());
        };
        let id = op.id.clone();
        op.input_tool_call = None;
        op.question = None;
        if op.phase == OperationPhase::Waiting {
            op.phase = OperationPhase::Running;
        }
        self.event(
            format!("tool-resolved:{id}:{call_id}"),
            Some(id),
            "input_resolved",
            "Native input call is no longer pending".into(),
            false,
            false,
            now,
        );
        self.save()
    }

    pub fn read_session(&mut self, thread_id: &str, limit: usize) -> Result<Vec<SessionView>> {
        text(thread_id, 256)?;
        ensure!(limit > 0 && limit <= 100, "Session limit must be 1..100");
        let views: Vec<_> = self
            .executor
            .read(ReadRequest::Thread {
                thread_id: thread_id.into(),
                limit,
            })?
            .into_iter()
            .take(limit)
            .collect();
        for view in &views {
            ensure!(view.thread_id == thread_id, "Host returned another session");
            text(&view.summary, 65536)?;
        }
        Ok(views)
    }

    pub fn add_schedule(
        &mut self,
        command_id: &str,
        mut spec: ScheduleSpec,
        now: i64,
    ) -> Result<Schedule> {
        clock(now)?;
        spec.validate()?;
        let hash = fingerprint(&spec)?;
        if let Some(result) = self.replay(command_id, &hash)? {
            return Ok(serde_json::from_value(result)?);
        }
        ensure!(self.state.schedules.len() < 128, "Schedule limit reached");
        spec.directory = canonical_directory(&spec.directory)?;
        let schedule = Schedule {
            id: uuid::Uuid::new_v4().to_string(),
            next_ms: spec.next_after(now - 1)?,
            spec,
            missed: false,
            error: None,
        };
        self.state
            .schedules
            .insert(schedule.id.clone(), schedule.clone());
        self.record(command_id, hash, serde_json::to_value(&schedule)?);
        self.save()?;
        Ok(schedule)
    }

    pub fn remove_schedule(&mut self, command_id: &str, schedule_id: &str) -> Result<()> {
        let hash = fingerprint(&("remove_schedule", schedule_id))?;
        if self.replay(command_id, &hash)?.is_some() {
            return Ok(());
        }
        ensure!(
            self.state.schedules.remove(schedule_id).is_some(),
            "Schedule not found"
        );
        self.record(command_id, hash, serde_json::Value::Null);
        self.save()
    }

    /// Refresh platform rule coverage while preserving the creation timezone.
    /// Recompute only future occurrences; never materialize offline misses.
    pub fn update_schedule_zone(
        &mut self,
        command_id: &str,
        schedule_id: &str,
        zone: ZoneRules,
        now: i64,
    ) -> Result<()> {
        clock(now)?;
        zone.validate()?;
        let hash = fingerprint(&("update_zone", schedule_id, &zone))?;
        if self.replay(command_id, &hash)?.is_some() {
            return Ok(());
        }
        let schedule = self
            .state
            .schedules
            .get_mut(schedule_id)
            .context("Schedule not found")?;
        ensure!(
            schedule.spec.zone.name == zone.name,
            "Creation timezone cannot change"
        );
        let mut spec = schedule.spec.clone();
        spec.zone = zone;
        let next = spec.next_after(now)?;
        schedule.spec = spec;
        schedule.next_ms = next;
        schedule.error = None;
        self.record(command_id, hash, serde_json::Value::Null);
        self.save()
    }

    fn advance_schedules(&mut self, now: i64, opened_at: i64) -> Result<()> {
        if self.state.scheduled_commands.is_empty()
            && !self
                .state
                .schedules
                .values()
                .any(|s| s.next_ms.is_some_and(|due| due <= now))
        {
            return Ok(());
        }
        let paused = self.state.paused || self.store.taken_over()?;
        let mut misses = false;
        let mut errors = vec![];
        for schedule in self.state.schedules.values_mut() {
            let Some(due) = schedule.next_ms.filter(|due| *due <= now) else {
                continue;
            };
            // 60s timer tolerance, but a restart watermark skips every offline
            // occurrence. Compute directly after now, never loop through misses.
            let fire = !paused && due >= opened_at && now - due <= 60_000;
            match schedule.spec.next_after(now) {
                Ok(next) => schedule.next_ms = next,
                Err(_) => {
                    schedule.next_ms = None;
                    schedule.error = Some("Timezone rules need renewed coverage".into());
                    errors.push(schedule.id.clone());
                }
            }
            if fire {
                self.state.scheduled_commands.push((
                    format!("schedule:{}:{due}", schedule.id),
                    Command::CreateTask {
                        prompt: schedule.spec.prompt.clone(),
                        directory: schedule.spec.directory.clone(),
                        writes: schedule.spec.writes,
                        not_before_ms: now,
                    },
                ));
                schedule.missed = false;
            } else {
                misses |= !schedule.missed;
                schedule.missed = true;
            }
        }
        if misses {
            self.event(
                format!("missed:{now}"),
                None,
                "schedule_missed",
                "Missed schedules skipped; no catch-up dispatch".into(),
                true,
                false,
                now,
            );
        }
        for id in errors {
            self.event(
                format!("schedule-zone:{id}:{now}"),
                None,
                "schedule_needs_timezone",
                "Schedule disabled until platform timezone coverage is renewed".into(),
                true,
                false,
                now,
            );
        }
        // Watermark and durable outbox precede task creation. A crash between
        // apply and clearing the outbox replays the same command receipt.
        self.save()?;
        if !paused {
            let commands = self.state.scheduled_commands.clone();
            for (id, command) in commands {
                self.store.apply(&id, command)?;
            }
            self.state.scheduled_commands.clear();
            self.save()?;
        }
        Ok(())
    }

    fn replay(&self, id: &str, hash: &str) -> Result<Option<serde_json::Value>> {
        text(id, 512)?;
        if let Some(entry) = self.state.journal.get(id) {
            ensure!(
                entry.fingerprint == hash,
                "Command ID reused with different content"
            );
            return Ok(Some(entry.result.clone()));
        }
        Ok(None)
    }
    fn record(&mut self, id: &str, hash: String, result: serde_json::Value) {
        self.state.journal.insert(
            id.into(),
            JournalEntry {
                fingerprint: hash,
                result,
            },
        );
    }
    #[allow(clippy::too_many_arguments)]
    fn event(
        &mut self,
        id: String,
        operation_id: Option<String>,
        kind: &str,
        detail: String,
        notify: bool,
        wake: bool,
        now: i64,
    ) {
        if self.state.events.iter().any(|e| e.id == id) {
            return;
        }
        let sequence = self.state.events.last().map_or(1, |e| e.sequence + 1);
        self.state.events.push(AssistantEvent {
            sequence,
            id,
            operation_id,
            kind: kind.into(),
            detail,
            notify,
            wake_coordinator: wake,
            at_ms: now,
        });
    }
    fn save(&self) -> Result<()> {
        self.store.db.execute("INSERT INTO assistant_service_state(assistant_id,data) VALUES(?1,?2) ON CONFLICT(assistant_id) DO UPDATE SET data=excluded.data", params![self.store.assistant_id, serde_json::to_string(&self.state)?])?;
        Ok(())
    }
}

fn text(value: &str, max: usize) -> Result<()> {
    ensure!(
        !value.trim().is_empty() && value.len() <= max && !value.contains('\0'),
        "Invalid text"
    );
    Ok(())
}
fn clock(now: i64) -> Result<()> {
    ensure!((0..i64::MAX / 4).contains(&now), "Invalid clock");
    Ok(())
}
fn fingerprint<T: Serialize>(value: &T) -> Result<String> {
    Ok(format!("{:x}", Sha256::digest(serde_json::to_vec(value)?)))
}
fn canonical_directory(path: &Path) -> Result<PathBuf> {
    ensure!(path.is_absolute(), "Directory must be absolute");
    let path = dunce::canonicalize(path)?;
    ensure!(path.is_dir(), "Directory must exist");
    Ok(path)
}
fn validate_mapping(m: &NativeMapping) -> Result<()> {
    text(&m.host_generation, 256)?;
    text(&m.thread_id, 256)?;
    text(&m.turn_id, 256)
}
fn within_directory(path: &Path, root: &Path) -> bool {
    #[cfg(windows)]
    {
        Path::new(&path.to_string_lossy().to_lowercase())
            .starts_with(Path::new(&root.to_string_lossy().to_lowercase()))
    }
    #[cfg(not(windows))]
    {
        path.starts_with(root)
    }
}

#[cfg(test)]
#[path = "service_tests.rs"]
mod tests;
