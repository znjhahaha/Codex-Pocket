//! Projections of authenticated broker evidence. No state-file or model fallback.
use serde_json::{Value, json};

pub fn readiness(health: &Value, now: u64) -> Value {
    let current = health["protocol"] == 1
        && crate::connection::fresh(health["updatedAt"].as_u64().unwrap_or(0), now);
    let rows:Vec<_>=health["runtimes"].as_array().map(Vec::as_slice).unwrap_or(&[]).iter().take(128).map(|r| {
        let role=r["role"]["kind"].as_str().unwrap_or(if r["source"]=="desktop"{"desktop"}else{"unknown"});
        let stage=r["readiness"]["stage"].as_str();
        let initialized=r["initialized"].as_bool().or_else(||stage.map(|v|matches!(v,"native_schema"|"session_inventory"|"ready")));
        let inventory=r["inventoryReady"]==true || stage==Some("ready");
        let schema=r["schemaState"]=="ready" || r["readiness"]["schemaState"]=="ready";
        let ready=current && r["online"]==true && initialized==Some(true) && inventory && schema;
        json!({"id":r["id"],"source":r["source"],"role":role,"online":current&&r["online"]==true,
            "initialized":initialized,"schemaReady":schema,"inventoryReady":inventory,"ready":ready,
            "reason":if !current{"evidence_stale"}else if r["online"]!=true{"offline"}else if initialized!=Some(true){"native_initialization_pending"}else if !schema{"schema_pending"}else if !inventory{"inventory_pending"}else{"ready"}})
    }).chain(health["auxiliaryRuntimes"].as_array().map(Vec::as_slice).unwrap_or(&[]).iter().take(128).map(|r|json!({
        "id":r["id"],"source":"desktop","role":"auxiliary","online":current&&r["online"]==true,
        "initialized":null,"schemaReady":false,"inventoryReady":false,"ready":false,"reason":"auxiliary_only"}))).collect();
    let runtime_ready = rows
        .iter()
        .any(|r| r["ready"] == true && matches!(r["role"].as_str(), Some("desktop" | "cliDaemon")));
    json!({"authoritative":current,"updatedAt":health["updatedAt"],"ready":runtime_ready&&health["acceptingWrites"]==true,
        "installed":health["nativeInstallation"]["nativeInstalled"],"cliInstalled":health["nativeInstallation"]["cliInstalled"],"desktopInstalled":health["nativeInstallation"]["desktopInstalled"],"historyReadable":health["nativeInstallation"]["profiles"].as_array().is_some_and(|p|p.iter().any(|r|r["historyAvailable"]==true)),
        "nativeObservation":if current {health["nativeObservation"].clone()}else{Value::Null},
        "acceptingWrites":current&&health["acceptingWrites"]==true,"runtimes":rows})
}

/// Missing counters are unknown. A fresh, explicit complete idle snapshot is
/// required before automatic handoff; the coordinator rechecks and fences it.
pub fn idle_for_update(health: &Value, now: u64) -> bool {
    let l = &health["lifecycle"];
    health["protocol"] == 1
        && crate::connection::fresh(health["updatedAt"].as_u64().unwrap_or(0), now)
        && crate::connection::fresh(l["observedAt"].as_u64().unwrap_or(0), now)
        && l["observedAt"].as_u64() >= health["updatedAt"].as_u64()
        && health["acceptingWrites"] == true
        && l["idle"] == true
        && l["nativeTasksPreserved"] == true
        && [
            "pendingWrites",
            "activeTurns",
            "pendingQuestions",
            "unknownOperations",
        ]
        .iter()
        .all(|n| l[n].as_u64() == Some(0))
}

/// The helper checkpoint carries exact broker tombstones. The stricter
/// native shutdown gate still requires zero unknown operations everywhere.
pub fn ready_for_helper_handoff(health: &Value, now: u64) -> bool {
    if idle_for_update(health, now) { return true; }
    let live = &health["lifecycle"];
    live["unknownOperations"].as_u64().is_some_and(|count| count > 0
        && live["checkpointableUnknownOperations"].as_u64() == Some(count))
        && health["protocol"] == 1 && health["acceptingWrites"] == true
        && live["nativeTasksPreserved"] == true && live["idle"] == false
        && live["observedAt"].as_u64() >= health["updatedAt"].as_u64()
        && crate::connection::fresh(live["observedAt"].as_u64().unwrap_or(0), now)
        && crate::connection::fresh(health["updatedAt"].as_u64().unwrap_or(0), now)
        && ["pendingWrites", "activeTurns", "pendingQuestions"].iter().all(|field| live[field].as_u64() == Some(0))
}

pub fn update_wait_reason(health: &Value, now: u64) -> Option<&'static str> {
    let live = &health["lifecycle"];
    let heartbeat = health["updatedAt"].as_u64()?;
    let observed = live["observedAt"].as_u64()?;
    if health["protocol"] != 1
        || observed < heartbeat
        || !crate::connection::fresh(heartbeat, now)
        || !crate::connection::fresh(observed, now)
    {
        return None;
    }
    if live["unknownOperations"].as_u64().is_some_and(|n| n > 0) {
        Some("unconfirmed_operations")
    } else if live["reason"] == "runtime_activity_unverified" {
        Some("runtime_activity_unverified")
    } else {
        None
    }
}

/// Export fixed fields only: no native paths, threads, tokens, or raw errors.
pub fn public_readiness(value: &Value) -> Value {
    let rows:Vec<_>=value["runtimes"].as_array().map(Vec::as_slice).unwrap_or(&[]).iter().map(|r|json!({
        "role":r["role"],"online":r["online"]==true,"initialized":r["initialized"].as_bool(),
        "schemaReady":r["schemaReady"]==true,"inventoryReady":r["inventoryReady"]==true,"ready":r["ready"]==true})).collect();
    json!({"authoritative":value["authoritative"]==true,"ready":value["ready"]==true,"updatedAt":value["updatedAt"],"runtimes":rows})
}

#[cfg(test)]
mod external_detection_tests {
    use super::*;

    #[test]
    fn exposes_detection_without_promoting_it_to_control_readiness() {
        let native = json!({"desktop":{"state":"detected","version":"26.1111.2228.0"},"cli":{"state":"detected"}});
        let health = json!({"protocol":1,"updatedAt":100,"acceptingWrites":true,"nativeObservation":native,"runtimes":[]});
        let report = readiness(&health, 100);
        assert_eq!(report["nativeObservation"], native);
        assert_eq!(report["ready"], false);
        assert_eq!(readiness(&health, 150)["nativeObservation"], Value::Null);
    }
    #[test]
    fn waiting_update_surfaces_missing_activity_evidence_not_ordinary_task_wait() {
        let health = json!({"protocol":1,"updatedAt":100,"lifecycle":{"observedAt":100,"reason":"runtime_activity_unverified"}});
        assert_eq!(
            update_wait_reason(&health, 100),
            Some("runtime_activity_unverified")
        );
        assert_eq!(update_wait_reason(&health, 150), None);
        let mut health = health;
        health["lifecycle"]["unknownOperations"] = json!(6);
        assert_eq!(
            update_wait_reason(&health, 100),
            Some("unconfirmed_operations")
        );
    }
}
