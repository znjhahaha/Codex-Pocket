//! Legacy Tauri dashboards already honor Restart Manager's session shutdown.
use super::*;
use std::ptr::{null};
use windows_sys::Win32::{Foundation::{FILETIME, HWND, LPARAM, ERROR_SUCCESS}, System::{RemoteDesktop::ProcessIdToSessionId, RestartManager::*}, UI::WindowsAndMessaging::*};

#[derive(Default)]
struct Windows(Vec<(u32, bool)>);
unsafe extern "system" fn collect(hwnd: HWND, data: LPARAM) -> i32 {
    let mut class = [0u16; 64];
    let count = unsafe { GetClassNameW(hwnd, class.as_mut_ptr(), class.len() as i32) };
    if count > 0 && String::from_utf16_lossy(&class[..count as usize]) == "Tauri Window" {
        let mut pid = 0;
        unsafe { GetWindowThreadProcessId(hwnd, &mut pid); }
        unsafe { &mut *(data as *mut Windows) }.0.push((pid, unsafe { IsWindowVisible(hwnd) } != 0));
    }
    1
}
fn owners(root: &Path) -> Result<Vec<(ProcessIdentity, bool)>> {
    let mut rows = Windows::default();
    ensure!(unsafe { EnumWindows(Some(collect), &mut rows as *mut Windows as LPARAM) } != 0, "Cannot enumerate UI windows");
    let mut out = Vec::new();
    for (pid, visible) in rows.0 {
        let Ok(identity) = ProcessIdentity::capture(pid) else { continue };
        if identity.executable.file_name().is_some_and(|s| s == "pocket-desktop.exe") && identity.executable.parent().and_then(Path::parent) == Some(root.join("bin").as_path()) {
            if !out.iter().any(|(owner, _)| owner == &identity) { out.push((identity, visible)); }
        }
    }
    Ok(out)
}
fn win(result: u32) -> Result<()> {
    if result != ERROR_SUCCESS { return Err(std::io::Error::from_raw_os_error(result as i32).into()); }
    Ok(())
}
fn session(pid: u32) -> Result<u32> {
    let mut session = 0;
    ensure!(unsafe { ProcessIdToSessionId(pid, &mut session) } != 0, "Cannot verify UI session");
    Ok(session)
}
struct RestartSession(u32);
impl Drop for RestartSession { fn drop(&mut self) { unsafe { RmEndSession(self.0); } } }
fn close_old(root: &Path, owner: &ProcessIdentity) -> Result<()> {
    ensure!(session(owner.pid)? == session(std::process::id())? && old_ui(root, owner.pid)? == *owner, "UI identity changed");
    let p = RM_UNIQUE_PROCESS { dwProcessId: owner.pid, ProcessStartTime: FILETIME { dwLowDateTime: owner.created_at as u32, dwHighDateTime: (owner.created_at >> 32) as u32 } };
    let mut key = [0u16; CCH_RM_SESSION_KEY as usize + 1];
    let mut handle = 0;
    unsafe {
        win(RmStartSession(&mut handle, 0, key.as_mut_ptr()))?;
        let session = RestartSession(handle);
        win(RmRegisterResources(session.0, 0, null(), 1, &p, 0, null()))?;
        let mut apps = [RM_PROCESS_INFO::default()];
        let (mut needed, mut count, mut reboot) = (0, 1, 0);
        win(RmGetList(session.0, &mut needed, &mut count, apps.as_mut_ptr(), &mut reboot))?;
        let listed = &apps[0].Process;
        ensure!(reboot == 0 && count == 1 && listed.dwProcessId == p.dwProcessId && listed.ProcessStartTime.dwLowDateTime == p.ProcessStartTime.dwLowDateTime && listed.ProcessStartTime.dwHighDateTime == p.ProcessStartTime.dwHighDateTime, "Unexpected Restart Manager scope");
        win(RmShutdown(session.0, 0, None))?;
    }
    wait_for_exit(owner, Duration::from_secs(10), maintenance::presence)
}
fn await_window(root: &Path, exe: &Path, child: &mut Child) -> Result<()> {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        if owners(root)?.iter().any(|(owner, _)| owner.executable == exe) { return Ok(()); }
        ensure!(child.try_wait()?.is_none(), "Selected UI exited before creating a window");
        ensure!(Instant::now() < deadline, "Selected UI did not create a window");
        std::thread::sleep(Duration::from_millis(100));
    }
}
pub(super) fn open(root: &Path, state: &Path, exe: &Path, existing_only: bool) -> Result<()> {
    replace(root,state,exe,existing_only,false)
}
pub(super) fn restart(root: &Path, state: &Path, exe: &Path) -> Result<()> {
    replace(root,state,exe,false,true)
}
fn replace(root: &Path, state: &Path, exe: &Path, existing_only: bool, force: bool) -> Result<()> {
    let dir = crate::storage::control(state);
    crate::storage::private_dir(&dir)?;
    let deadline = Instant::now() + Duration::from_secs(30);
    let _guard = loop {
        match crate::storage::Lock::acquire(&dir.join("ui-handoff.lock")) {
            Ok(lock) => break lock,
            Err(e) if super::super::local_update::lock_contended(&e) && Instant::now() < deadline => std::thread::sleep(Duration::from_millis(100)),
            Err(e) => return Err(e),
        }
    };
    let windows = owners(root)?;
    ensure!(windows.len() <= 1, "Multiple Pocket dashboards own this installation");
    let mut visible = !existing_only;
    let mut previous = None;
    if let Some((owner, was_visible)) = windows.first() {
        if owner.executable == exe && !force {
            if !existing_only { spawn(root, state, exe, true)?; }
            return Ok(());
        }
        visible |= *was_visible;
        close_old(root, owner)?;
        previous = Some(&owner.executable);
    } else if existing_only { return Ok(()); }
    let result = spawn(root, state, exe, visible).and_then(|mut child| await_window(root, exe, &mut child));
    if result.is_err() {
        if let Some(old) = previous { let mut restored = spawn(root, state, old, visible)?; await_window(root, old, &mut restored)?; }
    }
    result
}
