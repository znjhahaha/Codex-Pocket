//! Fixed current-user source and bounded blocking work; remote authorization belongs to broker.
use super::*;
use serde_json::{Value, json};
use std::{
    sync::{Arc, Mutex, OnceLock},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::Semaphore;
const TTL: u64 = 60;
const MAX_AGE: u64 = 300;
const MAX_ENTRIES: usize = 16;
const MAX_BYTES: usize = 8 * 1024 * 1024;
#[derive(Clone)]
struct Entry {
    key: String,
    value: Arc<Value>,
    at: Instant,
    sampled_at: u64,
    bytes: usize,
}
#[derive(Default)]
struct Cache {
    entries: Vec<Entry>,
    bytes: usize,
}
impl Cache {
    fn prune(&mut self) {
        self.entries.retain(|e| e.at.elapsed().as_secs() <= MAX_AGE);
        self.bytes = self.entries.iter().map(|e| e.bytes).sum();
    }
    fn get(&mut self, key: &str) -> Option<Entry> {
        self.prune();
        let index = self.entries.iter().position(|e| e.key == key)?;
        let entry = self.entries.remove(index);
        self.entries.push(entry.clone());
        Some(entry)
    }
    fn insert(&mut self, key: String, value: Value, at: Instant, sampled_at: u64) {
        self.prune();
        let bytes = serde_json::to_vec(&value).map_or(MAX_BYTES + 1, |v| v.len()) + key.len();
        self.entries.retain(|e| e.key != key);
        self.bytes = self.entries.iter().map(|e| e.bytes).sum();
        if bytes > MAX_BYTES {
            return;
        }
        while self.entries.len() >= MAX_ENTRIES || self.bytes + bytes > MAX_BYTES {
            let removed = self.entries.remove(0);
            self.bytes -= removed.bytes;
        }
        self.bytes += bytes;
        self.entries.push(Entry {
            key,
            value: Arc::new(value),
            at,
            sampled_at,
            bytes,
        });
    }
}
pub struct UsageService {
    path: Option<PathBuf>,
    cache: Mutex<Cache>,
    slots: Arc<Semaphore>,
}
/// One service per process: paths, concurrency and cache cannot be reset by a remote caller.
pub fn current_user_service() -> &'static UsageService {
    static SERVICE: OnceLock<UsageService> = OnceLock::new();
    SERVICE.get_or_init(UsageService::for_current_user)
}
impl UsageService {
    pub fn for_current_user() -> Self {
        Self::with_path(default_db_path())
    }
    fn with_path(path: Option<PathBuf>) -> Self {
        Self {
            path,
            cache: Mutex::new(Cache::default()),
            slots: Arc::new(Semaphore::new(2)),
        }
    }
    #[cfg(test)]
    pub(crate) fn test_service(path: Option<PathBuf>) -> Self {
        Self::with_path(path)
    }
    #[cfg(test)]
    pub(crate) async fn hold_slots_for_test(&self) -> tokio::sync::OwnedSemaphorePermit {
        self.slots.clone().acquire_many_owned(2).await.unwrap()
    }
    pub async fn sources(&self) -> Value {
        self.execute(None).await
    }
    pub async fn read(&self, query: UsageQuery) -> Value {
        self.execute(Some(query)).await
    }
    async fn execute(&self, query: Option<UsageQuery>) -> Value {
        let key = query
            .as_ref()
            .map_or_else(|| "sources".into(), |q| serde_json::to_string(q).unwrap());
        let cached = self
            .cache
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&key);
        if let Some(entry) = &cached {
            if entry.at.elapsed() < Duration::from_secs(TTL) {
                return envelope(
                    query,
                    (*entry.value).clone(),
                    metadata("fresh", Some(entry), None),
                );
            }
        }
        let permit = tokio::time::timeout(
            Duration::from_millis(250),
            self.slots.clone().acquire_owned(),
        )
        .await;
        let mut value = match permit {
            Ok(Ok(permit)) => {
                let path = self.path.clone();
                let q = query.clone();
                match tokio::task::spawn_blocking(move || {
                    let _permit = permit;
                    match q {
                        Some(q) => match path {
                            Some(p) => serde_json::to_value(super::query(&p, &q)).unwrap(),
                            None => unavailable_read(
                                &q,
                                "home_unavailable",
                                "The current user home is unavailable.",
                            ),
                        },
                        None => source_value(path.as_deref()),
                    }
                })
                .await
                {
                    Ok(value) => value,
                    Err(_) => failure_value(
                        query.as_ref(),
                        "backend_failed",
                        "The usage worker did not complete.",
                    ),
                }
            }
            _ => failure_value(
                query.as_ref(),
                "backend_busy",
                "The usage backend is busy; retry later.",
            ),
        };
        // A cache entry is stamped only after a successful completed read.
        let ready = value["availability"] == "ready";
        let mut meta = metadata(if ready { "fresh" } else { "unavailable" }, None, None);
        if ready {
            let at = Instant::now();
            let sampled_at = now();
            self.cache.lock().unwrap_or_else(|e| e.into_inner()).insert(
                key,
                value.clone(),
                at,
                sampled_at,
            );
            meta["sampled_at"] = json!(sampled_at);
            meta["age_seconds"] = json!(0);
        } else if matches!(value["availability"].as_str(), Some("missing" | "locked")) {
            if let Some(entry) = &cached {
                if entry.at.elapsed() < Duration::from_secs(MAX_AGE) {
                    let reason = if value["availability"] == "missing" {
                        "database_missing"
                    } else {
                        "database_locked"
                    };
                    if query.is_some() {
                        value["data"] = entry.value["data"].clone();
                        value["coverage"] = entry.value["coverage"].clone();
                        value["source"] = entry.value["source"].clone();
                    } else {
                        value["schema_version"] = entry.value["schema_version"].clone();
                    }
                    let mut errors = entry.value["errors"]
                        .as_array()
                        .cloned()
                        .unwrap_or_default();
                    errors.extend(value["errors"].as_array().cloned().unwrap_or_default());
                    errors.push(json!({"code":"stale_data","message":"The data is a previous successful snapshot; the current source is unavailable."}));
                    value["errors"] = json!(errors);
                    meta = metadata("stale", Some(entry), Some(reason));
                }
            }
        }
        envelope(query, value, meta)
    }
    #[cfg(test)]
    fn age_cache_for_test(&self, seconds: u64) {
        for entry in &mut self.cache.lock().unwrap().entries {
            entry.at = Instant::now() - Duration::from_secs(seconds);
            entry.sampled_at = now().saturating_sub(seconds);
        }
    }
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
fn metadata(state: &str, entry: Option<&Entry>, reason: Option<&str>) -> Value {
    json!({"state":state,"sampled_at":entry.map(|e|e.sampled_at),"age_seconds":entry.map(|e|e.at.elapsed().as_secs()),"ttl_seconds":TTL,"stale_reason":reason})
}
fn envelope(query: Option<UsageQuery>, value: Value, cache: Value) -> Value {
    match query {
        Some(query) => json!({"schema":1,"query":query,"result":value,"cache":cache}),
        None => json!({"schema":1,"sources":[value],"cache":cache}),
    }
}
fn unavailable_read(q: &UsageQuery, code: &str, message: &str) -> Value {
    json!({"availability":"unavailable","coverage":Coverage { scope:"retained_detail_only".into(), zero_cost_interpretation:"stored_zero_free_status_unknown".into(), ..Default::default() },"source":SourceInfo {kind:"cc_switch".into(),table:"proxy_request_logs".into(),app_type:q.filters.app_type.chars().take(256).collect(),data_source:q.filters.data_source,schema_version:None},"errors":[{"code":code,"message":message}],"data":null})
}
fn source_base() -> Value {
    json!({"id":"cc_switch","kind":"cc_switch","availability":"unavailable","schema_version":null,"apps":["codex","claude","gemini","grokbuild"],"data_sources":["proxy","codex_session"],"session_app":"codex","errors":[]})
}
fn failure_value(query: Option<&UsageQuery>, code: &str, message: &str) -> Value {
    match query {
        Some(q) => unavailable_read(q, code, message),
        None => {
            let mut v = source_base();
            v["errors"] = json!([{"code":code,"message":message}]);
            v
        }
    }
}
fn source_value(path: Option<&Path>) -> Value {
    let mut value = source_base();
    let Some(path) = path else {
        return failure_value(
            None,
            "home_unavailable",
            "The current user home is unavailable.",
        );
    };
    let mut version = None;
    match super::reader::probe(path, &mut version) {
        Ok(()) => value["availability"] = json!("ready"),
        Err(f) => {
            let (availability, code, message) = f.details();
            value["availability"] = json!(availability);
            value["errors"] = json!([{"code":code,"message":message}]);
        }
    }
    value["schema_version"] = json!(version);
    value
}
pub fn prepare_read(p: &Value, now: i64) -> Result<UsageQuery, ()> {
    let obj = p.as_object().ok_or(())?;
    if obj
        .keys()
        .any(|k| !matches!(k.as_str(), "source_id" | "query"))
        || obj
            .get("source_id")
            .is_some_and(|v| v.as_str() != Some("cc_switch"))
    {
        return Err(());
    }
    let query: UsageQuery = if let Some(query) = obj.get("query") {
        serde_json::from_value(query.clone()).map_err(|_| ())?
    } else {
        let end_at = now - now.rem_euclid(60);
        let start_at = end_at.checked_sub(7 * 86400).ok_or(())?;
        UsageQuery {
            start_at,
            end_at,
            bucket_edges: (0..=7).map(|i| start_at + i * 86400).collect(),
            filters: Filters::default(),
            provider_page: Page::default(),
            model_page: Page::default(),
        }
    };
    if super::valid(&query) {
        Ok(query)
    } else {
        Err(())
    }
}
pub fn validate_sources(p: &Value) -> bool {
    p.as_object().is_some_and(|o| o.is_empty())
}
#[cfg(test)]
#[path = "service_tests.rs"]
mod tests;
