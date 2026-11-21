//! Local, read-only evidence of an official Windows desktop process. It neither
//! contacts the broker nor claims access to the process's conversations.
use serde::Serialize;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Observation {
    version: String,
    observed_at: u64,
}

pub fn observe() -> Option<Observation> {
    #[cfg(windows)]
    {
        let version = windows::version()?;
        Some(Observation {
            version,
            observed_at: crate::storage::now(),
        })
    }
    #[cfg(not(windows))]
    None
}

#[cfg(any(test, windows))]
fn desktop_version(full_name: &str) -> Option<[u16; 4]> {
    let mut fields = full_name.split('_');
    if fields.next()? != "OpenAI.Codex" {
        return None;
    }
    let version = fields.next()?;
    if !matches!(fields.next()?, "x64" | "arm64" | "x86")
        || fields.next()? != ""
        || fields.next()? != "2p2nqsd0c76g0"
        || fields.next().is_some()
    {
        return None;
    }
    let mut parts = version.split('.');
    let mut result = [0; 4];
    for i in &mut result {
        let part = parts.next()?;
        if !part.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        *i = part.parse().ok()?;
    }
    parts.next().is_none().then_some(result)
}

#[cfg(windows)]
mod windows {
    use super::desktop_version;
    use crate::supervisor::maintenance::ProcessIdentity;
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
    use windows_sys::Win32::{
        Foundation::{ERROR_SUCCESS, INVALID_HANDLE_VALUE, WAIT_TIMEOUT},
        Storage::Packaging::Appx::GetPackageFullName,
        System::{Diagnostics::ToolHelp::*, Threading::*},
    };

    fn is_desktop_exe(name: &str) -> bool {
        name.eq_ignore_ascii_case("ChatGPT.exe") || name.eq_ignore_ascii_case("Codex.exe")
    }

    fn identity_version(pid: u32) -> Option<[u16; 4]> {
        // This checks the owner SID as well as the process identity. Titles,
        // filenames or a registered but stopped package are not evidence.
        let identity = ProcessIdentity::capture(pid).ok()?;
        if !is_desktop_exe(identity.executable.file_name()?.to_str()?)
            || identity.executable.parent()?.file_name()?.to_str()? != "app"
        {
            return None;
        }
        let handle = unsafe {
            OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                0,
                pid,
            )
        };
        if handle.is_null() {
            return None;
        }
        let handle = unsafe { OwnedHandle::from_raw_handle(handle) };
        let mut name = [0u16; 256];
        let mut length = name.len() as u32;
        if unsafe { GetPackageFullName(handle.as_raw_handle(), &mut length, name.as_mut_ptr()) }
            != ERROR_SUCCESS
        {
            return None;
        }
        let full_name = String::from_utf16(name.get(..(length as usize).checked_sub(1)?)?).ok()?;
        let version = desktop_version(&full_name)?;
        if unsafe { WaitForSingleObject(handle.as_raw_handle(), 0) } != WAIT_TIMEOUT
            || ProcessIdentity::capture(pid).ok()? != identity
        {
            return None;
        }
        Some(version)
    }

    pub fn version() -> Option<String> {
        let handle = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) };
        if handle == INVALID_HANDLE_VALUE {
            return None;
        }
        let handle = unsafe { OwnedHandle::from_raw_handle(handle) };
        let mut row: PROCESSENTRY32W = unsafe { std::mem::zeroed() };
        row.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
        let mut has_row = unsafe { Process32FirstW(handle.as_raw_handle(), &mut row) } != 0;
        let mut version = None;
        while has_row {
            let count = row
                .szExeFile
                .iter()
                .position(|&ch| ch == 0)
                .unwrap_or(row.szExeFile.len());
            if is_desktop_exe(&String::from_utf16_lossy(&row.szExeFile[..count])) {
                version = version.max(identity_version(row.th32ProcessID));
            }
            has_row = unsafe { Process32NextW(handle.as_raw_handle(), &mut row) } != 0;
        }
        let [a, b, c, d] = version?;
        Some(format!("{a}.{b}.{c}.{d}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_an_official_non_resource_application_package_qualifies() {
        assert_eq!(
            desktop_version("OpenAI.Codex_26.1111.2228.0_x64__2p2nqsd0c76g0"),
            Some([26, 1111, 2228, 0])
        );
        for name in [
            "ChatGPT.exe",
            "OpenAI.Codex_26.1111.2228.0_x64_en-us_2p2nqsd0c76g0",
            "OpenAI.Codex_26.1111.2228.0_x64__otherpublisher",
            "OpenAI.Codex_26.1111.2228_x64__2p2nqsd0c76g0",
        ] {
            assert_eq!(desktop_version(name), None);
        }
    }

    #[cfg(windows)]
    #[test]
    #[ignore = "requires an official Codex process already running"]
    fn observes_live_official_desktop_without_an_app_server() {
        assert!(
            observe().is_some(),
            "No current-user official Codex process matched"
        );
    }
}
