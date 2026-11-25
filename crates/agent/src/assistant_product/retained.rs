//! Read-only projections used before a Runtime Host is needed.
use super::*;
static SCHEDULER_START: std::sync::OnceLock<i64> = std::sync::OnceLock::new();
pub fn begin_scheduler() { SCHEDULER_START.get_or_init(||chrono::Utc::now().timestamp_millis()); }
pub(super) fn scheduler_started_at() -> Option<i64> { SCHEDULER_START.get().copied() }

pub(crate) fn create_conversation(root:&Path,computer:&str,profile:&str,caller:&str,p:&Value)->Result<Value> {
    crate::storage::private_directory(root)?;
    let command=text(p,"commandId")?;
    let conversation=crate::assistant::create_retained_conversation(&root.join("assistant.db"),computer,profile,command,p["title"].as_str(),now())?;
    let receipt=json!({"accepted":true,"commandId":command,"conversation":conversation});
    let db=messages_db(&root.join("messages.db"))?;
    let (reply,_)=oneshot::channel();
    let request=Request{method:"assistant/v2/conversation/create".into(),parameters:p.clone(),id:command.into(),caller:Some(caller.into()),directory:None,reply};
    command_receipts::record(&db,&request,&receipt)?;
    let mut response=receipt.clone();response["result"]=receipt;
    if let Ok(value)=snapshot(root,&json!({"conversationId":conversation.id})) {response["snapshot"]=value;}
    Ok(response)
}

pub(crate) fn pending_settings(root: &Path, scope: &str) -> Result<Option<Value>> {
    let path=root.join("pending-settings.json");
    if !path.try_exists()? { return Ok(None); }
    let value:Value=serde_json::from_slice(&crate::storage::read_physical(&path,1024*1024)?)?;
    Ok(value["scopes"].get(scope).cloned())
}
pub(crate) fn save_pending_settings(root: &Path, scope: &str, pending: Option<Value>) -> Result<()> {
    let path=root.join("pending-settings.json");
    let mut value=if path.try_exists()? {serde_json::from_slice::<Value>(&crate::storage::read_physical(&path,1024*1024)?)?} else {json!({"scopes":{}})};
    let scopes=value["scopes"].as_object_mut().context("Invalid pending settings")?;
    if let Some(pending)=pending { scopes.insert(scope.into(),pending); } else {scopes.remove(scope);}
    crate::storage::atomic_write(&path,&serde_json::to_vec(&value)?)
}

fn read_db(path: &Path) -> Result<Option<Connection>> {
    if !path.try_exists()? { return Ok(None); }
    let file = std::fs::File::open(path)?;
    crate::storage::verify_physical_file(path, &file)?;
    let db = Connection::open_with_flags(path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    db.busy_timeout(Duration::from_millis(50))?;
    Ok(Some(db))
}

pub(super) fn state(root: &Path) -> Result<Value> {
    let Some(db) = read_db(&root.join("assistant.db"))? else { return Ok(Value::Null); };
    let saved: Option<(String,String)> = db.query_row("SELECT assistant_id,data FROM assistant_service_state LIMIT 1", [], |r| Ok((r.get(0)?,r.get(1)?))).optional()?;
    let Some((id, data)) = saved else { return Ok(Value::Null); };
    let mut value: Value = serde_json::from_str(&data)?;
    value["assistantId"] = json!(id);
    let mut stmt = db.prepare("SELECT data FROM assistant_tasks WHERE assistant_id=?1 ORDER BY sequence")?;
    let rows = stmt.query_map([&id], |r|r.get::<_,String>(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
    value["tasks"] = json!(rows.into_iter().map(|r|serde_json::from_str::<Value>(&r)).collect::<serde_json::Result<Vec<_>>>()?);
    let mut memories=db.prepare("SELECT id,text FROM assistant_memories WHERE assistant_id=?1 ORDER BY sequence")?;
    value["memories"]=json!(memories.query_map([&id],|r|Ok(json!({"id":r.get::<_,String>(0)?,"text":r.get::<_,String>(1)?})))?.collect::<rusqlite::Result<Vec<_>>>()?);
    let entities:bool=db.query_row("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='assistant_v2_entities')",[],|r|r.get(0))?;
    if entities {
        let mut artifacts=db.prepare("SELECT data FROM assistant_v2_entities WHERE assistant_id=?1 AND kind='artifact' ORDER BY sequence")?;
        value["artifacts"]=json!(artifacts.query_map([&id],|r|r.get::<_,String>(0))?.collect::<rusqlite::Result<Vec<_>>>()?.into_iter().map(|s|serde_json::from_str::<Value>(&s)).collect::<serde_json::Result<Vec<_>>>()?);
    }
    Ok(value)
}

pub(crate) fn needs_attachment(root: &Path) -> Result<bool> {
    let state = state(root)?;
    Ok(state["operations"].as_object().is_some_and(|ops| ops.values().any(|o|
        o["mapping"].is_object() && matches!(o["phase"].as_str(), Some("Running"|"Waiting"|"Unknown")))))
}

pub(crate) fn needs_wake(root: &Path, now: i64) -> Result<bool> {
    let value = state(root)?;
    Ok(wake_from_state(&value, now))
}
fn wake_from_state(value: &Value, now: i64) -> bool {
    if value["paused"] == true { return false; }
    value["inbox"].as_array().is_some_and(|v|!v.is_empty()) ||
    value["conversation_coordinators"].as_object().is_some_and(|v|v.values().any(|c|c["inbox"].as_array().is_some_and(|i|!i.is_empty()))) ||
    value["scheduled_commands"].as_array().is_some_and(|v|!v.is_empty()) ||
    value["tasks"].as_array().is_some_and(|v|v.iter().any(|t|t["status"]=="queued" && t["reconciliation_required"]!=true && t["not_before_ms"].as_i64().is_some_and(|due|due<=now+5000))) ||
    value["schedules"].as_object().is_some_and(|v|v.values().any(|s|s["paused"]!=true && s["error"].is_null() && s["next_ms"].as_i64().is_some_and(|due|due<=now+5000)))
}

pub(crate) fn snapshot(root: &Path, p: &Value) -> Result<Value> {
    let value = state(root)?;
    let main=value["assistantId"].as_str().map(|id|format!("main:{id}"));
    let mut conversations=value["conversations"].as_object().map(|v|v.values().cloned().collect::<Vec<_>>()).unwrap_or_default();
    if conversations.is_empty() && let Some(id)=&main {
        let times=value["events"].as_array().into_iter().flatten().filter_map(|e|e["at_ms"].as_i64()).collect::<Vec<_>>();
        conversations.push(json!({"id":id,"title":"口袋助理","createdAtMs":times.iter().min().copied().unwrap_or(0),"updatedAtMs":times.iter().max().copied().unwrap_or(0)}));
    }
    conversations.sort_by(|a,b|b["updatedAtMs"].as_i64().cmp(&a["updatedAtMs"].as_i64()).then(a["id"].as_str().cmp(&b["id"].as_str())));
    let selected=p["conversationId"].as_str().map(str::to_owned).or(main.clone());
    if let Some(id)=p["conversationId"].as_str() {ensure!(conversations.iter().any(|c|c["id"]==id),"Assistant conversation not found");}
    let context=if selected==main {&value} else {&value["conversation_coordinators"][selected.as_deref().unwrap_or_default()]};
    let origin=|kind:&str,id:&str|value["conversation_origins"][format!("{kind}:{id}")].as_str().map(str::to_owned).or(main.clone());
    let values = |key:&str| value[key].as_object().map(|m|m.values().cloned().collect::<Vec<_>>()).unwrap_or_default();
    let mut out = json!({"enabled":value["assistantId"].is_string(),"ready":false,"fresh":true,"runtimeReady":false,
        "assistantId":value["assistantId"],"runtimeId":coordinator_identity(root)?.runtime_id,"coordinatorThreadId":context["coordinator_thread"],"conversationId":selected,"conversations":conversations,
        "activeCoordinatorOperationId":context["coordinator_attempt"].as_str().filter(|id|matches!(value["operations"][*id]["phase"].as_str(),Some("Running"|"Waiting"|"Unknown"))),"paused":value["paused"],
        "settings":value.get("settings").cloned().unwrap_or(serde_json::to_value(Settings::default())?),
        "tasks":value.get("tasks").cloned().unwrap_or(json!([])),"operations":values("operations"),"events":value.get("events").cloned().unwrap_or(json!([])),
        "schedules":values("schedules"),"jobs":values("jobs"),"scheduleRuns":values("schedule_runs"),"messages":[],
        "memories":value.get("memories").cloned().unwrap_or(json!([])),"memoryMetadata":value["memory_metadata"],"memoryUsage":value["memory_usage"],
        "memoryDisabledTasks":value["memory_disabled_tasks"],"artifacts":value.get("artifacts").cloned().unwrap_or(json!([])),"snapshotSource":"retained"});
    for key in ["members","groups","deliveries"] {
        out[key]=json!(value["collaboration"][key].as_object().map(|m|m.values().cloned().collect::<Vec<_>>()).unwrap_or_default());
    }
    out["assignments"]=json!(value["tasks"].as_array().into_iter().flatten().filter_map(|t|t.get("assignment").filter(|v|v.is_object()).cloned()).collect::<Vec<_>>());
    if let Some(db) = read_db(&root.join("messages.db"))? {
        let before = p["before"].as_i64().unwrap_or(i64::MAX);
        let limit = p["limit"].as_i64().unwrap_or(50);
        let has_origins:bool=db.query_row("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='product_conversation_origins')",[],|r|r.get(0))?;
        // A native stream may have committed its row immediately before a crash
        // and before the timeline index caught up. The service journal owns origin.
        let sql=if has_origins {"SELECT t.sequence,t.id,t.role,t.text,t.at_ms,t.operation_id FROM product_timeline t LEFT JOIN product_conversation_origins c ON c.id=t.id LEFT JOIN json_each(?5) o ON o.key=CASE WHEN t.operation_id IS NOT NULL THEN 'operation:'||t.operation_id ELSE 'message:'||t.id END WHERE t.sequence<?1 AND coalesce(c.conversation_id,o.value,?4)=?3 ORDER BY t.sequence DESC LIMIT ?2"}
            else {"SELECT t.sequence,t.id,t.role,t.text,t.at_ms,t.operation_id FROM product_timeline t LEFT JOIN json_each(?5) o ON o.key=CASE WHEN t.operation_id IS NOT NULL THEN 'operation:'||t.operation_id ELSE 'message:'||t.id END WHERE t.sequence<?1 AND coalesce(o.value,?4)=?3 ORDER BY t.sequence DESC LIMIT ?2"};
        let mut stmt = db.prepare(sql)?;
        let mut rows = stmt.query_map(params![before,limit+1,selected,main,value["conversation_origins"].to_string()], |r|Ok(json!({"sequence":r.get::<_,i64>(0)?,"id":r.get::<_,String>(1)?,"role":r.get::<_,String>(2)?,"text":r.get::<_,String>(3)?,"atMs":r.get::<_,i64>(4)?,"operationId":r.get::<_,Option<String>>(5)?})))?.collect::<rusqlite::Result<Vec<_>>>()?;
        let more=rows.len()>limit as usize;
        rows.truncate(limit as usize);rows.reverse();
        for message in &mut rows {
            let op=message["operationId"].as_str().and_then(|id|value["operations"].get(id));
            let entry=value["collaboration"]["conversation"].as_array().into_iter().flatten().find(|v|v["id"]==message["id"]);
            let assignment=message["operationId"].as_str().and_then(|id|value["collaboration"]["operation_assignments"].get(id));
            let member=assignment.and_then(|v|v["member_id"].as_str()).or_else(||entry.and_then(|v|v["member_id"].as_str()));
            message["conversationId"]=json!(selected);
            message["taskId"]=json!(op.and_then(|v|v["task_id"].as_str()).or_else(||entry.and_then(|v|v["task_id"].as_str())));
            message["memberId"]=json!(member);
            message["authorName"]=json!(member.and_then(|id|value["collaboration"]["members"][id]["name"].as_str()));
            message["sourceThreadId"]=json!(op.and_then(|v|v["mapping"]["thread_id"].as_str()).or_else(||entry.and_then(|v|v["source_thread_id"].as_str())));
        }
        out["nextBefore"] = json!(rows.first().and_then(|v|v["sequence"].as_i64()).filter(|_|more));
        out["messages"] = json!(rows);
        if p.get("before").is_some() || p.get("limit").is_some() {out["conversationPage"]=json!({"messages":out["messages"],"nextBefore":out["nextBefore"]});}
        let has_questions:bool=db.query_row("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='product_question_history')",[],|r|r.get(0))?;
        if has_questions {
            let mut questions=db.prepare("SELECT data FROM product_question_history ORDER BY rowid DESC LIMIT 200")?;
            out["questionHistory"]=json!(questions.query_map([],|r|r.get::<_,String>(0))?.collect::<rusqlite::Result<Vec<_>>>()?.into_iter().map(|s|serde_json::from_str::<Value>(&s)).collect::<serde_json::Result<Vec<_>>>()?);
        }
    }
    for (field,kind) in [("operations","operation"),("tasks","task"),("events","event"),("deliveries","delivery"),("schedules","schedule")] {
        for entry in out[field].as_array_mut().into_iter().flatten() {
            if let Some(id)=entry["id"].as_str() {entry["conversationId"]=json!(origin(kind,id));}
        }
    }
    if let Some(operations)=out["operations"].as_array_mut() {operations.retain(|op|op["role"]=="Execution" || op["conversationId"].as_str()==selected.as_deref());}
    if let Some(history)=out["questionHistory"].as_array_mut() {history.retain(|e|e["operationId"].as_str().is_some_and(|id|origin("operation",id)==selected || value["operations"][id]["role"]=="Execution"));}
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn model_drafts_remain_owned_by_their_original_scope() {
        let root=tempfile::tempdir().unwrap();
        save_pending_settings(root.path(),"a",Some(json!({"settings":{"model":"a-model"}}))).unwrap();
        save_pending_settings(root.path(),"b",Some(json!({"settings":{"model":null}}))).unwrap();
        assert_eq!(pending_settings(root.path(),"a").unwrap().unwrap()["settings"]["model"],"a-model");
        save_pending_settings(root.path(),"b",None).unwrap();
        assert!(pending_settings(root.path(),"b").unwrap().is_none());
        assert!(pending_settings(root.path(),"a").unwrap().is_some());
    }
    #[test]
    fn wake_only_for_due_work_not_history_or_paused_schedules() {
        assert!(!wake_from_state(&json!({"schedules":{"a":{"next_ms":100000}}}), 1));
        assert!(wake_from_state(&json!({"schedules":{"a":{"next_ms":5000}}}), 1));
        assert!(!wake_from_state(&json!({"schedules":{"a":{"next_ms":5000,"paused":true}}}), 1));
        assert!(!wake_from_state(&json!({"tasks":[{"status":"queued","not_before_ms":0,"reconciliation_required":true}]}), 1));
        assert!(!wake_from_state(&json!({"operations":{"o":{"phase":"Unknown"}}}), 1));
    }
}
