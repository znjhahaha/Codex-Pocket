//! Offline stdio/schema fixture. Has no network or model SDK and reads no auth.
use std::io::{BufRead, Write};
fn emit(value: String) {
    println!("{value}");
    std::io::stdout().flush().unwrap();
}
fn field<'a>(value: &'a str, name: &str) -> &'a str {
    let marker = format!("\"{name}\":");
    let Some((_, rest)) = value.split_once(&marker) else {
        return "null";
    };
    if let Some(rest) = rest.strip_prefix('"') {
        let end = rest.find('"').unwrap();
        &value[value.len() - rest.len() - 1..value.len() - rest.len() + end + 1]
    } else {
        rest.split([',', '}']).next().unwrap()
    }
}
fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.get(1).is_some_and(|v| v == "generate-json-schema") {
        let out = args.iter().position(|v| v == "--out").unwrap();
        std::fs::write(
            std::path::Path::new(&args[out + 1]).join("ClientRequest.json"),
            include_str!("../../../../bridge/tests/fixtures/ClientRequest.json"),
        )
        .unwrap();
        return;
    }
    assert_eq!(args, ["app-server", "--listen", "stdio://"]);
    let home = std::env::var("CODEX_HOME").unwrap();
    let stop = std::path::Path::new(&home).join("fixture-stop");
    std::thread::spawn(move || {
        loop {
            if stop.exists() {
                std::process::exit(0);
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
    });
    let mut initialized = 0;
    let mut ready = false;
    let mut writes = 0;
    let mut answers = 0;
    let mut waiting: Option<String> = None;
    let mut deferred: Option<String> = None;
    for line in std::io::stdin().lock().lines() {
        let value = line.unwrap();
        let id = field(&value, "id").to_owned();
        let method = field(&value, "method");
        if method == "null" && id == "77" {
            answers += 1;
            emit("{\"method\":\"serverRequest/resolved\",\"params\":{\"requestId\":77}}".into());
            if let Some(original) = waiting.take() {
                emit(format!(
                    "{{\"id\":{original},\"result\":{{\"answered\":true}}}}"
                ));
            }
            continue;
        }
        let result = match method {
            "\"initialize\"" => { initialized += 1; format!("{{\"codexHome\":{},\"userAgent\":\"fixture/1.0\",\"platformFamily\":\"windows\",\"platformOs\":\"windows\"}}", quote(&home)) }
            "\"initialized\"" => { ready = true; continue; }
            "\"fixture/info\"" => format!("{{\"home\":{},\"initialized\":{initialized},\"ready\":{ready},\"writes\":{writes},\"answers\":{answers}}}",quote(&home)),
            "\"turn/start\"" => { assert!(ready); writes += 1; emit("{\"method\":\"turn/started\",\"params\":{\"threadId\":\"fixture-thread\",\"turn\":{\"id\":\"fixture-turn\"}}}".into()); "{\"turn\":{\"id\":\"fixture-turn\"},\"writes\":1}".into() }
            "\"fixture/finish\"" => { emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"fixture-thread\",\"turn\":{\"id\":\"fixture-turn\",\"status\":\"completed\"}}}".into()); "{}".into() }
            "\"fixture/waitForHuman\"" => { waiting = Some(id); emit("{\"id\":77,\"method\":\"item/tool/requestUserInput\",\"params\":{\"threadId\":\"fixture-thread\",\"questions\":[]}}".into()); continue; }
            "\"fixture/deferred\"" => { writes += 1; deferred = Some(id); continue; }
            "\"fixture/release\"" => { if let Some(original) = deferred.take() { emit(format!("{{\"id\":{original},\"result\":{{\"released\":true}}}}")); } "{}".into() }
            "\"thread/read\"" => "{\"thread\":{\"id\":\"fixture-thread\",\"turns\":[{\"id\":\"fixture-turn\",\"status\":\"completed\"}]}}".into(),
            "\"fixture/exit\"" => break,
            _ => "{}".into(),
        };
        emit(format!("{{\"id\":{id},\"result\":{result}}}"));
    }
}
fn quote(value: &str) -> String {
    format!("\"{}\"", value.replace('\\', "\\\\").replace('"', "\\\""))
}
