//! Bounded recovery of absent local control. Never changes the remote preference.
use pocket_desktop_runtime::connection;
use serde::Serialize;
use serde_json::Value;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackgroundRecovery {
    pub phase: &'static str,
    pub attempts: u8,
    pub max_attempts: u8,
    pub next_attempt_at: Option<u64>,
    pub reason: Option<&'static str>,
}
impl Default for BackgroundRecovery {
    fn default() -> Self {
        Self { phase: "idle", attempts: 0, max_attempts: 3, next_attempt_at: None, reason: None }
    }
}
impl BackgroundRecovery {
    pub fn manual_attempt(&mut self, now: u64, started: bool) {
        *self = Self::default();
        if started {
            self.phase = "starting";
            self.next_attempt_at = Some(now + 30);
        }
    }

    /// Reserve an attempt before spawning; the caller also holds its operation
    /// mutex. Process creation is not recovery: only fresh verified control is.
    pub fn observe(&mut self, report: &Value, now: u64) -> bool {
        if let Some(reason) = blocked(report, now) {
            self.phase = "blocked";
            self.reason = Some(reason);
            self.next_attempt_at = None;
            return false;
        }
        if healthy(report, now) {
            *self = Self { phase: "recovered", ..Self::default() };
            return false;
        }
        self.reason = None;
        if self.phase == "exhausted" { return false; }
        let next = match self.next_attempt_at {
            Some(next) => next,
            None => {
                self.phase = "waiting";
                self.next_attempt_at = Some(now + 5);
                return false;
            }
        };
        if now < next { return false; }
        if self.attempts >= self.max_attempts {
            self.phase = "exhausted";
            self.next_attempt_at = None;
            self.reason = Some("background_unavailable");
            return false;
        }
        self.attempts += 1;
        self.phase = "starting";
        self.next_attempt_at = Some(now + if self.attempts == 1 { 10 } else { 30 });
        true
    }
    pub fn spawn_failed(&mut self) { self.phase = "waiting"; }
}

fn healthy(r: &Value, now: u64) -> bool {
    r["installation"]["helper"]["runningVerified"] == true
        && r["installation"]["helper"]["state"] == "current"
        && connection::fresh(r["connection"]["helperUpdatedAt"].as_u64().unwrap_or(0), now)
}
fn blocked(r: &Value, now: u64) -> Option<&'static str> {
    if r["desired"] != true { return Some("remote_paused"); }
    let installation = &r["installation"];
    if installation["consistent"] != true
        || !installation["installedGeneration"].as_str().is_some_and(|v|
            v.len() == 64 && v.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)))
        || r["issues"].as_array().is_some_and(|issues| issues.iter().any(|i|
            matches!(i["stage"].as_str(), Some("state_read"|"desired_read"|"supervisor_read"|"upgrade_read"|"installation"|"state_access")))) {
        return Some("state_unverified");
    }
    if installation["ui"]["state"] == "restart_required" || ["supervisor", "helper"].iter()
        .any(|name| installation[name]["state"] == "restart_required" && installation[name]["runningVerified"] == true) {
        return Some("update_required");
    }
    if installation["restartRequired"] == true && installation["ui"].is_null()
        && installation["supervisor"].is_null() && installation["helper"].is_null() {
        return Some("state_unverified");
    }
    if let Some(phase) = installation["upgrade"]["phase"].as_str() {
        // An unsupported legacy handoff never fenced or released anything. Its
        // durable next-login notice must not stop the ordinary new supervisor
        // launch after the old owners have gone. Live owners still block below.
        let legacy_wait = phase == "waiting_restart" && installation["upgrade"]["failure"] == "handoff_unsupported";
        if !matches!(phase, "succeeded" | "rolled_back") && !legacy_wait {
            return Some("upgrade_pending");
        }
    }
    if healthy(r, now) { return None; }
    // A live owner or fresh heartbeat gets its own recovery time. Never start a
    // competing supervisor merely because a route, assistant or phone is down.
    if installation["supervisor"]["runningVerified"] == true
        || installation["helper"]["runningVerified"] == true
        || connection::fresh(r["supervisor"]["updatedAt"].as_u64().unwrap_or(0), now)
        || connection::fresh(r["connection"]["helperUpdatedAt"].as_u64().unwrap_or(0), now) {
        return Some("owner_running");
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn absent() -> Value { json!({"desired":true,"installation":{"consistent":true,"installedGeneration":"a".repeat(64)},"supervisor":{"updatedAt":0},"connection":{"helperUpdatedAt":0}}) }

    #[test]
    fn retries_are_bounded_and_wait_for_verification_after_last_spawn() {
        let mut p = BackgroundRecovery::default(); let r = absent();
        for (time, start, phase) in [(100,false,"waiting"),(104,false,"waiting"),(105,true,"starting"),
            (106,false,"starting"),(115,true,"starting"),(145,true,"starting"),(174,false,"starting"),(175,false,"exhausted"),(999,false,"exhausted")] {
            assert_eq!(p.observe(&r,time),start,"{time}"); assert_eq!(p.phase,phase,"{time}");
        }
        assert_eq!(p.attempts,3);
    }
    #[test]
    fn a_spawn_error_keeps_backoff_and_manual_retry_starts_a_fresh_bounded_cycle() {
        let mut p=BackgroundRecovery::default();let r=absent();
        p.observe(&r,100);assert!(p.observe(&r,105));p.spawn_failed();
        assert!(!p.observe(&r,106));assert_eq!(p.attempts,1);
        p.manual_attempt(107,true);assert_eq!(p.attempts,0);
        assert!(!p.observe(&r,136));assert!(p.observe(&r,137));
        p.manual_attempt(140,false);assert!(!p.observe(&r,140));assert!(p.observe(&r,145));
    }
    #[test]
    fn pause_upgrade_unknown_state_and_existing_owners_never_launch() {
        for change in [json!({"desired":false}),json!({"installation":{"consistent":false}}),
            json!({"installation":{"consistent":true}}),
            json!({"installation":{"consistent":true,"installedGeneration":"a".repeat(64),"restartRequired":true}}),
            json!({"installation":{"consistent":true,"installedGeneration":"a".repeat(64),"upgrade":{"phase":"waiting_restart"}}}),
            json!({"installation":{"consistent":true,"installedGeneration":"a".repeat(64),"supervisor":{"runningVerified":true}}}),
            json!({"supervisor":{"updatedAt":100}}),
            json!({"issues":[{"stage":"desired_read"}]}),json!({"issues":[{"stage":"upgrade_read"}]})] {
            let mut r=absent();for (k,v) in change.as_object().unwrap(){r[k]=v.clone();}
            let mut p=BackgroundRecovery::default();assert!(!p.observe(&r,100));assert_eq!(p.phase,"blocked");assert_eq!(p.attempts,0);
        }
    }
    #[test]
    fn only_fresh_verified_control_clears_recovery_even_without_a_network_route() {
        let mut p=BackgroundRecovery::default();let mut r=absent();p.observe(&r,100);p.observe(&r,105);
        r["installation"]["helper"]=json!({"runningVerified":true,"state":"current"});
        assert!(!p.observe(&r,110));assert_eq!(p.phase,"blocked");
        r["connection"]["helperUpdatedAt"]=json!(110);r["connection"]["available"]=json!(false);
        assert!(!p.observe(&r,110));assert_eq!(p.phase,"recovered");assert_eq!(p.attempts,0);
    }
    #[test]
    fn stale_legacy_stamp_does_not_block_next_login_but_live_old_owner_does() {
        let mut r=absent();
        r["installation"]["supervisor"]=json!({"state":"restart_required","runningVerified":false});
        r["installation"]["restartRequired"]=json!(true);
        r["installation"]["upgrade"]=json!({"phase":"waiting_restart","failure":"handoff_unsupported"});
        let mut p=BackgroundRecovery::default();assert!(!p.observe(&r,100));assert!(p.observe(&r,105));
        r["installation"]["supervisor"]["runningVerified"]=json!(true);
        assert!(!p.observe(&r,200));assert_eq!(p.reason,Some("update_required"));
    }
}
