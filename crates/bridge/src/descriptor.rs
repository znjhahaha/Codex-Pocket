//! Publish bearer credentials only after restricting their directory and file.
use std::{
    fs,
    io::{self, Write},
    path::Path,
};

pub fn publish(directory: &Path, path: &Path, bytes: &[u8]) -> io::Result<()> {
    fs::create_dir_all(directory)?;
    let temporary = path.with_extension("tmp");
    let result = (|| {
        let mut file = private_file(directory, &temporary)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

#[cfg(unix)]
fn private_file(directory: &Path, path: &Path) -> io::Result<fs::File> {
    use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
    fs::set_permissions(directory, fs::Permissions::from_mode(0o700))?;
    fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
}

#[cfg(windows)]
fn private_file(directory: &Path, path: &Path) -> io::Result<fs::File> {
    windows::create(directory, path)
}

#[cfg(windows)]
mod windows {
    use super::*;
    use std::{
        ffi::c_void,
        os::windows::{ffi::OsStrExt, io::FromRawHandle},
        ptr,
    };
    type Handle = *mut c_void;
    #[repr(C)]
    struct SecurityAttributes {
        length: u32,
        descriptor: *mut c_void,
        inherit: i32,
    }
    #[link(name = "advapi32")]
    unsafe extern "system" {
        fn OpenProcessToken(process: Handle, access: u32, token: *mut Handle) -> i32;
        fn GetTokenInformation(
            token: Handle,
            class: u32,
            buffer: *mut c_void,
            size: u32,
            needed: *mut u32,
        ) -> i32;
        fn ConvertSidToStringSidW(sid: *mut c_void, string: *mut *mut u16) -> i32;
        fn ConvertStringSecurityDescriptorToSecurityDescriptorW(
            string: *const u16,
            revision: u32,
            descriptor: *mut *mut c_void,
            size: *mut u32,
        ) -> i32;
        fn SetFileSecurityW(path: *const u16, information: u32, descriptor: *mut c_void) -> i32;
    }
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetCurrentProcess() -> Handle;
        fn CloseHandle(handle: Handle) -> i32;
        fn LocalFree(memory: *mut c_void) -> *mut c_void;
        fn CreateFileW(
            path: *const u16,
            access: u32,
            sharing: u32,
            security: *const SecurityAttributes,
            creation: u32,
            flags: u32,
            template: Handle,
        ) -> Handle;
    }
    struct OwnedHandle(Handle);
    impl Drop for OwnedHandle {
        fn drop(&mut self) {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
    struct Local(*mut c_void);
    impl Drop for Local {
        fn drop(&mut self) {
            unsafe {
                LocalFree(self.0);
            }
        }
    }
    fn wide(path: &Path) -> io::Result<Vec<u16>> {
        let mut value: Vec<u16> = path.as_os_str().encode_wide().collect();
        if value.contains(&0) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "NUL in descriptor path",
            ));
        }
        value.push(0);
        Ok(value)
    }
    pub fn create(directory: &Path, path: &Path) -> io::Result<fs::File> {
        let directory = wide(directory)?;
        let path = wide(path)?;
        // All pointers below are live for the associated Win32 call. Allocations
        // returned by Advapi32 use LocalFree; token/file handles use CloseHandle.
        unsafe {
            let mut token = ptr::null_mut();
            if OpenProcessToken(GetCurrentProcess(), 0x0008, &mut token) == 0 {
                return Err(io::Error::last_os_error());
            }
            let token = OwnedHandle(token);
            let mut size = 0;
            GetTokenInformation(token.0, 1, ptr::null_mut(), 0, &mut size);
            if size == 0 {
                return Err(io::Error::last_os_error());
            }
            let mut buffer = vec![0usize; (size as usize).div_ceil(size_of::<usize>())];
            if GetTokenInformation(token.0, 1, buffer.as_mut_ptr().cast(), size, &mut size) == 0 {
                return Err(io::Error::last_os_error());
            }
            // TOKEN_USER begins with SID_AND_ATTRIBUTES, whose first field is PSID.
            let sid = ptr::read(buffer.as_ptr().cast::<*mut c_void>());
            let mut sid_text = ptr::null_mut();
            if ConvertSidToStringSidW(sid, &mut sid_text) == 0 {
                return Err(io::Error::last_os_error());
            }
            let sid_memory = Local(sid_text.cast());
            let mut len = 0;
            while *sid_text.add(len) != 0 {
                len += 1;
            }
            let sid = String::from_utf16(std::slice::from_raw_parts(sid_text, len))
                .map_err(|_| io::Error::other("Invalid Windows SID"))?;
            drop(sid_memory);
            // Protected DACL; only this user and LocalSystem can access it. The
            // directory ACEs also secure any files another same-user helper adds.
            let sddl: Vec<u16> = format!("D:P(A;OICI;FA;;;{sid})(A;OICI;FA;;;SY)")
                .encode_utf16()
                .chain([0])
                .collect();
            let mut descriptor = ptr::null_mut();
            if ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                1,
                &mut descriptor,
                ptr::null_mut(),
            ) == 0
            {
                return Err(io::Error::last_os_error());
            }
            let descriptor = Local(descriptor);
            if SetFileSecurityW(directory.as_ptr(), 0x00000004 | 0x80000000, descriptor.0) == 0 {
                return Err(io::Error::last_os_error());
            }
            let attributes = SecurityAttributes {
                length: size_of::<SecurityAttributes>() as u32,
                descriptor: descriptor.0,
                inherit: 0,
            };
            // CREATE_NEW prevents an existing path from being followed/replaced.
            // The protected ACL applies at creation, before any token bytes exist.
            let handle = CreateFileW(
                path.as_ptr(),
                0x40000000,
                0,
                &attributes,
                1,
                0x80,
                ptr::null_mut(),
            );
            if handle as isize == -1 {
                return Err(io::Error::last_os_error());
            }
            Ok(fs::File::from_raw_handle(handle))
        }
    }
}
