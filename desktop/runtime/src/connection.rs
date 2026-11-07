use serde::Serialize;
use serde_json::Value;
use std::sync::atomic::{AtomicU64,Ordering};

static OPERATION:AtomicU64=AtomicU64::new(1);
#[derive(Debug,Clone,Serialize)]
#[serde(rename_all="camelCase")]
pub struct OperationError {
    pub code:String,pub stage:String,pub message:String,pub retryable:bool,pub operation_id:String,
}
impl OperationError {
    pub fn new(stage:&str,code:&str,retryable:bool)->Self {
        let message=match code {
            "path_redirected"=>"当前启动环境读取到了旧的隔离状态。请从 Windows 开始菜单打开 Codex Pocket。",
            "installation_changed"=>"连接助手安装版本已变化。请关闭界面后从开始菜单重新打开。",
            "installation_invalid"=>"安装文件校验未通过，请使用完整安装包重新安装连接助手。",
            "permission_denied"=>"无法访问连接助手状态，请检查当前 Windows 用户及安装权限。",
            "startup_failed"=>"未能启动后台助手，请在连接诊断中检查登录任务。",
            "helper_unavailable"=>"后台助手暂未响应。请稍后重新检查连接状态。",
            "pairing_payload_invalid"=>"配对码未能读取或已经过期，请重新生成配对码。",
            "operation_busy"=>"另一项操作正在进行，请稍后重试。",
            _=>"此步骤未完成，请查看连接诊断后重试。",
        };
        Self{code:code.into(),stage:stage.into(),message:message.into(),retryable,
            operation_id:format!("{}-{}-{}",crate::storage::now(),std::process::id(),OPERATION.fetch_add(1,Ordering::Relaxed))}
    }
    pub fn from_error(stage:&str,error:&anyhow::Error)->Self {
        let code=if error.downcast_ref::<crate::storage::RedirectedPath>().is_some(){"path_redirected"}
        else if error.downcast_ref::<std::io::Error>().is_some_and(|e|e.kind()==std::io::ErrorKind::PermissionDenied){"permission_denied"}
        else {match stage {"installation"=>"installation_invalid","startup"=>"startup_failed","pairing_payload"=>"pairing_payload_invalid",_=>"helper_unavailable"}};
        Self::new(stage,code,!matches!(code,"path_redirected"|"installation_invalid"|"permission_denied"))
    }
}
pub fn fresh(timestamp:u64,now:u64)->bool{timestamp>0&&timestamp<=now.saturating_add(2)&&now.saturating_sub(timestamp)<=10}
pub const STARTUP_TIMEOUT_SECONDS:u64=30;
pub fn phase(desired:&crate::storage::Desired,consistent:bool,supervisor:&Value,helper:&Value,now:u64)->&'static str{
    if !consistent{return "failed"}
    if !desired.enabled{return "stopped"}
    let heartbeat=supervisor["updatedAt"].as_u64().unwrap_or(0);
    if !fresh(heartbeat,now){
        // Once this enable period has seen a supervisor, a lost heartbeat is recovery.
        if heartbeat>0&&heartbeat<=now.saturating_add(2)&&heartbeat>=desired.changed_at{return "recovering"}
        let changed=desired.changed_at;
        if changed>0&&changed<=now.saturating_add(2)&&now.saturating_sub(changed)<STARTUP_TIMEOUT_SECONDS{return "starting"}
        return "failed";
    }
    if supervisor["phase"]=="running"&&helper["connected"]==true&&helper["state"]=="connected"&&fresh(helper["updatedAt"].as_u64().unwrap_or(0),now){return "connected"}
    if helper["state"]=="connecting"{return "connecting"}
    "recovering"
}
