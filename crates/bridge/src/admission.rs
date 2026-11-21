//! Installed Bridge startup: register under the installation lock BEFORE native spawn.
use anyhow::{Context, Result, ensure};
use pocket_core::{
    admission::{
        self, Installation, Registration, RegistrationPhase, StartupContext, StartupGuard,
    },
    native_process::NativeProcess,
};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};

pub struct Startup {
    pub installation: Installation,
    pub guard: StartupGuard,
    pub registration: Registration,
}

/// Separate process creation from wait: a wait error cannot prove no child existed.
pub fn spawn_native(
    command: &mut tokio::process::Command,
    startup: Option<&Startup>,
) -> Result<tokio::process::Child> {
    #[cfg(not(windows))]
    let _ = startup;
    match command.spawn() {
        Ok(child) => Ok(child),
        Err(error) => {
            // Locked Tokio's Windows build_child/stdio conversion is infallible:
            // a returned spawn error comes from std::Command before child creation.
            // Cancellation stays under the original shared guard, never on Drop.
            #[cfg(windows)]
            if let Some(startup) = startup {
                if let Err(cancel) = startup.guard.cancel_starting(&startup.registration) {
                    return Err(anyhow::Error::new(error)
                        .context(format!("Failed to cancel rejected native startup: {cancel:#}")));
                }
            }
            Err(error.into())
        }
    }
}

fn context() -> Result<Option<StartupContext>> {
    match (
        std::env::var("POCKET_UPDATE_OPERATION").ok(),
        std::env::var_os("POCKET_UPDATE_RELEASE_FILE"),
    ) {
        (None, None) => Ok(None),
        (Some(operation_id), Some(path)) => Ok(Some(StartupContext {
            operation_id,
            release_file: path.into(),
        })),
        _ => anyhow::bail!("admission_update_context_incomplete"),
    }
}

pub fn installed_root(exe: &Path, explicit: Option<&Path>) -> Result<Option<(PathBuf, String)>> {
    let bin = exe.parent().and_then(Path::parent);
    let generation = exe
        .parent()
        .and_then(Path::file_name)
        .and_then(|v| v.to_str());
    let layout = bin.is_some_and(|p| p.file_name().is_some_and(|n| n == "bin"));
    if !layout {
        ensure!(explicit.is_none(), "admission_installation_shape_invalid");
        return Ok(None);
    }
    let root = bin
        .and_then(Path::parent)
        .context("admission_root_missing")?;
    let generation = generation
        .filter(|g| admission::valid_generation(g))
        .context("admission_generation_invalid")?;
    ensure!(
        exe.file_name().is_some_and(|n| n == "pocket-bridge.exe"),
        "admission_bridge_name_invalid"
    );
    ensure!(
        explicit.is_none_or(|r| r == root),
        "admission_explicit_root_mismatch"
    );
    admission::validate_path(root)?;
    let record: Value = admission::read_json(&root.join("desktop-install.json"), 32768)?;
    ensure!(
        record["version"] == 3
            && record["installRoot"].as_str().map(Path::new) == Some(root)
            && record["bridge"].as_str().map(Path::new)
                == Some(root.join("pocket-launcher.exe").as_path()),
        "admission_installation_binding_invalid"
    );
    let state = Path::new(
        record["stateDirectory"]
            .as_str()
            .context("admission_state_missing")?,
    );
    admission::validate_path(state)?;
    ensure!(
        admission::file_sha256(&root.join("pocket-launcher.exe"))?
            == record["launcherHash"].as_str().unwrap_or(""),
        "admission_launcher_changed"
    );
    let manifest = exe.parent().unwrap().join("windows-manifest.json");
    ensure!(
        admission::file_sha256(&manifest)? == generation,
        "admission_generation_changed"
    );
    let manifest: Value = admission::read_json(&manifest, 1024 * 1024)?;
    let entries: Vec<_> = manifest["files"]
        .as_array()
        .context("admission_manifest_invalid")?
        .iter()
        .filter(|f| f["path"] == "pocket-bridge.exe")
        .collect();
    ensure!(
        entries.len() == 1
            && entries[0]["sha256"] == admission::file_sha256(exe)?
            && entries[0]["bytes"].as_u64() == Some(std::fs::metadata(exe)?.len()),
        "admission_bridge_changed"
    );
    Ok(Some((root.into(), generation.into())))
}

pub fn begin(
    exe: &Path,
    directory: &Path,
    runtime_id: &str,
    app_server: bool,
) -> Result<Option<Startup>> {
    let explicit = std::env::var_os("POCKET_INSTALL_ROOT").map(PathBuf::from);
    let Some((root, generation)) = installed_root(exe, explicit.as_deref())? else {
        return Ok(None);
    };
    ensure!(
        directory == root.join("runtimes"),
        "admission_runtime_directory_mismatch"
    );
    let installation = Installation::open(&root)?;
    let context = context()?;
    let guard = installation.startup(&generation, context.as_ref())?;
    // Only an app-server can enforce the Router startup write barrier. Reject
    // other invocations before even creating a pre-spawn uncertainty record.
    if !app_server && let Some(c) = &context {
        ensure!(
            admission::read_release(&c.release_file, &c.operation_id)?,
            "admission_passthrough_fenced"
        );
    }
    let registration = Registration {
        schema: 1,
        runtime_id: runtime_id.into(),
        install_root: root,
        generation,
        bridge: NativeProcess::capture(std::process::id())?,
        bridge_executable: exe.into(),
        descriptor: directory.join(format!("{runtime_id}.json")),
        operation_id: context.as_ref().map(|c| c.operation_id.clone()),
        release_file: context.map(|c| c.release_file),
        phase: RegistrationPhase::Starting,
        native: None,
    };
    guard.register(&registration)?;
    Ok(Some(Startup {
        installation,
        guard,
        registration,
    }))
}

/// Schema export creates another native child. Keep it behind the same gate
/// and retain shared admission until discover has reaped that child.
pub async fn discover(
    binary: &Path,
    registration: Option<&Registration>,
) -> Result<crate::capabilities::Capabilities> {
    let _guard = if let Some(r) = registration {
        let context = r.operation_id.as_ref().zip(r.release_file.as_ref()).map(
            |(operation_id, release_file)| StartupContext {
                operation_id: operation_id.clone(),
                release_file: release_file.clone(),
            },
        );
        Some(Installation::open(&r.install_root)?.startup(&r.generation, context.as_ref())?)
    } else {
        None
    };
    crate::capabilities::discover(binary).await
}

/// A short updater lock must not leave routine exits permanently registered.
/// The caller must first reap every child it spawned; timeout retains evidence.
pub async fn unregister(installation: &Installation, runtime_id: &str) {
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(1);
    loop {
        if installation.unregister(runtime_id).is_ok() || tokio::time::Instant::now() >= deadline {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(25)).await;
    }
}

pub fn summary(registration: Option<&Registration>) -> Value {
    registration.map(|r|json!({"installRoot":r.install_root,"generation":r.generation,"registered":r.phase==RegistrationPhase::Registered,"registrationId":r.runtime_id})).unwrap_or(Value::Null)
}
