//! Trusted local broker API. Never expose the descriptor or this client to a phone.
//! Discovery never spawns a native process. An IPC error is not exit evidence.
use crate::protocol::{
    Descriptor, Health, PROTOCOL, Receipt, Replay, key, read_frame, write_frame,
};
use anyhow::{Context, Result, bail, ensure};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{io::Read, path::Path, time::Duration};
use tokio::{
    io::BufReader,
    net::TcpStream,
    sync::{broadcast, mpsc},
    task::AbortHandle,
};

const DEADLINE: Duration = Duration::from_secs(10);

pub const ANSWER_EVIDENCE_PAGE_SIZE: usize = 64;

/// Cold, payload-free terminal evidence. `completed` is not semantic success.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AnswerEvidence {
    pub generation: String,
    pub request_id: Value,
    pub completed: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AnswerEvidencePage {
    pub schema: u32,
    pub namespace: String,
    pub runtime_id: String,
    pub generation: String,
    pub snapshot_digest: String,
    pub offset: usize,
    pub total: usize,
    pub next_offset: Option<usize>,
    pub records: Vec<AnswerEvidence>,
}

impl AnswerEvidencePage {
    pub fn validate(&self, runtime: &str, generation: &str, digest: &str, offset: usize) -> Result<()> {
        ensure!(self.schema == 1 && self.namespace == "answer" && self.runtime_id == runtime
            && self.generation == generation && self.snapshot_digest == digest
            && digest.len() == 64 && digest.bytes().all(|b| b.is_ascii_hexdigit()),
            "Answer evidence identity mismatch");
        ensure!(self.offset == offset && self.total <= crate::protocol::MAX_RECOVERY_RECORDS
            && offset <= self.total, "Answer evidence pagination invalid");
        let count = (self.total - offset).min(ANSWER_EVIDENCE_PAGE_SIZE);
        let end = offset + count;
        ensure!(self.records.len() == count && self.next_offset == (end < self.total).then_some(end),
            "Answer evidence page incomplete");
        let mut seen = std::collections::BTreeSet::new();
        for record in &self.records {
            ensure!(!record.generation.is_empty() && record.generation.len() <= 256
                && !record.generation.contains('\0'), "Answer evidence generation invalid");
            let id = key(&record.request_id).context("Answer evidence request identity invalid")?;
            ensure!(!id.contains('\0') && seen.insert((record.generation.clone(), id)),
                "Answer evidence identity repeated");
        }
        Ok(())
    }
}

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

    /// Native answers have a separate ID namespace from broker commands.
    /// Unsupported legacy Hosts fail closed; neither command receipts nor
    /// aggregate recovery counters establish whether an answer was written.
    pub async fn answer_receipt(&mut self, id: Value) -> Result<Receipt> {
        ensure!(key(&id).is_some(), "Invalid native request ID");
        let value = self.control("host/answer/receipt", json!({
            "generation":self.descriptor.generation,"requestId":id,
        })).await.context("Authoritative answer receipt unavailable")?;
        decode_answer_receipt(value, &id, &self.descriptor.generation)
    }

    /// Enumerate all retained answer identities, pinned to one recovery snapshot.
    /// Legacy Hosts fail closed; a command receipt is never an answer substitute.
    pub async fn answer_evidence(&mut self, offset: usize, snapshot_digest: &str) -> Result<AnswerEvidencePage> {
        ensure!(offset <= crate::protocol::MAX_RECOVERY_RECORDS && snapshot_digest.len() == 64
            && snapshot_digest.bytes().all(|b| b.is_ascii_hexdigit()), "Invalid answer evidence request");
        let value = self.control("host/answer/evidence", json!({"generation":self.descriptor.generation,
            "offset":offset,"snapshotDigest":snapshot_digest})).await
            .context("Authoritative answer evidence unavailable")?;
        let page: AnswerEvidencePage = decode_reply(value)?;
        page.validate(&self.descriptor.runtime_id, &self.descriptor.generation, snapshot_digest, offset)?;
        Ok(page)
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
                        if frame["method"]=="host/request" && value["error"]["code"]=="reconciliation_required"
                            && value["error"]["notDispatched"]==true {
                            return Err(AdmissionRejected.into());
                        }
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

/// Authenticated host/request reply proved refusal before durable reservation
/// and before native write. Timeouts, old Host errors and native errors are not this proof.
#[derive(Debug)]
pub struct AdmissionRejected;
impl std::fmt::Display for AdmissionRejected {
    fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result {write!(f,"Runtime scope requires reconciliation before dispatch")}
}
impl std::error::Error for AdmissionRejected {}

fn decode_answer_receipt(value: Value, id: &Value, generation: &str) -> Result<Receipt> {
    ensure!(value["namespace"] == "answer" && value.get("requestId") == Some(id),
        "Answer receipt namespace or identity mismatch");
    let receipt: Receipt = decode_reply(value)?;
    ensure!(receipt.generation == generation, "Answer receipt generation mismatch");
    ensure!(matches!(receipt.state.as_str(), "missing" | "unknown" | "completed"),
        "Invalid answer receipt state");
    if let Some(response) = &receipt.response {
        ensure!(receipt.state == "completed" && response.get("id") == Some(id),
            "Invalid answer receipt response");
    }
    Ok(receipt)
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

    #[tokio::test]
    async fn scoped_refusal_requires_host_request_outer_identity_and_explicit_pre_dispatch_proof() {
        for (method, reply, rejected, success) in [
            ("host/request", json!({"id":"outer","error":{"code":"reconciliation_required","notDispatched":true}}), true, false),
            ("host/request", json!({"id":"outer","error":{"code":"reconciliation_required"}}), false, false),
            ("host/status", json!({"id":"outer","error":{"code":"reconciliation_required","notDispatched":true}}), false, false),
            ("host/request", json!({"id":"native","error":{"code":"reconciliation_required","notDispatched":true}}), false, true),
        ] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (peer, _) = listener.accept().await.unwrap();
            let (_read, write) = socket.into_split();
            let (send, incoming) = mpsc::channel(1);
            let reader = tokio::spawn(async move {
                let mut peer = BufReader::new(peer);
                let request = read_frame(&mut peer).await.unwrap().unwrap();
                assert_eq!(request["id"], "outer");
                send.send(reply).await.unwrap();
            });
            let (events, _) = broadcast::channel(1);
            let mut client = HostClient {
                descriptor: Descriptor {protocol: PROTOCOL, runtime_id:"fixture".into(), generation:uuid::Uuid::new_v4().to_string(),
                    pid:1,native_pid:2,native_process:None,host_process:None,port:listener.local_addr().unwrap().port(),
                    token:"a".repeat(64),config_home:std::env::temp_dir(),source:"cli".into()},
                transport:Some(Transport {write,incoming,reader:reader.abort_handle()}),events,
            };
            let result = client.exchange("outer", &json!({"id":"outer","method":method}), Some(&json!("native"))).await;
            assert_eq!(result.is_ok(), success);
            assert_eq!(result.as_ref().err().is_some_and(|e|e.downcast_ref::<AdmissionRejected>().is_some()), rejected);
            reader.await.unwrap();
        }
    }

    #[test]
    fn scoped_admission_is_optional_and_does_not_change_legacy_capabilities() {
        let legacy = json!({"protocol":PROTOCOL,"runtimeId":"r","generation":"g","pid":1,"nativePid":2,
            "initialized":true,"capabilities":crate::protocol::Capabilities::default(),"activeTurns":0,
            "pendingRequests":0,"reconciliationRequired":true});
        let old: Health = decode_reply(legacy.clone()).unwrap();
        assert_eq!(old.scoped_admission, 0);
        let mut next = legacy;
        next["scopedAdmission"] = json!(1);
        let next: Health = decode_reply(next).unwrap();
        assert_eq!(next.scoped_admission, 1);
        assert_eq!(next.capabilities, old.capabilities);
        assert!(next.reconciliation_required);
    }

    #[test]
    fn answer_evidence_decoder_requires_exact_namespace_snapshot_and_complete_page() {
        let digest="a".repeat(64);
        let valid=json!({"schema":1,"namespace":"answer","runtimeId":"runtime","generation":"current",
            "snapshotDigest":digest,"offset":0,"total":2,"nextOffset":null,"records":[
                {"generation":"original","requestId":0,"completed":true},
                {"generation":"original","requestId":"0","completed":false}]});
        let page:AnswerEvidencePage=decode_reply(valid.clone()).unwrap();
        page.validate("runtime","current",&digest,0).unwrap();
        for fault in ["namespace","runtime","generation","digest","offset","total","partial","next","duplicate","invalid-id","invalid-generation","payload","missing-completed"] {
            let mut bad=valid.clone();
            match fault {
                "namespace"=>bad["namespace"]=json!("command"),
                "runtime"=>bad["runtimeId"]=json!("foreign"),
                "generation"=>bad["generation"]=json!("original"),
                "digest"=>bad["snapshotDigest"]=json!("b".repeat(64)),
                "offset"=>bad["offset"]=json!(1),
                "total"=>bad["total"]=json!(8193),
                "partial"=>{bad["records"].as_array_mut().unwrap().pop();},
                "next"=>bad["nextOffset"]=json!(2),
                "duplicate"=>bad["records"][1]=bad["records"][0].clone(),
                "invalid-id"=>bad["records"][0]["requestId"]=json!({"id":0}),
                "invalid-generation"=>bad["records"][0]["generation"]=json!(""),
                "payload"=>bad["records"][0]["result"]=json!("private"),
                "missing-completed"=>{bad["records"][0].as_object_mut().unwrap().remove("completed");},
                _=>unreachable!(),
            }
            assert!(decode_reply::<AnswerEvidencePage>(bad).and_then(|p|p.validate("runtime","current",&digest,0)).is_err(),"accepted {fault}");
        }
    }

    #[test]
    fn answer_evidence_decoder_enforces_page_continuity_and_shared_capacity() {
        let digest="a".repeat(64);
        let mut page=AnswerEvidencePage {schema:1,namespace:"answer".into(),runtime_id:"r".into(),generation:"g".into(),
            snapshot_digest:digest.clone(),offset:0,total:65,next_offset:Some(64),records:(0..64).map(|id|AnswerEvidence {
                generation:"old".into(),request_id:json!(id),completed:true}).collect()};
        page.validate("r","g",&digest,0).unwrap();
        page.offset=64;page.next_offset=None;page.records=vec![AnswerEvidence {generation:"old".into(),request_id:json!(64),completed:true}];
        page.validate("r","g",&digest,64).unwrap();
        assert!(page.validate("r","g",&digest,63).is_err());
        page.records.clear();
        assert!(page.validate("r","g",&digest,64).is_err());
    }

    #[test]
    fn answer_receipt_decoder_rejects_ambiguous_namespace_and_identity() {
        let valid = json!({"namespace":"answer","requestId":0,"generation":"generation",
            "state":"completed","response":{"id":0,"result":{"written":true}}});
        assert_eq!(decode_answer_receipt(valid.clone(), &json!(0), "generation").unwrap().state, "completed");
        for (field, value) in [
            ("namespace", Value::Null), ("namespace", json!("command")),
            ("requestId", json!("0")), ("generation", json!("other")),
            ("state", json!("submitted")),
            ("response", json!({"id":"0","result":{"written":true}})),
        ] {
            let mut invalid = valid.clone();
            invalid[field] = value;
            assert!(decode_answer_receipt(invalid, &json!(0), "generation").is_err(), "{field}");
        }
        let mut absent = valid.clone();
        absent.as_object_mut().unwrap().remove("requestId");
        assert!(decode_answer_receipt(absent, &json!(0), "generation").is_err());
        let mut missing = valid.clone();
        missing["state"] = json!("missing");
        assert!(decode_answer_receipt(missing.clone(), &json!(0), "generation").is_err());
        missing["response"] = Value::Null;
        assert_eq!(decode_answer_receipt(missing.clone(), &json!(0), "generation").unwrap().state, "missing");
        missing["state"] = json!("unknown");
        assert_eq!(decode_answer_receipt(missing.clone(), &json!(0), "generation").unwrap().state, "unknown");
        // Cold completed tombstones preserve the fence; they do not prove written=true.
        missing["state"] = json!("completed");
        assert!(decode_answer_receipt(missing, &json!(0), "generation").unwrap().response.is_none());
    }

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
