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
            "setup_required"=>"尚无可用的手机连接线路。请按引导检查局域网，或配置 Cloudflare／服务器公网连接。",
            "route_not_ready"=>"连接线路还未就绪，请按引导检查网络，再重新检查。",
            "start_required"=>"远程连接已暂停，请先启动连接。",
            "invalid_relay_url"=>"请填写 wss:// 开头的中转基础地址，不要包含账号、令牌或配对房间。",
            "route_configuration_failed"=>"线路设置未完成，请检查地址和网络后重试。",
            "network_detection_failed"=>"未能检测局域网，请检查电脑网络；仍可继续配置公网连接。",
            "tunnel_failed"=>"Cloudflare 隧道设置未完成，请检查网络和授权后重试。",
            "deployment_failed"=>"服务器部署步骤未完成，请检查 SSH、域名和端口后重试。",
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
        else {match stage {"installation"=>"installation_invalid","startup"=>"startup_failed","pairing_payload"=>"pairing_payload_invalid","network"=>"network_detection_failed","routes"=>"route_configuration_failed","connectivity"|"cloudflare"=>"tunnel_failed","ssh"=>"deployment_failed",_=>"helper_unavailable"}};
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
