//! Foreground permission observation; never starts or writes to the Actor.
use super::*;

impl Broker {
    pub(in crate::broker) async fn assistant_project_read(
        &self,
        device: &str,
        p: &Value,
    ) -> Result<Value> {
        let fields = [
            "runtimeId",
            "sourceRuntimeId",
            "sourceConfigStoreId",
            "threadId",
        ];
        if p.as_object().is_none_or(|o| o.len() != fields.len())
            || fields[..3].iter().any(|f| !valid_scope_id(&p[*f]))
            || !p["threadId"]
                .as_str()
                .is_some_and(|t| t.len() <= 256 && crate::settings::valid_thread(t))
        {
            return Err(classify(
                anyhow::anyhow!("Invalid assistant project selector"),
                Code::InvalidParams,
            ));
        }
        let runtime = field(p, "runtimeId")?;
        let source = field(p, "sourceRuntimeId")?;
        let store = field(p, "sourceConfigStoreId")?;
        let thread = field(p, "threadId")?;
        let actor = self
            .assistant
            .try_lock()
            .ok()
            .and_then(|a| a.clone())
            .filter(|a| a.is_alive() && a.snapshot()["runtimeId"] == runtime)
            .context("Selected assistant unavailable")?;
        let profile_path = self.state_dir.join("assistant/profile.json");
        let profile: Profile =
            serde_json::from_slice(&storage::read_physical(&profile_path, 16384)?)?;
        anyhow::ensure!(profile.schema == 1, "Invalid assistant profile");
        self.reject_coordinator_access(source, Some(thread)).await?;
        let (connection, process) = {
            let state = self.state.lock().await;
            let current = state
                .runtimes
                .get(source)
                .filter(|r| r.rpc.online())
                .context("Project source runtime offline")?;
            anyhow::ensure!(
                current.store == store,
                "Selected project config store changed"
            );
            self.require_assistant_project_owner(&state, source, thread, store)?;
            (
                current.connection_id.clone(),
                current
                    .native_process
                    .clone()
                    .context("Source native process identity unavailable")?,
            )
        };
        let proof = tokio::time::timeout(Duration::from_secs(10), async {
            let (workspace, _, generation, _) = self.workspace_proof(source, thread).await?;
            anyhow::ensure!(
                generation == connection,
                "Project source connection changed"
            );
            let cwd = workspace
                .roots()
                .first()
                .context("Native project cwd unavailable")?;
            self.read_assistant_project_permissions(source, thread, cwd)
                .await
        })
        .await
        .context("Project permission read timed out")??;
        if !self.device_exists(device).await {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        anyhow::ensure!(
            self.assistant.try_lock().ok().is_some_and(|a| a
                .as_ref()
                .is_some_and(|current| current.is_alive() && current.same_worker(&actor))),
            "Assistant changed during project read"
        );
        let current_profile: Profile =
            serde_json::from_slice(&storage::read_physical(&profile_path, 16384)?)?;
        anyhow::ensure!(
            profile == current_profile,
            "Assistant profile changed during project read"
        );
        let state = self.state.lock().await;
        let current = state
            .runtimes
            .get(source)
            .filter(|r| {
                r.rpc.online()
                    && r.connection_id == connection
                    && r.store == store
                    && r.native_process.as_ref() == Some(&process)
            })
            .context("Source changed during project read")?;
        self.require_assistant_project_owner(&state, source, thread, store)?;
        anyhow::ensure!(
            proof.generation == format!("{connection}:{}:{}", process.pid, process.created_at)
                && proof.settings
                    == crate::settings::canonical(&current.settings.read(thread)["settings"]),
            "Source permission changed during project read"
        );
        // This is an observation, not a DirectoryGrant. Send/schedule still
        // prove native authority again before creating or dispatching work.
        Ok(
            json!({"sourceRuntimeId":source,"sourceConfigStoreId":store,"threadId":thread,
            "verified":true,"writable":crate::assistant_product::project_writable(&proof.settings),
            "sourceGeneration":proof.generation,"permissionRevision":proof.revision}),
        )
    }
}
