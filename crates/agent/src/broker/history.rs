use super::*;

impl Broker {
    pub(super) async fn history_homes(&self) -> BTreeMap<String,PathBuf> {
        let mut homes=self.config.codex_homes.clone();
        homes.extend(self.state.lock().await.runtimes.values().map(|r|r.config_home.clone()));
        homes.into_iter().filter_map(|home|storage::config_store(&home).ok().map(|id|(id,home))).collect()
    }
    pub(super) async fn persisted_history(&self, device:&str, p:&Value)->Result<Value> {
        let store=field(p,"configStoreId")?;let thread=field(p,"threadId")?;
        if !crate::settings::valid_thread(thread) {return Err(classify(anyhow::anyhow!("Invalid thread identity"),Code::InvalidParams))}
        if p["computerId"].as_str().is_some_and(|id|id!=self.config.computer_id){bail!("History computer mismatch")}
        let homes=self.history_homes().await;
        let home=homes.get(store).context("Unknown history profile")?.clone();
        if self.coordinator_visibility()?.internal(store,Some(thread)){return Err(classify(anyhow::anyhow!("Private assistant thread"),Code::NotFound))}
        let cursor=p["cursor"].as_str().map(str::to_owned);let thread=thread.to_owned();
        let limit=p["limit"].as_u64().unwrap_or(20).clamp(1,100)as usize;
        let native_page=self.native_history_page(store,&thread,cursor.as_deref(),limit).await?;
        let media=self.media.clone();let runtime=format!("history:{store}");
        let mut roots=image_roots(&self.state_dir);roots.push(home.join("generated_images"));
        let result=tokio::task::spawn_blocking(move|| -> Result<Value> {
            let mut result=match native_page {Some(page)=>page,None=>crate::history_catalog::read(&home,&thread,cursor.as_deref(),limit)?};
            crate::history_catalog::mark_internal_context(&home,&thread,&mut result);
            let mut native=result["thread"].clone();native["turns"]=result["data"].clone();
            if let Some(cwd)=native["cwd"].as_str(){roots.push(PathBuf::from(cwd));}
            let roots=roots.into_iter().filter_map(|r|crate::workspace::native_image_root(&r).ok()).take(32).collect();
            let proof=crate::media::NativeHistory::from_native_thread(&native,&runtime,"persisted").ok().map(|p|p.with_image_roots(roots));
            let mut raw=json!({"thread":native});history_questions::sanitize_history(&mut raw);
            let mut projected=match proof {Some(proof)=>media.project_with_history(&raw,&runtime,"persisted",&thread,&proof),None=>media.project(&raw,&runtime,"persisted",&thread)};
            media_connection_refs(&mut projected,&runtime,"persisted",0);
            result["data"]=projected["thread"]["turns"].take();
            result["thread"]=projected["thread"].take();
            if let Some(meta)=result["thread"].as_object_mut(){meta.remove("path");meta.remove("firstUserMessage");}
            Ok(result)
        }).await?
            .map_err(|e|classify(e,Code::NativeHistoryUnavailable))?;
        if !self.device_exists(device).await{return Err(classify(anyhow::anyhow!("Device revoked"),Code::Revoked))}
        Ok(result)
    }
    pub(super) async fn persisted_directory(&self,p:&Value)->Result<(Vec<Value>,serde_json::Map<String,Value>,BTreeSet<String>,Vec<Value>)> {
        let homes=self.history_homes().await;
        let mut data=Vec::new();let mut cursors=serde_json::Map::new();let mut covered=BTreeSet::new();let mut errors=Vec::new();
        for(store,home)in homes{
            if !home.join("state_5.sqlite").is_file(){continue}
            let previous=&p["cursor"][&store];
            if previous==false{covered.insert(store.clone());cursors.insert(store,Value::Bool(false));continue}
            // A native cursor cannot be reused by the disk index after reconnect.
            let mut query=p.clone();query["cursor"]=previous.clone();
            let result=tokio::task::spawn_blocking(move||crate::history_catalog::list(&home,&query)).await?;
            let result=match result{Ok(r)=>r,Err(_)=>{errors.push(json!({"configStoreId":store,"code":"native_history_unavailable"}));continue}};
            covered.insert(store.clone());
            cursors.insert(store.clone(),if result["nextCursor"].is_string(){result["nextCursor"].clone()}else{Value::Bool(false)});
            let s=self.state.lock().await;let directory=self.directory(&s)?;let visibility=self.coordinator_visibility()?;
            for thread in result["data"].as_array().into_iter().flatten(){
                let Some(id)=thread["id"].as_str()else{continue};
                if visibility.internal(&store,Some(id)){continue}
                let owner=directory.resolve_write(&ThreadKey{computer_id:self.config.computer_id.clone(),config_store_id:store.clone(),native_thread_id:id.into()});
                // The Android reader uses the history route for an unowned thread. An unrelated online runtime in the same profile cannot claim it.
                let runtime=owner.as_ref().ok().map(|o|o.runtime_id.clone());
                let mut row=thread.clone();
                row["runtimeId"]=json!(runtime.unwrap_or_else(||format!("history:{store}")));
                row["configStoreId"]=json!(store);row["nativeSource"]=thread["source"].clone();
                row["source"]=json!(if thread["source"]=="cli"{"cli"}else{"desktop"});
                row["readable"]=json!(true);row["controllable"]=json!(owner.is_ok());
                row["controlReason"]=json!(if owner.is_ok(){"connected"}else{"history_only"});
                row["ownerRuntimeId"]=json!(owner.as_ref().ok().map(|o|&o.runtime_id));
                row["conflict"]=json!(matches!(owner,Err(ResolveError::AmbiguousOwners|ResolveError::UncertainOwner)));
                row["ownershipReason"]=json!(session_access::ownership_reason(&owner));
                row.as_object_mut().unwrap().remove("firstUserMessage");
                row.as_object_mut().unwrap().remove("path");
                row.as_object_mut().unwrap().remove("turns");
                data.push(row);
            }
        }
        Ok((data,cursors,covered,errors))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::{Connection,params};
    #[tokio::test]
    async fn native_history_reads_unowned_reverted_thread_without_resuming() {
        let (dir,b)=super::super::tests::fixture().await;
        let store=storage::config_store(dir.path()).unwrap();
        let mut peer=super::super::tests::peer(&b,"reader","cli").await;
        let p=json!({"configStoreId":store,"threadId":"saved","limit":2});
        let serving=async {
            let r=peer.recv().await; assert_eq!(r["method"],"thread/read");
            assert_eq!(r["params"]["includeTurns"],false);
            peer.reply(&r,json!({"thread":{"id":"saved","cwd":"","status":{"type":"notLoaded"}}})).await;
            let r=peer.recv().await; assert_eq!(r["method"],"thread/turns/list");
            peer.reply(&r,json!({"data":[{"id":"turn","status":"completed","items":[]}],"nextCursor":"native-next"})).await;
        };
        let (reply,served)=tokio::join!(b.persisted_history("phone",&p),tokio::time::timeout(Duration::from_secs(2),serving));
        assert!(served.is_ok(),"Read must use native history even when there is no loaded owner or rollout file");
        let reply=reply.unwrap();assert_eq!(reply["data"][0]["id"],"turn");assert!(reply["nextCursor"].is_string());
        assert!(b.state.lock().await.runtimes["reader"].loaded.is_empty());
        let mut wrong=p.clone();wrong["threadId"]=json!("other");wrong["cursor"]=reply["nextCursor"].clone();
        assert!(b.persisted_history("phone",&wrong).await.is_err());peer.assert_idle().await;
        let mut next=p.clone();next["cursor"]=reply["nextCursor"].clone();
        let serving=async {
            let r=peer.recv().await;assert_eq!(r["method"],"thread/read");
            peer.reply(&r,json!({"thread":{"id":"saved","status":{"type":"notLoaded"}}})).await;
            let r=peer.recv().await;assert_eq!(r["params"]["cursor"],"native-next");
            peer.reply(&r,json!({"data":[{"id":"older","items":[]}],"nextCursor":null})).await;
        };
        let (reply,())=tokio::join!(b.persisted_history("phone",&next),serving);
        assert_eq!(reply.unwrap()["data"][0]["id"],"older");
        b.state.lock().await.runtimes.get_mut("reader").unwrap().connection_id="restarted".into();
        assert!(b.persisted_history("phone",&next).await.is_err());peer.assert_idle().await;
    }
    #[tokio::test]
    async fn directory_history_and_images_work_without_runtime_and_reject_foreign_profile() {
        let (dir,b)=super::super::tests::fixture().await;
        let store=storage::config_store(dir.path()).unwrap();
        let index=Connection::open(dir.path().join("state_5.sqlite")).unwrap();
        index.execute_batch("CREATE TABLE threads(id TEXT PRIMARY KEY,source TEXT,title TEXT,preview TEXT,cwd TEXT,updated_at INTEGER,archived INTEGER,rollout_path TEXT,history_mode TEXT); INSERT INTO threads VALUES('disk-thread','cli','Saved','preview','',1,0,'','paginated');").unwrap();
        let history=Connection::open(dir.path().join("thread_history_1.sqlite")).unwrap();
        history.execute_batch("CREATE TABLE thread_turns(thread_id TEXT,turn_id TEXT,rollout_ordinal INTEGER,status TEXT,error_json TEXT,started_at INTEGER,completed_at INTEGER,duration_ms INTEGER); CREATE TABLE thread_items(thread_id TEXT,turn_id TEXT,item_id TEXT,rollout_ordinal INTEGER,item_json TEXT); INSERT INTO thread_turns VALUES('disk-thread','turn',1,'completed',NULL,1,2,1000);").unwrap();
        use base64::Engine;
        let encoded=base64::engine::general_purpose::STANDARD.encode(b"\x89PNG\r\n\x1a\nx");
        let item=json!({"id":"image","type":"userMessage","content":[{"type":"image","url":format!("data:image/png;base64,{encoded}")} ]});
        history.execute("INSERT INTO thread_items VALUES('disk-thread','turn','image',1,?1)",params![item.to_string()]).unwrap();
        let list=b.request("phone","threads/list",json!({})).await.unwrap();
        let row=&list["data"][0];assert_eq!(row["id"],"disk-thread");assert_eq!(row["controllable"],false);assert_eq!(row["readable"],true);
        let p=json!({"configStoreId":store,"threadId":"disk-thread","computerId":b.config.computer_id});
        let reply=b.request("phone","threads/history/read",p.clone()).await.unwrap();
        let media=&reply["data"][0]["items"][0]["content"][0];assert_eq!(media["status"],"ready");
        let mut read=media.clone();read.as_object_mut().unwrap().retain(|k,_|["runtimeId","threadId","turnId","itemId","mediaId"].contains(&k.as_str()));read["itemId"]=json!("image");read["offset"]=json!(0);
        assert_eq!(b.request("phone","thread/media/read",read).await.unwrap()["status"],"ready");
        let mut foreign=p;foreign["configStoreId"]=json!("other");assert!(b.request("phone","threads/history/read",foreign).await.is_err());
        assert!(b.state.lock().await.runtimes.is_empty());
    }
    #[tokio::test]
    async fn unrelated_online_runtime_does_not_claim_persisted_cli_history() {
        let (dir, b) = super::super::tests::fixture().await;
        let store = storage::config_store(dir.path()).unwrap();
        let db = Connection::open(dir.path().join("state_5.sqlite")).unwrap();
        db.execute_batch("CREATE TABLE threads(id TEXT PRIMARY KEY,source TEXT,title TEXT,preview TEXT,cwd TEXT,updated_at INTEGER,archived INTEGER,rollout_path TEXT); INSERT INTO threads VALUES('normal-cli','cli','CLI saved','','',1,0,'');").unwrap();
        let mut desktop = super::super::tests::peer(&b, "live-desktop", "desktop").await;
        let (result, ()) = tokio::join!(
            b.request("phone", "threads/list", json!({})),
            super::super::tests::loaded(&mut desktop, &["another-thread"])
        );
        let result = result.unwrap();
        let row = &result["data"][0];
        assert_eq!(row["runtimeId"], format!("history:{store}"));
        assert_eq!(row["source"], "cli");
        assert_eq!(row["controllable"], false);
    }
    #[tokio::test]
    async fn persisted_project_history_reads_the_current_rollout_without_a_runtime() {
        let (dir,b)=super::super::tests::fixture().await;
        let thread="00000000-0000-0000-0000-000000000001";
        let rollout="00000000-0000-0000-0000-000000000002";
        let sessions=dir.path().join("sessions");std::fs::create_dir(&sessions).unwrap();
        let path=sessions.join(format!("rollout-2026-11-19T00-00-00-{thread}_{rollout}.jsonl"));
        std::fs::write(&path,json!({"type":"session_meta","payload":{"id":thread,"history_mode":"paginated"}}).to_string()+"\n").unwrap();
        let index=Connection::open(dir.path().join("state_5.sqlite")).unwrap();
        index.execute_batch("CREATE TABLE threads(id TEXT PRIMARY KEY,source TEXT,title TEXT,preview TEXT,cwd TEXT,updated_at INTEGER,archived INTEGER,rollout_path TEXT,history_mode TEXT);").unwrap();
        // std canonicalization preserves the \\?\ drive prefix used by Codex on Windows.
        let workspace=tempfile::Builder::new().prefix(".pocket-viewed-image-").tempdir_in(std::env::current_dir().unwrap()).unwrap();
        let native_cwd=std::fs::canonicalize(workspace.path()).unwrap();
        index.execute("INSERT INTO threads VALUES(?1,'vscode','Project chat','preview',?2,1,0,?3,'paginated')",params![thread,native_cwd.to_str().unwrap(),path.to_str().unwrap()]).unwrap();
        let history=Connection::open(dir.path().join("thread_history_1.sqlite")).unwrap();
        history.execute_batch("CREATE TABLE thread_turns(thread_id TEXT,turn_id TEXT,rollout_ordinal INTEGER,status TEXT,error_json TEXT,started_at INTEGER,completed_at INTEGER,duration_ms INTEGER); CREATE TABLE thread_items(thread_id TEXT,turn_id TEXT,item_id TEXT,rollout_ordinal INTEGER,item_json TEXT);").unwrap();
        for(id,turn,status)in [(thread,"old","completed"),(rollout,"current","inProgress")] {
            history.execute("INSERT INTO thread_turns VALUES(?1,?2,1,?3,NULL,1,NULL,NULL)",params![id,turn,status]).unwrap();
            history.execute("INSERT INTO thread_items VALUES(?1,?2,'item',2,?3)",params![id,turn,json!({"id":"item","type":"agentMessage","text":turn}).to_string()]).unwrap();
        }
        let viewed = workspace.path().join("viewed.png");
        std::fs::write(&viewed,b"\x89PNG\r\n\x1a\nviewed").unwrap();
        history.execute("INSERT INTO thread_items VALUES(?1,'current','view',3,?2)",params![rollout,
            json!({"id":"view","type":"imageView","path":viewed}).to_string()]).unwrap();
        let store=storage::config_store(dir.path()).unwrap();
        let p=json!({"configStoreId":store,"threadId":thread,"computerId":b.config.computer_id,"limit":1});
        let reply=b.request("phone","threads/history/read",p).await.unwrap();
        assert_eq!(reply["data"][0]["id"],"current");assert_eq!(reply["data"][0]["status"],"inProgress");
        assert_eq!(reply["data"][0]["items"][0]["text"],"current");
        assert_eq!(reply["thread"]["id"],thread);assert_eq!(reply["readOnly"],true);
        assert!(reply["thread"].get("path").is_none());assert!(b.state.lock().await.runtimes.is_empty());
        let image=&reply["data"][0]["items"][1]["toolOutputBlocks"][0];
        assert_eq!(image["status"],"ready");
        assert_eq!(image["connectionId"],"persisted");
        let params=json!({"runtimeId":image["runtimeId"],"threadId":thread,"turnId":"current","itemId":"view","mediaId":image["mediaId"],"offset":0});
        let media=b.request("phone","thread/media/read",params.clone()).await.unwrap();
        assert_eq!(media["status"],"ready");
        let mut foreign=params;foreign["threadId"]=json!("other");
        assert!(b.request("phone","thread/media/read",foreign).await.is_err());
    }
    #[cfg(windows)]
    #[tokio::test]
    #[ignore = "requires the officially launched desktop and a real Codex home with saved CLI history"]
    async fn clean_broker_recognizes_official_desktop_and_reads_cli_without_bridge() {
        let (dir, seed) = super::super::tests::fixture().await;
        let home = PathBuf::from(std::env::var_os("USERPROFILE").unwrap()).join(".codex");
        let store = storage::config_store(&home).unwrap();
        let mut cfg = seed.config.clone();
        cfg.codex_homes = vec![home];
        let isolated = dir.path().join("isolated");
        storage::private_directory(&isolated).unwrap();
        let (broker, _) = Broker::open(cfg, isolated).unwrap();
        broker.state.lock().await.devices.add("phone".into(), "Test fixture".into(), "fixture".into(), storage::now()).unwrap();
        let status = broker.diagnostics().await;
        assert_eq!(status["nativeObservation"]["desktop"]["state"], "detected");
        assert_eq!(status["nativeInstallation"]["cliInstalled"], true);
        assert_eq!(status["runtimes"], json!([]));
        for (source, filter) in [("cli", "cli"), ("desktop", "vscode")] {
            let page = broker.request("phone", "threads/list", json!({"source":filter,"limit":100})).await.unwrap();
            assert_eq!(page["errors"], json!([]));
            let rows = page["data"].as_array().unwrap();
            assert!(!rows.is_empty(), "missing saved {source} sessions");
            let mut readable = 0;
            for row in rows {
                assert_eq!(row["source"], source);
                assert_eq!(row["runtimeId"], format!("history:{store}"));
                assert_eq!(row["controllable"], false);
                let history = broker.request("phone", "threads/history/read", json!({"configStoreId":store,"threadId":row["id"],"limit":3})).await;
                if history.is_ok_and(|h| h["data"].as_array().is_some_and(|data| data.iter().any(|turn|
                    turn["items"].as_array().is_some_and(|items| !items.is_empty())))) {
                    readable += 1;
                    if source == "desktop" { break; }
                }
            }
            assert!(readable > 0, "no saved {source} sessions had readable items");
            println!("Verified {source} history via read-only broker: {} rows returned, {readable} read with items", rows.len());
        }
        assert!(broker.state.lock().await.runtimes.is_empty());
    }
}
