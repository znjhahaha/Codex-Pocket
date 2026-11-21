//! Durable user-provided input over the existing attachment transport.
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::path::PathBuf;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", deny_unknown_fields)]
pub enum AssistantInput {
    Image {
        id: String,
        name: String,
        path: PathBuf,
    },
    Text {
        id: String,
        name: String,
        text: String,
    },
}
impl AssistantInput {
    pub fn id(&self) -> &str {
        match self {
            Self::Image { id, .. } | Self::Text { id, .. } => id,
        }
    }
    pub fn name(&self) -> &str {
        match self {
            Self::Image { name, .. } | Self::Text { name, .. } => name,
        }
    }
    pub fn validate(&self) -> Result<()> {
        ensure!(
            uuid::Uuid::parse_str(self.id()).is_ok(),
            "Invalid attachment identity"
        );
        ensure!(
            !self.name().is_empty()
                && self.name().len() <= 512
                && !self.name().chars().any(char::is_control),
            "Invalid attachment name"
        );
        match self {
            Self::Image { path, .. } => {
                ensure!(path.is_absolute(), "Attachment path is not absolute");
                let bytes = crate::storage::read_physical(path, 8 * 1024 * 1024)?;
                ensure!(
                    bytes.starts_with(b"\x89PNG\r\n\x1a\n")
                        || bytes.starts_with(&[255, 216, 255])
                        || (bytes.len() >= 12
                            && &bytes[..4] == b"RIFF"
                            && &bytes[8..12] == b"WEBP"),
                    "Invalid attachment image"
                );
            }
            Self::Text { text, .. } => ensure!(
                !text.is_empty()
                    && text.len() <= 256 * 1024
                    && !text
                        .chars()
                        .any(|c| c == '\0' || (c.is_control() && !['\n', '\r', '\t'].contains(&c))),
                "Invalid text attachment"
            ),
        }
        Ok(())
    }
    pub fn native(&self) -> Value {
        match self {
            Self::Image { path, .. } => json!({"type":"localImage","path":path}),
            Self::Text { name, text, .. } => {
                json!({"type":"text","text":format!("Attachment: {name}\n\n{text}"),"text_elements":[]})
            }
        }
    }
}
pub fn validate_inputs(inputs: &[AssistantInput]) -> Result<()> {
    ensure!(inputs.len() <= 32, "Too many retained attachments");
    let mut ids = std::collections::BTreeSet::new();
    for input in inputs {
        ensure!(ids.insert(input.id()), "Repeated attachment identity");
        input.validate()?;
    }
    Ok(())
}
pub fn native_input(message: &str, inputs: &[AssistantInput]) -> Value {
    let mut values = vec![json!({"type":"text","text":message_label(message,inputs),"text_elements":[]})];
    values.extend(inputs.iter().map(AssistantInput::native));
    Value::Array(values)
}
pub fn message_label(message: &str, inputs: &[AssistantInput]) -> String {
    if inputs.is_empty() {
        message.into()
    } else {
        format!(
            "{message}\n\n附件：{}",
            inputs
                .iter()
                .map(AssistantInput::name)
                .collect::<Vec<_>>()
                .join("、")
        )
    }
}

#[derive(Deserialize)]
#[serde(tag = "type", deny_unknown_fields)]
enum UploadedInput {
    #[serde(rename = "pocketImage")]
    Image {
        #[serde(rename = "attachmentId")]
        attachment_id: String,
        name: String,
    },
    #[serde(rename = "textFile")]
    Text {
        id: String,
        name: String,
        text: String,
    },
}
pub fn validate_uploaded(value: &Value) -> Result<()> {
    let inputs: Vec<UploadedInput> = serde_json::from_value(value.clone())?;
    ensure!(inputs.len() <= 4, "At most four attachments per message");
    for input in inputs {
        match input {
            UploadedInput::Image {
                attachment_id,
                name,
            } => {
                ensure!(
                    uuid::Uuid::parse_str(&attachment_id).is_ok()
                        && !name.is_empty()
                        && name.len() <= 512
                        && !name.chars().any(char::is_control),
                    "Invalid image reference"
                );
            }
            UploadedInput::Text { id, name, text } => {
                AssistantInput::Text { id, name, text }.validate()?
            }
        }
    }
    Ok(())
}
/// Copy authorized temporary uploads into the assistant's durable input store
/// before acknowledging a message. The ordinary upload expiry cannot remove
/// an image while an entrusted task is queued or awaiting an answer.
pub fn retain_uploaded(
    value: &Value,
    owner: &str,
    entries: &std::collections::HashMap<String, crate::attachments::Attachment>,
    root: &std::path::Path,
) -> Result<Vec<AssistantInput>> {
    validate_uploaded(value)?;
    let uploaded: Vec<UploadedInput> = serde_json::from_value(value.clone())?;
    let mut result = Vec::new();
    for input in uploaded {
        result.push(match input {
            UploadedInput::Text { id, name, text } => AssistantInput::Text { id, name, text },
            UploadedInput::Image {
                attachment_id,
                name,
            } => {
                let mut params =
                    json!({"input":[{"type":"pocketImage","attachmentId":attachment_id}]});
                crate::attachments::translate(&mut params, owner, entries)?;
                let source = std::path::Path::new(
                    params["input"][0]["path"]
                        .as_str()
                        .ok_or_else(|| anyhow::anyhow!("Image path unavailable"))?,
                );
                let bytes = crate::storage::read_physical(source, 8 * 1024 * 1024)?;
                crate::storage::private_directory(root)?;
                let extension=source.extension().and_then(|s|s.to_str()).filter(|s|matches!(*s,"png"|"jpg"|"webp"))
                    .ok_or_else(||anyhow::anyhow!("Invalid uploaded image suffix"))?;
                let path = root.join(format!("{attachment_id}.{extension}"));
                if path.try_exists()? {
                    ensure!(
                        crate::storage::read_physical(&path, 8 * 1024 * 1024)? == bytes,
                        "Retained image identity changed"
                    );
                } else {
                    crate::storage::atomic_write(&path, &bytes)?;
                }
                AssistantInput::Image {
                    id: attachment_id,
                    name,
                    path,
                }
            }
        });
    }
    validate_inputs(&result)?;
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::{Engine, engine::general_purpose::STANDARD};
    #[test]
    fn assistant_attachment_retains_authorized_upload_past_temporary_expiry_and_rejects_raw_paths()
    {
        let dir = tempfile::tempdir().unwrap();
        let mut entries = std::collections::HashMap::new();
        let bytes = b"\x89PNG\r\n\x1a\nfixture";
        let id = crate::attachments::upload(
            dir.path(),
            "phone",
            &json!({"mime":"image/png","base64":STANDARD.encode(bytes)}),
            &mut entries,
        )
        .unwrap();
        let value = json!([{"type":"pocketImage","attachmentId":id,"name":"diagram.png"}]);
        let root = dir.path().join("retained");
        assert!(retain_uploaded(&value, "other-device", &entries, &root).is_err());
        let retained = retain_uploaded(&value, "phone", &entries, &root).unwrap();
        assert_eq!(
            retained,
            retain_uploaded(&value, "phone", &entries, &root).unwrap()
        );
        std::fs::remove_file(&entries[&id].path).unwrap();
        entries.clear();
        validate_inputs(&retained).unwrap();
        assert_eq!(native_input("look", &retained)[1]["type"], "localImage");
        assert!(validate_uploaded(&json!([{"type":"localImage","path":"C:/private"}])).is_err());
        assert!(
            validate_uploaded(
                &json!([{"type":"pocketImage","attachmentId":id,"name":"x","path":"C:/private"}])
            )
            .is_err()
        );
    }
}
