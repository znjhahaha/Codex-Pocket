//! Declare as a child of broker.rs. This module has no independent ingress.
use super::Broker;
use crate::{
    error::{Code, classify},
    upstream_versions::{self, BridgeVersion},
    usage_stats::service::{UsageService, current_user_service, prepare_read, validate_sources},
};
use anyhow::Result;
use serde_json::{Value, json};
impl Broker {
    pub(super) async fn usage_request(
        &self,
        device: &str,
        method: &str,
        p: &Value,
    ) -> Result<Value> {
        self.usage_with_service(device, method, p, current_user_service())
            .await
    }
    pub(super) async fn usage_with_service(
        &self,
        device: &str,
        method: &str,
        p: &Value,
        service: &UsageService,
    ) -> Result<Value> {
        // This is the existing paired broker read authorization. Outer transport
        // authenticates the device; never expose this method as a separate ingress.
        if !self.device_exists(device).await {
            return Err(classify(
                anyhow::anyhow!("Device access revoked"),
                Code::Revoked,
            ));
        }
        let invalid = || {
            classify(
                anyhow::anyhow!("Invalid usage request parameters"),
                Code::InvalidParams,
            )
        };
        let result = match method {
            "usage/sources" => {
                if !validate_sources(p) {
                    return Err(invalid());
                }
                service.sources().await
            }
            "usage/read" => {
                let query = prepare_read(p, crate::storage::now() as i64).map_err(|_| invalid())?;
                service.read(query).await
            }
            _ => {
                return Err(classify(
                    anyhow::anyhow!("Unsupported usage operation"),
                    Code::Unsupported,
                ));
            }
        };
        // Also suppress a fresh/cached/stale response if pairing was revoked during work.
        if !self.device_exists(device).await {
            return Err(classify(
                anyhow::anyhow!("Device access revoked"),
                Code::Revoked,
            ));
        }
        Ok(result)
    }
    /// Called only inside existing authenticated diagnostics. No path or stamp params.
    pub(super) async fn upstream_version_snapshot(&self) -> Value {
        use std::{
            sync::{Arc, OnceLock},
            time::Duration,
        };
        use tokio::sync::Semaphore;
        static SLOTS: OnceLock<Arc<Semaphore>> = OnceLock::new();
        let bridges: Vec<_> = {
            let state = self.state.lock().await;
            state
                .runtimes
                .iter()
                .take(256)
                .map(|(id, r)| BridgeVersion {
                    runtime_id: id.clone(),
                    version: r.bridge_version.clone(),
                })
                .collect()
        };
        let state = self.state_dir.clone();
        let permit = tokio::time::timeout(
            Duration::from_millis(250),
            SLOTS
                .get_or_init(|| Arc::new(Semaphore::new(2)))
                .clone()
                .acquire_owned(),
        )
        .await;
        if let Ok(Ok(permit)) = permit {
            if let Ok(value) = tokio::task::spawn_blocking(move || {
                let _permit = permit;
                upstream_versions::snapshot(&state, &bridges)
            })
            .await
            {
                return value;
            }
        }
        // Unknown is explicit; no inference from an install name or an old runtime.
        json!({"schema":1,"helper":{"state":"unknown","version":null,"generation":null,"evidence":"unavailable"},"installed":{"state":"unknown","version":null,"generation":null,"evidence":"unavailable"},"supervisor":{"state":"unknown","version":null,"generation":null,"evidence":"unavailable"},"bridges":[],"host":{"state":"unknown","version":null,"generation":null,"evidence":"unavailable"},"errors":[{"code":"version_projection_unavailable","message":"Version evidence is temporarily unavailable."}]})
    }
}
