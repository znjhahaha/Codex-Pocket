//! Windows cold-start smoke test. Uses an empty, disposable profile and no model calls.
#[cfg(not(windows))]
fn main() {
    eprintln!("This probe requires Windows");
    std::process::exit(2);
}

#[cfg(windows)]
#[tokio::main]
async fn main() -> anyhow::Result<()> {
    use anyhow::{Context, ensure};
    use pocket_agent::{auth, broker::Broker, config::Config, storage};
    use serde_json::json;
    use std::{path::PathBuf, time::Duration};

    // The caller supplies a new, short workspace path so the Windows AF_UNIX
    // endpoint fits. Never run against an existing profile.
    let home = PathBuf::from(
        std::env::args_os()
            .nth(1)
            .context("New test home required")?,
    );
    ensure!(home.is_absolute(), "Test home must be absolute");
    codex_cli_adapter::validate_windows_socket_path(
        &home.join("app-server-control/app-server-control.sock"),
    )?;
    std::fs::create_dir(&home)?;
    let state = home.join("pocket-state");
    std::fs::create_dir(&state)?;
    let mut config = Config::create("wss://example.invalid/pocket".into())?;
    config.codex_homes = vec![home.clone()];
    config.runtime_directories = vec![home.join("pocket-runtimes")];
    let (broker, events) = Broker::open(config, state.clone())?;
    let _events = tokio::spawn(broker.clone().receive_events(events));
    let _discovery = tokio::spawn(broker.clone().discover());
    let token = storage::secret();
    storage::atomic_write(
        &state.join("pairing.json"),
        &serde_json::to_vec(
            &json!({"tokenHash":auth::hash(&token),"expiresAt":storage::now()+60}),
        )?,
    )?;
    broker
        .pair(&json!({"pairingToken":token,"deviceId":"probe","deviceName":"Cold-start fixture"}))
        .await?;
    let before = broker.hello().await;
    ensure!(
        before["runtimes"] == json!([]),
        "Runtime present before ensure"
    );
    ensure!(
        before["capabilities"]
            .as_array()
            .is_some_and(|v| v.contains(&json!("runtime.ensure.cli"))),
        "Missing CLI ensure capability"
    );
    let ensured = broker
        .request(
            "probe",
            "runtime/ensure",
            json!({"commandId":"ensure","source":"cli"}),
        )
        .await?;
    ensure!(
        ensured["phase"] == "starting_cli" && ensured["runtimeId"].is_null(),
        "Unexpected cold-start acknowledgement"
    );
    ensure!(
        ensured["configStoreId"] == storage::config_store(&home)?,
        "Wrong profile"
    );
    let runtime = tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            let response = broker.request("probe", "runtimes/list", json!({})).await?;
            if let Some(row) = response["data"].as_array().into_iter().flatten().find(|r| {
                r["configStoreId"] == ensured["configStoreId"]
                    && r["role"]["kind"] == "cliDaemon"
                    && r["online"] == true
                    && r["readiness"]["stage"] == "ready"
            }) {
                return Ok::<_, anyhow::Error>(row["id"].clone());
            }
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
    })
    .await??;
    let reused = broker
        .request(
            "probe",
            "runtime/ensure",
            json!({"commandId":"reuse","source":"cli"}),
        )
        .await?;
    ensure!(
        reused["phase"] == "ready" && reused["runtimeId"] == runtime,
        "Verified daemon was not reused"
    );
    let models = broker
        .request(
            "probe",
            "codex/request",
            json!({"runtimeId":runtime,"method":"model/list","params":{}}),
        )
        .await?;
    ensure!(
        models["data"]
            .as_array()
            .is_some_and(|rows| !rows.is_empty()),
        "Model list not available"
    );
    let started = broker.request("probe", "codex/request", json!({"commandId":"start","runtimeId":runtime,"method":"thread/start","params":{"cwd":home}})).await?;
    ensure!(
        started["thread"]["id"]
            .as_str()
            .is_some_and(|s| !s.is_empty()),
        "No native thread was created"
    );
    println!(
        "PASS: isolated cold launch, verified daemon reuse, model listing, and native thread creation"
    );
    Ok(())
}
