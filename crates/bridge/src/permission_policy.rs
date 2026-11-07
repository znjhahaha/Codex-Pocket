//! Exact internal permission presets. Not a generic native configuration tunnel.
use serde_json::{Value, json};
pub fn valid_profile(v: &Value) -> bool {
    let Some(o) = v.as_object() else {
        return false;
    };
    !o.is_empty()
        && o.keys().all(|k| {
            matches!(
                k.as_str(),
                "description" | "extends" | "filesystem" | "network" | "workspace_roots"
            )
        })
        && ["filesystem", "network"]
            .iter()
            .all(|k| o.get(*k).is_none_or(Value::is_object))
        && ["description", "extends"]
            .iter()
            .all(|k| o.get(*k).is_none_or(Value::is_string))
        && o.get("workspace_roots")
            .is_none_or(|v| v.as_array().is_some_and(|a| a.iter().all(Value::is_string)))
        && ["extends", "filesystem", "network"]
            .iter()
            .any(|k| o.contains_key(*k))
}
pub fn configured_profiles(config: &Value) -> std::collections::BTreeSet<String> {
    config["config"]["permissions"]
        .as_object()
        .into_iter()
        .flatten()
        .filter(|(id, v)| {
            !id.is_empty()
                && id.len() <= 256
                && !id.starts_with(':')
                && !id.chars().any(char::is_control)
                && valid_profile(v)
        })
        .take(64)
        .map(|(id, _)| id.clone())
        .collect()
}
pub fn preset(thread: &str, id: &str) -> Option<Value> {
    let (approval, reviewer, sandbox) = match id {
        "request" => (
            "on-request",
            "user",
            json!({"type":"workspaceWrite","networkAccess":false}),
        ),
        "auto" => (
            "on-request",
            "auto_review",
            json!({"type":"workspaceWrite","networkAccess":false}),
        ),
        "full" => ("never", "user", json!({"type":"dangerFullAccess"})),
        _ => return None,
    };
    Some(
        json!({"threadId":thread,"approvalPolicy":approval,"approvalsReviewer":reviewer,"sandboxPolicy":sandbox}),
    )
}
pub fn valid_update(p: &Value) -> bool {
    let Some(thread) = p["threadId"]
        .as_str()
        .filter(|v| crate::settings::valid_thread(v))
    else {
        return false;
    };
    if ["request", "auto", "full"]
        .iter()
        .any(|id| preset(thread, id).as_ref() == Some(p))
    {
        return true;
    }
    p.as_object().is_some_and(|o| o.len() == 2)
        && p["permissions"].as_str().is_some_and(|id| {
            !id.is_empty() && id.len() <= 256 && !id.chars().any(char::is_control)
        })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn v051_exact_presets_reject_arbitrary_and_mixed_permissions() {
        for id in ["request", "auto", "full"] {
            assert!(valid_update(&preset("t", id).unwrap()));
        }
        assert!(valid_update(
            &json!({"threadId":"t","permissions":"existing"})
        ));
        for p in [
            json!({"threadId":"t","permissions":"x","sandboxPolicy":{"type":"dangerFullAccess"}}),
            json!({"threadId":"t","sandboxPolicy":{"type":"workspaceWrite","writableRoots":["C:/"]}}),
            json!({"threadId":"t","approvalPolicy":"never"}),
        ] {
            assert!(!valid_update(&p));
        }
    }
}
