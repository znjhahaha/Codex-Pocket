//! Authenticated product entry points; raw paths/Host RPC never come from the phone.
use super::*;
use crate::assistant_executor::{ProjectPermissionProof, ProjectPermissionReader};
use crate::{
    assistant_product::Actor,
    managed_runtime::{Controller, Installation, Registry, SelectedProfile},
};
use serde::{Deserialize, Serialize};
use std::{path::Path, sync::Weak};

struct BrokerPermissionReader(Weak<Broker>);
impl ProjectPermissionReader for BrokerPermissionReader {
    fn read(&self, runtime: &str, thread: &str, cwd: &Path) -> Result<ProjectPermissionProof> {
        let broker = self.0.upgrade().context("Source broker stopped")?;
        let handle = broker
            .assistant_permission_handle
            .get()
            .context("Source broker runtime unavailable")?;
        // Runs on the assistant's dedicated worker thread. Never enter writes:
        // the authenticated phone action may already hold that serializer.
        handle.block_on(async {
            tokio::time::timeout(
                Duration::from_secs(10),
                broker.read_assistant_project_permissions(runtime, thread, cwd),
            )
            .await
            .context("Source permission read timed out")?
        })
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Profile {
    schema: u32,
    config_home: PathBuf,
    native_binary: PathBuf,
    source_runtime_id: String,
}
impl Broker {
    #[cfg(all(test, windows))]
    pub(crate) async fn assistant_source_test_peer(
        &self,
        runtime: &str,
        source: &str,
    ) -> crate::native::test_peer::Peer {
        let (mut connected, mut peer) = crate::native::test_peer::connect(
            runtime,
            source,
            &self.state_dir,
            self.native_events.clone(),
        )
        .await;
        connected.native_process = Some(NativeProcess::capture(std::process::id()).unwrap());
        if source == "desktop" {
            let (registered, _) = tokio::join!(self.add_runtime(connected), async {
                let request = peer.recv().await;
                assert_eq!(request["method"], "pocket/approvals/list");
                peer.reply(&request, json!({"data":[]})).await;
            });
            registered.unwrap();
        } else {
            self.add_runtime(connected).await.unwrap();
        }
        peer
    }
    pub(super) fn register_assistant_permission_reader(self: &Arc<Self>) -> Result<()> {
        if let Ok(handle) = tokio::runtime::Handle::try_current() {
            let _ = self.assistant_permission_handle.set(handle);
        }
        let reader: Arc<dyn ProjectPermissionReader> =
            Arc::new(BrokerPermissionReader(Arc::downgrade(self)));
        crate::assistant_executor::register_project_permission_reader(
            &self.state_dir,
            reader.clone(),
        )?;
        self.assistant_permission_reader
            .set(reader)
            .map_err(|_| anyhow::anyhow!("Source reader already registered"))?;
        Ok(())
    }

    async fn read_assistant_project_permissions(
        &self,
        runtime: &str,
        thread: &str,
        cwd: &Path,
    ) -> Result<ProjectPermissionProof> {
        anyhow::ensure!(
            !runtime.is_empty()
                && runtime.len() <= 256
                && crate::settings::valid_thread(thread)
                && thread.len() <= 256,
            "Invalid source permission identity"
        );
        let expected = dunce::canonicalize(cwd)?;
        anyhow::ensure!(
            cwd.is_absolute() && expected.is_dir(),
            "Source directory unavailable"
        );
        let (rpc, generation, desktop, process, checkpoint, floor, mut caps) = {
            let mut state = self.state.lock().await;
            let r = state
                .runtimes
                .get_mut(runtime)
                .filter(|r| r.rpc.online())
                .context("Source runtime offline")?;
            anyhow::ensure!(!r.rpc.capability_failed(), "Source schema unavailable");
            (
                r.rpc.clone(),
                r.connection_id.clone(),
                r.source == "desktop",
                r.native_process
                    .clone()
                    .context("Source native process identity unavailable")?,
                r.settings.checkpoint(),
                r.assistant_permission_floor,
                r.capabilities.clone(),
            )
        };
        anyhow::ensure!(
            process.pid != 0
                && process.created_at != 0
                && NativeProcess::capture(process.pid)? == process,
            "Source native process changed"
        );
        if desktop {
            let status = rpc.call("pocket/status", json!({})).await?;
            anyhow::ensure!(
                serde_json::to_vec(&status)?.len() <= 512 * 1024
                    && status["runtimeId"] == runtime
                    && serde_json::from_value::<NativeProcess>(status["nativeProcess"].clone())?
                        == process,
                "Source bridge identity changed"
            );
            anyhow::ensure!(
                crate::capabilities::permission_discovery(&status).0 == "ready",
                "Source permission protocol unavailable"
            );
            caps = serde_json::from_value(status["nativeCapabilities"].clone())?;
        }
        anyhow::ensure!(
            caps.supports(
                "thread/read",
                &json!({"threadId":thread,"includeTurns":false})
            ),
            "Source thread read unsupported"
        );
        for pass in 0..2 {
            let result = rpc
                .call(
                    "thread/read",
                    json!({"threadId":thread,"includeTurns":false}),
                )
                .await?;
            anyhow::ensure!(
                serde_json::to_vec(&result)?.len() <= 4 * 1024 * 1024
                    && result["thread"]["id"] == thread,
                "Source thread identity changed"
            );
            let actual = dunce::canonicalize(Path::new(field(&result["thread"], "cwd")?))?;
            anyhow::ensure!(
                actual == expected && actual.is_dir(),
                "Source physical cwd changed"
            );
            if pass == 0 && desktop {
                let value = rpc
                    .call("pocket/threadSettings", json!({"threadId":thread}))
                    .await?;
                anyhow::ensure!(
                    serde_json::to_vec(&value)?.len() <= 64 * 1024
                        && value["threadId"] == thread
                        && value["known"] == true,
                    "Source effective permissions unknown"
                );
                // Validate this reply alone. A partial/unknown reply cannot reuse
                // the previous writable sandbox from the broker's UI cache.
                let mut fresh = crate::settings::SettingsCache::default();
                fresh.snapshot(thread, &value["settings"], 1);
                anyhow::ensure!(
                    fresh.permissions_proven_since(thread, 0),
                    "Source effective permissions incomplete"
                );
                let mut state = self.state.lock().await;
                let r = state
                    .runtimes
                    .get_mut(runtime)
                    .filter(|r| {
                        r.connection_id == generation
                            && r.rpc.online()
                            && r.native_process.as_ref() == Some(&process)
                    })
                    .ok_or(RpcFailure::Disconnected)?;
                r.settings.snapshot(thread, &value["settings"], checkpoint);
            }
        }
        anyhow::ensure!(
            dunce::canonicalize(cwd)? == expected
                && NativeProcess::capture(process.pid)? == process,
            "Source identity changed during permission read"
        );
        let state = self.state.lock().await;
        let r = state
            .runtimes
            .get(runtime)
            .filter(|r| {
                r.connection_id == generation
                    && r.rpc.online()
                    && r.native_process.as_ref() == Some(&process)
            })
            .ok_or(RpcFailure::Disconnected)?;
        anyhow::ensure!(
            r.settings.permissions_proven_since(
                thread,
                if desktop {
                    checkpoint.saturating_sub(1)
                } else {
                    floor
                }
            ),
            "Source has no effective permission observation on this connection"
        );
        let settings = crate::settings::canonical(&r.settings.read(thread)["settings"]);
        let bound_generation = format!("{generation}:{}:{}", process.pid, process.created_at);
        let revision = crate::auth::hash(
            &json!([runtime, bound_generation, thread, expected, settings]).to_string(),
        );
        Ok(ProjectPermissionProof {
            runtime_id: runtime.into(),
            generation: bound_generation,
            thread_id: thread.into(),
            cwd: expected,
            revision,
            settings,
        })
    }
    /// Watches durable service changes in memory. This never starts a model turn.
    pub async fn assistant_notifications(self: Arc<Self>) {
        let path = self.state_dir.join("assistant-notifications.json");
        let mut delivered = storage::read_physical(&path, 4096)
            .ok()
            .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok())
            .filter(|value| value["computerId"] == self.config.computer_id)
            .and_then(|value| value["sequence"].as_u64())
            .unwrap_or(0);
        let mut observed = String::new();
        let mut clock = tokio::time::interval(std::time::Duration::from_secs(1));
        clock.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            clock.tick().await;
            let Some(actor) = self.assistant.lock().await.clone() else {
                continue;
            };
            if self.handoff.get().is_some_and(|h| !h.accepting_writes()) {
                continue;
            }
            let value = actor.snapshot();
            let current = value["eventCursor"].as_u64().unwrap_or(0);
            let key = json!([
                value["assistantId"],
                current,
                value["ready"],
                value["paused"],
                value["error"]
            ])
            .to_string();
            if observed != key {
                observed = key;
                let _ = self
                    .notifications
                    .send(json!({"method":"assistant/changed","params":{"after":current}}));
            }
            if current <= delivered {
                continue;
            }
            let permit = match self.handoff.get() {
                Some(h) => match h.admit_write() {
                    Ok(p) => Some(p),
                    Err(_) => continue,
                },
                None => None,
            };
            // This guard covers local notification-ledger writes, including a
            // failed atomic save. It is not a model/approval delivery receipt.
            struct NotificationWrite(Option<crate::handoff::WritePermit>);
            impl Drop for NotificationWrite {
                fn drop(&mut self) {
                    if let Some(p) = self.0.take() {
                        let _ = p.confirm();
                    }
                }
            }
            let _write = NotificationWrite(permit);
            let Some(runtime) = value["runtimeId"].as_str() else {
                continue;
            };
            // Coalesce a batch, including offline missed reminders, to one wake-up.
            let event = value["events"]
                .as_array()
                .into_iter()
                .flatten()
                .rev()
                .find(|event| {
                    event["sequence"].as_u64().is_some_and(|s| s > delivered)
                        && event["notify"] == true
                });
            if let Some(event) = event.filter(|_| self.push.configured()) {
                let operation = &event["operation_id"];
                let thread = value["operations"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .find(|op| op["id"] == *operation)
                    .and_then(|op| op["mapping"]["thread_id"].as_str())
                    .or_else(|| value["coordinatorThreadId"].as_str());
                if let Some(thread) = thread {
                    let _delivery = self.push_delivery.lock().await;
                    let devices = self
                        .state
                        .lock()
                        .await
                        .devices
                        .devices
                        .keys()
                        .cloned()
                        .collect::<Vec<_>>();
                    // Push is a best-effort wake-up; service history is authoritative.
                    // Persist before sending to avoid duplicate notifications on recovery.
                    if storage::atomic_write(
                        &path,
                        &serde_json::to_vec(
                            &json!({"computerId":self.config.computer_id,"sequence":current}),
                        )
                        .unwrap(),
                    )
                    .is_err()
                    {
                        continue;
                    }
                    delivered = current;
                    let _ = self.push.emit(runtime, thread, &devices).await;
                    continue;
                }
            }
            if storage::atomic_write(
                &path,
                &serde_json::to_vec(
                    &json!({"computerId":self.config.computer_id,"sequence":current}),
                )
                .unwrap(),
            )
            .is_ok()
            {
                delivered = current;
            }
        }
    }
    /// A deliberate write to an assistant execution thread transfers control to
    /// the user before dispatch. Reads and history attachment never do so.
    pub(super) async fn assistant_before_manual_write(
        &self,
        runtime: &str,
        method: &str,
        p: &Value,
        command: &str,
    ) -> Result<()> {
        if matches!(method, "thread/resume" | "thread/name/set") {
            return Ok(());
        }
        let managed = {
            let state = self.state.lock().await;
            state
                .runtimes
                .get(runtime)
                .is_some_and(|r| matches!(r.role, crate::native::NativeRole::ManagedHost { .. }))
        };
        let actor = self.assistant.lock().await.clone();
        let snapshot = actor.as_ref().map(Actor::snapshot).unwrap_or(Value::Null);
        match manual_write_policy(managed, &snapshot, runtime, method, p) {
            ManualWrite::Ordinary => Ok(()),
            ManualWrite::Reject => Err(classify(
                anyhow::anyhow!(
                    "Use the Assistant page to manage its coordinator and create delegated tasks"
                ),
                Code::Rejected,
            )),
            ManualWrite::Takeover => {
                actor.context("Assistant unavailable; cannot safely transfer task control")?
                    .request("assistant/takeover",json!({}),format!("manual:{command}"),None).await
                    .map_err(|_|classify(anyhow::anyhow!("Assistant takeover is not confirmed; retry after checking its state"),Code::Rejected))?;
                Ok(())
            }
        }
    }
    pub async fn restore_assistant(&self) -> Result<()> {
        let result = async {
            let path = self.state_dir.join("assistant/profile.json");
            if !path.try_exists()? {
                return Ok(());
            }
            let profile: Profile = serde_json::from_slice(&storage::read_physical(&path, 16384)?)?;
            anyhow::ensure!(profile.schema == 1, "Unknown assistant profile schema");
            self.ensure_assistant(profile).await.map(|_| ())
        }
        .await;
        *self.assistant_error.lock().await = if result.is_err() {
            Some("assistant_restore_failed")
        } else {
            None
        };
        result
    }

    async fn ensure_assistant(&self, profile: Profile) -> Result<Actor> {
        self.assistant_permission_handle
            .get_or_init(tokio::runtime::Handle::current);
        let mut current = self.assistant.lock().await;
        if let Some(actor) = current.as_ref().filter(|actor| actor.is_alive()) {
            return Ok(actor.clone());
        }
        *current = None;
        let executable = std::env::current_exe()?;
        let bin = executable.parent().context("Missing installation")?;
        let generation = crate::installation::current()["generation"]
            .as_str()
            .context("Installed generation unavailable")?
            .to_string();
        let installation = Installation::new(bin, generation)?;
        let registry = Registry::new(self.state_dir.join("managed-runtimes"))?;
        let selected = SelectedProfile::new(&profile.config_home, &profile.native_binary)?;
        let descriptor = registry
            .profile_directory(&selected)?
            .join("descriptor.json");
        let runtime = Arc::new(
            Controller::new(installation, registry)
                .ensure(&selected)
                .await?,
        );
        let cursor = self
            .handoff
            .get()
            .and_then(|h| h.recovery_snapshot())
            .and_then(|s| {
                s.runtimes
                    .get(runtime.runtime_id())
                    .and_then(|r| r.host_cursor.as_ref())
                    .map(|c| crate::native::host::Cursor {
                        generation: c.generation.clone(),
                        after: c.after,
                    })
            });
        let connected = runtime.attach(self.native_events.clone(), cursor).await?;
        let capabilities = connected.capabilities.clone();
        if let Some(h) = self.handoff.get() {
            h.register_host_descriptor(runtime.runtime_id(), &descriptor)?;
        }
        self.add_runtime(connected).await?;
        storage::private_directory(&self.state_dir.join("assistant"))?;
        storage::atomic_write(
            &self.state_dir.join("assistant/profile.json"),
            &serde_json::to_vec(&profile)?,
        )?;
        let actor = Actor::start(
            self.state_dir.join("assistant"),
            self.config.computer_id.clone(),
            storage::config_store(&profile.config_home)?,
            runtime,
            capabilities,
            self.handoff.get().cloned(),
        )
        .await?;
        *current = Some(actor.clone());
        *self.assistant_error.lock().await = None;
        Ok(actor)
    }
    pub(super) async fn assistant_read(&self, p: &Value) -> Result<Value> {
        if p.as_object().is_none_or(|o| o.keys().any(|k| k != "after"))
            || p.get("after").is_some_and(|v| v.as_u64().is_none())
        {
            return Err(classify(
                anyhow::anyhow!("Invalid assistant read"),
                Code::InvalidParams,
            ));
        }
        let actor = self.assistant.lock().await.clone();
        let mut result = if let Some(actor) = actor {
            actor
                .request(
                    "assistant/read",
                    p.clone(),
                    format!("read:{}", uuid::Uuid::new_v4()),
                    None,
                )
                .await
                .unwrap_or_else(|_| actor.snapshot())
        } else {
            json!({"enabled":false,"ready":false,"runtimeId":null,"tasks":[],"operations":[],"events":[],"memories":[],"schedules":[],"messages":[]})
        };
        if let Some(error) = *self.assistant_error.lock().await {
            result["error"] = json!(error);
            result["recoveryAction"] = json!("select_runtime_and_enable");
        }
        Ok(result)
    }
    pub(super) fn validate_assistant(method: &str, p: &Value) -> Result<()> {
        let fields: &[&str] = match method {
            "assistant/enable" | "assistant/pause" | "assistant/resume" => &[],
            "assistant/send" => &["message", "threadId"],
            "assistant/task/pause" | "assistant/task/resume" => &["taskId"],
            "assistant/settings/update" => &["settings"],
            "assistant/memory/add" => &["text"],
            "assistant/memory/update" => &["text", "memoryId"],
            "assistant/memory/delete" => &["memoryId"],
            "assistant/schedule/create" => &["threadId", "spec"],
            "assistant/schedule/delete" => &["scheduleId"],
            "assistant/respond" => &["operationId", "requestId", "response"],
            _ => {
                return Err(classify(
                    anyhow::anyhow!("Unsupported assistant action"),
                    Code::Unsupported,
                ));
            }
        };
        if p.as_object().is_none_or(|o| {
            o.keys()
                .any(|k| !fields.contains(&k.as_str()) && k != "runtimeId" && k != "commandId")
        }) || p["runtimeId"]
            .as_str()
            .is_none_or(|s| s.is_empty() || s.len() > 256)
            || p.to_string().len() > 131072
        {
            return Err(classify(
                anyhow::anyhow!("Invalid assistant action"),
                Code::InvalidParams,
            ));
        }
        Ok(())
    }
    pub(super) async fn assistant_write(
        &self,
        device: &str,
        method: &str,
        p: &Value,
    ) -> Result<Value> {
        Self::validate_assistant(method, p)?;
        let runtime_id = field(p, "runtimeId")?;
        if method == "assistant/enable" {
            let (home, native) = {
                let state = self.state.lock().await;
                let runtime = state
                    .runtimes
                    .get(runtime_id)
                    .filter(|r| r.rpc.online())
                    .context("Selected runtime offline")?;
                (
                    runtime.config_home.clone(),
                    runtime
                        .native_process
                        .clone()
                        .context("Native process identity missing")?,
                )
            };
            anyhow::ensure!(
                pocket_core::native_process::NativeProcess::capture(native.pid)? == native,
                "Native process changed"
            );
            let binary = crate::native::native_binary(native.pid)?;
            anyhow::ensure!(
                pocket_core::native_process::NativeProcess::capture(native.pid)? == native,
                "Native process changed"
            );
            let profile_path = self.state_dir.join("assistant/profile.json");
            if profile_path.try_exists()? {
                let saved: Profile =
                    serde_json::from_slice(&storage::read_physical(&profile_path, 16384)?)?;
                anyhow::ensure!(
                    storage::config_store(&saved.config_home)? == storage::config_store(&home)?,
                    "Pause and finish the current assistant before changing profiles"
                );
            }
            let actor = self
                .ensure_assistant(Profile {
                    schema: 1,
                    config_home: home,
                    native_binary: binary,
                    source_runtime_id: runtime_id.into(),
                })
                .await
                .map_err(|e| classify(e, Code::RuntimeUnavailable))?;
            return Ok(actor.snapshot());
        }
        let actor = self
            .assistant
            .lock()
            .await
            .clone()
            .context("Enable the assistant first")?;
        let chosen = actor.snapshot()["runtimeId"]
            .as_str()
            .context("Assistant runtime unavailable")?
            .to_string();
        let profile: Profile = serde_json::from_slice(&storage::read_physical(
            &self.state_dir.join("assistant/profile.json"),
            16384,
        )?)?;
        if runtime_id != chosen && runtime_id != profile.source_runtime_id {
            return Err(classify(
                anyhow::anyhow!("Wrong assistant profile"),
                Code::InvalidParams,
            ));
        }
        let directory = if let Some(thread) = p.get("threadId") {
            let thread = thread
                .as_str()
                .filter(|s| crate::settings::valid_thread(s))
                .context("Invalid project thread")?;
            let (workspace, _, _, _) = self.workspace_proof(runtime_id, thread).await?;
            workspace.roots().first().cloned()
        } else {
            None
        };
        if !self.device_exists(device).await {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        actor
            .request(method, p.clone(), field(p, "commandId")?.into(), directory)
            .await
            .map_err(|e| classify(e, Code::Unknown))
    }
}

#[derive(Debug, PartialEq, Eq)]
enum ManualWrite {
    Ordinary,
    Takeover,
    Reject,
}
fn manual_write_policy(
    managed: bool,
    snapshot: &Value,
    runtime: &str,
    method: &str,
    p: &Value,
) -> ManualWrite {
    if !managed {
        return ManualWrite::Ordinary;
    }
    if snapshot["runtimeId"] != runtime || snapshot["enabled"] != true || method == "thread/start" {
        return ManualWrite::Reject;
    }
    let Some(thread) = p["threadId"].as_str() else {
        return ManualWrite::Reject;
    };
    if snapshot["coordinatorThreadId"] == thread {
        return ManualWrite::Reject;
    }
    if snapshot["operations"]
        .as_array()
        .into_iter()
        .flatten()
        .any(|o| o["role"] == "Execution" && o["mapping"]["thread_id"] == thread)
    {
        ManualWrite::Takeover
    } else {
        ManualWrite::Reject
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn review_assistant_permission_reader_registered_on_broker_open() {
        let dir = tempfile::tempdir().unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (broker, _events) = Broker::open(config, dir.path().into()).unwrap();
        assert!(
            crate::assistant_executor::project_permission_reader(dir.path())
                .unwrap()
                .is_some(),
            "Broker open must register the production reader before restore or enable"
        );
        drop(broker);
        assert!(
            crate::assistant_executor::project_permission_reader(dir.path())
                .unwrap()
                .is_none(),
            "Reader registration must not retain a stopped broker"
        );
    }
    #[cfg(windows)]
    async fn source_read(
        broker: &Broker,
        root: &Path,
    ) -> tokio::task::JoinHandle<Result<ProjectPermissionProof>> {
        let reader = crate::assistant_executor::project_permission_reader(&broker.state_dir)
            .unwrap()
            .unwrap();
        let root = root.to_owned();
        tokio::task::spawn_blocking(move || reader.read("source", "t", &root))
    }
    #[cfg(windows)]
    async fn source_status(broker: &Broker) -> Value {
        let state = broker.state.lock().await;
        let r = &state.runtimes["source"];
        json!({"runtimeId":"source","nativeProcess":r.native_process,"initialized":true,"schemaState":"ready","permissionProtocol":1,"nativeCapabilities":r.capabilities})
    }
    #[cfg(windows)]
    async fn reply_source(
        peer: &mut crate::native::test_peer::Peer,
        status: Value,
        root: &Path,
        policy: &str,
    ) {
        for method in [
            "pocket/status",
            "thread/read",
            "pocket/threadSettings",
            "thread/read",
        ] {
            let request = peer.recv().await;
            assert_eq!(request["method"], method);
            assert!(!method.contains("resume"));
            let result = match method {
                "pocket/status" => status.clone(),
                "thread/read" => json!({"thread":{"id":"t","cwd":root,"status":{"type":"idle"}}}),
                _ => {
                    json!({"threadId":"t","known":true,"settings":{"sandboxPolicy":{"type":policy},"approvalPolicy":"on-request","approvalsReviewer":"user","activePermissionProfile":null}})
                }
            };
            peer.reply(&request, result).await;
        }
    }
    #[cfg(windows)]
    #[tokio::test]
    async fn review_assistant_broker_reader_uses_fresh_authenticated_source_settings_and_revocation()
     {
        let (dir, b) = super::super::tests::fixture().await;
        let mut peer = b.assistant_source_test_peer("source", "desktop").await;
        let status = source_status(&b).await;
        let task = source_read(&b, dir.path()).await;
        reply_source(&mut peer, status.clone(), dir.path(), "workspaceWrite").await;
        let writable = task.await.unwrap().unwrap();
        assert_eq!(writable.settings["sandboxPolicy"]["type"], "workspaceWrite");
        assert_eq!(writable.cwd, dunce::canonicalize(dir.path()).unwrap());
        assert!(
            writable
                .generation
                .contains(&b.state.lock().await.runtimes["source"].connection_id)
        );
        let task = source_read(&b, dir.path()).await;
        reply_source(&mut peer, status, dir.path(), "readOnly").await;
        let revoked = task.await.unwrap().unwrap();
        assert_ne!(writable.revision, revoked.revision);
        assert_eq!(revoked.settings["sandboxPolicy"]["type"], "readOnly");
        peer.assert_idle().await;
    }
    #[cfg(windows)]
    #[tokio::test]
    async fn review_assistant_broker_reader_rejects_unknown_and_changed_source_bindings() {
        for failure in [
            "process",
            "runtime",
            "thread",
            "cwd",
            "unknown",
            "partial",
            "settings-thread",
            "generation",
        ] {
            let (dir, b) = super::super::tests::fixture().await;
            let other = tempfile::tempdir().unwrap();
            let mut peer = b.assistant_source_test_peer("source", "desktop").await;
            let mut status = source_status(&b).await;
            if failure == "process" {
                status["nativeProcess"]["createdAt"] = json!(1);
            }
            if failure == "runtime" {
                status["runtimeId"] = json!("other");
            }
            let task = source_read(&b, dir.path()).await;
            let request = peer.recv().await;
            assert_eq!(request["method"], "pocket/status");
            peer.reply(&request, status).await;
            if !matches!(failure, "process" | "runtime") {
                let request = peer.recv().await;
                assert_eq!(request["method"], "thread/read");
                peer.reply(&request, json!({"thread":{"id":if failure=="thread"{"other"}else{"t"},"cwd":if failure=="cwd"{other.path()}else{dir.path()}}})).await;
                if !matches!(failure, "thread" | "cwd") {
                    let request = peer.recv().await;
                    assert_eq!(request["method"], "pocket/threadSettings");
                    peer.reply(&request, json!({"threadId":if failure=="settings-thread"{"other"}else{"t"},"known":failure!="unknown",
                        "settings":if failure=="partial"{json!({"model":"m"})}else{json!({"sandboxPolicy":{"type":"workspaceWrite"},"approvalPolicy":"on-request","approvalsReviewer":"user"})}})).await;
                    if failure == "generation" {
                        let request = peer.recv().await;
                        assert_eq!(request["method"], "thread/read");
                        b.state
                            .lock()
                            .await
                            .runtimes
                            .get_mut("source")
                            .unwrap()
                            .connection_id = "replacement".into();
                        peer.reply(&request, json!({"thread":{"id":"t","cwd":dir.path()}}))
                            .await;
                    }
                }
            }
            assert!(
                task.await.unwrap().is_err(),
                "{failure} must not return writable proof"
            );
            peer.assert_idle().await;
        }
    }
    #[cfg(windows)]
    #[tokio::test]
    async fn review_assistant_cli_reader_requires_native_permissions_on_current_connection() {
        let (dir, b) = super::super::tests::fixture().await;
        let mut peer = b.assistant_source_test_peer("source", "cli").await;
        let task = source_read(&b, dir.path()).await;
        for _ in 0..2 {
            let read = peer.recv().await;
            assert_eq!(read["method"], "thread/read");
            peer.reply(&read, json!({"thread":{"id":"t","cwd":dir.path()}}))
                .await;
        }
        assert!(task.await.unwrap().is_err());
        // Actual native thread/resume response, observed by the existing broker
        // settings projection. No policy is added to Thread metadata.
        {
            let mut state = b.state.lock().await;
            let r = state.runtimes.get_mut("source").unwrap();
            let observed = r
                .settings
                .prepare("thread/resume", &json!({"threadId":"t"}))
                .unwrap();
            r.settings.accepted(observed, &json!({"thread":{"id":"t","cwd":dir.path()},"sandbox":{"type":"workspaceWrite"},"approvalPolicy":"on-request","approvalsReviewer":"user"}));
        }
        let task = source_read(&b, dir.path()).await;
        for _ in 0..2 {
            let read = peer.recv().await;
            peer.reply(&read, json!({"thread":{"id":"t","cwd":dir.path()}}))
                .await;
        }
        assert_eq!(
            task.await.unwrap().unwrap().settings["sandboxPolicy"]["type"],
            "workspaceWrite"
        );
        peer.assert_idle().await;
    }
    #[test]
    fn coordinator_is_not_an_arbitrary_native_write_entrypoint() {
        let snapshot = json!({"enabled":true,"runtimeId":"host","coordinatorThreadId":"coord","operations":[{"role":"Execution","mapping":{"thread_id":"task"}}]});
        assert_eq!(
            manual_write_policy(
                true,
                &snapshot,
                "host",
                "turn/start",
                &json!({"threadId":"coord"})
            ),
            ManualWrite::Reject
        );
        assert_eq!(
            manual_write_policy(true, &snapshot, "host", "thread/start", &json!({})),
            ManualWrite::Reject
        );
        assert_eq!(
            manual_write_policy(
                true,
                &snapshot,
                "host",
                "turn/start",
                &json!({"threadId":"task"})
            ),
            ManualWrite::Takeover
        );
        assert_eq!(
            manual_write_policy(
                true,
                &snapshot,
                "host",
                "turn/start",
                &json!({"threadId":"unknown"})
            ),
            ManualWrite::Reject
        );
        assert_eq!(
            manual_write_policy(
                true,
                &snapshot,
                "replacement",
                "turn/start",
                &json!({"threadId":"task"})
            ),
            ManualWrite::Reject
        );
        assert_eq!(
            manual_write_policy(
                false,
                &snapshot,
                "ordinary",
                "turn/start",
                &json!({"threadId":"ordinary-thread"})
            ),
            ManualWrite::Ordinary
        );
    }
}
