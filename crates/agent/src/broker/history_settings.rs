//! Read a saved session and its profile's catalog without creating an execution owner.
use super::*;
use anyhow::ensure;

impl Broker {
    async fn session_read_home(&self, p: &Value, catalog: bool) -> Result<PathBuf> {
        let allowed = if catalog { & ["computerId","configStoreId","threadId","method","params"][..] }
            else { & ["computerId","configStoreId","threadId"][..] };
        ensure!(p.as_object().is_some_and(|o|o.keys().all(|k|allowed.contains(&k.as_str()))), "Invalid session read");
        ensure!(p.get("computerId").is_none_or(|id|id == &self.config.computer_id), "Computer mismatch");
        let store = field(p,"configStoreId")?;
        let thread = field(p,"threadId")?;
        ensure!(crate::settings::valid_thread(thread), "Invalid thread identity");
        ensure!(!self.coordinator_visibility()?.internal(store,Some(thread)), "Private assistant thread");
        self.history_homes().await.remove(store).context("Unknown history profile")
    }

    pub(super) async fn saved_session_settings(&self, device: &str, p: &Value) -> Result<Value> {
        let home = self.session_read_home(p, false).await.map_err(|e|classify(e,Code::InvalidParams))?;
        let thread = field(p,"threadId")?.to_owned();
        let saved_thread = thread.clone();
        let saved = tokio::task::spawn_blocking(move || crate::history_catalog::settings::read(&home,&saved_thread))
            .await?.map_err(|e|classify(e,Code::NativeHistoryUnavailable))?;
        if !self.device_exists(device).await { return Err(classify(anyhow::anyhow!("Device revoked"),Code::Revoked)); }
        let runtime = format!("history:{}",field(p,"configStoreId")?);
        let mut permissions = crate::permissions::response(&runtime,&thread,"persisted",&saved,
            &crate::capabilities::Capabilities::default(),&BTreeMap::new());
        let permission_known = permissions["currentPresetId"].is_string();
        crate::permissions::unavailable(&mut permissions,if permission_known {"saved"} else {"unknown"},"settings_unknown");
        Ok(json!({"computerId":self.config.computer_id,"configStoreId":p["configStoreId"],"threadId":thread,
            "known":false,"settings":{},"savedKnown":saved.as_object().is_some_and(|o|!o.is_empty()),
            "savedSettings":saved,"savedPermissions":permissions,"readSource":"persisted"}))
    }

    pub(super) async fn session_catalog(&self, device: &str, p: &Value) -> Result<Value> {
        let home = self.session_read_home(p, true).await.map_err(|e|classify(e,Code::InvalidParams))?;
        let method = field(p,"method")?;
        if !matches!(method,"model/list"|"collaborationMode/list") {
            return Err(classify(anyhow::anyhow!("Not a catalog read"),Code::Unsupported));
        }
        access::validate(method,&p["params"]).map_err(access::AccessError::into_request_error)?;
        let proof_home = home.clone();
        let thread = field(p,"threadId")?.to_owned();
        tokio::task::spawn_blocking(move ||crate::history_catalog::settings::prove_thread(&proof_home,&thread))
            .await?.map_err(|e|classify(e,Code::NativeHistoryUnavailable))?;
        let store = field(p,"configStoreId")?;
        let reader = {
            let state = self.state.lock().await;
            state.runtimes.iter().filter(|(_,r)|r.store == store && r.rpc.online() && !r.role.is_auxiliary()
                && !r.role.assistant_private() && r.capabilities.supports(method,&p["params"]))
                .min_by_key(|(_,r)|r.source != "cli")
                .map(|(id,r)|(id.clone(),r.connection_id.clone(),r.rpc.clone()))
        };
        let mut result = if let Some((id,generation,rpc)) = reader {
            let result = tokio::time::timeout(Duration::from_secs(15),rpc.call(method,p["params"].clone()))
                .await.map_err(|e|classify(e.into(),Code::RequestTimeout))??;
            self.check_generation(&id,&generation).await?;
            result
        } else {
            #[cfg(windows)] {
                native::desktop_catalog::read_profile(&home,method,p["params"].clone()).await
                    .map_err(|e|classify(e,Code::RuntimeUnavailable))?
            }
            #[cfg(not(windows))] {
                let _ = home;
                return Err(classify(anyhow::anyhow!("Native catalog reader unavailable"),Code::RuntimeOffline));
            }
        };
        ensure!(result["data"].is_array(), "Invalid native catalog");
        if !self.device_exists(device).await { return Err(classify(anyhow::anyhow!("Device revoked"),Code::Revoked)); }
        result["computerId"] = json!(self.config.computer_id);
        result["configStoreId"] = p["configStoreId"].clone();
        result["threadId"] = p["threadId"].clone();
        Ok(result)
    }
}
