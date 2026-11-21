//! Disposable old-protocol server used only by the retirement integration test.
use anyhow::Result;
use pocket_runtime_ipc::{Descriptor, read_frame, write_frame};
use serde_json::json;
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use std::process::Stdio;
use tokio::{io::BufReader, net::TcpListener};
pub async fn run(root: PathBuf, installation: String) -> Result<()> {
    let binary = std::env::current_exe()?;
    let mut launch = tokio::process::Command::new(&binary);
    launch
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(false)
        .creation_flags(0x08000000);
    let mut child = launch.spawn()?;
    let _input = child.stdin.take().unwrap();
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let host = pocket_core::native_process::NativeProcess::capture(std::process::id())?;
    let native = pocket_core::native_process::NativeProcess::capture(child.id().unwrap())?;
    let home_key=format!("{:x}",Sha256::digest(pocket_runtime_ipc::support::normalized_home(&root)?.as_bytes()));
    let d = Descriptor {
        protocol: 2,
        runtime_id: format!("assistant-{}",&home_key[..24]),
        generation: uuid::Uuid::new_v4().to_string(),
        pid: host.pid,
        native_pid: native.pid,
        host_process: Some(host.clone()),
        native_process: Some(native),
        port: listener.local_addr()?.port(),
        token: "a".repeat(64),
        config_home: root.clone(),
        source: "cli".into(),
    };
    let state = root.join("state");
    pocket_agent::storage::private_directory(&state)?;
    std::fs::write(state.join("ensure.lock"), b"")?;
    let disk = json!({"schema":2,"runtime_id":d.runtime_id,"generation":d.generation,"sequence":0,
        "commands":{"s:old":{"generation":uuid::Uuid::new_v4().to_string(),"completed":false}},
        "answers":{},"runs":{},"pending":{},"reconciliation_required":true});
    pocket_agent::storage::atomic_write(&state.join("recovery.json"), &serde_json::to_vec(&disk)?)?;
    pocket_agent::storage::atomic_write(
        &state.join("startup.json"),
        &serde_json::to_vec(&json!({
        "schema":1,"phase":"ready","homeKey":home_key,"reservation":uuid::Uuid::new_v4().to_string(),"configHome":root,"nativeBinary":binary,"installationGeneration":installation,
        "hostProcess":host,"descriptorGeneration":d.generation}))?,
    )?;
    pocket_agent::storage::atomic_write(&state.join("descriptor.json"), &serde_json::to_vec(&d)?)?;
    loop {
        let (socket, _) = listener.accept().await?;
        let d = d.clone();
        tokio::spawn(async move {
            let mut socket = BufReader::new(socket);
            let auth = read_frame(&mut socket).await?.unwrap();
            anyhow::ensure!(
                auth["token"] == d.token && auth["generation"] == d.generation,
                "auth"
            );
            while let Some(request) = read_frame(&mut socket).await? {
                let result = match request["method"].as_str() {
                    Some("host/status") => {
                        json!({"protocol":2,"runtimeId":d.runtime_id,"generation":d.generation,"pid":d.pid,"nativePid":d.native_pid,
                        "hostProcess":d.host_process,"nativeProcess":d.native_process,"initialized":true,"capabilities":pocket_runtime_ipc::Capabilities::default(),
                        "activeTurns":0,"pendingRequests":0,"reconciliationRequired":true})
                    }
                    Some("host/recovery") => {
                        json!({"generation":d.generation,"sequence":0,"commandCount":1,"answerCount":0,
                        "unconfirmedWrites":1,"unknownOperations":1,"pendingRequests":[],"runs":[],"reconciliationRequired":true})
                    }
                    Some("host/request")
                        if request["params"]["request"]["method"] == "thread/loaded/list" =>
                    {
                        let native = &request["params"]["request"];
                        json!({"generation":d.generation,"state":"completed","response":{"id":native["id"],"result":{"data":[],"nextCursor":null}}})
                    }
                    _ => {
                        write_frame(
                            socket.get_mut(),
                            &json!({"id":request["id"],"error":{"code":"reconciliation_required"}}),
                        )
                        .await?;
                        continue;
                    }
                };
                write_frame(
                    socket.get_mut(),
                    &json!({"id":request["id"],"result":result}),
                )
                .await?;
            }
            Ok::<_, anyhow::Error>(())
        });
    }
}
