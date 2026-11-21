//! Saved execution settings for explicit same-ID migration. Never read credentials.
use super::*;
use std::io::{BufRead, BufReader, Read};

pub(crate) fn params(home:&Path,thread:&str)->Result<Value> {
    let db=open(home,"state_5.sqlite")?;
    columns(&db,"threads",&["id","model","model_provider","cwd","sandbox_policy","approval_mode"])?;
    let (model,provider,cwd,policy,approval):(Option<String>,String,String,String,String)=db.query_row(
        "SELECT model,model_provider,cwd,sandbox_policy,approval_mode FROM threads WHERE id=?1",[thread],
        |r|Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?)))?;
    let mut model=model.unwrap_or_default();let mut provider=provider;let mut cwd=cwd;
    let mut policy:Value=serde_json::from_str(&policy)?;
    let mut approval=serde_json::from_str::<Value>(&approval).unwrap_or(json!(approval));
    let mut reviewer=None;
    // The index can lag a turn's applied settings. Prefer the last native
    // settings event/context from the verified current rollout.
    if let Ok(path)=super::rollout_path(home,thread) {
        let file=std::fs::File::open(&path)?;
        crate::storage::verify_physical_file(&path,&file)?;
        // Bound memory per record, not per conversation. Large histories retain
        // the same settings contract; an unfinished append is not a settings event.
        let length=file.metadata()?.len();
        let mut reader=BufReader::new(file.take(length));
        let mut line=Vec::new();
        read_settings_line(&mut reader,&mut line)?;
        let header:Value=serde_json::from_slice(&line)?;
        ensure!(header["type"]=="session_meta"&&header["payload"]["id"]==thread,"Settings rollout identity mismatch");
        loop {
            line.clear();if read_settings_line(&mut reader,&mut line)?==0 {break}
            let record:Value=match serde_json::from_slice(&line) {
                Ok(v)=>v, Err(_) if !line.ends_with(b"\n")=>break, Err(e)=>return Err(e.into()),
            };
            let data=if record["type"]=="turn_context" {&record["payload"]}
                else if record["type"]=="event_msg"&&record["payload"]["type"]=="thread_settings_applied" {&record["payload"]["thread_settings"]}
                else {continue};
            if let Some(s)=data["model"].as_str(){model=s.into();}
            if let Some(s)=data.get("model_provider_id").or(data.get("model_provider")).and_then(Value::as_str){provider=s.into();}
            if let Some(s)=data["cwd"].as_str(){cwd=s.into();}
            if let Some(p)=data.get("permission_profile").or(data.get("sandbox_policy")).filter(|p|p.is_object()){policy=p.clone();}
            if let Some(v)=data.get("approval_policy"){approval=v.clone();}
            if let Some(v)=data.get("approvals_reviewer"){reviewer=Some(v.clone());}
        }
    }
    ensure!(!model.is_empty()&&!provider.is_empty()&&!cwd.is_empty(),"Saved execution settings unavailable");
    let mut p=json!({"threadId":thread,"excludeTurns":true,"model":model,"modelProvider":provider,"cwd":cwd,
        "approvalPolicy":approval});
    if let Some(v)=reviewer {p["approvalsReviewer"]=v;}
    let policy=legacy_policy(&policy,&cwd)?;
    let mode=match policy["type"].as_str(){Some("dangerFullAccess")=>"danger-full-access",Some("readOnly")=>"read-only",Some("workspaceWrite")=>"workspace-write",_=>bail!("Saved permission policy cannot be restored")};
    p["sandbox"]=json!(mode);
    if mode=="workspace-write" {
        p["config"]=json!({"sandbox_workspace_write":{
            "writable_roots":policy["writableRoots"],"network_access":policy["networkAccess"],
            "exclude_tmpdir_env_var":policy["excludeTmpdirEnvVar"],"exclude_slash_tmp":policy["excludeSlashTmp"]}});
    }
    Ok(p)
}

fn read_settings_line(reader:&mut impl BufRead,line:&mut Vec<u8>)->Result<usize> {
    let count=reader.take(64*1024*1024+1).read_until(b'\n',line)?;
    ensure!(count<=64*1024*1024,"Native settings record exceeds limit");
    Ok(count)
}

fn same_path(a:&str,b:&str)->bool {
    a.trim_start_matches(r"\\?\").replace('\\',"/").trim_end_matches('/').eq_ignore_ascii_case(
        b.trim_start_matches(r"\\?\").replace('\\',"/").trim_end_matches('/'))
}

fn legacy_policy(p:&Value,cwd:&str)->Result<Value> {
    match p["type"].as_str() {
        Some("danger-full-access"|"dangerFullAccess"|"disabled")=>return Ok(json!({"type":"dangerFullAccess"})),
        Some("read-only"|"readOnly")=>{
            ensure!(p.get("network_access").or(p.get("networkAccess")).is_none_or(|v|v==false),"Custom read-only network policy needs native profile");
            return Ok(json!({"type":"readOnly","networkAccess":false}))
        },
        Some("workspace-write"|"workspaceWrite")=>return Ok(json!({"type":"workspaceWrite",
            "writableRoots":p.get("writable_roots").or(p.get("writableRoots")).cloned().unwrap_or(json!([])),
            "networkAccess":p.get("network_access").or(p.get("networkAccess")).and_then(Value::as_bool).unwrap_or(false),
            "excludeTmpdirEnvVar":p.get("exclude_tmpdir_env_var").or(p.get("excludeTmpdirEnvVar")).and_then(Value::as_bool).unwrap_or(false),
            "excludeSlashTmp":p.get("exclude_slash_tmp").or(p.get("excludeSlashTmp")).and_then(Value::as_bool).unwrap_or(false)})),
        Some("managed")=>{},
        _=>bail!("Unsupported saved permission profile"),
    }
    ensure!(p["file_system"]["type"]=="restricted","Unsupported saved filesystem policy");
    let entries=p["file_system"]["entries"].as_array().context("Saved permissions missing")?;
    let mut root_read=false;let mut workspace=false;let mut tmp=false;let mut slash=false;let mut roots=Vec::new();
    for e in entries {
        let path=&e["path"];let access=e["access"].as_str().unwrap_or("");
        match (path["type"].as_str(),access) {
            (Some("special"),"read") if path["value"]["kind"]=="root"=>root_read=true,
            (Some("special"),"write") if path["value"]["kind"]=="tmpdir"=>tmp=true,
            (Some("special"),"write") if path["value"]["kind"]=="slash_tmp"=>slash=true,
            (Some("path"),"write")=>{
                let path=path["path"].as_str().context("Saved write root missing")?;
                if same_path(path,cwd){workspace=true}else{roots.push(path.to_owned())}
            },
            (Some("path"),"read") if e["missing_path_behavior"]=="skip"=>{
                let path=path["path"].as_str().context("Saved protected path missing")?;
                ensure!([".git",".agents",".codex",".aws"].iter().any(|name|same_path(path,&format!("{cwd}/{name}"))),"Custom protected path needs native profile");
            },
            _=>bail!("Custom permission entries need native profile"),
        }
    }
    ensure!(root_read,"Saved root permissions missing");
    let network=match p["network"].as_str(){Some("enabled")=>true,Some("restricted")=>false,_=>bail!("Saved network permissions unknown")};
    if !workspace {
        ensure!(!tmp&&!slash&&roots.is_empty()&&!network,"Read-only permission profile is not representable");
        return Ok(json!({"type":"readOnly","networkAccess":false}))
    }
    Ok(json!({"type":"workspaceWrite","writableRoots":roots,"networkAccess":network,"excludeTmpdirEnvVar":!tmp,"excludeSlashTmp":!slash}))
}

pub(crate) fn verify(params:&Value,result:&Value)->Result<()> {
    ensure!(result["thread"]["id"]==params["threadId"]&&result["model"]==params["model"]&&result["modelProvider"]==params["modelProvider"]
        && result["approvalPolicy"]==params["approvalPolicy"]&&same_path(result["cwd"].as_str().unwrap_or(""),params["cwd"].as_str().unwrap_or("")),"Native resume changed saved settings");
    let expected=match params["sandbox"].as_str(){Some("read-only")=>"readOnly",Some("workspace-write")=>"workspaceWrite",Some("danger-full-access")=>"dangerFullAccess",_=>bail!("Unknown resume permission")};
    ensure!(result["sandbox"]["type"]==expected,"Native resume changed permissions");
    if let Some(reviewer)=params.get("approvalsReviewer") {ensure!(result["approvalsReviewer"]==*reviewer,"Native resume changed approval reviewer");}
    if expected=="readOnly" {ensure!(result["sandbox"]["networkAccess"]==false,"Native resume changed read-only network permissions");}
    if expected=="workspaceWrite" {
        for (stored,native) in [("network_access","networkAccess"),("exclude_tmpdir_env_var","excludeTmpdirEnvVar"),("exclude_slash_tmp","excludeSlashTmp"),("writable_roots","writableRoots")] {
            ensure!(params["config"]["sandbox_workspace_write"][stored]==result["sandbox"][native],"Native resume changed workspace policy");
        }
    }
    Ok(())
}

#[cfg(test)]mod tests {
    use super::*;
    #[test]
    #[ignore="read-only selected local history diagnostic"]
    fn selected_native_settings_readback() {
        let home=std::path::PathBuf::from(std::env::var_os("POCKET_QA_NATIVE_HOME").unwrap());
        let thread=std::env::var("POCKET_QA_THREAD").unwrap();
        let result=params(&home,&thread).unwrap();
        println!("saved settings readable: thread={} model={} sandbox={}",thread,result["model"],result["sandbox"]);
    }
    #[test]fn settings_stream_large_history_and_preserve_latest_applied_values(){
        use std::io::Write;
        let d=super::super::tests::fixture();let sessions=d.path().join("sessions");std::fs::create_dir(&sessions).unwrap();
        let path=sessions.join("large.jsonl");let mut f=std::fs::File::create(&path).unwrap();
        writeln!(f,"{}",json!({"type":"session_meta","payload":{"id":"cli-a"}})).unwrap();
        let filler=json!({"type":"response_item","payload":{"text":"x".repeat(1024*1024)}}).to_string();
        for _ in 0..65 {writeln!(f,"{filler}").unwrap();}
        writeln!(f,"{}",json!({"type":"turn_context","payload":{"model":"latest","approval_policy":"never","sandbox_policy":{"type":"danger-full-access"}}})).unwrap();
        write!(f,"{{unfinished").unwrap();drop(f);
        let db=Connection::open(d.path().join("state_5.sqlite")).unwrap();
        db.execute_batch("ALTER TABLE threads ADD COLUMN model TEXT; ALTER TABLE threads ADD COLUMN sandbox_policy TEXT; ALTER TABLE threads ADD COLUMN approval_mode TEXT;").unwrap();
        db.execute("UPDATE threads SET model='old',sandbox_policy='{}',approval_mode='never',rollout_path=?1 WHERE id='cli-a'",[path.to_str().unwrap()]).unwrap();
        let saved=params(d.path(),"cli-a").unwrap();assert_eq!(saved["model"],"latest");assert_eq!(saved["sandbox"],"danger-full-access");
    }
    #[test]fn saved_settings_replace_current_defaults_and_readback_is_required(){
        let d=tempfile::tempdir().unwrap();let db=Connection::open(d.path().join("state_5.sqlite")).unwrap();
        db.execute_batch("CREATE TABLE threads(id TEXT,model TEXT,model_provider TEXT,cwd TEXT,sandbox_policy TEXT,approval_mode TEXT);").unwrap();
        db.execute("INSERT INTO threads VALUES('original','chosen','provider','D:/project',?1,'never')",[json!({"type":"managed","file_system":{"type":"restricted","entries":[{"path":{"type":"special","value":{"kind":"root"}},"access":"read"}]},"network":"restricted"}).to_string()]).unwrap();
        let p=params(d.path(),"original").unwrap();assert_eq!(p["model"],"chosen");assert_eq!(p["sandbox"],"read-only");
        let mut r=json!({"thread":{"id":"original"},"model":"chosen","modelProvider":"provider","cwd":"D:\\project","approvalPolicy":"never","sandbox":{"type":"readOnly","networkAccess":false}});
        verify(&p,&r).unwrap();r["model"]=json!("default");assert!(verify(&p,&r).is_err());
    }
    #[test]fn custom_deny_is_never_expanded_to_default_workspace(){
        assert!(legacy_policy(&json!({"type":"managed","file_system":{"type":"restricted","entries":[{"path":{"type":"path","path":"D:/secret"},"access":"deny"}]},"network":"restricted"}),"D:/project").is_err());
    }
    #[cfg(windows)]
    #[tokio::test]
    #[ignore="explicit native test creates one QA conversation and a bounded model turn"]
    async fn native_migration_preserves_original_model_and_read_only_permissions(){
        let home=std::path::PathBuf::from(std::env::var_os("POCKET_QA_NATIVE_HOME").expect("explicit config home"));
        let model=std::env::var("POCKET_QA_NATIVE_MODEL").expect("explicit model");
        let (tx,mut rx)=tokio::sync::mpsc::channel(256);
        let events=tokio::spawn(async move {while let Some(event)=rx.recv().await {
            match event {
                crate::native::NativeEvent::Snapshot{value,reply,..}|crate::native::NativeEvent::PendingSnapshot{value,reply,..}=>{let _=reply.send(Ok(value));},
                _=>{},
            }
        }});
        let c=crate::native::cli(&home,tx.clone()).await.unwrap();
        let cwd=std::env::current_dir().unwrap();
        let created=c.rpc.call("thread/start",json!({"model":model,"cwd":cwd,"approvalPolicy":"never","sandbox":"read-only","historyMode":"legacy"})).await.unwrap();
        let id=created["thread"]["id"].as_str().unwrap();
        c.rpc.call("thread/name/set",json!({"threadId":id,"name":"Pocket migration permission acceptance"})).await.unwrap();
        c.rpc.call("turn/start",json!({"threadId":id,"input":[{"type":"text","text":"Reply only POCKET_MIGRATION_OK. Do not call tools."}]})).await.unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(90),async {
            loop {let r=c.rpc.call("thread/read",json!({"threadId":id,"includeTurns":false})).await.unwrap();
                if r["thread"]["status"]["type"]=="idle"{break}
                tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            }
        }).await.unwrap();
        let saved=params(&home,id).unwrap();
        assert_eq!(saved["model"],model);assert_eq!(saved["approvalPolicy"],"never");assert_eq!(saved["sandbox"],"read-only");
        c.rpc.call("thread/unsubscribe",json!({"threadId":id})).await.unwrap();
        // A shared daemon deliberately retains an idle loaded thread after
        // unsubscribing. Archive/unarchive only this test-owned thread to
        // establish a cold resume without stopping a user's daemon.
        c.rpc.call("thread/archive",json!({"threadId":id})).await.unwrap();
        c.rpc.call("thread/unarchive",json!({"threadId":id})).await.unwrap();
        drop(c);
        let c=crate::native::cli(&home,tx).await.unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(10),async {
            loop {let r=c.rpc.call("thread/loaded/list",json!({"limit":100})).await.unwrap();
                if !r["data"].as_array().unwrap().iter().any(|t|t==id){break}
                tokio::time::sleep(std::time::Duration::from_millis(100)).await;
            }
        }).await.unwrap();
        let resumed=c.rpc.call("thread/resume",saved.clone()).await.unwrap();
        verify(&saved,&resumed).unwrap();
        c.rpc.call("turn/start",json!({"threadId":id,"input":[{"type":"text","text":"Reply only with the concatenation of MIGRATION_ and CONTINUE_OK. Do not call tools."}]})).await.unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(90),async {
            loop {
                let r=c.rpc.call("thread/read",json!({"threadId":id,"includeTurns":true})).await.unwrap();
                assert_eq!(r["thread"]["id"],id);
                let done=r["thread"]["turns"].as_array().into_iter().flatten()
                    .flat_map(|t|t["items"].as_array().into_iter().flatten())
                    .any(|i|i["type"]=="agentMessage"&&i["text"].as_str().is_some_and(|s|s.trim()=="MIGRATION_CONTINUE_OK"));
                if done && r["thread"]["status"]["type"]=="idle" {break}
                tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            }
        }).await.unwrap();
        println!("native_migration=passed same_id={id} saved_model=true saved_permissions=true continued_model_turn=true");
        c.rpc.call("thread/unsubscribe",json!({"threadId":id})).await.unwrap();events.abort();
    }
}
