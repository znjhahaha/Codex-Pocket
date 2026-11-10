//! Deterministic test peer. Never included in Pocket delivery packages.
use serde_json::{Value, json};
use std::io::{BufRead, Write};
fn main() {
    let mut writes = 0u64;
    let mut answers = 0u64;
    for line in std::io::stdin().lock().lines() {
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
