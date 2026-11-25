use super::*;

pub(super) fn assistant_scope(profile: Option<&Profile>) -> Result<String> {
    Ok(match profile {
        Some(p) => format!("assistant:{}:{}", storage::config_store(&p.config_home)?, p.source_runtime_id),
        None => "assistant:unconfigured".into(),
    })
}

impl Broker {
    /// The saved account owns all reads even if its executable has moved or disappeared.
    pub(super) fn assistant_read_profile(&self) -> Result<Option<Profile>> {
        let pending=self.state_dir.join("assistant/pending-profile.json");
        let file = if pending.try_exists()? {pending} else {self.state_dir.join("assistant/profile.json")};
        if file.try_exists()? {
            let profile: Profile = serde_json::from_slice(&storage::read_physical(&file,16384)?)?;
            anyhow::ensure!(profile.schema == 1, "Invalid assistant profile");
            return Ok(Some(profile));
        }
        Ok(crate::managed_runtime::discovery::from_environment().ok().map(|p|Profile {
            schema:1, config_home:p.config_home().into(), native_binary:p.native_binary().into(), source_runtime_id:String::new(),
        }))
    }

    pub(super) async fn assistant_models(&self, profile: Option<&Profile>) -> Result<Value> {
        let Some(profile) = profile else { return Ok(json!([])); };
        let store = storage::config_store(&profile.config_home)?;
        let rpc = {
            let state = self.state.lock().await;
            state.runtimes.values().find(|r|r.store==store && r.rpc.online()).map(|r|r.rpc.clone())
        };
        let mut models = BTreeMap::<String,Value>::new();
        if let Some(rpc) = rpc {
            let mut cursor = Value::Null;
            let mut seen = BTreeSet::new();
            for _ in 0..10 {
                let Ok(Ok(page)) = tokio::time::timeout(Duration::from_secs(3),rpc.call("model/list",json!({"cursor":cursor,"limit":100}))).await else { break; };
                for model in page["data"].as_array().into_iter().flatten() { add_model(&mut models,model); }
                cursor=page["nextCursor"].clone();
                if cursor.is_null() || !seen.insert(cursor.to_string()) { break; }
            }
        }
        if models.is_empty() {
            // Codex's cache is scoped to this exact home; never probe a different account.
            let cache=profile.config_home.join("models_cache.json");
            if cache.is_file() {
                if let Ok(bytes)=storage::read_physical(&cache,4*1024*1024) {
                    if let Ok(value)=serde_json::from_slice::<Value>(&bytes) {
                        for model in value["models"].as_array().into_iter().flatten() { add_model(&mut models,model); }
                    }
                }
            }
        }
        let config=profile.config_home.join("config.toml");
        if config.is_file() {
            if let Ok(bytes)=storage::read_physical(&config,1024*1024) {
                if let Ok(value)=String::from_utf8(bytes).ok().and_then(|text|toml::from_str::<toml::Value>(&text).ok()).ok_or(()) {
                    if let Some(model)=value.get("model").and_then(|v|v.as_str()) {
                        if !models.contains_key(model) {
                            add_model(&mut models,&json!({"id":model,"model":model,"displayName":model}));
                        }
                        for (id, entry) in &mut models { entry["isDefault"] = json!(id == model); }
                    }
                }
            }
        }
        Ok(json!(models.into_values().collect::<Vec<_>>()))
    }

    pub(super) async fn apply_pending_assistant_settings(&self, actor: &Actor) -> Result<()> {
        let root=self.state_dir.join("assistant");
        let scope=assistant_scope(self.assistant_read_profile()?.as_ref())?;
        let Some(pending)=crate::assistant_product::retained::pending_settings(&root,&scope)? else {return Ok(());};
        let mut patch=pending["settings"].clone();
        for key in ["coordinator_model", "effort", "coordinator_effort"] {
            patch[key]=pending["settings"][key].clone();
        }
        actor.request("assistant/settings/update",json!({"patch":patch}),
            format!("pending:{}",field(&pending,"commandId")?),None).await?;
        crate::assistant_product::retained::save_pending_settings(&root,&scope,None)?;
        Ok(())
    }
}

fn add_model(models: &mut BTreeMap<String,Value>, value: &Value) {
    if value["hidden"]==true || value["visibility"]=="hide" {return;}
    let Some(model)=value["model"].as_str().or_else(||value["slug"].as_str()).filter(|s|!s.is_empty() && s.len()<=256) else {return;};
    // Publish model metadata only, never a raw config/cache or provider credentials.
    models.insert(model.into(),json!({"id":value["id"].as_str().unwrap_or(model),"model":model,
        "displayName":value["displayName"].as_str().or_else(||value["display_name"].as_str()).unwrap_or(model),
        "description":value["description"].as_str().unwrap_or(""),"isDefault":value["isDefault"]==true,
        "inputModalities":value.get("inputModalities").or_else(||value.get("input_modalities")).cloned().unwrap_or(json!([])),
        "supportedReasoningEfforts":crate::assistant::model_capabilities::supported_efforts(value).into_iter().map(|effort|json!({"reasoningEffort":effort})).collect::<Vec<_>>(),
        "defaultReasoningEffort":crate::assistant::model_capabilities::default_effort(value)}));
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn effort_catalog_preserves_default_capabilities_and_lazy_settings_validate_published_choices() {
        let dir = tempfile::tempdir().unwrap();
        let home = dir.path().join("home"); std::fs::create_dir(&home).unwrap();
        std::fs::write(home.join("config.toml"), "model = 'work'\n").unwrap();
        std::fs::write(home.join("models_cache.json"), serde_json::to_vec(&json!({"models":[
            {"slug":"work","display_name":"Work","supported_reasoning_levels":[{"effort":"high"},{"effort":"max"}],"default_reasoning_level":"high"},
            {"slug":"chief","isDefault":true,"supported_reasoning_efforts":["low","high"]}
        ]})).unwrap()).unwrap();
        let (broker, _) = Broker::open(crate::config::Config::create("wss://example.org/pocket".into()).unwrap(), dir.path().into()).unwrap();
        let root = dir.path().join("assistant"); storage::private_directory(&root).unwrap();
        let profile = Profile {schema:1, config_home:home, native_binary:std::env::current_exe().unwrap(), source_runtime_id:"source".into()};
        storage::atomic_write(&root.join("profile.json"), &serde_json::to_vec(&profile).unwrap()).unwrap();
        let read = broker.assistant_read(&json!({"models":true})).await.unwrap();
        let rows = read["models"].as_array().unwrap();
        let work = rows.iter().find(|row| row["model"] == "work").unwrap();
        assert_eq!(work["supportedReasoningEfforts"], json!([{"reasoningEffort":"high"},{"reasoningEffort":"max"}]));
        assert_eq!(work["defaultReasoningEffort"], "high");
        assert_eq!(work["displayName"], "Work"); assert_eq!(work["isDefault"], true);
        assert_eq!(rows.iter().filter(|row| row["isDefault"] == true).count(), 1);
        let scope = assistant_scope(Some(&profile)).unwrap();
        broker.assistant_write("phone", "assistant/settings/update", &json!({"runtimeId":scope,"commandId":"efforts","patch":{"effort":"high","coordinator_model":"chief","coordinator_effort":"low"}})).await.unwrap();
        assert!(broker.assistant_write("phone", "assistant/settings/update", &json!({"runtimeId":scope,"commandId":"unsupported","patch":{"coordinator_effort":"max"}})).await.is_err());
        let saved = broker.assistant_read(&json!({})).await.unwrap();
        assert_eq!(saved["settings"]["effort"], "high"); assert_eq!(saved["settings"]["coordinator_effort"], "low");
        broker.assistant_write("phone", "assistant/settings/update", &json!({"runtimeId":scope,"commandId":"inherit","patch":{"coordinator_effort":null}})).await.unwrap();
        assert!(broker.assistant_write("phone", "assistant/settings/update", &json!({"runtimeId":scope,"commandId":"incompatible-work","patch":{"effort":"max"}})).await.is_err());
        broker.assistant_write("phone", "assistant/settings/update", &json!({"runtimeId":scope,"commandId":"reset","patch":{"effort":null}})).await.unwrap();
        let saved = broker.assistant_read(&json!({})).await.unwrap();
        assert!(saved["settings"]["effort"].is_null()); assert!(saved["settings"]["coordinator_effort"].is_null());
        assert!(broker.assistant.lock().await.is_none());
        assert!(broker.state.lock().await.runtimes.is_empty());
        assert!(!root.join("executor.db").exists());
    }

    #[test]
    fn cache_projection_does_not_expose_provider_config_and_keeps_native_model_names() {
        let mut rows=BTreeMap::new();
        add_model(&mut rows,&json!({"slug":"private/provider","display_name":"Private","api_key":"secret"}));
        assert_eq!(rows["private/provider"]["model"],"private/provider");
        assert!(rows["private/provider"].get("api_key").is_none());
        add_model(&mut rows,&json!({"slug":"hidden","visibility":"hide"}));
        assert_eq!(rows.len(),1);
    }
}
