use anyhow::{Context, Result, bail};
use pocket_agent::{auth, broker::Broker, config::Config, local, session, storage, transport};
use pocket_tunnel::ServerIdentity;
use serde_json::{Value, json};
use std::{collections::BTreeMap, path::PathBuf, sync::Arc, time::Duration};

struct Args {
    command: String,
    options: BTreeMap<String, String>,
    json: bool,
}
impl Args {
    fn parse(args: &[String]) -> Result<Self> {
        let command = args.first().map(String::as_str).unwrap_or("help");
        let allowed: &[&str] = match command {
            "status" | "devices" | "pair" | "run" | "prepare-state" => &["--state"],
            "revoke" => &["--state", "--device-id"],
            "init" => &["--state", "--relay", "--runtime-dir"],
            "help" | "--help" | "-h" => &[],
            _ => bail!("Unknown command"),
        };
        let mut options = BTreeMap::new();
        let mut json = false;
        let mut index = 1;
        while index < args.len() {
            let key = &args[index];
            if key == "--json" {
                if json || !matches!(command, "status" | "devices" | "revoke" | "pair") {
                    bail!("Invalid flag");
                }
                json = true;
                index += 1;
                continue;
            }
            if !allowed.contains(&key.as_str()) || options.contains_key(key) {
                bail!("Invalid option");
            }
            let value = args
                .get(index + 1)
                .filter(|s| !s.is_empty() && !s.starts_with("--"))
                .context("Missing option value")?;
            options.insert(key.clone(), value.clone());
            index += 2;
        }
        if command == "revoke"
            && options
                .get("--device-id")
                .is_none_or(|s| s.is_empty() || s.len() > 128 || s.chars().any(char::is_control))
        {
            bail!("Invalid device ID");
        }
        Ok(Self {
            command: command.into(),
            options,
            json,
        })
    }
    fn option(&self, key: &str) -> Option<&str> {
        self.options.get(key).map(String::as_str)
    }
    fn state(&self) -> PathBuf {
        self.option("--state")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                std::env::var_os("LOCALAPPDATA")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| PathBuf::from("."))
                    .join("CodexPocket/agent")
            })
    }
}
#[tokio::main]
async fn main() -> std::process::ExitCode {
    let raw: Vec<_> = std::env::args().skip(1).collect();
    let structured = raw.iter().any(|a| a == "--json");
    let args = match Args::parse(&raw) {
        Ok(args) => args,
        Err(_) => {
            if structured {
                println!(
                    "{}",
                    local::failure("invalid_params", "Invalid command arguments")
                );
            } else {
                eprintln!("Invalid command arguments. Use help for usage.");
            }
            return std::process::ExitCode::FAILURE;
        }
    };
    match execute(args).await {
        Ok(()) => std::process::ExitCode::SUCCESS,
        Err(_) => {
            if structured {
                println!(
                    "{}",
                    local::failure("unavailable", "Helper command could not be completed")
                );
            } else {
                eprintln!(
                    "Helper command could not be completed. Check configuration and helper status."
                );
            }
            std::process::ExitCode::FAILURE
        }
    }
}
async fn execute(args: Args) -> Result<()> {
    let state = args.state();
    match args.command.as_str() {
        "prepare-state" => {
            storage::private_directory(&state)?;
            println!("Private helper state directory ready.");
        }
        "init" => {
            let _lock = local::try_lock(&state)?.context("Helper already running")?;
            if state.join("config.json").exists() {
                bail!("Already initialized");
            }
            let mut c =
                Config::create(args.option("--relay").context("Missing relay")?.to_owned())?;
            if let Some(dir) = args.option("--runtime-dir") {
                c.runtime_directories.push(dir.into());
            }
            ServerIdentity::load_or_create(state.join("identity"))?;
            c.save(&state)?;
            let relay = json!({"bind":"0.0.0.0:8080","queue_capacity":8,"rooms":[{"id":c.room,"helper_token_sha256":auth::hash(&c.helper_token),"phone_token_sha256":auth::hash(&c.phone_token)}]});
            storage::atomic_write(
                &state.join("relay-config.json"),
                &serde_json::to_vec_pretty(&relay)?,
            )?;
            println!(
                "Initialized. Deployment configuration: {}",
                state.join("relay-config.json").display()
            );
        }
        "status" | "devices" | "revoke" | "pair" => {
            let result = local::execute(&state, &args.command, args.option("--device-id")).await?;
            if args.json {
                println!("{result}");
            } else {
                print_human(&args.command, &result);
            }
        }
        "run" => {
            let _lock = local::try_lock(&state)?.context("Helper already running")?;
            let c = Config::load(&state)?;
            let identity = Arc::new(ServerIdentity::load_or_create(state.join("identity"))?);
            let (broker, events) = Broker::open(c.clone(), state.clone())?;
            let _control = local::Server::start(&state, broker.clone()).await?;
            tokio::spawn(broker.clone().discover());
            tokio::spawn(broker.clone().receive_events(events));
            let (tx, mut rx) = tokio::sync::mpsc::channel(8);
            let b = broker.clone();
            tokio::spawn(async move {
                while let Some(tls) = rx.recv().await {
                    let b = b.clone();
                    tokio::spawn(async move {
                        let _ = session::serve(tls, b).await;
                    });
                }
            });
            println!("Codex Pocket helper running. Provider credentials stay on this computer.");
            loop {
                let connection = transport::run_monitored(
                    c.clone(),
                    identity.clone(),
                    tx.clone(),
                    broker.connection.clone(),
                );
                tokio::select! {
                    _=tokio::signal::ctrl_c()=>{broker.connection.set("stopped");break;},
                    _=connection=>{}
                }
                broker.connection.set("reconnecting");
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        }
        _ => println!(
            "Codex Pocket\n  init --relay wss://server/pocket [--runtime-dir PATH] [--state PATH]\n  run [--state PATH]\n  pair [--json] [--state PATH]\n  status [--json] [--state PATH]\n  devices [--json] [--state PATH]\n  revoke --device-id ID [--json] [--state PATH]"
        ),
    }
    Ok(())
}
fn print_human(command: &str, value: &Value) {
    match command {
        "status" => println!(
            "Connection: {}\nConnected: {}",
            value["state"].as_str().unwrap_or("stopped"),
            value["connected"]
        ),
        "pair" => println!(
            "Scan within 5 minutes: {}",
            value["qrPath"].as_str().unwrap_or("")
        ),
        _ => println!("{value}"),
    }
}
