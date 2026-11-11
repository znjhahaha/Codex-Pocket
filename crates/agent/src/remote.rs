//! Pauses phone ingress without dropping the broker or native model runtimes.
use crate::{broker::Broker, config::routes, storage, transport::TlsSession};
use pocket_tunnel::ServerIdentity;
use std::{path::{Path, PathBuf}, sync::Arc, time::Duration};
use tokio::sync::mpsc;

pub fn desired(state: &Path) -> bool {
    storage::read_physical(&state.join("desktop/desired.json"), 4096).ok()
        .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
        .is_some_and(|value| value["enabled"] == true)
}

pub async fn run(state: PathBuf, supervised: bool, controller: Arc<routes::Controller>,
    identity: Arc<ServerIdentity>, sessions: mpsc::Sender<TlsSession>, broker: Arc<Broker>) {
    let mut runtime = None;
    let mut timer = tokio::time::interval(Duration::from_millis(250));
    timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        timer.tick().await;
        let desired_enabled = !supervised || desired(&state);
        // Fence new phone requests before tearing down transport; already-dispatched
        // commands finish their original durable ledger entries, never get replayed.
        if let Some(handoff)=broker.handoff.get() {
            handoff.apply_remote_preference(desired_enabled,|enabled|broker.connection.set_remote_enabled(enabled));
        }else{broker.connection.set_remote_enabled(desired_enabled);}
        let enabled=broker.connection.remote_enabled();
        if enabled && runtime.is_none() {
            controller.resume().await;
            runtime = Some(routes::Runtime::start(controller.clone(), identity.clone(), sessions.clone()));
        } else if !enabled {
            if let Some(old) = runtime.take() { old.shutdown().await; }
            broker.connection.set("stopped");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn missing_corrupt_or_disabled_desire_fails_closed() {
        let d = tempfile::tempdir().unwrap();
        assert!(!desired(d.path()));
        let path = d.path().join("desktop/desired.json");
        for (data, expected) in [(b"invalid".as_slice(), false), (br#"{"enabled":false}"#, false),
            (br#"{"enabled":"true"}"#, false), (br#"{"enabled":true}"#, true)] {
            storage::atomic_write(&path, data).unwrap();
            assert_eq!(desired(d.path()), expected);
        }
    }
}
