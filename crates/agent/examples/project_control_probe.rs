//! Opt-in read-only project request through a temporary broker and the native daemon.
use pocket_agent::{broker::Broker,config::Config,storage,auth};
use serde_json::json;
#[tokio::main]
async fn main()->anyhow::Result<()> {
    let temp=tempfile::tempdir()?;
    let home=std::path::PathBuf::from(std::env::var("USERPROFILE")?).join(".codex");
    let mut config=Config::create("wss://example.invalid/pocket".into())?;
    config.codex_homes=vec![home];config.runtime_directories=vec![];
    let (broker,events)=Broker::open(config,temp.path().into())?;
    tokio::spawn(broker.clone().receive_events(events));
    tokio::spawn(broker.clone().discover());
    let token=storage::secret();
    storage::atomic_write(&temp.path().join("pairing.json"),&serde_json::to_vec(&json!({"tokenHash":auth::hash(&token),"expiresAt":storage::now()+60}))?)?;
    broker.pair(&json!({"pairingToken":token,"deviceId":"project-probe","deviceName":"Project probe"})).await?;
    let id=tokio::time::timeout(std::time::Duration::from_secs(30),async {
        loop {
            let status=broker.diagnostics().await;
            if let Some(id)=status["runtimes"].as_array().into_iter().flatten().find(|r|r["role"]["kind"]=="cliDaemon"&&r["online"]==true&&r["inventoryReady"]==true).and_then(|r|r["id"].as_str()) {break id.to_owned()}
            tokio::time::sleep(std::time::Duration::from_millis(200)).await;
        }
    }).await?;
    let reply=broker.request("project-probe","codex/request",json!({"runtimeId":id,"method":"project/list","params":{"limit":100}})).await?;
    println!("{}",json!({"projectCount":reply["data"].as_array().map(Vec::len),"runtime":id}));
    Ok(())
}
