//! Outer WSS multiplexing. Inner TLS terminates only in the helper and phone.
use anyhow::{Result, anyhow, bail};
use futures_util::{Sink, SinkExt, StreamExt};
use pocket_tunnel::ServerIdentity;
use std::{
    collections::{HashMap, VecDeque},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream, duplex, split},
    sync::mpsc,
    task::{AbortHandle, JoinSet},
    time::{Instant, interval, timeout},
};
use tokio_tungstenite::{
    WebSocketStream, connect_async_with_config,
    tungstenite::{
        Message,
        client::IntoClientRequest,
        http::{HeaderValue, Request},
        protocol::WebSocketConfig,
    },
};
use uuid::Uuid;

const MAX_CHUNK: usize = 65536;
const HEADER: usize = 17;
const MAX_PHONES: usize = 8;
const QUEUE_CAPACITY: usize = 8;
const IO_TIMEOUT: Duration = Duration::from_secs(5);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(20);
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);

pub type TlsSession = tokio_rustls::server::TlsStream<DuplexStream>;

/// One authenticated WSS connection; the parent owns retry policy.
/// Native OS roots and normal host/certificate checks protect the outer TLS.
/// Returning or cancelling this future closes every associated inner TLS session.
pub async fn run(
    config: crate::config::Config,
    identity: Arc<ServerIdentity>,
    sessions: mpsc::Sender<TlsSession>,
) -> Result<()> {
    run_monitored(
        config,
        identity,
        sessions,
        Arc::new(crate::connection::Connection::default()),
    )
    .await
}
pub async fn run_monitored(
    config: crate::config::Config,
    identity: Arc<ServerIdentity>,
    sessions: mpsc::Sender<TlsSession>,
    health: Arc<crate::connection::Connection>,
) -> Result<()> {
    struct Disconnect(Arc<crate::connection::Connection>);
    impl Drop for Disconnect {
        fn drop(&mut self) {
            self.0.set("reconnecting");
        }
    }
    let _disconnected = Disconnect(health.clone());
    let request = relay_request(&config)?;
    let mut websocket_config = WebSocketConfig::default();
    websocket_config.max_message_size = Some(MAX_CHUNK + HEADER);
    websocket_config.max_frame_size = Some(MAX_CHUNK + HEADER);
    websocket_config.read_buffer_size = 4096;
    websocket_config.write_buffer_size = 0;
    websocket_config.max_write_buffer_size = 2 * (MAX_CHUNK + HEADER);
    // rustls-tls-native-roots is enabled in Cargo. No custom connector or verifier.
    let (websocket, _) = timeout(
        CONNECT_TIMEOUT,
        connect_async_with_config(request, Some(websocket_config), true),
    )
    .await
    .map_err(|_| anyhow!("relay connection timed out"))?
    .map_err(|_| anyhow!("relay connection failed"))?;
    health.activity();
    run_connection_monitored(websocket, identity, sessions, health).await
}

fn relay_request(config: &crate::config::Config) -> Result<Request<()>> {
    let mut url = url::Url::parse(&config.relay_url).map_err(|_| anyhow!("invalid relay URL"))?;
    if config.protocol != 1
        || url.scheme() != "wss"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        bail!("relay requires a credential-free WSS URL");
    }
    if config.room.is_empty()
        || config.room.len() > 64
        || !config
            .room
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        bail!("invalid relay room");
    }
    if !(32..=256).contains(&config.helper_token.len())
        || !config.helper_token.bytes().all(|b| b.is_ascii_graphic())
        || config.helper_token == config.phone_token
    {
        bail!("invalid relay helper credential");
    }
    url.path_segments_mut()
        .map_err(|_| anyhow!("invalid relay URL"))?
        .pop_if_empty()
        .extend(["v1", "tunnel", &config.room, "helper"]);
    let mut request = url
        .as_str()
        .into_client_request()
        .map_err(|_| anyhow!("invalid relay request"))?;
    let mut authorization = HeaderValue::from_str(&format!("Bearer {}", config.helper_token))
        .map_err(|_| anyhow!("invalid relay helper credential"))?;
    authorization.set_sensitive(true);
    request.headers_mut().insert("authorization", authorization);
    Ok(request)
}

struct Peer {
    inbound: mpsc::Sender<Vec<u8>>,
    task: AbortHandle,
}
impl Drop for Peer {
    fn drop(&mut self) {
        self.task.abort();
    }
}

fn envelope(kind: u8, id: Uuid, payload: &[u8]) -> Vec<u8> {
    let mut data = Vec::with_capacity(HEADER + payload.len());
    data.push(kind);
    data.extend_from_slice(id.as_bytes());
    data.extend_from_slice(payload);
    data
}

fn decode(data: &[u8]) -> Result<(u8, Uuid, &[u8])> {
    if data.len() < HEADER || data.len() > HEADER + MAX_CHUNK {
        bail!("invalid relay envelope");
    }
    let id = Uuid::from_slice(&data[1..HEADER]).map_err(|_| anyhow!("invalid relay envelope"))?;
    if id.is_nil()
        || !(matches!((data[0], data.len()), (1 | 2, HEADER))
            || (data[0] == 3 && data.len() > HEADER))
    {
        bail!("invalid relay envelope");
    }
    Ok((data[0], id, &data[HEADER..]))
}

fn remember_closed(closed: &mut VecDeque<Uuid>, id: Uuid) {
    if closed.len() == 64 {
        closed.pop_front();
    }
    closed.push_back(id);
}

async fn send<S: Sink<Message> + Unpin>(sink: &mut S, message: Message) -> Result<()> {
    timeout(IO_TIMEOUT, sink.send(message))
        .await
        .map_err(|_| anyhow!("relay write timed out"))?
        .map_err(|_| anyhow!("relay write failed"))
}

#[cfg(test)]
async fn run_connection<S>(
    websocket: WebSocketStream<S>,
    identity: Arc<ServerIdentity>,
    sessions: mpsc::Sender<TlsSession>,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    run_connection_monitored(
        websocket,
        identity,
        sessions,
        Arc::new(crate::connection::Connection::default()),
    )
    .await
}
async fn run_connection_monitored<S>(
    websocket: WebSocketStream<S>,
    identity: Arc<ServerIdentity>,
    sessions: mpsc::Sender<TlsSession>,
    health: Arc<crate::connection::Connection>,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let (mut sink, mut source) = websocket.split();
    let mut peers: HashMap<Uuid, Peer> = HashMap::new();
    let mut closed = VecDeque::new();
    let mut workers = JoinSet::new();
    let (outbound, mut output) = mpsc::channel::<(Uuid, Vec<u8>)>(MAX_PHONES * 2);
    let mut heartbeat = interval(HEARTBEAT_INTERVAL);
    heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    heartbeat.tick().await;
    let mut received = Instant::now();
    loop {
        let message = tokio::select! {
            _=sessions.closed()=>return Ok(()),
            incoming=source.next()=>{
                let incoming=match incoming {Some(Ok(m))=>m,Some(Err(_))=>bail!("relay read failed"),None=>return Ok(())};
                received=Instant::now();
                health.activity();
                match incoming {
                    Message::Binary(data)=>{
                        let (kind,id,payload)=decode(&data)?;
                        match kind {
                            1=>{
                                if peers.contains_key(&id) || closed.contains(&id) {bail!("duplicate relay channel");}
                                if peers.len()==MAX_PHONES {remember_closed(&mut closed,id);Message::Binary(envelope(2,id,&[]).into())}
                                else {
                                    let (inbound,rx)=mpsc::channel(QUEUE_CAPACITY);
                                    let task=workers.spawn(channel_worker(id,identity.clone(),sessions.clone(),rx,outbound.clone()));
                                    peers.insert(id,Peer{inbound,task});
                                    continue;
                                }
                            },
                            2=>{
                                if peers.remove(&id).is_some() {remember_closed(&mut closed,id);}
                                else if !closed.contains(&id) {bail!("unknown relay channel");}
                                continue;
                            },
                            3=>{
                                let Some(peer)=peers.get(&id) else {
                                    // In-flight frames for a locally closed channel may precede
                                    // the relay observing our disconnect. Tombstones are bounded.
                                    if closed.contains(&id) {continue;}
                                    bail!("unknown relay channel");
                                };
                                if peer.inbound.try_send(payload.to_vec()).is_err() {
                                    peers.remove(&id);remember_closed(&mut closed,id);Message::Binary(envelope(2,id,&[]).into())
                                } else {tokio::task::yield_now().await;continue;}
                            },
                            _=>unreachable!(),
                        }
                    },
                    Message::Ping(bytes)=>Message::Pong(bytes),
                    Message::Pong(_)=>continue,
                    Message::Close(_)=>return Ok(()),
                    _=>bail!("unexpected relay message type"),
                }
            },
            next=output.recv()=>{
                let Some((id,data))=next else {return Ok(())};
                if !peers.contains_key(&id) {continue;}
                Message::Binary(envelope(3,id,&data).into())
            },
            completed=workers.join_next(),if !workers.is_empty()=>{
                match completed {
                    Some(Ok(id))=>{
                        if peers.remove(&id).is_none() {continue;}
                        remember_closed(&mut closed,id);Message::Binary(envelope(2,id,&[]).into())
                    },
                    Some(Err(error)) if error.is_cancelled()=>continue,
                    _=>bail!("inner transport task failed"),
                }
            },
            _=heartbeat.tick()=>{
                if received.elapsed()>=IDLE_TIMEOUT {bail!("relay keepalive timed out");}
                Message::Ping(Vec::new().into())
            },
        };
        send(&mut sink, message).await?;
    }
}

async fn channel_worker(
    id: Uuid,
    identity: Arc<ServerIdentity>,
    sessions: mpsc::Sender<TlsSession>,
    mut inbound: mpsc::Receiver<Vec<u8>>,
    outbound: mpsc::Sender<(Uuid, Vec<u8>)>,
) -> Uuid {
    let (tls, bridge) = duplex(MAX_CHUNK * 2);
    let (mut reader, mut writer) = split(bridge);
    let incoming = async {
        while let Some(data) = inbound.recv().await {
            if !matches!(
                timeout(IO_TIMEOUT, writer.write_all(&data)).await,
                Ok(Ok(()))
            ) {
                break;
            }
        }
    };
    let outgoing = async {
        let mut buffer = vec![0; MAX_CHUNK];
        loop {
            match reader.read(&mut buffer).await {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    // A large history reply produces ciphertext far faster than a home
                    // uplink drains it. Wait for the shared relay writer (bounded by the
                    // same I/O timeout) so TLS backpressure paces the session; dropping
                    // here disconnected every phone that opened a long conversation.
                    if !matches!(
                        timeout(IO_TIMEOUT, outbound.send((id, buffer[..n].to_vec()))).await,
                        Ok(Ok(()))
                    ) {
                        break;
                    }
                }
            }
        }
    };
    let handshake = async {
        // ServerIdentity imposes the independent 10-second TLS handshake deadline.
        if let Ok(tls) = identity.accept_tls(tls).await
            && sessions.try_send(tls).is_ok()
        {
            std::future::pending::<()>().await;
        }
    };
    tokio::select! {_=incoming=>{},_=outgoing=>{},_=handshake=>{}}
    id
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::{SinkExt, StreamExt};
    use tokio::io::{AsyncReadExt, AsyncWriteExt, duplex};
    use tokio::time::{Duration, timeout};
    use tokio_tungstenite::{
        WebSocketStream,
        tungstenite::{Message, protocol::Role},
    };
    use uuid::Uuid;

    fn config() -> crate::config::Config {
        serde_json::from_value(serde_json::json!({"protocol":1,"computerId":"test","computerName":"test","relayUrl":"wss://example.invalid/pocket","room":"room-1","helperToken":"h".repeat(43),"phoneToken":"p".repeat(43),"runtimeDirectories":[],"codexHomes":[]})).unwrap()
    }
    fn identity() -> (tempfile::TempDir, Arc<ServerIdentity>) {
        let dir = tempfile::tempdir().unwrap();
        let id = Arc::new(ServerIdentity::load_or_create(dir.path().join("identity")).unwrap());
        (dir, id)
    }
    fn frame(kind: u8, id: Uuid, data: &[u8]) -> Vec<u8> {
        let mut bytes = vec![kind];
        bytes.extend_from_slice(id.as_bytes());
        bytes.extend_from_slice(data);
        bytes
    }
    async fn next_binary<S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin>(
        ws: &mut WebSocketStream<S>,
    ) -> Vec<u8> {
        timeout(Duration::from_secs(4), async {
            loop {
                match ws.next().await.unwrap().unwrap() {
                    Message::Binary(bytes) => return bytes.to_vec(),
                    Message::Ping(bytes) => ws.send(Message::Pong(bytes)).await.unwrap(),
                    other => panic!("unexpected control frame: {other:?}"),
                }
            }
        })
        .await
        .unwrap()
    }

    #[test]
    fn request_requires_wss_and_uses_only_helper_bearer() {
        let good = config();
        let req = relay_request(&good).unwrap();
        assert_eq!(
            req.uri(),
            "wss://example.invalid/pocket/v1/tunnel/room-1/helper"
        );
        assert_eq!(
            req.headers()["authorization"],
            format!("Bearer {}", good.helper_token)
        );
        for url in [
            "ws://example.invalid",
            "wss://user@example.invalid",
            "wss://example.invalid?token=secret",
            "wss://example.invalid#secret",
        ] {
            let mut bad = config();
            bad.relay_url = url.into();
            assert!(relay_request(&bad).is_err());
        }
        let mut bad = config();
        bad.phone_token = bad.helper_token.clone();
        assert!(relay_request(&bad).is_err());
        let mut bad = config();
        bad.room = "../room".into();
        assert!(relay_request(&bad).is_err());
    }

    #[tokio::test]
    async fn malformed_outer_frame_terminates_connection_without_session() {
        let (_dir, id) = identity();
        let (a, b) = duplex(1024);
        let (tx, mut rx) = mpsc::channel(1);
        let client = WebSocketStream::from_raw_socket(a, Role::Client, None).await;
        let mut relay = WebSocketStream::from_raw_socket(b, Role::Server, None).await;
        let task = tokio::spawn(run_connection(client, id, tx));
        relay
            .send(Message::Binary(vec![7; 17].into()))
            .await
            .unwrap();
        assert!(
            timeout(Duration::from_secs(3), task)
                .await
                .unwrap()
                .unwrap()
                .is_err()
        );
        assert!(rx.recv().await.is_none());
    }

    #[tokio::test]
    async fn tls_session_reaches_broker_and_connection_loss_closes_it() {
        let (_dir, id) = identity();
        let client_cfg =
            pocket_tunnel::pinned_client_config(id.certificate_der(), &id.certificate_sha256())
                .unwrap();
        let (a, b) = duplex(131072);
        let (tx, mut sessions) = mpsc::channel(1);
        let client = WebSocketStream::from_raw_socket(a, Role::Client, None).await;
        let mut relay = WebSocketStream::from_raw_socket(b, Role::Server, None).await;
        let task = tokio::spawn(run_connection(client, id, tx));
        let channel = Uuid::new_v4();
        relay
            .send(Message::Binary(frame(1, channel, &[]).into()))
            .await
            .unwrap();
        let (phone, tunnel) = duplex(131072);
        let (mut rr, mut rw) = tokio::io::split(tunnel);
        let (mut ws_write, mut ws_read) = relay.split();
        let input = tokio::spawn(async move {
            while let Some(Ok(Message::Binary(bytes))) = ws_read.next().await {
                assert_eq!(bytes[0], 3);
                assert_eq!(&bytes[1..17], channel.as_bytes());
                if rw.write_all(&bytes[17..]).await.is_err() {
                    break;
                }
            }
        });
        let output = tokio::spawn(async move {
            let mut bytes = vec![0; 4000];
            while let Ok(n) = rr.read(&mut bytes).await {
                if n == 0 {
                    break;
                }
                if ws_write
                    .send(Message::Binary(frame(3, channel, &bytes[..n]).into()))
                    .await
                    .is_err()
                {
                    break;
                }
            }
        });
        let mut phone = timeout(
            Duration::from_secs(3),
            tokio_rustls::TlsConnector::from(Arc::new(client_cfg))
                .connect("pocket.local".try_into().unwrap(), phone),
        )
        .await
        .unwrap()
        .unwrap();
        let mut session = timeout(Duration::from_secs(3), sessions.recv())
            .await
            .unwrap()
            .unwrap();
        pocket_tunnel::write_json(
            &mut phone,
            &serde_json::json!({"id":"one","method":"hello","params":{}}),
        )
        .await
        .unwrap();
        let value: serde_json::Value = timeout(
            Duration::from_secs(3),
            pocket_tunnel::read_json(&mut session),
        )
        .await
        .unwrap()
        .unwrap()
        .unwrap();
        assert_eq!(value["id"], "one");
        task.abort();
        input.abort();
        output.abort();
        let read = timeout(
            Duration::from_secs(3),
            pocket_tunnel::read_json::<_, serde_json::Value>(&mut session),
        )
        .await
        .expect("TLS session must close with relay connection");
        assert!(matches!(read, Ok(None) | Err(_)));
    }

    #[tokio::test]
    async fn invalid_handshake_disconnects_only_channel() {
        let (_dir, id) = identity();
        let (a, b) = duplex(4096);
        let (tx, mut sessions) = mpsc::channel(1);
        let client = WebSocketStream::from_raw_socket(a, Role::Client, None).await;
        let mut relay = WebSocketStream::from_raw_socket(b, Role::Server, None).await;
        let task = tokio::spawn(run_connection(client, id, tx));
        let channel = Uuid::new_v4();
        relay
            .send(Message::Binary(frame(1, channel, &[]).into()))
            .await
            .unwrap();
        relay
            .send(Message::Binary(frame(3, channel, b"not TLS at all").into()))
            .await
            .unwrap();
        let mut disconnected = false;
        for _ in 0..4 {
            let bytes = next_binary(&mut relay).await;
            if bytes[0] == 2 {
                assert_eq!(&bytes[1..17], channel.as_bytes());
                disconnected = true;
                break;
            }
        }
        assert!(disconnected);
        assert!(sessions.try_recv().is_err());
        assert!(!task.is_finished());
        task.abort();
    }

    #[tokio::test]
    async fn invalid_tls_burst_cannot_leak_sessions() {
        let (_dir, id) = identity();
        let (a, b) = duplex(4 * 65536);
        let (tx, mut sessions) = mpsc::channel(1);
        let client = WebSocketStream::from_raw_socket(a, Role::Client, None).await;
        let mut relay = WebSocketStream::from_raw_socket(b, Role::Server, None).await;
        let task = tokio::spawn(run_connection(client, id, tx));
        let channel = Uuid::new_v4();
        relay
            .send(Message::Binary(frame(1, channel, &[]).into()))
            .await
            .unwrap();
        // A burst of invalid TLS bytes must never produce an authenticated stream.
        for _ in 0..20 {
            if relay
                .send(Message::Binary(frame(3, channel, &[0; 65536]).into()))
                .await
                .is_err()
            {
                break;
            }
        }
        let bytes = next_binary(&mut relay).await;
        assert_eq!(bytes[0], 2);
        assert_eq!(&bytes[1..17], channel.as_bytes());
        assert!(sessions.try_recv().is_err());
        task.abort();
    }

    #[tokio::test]
    async fn slow_broker_consumer_disconnects_only_its_phone() {
        let (_dir, id) = identity();
        let config =
            pocket_tunnel::pinned_client_config(id.certificate_der(), &id.certificate_sha256())
                .unwrap();
        let (a, b) = duplex(131072);
        let (tx, mut sessions) = mpsc::channel(1);
        let client = WebSocketStream::from_raw_socket(a, Role::Client, None).await;
        let mut relay = WebSocketStream::from_raw_socket(b, Role::Server, None).await;
        let task = tokio::spawn(run_connection(client, id, tx));
        let channel = Uuid::new_v4();
        relay
            .send(Message::Binary(frame(1, channel, &[]).into()))
            .await
            .unwrap();
        let (phone, tunnel) = duplex(131072);
        let (mut rr, mut rw) = tokio::io::split(tunnel);
        let (mut ws_write, mut ws_read) = relay.split();
        let (disconnected_tx, disconnected_rx) = tokio::sync::oneshot::channel();
        let input = tokio::spawn(async move {
            let mut disconnected_tx = Some(disconnected_tx);
            while let Some(Ok(Message::Binary(bytes))) = ws_read.next().await {
                assert_eq!(&bytes[1..17], channel.as_bytes());
                if bytes[0] == 2 {
                    if let Some(tx) = disconnected_tx.take() {
                        let _ = tx.send(());
                    }
                    // This is the shared relay socket, not the disconnected phone.
                    // Retain its read half to model the relay keeping other channels alive.
                    continue;
                }
                assert_eq!(bytes[0], 3);
                if rw.write_all(&bytes[17..]).await.is_err() {
                    break;
                }
            }
        });
        let output = tokio::spawn(async move {
            let mut bytes = vec![0; 65536];
            while let Ok(n) = rr.read(&mut bytes).await {
                if n == 0 {
                    break;
                }
                if ws_write
                    .send(Message::Binary(frame(3, channel, &bytes[..n]).into()))
                    .await
                    .is_err()
                {
                    break;
                }
            }
        });
        let mut phone = timeout(
            Duration::from_secs(3),
            tokio_rustls::TlsConnector::from(Arc::new(config))
                .connect("pocket.local".try_into().unwrap(), phone),
        )
        .await
        .unwrap()
        .unwrap();
        let mut unread_session = timeout(Duration::from_secs(3), sessions.recv())
            .await
            .unwrap()
            .unwrap();
        let sending = tokio::spawn(async move {
            pocket_tunnel::write_json(
                &mut phone,
                &serde_json::json!({"id":"flood","params":{"bytes":"x".repeat(2*1024*1024)}}),
            )
            .await
        });
        timeout(Duration::from_secs(7), disconnected_rx)
            .await
            .expect("slow consumer was not disconnected")
            .unwrap();
        assert!(
            !task.is_finished(),
            "one slow phone must not take down outer connection"
        );
        // There can be buffered incomplete ciphertext, but the TLS read must finish or fail promptly.
        assert!(
            timeout(
                Duration::from_secs(3),
                pocket_tunnel::read_json::<_, serde_json::Value>(&mut unread_session)
            )
            .await
            .unwrap()
            .is_err()
        );
        sending.abort();
        input.abort();
        output.abort();
        task.abort();
    }

    #[tokio::test]
    async fn large_reply_over_slow_relay_uplink_is_paced_not_disconnected() {
        let (_dir, id) = identity();
        let config =
            pocket_tunnel::pinned_client_config(id.certificate_der(), &id.certificate_sha256())
                .unwrap();
        // A small socket buffer models a home uplink that drains slower than TLS produces.
        let (a, b) = duplex(16384);
        let (tx, mut sessions) = mpsc::channel(1);
        let client = WebSocketStream::from_raw_socket(a, Role::Client, None).await;
        let relay = WebSocketStream::from_raw_socket(b, Role::Server, None).await;
        let task = tokio::spawn(run_connection(client, id, tx));
        let channel = Uuid::new_v4();
        let (mut ws_write, mut ws_read) = relay.split();
        ws_write
            .send(Message::Binary(frame(1, channel, &[]).into()))
            .await
            .unwrap();
        let (phone, tunnel) = duplex(1 << 20);
        let (mut rr, mut rw) = tokio::io::split(tunnel);
        let (disconnected_tx, mut disconnected_rx) = tokio::sync::oneshot::channel();
        let input = tokio::spawn(async move {
            let mut disconnected_tx = Some(disconnected_tx);
            while let Some(Ok(Message::Binary(bytes))) = ws_read.next().await {
                if bytes[0] == 2 {
                    if let Some(tx) = disconnected_tx.take() {
                        let _ = tx.send(());
                    }
                    break;
                }
                tokio::time::sleep(Duration::from_millis(15)).await;
                if rw.write_all(&bytes[17..]).await.is_err() {
                    break;
                }
            }
        });
        let output = tokio::spawn(async move {
            let mut bytes = vec![0; 65536];
            while let Ok(n) = rr.read(&mut bytes).await {
                if n == 0
                    || ws_write
                        .send(Message::Binary(frame(3, channel, &bytes[..n]).into()))
                        .await
                        .is_err()
                {
                    break;
                }
            }
        });
        let mut phone = timeout(
            Duration::from_secs(3),
            tokio_rustls::TlsConnector::from(Arc::new(config))
                .connect("pocket.local".try_into().unwrap(), phone),
        )
        .await
        .unwrap()
        .unwrap();
        let mut session = timeout(Duration::from_secs(3), sessions.recv())
            .await
            .unwrap()
            .unwrap();
        let reply = serde_json::json!({"id":"history","result":{"text":"x".repeat(2 * 1024 * 1024)}});
        let writer = tokio::spawn(async move {
            pocket_tunnel::write_json(&mut session, &reply).await.map(|_| session)
        });
        let received: serde_json::Value = tokio::select! {
            _ = &mut disconnected_rx => panic!("helper disconnected a slow but live phone"),
            read = timeout(Duration::from_secs(30), pocket_tunnel::read_json(&mut phone)) => read
                .expect("large reply stalled")
                .expect("phone channel closed during a large reply")
                .expect("phone channel ended during a large reply"),
        };
        assert_eq!(received["id"], "history");
        assert_eq!(received["result"]["text"].as_str().unwrap().len(), 2 * 1024 * 1024);
        let _ = writer.await;
        input.abort();
        output.abort();
        task.abort();
    }

    #[tokio::test]
    async fn ninth_phone_is_rejected_and_duplicate_connect_is_fatal() {
        let (_dir, id) = identity();
        let (a, b) = duplex(4096);
        let (tx, _sessions) = mpsc::channel(8);
        let client = WebSocketStream::from_raw_socket(a, Role::Client, None).await;
        let mut relay = WebSocketStream::from_raw_socket(b, Role::Server, None).await;
        let task = tokio::spawn(run_connection(client, id, tx));
        let channels: Vec<_> = (0..9).map(|_| Uuid::new_v4()).collect();
        for channel in &channels {
            relay
                .send(Message::Binary(frame(1, *channel, &[]).into()))
                .await
                .unwrap();
        }
        assert_eq!(next_binary(&mut relay).await, frame(2, channels[8], &[]));
        relay
            .send(Message::Binary(frame(1, channels[0], &[]).into()))
            .await
            .unwrap();
        assert!(
            timeout(Duration::from_secs(3), task)
                .await
                .unwrap()
                .unwrap()
                .is_err()
        );
    }
}
