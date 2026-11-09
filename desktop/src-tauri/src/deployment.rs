use crate::commands::{Context,preflight,fail,join_error};
use pocket_desktop_runtime::{connectivity::{cloudflare,ssh_deploy},storage};
use serde_json::{Value,json};
use tauri::State;
use pocket_desktop_runtime::connection::OperationError;

#[tauri::command]
pub async fn inspect_ssh(target:ssh_deploy::SshTarget)->Result<Value,OperationError>{tauri::async_runtime::spawn_blocking(move||ssh_deploy::inspect_host(target).map(|v|json!(v)).map_err(|e|fail("ssh",e))).await.map_err(join_error)?}
#[tauri::command]
pub async fn preflight_ssh(context:State<'_,Context>,target:ssh_deploy::SshTarget)->Result<Value,OperationError>{let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{preflight(&c)?;ssh_deploy::preflight(&c.state,target).map_err(|e|fail("ssh",e))}).await.map_err(join_error)?}
#[tauri::command]
pub async fn deploy_relay(context:State<'_,Context>,request:ssh_deploy::RelayDeployRequest)->Result<Value,OperationError>{
 let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;preflight(&c)?;ssh_deploy::deploy(&c.state,request).map(|v|json!(v)).map_err(|e|fail("ssh",e))}).await.map_err(join_error)?
}
#[tauri::command]
pub async fn rollback_relay(context:State<'_,Context>,target:ssh_deploy::SshTarget,operation_id:String)->Result<Value,OperationError>{
 let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;preflight(&c)?;ssh_deploy::rollback(&c.state,target,&operation_id).map(|v|json!(v)).map_err(|e|fail("ssh",e))}).await.map_err(join_error)?
}
#[tauri::command]
pub async fn export_relay(context:State<'_,Context>,hostname:String,destination:std::path::PathBuf)->Result<String,OperationError>{
 let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{preflight(&c)?;ssh_deploy::export_bundle(&c.state,&hostname,&destination).map(|v|v.display().to_string()).map_err(|e|fail("ssh",e))}).await.map_err(join_error)?
}
#[tauri::command]
pub async fn rollback_cloudflare(context:State<'_,Context>,token:Option<String>)->Result<Value,OperationError>{
 let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;preflight(&c)?;cloudflare::rollback_fixed(&storage::control(&c.state).join("connectivity"),token).map(|v|json!(v)).map_err(|e|fail("cloudflare",e))}).await.map_err(join_error)?
}

#[tauri::command]
pub async fn activate_relay(context:State<'_,Context>,operation_id:String)->Result<Value,OperationError>{
 let c=context.inner().clone();tauri::async_runtime::spawn_blocking(move||{
  let _guard=c.operation.try_lock().map_err(|_|OperationError::new("desktop","operation_busy",true))?;preflight(&c)?;
  if operation_id.len()!=36||!operation_id.bytes().all(|b|b.is_ascii_hexdigit()||b==b'-'){return Err(OperationError::new("routes","invalid_params",false))}
  let receipt:ssh_deploy::DeploymentReceipt=storage::read(&c.state.join("desktop/relay-deployments").join(format!("{operation_id}.json"))).map_err(|e|fail("routes",e))?;
  if !["deployed_pending_handshake","rolled_back_pending_handshake"].contains(&receipt.phase.as_str())||!pocket_desktop_runtime::connectivity::valid_hostname(&receipt.hostname){return Err(OperationError::new("routes","invalid_params",false))}
  let config:Value=storage::read(&c.state.join("config.json")).map_err(|e|fail("routes",e))?;
  let room=config["room"].as_str().filter(|s|!s.is_empty()&&s.bytes().all(|b|b.is_ascii_alphanumeric()||b"_-".contains(&b))).ok_or_else(||OperationError::new("routes","invalid_params",false))?;
  let dir=storage::control(&c.state);storage::private_dir(&dir).map_err(|e|fail("routes",e))?;
  let _routes=storage::Lock::acquire(&dir.join("routes.lock")).map_err(|e|fail("routes",e))?;
  let agent=c.bin.join("pocket-agent.exe");
  let report=pocket_desktop_runtime::process::agent(&agent,&c.state,"routes/read",&[]).map_err(|e|fail("routes",e))?;
  let mut prefs=report["routePreferences"].clone();
  let routes=prefs["routes"].as_array_mut().ok_or_else(||OperationError::new("routes","invalid_params",false))?;
  routes.retain(|r|r["id"]!="pocket-ssh-relay");routes.push(json!({"id":"pocket-ssh-relay","kind":"relay","url":format!("wss://{}/v1/tunnel/{room}/phone",receipt.hostname),"priority":30}));
  let file=dir.join(format!("relay-request-{}.json",std::process::id()));storage::atomic(&file,&prefs).map_err(|e|fail("routes",e))?;
  let result=pocket_desktop_runtime::process::agent(&agent,&c.state,"routes/configure",&["--config",&file.to_string_lossy()]).map_err(|e|fail("routes",e));let _=std::fs::remove_file(file);result
 }).await.map_err(join_error)?
}
