//! Selected-chat projection. The task catalog and execution receipts remain global.
use super::*;

pub(super) fn sync_origins<E: Executor>(
    service: &AssistantService<E>,
    db: &Connection,
) -> Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS product_conversation_origins(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS product_conversation_chat ON product_conversation_origins(conversation_id)")?;
    let rows=db.prepare("SELECT t.id,t.operation_id FROM product_timeline t LEFT JOIN product_conversation_origins c ON c.id=t.id WHERE c.id IS NULL")?
        .query_map([],|r|Ok((r.get::<_,String>(0)?,r.get::<_,Option<String>>(1)?)))?.collect::<rusqlite::Result<Vec<_>>>()?;
    // Backfill a retained timeline in one commit. A durable commit per message
    // can exhaust the actor's bounded read window on a Windows disk.
    let tx = db.unchecked_transaction()?;
    for (id, operation) in rows {
        let origin = operation
            .as_deref()
            .map(|op| service.origin_conversation("operation", op))
            .unwrap_or_else(|| service.origin_conversation("message", &id));
        tx.execute(
            "INSERT OR IGNORE INTO product_conversation_origins(id,conversation_id) VALUES(?1,?2)",
            params![id, origin],
        )?;
    }
    tx.commit()?;
    Ok(())
}

pub(super) fn annotate<E: Executor>(service: &AssistantService<E>, value: &mut Value) {
    value["conversations"] = json!(service.conversations());
    value["conversationId"] = json!(service.selected_conversation_id());
    value["activeCoordinatorOperationId"] =
        json!(service.conversation_operation_id(service.selected_conversation_id()));
    for (field, kind) in [
        ("operations", "operation"),
        ("tasks", "task"),
        ("events", "event"),
        ("deliveries", "delivery"),
        ("schedules", "schedule"),
    ] {
        for entry in value[field].as_array_mut().into_iter().flatten() {
            if let Some(id) = entry["id"].as_str() {
                entry["conversationId"] = json!(service.origin_conversation(kind, id));
            }
        }
    }
    for job in value["jobs"].as_array_mut().into_iter().flatten() {
        if let Some(id) = job["taskId"].as_str() {
            job["conversationId"] = json!(service.origin_conversation("task", id));
        }
    }
}

impl<R: RuntimeIo + Clone> Worker<R> {
    pub(super) fn project_conversation(&self, value: &mut Value, parameters: &Value) -> Result<()> {
        let conversation = self
            .service
            .resolve_conversation(parameters["conversationId"].as_str())?;
        value["conversationId"] = json!(conversation);
        value["coordinatorThreadId"] = json!(self.service.conversation_thread_id(&conversation));
        value["activeCoordinatorOperationId"] =
            json!(self.service.conversation_operation_id(&conversation));
        // Keep all execution tasks available to the global Tasks view, while a
        // chat's composer and interrupt button only see its own coordinators.
        let retained_operations = value["operations"].as_array().cloned().unwrap_or_default();
        value["operations"] = json!(
            self.service
                .operations()
                .into_iter()
                .filter(|op| op.role == Role::Execution
                    || self.service.origin_conversation("operation", &op.id) == conversation)
                .map(|op| {
                    let mut row = retained_operations
                        .iter()
                        .find(|row| row["id"] == op.id)
                        .cloned()
                        .unwrap_or_else(|| {
                            serde_json::to_value(&op).expect("operation serializes")
                        });
                    row["conversationId"] =
                        json!(self.service.origin_conversation("operation", &op.id));
                    row
                })
                .collect::<Vec<_>>()
        );
        if self.runtime_ready && self.account == AccountReadiness::Ready && !self.uncertain() {
            for record in self.executor.operation_records()? {
                if record.rejected
                    && !record.thread_submitted
                    && !record.turn_submitted
                    && record.mapping.is_none()
                    && self
                        .service
                        .coordinator_handoff_recovery_directories(&record.operation_id)
                        .is_ok()
                {
                    if let Some(op) = value["operations"]
                        .as_array_mut()
                        .and_then(|ops| ops.iter_mut().find(|op| op["id"] == record.operation_id))
                    {
                        op["handoffRecovery"] = json!({"action":"resume_original_handoff","operationId":record.operation_id,
                            "messageIds":op["user_message_ids"],"generation":record.generation});
                    }
                }
            }
        }
        let limit = parameters["limit"]
            .as_u64()
            .unwrap_or(if parameters.get("before").is_some() {
                50
            } else {
                200
            }) as usize;
        let page = timeline_page_for(
            &self.service,
            &self.messages,
            &conversation,
            parameters["before"].as_u64(),
            limit,
        )?;
        value["messages"] = page["messages"].clone();
        value["nextBefore"] = page["nextBefore"].clone();
        if parameters.get("before").is_some() || parameters.get("limit").is_some() {
            value["conversationPage"] = page;
        }
        if let Some(domain) = value["domain"].as_object_mut() {
            domain.insert("conversationId".into(), json!(conversation));
        }
        value["pendingRequests"]=json!(self.executor.pending_requests()?.into_iter().map(|p|json!({"operationId":p.operation_id,"requestId":p.handle,"method":p.method,"params":p.params})).collect::<Vec<_>>());
        value["questionHistory"] = json!(
            self.messages
                .prepare("SELECT data FROM product_question_history ORDER BY rowid DESC LIMIT 200")?
                .query_map([], |r| r.get::<_, String>(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?
                .into_iter()
                .map(|raw| serde_json::from_str::<Value>(&raw))
                .collect::<serde_json::Result<Vec<_>>>()?
        );
        for field in ["pendingRequests", "questionHistory"] {
            if let Some(entries) = value[field].as_array_mut() {
                for entry in entries.iter_mut() {
                    if let Some(id) = entry["operationId"].as_str() {
                        entry["conversationId"] =
                            json!(self.service.origin_conversation("operation", id));
                    }
                }
                entries.retain(|entry| {
                    entry["conversationId"] == conversation
                        || entry["operationId"].as_str().is_some_and(|id| {
                            self.service
                                .operations()
                                .iter()
                                .any(|op| op.id == id && op.role == Role::Execution)
                        })
                });
            }
        }
        for pending in self.executor.pending_requests()? {
            if self.executor.rejection_recovery_available(&pending)? {
                if let Some(entry) = value["pendingRequests"].as_array_mut().and_then(|items| {
                    items.iter_mut().find(|entry| {
                        entry["operationId"] == pending.operation_id
                            && entry["requestId"] == pending.handle
                    })
                }) {
                    entry["recoveryAction"] = json!("reject_original_tool_answer");
                }
            }
        }
        Ok(())
    }
}
