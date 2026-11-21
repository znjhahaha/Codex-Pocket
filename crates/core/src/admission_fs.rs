//! Private physical files for the cross-process admission protocol.
// Uses the same handle/owner checks as the desktop private storage implementation.
use anyhow::{Context, Result, bail};
use serde::{Serialize, de::DeserializeOwned};
use std::{
    fs::{self, File},
    io::{Read, Write},
    path::Path,
};
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DirectoryPolicyError {
    UnsafePath,
    NotDirectory,
    UnexpectedOwner,
}
impl std::fmt::Display for DirectoryPolicyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::UnsafePath => "Unsafe state directory path",
            Self::NotDirectory => "State directory is occupied by a file",
            Self::UnexpectedOwner => "Unexpected control directory owner",
        })
    }
}
impl std::error::Error for DirectoryPolicyError {}
fn plain(path: &Path) -> Result<()> {
    if !path.is_absolute() {
        return Err(DirectoryPolicyError::UnsafePath.into());
    }
    if path
        .components()
        .any(|c| matches!(c, std::path::Component::ParentDir))
    {
        return Err(DirectoryPolicyError::UnsafePath.into());
    }
    #[cfg(windows)]
    {
        use std::path::{Component, Prefix};
        if !matches!(path.components().next(),Some(Component::Prefix(p)) if matches!(p.kind(),Prefix::Disk(_)))
        {
            return Err(DirectoryPolicyError::UnsafePath.into());
        }
        if path.to_string_lossy().chars().skip(2).any(|c| c == ':') {
            return Err(DirectoryPolicyError::UnsafePath.into());
        }
    }
    for ancestor in path.ancestors() {
        if let Ok(m) = fs::symlink_metadata(ancestor) {
            #[cfg(windows)]
            {
                use std::os::windows::fs::MetadataExt;
                if m.file_attributes() & 0x400 != 0 {
                    return Err(RedirectedPath.into());
                }
            }
            if m.file_type().is_symlink() {
                return Err(RedirectedPath.into());
            }
        }
    }
    Ok(())
}
#[derive(Debug)]
pub struct RedirectedPath;
impl std::fmt::Display for RedirectedPath {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "State path is redirected by the launching process")
    }
}
impl std::error::Error for RedirectedPath {}

/// Validate the actual handle, since MSIX can redirect a file but not its parent.
pub fn validate_path(path: &Path) -> Result<()> {
    plain(path)?;
    #[cfg(windows)]
    unsafe {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::{Foundation::*, Storage::FileSystem::*};
        let mut existing = path;
        while !existing.try_exists()? {
            existing = existing.parent().context("No existing ancestor")?;
        }
        let name: Vec<u16> = existing.as_os_str().encode_wide().chain(Some(0)).collect();
        let handle = CreateFileW(
            name.as_ptr(),
            0,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            std::ptr::null(),
            OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS,
            std::ptr::null_mut(),
        );
        if handle == INVALID_HANDLE_VALUE {
            return Err(std::io::Error::last_os_error().into());
        }
        let result = verify_physical_handle(existing, handle);
        CloseHandle(handle);
        result?;
    }
    Ok(())
}

#[cfg(windows)]
unsafe fn verify_physical_handle(
    path: &Path,
    handle: windows_sys::Win32::Foundation::HANDLE,
) -> Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::*;
    let mut buffer = vec![0u16; 32768];
    let count = GetFinalPathNameByHandleW(handle, buffer.as_mut_ptr(), buffer.len() as u32, 0);
    if count == 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    if count as usize >= buffer.len() {
        bail!("Physical path exceeds limit")
    }
    let physical = String::from_utf16_lossy(&buffer[..count as usize]);
    let physical = physical.strip_prefix(r"\\?\").unwrap_or(&physical);
    // Expand 8.3 aliases without resolving a junction or MSIX redirection.
    let name: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let count = GetLongPathNameW(name.as_ptr(), buffer.as_mut_ptr(), buffer.len() as u32);
    if count == 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    if count as usize >= buffer.len() {
        bail!("Physical path exceeds limit")
    }
    let expected = String::from_utf16_lossy(&buffer[..count as usize]).replace('/', "\\");
    let lower = physical.to_lowercase();
    if (lower.contains("\\packages\\") && lower.contains("\\localcache\\"))
        || !physical.eq_ignore_ascii_case(&expected)
    {
        return Err(RedirectedPath.into());
    }
    Ok(())
}
pub fn private_dir(path: &Path) -> Result<()> {
    validate_path(path).context("Validate state directory location")?;
    #[cfg(windows)]
    private_windows_directory(path).context("Prepare private Windows state directory")?;
    #[cfg(not(windows))]
    {
        fs::create_dir_all(path)?;
        validate_path(path)?;
    }
    Ok(())
}
pub fn atomic<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    validate_path(path)?;
    let parent = path.parent().context("No parent directory")?;
    let mut f = tempfile::NamedTempFile::new_in(parent)?;
    validate_path(f.path())?;
    f.write_all(&serde_json::to_vec_pretty(value)?)?;
    f.as_file().sync_all()?;
    // Windows readers may briefly omit FILE_SHARE_DELETE. Retain the temp file
    // and retry replacement without truncating the current complete snapshot.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
    loop {
        match f.persist(path) {
            Ok(_) => return Ok(()),
            Err(error) => {
                let transient =
                    cfg!(windows) && matches!(error.error.raw_os_error(), Some(5 | 32 | 33));
                if !transient || std::time::Instant::now() >= deadline {
                    return Err(error.error.into());
                }
                f = error.file;
                std::thread::sleep(std::time::Duration::from_millis(25));
                validate_path(path)?;
            }
        }
    }
}
pub fn open_physical(path: &Path) -> Result<File> {
    validate_path(path)?;
    let file = File::open(path)?;
    verify_file(path, &file)?;
    Ok(file)
}
pub fn verify_file(path: &Path, file: &File) -> Result<()> {
    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawHandle;
        unsafe {
            verify_physical_handle(path, file.as_raw_handle())?;
        }
    }
    #[cfg(not(windows))]
    {
        let _ = file;
        validate_path(path)?;
    }
    Ok(())
}
#[cfg(windows)]
fn private_windows_directory(path: &Path) -> Result<()> {
    use std::{os::windows::ffi::OsStrExt, ptr::null_mut};
    use windows_sys::Win32::{
        Foundation::*,
        Security::{Authorization::*, *},
        Storage::FileSystem::*,
        System::Threading::*,
    };
    struct Handle(HANDLE);
    impl Drop for Handle {
        fn drop(&mut self) {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
    struct Descriptor(PSECURITY_DESCRIPTOR);
    impl Drop for Descriptor {
        fn drop(&mut self) {
            unsafe {
                LocalFree(self.0);
            }
        }
    }
    unsafe {
        let mut token = null_mut();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let token = Handle(token);
        let mut size = 0;
        GetTokenInformation(token.0, TokenUser, null_mut(), 0, &mut size);
        let mut data = vec![0usize; (size as usize).div_ceil(std::mem::size_of::<usize>())];
        if GetTokenInformation(
            token.0,
            TokenUser,
            data.as_mut_ptr().cast(),
            size,
            &mut size,
        ) == 0
        {
            return Err(std::io::Error::last_os_error().into());
        }
        let sid = (*(data.as_ptr().cast::<TOKEN_USER>())).User.Sid;
        let mut raw = null_mut();
        if ConvertSidToStringSidW(sid, &mut raw) == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let mut len = 0;
        while *raw.add(len) != 0 {
            len += 1;
        }
        let sid_text = String::from_utf16_lossy(std::slice::from_raw_parts(raw, len));
        LocalFree(raw.cast());
        // Assign the owner and protected DACL *at creation*. In particular an
        // elevated token may default to Administrators as its object owner;
        // creating with inherited permissions and then checking TokenUser
        // rejects a directory the very same operation just created.
        let sddl: Vec<u16> = format!("O:{sid_text}D:P(A;OICI;FA;;;{sid_text})")
            .encode_utf16()
            .chain(Some(0))
            .collect();
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
        let descriptor = Descriptor(descriptor);
        let (mut present, mut defaulted) = (0, 0);
        let mut dacl = null_mut();
        if GetSecurityDescriptorDacl(descriptor.0, &mut present, &mut dacl, &mut defaulted) == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let attributes = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: descriptor.0,
            bInheritHandle: 0,
        };
        let mut missing = Vec::new();
        let mut existing = path;
        loop {
            match fs::symlink_metadata(existing) {
                Ok(metadata) => {
                    if !metadata.is_dir() {
                        return Err(DirectoryPolicyError::NotDirectory.into());
                    }
                    break;
                }
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                    missing.push(existing);
                    existing = existing
                        .parent()
                        .context("No existing state directory ancestor")?;
                }
                Err(error) => return Err(error.into()),
            }
        }
        for directory in missing.into_iter().rev() {
            validate_path(directory)?;
            let name: Vec<u16> = directory.as_os_str().encode_wide().chain(Some(0)).collect();
            if CreateDirectoryW(name.as_ptr(), &attributes) == 0 {
                let error = std::io::Error::last_os_error();
                if error.raw_os_error() != Some(ERROR_ALREADY_EXISTS as i32) {
                    return Err(error.into());
                }
                // A competing creator is accepted only after the same owner,
                // directory type and physical-path checks as an existing path.
            }
        }
        plain(path)?;
        let name: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        // Keep the checked object pinned against rename/replacement while
        // reading its owner and applying its ACL. Named security APIs reopen
        // the path after validation, allowing redirection to a different object.
        let handle = CreateFileW(
            name.as_ptr(),
            READ_CONTROL | WRITE_DAC,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            std::ptr::null(),
            OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
            null_mut(),
        );
        if handle == INVALID_HANDLE_VALUE {
            return Err(std::io::Error::last_os_error().into());
        }
        let handle = Handle(handle);
        let mut information: BY_HANDLE_FILE_INFORMATION = std::mem::zeroed();
        if GetFileInformationByHandle(handle.0, &mut information) == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        if information.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
            return Err(RedirectedPath.into());
        }
        if information.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY == 0 {
            return Err(DirectoryPolicyError::NotDirectory.into());
        }
        verify_physical_handle(path, handle.0)?;
        let mut owner = null_mut();
        let mut old = null_mut();
        let result = GetSecurityInfo(
            handle.0,
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION,
            &mut owner,
            null_mut(),
            null_mut(),
            null_mut(),
            &mut old,
        );
        if result != 0 {
            return Err(std::io::Error::from_raw_os_error(result as i32).into());
        }
        let _old = Descriptor(old);
        if owner.is_null() || EqualSid(owner, sid) == 0 {
            return Err(DirectoryPolicyError::UnexpectedOwner.into());
        }
        let result = SetSecurityInfo(
            handle.0,
            SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
            null_mut(),
            null_mut(),
            dacl,
            null_mut(),
        );
        if result != 0 {
            return Err(std::io::Error::from_raw_os_error(result as i32).into());
        }
    }
    Ok(())
}

pub fn read_json<T: DeserializeOwned>(path: &Path, limit: u64) -> Result<T> {
    let file = open_physical(path)?;
    if file.metadata()?.len() > limit {
        bail!("Admission file exceeds limit");
    }
    let mut bytes = Vec::new();
    file.take(limit + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > limit {
        bail!("Admission file exceeds limit");
    }
    Ok(serde_json::from_slice(&bytes)?)
}
