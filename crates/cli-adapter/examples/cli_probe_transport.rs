//! Small stdio harness for scripts/cli-probe.ps1. The daemon is never spawned.
use codex_cli_adapter::{Discovery, Limits, SOURCE_VERSION, connect};
use serde_json::{Value, json};
use std::io::{self, BufRead, Write};
use std::sync::mpsc;
use std::time::Duration;

fn emit(message: &Value) -> io::Result<()> {
    let mut out = io::stdout().lock();
    serde_json::to_writer(&mut out, message)?;
    out.write_all(b"\n")?;
    out.flush()
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let discovery = Discovery::from_environment()?.ok_or("native config store absent")?;
    let mut connection = connect(&discovery, Limits::default()).await?;
    let request_id = "cli-adapter-transport-initialize";
    connection
        .write(&json!({
            "id":request_id,"method":"initialize",
            "params":{"clientInfo":{"name":"cli-adapter-probe","version":"0.1.0"},
                      "capabilities":{"experimentalApi":true}}
        }))
        .await?;
    let initialized = tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            let message = connection
                .read()
                .await?
                .ok_or_else(|| io::Error::from(io::ErrorKind::UnexpectedEof))?;
            if message.get("id").and_then(Value::as_str) == Some(request_id) {
                return message
                    .get("result")
                    .cloned()
                    .ok_or_else(|| io::Error::other("initialize rejected"));
            }
        }
    })
    .await??;
    let identity = discovery.verify_initialize_result(&initialized)?;
    connection.write(&json!({"method":"initialized"})).await?;
    emit(&json!({"probeMetadata":{
        "sourceVersion":SOURCE_VERSION,
        "serverVersion":identity.version,
        "codexHome":discovery.codex_home(),
        "socketPath":discovery.socket_path(),
        "initializeResult":initialized
    }}))?;

    let (sender, receiver) = mpsc::sync_channel(16);
    std::thread::spawn(move || {
        for line in io::stdin().lock().lines() {
            if sender.send(line).is_err() {
                break;
            }
        }
    });
    let mut tick = tokio::time::interval(Duration::from_millis(10));
    loop {
        tokio::select! {
            message = connection.read() => match message? {
                Some(message) => emit(&message)?,
                None => break,
            },
            _ = tick.tick() => match receiver.try_recv() {
                Ok(line) => connection.write(&serde_json::from_str::<Value>(&line?)?).await?,
                Err(mpsc::TryRecvError::Empty) => {},
                Err(mpsc::TryRecvError::Disconnected) => break,
            }
        }
    }
    Ok(())
}
