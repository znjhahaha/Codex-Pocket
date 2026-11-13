use serde::Serialize;
use serde_json::Value;
use std::sync::atomic::{AtomicU64,Ordering};

static OPERATION:AtomicU64=AtomicU64::new(1);
#[derive(Debug,Clone,Serialize)]
#[serde(rename_all="camelCase")]
pub struct OperationError {
    pub code:String,pub stage:String,pub message:String,pub retryable:bool,pub operation_id:String,
    #[serde(skip_serializing_if="Option::is_none")]pub exit_code:Option<i32>,
    #[serde(skip_serializing_if="Option::is_none")]pub os_code:Option<i32>,
    pub actions:Vec<String>,
}

#[cfg(test)]
mod route_failure_tests {
    use super::*;
    #[test]
    fn route_lock_contention_is_actionable_and_keeps_os_code_without_private_text() {
        let error=anyhow::Error::from(fs2::lock_contended_error()).context("PRIVATE path token");
        let failure=OperationError::from_error("route_lock",&error);
        assert_eq!(failure.code,"route_operation_busy");
        let json=serde_json::to_value(&failure).unwrap();
        assert_eq!(json["osCode"].as_i64(),fs2::lock_contended_error().raw_os_error().map(i64::from));
        assert!(!json.to_string().contains("PRIVATE"));
    }
    #[test]
    fn write_and_spawn_failures_do_not_blame_network() {
        let error=anyhow::anyhow!("PRIVATE low-level error");
        assert_eq!(OperationError::from_error("route_request_write",&error).code,"route_request_write_failed");
        let missing=anyhow::Error::from(std::io::Error::from(std::io::ErrorKind::NotFound));
        assert_eq!(OperationError::from_error("route_apply",&missing).code,"helper_start_failed");
    }
}
impl OperationError {
    pub fn new(stage:&str,code:&str,retryable:bool)->Self {
        let message=match code {
            "path_redirected"=>"本机状态路径被重定向或包含目录链接，无法安全访问。请从当前 Windows 用户的开始菜单打开 Pocket；仍失败时用完整安装包修复安装路径。",
            "installation_changed"=>"连接助手安装版本已变化。请关闭界面后从开始菜单重新打开。",
            "installation_invalid"=>"安装文件校验未通过，请使用完整安装包重新安装连接助手。",
            "permission_denied"=>"无法访问连接助手状态，请检查当前 Windows 用户及安装权限。",
            "startup_failed"=>"未能启动后台助手，请在连接诊断中检查登录任务。",
            "helper_unavailable"=>"后台助手暂未响应。请稍后重新检查连接状态。",
            "setup_required"=>"尚无可用的手机连接线路。请按引导检查局域网，或配置 Cloudflare／服务器公网连接。",
            "route_not_ready"=>"连接线路还未就绪，请按引导检查网络，再重新检查。",
            "start_required"=>"远程连接已暂停，请先启动连接。",
            "invalid_relay_url"=>"请填写 wss:// 开头的中转基础地址，不要包含账号、令牌或配对房间。",
            "route_configuration_failed"=>"线路配置步骤未完成，原因尚未分类。请导出诊断并提供此操作编号。",
            "route_operation_busy"=>"另一项线路更新正在进行。请稍后重试；当前请求尚未交给助手。",
            "route_prepare_failed"=>"无法准备本机线路配置目录。请导出诊断并提供此操作编号；仅在显示系统错误码时按该错误码排查。",
            "route_lock_failed"=>"无法取得本机线路配置锁。请查看系统错误码和安装诊断。",
            "route_request_write_failed"=>"无法写入本机线路配置请求。请检查磁盘空间、文件占用和当前用户权限。",
            "route_apply_failed"=>"线路配置请求执行失败。请提供操作编号和诊断，确认电脑助手的实际运行状态。",
            "helper_start_failed"=>"无法启动已安装的电脑助手命令。请检查安装文件是否完整或被安全软件隔离。",
            "control_invalid_response"=>"电脑助手返回的配置响应无法解析。请导出诊断并核对助手运行代次。",
            "route_read_failed"=>"无法读取运行中助手的线路设置。请检查后台运行代次与控制协议；已有配置未被清空。",
            "control_protocol_unsupported"=>"运行中的旧助手不支持此操作。请在安装与更新中完成后台交接。",
            "control_timeout"=>"本机后台控制请求超时。请检查后台运行状态后重试。",
            "control_rejected"=>"本机助手拒绝了此请求。请查看安装与运行代次及控制协议；配置没有被当作空值覆盖。",
            "control_failed"=>"本机控制命令失败。请查看本步骤的退出码与安装诊断后重试。",
            "control_invalid_params"=>"后台拒绝了请求参数。请重新读取设置后重试。",
            "control_version_conflict"=>"设置已被其他操作更新。请重新读取并核对后再保存。",
            "control_unauthorized"=>"本机后台授权校验失败。请从当前 Windows 用户的开始菜单重新打开 Pocket。",
            "network_detection_failed"=>"未能检测局域网，请检查电脑网络；仍可继续配置公网连接。",
            "tunnel_failed"=>"Cloudflare 隧道设置未完成，请检查网络和授权后重试。",
            "deployment_failed"=>"服务器部署步骤未完成，请检查 SSH、域名和端口后重试。",
            "pairing_payload_invalid"=>"配对码未能读取或已经过期，请重新生成配对码。",
            "operation_busy"=>"另一项操作正在进行，请稍后重试。",
            "upgrade_unverified"=>"尚未取得旧后台的完整进程与安装代次证据，无法自动交接。请保留任务，任务完成后重新登录 Windows，再核验版本。",
            _=>"此步骤未完成，请查看连接诊断后重试。",
        };
        Self{code:code.into(),stage:stage.into(),message:message.into(),retryable,
            operation_id:format!("{}-{}-{}",crate::storage::now(),std::process::id(),OPERATION.fetch_add(1,Ordering::Relaxed)),exit_code:None,os_code:None,
            actions:if retryable {vec!["refresh".into(),"export_diagnostics".into()]}else{vec!["export_diagnostics".into()]}}
    }
    pub fn from_error(stage:&str,error:&anyhow::Error)->Self {
        if let Some(failure)=error.chain().find_map(|e|e.downcast_ref::<crate::process::CommandFailure>()){
            let mut result=Self::new(stage,failure.code,!matches!(failure.code,"control_unauthorized"|"control_protocol_unsupported"));
            result.exit_code=failure.exit_code;return result;
        }
        // anyhow's downcast follows its own context wrappers, but not arbitrary
        // Error::source chains. Prefer the actual OS failure over a wrapper IO
        // error without raw_os_error, and never infer an OS code from text.
        let io=error.chain().filter_map(|e|e.downcast_ref::<std::io::Error>()).find(|e|e.raw_os_error().is_some())
            .or_else(||error.chain().filter_map(|e|e.downcast_ref::<std::io::Error>()).last());
        let policy=error.chain().find_map(|e|e.downcast_ref::<crate::storage::DirectoryPolicyError>());
        let code=if error.chain().any(|e|e.is::<crate::storage::RedirectedPath>()){ "path_redirected" }
        else if policy==Some(&crate::storage::DirectoryPolicyError::UnexpectedOwner){"permission_denied"}
        else if stage=="route_lock"&&io.is_some_and(|e|e.kind()==std::io::ErrorKind::WouldBlock||e.raw_os_error().is_some()&&e.raw_os_error()==fs2::lock_contended_error().raw_os_error()){ "route_operation_busy" }
        else if io.is_some_and(|e|e.kind()==std::io::ErrorKind::PermissionDenied){"permission_denied"}
        else if stage=="route_apply"&&io.is_some_and(|e|e.kind()==std::io::ErrorKind::NotFound){"helper_start_failed"}
        else if stage=="route_apply"&&error.downcast_ref::<serde_json::Error>().is_some(){"control_invalid_response"}
        else {match stage {"installation"=>"installation_invalid","startup"=>"startup_failed","pairing_payload"=>"pairing_payload_invalid","network"=>"network_detection_failed","routes"=>"route_configuration_failed","routes_read"=>"route_read_failed","route_prepare"=>"route_prepare_failed","route_lock"=>"route_lock_failed","route_request_write"=>"route_request_write_failed","route_apply"=>"route_apply_failed","connectivity"|"cloudflare"=>"tunnel_failed","ssh"=>"deployment_failed",_=>"helper_unavailable"}};
        let mut result=Self::new(stage,code,!matches!(code,"path_redirected"|"installation_invalid"|"permission_denied"));
        if let Some(policy)=policy {
            result.message=match policy {
                crate::storage::DirectoryPolicyError::UnexpectedOwner=>"本机状态目录的所有者与当前 Windows 用户不一致。请使用原安装账户打开 Pocket，或用完整安装包修复当前账户的安装；反复重试不会改变目录所有者。",
                crate::storage::DirectoryPolicyError::NotDirectory=>"本机状态目录的位置被文件占用。请保留现有文件，导出诊断并通过完整安装包修复安装路径。",
                crate::storage::DirectoryPolicyError::UnsafePath=>"本机状态目录使用了不支持的路径形式。请从当前 Windows 用户的开始菜单打开 Pocket；仍失败时用完整安装包修复安装路径。",
            }.into();
            result.retryable=false;result.actions=vec!["export_diagnostics".into()];
        } else if stage=="route_prepare"&&io.is_some_and(|e|matches!(e.raw_os_error(),Some(32|33))) {
            result.message="本机线路配置目录被其他程序占用。请关闭占用该目录的程序后重试；仍失败时导出诊断并提供系统错误码。".into();
        }
        result.os_code=io.and_then(|e|e.raw_os_error());result
    }
}
pub fn fresh(timestamp:u64,now:u64)->bool{timestamp>0&&timestamp<=now.saturating_add(2)&&now.saturating_sub(timestamp)<=10}
pub const STARTUP_TIMEOUT_SECONDS:u64=30;
pub fn phase(desired:&crate::storage::Desired,consistent:bool,supervisor:&Value,helper:&Value,now:u64)->&'static str{
    if !consistent{return "failed"}
    if !desired.enabled {
        if helper["remoteControlProtocol"]==1 && helper["remoteEnabled"]==false && fresh(helper["updatedAt"].as_u64().unwrap_or(0),now) { return "stopped"; }
        if supervisor["childPid"].as_u64().is_some() && fresh(supervisor["updatedAt"].as_u64().unwrap_or(0),now) {
            return if supervisor["phase"]=="waiting_restart" {"waiting_restart"} else {"stopping"};
        }
        return "stopped";
    }
    let heartbeat=supervisor["updatedAt"].as_u64().unwrap_or(0);
    if !fresh(heartbeat,now){
        // Once this enable period has seen a supervisor, a lost heartbeat is recovery.
        if heartbeat>0&&heartbeat<=now.saturating_add(2)&&heartbeat>=desired.changed_at{return "recovering"}
        let changed=desired.changed_at;
        if changed>0&&changed<=now.saturating_add(2)&&now.saturating_sub(changed)<STARTUP_TIMEOUT_SECONDS{return "starting"}
        return "failed";
    }
    if supervisor["phase"]=="running"&&fresh(helper["updatedAt"].as_u64().unwrap_or(0),now){
        if helper["routes"].as_array().is_some_and(|r|r.is_empty()){return "setup_required"}
        if helper["connected"]==true&&helper["state"]=="connected"&&helper["usableRoutes"].as_u64()!=Some(0){return "connected"}
        if helper["usableRoutes"].as_u64().is_some_and(|n|n>0){return "ready"}
    }
    if helper["state"]=="connecting"{return "connecting"}
    "recovering"
}

/// Phone routes only. A live loopback gateway by itself cannot be scanned or reached.
pub struct RouteReadiness { pub configured:Option<usize>, pub ready_ids:Vec<String> }
pub fn route_readiness(report:&Value,tunnel:&Value,now:u64)->RouteReadiness {
    let Some(routes)=report["routes"].as_array() else{return RouteReadiness{configured:None,ready_ids:Vec::new()}};
    let policy=report["policy"].as_str().unwrap_or("automatic");
    let health=report["routeStatus"].as_array().map(Vec::as_slice).unwrap_or(&[]);
    let mut ready_ids=Vec::new();
    let active:Vec<_>=routes.iter().filter(|r|r["expiresAt"].as_u64().is_none_or(|expiry|expiry>now)).collect();
    for r in &active {
        let Some(id)=r["id"].as_str().filter(|id|!matches!(*id,"gateway"|"mdns")) else{continue};
        let kind=r["kind"].as_str().unwrap_or("");
        if policy=="lan"&&kind!="lan"||policy=="public"&&kind=="lan"{continue}
        let h=health.iter().find(|h|h["id"]==id&&h["kind"]==kind);
        let ready=match kind {
            "lan"=>h.is_some_and(|h|h["listenerReady"]==true&&h["phase"]=="listening"&&h["endpoint"].as_str().zip(r["url"].as_str()).is_some_and(|(a,b)|a.trim_end_matches('/')==b.trim_end_matches('/'))),
            "relay"=>h.is_some_and(|h|h["outerConnected"]==true&&h["phase"]=="connected"&&h["endpoint"]==r["url"]),
            "cloudflare"=>{
                let matches_host=r["url"].as_str().and_then(|v|url::Url::parse(v).ok()).zip(tunnel["publicUrl"].as_str().and_then(|v|url::Url::parse(v).ok())).is_some_and(|(route,public)|route.scheme()=="wss"&&route.origin()==public.origin()&&route.path().starts_with(public.path().trim_end_matches('/')));
                matches_host&&tunnel["enabled"]==true&&tunnel["phase"]=="tunnel_connected"&&fresh(tunnel["updatedAt"].as_u64().unwrap_or(0),now)&&health.iter().any(|h|h["id"]=="gateway"&&h["listenerReady"]==true&&h["phase"]=="ready")
            },
            _=>false,
        };
        if ready&&!ready_ids.iter().any(|v|v==id){ready_ids.push(id.to_owned());}
    }
    RouteReadiness{configured:Some(active.len()),ready_ids}
}

pub fn ensure_pairable(desired:&crate::storage::Desired,supervisor:&Value,helper:&Value,tunnel:&Value,now:u64)->Result<(),OperationError>{
    if !desired.enabled{return Err(OperationError::new("startup","start_required",true))}
    if supervisor["phase"]!="running"||!fresh(supervisor["updatedAt"].as_u64().unwrap_or(0),now)||!fresh(helper["updatedAt"].as_u64().unwrap_or(0),now){return Err(OperationError::new("helper_control","helper_unavailable",true))}
    let readiness=route_readiness(helper,tunnel,now);
    if readiness.configured==Some(0){return Err(OperationError::new("connection_setup","setup_required",true))}
    if readiness.ready_ids.is_empty(){return Err(OperationError::new("routes","route_not_ready",true))}
    Ok(())
}

/// Only accepts a credential-free base URL; the room always comes from local identity.
pub fn relay_preferences(original:&Value,base:&str,room:&str)->Result<Value,OperationError>{
    let invalid=||OperationError::new("routes","invalid_relay_url",false);
    if base.len()>2048||base.chars().any(char::is_control){return Err(invalid())}
    let mut url=url::Url::parse(base.trim()).map_err(|_|invalid())?;
    if url.scheme()!="wss"||url.host_str().is_none()||!url.username().is_empty()||url.password().is_some()||url.query().is_some()||url.fragment().is_some()||url.path().contains("/v1/tunnel")||room.is_empty()||room.len()>64||!room.bytes().all(|b|b.is_ascii_alphanumeric()||b"_-".contains(&b)){return Err(invalid())}
    url.path_segments_mut().map_err(|_|invalid())?.pop_if_empty().extend(["v1","tunnel",room,"phone"]);
    let mut preferences=original.clone();
    let routes=preferences["routes"].as_array_mut().ok_or_else(invalid)?;
    routes.retain(|r|r["id"]!="pocket-manual-relay");
    if routes.len()>=32{return Err(invalid())}
    routes.push(serde_json::json!({"id":"pocket-manual-relay","kind":"relay","url":url.as_str(),"priority":30}));
    preferences["policy"]=serde_json::json!("automatic");
    Ok(preferences)
}
