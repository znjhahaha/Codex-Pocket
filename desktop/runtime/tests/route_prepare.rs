#![cfg(windows)]

use pocket_desktop_runtime::storage;
use std::{
    fs,
    os::windows::{ffi::OsStrExt, fs::OpenOptionsExt},
    ptr,
};
use windows_sys::Win32::Security::{Authorization::*, *};
use windows_sys::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};
use windows_sys::Win32::{Foundation::*, Storage::FileSystem::*};

struct Handle(HANDLE);
impl Drop for Handle {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}

fn fixture() -> tempfile::TempDir {
    // Never use the launching app's potentially virtualized LocalAppData/TEMP.
    tempfile::Builder::new()
        .prefix("route-prepare-")
        .tempdir_in(env!("CARGO_MANIFEST_DIR"))
        .unwrap()
}

fn protected(path: &std::path::Path) -> bool {
    let name: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let mut descriptor = ptr::null_mut();
    let mut owner = ptr::null_mut();
    let mut dacl = ptr::null_mut();
    let code = unsafe {
        GetNamedSecurityInfoW(
            name.as_ptr(),
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &mut owner,
            ptr::null_mut(),
            &mut dacl,
            ptr::null_mut(),
            &mut descriptor,
        )
    };
    assert_eq!(code, 0);
    let mut control = 0;
    let mut revision = 0;
    let result = unsafe { GetSecurityDescriptorControl(descriptor, &mut control, &mut revision) };
    assert_ne!(result, 0);
    let mut token = ptr::null_mut();
    assert_ne!(
        unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) },
        0
    );
    let token = Handle(token);
    let mut length = 0;
    unsafe {
        GetTokenInformation(token.0, TokenUser, ptr::null_mut(), 0, &mut length);
    }
    let mut data = vec![0usize; (length as usize).div_ceil(std::mem::size_of::<usize>())];
    assert_ne!(
        unsafe {
            GetTokenInformation(
                token.0,
                TokenUser,
                data.as_mut_ptr().cast(),
                length,
                &mut length,
            )
        },
        0
    );
    let private = unsafe {
        let user = (*data.as_ptr().cast::<TOKEN_USER>()).User.Sid;
        let mut ace = ptr::null_mut();
        let exact_acl = !dacl.is_null()
            && (*dacl).AceCount == 1
            && GetAce(dacl, 0, &mut ace) != 0
            && (*ace.cast::<ACE_HEADER>()).AceType == 0;
        exact_acl && control & SE_DACL_PROTECTED != 0 && EqualSid(owner, user) != 0 && {
            let allowed = &*ace.cast::<ACCESS_ALLOWED_ACE>();
            allowed.Mask == FILE_ALL_ACCESS
                && allowed.Header.AceFlags == (OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE) as u8
                && EqualSid(ptr::addr_of!(allowed.SidStart).cast_mut().cast(), user) != 0
        }
    };
    unsafe {
        LocalFree(descriptor);
    }
    private
}

#[test]
fn route_prepare_new_directory_is_private_from_first_visibility() {
    let root = fixture();
    // Observe the actual Windows descriptor concurrently, rather than checking
    // only the final result after an unsafe creation window has already closed.
    for n in 0..20 {
        let path = root.path().join(format!("desktop-{n}"));
        let worker_path = path.clone();
        let worker = std::thread::spawn(move || storage::private_dir(&worker_path));
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        while !path.try_exists().unwrap() {
            assert!(
                std::time::Instant::now() < deadline && !worker.is_finished(),
                "Directory creation did not complete"
            );
            std::thread::yield_now();
        }
        let was_private = protected(&path);
        worker.join().unwrap().unwrap();
        assert!(
            was_private,
            "New control directory inherited public permissions before private_dir hardened it"
        );
    }
}

#[test]
fn route_prepare_preserves_os_code_inside_error_sources() {
    #[derive(Debug)]
    struct Wrapped(std::io::Error);
    impl std::fmt::Display for Wrapped {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str("PRIVATE wrapped storage failure")
        }
    }
    impl std::error::Error for Wrapped {
        fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
            Some(&self.0)
        }
    }
    let error =
        anyhow::Error::new(Wrapped(std::io::Error::from_raw_os_error(5))).context("PRIVATE path");
    let public =
        pocket_desktop_runtime::connection::OperationError::from_error("route_prepare", &error);
    assert_eq!(public.code, "permission_denied");
    assert_eq!(public.os_code, Some(5));
    assert!(!serde_json::to_string(&public).unwrap().contains("PRIVATE"));
}

#[test]
fn route_prepare_existing_private_directory_with_live_directory_writer() {
    let root = fixture();
    let dir = root.path().join("desktop");
    storage::private_dir(&dir).unwrap();
    let name: Vec<u16> = dir.as_os_str().encode_wide().chain(Some(0)).collect();
    let handle = unsafe {
        CreateFileW(
            name.as_ptr(),
            GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ,
            ptr::null(),
            OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS,
            ptr::null_mut(),
        )
    };
    assert_ne!(
        handle,
        INVALID_HANDLE_VALUE,
        "{}",
        std::io::Error::last_os_error()
    );
    let _writer = Handle(handle);
    storage::private_dir(&dir)
        .expect("An existing directory writer must not prevent private directory preparation");
    assert!(protected(&dir));
}

#[test]
fn route_prepare_existing_private_directory_with_live_child_writer() {
    let root = fixture();
    let dir = root.path().join("desktop");
    storage::private_dir(&dir).unwrap();
    let child = dir.join("supervisor.lock");
    fs::write(&child, b"preserve").unwrap();
    let _writer = fs::OpenOptions::new()
        .read(true)
        .write(true)
        .share_mode(0)
        .open(&child)
        .unwrap();
    storage::private_dir(&dir)
        .expect("A live child writer must not prevent private directory preparation");
    assert!(protected(&dir));
}

#[test]
fn route_prepare_rejects_a_junction_without_modifying_its_target() {
    let root = fixture();
    let target = root.path().join("target");
    fs::create_dir(&target).unwrap();
    fs::write(target.join("preserved.json"), b"fixture preserved").unwrap();
    let link = root.path().join("linked");
    let mut command = std::process::Command::new(storage::powershell());
    command.args(["-NoProfile","-NonInteractive","-Command","New-Item -ItemType Junction -Path $env:POCKET_ROUTE_TEST_LINK -Target $env:POCKET_ROUTE_TEST_TARGET -ErrorAction Stop | Out-Null"])
        .env("POCKET_ROUTE_TEST_LINK",&link).env("POCKET_ROUTE_TEST_TARGET",&target);
    pocket_desktop_runtime::process::run_bounded(command, 5).unwrap();
    let error = storage::private_dir(&link.join("desktop")).unwrap_err();
    let public =
        pocket_desktop_runtime::connection::OperationError::from_error("route_prepare", &error);
    assert_eq!(public.code, "path_redirected");
    assert!(!public.retryable);
    assert!(!target.join("desktop").exists());
    assert_eq!(
        fs::read(target.join("preserved.json")).unwrap(),
        b"fixture preserved"
    );
}

#[test]
fn route_prepare_keeps_policy_errors_distinct_without_inventing_os_codes() {
    use storage::DirectoryPolicyError;
    for (policy, text) in [
        (DirectoryPolicyError::UnexpectedOwner, "所有者"),
        (DirectoryPolicyError::NotDirectory, "被文件占用"),
        (DirectoryPolicyError::UnsafePath, "不支持的路径形式"),
    ] {
        let error = anyhow::Error::new(policy).context("PRIVATE user directory");
        let failure =
            pocket_desktop_runtime::connection::OperationError::from_error("route_prepare", &error);
        assert_eq!(failure.os_code, None);
        assert!(!failure.retryable);
        assert!(failure.message.contains(text));
        assert!(!serde_json::to_string(&failure).unwrap().contains("PRIVATE"));
    }
}

#[test]
fn route_prepare_nested_directories_are_all_created_private() {
    let root = fixture();
    let parent = root.path().join("state");
    let dir = parent.join("desktop");
    storage::private_dir(&dir).unwrap();
    assert!(protected(&parent));
    assert!(protected(&dir));
    let child = dir.join("routes.json");
    storage::atomic(&child, &serde_json::json!({"policy":"lan"})).unwrap();
    assert_eq!(
        storage::read::<serde_json::Value>(&child).unwrap()["policy"],
        "lan"
    );
}

#[test]
fn route_prepare_concurrent_creators_keep_one_private_directory() {
    let root = fixture();
    let dir = root.path().join("desktop");
    let workers: Vec<_> = (0..4)
        .map(|_| {
            let dir = dir.clone();
            std::thread::spawn(move || storage::private_dir(&dir))
        })
        .collect();
    for worker in workers {
        worker.join().unwrap().unwrap();
    }
    assert!(protected(&dir));
    assert_eq!(fs::read_dir(root.path()).unwrap().count(), 1);
}

#[test]
fn route_prepare_existing_directory_removes_explicit_other_user_grants() {
    let root = fixture();
    let dir = root.path().join("desktop");
    storage::private_dir(&dir).unwrap();
    let child = dir.join("route-request-fixture.json");
    fs::write(&child, b"preserve").unwrap();
    let mut command = std::process::Command::new("icacls.exe");
    command.arg(&dir).args(["/grant", "*S-1-1-0:(OI)(CI)R"]);
    pocket_desktop_runtime::process::run_bounded(command, 5).unwrap();
    assert!(
        !protected(&dir),
        "Fixture must actually contain an explicit Everyone grant"
    );
    storage::private_dir(&dir).unwrap();
    assert!(
        protected(&dir),
        "Existing permissions must still be replaced by the exact current-user DACL"
    );
    assert_eq!(fs::read(&child).unwrap(), b"preserve");
}
