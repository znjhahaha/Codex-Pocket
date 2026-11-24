//! Display-only saved configuration. Never inserted into the live settings cache.
use super::*;
use std::io::{BufReader, Read};

pub(crate) fn prove_thread(home: &Path, thread: &str) -> Result<()> {
    ensure!(crate::settings::valid_thread(thread), "Invalid thread identity");
    metadata(&open(home, "state_5.sqlite")?, thread)?;
    Ok(())
}

fn fields(data: &Value, cwd: &str) -> Value {
    let mut normalized = json!({});
    for (native, wire) in [
        ("model", "model"), ("effort", "effort"), ("reasoning_effort", "effort"),
        ("service_tier", "serviceTier"), ("approval_policy", "approvalPolicy"),
        ("approvals_reviewer", "approvalsReviewer"),
        ("active_permission_profile", "activePermissionProfile"),
        ("collaboration_mode", "collaborationMode"),
    ] {
        if let Some(value) = data.get(native) { normalized[wire] = value.clone(); }
    }
    if normalized.get("effort").is_none() {
        if let Some(effort) = data.pointer("/collaboration_mode/settings/reasoning_effort") {
            normalized["effort"] = effort.clone();
        }
    }
    if let Some(policy) = data.get("permission_profile").or_else(||data.get("sandbox_policy")) {
        // A custom policy that cannot be represented stays unknown. In particular,
        // never replace it with workspace/default permissions.
        if let Ok(policy) = super::resume::legacy_policy(policy, data["cwd"].as_str().unwrap_or(cwd)) {
            normalized["sandboxPolicy"] = policy;
        }
    }
    crate::settings::canonical(&normalized)
}

pub(crate) fn read(home: &Path, thread: &str) -> Result<Value> {
    ensure!(crate::settings::valid_thread(thread), "Invalid thread identity");
    let db = open(home, "state_5.sqlite")?;
    let meta = metadata(&db, thread)?;
    let cols = columns(&db, "threads", &["id"])?;
    let select = ["model", "reasoning_effort", "approval_mode", "sandbox_policy"]
        .map(|name| if cols.contains(name) { name } else { "NULL" });
    let sql = format!("SELECT {} FROM threads WHERE id=?1", select.join(","));
    let (model, effort, approval, sandbox): (Option<String>, Option<String>, Option<String>, Option<String>) =
        db.query_row(&sql, [thread], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?)))?;
    let mut index = json!({});
    if let Some(model) = model { index["model"] = json!(model); }
    if let Some(effort) = effort { index["effort"] = json!(effort); }
    if let Some(approval) = approval { index["approval_policy"] = serde_json::from_str(&approval).unwrap_or(json!(approval)); }
    if let Some(policy) = sandbox { index["sandbox_policy"] = serde_json::from_str(&policy)?; }
    let cwd = meta["cwd"].as_str().unwrap_or("");
    let mut saved = fields(&index, cwd);
    let raw_path = meta["path"].as_str().unwrap_or("");
    if !raw_path.is_empty() && Path::new(raw_path).exists() {
        let path = super::rollout_path(home, thread)?;
        let file = std::fs::File::open(&path)?;
        crate::storage::verify_physical_file(&path, &file)?;
        let length = file.metadata()?.len();
        let mut reader = BufReader::new(file.take(length));
        let mut line = Vec::new();
        super::resume::read_settings_line(&mut reader, &mut line)?;
        let header: Value = serde_json::from_slice(&line)?;
        ensure!(line.ends_with(b"\n") && header["type"] == "session_meta" && header["payload"]["id"] == thread,
            "Settings rollout identity mismatch");
        let mut applied = false;
        loop {
            line.clear();
            if super::resume::read_settings_line(&mut reader, &mut line)? == 0 || !line.ends_with(b"\n") { break; }
            let record: Value = serde_json::from_slice(&line)?;
            if record["type"] == "session_meta" {
                ensure!(record["payload"]["id"] == thread, "Settings rollout owner changed");
            }
            let is_applied = record["type"] == "event_msg" && record["payload"]["type"] == "thread_settings_applied";
            let data = if is_applied { &record["payload"]["thread_settings"] }
                else if record["type"] == "turn_context" && !applied { &record["payload"] }
                else { continue };
            let owner = if is_applied { record["payload"].get("thread_id") } else { data.get("thread_id") };
            if owner.is_some_and(|id| !id.is_null() && id != thread) { continue; }
            // Applied snapshots describe future turns; a later turn_context may
            // still describe an in-flight turn. Do not roll back the snapshot.
            if is_applied { applied = true; }
            saved = fields(data, cwd);
        }
    }
    Ok(saved)
}
