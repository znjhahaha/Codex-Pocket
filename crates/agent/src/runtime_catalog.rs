//! Installation facts are independent of connected process capabilities.
use serde_json::{Value, json};
use std::path::PathBuf;
pub fn installed(homes: &[PathBuf]) -> Value {
    // Native/MSIX discovery can invoke the package service. Status polling must
    // not start a PowerShell/package scan for every phone heartbeat.
    static CACHE: std::sync::OnceLock<std::sync::Mutex<Option<(std::time::Instant, Value)>>> =
        std::sync::OnceLock::new();
    let mut cached = CACHE
        .get_or_init(|| std::sync::Mutex::new(None))
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let native = match cached.as_ref() {
        Some((at, value)) if at.elapsed() < std::time::Duration::from_secs(60) => value.clone(),
        _ => {
            let profile = crate::managed_runtime::discovery::from_environment().ok();
            let cli = crate::managed_runtime::discovery::cli_binary().is_some();
            let packages = crate::managed_runtime::discovery::registered_codex_packages().ok();
            let desktop = packages.as_ref().map(|p| !p.is_empty());
            let found = json!({"nativeInstalled":profile.is_some(),"cliInstalled":cli,"desktopInstalled":desktop});
            *cached = Some((std::time::Instant::now(), found.clone()));
            found
        }
    };
    let profiles: Vec<_> = homes
        .iter()
        .filter_map(|home| {
            crate::storage::config_store(home).ok().map(|id|json!({
        "configStoreId":id,"historyAvailable":home.join("state_5.sqlite").is_file(),
        "sharedEndpointPresent":home.join("app-server-control/app-server-control.sock").exists()
    }))
        })
        .collect();
    let mut result = native;
    result["profiles"] = json!(profiles);
    result
}

/// Seeing the official application and having a Pocket write route are separate
/// facts. This evidence is for presentation, never broker ownership or handoff.
fn observed(
    desktop_version: Option<&str>,
    cli_running: Option<bool>,
    desktop_control: bool,
    cli_control: bool,
) -> Value {
    json!({
        "desktop": {"state": if desktop_control {"controllable"} else if desktop_version.is_some() {"detected"} else {"unknown"}, "version":desktop_version},
        "cli": {"state": if cli_control {"controllable"} else {match cli_running {Some(true)=>"detected",Some(false)=>"not_running",None=>"unknown"}}}
    })
}

/// Only the native session endpoints can attest to external session control.
/// The assistant also speaks the CLI protocol, under its own managedHost role.
pub(super) fn native_controls(runtimes: &[Value]) -> (bool, bool) {
    let ready = |role: &str| runtimes.iter().any(|row| {
        (row["role"]["kind"] == role || (role=="desktop" && row["role"]["kind"]=="desktopFollower")) && row["online"] == true
            && row["readiness"]["stage"] == "ready" && row["schemaState"] == "ready"
    });
    (ready("desktop"), ready("cliDaemon"))
}

pub async fn observation(desktop_control: bool, cli_control: bool) -> Value {
    #[cfg(windows)]
    {
        let (desktop, cli) = tokio::join!(windows::desktop(), async {
            if cli_control {
                return None;
            }
            tokio::task::spawn_blocking(windows::cli_running)
                .await
                .ok()
                .flatten()
        });
        observed(desktop.as_deref(), cli, desktop_control, cli_control)
    }
    #[cfg(not(windows))]
    observed(None, None, desktop_control, cli_control)
}

#[cfg(windows)]
#[path = "runtime_catalog_windows.rs"]
mod windows;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn assistant_alone_cannot_claim_external_cli_control() {
        let row = |role| json!({"source":"cli","role":{"kind":role},"online":true,
            "readiness":{"stage":"ready"},"schemaState":"ready"});
        assert_eq!(native_controls(&[row("managedHost")]), (false, false));
        assert_eq!(native_controls(&[row("cliDaemon")]), (false, true));
    }

    #[test]
    fn desktop_detection_does_not_advertise_a_write_runtime() {
        let observation = observed(Some("26.1111.2228.0"), Some(true), false, false);
        assert_eq!(observation["desktop"]["state"], "detected");
        assert_eq!(observation["desktop"]["version"], "26.1111.2228.0");
        assert_eq!(observation["cli"]["state"], "detected");
        let observation = observed(None, Some(false), true, true);
        assert_eq!(observation["desktop"]["state"], "controllable");
        assert_eq!(observation["cli"]["state"], "controllable");
        let observation = observed(None, None, false, false);
        assert_eq!(observation["cli"]["state"], "unknown");
    }
}
