//! Live transport health. Wall timestamps are display data; freshness is monotonic.
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, AtomicUsize, Ordering},
    },
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
#[derive(Default)]
struct RouteHealth {
    generation: u64,
    kind: String,
    phase: String,
    listener_ready: bool,
    endpoint: Option<String>,
    outer_connected: bool,
    inner_handshake: bool,
    active_sessions: usize,
    last_activity: Option<u64>,
}
pub struct Connection(
    Mutex<Health>,
    Mutex<BTreeMap<String, RouteHealth>>,
    AtomicUsize,
    AtomicU64,
);
impl Default for Connection {
    fn default() -> Self {
        Self(
            Mutex::new(Health {
                state: "connecting",
                received: None,
                last_activity: None,
            }),
            Mutex::new(BTreeMap::new()),
            AtomicUsize::new(0),
            AtomicU64::new(0),
        )
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
        let routes = self.route_snapshot();
        let route_connected = routes
            .as_array()
            .is_some_and(|rs| rs.iter().any(|r| r["outerConnected"] == true));
        let authenticated = self.2.load(Ordering::Acquire) > 0;
        let available = connected
            || route_connected
            || routes.as_array().is_some_and(|rs| {
                rs.iter()
                    .any(|r| r["id"] != "mdns" && r["listenerReady"] == true)
            });
        json!({"state":if route_connected||authenticated||connected {"connected"}else if available {"ready"}else if h.state=="connected" && !connected{"reconnecting"}else{h.state},"connected":connected||route_connected||authenticated,"outerConnected":connected||route_connected,"available":available,"authenticatedPhone":authenticated,"lastActivityAt":h.last_activity,"routeStatus":routes})
    }
    /// Parent session::serve holds this guard only after successful device hello.
    /// TLS success and pairing invitation exchange never increment this count.
    pub fn authenticated_phone(self: &Arc<Self>) -> AuthenticatedPhone {
        self.2.fetch_add(1, Ordering::AcqRel);
        AuthenticatedPhone(self.clone())
    }
    pub fn reset_routes(&self, routes: &[crate::config::routes::Route]) {
        let mut map = self.1.lock().unwrap();
        let generation = self.3.fetch_add(1, Ordering::AcqRel) + 1;
        map.clear();
        for r in routes {
            map.insert(
                r.id.clone(),
                RouteHealth {
                    generation,
                    kind: serde_json::to_value(r.kind)
                        .unwrap()
                        .as_str()
                        .unwrap()
                        .into(),
                    phase: "configured".into(),
                    ..Default::default()
                },
            );
        }
    }
    pub fn route_phase(&self, id: &str, phase: &str, ready: bool, endpoint: Option<String>) {
        let mut map = self.1.lock().unwrap();
        let r = map.entry(id.into()).or_insert_with(|| RouteHealth {
            generation: self.3.load(Ordering::Acquire),
            kind: if id == "lan" { "lan" } else { "cloudflare" }.into(),
            ..Default::default()
        });
        r.phase = phase.into();
        r.listener_ready = ready;
        r.endpoint = endpoint;
        if matches!(phase, "stopped" | "failed" | "reconnecting") {
            r.outer_connected = false;
            r.active_sessions = 0;
        }
    }
    pub fn relay_evidence(&self, id: &str, evidence: &Value) {
        let mut map = self.1.lock().unwrap();
        let r = map.entry(id.into()).or_default();
        r.kind = "relay".into();
        r.phase = evidence["state"].as_str().unwrap_or("reconnecting").into();
        r.outer_connected = evidence["connected"] == true;
        r.last_activity = evidence["lastActivityAt"].as_u64();
    }
    pub fn inner_handshake(&self, id: &str) {
        let mut map = self.1.lock().unwrap();
        let r = map.entry(id.into()).or_default();
        r.inner_handshake = true;
        r.last_activity = Some(now());
    }
    pub fn session_open(&self, id: &str) -> u64 {
        let mut map = self.1.lock().unwrap();
        let r = map.entry(id.into()).or_default();
        r.inner_handshake = true;
        r.active_sessions += 1;
        r.last_activity = Some(now());
        r.generation
    }
    pub fn session_close(&self, id: &str) {
        if let Some(r) = self.1.lock().unwrap().get_mut(id) {
            r.active_sessions = r.active_sessions.saturating_sub(1);
        }
    }
    pub fn session_close_generation(&self, id: &str, generation: u64) {
        if let Some(r) = self.1.lock().unwrap().get_mut(id) {
            if r.generation == generation {
                r.active_sessions = r.active_sessions.saturating_sub(1);
            }
        }
    }
    pub fn route_snapshot(&self) -> Value {
        json!(self.1.lock().unwrap().iter().map(|(id,r)|json!({"id":id,"kind":r.kind,"phase":r.phase,"listenerReady":r.listener_ready,"endpoint":r.endpoint,"outerConnected":r.outer_connected,"innerHandshake":r.inner_handshake,"activeSessions":r.active_sessions,"lastActivityAt":r.last_activity})).collect::<Vec<_>>())
    }
    pub fn gateway_endpoint(&self) -> Option<String> {
        self.1
            .lock()
            .unwrap()
            .get("gateway")
            .filter(|r| r.listener_ready)
            .and_then(|r| r.endpoint.clone())
    }
    pub fn stop_routes(&self) {
        for r in self.1.lock().unwrap().values_mut() {
            r.phase = "stopped".into();
            r.listener_ready = false;
            r.outer_connected = false;
            r.active_sessions = 0;
            r.endpoint = None;
        }
        self.set("stopped");
    }
}
pub struct AuthenticatedPhone(Arc<Connection>);
impl Drop for AuthenticatedPhone {
    fn drop(&mut self) {
        self.0.2.fetch_sub(1, Ordering::AcqRel);
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
