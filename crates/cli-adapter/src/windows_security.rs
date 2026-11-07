// Read-only token helpers adapted from openai/codex rust-v0.164.0 (Apache-2.0).
use std::{
    io,
    os::windows::io::{FromRawHandle, OwnedHandle},
    ptr,
};
use windows_sys::Win32::{
    Foundation::HANDLE,
    Security::{GetTokenInformation, TOKEN_QUERY, TokenUser},
    System::Threading::{GetCurrentProcess, OpenProcessToken},
};
// Keep the pointer-aligned buffer alive while using its TOKEN_USER SID.
pub(super) fn current_user() -> io::Result<Vec<usize>> {
    // Token information contains pointers, so allocate pointer-aligned storage.
    let mut token = ptr::null_mut();
    if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
        return Err(io::Error::last_os_error());
    }
    let _token = unsafe { OwnedHandle::from_raw_handle(token) };
    token_user(token)
}

pub(super) fn token_user(token: HANDLE) -> io::Result<Vec<usize>> {
    let mut length = 0;
    unsafe {
        GetTokenInformation(
            token,
            TokenUser,
            ptr::null_mut(),
            /*tokeninformationlength*/ 0,
            &mut length,
        )
    };
    let mut user = vec![0usize; (length as usize).div_ceil(std::mem::size_of::<usize>())];
    if unsafe {
        GetTokenInformation(
            token,
            TokenUser,
            user.as_mut_ptr().cast(),
            length,
            &mut length,
        )
    } == 0
    {
        return Err(io::Error::last_os_error());
    }
    Ok(user)
}
