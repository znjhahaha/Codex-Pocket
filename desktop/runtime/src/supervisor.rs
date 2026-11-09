use crate::{storage::{self,Lock},process,policy};
use anyhow::{Context,Result};
use serde::{Serialize,Deserialize};
use std::{path::{Path,PathBuf},process::{Command,Child,Stdio},time::{Instant,Duration},thread};
#[derive(Serialize,Deserialize,Clone,Debug,Default)]
#[serde(rename_all="camelCase")]
pub struct Status{pub phase:String,pub pid:u32,pub child_pid:Option<u32>,pub updated_at:u64,pub failures:u32,pub last_exit:Option<i32>}
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
    #[cfg(windows)]let job=process::ChildJob::new()?;
    let mut child:Option<Child>=None;
    let mut status=Status{phase:"stopped".into(),pid:std::process::id(),..Default::default()};
    let mut retry=Instant::now();let mut started=Instant::now();
    let mut connectivity=crate::connectivity::ConnectivityManager::new(storage::control(state).join("connectivity"),agent.parent().context("Missing tools directory")?.to_owned())?;
    let mut published:Option<String>=None;
    let mut route_retry=Instant::now();
    loop{
        let enabled=storage::desired(state);
        if let Some(c)=child.as_mut(){
            if !enabled {c.kill().context("Cannot stop owned helper")?;let _=c.wait();child=None;status.phase="stopped".into();status.failures=0;retry=Instant::now();}
            else if let Some(exit)=c.try_wait()?{status.last_exit=exit.code();child=None;if started.elapsed()>Duration::from_secs(60){status.failures=0};retry=Instant::now()+Duration::from_secs(policy::restart_delay(status.failures));status.failures=status.failures.saturating_add(1);status.phase="restarting".into();}
        }
        if policy::should_run(enabled,child.is_some())&&Instant::now()>=retry{
            let mut c=Command::new(agent);c.arg("run").arg("--state").arg(state).env("LOCALAPPDATA",storage::local_app_data()?).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());process::hidden(&mut c);
            match c.spawn(){Ok(mut owned)=>{
                #[cfg(windows)]if let Err(e)=job.assign(&owned){let _=owned.kill();let _=owned.wait();return Err(e)}
                started=Instant::now();child=Some(owned);status.phase="running".into();
            },Err(_)=>{retry=Instant::now()+Duration::from_secs(policy::restart_delay(status.failures));status.failures=status.failures.saturating_add(1);status.phase="restarting".into();}}
        }
        if !enabled {status.phase="stopped".into();}
        status.child_pid=child.as_ref().map(Child::id);status.updated_at=storage::now();
        storage::atomic(&control.join("supervisor.json"),&status)?;
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
