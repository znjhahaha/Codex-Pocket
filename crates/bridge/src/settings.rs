//! Bounded observations of native thread settings. No defaults, content or config.
use serde_json::{Map, Value, json};
use std::collections::BTreeMap;

pub const MAX_THREADS: usize = 256;
pub fn valid_thread(id: &str) -> bool {
    !id.is_empty() && id.len() <= 4096 && !id.chars().any(char::is_control)
}

fn text(value: &Value) -> bool {
    value
        .as_str()
        .is_some_and(|s| !s.is_empty() && s.len() <= 256 && !s.chars().any(char::is_control))
}
/// Only native protocol fields are copied, including explicit native null effort.
pub fn canonical(value: &Value) -> Value {
    let mut out = Map::new();
    if matches!(
        value["approvalPolicy"].as_str(),
        Some("untrusted" | "on-request" | "never")
    ) {
        out.insert("approvalPolicy".into(), value["approvalPolicy"].clone());
    }
    if let Some(granular) = value["approvalPolicy"]["granular"].as_object() {
        let safe: Map<String, Value> = granular
            .iter()
            .filter(|(k, v)| {
                matches!(
                    k.as_str(),
                    "sandbox_approval"
                        | "rules"
                        | "mcp_elicitations"
                        | "request_permissions"
                        | "skill_approval"
                ) && v.is_boolean()
            })
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        if !safe.is_empty() {
            out.insert("approvalPolicy".into(), json!({"granular":safe}));
        }
    }
    if matches!(
        value["approvalsReviewer"].as_str(),
        Some("user" | "auto_review" | "guardian_subagent")
    ) {
        out.insert(
            "approvalsReviewer".into(),
            value["approvalsReviewer"].clone(),
        );
    }
    // Native thread/start and thread/resume responses name the effective policy
    // `sandbox`, while updates/events use `sandboxPolicy`. Only typed policies
    // below are accepted; a request's string sandbox mode is not readback proof.
    let sandbox = value
        .get("sandboxPolicy")
        .or_else(|| value.get("sandbox"))
        .unwrap_or(&Value::Null);
    if matches!(
        sandbox["type"].as_str(),
        Some("workspaceWrite" | "readOnly" | "dangerFullAccess" | "externalSandbox")
    ) {
        let mut safe = json!({"type":sandbox["type"]});
        for key in ["networkAccess", "excludeSlashTmp", "excludeTmpdirEnvVar"] {
            if let Some(v) = sandbox.get(key).filter(|v| {
                v.is_boolean()
                    || (key == "networkAccess"
                        && matches!(v.as_str(), Some("restricted" | "enabled")))
            }) {
                safe[key] = v.clone();
            }
        }
        // Preserve the semantic distinction without exposing filesystem paths.
        safe["hasAdditionalWritableRoots"] = json!(
            sandbox["writableRoots"]
                .as_array()
                .is_some_and(|v| !v.is_empty())
                || sandbox["hasAdditionalWritableRoots"] == true
        );
        out.insert("sandboxPolicy".into(), safe);
    }
    if let Some(profile) = value.get("activePermissionProfile") {
        if profile.is_null() {
            out.insert("activePermissionProfile".into(), Value::Null);
        } else if text(&profile["id"]) {
            out.insert(
                "activePermissionProfile".into(),
                json!({"id":profile["id"]}),
            );
        }
    }
    if text(&value["model"]) {
        out.insert("model".into(), value["model"].clone());
    }
    for key in ["effort", "serviceTier"] {
        if let Some(v) = value.get(key).filter(|v| v.is_null() || text(v)) {
            out.insert(key.into(), v.clone());
        }
    }
    let mode = &value["collaborationMode"];
    if matches!(mode["mode"].as_str(), Some("plan" | "default")) && text(&mode["settings"]["model"])
    {
        let mut settings = json!({"model":mode["settings"]["model"]});
        if let Some(effort) = mode["settings"]
            .get("reasoning_effort")
            .filter(|v| v.is_null() || text(v))
        {
            settings["reasoning_effort"] = effort.clone();
        }
        out.insert(
            "collaborationMode".into(),
            json!({"mode":mode["mode"],"settings":settings}),
        );
    }
    Value::Object(out)
}
pub fn notification(thread: &str, settings: Value) -> Value {
    json!({"method":"thread/settings/updated","params":{"threadId":thread,"threadSettings":settings}})
}
#[derive(Clone, Default)]
pub struct SettingsCache {
    clock: u64,
    floor: u64,
    entries: BTreeMap<String, Entry>,
}
#[derive(Clone)]
struct Entry {
    revision: u64,
    permission_barrier: u64,
    settings: Value,
    fields: BTreeMap<String, u64>,
}
/// Keeps only a routing identity and already sanitized fields while awaiting ACK.
pub struct Observation {
    revision: u64,
    method: String,
    thread: Option<String>,
    settings: Value,
}
impl SettingsCache {
    fn advance(&mut self) -> u64 {
        self.clock = self
            .clock
            .checked_add(1)
            .expect("settings observation counter exhausted");
        self.clock
    }
    pub fn read(&self, thread: &str) -> Value {
        let settings = self
            .entries
            .get(thread)
            .map(|e| e.settings.clone())
            .unwrap_or_else(|| json!({}));
        json!({"threadId":thread,"known":settings.as_object().is_some_and(|s| !s.is_empty()),"settings":settings})
    }
    pub fn prepare(&mut self, method: &str, params: &Value) -> Option<Observation> {
        if !matches!(
            method,
            "turn/start"
                | "thread/settings/update"
                | "thread/start"
                | "thread/resume"
                | "thread/read"
                | "thread/fork"
        ) {
            return None;
        }
        let thread = params["threadId"]
            .as_str()
            .filter(|s| valid_thread(s))
            .map(str::to_owned);
        if !matches!(method, "thread/start") && thread.is_none() {
            return None;
        }
        let mut settings = if matches!(method, "turn/start" | "thread/start" | "thread/resume") {
            canonical(params)
        } else {
            json!({})
        };
        // A settings ACK confirms receipt only. Effective settings come from
        // native response fields, settings events, or a subsequent native read.
        // Option-valued request nulls inherit native state; they are not proof of defaults.
        for key in ["model", "effort", "serviceTier"] {
            if settings[key].is_null()
                && !(key == "serviceTier" && method == "thread/settings/update")
            {
                settings.as_object_mut().unwrap().remove(key);
            }
        }
        // A requested collaboration preset has native precedence over model/effort.
        if let Some(mode) = settings.get("collaborationMode").cloned() {
            settings["model"] = mode["settings"]["model"].clone();
            if let Some(effort) = mode["settings"].get("reasoning_effort") {
                settings["effort"] = effort.clone();
            }
        }
        let revision = self.advance();
        if method == "thread/settings/update"
            && crate::permission_policy::valid_update(params)
            && let Some(entry) = thread.as_ref().and_then(|t| self.entries.get_mut(t))
        {
            // Ownership refresh/resume can produce native settings before this
            // request is dispatched. Those observations cannot confirm its ACK.
            entry.permission_barrier = revision;
        }
        Some(Observation {
            revision,
            method: method.into(),
            thread,
            settings,
        })
    }
    pub fn accepted(&mut self, observed: Observation, result: &Value) -> Option<Value> {
        let returned = result["thread"]["id"].as_str();
        let thread = if matches!(observed.method.as_str(), "thread/start" | "thread/fork") {
            let id = returned.filter(|s| valid_thread(s))?;
            if observed.method == "thread/fork" && Some(id) == observed.thread.as_deref() {
                return None;
            }
            id.to_owned()
        } else {
            let id = observed.thread?;
            if returned.is_some_and(|s| s != id)
                || result["threadId"].as_str().is_some_and(|s| s != id)
            {
                return None;
            }
            if matches!(observed.method.as_str(), "thread/read" | "thread/resume")
                && returned != Some(id.as_str())
            {
                return None;
            }
            id
        };
        let mut fields = observed.settings;
        if matches!(
            observed.method.as_str(),
            "thread/start" | "thread/resume" | "thread/settings/update"
        ) {
            fields
                .as_object_mut()
                .unwrap()
                .extend(canonical(result).as_object().unwrap().clone());
            if let Some(e) = result.get("reasoningEffort") {
                fields
                    .as_object_mut()
                    .unwrap()
                    .extend(canonical(&json!({"effort":e})).as_object().unwrap().clone());
            }
        }
        let metadata = &result["thread"];
        let mut native = canonical(metadata);
        if let Some(e) = metadata.get("reasoningEffort") {
            native
                .as_object_mut()
                .unwrap()
                .extend(canonical(&json!({"effort":e})).as_object().unwrap().clone());
        }
        fields
            .as_object_mut()
            .unwrap()
            .extend(native.as_object().unwrap().clone());
        for object in [
            result.get("settings"),
            result.get("threadSettings"),
            metadata.get("settings"),
            metadata.get("threadSettings"),
        ]
        .into_iter()
        .flatten()
        {
            fields
                .as_object_mut()
                .unwrap()
                .extend(canonical(object).as_object().unwrap().clone());
        }
        self.merge(&thread, fields, observed.revision)
    }
    pub fn event(&mut self, event: &Value) -> Option<Value> {
        let (thread, settings) = match event["method"].as_str()? {
            "thread/settings/updated" => (
                event["params"]["threadId"].as_str()?,
                canonical(&event["params"]["threadSettings"]),
            ),
            "thread/started" => {
                let thread = &event["params"]["thread"];
                let mut settings = canonical(thread);
                if let Some(e) = thread.get("reasoningEffort") {
                    settings
                        .as_object_mut()
                        .unwrap()
                        .extend(canonical(&json!({"effort":e})).as_object().unwrap().clone());
                }
                (thread["id"].as_str()?, settings)
            }
            _ => return None,
        };
        let revision = self.advance();
        self.merge(thread, settings, revision)
    }
    pub fn snapshot(&mut self, thread: &str, settings: &Value, revision: u64) -> Option<Value> {
        self.merge(thread, canonical(settings), revision)
    }
    pub fn checkpoint(&mut self) -> u64 {
        self.advance()
    }
    /// All effective permission fields must be native observations after dispatch.
    pub fn permissions_proven_since(&self, thread: &str, checkpoint: u64) -> bool {
        self.entries.get(thread).is_some_and(|entry| {
            if entry.settings["activePermissionProfile"]["id"].is_string() {
                entry
                    .fields
                    .get("activePermissionProfile")
                    .is_some_and(|r| *r > checkpoint && *r >= entry.permission_barrier)
            } else {
                ["approvalPolicy", "approvalsReviewer", "sandboxPolicy"]
                    .iter()
                    .all(|key| {
                        entry
                            .fields
                            .get(*key)
                            .is_some_and(|r| *r > checkpoint && *r >= entry.permission_barrier)
                    })
            }
        })
    }
    fn merge(&mut self, thread: &str, fields: Value, revision: u64) -> Option<Value> {
        if !valid_thread(thread) || fields.as_object()?.is_empty() || revision < self.floor {
            return None;
        }
        if !self.entries.contains_key(thread) && self.entries.len() == MAX_THREADS {
            let oldest = self
                .entries
                .iter()
                .min_by_key(|(_, e)| e.revision)
                .map(|(id, e)| (id.clone(), e.revision))
                .unwrap();
            self.entries.remove(&oldest.0);
            self.floor = self.floor.max(oldest.1);
        }
        let entry = self.entries.entry(thread.into()).or_insert_with(|| Entry {
            revision,
            permission_barrier: 0,
            settings: json!({}),
            fields: BTreeMap::new(),
        });
        let before = entry.settings.clone();
        let mut applied = Map::new();
        for (key, value) in fields.as_object()? {
            if entry.fields.get(key).is_none_or(|seen| *seen <= revision) {
                entry
                    .settings
                    .as_object_mut()?
                    .insert(key.clone(), value.clone());
                entry.fields.insert(key.clone(), revision);
                applied.insert(key.clone(), value.clone());
            }
        }
        entry.revision = entry.revision.max(revision);
        // A partial model/effort observation cannot establish an inconsistent preset.
        if let Some(mode) = entry.settings.get("collaborationMode") {
            let mismatch = applied
                .get("model")
                .is_some_and(|m| Some(m) != before.get("model") && m != &mode["settings"]["model"])
                || applied.get("effort").is_some_and(|e| {
                    Some(e) != before.get("effort")
                        && mode["settings"]
                            .get("reasoning_effort")
                            .is_some_and(|previous| e != previous)
                });
            let stale_mode = applied.contains_key("collaborationMode")
                && revision < entry.revision
                && (entry
                    .settings
                    .get("model")
                    .is_some_and(|m| m != &mode["settings"]["model"])
                    || entry.settings.get("effort").is_some_and(|e| {
                        mode["settings"]
                            .get("reasoning_effort")
                            .is_some_and(|m| m != e)
                    }));
            if (mismatch && !applied.contains_key("collaborationMode")) || stale_mode {
                entry.settings.as_object_mut()?.remove("collaborationMode");
                entry
                    .fields
                    .insert("collaborationMode".into(), entry.revision);
            }
        }
        (before != entry.settings).then(|| notification(thread, entry.settings.clone()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn v052_pre_dispatch_resume_cannot_confirm_later_empty_ack() {
        let mut cache = SettingsCache::default();
        let policy = crate::permission_policy::preset("t", "request").unwrap();
        cache.event(&notification("t", policy.clone()));
        let checkpoint = cache.checkpoint();
        let resume = cache
            .prepare("thread/resume", &json!({"threadId":"t"}))
            .unwrap();
        cache.accepted(resume, &json!({"thread":{"id":"t"},"settings":policy}));
        let update = cache.prepare("thread/settings/update", &policy).unwrap();
        cache.accepted(update, &json!({}));
        assert!(!cache.permissions_proven_since("t", checkpoint));
        let read = cache
            .prepare("thread/read", &json!({"threadId":"t"}))
            .unwrap();
        cache.accepted(read, &json!({"thread":{"id":"t","settings":policy}}));
        assert!(cache.permissions_proven_since("t", checkpoint));
    }
    #[test]
    fn v052_empty_settings_ack_never_confirms_requested_permissions() {
        let mut cache = SettingsCache::default();
        cache.event(&notification(
            "t",
            crate::permission_policy::preset("t", "request").unwrap(),
        ));
        let before = cache.read("t");
        let observed = cache
            .prepare(
                "thread/settings/update",
                &crate::permission_policy::preset("t", "full").unwrap(),
            )
            .unwrap();
        assert!(cache.accepted(observed, &json!({})).is_none());
        assert_eq!(cache.read("t"), before);
        let observed = cache
            .prepare(
                "thread/settings/update",
                &crate::permission_policy::preset("t", "full").unwrap(),
            )
            .unwrap();
        cache.accepted(observed, &json!({"threadId":"t","approvalPolicy":"never","approvalsReviewer":"user","sandboxPolicy":{"type":"dangerFullAccess"}}));
        assert_eq!(
            cache.read("t")["settings"]["sandboxPolicy"]["type"],
            "dangerFullAccess"
        );
    }
    #[test]
    fn v051_effective_start_sandbox_alias_preserves_policy_without_paths() {
        let mut cache = SettingsCache::default();
        let observed = cache
            .prepare("thread/start", &json!({"sandbox":"workspace-write"}))
            .unwrap();
        assert!(
            canonical(&json!({"sandbox":"workspace-write"}))
                .get("sandboxPolicy")
                .is_none()
        );
        cache.accepted(observed, &json!({"thread":{"id":"fixture"},"approvalPolicy":"on-request","approvalsReviewer":"user","sandbox":{"type":"workspaceWrite","networkAccess":false,"writableRoots":["private-path"]}}));
        let read = cache.read("fixture");
        assert_eq!(
            read["settings"]["sandboxPolicy"],
            json!({"type":"workspaceWrite","networkAccess":false,"hasAdditionalWritableRoots":true})
        );
        assert!(!read.to_string().contains("private-path"));
        assert_eq!(
            canonical(
                &json!({"sandboxPolicy":{"type":"readOnly"},"sandbox":{"type":"dangerFullAccess"}})
            )["sandboxPolicy"]["type"],
            "readOnly"
        );
    }
    #[test]
    fn v051_permission_fields_are_sanitized_without_config_or_paths() {
        let value = canonical(
            &json!({"approvalPolicy":"on-request","approvalsReviewer":"auto_review","sandboxPolicy":{"type":"workspaceWrite","networkAccess":false,"writableRoots":["secret"]},"config":{"key":"secret"}}),
        );
        assert_eq!(value["approvalPolicy"], "on-request");
        assert_eq!(value["approvalsReviewer"], "auto_review");
        assert_eq!(value["sandboxPolicy"]["type"], "workspaceWrite");
        assert!(!value.to_string().contains("secret"));
    }
    #[test]
    fn settings_resume_result_can_fill_plan_after_partial_started_metadata() {
        let mut cache = SettingsCache::default();
        let resume = cache
            .prepare("thread/resume", &json!({"threadId":"t"}))
            .unwrap();
        cache.event(&json!({"method":"thread/started","params":{"thread":{"id":"t","model":"m","reasoningEffort":"high"}}}));
        cache.accepted(resume, &json!({"thread":{"id":"t"},"model":"m","reasoningEffort":"high","collaborationMode":{"mode":"plan","settings":{"model":"m","reasoning_effort":"high"}}}));
        assert_eq!(
            cache.read("t")["settings"]["collaborationMode"]["mode"],
            "plan"
        );
    }
    #[test]
    fn settings_unrelated_tier_change_preserves_observed_native_plan() {
        let mut cache = SettingsCache::default();
        cache.event(&notification("t", json!({"model":"m","effort":null,"collaborationMode":{"mode":"plan","settings":{"model":"m","reasoning_effort":"high"}}})));
        let tier = cache
            .prepare(
                "thread/settings/update",
                &json!({"threadId":"t","serviceTier":"fast"}),
            )
            .unwrap();
        cache.accepted(tier, &json!({}));
        assert_eq!(
            cache.read("t")["settings"]["collaborationMode"]["mode"],
            "plan"
        );
    }
    #[test]
    fn settings_cache_is_bounded_and_eviction_rejects_old_responses() {
        let mut cache = SettingsCache::default();
        let late = cache
            .prepare("thread/read", &json!({"threadId":"evicted"}))
            .unwrap();
        for i in 0..MAX_THREADS + 1 {
            cache.event(&notification(&format!("t{i}"), json!({"model":"m"})));
        }
        assert_eq!(cache.entries.len(), MAX_THREADS);
        assert_eq!(cache.read("t0")["known"], false);
        assert_eq!(cache.read(&format!("t{MAX_THREADS}"))["known"], true);
        assert!(
            cache
                .accepted(late, &json!({"thread":{"id":"evicted","model":"old"}}))
                .is_none()
        );
        assert_eq!(cache.entries.len(), MAX_THREADS);
    }
    #[test]
    fn settings_snapshot_and_request_keep_only_bounded_canonical_fields() {
        let mut cache = SettingsCache::default();
        cache.event(&notification("t", json!({"model":"x".repeat(257),"effort":{},"serviceTier":[],"collaborationMode":{"mode":"secret-mode","settings":{"model":"m"}},"config":{"token":"private"}})));
        assert_eq!(cache.read("t")["known"], false);
        let request = cache.prepare("turn/start", &json!({"threadId":"t","model":"ignored","effort":"low","collaborationMode":{"mode":"plan","settings":{"model":"selected","reasoning_effort":null,"developer_instructions":"private"}},"permissions":"all","input":[{"text":"secret"}]})).unwrap();
        cache.accepted(request, &json!({"turn":{"id":"u"}}));
        assert_eq!(
            cache.read("t")["settings"],
            json!({"model":"selected","effort":null,"collaborationMode":{"mode":"plan","settings":{"model":"selected","reasoning_effort":null}}})
        );
    }
    #[test]
    fn settings_absent_native_effort_stays_absent_and_null_is_explicit() {
        let mut cache = SettingsCache::default();
        cache.event(&notification("t", json!({"model":"m"})));
        assert!(cache.read("t")["settings"].get("effort").is_none());
        cache.event(&notification("t", json!({"effort":null})));
        assert_eq!(
            cache.read("t")["settings"].get("effort"),
            Some(&Value::Null)
        );
        cache.event(&notification("t", json!({"model":"n"})));
        assert_eq!(
            cache.read("t")["settings"].get("effort"),
            Some(&Value::Null)
        );
        assert_eq!(cache.read("other")["known"], false);
    }
}
