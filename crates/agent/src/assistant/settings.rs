use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct NotificationSettings {
    pub completed: bool,
    pub failed: bool,
    pub waiting_user: bool,
}

#[cfg(test)]
mod patch_tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn field_patch_preserves_models_and_explicit_null_resets_each_role() {
        let original = Settings { model: Some("worker".into()), coordinator_model: Some("chief".into()), ..Settings::default() };
        let updated = original.updated(&json!({"patch":{"tone":"Brief"}})).unwrap();
        assert_eq!(updated.model, original.model);
        assert_eq!(updated.coordinator_model, original.coordinator_model);
        let updated = updated.updated(&json!({"patch":{"coordinator_model":null}})).unwrap();
        assert_eq!(updated.coordinator_model, None);
        assert_eq!(updated.model.as_deref(), Some("worker"));
        assert_eq!(updated.updated(&json!({"patch":{"model":null}})).unwrap().model, None);
    }

    #[test]
    fn legacy_full_settings_and_patch_validation() {
        let s = Settings::default();
        assert_eq!(s.updated(&json!({"settings":s})).unwrap(), s);
        for input in [json!({"patch":{"unknown":true}}), json!({"patch":{"tone":null}}), json!({"patch":{},"settings":s})] {
            assert!(s.updated(&input).is_err());
        }
        assert_eq!(s.updated(&json!({"patch":{"notifications":{"completed":false}}})).unwrap().notifications,
            NotificationSettings { completed:false, ..NotificationSettings::default() });
    }

    #[test]
    fn effort_patch_preserves_roles_and_null_restores_inheritance() {
        let settings=Settings::default().updated(&json!({"patch":{"effort":"high","coordinator_effort":"low"}})).unwrap();
        let worker=serde_json::to_value(settings.for_role(super::super::Role::Execution,None)).unwrap();
        let chief=serde_json::to_value(settings.for_role(super::super::Role::Coordinator,None)).unwrap();
        assert_eq!(worker["effort"],"high");
        assert_eq!(chief["effort"],"low");
        let inherit=settings.updated(&json!({"patch":{"coordinator_model":"chief","coordinator_effort":null}})).unwrap();
        let chief=serde_json::to_value(inherit.for_role(super::super::Role::Coordinator,None)).unwrap();
        assert_eq!(chief["model"],"chief");assert_eq!(chief["effort"],"high");
        assert_eq!(serde_json::to_value(inherit.updated(&json!({"patch":{"tone":"brief"}})).unwrap()).unwrap()["effort"],"high");
        for patch in [json!({"effort":" "}),json!({"effort":42}),json!({"coordinator_effort":"high\n"})] {
            assert!(settings.updated(&json!({"patch":patch})).is_err());
        }
    }
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
    /// None leaves reasoning effort to the native model default.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effort: Option<String>,
    /// Independent from model inheritance: None follows the work effort.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub coordinator_effort: Option<String>,
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
            effort: None,
            coordinator_effort: None,
            timezone: "Asia/Shanghai".into(),
            notifications: NotificationSettings::default(),
        }
    }
}
impl Settings {
    /// Legacy clients replace the whole object. New clients name only fields they edited.
    /// An absent model is preserved; JSON null explicitly restores inheritance.
    pub fn updated(&self, parameters: &serde_json::Value) -> Result<Self> {
        use serde_json::Value;
        let settings = match (parameters.get("settings"), parameters.get("patch")) {
            (Some(full), None) => serde_json::from_value(full.clone())?,
            (None, Some(Value::Object(patch))) => {
                let mut current = serde_json::to_value(self)?;
                for (key, value) in patch {
                    ensure!(["personality","tone","model","coordinator_model","effort","coordinator_effort","timezone","notifications"].contains(&key.as_str()), "Unknown assistant setting");
                    if key == "notifications" {
                        let fields = value.as_object().ok_or_else(|| anyhow::anyhow!("Invalid notifications patch"))?;
                        for (name, flag) in fields {
                            ensure!(["completed","failed","waiting_user"].contains(&name.as_str()) && flag.is_boolean(), "Invalid notification setting");
                            current[key][name] = flag.clone();
                        }
                    } else { current[key] = value.clone(); }
                }
                serde_json::from_value(current)?
            }
            _ => anyhow::bail!("Supply either settings or patch"),
        };
        let settings: Self = settings;
        settings.validate()?;
        Ok(settings)
    }
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
        for effort in [&self.effort, &self.coordinator_effort].into_iter().flatten() {
            ensure!(super::model_capabilities::valid_effort(effort), "Invalid reasoning effort");
        }
        Ok(())
    }
    /// Only changed effective selections require a fresh capability check.
    pub(crate) fn changed_effort_selections(&self, previous: &Self) -> std::collections::BTreeSet<(Option<String>, String)> {
        [super::Role::Execution, super::Role::Coordinator].into_iter().filter_map(|role| {
            let new = self.for_role(role, None);
            let old = previous.for_role(role, None);
            if new.model == old.model && new.effort == old.effort { return None; }
            new.effort.map(|effort| (new.model, effort))
        }).collect()
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
            effort: if role == super::Role::Coordinator { self.coordinator_effort.clone().or_else(|| self.effort.clone()) } else { self.effort.clone() },
            coordinator_effort: None,
            ..self.clone()
        }
    }
}
