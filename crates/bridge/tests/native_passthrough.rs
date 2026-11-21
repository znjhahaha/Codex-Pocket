//! Native startup/policy probes must work even with unusable Pocket state.
use std::{path::Path, process::Command};

fn native(root: &Path) -> std::path::PathBuf {
    let source = root.join("probe.rs");
    std::fs::write(&source, r#"
fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    println!("native:{}", args.join("|"));
    eprintln!("native-stderr");
    std::process::exit(23);
}
"#).unwrap();
    let binary = root.join(if cfg!(windows) {"native.exe"} else {"native"});
    assert!(Command::new("rustc").arg(source).arg("-o").arg(&binary).status().unwrap().success());
    binary
}

#[test]
fn native_probes_preserve_output_and_exit_without_installation_or_admission() {
    let root = tempfile::tempdir().unwrap();
    let native = native(root.path());
    let broken = root.path().join("unavailable-pocket");
    std::fs::write(&broken, b"not a directory").unwrap();
    for args in [vec!["--version"], vec!["--help"], vec!["app-server", "--print-startup-config"],
        vec!["debug", "policy-read"], vec!["app-server", "generate-json-schema"], vec!["app-server", "proxy"]] {
        let output = Command::new(env!("CARGO_BIN_EXE_pocket-bridge"))
            .arg("--original").arg(&native).arg("--").args(&args)
            .env("POCKET_INSTALL_ROOT", &broken).env("POCKET_RUNTIME_DIR", &broken)
            .env("POCKET_UPDATE_OPERATION", "unavailable-update")
            .env_remove("POCKET_UPDATE_RELEASE_FILE").output().unwrap();
        assert_eq!(output.status.code(), Some(23), "{args:?}: {}", String::from_utf8_lossy(&output.stderr));
        assert_eq!(String::from_utf8(output.stdout).unwrap().trim(), format!("native:{}",args.join("|")));
        assert_eq!(String::from_utf8(output.stderr).unwrap().trim(), "native-stderr");
        assert_eq!(std::fs::read(&broken).unwrap(), b"not a directory");
    }
}

#[test]
fn transparent_app_server_keeps_native_channel_when_pocket_state_is_unavailable() {
    use std::io::{BufRead, BufReader, Write};
    use std::process::Stdio;
    let root = tempfile::tempdir().unwrap();
    let native = root.path().join("fake-native.exe");
    assert!(Command::new("rustc").arg(std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/fake_lifecycle_native.rs")).arg("-o").arg(&native).status().unwrap().success());
    let proxy = root.path().join("pocket-native-proxy.exe");
    std::fs::copy(env!("CARGO_BIN_EXE_pocket-bridge"), &proxy).unwrap();
    let blocked = root.path().join("unwritable-pocket");
    std::fs::write(&blocked,b"not a directory").unwrap();
    let gate = root.path().join("schema-ready"); std::fs::write(&gate,b"ready").unwrap();
    let mut child = Command::new(&proxy).arg("--original").arg(&native).args(["--","app-server"])
        .env("POCKET_INSTALL_ROOT",&blocked).env("POCKET_RUNTIME_DIR",&blocked)
        .env("POCKET_UPDATE_OPERATION","old-update-context").env_remove("POCKET_UPDATE_RELEASE_FILE")
        .env("POCKET_TEST_SCHEMA_GATE",gate).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped())
        .spawn().unwrap();
    let mut stdin = child.stdin.take().unwrap();
    stdin.write_all(b"{\"id\":1,\"method\":\"initialize\",\"params\":{}}\n").unwrap();
    let mut line = String::new(); BufReader::new(child.stdout.take().unwrap()).read_line(&mut line).unwrap();
    drop(stdin);
    let _ = child.kill(); let _ = child.wait();
    assert!(line.contains("\"id\":1"), "native initialize must remain available: {line}");
    assert_eq!(std::fs::read(blocked).unwrap(),b"not a directory");
}
