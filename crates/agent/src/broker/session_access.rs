//! Explicit session routing. Reading history never acquires a native thread.
use super::*;
use serde::{Deserialize, Serialize};

const MAX_OPERATIONS: usize = 128;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub(super) enum AccessMode {
    #[default]
    Desktop,
    Cli,
}
impl AccessMode {
    fn parse(value: &Value) -> Result<Self> {
        match value.as_str() {
            Some("desktop") => Ok(Self::Desktop),
            Some("cli") => Ok(Self::Cli),
            _ => Err(classify(
                anyhow::anyhow!("Invalid access mode"),
                Code::InvalidParams,
            )),
        }
    }
    fn source(self) -> &'static str {
        if self == Self::Desktop {
            "desktop"
        } else {
            "cli"
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct AccessOperation {
    operation_id: String,
    device: String,
    store: String,
    thread: String,
    origin: String,
    origin_generation: String,
    target: Option<String>,
    mode: AccessMode,
    phase: String,
    reason: Option<String>,
    release_dispatched: bool,
    attach_dispatched: bool,
    #[serde(default)]
    release_confirmed: bool,
    #[serde(default)]
    attach_confirmed: bool,
    #[serde(default)]
    target_generation: Option<String>,
    cancelled: bool,
    updated_at: u64,
}
impl AccessOperation {
    fn pending(&self) -> bool {
        !self.cancelled
            && !matches!(
                self.phase.as_str(),
                "ready" | "blocked" | "failed" | "cancelled"
            )
    }
    fn awaiting_outcome(&self) -> bool {
        (self.release_dispatched && !self.release_confirmed)
            || (self.attach_dispatched && !self.attach_confirmed)
    }
    fn can_cancel(&self) -> bool {
        !self.cancelled && (self.pending() || self.awaiting_outcome())
    }
}

#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct AccessStore {
    #[serde(default)]
    computer_id: String,
    #[serde(default)]
    pub default_mode: AccessMode,
    #[serde(default)]
    revision: u64,
    #[serde(default)]
    modes: BTreeMap<String, AccessMode>,
    #[serde(default)]
    operations: BTreeMap<String, AccessOperation>,
    /// Only threads intentionally observed through Pocket are subscribed to CLI.
    #[serde(skip)]
    watched: BTreeSet<(String, String)>,
    /// A handoff must never be undone by automatic discovery/subscription.
    #[serde(default)]
    released: BTreeSet<(String, String)>,
}
impl AccessStore {
    pub fn open(state: &std::path::Path, computer: &str) -> Result<Self> {
        let path = state.join("session-access.json");
        let mut value: Self = match std::fs::read(&path) {
            Ok(bytes) => serde_json::from_slice(&bytes)?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Self::default(),
            Err(e) => return Err(e.into()),
        };
        if !value.computer_id.is_empty() && value.computer_id != computer {
            bail!("Session access belongs to a different computer");
        }
        value.computer_id = computer.into();
        if value.operations.len() > MAX_OPERATIONS {
            bail!("Too many persisted handoffs");
        }
        Ok(value)
    }
    fn key(store: &str, thread: &str) -> String {
        format!("{store}\0{thread}")
    }
    fn save(&self, state: &std::path::Path) -> Result<()> {
        storage::atomic_write(
            &state.join("session-access.json"),
            &serde_json::to_vec(self)?,
        )
    }
    pub fn wants_subscription(&self, runtime: &str, thread: &str) -> bool {
        self.watched.contains(&(runtime.into(), thread.into()))
            && !self.released.contains(&(runtime.into(), thread.into()))
    }
}

pub(super) fn validate(method: &str, params: &Value) -> Result<()> {
    let allowed: &[&str] = match method {
        "session/preferences/read" => &[],
        "session/preferences/update" => &["mode", "knownRevision", "commandId"],
        "runtime/ensure" => &["source", "commandId"],
        "thread/access/read" => &["runtimeId", "threadId"],
        "thread/access/update" => &["runtimeId", "threadId", "mode", "commandId"],
        "thread/access/cancel" => &["runtimeId", "threadId", "commandId"],
        _ => {
            return Err(classify(
                anyhow::anyhow!("Unknown session operation"),
                Code::Unsupported,
            ));
        }
    };
    let object = params.as_object().ok_or_else(|| {
        classify(
            anyhow::anyhow!("Invalid session request"),
            Code::InvalidParams,
        )
    })?;
    if object.keys().any(|k| !allowed.contains(&k.as_str())) {
        return Err(classify(
            anyhow::anyhow!("Unknown session field"),
            Code::InvalidParams,
        ));
    }
    if method.starts_with("thread/access/") {
        for name in ["threadId", "runtimeId"] {
            if name == "runtimeId" && method.ends_with("cancel") && !object.contains_key(name) {
                continue;
            }
            let value = object.get(name).and_then(Value::as_str).unwrap_or("");
            if value.is_empty() || value.len() > 512 || value.chars().any(char::is_control) {
                return Err(classify(
                    anyhow::anyhow!("Invalid native identity"),
                    Code::InvalidParams,
                ));
            }
        }
    }
    if method == "runtime/ensure" && params["source"] != "desktop" && params["source"] != "cli" {
        return Err(classify(
            anyhow::anyhow!("Invalid runtime source"),
            Code::InvalidParams,
        ));
    }
    if method.ends_with("update") {
        AccessMode::parse(&params["mode"])?;
    }
    Ok(())
}

impl Broker {
    pub(crate) async fn access_preferences(&self) -> Value {
        let access = self.access.lock().await;
        json!({"defaultMode":access.default_mode,"revision":access.revision})
    }

    pub(crate) async fn update_access_preferences(&self, p: &Value) -> Result<Value> {
        let mode = AccessMode::parse(&p["mode"])?;
        let mut access = self.access.lock().await;
        if p["knownRevision"].as_u64() != Some(access.revision) {
            return Err(classify(
                anyhow::anyhow!("Session preferences changed"),
                Code::VersionConflict,
            ));
        }
        let previous = access.default_mode;
        access.default_mode = mode;
        access.revision = access.revision.saturating_add(1);
        if let Err(e) = access.save(&self.state_dir) {
            access.default_mode = previous;
            access.revision = access.revision.saturating_sub(1);
            return Err(classify(e, Code::Rejected));
        }
        Ok(json!({"defaultMode":mode,"revision":access.revision}))
    }

    pub(super) async fn watch_cli(&self, runtime: &str, thread: &str) {
        let mut access = self.access.lock().await;
        if !access.released.contains(&(runtime.into(), thread.into())) {
            access.watched.insert((runtime.into(), thread.into()));
        }
    }

    pub(super) async fn access_snapshot(&self, p: &Value) -> Result<Value> {
        self.reconcile_access_outcomes().await?;
        let thread = field(p, "threadId")?;
        let runtime = field(p, "runtimeId")?;
        let fallback = {
            let access = self.access.lock().await;
            let mut matches = access.operations.values().filter(|op| {
                op.thread == thread
                    && (op.origin == runtime || op.target.as_deref() == Some(runtime))
            });
            let first = matches.next().map(|op| op.store.clone());
            if matches.next().is_none() {
                first
            } else {
                None
            }
        };
        let (store, source, owner, owner_source, online) = {
            let state = self.state.lock().await;
            let r = state.runtimes.get(runtime);
            let store = r.map(|r| r.store.clone()).or(fallback).ok_or_else(|| {
                classify(
                    anyhow::anyhow!("Runtime not available"),
                    Code::RuntimeOffline,
                )
            })?;
            let owner = self.directory(&state)?.resolve_write(&ThreadKey {
                computer_id: self.config.computer_id.clone(),
                config_store_id: store.clone(),
                native_thread_id: thread.into(),
            });
            let current = owner
                .as_ref()
                .ok()
                .and_then(|o| state.runtimes.get(&o.runtime_id));
            let owner_source = current.map(|r| r.source.clone());
            let online = current.is_some_and(|r| r.rpc.online() && r.inventory_ready);
            (
                store,
                r.map(|r| r.source.clone()).unwrap_or_default(),
                owner,
                owner_source,
                online,
            )
        };
        let access = self.access.lock().await;
        let key = AccessStore::key(&store, thread);
        let mode = access.modes.get(&key).copied().unwrap_or_else(|| {
            if owner_source.as_deref() == Some("cli") {
                AccessMode::Cli
            } else {
                access.default_mode
            }
        });
        let operation = access.operations.get(&key);
        let owner_id = owner.as_ref().ok().map(|o| o.runtime_id.as_str());
        let conflict = matches!(
            &owner,
            Err(ResolveError::AmbiguousOwners | ResolveError::UncertainOwner)
        );
        let mut phase = operation.map(|o| o.phase.as_str()).unwrap_or(if conflict {
            "blocked"
        } else if !online {
            "failed"
        } else {
            "ready"
        });
        if phase == "ready" && !online {
            phase = if conflict { "blocked" } else { "failed" };
        }
        let ready = phase == "ready"
            && !conflict
            && online
            && owner_source.as_deref() == Some(mode.source());
        let reason = operation.and_then(|o| o.reason.as_deref()).or(if conflict {
            Some("thread_owned_elsewhere")
        } else if !online {
            Some("runtime_offline")
        } else {
            None
        });
        Ok(
            json!({"threadId":thread,"runtimeId":owner_id.unwrap_or(runtime),"mode":mode,"ownerRuntimeId":owner_id,
            "source":owner_source.unwrap_or(source),"phase":phase,"canSend":ready,"reason":reason,
            "operationId":operation.map(|o|o.operation_id.as_str()),"canCancel":operation.is_some_and(AccessOperation::can_cancel)}),
        )
    }

    fn preferred_desktop(state: &State, store: Option<&str>) -> Option<String> {
        state
            .runtimes
            .iter()
            .filter(|(_, r)| {
                r.source == "desktop" && r.rpc.online() && store.is_none_or(|s| r.store == s)
            })
            .min_by_key(|(_, r)| {
                (
                    !r.version.contains("Codex Desktop"),
                    r.version.contains("browser-use"),
                )
            })
            .map(|(id, _)| id.clone())
    }

    pub(super) async fn ensure_runtime(&self, p: &Value) -> Result<Value> {
        let source = p["source"].as_str().unwrap_or("desktop");
        let existing = {
            let state = self.state.lock().await;
            if source == "desktop" {
                Self::preferred_desktop(&state, None)
            } else {
                state
                    .runtimes
                    .iter()
                    .find(|(_, r)| r.source == "cli" && r.rpc.online() && !matches!(r.role,crate::native::NativeRole::ManagedHost{..}))
                    .map(|(id, _)| id.clone())
            }
        };
        if let Some(id) = existing {
            return Ok(json!({"phase":"ready","runtimeId":id,"source":source}));
        }
        if source != "desktop" {
            return Err(classify(
                anyhow::anyhow!("Start the selected native CLI before attaching"),
                Code::RuntimeOffline,
            ));
        }
        self.launch_desktop_once().await?;
        Ok(json!({"phase":"starting_desktop","runtimeId":null,"source":"desktop"}))
    }

    async fn launch_desktop_once(&self) -> Result<()> {
        let mut launched = self.desktop_launch.lock().await;
        if launched.is_some_and(|at| storage::now().saturating_sub(at) < 30) {
            return Ok(());
        }
        #[cfg(windows)]
        {
            use sha2::{Digest, Sha256};
            use std::os::windows::process::CommandExt;
            // The state location must match an installed, integrity-checked stable launcher.
            // Neither the phone nor a native event supplies a path or command line.
            let root = self
                .state_dir
                .parent()
                .context("Missing installation root")?;
            let record: Value =
                serde_json::from_slice(&std::fs::read(root.join("desktop-install.json"))?)?;
            let launcher = root.join("pocket-launcher.exe");
            if record["stateDirectory"].as_str().map(std::path::Path::new)
                != Some(self.state_dir.as_path())
                || record["bridge"].as_str().map(std::path::Path::new) != Some(launcher.as_path())
                || record["launcherHash"].as_str()
                    != Some(format!("{:x}", Sha256::digest(std::fs::read(&launcher)?)).as_str())
            {
                return Err(classify(
                    anyhow::anyhow!("Desktop launcher installation is inconsistent"),
                    Code::RuntimeUnavailable,
                ));
            }
            let mut command = std::process::Command::new(launcher);
            command.arg("--pocket-desktop").creation_flags(0x08000000);
            command.spawn().map_err(|_| {
                classify(
                    anyhow::anyhow!("Desktop could not be started"),
                    Code::RuntimeUnavailable,
                )
            })?;
            *launched = Some(storage::now());
            Ok(())
        }
        #[cfg(not(windows))]
        {
            let _ = &mut launched;
            Err(classify(
                anyhow::anyhow!("Desktop launch requires Windows"),
                Code::Unsupported,
            ))
        }
    }

    pub(super) async fn update_access(&self, device: &str, p: &Value) -> Result<Value> {
        self.reconcile_access_outcomes().await?;
        let runtime = field(p, "runtimeId")?;
        let thread = field(p, "threadId")?;
        let mode = AccessMode::parse(&p["mode"])?;
        let (store, generation, rpc, source) = {
            let state = self.state.lock().await;
            let r = state
                .runtimes
                .get(runtime)
                .filter(|r| r.rpc.online())
                .ok_or_else(|| {
                    classify(anyhow::anyhow!("Runtime offline"), Code::RuntimeOffline)
                })?;
            (
                r.store.clone(),
                r.connection_id.clone(),
                r.rpc.clone(),
                r.source.clone(),
            )
        };
        let key = AccessStore::key(&store, thread);
        {
            let access = self.access.lock().await;
            if access
                .operations
                .get(&key)
                .is_some_and(|op| op.pending() || op.awaiting_outcome())
            {
                return Err(classify(
                    anyhow::anyhow!("Handoff already pending"),
                    Code::OperationInProgress,
                ));
            }
        }
        // A metadata-only native read proves identity and does not acquire a thread.
        let proof = tokio::time::timeout(
            Duration::from_secs(8),
            rpc.call(
                "thread/read",
                json!({"threadId":thread,"includeTurns":false}),
            ),
        )
        .await
        .map_err(|_| {
            classify(
                anyhow::anyhow!("Thread lookup timed out"),
                Code::RequestTimeout,
            )
        })??;
        if proof["thread"]["id"] != thread {
            return Err(classify(
                anyhow::anyhow!("Thread identity mismatch"),
                Code::InvalidParams,
            ));
        }
        self.check_generation(runtime, &generation).await?;
        let owner = {
            let state = self.state.lock().await;
            self.directory(&state)?.resolve_write(&ThreadKey {
                computer_id: self.config.computer_id.clone(),
                config_store_id: store.clone(),
                native_thread_id: thread.into(),
            })
        };
        if matches!(
            owner,
            Err(ResolveError::AmbiguousOwners | ResolveError::UncertainOwner)
        ) {
            return Err(classify(
                anyhow::anyhow!("Thread ownership is not resolved"),
                Code::ThreadOwnedElsewhere,
            ));
        }
        let origin = owner
            .as_ref()
            .ok()
            .map(|r| r.runtime_id.as_str())
            .unwrap_or(runtime);
        let (origin_generation, origin_source) = {
            let state = self.state.lock().await;
            let r = state.runtimes.get(origin).context("Owner missing")?;
            (r.connection_id.clone(), r.source.clone())
        };
        if mode == AccessMode::Cli && origin_source == "desktop" && owner.is_ok() {
            return Err(classify(
                anyhow::anyhow!("Use a separate CLI chat for independent execution"),
                Code::ThreadOwnedElsewhere,
            ));
        }
        let ready = owner.is_ok() && origin_source == mode.source();
        let op = AccessOperation {
            operation_id: uuid::Uuid::new_v4().to_string(),
            device: device.into(),
            store: store.clone(),
            thread: thread.into(),
            origin: origin.into(),
            origin_generation,
            target: if ready { Some(origin.into()) } else { None },
            mode,
            phase: if ready {
                "ready"
            } else if mode == AccessMode::Desktop {
                "starting_desktop"
            } else {
                "attaching_desktop"
            }
            .into(),
            reason: None,
            release_dispatched: false,
            attach_dispatched: false,
            release_confirmed: false,
            attach_confirmed: false,
            target_generation: None,
            cancelled: false,
            updated_at: storage::now(),
        };
        {
            let mut access = self.access.lock().await;
            if access
                .operations
                .get(&key)
                .is_some_and(|op| op.pending() || op.awaiting_outcome())
            {
                return Err(classify(
                    anyhow::anyhow!("Handoff already pending"),
                    Code::OperationInProgress,
                ));
            }
            let previous = access.clone();
            if access.operations.len() >= MAX_OPERATIONS && !access.operations.contains_key(&key) {
                if let Some(old) = access
                    .operations
                    .iter()
                    .filter(|(_, o)| !o.pending() && !o.awaiting_outcome())
                    .min_by_key(|(_, o)| o.updated_at)
                    .map(|(k, _)| k.clone())
                {
                    access.operations.remove(&old);
                } else {
                    return Err(classify(
                        anyhow::anyhow!("Too many handoffs"),
                        Code::OperationInProgress,
                    ));
                }
            }
            access.modes.insert(key.clone(), mode);
            access.operations.insert(key, op);
            if ready && source == "cli" {
                access.released.remove(&(runtime.into(), thread.into()));
                access.watched.insert((runtime.into(), thread.into()));
            }
            if let Err(error) = access.save(&self.state_dir) {
                *access = previous;
                return Err(error);
            }
        }
        self.access_snapshot(p).await
    }

    pub(super) async fn cancel_access(&self, device: &str, p: &Value) -> Result<Value> {
        let thread = field(p, "threadId")?;
        let runtime = p["runtimeId"].as_str();
        let store = {
            let state = self.state.lock().await;
            runtime
                .and_then(|id| state.runtimes.get(id))
                .map(|r| r.store.clone())
        };
        let mut access = self.access.lock().await;
        let matches: Vec<_> = access
            .operations
            .iter()
            .filter(|(_, o)| {
                o.thread == thread
                    && o.device == device
                    && o.can_cancel()
                    && runtime.is_none_or(|id| o.origin == id || o.target.as_deref() == Some(id))
                    && store.as_ref().is_none_or(|store| o.store == *store)
            })
            .map(|(k, _)| k.clone())
            .collect();
        if matches.len() != 1 {
            return Err(classify(
                anyhow::anyhow!("No unambiguous pending handoff"),
                Code::NotFound,
            ));
        }
        let previous = access.clone();
        let key = &matches[0];
        let op = access.operations.get_mut(key).unwrap();
        op.cancelled = true;
        op.phase = "cancelled".into();
        op.updated_at = storage::now();
        let result = json!({"threadId":thread,"operationId":op.operation_id,"phase":"cancelled","canSend":false,"reason":"refresh_owner_after_cancel"});
        if let Err(error) = access.save(&self.state_dir) {
            *access = previous;
            return Err(error);
        }
        Ok(result)
    }

    async fn access_phase(
        &self,
        op: &AccessOperation,
        phase: &str,
        reason: Option<&str>,
    ) -> Result<()> {
        let mut access = self.access.lock().await;
        let previous = access.clone();
        let key = AccessStore::key(&op.store, &op.thread);
        if let Some(current) = access
            .operations
            .get_mut(&key)
            .filter(|o| o.operation_id == op.operation_id && !o.cancelled)
        {
            let changed = current.phase != phase;
            current.phase = phase.into();
            current.reason = reason.map(str::to_owned);
            if changed {
                current.updated_at = storage::now();
            }
            if let Err(error) = access.save(&self.state_dir) {
                *access = previous;
                return Err(error);
            }
            let _=self.notifications.send(json!({"method":"thread/access/changed","params":{"threadId":op.thread,"runtimeId":op.origin,"phase":phase,"operationId":op.operation_id}}));
        }
        Ok(())
    }

    /// Inventory observations settle past dispatches; they never initiate a native request.
    async fn reconcile_access_outcomes(&self) -> Result<()> {
        let operations: Vec<_> = self
            .access
            .lock()
            .await
            .operations
            .values()
            .filter(|op| op.awaiting_outcome())
            .cloned()
            .collect();
        for op in operations {
            let (released, attached, target_generation) = {
                let state = self.state.lock().await;
                let owner = self.directory(&state)?.resolve_write(&ThreadKey {
                    computer_id: self.config.computer_id.clone(),
                    config_store_id: op.store.clone(),
                    native_thread_id: op.thread.clone(),
                });
                let released = matches!(&owner, Err(ResolveError::NoOnlineOwner))
                    || owner
                        .as_ref()
                        .is_ok_and(|owner| owner.runtime_id != op.origin);
                let attached = owner
                    .as_ref()
                    .ok()
                    .filter(|owner| op.target.as_deref() == Some(owner.runtime_id.as_str()))
                    .and_then(|owner| state.runtimes.get(&owner.runtime_id))
                    .filter(|r| {
                        r.rpc.online()
                            && r.inventory_ready
                            && r.store == op.store
                            && r.source == op.mode.source()
                    });
                (
                    released,
                    attached.is_some(),
                    attached.map(|r| r.connection_id.clone()),
                )
            };
            let mut access = self.access.lock().await;
            let previous = access.clone();
            let Some(current) = access
                .operations
                .get_mut(&AccessStore::key(&op.store, &op.thread))
                .filter(|current| current.operation_id == op.operation_id)
            else {
                continue;
            };
            let mut changed = false;
            if released && current.release_dispatched && !current.release_confirmed {
                current.release_confirmed = true;
                changed = true;
            }
            if attached && current.attach_dispatched && !current.attach_confirmed {
                current.attach_confirmed = true;
                current.target_generation = target_generation;
                if !current.cancelled {
                    current.phase = "ready".into();
                    current.reason = None;
                }
                changed = true;
            }
            if changed {
                current.updated_at = storage::now();
                let phase = current.phase.clone();
                if let Err(error) = access.save(&self.state_dir) {
                    *access = previous;
                    return Err(error);
                }
                let _ = self.notifications.send(json!({"method":"thread/access/changed","params":{
                    "threadId":op.thread,"runtimeId":op.origin,"phase":phase,"operationId":op.operation_id}}));
            }
        }
        Ok(())
    }

    pub(super) async fn drive_access_operations(&self) {
        let Ok(_drive) = self.access_drive.try_lock() else {
            return;
        };
        if self.reconcile_access_outcomes().await.is_err() {
            return;
        }
        let operations: Vec<_> = self
            .access
            .lock()
            .await
            .operations
            .values()
            .filter(|o| o.pending())
            .cloned()
            .collect();
        for op in operations {
            if !self.device_exists(&op.device).await {
                let _ = self
                    .access_phase(&op, "blocked", Some("device_revoked"))
                    .await;
                continue;
            }
            if let Err(error) = self.drive_access_operation(&op).await {
                let reason = crate::error::classification(&error)
                    .unwrap_or(Code::Unknown)
                    .as_str();
                let _ = self.access_phase(&op, "blocked", Some(reason)).await;
            }
        }
    }

    async fn drive_access_operation(&self, op: &AccessOperation) -> Result<()> {
        let current = self
            .access
            .lock()
            .await
            .operations
            .get(&AccessStore::key(&op.store, &op.thread))
            .filter(|current| current.operation_id == op.operation_id && current.pending())
            .cloned();
        let Some(current) = current else {
            return Ok(());
        };
        let op = &current;
        if !self.device_exists(&op.device).await {
            return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
        }
        let (owner, target) = {
            let state = self.state.lock().await;
            let owner = self.directory(&state)?.resolve_write(&ThreadKey {
                computer_id: self.config.computer_id.clone(),
                config_store_id: op.store.clone(),
                native_thread_id: op.thread.clone(),
            });
            let target = if op.mode == AccessMode::Desktop {
                Self::preferred_desktop(&state, Some(&op.store))
            } else {
                state
                    .runtimes
                    .get(&op.origin)
                    .filter(|r| r.source == "cli" && r.rpc.online())
                    .map(|_| op.origin.clone())
            };
            (owner, target)
        };
        let Some(target) = target else {
            if op.mode == AccessMode::Desktop {
                self.launch_desktop_once().await?;
                self.access_phase(op, "starting_desktop", None).await?;
                return Ok(());
            }
            return Err(classify(
                anyhow::anyhow!("CLI is offline"),
                Code::RuntimeOffline,
            ));
        };
        if owner.as_ref().is_ok_and(|o| o.runtime_id == target) {
            self.access_phase(op, "ready", None).await?;
            return Ok(());
        }
        if matches!(
            &owner,
            Err(ResolveError::AmbiguousOwners | ResolveError::UncertainOwner)
        ) {
            return Err(classify(
                anyhow::anyhow!("Owner is uncertain"),
                Code::ThreadOwnedElsewhere,
            ));
        }
        if let Ok(owner) = &owner {
            if owner.runtime_id != op.origin {
                return Err(classify(
                    anyhow::anyhow!("Owner changed during handoff"),
                    Code::ThreadOwnedElsewhere,
                ));
            }
            let (rpc, generation, can_release) = {
                let state = self.state.lock().await;
                let r = state
                    .runtimes
                    .get(&op.origin)
                    .context("Origin runtime absent")?;
                (
                    r.rpc.clone(),
                    r.connection_id.clone(),
                    r.source == "cli"
                        && r.capabilities
                            .supports("thread/unsubscribe", &json!({"threadId":op.thread})),
                )
            };
            if generation != op.origin_generation {
                return Err(classify(
                    anyhow::anyhow!("Origin generation changed"),
                    Code::Unknown,
                ));
            }
            if op.release_dispatched {
                let phase = if storage::now().saturating_sub(op.updated_at) > 120 {
                    "blocked"
                } else {
                    "waiting_release"
                };
                self.access_phase(op, phase, Some("native_owner_has_not_released"))
                    .await?;
                return Ok(());
            }
            if !can_release {
                return Err(classify(
                    anyhow::anyhow!("Runtime cannot release the Pocket subscription"),
                    Code::Unsupported,
                ));
            }
            let value = tokio::time::timeout(
                Duration::from_secs(8),
                rpc.call(
                    "thread/read",
                    json!({"threadId":op.thread,"includeTurns":false}),
                ),
            )
            .await
            .map_err(|_| {
                classify(
                    anyhow::anyhow!("Owner read timed out"),
                    Code::RequestTimeout,
                )
            })??;
            if value["thread"]["id"] != op.thread {
                return Err(classify(
                    anyhow::anyhow!("Owner read mismatch"),
                    Code::InvalidParams,
                ));
            }
            let status = &value["thread"]["status"];
            let kind = status["type"].as_str().or(status.as_str()).unwrap_or("");
            if kind == "active" || kind == "inProgress" {
                self.access_phase(op, "waiting_idle", None).await?;
                return Ok(());
            }
            if !matches!(
                kind,
                "idle" | "notLoaded" | "completed" | "failed" | "interrupted"
            ) {
                return Err(classify(
                    anyhow::anyhow!("Native idle state is not confirmed"),
                    Code::OperationInProgress,
                ));
            }
            {
                let _write = self.writes.lock().await;
                self.check_generation(&op.origin, &generation).await?;
                {
                    let state = self.state.lock().await;
                    if !state.devices.devices.contains_key(&op.device) {
                        return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
                    }
                    if !self
                        .directory(&state)?
                        .resolve_write(&ThreadKey {
                            computer_id: self.config.computer_id.clone(),
                            config_store_id: op.store.clone(),
                            native_thread_id: op.thread.clone(),
                        })
                        .is_ok_and(|owner| owner.runtime_id == op.origin)
                    {
                        return Err(classify(
                            anyhow::anyhow!("Ownership changed before release"),
                            Code::ThreadOwnedElsewhere,
                        ));
                    }
                    if state.pending.values().any(|pending| {
                        pending.runtime == op.origin
                            && pending.event["params"]["threadId"] == op.thread
                    }) {
                        return Err(classify(
                            anyhow::anyhow!("Thread has a pending native request"),
                            Code::OperationInProgress,
                        ));
                    }
                }
                let mut access = self.access.lock().await;
                let key = AccessStore::key(&op.store, &op.thread);
                let previous = access.clone();
                let Some(current) = access.operations.get_mut(&key).filter(|o| {
                    o.operation_id == op.operation_id && !o.cancelled && !o.release_dispatched
                }) else {
                    return Ok(());
                };
                current.release_dispatched = true;
                current.phase = "releasing_cli".into();
                current.target = Some(target.clone());
                access
                    .released
                    .insert((op.origin.clone(), op.thread.clone()));
                access
                    .watched
                    .remove(&(op.origin.clone(), op.thread.clone()));
                if let Err(error) = access.save(&self.state_dir) {
                    *access = previous;
                    return Err(error);
                }
            }
            if !rpc.online() {
                return Err(classify(
                    anyhow::anyhow!("Release connection changed"),
                    Code::Unknown,
                ));
            }
            let result = tokio::time::timeout(
                Duration::from_secs(15),
                rpc.call("thread/unsubscribe", json!({"threadId":op.thread})),
            )
            .await;
            match result {
                Ok(Ok(v))
                    if matches!(
                        v["status"].as_str(),
                        Some("unsubscribed" | "notSubscribed" | "notLoaded")
                    ) =>
                {
                    self.access_phase(op, "waiting_release", None).await?
                }
                // Dispatch may already have happened. Keep suppression and require readback.
                _ => {
                    self.access_phase(op, "waiting_release", Some("release_result_unknown"))
                        .await?
                }
            }
            return Ok(());
        }
        if !matches!(owner, Err(ResolveError::NoOnlineOwner)) {
            return Err(classify(
                anyhow::anyhow!("No confirmed handoff owner"),
                Code::ThreadOwnedElsewhere,
            ));
        }
        if op.attach_dispatched {
            return Err(classify(
                anyhow::anyhow!("Resume result unknown; inspect native ownership"),
                Code::Unknown,
            ));
        }
        let (rpc, generation) = {
            let state = self.state.lock().await;
            let r = state
                .runtimes
                .get(&target)
                .filter(|r| r.rpc.online())
                .context("Target unavailable")?;
            (r.rpc.clone(), r.connection_id.clone())
        };
        let proof = tokio::time::timeout(
            Duration::from_secs(8),
            rpc.call(
                "thread/read",
                json!({"threadId":op.thread,"includeTurns":false}),
            ),
        )
        .await
        .map_err(|_| {
            classify(
                anyhow::anyhow!("Target read timed out"),
                Code::RequestTimeout,
            )
        })??;
        if proof["thread"]["id"] != op.thread {
            return Err(classify(
                anyhow::anyhow!("Target thread mismatch"),
                Code::InvalidParams,
            ));
        }
        self.check_generation(&target, &generation).await?;
        {
            let _write = self.writes.lock().await;
            self.check_generation(&target, &generation).await?;
            {
                let state = self.state.lock().await;
                if !state.devices.devices.contains_key(&op.device) {
                    return Err(classify(anyhow::anyhow!("Device revoked"), Code::Revoked));
                }
                if !matches!(
                    self.directory(&state)?.resolve_write(&ThreadKey {
                        computer_id: self.config.computer_id.clone(),
                        config_store_id: op.store.clone(),
                        native_thread_id: op.thread.clone()
                    }),
                    Err(ResolveError::NoOnlineOwner)
                ) {
                    return Err(classify(
                        anyhow::anyhow!("Ownership changed before attach"),
                        Code::ThreadOwnedElsewhere,
                    ));
                }
            }
            let mut access = self.access.lock().await;
            let previous = access.clone();
            let Some(current) = access
                .operations
                .get_mut(&AccessStore::key(&op.store, &op.thread))
                .filter(|o| {
                    o.operation_id == op.operation_id && !o.cancelled && !o.attach_dispatched
                })
            else {
                return Ok(());
            };
            current.attach_dispatched = true;
            current.phase = "attaching_desktop".into();
            current.target = Some(target.clone());
            current.target_generation = Some(generation.clone());
            if current.release_dispatched {
                current.release_confirmed = true;
            }
            if let Err(error) = access.save(&self.state_dir) {
                *access = previous;
                return Err(error);
            }
        }
        if !rpc.online() {
            return Err(classify(
                anyhow::anyhow!("Attach connection changed"),
                Code::Unknown,
            ));
        }
        let reply = tokio::time::timeout(
            Duration::from_secs(15),
            rpc.call(
                "thread/resume",
                json!({"threadId":op.thread,"excludeTurns":true}),
            ),
        )
        .await;
        match reply {
            Ok(Ok(v)) if v["thread"]["id"] == op.thread => {
                let mut state = self.state.lock().await;
                let Some(r) = state
                    .runtimes
                    .get_mut(&target)
                    .filter(|r| r.connection_id == generation)
                else {
                    return Err(classify(
                        anyhow::anyhow!("Attach target generation changed"),
                        Code::Unknown,
                    ));
                };
                let online = r.rpc.online();
                r.loaded.insert(op.thread.clone());
                r.inventory_revision = uuid::Uuid::new_v4();
                if r.source == "cli" {
                    r.subscribed.insert(op.thread.clone());
                }
                self.save_ownership(&mut state)?;
                drop(state);
                if !online {
                    return Err(classify(
                        anyhow::anyhow!("Attach target disconnected"),
                        Code::Unknown,
                    ));
                }
                let mut access = self.access.lock().await;
                let previous = access.clone();
                if let Some(current) = access
                    .operations
                    .get_mut(&AccessStore::key(&op.store, &op.thread))
                    .filter(|current| current.operation_id == op.operation_id)
                {
                    current.attach_confirmed = true;
                    if current.release_dispatched {
                        current.release_confirmed = true;
                    }
                    if !current.cancelled {
                        current.phase = "ready".into();
                        current.reason = None;
                    }
                    current.updated_at = storage::now();
                    if op.mode == AccessMode::Cli && !current.cancelled {
                        access.released.remove(&(target.clone(), op.thread.clone()));
                        access.watched.insert((target.clone(), op.thread.clone()));
                    }
                    if let Err(error) = access.save(&self.state_dir) {
                        *access = previous;
                        return Err(error);
                    }
                    let phase = access.operations[&AccessStore::key(&op.store, &op.thread)]
                        .phase
                        .clone();
                    let _ = self.notifications.send(json!({"method":"thread/access/changed","params":{
                        "threadId":op.thread,"runtimeId":op.origin,"phase":phase,"operationId":op.operation_id}}));
                }
            }
            _ => {
                self.access_phase(op, "attaching_desktop", Some("attach_result_unknown"))
                    .await?
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::broker::tests::{fixture, loaded, peer};
    use crate::native::test_peer::Peer;

    async fn handoff_fixture(owned: bool) -> (tempfile::TempDir, Arc<Broker>, Peer, Peer, String) {
        let (dir, b) = fixture().await;
        let cli = peer(&b, "c", "cli").await;
        let desktop = peer(&b, "d", "desktop").await;
        let (store, generation) = {
            let mut state = b.state.lock().await;
            let cli = state.runtimes.get_mut("c").unwrap();
            if owned {
                cli.loaded.insert("t".into());
                cli.subscribed.insert("t".into());
            }
            cli.inventory_ready = true;
            cli.capabilities.0.insert(
                "thread/unsubscribe".into(),
                ["threadId".into()].into_iter().collect(),
            );
            let result = (cli.store.clone(), cli.connection_id.clone());
            state.runtimes.get_mut("d").unwrap().inventory_ready = true;
            b.save_ownership(&mut state).unwrap();
            result
        };
        let key = AccessStore::key(&store, "t");
        let op = AccessOperation {
            operation_id: "handoff".into(),
            device: "phone".into(),
            store,
            thread: "t".into(),
            origin: "c".into(),
            origin_generation: generation,
            target: None,
            mode: AccessMode::Desktop,
            phase: "waiting_idle".into(),
            reason: None,
            release_dispatched: false,
            attach_dispatched: false,
            release_confirmed: false,
            attach_confirmed: false,
            target_generation: None,
            cancelled: false,
            updated_at: storage::now(),
        };
        {
            let mut access = b.access.lock().await;
            access.operations.insert(key.clone(), op);
            access.watched.insert(("c".into(), "t".into()));
            access.save(&b.state_dir).unwrap();
        }
        (dir, b, cli, desktop, key)
    }
    async fn drive(b: &Arc<Broker>, key: &str) -> tokio::task::JoinHandle<Result<()>> {
        let op = b.access.lock().await.operations[key].clone();
        tokio::spawn({
            let b = b.clone();
            async move { b.drive_access_operation(&op).await }
        })
    }
    async fn idle(cli: &mut Peer) {
        let read = cli.recv().await;
        assert_eq!(read["method"], "thread/read");
        assert_eq!(read["params"], json!({"threadId":"t","includeTurns":false}));
        cli.reply(&read, json!({"thread":{"id":"t","status":{"type":"idle"}}}))
            .await;
    }
    async fn attach(desktop: &mut Peer) -> Value {
        let read = desktop.recv().await;
        assert_eq!(read["method"], "thread/read");
        desktop.reply(&read, json!({"thread":{"id":"t"}})).await;
        let resume = desktop.recv().await;
        assert_eq!(resume["method"], "thread/resume");
        assert_eq!(
            resume["params"],
            json!({"threadId":"t","excludeTurns":true})
        );
        resume
    }
    async fn cannot_replace_unknown(b: &Arc<Broker>, cli: &mut Peer) {
        let replacement = tokio::time::timeout(
            Duration::from_millis(200),
            b.update_access(
                "phone",
                &json!({"runtimeId":"c","threadId":"t","mode":"desktop"}),
            ),
        )
        .await;
        assert!(
            replacement.is_ok(),
            "Unknown dispatch must reject replacement without another native request"
        );
        assert_eq!(
            crate::error::code(&replacement.unwrap().unwrap_err()),
            Code::OperationInProgress
        );
        cli.assert_idle().await;
    }
    #[tokio::test]
    async fn cancellation_while_native_turn_is_running_never_releases_or_attaches() {
        let (_dir, b, mut cli, mut desktop, key) = handoff_fixture(true).await;
        let task = drive(&b, &key).await;
        let read = cli.recv().await;
        cli.reply(
            &read,
            json!({"thread":{"id":"t","status":{"type":"active"}}}),
        )
        .await;
        task.await.unwrap().unwrap();
        assert_eq!(b.access.lock().await.operations[&key].phase, "waiting_idle");
        b.request(
            "phone",
            "thread/access/cancel",
            json!({"runtimeId":"c","threadId":"t","commandId":"cancel-running"}),
        )
        .await
        .unwrap();
        b.drive_access_operations().await;
        let access = b.access.lock().await;
        assert_eq!(access.operations[&key].phase, "cancelled");
        assert!(!access.operations[&key].release_dispatched);
        assert!(!access.operations[&key].attach_dispatched);
        drop(access);
        cli.assert_idle().await;
        desktop.assert_idle().await;
    }
    #[tokio::test]
    async fn cancellation_during_metadata_read_prevents_release_and_attach_dispatch() {
        for owned in [true, false] {
            let (_dir, b, mut cli, mut desktop, key) = handoff_fixture(owned).await;
            let task = drive(&b, &key).await;
            let read = if owned {
                cli.recv().await
            } else {
                desktop.recv().await
            };
            assert_eq!(read["method"], "thread/read");
            b.request(
                "phone",
                "thread/access/cancel",
                json!({"runtimeId":"c","threadId":"t","commandId":"cancel-before-dispatch"}),
            )
            .await
            .unwrap();
            let proof = json!({"thread":{"id":"t","status":{"type":"idle"}}});
            if owned {
                cli.reply(&read, proof).await;
            } else {
                desktop.reply(&read, proof).await;
            }
            task.await.unwrap().unwrap();
            let access = b.access.lock().await;
            let op = &access.operations[&key];
            assert_eq!(op.phase, "cancelled");
            assert!(!op.release_dispatched);
            assert!(!op.attach_dispatched);
            drop(access);
            b.drive_access_operations().await;
            cli.assert_idle().await;
            desktop.assert_idle().await;
        }
    }
    #[tokio::test]
    async fn cancellation_during_release_is_prompt_and_unknown_outcome_stays_fenced() {
        let (_dir, b, mut cli, mut desktop, key) = handoff_fixture(true).await;
        let task = drive(&b, &key).await;
        idle(&mut cli).await;
        let release = cli.recv().await;
        assert_eq!(release["method"], "thread/unsubscribe");
        let mut cancel = tokio::spawn({
            let b = b.clone();
            async move {
                b.request(
                    "phone",
                    "thread/access/cancel",
                    json!({"runtimeId":"c","threadId":"t","commandId":"cancel-release"}),
                )
                .await
            }
        });
        let cancelled = tokio::time::timeout(Duration::from_millis(300), &mut cancel).await;
        if cancelled.is_err() {
            cancel.abort();
            cli.reply(&release, json!({"status":"unsubscribed"})).await;
            let _ = task.await;
            panic!("Cancellation must not wait for the running native release reply");
        }
        assert_eq!(cancelled.unwrap().unwrap().unwrap()["phase"], "cancelled");
        cli.reply(&release, json!({"status":"unknown"})).await;
        task.await.unwrap().unwrap();
        assert_eq!(b.access.lock().await.operations[&key].phase, "cancelled");
        assert!(!b.access.lock().await.wants_subscription("c", "t"));
        cannot_replace_unknown(&b, &mut cli).await;
        b.drive_access_operations().await;
        desktop.assert_idle().await;
        cli.assert_idle().await;
        let restored = AccessStore::open(&b.state_dir, &b.config.computer_id).unwrap();
        assert!(restored.operations[&key].release_dispatched);
        assert!(restored.operations[&key].cancelled);
    }
    #[tokio::test]
    async fn unknown_release_requires_authoritative_inventory_before_one_attach() {
        let (_dir, b, mut cli, mut desktop, key) = handoff_fixture(true).await;
        let task = drive(&b, &key).await;
        idle(&mut cli).await;
        let release = cli.recv().await;
        cli.reply(&release, json!({"status":"unknown"})).await;
        task.await.unwrap().unwrap();
        b.drive_access_operations().await;
        desktop.assert_idle().await;
        cli.assert_idle().await;
        {
            let mut state = b.state.lock().await;
            state.runtimes.get_mut("c").unwrap().loaded.clear();
            b.save_ownership(&mut state).unwrap();
        }
        let task = drive(&b, &key).await;
        let resume = attach(&mut desktop).await;
        desktop.reply(&resume, json!({"thread":{"id":"t"}})).await;
        task.await.unwrap().unwrap();
        b.drive_access_operations().await;
        desktop.assert_idle().await;
        cli.assert_idle().await;
        assert_eq!(b.access.lock().await.operations[&key].phase, "ready");
    }
    #[tokio::test]
    async fn cancelled_unknown_attach_cannot_be_replaced_or_attached_twice() {
        let (_dir, b, mut cli, mut desktop, key) = handoff_fixture(false).await;
        let task = drive(&b, &key).await;
        let resume = attach(&mut desktop).await;
        // Directly exercise cancellation while the native resume is already in flight.
        b.cancel_access("phone", &json!({"runtimeId":"c","threadId":"t"}))
            .await
            .unwrap();
        desktop
            .reply(&resume, json!({"thread":{"id":"wrong"}}))
            .await;
        task.await.unwrap().unwrap();
        cannot_replace_unknown(&b, &mut cli).await;
        b.drive_access_operations().await;
        desktop.assert_idle().await;
        let restored = AccessStore::open(&b.state_dir, &b.config.computer_id).unwrap();
        assert!(restored.operations[&key].attach_dispatched);
        assert!(restored.operations[&key].cancelled);
    }
    #[tokio::test]
    async fn blocked_unknown_attach_does_not_allow_a_fresh_duplicate_operation() {
        let (_dir, b, mut cli, mut desktop, key) = handoff_fixture(false).await;
        let task = drive(&b, &key).await;
        let resume = attach(&mut desktop).await;
        desktop
            .reply(&resume, json!({"thread":{"id":"wrong"}}))
            .await;
        task.await.unwrap().unwrap();
        b.drive_access_operations().await;
        assert_eq!(b.access.lock().await.operations[&key].phase, "blocked");
        cannot_replace_unknown(&b, &mut cli).await;
        desktop.assert_idle().await;
    }
    #[tokio::test]
    async fn cancelled_successful_attach_preserves_truthful_owner_without_replay() {
        let (_dir, b, mut cli, mut desktop, key) = handoff_fixture(false).await;
        let task = drive(&b, &key).await;
        let resume = attach(&mut desktop).await;
        let cancel = tokio::time::timeout(
            Duration::from_secs(1),
            b.request(
                "phone",
                "thread/access/cancel",
                json!({"runtimeId":"c","threadId":"t","commandId":"cancel-attach"}),
            ),
        )
        .await
        .expect("Cancellation must not wait for the native attach reply")
        .unwrap();
        assert_eq!(cancel["phase"], "cancelled");
        desktop.reply(&resume, json!({"thread":{"id":"t"}})).await;
        task.await.unwrap().unwrap();
        let snapshot = b
            .access_snapshot(&json!({"runtimeId":"c","threadId":"t"}))
            .await
            .unwrap();
        assert_eq!(snapshot["phase"], "cancelled");
        assert_eq!(snapshot["ownerRuntimeId"], "d");
        assert_eq!(snapshot["canSend"], false);
        assert_eq!(snapshot["canCancel"], false);
        let restored = AccessStore::open(&b.state_dir, &b.config.computer_id).unwrap();
        assert!(restored.operations[&key].attach_confirmed);
        assert!(!restored.operations[&key].awaiting_outcome());
        b.drive_access_operations().await;
        cli.assert_idle().await;
        desktop.assert_idle().await;
    }
    #[tokio::test]
    async fn unknown_release_keeps_offline_origin_claim_and_never_attaches() {
        let (_dir, b, mut cli, mut desktop, key) = handoff_fixture(true).await;
        let task = drive(&b, &key).await;
        idle(&mut cli).await;
        let release = cli.recv().await;
        assert_eq!(release["method"], "thread/unsubscribe");
        cli.reply(&release, json!({"status":"unknown"})).await;
        task.await.unwrap().unwrap();
        cli.offline();
        b.state.lock().await.runtimes.remove("c");
        b.drive_access_operations().await;
        let access = b.access.lock().await;
        let op = &access.operations[&key];
        assert_eq!(op.phase, "blocked");
        assert!(!op.release_confirmed);
        assert!(op.awaiting_outcome());
        assert!(!op.attach_dispatched);
        drop(access);
        desktop.assert_idle().await;
        let snapshot = b
            .access_snapshot(&json!({"runtimeId":"c","threadId":"t"}))
            .await
            .unwrap();
        assert_eq!(snapshot["phase"], "blocked");
        assert_eq!(snapshot["canSend"], false);
    }
    #[tokio::test]
    async fn cancelled_unknown_dispatches_survive_reload_and_operation_capacity() {
        let (_dir, b, mut cli, mut desktop, key) = handoff_fixture(false).await;
        {
            let mut access = b.access.lock().await;
            let mut op = access.operations.remove(&key).unwrap();
            op.phase = "cancelled".into();
            op.cancelled = true;
            op.attach_dispatched = true;
            op.target = Some("d".into());
            for index in 0..MAX_OPERATIONS {
                op.thread = format!("unknown-{index}");
                op.operation_id = format!("op-{index}");
                let key = AccessStore::key(&op.store, &op.thread);
                access.operations.insert(key, op.clone());
            }
            access.save(&b.state_dir).unwrap();
            *access = AccessStore::open(&b.state_dir, &b.config.computer_id).unwrap();
        }
        let update = tokio::spawn({
            let b = b.clone();
            async move {
                b.update_access(
                    "phone",
                    &json!({"runtimeId":"c","threadId":"new-thread","mode":"desktop"}),
                )
                .await
            }
        });
        let proof = cli.recv().await;
        assert_eq!(proof["method"], "thread/read");
        cli.reply(&proof, json!({"thread":{"id":"new-thread"}}))
            .await;
        assert_eq!(
            crate::error::code(&update.await.unwrap().unwrap_err()),
            Code::OperationInProgress
        );
        let access = b.access.lock().await;
        assert_eq!(access.operations.len(), MAX_OPERATIONS);
        assert!(access.operations.values().all(|op| op.awaiting_outcome()));
        drop(access);
        b.drive_access_operations().await;
        cli.assert_idle().await;
        desktop.assert_idle().await;
    }
    #[tokio::test]
    async fn persistence_failure_prevents_release_and_rolls_back_cancellation() {
        let (_dir, b, mut cli, mut desktop, key) = handoff_fixture(true).await;
        let task = drive(&b, &key).await;
        let read = cli.recv().await;
        assert_eq!(read["method"], "thread/read");
        let path = b.state_dir.join("session-access.json");
        let saved = b.state_dir.join("session-access.saved.json");
        std::fs::rename(&path, &saved).unwrap();
        std::fs::create_dir(&path).unwrap();
        assert!(
            b.cancel_access("phone", &json!({"runtimeId":"c","threadId":"t"}))
                .await
                .is_err()
        );
        assert!(!b.access.lock().await.operations[&key].cancelled);
        cli.reply(&read, json!({"thread":{"id":"t","status":{"type":"idle"}}}))
            .await;
        assert!(task.await.unwrap().is_err());
        let access = b.access.lock().await;
        assert!(!access.operations[&key].release_dispatched);
        assert!(access.wants_subscription("c", "t"));
        drop(access);
        cli.assert_idle().await;
        desktop.assert_idle().await;
        std::fs::remove_dir(&path).unwrap();
        std::fs::rename(&saved, &path).unwrap();
        let restored = AccessStore::open(&b.state_dir, &b.config.computer_id).unwrap();
        assert!(!restored.operations[&key].cancelled);
        assert!(!restored.operations[&key].release_dispatched);
    }
    #[tokio::test]
    async fn successful_handoff_remains_ready_when_origin_is_offline_or_removed() {
        for removed in [false, true] {
            let (_dir, b, mut cli, mut desktop, key) = handoff_fixture(true).await;
            let task = drive(&b, &key).await;
            idle(&mut cli).await;
            let release = cli.recv().await;
            assert_eq!(release["method"], "thread/unsubscribe");
            cli.reply(&release, json!({"status":"unsubscribed"})).await;
            task.await.unwrap().unwrap();
            {
                let mut state = b.state.lock().await;
                state.runtimes.get_mut("c").unwrap().loaded.clear();
                b.save_ownership(&mut state).unwrap();
            }
            let task = drive(&b, &key).await;
            let resume = attach(&mut desktop).await;
            desktop.reply(&resume, json!({"thread":{"id":"t"}})).await;
            task.await.unwrap().unwrap();
            cli.offline();
            if removed {
                b.state.lock().await.runtimes.remove("c");
            }
            let snapshot = b
                .access_snapshot(&json!({"runtimeId":"c","threadId":"t"}))
                .await
                .unwrap();
            assert_eq!(snapshot["phase"], "ready");
            assert_eq!(snapshot["ownerRuntimeId"], "d");
            assert_eq!(snapshot["runtimeId"], "d");
            assert_eq!(snapshot["canSend"], true);
            desktop.assert_idle().await;
        }
    }
    #[tokio::test]
    async fn late_resume_reply_cannot_mark_a_replacement_target_ready() {
        let (_dir, b, _cli, mut desktop, key) = handoff_fixture(false).await;
        let task = drive(&b, &key).await;
        let resume = attach(&mut desktop).await;
        desktop.offline();
        let mut replacement = peer(&b, "d", "desktop").await;
        desktop.reply(&resume, json!({"thread":{"id":"t"}})).await;
        assert!(task.await.unwrap().is_err());
        assert_ne!(b.access.lock().await.operations[&key].phase, "ready");
        assert!(!b.state.lock().await.runtimes["d"].loaded.contains("t"));
        replacement.assert_idle().await;
    }
    #[tokio::test]
    async fn directory_refresh_does_not_acquire_every_loaded_cli_thread() {
        let (_dir, b) = fixture().await;
        let mut cli = peer(&b, "cli", "cli").await;
        let task = tokio::spawn({
            let b = b.clone();
            async move { b.refresh_loaded().await }
        });
        loaded(&mut cli, &["already-running-in-terminal"]).await;
        tokio::time::timeout(Duration::from_secs(2), task)
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        cli.assert_idle().await;
        assert!(b.state.lock().await.runtimes["cli"].subscribed.is_empty());
    }
    #[tokio::test]
    async fn preferences_revision_and_command_dedup_preserve_selection() {
        let (_dir, b) = fixture().await;
        let initial = b
            .request("phone", "session/preferences/read", json!({}))
            .await
            .unwrap();
        assert_eq!(initial["defaultMode"], "desktop");
        let params = json!({"commandId":"pref","mode":"cli","knownRevision":initial["revision"]});
        let first = b
            .request("phone", "session/preferences/update", params.clone())
            .await
            .unwrap();
        let duplicate = b
            .request("phone", "session/preferences/update", params)
            .await
            .unwrap();
        assert_eq!(first, duplicate);
        let stale = b
            .request(
                "phone",
                "session/preferences/update",
                json!({"commandId":"stale","mode":"desktop","knownRevision":0}),
            )
            .await
            .unwrap_err();
        assert_eq!(crate::error::code(&stale), Code::VersionConflict);
        assert_eq!(b.access_preferences().await["defaultMode"], "cli");
    }
    #[tokio::test]
    async fn handoff_waits_for_native_release_and_never_resubscribes() {
        let (_dir, b) = fixture().await;
        let mut cli = peer(&b, "c", "cli").await;
        let mut desktop = peer(&b, "d", "desktop").await;
        let (store, generation) = {
            let mut s = b.state.lock().await;
            let r = s.runtimes.get_mut("c").unwrap();
            r.loaded.insert("t".into());
            r.subscribed.insert("t".into());
            r.inventory_ready = true;
            r.capabilities.0.insert(
                "thread/unsubscribe".into(),
                ["threadId".into()].into_iter().collect(),
            );
            (r.store.clone(), r.connection_id.clone())
        };
        b.state
            .lock()
            .await
            .runtimes
            .get_mut("d")
            .unwrap()
            .inventory_ready = true;
        let op = AccessOperation {
            operation_id: "op".into(),
            device: "phone".into(),
            store: store.clone(),
            thread: "t".into(),
            origin: "c".into(),
            origin_generation: generation,
            target: None,
            mode: AccessMode::Desktop,
            phase: "waiting_idle".into(),
            reason: None,
            release_dispatched: false,
            attach_dispatched: false,
            release_confirmed: false,
            attach_confirmed: false,
            target_generation: None,
            cancelled: false,
            updated_at: storage::now(),
        };
        let key = AccessStore::key(&store, "t");
        b.access
            .lock()
            .await
            .operations
            .insert(key.clone(), op.clone());
        let drive = tokio::spawn({
            let b = b.clone();
            let op = op.clone();
            async move { b.drive_access_operation(&op).await }
        });
        let proof = cli.recv().await;
        assert_eq!(proof["method"], "thread/read");
        cli.reply(
            &proof,
            json!({"thread":{"id":"t","status":{"type":"idle"}}}),
        )
        .await;
        let release = cli.recv().await;
        assert_eq!(release["method"], "thread/unsubscribe");
        cli.reply(&release, json!({"status":"unsubscribed"})).await;
        drive.await.unwrap().unwrap();
        assert!(!b.access.lock().await.wants_subscription("c", "t"));
        let waiting = b.access.lock().await.operations[&key].clone();
        b.drive_access_operation(&waiting).await.unwrap();
        desktop.assert_idle().await;
        cli.assert_idle().await;
        assert_eq!(
            b.access.lock().await.operations[&key].phase,
            "waiting_release"
        );
        // Only authoritative release permits target resume.
        {
            let mut s = b.state.lock().await;
            s.runtimes.get_mut("c").unwrap().loaded.clear();
            b.save_ownership(&mut s).unwrap();
        }
        let drive = tokio::spawn({
            let b = b.clone();
            let op = waiting.clone();
            async move { b.drive_access_operation(&op).await }
        });
        let proof = desktop.recv().await;
        assert_eq!(proof["method"], "thread/read");
        desktop.reply(&proof, json!({"thread":{"id":"t"}})).await;
        let resume = desktop.recv().await;
        assert_eq!(resume["method"], "thread/resume");
        desktop.reply(&resume, json!({"thread":{"id":"t"}})).await;
        drive.await.unwrap().unwrap();
        let snapshot = b
            .access_snapshot(&json!({"runtimeId":"d","threadId":"t"}))
            .await
            .unwrap();
        assert_eq!(snapshot["phase"], "ready");
        assert_eq!(snapshot["ownerRuntimeId"], "d");
    }
    #[test]
    fn default_is_desktop_and_watch_is_explicit() {
        let mut access = AccessStore::default();
        assert_eq!(access.default_mode, AccessMode::Desktop);
        assert!(!access.wants_subscription("cli", "thread"));
        access.watched.insert(("cli".into(), "thread".into()));
        assert!(access.wants_subscription("cli", "thread"));
        access.released.insert(("cli".into(), "thread".into()));
        assert!(!access.wants_subscription("cli", "thread"));
    }
    #[test]
    fn release_suppression_survives_reload_but_watch_does_not() {
        let dir = tempfile::tempdir().unwrap();
        let mut a = AccessStore::open(dir.path(), "pc").unwrap();
        a.watched.insert(("c".into(), "t".into()));
        a.released.insert(("c".into(), "t".into()));
        a.save(dir.path()).unwrap();
        let b = AccessStore::open(dir.path(), "pc").unwrap();
        assert!(b.watched.is_empty());
        assert!(b.released.contains(&("c".into(), "t".into())));
        assert!(AccessStore::open(dir.path(), "foreign").is_err());
    }
    #[test]
    fn only_fixed_session_operations_are_allowed() {
        assert!(
            validate(
                "runtime/ensure",
                &json!({"source":"desktop","commandId":"c"})
            )
            .is_ok()
        );
        assert!(
            validate(
                "runtime/ensure",
                &json!({"source":"desktop","commandId":"c","path":"injected"})
            )
            .is_err()
        );
        assert!(
            validate(
                "thread/access/update",
                &json!({"runtimeId":"r","threadId":"t","mode":"desktop","commandId":"c"})
            )
            .is_ok()
        );
        assert!(
            validate(
                "thread/access/update",
                &json!({"runtimeId":"r","threadId":"t","mode":"shell","commandId":"c"})
            )
            .is_err()
        );
    }
}
