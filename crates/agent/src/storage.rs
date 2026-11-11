use anyhow::{Context, Result, bail};
use std::{
    fs,
    io::Write,
    path::Path,
    sync::atomic::{AtomicU64, Ordering},
    time::{SystemTime, UNIX_EPOCH},
};
static CLOCK: AtomicU64 = AtomicU64::new(0);
pub fn now() -> u64 {
    let wall = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let mut seen = CLOCK.load(Ordering::Acquire);
    loop {
        let next = seen.max(wall);
        match CLOCK.compare_exchange_weak(seen, next, Ordering::AcqRel, Ordering::Acquire) {
            Ok(_) => return next,
            Err(value) => seen = value,
        }
    }
}
pub fn observe(value: u64) {
    let mut seen = CLOCK.load(Ordering::Acquire);
    while seen < value {
        match CLOCK.compare_exchange_weak(seen, value, Ordering::AcqRel, Ordering::Acquire) {
            Ok(_) => break,
            Err(actual) => seen = actual,
        }
    }
}
pub fn private_directory(path: &Path) -> Result<()> {
    for ancestor in path.ancestors() {
        if let Ok(metadata) = fs::symlink_metadata(ancestor) {
            if metadata.is_symlink() {
                bail!("Private state cannot use a symbolic link");
            }
            #[cfg(windows)]
            {
                use std::os::windows::fs::MetadataExt;
                if metadata.file_attributes() & 0x400 != 0 {
                    bail!("Private state cannot use a reparse point");
                }
            }
        }
    }
    fs::create_dir_all(path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use std::os::windows::process::CommandExt;
        use windows_sys::Win32::{
            Foundation::LocalFree,
            Security::{
                Authorization::{
                    ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1,
                },
                DACL_SECURITY_INFORMATION, PROTECTED_DACL_SECURITY_INFORMATION, SetFileSecurityW,
            },
        };
        let id = std::process::Command::new("whoami.exe")
            .args(["/user", "/fo", "csv", "/nh"])
            .creation_flags(0x08000000)
            .output()?;
        if !id.status.success() {
            bail!("Cannot determine current Windows user for private state");
        }
        let output = String::from_utf8(id.stdout)?;
        let sid = output
            .split(',')
            .nth(1)
            .unwrap_or_default()
            .trim()
            .trim_matches('"');
        if !sid.starts_with("S-1-")
            || !sid
                .chars()
                .all(|c| c.is_ascii_digit() || c == '-' || c == 'S')
        {
            bail!("Cannot determine current Windows user for private state");
        }
        // Replace the complete DACL: /grant:r only replaces the named trustees
        // and leaves explicit grants to Everyone or other users in place.
        let sddl: Vec<u16> = format!("D:P(A;OICI;FA;;;{sid})(A;OICI;FA;;;SY)")
            .encode_utf16()
            .chain(Some(0))
            .collect();
        let path: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        let mut descriptor = std::ptr::null_mut();
        if unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                SDDL_REVISION_1,
                &mut descriptor,
                std::ptr::null_mut(),
            )
        } == 0
        {
            return Err(std::io::Error::last_os_error().into());
        }
        let applied = unsafe {
            SetFileSecurityW(
                path.as_ptr(),
                DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                descriptor,
            )
        };
        let error = (applied == 0).then(std::io::Error::last_os_error);
        unsafe {
            LocalFree(descriptor);
        }
        if let Some(error) = error {
            return Err(error.into());
        }
    }
    Ok(())
}
pub fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().context("Missing state parent")?;
    fs::create_dir_all(parent)?;
    let mut file = tempfile::NamedTempFile::new_in(parent).context("Create state staging file")?;
    file.write_all(bytes).context("Write staged state")?;
    file.as_file().sync_all().context("Flush staged state")?;
    #[cfg(windows)]
    {
        // Rust 1.93's Windows rename uses FileRenameInfoEx/POSIX semantics when
        // legacy MoveFileEx rejects an open destination. Existing readers keep
        // their old complete snapshot while new readers see the new one. Never
        // delete/truncate the old file, or retry the business operation.
        fs::rename(file.path(), path).context("Replace state file")?;
        // Flush the same verified staging handle, now naming the replacement;
        // reopening by path would introduce another reader/writer race.
        file.as_file().sync_all().context("Flush replaced state")?;
    }
    #[cfg(not(windows))]
    {
        file.persist(path).map_err(|e| e.error)?;
    }
    #[cfg(unix)]
    {
        fs::File::open(parent)?.sync_all()?;
    }
    Ok(())
}

/// Read from the verified file handle itself. MSIX may redirect a file even
/// when its containing directory resolves to the expected physical path.
pub fn read_physical(path: &Path, limit: u64) -> Result<Vec<u8>> {
    use std::io::Read;
    let file = fs::File::open(path)?;
    if file.metadata()?.len() > limit { bail!("State file exceeds limit"); }
    verify_physical_file(path, &file)?;
    let mut bytes = vec![];
    file.take(limit + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > limit { bail!("State file exceeds limit"); }
    Ok(bytes)
}
pub fn verify_physical_file(path: &Path, file: &fs::File) -> Result<()> {
    #[cfg(not(windows))] let _ = (path, file);
    #[cfg(windows)] unsafe {
        use std::os::windows::{ffi::OsStrExt, io::AsRawHandle};
        use windows_sys::Win32::Storage::FileSystem::{GetFinalPathNameByHandleW, GetLongPathNameW};
        let mut buffer = vec![0u16; 32768];
        let count = GetFinalPathNameByHandleW(file.as_raw_handle(), buffer.as_mut_ptr(), buffer.len() as u32, 0);
        if count == 0 { return Err(std::io::Error::last_os_error().into()); }
        if count as usize >= buffer.len() { bail!("Physical state path exceeds limit"); }
        let actual = String::from_utf16_lossy(&buffer[..count as usize]);
        // TEMP on Windows CI (and some real installations) uses an 8.3 alias.
        // Expand names only: canonicalize would follow a redirected/reparse path
        // and make the security comparison tautological.
        let absolute = std::path::absolute(path)?;
        let name: Vec<u16> = absolute.as_os_str().encode_wide().chain(Some(0)).collect();
        let count = GetLongPathNameW(name.as_ptr(), buffer.as_mut_ptr(), buffer.len() as u32);
        if count == 0 { return Err(std::io::Error::last_os_error().into()); }
        if count as usize >= buffer.len() { bail!("Physical state path exceeds limit"); }
        let expected = String::from_utf16_lossy(&buffer[..count as usize]).replace('/', "\\");
        let normalize = |s: &str| s.strip_prefix(r"\\?\").unwrap_or(s).to_lowercase();
        let actual = normalize(&actual);
        if (actual.contains("\\packages\\") && actual.contains("\\localcache\\"))
            || actual != normalize(&expected) { bail!("state_path_redirected"); }
    }
    Ok(())
}
pub fn secret() -> String {
    use rand::RngCore;
    let mut bytes = [0u8; 32];
    rand::rng().fill_bytes(&mut bytes);
    hex::encode(bytes)
}
pub fn config_store(path: &Path) -> Result<String> {
    let p = dunce::canonicalize(path)?;
    let s = p.to_string_lossy().into_owned();
    #[cfg(windows)]
    let s = s.to_lowercase();
    Ok(crate::auth::hash(&s))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(windows)]
    #[test]
    fn physical_read_accepts_windows_short_names_but_not_a_different_handle() {
        use std::os::windows::ffi::{OsStrExt, OsStringExt};
        use windows_sys::Win32::Storage::FileSystem::GetShortPathNameW;
        let directory = tempfile::Builder::new()
            .prefix("pocket physical path fixture ")
            .tempdir()
            .unwrap();
        let path = directory.path().join("long state name.json");
        fs::write(&path, b"preserved").unwrap();
        let input: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        let mut output = vec![0u16; 32768];
        let count =
            unsafe { GetShortPathNameW(input.as_ptr(), output.as_mut_ptr(), output.len() as u32) };
        assert!(count > 0 && (count as usize) < output.len());
        let short =
            std::path::PathBuf::from(std::ffi::OsString::from_wide(&output[..count as usize]));
        assert_eq!(read_physical(&short, 1024).unwrap(), b"preserved");
        let other = directory.path().join("other.json");
        fs::write(&other, b"wrong").unwrap();
        assert!(verify_physical_file(&path, &fs::File::open(other).unwrap()).is_err());
    }
    #[test]
    fn durable_replace_reopens_complete_state_and_keeps_no_temporary_files() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("ledger.json");
        for generation in 0..30 {
            let state = serde_json::json!({"generation":generation,"payload":"x".repeat(8192)});
            let bytes = serde_json::to_vec(&state).unwrap();
            atomic_write(&path, &bytes).unwrap();
            assert_eq!(fs::read(&path).unwrap(), bytes);
        }
        assert_eq!(fs::read_dir(directory.path()).unwrap().count(), 1);
    }
    #[test]
    fn replacement_keeps_an_existing_reader_on_its_original_snapshot() {
        use std::io::Read;
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("observed.json");
        atomic_write(&path, b"previous").unwrap();
        let mut reader = fs::File::open(&path).unwrap();
        atomic_write(&path, b"replacement").unwrap();
        let mut before = Vec::new();
        reader.read_to_end(&mut before).unwrap();
        assert_eq!(before, b"previous");
        assert_eq!(fs::read(&path).unwrap(), b"replacement");
    }
    #[cfg(windows)]
    #[test]
    fn private_directory_replaces_explicit_other_user_grants() {
        use std::os::windows::process::CommandExt;
        let directory = tempfile::tempdir().unwrap();
        let grant = std::process::Command::new("icacls.exe")
            .arg(directory.path())
            .args(["/grant", "*S-1-1-0:(OI)(CI)R"])
            .creation_flags(0x08000000)
            .output()
            .unwrap();
        assert!(grant.status.success());
        private_directory(directory.path()).unwrap();
        let check=std::process::Command::new("powershell.exe").args(["-NoProfile","-NonInteractive","-Command",r#"
$ErrorActionPreference='Stop'
$acl=[IO.Directory]::GetAccessControl($env:POCKET_PRIVATE_DIRECTORY)
$allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18')
if(-not $acl.AreAccessRulesProtected){exit 1}
$rules=$acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])
foreach($r in $rules){if($r.IsInherited -or $r.IdentityReference.Value -notin $allowed){exit 2}}
foreach($sid in $allowed){if(-not ($rules | Where-Object {$_.IdentityReference.Value -eq $sid -and $_.AccessControlType -eq 'Allow' -and $_.FileSystemRights -eq 'FullControl'})){exit 3}}
"#]).env("POCKET_PRIVATE_DIRECTORY",directory.path()).creation_flags(0x08000000).status().unwrap();
        assert!(
            check.success(),
            "ACL verification exit code: {:?}",
            check.code()
        );
    }
}
