//! Read the installed desktop's original owner without starting or resuming it.
#[cfg(windows)]
#[tokio::main]
async fn main()->anyhow::Result<()> {
    use pocket_agent::native;
    use serde_json::json;
    let home=std::path::PathBuf::from(std::env::var("USERPROFILE")?).join(".codex");
    let thread=std::env::args().nth(1).ok_or_else(||anyhow::anyhow!("Thread required"))?;
    let mode=std::env::args().nth(2).unwrap_or_default();
    let (tx,mut rx)=tokio::sync::mpsc::channel(256);
    tokio::spawn(async move {while let Some(event)=rx.recv().await {match event {native::NativeEvent::Snapshot{reply,value,..}|native::NativeEvent::PendingSnapshot{reply,value,..}=>{let _=reply.send(Ok(value));},_=>{}}}});
    let catalog=if matches!(mode.as_str(),"catalog-direct"|"verify-context") {None}else{native::cli(&home,tx.clone()).await.ok().map(|c|c.rpc)};
    let connected=tokio::time::timeout(std::time::Duration::from_secs(15),native::desktop_ipc::Client::connect(&home,&thread,catalog,tx)).await??;
    let settings=connected.rpc.call("pocket/threadSettings",json!({"threadId":thread})).await?;
    let read=connected.rpc.call("thread/read",json!({"threadId":thread,"includeTurns":false})).await?;
    let models=if mode=="verify-context" {json!({})}else{connected.rpc.call("model/list",json!({"limit":100})).await?};
    println!("{}",json!({"runtime":connected.runtime_id,"thread":read["thread"]["id"],"status":read["thread"]["status"],"settings":settings["settings"],"tokenUsage":read["thread"]["tokenUsage"],"modelCount":models["data"].as_array().map(Vec::len)}));
    if mode=="catalog-direct" {anyhow::ensure!(!models["data"].as_array().is_none_or(Vec::is_empty),"No models without CLI daemon");return Ok(())}
    drop(connected);
    let state=tempfile::tempdir()?;
    let state_path=if mode.is_empty(){state.path().to_path_buf()}else{state.keep()};
    if !mode.is_empty() {eprintln!("probe ledger retained at {}",state_path.display());}
    let mut config=pocket_agent::config::Config::create("wss://example.invalid/pocket".into())?;
    config.codex_homes=vec![home.clone()];config.runtime_directories=vec![];
    let (broker,events)=pocket_agent::broker::Broker::open(config,state_path.clone())?;
    let _events=tokio::spawn(broker.clone().receive_events(events));
    let _discover=tokio::spawn(broker.clone().discover());
    let token=pocket_agent::storage::secret();
    pocket_agent::storage::atomic_write(&state_path.join("pairing.json"),&serde_json::to_vec(&json!({"tokenHash":pocket_agent::auth::hash(&token),"expiresAt":pocket_agent::storage::now()+60}))?)?;
    broker.pair(&json!({"pairingToken":token,"deviceId":"control-probe","deviceName":"Control fixture"})).await?;
    let store=pocket_agent::storage::config_store(&home)?;
    eprintln!("probe: attaching original desktop owner");
    let attached=tokio::time::timeout(std::time::Duration::from_secs(30),broker.request("control-probe","threads/control/read",json!({"configStoreId":store,"threadId":thread}))).await??;
    let runtime=attached["runtimeId"].as_str().ok_or_else(||anyhow::anyhow!("No desktop owner"))?;
    eprintln!("probe: reading permission");
    let permission=tokio::time::timeout(std::time::Duration::from_secs(30),broker.request("control-probe","thread/permissions/read",json!({"runtimeId":runtime,"threadId":thread}))).await??;
    eprintln!("probe: reading access");
    let access=tokio::time::timeout(std::time::Duration::from_secs(30),broker.request("control-probe","thread/access/read",json!({"runtimeId":runtime,"threadId":thread}))).await??;
    anyhow::ensure!(permission["status"]=="ready"&&permission["currentPresetId"]=="full","Permissions not readable");
    anyhow::ensure!(access["canSend"]==true,"Control unavailable");
    println!("{}",json!({"brokerPermission":permission["currentLabel"],"canSend":access["canSend"],"runtime":runtime}));
    if mode=="verify-context" {
        let context=broker.request("control-probe","thread/context/read",json!({"runtimeId":runtime,"threadId":thread})).await?;
        anyhow::ensure!(context["status"]=="ok" && context["usage"]["last"]["totalTokens"].is_u64()
            && context["usage"]["modelContextWindow"].is_u64(),"Native context usage unavailable");
        println!("broker context: {}",context);
    }
    if mode=="verify-full" {
        // Reapply the already-observed full preset. Does not broaden this thread.
        let updated=broker.request("control-probe","thread/permissions/update",json!({"runtimeId":runtime,"threadId":thread,
            "presetId":"full","expectedRevision":permission["revision"],"commandId":uuid::Uuid::new_v4().to_string()})).await?;
        anyhow::ensure!(updated["currentPresetId"]=="full","Full access readback failed");
        println!("full-access update and authoritative readback passed");
    }
    if mode=="send-stop-fixture" {
        anyhow::ensure!(thread=="01a1f357-b441-7433-91ba-d5703930443f","Only the authorized subagent fixture may receive this test");
        let send=broker.request("control-probe","codex/request",json!({"runtimeId":runtime,"commandId":uuid::Uuid::new_v4().to_string(),"method":"turn/start",
            "params":{"threadId":thread,"clientUserMessageId":uuid::Uuid::new_v4().to_string(),"input":[{"type":"text","text":"本次是 Pocket 控制链路验收。请先等待 20 秒，然后只回复 POCKET_CONTROL_OK_1119。不要修改任何文件或启动其他任务。"}]}})).await?;
        println!("send result: {}",send);
        let turn=send["turn"]["id"].as_str().ok_or_else(||anyhow::anyhow!("Turn ID missing"))?;
        let stopped=broker.request("control-probe","codex/request",json!({"runtimeId":runtime,"commandId":uuid::Uuid::new_v4().to_string(),"method":"turn/interrupt",
            "params":{"threadId":thread,"turnId":turn}})).await?;
        println!("stop result: {}",stopped);
    }
    Ok(())
}
#[cfg(not(windows))]
fn main(){}
