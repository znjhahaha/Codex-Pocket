use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{path::PathBuf, time::Duration};
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncReadExt, AsyncWrite, AsyncWriteExt};
pub const PROTOCOL: u32 = 2;
#[doc(hidden)]
pub const MAX_FRAME: usize = 4 * 1024 * 1024;
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Descriptor {
    pub protocol: u32,
    pub runtime_id: String,
    pub generation: String,
    pub pid: u32,
    pub native_pid: u32,
    pub native_process: Option<pocket_core::native_process::NativeProcess>,
    #[serde(default)]
    pub host_process: Option<pocket_core::native_process::NativeProcess>,
    pub port: u16,
    pub token: String,
    pub config_home: PathBuf,
    pub source: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Capabilities {
    pub trusted_broker_only: bool,
    pub phone_native_rpc: bool,
    pub generation_fencing: bool,
    pub persisted_recovery: bool,
    pub replay: bool,
    pub native_request: bool,
    pub native_response: bool,
    pub survives_broker_disconnect: bool,
    pub survives_host_exit: bool,
}
impl Default for Capabilities {
    fn default() -> Self {
        Self {
            trusted_broker_only: true,
            phone_native_rpc: false,
            generation_fencing: true,
            persisted_recovery: true,
            replay: true,
            native_request: true,
            native_response: true,
            survives_broker_disconnect: true,
            survives_host_exit: false,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Health {
    pub protocol: u32,
    pub runtime_id: String,
    pub generation: String,
    pub pid: u32,
    pub native_pid: u32,
    pub host_process: Option<pocket_core::native_process::NativeProcess>,
    pub native_process: Option<pocket_core::native_process::NativeProcess>,
    pub initialized: bool,
    pub capabilities: Capabilities,
    pub active_turns: usize,
    pub pending_requests: usize,
    pub reconciliation_required: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReplayEvent {
    pub sequence: u64,
    pub event: Value,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Replay {
    pub generation: String,
    pub sequence: u64,
    pub next_after: u64,
    pub has_more: bool,
    pub snapshot_required: bool,
    pub events: Vec<ReplayEvent>,
    pub pending_requests: Vec<Value>,
    pub reconciliation_required: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Receipt {
    pub generation: String,
    /// submitted, completed, unknown, or missing. Unknown must never be resubmitted.
    /// Completed can have no response after restart; reconcile instead of reissuing.
    pub state: String,
    pub response: Option<Value>,
}

#[doc(hidden)]
pub fn key(id: &Value) -> Option<String> {
    match id {
        Value::String(s) if !s.is_empty() && s.len() <= 256 => Some(format!("s:{s}")),
        Value::Number(n) if n.is_i64() || n.is_u64() => Some(format!("n:{id}")),
        _ => None,
    }
}
impl std::fmt::Debug for Descriptor {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RuntimeHost")
            .field("pid", &self.pid)
            .field("native_pid", &self.native_pid)
            .finish_non_exhaustive()
    }
}

/// Cancellation belongs to a dedicated reader task, never a partially read actor select branch.
pub async fn read_frame<R: AsyncBufRead + Unpin>(reader: &mut R) -> Result<Option<Value>> {
    let mut bytes = Vec::new();
    let length = reader
        .take((MAX_FRAME + 1) as u64)
        .read_until(b'\n', &mut bytes)
        .await?;
    if length == 0 {
        return Ok(None);
    }
    ensure!(
        length <= MAX_FRAME && bytes.last() == Some(&b'\n'),
        "Invalid host frame"
    );
    let value: Value =
        serde_json::from_slice(&bytes).map_err(|_| anyhow::anyhow!("Invalid host frame data"))?;
    ensure!(value.is_object(), "Invalid host message");
    Ok(Some(value))
}
pub async fn write_frame<W: AsyncWrite + Unpin>(writer: &mut W, value: &Value) -> Result<()> {
    let mut bytes = serde_json::to_vec(value)?;
    ensure!(bytes.len() < MAX_FRAME, "Host frame too large");
    bytes.push(b'\n');
    tokio::time::timeout(Duration::from_secs(5), async {
        writer.write_all(&bytes).await?;
        writer.flush().await
    })
    .await??;
    Ok(())
}
