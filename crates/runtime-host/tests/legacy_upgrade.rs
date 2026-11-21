#![cfg(windows)]
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::time::Duration;

#[tokio::test]
async fn installed_successor_automatically_retires_idle_legacy_and_preserves_unknown() {
    use pocket_agent::managed_runtime::{Controller,Installation,Registry,SelectedProfile};
    let dir=tempfile::tempdir().unwrap();
    let old=dir.path().join("old");std::fs::create_dir(&old).unwrap();
    let install=|path:&std::path::Path,source:&str,version:&str| {
        std::fs::create_dir_all(path).unwrap();
        let bytes=std::fs::read(source).unwrap();
        std::fs::write(path.join("pocket-runtime-host.exe"),&bytes).unwrap();
        let manifest=serde_json::to_vec(&json!({"schema":1,"product":"Codex Pocket","version":version,"files":[
            {"path":"pocket-runtime-host.exe","bytes":bytes.len(),"sha256":format!("{:x}",Sha256::digest(&bytes))}]})).unwrap();
        std::fs::write(path.join("windows-manifest.json"),&manifest).unwrap();
        Installation::new(path,format!("{:x}",Sha256::digest(&manifest))).unwrap()
    };
    let original=install(&old,env!("CARGO_BIN_EXE_pocket-host-fixture"),"legacy");
    let mut child=tokio::process::Command::new(old.join("pocket-runtime-host.exe"))
        .args(["--legacy-upgrade-fixture",dir.path().to_str().unwrap(),original.generation()])
        .creation_flags(0x08000000).kill_on_drop(true).spawn().unwrap();
    let old_state=dir.path().join("state");
    let d=tokio::time::timeout(Duration::from_secs(10),async {
        loop {if let Ok(d)=pocket_runtime_ipc::load_descriptor(&old_state.join("descriptor.json"),dir.path()){break d}
            tokio::time::sleep(Duration::from_millis(10)).await;}
    }).await.unwrap();
    let before:Value=serde_json::from_slice(&std::fs::read(old_state.join("recovery.json")).unwrap()).unwrap();
    let profile=SelectedProfile::new(dir.path(),old.join("pocket-runtime-host.exe")).unwrap();
    let registry=Registry::new(dir.path().join("registry")).unwrap();
    let state=registry.profile_directory(&profile).unwrap();
    std::fs::rename(&old_state,&state).unwrap();
    let new=install(&dir.path().join("new"),env!("CARGO_BIN_EXE_pocket-runtime-host"),"successor");
    let controller=Controller::new(new,registry);
    let runtime=controller.ensure(&profile).await.unwrap();
    let health=runtime.health().await.unwrap();
    assert_ne!(runtime.generation(),d.generation,"successor must replace the idle legacy Host automatically");
    assert_eq!(health.scoped_admission,1);
    assert!(d.host_process.as_ref().unwrap().terminated());
    assert!(d.native_process.as_ref().unwrap().terminated());
    child.wait().await.unwrap();
    assert_eq!(runtime.receipt(json!("old")).await.unwrap().state,"unknown");
    let recovery=runtime.recovery().await.unwrap();
    assert_eq!(recovery["unknownOperations"],1);
    let archived:Value=serde_json::from_slice(&std::fs::read(state.join(format!("legacy-upgrade-{}.json",d.generation))).unwrap()).unwrap();
    assert_eq!(archived["recovery"]["commands"],before["commands"]);
    runtime.shutdown_idle().await.unwrap();
}

#[tokio::test]
async fn legacy_upgrade_retires_only_authenticated_idle_fixture_and_preserves_unknown_bytes() {
    let dir = tempfile::tempdir().unwrap();
    let bin = dir.path().join("bin");
    std::fs::create_dir(&bin).unwrap();
    let binary = bin.join("pocket-runtime-host.exe");
    std::fs::copy(env!("CARGO_BIN_EXE_pocket-host-fixture"), &binary).unwrap();
    let bytes = std::fs::read(&binary).unwrap();
    let manifest=serde_json::to_vec(&json!({"schema":1,"product":"Codex Pocket","version":"fixture","files":[
        {"path":"pocket-runtime-host.exe","bytes":bytes.len(),"sha256":format!("{:x}",Sha256::digest(&bytes))}]})).unwrap();
    let generation = format!("{:x}", Sha256::digest(&manifest));
    std::fs::write(bin.join("windows-manifest.json"), manifest).unwrap();
    let mut command = tokio::process::Command::new(binary);
    command
        .arg("--legacy-upgrade-fixture")
        .arg(dir.path())
        .arg(generation)
        .creation_flags(0x08000000)
        .kill_on_drop(true);
    let mut child = command.spawn().unwrap();
    let state = dir.path().join("state");
    let path = state.join("descriptor.json");
    let d = tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if let Ok(d) = pocket_runtime_ipc::load_descriptor(&path, dir.path()) {
                break d;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    let before = std::fs::read(state.join("recovery.json")).unwrap();
    let check = pocket_runtime_host::legacy_upgrade::legacy_upgrade(
        &state,
        &path,
        dir.path(),
        &d.generation,
        false,
    )
    .await
    .unwrap();
    assert_eq!(check["retired"], false);
    assert!(!d.host_process.as_ref().unwrap().terminated());
    assert!(
        !state
            .join(format!("legacy-upgrade-{}.json", d.generation))
            .exists()
    );
    assert!(
        pocket_runtime_host::legacy_upgrade::legacy_upgrade(
            &state,
            &path,
            dir.path(),
            &uuid::Uuid::new_v4().to_string(),
            true
        )
        .await
        .is_err()
    );
    let result = pocket_runtime_host::legacy_upgrade::legacy_upgrade(
        &state,
        &path,
        dir.path(),
        &d.generation,
        true,
    )
    .await
    .unwrap();
    assert_eq!(result["retired"], true);
    assert_eq!(result["unknownPreserved"], 1);
    child.wait().await.unwrap();
    assert!(d.host_process.as_ref().unwrap().terminated());
    assert!(d.native_process.as_ref().unwrap().terminated());
    assert_eq!(std::fs::read(state.join("recovery.json")).unwrap(), before);
    let archived: Value = serde_json::from_slice(
        &std::fs::read(state.join(format!("legacy-upgrade-{}.json", d.generation))).unwrap(),
    )
    .unwrap();
    assert_eq!(
        archived["recovery"],
        serde_json::from_slice::<Value>(&before).unwrap()
    );
}
