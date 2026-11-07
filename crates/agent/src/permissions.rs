use crate::capabilities::Capabilities;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

pub fn opaque(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}
pub fn profiles(config: &Value, caps: &Capabilities) -> BTreeMap<String, String> {
    if !caps.supports(
        "thread/settings/update",
        &json!({"threadId":"t","permissions":"p"}),
    ) {
        return BTreeMap::new();
    }
    config["config"]["permissions"]
        .as_object()
        .into_iter()
        .flatten()
        .filter(|(id, v)| {
            !id.is_empty()
                && id.len() <= 256
                && !id.starts_with(':')
                && !id.chars().any(char::is_control)
                && crate::permission_policy::valid_profile(v)
        })
        .take(64)
        .map(|(id, value)| {
            (
                format!("custom-{}", opaque(&format!("{id}:{value}"))),
                id.clone(),
            )
        })
        .collect()
}
pub fn response(
    runtime: &str,
    thread: &str,
    generation: &str,
    settings: &Value,
    caps: &Capabilities,
    custom: &BTreeMap<String, String>,
) -> Value {
    let supported = caps.supports(
        "thread/settings/update",
        &crate::permission_policy::preset(thread, "request").unwrap(),
    );
    let auto = caps
        .0
        .get("thread/settings/update")
        .is_some_and(|f| f.contains("$autoReview"));
    let named_supported = caps.supports(
        "thread/settings/update",
        &json!({"threadId":thread,"permissions":"profile"}),
    );
    let known = settings["activePermissionProfile"]["id"].is_string()
        || (!settings["approvalPolicy"].is_null()
            && !settings["approvalsReviewer"].is_null()
            && settings["sandboxPolicy"]["type"].is_string());
    let mut presets = Vec::new();
    for (id, label, description) in [
        (
            "request",
            "请求批准",
            "编辑工作区外文件及访问网络时请求批准",
        ),
        ("auto", "帮我批准", "由原生自动审查，仅必要时请求批准"),
        ("full", "完全访问权限", "不受工作区或网络沙箱限制"),
    ] {
        let enabled = known && supported && (id != "auto" || auto);
        let mut v = json!({"id":id,"label":label,"description":description,"enabled":enabled});
        if !enabled {
            v["reasonCode"] = json!(if !known {
                "settings_unknown"
            } else {
                "native_unsupported"
            });
            v["reason"] = json!(if !known {
                "尚未确认当前原生权限"
            } else if supported {
                "当前原生协议未提供自动审批"
            } else {
                "当前原生协议不支持权限更新"
            });
        }
        presets.push(v);
    }
    for (id, name) in custom {
        presets.push(json!({"id":id,"label":format!("自定义 · {name}"),"description":"电脑现有的原生命名权限配置","enabled":known && named_supported}));
    }
    let s = &settings["sandboxPolicy"];
    let workspace = s["type"] == "workspaceWrite"
        && s["networkAccess"] == false
        && s["hasAdditionalWritableRoots"] != true
        && s["excludeSlashTmp"] != true
        && s["excludeTmpdirEnvVar"] != true;
    let current = if let Some((id, _)) = custom
        .iter()
        .find(|(_, name)| settings["activePermissionProfile"]["id"] == name.as_str())
    {
        Some(id.as_str())
    } else if settings["activePermissionProfile"]["id"]
        .as_str()
        .is_some_and(|id| !id.starts_with(':'))
    {
        Some("custom")
    } else if workspace
        && settings["approvalPolicy"] == "on-request"
        && settings["approvalsReviewer"] == "user"
    {
        Some("request")
    } else if workspace
        && settings["approvalPolicy"] == "on-request"
        && settings["approvalsReviewer"] == "auto_review"
    {
        Some("auto")
    } else if s["type"] == "dangerFullAccess"
        && settings["approvalPolicy"] == "never"
        && settings["approvalsReviewer"] == "user"
    {
        Some("full")
    } else if known {
        Some("custom")
    } else {
        None
    };
    let fields = json!({"approvalPolicy":settings["approvalPolicy"],"approvalsReviewer":settings["approvalsReviewer"],"sandboxPolicy":settings["sandboxPolicy"],"activePermissionProfile":settings["activePermissionProfile"]});
    let revision = opaque(&format!(
        "{generation}\n{thread}\n{fields}\n{:?}\n{:?}",
        custom.keys().collect::<Vec<_>>(),
        caps.0.get("thread/settings/update")
    ));
    let status = if !supported && (!named_supported || custom.is_empty()) {
        "unsupported"
    } else if known {
        "ready"
    } else {
        "unknown"
    };
    let label = current.map(|id| {
        if id == "custom" {
            "自定义权限".to_owned()
        } else {
            presets
                .iter()
                .find(|p| p["id"] == id)
                .and_then(|p| p["label"].as_str())
                .unwrap_or("自定义权限")
                .to_owned()
        }
    });
    let mut result = json!({"runtimeId":runtime,"threadId":thread,"status":status,"revision":if known {Some(revision)} else {None},"currentPresetId":current,"currentLabel":label,"presets":presets});
    if status != "ready" {
        unavailable(
            &mut result,
            status,
            if status == "unknown" {
                "settings_unknown"
            } else {
                "native_unsupported"
            },
        );
    }
    result
}
pub fn unavailable(result: &mut Value, status: &str, reason: &str) {
    result["status"] = json!(status);
    result["revision"] = Value::Null;
    result["reasonCode"] = json!(reason);
    result["reason"] = json!(match reason {
        "capability_initializing" => "正在确认权限能力",
        "bridge_update_required" => "请更新电脑桥接服务",
        "native_unsupported" => "原生暂不支持权限更新",
        "settings_unknown" => "当前权限尚未确认",
        "config_read_failed" => "无法验证自定义权限",
        _ => "权限状态读取失败",
    });
    let message = result["reason"].clone();
    if let Some(presets) = result["presets"].as_array_mut() {
        for p in presets {
            p["enabled"] = json!(false);
            p["reasonCode"] = json!(reason);
            p["reason"] = message.clone();
        }
    }
}
pub fn validate(p: &Value, update: bool) -> bool {
    let keys = if update {
        &[
            "runtimeId",
            "threadId",
            "presetId",
            "expectedRevision",
            "commandId",
        ][..]
    } else {
        &["runtimeId", "threadId"][..]
    };
    p.as_object().is_some_and(|o| {
        o.len() == keys.len()
            && keys.iter().all(|k| {
                o.get(*k).and_then(Value::as_str).is_some_and(|v| {
                    !v.is_empty() && v.len() <= 4096 && !v.chars().any(char::is_control)
                })
            })
    })
}
pub fn thread_metadata(t: &Value) -> Value {
    let parent = t["parentThreadId"]
        .as_str()
        .filter(|v| crate::settings::valid_thread(v))
        .or_else(|| {
            t.pointer("/source/subAgent/thread_spawn/parent_thread_id")
                .and_then(Value::as_str)
                .filter(|v| crate::settings::valid_thread(v))
        });
    let mut result = json!({});
    if t.get("source").is_some() || parent.is_some() {
        let sub = parent.is_some() || t["source"].get("subAgent").is_some();
        result["isSubagent"] = json!(sub);
        result["nativeSource"] = json!(if sub {
            "subAgent"
        } else {
            match t["source"].as_str() {
                Some("cli") => "cli",
                Some("vscode") => "vscode",
                Some("appServer") => "appServer",
                Some("exec") => "exec",
                _ => "unknown",
            }
        });
    }
    if let Some(parent) = parent {
        result["parentThreadId"] = json!(parent);
    }
    result
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn v052_known_custom_is_ready_and_partial_state_has_no_writable_revision() {
        let mut caps = Capabilities::default();
        caps.0.insert(
            "thread/settings/update".into(),
            [
                "threadId",
                "approvalPolicy",
                "approvalsReviewer",
                "sandboxPolicy",
                "$autoReview",
            ]
            .into_iter()
            .map(str::to_owned)
            .collect(),
        );
        let state = json!({"approvalPolicy":"on-request","approvalsReviewer":"user","sandboxPolicy":{"type":"readOnly"}});
        let r = response("r", "t", "g", &state, &caps, &BTreeMap::new());
        assert_eq!(r["status"], "ready");
        assert_eq!(r["currentPresetId"], "custom");
        assert_eq!(r["currentLabel"], "自定义权限");
        assert!(r["revision"].is_string());
        assert!(
            r["presets"]
                .as_array()
                .unwrap()
                .iter()
                .all(|p| p["enabled"] == true)
        );
        let r = response(
            "r",
            "t",
            "g",
            &json!({"approvalPolicy":"never"}),
            &caps,
            &BTreeMap::new(),
        );
        assert_eq!(r["status"], "unknown");
        assert!(r["revision"].is_null());
        assert!(
            r["presets"]
                .as_array()
                .unwrap()
                .iter()
                .all(|p| p["enabled"] == false)
        );
    }
    #[test]
    fn v052_custom_policies_and_capability_changes_keep_authoritative_revisions() {
        let mut caps = Capabilities::default();
        caps.0.insert(
            "thread/settings/update".into(),
            [
                "threadId",
                "approvalPolicy",
                "approvalsReviewer",
                "sandboxPolicy",
            ]
            .into_iter()
            .map(str::to_owned)
            .collect(),
        );
        for sandbox in [
            json!({"type":"readOnly"}),
            json!({"type":"externalSandbox"}),
            json!({"type":"workspaceWrite","networkAccess":true}),
            json!({"type":"workspaceWrite","networkAccess":false,"hasAdditionalWritableRoots":true}),
            json!({"type":"workspaceWrite","networkAccess":false,"excludeSlashTmp":true}),
        ] {
            let r = response(
                "r",
                "t",
                "g",
                &json!({"approvalPolicy":"on-request","approvalsReviewer":"user","sandboxPolicy":sandbox}),
                &caps,
                &BTreeMap::new(),
            );
            assert_eq!(r["status"], "ready");
            assert_eq!(r["currentPresetId"], "custom");
        }
        let mut state =
            crate::settings::canonical(&crate::permission_policy::preset("t", "request").unwrap());
        state["activePermissionProfile"] = json!({"id":":workspace"});
        let before = response("r", "t", "g", &state, &caps, &BTreeMap::new());
        assert_eq!(before["currentPresetId"], "request");
        caps.0
            .get_mut("thread/settings/update")
            .unwrap()
            .insert("$autoReview".into());
        assert_ne!(
            before["revision"],
            response("r", "t", "g", &state, &caps, &BTreeMap::new())["revision"]
        );
        state["activePermissionProfile"] = json!({"id":"unverified-named"});
        assert_eq!(
            response("r", "t", "g", &state, &caps, &BTreeMap::new())["currentPresetId"],
            "custom"
        );
    }
    #[test]
    fn v051_custom_profiles_require_native_field_and_valid_existing_config() {
        let config = json!({"config":{"permissions":{"project":{"extends":":workspace"},"bad":{"apiKey":"secret"},"empty":{}}}});
        let mut c = Capabilities::default();
        assert!(profiles(&config, &c).is_empty());
        c.0.insert(
            "thread/settings/update".into(),
            ["threadId", "permissions"]
                .into_iter()
                .map(str::to_owned)
                .collect(),
        );
        let p = profiles(&config, &c);
        assert_eq!(p.len(), 1);
        assert_eq!(p.values().next().unwrap(), "project");
        assert!(!p.keys().next().unwrap().contains("project"));
        let edited = json!({"config":{"permissions":{"project":{"extends":":read-only"}}}});
        assert_ne!(profiles(&edited, &c), p);
    }
    #[test]
    fn v051_metadata_never_infers_subagent_from_name_or_fork() {
        assert!(
            thread_metadata(&json!({"name":"Hubble","forkedFromId":"p"}))
                .get("isSubagent")
                .is_none()
        );
        assert_eq!(
            thread_metadata(&json!({"source":"cli","forkedFromId":"p"}))["isSubagent"],
            false
        );
        assert_eq!(
            thread_metadata(&json!({"parentThreadId":"p"}))["isSubagent"],
            true
        );
        assert_eq!(
            thread_metadata(
                &json!({"source":{"subAgent":{"thread_spawn":{"parent_thread_id":"p"}}}})
            )["parentThreadId"],
            "p"
        );
    }
    #[test]
    fn v051_no_default_permission_selection_or_arbitrary_input() {
        let r = response(
            "r",
            "t",
            "g",
            &json!({}),
            &Capabilities::default(),
            &BTreeMap::new(),
        );
        assert!(r["currentPresetId"].is_null());
        assert_eq!(r["status"], "unsupported");
        assert!(!validate(
            &json!({"runtimeId":"r","threadId":"t","path":"secret"}),
            false
        ));
        let mut caps = Capabilities::default();
        caps.0.insert(
            "thread/settings/update".into(),
            [
                "threadId",
                "approvalPolicy",
                "approvalsReviewer",
                "sandboxPolicy",
            ]
            .into_iter()
            .map(str::to_owned)
            .collect(),
        );
        let a = response(
            "r",
            "t",
            "g",
            &json!({"approvalPolicy":"never","approvalsReviewer":"user","sandboxPolicy":{"type":"dangerFullAccess"}}),
            &caps,
            &BTreeMap::new(),
        );
        let b = response(
            "r",
            "t",
            "h",
            &json!({"approvalPolicy":"never","approvalsReviewer":"user","sandboxPolicy":{"type":"dangerFullAccess"}}),
            &caps,
            &BTreeMap::new(),
        );
        assert_ne!(a["revision"], b["revision"]);
    }
}
