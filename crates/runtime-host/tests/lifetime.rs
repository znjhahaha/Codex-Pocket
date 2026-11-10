use pocket_runtime_host::{Descriptor, HostConfig, read_frame, write_frame};
use serde_json::{Value, json};
use std::time::Duration;
use tokio::{
    io::BufReader,
    net::{
        TcpStream,
        tcp::{OwnedReadHalf, OwnedWriteHalf},
    },
};
struct Client {
    read: BufReader<OwnedReadHalf>,
    write: OwnedWriteHalf,
}
impl Client {
    async fn attach(d: &Descriptor) -> Self {
        let (r, mut w) = TcpStream::connect(("127.0.0.1", d.port))
            .await
            .unwrap()
            .into_split();
        write_frame(&mut w, &json!({"token":d.token}))
            .await
            .unwrap();
        Self {
            read: BufReader::new(r),
            write: w,
        }
    }
    async fn call(&mut self, id: &str, method: &str, params: Value) -> Value {
        write_frame(
            &mut self.write,
            &json!({"id":id,"method":method,"params":params}),
        )
        .await
        .unwrap();
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                let value = read_frame(&mut self.read).await.unwrap().unwrap();
                if value["id"] == id {
                    return value;
                }
                if value["method"] == "host/event" && value["params"]["event"]["id"] == id {
                    return value["params"]["event"].clone();
                }
            }
        })
        .await
        .unwrap()
    }
}
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn disconnect_keeps_native_task_replay_does_not_rerun_and_shutdown_waits_for_idle() {
    let dir = tempfile::tempdir().unwrap();
    let descriptor = dir.path().join("registry/host.json");
    let config = HostConfig {
        state: dir.path().join("host"),
        descriptor: descriptor.clone(),
        config_home: dir.path().into(),
        binary: std::path::PathBuf::from(env!("CARGO_BIN_EXE_pocket-host-fixture")),
        native_args: vec![],
    };
    let host = tokio::spawn(pocket_runtime_host::run(config));
    let d: Descriptor = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if let Ok(bytes) = pocket_agent::storage::read_physical(&descriptor, 16384) {
                break serde_json::from_slice(&bytes).unwrap();
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    let bad = TcpStream::connect(("127.0.0.1", d.port)).await.unwrap();
    let (bad_read, mut bad_write) = bad.into_split();
    write_frame(&mut bad_write, &json!({"token":"incorrect"}))
        .await
        .unwrap();
    let denied = tokio::time::timeout(
        Duration::from_secs(3),
        read_frame(&mut BufReader::new(bad_read)),
    )
    .await
    .unwrap();
    assert!(denied.is_err() || denied.unwrap().is_none());
    let mut first = Client::attach(&d).await;
    assert_eq!(
        first
            .call("task", "turn/start", json!({"threadId":"fixture-thread"}))
            .await["result"]["writes"],
        1
    );
    drop(first); // Simulate an agent/supervisor restart. No signal goes to native.
    let mut second = Client::attach(&d).await;
    let status = second.call("status", "host/status", json!({})).await;
    assert_eq!(status["result"]["nativePid"], d.native_pid);
    assert_eq!(status["result"]["activeTurns"], 1);
    assert_eq!(
        second.call("stop-busy", "host/shutdown", json!({})).await["error"]["code"],
        "busy"
    );
    assert_eq!(
        second
            .call("task", "turn/start", json!({"threadId":"fixture-thread"}))
            .await["result"]["writes"],
        1
    );
    assert_eq!(
        second
            .call("task", "turn/start", json!({"threadId":"different"}))
            .await["error"]["code"],
        "conflict"
    );
    let replay = second
        .call("replay", "host/replay", json!({"after":0}))
        .await;
    assert_eq!(replay["result"]["generation"], d.generation);
    assert!(
        replay["result"]["events"]
            .as_array()
            .unwrap()
            .iter()
            .any(|event| event["event"]["method"] == "turn/started")
    );
    assert_eq!(
        second.call("finish", "fixture/finish", json!({})).await["result"]["writes"],
        1
    );
    assert_eq!(
        second.call("stop-idle", "host/shutdown", json!({})).await["result"]["stopping"],
        true
    );
    tokio::time::timeout(Duration::from_secs(5), host)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
}
