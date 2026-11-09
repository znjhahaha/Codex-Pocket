//! Agent integration for the shared native media projection. Broker supplies proven ownership.
#[path = "history_media.rs"]
pub mod history_media;
#[path = "../../bridge/src/media.rs"]
mod implementation;
use anyhow::{Result, bail};
pub use history_media::NativeHistory;
pub use implementation::MediaOwner;
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    path::Path,
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};

const MAX_RECOVERY_REFS: usize = 256;
const RECOVERY_TTL: u64 = 24 * 60 * 60;
struct RecoveryReference {
    owner: MediaOwner,
    slot: usize,
    history: NativeHistory,
    created: u64,
    resolved: Mutex<Option<Value>>,
}
#[derive(Clone)]
pub struct MediaStore {
    store: Arc<implementation::MediaStore>,
    recoveries: Arc<Mutex<BTreeMap<String, Arc<RecoveryReference>>>>,
}
impl MediaStore {
    pub fn new(state_dir: impl AsRef<Path>) -> Result<Self> {
        Ok(Self {
            store: Arc::new(implementation::MediaStore::with_limits(
                state_dir,
                crate::workspace::MAX_IMAGE,
                crate::workspace::IMAGE_CHUNK,
            )?),
            recoveries: Arc::new(Mutex::new(BTreeMap::new())),
        })
    }
    pub fn project(
        &self,
        native: &Value,
        runtime_id: &str,
        connection_id: &str,
        fallback_thread_id: &str,
    ) -> Value {
        self.store
            .project(native, runtime_id, connection_id, fallback_thread_id)
    }
    pub fn project_with_history(
        &self,
        native: &Value,
        runtime_id: &str,
        connection_id: &str,
        fallback_thread_id: &str,
        history: &NativeHistory,
    ) -> Value {
        let mut projected = self.project(native, runtime_id, connection_id, fallback_thread_id);
        self.register_user_images(&mut projected, runtime_id, connection_id, history, 0);
        projected
    }
    fn register_user_images(
        &self,
        value: &mut Value,
        runtime: &str,
        connection: &str,
        history: &NativeHistory,
        depth: usize,
    ) {
        if depth > 64 {
            return;
        }
        if value["type"] == "userMessage" {
            let item = value["id"].as_str().unwrap_or("").to_owned();
            if let Some(parts) = value.get_mut("content").and_then(Value::as_array_mut) {
                for (slot, part) in parts.iter_mut().enumerate() {
                    if part["type"] != "image"
                        || part["status"] == "ready"
                        || part["contentPart"].as_u64() != Some(slot as u64)
                    {
                        continue;
                    }
                    let owner = MediaOwner {
                        runtime_id: runtime.into(),
                        connection_id: connection.into(),
                        thread_id: part["threadId"].as_str().unwrap_or("").into(),
                        turn_id: part["turnId"].as_str().unwrap_or("").into(),
                        item_id: item.clone(),
                    };
                    if history.can_recover(&owner, slot) {
                        if let Some(reference) =
                            self.recovery_reference(&owner, slot, history, part)
                        {
                            *part = reference;
                        }
                    }
                }
            }
            return;
        }
        match value {
            Value::Array(values) => {
                for value in values {
                    self.register_user_images(value, runtime, connection, history, depth + 1);
                }
            }
            Value::Object(fields) => {
                for value in fields.values_mut() {
                    self.register_user_images(value, runtime, connection, history, depth + 1);
                }
            }
            _ => {}
        }
    }
    fn recovery_reference(
        &self,
        owner: &MediaOwner,
        slot: usize,
        history: &NativeHistory,
        original: &Value,
    ) -> Option<Value> {
        let mut refs = self.recoveries.lock().ok()?;
        let timestamp = media_now();
        refs.retain(|_, r| timestamp.saturating_sub(r.created) < RECOVERY_TTL);
        let existing = refs
            .iter()
            .find(|(_, r)| r.owner == *owner && r.slot == slot && r.history.same_proof(history))
            .map(|(id, r)| (id.clone(), r.clone()));
        let (id, entry) = if let Some(existing) = existing {
            existing
        } else {
            while refs.len() >= MAX_RECOVERY_REFS {
                let oldest = refs
                    .iter()
                    .min_by_key(|(_, r)| r.created)
                    .map(|(id, _)| id.clone())?;
                refs.remove(&oldest);
            }
            let id = uuid::Uuid::new_v4().simple().to_string();
            let entry = Arc::new(RecoveryReference {
                owner: owner.clone(),
                slot,
                history: history.clone(),
                created: timestamp,
                resolved: Mutex::new(None),
            });
            refs.insert(id.clone(), entry.clone());
            (id, entry)
        };
        drop(refs);
        // Do not wait for a background read while constructing a page.
        let resolved = entry.resolved.try_lock().ok().and_then(|r| r.clone());
        let mut block = original.clone();
        block["status"] = json!("recoverable");
        block["message"] = json!("");
        if let Some(resolved) = resolved {
            for key in ["status", "message", "mimeType", "byteLength"] {
                if let Some(value) = resolved.get(key) {
                    block[key] = value.clone();
                }
            }
        }
        block["mediaId"] = json!(id);
        Some(block)
    }
    pub fn read(
        &self,
        owner: &MediaOwner,
        media_id: &str,
        offset: u64,
        max_bytes: usize,
    ) -> Result<Value> {
        if max_bytes == 0 || max_bytes > crate::workspace::IMAGE_CHUNK {
            bail!("Invalid media chunk");
        }
        let reference = self
            .recoveries
            .lock()
            .map_err(|_| anyhow::anyhow!("Media store unavailable"))?
            .get(media_id)
            .cloned();
        let Some(reference) = reference else {
            return self.store.read(owner, media_id, offset, max_bytes);
        };
        if reference.owner != *owner {
            bail!("Media does not belong to the selected item");
        }
        if media_now().saturating_sub(reference.created) >= RECOVERY_TTL {
            return Ok(json!({"mediaId":media_id,"status":"expired"}));
        }
        let mut resolution = reference
            .resolved
            .lock()
            .map_err(|_| anyhow::anyhow!("Media recovery unavailable"))?;
        if resolution.is_none() {
            *resolution = Some(match reference.history.recover(owner, reference.slot) {
                Ok(part) => self.store.project_user_image(owner, reference.slot, &part),
                Err(_) => {
                    json!({"status":"failed","message":"Native history image unavailable or invalid"})
                }
            });
        }
        let resolved = resolution.as_ref().unwrap();
        let mut result = if resolved["status"] == "ready" {
            self.store.read(
                owner,
                resolved["mediaId"].as_str().unwrap_or(""),
                offset,
                max_bytes,
            )?
        } else {
            resolved.clone()
        };
        result["mediaId"] = json!(media_id);
        Ok(result)
    }
}
fn media_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::{fs, io::Write};

    #[test]
    fn recovered_user_images_are_ordered_opaque_and_readable_only_by_their_owner() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("native.jsonl");
        let source = "data:image/png;base64,iVBORw0KGgp4eA==";
        let mut file = fs::File::create(&path).unwrap();
        for record in [
            json!({"type":"session_meta","payload":{"id":"thread"}}),
            json!({"type":"turn_context","payload":{"turn_id":"turn"}}),
            json!({"type":"response_item","payload":{"type":"message","role":"user","id":"user","content":[
                {"type":"input_text","text":"marker"},{"type":"input_image","image_url":source},
                {"type":"input_text","text":"marker"},{"type":"input_image","image_url":source}
            ]}}),
        ] {
            serde_json::to_writer(&mut file, &record).unwrap();
            file.write_all(b"\n").unwrap();
        }
        drop(file);
        let native = json!({"thread":{"id":"thread","path":path,"turns":[{"id":"turn","items":[
            {"id":"user","type":"userMessage","content":[
                {"type":"localImage","path":"C:/missing.png"},{"type":"text","text":"between"},
                {"type":"localImage","path":"C:/missing.png"}
            ]}
        ]}]}});
        let proof =
            NativeHistory::from_native_thread(&native["thread"], "runtime", "connection").unwrap();
        fn thread_safe<T: Clone + Send + Sync>() {}
        thread_safe::<NativeHistory>();
        thread_safe::<MediaStore>();
        let store = MediaStore::new(root.path()).unwrap();
        let moved = root.path().join("paused.jsonl");
        fs::rename(&path, &moved).unwrap();
        let projected =
            store.project_with_history(&native, "runtime", "connection", "thread", &proof);
        assert_eq!(
            proof.scan_builds.load(std::sync::atomic::Ordering::Relaxed),
            0
        );
        fs::rename(&moved, &path).unwrap();
        let content = &projected["thread"]["turns"][0]["items"][0]["content"];
        assert_eq!(content[0]["status"], "recoverable");
        assert_eq!(content[2]["status"], "recoverable");
        assert_eq!(content[1]["text"], "between");
        assert_ne!(content[0]["mediaId"], content[2]["mediaId"]);
        let owner = MediaOwner {
            runtime_id: "runtime".into(),
            connection_id: "connection".into(),
            thread_id: "thread".into(),
            turn_id: "turn".into(),
            item_id: "user".into(),
        };
        let first_id = content[0]["mediaId"].as_str().unwrap();
        for field in 0..5 {
            let mut wrong = owner.clone();
            match field {
                0 => wrong.runtime_id.push('x'),
                1 => wrong.connection_id.push('x'),
                2 => wrong.thread_id.push('x'),
                3 => wrong.turn_id.push('x'),
                _ => wrong.item_id.push('x'),
            }
            assert!(store.read(&wrong, first_id, 0, 8).is_err());
        }
        assert!(
            store
                .read(&owner, first_id, 0, crate::workspace::IMAGE_CHUNK + 1)
                .is_err()
        );
        assert_eq!(
            proof.scan_builds.load(std::sync::atomic::Ordering::Relaxed),
            0
        );
        for slot in [0, 2] {
            let id = content[slot]["mediaId"].as_str().unwrap();
            assert_eq!(
                store.read(&owner, id, 0, 8).unwrap()["data"],
                "iVBORw0KGgo="
            );
            assert_eq!(store.clone().read(&owner, id, 0, 8).unwrap()["mediaId"], id);
        }
        assert_eq!(
            proof.scan_builds.load(std::sync::atomic::Ordering::Relaxed),
            1
        );
        let replay =
            store.project_with_history(&native, "runtime", "connection", "thread", &proof.clone());
        assert_eq!(
            replay["thread"]["turns"][0]["items"][0]["content"][0]["mediaId"],
            first_id
        );
        assert_eq!(
            replay["thread"]["turns"][0]["items"][0]["content"][0]["status"],
            "ready"
        );
        assert_eq!(
            proof.scan_builds.load(std::sync::atomic::Ordering::Relaxed),
            1
        );
        assert!(!projected.to_string().contains("base64"));
        assert_eq!(
            native["thread"]["turns"][0]["items"][0]["content"][0]["type"],
            "localImage"
        );
        let wrong =
            store.project_with_history(&native, "runtime", "new-generation", "thread", &proof);
        assert_ne!(
            wrong["thread"]["turns"][0]["items"][0]["content"][0]["status"],
            "ready"
        );
        // A phone-supplied path cannot become a recovery target by matching a basename.
        let foreign = json!({"thread":{"id":"thread","turns":[{"id":"turn","items":[
            {"id":"foreign","type":"userMessage","content":[{"type":"localImage","path":path}]}
        ]}]}});
        assert_ne!(
            store.project_with_history(&foreign, "runtime", "connection", "thread", &proof)["thread"]
                ["turns"][0]["items"][0]["content"][0]["status"],
            "ready"
        );
    }
    #[test]
    fn lazy_references_are_bounded_expire_and_cache_missing_history_failures() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("missing.jsonl");
        let store = MediaStore::new(root.path()).unwrap();
        let mut first = None;
        for index in 0..MAX_RECOVERY_REFS + 4 {
            let native = json!({"thread":{"id":"thread","path":path,"turns":[{"id":"turn","items":[
                {"id":format!("user-{index}"),"type":"userMessage","content":[{"type":"localImage","path":"C:/dead.png"}]}
            ]}]}});
            let proof =
                NativeHistory::from_native_thread(&native["thread"], "runtime", "connection")
                    .unwrap();
            let view =
                store.project_with_history(&native, "runtime", "connection", "thread", &proof);
            let image = &view["thread"]["turns"][0]["items"][0]["content"][0];
            assert_eq!(image["status"], "recoverable");
            if index == 0 {
                first = Some(image["mediaId"].as_str().unwrap().to_owned());
            }
            assert_eq!(
                proof.scan_builds.load(std::sync::atomic::Ordering::Relaxed),
                0
            );
        }
        assert_eq!(store.recoveries.lock().unwrap().len(), MAX_RECOVERY_REFS);
        assert_eq!(
            fs::read_dir(root.path().join("tool-media"))
                .unwrap()
                .count(),
            0
        );
        let id = store
            .recoveries
            .lock()
            .unwrap()
            .keys()
            .next()
            .unwrap()
            .clone();
        let owner = store.recoveries.lock().unwrap()[&id].owner.clone();
        assert_eq!(store.read(&owner, &id, 0, 8).unwrap()["status"], "failed");
        assert_eq!(store.read(&owner, &id, 0, 8).unwrap()["status"], "failed");
        let mut entries = store.recoveries.lock().unwrap();
        Arc::get_mut(entries.get_mut(&id).unwrap()).unwrap().created = 0;
        drop(entries);
        assert_eq!(store.read(&owner, &id, 0, 8).unwrap()["status"], "expired");
        assert!(first.is_some());
    }
}
