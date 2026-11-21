//! Reconcile the authorized npm entry integration after npm/Pocket updates.
//! The installer validates its own prior bytes and refuses unrelated shim drift.
use std::{path::{Path,PathBuf}, time::{Duration,SystemTime}};
use serde_json::json;

fn stamps(paths: &[PathBuf]) -> Option<Vec<(u64,SystemTime)>> {
    paths.iter().map(|p|std::fs::metadata(p).ok().and_then(|m|Some((m.len(),m.modified().ok()?)))).collect()
}

pub async fn maintain(state: PathBuf) {
    let Ok(exe)=std::env::current_exe() else{return};
    let Some(bin)=exe.parent() else{return};
    let Some(root)=bin.parent().filter(|p|p.file_name().is_some_and(|n|n=="bin")).and_then(Path::parent) else{return};
    let Ok(record)=std::fs::read(root.join("desktop-install.json")) else{return};
    let Ok(record)=serde_json::from_slice::<serde_json::Value>(&record) else{return};
    if record["nativeProxyProtocol"]!=1 || record["stateDirectory"].as_str().map(Path::new)!=Some(state.as_path()) {return}
    let Some(appdata)=std::env::var_os("APPDATA") else{return};
    let npm=PathBuf::from(appdata).join("npm");
    let script=bin.join("cli-install.ps1");
    let paths=[npm.join("codex"),npm.join("codex.cmd"),npm.join("codex.ps1"),
        npm.join("node_modules/@openai/codex/package.json"),script.clone(),bin.join("cli-wrapper.mjs")];
    let Some(system)=std::env::var_os("SystemRoot") else{return};
    let shell=PathBuf::from(system).join("System32/WindowsPowerShell/v1.0/powershell.exe");
    let mut observed=None;
    loop {
        let current=stamps(&paths);
        if current.is_some() && current!=observed {
            let outcome=tokio::process::Command::new(&shell)
                .args(["-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File"])
                .arg(&script).arg("-LauncherDirectory").arg(&npm).args(["-Apply","-Upgrade"])
                .env_remove("PSModulePath").creation_flags(0x08000000).kill_on_drop(true)
                .stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null())
                .status();
            let success=matches!(tokio::time::timeout(Duration::from_secs(25),outcome).await,Ok(Ok(code))if code.success());
            let _=crate::storage::atomic_write(&state.join("cli-integration.json"),
                &serde_json::to_vec(&json!({"schema":1,"state":if success{"installed"}else{"repair_required"},
                    "updatedAt":crate::storage::now(),"generation":bin.file_name().and_then(|n|n.to_str())})).unwrap());
            // On failure wait for input/source changes rather than spinning a
            // repair operation. The preserved update journal recovers next boot.
            observed=stamps(&paths);
        }
        tokio::time::sleep(Duration::from_secs(15)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reinstallation_is_detected_and_absent_cli_does_not_request_repair() {
        let root=tempfile::tempdir().unwrap();let shim=root.path().join("codex.cmd");
        assert!(stamps(&[shim.clone()]).is_none());
        std::fs::write(&shim,b"wrapper").unwrap();let before=stamps(&[shim.clone()]);
        assert_eq!(stamps(&[shim.clone()]),before);
        std::fs::write(&shim,b"npm replacement").unwrap();assert_ne!(stamps(&[shim]),before);
    }
}
