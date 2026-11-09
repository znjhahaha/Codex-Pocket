use pocket_desktop_runtime::{connection::{self,OperationError},launcher,pairing,process,storage,supervisor};
use serde_json::{json,Value};
use std::{path::{Path,PathBuf},process::Command,sync::{Arc,Mutex},time::{Duration,Instant}};
use tauri::State;

#[derive(Clone)]
pub struct Context {
    pub state:PathBuf,pub bin:PathBuf,pub operation:Arc<Mutex<()>>,
    pub startup_cache:Arc<Mutex<Option<(Instant,bool)>>>,
}
impl Context {
    pub fn new(state:PathBuf,bin:PathBuf)->Self{Self{state,bin,operation:Arc::new(Mutex::new(())),startup_cache:Arc::new(Mutex::new(None))}}
}
pub(crate) fn fail(stage:&str,error:anyhow::Error)->OperationError{OperationError::from_error(stage,&error)}
pub(crate) fn join_error(_:impl std::fmt::Display)->OperationError{OperationError::new("desktop","unavailable",true)}

pub(crate) fn preflight(c:&Context)->Result<(),OperationError>{
    let root=storage::local_app_data().map_err(|e|fail("installation",e))?.join("CodexPocket");
    let (bin,state)=launcher::resolve_release(&root).map_err(|e|fail("installation",e))?;
    if bin!=c.bin||state!=c.state{return Err(OperationError::new("installation","installation_changed",false))}
    for name in ["config.json","agent.lock","control.json","status.json","devices.json","desktop/desired.json","desktop/supervisor.json","pairing-qr.json"] {
        storage::unredirected(&state.join(name)).map_err(|e|fail("state_access",e))?;
    }
    for name in ["pocket-agent.exe","pocket-supervisor.exe","windows-startup.ps1","windows-common.ps1"] {
        launcher::verified_exe(&bin,name).map_err(|e|fail("installation",e))?;
    }
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
fn startup_enabled(c:&Context)->Result<bool,OperationError>{
    if let Ok(cache)=c.startup_cache.lock(){if let Some((at,enabled))=*cache{if at.elapsed()<Duration::from_secs(60){return Ok(enabled)}}}
    let enabled=startup(c,"Status").map_err(|e|fail("startup",e))?["enabled"].as_bool().unwrap_or(false);
    if let Ok(mut cache)=c.startup_cache.lock(){*cache=Some((Instant::now(),enabled));}Ok(enabled)
}
fn snapshot(c:&Context)->Value{
    let mut issues=Vec::<OperationError>::new();
    let consistent=match preflight(c){Ok(())=>true,Err(e)=>{issues.push(e);false}};
    let mut config=Value::Null;let mut status=Value::Null;let mut supervisor=json!(supervisor::Status::default());
    let mut desired=storage::Desired::default();let mut paired=Vec::new();let mut startup_on=false;
    if consistent {
        match storage::read::<Value>(&c.state.join("config.json")){Ok(v)=>config=v,Err(e)=>issues.push(fail("state_access",e))}
        match storage::read::<storage::Desired>(&storage::control(&c.state).join("desired.json")){Ok(v)=>desired=v,Err(e)=>issues.push(fail("state_access",e))}
        match storage::read::<supervisor::Status>(&storage::control(&c.state).join("supervisor.json")){Ok(v)=>supervisor=json!(v),Err(e)=>issues.push(fail("supervisor",e))}
        match process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"status",&[]){Ok(v)=>status=v,Err(e)=>issues.push(fail("helper_control",e))}
        match devices(c){Ok(v)=>paired=v,Err(e)=>issues.push(fail("devices",e))}
        match startup_enabled(c){Ok(v)=>startup_on=v,Err(e)=>issues.push(e)}
    }
    let routes=if consistent {process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"routes/read",&[]).unwrap_or(Value::Null)}else{Value::Null};
    let connectivity=if consistent {pocket_desktop_runtime::connectivity::ConnectivityManager::new(storage::control(&c.state).join("connectivity"),c.bin.clone()).map(|m|json!(m.status())).unwrap_or(Value::Null)}else{Value::Null};
    let phase=connection::phase(&desired,consistent,&supervisor,&status,storage::now());
    let processes:Vec<_>=status["runtimes"].as_array().unwrap_or(&vec![]).iter().map(|r|json!({"name":r["source"].as_str().unwrap_or("Codex"),"state":if r["online"]==true{"在线"}else{"离线"},"version":r["version"],"bridgeVersion":r["bridgeVersion"],"helperVersion":r["helperVersion"],"bridgeUpdatePending":r["bridgeUpdatePending"]})).collect();
    let relay=config["relayUrl"].as_str().or(config["relay_url"].as_str()).unwrap_or("");
    let host=relay.split("://").nth(1).unwrap_or("").split('/').next().unwrap_or("").split('@').next_back().unwrap_or("").split('?').next().unwrap_or("");
    json!({"computerName":config["computerName"].as_str().or(config["computer_name"].as_str()).unwrap_or("此电脑"),"desired":desired.enabled,"supervisor":supervisor,
        "relay":{"state":status["state"].as_str().unwrap_or("unknown"),"host":host},"devices":paired,"processes":processes,"version":env!("CARGO_PKG_VERSION"),"startup":startup_on,
        "installation":{"consistent":consistent},"connection":{"phase":phase,"connected":phase=="connected","available":status["available"],"authenticatedPhone":status["authenticatedPhone"],"helperUpdatedAt":status["updatedAt"].as_u64().unwrap_or(0),"lastSuccessAt":status["lastActivityAt"],"desiredChangedAt":desired.changed_at},
        "issues":issues,"discoveryErrors":status["discoveryErrors"],"routes":routes,"cloudflareReceipt":if consistent {storage::read::<Value>(&storage::control(&c.state).join("connectivity/fixed-tunnel.json")).unwrap_or(Value::Null)}else{Value::Null},"connectivity":connectivity,"helperVersion":status["helperVersion"],"sessionPreferences":if consistent&&desired.enabled {process::local_control(&c.state,"session/preferences/read",json!({})).unwrap_or(Value::Null)}else{Value::Null}})
}
pub fn tray_status(c:&Context)->String{
    let report=snapshot(c);
    let label=match report["connection"]["phase"].as_str(){Some("connected")=>"中转已连接",Some("stopped")=>"远程连接已暂停",Some("failed")=>"连接需要检查",Some("starting")=>"正在启动助手",_=>"正在恢复连接"};
    format!("Codex Pocket · {label}")
}
#[tauri::command]
pub async fn dashboard(context:State<'_,Context>)->Result<Value,OperationError>{
    let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||snapshot(&c)).await.map_err(join_error)
}
#[tauri::command]
pub async fn set_remote(context:State<'_,Context>,enabled:bool)->Result<(),OperationError>{
    let c=context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||{
        let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;
        preflight(&c)?;
        set_remote_desired(&c.state,enabled,|action|startup(&c,action))
    }).await.map_err(join_error)?
}
fn set_remote_desired(state:&Path,enabled:bool,mut startup:impl FnMut(&str)->anyhow::Result<Value>)->Result<(),OperationError>{
    // Pausing only needs the validated state file, even if Task Scheduler is unavailable.
    if !enabled {return storage::set_desired(state,false).map_err(|e|fail("state_access",e));}
    startup("Status").map_err(|e|fail("startup",e))?;
    let previous=storage::desired(state);
    if !previous{storage::set_desired(state,true).map_err(|e|fail("state_access",e))?;}
    if let Err(error)=startup("Start") {
        if !previous{storage::set_desired(state,false).map_err(|e|fail("state_access",e))?;}
        return Err(fail("startup",error));
    }
    Ok(())
}
#[tauri::command]
pub async fn pair_device(context:State<'_,Context>)->Result<Value,OperationError>{
    let c=context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||{
        let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;
        preflight(&c)?;
        let status=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"status",&[]).map_err(|e|fail("helper_control",e))?;
        if status["connected"]!=true && status["routes"].as_array().is_none_or(|r|r.is_empty()){return Err(OperationError::new("helper_control","helper_unavailable",true))}
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
pub async fn set_startup(context:State<'_,Context>,enabled:bool)->Result<(),OperationError>{
    let c=context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||{
        let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;preflight(&c)?;
        startup(&c,if enabled{"Enable"}else{"Disable"}).map_err(|e|fail("startup",e))?;
        if let Ok(mut cache)=c.startup_cache.lock(){*cache=Some((Instant::now(),enabled));}Ok(())
    }).await.map_err(join_error)?
}
#[tauri::command]
pub async fn export_diagnostics(context:State<'_,Context>)->Result<String,OperationError>{
    let c=context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||{
        let report=snapshot(&c);
        let clean=json!({"schema":2,"version":env!("CARGO_PKG_VERSION"),"exportedAt":storage::now(),"remoteEnabled":report["desired"],"supervisor":report["supervisor"],"connection":report["connection"],"installation":report["installation"],"issues":report["issues"],"runtimeCount":report["processes"].as_array().map(Vec::len).unwrap_or(0),"pairedDevices":report["devices"].as_array().map(Vec::len).unwrap_or(0),"startup":report["startup"]});
        let root=storage::local_app_data().map_err(|e|fail("export",e))?.join("CodexPocketDiagnostics");
        storage::private_dir(&root).map_err(|e|fail("export",e))?;
        let path=root.join(format!("diagnostics-{}.json",storage::now()));storage::atomic(&path,&clean).map_err(|e|fail("export",e))?;Ok(path.display().to_string())
    }).await.map_err(join_error)?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disable_persists_without_querying_unavailable_startup() {
        let state=tempfile::tempdir().unwrap();
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
        let state=tempfile::tempdir().unwrap();
        storage::set_desired(state.path(),false).unwrap();
        let path=storage::control(state.path()).join("desired.json");
        let before=std::fs::read(&path).unwrap();
        let mut calls=Vec::new();
        let error=set_remote_desired(state.path(),true,|action|{
            calls.push(action.to_owned());
            anyhow::bail!("Fixture task drift")
        }).unwrap_err();
        assert_eq!(std::fs::read(path).unwrap(),before);
        assert_eq!(calls,vec!["Status"]);
        assert_eq!(error.stage,"startup");
        assert_eq!(error.code,"startup_failed");
        assert!(!error.message.contains("Fixture"));
    }

    #[test]
    fn enable_start_failure_restores_previous_desired_value() {
        for previous in [false,true] {
            let state=tempfile::tempdir().unwrap();
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
            assert_eq!(calls,vec!["Status","Start"]);
            assert_eq!(error.stage,"startup");
        }
    }

    #[test]
    fn repeated_enable_keeps_desired_enabled() {
        let state=tempfile::tempdir().unwrap();
        storage::set_desired(state.path(),false).unwrap();
        for _ in 0..2 {
            let mut calls=Vec::new();
            set_remote_desired(state.path(),true,|action|{
                calls.push(action.to_owned());
                if action=="Start" {assert!(storage::desired(state.path()));}
                Ok(json!({"registered":true,"enabled":true}))
            }).unwrap();
            assert_eq!(calls,vec!["Status","Start"]);
            assert!(storage::desired(state.path()));
        }
    }

    #[test]
    fn repeated_enable_does_not_reset_startup_deadline() {
        let state=tempfile::tempdir().unwrap();
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
  let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;preflight(&c)?;
  let dir=storage::control(&c.state);storage::private_dir(&dir).map_err(|e|fail("routes",e))?;
  let _route_guard=storage::Lock::acquire(&dir.join("routes.lock")).map_err(|e|fail("routes",e))?;
  let file=dir.join(format!("route-request-{}-{}.json",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_nanos()));
  storage::atomic(&file,&preferences).map_err(|e|fail("routes",e))?;
  let result=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"routes/configure",&["--config",&file.to_string_lossy()]).map_err(|e|fail("routes",e));
  let _=std::fs::remove_file(&file);result
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
  let mut command=Command::new(storage::powershell());
  command.args(["-NoProfile","-NonInteractive","-Command",r#"$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); @(Get-NetIPConfiguration | Where-Object {$_.NetAdapter.Status -eq 'Up'} | Sort-Object @{Expression={if($_.IPv4DefaultGateway){0}else{1}}} | ForEach-Object {$n=$_.InterfaceAlias; $_.IPv4Address | ForEach-Object {@{address=$_.IPAddress;name=$n}}}) | ConvertTo-Json -Compress"#]);
  let bytes=process::run_bounded(command,12).map_err(|e|fail("network",e))?;
  let parsed:Value=serde_json::from_slice(&bytes).map_err(|e|fail("network",e.into()))?;
  let rows=parsed.as_array().cloned().unwrap_or_else(||vec![parsed]);
  Ok(Value::Array(rows.into_iter().filter(|v|v["address"].as_str().and_then(|s|s.parse::<std::net::Ipv4Addr>().ok()).is_some_and(|ip|ip.is_private())).collect()))
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

fn ensure_gateway(c:&Context,url:&str)->Result<(),OperationError>{
 let gateway=pocket_desktop_runtime::connectivity::gateway(url).map_err(|e|fail("routes",e))?;
 let dir=storage::control(&c.state);storage::private_dir(&dir).map_err(|e|fail("routes",e))?;
 let _guard=storage::Lock::acquire(&dir.join("routes.lock")).map_err(|e|fail("routes",e))?;
 let report=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"routes/read",&[]).map_err(|e|fail("routes",e))?;
 let mut prefs=report["routePreferences"].clone();
 let bind=format!("{}:{}",gateway.host_str().unwrap_or("127.0.0.1"),gateway.port().unwrap_or(17892));
 if prefs["gateway"]["bind"]==bind{return Ok(())}
 prefs["gateway"]=json!({"bind":bind});
 let file=dir.join(format!("gateway-request-{}.json",std::process::id()));storage::atomic(&file,&prefs).map_err(|e|fail("routes",e))?;
 let result=process::agent(&c.bin.join("pocket-agent.exe"),&c.state,"routes/configure",&["--config",&file.to_string_lossy()]).map_err(|e|fail("routes",e));let _=std::fs::remove_file(file);result.map(|_|())
}
