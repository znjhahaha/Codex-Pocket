//! Bounded native media projection. Native values are never modified. No caller-supplied paths.
use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::Mutex,
    time::{SystemTime, UNIX_EPOCH},
};

pub const MAX_MEDIA_BYTES: u64 = 24 * 1024 * 1024;
pub const MEDIA_CHUNK_BYTES: usize = 192 * 1024;
const STORE_BYTES: u64 = 256 * 1024 * 1024;
const STORE_ITEMS: usize = 256;
const MAX_BLOCKS: usize = 256;
const TTL_SECONDS: u64 = 24 * 60 * 60;

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaOwner {
    pub runtime_id: String,
    pub connection_id: String,
    pub thread_id: String,
    pub turn_id: String,
    pub item_id: String,
}
impl MediaOwner {
    fn valid(&self) -> bool {
        [
            &self.runtime_id,
            &self.connection_id,
            &self.thread_id,
            &self.turn_id,
            &self.item_id,
        ]
        .iter()
        .all(|s| !s.is_empty() && s.len() <= 512 && !s.chars().any(char::is_control))
    }
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Entry {
    owner: MediaOwner,
    mime: String,
    size: u64,
    created: u64,
    slot: usize,
}

pub struct MediaStore {
    root: PathBuf,
    max_image: u64,
    chunk: usize,
    entries: Mutex<BTreeMap<String, Entry>>,
}
impl MediaStore {
    pub fn new(state_dir: impl AsRef<Path>) -> Result<Self> {
        Self::with_limits(state_dir, MAX_MEDIA_BYTES, MEDIA_CHUNK_BYTES)
    }
    pub fn with_limits(state_dir: impl AsRef<Path>, max_image: u64, chunk: usize) -> Result<Self> {
        if max_image == 0 || max_image > MAX_MEDIA_BYTES || chunk == 0 || chunk > MEDIA_CHUNK_BYTES
        {
            bail!("Invalid media limits");
        }
        let root = state_dir.as_ref().join("tool-media");
        fs::create_dir_all(&root)?;
        regular_directory(&root)?;
        let mut entries = BTreeMap::new();
        // A bounded scan restores metadata, never blob contents. Orphan files are removed.
        for file in fs::read_dir(&root)?.take(STORE_ITEMS * 4) {
            let file = file?;
            let path = file.path();
            let id = path.file_stem().and_then(|s| s.to_str()).unwrap_or("");
            if path.extension().and_then(|s| s.to_str()) != Some("json") || !opaque_id(id) {
                continue;
            }
            let loaded = (|| -> Result<Entry> {
                let file = regular_file(&path)?;
                if file.metadata()?.len() > 8192 {
                    bail!("Invalid media metadata");
                }
                let mut bytes = Vec::new();
                file.take(8193).read_to_end(&mut bytes)?;
                let entry: Entry = serde_json::from_slice(&bytes)?;
                if !entry.owner.valid()
                    || entry.size == 0
                    || entry.size > max_image
                    || now().saturating_sub(entry.created) >= TTL_SECONDS
                {
                    bail!("Expired media");
                }
                if regular_file(&root.join(format!("{id}.blob")))?
                    .metadata()?
                    .len()
                    != entry.size
                {
                    bail!("Missing media");
                }
                Ok(entry)
            })();
            if let Ok(entry) = loaded {
                entries.insert(id.to_owned(), entry);
            } else {
                remove_entry(&root, id);
            }
        }
        let store = Self {
            root,
            max_image,
            chunk,
            entries: Mutex::new(entries),
        };
        {
            let mut entries = store
                .entries
                .lock()
                .map_err(|_| anyhow::anyhow!("Media store unavailable"))?;
            store.prune(&mut entries, 0);
        }
        for file in fs::read_dir(&store.root)?.take(STORE_ITEMS * 4).flatten() {
            let path = file.path();
            let id = path.file_stem().and_then(|s| s.to_str()).unwrap_or("");
            if path.extension().and_then(|s| s.to_str()) == Some("part")
                || (path.extension().and_then(|s| s.to_str()) == Some("blob")
                    && opaque_id(id)
                    && !store.entries.lock().unwrap().contains_key(id))
            {
                let _ = fs::remove_file(path);
            }
        }
        Ok(store)
    }
    fn prune(&self, entries: &mut BTreeMap<String, Entry>, incoming: u64) {
        let expired: Vec<_> = entries
            .iter()
            .filter(|(_, e)| now().saturating_sub(e.created) >= TTL_SECONDS)
            .map(|(id, _)| id.clone())
            .collect();
        for id in expired {
            entries.remove(&id);
            remove_entry(&self.root, &id);
        }
        while entries.len() >= STORE_ITEMS
            || entries.values().map(|e| e.size).sum::<u64>() + incoming > STORE_BYTES
        {
            let Some(id) = entries
                .iter()
                .min_by_key(|(_, e)| e.created)
                .map(|(id, _)| id.clone())
            else {
                break;
            };
            entries.remove(&id);
            remove_entry(&self.root, &id);
        }
    }
    fn image(&self, owner: &MediaOwner, slot: usize, part: &Value) -> Value {
        // A projection may cross more than one phone transport boundary. Never restore its bytes.
        if let Some(status) = part.get("status").and_then(Value::as_str)
            && part.get("mediaId").is_some()
        {
            let mime = part.get("mimeType").and_then(Value::as_str).unwrap_or("");
            let status = if matches!(status, "ready" | "unsupported" | "expired" | "failed") {
                status
            } else {
                "failed"
            };
            let message = part
                .get("message")
                .and_then(Value::as_str)
                .filter(|s| s.len() <= 512)
                .unwrap_or("");
            let mut block = image_status(owner, status, message, mime);
            if status != "ready" {
                return block;
            }
            let id = part.get("mediaId").and_then(Value::as_str).unwrap_or("");
            let valid = self
                .entries
                .lock()
                .ok()
                .and_then(|entries| entries.get(id).cloned());
            if let Some(entry) = valid {
                if opaque_id(id)
                    && entry.owner == *owner
                    && entry.mime == mime
                    && now().saturating_sub(entry.created) < TTL_SECONDS
                {
                    block["mediaId"] = json!(id);
                    block["byteLength"] = json!(entry.size);
                    return block;
                }
            }
            return image_status(owner, "expired", "Image reference is unavailable", mime);
        }
        if part.get("type").and_then(Value::as_str) == Some("localImage") {
            return image_status(
                owner,
                "expired",
                "Native image requires history recovery",
                "",
            );
        }
        let declared = part
            .get("mimeType")
            .or_else(|| part.get("mime"))
            .and_then(Value::as_str)
            .unwrap_or("");
        let source = part
            .get("imageUrl")
            .or_else(|| part.get("url"))
            .or_else(|| {
                part.get("image_url")
                    .and_then(|v| v.as_str().map(|_| v).or_else(|| v.get("url")))
            })
            .and_then(Value::as_str)
            .unwrap_or("");
        let (mime, encoded) = if !source.is_empty() {
            let Some((header, encoded)) = source.split_once(',') else {
                return image_status(
                    owner,
                    "unsupported",
                    "External tool image is unavailable",
                    declared,
                );
            };
            let Some(mime) = header
                .strip_prefix("data:")
                .and_then(|s| s.strip_suffix(";base64"))
            else {
                return image_status(owner, "unsupported", "Unsupported image source", declared);
            };
            (mime, encoded)
        } else {
            (
                declared,
                part.get("data").and_then(Value::as_str).unwrap_or(""),
            )
        };
        if !supported_mime(mime) {
            return image_status(owner, "unsupported", "Unsupported image type", mime);
        }
        let size = match decoded_size(encoded) {
            Some(size) if size > 0 && size <= self.max_image => size,
            _ => {
                return image_status(
                    owner,
                    "failed",
                    "Image data exceeds the limit or is invalid",
                    mime,
                );
            }
        };
        if !owner.valid() {
            return image_status(owner, "failed", "Image ownership is unavailable", mime);
        }
        let head = match decode_piece(&encoded.as_bytes()[..encoded.len().min(32)]) {
            Ok(v) => v,
            Err(_) => return image_status(owner, "failed", "Invalid image data", mime),
        };
        if sniff_mime(&head) != Some(mime) {
            return image_status(
                owner,
                "unsupported",
                "Image type does not match its bytes",
                mime,
            );
        }
        let result = (|| -> Result<String> {
            let mut entries = self
                .entries
                .lock()
                .map_err(|_| anyhow::anyhow!("Media store unavailable"))?;
            // Match native item + ordinal + exact bounded byte stream; replay keeps opaque IDs stable.
            for (id, entry) in entries.iter() {
                if entry.owner == *owner
                    && entry.slot == slot
                    && entry.size == size
                    && entry.mime == mime
                    && now().saturating_sub(entry.created) < TTL_SECONDS
                    && matches_encoded(&self.root.join(format!("{id}.blob")), encoded)?
                {
                    return Ok(id.clone());
                }
            }
            self.prune(&mut entries, size);
            regular_directory(&self.root)?;
            let id = uuid::Uuid::new_v4().simple().to_string();
            let temporary = self.root.join(format!("{id}.part"));
            let blob = self.root.join(format!("{id}.blob"));
            let entry = Entry {
                owner: owner.clone(),
                mime: mime.to_owned(),
                size,
                created: now(),
                slot,
            };
            let write = (|| -> Result<()> {
                let mut file = OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(&temporary)?;
                for (index, piece) in encoded.as_bytes().chunks(8192).enumerate() {
                    if (index + 1) * 8192 < encoded.len() && piece.contains(&b'=') {
                        bail!("Invalid base64 padding");
                    }
                    file.write_all(&decode_piece(piece)?)?;
                }
                if file.metadata()?.len() != size {
                    bail!("Invalid image length");
                }
                file.sync_all()?;
                drop(file);
                fs::rename(&temporary, &blob)?;
                let bytes = serde_json::to_vec(&entry)?;
                let mut metadata = OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(self.root.join(format!("{id}.json")))?;
                metadata.write_all(&bytes)?;
                metadata.sync_all()?;
                Ok(())
            })();
            if let Err(error) = write {
                let _ = fs::remove_file(&temporary);
                remove_entry(&self.root, &id);
                return Err(error);
            }
            entries.insert(id.clone(), entry);
            Ok(id)
        })();
        match result {
            Ok(id) => {
                let mut block = image_status(owner, "ready", "", mime);
                block["mediaId"] = json!(id);
                block["byteLength"] = json!(size);
                block
            }
            Err(_) => image_status(owner, "failed", "Image storage failed", mime),
        }
    }
    /// Build the phone view while extracting blobs, before history/event JSON serialization.
    pub fn project(
        &self,
        native: &Value,
        runtime_id: &str,
        connection_id: &str,
        fallback_thread_id: &str,
    ) -> Value {
        let owner = MediaOwner {
            runtime_id: runtime_id.into(),
            connection_id: connection_id.into(),
            thread_id: fallback_thread_id.into(),
            turn_id: String::new(),
            item_id: String::new(),
        };
        self.walk(native, &owner, 0, 0)
    }
    /// Store an image recovered by the agent from a proven native user item/content part.
    pub fn project_user_image(
        &self,
        owner: &MediaOwner,
        content_part: usize,
        part: &Value,
    ) -> Value {
        let wrong_slot = part
            .get("mediaId")
            .and_then(Value::as_str)
            .is_some_and(|id| {
                self.entries.lock().ok().is_some_and(|entries| {
                    entries
                        .get(id)
                        .is_some_and(|entry| entry.slot != content_part)
                })
            });
        let mut image = if wrong_slot {
            image_status(
                owner,
                "expired",
                "Image reference belongs to another content part",
                "",
            )
        } else {
            self.image(owner, content_part, part)
        };
        image["contentPart"] = json!(content_part);
        image
    }
    fn walk(&self, value: &Value, owner: &MediaOwner, depth: usize, slot: usize) -> Value {
        if depth > 64 {
            return Value::Null;
        }
        match value {
            Value::Array(items) => Value::Array(
                items
                    .iter()
                    .enumerate()
                    .map(|(slot, item)| self.walk(item, owner, depth + 1, slot))
                    .collect(),
            ),
            Value::Object(object) => {
                let mut owner = owner.clone();
                if let Some(id) = value.get("threadId").and_then(Value::as_str) {
                    owner.thread_id = id.into();
                }
                if let Some(id) = value.get("turnId").and_then(Value::as_str) {
                    owner.turn_id = id.into();
                }
                if let Some(id) = value.get("itemId").and_then(Value::as_str) {
                    owner.item_id = id.into();
                }
                if object.contains_key("turns") {
                    if let Some(id) = value.get("id").and_then(Value::as_str) {
                        owner.thread_id = id.into();
                    }
                }
                if object.contains_key("items") {
                    if let Some(id) = value.get("id").and_then(Value::as_str) {
                        owner.turn_id = id.into();
                    }
                }
                let kind = value.get("type").and_then(Value::as_str).unwrap_or("");
                if image_kind(kind) {
                    return self.image(&owner, slot, value);
                }
                if kind == "userMessage" {
                    owner.item_id = value.get("id").and_then(Value::as_str).unwrap_or("").into();
                    let mut result = serde_json::Map::new();
                    for (key, field) in object {
                        if key == "content"
                            && let Some(parts) = field.as_array()
                        {
                            let mut content: Vec<_> = parts
                                .iter()
                                .take(MAX_BLOCKS)
                                .enumerate()
                                .map(|(slot, part)| {
                                    if part
                                        .get("type")
                                        .and_then(Value::as_str)
                                        .is_some_and(image_kind)
                                    {
                                        self.project_user_image(&owner, slot, part)
                                    } else {
                                        self.walk(part, &owner, depth + 1, slot)
                                    }
                                })
                                .collect();
                            if parts.len() > MAX_BLOCKS {
                                content.push(image_status(
                                    &owner,
                                    "unsupported",
                                    "Additional media omitted by the item limit",
                                    "",
                                ));
                            }
                            result.insert(key.clone(), Value::Array(content));
                        } else {
                            result.insert(key.clone(), self.walk(field, &owner, depth + 1, slot));
                        }
                    }
                    return Value::Object(result);
                }
                if matches!(
                    kind,
                    "mcpToolCall" | "dynamicToolCall" | "functionCallOutput"
                ) {
                    owner.item_id = value.get("id").and_then(Value::as_str).unwrap_or("").into();
                    return self.tool_item(value, &owner, kind, depth);
                }
                Value::Object(
                    object
                        .iter()
                        .map(|(key, value)| {
                            (key.clone(), self.walk(value, &owner, depth + 1, slot))
                        })
                        .collect(),
                )
            }
            // A nested data URI must never slip back into a phone record.
            Value::String(s) if s.starts_with("data:image/") => Value::Null,
            _ => value.clone(),
        }
    }
    fn tool_item(&self, native: &Value, owner: &MediaOwner, kind: &str, depth: usize) -> Value {
        let content = if let Some(content) = native.get("toolOutputBlocks") {
            Some(content)
        } else {
            match kind {
                "mcpToolCall" => native.get("result").and_then(|r| r.get("content")),
                "dynamicToolCall" => native.get("contentItems"),
                _ => native.get("output").or_else(|| native.get("content")),
            }
        };
        let content = content.and_then(|c| {
            c.as_array()
                .or_else(|| c.get("content").and_then(Value::as_array))
        });
        let mut blocks = Vec::new();
        if let Some(parts) = content {
            for (slot, part) in parts.iter().take(MAX_BLOCKS).enumerate() {
                let kind = part.get("type").and_then(Value::as_str).unwrap_or("");
                match kind {
                    "text" | "inputText" | "outputText" | "input_text" | "output_text" => {
                        if let Some(text) = part.get("text").and_then(Value::as_str) {
                            if !text.is_empty() {
                                blocks.push(json!({"type":"text","text":text}));
                            }
                        }
                    }
                    kind if image_kind(kind) => blocks.push(self.image(owner, slot, part)),
                    _ => {}
                }
            }
            if parts.len() > MAX_BLOCKS {
                blocks.push(image_status(
                    owner,
                    "unsupported",
                    "Additional media omitted by the item limit",
                    "",
                ));
            }
        } else if kind == "functionCallOutput" {
            if let Some(text) = native.get("output").and_then(Value::as_str) {
                blocks.push(json!({"type":"text","text":text}));
            }
        }
        let mut result = serde_json::Map::new();
        for (key, value) in native.as_object().unwrap() {
            if matches!(
                key.as_str(),
                "contentItems" | "output" | "content" | "toolOutputBlocks"
            ) {
                continue;
            }
            if key == "result" && value.is_object() {
                let mut clean = serde_json::Map::new();
                for (key, value) in value.as_object().unwrap() {
                    if key != "content" {
                        clean.insert(key.clone(), self.walk(value, owner, depth + 1, 0));
                    }
                }
                clean.insert("content".into(), Value::Array(blocks.clone()));
                result.insert(key.clone(), Value::Object(clean));
            } else {
                result.insert(key.clone(), self.walk(value, owner, depth + 1, 0));
            }
        }
        if kind == "dynamicToolCall" {
            result.insert("contentItems".into(), Value::Array(blocks.clone()));
        }
        if kind == "functionCallOutput" {
            result.insert("output".into(), Value::Array(blocks.clone()));
        }
        result.insert("toolOutputBlocks".into(), Value::Array(blocks));
        Value::Object(result)
    }
    pub fn read(
        &self,
        owner: &MediaOwner,
        media_id: &str,
        offset: u64,
        max_bytes: usize,
    ) -> Result<Value> {
        if !opaque_id(media_id) || !owner.valid() || max_bytes == 0 || max_bytes > self.chunk {
            bail!("Invalid media request");
        }
        let entries = self
            .entries
            .lock()
            .map_err(|_| anyhow::anyhow!("Media store unavailable"))?;
        let Some(entry) = entries.get(media_id) else {
            return Ok(json!({"mediaId":media_id,"status":"expired"}));
        };
        if entry.owner != *owner {
            bail!("Media does not belong to the selected item");
        }
        if now().saturating_sub(entry.created) >= TTL_SECONDS {
            return Ok(json!({"mediaId":media_id,"status":"expired"}));
        }
        if offset >= entry.size {
            bail!("Offset past end of media");
        }
        regular_directory(&self.root)?;
        let Ok(mut file) = regular_file(&self.root.join(format!("{media_id}.blob"))) else {
            return Ok(json!({"mediaId":media_id,"status":"expired"}));
        };
        if file.metadata()?.len() != entry.size {
            return Ok(json!({"mediaId":media_id,"status":"failed"}));
        }
        let mut head = [0u8; 32];
        let count = file.read(&mut head)?;
        if sniff_mime(&head[..count]) != Some(entry.mime.as_str()) {
            return Ok(json!({"mediaId":media_id,"status":"unsupported"}));
        }
        file.seek(SeekFrom::Start(offset))?;
        let mut bytes = Vec::with_capacity(max_bytes);
        file.take((entry.size - offset).min(max_bytes as u64))
            .read_to_end(&mut bytes)?;
        Ok(
            json!({"mediaId":media_id,"status":"ready","mime":entry.mime,"mimeType":entry.mime,"size":entry.size,"offset":offset,"eof":offset + bytes.len() as u64 == entry.size,"data":encode(&bytes)}),
        )
    }
}
fn image_kind(kind: &str) -> bool {
    matches!(
        kind,
        "image" | "inputImage" | "imageUrl" | "input_image" | "image_url" | "localImage"
    )
}
fn image_status(owner: &MediaOwner, status: &str, message: &str, mime: &str) -> Value {
    json!({"type":"image","mediaId":"","mimeType":if supported_mime(mime){mime}else{""},"byteLength":0,"status":status,"message":message,"runtimeId":owner.runtime_id,"threadId":owner.thread_id,"turnId":owner.turn_id,"itemId":owner.item_id})
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
fn opaque_id(id: &str) -> bool {
    id.len() == 32
        && id
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}
fn remove_entry(root: &Path, id: &str) {
    if opaque_id(id) {
        let _ = fs::remove_file(root.join(format!("{id}.blob")));
        let _ = fs::remove_file(root.join(format!("{id}.json")));
    }
}
fn linked(metadata: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        if metadata.file_attributes() & 0x400 != 0 {
            return true;
        }
    }
    metadata.file_type().is_symlink()
}
fn regular_directory(path: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.is_dir() || linked(&metadata) {
        bail!("Invalid media directory");
    }
    Ok(())
}
fn regular_file(path: &Path) -> Result<File> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.is_file() || linked(&metadata) {
        bail!("Invalid media blob");
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        options.share_mode(1).custom_flags(0x00200000);
    }
    #[cfg(target_os = "linux")]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(0x20000 | 0x800);
    }
    let file = options.open(path)?;
    if !file.metadata()?.is_file() || linked(&file.metadata()?) {
        bail!("Invalid media blob");
    }
    Ok(file)
}
pub fn sniff_mime(head: &[u8]) -> Option<&'static str> {
    if head.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("image/png")
    } else if head.starts_with(&[0xff, 0xd8, 0xff]) {
        Some("image/jpeg")
    } else if head.starts_with(b"GIF87a") || head.starts_with(b"GIF89a") {
        Some("image/gif")
    } else if head.len() >= 12 && &head[..4] == b"RIFF" && &head[8..12] == b"WEBP" {
        Some("image/webp")
    } else if head.starts_with(b"BM") {
        Some("image/bmp")
    } else {
        None
    }
}
fn supported_mime(mime: &str) -> bool {
    matches!(
        mime,
        "image/png" | "image/jpeg" | "image/gif" | "image/webp" | "image/bmp"
    )
}
const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
fn decoded_size(text: &str) -> Option<u64> {
    if text.is_empty() || text.len() % 4 != 0 {
        return None;
    }
    let padding = if text.ends_with("==") {
        2
    } else if text.ends_with('=') {
        1
    } else {
        0
    };
    Some((text.len() / 4 * 3 - padding) as u64)
}
// Pieces align on base64 quanta; allocations never exceed 6 KiB per decode operation.
fn decode_piece(text: &[u8]) -> Result<Vec<u8>> {
    if text.len() % 4 != 0 {
        bail!("Invalid base64 length");
    }
    let mut bytes = Vec::with_capacity(text.len() / 4 * 3);
    for (index, q) in text.chunks_exact(4).enumerate() {
        let value = |b: u8| {
            ALPHABET
                .iter()
                .position(|v| *v == b)
                .map(|n| n as u8)
                .context("Invalid base64 data")
        };
        let a = value(q[0])?;
        let b = value(q[1])?;
        let last = (index + 1) * 4 == text.len();
        if q[2] == b'=' {
            if !last || q[3] != b'=' || b & 15 != 0 {
                bail!("Invalid base64 padding");
            }
            bytes.push(a << 2 | b >> 4);
        } else {
            let c = value(q[2])?;
            bytes.push(a << 2 | b >> 4);
            bytes.push(b << 4 | c >> 2);
            if q[3] == b'=' {
                if !last || c & 3 != 0 {
                    bail!("Invalid base64 padding");
                }
            } else {
                let d = value(q[3])?;
                bytes.push(c << 6 | d);
            }
        }
    }
    Ok(bytes)
}
fn encode(bytes: &[u8]) -> String {
    let mut text = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for q in bytes.chunks(3) {
        text.push(ALPHABET[(q[0] >> 2) as usize] as char);
        text.push(
            ALPHABET[((q[0] & 3) << 4 | q.get(1).copied().unwrap_or(0) >> 4) as usize] as char,
        );
        text.push(if q.len() > 1 {
            ALPHABET[((q[1] & 15) << 2 | q.get(2).copied().unwrap_or(0) >> 6) as usize] as char
        } else {
            '='
        });
        text.push(if q.len() > 2 {
            ALPHABET[(q[2] & 63) as usize] as char
        } else {
            '='
        });
    }
    text
}
fn matches_encoded(path: &Path, encoded: &str) -> Result<bool> {
    let Ok(mut file) = regular_file(path) else {
        return Ok(false);
    };
    for piece in encoded.as_bytes().chunks(8192) {
        let expected = decode_piece(piece)?;
        let mut actual = vec![0; expected.len()];
        if file.read_exact(&mut actual).is_err() || actual != expected {
            return Ok(false);
        }
    }
    let mut tail = [0];
    Ok(file.read(&mut tail)? == 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            let root =
                std::env::temp_dir().join(format!("pocket-media-test-{}", uuid::Uuid::new_v4()));
            fs::create_dir(&root).unwrap();
            Self(root)
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    fn native(bytes: &[u8]) -> Value {
        json!({"thread":{"id":"thread","turns":[{"id":"turn","items":[{"id":"tool","type":"mcpToolCall","result":{"content":[{"type":"text","text":"before"},{"type":"image","mimeType":"image/png","data":encode(bytes)},{"type":"text","text":"after"}]}}]}]}})
    }
    fn item(projected: &Value) -> &Value {
        &projected["thread"]["turns"][0]["items"][0]
    }
    fn owner() -> MediaOwner {
        MediaOwner {
            runtime_id: "runtime".into(),
            connection_id: "connection".into(),
            thread_id: "thread".into(),
            turn_id: "turn".into(),
            item_id: "tool".into(),
        }
    }
    #[test]
    fn mixed_order_opaque_chunks_restart_and_native_unchanged() {
        let fixture = Fixture::new();
        let store = MediaStore::new(&fixture.0).unwrap();
        let bytes = [
            b"\x89PNG\r\n\x1a\n".as_slice(),
            &vec![42; MEDIA_CHUNK_BYTES + 64],
        ]
        .concat();
        let original = native(&bytes);
        let projected = store.project(&original, "runtime", "connection", "thread");
        let blocks = &item(&projected)["toolOutputBlocks"];
        assert_eq!(blocks[0]["text"], "before");
        assert_eq!(blocks[1]["status"], "ready");
        assert_eq!(blocks[2]["text"], "after");
        assert!(!projected.to_string().contains(&encode(&bytes)));
        assert_eq!(original, native(&bytes));
        let id = blocks[1]["mediaId"].as_str().unwrap();
        assert!(opaque_id(id));
        assert_eq!(
            item(&store.project(&original, "runtime", "connection", "thread"))["toolOutputBlocks"]
                [1]["mediaId"],
            id
        );
        let first = store.read(&owner(), id, 0, MEDIA_CHUNK_BYTES).unwrap();
        assert_eq!(first["eof"], false);
        assert_eq!(
            decode_piece(first["data"].as_str().unwrap().as_bytes()).unwrap(),
            bytes[..MEDIA_CHUNK_BYTES]
        );
        drop(store);
        let restored = MediaStore::new(&fixture.0).unwrap();
        let tail = restored
            .read(&owner(), id, MEDIA_CHUNK_BYTES as u64, MEDIA_CHUNK_BYTES)
            .unwrap();
        assert_eq!(tail["eof"], true);
        let reprojection = restored.project(&projected, "runtime", "connection", "thread");
        assert_eq!(item(&reprojection)["toolOutputBlocks"][1]["mediaId"], id);
    }
    #[test]
    fn ownership_offsets_paths_and_reconnect_rejected() {
        let fixture = Fixture::new();
        let store = MediaStore::new(&fixture.0).unwrap();
        let projected = store.project(
            &native(b"\x89PNG\r\n\x1a\nxx"),
            "runtime",
            "connection",
            "thread",
        );
        let id = item(&projected)["toolOutputBlocks"][1]["mediaId"]
            .as_str()
            .unwrap();
        for field in 0..5 {
            let mut wrong = owner();
            match field {
                0 => wrong.runtime_id.push('x'),
                1 => wrong.connection_id.push('x'),
                2 => wrong.thread_id.push('x'),
                3 => wrong.turn_id.push('x'),
                _ => wrong.item_id.push('x'),
            };
            assert!(store.read(&wrong, id, 0, 8).is_err());
        }
        assert!(store.read(&owner(), "../secret", 0, 8).is_err());
        assert!(store.read(&owner(), id, 10, 8).is_err());
        assert!(store.read(&owner(), id, 0, MEDIA_CHUNK_BYTES + 1).is_err());
        fs::remove_file(fixture.0.join("tool-media").join(format!("{id}.blob"))).unwrap();
        assert_eq!(store.read(&owner(), id, 0, 8).unwrap()["status"], "expired");
    }
    #[test]
    fn rejects_size_mime_corruption_and_external_urls_without_payload_in_history() {
        let fixture = Fixture::new();
        let store = MediaStore::with_limits(&fixture.0, 16, 8).unwrap();
        for bytes in [
            b"garbage".as_slice(),
            b"\x89PNG\r\n\x1a\n1234567890".as_slice(),
        ] {
            let projected = store.project(&native(bytes), "runtime", "connection", "thread");
            assert_ne!(item(&projected)["toolOutputBlocks"][1]["status"], "ready");
            assert!(!projected.to_string().contains(&encode(bytes)));
        }
        for part in [
            json!({"type":"inputImage","imageUrl":"https://example/image.png"}),
            json!({"type":"image","mimeType":"image/svg+xml","data":"PRIVATE"}),
            json!({"type":"image","mimeType":"image/png","data":"iVBORw0KGgo=!!!!"}),
        ] {
            let native = json!({"params":{"threadId":"thread","turnId":"turn","item":{"id":"tool","type":"dynamicToolCall","contentItems":[part]}}});
            let projected = store.project(&native, "runtime", "connection", "thread");
            assert_ne!(
                projected["params"]["item"]["toolOutputBlocks"][0]["status"],
                "ready"
            );
            assert!(!projected.to_string().contains("PRIVATE"));
        }
    }
    #[test]
    fn dynamic_and_function_output_use_same_ordered_contract() {
        let fixture = Fixture::new();
        let store = MediaStore::new(&fixture.0).unwrap();
        for kind in ["dynamicToolCall", "functionCallOutput"] {
            let mut native = json!({"params":{"threadId":"thread","turnId":"turn","item":{"id":"tool","type":kind}}});
            let key = if kind == "dynamicToolCall" {
                "contentItems"
            } else {
                "output"
            };
            native["params"]["item"][key] = json!([{"type":"inputImage","imageUrl":format!("data:image/png;base64,{}",encode(b"\x89PNG\r\n\x1a\nxx"))}]);
            let projected = store.project(&native, "runtime", "connection", "thread");
            assert_eq!(
                projected["params"]["item"]["toolOutputBlocks"][0]["status"],
                "ready"
            );
        }
        for size in 0..64 {
            let bytes = vec![255; size];
            assert_eq!(decode_piece(encode(&bytes).as_bytes()).unwrap(), bytes);
        }
        assert!(decode_piece(b"AA=A").is_err());
        assert!(decode_piece(b"AB==").is_err());
    }
    #[test]
    fn item_count_bound_limits_metadata_and_blob_files() {
        let fixture = Fixture::new();
        let store = MediaStore::new(&fixture.0).unwrap();
        for index in 0..STORE_ITEMS + 4 {
            let mut record = native(b"\x89PNG\r\n\x1a\nxx");
            record["thread"]["turns"][0]["items"][0]["id"] = json!(format!("tool-{index}"));
            assert_eq!(
                item(&store.project(&record, "runtime", "connection", "thread"))["toolOutputBlocks"]
                    [1]["status"],
                "ready"
            );
        }
        assert_eq!(store.entries.lock().unwrap().len(), STORE_ITEMS);
        assert_eq!(
            fs::read_dir(fixture.0.join("tool-media")).unwrap().count(),
            STORE_ITEMS * 2
        );
    }
    #[test]
    fn item_result_events_extract_bytes_and_expired_entries_are_pruned() {
        let fixture = Fixture::new();
        let store = MediaStore::new(&fixture.0).unwrap();
        let native = json!({"method":"item/mcpToolCall/result","params":{"threadId":"thread","turnId":"turn","itemId":"tool","result":{"content":[{"type":"image","mimeType":"image/png","data":encode(b"\x89PNG\r\n\x1a\nxx")}]}}});
        let projected = store.project(&native, "runtime", "connection", "thread");
        let image = &projected["params"]["result"]["content"][0];
        assert_eq!(image["status"], "ready");
        let id = image["mediaId"].as_str().unwrap();
        store.entries.lock().unwrap().get_mut(id).unwrap().created = now() - TTL_SECONDS;
        assert_eq!(store.read(&owner(), id, 0, 8).unwrap()["status"], "expired");
        let mut entries = store.entries.lock().unwrap();
        store.prune(&mut entries, 0);
        assert!(entries.is_empty());
        assert!(
            !fixture
                .0
                .join("tool-media")
                .join(format!("{id}.blob"))
                .exists()
        );
    }

    #[test]
    fn user_images_keep_content_order_item_ownership_and_distinct_slots() {
        let fixture = Fixture::new();
        let store = MediaStore::new(&fixture.0).unwrap();
        let source = format!("data:image/png;base64,{}", encode(b"\x89PNG\r\n\x1a\nxx"));
        let native = json!({"thread":{"id":"thread","turns":[{"id":"turn","items":[
            {"type":"userMessage","id":"user","content":[
                {"type":"text","text":"before"},
                {"type":"image","url":source},
                {"type":"mention","name":"file","path":"relative.txt"},
                {"type":"input_image","image_url":source},
                {"type":"localImage","path":"C:/missing.png"},
                {"type":"text","text":"after"}
            ]},
            {"type":"dynamicToolCall","id":"tool","contentItems":[
                {"type":"image","url":source},{"type":"image","url":source}
            ]}
        ]}]}});
        let projected = store.project(&native, "runtime", "connection", "thread");
        let content = &item(&projected)["content"];
        assert_eq!(content.as_array().unwrap().len(), 6);
        assert_eq!(content[0]["text"], "before");
        assert_eq!(content[2]["type"], "mention");
        assert_eq!(content[5]["text"], "after");
        for slot in [1, 3] {
            assert_eq!(content[slot]["status"], "ready");
            assert_eq!(content[slot]["itemId"], "user");
            assert_eq!(content[slot]["contentPart"], slot);
        }
        assert_ne!(content[1]["mediaId"], content[3]["mediaId"]);
        assert_eq!(content[4]["status"], "expired");
        assert!(content[4].get("path").is_none());
        let tools = &projected["thread"]["turns"][0]["items"][1]["toolOutputBlocks"];
        assert_eq!(tools[0]["status"], "ready");
        assert_ne!(tools[0]["mediaId"], tools[1]["mediaId"]);
        assert_ne!(tools[0]["mediaId"], content[1]["mediaId"]);
        let mut user = owner();
        user.item_id = "user".into();
        let id = content[1]["mediaId"].as_str().unwrap();
        assert_eq!(store.read(&user, id, 0, 8).unwrap()["status"], "ready");
        assert!(store.read(&owner(), id, 0, 8).is_err());
        assert!(!projected.to_string().contains("base64"));
        assert!(native["thread"]["turns"][0]["items"][0]["content"][1]["url"].is_string());
        assert_eq!(
            store.project(&projected, "runtime", "connection", "thread"),
            projected
        );
    }

    #[test]
    fn result_event_images_have_distinct_content_slots() {
        let fixture = Fixture::new();
        let store = MediaStore::new(&fixture.0).unwrap();
        let part =
            json!({"type":"image","mimeType":"image/png","data":encode(b"\x89PNG\r\n\x1a\nxx")});
        let event = json!({"params":{"threadId":"thread","turnId":"turn","itemId":"tool","result":{"content":[part,part]}}});
        let projected = store.project(&event, "runtime", "connection", "thread");
        let parts = &projected["params"]["result"]["content"];
        assert_eq!(parts[0]["status"], "ready");
        assert_ne!(parts[0]["mediaId"], parts[1]["mediaId"]);
    }
}
