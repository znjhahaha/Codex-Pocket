use anyhow::{Context, Result, bail};
use pocket_bridge::router::{Client, Route, Router};
use serde_json::{Value, json};
use std::{collections::HashMap, path::PathBuf, process::Stdio};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::TcpListener,
    process::Command,
    sync::{mpsc, oneshot},
};
mod descriptor;

const MAX_LINE: usize = 16 * 1024 * 1024;
struct Peer {
    sender: mpsc::Sender<Value>,
    _cancel: oneshot::Sender<()>,
}
enum Input {
    Message(Client, Value),
    Runtime(Value),
    Gone(Client),
    RuntimeGone,
    Connected(String, Peer),
    Schema(Option<pocket_bridge::capabilities::Capabilities>),
}

fn main() {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Tokio runtime");
    let code = match runtime.block_on(run()) {
        Ok(code) => code,
        Err(error) => {
            eprintln!("{error:#}");
            1
        }
    };
    // run() has already reaped the child and dropped the descriptor guard.
    // Tokio's blocking stdin read cannot be cancelled by runtime shutdown.
    std::process::exit(code);
}
async fn run() -> Result<i32> {
    let mut args = std::env::args_os().skip(1).collect::<Vec<_>>();
    let exe = std::env::current_exe()?;
    // This immutable entry is separate from the management/update bootstrap.
    let transparent = exe.file_name().is_some_and(|n| n.eq_ignore_ascii_case("pocket-native-proxy.exe"));
    let config = exe.with_file_name("pocket-original.json");
    let mut package_family = None;
    let original = if args.first().is_some_and(|a| a == "--original") {
        if args.len() < 3 {
            bail!("usage: pocket-bridge --original <codex.exe> -- <arguments>");
        }
        args.remove(0);
        let p = PathBuf::from(args.remove(0));
        if args.first().is_some_and(|a| a == "--") {
            args.remove(0);
        }
        p
    } else if transparent {
        let (binary, family) = pocket_bridge::discovery::registered_desktop().await?;
        package_family = Some(family);
        binary
    } else {
        let v: Value = serde_json::from_slice(
            &std::fs::read(config).context("Missing pocket-original.json")?,
        )?;
        package_family = v["windowsSandboxPackageFamily"].as_str().map(str::to_owned);
        pocket_bridge::discovery::resolve(
            std::path::Path::new(v["binary"].as_str().context("Missing binary")?),
            &exe,
        )
        .await?
    };
    if original.canonicalize()? == exe.canonicalize()? {
        bail!("Original binary cannot be the bridge");
    }
    let mut cmd = Command::new(&original);
    if let Some(family) = package_family {
        cmd.env("CODEX_WINDOWS_SANDBOX_PACKAGE_FAMILY", family);
    }
    cmd.args(&args).env("CODEX_APP_RUNTIME_CLI_PATH", &original);
    // The desktop owns the launch barrier. Native tools must not inherit it.
    cmd.env_remove("POCKET_UPDATE_OPERATION").env_remove("POCKET_UPDATE_RELEASE_FILE");
    // Desktop callbacks must use the actual native executable, avoiding wrapper recursion.
    if std::env::var_os("CODEX_CLI_PATH").is_some() {
        cmd.env("CODEX_CLI_PATH", &original);
    }
    let runtime_id = uuid::Uuid::new_v4().to_string();
    let directory = std::env::var_os("POCKET_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(
                std::env::var_os("LOCALAPPDATA")
                    .unwrap_or_else(|| std::env::temp_dir().into_os_string()),
            )
            .join("CodexPocket/runtimes")
        });
    let app_server = args.iter().any(|a| a == "app-server")
        && !args.iter().any(|a| {
            a == "generate-json-schema"
                || a == "generate-ts"
                || a == "daemon"
                || a == "proxy"
                || a == "--print-startup-config"
                || a == "--help"
                || a == "-h"
                || a == "--version"
                || a == "-V"
        });
    if !app_server {
        // Policy/help/config probes belong to Codex, not Pocket's update
        // lifecycle. In particular an MSIX caller must never create redirected
        // Pocket state just to discover its native startup policy.
        let probe = args.iter().any(|a| matches!(a.to_str(),Some("--version"|"-V"|"--help"|"-h"|"--print-startup-config"|"generate-json-schema"|"generate-ts")))
            || args.windows(2).any(|a| a[0]=="debug" && a[1]=="policy-read")
            || args.windows(2).any(|a| a[0]=="app-server" && (a[1]=="proxy" || a[1]=="daemon"));
        let admission = if transparent || probe { None } else {
            pocket_bridge::admission::begin(&exe,&directory,&runtime_id,false)?
        };
        let mut child = pocket_bridge::admission::spawn_native(&mut cmd,admission.as_ref())?;
        let status = child.wait().await?;
        if let Some(a)=admission {
            drop(a.guard);
            pocket_bridge::admission::unregister(&a.installation,&runtime_id).await;
        }
        return Ok(status.code().unwrap_or(1));
    }
    let mut admission = if transparent { None } else {
        pocket_bridge::admission::begin(&exe, &directory, &runtime_id, true)?
    };
    let mut router = match std::env::var_os("POCKET_UPDATE_OPERATION").filter(|_| !transparent) {
        Some(operation) => Router::with_startup_operation(
            operation.into_string().map_err(|_|anyhow::anyhow!("Invalid startup update operation"))?,
            std::env::var_os("POCKET_UPDATE_RELEASE_FILE").map(PathBuf::from),
        )?,
        None => {
            if !transparent && std::env::var_os("POCKET_UPDATE_RELEASE_FILE").is_some() { bail!("Startup release requires an operation"); }
            Router::default()
        }
    };
    #[cfg(windows)]
    cmd.creation_flags(0x08000000);
    cmd.stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .kill_on_drop(true);
    pocket_bridge::native_lifetime::bind_to_proxy()?;
    let mut child = pocket_bridge::admission::spawn_native(&mut cmd, admission.as_ref())?;
    #[cfg(windows)]
    let native_process = Some(pocket_core::native_process::NativeProcess::capture(
        child.id().context("native process id")?,
    )?);
    #[cfg(not(windows))]
    let native_process: Option<pocket_core::native_process::NativeProcess> = None;
    let mut child_input = child.stdin.take().context("stdin")?;
    let child_output = child.stdout.take().context("stdout")?;
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let token = uuid::Uuid::new_v4().to_string() + &uuid::Uuid::new_v4().to_string();
    if let Some(a)=admission.as_mut() {
        a.registration.native=native_process.clone();
        a.registration.phase=pocket_core::admission::RegistrationPhase::Registered;
        router.startup_admission=Some(a.registration.clone());
    }
    let descriptor = directory.join(format!("{runtime_id}.json"));
    let metadata = json!({"protocol":1,"runtimeId":runtime_id,"source":"desktop","pid":std::process::id(),"nativeProcess":native_process,"proxyProcess":pocket_core::native_process::NativeProcess::capture(std::process::id()).ok(),"port":listener.local_addr()?.port(),"token":token,"binary":original,"startupAdmissionProtocol":if router.startup_admission.is_some(){1}else{0},"startupAdmission":router.startup_admission,"admission":pocket_bridge::admission::summary(router.startup_admission.as_ref()),"cwd":std::env::current_dir()?,"configHome":std::env::var_os("CODEX_HOME").map(PathBuf::from).unwrap_or_else(||PathBuf::from(std::env::var_os("USERPROFILE").unwrap_or_default()).join(".codex"))});
    let _guard = if transparent { None } else {
        descriptor::publish(&directory, &descriptor, &serde_json::to_vec(&metadata)?)?;
        Some(DescriptorGuard(descriptor))
    };
    #[cfg(windows)]
    let _publication = if transparent {
        pocket_bridge::registry::pipe_name().ok().map(|pipe|pocket_bridge::registry::publish_until_closed(pipe,metadata))
    } else { None };
    let (tx, mut rx) = mpsc::channel::<Input>(256);
    tokio::spawn(read_stream(
        tokio::io::stdin(),
        tx.clone(),
        Some(Client::Desktop),
    ));
    tokio::spawn(read_stream(child_output, tx.clone(), None));
    let accept_tx = tx.clone();
    tokio::spawn(async move {
        loop {
            let Ok((stream, _)) = listener.accept().await else {
                break;
            };
            let t = accept_tx.clone();
            let expected = token.clone();
            tokio::spawn(async move {
                let (read, mut write) = stream.into_split();
                let mut reader = BufReader::new(read);
                let mut line = Vec::new();
                let auth = tokio::time::timeout(
                    std::time::Duration::from_secs(5),
                    read_bounded(&mut reader, &mut line),
                )
                .await;
                if !matches!(auth, Ok(Ok(true))) {
                    return;
                }
                let Ok(v) = serde_json::from_slice::<Value>(&line) else {
                    return;
                };
                if v["token"].as_str() != Some(&expected) {
                    return;
                }
                let id = uuid::Uuid::new_v4().to_string();
                let (out, mut inbox) = mpsc::channel::<Value>(256);
                let (cancel, canceled) = oneshot::channel();
                if t.send(Input::Connected(
                    id.clone(),
                    Peer {
                        sender: out,
                        _cancel: cancel,
                    },
                ))
                .await
                .is_err()
                {
                    return;
                }
                let read_tx = t.clone();
                let read_id = id.clone();
                let reader_task = async move {
                    while let Ok(true) = read_bounded(&mut reader, &mut line).await {
                        if let Ok(value) = serde_json::from_slice(&line)
                            && read_tx
                                .send(Input::Message(Client::Remote(read_id.clone()), value))
                                .await
                                .is_err()
                        {
                            break;
                        }
                    }
                };
                let writer_task = async move {
                    while let Some(v) = inbox.recv().await {
                        if write_json(&mut write, &v).await.is_err() {
                            break;
                        }
                    }
                };
                // Losing either half or dropping the registry entry closes BOTH
                // halves, even if the peer is not draining its output socket.
                tokio::select! {_=reader_task=>{},_=writer_task=>{},_=canceled=>{}}
                let _ = t.send(Input::Gone(Client::Remote(id))).await;
            });
        }
    });
    // Descriptor is durable and authenticated listener task is installed. Only
    // now may updater's exclusive lock overtake this startup.
    let admission_installation=if let Some(a)=admission.take() {
        a.guard.registered(&a.registration)?;
        drop(a.guard);
        Some(a.installation)
    } else { None };
    // Probe asynchronously so the original desktop's initialize is never delayed.
    let schema_binary = original.clone();
    let schema_tx = tx.clone();
    let schema_admission = router.startup_admission.clone();
    let mut schema_task = tokio::spawn(async move {
        let capabilities = pocket_bridge::admission::discover(&schema_binary, schema_admission.as_ref()).await.ok();
        let _ = schema_tx.send(Input::Schema(capabilities)).await;
    });
    let mut schema_pending = true;
    let mut schema_state = "initializing";
    let mut schema_failures = 0u32;
    let mut schema_retry_at = tokio::time::Instant::now();
    let mut remotes = HashMap::<String, Peer>::new();
    let mut desktop = tokio::io::stdout();
    let mut native_eof = false;
    let mut failure = None;
    'messages: while let Some(input) = rx.recv().await {
        let routes = match input {
            Input::Message(c, v) => {
                let previous_maintenance=router.maintenance_status();
                router.refresh_startup();
                if let Client::Remote(id) = &c
                    && !remotes.contains_key(id)
                {
                    continue;
                }
                if matches!(c, Client::Remote(_)) && v["method"] == "pocket/status" {
                    let retry = schema_state == "error" && !schema_pending && tokio::time::Instant::now() >= schema_retry_at;
                    if retry {
                        schema_pending = true;
                        schema_state = "initializing";
                        let binary = original.clone();
                        let schema_tx = tx.clone();
                        let schema_admission = router.startup_admission.clone();
                        schema_task = tokio::spawn(async move {
                            let capabilities = pocket_bridge::admission::discover(&binary, schema_admission.as_ref()).await.ok();
                            let _ = schema_tx.send(Input::Schema(capabilities)).await;
                        });
                    }
                    let mut routes = vec![Route::Client(
                        c,
                        json!({"id":v["id"],"result":runtime_status(&router, &runtime_id, &native_process, schema_pending, schema_state)}),
                    )];
                    if retry || barrier_changed(&previous_maintenance,&router) {
                        routes.push(Route::Broadcast(json!({"method":"pocket/runtimeStatus","params":runtime_status(&router, &runtime_id, &native_process, schema_pending, schema_state)})));
                    }
                    routes
                } else {
                    let initialized = router.initialized;
                    let mut routes = router.from_client(c, v);
                    if router.initialized != initialized || barrier_changed(&previous_maintenance,&router) {
                        routes.push(Route::Broadcast(json!({"method":"pocket/runtimeStatus","params":runtime_status(&router, &runtime_id, &native_process, schema_pending, schema_state)})));
                    }
                    routes
                }
            }
            Input::Runtime(v) => {
                let previous = (router.initialized, router.runtime_role);
                let mut routes = router.from_runtime(v);
                if previous != (router.initialized, router.runtime_role) {
                    routes.push(Route::Broadcast(json!({"method":"pocket/runtimeStatus","params":runtime_status(&router, &runtime_id, &native_process, schema_pending, schema_state)})));
                }
                routes
            },
            Input::Schema(capabilities) => {
                schema_pending = false;
                if let Some(capabilities) = capabilities {
                    router.capabilities = capabilities;
                    schema_state = "ready";
                    schema_failures = 0;
                } else {
                    schema_state = "error";
                    schema_failures = schema_failures.saturating_add(1);
                    schema_retry_at = tokio::time::Instant::now() + std::time::Duration::from_secs((5u64 << schema_failures.min(4).saturating_sub(1)).min(60));
                }
                vec![Route::Broadcast(json!({"method":"pocket/runtimeStatus","params":runtime_status(&router, &runtime_id, &native_process, schema_pending, schema_state)}))]
            }
            Input::Gone(Client::Desktop) => break,
            Input::RuntimeGone => {
                native_eof = true;
                break;
            }
            Input::Gone(c) => {
                if let Client::Remote(id) = &c {
                    remotes.remove(id);
                }
                router.disconnect(&c);
                continue;
            }
            Input::Connected(id, sender) => {
                remotes.insert(id, sender);
                continue;
            }
        };
        for route in routes {
            match route {
                Route::Runtime(v) => {
                    if let Err(error) = write_json(&mut child_input, &v).await {
                        failure = Some(error);
                        break 'messages;
                    }
                }
                Route::Client(Client::Desktop, v) => {
                    if let Err(error) = write_json(&mut desktop, &v).await {
                        failure = Some(error);
                        break 'messages;
                    }
                }
                Route::Client(Client::Remote(id), v) => {
                    if let Some(p) = remotes.get(&id)
                        && p.sender.try_send(v).is_err()
                    {
                        remotes.remove(&id);
                        router.disconnect(&Client::Remote(id));
                    }
                }
                Route::Broadcast(v) => {
                    remotes.retain(|id, p| {
                        if p.sender.try_send(v.clone()).is_ok() {
                            true
                        } else {
                            router.disconnect(&Client::Remote(id.clone()));
                            false
                        }
                    });
                }
                Route::Drop => {}
            }
        }
    }
    remotes.clear();
    drop(child_input);
    let status = if native_eof {
        match tokio::time::timeout(std::time::Duration::from_secs(1), child.wait()).await {
            Ok(status) => status?,
            Err(_) => {
                let _ = child.kill().await;
                child.wait().await?
            }
        }
    } else {
        let _ = child.kill().await;
        child.wait().await?
    };
    // Stop accepting new work, then reap the bounded schema export too. Its
    // shared guard and the registration cannot outlive their evidence owner.
    drop(rx);
    schema_task.await?;
    if let Some(installation)=admission_installation {
        // Never erase evidence on Drop/panic: only a reaped native permits
        // normal cleanup. If updater holds exclusive, retain it for recovery.
        pocket_bridge::admission::unregister(&installation,&runtime_id).await;
    }
    if let Some(error) = failure {
        return Err(error);
    }
    Ok(if native_eof {
        status.code().unwrap_or(1)
    } else {
        0
    })
}
fn runtime_status(
    router: &Router,
    runtime_id: &str,
    native_process: &Option<pocket_core::native_process::NativeProcess>,
    schema_pending: bool,
    schema_state: &str,
) -> Value {
    json!({"runtimeId":runtime_id,"runtimeRole":router.runtime_role,"nativeProcess":native_process,"initialized":router.initialized,"nativeVersion":router.native_version,"loadedThreadIds":router.loaded,"pendingApprovals":router.pending_approvals(),"nativeCapabilities":router.capabilities,"schemaPending":schema_pending,"schemaState":schema_state,"bridgeVersion":env!("CARGO_PKG_VERSION"),"permissionProtocol":pocket_bridge::capabilities::PERMISSION_PROTOCOL,"startupBarrierProtocol":1,"startupAdmissionProtocol":if router.startup_admission.is_some(){1}else{0},"startupAdmission":router.startup_admission,"admission":pocket_bridge::admission::summary(router.startup_admission.as_ref()),"maintenance":router.maintenance_status()})
}
fn barrier_changed(previous:&Value,router:&Router)->bool {
    let current=router.maintenance_status();
    previous["operationId"]!=current["operationId"] || previous["startupFenced"]!=current["startupFenced"]
}
async fn write_json<W: tokio::io::AsyncWrite + Unpin>(w: &mut W, v: &Value) -> Result<()> {
    let mut b = serde_json::to_vec(v)?;
    b.push(b'\n');
    w.write_all(&b).await?;
    w.flush().await?;
    Ok(())
}
async fn read_bounded<R: tokio::io::AsyncBufRead + Unpin>(
    reader: &mut R,
    output: &mut Vec<u8>,
) -> Result<bool> {
    output.clear();
    loop {
        let b = reader.fill_buf().await?;
        if b.is_empty() {
            return Ok(!output.is_empty());
        }
        let size = b
            .iter()
            .position(|x| *x == b'\n')
            .map_or(b.len(), |i| i + 1);
        if output.len() + size > MAX_LINE {
            bail!("RPC frame exceeds limit")
        };
        output.extend_from_slice(&b[..size]);
        let done = b[size - 1] == b'\n';
        reader.consume(size);
        if done {
            return Ok(true);
        }
    }
}
async fn read_stream<R: tokio::io::AsyncRead + Unpin>(
    reader: R,
    tx: mpsc::Sender<Input>,
    client: Option<Client>,
) {
    let mut reader = BufReader::new(reader);
    let mut line = Vec::new();
    while let Ok(true) = read_bounded(&mut reader, &mut line).await {
        let Ok(v) = serde_json::from_slice(&line) else {
            continue;
        };
        let input = match &client {
            Some(c) => Input::Message(c.clone(), v),
            None => Input::Runtime(v),
        };
        if tx.send(input).await.is_err() {
            return;
        }
    }
    let _ = tx
        .send(match client {
            Some(c) => Input::Gone(c),
            None => Input::RuntimeGone,
        })
        .await;
}
struct DescriptorGuard(PathBuf);
impl Drop for DescriptorGuard {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}
