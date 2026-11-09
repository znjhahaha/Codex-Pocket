use anyhow::{bail,Context,Result};
use serde_json::Value;
use std::{path::{Path,PathBuf},process::Command};
use sha2::{Digest,Sha256};
use crate::{storage,process};
pub fn resolve_release(root:&Path)->Result<(PathBuf,PathBuf)>{
    let v:Value=storage::read(&root.join("desktop-install.json"))?;
    if v["version"]!=3 || v["installRoot"].as_str().map(Path::new)!=Some(root) || v["bridge"].as_str().map(Path::new)!=Some(root.join("pocket-launcher.exe").as_path()) {bail!("Invalid installation record")}
    let generation=v["generation"].as_str().context("Missing installed generation")?;
    if generation.len()!=64||!generation.bytes().all(|b|b.is_ascii_hexdigit()){bail!("Invalid generation")}
    let bin=root.join("bin").join(generation);storage::plain(&bin)?;
    let manifest_path=bin.join("windows-manifest.json");storage::unredirected(&manifest_path)?;
    let manifest=std::fs::read(manifest_path)?;
    let hash=format!("{:x}",Sha256::digest(&manifest));
    if v["releaseHash"].as_str()!=Some(&hash){bail!("Installed manifest has changed")}
    let state=PathBuf::from(v["stateDirectory"].as_str().context("Missing installed state")?);storage::plain(&state)?;
    Ok((bin,state))
}
pub fn verified_exe(bin:&Path,name:&str)->Result<PathBuf>{
    if !["pocket-desktop.exe","pocket-supervisor.exe","pocket-bridge.exe","pocket-agent.exe","windows-startup.ps1","windows-common.ps1"].contains(&name){bail!("Unsupported executable or script")}
    let manifest:Value=storage::read(&bin.join("windows-manifest.json"))?;
    let entry=manifest["files"].as_array().context("Missing inventory")?.iter().find(|e|e["path"]==name).context("Executable absent from inventory")?;
    let path=bin.join(name);storage::unredirected(&path)?;
    let bytes=std::fs::read(&path)?;
    if entry["sha256"]!=format!("{:x}",Sha256::digest(&bytes))||entry["bytes"].as_u64()!=Some(bytes.len() as u64){bail!("Installed executable failed integrity check")};Ok(path)
}
pub fn current_package()->Result<Value>{
    let mut cmd=Command::new(storage::powershell());
    cmd.args(["-NoProfile","-NonInteractive","-Command",r#"$ErrorActionPreference='Stop';$p=Get-AppxPackage -Name OpenAI.Codex | Sort-Object Version -Descending | Select-Object -First 1;if(!$p){throw 'Codex not installed'};@{location=$p.InstallLocation;family=$p.PackageFamilyName}|ConvertTo-Json -Compress"#]);
    Ok(serde_json::from_slice(&process::run_bounded(cmd,15)?)?)
}
pub fn launch()->Result<i32>{
    let args:Vec<_>=std::env::args_os().skip(1).collect();
    let root=storage::local_app_data()?.join("CodexPocket");let(bin,state)=resolve_release(&root)?;
    let mode=args.first().and_then(|s|s.to_str()).unwrap_or("");
    if ["--pocket-ui","--pocket-supervise"].contains(&mode){
        let name=if mode=="--pocket-ui"{"pocket-desktop.exe"}else{"pocket-supervisor.exe"};
        verified_exe(&bin,"pocket-agent.exe")?;
        let mut command=Command::new(verified_exe(&bin,name)?);command.arg("--state").arg(&state);process::hidden(&mut command);
        if mode=="--pocket-ui"{command.spawn()?;return Ok(0)}
        #[cfg(windows)]let job=process::ChildJob::new()?;
        let mut child=command.spawn()?;
        #[cfg(windows)]if let Err(e)=job.assign(&child){let _=child.kill();let _=child.wait();return Err(e)}
        return Ok(child.wait()?.code().unwrap_or(1))
    }
    let package=current_package()?;let location=PathBuf::from(package["location"].as_str().context("Missing package location")?);
    let native=location.join("app/resources/codex.exe");storage::plain(&native)?;if !native.is_file(){bail!("Current Codex runtime is missing")}
    let family=package["family"].as_str().context("Missing package family")?;
    let mut command;
    if mode=="--pocket-desktop"{
        let app=["app/ChatGPT.exe","app/Codex.exe"].iter().map(|p|location.join(p)).find(|p|p.is_file()).context("Desktop executable missing")?;
        command=Command::new(app);command.env("CODEX_CLI_PATH",std::env::current_exe()?);process::hidden(&mut command);
        command.spawn()?;return Ok(0)
    }else{command=Command::new(verified_exe(&bin,"pocket-bridge.exe")?);command.arg("--original").arg(&native).arg("--").args(args);}
    command.env("CODEX_WINDOWS_SANDBOX_PACKAGE_FAMILY",family).env("POCKET_RUNTIME_DIR",root.join("runtimes"));
    process::hidden(&mut command);
    Ok(command.status()?.code().unwrap_or(1))
}
