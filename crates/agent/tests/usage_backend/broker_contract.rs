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
    async fn explicit_refresh_is_validated_and_bypasses_each_read_cache() {
        assert_eq!(
            usage_stats::service::EXPLICIT_REFRESH_CAPABILITY,
            "usage/refresh"
        );
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("usage.db");
        let conn = rusqlite::Connection::open(&path).unwrap();
        conn.execute_batch("PRAGMA user_version=27;
            CREATE TABLE proxy_request_logs(request_id TEXT PRIMARY KEY,provider_id TEXT,app_type TEXT,model TEXT,
                input_tokens INTEGER,output_tokens INTEGER,cache_read_tokens INTEGER,cache_creation_tokens INTEGER,input_token_semantics INTEGER,
                input_cost_usd TEXT,output_cost_usd TEXT,cache_read_cost_usd TEXT,cache_creation_cost_usd TEXT,total_cost_usd TEXT,
                status_code INTEGER,created_at INTEGER,data_source TEXT);").unwrap();
        let b = broker(dir.path().into());
        for method in [
            "usage/read",
            "usage/dashboard/read",
            "usage/requests/read",
            "usage/sources",
        ] {
            let service = UsageService::test_service(Some(path.clone()));
            conn.execute_batch("DELETE FROM proxy_request_logs; PRAGMA user_version=27")
                .unwrap();
            let params = if method == "usage/sources" {
                json!({})
            } else {
                json!({"query":{"start_at":0,"end_at":30,"bucket_edges":[0,30]}})
            };
            let first = b
                .usage_with_service("phone", method, &params, &service)
                .await
                .unwrap();
            conn.execute_batch("INSERT INTO proxy_request_logs VALUES('a','p','codex','m',100,5,10,20,2,NULL,NULL,NULL,NULL,'0.1',200,1,'proxy'); PRAGMA user_version=28").unwrap();
            assert_eq!(
                b.usage_with_service("phone", method, &params, &service)
                    .await
                    .unwrap(),
                first
            );
            let mut params = params;
            params["refresh"] = json!(false);
            assert_eq!(
                b.usage_with_service("phone", method, &params, &service)
                    .await
                    .unwrap(),
                first
            );
            params["refresh"] = json!(true);
            let refreshed = b
                .usage_with_service("phone", method, &params, &service)
                .await
                .unwrap();
            if method == "usage/sources" {
                assert_eq!(refreshed["sources"][0]["schema_version"], 28);
            } else {
                assert_eq!(refreshed["result"]["source"]["schema_version"], 28);
                if method == "usage/requests/read" {
                    assert_eq!(
                        refreshed["result"]["data"]["requests"]["items"]
                            .as_array()
                            .unwrap()
                            .len(),
                        1
                    );
                } else {
                    assert_eq!(refreshed["result"]["data"]["totals"]["requests"], 1);
                    if method == "usage/read" {
                        assert!(refreshed["result"]["data"].get("recent_requests").is_none());
                    }
                }
            }
            for bad in [json!(null), json!(1), json!("true"), json!({})] {
                params["refresh"] = bad;
                assert_eq!(
                    error::code(
                        &b.usage_with_service("phone", method, &params, &service)
                            .await
                            .unwrap_err()
                    ),
                    Code::InvalidParams
                );
            }
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
        for method in ["usage/read", "usage/dashboard/read", "usage/requests/read"] {
            let dir = tempfile::tempdir().unwrap();
            let b = broker(dir.path().into());
            let service = UsageService::test_service(Some(dir.path().join("missing.db")));
            let _permits = service.hold_slots_for_test().await;
            let params = json!({});
            let read = b.usage_with_service("phone", method, &params, &service);
            let revoke = async {
                tokio::time::sleep(std::time::Duration::from_millis(30)).await;
                b.state.lock().await.devices.remove("phone");
            };
            let (result, _) = tokio::join!(read, revoke);
            assert_eq!(error::code(&result.unwrap_err()), Code::Revoked);
        }
    }
    #[tokio::test]
    async fn new_usage_methods_share_read_authorization_and_fixed_source_validation() {
        let dir = tempfile::tempdir().unwrap();
        let b = broker(dir.path().into());
        let service = UsageService::test_service(Some(dir.path().join("missing.db")));
        for method in ["usage/dashboard/read", "usage/requests/read"] {
            assert_eq!(
                error::code(
                    &b.usage_with_service("unknown", method, &json!({}), &service)
                        .await
                        .unwrap_err()
                ),
                Code::Revoked
            );
            assert_eq!(
                error::code(
                    &b.usage_with_service("phone", method, &json!({"path":"private"}), &service)
                        .await
                        .unwrap_err()
                ),
                Code::InvalidParams
            );
            let r = b
                .usage_with_service("phone", method, &json!({}), &service)
                .await
                .unwrap();
            assert_eq!(r["result"]["availability"], "missing");
            assert!(r["result"]["data"].is_null());
        }
        assert_eq!(
            error::code(
                &b.usage_with_service("phone", "usage/write", &json!({}), &service)
                    .await
                    .unwrap_err()
            ),
            Code::Unsupported
        );
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
