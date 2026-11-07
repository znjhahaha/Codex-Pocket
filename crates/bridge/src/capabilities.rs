//! Evidence from the connected native executable's exported protocol, never a version guess.
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug, Default, serde::Serialize, serde::Deserialize)]
pub struct Capabilities(pub BTreeMap<String, BTreeSet<String>>);
impl Capabilities {
    pub fn from_schema(schema: &Value) -> Self {
        let mut result = Self::default();
        if let Some(variants) = schema["oneOf"].as_array() {
            for variant in variants {
                let Some(method) = variant["properties"]["method"]["enum"][0].as_str() else {
                    continue;
                };
                if !allowed_method(method) {
                    continue;
                }
                let raw = &variant["properties"]["params"];
                let params = raw["$ref"]
                    .as_str()
                    .and_then(|s| s.strip_prefix('#'))
                    .and_then(|s| schema.pointer(s))
                    .unwrap_or(raw);
                if method == "thread/settings/update"
                    && params["properties"].get("serviceTier").is_none()
                {
                    continue;
                }
                if let Some(properties) = params["properties"].as_object() {
                    result
                        .0
                        .insert(method.into(), properties.keys().cloned().collect());
                } else if params["type"] == "object" {
                    result.0.insert(method.into(), BTreeSet::new());
                }
            }
        }
        result
    }
    pub fn supports(&self, method: &str, params: &Value) -> bool {
        self.0.get(method).is_some_and(|fields| {
            params
                .as_object()
                .is_some_and(|p| p.keys().all(|k| fields.contains(k)))
        })
    }
    pub fn methods(&self) -> Vec<String> {
        self.0.keys().cloned().collect()
    }
}

pub fn allowed_method(m: &str) -> bool {
    matches!(
        m,
        "thread/list"
            | "thread/read"
            | "thread/loaded/list"
            | "thread/queue/list"
            | "thread/queue/add"
            | "thread/queue/update"
            | "thread/queue/delete"
            | "thread/queue/reorder"
            | "thread/queue/start"
            | "thread/fork"
            | "thread/goal/get"
            | "thread/goal/set"
            | "thread/goal/clear"
            | "thread/compact/start"
            | "thread/settings/update"
            | "skills/list"
            | "plugin/list"
            | "model/list"
            | "collaborationMode/list"
            | "thread/start"
            | "thread/resume"
            | "thread/name/set"
            | "thread/archive"
            | "thread/unarchive"
            | "thread/turns/list"
            | "thread/items/list"
            | "turn/start"
            | "turn/steer"
            | "turn/interrupt"
    )
}
pub fn advanced(m: &str) -> bool {
    matches!(
        m,
        "thread/queue/add"
            | "thread/queue/update"
            | "thread/queue/delete"
            | "thread/queue/reorder"
            | "thread/queue/start"
            | "thread/fork"
            | "thread/goal/get"
            | "thread/goal/set"
            | "thread/goal/clear"
            | "thread/compact/start"
            | "thread/settings/update"
            | "skills/list"
            | "plugin/list"
    )
}

/// Read-only schema export in a disposable directory. Never starts a conversation.
pub async fn discover(binary: &std::path::Path) -> anyhow::Result<Capabilities> {
    use anyhow::{Context, bail};
    let before = std::fs::metadata(binary)?;
    if !before.is_file() {
        bail!("Native executable unavailable");
    }
    let dir = std::env::temp_dir().join(format!("pocket-schema-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&dir)?;
    struct Cleanup(std::path::PathBuf);
    impl Drop for Cleanup {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    let _cleanup = Cleanup(dir.clone());
    let mut cmd = tokio::process::Command::new(binary);
    cmd.args([
        "app-server",
        "generate-json-schema",
        "--experimental",
        "--out",
    ])
    .arg(&dir)
    .stdin(std::process::Stdio::null())
    .stdout(std::process::Stdio::null())
    .stderr(std::process::Stdio::null())
    .kill_on_drop(true);
    #[cfg(windows)]
    cmd.creation_flags(0x08000000);
    let status = tokio::time::timeout(std::time::Duration::from_secs(15), cmd.status()).await??;
    if !status.success() {
        bail!("Native schema export unavailable");
    }
    let after = std::fs::metadata(binary)?;
    if before.len() != after.len() || before.modified()? != after.modified()? {
        bail!("Native executable changed during schema export");
    }
    let file = dir.join("ClientRequest.json");
    if std::fs::metadata(&file)?.len() > 16 * 1024 * 1024 {
        bail!("Native schema exceeds limit");
    }
    let value: Value = serde_json::from_slice(&std::fs::read(file)?)?;
    let c = Capabilities::from_schema(&value);
    c.0.get("thread/read").context("Native schema incomplete")?;
    Ok(c)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn v02_schema_evidence_requires_each_actual_method_and_field() {
        let schema: Value =
            serde_json::from_str(include_str!("../tests/fixtures/ClientRequest.json")).unwrap();
        let c = Capabilities::from_schema(&schema);
        assert!(c.supports(
            "thread/fork",
            &json!({"threadId":"t","beforeTurnId":"u","attachToSource":true})
        ));
        assert!(c.supports(
            "thread/queue/reorder",
            &json!({"threadId":"t","queuedSubmissionIds":[]})
        ));
        assert!(!c.supports("thread/fork", &json!({"inventedField":true})));
        assert!(!c.supports("command/exec", &json!({})));
        assert!(!Capabilities::default().supports("thread/fork", &json!({})));
        assert!(Capabilities::from_schema(&json!({})).methods().is_empty());
    }
    #[test]
    fn v02_older_fork_schema_does_not_claim_new_fields() {
        let c = Capabilities::from_schema(
            &json!({"oneOf":[{"properties":{"method":{"enum":["thread/fork"]},"params":{"$ref":"#/definitions/Fork"}}}],"definitions":{"Fork":{"properties":{"threadId":{"type":"string"}}}}}),
        );
        assert!(c.supports("thread/fork", &json!({"threadId":"t"})));
        assert!(!c.supports("thread/fork", &json!({"threadId":"t","beforeTurnId":"u"})));
    }
    #[test]
    fn v02_method_name_without_service_tier_is_not_fast_evidence() {
        let c = Capabilities::from_schema(
            &json!({"oneOf":[{"properties":{"method":{"enum":["thread/settings/update"]},"params":{"type":"object","properties":{"threadId":{"type":"string"}}}}}]}),
        );
        assert!(!c.methods().contains(&"thread/settings/update".to_owned()));
    }
}
