use crate::storage;
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
// Nested registration keeps this change independent of the parent's lib.rs edits.
#[path = "routes.rs"]
pub mod routes;
use routes::{Route, RoutePreferences};
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub protocol: u32,
    pub computer_id: String,
    pub computer_name: String,
    #[serde(default)]
    pub relay_url: String,
    pub room: String,
    pub helper_token: String,
    pub phone_token: String,
    pub runtime_directories: Vec<PathBuf>,
    pub codex_homes: Vec<PathBuf>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub route_preferences: Option<RoutePreferences>,
}
impl Config {
    pub fn create(relay_url: String) -> Result<Self> {
        if !relay_url.is_empty() {
            routes::validate_public_url(&relay_url)?;
        }
        let user = std::env::var_os("USERPROFILE")
            .or_else(|| std::env::var_os("HOME"))
            .map(PathBuf::from)
            .ok_or_else(|| anyhow::anyhow!("Home directory missing"))?;
        let local = std::env::var_os("LOCALAPPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(|| user.join(".local/share"));
        let route_preferences = if relay_url.is_empty() {
            let mut p = RoutePreferences::default();
            // UDP connect selects the current interface without sending any packet.
            // Disconnected/non-private networks keep identity initialization available.
            p.lan = routes::default_lan_bind().map(|bind| routes::LanConfig { bind, mdns: true });
            Some(p)
        } else {
            None
        };
        Ok(Self {
            protocol: 1,
            computer_id: uuid::Uuid::new_v4().to_string(),
            computer_name: std::env::var("COMPUTERNAME").unwrap_or_else(|_| "My computer".into()),
            relay_url: relay_url.trim_end_matches('/').into(),
            room: uuid::Uuid::new_v4().simple().to_string(),
            helper_token: storage::secret(),
            phone_token: storage::secret(),
            runtime_directories: vec![local.join("CodexPocket/runtimes")],
            codex_homes: vec![
                std::env::var_os("CODEX_HOME")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| user.join(".codex")),
            ],
            route_preferences,
        })
    }
    pub fn create_local() -> Result<Self> {
        Self::create(String::new())
    }
    pub fn preferences(&self) -> RoutePreferences {
        self.route_preferences.clone().unwrap_or_else(|| {
            let mut preferences = RoutePreferences::default();
            if !self.relay_url.is_empty() {
                if let Ok(url) = routes::phone_url(&self.relay_url, &self.room) {
                    preferences.routes.push(Route {
                        id: "legacy-relay".into(),
                        kind: routes::RouteKind::Relay,
                        url,
                        priority: 100,
                        expires_at: None,
                    });
                }
            }
            preferences
        })
    }
    pub fn routes(&self) -> Vec<Route> {
        self.preferences()
            .advertised_routes(crate::connection::now())
    }
    /// Credential-free route descriptors. Runtime evidence lives in Connection.
    pub fn route_snapshot(&self) -> serde_json::Value {
        serde_json::json!({"computerId":self.computer_id,"policy":self.preferences().policy,"routes":self.routes()})
    }
    pub fn load(state: &Path) -> Result<Self> {
        let mut value: Self = serde_json::from_slice(&std::fs::read(state.join("config.json"))?)?;
        if value.protocol != 1 {
            bail!("Unsupported configuration version")
        };
        if !value.relay_url.is_empty() {
            routes::validate_public_url(&value.relay_url)?;
        }
        if value.route_preferences.is_none() {
            value.route_preferences = Some(value.preferences());
        }
        value.preferences().validate(&value.room)?;
        Ok(value)
    }
    pub fn save(&self, state: &Path) -> Result<()> {
        self.preferences().validate(&self.room)?;
        storage::atomic_write(
            &state.join("config.json"),
            &serde_json::to_vec_pretty(self)?,
        )
    }
}
