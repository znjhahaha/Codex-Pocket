use anyhow::{Context, Result, bail};
use pocket_runtime_host::client::{Discovery, HostClient};
use pocket_runtime_host::{HostConfig, run};
use std::path::PathBuf;

#[tokio::main]
async fn main() -> std::process::ExitCode {
    match start().await {
        Ok(()) => std::process::ExitCode::SUCCESS,
        Err(_) => {
            eprintln!("Pocket runtime host failed; inspect local host state");
            std::process::ExitCode::FAILURE
        }
    }
}
async fn start() -> Result<()> {
    let mut args = std::env::args().skip(1);
    let mut values = std::collections::BTreeMap::new();
    while let Some(key) = args.next() {
        if !["--state", "--descriptor", "--binary", "--config-home"].contains(&key.as_str())
            || values.contains_key(&key)
        {
            bail!("Invalid argument");
        }
        values.insert(key, args.next().context("Missing argument")?);
    }
    let path = |name: &str| {
        values
            .get(name)
            .map(PathBuf::from)
            .context("Missing required path")
    };
    let config = HostConfig {
        state: path("--state")?,
        descriptor: path("--descriptor")?,
        binary: path("--binary")?,
        config_home: path("--config-home")?,
        native_args: vec!["app-server".into(), "--listen".into(), "stdio://".into()],
    };
    if !config.state.join("startup.json").try_exists()? {
        bail!("Managed launcher startup ownership is required");
    }
    if config.descriptor.try_exists()? {
        match HostClient::discover(&config.descriptor, &config.config_home).await? {
            Discovery::Attached(_) => return Ok(()),
            Discovery::Missing | Discovery::Unavailable { .. } => {}
        }
    }
    // run rechecks the exclusive lock and verifies the old native identity ended.
    run(config).await
}
