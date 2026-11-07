use codex_cli_adapter::{Availability, Discovery, validate_windows_socket_path};
use std::{fs, path::Path};
mod common;

#[test]
fn canonical_home_missing_socket_is_passive() {
    let home = common::home();
    fs::create_dir(home.path().join("nested")).unwrap();
    let found = Discovery::for_home(&home.path().join("nested/..")).unwrap();
    assert_eq!(
        found.codex_home(),
        dunce::canonicalize(home.path()).unwrap()
    );
    assert_eq!(
        found.socket_path(),
        found
            .codex_home()
            .join("app-server-control/app-server-control.sock")
    );
    assert_eq!(found.availability(), Availability::Absent);
    assert!(
        !home.path().join("app-server-control").exists(),
        "discovery must not create daemon state"
    );
}

#[test]
fn invalid_homes_are_rejected() {
    let home = common::home();
    assert!(Discovery::for_home(&home.path().join("missing")).is_err());
    fs::write(home.path().join("file"), "not home").unwrap();
    assert!(Discovery::for_home(&home.path().join("file")).is_err());
    assert!(Discovery::for_home(Path::new("")).is_err());
}

#[test]
fn ordinary_file_at_socket_is_rejected() {
    let home = common::home();
    let control = home.path().join("app-server-control");
    fs::create_dir(&control).unwrap();
    fs::write(control.join("app-server-control.sock"), "not a socket").unwrap();
    assert!(Discovery::for_home(home.path()).is_err());
}

#[test]
fn windows_address_requires_absolute_local_utf8_and_108_byte_bound() {
    assert!(validate_windows_socket_path(Path::new(r"C:\short\server.sock")).is_ok());
    assert!(validate_windows_socket_path(Path::new(r"\\?\C:\short\server.sock")).is_ok());
    for bad in [
        "relative.sock".to_owned(),
        r"\\host\share\server.sock".to_owned(),
        r"\\.\pipe\codex".to_owned(),
        "C:\\nul\0.sock".to_owned(),
        format!("C:\\{}", "x".repeat(105)),
    ] {
        assert!(
            validate_windows_socket_path(Path::new(&bad)).is_err(),
            "reject invalid address"
        );
    }
    assert!(validate_windows_socket_path(Path::new(&format!("C:\\{}", "x".repeat(104)))).is_ok());
    assert!(validate_windows_socket_path(Path::new(&format!("C:\\{}", "界".repeat(35)))).is_err());
}

#[test]
fn initialize_identity_must_match_canonical_home() {
    let home = common::home();
    let other = common::home();
    let found = Discovery::for_home(home.path()).unwrap();
    let result = serde_json::json!({"codexHome": found.codex_home(), "userAgent":"codex_cli_rs/0.164.0 (Windows)", "platformFamily":"windows", "platformOs":"windows"});
    assert_eq!(
        found.verify_initialize_result(&result).unwrap().version,
        "0.164.0"
    );
    let mut wrong = result;
    wrong["codexHome"] = serde_json::json!(other.path());
    assert!(found.verify_initialize_result(&wrong).is_err());
    assert!(
        found
            .verify_initialize_result(&serde_json::json!({}))
            .is_err()
    );
}

#[test]
fn environment_override_uses_native_home_without_reading_config() {
    const MARKER: &str = "CODEX_ADAPTER_ENVIRONMENT_FIXTURE";
    if let Some(expected) = std::env::var_os(MARKER) {
        let found = Discovery::from_environment().unwrap().unwrap();
        assert_eq!(found.codex_home(), dunce::canonicalize(expected).unwrap());
        assert_eq!(found.availability(), Availability::Absent);
        return;
    }
    let home = common::home();
    // Deliberately invalid native configuration: identity discovery must not parse it.
    fs::write(home.path().join("config.toml"), [0xff, 0xfe, 0x00]).unwrap();
    let result = std::process::Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "environment_override_uses_native_home_without_reading_config",
        ])
        .env("CODEX_HOME", home.path())
        .env(MARKER, home.path())
        .output()
        .unwrap();
    assert!(
        result.status.success(),
        "fixture subprocess failed: {}",
        String::from_utf8_lossy(&result.stderr)
    );
}
