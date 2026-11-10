//! Trusted local broker API. Never expose the descriptor or this client to a phone.
//! Discovery never spawns a native process. An IPC error is not exit evidence.
use crate::protocol::{
    Descriptor, Health, PROTOCOL, Receipt, Replay, key, read_frame, write_frame,
};
use anyhow::{Context, Result, bail, ensure};
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use std::{io::Read, path::Path, time::Duration};
use tokio::{
    io::BufReader,
    net::TcpStream,
    sync::{broadcast, mpsc},
    task::AbortHandle,
};

const DEADLINE: Duration = Duration::from_secs(10);

pub enum Discovery {
    Missing,
    Attached(HostClient),
    /// A descriptor exists, but attachment failed. Do not launch on this outcome.
    Unavailable {
        descriptor: Descriptor,
    },
}

struct Transport {
    write: tokio::net::tcp::OwnedWriteHalf,
    incoming: mpsc::Receiver<Value>,
    reader: AbortHandle,
}
impl Drop for Transport {
    fn drop(&mut self) {
        self.reader.abort();
    }
}

pub struct HostClient {
    descriptor: Descriptor,
    transport: Option<Transport>,
    events: broadcast::Sender<Value>,
}
impl std::fmt::Debug for HostClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HostClient")
            .field("pid", &self.descriptor.pid)
            .finish_non_exhaustive()
    }
}

impl HostClient {
    pub async fn discover(path: &Path, config_home: &Path) -> Result<Discovery> {
        if !path.try_exists()? {
            return Ok(Discovery::Missing);
        }
        let descriptor = load_descriptor(path, config_home)?;
        match Self::attach(&descriptor).await {
            Ok(client) => Ok(Discovery::Attached(client)),
            Err(_) => Ok(Discovery::Unavailable { descriptor }),
        }
    }

    pub async fn attach(descriptor: &Descriptor) -> Result<Self> {
        validate_descriptor(descriptor)?;
        verify_live_identity(descriptor)?;
        let socket =
            tokio::time::timeout(DEADLINE, TcpStream::connect(("127.0.0.1", descriptor.port)))
                .await??;
        socket.set_nodelay(true)?;
        let (read, mut write) = socket.into_split();
        write_frame(
            &mut write,
            &json!({"token":descriptor.token,"generation":descriptor.generation}),
        )
        .await?;
        let (out, incoming) = mpsc::channel(128);
        let (events, _) = broadcast::channel(128);
        let event_sender = events.clone();
        let generation = descriptor.generation.clone();
        let reader = tokio::spawn(async move {
            let mut read = BufReader::new(read);
            while let Ok(Some(value)) = read_frame(&mut read).await {
                if value["method"] == "host/event" {
                    if value["params"]["generation"] != generation {
                        break;
                    }
                    let _ = event_sender.send(value.clone());
                    let native = &value["params"]["event"];
                    if native.get("id").is_some()
                        && native.get("method").is_none()
                        && out.send(native.clone()).await.is_err()
                    {
                        break;
                    }
                } else if value["method"] == "host/resync" {
                    let _ = event_sender.send(value);
                } else if out.send(value).await.is_err() {
                    break;
                }
            }
            let _ = event_sender.send(json!({"method":"host/disconnected"}));
        });
        let mut client = Self {
            descriptor: descriptor.clone(),
            events,
            transport: Some(Transport {
                write,
                incoming,
                reader: reader.abort_handle(),
            }),
        };
        client.health().await?;
        Ok(client)
    }

    pub fn runtime_id(&self) -> &str {
        &self.descriptor.runtime_id
    }
    pub fn generation(&self) -> &str {
        &self.descriptor.generation
    }
    /// Subscribe before requesting replay. Lag/disconnect requires replay, never native reissue.
    pub fn subscribe(&self) -> broadcast::Receiver<Value> {
        self.events.subscribe()
    }

    pub async fn health(&mut self) -> Result<Health> {
        let value = self.control("host/status", json!({})).await?;
        let health: Health = decode_reply(value)?;
        ensure!(
            health.protocol == PROTOCOL
                && health.initialized
                && health.runtime_id == self.descriptor.runtime_id
                && health.generation == self.descriptor.generation
                && health.pid == self.descriptor.pid
                && health.native_pid == self.descriptor.native_pid
                && health.native_process == self.descriptor.native_process
                && health.host_process == self.descriptor.host_process,
            "Runtime host identity mismatch"
        );
        ensure!(
            health.capabilities.trusted_broker_only
                && !health.capabilities.phone_native_rpc
                && health.capabilities.generation_fencing
                && health.capabilities.persisted_recovery,
            "Runtime host capability mismatch"
        );
        verify_live_identity(&self.descriptor)?;
        Ok(health)
    }

    pub async fn replay(&mut self, generation: &str, after: u64) -> Result<Replay> {
        let value = self
            .control(
                "host/replay",
                json!({"generation":generation,"after":after}),
            )
            .await?;
        let replay: Replay = decode_reply(value)?;
        ensure!(
            replay.generation == self.descriptor.generation,
            "Replay generation mismatch"
        );
        Ok(replay)
    }

    /// The caller supplies an operation ID durable in its own authorized ledger.
    /// A timeout/disconnect is uncertain; consult receipt/replay and never invent a new ID.
    pub async fn request(&mut self, id: Value, method: &str, params: Value) -> Result<Value> {
        ensure!(
            key(&id).is_some()
                && !method.is_empty()
                && !method.starts_with("host/")
                && method != "initialize"
                && method != "initialized",
            "Invalid native request"
        );
        let request = json!({"id":id,"method":method,"params":params});
        let call_id = uuid::Uuid::new_v4().to_string();
        let frame = json!({"id":call_id,"method":"host/request","params":{
            "generation":self.descriptor.generation,"request":request}});
        self.exchange(&call_id, &frame, Some(&id)).await
    }

    pub async fn receipt(&mut self, id: Value) -> Result<Receipt> {
        ensure!(key(&id).is_some(), "Invalid operation ID");
        decode_reply(
            self.control("host/receipt", json!({"requestId":id}))
                .await?,
        )
    }

    /// Response must contain exactly one of result/error. Only a live pending native ID is accepted.
    pub async fn respond(&mut self, request_id: Value, response: Value) -> Result<Receipt> {
        ensure!(key(&request_id).is_some(), "Invalid native request ID");
        decode_reply(
            self.control(
                "host/respond",
                json!({
            "generation":self.descriptor.generation,"requestId":request_id,"response":response}),
            )
            .await?,
        )
    }

    /// Includes retained runs and pending questions with their original generation.
    pub async fn recovery(&mut self) -> Result<Value> {
        self.control("host/recovery", json!({})).await
    }

    pub async fn shutdown_idle(&mut self) -> Result<()> {
        self.control(
            "host/shutdown",
            json!({"generation":self.descriptor.generation}),
        )
        .await?;
        self.transport.take();
        Ok(())
    }

    async fn control(&mut self, method: &str, params: Value) -> Result<Value> {
        let id = uuid::Uuid::new_v4().to_string();
        let value = self
            .exchange(&id, &json!({"id":id,"method":method,"params":params}), None)
            .await?;
        value.get("result").cloned().context("Invalid host reply")
    }

    async fn exchange(
        &mut self,
        id: &str,
        frame: &Value,
        native_id: Option<&Value>,
    ) -> Result<Value> {
        // Cancellation drops the socket as well as its reader. A partially written
        // request can never be followed by another frame on the same connection.
        let mut transport = self.transport.take().context("Host client disconnected")?;
        let result = tokio::time::timeout(DEADLINE, async {
            write_frame(&mut transport.write, frame).await?;
            while let Some(value) = transport.incoming.recv().await {
                if value["id"] == id {
                    if value.get("error").is_some() {
                        bail!("Host request refused");
                    }
                    if native_id.is_some() {
                        let receipt: Receipt = decode_reply(value["result"].clone())?;
                        if let Some(response) = receipt.response {
                            return Ok(response);
                        }
                        ensure!(
                            receipt.state == "submitted",
                            "Native request outcome is uncertain"
                        );
                    } else {
                        return Ok(value);
                    }
                } else if native_id.is_some_and(|id| value.get("id") == Some(id)) {
                    return Ok(value);
                }
            }
            bail!("Host disconnected; native operation may still be running")
        })
        .await;
        match result {
            Ok(Ok(value)) => {
                self.transport = Some(transport);
                Ok(value)
            }
            Ok(Err(error)) => Err(error),
            Err(_) => {
                bail!("Host deadline exceeded; native operation may still be running")
            }
        }
    }
}

fn decode_reply<T: DeserializeOwned>(value: Value) -> Result<T> {
    serde_json::from_value(value).map_err(|_| anyhow::anyhow!("Invalid host reply"))
}

pub fn load_descriptor(path: &Path, config_home: &Path) -> Result<Descriptor> {
    ensure!(
        path.is_absolute() && config_home.is_absolute(),
        "Absolute discovery paths required"
    );
    let descriptor: Descriptor = serde_json::from_slice(&read_private(path, 16384)?)
        .map_err(|_| anyhow::anyhow!("Invalid host descriptor data"))?;
    validate_descriptor(&descriptor)?;
    ensure!(
        normalized_home(&descriptor.config_home)? == normalized_home(config_home)?,
        "Configuration home mismatch"
    );
    Ok(descriptor)
}

#[doc(hidden)]
pub fn normalized_home(path: &Path) -> Result<String> {
    let home = std::fs::canonicalize(path)?.to_string_lossy().into_owned();
    #[cfg(windows)]
    let home = home.to_lowercase();
    #[cfg(windows)]
    ensure!(!msix_localcache(&home), "Configuration home redirected");
    Ok(home)
}

#[cfg(windows)]
fn msix_localcache(path: &str) -> bool {
    let path = path.replace('/', "\\").to_lowercase();
    path.contains("\\packages\\") && path.contains("\\localcache\\")
}

fn validate_descriptor(d: &Descriptor) -> Result<()> {
    ensure!(
        d.protocol == PROTOCOL
            && d.port != 0
            && d.pid != 0
            && d.native_pid != 0
            && d.config_home.is_absolute()
            && !d.runtime_id.is_empty()
            && uuid::Uuid::parse_str(&d.generation).is_ok()
            && d.token.len() == 64
            && d.token.bytes().all(|b| b.is_ascii_hexdigit())
            && d.source == "cli",
        "Invalid host descriptor"
    );
    Ok(())
}

fn verify_live_identity(d: &Descriptor) -> Result<()> {
    for (pid, expected) in [(d.pid, &d.host_process), (d.native_pid, &d.native_process)] {
        let expected = expected
            .as_ref()
            .context("Process creation identity unavailable")?;
        ensure!(
            expected.pid == pid && expected.created_at != 0,
            "Invalid process creation identity"
        );
        ensure!(
            pocket_core::native_process::NativeProcess::capture(pid)? == *expected
                && !expected.terminated(),
            "Process creation identity mismatch"
        );
    }
    Ok(())
}

/// Read-only physical-path check; never changes ACLs while discovering another host.
#[doc(hidden)]
pub fn read_private(path: &Path, limit: u64) -> Result<Vec<u8>> {
    for ancestor in path.ancestors() {
        let meta = std::fs::symlink_metadata(ancestor)?;
        ensure!(
            !meta.is_symlink(),
            "Private state cannot use a symbolic link"
        );
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            ensure!(
                meta.file_attributes() & 0x400 == 0,
                "Private state cannot use a reparse point"
            );
        }
    }
    let file = std::fs::File::open(path)?;
    ensure!(
        file.metadata()?.is_file() && file.metadata()?.len() <= limit,
        "Invalid state file"
    );
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        ensure!(
            file.metadata()?.permissions().mode() & 0o077 == 0,
            "State file is not private"
        );
    }
    #[cfg(windows)]
    {
        use std::os::windows::{ffi::OsStrExt, io::AsRawHandle};
        use windows_sys::Win32::Storage::FileSystem::{
            GetFinalPathNameByHandleW, GetLongPathNameW,
        };
        let mut buffer = vec![0u16; 32768];
        let count = unsafe {
            GetFinalPathNameByHandleW(
                file.as_raw_handle(),
                buffer.as_mut_ptr(),
                buffer.len() as u32,
                0,
            )
        };
        ensure!(
            count > 0 && (count as usize) < buffer.len(),
            "Physical path unavailable"
        );
        let actual = String::from_utf16(&buffer[..count as usize])?;
        let name: Vec<u16> = std::path::absolute(path)?
            .as_os_str()
            .encode_wide()
            .chain(Some(0))
            .collect();
        let count =
            unsafe { GetLongPathNameW(name.as_ptr(), buffer.as_mut_ptr(), buffer.len() as u32) };
        ensure!(
            count > 0 && (count as usize) < buffer.len(),
            "Expected path unavailable"
        );
        let expected = String::from_utf16(&buffer[..count as usize])?;
        // Equality alone accepts explicitly addressed package virtual storage.
        ensure!(
            !msix_localcache(&actual) && !msix_localcache(&expected),
            "State path redirected"
        );
        let normalize = |s: &str| {
            s.strip_prefix(r"\\?\")
                .unwrap_or(s)
                .replace('/', "\\")
                .to_lowercase()
        };
        ensure!(
            normalize(&actual) == normalize(&expected),
            "State path redirected"
        );
    }
    let mut bytes = Vec::new();
    file.take(limit + 1).read_to_end(&mut bytes)?;
    ensure!(bytes.len() as u64 <= limit, "State file exceeds limit");
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(windows)]
    #[test]
    fn discovery_rejects_explicit_msix_localcache_even_when_paths_are_equal() {
        let dir = tempfile::tempdir().unwrap();
        let parent = dir.path().join("PaCkAgEs/Test.Package/LoCaLcAcHe/Local");
        std::fs::create_dir_all(&parent).unwrap();
        let path = parent.join("host.json");
        std::fs::write(&path, b"{}").unwrap();
        assert!(read_private(&path, 16384).is_err());
    }

    #[test]
    fn descriptor_debug_and_parse_errors_do_not_expose_token() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("host.json");
        let token = "a".repeat(64);
        let d = Descriptor {
            protocol: PROTOCOL,
            runtime_id: "fixture".into(),
            generation: uuid::Uuid::new_v4().to_string(),
            pid: 1,
            native_pid: 2,
            host_process: None,
            native_process: None,
            port: 1,
            token: token.clone(),
            config_home: dir.path().into(),
            source: "cli".into(),
        };
        assert!(!format!("{d:?}").contains(&token));
        let mut malformed = serde_json::to_value(&d).unwrap();
        malformed
            .as_object_mut()
            .unwrap()
            .insert(token.clone(), json!(true));
        std::fs::write(&path, serde_json::to_vec(&malformed).unwrap()).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        }
        let error = load_descriptor(&path, dir.path()).unwrap_err();
        assert!(!format!("{error:#}").contains(&token));
    }
}
