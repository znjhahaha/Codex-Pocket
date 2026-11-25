use anyhow::{Result,ensure};
use serde_json::{Value,json};
use std::path::Path;

/// Legacy rollout projection is deliberately read-only and bounded. Never use
/// a caller-supplied path or repair native storage while browsing it.
pub(super) fn read(home:&Path,thread:&str,raw:&str)->Result<Vec<Value>> {
    let path=Path::new(raw);
    if !path.is_file(){return Ok(Vec::new())}
    let root=dunce::canonicalize(home)?;let resolved=dunce::canonicalize(path)?;
    ensure!(resolved.starts_with(root.join("sessions"))||resolved.starts_with(root.join("archived_sessions")),"Rollout is outside selected store");
    let bytes=crate::storage::read_physical(path,64*1024*1024)?;
    let mut lines=bytes.split(|c|*c==b'\n');
    let header:Value=serde_json::from_slice(lines.next().unwrap_or_default())?;
    ensure!(header["type"]=="session_meta"&&header["payload"]["id"]==thread,"Rollout identity mismatch");
    let mut turns:Vec<Value>=Vec::new();let mut id=String::new();
    let remaining:Vec<_>=lines.collect();
    let authored: std::collections::BTreeSet<String> = remaining.iter()
        .filter_map(|line| serde_json::from_slice::<Value>(line).ok())
        .filter(|r| r["type"] == "event_msg" && r["payload"]["type"] == "user_message")
        .filter_map(|r| r["payload"]["message"].as_str().map(str::to_owned)).collect();
    for (ordinal,line) in remaining.iter().enumerate(){
        if line.is_empty(){continue}
        let record=match serde_json::from_slice::<Value>(line){Ok(r)=>r,Err(_)if ordinal+1==remaining.len()&&!bytes.ends_with(b"\n")=>break,Err(e)=>return Err(e.into())};
        let p=&record["payload"];
        if record["type"]=="turn_context" || (record["type"]=="event_msg"&&p["type"]=="task_started") {
            if let Some(next)=p["turn_id"].as_str(){
                id=next.into();
                if !turns.iter().any(|t|t["id"]==id){turns.push(json!({"id":id,"status":"unknown","items":[]}));}
            }
            continue
        }
        let Some(turn)=turns.iter_mut().rev().find(|t|t["id"]==id)else{continue};
        if record["type"]=="event_msg"&&matches!(p["type"].as_str(),Some("task_complete"|"task_aborted")){
            turn["status"]=json!(if p["type"]=="task_complete"{"completed"}else{"interrupted"});continue
        }
        if record["type"]!="response_item"{continue}
        let item_id=p["id"].as_str().map(str::to_owned).unwrap_or_else(||format!("rollout-{ordinal}"));
        let mut item=match p["type"].as_str(){
            Some("message") if p["role"]=="user"=>json!({"id":item_id,"type":"userMessage","content":p["content"].as_array().into_iter().flatten().filter_map(|c|match c["type"].as_str(){Some("input_text")=>Some(json!({"type":"text","text":c["text"]})),Some("input_image")=>Some(json!({"type":"image","url":c["image_url"]})),_=>None}).collect::<Vec<_>>()}),
            Some("message") if p["role"]=="assistant"=>json!({"id":item_id,"type":"agentMessage","text":p["content"].as_array().into_iter().flatten().filter_map(|c|c["text"].as_str()).collect::<Vec<_>>().join("\n"),"phase":p["phase"]}),
            Some("reasoning")=>json!({"id":item_id,"type":"reasoning","summary":p["summary"]}),
            Some("function_call"|"custom_tool_call")=>json!({"id":item_id,"type":"dynamicToolCall","tool":p["name"],"arguments":p["arguments"],"input":p["input"],"status":"unknown"}),
            Some("function_call_output"|"custom_tool_call_output")=>json!({"id":item_id,"type":"dynamicToolCall","tool":p["call_id"],"output":p["output"],"status":"completed"}),
            _=>continue,
        };
        if item["type"] == "userMessage" {
            let text = p["content"].as_array().into_iter().flatten()
                .filter_map(|c| c["text"].as_str()).collect::<Vec<_>>().join("\n");
            // Native rollout user_message events carry provenance. A user can type
            // these same tags; that explicit event must keep their message visible.
            if !authored.contains(&text) && text.trim().starts_with("<environment_context>") && text.trim().ends_with("</environment_context>") {
                item["pocketContextSource"] = json!("runtime-environment");
            }
        }
        turn["items"].as_array_mut().unwrap().push(item);
    }
    Ok(turns)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn generated_environment_context_is_marked_but_user_authored_tags_stay_visible() {
        let home = tempfile::tempdir().unwrap();
        let sessions = home.path().join("sessions"); std::fs::create_dir(&sessions).unwrap();
        let path = sessions.join("t.jsonl");
        let generated = "<environment_context><cwd>/project</cwd></environment_context>";
        let typed = "<environment_context>I typed these tags</environment_context>";
        let records = [json!({"type":"session_meta","payload":{"id":"t"}}),
            json!({"type":"turn_context","payload":{"turn_id":"u","cwd":"/project"}}),
            json!({"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":generated}]}}),
            json!({"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":typed}]}}),
            json!({"type":"event_msg","payload":{"type":"user_message","message":typed}})];
        let source = records.iter().map(Value::to_string).collect::<Vec<_>>().join("\n") + "\n";
        std::fs::write(&path, &source).unwrap();
        let turns = read(home.path(), "t", path.to_str().unwrap()).unwrap();
        assert_eq!(turns[0]["items"][0]["pocketContextSource"], "runtime-environment");
        assert!(turns[0]["items"][1]["pocketContextSource"].is_null());
        assert_eq!(std::fs::read_to_string(path).unwrap(), source);
    }
    #[test]
    fn legacy_task_start_keeps_user_image_tools_and_terminal_state() {
        let home=tempfile::tempdir().unwrap();let sessions=home.path().join("sessions");std::fs::create_dir(&sessions).unwrap();let path=sessions.join("thread.jsonl");
        let records=[json!({"type":"session_meta","payload":{"id":"t"}}),json!({"type":"event_msg","payload":{"type":"task_started","turn_id":"u"}}),
            json!({"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"question"},{"type":"input_image","image_url":"data:image/png;base64,eA=="}]}}),
            json!({"type":"response_item","payload":{"type":"function_call","name":"shell","arguments":"{}"}}),json!({"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"result"}]}}),
            json!({"type":"event_msg","payload":{"type":"task_complete"}})];
        let text=records.into_iter().map(|v|v.to_string()).collect::<Vec<_>>().join("\n")+"\n";
        std::fs::write(&path,&text).unwrap();let turns=read(home.path(),"t",path.to_str().unwrap()).unwrap();assert_eq!(turns.len(),1);assert_eq!(turns[0]["status"],"completed");assert_eq!(turns[0]["items"].as_array().unwrap().len(),3);
        assert_eq!(turns[0]["items"][0]["content"][1]["type"],"image");assert_eq!(turns[0]["items"][2]["text"],"result");
        std::fs::write(&path,format!("{text}{{unfinished")).unwrap();assert_eq!(read(home.path(),"t",path.to_str().unwrap()).unwrap(),turns);
        std::fs::write(&path,format!("{text}{{invalid\n")).unwrap();assert!(read(home.path(),"t",path.to_str().unwrap()).is_err());
    }
}
