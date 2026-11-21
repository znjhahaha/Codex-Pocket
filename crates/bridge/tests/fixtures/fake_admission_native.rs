// Standalone synthetic native peer: no user configuration, network, or model calls.
use std::{
    io::{self, BufRead, Write},
    path::PathBuf,
    time::{Duration, Instant},
};
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let markers = PathBuf::from(std::env::var_os("POCKET_ADMISSION_MARKERS").unwrap());
    let schema = args.iter().any(|a| a == "generate-json-schema");
    let server = args.iter().any(|a| a == "app-server");
    let kind = if schema {
        "schema"
    } else if server {
        "native"
    } else {
        "passthrough"
    };
    // Native's first action observes the registration already persisted before spawn.
    let records = PathBuf::from(std::env::var_os("POCKET_INSTALL_ROOT").unwrap())
        .join("control/native-startups");
    let evidence: Vec<_> = std::fs::read_dir(records)
        .unwrap()
        .filter_map(Result::ok)
        .filter(|e| e.path().extension().is_some_and(|x| x == "json"))
        .map(|e| std::fs::read_to_string(e.path()).unwrap())
        .collect();
    std::fs::write(
        markers.join(format!("{kind}-{}", std::process::id())),
        evidence.join("\n"),
    )
    .unwrap();
    if schema {
        if std::env::var_os("POCKET_ADMISSION_SCHEMA_FAIL").is_some() {
            std::process::exit(1);
        }
        if let Some(path) = std::env::var_os("POCKET_ADMISSION_SCHEMA_WAIT") {
            let deadline = Instant::now() + Duration::from_secs(20);
            while !PathBuf::from(&path).exists() && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(10));
            }
        }
        let out = args.iter().position(|a| a == "--out").unwrap();
        std::fs::write(
            PathBuf::from(&args[out + 1]).join("ClientRequest.json"),
            include_str!("ClientRequest.json"),
        )
        .unwrap();
        return;
    }
    if !server {
        return;
    }
    for line in io::stdin().lock().lines() {
        let line = line.unwrap();
        let Some(start) = line.find("\"id\":\"") else {
            continue;
        };
        let id = line[start + 6..].split('"').next().unwrap();
        println!("{{\"id\":\"{id}\",\"result\":{{\"version\":\"synthetic-admission\"}}}}");
        io::stdout().flush().unwrap();
    }
}
