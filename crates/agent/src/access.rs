use serde_json::Value;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AccessError {
    Unsupported,
    InvalidParams(&'static str),
}
impl From<&'static str> for AccessError {
    fn from(message: &'static str) -> Self {
        Self::InvalidParams(message)
    }
}
impl std::fmt::Display for AccessError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Unsupported => "This native operation is not exposed to paired devices",
            Self::InvalidParams(message) => message,
        })
    }
}
impl std::error::Error for AccessError {}
impl AccessError {
    pub fn into_request_error(self) -> anyhow::Error {
        use crate::error::{Code, classify};
        classify(
            self.into(),
            match self {
                Self::Unsupported => Code::Unsupported,
                Self::InvalidParams(_) => Code::InvalidParams,
            },
        )
    }
}
pub fn validate(method: &str, params: &Value) -> Result<bool, AccessError> {
    let (fields, mutation): (&[&str], bool) = match method {
        "thread/list" => (
            &[
                "cursor",
                "limit",
                "sortKey",
                "sortDirection",
                "archived",
                "searchTerm",
                "cwd",
                "sourceKinds",
                "modelProviders",
            ],
            false,
        ),
        "thread/read" => (&["threadId", "includeTurns"], false),
        "thread/loaded/list" => (&["cursor", "limit"], false),
        "thread/queue/list" => (&["threadId", "cursor", "limit"], false),
        "thread/queue/add" => (&["threadId", "clientUserMessageId", "input"], true),
        "thread/queue/update" => (&["threadId", "queuedSubmissionId", "input"], true),
        "thread/queue/delete" => (&["threadId", "queuedSubmissionId"], true),
        "thread/queue/reorder" => (&["threadId", "queuedSubmissionIds"], true),
        "thread/queue/start" => (&["threadId", "queuedSubmissionId"], true),
        "thread/fork" => (
            &["threadId", "attachToSource", "beforeTurnId", "excludeTurns"],
            true,
        ),
        "thread/goal/get" => (&["threadId"], false),
        "thread/goal/set" => (
            &["threadId", "objective", "status", "tokenBudget", "origin"],
            true,
        ),
        "thread/goal/clear" => (&["threadId", "origin"], true),
        "thread/compact/start" => (&["threadId"], true),
        "thread/settings/update" => (&["threadId", "serviceTier"], true),
        // Cwds/catalog flags are injected by the broker from native thread proof.
        "skills/list" | "plugin/list" => (&[], false),
        "thread/turns/list" => (&["threadId", "cursor", "limit", "sortDirection", "itemsView"], false),
        "thread/items/list" => (
            &["threadId", "turnId", "cursor", "limit", "sortDirection"],
            false,
        ),
        "model/list" => (&["cursor", "limit", "includeHidden"], false),
        "collaborationMode/list" => (&[], false),
        "thread/start" => (&["cwd", "model"], true),
        "thread/resume" => (&["threadId", "excludeTurns"], true),
        "thread/name/set" => (&["threadId", "name"], true),
        "thread/archive" | "thread/unarchive" => (&["threadId"], true),
        "turn/start" => (
            &[
                "threadId",
                "input",
                "model",
                "effort",
                "clientUserMessageId",
                "collaborationMode",
            ],
            true,
        ),
        "turn/steer" => (
            &["threadId", "input", "expectedTurnId", "clientUserMessageId"],
            true,
        ),
        "turn/interrupt" => (&["threadId", "turnId"], true),
        _ => return Err(AccessError::Unsupported),
    };
    let object = params.as_object().ok_or("Parameters must be an object")?;
    if object.keys().any(|k| !fields.contains(&k.as_str())) {
        return Err(
            "Parameters would change native configuration, permissions or unsupported state".into(),
        );
    }
    if method.starts_with("thread/queue/")
        || matches!(
            method,
            "thread/fork"
                | "thread/goal/get"
                | "thread/goal/set"
                | "thread/goal/clear"
                | "thread/compact/start"
                | "thread/settings/update"
        )
    {
        required_string(params, "threadId")?;
    }
    match method {
        "thread/queue/add" => {
            required_string(params, "clientUserMessageId")?;
            if !params["input"].is_array() {
                return Err("Missing input".into());
            }
        }
        "thread/queue/update" | "thread/queue/delete" => {
            required_string(params, "queuedSubmissionId")?;
            if method.ends_with("update") && !params["input"].is_array() {
                return Err("Missing input".into());
            }
        }
        "thread/queue/start" => {
            if !params["queuedSubmissionId"].is_null() {
                required_string(params, "queuedSubmissionId")?;
            }
        }
        "thread/queue/reorder" => {
            let ids = params["queuedSubmissionIds"]
                .as_array()
                .ok_or("Missing queue order")?;
            let mut unique = std::collections::BTreeSet::new();
            if ids.len() > 1024
                || ids.iter().any(|id| {
                    id.as_str()
                        .is_none_or(|s| s.is_empty() || s.len() > 4096 || !unique.insert(s))
                })
            {
                return Err("Invalid queue order".into());
            }
        }
        "thread/fork" => {
            for key in ["attachToSource", "excludeTurns"] {
                if params.get(key).is_some_and(|v| !v.is_boolean()) {
                    return Err("Invalid fork flag".into());
                }
            }
            if !params["beforeTurnId"].is_null() {
                required_string(params, "beforeTurnId")?;
            }
        }
        "thread/goal/set" | "thread/goal/clear" => {
            if params["origin"] != "user" {
                return Err("Explicit user goal provenance required".into());
            }
            if !params["objective"].is_null()
                && params["objective"]
                    .as_str()
                    .is_none_or(|s| s.is_empty() || s.len() > 65536)
            {
                return Err("Invalid objective".into());
            }
            if !params["status"].is_null()
                && !matches!(
                    params["status"].as_str(),
                    Some(
                        "active"
                            | "paused"
                            | "blocked"
                            | "usageLimited"
                            | "budgetLimited"
                            | "complete"
                    )
                )
            {
                return Err("Invalid goal status".into());
            }
            if !params["tokenBudget"].is_null()
                && params["tokenBudget"].as_i64().is_none_or(|n| n <= 0)
            {
                return Err("Invalid token budget".into());
            }
        }
        "thread/settings/update" => {
            if !object.contains_key("serviceTier")
                || !(params["serviceTier"].is_null() || params["serviceTier"] == "fast")
            {
                return Err("Only fast service tier or null is allowed".into());
            }
        }
        _ => {}
    }
    if let Some(mode) = params.get("collaborationMode") {
        let settings = &mode["settings"];
        if !matches!(mode["mode"].as_str(), Some("plan" | "default"))
            || mode
                .as_object()
                .is_none_or(|m| m.keys().any(|k| k != "mode" && k != "settings"))
            || settings.as_object().is_none_or(|s| {
                s.keys().any(|k| {
                    !matches!(
                        k.as_str(),
                        "model" | "reasoning_effort" | "developer_instructions"
                    )
                })
            })
            || !settings["developer_instructions"].is_null()
            || settings["model"].as_str().is_none_or(str::is_empty)
        {
            return Err("Invalid native collaboration preset".into());
        }
    }
    if let Some(input) = params.get("input") {
        let items = input.as_array().ok_or("input must be an array")?;
        if items.len() > 16 {
            return Err("Too many input items".into());
        }
        for item in items {
            let allowed: &[&str] = match item["type"].as_str() {
                Some("text") => &["type", "text", "text_elements"],
                Some("pocketImage") => &["type", "attachmentId"],
                Some("skill" | "mention") => &["type", "name", "path"],
                _ => return Err("Unsupported input type".into()),
            };
            if item
                .as_object()
                .is_none_or(|o| o.keys().any(|k| !allowed.contains(&k.as_str())))
            {
                return Err("Invalid input fields".into());
            }
            match item["type"].as_str() {
                Some("text") => {
                    if item["text"].as_str().is_none_or(|s| s.len() > 1024 * 1024) {
                        return Err("Invalid text input".into());
                    }
                }
                Some("pocketImage") => {
                    if item["attachmentId"].as_str().is_none_or(|s| s.len() > 128) {
                        return Err("Invalid attachment".into());
                    }
                }
                Some("skill" | "mention") => {
                    required_string(item, "name")?;
                    required_string(item, "path")?;
                }
                _ => {
                    return Err("Only text and uploaded Pocket attachments can be submitted".into());
                }
            }
        }
    }
    Ok(mutation)
}
fn required_string(value: &Value, key: &str) -> Result<(), AccessError> {
    if value[key]
        .as_str()
        .is_none_or(|s| s.is_empty() || s.len() > 4096 || s.chars().any(char::is_control))
    {
        return Err("Missing or invalid native identifier".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn rejects_auth_config_and_arbitrary_shell() {
        for m in [
            "initialize",
            "account/login/start",
            "account/logout",
            "config/value/write",
            "command/exec",
            "thread/delete",
        ] {
            assert!(validate(m, &json!({})).is_err(), "{m}");
        }
    }
    #[test]
    fn prohibits_mobile_permission_changes() {
        for key in [
            "approvalPolicy",
            "sandboxPolicy",
            "config",
            "baseInstructions",
            "modelProvider",
        ] {
            let mut p = json!({"threadId":"t","input":[]});
            p[key] = json!("anything");
            assert!(validate("turn/start", &p).is_err(), "{key}");
        }
    }
    #[test]
    fn queue_listing_is_read_only_and_requires_native_mutation_fields() {
        assert_eq!(
            validate(
                "thread/queue/list",
                &json!({"threadId":"t","limit":50,"cursor":null})
            ),
            Ok(false)
        );
        assert!(validate("thread/queue/list", &json!({"threadId":"t","input":[]})).is_err());
        for method in [
            "thread/queue/add",
            "thread/queue/delete",
            "thread/queue/update",
        ] {
            assert!(validate(method, &json!({"threadId":"t"})).is_err());
        }
    }
    #[test]
    fn v02_exact_native_mutations_and_reference_inputs() {
        for (method, p) in [
            (
                "thread/queue/add",
                json!({"threadId":"t","clientUserMessageId":"m","input":[{"type":"text","text":"hi"}]}),
            ),
            (
                "thread/queue/update",
                json!({"threadId":"t","queuedSubmissionId":"q","input":[]}),
            ),
            (
                "thread/queue/delete",
                json!({"threadId":"t","queuedSubmissionId":"q"}),
            ),
            (
                "thread/queue/reorder",
                json!({"threadId":"t","queuedSubmissionIds":["b","a"]}),
            ),
            ("thread/queue/start", json!({"threadId":"t"})),
            (
                "thread/fork",
                json!({"threadId":"t","attachToSource":true,"beforeTurnId":"u","excludeTurns":true}),
            ),
            (
                "thread/goal/set",
                json!({"threadId":"t","objective":"implement","origin":"user"}),
            ),
            ("thread/goal/clear", json!({"threadId":"t","origin":"user"})),
            ("thread/compact/start", json!({"threadId":"t"})),
            (
                "thread/settings/update",
                json!({"threadId":"t","serviceTier":"fast"}),
            ),
            (
                "turn/start",
                json!({"threadId":"t","input":[{"type":"skill","name":"review","path":"C:/skills/review/SKILL.md"}]}),
            ),
        ] {
            assert_eq!(validate(method, &p), Ok(true), "{method}");
        }
        assert_eq!(
            validate("thread/goal/get", &json!({"threadId":"t"})),
            Ok(false)
        );
    }
    #[test]
    fn v02_rejects_invalid_or_privilege_changing_payloads() {
        for (method, p) in [
            ("thread/queue/add", json!({"threadId":"t","input":[]})),
            (
                "thread/queue/reorder",
                json!({"threadId":"t","queuedSubmissionIds":["q","q"]}),
            ),
            (
                "thread/fork",
                json!({"threadId":"t","path":"C:/private","attachToSource":true}),
            ),
            (
                "thread/fork",
                json!({"threadId":"t","attachToSource":"true"}),
            ),
            (
                "thread/goal/set",
                json!({"threadId":"t","objective":"x","origin":"automatic"}),
            ),
            (
                "thread/settings/update",
                json!({"threadId":"t","serviceTier":"arbitrary"}),
            ),
            (
                "thread/settings/update",
                json!({"threadId":"t","sandboxPolicy":{}}),
            ),
            (
                "turn/start",
                json!({"threadId":"t","input":[{"type":"pocketFile","attachmentId":"file"}]}),
            ),
            (
                "turn/start",
                json!({"threadId":"t","input":[{"type":"text","text":"x","path":"C:/private"}]}),
            ),
        ] {
            assert!(validate(method, &p).is_err(), "{method}: {p}");
        }
    }
    #[test]
    fn mutations_are_distinguished_from_reads() {
        assert_eq!(
            validate("thread/read", &json!({"threadId":"t","includeTurns":true})),
            Ok(false)
        );
        assert_eq!(
            validate(
                "turn/start",
                &json!({"threadId":"t","input":[{"type":"text","text":"hello"}]})
            ),
            Ok(true)
        );
    }
    #[test]
    fn cannot_inject_paths_through_resume_or_local_image() {
        assert!(validate("thread/resume", &json!({"threadId":"t","path":"C:/secret"})).is_err());
        assert!(
            validate(
                "turn/start",
                &json!({"threadId":"t","input":[{"type":"localImage","path":"C:/secret"}]})
            )
            .is_err()
        );
    }
}
