use crate::{storage::{self,Lock},process,policy};
#[path = "upgrade.rs"]
pub mod upgrade;
#[path = "maintenance.rs"]
pub mod maintenance;
#[path = "startup_failure.rs"]
pub mod startup_failure;
use anyhow::{Context,Result};
use serde::{Serialize,Deserialize};
use std::{path::{Path,PathBuf},process::{Command,Child,Stdio},time::{Instant,Duration},thread};
#[derive(Serialize,Deserialize,Clone,Debug,Default)]
#[serde(rename_all="camelCase")]
pub struct Status{pub phase:String,pub pid:u32,pub child_pid:Option<u32>,pub updated_at:u64,pub failures:u32,pub last_exit:Option<i32>,#[serde(default)]pub runtime:crate::installation::RuntimeStamp,#[serde(default)]pub upgrade:Option<upgrade::UpgradeStatus>,#[serde(default)]pub upgrade_error:Option<upgrade::Failure>,#[serde(default)]pub maintenance:Option<maintenance::Journal>,#[serde(default)]pub maintenance_error:Option<maintenance::Failure>,#[serde(default)]pub startup_failure:Option<startup_failure::StartupFailure>}
/// Desired is phone ingress only; stopped may intentionally retain childPid.
/// Only a fresh, authenticated helper response can confirm the remote gate.
pub fn remote_phase(enabled:bool,helper:Option<&serde_json::Value>,pid:u32,now:u64)->&'static str {
    let Some(h)=helper.filter(|h|h["helperRuntime"]["pid"].as_u64()==Some(u64::from(pid))
        &&crate::connection::fresh(h["updatedAt"].as_u64().unwrap_or(0),now)) else {
        return if enabled {"starting"} else {"stopping"};
    };
    if h["remoteControlProtocol"]!=1 || !h["remoteEnabled"].is_boolean(){return "waiting_restart"}
    match (enabled,h["remoteEnabled"].as_bool()) {
        (true,Some(true))=>"running",(false,Some(false))=>"stopped",
        (true,_)=>"starting",(false,_)=>"stopping",
    }
}

/// Read the same authenticated framed control socket as the agent CLI, without
/// spawning helper/model processes or depending on a stale status file.
fn helper_status(state:&Path)->Result<serde_json::Value>{
    use std::io::{Read,Write};
    let path=state.join("control.json");storage::unredirected(&path)?;
    anyhow::ensure!(std::fs::metadata(&path)?.len()<=4096,"Invalid control endpoint");
    let endpoint:serde_json::Value=storage::read(&path)?;
    anyhow::ensure!(endpoint["protocol"]==1,"Unsupported control protocol");
    let port=endpoint["port"].as_u64().filter(|n|*n>0&&*n<=65535).context("Invalid control port")? as u16;
    let token=endpoint["token"].as_str().filter(|s|s.len()==64&&s.bytes().all(|b|b.is_ascii_hexdigit())).context("Invalid control authentication")?;
    let mut socket=std::net::TcpStream::connect_timeout(&([127,0,0,1],port).into(),Duration::from_millis(250))?;
    socket.set_read_timeout(Some(Duration::from_millis(500)))?;socket.set_write_timeout(Some(Duration::from_millis(500)))?;
    let request=serde_json::to_vec(&serde_json::json!({"token":token,"method":"status","params":{}}))?;
    socket.write_all(&(request.len() as u32).to_be_bytes())?;socket.write_all(&request)?;
    let mut size=[0;4];socket.read_exact(&mut size)?;let size=u32::from_be_bytes(size) as usize;
    anyhow::ensure!(size<=1024*1024,"Control response exceeds limit");
    let mut bytes=vec![0;size];socket.read_exact(&mut bytes)?;
    let reply:serde_json::Value=serde_json::from_slice(&bytes)?;
    anyhow::ensure!(reply.get("error").is_none(),"Local helper status failed");
    reply.get("result").cloned().context("Invalid helper status")
}

fn agent_command(agent:&Path,state:&Path)->Result<Command>{
    let mut c=Command::new(agent);c.arg("run").arg("--supervised").arg("--state").arg(state)
        .env("LOCALAPPDATA",storage::local_app_data()?).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    process::hidden(&mut c);Ok(c)
}
pub fn run(state:&Path,agent:&Path,owner_directory:Option<PathBuf>)->Result<()> {
    storage::plain(state)?;storage::plain(agent)?;
    for name in ["config.json","agent.lock","control.json","desktop/desired.json","desktop/supervisor.json","desktop/supervisor.lock"] { storage::unredirected(&state.join(name))?; }
    if !state.join("config.json").is_file(){anyhow::bail!("Existing initialized state required")}
    let control=storage::control(state);storage::private_dir(&control)?;
    let installed_owner=owner_directory.is_none();
    let owner=owner_directory.unwrap_or(storage::local_app_data()?.join("CodexPocket/control"));storage::private_dir(&owner)?;
    let _user=Lock::acquire(&owner.join("supervisor.lock"))?;
    let _state=Lock::acquire(&control.join("supervisor.lock"))?;
    // The supervisor owns tray startup so previously installed stable launchers
    // pick up this behavior through the generation they already resolve.
    if installed_owner {
    let bin=agent.parent().context("Missing installed directory")?;
    let mut ui=Command::new(crate::launcher::verified_exe(bin,"pocket-desktop.exe")?);
    ui.arg("--state").arg(state).arg("--background");process::hidden(&mut ui);ui.spawn()?;
    }
    // Never put the agent (and possible native runtime descendants) into a
    // kill-on-close job. Supervised agents pause ingress, not native work.
    let mut child:Option<Child>=None;
    let mut status=Status{phase:"stopped".into(),pid:std::process::id(),runtime:crate::installation::RuntimeStamp::current(),..Default::default()};
    let mut retry=Instant::now();let mut started=Instant::now();
    let mut connectivity=crate::connectivity::ConnectivityManager::new(storage::control(state).join("connectivity"),agent.parent().context("Missing tools directory")?.to_owned())?;
    let mut published:Option<String>=None;
    let mut route_retry=Instant::now();
    let mut maintenance=maintenance::Gate::default();
    let mut startup_attempt:Option<startup_failure::Attempt>=None;
    let agent_generation=agent.parent().and_then(|p|crate::installation::generation(p).ok());
    loop{
        let enabled=storage::desired(state);
        if let Some(failure)=startup_attempt.as_ref().and_then(|a|a.read(state)){status.startup_failure=Some(failure);}
        if let Some(c)=child.as_mut(){
            if let Some(exit)=c.try_wait()?{status.last_exit=exit.code();child=None;if started.elapsed()>Duration::from_secs(60){status.failures=0};retry=Instant::now()+Duration::from_secs(policy::restart_delay(status.failures));status.failures=status.failures.saturating_add(1);status.phase="restarting".into();}
        }
        // Maintenance owns only the respawn gate. Retain the live Child and both
        // supervisor locks: no current agent protocol proves a safe handoff exit.
        maintenance.poll(state,status.runtime.generation.as_deref(),child.as_ref().map(Child::id));
        // Keep local control and broker alive even with phone ingress disabled.
        if maintenance.respawn_ready(child.is_some(),Instant::now()>=retry){
            let mut c=agent_command(agent,state)?;
            let operation_id=uuid::Uuid::new_v4().to_string();
            c.env("POCKET_AGENT_STARTUP_OPERATION_ID",&operation_id).stderr(Stdio::piped());
            match c.spawn(){Ok(mut owned)=>{
                startup_attempt=Some(startup_failure::Attempt::new(operation_id,owned.id(),agent_generation.clone(),owned.stderr.take()));
                started=Instant::now();child=Some(owned);status.phase=if enabled{"starting"}else{"stopping"}.into();
            },Err(_)=>{retry=Instant::now()+Duration::from_secs(policy::restart_delay(status.failures));status.failures=status.failures.saturating_add(1);status.phase="restarting".into();}}
        }
        if let Some(c)=child.as_ref(){
            let helper=helper_status(state).ok();
            status.phase=remote_phase(enabled,helper.as_ref(),c.id(),storage::now()).into();
            if matches!(status.phase.as_str(),"running"|"stopped"){status.startup_failure=None;}
        }
        if maintenance.blocks_respawn()&&child.is_none(){status.phase="maintenance".into();}
        status.maintenance=maintenance.journal.clone();status.maintenance_error=maintenance.error;
        // Corrupt upgrade diagnostics must not drop supervisor ownership while
        // a live agent/native task remains. Surface the failure in the heartbeat.
        match upgrade::read_status(state) {
            Ok(value)=>{status.upgrade=value;status.upgrade_error=None;}
            Err(_)=>{status.upgrade=None;status.upgrade_error=Some(upgrade::Failure::JournalInvalid);}
        }
        status.child_pid=child.as_ref().map(Child::id);status.updated_at=storage::now();
        storage::atomic(&control.join("supervisor.json"),&status)?;
        // Do not republish routes or reconcile/spawn connectivity helpers while
        // the coordinator owns maintenance or ownership evidence is uncertain.
        if !maintenance.blocks_respawn(){
        if !enabled {
            let mut request:crate::connectivity::ConnectivityRequest=storage::read(&control.join("connectivity/desired.json")).unwrap_or_default();
            if request.enabled {request.enabled=false;let _=connectivity.configure(request);}
        }
        let _=connectivity.reconcile();
        if Instant::now()>=route_retry {
            let tunnel=connectivity.status();
            let endpoint=advertised_endpoint(&tunnel);
            let signature=endpoint.clone().unwrap_or_default();
            if published.as_ref()!=Some(&signature) {
                if publish_tunnel_route(state,agent,endpoint.as_deref()).is_ok(){published=Some(signature);}
            }
            route_retry=Instant::now()+Duration::from_secs(5);
        }
        }
        thread::sleep(Duration::from_secs(1));
    }
}

fn publish_tunnel_route(state:&Path,agent:&Path,base:Option<&str>)->Result<()> {
    let _guard=storage::Lock::acquire(&storage::control(state).join("routes.lock"))?;
    let report=process::agent(agent,state,"routes/read",&[])?;
    let config:serde_json::Value=storage::read(&state.join("config.json"))?;
    let room=config["room"].as_str().unwrap_or("");
    let prefs=updated_tunnel_preferences(report["routePreferences"].clone(),base,room)?;
    if prefs==report["routePreferences"]{return Ok(())}
    let file=tempfile::NamedTempFile::new_in(storage::control(state))?;
    storage::atomic(file.path(),&prefs)?;
    process::agent(agent,state,"routes/configure",&["--config",&file.path().to_string_lossy()])?;Ok(())
}
fn advertised_endpoint(status:&crate::connectivity::ConnectivityStatus)->Option<String>{
    if status.enabled&&status.phase=="tunnel_connected"{status.public_url.clone()}else{None}
}
fn updated_tunnel_preferences(mut prefs:serde_json::Value,base:Option<&str>,room:&str)->Result<serde_json::Value>{
    let routes=prefs["routes"].as_array_mut().context("Route preferences unavailable")?;
    routes.retain(|r|r["id"]!="pocket-cloudflare");
    if let Some(base)=base {
        if room.is_empty()||!room.bytes().all(|c|c.is_ascii_alphanumeric()||b"_-".contains(&c)){anyhow::bail!("Invalid room")}
        let mut url=url::Url::parse(base)?;
        if url.scheme()!="wss"||url.host_str().is_none()||!url.username().is_empty()||url.password().is_some()||url.query().is_some()||url.fragment().is_some(){anyhow::bail!("Invalid tunnel URL")}
        url.set_path(&format!("/v1/tunnel/{room}/phone"));
        routes.push(serde_json::json!({"id":"pocket-cloudflare","kind":"cloudflare","url":url.as_str(),"priority":20}));
    }
    Ok(prefs)
}
#[cfg(test)]mod tests{
 use super::*;use serde_json::json;
 #[test]fn legacy_supervisor_status_has_no_maintenance_claim(){
  let status:Status=serde_json::from_value(json!({"phase":"running","pid":1,"childPid":2,"updatedAt":100,"failures":0,"lastExit":null})).unwrap();
  assert!(status.maintenance.is_none());assert!(status.maintenance_error.is_none());
  let heartbeat=serde_json::to_value(Status{maintenance_error:Some(maintenance::Failure::IdentityUnavailable),..status}).unwrap();
  assert_eq!(heartbeat["maintenanceError"],"identity_unavailable");assert_eq!(heartbeat["childPid"],2);
 }
 #[test]fn remote_gate_confirms_pause_without_terminating_agent(){
  let helper=json!({"helperRuntime":{"pid":42},"updatedAt":100,"remoteControlProtocol":1,"remoteEnabled":false});
  assert_eq!(remote_phase(false,Some(&helper),42,100),"stopped");
  assert_eq!(remote_phase(true,Some(&helper),42,100),"starting");
  let mut active=helper.clone();active["remoteEnabled"]=json!(true);
  assert_eq!(remote_phase(false,Some(&active),42,100),"stopping");
  assert_eq!(remote_phase(true,Some(&active),42,100),"running");
 }
 #[test]fn remote_gate_missing_stale_or_wrong_process_cannot_confirm_stop(){
  let legacy=json!({"helperRuntime":{"pid":42},"updatedAt":100,"connected":false});
  assert_eq!(remote_phase(false,Some(&legacy),42,100),"waiting_restart");
  assert_eq!(remote_phase(false,None,42,100),"stopping");
  let helper=json!({"helperRuntime":{"pid":42},"updatedAt":100,"remoteControlProtocol":1,"remoteEnabled":false});
  assert_eq!(remote_phase(false,Some(&helper),42,120),"stopping");
  assert_eq!(remote_phase(false,Some(&helper),99,100),"stopping");
 }
 #[test]fn agent_is_supervised_without_phone_network_flag_value(){
  let state=crate::test_support::tempdir();let command=agent_command(&state.path().join("fixture.exe"),state.path()).unwrap();
  let args:Vec<_>=command.get_args().map(|a|a.to_string_lossy().into_owned()).collect();
  assert_eq!(&args[..3],["run","--supervised","--state"]);
 }
 #[test]fn remote_status_uses_authenticated_control_and_does_not_dispatch_model_rpc(){
  use std::io::{Read,Write};
  let state=crate::test_support::tempdir();let listener=std::net::TcpListener::bind("127.0.0.1:0").unwrap();
  let token="a".repeat(64);
  storage::atomic(&state.path().join("control.json"),&json!({"protocol":1,"port":listener.local_addr().unwrap().port(),"token":token})).unwrap();
  let worker=thread::spawn(move||{
   let (mut socket,_)=listener.accept().unwrap();let mut size=[0;4];socket.read_exact(&mut size).unwrap();
   let mut bytes=vec![0;u32::from_be_bytes(size) as usize];socket.read_exact(&mut bytes).unwrap();
   let request:serde_json::Value=serde_json::from_slice(&bytes).unwrap();
   assert_eq!(request["token"],token);assert_eq!(request["method"],"status");
   let result=json!({"result":{"helperRuntime":{"pid":42},"updatedAt":100,"remoteControlProtocol":1,"remoteEnabled":false}});
   let bytes=serde_json::to_vec(&result).unwrap();socket.write_all(&(bytes.len() as u32).to_be_bytes()).unwrap();socket.write_all(&bytes).unwrap();
  });
  let status=helper_status(state.path()).unwrap();worker.join().unwrap();
  assert_eq!(remote_phase(false,Some(&status),42,100),"stopped");
 }
 #[test]fn remote_gate_wrong_protocol_or_non_boolean_cannot_confirm_stop(){
  let mut helper=json!({"helperRuntime":{"pid":42},"updatedAt":100,"remoteControlProtocol":2,"remoteEnabled":false});
  assert_eq!(remote_phase(false,Some(&helper),42,100),"waiting_restart");
  helper["remoteControlProtocol"]=json!(1);helper["remoteEnabled"]=json!("false");
  assert_eq!(remote_phase(false,Some(&helper),42,100),"waiting_restart");
 }
 #[test]fn fixed_hostname_is_not_advertised_before_registration(){
  let mut status=crate::connectivity::ConnectivityStatus{enabled:true,phase:"starting".into(),public_url:Some("wss://fixture.example".into()),..Default::default()};
  assert!(advertised_endpoint(&status).is_none());status.phase="tunnel_connected".into();assert!(advertised_endpoint(&status).is_some());status.enabled=false;assert!(advertised_endpoint(&status).is_none());
 }
 #[test]fn tunnel_publication_preserves_lan_and_foreign_routes_and_removes_only_owned_route(){
  let prefs=json!({"policy":"lan","lan":{"bind":"192.168.1.2:17891","mdns":true},"gateway":{"bind":"127.0.0.1:17892"},"routes":[{"id":"custom","kind":"relay","url":"wss://relay.example/v1/tunnel/room/phone","priority":30}]});
  let published=updated_tunnel_preferences(prefs.clone(),Some("wss://tunnel.example"),"room").unwrap();
  assert_eq!(published["routes"][1]["url"],"wss://tunnel.example/v1/tunnel/room/phone");assert_eq!(published["lan"],prefs["lan"]);assert_eq!(published["policy"],"lan");
  assert_eq!(updated_tunnel_preferences(published,None,"room").unwrap(),prefs);
 }
}
