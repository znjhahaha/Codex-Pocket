//! Handle-based storage. Never follow identity symlinks/reparse points, never
//! repair existing ACLs/modes, and never truncate or overwrite an existing file.
use crate::{Error, Result};
use std::{
    fs::File,
    io::{Read, Write},
    path::Path,
};

pub(crate) struct Directory(platform::Directory);
impl Directory {
    pub fn open(path: &Path) -> Result<Self> {
        platform::Directory::open(path).map(Self)
    }
    pub fn lock(&self) -> Result<File> {
        match self.0.open_file("identity.lock", false)? {
            Some(file) => Ok(file),
            None => match self.0.open_file("identity.lock", true) {
                Ok(Some(file)) => Ok(file),
                Err(Error::Io(e)) if e.kind() == std::io::ErrorKind::AlreadyExists => self
                    .0
                    .open_file("identity.lock", false)?
                    .ok_or(Error::InsecureStorage),
                other => other?.ok_or(Error::InsecureStorage),
            },
        }
    }
    pub fn read(&self, name: &str, limit: u64) -> Result<Option<Vec<u8>>> {
        let Some(file) = self.0.open_file(name, false)? else {
            return Ok(None);
        };
        if file.metadata()?.len() > limit {
            return Err(Error::InvalidIdentity);
        }
        let mut bytes = Vec::new();
        file.take(limit + 1).read_to_end(&mut bytes)?;
        if bytes.is_empty() || bytes.len() as u64 > limit {
            return Err(Error::InvalidIdentity);
        }
        Ok(Some(bytes))
    }
    pub fn write_new(&self, name: &str, bytes: &[u8]) -> Result<()> {
        let mut file = self
            .0
            .open_file(name, true)?
            .ok_or(Error::InsecureStorage)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        Ok(())
    }
    pub fn sync(&self) -> Result<()> {
        self.0.sync()
    }
}

#[cfg(unix)]
mod platform {
    use super::*;
    use std::{
        ffi::CString,
        os::{
            fd::{AsRawFd, FromRawFd},
            unix::fs::MetadataExt,
        },
        path::Component,
    };

    pub struct Directory(File);
    impl Directory {
        pub fn open(path: &Path) -> Result<Self> {
            let path = std::path::absolute(path)?;
            let mut components = path.components().peekable();
            let mut directory = unsafe {
                libc::open(
                    c"/".as_ptr(),
                    libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
                )
            };
            if directory < 0 {
                return Err(std::io::Error::last_os_error().into());
            }
            let mut parent = unsafe { File::from_raw_fd(directory) };
            while let Some(component) = components.next() {
                let Component::Normal(name) = component else {
                    if component == Component::RootDir || component == Component::CurDir {
                        continue;
                    }
                    return Err(Error::InsecureStorage);
                };
                use std::os::unix::ffi::OsStrExt;
                let name = CString::new(name.as_bytes()).map_err(|_| Error::InsecureStorage)?;
                let final_component = components.peek().is_none();
                if final_component {
                    let created =
                        unsafe { libc::mkdirat(parent.as_raw_fd(), name.as_ptr(), 0o700) };
                    if created < 0 {
                        let e = std::io::Error::last_os_error();
                        if e.kind() != std::io::ErrorKind::AlreadyExists {
                            return Err(e.into());
                        }
                    }
                }
                directory = unsafe {
                    libc::openat(
                        parent.as_raw_fd(),
                        name.as_ptr(),
                        libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                    )
                };
                if directory < 0 {
                    return Err(std::io::Error::last_os_error().into());
                }
                parent = unsafe { File::from_raw_fd(directory) };
            }
            private(&parent, true)?;
            Ok(Self(parent))
        }
        pub fn open_file(&self, name: &str, create: bool) -> Result<Option<File>> {
            let name = CString::new(name).map_err(|_| Error::InsecureStorage)?;
            let flags = libc::O_RDWR
                | libc::O_NOFOLLOW
                | libc::O_CLOEXEC
                | libc::O_NONBLOCK
                | if create {
                    libc::O_CREAT | libc::O_EXCL
                } else {
                    0
                };
            let fd = unsafe { libc::openat(self.0.as_raw_fd(), name.as_ptr(), flags, 0o600) };
            if fd < 0 {
                let e = std::io::Error::last_os_error();
                if !create && e.kind() == std::io::ErrorKind::NotFound {
                    return Ok(None);
                }
                return Err(e.into());
            }
            let file = unsafe { File::from_raw_fd(fd) };
            private(&file, false)?;
            Ok(Some(file))
        }
        pub fn sync(&self) -> Result<()> {
            self.0.sync_all().map_err(Into::into)
        }
    }
    fn private(file: &File, directory: bool) -> Result<()> {
        let metadata = file.metadata()?;
        if metadata.uid() != unsafe { libc::geteuid() }
            || metadata.mode() & 0o077 != 0
            || (directory && !metadata.is_dir())
            || (!directory && (!metadata.is_file() || metadata.nlink() != 1))
        {
            return Err(Error::InsecureStorage);
        }
        Ok(())
    }
}

#[cfg(windows)]
mod platform {
    use super::*;
    use std::{
        ffi::{OsStr, c_void},
        os::windows::{
            ffi::OsStrExt,
            io::{AsRawHandle, FromRawHandle},
        },
        path::{Component, PathBuf},
        ptr::{null, null_mut},
    };
    use windows_sys::Win32::{
        Foundation::*,
        Security::{Authorization::*, *},
        Storage::FileSystem::*,
        System::Threading::*,
    };

    struct Descriptor(PSECURITY_DESCRIPTOR);
    impl Drop for Descriptor {
        fn drop(&mut self) {
            unsafe {
                LocalFree(self.0);
            }
        }
    }

    struct User {
        token: windows_sys::Win32::Foundation::HANDLE,
        data: Vec<usize>,
    }
    impl User {
        fn current() -> Result<Self> {
            unsafe {
                let mut token = null_mut();
                if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
                    return Err(std::io::Error::last_os_error().into());
                }
                let mut size = 0;
                GetTokenInformation(token, TokenUser, null_mut(), 0, &mut size);
                let mut user = Self {
                    token,
                    data: vec![0; (size as usize).div_ceil(std::mem::size_of::<usize>())],
                };
                if GetTokenInformation(
                    token,
                    TokenUser,
                    user.data.as_mut_ptr().cast(),
                    size,
                    &mut size,
                ) == 0
                {
                    return Err(std::io::Error::last_os_error().into());
                }
                Ok(user)
            }
        }
        fn sid(&self) -> PSID {
            unsafe { (*(self.data.as_ptr().cast::<TOKEN_USER>())).User.Sid }
        }
        fn descriptor(&self) -> Result<Descriptor> {
            unsafe {
                let mut sid_string = null_mut();
                if ConvertSidToStringSidW(self.sid(), &mut sid_string) == 0 {
                    return Err(std::io::Error::last_os_error().into());
                }
                let mut length = 0;
                while *sid_string.add(length) != 0 {
                    length += 1;
                }
                let sid = String::from_utf16(std::slice::from_raw_parts(sid_string, length))
                    .map_err(|_| Error::InsecureStorage);
                LocalFree(sid_string.cast());
                let sid = sid?;
                // Protected DACL, exactly one allow ACE for the process user.
                let sddl = wide(OsStr::new(&format!("O:{sid}D:P(A;;FA;;;{sid})")))?;
                let mut descriptor = null_mut();
                if ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    sddl.as_ptr(),
                    SDDL_REVISION_1,
                    &mut descriptor,
                    null_mut(),
                ) == 0
                {
                    return Err(std::io::Error::last_os_error().into());
                }
                Ok(Descriptor(descriptor))
            }
        }
    }
    impl Drop for User {
        fn drop(&mut self) {
            unsafe {
                CloseHandle(self.token);
            }
        }
    }

    pub struct Directory {
        path: PathBuf,
        _ancestors: Vec<File>,
        user: User,
    }
    impl Directory {
        pub fn open(path: &Path) -> Result<Self> {
            let path = std::path::absolute(path)?;
            let user = User::current()?;
            let descriptor = user.descriptor()?;
            let attributes = SECURITY_ATTRIBUTES {
                nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
                lpSecurityDescriptor: descriptor.0,
                bInheritHandle: 0,
            };
            let mut current = PathBuf::new();
            let mut ancestors = Vec::new();
            let mut components = path.components().peekable();
            while let Some(component) = components.next() {
                match component {
                    Component::ParentDir => return Err(Error::InsecureStorage),
                    Component::CurDir => continue,
                    Component::Prefix(_) => {
                        current.push(component.as_os_str());
                        continue;
                    }
                    _ => current.push(component.as_os_str()),
                }
                let final_component = components.peek().is_none();
                let name = wide(current.as_os_str())?;
                if final_component && unsafe { CreateDirectoryW(name.as_ptr(), &attributes) } == 0 {
                    let e = std::io::Error::last_os_error();
                    if e.raw_os_error() != Some(ERROR_ALREADY_EXISTS as i32) {
                        return Err(e.into());
                    }
                }
                let handle = unsafe {
                    CreateFileW(
                        name.as_ptr(),
                        FILE_READ_ATTRIBUTES | READ_CONTROL,
                        FILE_SHARE_READ | FILE_SHARE_WRITE,
                        null(),
                        OPEN_EXISTING,
                        FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
                        null_mut(),
                    )
                };
                if handle == INVALID_HANDLE_VALUE {
                    return Err(std::io::Error::last_os_error().into());
                }
                let file = unsafe { File::from_raw_handle(handle) };
                check_type(&file, true)?;
                if final_component {
                    private(&file, &user)?;
                }
                // Deny delete sharing on every ancestor, so all path components
                // stay pinned until file creation/loading has finished.
                ancestors.push(file);
            }
            if ancestors.is_empty() {
                return Err(Error::InsecureStorage);
            }
            Ok(Self {
                path,
                _ancestors: ancestors,
                user,
            })
        }
        pub fn open_file(&self, name: &str, create: bool) -> Result<Option<File>> {
            let name = wide(self.path.join(name).as_os_str())?;
            let descriptor = self.user.descriptor()?;
            let attributes = SECURITY_ATTRIBUTES {
                nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
                lpSecurityDescriptor: descriptor.0,
                bInheritHandle: 0,
            };
            let handle = unsafe {
                CreateFileW(
                    name.as_ptr(),
                    GENERIC_READ | GENERIC_WRITE | READ_CONTROL,
                    FILE_SHARE_READ | FILE_SHARE_WRITE,
                    &attributes,
                    if create { CREATE_NEW } else { OPEN_EXISTING },
                    FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT,
                    null_mut(),
                )
            };
            if handle == INVALID_HANDLE_VALUE {
                let e = std::io::Error::last_os_error();
                if !create && e.kind() == std::io::ErrorKind::NotFound {
                    return Ok(None);
                }
                return Err(e.into());
            }
            let file = unsafe { File::from_raw_handle(handle) };
            check_type(&file, false)?;
            private(&file, &self.user)?;
            Ok(Some(file))
        }
        pub fn sync(&self) -> Result<()> {
            Ok(())
        } // Each new file is FlushFileBuffers'd via sync_all.
    }
    fn wide(value: &OsStr) -> Result<Vec<u16>> {
        let mut result: Vec<_> = value.encode_wide().collect();
        if result.contains(&0) {
            return Err(Error::InsecureStorage);
        }
        result.push(0);
        Ok(result)
    }
    fn check_type(file: &File, directory: bool) -> Result<()> {
        unsafe {
            let mut information: BY_HANDLE_FILE_INFORMATION = std::mem::zeroed();
            if GetFileInformationByHandle(file.as_raw_handle(), &mut information) == 0 {
                return Err(std::io::Error::last_os_error().into());
            }
            if information.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT != 0
                || (information.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY != 0) != directory
                || (!directory && information.nNumberOfLinks != 1)
            {
                return Err(Error::InsecureStorage);
            }
            Ok(())
        }
    }
    fn private(file: &File, user: &User) -> Result<()> {
        unsafe {
            let mut owner: PSID = null_mut();
            let mut acl: *mut ACL = null_mut();
            let mut raw = null_mut();
            let status = GetSecurityInfo(
                file.as_raw_handle(),
                SE_FILE_OBJECT,
                OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
                &mut owner,
                null_mut(),
                &mut acl,
                null_mut(),
                &mut raw,
            );
            if status != ERROR_SUCCESS {
                return Err(std::io::Error::from_raw_os_error(status as i32).into());
            }
            let _descriptor = Descriptor(raw);
            let mut control = 0;
            let mut revision = 0;
            if GetSecurityDescriptorControl(raw, &mut control, &mut revision) == 0
                || control & SE_DACL_PROTECTED == 0
                || owner.is_null()
                || EqualSid(owner, user.sid()) == 0
                || acl.is_null()
                || (*acl).AceCount != 1
            {
                return Err(Error::InsecureStorage);
            }
            let mut ace: *mut c_void = null_mut();
            if GetAce(acl, 0, &mut ace) == 0 {
                return Err(Error::InsecureStorage);
            }
            let ace = &*(ace.cast::<ACCESS_ALLOWED_ACE>());
            // ACCESS_ALLOWED_ACE_TYPE is defined as zero by the Windows ABI.
            if ace.Header.AceType != 0
                || ace.Header.AceFlags != 0
                || ace.Mask != FILE_ALL_ACCESS
                || EqualSid((&ace.SidStart as *const u32).cast_mut().cast(), user.sid()) == 0
            {
                return Err(Error::InsecureStorage);
            }
            Ok(())
        }
    }
}

#[cfg(not(any(unix, windows)))]
compile_error!("pocket-tunnel identity storage requires Unix permissions or Windows ACLs");
