use crate::storage;
use anyhow::{Context, Result, bail};
use base64::{Engine, engine::general_purpose::STANDARD};
use serde_json::Value;
use std::{collections::HashMap, path::PathBuf};
#[derive(Clone, serde::Serialize, serde::Deserialize)]
pub struct Attachment {
    pub owner: String,
    pub path: PathBuf,
    pub expires: u64,
}
pub fn upload(
    directory: &std::path::Path,
    owner: &str,
    p: &Value,
    entries: &mut HashMap<String, Attachment>,
) -> Result<String> {
    let encoded = p["base64"].as_str().context("Missing image data")?;
    if encoded.len() > 11_184_812 {
        bail!("Image exceeds 8 MiB")
    }
    let bytes = STANDARD.decode(encoded)?;
    if bytes.len() > 8 * 1024 * 1024 || bytes.is_empty() {
        bail!("Image exceeds limit")
    }
    let ext = match p["mime"].as_str() {
        Some("image/png") if bytes.starts_with(b"\x89PNG\r\n\x1a\n") => "png",
        Some("image/jpeg") if bytes.starts_with(&[0xff, 0xd8, 0xff]) => "jpg",
        Some("image/webp")
            if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" =>
        {
            "webp"
        }
        _ => bail!("Image type or file signature is invalid"),
    };
    let now = storage::now();
    entries.retain(|_, a| {
        if a.expires <= now {
            let _ = std::fs::remove_file(&a.path);
            false
        } else {
            true
        }
    });
    if entries.len() >= 64 {
        bail!("Attachment storage full; wait for older uploads to expire")
    }
    let id = uuid::Uuid::new_v4().to_string();
    let path = directory.join(format!("{id}.{ext}"));
    storage::atomic_write(&path, &bytes)?;
    entries.insert(
        id.clone(),
        Attachment {
            owner: owner.into(),
            path,
            expires: now + 3600,
        },
    );
    Ok(id)
}
pub fn translate(
    params: &mut Value,
    owner: &str,
    entries: &HashMap<String, Attachment>,
) -> Result<()> {
    if let Some(input) = params.get_mut("input").and_then(Value::as_array_mut) {
        for item in input {
            if item["type"] == "pocketImage" {
                let a = entries
                    .get(
                        item["attachmentId"]
                            .as_str()
                            .context("Missing attachment")?,
                    )
                    .context("Attachment missing or expired")?;
                if a.owner != owner || a.expires <= storage::now() {
                    bail!("Attachment is not available to this device")
                }
                *item = serde_json::json!({"type":"localImage","path":a.path});
            }
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn rejects_mime_spoof_and_cross_device() {
        let dir = tempfile::tempdir().unwrap();
        let mut entries = HashMap::new();
        assert!(
            upload(
                dir.path(),
                "a",
                &json!({"mime":"image/png","base64":STANDARD.encode(b"not png")}),
                &mut entries
            )
            .is_err()
        );
        let id = upload(
            dir.path(),
            "a",
            &json!({"mime":"image/png","base64":STANDARD.encode(b"\x89PNG\r\n\x1a\nheader")}),
            &mut entries,
        )
        .unwrap();
        let mut p = json!({"input":[{"type":"pocketImage","attachmentId":id}]});
        assert!(translate(&mut p, "b", &entries).is_err());
        translate(&mut p, "a", &entries).unwrap();
        assert_eq!(p["input"][0]["type"], "localImage");
    }
}
