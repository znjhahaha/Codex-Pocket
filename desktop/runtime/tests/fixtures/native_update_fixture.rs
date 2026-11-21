//! Synthetic native workload and desktop owner. No model, network or user files.
use serde_json::{json, Value};
use std::{
    io::{BufRead, Write},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::Duration,
};
fn emit(v: Value) {
    println!("{v}");
    std::io::stdout().flush().unwrap();
}
fn arg(args: &[String], key: &str) -> String {
    args.iter()
        .position(|v| v == key)
        .and_then(|i| args.get(i + 1))
        .expect(key)
        .clone()
}
fn thread(root: &Path, mode: &str) -> Value {
    json!({"id":"fixture-thread","cwd":root,"title":"isolated native workload","status":{"type":if mode=="idle"{"idle"}else{"active"}},"turns":[],"createdAt":1,"updatedAt":1})
}
fn signal(root: &Path, mode: &str) {
    std::fs::write(
        root.join("native-mode.json"),
        json!({"mode":mode}).to_string(),
    )
    .unwrap();
    if mode == "idle" {
        emit(
            json!({"method":"serverRequest/resolved","params":{"threadId":"fixture-thread","requestId":"fixture-question"}}),
        );
        emit(
            json!({"method":"turn/completed","params":{"threadId":"fixture-thread","turn":{"id":"fixture-turn","status":"completed"}}}),
        );
        emit(
            json!({"method":"thread/status/changed","params":{"threadId":"fixture-thread","status":{"type":"idle"}}}),
        );
    } else {
        emit(
            json!({"method":"turn/started","params":{"threadId":"fixture-thread","turn":{"id":"fixture-turn","status":"inProgress"}}}),
        );
        if mode == "waiting" {
            emit(
                json!({"id":"fixture-question","method":"item/commandExecution/requestApproval","params":{"threadId":"fixture-thread","turnId":"fixture-turn","itemId":"fixture-item","command":"echo fixture","cwd":root}}),
            );
        }
    }
}
fn native(args: &[String], root: &Path) {
    if args.iter().any(|a| a == "generate-json-schema") {
        let out = PathBuf::from(arg(args, "--out"));
        assert!(out.starts_with(root));
        let schema = std::env::var_os("POCKET_FIXTURE_SCHEMA")
            .map(PathBuf::from)
            .unwrap();
        std::fs::copy(schema, out.join("ClientRequest.json")).unwrap();
        return;
    }
    std::fs::write(root.join("native-env.json"),json!({"operation":std::env::var("POCKET_UPDATE_OPERATION").ok(),"release":std::env::var("POCKET_UPDATE_RELEASE_FILE").ok()}).to_string()).unwrap();
    let mut mode = "idle".to_owned();
    let mut reads = 0;
    for line in std::io::stdin().lock().lines() {
        let Ok(line) = line else { break };
        let mut trace = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(root.join(format!("native-input-{}.jsonl", std::process::id())))
            .unwrap();
        writeln!(trace, "{line}").unwrap();
        let Ok(v) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        let Some(id) = v.get("id") else { continue };
        let result = match v["method"].as_str().unwrap_or("") {
            "initialize" => {
                json!({"userAgent":"pocket-native-fixture/1","version":"fixture-native","platformFamily":"windows","platformOs":"windows"})
            }
            "thread/resume" | "thread/start" => {
                json!({"thread":thread(root,&mode),"model":"fixture","modelProvider":"fixture","cwd":root,"approvalPolicy":"never","sandbox":{"type":"readOnly"}})
            }
            "thread/read" => {
                reads += 1;
                std::fs::write(root.join("native-read-evidence.json"),json!({"reads":reads,"threadId":"fixture-thread","mode":mode,"pid":std::process::id()}).to_string()).unwrap();
                json!({"thread":thread(root,&mode)})
            }
            "thread/loaded/list" => json!({"data":["fixture-thread"],"nextCursor":null}),
            "thread/list" => json!({"data":[thread(root,&mode)],"nextCursor":null}),
            "model/list" => json!({"data":[],"nextCursor":null}),
            "account/read" => json!({"account":null,"requiresOpenaiAuth":false}),
            "config/read" => json!({"config":{},"origins":{},"layers":[]}),
            "fixture/setMode" => {
                mode = v["params"]["mode"].as_str().unwrap().into();
                signal(root, &mode);
                json!({"mode":mode})
            }
            "turn/start" => {
                mode = "active".into();
                signal(root, &mode);
                json!({"turn":{"id":"fixture-turn","status":"inProgress","items":[]}})
            }
            _ => json!({"data":[],"nextCursor":null}),
        };
        emit(json!({"id":id,"result":result}));
    }
}
fn connection(args: &[String], root: &Path) -> (std::process::Child, std::process::ChildStdin) {
    let bridge = PathBuf::from(arg(args, "--bridge"));
    let binary = PathBuf::from(arg(args, "--native"));
    assert!(bridge.starts_with(root) && binary.starts_with(root));
    let mut child = Command::new(&bridge)
        .args(["--original"])
        .arg(binary)
        .args(["--", "app-server", "--fixture-root"])
        .arg(root)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(
            std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(root.join("bridge-stderr.log"))
                .unwrap(),
        )
        .spawn()
        .unwrap();
    let mut stdin = child.stdin.take().unwrap();
    let output = child.stdout.take().unwrap();
    let log = root.join("desktop-wire.jsonl");
    let (initialized, initialize_reply) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let mut f = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log)
            .unwrap();
        for l in std::io::BufReader::new(output)
            .lines()
            .map_while(Result::ok)
        {
            writeln!(f, "{l}").unwrap();
            if serde_json::from_str::<Value>(&l)
                .is_ok_and(|v| v["id"] == "desktop-init" && v["result"].is_object())
            {
                let _ = initialized.send(());
            }
        }
    });
    if !args.iter().any(|a| a == "--skip-initialize") {
        writeln!(stdin,"{}",json!({"id":"desktop-init","method":"initialize","params":{"clientInfo":{"name":"codex-desktop","version":"fixture","title":"Fixture"},"capabilities":{"experimentalApi":true}}})).unwrap();
        stdin.flush().unwrap();
        initialize_reply
            .recv_timeout(Duration::from_secs(10))
            .expect("native initialize response before initialized notification");
        if !args.iter().any(|a| a == "--withhold-initialized") {
            writeln!(stdin, "{}", json!({"method":"initialized"})).unwrap();
            stdin.flush().unwrap();
        }
        writeln!(stdin,"{}",json!({"id":"desktop-resume","method":"thread/resume","params":{"threadId":"fixture-thread","excludeTurns":false}})).unwrap();
        stdin.flush().unwrap();
    }
    (child, stdin)
}
fn desktop(args: &[String], root: &Path) {
    std::fs::write(root.join("desktop-env.json"),json!({"operation":std::env::var("POCKET_UPDATE_OPERATION").ok(),"release":std::env::var("POCKET_UPDATE_RELEASE_FILE").ok()}).to_string()).unwrap();
    let count = if args.iter().any(|a| a == "--multiple") {
        2
    } else {
        1
    };
    let mut children: Vec<_> = (0..count).map(|_| connection(args, root)).collect();
    let mut seen = String::new();
    loop {
        if children
            .iter_mut()
            .all(|(child, _)| child.try_wait().unwrap().is_some())
        {
            return;
        }
        if let Ok(text) = std::fs::read_to_string(root.join("desktop-command.json")) {
            if text != seen {
                seen = text.clone();
                let v: Value = serde_json::from_str(&text).unwrap();
                if v["mode"] == "exit" {
                    for (mut child, stdin) in children {
                        drop(stdin);
                        child.wait().unwrap();
                    }
                    return;
                }
                if v["mode"] == "restart" {
                    std::fs::remove_file(root.join("desktop-command.json")).unwrap();
                    for (mut child, stdin) in children {
                        drop(stdin);
                        child.wait().unwrap();
                    }
                    desktop(args, root);
                    return;
                }
                for (_, stdin) in &mut children {
                    writeln!(stdin,"{}",json!({"id":format!("desktop-mode-{}",v["revision"]),"method":"fixture/setMode","params":{"mode":v["mode"]}})).unwrap();
                    stdin.flush().unwrap();
                }
            }
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let root = PathBuf::from(if args.iter().any(|a| a == "--fixture-root") {
        arg(&args, "--fixture-root")
    } else {
        std::env::var("POCKET_FIXTURE_ROOT").expect("isolated schema probe root")
    });
    assert!(root.is_absolute() && root.join(".fixture-owner").is_file());
    if args.iter().any(|a| a == "--desktop") {
        desktop(&args, &root)
    } else {
        native(&args, &root)
    }
}
