//! Resolve a package upgrade only for a new launch; never touch an active child.
use std::path::{Path, PathBuf};
fn package(path: &Path) -> Option<(String, Vec<u64>, String)> {
    let name = path.ancestors().find_map(|p| {
        let name = p.file_name()?.to_str()?;
        name.starts_with("OpenAI.Codex_").then_some(name)
    })?;
    let fields: Vec<_> = name.split('_').collect();
    if fields.len() != 5 || fields[0] != "OpenAI.Codex" {
        return None;
    }
    let version = fields[1]
        .split('.')
        .map(str::parse)
        .collect::<Result<Vec<u64>, _>>()
        .ok()?;
    if version.len() != 4 {
        return None;
    }
    Some((
        fields[0].into(),
        version,
        format!("{}_{}", fields[2], fields[4]),
    ))
}
pub fn select(configured: &Path, candidates: &[PathBuf], bridge: &Path) -> anyhow::Result<PathBuf> {
    let bridge = bridge.canonicalize()?;
    let usable = |p: &Path| p.is_file() && p.canonicalize().is_ok_and(|v| v != bridge);
    if let Some((name, current_version, family)) = package(configured) {
        let mut matches: Vec<_> = candidates
            .iter()
            .filter_map(|p| {
                let (n, v, f) = package(p)?;
                (n == name && f == family && usable(p)).then_some((v, p.as_path()))
            })
            .collect();
        if usable(configured) {
            matches.push((current_version, configured));
        }
        matches.sort_by(|a, b| a.0.cmp(&b.0));
        if let Some((_, path)) = matches.last() {
            return Ok((*path).to_path_buf());
        }
    }
    if usable(configured) {
        return Ok(configured.into());
    }
    anyhow::bail!("Original native binary unavailable; refresh desktop installation discovery")
}

pub async fn resolve(configured: &Path, bridge: &Path) -> anyhow::Result<PathBuf> {
    let mut candidates = Vec::new();
    // Ask the current user's package registry. Scanning WindowsApps could select
    // staged/unregistered packages belonging to another user.
    #[cfg(windows)]
    if package(configured).is_some() {
        let mut cmd = tokio::process::Command::new("powershell.exe");
        cmd.args(["-NoProfile","-NonInteractive","-Command","@(Get-AppxPackage -Name OpenAI.Codex | Select-Object -ExpandProperty InstallLocation) | ConvertTo-Json -Compress"])
            .stdin(std::process::Stdio::null()).stderr(std::process::Stdio::null()).kill_on_drop(true).creation_flags(0x08000000);
        if let Ok(Ok(out)) =
            tokio::time::timeout(std::time::Duration::from_secs(10), cmd.output()).await
            && out.status.success()
            && out.stdout.len() < 65536
            && let Ok(v) = serde_json::from_slice::<serde_json::Value>(&out.stdout)
        {
            let values = v.as_array().cloned().unwrap_or_else(|| vec![v]);
            for v in values {
                if let Some(p) = v.as_str() {
                    candidates.push(PathBuf::from(p).join("app/resources/codex.exe"));
                }
            }
        }
    }
    select(configured, &candidates, bridge)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn v02_upgrade_prefers_new_package_and_never_selects_bridge() {
        let root = std::env::temp_dir().join(format!("pocket-path-{}", uuid::Uuid::new_v4()));
        let old = root.join("OpenAI.Codex_1.9.0.0_x64__same/app/resources/codex.exe");
        let new = root.join("OpenAI.Codex_1.10.0.0_x64__same/app/resources/codex.exe");
        let other = root.join("Other_99.0.0.0_x64__same/app/resources/codex.exe");
        let bridge = root.join("bridge.exe");
        for p in [&old, &new, &other, &bridge] {
            std::fs::create_dir_all(p.parent().unwrap()).unwrap();
            std::fs::write(p, b"fixture").unwrap();
        }
        let candidates = vec![old.clone(), new.clone(), other, bridge.clone()];
        assert_eq!(select(&old, &candidates, &bridge).unwrap(), new);
        assert_eq!(
            select(&new, std::slice::from_ref(&old), &bridge).unwrap(),
            new,
            "never downgrade a still valid configured package"
        );
        std::fs::remove_file(&old).unwrap();
        assert_eq!(select(&old, &candidates, &bridge).unwrap(), new);
        assert!(select(&bridge, std::slice::from_ref(&bridge), &bridge).is_err());
        let resolved = root.canonicalize().unwrap();
        assert!(resolved.starts_with(std::env::temp_dir().canonicalize().unwrap()));
        std::fs::remove_dir_all(resolved).unwrap();
    }
}
