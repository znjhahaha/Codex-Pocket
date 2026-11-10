//! Startup evidence from an owned child, never process-liveness evidence.
use crate::storage;
use serde::{Deserialize, Serialize};
use std::{io::Read, path::Path, process::ChildStderr, sync::mpsc};

const LIMIT: usize = 4096;
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartupFailure {
    pub schema: u32,
    pub operation_id: String,
    pub pid: u32,
    pub runtime_generation: Option<String>,
    pub time: u64,
    pub phase: String,
    pub code: String,
    pub os_code: Option<i32>,
}

pub struct Attempt {
    pub operation_id: String,
    pid: u32,
    generation: Option<String>,
    started_ms: u64,
    stderr: mpsc::Receiver<Vec<u8>>,
}
fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default().as_millis().min(u64::MAX as u128) as u64
}
impl Attempt {
    pub fn new(operation_id: String, pid: u32, generation: Option<String>, stderr: Option<ChildStderr>) -> Self {
        let (sender, receiver) = mpsc::sync_channel(1);
        if let Some(stderr) = stderr {
            std::thread::spawn(move || {
                let mut bytes = Vec::new();
                if stderr.take((LIMIT + 1) as u64).read_to_end(&mut bytes).is_ok() {
                    let _ = sender.send(bytes);
                }
            });
        }
        Self { operation_id, pid, generation, started_ms: now_ms(), stderr: receiver }
    }
    pub fn read(&self, state: &Path) -> Option<StartupFailure> {
        // Early state/permission failures may have no writable diagnostic file.
        if let Ok(bytes) = self.stderr.try_recv() {
            if let Some(value) = self.parse(&bytes) { return Some(value); }
        }
        let path = state.join("startup-failure.json");
        storage::unredirected(&path).ok()?;
        let mut bytes = Vec::new();
        std::fs::File::open(&path).ok()?.take((LIMIT + 1) as u64).read_to_end(&mut bytes).ok()?;
        self.parse(&bytes)
    }
    fn parse(&self, bytes: &[u8]) -> Option<StartupFailure> {
        if bytes.len() > LIMIT { return None; }
        let value: StartupFailure = serde_json::from_slice(bytes).ok()?;
        let phase_matches = matches!((value.phase.as_str(), value.code.as_str()),
            ("state_path", "state_path_invalid") | ("state_access", "state_access_failed") |
            ("state_lock", "state_lock_failed" | "state_lock_contended") |
            ("config", "config_load_failed") | ("identity", "identity_load_failed") |
            ("broker", "broker_open_failed") | ("control_bind", "control_bind_failed"));
        (value.schema == 1 && value.pid == self.pid && value.operation_id == self.operation_id &&
            value.runtime_generation == self.generation && phase_matches &&
            value.time >= self.started_ms.saturating_sub(2_000) && value.time <= now_ms().saturating_add(2_000))
            .then_some(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn record(a: &Attempt) -> serde_json::Value {
        json!({"schema":1,"operationId":a.operation_id,"pid":a.pid,"runtimeGeneration":a.generation,
            "time":now_ms(),"phase":"config","code":"config_load_failed","osCode":5})
    }
    #[test]
    fn accepts_only_this_owned_attempt_and_fixed_public_fields() {
        let a = Attempt::new(uuid::Uuid::new_v4().to_string(), 42, Some("a".repeat(64)), None);
        let valid = record(&a);
        assert_eq!(a.parse(&serde_json::to_vec(&valid).unwrap()).unwrap().os_code, Some(5));
        for (field, bad) in [("operationId", json!(uuid::Uuid::new_v4().to_string())), ("pid",json!(43)),
            ("runtimeGeneration",json!("b".repeat(64))), ("phase",json!("PRIVATE")),
            ("code",json!("PRIVATE")), ("time",json!(1)), ("schema",json!(2)), ("rawError",json!("PRIVATE"))] {
            let mut value=valid.clone();value[field]=bad;
            assert!(a.parse(&serde_json::to_vec(&value).unwrap()).is_none(),"{field}");
        }
        assert!(a.parse(&vec![b' ';LIMIT+1]).is_none());
        assert!(a.parse(b"private stderr text").is_none());
    }
    #[test]
    fn missing_or_stale_file_is_not_a_current_startup_failure() {
        let dir=crate::test_support::tempdir();
        let a=Attempt::new(uuid::Uuid::new_v4().to_string(),42,None,None);
        assert!(a.read(dir.path()).is_none());
        let mut value=record(&a);value["operationId"]=json!(uuid::Uuid::new_v4().to_string());
        storage::atomic(&dir.path().join("startup-failure.json"),&value).unwrap();
        assert!(a.read(dir.path()).is_none());
        storage::atomic(&dir.path().join("startup-failure.json"),&record(&a)).unwrap();
        assert_eq!(a.read(dir.path()).unwrap().code,"config_load_failed");
    }
}
