use pocket_desktop_runtime::{connection::{self,OperationError},launcher,pairing,process,storage,supervisor};
use serde_json::{json,Value};
use std::{path::{Path,PathBuf},process::Command,sync::{Arc,Mutex},time::{Duration,Instant}};
use tauri::State;

#[derive(Clone)]
pub struct Context {
    pub state:PathBuf,pub bin:PathBuf,pub operation:Arc<Mutex<()>>,
    pub startup_cache:Arc<Mutex<StartupCache>>,
    pub action_failures:Arc<Mutex<Vec<Value>>>,
    pub control_start_request:Arc<Mutex<Option<Instant>>>,
    pub control_start_error:Arc<Mutex<Option<OperationError>>>,
    pub background_recovery:Arc<Mutex<crate::background_recovery::BackgroundRecovery>>,
}
#[derive(Default)]
pub struct StartupCache {
    enabled:Option<bool>,failure:Option<OperationError>,checked_at:Option<Instant>,in_flight:bool,
}
impl Context {
    pub fn new(state:PathBuf,bin:PathBuf)->Self{Self{state,bin,operation:Arc::new(Mutex::new(())),startup_cache:Arc::new(Mutex::new(StartupCache::default())),action_failures:Arc::new(Mutex::new(Vec::new())),control_start_request:Arc::new(Mutex::new(None)),control_start_error:Arc::new(Mutex::new(None)),background_recovery:Arc::new(Mutex::new(Default::default()))}}
}
pub(crate) fn fail(stage:&str,error:anyhow::Error)->OperationError{OperationError::from_error(stage,&error)}
pub(crate) fn join_error(_:impl std::fmt::Display)->OperationError{OperationError::new("desktop","unavailable",true)}

fn core_preflight(c:&Context)->Result<(),OperationError>{
    let root=storage::local_app_data().map_err(|e|fail("installation",e))?.join("CodexPocket");
    let (bin,state)=launcher::resolve_release(&root).map_err(|e|fail("installation",e))?;
    if bin!=c.bin||state!=c.state{return Err(OperationError::new("installation","installation_changed",false))}
    for name in ["config.json","agent.lock","control.json","status.json","devices.json","desktop/desired.json","desktop/supervisor.json","pairing-qr.json"] {
        storage::unredirected(&state.join(name)).map_err(|e|fail("state_access",e))?;
    }
    for name in ["pocket-agent.exe","pocket-supervisor.exe"] {
        launcher::verified_exe(&bin,name).map_err(|e|fail("installation",e))?;
    }
    Ok(())
}
pub(crate) fn preflight(c:&Context)->Result<(),OperationError>{
    core_preflight(c)?;
    for name in ["windows-startup.ps1","windows-common.ps1"]{launcher::verified_exe(&c.bin,name).map_err(|e|fail("installation",e))?;}
    Ok(())
}
fn devices(c:&Context)->anyhow::Result<Vec<Value>>{
    let result=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"devices",&[])?;
    Ok(result["data"].as_array().unwrap_or(&vec![]).iter().map(|d|json!({"id":d["id"],"name":d["name"],"createdAt":d["createdAt"]})).collect())
}
fn startup(c:&Context,action:&str)->anyhow::Result<Value>{
    launcher::verified_exe(&c.bin,"windows-common.ps1")?;
    let script=launcher::verified_exe(&c.bin,"windows-startup.ps1")?;
    let mut cmd=Command::new(storage::powershell());
    cmd.args(["-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File"]).arg(script).args(["-Action",action]);
    Ok(serde_json::from_slice(&process::run_bounded(cmd,15)?)?)
}
// Dashboard, tray and diagnostic export only read evidence. Expiry removes a
// claim of current state; it never schedules another PowerShell child.
fn startup_enabled(c:&Context)->Result<Option<bool>,OperationError>{startup_cached(c)}
fn startup_cached(c:&Context)->Result<Option<bool>,OperationError>{
    let cache=c.startup_cache.lock().map_err(|_|OperationError::new("startup","login_task_status_failed",true))?;
    if cache.in_flight||!cache.checked_at.is_some_and(|at|at.elapsed()<Duration::from_secs(60)){return Ok(None);}
    if let Some(error)=&cache.failure{Err(error.clone())}else{Ok(cache.enabled)}
}
fn startup_cache_status(c:&Context)->&'static str{
    match c.startup_cache.lock(){Ok(cache) if cache.in_flight=>"pending",
        Ok(cache) if cache.checked_at.is_some_and(|at|at.elapsed()<Duration::from_secs(60))&&cache.enabled.is_some()&&cache.failure.is_none()=>"known",_=>"unknown"}
}
pub(crate) fn invalidate_startup(c:&Context){
    if let Ok(mut cache)=c.startup_cache.lock(){cache.enabled=None;cache.failure=None;cache.checked_at=None;}
}
fn check_startup_with(c:&Context,query:impl FnOnce()->Result<bool,OperationError>)->Result<bool,OperationError>{
    {
        let mut cache=c.startup_cache.lock().map_err(|_|OperationError::new("startup","login_task_status_failed",true))?;
        if cache.in_flight{return Err(OperationError::new("startup","operation_busy",true));}
        cache.in_flight=true;cache.enabled=None;cache.failure=None;cache.checked_at=None;
    }
    let result=query();
    let mut cache=c.startup_cache.lock().map_err(|_|OperationError::new("startup","login_task_status_failed",true))?;
    cache.in_flight=false;cache.checked_at=Some(Instant::now());
    match &result{Ok(value)=>cache.enabled=Some(*value),Err(error)=>cache.failure=Some(error.clone())}
    result
}
fn startup_readback(value:&Value)->anyhow::Result<bool>{
    let enabled=value["enabled"].as_bool().ok_or_else(||anyhow::anyhow!("Invalid startup status"))?;
    let registered=value["registered"].as_bool().ok_or_else(||anyhow::anyhow!("Invalid startup registration"))?;
    anyhow::ensure!(!enabled||registered,"Unregistered startup cannot be enabled");Ok(enabled)
}
fn connectivity_status(c:&Context)->Value {
    pocket_desktop_runtime::connectivity::ConnectivityManager::new(storage::control(&c.state).join("connectivity"),c.bin.clone()).map(|m|json!(m.status())).unwrap_or(Value::Null)
}
fn public_upgrade(value:&Value)->Value {
    if !value.is_object(){return Value::Null}
    json!({"schema":value["schema"],"operationId":value["operationId"],
        "fromGeneration":value["fromGeneration"],"toGeneration":value["toGeneration"],
        "phase":value["phase"],"updatedAt":value["updatedAt"],"failure":value["failure"],
        "bridgeRestartRequired":value["bridgeRestartRequired"].as_bool()})
}
fn public_startup_failure(value:&Value)->Value{
    let valid_pair=matches!((value["phase"].as_str(),value["code"].as_str()),
        (Some("state_path"),Some("state_path_invalid"))|(Some("state_access"),Some("state_access_failed"))|
        (Some("state_lock"),Some("state_lock_failed"|"state_lock_contended"))|(Some("config"),Some("config_load_failed"))|
        (Some("identity"),Some("identity_load_failed"))|(Some("broker"),Some("broker_open_failed"))|(Some("control_bind"),Some("control_bind_failed"))|
        (Some("handoff"),Some("handoff_recovery_failed"))|(Some("assistant_restore"),Some("assistant_restore_failed")));
    let id=value["operationId"].as_str().filter(|s|s.len()==36&&s.bytes().enumerate().all(|(i,b)|if [8,13,18,23].contains(&i){b==b'-'}else{b.is_ascii_digit()||(b'a'..=b'f').contains(&b)}));
    if value["schema"]!=1||!valid_pair||id.is_none()||!value["pid"].as_u64().is_some_and(|pid|pid>0&&pid<=u32::MAX as u64)||!value["time"].as_u64().is_some_and(|time|time>0){return Value::Null;}
    let generation=value["runtimeGeneration"].as_str().filter(|s|s.len()==64&&s.bytes().all(|b|b.is_ascii_digit()||(b'a'..=b'f').contains(&b)));
    json!({"schema":1,"operationId":id,"pid":value["pid"],"runtimeGeneration":generation,"time":value["time"],"phase":value["phase"],"code":value["code"],
        "osCode":value["osCode"].as_i64().filter(|n|i32::try_from(*n).is_ok())})
}
fn public_launch_failure(value:&Value)->Value{
    use supervisor::startup_failure::{LaunchFailure,LaunchFailureCode};
    let Ok(failure)=serde_json::from_value::<LaunchFailure>(value.clone()) else{return Value::Null;};
    let expected=match failure.code {LaunchFailureCode::TrayVerificationFailed=>"tray_verify",LaunchFailureCode::TraySpawnFailed=>"tray_spawn",LaunchFailureCode::HelperSpawnFailed=>"helper_spawn"};
    if failure.schema!=1||failure.phase!=expected||failure.supervisor_pid==0||failure.time==0
        ||!supervisor::upgrade::local::canonical_uuid(&failure.operation_id)
        ||failure.runtime_generation.as_ref().is_some_and(|g|g.len()!=64||!g.bytes().all(|b|b.is_ascii_digit()||(b'a'..=b'f').contains(&b))){return Value::Null;}
    json!(failure)
}
fn read_required_config(state:&Path)->Result<Value,OperationError>{
    let value=storage::read::<Value>(&state.join("config.json"));
    match value {
        Ok(value) if value.is_object()=>Ok(value),
        result=>{
            let (code,os)=match result {
                Ok(_)=>("state_config_invalid",None),
                Err(error)=>{
                    let io=error.downcast_ref::<std::io::Error>();
                    let code=if error.downcast_ref::<storage::RedirectedPath>().is_some(){"path_redirected"}
                    else if io.is_some_and(|e|e.kind()==std::io::ErrorKind::NotFound){"state_config_missing"}
                    else if io.is_some_and(|e|e.kind()==std::io::ErrorKind::PermissionDenied){"state_config_permission_denied"}
                    else if error.downcast_ref::<serde_json::Error>().is_some(){"state_config_invalid"}
                    else{"state_config_io_failed"};
                    (code,io.and_then(|e|e.raw_os_error()))
                }
            };
            let mut error=OperationError::new("state_read",code,false);error.os_code=os;
            error.message=match code {
                "state_config_missing"=>"未找到必需的本机配置。请从开始菜单重新打开 Pocket 并查看安装诊断。",
                "state_config_invalid"=>"必需的本机配置格式无效。请查看安装诊断；现有配置未被覆盖。",
                "state_config_permission_denied"=>"当前用户无法读取必需的本机配置。请检查安装与当前 Windows 用户。",
                _=>"无法读取必需的本机配置。请查看安装诊断；尚未进入线路连接阶段。",
            }.into();Err(error)
        }
    }
}
fn optional_state<T:serde::de::DeserializeOwned>(path:&Path)->anyhow::Result<Option<T>>{
    match storage::read(path){Ok(value)=>Ok(Some(value)),Err(error) if error.downcast_ref::<std::io::Error>().is_some_and(|e|e.kind()==std::io::ErrorKind::NotFound)=>Ok(None),Err(error)=>Err(error)}
}
fn snapshot(c:&Context)->Value{
    let mut issues=Vec::<OperationError>::new();
    let consistent=match preflight(c){Ok(())=>true,Err(e)=>{issues.push(e);false}};
    let mut config=Value::Null;let mut status=Value::Null;let mut supervisor=json!(supervisor::Status::default());
    let mut desired=storage::Desired::default();let mut paired=Vec::new();let mut startup_on=None;
    if consistent {
        match read_required_config(&c.state){Ok(v)=>config=v,Err(e)=>issues.push(e)}
        match optional_state::<storage::Desired>(&storage::control(&c.state).join("desired.json")){Ok(Some(v))=>desired=v,Ok(None)=>{},Err(e)=>issues.push(fail("desired_read",e))}
        match optional_state::<supervisor::Status>(&storage::control(&c.state).join("supervisor.json")){Ok(Some(v))=>supervisor=json!(v),Ok(None)=>{},Err(e)=>issues.push(fail("supervisor_read",e))}
        if config.is_object(){
        match process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"status",&[]){Ok(v)=>status=v,Err(e)=>issues.push(fail("helper_control",e))}
        match devices(c){Ok(v)=>paired=v,Err(e)=>issues.push(fail("devices",e))}
        match startup_enabled(c){Ok(v)=>startup_on=v,Err(e)=>issues.push(e)}
        }
    }
    let routes=if consistent&&config.is_object() {match process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"routes/read",&[]) {
        Ok(value)=>value,Err(error)=>{issues.push(fail("routes_read",error));Value::Null}
    }}else{Value::Null};
    let installed_generation=pocket_desktop_runtime::installation::generation(&c.bin).unwrap_or_default();
    let installed_version=storage::read::<Value>(&c.bin.join("windows-manifest.json")).ok().and_then(|v|v["version"].as_str().map(str::to_owned)).unwrap_or_default();
    let mut installation=pocket_desktop_runtime::installation::report(&installed_generation,&installed_version,
        &pocket_desktop_runtime::installation::RuntimeStamp::current(),&supervisor["runtime"],&status["helperRuntime"],consistent);
    // A former launch failure is historical once the installed helper is actually
    // verified and answering fresh authenticated control. Spawn success alone
    // must not dismiss it. The persisted operation history remains available.
    project_control_start_error(c,consistent&&installation["helper"]["runningVerified"]==true&&installation["helper"]["state"]=="current"
        &&connection::fresh(status["updatedAt"].as_u64().unwrap_or(0),storage::now()),&mut issues);
    installation["optionalIntegrations"]=match storage::local_app_data() {
        Ok(root)=>crate::installed_integrations::read_status(&root.join("CodexPocket"),&c.bin,&c.state,consistent),
        Err(_)=>crate::installed_integrations::public_status(&Value::Null),
    };
    if consistent&&installation["optionalIntegrations"]["recordState"]=="available" {
        // Hash validation permits an explicit attempt; it is not proof that the
        // installer protocol or Task Scheduler is available on this machine.
        installation["optionalIntegrations"]["repairCheckAvailable"]=json!(crate::installed_integrations::repair_check_available(&c.bin,&installed_generation));
    }
    if consistent {
        match supervisor::upgrade::read_status(&c.state) {
            Ok(value)=>installation["upgrade"]=public_upgrade(&json!(value)),
            Err(e)=>issues.push(fail("upgrade_read",e)),
        }
    }
    supervisor["upgrade"]=public_upgrade(&supervisor["upgrade"]);
    supervisor["startupFailure"]=public_startup_failure(&supervisor["startupFailure"]);
    supervisor["trayStartupFailure"]=public_launch_failure(&supervisor["trayStartupFailure"]);
    supervisor["helperSpawnFailure"]=public_launch_failure(&supervisor["helperSpawnFailure"]);
    let connectivity=if consistent {connectivity_status(c)}else{Value::Null};
    // Route catalog and readiness must come from the same response: a network
    // refresh can change a same-ID endpoint between status and routes/read.
    let readiness=connection::route_readiness(&routes,&connectivity,storage::now());
    if status.is_object(){
        status["usableRoutes"]=json!(readiness.ready_ids.len());
        if routes["routes"].is_array(){status["routes"]=routes["routes"].clone();}
    }
    let available=desired.enabled&&supervisor["phase"]=="running"&&connection::fresh(supervisor["updatedAt"].as_u64().unwrap_or(0),storage::now())&&connection::fresh(status["updatedAt"].as_u64().unwrap_or(0),storage::now())&&!readiness.ready_ids.is_empty();
    let phase=connection::phase(&desired,consistent,&supervisor,&status,storage::now());
    let processes:Vec<_>=status["runtimes"].as_array().unwrap_or(&vec![]).iter().map(|r|json!({"name":r["source"].as_str().unwrap_or("Codex"),"state":if r["online"]==true{"在线"}else{"离线"},"version":r["version"],"bridgeVersion":r["bridgeVersion"],"helperVersion":r["helperVersion"],"bridgeUpdatePending":r["bridgeUpdatePending"]})).collect();
    let relay=config["relayUrl"].as_str().or(config["relay_url"].as_str()).unwrap_or("");
    let host=relay.split("://").nth(1).unwrap_or("").split('/').next().unwrap_or("").split('@').next_back().unwrap_or("").split('?').next().unwrap_or("");
    json!({"computerName":config["computerName"].as_str().or(config["computer_name"].as_str()).unwrap_or("此电脑"),"desired":desired.enabled,"supervisor":supervisor,"startupStatus":if consistent{startup_cache_status(c)}else{"unknown"},
        "relay":{"state":status["state"].as_str().unwrap_or("unknown"),"host":host},"devices":paired,"processes":processes,"version":env!("CARGO_PKG_VERSION"),"startup":startup_on,
        "backgroundRecovery":c.background_recovery.lock().ok().map(|v|json!(*v)),
        "installation":installation,"connection":{"phase":phase,"connected":phase=="connected","available":available,"configuredRoutes":readiness.configured,"usableRoutes":if available{readiness.ready_ids.len()}else{0},"readyRouteIds":if available{readiness.ready_ids}else{Vec::new()},"authenticatedPhone":status["authenticatedPhone"],"helperUpdatedAt":status["updatedAt"].as_u64().unwrap_or(0),"lastSuccessAt":status["lastActivityAt"],"desiredChangedAt":desired.changed_at},
        "issues":issues,"operationFailures":crate::operation_failures::history(c),"discoveryErrors":status["discoveryErrors"],"routes":routes,"cloudflareReceipt":if consistent {storage::read::<Value>(&storage::control(&c.state).join("connectivity/fixed-tunnel.json")).unwrap_or(Value::Null)}else{Value::Null},"connectivity":connectivity,"helperVersion":status["helperVersion"],"sessionPreferences":if consistent&&desired.enabled {process::local_control(&c.state,"session/preferences/read",json!({})).unwrap_or(Value::Null)}else{Value::Null}})
}
pub fn tray_status(c:&Context)->String{
    // Read-only probes may wait for a missing helper. Do not hold the action
    // mutex during them: manual recovery must remain reachable. Re-read mutable
    // intent under the mutex before dispatch; live duplicate ownership is also
    // rejected by the supervisor's user/state locks.
    let mut report=snapshot(c);
    if let Ok(_guard)=c.operation.try_lock(){
        if let (Ok(desired),Ok(upgrade))=(storage::read::<storage::Desired>(&storage::control(&c.state).join("desired.json")),supervisor::upgrade::read_status(&c.state)){
            report["desired"]=json!(desired.enabled);
            report["installation"]["upgrade"]=public_upgrade(&json!(upgrade));
            advance_background_recovery(c,&report,storage::now(),||start_local_control(c,true));
        }
    }
    let label=match report["connection"]["phase"].as_str(){Some("connected")=>"连接线路已就绪",Some("ready")=>"可以扫码配对",Some("setup_required")=>"请选择连接方式",Some("stopped")=>"远程连接已暂停",Some("failed")=>"连接需要检查",Some("starting")=>"正在启动助手",_=>"正在准备连接"};
    format!("Codex Pocket · {label}")
}
#[tauri::command]
pub async fn dashboard(context:State<'_,Context>)->Result<Value,OperationError>{
    let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||snapshot(&c)).await.map_err(join_error)
}
#[tauri::command]
pub async fn request_upgrade(context:State<'_,Context>)->Result<Value,OperationError>{
    let c=context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||{
        let _guard=c.operation.try_lock().map_err(|_|OperationError::new("upgrade","operation_busy",true))?;
        preflight(&c)?;
        if let Some(existing)=supervisor::upgrade::read_status(&c.state).map_err(|e|fail("upgrade_read",e))? {
            // Repeated clicks inspect/resume the existing operation instead of replacing it.
            if !existing.phase.terminal() {
                return supervisor::upgrade::resume_upgrade(&existing.install_root,&c.state).map(|s|public_upgrade(&json!(s))).map_err(|e|fail("upgrade",e));
            }
            if existing.phase==supervisor::upgrade::Phase::WaitingRestart &&
                pocket_desktop_runtime::installation::generation(&c.bin).ok().as_deref()==Some(&existing.to_generation) {
                return Ok(public_upgrade(&json!(existing)));
            }
        }
        let report=snapshot(&c);
        let source=&report["installation"]["supervisor"];
        if source["runningVerified"]!=true || source["state"]!="restart_required" {
            return Err(OperationError::new("upgrade","upgrade_unverified",false));
        }
        let from=source["generation"].as_str().ok_or_else(||OperationError::new("upgrade","upgrade_unverified",false))?;
        let root=storage::local_app_data().map_err(|e|fail("installation",e))?.join("CodexPocket");
        supervisor::upgrade::request_upgrade(&root,&c.state,from).map(|s|public_upgrade(&json!(s))).map_err(|e|fail("upgrade",e))
    }).await.map_err(join_error)?
}
#[tauri::command]
pub async fn set_remote(context:State<'_,Context>,enabled:bool)->Result<(),OperationError>{
    let c=context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||{
        let result=(||{
        let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;
        core_preflight(&c)?;
        set_remote_desired(&c.state,enabled,|_|manual_control_attempt(&c,||start_local_control(&c,true)))
        })();crate::operation_failures::record_result(&c,result)
    }).await.map_err(join_error)?
}
fn set_remote_desired(state:&Path,enabled:bool,mut startup:impl FnMut(&str)->anyhow::Result<Value>)->Result<(),OperationError>{
    // Pausing only needs the validated state file, even if Task Scheduler is unavailable.
    if !enabled {return storage::set_desired(state,false).map_err(|e|fail("state_access",e));}
    let previous=optional_state::<storage::Desired>(&storage::control(state).join("desired.json")).map_err(|e|fail("desired_read",e))?.unwrap_or_default();
    if !previous.enabled{storage::set_desired(state,true).map_err(|e|fail("state_access",e))?;}
    if let Err(error)=startup("Start") {
        if !previous.enabled{storage::atomic(&storage::control(state).join("desired.json"),&previous).map_err(|e|fail("state_access",e))?;}
        return Err(control_start_failure(error));
    }
    Ok(())
}
fn start_control_with(c:&Context,explicit_retry:bool,launch:impl FnOnce()->anyhow::Result<()>)->anyhow::Result<Value>{
    let mut pending=c.control_start_request.lock().map_err(|_|anyhow::anyhow!("Start request unavailable"))?;
    if explicit_retry{*pending=None;}
    if pending.is_some_and(|at|at.elapsed()<Duration::from_secs(30)){
        return Ok(json!({"requested":false,"state":"request_pending","runtimeVerification":"pending"}));
    }
    launch()?;*pending=Some(Instant::now());
    Ok(json!({"requested":true,"state":"start_requested","runtimeVerification":"pending"}))
}
fn start_local_control(c:&Context,explicit_retry:bool)->anyhow::Result<Value>{
    // Manual control uses only the current installed core. Optional scripts and
    // Task Scheduler do not gate this path, and desired.json is untouched here.
    let root=storage::local_app_data()?.join("CodexPocket");
    let (bin,state)=launcher::resolve_release(&root)?;
    anyhow::ensure!(bin==c.bin&&state==c.state,"Installation changed");
    start_control_with(c,explicit_retry,||launcher::start_supervisor(&bin,&state))
}
fn advance_background_recovery(c:&Context,report:&Value,now:u64,launch:impl FnOnce()->anyhow::Result<Value>){
    let Ok(mut recovery)=c.background_recovery.lock() else{return;};
    if !recovery.observe(report,now){return;}
    let result=launch().map_err(control_start_failure);
    if result.is_err(){recovery.spawn_failed();}
    if let Ok(mut error)=c.control_start_error.lock(){*error=result.as_ref().err().cloned();}
    let _=crate::operation_failures::record_result(c,result);
}
fn manual_control_attempt(c:&Context,launch:impl FnOnce()->anyhow::Result<Value>)->anyhow::Result<Value>{
    let result=launch();
    if let Ok(mut recovery)=c.background_recovery.lock(){recovery.manual_attempt(storage::now(),result.is_ok());}
    result
}
pub fn manual_open(c:&Context){
    let Ok(_guard)=c.operation.try_lock() else{return;};
    let result=manual_control_attempt(c,||start_local_control(c,false)).map_err(control_start_failure);
    if let Ok(mut error)=c.control_start_error.lock(){*error=result.as_ref().err().cloned();}
    let _=crate::operation_failures::record_result(c,result);
}
fn project_control_start_error(c:&Context,helper_verified:bool,issues:&mut Vec<OperationError>){
    if let Ok(mut error)=c.control_start_error.lock(){
        if helper_verified {*error=None;}
        else if let Some(value)=error.as_ref(){issues.push(value.clone());}
    }
}
fn control_start_failure(error:anyhow::Error)->OperationError{
    let mut error=fail("startup",error);
    if error.code=="startup_failed"{
        error.code="control_start_failed".into();
        error.message="本机控制服务启动请求失败。请查看安装与状态诊断；尚未确认后台就绪。".into();
    }
    error
}
#[tauri::command]
pub async fn pair_device(context:State<'_,Context>)->Result<Value,OperationError>{
    let c=context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||{
        let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;
        preflight(&c)?;
        let status=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"status",&[]).map_err(|e|fail("helper_control",e))?;
        let desired=storage::read::<storage::Desired>(&storage::control(&c.state).join("desired.json")).map_err(|e|fail("state_access",e))?;
        let supervisor=storage::read::<Value>(&storage::control(&c.state).join("supervisor.json")).map_err(|e|fail("supervisor",e))?;
        connection::ensure_pairable(&desired,&supervisor,&status,&connectivity_status(&c),storage::now())?;
        let baseline=devices(&c).map_err(|e|fail("devices",e))?.iter().map(|v|v["id"].clone()).collect::<Vec<_>>();
        let result=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"pair",&[]).map_err(|e|fail("pairing",e))?;
        let payload=pairing::read_payload(&c.state,&result,storage::now()).map_err(|e|fail("pairing_payload",e))?;
        Ok(json!({"payload":payload.to_string(),"expiresAt":result["expiresAt"],"baselineIds":baseline}))
    }).await.map_err(join_error)?
}
#[tauri::command]
pub async fn revoke_device(context:State<'_,Context>,id:String)->Result<(),OperationError>{
    let c=context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||{
        if id.len()>128||id.is_empty()||!id.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'-'||b==b'_'){return Err(OperationError::new("devices","invalid_device",false))}
        let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;preflight(&c)?;
        process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"revoke",&["--device-id",&id]).map_err(|e|fail("devices",e))?;Ok(())
    }).await.map_err(join_error)?
}
#[tauri::command]
pub async fn check_startup(context:State<'_,Context>)->Result<bool,OperationError>{
    let c=context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||{
        let _guard=c.operation.try_lock().map_err(|_|OperationError::new("startup","operation_busy",true))?;
        preflight(&c)?;
        check_startup_with(&c,||startup(&c,"Status").and_then(|v|startup_readback(&v)).map_err(|e|{
            let mut error=fail("startup",e);error.code="login_task_status_failed".into();
            error.message="登录任务状态暂时无法读取。手动连接可以继续；请在偏好设置中重试核验登录任务。".into();error
        }))
    }).await.map_err(join_error)?
}
#[tauri::command]
pub async fn set_startup(context:State<'_,Context>,enabled:bool)->Result<(),OperationError>{
    let c=context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||{
        let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;preflight(&c)?;
        check_startup_with(&c,||{
            startup(&c,if enabled{"Enable"}else{"Disable"}).and_then(|v|{
                let actual=startup_readback(&v)?;
                anyhow::ensure!(actual==enabled,"Startup readback differs from requested state");Ok(actual)
            }).map_err(|e|fail("startup",e))
        })?;Ok(())
    }).await.map_err(join_error)?
}
#[tauri::command]
pub async fn export_diagnostics(context:State<'_,Context>)->Result<String,OperationError>{
    let c=context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||{
        let report=snapshot(&c);
        let clean=diagnostics(&report,storage::now());
        let root=storage::local_app_data().map_err(|e|fail("export",e))?.join("CodexPocketDiagnostics");
        storage::private_dir(&root).map_err(|e|fail("export",e))?;
        let path=root.join(format!("diagnostics-{}.json",storage::now()));storage::atomic(&path,&clean).map_err(|e|fail("export",e))?;Ok(path.display().to_string())
    }).await.map_err(join_error)?
}

fn diagnostics(report:&Value,now:u64)->Value {
    // Export readiness, never URLs, device/route IDs, rooms, tokens or SSH data.
    let mut connection=report["connection"].clone();
    if let Some(object)=connection.as_object_mut(){object.remove("readyRouteIds");}
    let mut supervisor=report["supervisor"].clone();
    let mut installation=report["installation"].clone();
    if supervisor.is_object(){supervisor["upgrade"]=public_upgrade(&supervisor["upgrade"]);supervisor["startupFailure"]=public_startup_failure(&supervisor["startupFailure"]);}
    if installation.is_object(){
        installation["upgrade"]=public_upgrade(&installation["upgrade"]);
        installation["optionalIntegrations"]=crate::installed_integrations::public_status(&installation["optionalIntegrations"]);
    }
    let choices=|value:&Value,allowed:&[&str]|value.as_str().filter(|s|allowed.contains(s)).unwrap_or("unknown").to_owned();
    let routes:Vec<_>=report["routes"]["routeStatus"].as_array().map(Vec::as_slice).unwrap_or(&[]).iter().take(34).map(|r|json!({
        "kind":if r["id"]=="gateway"{"gateway".into()}else if r["id"]=="mdns"||r["kind"]=="discovery"||r["id"].as_str().is_some_and(|s|s.starts_with("mdns-")){"discovery".into()}else{choices(&r["kind"],&["lan","cloudflare","relay"])},
        "phase":choices(&r["phase"],&["configured","connecting","connected","reconnecting","ready","listening","failed","stopped","starting","advertising"]),
        "listenerReady":r["listenerReady"]==true,"outerConnected":r["outerConnected"]==true,"innerHandshake":r["innerHandshake"]==true,
        "activeSessions":r["activeSessions"].as_u64().unwrap_or(0),
        "failure":if r["failure"].is_object(){json!({"stage":choices(&r["failure"]["stage"],&["lan_bind","mdns_start","mdns_service","mdns_register","mdns_announce","mdns_monitor","network_inventory"]),"code":choices(&r["failure"]["code"],&["listener_bind_failed","discovery_bind_failed","discovery_multicast_failed","discovery_queue_full","discovery_stopped","discovery_invalid_service","discovery_announcement_unconfirmed","discovery_failed","network_inventory_failed"]),"osCode":r["failure"]["osCode"].as_i64()})}else{Value::Null}})).collect();
    json!({"schema":3,"version":env!("CARGO_PKG_VERSION"),"exportedAt":now,"remoteEnabled":report["desired"],"supervisor":supervisor,"connection":connection,"installation":installation,"issues":report["issues"],"operationFailures":crate::operation_failures::public_history(&report["operationFailures"]),"runtimeCount":report["processes"].as_array().map(Vec::len).unwrap_or(0),"pairedDevices":report["devices"].as_array().map(Vec::len).unwrap_or(0),"startup":report["startup"],"startupStatus":choices(&report["startupStatus"],&["pending","known","unknown"]),"routeStatus":routes,
        "routePolicy":choices(&report["routes"]["policy"],&["automatic","lan","public"]),
        "tunnel":{"phase":choices(&report["connectivity"]["phase"],&["starting","stopped","stopping","failed","waiting_network","tunnel_connected"]),"enabled":report["connectivity"]["enabled"]==true,"mode":choices(&report["connectivity"]["mode"],&["off","quick","fixed"]),"hasPublicAddress":report["connectivity"]["publicUrl"].as_str().is_some_and(|s|!s.is_empty()),"updatedAt":report["connectivity"]["updatedAt"].as_u64()}})
}

#[cfg(test)]
mod tests {
    #[test]
    fn automatic_startup_polling_never_dispatches_or_renews_expired_script_checks() {
        let state=crate::test_support::tempdir();
        let c=super::Context::new(state.path().into(),state.path().into());
        for _ in 0..3 {
            assert_eq!(super::startup_enabled(&c).unwrap(),None);
            assert_eq!(super::startup_cache_status(&c),"unknown");
            let mut cache=c.startup_cache.lock().unwrap();
            assert!(!cache.in_flight,"A passive dashboard read must never dispatch a script");
            cache.checked_at=Some(std::time::Instant::now()-std::time::Duration::from_secs(61));
        }
    }
    #[test]
    fn expired_startup_success_is_unknown_instead_of_claiming_disabled_or_current() {
        let state=crate::test_support::tempdir();
        let c=super::Context::new(state.path().into(),state.path().into());
        {
            let mut cache=c.startup_cache.lock().unwrap();
            cache.enabled=Some(true);
            cache.checked_at=Some(std::time::Instant::now()-std::time::Duration::from_secs(61));
        }
        assert_eq!(super::startup_cache_status(&c),"unknown");
        assert_eq!(super::startup_enabled(&c).unwrap(),None);
        assert!(!c.startup_cache.lock().unwrap().in_flight);
    }
    #[test]
    fn former_start_failure_clears_only_after_verified_helper_recovery(){
        let state=crate::test_support::tempdir();
        let context=super::Context::new(state.path().to_owned(),state.path().to_owned());
        let original=super::OperationError::new("startup","control_start_failed",true);
        *context.control_start_error.lock().unwrap()=Some(original.clone());
        let mut issues=Vec::new();
        super::project_control_start_error(&context,false,&mut issues);
        assert_eq!(issues[0].operation_id,original.operation_id);
        issues.clear();
        super::project_control_start_error(&context,true,&mut issues);
        assert!(issues.is_empty());
        assert!(context.control_start_error.lock().unwrap().is_none());
        super::project_control_start_error(&context,false,&mut issues);
        assert!(issues.is_empty(),"a previous failure must not return after recovery");
    }
    use super::*;
    #[test]
    fn manual_start_failure_preserves_specific_path_and_permission_classification(){
        let redirected=control_start_failure(storage::RedirectedPath.into());assert_eq!(redirected.code,"path_redirected");
        let denied=control_start_failure(std::io::Error::from_raw_os_error(5).into());assert_eq!(denied.code,"permission_denied");assert_eq!(denied.os_code,Some(5));
        let generic=control_start_failure(anyhow::anyhow!("Fixture generic start"));assert_eq!(generic.code,"control_start_failed");
    }
    #[test]
    fn explicit_startup_query_is_single_flight_and_passive_reads_never_retry_failure(){
        use std::sync::{mpsc,atomic::{AtomicUsize,Ordering}};
        let state=crate::test_support::tempdir();let c=Context::new(state.path().into(),state.path().into());
        let (release_tx,release_rx)=mpsc::channel();let (started_tx,started_rx)=mpsc::channel();
        let calls=Arc::new(AtomicUsize::new(0));let worker_calls=calls.clone();let worker=c.clone();
        let thread=std::thread::spawn(move||check_startup_with(&worker,||{
            worker_calls.fetch_add(1,Ordering::SeqCst);started_tx.send(()).unwrap();
            release_rx.recv_timeout(Duration::from_secs(2)).unwrap();Err(OperationError::new("startup","login_task_status_failed",true))
        }));
        started_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        assert_eq!(startup_cache_status(&c),"pending");assert_eq!(startup_cached(&c).unwrap(),None);
        assert_eq!(check_startup_with(&c,||panic!("Duplicate optional query")).unwrap_err().code,"operation_busy");
        release_tx.send(()).unwrap();
        assert_eq!(thread.join().unwrap().unwrap_err().code,"login_task_status_failed");
        assert_eq!(startup_cache_status(&c),"unknown");
        for _ in 0..3{assert_eq!(startup_cached(&c).unwrap_err().code,"login_task_status_failed");}
        assert_eq!(calls.load(Ordering::SeqCst),1);
        c.startup_cache.lock().unwrap().checked_at=Some(Instant::now()-Duration::from_secs(61));
        for _ in 0..3{assert_eq!(startup_cached(&c).unwrap(),None);assert!(!c.startup_cache.lock().unwrap().in_flight);}
        // Expired failure does not disable another explicit user request.
        assert!(check_startup_with(&c,||{calls.fetch_add(1,Ordering::SeqCst);Ok(true)}).unwrap());
        assert_eq!(calls.load(Ordering::SeqCst),2);assert_eq!(startup_cached(&c).unwrap(),Some(true));
    }
    #[test]
    fn startup_readback_and_invalidation_never_turn_unknown_into_success(){
        for value in [json!({}),json!({"enabled":false}),json!({"enabled":true,"registered":false}),json!({"enabled":"false","registered":true})]{assert!(startup_readback(&value).is_err());}
        assert!(!startup_readback(&json!({"enabled":false,"registered":false})).unwrap());
        let state=crate::test_support::tempdir();let c=Context::new(state.path().into(),state.path().into());
        check_startup_with(&c,||Ok(true)).unwrap();assert_eq!(startup_cache_status(&c),"known");
        invalidate_startup(&c);assert_eq!(startup_cached(&c).unwrap(),None);assert_eq!(startup_cache_status(&c),"unknown");
    }
    #[test]
    fn optional_task_scheduler_status_failure_does_not_block_manual_enable(){
        let state=crate::test_support::tempdir();storage::set_desired(state.path(),false).unwrap();
        let mut calls=Vec::new();
        set_remote_desired(state.path(),true,|action|{calls.push(action.to_owned());if action=="Status"{anyhow::bail!("Fixture optional Task Scheduler failure")};Ok(json!({"requested":true,"runtimeVerification":"pending"}))}).unwrap();
        assert_eq!(calls,vec!["Start"]);assert!(storage::desired(state.path()));
    }
    #[test]
    fn manual_control_request_never_enables_remote_or_claims_ready(){
        let state=crate::test_support::tempdir();let c=Context::new(state.path().into(),state.path().into());
        let mut launches=0;
        let result=start_control_with(&c,false,||{launches+=1;Ok(())}).unwrap();
        assert_eq!(result["state"],"start_requested");assert_eq!(result["runtimeVerification"],"pending");
        assert!(!storage::control(state.path()).join("desired.json").exists());
        let repeated=start_control_with(&c,false,||{launches+=1;Ok(())}).unwrap();
        assert_eq!(launches,1);assert_eq!(repeated["requested"],false);
        storage::set_desired(state.path(),false).unwrap();let before=std::fs::read(storage::control(state.path()).join("desired.json")).unwrap();
        start_control_with(&c,false,||Ok(())).unwrap();
        assert_eq!(before,std::fs::read(storage::control(state.path()).join("desired.json")).unwrap());
    }
    #[test]
    fn explicit_start_retry_is_not_swallowed_by_a_previous_pending_request(){
        let state=crate::test_support::tempdir();let c=Context::new(state.path().into(),state.path().into());
        start_control_with(&c,false,||Ok(())).unwrap();
        assert!(start_control_with(&c,true,||anyhow::bail!("Fixture child already exited")).is_err());
        let result=start_control_with(&c,false,||Ok(())).unwrap();assert_eq!(result["requested"],true);
    }
    #[test]
    fn automatic_recovery_dispatches_once_per_deadline_without_changing_preference(){
        let state=crate::test_support::tempdir();let c=Context::new(state.path().into(),state.path().into());
        storage::set_desired(state.path(),true).unwrap();
        let path=storage::control(state.path()).join("desired.json");let before=std::fs::read(&path).unwrap();
        let report=json!({"desired":true,"installation":{"consistent":true,"installedGeneration":"a".repeat(64)},"supervisor":{"updatedAt":0}});
        let mut launches=0;
        for now in [100,104,105,105,106,115,145,174,175,200]{
            advance_background_recovery(&c,&report,now,||{launches+=1;Ok(json!({"requested":true}))});
        }
        assert_eq!(launches,3);assert_eq!(c.background_recovery.lock().unwrap().phase,"exhausted");
        assert_eq!(std::fs::read(&path).unwrap(),before);
        manual_control_attempt(&c,||Ok(json!({"requested":true}))).unwrap();
        assert_eq!(c.background_recovery.lock().unwrap().attempts,0);
        assert_eq!(c.background_recovery.lock().unwrap().phase,"starting");
        assert_eq!(std::fs::read(&path).unwrap(),before);
    }
    #[test]
    fn launch_failure_projection_validates_fixed_codes_and_never_leaks_raw_errors(){
        for (phase,code) in [("tray_verify","tray_verification_failed"),("tray_spawn","tray_spawn_failed"),("helper_spawn","helper_spawn_failed")]{
            let value=json!({"schema":1,"operationId":"12345678-1234-4234-8234-123456789abc","supervisorPid":42,"runtimeGeneration":"a".repeat(64),"time":1794316209000u64,"phase":phase,"code":code,"osCode":5,"retryable":true});
            let safe=public_launch_failure(&value);assert_eq!(safe["code"],code);assert_eq!(safe["osCode"],5);
            for (key,invalid) in [("phase",json!("PRIVATE")),("code",json!("PRIVATE")),("supervisorPid",json!(0)),("rawError",json!("PRIVATE"))]{
                let mut bad=value.clone();bad[key]=invalid;assert_eq!(public_launch_failure(&bad),Value::Null);
            }
        }
    }
    #[test]
    fn startup_failure_projection_keeps_fixed_fields_without_liveness_claims(){
        let value=json!({"schema":1,"operationId":"12345678-1234-4234-8234-123456789abc","pid":42,"runtimeGeneration":"a".repeat(64),"time":1794316209000u64,"phase":"config","code":"config_load_failed","osCode":5,"privatePath":"fixture-private"});
        let safe=public_startup_failure(&value);assert_eq!(safe["code"],"config_load_failed");assert_eq!(safe["osCode"],5);
        assert!(!safe.to_string().contains("fixture-private"));
        let mut bad=value;bad["phase"]=json!("private-phase");assert_eq!(public_startup_failure(&bad),Value::Null);
    }

    #[test]
    fn upgrade_projection_preserves_only_boolean_bridge_restart_evidence(){
        for expected in [true,false]{
            let safe=public_upgrade(&json!({"phase":"complete","bridgeRestartRequired":expected,"privatePath":"fixture-private"}));
            assert_eq!(safe["bridgeRestartRequired"],expected);
            assert!(!safe.to_string().contains("fixture-private"));
        }
        for invalid in [Value::Null,json!("true"),json!(1),json!({"required":true})]{
            assert_eq!(public_upgrade(&json!({"bridgeRestartRequired":invalid}))["bridgeRestartRequired"],Value::Null);
        }
        assert_eq!(public_upgrade(&json!({}))["bridgeRestartRequired"],Value::Null);
    }

    #[test]
    fn startup_failure_projection_keeps_handoff_and_assistant_restore_pairs(){
        for (phase,code) in [("handoff","handoff_recovery_failed"),("assistant_restore","assistant_restore_failed")]{
            let mut value=json!({"schema":1,"operationId":"12345678-1234-4234-8234-123456789abc","pid":42,"time":1794316209000u64,"phase":phase,"code":code,"privatePath":"fixture-private"});
            let safe=public_startup_failure(&value);
            assert_eq!(safe["phase"],phase);assert_eq!(safe["code"],code);
            assert!(!safe.to_string().contains("fixture-private"));
            value["code"]=json!("config_load_failed");assert_eq!(public_startup_failure(&value),Value::Null);
        }
    }

    #[test]
    fn missing_required_config_is_distinct_from_absent_optional_heartbeat(){
        let state=crate::test_support::tempdir();
        let error=read_required_config(state.path()).unwrap_err();
        assert_eq!(error.stage,"state_read");assert_eq!(error.code,"state_config_missing");assert!(error.os_code.is_some());
        assert!(optional_state::<supervisor::Status>(&state.path().join("desktop/supervisor.json")).unwrap().is_none());
        assert!(!serde_json::to_string(&error).unwrap().contains(&state.path().display().to_string()));
    }
    #[test]
    fn invalid_required_config_preserves_its_bytes_and_reports_state_read(){
        let state=crate::test_support::tempdir();let path=state.path().join("config.json");
        std::fs::write(&path,b"fixture-invalid").unwrap();
        let error=read_required_config(state.path()).unwrap_err();assert_eq!(error.code,"state_config_invalid");
        assert_eq!(std::fs::read(&path).unwrap(),b"fixture-invalid");
    }

    #[test]
    fn diagnostics_omit_private_upgrade_journal_paths(){
        let upgrade=json!({"operationId":"operation","phase":"waiting_restart","installRoot":"private-root",
            "stateDirectory":"private-state","checkpoint":{"savedSha256":"private-checkpoint"}});
        let report=json!({"supervisor":{"upgrade":upgrade},"installation":{"upgrade":upgrade},
            "routes":{"routeStatus":[{"id":"mdns-private-address","kind":"discovery","phase":"failed"}]}});
        let safe=diagnostics(&report,100);
        assert_eq!(safe["supervisor"]["upgrade"]["phase"],"waiting_restart");
        assert_eq!(safe["installation"]["upgrade"]["operationId"],"operation");
        assert_eq!(safe["routeStatus"][0]["kind"],"discovery");
        assert!(!safe.to_string().contains("private-"));
    }

    #[test]
    fn diagnostics_keep_readiness_without_route_secrets_or_addresses(){
        let report=json!({"connection":{"phase":"setup_required","configuredRoutes":0,"usableRoutes":0,"readyRouteIds":["secret-id"]},"routes":{"policy":"automatic","routeStatus":[{"id":"secret-id","kind":"relay","phase":"connecting","endpoint":"wss://secret-host/private","listenerReady":false}]},"connectivity":{"phase":"starting","mode":"quick","enabled":true,"publicUrl":"wss://secret-host","token":"secret-token"}});
        let safe=diagnostics(&report,100);
        assert_eq!(safe["connection"]["phase"],"setup_required");
        assert_eq!(safe["routeStatus"][0]["phase"],"connecting");
        assert_eq!(safe["tunnel"]["hasPublicAddress"],true);
        assert!(!safe.to_string().contains("secret-"));
    }

    #[test]
    fn disable_persists_without_querying_unavailable_startup() {
        let state=crate::test_support::tempdir();
        storage::set_desired(state.path(),true).unwrap();
        let mut calls=Vec::new();
        let result=set_remote_desired(state.path(),false,|action|{
            calls.push(action.to_owned());
            anyhow::bail!("Fixture startup unavailable")
        });
        let desired:storage::Desired=storage::read(&storage::control(state.path()).join("desired.json")).unwrap();
        assert!(!desired.enabled,"disable must persist even when startup inspection would fail");
        assert!(result.is_ok());
        assert!(calls.is_empty(),"disable must not inspect or start a scheduled task");
    }

    #[test]
    fn enable_status_failure_preserves_desired_file() {
        let state=crate::test_support::tempdir();
        storage::set_desired(state.path(),false).unwrap();
        let path=storage::control(state.path()).join("desired.json");
        let before=std::fs::read(&path).unwrap();
        let mut calls=Vec::new();
        let error=set_remote_desired(state.path(),true,|action|{
            calls.push(action.to_owned());
            anyhow::bail!("Fixture task drift")
        }).unwrap_err();
        assert_eq!(std::fs::read(path).unwrap(),before);
        assert_eq!(calls,vec!["Start"]);
        assert_eq!(error.stage,"startup");
        assert_eq!(error.code,"control_start_failed");
        assert!(!error.message.contains("Fixture"));
    }

    #[test]
    fn enable_start_failure_restores_previous_desired_value() {
        for previous in [false,true] {
            let state=crate::test_support::tempdir();
            storage::set_desired(state.path(),previous).unwrap();
            let mut calls=Vec::new();
            let error=set_remote_desired(state.path(),true,|action|{
                calls.push(action.to_owned());
                if action=="Start" {
                    assert!(storage::desired(state.path()),"enable must be persisted before Start");
                    anyhow::bail!("Fixture start failed")
                }
                Ok(json!({"registered":true,"enabled":true}))
            }).unwrap_err();
            assert_eq!(storage::desired(state.path()),previous);
            assert_eq!(calls,vec!["Start"]);
            assert_eq!(error.stage,"startup");
        }
    }

    #[test]
    fn repeated_enable_keeps_desired_enabled() {
        let state=crate::test_support::tempdir();
        storage::set_desired(state.path(),false).unwrap();
        for _ in 0..2 {
            let mut calls=Vec::new();
            set_remote_desired(state.path(),true,|action|{
                calls.push(action.to_owned());
                if action=="Start" {assert!(storage::desired(state.path()));}
                Ok(json!({"registered":true,"enabled":true}))
            }).unwrap();
            assert_eq!(calls,vec!["Start"]);
            assert!(storage::desired(state.path()));
        }
    }

    #[test]
    fn repeated_enable_does_not_reset_startup_deadline() {
        let state=crate::test_support::tempdir();
        storage::set_desired(state.path(),true).unwrap();
        let path=storage::control(state.path()).join("desired.json");
        storage::atomic(&path,&storage::Desired{enabled:true,changed_at:100}).unwrap();
        let before=std::fs::read(&path).unwrap();
        for _ in 0..2 {
            set_remote_desired(state.path(),true,|_|Ok(json!({"registered":true,"enabled":true}))).unwrap();
            assert_eq!(std::fs::read(&path).unwrap(),before,"repeated enable must not extend the original startup deadline");
        }
    }
}

#[tauri::command]
pub async fn configure_routes(context:State<'_,Context>,preferences:Value)->Result<Value,OperationError>{
 let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{
  let result=(||{
  let _guard=c.operation.try_lock().map_err(|_|OperationError::new("route_lock","route_operation_busy",true))?;preflight(&c)?;
  let dir=prepare_route_directory(&c.state)?;
  let _route_guard=storage::Lock::acquire(&dir.join("routes.lock")).map_err(|e|fail("route_lock",e))?;
  let file=dir.join(format!("route-request-{}-{}.json",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_nanos()));
  storage::atomic(&file,&preferences).map_err(|e|fail("route_request_write",e))?;
  let result=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"routes/configure",&["--config",&file.to_string_lossy()]).map_err(|e|fail("route_apply",e));
  let _=std::fs::remove_file(&file);result
  })();crate::operation_failures::record_result(&c,result)
 }).await.map_err(join_error)?
}
#[tauri::command]
pub async fn configure_relay(context:State<'_,Context>,url:String)->Result<Value,OperationError>{
 let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{
  let result=(||{
  let _guard=c.operation.try_lock().map_err(|_|OperationError::new("route_lock","route_operation_busy",true))?;preflight(&c)?;
  let dir=prepare_route_directory(&c.state)?;
  let _routes=storage::Lock::acquire(&dir.join("routes.lock")).map_err(|e|fail("route_lock",e))?;
  let config:Value=storage::read(&c.state.join("config.json")).map_err(|e|fail("route_prepare",e))?;
  let report=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"routes/read",&[]).map_err(|e|fail("routes_read",e))?;
  let preferences=connection::relay_preferences(&report["routePreferences"],&url,config["room"].as_str().unwrap_or(""))?;
  let file=dir.join(format!("relay-request-{}-{}.json",std::process::id(),storage::now()));
  storage::atomic(&file,&preferences).map_err(|e|fail("route_request_write",e))?;
  let result=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"routes/configure",&["--config",&file.to_string_lossy()]).map_err(|e|fail("route_apply",e));
  let _=std::fs::remove_file(file);result
  })();crate::operation_failures::record_result(&c,result)
 }).await.map_err(join_error)?
}
#[tauri::command]
pub async fn open_codex(context:State<'_,Context>)->Result<(),OperationError>{
 let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{
  preflight(&c)?;let root=storage::local_app_data().map_err(|e|fail("installation",e))?.join("CodexPocket");
  let mut command=Command::new(root.join("pocket-launcher.exe"));command.arg("--pocket-desktop");process::hidden(&mut command);command.spawn().map_err(|e|fail("runtime",e.into()))?;Ok(())
 }).await.map_err(join_error)?
}
#[tauri::command]
pub async fn configure_connectivity(context:State<'_,Context>,request:pocket_desktop_runtime::connectivity::ConnectivityRequest)->Result<Value,OperationError>{
 let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{
  let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;preflight(&c)?;
  let manager=pocket_desktop_runtime::connectivity::ConnectivityManager::new(storage::control(&c.state).join("connectivity"),c.bin.clone()).map_err(|e|fail("connectivity",e))?;
  if request.enabled {ensure_gateway(&c,&request.gateway_url)?;}
  manager.configure(request).map_err(|e|fail("connectivity",e))?;Ok(json!(manager.status()))
 }).await.map_err(join_error)?
}
#[tauri::command]
pub async fn cloudflare_accounts(token:String)->Result<Value,OperationError>{tauri::async_runtime::spawn_blocking(move||pocket_desktop_runtime::connectivity::cloudflare::cloudflare_accounts(&token).map(|v|json!(v)).map_err(|e|fail("cloudflare",e))).await.map_err(join_error)?}
#[tauri::command]
pub async fn cloudflare_zones(token:String,account_id:String)->Result<Value,OperationError>{tauri::async_runtime::spawn_blocking(move||pocket_desktop_runtime::connectivity::cloudflare::cloudflare_zones(&token,&account_id).map(|v|json!(v)).map_err(|e|fail("cloudflare",e))).await.map_err(join_error)?}
#[tauri::command]
pub async fn setup_fixed(context:State<'_,Context>,request:pocket_desktop_runtime::connectivity::cloudflare::FixedTunnelRequest)->Result<Value,OperationError>{
 let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;preflight(&c)?;ensure_gateway(&c,&request.gateway_url)?;pocket_desktop_runtime::connectivity::cloudflare::setup_fixed(&storage::control(&c.state).join("connectivity"),request).map(|v|json!(v)).map_err(|e|fail("cloudflare",e))}).await.map_err(join_error)?
}

#[tauri::command]
pub async fn detect_lan()->Result<Value,OperationError>{
 tauri::async_runtime::spawn_blocking(move||{
  pocket_desktop_runtime::network::interfaces().map(|rows|json!(rows)).map_err(|e|fail("network",e.into()))
 }).await.map_err(join_error)?
}

#[tauri::command]
pub async fn session_preferences_update(context:State<'_,Context>,mode:String,known_revision:u64,command_id:String)->Result<Value,OperationError>{
 let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{
  let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;preflight(&c)?;
  if !["desktop","cli"].contains(&mode.as_str())||command_id.is_empty()||command_id.len()>128{return Err(OperationError::new("session","invalid_params",false))}
  process::local_control(&c.state,"session/preferences/update",json!({"mode":mode,"knownRevision":known_revision,"commandId":command_id})).map_err(|e|fail("session",e))
 }).await.map_err(join_error)?
}

fn prepare_route_directory(state:&Path)->Result<PathBuf,OperationError>{
 let directory=storage::control(state);
 storage::private_dir(&directory).map_err(|e|fail("route_prepare",e))?;
 Ok(directory)
}

#[cfg(test)]
mod route_prepare_tests {
 use super::*;
 #[cfg(windows)]
 #[test]
 fn preparation_failure_preserves_existing_state_and_does_not_dispatch_a_request(){
  let state=crate::test_support::tempdir();
  let occupied=storage::control(state.path());std::fs::write(&occupied,b"fixture existing file").unwrap();
  let error=prepare_route_directory(state.path()).unwrap_err();
  assert_eq!(error.stage,"route_prepare");assert!(!error.retryable);
  assert!(error.message.contains("被文件占用"));
  assert_eq!(std::fs::read(&occupied).unwrap(),b"fixture existing file");
  assert_eq!(std::fs::read_dir(state.path()).unwrap().count(),1);
 }
 #[test]
 fn preparation_creates_private_directory_and_supports_repeated_route_changes(){
  let state=crate::test_support::tempdir();
  let dir=prepare_route_directory(state.path()).unwrap();
  let path=dir.join("route-request-fixture.json");
  storage::atomic(&path,&json!({"policy":"lan"})).unwrap();
  assert_eq!(prepare_route_directory(state.path()).unwrap(),dir);
  let value:Value=storage::read(&path).unwrap();assert_eq!(value["policy"],"lan");
 }
}

fn ensure_gateway(c:&Context,url:&str)->Result<(),OperationError>{
 let gateway=pocket_desktop_runtime::connectivity::gateway(url).map_err(|e|fail("route_prepare",e))?;
 let dir=prepare_route_directory(&c.state)?;
 let _guard=storage::Lock::acquire(&dir.join("routes.lock")).map_err(|e|fail("route_lock",e))?;
 let report=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"routes/read",&[]).map_err(|e|fail("routes_read",e))?;
 let mut prefs=report["routePreferences"].clone();
 let bind=format!("{}:{}",gateway.host_str().unwrap_or("127.0.0.1"),gateway.port().unwrap_or(17892));
 if prefs["gateway"]["bind"]==bind{return Ok(())}
 prefs["gateway"]=json!({"bind":bind});
 let file=dir.join(format!("gateway-request-{}.json",std::process::id()));storage::atomic(&file,&prefs).map_err(|e|fail("route_request_write",e))?;
 let result=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"routes/configure",&["--config",&file.to_string_lossy()]).map_err(|e|fail("route_apply",e));let _=std::fs::remove_file(file);result.map(|_|())
}
