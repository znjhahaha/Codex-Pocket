use super::*;
use anyhow::ensure;
#[path = "rollout_owners.rs"]
mod rollout_owners;

#[derive(Clone,Debug,serde::Serialize,serde::Deserialize)]
#[serde(rename_all="camelCase")]
pub(super) struct Migration {
    pub operation_id:String,
    pub device:String,
    pub store:String,
    pub thread:String,
    pub phase:String,
    pub reason:Option<String>,
    pub runtime:Option<String>,
    pub native:Option<NativeProcess>,
}
impl Migration {
    fn snapshot(&self)->Value {json!({"operationId":self.operation_id,"phase":self.phase,"reason":self.reason,
        "runtimeId":self.runtime,"canSend":self.phase=="ready","threadId":self.thread,"configStoreId":self.store})}
    fn pending(&self)->bool {matches!(self.phase.as_str(),"queued"|"waiting_release"|"waiting_idle"|"attaching")}
    pub(super) fn blocks_write(&self)->bool {self.phase!="ready" && (self.pending() || self.native.is_some())}
}

impl Broker {
    // A queued recovery has not acquired a native session. If the original
    // session is already loaded by one verified runtime, join that owner instead
    // of waiting forever for it to release so a different CLI can resume it.
    async fn adopt_observed_migration_owner(&self, op: &Migration) -> Result<bool> {
        if op.native.is_some() || op.runtime.is_some()
            || !matches!(op.phase.as_str(), "queued" | "waiting_idle" | "waiting_release") {
            return Ok(false);
        }
        let state = self.state.lock().await;
        let key = ThreadKey { computer_id: self.config.computer_id.clone(),
            config_store_id: op.store.clone(), native_thread_id: op.thread.clone() };
        let Ok(owner) = self.directory(&state)?.resolve_write(&key) else { return Ok(false) };
        let Some(runtime) = state.runtimes.get(&owner.runtime_id).filter(|r|
            r.rpc.online() && r.inventory_ready && r.desktop_initialized != Some(false)
                && !r.role.is_auxiliary() && !matches!(r.role, native::NativeRole::DesktopFollower))
            else { return Ok(false) };
        let Some(native) = runtime.native_process.as_ref().filter(|p|
            !p.terminated() && NativeProcess::capture(p.pid).is_ok_and(|actual| actual == **p))
            else { return Ok(false) };
        let mut access = self.access.lock().await;
        let previous = access.clone();
        let Some(current) = access.migrations.get_mut(&format!("{}\0{}",op.store,op.thread))
            .filter(|m| m.operation_id == op.operation_id && m.native.is_none() && m.runtime.is_none()
                && matches!(m.phase.as_str(), "queued" | "waiting_idle" | "waiting_release"))
            else { return Ok(false) };
        current.phase = "ready".into();
        current.reason = None;
        current.runtime = Some(owner.runtime_id.clone());
        current.native = Some(native.clone());
        if let Err(error) = access.save(&self.state_dir) { *access = previous; return Err(error) }
        drop(access);
        drop(state);
        let _ = self.notifications.send(json!({"method":"thread/access/changed","params":{
            "threadId":op.thread,"runtimeId":owner.runtime_id,"phase":"ready","operationId":op.operation_id}}));
        Ok(true)
    }
    // A completed migration is a receipt, not a permanent execution lease.
    async fn migration_connected(&self, op:&Migration)->bool {
        let state=self.state.lock().await;
        let Some(runtime)=op.runtime.as_ref().and_then(|id|state.runtimes.get(id)) else{return false};
        if !runtime.rpc.online() || runtime.store!=op.store || op.native.is_none() || runtime.native_process!=op.native {return false}
        let key=ThreadKey{computer_id:self.config.computer_id.clone(),config_store_id:op.store.clone(),native_thread_id:op.thread.clone()};
        self.directory(&state).ok().and_then(|d|d.resolve_write(&key).ok())
            .is_some_and(|owner|Some(&owner.runtime_id)==op.runtime.as_ref())
    }
    async fn migration_view(&self, op:&Migration)->Value {
        let mut value=op.snapshot();
        if op.phase=="ready" && !self.migration_connected(op).await {
            value["phase"]=json!("disconnected");value["canSend"]=json!(false);
            value["reason"]=json!(if op.native.as_ref().is_some_and(NativeProcess::terminated){"runtime_restarted"}else{"runtime_disconnected"});
        }
        value
    }
    pub(super) async fn migration_status(&self,p:&Value)->Result<Value> {
        let key=format!("{}\0{}",field(p,"configStoreId")?,field(p,"threadId")?);
        let op=self.access.lock().await.migrations.get(&key).cloned();
        Ok(if let Some(op)=op {self.migration_view(&op).await}else{json!({"phase":"absent","canSend":false})})
    }
    pub(super) async fn begin_history_migration(&self,device:&str,p:&Value)->Result<Value> {
        let store=field(p,"configStoreId")?;let thread=field(p,"threadId")?;
        ensure!(p["computerId"].as_str().is_none_or(|c|c==self.config.computer_id),"History computer mismatch");
        ensure!(crate::settings::valid_thread(thread)&&self.history_homes().await.contains_key(store),"Unknown history profile");
        ensure!(!self.coordinator_visibility()?.internal(store,Some(thread)),"Private assistant thread");
        let key=format!("{store}\0{thread}");
        let previous=self.access.lock().await.migrations.get(&key).cloned();
        if let Some(op)=previous.as_ref() {
            if op.phase=="ready" {
                if self.migration_connected(op).await {return Ok(op.snapshot())}
                let same_instance={let s=self.state.lock().await;op.runtime.as_ref().and_then(|id|s.runtimes.get(id))
                    .is_some_and(|r|r.rpc.online()&&r.store==store&&op.native.is_some()&&r.native_process==op.native)};
                if !same_instance && !op.native.as_ref().is_some_and(NativeProcess::terminated) {
                    return Ok(self.migration_view(op).await)
                }
            } else if op.phase!="blocked" || op.reason.as_deref()==Some("resume_result_unknown") || op.native.is_some() {return Ok(op.snapshot())}
        }
        let mut access=self.access.lock().await;
        ensure!(access.migrations.len()<128||previous.is_some(),"Too many retained migrations");
        let op=Migration{operation_id:uuid::Uuid::new_v4().to_string(),device:device.into(),store:store.into(),thread:thread.into(),
            phase:"queued".into(),reason:None,runtime:None,native:None};
        access.migrations.insert(key.clone(),op.clone());
        if let Err(e)=access.save(&self.state_dir) {
            if let Some(old)=previous {access.migrations.insert(key,old);}else{access.migrations.remove(&key);}
            return Err(e)
        }
        Ok(op.snapshot())
    }
    pub(super) async fn drive_history_migrations(&self) {
        let Ok(_write)=self.writes.try_lock() else{return};
        if self.check_write_fence().is_err(){return}
        let operations=self.access.lock().await.migrations.values().filter(|m|m.pending()).cloned().collect::<Vec<_>>();
        for op in operations {
            if self.device_exists(&op.device).await {
                match self.adopt_observed_migration_owner(&op).await {
                    Ok(true) => continue,
                    Ok(false) => {},
                    Err(error) => { eprintln!("Cannot persist observed native owner: {error:#}"); continue; }
                }
            }
            let result=if !self.device_exists(&op.device).await {Ok(json!({"phase":"blocked","reason":"device_revoked"}))}
                else if op.phase=="attaching" {
                    // Ownership alone cannot prove the saved model/permissions
                    // were restored. Never replay or promote a crash to success.
                    Ok(json!({"phase":"blocked","reason":"resume_result_unknown"}))
                } else {self.takeover_history(&op).await};
            let value=result.unwrap_or_else(|e|json!({"phase":"blocked","reason":crate::error::classification(&e).unwrap_or(Code::Unknown).as_str()}));
            let key=format!("{}\0{}",op.store,op.thread);
            let mut access=self.access.lock().await;
            if let Some(current)=access.migrations.get_mut(&key).filter(|m|m.operation_id==op.operation_id) {
                current.phase=value["phase"].as_str().unwrap_or("blocked").into();
                current.reason=value["reason"].as_str().map(str::to_owned);
                if let Some(id)=value["runtimeId"].as_str(){current.runtime=Some(id.into());}
            }
            let _=access.save(&self.state_dir);
        }
    }
    pub(super) async fn migration_blocks_write(&self,store:&str,thread:&str)->bool {
        self.access.lock().await.migrations.get(&format!("{store}\0{thread}"))
            .is_some_and(Migration::blocks_write)
    }
    async fn external_release_unconfirmed(&self, home: &std::path::Path, thread: &str) -> bool {
        // Inspect holders of this exact rollout, not every Codex process on the
        // computer. A stale descriptor or reused PID is never release evidence.
        let mut known = self.state.lock().await.runtimes.values()
            .filter(|r| r.rpc.online()).filter_map(|r| r.native_process.clone()).collect::<Vec<_>>();
        // Media history indexes intentionally keep a read handle open. This
        // broker never executes native turns; its own handle is not a native owner.
        // Capture the full process identity so PID reuse cannot grant release.
        if let Ok(reader)=NativeProcess::capture(std::process::id()) {known.push(reader);}
        let home = home.to_owned();
        let thread = thread.to_owned();
        tokio::task::spawn_blocking(move || {
            let path = crate::history_catalog::rollout_path(&home,&thread)?;
            let owners = rollout_owners::holders(&path)?;
            Ok::<bool,anyhow::Error>(unregistered_identities(&known,&owners,|p|NativeProcess::capture(p.pid).ok()))
        }).await.ok().and_then(Result::ok).unwrap_or(true)
    }

    async fn takeover_history(&self,op:&Migration)->Result<Value>{
        let store=op.store.as_str();let thread=op.thread.as_str();
        ensure!(crate::settings::valid_thread(thread),"Invalid native thread identity");
        let home=self.history_homes().await.remove(store).context("Unknown history profile")?;
        if self.coordinator_visibility()?.internal(store,Some(thread)){bail!("Private assistant thread")}
        if self.external_release_unconfirmed(&home,thread).await {
            return Ok(json!({"phase":"waiting_release","reason":"external_runtime_active","canSend":false}))
        }
        let settings_home=home.clone();let settings_thread=thread.to_owned();
        let resume=match tokio::task::spawn_blocking(move||crate::history_catalog::resume::params(&settings_home,&settings_thread)).await? {
            Ok(params)=>params,
            Err(_)=>return Ok(json!({"phase":"blocked","reason":"saved_settings_unavailable","canSend":false})),
        };
        let existing={let s=self.state.lock().await;s.runtimes.iter().find(|(_,r)|r.store==store&&r.rpc.online()&&r.source=="cli"&&!r.role.assistant_private()).map(|(id,_)|id.clone())};
        let hosted=crate::managed_runtime::packaged();
        if existing.is_none()&&!hosted{
            let home_copy=home.clone();
            let profile=tokio::task::spawn_blocking(move||{
                crate::managed_runtime::discovery::for_cli_home(&home_copy)
            }).await??;
            if self.external_release_unconfirmed(&home,thread).await {return Ok(json!({"phase":"waiting_release","reason":"external_runtime_active","canSend":false}))}
            let mut command=tokio::process::Command::new(profile.native_binary());
            command.args(["app-server","daemon","start"]).env("CODEX_HOME",profile.config_home())
                .kill_on_drop(true)
                .env_remove("CODEX_CLI_PATH").stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null());
            #[cfg(windows)] command.creation_flags(0x08000000);
            let mut child=command.spawn()?;
            let status=tokio::time::timeout(Duration::from_secs(15),child.wait()).await;
            if !matches!(status,Ok(Ok(s))if s.success()){return Ok(json!({"phase":"waiting_release","reason":"native_start_unconfirmed","canSend":false}))}
        }
        let runtime=if let Some(id)=existing{id}else if hosted{
            match self.ensure_device_runtime().await {
                Ok(id)=>id,
                Err(_)=>return Ok(json!({"phase":"waiting_release","reason":"native_start_unconfirmed","canSend":false})),
            }
        }else{
            let connected=native::cli(&home,self.native_events.clone()).await?;let id=connected.runtime_id.clone();self.add_runtime(connected).await?;id
        };
        self.refresh_loaded().await?;
        let (rpc,generation)={let s=self.state.lock().await;
            let key=ThreadKey{computer_id:self.config.computer_id.clone(),config_store_id:store.into(),native_thread_id:thread.into()};
            match self.directory(&s)?.resolve_write(&key){
                Ok(owner)if owner.runtime_id!=runtime=>return Ok(json!({"phase":"waiting_release","reason":"thread_owned_elsewhere","canSend":false})),
                Err(ResolveError::AmbiguousOwners|ResolveError::UncertainOwner)=>return Ok(json!({"phase":"waiting_release","reason":"owner_uncertain","canSend":false})),
                _=>{}
            }
            let r=s.runtimes.get(&runtime).context("Runtime changed")?;
            if !r.capabilities.supports("thread/read",&json!({"threadId":thread,"includeTurns":false})) ||
                !r.capabilities.supports("thread/resume",&json!({"threadId":thread,"excludeTurns":true})) {
                return Ok(json!({"phase":"waiting_release","reason":"native_takeover_unsupported","canSend":false}))
            }
            (r.rpc.clone(),r.connection_id.clone())
        };
        let metadata=rpc.call("thread/read",json!({"threadId":thread,"includeTurns":false})).await?;
        if metadata["thread"]["id"]!=thread||!matches!(metadata["thread"]["status"]["type"].as_str(),Some("idle"|"notLoaded")){
            return Ok(json!({"phase":"waiting_idle","reason":"thread_busy","canSend":false}))
        }
        if self.external_release_unconfirmed(&home,thread).await {
            return Ok(json!({"phase":"waiting_release","reason":"external_runtime_active","canSend":false}))
        }
        self.check_generation(&runtime,&generation).await?;
        {
            let native=self.state.lock().await.runtimes.get(&runtime).and_then(|r|r.native_process.clone());
            ensure!(native.is_some(),"Migration native identity missing");
            let mut access=self.access.lock().await;
            let current=access.migrations.get_mut(&format!("{store}\0{thread}")).filter(|m|m.operation_id==op.operation_id).context("Migration replaced")?;
            current.phase="attaching".into();current.runtime=Some(runtime.clone());current.native=native;
            access.save(&self.state_dir)?;
        }
        let result=rpc.call("thread/resume",resume.clone()).await
            .map_err(|e|classify(e.into(),Code::Unknown))?;
        crate::history_catalog::resume::verify(&resume,&result).map_err(|e|classify(e,Code::Unknown))?;
        if result["thread"]["id"]!=thread{return Err(classify(anyhow::anyhow!("Resume identity mismatch"),Code::Unknown))}
        {let mut s=self.state.lock().await;let r=s.runtimes.get_mut(&runtime).filter(|r|r.connection_id==generation&&r.rpc.online()).ok_or_else(||classify(anyhow::anyhow!("Runtime changed after resume"),Code::Unknown))?;r.loaded.insert(thread.into());self.save_ownership(&mut s).map_err(|e|classify(e,Code::Unknown))?;}
        self.access.lock().await.select_cli(store,thread,&self.state_dir).map_err(|e|classify(e,Code::Unknown))?;
        Ok(json!({"phase":"ready","runtimeId":runtime,"canSend":true}))
    }
}

fn unregistered_identities(known: &[NativeProcess], observed: &[NativeProcess],
    capture: impl Fn(&NativeProcess)->Option<NativeProcess>) -> bool {
    observed.iter().any(|p| p.pid==0 || p.created_at==0 || !known.contains(p) || capture(p).as_ref()!=Some(p))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(windows)]
    #[tokio::test]
    async fn unstarted_migration_adopts_the_verified_owner_without_resuming_again() {
        for source in ["desktop", "cli"] {
            let (dir,b)=super::super::tests::fixture().await;
            let store=storage::config_store(dir.path()).unwrap();
            let p=json!({"configStoreId":store,"threadId":"legacy"});
            let first=b.begin_history_migration("phone",&p).await.unwrap();
            let mut peer=super::super::tests::peer(&b,"current",source).await;
            let native=NativeProcess::capture(std::process::id()).unwrap();
            {
                let mut state=b.state.lock().await;
                let runtime=state.runtimes.get_mut("current").unwrap();
                runtime.loaded.insert("legacy".into());runtime.inventory_ready=true;
                runtime.native_process=Some(native.clone());
                b.save_ownership(&mut state).unwrap();
                let mut access=b.access.lock().await;
                let op=access.migrations.values_mut().next().unwrap();
                op.phase="waiting_release".into();op.reason=Some("owner_uncertain".into());
            }
            b.drive_history_migrations().await;
            let status=b.migration_status(&p).await.unwrap();
            assert_eq!(status["phase"],"ready");assert_eq!(status["canSend"],true);
            assert_eq!(status["runtimeId"],"current");assert_eq!(status["operationId"],first["operationId"]);
            let access=b.access_snapshot(&json!({"runtimeId":"current","threadId":"legacy"})).await.unwrap();
            assert_eq!(access["canSend"],true);assert_eq!(access["mode"],source);
            let saved=session_access::AccessStore::open(&b.state_dir,&b.config.computer_id).unwrap();
            let saved=saved.migrations.values().next().unwrap();
            assert_eq!(saved.phase,"ready");assert_eq!(saved.native,Some(native));
            peer.assert_idle().await;
        }
    }
    #[cfg(windows)]
    #[tokio::test]
    async fn migration_adoption_keeps_uncertain_dispatched_and_incomplete_owners_blocked() {
        for reason in ["offline","inventory","handshake","conflict","dispatched","attaching","auxiliary","follower"] {
            let (dir,b)=super::super::tests::fixture().await;
            let store=storage::config_store(dir.path()).unwrap();
            b.begin_history_migration("phone",&json!({"configStoreId":store,"threadId":"legacy"})).await.unwrap();
            let mut peer=super::super::tests::peer(&b,"current","desktop").await;
            let native=NativeProcess::capture(std::process::id()).unwrap();
            {
                let mut state=b.state.lock().await;
                let runtime=state.runtimes.get_mut("current").unwrap();
                runtime.loaded.insert("legacy".into());runtime.native_process=Some(native.clone());
                runtime.inventory_ready=reason!="inventory";
                if reason=="handshake" {runtime.desktop_initialized=Some(false);}
                if reason=="auxiliary" {runtime.role=native::NativeRole::DesktopAuxiliary;}
                if reason=="follower" {runtime.role=native::NativeRole::DesktopFollower;}
                b.save_ownership(&mut state).unwrap();
                if reason=="conflict" {
                    let mut claim=state.ownership["current"].clone();
                    claim.advertisement.id.runtime_id="old".into();claim.advertisement.online=false;
                    state.ownership.insert("old".into(),claim);
                }
                let mut access=b.access.lock().await;
                let op=access.migrations.values_mut().next().unwrap();
                if reason=="dispatched" {op.native=Some(native);op.runtime=Some("old".into());}
                if reason=="attaching" {op.phase="attaching".into();}
            }
            if reason=="offline" {peer.offline();}
            let before=std::fs::read(b.state_dir.join("session-access.json")).unwrap();
            let op=b.access.lock().await.migrations.values().next().unwrap().clone();
            assert!(!b.adopt_observed_migration_owner(&op).await.unwrap(),"{reason}");
            assert_eq!(std::fs::read(b.state_dir.join("session-access.json")).unwrap(),before);
            peer.assert_idle().await;
        }
    }
    #[tokio::test]
    async fn completed_recovery_does_not_report_dead_runtime_as_ready() {
        let (dir,b)=super::super::tests::fixture().await;
        let store=storage::config_store(dir.path()).unwrap();
        let p=json!({"configStoreId":store,"threadId":"legacy"});
        let first=b.begin_history_migration("phone",&p).await.unwrap();
        {
            let mut access=b.access.lock().await;
            let op=access.migrations.values_mut().next().unwrap();
            op.phase="ready".into();op.runtime=Some("retired".into());
            op.native=Some(NativeProcess{pid:0,created_at:0});
        }
        let status=b.migration_status(&p).await.unwrap();
        assert_eq!(status["phase"],"disconnected");
        assert_eq!(status["canSend"],false);
        assert_eq!(status["operationId"],first["operationId"]);
        // A missing transport is not proof that its original execution stopped.
        assert_eq!(b.begin_history_migration("phone",&p).await.unwrap()["phase"],"disconnected");
        assert_eq!(b.access.lock().await.migrations.values().next().unwrap().phase,"ready");
    }
    #[cfg(windows)]
    #[tokio::test]
    async fn completed_recovery_can_restart_after_verified_native_exit() {
        let (dir,b)=super::super::tests::fixture().await;
        let store=storage::config_store(dir.path()).unwrap();
        let p=json!({"configStoreId":store,"threadId":"legacy"});
        let first=b.begin_history_migration("phone",&p).await.unwrap();
        let mut ended=NativeProcess::capture(std::process::id()).unwrap();ended.created_at-=1;
        {
            let mut access=b.access.lock().await;
            let op=access.migrations.values_mut().next().unwrap();
            op.phase="ready".into();op.runtime=Some("retired".into());op.native=Some(ended);
        }
        assert_eq!(b.migration_status(&p).await.unwrap()["reason"],"runtime_restarted");
        let retry=b.begin_history_migration("phone",&p).await.unwrap();
        assert_eq!(retry["phase"],"queued");
        assert_ne!(retry["operationId"],first["operationId"]);
        assert_eq!(retry,b.begin_history_migration("phone",&p).await.unwrap());
    }
    #[cfg(windows)]
    #[tokio::test]
    async fn brokers_own_history_reader_does_not_block_session_recovery() {
        let (dir,b)=super::super::tests::fixture().await;
        let sessions=dir.path().join("sessions");std::fs::create_dir(&sessions).unwrap();
        let path=sessions.join("history.jsonl");std::fs::write(&path,b"history").unwrap();
        let db=rusqlite::Connection::open(dir.path().join("state_5.sqlite")).unwrap();
        db.execute_batch("CREATE TABLE threads(id TEXT PRIMARY KEY,source TEXT,title TEXT,preview TEXT,cwd TEXT,updated_at INTEGER,archived INTEGER,rollout_path TEXT);").unwrap();
        db.execute("INSERT INTO threads VALUES('t','cli','saved','','',1,0,?1)",[path.to_str().unwrap()]).unwrap();
        let handle=std::fs::File::open(&path).unwrap();
        assert!(rollout_owners::holders(&path).unwrap().contains(&NativeProcess::capture(std::process::id()).unwrap()));
        assert!(!b.external_release_unconfirmed(dir.path(),"t").await);
        drop(handle);
    }
    #[tokio::test]
    async fn migration_is_persisted_once_and_status_survives_restart() {
        let (dir,b)=super::super::tests::fixture().await;
        let store=storage::config_store(dir.path()).unwrap();
        let p=json!({"configStoreId":store,"threadId":"legacy"});
        let first=b.begin_history_migration("phone",&p).await.unwrap();
        assert_eq!(first["phase"],"queued");
        assert_eq!(first,b.begin_history_migration("phone",&p).await.unwrap());
        *b.access.lock().await=session_access::AccessStore::open(&b.state_dir,&b.config.computer_id).unwrap();
        assert_eq!(first,b.migration_status(&p).await.unwrap());
        assert!(b.begin_history_migration("phone",&json!({"computerId":"foreign","configStoreId":store,"threadId":"legacy"})).await.is_err());
    }
    #[tokio::test]
    async fn uncertain_migration_cannot_be_reissued() {
        let (dir,b)=super::super::tests::fixture().await;
        let store=storage::config_store(dir.path()).unwrap();
        let p=json!({"configStoreId":store,"threadId":"legacy"});
        b.begin_history_migration("phone",&p).await.unwrap();
        {
            let mut access=b.access.lock().await;
            let op=access.migrations.values_mut().next().unwrap();
            op.phase="attaching".into();op.native=Some(NativeProcess{pid:77,created_at:44});op.runtime=Some("native".into());
            access.save(&b.state_dir).unwrap();
        }
        b.drive_history_migrations().await;
        let status=b.migration_status(&p).await.unwrap();
        assert_eq!(status["phase"],"blocked");assert_eq!(status["reason"],"resume_result_unknown");
        assert_eq!(b.begin_history_migration("phone",&p).await.unwrap(),status);
    }
    #[test]
    fn stale_and_unverifiable_pids_never_prove_release() {
        let known=NativeProcess{pid:17,created_at:31};
        let reused=NativeProcess{created_at:32,..known.clone()};
        assert!(!unregistered_identities(&[known.clone()],&[known.clone()],|p|Some(p.clone())));
        assert!(unregistered_identities(&[known.clone()],&[reused],|p|Some(p.clone())));
        assert!(unregistered_identities(&[known.clone()],&[known.clone()],|_|None));
        assert!(unregistered_identities(&[],&[known.clone()],|p|Some(p.clone())));
        assert!(unregistered_identities(&[known.clone()],&[known],|p|Some(NativeProcess{created_at:33,..p.clone()})));
    }

}
