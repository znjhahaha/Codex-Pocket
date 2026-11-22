//! Declare as a child of broker.rs. Source-selecting usage reads; no independent ingress.
use super::Broker;
use crate::{
    error::{Code, classify},
    native_usage::service::{Source, prepare_read, validate_sources},
    usage_stats::service::UsageService,
};
use anyhow::Result;
use serde_json::Value;
impl Broker {
    pub(super) async fn usage_v2_request(
        &self,
        device: &str,
        method: &str,
        p: &Value,
    ) -> Result<Value> {
        // Like quota, unit tests never reach the current user's CC Switch database.
        #[cfg(test)]
        let fixture = UsageService::test_service(Some(self.state_dir.join("test-cc-switch.db")));
        #[cfg(test)]
        let cc_switch = &fixture;
        #[cfg(not(test))]
        let cc_switch = crate::usage_stats::service::current_user_service();
        self.usage_v2_with_service(device, method, p, cc_switch)
            .await
    }
    pub(super) async fn usage_v2_with_service(
        &self,
        device: &str,
        method: &str,
        p: &Value,
        cc_switch: &UsageService,
    ) -> Result<Value> {
        // Same paired read authorization as v1, before and after the work.
        let revoked = || {
            classify(
                anyhow::anyhow!("Device access revoked"),
                Code::Revoked,
            )
        };
        if !self.device_exists(device).await {
            return Err(revoked());
        }
        let invalid = || {
            classify(
                anyhow::anyhow!("Invalid usage request parameters"),
                Code::InvalidParams,
            )
        };
        let result = match method {
            "usage/v2/sources" => {
                if !validate_sources(p) {
                    return Err(invalid());
                }
                self.native_usage.homes(self.history_homes().await);
                self.native_usage
                    .sources(cc_switch, p["refresh"].as_bool().unwrap_or(false))
                    .await
            }
            "usage/v2/read" | "usage/v2/requests/read" => {
                let (source, query, refresh) =
                    prepare_read(p, crate::storage::now() as i64).map_err(|_| invalid())?;
                // Import roots are the agent's own profiles; a request never names a path.
                if source == Source::Native {
                    self.native_usage.homes(self.history_homes().await);
                }
                if method == "usage/v2/read" {
                    self.native_usage
                        .read(cc_switch, source, query, refresh)
                        .await
                } else {
                    self.native_usage
                        .requests(cc_switch, source, query, refresh)
                        .await
                }
            }
            _ => {
                return Err(classify(
                    anyhow::anyhow!("Unsupported usage operation"),
                    Code::Unsupported,
                ));
            }
        };
        // Also suppress a completed response if pairing was revoked during work.
        if !self.device_exists(device).await {
            return Err(revoked());
        }
        Ok(result)
    }
}

#[cfg(test)]
#[path = "broker/usage_v2_tests.rs"]
mod tests;
