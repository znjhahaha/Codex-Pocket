//! Private LAN direct TLS. A bounded duplex proxy makes cancellation close delivered sessions.
use super::LanConfig;
use crate::{connection::Connection, transport::TlsSession};
use anyhow::{Result, bail};
use pocket_tunnel::ServerIdentity;
use std::{
    net::{IpAddr, SocketAddr},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncWrite, AsyncWriteExt},
    net::{TcpListener, UdpSocket},
    sync::{Semaphore, mpsc},
    task::JoinSet,
};

pub struct Server {
    task: tokio::task::JoinHandle<()>,
    address: SocketAddr,
    health: Arc<Connection>,
    mdns: bool,
    generation: u64,
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
        self.health
            .route_phase_at(self.generation, "lan", "stopped", false, None);
        if self.mdns {
            self.health
                .route_phase_at(self.generation, "mdns", "stopped", false, None);
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
        if health.generation() != generation {
            bail!("LAN configuration changed");
        }
        if !super::private_ip(settings.bind.ip()) {
            bail!("LAN listener requires a specific private bind");
        }
        let listener = TcpListener::bind(settings.bind).await?;
        let address = listener.local_addr()?;
        // mDNS failure must be visible separately; it does not invalidate the TLS listener.
        let mdns = if settings.mdns {
            match Advertisement::start(address, &computer_id).await {
                Ok(a) => {
                    health.route_phase_at(generation, "mdns", "advertising", true, None);
                    Some(a)
                }
                Err(_) => {
                    health.route_phase_at(generation, "mdns", "failed", false, None);
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
            "lan",
            "listening",
            true,
            Some(format!("tls://{address}")),
        );
        let observed = health.clone();
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
                        let Ok((stream,peer))=incoming else {observed.route_phase_at(generation,"lan","failed",false,None);break;};
                        if observed.generation()!=generation{break;}
                        if !super::private_ip(peer.ip()) {continue;}
                        let Ok(permit)=permits.clone().try_acquire_owned() else {continue;};
                        let identity=identity.clone();let sessions=sessions.clone();let health=observed.clone();
                        workers.spawn(async move {let _permit=permit;let _=serve_stream_at(stream,identity,sessions,health,"lan",generation).await;});
                    }
                }
            }
            observed.route_phase_at(generation, "lan", "stopped", false, None);
            if settings.mdns {
                observed.route_phase_at(generation, "mdns", "stopped", false, None);
            }
        });
        Ok(Self {
            task,
            address,
            health,
            mdns: settings.mdns,
            generation,
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
    id: &'static str,
    generation: u64,
}
impl SessionGuard {
    pub(crate) fn new_at(
        health: Arc<Connection>,
        id: &'static str,
        generation: u64,
    ) -> Option<Self> {
        let generation = health.session_open_at(generation, id)?;
        Some(Self {
            health,
            id,
            generation,
        })
    }
}
impl Drop for SessionGuard {
    fn drop(&mut self) {
        self.health
            .session_close_generation(self.id, self.generation);
    }
}

/// Shared TLS ingress; no authentication is bypassed, session::serve still requires pair/hello.
pub(crate) async fn serve_stream_at<S>(
    mut stream: S,
    identity: Arc<ServerIdentity>,
    sessions: mpsc::Sender<TlsSession>,
    health: Arc<Connection>,
    id: &'static str,
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
struct Advertisement {
    task: tokio::task::JoinHandle<()>,
    socket: Arc<UdpSocket>,
    goodbye: Vec<u8>,
    destination: SocketAddr,
}
impl Drop for Advertisement {
    fn drop(&mut self) {
        self.task.abort();
        let _ = self.socket.try_send_to(&self.goodbye, self.destination);
    }
}
impl Advertisement {
    async fn start(address: SocketAddr, computer_id: &str) -> Result<Self> {
        // One advertiser per interface. Bind failure (e.g. another mDNS daemon) reports failed.
        let IpAddr::V4(ip) = address.ip() else {
            bail!("mDNS currently requires an IPv4 LAN interface");
        };
        if ip.is_loopback() {
            bail!("mDNS requires a LAN interface");
        }
        let group = std::net::Ipv4Addr::new(224, 0, 0, 251);
        let socket = std::net::UdpSocket::bind((ip, 5353))?;
        socket.join_multicast_v4(&group, &ip)?;
        socket.set_multicast_ttl_v4(255)?;
        socket.set_nonblocking(true)?;
        let socket = Arc::new(UdpSocket::from_std(socket)?);
        let destination = SocketAddr::new(group.into(), 5353);
        let announcement = mdns_packet(computer_id, address, 120)?;
        let goodbye = mdns_packet(computer_id, address, 0)?;
        socket.send_to(&announcement, destination).await?;
        let udp = socket.clone();
        let task = tokio::spawn(async move {
            let mut clock = tokio::time::interval(Duration::from_secs(30));
            let mut input = [0u8; 1500];
            let mut replied = tokio::time::Instant::now() - Duration::from_secs(1);
            loop {
                tokio::select! {
                    _=clock.tick()=>{let _=udp.send_to(&announcement,destination).await;},
                    result=udp.recv_from(&mut input)=>{
                        let Ok((n,peer))=result else {break;};
                        // Answer bounded DNS queries on this LAN, never reflect unicast traffic.
                    if n>=12 && input[2]&0x80==0 && peer.port()==5353 && super::private_ip(peer.ip()) && replied.elapsed()>=Duration::from_secs(1) {
                        replied=tokio::time::Instant::now();
                            let _=udp.send_to(&announcement,destination).await;
                        }
                    }
                }
            }
        });
        Ok(Self {
            task,
            socket,
            goodbye,
            destination,
        })
    }
}
