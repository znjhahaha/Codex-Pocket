//! Credential-free, bounded projection of version evidence for authenticated diagnostics.
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::Path;
#[derive(Clone)]
pub struct BridgeVersion {
    pub runtime_id: String,
    pub version: Option<String>,
}
pub fn snapshot(state: &Path, bridges: &[BridgeVersion]) -> Value {
    let exe = std::env::current_exe().ok();
    let local = std::env::var_os("LOCALAPPDATA").map(std::path::PathBuf::from);
    project(
        option_env!("CARGO_PKG_VERSION").unwrap_or("unknown"),
        exe.as_deref(),
        local.as_deref(),
        state,
        bridges,
    )
}
#[derive(Deserialize)]
struct Manifest {
    schema: u32,
    version: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Installed {
    version: u32,
    generation: String,
    release_hash: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Stamp {
    version: String,
    generation: Option<String>,
    pid: u32,
    control_protocol: u32,
}
#[derive(Deserialize)]
struct Supervisor {
    pid: u32,
    runtime: Stamp,
}
fn valid_version(value: &str) -> bool {
    if value.is_empty() || value.len() > 64 {
        return false;
    }
    let identifier = |text: &str, prerelease: bool| {
        text.split('.').all(|part| {
            !part.is_empty()
                && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
                && !(prerelease
                    && part.len() > 1
                    && part.starts_with('0')
                    && part.bytes().all(|b| b.is_ascii_digit()))
        })
    };
    let mut build = value.split('+');
    let base = build.next().unwrap_or_default();
    if build.next().is_some_and(|v| !identifier(v, false)) || build.next().is_some() {
        return false;
    }
    let (core, prerelease) = base
        .split_once('-')
        .map_or((base, None), |(c, p)| (c, Some(p)));
    if prerelease.is_some_and(|v| !identifier(v, true)) {
        return false;
    }
    let parts: Vec<_> = core.split('.').collect();
    parts.len() == 3
        && parts.iter().all(|p| {
            !p.is_empty()
                && p.bytes().all(|b| b.is_ascii_digit())
                && (p.len() == 1 || !p.starts_with('0'))
        })
}
fn valid_generation(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn component(evidence: &str, version: Option<&str>, generation: Option<&str>) -> Value {
    json!({"state":if version.is_some(){"observed"}else{"unknown"},"version":version,"generation":generation,"evidence":evidence})
}
fn manifest(path: &Path) -> Option<(String, String)> {
    let bytes = crate::usage_stats::read_projection(path, 1024 * 1024)?;
    let record: Manifest = serde_json::from_slice(&bytes).ok()?;
    if record.schema != 1 || !valid_version(&record.version) {
        return None;
    }
    Some((record.version, hex::encode(Sha256::digest(&bytes))))
}
fn installed(local: Option<&Path>) -> Option<(String, String)> {
    let root = local?.join("CodexPocket");
    let bytes =
        crate::usage_stats::read_projection(&root.join("desktop-install.json"), 1024 * 1024)?;
    // Deserialize only these three necessary fields. Paths, backups and settings are ignored.
    let record: Installed = serde_json::from_slice(&bytes).ok()?;
    if record.version != 3
        || !valid_generation(&record.generation)
        || record.release_hash != record.generation
    {
        return None;
    }
    let (version, generation) = manifest(
        &root
            .join("bin")
            .join(&record.generation)
            .join("windows-manifest.json"),
    )?;
    (generation == record.generation).then_some((version, generation))
}
fn supervisor(state: &Path) -> Option<Stamp> {
    let bytes =
        crate::usage_stats::read_projection(&state.join("desktop/supervisor.json"), 64 * 1024)?;
    let record: Supervisor = serde_json::from_slice(&bytes).ok()?;
    let stamp = record.runtime;
    (record.pid != 0
        && record.pid == stamp.pid
        && stamp.control_protocol == 1
        && valid_version(&stamp.version)
        && stamp.generation.as_deref().is_some_and(valid_generation))
    .then_some(stamp)
}
fn project(
    current_version: &str,
    exe: Option<&Path>,
    local: Option<&Path>,
    state: &Path,
    bridges: &[BridgeVersion],
) -> Value {
    let version = valid_version(current_version).then_some(current_version);
    let current_generation = exe
        .and_then(Path::parent)
        .and_then(|p| manifest(&p.join("windows-manifest.json")))
        .filter(|(v, _)| Some(v.as_str()) == version)
        .map(|(_, g)| g);
    let install = installed(local);
    let supervisor = supervisor(state);
    json!({"schema":1,
        "helper":component("current_runtime",version,current_generation.as_deref()),
        "installed":component("installed_manifest",install.as_ref().map(|(v,_)|v.as_str()),install.as_ref().map(|(_,g)|g.as_str())),
        "supervisor":component("supervisor_stamp",supervisor.as_ref().map(|s|s.version.as_str()),supervisor.as_ref().and_then(|s|s.generation.as_deref())),
        "bridges":bridges.iter().take(256).map(|b| {
            let version = b.version.as_deref().filter(|v|valid_version(v));
            json!({"runtime_id":b.runtime_id,"state":if version.is_some(){"observed"}else{"unknown"},"version":version,"evidence":"authenticated_runtime"})
        }).collect::<Vec<_>>(),
        "host":component("unavailable",None,None)})
}
#[cfg(test)]
#[path = "../tests/usage_backend/upstream_versions.rs"]
mod tests;
