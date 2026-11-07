//! Read-only integration of the broker with the installed original runtimes.
use anyhow::{Context, Result, bail};
use pocket_agent::{auth, broker::Broker, config::Config, storage};
use serde_json::json;
use std::{path::PathBuf, time::Duration};
#[tokio::main]
async fn main() -> Result<()> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    let real = Config::load(&root.join(".local/agent"))?;
    let temp = tempfile::tempdir()?;
    let (b, rx) = Broker::open(real, temp.path().into())?;
    let events = tokio::spawn(b.clone().receive_events(rx));
    let discovery = tokio::spawn(b.clone().discover());
    let token = storage::secret();
    storage::atomic_write(
        &temp.path().join("pairing.json"),
        &serde_json::to_vec(
            &json!({"tokenHash":auth::hash(&token),"expiresAt":storage::now()+60}),
        )?,
    )?;
    let pair=b.pair(&json!({"pairingToken":token,"deviceId":"probe","deviceName":"Disposable integration probe"})).await?;
    b.authenticate(&json!({"protocol":1,"deviceId":"probe","deviceSecret":pair["deviceSecret"]}))
        .await?;
    let mut runtimes = json!([]);
    for _ in 0..30 {
        runtimes = b.hello().await["runtimes"].clone();
        if runtimes.as_array().is_some_and(|r| {
            r.iter().any(|v| v["source"] == "desktop") && r.iter().any(|v| v["source"] == "cli")
        }) {
            break;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    let data = runtimes.as_array().context("runtime directory")?;
    if data.len() < 2 {
        bail!("Expected both original desktop and CLI runtime")
    }
    let desktop = data
        .iter()
        .find(|r| r["source"] == "desktop")
        .context("desktop runtime")?;
    let report: serde_json::Value =
        serde_json::from_slice(&std::fs::read(root.join("docs/desktop-origin-probe.json"))?)?;
    let read=b.request("probe","codex/request",json!({"runtimeId":desktop["id"],"method":"thread/read","params":{"threadId":report["threadId"],"includeTurns":true}})).await?;
    if read["thread"]["id"] != report["threadId"]
        || read["pocketCursor"]["sequence"].as_u64().is_none()
    {
        bail!("Native thread ID or snapshot watermark missing")
    }
    let list = b
        .request(
            "probe",
            "threads/list",
            json!({"limit":5,"search":"DESKTOP_PHONE_TEST"}),
        )
        .await?;
    let sync = b.request("probe", "sync", json!({"cursors":{}})).await?;
    let report = json!({"ok":true,"testedAt":storage::now(),"runtimeSources":data.iter().map(|r|r["source"].clone()).collect::<Vec<_>>(),"nativeThreadIdPreserved":true,"snapshotWatermark":true,"historyQueryCompleted":list["data"].is_array(),"syncSnapshotCount":sync["snapshotRequired"].as_array().map(Vec::len),"accountLoginRequired":false});
    std::fs::write(
        root.join("docs/broker-native-probe.json"),
        serde_json::to_vec_pretty(&report)?,
    )?;
    println!("{}", serde_json::to_string_pretty(&report)?);
    discovery.abort();
    events.abort();
    Ok(())
}
