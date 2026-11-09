//! Evidence from the connected native executable's exported protocol, never a version guess.
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};

pub const PERMISSION_PROTOCOL: u64 = 1;
/// Status belongs to the authenticated bridge connection, not a native version guess.
pub fn permission_discovery(status: &Value) -> (&'static str, &'static str) {
    if status["initialized"] != true
        || status["schemaPending"] == true
        || status["schemaState"] == "initializing"
    {
        ("initializing", "capability_initializing")
    } else if status["schemaState"] == "error" {
        ("error", "native_schema_failed")
    } else if status["permissionProtocol"]
        .as_u64()
        .is_none_or(|p| p < PERMISSION_PROTOCOL)
    {
        ("update_required", "bridge_update_required")
    } else if status["schemaState"] != "ready" {
        ("error", "native_schema_failed")
    } else {
        ("ready", "")
    }
}

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
                // New native schemas wrap the quota params in anyOf(object, null).
                let params = if method == "account/rateLimits/read" {
                    params["anyOf"]
                        .as_array()
                        .and_then(|a| {
                            a.iter().find_map(|p| {
                                let resolved = p["$ref"]
                                    .as_str()
                                    .and_then(|s| s.strip_prefix('#'))
                                    .and_then(|s| schema.pointer(s))
                                    .unwrap_or(p);
                                (resolved["type"] == "object").then_some(resolved)
                            })
                        })
                        .unwrap_or(params)
                } else {
                    params
                };
                // Settings capability is independent of model Fast support.
                if let Some(properties) = params["properties"].as_object() {
                    let mut fields: BTreeSet<String> = properties.keys().cloned().collect();
                    if method == "thread/settings/update"
                        && schema_value_contains(
                            schema,
                            &params["properties"]["approvalsReviewer"],
                            "auto_review",
                            0,
                        )
                    {
                        fields.insert("$autoReview".into());
                    }
                    result.0.insert(method.into(), fields);
                } else if params["type"] == "object"
                    || (method == "config/mcpServer/reload" && params["type"] == "null")
                    || (method == "account/rateLimits/read"
                        && (params["type"] == "null" || raw.is_null()))
                {
                    result.0.insert(method.into(), BTreeSet::new());
                }
            }
        }
        result
    }
    pub fn supports(&self, method: &str, params: &Value) -> bool {
        self.0.get(method).is_some_and(|fields| {
            if method == "config/mcpServer/reload" {
                return params.is_null() && fields.is_empty();
            }
            params
                .as_object()
                .is_some_and(|p| p.keys().all(|k| !k.starts_with('$') && fields.contains(k)))
        })
    }
    pub fn methods(&self) -> Vec<String> {
        self.0.keys().cloned().collect()
    }
}

fn schema_value_contains(root: &Value, value: &Value, expected: &str, depth: usize) -> bool {
    if depth > 12 {
        return false;
    }
    if value["enum"]
        .as_array()
        .is_some_and(|a| a.iter().any(|v| v == expected))
    {
        return true;
    }
    if let Some(target) = value["$ref"]
        .as_str()
        .and_then(|r| r.strip_prefix('#'))
        .and_then(|r| root.pointer(r))
    {
        return schema_value_contains(root, target, expected, depth + 1);
    }
    ["anyOf", "oneOf", "allOf"].iter().any(|key| {
        value[*key].as_array().is_some_and(|a| {
            a.iter()
                .any(|v| schema_value_contains(root, v, expected, depth + 1))
        })
    })
}

pub fn allowed_method(m: &str) -> bool {
    matches!(
        m,
        "account/rateLimits/read"
            | "config/read"
            | "config/value/write"
            | "config/mcpServer/reload"
            | "mcpServerStatus/list"
            | "skills/config/write"
            | "plugin/installed"
            | "app/installed"
            | "thread/revert"
            | "thread/list"
            | "thread/read"
            | "thread/loaded/list"
            | "thread/unsubscribe"
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
            | "thread/revert"
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
    fn v054_apps_require_exported_read_capability_without_new_writes() {
        let c = Capabilities::from_schema(&json!({"oneOf":[{"properties":{"method":{"enum":["app/installed"]},"params":{"type":"object","properties":{"forceRefresh":{"type":"boolean"},"threadId":{"type":["string","null"]}}}}}]}));
        assert!(c.supports("app/installed", &json!({"forceRefresh":false})));
        assert!(!Capabilities::default().supports("app/installed", &json!({"forceRefresh":false})));
        assert!(!allowed_method("app/toggle"));
        assert!(!allowed_method("app/read"));
        assert!(!allowed_method("app/list"));
    }
    #[test]
    fn v054_reload_uses_actual_null_native_schema() {
        let caps = Capabilities::from_schema(
            &json!({"oneOf":[{"properties":{"method":{"enum":["config/mcpServer/reload"]},"params":{"type":"null"}}}]}),
        );
        assert!(caps.supports("config/mcpServer/reload", &Value::Null));
        assert!(!caps.supports("config/mcpServer/reload", &json!({"path":"private"})));
    }
    #[test]
    fn v052_discovery_distinguishes_pending_failed_and_old_bridge() {
        assert_eq!(
            permission_discovery(&json!({"initialized":true,"schemaPending":true})).0,
            "initializing"
        );
        assert_eq!(
            permission_discovery(
                &json!({"initialized":true,"schemaState":"error","permissionProtocol":1})
            )
            .0,
            "error"
        );
        assert_eq!(
            permission_discovery(&json!({"initialized":true,"schemaPending":false})).0,
            "update_required"
        );
        assert_eq!(
            permission_discovery(
                &json!({"initialized":true,"schemaState":"ready","permissionProtocol":1})
            )
            .0,
            "ready"
        );
    }
    #[test]
    fn quota_requires_schema_evidence_and_accepts_nullable_parameter_schemas() {
        assert!(!Capabilities::default().supports("account/rateLimits/read", &json!({})));
        for params in [
            json!({"type":"null"}),
            json!({"anyOf":[{"$ref":"#/definitions/Quota"},{"type":"null"}]}),
        ] {
            let schema = json!({"oneOf":[{"properties":{"method":{"enum":["account/rateLimits/read"]},"params":params}}],"definitions":{"Quota":{"type":"object","properties":{"excludeResetCreditDetails":{"type":"boolean"}}}}});
            let c = Capabilities::from_schema(&schema);
            assert!(c.supports("account/rateLimits/read", &json!({})));
            assert!(!c.supports("account/rateLimits/read", &json!({"apiKey":"secret"})));
        }
        assert!(!allowed_method("account/logout"));
        assert!(allowed_method("config/value/write")); // Internal bridge evidence; paired-device access still denies this method.
    }
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
        assert!(c.methods().contains(&"thread/settings/update".to_owned()));
        assert!(!c.supports(
            "thread/settings/update",
            &json!({"threadId":"t","serviceTier":"fast"})
        ));
    }
    #[test]
    fn v051_auto_reviewer_requires_exported_value_not_just_field() {
        for offered in [true, false] {
            let schema = json!({"oneOf":[{"properties":{"method":{"enum":["thread/settings/update"]},"params":{"properties":{"threadId":{},"approvalPolicy":{},"sandboxPolicy":{},"approvalsReviewer":{"anyOf":[{"$ref":"#/definitions/Reviewer"},{"type":"null"}]}}}}}],"definitions":{"Reviewer":{"enum":if offered{json!(["user","auto_review"])}else{json!(["user"])}}}});
            let c = Capabilities::from_schema(&schema);
            assert_eq!(
                c.0["thread/settings/update"].contains("$autoReview"),
                offered
            );
            assert!(c.supports("thread/settings/update",&json!({"threadId":"t","approvalPolicy":"never","approvalsReviewer":"user","sandboxPolicy":{"type":"dangerFullAccess"}})));
        }
    }
}
