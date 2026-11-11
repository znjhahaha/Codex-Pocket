//! Isolated, time-bounded Android/Windows transport fixture. Never discovers native runtimes.
use std::{path::PathBuf,sync::Arc,time::Duration};
use anyhow::{Result,Context};
use serde_json::json;
use pocket_agent::{auth::DeviceStore,broker::Broker,config::Config,storage};
use pocket_tunnel::ServerIdentity;
#[tokio::main]
async fn main()->Result<()> {
    let output=PathBuf::from(std::env::args().nth(1).context("output file required")?);
    anyhow::ensure!(output.is_absolute() && !output.exists(),"Use a new absolute output file");
    anyhow::ensure!(pocket_p2p::available(),"Native adapter required");
    let temp=tempfile::tempdir()?;let state=temp.path().join("agent");storage::private_directory(&state)?;
    let identity=Arc::new(ServerIdentity::load_or_create(state.join("identity"))?);
    let secret=uuid::Uuid::new_v4().to_string();
    let mut devices=DeviceStore::default();devices.add("fixture-phone".into(),"Fixture".into(),&secret,storage::now()).map_err(anyhow::Error::msg)?;
    storage::atomic_write(&state.join("devices.json"),&serde_json::to_vec(&devices)?)?;
    let config=Config::create("wss://fixture.invalid/pocket".into())?;
    let id=config.computer_id.clone();let (broker,_)=Broker::open(config,state)?;
    let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    storage::atomic_write(&output,&serde_json::to_vec(&json!({"port":listener.local_addr()?.port(),"computerId":id,"certificateSha256":identity.certificate_sha256(),"deviceId":"fixture-phone","deviceSecret":secret}))?)?;
    println!("Isolated P2P device fixture ready; expires in 240 seconds");
    let until=tokio::time::sleep(Duration::from_secs(240));tokio::pin!(until);
    let mut sessions=tokio::task::JoinSet::new();
    loop {tokio::select! {
        _=&mut until=>break,
        incoming=listener.accept()=>{let (stream,_)=incoming?;let broker=broker.clone();let identity=identity.clone();sessions.spawn(async move {
            if let Ok(tls)=identity.accept_tls(stream).await {let _=pocket_agent::session::serve_with_identity(tls,broker,identity).await;}
        });},
        Some(_)=sessions.join_next(),if !sessions.is_empty()=>{},
    }}
    println!("Isolated P2P device fixture expired");Ok(())
}
