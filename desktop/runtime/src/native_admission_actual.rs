//! Opt-in installed consumer: real Bridge/core locks, TCP authentication, OS
//! inventory and exact process handles. Desktop/native and helper status are
//! synthetic. No package discovery, registry, external services or model calls.
use super::*;
use pocket_core::admission::{GatePhase,Installation};
use sha2::{Digest,Sha256};
use std::{fs,process::{Command,Stdio}};
#[test]
#[ignore="requires a newly marked sanitized empty-admission snapshot"]
fn empty_admission_snapshot_reports_exact_boundary() {
    let root=PathBuf::from(std::env::var_os("POCKET_EMPTY_ADMISSION_PROBE").expect("new fixture root"));
    assert_eq!(fs::read(root.join(".empty-admission-probe")).unwrap(),b"sanitized-new-root-no-old-processes");
    let state=root.join("install/isolated state");let j=read_status(&state).unwrap().unwrap();
    assert_eq!(j.install_root,root.join("install"));assert_eq!(j.state_directory,state);
    assert_eq!(j.phase,"empty_resuming");
    let result=io::admission::finish_empty(&j);
    println!("empty admission boundary result: {result:#?}");
    result.expect("fresh snapshot with proved retired registrations must finish empty admission");
    let gate=Installation::open(&j.install_root).unwrap().update().unwrap().gate().unwrap().unwrap();
    assert_eq!(gate.phase,GatePhase::Released);assert_eq!(gate.allowed_generation,Some(j.to_generation));
}
fn hash(bytes:&[u8])->String {format!("{:x}",Sha256::digest(bytes))}
fn wait<T>(mut f:impl FnMut()->Result<T>)->T {
    let deadline=Instant::now()+Duration::from_secs(30);
    loop {match f(){Ok(v)=>return v,Err(e)=>{if Instant::now()>deadline{panic!("installed fixture: {e:#}")}}}std::thread::sleep(Duration::from_millis(100));}
}
const ADAPTER:&str=r#"param($NativeUpdateMode,$InstallRoot,$StateDirectory)
$ErrorActionPreference='Stop'
$fixtureRoot=$InstallRoot;$fixtureState=$StateDirectory;$fixtureMode=$NativeUpdateMode
trap { [IO.File]::WriteAllText((Join-Path $fixtureRoot 'adapter-error.txt'),$_.Exception.Message);throw }
. (Join-Path $fixtureRoot 'fixture/windows-install.ps1')
$InstallRoot=$fixtureRoot;$StateDirectory=$fixtureState
function Get-PocketDesktopPackage {return @{InstallLocation=$fixtureRoot}}
$script:kernelInventory=${function:Get-PocketInstallProcesses}
function Get-PocketInstallProcesses {
 $all=@(& $script:kernelInventory);$selected=@{}
 foreach($row in $all){if($row.ExecutablePath -and $row.ExecutablePath.StartsWith($fixtureRoot+'\',[StringComparison]::OrdinalIgnoreCase)){$selected[[int]$row.ProcessId]=$row}}
 do {
  $added=$false
  foreach($row in $all){
   if($selected.ContainsKey([int]$row.ProcessId) -or -not $selected.ContainsKey([int]$row.ParentProcessId)){continue}
   $parent=Get-PocketInstallProcessIdentity ([int]$row.ParentProcessId)
   $child=Get-PocketInstallProcessIdentity ([int]$row.ProcessId)
   # An older unrelated OS orphan may still name a reused fixture PID. It is
   # outside this isolated tree; every younger child, including unknown images,
   # remains subject to the production ancestry/image checks below.
   if($child -and $parent -and $child.createdAt -le $parent.createdAt){continue}
   $selected[[int]$row.ProcessId]=$row;$added=$true
  }
 }while($added)
 return @($selected.Values)
}
if($fixtureMode -eq 'Cleanup') {
 if([IO.File]::ReadAllText((Join-Path $fixtureRoot '.fixture-owner')) -ne 'native-consumer-test'){throw 'not our fixture'}
 $prefix=$fixtureRoot+'\'
 for($attempt=0;$attempt -lt 5;$attempt++){
  $rows=@(Get-PocketInstallProcesses | Where-Object {$_.ExecutablePath -and $_.ExecutablePath.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)})
  if(-not $rows.Count){'{"clean":true}';exit 0}
  foreach($row in $rows){$i=Get-PocketInstallProcessIdentity ([int]$row.ProcessId);if($i -and $i.executable.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){Stop-PocketInstallProcess @{pid=$i.pid;createdAt=$i.createdAt;executable=$i.executable;sha256=(Get-PocketFileHash $i.executable)}}}
  Start-Sleep -Milliseconds 100
 }
 throw 'fixture cleanup incomplete'
}elseif($fixtureMode -eq 'Inventory') {
 $all=@(Get-PocketInstallProcesses);$members=@($all|Where-Object {$_.ExecutablePath -and $_.ExecutablePath.StartsWith($fixtureRoot+'\',[StringComparison]::OrdinalIgnoreCase)})
 $selected=@($members.ProcessId)
 @($all|Where-Object {$_.ProcessId -in $selected -or $_.ParentProcessId -in $selected}|Select-Object ProcessId,ParentProcessId,Name,ExecutablePath)|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $fixtureRoot 'os-tree-evidence.json')
 Get-PocketNativeInventory $fixtureRoot $fixtureState | ConvertTo-Json -Depth 16 -Compress
}else{
 $record=Get-Content -LiteralPath (Join-Path $fixtureRoot 'desktop-install.json') -Raw | ConvertFrom-Json
 $bridge=Join-Path $fixtureRoot ('bin/'+$record.generation+'/pocket-bridge.exe')
 $info=[Diagnostics.ProcessStartInfo]::new()
 $info.FileName=Join-Path $fixtureRoot 'app/Codex.exe'
 $info.Arguments='--desktop --multiple --fixture-root "'+$fixtureRoot+'" --bridge "'+$bridge+'" --native "'+(Join-Path $fixtureRoot 'native.exe')+'"'
 if(Test-Path -LiteralPath (Join-Path $fixtureRoot 'fail-candidate')){$info.Arguments+=' --skip-initialize'}
 $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.WorkingDirectory=$fixtureRoot
 $child=[Diagnostics.Process]::Start($info)
 [IO.File]::WriteAllText((Join-Path $fixtureRoot 'launched.json'),(@{pid=$child.Id}|ConvertTo-Json -Compress))
 $child.Dispose();'{"launched":true}'
}
"#;
struct Fixture {root:PathBuf,j:Journal,cleanup:Vec<io::Batch>}
impl Fixture {
 fn new()->Self {
    let _owner=crate::test_support::tempdir();
    let base=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../.local/v070");
    let root=tempfile::Builder::new().prefix("admission-consumer-").tempdir_in(base).unwrap().keep();
    let root=root.components().collect::<PathBuf>();
    // Normalize '..' once; production rejects alternate physical spellings.
    let canonical=fs::canonicalize(&root).unwrap();let root=PathBuf::from(canonical.to_str().unwrap().trim_start_matches(r"\\?\"));
    fs::write(root.join(".fixture-owner"),b"native-consumer-test").unwrap();
    for name in ["app","state","runtimes","home","temp","fixture","appdata","localappdata"]{storage::private_dir(&root.join(name)).unwrap();}
    let workload=std::env::var_os("POCKET_TEST_NATIVE_FIXTURE").expect("explicit synthetic fixture executable");
    fs::copy(&workload,root.join("app/Codex.exe")).unwrap();fs::copy(&workload,root.join("native.exe")).unwrap();
    fs::write(root.join("fixture/windows-install.ps1"),include_str!("../../../scripts/windows-install.ps1")).unwrap();
    fs::write(root.join("fixture/windows-common.ps1"),include_str!("../../../scripts/windows-common.ps1")).unwrap();
    fs::write(root.join("fixture/windows-startup.ps1"),include_str!("../../../scripts/windows-startup.ps1")).unwrap();
    let bridge=fs::read(std::env::var_os("POCKET_TEST_BRIDGE").expect("explicit current Bridge executable")).unwrap();
    let payload:Value=serde_json::from_slice(&fs::read(PathBuf::from(std::env::var_os("POCKET_TEST_PAYLOAD").expect("existing payload inventory only")).join("windows-manifest.json")).unwrap()).unwrap();
    let mut generations=vec![];
    for version in ["0.7.0","0.7.1"] {
      let mut files=vec![];let mut bytes=vec![];
      for entry in payload["files"].as_array().unwrap() {
        let name=entry["path"].as_str().unwrap();
        let content=match name {
          "pocket-bridge.exe"=>bridge.clone(),
          "windows-install.ps1"=>ADAPTER.as_bytes().to_vec(),
          "windows-common.ps1"=>include_bytes!("../../../scripts/windows-common.ps1").to_vec(),
          _=>b"synthetic payload; never executed".to_vec()
        };
        files.push(json!({"path":name,"sha256":hash(&content),"bytes":content.len()}));bytes.push((name,content));
      }
      let manifest=serde_json::to_vec(&json!({"schema":1,"version":version,"files":files})).unwrap();
      let g=hash(&manifest);let bin=root.join("bin").join(&g);storage::private_dir(&bin).unwrap();
      fs::write(bin.join("windows-manifest.json"),manifest).unwrap();
      for (name,content) in bytes {fs::write(bin.join(name),content).unwrap();}
      generations.push(g);
    }
    fs::write(root.join("pocket-launcher.exe"),b"stable identity fixture; never execute").unwrap();
    let mut j=super::recovery_tests::journal(&root);j.original=None;j.verified=None;j.loaded_threads=vec![];
    j.from_generation=generations[0].clone();j.to_generation=generations[1].clone();
    j.candidate_record=json!({"version":3,"installRoot":root,"stateDirectory":j.state_directory,"bridge":root.join("pocket-launcher.exe"),"generation":j.to_generation,"releaseHash":j.to_generation,"launcherHash":hash(&fs::read(root.join("pocket-launcher.exe")).unwrap())});
    storage::atomic(&root.join("desktop-install.json"),&j.candidate_record).unwrap();save(&mut j,"waiting_idle").unwrap();
    println!("installed consumer fixture: {}",root.display());
    Self{root,j,cleanup:vec![]}
 }
 fn environment(&self,c:&mut Command) {
    c.current_dir(&self.root).env("POCKET_INSTALL_ROOT",&self.root).env("POCKET_RUNTIME_DIR",self.root.join("runtimes"))
     .env("POCKET_FIXTURE_ROOT",&self.root).env("POCKET_FIXTURE_SCHEMA",std::env::var_os("POCKET_TEST_NATIVE_SCHEMA").unwrap())
     .env("CODEX_HOME",self.root.join("home")).env("HOME",self.root.join("home"))
     .env("USERPROFILE",self.root.join("home")).env("APPDATA",self.root.join("appdata"))
     .env("LOCALAPPDATA",self.root.join("localappdata")).env("TEMP",self.root.join("temp")).env("TMP",self.root.join("temp"))
     .env_remove("POCKET_UPDATE_OPERATION").env_remove("POCKET_UPDATE_RELEASE_FILE")
     .env_remove("PSModulePath")
     .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());crate::process::hidden(c);
 }
 fn original(&mut self)->Plan {
    let mut c=Command::new(self.root.join("app/Codex.exe"));self.environment(&mut c);
    c.args(["--desktop","--multiple","--fixture-root"]).arg(&self.root).arg("--bridge").arg(self.root.join("bin").join(&self.j.from_generation).join("pocket-bridge.exe")).arg("--native").arg(self.root.join("native.exe"));
    let _child=c.spawn().unwrap();self.plan(true)
 }
 fn plan(&mut self,ready:bool)->Plan {
    let p=wait(|| io::inventory(&self.root,&self.j.state_directory,&self.j.to_generation).map_err(|e|anyhow::anyhow!("{e}; fixture adapter: {}",fs::read_to_string(self.root.join("adapter-error.txt")).unwrap_or_default()))?.context("tree pending"));
    self.cleanup.push(io::Batch::open_fixture(&p).unwrap());
    assert_eq!(p.additional.len(),1,"complete two-Bridge tree required");
    if ready {wait(||io::read_ready(&p).map_err(|e| {
        let status=connections(&p).iter().map(|c|io::rpc(c,"pocket/status",json!({})).map(|v|json!({"runtimeId":v["runtimeId"],"initialized":v["initialized"],"schemaState":v["schemaState"],"nativeProcess":v["nativeProcess"]})).unwrap_or(Value::Null)).collect::<Vec<_>>();
        let _=storage::atomic(&self.root.join("readiness-evidence.json"),&status);
        anyhow::anyhow!("{e}; readiness {status:?}")
    }));}
    p
 }
 fn mode(&self,p:&Plan,mode:&str,revision:u32) {
    storage::atomic(&self.root.join("desktop-command.json"),&json!({"mode":mode,"revision":revision})).unwrap();
    wait(||{let v=io::rpc(p,"pocket/managementStatus",json!({}))?;ensure!(if mode=="idle"{v["busy"]==false}else if mode=="waiting"{v["pendingQuestions"].as_u64().unwrap_or(0)>0}else{v["activeTurns"].as_u64().unwrap_or(0)>0},"mode pending");Ok(())});
 }
 fn late_start_rejected(&self) {
    let mut c=Command::new(self.root.join("bin").join(&self.j.from_generation).join("pocket-bridge.exe"));self.environment(&mut c);
    c.arg("--original").arg(self.root.join("native.exe")).args(["--","app-server"]);
    let out=crate::process::run_bounded(c,5);assert!(out.is_err(),"late Bridge must not spawn native");
 }
 fn launch(&mut self,ready:bool)->Plan {
    // Only the test's child adapter receives fixture environment. Product launch
    // itself writes/validates the persistent gate and operation startup env.
    let mut c=io::test_launch_command(&self.j).unwrap();self.environment(&mut c);
    io::startup_environment(&mut c,&self.j).unwrap();
    io::admission::allow_start(&self.j).unwrap();
    assert!(c.status().unwrap().success());self.plan(ready)
 }
 fn stop(&mut self,p:&Plan,startup:bool)->Result<()> {
    let op=self.j.operation_id.clone();let mut b=Stop{j:&mut self.j,batch:None,startup};fenced_stop(&mut b,p,&op)
 }
 fn resume(&mut self,p:Plan,rollback:bool) {
    self.j.verified=Some(p);self.j.failure=Some("previous_transient_readiness_failure".into());
    save(&mut self.j,if rollback{"rollback_resuming"}else{"resuming"}).unwrap();
    let guard=io::admission::resume_guard(&self.j).unwrap();
    let mut b=Resume{live:LiveResume{gate:Some(guard)}};
    resume_verified_with(&mut self.j,rollback,&mut b).unwrap();
    drop(b);
    let g=Installation::open(&self.root).unwrap().update().unwrap().gate().unwrap().unwrap();
    assert_eq!(g.phase,GatePhase::Released);
 }
}
impl Drop for Fixture {fn drop(&mut self){for b in &self.cleanup{let _=b.stop(Instant::now()+Duration::from_secs(5));}match io::script(&self.root,&self.j.state_directory,&self.j.to_generation,"Cleanup"){Ok(v) if v["clean"]==true=>{let _=fs::write(self.root.join("fixture-finished.txt"),"Test-spawned exact handles closed; OS confirmed no executable beneath this private fixture root remains. Root retained.");},other=>eprintln!("FIXTURE CLEANUP NEEDS ATTENTION {}: {other:?}",self.root.display())}}}
struct Stop<'a>{j:&'a mut Journal,batch:Option<io::Batch>,startup:bool}
impl Backend for Stop<'_>{
 fn startup_fenced(&self)->bool{self.startup}
 fn idle(&mut self)->Result<bool>{let p=io::inventory(&self.j.install_root,&self.j.state_directory,&self.j.to_generation)?.context("no tree")?;for c in connections(&p){if io::rpc(&c,"pocket/managementStatus",json!({}))?["busy"]!=false{return Ok(false)}}Ok(true)}
 fn rpc(&mut self,p:&Plan,m:&str,v:Value)->Result<Value>{io::rpc(p,m,v)}
 fn prepare_stop(&mut self,p:&Plan)->Result<()>{self.batch=Some(io::Batch::open(p)?);Ok(())}
 fn persist_stop_intent(&mut self,p:&Plan)->Result<()>{self.j.stopping=Some(p.clone());save(self.j,if self.startup{"rollback_stop_intent"}else{"stop_intent"})}
 fn stop(&mut self,_:&Plan,d:Instant)->Result<()>{self.batch.as_ref().unwrap().stop(d)}
}
// Helper discovery/handoff is exercised separately by the coordinator harness.
// Here only helper status is synthetic; all native evidence remains live.
struct Resume{live:LiveResume}
impl ResumeBackend for Resume {
 fn complete_released(&mut self,j:&Journal)->Result<bool>{self.live.complete_released(j)}
 fn helper(&mut self,j:&Journal,_:&str)->Result<Value>{let p=io::inventory(&j.install_root,&j.state_directory,&j.to_generation)?;let connections=p.as_ref().map(connections).unwrap_or_default();Ok(json!({"acceptingWrites":true,"runtimes":connections.iter().map(|c|json!({"id":c.runtime_id,"online":true,"initialized":true,"schemaState":"ready","inventoryReady":true})).collect::<Vec<_>>()}))}
 fn inventory(&mut self,j:&Journal)->Result<Option<Plan>>{self.live.inventory(j)}
 fn read_ready(&mut self,p:&Plan)->Result<Vec<String>>{self.live.read_ready(p)}
 fn image_ok(&mut self,i:&Image)->Result<()>{self.live.image_ok(i)}
 fn rpc(&mut self,p:&Plan,m:&str,v:Value)->Result<Value>{self.live.rpc(p,m,v)}
 fn finish_admission(&mut self,j:&Journal)->Result<()>{self.live.finish_admission(j)}
}
#[test]
#[ignore="requires explicit real Bridge, synthetic workload and payload inventory paths"]
fn installed_idle_close_reopen_and_failed_candidate_rollback() {
 for rollback in [false,true] {
    let mut f=Fixture::new();let old=f.original();f.j.original=Some(old.clone());f.j.loaded_threads=io::read_ready(&old).unwrap();save(&mut f.j,"waiting_idle").unwrap();
    for (n,mode) in ["active","waiting"].iter().enumerate(){f.mode(&old,mode,n as u32+1);assert!(f.stop(&old,false).is_err());io::image_ok(&old.native).unwrap();}
    f.mode(&old,"idle",3);fs::remove_file(f.root.join("desktop-command.json")).unwrap();
    {let guard=wait(||io::admission::close(&f.j,&old));f.late_start_rejected();drop(guard);}
    // Dropping/crashing the updater does not reopen startup admission.
    f.late_start_rejected();io::image_ok(&old.desktop).unwrap();
    f.stop(&old,false).unwrap();assert!(originals_gone(&old));save(&mut f.j,"starting").unwrap();
    if rollback {fs::write(f.root.join("fail-candidate"),b"skip initialize").unwrap();}
    let candidate=f.launch(!rollback);
    assert_ne!(candidate.desktop.identity,old.desktop.identity);
    assert!(startup_proven(&io::rpc(&candidate,"pocket/managementStatus",json!({})).unwrap(),&f.j.operation_id));
    if rollback {
      assert!(io::read_ready(&candidate).is_err());assert!(!released(&f.j).unwrap());
      save(&mut f.j,"rolling_back").unwrap();f.stop(&candidate,true).unwrap();assert!(originals_gone(&candidate));
      storage::atomic(&f.root.join("desktop-install.json"),&previous_record(&f.j)).unwrap();
      fs::remove_file(f.root.join("fail-candidate")).unwrap();save(&mut f.j,"rollback_starting").unwrap();
      let old_generation=f.launch(true);assert_eq!(generation(&old_generation).unwrap(),f.j.from_generation);f.resume(old_generation,true);
    }else{assert_eq!(generation(&candidate).unwrap(),f.j.to_generation);f.resume(candidate,false);}
    assert_eq!(f.j.phase,if rollback{"rolled_back"}else{"succeeded"});assert!(released(&f.j).unwrap());
    let current=f.j.verified.clone().unwrap();f.mode(&current,"active",4);f.mode(&current,"idle",5);
    println!("PASS installed {}: busy/waiting preserved, exclusive+durable gate rejected late startup, exact idle stop, fresh real Bridge read, receipt+resume released writes",f.j.phase);
 }
}

#[test]
#[ignore="requires explicit real Bridge, synthetic workload and payload inventory paths"]
fn empty_generation_gate_recovers_after_receipt_without_stopping_late_work() {
    let mut f=Fixture::new();
    let prior=super::recovery_tests::journal(&f.root);
    let prior_receipt=release_path(&prior).unwrap();storage::private_dir(prior_receipt.parent().unwrap()).unwrap();
    {
        let g=Installation::open(&f.root).unwrap().update().unwrap();
        let gate=pocket_core::admission::Gate{schema:1,operation_id:prior.operation_id.clone(),phase:GatePhase::Closing,allowed_generation:None,release_file:prior_receipt.clone()};
        g.set_gate(&gate).unwrap();
        g.set_gate(&pocket_core::admission::Gate{phase:GatePhase::Starting,allowed_generation:Some(f.j.from_generation.clone()),..gate.clone()}).unwrap();
        storage::atomic(&prior_receipt,&ReleaseReceipt{schema:1,operation_id:prior.operation_id.clone(),released:true}).unwrap();
        g.set_gate(&pocket_core::admission::Gate{phase:GatePhase::Released,allowed_generation:Some(f.j.from_generation.clone()),..gate}).unwrap();
    }
    save(&mut f.j,"empty_resuming").unwrap();
    assert!(read_status(&f.j.state_directory).is_ok(),"empty gate recovery must be a durable recoverable phase");
    // An uncertain abandoned Starting record is deliberately retained. Only
    // the artificial test record is removed by this test, never by consumer IO.
    let runtime_id=uuid::Uuid::new_v4().to_string();
    let registration=pocket_core::admission::Registration{schema:1,runtime_id:runtime_id.clone(),install_root:f.root.clone(),generation:f.j.from_generation.clone(),bridge:pocket_core::native_process::NativeProcess{pid:u32::MAX,created_at:1},bridge_executable:f.root.join("bin").join(&f.j.from_generation).join("pocket-bridge.exe"),descriptor:f.root.join("runtimes").join(format!("{runtime_id}.json")),operation_id:None,release_file:None,phase:pocket_core::admission::RegistrationPhase::Starting,native:None};
    let registration_path=f.root.join("control/native-startups").join(format!("{runtime_id}.json"));
    storage::atomic(&registration_path,&registration).unwrap();
    assert_eq!(Installation::open(&f.root).unwrap().update().unwrap().registrations().unwrap(),vec![registration]);
    assert!(io::admission::finish_empty(&f.j).is_err());
    assert!(registration_path.is_file());assert!(!released(&f.j).unwrap());
    let unchanged=Installation::open(&f.root).unwrap().update().unwrap().gate().unwrap().unwrap();
    assert_eq!(unchanged.phase,GatePhase::Released);assert_eq!(unchanged.allowed_generation,Some(f.j.from_generation.clone()));
    fs::remove_file(registration_path).unwrap();
    io::admission::finish_empty(&f.j).unwrap();
    let g=Installation::open(&f.root).unwrap().update().unwrap().gate().unwrap().unwrap();
    assert_eq!(g.allowed_generation,Some(f.j.to_generation.clone()));assert_eq!(g.phase,GatePhase::Released);
    // A second empty update must advance the existing Released gate again.
    // Retain the first receipt/archive exactly as the real coordinator does.
    let first_receipt_path=release_path(&f.j).unwrap();
    let first_receipt=fs::read(&first_receipt_path).unwrap();
    save(&mut f.j,"succeeded").unwrap();
    fs::rename(path(&f.j.state_directory),storage::control(&f.j.state_directory).join(format!("native-update-{}.json",f.j.handoff_id))).unwrap();
    let previous_bin=f.root.join("bin").join(&f.j.to_generation);
    let mut manifest:Value=storage::read(&previous_bin.join("windows-manifest.json")).unwrap();
    manifest["version"]=json!("0.7.2");
    let manifest_bytes=serde_json::to_vec(&manifest).unwrap();
    let third=hash(&manifest_bytes);let third_bin=f.root.join("bin").join(&third);
    storage::private_dir(&third_bin).unwrap();
    for entry in manifest["files"].as_array().unwrap(){let name=entry["path"].as_str().unwrap();fs::copy(previous_bin.join(name),third_bin.join(name)).unwrap();}
    fs::write(third_bin.join("windows-manifest.json"),manifest_bytes).unwrap();
    f.j.from_generation=f.j.to_generation.clone();f.j.to_generation=third.clone();
    f.j.operation_id=uuid::Uuid::new_v4().to_string();f.j.handoff_id=uuid::Uuid::new_v4().to_string();
    f.j.candidate_record["generation"]=json!(third);f.j.candidate_record["releaseHash"]=json!(third);
    storage::atomic(&f.root.join("desktop-install.json"),&f.j.candidate_record).unwrap();
    save(&mut f.j,"empty_resuming").unwrap();
    io::admission::finish_empty(&f.j).unwrap();
    let g=Installation::open(&f.root).unwrap().update().unwrap().gate().unwrap().unwrap();
    assert_eq!(g.operation_id,f.j.operation_id);assert_eq!(g.allowed_generation,Some(third));assert_eq!(g.phase,GatePhase::Released);
    assert_eq!(fs::read(first_receipt_path).unwrap(),first_receipt);
    // A real late Desktop with two real Bridges begins an active synthetic
    // turn after publication. Receipt recovery must leave all identities alive.
    let mut c=Command::new(f.root.join("app/Codex.exe"));f.environment(&mut c);
    c.args(["--desktop","--multiple","--fixture-root"]).arg(&f.root).arg("--bridge").arg(f.root.join("bin").join(&f.j.to_generation).join("pocket-bridge.exe")).arg("--native").arg(f.root.join("native.exe"));
    let _child=c.spawn().unwrap();let late=f.plan(true);f.mode(&late,"active",1);
    // Simulate crash after receipt publication but before terminal journal save.
    io::admission::finish_empty(&f.j).unwrap();
    for image in io::images(&late){io::image_ok(&image).unwrap();}
    assert!(io::rpc(&late,"pocket/managementStatus",json!({})).unwrap()["activeTurns"].as_u64().unwrap()>0);
    f.mode(&late,"idle",2);
    println!("PASS uncertain Starting retained; consecutive empty A->B->C gate advance; first receipt retained; post-receipt recovery preserved active two-Bridge tree");
}

#[test]
#[ignore="requires explicit real Bridge, synthetic workload and payload inventory paths"]
fn review_installed_post_receipt_tree_exit_and_later_desktop_complete_read_only() {
    for rollback in [false, true] {
        for gate_released in [false, true] {
            let mut f=Fixture::new();
            if rollback {storage::atomic(&f.root.join("desktop-install.json"),&previous_record(&f.j)).unwrap();}
            save(&mut f.j,if rollback {"rollback_starting"} else {"starting"}).unwrap();
            let receipt_path=release_path(&f.j).unwrap();
            {
                let guard=Installation::open(&f.root).unwrap().update().unwrap();
                guard.set_gate(&pocket_core::admission::Gate{schema:1,operation_id:f.j.operation_id.clone(),phase:GatePhase::Closing,allowed_generation:None,release_file:receipt_path.clone()}).unwrap();
            }
            let original=f.launch(true);
            f.j.verified=Some(original.clone());
            f.j.failure=Some("preserved_candidate_failure".into());
            save(&mut f.j,if rollback {"rollback_resuming"} else {"resuming"}).unwrap();
            publish_release(&f.j).unwrap();
            let receipt=fs::read(&receipt_path).unwrap();
            {
                let guard=io::admission::resume_guard(&f.j).unwrap();
                assert!(!io::admission::finish_released(&f.j,&guard).unwrap(),"Starting completion requires the original complete tree to be Gone");
                assert_eq!(guard.gate().unwrap().unwrap().phase,GatePhase::Starting);
            }
            if gate_released {
                let guard=io::admission::resume_guard(&f.j).unwrap();
                io::admission::finish(&f.j,&guard).unwrap();
            }
            // Normal fixture Desktop exit, not consumer stop. Its full real
            // Bridge/native tree exits after the irreversible publication.
            storage::atomic(&f.root.join("desktop-command.json"),&json!({"mode":"exit","revision":100})).unwrap();
            wait(||{ensure!(originals_gone(&original),"original tree still exiting");Ok(())});
            fs::remove_file(f.root.join("desktop-command.json")).unwrap();
            let expected=if rollback {f.j.from_generation.clone()} else {f.j.to_generation.clone()};
            let later=if gate_released {
                let mut command=Command::new(f.root.join("app/Codex.exe"));f.environment(&mut command);
                command.args(["--desktop","--multiple","--fixture-root"]).arg(&f.root).arg("--bridge").arg(f.root.join("bin").join(&expected).join("pocket-bridge.exe")).arg("--native").arg(f.root.join("native.exe"));
                let _child=command.spawn().unwrap();let plan=f.plan(true);f.mode(&plan,"active",101);Some(plan)
            } else {
                let installation=Installation::open(&f.root).unwrap();
                assert!(installation.startup(&expected,None).is_err(),"receipt plus Starting must not admit a new Bridge");
                None
            };
            // An uncertain later Starting record is not part of the published
            // readiness proof and must survive this operation's completion.
            let uncertain_id=uuid::Uuid::new_v4().to_string();
            let uncertain=pocket_core::admission::Registration{schema:1,runtime_id:uncertain_id.clone(),install_root:f.root.clone(),generation:expected.clone(),bridge:pocket_core::native_process::NativeProcess{pid:u32::MAX,created_at:1},bridge_executable:f.root.join("bin").join(&expected).join("pocket-bridge.exe"),descriptor:f.root.join("runtimes").join(format!("{uncertain_id}.json")),operation_id:Some(f.j.operation_id.clone()),release_file:Some(receipt_path.clone()),phase:pocket_core::admission::RegistrationPhase::Starting,native:None};
            let uncertain_path=f.root.join("control/native-startups").join(format!("{uncertain_id}.json"));
            storage::atomic(&uncertain_path,&uncertain).unwrap();let uncertain_bytes=fs::read(&uncertain_path).unwrap();
            let result=run(&f.root,&f.j.state_directory,&f.j.handoff_id);
            assert!(result.is_ok(),"post-receipt completion cannot require an exited Desktop: {result:?}");
            f.j=read_status(&f.j.state_directory).unwrap().unwrap();
            assert_eq!(f.j.phase,if rollback {"rolled_back"} else {"succeeded"});
            assert_eq!(f.j.failure,if rollback {Some("preserved_candidate_failure".into())} else {None});
            assert_eq!(f.j.verified,Some(original),"never adopt later native identities");
            assert_eq!(fs::read(&receipt_path).unwrap(),receipt);
            assert_eq!(fs::read(&uncertain_path).unwrap(),uncertain_bytes,"uncertain Starting must never be cleared");
            let gate=Installation::open(&f.root).unwrap().update().unwrap().gate().unwrap().unwrap();
            assert_eq!(gate.phase,GatePhase::Released);assert_eq!(gate.allowed_generation,Some(expected));
            if let Some(later)=later {
                for image in io::images(&later){io::image_ok(image).unwrap();}
                assert!(io::rpc(&later,"pocket/managementStatus",json!({})).unwrap()["activeTurns"].as_u64().unwrap()>0);
                f.mode(&later,"idle",102);
            }
            println!("PASS post-receipt {} gate={gate_released}: original complete tree Gone; exact receipt/gate/journal finished; later work unadopted",f.j.phase);
        }
    }
}
