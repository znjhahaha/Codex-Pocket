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
    /// The coordinator's own model; None means it follows `model`. Jobs never use it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub coordinator_model: Option<String>,
    pub timezone: String,
    pub notifications: NotificationSettings,
}
impl Default for Settings {
    fn default() -> Self {
        Self {
            personality: "Helpful personal assistant".into(),
            tone: "Clear and concise".into(),
            model: None,
            coordinator_model: None,
            timezone: "Asia/Shanghai".into(),
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
        for model in [&self.model, &self.coordinator_model].into_iter().flatten() {
            ensure!(
                !model.trim().is_empty() && model.len() <= 256 && !model.contains('\0'),
                "Invalid model"
            );
        }
        Ok(())
    }
    /// What a native request for this role runs on. A job may name its own model.
    pub fn for_role(&self, role: super::Role, job_model: Option<&str>) -> Self {
        let model = match role {
            super::Role::Coordinator => self.coordinator_model.clone(),
            super::Role::Execution => job_model.map(str::to_owned),
        };
        // The request carries only the model it runs on, so changing the coordinator's
        // model never alters what a job's request looks like.
        Self {
            model: model.or_else(|| self.model.clone()),
            coordinator_model: None,
            ..self.clone()
        }
    }
}
