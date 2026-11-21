//! Adapter for the desktop's versioned follower IPC. Never resumes a thread.
use super::*;
use anyhow::ensure;
use std::os::windows::io::AsRawHandle;
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::windows::named_pipe::{ClientOptions, NamedPipeClient}, sync::Mutex};

const MAX_FRAME: usize = 64 * 1024 * 1024;
const STREAM_VERSION: u64 = 13;
#[path = "desktop_catalog.rs"]
mod catalog;
#[derive(Debug)]
struct DesktopRejected(String);
impl std::fmt::Display for DesktopRejected {fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result {f.write_str(&self.0)}}
impl std::error::Error for DesktopRejected {}

fn desktop_input(input: &Value) -> Result<Value> {
    let mut items = input.as_array().context("Missing input")?.clone();
    for item in &mut items {
        if item["type"] == "text" {
            ensure!(item["text"].is_string(), "Invalid text input");
            if item.get("text_elements").is_none() { item["text_elements"] = json!([]); }
            ensure!(item["text_elements"].is_array(), "Invalid text elements");
        }
    }
    Ok(Value::Array(items))
}

fn definite_rejection(error: &str) -> bool {
    matches!(error, "no-client-found" | "request-version-mismatch" | "no-handler-for-request")
        || error.starts_with("Invalid request:")
        || error.starts_with("thread not found:")
}

fn observed_settings(state: &Value) -> Value {
    let mut fields=state["currentPermissions"].as_object().cloned().unwrap_or_default();
    if let Some(v)=state["latestThreadSettings"].as_object(){fields.extend(v.clone());}
    if !fields.contains_key("model") && state["latestModel"].is_string(){fields.insert("model".into(),state["latestModel"].clone());}
    if !fields.contains_key("effort") && state.get("latestReasoningEffort").is_some(){fields.insert("effort".into(),state["latestReasoningEffort"].clone());}
    crate::settings::canonical(&Value::Object(fields))
}
fn active_turn(state: &Value) -> Option<String> {
    state["turns"].as_array().into_iter().flatten()
        .chain(state["turnHistory"]["history"]["entitiesByKey"].as_object().into_iter().flat_map(|o|o.values()))
        .filter(|v|v["status"]=="inProgress")
        .max_by_key(|v|v["turnStartedAtMs"].as_u64().unwrap_or(0))
        .and_then(|v|v["turnId"].as_str()).map(str::to_owned)
}
fn loaded(state:&Value)->bool {
    matches!(state["threadRuntimeStatus"]["type"].as_str(),Some("active"|"idle"|"systemError"))
}

fn follower_request(method:&str, p:&Value, state:&Value, command:&str)->Result<(&'static str,u64,Value)> {
    let thread=p["threadId"].as_str().context("Missing thread")?;
    ensure!(state["id"]==thread,"Desktop thread identity changed");
    ensure!(loaded(state),"Desktop thread is no longer loaded");
    let active=active_turn(state);
    Ok(match method {
        "turn/start"=>{
            ensure!(active.is_none() && state["threadRuntimeStatus"]["type"]!="active","Thread is busy");
            let mut request=p.clone();
            request["input"] = desktop_input(&p["input"])?;
            if request["clientUserMessageId"].is_null(){request["clientUserMessageId"]=json!(command);}
            ("thread-follower-start-turn",2,json!({"conversationId":thread,"turnStart":{"request":request}}))
        },
        "turn/steer"=>{
            ensure!(active.as_deref()==p["expectedTurnId"].as_str()&&active.is_some(),"Active turn changed");
            ("thread-follower-steer-turn",1,json!({"conversationId":thread,"input":desktop_input(&p["input"])?,"clientUserMessageId":p.get("clientUserMessageId").cloned().unwrap_or(json!(command))}))
        },
        "turn/interrupt"=>{
            ensure!(active.as_deref()==p["turnId"].as_str()&&active.is_some(),"Active turn changed");
            ("thread-follower-interrupt-turn",4,json!({"conversationId":thread,"mode":"user-stop","expectedTurnId":active}))
        },
        "thread/settings/update"=>{
            let mut fields=p.as_object().context("Invalid settings")?.clone();fields.remove("threadId");
            ("thread-follower-update-thread-settings",2,json!({"conversationId":thread,"threadSettings":fields}))
        },
        "thread/compact/start"=>("thread-follower-compact-thread",1,json!({"conversationId":thread})),
        _=>bail!("Desktop follower method unsupported"),
    })
}

struct Snapshot { revision:u64, state:Value }
#[derive(Default)]
struct Projection {settings:Option<Value>,usage:Option<Value>}
impl Projection {
    fn updates(&mut self,state:&Value)->Vec<Value> {
        let mut events=vec![];
        let Some(thread)=state["id"].as_str() else{return events};
        let settings=observed_settings(state);
        if self.settings.as_ref()!=Some(&settings) {
            events.push(crate::settings::notification(thread,settings.clone()));
            self.settings=Some(settings);
        }
        let usage=&state["latestTokenUsageInfo"];
        if crate::context_usage::snapshot(usage,"native_snapshot",0,None).is_some() && self.usage.as_ref()!=Some(usage) {
            events.push(json!({"method":"thread/tokenUsage/updated","params":{"threadId":thread,"turnId":active_turn(state),"tokenUsage":usage}}));
            self.usage=Some(usage.clone());
        }
        events
    }
}
impl Snapshot {
    fn apply(&mut self, change:&Value)->Result<()> {
        let revision=change["revision"].as_u64().context("Missing desktop revision")?;
        if change["type"]=="snapshot" {
            ensure!(change["conversationState"].is_object(),"Invalid desktop snapshot");
            self.state=change["conversationState"].clone();self.revision=revision;return Ok(())
        }
        ensure!(change["type"]=="patches"&&change["baseRevision"].as_u64()==Some(self.revision)&&revision>self.revision,"Desktop snapshot gap");
        let patches=change["patches"].as_array().context("Missing desktop patches")?;
        for patch in patches { apply_patch(&mut self.state,patch)?; }
        self.revision=revision;Ok(())
    }
}
fn apply_patch(state:&mut Value,p:&Value)->Result<()> {
    let path=p["path"].as_array().context("Invalid patch path")?;
    let mut node=state;
    for key in path.iter().take(path.len().saturating_sub(1)) {
        node=if let Some(k)=key.as_str(){node.get_mut(k)}else{key.as_u64().and_then(|i|node.get_mut(i as usize))}.context("Missing patch parent")?;
    }
    let op=p["op"].as_str().context("Missing patch op")?;
    let value=p["value"].clone();
    let Some(key)=path.last() else {ensure!(op=="replace","Invalid root patch");*node=value;return Ok(())};
    if let Some(o)=node.as_object_mut(){
        let k=key.as_str().context("Invalid object patch")?;
        match op {"add"=>{o.insert(k.into(),value);},"replace"=>{ensure!(o.contains_key(k),"Missing patch key");o.insert(k.into(),value);},"remove"=>{ensure!(o.remove(k).is_some(),"Missing patch key");},_=>bail!("Unsupported patch")}
    }else if let Some(a)=node.as_array_mut(){
        let i=key.as_u64().context("Invalid array patch")? as usize;
        match op {"add"=>{ensure!(i<=a.len(),"Patch index out of range");a.insert(i,value)},"replace"=>{*a.get_mut(i).context("Missing array item")?=value},"remove"=>{ensure!(i<a.len(),"Patch index out of range");a.remove(i);},_=>bail!("Unsupported patch")}
    }else{bail!("Invalid patch target")}
    Ok(())
}

struct Channel {
    pipe:NamedPipeClient, client:String, owner:String, thread:String,
    snapshot:Snapshot, snapshot_valid:bool,
}
impl Channel {
    async fn write(&mut self, value:&Value)->Result<()> {
        let bytes=serde_json::to_vec(value)?;ensure!(bytes.len()<=MAX_FRAME,"Desktop IPC frame too large");
        self.pipe.write_u32_le(bytes.len() as u32).await?;self.pipe.write_all(&bytes).await?;Ok(())
    }
    async fn read(&mut self)->Result<Value> {
        let size=self.pipe.read_u32_le().await? as usize;ensure!((1..=MAX_FRAME).contains(&size),"Invalid desktop IPC frame");
        let mut bytes=vec![0;size];self.pipe.read_exact(&mut bytes).await?;Ok(serde_json::from_slice(&bytes)?)
    }
    async fn observe(&mut self,v:&Value)->Result<()> {
        if v["type"]=="client-discovery-request" {
            self.write(&json!({"type":"client-discovery-response","requestId":v["requestId"],"response":{"canHandle":false}})).await?;
        }else if v["type"]=="broadcast"&&v["method"]=="thread-stream-state-changed"&&
            v["sourceClientId"]==self.owner&&v["params"]["conversationId"]==self.thread&&v["params"]["hostId"]=="local" {
            ensure!(v["version"]==STREAM_VERSION,"Unsupported desktop stream version");
            if v["params"]["change"]["type"]=="snapshot"||self.snapshot_valid {
                self.snapshot_valid=self.snapshot.apply(&v["params"]["change"]).is_ok();
            }
        }else if v["method"]=="client-status-changed"&&v["params"]["clientId"]==self.owner&&v["params"]["status"]=="disconnected" {
            bail!("Desktop owner disconnected")
        }
        Ok(())
    }
    async fn request(&mut self,method:&str,version:u64,params:Value,target:bool)->Result<Value> {
        let id=uuid::Uuid::new_v4().to_string();
        let mut request=json!({"type":"request","requestId":id,"sourceClientId":self.client,"method":method,"version":version,"params":params});
        if target {request["targetClientId"]=json!(self.owner);}
        self.write(&request).await?;
        loop {
            let reply=self.read().await?;self.observe(&reply).await?;
            if reply["type"]=="response"&&reply["requestId"]==id {
                if reply["resultType"]!="success" {return Err(DesktopRejected(reply["error"].as_str().unwrap_or("unknown").into()).into());}
                ensure!(reply["method"]==method,"Desktop response method mismatch");
                if target {ensure!(reply["handledByClientId"]==self.owner,"Desktop owner changed");}
                return Ok(reply)
            }
        }
    }
    async fn owner(&mut self)->Result<String> {
        let v=self.request("thread-owner-discovery",1,json!({"hostId":"local","conversationId":self.thread}),false).await?;
        ensure!(v["result"]["supportsUntrustedAppInput"]==true,"Unsupported desktop input contract");
        Ok(v["handledByClientId"].as_str().context("Missing desktop owner")?.to_owned())
    }
    async fn follow(&mut self,following:bool)->Result<()> {
        self.write(&json!({"type":"broadcast","sourceClientId":self.client,"method":"thread-stream-following-changed","version":1,
            "targetClientIds":[self.owner],"params":{"hostId":"local","conversationId":self.thread,"following":following}})).await
    }
    async fn fresh(&mut self)->Result<()> {
        ensure!(self.owner().await?==self.owner,"Desktop owner changed");
        self.follow(false).await?;self.snapshot_valid=false;self.follow(true).await?;
        while !self.snapshot_valid {let v=self.read().await?;self.observe(&v).await?;}
        self.follow(false).await?;
        ensure!(self.snapshot.state["id"]==self.thread,"Desktop snapshot identity mismatch");Ok(())
    }
}

pub struct Client {
    channel:Mutex<Channel>, online:Arc<AtomicBool>,
    pub runtime_id:String, connection_id:String, native_process:NativeProcess,
    version:String, catalog:Option<Rpc>, caps:crate::capabilities::Capabilities,
    catalog_binary:PathBuf, home:PathBuf, events_started:AtomicBool,
    projection:Mutex<Projection>,
    events:mpsc::Sender<NativeEvent>,
}
fn capabilities()->crate::capabilities::Capabilities {
    let mut c=crate::capabilities::Capabilities::default();
    for (method,fields) in [
        ("thread/read",vec!["threadId","includeTurns"]),("thread/loaded/list",vec!["limit","cursor"]),
        ("turn/start",vec!["threadId","input","model","effort","clientUserMessageId","collaborationMode"]),
        ("turn/steer",vec!["threadId","input","expectedTurnId","clientUserMessageId"]),
        ("turn/interrupt",vec!["threadId","turnId"]),("thread/compact/start",vec!["threadId"]),
        ("thread/settings/update",vec!["threadId","model","effort","serviceTier","approvalPolicy","approvalsReviewer","sandboxPolicy"]),
    ] {c.0.insert(method.into(),fields.into_iter().map(str::to_owned).collect());}
    {
        c.0.insert("model/list".into(),["limit","cursor","includeHidden"].into_iter().map(str::to_owned).collect());
        c.0.insert("collaborationMode/list".into(),Default::default());
    }
    c
}
impl Client {
    pub async fn connect(home:&Path,thread:&str,catalog:Option<Rpc>,events:mpsc::Sender<NativeEvent>)->Result<Connected> {
        ensure!(crate::settings::valid_thread(thread),"Invalid thread");
        let pipe=ClientOptions::new().open(r"\\.\pipe\codex-ipc")?;
        let mut pid=0;
        ensure!(unsafe{windows_sys::Win32::System::Pipes::GetNamedPipeServerProcessId(pipe.as_raw_handle(),&mut pid)}!=0,"Cannot verify desktop pipe");
        let (native_process,version,catalog_binary)=tokio::task::spawn_blocking(move||->Result<_>{
            let identity=NativeProcess::capture(pid)?;let binary=native_binary(pid)?;
            let packages=crate::managed_runtime::discovery::registered_codex_packages()?;
            let package=packages.iter().find(|p|dunce::canonicalize(p.location.join("app/ChatGPT.exe")).ok()==dunce::canonicalize(&binary).ok()).context("Not the registered desktop")?;
            ensure!(!identity.terminated(),"Desktop exited");
            Ok((identity,package.full_name.split('_').nth(1).context("Missing version")?.to_string(),package.location.join("app/resources/codex.exe")))
        }).await??;
        let mut channel=Channel{pipe,client:"initializing-client".into(),owner:String::new(),thread:thread.into(),snapshot:Snapshot{revision:0,state:Value::Null},snapshot_valid:false};
        let init=channel.request("initialize",0,json!({"clientType":"codex-pocket"}),false).await?;
        channel.client=init["result"]["clientId"].as_str().context("Missing client ID")?.into();
        channel.owner=channel.owner().await?;channel.fresh().await?;
        ensure!(loaded(&channel.snapshot.state),"Desktop thread is not loaded");
        // Only this profile's native transcript can establish the profile binding.
        let rollout=channel.snapshot.state["rolloutPath"].as_str().context("Desktop profile not proven")?;
        let path=dunce::canonicalize(rollout)?;let home=dunce::canonicalize(home)?;
        ensure!(path.starts_with(home.join("sessions"))||path.starts_with(home.join("archived_sessions")),"Desktop profile mismatch");
        let store=crate::storage::config_store(&home)?;
        let runtime_id=format!("desktop-follower-{}",&crate::auth::hash(&format!("{store}:{thread}:{}",native_process.created_at))[..24]);
        let connection_id=uuid::Uuid::new_v4().to_string();let online=Arc::new(AtomicBool::new(true));
        let caps=capabilities();
        let client=Arc::new(Self{channel:Mutex::new(channel),online:online.clone(),runtime_id:runtime_id.clone(),connection_id:connection_id.clone(),native_process:native_process.clone(),version:version.clone(),catalog,caps:caps.clone(),catalog_binary,home:home.clone(),events_started:AtomicBool::new(false),projection:Mutex::new(Projection::default()),events});
        let (tx,_rx)=mpsc::channel(1);
        Ok(Connected{initial_status:None,role:NativeRole::DesktopFollower,bridge_version:None,capabilities:caps,
            rpc:Rpc{tx,response_tx:None,host_stop:None,host_event_start:None,host_cursor:None,online,capability_failed:false,desktop_ipc:Some(client)},
            native_process:Some(native_process),runtime_id,connection_id,config_home:home,source:"desktop".into(),version})
    }
    async fn event(&self,value:Value){let _=self.events.send(NativeEvent::Message{runtime_id:self.runtime_id.clone(),connection_id:self.connection_id.clone(),value}).await;}
    async fn publish(&self,state:&Value) {
        let events=self.projection.lock().await.updates(state);
        for event in events {self.event(event).await;}
    }
    pub fn activate_events(self:&Arc<Self>) {
        if self.events_started.swap(true,Ordering::AcqRel) {return}
        let weak=Arc::downgrade(self);
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(5)).await;
                let Some(client)=weak.upgrade() else {break};
                if !client.online.load(Ordering::Acquire) {break}
                if Box::pin(client.call(json!(uuid::Uuid::new_v4().to_string()),"pocket/approvals/list",json!({}))).await.is_err(){break}
            }
        });
    }
    async fn pending(&self,state:&Value)->Result<Value> {
        let data=state["requests"].as_array().context("Missing desktop requests")?;
        let value=json!({"data":data.iter().filter(|r|matches!(r["method"].as_str(),Some("item/commandExecution/requestApproval"|"item/fileChange/requestApproval"|"item/tool/requestUserInput"))).collect::<Vec<_>>()});
        let (reply,rx)=oneshot::channel();
        self.events.send(NativeEvent::PendingSnapshot{runtime_id:self.runtime_id.clone(),connection_id:self.connection_id.clone(),value:value.clone(),reply}).await?;
        rx.await??;
        Ok(value)
    }
    fn status(&self)->Value{json!({"runtimeId":self.runtime_id,"nativeProcess":self.native_process,"nativeVersion":self.version,"initialized":true,"schemaState":"ready","schemaPending":false,"permissionProtocol":1,"nativeCapabilities":self.caps})}
    pub async fn call(&self,id:Value,method:&str,p:Value)->std::result::Result<Value,RpcFailure>{
        if !self.online.load(Ordering::Acquire){return Err(RpcFailure::Disconnected)}
        if matches!(method,"model/list"|"collaborationMode/list"){
            if let Some(c)=&self.catalog {if c.online(){return Box::pin(c.call(method,p)).await}}
            return catalog::read(&self.catalog_binary,&self.home,method,p).await
                .map_err(|e|RpcFailure::Native(json!({"code":-32603,"message":e.to_string()})))
        }
        let mutation=matches!(method,"turn/start"|"turn/steer"|"turn/interrupt"|"thread/settings/update"|"thread/compact/start");
        let mut dispatched=false;
        let mut verified=false;
        let result=tokio::time::timeout(Duration::from_secs(20),async{
            let mut channel=self.channel.lock().await;
            if method=="pocket/status" {return Ok(self.status())}
            channel.fresh().await?;
            verified=true;
            let state=&channel.snapshot.state;
            let thread=&channel.thread;
            if let Some(requested)=p.get("threadId"){ensure!(requested==thread,"Thread mismatch");}
            match method {
                "thread/loaded/list"=>Ok(json!({"data":if loaded(state){vec![thread]}else{vec![]},"nextCursor":null})),
                "pocket/managementStatus"=>{
                    let active=u64::from(state["threadRuntimeStatus"]["type"]=="active");
                    ensure!(matches!(state["threadRuntimeStatus"]["type"].as_str(),Some("active"|"idle"|"systemError"|"notLoaded")),"Unknown desktop activity");
                    let pending=state["requests"].as_array().context("Missing desktop requests")?.len();
                    Ok(json!({"protocol":1,"initialized":true,"activeTurns":active,"pendingQuestions":pending,"pendingWrites":0,"unknownOperations":0,"busy":active>0||pending>0}))
                },
                "pocket/approvals/list"=>{
                    let result=self.pending(state).await?;
                    self.publish(state).await;
                    Ok(result)
                },
                "pocket/threadSettings"=>Ok(json!({"threadId":thread,"known":true,"settings":observed_settings(state)})),
                "thread/read"=>{
                    ensure!(p["includeTurns"]!=true,"Desktop history uses paginated history endpoint");
                    let mut meta=observed_settings(state);
                    meta["id"]=json!(thread);meta["cwd"]=state["cwd"].clone();meta["status"]=state["threadRuntimeStatus"].clone();meta["turns"]=json!([]);
                    meta["tokenUsage"]=state["latestTokenUsageInfo"].clone();
                    meta["turnId"]=json!(active_turn(state));
                    Ok(json!({"thread":meta}))
                },
                _ if mutation=>{
                    let command=id.as_str().map(str::to_owned).unwrap_or_else(||id.to_string());
                    let (name,version,params)=follower_request(method,&p,state,&command)?;
                    dispatched=true;
                    let reply=channel.request(name,version,params,true).await?;
                    let result=reply["result"].clone();
                    if method=="thread/settings/update" {
                        ensure!(result["applied"]==true,"Settings not applied");
                        channel.fresh().await?;let settings=observed_settings(&channel.snapshot.state);
                        self.publish(&channel.snapshot.state).await;
                        return Ok(json!({"threadSettings":settings}))
                    }
                    if method=="turn/interrupt" {ensure!(result["interruptedTurnId"]==p["turnId"],"Stop not confirmed");return Ok(json!({}))}
                    if matches!(method,"turn/start"|"turn/steer") {return Ok(result["result"].clone())}
                    Ok(result)
                },
                _=>bail!("Desktop follower method unsupported"),
            }
        }).await;
        match result {
            Ok(Ok(v))=>Ok(v),
            Ok(Err(e)) if e.downcast_ref::<DesktopRejected>().is_some_and(|r|definite_rejection(&r.0))=>Err(RpcFailure::Native(json!({"code":-32600,"message":e.to_string()}))),
            Ok(Err(e)) if !dispatched&&verified=>Err(RpcFailure::Native(json!({"code":-32600,"message":e.to_string()}))),
            other=>{
                if let Ok(Err(e)) = other { eprintln!("Desktop follower {method} did not confirm an outcome: {e}"); }
                self.online.store(false,Ordering::Release);Err(if dispatched{RpcFailure::Timeout}else{RpcFailure::Disconnected})
            }
        }
    }
    pub async fn respond(&self,id:Value,response:Value)->std::result::Result<(),RpcFailure>{
        let result=tokio::time::timeout(Duration::from_secs(20),async {
            let mut c=self.channel.lock().await;c.fresh().await?;
            let request=c.snapshot.state["requests"].as_array().into_iter().flatten().find(|r|r["id"]==id).context("Request no longer pending")?;
            let (method,key)=match request["method"].as_str(){
                Some("item/commandExecution/requestApproval")=>("thread-follower-command-approval-decision","decision"),
                Some("item/fileChange/requestApproval")=>("thread-follower-file-approval-decision","decision"),
                Some("item/tool/requestUserInput")=>("thread-follower-submit-user-input","response"),
                _=>bail!("Unsupported pending request"),
            };
            let mut p=json!({"conversationId":c.thread,"requestId":id});
            p[key]=if key=="decision"{response["decision"].clone()}else{response};
            c.request(method,1,p,true).await?;Ok::<_,anyhow::Error>(())
        }).await;
        match result{Ok(Ok(()))=>Ok(()),_=>Err(RpcFailure::Timeout)}
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn observed_full_access_and_model_are_preserved_without_inventing_defaults() {
        let state=json!({"id":"t","latestThreadSettings":{"model":"custom/model","effort":"xhigh",
            "approvalPolicy":"never","approvalsReviewer":"user","sandboxPolicy":{"type":"dangerFullAccess"},
            "activePermissionProfile":{"id":":danger-full-access"}},"latestModel":"old"});
        let value=observed_settings(&state);
        assert_eq!(value["model"],"custom/model");assert_eq!(value["effort"],"xhigh");
        assert_eq!(value["sandboxPolicy"]["type"],"dangerFullAccess");
        assert!(observed_settings(&json!({}))["sandboxPolicy"].is_null());
    }
    #[test]
    fn follower_send_uses_original_identity_and_request_id() {
        let state=json!({"id":"t","threadRuntimeStatus":{"type":"idle"}});
        let (method,version,params)=follower_request("turn/start",&json!({"threadId":"t","input":[{"type":"text","text":"hello"}]}),&state,"command-1").unwrap();
        assert_eq!((method,version),("thread-follower-start-turn",2));
        assert_eq!(params["conversationId"],"t");
        assert_eq!(params["turnStart"]["request"]["clientUserMessageId"],"command-1");
        assert_eq!(params["turnStart"]["request"]["input"][0]["text"],"hello");
        assert_eq!(params["turnStart"]["request"]["input"][0]["text_elements"],json!([]));
        assert!(params["turnStart"]["request"].get("approvalPolicy").is_none());
    }
    #[test]
    fn stop_and_steer_cannot_affect_a_replacement_turn() {
        let state=json!({"id":"t","threadRuntimeStatus":{"type":"active"},"turns":[{"turnId":"current","status":"inProgress"}]});
        assert!(follower_request("turn/interrupt",&json!({"threadId":"t","turnId":"old"}),&state,"c").is_err());
        let (_,version,p)=follower_request("turn/interrupt",&json!({"threadId":"t","turnId":"current"}),&state,"c").unwrap();
        assert_eq!(version,4);assert_eq!(p["expectedTurnId"],"current");
        assert!(follower_request("turn/start",&json!({"threadId":"another","input":[]}),&state,"c").is_err());
    }
    #[test]
    fn patches_require_matching_revision_and_do_not_apply_after_a_gap() {
        let mut snapshot=Snapshot{revision:4,state:json!({"id":"t","latestThreadSettings":{"model":"old"}})};
        assert!(snapshot.apply(&json!({"type":"patches","baseRevision":2,"revision":5,"patches":[]})).is_err());
        assert_eq!(snapshot.revision,4);
        snapshot.apply(&json!({"type":"patches","baseRevision":4,"revision":5,"patches":[{"op":"replace","path":["latestThreadSettings","model"],"value":"new"}]})).unwrap();
        assert_eq!(observed_settings(&snapshot.state)["model"],"new");
    }
    #[test]
    fn input_preserves_images_and_existing_text_ranges() {
        let input=json!([{"type":"text","text":"hello","text_elements":[{"byteRange":{"start":0,"end":5},"placeholder":"hello"}]},{"type":"image","url":"data:image/png;base64,AA=="}]);
        assert_eq!(desktop_input(&input).unwrap(),input);
        assert!(desktop_input(&json!([{"type":"text","text":"hi","text_elements":null}])).is_err());
    }
    #[test]
    fn forwarding_errors_remain_unknown_after_dispatch() {
        for error in ["request-timeout","client-disconnected","server-closed","error-handling-request","write EPIPE"] {
            assert!(!definite_rejection(error));
        }
        assert!(definite_rejection("request-version-mismatch"));
    }
    #[test]
    fn cached_desktop_ownership_is_not_a_loaded_native_thread() {
        let state=json!({"id":"t","threadRuntimeStatus":{"type":"notLoaded"}});
        assert!(!loaded(&state));
        assert!(follower_request("turn/start",&json!({"threadId":"t","input":[]}),&state,"c").is_err());
    }
    #[test]
    fn unchanged_polls_do_not_reload_permissions_and_usage_changes_still_publish() {
        let mut state=json!({"id":"t","latestThreadSettings":{"approvalPolicy":"never","sandboxPolicy":{"type":"dangerFullAccess"}},
            "latestTokenUsageInfo":{"total":{"totalTokens":150,"inputTokens":100,"cachedInputTokens":20,"outputTokens":50,"reasoningOutputTokens":10},
                "last":{"totalTokens":150,"inputTokens":100,"cachedInputTokens":20,"outputTokens":50,"reasoningOutputTokens":10},"modelContextWindow":1000}});
        let mut projection=Projection::default();
        assert_eq!(projection.updates(&state).len(),2);
        assert!(projection.updates(&state).is_empty());
        state["latestTokenUsageInfo"]["last"]["inputTokens"]=json!(110);
        let events=projection.updates(&state);
        assert_eq!(events.len(),1);assert_eq!(events[0]["method"],"thread/tokenUsage/updated");
        assert_eq!(events[0]["params"]["tokenUsage"]["modelContextWindow"],1000);
    }
}
