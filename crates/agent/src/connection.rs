//! Live transport health. Wall timestamps are display data; freshness is monotonic.
use serde_json::{Value, json};
use std::{
    sync::Mutex,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::time::Instant;

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
struct Health {
    state: &'static str,
    received: Option<Instant>,
    last_activity: Option<u64>,
}
pub struct Connection(Mutex<Health>);
impl Default for Connection {
    fn default() -> Self {
        Self(Mutex::new(Health {
            state: "connecting",
            received: None,
            last_activity: None,
        }))
    }
}
impl Connection {
    pub fn set(&self, state: &'static str) {
        self.0.lock().unwrap().state = state;
    }
    pub fn activity(&self) {
        let mut h = self.0.lock().unwrap();
        h.state = "connected";
        h.received = Some(Instant::now());
        h.last_activity = Some(now());
    }
    pub fn snapshot(&self) -> Value {
        let h = self.0.lock().unwrap();
        let connected = h.state == "connected"
            && h.received
                .is_some_and(|i| i.elapsed() < Duration::from_secs(60));
        json!({"state":if h.state=="connected" && !connected{"reconnecting"}else{h.state},"connected":connected,"lastActivityAt":h.last_activity})
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test(start_paused = true)]
    async fn v02_transport_freshness_expires_even_without_disconnect() {
        let c = Connection::default();
        assert_eq!(c.snapshot()["state"], "connecting");
        c.activity();
        assert_eq!(c.snapshot()["connected"], true);
        tokio::time::advance(Duration::from_secs(61)).await;
        assert_eq!(c.snapshot()["connected"], false);
        assert_eq!(c.snapshot()["state"], "reconnecting");
        c.activity();
        assert_eq!(c.snapshot()["connected"], true);
        c.set("reconnecting");
        assert_eq!(c.snapshot()["connected"], false);
    }
}
