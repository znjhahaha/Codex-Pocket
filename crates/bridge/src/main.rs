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
    // Desktop callbacks must use the actual native executable, avoiding wrapper recursion.
    if std::env::var_os("CODEX_CLI_PATH").is_some() {
        cmd.env("CODEX_CLI_PATH", &original);
    }
    if !args.iter().any(|a| a == "app-server")
        || args.iter().any(|a| {
            a == "generate-json-schema"
                || a == "generate-ts"
                || a == "daemon"
                || a == "proxy"
                || a == "--print-startup-config"
                || a == "--help"
                || a == "-h"
                || a == "--version"
                || a == "-V"
        })
    {
        return Ok(cmd.status().await?.code().unwrap_or(1));
    }
    #[cfg(windows)]
    cmd.creation_flags(0x08000000);
    let mut child = cmd
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .kill_on_drop(true)
        .spawn()?;
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
    std::fs::create_dir_all(&directory)?;
    let descriptor = directory.join(format!("{runtime_id}.json"));
    let metadata = json!({"protocol":1,"runtimeId":runtime_id,"source":"desktop","pid":std::process::id(),"port":listener.local_addr()?.port(),"token":token,"binary":original,"cwd":std::env::current_dir()?,"configHome":std::env::var_os("CODEX_HOME").map(PathBuf::from).unwrap_or_else(||PathBuf::from(std::env::var_os("USERPROFILE").unwrap_or_default()).join(".codex"))});
    descriptor::publish(&directory, &descriptor, &serde_json::to_vec(&metadata)?)?;
    let _guard = DescriptorGuard(descriptor);
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
    let mut router = Router::default();
    // Probe asynchronously so the original desktop's initialize is never delayed.
    let schema_binary = original.clone();
    let schema_task =
        tokio::spawn(async move { pocket_bridge::capabilities::discover(&schema_binary).await });
    let mut schema_task = Some(schema_task);
    let mut schema_state = "initializing";
    let mut remotes = HashMap::<String, Peer>::new();
    let mut desktop = tokio::io::stdout();
    let mut native_eof = false;
    let mut failure = None;
    'messages: while let Some(input) = rx.recv().await {
        if schema_task.as_ref().is_some_and(|t| t.is_finished())
            && let Some(task) = schema_task.take()
        {
            match task.await {
                Ok(Ok(capabilities)) => {
                    router.capabilities = capabilities;
                    schema_state = "ready";
                }
                _ => {
                    schema_state = "error";
                }
            }
        }
        let routes = match input {
            Input::Message(c, v) => {
                if let Client::Remote(id) = &c
                    && !remotes.contains_key(id)
                {
                    continue;
                }
                if matches!(c, Client::Remote(_)) && v["method"] == "pocket/status" {
                    vec![Route::Client(
                        c,
                        json!({"id":v["id"],"result":{"runtimeId":runtime_id,"nativeProcess":native_process,"initialized":router.initialized,"nativeVersion":router.native_version,"loadedThreadIds":router.loaded,"pendingApprovals":router.pending_approvals(),"nativeCapabilities":router.capabilities,"schemaPending":schema_task.is_some(),"schemaState":schema_state,"bridgeVersion":env!("CARGO_PKG_VERSION"),"permissionProtocol":pocket_bridge::capabilities::PERMISSION_PROTOCOL}}),
                    )]
                } else {
                    router.from_client(c, v)
                }
            }
            Input::Runtime(v) => router.from_runtime(v),
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
    if let Some(error) = failure {
        return Err(error);
    }
    Ok(if native_eof {
        status.code().unwrap_or(1)
    } else {
        0
    })
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
