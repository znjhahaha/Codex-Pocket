use std::time::Duration;

use bytes::Bytes;
use futures_util::{SinkExt, StreamExt};
use pocket_relay::{Envelope, MAX_CHUNK, RelayConfig, RelayState, RoomConfig, router};
use sha2::{Digest, Sha256};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::timeout;
use tokio_tungstenite::{
    MaybeTlsStream, WebSocketStream, connect_async,
    tungstenite::{
        Message,
        client::IntoClientRequest,
        http::{HeaderValue, StatusCode},
    },
};
use uuid::Uuid;

const HELPER: &str = "test-helper-token-0123456789abcdef0123456789abcdef";
const PHONE: &str = "test-phone-token--0123456789abcdef0123456789abcdef";
type Client = WebSocketStream<MaybeTlsStream<TcpStream>>;

fn config() -> RelayConfig {
    RelayConfig {
        rooms: vec![RoomConfig {
            id: "room-1".into(),
            helper_token_sha256: hex::encode(Sha256::digest(HELPER)),
            phone_token_sha256: hex::encode(Sha256::digest(PHONE)),
        }],
        ..Default::default()
    }
}

struct Server {
    base: String,
    task: tokio::task::JoinHandle<()>,
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
    }
}
async fn start() -> Server {
    start_with_config(config()).await
}
async fn start_with_config(config: RelayConfig) -> Server {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("ws://{}", listener.local_addr().unwrap());
    let app = router(RelayState::new(config).unwrap());
    let task = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    Server { base, task }
}

#[tokio::test]
async fn full_duplex_bursts_preserve_order_with_one_slot_queues() {
    let mut config = config();
    config.queue_capacity = 1;
    let server = start_with_config(config).await;
    let mut helper = helper(&server).await;
    let phone = phone(&server).await;
    let Envelope::Connect(id) = Envelope::decode(&binary(&mut helper).await).unwrap() else {
        panic!("expected phone connect");
    };
    let (mut helper_send, mut helper_recv) = helper.split();
    let (mut phone_send, mut phone_recv) = phone.split();
    const CHUNKS: u32 = 128;
    fn payload(direction: u8, index: u32) -> Bytes {
        let mut data = vec![direction; MAX_CHUNK];
        data[..4].copy_from_slice(&index.to_be_bytes());
        Bytes::from(data)
    }
    timeout(Duration::from_secs(15), async {
        let to_phone = async {
            for index in 0..CHUNKS {
                helper_send
                    .send(Message::Binary(
                        Envelope::Data(id, payload(0x41, index)).encode(),
                    ))
                    .await
                    .unwrap();
            }
        };
        let to_helper = async {
            for index in 0..CHUNKS {
                phone_send
                    .send(Message::Binary(payload(0x42, index)))
                    .await
                    .unwrap();
            }
        };
        let from_phone = async {
            tokio::time::sleep(Duration::from_millis(40)).await;
            for index in 0..CHUNKS {
                let Message::Binary(bytes) = helper_recv.next().await.unwrap().unwrap() else {
                    panic!("expected helper data");
                };
                assert_eq!(
                    Envelope::decode(&bytes).unwrap(),
                    Envelope::Data(id, payload(0x42, index))
                );
            }
        };
        let from_helper = async {
            tokio::time::sleep(Duration::from_millis(40)).await;
            for index in 0..CHUNKS {
                let Message::Binary(bytes) = phone_recv.next().await.unwrap().unwrap() else {
                    panic!("expected phone data");
                };
                assert_eq!(bytes, payload(0x41, index));
            }
        };
        tokio::join!(to_phone, to_helper, from_phone, from_helper);
    })
    .await
    .expect("full-duplex relay stalled");
}
async fn connect(
    server: &Server,
    path: &str,
    token: Option<&str>,
) -> Result<Client, tokio_tungstenite::tungstenite::Error> {
    let mut request = format!("{}{}", server.base, path)
        .into_client_request()
        .unwrap();
    if let Some(token) = token {
        request.headers_mut().insert(
            "authorization",
            HeaderValue::from_str(&format!("Bearer {token}")).unwrap(),
        );
    }
    connect_async(request).await.map(|(ws, _)| ws)
}
async fn helper(s: &Server) -> Client {
    connect(s, "/v1/tunnel/room-1/helper", Some(HELPER))
        .await
        .unwrap()
}
async fn phone(s: &Server) -> Client {
    connect(s, "/v1/tunnel/room-1/phone", Some(PHONE))
        .await
        .unwrap()
}
async fn binary(ws: &mut Client) -> Bytes {
    timeout(Duration::from_secs(3), async {
        loop {
            match ws.next().await.unwrap().unwrap() {
                Message::Binary(bytes) => return bytes,
                Message::Ping(bytes) => ws.send(Message::Pong(bytes)).await.unwrap(),
                msg => panic!("expected data, got {msg:?}"),
            }
        }
    })
    .await
    .expect("binary timeout")
}
async fn closed(ws: &mut Client) {
    timeout(Duration::from_secs(3), async {
        loop {
            match ws.next().await {
                None | Some(Err(_)) | Some(Ok(Message::Close(_))) => return,
                Some(Ok(Message::Ping(b))) => {
                    let _ = ws.send(Message::Pong(b)).await;
                }
                other => panic!("expected closure, got {other:?}"),
            }
        }
    })
    .await
    .expect("close timeout");
}
fn denied(result: Result<Client, tokio_tungstenite::tungstenite::Error>, expected: StatusCode) {
    match result {
        Err(tokio_tungstenite::tungstenite::Error::Http(response)) => {
            assert_eq!(response.status(), expected);
            if let Some(body) = response.body() {
                let body = String::from_utf8_lossy(body);
                assert!(!body.contains(HELPER));
                assert!(!body.contains("room-1"));
            }
        }
        other => panic!("expected HTTP {expected}, got {other:?}"),
    }
}

#[test]
fn envelope_rejects_malformed_unknown_and_oversize() {
    let id = Uuid::new_v4();
    for raw in [
        vec![],
        vec![3; 16],
        vec![9; 17],
        [vec![1], id.as_bytes().to_vec(), vec![1]].concat(),
        [vec![2], id.as_bytes().to_vec(), vec![1]].concat(),
        [vec![3], id.as_bytes().to_vec()].concat(),
        [vec![3], id.as_bytes().to_vec(), vec![0; MAX_CHUNK + 1]].concat(),
    ] {
        assert!(Envelope::decode(&raw).is_err());
    }
    let event = Envelope::Data(id, Bytes::from_static(b"opaque TLS records"));
    assert_eq!(Envelope::decode(&event.encode()).unwrap(), event);
    assert!(Envelope::decode_helper(&Envelope::Connect(id).encode()).is_err());
}

#[test]
fn config_rejects_unsafe_or_ambiguous_values() {
    let mut cfg = config();
    cfg.rooms[0].phone_token_sha256 = cfg.rooms[0].helper_token_sha256.clone();
    assert!(RelayState::new(cfg).is_err());
    let mut cfg = config();
    cfg.rooms[0].id = "../bad".into();
    assert!(RelayState::new(cfg).is_err());
    let mut cfg = config();
    cfg.rooms[0].helper_token_sha256 = "invalid".into();
    assert!(RelayState::new(cfg).is_err());
    let mut cfg = config();
    cfg.rooms.push(cfg.rooms[0].clone());
    assert!(RelayState::new(cfg).is_err());
    let mut cfg = config();
    cfg.queue_capacity = 0;
    assert!(RelayState::new(cfg).is_err());
}

#[tokio::test]
async fn auth_paths_roles_and_offline_rejection() {
    let s = start().await;
    denied(
        connect(&s, "/v1/tunnel/room-1/helper", None).await,
        StatusCode::UNAUTHORIZED,
    );
    denied(
        connect(&s, "/v1/tunnel/room-1/helper", Some(PHONE)).await,
        StatusCode::UNAUTHORIZED,
    );
    denied(
        connect(&s, "/v1/tunnel/unknown/helper", Some(HELPER)).await,
        StatusCode::UNAUTHORIZED,
    );
    denied(
        connect(&s, "/v1/tunnel/room-1/phone", Some(PHONE)).await,
        StatusCode::SERVICE_UNAVAILABLE,
    );
    denied(
        connect(
            &s,
            &format!("/v1/tunnel/room-1/helper?token={HELPER}"),
            None,
        )
        .await,
        StatusCode::BAD_REQUEST,
    );
    denied(
        connect(&s, "/v1/tunnel/room%2D1/helper", Some(HELPER)).await,
        StatusCode::BAD_REQUEST,
    );
    denied(
        connect(&s, "/v1/tunnel/room-1/other", Some(HELPER)).await,
        StatusCode::BAD_REQUEST,
    );
    denied(
        connect(&s, "/v1/tunnel/room-1/helper/extra", Some(HELPER)).await,
        StatusCode::NOT_FOUND,
    );
}

#[tokio::test]
async fn real_websocket_routing_cleanup_and_duplicate_helper() {
    let s = start().await;
    let mut h = helper(&s).await;
    denied(
        connect(&s, "/v1/tunnel/room-1/helper", Some(HELPER)).await,
        StatusCode::CONFLICT,
    );
    let mut p = phone(&s).await;
    let Envelope::Connect(id) = Envelope::decode(&binary(&mut h).await).unwrap() else {
        panic!()
    };
    p.send(Message::Binary(Bytes::from_static(b"client TLS")))
        .await
        .unwrap();
    assert_eq!(
        Envelope::decode(&binary(&mut h).await).unwrap(),
        Envelope::Data(id, Bytes::from_static(b"client TLS"))
    );
    h.send(Message::Binary(
        Envelope::Data(id, Bytes::from_static(b"server TLS")).encode(),
    ))
    .await
    .unwrap();
    assert_eq!(binary(&mut p).await, Bytes::from_static(b"server TLS"));
    p.close(None).await.unwrap();
    assert_eq!(
        Envelope::decode(&binary(&mut h).await).unwrap(),
        Envelope::Disconnect(id)
    );
    h.send(Message::Binary(
        Envelope::Data(id, Bytes::from_static(b"stale")).encode(),
    ))
    .await
    .unwrap();
    closed(&mut h).await;
}

#[tokio::test]
async fn helper_disconnect_closes_all_phones_and_room_can_reconnect() {
    let s = start().await;
    let mut h = helper(&s).await;
    let mut p1 = phone(&s).await;
    let _ = binary(&mut h).await;
    let mut p2 = phone(&s).await;
    let _ = binary(&mut h).await;
    h.close(None).await.unwrap();
    closed(&mut p1).await;
    closed(&mut p2).await;
    let mut h2 = helper(&s).await;
    let _p = phone(&s).await;
    assert!(matches!(
        Envelope::decode(&binary(&mut h2).await).unwrap(),
        Envelope::Connect(_)
    ));
}

#[tokio::test]
async fn text_oversize_and_phantom_channels_disconnect_sender() {
    let s = start().await;
    let mut h = helper(&s).await;
    let mut p = phone(&s).await;
    let _ = binary(&mut h).await;
    p.send(Message::Text("forbidden".into())).await.unwrap();
    closed(&mut p).await;
    assert!(matches!(
        Envelope::decode(&binary(&mut h).await).unwrap(),
        Envelope::Disconnect(_)
    ));
    let mut p = phone(&s).await;
    let _ = binary(&mut h).await;
    p.send(Message::Binary(vec![0; MAX_CHUNK + 1].into()))
        .await
        .unwrap();
    closed(&mut p).await;
    assert!(matches!(
        Envelope::decode(&binary(&mut h).await).unwrap(),
        Envelope::Disconnect(_)
    ));
    h.send(Message::Binary(
        Envelope::Data(Uuid::new_v4(), Bytes::from_static(b"phantom")).encode(),
    ))
    .await
    .unwrap();
    closed(&mut h).await;
}

#[tokio::test]
async fn helper_disconnect_envelope_only_closes_selected_phone() {
    let s = start().await;
    let mut h = helper(&s).await;
    let mut p = phone(&s).await;
    let Envelope::Connect(id) = Envelope::decode(&binary(&mut h).await).unwrap() else {
        panic!()
    };
    h.send(Message::Binary(Envelope::Disconnect(id).encode()))
        .await
        .unwrap();
    closed(&mut p).await;
    let _p2 = phone(&s).await;
    assert!(matches!(
        Envelope::decode(&binary(&mut h).await).unwrap(),
        Envelope::Connect(_)
    ));
}

#[tokio::test]
async fn at_most_eight_phones_and_binary_chunk_boundary() {
    let s = start().await;
    let mut h = helper(&s).await;
    let mut phones = Vec::new();
    for _ in 0..8 {
        phones.push(phone(&s).await);
        let _ = binary(&mut h).await;
    }
    denied(
        connect(&s, "/v1/tunnel/room-1/phone", Some(PHONE)).await,
        StatusCode::TOO_MANY_REQUESTS,
    );
    phones[0]
        .send(Message::Binary(vec![7; MAX_CHUNK].into()))
        .await
        .unwrap();
    let Envelope::Data(_, data) = Envelope::decode(&binary(&mut h).await).unwrap() else {
        panic!()
    };
    assert_eq!(data.len(), MAX_CHUNK);
}

#[tokio::test]
async fn health_and_fallback_are_constant_without_secrets() {
    use axum::{body::Body, http::Request};
    use http_body_util::BodyExt;
    use tower::ServiceExt;
    let app = router(RelayState::new(config()).unwrap());
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/healthz")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.into_body().collect().await.unwrap().to_bytes(),
        r#"{"status":"ok"}"#
    );
    let response = app
        .oneshot(
            Request::builder()
                .uri("/unknown?secret=do-not-echo")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    assert!(
        !String::from_utf8_lossy(&response.into_body().collect().await.unwrap().to_bytes())
            .contains("do-not-echo")
    );
}

#[tokio::test]
async fn helper_cannot_inject_connect_or_unknown_envelopes() {
    for tag in [0, 1, 4, 255] {
        let s = start().await;
        let mut h = helper(&s).await;
        let mut p = phone(&s).await;
        let _ = binary(&mut h).await;
        let mut raw = vec![tag];
        raw.extend_from_slice(Uuid::new_v4().as_bytes());
        h.send(Message::Binary(raw.into())).await.unwrap();
        closed(&mut h).await;
        closed(&mut p).await;
    }
}

#[tokio::test]
async fn helper_text_and_oversized_envelope_close_room() {
    for message in [
        Message::Text("not binary".into()),
        Message::Binary(Envelope::Data(Uuid::new_v4(), vec![0; MAX_CHUNK + 1].into()).encode()),
    ] {
        let s = start().await;
        let mut h = helper(&s).await;
        let mut p = phone(&s).await;
        let _ = binary(&mut h).await;
        h.send(message).await.unwrap();
        closed(&mut h).await;
        closed(&mut p).await;
    }
}

#[tokio::test]
async fn duplicate_authorization_header_is_rejected() {
    let s = start().await;
    let mut req = format!("{}/v1/tunnel/room-1/helper", s.base)
        .into_client_request()
        .unwrap();
    for _ in 0..2 {
        req.headers_mut().append(
            "authorization",
            HeaderValue::from_str(&format!("Bearer {HELPER}")).unwrap(),
        );
    }
    denied(
        connect_async(req).await.map(|(ws, _)| ws),
        StatusCode::UNAUTHORIZED,
    );
}

/// Full inner TLS over two real WebSockets, re-chunked across arbitrary record boundaries.
#[tokio::test]
async fn inner_tls_json_round_trip_through_opaque_relay() {
    use pocket_tunnel::{ServerIdentity, pinned_client_config, read_json, write_json};
    use std::sync::Arc;
    use tokio::io::{AsyncReadExt, AsyncWriteExt, duplex, split};
    let dir = tempfile::tempdir().unwrap();
    let identity = ServerIdentity::load_or_create(dir.path().join("identity")).unwrap();
    let client_config =
        pinned_client_config(identity.certificate_der(), &identity.certificate_sha256()).unwrap();
    let s = start().await;
    let mut h = helper(&s).await;
    let p = phone(&s).await;
    let Envelope::Connect(channel) = Envelope::decode(&binary(&mut h).await).unwrap() else {
        panic!()
    };
    let (server_io, helper_io) = duplex(MAX_CHUNK * 2);
    let (client_io, phone_io) = duplex(MAX_CHUNK * 2);
    let (mut hs, mut hr) = h.split();
    let (mut ps, mut pr) = p.split();
    let (mut helper_read, mut helper_write) = split(helper_io);
    let (mut phone_read, mut phone_write) = split(phone_io);
    let mut tasks = Vec::new();
    tasks.push(tokio::spawn(async move {
        while let Some(Ok(Message::Binary(data))) = hr.next().await {
            let Envelope::Data(id, data) = Envelope::decode(&data).unwrap() else {
                break;
            };
            assert_eq!(id, channel);
            if helper_write.write_all(&data).await.is_err() {
                break;
            }
        }
    }));
    tasks.push(tokio::spawn(async move {
        let mut bytes = vec![0; 3071];
        while let Ok(n) = helper_read.read(&mut bytes).await {
            if n == 0 {
                break;
            }
            if hs
                .send(Message::Binary(
                    Envelope::Data(channel, Bytes::copy_from_slice(&bytes[..n])).encode(),
                ))
                .await
                .is_err()
            {
                break;
            }
        }
    }));
    tasks.push(tokio::spawn(async move {
        while let Some(Ok(Message::Binary(data))) = pr.next().await {
            if phone_write.write_all(&data).await.is_err() {
                break;
            }
        }
    }));
    tasks.push(tokio::spawn(async move {
        let mut bytes = vec![0; 7919];
        while let Ok(n) = phone_read.read(&mut bytes).await {
            if n == 0 {
                break;
            }
            if ps
                .send(Message::Binary(Bytes::copy_from_slice(&bytes[..n])))
                .await
                .is_err()
            {
                break;
            }
        }
    }));
    let server = tokio::spawn(async move {
        let tls = identity.accept_tls(server_io).await.unwrap();
        let (mut reader, mut writer) = split(tls);
        let request: serde_json::Value = read_json(&mut reader).await.unwrap().unwrap();
        assert_eq!(request["payload"].as_str().unwrap().len(), 150_000);
        write_json(
            &mut writer,
            &serde_json::json!({"id":"roundtrip","result":request["payload"]}),
        )
        .await
        .unwrap();
    });
    timeout(Duration::from_secs(15), async {
        let tls = tokio_rustls::TlsConnector::from(Arc::new(client_config))
            .connect(
                rustls::pki_types::ServerName::try_from("pocket.local").unwrap(),
                client_io,
            )
            .await
            .unwrap();
        let (mut reader, mut writer) = split(tls);
        write_json(
            &mut writer,
            &serde_json::json!({"id":"roundtrip","payload":"x".repeat(150_000)}),
        )
        .await
        .unwrap();
        let response: serde_json::Value = read_json(&mut reader).await.unwrap().unwrap();
        assert_eq!(response["result"].as_str().unwrap().len(), 150_000);
        server.await.unwrap();
    })
    .await
    .expect("end-to-end TLS timed out");
    for task in tasks {
        task.abort();
    }
}
