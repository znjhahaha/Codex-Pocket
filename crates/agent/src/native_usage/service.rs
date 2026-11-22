//! Async front of the native ledger and the source-selecting v2 envelope. The live hook
//! only queues numbers; SQLite, rollout reads and aggregation run on bounded blocking
//! workers. Remote authorization belongs to the broker.
use super::{
    Observation, import,
    ledger::{self, Failure, Ledger},
    query,
};
use crate::usage_stats::{
    DataSource, StatusFilter, UsageQuery,
    service::{self as legacy, UsageService},
};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, MutexGuard, Weak,
        atomic::{AtomicBool, AtomicU64, Ordering::Relaxed},
    },
    time::{Duration, Instant},
};
use tokio::sync::{Semaphore, mpsc, watch};

const TTL: u64 = 60;
const MAX_ENTRIES: usize = 16;
const MAX_BYTES: usize = 8 * 1024 * 1024;
const MAX_HOMES: usize = 16;
const QUEUE: usize = 1024;
const BATCH: usize = 256;
/// One import slice, then a pause: a large store is absorbed over many slices
/// without monopolising a blocking thread or the disk.
const PASS_BYTES: u64 = 32 * 1024 * 1024;
const PASS_TIME: Duration = Duration::from_millis(750);
const PASS_PAUSE: Duration = Duration::from_millis(250);
/// A caught-up store is re-listed at most this often, however many reads arrive.
const PASS_INTERVAL: Duration = Duration::from_secs(5);
/// A read may wait this long for a running slice, then answers with what is known.
const READ_WAIT: Duration = Duration::from_millis(300);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    Native,
    CcSwitch,
}
#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Sources,
    Dashboard,
    Requests,
}
type Reply = Arc<(Value, Value)>;
struct Entry {
    key: String,
    value: Value,
    generation: u64,
    at: Instant,
    sampled_at: u64,
    bytes: usize,
}
#[derive(Default)]
struct Import {
    running: bool,
    passes: u64,
    finished: Option<Instant>,
    finished_at: Option<u64>,
    last: import::Pass,
    failed: bool,
}
struct Inner {
    path: PathBuf,
    ledger: Mutex<Option<Arc<Ledger>>>,
    homes: Mutex<BTreeMap<String, PathBuf>>,
    queue: mpsc::Sender<Observation>,
    receiver: Mutex<Option<mpsc::Receiver<Observation>>>,
    started: AtomicBool,
    queued: AtomicU64,
    applied: AtomicU64,
    rejected: AtomicU64,
    dropped: AtomicU64,
    // Bumped by every ledger change so cached aggregates never outlive their data.
    generation: AtomicU64,
    import: Mutex<Import>,
    passes: watch::Sender<u64>,
    cache: Mutex<Vec<Entry>>,
    in_flight: Mutex<BTreeMap<String, watch::Receiver<Option<Reply>>>>,
    slots: Arc<Semaphore>,
    pass_bytes: u64,
    pass_pause: Duration,
}
fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}
fn metadata(state: &str, sampled_at: Option<u64>, age: Option<u64>) -> Value {
    json!({"state":state,"sampled_at":sampled_at,"age_seconds":age,"ttl_seconds":TTL,"stale_reason":null})
}
fn failure(kind: Kind, f: Failure) -> Value {
    let (availability, code, message) = f.details();
    let errors = json!([{"code":code,"message":message}]);
    match kind {
        Kind::Sources => {
            json!({"availability":availability,"schema_version":null,"errors":errors,"coverage":{"first_observed_at":null,"last_observed_at":null,"sessions":0,"invalid_records":0,"rejected_records":0,"rewound_files":0}})
        }
        Kind::Requests => {
            json!({"availability":availability,"source":query::source(None),"capabilities":query::capabilities(),"errors":errors,"data":null})
        }
        Kind::Dashboard => {
            json!({"availability":availability,"coverage":{"scope":"native_ledger","complete":false,"matched_rows":0,"first_observed_at":null,"last_observed_at":null,"rollups_present":false,"rollups_included":false,"cost_known_rows":0,"cost_missing_rows":0,"cost_invalid_rows":0,"zero_cost_rows":0,"token_invalid_rows":0,"confirmed_free_rows":null,"zero_cost_interpretation":"cost_not_provided","sessions":0,"unanchored_rows":0,"inherited_rows":0},"source":query::source(None),"capabilities":query::capabilities(),"errors":errors,"data":null})
        }
    }
}
impl Inner {
    fn ledger(&self) -> Result<Arc<Ledger>, Failure> {
        let mut slot = lock(&self.ledger);
        if let Some(ledger) = &*slot {
            return Ok(ledger.clone());
        }
        let ledger = Arc::new(Ledger::open(&self.path)?);
        *slot = Some(ledger.clone());
        Ok(ledger)
    }
    fn register(&self, store: &str, home: &Path) {
        // Unit tests construct brokers with the default profile; never read the real one.
        #[cfg(test)]
        if !home.starts_with(std::env::temp_dir()) {
            return;
        }
        let mut homes = lock(&self.homes);
        if homes.len() < MAX_HOMES && !homes.contains_key(store) {
            homes.insert(store.to_owned(), home.to_owned());
        }
    }
    fn apply(&self, batch: Vec<Observation>) {
        let count = batch.len() as u64;
        match self.ledger().and_then(|ledger| ledger.apply(&batch)) {
            Ok(outcome) if outcome.changed => {
                self.generation.fetch_add(1, Relaxed);
            }
            Ok(_) => {}
            Err(_) => {
                self.dropped.fetch_add(count, Relaxed);
            }
        }
        self.applied.fetch_add(count, Relaxed);
    }
    fn start(self: &Arc<Self>) {
        if self.started.load(Relaxed) {
            return;
        }
        let Ok(handle) = tokio::runtime::Handle::try_current() else {
            return;
        };
        let Some(mut receiver) = lock(&self.receiver).take() else {
            return;
        };
        self.started.store(true, Relaxed);
        // The worker keeps no strong reference while idle, so it ends with its owner.
        let weak = Arc::downgrade(self);
        handle.spawn(async move {
            while let Some(first) = receiver.recv().await {
                let mut batch = vec![first];
                while batch.len() < BATCH
                    && let Ok(next) = receiver.try_recv()
                {
                    batch.push(next);
                }
                let Some(inner) = weak.upgrade() else { return };
                let worker = inner.clone();
                let _ = tokio::task::spawn_blocking(move || worker.apply(batch)).await;
                // The rollout that produced these events fills any gap they left.
                inner.kick(false);
            }
        });
    }
    fn pass(&self) -> Result<import::Pass, Failure> {
        let ledger = self.ledger()?;
        let homes: Vec<_> = lock(&self.homes)
            .iter()
            .map(|(store, home)| (store.clone(), home.clone()))
            .collect();
        import::pass(&ledger, &homes, self.pass_bytes, PASS_TIME)
    }
    /// Starts the import loop unless one is running or a caught-up store was listed
    /// recently. Returns a receiver that changes when the next slice ends.
    fn kick(self: &Arc<Self>, force: bool) -> Option<watch::Receiver<u64>> {
        let handle = tokio::runtime::Handle::try_current().ok()?;
        let mut state = lock(&self.import);
        if !state.running {
            if !force
                && state.last.pending_files == 0
                && state.finished.is_some_and(|at| at.elapsed() < PASS_INTERVAL)
            {
                return None;
            }
            state.running = true;
            handle.spawn(run(Arc::downgrade(self)));
        }
        Some(self.passes.subscribe())
    }
    fn import_state(&self) -> Value {
        let state = lock(&self.import);
        let name = if state.failed {
            "unavailable"
        } else if state.passes == 0 || state.last.pending_files > 0 {
            "catching_up"
        } else {
            "complete"
        };
        json!({"state":name,"complete":name=="complete","files":state.last.files,"files_pending":state.last.pending_files,"bytes_pending":state.last.pending_bytes,"files_skipped":state.last.skipped_files,"scan_truncated":state.last.truncated,"last_pass_at":state.finished_at})
    }
    async fn complete(self: Arc<Self>, kind: Kind, query: Option<UsageQuery>, key: String) -> Reply {
        let permit = tokio::time::timeout(
            Duration::from_millis(250),
            self.slots.clone().acquire_owned(),
        )
        .await;
        let generation = self.generation.load(Relaxed);
        let result = match permit {
            Ok(Ok(permit)) => {
                let inner = self.clone();
                tokio::task::spawn_blocking(move || {
                    let _permit = permit;
                    // Opening the writer first creates the ledger on first use.
                    inner.ledger()?;
                    let conn = ledger::reader(&inner.path)?;
                    match (kind, &query) {
                        (Kind::Dashboard, Some(q)) => query::dashboard(&conn, q),
                        (Kind::Requests, Some(q)) => query::requests(&conn, q),
                        _ => query::overview(&conn).map(|coverage| {
                            json!({"availability":"ready","schema_version":ledger::SCHEMA,"errors":query::notices(),"coverage":coverage})
                        }),
                    }
                })
                .await
                .unwrap_or(Err(Failure::Unavailable))
            }
            _ => Err(Failure::Busy),
        };
        Arc::new(match result {
            Ok(value) => {
                let sampled_at = crate::storage::now();
                let bytes = serde_json::to_vec(&value).map_or(MAX_BYTES + 1, |v| v.len());
                let mut cache = lock(&self.cache);
                cache.retain(|e| e.key != key && e.at.elapsed().as_secs() <= TTL);
                if bytes <= MAX_BYTES {
                    while cache.len() >= MAX_ENTRIES
                        || cache.iter().map(|e| e.bytes).sum::<usize>() + bytes > MAX_BYTES
                    {
                        cache.remove(0);
                    }
                    cache.push(Entry {
                        key,
                        value: value.clone(),
                        generation,
                        at: Instant::now(),
                        sampled_at,
                        bytes,
                    });
                }
                (value, metadata("fresh", Some(sampled_at), Some(0)))
            }
            Err(f) => (failure(kind, f), metadata("unavailable", None, None)),
        })
    }
    async fn execute(self: &Arc<Self>, kind: Kind, query: Option<UsageQuery>, refresh: bool) -> Reply {
        if let Some(mut pass) = self.kick(refresh) {
            let _ = tokio::time::timeout(READ_WAIT, pass.changed()).await;
        }
        let key = format!(
            "{}:{}",
            kind as u8,
            query
                .as_ref()
                .map_or_else(String::new, |q| serde_json::to_string(q).unwrap_or_default())
        );
        let unavailable = |f| Arc::new((failure(kind, f), metadata("unavailable", None, None)));
        let mut receiver = {
            let mut flights = lock(&self.in_flight);
            if let Some(receiver) = flights.get(&key) {
                receiver.clone()
            } else {
                if !refresh {
                    let generation = self.generation.load(Relaxed);
                    if let Some(entry) = lock(&self.cache).iter().find(|e| {
                        e.key == key && e.generation == generation && e.at.elapsed().as_secs() < TTL
                    }) {
                        return Arc::new((
                            entry.value.clone(),
                            metadata(
                                "fresh",
                                Some(entry.sampled_at),
                                Some(entry.at.elapsed().as_secs()),
                            ),
                        ));
                    }
                }
                // Bound distinct pending keys as well as active SQLite workers.
                if flights.len() >= MAX_ENTRIES {
                    return unavailable(Failure::Busy);
                }
                let (sender, receiver) = watch::channel(None);
                flights.insert(key.clone(), receiver.clone());
                let inner = self.clone();
                // The task owns completion so a disconnected caller cannot leave a stuck flight.
                tokio::spawn(async move {
                    let value = inner.clone().complete(kind, query, key.clone()).await;
                    let mut flights = lock(&inner.in_flight);
                    sender.send_replace(Some(value));
                    flights.remove(&key);
                });
                receiver
            }
        };
        loop {
            if let Some(value) = receiver.borrow().clone() {
                return value;
            }
            if receiver.changed().await.is_err() {
                return unavailable(Failure::Unavailable);
            }
        }
    }
}
async fn run(weak: Weak<Inner>) {
    let mut idle = 0;
    loop {
        let Some(inner) = weak.upgrade() else { return };
        let worker = inner.clone();
        let result = tokio::task::spawn_blocking(move || worker.pass())
            .await
            .unwrap_or(Err(Failure::Unavailable));
        let more = {
            let mut state = lock(&inner.import);
            state.passes += 1;
            state.finished = Some(Instant::now());
            state.finished_at = Some(crate::storage::now());
            state.failed = result.is_err();
            let more = match result {
                Ok(pass) => {
                    // A slice that could read nothing must not spin on what is left.
                    idle = if pass.read_bytes == 0 { idle + 1 } else { 0 };
                    if pass.changed {
                        inner.generation.fetch_add(1, Relaxed);
                    }
                    let more = pass.pending_files > 0 && idle < 3;
                    state.last = pass;
                    more
                }
                Err(_) => false,
            };
            state.running = more;
            more
        };
        inner.passes.send_modify(|n| *n += 1);
        let pause = inner.pass_pause;
        drop(inner);
        if !more {
            return;
        }
        tokio::time::sleep(pause).await;
    }
}

#[derive(Clone)]
pub struct NativeUsage(Arc<Inner>);
impl NativeUsage {
    /// `path` is the ledger inside the agent's private state directory; `homes` are
    /// trusted local Codex homes keyed by config store. Neither is ever remote input.
    pub fn new(path: PathBuf, homes: impl IntoIterator<Item = (String, PathBuf)>) -> Self {
        Self::with_budget(path, homes, PASS_BYTES, PASS_PAUSE)
    }
    pub(crate) fn with_budget(
        path: PathBuf,
        homes: impl IntoIterator<Item = (String, PathBuf)>,
        pass_bytes: u64,
        pass_pause: Duration,
    ) -> Self {
        let (queue, receiver) = mpsc::channel(QUEUE);
        let service = Self(Arc::new(Inner {
            path,
            ledger: Mutex::new(None),
            homes: Mutex::new(BTreeMap::new()),
            queue,
            receiver: Mutex::new(Some(receiver)),
            started: AtomicBool::new(false),
            queued: AtomicU64::new(0),
            applied: AtomicU64::new(0),
            rejected: AtomicU64::new(0),
            dropped: AtomicU64::new(0),
            generation: AtomicU64::new(0),
            import: Mutex::new(Import::default()),
            passes: watch::channel(0).0,
            cache: Mutex::new(Vec::new()),
            in_flight: Mutex::new(BTreeMap::new()),
            slots: Arc::new(Semaphore::new(2)),
            pass_bytes,
            pass_pause,
        }));
        service.homes(homes);
        service
    }
    pub fn homes(&self, homes: impl IntoIterator<Item = (String, PathBuf)>) {
        for (store, home) in homes {
            self.0.register(&store, &home);
        }
    }
    /// Called for every native event while the broker holds its state lock: no I/O,
    /// no waiting, and no failure can reach event delivery. A full queue drops the
    /// sample; the rollout import later supplies the same cumulative point.
    pub fn live(&self, store: &str, home: &Path, event: &Value) {
        if event["method"] != "thread/tokenUsage/updated" {
            return;
        }
        let inner = &self.0;
        let Some(observation) = Observation::live(&event["params"], crate::storage::now() as i64)
        else {
            inner.rejected.fetch_add(1, Relaxed);
            return;
        };
        inner.register(store, home);
        inner.start();
        if inner.queue.try_send(observation).is_ok() {
            inner.queued.fetch_add(1, Relaxed);
        } else {
            inner.dropped.fetch_add(1, Relaxed);
        }
    }
    async fn native_source(&self, refresh: bool) -> Value {
        let reply = self.0.execute(Kind::Sources, None, refresh).await;
        let import = self.0.import_state();
        let mut value = json!({"id":super::SOURCE,"kind":super::SOURCE,"apps":["codex"],"data_sources":[],"session_app":"codex","capabilities":query::capabilities()});
        for (key, field) in reply.0.as_object().into_iter().flatten() {
            value[key] = field.clone();
        }
        if import["complete"] != true && value["availability"] == "ready" {
            push_notice(&mut value["errors"]);
        }
        value["coverage"]["history_import"] = import;
        value["coverage"]["live_rejected"] = json!(self.0.rejected.load(Relaxed));
        value["coverage"]["live_dropped"] = json!(self.0.dropped.load(Relaxed));
        value["sampled_at"] = reply.1["sampled_at"].clone();
        value
    }
    async fn native(&self, kind: Kind, query: UsageQuery, refresh: bool) -> Value {
        let reply = self.0.execute(kind, Some(query.clone()), refresh).await;
        let (mut result, cache) = (*reply).clone();
        let import = self.0.import_state();
        if import["complete"] != true && result["availability"] == "ready" {
            push_notice(&mut result["errors"]);
        }
        if kind == Kind::Dashboard {
            result["coverage"]["history_import"] = import;
        }
        json!({"schema":2,"source":super::SOURCE,"query":query,"result":result,"cache":cache})
    }
    pub async fn sources(&self, cc: &UsageService, refresh: bool) -> Value {
        let (native, mut v1) = tokio::join!(
            self.native_source(refresh),
            cc.sources_with_refresh(refresh)
        );
        let mut cc_switch = v1["sources"][0].take();
        cc_switch["capabilities"] = json!({"cost":true,"performance":true,"status":true,"providers":true,"models":true,"sessions":true,"requests":true});
        // `cache` keeps its v1 meaning (the CC Switch probe); the native entry is
        // sampled from the local ledger and carries its own `sampled_at`.
        json!({"schema":2,"sources":[native, cc_switch],"cache":v1["cache"].take()})
    }
    pub async fn read(&self, cc: &UsageService, source: Source, query: UsageQuery, refresh: bool) -> Value {
        match source {
            Source::Native => self.native(Kind::Dashboard, query, refresh).await,
            Source::CcSwitch => rewrap(cc.dashboard_with_refresh(query, refresh).await),
        }
    }
    pub async fn requests(&self, cc: &UsageService, source: Source, query: UsageQuery, refresh: bool) -> Value {
        match source {
            Source::Native => self.native(Kind::Requests, query, refresh).await,
            Source::CcSwitch => rewrap(cc.requests_with_refresh(query, refresh).await),
        }
    }
    /// Waits until queued live samples are stored and no import slice is pending.
    #[cfg(test)]
    pub(crate) async fn settle(&self) {
        for _ in 0..2000 {
            let idle = {
                let state = lock(&self.0.import);
                !state.running && state.last.pending_files == 0
            };
            if idle && self.0.applied.load(Relaxed) >= self.0.queued.load(Relaxed) {
                return;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        panic!("native usage did not settle");
    }
    #[cfg(test)]
    pub(crate) async fn hold_slots_for_test(&self) -> tokio::sync::OwnedSemaphorePermit {
        self.0.slots.clone().acquire_many_owned(2).await.unwrap()
    }
}
fn push_notice(errors: &mut Value) {
    if let Some(errors) = errors.as_array_mut() {
        errors.push(json!({"code":"history_import_incomplete","message":"Native history import is still catching up; totals may grow."}));
    }
}
/// The CC Switch result, query and cache are exactly the v1 values.
fn rewrap(mut v1: Value) -> Value {
    json!({"schema":2,"source":"cc_switch","query":v1["query"].take(),"result":v1["result"].take(),"cache":v1["cache"].take()})
}
/// v2 read parameters: everything v1 accepts plus the required `source`.
pub fn prepare_read(p: &Value, now: i64) -> Result<(Source, UsageQuery, bool), ()> {
    let mut params = p.as_object().ok_or(())?.clone();
    let (source, id) = match params.remove("source").as_ref().and_then(Value::as_str) {
        Some(super::SOURCE) => (Source::Native, super::SOURCE),
        Some("cc_switch") => (Source::CcSwitch, "cc_switch"),
        _ => return Err(()),
    };
    if params
        .remove("source_id")
        .is_some_and(|v| v.as_str() != Some(id))
    {
        return Err(());
    }
    let refresh = params.get("refresh").and_then(Value::as_bool).unwrap_or(false);
    let query = legacy::prepare_read(&Value::Object(params), now)?;
    // Native usage is Codex-only and has no data-source or status dimension. Their
    // defaults mean "not specified"; any other value is rejected rather than ignored,
    // so a CC Switch `codex_session` view can never be mistaken for native data.
    if source == Source::Native
        && (query.filters.app_type != "codex"
            || query.filters.data_source != DataSource::Proxy
            || query.filters.status != StatusFilter::All
            || query
                .request_page
                .cursor
                .as_ref()
                .is_some_and(|c| !c.request_id.parse::<i64>().is_ok_and(|id| id > 0)))
    {
        return Err(());
    }
    Ok((source, query, refresh))
}
pub fn validate_sources(p: &Value) -> bool {
    legacy::validate_sources(p)
}
