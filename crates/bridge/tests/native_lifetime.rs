//! A desktop can terminate its proxy without allowing Rust Drop to run.
#![cfg(windows)]
use pocket_core::native_process::NativeProcess;
use std::{path::Path, process::{Command, Stdio}, time::{Duration, Instant}};

fn native(root: &Path) -> std::path::PathBuf {
    let source = root.join("native.rs");
    std::fs::write(&source, r#"
fn main() {
    if std::env::args().any(|a| a == "generate-json-schema") { std::process::exit(1); }
    std::fs::write(std::env::var_os("POCKET_TEST_PID").unwrap(), std::process::id().to_string()).unwrap();
    loop { std::thread::sleep(std::time::Duration::from_millis(20)); }
}
"#).unwrap();
    let binary=root.join("native.exe");
    assert!(Command::new("rustc").arg(source).arg("-o").arg(&binary).status().unwrap().success());
    binary
}

#[test]
fn abrupt_proxy_exit_reaps_only_its_native_process() {
    let root=tempfile::tempdir().unwrap();
    let native=native(root.path());
    // A separate native process is deliberately not in the proxy's lifetime.
    let unrelated_pid=root.path().join("unrelated.pid");
    let mut unrelated=Command::new(&native).env("POCKET_TEST_PID",&unrelated_pid).spawn().unwrap();
    for transparent in [false,true] {
        let proxy=root.path().join(if transparent {"pocket-native-proxy.exe"} else {"pocket-bridge.exe"});
        std::fs::copy(env!("CARGO_BIN_EXE_pocket-bridge"),&proxy).unwrap();
        let pid_file=root.path().join(format!("{transparent}.pid"));
        let mut child=Command::new(&proxy).arg("--original").arg(&native).args(["--","app-server"])
            .env("POCKET_TEST_PID",&pid_file).env("POCKET_RUNTIME_DIR",root.path().join("runtimes"))
            .env_remove("POCKET_INSTALL_ROOT").env_remove("POCKET_UPDATE_OPERATION").env_remove("POCKET_UPDATE_RELEASE_FILE")
            .stdin(Stdio::piped()).stdout(Stdio::null()).stderr(Stdio::null()).spawn().unwrap();
        let until=Instant::now()+Duration::from_secs(10);
        let pid=loop {
            if let Some(pid)=std::fs::read_to_string(&pid_file).ok().and_then(|s|s.parse::<u32>().ok()) { break pid; }
            assert!(Instant::now()<until,"native did not start");
            assert!(child.try_wait().unwrap().is_none(),"proxy exited before native startup");
            std::thread::sleep(Duration::from_millis(20));
        };
        let identity=NativeProcess::capture(pid).unwrap();
        child.kill().unwrap();child.wait().unwrap();
        let until=Instant::now()+Duration::from_secs(3);
        while !identity.terminated() && Instant::now()<until {std::thread::sleep(Duration::from_millis(20));}
        let reaped=identity.terminated();
        if !reaped {
            // Clean up this test's exact fixture, never another native runtime.
            unsafe {
                use windows_sys::Win32::{Foundation::CloseHandle,System::Threading::*};
                let handle=OpenProcess(PROCESS_TERMINATE,0,pid);
                if !handle.is_null() {if NativeProcess::capture(pid).ok()==Some(identity) {TerminateProcess(handle,1);} CloseHandle(handle);}
            }
        }
        assert!(unrelated.try_wait().unwrap().is_none());
        if !reaped {unrelated.kill().unwrap();unrelated.wait().unwrap();}
        assert!(reaped,"abrupt proxy exit left its native process alive (transparent={transparent})");
    }
    unrelated.kill().unwrap();unrelated.wait().unwrap();
}
