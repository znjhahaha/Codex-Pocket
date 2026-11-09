use futures_util::{SinkExt, StreamExt};
use pocket_agent::{config::Config, storage};
use pocket_agent::{
    config::routes::{self, GatewayConfig, LanConfig, RoutePreferences, gateway, lan},
    connection::Connection,
};
use serde_json::{Value, json};
use std::{sync::Arc, time::Duration};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    sync::mpsc,
};
use tokio_tungstenite::{
    connect_async,
    tungstenite::{Message, client::IntoClientRequest, http::HeaderValue},
};

fn fixture() -> (
    tempfile::TempDir,
    Config,
    Arc<pocket_tunnel::ServerIdentity>,
    Arc<Connection>,
) {
    let dir = tempfile::tempdir().unwrap();
    let c = Config::create_local().unwrap();
    c.save(dir.path()).unwrap();
    let identity = Arc::new(
        pocket_tunnel::ServerIdentity::load_or_create(dir.path().join("identity")).unwrap(),
    );
    let health = Arc::new(Connection::default());
    health.set("stopped");
    (dir, c, identity, health)
}

#[test]
fn automatic_lan_advertisement_survives_expired_or_stale_explicit_routes() {
    let mut prefs: RoutePreferences = serde_json::from_value(json!({
        "routes":[{"id":"lan","kind":"lan","url":"tls://192.168.1.2:43821","priority":0,"expiresAt":50}],
        "lan":{"bind":"192.168.1.3:43821","mdns":false}
    })).unwrap();
    let active=prefs.advertised_routes(100);
    assert_eq!(active.len(),1);
    assert_eq!(active[0].url,"tls://192.168.1.3:43821");
    prefs.routes[0].expires_at=None;
    let active=prefs.advertised_routes(100);
    assert_eq!(active.len(),2);
    assert_ne!(active[0].id,active[1].id);
    assert!(active.iter().any(|r|r.url=="tls://192.168.1.3:43821"));
    prefs.routes[0].url="tls://192.168.1.3:43821/".into();
    assert_eq!(prefs.advertised_routes(100)[0].url,"tls://192.168.1.3:43821");
}

#[tokio::test]
async fn automatic_lan_recovers_missing_network_and_preserves_identity_and_public_routes() {
    let (d,mut c,_,health)=fixture();
    c.route_preferences=Some(serde_json::from_value(json!({"autoLan":true,"policy":"automatic","routes":[
        {"id":"existing","kind":"relay","url":format!("wss://example.test/v1/tunnel/{}/phone",c.room),"priority":30}
    ]})).unwrap());
    c.save(d.path()).unwrap();
    let controller=routes::Controller::open(d.path().into(),c.clone(),health);
    assert!(!controller.refresh_auto_lan(None).await.unwrap());
    assert!(controller.refresh_auto_lan(Some("192.168.5.7:43821".parse().unwrap())).await.unwrap());
    let loaded=Config::load(d.path()).unwrap();
    assert_eq!(loaded.computer_id,c.computer_id);
    assert_eq!(loaded.room,c.room);
    assert_eq!(loaded.phone_token,c.phone_token);
    assert_eq!(loaded.preferences().routes,c.preferences().routes);
    assert_eq!(loaded.preferences().lan.unwrap().bind,"192.168.5.7:43821".parse().unwrap());
    assert!(!controller.refresh_auto_lan(Some("192.168.5.7:43821".parse().unwrap())).await.unwrap());
    let mut preferences=loaded.preferences();
    preferences.lan.as_mut().unwrap().bind.set_port(17891);
    preferences.lan.as_mut().unwrap().mdns=false;
    controller.configure(preferences).await.unwrap();
    assert!(controller.refresh_auto_lan(Some("192.168.8.9:43821".parse().unwrap())).await.unwrap());
    let next=Config::load(d.path()).unwrap().preferences();
    assert_eq!(next.lan.as_ref().unwrap().bind,"192.168.8.9:17891".parse().unwrap());
    assert!(!next.lan.as_ref().unwrap().mdns);
    assert!(!controller.refresh_auto_lan(Some("8.8.8.8:43821".parse().unwrap())).await.unwrap());
    let mut manual=next.clone();manual.auto_lan=false;
    controller.configure(manual.clone()).await.unwrap();
    assert!(!controller.refresh_auto_lan(Some("192.168.9.10:43821".parse().unwrap())).await.unwrap());
    assert_eq!(Config::load(d.path()).unwrap().preferences(),manual);
}

#[tokio::test]
async fn unchanged_configuration_keeps_workers_and_old_relay_generation_cannot_claim_new_address(){
    let (d,mut c,_,health)=fixture();
    let prefs:RoutePreferences=serde_json::from_value(json!({"routes":[{"id":"relay","kind":"relay","url":format!("wss://first.example/v1/tunnel/{}/phone",c.room),"priority":30}]})).unwrap();
    c.route_preferences=Some(prefs.clone());c.save(d.path()).unwrap();
    let controller=routes::Controller::open(d.path().into(),c,health.clone());
    let mut changes=controller.subscribe();
    let generation=changes.borrow_and_update().1;
    health.relay_evidence_at(generation,"relay",&prefs.routes[0].url,&json!({"state":"connected","connected":true}));
    controller.configure(prefs.clone()).await.unwrap();
    assert!(!changes.has_changed().unwrap());
    assert_eq!(health.route_snapshot()[0]["outerConnected"],true);
    let mut updated=prefs.clone();updated.routes[0].url=updated.routes[0].url.replace("first.example","second.example");
    controller.configure(updated).await.unwrap();
    assert!(changes.has_changed().unwrap());
    health.relay_evidence_at(generation,"relay",&prefs.routes[0].url,&json!({"state":"connected","connected":true}));
    health.route_phase_at(generation,"relay","connected",true,Some(prefs.routes[0].url.clone()));
    assert_eq!(health.route_snapshot()[0]["outerConnected"],false);
    assert_eq!(health.route_snapshot()[0]["phase"],"configured");
}

#[tokio::test]
async fn expiry_retires_health_but_cannot_replace_a_newer_configuration(){
    let (d,mut c,_,health)=fixture();
    let prefs:RoutePreferences=serde_json::from_value(json!({"routes":[{"id":"relay","kind":"relay","url":format!("wss://first.example/v1/tunnel/{}/phone",c.room),"priority":30,"expiresAt":1}]})).unwrap();
    c.route_preferences=Some(prefs.clone());c.save(d.path()).unwrap();
    let controller=routes::Controller::open(d.path().into(),c,health.clone());
    let mut changes=controller.subscribe();
    let generation=changes.borrow_and_update().1;
    health.relay_evidence_at(generation,"relay",&prefs.routes[0].url,&json!({"state":"connected","connected":true}));
    controller.refresh_expired(generation).await;
    assert!(health.route_snapshot().as_array().unwrap().is_empty());
    let next_generation=changes.borrow_and_update().1;
    assert_ne!(generation,next_generation);
    let mut newer=prefs;newer.routes[0].expires_at=None;
    controller.configure(newer).await.unwrap();
    let configured_generation=changes.borrow_and_update().1;
    controller.refresh_expired(next_generation).await;
    assert_eq!(changes.borrow().1,configured_generation);
}
async fn phone(
    address: std::net::SocketAddr,
    identity: &pocket_tunnel::ServerIdentity,
) -> tokio_rustls::client::TlsStream<TcpStream> {
    let tls = pocket_tunnel::pinned_client_config(
        identity.certificate_der(),
        &identity.certificate_sha256(),
    )
    .unwrap();
    tokio::time::timeout(
        Duration::from_secs(3),
        tokio_rustls::TlsConnector::from(Arc::new(tls)).connect(
            pocket_tunnel::SERVER_NAME.try_into().unwrap(),
            TcpStream::connect(address).await.unwrap(),
        ),
    )
    .await
    .unwrap()
    .unwrap()
}
async fn gateway_connect(
    address: std::net::SocketAddr,
    path: &str,
    token: Option<&str>,
) -> Result<
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<TcpStream>>,
    tokio_tungstenite::tungstenite::Error,
> {
    let mut request = format!("ws://{address}{path}")
        .into_client_request()
        .unwrap();
    if let Some(token) = token {
        request.headers_mut().insert(
            "authorization",
            HeaderValue::from_str(&format!("Bearer {token}")).unwrap(),
        );
    }
    tokio::time::timeout(Duration::from_secs(3), connect_async(request))
        .await
        .unwrap()
        .map(|(ws, _)| ws)
}

#[test]
fn v06_routes_reject_public_lan_duplicate_ids_and_management_urls() {
    let (_d, c, _identity, _h) = fixture();
    for url in ["tls://192.168.1.2:1234", "tls://[fd00::1]:1234"] {
        let p: RoutePreferences = serde_json::from_value(
            json!({"routes":[{"id":"x","kind":"lan","url":url,"priority":0}]}),
        )
        .unwrap();
        p.validate(&c.room).unwrap();
    }
    for url in [
        "tls://8.8.8.8:443",
        "tls://example.local:1234",
        "tls://0.0.0.0:1234",
        "tls://192.168.1.2:0",
        "tls://[::ffff:192.168.1.2]:1234",
        "tls://192.168.1.2:12?token=secret",
    ] {
        let p: RoutePreferences = serde_json::from_value(
            json!({"routes":[{"id":"x","kind":"lan","url":url,"priority":0}]}),
        )
        .unwrap();
        assert!(p.validate(&c.room).is_err(), "accepted {url}");
    }
    let route = json!({"id":"x","kind":"lan","url":"tls://192.168.1.2:1234","priority":0});
    let p: RoutePreferences =
        serde_json::from_value(json!({"routes":[route.clone(),route]})).unwrap();
    assert!(p.validate(&c.room).is_err());
    for bind in ["0.0.0.0:0", "192.168.1.2:8080", "[::]:0"] {
        let p: RoutePreferences = serde_json::from_value(json!({"gateway":{"bind":bind}})).unwrap();
        assert!(p.validate(&c.room).is_err());
    }
    let p:RoutePreferences=serde_json::from_value(json!({"routes":[{"id":"x","kind":"cloudflare","url":"wss://example.invalid/management","priority":0}]})).unwrap();
    assert!(p.validate(&c.room).is_err());
}

#[tokio::test]
async fn v06_lan_pinned_tls_frames_shutdown_and_no_fake_phone_online() {
    let (_d, c, identity, health) = fixture();
    let (tx, mut rx) = mpsc::channel(8);
    let server = lan::Server::start(
        LanConfig {
            bind: "127.0.0.1:0".parse().unwrap(),
            mdns: false,
        },
        identity.clone(),
        tx,
        health.clone(),
        c.computer_id,
    )
    .await
    .unwrap();
    assert_eq!(health.snapshot()["connected"], false);
    assert_eq!(health.snapshot()["available"], true);
    let address = server.local_addr();
    let mut client = phone(address, &identity).await;
    let mut session = tokio::time::timeout(Duration::from_secs(3), rx.recv())
        .await
        .unwrap()
        .unwrap();
    pocket_tunnel::write_json(
        &mut client,
        &json!({"id":"one","method":"hello","params":{}}),
    )
    .await
    .unwrap();
    assert_eq!(
        pocket_tunnel::read_json::<_, Value>(&mut session)
            .await
            .unwrap()
            .unwrap()["id"],
        "one"
    );
    assert_eq!(health.snapshot()["authenticatedPhone"], false);
    assert_eq!(health.snapshot()["connected"], false);
    let guard = health.authenticated_phone();
    assert_eq!(health.snapshot()["authenticatedPhone"], true);
    assert_eq!(health.snapshot()["connected"], true);
    drop(guard);
    assert_eq!(health.snapshot()["authenticatedPhone"], false);
    pocket_tunnel::write_json(&mut session, &json!({"id":"one","result":{"protocol":1}}))
        .await
        .unwrap();
    assert_eq!(
        pocket_tunnel::read_json::<_, Value>(&mut client)
            .await
            .unwrap()
            .unwrap()["result"]["protocol"],
        1
    );
    server.shutdown().await;
    assert!(matches!(
        tokio::time::timeout(
            Duration::from_secs(3),
            pocket_tunnel::read_json::<_, Value>(&mut session)
        )
        .await
        .unwrap(),
        Ok(None) | Err(_)
    ));
    assert!(TcpStream::connect(address).await.is_err());
    assert_eq!(health.snapshot()["available"], false);
}

#[tokio::test]
async fn v06_wrong_pin_rejected_and_two_sessions_are_independent() {
    let (_d, c, identity, health) = fixture();
    let (tx, mut rx) = mpsc::channel(8);
    let server = lan::Server::start(
        LanConfig {
            bind: "127.0.0.1:0".parse().unwrap(),
            mdns: false,
        },
        identity.clone(),
        tx,
        health,
        c.computer_id,
    )
    .await
    .unwrap();
    let tls =
        pocket_tunnel::pinned_client_config(identity.certificate_der(), &"00".repeat(32)).unwrap();
    assert!(
        tokio::time::timeout(
            Duration::from_secs(3),
            tokio_rustls::TlsConnector::from(Arc::new(tls)).connect(
                pocket_tunnel::SERVER_NAME.try_into().unwrap(),
                TcpStream::connect(server.local_addr()).await.unwrap()
            )
        )
        .await
        .unwrap()
        .is_err()
    );
    assert!(rx.try_recv().is_err());
    let mut a = phone(server.local_addr(), &identity).await;
    let mut sa = rx.recv().await.unwrap();
    let mut b = phone(server.local_addr(), &identity).await;
    let mut sb = rx.recv().await.unwrap();
    for (client, session, id) in [(&mut a, &mut sa, "a"), (&mut b, &mut sb, "b")] {
        pocket_tunnel::write_json(client, &json!({"id":id}))
            .await
            .unwrap();
        assert_eq!(
            pocket_tunnel::read_json::<_, Value>(session)
                .await
                .unwrap()
                .unwrap()["id"],
            id
        );
    }
    drop(a);
    drop(sa);
    pocket_tunnel::write_json(&mut b, &json!({"id":"still-live"}))
        .await
        .unwrap();
    assert_eq!(
        pocket_tunnel::read_json::<_, Value>(&mut sb)
            .await
            .unwrap()
            .unwrap()["id"],
        "still-live"
    );
    server.shutdown().await;
}

#[tokio::test]
async fn v06_gateway_rejects_tokens_paths_queries_and_never_proxies_control() {
    let (_d, c, identity, health) = fixture();
    let (tx, mut rx) = mpsc::channel(8);
    let server = gateway::Server::start(
        GatewayConfig {
            bind: "127.0.0.1:0".parse().unwrap(),
        },
        &c,
        identity,
        tx,
        health.clone(),
    )
    .await
    .unwrap();
    let path = format!("/v1/tunnel/{}/phone", c.room);
    for (path, token, status) in [
        (path.clone(), None, 401),
        (path.clone(), Some(c.helper_token.as_str()), 401),
        ("/control".into(), Some(c.phone_token.as_str()), 404),
        (
            "/v1/tunnel/wrong/phone".into(),
            Some(c.phone_token.as_str()),
            404,
        ),
        (
            format!("{path}?token={}", c.phone_token),
            Some(c.phone_token.as_str()),
            404,
        ),
        (
            format!("/v1/tunnel/{}/helper", c.room),
            Some(c.phone_token.as_str()),
            404,
        ),
    ] {
        let error = gateway_connect(server.local_addr(), &path, token)
            .await
            .unwrap_err();
        match error {
            tokio_tungstenite::tungstenite::Error::Http(response) => {
                assert_eq!(response.status().as_u16(), status)
            }
            _ => panic!("Expected HTTP rejection"),
        }
    }
    assert!(rx.try_recv().is_err());
    assert_eq!(health.snapshot()["connected"], false);
    assert_eq!(health.snapshot()["authenticatedPhone"], false);
    assert!(
        health
            .gateway_endpoint()
            .unwrap()
            .starts_with("http://127.0.0.1:")
    );
    server.shutdown().await;
}

#[tokio::test]
async fn v06_gateway_authenticated_upgrade_carries_same_pinned_inner_tls() {
    let (_d, c, identity, health) = fixture();
    let (tx, mut rx) = mpsc::channel(8);
    let server = gateway::Server::start(
        GatewayConfig {
            bind: "127.0.0.1:0".parse().unwrap(),
        },
        &c,
        identity.clone(),
        tx,
        health.clone(),
    )
    .await
    .unwrap();
    let ws = gateway_connect(
        server.local_addr(),
        &format!("/v1/tunnel/{}/phone", c.room),
        Some(&c.phone_token),
    )
    .await
    .unwrap();
    let (wire, bridge) = tokio::io::duplex(131072);
    let task = tokio::spawn(async move {
        let (mut source_read, mut source_write) = tokio::io::split(bridge);
        let (mut sink, mut source) = ws.split();
        let inbound = async {
            while let Some(Ok(message)) = source.next().await {
                match message {
                    Message::Binary(bytes) => {
                        if source_write.write_all(&bytes).await.is_err() {
                            break;
                        }
                    }
                    Message::Close(_) => break,
                    _ => {}
                }
            }
        };
        let outbound = async {
            let mut buffer = vec![0; 65536];
            while let Ok(n) = source_read.read(&mut buffer).await {
                if n == 0 {
                    break;
                }
                if sink
                    .send(Message::Binary(buffer[..n].to_vec().into()))
                    .await
                    .is_err()
                {
                    break;
                }
            }
        };
        tokio::pin!(inbound);
        tokio::pin!(outbound);
        tokio::select! {_=&mut inbound=>{},_=&mut outbound=>{}}
    });
    let tls = pocket_tunnel::pinned_client_config(
        identity.certificate_der(),
        &identity.certificate_sha256(),
    )
    .unwrap();
    let mut client = tokio::time::timeout(
        Duration::from_secs(3),
        tokio_rustls::TlsConnector::from(Arc::new(tls))
            .connect(pocket_tunnel::SERVER_NAME.try_into().unwrap(), wire),
    )
    .await
    .unwrap()
    .unwrap();
    let mut session = rx.recv().await.unwrap();
    pocket_tunnel::write_json(&mut client, &json!({"id":"gateway"}))
        .await
        .unwrap();
    assert_eq!(
        pocket_tunnel::read_json::<_, Value>(&mut session)
            .await
            .unwrap()
            .unwrap()["id"],
        "gateway"
    );
    assert_eq!(health.snapshot()["authenticatedPhone"], false);
    assert_eq!(health.snapshot()["connected"], false);
    server.shutdown().await;
    assert!(matches!(
        tokio::time::timeout(
            Duration::from_secs(3),
            pocket_tunnel::read_json::<_, Value>(&mut session)
        )
        .await
        .unwrap(),
        Ok(None) | Err(_)
    ));
    task.abort();
}

#[test]
fn v06_expired_routes_do_not_resurrect_legacy_relay_and_mdns_has_no_secrets() {
    let (_d, mut c, _identity, _health) = fixture();
    c.relay_url = "wss://old.invalid".into();
    c.route_preferences=Some(serde_json::from_value(json!({"policy":"lan","routes":[{"id":"cf","kind":"cloudflare","url":format!("wss://new.invalid/v1/tunnel/{}/phone",c.room),"priority":1,"expiresAt":1}]})).unwrap());
    assert!(c.routes().is_empty());
    let packet =
        lan::mdns_packet(&c.computer_id, "192.168.1.2:43821".parse().unwrap(), 120).unwrap();
    let text = String::from_utf8_lossy(&packet);
    assert!(text.contains(&c.computer_id));
    assert!(text.contains("port=43821"));
    assert!(text.contains("protocol=1"));
    for secret in [&c.helper_token, &c.phone_token, &c.room] {
        assert!(!text.contains(secret));
    }
    assert_eq!(c.preferences().policy, routes::RoutePolicy::Lan);
}

#[tokio::test]
async fn v06_local_control_requires_own_token_and_preserves_identity() {
    let (d, c, _identity, _health) = fixture();
    let _lock = pocket_agent::local::try_lock(d.path()).unwrap().unwrap();
    let (broker, _) = pocket_agent::broker::Broker::open(c.clone(), d.path().into()).unwrap();
    let server = pocket_agent::local::Server::start(d.path(), broker)
        .await
        .unwrap();
    let endpoint: Value =
        serde_json::from_slice(&std::fs::read(d.path().join("control.json")).unwrap()).unwrap();
    let request = json!({"token":c.phone_token,"method":"routes/configure","params":{"routePreferences":{"policy":"public"}}});
    let mut stream = TcpStream::connect((
        std::net::Ipv4Addr::LOCALHOST,
        endpoint["port"].as_u64().unwrap() as u16,
    ))
    .await
    .unwrap();
    let bytes = serde_json::to_vec(&request).unwrap();
    stream.write_u32(bytes.len() as u32).await.unwrap();
    stream.write_all(&bytes).await.unwrap();
    assert!(matches!(
        tokio::time::timeout(Duration::from_secs(3), stream.read_u32())
            .await
            .unwrap(),
        Err(_)
    ));
    let p = json!({"routes":[],"policy":"lan","lan":{"bind":"127.0.0.1:43822","mdns":false},"gateway":{"bind":"127.0.0.1:0"}});
    let result = pocket_agent::local::execute_with_params(
        d.path(),
        "routes/configure",
        None,
        json!({"routePreferences":p}),
    )
    .await
    .unwrap();
    assert_eq!(result["policy"], "lan");
    assert!(!result.to_string().contains(&c.phone_token));
    assert!(!result.to_string().contains(&c.helper_token));
    let loaded = Config::load(d.path()).unwrap();
    assert_eq!(loaded.computer_id, c.computer_id);
    assert_eq!(loaded.phone_token, c.phone_token);
    assert_eq!(loaded.helper_token, c.helper_token);
    assert_eq!(loaded.room, c.room);
    let value = routes::snapshot(&c, d.path());
    assert_eq!(value["policy"], "lan");
    assert_eq!(value["routes"][0]["kind"], "lan");
    drop(server);
}

#[tokio::test]
async fn v06_runtime_lan_only_reconfigure_and_shutdown_retire_listeners() {
    let (d, mut c, identity, health) = fixture();
    c.route_preferences = Some(RoutePreferences {
        lan: Some(LanConfig {
            bind: "127.0.0.1:0".parse().unwrap(),
            mdns: false,
        }),
        gateway: Some(GatewayConfig {
            bind: "127.0.0.1:0".parse().unwrap(),
        }),
        ..Default::default()
    });
    c.save(d.path()).unwrap();
    let controller = routes::Controller::open(d.path().into(), c.clone(), health.clone());
    let (tx, mut rx) = mpsc::channel(8);
    let runtime = routes::Runtime::start(controller.clone(), identity.clone(), tx);
    let endpoint = tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            if let Some(endpoint) = health.gateway_endpoint() {
                break endpoint;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    let lan_address = health
        .route_snapshot()
        .as_array()
        .unwrap()
        .iter()
        .find(|r| r["id"] == "lan")
        .unwrap()["endpoint"]
        .as_str()
        .unwrap()
        .trim_start_matches("tls://")
        .parse()
        .unwrap();
    let _phone = phone(lan_address, &identity).await;
    let mut stream = rx.recv().await.unwrap();
    let response = controller
        .configure(RoutePreferences::default())
        .await
        .unwrap();
    assert!(response.get("gatewayEndpoint").is_none());
    assert_eq!(response["connected"], false);
    assert!(matches!(
        tokio::time::timeout(
            Duration::from_secs(3),
            pocket_tunnel::read_json::<_, Value>(&mut stream)
        )
        .await
        .unwrap(),
        Ok(None) | Err(_)
    ));
    let gateway_address = endpoint
        .trim_start_matches("http://")
        .parse::<std::net::SocketAddr>()
        .unwrap();
    tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            if TcpStream::connect(gateway_address).await.is_err() {
                break;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(Config::load(d.path()).unwrap().computer_id, c.computer_id);
    assert_eq!(
        pocket_tunnel::ServerIdentity::load_or_create(d.path().join("identity"))
            .unwrap()
            .certificate_sha256(),
        identity.certificate_sha256()
    );
    runtime.shutdown().await;
    assert_eq!(health.snapshot()["available"], false);
    assert_eq!(health.snapshot()["state"], "stopped");
}

async fn local_request(
    d: &std::path::Path,
    token: &str,
    method: &str,
    params: Value,
) -> Option<Value> {
    let endpoint: Value =
        serde_json::from_slice(&std::fs::read(d.join("control.json")).unwrap()).unwrap();
    let mut stream = TcpStream::connect((
        std::net::Ipv4Addr::LOCALHOST,
        endpoint["port"].as_u64().unwrap() as u16,
    ))
    .await
    .unwrap();
    let bytes =
        serde_json::to_vec(&json!({"token":token,"method":method,"params":params})).unwrap();
    stream.write_u32(bytes.len() as u32).await.unwrap();
    stream.write_all(&bytes).await.unwrap();
    let Ok(length) = tokio::time::timeout(Duration::from_secs(3), stream.read_u32())
        .await
        .unwrap()
    else {
        return None;
    };
    let mut bytes = vec![0; length as usize];
    stream.read_exact(&mut bytes).await.unwrap();
    Some(serde_json::from_slice(&bytes).unwrap())
}

#[tokio::test]
async fn v06_session_auth_guard_requires_successful_hello_and_cleans_up_on_abort() {
    let (d, c, identity, _health) = fixture();
    let (broker, _) = pocket_agent::broker::Broker::open(c.clone(), d.path().into()).unwrap();
    broker.connection.set("idle");
    storage::atomic_write(&d.path().join("pairing.json"),&serde_json::to_vec(&json!({"tokenHash":pocket_agent::auth::hash("fixture-pair"),"expiresAt":storage::now()+60})).unwrap()).unwrap();
    let (tx, mut rx) = mpsc::channel(8);
    let listener = lan::Server::start(
        LanConfig {
            bind: "127.0.0.1:0".parse().unwrap(),
            mdns: false,
        },
        identity.clone(),
        tx,
        broker.connection.clone(),
        c.computer_id,
    )
    .await
    .unwrap();
    let mut client = phone(listener.local_addr(), &identity).await;
    let tls = rx.recv().await.unwrap();
    let task = tokio::spawn(pocket_agent::session::serve(tls, broker.clone()));
    assert_eq!(broker.connection.snapshot()["authenticatedPhone"], false);
    pocket_tunnel::write_json(&mut client,&json!({"id":"pair","method":"pair","params":{"pairingToken":"fixture-pair","deviceId":"phone","deviceName":"Fixture"}})).await.unwrap();
    let pair = pocket_tunnel::read_json::<_, Value>(&mut client)
        .await
        .unwrap()
        .unwrap();
    assert!(pair["result"]["deviceSecret"].is_string());
    assert_eq!(broker.connection.snapshot()["authenticatedPhone"], false);
    pocket_tunnel::write_json(&mut client,&json!({"id":"bad","method":"hello","params":{"protocol":1,"deviceId":"phone","deviceSecret":"wrong"}})).await.unwrap();
    assert!(
        pocket_tunnel::read_json::<_, Value>(&mut client)
            .await
            .unwrap()
            .unwrap()
            .get("error")
            .is_some()
    );
    assert_eq!(broker.connection.snapshot()["authenticatedPhone"], false);
    pocket_tunnel::write_json(&mut client,&json!({"id":"hello","method":"hello","params":{"protocol":1,"deviceId":"phone","deviceSecret":pair["result"]["deviceSecret"]}})).await.unwrap();
    assert!(
        pocket_tunnel::read_json::<_, Value>(&mut client)
            .await
            .unwrap()
            .unwrap()
            .get("result")
            .is_some()
    );
    assert_eq!(broker.connection.snapshot()["authenticatedPhone"], true);
    assert_eq!(broker.connection.snapshot()["connected"], true);
    task.abort();
    let _ = task.await;
    assert_eq!(broker.connection.snapshot()["authenticatedPhone"], false);
    assert!(matches!(
        tokio::time::timeout(
            Duration::from_secs(3),
            pocket_tunnel::read_json::<_, Value>(&mut client)
        )
        .await
        .unwrap(),
        Ok(None) | Err(_)
    ));
    listener.shutdown().await;
}

#[tokio::test]
async fn v06_local_preferences_need_only_local_auth_and_use_persisted_cas() {
    let (d, c, _identity, _health) = fixture();
    let _lock = pocket_agent::local::try_lock(d.path()).unwrap().unwrap();
    let (broker, _) = pocket_agent::broker::Broker::open(c.clone(), d.path().into()).unwrap();
    let server = pocket_agent::local::Server::start(d.path(), broker.clone())
        .await
        .unwrap();
    let endpoint: Value =
        serde_json::from_slice(&std::fs::read(d.path().join("control.json")).unwrap()).unwrap();
    let token = endpoint["token"].as_str().unwrap();
    let initial = local_request(d.path(), token, "session/preferences/read", json!({}))
        .await
        .unwrap();
    assert_eq!(
        initial,
        json!({"result":{"defaultMode":"desktop","revision":0}})
    );
    assert!(
        local_request(
            d.path(),
            &c.phone_token,
            "session/preferences/update",
            json!({"mode":"cli","knownRevision":0})
        )
        .await
        .is_none()
    );
    let update = local_request(
        d.path(),
        token,
        "session/preferences/update",
        json!({"mode":"cli","knownRevision":0}),
    )
    .await
    .unwrap();
    assert_eq!(update, json!({"result":{"defaultMode":"cli","revision":1}}));
    let stale = local_request(
        d.path(),
        token,
        "session/preferences/update",
        json!({"mode":"desktop","knownRevision":0,"commandId":"desktop-compat"}),
    )
    .await
    .unwrap();
    assert_eq!(stale["error"]["code"], "version_conflict");
    let error = pocket_agent::local::execute_with_params(
        d.path(),
        "session/preferences/update",
        None,
        json!({"mode":"desktop","knownRevision":0}),
    )
    .await
    .unwrap_err();
    assert_eq!(
        pocket_agent::error::code(&error),
        pocket_agent::error::Code::VersionConflict
    );
    for params in [
        json!({"mode":"cli"}),
        json!({"mode":"invalid","knownRevision":1}),
        json!({"mode":"desktop","knownRevision":1,"deviceId":"fake"}),
        json!({"mode":"desktop","knownRevision":-1}),
    ] {
        let reply = local_request(d.path(), token, "session/preferences/update", params)
            .await
            .unwrap();
        assert_eq!(reply["error"]["code"], "invalid_params");
    }
    let read = local_request(d.path(), token, "session/preferences/read", json!({}))
        .await
        .unwrap();
    assert_eq!(read["result"], update["result"]);
    assert!(!read.to_string().contains(&c.phone_token));
    drop(server);
    drop(broker);
    let (restored, _) =
        pocket_agent::broker::Broker::open(Config::load(d.path()).unwrap(), d.path().into())
            .unwrap();
    let server = pocket_agent::local::Server::start(d.path(), restored)
        .await
        .unwrap();
    let endpoint: Value =
        serde_json::from_slice(&std::fs::read(d.path().join("control.json")).unwrap()).unwrap();
    let read = local_request(
        d.path(),
        endpoint["token"].as_str().unwrap(),
        "session/preferences/read",
        json!({}),
    )
    .await
    .unwrap();
    assert_eq!(read["result"], update["result"]);
    drop(server);
}

#[test]
fn v06_offline_session_preferences_cli_keeps_revision_and_identity() {
    let (d, c, _identity, _health) = fixture();
    let file = d.path().join("preferences-update.json");
    storage::atomic_write(
        &file,
        &serde_json::to_vec(&json!({"mode":"cli","knownRevision":0})).unwrap(),
    )
    .unwrap();
    for action in ["configure", "preferences"] {
        let mut command = std::process::Command::new(env!("CARGO_BIN_EXE_pocket-agent"));
        command
            .args(["session", action, "--state"])
            .arg(d.path())
            .arg("--json");
        if action == "configure" {
            command.arg("--config").arg(&file);
        }
        let output = command.output().unwrap();
        assert!(output.status.success());
        assert!(output.stderr.is_empty());
        let value: Value = serde_json::from_slice(&output.stdout).unwrap();
        assert_eq!(value, json!({"defaultMode":"cli","revision":1}));
    }
    let output = std::process::Command::new(env!("CARGO_BIN_EXE_pocket-agent"))
        .args(["session", "configure", "--config"])
        .arg(&file)
        .arg("--state")
        .arg(d.path())
        .arg("--json")
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(output.stderr.is_empty());
    let reply: Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(reply["error"]["code"], "version_conflict");
    let restored = Config::load(d.path()).unwrap();
    assert_eq!(restored.computer_id, c.computer_id);
    assert_eq!(restored.phone_token, c.phone_token);
}

#[test]
fn v06_cli_routes_read_configure_and_session_generation_isolation() {
    let (d, c, _identity, health) = fixture();
    let file = d.path().join("route-update.json");
    storage::atomic_write(
        &file,
        &serde_json::to_vec(&json!({"routes":[],"policy":"public"})).unwrap(),
    )
    .unwrap();
    for action in ["configure", "read"] {
        let mut command = std::process::Command::new(env!("CARGO_BIN_EXE_pocket-agent"));
        command
            .args(["routes", action, "--state"])
            .arg(d.path())
            .arg("--json");
        if action == "configure" {
            command.arg("--config").arg(&file);
        }
        let output = command.output().unwrap();
        assert!(output.status.success());
        assert!(output.stderr.is_empty());
        let value: Value = serde_json::from_slice(&output.stdout).unwrap();
        assert_eq!(value["policy"], "public");
        assert_eq!(value["computerId"], c.computer_id);
        assert!(!String::from_utf8_lossy(&output.stdout).contains(&c.phone_token));
    }
    health.reset_routes(&[]);
    health.route_phase("lan", "listening", true, None);
    let previous = health.session_open("lan");
    health.reset_routes(&[]);
    health.route_phase("lan", "listening", true, None);
    let current = health.session_open("lan");
    health.session_close_generation("lan", previous);
    assert_eq!(health.route_snapshot()[0]["activeSessions"], 1);
    health.session_close_generation("lan", current);
    assert_eq!(health.route_snapshot()[0]["activeSessions"], 0);
}

#[test]
fn v06_lan_only_identity_and_old_config_migration() {
    let dir = tempfile::tempdir().unwrap();
    let old = Config::create("wss://example.invalid/pocket".into()).unwrap();
    let mut raw = serde_json::to_value(&old).unwrap();
    raw.as_object_mut().unwrap().remove("routePreferences");
    storage::atomic_write(
        &dir.path().join("config.json"),
        &serde_json::to_vec(&raw).unwrap(),
    )
    .unwrap();
    let loaded = Config::load(dir.path()).unwrap();
    let saved = serde_json::to_value(loaded).unwrap();
    assert_eq!(saved["computerId"], raw["computerId"]);
    assert_eq!(saved["helperToken"], raw["helperToken"]);
    assert_eq!(saved["phoneToken"], raw["phoneToken"]);
    assert_eq!(saved["routePreferences"]["routes"][0]["kind"], "relay");
    assert_eq!(
        saved["routePreferences"]["routes"][0]["url"],
        "wss://example.invalid/pocket/v1/tunnel/".to_owned()
            + raw["room"].as_str().unwrap()
            + "/phone"
    );
}

#[test]
fn v06_init_does_not_require_a_relay() {
    let dir = tempfile::tempdir().unwrap();
    let output = std::process::Command::new(env!("CARGO_BIN_EXE_pocket-agent"))
        .args(["init", "--state"])
        .arg(dir.path())
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "init should create identity without relay: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let value: Value =
        serde_json::from_slice(&std::fs::read(dir.path().join("config.json")).unwrap()).unwrap();
    assert_eq!(value["relayUrl"], "");
    assert!(value["computerId"].as_str().unwrap().len() > 16);
    assert!(dir.path().join("identity/certificate.der").exists());
    assert!(!dir.path().join("relay-config.json").exists());
    let pair = pocket_agent::local::pair_files(dir.path()).unwrap();
    let qr: Value =
        serde_json::from_slice(&std::fs::read(pair["payloadPath"].as_str().unwrap()).unwrap())
            .unwrap();
    assert_eq!(qr["v"], 1);
    assert_eq!(qr["computerId"], value["computerId"]);
    assert!(qr["routes"].is_array());
    for route in qr["routes"].as_array().unwrap() {
        assert_eq!(route["kind"], "lan");
        assert!(!route["url"].as_str().unwrap().contains("127.0.0.1"));
    }
}
