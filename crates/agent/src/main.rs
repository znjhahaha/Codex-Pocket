use anyhow::{Context, Result, bail};
use pocket_agent::{
    auth,
    broker::Broker,
    config::{
        Config,
        routes::{self, GatewayConfig, LanConfig, RoutePolicy, RoutePreferences},
    },
    local, session, storage,
};
use pocket_tunnel::ServerIdentity;
use serde_json::{Value, json};
use std::{collections::BTreeMap, path::PathBuf, sync::Arc};

struct Args {
    command: String,
    options: BTreeMap<String, String>,
    json: bool,
}
impl Args {
    fn parse(args: &[String]) -> Result<Self> {
        let original = args.first().map(String::as_str).unwrap_or("help");
        let command = if original == "routes" {
            match args.get(1).map(String::as_str) {
                Some("read") => "routes/read",
                Some("configure") => "routes/configure",
                _ => bail!("Invalid routes action"),
            }
        } else if original == "session" {
            match args.get(1).map(String::as_str) {
                Some("preferences") => "session/preferences/read",
                Some("configure") => "session/preferences/update",
                _ => bail!("Invalid session action"),
            }
        } else {
            original
        };
        let allowed: &[&str] = match command {
            "status" | "devices" | "pair" | "run" | "prepare-state" => &["--state"],
            "revoke" => &["--state", "--device-id"],
            "init" => &[
                "--state",
                "--relay",
                "--runtime-dir",
                "--lan-bind",
                "--gateway-bind",
                "--policy",
            ],
            "routes/read" => &["--state"],
            "routes/configure" => &["--state", "--config"],
            "session/preferences/read" => &["--state"],
            "session/preferences/update" => &["--state", "--config"],
            "help" | "--help" | "-h" => &[],
            _ => bail!("Unknown command"),
        };
        let mut options = BTreeMap::new();
        let mut json = false;
        let mut index = if matches!(original, "routes" | "session") {
            2
        } else {
            1
        };
        while index < args.len() {
            let key = &args[index];
            if key == "--json" {
                if json
                    || !matches!(
                        command,
                        "status"
                            | "devices"
                            | "revoke"
                            | "pair"
                            | "routes/read"
                            | "routes/configure"
                            | "init"
                            | "session/preferences/read"
                            | "session/preferences/update"
                    )
                {
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
        Err(e) => {
            if structured {
                if let Some(code) = pocket_agent::error::classification(&e) {
                    println!("{}", local::failure(code.as_str(), code.message()));
                } else {
                    println!(
                        "{}",
                        local::failure("unavailable", "Helper command could not be completed")
                    );
                }
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
            let mut c = Config::create(args.option("--relay").unwrap_or("").to_owned())?;
            let mut preferences = c.preferences();
            if let Some(bind) = args.option("--lan-bind") {
                preferences.lan = Some(LanConfig {
                    bind: bind.parse()?,
                    mdns: false,
                });
            }
            if let Some(bind) = args.option("--gateway-bind") {
                preferences.gateway = Some(GatewayConfig {
                    bind: bind.parse()?,
                });
            }
            if let Some(policy) = args.option("--policy") {
                preferences.policy = match policy {
                    "automatic" => RoutePolicy::Automatic,
                    "lan" => RoutePolicy::Lan,
                    "public" => RoutePolicy::Public,
                    _ => bail!("Invalid route policy"),
                };
            }
            preferences.validate(&c.room)?;
            c.route_preferences = Some(preferences);
            if let Some(dir) = args.option("--runtime-dir") {
                c.runtime_directories.push(dir.into());
            }
            ServerIdentity::load_or_create(state.join("identity"))?;
            c.save(&state)?;
            if !c.relay_url.is_empty() {
                let relay = json!({"bind":"0.0.0.0:8080","queue_capacity":8,"rooms":[{"id":c.room,"helper_token_sha256":auth::hash(&c.helper_token),"phone_token_sha256":auth::hash(&c.phone_token)}]});
                storage::atomic_write(
                    &state.join("relay-config.json"),
                    &serde_json::to_vec_pretty(&relay)?,
                )?;
            }
            if args.json {
                println!(
                    "{}",
                    json!({"initialized":true,"computerId":c.computer_id,"routes":c.routes()})
                );
            } else {
                println!("Initialized computer identity.");
            }
        }
        "status" | "devices" | "revoke" | "pair" => {
            let result = local::execute(&state, &args.command, args.option("--device-id")).await?;
            if args.json {
                println!("{result}");
            } else {
                print_human(&args.command, &result);
            }
        }
        "routes/read" | "routes/configure" => {
            let params = if args.command == "routes/configure" {
                let path = args
                    .option("--config")
                    .context("Missing route preferences file")?;
                if std::fs::metadata(path)?.len() > 256 * 1024 {
                    bail!("Route preferences exceed limit");
                }
                let preferences: RoutePreferences = serde_json::from_slice(&std::fs::read(path)?)?;
                json!({"routePreferences":preferences})
            } else {
                json!({})
            };
            let value = local::execute_with_params(&state, &args.command, None, params).await?;
            println!("{value}");
        }
        "session/preferences/read" | "session/preferences/update" => {
            let params = if args.command == "session/preferences/update" {
                let path = args
                    .option("--config")
                    .context("Missing preference request file")?;
                if std::fs::metadata(path)?.len() > 256 * 1024 {
                    bail!("Preference request exceeds limit");
                }
                serde_json::from_slice(&std::fs::read(path)?)?
            } else {
                json!({})
            };
            let value = local::execute_with_params(&state, &args.command, None, params).await?;
            println!("{value}");
        }
        "run" => {
            let _lock = local::try_lock(&state)?.context("Helper already running")?;
            let c = Config::load(&state)?;
            let identity = Arc::new(ServerIdentity::load_or_create(state.join("identity"))?);
            let (broker, events) = Broker::open(c.clone(), state.clone())?;
            let controller =
                routes::Controller::open(state.clone(), c.clone(), broker.connection.clone());
            let _control =
                local::Server::start_with_routes(&state, broker.clone(), controller.clone())
                    .await?;
            let mut tasks = tokio::task::JoinSet::new();
            tasks.spawn(broker.clone().discover());
            tasks.spawn(broker.clone().receive_events(events));
            let (tx, mut rx) = tokio::sync::mpsc::channel(8);
            let b = broker.clone();
            tasks.spawn(async move {
                let mut sessions=tokio::task::JoinSet::new();
                loop {tokio::select! {
                    incoming=rx.recv()=>{let Some(tls)=incoming else{break;};let b=b.clone();sessions.spawn(async move {let _=session::serve(tls,b).await;});},
                    Some(_)=sessions.join_next(),if !sessions.is_empty()=>{},
                }}
            });
            let runtime = routes::Runtime::start(controller, identity, tx);
            println!("Codex Pocket helper running. Provider credentials stay on this computer.");
            tokio::signal::ctrl_c().await?;
            runtime.shutdown().await;
            tasks.abort_all();
            broker.connection.stop_routes();
        }
        _ => println!(
            "Codex Pocket\n  init [--relay wss://server/pocket] [--lan-bind PRIVATE_IP:PORT] [--gateway-bind 127.0.0.1:PORT] [--runtime-dir PATH] [--state PATH]\n  run [--state PATH]\n  routes read [--json] [--state PATH]\n  routes configure --config JSON_PATH [--json] [--state PATH]\n  session preferences [--json] [--state PATH]\n  session configure --config JSON_PATH [--json] [--state PATH]\n  pair [--json] [--state PATH]\n  status [--json] [--state PATH]\n  devices [--json] [--state PATH]\n  revoke --device-id ID [--json] [--state PATH]"
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
