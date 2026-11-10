//! Real DataChannel + existing Pocket TLS + existing device authentication.
//! Loopback fixture only: no model, public STUN or production state.
use pocket_agent::{auth::DeviceStore, broker::Broker, config::Config};
use pocket_p2p::{DataChannelControl, DataChannelStream, Signal};
use pocket_tunnel::{SERVER_NAME, ServerIdentity, pinned_client_config, read_json, write_json};
use serde_json::{Value, json};
use std::{sync::Arc, time::Duration};

async fn peers() -> (
    DataChannelStream,
    DataChannelStream,
    tokio::task::JoinHandle<()>,
) {
    let (tx, mut rx) = tokio::sync::mpsc::channel(256);
    let make = |index| {
        let tx = tx.clone();
        DataChannelStream::new(9, Some("127.0.0.1"), move |signal| {
            let _ = tx.try_send((index, signal));
        })
        .unwrap()
    };
    let (a, ca) = make(0usize);
    let (b, cb) = make(1usize);
    let controls: [DataChannelControl; 2] = [cb.clone(), ca.clone()];
    let task = tokio::spawn(async move {
        while let Some((index, signal)) = rx.recv().await {
            let result = match signal {
                Signal::Description {
                    generation,
                    sdp,
                    kind,
                } => controls[index].remote_description(generation, &sdp, &kind),
                Signal::Candidate {
                    generation,
                    candidate,
                    mid,
                } => controls[index].remote_candidate(generation, &candidate, &mid),
            };
            if result.is_err() {
                break;
            }
        }
    });
    cb.start(false).unwrap();
    ca.start(true).unwrap();
    tokio::time::timeout(Duration::from_secs(10), async {
        ca.opened().await.unwrap();
        cb.opened().await.unwrap();
    })
    .await
    .unwrap();
    (a, b, task)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn original_pin_and_device_hello_are_required_over_datachannel() {
    let root = tempfile::tempdir().unwrap();
    let identity = Arc::new(ServerIdentity::load_or_create(root.path().join("identity")).unwrap());
    let state = root.path().join("agent");
    pocket_agent::storage::private_directory(&state).unwrap();
    let mut devices = DeviceStore::default();
    devices
        .add(
            "fixture-phone".into(),
            "Fixture".into(),
            "fixture-secret",
            1,
        )
        .unwrap();
    pocket_agent::storage::atomic_write(
        &state.join("devices.json"),
        &serde_json::to_vec(&devices).unwrap(),
    )
    .unwrap();
    let config = Config::create("wss://fixture.invalid/pocket".into()).unwrap();
    let computer = config.computer_id.clone();
    let (broker, _) = Broker::open(config, state).unwrap();
    for (valid_pin, valid_device) in [(true, true), (true, false), (false, true)] {
        let (a, b, signals) = peers().await;
        let server = tokio::spawn({
            let identity = identity.clone();
            let broker = broker.clone();
            async move {
                if let Ok(tls) = identity.accept_tls(b).await {
                    pocket_agent::session::serve(tls, broker).await.ok();
                }
            }
        });
        let pin = if valid_pin {
            identity.certificate_sha256()
        } else {
            "00".repeat(32)
        };
        // The same verifier and framed business protocol used by LAN and relay.
        let client = pinned_client_config(identity.certificate_der(), &pin);
        if !valid_pin {
            if let Ok(config) = client {
                assert!(
                    tokio_rustls::TlsConnector::from(Arc::new(config))
                        .connect(
                            rustls::pki_types::ServerName::try_from(SERVER_NAME).unwrap(),
                            a
                        )
                        .await
                        .is_err()
                );
            }
        } else {
            let mut tls = tokio_rustls::TlsConnector::from(Arc::new(client.unwrap()))
                .connect(
                    rustls::pki_types::ServerName::try_from(SERVER_NAME).unwrap(),
                    a,
                )
                .await
                .unwrap();
            write_json(&mut tls,&json!({"id":"hello","method":"hello","params":{"protocol":1,"deviceId":"fixture-phone","deviceSecret":if valid_device{"fixture-secret"}else{"wrong"}}})).await.unwrap();
            let hello: Value = tokio::time::timeout(Duration::from_secs(3), read_json(&mut tls))
                .await
                .unwrap()
                .unwrap()
                .unwrap();
            if valid_device {
                assert_eq!(hello["result"]["computerId"], computer);
                write_json(&mut tls, &json!({"id":"ping","method":"ping","params":{}}))
                    .await
                    .unwrap();
                let ping: Value = tokio::time::timeout(Duration::from_secs(3), read_json(&mut tls))
                    .await
                    .unwrap()
                    .unwrap()
                    .unwrap();
                assert_eq!(ping["result"]["ok"], true);
            } else {
                assert!(hello.get("error").is_some());
                write_json(&mut tls, &json!({"id":"ping","method":"ping","params":{}}))
                    .await
                    .unwrap();
                let denied: Value = read_json(&mut tls).await.unwrap().unwrap();
                assert_eq!(denied["error"]["code"], "unauthorized");
            }
        }
        server.abort();
        signals.abort();
        let _ = server.await;
        let _ = signals.await;
    }
}
