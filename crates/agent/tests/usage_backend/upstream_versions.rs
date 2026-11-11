use super::*;
use serde_json::json;
fn installed(root: &Path, version: &str) -> String {
    use sha2::Digest;
    let bytes = serde_json::to_vec(
        &json!({"schema":1,"version":version,"files":[],"ignored":"DO_NOT_PROJECT"}),
    )
    .unwrap();
    let generation = hex::encode(sha2::Sha256::digest(&bytes));
    let bin = root.join("CodexPocket/bin").join(&generation);
    std::fs::create_dir_all(&bin).unwrap();
    std::fs::write(bin.join("windows-manifest.json"), bytes).unwrap();
    std::fs::write(root.join("CodexPocket/desktop-install.json"), serde_json::to_vec(&json!({"version":3,"generation":generation,"releaseHash":generation,"installRoot":"DO_NOT_FOLLOW","previousCliPath":"DO_NOT_PROJECT"})).unwrap()).unwrap();
    generation
}
#[test]
fn versions_project_only_necessary_fields_from_verified_fixed_locations() {
    let root = tempfile::tempdir().unwrap();
    let generation = installed(root.path(), "0.6.3");
    let state = root.path().join("state");
    std::fs::create_dir_all(state.join("desktop")).unwrap();
    std::fs::write(state.join("desktop/supervisor.json"), serde_json::to_vec(&json!({"pid":42,"runtime":{"pid":42,"version":"0.6.2","generation":generation,"controlProtocol":1,"executableSha256":"DO_NOT_PROJECT"},"upgrade":{"private":"DO_NOT_PROJECT"}})).unwrap()).unwrap();
    let bin = root.path().join("CodexPocket/bin").join(&generation);
    let exe = bin.join("pocket-agent.exe");
    let v = project(
        "0.6.3",
        Some(&exe),
        Some(root.path()),
        &state,
        &[BridgeVersion {
            runtime_id: "r".into(),
            version: Some("0.6.1".into()),
        }],
    );
    assert_eq!(v["helper"]["version"], "0.6.3");
    assert_eq!(v["helper"]["generation"], generation);
    assert_eq!(v["installed"]["version"], "0.6.3");
    assert_eq!(v["supervisor"]["version"], "0.6.2");
    assert_eq!(v["supervisor"]["evidence"], "supervisor_stamp");
    assert_eq!(v["host"]["state"], "unknown");
    assert_eq!(v["bridges"][0]["version"], "0.6.1");
    let serialized = v.to_string();
    for marker in [
        "DO_NOT",
        "installRoot",
        "executableSha256",
        "pid",
        root.path().to_str().unwrap(),
    ] {
        assert!(!serialized.contains(marker));
    }
}
#[test]
fn invalid_required_fields_digest_missing_or_large_file_are_unknown() {
    let root = tempfile::tempdir().unwrap();
    let generation = installed(root.path(), "0.6.3");
    let state = root.path().join("state");
    std::fs::create_dir_all(state.join("desktop")).unwrap();
    let stamp = state.join("desktop/supervisor.json");
    for value in [
        json!({}),
        json!({"pid":42,"runtime":{"pid":0,"version":"0.6.3","generation":generation,"controlProtocol":1}}),
        json!({"pid":42,"runtime":{"pid":42,"version":"C:/secret","generation":generation,"controlProtocol":1}}),
    ] {
        std::fs::write(&stamp, value.to_string()).unwrap();
        assert_eq!(
            project("0.6.3", None, Some(root.path()), &state, &[])["supervisor"]["state"],
            "unknown"
        );
    }
    let manifest = root
        .path()
        .join("CodexPocket/bin")
        .join(&generation)
        .join("windows-manifest.json");
    std::fs::write(&manifest, r#"{"schema":1,"version":"0.6.4"}"#).unwrap();
    assert_eq!(
        project("0.6.3", None, Some(root.path()), &state, &[])["installed"]["state"],
        "unknown"
    );
    std::fs::write(&manifest, vec![b' '; 1024 * 1024 + 1]).unwrap();
    assert_eq!(
        project("0.6.3", None, Some(root.path()), &state, &[])["installed"]["state"],
        "unknown"
    );
    let v = project(
        "0.6.3",
        None,
        None,
        &state,
        &[BridgeVersion {
            runtime_id: "r".into(),
            version: Some("DO_NOT_PROJECT_SECRET".into()),
        }],
    );
    assert_eq!(v["bridges"][0]["state"], "unknown");
    assert_eq!(v["helper"]["generation"], Value::Null);
}
#[test]
fn manifest_version_cannot_replace_the_running_helper_version() {
    let root = tempfile::tempdir().unwrap();
    let generation = installed(root.path(), "0.6.2");
    let exe = root
        .path()
        .join("CodexPocket/bin")
        .join(&generation)
        .join("pocket-agent.exe");
    let v = project("0.6.3", Some(&exe), Some(root.path()), root.path(), &[]);
    assert_eq!(v["helper"]["version"], "0.6.3");
    assert!(v["helper"]["generation"].is_null());
    assert_eq!(v["installed"]["version"], "0.6.2");
}
#[test]
fn version_syntax_rejects_empty_suffixes_and_arbitrary_text() {
    for version in [
        "0.6.3-",
        "0.6.3+",
        "0.6.3+..",
        "0.6.3+x+y",
        "00.6.3",
        "0.6.3-alpha..1",
        "0.6.3-alpha.01",
        "DO_NOT_PROJECT",
    ] {
        assert!(!valid_version(version), "invalid version syntax accepted");
    }
    for version in ["0.6.3", "0.166.0-alpha.6", "0.6.3+build.01", "0.6.3-beta-1"] {
        assert!(valid_version(version));
    }
}
#[test]
fn version_files_reject_relative_paths_and_reparse_or_symlink_targets() {
    assert!(crate::usage_stats::read_projection(Path::new("relative.json"), 4096).is_none());
    let root = tempfile::tempdir().unwrap();
    installed(root.path(), "0.6.3");
    let outside = tempfile::tempdir().unwrap();
    let state = outside.path().join("state");
    std::fs::create_dir_all(&state).unwrap();
    let target = root.path().join("CodexPocket");
    let link = outside.path().join("CodexPocket");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let output = std::process::Command::new("cmd.exe")
            .args(["/d", "/c", "mklink", "/J"])
            .arg(&link)
            .arg(&target)
            .creation_flags(0x08000000)
            .output()
            .unwrap();
        assert!(output.status.success());
    }
    #[cfg(unix)]
    std::os::unix::fs::symlink(&target, &link).unwrap();
    assert_eq!(
        project("0.6.3", None, Some(outside.path()), &state, &[])["installed"]["state"],
        "unknown"
    );
    #[cfg(windows)]
    std::fs::remove_dir(link).unwrap();
}
