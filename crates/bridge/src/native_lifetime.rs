//! The desktop owns this proxy and its stdio app-server. Pocket's updater
//! does not own either process and must leave this job running.

#[cfg(windows)]
pub fn bind_to_proxy() -> anyhow::Result<()> {
    use anyhow::{ensure, Context};
    use std::os::windows::io::{AsRawHandle, FromRawHandle, IntoRawHandle, OwnedHandle};
    use windows_sys::Win32::System::{JobObjects::*, Threading::GetCurrentProcess};
    unsafe {
        let raw=CreateJobObjectW(std::ptr::null(),std::ptr::null());
        ensure!(!raw.is_null(),"Cannot create native process lifetime: {}",std::io::Error::last_os_error());
        let job=OwnedHandle::from_raw_handle(raw);
        let mut info:JOBOBJECT_EXTENDED_LIMIT_INFORMATION=std::mem::zeroed();
        // Explicit native sandbox/tool breakaway keeps its original meaning.
        // Our app-server launch itself never requests breakaway.
        info.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE|JOB_OBJECT_LIMIT_BREAKAWAY_OK;
        if SetInformationJobObject(job.as_raw_handle(),JobObjectExtendedLimitInformation,
            &info as *const _ as _,std::mem::size_of_val(&info) as u32)==0 {
            return Err(std::io::Error::last_os_error()).context("Cannot configure native process lifetime");
        }
        // Join BEFORE spawning: assigning a child after spawn leaves a crash
        // window. Descendants inherit this job atomically at process creation.
        if AssignProcessToJobObject(job.as_raw_handle(),GetCurrentProcess())==0 {
            return Err(std::io::Error::last_os_error()).context("Cannot bind native process lifetime");
        }
        // The handle is non-inheritable and owned for the entire proxy process.
        // Windows closes it even on TerminateProcess; a Rust Drop is insufficient.
        // Closing it here would also terminate this proxy before returning its
        // native exit code, so the OS is the sole final owner of this handle.
        let _process_lifetime_handle=job.into_raw_handle();
    }
    Ok(())
}

#[cfg(not(windows))]
pub fn bind_to_proxy() -> anyhow::Result<()> { Ok(()) }
