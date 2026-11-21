//! Serialized assistant product worker; Host lifetime is independent of remote UI.
use crate::{assistant::*, assistant_executor::*, managed_runtime::ManagedRuntime};
use anyhow::{Context, Result, ensure};
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
};
use tokio::sync::oneshot;

mod command_receipts;
mod artifacts;
pub(crate) use command_receipts::read_completed_command;

/// Read retained role identity without starting the Actor, acquiring executor
/// ownership, or touching its journal. Old/partial Coordinator starts are still
/// internal even when they are no longer the Service's current coordinator.
#[cfg(test)]
pub(crate) fn coordinator_threads(root: &Path) -> Result<BTreeSet<String>> {
    Ok(coordinator_identity(root)?.threads)
}
#[derive(Default)]
pub(crate) struct CoordinatorIdentity {
    pub runtime_id: Option<String>,
    pub threads: BTreeSet<String>,
}
pub(crate) fn coordinator_identity(root: &Path) -> Result<CoordinatorIdentity> {
    #[derive(serde::Deserialize)]
    struct Owner { role: Role }
    #[derive(serde::Deserialize)]
    struct Operation {
        role: Role,
        thread_id: Option<String>,
        mapping: Option<Mapping>,
    }
    #[derive(serde::Deserialize)]
    struct Mapping { thread_id: String }
    #[derive(serde::Deserialize)]
    struct Identity {
        #[serde(default)]
        runtime_id: Option<String>,
        operations: BTreeMap<String, Operation>,
        threads: BTreeMap<String, Owner>,
    }
    let path = root.join("executor.db");
    if !path.try_exists()? { return Ok(CoordinatorIdentity::default()); }
    let file = std::fs::File::open(&path)?;
    crate::storage::verify_physical_file(&path, &file)?;
    let db = Connection::open_with_flags(&path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    db.busy_timeout(Duration::from_millis(50))?;
    db.execute_batch("PRAGMA query_only=ON")?;
    let saved: Option<String> = db.query_row(
        "SELECT data FROM assistant_executor_state WHERE singleton=1", [], |r| r.get(0),
    ).optional()?;
    let Some(saved) = saved else { return Ok(CoordinatorIdentity::default()); };
    let identity: Identity = serde_json::from_str(&saved)?;
    let mut threads = BTreeSet::new();
    for (thread, owner) in identity.threads {
        if owner.role == Role::Coordinator { threads.insert(thread); }
    }
    for operation in identity.operations.into_values().filter(|o| o.role == Role::Coordinator) {
        threads.extend(operation.thread_id);
        threads.extend(operation.mapping.map(|m| m.thread_id));
    }
    Ok(CoordinatorIdentity { runtime_id:identity.runtime_id, threads })
}

#[derive(Clone)]
pub struct HostIo {
    pub runtime: Arc<ManagedRuntime>,
    pub handle: tokio::runtime::Handle,
    pub capabilities: crate::capabilities::Capabilities,
    pub project_reader: Option<Arc<dyn ProjectPermissionReader>>,
}
fn checked_envelope(id: &Value, envelope: Value) -> Result<Value> {
    ensure!(
        envelope["id"] == *id && envelope.is_object(),
        "Native reply identity mismatch"
    );
    ensure!(
        envelope.get("result").is_some() ^ envelope.get("error").is_some(),
        "Invalid native result/error envelope"
    );
    ensure!(
        serde_json::to_vec(&envelope)?.len() <= 4 * 1024 * 1024,
        "Native envelope oversized"
    );
    if let Some(error) = envelope.get("error") {
        anyhow::bail!(
            "Native request rejected (code {})",
            error["code"].as_i64().unwrap_or(-1)
        );
    }
    Ok(envelope["result"].clone())
}
fn checked_receipt(
    id: &Value,
    generation: &str,
    receipt: RuntimeReceipt,
) -> Result<RuntimeReceipt> {
    ensure!(
        receipt.generation == generation,
        "Native receipt generation changed"
    );
    ensure!(
        matches!(
            receipt.state.as_str(),
            "missing" | "submitted" | "unknown" | "completed"
        ),
        "Invalid native receipt state"
    );
    if let Some(v) = &receipt.response {
        ensure!(
            receipt.state == "completed" && v["id"] == *id,
            "Native receipt identity/state mismatch"
        );
        ensure!(
            v.get("result").is_some() ^ v.get("error").is_some(),
            "Invalid receipt envelope"
        );
        ensure!(
            serde_json::to_vec(v)?.len() <= 4 * 1024 * 1024,
            "Native receipt oversized"
        );
    }
    Ok(receipt)
}
fn checked_answer(id: &Value, generation: &str, receipt: RuntimeReceipt) -> Result<()> {
    let receipt = checked_receipt(id, generation, receipt)?;
    ensure!(
        receipt.state == "completed",
        "Native answer outcome unknown"
    );
    let result = checked_envelope(
        id,
        receipt
            .response
            .context("Native answer has no retained confirmation")?,
    )?;
    ensure!(
        result["written"] == true,
        "Native answer write not confirmed"
    );
    Ok(())
}
fn assistant_protocol_ready(c: &crate::capabilities::Capabilities) -> bool {
    let requirements: &[(&str, &[&str])] = &[
        (
            "thread/start",
            &[
                "model",
                "cwd",
                "sandbox",
                "approvalPolicy",
                "approvalsReviewer",
                "baseInstructions",
                "runtimeWorkspaceRoots",
                "ephemeral",
                "allowProviderModelFallback",
                "dynamicTools",
            ],
        ),
        (
            "thread/resume",
            &[
                "threadId",
                "model",
                "cwd",
                "sandbox",
                "approvalPolicy",
                "approvalsReviewer",
                "baseInstructions",
                "runtimeWorkspaceRoots",
            ],
        ),
        (
            "turn/start",
            &[
                "threadId",
                "model",
                "cwd",
                "input",
                "approvalPolicy",
                "approvalsReviewer",
                "sandboxPolicy",
                "runtimeWorkspaceRoots",
                "clientUserMessageId",
            ],
        ),
        ("turn/interrupt", &["threadId", "turnId"]),
        ("thread/read", &["threadId", "includeTurns"]),
        (
            "thread/turns/list",
            &["threadId", "limit", "sortDirection", "itemsView"],
        ),
    ];
    requirements.iter().all(|(method, fields)| {
        c.0.get(*method)
            .is_some_and(|schema| fields.iter().all(|field| schema.contains(*field)))
    })
}
impl RuntimeIo for HostIo {
    fn supports(&self, method: &str, parameters: &Value) -> bool {
        self.capabilities.supports(method, parameters)
    }
    fn project_permissions(
        &mut self,
        runtime: &str,
        thread: &str,
        cwd: &Path,
    ) -> Result<ProjectPermissionProof> {
        self.project_reader
            .as_ref()
            .context("Verified source permission reader unavailable")?
            .read(runtime, thread, cwd)
    }
    fn request(&mut self, id: Value, method: &str, parameters: Value) -> Result<Value> {
        if !self.capabilities.supports(method, &parameters) {
            return Err(RuntimeUnsupported {
                method: method.into(),
            }
            .into());
        }
        let result = self
            .handle
            .block_on(self.runtime.request(id.clone(), method, parameters))?;
        checked_envelope(&id, result)
    }
    fn receipt(&mut self, id: Value) -> Result<RuntimeReceipt> {
        let r = self.handle.block_on(self.runtime.receipt(id.clone()))?;
        checked_receipt(
            &id,
            self.runtime.generation(),
            RuntimeReceipt {
                generation: r.generation,
                state: r.state,
                response: r.response,
            },
        )
    }
    fn answer_receipt(&mut self, id: Value) -> Result<RuntimeReceipt> {
        let r = self.handle.block_on(self.runtime.answer_receipt(id.clone()))?;
        checked_receipt(&id, self.runtime.generation(), RuntimeReceipt {
            generation:r.generation, state:r.state, response:r.response,
        })
    }
    fn historical_receipt(&mut self, id:Value, generation:&str)->Result<RuntimeReceipt> {
        let receipt=self.handle.block_on(self.runtime.receipt(id.clone()))?;
        checked_receipt(&id,generation,RuntimeReceipt{generation:receipt.generation,state:receipt.state,response:receipt.response})
    }
    fn recovery(&mut self)->Result<Value> {self.handle.block_on(self.runtime.recovery())}
    fn answer_evidence(&mut self,offset:usize,digest:&str)->Result<pocket_runtime_ipc::client::AnswerEvidencePage> {
        self.handle.block_on(self.runtime.answer_evidence(offset,digest))
    }
    fn answer(&mut self, id: Value, result: Value) -> Result<()> {
        let r = self
            .handle
            .block_on(self.runtime.answer(id.clone(), json!({"result":result})))?;
        checked_answer(
            &id,
            self.runtime.generation(),
            RuntimeReceipt {
                generation: r.generation,
                state: r.state,
                response: r.response,
            },
        )
    }
    fn events(&mut self, after: u64, limit: usize) -> Result<RuntimeEvents> {
        ensure!((1..=256).contains(&limit), "Invalid runtime event limit");
        let r = self.handle.block_on(self.runtime.events(after))?;
        ensure!(
            r.generation == self.runtime.generation(),
            "Native replay generation changed"
        );
        let truncated = r.events.len() > limit;
        let events: Vec<_> = r
            .events
            .into_iter()
            .take(limit)
            .map(|e| RuntimeEvent {
                sequence: e.sequence,
                message: e.event,
            })
            .collect();
        let next_after = if truncated {
            events.last().map_or(after, |e| e.sequence)
        } else {
            r.next_after
        };
        Ok(RuntimeEvents {
            generation: r.generation,
            next_after,
            snapshot_required: r.snapshot_required,
            events,
            pending_requests: r.pending_requests,
        })
    }
    fn health(&mut self) -> Result<RuntimeHealth> {
        let h = self.handle.block_on(self.runtime.health())?;
        ensure!(
            h.runtime_id == self.runtime.runtime_id() && h.generation == self.runtime.generation(),
            "Host identity changed"
        );
        let schema = assistant_protocol_ready(&self.capabilities);
        Ok(RuntimeHealth {
            runtime_id: h.runtime_id,
            generation: h.generation,
            online: true,
            initialized: h.initialized,
            protocol_verified: schema,
            reconciliation_required: h.reconciliation_required,
            scoped_admission:h.scoped_admission==1,
        })
    }
    fn account(&mut self) -> Result<AccountReadiness> {
        let id = read_id();
        let result = self.handle.block_on(async {
            tokio::time::timeout(
                Duration::from_secs(5),
                self.runtime
                    .request(id.clone(), "account/read", json!({"refreshToken":false})),
            )
            .await
        })??;
        Ok(AccountReadiness::from_native(&checked_envelope(
            &id, result,
        )?))
    }
}

type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;
fn now() -> i64 {
    chrono::Utc::now().timestamp_millis()
}
fn key(id: &Value) -> String {
    serde_json::to_string(id).unwrap_or_default()
}
fn digest(v: &impl serde::Serialize) -> Result<String> {
    Ok(format!("{:x}", Sha256::digest(serde_json::to_vec(v)?)))
}
fn physical(path: &Path) -> Result<PathBuf> {
    ensure!(path.is_absolute(), "Project directory must be absolute");
    let p = dunce::canonicalize(path)?;
    ensure!(p.is_dir(), "Project directory missing");
    Ok(p)
}
fn within(path: &Path, root: &Path) -> bool {
    #[cfg(windows)]
    {
        Path::new(&path.to_string_lossy().to_lowercase())
            .starts_with(Path::new(&root.to_string_lossy().to_lowercase()))
    }
    #[cfg(not(windows))]
    {
        path.starts_with(root)
    }
}
fn read_id() -> Value {
    json!(format!("assistant-product-read:{}", uuid::Uuid::new_v4()))
}
fn read_method(method: &str) -> bool {
    matches!(
        method,
        "model/list"
            | "config/read"
            | "thread/read"
            | "thread/list"
            | "thread/turns/list"
            | "thread/items/list"
    )
}
#[derive(Default)]
struct Tracking {
    epoch: u64,
    generation: String,
    pending: BTreeSet<String>,
    uncertain_answers: BTreeMap<String, u64>,
    old_answers: bool,
    unsupported: Option<String>,
}
#[derive(Clone)]
struct WorkerIo<R> {
    inner: R,
    handoff: Option<Arc<crate::handoff::Handoff>>,
    tracking: Arc<Mutex<Tracking>>,
    ledger: Arc<Mutex<Connection>>,
}
impl<R: RuntimeIo> RuntimeIo for WorkerIo<R> {
    fn supports(&self, method: &str, parameters: &Value) -> bool {
        self.inner.supports(method, parameters)
    }
    fn request(&mut self, id: Value, method: &str, p: Value) -> Result<Value> {
        // New native work is checked again at the transport boundary. A fenced
        // operation is unavailable before dispatch, never an ambiguous retry.
        if matches!(
            method,
            "thread/start" | "thread/resume" | "turn/start" | "turn/steer"
        ) && self.handoff.as_ref().is_some_and(|h| !h.accepting_writes())
        {
            return Err(RuntimeUnsupported {
                method: format!("{method} (handoff fenced before dispatch)"),
            }
            .into());
        }
        let result = self.inner.request(id, method, p);
        let mut t = self.tracking.lock().unwrap();
        if result
            .as_ref()
            .err()
            .is_some_and(|e| e.downcast_ref::<RuntimeUnsupported>().is_some())
        {
            t.unsupported = Some(method.into());
        } else if result.is_ok() && !read_method(method) {
            t.unsupported = None;
        }
        result
    }
    fn receipt(&mut self, id: Value) -> Result<RuntimeReceipt> {
        self.inner.receipt(id)
    }
    fn answer_receipt(&mut self, id: Value) -> Result<RuntimeReceipt> {
        self.inner.answer_receipt(id)
    }
    fn historical_receipt(&mut self,id:Value,generation:&str)->Result<RuntimeReceipt> {self.inner.historical_receipt(id,generation)}
    fn recovery(&mut self)->Result<Value> {self.inner.recovery()}
    fn answer_evidence(&mut self,offset:usize,digest:&str)->Result<pocket_runtime_ipc::client::AnswerEvidencePage> {
        self.inner.answer_evidence(offset,digest)
    }
    fn confirm_answer_receipt(&mut self, generation: &str, id: &Value) -> Result<()> {
        let mut t = self.tracking.lock().unwrap();
        ensure!(generation == t.generation, "Answer confirmation generation changed");
        if t.uncertain_answers.contains_key(&key(id)) {
            self.ledger.lock().unwrap().execute(
                "DELETE FROM product_answers WHERE generation=?1 AND native_id=?2",
                params![generation, key(id)],
            )?;
            t.uncertain_answers.remove(&key(id));
        }
        Ok(())
    }
    fn answer(&mut self, id: Value, result: Value) -> Result<()> {
        let (generation, epoch) = {
            let t = self.tracking.lock().unwrap();
            (t.generation.clone(), t.epoch)
        };
        // Durable intent precedes the native answer; a crash is an unknown
        // answer until a later authoritative pending snapshot proves resolution.
        self.ledger.lock().unwrap().execute(
            "INSERT OR IGNORE INTO product_answers(generation,native_id) VALUES(?1,?2)",
            params![generation, key(&id)],
        )?;
        self.tracking
            .lock()
            .unwrap()
            .uncertain_answers
            .insert(key(&id), epoch);
        // Executor calls confirm_answer_receipt only after saving its own
        // confirmation. Keep this product intent if that save fails.
        self.inner.answer(id, result)
    }
    fn events(&mut self, after: u64, limit: usize) -> Result<RuntimeEvents> {
        let batch = self.inner.events(after, limit)?;
        ensure!(
            batch.events.len() <= limit
                && batch.pending_requests.len() <= 128
                && batch.next_after >= after,
            "Invalid authoritative native event page"
        );
        ensure!(
            batch
                .pending_requests
                .iter()
                .all(|p| pocket_runtime_ipc::support::key(&p["id"]).is_some()
                    && p["method"].as_str().is_some()),
            "Invalid authoritative pending request identity"
        );
        let mut t = self.tracking.lock().unwrap();
        ensure!(
            batch.generation == t.generation,
            "Pending snapshot generation changed"
        );
        t.epoch += 1;
        t.pending = batch
            .pending_requests
            .iter()
            .filter_map(|r| r.get("id").map(key))
            .collect();
        let epoch = t.epoch;
        let pending = t.pending.clone();
        let resolved = t
            .uncertain_answers
            .iter()
            .filter(|(id, attempt)| **attempt < epoch && !pending.contains(*id))
            .map(|(id, _)| id.clone())
            .collect::<Vec<_>>();
        for id in resolved {
            self.ledger.lock().unwrap().execute(
                "DELETE FROM product_answers WHERE generation=?1 AND native_id=?2",
                params![t.generation, id],
            )?;
            t.uncertain_answers.remove(&id);
        }
        Ok(batch)
    }
    fn health(&mut self) -> Result<RuntimeHealth> {
        self.inner.health()
    }
    fn account(&mut self) -> Result<AccountReadiness> {
        self.inner.account()
    }
    fn project_permissions(
        &mut self,
        runtime: &str,
        thread: &str,
        cwd: &Path,
    ) -> Result<ProjectPermissionProof> {
        self.inner.project_permissions(runtime, thread, cwd)
    }
}
type Service<R> = AssistantService<SharedHostExecutor<WorkerIo<R>>>;
struct Request {
    method: String,
    parameters: Value,
    id: String,
    // Authenticated Broker metadata; never obtained from phone parameters.
    caller: Option<String>,
    directory: Option<PathBuf>,
    reply: oneshot::Sender<Result<Value>>,
}
#[derive(Clone)]
pub struct Actor {
    sender: mpsc::SyncSender<Request>,
    snapshot: Arc<Mutex<Value>>,
    alive: Arc<AtomicBool>,
}
struct WorkerLife(Arc<AtomicBool>);
impl Drop for WorkerLife {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}
impl Actor {
    pub async fn start(
        root: PathBuf,
        computer: String,
        profile: String,
        runtime: Arc<ManagedRuntime>,
        capabilities: crate::capabilities::Capabilities,
        handoff: Option<Arc<crate::handoff::Handoff>>,
    ) -> Result<Self> {
        let project_reader =
            project_permission_reader(root.parent().context("Assistant state parent missing")?)?;
        Self::start_with_io(
            root,
            computer,
            profile,
            HostIo {
                runtime,
                handle: tokio::runtime::Handle::current(),
                capabilities,
                project_reader,
            },
            handoff,
            Arc::new(now),
        )
        .await
    }
    pub(crate) async fn start_with_io<R: RuntimeIo + Clone + Send + 'static>(
        root: PathBuf,
        computer: String,
        profile: String,
        io: R,
        handoff: Option<Arc<crate::handoff::Handoff>>,
        clock: Clock,
    ) -> Result<Self> {
        let (sender, receiver) = mpsc::sync_channel::<Request>(32);
        let snapshot = Arc::new(Mutex::new(
            json!({"enabled":true,"ready":false,"fresh":false,"snapshotSource":"starting"}),
        ));
        let alive = Arc::new(AtomicBool::new(true));
        let out = snapshot.clone();
        let running = alive.clone();
        let (started, ready) = oneshot::channel();
        std::thread::Builder::new().name("pocket-assistant".into()).spawn(move ||{
            let _life=WorkerLife(running.clone());
            let mut worker=match Worker::open(root,computer,profile,io,handoff,clock){
                Ok(worker)=>worker,Err(error)=>{running.store(false,Ordering::Release);let _=started.send(Err(error));return;}
            };
            match worker.snapshot(None){
                Ok(value)=>{*out.lock().unwrap()=value;let _=started.send(Ok(()));}
                Err(error)=>{drop(worker);running.store(false,Ordering::Release);let _=started.send(Err(error));return;}
            }
            let mut parked=false;
            loop{
                let request=match receiver.recv_timeout(Duration::from_millis(500)){
                    Ok(request)=>Some(request),Err(mpsc::RecvTimeoutError::Timeout)=>None,Err(mpsc::RecvTimeoutError::Disconnected)=>break,
                };
                if let Some(r)=request.as_ref().filter(|r|r.caller.is_none() && r.method.starts_with("local/lifecycle/")) {
                    let method=r.method.clone();let r=request.unwrap();
                    match method.as_str() {
                        "local/lifecycle/park"=>{parked=true;let _=r.reply.send(Ok(json!({"generation":worker.generation})));}
                        "local/lifecycle/resume"=>{parked=false;let _=r.reply.send(Ok(Value::Null));}
                        "local/lifecycle/stop" if parked=>{
                            drop(worker);running.store(false,Ordering::Release);
                            let _=r.reply.send(Ok(Value::Null));return;
                        }
                        _=>{let _=r.reply.send(Err(anyhow::anyhow!("Worker lifecycle transition refused")));}
                    }
                    continue;
                }
                if parked {
                    if let Some(r)=request {let _=r.reply.send(Err(anyhow::anyhow!("Assistant runtime transition in progress")));}
                    continue;
                }
                // A timed-out read has already fallen back to the published cache.
                // Mutations must still finish even if their caller stopped waiting.
                if request.as_ref().is_some_and(|r| is_read(&r.method) && r.reply.is_closed()) { continue; }
                // Observe buffered native results before recording a newer
                // user message. Explicit requests wake this loop immediately.
                if request.as_ref().is_some_and(|r| is_read(&r.method)) { worker.account_checked_at = i64::MIN; }
                worker.observe_runtime();
                if request.as_ref().is_some_and(|r| !is_read(&r.method)) {
                    let mut cache = out.lock().unwrap();
                    cache["dispatchInFlight"] = json!(true);
                    cache["ready"] = json!(false);
                    cache["fresh"] = json!(false);
                    cache["error"] = Value::Null;
                }
                let mut result=request.as_ref().map(|r| {
                    let receipt=worker.apply(r)?;
                    if !is_read(&r.method) {
                        command_receipts::record(&worker.messages,r,&receipt)?;
                    }
                    Ok(receipt)
                });
                let request = match request {
                    Some(request) if !is_read(&request.method) => {
                        let response = result.take().unwrap().map(|receipt| {
                            let mut response = json!({"accepted":true,"commandId":request.id,"result":receipt});
                            // Acceptance is durable before dispatch. A later refresh failure
                            // cannot turn that receipt into an ambiguous mutation result.
                            if let Ok(mut value) = worker.snapshot(None) {
                                value["dispatchInFlight"] = json!(true);
                                value["ready"] = json!(false);
                                value["fresh"] = json!(false);
                                value["snapshotSource"] = json!("worker_cache");
                                *out.lock().unwrap() = value.clone();
                                response["snapshot"] = value;
                            }
                            response
                        });
                        let _ = request.reply.send(response);
                        None
                    }
                    request => request,
                };
                worker.dispatch_pending();
                let value=worker.snapshot(None);
                if let Ok(v)=&value{*out.lock().unwrap()=v.clone();}
                if let Some(request)=request{
                    let response=(||->Result<Value>{match result.unwrap(){
                        Ok(receipt) if request.method=="assistant/artifact/read"=>Ok(receipt),
                        Ok(_) if is_read(&request.method)=>{
                            match value{Ok(mut v)=>{
                                if request.method=="assistant/settings/read"{v=json!({"settings":v["settings"],"fresh":true,"updatedAtMs":v["updatedAtMs"],"snapshotSource":"worker_read"});}
                                else {
                                    if let Some(after)=request.parameters.get("after").and_then(Value::as_u64){v["events"]=json!(worker.service.events(after,256)?);}
                                    if request.parameters.get("before").is_some() || request.parameters.get("limit").is_some() {
                                        v["conversationPage"]=timeline_page(&worker.service,&worker.messages,request.parameters["before"].as_u64(),request.parameters["limit"].as_u64().unwrap_or(50) as usize)?;
                                    }
                                    if let Some(task)=request.parameters["taskId"].as_str(){v["taskDetail"]=worker.service.task_context(task)?;}
                                }
                                Ok(v)
                            },Err(e)=>Err(e)}
                        }
                        Ok(receipt)=>match value{Ok(v)=>Ok(json!({"accepted":true,"commandId":request.id,"result":receipt,"snapshot":v})),Err(e)=>Err(e)},
                        Err(error)=>Err(error),
                    }})();
                    let _=request.reply.send(response);
                }
            }
            // IPC/remote shutdown never kills Host or native turns. Pending
            // write permits remain unconfirmed if this worker dies uncertain.
            drop(worker);running.store(false,Ordering::Release);
        })?;
        tokio::time::timeout(Duration::from_secs(20), ready)
            .await
            .context("Assistant startup timed out")???;
        Ok(Self {
            sender,
            snapshot,
            alive,
        })
    }
    pub fn snapshot(&self) -> Value {
        let mut value = self.snapshot.lock().unwrap().clone();
        value["fresh"] = json!(false);
        value["snapshotSource"] = json!("worker_cache");
        if !self.alive.load(Ordering::Acquire) {
            value["ready"] = json!(false);
        }
        value
    }
    pub fn is_alive(&self) -> bool {
        self.alive.load(Ordering::Acquire)
    }
    pub(crate) async fn park_for_runtime_transition(&self)->Result<String> {
        let value=self.request("local/lifecycle/park",json!({}),"lifecycle".into(),None).await?;
        Ok(text(&value,"generation")?.into())
    }
    pub(crate) async fn resume_after_runtime_check(&self)->Result<()> {
        self.request("local/lifecycle/resume",json!({}),"lifecycle".into(),None).await?;Ok(())
    }
    pub(crate) async fn stop_parked_worker(&self)->Result<()> {
        self.request("local/lifecycle/stop",json!({}),"lifecycle".into(),None).await?;Ok(())
    }
    pub(crate) fn same_worker(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.alive, &other.alive)
    }
    pub async fn request(
        &self,
        method: &str,
        parameters: Value,
        id: String,
        directory: Option<PathBuf>,
    ) -> Result<Value> {
        self.request_authorized(method, parameters, id, directory, None).await
    }
    pub(crate) async fn request_from_device(
        &self, method: &str, parameters: Value, id: String,
        directory: Option<PathBuf>, caller: String,
    ) -> Result<Value> {
        ensure!(!caller.is_empty() && caller.len() <= 256, "Invalid authenticated caller");
        self.request_authorized(method, parameters, id, directory, Some(caller)).await
    }
    async fn request_authorized(
        &self, method: &str, parameters: Value, id: String,
        directory: Option<PathBuf>, caller: Option<String>,
    ) -> Result<Value> {
        ensure!(
            self.alive.load(Ordering::Acquire),
            "Assistant worker unavailable"
        );
        let (reply, response) = oneshot::channel();
        self.sender
            .try_send(Request {
                method: method.into(),
                parameters,
                id,
                caller,
                directory,
                reply,
            })
            .map_err(|_| anyhow::anyhow!("Assistant queue busy"))?;
        // Leave time for the Broker to return its explicitly stale cache before
        // the phone's eight-second quiet read window expires.
        let budget = if is_read(method) { Duration::from_millis(1500) } else { Duration::from_secs(25) };
        tokio::time::timeout(budget, response)
            .await
            .context("Assistant result pending")??
    }
}
fn is_read(method: &str) -> bool {
    matches!(
        method,
        "assistant/read" | "assistant/settings/read" | "assistant/snapshot" | "assistant/artifact/read"
    )
}
#[derive(Clone)]
struct Project {
    thread: String,
    root: PathBuf,
    writable: bool,
    verified: bool,
    source_runtime: String,
    source_generation: String,
    permission_revision: String,
}
struct Worker<R: RuntimeIo + Clone> {
    service: Service<R>,
    executor: SharedHostExecutor<WorkerIo<R>>,
    probe: WorkerIo<R>,
    messages: Connection,
    projects: Vec<Project>,
    workspace: PathBuf,
    deferred: BTreeMap<String, PendingToolCall>,
    local_receipts_pending: BTreeSet<String>,
    handoff: Option<Arc<crate::handoff::Handoff>>,
    permits: Vec<crate::handoff::WritePermit>,
    recovered_permits: Vec<uuid::Uuid>,
    clock: Clock,
    runtime_id: String,
    generation: String,
    error: Option<&'static str>,
    runtime_ready: bool,
    startup_runtime_ready: bool,
    capabilities_ready: bool,
    account: AccountReadiness,
    account_checked_at: i64,
    revision: u64,
}
fn messages_db(path: &Path) -> Result<Connection> {
    let db = Connection::open(path)?;
    db.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY,text TEXT NOT NULL,at_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS product_timeline(sequence INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL,at_ms INTEGER NOT NULL,operation_id TEXT,source_sequence INTEGER);
CREATE TABLE IF NOT EXISTS product_meta(key TEXT PRIMARY KEY,value INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS product_projects(thread_id TEXT NOT NULL,root TEXT NOT NULL,writable INTEGER NOT NULL,PRIMARY KEY(thread_id,root));
CREATE TABLE IF NOT EXISTS product_answers(generation TEXT NOT NULL,native_id TEXT NOT NULL,PRIMARY KEY(generation,native_id));
CREATE TABLE IF NOT EXISTS product_guards(token TEXT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS product_question_history(id TEXT PRIMARY KEY,data TEXT NOT NULL);
UPDATE product_projects SET writable=(SELECT max(p.writable) FROM product_projects p WHERE p.root=product_projects.root);
DELETE FROM product_projects WHERE rowid NOT IN (SELECT max(rowid) FROM product_projects GROUP BY root);
DELETE FROM product_timeline WHERE role='User' AND id IN (SELECT id FROM messages) AND ('user:'||id) IN (SELECT id FROM product_timeline);
UPDATE product_timeline SET id='user:'||id WHERE role='User' AND id IN (SELECT id FROM messages);
INSERT OR IGNORE INTO product_timeline(id,role,text,at_ms) SELECT 'user:'||id,'User',text,at_ms FROM messages ORDER BY at_ms,rowid;")?;
    command_receipts::initialize(&db)?;
    artifacts::initialize(&db)?;
    let columns = db
        .prepare("PRAGMA table_info(product_projects)")?
        .query_map([], |r| r.get::<_, String>(1))?
        .collect::<rusqlite::Result<BTreeSet<_>>>()?;
    for name in ["source_runtime", "source_generation", "permission_revision"] {
        if !columns.contains(name) {
            db.execute_batch(&format!(
                "ALTER TABLE product_projects ADD COLUMN {name} TEXT NOT NULL DEFAULT ''"
            ))?;
        }
    }
    Ok(db)
}
impl<R: RuntimeIo + Clone> Worker<R> {
    fn open(
        root: PathBuf,
        computer: String,
        profile: String,
        mut io: R,
        handoff: Option<Arc<crate::handoff::Handoff>>,
        clock: Clock,
    ) -> Result<Self> {
        crate::storage::private_directory(&root)?;
        let workspace = root.join("workspace");
        crate::storage::private_directory(&workspace)?;
        let health = io.health()?;
        let account = if health.online && health.initialized {
            io.account().unwrap_or(AccountReadiness::Unavailable)
        } else {
            AccountReadiness::Unavailable
        };
        let runtime_id = health.runtime_id.clone();
        let generation = health.generation.clone();
        let messages = messages_db(&root.join("messages.db"))?;
        messages.busy_timeout(Duration::from_secs(5))?;
        let ledger = Connection::open(root.join("messages.db"))?;
        ledger.busy_timeout(Duration::from_secs(5))?;
        let (uncertain_answers, old_answers) = {
            let mut stmt = ledger.prepare("SELECT generation,native_id FROM product_answers")?;
            let rows = stmt
                .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            (
                rows.iter()
                    .filter(|(g, _)| *g == generation)
                    .map(|(_, id)| (id.clone(), 0))
                    .collect(),
                rows.iter().any(|(g, _)| *g != generation),
            )
        };
        let recovered_permits = {
            let mut stmt = messages.prepare("SELECT token FROM product_guards")?;
            stmt.query_map([], |r| r.get::<_, String>(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?
                .into_iter()
                .map(|s| s.parse())
                .collect::<std::result::Result<Vec<_>, _>>()?
        };
        let tracking = Arc::new(Mutex::new(Tracking {
            generation: generation.clone(),
            uncertain_answers,
            old_answers,
            ..Default::default()
        }));
        let mut probe = WorkerIo {
            inner: io,
            handoff: handoff.clone(),
            tracking,
            ledger: Arc::new(Mutex::new(ledger)),
        };
        let mut projects = {
            let mut stmt = messages.prepare(
                "SELECT thread_id,root,writable,source_runtime,source_generation,permission_revision FROM product_projects ORDER BY rowid LIMIT 65",
            )?;
            stmt.query_map([], |r| {
                Ok(Project {
                    thread: r.get(0)?,
                    root: PathBuf::from(r.get::<_, String>(1)?),
                    writable: r.get(2)?,
                    verified: false,
                    source_runtime: r.get(3)?,
                    source_generation: r.get(4)?,
                    permission_revision: r.get(5)?,
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?
        };
        ensure!(projects.len() <= 64, "Too many persisted projects");
        let mut grants = vec![DirectoryGrant {root:workspace.clone(),writable:true}];
        for project in &mut projects {
            if let Ok((actual, writable, proof)) = verify_project(
                &mut probe,
                &project.source_runtime,
                Some(&project.source_generation),
                &project.thread,
                &project.root,
            ) {
                project.root = actual;
                project.writable &= writable;
                project.permission_revision = proof.revision;
                messages.execute(
                    "UPDATE product_projects SET writable=?1,permission_revision=?4 WHERE thread_id=?2 AND root=?3",
                    params![
                        project.writable,
                        project.thread,
                        project.root.to_string_lossy()
                        ,project.permission_revision
                    ],
                )?;
                project.verified = true;
                grants.push(DirectoryGrant {
                    root: project.root.clone(),
                    writable: project.writable,
                });
            }
        }
        let executor = SharedHostExecutor::new(HostExecutor::open(
            &root.join("executor.db"),
            probe.clone(),
            ExecutorPolicy {
                coordinator_directory: workspace.clone(),
                grants,
            },
        )?);
        let mut service = AssistantService::open(
            &root.join("assistant.db"),
            &computer,
            &profile,
            &generation,
            executor.clone(),
        )?;
        let legacy=messages.prepare("SELECT id,role,text,at_ms FROM product_timeline ORDER BY sequence")?
            .query_map([],|row|Ok(ConversationEntry{id:row.get(0)?,role:row.get(1)?,text:row.get(2)?,at_ms:row.get(3)?,member_id:None,task_id:None,group_id:None,source_thread_id:None}))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        service.import_legacy_conversation(legacy)?;
        let answers_confirmed={let tracking=probe.tracking.lock().unwrap();!tracking.old_answers && tracking.uncertain_answers.is_empty()};
        let scoped_transition=executor.adopt_scoped_generation();
        let terminal_proof_failed = if matches!(scoped_transition,Ok(true)) {false}
        else if scoped_transition.is_err() {true}
        else if recovered_permits.is_empty() && answers_confirmed {
            // Only a complete cold terminal proof can move executor generation.
            // A failed proof preserves old histories and all dispatch fences.
            executor.reconcile_terminal_generation(&service.operations()).is_err()
        } else { false };
        let executor_generation_ready=executor.current_generation_ready(&generation)?;
        if executor_generation_ready {
            service.release_ended_resources(&executor.released_generations()?)?;
        }
        let mut worker = Self {
            service,
            executor,
            probe,
            messages,
            projects,
            workspace,
            deferred: BTreeMap::new(),
            local_receipts_pending: BTreeSet::new(),
            handoff,
            permits: vec![],
            recovered_permits,
            clock: clock.clone(),
            runtime_id,
            generation,
            error: if terminal_proof_failed || !executor_generation_ready {Some("terminal_generation_requires_reconciliation")}else{None},
            runtime_ready: health.online && health.initialized && health.protocol_verified,
            startup_runtime_ready: health.online && health.initialized,
            capabilities_ready: health.protocol_verified,
            account,
            account_checked_at: (clock)(),
            revision: 0,
        };
        worker.sync_timeline()?;
        worker.pause_unverified_tasks()?;
        Ok(worker)
    }
    fn allowed(&self) -> bool {
        self.handoff.as_ref().is_none_or(|h| h.accepting_writes())
    }
    fn admit(&self) -> Result<Option<crate::handoff::WritePermit>> {
        let permit = self.handoff.as_ref().map(|h| h.admit_write()).transpose()?;
        if let Some(p) = &permit {
            self.messages.execute(
                "INSERT INTO product_guards(token) VALUES(?1)",
                [p.token().to_string()],
            )?;
        }
        Ok(permit)
    }
    fn finish_permit(&mut self, permit: Option<crate::handoff::WritePermit>) {
        let Some(permit) = permit else {
            return;
        };
        if self.uncertain() && self.permits.is_empty() && self.recovered_permits.is_empty() {
            self.permits.push(permit);
        } else {
            let token = permit.token();
            if permit.confirm().is_ok() {
                let _ = self.messages.execute(
                    "DELETE FROM product_guards WHERE token=?1",
                    [token.to_string()],
                );
            }
        }
    }
    fn uncertain(&self) -> bool {
        self.native_uncertain() || !self.local_receipts_pending.is_empty()
    }
    fn native_uncertain(&self) -> bool {
        // Read before taking tracking: executor I/O may take the tracking lock.
        let generation_ready=self.executor.current_generation_ready(&self.generation).unwrap_or(false);
        let t = self.probe.tracking.lock().unwrap();
        self.service
            .operations()
            .iter()
            .any(|o| o.phase == OperationPhase::Unknown)
            || self.service.uncertain_deliveries()
            || t.old_answers
            || !t.uncertain_answers.is_empty()
            || !generation_ready
    }
    fn confirm_known(&mut self) {
        if !self.uncertain() {
            for permit in std::mem::take(&mut self.permits) {
                let token = permit.token();
                if permit.confirm().is_ok() {
                    let _ = self.messages.execute(
                        "DELETE FROM product_guards WHERE token=?1",
                        [token.to_string()],
                    );
                }
            }
            for token in std::mem::take(&mut self.recovered_permits) {
                if self
                    .handoff
                    .as_ref()
                    .is_none_or(|h| h.confirm_write(token).is_ok())
                {
                    let _ = self.messages.execute(
                        "DELETE FROM product_guards WHERE token=?1",
                        [token.to_string()],
                    );
                }
            }
        }
    }
    fn sync_timeline(&mut self) -> Result<()> {
        // The executor durably saves text together with its replay cursor. If
        // the worker crashed before projection, reconstruct without native RPC.
        for stream in self.executor.streaming_text()? {
            if self.service.operations().iter().any(|o| {
                o.id == stream.operation_id
                    && matches!(
                        o.phase,
                        OperationPhase::Unknown | OperationPhase::Running | OperationPhase::Waiting
                    )
                    && (o.role == Role::Coordinator || self.service.direct_result(o))
            }) {
                self.messages.execute("INSERT INTO product_timeline(id,role,text,at_ms,operation_id) VALUES(?1,'Assistant',?2,?3,?4) ON CONFLICT(id) DO UPDATE SET text=excluded.text",
                    params![format!("assistant-operation:{}",stream.operation_id),stream.text,(self.clock)(),stream.operation_id])?;
            }
        }
        let mut after = self
            .messages
            .query_row(
                "SELECT value FROM product_meta WHERE key='event_cursor'",
                [],
                |r| r.get::<_, u64>(0),
            )
            .optional()?
            .unwrap_or(0);
        for _ in 0..8 {
            let events = self.service.events(after, 256)?;
            if events.is_empty() {
                break;
            }
            let tx = self.messages.transaction()?;
            for event in &events {
                if event.kind == "completed"
                    && event.operation_id.as_ref().is_some_and(|id| {
                        self.service.operations().iter().any(|o| {
                            o.id == *id
                                && (o.role == Role::Coordinator || self.service.direct_result(o))
                        })
                    })
                {
                    let stream_id = format!(
                        "assistant-operation:{}",
                        event.operation_id.as_deref().unwrap()
                    );
                    let streaming: bool = tx.query_row(
                        "SELECT EXISTS(SELECT 1 FROM product_timeline WHERE id=?1)",
                        [&stream_id],
                        |r| r.get(0),
                    )?;
                    if streaming {
                        tx.execute(
                            "UPDATE product_timeline SET text=?2,source_sequence=?3 WHERE id=?1",
                            params![stream_id, event.detail, event.sequence],
                        )?;
                    } else {
                        tx.execute("INSERT OR IGNORE INTO product_timeline(id,role,text,at_ms,operation_id,source_sequence) VALUES(?1,'Assistant',?2,?3,?4,?5)",params![format!("assistant:{}",event.id),event.detail,event.at_ms,event.operation_id,event.sequence])?;
                    }
                }
                after = event.sequence;
            }
            tx.execute("INSERT INTO product_meta(key,value) VALUES('event_cursor',?1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[after])?;
            tx.commit()?;
        }
        Ok(())
    }
    fn authorize(
        &mut self,
        runtime: &str,
        generation: Option<&str>,
        thread: &str,
        root: &Path,
        writable: bool,
    ) -> Result<()> {
        let (root, native_writable, proof) =
            verify_project(&mut self.probe, runtime, generation, thread, root)?;
        let existing = self
            .projects
            .iter()
            .position(|p| within(&p.root, &root) && within(&root, &p.root));
        ensure!(
            existing.is_some() || self.projects.len() < 64,
            "Too many projects"
        );
        let writable = native_writable
            && (writable
                || existing.is_some_and(|i| {
                    let previous = &self.projects[i];
                    previous.writable
                        && previous.source_runtime == runtime
                        && previous.thread == thread
                        && previous.source_generation == proof.generation
                }));
        let tx = self.messages.transaction()?;
        if let Some(i) = existing {
            tx.execute(
                "DELETE FROM product_projects WHERE root=?1",
                [self.projects[i].root.to_string_lossy().as_ref()],
            )?;
        }
        tx.execute("INSERT INTO product_projects(thread_id,root,writable,source_runtime,source_generation,permission_revision) VALUES(?1,?2,?3,?4,?5,?6) ON CONFLICT(thread_id,root) DO UPDATE SET writable=excluded.writable,source_runtime=excluded.source_runtime,source_generation=excluded.source_generation,permission_revision=excluded.permission_revision",params![thread,root.to_string_lossy(),writable,runtime,proof.generation,proof.revision])?;
        tx.commit()?;
        let project = Project {
            thread: thread.into(),
            root,
            writable,
            verified: true,
            source_runtime: runtime.into(),
            source_generation: proof.generation,
            permission_revision: proof.revision,
        };
        if let Some(i) = existing {
            self.projects[i] = project;
        } else {
            self.projects.push(project);
        }
        self.sync_grants()?;
        Ok(())
    }
    fn sync_grants(&self) -> Result<()> {
        self.executor.replace_directory_grants(
            self.projects
                .iter()
                .filter(|p| p.verified)
                .map(|p| DirectoryGrant {
                    root: p.root.clone(),
                    writable: p.writable,
                })
                .chain(std::iter::once(DirectoryGrant {root:self.workspace.clone(),writable:true}))
                .collect(),
        )
    }
    fn revalidate_pending_projects(&mut self) -> Result<()> {
        let mut pending = self
            .service
            .tasks()?
            .into_iter()
            .filter(|t| t.status == TaskStatus::Queued)
            .map(|t| t.directory)
            .collect::<Vec<_>>();
        pending.extend(
            self.service
                .schedules()
                .into_iter()
                .filter(|s| s.next_ms.is_some_and(|at| at <= (self.clock)()))
                .map(|s| s.spec.directory),
        );
        pending.extend(
            self.service
                .deliveries()
                .into_iter()
                .filter(|d| matches!(d.status.as_str(), "queued" | "sending" | "unknown"))
                .map(|d| d.directory),
        );
        self.revalidate_project_paths(&pending)
    }
    fn revalidate_project_paths(&mut self, pending: &[PathBuf]) -> Result<()> {
        for project in &mut self.projects {
            if !pending
                .iter()
                .any(|root| physical(root).is_ok_and(|d| within(&d, &project.root)))
            {
                continue;
            }
            match verify_project(
                &mut self.probe,
                &project.source_runtime,
                Some(&project.source_generation),
                &project.thread,
                &project.root,
            ) {
                Ok((root, writable, proof)) => {
                    project.root = root;
                    project.verified = true;
                    project.writable &= writable;
                    project.permission_revision = proof.revision;
                    self.messages.execute(
                        "UPDATE product_projects SET writable=?1,permission_revision=?4 WHERE thread_id=?2 AND root=?3",
                        params![
                            project.writable,
                            project.thread,
                            project.root.to_string_lossy()
                            ,project.permission_revision
                        ],
                    )?;
                }
                Err(_) => project.verified = false,
            }
        }
        self.sync_grants()
    }
    fn pause_unverified_tasks(&mut self) -> Result<()> {
        for task in self
            .service
            .tasks()?
            .into_iter()
            .filter(|t| t.status == TaskStatus::Queued)
        {
            if physical(&task.directory).is_ok_and(|d|d==self.workspace) {continue;}
            if !self.projects.iter().any(|p| {
                p.verified
                    && (!task.writes || p.writable)
                    && physical(&task.directory).is_ok_and(|d| within(&d, &p.root))
            }) {
                self.service.apply(
                    &format!("product-project-check:{}", task.id),
                    Command::PauseTask { task_id: task.id },
                    (self.clock)(),
                )?;
            }
        }
        Ok(())
    }
    fn apply(&mut self, r: &Request) -> Result<Value> {
        if r.method=="assistant/artifact/read" {
            ensure!(r.parameters.as_object().is_some_and(|o|o.keys().all(|k|["artifactId","offset"].contains(&k.as_str()))) && r.parameters.get("offset").is_none_or(|v|v.as_u64().is_some()),"Invalid artifact read");
            return self.artifact_read(&r.parameters);
        }
        if is_read(&r.method) {
            ensure!(
                r.parameters.as_object().is_some_and(|p| p
                    .keys()
                    .all(|k| ["after", "before", "limit", "taskId"].contains(&k.as_str())))
                    && ["after", "before"]
                        .iter()
                        .all(|k| r.parameters.get(k).is_none_or(|v| v.as_u64().is_some()))
                    && r.parameters
                        .get("limit")
                        .is_none_or(|v| v.as_u64().is_some_and(|n| (1..=100).contains(&n)))
                    && r.parameters
                        .get("taskId")
                        .is_none_or(|v| v.as_str().is_some_and(|id| !id.is_empty()
                            && id.len() <= 256
                            && id
                                .bytes()
                                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_')))),
                "Invalid assistant read cursor"
            );
            return Ok(Value::Null);
        }
        let restrictive = matches!(
            r.method.as_str(),
            "assistant/pause" | "assistant/takeover" | "assistant/task/pause"
        );
        ensure!(
            self.allowed() || restrictive,
            "Computer helper updating; new assistant work fenced"
        );
        let permit = if restrictive { None } else { self.admit()? };
        let result = self.apply_inner(r);
        self.finish_permit(permit);
        result
    }
    fn apply_inner(&mut self, r: &Request) -> Result<Value> {
        let p = &r.parameters;
        let at = (self.clock)();
        match r.method.as_str() {
            "assistant/send" => {
                let message = text(p, "message")?;
                let mut inputs:Vec<crate::assistant::AssistantInput>=p.get("retainedAttachments").map(|v|serde_json::from_value(v.clone())).transpose()?.unwrap_or_default();
                crate::assistant::inputs::validate_inputs(&inputs)?;
                let expanded=if let Some(artifact)=p["artifactId"].as_str() {
                    let context=self.artifact_context(artifact,text(p,"artifactVersion")?,text(p,"taskId")?)?;
                    if let Some(image)=self.artifact_image_input(artifact)? {inputs.push(image);}
                    Some(format!("{}\n\n{}",message,context))
                } else {None};
                let submitted=expanded.as_deref().unwrap_or(message);
                ensure!(submitted.len()<=65536,"Result context and follow-up exceed input limit");
                self.account = self
                    .probe
                    .account()
                    .unwrap_or(AccountReadiness::Unavailable);
                self.account_checked_at = at;
                ensure!(
                    self.account == AccountReadiness::Ready,
                    "{}",
                    self.account
                        .error()
                        .unwrap_or("assistant_account_unavailable")
                );
                let h = self.probe.health()?;
                ensure!(
                    h.online
                        && h.initialized
                        && h.protocol_verified
                        && h.runtime_id == self.runtime_id
                        && h.generation == self.generation,
                    "Assistant native runtime unavailable/unsupported"
                );
                if let Some(root) = &r.directory {
                    let source = p["sourceRuntimeId"]
                        .as_str()
                        .or_else(|| p["runtimeId"].as_str())
                        .unwrap_or(&self.runtime_id)
                        .to_owned();
                    self.authorize(&source, None, text(p, "threadId")?, root, true)?;
                }
                if p["memberId"].is_string() || p["taskId"].is_string() {
                    self.service.submit_targeted_with_inputs(
                        &r.id,
                        submitted,
                        p["memberId"].as_str(),
                        p["taskId"].as_str(),
                        false,
                        inputs,
                        at,
                    )?;
                } else {
                    self.service.submit_with_inputs(
                        &r.id,
                        message,
                        vec![r.directory.clone().unwrap_or_else(||self.workspace.clone())],
                        inputs,
                        at,
                    )?;
                }
                let tx = self.messages.transaction()?;
                tx.execute(
                    "INSERT OR IGNORE INTO messages(id,text,at_ms) VALUES(?1,?2,?3)",
                    params![r.id, message, at],
                )?;
                tx.execute("INSERT OR IGNORE INTO product_timeline(id,role,text,at_ms) VALUES(?1,'User',?2,?3)",params![format!("user:{}",r.id),message,at])?;
                tx.commit()?;
            }
            "assistant/pause" => self.service.pause(&r.id, at)?,
            "assistant/takeover" => {
                self.service.pause(&r.id, at)?;
                self.reconcile_only();
                ensure!(
                    self.service.is_paused()
                        && !self.uncertain()
                        && self.service.operations().iter().all(|op| {
                            matches!(
                                op.phase,
                                OperationPhase::Paused
                                    | OperationPhase::Completed
                                    | OperationPhase::Failed
                            )
                        }),
                    "Assistant paused; native stop must be confirmed before manual write"
                );
            }
            "assistant/resume" => {
                if let Some(operation)=p["operationId"].as_str() {
                    let selected=self.service.coordinator_handoff_recovery_directories(operation)?;
                    self.revalidate_project_paths(&selected)?;
                    self.service.resume_coordinator_handoff(&r.id,operation,at)?;
                } else {self.service.release_takeover(&r.id,at)?;}
            }
            "assistant/task/memory" => self.service.set_task_memories(&r.id,text(p,"taskId")?,p["enabled"].as_bool().context("Missing memory choice")?)?,
            "assistant/task/pause" => {
                self.service.apply(
                    &r.id,
                    Command::PauseTask {
                        task_id: text(p, "taskId")?.into(),
                    },
                    at,
                )?;
            }
            "assistant/task/resume" | "assistant/v2/task/retry" => {
                let task_id = text(p, "taskId")?;
                let task = self
                    .service
                    .tasks()?
                    .into_iter()
                    .find(|t| t.id == task_id)
                    .context("Task not found")?;
                // Explicit resume cannot reuse permission evidence captured
                // before a native project policy change.
                if !physical(&task.directory).is_ok_and(|d|d==self.workspace) {
                let project = self
                    .projects
                    .iter()
                    .find(|p| physical(&task.directory).is_ok_and(|d| within(&d, &p.root)))
                    .context("Reauthorize project before resuming task")?;
                let (thread, root, writable, source, source_generation) = (
                    project.thread.clone(),
                    project.root.clone(),
                    project.writable,
                    project.source_runtime.clone(),
                    project.source_generation.clone(),
                );
                self.authorize(&source, Some(&source_generation), &thread, &root, writable)?;
                ensure!(
                    self.projects.iter().any(|project| project.verified
                        && (!task.writes || project.writable)
                        && physical(&task.directory)
                            .is_ok_and(|root| within(&root, &project.root))),
                    "Reauthorize project before resuming task"
                );
                }
                self.service.apply(
                    &r.id,
                    if r.method=="assistant/v2/task/retry" {Command::RetryTask {task_id:task_id.into()}}
                    else {Command::ResumeTask {task_id:task_id.into()}},
                    at,
                )?;
            }
            "assistant/memory/add" => {
                self.service.remember_with_source(&r.id,text(p,"text")?,&format!("command:{}",r.id),p["scope"].as_str().unwrap_or("personal"),false,at)?;
            }
            "assistant/memory/undo" => self.service.undo_memory(&r.id,text(p,"memoryId")?,at)?,
            "assistant/v2/task/seen" => self.service.mark_task_seen("local",text(p,"taskId")?,p["throughSequence"].as_u64().context("Missing activity cursor")?)?,
            "assistant/memory/update" => {
                self.service.apply(
                    &r.id,
                    Command::EditMemory {
                        memory_id: text(p, "memoryId")?.into(),
                        text: text(p, "text")?.into(),
                    },
                    at,
                )?;
            }
            "assistant/memory/delete" => {
                self.service.apply(
                    &r.id,
                    Command::DeleteMemory {
                        memory_id: text(p, "memoryId")?.into(),
                    },
                    at,
                )?;
            }
            "assistant/settings/update" => {
                let settings: Settings = serde_json::from_value(p["settings"].clone())?;
                settings.validate()?;
                let _: chrono_tz::Tz = settings
                    .timezone
                    .parse()
                    .map_err(|_| anyhow::anyhow!("Unknown timezone"))?;
                self.service.update_settings(&r.id, settings, at)?;
            }
            "assistant/respond" => {
                let operation = text(p, "operationId")?;
                let request = text(p, "requestId")?;
                let response: HumanResponse = serde_json::from_value(p["response"].clone())?;
                if response == (HumanResponse::Approval { approved:false }) {
                    if self.service.tool_rejection_receipt(&r.id, operation, request, None)? {
                        return Ok(json!({"accepted":true,"commandId":r.id}));
                    }
                    let op = self.service.operations().into_iter().find(|o| o.id == operation)
                        .context("Unowned operation")?;
                    let scope = op.mapping.context("Missing native mapping")?;
                    let pending = self.executor.pending_requests()?.into_iter()
                        .find(|q| q.operation_id == operation && q.handle == request);
                    if pending.as_ref().is_some_and(|q| q.method == "item/tool/call")
                        || (op.role == Role::Coordinator && matches!(op.phase, OperationPhase::Completed | OperationPhase::Failed))
                    {
                        self.service.tool_rejection_receipt(&r.id, operation, request, Some(false))?;
                        self.executor.reject_retained_tool(scope, request)?;
                        self.service.tool_rejection_receipt(&r.id, operation, request, Some(true))?;
                        return Ok(json!({"accepted":true,"commandId":r.id}));
                    }
                }
                let pending = self
                    .executor
                    .pending_requests()?
                    .into_iter()
                    .find(|q| q.operation_id == operation && q.handle == request);
                let op = self
                    .service
                    .operations()
                    .into_iter()
                    .find(|op| op.id == operation);
                let history = safe_answer_history(
                    operation,
                    request,
                    pending.as_ref(),
                    op.as_ref(),
                    &response,
                    at,
                );
                self.service
                    .respond(&r.id, operation, request, response, at)?;
                if let Some(history) = history {
                    self.messages.execute(
                        "INSERT OR IGNORE INTO product_question_history(id,data) VALUES(?1,?2)",
                        params![
                            history["id"].as_str().unwrap(),
                            serde_json::to_string(&history)?
                        ],
                    )?;
                }
            }
            "assistant/schedule/delete" => self
                .service
                .remove_schedule(&r.id, text(p, "scheduleId")?)?,
            "assistant/schedule/pause" | "assistant/schedule/resume" => self.service.set_schedule_paused(&r.id,text(p,"scheduleId")?,r.method.ends_with("/pause"),at)?,
            "assistant/schedule/run" => {
                let schedule=self.service.schedules().into_iter().find(|s|Some(s.id.as_str())==p["scheduleId"].as_str()).context("Schedule not found")?;
                self.revalidate_project_paths(std::slice::from_ref(&schedule.spec.directory))?;
                self.executor.clone().validate_directory(&schedule.spec.directory,schedule.spec.writes)?;
                self.service.run_schedule(&r.id,&schedule.id,at)?;
            }
            "assistant/schedule/create" | "assistant/schedule/update" => {
                let spec=&p["spec"];
                let existing=if r.method.ends_with("/update") {Some(self.service.schedules().into_iter().find(|s|Some(s.id.as_str())==p["scheduleId"].as_str()).context("Schedule not found")?)} else {None};
                let root=existing.as_ref().map(|s|s.spec.directory.clone()).or_else(||r.directory.clone()).unwrap_or_else(||self.workspace.clone());
                let requested_writes=spec["writes"].as_bool().context("Missing writes choice")?;
                let writes=root==self.workspace || requested_writes;
                if let Some(selected)=&r.directory {
                    let source=p["sourceRuntimeId"].as_str().or_else(||p["runtimeId"].as_str()).unwrap_or(&self.runtime_id).to_owned();
                    self.authorize(&source,None,text(p,"threadId")?,selected,writes)?;
                } else if root!=self.workspace {
                    self.revalidate_project_paths(std::slice::from_ref(&root))?;
                }
                self.executor.clone().validate_directory(&root,writes)?;
                let zone=zone_rules(spec["zone"]["name"].as_str().unwrap_or("Asia/Shanghai"),at)?;
                let updated=ScheduleSpec {kind:serde_json::from_value(spec["kind"].clone())?,zone,prompt:text(spec,"prompt")?.into(),directory:root,writes};
                if let Some(existing)=existing {
                    self.service.update_schedule(&r.id,&existing.id,updated,p["missedPolicy"].as_str().unwrap_or(&existing.missed_policy),at)?;
                } else {
                    self.service.add_schedule(&r.id,updated,at)?;
                }
            }
            _ => anyhow::bail!("Unsupported assistant action"),
        }
        Ok(json!({"accepted":true,"commandId":r.id}))
    }
    fn reconcile_only(&mut self) {
        for op in self.service.operations().into_iter().filter(|o| {
            matches!(
                o.phase,
                OperationPhase::Unknown | OperationPhase::Running | OperationPhase::Waiting
            )
        }) {
            let mut executor = self.executor.clone();
            if let Ok(status) = executor.status(StatusRequest {
                operation_id: op.id.clone(),
                mapping: op.mapping,
                host_generation: op.expected_generation,
            }) {
                if let Ok(hash) = digest(&status) {
                    let _ = self.service.observe(
                        &op.id,
                        status,
                        &format!("worker-reconcile:{}:{hash}", op.id),
                        (self.clock)(),
                    );
                }
            }
        }
        self.confirm_known();
    }
    fn observe_runtime(&mut self) {
        self.error = None;
        let health = self.probe.health();
        self.startup_runtime_ready = health.as_ref().is_ok_and(|h| {
            h.online
                && h.initialized
                && h.runtime_id == self.runtime_id
                && h.generation == self.generation
        });
        self.capabilities_ready = health.as_ref().is_ok_and(|h| h.protocol_verified);
        self.runtime_ready = health.is_ok_and(|h| {
            h.online
                && h.initialized
                && h.protocol_verified
                && h.runtime_id == self.runtime_id
                && h.generation == self.generation
        });
        if !self.runtime_ready {
            self.error = Some("runtime_unavailable_or_unsupported");
        }
        let at = (self.clock)();
        if self.startup_runtime_ready && at.saturating_sub(self.account_checked_at) >= 5000 {
            self.account = self
                .probe
                .account()
                .unwrap_or(AccountReadiness::Unavailable);
            self.account_checked_at = at;
        }
        match self.executor.poll_events(256) {
            Ok(projection) => {
                for stream in projection.streams {
                    if self.service.operations().iter().any(|o| {
                        o.id == stream.operation_id
                            && (o.role == Role::Coordinator || self.service.direct_result(o))
                    }) {
                        if self.messages.execute("INSERT INTO product_timeline(id,role,text,at_ms,operation_id) VALUES(?1,'Assistant',?2,?3,?4) ON CONFLICT(id) DO UPDATE SET text=excluded.text",
                            params![format!("assistant-operation:{}",stream.operation_id),stream.text,(self.clock)(),stream.operation_id]).is_err() {
                            self.error = Some("timeline_unavailable");
                        }
                    }
                }
                if projection.pending_snapshot {
                    match self.executor.pending_requests() {
                        Ok(pending) => {
                            self.deferred.retain(|_, tool| {
                                pending.iter().any(|p| {
                                    p.mapping == tool.scope
                                        && p.call_id.as_deref() == Some(tool.call_id.as_str())
                                })
                            });
                            for op in self.service.operations() {
                                if let (Some(scope), Some(call)) = (op.mapping, op.input_tool_call)
                                {
                                    if scope.host_generation!=self.generation {continue;}
                                    if !pending.iter().any(|p| {
                                        p.mapping == scope
                                            && p.call_id.as_deref() == Some(call.as_str())
                                    }) && self
                                        .service
                                        .observe_tool_resolved(&scope, &call, (self.clock)())
                                        .is_err()
                                    {
                                        self.error =
                                            Some("native_observation_requires_reconciliation");
                                    }
                                }
                            }
                        }
                        Err(_) => self.error = Some("native_observation_requires_reconciliation"),
                    }
                }
                for (scope, call) in projection.resolved_tools {
                    self.deferred
                        .retain(|_, tool| tool.scope != scope || tool.call_id != call);
                    if self
                        .service
                        .observe_tool_resolved(&scope, &call, (self.clock)())
                        .is_err()
                    {
                        self.error = Some("native_observation_requires_reconciliation");
                    }
                }
                for event in projection.observations {
                    if self
                        .service
                        .observe(
                            &event.operation_id,
                            event.status,
                            &event.event_id,
                            (self.clock)(),
                        )
                        .is_err()
                    {
                        self.error = Some("native_observation_requires_reconciliation");
                    }
                }
                for tool in projection.tools {
                    self.deferred
                        .insert(format!("{}:{}", tool.operation_id, tool.call_id), tool);
                }
            }
            Err(_) => self.error = Some("runtime_reconnecting"),
        }
        self.reconcile_only();
        if self.executor.reconcile_answer_receipts().is_err() {
            self.error = Some("native_tool_answer_requires_reconciliation");
        }
        self.confirm_known();
        if self.service.reconcile_deliveries().is_err() {
            self.error = Some("delivery_requires_reconciliation");
        }
        if self.sync_timeline().is_err() {
            self.error = Some("timeline_unavailable");
        }
    }
    fn dispatch_pending(&mut self) {
        if self.allowed() && self.runtime_ready {
            for (id, tool) in self.deferred.clone() {
                if self.service.operations().iter().any(|o| o.id == tool.operation_id
                    && matches!(o.phase, OperationPhase::Completed | OperationPhase::Failed | OperationPhase::Paused)) {
                    self.error = Some("retained_native_request_requires_response");
                    continue;
                }
                if self.service.operations().iter().any(|o| {
                    o.id == tool.operation_id && o.input_tool_call.as_deref() == Some(&tool.call_id)
                }) {
                    continue;
                }
                if !self.allowed() {
                    break;
                }
                let Ok(permit) = self.admit() else {
                    break;
                };
                let result = match tool.call.clone() {
                    Some(call) => {
                        let validation =
                            if let ToolCall::Housekeeping(HousekeepingTool::ScheduleCreate {
                                directory,
                                ..
                            }) = &call
                            {
                                self.revalidate_project_paths(std::slice::from_ref(directory))
                            } else {
                                Ok(())
                            };
                        validation.and_then(|_| {
                            self.service.tool(
                                tool.scope.clone(),
                                &tool.call_id,
                                call,
                                (self.clock)(),
                            )
                        })
                    }
                    None => Err(anyhow::anyhow!(
                        tool.error.clone().unwrap_or("Invalid native tool".into())
                    )),
                };
                match result {
                    Ok(_) => {
                        self.deferred.remove(&id);
                        self.local_receipts_pending.remove(&id);
                    }
                    Err(error) => {
                        let committed = tool.call.as_ref().is_some_and(|call| {
                            self.service
                                .tool_effect_committed(&tool.scope, &tool.call_id, call)
                                .unwrap_or(true)
                        });
                        if committed {
                            self.local_receipts_pending.insert(id.clone());
                            self.error = Some("local_tool_receipt_pending");
                        } else if self
                            .executor
                            .has_tool_answer_attempt(&tool.scope, &tool.call_id)
                            .unwrap_or(true)
                        {
                            self.error = Some("native_tool_answer_unknown");
                        } else if self
                            .executor
                            .reject_tool(tool.scope.clone(), &tool.call_id, &error.to_string())
                            .is_ok()
                        {
                            self.deferred.remove(&id);
                        }
                    }
                }
                self.finish_permit(permit);
            }
            if self.allowed()
                && self.account == AccountReadiness::Ready
                && self.runtime_ready
            {
                if let Ok(permit) = self.admit() {
                    if self
                        .revalidate_pending_projects()
                        .and_then(|_| self.pause_unverified_tasks())
                        .and_then(|_| self.service.tick((self.clock)()))
                        .is_err()
                    {
                        self.error = Some("service_reconciliation_required");
                    }
                    // A typed pre-dispatch rejection can be resolved locally
                    // in this same response without polling every live turn.
                    if self
                        .service
                        .operations()
                        .iter()
                        .any(|o| o.phase == OperationPhase::Unknown)
                    {
                        self.reconcile_only();
                    }
                    self.confirm_known();
                    self.finish_permit(permit);
                }
            }
        }
        if !self.allowed() {
            self.error = Some("handoff_fenced");
        }
        if self.uncertain() {
            self.error = Some("service_reconciliation_required");
        }
        if self.probe.tracking.lock().unwrap().unsupported.is_some() {
            self.error = Some("native_protocol_unsupported");
        }
        let pending = self.executor.pending_requests().unwrap_or_default();
        self.deferred.retain(|_, t| {
            pending
                .iter()
                .any(|p| p.mapping == t.scope && p.call_id.as_deref() == Some(&t.call_id))
        });
        self.local_receipts_pending
            .retain(|id| self.deferred.contains_key(id));
        self.confirm_known();
        if self.sync_timeline().is_err() {
            self.error = Some("timeline_unavailable");
        }
        self.revision += 1;
    }
    fn snapshot(&mut self, override_error: Option<&str>) -> Result<Value> {
        self.sync_timeline()?;
        self.capture_artifacts()?;
        let value = snapshot_value(
            &self.service,
            &self.executor,
            &self.messages,
            &self.runtime_id,
            override_error.or(self.error),
        )?;
        let mut value = value;
        artifacts::project(&self.messages,&mut value)?;
        self.service.record_artifact_versions(value["artifacts"].as_array().map(Vec::as_slice).unwrap_or(&[]))?;
        value["domain"]=self.service.domain_snapshot("local")?;
        value["fresh"] = json!(true);
        value["snapshotSource"] = json!("worker_read");
        value["updatedAtMs"] = json!((self.clock)());
        value["revision"] = json!(self.revision);
        value["runtimeEventCursor"] = json!(self.executor.cursor()?);
        value["runtimeReady"] = json!(self.runtime_ready);
        value["executorGenerationReady"] = json!(self.executor.current_generation_ready(&self.generation)?);
        value["reconciliationRequired"] = json!(self.uncertain());
        value["unconfirmedWrites"] = json!(self.permits.len() + self.recovered_permits.len());
        value["ready"] = json!(
            self.runtime_ready
                && self.account == AccountReadiness::Ready
                && self.executor.current_generation_ready(&self.generation)?
                && override_error.is_none()
        );
        value["accountReady"] = json!(self.account == AccountReadiness::Ready);
        value["acceptingMessages"]=json!(self.allowed() && self.account==AccountReadiness::Ready);
        value["protocolVersion"]=json!(2);
        if value["ready"]==true && value["error"]=="service_reconciliation_required" {value["error"]=Value::Null;}
        if self.startup_runtime_ready && self.account.error().is_some() {
            value["error"] = json!(self.account.error());
        }
        value["startupStages"] = json!([
            {"id":"runtime","status":if self.startup_runtime_ready {"ready"}else{"blocked"},"recoveryAction":if self.startup_runtime_ready {Value::Null}else{json!("inspect_runtime_host_startup")}},
            {"id":"account","status":if !self.startup_runtime_ready {"waiting"}else if self.account == AccountReadiness::Ready {"ready"}else{"blocked"},"recoveryAction":self.account.error().map(|e|if e == "assistant_needs_login" {"login_with_codex_cli"}else{"check_cli_account_status"})},
            {"id":"capabilities","status":if !self.startup_runtime_ready {"waiting"}else if self.capabilities_ready {"ready"}else{"blocked"},"recoveryAction":if self.capabilities_ready {Value::Null}else{json!("update_native_codex_protocol")}},
            {"id":"coordinator","status":if !self.runtime_ready || self.account != AccountReadiness::Ready {"waiting"}else if self.uncertain() {"reconciling"}else if self.service.is_paused() {"paused"}else if self.error.is_some() {"blocked"}else{"ready"},"sessionCreated":self.service.coordinator_thread_id().is_some(),"recoveryAction":if self.uncertain() {json!("reconcile_retained_native_receipts")}else if self.service.is_paused() {json!("resume_assistant")}else if self.error.is_some() {json!("inspect_runtime_host_and_retry")}else{Value::Null}}
        ]);
        value["unsupportedOperation"] = json!(self.probe.tracking.lock().unwrap().unsupported);
        if self.runtime_ready && self.account==AccountReadiness::Ready && !self.uncertain() {
            for record in self.executor.operation_records()? {
                if record.rejected && !record.thread_submitted && !record.turn_submitted && record.mapping.is_none()
                    && self.service.coordinator_handoff_recovery_directories(&record.operation_id).is_ok() {
                    if let Some(op)=value["operations"].as_array_mut().and_then(|ops|ops.iter_mut().find(|op|op["id"]==record.operation_id)) {
                        op["handoffRecovery"]=json!({"action":"resume_original_handoff","operationId":record.operation_id,
                            "messageIds":op["user_message_ids"],"generation":record.generation});
                    }
                }
            }
        }
        value["projectAuthorization"]=json!(self.projects.iter().map(|p|json!({"threadId":p.thread,"directory":p.root,"writable":p.writable,"verified":p.verified,"sourceRuntimeId":p.source_runtime,"sourceGeneration":p.source_generation,"permissionRevision":p.permission_revision})).collect::<Vec<_>>());
        for p in self.executor.pending_requests()? {
            if self.executor.rejection_recovery_available(&p)? {
                if let Some(v) = value["pendingRequests"].as_array_mut().and_then(|items| items.iter_mut()
                    .find(|v| v["operationId"] == p.operation_id && v["requestId"] == p.handle)) {
                    v["recoveryAction"] = json!("reject_original_tool_answer");
                }
            }
        }
        Ok(value)
    }
}
fn verify_project<R: RuntimeIo>(
    io: &mut R,
    runtime: &str,
    generation: Option<&str>,
    thread: &str,
    expected: &Path,
) -> Result<(PathBuf, bool, ProjectPermissionProof)> {
    ensure!(
        !thread.is_empty() && thread.len() <= 256,
        "Invalid authorized project thread"
    );
    let expected = physical(expected)?;
    let before = io.health()?;
    ensure!(
        before.online && before.initialized && before.protocol_verified,
        "Assistant Host permission boundary unavailable"
    );
    let actual = if runtime == before.runtime_id {
    let result = io.request(
        read_id(),
        "thread/read",
        json!({"threadId":thread,"includeTurns":false}),
    )?;
    ensure!(
        result["thread"]["id"].as_str() == Some(thread),
        "Project thread identity changed"
    );
    let cwd = result["thread"]["cwd"]
        .as_str()
        .context("Native project cwd unavailable")?;
    physical(Path::new(cwd))?
    } else {
        // The controller supplied this root from the selected source runtime's
        // native thread/read. Its registered permission reader rechecks that
        // source's owner, cwd, policy and generation below; the assistant Host
        // must not be asked to resolve another runtime's thread namespace.
        expected.clone()
    };
    ensure!(
        within(&actual, &expected) && within(&expected, &actual),
        "Native project cwd changed; reauthorize"
    );
    let proof = io.project_permissions(runtime, thread, &actual)?;
    ensure!(
        !runtime.is_empty()
            && proof.runtime_id == runtime
            && proof.thread_id == thread
            && !proof.generation.is_empty()
            && proof.generation.len() <= 256
            && generation.is_none_or(|g| !g.is_empty() && g == proof.generation)
            && !proof.revision.is_empty()
            && proof.revision.len() <= 256,
        "Source permission generation/thread/revision mismatch; reauthorize the project"
    );
    let source_cwd = physical(&proof.cwd)?;
    ensure!(
        within(&source_cwd, &actual)
            && within(&actual, &source_cwd)
            && physical(&expected)? == actual,
        "Source permission physical cwd changed; reauthorize the project"
    );
    let after = io.health()?;
    ensure!(
        after.online
            && after.initialized
            && after.protocol_verified
            && after.runtime_id == before.runtime_id
            && after.generation == before.generation,
        "Assistant Host changed while reading project permissions"
    );
    let writable = project_writable(&proof.settings);
    Ok((actual, writable, proof))
}
pub(crate) fn project_writable(settings: &Value) -> bool {
    let policy = crate::settings::canonical(settings);
    policy["activePermissionProfile"]["id"].is_null()
        && matches!(
            policy["sandboxPolicy"]["type"].as_str(),
            Some("workspaceWrite" | "dangerFullAccess")
        )
}
fn text<'a>(v: &'a Value, field: &str) -> Result<&'a str> {
    v[field]
        .as_str()
        .filter(|s| !s.is_empty() && s.len() <= 65536 && !s.contains('\0'))
        .context("Missing assistant field")
}
/// Only a public, positively acknowledged answer is retained by the product UI.
/// Host recovery journals continue to store receipt identity, never answer payloads.
fn safe_answer_history(
    operation: &str,
    request: &str,
    pending: Option<&PendingRequest>,
    op: Option<&Operation>,
    response: &HumanResponse,
    at: i64,
) -> Option<Value> {
    let HumanResponse::Answer(answer) = response else {
        return None;
    };
    let (questions, answers) = if let Some(pending) =
        pending.filter(|p| p.method == "item/tool/requestUserInput")
    {
        let questions = pending.params["questions"].as_array()?.clone();
        let normalized = normalize_input_answers(&questions, answer).ok()?;
        let mut answers = serde_json::Map::new();
        for question in &questions {
            let id = question["id"].as_str()?;
            let secret = question["isSecret"].as_bool().unwrap_or(true);
            let value = if secret {
                json!({"answers":["敏感回答已隐藏"]})
            } else {
                normalized.get(id)?.clone()
            };
            answers.insert(id.into(), value);
        }
        (questions, Value::Object(answers))
    } else if let Some(op) = op.filter(|op| op.input_tool_call.is_some()) {
        let question = op.question.as_ref()?;
        (
            vec![
                json!({"id":request,"header":"问题","question":question.text,"isOther":true,"isSecret":false,"options":null}),
            ],
            json!({request:{"answers":[answer]}}),
        )
    } else {
        return None;
    };
    Some(
        json!({"id":format!("answered:{operation}:{request}"),"operationId":operation,"requestId":request,"atMs":at,
        "item":{"id":format!("answered:{operation}:{request}"),"type":"dynamicToolCall","tool":"request_user_input","arguments":{"questions":questions},"status":"completed","success":true,"contentItems":[{"type":"inputText","text":json!({"answers":answers}).to_string()}]}}),
    )
}
fn attribute_messages<R: RuntimeIo>(
    service: &AssistantService<SharedHostExecutor<R>>,
    chat: &mut [Value],
) -> Result<()> {
    let operations = service.operations();
    let members = service.members();
    for message in chat {
        let operation = message["operationId"]
            .as_str()
            .and_then(|id| operations.iter().find(|op| op.id == id));
        let entry = message["id"]
            .as_str()
            .and_then(|id| service.conversation_entry(id));
        let task = operation.and_then(|op| op.task_id.as_deref());
        let assignment = operation.and_then(|op| service.operation_assignment(&op.id));
        let member_id = assignment
            .and_then(|a| a.member_id.as_deref())
            .or_else(|| entry.and_then(|e| e.member_id.as_deref()));
        message["memberId"] = json!(member_id);
        message["authorName"] = json!(
            member_id
                .and_then(|id| members.iter().find(|m| m.id == id))
                .map(|m| &m.name)
        );
        message["taskId"] = json!(task.or_else(|| entry.and_then(|e| e.task_id.as_deref())));
        message["groupId"] = json!(
            assignment
                .and_then(|a| a.group_id.as_deref())
                .or_else(|| entry.and_then(|e| e.group_id.as_deref()))
        );
        message["sourceThreadId"] = json!(
            operation
                .and_then(|op| op.mapping.as_ref())
                .map(|m| &m.thread_id)
        );
    }
    Ok(())
}
fn timeline_page<R: RuntimeIo>(
    service: &AssistantService<SharedHostExecutor<R>>,
    db: &Connection,
    before: Option<u64>,
    limit: usize,
) -> Result<Value> {
    ensure!(
        (1..=100).contains(&limit),
        "Timeline page limit must be 1..100"
    );
    let before = before.unwrap_or(i64::MAX as u64).min(i64::MAX as u64) as i64;
    let mut stmt=db.prepare("SELECT sequence,id,role,text,at_ms,operation_id,source_sequence FROM product_timeline WHERE sequence<?1 ORDER BY sequence DESC LIMIT ?2")?;
    let mut chat=stmt.query_map(params![before,limit as i64],|r|Ok(json!({"sequence":r.get::<_,i64>(0)?,"id":r.get::<_,String>(1)?,"role":r.get::<_,String>(2)?,"text":r.get::<_,String>(3)?,"atMs":r.get::<_,i64>(4)?,"operationId":r.get::<_,Option<String>>(5)?,"sourceEventSequence":r.get::<_,Option<u64>>(6)?})))?.collect::<rusqlite::Result<Vec<_>>>()?;
    chat.reverse();
    attribute_messages(service, &mut chat)?;
    let cursor = chat.first().and_then(|v| v["sequence"].as_u64());
    let more = cursor.is_some_and(|cursor| {
        db.query_row(
            "SELECT EXISTS(SELECT 1 FROM product_timeline WHERE sequence<?1)",
            [cursor],
            |r| r.get::<_, bool>(0),
        )
        .unwrap_or(false)
    });
    Ok(json!({"messages":chat,"nextBefore":cursor.filter(|_|more)}))
}
fn snapshot_value<R: RuntimeIo>(
    service: &AssistantService<SharedHostExecutor<R>>,
    executor: &SharedHostExecutor<R>,
    messages: &Connection,
    runtime: &str,
    error: Option<&str>,
) -> Result<Value> {
    let operations = service.operations();
    let mut chat = if messages
        .prepare("SELECT id FROM product_timeline LIMIT 1")
        .is_ok()
    {
        let mut stmt=messages.prepare("SELECT sequence,id,role,text,at_ms,operation_id,source_sequence FROM (SELECT * FROM product_timeline ORDER BY at_ms DESC,sequence DESC LIMIT 200) ORDER BY at_ms,sequence")?;
        stmt.query_map([],|r|Ok(json!({"sequence":r.get::<_,i64>(0)?,"id":r.get::<_,String>(1)?,"role":r.get::<_,String>(2)?,"text":r.get::<_,String>(3)?,"atMs":r.get::<_,i64>(4)?,"operationId":r.get::<_,Option<String>>(5)?,"sourceEventSequence":r.get::<_,Option<u64>>(6)?})))?.collect::<rusqlite::Result<Vec<_>>>()?
    } else {
        let mut stmt = messages
            .prepare("SELECT id,text,at_ms FROM messages ORDER BY at_ms,rowid LIMIT 200")?;
        stmt.query_map([],|r|Ok(json!({"id":r.get::<_,String>(0)?,"role":"User","text":r.get::<_,String>(1)?,"atMs":r.get::<_,i64>(2)?})))?.collect::<rusqlite::Result<Vec<_>>>()?
    };
    let members = service.members();
    let assignments = service.assignments()?;
    attribute_messages(service, &mut chat)?;
    let first = chat.iter().filter_map(|v| v["sequence"].as_u64()).min();
    let next_before = first.filter(|sequence| {
        messages
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM product_timeline WHERE sequence<?1)",
                [sequence],
                |r| r.get::<_, bool>(0),
            )
            .unwrap_or(false)
    });
    let pending=executor.pending_requests()?.into_iter().map(|p|json!({"operationId":p.operation_id,"requestId":p.handle,"method":p.method,"params":p.params})).collect::<Vec<_>>();
    let events = service.latest_events(256)?;
    let question_history = messages
        .prepare("SELECT data FROM product_question_history ORDER BY rowid DESC LIMIT 200")?
        .query_map([], |r| r.get::<_, String>(0))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .into_iter()
        .map(|s| serde_json::from_str::<Value>(&s))
        .collect::<std::result::Result<Vec<_>, _>>()?;
    Ok(
        json!({"enabled":true,"ready":error.is_none(),"runtimeId":runtime,"coordinatorThreadId":service.coordinator_thread_id(),"assistantId":service.assistant_id(),"paused":service.is_paused(),"settings":service.settings(),"tasks":service.tasks()?,"members":members,"groups":service.groups()?,"assignments":assignments,"deliveries":service.deliveries(),"questionHistory":question_history,"operations":operations,"events":events,"eventCursor":events.last().map(|e|e.sequence),"memories":service.memories()?,"memoryMetadata":service.memory_metadata(),"memoryUsage":service.memory_usage(),"memoryDisabledTasks":service.memory_disabled_tasks(),"schedules":service.schedules(),"scheduleRuns":service.schedule_runs()?,"artifacts":service.artifacts(),"messages":chat,"nextBefore":next_before,"pendingRequests":pending,"error":error}),
    )
}
/// Timezone data is produced locally; clients supply a validated zone name only.
pub fn zone_rules(name: &str, now: i64) -> Result<ZoneRules> {
    use chrono::{Offset, TimeZone};
    let zone: chrono_tz::Tz = name
        .parse()
        .map_err(|_| anyhow::anyhow!("Unknown timezone"))?;
    let from = (now - 86400000).max(0) / 1000 * 1000;
    let until = now + 400 * 86400000i64;
    let offset = |ms| -> Result<i32> {
        Ok(zone
            .timestamp_millis_opt(ms)
            .single()
            .context("Invalid instant")?
            .offset()
            .fix()
            .local_minus_utc())
    };
    let initial = offset(from)?;
    let mut last = initial;
    let mut cursor = from;
    let mut transitions = vec![];
    while cursor < until {
        let next = (cursor + 3600000).min(until);
        let current = offset(next)?;
        if current != last {
            let (mut low, mut high) = (cursor / 1000, next / 1000);
            while low + 1 < high {
                let mid = (low + high) / 2;
                if offset(mid * 1000)? == last {
                    low = mid
                } else {
                    high = mid
                }
            }
            transitions.push(ZoneTransition {
                at_ms: high * 1000,
                offset_seconds: current,
            });
            last = current;
        }
        cursor = next;
    }
    let rules = ZoneRules {
        name: name.into(),
        from_ms: from,
        until_ms: until,
        initial_offset_seconds: initial,
        transitions,
    };
    rules.validate()?;
    Ok(rules)
}
#[cfg(test)]
#[path = "assistant_product/tests.rs"]
mod tests;
#[cfg(test)]
pub(crate) use tests::Fake as AssistantTestIo;
