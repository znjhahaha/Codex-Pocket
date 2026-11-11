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
impl RuntimeIo for HostIo {
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
        let schema = self
            .capabilities
            .0
            .get("thread/start")
            .is_some_and(|fields| fields.contains("dynamicTools"))
            && self.capabilities.0.contains_key("turn/start");
        Ok(RuntimeHealth {
            runtime_id: h.runtime_id,
            generation: h.generation,
            online: true,
            initialized: h.initialized,
            protocol_verified: schema,
            reconciliation_required: h.reconciliation_required,
        })
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
        "model/list" | "thread/read" | "thread/list" | "thread/turns/list" | "thread/items/list"
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
    fn request(&mut self, id: Value, method: &str, p: Value) -> Result<Value> {
        // New native work is checked again at the transport boundary. A fenced
        // operation is unavailable before dispatch, never an ambiguous retry.
        if matches!(method, "thread/start" | "thread/resume" | "turn/start")
            && self.handoff.as_ref().is_some_and(|h| !h.accepting_writes())
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
        let response = self.inner.answer(id.clone(), result);
        if response.is_ok() {
            self.ledger.lock().unwrap().execute(
                "DELETE FROM product_answers WHERE generation=?1 AND native_id=?2",
                params![generation, key(&id)],
            )?;
            self.tracking
                .lock()
                .unwrap()
                .uncertain_answers
                .remove(&key(&id));
        }
        response
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
    async fn start_with_io<R: RuntimeIo + Clone + Send + 'static>(
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
            loop{
                let request=match receiver.recv_timeout(Duration::from_millis(500)){
                    Ok(request)=>Some(request),Err(mpsc::RecvTimeoutError::Timeout)=>None,Err(mpsc::RecvTimeoutError::Disconnected)=>break,
                };
                // Observe buffered native results before recording a newer
                // user message. Explicit requests wake this loop immediately.
                worker.observe_runtime();
                let result=request.as_ref().map(|r|worker.apply(r));
                worker.dispatch_pending();
                let value=worker.snapshot(None);
                if let Ok(v)=&value{*out.lock().unwrap()=v.clone();}
                if let Some(request)=request{
                    let response=(||->Result<Value>{match result.unwrap(){
                        Ok(_) if is_read(&request.method)=>{
                            match value{Ok(mut v)=>{
                                if request.method=="assistant/settings/read"{v=json!({"settings":v["settings"],"fresh":true,"updatedAtMs":v["updatedAtMs"],"snapshotSource":"worker_read"});}
                                else if let Some(after)=request.parameters.get("after").and_then(Value::as_u64){v["events"]=json!(worker.service.events(after,256)?);}
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
    pub async fn request(
        &self,
        method: &str,
        parameters: Value,
        id: String,
        directory: Option<PathBuf>,
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
                directory,
                reply,
            })
            .map_err(|_| anyhow::anyhow!("Assistant queue busy"))?;
        tokio::time::timeout(Duration::from_secs(25), response)
            .await
            .context("Assistant result pending")??
    }
}
fn is_read(method: &str) -> bool {
    matches!(
        method,
        "assistant/read" | "assistant/settings/read" | "assistant/snapshot"
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
    deferred: BTreeMap<String, PendingToolCall>,
    handoff: Option<Arc<crate::handoff::Handoff>>,
    permits: Vec<crate::handoff::WritePermit>,
    recovered_permits: Vec<uuid::Uuid>,
    clock: Clock,
    runtime_id: String,
    generation: String,
    error: Option<&'static str>,
    runtime_ready: bool,
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
UPDATE product_projects SET writable=(SELECT max(p.writable) FROM product_projects p WHERE p.root=product_projects.root);
DELETE FROM product_projects WHERE rowid NOT IN (SELECT max(rowid) FROM product_projects GROUP BY root);
DELETE FROM product_timeline WHERE role='User' AND id IN (SELECT id FROM messages) AND ('user:'||id) IN (SELECT id FROM product_timeline);
UPDATE product_timeline SET id='user:'||id WHERE role='User' AND id IN (SELECT id FROM messages);
INSERT OR IGNORE INTO product_timeline(id,role,text,at_ms) SELECT 'user:'||id,'User',text,at_ms FROM messages ORDER BY at_ms,rowid;")?;
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
        let mut grants = vec![];
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
                coordinator_directory: workspace,
                grants,
            },
        )?);
        let service = AssistantService::open(
            &root.join("assistant.db"),
            &computer,
            &profile,
            &generation,
            executor.clone(),
        )?;
        let mut worker = Self {
            service,
            executor,
            probe,
            messages,
            projects,
            deferred: BTreeMap::new(),
            handoff,
            permits: vec![],
            recovered_permits,
            clock,
            runtime_id,
            generation,
            error: None,
            runtime_ready: health.online && health.initialized && health.protocol_verified,
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
        let t = self.probe.tracking.lock().unwrap();
        self.service
            .operations()
            .iter()
            .any(|o| o.phase == OperationPhase::Unknown)
            || t.old_answers
            || !t.uncertain_answers.is_empty()
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
                        self.service
                            .operations()
                            .iter()
                            .any(|o| o.id == *id && o.role == Role::Coordinator)
                    })
                {
                    tx.execute("INSERT OR IGNORE INTO product_timeline(id,role,text,at_ms,operation_id,source_sequence) VALUES(?1,'Assistant',?2,?3,?4,?5)",params![format!("assistant:{}",event.id),event.detail,event.at_ms,event.operation_id,event.sequence])?;
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
        if is_read(&r.method) {
            ensure!(
                r.parameters
                    .as_object()
                    .is_some_and(|p| p.keys().all(|k| k == "after"))
                    && r.parameters
                        .get("after")
                        .is_none_or(|v| v.as_u64().is_some()),
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
                    let source = p["runtimeId"]
                        .as_str()
                        .unwrap_or(&self.runtime_id)
                        .to_owned();
                    self.authorize(&source, None, text(p, "threadId")?, root, true)?;
                }
                self.service
                    .submit(&r.id, message, r.directory.iter().cloned().collect(), at)?;
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
            "assistant/resume" => self.service.release_takeover(&r.id, at)?,
            "assistant/task/pause" => {
                self.service.apply(
                    &r.id,
                    Command::PauseTask {
                        task_id: text(p, "taskId")?.into(),
                    },
                    at,
                )?;
            }
            "assistant/task/resume" => {
                let task_id = text(p, "taskId")?;
                let task = self
                    .service
                    .tasks()?
                    .into_iter()
                    .find(|t| t.id == task_id)
                    .context("Task not found")?;
                // Explicit resume cannot reuse permission evidence captured
                // before a native project policy change.
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
                self.service.apply(
                    &r.id,
                    Command::ResumeTask {
                        task_id: task_id.into(),
                    },
                    at,
                )?;
            }
            "assistant/memory/add" => {
                self.service.apply(
                    &r.id,
                    Command::Remember {
                        text: text(p, "text")?.into(),
                    },
                    at,
                )?;
            }
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
            "assistant/respond" => self.service.respond(
                &r.id,
                text(p, "operationId")?,
                text(p, "requestId")?,
                serde_json::from_value(p["response"].clone())?,
                at,
            )?,
            "assistant/schedule/delete" => self
                .service
                .remove_schedule(&r.id, text(p, "scheduleId")?)?,
            "assistant/schedule/create" => {
                let root = r
                    .directory
                    .as_ref()
                    .context("Select an authorized project")?;
                let spec = &p["spec"];
                let writes = spec["writes"].as_bool().context("Missing writes choice")?;
                let source = p["runtimeId"]
                    .as_str()
                    .unwrap_or(&self.runtime_id)
                    .to_owned();
                self.authorize(&source, None, text(p, "threadId")?, root, writes)?;
                ensure!(
                    !writes
                        || self.projects.iter().any(|project| project.verified
                            && project.writable
                            && physical(root).is_ok_and(|actual| within(&actual, &project.root))),
                    "Selected project does not authorize scheduled writes"
                );
                let zone = zone_rules(text(&spec["zone"], "name")?, at)?;
                self.service.add_schedule(
                    &r.id,
                    ScheduleSpec {
                        kind: serde_json::from_value(spec["kind"].clone())?,
                        zone,
                        prompt: text(spec, "prompt")?.into(),
                        directory: root.clone(),
                        writes,
                    },
                    at,
                )?;
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
        self.runtime_ready = self.probe.health().is_ok_and(|h| {
            h.online
                && h.initialized
                && h.protocol_verified
                && h.runtime_id == self.runtime_id
                && h.generation == self.generation
        });
        if !self.runtime_ready {
            self.error = Some("runtime_unavailable_or_unsupported");
        }
        match self.executor.poll_events(256) {
            Ok(projection) => {
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
        if self.sync_timeline().is_err() {
            self.error = Some("timeline_unavailable");
        }
    }
    fn dispatch_pending(&mut self) {
        if self.allowed() && self.runtime_ready && !self.uncertain() {
            for (id, tool) in self.deferred.clone() {
                if self.service.operations().iter().any(|o| {
                    o.id == tool.operation_id && o.input_tool_call.as_deref() == Some(&tool.call_id)
                }) {
                    continue;
                }
                if !self.allowed() || self.uncertain() {
                    break;
                }
                let Ok(permit) = self.admit() else {
                    break;
                };
                let result = match tool.call.clone() {
                    Some(call) => {
                        self.service
                            .tool(tool.scope.clone(), &tool.call_id, call, (self.clock)())
                    }
                    None => Err(anyhow::anyhow!(
                        tool.error.clone().unwrap_or("Invalid native tool".into())
                    )),
                };
                match result {
                    Ok(_) => {
                        self.deferred.remove(&id);
                    }
                    Err(error) => {
                        if self
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
            if self.allowed() && !self.uncertain() {
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
        if self.sync_timeline().is_err() {
            self.error = Some("timeline_unavailable");
        }
        self.revision += 1;
    }
    fn snapshot(&mut self, override_error: Option<&str>) -> Result<Value> {
        self.sync_timeline()?;
        let value = snapshot_value(
            &self.service,
            &self.executor,
            &self.messages,
            &self.runtime_id,
            override_error.or(self.error),
        )?;
        let mut value = value;
        value["fresh"] = json!(true);
        value["snapshotSource"] = json!("worker_read");
        value["updatedAtMs"] = json!((self.clock)());
        value["revision"] = json!(self.revision);
        value["runtimeReady"] = json!(self.runtime_ready);
        value["reconciliationRequired"] = json!(self.uncertain());
        value["unconfirmedWrites"] = json!(self.permits.len() + self.recovered_permits.len());
        value["ready"] = json!(
            self.runtime_ready && !self.uncertain() && override_error.or(self.error).is_none()
        );
        value["unsupportedOperation"] = json!(self.probe.tracking.lock().unwrap().unsupported);
        value["projectAuthorization"]=json!(self.projects.iter().map(|p|json!({"threadId":p.thread,"directory":p.root,"writable":p.writable,"verified":p.verified,"sourceRuntimeId":p.source_runtime,"sourceGeneration":p.source_generation,"permissionRevision":p.permission_revision})).collect::<Vec<_>>());
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
    let actual = physical(Path::new(cwd))?;
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
    let policy = crate::settings::canonical(&proof.settings);
    let writable = policy["activePermissionProfile"]["id"].is_null()
        && matches!(
            policy["sandboxPolicy"]["type"].as_str(),
            Some("workspaceWrite" | "dangerFullAccess")
        );
    Ok((actual, writable, proof))
}
fn text<'a>(v: &'a Value, field: &str) -> Result<&'a str> {
    v[field]
        .as_str()
        .filter(|s| !s.is_empty() && s.len() <= 65536 && !s.contains('\0'))
        .context("Missing assistant field")
}
fn snapshot_value<R: RuntimeIo>(
    service: &AssistantService<SharedHostExecutor<R>>,
    executor: &SharedHostExecutor<R>,
    messages: &Connection,
    runtime: &str,
    error: Option<&str>,
) -> Result<Value> {
    let operations = service.operations();
    let chat = if messages
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
    let pending=executor.pending_requests()?.into_iter().map(|p|json!({"operationId":p.operation_id,"requestId":p.handle,"method":p.method,"params":p.params})).collect::<Vec<_>>();
    let events = service.latest_events(256)?;
    Ok(
        json!({"enabled":true,"ready":error.is_none(),"runtimeId":runtime,"coordinatorThreadId":service.coordinator_thread_id(),"assistantId":service.assistant_id(),"paused":service.is_paused(),"settings":service.settings(),"tasks":service.tasks()?,"operations":operations,"events":events,"eventCursor":events.last().map(|e|e.sequence),"memories":service.memories()?,"schedules":service.schedules(),"messages":chat,"pendingRequests":pending,"error":error}),
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
