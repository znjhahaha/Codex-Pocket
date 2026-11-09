//! Cloudflared ingress: loopback WebSocket phone path only, bearer before any TLS proxy.
use super::{GatewayConfig, lan};
use crate::{config::Config, connection::Connection, transport::TlsSession};
use anyhow::{Result, bail};
use futures_util::{SinkExt, StreamExt};
use pocket_tunnel::ServerIdentity;
use std::{net::SocketAddr, sync::Arc, time::Duration};
use subtle::ConstantTimeEq;
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::TcpListener,
    sync::{Semaphore, mpsc},
    task::JoinSet,
};
use tokio_tungstenite::{
    WebSocketStream, accept_hdr_async_with_config,
    tungstenite::{
        Message,
        handshake::server::{ErrorResponse, Request, Response},
        http::StatusCode,
        protocol::WebSocketConfig,
    },
};

pub struct Server {
    task: tokio::task::JoinHandle<()>,
    address: SocketAddr,
    health: Arc<Connection>,
    generation: u64,
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
        self.health
            .route_phase_at(self.generation, "gateway", "stopped", false, None);
    }
}
impl Server {
    pub async fn start(
        settings: GatewayConfig,
        config: &Config,
        identity: Arc<ServerIdentity>,
        sessions: mpsc::Sender<TlsSession>,
        health: Arc<Connection>,
    ) -> Result<Self> {
        let generation = health.generation();
        Self::start_at(settings, config, identity, sessions, health, generation).await
    }
    pub async fn start_at(
        settings: GatewayConfig,
        config: &Config,
        identity: Arc<ServerIdentity>,
        sessions: mpsc::Sender<TlsSession>,
        health: Arc<Connection>,
        generation: u64,
    ) -> Result<Self> {
        if health.generation() != generation {
            bail!("Gateway configuration changed");
        }
        if !settings.bind.ip().is_loopback() {
            bail!("Gateway must bind loopback");
        }
        config.preferences().validate(&config.room)?;
        if config.phone_token.len() < 32 || config.phone_token == config.helper_token {
            bail!("Invalid phone credential");
        }
        let listener = TcpListener::bind(settings.bind).await?;
        let address = listener.local_addr()?;
        let path = format!("/v1/tunnel/{}/phone", config.room);
        let token = config.phone_token.clone();
        if health.generation() != generation {
            bail!("Gateway configuration changed");
        }
        health.route_phase_at(
            generation,
            "gateway",
            "ready",
            true,
            Some(format!("http://{address}")),
        );
        let observed = health.clone();
        let task = tokio::spawn(async move {
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
                        let Ok((stream,peer))=incoming else {observed.route_phase_at(generation,"gateway","failed",false,None);break;};
                        if observed.generation()!=generation{break;}
                        if !peer.ip().is_loopback(){continue;}
                        let Ok(permit)=permits.clone().try_acquire_owned() else{continue;};
                        let path=path.clone();let token=token.clone();let identity=identity.clone();let sessions=sessions.clone();let health=observed.clone();
                        workers.spawn(async move {
                            let _permit=permit;
                            let callback=move |request:&Request,response:Response|authorize(request,response,&path,&token);
                            let mut settings=WebSocketConfig::default();
                            settings.max_message_size=Some(65536);settings.max_frame_size=Some(65536);
                            settings.read_buffer_size=4096;settings.write_buffer_size=0;settings.max_write_buffer_size=131072;
                            if let Ok(Ok(websocket))=tokio::time::timeout(Duration::from_secs(5),accept_hdr_async_with_config(stream,callback,Some(settings))).await {
                                if health.generation()!=generation{return;}
                                let (inner,wire)=tokio::io::duplex(131072);
                                // A WebSocket upgrade alone is readiness, never remote or innerTLS health.
                                let tls=lan::serve_stream_at(inner,identity,sessions,health,"gateway",generation);
                                let copying=proxy(websocket,wire);
                                tokio::pin!(tls);tokio::pin!(copying);
                                tokio::select! {_=&mut tls=>{},_=&mut copying=>{}}
                            }
                        });
                    }
                }
            }
            observed.route_phase_at(generation, "gateway", "stopped", false, None);
        });
        Ok(Self {
            task,
            address,
            health,
            generation,
        })
    }
    pub fn local_addr(&self) -> SocketAddr {
        self.address
    }
    pub fn endpoint(&self) -> String {
        format!("http://{}", self.address)
    }
    pub async fn shutdown(mut self) {
        self.task.abort();
        let _ = (&mut self.task).await;
    }
}
fn authorize(
    request: &Request,
    response: Response,
    path: &str,
    phone_token: &str,
) -> std::result::Result<Response, ErrorResponse> {
    fn reject(status: StatusCode) -> ErrorResponse {
        let mut r = ErrorResponse::new(Some("Gateway request rejected".into()));
        *r.status_mut() = status;
        r
    }
    if request.uri().path() != path || request.uri().query().is_some() || request.method() != "GET"
    {
        return Err(reject(StatusCode::NOT_FOUND));
    }
    let headers = request.headers().get_all("authorization");
    let mut values = headers.iter();
    let value = values.next().and_then(|v| v.to_str().ok()).unwrap_or("");
    let expected = format!("Bearer {phone_token}");
    if values.next().is_some() || !bool::from(value.as_bytes().ct_eq(expected.as_bytes())) {
        return Err(reject(StatusCode::UNAUTHORIZED));
    }
    Ok(response)
}
/// Bounded ciphertext-only bridge. No local control, JSON RPC, or management HTTP routing.
async fn proxy<S>(websocket: WebSocketStream<S>, wire: tokio::io::DuplexStream) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let (mut sink, mut source) = websocket.split();
    let (mut read, mut write) = tokio::io::split(wire);
    let mut buffer = vec![0; 65536];
    let mut heartbeat = tokio::time::interval(Duration::from_secs(20));
    heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut received = tokio::time::Instant::now();
    loop {
        let output = tokio::select! {
            incoming=source.next()=>{
                let Some(message)=incoming else {return Ok(());};received=tokio::time::Instant::now();
                match message? {
                    Message::Binary(bytes) if !bytes.is_empty()=>{tokio::time::timeout(Duration::from_secs(5),write.write_all(&bytes)).await??;continue;},
                    Message::Ping(bytes)=>Message::Pong(bytes),
                    Message::Pong(_)=>continue,
                    Message::Close(_)=>return Ok(()),
                    _=>bail!("Gateway accepts TLS ciphertext only"),
                }
            },
            n=read.read(&mut buffer)=>{let n=n?;if n==0{return Ok(());}Message::Binary(buffer[..n].to_vec().into())},
            _=heartbeat.tick()=>{if received.elapsed()>=Duration::from_secs(60){bail!("Gateway peer timed out");}Message::Ping(Vec::new().into())},
        };
        tokio::time::timeout(Duration::from_secs(5), sink.send(output)).await??;
    }
}
