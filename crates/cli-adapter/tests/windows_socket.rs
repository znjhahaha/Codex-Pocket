#![cfg(windows)]
use codex_cli_adapter::{Availability, Discovery, Limits, connect};
use serde_json::json;
use std::{fs, io, path::Path, time::Duration};
mod common;

// Hosted Windows runners may be elevated. The production adapter intentionally
// rejects that context; exercise the rejection instead of bypassing the check.
fn current_process_is_elevated() -> bool {
    use std::ptr;
    use windows_sys::Win32::{
        Foundation::CloseHandle,
        Security::{GetTokenInformation, TOKEN_ELEVATION, TOKEN_QUERY, TokenElevation},
        System::Threading::{GetCurrentProcess, OpenProcessToken},
    };
    unsafe {
        let mut token = ptr::null_mut();
        assert_ne!(
            OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token),
            0
        );
        let mut elevation: TOKEN_ELEVATION = std::mem::zeroed();
        let mut returned = 0;
        let result = GetTokenInformation(
            token,
            TokenElevation,
            ptr::addr_of_mut!(elevation).cast(),
            size_of::<TOKEN_ELEVATION>() as u32,
            &mut returned,
        );
        CloseHandle(token);
        assert_ne!(result, 0);
        elevation.TokenIsElevated != 0
    }
}

// Fixture-only directory creation. Production adapter never changes ACLs.
fn private_directory(path: &Path) {
    use std::{os::windows::ffi::OsStrExt, ptr};
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::{
            Authorization::{
                ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
            },
            GetTokenInformation, SECURITY_ATTRIBUTES, TOKEN_QUERY, TOKEN_USER, TokenUser,
        },
        Storage::FileSystem::CreateDirectoryW,
        System::Threading::{GetCurrentProcess, OpenProcessToken},
    };
    unsafe {
        let mut token = ptr::null_mut();
        assert_ne!(
            OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token),
            0
        );
        let mut length = 0;
        GetTokenInformation(token, TokenUser, ptr::null_mut(), 0, &mut length);
        let mut buffer = vec![0usize; (length as usize).div_ceil(size_of::<usize>())];
        assert_ne!(
            GetTokenInformation(
                token,
                TokenUser,
                buffer.as_mut_ptr().cast(),
                length,
                &mut length
            ),
            0
        );
        let mut sid = ptr::null_mut();
        assert_ne!(
            ConvertSidToStringSidW((*buffer.as_ptr().cast::<TOKEN_USER>()).User.Sid, &mut sid),
            0
        );
        let mut count = 0;
        while *sid.add(count) != 0 {
            count += 1;
        }
        let user = String::from_utf16_lossy(std::slice::from_raw_parts(sid, count));
        let sddl: Vec<u16> = format!("O:{user}D:P(A;OICI;FA;;;{user})")
            .encode_utf16()
            .chain(Some(0))
            .collect();
        let mut descriptor = ptr::null_mut();
        assert_ne!(
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                1,
                &mut descriptor,
                ptr::null_mut()
            ),
            0
        );
        let attributes = SECURITY_ATTRIBUTES {
            nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: descriptor,
            bInheritHandle: 0,
        };
        let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        assert_ne!(CreateDirectoryW(wide.as_ptr(), &attributes), 0);
        LocalFree(descriptor as _);
        LocalFree(sid as _);
        windows_sys::Win32::Foundation::CloseHandle(token);
    }
}

#[tokio::test]
async fn actual_windows_af_unix_enforces_elevation_and_preserves_ids() {
    let elevated = current_process_is_elevated();
    let home = common::home();
    let control = home.path().join("app-server-control");
    private_directory(&control);
    let socket = control.join("app-server-control.sock");
    let listener = uds_windows::UnixListener::bind(&socket).unwrap();
    let server = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        if elevated {
            let mut bytes = Vec::new();
            std::io::Read::read_to_end(&mut stream, &mut bytes).unwrap();
            assert!(
                bytes.is_empty(),
                "elevated clients must not send handshake or application bytes"
            );
            return;
        }
        let mut websocket = tokio_tungstenite::tungstenite::accept(stream).unwrap();
        let request = websocket.read().unwrap();
        let message: serde_json::Value = serde_json::from_str(request.to_text().unwrap()).unwrap();
        assert_eq!(message["id"], "opaque-native:17");
        websocket
            .send(tokio_tungstenite::tungstenite::Message::Text(
                json!({"id":message["id"],"result":{"ok":true}})
                    .to_string()
                    .into(),
            ))
            .unwrap();
    });
    let found = Discovery::for_home(home.path()).unwrap();
    assert_eq!(found.availability(), Availability::Present);
    let connection =
        tokio::time::timeout(Duration::from_secs(3), connect(&found, Limits::default()))
            .await
            .unwrap();
    if elevated {
        assert!(matches!(connection, Err(ref error)
            if error.kind() == io::ErrorKind::PermissionDenied
                && error.to_string().contains("non-elevated current-user tokens")));
        server.join().unwrap();
        fs::remove_file(socket).unwrap();
        return;
    }
    let mut client = connection.unwrap();
    assert_eq!(client.peer_process_id().unwrap(), std::process::id());
    assert!(
        fs::rename(&control, home.path().join("replaced-directory")).is_err(),
        "validated directory must stay pinned while connected"
    );
    client
        .write(&json!({"id":"opaque-native:17", "method":"fixture"}))
        .await
        .unwrap();
    let response = tokio::time::timeout(Duration::from_secs(3), client.read())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(
        response,
        json!({"id":"opaque-native:17", "result":{"ok":true}})
    );
    drop(client);
    server.join().unwrap();
    fs::remove_file(socket).unwrap();
}

#[tokio::test]
async fn insecure_directory_is_rejected_before_application_bytes() {
    let home = common::home();
    let control = home.path().join("app-server-control");
    fs::create_dir(&control).unwrap();
    let listener =
        uds_windows::UnixListener::bind(control.join("app-server-control.sock")).unwrap();
    listener.set_nonblocking(true).unwrap();
    assert!(Discovery::for_home(home.path()).is_err());
    assert_eq!(
        listener.accept().unwrap_err().kind(),
        io::ErrorKind::WouldBlock
    );
    drop(listener);
    fs::remove_file(control.join("app-server-control.sock")).unwrap();
}

#[tokio::test]
async fn stale_discovery_is_revalidated_before_connection() {
    let home = common::home();
    let found = Discovery::for_home(home.path()).unwrap();
    let control = home.path().join("app-server-control");
    fs::create_dir(&control).unwrap();
    let listener =
        uds_windows::UnixListener::bind(control.join("app-server-control.sock")).unwrap();
    listener.set_nonblocking(true).unwrap();
    assert!(connect(&found, Limits::default()).await.is_err());
    assert_eq!(
        listener.accept().unwrap_err().kind(),
        io::ErrorKind::WouldBlock
    );
    drop(listener);
    fs::remove_file(control.join("app-server-control.sock")).unwrap();
}

#[tokio::test]
async fn websocket_upgrade_has_a_deadline_after_elevation_validation() {
    use std::io::Read;
    let elevated = current_process_is_elevated();
    let home = common::home();
    let control = home.path().join("app-server-control");
    private_directory(&control);
    let socket = control.join("app-server-control.sock");
    let listener = uds_windows::UnixListener::bind(&socket).unwrap();
    let server = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        let mut bytes = Vec::new();
        stream.read_to_end(&mut bytes).unwrap();
        if elevated {
            assert!(
                bytes.is_empty(),
                "elevation must be checked before the upgrade request"
            );
        } else {
            assert!(bytes.starts_with(b"GET / HTTP/1.1\r\n"));
        }
    });
    let found = Discovery::for_home(home.path()).unwrap();
    let result = connect(
        &found,
        Limits {
            connect_timeout: Duration::from_millis(50),
            ..Limits::default()
        },
    )
    .await;
    if elevated {
        assert!(matches!(result, Err(error)
            if error.kind() == io::ErrorKind::PermissionDenied
                && error.to_string().contains("non-elevated current-user tokens")));
    } else {
        assert!(matches!(result, Err(error) if error.kind() == io::ErrorKind::TimedOut));
    }
    server.join().unwrap();
    fs::remove_file(socket).unwrap();
}

#[test]
fn canonical_windows_home_must_fit_without_alias_shortening() {
    let home = common::home();
    let long = home.path().join("x".repeat(80));
    fs::create_dir(&long).unwrap();
    let result = Discovery::for_home(&long);
    assert!(matches!(result, Err(error) if error.kind() == io::ErrorKind::InvalidInput));
}
