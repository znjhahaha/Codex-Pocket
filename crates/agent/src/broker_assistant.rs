//! Authenticated product entry points; raw paths/Host RPC never come from the phone.
use super::*;
use crate::assistant_executor::{ProjectPermissionProof, ProjectPermissionReader};
use crate::{
    assistant_product::Actor,
    managed_runtime::{Controller, Installation, Registry, SelectedProfile},
};
use crate::managed_runtime::discovery::RestoreFailure;
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

#[derive(Clone, Serialize, Deserialize,PartialEq,Eq)]
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
        self.restore_assistant_using(crate::managed_runtime::discovery::restore_saved_profile,async |profile|self.ensure_assistant(profile).await).await
    }
    async fn restore_assistant_using(&self,resolve:impl FnOnce(&Path,&Path)->Result<SelectedProfile>,ensure:impl AsyncFnOnce(Profile)->Result<Actor>)->Result<()> {
        let _write=self.writes.lock().await;
        let result = async {
            let profile=if self.fenced_assistant_bootstrap() {
                self.saved_assistant_profile(validate_fenced_saved_profile)?
            }else{
                self.check_write_fence()?;
                self.saved_assistant_profile(resolve)?
            };
            let Some(profile)=profile else {
                return Ok(());
            };
            ensure(profile).await.map(|_| ()).map_err(runtime_restore_error)
        }
        .await;
        *self.assistant_error.lock().await=result.as_ref().err().map(|e|assistant_preparation_error(e,true));
        result
    }

    fn fenced_assistant_bootstrap(&self)->bool {
        self.handoff.get().is_some_and(|h|!h.accepting_writes()&&h.expected_checkpoint().is_some())
    }

    fn saved_assistant_profile(&self,resolve:impl FnOnce(&Path,&Path)->Result<SelectedProfile>)->Result<Option<Profile>> {
        let path=self.state_dir.join("assistant/profile.json");
        if !path.try_exists().context(RestoreFailure::ProfileRead)?{return Ok(None)}
        let bytes=storage::read_physical(&path,16384).context(RestoreFailure::ProfileRead)?;
        let mut saved:Profile=serde_json::from_slice(&bytes).context(RestoreFailure::ProfileInvalid)?;
        anyhow::ensure!(saved.schema==1,RestoreFailure::ProfileInvalid);
        let missing=matches!(std::fs::symlink_metadata(&saved.native_binary),Err(e) if e.kind()==std::io::ErrorKind::NotFound);
        let selected=resolve(&saved.config_home,&saved.native_binary)?;
        // Preserve the saved config-home spelling and source/account authority.
        // No disk mutation here: ensure.lock must confirm old ownership ended,
        // and the Host must attach successfully before persisting the new path.
        if missing {saved.native_binary=selected.native_binary().into();}
        Ok(Some(saved))
    }

    /// Local startup preparation shares the phone write fence/serializer. First
    /// discovery is deliberately forbidden until a fenced successor is active.
    pub async fn prepare_assistant(&self) -> Result<()> {
        let _write = self.writes.lock().await;
        self.prepare_assistant_inner().await
    }

    /// Retry only transient startup failures. Registry ownership checks still
    /// prevent replacing a live or unconfirmed Host/native process.
    pub async fn prepare_assistant_at_startup(&self) {
        for attempt in 0..3 {
            if self.prepare_assistant().await.is_ok() {
                return;
            }
            if *self.assistant_error.lock().await != Some("assistant_runtime_start_failed") {
                return;
            }
            if attempt < 2 {
                tokio::time::sleep(Duration::from_secs(attempt + 1)).await;
            }
        }
    }

    /// Current-user diagnostics reveal readiness, never chat/account contents.
    pub async fn assistant_status(&self) -> Value {
        let snapshot = self.assistant_read(&json!({})).await;
        match snapshot {
            Ok(value) => json!({
                "ready":value["ready"], "runtimeReady":value["runtimeReady"],
                "preparationStatus":value["preparationStatus"], "error":value["error"],
                "reconciliationRequired":value["reconciliationRequired"],
                "paused":value["paused"], "runtimeId":value["runtimeId"]
            }),
            Err(_) => json!({"ready":false,"error":"assistant_status_unavailable"}),
        }
    }

    fn preparation_profile(
        &self,
        discover: impl FnOnce() -> Result<SelectedProfile>,
    ) -> Result<Profile> {
        self.check_write_fence()?;
        if let Some(saved)=self.saved_assistant_profile(crate::managed_runtime::discovery::restore_saved_profile)? {
            return Ok(saved);
        }
        let selected = discover()?;
        Ok(Profile {
            schema: 1,
            config_home: selected.config_home().into(),
            native_binary: selected.native_binary().into(),
            source_runtime_id: String::new(),
        })
    }

    async fn prepare_assistant_inner(&self) -> Result<()> {
        self.prepare_assistant_using(
            crate::managed_runtime::discovery::from_environment,
            async |profile| self.ensure_assistant(profile).await,
        )
        .await
    }

    async fn prepare_assistant_using(
        &self,
        discover: impl FnOnce() -> Result<SelectedProfile>,
        ensure: impl AsyncFnOnce(Profile) -> Result<Actor>,
    ) -> Result<()> {
        self.check_write_fence()?;
        *self.assistant_error.lock().await = Some("assistant_preparing");
        let result = async {
            let profile = self.preparation_profile(discover)?;
            ensure(profile).await.map(|_| ()).map_err(runtime_restore_error)
        }
        .await;
        let error = result.as_ref().err().map(|error| {
            assistant_preparation_error(error,self.state_dir.join("assistant/profile.json").exists())
        });
        *self.assistant_error.lock().await = error;
        result
    }

    async fn ensure_assistant(&self, profile: Profile) -> Result<Actor> {
        if let Some(actor) = self
            .assistant
            .lock()
            .await
            .as_ref()
            .filter(|a| a.is_alive())
        {
            return Ok(actor.clone());
        }
        let executable = std::env::current_exe()?;
        let bin = executable.parent().context("Missing installation")?;
        let generation = crate::installation::current()["generation"]
            .as_str()
            .context("Installed generation unavailable")?
            .to_string();
        let installation = Installation::new(bin, generation)?;
        let registry = if self.fenced_assistant_bootstrap(){Registry::existing(self.state_dir.join("managed-runtimes"))?}else{Registry::new(self.state_dir.join("managed-runtimes"))?};
        let controller = Controller::new(installation, registry.clone());
        self.ensure_assistant_with(profile, &controller, &registry)
            .await
    }

    async fn ensure_assistant_with(
        &self,
        profile: Profile,
        controller: &Controller,
        registry: &Registry,
    ) -> Result<Actor> {
        let readonly=self.fenced_assistant_bootstrap();
        if readonly {
            let saved=self.saved_assistant_profile(validate_fenced_saved_profile)?;
            anyhow::ensure!(saved.as_ref()==Some(&profile),RestoreFailure::ProfileMigrationFenced);
        }else{self.check_write_fence()?;}
        self.assistant_permission_handle
            .get_or_init(tokio::runtime::Handle::current);
        let mut current = self.assistant.lock().await;
        if let Some(actor) = current.as_ref().filter(|actor| actor.is_alive()) {
            return Ok(actor.clone());
        }
        *current = None;
        let selected = SelectedProfile::new(&profile.config_home, &profile.native_binary)?;
        let descriptor = registry
            .profile_directory(&selected)?
            .join("descriptor.json");
        let runtime = Arc::new(if readonly {controller.attach_existing(&selected).await?}else{controller.ensure(&selected).await?});
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
        if !readonly {
            self.check_write_fence()?;
            storage::private_directory(&self.state_dir.join("assistant"))?;
            storage::atomic_write(
                &self.state_dir.join("assistant/profile.json"),
                &serde_json::to_vec(&profile)?,
            ).context(RestoreFailure::ProfileSave)?;
        }
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
        if p.as_object().is_none_or(|o| {
            o.keys()
                .any(|k| !["after", "before", "limit", "taskId"].contains(&k.as_str()))
        }) || ["after", "before"]
            .iter()
            .any(|k| p.get(k).is_some_and(|v| v.as_u64().is_none()))
            || p.get("limit")
                .is_some_and(|v| v.as_u64().is_none_or(|n| !(1..=100).contains(&n)))
            || p.get("taskId").is_some_and(|v| !valid_scope_id(v))
        {
            return Err(classify(
                anyhow::anyhow!("Invalid assistant read"),
                Code::InvalidParams,
            ));
        }
        // ensure holds this lock while launching. Reads must still expose the
        // preparing state, without queueing behind native startup/handshake.
        let actor = self.assistant.try_lock().ok().and_then(|a| a.clone());
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
        let mut error = *self.assistant_error.lock().await;
        if error.is_none() && result["runtimeId"].as_str().is_some() {
            error = self.assistant_account_status(&result).await;
            if error.is_none() && result["ready"] != true {
                error = Some("assistant_runtime_unavailable");
            }
        }
        if let Some(error) = error.filter(|e| *e != "assistant_preparing") {
            if result["error"].is_null() {
                result["error"] = json!(error);
            }
            result["recoveryAction"] = json!(preparation_hint(error));
        }
        let status = match error {
            Some("assistant_preparing") => "preparing",
            Some("assistant_missing_cli") => "missing_cli",
            Some("assistant_missing_config") => "missing_config",
            Some("assistant_needs_login") => "needs_login",
            Some(_) => "recoverable_error",
            None if result["ready"] == true => "ready",
            None if result["runtimeId"].is_null() => "preparing",
            None => "recoverable_error",
        };
        // The worker snapshot's ready/fresh remain authoritative. This describes
        // setup and auth evidence, and never infers login from a config file.
        result["preparation"] = json!({"status":status,"recoveryAction":error.map(preparation_hint),"retryable":status != "ready"});
        result["preparationStatus"] = json!(status);
        Ok(result)
    }

    async fn assistant_account_status(&self, snapshot: &Value) -> Option<&'static str> {
        let runtime = snapshot["runtimeId"].as_str()?;
        let rpc = {
            let state = self.state.lock().await;
            let Some(runtime) = state.runtimes.get(runtime).filter(|r| {
                r.rpc.online() && matches!(r.role, crate::native::NativeRole::ManagedHost { .. })
            }) else {
                return Some("assistant_runtime_unavailable");
            };
            runtime.rpc.clone()
        };
        // The capability list is the phone allowlist, which intentionally omits
        // account/read. This fixed, local read probes the authenticated Host;
        // it neither accepts phone RPC/paths nor returns any account details.
        let params = json!({"refreshToken":false});
        match tokio::time::timeout(Duration::from_secs(5), rpc.call("account/read", params)).await {
            Ok(Ok(value)) => account_status(&value),
            _ => Some("assistant_account_unavailable"),
        }
    }
    pub(super) fn validate_assistant(method: &str, p: &Value) -> Result<()> {
        let fields: &[&str] = match method {
            "assistant/prepare" => &[],
            "assistant/enable" | "assistant/pause" | "assistant/resume" => &[],
            "assistant/send" => &["message", "threadId", "memberId", "taskId"],
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
        }) || (method == "assistant/prepare" && p.get("runtimeId").is_some())
            || (method != "assistant/prepare"
                && p["runtimeId"]
                    .as_str()
                    .is_none_or(|s| s.is_empty() || s.len() > 256))
            || (method == "assistant/send"
                && ["memberId", "taskId"].iter().any(|key| {
                    p.get(key).is_some_and(|v| {
                        if *key == "memberId" {
                            !valid_assistant_id(v)
                        } else {
                            !valid_scope_id(v)
                        }
                    })
                }))
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
        if method == "assistant/prepare" {
            self.prepare_assistant_inner().await?;
            return self.assistant_read(&json!({})).await;
        }
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
        // Parent integration point: target-send/group validation belongs here,
        // before project proof and actor dispatch; prepare has no target scope.
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

fn valid_scope_id(value: &Value) -> bool {
    value.as_str().is_some_and(|id| {
        !id.is_empty()
            && id.len() <= 256
            && id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
    })
}

fn valid_assistant_id(value: &Value) -> bool {
    value.as_str().is_some_and(|id| {
        !id.is_empty() && id.len() <= 256 && id.trim() == id && !id.chars().any(char::is_control)
    })
}

fn account_status(value: &Value) -> Option<&'static str> {
    match value["requiresOpenaiAuth"].as_bool() {
        Some(false) => None,
        Some(true) if value.get("account") == Some(&Value::Null) => Some("assistant_needs_login"),
        Some(true)
            if matches!(
                value["account"]["type"].as_str(),
                Some("apiKey" | "chatgpt")
            ) =>
        {
            None
        }
        _ => Some("assistant_account_unavailable"),
    }
}

fn preparation_hint(error: &str) -> &'static str {
    match error {
        "assistant_preparing" => "wait_for_preparation",
        "assistant_missing_cli" => "install_or_locate_native_codex_cli",
        "assistant_missing_config" => "create_or_repair_codex_home",
        "assistant_needs_login" => "login_with_codex_cli",
        "assistant_restore_failed" => "repair_saved_profile",
        "assistant_saved_profile_read_failed"=>"check_saved_profile_access",
        "assistant_saved_profile_invalid"=>"repair_saved_profile",
        "assistant_config_home_invalid"=>"repair_saved_codex_home",
        "assistant_native_binary_missing"=>"locate_saved_native_codex_cli",
        "assistant_native_binary_invalid"=>"repair_native_codex_package",
        "assistant_msix_query_failed"=>"check_windows_package_registration",
        "assistant_msix_package_missing"=>"install_or_update_codex_desktop",
        "assistant_msix_package_ambiguous"|"assistant_msix_identity_mismatch"=>"verify_codex_msix_package_identity",
        "assistant_owner_unconfirmed"=>"inspect_runtime_ownership_before_retry",
        "assistant_profile_save_failed"=>"check_saved_profile_write_access",
        "assistant_runtime_start_failed"=>"inspect_runtime_host_startup",
        "assistant_profile_migration_fenced"=>"resolve_handoff_before_profile_migration",
        "assistant_account_unavailable" => "check_cli_account_status",
        "assistant_runtime_unavailable" => "retry_preparation",
        _ => "inspect_runtime_host_and_retry",
    }
}

fn validate_fenced_saved_profile(home:&Path,binary:&Path)->Result<SelectedProfile> {
    if matches!(std::fs::symlink_metadata(binary),Err(e) if e.kind()==std::io::ErrorKind::NotFound) {
        return Err(RestoreFailure::ProfileMigrationFenced.into());
    }
    SelectedProfile::new(home,binary).context(RestoreFailure::NativeInvalid)
}

fn runtime_restore_error(error:anyhow::Error)->anyhow::Error {
    if error.downcast_ref::<RestoreFailure>().is_some(){error}else{error.context(RestoreFailure::RuntimeStart)}
}
fn assistant_preparation_error(error:&anyhow::Error,saved:bool)->&'static str {
    use crate::managed_runtime::discovery::Unavailable;
    if let Some(failure)=error.downcast_ref::<RestoreFailure>() {
        return match failure {
            RestoreFailure::ProfileRead=>"assistant_saved_profile_read_failed",
            RestoreFailure::ProfileInvalid=>"assistant_saved_profile_invalid",
            RestoreFailure::ConfigHome=>"assistant_config_home_invalid",
            RestoreFailure::NativeMissing=>"assistant_native_binary_missing",
            RestoreFailure::NativeInvalid=>"assistant_native_binary_invalid",
            RestoreFailure::PackageQuery=>"assistant_msix_query_failed",
            RestoreFailure::PackageMissing=>"assistant_msix_package_missing",
            RestoreFailure::PackageAmbiguous=>"assistant_msix_package_ambiguous",
            RestoreFailure::PackageIdentity=>"assistant_msix_identity_mismatch",
            RestoreFailure::OwnershipUnconfirmed=>"assistant_owner_unconfirmed",
            RestoreFailure::ProfileSave=>"assistant_profile_save_failed",
            RestoreFailure::RuntimeStart=>"assistant_runtime_start_failed",
            RestoreFailure::ProfileMigrationFenced=>"assistant_profile_migration_fenced",
        };
    }
    match error.downcast_ref::<Unavailable>() {
        Some(Unavailable::MissingCli)=>"assistant_missing_cli",
        Some(Unavailable::MissingConfig)=>"assistant_missing_config",
        None if saved=>"assistant_restore_failed",
        None=>"assistant_prepare_failed",
    }
}

#[cfg(all(test, windows))]
#[allow(dead_code)] // The shared fixture also serves the full Controller suite.
#[path = "managed_runtime/tests/fixture.rs"]
mod preparation_fixture;

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
    fn silent_prepare_accepts_no_selected_runtime_and_rejects_phone_paths() {
        assert!(
            Broker::validate_assistant("assistant/prepare", &json!({"commandId":"prepare"}))
                .is_ok()
        );
        for params in [
            json!({"commandId":"prepare","configHome":"C:/other"}),
            json!({"commandId":"prepare","nativeBinary":"C:/codex.exe"}),
            json!({"commandId":"prepare","runtimeId":"selected"}),
        ] {
            assert!(Broker::validate_assistant("assistant/prepare", &params).is_err());
        }
        assert!(
            Broker::validate_assistant("assistant/enable", &json!({"commandId":"enable"})).is_err()
        );
        assert!(
            Broker::validate_assistant(
                "assistant/enable",
                &json!({"commandId":"enable","runtimeId":"selected"})
            )
            .is_ok()
        );
    }

    #[tokio::test]
    async fn fenced_startup_absent_profile_restores_without_discovery_and_preserves_checkpoint() {
        use crate::handoff::{Handoff,Params,CheckpointStore};
        let dir=tempfile::tempdir().unwrap();
        let config=Config::create("wss://example.org/pocket".into()).unwrap();
        let (old,_)=Broker::open(config.clone(),dir.path().into()).unwrap();
        let generation=uuid::Uuid::new_v4();let handoff=Handoff::open(dir.path(),generation,true,false).unwrap();
        let snapshot=old.upgrade_startup_snapshot(&handoff).await.unwrap();
        let mut params=Params::parse(&json!({"id":uuid::Uuid::new_v4(),"fromGeneration":generation,"checkpoint":null})).unwrap();
        params.checkpoint=handoff.quiesce(&params,snapshot.clone()).unwrap().checkpoint;
        let cp=handoff.release(&params,snapshot).unwrap().checkpoint.unwrap();handoff.release_flushed(&params).unwrap();
        let boot=Handoff::open_fenced(dir.path(),uuid::Uuid::new_v4(),true,false,&cp).unwrap();
        let (successor,_)=Broker::open(config.clone(),dir.path().into()).unwrap();successor.install_handoff(boot.clone()).unwrap();
        assert!(!boot.accepting_writes());
        assert!(successor.prepare_assistant_using(||panic!("User preparation remains fenced"),async |_|panic!("No user Host launch during bootstrap")).await.is_err());
        successor.restore_assistant().await.expect("Fenced bootstrap with no saved assistant must not perform discovery or fail because writes are closed");
        assert!(!dir.path().join("assistant/profile.json").exists());
        CheckpointStore::new(dir.path()).verify(&cp,&config.computer_id).unwrap();
        successor.activate_handoff_successor(&boot,&cp).await.unwrap();assert!(boot.accepting_writes());
    }
    #[cfg(windows)]
    #[tokio::test(flavor="multi_thread",worker_threads=4)]
    async fn fenced_startup_saved_profile_attaches_original_host_without_rewriting_checkpoint() {
        use super::preparation_fixture::*;
        use crate::handoff::{Handoff,CheckpointRef,CheckpointStore};
        let f=Fixture::new();let root=f.dir.path().join("broker");storage::private_directory(&root).unwrap();
        let config=Config::create("wss://example.org/pocket".into()).unwrap();
        let (old,rx)=Broker::open(config.clone(),root.clone()).unwrap();let old_events=tokio::spawn(old.clone().receive_events(rx));
        let generation=uuid::Uuid::new_v4();let handoff=Handoff::open(&root,generation,true,false).unwrap();old.install_handoff(handoff.clone()).unwrap();
        let profile=Profile{schema:1,config_home:f.profile.config_home().into(),native_binary:f.profile.native_binary().into(),source_runtime_id:"saved-original".into()};
        let old_actor=old.ensure_assistant_with(profile.clone(),&f.controller,&f.registry).await.unwrap();
        old.refresh_loaded().await.expect("Predecessor must obtain a complete native inventory before checkpointing");
        // Pretty formatting is deliberate: a same-value reserialization would
        // still change the protected checkpoint's exact bytes.
        let path=root.join("assistant/profile.json");let original=serde_json::to_vec_pretty(&profile).unwrap();storage::atomic_write(&path,&original).unwrap();
        let descriptor=f.state().join("descriptor.json");let before=pocket_runtime_ipc::load_descriptor(&descriptor,f.profile.config_home()).unwrap();
        let params=json!({"id":uuid::Uuid::new_v4(),"fromGeneration":generation,"checkpoint":null});
        let ready=old.upgrade_local(&handoff,"upgrade/quiesce",params.clone()).await.unwrap();assert_eq!(ready["safeToExit"],true,"{ready}");
        let mut release=params;release["checkpoint"]=ready["checkpoint"].clone();
        let released=old.upgrade_local(&handoff,"upgrade/release",release.clone()).await.unwrap();old.upgrade_release_flushed(&handoff,&release).unwrap();
        let cp:CheckpointRef=serde_json::from_value(released["checkpoint"].clone()).unwrap();
        // Main exits the predecessor after release. Close only its fixture IPC
        // and worker, leaving the independent Host/native processes alive.
        for runtime in old.state.lock().await.runtimes.values() {runtime.rpc.detach_managed_host();}
        old_events.abort();let _=old_events.await;drop(old_actor);drop(old);
        let boot=Handoff::open_fenced(&root,uuid::Uuid::new_v4(),true,false,&cp).unwrap();
        let (successor,rx)=Broker::open(config.clone(),root.clone()).unwrap();successor.install_handoff(boot.clone()).unwrap();let events=tokio::spawn(successor.clone().receive_events(rx));
        let mut changed=profile.clone();changed.source_runtime_id="changed-authority".into();
        let error=successor.ensure_assistant_with(changed,&f.controller,&f.registry).await.err().unwrap();
        assert_eq!(error.downcast_ref::<RestoreFailure>(),Some(&RestoreFailure::ProfileMigrationFenced));
        assert_eq!(std::fs::read(&path).unwrap(),original);
        successor.restore_assistant_using(|_,_|panic!("Fenced restore must not run migration/PATH discovery"),async |saved|successor.ensure_assistant_with(saved,&f.controller,&f.registry).await).await.unwrap();
        assert!(!boot.accepting_writes());assert_eq!(std::fs::read(&path).unwrap(),original);
        let after=pocket_runtime_ipc::load_descriptor(&descriptor,f.profile.config_home()).unwrap();
        assert_eq!(after.host_process,before.host_process);assert_eq!(after.native_process,before.native_process);assert_eq!(after.generation,before.generation);
        CheckpointStore::new(&root).verify(&cp,&config.computer_id).unwrap();
        successor.refresh_loaded().await.expect("Successor must verify the original Host inventory before activation");
        successor.activate_handoff_successor(&boot,&cp).await.unwrap();assert!(boot.accepting_writes());
        let methods=std::fs::read_to_string(f.profile.config_home().join("fixture-methods")).unwrap();assert!(!methods.contains("turn/start"));assert!(!methods.contains("thread/start"));
        for runtime in successor.state.lock().await.runtimes.values() {runtime.rpc.detach_managed_host();}
        events.abort();let _=events.await;drop(successor);
        let runtime=f.controller.ensure(&f.profile).await.unwrap();runtime.shutdown_idle().await.unwrap();ended(&after).await;
    }
    #[tokio::test]
    async fn fenced_startup_missing_binary_never_migrates_or_saves_protected_profile() {
        use crate::handoff::{Handoff,Params,CheckpointStore};
        let dir=tempfile::tempdir().unwrap();let home=dir.path().join("home");std::fs::create_dir(&home).unwrap();
        let config=Config::create("wss://example.org/pocket".into()).unwrap();let (old,_)=Broker::open(config.clone(),dir.path().into()).unwrap();
        storage::private_directory(&dir.path().join("assistant")).unwrap();
        let path=dir.path().join("assistant/profile.json");let bytes=serde_json::to_vec_pretty(&json!({"schema":1,"configHome":home,"nativeBinary":dir.path().join("WindowsApps/OpenAI.Codex_26.1103.7773.0_x64__2p2nqsd0c76g0/app/resources/codex.exe"),"sourceRuntimeId":"old"})).unwrap();storage::atomic_write(&path,&bytes).unwrap();
        let generation=uuid::Uuid::new_v4();let h=Handoff::open(dir.path(),generation,true,false).unwrap();let snapshot=old.upgrade_startup_snapshot(&h).await.unwrap();
        let mut params=Params::parse(&json!({"id":uuid::Uuid::new_v4(),"fromGeneration":generation,"checkpoint":null})).unwrap();params.checkpoint=h.quiesce(&params,snapshot.clone()).unwrap().checkpoint;
        let cp=h.release(&params,snapshot).unwrap().checkpoint.unwrap();h.release_flushed(&params).unwrap();
        let boot=Handoff::open_fenced(dir.path(),uuid::Uuid::new_v4(),true,false,&cp).unwrap();let (successor,_)=Broker::open(config.clone(),dir.path().into()).unwrap();successor.install_handoff(boot.clone()).unwrap();
        let error=successor.restore_assistant_using(|_,_|panic!("Migration must not run under a protected checkpoint"),async |_|panic!("A missing binary cannot start a replacement behind the fence")).await.unwrap_err();
        assert_eq!(error.downcast_ref::<RestoreFailure>(),Some(&RestoreFailure::ProfileMigrationFenced));
        assert_eq!(std::fs::read(&path).unwrap(),bytes);assert!(!boot.accepting_writes());
        CheckpointStore::new(dir.path()).verify(&cp,&config.computer_id).unwrap();
    }
    #[tokio::test]
    async fn silent_restore_missing_native_binary_reports_the_actual_layer() {
        let dir=tempfile::tempdir().unwrap();
        let home=dir.path().join("home");std::fs::create_dir(&home).unwrap();
        let config=Config::create("wss://example.org/pocket".into()).unwrap();
        let (broker,_)=Broker::open(config,dir.path().into()).unwrap();
        storage::private_directory(&dir.path().join("assistant")).unwrap();
        let bytes=serde_json::to_vec(&json!({"schema":1,"configHome":home,"nativeBinary":dir.path().join("custom/missing.exe"),"sourceRuntimeId":"saved-source"})).unwrap();
        storage::atomic_write(&dir.path().join("assistant/profile.json"),&bytes).unwrap();
        assert!(broker.restore_assistant().await.is_err());
        let value=broker.assistant_read(&json!({})).await.unwrap();
        assert_eq!(value["error"],"assistant_native_binary_missing");
        assert_eq!(value["preparation"]["recoveryAction"],"locate_saved_native_codex_cli");
        assert_eq!(std::fs::read(dir.path().join("assistant/profile.json")).unwrap(),bytes);
    }
    #[cfg(windows)]
    #[tokio::test(flavor="multi_thread",worker_threads=4)]
    async fn silent_restore_verified_msix_persists_only_binary_after_old_owners_end() {
        use super::preparation_fixture::*;
        use crate::managed_runtime::discovery::{MsixPackage,restore_saved_profile_with};
        let f=Fixture::new();let apps=f.dir.path().join("WindowsApps");
        let old=apps.join("OpenAI.Codex_26.1103.7773.0_x64__2p2nqsd0c76g0/app/resources/codex.exe");
        let full_name="OpenAI.Codex_26.1106.6628.0_x64__2p2nqsd0c76g0".to_string();
        let package=MsixPackage{location:apps.join(&full_name),full_name};
        let new=package.location.join("app/resources/codex.exe");
        for path in [&old,&new] {std::fs::create_dir_all(path.parent().unwrap()).unwrap();std::fs::copy(native_fixture(),path).unwrap();}
        let mut registry=f.registry.clone();registry.msix_packages=Some(vec![package.clone()]);
        let controller=Controller::new(Installation::new(&f.bin,f.generation.clone()).unwrap(),registry.clone());
        let old_selected=SelectedProfile::new(f.profile.config_home(),&old).unwrap();
        let first=controller.ensure(&old_selected).await.unwrap();
        let d=pocket_runtime_ipc::load_descriptor(&f.state().join("descriptor.json"),f.profile.config_home()).unwrap();
        first.shutdown_idle().await.unwrap();ended(&d).await;std::fs::remove_file(&old).unwrap();
        let root=f.dir.path().join("broker");storage::private_directory(&root.join("assistant")).unwrap();
        let (broker,rx)=Broker::open(Config::create("wss://example.org/pocket".into()).unwrap(),root.clone()).unwrap();
        let events=tokio::spawn(broker.clone().receive_events(rx));
        let path=root.join("assistant/profile.json");
        let original=json!({"schema":1,"configHome":f.profile.config_home(),"nativeBinary":old,"sourceRuntimeId":"selected-before-msix-update"});
        let bytes=serde_json::to_vec(&original).unwrap();storage::atomic_write(&path,&bytes).unwrap();
        let account=f.profile.config_home().join("auth.json");std::fs::write(&account,b"fixture original account").unwrap();
        let saved=broker.saved_assistant_profile(|home,binary|restore_saved_profile_with(home,binary,||Ok(vec![package]))).unwrap().unwrap();
        assert_eq!(std::fs::read(&path).unwrap(),bytes,"Discovery/validation must not rewrite saved authority before the ownership guard");
        let actor=broker.ensure_assistant_with(saved,&controller,&registry).await.unwrap();
        let persisted:Value=serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
        let mut expected=original;expected["nativeBinary"]=json!(dunce::canonicalize(&new).unwrap());
        assert_eq!(persisted,expected);
        assert_eq!(std::fs::read(&account).unwrap(),b"fixture original account");
        let runtime=controller.ensure(&SelectedProfile::new(f.profile.config_home(),new).unwrap()).await.unwrap();
        let info=runtime.request(json!("msix-inspect"),"fixture/info",json!({})).await.unwrap();
        assert_eq!(info["result"]["writes"],0);
        let methods=std::fs::read_to_string(f.profile.config_home().join("fixture-methods")).unwrap();
        assert!(!methods.contains("turn/start"));assert!(!methods.contains("thread/start"));
        events.abort();drop(actor);drop(broker);runtime.shutdown_idle().await.unwrap();
        ended(&pocket_runtime_ipc::load_descriptor(&f.state().join("descriptor.json"),f.profile.config_home()).unwrap()).await;
    }
    #[tokio::test]
    async fn silent_prepare_invalid_saved_profile_has_recovery_hint() {
        let dir = tempfile::tempdir().unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (broker, _) = Broker::open(config, dir.path().into()).unwrap();
        storage::private_directory(&dir.path().join("assistant")).unwrap();
        storage::atomic_write(
            &dir.path().join("assistant/profile.json"),
            b"invalid saved profile",
        )
        .unwrap();
        assert!(broker.restore_assistant().await.is_err());
        let value = broker.assistant_read(&json!({})).await.unwrap();
        assert_eq!(value["preparation"]["status"], "recoverable_error");
        assert_eq!(
            value["preparation"]["recoveryAction"],
            "repair_saved_profile"
        );
        assert_eq!(value["ready"], false);
        assert_eq!(
            std::fs::read(dir.path().join("assistant/profile.json")).unwrap(),
            b"invalid saved profile"
        );
    }

    #[tokio::test]
    async fn silent_prepare_read_reports_preparing_without_waiting_for_launch() {
        let dir = tempfile::tempdir().unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (broker, _) = Broker::open(config, dir.path().into()).unwrap();
        *broker.assistant_error.lock().await = Some("assistant_preparing");
        let _launch = broker.assistant.lock().await;
        let value = tokio::time::timeout(
            Duration::from_millis(100),
            broker.assistant_read(&json!({})),
        )
        .await
        .expect("preparation status must be readable during a slow host launch")
        .unwrap();
        assert_eq!(value["preparation"]["status"], "preparing");
        assert_eq!(value["ready"], false);
    }
    #[test]
    fn collaboration_send_accepts_member_and_task_scope_but_requires_runtime() {
        assert!(Broker::validate_assistant("assistant/send", &json!({"commandId":"s","runtimeId":"r","message":"hello","memberId":format!("member:{}", "a".repeat(64)),"taskId":uuid::Uuid::new_v4()})).is_ok());
        assert!(Broker::validate_assistant("assistant/send", &json!({"commandId":"s","runtimeId":"r","message":"hello","memberId":"m","taskId":"t"})).is_ok());
        assert!(
            Broker::validate_assistant(
                "assistant/send",
                &json!({"commandId":"s","message":"hello","memberId":"m","taskId":"t"})
            )
            .is_err()
        );
        assert!(
            Broker::validate_assistant(
                "assistant/send",
                &json!({"commandId":"s","runtimeId":"r","message":"hello","reassign":"m"})
            )
            .is_err()
        );
    }
    #[tokio::test]
    async fn collaboration_read_accepts_pagination_and_rejects_invalid_task_scope() {
        let dir = tempfile::tempdir().unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (broker, _) = Broker::open(config, dir.path().into()).unwrap();
        assert!(
            broker
                .assistant_read(&json!({"after":0,"before":0,"limit":100,"taskId":"task-1"}))
                .await
                .is_ok()
        );
        for p in [
            json!({"before":-1}),
            json!({"limit":0}),
            json!({"limit":101}),
            json!({"taskId":"../task"}),
            json!({"taskId":""}),
            json!({"taskId":42}),
        ] {
            assert!(broker.assistant_read(&p).await.is_err(), "{p}");
        }
    }
    #[test]
    fn silent_prepare_saved_profile_never_uses_environment_fallback() {
        let dir = tempfile::tempdir().unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (broker, _) = Broker::open(config, dir.path().into()).unwrap();
        storage::private_directory(&dir.path().join("assistant")).unwrap();
        for value in [b"broken".to_vec(), serde_json::to_vec(&json!({"schema":99,"configHome":dir.path(),"nativeBinary":dir.path().join("missing.exe"),"sourceRuntimeId":"old"})).unwrap(), serde_json::to_vec(&json!({"schema":1,"configHome":dir.path().join("missing"),"nativeBinary":dir.path().join("missing.exe"),"sourceRuntimeId":"old"})).unwrap()] {
            storage::atomic_write(&dir.path().join("assistant/profile.json"), &value).unwrap();
            assert!(broker.preparation_profile(|| panic!("saved profile must suppress discovery")).is_err());
            assert_eq!(std::fs::read(dir.path().join("assistant/profile.json")).unwrap(), value);
        }
    }

    #[tokio::test]
    async fn silent_prepare_discovery_never_runs_behind_handoff_fence() {
        let dir = tempfile::tempdir().unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (broker, _) = Broker::open(config, dir.path().into()).unwrap();
        let generation = uuid::Uuid::new_v4();
        let handoff = crate::handoff::Handoff::open(dir.path(), generation, true, false).unwrap();
        broker.install_handoff(handoff.clone()).unwrap();
        let params = crate::handoff::Params::parse(
            &json!({"id":uuid::Uuid::new_v4(),"fromGeneration":generation,"checkpoint":null}),
        )
        .unwrap();
        handoff.begin_quiesce(&params).unwrap();
        assert!(!handoff.accepting_writes());
        let result = broker
            .prepare_assistant_using(
                || panic!("first discovery must wait for handoff activation"),
                async |_| panic!("must not launch behind the handoff fence"),
            )
            .await;
        assert_eq!(
            crate::error::code(&result.unwrap_err()),
            Code::OperationInProgress
        );
        assert!(!dir.path().join("assistant/profile.json").exists());
        assert!(!dir.path().join("managed-runtimes").exists());
    }

    #[test]
    fn silent_prepare_account_evidence_never_infers_login_from_initialization() {
        assert_eq!(
            account_status(&json!({"requiresOpenaiAuth":true,"account":null})),
            Some("assistant_needs_login")
        );
        assert_eq!(
            account_status(&json!({"requiresOpenaiAuth":false,"account":null})),
            None
        );
        assert_eq!(
            account_status(&json!({"requiresOpenaiAuth":true,"account":{"type":"chatgpt"}})),
            None
        );
        assert_eq!(
            account_status(&json!({"initialized":true})),
            Some("assistant_account_unavailable")
        );
        assert_eq!(
            account_status(&json!({"requiresOpenaiAuth":true,"account":{}})),
            Some("assistant_account_unavailable")
        );
    }

    #[tokio::test]
    async fn silent_prepare_missing_cli_or_config_has_hint_without_launch() {
        use crate::managed_runtime::discovery::Unavailable;
        for (failure, status, action) in [
            (
                Unavailable::MissingConfig,
                "missing_config",
                "create_or_repair_codex_home",
            ),
            (
                Unavailable::MissingCli,
                "missing_cli",
                "install_or_locate_native_codex_cli",
            ),
        ] {
            let dir = tempfile::tempdir().unwrap();
            let config = Config::create("wss://example.org/pocket".into()).unwrap();
            let (broker, _) = Broker::open(config, dir.path().into()).unwrap();
            assert!(
                broker
                    .prepare_assistant_using(
                        || Err(failure.into()),
                        async |_| panic!("unavailable discovery must not run a launcher"),
                    )
                    .await
                    .is_err()
            );
            let value = broker.assistant_read(&json!({})).await.unwrap();
            assert_eq!(value["preparationStatus"], status);
            assert_eq!(value["preparation"]["recoveryAction"], action);
            assert_eq!(value["ready"], false);
            assert!(!dir.path().join("assistant/profile.json").exists());
        }
    }

    #[cfg(windows)]
    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn silent_prepare_reuses_managed_host_reports_login_and_never_starts_model_turns() {
        use super::preparation_fixture::*;
        let f = Fixture::new();
        let root = f.dir.path().join("broker");
        storage::private_directory(&root).unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (broker, rx) = Broker::open(config, root.clone()).unwrap();
        broker
            .state
            .lock()
            .await
            .devices
            .add(
                "phone".into(),
                "Phone".into(),
                "fixture-secret",
                storage::now(),
            )
            .unwrap();
        let events = tokio::spawn(broker.clone().receive_events(rx));
        let discovered = std::sync::atomic::AtomicUsize::new(0);
        broker
            .prepare_assistant_using(
                || {
                    discovered.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                    Ok(f.profile.clone())
                },
                async |profile| {
                    broker
                        .ensure_assistant_with(profile, &f.controller, &f.registry)
                        .await
                },
            )
            .await
            .unwrap();
        let first = broker.assistant_read(&json!({})).await.unwrap();
        assert_eq!(first["preparation"]["status"], "needs_login", "{first}");
        assert_eq!(
            first["preparation"]["recoveryAction"],
            "login_with_codex_cli"
        );
        assert_eq!(
            first["ready"],
            broker.assistant.lock().await.as_ref().unwrap().snapshot()["ready"]
        );
        for _ in 0..2 {
            broker
                .prepare_assistant_using(
                    || panic!("repeat preparation must restore saved authority"),
                    async |profile| {
                        broker
                            .ensure_assistant_with(profile, &f.controller, &f.registry)
                            .await
                    },
                )
                .await
                .unwrap();
        }
        let reply = broker
            .request("phone", "assistant/prepare", json!({"commandId":"prepare"}))
            .await
            .unwrap();
        assert_eq!(reply["assistantId"], first["assistantId"]);
        let duplicate = broker
            .request("phone", "assistant/prepare", json!({"commandId":"prepare"}))
            .await
            .unwrap();
        assert_eq!(duplicate, reply);
        assert_eq!(discovered.load(std::sync::atomic::Ordering::SeqCst), 1);
        // Login status is native read evidence; never write credentials or refresh.
        std::fs::write(
            f.profile.config_home().join("fixture-account.json"),
            serde_json::to_vec(&json!({"requiresOpenaiAuth":true,"account":{"type":"chatgpt"}}))
                .unwrap(),
        )
        .unwrap();
        let logged_in = broker
            .assistant_read(&json!({"before":0,"limit":1}))
            .await
            .unwrap();
        assert_eq!(logged_in["preparation"]["status"], "ready", "{logged_in}");
        let runtime = f.controller.ensure(&f.profile).await.unwrap();
        let info = runtime
            .request(json!("inspect"), "fixture/info", json!({}))
            .await
            .unwrap();
        assert_eq!(info["result"]["writes"], 0);
        assert_eq!(info["result"]["initialized"], 1);
        let methods =
            std::fs::read_to_string(f.profile.config_home().join("fixture-methods")).unwrap();
        assert!(!methods.contains("turn/start"), "{methods}");
        assert!(!methods.contains("thread/start"), "{methods}");
        events.abort();
        drop(broker);
        runtime.shutdown_idle().await.unwrap();
        let descriptor = pocket_runtime_ipc::load_descriptor(
            &f.state().join("descriptor.json"),
            f.profile.config_home(),
        )
        .unwrap();
        ended(&descriptor).await;
    }
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
