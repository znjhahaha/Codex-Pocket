//! Restricted inventory: paths, secrets and native configuration never leave this module.
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
pub fn bare_key(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 128
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}
pub fn known_config(read: &Value) -> Vec<Value> {
    let mut known = Vec::new();
    for layer in read["layers"].as_array().into_iter().flatten().filter(|l| {
        l["name"]["type"] == "user"
            && l["disabledReason"].is_null()
            && l["version"]
                .as_str()
                .is_some_and(crate::message_edit::valid_id)
    }) {
        for (kind, table) in [("mcp", "mcp_servers"), ("plugin", "plugins")] {
            for (name, entry) in layer["config"][table].as_object().into_iter().flatten() {
                if bare_key(name)
                    && entry.is_object()
                    && (entry["enabled"].is_boolean()
                        || (kind == "mcp" && entry.get("enabled").is_none()))
                {
                    known.push(json!({"kind":kind,"name":name,"enabled":entry["enabled"].as_bool().unwrap_or(true),"keyPath":format!("{table}.{name}.enabled"),"expectedVersion":layer["version"]}));
                }
            }
        }
    }
    known
}
#[derive(Clone)]
pub struct Inventory {
    pub generation: String,
    pub version: String,
    pub data: Vec<Value>,
    pub updates: BTreeMap<String, (String, Value)>,
    app_inventory: Value,
}
fn opaque(s: &str) -> String {
    format!("ext-{:x}", Sha256::digest(s.as_bytes()))
}
impl Inventory {
    pub fn build(
        runtime: &str,
        generation: &str,
        read: &Value,
        skills: &Value,
        plugins: &Value,
        apps: &Value,
        status: &Value,
        caps: &crate::capabilities::Capabilities,
    ) -> Self {
        let version = opaque(&format!("{generation}:{}", json!([read, skills, plugins, apps])));
        let mut this = Self {
            generation: generation.into(),
            version: version.clone(),
            data: Vec::new(),
            updates: BTreeMap::new(),
            app_inventory: if apps["apps"].is_array() {
                json!({"status":"ready","reason":null})
            } else if apps["status"] == "unsupported" {
                json!({"status":"unsupported","reason":"native_app_inventory_not_supported"})
            } else {
                json!({"status":"unavailable","reason":"native_app_inventory_unavailable"})
            },
        };
        let known = known_config(read);
        for (name, entry) in read["config"]["mcp_servers"]
            .as_object()
            .into_iter()
            .flatten()
        {
            let enabled = entry["enabled"].as_bool().unwrap_or(true);
            let native=known.iter().find(|v|v["kind"]=="mcp" && v["name"]==*name).map(|v|("config/value/write".into(),json!({"keyPath":v["keyPath"],"value":enabled,"mergeStrategy":"replace","expectedVersion":v["expectedVersion"]})));
            let state = status["data"]
                .as_array()
                .into_iter()
                .flatten()
                .find(|s| s["name"] == *name)
                .and_then(|s| s["startupState"].as_str())
                .filter(|s| matches!(*s, "ready" | "failed" | "starting" | "stopped"))
                .unwrap_or(if enabled { "unknown" } else { "disabled" });
            this.add(
                runtime, generation, "mcp", name, enabled, state, native, caps,
            );
        }
        for skill in skills["data"]
            .as_array()
            .into_iter()
            .flatten()
            .flat_map(|s| s["skills"].as_array().into_iter().flatten())
        {
            let Some(name) = skill["name"]
                .as_str()
                .filter(|s| crate::message_edit::valid_id(s))
            else {
                continue;
            };
            let Some(path) = skill["path"]
                .as_str()
                .filter(|s| crate::message_edit::valid_id(s))
            else {
                continue;
            };
            let Some(enabled) = skill["enabled"].as_bool() else {
                continue;
            };
            // A path selector is generated from native inventory, never supplied by the phone.
            this.add(
                runtime,
                generation,
                "skill",
                name,
                enabled,
                if enabled { "ready" } else { "disabled" },
                Some((
                    "skills/config/write".into(),
                    json!({"path":path,"enabled":enabled}),
                )),
                caps,
            );
        }
        for plugin in plugins["marketplaces"]
            .as_array()
            .into_iter()
            .flatten()
            .flat_map(|m| m["plugins"].as_array().into_iter().flatten())
            .filter(|p| p["installed"] == true)
        {
            let Some(id) = plugin["id"].as_str() else {
                continue;
            };
            let name = plugin["name"].as_str().unwrap_or(id);
            let enabled = plugin["enabled"].as_bool().unwrap_or(false);
            let native=known.iter().find(|v|v["kind"]=="plugin" && v["name"]==id).map(|v|("config/value/write".into(),json!({"keyPath":v["keyPath"],"value":enabled,"mergeStrategy":"replace","expectedVersion":v["expectedVersion"]})));
            this.add(
                runtime,
                generation,
                "plugin",
                name,
                enabled,
                if enabled { "ready" } else { "disabled" },
                native,
                caps,
            );
        }
        for app in apps["apps"].as_array().into_iter().flatten() {
            let Some(native_id) = app["id"].as_str().filter(|s| crate::message_edit::valid_id(s)) else {
                continue;
            };
            let (Some(enabled), Some(callable)) = (app["enabled"].as_bool(), app["callable"].as_bool()) else {
                continue;
            };
            let id = opaque(&format!("{runtime}:{generation}:app:{native_id}"));
            if this.data.iter().any(|row| row["id"] == id) {
                continue;
            }
            let name = app["runtimeName"].as_str().filter(|s| crate::message_edit::valid_id(s)).unwrap_or("App");
            // Installed apps expose effective snapshot state, never a guessed configuration selector.
            this.data.push(json!({"id":id,"kind":"app","name":name,"enabled":enabled,"callable":callable,"status":if !enabled {"disabled"} else if callable {"ready"} else {"unavailable"},"capabilities":{"update":false},"version":version,"reason":"native_app_toggle_not_supported"}));
        }
        this
    }
    fn add(
        &mut self,
        runtime: &str,
        generation: &str,
        kind: &str,
        name: &str,
        enabled: bool,
        status: &str,
        native: Option<(String, Value)>,
        caps: &crate::capabilities::Capabilities,
    ) {
        let selector = native
            .as_ref()
            .map(|(_, p)| json!([p["path"], p["keyPath"]]))
            .unwrap_or(json!(name));
        let id = opaque(&format!("{runtime}:{generation}:{kind}:{name}:{selector}"));
        if self.data.iter().any(|r| r["id"] == id) {
            return;
        }
        let native = native.filter(|(method, p)| {
            caps.supports(method, p)
                && (kind == "skill" || caps.supports("config/mcpServer/reload", &Value::Null))
        });
        let update = native.is_some();
        if let Some(n) = native {
            self.updates.insert(id.clone(), n);
        }
        self.data.push(json!({"id":id,"kind":kind,"name":name,"enabled":enabled,"status":status,"capabilities":{"update":update},"version":self.version,"reason":if update {Value::Null} else {json!(if kind=="plugin" {"existing_plugin_config_not_supported"} else {"native_toggle_not_supported"})}}));
    }
    pub fn response(&self, runtime: &str) -> Value {
        json!({"runtimeId":runtime,"status":"ready","version":self.version,"data":self.data,"appInventory":self.app_inventory,"reason":self.app_inventory["reason"]})
    }
    pub fn update(&self, id: &str, enabled: bool) -> Option<(String, Value)> {
        let (method, mut p) = self.updates.get(id)?.clone();
        p[if method == "skills/config/write" {
            "enabled"
        } else {
            "value"
        }] = json!(enabled);
        Some((method, p))
    }
}
pub fn validate(p: &Value, write: bool) -> bool {
    let fields = if write {
        &["commandId", "runtimeId", "id", "enabled", "expectedVersion"][..]
    } else {
        &["runtimeId"][..]
    };
    p.as_object().is_some_and(|o| {
        o.len() == fields.len()
            && o.keys().all(|k| fields.contains(&k.as_str()))
            && fields.iter().all(|k| {
                if *k == "enabled" {
                    p[*k].is_boolean()
                } else {
                    p[*k].as_str().is_some_and(crate::message_edit::valid_id)
                }
            })
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn v054_installed_apps_are_opaque_effective_and_read_only() {
        let apps = json!({"apps":[
            {"id":"PRIVATE-ID-1","runtimeName":"Shared name","enabled":true,"callable":true,"metadata":{"token":"PRIVATE-TOKEN"},"path":"C:/PRIVATE"},
            {"id":"PRIVATE-ID-2","runtimeName":"Shared name","enabled":true,"callable":false},
            {"id":"PRIVATE-ID-3","runtimeName":null,"enabled":false,"callable":false}
        ]});
        let caps = crate::capabilities::Capabilities::default();
        let build = |apps: &Value| Inventory::build("r", "g", &json!({}), &json!({}), &json!({}), apps, &json!({}), &caps);
        let inventory = build(&apps);
        let response = inventory.response("r");
        assert_eq!(response["appInventory"]["status"], "ready");
        assert_eq!(inventory.data.len(), 3);
        assert_ne!(inventory.data[0]["id"], inventory.data[1]["id"]);
        assert_eq!(inventory.data[0]["status"], "ready");
        assert_eq!(inventory.data[1]["status"], "unavailable");
        assert_eq!(inventory.data[1]["enabled"], true);
        assert_eq!(inventory.data[1]["callable"], false);
        assert_eq!(inventory.data[2]["status"], "disabled");
        assert_eq!(inventory.data[2]["name"], "App");
        for row in &inventory.data {
            assert_eq!(row["kind"], "app");
            assert_eq!(row["capabilities"]["update"], false);
            assert_eq!(row["reason"], "native_app_toggle_not_supported");
            assert!(inventory.update(row["id"].as_str().unwrap(), false).is_none());
        }
        assert!(inventory.updates.is_empty());
        assert!(!response.to_string().contains("PRIVATE"));
        let mut changed = apps.clone();
        changed["apps"][1]["callable"] = json!(true);
        assert_ne!(inventory.version, build(&changed).version);
        assert_eq!(inventory.data[1]["id"], build(&changed).data[1]["id"]);
        let empty = build(&json!({"apps":[]})).response("r");
        let old = build(&json!({"status":"unsupported","reason":"native_app_inventory_not_supported"})).response("r");
        assert_eq!(empty["appInventory"]["status"], "ready");
        assert_eq!(old["appInventory"]["status"], "unsupported");
        assert_eq!(old["reason"], "native_app_inventory_not_supported");
    }
    #[test]
    fn v054_existing_mcp_without_enabled_uses_default_and_never_creates_a_server() {
        let read = json!({"layers":[{"name":{"type":"user"},"version":"v1","config":{"mcp_servers":{"existing":{"command":"PRIVATE"}},"plugins":{"unknown":{}}}}]});
        let known = known_config(&read);
        assert_eq!(known.len(), 1);
        assert_eq!(known[0]["enabled"], true);
        assert_eq!(known[0]["keyPath"], "mcp_servers.existing.enabled");
        assert!(!json!(known).to_string().contains("PRIVATE"));
    }
    #[test]
    fn v054_known_config_only_maps_existing_user_booleans() {
        let read = json!({"config":{"mcp_servers":{"safe":{"enabled":true,"env":{"TOKEN":"private"}},"new":{"url":"private"}}},"layers":[{"name":{"type":"user","file":"C:/private/config.toml"},"version":"v1","config":{"mcp_servers":{"safe":{"enabled":true,"env":{"TOKEN":"private"}}},"plugins":{"installed":{"enabled":false}}}}]});
        let known = known_config(&read);
        assert_eq!(known.len(), 2);
        assert_eq!(known[0]["expectedVersion"], "v1");
        assert!(!serde_json::to_string(&known).unwrap().contains("private"));
    }
}
