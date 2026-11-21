//! Reconcile the native-only desktop entry with the selected, verified release.
//! Separate from the install record so older update coordinators can adopt it.
use crate::{launcher,storage};
use anyhow::{Result,Context,ensure};
use serde::{Serialize,Deserialize};
use std::path::{Path,PathBuf};

#[derive(Clone,Debug,Serialize,Deserialize,PartialEq,Eq)]
pub struct Setting { pub value:Option<String>, pub expandable:bool }
#[derive(Clone,Debug,Serialize,Deserialize)]
struct Journal { schema:u32, original:Setting, selected:Setting, pending:Option<Setting> }

fn owned(root:&Path,value:&Setting)->bool {
    let Some(value)=&value.value else{return false};
    let p=PathBuf::from(value);
    p==root.join("pocket-launcher.exe") ||
        p.file_name().is_some_and(|n|matches!(n.to_str(),Some("pocket-native-proxy.exe"|"pocket-bridge.exe"))) &&
        p.parent().and_then(Path::parent)==Some(root.join("bin").as_path()) &&
        p.parent().and_then(Path::file_name).and_then(|n|n.to_str()).is_some_and(|s|s.len()==64&&s.bytes().all(|c|c.is_ascii_hexdigit()))
}

fn reconcile_with(root:&Path,target:Option<&Path>,mut read:impl FnMut()->Result<Setting>,mut write:impl FnMut(&Setting)->Result<()>)->Result<()> {
    let path=root.join("control/native-entry.json");
    let current=read()?;
    let mut journal=if path.exists(){let j:Journal=storage::read(&path)?;ensure!(j.schema==1,"Unsupported native entry journal");j}
        else {Journal{schema:1,original:current.clone(),selected:current.clone(),pending:None}};
    if current!=journal.selected && journal.pending.as_ref()!=Some(&current) && !owned(root,&current) {
        anyhow::bail!("Native entry was changed outside Pocket");
    }
    // Preserve an explicit third-party native program and its registry value kind.
    if journal.original.value.is_some() && !owned(root,&journal.original) {return Ok(())}
    let target=target.map(|p|Setting{value:Some(p.to_string_lossy().into_owned()),expandable:false})
        .unwrap_or_else(||if owned(root,&journal.original){Setting{value:None,expandable:false}}else{journal.original.clone()});
    if current==target && journal.pending.is_none(){return Ok(())}
    journal.pending=Some(target.clone());
    storage::private_dir(&root.join("control"))?;
    storage::atomic(&path,&journal)?;
    if current!=target {write(&target)?;}
    ensure!(read()?==target,"Native entry publication failed");
    journal.selected=target;journal.pending=None;
    storage::atomic(&path,&journal)
}

pub fn reconcile(root:&Path)->Result<()> {
    let _lock=crate::updates::InstallMutex::acquire(root)?;
    let (bin,_)=launcher::resolve_release(root)?;
    let manifest:serde_json::Value=storage::read(&bin.join("windows-manifest.json"))?;
    let target=if manifest["files"].as_array().is_some_and(|files|files.iter().any(|f|f["path"]=="pocket-native-proxy.exe")) {
        Some(launcher::verified_exe(&bin,"pocket-native-proxy.exe")?)
    }else{None};
    reconcile_with(root,target.as_deref(),registry::read,registry::write)
}

#[cfg(windows)]
mod registry {
    use super::*;
    use windows_sys::Win32::System::Registry::*;
    fn wide(s:&str)->Vec<u16>{s.encode_utf16().chain(Some(0)).collect()}
    pub fn read()->Result<Setting>{unsafe {
        let mut kind=0;let mut size=0;
        let key=wide("Environment");let name=wide("CODEX_CLI_PATH");
        let code=RegGetValueW(HKEY_CURRENT_USER,key.as_ptr(),name.as_ptr(),RRF_RT_REG_SZ|RRF_RT_REG_EXPAND_SZ|RRF_NOEXPAND,&mut kind,std::ptr::null_mut(),&mut size);
        if code==2{return Ok(Setting{value:None,expandable:false})}
        ensure!(code==0&&size<=65536,"Cannot read native entry");
        let mut buffer=vec![0u16;(size as usize/2)+1];
        ensure!(RegGetValueW(HKEY_CURRENT_USER,key.as_ptr(),name.as_ptr(),RRF_RT_REG_SZ|RRF_RT_REG_EXPAND_SZ|RRF_NOEXPAND,&mut kind,buffer.as_mut_ptr().cast(),&mut size)==0,"Cannot read native entry");
        let length=buffer.iter().position(|c|*c==0).context("Invalid native entry")?;
        Ok(Setting{value:Some(String::from_utf16(&buffer[..length])?),expandable:kind==REG_EXPAND_SZ})
    }}
    pub fn write(value:&Setting)->Result<()>{unsafe{
        let mut key=std::ptr::null_mut();
        ensure!(RegCreateKeyExW(HKEY_CURRENT_USER,wide("Environment").as_ptr(),0,std::ptr::null(),0,KEY_SET_VALUE,std::ptr::null(),&mut key,std::ptr::null_mut())==0,"Cannot publish native entry");
        let name=wide("CODEX_CLI_PATH");
        let code=match &value.value {Some(v)=>{let v=wide(v);RegSetValueExW(key,name.as_ptr(),0,if value.expandable{REG_EXPAND_SZ}else{REG_SZ},v.as_ptr().cast(),(v.len()*2) as u32)},None=>RegDeleteValueW(key,name.as_ptr())};
        RegCloseKey(key);
        ensure!(code==0||(code==2&&value.value.is_none()),"Cannot publish native entry");
        use windows_sys::Win32::UI::WindowsAndMessaging::*;
        SendMessageTimeoutW(HWND_BROADCAST,WM_SETTINGCHANGE,0,wide("Environment").as_ptr() as isize,SMTO_ABORTIFHUNG,1000,std::ptr::null_mut());
        Ok(())
    }}
}
#[cfg(not(windows))]
mod registry {use super::*;pub fn read()->Result<Setting>{anyhow::bail!("Windows native entry required")}pub fn write(_: &Setting)->Result<()>{anyhow::bail!("Windows native entry required")}}

#[cfg(test)]
mod tests {
    use super::*;use std::cell::RefCell;
    #[test]
    fn native_only_entry_must_be_verifiable_before_publication(){
        use sha2::{Digest,Sha256};
        let temp=crate::test_support::tempdir();let bin=temp.path();
        let bytes=b"inert native entry fixture";
        std::fs::write(bin.join("pocket-native-proxy.exe"),bytes).unwrap();
        storage::atomic(&bin.join("windows-manifest.json"),&serde_json::json!({"files":[{
            "path":"pocket-native-proxy.exe","bytes":bytes.len(),"sha256":format!("{:x}",Sha256::digest(bytes))
        }]})).unwrap();
        assert_eq!(launcher::verified_exe(bin,"pocket-native-proxy.exe").unwrap(),bin.join("pocket-native-proxy.exe"));
        std::fs::write(bin.join("pocket-native-proxy.exe"),b"changed").unwrap();
        assert!(launcher::verified_exe(bin,"pocket-native-proxy.exe").is_err());
    }
    #[test]
    fn upgrade_from_legacy_then_rollback_restores_environment_without_changing_install_record(){
        let temp=crate::test_support::tempdir();let root=temp.path();
        let env=RefCell::new(Setting{value:None,expandable:false});
        let first=root.join("bin").join("a".repeat(64)).join("pocket-native-proxy.exe");
        let second=root.join("bin").join("b".repeat(64)).join("pocket-native-proxy.exe");
        let run=|target:Option<&Path>|reconcile_with(root,target,||Ok(env.borrow().clone()),|s|{*env.borrow_mut()=s.clone();Ok(())});
        run(Some(&first)).unwrap();assert_eq!(env.borrow().value.as_deref(),first.to_str());
        run(Some(&second)).unwrap();assert_eq!(env.borrow().value.as_deref(),second.to_str());
        run(Some(&first)).unwrap();assert_eq!(env.borrow().value.as_deref(),first.to_str());
        run(None).unwrap();assert_eq!(env.borrow().value,None);
        assert!(!root.join("desktop-install.json").exists());
    }
    #[test]
    fn interrupted_publication_resumes_and_custom_override_is_never_replaced(){
        let temp=crate::test_support::tempdir();let root=temp.path();let target=root.join("proxy.exe");
        let env=RefCell::new(Setting{value:None,expandable:false});
        assert!(reconcile_with(root,Some(&target),||Ok(env.borrow().clone()),|_|anyhow::bail!("interrupted")).is_err());
        reconcile_with(root,Some(&target),||Ok(env.borrow().clone()),|s|{*env.borrow_mut()=s.clone();Ok(())}).unwrap();
        *env.borrow_mut()=Setting{value:Some("custom.exe".into()),expandable:true};
        assert!(reconcile_with(root,Some(&target),||Ok(env.borrow().clone()),|_|panic!("must not write")).is_err());
        let custom=crate::test_support::tempdir();
        reconcile_with(custom.path(),Some(&target),||Ok(env.borrow().clone()),|_|panic!("must not write")).unwrap();
    }
}
