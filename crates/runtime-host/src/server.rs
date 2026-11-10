//! Pocket-owned stdio host. Losing a broker connection never drops the child.
//! The broker remains responsible for user authorization and durable operation IDs.
use crate::{
    protocol::{Capabilities, Descriptor, PROTOCOL, Receipt, key, read_frame, write_frame},
    recovery::Store,
};
use anyhow::{Context, Result, bail, ensure};
use fs2::FileExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{path::PathBuf, process::Stdio, sync::Arc, time::Duration};
use subtle::ConstantTimeEq;
use tokio::{
    io::{AsyncWriteExt, BufReader},
    net::{TcpListener, TcpStream},
    sync::{broadcast, mpsc},
};

pub struct HostConfig {
    pub state: PathBuf,
    pub descriptor: PathBuf,
    pub binary: PathBuf,
    pub config_home: PathBuf,
    /// Production uses [app-server, --listen, stdio://]. Fixtures use a fake subprocess.
    pub native_args: Vec<String>,
}

struct Reply {
    value: Value,
    flushed: Option<tokio::sync::oneshot::Sender<()>>,
}
fn deliver(sender: &mpsc::Sender<Reply>, value: Value) {
    let _ = sender.try_send(Reply {
        value,
        flushed: None,
    });
}
enum Event {
    Native(Value),
    NativeClosed,
    Client(Value, mpsc::Sender<Reply>),
}
fn fail(id: Value, code: &str) -> Value {
    json!({"id":id,"error":{"code":code,"message":"Runtime host request unavailable"}})
}
async fn connection(
    socket: TcpStream,
    token: Arc<String>,
    generation: String,
    actor: mpsc::Sender<Event>,
    mut events: broadcast::Receiver<Value>,
) -> Result<()> {
    let (read, mut write) = socket.into_split();
    let mut read = BufReader::new(read);
    let auth = tokio::time::timeout(Duration::from_secs(3), read_frame(&mut read))
        .await??
        .context("Missing host authentication")?;
    let supplied = auth["token"].as_str().unwrap_or("");
    ensure!(
        supplied.len() == token.len() && bool::from(supplied.as_bytes().ct_eq(token.as_bytes())),
        "Host authentication refused"
    );
    ensure!(
        auth.get("generation")
            .is_none_or(|g| g == &json!(generation)),
        "Host generation refused"
    );
    let (out, mut replies) = mpsc::channel(32);
    let receiver = tokio::spawn(async move {
        while let Ok(Some(value)) = read_frame(&mut read).await {
            if actor.send(Event::Client(value, out.clone())).await.is_err() {
                break;
            }
        }
    });
    struct Reader(tokio::task::AbortHandle);
    impl Drop for Reader {
        fn drop(&mut self) {
            self.0.abort();
        }
    }
    let _reader = Reader(receiver.abort_handle());
    loop {
        tokio::select! {
            value=replies.recv()=>match value{Some(value)=>{write_frame(&mut write,&value.value).await?;if let Some(ack)=value.flushed{let _=ack.send(());}},None=>break},
            event=events.recv()=>match event {
                Ok(value)=>write_frame(&mut write,&value).await?,
                Err(broadcast::error::RecvError::Lagged(_))=>write_frame(&mut write,&json!({"method":"host/resync","params":{}})).await?,
                Err(_)=>break,
            }
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
async fn actor_loop(
    listener: TcpListener,
    token: Arc<String>,
    descriptor: &Descriptor,
    initialized: Value,
    writer: &mut tokio::process::ChildStdin,
    store: &mut Store,
    events: mpsc::Sender<Event>,
    mut incoming: mpsc::Receiver<Event>,
    broadcast: broadcast::Sender<Value>,
    clients: &mut tokio::task::JoinSet<Result<()>>,
) -> Result<bool> {
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let (socket,address)=accepted?;
                if address.ip().is_loopback() && clients.len()<8 {
                    clients.spawn(connection(socket,token.clone(),descriptor.generation.clone(),events.clone(),broadcast.subscribe()));
                }
            },
            _ = clients.join_next(), if !clients.is_empty() => {},
            event = incoming.recv() => match event {
                Some(Event::Native(value)) => {
                    let envelope=store.state.push(value)?;
                    store.save()?; // Native receipt is durable before publication to a broker.
                    let _=broadcast.send(envelope);
                },
                Some(Event::NativeClosed)|None => {
                    for run in store.state.runs.values_mut().filter(|r|r.generation==descriptor.generation && r.state=="active") {run.state="unknown".into();store.state.reconciliation_required=true;}
                    store.state.reconciliation_required |= store.state.busy();
                    store.save()?;
                    return Ok(false);
                },
                Some(Event::Client(value,reply)) => {
                    let id=value["id"].clone();
                    let Some(command_key)=key(&id) else {deliver(&reply,fail(id,"invalid_request"));continue;};
                    let method=value["method"].as_str().unwrap_or("");
                    if value["params"].get("generation").is_some_and(|g|g!=&json!(descriptor.generation)) && method!="host/replay" {
                        deliver(&reply,fail(id,"generation_mismatch"));continue;
                    }
                    match method {
                        "host/status" => {
                            deliver(&reply,json!({"id":id,"result":{"protocol":PROTOCOL,"runtimeId":descriptor.runtime_id,"generation":descriptor.generation,
                                "pid":descriptor.pid,"nativePid":descriptor.native_pid,"hostProcess":descriptor.host_process,"nativeProcess":descriptor.native_process,
                                "initialized":true,"native":initialized,"capabilities":Capabilities::default(),"activeTurns":store.state.active_count(),
                                "pendingRequests":store.state.pending_count(),"reconciliationRequired":store.state.reconciliation_required}}));
                        },
                        "host/replay" => {
                            let Some(after)=value["params"]["after"].as_u64() else {deliver(&reply,fail(id,"invalid_request"));continue;};
                            let result=store.state.replay(after,value["params"]["generation"].as_str());
                            deliver(&reply,json!({"id":id,"result":result}));
                        },
                        "host/recovery" => deliver(&reply,json!({"id":id,"result":store.state.snapshot()})),
                        "host/receipt" => {
                            let Some(key)=key(&value["params"]["requestId"]) else {deliver(&reply,fail(id,"invalid_request"));continue;};
                            deliver(&reply,json!({"id":id,"result":store.state.receipt(&key,false)}));
                        },
                        "host/shutdown" => {
                            if store.state.busy() {deliver(&reply,fail(id,"busy"));continue;}
                            let (ack,wait)=tokio::sync::oneshot::channel();
                            if reply.try_send(Reply{value:json!({"id":id,"result":{"stopping":true}}),flushed:Some(ack)}).is_err() {continue;}
                            let _=tokio::time::timeout(Duration::from_secs(2),wait).await;
                            return Ok(true);
                        },
                        "host/request" => {
                            if value["params"]["generation"]!=descriptor.generation {deliver(&reply,fail(id,"generation_mismatch"));continue;}
                            submit(writer,store,value["params"]["request"].clone(),&reply,Some(id)).await?;
                        },
                        "host/respond" => {
                            if value["params"]["generation"]!=descriptor.generation {deliver(&reply,fail(id,"generation_mismatch"));continue;}
                            answer(writer,store,value["params"]["requestId"].clone(),value["params"]["response"].clone(),&reply,Some(id)).await?;
                        },
                        "" => {
                            let mut response=value.clone();response.as_object_mut().unwrap().remove("id");
                            answer(writer,store,id,response,&reply,None).await?;
                        },
                        _ if method.starts_with("host/") || method=="initialize" || method=="initialized" => deliver(&reply,fail(id,"unsupported")),
                        _ => {let _=command_key;submit(writer,store,value,&reply,None).await?;},
                    }
                },
            }
        }
    }
}

async fn submit(
    writer: &mut tokio::process::ChildStdin,
    store: &mut Store,
    value: Value,
    reply: &mpsc::Sender<Reply>,
    outer: Option<Value>,
) -> Result<()> {
    let id = outer.clone().unwrap_or_else(|| value["id"].clone());
    let method = value["method"].as_str().unwrap_or("");
    let Some(command_key) = key(&value["id"]) else {
        deliver(reply, fail(id, "invalid_request"));
        return Ok(());
    };
    if method.is_empty()
        || method.starts_with("host/")
        || ["initialize", "initialized"].contains(&method)
        || value.as_object().is_none_or(|o| {
            o.keys()
                .any(|k| !["id", "method", "params"].contains(&k.as_str()))
        })
    {
        deliver(reply, fail(id, "unsupported"));
        return Ok(());
    }
    match store.state.reserve(command_key, &value, false) {
        Err(error) => {
            deliver(
                reply,
                fail(
                    id,
                    if error.to_string() == "conflict" {
                        "conflict"
                    } else {
                        "capacity"
                    },
                ),
            );
        }
        Ok(Some(receipt)) => {
            if outer.is_some() {
                deliver(reply, json!({"id":id,"result":receipt}));
            } else {
                deliver(
                    reply,
                    receipt.response.unwrap_or_else(|| fail(id, "unknown")),
                );
            }
        }
        Ok(None) => {
            store.save()?;
            write_frame(writer, &value).await?;
            if outer.is_some() {
                deliver(
                    reply,
                    json!({"id":id,"result":Receipt{generation:store.state.generation.clone(),state:"submitted".into(),response:None}}),
                );
            }
        }
    }
    Ok(())
}

async fn answer(
    writer: &mut tokio::process::ChildStdin,
    store: &mut Store,
    request_id: Value,
    response: Value,
    reply: &mpsc::Sender<Reply>,
    outer: Option<Value>,
) -> Result<()> {
    let id = outer.clone().unwrap_or_else(|| request_id.clone());
    let Some(request_key) = key(&request_id) else {
        deliver(reply, fail(id, "invalid_request"));
        return Ok(());
    };
    let valid = response
        .as_object()
        .is_some_and(|o| o.len() == 1 && (o.contains_key("result") || o.contains_key("error")));
    if !valid {
        deliver(reply, fail(id, "invalid_response"));
        return Ok(());
    }
    let mut native = response;
    native
        .as_object_mut()
        .unwrap()
        .insert("id".into(), request_id.clone());
    if store
        .state
        .answers
        .get(&request_key)
        .is_some_and(|r| r.generation != store.state.generation)
        || !store.state.answers.contains_key(&request_key)
            && store.state.pending.get(&request_key).is_none_or(|r| {
                r.generation != store.state.generation || r.request.get("method").is_none()
            })
    {
        deliver(reply, fail(id, "stale_request"));
        return Ok(());
    }
    match store.state.reserve(request_key.clone(), &native, true) {
        Err(error) => deliver(
            reply,
            fail(
                id,
                if error.to_string() == "conflict" {
                    "conflict"
                } else {
                    "capacity"
                },
            ),
        ),
        Ok(Some(receipt)) => {
            if outer.is_some() {
                deliver(reply, json!({"id":id,"result":receipt}));
            }
        }
        Ok(None) => {
            store.save()?; // Keep the question until the response reservation is on disk.
            write_frame(writer, &native).await?;
            store.state.pending.remove(&request_key);
            store.state.complete(
                &request_key,
                json!({"id":request_id,"result":{"written":true}}),
                true,
            );
            store.save()?;
            if outer.is_some() {
                deliver(
                    reply,
                    json!({"id":id,"result":store.state.receipt(&request_key,true)}),
                );
            }
        }
    }
    Ok(())
}

pub async fn run(config: HostConfig) -> Result<()> {
    ensure!(
        config.state.is_absolute() && config.descriptor.is_absolute(),
        "Absolute host state paths required"
    );
    ensure!(
        config.binary.is_absolute() && config.binary.is_file(),
        "Absolute native executable required"
    );
    ensure!(
        config.config_home.is_absolute() && config.config_home.is_dir(),
        "Existing config home required"
    );
    pocket_agent::storage::private_directory(&config.state)
        .context("Protect host state directory")?;
    let lock = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(config.state.join("host.lock"))
        .context("Open host lock")?;
    // Verify a real opened file before reading an earlier descriptor or launching.
    let _ = pocket_agent::storage::read_physical(&config.state.join("host.lock"), 1024)
        .context("Verify host lock path")?;
    lock.try_lock_exclusive()
        .context("Runtime host already running")?;
    let generation = uuid::Uuid::new_v4().to_string();
    let runtime_id = format!(
        "assistant-{}",
        &format!(
            "{:x}",
            Sha256::digest(crate::client::normalized_home(&config.config_home)?.as_bytes())
        )[..24]
    );
    if config.descriptor.try_exists()? {
        let bytes = pocket_agent::storage::read_physical(&config.descriptor, 16384)
            .context("Read earlier host descriptor")?;
        let previous: Descriptor = serde_json::from_slice(&bytes)
            .map_err(|_| anyhow::anyhow!("Invalid host descriptor data"))?;
        ensure!(
            previous
                .native_process
                .as_ref()
                .is_some_and(|process| process.pid == previous.native_pid && process.terminated()),
            "Previous native process has not verifiably exited"
        );
    }
    let parent = config
        .descriptor
        .parent()
        .context("Descriptor parent missing")?;
    pocket_agent::storage::private_directory(parent).context("Protect descriptor directory")?;
    let mut store = Store::open(
        &config.state.join("recovery.json"),
        &runtime_id,
        &generation,
    )
    .context("Open host recovery")?;
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let mut launch = tokio::process::Command::new(&config.binary);
    launch
        .args(&config.native_args)
        .env("CODEX_HOME", &config.config_home)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(false);
    #[cfg(windows)]
    launch.creation_flags(0x08000000);
    let mut child = launch.spawn().context("Spawn native process")?;
    let native_pid = child.id().context("Native PID unavailable")?;
    let descriptor = Descriptor {
        protocol: PROTOCOL,
        runtime_id,
        generation,
        pid: std::process::id(),
        native_pid,
        native_process: pocket_core::native_process::NativeProcess::capture(native_pid).ok(),
        host_process: pocket_core::native_process::NativeProcess::capture(std::process::id()).ok(),
        port: listener.local_addr()?.port(),
        token: pocket_agent::storage::secret(),
        config_home: config.config_home.clone(),
        source: "cli".into(),
    };
    // Publish ownership before initialization: a startup failure cannot authorize a duplicate native.
    pocket_agent::storage::atomic_write(&config.descriptor, &serde_json::to_vec(&descriptor)?)
        .context("Publish host ownership")?;
    store
        .begin_generation(&descriptor.generation)
        .context("Persist host generation")?;
    let mut writer = child.stdin.take().context("Native stdin unavailable")?;
    let mut reader = BufReader::new(child.stdout.take().context("Native stdout unavailable")?);
    write_frame(&mut writer,&json!({"id":"pocket-host-initialize","method":"initialize","params":{"clientInfo":{"name":"codex_pocket_assistant","title":"Pocket Assistant","version":env!("CARGO_PKG_VERSION")},"capabilities":{"experimentalApi":true}}})).await?;
    let initialized = tokio::time::timeout(Duration::from_secs(15), async {
        for _ in 0..128 {
            let value = read_frame(&mut reader)
                .await?
                .context("Native closed while initializing")?;
            if value["id"] == "pocket-host-initialize" {
                ensure!(
                    value.get("result").is_some(),
                    "Native initialization rejected"
                );
                ensure!(
                    serde_json::to_vec(&value["result"])?.len() <= 65536,
                    "Native initialization result exceeds budget"
                );
                return Ok(value["result"].clone());
            }
            store.state.push(value)?;
            store.save()?;
        }
        bail!("Native initialization exceeded budget")
    })
    .await??;
    write_frame(&mut writer, &json!({"method":"initialized"})).await?;
    let token = Arc::new(descriptor.token.clone());
    let mut clients = tokio::task::JoinSet::new();
    let (events, incoming) = mpsc::channel(128);
    let (broadcast, _) = broadcast::channel(128);
    let native_reader = tokio::spawn({
        let events = events.clone();
        async move {
            while let Ok(Some(value)) = read_frame(&mut reader).await {
                if events.send(Event::Native(value)).await.is_err() {
                    return;
                }
            }
            let _ = events.send(Event::NativeClosed).await;
        }
    });
    // Actor loop is defined below.
    let idle_exit = actor_loop(
        listener,
        token,
        &descriptor,
        initialized,
        &mut writer,
        &mut store,
        events,
        incoming,
        broadcast,
        &mut clients,
    )
    .await?;
    clients.abort_all();
    native_reader.abort();
    // Only an explicitly verified idle shutdown closes stdin intentionally.
    // Unexpected native exit is reported; no native or unrelated process is killed.
    if idle_exit {
        writer.shutdown().await?;
        drop(writer);
        let _ = tokio::time::timeout(Duration::from_secs(10), child.wait()).await;
    }
    Ok(())
}
