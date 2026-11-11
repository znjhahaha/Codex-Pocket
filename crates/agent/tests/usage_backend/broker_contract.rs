//! Minimal host for compiling the actual broker child; full ingress remains the main controller's gate.
use std::{collections::BTreeMap, path::PathBuf};
use tokio::sync::Mutex;
pub struct Runtime {
    pub bridge_version: Option<String>,
}
pub struct State {
    pub devices: BTreeMap<String, ()>,
    pub runtimes: BTreeMap<String, Runtime>,
}
pub struct Broker {
    pub state: Mutex<State>,
    pub state_dir: PathBuf,
}
impl Broker {
    pub async fn device_exists(&self, device: &str) -> bool {
        self.state.lock().await.devices.contains_key(device)
    }
}
#[path = "../../src/broker_usage.rs"]
mod usage_backend;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::{self, Code};
    use crate::usage_stats::{self, service::UsageService};
    use serde_json::json;
    fn broker(state_dir: PathBuf) -> Broker {
        Broker {
            state_dir,
            state: Mutex::new(State {
                devices: BTreeMap::from([("phone".into(), ())]),
                runtimes: BTreeMap::new(),
            }),
        }
    }
    #[tokio::test]
    async fn broker_child_preserves_paired_read_and_rejects_remote_paths() {
        let dir = tempfile::tempdir().unwrap();
        let b = broker(dir.path().into());
        let service = UsageService::test_service(Some(dir.path().join("missing.db")));
        let rejected = b
            .usage_with_service("unknown", "usage/read", &json!({}), &service)
            .await
            .unwrap_err();
        assert_eq!(error::code(&rejected), Code::Revoked);
        for (method, p) in [
            ("usage/read", json!({"path":"private"})),
            ("usage/sources", json!({"query":{}})),
            ("usage/read", json!({"query":{"start_at":0}})),
        ] {
            assert_eq!(
                error::code(
                    &b.usage_with_service("phone", method, &p, &service)
                        .await
                        .unwrap_err()
                ),
                Code::InvalidParams
            );
        }
        let r = b
            .usage_with_service("phone", "usage/read", &json!({}), &service)
            .await
            .unwrap();
        assert_eq!(r["result"]["availability"], "missing");
        assert_eq!(r["query"]["filters"]["app_type"], "codex");
        let r = b
            .usage_with_service("phone", "usage/sources", &json!({}), &service)
            .await
            .unwrap();
        assert_eq!(r["sources"][0]["availability"], "missing");
    }
    #[tokio::test]
    async fn revocation_while_usage_waits_suppresses_the_response() {
        let dir = tempfile::tempdir().unwrap();
        let b = broker(dir.path().into());
        let service = UsageService::test_service(Some(dir.path().join("missing.db")));
        let _permits = service.hold_slots_for_test().await;
        let params = json!({});
        let read = b.usage_with_service("phone", "usage/read", &params, &service);
        let revoke = async {
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            b.state.lock().await.devices.remove("phone");
        };
        let (result, _) = tokio::join!(read, revoke);
        assert_eq!(error::code(&result.unwrap_err()), Code::Revoked);
    }
    #[tokio::test]
    async fn diagnostics_child_uses_existing_bridge_versions_and_unknown_host() {
        let dir = tempfile::tempdir().unwrap();
        let b = broker(dir.path().into());
        b.state.lock().await.runtimes.insert(
            "r".into(),
            Runtime {
                bridge_version: Some("0.6.3".into()),
            },
        );
        let result = b.upstream_version_snapshot().await;
        assert_eq!(result["bridges"][0]["runtime_id"], "r");
        assert_eq!(result["bridges"][0]["version"], "0.6.3");
        assert_eq!(result["host"]["state"], "unknown");
        // Production wrapper checks authorization even on its fixed process singleton.
        assert_eq!(
            error::code(
                &b.usage_request("unknown", "usage/read", &json!({}))
                    .await
                    .unwrap_err()
            ),
            Code::Revoked
        );
        let _ = usage_stats::service::current_user_service();
    }
}
