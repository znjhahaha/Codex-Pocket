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
    path::{Path, PathBuf},
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
type LocalKey = (String, String, String, String, usize, PathBuf);
#[derive(Clone, serde::Serialize, serde::Deserialize)]
struct LocalSnapshot {
    canonical: PathBuf,
    owner: MediaOwner,
    media_id: String,
    created: u64,
}
#[derive(serde::Serialize, serde::Deserialize)]
struct LocalBinding {
    source: PathBuf,
    slot: usize,
    snapshot: LocalSnapshot,
}
#[derive(Clone)]
pub struct MediaStore {
    store: Arc<implementation::MediaStore>,
    recoveries: Arc<Mutex<BTreeMap<String, Arc<RecoveryReference>>>>,
    locals: Arc<Mutex<BTreeMap<LocalKey, LocalSnapshot>>>,
    bindings_path: PathBuf,
}
impl MediaStore {
    pub fn new(state_dir: impl AsRef<Path>) -> Result<Self> {
        let bindings_path=state_dir.as_ref().join("native-media-bindings.json");
        // A damaged advisory cache cannot prevent the computer bridge from starting.
        let locals=read_local_bindings(&bindings_path).unwrap_or_default();
        Ok(Self {
            store: Arc::new(implementation::MediaStore::with_limits(
                state_dir,
                crate::workspace::MAX_IMAGE,
                crate::workspace::IMAGE_CHUNK,
            )?),
            recoveries: Arc::new(Mutex::new(BTreeMap::new())),
            locals: Arc::new(Mutex::new(locals)),
            bindings_path,
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
                        if let Some(local) = self.local_reference(&owner, slot, history) {
                            *part = local;
                            continue;
                        }
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
    fn local_reference(
        &self,
        owner: &MediaOwner,
        slot: usize,
        history: &NativeHistory,
    ) -> Option<Value> {
        let source = history.local_source(owner, slot)?;
        let key = (
            owner.runtime_id.clone(),
            owner.thread_id.clone(),
            owner.turn_id.clone(),
            owner.item_id.clone(),
            slot,
            source.to_path_buf(),
        );
        let mut locals = self.locals.lock().ok()?;
        let now = media_now();
        locals.retain(|_, entry| now.saturating_sub(entry.created) < RECOVERY_TTL);
        let (image, canonical, created) =
            if let Ok((file, canonical)) = history.local_image(owner, slot) {
                (
                    self.store.project_user_image_file(owner, slot, file),
                    canonical,
                    now,
                )
            } else {
                let snapshot = locals.get(&key)?;
                if !history.authorizes_local_snapshot(owner, slot, &snapshot.canonical) {
                    return None;
                }
                (
                    self.store
                        .renew_user_image(&snapshot.owner, owner, slot, &snapshot.media_id),
                    snapshot.canonical.clone(),
                    snapshot.created,
                )
            };
        if image["status"] != "ready" {
            return None;
        }
        if !locals.contains_key(&key) {
            while locals.len() >= MAX_RECOVERY_REFS {
                let oldest = locals
                    .iter()
                    .min_by_key(|(_, entry)| entry.created)
                    .map(|(key, _)| key.clone())?;
                locals.remove(&oldest);
            }
        }
        locals.insert(
            key,
            LocalSnapshot {
                canonical,
                owner: owner.clone(),
                media_id: image["mediaId"].as_str()?.into(),
                created,
            },
        );
        let bindings:Vec<_>=locals.iter().map(|(key,snapshot)|LocalBinding{source:key.5.clone(),slot:key.4,snapshot:snapshot.clone()}).collect();
        let data=serde_json::to_vec(&bindings).ok()?;
        if data.len()>4*1024*1024 {return None;}
        crate::storage::atomic_write(&self.bindings_path,&data).ok()?;
        Some(image)
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
        // A transient read error must not make a refreshed native item unreadable.
        // Keep its proven opaque recovery reference; reads still report the
        // current error and recheck the original owner before retrying.
        if let Some(resolved) = resolved.filter(|value| value["status"] != "failed") {
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
        if let Some(resolved) = resolution.as_ref().filter(|r| r["status"] == "ready") {
            let result = self.store.read(
                owner,
                resolved["mediaId"].as_str().unwrap_or(""),
                offset,
                max_bytes,
            )?;
            if result["status"] == "ready" {
                let mut result = result;
                result["mediaId"] = json!(media_id);
                return Ok(result);
            }
            *resolution = None;
        }
        if resolution
            .as_ref()
            .is_none_or(|r| matches!(r["status"].as_str(), Some("failed" | "expired")))
        {
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

fn read_local_bindings(path:&Path)->Result<BTreeMap<LocalKey,LocalSnapshot>> {
    if !path.try_exists()? {return Ok(BTreeMap::new());}
    let bytes=crate::storage::read_physical(path,4*1024*1024)?;
    let bindings:Vec<LocalBinding>=serde_json::from_slice(&bytes)?;
    if bindings.len()>MAX_RECOVERY_REFS {bail!("Too many native media bindings");}
    let mut entries=BTreeMap::new();
    for binding in bindings {
        let snapshot=binding.snapshot;
        if !binding.source.is_absolute() || !snapshot.canonical.is_absolute() || binding.slot>4096
            || snapshot.media_id.len()!=32 || !snapshot.media_id.bytes().all(|b|b.is_ascii_hexdigit())
            || snapshot.created>media_now() || media_now().saturating_sub(snapshot.created)>=RECOVERY_TTL {continue;}
        let owner=&snapshot.owner;
        if [&owner.runtime_id,&owner.connection_id,&owner.thread_id,&owner.turn_id,&owner.item_id].iter().any(|id|id.is_empty()||id.len()>4096||id.chars().any(char::is_control)){continue;}
        // Persisted bindings never authorize a phone path: every use still checks native item,
        // source path, current roots and the underlying cached blob's original owner.
        let key=(owner.runtime_id.clone(),owner.thread_id.clone(),owner.turn_id.clone(),owner.item_id.clone(),binding.slot,binding.source);
        entries.insert(key,snapshot);
    }
    Ok(entries)
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
    fn local_snapshots_require_current_roots_exact_path_item_and_slot() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("paste.png");
        fs::write(&path, b"\x89PNG\r\n\x1a\nx").unwrap();
        let native = json!({"thread":{"id":"thread","path":root.path().join("unneeded.jsonl"),"turns":[{"id":"turn","items":[{"type":"userMessage","id":"native-user","content":[{"type":"localImage","path":path}]}]}]}});
        let store = MediaStore::new(root.path()).unwrap();
        let make_proof = |value: &Value, generation: &str, allowed: bool| {
            NativeHistory::from_native_thread(&value["thread"], "runtime", generation)
                .unwrap()
                .with_image_roots(if allowed {
                    vec![dunce::canonicalize(root.path()).unwrap()]
                } else {
                    vec![]
                })
        };
        let denied = store.project_with_history(
            &native,
            "runtime",
            "one",
            "thread",
            &make_proof(&native, "one", false),
        );
        assert_ne!(
            denied["thread"]["turns"][0]["items"][0]["content"][0]["status"],
            "ready"
        );
        let ready = store.project_with_history(
            &native,
            "runtime",
            "one",
            "thread",
            &make_proof(&native, "one", true),
        );
        assert_eq!(
            ready["thread"]["turns"][0]["items"][0]["content"][0]["status"],
            "ready"
        );
        fs::remove_file(&path).unwrap();
        for mode in 0..4 {
            let mut changed = native.clone();
            if mode == 1 {
                changed["thread"]["turns"][0]["items"][0]["id"] = json!("other");
            }
            if mode == 2 {
                changed["thread"]["turns"][0]["items"][0]["content"][0]["path"] =
                    json!(root.path().join("other.png"));
            }
            if mode == 3 {
                changed["thread"]["turns"][0]["items"][0]["content"] =
                    json!([{"type":"text","text":"x"},{"type":"localImage","path":path}]);
            }
            let proof = make_proof(&changed, "two", mode != 0);
            let projected =
                store.project_with_history(&changed, "runtime", "two", "thread", &proof);
            assert!(
                projected["thread"]["turns"][0]["items"][0]["content"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .all(|part| part["status"] != "ready")
            );
        }
        let renewed = store.project_with_history(
            &native,
            "runtime",
            "two",
            "thread",
            &make_proof(&native, "two", true),
        );
        assert_eq!(
            renewed["thread"]["turns"][0]["items"][0]["content"][0]["status"],
            "ready"
        );
    }
    #[test]
    #[ignore = "Explicit read-only native fixture probe; no text, paths, secrets or image bytes printed"]
    fn real_rollout_seven_source_native_temp_images_are_cached_and_renewed() {
        let path = Path::new(
            "C:/Users/82206/.codex/sessions/2026/11/11/rollout-2026-11-11T17-49-02-01a1ca02-c5db-74a0-a1d5-cf3ef5eb0473.jsonl",
        );
        let bytes = crate::storage::read_physical(path, 512 * 1024 * 1024).unwrap();
        let thread = "01a1ca02-c5db-74a0-a1d5-cf3ef5eb0473";
        let mut turns: BTreeMap<String, Vec<Value>> = BTreeMap::new();
        for line in bytes
            .split(|byte| *byte == b'\n')
            .filter(|line| !line.is_empty())
        {
            let record: Value = serde_json::from_slice(line).unwrap();
            let payload = &record["payload"];
            if record["type"] != "event_msg"
                || payload["type"] != "item_completed"
                || payload["thread_id"] != thread
                || payload["item"]["type"] != "UserMessage"
            {
                continue;
            }
            let content: Vec<_> = payload["item"]["content"]
                .as_array()
                .unwrap()
                .iter()
                .filter(|part| part["type"] == "local_image")
                .map(|part| json!({"type":"localImage","path":part["path"]}))
                .collect();
            if content.is_empty() {
                continue;
            }
            turns
                .entry(payload["turn_id"].as_str().unwrap().into())
                .or_default()
                .push(json!({"type":"userMessage","id":payload["item"]["id"],"content":content}));
        }
        let native = json!({"thread":{"id":thread,"path":path,"turns":turns.into_iter().map(|(id,items)|json!({"id":id,"items":items})).collect::<Vec<_>>()}});
        let root = tempfile::tempdir().unwrap();
        let store = MediaStore::new(root.path()).unwrap();
        let allowed = vec![dunce::canonicalize(std::env::temp_dir()).unwrap()];
        for generation in ["first", "reconnected"] {
            let proof = NativeHistory::from_native_thread(&native["thread"], "runtime", generation)
                .unwrap()
                .with_image_roots(allowed.clone());
            let view = store.project_with_history(&native, "runtime", generation, thread, &proof);
            let mut count = 0;
            for turn in view["thread"]["turns"].as_array().unwrap() {
                for item in turn["items"].as_array().unwrap() {
                    let owner = MediaOwner {
                        runtime_id: "runtime".into(),
                        connection_id: generation.into(),
                        thread_id: thread.into(),
                        turn_id: turn["id"].as_str().unwrap().into(),
                        item_id: item["id"].as_str().unwrap().into(),
                    };
                    for part in item["content"].as_array().unwrap() {
                        assert!(
                            part["status"] == "ready",
                            "Source-native fixture image unavailable"
                        );
                        let result = store
                            .read(&owner, part["mediaId"].as_str().unwrap(), 0, 192 * 1024)
                            .unwrap();
                        assert!(
                            result["status"] == "ready",
                            "Cached fixture image unavailable"
                        );
                        assert!(part.get("path").is_none());
                        count += 1;
                    }
                }
            }
            assert_eq!(count, 7);
        }
    }

    #[test]
    fn native_bindings_survive_reopen_after_temp_source_deletion() {
        let state = tempfile::tempdir().unwrap();
        let images = tempfile::tempdir().unwrap();
        let path = images.path().join("original.png");
        fs::write(&path,b"\x89PNG\r\n\x1a\nx").unwrap();
        let native = json!({"thread":{"id":"thread","path":state.path().join("native.jsonl"),"turns":[{"id":"turn","items":[{"type":"userMessage","id":"native-user","content":[{"type":"text","text":"omitted"},{"type":"localImage","path":path}]}]}]}});
        let proof = |generation: &str, allowed: bool| NativeHistory::from_native_thread(&native["thread"],"runtime",generation).unwrap()
            .with_image_roots(if allowed {vec![dunce::canonicalize(images.path()).unwrap()]} else {vec![]});
        let first = {
            let store = MediaStore::new(state.path()).unwrap();
            let view = store.project_with_history(&native,"runtime","before","thread",&proof("before",true));
            assert_eq!(view["thread"]["turns"][0]["items"][0]["content"][1]["status"],"ready");
            view["thread"]["turns"][0]["items"][0]["content"][1]["mediaId"].as_str().unwrap().to_owned()
        };
        fs::remove_file(&path).unwrap(); // Only this test's disposable image is deleted.
        let store = MediaStore::new(state.path()).unwrap();
        let denied = store.project_with_history(&native,"runtime","after","thread",&proof("after",false));
        assert_ne!(denied["thread"]["turns"][0]["items"][0]["content"][1]["status"],"ready");
        let view = store.project_with_history(&native,"runtime","after","thread",&proof("after",true));
        let image = &view["thread"]["turns"][0]["items"][0]["content"][1];
        assert_eq!(image["status"],"ready");
        assert_ne!(image["mediaId"],first);
        let owner = MediaOwner {runtime_id:"runtime".into(),connection_id:"after".into(),thread_id:"thread".into(),turn_id:"turn".into(),item_id:"native-user".into()};
        assert_eq!(store.read(&owner,image["mediaId"].as_str().unwrap(),0,9).unwrap()["data"],"iVBORw0KGgp4");
        assert!(store.read(&owner,&first,0,9).is_err());
    }

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
        // A temporary inaccessible/incomplete history must not poison this
        // opaque reference for the rest of its 24-hour lifetime.
        fs::rename(&path, &moved).unwrap();
        assert_eq!(
            store.read(&owner, first_id, 0, 8).unwrap()["status"],
            "failed"
        );
        let refreshed =
            store.project_with_history(&native, "runtime", "connection", "thread", &proof);
        let refreshed_image = &refreshed["thread"]["turns"][0]["items"][0]["content"][0];
        assert_eq!(refreshed_image["status"], "recoverable");
        assert_eq!(refreshed_image["mediaId"], first_id);
        fs::rename(&moved, &path).unwrap();
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
        // A pruned/missing underlying blob must not poison a still-valid recovery reference.
        let resolved_id = store.recoveries.lock().unwrap()[first_id]
            .resolved
            .lock()
            .unwrap()
            .as_ref()
            .unwrap()["mediaId"]
            .as_str()
            .unwrap()
            .to_owned();
        fs::remove_file(
            root.path()
                .join("tool-media")
                .join(format!("{resolved_id}.blob")),
        )
        .unwrap();
        assert_eq!(
            store.read(&owner, first_id, 0, 8).unwrap()["status"],
            "ready"
        );
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
