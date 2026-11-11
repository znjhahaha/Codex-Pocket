//! Deterministic test peer. Never included in Pocket delivery packages.
use serde_json::{Value, json};
use std::io::{BufRead, Write};
fn main() {
    // Give lifecycle tests a real Host process identity, independent of the test runner.
    let mut args = std::env::args().skip(1);
    let mode = args.next();
    if mode.as_deref() == Some("--host-test-driver") {
        let root = std::path::PathBuf::from(args.next().expect("fixture root"));
        assert!(args.next().is_none());
        let config = pocket_runtime_host::HostConfig {
            state: root.join("state"),
            descriptor: root.join("registry/host.json"),
            binary: std::env::current_exe().unwrap(),
            native_args: vec!["--exit-file".into(), root.join("native-exit").to_string_lossy().into_owned()],
            config_home: root,
        };
        tokio::runtime::Runtime::new().unwrap().block_on(pocket_runtime_host::run(config)).unwrap();
        return;
    }
    let exit_file = (mode.as_deref() == Some("--exit-file"))
        .then(|| std::path::PathBuf::from(args.next().expect("exit sentinel")));
    let (send, receive) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        for line in std::io::stdin().lock().lines() {
            if send.send(line).is_err() { break; }
        }
    });
    let mut writes = 0u64;
    let mut answers = 0u64;
    loop {
        if exit_file.as_ref().is_some_and(|path| path.exists()) { break; }
        let line = match receive.recv_timeout(std::time::Duration::from_millis(20)) {
            Ok(line) => line,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => continue,
            Err(_) => break,
        };
        let Ok(line) = line else {
            break;
        };
        let Ok(value) = serde_json::from_str::<Value>(&line) else {
            break;
        };
        let method = value["method"].as_str().unwrap_or("");
        if method.is_empty() && (value["id"] == 77 || value["id"] == 78) {
            answers += 1;
            // Deliberately reflect private answers in an event/error payload:
            // the host's live transport may carry it, its disk journal may not.
            emit(json!({"method":"fixture/answerEcho","params":{"error":{"message":value}}}));
            emit(json!({"method":"serverRequest/resolved","params":{"requestId":value["id"]}}));
            continue;
        }
        if method == "initialized" {
            continue;
        }
        let result = match method {
            "initialize" => json!({"userAgent":"deterministic-pocket-fixture"}),
            "turn/start" => {
                writes += 1;
                emit(
                    json!({"method":"turn/started","params":{"threadId":"fixture-thread","turn":{"id":"fixture-turn"}}}),
                );
                json!({"turn":{"id":"fixture-turn"},"writes":writes})
            }
            "fixture/finish" => {
                emit(
                    json!({"method":"turn/completed","params":{"threadId":"fixture-thread","turn":{"id":"fixture-turn","status":"completed"}}}),
                );
                json!({"writes":writes})
            }
            "fixture/count" => json!({"writes":writes,"answers":answers}),
            "thread/read" => json!({"thread":{"id":value["params"]["threadId"],"turns":[]},"writes":writes,"answers":answers}),
            "fixture/question" => {
                emit(
                    json!({"id":77,"method":"item/commandExecution/requestApproval","params":{"threadId":"fixture-thread","command":"fixture only"}}),
                );
                json!({"question":77})
            }
            "fixture/private-question" => {
                let id = value["params"]
                    .get("requestId")
                    .cloned()
                    .unwrap_or(json!(77));
                emit(
                    json!({"id":id,"method":"item/tool/requestUserInput","params":{"threadId":"fixture-thread","questions":[{"id":"pin","isSecret":true,"question":"fixture-private-question-text"}]}}),
                );
                json!({"question":id})
            }
            "fixture/private-result" => {
                writes += 1;
                emit(json!({"method":"fixture/privateMessage","params":value["params"]}));
                value["params"].clone()
            }
            "fixture/deferred" => {
                writes += 1;
                continue;
            }
            "fixture/exit" => break,
            _ => json!({}),
        };
        emit(json!({"id":value["id"],"result":result}));
    }
}
fn emit(value: Value) {
    println!("{value}");
    std::io::stdout().flush().unwrap();
}
