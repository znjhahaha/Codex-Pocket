//! Session-scoped native release evidence. Never infer ownership from process names.
use anyhow::{Result, ensure};
use pocket_core::native_process::NativeProcess;
use std::path::Path;

#[cfg(windows)]
pub(super) fn holders(path: &Path) -> Result<Vec<NativeProcess>> {
    use std::{os::windows::ffi::OsStrExt, ptr::null};
    use windows_sys::Win32::{Foundation::{ERROR_MORE_DATA, ERROR_SUCCESS}, System::RestartManager::*};
    struct Session(u32);
    impl Drop for Session { fn drop(&mut self) { unsafe { RmEndSession(self.0); } } }
    fn win(code: u32) -> Result<()> {
        ensure!(code == ERROR_SUCCESS, "Rollout owner inspection failed: {code}"); Ok(())
    }
    ensure!(path.is_file(), "Rollout unavailable; release is unconfirmed");
    let path: Vec<u16> = path.as_os_str().encode_wide().chain([0]).collect();
    let mut key = [0u16; CCH_RM_SESSION_KEY as usize + 1];
    let mut handle = 0;
    unsafe { win(RmStartSession(&mut handle, 0, key.as_mut_ptr()))?; }
    let session = Session(handle);
    unsafe { win(RmRegisterResources(handle,1,[path.as_ptr()].as_ptr(),0,null(),0,null()))?; }
    let mut apps = vec![RM_PROCESS_INFO::default(); 16];
    for _ in 0..3 {
        let (mut needed, mut count, mut reboot) = (0,apps.len() as u32,0);
        let result = unsafe{RmGetList(session.0,&mut needed,&mut count,apps.as_mut_ptr(),&mut reboot)};
        if result==ERROR_MORE_DATA {
            ensure!(needed<=1024,"Rollout ownership inventory too large");
            apps.resize(needed as usize,RM_PROCESS_INFO::default()); continue;
        }
        win(result)?;
        return Ok(apps.into_iter().take(count as usize).map(|v|NativeProcess {
            pid:v.Process.dwProcessId,
            created_at:(u64::from(v.Process.ProcessStartTime.dwHighDateTime)<<32)|u64::from(v.Process.ProcessStartTime.dwLowDateTime)
        }).collect());
    }
    anyhow::bail!("Rollout ownership is changing")
}
#[cfg(not(windows))]
pub(super) fn holders(_: &Path) -> Result<Vec<NativeProcess>> { anyhow::bail!("Native ownership inspection unavailable") }

#[cfg(all(test,windows))]
mod tests {
    use super::*;
    #[test]
    fn only_process_holding_the_selected_rollout_blocks_release() {
        let root=tempfile::tempdir().unwrap();
        let selected=root.path().join("selected.jsonl");
        let unrelated=root.path().join("unrelated.jsonl");
        std::fs::write(&selected,b"session").unwrap();
        let unrelated_handle=std::fs::File::create(&unrelated).unwrap();
        assert!(holders(&selected).unwrap().is_empty());
        let handle=std::fs::OpenOptions::new().read(true).write(true).open(&selected).unwrap();
        assert!(holders(&selected).unwrap().contains(&NativeProcess::capture(std::process::id()).unwrap()));
        drop(handle);
        assert!(holders(&selected).unwrap().is_empty());
        drop(unrelated_handle);
    }
    #[test]
    fn missing_rollout_does_not_prove_owner_release() {
        let root=tempfile::tempdir().unwrap();
        assert!(holders(&root.path().join("missing.jsonl")).is_err());
    }
}
