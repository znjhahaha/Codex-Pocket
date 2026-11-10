//! Live transport health. Wall timestamps are display data; freshness is monotonic.
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
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
    failure: Option<RouteFailure>,
}
#[derive(Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RouteFailure { pub stage: &'static str, pub code: &'static str, pub os_code: Option<i32> }
pub struct Connection(
    Mutex<Health>,
    Mutex<BTreeMap<String, RouteHealth>>,
    AtomicUsize,
    AtomicU64,
    AtomicBool,
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
            AtomicBool::new(true),
        )
    }
}
impl Connection {
    pub fn remote_enabled(&self) -> bool { self.4.load(Ordering::Acquire) }
    pub fn set_remote_enabled(&self, enabled: bool) { self.4.store(enabled, Ordering::Release); }
    pub fn generation(&self) -> u64 {
        self.3.load(Ordering::Acquire)
    }
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
        let authenticated = self.remote_enabled() && self.2.load(Ordering::Acquire) > 0;
        if !self.remote_enabled() {
            return json!({"state":"stopped","remoteEnabled":false,"remoteControlProtocol":1,"connected":false,"outerConnected":false,"available":false,"authenticatedPhone":false,"lastActivityAt":h.last_activity,"routeStatus":routes});
        }
        let available = connected
            || route_connected
            || routes.as_array().is_some_and(|rs| {
                rs.iter()
                    .any(|r| r["kind"] != "discovery" && r["id"] != "mdns" && r["listenerReady"] == true)
            });
        json!({"state":if route_connected||authenticated||connected {"connected"}else if available {"ready"}else if h.state=="connected" && !connected{"reconnecting"}else{h.state},"remoteEnabled":true,"remoteControlProtocol":1,"connected":connected||route_connected||authenticated,"outerConnected":connected||route_connected,"available":available,"authenticatedPhone":authenticated,"lastActivityAt":h.last_activity,"routeStatus":routes})
    }
    /// Parent session::serve holds this guard only after successful device hello.
    /// TLS success and pairing invitation exchange never increment this count.
    pub fn authenticated_phone(self: &Arc<Self>) -> AuthenticatedPhone {
        self.2.fetch_add(1, Ordering::AcqRel);
        AuthenticatedPhone(self.clone())
    }
    pub fn reset_routes(&self, routes: &[crate::config::routes::Route]) -> u64 {
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
        generation
    }
    pub fn route_phase(&self, id: &str, phase: &str, ready: bool, endpoint: Option<String>) {
        self.route_phase_at(self.3.load(Ordering::Acquire), id, phase, ready, endpoint);
    }
    pub fn route_phase_at(
        &self,
        generation: u64,
        id: &str,
        phase: &str,
        ready: bool,
        endpoint: Option<String>,
    ) {
        let mut map = self.1.lock().unwrap();
        if generation != self.3.load(Ordering::Acquire) {
            return;
        }
        let r = map.entry(id.into()).or_insert_with(|| RouteHealth {
            generation: self.3.load(Ordering::Acquire),
            kind: if id == "mdns" || id.starts_with("mdns-") { "discovery" } else if id == "lan" || id.starts_with("pocket-lan-auto-") { "lan" } else { "cloudflare" }.into(),
            ..Default::default()
        });
        r.phase = phase.into();
        r.listener_ready = ready;
        r.endpoint = endpoint;
        if ready { r.failure = None; }
        if matches!(phase, "stopped" | "failed" | "reconnecting") {
            r.outer_connected = false;
            r.active_sessions = 0;
        }
    }
    pub fn route_failure_at(&self, generation: u64, id: &str, failure: RouteFailure) {
        self.route_phase_at(generation, id, "failed", false, None);
        let mut map = self.1.lock().unwrap();
        if generation != self.generation() { return; }
        if let Some(route) = map.get_mut(id) { route.failure = Some(failure); }
    }
    pub fn relay_evidence(&self, id: &str, evidence: &Value) {
        self.relay_evidence_at(self.3.load(Ordering::Acquire), id, "", evidence);
    }
    pub fn relay_evidence_at(&self, generation: u64, id: &str, endpoint: &str, evidence: &Value) {
        let mut map = self.1.lock().unwrap();
        if generation != self.3.load(Ordering::Acquire) {
            return;
        }
        let r = map.entry(id.into()).or_default();
        r.endpoint = Some(endpoint.into());
        r.kind = "relay".into();
        r.phase = evidence["state"].as_str().unwrap_or("reconnecting").into();
        r.outer_connected = evidence["connected"] == true;
        r.last_activity = evidence["lastActivityAt"].as_u64();
    }
    pub fn inner_handshake(&self, id: &str) {
        self.inner_handshake_at(self.generation(), id);
    }
    pub fn inner_handshake_at(&self, generation: u64, id: &str) -> bool {
        let mut map = self.1.lock().unwrap();
        if generation != self.generation() {
            return false;
        }
        let r = map.entry(id.into()).or_insert_with(|| RouteHealth {
            generation,
            ..Default::default()
        });
        r.inner_handshake = true;
        r.last_activity = Some(now());
        true
    }
    pub fn session_open(&self, id: &str) -> u64 {
        let mut map = self.1.lock().unwrap();
        let generation = self.generation();
        let r = map.entry(id.into()).or_insert_with(|| RouteHealth {
            generation,
            ..Default::default()
        });
        r.inner_handshake = true;
        r.active_sessions += 1;
        r.last_activity = Some(now());
        r.generation
    }
    pub fn session_open_at(&self, generation: u64, id: &str) -> Option<u64> {
        let mut map = self.1.lock().unwrap();
        if generation != self.generation() {
            return None;
        }
        let r = map.entry(id.into()).or_insert_with(|| RouteHealth {
            generation,
            ..Default::default()
        });
        r.inner_handshake = true;
        r.active_sessions += 1;
        r.last_activity = Some(now());
        Some(generation)
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
        json!(self.1.lock().unwrap().iter().map(|(id,r)|json!({"id":id,"kind":r.kind,"phase":r.phase,"listenerReady":r.listener_ready,"endpoint":r.endpoint,"outerConnected":r.outer_connected,"innerHandshake":r.inner_handshake,"activeSessions":r.active_sessions,"lastActivityAt":r.last_activity,"failure":r.failure})).collect::<Vec<_>>())
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
        {
            let mut map = self.1.lock().unwrap();
            let generation = self.3.fetch_add(1, Ordering::AcqRel) + 1;
            for r in map.values_mut() {
                r.generation = generation;
                r.phase = "stopped".into();
                r.listener_ready = false;
                r.outer_connected = false;
                r.active_sessions = 0;
                r.endpoint = None;
            }
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
    #[test]
    fn retired_ingress_cannot_change_new_generation_or_session_counts() {
        let c = Connection::default();
        let old = c.reset_routes(&[]);
        c.route_phase_at(
            old,
            "lan",
            "listening",
            true,
            Some("tls://192.168.1.2:43821".into()),
        );
        assert!(c.inner_handshake_at(old, "lan"));
        let old_session = c.session_open_at(old, "lan").unwrap();
        let current = c.reset_routes(&[]);
        c.route_phase_at(
            current,
            "lan",
            "listening",
            true,
            Some("tls://192.168.1.3:43821".into()),
        );
        assert!(c.inner_handshake_at(current, "lan"));
        assert_eq!(c.session_open_at(current, "lan"), Some(current));
        let before = c.route_snapshot();

        c.route_phase_at(old, "lan", "stopped", false, None);
        c.relay_evidence_at(
            old,
            "retired-relay",
            "wss://old.example",
            &json!({"state":"connected","connected":true}),
        );
        assert!(!c.inner_handshake_at(old, "lan"));
        assert!(!c.inner_handshake_at(old, "retired-gateway"));
        assert_eq!(c.session_open_at(old, "lan"), None);
        assert_eq!(c.session_open_at(old, "retired-gateway"), None);
        c.session_close_generation("lan", old_session);
        assert_eq!(c.route_snapshot(), before);

        c.session_close_generation("lan", current);
        assert_eq!(c.route_snapshot()[0]["activeSessions"], 0);
    }

    #[test]
    fn legacy_ingress_wrappers_record_current_generation_for_new_rows() {
        let c = Connection::default();
        let current = c.reset_routes(&[]);
        c.inner_handshake("gateway");
        let session = c.session_open("gateway");
        assert_eq!(session, current);
        assert_eq!(c.generation(), current);
        assert_eq!(c.route_snapshot()[0]["activeSessions"], 1);
        c.session_close_generation("gateway", session);
        assert_eq!(c.route_snapshot()[0]["activeSessions"], 0);
    }

    #[test]
    fn stopping_routes_invalidates_pending_ingress_generation() {
        let c = Connection::default();
        let old = c.reset_routes(&[]);
        c.route_phase_at(
            old,
            "gateway",
            "ready",
            true,
            Some("http://127.0.0.1:17892".into()),
        );
        c.stop_routes();
        assert!(c.generation() > old);
        let before = c.snapshot();

        c.route_phase_at(
            old,
            "gateway",
            "ready",
            true,
            Some("http://127.0.0.1:17892".into()),
        );
        assert!(!c.inner_handshake_at(old, "gateway"));
        assert_eq!(c.session_open_at(old, "gateway"), None);
        assert_eq!(c.snapshot(), before);
        assert_eq!(before["state"], "stopped");
    }

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
