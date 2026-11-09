//! Shared v0.6 route contract. URLs are complete phone endpoints, never credentials.
use crate::{config::Config, connection::Connection, transport};
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::BTreeSet,
    net::{IpAddr, SocketAddr},
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tokio::{
    sync::{Mutex, mpsc, watch},
    task::JoinSet,
};
#[path = "gateway.rs"]
pub mod gateway;
#[path = "lan.rs"]
pub mod lan;

#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum RoutePolicy {
    #[default]
    Automatic,
    Lan,
    Public,
}
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum RouteKind {
    Lan,
    Cloudflare,
    Relay,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Route {
    pub id: String,
    pub kind: RouteKind,
    pub url: String,
    pub priority: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expires_at: Option<u64>,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LanConfig {
    pub bind: SocketAddr,
    #[serde(default)]
    pub mdns: bool,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GatewayConfig {
    pub bind: SocketAddr,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RoutePreferences {
    #[serde(default)]
    pub auto_lan: bool,
    #[serde(default)]
    pub routes: Vec<Route>,
    #[serde(default)]
    pub policy: RoutePolicy,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lan: Option<LanConfig>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gateway: Option<GatewayConfig>,
}
pub fn private_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => ip.is_private() || ip.is_loopback(),
        IpAddr::V6(ip) => ip.is_loopback() || ip.segments()[0] & 0xfe00 == 0xfc00,
    }
}
pub fn default_lan_bind() -> Option<SocketAddr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.0.2.1:9").ok()?;
    let ip = socket.local_addr().ok()?.ip();
    (private_ip(ip) && !ip.is_loopback()).then(|| SocketAddr::new(ip, 43821))
}
pub fn validate_public_url(value: &str) -> Result<url::Url> {
    let url = url::Url::parse(value)?;
    if value.len() > 2048
        || url.scheme() != "wss"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || value.chars().any(char::is_control)
    {
        bail!("Public route requires credential-free WSS URL");
    }
    Ok(url)
}
pub fn phone_url(base: &str, room: &str) -> Result<String> {
    let mut url = validate_public_url(base)?;
    url.path_segments_mut()
        .map_err(|_| anyhow::anyhow!("Invalid route URL"))?
        .pop_if_empty()
        .extend(["v1", "tunnel", room, "phone"]);
    Ok(url.into())
}
impl RoutePreferences {
    pub fn validate(&self, room: &str) -> Result<()> {
        if room.is_empty()
            || room.len() > 64
            || !room
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        {
            bail!("Invalid room");
        }
        if self.routes.len() > 32 {
            bail!("Too many routes");
        }
        let mut ids = BTreeSet::new();
        for route in &self.routes {
            if route.id.is_empty()
                || route.id.len() > 64
                || !route
                    .id
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
                || !ids.insert(&route.id)
                || matches!(route.id.as_str(), "gateway" | "mdns")
                || (route.id == "lan" && route.kind != RouteKind::Lan)
            {
                bail!("Invalid or duplicate route ID");
            }
            if route.kind == RouteKind::Lan {
                let url = url::Url::parse(&route.url)?;
                // `tls` is a non-special URL scheme: WHATWG can classify IPv4 as Domain.
                // Parse the literal explicitly instead of resolving any hostname.
                let ip: IpAddr = url
                    .host_str()
                    .unwrap_or("")
                    .trim_matches(['[', ']'])
                    .parse()
                    .map_err(|_| anyhow::anyhow!("LAN route requires a private literal IP"))?;
                if route.url.len() > 256
                    || url.scheme() != "tls"
                    || !private_ip(ip)
                    || url.port().is_none_or(|p| p == 0)
                    || !matches!(url.path(), "" | "/")
                    || !url.username().is_empty()
                    || url.password().is_some()
                    || url.query().is_some()
                    || url.fragment().is_some()
                {
                    bail!("Invalid LAN route");
                }
            } else {
                let url = validate_public_url(&route.url)?;
                if !url.path().ends_with(&format!("/v1/tunnel/{room}/phone")) {
                    bail!("Public route must be a complete phone endpoint");
                }
            }
        }
        if self.lan.as_ref().is_some_and(|c| !private_ip(c.bind.ip())) {
            bail!("LAN bind must be a specific private address");
        }
        if self
            .gateway
            .as_ref()
            .is_some_and(|c| !c.bind.ip().is_loopback())
        {
            bail!("Gateway must bind loopback");
        }
        Ok(())
    }
    pub fn advertised_routes(&self, now: u64) -> Vec<Route> {
        let mut routes: Vec<_> = self
            .routes
            .iter()
            .filter(|r| r.expires_at.is_none_or(|expiry| expiry > now))
            .cloned()
            .map(|mut route|{if route.kind==RouteKind::Lan{route.url=route.url.trim_end_matches('/').to_owned();}route})
            .collect();
        if let Some(lan) = &self.lan {
            let endpoint=format!("tls://{}",lan.bind);
            if lan.bind.port() != 0 && !routes.iter().any(|r| r.kind == RouteKind::Lan && r.url.trim_end_matches('/')==endpoint) {
                let mut id="lan".to_owned();
                while routes.iter().any(|r|r.id==id){id.push_str("-local");}
                routes.push(Route {
                    id,
                    kind: RouteKind::Lan,
                    url: format!("tls://{}", lan.bind),
                    priority: 0,
                    expires_at: None,
                });
            }
        }
        routes.sort_by(|a, b| a.priority.cmp(&b.priority).then(a.id.cmp(&b.id)));
        routes
    }
}
/// Parent broker uses this for routes/list; reads persisted changes despite stale Broker.config.
pub fn snapshot(config: &Config, state_dir: &Path) -> Value {
    Config::load(state_dir)
        .unwrap_or_else(|_| config.clone())
        .route_snapshot()
}

pub struct Controller {
    state: PathBuf,
    config: Mutex<Config>,
    changes: watch::Sender<(Config,u64)>,
    pub health: Arc<Connection>,
}
impl Controller {
    pub fn open(state: PathBuf, config: Config, health: Arc<Connection>) -> Arc<Self> {
        let generation=health.reset_routes(&config.routes());
        let (changes, _) = watch::channel((config.clone(),generation));
        Arc::new(Self {
            state,
            config: Mutex::new(config),
            changes,
            health,
        })
    }
    pub async fn read(&self) -> Value {
        let config = self.config.lock().await;
        let mut v = config.route_snapshot();
        v["routePreferences"] = json!(config.preferences());
        v["routeStatus"] = self.health.route_snapshot();
        v["initialized"] = json!(true);
        let evidence = self.health.snapshot();
        for key in [
            "available",
            "connected",
            "outerConnected",
            "authenticatedPhone",
        ] {
            v[key] = evidence[key].clone();
        }
        if let Some(endpoint) = self.health.gateway_endpoint() {
            v["gatewayEndpoint"] = json!(endpoint);
        }
        v
    }
    pub async fn configure(&self, preferences: RoutePreferences) -> Result<Value> {
        let mut config = self.config.lock().await;
        preferences.validate(&config.room)?;
        let mut next = Config::load(&self.state)?;
        if config.preferences()==preferences&&next.preferences()==preferences {
            drop(config);
            return Ok(self.read().await);
        }
        next.route_preferences = Some(preferences);
        next.save(&self.state)?;
        *config = next.clone();
        // The reply exposes pending application; an old listener cannot prove new readiness.
        let generation=self.health.reset_routes(&next.routes());
        self.changes.send_replace((next,generation));
        drop(config);
        Ok(self.read().await)
    }
    /// Network probing supplies only a private address; identity and public routes
    /// come from the current on-disk configuration, never from the probe.
    pub async fn refresh_auto_lan(&self,discovered:Option<SocketAddr>)->Result<bool>{
        let Some(discovered)=discovered.filter(|a|private_ip(a.ip())&&!a.ip().is_loopback()) else{return Ok(false)};
        let mut config=self.config.lock().await;
        let mut next=Config::load(&self.state)?;
        let mut prefs=next.preferences();
        if !prefs.auto_lan{return Ok(false)}
        let port=prefs.lan.as_ref().map(|c|c.bind.port()).filter(|p|*p>0).unwrap_or(43821);
        let lan=LanConfig{bind:SocketAddr::new(discovered.ip(),port),mdns:prefs.lan.as_ref().is_none_or(|c|c.mdns)};
        if prefs.lan.as_ref()==Some(&lan){return Ok(false)}
        prefs.lan=Some(lan);prefs.validate(&next.room)?;
        next.route_preferences=Some(prefs);next.save(&self.state)?;
        *config=next.clone();
        let generation=self.health.reset_routes(&next.routes());
        self.changes.send_replace((next,generation));
        Ok(true)
    }
    pub fn subscribe(&self) -> watch::Receiver<(Config,u64)> {
        self.changes.subscribe()
    }
    pub async fn refresh_expired(&self,expected_generation:u64){
        let config=self.config.lock().await;
        if self.changes.borrow().1!=expected_generation{return}
        let generation=self.health.reset_routes(&config.routes());
        self.changes.send_replace((config.clone(),generation));
    }
}

/// Cancelling this owner closes listeners, proxy workers, and outbound relay channels.
pub struct Runtime {
    task: tokio::task::JoinHandle<()>,
    network_task: tokio::task::JoinHandle<()>,
    health: Arc<Connection>,
}
impl Drop for Runtime {
    fn drop(&mut self) {
        self.task.abort();
        self.network_task.abort();
        self.health.stop_routes();
    }
}
impl Runtime {
    pub fn start(
        controller: Arc<Controller>,
        identity: Arc<pocket_tunnel::ServerIdentity>,
        sessions: mpsc::Sender<transport::TlsSession>,
    ) -> Self {
        let health = controller.health.clone();
        let network=controller.clone();
        let network_task=tokio::spawn(async move {
            let mut interval=tokio::time::interval(Duration::from_secs(10));
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {interval.tick().await;let _=network.refresh_auto_lan(default_lan_bind()).await;}
        });
        let task = tokio::spawn(async move {
            let mut changes = controller.subscribe();
            loop {
                let (config,generation) = changes.borrow_and_update().clone();
                let prefs = config.preferences();
                controller.health.set("idle");
                let mut workers = JoinSet::new();
                let mut lan = None;
                if let Some(settings) = &prefs.lan {
                    match lan::Server::start_at(
                        settings.clone(),
                        identity.clone(),
                        sessions.clone(),
                        controller.health.clone(),
                        config.computer_id.clone(),
                        generation,
                    )
                    .await
                    {
                        Ok(server) => {
                            let endpoint = format!("tls://{}", server.local_addr());
                            for r in config.routes().into_iter().filter(|r| {
                                r.kind == RouteKind::Lan && r.url.trim_end_matches('/') == endpoint
                            }) {
                                controller.health.route_phase_at(
                                    generation,
                                    &r.id,
                                    "listening",
                                    true,
                                    Some(endpoint.clone()),
                                );
                            }
                            lan = Some(server);
                        }
                        Err(_) => controller.health.route_phase_at(generation,"lan", "failed", false, None),
                    }
                }
                let mut gateway = None;
                if let Some(settings) = &prefs.gateway {
                    match gateway::Server::start_at(
                        settings.clone(),
                        &config,
                        identity.clone(),
                        sessions.clone(),
                        controller.health.clone(),
                        generation,
                    )
                    .await
                    {
                        Ok(server) => gateway = Some(server),
                        Err(_) => controller
                            .health
                            .route_phase_at(generation,"gateway", "failed", false, None),
                    }
                }
                for route in config
                    .routes()
                    .into_iter()
                    .filter(|r| r.kind == RouteKind::Relay)
                {
                    let mut relay = config.clone();
                    let suffix = format!("/v1/tunnel/{}/phone", config.room);
                    relay.relay_url = route.url.strip_suffix(&suffix).unwrap_or("").to_owned();
                    let endpoint=route.url;
                    let id = route.id;
                    let identity = identity.clone();
                    let sessions = sessions.clone();
                    let health = controller.health.clone();
                    workers.spawn(async move {
                        let monitored = Arc::new(Connection::default());
                        loop {
                            health.route_phase_at(generation,&id, "connecting", false, Some(endpoint.clone()));
                            let (accepted,mut incoming)=mpsc::channel(8);
                            let run = transport::run_monitored(
                                relay.clone(),
                                identity.clone(),
                                accepted,
                                monitored.clone(),
                            );
                            tokio::pin!(run);
                            let mut tick = tokio::time::interval(Duration::from_millis(100));
                            loop {
                                tokio::select! {
                                    _=&mut run=>break,
                                    session=incoming.recv()=>{
                                        let Some(session)=session else{break;};
                                        if !health.inner_handshake_at(generation,&id){break;}
                                        if !matches!(tokio::time::timeout(Duration::from_secs(5),sessions.send(session)).await,Ok(Ok(()))){break;}
                                    },
                                    _=tick.tick()=>health.relay_evidence_at(generation,&id,&endpoint,&monitored.snapshot()),
                                }
                            }
                            health.route_phase_at(generation,&id, "reconnecting", false, Some(endpoint.clone()));
                            tokio::time::sleep(Duration::from_secs(1)).await;
                        }
                    });
                }
                // Receipt is retained until generation changes; dropping JoinSet aborts all channels.
                let next_expiry = prefs
                    .routes
                    .iter()
                    .filter_map(|r| r.expires_at)
                    .filter(|e| *e > crate::connection::now())
                    .min();
                let delay = next_expiry
                    .map(|e| Duration::from_secs(e.saturating_sub(crate::connection::now()).max(1)))
                    .unwrap_or(Duration::from_secs(86400));
                tokio::select! {
                    changed=changes.changed()=>{if changed.is_err(){break;}},
                    _=tokio::time::sleep(delay)=>{controller.refresh_expired(generation).await;},
                    _=sessions.closed()=>break,
                }
                drop(lan);
                drop(gateway);
                workers.abort_all();
            }
        });
        Self { task, network_task, health }
    }
    pub async fn shutdown(mut self) {
        self.task.abort();
        self.network_task.abort();
        let _ = (&mut self.task).await;
    }
}
