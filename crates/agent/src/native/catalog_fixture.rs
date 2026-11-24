//! Catalog-only test executable; no network, credentials, or session operations.
use std::io::{BufRead, Write};
fn main() {
    assert_eq!(std::env::args().skip(1).collect::<Vec<_>>(), ["app-server"]);
    let home = std::path::PathBuf::from(std::env::var_os("CODEX_HOME").unwrap());
    std::fs::write(home.join("catalog-pid"),std::process::id().to_string()).unwrap();
    for line in std::io::stdin().lock().lines() {
        let line = line.unwrap();
        let (method,id,result) = if line.contains("\"method\":\"initialize\"") {
            ("initialize","catalog-init","{}".to_owned())
        } else if line.contains("\"method\":\"initialized\"") {
            ("initialized","",String::new())
        } else if line.contains("\"method\":\"model/list\"") {
            let next = if line.contains("\"cursor\":\"second\"") { "null" } else { "\"second\"" };
            ("model/list","catalog-read",format!("{{\"data\":[{{\"model\":\"fixture/model\"}}],\"nextCursor\":{next}}}"))
        } else if line.contains("\"method\":\"collaborationMode/list\"") {
            ("collaborationMode/list","catalog-read","{\"data\":[]}".to_owned())
        } else { panic!("unexpected native operation") };
        let mut log = std::fs::OpenOptions::new().append(true).create(true).open(home.join("catalog-methods")).unwrap();
        writeln!(log,"{method}").unwrap();
        if method == "model/list" && home.join("catalog-hang").exists() {
            std::thread::sleep(std::time::Duration::from_secs(30));
        }
        if id.is_empty() { continue; }
        println!("{{\"id\":\"{id}\",\"result\":{result}}}");
        std::io::stdout().flush().unwrap();
    }
}
