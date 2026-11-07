use crate::storage;
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub protocol: u32,
    pub computer_id: String,
    pub computer_name: String,
    pub relay_url: String,
    pub room: String,
    pub helper_token: String,
    pub phone_token: String,
    pub runtime_directories: Vec<PathBuf>,
    pub codex_homes: Vec<PathBuf>,
}
impl Config {
    pub fn create(relay_url: String) -> Result<Self> {
        let parsed = url::Url::parse(&relay_url)?;
        if parsed.scheme() != "wss"
            || parsed.host_str().is_none()
            || !parsed.username().is_empty()
            || parsed.password().is_some()
            || parsed.query().is_some()
            || parsed.fragment().is_some()
        {
            bail!("Relay must be a wss URL without embedded credentials, query or fragment");
        }
        let user = std::env::var_os("USERPROFILE")
            .or_else(|| std::env::var_os("HOME"))
            .map(PathBuf::from)
            .ok_or_else(|| anyhow::anyhow!("Home directory missing"))?;
        let local = std::env::var_os("LOCALAPPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(|| user.join(".local/share"));
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
        })
    }
    pub fn load(state: &Path) -> Result<Self> {
        let value: Self = serde_json::from_slice(&std::fs::read(state.join("config.json"))?)?;
        if value.protocol != 1 {
            bail!("Unsupported configuration version")
        };
        Ok(value)
    }
    pub fn save(&self, state: &Path) -> Result<()> {
        storage::atomic_write(
            &state.join("config.json"),
            &serde_json::to_vec_pretty(self)?,
        )
    }
}
