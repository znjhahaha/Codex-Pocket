//! Persistable native engine identity. Transport/descriptor loss is not exit evidence.
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct NativeProcess {
    pub pid: u32,
    /// Windows creation FILETIME; protects against PID reuse across broker restarts.
    pub created_at: u64,
}

impl NativeProcess {
    pub fn capture(pid: u32) -> std::io::Result<Self> {
        platform::capture(pid)
    }
    pub fn terminated(&self) -> bool {
        self.pid != 0 && self.created_at != 0 && platform::terminated(self)
    }
}

#[cfg(windows)]
mod platform {
    use super::NativeProcess;
    use std::{
        io,
        os::windows::io::{FromRawHandle, OwnedHandle},
    };
    use windows_sys::Win32::{
        Foundation::{ERROR_INVALID_PARAMETER, FILETIME, WAIT_OBJECT_0, WAIT_TIMEOUT},
        System::Threading::{
            GetProcessTimes, OpenProcess, WaitForSingleObject, PROCESS_QUERY_LIMITED_INFORMATION,
            PROCESS_SYNCHRONIZE,
        },
    };

    pub fn capture(pid: u32) -> io::Result<NativeProcess> {
        let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
        if handle.is_null() {
            return Err(io::Error::last_os_error());
        }
        let _owned = unsafe { OwnedHandle::from_raw_handle(handle) };
        identity(handle, pid)
    }
    fn identity(
        handle: windows_sys::Win32::Foundation::HANDLE,
        pid: u32,
    ) -> io::Result<NativeProcess> {
        let mut created: FILETIME = unsafe { std::mem::zeroed() };
        let mut exit = created;
        let mut kernel = created;
        let mut user = created;
        if unsafe { GetProcessTimes(handle, &mut created, &mut exit, &mut kernel, &mut user) } == 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(NativeProcess {
            pid,
            created_at: (u64::from(created.dwHighDateTime) << 32)
                | u64::from(created.dwLowDateTime),
        })
    }
    pub fn terminated(expected: &NativeProcess) -> bool {
        let handle = unsafe {
            OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                0,
                expected.pid,
            )
        };
        if handle.is_null() {
            // Only "no such process" is release evidence. Access denied and all
            // other query failures leave ownership uncertain.
            return io::Error::last_os_error().raw_os_error()
                == Some(ERROR_INVALID_PARAMETER as i32);
        }
        let _owned = unsafe { OwnedHandle::from_raw_handle(handle) };
        match unsafe { WaitForSingleObject(handle, 0) } {
            WAIT_OBJECT_0 => true,
            WAIT_TIMEOUT => identity(handle, expected.pid).is_ok_and(|actual| actual != *expected),
            _ => false,
        }
    }
}

#[cfg(not(windows))]
mod platform {
    pub fn capture(_: u32) -> std::io::Result<super::NativeProcess> {
        Err(std::io::ErrorKind::Unsupported.into())
    }
    pub fn terminated(_: &super::NativeProcess) -> bool {
        false
    }
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    #[test]
    fn identity_distinguishes_live_process_from_reused_pid() {
        let current = NativeProcess::capture(std::process::id()).unwrap();
        assert!(!current.terminated());
        let old = NativeProcess {
            created_at: current.created_at - 1,
            ..current
        };
        assert!(
            old.terminated(),
            "a different creation time proves that the previous PID identity ended"
        );
    }
    #[test]
    fn invalid_or_unverifiable_identity_never_proves_termination() {
        assert!(!NativeProcess {
            pid: 0,
            created_at: 0
        }
        .terminated());
        assert!(!NativeProcess {
            pid: std::process::id(),
            created_at: 0
        }
        .terminated());
        // System is protected; inability to query it must not count as exit.
        assert!(!NativeProcess {
            pid: 4,
            created_at: 1
        }
        .terminated());
    }
}
