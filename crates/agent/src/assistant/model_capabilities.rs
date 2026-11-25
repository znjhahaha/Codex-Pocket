//! Reasoning choices come from the selected native model, never an invented enum.
use anyhow::{Context, Result, ensure};
use serde_json::{Value, json};
use std::collections::BTreeSet;

pub(crate) fn valid_effort(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value.trim() == value
        && !value.chars().any(char::is_control)
}

pub(crate) fn supported_efforts(model: &Value) -> Vec<String> {
    let values = [
        "supportedReasoningEfforts",
        "supported_reasoning_efforts",
        "supported_reasoning_levels",
        "reasoning_efforts",
    ]
    .into_iter()
    .find_map(|key| model.get(key).and_then(Value::as_array));
    let mut seen = BTreeSet::new();
    values
        .into_iter()
        .flatten()
        .filter_map(|value| {
            let effort = value.as_str().or_else(|| {
                ["reasoningEffort", "reasoning_effort", "effort"]
                    .into_iter()
                    .find_map(|key| value[key].as_str())
            })?;
            (valid_effort(effort) && seen.insert(effort)).then(|| effort.to_owned())
        })
        .collect()
}

pub(crate) fn default_effort(model: &Value) -> Option<&str> {
    [
        "defaultReasoningEffort",
        "default_reasoning_effort",
        "default_reasoning_level",
    ]
    .into_iter()
    .find_map(|key| model[key].as_str())
    .filter(|s| valid_effort(s))
}

fn model_name(model: &Value) -> Option<&str> {
    model["model"]
        .as_str()
        .or_else(|| model["slug"].as_str())
        .filter(|s| !s.trim().is_empty() && s.len() <= 256 && !s.chars().any(char::is_control))
}

fn matches(model: &Value, requested: Option<&str>) -> bool {
    model["hidden"] != true
        && model["visibility"] != "hide"
        && model_name(model).is_some()
        && requested.map_or(model["isDefault"] == true, |requested| {
            model_name(model) == Some(requested) || model["id"].as_str() == Some(requested)
        })
}

pub(crate) fn verify_catalog_effort(
    models: &[Value],
    requested: Option<&str>,
    effort: &str,
) -> Result<String> {
    ensure!(valid_effort(effort), "Invalid reasoning effort");
    let model = models
        .iter()
        .find(|model| matches(model, requested))
        .context("Selected model capabilities unavailable")?;
    ensure!(
        supported_efforts(model).iter().any(|s| s == effort),
        "Reasoning effort is not published for the selected model"
    );
    Ok(model_name(model).unwrap().to_owned())
}

/// Bind an inherited choice to the effective profile model before validating it.
/// The returned model must also be used on the native turn that carries the effort.
pub(crate) fn verify_native_effort(
    requested: Option<&str>,
    effort: &str,
    mut read: impl FnMut(&str, Value) -> Result<Value>,
) -> Result<String> {
    let configured;
    let requested = if let Some(requested) = requested {
        Some(requested)
    } else {
        configured = read("config/read", json!({"includeLayers":false}))?;
        let config = configured["config"]
            .as_object()
            .context("Effective native config unavailable")?;
        match config.get("model") {
            None | Some(Value::Null) => None,
            Some(Value::String(model)) if !model.trim().is_empty() => Some(model.as_str()),
            _ => anyhow::bail!("Invalid effective native model"),
        }
    };
    let mut cursor = Value::Null;
    let mut seen = BTreeSet::new();
    for _ in 0..10 {
        let page = read(
            "model/list",
            json!({"cursor":cursor,"limit":100,"includeHidden":false}),
        )?;
        let models = page["data"]
            .as_array()
            .context("Invalid native model directory")?;
        ensure!(models.len() <= 100, "Oversize native model page");
        if models.iter().any(|model| matches(model, requested)) {
            return verify_catalog_effort(models, requested, effort);
        }
        cursor = page["nextCursor"].clone();
        if cursor.is_null() {
            break;
        }
        ensure!(
            cursor.as_str().is_some() && seen.insert(cursor.clone().to_string()),
            "Invalid native model pagination"
        );
    }
    anyhow::bail!("Selected model capabilities unavailable")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn effort_native_pages_use_effective_profile_and_advertised_strings() {
        let mut pages = 0;
        let selected = verify_native_effort(None, "provider-max", |method, params| {
            if method == "config/read" { return Ok(json!({"config":{"model":"custom"}})); }
            pages += 1;
            Ok(if params["cursor"].is_null() { json!({"data":[{"model":"other","isDefault":true}],"nextCursor":"two"}) }
                else { json!({"data":[{"model":"custom","supported_reasoning_levels":[{"effort":"provider-max"}]}],"nextCursor":null}) })
        }).unwrap();
        assert_eq!(selected, "custom");
        assert_eq!(pages, 2);
        assert!(verify_native_effort(None, "high", |_, _| Ok(json!({}))).is_err());
    }

    #[test]
    fn effort_metadata_is_bounded_deduplicated_and_never_inferred() {
        let model = json!({"model":"m","reasoning_efforts":["high",{"reasoning_effort":"high"},{"effort":"max"}," ","bad\n",42],"default_reasoning_effort":"high"});
        assert_eq!(supported_efforts(&model), vec!["high", "max"]);
        assert_eq!(default_effort(&model), Some("high"));
        assert!(verify_catalog_effort(&[model], Some("m"), "low").is_err());
        assert!(
            verify_catalog_effort(
                &[json!({"model":"m","defaultReasoningEffort":"high"})],
                Some("m"),
                "high"
            )
            .is_err()
        );
        assert!(
            verify_catalog_effort(
                &[json!({"model":"m","hidden":true,"supportedReasoningEfforts":["high"]})],
                Some("m"),
                "high"
            )
            .is_err()
        );
    }
}
