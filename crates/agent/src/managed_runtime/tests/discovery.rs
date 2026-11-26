use super::*;
use serde_json::json;

fn write(path: &Path, data: &[u8]) {
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, data).unwrap();
}
fn native(path: &Path) {
    #[cfg(windows)]
    {
        let mut bytes = [0u8; 128];
        bytes[..2].copy_from_slice(b"MZ");
        bytes[60..64].copy_from_slice(&64u32.to_le_bytes());
        bytes[64..68].copy_from_slice(b"PE\0\0");
        write(path, &bytes);
    }
    #[cfg(not(windows))]
    write(path, b"\x7fELFfixture never executed");
}
fn fixture() -> (tempfile::TempDir, PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let home = dir.path().join("home");
    std::fs::create_dir(&home).unwrap();
    (dir, home)
}
fn vendor(package: &Path) -> PathBuf {
    #[cfg(all(windows, target_arch = "x86_64"))]
    let target = "x86_64-pc-windows-msvc";
    #[cfg(all(windows, target_arch = "aarch64"))]
    let target = "aarch64-pc-windows-msvc";
    #[cfg(all(target_os = "linux", target_arch = "x86_64"))]
    let target = "x86_64-unknown-linux-musl";
    #[cfg(all(target_os = "linux", target_arch = "aarch64"))]
    let target = "aarch64-unknown-linux-musl";
    #[cfg(all(target_os = "macos", target_arch = "x86_64"))]
    let target = "x86_64-apple-darwin";
    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    let target = "aarch64-apple-darwin";
    package
        .join("vendor")
        .join(target)
        .join("codex")
        .join(if cfg!(windows) { "codex.exe" } else { "codex" })
}

#[test]
fn packaged_codex_from_before_an_update_is_replaced_by_the_one_now_installed() {
    let dir = tempfile::tempdir().unwrap();
    let before = dir.path().join("app-before/lib/x86_64/libcodex.so");
    let agent = dir.path().join("app-now/lib/x86_64/libpocket-agent.so");
    let now = agent.with_file_name("libcodex.so");
    write(&now, b"now");
    // The path an update left behind points at the Codex beside the running agent.
    assert_eq!(packaged_replacement(&before, Some(&agent), true), Some(now.clone()));
    // Only on a packaged installation, only for Codex, and only once the old one is really gone.
    assert_eq!(packaged_replacement(&before, Some(&agent), false), None);
    assert_eq!(packaged_replacement(&before.with_file_name("libother.so"), Some(&agent), true), None);
    assert_eq!(packaged_replacement(Path::new("lib/libcodex.so"), Some(&agent), true), None);
    assert_eq!(packaged_replacement(&now, Some(&agent), true), None);
    write(&before, b"before");
    assert_eq!(packaged_replacement(&before, Some(&agent), true), None);
    std::fs::remove_file(&before).unwrap();
    std::fs::remove_file(&now).unwrap();
    assert_eq!(packaged_replacement(&before, Some(&agent), true), None);
    assert_eq!(packaged_replacement(&before, None, true), None);
}

#[test]
fn explicit_native_config_precedes_path_and_preserves_config_home() {
    let (dir, home) = fixture();
    let selected = dir.path().join("selected/codex.exe");
    let path = dir.path().join("path/codex.exe");
    native(&selected);
    native(&path);
    let found = select(&home, &[selected.clone()], &[path.parent().unwrap().into()]).unwrap();
    assert_eq!(
        found.native_binary(),
        dunce::canonicalize(selected).unwrap()
    );
    assert_eq!(found.config_home(), dunce::canonicalize(home).unwrap());
}

#[test]
fn pocket_sidecar_resolves_native_in_the_same_directory_without_recursion() {
    let (dir, home) = fixture();
    let bridge = dir.path().join("bin/pocket-bridge.exe");
    let binary = dir.path().join("bin/codex.exe");
    native(&bridge);
    native(&binary);
    write(
        &bridge.with_file_name("pocket-original.json"),
        &serde_json::to_vec(&json!({"binary":binary})).unwrap(),
    );
    assert_eq!(
        select(&home, &[bridge], &[]).unwrap().native_binary(),
        dunce::canonicalize(binary).unwrap()
    );
}

#[test]
fn recursive_pocket_wrapper_and_scripts_never_become_native() {
    let (dir, home) = fixture();
    let bridge = dir.path().join("pocket-bridge.exe");
    native(&bridge);
    write(
        &bridge.with_file_name("pocket-original.json"),
        &serde_json::to_vec(&json!({"binary":bridge})).unwrap(),
    );
    let error = select(&home, &[bridge], &[]).unwrap_err();
    assert!(matches!(
        error.downcast_ref::<Unavailable>(),
        Some(Unavailable::MissingCli)
    ));
    let script = dir.path().join("scripts/codex.exe");
    write(&script, b"echo DO NOT RUN THIS WRAPPER");
    assert!(select(&home, &[script], &[]).is_err());
}

#[cfg(windows)]
#[test]
fn malformed_mz_file_is_not_a_native_cli() {
    let (dir, home) = fixture();
    let binary = dir.path().join("codex.exe");
    write(&binary, b"MZecho this is not a PE executable");
    assert!(select(&home, &[binary], &[]).is_err());
}

#[test]
fn npm_and_pocket_module_wrappers_resolve_behind_native_executable() {
    let (dir, home) = fixture();
    let bin = dir.path().join("npm");
    let package = bin.join("node_modules/@openai/codex");
    let binary = vendor(&package);
    native(&binary);
    let launcher = bin.join("codex.cmd");
    write(
        &launcher,
        b"echo .codex2-cli/cli-wrapper.mjs MUST NOT EXECUTE",
    );
    let module = package.join("bin/codex.js");
    write(&module, b"throw new Error('must not execute');");
    write(
        &bin.join(".codex2-cli/runtime.json"),
        &serde_json::to_vec(&json!({"schema":1,"originalModule":module})).unwrap(),
    );
    assert_eq!(
        select(&home, &[], &[bin]).unwrap().native_binary(),
        dunce::canonicalize(binary).unwrap()
    );
}

#[test]
fn npm_current_bin_layout_is_found_without_executing_wrapper() {
    let (dir, home) = fixture();
    let bin = dir.path().join("npm");
    let package = bin.join("node_modules/@openai/codex");
    let legacy = vendor(&package);
    let binary = legacy.parent().unwrap().parent().unwrap().join("bin").join(legacy.file_name().unwrap());
    native(&binary);
    write(&bin.join("codex.cmd"), b"echo MUST NOT EXECUTE");
    assert_eq!(select(&home, &[], &[bin]).unwrap().native_binary(), dunce::canonicalize(binary).unwrap());
}

#[cfg(windows)]
#[test]
fn volta_pe_shim_resolves_installed_package_native_instead_of_running_shim() {
    let (dir, home) = fixture();
    let volta = dir.path().join("volta");
    let shim = volta.join("bin/codex.exe");
    native(&shim);
    let package = volta.join("tools/image/packages/@openai/codex/1.2.3/node_modules/@openai/codex");
    let binary = vendor(&package);
    native(&binary);
    assert_eq!(
        select(&home, &[], &[shim.parent().unwrap().into()])
            .unwrap()
            .native_binary(),
        dunce::canonicalize(binary).unwrap()
    );
}

#[test]
#[ignore = "invoked only in a fixture-private environment by the following test"]
fn environment_fixture() {
    let root = std::env::var("PREPARATION_FIXTURE_ROOT").unwrap();
    let root = Path::new(&root);
    if std::env::var_os("PREPARATION_EXPECT_INVALID_HOME").is_some() {
        assert!(matches!(
            from_environment()
                .unwrap_err()
                .downcast_ref::<Unavailable>(),
            Some(Unavailable::MissingConfig)
        ));
    } else {
        let profile = from_environment().unwrap();
        assert_eq!(
            profile.config_home(),
            dunce::canonicalize(
                std::env::var_os("PREPARATION_EXPECT_HOME")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| root.join("home")),
            )
            .unwrap()
        );
        assert_eq!(
            profile.native_binary(),
            dunce::canonicalize(root.join("bin/codex.exe")).unwrap()
        );
    }
}

#[test]
fn native_environment_discovery_honors_explicit_home_and_never_falls_back_on_invalid_home() {
    let (dir, home) = fixture();
    let binary = dir.path().join("bin/codex.exe");
    native(&binary);
    for invalid in [false, true] {
        let mut command = std::process::Command::new(std::env::current_exe().unwrap());
        command
            .args([
                "--ignored",
                "--exact",
                "managed_runtime::discovery::tests::environment_fixture",
            ])
            .env("PREPARATION_FIXTURE_ROOT", dir.path())
            .env(
                "CODEX_HOME",
                if invalid {
                    dir.path().join("missing")
                } else {
                    home.clone()
                },
            )
            .env("CODEX_CLI_PATH", &binary)
            .env("PATH", binary.parent().unwrap());
        if invalid {
            command.env("PREPARATION_EXPECT_INVALID_HOME", "1");
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000);
        }
        let output = command.output().unwrap();
        assert!(
            output.status.success(),
            "{}{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
    }
}

fn check_environment(root: &Path, home: Option<&std::ffi::OsStr>, expected: Option<&Path>) {
    let binary = root.join("bin/codex.exe");
    let mut command = std::process::Command::new(std::env::current_exe().unwrap());
    command
        .args([
            "--ignored",
            "--exact",
            "managed_runtime::discovery::tests::environment_fixture",
        ])
        .env("PREPARATION_FIXTURE_ROOT", root)
        .env_remove("PREPARATION_EXPECT_INVALID_HOME")
        .env_remove("PREPARATION_EXPECT_HOME")
        .env_remove("CODEX_HOME")
        .env("USERPROFILE", root)
        .env("HOME", root)
        .env("CODEX_CLI_PATH", &binary)
        .env("PATH", binary.parent().unwrap());
    if let Some(home) = home {
        command.env("CODEX_HOME", home);
    }
    if let Some(expected) = expected {
        command.env("PREPARATION_EXPECT_HOME", expected);
    } else {
        command.env("PREPARATION_EXPECT_INVALID_HOME", "1");
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    let output = command.output().unwrap();
    assert!(
        output.status.success(),
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

#[test]
fn long_config_home_discovery_does_not_require_cli_attachment_socket() {
    let (dir, _) = fixture();
    native(&dir.path().join("bin/codex.exe"));
    for name in ["isolated-native-home-".repeat(4), "隔离配置目录".repeat(8)] {
        let home = dir.path().join(name);
        write(&home.join("config.toml"), b"model = 'fixture-never-run'\n");
        write(&home.join("auth.json"), b"fixture account bytes");
        let socket = home.join("app-server-control/app-server-control.sock");
        assert!(socket.to_str().unwrap().len() >= 108);
        assert!(!socket.exists());
        #[cfg(windows)]
        assert!(codex_cli_adapter::Discovery::for_home(&home).is_err());
        check_environment(dir.path(), Some(home.as_os_str()), Some(&home));
        assert!(!socket.exists(), "Host discovery must not create CLI state");
        assert_eq!(std::fs::read(home.join("auth.json")).unwrap(), b"fixture account bytes");
    }
}

#[test]
fn long_saved_config_home_restores_and_migrates_without_cli_socket_validation() {
    let (dir, _) = fixture();
    let home = dir.path().join("isolated-native-home-".repeat(4));
    std::fs::create_dir(&home).unwrap();
    let binary = dir.path().join("custom/codex.exe");
    native(&binary);
    let selected = restore_saved_profile_with(&home, &binary, || panic!("Existing native must not query packages")).unwrap();
    assert_eq!(selected.config_home(), dunce::canonicalize(&home).unwrap());
    assert_eq!(selected.native_binary(), dunce::canonicalize(&binary).unwrap());
    let (old, package, new) = msix_fixture(dir.path());
    let selected = restore_saved_profile_with(&home, &old, || Ok(vec![package])).unwrap();
    assert_eq!(selected.config_home(), dunce::canonicalize(&home).unwrap());
    assert_eq!(selected.native_binary(), dunce::canonicalize(new).unwrap());
}

#[test]
fn long_discovered_profile_survives_assistant_prepare_revalidation() {
    let (dir, _) = fixture();
    let home = dir.path().join("isolated-native-home-".repeat(4));
    std::fs::create_dir(&home).unwrap();
    let binary = dir.path().join("bin/codex.exe");
    native(&binary);
    let discovered = select(&home, &[binary], &[]).unwrap();
    // Broker::ensure_assistant_with reconstructs SelectedProfile from its saved
    // paths before starting Host. Discovery success must survive this boundary.
    let selected =
        SelectedProfile::new(discovered.config_home(), discovered.native_binary()).unwrap();
    assert_eq!(selected.config_home(), discovered.config_home());
    assert_eq!(selected.native_binary(), discovered.native_binary());
}

#[test]
fn host_profile_retains_physical_path_and_file_type_validation() {
    let (dir, home) = fixture();
    let binary = dir.path().join("bin/codex.exe");
    native(&binary);
    for invalid_home in [dir.path().join("missing"), binary.clone(), PathBuf::from(".")] {
        assert!(SelectedProfile::new(invalid_home, &binary).is_err());
    }
    for invalid_binary in [dir.path().join("missing.exe"), home.clone(), PathBuf::from("codex.exe")] {
        assert!(SelectedProfile::new(&home, invalid_binary).is_err());
    }
}

#[test]
fn host_discovery_ignores_unrelated_invalid_cli_socket() {
    let (dir, home) = fixture();
    native(&dir.path().join("bin/codex.exe"));
    let socket = home.join("app-server-control/app-server-control.sock");
    write(&socket, b"not a CLI socket");
    assert!(codex_cli_adapter::Discovery::for_home(&home).is_err());
    check_environment(dir.path(), Some(home.as_os_str()), Some(&home));
    assert_eq!(std::fs::read(socket).unwrap(), b"not a CLI socket");
}

#[test]
fn invalid_explicit_config_home_never_selects_valid_default_account() {
    let (dir, _) = fixture();
    native(&dir.path().join("bin/codex.exe"));
    let default = dir.path().join(".codex");
    std::fs::create_dir(&default).unwrap();
    for explicit in [None, Some(std::ffi::OsString::new())] {
        assert_eq!(
            configuration_home(explicit, || Ok(Some(dir.path().to_owned()))).unwrap(),
            dunce::canonicalize(&default).unwrap()
        );
    }
    let file = dir.path().join("file");
    write(&file, b"not a directory");
    for home in [dir.path().join("missing"), file, PathBuf::from(".codex")] {
        check_environment(dir.path(), Some(home.as_os_str()), None);
        assert!(configuration_home(Some(home.into_os_string()), || panic!("Invalid explicit home must not consult another account")).is_err());
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStringExt;
        let mut invalid = dir.path().join("invalid-unicode-").into_os_string();
        invalid.push(std::ffi::OsString::from_wide(&[0xd800]));
        // NTFS can hold this existing physical directory, but native's Unicode
        // CODEX_HOME parser cannot select it. It must not become a profile.
        std::fs::create_dir(&invalid).unwrap();
        check_environment(dir.path(), Some(&invalid), None);
    }
}

#[cfg(windows)]
#[test]
fn environment_config_home_rejects_reparse_points_and_package_redirection() {
    let (dir, home) = fixture();
    native(&dir.path().join("bin/codex.exe"));
    std::fs::create_dir(dir.path().join(".codex")).unwrap();
    let linked = dir.path().join("home-link");
    junction(&linked, &home);
    check_environment(dir.path(), Some(linked.as_os_str()), None);
    assert!(SelectedProfile::new(&linked, dir.path().join("bin/codex.exe")).is_err());
    std::fs::create_dir(home.join("nested")).unwrap();
    check_environment(dir.path(), Some(linked.join("nested").as_os_str()), None);
    assert!(SelectedProfile::new(linked.join("nested"), dir.path().join("bin/codex.exe")).is_err());
    native(&home.join("codex.exe"));
    assert!(SelectedProfile::new(&home, linked.join("codex.exe")).is_err());
    let redirected = dir.path().join("Packages/Fixture/LocalCache/home");
    std::fs::create_dir_all(&redirected).unwrap();
    check_environment(dir.path(), Some(redirected.as_os_str()), None);
}

fn msix_fixture(dir:&Path)->(PathBuf,MsixPackage,PathBuf) {
    let root=dunce::canonicalize(dir).unwrap().join("WindowsApps");
    let old=root.join("OpenAI.Codex_26.1103.7773.0_x64__2p2nqsd0c76g0/app/resources/codex.exe");
    let full_name="OpenAI.Codex_26.1106.6628.0_x64__2p2nqsd0c76g0".to_string();
    let package=MsixPackage{location:root.join(&full_name),full_name};
    let new=package.location.join("app/resources/codex.exe");native(&new);
    (old,package,new)
}

#[test]
fn saved_msix_migration_preserves_home_and_only_selects_same_registered_package() {
    let (dir,home)=fixture();let(old,package,new)=msix_fixture(dir.path());
    write(&home.join("auth.json"),b"fixture account bytes");
    let selected=restore_saved_profile_with(&home,&old,||Ok(vec![package])).unwrap();
    assert_eq!(selected.native_binary(),dunce::canonicalize(new).unwrap());
    assert_eq!(selected.config_home(),dunce::canonicalize(&home).unwrap());
    assert_eq!(std::fs::read(home.join("auth.json")).unwrap(),b"fixture account bytes");
    assert!(!old.exists());
}

#[test]
fn saved_valid_custom_cli_never_queries_msix_or_changes_identity() {
    let (dir,home)=fixture();let binary=dir.path().join("custom/codex.exe");native(&binary);
    let selected=restore_saved_profile_with(&home,&binary,||panic!("Valid custom selection must not query MSIX/PATH")).unwrap();
    assert_eq!(selected.native_binary(),dunce::canonicalize(binary).unwrap());
    let missing=dir.path().join("custom/missing.exe");
    let error=restore_saved_profile_with(&home,&missing,||panic!("Missing custom CLI is not an MSIX update")).unwrap_err();
    assert_eq!(error.downcast_ref::<RestoreFailure>(),Some(&RestoreFailure::NativeMissing));
}

#[test]
fn saved_msix_migration_rejects_wrong_identity_roots_architecture_and_ambiguity() {
    let(dir,home)=fixture();let(old,package,_)=msix_fixture(dir.path());
    for changed in [
        MsixPackage{full_name:package.full_name.replace("2p2nqsd0c76g0","differentpublisher"),..package.clone()},
        MsixPackage{full_name:package.full_name.replace("x64","arm64"),..package.clone()},
        MsixPackage{location:dir.path().join("other/WindowsApps").join(&package.full_name),..package.clone()},
    ] {
        let error=restore_saved_profile_with(&home,&old,||Ok(vec![changed])).unwrap_err();
        assert_eq!(error.downcast_ref::<RestoreFailure>(),Some(&RestoreFailure::PackageIdentity));
    }
    let error=restore_saved_profile_with(&home,&old,||Ok(vec![package.clone(),package])).unwrap_err();
    assert_eq!(error.downcast_ref::<RestoreFailure>(),Some(&RestoreFailure::PackageAmbiguous));
    let error=restore_saved_profile_with(&home,&old,||Ok(vec![])).unwrap_err();
    assert_eq!(error.downcast_ref::<RestoreFailure>(),Some(&RestoreFailure::PackageMissing));
}

#[test]
fn saved_msix_migration_validates_home_before_package_query_and_rejects_invalid_native() {
    let(dir,home)=fixture();let(old,package,new)=msix_fixture(dir.path());
    let error=restore_saved_profile_with(&dir.path().join("missing-home"),&old,||panic!("No account fallback")).unwrap_err();
    assert_eq!(error.downcast_ref::<RestoreFailure>(),Some(&RestoreFailure::ConfigHome));
    write(&new,b"wrapper must not execute");
    let error=restore_saved_profile_with(&home,&old,||Ok(vec![package])).unwrap_err();
    assert_eq!(error.downcast_ref::<RestoreFailure>(),Some(&RestoreFailure::NativeInvalid));
}

#[cfg(windows)]
#[test]
fn registered_msix_inventory_is_read_only_and_uses_the_official_family() {
    for package in registered_codex_packages().unwrap() {
        assert!(package_identity(&package.full_name).is_some());
        assert_eq!(package.location.file_name().unwrap().to_str().unwrap(),package.full_name);
    }
}

#[cfg(windows)]
fn junction(link:&Path,target:&Path) {
    use std::os::windows::process::CommandExt;
    let shell=std::path::PathBuf::from(std::env::var_os("SystemRoot").unwrap()).join("System32/WindowsPowerShell/v1.0/powershell.exe");
    let output=std::process::Command::new(shell).args(["-NoProfile","-NonInteractive","-Command","New-Item -ItemType Junction -Path $env:POCKET_MSIX_TEST_LINK -Target $env:POCKET_MSIX_TEST_TARGET -ErrorAction Stop | Out-Null"])
        .env("POCKET_MSIX_TEST_LINK",link).env("POCKET_MSIX_TEST_TARGET",target).env_remove("PSModulePath").creation_flags(0x08000000).output().unwrap();
    assert!(output.status.success(),"Fixture junction creation failed");
}

#[cfg(windows)]
#[test]
fn saved_msix_migration_never_resolves_reparse_evidence_away() {
    let(dir,home)=fixture();let(old,package,new)=msix_fixture(dir.path());
    let outside=dir.path().join("outside");native(&outside.join("codex.exe"));
    std::fs::remove_file(&new).unwrap();std::fs::remove_dir(new.parent().unwrap()).unwrap();
    junction(new.parent().unwrap(),&outside);
    let error=restore_saved_profile_with(&home,&old,||Ok(vec![package])).unwrap_err();
    assert_eq!(error.downcast_ref::<RestoreFailure>(),Some(&RestoreFailure::NativeInvalid));
    let redirected_home=dir.path().join("home-link");junction(&redirected_home,&home);
    let error=restore_saved_profile_with(&redirected_home,&old,||panic!("Redirected account home must not query a replacement")).unwrap_err();
    assert_eq!(error.downcast_ref::<RestoreFailure>(),Some(&RestoreFailure::ConfigHome));
    let linked=dir.path().join("linked");std::fs::create_dir(&linked).unwrap();
    junction(&linked.join("WindowsApps"),&dir.path().join("WindowsApps"));
    let old=linked.join("WindowsApps/OpenAI.Codex_26.1103.7773.0_x64__2p2nqsd0c76g0/app/resources/codex.exe");
    let error=restore_saved_profile_with(&home,&old,||panic!("An old reparse ancestor must be rejected before package discovery")).unwrap_err();
    assert_eq!(error.downcast_ref::<RestoreFailure>(),Some(&RestoreFailure::NativeInvalid));
}
