//! Jobs: what the thin coordinator hands out. A job is the root of an anonymous task
//! lineage that runs in one ordinary native session; follow-ups continue that session.
//! Nothing here dispatches: tasks, leases and deliveries stay with the existing machinery.
use super::*;
use serde_json::{Value, json};

/// Results finishing within this window reach the coordinator as one wake.
pub(super) const SETTLE_WINDOW_MS: i64 = 1500;
const SETTLED_LIMIT: usize = 2048;
const ACTIVE_JOBS_LIMIT: usize = 1200;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SessionControl {
    Status,
    Stop,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Job {
    pub title: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    /// The caller's explicit wish for or against an isolated branch.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub use_worktree: Option<bool>,
    pub created_at_ms: i64,
}

fn terminal(status: TaskStatus) -> bool {
    matches!(status, TaskStatus::Done | TaskStatus::Failed)
}

impl<E: Executor> AssistantService<E> {
    /// The job a task belongs to: its own id for a dispatched job, the root for a follow-up.
    pub(super) fn job_root(&self, task: &Task) -> Option<String> {
        if self.state.jobs.contains_key(&task.id) {
            return Some(task.id.clone());
        }
        self.anonymous_lineage_root(task)
            .ok()
            .filter(|root| self.state.jobs.contains_key(root))
    }
    pub(super) fn job_of_operation(&self, op: &Operation) -> Option<String> {
        let task = self.store.task(op.task_id.as_deref()?).ok()?;
        self.job_root(&task)
    }
    pub(super) fn job_model(&self, op: &Operation) -> Option<String> {
        self.job_of_operation(op)
            .and_then(|root| self.state.jobs.get(&root)?.model.clone())
    }
    /// The newest task of a job: the one a correction or a stop has to reach.
    fn job_head(&self, root: &str, tasks: &[Task]) -> Option<Task> {
        tasks
            .iter()
            .rev()
            .find(|task| self.job_root(task).as_deref() == Some(root))
            .cloned()
    }
    fn resolve_job(&self, task_id: &str) -> Result<(String, Task)> {
        let named = self.store.task(task_id).context("Job not found")?;
        let root = self.job_root(&named).context("Task is not a dispatched job")?;
        let head = self
            .job_head(&root, &self.store.tasks()?)
            .context("Job has no task")?;
        Ok((root, head))
    }
    fn job_status(&self, head: &Task) -> String {
        let operations = self
            .state
            .operations
            .values()
            .filter(|o| o.task_id.as_deref() == Some(&head.id));
        let (mut stopping, mut unknown) = (false, head.reconciliation_required);
        for o in operations {
            stopping |= o.suspended
                && o.phase.live()
                && !self.state.released_generations.contains(&o.expected_generation);
            unknown |= o.phase == OperationPhase::Unknown;
        }
        if stopping {
            "stop_requested".into()
        } else if unknown {
            "needs_verification".into()
        } else {
            serde_json::to_value(head.status)
                .ok()
                .and_then(|v| v.as_str().map(str::to_owned))
                .unwrap_or_default()
        }
    }
    fn job_thread(&self, root: &str, tasks: &[Task]) -> Option<String> {
        let members: Vec<&str> = tasks
            .iter()
            .filter(|task| self.job_root(task).as_deref() == Some(root))
            .map(|task| task.id.as_str())
            .collect();
        self.state
            .operations
            .values()
            .filter(|o| o.task_id.as_deref().is_some_and(|id| members.contains(&id)))
            .filter_map(|o| o.mapping.as_ref().map(|m| m.thread_id.clone()))
            .last()
    }
    fn job_view(&self, root: &str, tasks: &[Task]) -> Result<Value> {
        let job = self.state.jobs.get(root).context("Job not found")?;
        let head = self.job_head(root, tasks).context("Job has no task")?;
        Ok(json!({
            "taskId": root,
            "title": job.title,
            "directory": head.directory,
            "writes": head.writes,
            "status": self.job_status(&head),
            "activeTaskId": head.id,
            "threadId": self.job_thread(root, tasks),
            "model": job.model,
            "workspaceMode": "direct",
            "createdAtMs": job.created_at_ms,
            "detail": head.detail.as_deref().map(|d| collaboration::clip_bytes(d, 1000)),
        }))
    }
    /// Every job, oldest first, for the phone's task list and cards.
    pub fn jobs(&self) -> Result<Vec<Value>> {
        let tasks = self.store.tasks()?;
        tasks
            .iter()
            .filter(|task| self.state.jobs.contains_key(&task.id))
            .map(|task| self.job_view(&task.id, &tasks))
            .collect()
    }
    /// Active jobs first, then the most recently settled ones.
    fn recent_jobs(&self, limit: usize, settled: usize) -> Result<Vec<Value>> {
        let tasks = self.store.tasks()?;
        let (mut active, mut done) = (vec![], vec![]);
        for task in tasks.iter().filter(|t| self.state.jobs.contains_key(&t.id)) {
            let head = self.job_head(&task.id, &tasks).context("Job has no task")?;
            let view = self.job_view(&task.id, &tasks)?;
            if terminal(head.status) && head.active_run_id.is_none() {
                done.push(view)
            } else {
                active.push(view)
            }
        }
        let keep = done.len().saturating_sub(settled);
        active.extend(done.drain(keep..));
        active.truncate(limit);
        Ok(active)
    }
    /// The coordinator's routing table: bounded, with no results or transcripts in it.
    pub(super) fn active_jobs_summary(&self) -> Result<String> {
        let jobs = self.recent_jobs(usize::MAX, 5)?;
        let mut kept: Vec<Value> = vec![];
        for (index, job) in jobs.iter().enumerate() {
            let entry = json!({
                "taskId": job["taskId"],
                "title": job["title"].as_str().map(|t| collaboration::clip_bytes(t, 96)),
                "directory": job["directory"].as_str().map(|d| collaboration::clip_bytes(d, 160)),
                "status": job["status"],
                "workspaceMode": job["workspaceMode"],
            });
            kept.push(entry);
            let omitted = jobs.len() - index - 1;
            let mut candidate = kept.clone();
            if omitted > 0 {
                candidate.push(json!({"omitted": omitted}));
            }
            if serde_json::to_string(&candidate)?.len() > ACTIVE_JOBS_LIMIT {
                kept.pop();
                kept.push(json!({"omitted": omitted + 1}));
                break;
            }
        }
        // The marker itself may tip a nearly full list over; drop entries until it fits.
        while serde_json::to_string(&kept)?.len() > ACTIVE_JOBS_LIMIT && kept.len() > 1 {
            let omitted = kept.pop().and_then(|m| m["omitted"].as_u64()).unwrap_or(0);
            kept.pop();
            kept.push(json!({"omitted": omitted + 1}));
        }
        Ok(serde_json::to_string(&kept)?)
    }

    /// The newest public message for the coordinator's prompt. A job's result is in the
    /// conversation for the user to read; the coordinator gets only its opening lines.
    pub(super) fn coordinator_recent_conversation(&self) -> Result<Value> {
        let mut page = self.conversation_page(None, 1)?;
        for message in page["messages"].as_array_mut().into_iter().flatten() {
            let from_job = message["role"] == "Assistant"
                && message["task_id"]
                    .as_str()
                    .and_then(|id| self.store.task(id).ok())
                    .is_some_and(|task| self.job_root(&task).is_some());
            if let (true, Some(body)) = (from_job, message["text"].as_str()) {
                let clipped = collaboration::clip_bytes(body, 240).to_owned();
                if clipped.len() < body.len() {
                    message["truncated"] = json!(true);
                }
                message["text"] = json!(clipped);
            }
        }
        Ok(page)
    }

    pub(super) fn dispatch_session(
        &mut self,
        op: &Operation,
        journal: &str,
        title: String,
        prompt: String,
        directory: PathBuf,
        writes: bool,
        use_worktree: Option<bool>,
        model: Option<String>,
        now: i64,
    ) -> Result<Value> {
        let title = title.trim().to_owned();
        ensure!(
            !title.is_empty() && title.chars().count() <= 120 && !title.contains('\0'),
            "Job title must be 1..120 characters"
        );
        text(&prompt, 65536)?;
        if let Some(model) = &model {
            ensure!(
                !model.trim().is_empty() && model.len() <= 256 && !model.contains('\0'),
                "Invalid model"
            );
        }
        let directory = self.authorize_task_directory(op, &directory)?;
        // The task, not this call, owns execution: the coordinator is free as soon as it is saved.
        let receipt = self.store.apply(
            journal,
            Command::CreateTask {
                prompt,
                directory,
                writes,
                not_before_ms: 0,
            },
        )?;
        let CommandResult::Task(task) = &receipt.result else {
            anyhow::bail!("Dispatch did not create a task")
        };
        self.inherit_task_inputs(&task.id, op);
        self.state.jobs.entry(task.id.clone()).or_insert(Job {
            title,
            model,
            use_worktree,
            created_at_ms: now,
        });
        Ok(json!({"task_id": task.id, "status": "queued", "workspace_mode": "direct"}))
    }

    pub(super) fn steer_session(
        &mut self,
        op: &Operation,
        journal: &str,
        task_id: &str,
        instruction: &str,
        now: i64,
    ) -> Result<Value> {
        let (root, head) = self.resolve_job(task_id)?;
        self.authorize_task_directory(op, &head.directory)?;
        let inputs = self.operation_inputs(op);
        self.submit_targeted_with_inputs(journal, instruction, None, Some(&head.id), false, inputs, now)?;
        if let Some(entry) = self
            .state
            .collaboration
            .conversation
            .iter_mut()
            .find(|e| e.id == format!("user:{journal}"))
        {
            entry.role = "Delegation".into();
        }
        Ok(json!({"queued": true, "deliveryId": journal, "task_id": root}))
    }

    pub(super) fn control_session(
        &mut self,
        journal: &str,
        task_id: &str,
        action: SessionControl,
        now: i64,
    ) -> Result<Value> {
        let (root, head) = self.resolve_job(task_id)?;
        if action == SessionControl::Stop
            && !(terminal(head.status) || head.status == TaskStatus::Paused)
        {
            // Paused is recorded only once native evidence shows the turn stopped.
            self.apply(
                &format!("pause-tool:{}", fingerprint(&journal)?),
                Command::PauseTask { task_id: head.id },
                now,
            )?;
        }
        self.job_view(&root, &self.store.tasks()?)
    }

    pub(super) fn list_jobs(&self, limit: usize) -> Result<Value> {
        ensure!((1..=30).contains(&limit), "Job limit must be 1..30");
        Ok(json!(self.recent_jobs(limit, limit)?))
    }

    /// What the coordinator learns when a job's native turn ends: a bounded summary, never
    /// the transcript, command output or a long stack trace.
    pub(super) fn job_settlement(&self, op: &Operation, kind: &str, result: &str) -> Result<Option<String>> {
        let Some(root) = self.job_of_operation(op) else {
            return Ok(None);
        };
        let title = self
            .state
            .jobs
            .get(&root)
            .map(|job| collaboration::clip_bytes(&job.title, 240).to_owned());
        let (status, summary, error) = match kind {
            "completed" => ("completed", Some(result), None),
            "failed" => ("failed", None, Some(result)),
            _ => ("stopped", None, None),
        };
        let build = |limit: usize| {
            json!({
                "kind": "job_settled",
                "taskId": root,
                "threadId": op.mapping.as_ref().map(|m| &m.thread_id),
                "title": title,
                "status": status,
                "summary": summary.map(|s| collaboration::clip_bytes(s, limit)),
                "truncated": summary.or(error).is_some_and(|s| s.len() > limit),
                "error": error.map(|s| collaboration::clip_bytes(s, limit.min(400))),
            })
            .to_string()
        };
        // JSON escaping can more than double a clipped body; shrink until the whole fits.
        let mut limit = 1000;
        let mut detail = build(limit);
        while detail.len() > SETTLED_LIMIT && limit > 0 {
            limit /= 2;
            detail = build(limit);
        }
        ensure!(detail.len() <= SETTLED_LIMIT, "Job settlement exceeds its bound");
        Ok(Some(detail))
    }
}
