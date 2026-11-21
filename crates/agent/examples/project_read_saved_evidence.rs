//! Read-only diagnostics from a saved, sanitized source-permission-read.json.
//! No Broker, Actor, database, sockets, native RPC, or child processes are used.
use anyhow::{Context, Result};
use pocket_agent::{capabilities, settings::SettingsCache, workspace::Workspace};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{fs, path::PathBuf};

#[cfg(windows)]
fn component_reads(path: &std::path::Path) -> Vec<Value> {
    use std::os::windows::fs::{MetadataExt, OpenOptionsExt};
    use std::path::Component;
    let mut current = PathBuf::new();
    let mut result = Vec::new();
    for part in path.components() {
        current.push(part);
        if matches!(part, Component::Prefix(_)) {
            continue;
        }
        let metadata = match fs::symlink_metadata(&current) {
            Ok(value) => value,
            Err(error) => {
                result.push(json!({"path":current,"stage":"symlink_metadata",
                    "error":error.to_string(),"rawOsError":error.raw_os_error()}));
                break;
            }
        };
        // Match Workspace::guard exactly, and immediately release each handle.
        // This isolates the failing component; Workspace::new above tests the
        // actual production implementation while retaining all its handles.
        let open = fs::OpenOptions::new()
            .read(true)
            .share_mode(1 | 2)
            .custom_flags(0x00200000 | 0x02000000)
            .open(&current);
        let mut row = json!({"path":current,"reparse":metadata.file_attributes() & 0x400 != 0,
            "directory":metadata.is_dir(),"stage":"guard_open","ok":open.is_ok()});
        if let Err(error) = open {
            row["error"] = json!(error.to_string());
            row["rawOsError"] = json!(error.raw_os_error());
        }
        result.push(row);
    }
    result
}

fn main() -> Result<()> {
    let evidence = PathBuf::from(std::env::args_os().nth(1).context("Pass saved evidence JSON")?);
    let evidence_bytes = fs::read(&evidence)?;
    let value: Value = serde_json::from_slice(&evidence_bytes)?;
    let thread = value["thread"]["id"].as_str().context("Missing saved thread ID")?;
    let cwd = PathBuf::from(value["thread"]["cwd"].as_str().context("Missing saved cwd")?);
    let snapshot = &value["settings"];
    anyhow::ensure!(snapshot["threadId"] == thread && snapshot["known"] == true,
        "Saved settings identity/known mismatch");
    let mut fresh = SettingsCache::default();
    fresh.snapshot(thread, &snapshot["settings"], 1);
    let mut broker_cache = SettingsCache::default();
    let checkpoint = broker_cache.checkpoint();
    broker_cache.snapshot(thread, &snapshot["settings"], checkpoint);
    let observation = broker_cache.prepare("thread/read", &json!({"threadId":thread})).unwrap();
    broker_cache.accepted(observation, &json!({"thread":value["thread"]}));

    // Synthetic policy controls, using only a clone of the saved settings.
    // These never update native state or the saved evidence.
    let mut policy_controls = Vec::new();
    for mode in ["readOnly", "workspaceWrite"] {
        let mut settings = snapshot["settings"].clone();
        settings["sandboxPolicy"]["type"] = json!(mode);
        let mut cache = SettingsCache::default();
        cache.snapshot(thread, &settings, 1);
        policy_controls.push(json!({"type":mode,"synthetic":true,
            "proven":cache.permissions_proven_since(thread, 0)}));
    }
    let mut partial = snapshot["settings"].clone();
    partial.as_object_mut().unwrap().remove("sandboxPolicy");
    let mut partial_cache = SettingsCache::default();
    partial_cache.snapshot(thread, &partial, 1);

    let workspace = match Workspace::new(vec![cwd.clone()]) {
        Ok(workspace) => json!({"ok":true,"roots":workspace.roots()}),
        Err(error) => json!({"ok":false,"error":format!("{error:#}"),
            "rawOsError":error.downcast_ref::<std::io::Error>().and_then(|e| e.raw_os_error())}),
    };
    let (protocol, reason) = capabilities::permission_discovery(&value["status"]);
    let mut output = json!({
        "evidence":evidence,"evidenceSha256":hex::encode(Sha256::digest(&evidence_bytes)),
        "threadId":thread,"cwd":cwd,
        "statusBytes":value["statusBytes"],"savedStatusPermissionDiscovery":{"state":protocol,"reason":reason},
        "nativeCapabilitiesCaptured":value["status"].get("nativeCapabilities").is_some(),
        "freshPermissionsProvenSince0":fresh.permissions_proven_since(thread, 0),
        "freshCanonicalSettings":fresh.read(thread)["settings"],
        "brokerCacheAfterMetadataOnlyReadProven":broker_cache.permissions_proven_since(thread, checkpoint.saturating_sub(1)),
        "syntheticPolicyControls":policy_controls,
        "syntheticMissingSandboxRejected":!partial_cache.permissions_proven_since(thread, 0),
        "workspace":workspace,
        "liveRpcCalls":0,"databaseAccesses":0,"modelCalls":0
    });
    #[cfg(windows)]
    { output["components"] = json!(component_reads(&cwd)); }
    println!("{}", serde_json::to_string_pretty(&output)?);
    Ok(())
}
