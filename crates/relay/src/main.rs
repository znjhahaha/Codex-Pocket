use pocket_relay::{RelayConfig, RelayState, router};
use std::{io::Read, path::PathBuf, process::ExitCode};

#[tokio::main(worker_threads = 2)]
async fn main() -> ExitCode {
    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("{message}");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> Result<(), &'static str> {
    let mut args = std::env::args_os().skip(1);
    let path = args
        .next()
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("POCKET_RELAY_CONFIG").map(PathBuf::from))
        .ok_or("relay configuration file required")?;
    if args.next().is_some() {
        return Err("expected only one configuration file argument");
    }
    let file = std::fs::File::open(path).map_err(|_| "cannot open relay configuration")?;
    let mut bytes = Vec::new();
    file.take(65_537)
        .read_to_end(&mut bytes)
        .map_err(|_| "cannot read relay configuration")?;
    if bytes.len() > 65_536 {
        return Err("relay configuration too large");
    }
    let config: RelayConfig =
        serde_json::from_slice(&bytes).map_err(|_| "invalid relay configuration JSON")?;
    let bind = config.bind;
    let state = RelayState::new(config).map_err(|_| "invalid relay configuration")?;
    let listener = tokio::net::TcpListener::bind(bind)
        .await
        .map_err(|_| "cannot bind relay listener")?;
    let shutdown_state = state.clone();
    axum::serve(listener, router(state))
        .with_graceful_shutdown(async move {
            shutdown_signal().await;
            shutdown_state.shutdown();
        })
        .await
        .map_err(|_| "relay listener failed")
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{SignalKind, signal};
        if let Ok(mut terminate) = signal(SignalKind::terminate()) {
            tokio::select! { _=tokio::signal::ctrl_c()=>{},_=terminate.recv()=>{} }
            return;
        }
    }
    let _ = tokio::signal::ctrl_c().await;
}
