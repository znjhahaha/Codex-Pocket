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

struct ReplayBatch {
    messages: Vec<(u64, Option<Value>)>,
    reconciliation_required: bool,
}

async fn replay(
    client: &mut HostClient,
    cursor: &mut Cursor,
    fresh_attachment: bool,
) -> Result<ReplayBatch> {
    let mut messages = Vec::new();
    let mut count = 0;
    let mut bytes = 0;
    for page_index in 0..8 {
        let before = cursor.after;
        let page = client.replay(&cursor.generation, before).await?;
        if page.snapshot_required {
            // A new observer has no applied event history. The same actor reply
            // supplies an atomic cursor and live pending snapshot, even when a
            // cold Host retained only durable evidence rather than event bodies.
            // Explicit handoff cursors and running observers must never skip gaps.
            ensure!(
                fresh_attachment && page_index == 0 && before == 0,
                "Host event snapshot required"
            );
            ensure!(
                page.generation == cursor.generation
                    && page.next_after == page.sequence
                    && !page.has_more
                    && page.events.is_empty()
                    && page.pending_requests.len() <= 128
                    && serde_json::to_vec(&page.pending_requests)?.len() <= 1024 * 1024,
                "Invalid host recovery snapshot"
            );
            let mut ids = std::collections::BTreeSet::new();
            for request in page.pending_requests {
                let id = pocket_runtime_ipc::support::key(&request["id"])
                    .context("Invalid pending host request")?;
                ensure!(
                    ids.insert(id)
                        && request["method"]
                            .as_str()
                            .is_some_and(|method| !method.is_empty()),
                    "Invalid pending host request"
                );
                messages.push((0, Some(request)));
            }
            // Acknowledge the baseline only after ALL pending requests are queued.
            // Unknown receipts/runs and old-generation questions remain in the
            // Host ledger and are reconciled by their existing owners, never replayed.
            messages.push((page.sequence, None));
            cursor.after = page.sequence;
            return Ok(ReplayBatch {
                messages,
                reconciliation_required: page.reconciliation_required,
            });
        }
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
            return Ok(ReplayBatch {
                messages,
                reconciliation_required: page.reconciliation_required,
            });
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
    event_client.health().await?;
    let fresh_attachment = cursor.is_none();
    let mut cursor = cursor.unwrap_or_else(|| Cursor {
        generation: descriptor.generation.clone(),
        after: 0,
    });
    let committed = cursor.after;
    let initial = replay(&mut event_client, &mut cursor, fresh_attachment).await?;
    let reconciliation_required = initial.reconciliation_required;
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
                for (next, value) in initial.messages {
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
                                Ok(value) if value["method"] == "host/resync" => { messages = replay(&mut event_client, &mut cursor, false).await?.messages; }
                                Err(broadcast::error::RecvError::Lagged(_)) => { messages = replay(&mut event_client, &mut cursor, false).await?.messages; }
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
            reconciliation_required,
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
            // The native sentinel also works when recovery correctly fences RPC.
            if self.child.try_wait().ok().flatten().is_some() {
                return;
            }
            let _ = std::fs::write(self.home.join("native-exit"), b"exit");
            for _ in 0..100 {
                if self.child.try_wait().ok().flatten().is_some() {
                    return;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
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
            Self::start_in(dir).await
        }
        async fn start_in(dir: tempfile::TempDir) -> Self {
            let home = dir.path().to_path_buf();
            let descriptor = dir.path().join("registry/host.json");
            let bins = Path::new(env!("CARGO_MANIFEST_DIR")).join("../runtime-host/target/debug");
            let host = bins.join("pocket-runtime-host.exe");
            let native = bins.join("pocket-host-fixture.exe");
            assert!(
                host.is_file() && native.is_file(),
                "build the scoped Runtime Host --bins before these integration tests"
            );
            // The fixture driver runs the real Host in its own process; the product
            // executable requires the managed launcher's persisted reservation.
            let mut command = tokio::process::Command::new(native);
            command
                .arg("--host-test-driver")
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
        async fn cold_restart(mut self) -> Self {
            // Stop only this fixture's native through its own sentinel. A Host
            // command here would itself leave an unrelated unknown receipt.
            std::fs::write(self.home.join("native-exit"), b"exit").unwrap();
            assert!(
                tokio::time::timeout(Duration::from_secs(10), self.child.wait())
                    .await
                    .unwrap()
                    .unwrap()
                    .success()
            );
            std::fs::remove_file(self.home.join("native-exit")).unwrap();
            let dir = std::mem::replace(&mut self._dir, tempfile::tempdir().unwrap());
            Self::start_in(dir).await
        }
        async fn exit(mut self) {
            std::fs::write(self.home.join("native-exit"), b"exit").unwrap();
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

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn fresh_attachment_recovers_nonzero_cold_sequence_without_reissuing_history() {
        let fixture = Fixture::start().await;
        let old_generation = fixture.identity.generation.clone();
        let mut control = HostClient::attach(&fixture.identity).await.unwrap();
        control
            .request(json!("before-cold-count"), "fixture/count", json!({}))
            .await
            .unwrap();
        drop(control);
        let fixture = fixture.cold_restart().await;
        assert_ne!(fixture.identity.generation, old_generation);
        let mut control = HostClient::attach(&fixture.identity).await.unwrap();
        let before = control.recovery().await.unwrap();
        assert!(before["sequence"].as_u64().unwrap() > 0);
        assert!(
            control
                .replay(&fixture.identity.generation, 0)
                .await
                .unwrap()
                .snapshot_required
        );
        assert!(!control.health().await.unwrap().reconciliation_required);
        let (events, mut rx) = mpsc::channel(32);
        let result = attach_fixture(&fixture.descriptor, &fixture.home, events).await;
        let connected = result
            .unwrap_or_else(|e| panic!("fresh cold attachment must use current snapshot: {e:#}"));
        assert!(rx.try_recv().is_err());
        connected.rpc.activate_events();
        tokio::time::timeout(Duration::from_secs(3), async {
            while connected.rpc.managed_host_cursor().unwrap().after
                < before["sequence"].as_u64().unwrap()
            {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert!(
            rx.try_recv().is_err(),
            "completed history must not become new native events"
        );
        assert_eq!(
            control.recovery().await.unwrap(),
            before,
            "attachment must not mutate the Host ledger"
        );
        let count = connected
            .rpc
            .call("fixture/count", json!({}))
            .await
            .unwrap();
        assert_eq!(count, json!({"writes":0,"answers":0}));
        let receipt = control.receipt(json!("before-cold-count")).await.unwrap();
        assert_eq!(receipt.state, "completed");
        assert_eq!(receipt.generation, old_generation);
        assert!(receipt.response.is_none());
        connected.rpc.detach_managed_host();
        drop(connected);
        drop(control);
        fixture.exit().await;
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn cold_attachment_preserves_unknown_evidence_and_explicit_cursor_fences() {
        let fixture = Fixture::start().await;
        let old_generation = fixture.identity.generation.clone();
        let mut control = HostClient::attach(&fixture.identity).await.unwrap();
        control
            .request(json!("old-turn"), "turn/start", json!({}))
            .await
            .unwrap();
        control
            .request(
                json!("old-question"),
                "fixture/private-question",
                json!({"requestId":78}),
            )
            .await
            .unwrap();
        let mut deferred_client = HostClient::attach(&fixture.identity).await.unwrap();
        let deferred = tokio::spawn(async move {
            deferred_client
                .request(json!("old-unknown"), "fixture/deferred", json!({}))
                .await
        });
        tokio::time::timeout(Duration::from_secs(3), async {
            while control.receipt(json!("old-unknown")).await.unwrap().state != "unknown" {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        deferred.abort();
        let _ = deferred.await;
        drop(control);
        let fixture = fixture.cold_restart().await;
        let mut control = HostClient::attach(&fixture.identity).await.unwrap();
        let before = control.recovery().await.unwrap();
        assert_eq!(before["pendingRequests"][0]["state"], "unknown");
        assert_eq!(before["pendingRequests"][0]["generation"], old_generation);
        assert_eq!(before["runs"][0]["state"], "unknown");
        assert_eq!(
            control.receipt(json!("old-unknown")).await.unwrap().state,
            "unknown"
        );
        for cursor in [
            Cursor {
                generation: old_generation,
                after: before["sequence"].as_u64().unwrap(),
            },
            Cursor {
                generation: fixture.identity.generation.clone(),
                after: 0,
            },
            Cursor {
                generation: fixture.identity.generation.clone(),
                after: u64::MAX,
            },
        ] {
            let (events, _) = mpsc::channel(32);
            let client = HostClient::attach(&fixture.identity).await.unwrap();
            let result = connect(
                fixture.identity.clone(),
                client,
                fixture_capabilities(),
                events,
                Some(cursor),
            )
            .await;
            assert!(
                result.is_err(),
                "an explicit cursor must never skip missing history"
            );
        }
        let (events, mut rx) = mpsc::channel(32);
        let connected = attach_fixture(&fixture.descriptor, &fixture.home, events)
            .await
            .unwrap();
        assert!(matches!(
            connected.role,
            NativeRole::ManagedHost {
                reconciliation_required: true,
                ..
            }
        ));
        connected.rpc.activate_events();
        tokio::time::timeout(Duration::from_secs(3), async {
            while connected.rpc.managed_host_cursor().unwrap().after
                < before["sequence"].as_u64().unwrap()
            {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert!(
            rx.try_recv().is_err(),
            "old unknown questions are not current approvals"
        );
        assert_eq!(control.recovery().await.unwrap(), before);
        assert!(
            control
                .request(json!("old-turn"), "turn/start", json!({}))
                .await
                .is_err()
        );
        let mut control = HostClient::attach(&fixture.identity).await.unwrap();
        assert!(
            control
                .request(json!("old-unknown"), "fixture/deferred", json!({}))
                .await
                .is_err()
        );
        let mut control = HostClient::attach(&fixture.identity).await.unwrap();
        assert_eq!(
            control.receipt(json!("old-unknown")).await.unwrap().state,
            "unknown"
        );
        assert_eq!(
            control.receipt(json!("old-turn")).await.unwrap().state,
            "completed"
        );
        let count = control
            .request(
                json!("new-count"),
                "thread/read",
                json!({"threadId":"fixture-thread","includeTurns":true}),
            )
            .await
            .unwrap();
        assert_eq!(count["result"]["writes"], 0);
        assert_eq!(count["result"]["answers"], 0);
        connected.rpc.detach_managed_host();
        drop(connected);
        drop(control);
        fixture.exit().await;
    }

    fn fixture_capabilities() -> crate::capabilities::Capabilities {
        crate::capabilities::Capabilities::from_schema(
            &serde_json::from_str(include_str!(
                "../../bridge/tests/fixtures/ClientRequest.json"
            ))
            .unwrap(),
        )
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn cold_pending_snapshot_commits_after_all_requests_and_preserves_later_events() {
        let fixture = Fixture::start().await;
        let mut control = HostClient::attach(&fixture.identity).await.unwrap();
        control
            .request(json!("cold-sequence"), "fixture/count", json!({}))
            .await
            .unwrap();
        drop(control);
        let fixture = fixture.cold_restart().await;
        let mut control = HostClient::attach(&fixture.identity).await.unwrap();
        for id in [77, 78] {
            control
                .request(
                    json!(format!("pending-{id}")),
                    "fixture/private-question",
                    json!({"requestId":id}),
                )
                .await
                .unwrap();
        }
        let baseline = control.recovery().await.unwrap()["sequence"]
            .as_u64()
            .unwrap();
        let (events, mut rx) = mpsc::channel(1);
        let connected = attach_fixture(&fixture.descriptor, &fixture.home, events)
            .await
            .unwrap();
        assert!(rx.try_recv().is_err());
        assert_eq!(connected.rpc.managed_host_cursor().unwrap().after, 0);
        control
            .request(json!("after-snapshot"), "fixture/finish", json!({}))
            .await
            .unwrap();
        connected.rpc.activate_events();
        tokio::time::timeout(Duration::from_secs(3), async {
            while rx.is_empty() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert_eq!(
            connected.rpc.managed_host_cursor().unwrap().after,
            0,
            "second pending request is still blocked behind the first"
        );
        let mut received = Vec::new();
        tokio::time::timeout(Duration::from_secs(3), async {
            while received.len() < 3 {
                if let Some(NativeEvent::Message { value, .. }) = rx.recv().await {
                    received.push(value);
                }
            }
            while connected.rpc.managed_host_cursor().unwrap().after <= baseline {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert_eq!(received[0]["id"], 77);
        assert_eq!(received[1]["id"], 78);
        assert_eq!(received[2]["method"], "turn/completed");
        assert!(
            rx.try_recv().is_err(),
            "snapshot/live overlap must not duplicate pending requests"
        );
        connected.rpc.detach_managed_host();
        drop(connected);
        drop(control);
        fixture.exit().await;
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
