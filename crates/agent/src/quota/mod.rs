//! Authenticated helper-only quota API. No caller-supplied endpoint, code or credential.
mod sandbox;
mod sources;
#[cfg(test)]
mod tests;
use crate::{capabilities::Capabilities, native::Rpc};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tokio::sync::{Mutex as AsyncMutex, Semaphore};
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Failure {
    Invalid,
    Unavailable,
    Script,
    Timeout,
    Http,
    Limit,
}
impl Failure {
    fn status(self) -> &'static str {
        match self {
            Self::Invalid => "invalid",
            Self::Unavailable => "unavailable",
            Self::Script => "script_error",
            Self::Timeout => "timeout",
            Self::Http => "http_error",
            Self::Limit => "limit_exceeded",
        }
    }
}
fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}
fn safe_text(v: &Value, fallback: &str) -> String {
    v.as_str()
        .filter(|s| !s.is_empty() && s.len() <= 160 && !s.chars().any(char::is_control))
        .unwrap_or(fallback)
        .to_owned()
}
fn number(v: &Value) -> Result<Option<f64>, Failure> {
    if v.is_null() {
        return Ok(None);
    }
    v.as_f64()
        .filter(|n| n.is_finite() && *n >= 0.0)
        .map(Some)
        .ok_or(Failure::Invalid)
}
fn timestamp(v: &Value) -> Option<u64> {
    v.as_u64().and_then(|n| {
        if n < 1_000_000_000_000 {
            n.checked_mul(1000)
        } else {
            Some(n)
        }
    })
}
fn normalize(value: &Value) -> Result<Vec<Value>, Failure> {
    let list = if let Some(a) = value.as_array() {
        a.clone()
    } else {
        vec![value.clone()]
    };
    if list.is_empty() || list.len() > 32 {
        return Err(Failure::Limit);
    }
    list.iter().map(|v| {
        if !v.is_object() || v["isValid"]==false {return Err(Failure::Invalid);}
        let mut used=number(&v["used"])?; let total=number(&v["total"])?; let mut remaining=number(&v["remaining"])?;
        if used.is_none() {used=total.zip(remaining).filter(|(t,r)|t>=r).map(|(t,r)|t-r);}
        if remaining.is_none() {remaining=total.zip(used).filter(|(t,u)|t>=u).map(|(t,u)|t-u);}
        let percent=number(&v["usedPercent"])?.or_else(||used.zip(total).filter(|(_,t)|*t>0.0).map(|(u,t)|u/t*100.0));
        if used.is_none() && remaining.is_none() && percent.is_none() && total.is_none() {return Err(Failure::Invalid);}
        Ok(json!({"label":safe_text(v.get("label").unwrap_or(&v["planName"]),"Quota"),"used":used,"total":total,"remaining":remaining,"unit":safe_text(&v["unit"],""),"usedPercent":percent.filter(|n|n.is_finite()),"resetsAt":timestamp(&v["resetsAt"])}))
    }).collect()
}
fn official(value: &Value) -> Result<Vec<Value>, Failure> {
    let mut result = vec![];
    let mut limits = vec![];
    if let Some(map) = value["rateLimitsByLimitId"].as_object() {
        if map.len() > 16 {
            return Err(Failure::Limit);
        }
        limits.extend(map.values());
    }
    if limits.is_empty() {
        limits.push(&value["rateLimits"]);
    }
    for limit in limits {
        for key in ["primary", "secondary"] {
            let window = &limit[key];
            if window.is_null() {
                continue;
            }
            let p = number(&window["usedPercent"])?.ok_or(Failure::Invalid)?;
            let label = format!("{} {}", safe_text(&limit["limitName"], "Codex"), key);
            result.push(json!({"label":label,"used":null,"total":null,"remaining":null,"unit":"%","usedPercent":p,"resetsAt":window["resetsAt"].as_u64().and_then(|s|s.checked_mul(1000))}));
        }
        if let Some(balance) = limit["credits"]["balance"]
            .as_str()
            .and_then(|s| s.parse::<f64>().ok())
            .or_else(|| limit["credits"]["balance"].as_f64())
            .filter(|n| n.is_finite() && *n >= 0.0)
        {
            result.push(json!({"label":"Credits","used":null,"total":null,"remaining":balance,"unit":"credits","usedPercent":null,"resetsAt":null}));
        }
    }
    if result.is_empty() {
        Err(Failure::Unavailable)
    } else {
        Ok(result)
    }
}
#[derive(Default)]
struct Cache {
    items: Vec<Value>,
    updated: Option<u64>,
    last_attempt: Option<Instant>,
    failure: Option<Failure>,
    revision: u64,
}
struct Source {
    id: String,
    label: String,
    configured: bool,
    kind: &'static str,
    interval: u64,
    provider: Option<sources::Provider>,
    rpc: Option<Rpc>,
    cache: Mutex<Cache>,
    flight: AsyncMutex<()>,
}
impl Source {
    fn snapshot(&self, runtime: &str, manual: bool) -> Value {
        let cache = self.cache.lock().unwrap();
        let interval = if manual { 15 } else { self.interval.max(60) };
        let retry = cache
            .last_attempt
            .map(|t| interval.saturating_sub(t.elapsed().as_secs()))
            .unwrap_or(0);
        let stale = cache.updated.is_some()
            && (cache.failure.is_some()
                || cache.updated.is_some_and(|at| {
                    now_ms().saturating_sub(at) >= self.interval.max(60).saturating_mul(1000)
                }));
        let status = if !self.configured {
            "unconfigured"
        } else {
            cache
                .failure
                .map(Failure::status)
                .unwrap_or(if cache.updated.is_some() {
                    "ok"
                } else if self.interval == 0 && !manual {
                    "auto_disabled"
                } else {
                    "unknown"
                })
        };
        json!({"runtimeId":runtime,"sourceId":self.id,"label":self.label,"status":status,"items":cache.items,"updatedAt":cache.updated,"stale":stale,"retryAfterSeconds":retry})
    }
    async fn read(&self, runtime: &str, manual: bool, permits: &Semaphore) -> Value {
        if !self.configured {
            return self.snapshot(runtime, manual);
        }
        let _flight = match self.flight.try_lock() {
            Ok(guard) => guard,
            Err(_) => {
                let _guard = self.flight.lock().await;
                return self.snapshot(runtime, manual);
            }
        };
        let interval = if manual { 15 } else { self.interval.max(60) };
        let revision = {
            let mut c = self.cache.lock().unwrap();
            if (!manual && self.interval == 0)
                || c.last_attempt
                    .is_some_and(|t| t.elapsed() < Duration::from_secs(interval))
            {
                drop(c);
                return self.snapshot(runtime, manual);
            }
            c.last_attempt = Some(Instant::now());
            c.revision
        };
        let result = tokio::time::timeout(Duration::from_secs(20), async {
            let _permit = permits.acquire().await.map_err(|_| Failure::Unavailable)?;
            if let Some(provider) = self.provider.clone() {
                sources::execute(provider).await
            } else if let Some(rpc) = &self.rpc {
                let v = rpc
                    .call("account/rateLimits/read", json!({}))
                    .await
                    .map_err(|_| Failure::Unavailable)?;
                official(&v)
            } else {
                Err(Failure::Unavailable)
            }
        })
        .await
        .unwrap_or(Err(Failure::Timeout));
        {
            let mut c = self.cache.lock().unwrap();
            // A fresher native update wins over an older in-flight reply/failure.
            if c.revision == revision {
                match result {
                    Ok(items) => {
                        c.items = items;
                        c.updated = Some(now_ms());
                        c.failure = None;
                    }
                    Err(e) => c.failure = Some(e),
                }
            }
        }
        self.snapshot(runtime, manual)
    }
}
struct Catalog {
    at: Instant,
    sources: Vec<(Arc<Source>, bool)>,
    status: &'static str,
}
pub struct Quota {
    path: PathBuf,
    catalogs: AsyncMutex<HashMap<String, Catalog>>,
    known: Mutex<HashMap<String, Arc<Source>>>,
    permits: Semaphore,
}
impl Default for Quota {
    fn default() -> Self {
        Self::new(sources::default_path())
    }
}
impl Quota {
    pub fn new(path: PathBuf) -> Self {
        Self {
            path,
            catalogs: AsyncMutex::new(HashMap::new()),
            known: Mutex::new(HashMap::new()),
            permits: Semaphore::new(4),
        }
    }
    pub async fn sources(
        &self,
        runtime: &str,
        generation: &str,
        rpc: Rpc,
        caps: Capabilities,
    ) -> Value {
        let key = format!("{runtime}:{generation}");
        let mut catalogs = self.catalogs.lock().await;
        if catalogs
            .get(&key)
            .is_none_or(|c| c.at.elapsed() >= Duration::from_secs(60))
        {
            let path = self.path.clone();
            let loaded = tokio::task::spawn_blocking(move || sources::load(&path))
                .await
                .unwrap_or(Err(Failure::Unavailable));
            if loaded.is_err()
                && let Some(old) = catalogs.get_mut(&key)
            {
                old.at = Instant::now();
                old.status = "unavailable";
                for (source, _) in &old.sources {
                    if source.kind == "cc_switch" {
                        source.cache.lock().unwrap().failure = Some(Failure::Unavailable);
                    }
                }
                return catalog_value(runtime, old);
            }
            let native = if caps.supports("config/read", &json!({})) {
                tokio::time::timeout(Duration::from_secs(3), rpc.call("config/read", json!({})))
                    .await
                    .ok()
                    .and_then(Result::ok)
                    .and_then(|v| sources::native_identity(&v))
            } else {
                None
            };
            let mut entries = vec![];
            let mut known = self.known.lock().unwrap();
            if caps.supports("account/rateLimits/read", &json!({})) {
                let s = known
                    .entry(key.clone())
                    .or_insert_with(|| {
                        Arc::new(Source {
                            id: uuid::Uuid::new_v4().to_string(),
                            label: "Codex account".into(),
                            configured: true,
                            kind: "official",
                            interval: 60,
                            provider: None,
                            rpc: Some(rpc),
                            cache: Mutex::default(),
                            flight: AsyncMutex::new(()),
                        })
                    })
                    .clone();
                entries.push((s, native.as_ref().is_some_and(|i| i.0 == "openai")));
            }
            let status = if loaded.is_err() { "unavailable" } else { "ok" };
            for provider in loaded.unwrap_or_default() {
                let matched = native
                    .as_ref()
                    .zip(provider.identity.as_ref())
                    .is_some_and(|(a, b)| a == b);
                if !provider.enabled && !matched {
                    continue;
                }
                let pkey = format!("cc:{}", provider.key);
                if known.get(&pkey).is_some_and(|s| {
                    s.provider
                        .as_ref()
                        .is_none_or(|p| p.fingerprint != provider.fingerprint)
                }) {
                    known.remove(&pkey);
                }
                let s = known
                    .entry(pkey)
                    .or_insert_with(|| {
                        Arc::new(Source {
                            id: uuid::Uuid::new_v4().to_string(),
                            label: provider.label.clone(),
                            configured: provider.enabled,
                            kind: "cc_switch",
                            interval: provider.interval,
                            provider: Some(provider),
                            rpc: None,
                            cache: Mutex::default(),
                            flight: AsyncMutex::new(()),
                        })
                    })
                    .clone();
                entries.push((s, matched));
            }
            if native.as_ref().is_some_and(|n| n.0 != "openai")
                && !entries.iter().any(|(_, matched)| *matched)
                && status == "ok"
            {
                let pkey = format!("unconfigured:{key}");
                let source = known
                    .entry(pkey)
                    .or_insert_with(|| {
                        Arc::new(Source {
                            id: uuid::Uuid::new_v4().to_string(),
                            label: "Current provider".into(),
                            configured: false,
                            kind: "cc_switch",
                            interval: 0,
                            provider: None,
                            rpc: None,
                            cache: Mutex::default(),
                            flight: AsyncMutex::new(()),
                        })
                    })
                    .clone();
                entries.push((source, true));
            }
            // Ambiguous configurations are deliberately never automatically selected.
            if entries.iter().filter(|(_, m)| *m).count() != 1 {
                for (_, m) in &mut entries {
                    *m = false;
                }
            }
            if catalogs.len() >= 64 {
                catalogs.clear();
            }
            catalogs.insert(
                key.clone(),
                Catalog {
                    at: Instant::now(),
                    sources: entries,
                    status,
                },
            );
            // Bound retained secrets/cache to the currently known catalogs.
            known.retain(|_, s| {
                catalogs
                    .values()
                    .any(|c| c.sources.iter().any(|(x, _)| Arc::ptr_eq(x, s)))
            });
        }
        catalog_value(runtime, &catalogs[&key])
    }
    pub async fn read(
        &self,
        runtime: &str,
        generation: &str,
        source: &str,
        manual: bool,
    ) -> Result<Value, Failure> {
        let (selected, available) = {
            let catalogs = self.catalogs.lock().await;
            let c = catalogs
                .get(&format!("{runtime}:{generation}"))
                .ok_or(Failure::Invalid)?;
            let s = c
                .sources
                .iter()
                .find(|(s, _)| s.id == source)
                .map(|(s, _)| s.clone())
                .ok_or(Failure::Invalid)?;
            let available = c.status == "ok" || s.kind == "official";
            (s, available)
        };
        if !available {
            return Ok(selected.snapshot(runtime, manual));
        }
        Ok(selected.read(runtime, manual, &self.permits).await)
    }
    pub fn account_changed(&self, runtime: &str, generation: &str) -> Option<Value> {
        let source = self
            .known
            .lock()
            .unwrap()
            .get(&format!("{runtime}:{generation}"))?
            .clone();
        {
            let mut cache = source.cache.lock().unwrap();
            let revision = cache.revision.wrapping_add(1);
            *cache = Cache {
                revision,
                ..Default::default()
            };
        }
        Some(source.snapshot(runtime, false))
    }
    // No async lock or HTTP on the broker event path. Constant bounded normalization only.
    pub fn update(&self, runtime: &str, generation: &str, value: &Value) -> Option<Value> {
        let s = self
            .known
            .lock()
            .unwrap()
            .get(&format!("{runtime}:{generation}"))?
            .clone();
        let items = official(value).ok()?;
        {
            let mut c = s.cache.lock().unwrap();
            c.items = items;
            c.updated = Some(now_ms());
            c.failure = None;
            c.revision = c.revision.wrapping_add(1);
        }
        Some(s.snapshot(runtime, false))
    }
}
pub fn validate(method: &str, p: &Value) -> Result<(), Failure> {
    let keys = if method == "quota/sources" {
        &["runtimeId"][..]
    } else {
        &["runtimeId", "sourceId", "refresh"][..]
    };
    if p.as_object()
        .is_none_or(|o| o.keys().any(|k| !keys.contains(&k.as_str())))
        || p["runtimeId"]
            .as_str()
            .is_none_or(|s| s.is_empty() || s.len() > 256)
        || (method == "quota/read"
            && p["sourceId"]
                .as_str()
                .is_none_or(|s| uuid::Uuid::parse_str(s).is_err()))
        || p.get("refresh").is_some_and(|v| !v.is_boolean())
    {
        Err(Failure::Invalid)
    } else {
        Ok(())
    }
}

fn catalog_value(runtime: &str, c: &Catalog) -> Value {
    let selected = c
        .sources
        .iter()
        .find(|(_, m)| *m)
        .map(|(s, _)| s.id.clone());
    json!({"runtimeId":runtime,"status":if c.status!="ok" {c.status} else if selected.is_none() {"unmatched"} else if c.sources.iter().any(|(s,m)|*m&&!s.configured) {"unconfigured"} else {"ok"},"selectedSourceId":selected,"sources":c.sources.iter().map(|(s,matched)|json!({"id":s.id,"label":s.label,"kind":s.kind,"status":if !s.configured {"unconfigured"} else if *matched {"matched"}else {"unmatched"},"associated":matched,"autoRefreshSeconds":s.interval,"manualCooldownSeconds":15})).collect::<Vec<_>>()})
}
