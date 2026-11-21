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
    if generation!=hash || v["releaseHash"].as_str()!=Some(&hash){bail!("Installed manifest has changed")}
    let state=PathBuf::from(v["stateDirectory"].as_str().context("Missing installed state")?);storage::plain(&state)?;
    Ok((bin,state))
}
pub fn verified_exe(bin:&Path,name:&str)->Result<PathBuf>{
    if !["pocket-desktop.exe","pocket-supervisor.exe","pocket-bridge.exe","pocket-native-proxy.exe","pocket-agent.exe","pocket-upgrade.exe","pocket-launcher.exe","windows-startup.ps1","windows-common.ps1","windows-install.ps1","windows-update-trust.json"].contains(&name){bail!("Unsupported executable or script")}
    let manifest:Value=storage::read(&bin.join("windows-manifest.json"))?;
    let entry=manifest["files"].as_array().context("Missing inventory")?.iter().find(|e|e["path"]==name).context("Executable absent from inventory")?;
    let path=bin.join(name);storage::unredirected(&path)?;
    let bytes=std::fs::read(&path)?;
    if entry["sha256"]!=format!("{:x}",Sha256::digest(&bytes))||entry["bytes"].as_u64()!=Some(bytes.len() as u64){bail!("Installed executable failed integrity check")};Ok(path)
}
/// Start local control independently of optional Windows logon registration.
/// This acknowledges process creation only; the authenticated heartbeat and
/// installation report remain the authority for readiness and running generation.
pub fn start_supervisor(bin:&Path,state:&Path)->Result<()> {
    background_command(bin,state)?.spawn().context("Could not start Pocket background")?;
    Ok(())
}
/// Decide before the old supervisor can take either owner lock. An installer
/// coordinator is independent of the window and also starts the selected helper.
pub fn background_command(bin: &Path, state: &Path) -> Result<Command> {
    let normal = supervisor_command(bin, state)?;
    let Some(bins) = bin.parent().filter(|p| p.file_name().is_some_and(|n| n=="bin")) else {return Ok(normal)};
    let root = bins.parent().context("Missing installation root")?;
    if let Some(command) = crate::local_update::background_update_command(root, state, bin)? {
        return Ok(command);
    }
    Ok(normal)
}
pub(crate) fn spawn_supervisor(bin:&Path,state:&Path)->Result<std::process::Child> {
    supervisor_command(bin,state)?.spawn().context("Could not start Pocket supervisor")
}
fn supervisor_command(bin:&Path,state:&Path)->Result<Command> {
    storage::unredirected(state)?;
    // A launch must never initialize missing state or replace an existing identity.
    let config:Value=storage::read(&state.join("config.json"))?;
    if !config.is_object(){bail!("Invalid Pocket configuration")}
    verified_exe(bin,"pocket-agent.exe")?;
    let mut command=Command::new(verified_exe(bin,"pocket-supervisor.exe")?);
    command.current_dir(bin);
    command.arg("--state").arg(state);
    if bin.parent().is_some_and(|p|p.file_name().is_some_and(|n|n=="bin")) {
        let root=bin.parent().and_then(Path::parent).context("Missing installation root")?;
        let (current,installed_state)=resolve_release(root)?;
        if current!=bin||installed_state!=state {bail!("Supervisor installation changed")}
        if root!=storage::local_app_data()?.join("CodexPocket") {
            command.arg("--owner-directory").arg(root.join("control"));
        }
    }
    command.stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null());
    process::hidden(&mut command);
    // The supervisor's user and state locks reject duplicate launches. Do not
    // attach it or its native descendants to a kill-on-close job.
    Ok(command)
}

#[cfg(test)]
mod local_start_tests {
    use super::*;
    use serde_json::json;
    use std::fs;
    fn fixture()->(tempfile::TempDir,PathBuf,PathBuf) {
        let root=crate::test_support::tempdir();
        let bin=root.path().join("installed");let state=root.path().join("中文 state");
        fs::create_dir(&bin).unwrap();fs::create_dir(&state).unwrap();
        let mut files=Vec::new();
        for name in ["pocket-supervisor.exe","pocket-agent.exe"] {
            let bytes=format!("fixture {name}").into_bytes();
            files.push(json!({"path":name,"sha256":format!("{:x}",Sha256::digest(&bytes)),"bytes":bytes.len()}));
            fs::write(bin.join(name),bytes).unwrap();
        }
        storage::atomic(&bin.join("windows-manifest.json"),&json!({"files":files})).unwrap();
        storage::atomic(&state.join("config.json"),&json!({"fixture":true})).unwrap();
        (root,bin,state)
    }
    #[test]
    fn manual_control_start_does_not_require_or_change_logon_task_or_remote_preference() {
        let (_root,bin,state)=fixture();
        let config=fs::read(state.join("config.json")).unwrap();
        // Fresh installation: no desktop state, scheduled-task receipt or desired flag.
        let command=supervisor_command(&bin,&state).unwrap();
        assert_eq!(command.get_program(),bin.join("pocket-supervisor.exe"));
        assert_eq!(command.get_args().collect::<Vec<_>>(),vec![std::ffi::OsStr::new("--state"),state.as_os_str()]);
        assert!(!state.join("desktop").exists());
        storage::set_desired(&state,false).unwrap();
        let desired=fs::read(state.join("desktop/desired.json")).unwrap();
        for _ in 0..2 {supervisor_command(&bin,&state).unwrap();}
        assert_eq!(fs::read(state.join("desktop/desired.json")).unwrap(),desired);
        assert_eq!(fs::read(state.join("config.json")).unwrap(),config);
    }
    #[test]
    fn local_start_refuses_missing_state_and_tampered_components() {
        let (_root,bin,state)=fixture();
        fs::remove_file(state.join("config.json")).unwrap();
        assert!(supervisor_command(&bin,&state).is_err());
        assert!(!state.join("config.json").exists());
        storage::atomic(&state.join("config.json"),&json!({"fixture":true})).unwrap();
        for name in ["pocket-agent.exe","pocket-supervisor.exe"] {
            let path=bin.join(name);let original=fs::read(&path).unwrap();
            fs::write(&path,b"changed").unwrap();
            assert!(supervisor_command(&bin,&state).is_err());
            fs::write(path,original).unwrap();
        }
    }
}
pub fn current_package()->Result<Value>{
    let mut cmd=Command::new(storage::powershell());
    cmd.args(["-NoProfile","-NonInteractive","-Command",r#"$ErrorActionPreference='Stop';$p=Get-AppxPackage -Name OpenAI.Codex | Sort-Object Version -Descending | Select-Object -First 1;if(!$p){throw 'Codex not installed'};@{location=$p.InstallLocation;family=$p.PackageFamilyName}|ConvertTo-Json -Compress"#]);
    Ok(serde_json::from_slice(&process::run_bounded(cmd,15)?)?)
}
fn launcher_root(executable:&Path)->Result<PathBuf>{
    storage::unredirected(executable)?;
    if !executable.file_name().and_then(|s|s.to_str()).is_some_and(|s|s.eq_ignore_ascii_case("pocket-launcher.exe")) {
        bail!("Launcher must run from the stable installation path")
    }
    let parent=executable.parent().context("Launcher installation root missing")?;
    // The updater uses its verified generation's launcher, including during
    // rollback. It resolves the committed generation on every invocation.
    let versioned=parent.parent().is_some_and(|p|p.file_name().is_some_and(|n|n=="bin"));
    let root=if versioned {parent.parent().and_then(Path::parent).context("Launcher installation root missing")?}else{parent};
    resolve_release(root)?;
    let record:Value=storage::read(&root.join("desktop-install.json"))?;
    let mut file=storage::open_physical(executable)?;
    let mut hash=Sha256::new();std::io::copy(&mut file,&mut hash)?;
    let digest=format!("{:x}",hash.finalize());
    if versioned {
        let generation=crate::installation::generation(parent)?;
        if parent.file_name().and_then(|s|s.to_str())!=Some(&generation){bail!("Launcher generation changed")}
        verified_exe(parent,"pocket-launcher.exe")?;
    }else if record["launcherHash"]!=digest {bail!("Stable launcher failed integrity check")}
    Ok(root.to_path_buf())
}
fn desktop_command(app:&Path,override_path:Option<std::ffi::OsString>)->Command{
    let mut command=Command::new(app);
    if override_path.is_some_and(|p|{
        let path=PathBuf::from(p);
        path.file_name().is_some_and(|n|n.eq_ignore_ascii_case("pocket-launcher.exe")||n.eq_ignore_ascii_case("pocket-bridge.exe"))
    }){command.env_remove("CODEX_CLI_PATH");}
    process::hidden(&mut command);
    command
}
pub fn launch()->Result<i32>{
    let args:Vec<_>=std::env::args_os().skip(1).collect();
    let mode=args.first().and_then(|s|s.to_str()).unwrap_or("");
    if mode=="--pocket-desktop" {
        let package=current_package()?;let location=PathBuf::from(package["location"].as_str().context("Missing package location")?);
        let app=["app/ChatGPT.exe","app/Codex.exe"].iter().map(|p|location.join(p)).find(|p|p.is_file()).context("Desktop executable missing")?;
        desktop_command(&app,std::env::var_os("CODEX_CLI_PATH")).spawn()?;return Ok(0)
    }
    let root=launcher_root(&std::env::current_exe()?)?;let(bin,state)=resolve_release(&root)?;
    if ["--pocket-ui","--pocket-supervise"].contains(&mode){
        if mode=="--pocket-ui" {
            crate::ui_update::open(&root, &state, false)?;
            return Ok(0)
        }
        // Keep the stable launcher argument/exit ABI. Supervisor lifetime must
        // not put existing native/CLI/model descendants in a kill-on-close job.
        let mut child=background_command(&bin,&state)?.spawn()?;
        return Ok(child.wait()?.code().unwrap_or(1))
    }
    let package=current_package()?;let location=PathBuf::from(package["location"].as_str().context("Missing package location")?);
    let native=location.join("app/resources/codex.exe");storage::plain(&native)?;if !native.is_file(){bail!("Current Codex runtime is missing")}
    let family=package["family"].as_str().context("Missing package family")?;
    let mut command=Command::new(verified_exe(&bin,"pocket-bridge.exe")?);command.arg("--original").arg(&native).arg("--").args(args);
    command.env("CODEX_WINDOWS_SANDBOX_PACKAGE_FAMILY",family).env("POCKET_RUNTIME_DIR",root.join("runtimes")).env("POCKET_INSTALL_ROOT",&root);
    process::hidden(&mut command);
    Ok(command.status()?.code().unwrap_or(1))
}

#[cfg(test)]
mod root_binding_tests {
    use super::*;
    use serde_json::json;
    use std::fs;

    fn install(parent:&Path,name:&str)->PathBuf {
        let root=parent.join(name);let state=root.join("private state");
        fs::create_dir_all(&state).unwrap();
        let manifest=b"{\"files\":[]}";
        let generation=format!("{:x}",Sha256::digest(manifest));
        let bin=root.join("bin").join(&generation);fs::create_dir_all(&bin).unwrap();
        fs::write(bin.join("windows-manifest.json"),manifest).unwrap();
        let exe=root.join("pocket-launcher.exe");fs::write(&exe,b"synthetic launcher; never run").unwrap();
        storage::atomic(&root.join("desktop-install.json"),&json!({
            "version":3,"installRoot":root,"stateDirectory":state,"generation":generation,
            "releaseHash":generation,"bridge":exe,"launcherHash":format!("{:x}",Sha256::digest(fs::read(&exe).unwrap()))
        })).unwrap();
        root
    }

    #[test]
    fn supervisor_command_uses_the_nondefault_installation_control_directory() {
        let d=crate::test_support::tempdir();let root=install(d.path(),"custom root");
        let bytes=b"synthetic supervisor and agent";
        let entries=["pocket-supervisor.exe","pocket-agent.exe"].map(|name|json!({"path":name,"sha256":format!("{:x}",Sha256::digest(bytes)),"bytes":bytes.len()}));
        let manifest=serde_json::to_vec(&json!({"files":entries})).unwrap();let g=format!("{:x}",Sha256::digest(&manifest));
        let bin=root.join("bin").join(&g);fs::create_dir(&bin).unwrap();fs::write(bin.join("windows-manifest.json"),manifest).unwrap();
        for name in ["pocket-supervisor.exe","pocket-agent.exe"]{fs::write(bin.join(name),bytes).unwrap();}
        let mut record:Value=storage::read(&root.join("desktop-install.json")).unwrap();record["generation"]=json!(g);record["releaseHash"]=json!(g);storage::atomic(&root.join("desktop-install.json"),&record).unwrap();
        let state=root.join("private state");storage::atomic(&state.join("config.json"),&json!({"fixture":true})).unwrap();
        let c=supervisor_command(&bin,&state).unwrap();
        assert_eq!(c.get_args().collect::<Vec<_>>(),vec![std::ffi::OsStr::new("--state"),state.as_os_str(),std::ffi::OsStr::new("--owner-directory"),root.join("control").as_os_str()]);
        // Merely looking for optional updates must not make an otherwise valid
        // existing installation unbootable. Retain broken metadata for diagnosis.
        let updates=crate::updates::directory(&state);storage::private_dir(&updates).unwrap();
        let journal=updates.join("status.json");fs::write(&journal,b"broken").unwrap();
        assert!(background_command(&bin,&state).is_err());
        storage::atomic(&root.join("installer-lifecycle.json"),&json!({"schema":1,"phase":"verified",
            "installRoot":root,"stateDirectory":state,"targetGeneration":g})).unwrap();
        assert_eq!(background_command(&bin,&state).unwrap().get_program(),bin.join("pocket-supervisor.exe"));
        assert_eq!(fs::read(journal).unwrap(),b"broken");
    }
    #[test]
    fn nondefault_installation_is_bound_to_stable_executable_not_global_state() {
        let d=crate::test_support::tempdir();
        let first=install(d.path(),"first 安装");let second=install(d.path(),"second 安装");
        for root in [&first,&second] {
            let actual=launcher_root(&root.join("pocket-launcher.exe")).unwrap();
            assert_eq!(&actual,root);
            assert_eq!(resolve_release(&actual).unwrap().1,root.join("private state"));
            let command=desktop_command(&root.join("synthetic-desktop.exe"),Some(root.join("pocket-launcher.exe").into_os_string()));
            let env:std::collections::HashMap<_,_>=command.get_envs().collect();
            assert_eq!(env[std::ffi::OsStr::new("CODEX_CLI_PATH")],None);
            assert!(!env.contains_key(std::ffi::OsStr::new("POCKET_RUNTIME_DIR")));
        }
    }
    #[test]
    fn verified_version_launcher_keeps_its_root_and_path_for_future_children() {
        let d=crate::test_support::tempdir();let root=install(d.path(),"custom 安装");
        let bytes=b"updated launcher; never execute";
        let manifest=serde_json::to_vec(&json!({"files":[{"path":"pocket-launcher.exe","sha256":format!("{:x}",Sha256::digest(bytes)),"bytes":bytes.len()}]})).unwrap();
        let generation=format!("{:x}",Sha256::digest(&manifest));
        let bin=root.join("bin").join(generation);fs::create_dir_all(&bin).unwrap();
        fs::write(bin.join("windows-manifest.json"),manifest).unwrap();
        let exe=bin.join("pocket-launcher.exe");fs::write(&exe,bytes).unwrap();
        assert_eq!(launcher_root(&exe).unwrap(),root);
        let c=desktop_command(&root.join("synthetic.exe"),Some(exe.clone().into_os_string()));
        let env:std::collections::HashMap<_,_>=c.get_envs().collect();
        assert_eq!(env[std::ffi::OsStr::new("CODEX_CLI_PATH")],None);
        assert!(!env.contains_key(std::ffi::OsStr::new("POCKET_INSTALL_ROOT")));
        fs::write(&exe,b"tampered").unwrap();assert!(launcher_root(&exe).is_err());
    }
    #[test]
    fn misplaced_tampered_or_cross_root_launcher_is_rejected_without_fallback() {
        let d=crate::test_support::tempdir();let root=install(d.path(),"installed");
        let exe=root.join("pocket-launcher.exe");
        assert!(launcher_root(&root.join("other.exe")).is_err());
        assert!(launcher_root(&d.path().join("missing/pocket-launcher.exe")).is_err());
        let original=fs::read(&exe).unwrap();fs::write(&exe,b"tampered").unwrap();
        assert!(launcher_root(&exe).is_err());fs::write(&exe,original).unwrap();
        let record=root.join("desktop-install.json");let mut v:Value=storage::read(&record).unwrap();
        v["installRoot"]=json!(d.path());storage::atomic(&record,&v).unwrap();
        assert!(launcher_root(&exe).is_err());
    }
}
