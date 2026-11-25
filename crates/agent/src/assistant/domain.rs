//! v2 product records over the single Service execution journal. These records
//! never dispatch: the existing SQLite lease and profile owner remain the only
//! consumer. Source IDs make legacy import and crash recovery idempotent.
use super::*;
use rusqlite::Connection;
use serde_json::{Value, json};

pub(super) fn initialize(db: &Connection) -> Result<()> {
    db.execute_batch(
        "CREATE TABLE IF NOT EXISTS assistant_v2_entities (
      sequence INTEGER PRIMARY KEY AUTOINCREMENT, assistant_id TEXT NOT NULL,
      kind TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL,
      UNIQUE(assistant_id,kind,id));
      CREATE TABLE IF NOT EXISTS assistant_v2_memory_revisions (
      sequence INTEGER PRIMARY KEY AUTOINCREMENT, assistant_id TEXT NOT NULL,
      memory_id TEXT NOT NULL, revision INTEGER NOT NULL, data TEXT NOT NULL,
      UNIQUE(assistant_id,memory_id,revision));
      CREATE TABLE IF NOT EXISTS assistant_v2_seen (
      assistant_id TEXT NOT NULL, device TEXT NOT NULL, task_id TEXT NOT NULL,
      through_sequence INTEGER NOT NULL, PRIMARY KEY(assistant_id,device,task_id));",
    )?;
    Ok(())
}
fn put(db: &Connection, owner: &str, kind: &str, id: &str, data: Value) -> Result<()> {
    db.execute("INSERT INTO assistant_v2_entities(assistant_id,kind,id,data) VALUES(?1,?2,?3,?4)
       ON CONFLICT(assistant_id,kind,id) DO UPDATE SET data=excluded.data WHERE data<>excluded.data",
       params![owner,kind,id,serde_json::to_string(&data)?])?;
    Ok(())
}

pub(super) fn sync(db: &Connection, owner: &str, s: &State) -> Result<()> {
    let main = format!("main:{owner}");
    for conversation in s.conversations.values() {
        put(db,owner,"conversation",&conversation.id,serde_json::to_value(conversation)?)?;
    }
    for m in &s.collaboration.conversation {
        let conversation=s.origin("message",&m.id,&main);
        put(
            db,
            owner,
            "message",
            &m.id,
            json!({"id":m.id,"conversationId":conversation,"role":m.role,"text":m.text,
            "taskId":m.task_id,"createdAtMs":m.at_ms,"sourceThreadId":m.source_thread_id}),
        )?;
    }
    let tasks = db
        .prepare("SELECT data FROM assistant_tasks WHERE assistant_id=?1 ORDER BY sequence")?
        .query_map([owner], |r| r.get::<_, String>(0))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    for task in tasks {
        let task: Task = serde_json::from_str(&task)?;
        let conversation=s.origin("task",&task.id,&main);
        let operations = s
            .operations
            .values()
            .filter(|o| o.task_id.as_deref() == Some(&task.id))
            .collect::<Vec<_>>();
        let stopping = operations.iter().any(|o| {
            o.suspended
                && o.phase.live()
                && !s.released_generations.contains(&o.expected_generation)
        });
        let unknown = task.reconciliation_required
            || operations
                .iter()
                .any(|o| o.phase == OperationPhase::Unknown);
        let state = if stopping {
            "stop_requested".into()
        } else if unknown {
            "needs_verification".into()
        } else {
            serde_json::to_value(task.status)?
                .as_str()
                .unwrap()
                .to_owned()
        };
        let last = s
            .events
            .iter()
            .filter(|e| e.task_id.as_deref() == Some(&task.id))
            .map(|e| e.sequence)
            .max()
            .unwrap_or(0);
        put(
            db,
            owner,
            "task",
            &task.id,
            json!({"id":task.id,"conversationId":conversation,"goal":task.prompt,
            "parentTaskId":task.assignment.as_ref().and_then(|a|a.parent_task_id.as_ref()),
            "continuesTaskId":task.assignment.as_ref().and_then(|a|a.continue_from_task.as_ref()),
            "groupId":task.assignment.as_ref().and_then(|a|a.group_id.as_ref()),"state":state,
            "activeAttemptId":task.active_run_id,"detail":task.detail,"activitySequence":last,
            "completionCriteria":task.assignment.as_ref().map(|a|&a.completion_criteria)}),
        )?;
    }
    // Pending objects retain the exact operation/request identity shared by all views.
    // Historical records remain available after resolution.
    let mut open_attention = std::collections::BTreeSet::new();
    for op in s.operations.values() {
        let conversation=s.origin("operation",&op.id,&main);
        put(
            db,
            owner,
            "attempt",
            &op.id,
            json!({"id":op.id,"taskId":op.task_id,"conversationId":conversation,
            "state":if op.suspended && op.phase.live(){"stop_requested".into()}else{format!("{:?}",op.phase).to_lowercase()},
            "executor":op.mapping,"expectedGeneration":op.expected_generation,
            "sourceMessageIds":op.user_message_ids}),
        )?;
        if op.phase == OperationPhase::Unknown {
            let id = format!("verify:{}", op.id);
            open_attention.insert(id.clone());
            put(
                db,
                owner,
                "attention",
                &id,
                json!({"id":id,"kind":"verification","state":"open","taskId":op.task_id,
                "attemptId":op.id,"title":"核对这次执行的结果","action":"read_original_receipt","replayAllowed":false}),
            )?;
        }
        if let Some(q) = &op.question {
            if op.phase == OperationPhase::Waiting && !op.suspended {
                let id = format!("question:{}:{}", op.id, q.request_id);
                open_attention.insert(id.clone());
                put(
                    db,
                    owner,
                    "attention",
                    &id,
                    json!({"id":id,"kind":"question","state":"open","taskId":op.task_id,
                    "attemptId":op.id,"requestId":q.request_id,"questionKind":q.kind,"title":q.text}),
                )?;
            }
        }
        if let Some(body) = &op.result {
            let id = format!("result:{}", op.id);
            put(
                db,
                owner,
                "artifact",
                &id,
                json!({"id":id,"taskId":op.task_id,"attemptId":op.id,"version":op.id,
                "kind":"text","contentBytes":body.len(),"partial":op.phase!=OperationPhase::Completed}),
            )?;
        }
    }
    let previous=db.prepare("SELECT id,data FROM assistant_v2_entities WHERE assistant_id=?1 AND kind='attention' AND json_extract(data,'$.state')='open'")?
        .query_map([owner],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?)))?.collect::<rusqlite::Result<Vec<_>>>()?;
    for (id, data) in previous {
        if !open_attention.contains(&id) {
            let mut data: Value = serde_json::from_str(&data)?;
            data["state"] = json!("resolved");
            put(db, owner, "attention", &id, data)?;
        }
    }
    for p in s.schedules.values() {
        let mut data = serde_json::to_value(p)?;
        data["deleted"] = json!(false);
        put(db, owner, "plan", &p.id, data)?;
    }
    let plans = db
        .prepare("SELECT id,data FROM assistant_v2_entities WHERE assistant_id=?1 AND kind='plan'")?
        .query_map([owner], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    for (id, data) in plans {
        if !s.schedules.contains_key(&id) {
            let mut data: Value = serde_json::from_str(&data)?;
            data["deleted"] = json!(true);
            put(db, owner, "plan", &id, data)?;
        }
    }
    for r in s.schedule_runs.values() {
        put(db, owner, "planRun", &r.id, serde_json::to_value(r)?)?;
    }
    let memories = db
        .prepare("SELECT id,text FROM assistant_memories WHERE assistant_id=?1 ORDER BY sequence")?
        .query_map([owner], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let ids = memories
        .iter()
        .map(|(id, _)| id.clone())
        .collect::<std::collections::BTreeSet<_>>();
    for (id, body) in memories {
        let meta = s.memory_metadata.get(&id);
        let current = json!({"id":id,"text":body,"state":"active","source":meta.map(|m|&m.source),
            "scope":meta.map(|m|&m.scope),"automatic":meta.is_some_and(|m|m.automatic),
            "updatedAtMs":meta.map(|m|m.updated_at_ms),"authority":false});
        memory_revision(db, owner, &id, current)?;
    }
    let removed = db
        .prepare(
            "SELECT id,data FROM assistant_v2_entities WHERE assistant_id=?1 AND kind='memory'",
        )?
        .query_map([owner], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    for (id, data) in removed {
        if !ids.contains(&id) {
            let mut value: Value = serde_json::from_str(&data)?;
            if value["state"] != "deleted" {
                value["state"] = json!("deleted");
                value["text"] = Value::Null;
                value["updatedAtMs"] =
                    json!(s.memory_metadata.get(&id).and_then(|m| m.revoked_at_ms));
                memory_revision(db, owner, &id, value)?;
            }
        }
    }
    Ok(())
}
fn memory_revision(db: &Connection, owner: &str, id: &str, value: Value) -> Result<()> {
    let body = serde_json::to_string(&value)?;
    let old:Option<String>=db.query_row("SELECT data FROM assistant_v2_entities WHERE assistant_id=?1 AND kind='memory' AND id=?2",
        params![owner,id],|r|r.get(0)).optional()?;
    if old.as_deref() == Some(&body) {
        return Ok(());
    }
    db.execute("INSERT INTO assistant_v2_memory_revisions(assistant_id,memory_id,revision,data)
        SELECT ?1,?2,coalesce(max(revision),0)+1,?3 FROM assistant_v2_memory_revisions WHERE assistant_id=?1 AND memory_id=?2",
        params![owner,id,body])?;
    put(db, owner, "memory", id, value)
}

impl<E: Executor> AssistantService<E> {
    pub fn domain_snapshot(&self, device: &str) -> Result<Value> {
        let mut value = json!({"schema":2,"conversationId":self.selected_conversation_id(),"conversations":self.conversations(),
            "executionConsumer":"assistant_service","messages":[],"tasks":[],"attempts":[],"attention":[],
            "artifacts":[],"plans":[],"planRuns":[],"memories":[],"memoryRevisions":[]});
        let mut stmt=self.store.db.prepare("SELECT sequence,kind,data FROM assistant_v2_entities WHERE assistant_id=?1 AND kind<>'message' ORDER BY sequence")?;
        let rows = stmt.query_map([self.assistant_id()], |r| {
            Ok((
                r.get::<_, u64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
            ))
        })?;
        for row in rows {
            let (sequence, kind, data) = row?;
            let field = match kind.as_str() {
                "message" => "messages",
                "task" => "tasks",
                "attempt" => "attempts",
                "attention" => "attention",
                "artifact" => "artifacts",
                "plan" => "plans",
                "planRun" => "planRuns",
                "memory" => "memories",
                _ => continue,
            };
            let mut data: Value = serde_json::from_str(&data)?;
            data["sequence"] = json!(sequence);
            if kind == "task" {
                let seen:u64=self.store.db.query_row("SELECT through_sequence FROM assistant_v2_seen WHERE assistant_id=?1 AND device=?2 AND task_id=?3",
                    params![self.assistant_id(),device,data["id"].as_str()],|r|r.get(0)).optional()?.unwrap_or(0);
                data["unread"] = json!(data["activitySequence"].as_u64().unwrap_or(0) > seen);
            }
            value[field].as_array_mut().unwrap().push(data);
        }
        // The existing paged conversation API serves the full body. Do not add a
        // second unbounded transcript to every two-second phone refresh.
        value["messages"] = json!([]);
        let mut stmt=self.store.db.prepare("SELECT memory_id,revision,data FROM assistant_v2_memory_revisions WHERE assistant_id=?1 ORDER BY sequence")?;
        for row in stmt.query_map([self.assistant_id()], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, u64>(1)?,
                r.get::<_, String>(2)?,
            ))
        })? {
            let (id, revision, data) = row?;
            let mut data: Value = serde_json::from_str(&data)?;
            data["memoryId"] = json!(id);
            data["revision"] = json!(revision);
            value["memoryRevisions"].as_array_mut().unwrap().push(data);
        }
        Ok(value)
    }
    pub fn mark_task_seen(&self, device: &str, task: &str, through: u64) -> Result<()> {
        text(device, 256)?;
        self.store.task(task)?;
        ensure!(
            through <= self.state.events.last().map_or(0, |e| e.sequence),
            "Invalid activity cursor"
        );
        self.store.db.execute("INSERT INTO assistant_v2_seen VALUES(?1,?2,?3,?4) ON CONFLICT(assistant_id,device,task_id)
            DO UPDATE SET through_sequence=max(through_sequence,excluded.through_sequence)",params![self.assistant_id(),device,task,through])?;
        Ok(())
    }
    pub fn record_artifact_versions(&self, values: &[Value]) -> Result<()> {
        for value in values {
            if let (Some(id), Some(version)) = (value["id"].as_str(), value["version"].as_str()) {
                let mut metadata = value.clone();
                metadata
                    .as_object_mut()
                    .context("Invalid artifact")?
                    .remove("content");
                // Artifact IDs already include their immutable attempt identity.
                // Enrich that same record; do not duplicate every result per read.
                let old:Option<String>=self.store.db.query_row("SELECT data FROM assistant_v2_entities WHERE assistant_id=?1 AND kind='artifact' AND id=?2",
                    params![self.assistant_id(),id],|r|r.get(0)).optional()?;
                if let Some(old) = old {
                    let old: Value = serde_json::from_str(&old)?;
                    ensure!(
                        old["version"] == version,
                        "Artifact version identity changed"
                    );
                    if let Some(partial) = old.get("partial") {
                        metadata["partial"] = partial.clone();
                    }
                }
                put(
                    &self.store.db,
                    self.assistant_id(),
                    "artifact",
                    id,
                    metadata,
                )?;
            }
        }
        Ok(())
    }
}
