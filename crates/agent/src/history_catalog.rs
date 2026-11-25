//! Read-only persisted history, independent of a live native connection.
use anyhow::{Context, Result, bail, ensure};
use rusqlite::{Connection, OpenFlags, OptionalExtension, params};
use serde_json::{Value, json};
use std::{collections::BTreeSet, path::Path, time::Duration};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};

const MAX_REPLY: usize = 12 * 1024 * 1024;
#[path = "history_catalog/context.rs"]
mod context;
pub(crate) use context::LiveContexts;
pub(crate) fn mark_internal_context(home: &Path, thread: &str, result: &mut Value) {
    // Missing provenance preserves the original display; never infer from text alone.
    let _ = context::mark(home, thread, result);
}
fn open(home: &Path, name: &str) -> Result<Connection> {
    let file = home.join(name);
    for candidate in [file.clone(),home.join(format!("{name}-wal")),home.join(format!("{name}-shm"))] {
        if !candidate.exists(){continue}
        for ancestor in candidate.ancestors() {
            let meta=std::fs::symlink_metadata(ancestor)?;
            ensure!(!meta.is_symlink(),"Redirected native history path");
            #[cfg(windows)] {use std::os::windows::fs::MetadataExt;ensure!(meta.file_attributes()&0x400==0,"Redirected native history path");}
        }
    }
    crate::storage::verify_physical_file(&file, &std::fs::File::open(&file)?)?;
    let db = Connection::open_with_flags(file, OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX)?;
    db.busy_timeout(Duration::from_millis(500))?;
    db.execute_batch("PRAGMA query_only=ON; PRAGMA trusted_schema=OFF;")?;
    Ok(db)
}
fn columns(db: &Connection, table: &str, required: &[&str]) -> Result<BTreeSet<String>> {
    let mut stmt = db.prepare(&format!("PRAGMA table_info({table})"))?;
    let names = stmt.query_map([], |r| r.get(1))?.collect::<rusqlite::Result<BTreeSet<String>>>()?;
    ensure!(required.iter().all(|c| names.contains(*c)), "Unsupported native history schema");
    Ok(names)
}
fn metadata(db: &Connection, id: &str) -> Result<Value> {
    let cols=columns(db,"threads", &["id","source","title","preview","cwd","updated_at","archived","rollout_path"])?;
    let mode=if cols.contains("history_mode") {"history_mode"} else {"'legacy'"};
    let first=if cols.contains("first_user_message") {"first_user_message"} else {"''"};
    let name=if cols.contains("name") && cols.contains("history_mode") {"CASE WHEN history_mode='paginated' THEN COALESCE(name,'') ELSE title END"} else {"title"};
    let sql=format!("SELECT id,source,{name},preview,cwd,updated_at,archived,rollout_path,{mode},{first} FROM threads WHERE id=?1");
    db.query_row(&sql,[id], |r|Ok(json!({"id":r.get::<_,String>(0)?,"source":r.get::<_,String>(1)?,"name":r.get::<_,String>(2)?,"preview":r.get::<_,String>(3)?,"cwd":r.get::<_,String>(4)?,"updatedAt":r.get::<_,i64>(5)?,"archived":r.get::<_,bool>(6)?,"path":r.get::<_,String>(7)?,"historyMode":r.get::<_,String>(8)?,"firstUserMessage":r.get::<_,String>(9)?,"status":{"type":"notLoaded"},"turns":[]})))
        .optional()?.context("Native thread not found")
}
fn token(value: Value) -> String { URL_SAFE_NO_PAD.encode(value.to_string()) }
pub(crate) fn rollout_path(home: &Path, thread: &str) -> Result<std::path::PathBuf> {
    ensure!(crate::settings::valid_thread(thread), "Invalid native thread identity");
    let meta=metadata(&open(home,"state_5.sqlite")?,thread)?;
    let path=dunce::canonicalize(meta["path"].as_str().context("Rollout path unavailable")?)?;
    let home=dunce::canonicalize(home)?;
    ensure!(path.starts_with(home.join("sessions")) || path.starts_with(home.join("archived_sessions")), "Rollout outside selected profile");
    ensure!(path.is_file(),"Rollout unavailable");
    Ok(path)
}
fn decode(value: &str) -> Result<Value> {
    ensure!(value.len()<=8192,"Invalid history cursor");
    Ok(serde_json::from_slice(&URL_SAFE_NO_PAD.decode(value)?)?)
}
pub fn list(home: &Path, p: &Value) -> Result<Value> {
    let db=open(home,"state_5.sqlite")?;
    let names=titles::read(home)?;
    let cols=columns(&db,"threads", &["id","source","title","preview","cwd","updated_at","archived"])?;
    let sqlite_name=cols.contains("name") && cols.contains("history_mode");
    let archived=p["archived"].as_bool().unwrap_or(false);
    let search=p["search"].as_str().unwrap_or("");ensure!(search.len()<=4096,"Search too long");
    let source=p["source"].as_str().unwrap_or("");
    let limit=p["limit"].as_u64().unwrap_or(30).clamp(1,100) as usize;
    let store=crate::storage::config_store(home)?;
    let scope=json!([store,archived,search,source]);
    let cursor=p["cursor"].as_str().map(decode).transpose()?;
    if let Some(c)=&cursor {ensure!(c["scope"]==scope&&c["at"].is_i64()&&c["id"].is_string(),"History cursor does not match query");}
    let after=cursor.as_ref().and_then(|c|c["at"].as_i64()).unwrap_or(i64::MAX);
    let after_id=cursor.as_ref().and_then(|c|c["id"].as_str()).unwrap_or("\u{10ffff}");
    let matching_names:Vec<_>=names.iter().filter(|(_,name)|name.to_lowercase().contains(&search.to_lowercase())).map(|(id,_)|id).collect();
    let title=if sqlite_name {"CASE WHEN history_mode='paginated' THEN COALESCE(name,'') ELSE title END"} else {"title"};
    let legacy=if sqlite_name {"history_mode<>'paginated'"} else {"1"};
    let sql=format!("SELECT id FROM threads WHERE archived=?1 AND source IN ('cli','vscode','appServer','unknown','desktop') AND (?2='' OR instr(lower(({title}) || ' ' || preview || ' ' || cwd),lower(?2))>0 OR ({legacy} AND id IN (SELECT value FROM json_each(?7)))) AND (?3='' OR source=?3) AND (updated_at<?4 OR (updated_at=?4 AND id<?5)) ORDER BY updated_at DESC,id DESC LIMIT ?6");
    let mut stmt=db.prepare(&sql)?;
    let ids=stmt.query_map(params![archived,search,source,after,after_id,limit+1,serde_json::to_string(&matching_names)?],|r|r.get::<_,String>(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
    let more=ids.len()>limit;
    let data=ids.iter().take(limit).map(|id| {let mut row=metadata(&db,id)?;titles::apply(&mut row,&names,sqlite_name);Ok(row)}).collect::<Result<Vec<_>>>()?;
    let next=if more {let row=data.last().unwrap();Some(token(json!({"scope":scope,"at":row["updatedAt"],"id":row["id"]})))}else{None};
    Ok(json!({"data":data,"nextCursor":next}))
}
pub fn read(home: &Path, thread: &str, cursor: Option<&str>, limit: usize) -> Result<Value> {
    ensure!(crate::settings::valid_thread(thread),"Invalid thread identity");
    let db=open(home,"state_5.sqlite")?;let mut meta=metadata(&db,thread)?;
    titles::apply(&mut meta,&titles::read(home)?,columns(&db,"threads", &["id"])?.contains("name"));
    // Persisted history does not establish whether a runtime has loaded a thread.
    meta["status"]=json!({"type":"unknown"});
    ensure!(matches!(meta["historyMode"].as_str(),Some("legacy"|"paginated")),"Unsupported native history mode");
    let scope=json!([crate::storage::config_store(home)?,thread]);
    let cursor=cursor.map(decode).transpose()?;
    if let Some(c)=&cursor {ensure!(c["scope"]==scope&&c["ordinal"].is_i64(),"History cursor does not match thread");}
    let before=cursor.as_ref().and_then(|c|c["ordinal"].as_i64()).unwrap_or(i64::MAX);
    let limit=limit.clamp(1,100);
    let mut data=Vec::new();let mut ordinals=Vec::new();
    if home.join("thread_history_1.sqlite").is_file() {
        let history=open(home,"thread_history_1.sqlite")?;
        columns(&history,"thread_turns", &["thread_id","turn_id","rollout_ordinal","status","error_json","started_at","completed_at","duration_ms"])?;
        columns(&history,"thread_items", &["thread_id","turn_id","item_id","rollout_ordinal","item_json"])?;
        history.execute_batch("BEGIN")?;
        let segments=if meta["historyMode"]=="paginated" {lineage::resolve(home,&db,thread,meta["path"].as_str().unwrap_or(""))?}
            else {vec![lineage::Segment{id:thread.into(),start:0,end:i64::MAX}]};
        let turns=lineage::turns(&history,&segments,before,limit+1)?;
        let latest=if cursor.is_none(){turns.first().map(|t|t.2.clone())}
            else{lineage::turns(&history,&segments,i64::MAX,1)?.first().map(|t|t.2.clone())};
        if latest.as_deref()==Some("inProgress") {meta["status"]=json!({"type":"active","activeFlags":[]});}
        let mut total=0usize;
        for (id,ordinal,status,error,started,completed,duration) in turns {
            ordinals.push(ordinal);
            if data.len()==limit {break}
            let raw=lineage::items(&history,&segments,&id)?;
            let mut items=Vec::new();
            for raw in raw {total+=raw.len();ensure!(total<=MAX_REPLY,"History page exceeds limit");let item:Value=serde_json::from_str(&raw)?;ensure!(item["id"].is_string()&&item["type"].is_string(),"Unsupported native item");items.push(item);}
            data.push(json!({"id":id,"status":status,"error":error.map(|s|serde_json::from_str::<Value>(&s)).transpose()?,"startedAt":started,"completedAt":completed,"durationMs":duration,"items":items}));
        }
    }
    if data.is_empty() {
        if meta["historyMode"]=="legacy" {
            let turns=legacy::read(home,thread,meta["path"].as_str().unwrap_or(""))?;
            let filtered:Vec<_>=turns.into_iter().enumerate().filter(|(i,_)|(*i as i64)<before).rev().take(limit+1).collect();
            ordinals=filtered.iter().map(|(i,_)|*i as i64).collect();
            data=filtered.into_iter().take(limit).map(|(_,v)|v).collect();
        }
        if data.is_empty() && cursor.is_none() && (meta["preview"].as_str().is_some_and(|s|!s.is_empty()) || meta["firstUserMessage"].as_str().is_some_and(|s|!s.is_empty())) {bail!("Persisted history missing or not yet projected");}
    }
    let next=if ordinals.len()>limit {Some(token(json!({"scope":scope,"ordinal":ordinals[limit-1]})))}else{None};
    let state=if data.is_empty()&&cursor.is_none(){"empty"}else{"ready"};
    let mut result = json!({"thread":meta,"data":data,"nextCursor":next,"historyState":state,"readOnly":true,"readSource":"persisted"});
    mark_internal_context(home, thread, &mut result);
    Ok(result)
}

#[path="history_catalog/legacy.rs"]
mod legacy;
mod titles;
pub(crate) mod resume;
pub(crate) mod settings;

#[path="history_catalog/lineage.rs"]
mod lineage;

#[cfg(test)]
#[path="history_catalog/lineage_tests.rs"]
mod lineage_tests;

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;
    use serde_json::json;
    pub(super) fn fixture() -> tempfile::TempDir {
        let d = tempfile::tempdir().unwrap();
        let db = Connection::open(d.path().join("state_5.sqlite")).unwrap();
        db.execute_batch("CREATE TABLE threads(id TEXT PRIMARY KEY,source TEXT,model_provider TEXT,title TEXT,preview TEXT,cwd TEXT,updated_at INTEGER,archived INTEGER,rollout_path TEXT,history_mode TEXT,first_user_message TEXT); INSERT INTO threads VALUES('cli-a','cli','custom','CLI notes','hello','D:/project',10,0,'missing.jsonl','paginated','hello'); INSERT INTO threads VALUES('desktop-b','vscode','other','Desktop notes','world','D:/project',9,0,'missing.jsonl','paginated','world'); INSERT INTO threads VALUES('empty','cli','custom','','','D:/project',8,0,'missing.jsonl','paginated','');").unwrap();
        let history = Connection::open(d.path().join("thread_history_1.sqlite")).unwrap();
        history.execute_batch("CREATE TABLE thread_turns(thread_id TEXT,turn_id TEXT,rollout_ordinal INTEGER,status TEXT,error_json TEXT,started_at INTEGER,completed_at INTEGER,duration_ms INTEGER); CREATE TABLE thread_items(thread_id TEXT,turn_id TEXT,item_id TEXT,rollout_ordinal INTEGER,item_json TEXT);").unwrap();
        for n in 1..=3 {
            history.execute("INSERT INTO thread_turns VALUES('cli-a',?1,?2,'completed',NULL,1,2,1000)",rusqlite::params![format!("t{n}"), n]).unwrap();
            history.execute("INSERT INTO thread_items VALUES('cli-a',?1,?2,?3,?4)",rusqlite::params![format!("t{n}"),format!("i{n}"),n,json!({"id":format!("i{n}"),"type":"agentMessage","text":format!("answer {n}")}).to_string()]).unwrap();
        }
        d
    }
    #[test]
    fn lists_all_providers_without_runtime_and_uses_stable_bound_pagination() {
        let d=fixture();let first=list(d.path(),&json!({"limit":1})).unwrap();
        assert_eq!(first["data"][0]["id"],"cli-a");
        let next=list(d.path(),&json!({"limit":1,"cursor":first["nextCursor"]})).unwrap();
        assert_eq!(next["data"][0]["id"],"desktop-b");
        assert!(list(d.path(),&json!({"cursor":first["nextCursor"],"search":"different"})).is_err());
    }
    #[test]
    fn sqlite_turns_survive_missing_rollout_and_page_without_duplicates() {
        let d=fixture();let before=std::fs::read(d.path().join("thread_history_1.sqlite")).unwrap();
        let first=read(d.path(),"cli-a",None,2).unwrap();
        assert_eq!(first["data"].as_array().unwrap().len(),2);
        assert_eq!(first["data"][0]["id"],"t3");
        let next=read(d.path(),"cli-a",first["nextCursor"].as_str(),2).unwrap();
        assert_eq!(next["data"][0]["items"][0]["text"],"answer 1");
        assert!(next["nextCursor"].is_null());
        assert_eq!(std::fs::read(d.path().join("thread_history_1.sqlite")).unwrap(),before);
        assert!(read(d.path(),"desktop-b",first["nextCursor"].as_str(),2).is_err());
    }
    #[test]
    fn distinguishes_empty_missing_and_unsupported_history() {
        let d=fixture();
        assert_eq!(read(d.path(),"empty",None,10).unwrap()["historyState"],"empty");
        assert!(read(d.path(),"desktop-b",None,10).is_err());
        assert!(read(d.path(),"not-found",None,10).is_err());
        Connection::open(d.path().join("thread_history_1.sqlite")).unwrap().execute_batch("DROP TABLE thread_items").unwrap();
        assert!(read(d.path(),"cli-a",None,10).is_err());
    }
    #[test]
    fn desktop_session_names_replace_initial_prompts_in_list_and_history() {
        let d = fixture();
        std::fs::write(d.path().join("session_index.jsonl"), concat!(
            "{\"id\":\"cli-a\",\"thread_name\":\"Previous title\",\"updated_at\":\"2026-11-23T00:00:00Z\"}\n",
            "{\"id\":\"cli-a\",\"thread_name\":\"Finish Pocket release\",\"updated_at\":\"2026-11-24T00:00:00Z\"}\n",
            "{\"id\":\"foreign\",\"thread_name\":\"Unrelated title\",\"updated_at\":\"2026-11-24T00:00:00Z\"}\n",
            "{\"id\":\"cli-a\",\"thread_name\":\"Incomplete write"
        )).unwrap();
        let listed = list(d.path(), &json!({})).unwrap();
        assert_eq!(listed["data"][0]["name"], "Finish Pocket release");
        assert_eq!(listed["data"][0]["preview"], "hello");
        assert_eq!(listed["data"][1]["name"], "Desktop notes");
        let searched = list(d.path(), &json!({"search":"Pocket release"})).unwrap();
        assert_eq!(searched["data"].as_array().unwrap().len(), 1);
        assert_eq!(searched["data"][0]["id"], "cli-a");
        assert_eq!(read(d.path(), "cli-a", None, 1).unwrap()["thread"]["name"], "Finish Pocket release");
    }
    #[test]
    fn paginated_sqlite_name_wins_over_legacy_index_and_is_searchable() {
        let d = fixture();
        Connection::open(d.path().join("state_5.sqlite")).unwrap().execute_batch(
            "ALTER TABLE threads ADD COLUMN name TEXT; UPDATE threads SET name='Current desktop title' WHERE id='cli-a';"
        ).unwrap();
        std::fs::write(d.path().join("session_index.jsonl"), "{\"id\":\"cli-a\",\"thread_name\":\"Stale indexed title\"}\n").unwrap();
        assert_eq!(list(d.path(), &json!({})).unwrap()["data"][0]["name"], "Current desktop title");
        assert_eq!(read(d.path(), "cli-a", None, 1).unwrap()["thread"]["name"], "Current desktop title");
        assert_eq!(list(d.path(), &json!({"search":"Current desktop"})).unwrap()["data"].as_array().unwrap().len(), 1);
        assert!(list(d.path(), &json!({"search":"Stale indexed"})).unwrap()["data"].as_array().unwrap().is_empty());
    }
    #[test]
    fn valid_index_record_at_eof_does_not_require_a_newline() {
        let d = fixture();
        std::fs::write(d.path().join("session_index.jsonl"), concat!(
            "{\"id\":\"cli-a\",\"thread_name\":\"Old title\"}\n",
            "{\"id\":\"cli-a\",\"thread_name\":\"Current title\"}"
        )).unwrap();
        assert_eq!(list(d.path(), &json!({})).unwrap()["data"][0]["name"], "Current title");
    }
}
