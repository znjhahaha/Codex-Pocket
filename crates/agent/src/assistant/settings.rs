use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct NotificationSettings {
    pub completed: bool,
    pub failed: bool,
    pub waiting_user: bool,
}
impl Default for NotificationSettings {
    fn default() -> Self {
        Self {
            completed: true,
            failed: true,
            waiting_user: true,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Settings {
    pub personality: String,
    pub tone: String,
    /// None means use the Host's selected model; no invented model identifiers.
    pub model: Option<String>,
    pub timezone: String,
    pub notifications: NotificationSettings,
}
impl Default for Settings {
    fn default() -> Self {
        Self {
            personality: "Helpful personal assistant".into(),
            tone: "Clear and concise".into(),
            model: None,
            timezone: "UTC".into(),
            notifications: NotificationSettings::default(),
        }
    }
}
impl Settings {
    pub fn validate(&self) -> Result<()> {
        for (text, max) in [
            (&self.personality, 8192),
            (&self.tone, 2048),
            (&self.timezone, 256),
        ] {
            ensure!(
                !text.trim().is_empty() && text.len() <= max && !text.contains('\0'),
                "Invalid assistant setting"
            );
        }
        if let Some(model) = &self.model {
            ensure!(
                !model.trim().is_empty() && model.len() <= 256 && !model.contains('\0'),
                "Invalid model"
            );
        }
        Ok(())
    }
}
