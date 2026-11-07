//! Numeric-only native usage snapshots and bounded, identity-checked rollout recovery.
use anyhow::{Result, bail};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    fs::File,
    io::{BufRead, BufReader, Read, Seek, SeekFrom},
    path::Path,
};

pub const TAIL_LIMIT: u64 = 4 * 1024 * 1024;
pub const MAX_THREADS: usize = 256;
#[derive(Clone, Default)]
pub struct UsageCache(BTreeMap<String, Value>);
impl UsageCache {
    pub fn stale(mut self) -> Self {
        for value in self.0.values_mut() {
            value["status"] = json!("stale");
        }
        self
    }
    pub fn get(&self, thread: &str) -> Option<Value> {
        self.0.get(thread).cloned()
    }
    pub fn put(&mut self, thread: &str, snapshot: Value) {
        if !crate::settings::valid_thread(thread) || snapshot["usage"].is_null() {
            return;
        }
        if !self.0.contains_key(thread)
            && self.0.len() >= MAX_THREADS
            && let Some(key) = self
                .0
                .iter()
                .min_by_key(|(_, v)| v["updatedAt"].as_u64().unwrap_or(0))
                .map(|(k, _)| k.clone())
        {
            self.0.remove(&key);
        }
        self.0.insert(thread.into(), snapshot);
    }
    pub fn event(&mut self, value: &Value) {
        if (value["method"] == "thread/compacted"
            || (value["method"] == "item/completed"
                && value["params"]["item"]["type"] == "contextCompaction"))
            && let Some(v) = value["params"]["threadId"]
                .as_str()
                .and_then(|id| self.0.get_mut(id))
        {
            v["status"] = json!("stale");
        }
        if value["method"] != "thread/tokenUsage/updated" {
            return;
        }
        let p = &value["params"];
        if let Some(thread) = p["threadId"].as_str()
            && let Some(v) = snapshot(
                &p["tokenUsage"],
                "native_event",
                crate::storage::now().saturating_mul(1000),
                p["turnId"].as_str(),
            )
        {
            self.put(thread, v);
        }
    }
}
fn breakdown(v: &Value) -> Option<Value> {
    let mut out = serde_json::Map::new();
    for key in [
        "inputTokens",
        "cachedInputTokens",
        "cacheWriteInputTokens",
        "outputTokens",
        "reasoningOutputTokens",
        "totalTokens",
    ] {
        if let Some(n) = v.get(key) {
            out.insert(
                key.into(),
                json!(n.as_u64().filter(|n| *n <= i64::MAX as u64)?),
            );
        }
    }
    for required in ["inputTokens", "outputTokens", "totalTokens"] {
        if !out.contains_key(required) {
            return None;
        }
    }
    Some(Value::Object(out))
}
pub fn snapshot(usage: &Value, source: &str, time: u64, turn: Option<&str>) -> Option<Value> {
    let last = breakdown(&usage["last"])?;
    let total = breakdown(&usage["total"])?;
    let window = usage["modelContextWindow"]
        .as_u64()
        .filter(|n| *n > 0 && *n <= i64::MAX as u64);
    Some(
        json!({"status":"ok","source":source,"updatedAt":time,"turnId":turn.filter(|t|crate::settings::valid_thread(t)),"usage":{"last":last,"total":total,"modelContextWindow":window}}),
    )
}
pub fn empty(status: &str) -> Value {
    json!({"status":status,"source":null,"updatedAt":null,"turnId":null,"usage":null})
}
fn legacy_breakdown(v: &Value) -> Value {
    let mut out = serde_json::Map::new();
    for (old, new) in [
        ("input_tokens", "inputTokens"),
        ("cached_input_tokens", "cachedInputTokens"),
        ("cache_write_input_tokens", "cacheWriteInputTokens"),
        ("output_tokens", "outputTokens"),
        ("reasoning_output_tokens", "reasoningOutputTokens"),
        ("total_tokens", "totalTokens"),
    ] {
        if let Some(value) = v.get(old) {
            out.insert(new.into(), value.clone());
        }
    }
    Value::Object(out)
}
// Rollouts use RFC3339 UTC timestamps. Unknown timestamp forms stay unknown.
fn timestamp_ms(value: &Value) -> Option<u64> {
    let text = value.as_str()?;
    let (date, time) = text.strip_suffix('Z')?.split_once('T')?;
    let d: Vec<i64> = date
        .split('-')
        .map(str::parse)
        .collect::<Result<_, _>>()
        .ok()?;
    let t: Vec<&str> = time.split(':').collect();
    if d.len() != 3 || t.len() != 3 {
        return None;
    }
    let (y, m, day) = (d[0], d[1], d[2]);
    if !(1970..=9999).contains(&y) || !(1..=12).contains(&m) {
        return None;
    }
    let leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0);
    let days = [
        31,
        if leap { 29 } else { 28 },
        31,
        30,
        31,
        30,
        31,
        31,
        30,
        31,
        30,
        31,
    ];
    if day < 1 || day > days[(m - 1) as usize] {
        return None;
    }
    let hour: i64 = t[0].parse().ok()?;
    let minute: i64 = t[1].parse().ok()?;
    let (seconds, fraction) = t[2].split_once('.').unwrap_or((t[2], ""));
    let second: i64 = seconds.parse().ok()?;
    if !(0..24).contains(&hour)
        || !(0..60).contains(&minute)
        || !(0..60).contains(&second)
        || !fraction.bytes().all(|b| b.is_ascii_digit())
    {
        return None;
    }
    let ms: f64 = if fraction.is_empty() {
        0.0
    } else {
        format!("0.{fraction}").parse::<f64>().ok()? * 1000.0
    };
    let adjusted = y - i64::from(m <= 2);
    let era = adjusted / 400;
    let yoe = adjusted - era * 400;
    let doy = (153 * (m + if m > 2 { -3 } else { 9 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    u64::try_from(
        ((era * 146097 + doe - 719468) * 86400 + hour * 3600 + minute * 60 + second) * 1000
            + ms as i64,
    )
    .ok()
}
fn reject_links(path: &Path) -> Result<()> {
    for part in path.ancestors() {
        let meta = std::fs::symlink_metadata(part)?;
        if meta.is_symlink() {
            bail!("History path is not a regular native path");
        }
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            if meta.file_attributes() & 0x400 != 0 {
                bail!("History path is not a regular native path");
            }
        }
    }
    Ok(())
}
pub fn read_history(
    home: &Path,
    store: &str,
    thread: &str,
    metadata: &Value,
) -> Result<Option<Value>> {
    if metadata["id"] != thread || crate::storage::config_store(home)? != store {
        bail!("History identity mismatch");
    }
    let Some(raw) = metadata["path"].as_str() else {
        return Ok(None);
    };
    let path = Path::new(raw);
    if !path.is_absolute() {
        bail!("History path must be native absolute path");
    }
    reject_links(path)?;
    let root = dunce::canonicalize(home)?;
    let resolved = dunce::canonicalize(path)?;
    if !(resolved.starts_with(root.join("sessions"))
        || resolved.starts_with(root.join("archived_sessions")))
    {
        bail!("History path is outside native store");
    }
    let mut file = File::open(&resolved)?;
    if !file.metadata()?.is_file() {
        bail!("History is not a regular file");
    }
    // Validate the native session header on the same open handle used for the tail.
    let mut header = Vec::new();
    BufReader::new((&mut file).take(65536)).read_until(b'\n', &mut header)?;
    if header.last() != Some(&b'\n') {
        bail!("History header exceeds limit");
    }
    let meta: Value = serde_json::from_slice(&header)?;
    if meta["type"] != "session_meta" || meta["payload"]["id"] != thread {
        bail!("History session mismatch");
    }
    let size = file.metadata()?.len();
    let start = size.saturating_sub(TAIL_LIMIT);
    file.seek(SeekFrom::Start(start))?;
    let mut bytes = Vec::new();
    file.take(TAIL_LIMIT).read_to_end(&mut bytes)?;
    let mut lines = bytes.split(|b| *b == b'\n');
    if start != 0 {
        lines.next();
    }
    let mut found = None;
    let mut turn = None;
    for line in lines {
        let Ok(v) = serde_json::from_slice::<Value>(line) else {
            continue;
        };
        if v["type"] == "turn_context" {
            turn = v["payload"]["turn_id"].as_str().map(str::to_owned);
        }
        if v["type"] != "event_msg" || v["payload"]["type"] != "token_count" {
            continue;
        }
        let info = &v["payload"]["info"];
        let usage = json!({"last":legacy_breakdown(&info["last_token_usage"]),"total":legacy_breakdown(&info["total_token_usage"]),"modelContextWindow":info["model_context_window"]});
        if let Some(mut value) = snapshot(&usage, "history", 0, turn.as_deref()) {
            value["updatedAt"] = json!(timestamp_ms(&v["timestamp"]));
            found = Some(value);
        }
    }
    Ok(found)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn usage() -> Value {
        json!({"last":{"inputTokens":10,"outputTokens":2,"totalTokens":12,"body":"secret"},"total":{"inputTokens":100,"outputTokens":20,"totalTokens":120},"modelContextWindow":1000})
    }
    #[test]
    fn v051_history_timestamp_is_original_not_reload_time() {
        assert_eq!(timestamp_ms(&json!("1970-01-01T00:00:01.234Z")), Some(1234));
        assert_eq!(
            timestamp_ms(&json!("2026-11-07T00:00:00Z")),
            Some(1794009600000)
        );
        assert!(timestamp_ms(&json!("2026-02-30T00:00:00Z")).is_none());
    }
    #[test]
    fn v051_numeric_cache_never_clears_on_missing_or_malformed() {
        let mut c = UsageCache::default();
        c.event(&json!({"method":"thread/tokenUsage/updated","params":{"threadId":"t","turnId":"u","tokenUsage":usage()}}));
        c.event(&json!({"method":"thread/tokenUsage/updated","params":{"threadId":"t","tokenUsage":null}}));
        assert_eq!(c.get("t").unwrap()["usage"]["last"]["totalTokens"], 12);
        assert!(!c.get("t").unwrap().to_string().contains("secret"));
        c.event(&json!({"method":"thread/compacted","params":{"threadId":"t"}}));
        assert_eq!(c.get("t").unwrap()["status"], "stale");
        assert_eq!(c.get("t").unwrap()["usage"]["last"]["totalTokens"], 12);
        let mut negative = usage();
        negative["last"]["inputTokens"] = json!(-1);
        assert!(snapshot(&negative, "native_event", 0, None).is_none());
    }
    #[test]
    fn v051_tail_requires_store_path_and_session_identity() {
        let d = tempfile::tempdir().unwrap();
        std::fs::create_dir(d.path().join("sessions")).unwrap();
        let p = d.path().join("sessions/test.jsonl");
        let content = format!(
            "{}\n{}\n{}\n",
            json!({"type":"session_meta","payload":{"id":"t"}}),
            json!({"type":"turn_context","payload":{"turn_id":"u"}}),
            json!({"type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12},"total_token_usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120},"model_context_window":1000},"secret":"private"}})
        );
        std::fs::write(&p, &content).unwrap();
        let store = crate::storage::config_store(d.path()).unwrap();
        let v = read_history(d.path(), &store, "t", &json!({"id":"t","path":p}))
            .unwrap()
            .unwrap();
        assert_eq!(v["turnId"], "u");
        assert_eq!(v["usage"]["last"]["totalTokens"], 12);
        assert!(!v.to_string().contains("private"));
        assert!(read_history(d.path(), "wrong", "t", &json!({"id":"t","path":p})).is_err());
        assert!(read_history(d.path(), &store, "other", &json!({"id":"other","path":p})).is_err());
        let outside = d.path().join("other.jsonl");
        std::fs::write(&outside, &content).unwrap();
        assert!(read_history(d.path(), &store, "t", &json!({"id":"t","path":outside})).is_err());
        let huge = format!(
            "{}\n{}\n",
            json!({"type":"session_meta","payload":{"id":"t"}}),
            "x".repeat(TAIL_LIMIT as usize + 64)
        );
        std::fs::write(&p, huge).unwrap();
        assert!(
            read_history(d.path(), &store, "t", &json!({"id":"t","path":p}))
                .unwrap()
                .is_none()
        );
    }
}
