//! Bounded operation correlation without messages, configurations, or paths.
use serde_json::{json,Value};
use std::{io::Read,path::Path};
use pocket_desktop_runtime::{connection::OperationError,storage};
const LIMIT:usize=32;
const STAGES:&[&str]=&["route_prepare","route_lock","route_request_write","routes_read","route_apply","routes","installation","state_access","state_read","startup","installation_repair"];
const CODES:&[&str]=&["route_prepare_failed","route_operation_busy","route_lock_failed","route_request_write_failed","route_read_failed","route_apply_failed",
 "route_configuration_failed","helper_start_failed","helper_unavailable","control_invalid_response","control_invalid_params","control_unauthorized","control_protocol_unsupported",
 "control_rejected","control_failed","control_timeout","control_version_conflict","control_invalid_params","operation_busy","invalid_params","path_redirected",
 "installation_invalid","installation_changed","permission_denied","invalid_relay_url","repair_unsupported","route_configuration_invalid",
 "control_start_failed","login_task_status_failed","state_config_missing","state_config_invalid","state_config_permission_denied","state_config_io_failed"];
fn valid_id(id:&str)->bool{
 let parts:Vec<_>=id.split('-').collect();
 (parts.len()==3&&parts.iter().all(|p|!p.is_empty()&&p.len()<=20&&p.bytes().all(|b|b.is_ascii_digit())))||
 (id.len()==36&&id.bytes().enumerate().all(|(i,b)|if [8,13,18,23].contains(&i){b==b'-'}else{b.is_ascii_digit()||(b'a'..=b'f').contains(&b)}))
}
pub fn public_history(value:&Value)->Value {
 let result:Vec<_>=value.as_array().map(Vec::as_slice).unwrap_or(&[]).iter().rev().take(LIMIT).filter_map(|error|{
  let id=error["operationId"].as_str().filter(|id|valid_id(id))?;
  let stage=error["stage"].as_str().filter(|stage|STAGES.contains(stage))?;
  let code=error["code"].as_str().filter(|code|CODES.contains(code)).unwrap_or("unknown");
  let integer=|value:&Value|value.as_i64().filter(|v|i32::try_from(*v).is_ok());
  Some(json!({"operationId":id,"stage":stage,"code":code,"osCode":integer(&error["osCode"]),"exitCode":integer(&error["exitCode"]),"recordedAt":error["recordedAt"].as_u64().unwrap_or(0)}))
 }).collect();
 json!(result.into_iter().rev().collect::<Vec<_>>())
}
fn read(root:&Path)->anyhow::Result<Value>{
 let path=root.join("operation-failures.json");storage::unredirected(&path)?;
 if !path.try_exists()?{return Ok(json!([]))}
 let mut bytes=Vec::new();std::fs::File::open(path)?.take(64*1024+1).read_to_end(&mut bytes)?;
 anyhow::ensure!(bytes.len()<=64*1024,"History exceeds limit");
 let value:Value=serde_json::from_slice(&bytes)?;anyhow::ensure!(value["schema"]==1&&value["failures"].is_array(),"Invalid history");
 Ok(public_history(&value["failures"]))
}
fn persist(root:&Path,error:&Value)->anyhow::Result<()> {
 storage::unredirected(root)?;storage::private_dir(root)?;
 let _guard=storage::Lock::acquire(&root.join("operation-failures.lock"))?;
 let mut history=read(root)?.as_array().cloned().unwrap_or_default();
 let safe=public_history(&json!([error]));
 for item in safe.as_array().unwrap(){history.retain(|e|e["operationId"]!=item["operationId"]);history.push(item.clone());}
 let clean=public_history(&json!(history));storage::atomic(&root.join("operation-failures.json"),&json!({"schema":1,"failures":clean}))
}
pub fn record_result<T>(c:&crate::commands::Context,result:Result<T,OperationError>)->Result<T,OperationError>{
 if let Err(error)=&result{
  let mut entry=serde_json::to_value(error).unwrap_or(Value::Null);entry["recordedAt"]=json!(storage::now());
  let clean=public_history(&json!([entry]));
  if let Ok(mut history)=c.action_failures.lock(){if let Some(item)=clean[0].as_object(){history.push(Value::Object(item.clone()));if history.len()>LIMIT{history.remove(0);}}}
  if let Ok(root)=storage::local_app_data(){let _=persist(&root.join("CodexPocketDiagnostics"),&entry);}
 }
 result
}
pub fn history(c:&crate::commands::Context)->Value{
 let mut entries=storage::local_app_data().ok().and_then(|root|read(&root.join("CodexPocketDiagnostics")).ok()).and_then(|v|v.as_array().cloned()).unwrap_or_default();
 if let Ok(current)=c.action_failures.lock(){for item in current.iter(){entries.retain(|e|e["operationId"]!=item["operationId"]);entries.push(item.clone());}}
 public_history(&json!(entries))
}

#[cfg(test)]
mod tests {
 use super::*;
 use pocket_desktop_runtime::storage;
 #[test]
 fn failure_history_keeps_correlation_and_numeric_codes_without_private_text(){
  let history=json!([{"operationId":"1794316209-20576-24","stage":"route_lock","code":"route_operation_busy","osCode":33,"exitCode":5,"recordedAt":1,
   "message":"fixture-private-token","config":{"token":"fixture-private-token"},"path":"fixture-private-path"}]);
  let safe=public_history(&history);
  assert_eq!(safe[0]["operationId"],"1794316209-20576-24");assert_eq!(safe[0]["osCode"],33);assert_eq!(safe[0]["exitCode"],5);
  assert!(!safe.to_string().contains("fixture-private"));
 }
 #[test]
 fn persisted_ring_is_bounded_and_preserves_previous_operation_ids(){
  let root=crate::test_support::tempdir();
  for i in 0..40 {persist(root.path(),&json!({"operationId":format!("1794316209-20576-{i}"),"stage":"route_apply","code":"control_failed","recordedAt":i})).unwrap();}
  let data:Value=storage::read(&root.path().join("operation-failures.json")).unwrap();
  let failures=data["failures"].as_array().unwrap();assert_eq!(failures.len(),32);assert_eq!(failures[0]["operationId"],"1794316209-20576-8");
 }
 #[test]
 fn corrupt_existing_history_is_not_overwritten(){
  let root=crate::test_support::tempdir();let path=root.path().join("operation-failures.json");std::fs::write(&path,b"fixture-invalid").unwrap();
  assert!(persist(root.path(),&json!({"operationId":"1-2-3","stage":"route_prepare","code":"route_prepare_failed"})).is_err());
  assert_eq!(std::fs::read(path).unwrap(),b"fixture-invalid");
 }
}
