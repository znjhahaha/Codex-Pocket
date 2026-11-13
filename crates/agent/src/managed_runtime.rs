//! Current-user launcher. These types are local authority, never phone DTOs.
//! Dropping a controller/session closes IPC only; it never owns a kill handle.
use anyhow::{Context, Result, ensure};
use pocket_runtime_ipc::{Descriptor, Health, HostClient, Receipt, Replay};
use serde_json::Value;
use std::path::{Path, PathBuf};
use tokio::sync::{Mutex, mpsc};

#[path = "managed_runtime/discovery.rs"]
pub mod discovery;
#[path = "managed_runtime/installation.rs"]
mod installation;
#[path = "managed_runtime/registry.rs"]
mod registry;
pub use crate::native::host::Cursor;
pub use installation::Installation;
pub use registry::Registry;

/// Local authority from an explicitly selected runtime or first-install discovery.
/// Saved profiles are restored separately, without an environment identity fallback.
#[derive(Clone, Debug)]
pub struct SelectedProfile {
    home: PathBuf,
    binary: PathBuf,
}
impl SelectedProfile {
    pub fn from_runtime(runtime: &crate::native::Connected) -> Result<Self> {
        ensure!(
            runtime.source == "cli",
            "Selected runtime must be a locally authorized CLI runtime"
        );
        let process = runtime
            .native_process
            .as_ref()
            .context("Selected native creation identity unavailable")?;
        Self::new(
            &runtime.config_home,
            installation::binary_for_process(process)?,
        )
    }
    pub fn new(config_home: impl AsRef<Path>, native_binary: impl AsRef<Path>) -> Result<Self> {
        let home = installation::physical(config_home.as_ref(), true)?;
        let binary = installation::physical(native_binary.as_ref(), false)?;
        // Apply the existing native config-store discovery rules without connecting.
        let discovered = codex_cli_adapter::Discovery::for_home(&home)?;
        ensure!(
            discovered.codex_home() == home,
            "Selected home identity changed"
        );
        Ok(Self { home, binary })
    }
    pub fn config_home(&self) -> &Path {
        &self.home
    }
    pub fn native_binary(&self) -> &Path {
        &self.binary
    }
}

#[derive(Clone)]
pub struct Controller {
    installation: Installation,
    registry: Registry,
}
impl Controller {
    pub fn new(installation: Installation, registry: Registry) -> Self {
        Self {
            installation,
            registry,
        }
    }
    pub async fn ensure(&self, profile: &SelectedProfile) -> Result<ManagedRuntime> {
        registry::ensure(&self.installation, &self.registry, profile).await
    }
}

/// One authenticated generation. A replaced descriptor never silently retargets it.
pub struct ManagedRuntime {
    descriptor_path: PathBuf,
    descriptor: Descriptor,
    initial_health: Health,
    control: Mutex<HostClient>,
    answers: Mutex<HostClient>,
}
impl ManagedRuntime {
    async fn open(path: PathBuf, descriptor: Descriptor, mut control: HostClient) -> Result<Self> {
        let initial_health = control.health().await?;
        let answers = HostClient::attach(&descriptor).await?;
        Ok(Self {
            descriptor_path: path,
            descriptor,
            initial_health,
            control: Mutex::new(control),
            answers: Mutex::new(answers),
        })
    }
    fn check_descriptor(&self) -> Result<()> {
        let current = pocket_runtime_ipc::load_descriptor(
            &self.descriptor_path,
            &self.descriptor.config_home,
        )?;
        ensure!(
            current.generation == self.descriptor.generation
                && current.host_process == self.descriptor.host_process
                && current.native_process == self.descriptor.native_process
                && current.token == self.descriptor.token,
            "Managed generation changed; detach and ensure the selected profile again"
        );
        Ok(())
    }
    pub fn runtime_id(&self) -> &str {
        &self.descriptor.runtime_id
    }
    pub fn generation(&self) -> &str {
        &self.descriptor.generation
    }
    pub fn config_home(&self) -> &Path {
        &self.descriptor.config_home
    }
    /// Initial role; health() supplies current reconciliation state.
    pub fn role(&self) -> crate::native::NativeRole {
        crate::native::NativeRole::ManagedHost {
            generation: self.generation().into(),
            reconciliation_required: self.initial_health.reconciliation_required,
        }
    }
    /// Caller registers Connected before activating its event stream.
    pub async fn attach(
        &self,
        events: mpsc::Sender<crate::native::NativeEvent>,
        cursor: Option<Cursor>,
    ) -> Result<crate::native::Connected> {
        self.check_descriptor()?;
        crate::native::host::attach(&self.descriptor_path, self.config_home(), events, cursor).await
    }
    async fn client(&self) -> Result<HostClient> {
        self.check_descriptor()?;
        HostClient::attach(&self.descriptor).await
    }
    pub async fn health(&self) -> Result<Health> {
        self.client().await?.health().await
    }
    /// Returns the native JSON-RPC envelope. Receipt queries use an independent socket.
    /// Never generates an operation ID, retries, initializes, or creates a turn implicitly.
    pub async fn request(
        &self,
        stable_operation_id: Value,
        method: &str,
        params: Value,
    ) -> Result<Value> {
        ensure!(
            pocket_runtime_ipc::support::key(&stable_operation_id).is_some()
                && !method.is_empty()
                && !method.starts_with("host/")
                && !["initialize", "initialized"].contains(&method),
            "Invalid native operation"
        );
        self.check_descriptor()?;
        let mut client = self.control.lock().await;
        let previous = client.receipt(stable_operation_id.clone()).await?;
        if previous.state != "missing"
            && !(previous.state == "completed" && previous.response.is_some())
        {
            anyhow::bail!(
                "Operation already reserved or completed without a retained result; reconcile this same ID"
            );
        }
        // A retained completed request still goes through Host digest comparison,
        // so a caller cannot reuse its ID with different params. Host never rewrites it.
        // The Host atomically reserves the same ID before writing native stdin.
        // If this socket is cancelled/disconnected, reconnect only for receipt reads.
        client.request(stable_operation_id, method, params).await
    }
    /// Response contains exactly one of result/error, as in the existing IPC adapter.
    /// Its independent connection cannot be held behind a long native request.
    pub async fn answer(&self, native_request_id: Value, response: Value) -> Result<Receipt> {
        self.check_descriptor()?;
        self.answers
            .lock()
            .await
            .respond(native_request_id, response)
            .await
    }
    pub async fn receipt(&self, stable_operation_id: Value) -> Result<Receipt> {
        self.client().await?.receipt(stable_operation_id).await
    }
    pub async fn recovery(&self) -> Result<Value> {
        self.client().await?.recovery().await
    }
    /// Notifications/server requests, as in attach(). RPC replies remain receipts.
    pub async fn events(&self, after: u64) -> Result<Replay> {
        let mut replay = self
            .client()
            .await?
            .replay(self.generation(), after)
            .await?;
        replay.events.retain(|event| {
            let value = &event.event;
            !(value.get("method").is_none()
                && value.get("id").is_some()
                && (value.get("result").is_some() || value.get("error").is_some()))
        });
        // Keep Host sequence/next_after/has_more and pending snapshots unchanged:
        // a setup/account read advances the cursor even when it has no events.
        Ok(replay)
    }
    pub async fn shutdown_idle(&self) -> Result<()> {
        self.client().await?.shutdown_idle().await
    }
}
