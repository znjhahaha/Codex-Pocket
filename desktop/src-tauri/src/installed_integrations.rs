//! Optional integrations are installer records, never live runtime acceptance.
use crate::commands::Context;
use pocket_desktop_runtime::{connection::OperationError, launcher, storage};
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{io::Read, path::Path};
use anyhow::Context as _;

#[cfg(test)]
thread_local! { static SCRIPT_COMMANDS:std::cell::Cell<usize>=const{std::cell::Cell::new(0)}; }

const STEPS: [&str; 4] = ["shortcut_0", "shortcut_1", "shortcut_2", "startup"];
const LIMIT: u64 = 1024 * 1024;
const STAGES: &[&str] = &["shortcut", "shortcut_verify", "shortcut_publish", "startup_read",
    "startup_ownership", "startup_register", "integration", "installation_publish", "rollback",
    "repair_preflight","repair_paths","repair_verify","repair_lock","repair_operation","repair_recovery","integration_read","startup_compare"];
const CODES: &[&str] = &["integration_pending", "integration_completed", "shortcut_integration_failed",
    "startup_integration_failed", "startup_snapshot_unknown", "integration_apply_failed", "integration_recovery_pending"];
const CALLS: &[&str] = &["Initialize-PocketShellLink", "IShellLinkW/IPersistFile.Save",
    "IPersistFile.Load/GetPath/GetArguments/GetIconLocation", "compare_committed_shortcut_snapshot",
    "Write-PocketBackup", "New-PocketShortcutBytes", "Get-PocketTaskXml", "Assert-PocketTaskOwned",
    "Set-PocketTaskXml", "Invoke-PocketInstallTransaction", "Restore-PocketTransaction"];
const REPAIR_CODES: &[&str] = &["integration_completed","integration_already_completed","invalid_params","generation_changed",
    "installation_unverified","path_unverified","integration_drift","operation_conflict","operation_busy","operation_unknown",
    "integration_repair_failed","integration_recovery_needed","operation_record_failed"];
const REPAIR_CALLS: &[&str] = &["Invoke-PocketIntegrationRepair","Assert-PocketUnredirectedPath","Assert-PocketRepairInstalled",
    "Mutex.WaitOne","Read-PocketRepairJson","Write-PocketJson","Restore-PocketTransaction","Invoke-PocketInstallTransaction",
    "Get-PocketIntegrationSnapshot","New-PocketShortcutBytes","Initialize-PocketShellLink","IShellLinkW/IPersistFile.Save",
    "IPersistFile.Save","IPersistFile.Load/GetPath/GetArguments/GetIconLocation","Write-PocketBackup","Assert-PocketTaskOwned",
    "Get-PocketTaskXml","Set-PocketTaskXml","CoCreateInstance(Schedule.Service)","ITaskService.Connect","ITaskService.GetFolder",
    "ITaskFolder.GetTask","IRegisteredTask.Xml","ITaskFolder.RegisterTask","ITaskFolder.DeleteTask","ITaskService.NewTask",
    "ITaskDefinition.XmlText(left)","ITaskDefinition.XmlText(right)"];

fn choice<'a>(value: &'a Value, allowed: &[&str]) -> &'a str {
    value.as_str().filter(|s| allowed.contains(s)).unwrap_or("unknown")
}
fn public_step(step: &str, value: &Value) -> Value {
    let hresult = value["hresult"].as_str().filter(|s| s.len() == 10 && s.starts_with("0x") && s.bytes().skip(2).all(|b| b.is_ascii_hexdigit()));
    json!({"step":step,"state":choice(&value["state"], &["completed", "pending", "unverified"]),
        "stage":choice(&value["stage"], STAGES),"code":choice(&value["code"], CODES),
        "call":value["call"].as_str().filter(|s| CALLS.contains(s)),"hresult":hresult})
}
fn unknown(reason: &str, recovery: &str) -> Value {
    let mut result = json!({"recordState":"unverified","reason":reason,"installationState":"unknown",
        "recoveryState":recovery,"repairSupported":false,"repairCheckAvailable":false,"source":"installer_record",
        "steps":STEPS.map(|step|public_step(step,&json!({"state":"unverified"})))});
    result["steps"].as_array_mut().unwrap().iter_mut().for_each(|step|step["state"]=json!("unverified"));
    result
}
// Reapply the projection at the diagnostics boundary, not only at disk read.
pub fn public_status(value: &Value) -> Value {
    let available = value["recordState"] == "available";
    let steps: Vec<_> = STEPS.iter().map(|step| {
        let detail = value["steps"].as_array().and_then(|list|list.iter().find(|v|v["step"]==*step));
        let mut item = public_step(step, detail.unwrap_or(&Value::Null));
        if !available || item["state"] == "unknown" { item["state"] = json!("unverified"); }
        item
    }).collect();
    json!({"recordState":if available{"available"}else{"unverified"},
        "reason":choice(&value["reason"], &["installation_changed", "installation_record_unavailable", "integration_record_invalid", "integration_record_missing", "installer_repair_protocol_missing", "repair_capability_unchecked"]),
        "installationState":choice(&value["installationState"], &["installed", "installed_incomplete"]),
        "recoveryState":choice(&value["recoveryState"], &["clear", "pending"]),
        "source":"installer_record","repairSupported":available&&value["repairSupported"]==true,
        "repairCheckAvailable":available&&value["repairCheckAvailable"]==true,"steps":steps})
}
fn bounded_record(path: &Path) -> anyhow::Result<Value> {
    storage::unredirected(path)?;
    let file = std::fs::File::open(path)?;
    anyhow::ensure!(file.metadata()?.is_file(), "Invalid record file");
    let mut bytes = Vec::new();
    file.take(LIMIT + 1).read_to_end(&mut bytes)?;
    anyhow::ensure!(bytes.len() as u64 <= LIMIT, "Record exceeds limit");
    Ok(serde_json::from_slice(&bytes)?)
}
fn recovery_state(root: &Path) -> &'static str {
    let path = root.join("installation-pending.json");
    if storage::unredirected(&path).is_err() { return "unknown"; }
    match path.try_exists() { Ok(true)=>"pending", Ok(false)=>"clear", Err(_)=>"unknown" }
}

#[derive(Debug, Deserialize)]
pub enum RepairStep {
    #[serde(rename = "shortcut_0")] Entry,
    #[serde(rename = "shortcut_1")] OldDesktop,
    #[serde(rename = "shortcut_2")] OldStartup,
    #[serde(rename = "startup")] Startup,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RepairRequest {
    pub step: RepairStep,
    pub operation_id: String,
    pub expected_generation: String,
    #[serde(default)]
    pub check_repair: bool,
}
impl RepairStep {
    fn name(&self) -> &'static str {
        match self { Self::Entry=>"shortcut_0", Self::OldDesktop=>"shortcut_1", Self::OldStartup=>"shortcut_2", Self::Startup=>"startup" }
    }
}

fn decode_result(bytes: &[u8], exit: Option<i32>, request: &RepairRequest) -> anyhow::Result<Value> {
    anyhow::ensure!(bytes.len() as u64 <= LIMIT, "Result exceeds limit");
    let value:Value=serde_json::from_slice(bytes)?;
    anyhow::ensure!(value["schemaVersion"]==1 && value["action"]=="repair_integration" && value["operationId"]==request.operation_id &&
        value["step"]==request.step.name() && value["expectedGeneration"]==request.expected_generation &&
        value["runtimeVerification"]=="pending" && value["processesStarted"]==0 && value["processesStopped"]==0 &&
        value["coreInstalled"].is_boolean() && value["retryable"].is_boolean(), "Uncorrelated result");
    let state=value["state"].as_str().unwrap_or("");
    let expected_exit=match state {"completed"=>0,"failed"=>1,"recovery-needed"=>2,"unknown"=>3,"busy"=>4,_=>anyhow::bail!("Unknown result state")};
    anyhow::ensure!(exit==Some(expected_exit) && value["exitCode"]==expected_exit &&
        choice(&value["code"],REPAIR_CODES)!="unknown" && value["recoveryState"].as_str().is_some_and(|s|["clear","pending","unknown"].contains(&s)), "Inconsistent result");
    anyhow::ensure!(state!="completed" || (value["coreInstalled"]==true && value["recoveryState"]=="clear" &&
        matches!(value["code"].as_str(),Some("integration_completed"|"integration_already_completed"))), "Unverified completion");
    let hresult=value["hresult"].as_str().filter(|s|s.len()==10&&s.starts_with("0x")&&s.bytes().skip(2).all(|b|b.is_ascii_hexdigit()));
    Ok(json!({"schemaVersion":1,"action":"repair_integration","operationId":request.operation_id,"step":request.step.name(),
        "expectedGeneration":request.expected_generation,"state":state,"code":value["code"],"stage":choice(&value["stage"],STAGES),
        "call":value["call"].as_str().filter(|s|REPAIR_CALLS.contains(s)),"hresult":hresult,"recoveryState":value["recoveryState"],
        "retryable":value["retryable"],"coreInstalled":value["coreInstalled"],"runtimeVerification":"pending","processesStarted":0,"processesStopped":0,"exitCode":expected_exit}))
}
fn repair_command(bin: &Path, request: &RepairRequest) -> std::process::Command {
    #[cfg(test)]
    SCRIPT_COMMANDS.with(|count|count.set(count.get()+1));
    let mut command=std::process::Command::new(storage::powershell());
    command.args(["-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File"]).arg(bin.join("windows-install.ps1"))
        .args(["-RepairStep",request.step.name(),"-OperationId",&request.operation_id,"-ExpectedGeneration",&request.expected_generation]);
    if request.check_repair { command.arg("-CheckRepair"); }
    command
}

pub fn read_status(root: &Path, bin: &Path, state: &Path, consistent: bool) -> Value {
    let recovery = recovery_state(root);
    if !consistent { return unknown("installation_changed", recovery); }
    let path = root.join("desktop-install.json");
    let Ok(record) = bounded_record(&path) else { return unknown("installation_record_unavailable", recovery); };
    let Ok((installed_bin, installed_state)) = launcher::resolve_release(root) else { return unknown("installation_record_unavailable", recovery); };
    if installed_bin != bin || installed_state != state || bounded_record(&path).ok().as_ref() != Some(&record) {
        return unknown("installation_changed", recovery);
    }
    let integration = &record["integration"];
    if integration.is_null() { return unknown("integration_record_missing", recovery); }
    let Some(shortcuts) = integration["shortcuts"].as_array() else { return unknown("integration_record_invalid", recovery); };
    if shortcuts.len() != 3 || shortcuts.iter().chain(std::iter::once(&integration["startup"]))
        .any(|v|!matches!(v["state"].as_str(),Some("pending"|"completed"))) {
        return unknown("integration_record_invalid", recovery);
    }
    public_status(&json!({"recordState":"available","reason":"repair_capability_unchecked",
        "installationState":record["installationState"],"recoveryState":recovery,
        "steps":[public_step(STEPS[0],&shortcuts[0]),public_step(STEPS[1],&shortcuts[1]),
            public_step(STEPS[2],&shortcuts[2]),public_step(STEPS[3],&integration["startup"])]}))
}
fn valid_operation_id(id: &str) -> bool {
    id.len()==36 && id.bytes().enumerate().all(|(i,b)|if [8,13,18,23].contains(&i){b==b'-'}else{b.is_ascii_digit()||(b'a'..=b'f').contains(&b)})
}
fn request_error(code: &str, id: &str, retryable: bool) -> OperationError {
    let mut error = OperationError::new("installation_repair",code,retryable);
    if valid_operation_id(id) { error.operation_id = id.to_owned(); }
    if code == "repair_unsupported" { error.message = "当前安装器尚不支持分项修复，请导出诊断后使用完整安装包的重试流程。".into(); }
    if code == "repair_capability_unknown" { error.message = "安装器修复能力尚未核验，本次请求无法继续；此前修复的结果仍需核对。请保留诊断，稍后手动重试。".into(); }
    error
}
fn request_repair(c: &Context, request: RepairRequest) -> Result<Value, OperationError> {
    if !valid_operation_id(&request.operation_id) || request.expected_generation.len()!=64 ||
        !request.expected_generation.bytes().all(|b|b.is_ascii_digit()||(b'a'..=b'f').contains(&b)) {
        return Err(request_error("invalid_params", &request.operation_id, false));
    }
    let _guard = c.operation.try_lock().map_err(|_|request_error("operation_busy", &request.operation_id, true))?;
    let root=storage::local_app_data().map_err(|_|request_error("installation_invalid",&request.operation_id,false))?.join("CodexPocket");
    execute_repair(c,&root,request)
}

// The PowerShell parser/protocol probe is reserved for explicit repair. Passive
// snapshots verify files natively and never execute this wrapper or the installer.
const VERIFY_INSTALLER: &str = r#"
$ErrorActionPreference='Stop'
$bin=$env:POCKET_REPAIR_BIN
$manifestPath=Join-Path $bin 'windows-manifest.json'
if ((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $env:POCKET_REPAIR_GENERATION) { throw 'Manifest changed' }
$manifest=Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($name in @('windows-install.ps1','windows-common.ps1','windows-startup.ps1')) {
    $entries=@($manifest.files | Where-Object { $_.path -ceq $name })
    if ($entries.Count -ne 1) { throw 'Invalid script inventory' }
    $file=Join-Path $bin $name
    if ((Get-Item -LiteralPath $file).Length -ne $entries[0].bytes -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entries[0].sha256) { throw 'Script changed' }
}
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $bin 'windows-install.ps1'),[ref]$tokens,[ref]$errors)
$names=@($ast.ParamBlock.Parameters | ForEach-Object {$_.Name.VariablePath.UserPath})
$required=@('RepairStep','OperationId','ExpectedGeneration','CheckRepair')
$supported=$errors.Count -eq 0 -and @($required|Where-Object{$_ -notin $names}).Count -eq 0
@{verified=$true;repairParameters=$supported}|ConvertTo-Json -Compress
"#;

fn verification_command(bin: &Path, generation: &str) -> std::process::Command {
    #[cfg(test)]
    SCRIPT_COMMANDS.with(|count|count.set(count.get()+1));
    let mut command = std::process::Command::new(storage::powershell());
    command.args(["-NoProfile", "-NonInteractive", "-Command", VERIFY_INSTALLER]);
    command.env("POCKET_REPAIR_BIN",bin).env("POCKET_REPAIR_GENERATION",generation);
    command
}

fn verify_installer(bin: &Path, generation: &str) -> anyhow::Result<()> {
    let handles=script_handles(bin)?;
    let read=|file:&std::fs::File|->anyhow::Result<Vec<u8>>{
        let mut bytes=Vec::new();file.take(LIMIT+1).read_to_end(&mut bytes)?;
        anyhow::ensure!(bytes.len() as u64<=LIMIT,"Installer file exceeds limit");Ok(bytes)
    };
    let manifest=read(&handles[0])?;
    anyhow::ensure!(format!("{:x}",Sha256::digest(&manifest))==generation,"Installation changed");
    let inventory:Value=serde_json::from_slice(&manifest)?;
    anyhow::ensure!(inventory["schema"]==1,"Invalid installer manifest");
    let entries=inventory["files"].as_array().ok_or_else(||anyhow::anyhow!("Missing script inventory"))?;
    for (name,file) in ["windows-install.ps1","windows-common.ps1","windows-startup.ps1"].iter().zip(&handles[1..]) {
        let mut matching=entries.iter().filter(|entry|entry["path"]==*name);
        let entry=matching.next().ok_or_else(||anyhow::anyhow!("Missing script inventory"))?;
        anyhow::ensure!(matching.next().is_none(),"Duplicate script inventory");
        let bytes=read(file)?;
        anyhow::ensure!(entry["bytes"].as_u64()==Some(bytes.len() as u64)&&
            entry["sha256"].as_str()==Some(&format!("{:x}",Sha256::digest(&bytes))),"Script changed");
    }
    Ok(())
}
// Only this owned PowerShell child can be terminated on timeout. No job object
// or descendant kill is used. Output remains bounded and is never logged raw.
fn run_script(mut command:std::process::Command, seconds:u64)->anyhow::Result<(Option<i32>,Vec<u8>)> {
    use std::{process::Stdio,sync::mpsc,thread,time::{Duration,Instant}};
    pocket_desktop_runtime::process::hidden(&mut command);
    command.env_remove("PSModulePath").stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null());
    let mut child=command.spawn()?;let output=child.stdout.take().ok_or_else(||anyhow::anyhow!("Output unavailable"))?;
    let (tx,rx)=mpsc::channel();
    thread::spawn(move||{let mut bytes=Vec::new();let result=output.take(LIMIT+1).read_to_end(&mut bytes).map(|_|bytes);let _=tx.send(result);});
    let deadline=Instant::now()+Duration::from_secs(seconds);
    let exit=loop {
        if let Some(status)=child.try_wait()?{break status.code()}
        if Instant::now()>=deadline { let _=child.kill();let _=child.wait();anyhow::bail!("Repair outcome unknown"); }
        thread::sleep(Duration::from_millis(40));
    };
    let bytes=rx.recv_timeout(Duration::from_secs(2))??;
    anyhow::ensure!(bytes.len() as u64<=LIMIT,"Result exceeds limit");Ok((exit,bytes))
}
fn probe_request(generation:&str)->RepairRequest {
    // Reserved read-only check; never submitted without CheckRepair.
    RepairRequest{step:RepairStep::Startup,operation_id:"00000000-0000-4000-8000-000000000000".into(),expected_generation:generation.into(),check_repair:true}
}
fn probe_capability(bin:&Path,generation:&str)->anyhow::Result<bool> {
    let _handles=script_handles(bin)?;
    verify_installer(bin,generation)?;
    let reply:Value=serde_json::from_slice(&pocket_desktop_runtime::process::run_bounded(verification_command(bin,generation),10)
        .context("repair_capability: parser/manifest verification failed")?)?;
    anyhow::ensure!(reply["verified"]==true,"Verification unavailable");
    let supported=reply["repairParameters"].as_bool().ok_or_else(||anyhow::anyhow!("Invalid capability result"))?;
    if !supported{return Ok(false);}
    let probe=probe_request(generation);let (exit,bytes)=run_script(repair_command(bin,&probe),10)
        .context("repair_capability: installer CheckRepair failed; no repair mutation dispatched")?;
    let result=decode_result(&bytes,exit,&probe)?;
    Ok(result["coreInstalled"]==true)
}
pub fn repair_check_available(bin:&Path,generation:&str)->bool {
    // No age/mtime shortcut: same-size edits and current generation changes
    // are detected on every read. Availability only permits a user-driven probe.
    verify_installer(bin,generation).is_ok()
}
fn unknown_result(request:&RepairRequest)->Value {
    json!({"schemaVersion":1,"action":"repair_integration","operationId":request.operation_id,"step":request.step.name(),
        "expectedGeneration":request.expected_generation,"state":"unknown","code":"operation_unknown","stage":"repair_operation",
        "call":null,"hresult":null,"recoveryState":"unknown","retryable":true,"coreInstalled":false,
        "runtimeVerification":"pending","processesStarted":0,"processesStopped":0,"exitCode":null})
}
fn execute_repair(c:&Context,root:&Path,request:RepairRequest)->Result<Value,OperationError> {
    let generation=request.expected_generation.clone();
    execute_repair_with(c,root,request,||probe_capability(&c.bin,&generation),|request|{
        let (exit,bytes)=run_script(repair_command(&c.bin,request),20)?;decode_result(&bytes,exit,request)
    })
}
fn execute_repair_with(c:&Context,root:&Path,mut request:RepairRequest,probe:impl FnOnce()->anyhow::Result<bool>,
    dispatch:impl Fn(&RepairRequest)->anyhow::Result<Value>)->Result<Value,OperationError> {
    let resolved=launcher::resolve_release(root).map_err(|_|request_error("installation_invalid",&request.operation_id,false))?;
    if resolved.0!=c.bin||resolved.1!=c.state||pocket_desktop_runtime::installation::generation(&c.bin).ok().as_deref()!=Some(&request.expected_generation) {
        return Err(request_error("installation_changed",&request.operation_id,false));
    }
    let _handles=script_handles(&c.bin).map_err(|_|request_error("repair_unsupported",&request.operation_id,false))?;
    verify_installer(&c.bin,&request.expected_generation).map_err(|_|request_error("repair_unsupported",&request.operation_id,false))?;
    if !probe().map_err(|_|request_error("repair_capability_unknown",&request.operation_id,true))? {
        return Err(request_error("repair_unsupported",&request.operation_id,false));
    }
    let run=|request:&RepairRequest|->anyhow::Result<Value>{
        // Reverify immediately before every operation, including reconciliation.
        verify_installer(&c.bin,&request.expected_generation)?;
        if matches!(request.step,RepairStep::Startup){crate::commands::invalidate_startup(c);}
        dispatch(request)
    };
    Ok(reconcile_run(&mut request,run))
}
fn reconcile_run(request:&mut RepairRequest,run:impl Fn(&RepairRequest)->anyhow::Result<Value>)->Value {
    match run(request) {
        Ok(result)=>result,
        Err(_) if !request.check_repair=>{request.check_repair=true;run(request).unwrap_or_else(|_|unknown_result(request))},
        Err(_)=>unknown_result(request),
    }
}
fn script_handles(bin:&Path)->anyhow::Result<Vec<std::fs::File>> {
    ["windows-manifest.json","windows-install.ps1","windows-common.ps1","windows-startup.ps1"].iter().map(|name|{
        let path=bin.join(name);storage::unredirected(&path)?;
        let mut options=std::fs::OpenOptions::new();options.read(true);
        #[cfg(windows)]{use std::os::windows::fs::OpenOptionsExt;options.share_mode(1);}
        let file=options.open(path)?;anyhow::ensure!(file.metadata()?.is_file(),"Invalid script file");Ok(file)
    }).collect()
}
#[tauri::command]
pub async fn repair_installed_integration(context: tauri::State<'_, Context>, request: RepairRequest) -> Result<Value,OperationError> {
    let c = context.inner().clone();
    tauri::async_runtime::spawn_blocking(move||request_repair(&c,request)).await.map_err(crate::commands::join_error)?
}

#[cfg(test)]
mod tests {
    use super::*;
    use pocket_desktop_runtime::{installation, storage};
    const ID: &str = "12345678-1234-4234-8234-123456789abc";

    #[test]
    fn automatic_repair_checks_only_establish_file_integrity_not_script_protocol_success() {
        let dir=crate::test_support::tempdir();
        let names=["windows-install.ps1","windows-common.ps1","windows-startup.ps1"];
        let entries:Vec<_>=names.iter().map(|name|json!({"path":name,"bytes":0,
            "sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"})).collect();
        for name in names {std::fs::write(dir.path().join(name),b"").unwrap();}
        storage::atomic(&dir.path().join("windows-manifest.json"),&json!({"schema":1,"files":entries})).unwrap();
        let generation=installation::generation(dir.path()).unwrap();
        // Empty scripts have valid inventory hashes, but no repair protocol.
        // A passive check only makes the explicit verification action eligible.
        SCRIPT_COMMANDS.with(|count|count.set(0));
        for _ in 0..3 {assert!(repair_check_available(dir.path(),&generation));}
        SCRIPT_COMMANDS.with(|count|assert_eq!(count.get(),0,"Passive checks must not even construct a PowerShell command"));
    }

    fn protocol_fixture()->(tempfile::TempDir,Context,Value){
        let (dir,mut c,mut record)=fixture();
        std::fs::write(c.bin.join("windows-install.ps1"),include_str!("fixtures/repair_protocol.ps1")).unwrap();
        for name in ["windows-common.ps1","windows-startup.ps1"]{std::fs::write(c.bin.join(name),b"").unwrap();}
        let entries:Vec<_>=["windows-install.ps1","windows-common.ps1","windows-startup.ps1"].iter().map(|name|{
            let bytes=std::fs::read(c.bin.join(name)).unwrap();json!({"path":name,"bytes":bytes.len(),"sha256":format!("{:x}",Sha256::digest(&bytes))})
        }).collect();
        storage::atomic(&c.bin.join("windows-manifest.json"),&json!({"schema":1,"files":entries})).unwrap();
        let generation=installation::generation(&c.bin).unwrap();let bin=dir.path().join("bin").join(&generation);
        std::fs::rename(&c.bin,&bin).unwrap();c.bin=bin;
        record["generation"]=json!(generation);record["releaseHash"]=json!(generation);
        storage::atomic(&dir.path().join("desktop-install.json"),&record).unwrap();(dir,c,record)
    }
    #[test]
    fn passive_integrity_checks_detect_same_size_and_timestamp_edits_and_generation_drift(){
        let (_dir,c,_)=protocol_fixture();let generation=installation::generation(&c.bin).unwrap();
        let script=c.bin.join("windows-install.ps1");let before=std::fs::read(&script).unwrap();
        let modified=std::fs::metadata(&script).unwrap().modified().unwrap();
        SCRIPT_COMMANDS.with(|count|count.set(0));
        for _ in 0..3{assert!(repair_check_available(&c.bin,&generation));}
        let mut changed=before.clone();changed[0]=b' ';
        std::fs::write(&script,changed).unwrap();
        std::fs::OpenOptions::new().write(true).open(&script).unwrap().set_times(std::fs::FileTimes::new().set_modified(modified)).unwrap();
        for _ in 0..3{assert!(!repair_check_available(&c.bin,&generation));}
        std::fs::write(&script,before).unwrap();assert!(repair_check_available(&c.bin,&generation));
        assert!(!repair_check_available(&c.bin,&"a".repeat(64)));
        SCRIPT_COMMANDS.with(|count|assert_eq!(count.get(),0));
    }
    #[test]
    fn native_verifier_rejects_duplicate_inventory_and_non_file_scripts(){
        let (_dir,c,_)=protocol_fixture();let mut manifest=bounded_record(&c.bin.join("windows-manifest.json")).unwrap();
        let duplicate=manifest["files"][0].clone();manifest["files"].as_array_mut().unwrap().push(duplicate);
        storage::atomic(&c.bin.join("windows-manifest.json"),&manifest).unwrap();
        let generation=installation::generation(&c.bin).unwrap();assert!(!repair_check_available(&c.bin,&generation));
        std::fs::remove_file(c.bin.join("windows-startup.ps1")).unwrap();
        std::fs::create_dir(c.bin.join("windows-startup.ps1")).unwrap();assert!(!repair_check_available(&c.bin,&generation));
    }
    #[test]
    fn capability_failure_never_dispatches_mutation_and_does_not_suppress_explicit_retries(){
        use std::cell::Cell;
        let (dir,c,_)=protocol_fixture();let generation=installation::generation(&c.bin).unwrap();let calls=Cell::new(0);
        let before=std::fs::read(dir.path().join("desktop-install.json")).unwrap();
        for _ in 0..3{
            let error=execute_repair_with(&c,dir.path(),request(&generation),||{calls.set(calls.get()+1);anyhow::bail!("Fixture probe timeout")},|_|panic!("Must not dispatch a mutation")).unwrap_err();
            assert_eq!(error.code,"repair_capability_unknown");assert_eq!(error.operation_id,ID);assert!(error.retryable);
        }
        assert_eq!(calls.get(),3);
        let error=execute_repair_with(&c,dir.path(),request(&generation),||Ok(false),|_|panic!("Unsupported installer must not mutate")).unwrap_err();
        assert_eq!(error.code,"repair_unsupported");assert!(!error.retryable);
        assert_eq!(std::fs::read(dir.path().join("desktop-install.json")).unwrap(),before);
    }

    fn fixture() -> (tempfile::TempDir, Context, Value) {
        let dir = crate::test_support::tempdir();
        let root = dir.path();
        let stage = root.join("stage");
        std::fs::create_dir(&stage).unwrap();
        std::fs::write(stage.join("windows-manifest.json"), b"{\"schema\":1,\"files\":[]}").unwrap();
        let generation = installation::generation(&stage).unwrap();
        let bin = root.join("bin").join(&generation);
        std::fs::create_dir(root.join("bin")).unwrap();
        std::fs::rename(&stage, &bin).unwrap();
        let state = root.join("state");
        std::fs::create_dir(&state).unwrap();
        let record = json!({"version":3,"installRoot":root,"bridge":root.join("pocket-launcher.exe"),
            "stateDirectory":state,"generation":generation,"releaseHash":generation,"coreInstalled":true,
            "installationState":"installed_incomplete","integration":{"shortcuts":[
                {"state":"completed","stage":"shortcut","code":"integration_completed"},
                {"state":"pending","stage":"shortcut_verify","code":"shortcut_integration_failed","call":"IPersistFile.Load/GetPath/GetArguments/GetIconLocation","hresult":"0x80004005"},
                {"state":"completed"}],"startup":{"state":"pending","stage":"startup_read","code":"startup_snapshot_unknown","call":"Get-PocketTaskXml","hresult":"0x80070005"}},
            "previousTask":"fixture-private-task","shortcuts":[{"original":"fixture-private-backup"}]});
        storage::atomic(&root.join("desktop-install.json"), &record).unwrap();
        (dir, Context::new(state, bin), record)
    }
    fn request(generation: &str) -> RepairRequest {
        serde_json::from_value(json!({"step":"startup","operationId":ID,"expectedGeneration":generation})).unwrap()
    }
    #[test]
    fn installed_pending_and_completed_are_separate_from_runtime_acceptance() {
        let (dir,c,_) = fixture();
        let r = read_status(dir.path(), &c.bin, &c.state, true);
        assert_eq!(r["recordState"], "available");
        assert_eq!(r["steps"].as_array().unwrap().len(), 4);
        assert_eq!(r["steps"][0]["state"], "completed");
        assert_eq!(r["steps"][1]["state"], "pending");
        assert_eq!(r["steps"][1]["hresult"], "0x80004005");
        assert_eq!(r["steps"][3]["code"], "startup_snapshot_unknown");
        assert_eq!(r["repairSupported"], false);
        assert!(!r.to_string().contains("fixture-private"));
        assert!(!r.to_string().contains(&dir.path().display().to_string()));
    }
    #[test]
    fn malformed_missing_stale_and_oversized_records_remain_unverified() {
        let (dir,c,mut record) = fixture();
        assert_eq!(read_status(dir.path(), &c.bin, &c.state, false)["recordState"], "unverified");
        assert_eq!(read_status(dir.path(), &c.bin, &dir.path().join("wrong"), true)["recordState"], "unverified");
        record["integration"]["shortcuts"][1]["state"] = json!("success");
        storage::atomic(&dir.path().join("desktop-install.json"), &record).unwrap();
        assert_eq!(read_status(dir.path(), &c.bin, &c.state, true)["recordState"], "unverified");
        std::fs::write(dir.path().join("desktop-install.json"), vec![b' '; 1024*1024+1]).unwrap();
        assert_eq!(read_status(dir.path(), &c.bin, &c.state, true)["reason"], "installation_record_unavailable");
        std::fs::remove_file(dir.path().join("desktop-install.json")).unwrap();
        assert_eq!(read_status(dir.path(), &c.bin, &c.state, true)["recordState"], "unverified");
    }
    #[test]
    fn recovery_journal_blocks_completion_without_exporting_its_contents() {
        let (dir,c,mut record) = fixture();
        record["integration"]["startup"]["state"] = json!("completed");
        record["integration"]["shortcuts"][1]["state"] = json!("completed");
        storage::atomic(&dir.path().join("desktop-install.json"), &record).unwrap();
        std::fs::write(dir.path().join("installation-pending.json"), b"fixture-private-journal").unwrap();
        let r = read_status(dir.path(), &c.bin, &c.state, true);
        assert_eq!(r["recoveryState"], "pending");
        assert_eq!(r["steps"][0]["state"], "completed");
        assert!(!r.to_string().contains("fixture-private"));
    }
    #[test]
    fn metadata_is_allowlisted_even_if_record_contains_arbitrary_strings() {
        let (dir,c,mut record) = fixture();
        record["integration"]["startup"]["call"] = json!("fixture-private-token");
        record["integration"]["startup"]["stage"] = json!("fixture-private-path");
        record["integration"]["startup"]["hresult"] = json!("0x80070005 fixture-private-error");
        storage::atomic(&dir.path().join("desktop-install.json"), &record).unwrap();
        let r = read_status(dir.path(), &c.bin, &c.state, true);
        assert_eq!(r["steps"][3]["stage"], "unknown");
        assert_eq!(r["steps"][3]["hresult"], Value::Null);
        assert!(!r.to_string().contains("fixture-private"));
    }
    #[test]
    fn arbitrary_steps_paths_commands_and_invalid_ids_are_rejected() {
        for value in [json!({"step":"firewall","operationId":ID,"expectedGeneration":"a".repeat(64)}),
            json!({"step":"startup","operationId":ID,"expectedGeneration":"a".repeat(64),"script":"elsewhere"}),
            json!({"step":"startup","operationId":ID,"expectedGeneration":"a".repeat(64),"command":"cmd"})] {
            assert!(serde_json::from_value::<RepairRequest>(value).is_err());
        }
        let (_,c,_) = fixture();
        let mut bad = request("a"); bad.operation_id = "../../elsewhere".into();
        assert_eq!(request_repair(&c,bad).unwrap_err().code, "invalid_params");
    }
    #[test]
    fn unsupported_repair_keeps_operation_id_and_never_writes_installed_record() {
        let (dir,c,_) = fixture();
        let before = std::fs::read(dir.path().join("desktop-install.json")).unwrap();
        let error = execute_repair(&c,dir.path(),request(&installation::generation(&c.bin).unwrap())).unwrap_err();
        assert_eq!(error.code, "repair_unsupported");
        assert_eq!(error.operation_id, ID);
        assert!(!error.retryable);
        assert_eq!(before,std::fs::read(dir.path().join("desktop-install.json")).unwrap());
    }
    #[test]
    fn repair_uses_shared_single_flight_and_preserves_request_id_on_busy() {
        let (dir,c,_) = fixture();
        let guard = c.operation.lock().unwrap();
        let error = request_repair(&c,request(&installation::generation(&c.bin).unwrap())).unwrap_err();
        assert_eq!(error.code, "operation_busy");
        assert_eq!(error.operation_id, ID);
        drop(guard);
        assert_eq!(execute_repair(&c,dir.path(),request(&installation::generation(&c.bin).unwrap())).unwrap_err().code,"repair_unsupported");
    }

    #[test]
    fn installed_verifier_never_accepts_caller_command_or_path_arguments() {
        let (_dir,c,_) = fixture();
        let generation=installation::generation(&c.bin).unwrap();
        let command=verification_command(&c.bin,&generation);
        let args: Vec<_>=command.get_args().map(|a|a.to_string_lossy().into_owned()).collect();
        assert_eq!(args,vec!["-NoProfile","-NonInteractive","-Command",VERIFY_INSTALLER]);
        assert!(!VERIFY_INSTALLER.contains("-Apply"));
        assert!(!VERIFY_INSTALLER.contains("Invoke-Expression"));
    }

    #[test]
    fn installed_script_hash_probe_rejects_tampered_or_missing_scripts() {
        let dir=crate::test_support::tempdir();
        let names=["windows-install.ps1", "windows-common.ps1", "windows-startup.ps1"];
        let entries: Vec<_>=names.iter().map(|name|json!({"path":name,"bytes":0,
            "sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"})).collect();
        for name in names { std::fs::write(dir.path().join(name),b"").unwrap(); }
        storage::atomic(&dir.path().join("windows-manifest.json"),&json!({"schema":1,"files":entries})).unwrap();
        let generation=installation::generation(dir.path()).unwrap();
        verify_installer(dir.path(),&generation).unwrap();
        std::fs::write(dir.path().join("windows-install.ps1"),b"fixture-tamper").unwrap();
        assert!(verify_installer(dir.path(),&generation).is_err());
        std::fs::remove_file(dir.path().join("windows-install.ps1")).unwrap();
        assert!(verify_installer(dir.path(),&generation).is_err());
    }

    fn result(request: &RepairRequest, state: &str, code: &str, exit: i32) -> Value {
        json!({"schemaVersion":1,"action":"repair_integration","operationId":request.operation_id,
            "step":request.step.name(),"expectedGeneration":request.expected_generation,"state":state,
            "code":code,"stage":"shortcut_verify","call":"IPersistFile.Load/GetPath/GetArguments/GetIconLocation",
            "hresult":"0x80004005","recoveryState":"clear","retryable":false,"coreInstalled":true,
            "runtimeVerification":"pending","processesStarted":0,"processesStopped":0,"exitCode":exit,
            "privateExtra":"fixture-private-path"})
    }
    #[test]
    fn repair_command_uses_only_fixed_installed_file_and_closed_arguments() {
        let (_dir,c,_)=fixture(); let mut req=request(&installation::generation(&c.bin).unwrap());req.check_repair=true;
        let command=repair_command(&c.bin,&req);
        let args:Vec<_>=command.get_args().map(|v|v.to_string_lossy().into_owned()).collect();
        assert_eq!(args,vec!["-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",
            &c.bin.join("windows-install.ps1").display().to_string(),"-RepairStep","startup","-OperationId",ID,
            "-ExpectedGeneration",&req.expected_generation,"-CheckRepair"]);
        assert!(!args.iter().any(|a|a=="-Apply"));
    }
    #[test]
    fn structured_failures_keep_exit_code_and_are_correlated_and_redacted() {
        let req=request(&"a".repeat(64)); let mut reply=result(&req,"failed","integration_repair_failed",1);
        let clean=decode_result(&serde_json::to_vec(&reply).unwrap(),Some(1),&req).unwrap();
        assert_eq!(clean["code"],"integration_repair_failed");assert_eq!(clean["exitCode"],1);
        assert_eq!(clean["hresult"],"0x80004005");assert!(!clean.to_string().contains("fixture-private"));
        reply["operationId"]=json!("wrong");assert!(decode_result(&serde_json::to_vec(&reply).unwrap(),Some(1),&req).is_err());
        reply["operationId"]=json!(ID);reply["processesStarted"]=json!(1);
        assert!(decode_result(&serde_json::to_vec(&reply).unwrap(),Some(1),&req).is_err());
    }
    #[test]
    fn completion_requires_matching_process_exit_and_schema() {
        let req=request(&"a".repeat(64)); let mut reply=result(&req,"completed","integration_completed",0);
        assert_eq!(decode_result(&serde_json::to_vec(&reply).unwrap(),Some(0),&req).unwrap()["state"],"completed");
        assert!(decode_result(&serde_json::to_vec(&reply).unwrap(),Some(1),&req).is_err());
        reply["schemaVersion"]=json!(2);assert!(decode_result(&serde_json::to_vec(&reply).unwrap(),Some(0),&req).is_err());
    }
    #[test]
    fn uncertain_dispatch_checks_same_id_without_replaying_mutation() {
        use std::cell::RefCell;
        let mut req=request(&"a".repeat(64));let calls=RefCell::new(Vec::new());
        let r=reconcile_run(&mut req,|request|{
            calls.borrow_mut().push((request.operation_id.clone(),request.step.name(),request.check_repair));
            if !request.check_repair { anyhow::bail!("Fixture lost output"); }
            Ok(result(request,"failed","integration_repair_failed",1))
        });
        assert_eq!(r["state"],"failed");assert_eq!(calls.into_inner(),vec![(ID.into(),"startup",false),(ID.into(),"startup",true)]);
    }
    #[test]
    fn repeated_reconciliation_failure_stays_unknown() {
        let mut req=request(&"a".repeat(64));
        let r=reconcile_run(&mut req,|_|anyhow::bail!("Fixture unavailable"));
        assert_eq!(r["state"],"unknown");assert_eq!(r["operationId"],ID);assert_eq!(r["exitCode"],Value::Null);
    }
    #[cfg(windows)]
    #[test]
    fn verified_installed_protocol_probe_and_failure_invocation_work_without_os_mutation() {
        let (dir,c,_)=protocol_fixture();let generation=installation::generation(&c.bin).unwrap();
        assert!(probe_capability(&c.bin,&generation).unwrap());
        let before=std::fs::read(dir.path().join("desktop-install.json")).unwrap();
        let result=execute_repair(&c,dir.path(),request(&generation)).unwrap();
        assert_eq!(result["state"],"failed");assert_eq!(result["exitCode"],1);assert_eq!(result["hresult"],"0x80070005");
        assert!(!result.to_string().contains("fixture-private"));
        assert_eq!(before,std::fs::read(dir.path().join("desktop-install.json")).unwrap());
        let guards=script_handles(&c.bin).unwrap();
        assert!(std::fs::write(c.bin.join("windows-install.ps1"),b"tamper").is_err());drop(guards);
        std::fs::write(c.bin.join("windows-install.ps1"),b"tamper").unwrap();
        assert!(!repair_check_available(&c.bin,&generation));
        assert_eq!(execute_repair(&c,dir.path(),request(&generation)).unwrap_err().code,"repair_unsupported");
    }
}
