//! Product projections and durable schedule/remember actions. Native authority
//! continues to come exclusively from the existing executor and command journal.
use super::*;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ScheduleRun {
    pub id: String,
    pub schedule_id: String,
    pub due_ms: i64,
    pub created_at_ms: i64,
    pub manual: bool,
    pub task_id: Option<String>,
    pub status: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct MemoryMetadata {
    pub source: String,
    pub scope: String,
    pub automatic: bool,
    pub created_at_ms: i64,
    pub updated_at_ms: i64,
    pub revoked_at_ms: Option<i64>,
    #[serde(default)]
    pub directory: Option<PathBuf>,
    #[serde(default)]
    pub content_hash: String,
    #[serde(default)]
    pub revisions: Vec<i64>,
}

impl<E: Executor> AssistantService<E> {
    pub fn memory_usage(&self)->BTreeMap<String,Vec<String>> {self.state.memory_usage.clone()}
    pub fn memory_disabled_tasks(&self)->Vec<String> {self.state.memory_disabled_tasks.iter().cloned().collect()}
    pub(super) fn task_memories_disabled(&self,id:&str)->bool {
        let mut current=Some(id.to_owned());let mut seen=std::collections::BTreeSet::new();
        while let Some(id)=current {
            if !seen.insert(id.clone()) {break;}
            if self.state.memory_disabled_tasks.contains(&id) {return true;}
            current=self.store.task(&id).ok().and_then(|t|t.assignment).and_then(|a|a.continue_from_task);
        }
        false
    }
    pub fn set_task_memories(&mut self,command:&str,task:&str,enabled:bool)->Result<()> {
        self.store.task(task)?;
        let hash=fingerprint(&("task_memory",task,enabled))?;
        if self.replay(command,&hash)?.is_some() {return Ok(());}
        let previous=self.state.clone();
        if enabled {self.state.memory_disabled_tasks.remove(task);} else {self.state.memory_disabled_tasks.insert(task.into());}
        self.record(command,hash,serde_json::Value::Null);
        if let Err(e)=self.save() {self.state=previous;return Err(e);}
        Ok(())
    }

    pub fn memory_metadata(&self) -> BTreeMap<String, MemoryMetadata> {
        self.state.memory_metadata.clone()
    }

    pub fn remember_with_source(&mut self, id: &str, value: &str, source: &str, scope: &str, automatic: bool, now: i64) -> Result<CommandReceipt> {
        clock(now)?;text(source, 512)?;
        ensure!(matches!(scope, "personal" | "facts" | "project"), "Unknown memory scope");
        let directory=if scope=="project" {
            let roots=self.state.operations.values().find(|op|op.role==Role::Coordinator && op.user_message_ids.iter().any(|id|id==source)).context("Project memory needs an active user handoff")?.authorized_directories.clone();
            ensure!(roots.len()==1,"Project memory needs one unambiguous selected project");
            Some(roots[0].clone())
        } else {None};
        let content_hash=fingerprint(&value.trim())?;
        if automatic && !self.store.command_applied(id)? {
            ensure!(!self.state.memory_metadata.values().any(|m|m.revoked_at_ms.is_some() && m.content_hash==content_hash),"This automatic memory was revoked; explicit user restoration is required");
        }
        let command = Command::Remember {text: value.into()};
        let metadata = MemoryMetadata {source:source.into(),scope:scope.into(),automatic,created_at_ms:now,updated_at_ms:now,revoked_at_ms:None,directory,content_hash,revisions:vec![now]};
        // Persist provenance in the SAME transaction as memory text and receipt.
        // Retrying after a crash must never overwrite a subsequent revocation.
        let mut next = self.state.clone();
        let assistant_id = self.store.assistant_id.clone();
        let receipt = self.store.apply_with_effect(id, command, |db, receipt| {
            if let CommandResult::Memory(memory) = &receipt.result {next.memory_metadata.insert(memory.clone(), metadata);}
            save_state(db, &assistant_id, &next)
        })?;
        let saved:String=self.store.db.query_row("SELECT data FROM assistant_service_state WHERE assistant_id=?1",[&assistant_id],|r|r.get(0))?;
        self.state=serde_json::from_str(&saved)?;
        Ok(receipt)
    }

    pub fn undo_memory(&mut self, id: &str, memory: &str, now: i64) -> Result<()> {
        self.apply(id, Command::DeleteMemory {memory_id:memory.into()}, now)?;
        Ok(())
    }

    pub fn schedule_runs(&self) -> Result<Vec<ScheduleRun>> {
        let tasks=self.tasks()?;
        let mut runs=self.state.schedule_runs.values().cloned().collect::<Vec<_>>();
        for run in &mut runs {
            if let Some(task)=tasks.iter().find(|t|Some(&t.id)==run.task_id.as_ref()) {
                run.status=if task.reconciliation_required {"unknown".into()} else {serde_json::to_value(task.status)?.as_str().unwrap_or("unknown").into()};
            }
        }
        runs.sort_by(|a,b|b.created_at_ms.cmp(&a.created_at_ms).then(a.id.cmp(&b.id)));
        Ok(runs)
    }

    pub fn update_schedule(&mut self, command: &str, id: &str, mut spec: ScheduleSpec, missed_policy: &str, now: i64) -> Result<()> {
        clock(now)?;spec.validate()?;
        ensure!(matches!(missed_policy,"skip"|"run_once"),"Unknown missed schedule policy");
        let hash=fingerprint(&("update_schedule",id,&spec,missed_policy))?;
        if self.replay(command,&hash)?.is_some() {return Ok(());}
        spec.directory=canonical_directory(&spec.directory)?;
        let previous=self.state.clone();
        let schedule=self.state.schedules.get_mut(id).context("Schedule not found")?;
        // An edit may not widen the original project's execution authority.
        ensure!(spec.directory==schedule.spec.directory && (!spec.writes || schedule.spec.writes),"Schedule scope changed; create a new authorized schedule");
        let next=if schedule.paused {None} else {spec.next_after(now)?};
        schedule.spec=spec;schedule.next_ms=next;schedule.missed_policy=missed_policy.into();schedule.error=None;
        self.record(command,hash,serde_json::Value::Null);
        if let Err(e)=self.save() {self.state=previous;return Err(e);}
        Ok(())
    }

    pub fn set_schedule_paused(&mut self, command: &str, id: &str, paused: bool, now: i64) -> Result<()> {
        clock(now)?;
        let hash=fingerprint(&("schedule_pause",id,paused))?;
        if self.replay(command,&hash)?.is_some() {return Ok(());}
        let previous=self.state.clone();
        let schedule=self.state.schedules.get_mut(id).context("Schedule not found")?;
        let next=if paused {None} else {schedule.spec.next_after(now)?};
        schedule.paused=paused;schedule.next_ms=next;
        self.record(command,hash,serde_json::Value::Null);
        if let Err(e)=self.save() {self.state=previous;return Err(e);}
        Ok(())
    }

    pub fn run_schedule(&mut self, command: &str, id: &str, now: i64) -> Result<()> {
        clock(now)?;
        let hash=fingerprint(&("run_schedule",id))?;
        if self.replay(command,&hash)?.is_some() {return Ok(());}
        ensure!(!self.state.paused && !self.store.taken_over()?,"Resume assistant before running schedule");
        let spec=self.state.schedules.get(id).context("Schedule not found")?.spec.clone();
        let previous=self.state.clone();
        let run=format!("schedule-manual:{id}:{command}");
        self.state.schedule_runs.insert(run.clone(),ScheduleRun {id:run.clone(),schedule_id:id.into(),due_ms:now,created_at_ms:now,manual:true,task_id:None,status:"queued".into()});
        self.state.scheduled_commands.push((run,Command::CreateTask {prompt:spec.prompt,directory:spec.directory,writes:spec.writes,not_before_ms:now}));
        self.record(command,hash,serde_json::Value::Null);
        if let Err(e)=self.save() {self.state=previous;return Err(e);}
        Ok(())
    }

    /// Every native operation retains its own result version. Paths in generated
    /// Markdown are references, not proof of file existence or downloadable bytes.
    pub fn artifacts(&self) -> Vec<serde_json::Value> {
        let mut result=vec![];
        for op in self.state.operations.values() {
            let Some(body)=op.result.as_deref().filter(|s|!s.trim().is_empty()) else {continue};
            if !matches!(op.phase,OperationPhase::Completed|OperationPhase::Failed) {continue;}
            let at=self.state.events.iter().rev().find(|e|e.operation_id.as_deref()==Some(&op.id)).map(|e|e.at_ms);
            result.push(serde_json::json!({"id":format!("result:{}",op.id),"taskId":op.task_id,"operationId":op.id,"threadId":op.mapping.as_ref().map(|m|&m.thread_id),"name":if op.phase==OperationPhase::Failed {"部分结果与问题"}else{"执行结果"},"kind":"text","content":body,"version":op.id,"createdAtMs":at,"availability":"available"}));
            // Bounded parsing; raw file paths remain unavailable until the host
            // file API validates the original task's scope and captures a version.
            for (index,part) in body.split("](").skip(1).take(64).enumerate() {
                let Some(end)=part.find(')') else {continue};
                let target=part[..end].trim().trim_matches('<').trim_matches('>');
                if target.is_empty() || target.len()>4096 {continue;}
                let external=url::Url::parse(target).is_ok_and(|u|matches!(u.scheme(),"https"|"http"));
                let local=target.starts_with('/') || target.starts_with("sandbox:/") || target.as_bytes().get(1)==Some(&b':');
                if !external && !local {continue;}
                result.push(serde_json::json!({"id":format!("reference:{}:{index}",op.id),"taskId":op.task_id,"operationId":op.id,"threadId":op.mapping.as_ref().map(|m|&m.thread_id),"name":target.rsplit(['/', '\\']).next().unwrap_or(target),"kind":if external{"link"}else{"file"},"uri":target,"version":op.id,"createdAtMs":at,"availability":if external{"external"}else{"reference"}}));
            }
        }
        result
    }
}
