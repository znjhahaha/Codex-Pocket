// Standalone, dependency-free native peer. Never starts a model or reads user config.
use std::{
    io::{self, BufRead, Write},
    path::PathBuf,
    time::Duration,
};
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let gate = PathBuf::from(std::env::var_os("POCKET_TEST_SCHEMA_GATE").unwrap());
    if args.iter().any(|a| a == "generate-json-schema") {
        while !gate.exists() {
            std::thread::sleep(Duration::from_millis(10));
        }
        let out = args.iter().position(|a| a == "--out").unwrap();
        std::fs::write(
            PathBuf::from(&args[out + 1]).join("ClientRequest.json"),
            include_str!("ClientRequest.json"),
        )
        .unwrap();
        return;
    }
    for line in io::stdin().lock().lines() {
        let line = line.unwrap();
        let Some(start) = line.find("\"id\":\"") else {
            continue;
        };
        let id = line[start + 6..].split('"').next().unwrap();
        println!("{{\"id\":\"{id}\",\"result\":{{\"version\":\"fake\"}}}}");
        io::stdout().flush().unwrap();
    }
}
