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

#[path = "broker_assistant/project_read.rs"]
mod project_read;

#[path = "broker_assistant/command_receipts.rs"]
mod command_receipts;
#[path = "broker_assistant/models.rs"]
mod models;
use models::assistant_scope;

#[cfg(test)]
#[path = "broker_assistant/visibility_tests.rs"]
mod visibility_tests;
#[cfg(test)]
#[path = "broker_assistant/project_source_tests.rs"]
mod project_source_tests;
#[cfg(all(test, windows))]
#[path = "broker_assistant/project_read_tests.rs"]
mod project_read_tests;

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

#[derive(Default)]
pub(super) struct CoordinatorVisibility {
    store: Option<String>,
    threads: BTreeSet<String>,
    runtime_ids: BTreeSet<String>,
}
impl CoordinatorVisibility {
    pub(super) fn metadata(&self, state: &State, store: Option<&str>) -> Value {
        let Some(account) = self.store.as_deref().filter(|account| store.is_none_or(|s| s == *account)) else {
            return json!({"schema":1,"authoritative":true,"bindings":[],"hiddenThreads":[]});
        };
        let mut runtimes = self.runtime_ids.clone();
        runtimes.extend(state.runtimes.iter().filter(|(_,r)| r.store == account).map(|(id,_)|id.clone()));
        let hidden = runtimes.iter().flat_map(|runtime| self.threads.iter().map(move |thread|
            json!({"runtimeId":runtime,"configStoreId":account,"threadId":thread,"role":"assistant_coordinator"}))).collect::<Vec<_>>();
        json!({"schema":1,"authoritative":true,"bindings":[{
            "configStoreId":account,"runtimeIds":runtimes,"role":"Coordinator","threadIds":self.threads
        }],"hiddenThreads":hidden})
    }
    pub(super) fn internal(&self, store: &str, thread: Option<&str>) -> bool {
        self.store.as_deref() == Some(store) && thread.is_some_and(|t| self.threads.contains(t))
    }
    pub(super) fn event_thread<'a>(event: &'a Value) -> Option<&'a str> {
        event["params"]["threadId"].as_str().or_else(|| event["params"]["thread"]["id"].as_str())
    }
    pub(super) fn event(&self, store: &str, event: &Value) -> bool {
        self.internal(store, Self::event_thread(event))
    }
    pub(super) fn pending(&self, state: &State, pending: &Pending) -> bool {
        state.runtimes.get(&pending.runtime).is_some_and(|r| self.event(&r.store, &pending.event))
    }
}

impl Broker {
    pub(super) fn coordinator_visibility(&self) -> Result<CoordinatorVisibility> {
        let root = self.state_dir.join("assistant");
        let path = root.join("profile.json");
        if !path.try_exists()? {
            anyhow::ensure!(!root.join("executor.db").try_exists()?, "Assistant thread identity has no profile");
            return Ok(CoordinatorVisibility::default());
        }
        // Visibility must survive an unavailable Host/binary and disabled Actor.
        // Resolve only the retained account store, never environment discovery.
        let profile: Profile = serde_json::from_slice(&storage::read_physical(&path, 16384)?)?;
        anyhow::ensure!(profile.schema == 1, "Invalid assistant identity profile");
        let identity = crate::assistant_product::coordinator_identity(&root)?;
        let mut runtime_ids = BTreeSet::from([profile.source_runtime_id.clone()]);
        runtime_ids.extend(identity.runtime_id);
        Ok(CoordinatorVisibility {
            store: Some(storage::config_store(&profile.config_home)?),
            threads: identity.threads,
            runtime_ids,
        })
    }

    pub(super) async fn reject_coordinator_access(&self, runtime: &str, thread: Option<&str>) -> Result<()> {
        let Some(thread) = thread else { return Ok(()); };
        let visibility = self.coordinator_visibility().map_err(|_| classify(
            anyhow::anyhow!("Assistant thread identity unavailable; retry after reconciliation"), Code::Rejected,
        ))?;
        let state = self.state.lock().await;
        if state.runtimes.get(runtime).is_some_and(|r| visibility.internal(&r.store, Some(thread))) {
            return Err(classify(anyhow::anyhow!("Use the Assistant page to manage its internal coordinator"), Code::Rejected));
        }
        Ok(())
    }

    pub(super) fn public_runtime_values(&self, state: &State) -> Vec<Value> {
        let visibility = self.coordinator_visibility();
        let mut values = Self::runtime_values(state);
        for value in &mut values {
            let store = value["configStoreId"].as_str().unwrap_or_default().to_owned();
            value["assistantInternalThreads"] = visibility.as_ref()
                .map(|v|v.metadata(state,Some(&store)))
                .unwrap_or_else(|_|json!({"schema":1,"authoritative":false,"bindings":[]}));
            value["hiddenThreads"] = value["assistantInternalThreads"]["hiddenThreads"].as_array()
                .map(|rows|json!(rows)).unwrap_or_else(||json!([]));
            value["hiddenThreadsAuthoritative"] = value["assistantInternalThreads"]["authoritative"].clone();
            if let Some(threads) = value["loadedThreadIds"].as_array_mut() {
                threads.retain(|t| visibility.as_ref().is_ok_and(|v| !v.internal(&store, t.as_str())));
            }
        }
        values
    }

    pub(super) fn ordinary_questions(&self, state: &State, device: &str, thread: Option<&str>) -> Result<Vec<Value>> {
        let visibility = self.coordinator_visibility()?;
        // Receipt stores remain authoritative after their runtime detaches.
        let receipts = serde_json::to_value(&state.questions)?;
        let internal: BTreeSet<_> = receipts["records"].as_array().into_iter().flatten()
            .filter(|r| r["command"]["caller_id"] == device && visibility.internal(
                r["store"].as_str().unwrap_or_default(), r["thread"].as_str()))
            .filter_map(|r| r["command"]["command_id"].as_str()).collect();
        Ok(state.questions.list(device, thread).into_iter()
            .filter(|r| !r["commandId"].as_str().is_some_and(|id| internal.contains(id))).collect())
    }

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
        let (_, ()) = tokio::join!(self.refresh_loaded(), async {
            let request = peer.recv().await;
            assert_eq!(request["method"], "thread/loaded/list");
            peer.reply(&request, json!({"data":["t","desktop-thread"],"nextCursor":null})).await;
        });
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
            let store = state.runtimes.get(runtime).context("Source runtime offline")?.store.clone();
            self.require_assistant_project_owner(&state, runtime, thread, &store)?;
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
        self.require_assistant_project_owner(&state, runtime, thread, &r.store)?;
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
            &json!([runtime, r.store, bound_generation, thread, expected, settings]).to_string(),
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
    fn require_assistant_project_owner(&self, state: &State, runtime: &str, thread: &str, store: &str) -> Result<()> {
        let directory = self.directory(state)?;
        let owner = directory.resolve_write(&ThreadKey {
            computer_id:self.config.computer_id.clone(), config_store_id:store.into(), native_thread_id:thread.into(),
        }).map_err(|e|anyhow::anyhow!("Selected project has no verified native owner; open it on the source runtime ({e:?})"))?;
        anyhow::ensure!(owner.runtime_id == runtime, "Selected project owner changed; select its current owner runtime");
        Ok(())
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
                ,value["runtimeEventCursor"]
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
        self.reject_coordinator_access(runtime, p["threadId"].as_str()).await?;
        if matches!(method, "thread/resume" | "thread/name/set") {
            return Ok(());
        }
        let managed = {
            let state = self.state.lock().await;
            state
                .runtimes
                .get(runtime)
                .is_some_and(|r| r.role.assistant_private())
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
        crate::assistant_product::retained::begin_scheduler();
        self.restore_assistant_using(crate::managed_runtime::discovery::restore_saved_profile,async |profile|self.assistant_runtime(profile,true).await).await
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
            if !self.fenced_assistant_bootstrap() && !crate::assistant_product::retained::needs_attachment(&self.state_dir.join("assistant"))? { return Ok(()); }
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

    /// Only durable queued work or a schedule approaching its due time needs a Host.
    /// Reads and an empty installation never enter Controller::ensure.
    pub async fn assistant_wake_loop(self: Arc<Self>) {
        if crate::managed_runtime::packaged() { return; }
        loop {
            tokio::time::sleep(Duration::from_secs(1)).await;
            if self.assistant.try_lock().ok().is_none_or(|a|a.as_ref().is_some_and(Actor::is_alive)) { continue; }
            if crate::assistant_product::retained::needs_wake(&self.state_dir.join("assistant"), chrono::Utc::now().timestamp_millis()).unwrap_or(false) {
                if self.prepare_assistant().await.is_err() { tokio::time::sleep(Duration::from_secs(30)).await; }
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
                ,"startupStages":value["startupStages"]
            }),
            Err(_) => json!({"ready":false,"error":"assistant_status_unavailable"}),
        }
    }

    fn preparation_profile(
        &self,
        discover: impl FnOnce() -> Result<SelectedProfile>,
    ) -> Result<Profile> {
        self.check_write_fence()?;
        if self.state_dir.join("assistant/pending-profile.json").try_exists()? {
            let mut profile=self.assistant_read_profile()?.context("Selected assistant profile missing")?;
            let selected=crate::managed_runtime::discovery::restore_saved_profile(&profile.config_home,&profile.native_binary)?;
            profile.native_binary=selected.native_binary().into();
            return Ok(profile);
        }
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
        // The assistant's coordinator is held read-only and its jobs to their own worktree by
        // Codex's sandbox, which an Android application cannot create. It is not offered there
        // rather than run without those limits.
        // What is prepared there is the runtime its ordinary sessions run on.
        if crate::managed_runtime::packaged() {
            let started = self.ensure_device_runtime().await;
            *self.assistant_error.lock().await =
                Some(if started.is_ok() { ASSISTANT_UNAVAILABLE_ON_DEVICE } else { "assistant_runtime_start_failed" });
            return started.map(|_| ());
        }
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
        let actor = self.assistant.try_lock().ok().and_then(|a|a.as_ref().filter(|a|a.is_alive()).cloned());
        if let Some(actor)=actor.filter(|_|!self.state_dir.join("assistant/pending-profile.json").exists()) {
            return self.apply_pending_assistant_settings(&actor).await;
        }
        *self.assistant_error.lock().await = Some("assistant_preparing");
        let result = async {
            let profile = self.preparation_profile(discover)?;
            let actor=ensure(profile).await.map_err(runtime_restore_error)?;
            self.apply_pending_assistant_settings(&actor).await?;
            let pending=self.state_dir.join("assistant/pending-profile.json");
            if pending.try_exists()? {std::fs::remove_file(pending)?;}
            Ok(())
        }
        .await;
        let error = result.as_ref().err().map(|error| {
            assistant_preparation_error(error,self.state_dir.join("assistant/profile.json").exists())
        });
        *self.assistant_error.lock().await = error;
        result
    }

    /// Starts, or attaches to, the hosted runtime that carries ordinary sessions on a packaged
    /// Android installation, and returns its id. The assistant is not started on it.
    pub(super) async fn ensure_device_runtime(&self) -> Result<String> {
        // One preparation at a time, as for the assistant this runtime otherwise belongs to.
        let _serial = self.assistant.lock().await;
        let online = |state: &State| state.runtimes.iter()
            .find(|(_, r)| r.rpc.online() && matches!(r.role, crate::native::NativeRole::ManagedHost { .. }))
            .map(|(id, _)| id.clone());
        if let Some(id) = online(&*self.state.lock().await) { return Ok(id); }
        self.check_write_fence()?;
        let selected = crate::managed_runtime::discovery::from_environment()?;
        let executable = std::env::current_exe()?;
        let generation = crate::installation::current()["generation"].as_str().context("Installed generation unavailable")?.to_string();
        let installation = Installation::new(executable.parent().context("Missing installation")?, generation)?;
        let registry = Registry::new(self.state_dir.join("managed-runtimes"))?;
        let descriptor = registry.profile_directory(&selected)?.join("descriptor.json");
        let runtime = Controller::new(installation, registry).ensure(&selected).await?;
        let cursor = self.handoff.get().and_then(|h| h.recovery_snapshot())
            .and_then(|s| s.runtimes.get(runtime.runtime_id()).and_then(|r| r.host_cursor.as_ref())
                .map(|c| crate::native::host::Cursor { generation: c.generation.clone(), after: c.after }));
        let connected = runtime.attach(self.native_events.clone(), cursor).await?;
        if let Some(h) = self.handoff.get() { h.register_host_descriptor(runtime.runtime_id(), &descriptor)?; }
        let id = connected.runtime_id.clone();
        self.add_runtime(connected).await?;
        Ok(id)
    }

    async fn ensure_assistant(&self, profile: Profile) -> Result<Actor> {
        self.assistant_runtime(profile,false).await
    }
    async fn assistant_runtime(&self, profile: Profile, attach_only: bool) -> Result<Actor> {
        let executable = std::env::current_exe()?;
        let bin = executable.parent().context("Missing installation")?;
        let generation = crate::installation::current()["generation"]
            .as_str()
            .context("Installed generation unavailable")?
            .to_string();
        let installation = Installation::new(bin, generation)?;
        let registry = if self.fenced_assistant_bootstrap(){Registry::existing(self.state_dir.join("managed-runtimes"))?}else{Registry::new(self.state_dir.join("managed-runtimes"))?};
        let controller = Controller::new(installation, registry.clone());
        self.assistant_with_mode(profile, &controller, &registry,attach_only)
            .await
    }

    #[cfg(test)]
    async fn ensure_assistant_with(
        &self,
        profile: Profile,
        controller: &Controller,
        registry: &Registry,
    ) -> Result<Actor> {
        self.assistant_with_mode(profile,controller,registry,false).await
    }
    async fn assistant_with_mode(&self, profile: Profile, controller: &Controller, registry: &Registry, attach_only: bool) -> Result<Actor> {
        let readonly=self.fenced_assistant_bootstrap();
        if readonly {
            let saved=self.saved_assistant_profile(validate_fenced_saved_profile)?;
            anyhow::ensure!(saved.as_ref()==Some(&profile),RestoreFailure::ProfileMigrationFenced);
        }else{self.check_write_fence()?;}
        self.assistant_permission_handle
            .get_or_init(tokio::runtime::Handle::current);
        let mut current = self.assistant.lock().await;
        let selected = SelectedProfile::new(&profile.config_home, &profile.native_binary)?;
        let descriptor = registry
            .profile_directory(&selected)?
            .join("descriptor.json");
        let runtime = if let Some(actor)=current.as_ref().filter(|actor|actor.is_alive()).cloned() {
            if readonly {return Ok(actor);}
            // Park the single consumer at a request boundary. The controller
            // owns retirement eligibility; a still-active Host remains intact.
            let old=actor.park_for_runtime_transition().await?;
            let checked=controller.ensure(&selected).await;
            let runtime=match checked {
                Ok(runtime)=>runtime,
                Err(error)=>{actor.resume_after_runtime_check().await?;return Err(error);}
            };
            if runtime.generation()==old {
                actor.resume_after_runtime_check().await?;
                storage::atomic_write(&self.state_dir.join("assistant/profile.json"),&serde_json::to_vec(&profile)?)?;
                return Ok(actor);
            }
            // Acknowledgement follows Worker drop, including the Service and
            // Executor owner locks. Cloned Actor handles cannot keep it alive.
            actor.stop_parked_worker().await?;
            *current=None;
            Arc::new(runtime)
        } else {
            *current=None;
            Arc::new(if readonly || attach_only {controller.attach_existing(&selected).await?}else{controller.ensure(&selected).await?})
        };
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
        // Live frames go to connected phones as they are; the task ends with this actor.
        let (mut frames, phones) = (actor.subscribe_stream(), self.notifications.clone());
        tokio::spawn(async move {
            use tokio::sync::broadcast::error::RecvError;
            loop {
                match frames.recv().await {
                    Ok(frame) => {
                        let _ = phones.send(json!({"method":"assistant/stream","params":frame}));
                    }
                    // A phone that missed frames sees the gap in their sequence and reads again.
                    Err(RecvError::Lagged(_)) => continue,
                    Err(RecvError::Closed) => break,
                }
            }
        });
        Ok(actor)
    }
    pub(super) async fn assistant_artifact_read(&self, p:&Value)->Result<Value> {
        anyhow::ensure!(p.as_object().is_some_and(|o|o.keys().all(|k|["artifactId","offset"].contains(&k.as_str()))) && p["artifactId"].as_str().is_some_and(|s|!s.is_empty() && s.len()<=512) && p.get("offset").is_none_or(|v|v.as_u64().is_some()),"Invalid artifact read");
        let actor=self.assistant.try_lock().ok().and_then(|a|a.clone()).context("Assistant unavailable")?;
        actor.request("assistant/artifact/read",p.clone(),format!("artifact-read:{}",uuid::Uuid::new_v4()),None).await
    }

    pub(super) async fn assistant_read(&self, p: &Value) -> Result<Value> {
        if p.as_object().is_none_or(|o| {
            o.keys()
                .any(|k| !["after", "before", "limit", "taskId", "models", "conversationId"].contains(&k.as_str()))
        }) || ["after", "before"]
            .iter()
            .any(|k| p.get(k).is_some_and(|v| v.as_u64().is_none()))
            || p.get("limit")
                .is_some_and(|v| v.as_u64().is_none_or(|n| !(1..=100).contains(&n)))
            || p.get("taskId").is_some_and(|v| !valid_scope_id(v))
            || p.get("models").is_some_and(|v| !v.is_boolean())
            || p.get("conversationId").is_some_and(|v| !valid_assistant_id(v))
        {
            return Err(classify(
                anyhow::anyhow!("Invalid assistant read"),
                Code::InvalidParams,
            ));
        }
        // ensure holds this lock while launching. Reads must still expose the
        // preparing state, without queueing behind native startup/handshake.
        let actor = self.assistant.try_lock().ok().and_then(|a| a.clone());
        let idle = actor.is_none();
        let selection_pending=self.state_dir.join("assistant/pending-profile.json").try_exists()?;
        let mut read = p.clone();
        read.as_object_mut().unwrap().remove("models");
        let mut result = if let Some(actor) = actor {
            actor
                .request(
                    "assistant/read",
                    read.clone(),
                    format!("read:{}", uuid::Uuid::new_v4()),
                    None,
                )
                .await
                .or_else(|error| {
                    if p.get("conversationId").is_some() {crate::assistant_product::retained::snapshot(&self.state_dir.join("assistant"),p).map_err(|_|error)} else {Ok(actor.snapshot())}
                })?
        } else {
            crate::assistant_product::retained::snapshot(&self.state_dir.join("assistant"),p)?
        };
        let profile = match self.assistant_read_profile() {
            Ok(profile)=>profile,
            Err(_) => { *self.assistant_error.lock().await=Some("assistant_restore_failed"); None }
        };
        let scope = assistant_scope(profile.as_ref())?;
        result["scopeId"] = json!(scope);
        result["selectedRuntimeId"] = json!(profile.as_ref().map(|p|p.source_runtime_id.as_str()).filter(|id|!id.is_empty()));
        if let Some(saved)=crate::assistant_product::retained::pending_settings(&self.state_dir.join("assistant"), &scope)? {
            result["settings"] = saved["settings"].clone();
        }
        if p["models"] == true { result["models"] = self.assistant_models(profile.as_ref()).await?; }
        let mut error = *self.assistant_error.lock().await;
        if crate::managed_runtime::packaged() { error=Some(ASSISTANT_UNAVAILABLE_ON_DEVICE); }
        if !idle && !selection_pending && error.is_none() && result["runtimeId"].as_str().is_some() {
            error = if result.get("accountReady").is_some() {
                match result["error"].as_str() { Some("assistant_needs_login")=>Some("assistant_needs_login"), Some("assistant_account_unavailable")=>Some("assistant_account_unavailable"), _=>None }
            } else { self.assistant_account_status(&result).await };
            if error.is_none() && result["ready"] != true && result["dispatchInFlight"] != true {
                // A receipt reconciliation or a capability failure belongs to
                // its own stage; do not relabel it as a failed Host startup.
                if result["error"].is_null() { error = Some("assistant_runtime_unavailable"); }
            }
        }
        if let Some(error) = error.filter(|e| *e != "assistant_preparing") {
            if result["error"].is_null() {
                result["error"] = json!(error);
            }
            result["recoveryAction"] = json!(preparation_hint(error));
        }
        if selection_pending {result["ready"]=json!(false);result["acceptingMessages"]=json!(false);}
        let status = match error {
            Some("assistant_preparing") => "starting",
            Some("assistant_missing_cli") => "missing_cli",
            Some("assistant_missing_config") => "missing_config",
            Some("assistant_needs_login") => "needs_login",
            Some(_) => "recoverable_error",
            None if idle || selection_pending => "idle",
            None if result["ready"] == true || result["dispatchInFlight"] == true => "ready",
            None => "recoverable_error",
        };
        // The worker snapshot's ready/fresh remain authoritative. This describes
        // setup and auth evidence, and never infers login from a config file.
        result["preparation"] = json!({"status":status,"recoveryAction":error.map(preparation_hint),"retryable":status != "ready"});
        result["preparationStatus"] = json!(status);
        if !result["startupStages"].is_array() {
            result["startupStages"] = json!([
                {"id":"runtime","status":if status=="starting" {"preparing"}else if status=="idle" {"idle"}else{"blocked"},"recoveryAction":error.map(preparation_hint)},
                {"id":"account","status":"waiting","recoveryAction":null},
                {"id":"capabilities","status":"waiting","recoveryAction":null},
                {"id":"coordinator","status":"waiting","sessionCreated":false,"recoveryAction":null}
            ]);
        }
        let state = self.state.lock().await;
        let internal = self.coordinator_visibility().map(|v|v.metadata(&state,None))
            .unwrap_or_else(|_|json!({"authoritative":false,"hiddenThreads":[]}));
        result["hiddenThreads"] = internal["hiddenThreads"].clone();
        result["hiddenThreadsAuthoritative"] = internal["authoritative"].clone();
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
            "assistant/prepare" => &["scopeId"],
            "assistant/enable" | "assistant/pause" => &[],
            "assistant/resume" => &["operationId"],
            "assistant/send" | "assistant/v2/send" => &["conversationId", "message", "threadId", "sourceRuntimeId", "sourceConfigStoreId", "memberId", "taskId", "artifactId", "artifactVersion", "attachments", "scopeId"],
            "assistant/v2/conversation/create" => &["title"],
            "assistant/v2/conversation/interrupt" => &["conversationId","operationId"],
            "assistant/task/pause" | "assistant/task/resume" => &["taskId"],
            "assistant/v2/task/retry" => &["taskId"],
            "assistant/v2/task/merge" | "assistant/v2/task/discard_worktree" => &["taskId"],
            "assistant/v2/task/seen" => &["taskId","throughSequence"],
            "assistant/task/memory" => &["taskId", "enabled"],
            "assistant/settings/update" => &["settings", "patch"],
            "assistant/memory/add" => &["text", "scope"],
            "assistant/memory/update" => &["text", "memoryId"],
            "assistant/memory/delete" | "assistant/memory/undo" => &["memoryId"],
            "assistant/schedule/create" => &["threadId", "sourceRuntimeId", "sourceConfigStoreId", "spec"],
            "assistant/schedule/update" => &["scheduleId", "spec", "missedPolicy"],
            "assistant/schedule/delete" | "assistant/schedule/pause" | "assistant/schedule/resume" | "assistant/schedule/run" => &["scheduleId"],
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
            || p.get("sourceRuntimeId").is_some_and(|source| {
                !valid_scope_id(source)
                    || !p["threadId"].as_str().is_some_and(|t| crate::settings::valid_thread(t) && t.len() <= 256)
            })
            || p.get("sourceConfigStoreId").is_some_and(|store| {
                !valid_scope_id(store)
                    || !p["threadId"].as_str().is_some_and(|t| crate::settings::valid_thread(t) && t.len() <= 256)
            })
            || (method != "assistant/prepare"
                && p["runtimeId"]
                    .as_str()
                    .is_none_or(|s| s.is_empty() || s.len() > 256))
            || (matches!(method,"assistant/send"|"assistant/v2/send")
                && ["memberId", "taskId"].iter().any(|key| {
                    p.get(key).is_some_and(|v| {
                        if *key == "memberId" {
                            !valid_assistant_id(v)
                        } else {
                            !valid_scope_id(v)
                        }
                    })
                }))
            // Merging or dropping a branch names exactly one job; nothing is inferred.
            || (matches!(method, "assistant/v2/task/merge" | "assistant/v2/task/discard_worktree")
                && !p.get("taskId").is_some_and(valid_scope_id))
            || p.to_string().len() > if matches!(method,"assistant/send"|"assistant/v2/send") {2*1024*1024} else {131072}
        {
            return Err(classify(
                anyhow::anyhow!("Invalid assistant action"),
                Code::InvalidParams,
            ));
        }
        if let Some(conversation)=p.get("conversationId") {anyhow::ensure!(valid_assistant_id(conversation),"Invalid conversation ID");}
        if method=="assistant/v2/conversation/create" {anyhow::ensure!(p.get("title").is_none_or(|v|v.as_str().is_some_and(|s|!s.trim().is_empty() && s.len()<=512 && !s.contains('\0'))),"Invalid conversation title");}
        if method=="assistant/v2/conversation/interrupt" {anyhow::ensure!(p.get("conversationId").is_some_and(valid_assistant_id) && p.get("operationId").is_some_and(valid_scope_id),"Exact coordinator scope required");}
        if let Some(inputs)=p.get("attachments") {crate::assistant::inputs::validate_uploaded(inputs)?;}
        if let Some(scope)=p.get("scopeId") { anyhow::ensure!(valid_assistant_id(scope), "Invalid assistant scope"); }
        Ok(())
    }
    pub(super) async fn assistant_write(
        &self,
        device: &str,
        method: &str,
        p: &Value,
    ) -> Result<Value> {
        Self::validate_assistant(method, p)?;
        if withheld_write(method, crate::managed_runtime::packaged()) {
            return Err(classify(anyhow::anyhow!("The assistant is not available on this device"), Code::RuntimeUnavailable));
        }
        if method == "assistant/prepare" {
            if let Some(scope)=p["scopeId"].as_str() {
                anyhow::ensure!(scope == assistant_scope(self.assistant_read_profile()?.as_ref())?, "Assistant scope changed; keep the draft and select its original profile");
            }
            self.prepare_assistant_inner().await.map_err(|e|classify(e,Code::RuntimeUnavailable))?;
            return self.assistant_read(&json!({})).await;
        }
        let runtime_id = field(p, "runtimeId")?;
        if method == "assistant/settings/update" && (self.state_dir.join("assistant/pending-profile.json").try_exists()? || self.assistant.lock().await.as_ref().is_none_or(|a|!a.is_alive())) {
            let profile = self.assistant_read_profile()?;
            let scope = assistant_scope(profile.as_ref())?;
            anyhow::ensure!(runtime_id == scope, "Assistant settings scope changed");
            let snapshot = self.assistant_read(&json!({})).await?;
            let current: crate::assistant::Settings = serde_json::from_value(snapshot["settings"].clone())?;
            let settings = current.updated(p)?;
            let selections = settings.changed_effort_selections(&current);
            if !selections.is_empty() {
                let catalog = self.assistant_models(profile.as_ref()).await?;
                for (model, effort) in selections {
                    crate::assistant::model_capabilities::verify_catalog_effort(
                        catalog.as_array().context("Assistant model catalog unavailable")?, model.as_deref(), &effort,
                    ).map_err(|error| classify(error, Code::Rejected))?;
                }
            }
            let _: chrono_tz::Tz = settings.timezone.parse().context("Unknown timezone")?;
            let root = self.state_dir.join("assistant");
            storage::private_directory(&root)?;
            let profile=profile.context("Select a configured assistant runtime first")?;
            storage::atomic_write(&root.join("pending-profile.json"), &serde_json::to_vec(&profile)?)?;
            crate::assistant_product::retained::save_pending_settings(&root,&scope,Some(json!({"scopeId":scope,"configStoreId":storage::config_store(&profile.config_home)?,"commandId":field(p,"commandId")?,"settings":settings})))?;
            return Ok(json!({"accepted":true,"commandId":field(p,"commandId")?,"snapshot":self.assistant_read(&json!({})).await?}));
        }
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
            let profile = Profile {
                    schema: 1,
                    config_home: home,
                    native_binary: binary,
                    source_runtime_id: runtime_id.into(),
                };
            storage::private_directory(&self.state_dir.join("assistant"))?;
            storage::atomic_write(&self.state_dir.join("assistant/pending-profile.json"),&serde_json::to_vec(&profile)?)?;
            return self.assistant_read(&json!({})).await;
        }
        if method=="assistant/v2/conversation/create" {
            let current=self.assistant.lock().await;
            if current.as_ref().is_none_or(|actor|!actor.is_alive()) {
                self.check_write_fence()?;
                anyhow::ensure!(self.device_exists(device).await,"Device revoked");
                let profile=self.assistant_read_profile()?.context("Select a configured assistant runtime first")?;
                let scope=assistant_scope(Some(&profile))?;
                let retained_runtime=crate::assistant_product::coordinator_identity(&self.state_dir.join("assistant"))?.runtime_id;
                anyhow::ensure!(runtime_id==scope || runtime_id==profile.source_runtime_id || retained_runtime.as_deref()==Some(runtime_id),"Wrong assistant profile");
                return crate::assistant_product::retained::create_conversation(&self.state_dir.join("assistant"),&self.config.computer_id,
                    &storage::config_store(&profile.config_home)?,device,p);
            }
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
        if runtime_id != chosen && runtime_id != profile.source_runtime_id && runtime_id != assistant_scope(Some(&profile))? {
            return Err(classify(
                anyhow::anyhow!("Wrong assistant profile"),
                Code::InvalidParams,
            ));
        }
        if let Some(scope)=p["scopeId"].as_str() {
            if scope != assistant_scope(Some(&profile))? {
                return Err(classify(anyhow::anyhow!("Assistant scope changed before dispatch; draft retained"),Code::InvalidParams));
            }
        }
        // Parent integration point: target-send/group validation belongs here,
        // before project proof and actor dispatch; prepare has no target scope.
        if !self.device_exists(device).await {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        let directory = if let Some(thread) = p.get("threadId") {
            let thread = thread
                .as_str()
                .filter(|s| crate::settings::valid_thread(s))
                .context("Invalid project thread")?;
            let source = p["sourceRuntimeId"].as_str().unwrap_or(runtime_id);
            {
                let state = self.state.lock().await;
                let current = state.runtimes.get(source).context("Project source runtime offline")?;
                anyhow::ensure!(p["sourceConfigStoreId"].as_str().is_none_or(|store| store == current.store),
                    "Selected project config store changed; select the current native project");
                self.require_assistant_project_owner(&state, source, thread, &current.store)?;
            }
            self.reject_coordinator_access(source, Some(thread)).await?;
            let (workspace, _, _, _) = self.workspace_proof(source, thread).await?;
            workspace.roots().first().cloned()
        } else {
            None
        };
        if !self.device_exists(device).await {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        let mut parameters=p.clone();
        if matches!(method,"assistant/send"|"assistant/v2/send") {
            if let Some(inputs)=p.get("attachments") {
                let state=self.state.lock().await;
                parameters["retainedAttachments"]=serde_json::to_value(crate::assistant::inputs::retain_uploaded(inputs,device,&state.attachments,
                    &self.state_dir.join("assistant/input-images"))?)?;
            }
        }
        actor
            .request_from_device(method, parameters, field(p, "commandId")?.into(), directory, device.into())
            .await
            .map_err(|e| {let code=if e.downcast_ref::<crate::assistant_product::ModelSelectionUnavailable>().is_some() {Code::Rejected}else{Code::Unknown};classify(e,code)})
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

const ASSISTANT_UNAVAILABLE_ON_DEVICE: &str = "assistant_unavailable_on_device";
/// Preparing is allowed on a packaged Android installation because it starts the runtime
/// ordinary sessions use; nothing that would drive the assistant itself is.
fn withheld_write(method: &str, packaged: bool) -> bool {
    packaged && method != "assistant/prepare"
}
fn runtime_restore_error(error:anyhow::Error)->anyhow::Error {
    if error.downcast_ref::<RestoreFailure>().is_some(){error}else{error.context(RestoreFailure::RuntimeStart)}
}
fn assistant_preparation_error(error:&anyhow::Error,saved:bool)->&'static str {
    use crate::managed_runtime::discovery::Unavailable;
    if error.downcast_ref::<crate::assistant_product::ModelSelectionUnavailable>().is_some() {return "assistant_model_unavailable";}
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
    #[test]
    fn conversation_wire_contract_validates_explicit_interrupt_and_optional_send_scope() {
        use serde_json::json;
        use super::Broker;
        for method in ["assistant/send","assistant/v2/send"] {
            assert!(Broker::validate_assistant(method,&json!({"runtimeId":"r","commandId":"c","message":"hello","conversationId":"main:owner"})).is_ok());
            assert!(Broker::validate_assistant(method,&json!({"runtimeId":"r","commandId":"c","message":"hello","conversationId":42})).is_err());
        }
        assert!(Broker::validate_assistant("assistant/v2/conversation/create",&json!({"runtimeId":"r","commandId":"c","title":"新对话"})).is_ok());
        assert!(Broker::validate_assistant("assistant/v2/conversation/create",&json!({"runtimeId":"r","commandId":"c","title":" "})).is_err());
        assert!(Broker::validate_assistant("assistant/v2/conversation/interrupt",&json!({"runtimeId":"r","commandId":"c","conversationId":"chat:id","operationId":"operation-id"})).is_ok());
        for missing in ["conversationId","operationId"] {
            let mut p=json!({"runtimeId":"r","commandId":"c","conversationId":"chat:id","operationId":"operation-id"});
            p.as_object_mut().unwrap().remove(missing);
            assert!(Broker::validate_assistant("assistant/v2/conversation/interrupt",&p).is_err());
        }
    }
    use super::*;
    #[tokio::test]
    async fn idle_read_never_prepares_or_creates_an_assistant() {
        let dir = tempfile::tempdir().unwrap();
        let config = Config::create("wss://example.org/pocket".into()).unwrap();
        let (broker, _) = Broker::open(config, dir.path().into()).unwrap();
        let read = broker.assistant_read(&json!({})).await.unwrap();
        assert_eq!(read["preparationStatus"], "idle");
        assert!(!dir.path().join("assistant/profile.json").exists());
        assert!(broker.assistant.lock().await.is_none());
    }
    #[tokio::test]
    async fn model_panel_reads_only_saved_home_and_settings_are_saved_without_ensure() {
        let dir=tempfile::tempdir().unwrap();
        let home=dir.path().join("home");std::fs::create_dir(&home).unwrap();
        std::fs::write(home.join("config.toml"),"model = 'tenant-private'\napi_key = 'private-secret'\n").unwrap();
        std::fs::write(home.join("models_cache.json"),r#"{"models":[{"slug":"cached-model","display_name":"Cached"}]}"#).unwrap();
        let (broker,_)=Broker::open(Config::create("wss://example.org/pocket".into()).unwrap(),dir.path().into()).unwrap();
        let root=dir.path().join("assistant");storage::private_directory(&root).unwrap();
        let profile=Profile {schema:1,config_home:home.clone(),native_binary:std::env::current_exe().unwrap(),source_runtime_id:"source".into()};
        storage::atomic_write(&root.join("profile.json"),&serde_json::to_vec(&profile).unwrap()).unwrap();
        let read=broker.assistant_read(&json!({"models":true})).await.unwrap();
        assert_eq!(read["preparationStatus"],"idle");
        assert_eq!(read["models"].as_array().unwrap().len(),2);
        assert!(!read.to_string().contains("private-secret"));
        let scope=assistant_scope(Some(&profile)).unwrap();
        broker.assistant_write("phone","assistant/settings/update",&json!({"runtimeId":scope,"commandId":"choose","patch":{"coordinator_model":"cached-model"}})).await.unwrap();
        let saved=broker.assistant_read(&json!({"before":100,"limit":10})).await.unwrap();
        assert_eq!(saved["settings"]["coordinator_model"],"cached-model");
        assert!(broker.assistant.lock().await.is_none());
        assert!(broker.state.lock().await.runtimes.is_empty());
        assert!(!root.join("executor.db").exists());
        assert_eq!(std::fs::read_to_string(home.join("config.toml")).unwrap(),"model = 'tenant-private'\napi_key = 'private-secret'\n");
    }
    #[test]
    fn packaged_device_runs_ordinary_sessions_on_the_hosted_runtime_and_withholds_the_assistant() {
        use crate::native::NativeRole;
        let hosted = NativeRole::ManagedHost { generation: "g".into(), reconciliation_required: false };
        // Elsewhere the hosted runtime is the assistant's alone.
        assert!(hosted.assistant_private_when(false) && !hosted.serves_cli_sessions_when(false));
        assert_eq!(hosted.shown_when(false)["kind"], "managedHost");
        // On the device it is where sessions run, and a phone is told so.
        assert!(!hosted.assistant_private_when(true) && hosted.serves_cli_sessions_when(true));
        assert_eq!(hosted.shown_when(true), json!({"kind":"cliDaemon"}));
        for packaged in [false, true] {
            assert!(NativeRole::CliDaemon.serves_cli_sessions_when(packaged) && !NativeRole::CliDaemon.assistant_private_when(packaged));
            assert!(!NativeRole::Desktop.serves_cli_sessions_when(packaged));
            assert_eq!(NativeRole::Desktop.shown_when(packaged)["kind"], "desktop");
        }
        assert!(!withheld_write("assistant/prepare", true));
        for method in ["assistant/send", "assistant/enable", "assistant/settings/update", "assistant/schedule/create"] {
            assert!(withheld_write(method, true) && !withheld_write(method, false));
        }
    }
    #[test]
    fn silent_prepare_accepts_no_selected_runtime_and_rejects_phone_paths() {
        assert!(Broker::validate_assistant("assistant/prepare",&json!({"commandId":"prepare","scopeId":"assistant:store:runtime"})).is_ok());
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
        assert_eq!(value["preparation"]["status"], "starting");
        assert_eq!(value["ready"], false);
    }
    #[test]
    fn job_merge_and_discard_take_exactly_one_task_and_a_runtime() {
        for method in ["assistant/v2/task/merge", "assistant/v2/task/discard_worktree"] {
            assert!(Broker::validate_assistant(method, &json!({"commandId":"c","runtimeId":"r","taskId":"t"})).is_ok(), "{method}");
            assert!(Broker::validate_assistant(method, &json!({"commandId":"c","taskId":"t"})).is_err(), "{method} needs a runtime");
            assert!(Broker::validate_assistant(method, &json!({"commandId":"c","runtimeId":"r"})).is_err(), "{method} needs a task");
            assert!(Broker::validate_assistant(method, &json!({"commandId":"c","runtimeId":"r","taskId":"t","force":true})).is_err(), "{method} takes nothing else");
            assert!(Broker::validate_assistant(method, &json!({"commandId":"c","runtimeId":"r","taskId":"../t"})).is_err(), "{method} rejects a path as a task");
        }
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
