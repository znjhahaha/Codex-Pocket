// Disposable state follows the same physical-path contract as an installation.
// Windows runners can expose TEMP through an 8.3 alias; do not relax the
// production MSIX redirection guard just to accommodate that test spelling.
pub fn tempdir() -> tempfile::TempDir {
    #[cfg(windows)]
    current_user_default_owner();
    let canonical = std::fs::canonicalize(std::env::temp_dir()).unwrap();
    #[cfg(windows)]
    let canonical = {
        let text = canonical.to_str().unwrap();
        std::path::PathBuf::from(text.strip_prefix(r"\\?\").unwrap_or(text))
    };
    tempfile::tempdir_in(canonical).unwrap()
}

#[cfg(windows)]
fn current_user_default_owner() {
    static INITIALIZED: std::sync::Once = std::sync::Once::new();
    INITIALIZED.call_once(|| unsafe {
        use std::{
            os::windows::io::{FromRawHandle, OwnedHandle},
            ptr,
        };
        use windows_sys::Win32::{
            Security::{
                GetTokenInformation, SetTokenInformation, TokenOwner, TokenUser,
                TOKEN_ADJUST_DEFAULT, TOKEN_OWNER, TOKEN_QUERY, TOKEN_USER,
            },
            System::Threading::{GetCurrentProcess, OpenProcessToken},
        };
        // Only this test executable's default object owner changes. Hosted
        // administrators otherwise create Administrators-owned fixtures, unlike
        // the per-user installation being tested. Privileges/elevation and the
        // product's owner validation remain unchanged, including in child probes.
        let mut token = ptr::null_mut();
        assert_ne!(
            OpenProcessToken(
                GetCurrentProcess(),
                TOKEN_QUERY | TOKEN_ADJUST_DEFAULT,
                &mut token
            ),
            0
        );
        let _owned = OwnedHandle::from_raw_handle(token);
        let mut length = 0;
        GetTokenInformation(token, TokenUser, ptr::null_mut(), 0, &mut length);
        let mut data = vec![0usize; (length as usize).div_ceil(size_of::<usize>())];
        assert_ne!(
            GetTokenInformation(
                token,
                TokenUser,
                data.as_mut_ptr().cast(),
                length,
                &mut length
            ),
            0
        );
        let owner = TOKEN_OWNER {
            Owner: (*data.as_ptr().cast::<TOKEN_USER>()).User.Sid,
        };
        assert_ne!(
            SetTokenInformation(
                token,
                TokenOwner,
                ptr::addr_of!(owner).cast(),
                size_of::<TOKEN_OWNER>() as u32
            ),
            0
        );
    });
}
