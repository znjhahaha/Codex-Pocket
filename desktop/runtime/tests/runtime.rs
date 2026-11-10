use pocket_desktop_runtime::{launcher, process, storage};
#[path = "../test_support/mod.rs"]
mod test_support;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::{fs, path::Path, process::{Child, Command, Stdio}, thread, time::{Duration, Instant}};

#[test]
fn launcher_resolves_new_generation_and_refuses_tampered_payloads() {
    let dir=test_support::tempdir(); let root=dir.path();
    for number in [1,2] {
        let bytes=format!("fixture executable {number}").into_bytes();
        let manifest=serde_json::to_vec(&json!({"files":[{"path":"pocket-desktop.exe","sha256":format!("{:x}",Sha256::digest(&bytes)),"bytes":bytes.len()}]})).unwrap();
        let hash=format!("{:x}",Sha256::digest(&manifest));let bin=root.join("bin").join(&hash);fs::create_dir_all(&bin).unwrap();
        fs::write(bin.join("windows-manifest.json"),&manifest).unwrap();fs::write(bin.join("pocket-desktop.exe"),bytes).unwrap();
        storage::atomic(&root.join("desktop-install.json"),&json!({"version":3,"installRoot":root,"bridge":root.join("pocket-launcher.exe"),"generation":hash,"releaseHash":hash,"stateDirectory":root.join("state")})).unwrap();
        assert_eq!(launcher::resolve_release(root).unwrap().0,bin);
        assert!(launcher::verified_exe(&bin,"pocket-desktop.exe").is_ok());
        fs::write(bin.join("pocket-desktop.exe"),"tampered").unwrap();
        assert!(launcher::verified_exe(&bin,"pocket-desktop.exe").is_err());
        fs::write(bin.join("windows-manifest.json"),"{}").unwrap();assert!(launcher::resolve_release(root).is_err());
    }
}
#[test]
fn locks_prevent_duplicate_ownership_and_persist_explicit_stop() {
    let dir=test_support::tempdir();let path=dir.path().join("lock");
    let first=storage::Lock::acquire(&path).unwrap();assert!(storage::Lock::acquire(&path).is_err());drop(first);assert!(storage::Lock::acquire(&path).is_ok());
    storage::set_desired(dir.path(),true).unwrap();assert!(storage::desired(dir.path()));
    storage::set_desired(dir.path(),false).unwrap();assert!(!storage::desired(dir.path()));
}
#[cfg(windows)]
#[test]
fn atomic_status_survives_a_short_reader_without_delete_sharing() {
    use std::os::windows::fs::OpenOptionsExt;
    let dir=test_support::tempdir();let path=dir.path().join("status.json");
    storage::atomic(&path,&json!({"sequence":1})).unwrap();
    let reader=fs::OpenOptions::new().read(true).share_mode(3).open(&path).unwrap();
    let release=thread::spawn(move||{thread::sleep(Duration::from_millis(180));drop(reader)});
    let result=storage::atomic(&path,&json!({"sequence":2}));release.join().unwrap();
    result.expect("brief readers must not terminate the supervising process");
    assert_eq!(storage::read::<serde_json::Value>(&path).unwrap()["sequence"],2);
}
#[test]
fn refuses_path_aliases_and_parent_traversal() {
    assert!(storage::plain(Path::new("relative")).is_err());
    #[cfg(windows)]for path in [r"C:\safe\..\outside",r"\\?\C:\safe",r"\\server\share\safe",r"C:\safe:stream"] {assert!(storage::plain(Path::new(path)).is_err(),"{path}");}
}
#[test]
fn physical_file_checks_cover_each_state_file_and_missing_children() {
    let dir=test_support::tempdir();
    let file=dir.path().join("state.json");fs::write(&file,"{}").unwrap();
    storage::unredirected(&file).unwrap();
    storage::unredirected(Path::new(&file.to_string_lossy().replace('\\',"/"))).unwrap();
    storage::unredirected(&dir.path().join("not-created/state.json")).unwrap();
    assert!(storage::unredirected(Path::new("relative")).is_err());
}
#[cfg(windows)]
#[test]
fn bounded_process_times_out_without_exposing_stderr() {
    let mut cmd=Command::new(storage::powershell());cmd.args(["-NoProfile","-Command","Start-Sleep -Seconds 20"]);
    let started=Instant::now();assert!(process::run_bounded(cmd,1).unwrap_err().to_string().contains("timed out"));assert!(started.elapsed()<Duration::from_secs(5));
    let mut cmd=Command::new(storage::powershell());cmd.args(["-NoProfile","-Command","[Console]::Error.WriteLine('private-secret');exit 5"]);
    let error=process::run_bounded(cmd,5).unwrap_err().to_string();assert!(!error.contains("private-secret"));
}
#[cfg(windows)]
#[test]
fn failed_helper_preserves_safe_code_and_exit_status_without_raw_output() {
    let mut cmd=Command::new(storage::powershell());
    cmd.args(["-NoProfile","-Command",r#"[Console]::WriteLine('{"error":{"code":"rejected","message":"private-secret"}}');exit 5"#]);
    let err=process::run_bounded(cmd,5).unwrap_err();
    let public=serde_json::to_value(pocket_desktop_runtime::connection::OperationError::from_error("routes_read",&err)).unwrap();
    assert_eq!(public["code"],"control_rejected");
    assert_eq!(public["exitCode"],5);
    assert!(!public.to_string().contains("private-secret"));
}
struct Owned(Child);
impl Drop for Owned {fn drop(&mut self){let _=self.0.kill();let _=self.0.wait();}}
fn wait_for(mut predicate:impl FnMut()->bool) {let until=Instant::now()+Duration::from_secs(25);while !predicate(){assert!(Instant::now()<until,"fixture did not reach expected state");thread::sleep(Duration::from_millis(100));}}
#[cfg(windows)]
#[test]
fn real_supervisor_backoff_and_local_control_retry_survive_remote_pause_and_relaunch() {
    let dir=test_support::tempdir();let state=dir.path().join("state");fs::create_dir(&state).unwrap();fs::write(state.join("config.json"),"{}").unwrap();storage::set_desired(&state,true).unwrap();
    // cmd.exe exits on the helper's arguments. Only this fixture's child is supervised.
    let spawn=||{let mut c=Command::new(env!("CARGO_BIN_EXE_pocket-supervisor"));c.arg("--state").arg(&state).arg("--agent").arg(std::env::var("COMSPEC").unwrap()).arg("--owner-directory").arg(dir.path().join("owner")).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());process::hidden(&mut c);Owned(c.spawn().unwrap())};
    let mut first=spawn();let heartbeat=storage::control(&state).join("supervisor.json");
    wait_for(||storage::read::<serde_json::Value>(&heartbeat).is_ok_and(|s|s["failures"].as_u64().unwrap_or(0)>=2));
    let mut duplicate=spawn();wait_for(||duplicate.0.try_wait().unwrap().is_some());assert!(!duplicate.0.wait().unwrap().success());
    storage::set_desired(&state,false).unwrap();wait_for(||storage::read::<serde_json::Value>(&heartbeat).is_ok_and(|s|s["phase"]=="restarting"&&s["childPid"].is_null()));
    first.0.kill().unwrap();first.0.wait().unwrap();let mut next=spawn();
    // The disposable cmd fixture has no remote gate/local control. Never
    // claim stop confirmation; retry supervised control despite desired=false.
    wait_for(||storage::read::<serde_json::Value>(&heartbeat).is_ok_and(|s|s["pid"].as_u64()==Some(next.0.id() as u64)&&s["phase"]=="restarting"&&s["failures"].as_u64().unwrap_or(0)>0));assert!(next.0.try_wait().unwrap().is_none());
}
