//! Managed Host attachment. No discovery launcher or phone RPC endpoint.
use super::*;
use anyhow::ensure;
use pocket_runtime_ipc::{Descriptor, HostClient, Receipt, load_descriptor};
use std::sync::atomic::AtomicU64;
use tokio::sync::{Mutex, Notify, broadcast, watch};

const REQUEST_CLIENTS: usize = 2;
const REPLAY_EVENTS: usize = 2048;
const REPLAY_BYTES: usize = 8 * 1024 * 1024;

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Cursor {
    pub generation: String,
    pub after: u64,
}

/// Attach an already-installed private descriptor. Never creates a host/native process.
/// The caller must register Connected, then call rpc.activate_events(). There is
/// deliberately no broker/phone discovery endpoint until installation supplies a
/// verified registry contract and broker command IDs are wired to call_with_id.
pub async fn attach(
    path: &Path,
    home: &Path,
    events: mpsc::Sender<NativeEvent>,
    cursor: Option<Cursor>,
) -> Result<Connected> {
    let descriptor = load_descriptor(path, home)?;
    let client = HostClient::attach(&descriptor).await?;
    let native = descriptor
        .native_process
        .as_ref()
        .context("Native identity unavailable")?;
    verify_native(native)?;
    let binary = native_binary(native.pid)?;
    let capabilities = crate::capabilities::discover(&binary).await?;
    verify_native(native)?; // A schema from a replaced PID is never accepted.
    connect(descriptor, client, capabilities, events, cursor).await
}

/// Read the original operation's receipt, without dispatching or changing IDs.
pub async fn receipt(path: &Path, home: &Path, id: Value) -> Result<Receipt> {
    let descriptor = load_descriptor(path, home)?;
    HostClient::attach(&descriptor).await?.receipt(id).await
}

fn verify_native(expected: &NativeProcess) -> Result<()> {
    ensure!(
        expected.pid != 0
            && expected.created_at != 0
            && NativeProcess::capture(expected.pid)? == *expected
            && !expected.terminated(),
        "Managed native identity mismatch"
    );
    Ok(())
}

fn accept(
    cursor: &mut Cursor,
    generation: &str,
    sequence: u64,
    value: Value,
) -> Result<Option<Value>> {
    ensure!(
        generation == cursor.generation,
        "Host event generation changed"
    );
    if sequence <= cursor.after {
        return Ok(None);
    }
    ensure!(
        cursor.after.checked_add(1) == Some(sequence),
        "Host event snapshot required"
    );
    cursor.after = sequence;
    Ok(value.get("method").is_some().then_some(value))
}

async fn replay(client: &mut HostClient, cursor: &mut Cursor) -> Result<Vec<(u64, Option<Value>)>> {
    let mut messages = Vec::new();
    let mut count = 0;
    let mut bytes = 0;
    for _ in 0..8 {
        let before = cursor.after;
        let page = client.replay(&cursor.generation, before).await?;
        ensure!(!page.snapshot_required, "Host event snapshot required");
        for event in page.events {
            count += 1;
            bytes += serde_json::to_vec(&event.event)?.len();
            ensure!(
                count <= REPLAY_EVENTS && bytes <= REPLAY_BYTES,
                "Host replay budget exceeded"
            );
            let next = event.sequence;
            let value = accept(cursor, &page.generation, next, event.event)?;
            messages.push((next, value));
        }
        ensure!(
            page.next_after == cursor.after && page.sequence >= cursor.after,
            "Invalid host replay cursor"
        );
        if !page.has_more {
            return Ok(messages);
        }
        ensure!(cursor.after > before, "Host replay made no progress");
    }
    bail!("Host replay budget exceeded")
}

async fn connect(
    descriptor: Descriptor,
    mut event_client: HostClient,
    capabilities: crate::capabilities::Capabilities,
    events: mpsc::Sender<NativeEvent>,
    cursor: Option<Cursor>,
) -> Result<Connected> {
    let mut subscription = event_client.subscribe(); // Exactly one event subscription, before replay.
    let health = event_client.health().await?;
    let mut cursor = cursor.unwrap_or_else(|| Cursor {
        generation: descriptor.generation.clone(),
        after: 0,
    });
    let committed = cursor.after;
    let initial = replay(&mut event_client, &mut cursor).await?;
    let mut requests = Vec::new();
    for _ in 0..REQUEST_CLIENTS {
        requests.push(HostClient::attach(&descriptor).await?);
    }
    let response_client = HostClient::attach(&descriptor).await?;
    let (tx, rx) = mpsc::channel(128);
    let (response_tx, response_rx) = mpsc::channel(64);
    let (stop, stopped) = watch::channel(false);
    let online = Arc::new(AtomicBool::new(true));
    let start = Arc::new(Notify::new());
    let sequence = Arc::new(AtomicU64::new(committed));
    let connection_id = uuid::Uuid::new_v4().to_string();
    let rx = Arc::new(Mutex::new(rx));
    for client in requests {
        tokio::spawn(request_worker(
            client,
            rx.clone(),
            events.clone(),
            descriptor.runtime_id.clone(),
            connection_id.clone(),
            stop.clone(),
            stopped.clone(),
        ));
    }
    tokio::spawn(response_worker(
        response_client,
        response_rx,
        stop.clone(),
        stopped.clone(),
        descriptor.generation.clone(),
    ));
    tokio::spawn({
        let online = online.clone();
        let sequence = sequence.clone();
        let start = start.clone();
        let stop = stop.clone();
        let runtime_id = descriptor.runtime_id.clone();
        let connection_id = connection_id.clone();
        async move {
            let mut stopped = stopped;
            let result: Result<()> = async {
                tokio::select! { _ = start.notified() => {}, _ = stopped.changed() => bail!("Host adapter detached") }
                let send = |value| NativeEvent::Message { runtime_id: runtime_id.clone(), connection_id: connection_id.clone(), value };
                for (next, value) in initial {
                    if let Some(value) = value {
                        tokio::select! { result = events.send(send(value)) => { result?; }, _ = stopped.changed() => bail!("Host adapter detached") }
                    }
                    sequence.fetch_max(next, Ordering::Release);
                }
                let mut heartbeat = tokio::time::interval(Duration::from_millis(500));
                loop {
                    tokio::select! {
                        _ = stopped.changed() => break,
                        _ = heartbeat.tick() => {
                            // Drains unrelated replies from this client's bounded incoming
                            // queue, without native/model calls or a second event consumer.
                            event_client.health().await?;
                        }
                        envelope = subscription.recv() => {
                            let mut messages = Vec::new();
                            match envelope {
                                Ok(value) if value["method"] == "host/event" => {
                                    let generation = value["params"]["generation"].as_str().context("Invalid host event generation")?;
                                    let next = value["params"]["sequence"].as_u64().context("Invalid host event sequence")?;
                                    let value = accept(&mut cursor, generation, next, value["params"]["event"].clone())?;
                                    messages.push((next, value));
                                }
                                Ok(value) if value["method"] == "host/resync" => { messages = replay(&mut event_client, &mut cursor).await?; }
                                Err(broadcast::error::RecvError::Lagged(_)) => { messages = replay(&mut event_client, &mut cursor).await?; }
                                _ => bail!("Host event connection unavailable"),
                            }
                            for (next, value) in messages {
                                if let Some(value) = value {
                                    tokio::select! { result = events.send(send(value)) => { result?; }, _ = stopped.changed() => bail!("Host adapter detached") }
                                }
                                sequence.fetch_max(next, Ordering::Release);
                            }
                        }
                    }
                }
                Ok(())
            }.await;
            online.store(false, Ordering::Release);
            stop.send_replace(true);
            if result.is_err() {
                let _ = events.try_send(NativeEvent::Message { runtime_id: runtime_id.clone(), connection_id: connection_id.clone(),
                    value: json!({"method":"pocket/managedHostSnapshotRequired","params":{"reason":"event_connection_unavailable"}}) });
            }
            let _ = events.try_send(NativeEvent::Disconnected {
                runtime_id,
                connection_id,
            });
            // Dropping this client only closes IPC. No shutdown/kill/reissue.
        }
    });
    Ok(Connected {
        role: NativeRole::ManagedHost {
            generation: descriptor.generation.clone(),
            reconciliation_required: health.reconciliation_required,
        },
        bridge_version: None,
        capabilities,
        rpc: Rpc {
            tx,
            response_tx: Some(response_tx),
            host_stop: Some(stop),
            host_event_start: Some(start),
            host_cursor: Some((descriptor.generation.clone(), sequence)),
            online,
            capability_failed: false,
        },
        native_process: descriptor.native_process,
        runtime_id: descriptor.runtime_id,
        connection_id,
        config_home: descriptor.config_home,
        source: "cli".into(),
        version: "unknown".into(),
    })
}

async fn request_worker(
    mut client: HostClient,
    rx: Arc<Mutex<mpsc::Receiver<Outgoing>>>,
    events: mpsc::Sender<NativeEvent>,
    runtime_id: String,
    connection_id: String,
    stop: watch::Sender<bool>,
    mut stopped: watch::Receiver<bool>,
) {
    loop {
        let output = tokio::select! { _ = stopped.changed() => break, output = async { rx.lock().await.recv().await } => output };
        let Some(Outgoing::Request { value, mut reply }) = output else {
            break;
        };
        let result = tokio::select! {
            _ = stopped.changed() => Err(RpcFailure::Disconnected),
            _ = reply.closed() => { stop.send_replace(true); break; },
            result = client.request(value["id"].clone(), value["method"].as_str().unwrap_or(""), value["params"].clone()) => {
                match result {
                    Ok(value) => if let Some(error) = value.get("error") { Err(RpcFailure::Native(error.clone())) } else { Ok(value["result"].clone()) },
                    Err(_) => { stop.send_replace(true); Err(RpcFailure::Timeout) }, // Outcome unknown; never resubmit.
                }
            }
        };
        if let Ok(result) = result {
            if value["method"] == "thread/read" || value["method"] == "thread/turns/list" {
                if events
                    .send(NativeEvent::Snapshot {
                        runtime_id: runtime_id.clone(),
                        connection_id: connection_id.clone(),
                        value: result,
                        reply,
                    })
                    .await
                    .is_err()
                {
                    break;
                }
            } else {
                let _ = reply.send(Ok(result));
            }
        } else {
            let _ = reply.send(result);
        }
        if *stopped.borrow() {
            break;
        }
    }
    stop.send_replace(true);
    let mut rx = rx.lock().await;
    rx.close();
    while let Ok(output) = rx.try_recv() {
        if let Outgoing::Request { reply, .. } = output {
            let _ = reply.send(Err(RpcFailure::Disconnected));
        }
    }
}

async fn response_worker(
    mut client: HostClient,
    mut rx: mpsc::Receiver<Outgoing>,
    stop: watch::Sender<bool>,
    mut stopped: watch::Receiver<bool>,
    generation: String,
) {
    loop {
        let output =
            tokio::select! { _ = stopped.changed() => break, output = rx.recv() => output };
        let Some(Outgoing::Response { value, flushed }) = output else {
            break;
        };
        let result = tokio::select! {
            _ = stopped.changed() => Err(RpcFailure::Disconnected),
            result = client.respond(value["id"].clone(), json!({"result":value["result"]})) => {
                match result {
                    Ok(receipt) if receipt.generation == generation && receipt.state == "completed" => Ok(()),
                    _ => { stop.send_replace(true); Err(RpcFailure::Timeout) },
                }
            }
        };
        let _ = flushed.send(result);
        if *stopped.borrow() {
            break;
        }
    }
    stop.send_replace(true);
    rx.close();
    while let Ok(output) = rx.try_recv() {
        if let Outgoing::Response { flushed, .. } = output {
            let _ = flushed.send(Err(RpcFailure::Disconnected));
        }
    }
}

#[cfg(test)]
async fn attach_fixture(
    path: &Path,
    home: &Path,
    events: mpsc::Sender<NativeEvent>,
) -> Result<Connected> {
    let descriptor = load_descriptor(path, home)?;
    let client = HostClient::attach(&descriptor).await?;
    verify_native(descriptor.native_process.as_ref().unwrap())?;
    let capabilities = crate::capabilities::Capabilities::from_schema(
        &serde_json::from_str(include_str!(
            "../../bridge/tests/fixtures/ClientRequest.json"
        ))
        .unwrap(),
    );
    connect(descriptor, client, capabilities, events, None).await
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    use pocket_runtime_ipc::{Descriptor, HostClient, load_descriptor};
    use std::process::Stdio;

    struct Fixture {
        _dir: tempfile::TempDir,
        home: PathBuf,
        descriptor: PathBuf,
        child: tokio::process::Child,
        identity: Descriptor,
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            // Panic cleanup addresses only this test's own authenticated fixture.
            // Never kill a process or inspect an external registry.
            use std::io::Write;
            if self.child.try_wait().ok().flatten().is_some() {
                return;
            }
            if let Ok(mut stream) = std::net::TcpStream::connect_timeout(
                &std::net::SocketAddr::from(([127, 0, 0, 1], self.identity.port)),
                Duration::from_secs(1),
            ) {
                let _ = stream.set_write_timeout(Some(Duration::from_secs(1)));
                for value in [
                    json!({"token":self.identity.token,"generation":self.identity.generation}),
                    json!({"id":"fixture-panic-cleanup","method":"host/request","params":{"generation":self.identity.generation,
                        "request":{"id":"fixture-panic-cleanup","method":"fixture/exit","params":{}}}}),
                ] {
                    if let Ok(mut bytes) = serde_json::to_vec(&value) {
                        bytes.push(b'\n');
                        let _ = stream.write_all(&bytes);
                    }
                }
            }
        }
    }
    impl Fixture {
        async fn start() -> Self {
            let dir = tempfile::tempdir().unwrap();
            let home = dir.path().join("home");
            std::fs::create_dir(&home).unwrap();
            let descriptor = dir.path().join("state/runtime-host/registry/fixture.json");
            let bins = Path::new(env!("CARGO_MANIFEST_DIR")).join("../runtime-host/target/debug");
            let host = bins.join("pocket-runtime-host.exe");
            let native = bins.join("pocket-host-fixture.exe");
            assert!(
                host.is_file() && native.is_file(),
                "build the scoped Runtime Host --bins before these integration tests"
            );
            let mut command = tokio::process::Command::new(host);
            command
                .args(["--state"])
                .arg(dir.path().join("host-state"))
                .arg("--descriptor")
                .arg(&descriptor)
                .arg("--binary")
                .arg(native)
                .arg("--config-home")
                .arg(&home)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .creation_flags(0x08000000);
            let child = command.spawn().unwrap();
            let identity = tokio::time::timeout(Duration::from_secs(10), async {
                loop {
                    if let Ok(d) = load_descriptor(&descriptor, &home) {
                        if HostClient::attach(&d).await.is_ok() {
                            break d;
                        }
                    }
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            })
            .await
            .unwrap();
            Self {
                _dir: dir,
                home,
                descriptor,
                child,
                identity,
            }
        }
        async fn exit(mut self) {
            let mut control = HostClient::attach(&self.identity).await.unwrap();
            let _ = control
                .request(json!("fixture-exit"), "fixture/exit", json!({}))
                .await;
            assert!(
                tokio::time::timeout(Duration::from_secs(10), self.child.wait())
                    .await
                    .unwrap()
                    .unwrap()
                    .success()
            );
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn managed_attachment_returns_native_connected_without_launching_another_native() {
        let fixture = Fixture::start().await;
        let (events, _rx) = mpsc::channel(32);
        let result = attach_fixture(&fixture.descriptor, &fixture.home, events).await;
        let succeeded = result.is_ok();
        if let Ok(connected) = result {
            assert!(matches!(connected.role, NativeRole::ManagedHost { .. }));
            assert!(connected.capabilities.supports(
                "thread/read",
                &json!({"threadId":"fixture-thread","includeTurns":false})
            ));
            assert_eq!(connected.native_process, fixture.identity.native_process);
            assert_eq!(connected.runtime_id, fixture.identity.runtime_id);
            drop(connected);
        }
        fixture.exit().await;
        assert!(
            succeeded,
            "Managed Host must return the real Connected adapter"
        );
    }

    async fn question(rx: &mut mpsc::Receiver<NativeEvent>) -> Value {
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                if let Some(NativeEvent::Message { value, .. }) = rx.recv().await {
                    if value["method"] == "item/commandExecution/requestApproval" {
                        break value;
                    }
                }
            }
        })
        .await
        .unwrap()
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn replay_waits_for_registration_and_delivers_one_pending_question() {
        let fixture = Fixture::start().await;
        let mut control = HostClient::attach(&fixture.identity).await.unwrap();
        control
            .request(json!("early-question"), "fixture/question", json!({}))
            .await
            .unwrap();
        let (events, mut rx) = mpsc::channel(32);
        let connected = attach_fixture(&fixture.descriptor, &fixture.home, events)
            .await
            .unwrap();
        assert!(
            rx.try_recv().is_err(),
            "replay must wait until runtime registration"
        );
        assert_eq!(
            connected.rpc.managed_host_cursor().unwrap().after,
            0,
            "the reconnect cursor must not acknowledge replay before it is queued to the consumer"
        );
        connected.rpc.activate_events();
        let event = question(&mut rx).await;
        assert_eq!(event["id"], 77);
        connected
            .rpc
            .respond(event["id"].clone(), json!({"decision":"decline"}))
            .await
            .unwrap();
        let count = connected
            .rpc
            .call("fixture/count", json!({}))
            .await
            .unwrap();
        assert_eq!(count["answers"], 1);
        while let Ok(event) = rx.try_recv() {
            if let NativeEvent::Message { value, .. } = event {
                assert_ne!(
                    value["method"], "item/commandExecution/requestApproval",
                    "event/replay overlap must not duplicate approvals"
                );
            }
        }
        connected.rpc.detach_managed_host();
        drop(connected);
        drop(control);
        fixture.exit().await;
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn human_response_uses_independent_client_and_unknown_id_is_never_reissued() {
        let fixture = Fixture::start().await;
        let (events, mut rx) = mpsc::channel(64);
        let connected = attach_fixture(&fixture.descriptor, &fixture.home, events)
            .await
            .unwrap();
        connected.rpc.activate_events();
        let rpc = connected.rpc.clone();
        let pending = tokio::spawn(async move {
            rpc.call_with_id(json!("stable-deferred"), "fixture/deferred", json!({}))
                .await
        });
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                let evidence =
                    receipt(&fixture.descriptor, &fixture.home, json!("stable-deferred"))
                        .await
                        .unwrap();
                if evidence.state == "unknown" {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        connected
            .rpc
            .call("fixture/question", json!({}))
            .await
            .unwrap();
        let event = question(&mut rx).await;
        assert!(!pending.is_finished());
        tokio::time::timeout(
            Duration::from_secs(2),
            connected
                .rpc
                .respond(event["id"].clone(), json!({"decision":"decline"})),
        )
        .await
        .unwrap()
        .unwrap();
        assert!(
            !pending.is_finished(),
            "approval must complete while the original call is still pending"
        );
        let count = connected
            .rpc
            .call("fixture/count", json!({}))
            .await
            .unwrap();
        assert_eq!(count, json!({"writes":1,"answers":1}));
        let saved_cursor = connected.rpc.managed_host_cursor().unwrap();
        connected.rpc.detach_managed_host();
        assert!(pending.await.unwrap().is_err());
        tokio::time::timeout(Duration::from_secs(2), async {
            while connected.rpc.online() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        drop(connected);
        let (events, _rx) = mpsc::channel(64);
        let descriptor = load_descriptor(&fixture.descriptor, &fixture.home).unwrap();
        let client = HostClient::attach(&descriptor).await.unwrap();
        let capabilities = crate::capabilities::Capabilities::from_schema(
            &serde_json::from_str(include_str!(
                "../../bridge/tests/fixtures/ClientRequest.json"
            ))
            .unwrap(),
        );
        let reattached = connect(descriptor, client, capabilities, events, Some(saved_cursor))
            .await
            .unwrap();
        assert_eq!(reattached.native_process, fixture.identity.native_process);
        reattached.rpc.activate_events();
        let result = reattached
            .rpc
            .call_with_id(json!("stable-deferred"), "fixture/deferred", json!({}))
            .await;
        assert!(matches!(
            result,
            Err(RpcFailure::Timeout) | Err(RpcFailure::Disconnected)
        ));
        let mut control = HostClient::attach(&fixture.identity).await.unwrap();
        let count = control
            .request(json!("after-reattach-count"), "fixture/count", json!({}))
            .await
            .unwrap();
        assert_eq!(
            count["result"],
            json!({"writes":1,"answers":1}),
            "unknown stable ID must not dispatch twice"
        );
        assert_eq!(
            receipt(&fixture.descriptor, &fixture.home, json!("stable-deferred"))
                .await
                .unwrap()
                .generation,
            fixture.identity.generation
        );
        reattached.rpc.detach_managed_host();
        drop(reattached);
        drop(control);
        fixture.exit().await;
    }

    #[test]
    fn event_cursor_deduplicates_and_rejects_gaps_and_changed_generations() {
        let mut cursor = Cursor {
            generation: "current".into(),
            after: 4,
        };
        assert!(
            accept(&mut cursor, "current", 4, json!({"method":"duplicate"}))
                .unwrap()
                .is_none()
        );
        assert!(accept(&mut cursor, "current", 6, json!({"method":"gap"})).is_err());
        assert_eq!(cursor.after, 4);
        assert!(accept(&mut cursor, "old", 5, json!({"method":"wrong"})).is_err());
        assert_eq!(cursor.after, 4);
        assert!(
            accept(&mut cursor, "current", 5, json!({"method":"next"}))
                .unwrap()
                .is_some()
        );
        assert_eq!(cursor.after, 5);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn production_schema_failure_does_not_publish_a_connected_stub() {
        let fixture = Fixture::start().await;
        let (events, _rx) = mpsc::channel(8);
        // The owned native fixture accepts stdio but does not export native schema.
        // Production attach must reject it rather than borrowing a version's schema.
        assert!(
            attach(&fixture.descriptor, &fixture.home, events, None)
                .await
                .is_err()
        );
        let mut wrong = fixture.identity.native_process.clone().unwrap();
        wrong.created_at += 1;
        assert!(verify_native(&wrong).is_err());
        fixture.exit().await;
    }
}
