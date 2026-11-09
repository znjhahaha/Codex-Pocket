//! Current-user local CLI control. The daemon lock serializes offline changes;
//! an authenticated loopback request serializes changes through a live broker.
use crate::{
    auth,
    broker::Broker,
    config::{
        Config,
        routes::{Controller, RoutePreferences},
    },
    connection, storage,
};
use anyhow::{Context, Result, bail};
use serde_json::{Value, json};
use std::{
    fs::{File, OpenOptions},
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};

pub fn try_lock(state: &Path) -> Result<Option<File>> {
    storage::private_directory(state)?;
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(state.join("agent.lock"))?;
    match fs2::FileExt::try_lock_exclusive(&file) {
        Ok(()) => Ok(Some(file)),
        Err(e)
            if e.kind() == std::io::ErrorKind::WouldBlock
                || e.raw_os_error() == fs2::lock_contended_error().raw_os_error() =>
        {
            Ok(None)
        }
        Err(e) => Err(e.into()),
    }
}
pub struct Server {
    task: tokio::task::JoinHandle<()>,
    path: PathBuf,
    token: String,
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
        let current = std::fs::read(&self.path)
            .ok()
            .and_then(|v| serde_json::from_slice::<Value>(&v).ok());
        if current.is_some_and(|v| v["token"] == self.token) {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}
impl Server {
    pub async fn start(state: &Path, broker: Arc<Broker>) -> Result<Self> {
        let routes = Controller::open(
            state.into(),
            broker.config.clone(),
            broker.connection.clone(),
        );
        Self::start_with_routes(state, broker, routes).await
    }
    pub async fn start_with_routes(
        state: &Path,
        broker: Arc<Broker>,
        routes: Arc<Controller>,
    ) -> Result<Self> {
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let token = storage::secret();
        let path = state.join("control.json");
        storage::atomic_write(
            &path,
            &serde_json::to_vec(
                &json!({"protocol":1,"port":listener.local_addr()?.port(),"token":token}),
            )?,
        )?;
        let expected = token.clone();
        let task = tokio::spawn(async move {
            let permits = Arc::new(tokio::sync::Semaphore::new(16));
            let mut workers = tokio::task::JoinSet::new();
            loop {
                let incoming = tokio::select! {
                    incoming=listener.accept()=>incoming,
                    Some(_)=workers.join_next(),if !workers.is_empty()=>continue,
                };
                let Ok((mut stream, _)) = incoming else {
                    break;
                };
                let Ok(permit) = permits.clone().try_acquire_owned() else {
                    continue;
                };
                let expected = expected.clone();
                let broker = broker.clone();
                let routes = routes.clone();
                workers.spawn(async move {
                    let _permit = permit;
                    let _ = tokio::time::timeout(Duration::from_secs(20), async {
                        let request = read(&mut stream).await?;
                        use subtle::ConstantTimeEq;
                        if !bool::from(
                            request["token"]
                                .as_str()
                                .unwrap_or("")
                                .as_bytes()
                                .ct_eq(expected.as_bytes()),
                        ) {
                            bail!("Local authentication required");
                        }
                        let result = match request["method"].as_str() {
                            Some("status") => {
                                let mut v = broker.diagnostics().await;
                                let r = routes.read().await;
                                for key in [
                                    "computerId",
                                    "policy",
                                    "routes",
                                    "routeStatus",
                                    "gatewayEndpoint",
                                    "initialized",
                                ] {
                                    if let Some(value) = r.get(key) {
                                        v[key] = value.clone();
                                    }
                                }
                                Ok(v)
                            }
                            Some("routes/read") => Ok(routes.read().await),
                            Some(
                                method @ ("session/preferences"
                                | "session/preferences/read"
                                | "session/preferences/update"),
                            ) => preferences(&broker, method, &request["params"]).await,
                            Some("routes/configure") => {
                                match serde_json::from_value::<RoutePreferences>(
                                    request["params"]["routePreferences"].clone(),
                                ) {
                                    Ok(p) => routes.configure(p).await,
                                    Err(_) => Err(anyhow::anyhow!("Invalid route preferences")),
                                }
                            }
                            Some("devices") => Ok(broker.devices().await),
                            Some("pair") => broker.local_pair().await,
                            Some("revoke") => {
                                match request["deviceId"].as_str().filter(|id| valid_device(id)) {
                                    Some(id) => broker.local_revoke(id).await,
                                    None => Err(anyhow::anyhow!("Invalid device ID")),
                                }
                            }
                            _ => Err(anyhow::anyhow!("Unsupported local command")),
                        };
                        let reply = match result {
                            Ok(v) => json!({"result":v}),
                            Err(e) => {
                                let code = crate::error::classification(&e)
                                    .unwrap_or(crate::error::Code::Rejected);
                                failure(code.as_str(), code.message())
                            }
                        };
                        write(&mut stream, &reply).await
                    })
                    .await;
                });
            }
        });
        Ok(Self { task, path, token })
    }
}
fn valid_device(id: &str) -> bool {
    !id.is_empty() && id.len() <= 128 && !id.chars().any(char::is_control)
}
/// Desktop authority comes from the local token/daemon lock, not a synthetic phone device.
async fn preferences(broker: &Broker, method: &str, params: &Value) -> Result<Value> {
    use crate::error::{Code, classify};
    let invalid = || {
        classify(
            anyhow::anyhow!("Invalid session preferences"),
            Code::InvalidParams,
        )
    };
    let object = params.as_object().ok_or_else(invalid)?;
    if method == "session/preferences/update" {
        if object
            .keys()
            .any(|key| !matches!(key.as_str(), "mode" | "knownRevision" | "commandId"))
            || params["knownRevision"].as_u64().is_none()
            || object.get("commandId").is_some_and(|value| {
                value.as_str().is_none_or(|s| {
                    s.is_empty() || s.len() > 128 || s.chars().any(char::is_control)
                })
            })
        {
            return Err(invalid());
        }
        broker.update_access_preferences(params).await
    } else {
        if !object.is_empty() {
            return Err(invalid());
        }
        Ok(broker.access_preferences().await)
    }
}
pub fn failure(code: &str, message: &str) -> Value {
    json!({"error":{"code":code,"message":message}})
}
pub fn stopped() -> Value {
    json!({"protocol":1,"state":"stopped","connected":false,"updatedAt":connection::now(),"lastActivityAt":null,"runtimes":[],"discoveryErrors":[]})
}
async fn read(stream: &mut TcpStream) -> Result<Value> {
    let len = stream.read_u32().await? as usize;
    if len > 256 * 1024 {
        bail!("Local message exceeds limit");
    }
    let mut bytes = vec![0; len];
    stream.read_exact(&mut bytes).await?;
    Ok(serde_json::from_slice(&bytes)?)
}
async fn write(stream: &mut TcpStream, value: &Value) -> Result<()> {
    let bytes = serde_json::to_vec(value)?;
    if bytes.len() > 256 * 1024 {
        bail!("Local message exceeds limit");
    }
    stream.write_u32(bytes.len() as u32).await?;
    stream.write_all(&bytes).await?;
    Ok(())
}
pub async fn execute(state: &Path, method: &str, device: Option<&str>) -> Result<Value> {
    execute_with_params(state, method, device, json!({})).await
}
pub async fn execute_with_params(
    state: &Path,
    method: &str,
    device: Option<&str>,
    params: Value,
) -> Result<Value> {
    if method == "revoke" && device.is_none_or(|id| !valid_device(id)) {
        bail!("Invalid device ID");
    }
    if let Some(_lock) = try_lock(state)? {
        return match method {
            "status" => {
                let mut v = stopped();
                if let Ok(c) = Config::load(state) {
                    for (key, value) in c.route_snapshot().as_object().unwrap() {
                        v[key] = value.clone();
                    }
                    v["initialized"] = json!(true);
                } else {
                    v["initialized"] = json!(false);
                }
                Ok(v)
            }
            "routes/read" | "routes/configure" => {
                let c = Config::load(state)?;
                let health = Arc::new(connection::Connection::default());
                health.set("stopped");
                let routes = Controller::open(state.into(), c, health);
                if method == "routes/configure" {
                    routes
                        .configure(serde_json::from_value(params["routePreferences"].clone())?)
                        .await
                } else {
                    Ok(routes.read().await)
                }
            }
            "session/preferences" | "session/preferences/read" | "session/preferences/update" => {
                let (broker, _) = Broker::open(Config::load(state)?, state.into())?;
                preferences(&broker, method, &params).await
            }
            "pair" => pair_files(state),
            "devices" | "revoke" => {
                let file = state.join("devices.json");
                let mut devices: auth::DeviceStore = if file.exists() {
                    serde_json::from_slice(&std::fs::read(&file)?)?
                } else {
                    Default::default()
                };
                if method == "devices" {
                    Ok(
                        json!({"data":devices.devices.values().map(|d|json!({"id":d.id,"name":d.name,"createdAt":d.created_at})).collect::<Vec<_>>()}),
                    )
                } else {
                    let id = device.unwrap();
                    let revoked = devices.revoke(id);
                    storage::atomic_write(&file, &serde_json::to_vec(&devices)?)?;
                    let push = crate::push::PushDelivery::open(state.into())?;
                    let _ = push.unregister(id).await;
                    Ok(json!({"revoked":revoked,"deviceId":id}))
                }
            }
            _ => bail!("Unsupported local command"),
        };
    }
    let result = tokio::time::timeout(Duration::from_secs(22), async {
        let file = state.join("control.json");
        if std::fs::metadata(&file)?.len() > 4096 {
            bail!("Invalid local endpoint");
        }
        let endpoint: Value = serde_json::from_slice(&std::fs::read(file)?)?;
        let port = endpoint["port"]
            .as_u64()
            .filter(|p| *p > 0 && *p <= 65535)
            .context("Invalid local endpoint")?;
        let token = endpoint["token"]
            .as_str()
            .filter(|s| s.len() == 64)
            .context("Invalid local endpoint")?;
        if endpoint["protocol"] != 1 {
            bail!("Invalid local protocol");
        }
        let mut stream = TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port as u16)).await?;
        write(
            &mut stream,
            &json!({"token":token,"method":method,"deviceId":device,"params":params}),
        )
        .await?;
        let reply = read(&mut stream).await?;
        if reply.get("error").is_some() {
            use crate::error::{Code, classify};
            let code = match reply["error"]["code"].as_str() {
                Some("invalid_params") => Code::InvalidParams,
                Some("version_conflict") => Code::VersionConflict,
                Some("unauthorized") => Code::Unauthorized,
                _ => Code::Rejected,
            };
            return Err(classify(anyhow::anyhow!("Local command rejected"), code));
        }
        reply.get("result").cloned().context("Invalid local reply")
    })
    .await;
    match result {
        Ok(Ok(v)) => Ok(v),
        Ok(Err(e)) if crate::error::classification(&e).is_some() => Err(e),
        _ if method == "status" => {
            let mut v = stopped();
            v["state"] = json!("reconnecting");
            v["discoveryErrors"] = json!([{"source":"helper","code":"control_unavailable"}]);
            Ok(v)
        }
        _ => bail!("Live helper control unavailable; no offline changes performed"),
    }
}

pub fn pair_files(state: &Path) -> Result<Value> {
    let state = dunce::canonicalize(state)?;
    let c = Config::load(&state)?;
    let identity = pocket_tunnel::ServerIdentity::load_or_create(state.join("identity"))?;
    let token = storage::secret();
    let expires = storage::now() + 300;
    let qr = json!({"v":1,"relayUrl":c.relay_url,"room":c.room,"phoneToken":c.phone_token,"certificateSha256":identity.certificate_sha256(),"pairingToken":token,"expiresAt":expires,"computerName":c.computer_name,"computerId":c.computer_id,"routes":c.routes(),"policy":c.preferences().policy});
    let code = qrcode::QrCode::new(serde_json::to_vec(&qr)?)?;
    let svg = code
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(640, 640)
        .build();
    let side = code.width();
    let scale = 8;
    let width = (side + 8) * scale;
    let mut pixels = vec![255u8; width * width];
    for y in 0..side {
        for x in 0..side {
            if code[(x, y)] == qrcode::Color::Dark {
                for dy in 0..scale {
                    for dx in 0..scale {
                        pixels[((y + 4) * scale + dy) * width + (x + 4) * scale + dx] = 0;
                    }
                }
            }
        }
    }
    let mut image = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut image, width as u32, width as u32);
        encoder.set_color(png::ColorType::Grayscale);
        encoder.set_depth(png::BitDepth::Eight);
        encoder.write_header()?.write_image_data(&pixels)?;
    }
    // Publish the invitation last, after every private display artifact is durable.
    storage::atomic_write(
        &state.join("pairing-qr.json"),
        &serde_json::to_vec_pretty(&qr)?,
    )?;
    storage::atomic_write(&state.join("pairing-qr.svg"), svg.as_bytes())?;
    storage::atomic_write(&state.join("pairing-qr.png"), &image)?;
    storage::atomic_write(
        &state.join("pairing.json"),
        &serde_json::to_vec(&json!({"tokenHash":auth::hash(&token),"expiresAt":expires}))?,
    )?;
    Ok(
        json!({"expiresAt":expires,"qrPath":state.join("pairing-qr.svg"),"pngPath":state.join("pairing-qr.png"),"payloadPath":state.join("pairing-qr.json")}),
    )
}
