use anyhow::{Context, Result, bail};
use codex_cli_adapter::{Discovery, JsonRpcConnection, Limits, LocalStream};
use pocket_core::native_process::NativeProcess;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader, Lines},
    net::{
        TcpStream,
        tcp::{OwnedReadHalf, OwnedWriteHalf},
    },
    sync::{mpsc, oneshot},
};

#[path = "native_host.rs"]
pub mod host;

#[derive(Debug, Clone)]
pub enum RpcFailure {
    Native(Value),
    Disconnected,
    Timeout,
}
impl std::fmt::Display for RpcFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Native(v) => write!(f, "Native runtime rejected request: {v}"),
            Self::Disconnected => write!(f, "Runtime disconnected"),
            Self::Timeout => write!(f, "Native result unknown (timeout)"),
        }
    }
}
impl std::error::Error for RpcFailure {}
pub type Reply = oneshot::Sender<std::result::Result<Value, RpcFailure>>;
enum Outgoing {
    Request { value: Value, reply: Reply },
    Response { value: Value, flushed: oneshot::Sender<std::result::Result<(),RpcFailure>> },
}
#[derive(Clone)]
pub struct Rpc {
    tx: mpsc::Sender<Outgoing>,
    response_tx: Option<mpsc::Sender<Outgoing>>,
    host_stop: Option<tokio::sync::watch::Sender<bool>>,
    host_event_start: Option<Arc<tokio::sync::Notify>>,
    host_cursor: Option<(String, Arc<std::sync::atomic::AtomicU64>)>,
    online: Arc<AtomicBool>,
    capability_failed: bool,
}
#[derive(Debug)]
pub enum NativeEvent {
    PendingSnapshot {
        runtime_id: String,
        connection_id: String,
        value: Value,
        reply: Reply,
    },
    Message {
        runtime_id: String,
        connection_id: String,
        value: Value,
    },
    Snapshot {
        runtime_id: String,
        connection_id: String,
        value: Value,
        reply: Reply,
    },
    Disconnected {
        runtime_id: String,
        connection_id: String,
    },
}
pub struct Connected {
    pub role: NativeRole,
    pub bridge_version: Option<String>,
    pub capabilities: crate::capabilities::Capabilities,
    pub rpc: Rpc,
    pub native_process: Option<NativeProcess>,
    pub runtime_id: String,
    pub connection_id: String,
    pub config_home: PathBuf,
    pub source: String,
    pub version: String,
}

/// Native discovery source stays compatible; the owning runtime role is explicit.
#[derive(Clone, Debug, serde::Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum NativeRole {
    Desktop,
    CliDaemon,
    ManagedHost {
        generation: String,
        #[serde(rename = "reconciliationRequired")]
        reconciliation_required: bool,
    },
}

enum Connection {
    Desktop {
        reader: Lines<BufReader<OwnedReadHalf>>,
        writer: OwnedWriteHalf,
    },
    Cli(Box<JsonRpcConnection<LocalStream>>),
}
impl Connection {
    async fn read(&mut self) -> Result<Option<Value>> {
        match self {
            Self::Desktop { reader, .. } => match reader.next_line().await? {
                Some(s) => {
                    if s.len() > 16 * 1024 * 1024 {
                        bail!("Oversized native RPC")
                    };
                    Ok(Some(serde_json::from_str(&s)?))
                }
                None => Ok(None),
            },
            Self::Cli(c) => Ok(c.read().await?),
        }
    }
    async fn write(&mut self, v: &Value) -> Result<()> {
        match self {
            Self::Desktop { writer, .. } => {
                let mut b = serde_json::to_vec(v)?;
                b.push(b'\n');
                writer.write_all(&b).await?;
                writer.flush().await?;
                Ok(())
            }
            Self::Cli(c) => Ok(c.write(v).await?),
        }
    }
}
impl Rpc {
    pub fn capability_failed(&self) -> bool {
        self.capability_failed
    }
    pub fn online(&self) -> bool {
        self.online.load(Ordering::Acquire)
    }
    pub async fn call(
        &self,
        method: &str,
        params: Value,
    ) -> std::result::Result<Value, RpcFailure> {
        self.call_with_id(json!(uuid::Uuid::new_v4().to_string()), method, params).await
    }
    /// Dispatch with the caller's durable operation ID. Never replace it after an unknown outcome.
    pub async fn call_with_id(
        &self,
        id: Value,
        method: &str,
        params: Value,
    ) -> std::result::Result<Value, RpcFailure> {
        if pocket_runtime_ipc::support::key(&id).is_none() {
            return Err(RpcFailure::Native(json!({"message":"Invalid operation ID"})));
        }
        let (tx, rx) = oneshot::channel();
        let value = json!({"id":id,"method":method,"params":params});
        self.tx
            .send(Outgoing::Request { value, reply: tx })
            .await
            .map_err(|_| RpcFailure::Disconnected)?;
        tokio::time::timeout(Duration::from_secs(90), rx)
            .await
            .map_err(|_| RpcFailure::Timeout)?
            .map_err(|_| RpcFailure::Disconnected)?
    }
    pub async fn respond(&self, id: Value, response: Value) -> std::result::Result<(), RpcFailure> {
        let (flushed, receiver)=oneshot::channel();
        self.response_tx.as_ref().unwrap_or(&self.tx)
            .send(Outgoing::Response{value:json!({"id":id,"result":response}),flushed})
            .await
            .map_err(|_| RpcFailure::Disconnected)?;
        tokio::time::timeout(Duration::from_secs(10),receiver).await
            .map_err(|_|RpcFailure::Timeout)?.map_err(|_|RpcFailure::Disconnected)?
    }

    /// The broker calls this after storing Connected, so replay cannot race registration.
    pub fn activate_events(&self) {
        if let Some(start) = &self.host_event_start { start.notify_one(); }
    }
    /// Detach IPC only. This does not stop the host or its native process.
    pub fn detach_managed_host(&self) {
        if let Some(stop) = &self.host_stop { stop.send_replace(true); }
    }
    pub fn managed_host_cursor(&self) -> Option<host::Cursor> {
        self.host_cursor.as_ref().map(|(generation, sequence)| host::Cursor {
            generation: generation.clone(), after: sequence.load(Ordering::Acquire),
        })
    }
}
fn spawn(
    mut connection: Connection,
    runtime_id: String,
    connection_id: String,
    events: mpsc::Sender<NativeEvent>,
) -> Rpc {
    let (tx, mut rx) = mpsc::channel(128);
    let online = Arc::new(AtomicBool::new(true));
    let flag = online.clone();
    tokio::spawn(async move {
        let mut pending = HashMap::<String, (Reply, tokio::time::Instant, bool, bool)>::new();
        let mut sweep = tokio::time::interval(Duration::from_secs(15));
        loop {
            tokio::select! {
             event=connection.read()=>match event{
              Ok(Some(v))=>{if v.get("method").is_some(){if events.send(NativeEvent::Message{runtime_id:runtime_id.clone(),connection_id:connection_id.clone(),value:v}).await.is_err(){break}}else if let Some(id)=pocket_runtime_ipc::support::key(&v["id"])&& let Some((reply,_,snapshot,approvals))=pending.remove(&id){if (snapshot||approvals)&&v.get("error").is_none(){let event=if approvals{NativeEvent::PendingSnapshot{runtime_id:runtime_id.clone(),connection_id:connection_id.clone(),value:v["result"].clone(),reply}}else{NativeEvent::Snapshot{runtime_id:runtime_id.clone(),connection_id:connection_id.clone(),value:v["result"].clone(),reply}};if events.send(event).await.is_err(){break}}else{let _=reply.send(if let Some(e)=v.get("error"){Err(RpcFailure::Native(e.clone()))}else{Ok(v["result"].clone())});}}},
              _=>break,
             },
             output=rx.recv()=>match output{
              Some(Outgoing::Request{value,reply})=>{let id=pocket_runtime_ipc::support::key(&value["id"]).unwrap();if pending.len()>=128||pending.contains_key(&id){let _=reply.send(Err(RpcFailure::Native(json!({"message":"Native operation already pending or capacity exceeded"}))));continue}let snapshot=value["method"]=="thread/read"||value["method"]=="thread/turns/list";let approvals=value["method"]=="pocket/approvals/list";pending.insert(id,(reply,tokio::time::Instant::now(),snapshot,approvals));if connection.write(&value).await.is_err(){break}},
              Some(Outgoing::Response{value,flushed})=>{let result=connection.write(&value).await.map_err(|_|RpcFailure::Disconnected);let failed=result.is_err();let _=flushed.send(result);if failed{break}},
              None=>break,
             },
             _=sweep.tick()=>{pending.retain(|_,(reply,at,_,_)|!reply.is_closed()&&at.elapsed()<Duration::from_secs(100));},
            }
        }
        flag.store(false, Ordering::Release);
        for (_, (reply, _, _, _)) in pending {
            let _ = reply.send(Err(RpcFailure::Disconnected));
        }
        let _ = events
            .send(NativeEvent::Disconnected {
                runtime_id,
                connection_id,
            })
            .await;
    });
    Rpc {
        tx,
        response_tx: None,
        host_stop: None,
        host_event_start: None,
        host_cursor: None,
        online,
        capability_failed: false,
    }
}
pub async fn desktop(descriptor: &Path, events: mpsc::Sender<NativeEvent>) -> Result<Connected> {
    let bytes = tokio::fs::read(descriptor).await?;
    if bytes.len() > 16384 {
        bail!("Invalid runtime descriptor")
    };
    let v: Value = serde_json::from_slice(&bytes)?;
    let runtime_id = v["runtimeId"].as_str().context("runtime ID")?.to_owned();
    let port = u16::try_from(v["port"].as_u64().context("port")?)?;
    let stream = tokio::time::timeout(
        Duration::from_secs(3),
        TcpStream::connect(("127.0.0.1", port)),
    )
    .await??;
    let (read, write) = stream.into_split();
    let mut connection = Connection::Desktop {
        reader: BufReader::new(read).lines(),
        writer: write,
    };
    connection.write(&json!({"token":v["token"]})).await?;
    let connection_id = uuid::Uuid::new_v4().to_string();
    let rpc = spawn(
        connection,
        runtime_id.clone(),
        connection_id.clone(),
        events,
    );
    let status = rpc.call("pocket/status", json!({})).await?;
    if status["runtimeId"].as_str() != Some(runtime_id.as_str()) {
        bail!("Desktop runtime identity mismatch");
    }
    let native_process: Option<NativeProcess> = status
        .get("nativeProcess")
        .filter(|v| !v.is_null())
        .map(|v| serde_json::from_value(v.clone()))
        .transpose()?;
    if native_process
        .as_ref()
        .is_some_and(|p| p.pid == 0 || p.created_at == 0)
    {
        bail!("Invalid native process identity");
    }
    // Keep the authenticated runtime visible while discovery initializes.
    // Permission reads refresh pocket/status before claiming native support.
    let capabilities = status
        .get("nativeCapabilities")
        .and_then(|v| serde_json::from_value(v.clone()).ok())
        .unwrap_or_default();
    let version = status["nativeVersion"]
        .as_str()
        .unwrap_or("unknown")
        .to_owned();
    Ok(Connected {
        role: NativeRole::Desktop,
        bridge_version: status["bridgeVersion"].as_str().map(str::to_owned),
        capabilities,
        rpc,
        native_process,
        runtime_id,
        connection_id,
        config_home: PathBuf::from(v["configHome"].as_str().context("config home")?),
        source: "desktop".into(),
        version,
    })
}
pub async fn cli(home: &Path, events: mpsc::Sender<NativeEvent>) -> Result<Connected> {
    let discovery = Discovery::for_home(home)?;
    let mut connection = codex_cli_adapter::connect(&discovery, Limits::default()).await?;
    #[cfg(windows)]
    let native_process = Some(NativeProcess::capture(connection.peer_process_id()?)?);
    #[cfg(not(windows))]
    let native_process: Option<NativeProcess> = None;
    connection.write(&json!({"id":"pocket-init","method":"initialize","params":{"clientInfo":{"name":"codex_pocket","title":"Codex Pocket","version":"0.1.0"},"capabilities":{"experimentalApi":true}}})).await?;
    let identity = tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            let value = connection
                .read()
                .await?
                .context("Daemon closed during initialize")?;
            if value["id"] == "pocket-init" {
                if value.get("error").is_some() {
                    bail!("Native daemon initialization rejected")
                };
                return Ok::<_, anyhow::Error>(
                    discovery.verify_initialize_result(&value["result"])?,
                );
            }
        }
    })
    .await??;
    let discovered = match native_process
        .as_ref()
        .and_then(|p| native_binary(p.pid).ok())
    {
        Some(binary) => crate::capabilities::discover(&binary).await,
        None => Err(anyhow::anyhow!("Native schema identity unavailable")),
    };
    let capability_failed = discovered.is_err();
    let capabilities = discovered.unwrap_or_default();
    connection.write(&json!({"method":"initialized"})).await?;
    let generation = if let Some(process) = &native_process {
        format!("{}:{}", process.pid, process.created_at)
    } else {
        std::fs::metadata(discovery.socket_path())?
            .modified()?
            .duration_since(std::time::UNIX_EPOCH)?
            .as_nanos()
            .to_string()
    };
    let store = crate::storage::config_store(home)?;
    let runtime_id = format!(
        "cli-{}",
        &crate::auth::hash(&format!("{store}:{generation}"))[..24]
    );
    let connection_id = uuid::Uuid::new_v4().to_string();
    let mut rpc = spawn(
        Connection::Cli(Box::new(connection)),
        runtime_id.clone(),
        connection_id.clone(),
        events,
    );
    rpc.capability_failed = capability_failed;
    Ok(Connected {
        role: NativeRole::CliDaemon,
        bridge_version: None,
        capabilities,
        rpc,
        native_process,
        runtime_id,
        connection_id,
        config_home: discovery.codex_home().to_owned(),
        source: "cli".into(),
        version: identity.version,
    })
}

#[cfg(windows)]
fn native_binary(pid: u32) -> Result<PathBuf> {
    use std::{ffi::c_void, os::windows::ffi::OsStringExt};
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn OpenProcess(access: u32, inherit: i32, pid: u32) -> *mut c_void;
        fn QueryFullProcessImageNameW(
            process: *mut c_void,
            flags: u32,
            buffer: *mut u16,
            size: *mut u32,
        ) -> i32;
        fn CloseHandle(handle: *mut c_void) -> i32;
    }
    let handle = unsafe { OpenProcess(0x1000, 0, pid) };
    if handle.is_null() {
        bail!("Native executable identity unavailable");
    }
    let mut buffer = vec![0u16; 32768];
    let mut size = buffer.len() as u32;
    let ok = unsafe { QueryFullProcessImageNameW(handle, 0, buffer.as_mut_ptr(), &mut size) };
    unsafe { CloseHandle(handle) };
    if ok == 0 {
        bail!("Native executable identity unavailable");
    }
    Ok(PathBuf::from(std::ffi::OsString::from_wide(
        &buffer[..size as usize],
    )))
}
#[cfg(not(windows))]
fn native_binary(_pid: u32) -> Result<PathBuf> {
    bail!("Native executable identity unavailable")
}

// Loopback peer exercises the real JSON framing, response routing and event task.
#[cfg(test)]
pub(crate) mod test_peer {
    use super::*;
    pub struct Peer {
        reader: Lines<BufReader<OwnedReadHalf>>,
        writer: OwnedWriteHalf,
        online: Arc<AtomicBool>,
    }
    pub async fn connect(
        id: &str,
        source: &str,
        home: &Path,
        events: mpsc::Sender<NativeEvent>,
    ) -> (Connected, Peer) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (server, _) = listener.accept().await.unwrap();
        let (read, write) = stream.into_split();
        let connection_id = uuid::Uuid::new_v4().to_string();
        let rpc = spawn(
            Connection::Desktop {
                reader: BufReader::new(read).lines(),
                writer: write,
            },
            id.into(),
            connection_id.clone(),
            events,
        );
        let online = rpc.online.clone();
        let (read, write) = server.into_split();
        (
            Connected {
                role: if source == "desktop" { NativeRole::Desktop } else { NativeRole::CliDaemon },
                bridge_version: Some(env!("CARGO_PKG_VERSION").into()),
                capabilities: crate::capabilities::Capabilities::from_schema(
                    &serde_json::from_str(include_str!(
                        "../../bridge/tests/fixtures/ClientRequest.json"
                    ))
                    .unwrap(),
                ),
                rpc,
                native_process: None,
                runtime_id: id.into(),
                connection_id,
                config_home: home.into(),
                source: source.into(),
                version: "test".into(),
            },
            Peer {
                reader: BufReader::new(read).lines(),
                writer: write,
                online,
            },
        )
    }
    impl Peer {
        pub async fn assert_idle(&mut self) {
            assert!(
                tokio::time::timeout(Duration::from_millis(30), self.reader.next_line())
                    .await
                    .is_err(),
                "unexpected native dispatch"
            );
        }
        pub async fn recv(&mut self) -> Value {
            let line = tokio::time::timeout(Duration::from_secs(3), self.reader.next_line())
                .await
                .expect("native request timeout")
                .unwrap()
                .expect("peer closed");
            serde_json::from_str(&line).unwrap()
        }
        pub async fn send(&mut self, value: Value) {
            let mut bytes = serde_json::to_vec(&value).unwrap();
            bytes.push(b'\n');
            self.writer.write_all(&bytes).await.unwrap();
        }
        pub async fn reply(&mut self, request: &Value, result: Value) {
            self.send(json!({"id":request["id"],"result":result})).await;
        }
        pub fn offline(&self) {
            // Model an old task's queued completion after its online flag clears.
            self.online.store(false, Ordering::Release);
        }
    }

    #[tokio::test]
    async fn explicit_numeric_operation_id_is_preserved_and_pending_duplicate_is_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let (events, _rx) = mpsc::channel(8);
        let (connected, mut peer) = connect("stable-rpc", "cli", dir.path(), events).await;
        let rpc = connected.rpc.clone();
        let first = tokio::spawn(async move { rpc.call_with_id(json!(17), "fixture/count", json!({})).await });
        let request = peer.recv().await;
        assert_eq!(request["id"], 17);
        assert!(matches!(connected.rpc.call_with_id(json!(17), "fixture/count", json!({})).await, Err(RpcFailure::Native(_))));
        peer.assert_idle().await;
        peer.reply(&request, json!({"count":1})).await;
        assert_eq!(first.await.unwrap().unwrap(), json!({"count":1}));
    }

    #[cfg(windows)]
    #[tokio::test]
    async fn authenticated_status_uses_native_identity_not_descriptor_pid() {
        let dir = tempfile::tempdir().unwrap();
        let native = NativeProcess::capture(std::process::id()).unwrap();
        for matching_runtime in [true, false] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let path = dir.path().join("runtime.json");
            std::fs::write(
                &path,
                serde_json::to_vec(&json!({
                    "runtimeId":"bridge-test", "port":listener.local_addr().unwrap().port(),
                    "pid":4, "token":"synthetic-token", "configHome":dir.path()
                }))
                .unwrap(),
            )
            .unwrap();
            let (events, _rx) = mpsc::channel(8);
            let task = tokio::spawn(async move { desktop(&path, events).await });
            let (stream, _) = listener.accept().await.unwrap();
            let (reader, mut writer) = stream.into_split();
            let mut lines = BufReader::new(reader).lines();
            let auth: Value =
                serde_json::from_str(&lines.next_line().await.unwrap().unwrap()).unwrap();
            assert_eq!(auth["token"], "synthetic-token");
            let status: Value =
                serde_json::from_str(&lines.next_line().await.unwrap().unwrap()).unwrap();
            assert_eq!(status["method"], "pocket/status");
            let reply = json!({"id":status["id"],"result":{
                "runtimeId":if matching_runtime {"bridge-test"}else{"wrong-bridge"},
                "initialized":true, "nativeVersion":"test", "nativeProcess":native
            }});
            writer
                .write_all(format!("{reply}\n").as_bytes())
                .await
                .unwrap();
            let result = task.await.unwrap();
            if matching_runtime {
                assert_eq!(result.unwrap().native_process, Some(native.clone()));
            } else {
                assert!(result.is_err());
            }
        }
    }
}
