//! Installed files and running processes are separate evidence.
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::path::Path;

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RuntimeStamp {
    pub version: String,
    pub generation: Option<String>,
    pub pid: u32,
    pub started_at: u64,
    pub control_protocol: u32,
    pub process_created_at: Option<u64>,
    pub executable_sha256: Option<String>,
}

impl RuntimeStamp {
    pub fn current() -> Self {
        static STAMP: std::sync::OnceLock<RuntimeStamp> = std::sync::OnceLock::new();
        STAMP.get_or_init(|| {
            let exe=std::env::current_exe().ok();
            let generation=exe.as_deref().and_then(Path::parent).and_then(|p|generation(p).ok());
            let process_created_at=process_evidence(std::process::id()).map(|(created,_)|created);
            let executable_sha256=exe.as_deref().and_then(|p|digest_file(p).ok());
            Self { version: env!("CARGO_PKG_VERSION").into(), generation, pid: std::process::id(),
                started_at: crate::storage::now(), control_protocol: 1, process_created_at, executable_sha256 }
        }).clone()
    }
}

fn digest_file(path:&Path)->anyhow::Result<String>{
    use std::io::Read;
    crate::storage::unredirected(path)?;
    let mut file=std::fs::File::open(path)?;
    let mut hash=Sha256::new();let mut bytes=[0u8;65536];
    loop {let n=file.read(&mut bytes)?;if n==0{break};hash.update(&bytes[..n]);}
    Ok(format!("{:x}",hash.finalize()))
}

#[cfg(windows)]
fn process_evidence(pid:u32)->Option<(u64,std::path::PathBuf)>{
    use windows_sys::Win32::{Foundation::{CloseHandle,FILETIME,WAIT_TIMEOUT},System::Threading::*};
    if pid==0{return None}
    unsafe {
        let handle=OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION|PROCESS_SYNCHRONIZE,0,pid);
        if handle.is_null(){return None}
        let result=(||{
            if WaitForSingleObject(handle,0)!=WAIT_TIMEOUT{return None}
            if !same_user(handle){return None}
            let mut created:FILETIME=std::mem::zeroed();let mut exit=created;let mut kernel=created;let mut user=created;
            if GetProcessTimes(handle,&mut created,&mut exit,&mut kernel,&mut user)==0{return None}
            let mut path=vec![0u16;32768];let mut length=path.len() as u32;
            if QueryFullProcessImageNameW(handle,0,path.as_mut_ptr(),&mut length)==0{return None}
            Some(((u64::from(created.dwHighDateTime)<<32)|u64::from(created.dwLowDateTime),
                std::path::PathBuf::from(String::from_utf16(&path[..length as usize]).ok()?)))
        })();
        CloseHandle(handle);result
    }
}
#[cfg(windows)]
unsafe fn same_user(handle:windows_sys::Win32::Foundation::HANDLE)->bool{
    use windows_sys::Win32::{Foundation::*,Security::*,System::Threading::*};
    unsafe {
        fn token_user(handle:HANDLE)->Option<Vec<usize>>{unsafe{
            let mut token=std::ptr::null_mut();
            if OpenProcessToken(handle,TOKEN_QUERY,&mut token)==0{return None}
            let mut size=0;GetTokenInformation(token,TokenUser,std::ptr::null_mut(),0,&mut size);
            let mut data=vec![0usize;(size as usize).div_ceil(std::mem::size_of::<usize>())];
            let ok=GetTokenInformation(token,TokenUser,data.as_mut_ptr().cast(),size,&mut size)!=0;
            CloseHandle(token);ok.then_some(data)
        }}
        let Some(other)=token_user(handle) else{return false};
        let Some(current)=token_user(GetCurrentProcess()) else{return false};
        EqualSid((*(other.as_ptr() as *const TOKEN_USER)).User.Sid,(*(current.as_ptr() as *const TOKEN_USER)).User.Sid)!=0
    }
}
#[cfg(not(windows))]
fn process_evidence(_:u32)->Option<(u64,std::path::PathBuf)>{None}

/// A stale status file, a reused PID, or a changed on-disk manifest is not a live process.
fn verified_runtime(value:&Value,name:&str)->bool{
    let Some(pid)=value["pid"].as_u64().and_then(|n|u32::try_from(n).ok()) else{return false};
    let Some((created,path))=process_evidence(pid) else{return false};
    let claimed_created=value["processCreatedAt"].as_u64().or(value["processIdentity"]["createdAt"].as_u64());
    if claimed_created!=Some(created)||path.file_name().and_then(|v|v.to_str())!=Some(name){return false}
    let Some(bin)=path.parent() else{return false};
    let Some(g)=value["generation"].as_str().filter(|g|g.len()==64) else{return false};
    // The current-user install tree and immutable generation are independently checked.
    let Ok(root)=crate::storage::local_app_data().map(|p|p.join("CodexPocket/bin").join(g)) else{return false};
    if bin!=root||generation(bin).ok().as_deref()!=Some(g){return false}
    let Some(digest)=value["executableSha256"].as_str() else{return false};
    let Ok(manifest)=crate::storage::read::<Value>(&bin.join("windows-manifest.json")) else{return false};
    if !manifest["files"].as_array().is_some_and(|entries|entries.iter().any(|e|e["path"]==name&&e["sha256"]==digest)){return false}
    // Dashboard and tray share this bounded cache. Always re-probe process creation
    // and file metadata; avoid hashing the same large immutable executable every 5s.
    #[derive(PartialEq,Eq,Clone)]
    struct FileEvidence{path:std::path::PathBuf,created:u64,digest:String,length:u64,modified:std::time::SystemTime}
    static VERIFIED:std::sync::OnceLock<std::sync::Mutex<Vec<FileEvidence>>>=std::sync::OnceLock::new();
    let Ok(metadata)=std::fs::metadata(&path) else{return false};
    let Ok(modified)=metadata.modified() else{return false};
    let evidence=FileEvidence{path:path.clone(),created,digest:digest.into(),length:metadata.len(),modified};
    let Ok(mut cache)=VERIFIED.get_or_init(Default::default).lock() else{return false};
    if cache.contains(&evidence){return true}
    if digest_file(&path).ok().as_deref()!=Some(digest){return false}
    cache.retain(|item|item.path!=path);if cache.len()>=16{cache.remove(0);}
    cache.push(evidence);true
}

pub fn generation(bin: &Path) -> anyhow::Result<String> {
    let path = bin.join("windows-manifest.json");
    crate::storage::unredirected(&path)?;
    let bytes = std::fs::read(path)?;
    anyhow::ensure!(bytes.len() <= 1024 * 1024, "Manifest exceeds limit");
    Ok(format!("{:x}", Sha256::digest(&bytes)))
}

pub fn report(installed: &str, installed_version: &str, ui: &RuntimeStamp,
              supervisor: &Value, helper: &Value, consistent: bool) -> Value {
    fn component(installed: &str, value: &Value, name:&str) -> Value {
        let running = value["generation"].as_str();
        let verified=verified_runtime(value,name);
        json!({"version":value["version"],"generation":running,"pid":value["pid"],
            "controlProtocol":value["controlProtocol"],"runningVerified":verified,
            "state":match running {Some(g) if !g.is_empty()&&!installed.is_empty()&&g!=installed=>"restart_required",Some(g) if verified&&g==installed=>"current",_=>"unverified"}})
    }
    let ui = component(installed, &json!(ui),"pocket-desktop.exe");
    let supervisor = component(installed, supervisor,"pocket-supervisor.exe");
    let helper = component(installed, helper,"pocket-agent.exe");
    let pending = [&ui,&supervisor,&helper].iter().any(|v| v["state"] == "restart_required");
    json!({"consistent":consistent,"installedGeneration":installed,"installedVersion":installed_version,
        "ui":ui,"supervisor":supervisor,"helper":helper,"restartRequired":pending})
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn equal_versions_do_not_hide_different_generations() {
        let ui=RuntimeStamp{version:"0.6.3".into(),generation:Some("new".into()),..Default::default()};
        let old=json!({"version":"0.6.3","generation":"old","pid":42});
        let r=report("new","0.6.3",&ui,&old,&old,true);
        assert_eq!(r["supervisor"]["state"],"restart_required");
        assert_eq!(r["restartRequired"],true);
    }
    #[test]
    fn missing_generation_is_unverified_not_current() {
        let r=report("new","1",&RuntimeStamp::default(),&json!({"version":"1"}),&json!({}),true);
        assert_eq!(r["helper"]["state"],"unverified");
        assert_eq!(r["restartRequired"],false);
    }
    #[test]
    fn manifest_claim_without_process_evidence_is_never_current() {
        let claim=json!({"version":"1","generation":"new","pid":0});
        let r=report("new","1",&RuntimeStamp::default(),&claim,&claim,true);
        assert_eq!(r["helper"]["state"],"unverified");
        assert_eq!(r["helper"]["runningVerified"],false);
    }
    #[test]
    fn unavailable_installed_manifest_never_compares_equal() {
        let claim=json!({"version":"1","generation":"","pid":std::process::id()});
        let r=report("","1",&RuntimeStamp::default(),&claim,&claim,true);
        assert_eq!(r["helper"]["state"],"unverified");
    }
    #[cfg(windows)]
    #[test]
    fn current_user_process_has_stable_creation_and_path_evidence(){
        let first=process_evidence(std::process::id()).unwrap();
        assert_ne!(first.0,0);
        assert_eq!(first,process_evidence(std::process::id()).unwrap());
        assert!(first.1.is_file());
        assert!(process_evidence(0).is_none());
    }
}
