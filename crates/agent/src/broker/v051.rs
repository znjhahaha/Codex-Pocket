use super::*;
use crate::{context_usage, permissions};

impl Broker {
    pub(super) async fn read_context(&self, device: &str, p: &Value) -> Result<Value> {
        if !permissions::validate(p, false) {
            return Err(classify(
                anyhow::anyhow!("Invalid context parameters"),
                Code::InvalidParams,
            ));
        }
        let runtime = field(p, "runtimeId")?;
        let thread = field(p, "threadId")?;
        let (rpc, generation, home, store, can_read) = {
            let s = self.state.lock().await;
            let Some(r) = s.runtimes.get(runtime) else {
                return Ok(context_envelope(
                    runtime,
                    thread,
                    context_usage::empty("unsupported"),
                ));
            };
            if let Some(mut v) = r.usage.get(thread) {
                if !r.rpc.online() {
                    v["status"] = json!("stale");
                }
                return Ok(context_envelope(runtime, thread, v));
            }
            (
                r.rpc.clone(),
                r.connection_id.clone(),
                r.config_home.clone(),
                r.store.clone(),
                r.capabilities.supports(
                    "thread/read",
                    &json!({"threadId":thread,"includeTurns":false}),
                ),
            )
        };
        if !can_read {
            return Ok(context_envelope(
                runtime,
                thread,
                context_usage::empty("unsupported"),
            ));
        }
        let result = rpc
            .call(
                "thread/read",
                json!({"threadId":thread,"includeTurns":false}),
            )
            .await;
        let candidate = match result {
            Ok(value) if value["thread"]["id"] == thread => {
                if let Some(v) = context_usage::snapshot(
                    &value["thread"]["tokenUsage"],
                    "native_snapshot",
                    storage::now().saturating_mul(1000),
                    value["thread"]["turnId"].as_str(),
                ) {
                    v
                } else {
                    let meta = value["thread"].clone();
                    let thread = thread.to_owned();
                    match tokio::task::spawn_blocking(move || {
                        context_usage::read_history(&home, &store, &thread, &meta)
                    })
                    .await
                    {
                        Ok(Ok(Some(v))) => v,
                        Ok(Ok(None)) => context_usage::empty("empty"),
                        _ => context_usage::empty("failed"),
                    }
                }
            }
            _ => context_usage::empty("failed"),
        };
        let mut s = self.state.lock().await;
        if !s.devices.devices.contains_key(device) {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        let r = s
            .runtimes
            .get_mut(runtime)
            .filter(|r| r.connection_id == generation)
            .ok_or(RpcFailure::Disconnected)?;
        // An event received during disk/RPC I/O is newer than the snapshot.
        let mut result = r.usage.get(thread).unwrap_or(candidate);
        if !r.rpc.online() && !result["usage"].is_null() {
            result["status"] = json!("stale");
        }
        r.usage.put(thread, result.clone());
        Ok(context_envelope(runtime, thread, result))
    }
    pub(super) async fn permission_data(
        &self,
        device: &str,
        p: &Value,
    ) -> Result<(Value, BTreeMap<String, String>)> {
        if !permissions::validate(p, false) {
            return Err(classify(
                anyhow::anyhow!("Invalid permission read"),
                Code::InvalidParams,
            ));
        }
        let runtime = field(p, "runtimeId")?;
        let thread = field(p, "threadId")?;
        let (generation, rpc, desktop, process) = {
            let s = self.state.lock().await;
            let r = s
                .runtimes
                .get(runtime)
                .filter(|r| r.rpc.online())
                .context("Runtime offline")?;
            (
                r.connection_id.clone(),
                r.rpc.clone(),
                r.source == "desktop",
                r.native_process.clone(),
            )
        };
        let mut identity = json!({});
        let mut unavailable = None;
        if desktop {
            match rpc.call("pocket/status", json!({})).await {
                Ok(status) => {
                    if status["runtimeId"] != runtime
                        || (process.is_some()
                            && serde_json::from_value::<Option<NativeProcess>>(
                                status["nativeProcess"].clone(),
                            )
                            .ok()
                                != Some(process))
                    {
                        bail!("Native bridge identity changed");
                    }
                    if let Some(version) = status["bridgeVersion"]
                        .as_str()
                        .filter(|v| v.len() <= 128 && !v.chars().any(char::is_control))
                    {
                        identity["bridgeVersion"] = json!(version);
                    }
                    if let Some(protocol) = status["permissionProtocol"].as_u64() {
                        identity["permissionProtocol"] = json!(protocol);
                    }
                    let (state, reason) = crate::capabilities::permission_discovery(&status);
                    let parsed = status
                        .get("nativeCapabilities")
                        .and_then(|v| serde_json::from_value(v.clone()).ok());
                    if state == "ready" && parsed.is_none() {
                        unavailable = Some(("error", "native_schema_failed"));
                    }
                    let caps = parsed.unwrap_or_default();
                    let mut s = self.state.lock().await;
                    let r = s
                        .runtimes
                        .get_mut(runtime)
                        .filter(|r| r.connection_id == generation && r.rpc.online())
                        .ok_or(RpcFailure::Disconnected)?;
                    r.capabilities = caps;
                    if state != "ready" {
                        unavailable = Some((state, reason));
                    }
                }
                Err(_) => unavailable = Some(("error", "native_status_failed")),
            }
        } else if rpc.capability_failed() {
            unavailable = Some(("error", "native_schema_failed"));
        }
        if unavailable.is_none() {
            let s = self.state.lock().await;
            let r = s
                .runtimes
                .get(runtime)
                .filter(|r| r.connection_id == generation && r.rpc.online())
                .ok_or(RpcFailure::Disconnected)?;
            if !r.capabilities.supports(
                "thread/settings/update",
                &crate::permission_policy::preset(thread, "request").unwrap(),
            ) && !r.capabilities.supports(
                "thread/settings/update",
                &json!({"threadId":thread,"permissions":"profile"}),
            ) {
                unavailable = Some(("unsupported", "native_unsupported"));
            }
        }
        if let Some((status, reason)) = unavailable {
            let s = self.state.lock().await;
            if !s.devices.devices.contains_key(device) {
                return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
            }
            let r = s
                .runtimes
                .get(runtime)
                .filter(|r| r.connection_id == generation)
                .ok_or(RpcFailure::Disconnected)?;
            let mut response = permissions::response(
                runtime,
                thread,
                &generation,
                &json!({}),
                &r.capabilities,
                &BTreeMap::new(),
            );
            permissions::unavailable(&mut response, status, reason);
            response
                .as_object_mut()
                .unwrap()
                .extend(identity.as_object().unwrap().clone());
            return Ok((response, BTreeMap::new()));
        }
        // A bridge cache can know a historical thread without proving that the
        // requested ID belongs to this live connection. Verify before exposing
        // thread state or consulting its scoped configuration.
        let verified = self
            .verify_permission_thread(device, runtime, thread, &generation)
            .await?;
        if !verified {
            let s = self.state.lock().await;
            let r = s
                .runtimes
                .get(runtime)
                .filter(|r| r.connection_id == generation && r.rpc.online())
                .ok_or(RpcFailure::Disconnected)?;
            let mut response = permissions::response(
                runtime,
                thread,
                &generation,
                &json!({}),
                &r.capabilities,
                &BTreeMap::new(),
            );
            permissions::unavailable(&mut response, "error", "native_read_failed");
            response
                .as_object_mut()
                .unwrap()
                .extend(identity.as_object().unwrap().clone());
            return Ok((response, BTreeMap::new()));
        }
        let read_failed = desktop && self.read_settings(device, p).await.is_err();
        self.check_generation(runtime, &generation).await?;
        let (rpc, caps) = {
            let s = self.state.lock().await;
            let r = s
                .runtimes
                .get(runtime)
                .filter(|r| r.connection_id == generation && r.rpc.online())
                .ok_or(RpcFailure::Disconnected)?;
            (r.rpc.clone(), r.capabilities.clone())
        };
        let mut custom = BTreeMap::new();
        let mut custom_state = "unsupported";
        if caps.supports(
            "thread/settings/update",
            &json!({"threadId":thread,"permissions":"profile"}),
        ) && caps.supports("config/read", &json!({"includeLayers":false,"cwd":"."}))
        {
            let proof = rpc
                .call(
                    "thread/read",
                    json!({"threadId":thread,"includeTurns":false}),
                )
                .await?;
            if proof["thread"]["id"] != thread {
                bail!("Native thread identity mismatch");
            }
            custom_state = "error";
            if let Some(cwd) = proof["thread"]["cwd"]
                .as_str()
                .filter(|v| crate::settings::valid_thread(v))
                && let Ok(config) = rpc
                    .call("config/read", json!({"includeLayers":false,"cwd":cwd}))
                    .await
            {
                custom = permissions::profiles(&config, &caps);
                custom_state = "ready";
            }
        }
        let s = self.state.lock().await;
        if !s.devices.devices.contains_key(device) {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        let r = s
            .runtimes
            .get(runtime)
            .filter(|r| r.connection_id == generation && r.rpc.online())
            .ok_or(RpcFailure::Disconnected)?;
        let mut response = permissions::response(
            runtime,
            thread,
            &generation,
            &r.settings.read(thread)["settings"],
            &r.capabilities,
            &custom,
        );
        if read_failed {
            permissions::unavailable(&mut response, "error", "native_read_failed");
        }
        response["customProfilesState"] = json!(custom_state);
        if custom_state == "error" {
            response["customProfilesReasonCode"] = json!("config_read_failed");
        }
        response
            .as_object_mut()
            .unwrap()
            .extend(identity.as_object().unwrap().clone());
        Ok((response, custom))
    }
    async fn verify_permission_thread(
        &self,
        device: &str,
        runtime: &str,
        thread: &str,
        generation: &str,
    ) -> Result<bool> {
        let (rpc, observation) = {
            let mut s = self.state.lock().await;
            let r = s
                .runtimes
                .get_mut(runtime)
                .filter(|r| r.connection_id == generation && r.rpc.online())
                .ok_or(RpcFailure::Disconnected)?;
            if !r.capabilities.supports(
                "thread/read",
                &json!({"threadId":thread,"includeTurns":false}),
            ) {
                return Ok(false);
            }
            (
                r.rpc.clone(),
                r.settings
                    .prepare("thread/read", &json!({"threadId":thread}))
                    .unwrap(),
            )
        };
        let result = rpc
            .call(
                "thread/read",
                json!({"threadId":thread,"includeTurns":false}),
            )
            .await;
        let mut s = self.state.lock().await;
        if !s.devices.devices.contains_key(device) {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        let r = s
            .runtimes
            .get_mut(runtime)
            .filter(|r| r.connection_id == generation && r.rpc.online())
            .ok_or(RpcFailure::Disconnected)?;
        let Ok(result) = result else {
            return Ok(false);
        };
        if result["thread"]["id"] != thread {
            return Ok(false);
        }
        if let Some(event) = r.settings.accepted(observation, &result) {
            self.publish_settings(runtime, r, event);
        }
        Ok(true)
    }
    pub(super) async fn update_permissions(&self, device: &str, p: &Value) -> Result<Value> {
        let runtime = field(p, "runtimeId")?;
        let thread = field(p, "threadId")?;
        let (before, custom) = self
            .permission_data(device, &json!({"runtimeId":runtime,"threadId":thread}))
            .await?;
        if before["revision"] != p["expectedRevision"] {
            return Err(classify(
                anyhow::anyhow!("Permission revision changed; read current settings"),
                Code::Rejected,
            ));
        }
        if before["status"] != "ready" || !before["revision"].is_string() {
            return Err(classify(
                anyhow::anyhow!("Permission state is not authoritative"),
                Code::Unsupported,
            ));
        }
        let id = field(p, "presetId")?;
        if !before["presets"]
            .as_array()
            .is_some_and(|list| list.iter().any(|v| v["id"] == id && v["enabled"] == true))
        {
            return Err(classify(
                anyhow::anyhow!("Permission preset unavailable"),
                Code::Unsupported,
            ));
        }
        let params = crate::permission_policy::preset(thread, id)
            .or_else(|| {
                custom
                    .get(id)
                    .map(|name| json!({"threadId":thread,"permissions":name}))
            })
            .context("Preset unavailable")?;
        let (generation, checkpoint) = {
            let mut s = self.state.lock().await;
            let r = s
                .runtimes
                .get_mut(runtime)
                .filter(|r| r.rpc.online())
                .ok_or(RpcFailure::Disconnected)?;
            (r.connection_id.clone(), r.settings.checkpoint())
        };
        self.native_request_checked(
            device,
            &json!({"runtimeId":runtime,"method":"thread/settings/update","params":params}),
            Some((field(p, "expectedRevision")?, &custom)),
        )
        .await?;
        self.check_generation(runtime, &generation).await?;
        let proven = self
            .await_permission_proof(device, runtime, thread, &generation, checkpoint)
            .await?;
        if !proven {
            // Readback is safe after an ambiguous ACK; never replay the mutation.
            self.fresh_permission_readback(device, runtime, thread, &generation, checkpoint)
                .await?;
        }
        let s = self.state.lock().await;
        if !s.devices.devices.contains_key(device) {
            return Err(classify(
                anyhow::anyhow!("Device revoked after dispatch"),
                Code::Unknown,
            ));
        }
        let r = s
            .runtimes
            .get(runtime)
            .filter(|r| r.connection_id == generation && r.rpc.online())
            .ok_or(RpcFailure::Disconnected)?;
        let mut response = permissions::response(
            runtime,
            thread,
            &r.connection_id,
            &r.settings.read(thread)["settings"],
            &r.capabilities,
            &custom,
        );
        if !r.settings.permissions_proven_since(thread, checkpoint)
            || response["currentPresetId"] != id
        {
            return Err(classify(
                anyhow::anyhow!(
                    "Permission outcome unconfirmed; read current settings before another command"
                ),
                Code::Unknown,
            ));
        }
        for key in ["bridgeVersion", "permissionProtocol"] {
            if let Some(v) = before.get(key) {
                response[key] = v.clone();
            }
        }
        Ok(response)
    }
    async fn await_permission_proof(
        &self,
        device: &str,
        runtime: &str,
        thread: &str,
        generation: &str,
        checkpoint: u64,
    ) -> Result<bool> {
        // Native ACKs can precede their settings notification. Wait for bounded
        // native observations, never infer success or replay the update.
        for delay in [0, 25, 75, 150] {
            if delay > 0 {
                tokio::time::sleep(Duration::from_millis(delay)).await;
            }
            let s = self.state.lock().await;
            if !s.devices.devices.contains_key(device) {
                return Err(classify(
                    anyhow::anyhow!("Device revoked after dispatch"),
                    Code::Unknown,
                ));
            }
            let r = s
                .runtimes
                .get(runtime)
                .filter(|r| r.connection_id == generation && r.rpc.online())
                .ok_or(RpcFailure::Disconnected)?;
            if r.settings.permissions_proven_since(thread, checkpoint) {
                return Ok(true);
            }
        }
        Ok(false)
    }
    async fn fresh_permission_readback(
        &self,
        device: &str,
        runtime: &str,
        thread: &str,
        generation: &str,
        checkpoint: u64,
    ) -> Result<()> {
        let (rpc, observation) = {
            let mut s = self.state.lock().await;
            let r = s
                .runtimes
                .get_mut(runtime)
                .filter(|r| r.connection_id == generation && r.rpc.online())
                .ok_or(RpcFailure::Disconnected)?;
            if !r.capabilities.supports(
                "thread/read",
                &json!({"threadId":thread,"includeTurns":false}),
            ) {
                return Err(classify(
                    anyhow::anyhow!("Fresh native permission readback unavailable"),
                    Code::Unknown,
                ));
            }
            (
                r.rpc.clone(),
                r.settings
                    .prepare("thread/read", &json!({"threadId":thread}))
                    .unwrap(),
            )
        };
        let result = rpc
            .call(
                "thread/read",
                json!({"threadId":thread,"includeTurns":false}),
            )
            .await
            .map_err(|e| classify(e.into(), Code::Unknown))?;
        if result["thread"]["id"] != thread {
            return Err(classify(
                anyhow::anyhow!("Native readback thread mismatch"),
                Code::Unknown,
            ));
        }
        let mut s = self.state.lock().await;
        if !s.devices.devices.contains_key(device) {
            return Err(classify(
                anyhow::anyhow!("Device revoked after dispatch"),
                Code::Unknown,
            ));
        }
        let r = s
            .runtimes
            .get_mut(runtime)
            .filter(|r| r.connection_id == generation && r.rpc.online())
            .ok_or(RpcFailure::Disconnected)?;
        if let Some(event) = r.settings.accepted(observation, &result) {
            self.publish_settings(runtime, r, event);
        }
        if r.settings.permissions_proven_since(thread, checkpoint) {
            return Ok(());
        }
        // Native thread/read is metadata-only on some protocols. An already
        // loaded, exclusively owned thread may be resumed without overrides to
        // obtain the native effective settings; this never starts a turn.
        let key = ThreadKey {
            computer_id: self.config.computer_id.clone(),
            config_store_id: r.store.clone(),
            native_thread_id: thread.to_owned(),
        };
        let params = json!({"threadId":thread,"excludeTurns":true});
        if !r.loaded.contains(thread)
            || !r.capabilities.supports("thread/resume", &params)
            || !self
                .directory(&s)
                .map_err(|e| classify(e, Code::Unknown))?
                .resolve_write(&key)
                .is_ok_and(|owner| owner.runtime_id == runtime)
        {
            return Err(classify(
                anyhow::anyhow!("Permission readback owner unavailable"),
                Code::Unknown,
            ));
        }
        let r = s.runtimes.get_mut(runtime).unwrap();
        let observed = r.settings.prepare("thread/resume", &params).unwrap();
        let rpc = r.rpc.clone();
        drop(s);
        let result = rpc
            .call("thread/resume", params)
            .await
            .map_err(|e| classify(e.into(), Code::Unknown))?;
        if result["thread"]["id"] != thread {
            return Err(classify(
                anyhow::anyhow!("Native resume readback thread mismatch"),
                Code::Unknown,
            ));
        }
        let mut s = self.state.lock().await;
        if !s.devices.devices.contains_key(device) {
            return Err(classify(
                anyhow::anyhow!("Device revoked after dispatch"),
                Code::Unknown,
            ));
        }
        let r = s
            .runtimes
            .get_mut(runtime)
            .filter(|r| r.connection_id == generation && r.rpc.online())
            .ok_or(RpcFailure::Disconnected)?;
        if let Some(event) = r.settings.accepted(observed, &result) {
            self.publish_settings(runtime, r, event);
        }
        Ok(())
    }
}
fn context_envelope(runtime: &str, thread: &str, mut v: Value) -> Value {
    v["runtimeId"] = json!(runtime);
    v["threadId"] = json!(thread);
    v
}

#[cfg(test)]
mod tests {
    use super::super::tests::{finish, fixture, loaded, peer};
    use super::*;
    fn usage(n: u64) -> Value {
        json!({"last":{"inputTokens":n,"outputTokens":2,"totalTokens":n+2},"total":{"inputTokens":100,"outputTokens":20,"totalTokens":120},"modelContextWindow":1000})
    }
    #[tokio::test]
    async fn v052_unverified_thread_never_discloses_cached_settings_or_config() {
        let (_dir, b, mut native) = permission_fixture().await;
        let task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request(
                    "phone",
                    "thread/permissions/read",
                    json!({"runtimeId":"r","threadId":"t"}),
                )
                .await
            }
        });
        let read = native.recv().await;
        assert_eq!(read["method"], "thread/read");
        native
            .reply(&read, json!({"thread":{"id":"other","cwd":"private"}}))
            .await;
        let result = finish(task).await.unwrap();
        assert_eq!(result["status"], "error");
        assert!(result["currentPresetId"].is_null());
        assert!(result["currentLabel"].is_null());
        assert!(result["revision"].is_null());
        assert!(!result.to_string().contains("private"));
        native.assert_idle().await;
    }
    #[tokio::test]
    async fn v052_delayed_native_notification_confirms_ack_without_replay_or_resume() {
        let (_dir, b, mut native) = permission_fixture().await;
        let current = current_permissions(&b).await;
        let p = json!({"runtimeId":"r","threadId":"t","presetId":"full","expectedRevision":current["revision"],"commandId":"delayed-native"});
        let task = dispatch_permission(&b, &mut native, p).await;
        let update = native.recv().await;
        native.reply(&update, json!({})).await;
        tokio::time::sleep(Duration::from_millis(10)).await;
        native
            .send(crate::settings::notification(
                "t",
                crate::permission_policy::preset("t", "full").unwrap(),
            ))
            .await;
        assert_eq!(finish(task).await.unwrap()["currentPresetId"], "full");
        native.assert_idle().await;
    }
    #[tokio::test]
    async fn v052_desktop_cached_state_cannot_confirm_empty_ack_even_for_same_preset() {
        let (_dir, b, mut native) = permission_fixture().await;
        let caps = b.state.lock().await.runtimes["r"].capabilities.clone();
        b.state.lock().await.runtimes.get_mut("r").unwrap().source = "desktop".into();
        let current = current_permissions(&b).await;
        let p = json!({"runtimeId":"r","threadId":"t","presetId":"request","expectedRevision":current["revision"],"commandId":"same-empty"});
        let task = tokio::spawn({
            let b = b.clone();
            async move { b.request("phone", "thread/permissions/update", p).await }
        });
        let status = native.recv().await;
        native.reply(&status, json!({"runtimeId":"r","initialized":true,"schemaState":"ready","permissionProtocol":1,"nativeCapabilities":caps})).await;
        let proof = native.recv().await;
        assert_eq!(proof["method"], "thread/read");
        native.reply(&proof, json!({"thread":{"id":"t"}})).await;
        let cache = native.recv().await;
        assert_eq!(cache["method"], "pocket/threadSettings");
        native.reply(&cache, json!({"threadId":"t","known":true,"settings":crate::permission_policy::preset("t", "request").unwrap()})).await;
        loaded(&mut native, &["t"]).await;
        let update = native.recv().await;
        assert_eq!(update["method"], "thread/settings/update");
        native.reply(&update, json!({})).await;
        let read = native.recv().await;
        assert_eq!(
            read["method"], "thread/read",
            "readback must be fresh native data rather than the earlier bridge cache"
        );
        native.reply(&read, json!({"thread":{"id":"t"}})).await;
        let resume = native.recv().await;
        assert_eq!(resume["method"], "thread/resume");
        assert_eq!(
            resume["params"],
            json!({"threadId":"t","excludeTurns":true})
        );
        native.reply(&resume, json!({"thread":{"id":"t"}})).await;
        assert_eq!(
            crate::error::code(&finish(task).await.unwrap_err()),
            Code::Unknown
        );
        native.assert_idle().await;
    }
    #[tokio::test]
    async fn v051_context_event_wins_snapshot_and_cache_is_numeric() {
        let (_dir, b) = fixture().await;
        let mut native = peer(&b, "r", "cli").await;
        let task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request(
                    "phone",
                    "thread/context/read",
                    json!({"runtimeId":"r","threadId":"t"}),
                )
                .await
            }
        });
        let read = native.recv().await;
        assert_eq!(read["method"], "thread/read");
        // Deterministic event while the history RPC is in flight.
        b.state.lock().await.runtimes.get_mut("r").unwrap().usage.event(&json!({"method":"thread/tokenUsage/updated","params":{"threadId":"t","turnId":"live","tokenUsage":usage(50)}}));
        native
            .reply(&read, json!({"thread":{"id":"t","tokenUsage":usage(10)}}))
            .await;
        let v = finish(task).await.unwrap();
        assert_eq!(v["usage"]["last"]["inputTokens"], 50);
        assert_eq!(v["turnId"], "live");
        assert_eq!(v["status"], "ok");
        assert!(v["updatedAt"].as_u64().unwrap() > 1_000_000_000_000);
        let v = b
            .request(
                "phone",
                "thread/context/read",
                json!({"runtimeId":"r","threadId":"t"}),
            )
            .await
            .unwrap();
        assert_eq!(v["usage"]["last"]["inputTokens"], 50);
        assert!(
            b.request(
                "phone",
                "thread/context/read",
                json!({"runtimeId":"r","threadId":"t","path":"secret"})
            )
            .await
            .is_err()
        );
    }
    #[tokio::test]
    async fn v051_context_wrong_thread_is_content_failure_not_transport_failure() {
        let (_dir, b) = fixture().await;
        let mut native = peer(&b, "r", "cli").await;
        let task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request(
                    "phone",
                    "thread/context/read",
                    json!({"runtimeId":"r","threadId":"t"}),
                )
                .await
            }
        });
        let read = native.recv().await;
        native
            .reply(
                &read,
                json!({"thread":{"id":"other","tokenUsage":usage(1)}}),
            )
            .await;
        assert_eq!(finish(task).await.unwrap()["status"], "failed");
        assert!(b.state.lock().await.runtimes["r"].rpc.online());
    }
    async fn permission_fixture() -> (
        tempfile::TempDir,
        Arc<Broker>,
        crate::native::test_peer::Peer,
    ) {
        let (dir, b) = fixture().await;
        let native = peer(&b, "r", "cli").await;
        // Permission controls belong to an explicitly opened phone conversation.
        // Inventory refresh must never subscribe to every loaded CLI thread.
        b.watch_cli("r", "t").await;
        {
            let mut s = b.state.lock().await;
            let r = s.runtimes.get_mut("r").unwrap();
            r.capabilities.0.insert(
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
            r.settings.event(&crate::settings::notification(
                "t",
                crate::permission_policy::preset("t", "request").unwrap(),
            ));
        }
        (dir, b, native)
    }
    #[tokio::test]
    async fn v051_permission_update_owned_idempotent_and_fast_independent() {
        let (_dir, b, mut native) = permission_fixture().await;
        let current = {
            let s = b.state.lock().await;
            let r = &s.runtimes["r"];
            crate::permissions::response(
                "r",
                "t",
                &r.connection_id,
                &r.settings.read("t")["settings"],
                &r.capabilities,
                &BTreeMap::new(),
            )
        };
        assert_eq!(current["currentPresetId"], "request");
        assert!(
            Broker::runtime_values(&*b.state.lock().await)[0]["capabilities"]
                .as_array()
                .unwrap()
                .contains(&json!("thread/settings/update"))
        );
        let request = json!({"runtimeId":"r","threadId":"t","presetId":"full","expectedRevision":current["revision"],"commandId":"perm-1"});
        let task = tokio::spawn({
            let b = b.clone();
            let p = request.clone();
            async move { b.request("phone", "thread/permissions/update", p).await }
        });
        let read = native.recv().await;
        assert_eq!(read["method"], "thread/read");
        native.reply(&read, json!({"thread":{"id":"t"}})).await;
        loaded(&mut native, &["t"]).await;
        // CLI refresh subscribes to loaded threads once.
        let subscribe = native.recv().await;
        assert_eq!(subscribe["method"], "thread/resume");
        native.reply(&subscribe, json!({"thread":{"id":"t"}})).await;
        let update = native.recv().await;
        assert_eq!(update["method"], "thread/settings/update");
        assert_eq!(
            update["params"],
            crate::permission_policy::preset("t", "full").unwrap()
        );
        native.reply(&update, json!({"threadId":"t","threadSettings":crate::permission_policy::preset("t", "full").unwrap()})).await;
        let result = finish(task).await.unwrap();
        assert_eq!(result["currentPresetId"], "full");
        assert_ne!(result["revision"], current["revision"]);
        assert_eq!(
            b.request("phone", "thread/permissions/update", request)
                .await
                .unwrap(),
            result
        );
        assert!(b.request("phone","codex/request",json!({"runtimeId":"r","commandId":"bypass","method":"thread/settings/update","params":crate::permission_policy::preset("t","full").unwrap()})).await.is_err());
    }
    async fn current_permissions(b: &Broker) -> Value {
        let s = b.state.lock().await;
        let r = &s.runtimes["r"];
        permissions::response(
            "r",
            "t",
            &r.connection_id,
            &r.settings.read("t")["settings"],
            &r.capabilities,
            &BTreeMap::new(),
        )
    }
    async fn dispatch_permission(
        b: &Arc<Broker>,
        native: &mut crate::native::test_peer::Peer,
        p: Value,
    ) -> tokio::task::JoinHandle<Result<Value>> {
        let task = tokio::spawn({
            let b = b.clone();
            async move { b.request("phone", "thread/permissions/update", p).await }
        });
        let read = native.recv().await;
        assert_eq!(read["method"], "thread/read");
        native.reply(&read, json!({"thread":{"id":"t"}})).await;
        loaded(native, &["t"]).await;
        let subscribe = native.recv().await;
        assert_eq!(subscribe["method"], "thread/resume");
        native.reply(&subscribe, json!({"thread":{"id":"t"}})).await;
        task
    }
    #[tokio::test]
    async fn v052_all_three_presets_use_native_confirmation_and_keep_other_threads() {
        for id in ["request", "auto", "full"] {
            let (_dir, b, mut native) = permission_fixture().await;
            b.state
                .lock()
                .await
                .runtimes
                .get_mut("r")
                .unwrap()
                .settings
                .event(&crate::settings::notification(
                    "other",
                    crate::permission_policy::preset("other", "request").unwrap(),
                ));
            let current = current_permissions(&b).await;
            let p = json!({"runtimeId":"r","threadId":"t","presetId":id,"expectedRevision":current["revision"],"commandId":format!("three-{id}")});
            let task = dispatch_permission(&b, &mut native, p.clone()).await;
            let update = native.recv().await;
            assert_eq!(
                update["params"],
                crate::permission_policy::preset("t", id).unwrap()
            );
            native.reply(&update, json!({"threadId":"t","settings":crate::permission_policy::preset("t", id).unwrap()})).await;
            let result = finish(task).await.unwrap();
            assert_eq!(result["currentPresetId"], id);
            assert_eq!(result["status"], "ready");
            assert_eq!(
                b.state.lock().await.runtimes["r"].settings.read("other")["settings"]["approvalPolicy"],
                "on-request"
            );
            assert_eq!(
                b.request("phone", "thread/permissions/update", p.clone())
                    .await
                    .unwrap(),
                result
            );
            let mut conflicting = p;
            conflicting["presetId"] = json!("auto");
            conflicting["expectedRevision"] = json!("other-revision");
            assert_eq!(
                crate::error::code(
                    &b.request("phone", "thread/permissions/update", conflicting)
                        .await
                        .unwrap_err()
                ),
                Code::Unknown
            );
            native.assert_idle().await;
        }
    }
    #[tokio::test]
    async fn v052_empty_ack_requires_fresh_proof_or_stays_unknown_without_replay() {
        for confirmed in [true, false] {
            let (_dir, b, mut native) = permission_fixture().await;
            let current = current_permissions(&b).await;
            let p = json!({"runtimeId":"r","threadId":"t","presetId":"full","expectedRevision":current["revision"],"commandId":"empty-ack"});
            let task = dispatch_permission(&b, &mut native, p.clone()).await;
            let update = native.recv().await;
            native.reply(&update, json!({})).await;
            let read = native.recv().await;
            assert_eq!(read["method"], "thread/read");
            native.reply(&read, json!({"thread":{"id":"t","settings":crate::permission_policy::preset("t", if confirmed { "full" } else { "request" }).unwrap()}})).await;
            let result = finish(task).await;
            if confirmed {
                assert_eq!(result.unwrap()["currentPresetId"], "full");
            } else {
                assert_eq!(crate::error::code(&result.unwrap_err()), Code::Unknown);
                assert_eq!(
                    crate::error::code(
                        &b.request("phone", "thread/permissions/update", p)
                            .await
                            .unwrap_err()
                    ),
                    Code::Unknown
                );
                assert_eq!(
                    b.request("phone", "commands/status", json!({"commandId":"empty-ack"}))
                        .await
                        .unwrap()["status"],
                    "unknown"
                );
            }
            native.assert_idle().await;
        }
    }
    #[tokio::test]
    async fn v052_permission_read_refreshes_pending_old_failed_and_unsupported_bridge() {
        for (status, expected) in [
            (
                json!({"initialized":true,"schemaPending":true}),
                "initializing",
            ),
            (
                json!({"initialized":true,"schemaPending":false}),
                "update_required",
            ),
            (
                json!({"initialized":true,"permissionProtocol":1,"schemaState":"error"}),
                "error",
            ),
            (
                json!({"initialized":true,"permissionProtocol":1,"schemaState":"ready","nativeCapabilities":{}}),
                "unsupported",
            ),
        ] {
            let (_dir, b, mut native) = permission_fixture().await;
            b.state.lock().await.runtimes.get_mut("r").unwrap().source = "desktop".into();
            let task = tokio::spawn({
                let b = b.clone();
                async move {
                    b.request(
                        "phone",
                        "thread/permissions/read",
                        json!({"runtimeId":"r","threadId":"t"}),
                    )
                    .await
                }
            });
            let read = native.recv().await;
            assert_eq!(read["method"], "pocket/status");
            let mut status = status;
            status["runtimeId"] = json!("r");
            native.reply(&read, status).await;
            let result = finish(task).await.unwrap();
            assert_eq!(result["status"], expected);
            assert!(result["revision"].is_null());
            assert!(result["currentPresetId"].is_null());
            assert!(result["currentLabel"].is_null());
            assert!(
                result["presets"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .all(|p| p["enabled"] == false)
            );
            native.assert_idle().await;
        }
    }
    #[tokio::test]
    async fn v052_permission_revision_race_is_rejected_at_dispatch() {
        let (_dir, b, mut native) = permission_fixture().await;
        let current = current_permissions(&b).await;
        let p = json!({"runtimeId":"r","threadId":"t","presetId":"full","expectedRevision":current["revision"],"commandId":"race"});
        let task = tokio::spawn({
            let b = b.clone();
            async move { b.request("phone", "thread/permissions/update", p).await }
        });
        let read = native.recv().await;
        native.reply(&read, json!({"thread":{"id":"t"}})).await;
        let inventory = native.recv().await;
        assert_eq!(inventory["method"], "thread/loaded/list");
        b.state
            .lock()
            .await
            .runtimes
            .get_mut("r")
            .unwrap()
            .settings
            .event(&crate::settings::notification(
                "t",
                crate::permission_policy::preset("t", "auto").unwrap(),
            ));
        native.reply(&inventory, json!({"data":["t"]})).await;
        let subscribe = native.recv().await;
        native.reply(&subscribe, json!({"thread":{"id":"t"}})).await;
        assert!(finish(task).await.is_err());
        native.assert_idle().await;
    }
    #[tokio::test]
    async fn v052_foreign_or_ambiguous_owner_never_gets_permission_update() {
        for ambiguous in [false, true] {
            let (_dir, b, mut native) = permission_fixture().await;
            let mut foreign = peer(&b, "z", "desktop").await;
            let current = current_permissions(&b).await;
            let p = json!({"runtimeId":"r","threadId":"t","presetId":"full","expectedRevision":current["revision"],"commandId":"foreign"});
            let task = tokio::spawn({
                let b = b.clone();
                async move { b.request("phone", "thread/permissions/update", p).await }
            });
            let read = native.recv().await;
            native.reply(&read, json!({"thread":{"id":"t"}})).await;
            loaded(&mut native, if ambiguous { &["t"] } else { &[] }).await;
            loaded(&mut foreign, &["t"]).await;
            assert!(finish(task).await.is_err());
            native.assert_idle().await;
            foreign.assert_idle().await;
        }
    }
    #[tokio::test]
    async fn v052_same_bridge_transitions_from_initializing_to_ready() {
        let (_dir, b, mut native) = permission_fixture().await;
        let caps = b.state.lock().await.runtimes["r"].capabilities.clone();
        b.state.lock().await.runtimes.get_mut("r").unwrap().source = "desktop".into();
        for pending in [true, false] {
            let task = tokio::spawn({
                let b = b.clone();
                async move {
                    b.request(
                        "phone",
                        "thread/permissions/read",
                        json!({"runtimeId":"r","threadId":"t"}),
                    )
                    .await
                }
            });
            let status = native.recv().await;
            native.reply(&status, json!({"runtimeId":"r","initialized":true,"schemaPending":pending,"schemaState":if pending {"initializing"} else {"ready"},"permissionProtocol":1,"bridgeVersion":"synthetic-build","nativeCapabilities":if pending {json!({})}else{serde_json::to_value(&caps).unwrap()}})).await;
            if !pending {
                let proof = native.recv().await;
                assert_eq!(proof["method"], "thread/read");
                native.reply(&proof, json!({"thread":{"id":"t"}})).await;
                let read = native.recv().await;
                assert_eq!(read["method"], "pocket/threadSettings");
                native.reply(&read, json!({"threadId":"t","known":true,"settings":crate::permission_policy::preset("t", "request").unwrap()})).await;
            }
            let result = finish(task).await.unwrap();
            assert_eq!(
                result["status"],
                if pending { "initializing" } else { "ready" }
            );
            assert_eq!(result["revision"].is_string(), !pending);
            assert_eq!(result["bridgeVersion"], "synthetic-build");
            assert_eq!(result["permissionProtocol"], 1);
            native.assert_idle().await;
        }
    }
    #[tokio::test]
    async fn v052_custom_choice_requires_fresh_thread_config_and_native_profile_proof() {
        let (_dir, b, mut native) = permission_fixture().await;
        b.state
            .lock()
            .await
            .runtimes
            .get_mut("r")
            .unwrap()
            .capabilities
            .0
            .get_mut("thread/settings/update")
            .unwrap()
            .insert("permissions".into());
        let config = json!({"config":{"permissions":{"existing":{"extends":":workspace"},"invalid":{"apiKey":"secret"}}}});
        let read_task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request(
                    "phone",
                    "thread/permissions/read",
                    json!({"runtimeId":"r","threadId":"t"}),
                )
                .await
            }
        });
        for _ in 0..2 {
            let read = native.recv().await;
            assert_eq!(read["method"], "thread/read");
            native
                .reply(
                    &read,
                    json!({"thread":{"id":"t","cwd":"C:/verified-private"}}),
                )
                .await;
        }
        let read = native.recv().await;
        assert_eq!(read["method"], "config/read");
        assert_eq!(
            read["params"],
            json!({"cwd":"C:/verified-private","includeLayers":false})
        );
        native.reply(&read, config.clone()).await;
        let current = finish(read_task).await.unwrap();
        let choice = current["presets"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| p["id"].as_str().unwrap().starts_with("custom-"))
            .unwrap()["id"]
            .clone();
        assert_eq!(current["presets"].as_array().unwrap().len(), 4);
        assert!(!current.to_string().contains("verified-private"));
        let p = json!({"runtimeId":"r","threadId":"t","presetId":choice,"expectedRevision":current["revision"],"commandId":"custom-choice"});
        let task = tokio::spawn({
            let b = b.clone();
            async move { b.request("phone", "thread/permissions/update", p).await }
        });
        for _ in 0..2 {
            let read = native.recv().await;
            assert_eq!(read["method"], "thread/read");
            native
                .reply(
                    &read,
                    json!({"thread":{"id":"t","cwd":"C:/verified-private"}}),
                )
                .await;
        }
        let read = native.recv().await;
        native.reply(&read, config).await;
        loaded(&mut native, &["t"]).await;
        let subscribe = native.recv().await;
        native.reply(&subscribe, json!({"thread":{"id":"t"}})).await;
        let update = native.recv().await;
        assert_eq!(
            update["params"],
            json!({"threadId":"t","permissions":"existing"})
        );
        native
            .reply(
                &update,
                json!({"threadId":"t","settings":{"activePermissionProfile":{"id":"existing"}}}),
            )
            .await;
        let result = finish(task).await.unwrap();
        assert_eq!(result["status"], "ready");
        assert_eq!(result["currentPresetId"], choice);
        native.assert_idle().await;
    }
    #[tokio::test]
    async fn v051_permission_lost_ack_is_unknown_never_replayed() {
        let (_dir, b, mut native) = permission_fixture().await;
        let current = {
            let s = b.state.lock().await;
            let r = &s.runtimes["r"];
            permissions::response(
                "r",
                "t",
                &r.connection_id,
                &r.settings.read("t")["settings"],
                &r.capabilities,
                &BTreeMap::new(),
            )
        };
        let request = json!({"runtimeId":"r","threadId":"t","presetId":"full","expectedRevision":current["revision"],"commandId":"lost-perm"});
        let task = tokio::spawn({
            let b = b.clone();
            let p = request.clone();
            async move { b.request("phone", "thread/permissions/update", p).await }
        });
        let read = native.recv().await;
        native.reply(&read, json!({"thread":{"id":"t"}})).await;
        loaded(&mut native, &["t"]).await;
        let subscribe = native.recv().await;
        native.reply(&subscribe, json!({"thread":{"id":"t"}})).await;
        let update = native.recv().await;
        assert_eq!(update["method"], "thread/settings/update");
        drop(native);
        let error = finish(task).await.unwrap_err();
        assert_eq!(crate::error::code(&error), Code::Unknown);
        let repeated = b
            .request("phone", "thread/permissions/update", request)
            .await
            .unwrap_err();
        assert_eq!(crate::error::code(&repeated), Code::Unknown);
        assert_eq!(
            b.request("phone", "commands/status", json!({"commandId":"lost-perm"}))
                .await
                .unwrap()["status"],
            "unknown"
        );
    }
    #[tokio::test]
    async fn v051_permission_revision_conflict_never_dispatches() {
        let (_dir, b, mut native) = permission_fixture().await;
        let task = tokio::spawn({
            let b = b.clone();
            async move {
                b.request("phone","thread/permissions/update",json!({"runtimeId":"r","threadId":"t","presetId":"full","expectedRevision":"stale","commandId":"perm-2"})).await
            }
        });
        let read = native.recv().await;
        native.reply(&read, json!({"thread":{"id":"t"}})).await;
        assert!(finish(task).await.is_err());
        assert_eq!(
            b.state.lock().await.runtimes["r"].settings.read("t")["settings"]["approvalPolicy"],
            "on-request"
        );
    }
}
