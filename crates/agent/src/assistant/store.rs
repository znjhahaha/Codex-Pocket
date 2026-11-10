use super::*;
use anyhow::{Context, Result, bail, ensure};
use rusqlite::{Connection, OptionalExtension, TransactionBehavior, params};
use sha2::{Digest, Sha256};
use std::{path::Path, time::Duration};

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS assistant_computers (
    computer_id TEXT PRIMARY KEY,
    assistant_id TEXT NOT NULL UNIQUE,
    taken_over INTEGER NOT NULL DEFAULT 0 CHECK(taken_over IN (0,1))
);
CREATE TABLE IF NOT EXISTS assistant_tasks (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    assistant_id TEXT NOT NULL REFERENCES assistant_computers(assistant_id),
    status TEXT NOT NULL CHECK(status IN ('queued','running','waiting_user','paused','done','failed')),
    directory_key TEXT NOT NULL,
    writes INTEGER NOT NULL CHECK(writes IN (0,1)),
    not_before_ms INTEGER NOT NULL CHECK(not_before_ms >= 0),
    active_run_id TEXT,
    data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS assistant_queue
    ON assistant_tasks(assistant_id,status,not_before_ms,sequence);
CREATE INDEX IF NOT EXISTS assistant_active
    ON assistant_tasks(assistant_id) WHERE active_run_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS assistant_one_writer
    ON assistant_tasks(assistant_id,directory_key)
    WHERE writes=1 AND active_run_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS assistant_commands (
    assistant_id TEXT NOT NULL REFERENCES assistant_computers(assistant_id),
    command_id TEXT NOT NULL,
    payload TEXT NOT NULL,
    receipt TEXT NOT NULL,
    PRIMARY KEY(assistant_id,command_id)
);
CREATE TABLE IF NOT EXISTS assistant_memories (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    assistant_id TEXT NOT NULL REFERENCES assistant_computers(assistant_id),
    text TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS assistant_runs (
    run_id TEXT PRIMARY KEY,
    assistant_id TEXT NOT NULL REFERENCES assistant_computers(assistant_id),
    task_id TEXT NOT NULL REFERENCES assistant_tasks(id),
    decision TEXT,
    receipt TEXT
);
"#;

pub struct AssistantStore {
    db: Connection,
    assistant_id: String,
}

impl AssistantStore {
    /// The caller supplies a private, local database path; its parent must exist.
    /// Opening another handle does not fence workers. Recovery is explicit.
    pub fn open(path: &Path, computer_id: &str) -> Result<Self> {
        bounded_text(computer_id, 256)?;
        let mut db = Connection::open(path)?;
        db.busy_timeout(Duration::from_secs(5))?;
        db.execute_batch(
            "PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;",
        )?;
        let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        tx.execute_batch(SCHEMA)?;
        tx.execute(
            "INSERT OR IGNORE INTO assistant_computers(computer_id,assistant_id) VALUES(?1,?2)",
            params![computer_id, uuid::Uuid::new_v4().to_string()],
        )?;
        let assistant_id = tx.query_row(
            "SELECT assistant_id FROM assistant_computers WHERE computer_id=?1",
            [computer_id],
            |row| row.get(0),
        )?;
        tx.commit()?;
        Ok(Self { db, assistant_id })
    }

    pub fn assistant_id(&self) -> &str {
        &self.assistant_id
    }

    /// Atomically applies an explicit user command and persists its receipt.
    /// Reusing an ID with different content is an error, even after restart.
    pub fn apply(&mut self, command_id: &str, command: Command) -> Result<CommandReceipt> {
        bounded_text(command_id, 256)?;
        // Persist a fingerprint, not an extra copy of explicit memory text.
        let payload = format!("{:x}", Sha256::digest(serde_json::to_vec(&command)?));
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        let saved: Option<(String, String)> = tx.query_row(
            "SELECT payload,receipt FROM assistant_commands WHERE assistant_id=?1 AND command_id=?2",
            params![self.assistant_id, command_id], |row| Ok((row.get(0)?, row.get(1)?)),
        ).optional()?;
        if let Some((previous, receipt)) = saved {
            ensure!(
                previous == payload,
                "Command ID reused with different content"
            );
            return Ok(serde_json::from_str(&receipt)?);
        }
        let result = match command {
            Command::CreateTask {
                prompt,
                directory,
                writes,
                not_before_ms,
            } => {
                bounded_text(&prompt, 64 * 1024)?;
                ensure!(not_before_ms >= 0, "Invalid schedule time");
                ensure!(directory.is_absolute(), "Task directory must be absolute");
                let directory = dunce::canonicalize(directory)?;
                ensure!(directory.is_dir(), "Task directory must exist");
                let key = directory_key(&directory)?;
                let task = Task {
                    id: uuid::Uuid::new_v4().to_string(),
                    assistant_id: self.assistant_id.clone(),
                    prompt,
                    directory,
                    writes,
                    not_before_ms,
                    status: TaskStatus::Queued,
                    active_run_id: None,
                    reconciliation_required: false,
                    detail: None,
                };
                tx.execute(
                    "INSERT INTO assistant_tasks(id,assistant_id,status,directory_key,writes,not_before_ms,data)
                     VALUES(?1,?2,'queued',?3,?4,?5,?6)",
                    params![task.id, self.assistant_id, key, writes, not_before_ms, serde_json::to_string(&task)?],
                )?;
                CommandResult::Task(task)
            }
            Command::PauseTask { task_id } => {
                let mut task = read_task(&tx, &self.assistant_id, &task_id)?;
                ensure!(
                    !matches!(task.status, TaskStatus::Done | TaskStatus::Failed),
                    "Task is terminal"
                );
                task.status = TaskStatus::Paused;
                task.reconciliation_required = task.active_run_id.is_some();
                save_task(&tx, &task)?;
                CommandResult::Task(task)
            }
            Command::ResumeTask { task_id } => {
                let mut task = read_task(&tx, &self.assistant_id, &task_id)?;
                ensure!(
                    !task.reconciliation_required && task.active_run_id.is_none(),
                    "Reconcile before resuming"
                );
                ensure!(
                    matches!(task.status, TaskStatus::Paused | TaskStatus::WaitingUser),
                    "Task cannot resume"
                );
                task.status = TaskStatus::Queued;
                save_task(&tx, &task)?;
                CommandResult::Task(task)
            }
            Command::TakeOver | Command::ReleaseTakeover => {
                let taken_over = matches!(command, Command::TakeOver);
                tx.execute(
                    "UPDATE assistant_computers SET taken_over=?1 WHERE assistant_id=?2",
                    params![taken_over, self.assistant_id],
                )?;
                if taken_over {
                    pause_running(&tx, &self.assistant_id)?;
                }
                CommandResult::Takeover(taken_over)
            }
            Command::Remember { text } => {
                bounded_text(&text, 16 * 1024)?;
                let memory = Memory {
                    id: uuid::Uuid::new_v4().to_string(),
                    text,
                };
                tx.execute(
                    "INSERT INTO assistant_memories(id,assistant_id,text) VALUES(?1,?2,?3)",
                    params![memory.id, self.assistant_id, memory.text],
                )?;
                CommandResult::Memory(memory.id)
            }
            Command::EditMemory { memory_id, text } => {
                bounded_text(&text, 16 * 1024)?;
                let changed = tx.execute(
                    "UPDATE assistant_memories SET text=?1 WHERE assistant_id=?2 AND id=?3",
                    params![text, self.assistant_id, memory_id],
                )?;
                ensure!(changed == 1, "Memory not found");
                CommandResult::Memory(memory_id)
            }
            Command::DeleteMemory { memory_id } => {
                let changed = tx.execute(
                    "DELETE FROM assistant_memories WHERE assistant_id=?1 AND id=?2",
                    params![self.assistant_id, memory_id],
                )?;
                ensure!(changed == 1, "Memory not found");
                CommandResult::MemoryDeleted(memory_id)
            }
        };
        let receipt = CommandReceipt {
            command_id: command_id.into(),
            result,
        };
        tx.execute("INSERT INTO assistant_commands(assistant_id,command_id,payload,receipt) VALUES(?1,?2,?3,?4)",
            params![self.assistant_id, command_id, payload, serde_json::to_string(&receipt)?])?;
        tx.commit()?;
        Ok(receipt)
    }

    pub fn tasks(&self) -> Result<Vec<Task>> {
        read_tasks(&self.db, &self.assistant_id, None)
    }
    pub fn task(&self, task_id: &str) -> Result<Task> {
        read_task(&self.db, &self.assistant_id, task_id)
    }
    pub fn memories(&self) -> Result<Vec<Memory>> {
        let mut stmt = self.db.prepare(
            "SELECT id,text FROM assistant_memories WHERE assistant_id=?1 ORDER BY sequence",
        )?;
        Ok(stmt
            .query_map([&self.assistant_id], |row| {
                Ok(Memory {
                    id: row.get(0)?,
                    text: row.get(1)?,
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?)
    }
    pub fn taken_over(&self) -> Result<bool> {
        takeover(&self.db, &self.assistant_id)
    }

    /// Claims one due task, skipping blocked writers. Unresolved executions still
    /// occupy capacity and directory reservations, regardless of visible status.
    pub fn claim_next(&mut self, now_ms: i64) -> Result<Option<RunLease>> {
        ensure!(now_ms >= 0, "Invalid clock time");
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        if takeover(&tx, &self.assistant_id)? {
            return Ok(None);
        }
        let active: usize = tx.query_row(
            "SELECT count(*) FROM assistant_tasks WHERE assistant_id=?1 AND active_run_id IS NOT NULL",
            [&self.assistant_id], |row| row.get(0),
        )?;
        if active >= MAX_CONCURRENT_TASKS {
            return Ok(None);
        }
        let data: Option<String> = tx.query_row(
            "SELECT t.data FROM assistant_tasks t
             WHERE t.assistant_id=?1 AND t.status='queued' AND t.active_run_id IS NULL AND t.not_before_ms<=?2
             AND (t.writes=0 OR NOT EXISTS (
                 SELECT 1 FROM assistant_tasks busy WHERE busy.assistant_id=t.assistant_id
                 AND busy.writes=1 AND busy.directory_key=t.directory_key AND busy.active_run_id IS NOT NULL))
             ORDER BY t.not_before_ms,t.sequence LIMIT 1",
            params![self.assistant_id, now_ms], |row| row.get(0),
        ).optional()?;
        let Some(data) = data else {
            return Ok(None);
        };
        let mut task: Task = serde_json::from_str(&data)?;
        let run_id = uuid::Uuid::new_v4().to_string();
        task.status = TaskStatus::Running;
        task.active_run_id = Some(run_id.clone());
        task.detail = None;
        save_task(&tx, &task)?;
        tx.execute(
            "INSERT INTO assistant_runs(run_id,assistant_id,task_id) VALUES(?1,?2,?3)",
            params![run_id, self.assistant_id, task.id],
        )?;
        tx.commit()?;
        Ok(Some(RunLease { task, run_id }))
    }

    /// Records a known result or fences an ambiguous run. A late result from a
    /// paused executor must go through `reconcile`, never dispatch again.
    pub fn finish(&mut self, lease: &RunLease, outcome: RunOutcome) -> Result<Task> {
        self.settle(
            &lease.task.id,
            &lease.run_id,
            Reconciliation::Resolved(outcome),
            false,
        )
    }
    pub fn reconcile(
        &mut self,
        task_id: &str,
        run_id: &str,
        result: Reconciliation,
    ) -> Result<Task> {
        ensure!(
            !matches!(result, Reconciliation::Resolved(RunOutcome::Unknown)),
            "Reconciliation requires evidence of a known result"
        );
        self.settle(task_id, run_id, result, true)
    }

    fn settle(
        &mut self,
        task_id: &str,
        run_id: &str,
        result: Reconciliation,
        reconciling: bool,
    ) -> Result<Task> {
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        let decision = serde_json::to_string(&result)?;
        let previous: Option<(Option<String>, Option<String>)> = tx.query_row(
            "SELECT decision,receipt FROM assistant_runs WHERE assistant_id=?1 AND task_id=?2 AND run_id=?3",
            params![self.assistant_id, task_id, run_id], |row| Ok((row.get(0)?, row.get(1)?)),
        ).optional()?;
        let (prior_decision, prior_receipt) = previous.context("Run not found")?;
        if let Some(prior_decision) = prior_decision {
            ensure!(
                prior_decision == decision,
                "Run already settled with a different result"
            );
            return Ok(serde_json::from_str(
                &prior_receipt.context("Missing run receipt")?,
            )?);
        }
        let mut task = read_task(&tx, &self.assistant_id, task_id)?;
        ensure!(task.active_run_id.as_deref() == Some(run_id), "Stale run");
        if reconciling {
            ensure!(
                task.reconciliation_required,
                "Run does not need reconciliation"
            );
        } else if !matches!(result, Reconciliation::Resolved(RunOutcome::Unknown)) {
            ensure!(
                task.status == TaskStatus::Running && !task.reconciliation_required,
                "Reconcile paused run before recording a result"
            );
        }
        let (status, detail, unresolved) = match &result {
            Reconciliation::Resolved(RunOutcome::Done(detail)) => {
                (TaskStatus::Done, Some(detail.clone()), false)
            }
            Reconciliation::Resolved(RunOutcome::Failed(detail)) => {
                (TaskStatus::Failed, Some(detail.clone()), false)
            }
            Reconciliation::Resolved(RunOutcome::WaitingUser(detail)) => {
                (TaskStatus::WaitingUser, Some(detail.clone()), false)
            }
            Reconciliation::Resolved(RunOutcome::Unknown) => (TaskStatus::Paused, None, true),
            Reconciliation::NotDispatched => (TaskStatus::Paused, None, false),
        };
        task.status = status;
        task.detail = detail;
        task.reconciliation_required = unresolved;
        if !unresolved {
            task.active_run_id = None;
        }
        save_task(&tx, &task)?;
        if !unresolved {
            tx.execute("UPDATE assistant_runs SET decision=?1,receipt=?2 WHERE run_id=?3 AND assistant_id=?4",
                params![decision, serde_json::to_string(&task)?, run_id, self.assistant_id])?;
        }
        tx.commit()?;
        Ok(task)
    }

    /// Call only after the host proves that all old executors have stopped.
    /// Returns the newly fenced tasks; reservations remain for reconciliation.
    pub fn recover_after_restart(&mut self) -> Result<Vec<Task>> {
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        let paused = pause_running(&tx, &self.assistant_id)?;
        tx.commit()?;
        Ok(paused)
    }
}

fn bounded_text(value: &str, max: usize) -> Result<()> {
    ensure!(
        !value.trim().is_empty() && value.len() <= max && !value.contains('\0'),
        "Invalid text"
    );
    Ok(())
}
fn directory_key(directory: &Path) -> Result<String> {
    let key = directory
        .to_str()
        .context("Task directory must be UTF-8")?
        .to_owned();
    #[cfg(windows)]
    let key = key.to_lowercase();
    Ok(key)
}
fn takeover(db: &Connection, assistant_id: &str) -> Result<bool> {
    Ok(db.query_row(
        "SELECT taken_over FROM assistant_computers WHERE assistant_id=?1",
        [assistant_id],
        |row| row.get(0),
    )?)
}
fn read_task(db: &Connection, assistant_id: &str, task_id: &str) -> Result<Task> {
    let data: Option<String> = db
        .query_row(
            "SELECT data FROM assistant_tasks WHERE assistant_id=?1 AND id=?2",
            params![assistant_id, task_id],
            |row| row.get(0),
        )
        .optional()?;
    Ok(serde_json::from_str(&data.context("Task not found")?)?)
}
fn read_tasks(db: &Connection, assistant_id: &str, status: Option<&str>) -> Result<Vec<Task>> {
    let mut stmt = db.prepare("SELECT data FROM assistant_tasks WHERE assistant_id=?1 AND (?2 IS NULL OR status=?2) ORDER BY sequence")?;
    let rows = stmt.query_map(params![assistant_id, status], |row| row.get::<_, String>(0))?;
    rows.map(|row| Ok(serde_json::from_str(&row?)?)).collect()
}
fn save_task(db: &Connection, task: &Task) -> Result<()> {
    let status = match task.status {
        TaskStatus::Queued => "queued",
        TaskStatus::Running => "running",
        TaskStatus::WaitingUser => "waiting_user",
        TaskStatus::Paused => "paused",
        TaskStatus::Done => "done",
        TaskStatus::Failed => "failed",
    };
    let changed = db.execute("UPDATE assistant_tasks SET status=?1,active_run_id=?2,data=?3 WHERE assistant_id=?4 AND id=?5",
        params![status, task.active_run_id, serde_json::to_string(task)?, task.assistant_id, task.id])?;
    if changed != 1 {
        bail!("Task not found");
    }
    Ok(())
}
fn pause_running(db: &Connection, assistant_id: &str) -> Result<Vec<Task>> {
    let mut tasks = read_tasks(db, assistant_id, Some("running"))?;
    for task in &mut tasks {
        task.status = TaskStatus::Paused;
        task.reconciliation_required = true;
        save_task(db, task)?;
    }
    Ok(tasks)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fixture {
        root: tempfile::TempDir,
        store: AssistantStore,
    }
    impl Fixture {
        fn new() -> Self {
            let root = tempfile::tempdir().unwrap();
            std::fs::create_dir(root.path().join("work")).unwrap();
            let store =
                AssistantStore::open(&root.path().join("assistant.sqlite"), "computer-a").unwrap();
            Self { root, store }
        }
        fn second(&self) -> AssistantStore {
            AssistantStore::open(&self.root.path().join("assistant.sqlite"), "computer-a").unwrap()
        }
        fn create(&mut self, command_id: &str, writes: bool, due: i64) -> Task {
            let receipt = self
                .store
                .apply(
                    command_id,
                    Command::CreateTask {
                        prompt: command_id.into(),
                        directory: self.root.path().join("work"),
                        writes,
                        not_before_ms: due,
                    },
                )
                .unwrap();
            match receipt.result {
                CommandResult::Task(task) => task,
                _ => panic!("task receipt"),
            }
        }
        fn memory(&mut self, command_id: &str, text: &str) -> Memory {
            match self
                .store
                .apply(command_id, Command::Remember { text: text.into() })
                .unwrap()
                .result
            {
                CommandResult::Memory(id) => self
                    .store
                    .memories()
                    .unwrap()
                    .into_iter()
                    .find(|m| m.id == id)
                    .unwrap(),
                _ => panic!("memory receipt"),
            }
        }
    }

    #[test]
    fn identity_and_command_receipts_survive_reopen() {
        let mut f = Fixture::new();
        let id = f.store.assistant_id().to_owned();
        let command = Command::Remember {
            text: "explicit preference".into(),
        };
        let receipt = f.store.apply("remember", command.clone()).unwrap();
        let mut reopened = f.second();
        assert_eq!(reopened.assistant_id(), id);
        assert_eq!(reopened.apply("remember", command).unwrap(), receipt);
        assert_eq!(reopened.memories().unwrap().len(), 1);
        assert!(
            reopened
                .apply(
                    "remember",
                    Command::Remember {
                        text: "different".into()
                    }
                )
                .is_err()
        );
        let other =
            AssistantStore::open(&f.root.path().join("assistant.sqlite"), "computer-b").unwrap();
        assert_ne!(other.assistant_id(), id);
        assert!(other.tasks().unwrap().is_empty());
        assert!(other.memories().unwrap().is_empty());
    }

    #[test]
    fn due_tasks_and_capacity_are_shared_across_handles() {
        let mut f = Fixture::new();
        let later = f.create("later", false, 200);
        for n in 0..4 {
            f.create(&format!("ready-{n}"), false, 100);
        }
        assert!(f.store.claim_next(99).unwrap().is_none());
        let first = f.store.claim_next(100).unwrap().unwrap();
        let mut second = f.second();
        let next = second.claim_next(100).unwrap().unwrap();
        assert_ne!(first.task.id, next.task.id);
        assert!(f.store.claim_next(100).unwrap().is_some());
        assert!(second.claim_next(100).unwrap().is_none());
        assert_eq!(second.task(&later.id).unwrap().status, TaskStatus::Queued);
        second
            .finish(&first, RunOutcome::Done("ok".into()))
            .unwrap();
        assert!(second.claim_next(100).unwrap().is_some());
        assert_eq!(
            f.store
                .tasks()
                .unwrap()
                .iter()
                .filter(|t| t.status == TaskStatus::Running)
                .count(),
            MAX_CONCURRENT_TASKS
        );
    }

    #[test]
    fn canonical_write_directories_serialize_without_blocking_reads() {
        let mut f = Fixture::new();
        let writer = f.create("writer", true, 0);
        let receipt = f
            .store
            .apply(
                "alias",
                Command::CreateTask {
                    prompt: "alias".into(),
                    directory: f.root.path().join("work").join("..").join("work"),
                    writes: true,
                    not_before_ms: 0,
                },
            )
            .unwrap();
        let alias = match receipt.result {
            CommandResult::Task(t) => t,
            _ => panic!(),
        };
        assert_eq!(alias.directory, writer.directory);
        let reader = f.create("reader", false, 0);
        let run = f.store.claim_next(0).unwrap().unwrap();
        assert_eq!(run.task.id, writer.id);
        assert_eq!(f.store.claim_next(0).unwrap().unwrap().task.id, reader.id);
        assert!(f.store.claim_next(0).unwrap().is_none());
        f.store
            .finish(&run, RunOutcome::Done("written".into()))
            .unwrap();
        assert_eq!(f.store.claim_next(0).unwrap().unwrap().task.id, alias.id);
    }

    #[test]
    fn unknown_results_hold_reservations_until_reconciled() {
        let mut f = Fixture::new();
        let writer = f.create("writer", true, 0);
        let blocked = f.create("blocked", true, 0);
        let run = f.store.claim_next(0).unwrap().unwrap();
        f.store.finish(&run, RunOutcome::Unknown).unwrap();
        let mut reopened = f.second();
        let task = reopened.task(&writer.id).unwrap();
        assert_eq!(task.status, TaskStatus::Paused);
        assert!(task.reconciliation_required);
        assert_eq!(task.active_run_id.as_deref(), Some(run.run_id.as_str()));
        assert!(
            reopened
                .apply(
                    "resume",
                    Command::ResumeTask {
                        task_id: writer.id.clone()
                    }
                )
                .is_err()
        );
        assert!(reopened.claim_next(0).unwrap().is_none());
        assert!(
            reopened
                .reconcile(&writer.id, "stale-run", Reconciliation::NotDispatched)
                .is_err()
        );
        assert!(
            reopened
                .reconcile(
                    &writer.id,
                    &run.run_id,
                    Reconciliation::Resolved(RunOutcome::Unknown)
                )
                .is_err()
        );
        reopened
            .reconcile(
                &writer.id,
                &run.run_id,
                Reconciliation::Resolved(RunOutcome::Done("confirmed".into())),
            )
            .unwrap();
        assert_eq!(reopened.claim_next(0).unwrap().unwrap().task.id, blocked.id);
    }

    #[test]
    fn takeover_fences_workers_and_persists_the_automation_gate() {
        let mut f = Fixture::new();
        let task = f.create("active", false, 0);
        let queued = f.create("queued", false, 0);
        let run = f.store.claim_next(0).unwrap().unwrap();
        f.store.apply("takeover", Command::TakeOver).unwrap();
        let mut reopened = f.second();
        assert!(reopened.taken_over().unwrap());
        assert!(reopened.claim_next(0).unwrap().is_none());
        assert_eq!(reopened.task(&task.id).unwrap().status, TaskStatus::Paused);
        assert!(
            reopened
                .finish(&run, RunOutcome::Done("late".into()))
                .is_err()
        );
        reopened.apply("release", Command::ReleaseTakeover).unwrap();
        assert_eq!(reopened.claim_next(0).unwrap().unwrap().task.id, queued.id);
        reopened
            .reconcile(&task.id, &run.run_id, Reconciliation::NotDispatched)
            .unwrap();
        assert_eq!(reopened.task(&task.id).unwrap().status, TaskStatus::Paused);
        reopened
            .apply("resume", Command::ResumeTask { task_id: task.id })
            .unwrap();
        let new_run = reopened.claim_next(0).unwrap().unwrap();
        assert_ne!(new_run.run_id, run.run_id);
        assert!(
            reopened
                .finish(&run, RunOutcome::Done("stale".into()))
                .is_err()
        );
    }

    #[test]
    fn restart_recovery_and_explicit_memory_edits_are_durable() {
        let mut f = Fixture::new();
        let task = f.create("active", false, 0);
        let run = f.store.claim_next(0).unwrap().unwrap();
        let memory = f.memory("remember", "before");
        let mut reopened = f.second();
        assert_eq!(reopened.task(&task.id).unwrap().status, TaskStatus::Running);
        assert_eq!(reopened.recover_after_restart().unwrap().len(), 1);
        assert!(reopened.recover_after_restart().unwrap().is_empty());
        assert!(reopened.claim_next(0).unwrap().is_none());
        reopened
            .reconcile(
                &task.id,
                &run.run_id,
                Reconciliation::Resolved(RunOutcome::WaitingUser("approve".into())),
            )
            .unwrap();
        assert_eq!(
            reopened.task(&task.id).unwrap().status,
            TaskStatus::WaitingUser
        );
        reopened
            .apply(
                "edit",
                Command::EditMemory {
                    memory_id: memory.id.clone(),
                    text: "after".into(),
                },
            )
            .unwrap();
        assert_eq!(f.store.memories().unwrap()[0].text, "after");
        let command = Command::DeleteMemory {
            memory_id: memory.id,
        };
        let deleted = reopened.apply("delete", command.clone()).unwrap();
        assert_eq!(f.store.apply("delete", command).unwrap(), deleted);
        assert!(f.store.memories().unwrap().is_empty());
    }

    #[test]
    fn memory_command_journal_does_not_keep_edited_or_deleted_text() {
        let mut f = Fixture::new();
        let memory = f.memory("remember", "obsolete-explicit-memory-7a");
        f.store
            .apply(
                "edit",
                Command::EditMemory {
                    memory_id: memory.id.clone(),
                    text: "deleted-explicit-memory-8b".into(),
                },
            )
            .unwrap();
        f.store
            .apply(
                "delete",
                Command::DeleteMemory {
                    memory_id: memory.id,
                },
            )
            .unwrap();
        let mut stmt = f
            .store
            .db
            .prepare("SELECT payload,receipt FROM assistant_commands")
            .unwrap();
        let journal = stmt
            .query_map([], |r| {
                Ok(format!(
                    "{}{}",
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?
                ))
            })
            .unwrap()
            .collect::<rusqlite::Result<Vec<_>>>()
            .unwrap()
            .join("");
        assert!(
            !journal.contains("obsolete-explicit-memory-7a"),
            "journal retained edited memory"
        );
        assert!(
            !journal.contains("deleted-explicit-memory-8b"),
            "journal retained deleted memory"
        );
    }

    #[test]
    fn concurrent_claims_never_overbook_or_duplicate_a_task() {
        let mut f = Fixture::new();
        for n in 0..12 {
            f.create(&format!("ready-{n}"), false, 0);
        }
        // Open beforehand to isolate claim contention from schema setup.
        let stores: Vec<_> = (0..8).map(|_| f.second()).collect();
        let barrier = std::sync::Arc::new(std::sync::Barrier::new(stores.len()));
        let workers: Vec<_> = stores
            .into_iter()
            .map(|mut store| {
                let barrier = barrier.clone();
                std::thread::spawn(move || {
                    barrier.wait();
                    store.claim_next(0).unwrap()
                })
            })
            .collect();
        let runs: Vec<_> = workers
            .into_iter()
            .filter_map(|w| w.join().unwrap())
            .collect();
        assert_eq!(runs.len(), MAX_CONCURRENT_TASKS);
        let ids: std::collections::HashSet<_> = runs.iter().map(|r| &r.task.id).collect();
        assert_eq!(ids.len(), MAX_CONCURRENT_TASKS);
    }

    #[test]
    fn unresolved_read_runs_still_occupy_all_three_slots() {
        let mut f = Fixture::new();
        for n in 0..4 {
            f.create(&format!("ready-{n}"), false, 0);
        }
        let runs: Vec<_> = (0..3)
            .map(|_| f.store.claim_next(0).unwrap().unwrap())
            .collect();
        for run in &runs {
            f.store.finish(run, RunOutcome::Unknown).unwrap();
        }
        let mut reopened = f.second();
        assert!(reopened.claim_next(0).unwrap().is_none());
        reopened
            .reconcile(
                &runs[0].task.id,
                &runs[0].run_id,
                Reconciliation::NotDispatched,
            )
            .unwrap();
        assert!(reopened.claim_next(0).unwrap().is_some());
    }

    #[test]
    fn queued_pause_waiting_user_and_terminal_transitions_are_explicit() {
        let mut f = Fixture::new();
        let task = f.create("task", false, 0);
        let pause = Command::PauseTask {
            task_id: task.id.clone(),
        };
        let receipt = f.store.apply("pause", pause.clone()).unwrap();
        assert_eq!(f.store.apply("pause", pause).unwrap(), receipt);
        assert!(f.store.claim_next(0).unwrap().is_none());
        assert!(
            f.store
                .reconcile(&task.id, "no-run", Reconciliation::NotDispatched)
                .is_err()
        );
        f.store
            .apply(
                "resume",
                Command::ResumeTask {
                    task_id: task.id.clone(),
                },
            )
            .unwrap();
        let run = f.store.claim_next(0).unwrap().unwrap();
        let waiting = f
            .store
            .finish(&run, RunOutcome::WaitingUser("input needed".into()))
            .unwrap();
        assert_eq!(waiting.status, TaskStatus::WaitingUser);
        assert!(waiting.active_run_id.is_none());
        assert!(f.store.claim_next(0).unwrap().is_none());
        f.store
            .apply(
                "answer",
                Command::ResumeTask {
                    task_id: task.id.clone(),
                },
            )
            .unwrap();
        let next_run = f.store.claim_next(0).unwrap().unwrap();
        assert_ne!(run.run_id, next_run.run_id);
        let failed = f
            .store
            .finish(&next_run, RunOutcome::Failed("known failure".into()))
            .unwrap();
        assert_eq!(failed.status, TaskStatus::Failed);
        assert!(
            f.store
                .apply(
                    "bad-resume",
                    Command::ResumeTask {
                        task_id: task.id.clone()
                    }
                )
                .is_err()
        );
        assert!(
            f.store
                .apply("bad-pause", Command::PauseTask { task_id: task.id })
                .is_err()
        );
    }

    #[test]
    fn known_run_receipts_are_idempotent_and_cannot_overwrite_later_runs() {
        let mut f = Fixture::new();
        let task = f.create("task", false, 0);
        let run = f.store.claim_next(0).unwrap().unwrap();
        let outcome = RunOutcome::WaitingUser("confirm".into());
        let receipt = f.store.finish(&run, outcome.clone()).unwrap();
        let mut reopened = f.second();
        assert_eq!(reopened.finish(&run, outcome.clone()).unwrap(), receipt);
        assert!(
            reopened
                .finish(&run, RunOutcome::Done("different".into()))
                .is_err()
        );
        reopened
            .apply(
                "resume",
                Command::ResumeTask {
                    task_id: task.id.clone(),
                },
            )
            .unwrap();
        let next_run = reopened.claim_next(0).unwrap().unwrap();
        assert_eq!(reopened.finish(&run, outcome).unwrap(), receipt);
        assert_eq!(
            reopened.task(&task.id).unwrap().active_run_id.as_deref(),
            Some(next_run.run_id.as_str())
        );
        reopened.finish(&next_run, RunOutcome::Unknown).unwrap();
        let resolved = Reconciliation::Resolved(RunOutcome::Done("confirmed".into()));
        let done = reopened
            .reconcile(&task.id, &next_run.run_id, resolved.clone())
            .unwrap();
        assert_eq!(
            reopened
                .reconcile(&task.id, &next_run.run_id, resolved)
                .unwrap(),
            done
        );
    }

    #[test]
    fn create_replay_does_not_depend_on_the_directory_still_existing() {
        let mut f = Fixture::new();
        let command = Command::CreateTask {
            prompt: "durable".into(),
            directory: f.root.path().join("work"),
            writes: true,
            not_before_ms: 0,
        };
        let receipt = f.store.apply("create", command.clone()).unwrap();
        std::fs::remove_dir(f.root.path().join("work")).unwrap();
        let mut reopened = f.second();
        assert_eq!(reopened.apply("create", command).unwrap(), receipt);
        assert_eq!(reopened.tasks().unwrap().len(), 1);
    }

    #[test]
    fn invalid_commands_roll_back_and_cross_computer_access_is_rejected() {
        let mut f = Fixture::new();
        assert!(
            f.store
                .apply("bad", Command::Remember { text: " ".into() })
                .is_err()
        );
        assert!(
            f.store
                .apply(
                    "bad-dir",
                    Command::CreateTask {
                        prompt: "task".into(),
                        directory: "relative".into(),
                        writes: true,
                        not_before_ms: 0,
                    }
                )
                .is_err()
        );
        assert!(f.store.claim_next(-1).is_err());
        let count: i64 = f
            .store
            .db
            .query_row("SELECT count(*) FROM assistant_commands", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 0);
        let memory = f.memory("bad", "valid corrected command");
        let task = f.create("task", false, 0);
        let run = f.store.claim_next(0).unwrap().unwrap();
        let mut other =
            AssistantStore::open(&f.root.path().join("assistant.sqlite"), "computer-b").unwrap();
        assert!(other.task(&task.id).is_err());
        assert!(
            other
                .apply("pause", Command::PauseTask { task_id: task.id })
                .is_err()
        );
        assert!(other.finish(&run, RunOutcome::Unknown).is_err());
        assert!(
            other
                .apply(
                    "edit",
                    Command::EditMemory {
                        memory_id: memory.id.clone(),
                        text: "other".into()
                    }
                )
                .is_err()
        );
        assert!(
            other
                .apply(
                    "delete",
                    Command::DeleteMemory {
                        memory_id: memory.id
                    }
                )
                .is_err()
        );
    }

    #[test]
    fn closing_all_handles_preserves_identity_tasks_and_memory_deletions() {
        let mut f = Fixture::new();
        let assistant_id = f.store.assistant_id().to_owned();
        let task = f.create("active", true, 0);
        let run = f.store.claim_next(0).unwrap().unwrap();
        let command = Command::Remember {
            text: "explicit removable memory".into(),
        };
        let receipt = f.store.apply("remember", command.clone()).unwrap();
        let memory_id = match &receipt.result {
            CommandResult::Memory(id) => id.clone(),
            _ => panic!(),
        };
        f.store
            .apply("delete", Command::DeleteMemory { memory_id })
            .unwrap();
        let path = f.root.path().join("assistant.sqlite");
        drop(f.store);
        let mut reopened = AssistantStore::open(&path, "computer-a").unwrap();
        assert_eq!(reopened.assistant_id(), assistant_id);
        assert_eq!(
            reopened.task(&task.id).unwrap().active_run_id.as_deref(),
            Some(run.run_id.as_str())
        );
        reopened.recover_after_restart().unwrap();
        assert!(reopened.claim_next(0).unwrap().is_none());
        assert_eq!(reopened.apply("remember", command).unwrap(), receipt);
        assert!(reopened.memories().unwrap().is_empty());
    }

    #[test]
    fn blocked_writers_do_not_block_other_directories_or_release_on_pause() {
        let mut f = Fixture::new();
        let writer = f.create("writer", true, 0);
        let blocked = f.create("blocked", true, 0);
        let other_directory = f.root.path().join("other");
        std::fs::create_dir(&other_directory).unwrap();
        let receipt = f
            .store
            .apply(
                "other",
                Command::CreateTask {
                    prompt: "other".into(),
                    directory: other_directory,
                    writes: true,
                    not_before_ms: 0,
                },
            )
            .unwrap();
        let other = match receipt.result {
            CommandResult::Task(t) => t,
            _ => panic!(),
        };
        let run = f.store.claim_next(0).unwrap().unwrap();
        assert_eq!(run.task.id, writer.id);
        f.store
            .apply(
                "pause",
                Command::PauseTask {
                    task_id: writer.id.clone(),
                },
            )
            .unwrap();
        assert_eq!(f.store.claim_next(0).unwrap().unwrap().task.id, other.id);
        assert!(f.store.claim_next(0).unwrap().is_none());
        f.store
            .reconcile(
                &writer.id,
                &run.run_id,
                Reconciliation::Resolved(RunOutcome::Failed("confirmed stopped".into())),
            )
            .unwrap();
        assert_eq!(f.store.claim_next(0).unwrap().unwrap().task.id, blocked.id);
    }

    #[test]
    fn replayed_control_commands_do_not_change_current_state() {
        let mut f = Fixture::new();
        let first = f.store.apply("takeover", Command::TakeOver).unwrap();
        f.store.apply("release", Command::ReleaseTakeover).unwrap();
        assert_eq!(f.store.apply("takeover", Command::TakeOver).unwrap(), first);
        assert!(!f.store.taken_over().unwrap());
        let task = f.create("task", false, 0);
        assert_eq!(f.store.claim_next(0).unwrap().unwrap().task.id, task.id);
    }

    #[cfg(windows)]
    #[test]
    fn windows_directory_case_aliases_share_a_writer_reservation() {
        let mut f = Fixture::new();
        f.create("writer", true, 0);
        let alias = f.root.path().join("work").to_str().unwrap().to_uppercase();
        f.store
            .apply(
                "uppercase",
                Command::CreateTask {
                    prompt: "case alias".into(),
                    directory: alias.into(),
                    writes: true,
                    not_before_ms: 0,
                },
            )
            .unwrap();
        assert!(f.store.claim_next(0).unwrap().is_some());
        assert!(f.store.claim_next(0).unwrap().is_none());
    }
}
