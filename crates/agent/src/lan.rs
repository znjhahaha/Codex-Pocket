//! Private LAN direct TLS. A bounded duplex proxy makes cancellation close delivered sessions.
use super::LanConfig;
use crate::{connection::{Connection, RouteFailure}, transport::TlsSession};
use anyhow::{Result, bail};
use pocket_tunnel::ServerIdentity;
use std::{
    net::{IpAddr, SocketAddr},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncWrite, AsyncWriteExt},
    net::TcpListener,
    sync::{Semaphore, mpsc},
    task::JoinSet,
};

pub struct Server {
    task: tokio::task::JoinHandle<()>,
    address: SocketAddr,
    health: Arc<Connection>,
    mdns: bool,
    generation: u64,
    route_id: String,
    discovery_id: String,
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
        self.health
            .route_phase_at(self.generation, &self.route_id, "stopped", false, None);
        if self.mdns {
            self.health
                .route_phase_at(self.generation, &self.discovery_id, "stopped", false, None);
        }
    }
}
impl Server {
    pub async fn start(
        settings: LanConfig,
        identity: Arc<ServerIdentity>,
        sessions: mpsc::Sender<TlsSession>,
        health: Arc<Connection>,
        computer_id: String,
    ) -> Result<Self> {
        let generation = health.generation();
        Self::start_at(
            settings,
            identity,
            sessions,
            health,
            computer_id,
            generation,
        )
        .await
    }
    pub async fn start_at(
        settings: LanConfig,
        identity: Arc<ServerIdentity>,
        sessions: mpsc::Sender<TlsSession>,
        health: Arc<Connection>,
        computer_id: String,
        generation: u64,
    ) -> Result<Self> {
        Self::start_named_at(settings, identity, sessions, health, computer_id, generation, "lan".into()).await
    }
    pub async fn start_named_at(
        settings: LanConfig, identity: Arc<ServerIdentity>, sessions: mpsc::Sender<TlsSession>,
        health: Arc<Connection>, computer_id: String, generation: u64, route_id: String,
    ) -> Result<Self> {
        if health.generation() != generation {
            bail!("LAN configuration changed");
        }
        if !super::private_ip(settings.bind.ip()) {
            bail!("LAN listener requires a specific private bind");
        }
        let listener = TcpListener::bind(settings.bind).await?;
        let address = listener.local_addr()?;
        let discovery_id=if route_id=="lan" {"mdns".to_owned()} else {format!("mdns-{route_id}")};
        // mDNS failure must be visible separately; it does not invalidate the TLS listener.
        let mdns = if settings.mdns {
            match Advertisement::start(address, &computer_id, health.clone(), generation, discovery_id.clone()) {
                Ok(a) => {
                    Some(a)
                }
                Err(e) => {
                    health.route_failure_at(generation, &discovery_id, e);
                    None
                }
            }
        } else {
            None
        };
        if health.generation() != generation {
            bail!("LAN configuration changed");
        }
        health.route_phase_at(
            generation,
            &route_id,
            "listening",
            true,
            Some(format!("tls://{address}")),
        );
        let observed = health.clone();
        let task_route=route_id.clone();
        let task_discovery=discovery_id.clone();
        let task = tokio::spawn(async move {
            let _mdns = mdns;
            let permits = Arc::new(Semaphore::new(8));
            let mut workers = JoinSet::new();
            loop {
                if observed.generation() != generation {
                    break;
                }
                tokio::select! {
                    _=sessions.closed()=>break,
                    Some(_)=workers.join_next(),if !workers.is_empty()=>{},
                    incoming=listener.accept()=>{
                        let Ok((stream,peer))=incoming else {observed.route_phase_at(generation,&task_route,"failed",false,None);break;};
                        if observed.generation()!=generation{break;}
                        if !super::private_ip(peer.ip()) {continue;}
                        let Ok(permit)=permits.clone().try_acquire_owned() else {continue;};
                        let identity=identity.clone();let sessions=sessions.clone();let health=observed.clone();
                        let id=task_route.clone();
                        workers.spawn(async move {let _permit=permit;let _=serve_stream_at(stream,identity,sessions,health,&id,generation).await;});
                    }
                }
            }
            observed.route_phase_at(generation, &task_route, "stopped", false, None);
            if settings.mdns {
                observed.route_phase_at(generation, &task_discovery, "stopped", false, None);
            }
        });
        Ok(Self {
            task,
            address,
            health,
            mdns: settings.mdns,
            generation,
            route_id,
            discovery_id,
        })
    }
    pub fn local_addr(&self) -> SocketAddr {
        self.address
    }
    pub async fn shutdown(mut self) {
        self.task.abort();
        let _ = (&mut self.task).await;
    }
}

pub(crate) struct SessionGuard {
    health: Arc<Connection>,
    id: String,
    generation: u64,
}
impl SessionGuard {
    pub(crate) fn new_at(
        health: Arc<Connection>,
        id: &str,
        generation: u64,
    ) -> Option<Self> {
        let generation = health.session_open_at(generation, id)?;
        Some(Self {
            health,
            id: id.to_owned(),
            generation,
        })
    }
}
impl Drop for SessionGuard {
    fn drop(&mut self) {
        self.health
            .session_close_generation(&self.id, self.generation);
    }
}

/// Shared TLS ingress; no authentication is bypassed, session::serve still requires pair/hello.
pub(crate) async fn serve_stream_at<S>(
    mut stream: S,
    identity: Arc<ServerIdentity>,
    sessions: mpsc::Sender<TlsSession>,
    health: Arc<Connection>,
    id: &str,
    generation: u64,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    if health.generation() != generation {
        bail!("Route configuration changed");
    }
    let (inner, mut proxy) = tokio::io::duplex(131072);
    let tls = async {
        let tls = identity.accept_tls(inner).await?;
        if !health.inner_handshake_at(generation, id) {
            bail!("Route configuration changed");
        }
        let _guard = SessionGuard::new_at(health.clone(), id, generation)
            .ok_or_else(|| anyhow::anyhow!("Route configuration changed"))?;
        // A full queue must not strand a ninth handshaken peer indefinitely.
        let permit = tokio::time::timeout(Duration::from_secs(5), sessions.reserve()).await??;
        if health.generation() != generation {
            bail!("Route configuration changed");
        }
        permit.send(tls);
        sessions.closed().await;
        Ok::<(), anyhow::Error>(())
    };
    let copying = async {
        tokio::io::copy_bidirectional(&mut stream, &mut proxy).await?;
        stream.shutdown().await?;
        Ok::<(), anyhow::Error>(())
    };
    tokio::pin!(tls);
    tokio::pin!(copying);
    tokio::select! {r=&mut tls=>r,r=&mut copying=>r}
}

const SERVICE: &str = "_codex-pocket._tcp.local";
/// Standard DNS-SD announcement data; only computerId, port and protocol are TXT data.
pub fn mdns_packet(computer_id: &str, address: SocketAddr, ttl: u32) -> Result<Vec<u8>> {
    if uuid::Uuid::parse_str(computer_id).is_err()
        || !super::private_ip(address.ip())
        || address.port() == 0
    {
        bail!("Invalid mDNS identity or address");
    }
    let host = format!("pocket-{computer_id}.local");
    let instance = format!("{computer_id}.{SERVICE}");
    let mut bytes = vec![0, 0, 0x84, 0, 0, 0, 0, 4, 0, 0, 0, 0];
    fn name(out: &mut Vec<u8>, name: &str) {
        for label in name.split('.') {
            out.push(label.len() as u8);
            out.extend(label.as_bytes());
        }
        out.push(0);
    }
    fn record(out: &mut Vec<u8>, owner: &str, kind: u16, ttl: u32, data: &[u8], flush: bool) {
        name(out, owner);
        out.extend(kind.to_be_bytes());
        out.extend(if flush { 0x8001u16 } else { 1 }.to_be_bytes());
        out.extend(ttl.to_be_bytes());
        out.extend((data.len() as u16).to_be_bytes());
        out.extend(data);
    }
    let mut ptr = Vec::new();
    name(&mut ptr, &instance);
    record(&mut bytes, SERVICE, 12, ttl, &ptr, false);
    let mut srv = vec![0, 0, 0, 0];
    srv.extend(address.port().to_be_bytes());
    name(&mut srv, &host);
    record(&mut bytes, &instance, 33, ttl, &srv, true);
    let mut txt = Vec::new();
    for property in [
        format!("computerId={computer_id}"),
        format!("port={}", address.port()),
        "protocol=1".into(),
    ] {
        txt.push(property.len() as u8);
        txt.extend(property.as_bytes());
    }
    record(&mut bytes, &instance, 16, ttl, &txt, true);
    match address.ip() {
        IpAddr::V4(ip) => record(&mut bytes, &host, 1, ttl, &ip.octets(), true),
        IpAddr::V6(ip) => record(&mut bytes, &host, 28, ttl, &ip.octets(), true),
    };
    Ok(bytes)
}
/// DNS-SD only contains public identity, protocol, port, and explicit interface addresses.
pub fn mdns_service(computer_id: &str, address: SocketAddr) -> Result<mdns_sd::ServiceInfo> {
    if uuid::Uuid::parse_str(computer_id).is_err() || !super::private_ip(address.ip())
        || address.ip().is_loopback() || address.port() == 0 { bail!("Invalid mDNS identity or address"); }
    let properties = std::collections::HashMap::from([
        ("computerId".to_owned(), computer_id.to_owned()),
        ("port".to_owned(), address.port().to_string()), ("protocol".to_owned(), "1".to_owned()),
    ]);
    let mut service = mdns_sd::ServiceInfo::new(&format!("{SERVICE}."), computer_id,
        &format!("pocket-{computer_id}.local."), address.ip(), address.port(), properties)?;
    service.set_interfaces(vec![mdns_sd::IfKind::Addr(address.ip())]);
    Ok(service)
}
struct Advertisement {
    task: tokio::task::JoinHandle<()>,
    daemon: mdns_sd::ServiceDaemon,
    fullname: String,
}
impl Drop for Advertisement {
    fn drop(&mut self) {
        self.task.abort();
        let _ = self.daemon.unregister(&self.fullname);
        let _ = self.daemon.shutdown();
    }
}
fn discovery_failure(stage: &'static str, e: &mdns_sd::Error) -> RouteFailure {
    // The upstream library carries some platform failures as text. Never export that text.
    let code = match e {
        mdns_sd::Error::Again => "discovery_queue_full",
        mdns_sd::Error::DaemonShutdown => "discovery_stopped",
        mdns_sd::Error::Msg(text) if text.contains("bind") => "discovery_bind_failed",
        mdns_sd::Error::Msg(text) if text.contains("multicast") || text.contains("join") => "discovery_multicast_failed",
        _ => "discovery_failed",
    };
    RouteFailure { stage, code, os_code: None }
}
impl Advertisement {
    fn start(address: SocketAddr, computer_id: &str, health: Arc<Connection>, generation: u64, discovery_id: String)
        -> std::result::Result<Self, RouteFailure> {
        let service = mdns_service(computer_id, address).map_err(|_| RouteFailure {
            stage: "mdns_service", code: "discovery_invalid_service", os_code: None })?;
        let daemon = mdns_sd::ServiceDaemon::new().map_err(|e| discovery_failure("mdns_start", &e))?;
        let configure = || -> mdns_sd::Result<_> {
            daemon.disable_interface(mdns_sd::IfKind::All)?;
            daemon.enable_interface(mdns_sd::IfKind::Addr(address.ip()))?;
            let events = daemon.monitor()?;
            let fullname = service.get_fullname().to_owned();
            daemon.register(service)?;
            Ok((events, fullname))
        };
        let (events, fullname) = match configure() {
            Ok(v) => v,
            Err(e) => { let _ = daemon.shutdown(); return Err(discovery_failure("mdns_register", &e)); }
        };
        health.route_phase_at(generation, &discovery_id, "starting", false, None);
        let expected = fullname.clone();
        let task = tokio::spawn(async move {
            loop {
                match tokio::time::timeout(Duration::from_secs(10), events.recv_async()).await {
                    Ok(Ok(mdns_sd::DaemonEvent::Announce(name, _))) if name == expected =>
                        health.route_phase_at(generation, &discovery_id, "advertising", true, None),
                    Ok(Ok(mdns_sd::DaemonEvent::Error(e))) =>
                        health.route_failure_at(generation, &discovery_id, discovery_failure("mdns_announce", &e)),
                    Ok(Err(_)) => { health.route_failure_at(generation, &discovery_id, RouteFailure {
                        stage: "mdns_monitor", code: "discovery_stopped", os_code: None }); break; }
                    Err(_) => {
                        // Keep existing positive evidence; a quiet daemon is normal after announcing.
                        let announced = health.route_snapshot().as_array().is_some_and(|rows|
                            rows.iter().any(|r| r["id"] == discovery_id && r["listenerReady"] == true));
                        if !announced { health.route_failure_at(generation, &discovery_id, RouteFailure {
                            stage: "mdns_announce", code: "discovery_announcement_unconfirmed", os_code: None }); }
                    }
                    _ => {}
                }
            }
        });
        Ok(Self { task, daemon, fullname })
    }
}
