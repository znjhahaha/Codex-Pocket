//! Recovery from a path returned by native thread/read. Never construct this proof from phone JSON.
use super::MediaOwner;
use anyhow::{Result, bail};
use serde::{
    Deserialize,
    de::{self, DeserializeSeed, IgnoredAny, MapAccess, SeqAccess, Visitor},
};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    cell::Cell,
    collections::{BTreeMap, HashSet},
    fmt,
    fs::{self, File, OpenOptions},
    io::{self, BufRead, BufReader, Read, Seek, SeekFrom},
    path::{Component, Path, PathBuf},
    rc::Rc,
    sync::{Arc, Mutex},
};

const MAX_SCAN_BYTES: u64 = 512 * 1024 * 1024;
const MAX_RECORDS: usize = 100_000;
const MAX_PARTS: usize = 4096;
const MAX_TARGETS: usize = 256;
const MAX_SELECTED_BYTES: usize =
    (super::implementation::MAX_MEDIA_BYTES as usize / 3 * 4) + 16 * 1024;

#[derive(Clone)]
struct Target {
    owner: MediaOwner,
    slot: usize,
    image_index: usize,
    image_count: usize,
}

/// A short-lived proof from a native thread/read response, scoped to its connection generation.
/// Parent must perform the broker's ownership/generation checks before constructing or using it.
/// There is deliberately no history-path parameter on recover or on the phone media-read API.
#[derive(Clone)]
pub struct NativeHistory {
    path: PathBuf,
    targets: Arc<Vec<Target>>,
    scan_bytes: u64,
    record_limit: usize,
    selected_bytes: usize,
    cache: Arc<Mutex<Option<HistoryIndex>>>,
    #[cfg(test)]
    pub(super) scan_builds: Arc<std::sync::atomic::AtomicUsize>,
}
struct HistoryIndex {
    file: File,
    complete_end: u64,
    prefix_sha256: [u8; 32],
    records: BTreeMap<String, IndexedItem>,
}
struct IndexedItem {
    start: u64,
    end: u64,
    images: Option<Vec<usize>>,
}
impl NativeHistory {
    pub fn from_native_thread(thread: &Value, runtime: &str, connection: &str) -> Result<Self> {
        let id = thread["id"]
            .as_str()
            .filter(|s| valid_id(s))
            .ok_or_else(|| anyhow::anyhow!("Native history identity unavailable"))?;
        if !valid_id(runtime) || !valid_id(connection) {
            bail!("Invalid history generation");
        }
        let path = PathBuf::from(
            thread["path"]
                .as_str()
                .ok_or_else(|| anyhow::anyhow!("Native history path unavailable"))?,
        );
        if !path.is_absolute()
            || path
                .components()
                .any(|c| matches!(c, Component::ParentDir | Component::CurDir))
        {
            bail!("Native history path must be absolute");
        }
        let turns = thread["turns"]
            .as_array()
            .ok_or_else(|| anyhow::anyhow!("Native turns unavailable"))?;
        let mut identities = HashSet::new();
        let mut user_ids = HashSet::new();
        let mut targets = Vec::new();
        let mut items_seen = 0;
        for turn in turns {
            let turn_id = turn["id"]
                .as_str()
                .filter(|s| valid_id(s))
                .ok_or_else(|| anyhow::anyhow!("Native turn identity unavailable"))?;
            if !identities.insert(turn_id) || identities.len() > MAX_PARTS {
                bail!("Ambiguous or excessive native turns");
            }
            let items = turn["items"]
                .as_array()
                .ok_or_else(|| anyhow::anyhow!("Native items unavailable"))?;
            for item in items {
                items_seen += 1;
                if items_seen > MAX_RECORDS {
                    bail!("Native history item limit");
                }
                if item["type"] != "userMessage" {
                    continue;
                }
                let item_id = item["id"]
                    .as_str()
                    .filter(|s| valid_id(s))
                    .ok_or_else(|| anyhow::anyhow!("Native user identity unavailable"))?;
                if !user_ids.insert(item_id) {
                    bail!("Ambiguous native user identity");
                }
                let parts = item["content"]
                    .as_array()
                    .ok_or_else(|| anyhow::anyhow!("Native user content unavailable"))?;
                if parts.len() > MAX_PARTS {
                    bail!("Native content part limit");
                }
                let image_count = parts
                    .iter()
                    .filter(|p| is_image(p["type"].as_str().unwrap_or("")))
                    .count();
                let mut image_index = 0;
                for (slot, part) in parts.iter().enumerate() {
                    if !is_image(part["type"].as_str().unwrap_or("")) {
                        continue;
                    }
                    // Recover only local/stripped native images, never external URLs or file IDs.
                    if recoverable(part) {
                        if targets.len() >= MAX_TARGETS {
                            bail!("History recovery target limit");
                        }
                        targets.push(Target {
                            owner: MediaOwner {
                                runtime_id: runtime.into(),
                                connection_id: connection.into(),
                                thread_id: id.into(),
                                turn_id: turn_id.into(),
                                item_id: item_id.into(),
                            },
                            slot,
                            image_index,
                            image_count,
                        });
                    }
                    image_index += 1;
                }
            }
        }
        Ok(Self {
            path,
            targets: Arc::new(targets),
            scan_bytes: MAX_SCAN_BYTES,
            record_limit: MAX_RECORDS,
            selected_bytes: MAX_SELECTED_BYTES,
            cache: Arc::new(Mutex::new(None)),
            #[cfg(test)]
            scan_builds: Arc::new(std::sync::atomic::AtomicUsize::new(0)),
        })
    }
    pub fn can_recover(&self, owner: &MediaOwner, content_part: usize) -> bool {
        self.targets
            .iter()
            .any(|t| t.owner == *owner && t.slot == content_part)
    }
    pub(super) fn same_proof(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.cache, &other.cache)
    }
    pub fn recover(&self, owner: &MediaOwner, content_part: usize) -> Result<Value> {
        let target = self
            .targets
            .iter()
            .find(|t| t.owner == *owner && t.slot == content_part)
            .ok_or_else(|| anyhow::anyhow!("History content part was not proven"))?;
        // Keep parser/IO details (which can contain native text or paths) out of caller errors.
        self.recover_target(target)
            .map_err(|_| anyhow::anyhow!("Native history image unavailable or invalid"))
    }
    fn recover_target(&self, target: &Target) -> Result<Value> {
        let mut shared = self
            .cache
            .lock()
            .map_err(|_| anyhow::anyhow!("History index unavailable"))?;
        if let Some(index) = shared.as_ref() {
            let current = index.file.metadata()?;
            if current.len() < index.complete_end
                || prefix_digest(&index.file, index.complete_end)? != index.prefix_sha256
                || (!index.records.contains_key(&target.owner.item_id) && current.len() > index.complete_end)
            {
                *shared = None;
            }
        }
        if shared.is_none() {
            *shared = Some(self.build_index(&target.owner)?);
        }
        let index = shared.as_mut().unwrap();
        if index.complete_end > self.scan_bytes {
            bail!("History scan byte limit");
        }
        let record = index
            .records
            .get_mut(&target.owner.item_id)
            .ok_or_else(|| anyhow::anyhow!("Exact history user item missing"))?;
        let budget = Rc::new(Cell::new(usize::MAX));
        let mut reader = BoundedReader {
            inner: BufReader::new(index.file.try_clone()?),
            position: 0,
            end: index.complete_end,
            budget: budget.clone(),
        };
        if record.images.is_none() {
            reader.seek_to(record.start, record.end)?;
            let skeleton = parse(&mut reader, &budget, Node::RecordContent)?;
            let parts = skeleton["payload"]["content"]
                .as_array()
                .ok_or_else(|| anyhow::anyhow!("History content missing"))?;
            let images: Vec<_> = parts
                .iter()
                .enumerate()
                .filter(|(_, p)| is_image(p["type"].as_str().unwrap_or("")))
                .map(|(index, _)| index)
                .collect();
            if images.len() != target.image_count {
                bail!("History image mapping mismatch");
            }
            record.images = Some(images);
        }
        let image_part = *record
            .images
            .as_ref()
            .unwrap()
            .get(target.image_index)
            .ok_or_else(|| anyhow::anyhow!("History image missing"))?;
        reader.seek_to(record.start, record.end)?;
        let mut selected = parse(
            &mut reader,
            &budget,
            Node::RecordSelect(image_part, self.selected_bytes),
        )?;
        let part = selected["payload"]["content"].take();
        let source = part
            .get("image_url")
            .or_else(|| part.get("imageUrl"))
            .or_else(|| part.get("url"));
        if !source
            .and_then(Value::as_str)
            .is_some_and(|s| s.starts_with("data:image/") && s.contains(";base64,"))
        {
            bail!("History has no embedded image");
        }
        // Native history is append-only while a turn is running. Verify the exact
        // indexed prefix, without treating unrelated appended events as corruption.
        if prefix_digest(&index.file, index.complete_end)? != index.prefix_sha256 {
            *shared = None;
            bail!("History changed during recovery");
        }
        Ok(part)
    }
    fn build_index(&self, owner: &MediaOwner) -> Result<HistoryIndex> {
        let file = open_history(&self.path)?;
        let original = file.metadata()?;
        if original.len() == 0 || original.len() > self.scan_bytes {
            bail!("History scan byte limit");
        }
        let length = complete_prefix_end(&file, original.len())?;
        let prefix_sha256 = prefix_digest(&file, length)?;
        #[cfg(test)]
        self.scan_builds
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let budget = Rc::new(Cell::new(usize::MAX));
        let mut reader = BoundedReader {
            inner: BufReader::new(file),
            position: 0,
            end: length,
            budget: budget.clone(),
        };
        let mut records = 0;
        let mut active_turn = String::new();
        let mut found = BTreeMap::new();
        // No line or record buffer: skip strings/objects using IgnoredAny. Only short IDs are retained.
        while reader.skip_whitespace()? {
            if records >= self.record_limit {
                bail!("History record limit");
            }
            let start = reader.position;
            let record = parse(&mut reader, &budget, Node::Record)?;
            let end = reader.position;
            if records == 0 {
                if record["type"] != "session_meta"
                    || record["payload"]["id"] != owner.thread_id
                    || record["payload"]
                        .get("session_id")
                        .is_some_and(|id| !id.is_null() && id != &json!(owner.thread_id))
                {
                    bail!("History session ownership mismatch");
                }
            } else if record["type"] == "session_meta" {
                bail!("Ambiguous history session");
            }
            if record["type"] == "turn_context"
                || (record["type"] == "event_msg" && record["payload"]["type"] == "task_started")
            {
                active_turn = record["payload"]["turn_id"]
                    .as_str()
                    .filter(|s| valid_id(s))
                    .ok_or_else(|| anyhow::anyhow!("History turn unavailable"))?
                    .into();
            }
            if let Some(target) = record["payload"]["id"]
                .as_str()
                .and_then(|id| self.targets.iter().find(|t| t.owner.item_id == id))
            {
                if found.contains_key(&target.owner.item_id)
                    || record["type"] != "response_item"
                    || record["payload"]["type"] != "message"
                    || record["payload"]["role"] != "user"
                    || active_turn != target.owner.turn_id
                {
                    bail!("History user identity mismatch");
                }
                found.insert(
                    target.owner.item_id.clone(),
                    IndexedItem {
                        start,
                        end,
                        images: None,
                    },
                );
            }
            records += 1;
        }
        if prefix_digest(reader.inner.get_ref(), length)? != prefix_sha256 {
            bail!("History changed during recovery");
        }
        Ok(HistoryIndex {
            file: reader.inner.into_inner(),
            complete_end: length,
            prefix_sha256,
            records: found,
        })
    }
}

// JSONL writers may be between writes when a phone opens an image. Only parse
// complete records. All selected records still require exact session/turn/item
// identity; future/incomplete records cannot supply ownership evidence.
fn complete_prefix_end(file: &File, length: u64) -> Result<u64> {
    let mut file = file.try_clone()?;
    let mut end = length;
    let mut block = [0u8; 8192];
    while end > 0 {
        let start = end.saturating_sub(block.len() as u64);
        let count = (end - start) as usize;
        file.seek(SeekFrom::Start(start))?;
        file.read_exact(&mut block[..count])?;
        if let Some(index) = block[..count].iter().rposition(|b| *b == b'\n') {
            file.seek(SeekFrom::Start(0))?;
            return Ok(start + index as u64 + 1);
        }
        end = start;
    }
    bail!("No complete history record")
}
fn prefix_digest(file: &File, length: u64) -> Result<[u8; 32]> {
    let mut file = file.try_clone()?;
    file.seek(SeekFrom::Start(0))?;
    let mut remaining = length;
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 65536];
    while remaining > 0 {
        let count = remaining.min(buffer.len() as u64) as usize;
        file.read_exact(&mut buffer[..count])?;
        digest.update(&buffer[..count]); remaining -= count as u64;
    }
    file.seek(SeekFrom::Start(0))?;
    Ok(digest.finalize().into())
}

fn valid_id(s: &str) -> bool {
    !s.is_empty() && s.len() <= 512 && !s.chars().any(char::is_control)
}
fn is_image(kind: &str) -> bool {
    matches!(
        kind,
        "image" | "inputImage" | "input_image" | "imageUrl" | "image_url" | "localImage"
    )
}
fn recoverable(part: &Value) -> bool {
    part["type"] == "localImage"
        || (part.get("fileId").is_none()
            && ["url", "imageUrl", "image_url", "data"].iter().all(|key| {
                part.get(*key)
                    .is_none_or(|v| v.is_null() || v.as_str() == Some(""))
            }))
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
fn open_history(path: &Path) -> Result<File> {
    for ancestor in path.ancestors() {
        let metadata = fs::symlink_metadata(ancestor)?;
        if linked(&metadata)
            || (ancestor != path && !metadata.is_dir())
            || (ancestor == path && !metadata.is_file())
        {
            bail!("History is not a regular native file");
        }
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        use windows_sys::Win32::Storage::FileSystem::{
            FILE_FLAG_OPEN_REPARSE_POINT, FILE_SHARE_DELETE, FILE_SHARE_READ, FILE_SHARE_WRITE,
        };
        // Keep the proven file handle across a native archive rename without following links.
        options
            .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT);
    }
    #[cfg(target_os = "linux")]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(0x20000 | 0x800);
    }
    let file = options.open(path)?;
    if !file.metadata()?.is_file() || linked(&file.metadata()?) {
        bail!("Invalid history file");
    }
    crate::storage::verify_physical_file(path, &file)?;
    Ok(file)
}

struct BoundedReader {
    inner: BufReader<File>,
    position: u64,
    end: u64,
    budget: Rc<Cell<usize>>,
}
impl Read for BoundedReader {
    fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
        if self.budget.get() == 0 {
            return Err(io::Error::other("History selected value byte limit"));
        }
        let count = bytes
            .len()
            .min(self.budget.get())
            .min((self.end - self.position) as usize);
        let read = self.inner.read(&mut bytes[..count])?;
        self.position += read as u64;
        self.budget.set(self.budget.get() - read);
        Ok(read)
    }
}
impl BoundedReader {
    fn skip_whitespace(&mut self) -> io::Result<bool> {
        while self.position < self.end {
            let bytes = self.inner.fill_buf()?;
            if bytes.is_empty() {
                return Err(io::Error::other("Truncated history"));
            }
            let available = bytes.len().min((self.end - self.position) as usize);
            let count = bytes[..available]
                .iter()
                .take_while(|b| matches!(b, b' ' | b'\r' | b'\n' | b'\t'))
                .count();
            if count == 0 {
                return Ok(true);
            }
            self.inner.consume(count);
            self.position += count as u64;
        }
        Ok(false)
    }
    fn seek_to(&mut self, start: u64, end: u64) -> io::Result<()> {
        self.inner.seek(SeekFrom::Start(start))?;
        self.position = start;
        self.end = end;
        Ok(())
    }
}

#[derive(Clone, Copy)]
enum Node {
    Record,
    Payload,
    RecordContent,
    PayloadContent,
    Content,
    Part,
    RecordSelect(usize, usize),
    PayloadSelect(usize, usize),
    SelectContent(usize, usize),
}
struct Skeleton {
    node: Node,
    budget: Rc<Cell<usize>>,
}
fn parse(reader: &mut BoundedReader, budget: &Rc<Cell<usize>>, node: Node) -> Result<Value> {
    let mut deserializer = serde_json::Deserializer::from_reader(reader);
    Ok(Skeleton {
        node,
        budget: budget.clone(),
    }
    .deserialize(&mut deserializer)?)
}
impl<'de> DeserializeSeed<'de> for Skeleton {
    type Value = Value;
    fn deserialize<D: de::Deserializer<'de>>(self, d: D) -> std::result::Result<Value, D::Error> {
        if matches!(self.node, Node::Content | Node::SelectContent(..)) {
            d.deserialize_seq(self)
        } else {
            d.deserialize_map(self)
        }
    }
}
struct LimitedString(Rc<Cell<usize>>);
impl<'de> DeserializeSeed<'de> for LimitedString {
    type Value = String;
    fn deserialize<D: de::Deserializer<'de>>(self, d: D) -> std::result::Result<String, D::Error> {
        let old = self.0.get();
        let limit = old.min(8192);
        self.0.set(limit);
        let value = String::deserialize(d);
        let used = limit - self.0.get();
        self.0.set(old - used);
        let value = value?;
        if value.len() > 512 {
            return Err(de::Error::custom("History identifier limit"));
        }
        Ok(value)
    }
}
struct SelectedValue {
    budget: Rc<Cell<usize>>,
    limit: usize,
}
impl<'de> DeserializeSeed<'de> for SelectedValue {
    type Value = Value;
    fn deserialize<D: de::Deserializer<'de>>(self, d: D) -> std::result::Result<Value, D::Error> {
        let old = self.budget.get();
        let limit = old.min(self.limit);
        self.budget.set(limit);
        let result = d.deserialize_map(SelectedImage(self.budget.clone()));
        let used = limit - self.budget.get();
        self.budget.set(old - used);
        result
    }
}
struct SelectedImage(Rc<Cell<usize>>);
impl<'de> Visitor<'de> for SelectedImage {
    type Value = Value;
    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("a native embedded image part")
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> std::result::Result<Value, A::Error> {
        let mut fields = serde_json::Map::new();
        let mut keys = HashSet::new();
        while let Some(key) = map.next_key_seed(LimitedString(self.0.clone()))? {
            if !keys.insert(key.clone()) || keys.len() > MAX_PARTS {
                return Err(de::Error::custom("Duplicate or excessive image fields"));
            }
            match key.as_str() {
                "image_url" | "imageUrl" | "url" | "data" => {
                    fields.insert(key, Value::String(map.next_value::<String>()?));
                }
                "type" | "mimeType" | "mime" => {
                    fields.insert(
                        key,
                        Value::String(map.next_value_seed(LimitedString(self.0.clone()))?),
                    );
                }
                _ => {
                    map.next_value::<IgnoredAny>()?;
                }
            }
        }
        Ok(Value::Object(fields))
    }
}
impl<'de> Visitor<'de> for Skeleton {
    type Value = Value;
    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("native history object or content array")
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> std::result::Result<Value, A::Error> {
        let mut result = serde_json::Map::new();
        let mut keys = HashSet::new();
        while let Some(key) = map.next_key_seed(LimitedString(self.budget.clone()))? {
            if !keys.insert(key.clone()) || keys.len() > MAX_PARTS {
                return Err(de::Error::custom("Duplicate or excessive history fields"));
            }
            let child = match (self.node, key.as_str()) {
                (Node::Record, "payload") => Some(Node::Payload),
                (Node::RecordContent, "payload") => Some(Node::PayloadContent),
                (Node::RecordSelect(index, limit), "payload") => {
                    Some(Node::PayloadSelect(index, limit))
                }
                (Node::PayloadContent, "content") => Some(Node::Content),
                (Node::PayloadSelect(index, limit), "content") => {
                    Some(Node::SelectContent(index, limit))
                }
                _ => None,
            };
            let short = match self.node {
                Node::Record | Node::RecordContent | Node::RecordSelect(..) | Node::Part => {
                    key == "type"
                }
                Node::Payload => matches!(
                    key.as_str(),
                    "id" | "session_id" | "type" | "role" | "turn_id"
                ),
                _ => false,
            };
            if let Some(node) = child {
                result.insert(
                    key,
                    map.next_value_seed(Skeleton {
                        node,
                        budget: self.budget.clone(),
                    })?,
                );
            } else if short {
                result.insert(
                    key,
                    Value::String(map.next_value_seed(LimitedString(self.budget.clone()))?),
                );
            } else {
                map.next_value::<IgnoredAny>()?;
            }
        }
        Ok(Value::Object(result))
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> std::result::Result<Value, A::Error> {
        let mut parts = Vec::new();
        let mut selected = Value::Null;
        for index in 0..=MAX_PARTS {
            if index == MAX_PARTS {
                if seq.next_element::<IgnoredAny>()?.is_some() {
                    return Err(de::Error::custom("History content part limit"));
                }
                break;
            }
            match self.node {
                Node::SelectContent(target, limit) if index == target => {
                    let Some(value) = seq.next_element_seed(SelectedValue {
                        budget: self.budget.clone(),
                        limit,
                    })?
                    else {
                        break;
                    };
                    selected = value;
                }
                Node::SelectContent(..) => {
                    if seq.next_element::<IgnoredAny>()?.is_none() {
                        break;
                    }
                }
                _ => {
                    let Some(value) = seq.next_element_seed(Skeleton {
                        node: Node::Part,
                        budget: self.budget.clone(),
                    })?
                    else {
                        break;
                    };
                    parts.push(value);
                }
            }
        }
        Ok(if matches!(self.node, Node::SelectContent(..)) {
            selected
        } else {
            Value::Array(parts)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::{fs, io::Write, path::Path};

    fn owner() -> MediaOwner {
        MediaOwner {
            runtime_id: "runtime".into(),
            connection_id: "connection".into(),
            thread_id: "thread".into(),
            turn_id: "turn".into(),
            item_id: "user".into(),
        }
    }
    fn image() -> Value {
        json!({"type":"input_image","image_url":"data:image/png;base64,iVBORw0KGgp4eA=="})
    }
    fn second_image() -> Value {
        json!({"type":"input_image","image_url":"data:image/png;base64,iVBORw0KGgp5eQ=="})
    }
    fn native(path: &Path) -> Value {
        json!({"id":"thread","path":path,"turns":[{"id":"turn","items":[
            {"id":"user","type":"userMessage","content":[
                {"type":"text","text":"before"},
                {"type":"localImage","path":"C:/missing/one.png"},
                {"type":"text","text":"between"},
                {"type":"localImage","path":"C:/missing/two.png"}
            ]}
        ]}]})
    }
    fn records() -> Vec<Value> {
        vec![
            json!({"type":"session_meta","payload":{"id":"thread","session_id":"thread"}}),
            json!({"type":"turn_context","payload":{"turn_id":"turn"}}),
            json!({"type":"response_item","payload":{"type":"message","role":"user","id":"other","content":[image()]}}),
            json!({"type":"response_item","payload":{"type":"message","role":"user","id":"user","content":[
                {"type":"input_text","text":"markers"}, image(),
                {"type":"input_text","text":"more markers"}, {"type":"input_text","text":"text"}, second_image()
            ]}}),
        ]
    }
    fn write(path: &Path, records: &[Value]) {
        let mut file = fs::File::create(path).unwrap();
        for record in records {
            serde_json::to_writer(&mut file, record).unwrap();
            file.write_all(b"\n").unwrap();
        }
    }
    #[test]
    fn recovers_exact_user_image_ordinal_despite_history_text_markers() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("native.jsonl");
        write(&path, &records());
        let proof =
            NativeHistory::from_native_thread(&native(&path), "runtime", "connection").unwrap();
        assert!(proof.recover(&owner(), 1).unwrap() == image());
        assert!(proof.clone().recover(&owner(), 3).unwrap() == second_image());
        assert_eq!(
            proof.scan_builds.load(std::sync::atomic::Ordering::Relaxed),
            1
        );
        assert!(proof.recover(&owner(), 0).is_err());
        for field in 0..5 {
            let mut wrong = owner();
            match field {
                0 => wrong.runtime_id.push('x'),
                1 => wrong.connection_id.push('x'),
                2 => wrong.thread_id.push('x'),
                3 => wrong.turn_id.push('x'),
                _ => wrong.item_id.push('x'),
            }
            assert!(proof.recover(&wrong, 1).is_err());
        }
    }
    #[test]
    fn append_only_active_history_reuses_verified_prefix_despite_incomplete_tail() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("native.jsonl");
        write(&path, &records());
        let proof = NativeHistory::from_native_thread(&native(&path), "runtime", "connection").unwrap();
        assert_eq!(proof.recover(&owner(), 1).unwrap(), image());
        let mut writer = fs::OpenOptions::new().append(true).open(&path).unwrap();
        writer.write_all(b"{\"type\":\"event_msg\",\"payload\":").unwrap();
        assert_eq!(proof.recover(&owner(), 3).unwrap(), second_image());
        assert_eq!(proof.scan_builds.load(std::sync::atomic::Ordering::Relaxed), 1);
    }

    #[test]
    fn first_recovery_uses_only_complete_records_and_detects_prefix_rewrites() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("native.jsonl");
        write(&path, &records());
        fs::OpenOptions::new().append(true).open(&path).unwrap().write_all(b"{\"type\":").unwrap();
        let proof = NativeHistory::from_native_thread(&native(&path), "runtime", "connection").unwrap();
        assert_eq!(proof.recover(&owner(), 1).unwrap(), image());
        let mut changed = records(); changed[0]["payload"]["id"] = json!("foreign");
        write(&path, &changed);
        assert!(proof.recover(&owner(), 1).is_err());
    }
    #[test]
    fn unavailable_image_can_recover_after_native_writer_commits_its_record() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("native.jsonl");
        let data = records();
        write(&path, &data[..3]);
        let proof = NativeHistory::from_native_thread(&native(&path), "runtime", "connection").unwrap();
        assert!(proof.recover(&owner(), 1).is_err());
        let mut writer = fs::OpenOptions::new().append(true).open(&path).unwrap();
        serde_json::to_writer(&mut writer, &data[3]).unwrap(); writer.write_all(b"\n").unwrap();
        assert_eq!(proof.recover(&owner(), 1).unwrap(), image());
    }
    #[test]
    #[ignore = "Read-only local metadata probe; explicit fixture path required, no content printed"]
    fn supplied_native_image_history_probe() {
        let input = std::env::var("POCKET_MEDIA_PROBE").expect("Explicit probe metadata required");
        let thread: Value = serde_json::from_slice(&crate::storage::read_physical(Path::new(&input), 1_048_576).unwrap()).unwrap();
        let proof = NativeHistory::from_native_thread(&thread, "probe", "probe").unwrap();
        for target in proof.targets.iter() {
            let now = std::time::Instant::now();
            match proof.recover_target(target) {
                Ok(part) => println!("media_probe success=true elapsedMs={} embedded={}", now.elapsed().as_millis(), part["image_url"].as_str().is_some_and(|s|s.starts_with("data:image/"))),
                Err(e) => { println!("media_probe success=false elapsedMs={} error={e}", now.elapsed().as_millis()); panic!("Image recovery failed") }
            }
        }
    }
    #[test]
    fn rejects_foreign_session_turn_role_duplicate_item_and_image_count() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("native.jsonl");
        for failure in 0..7 {
            let mut data = records();
            match failure {
                0 => data[0]["payload"]["id"] = json!("foreign"),
                1 => data[0]["payload"]["session_id"] = json!("foreign"),
                2 => data[1]["payload"]["turn_id"] = json!("foreign"),
                3 => data[3]["payload"]["role"] = json!("assistant"),
                4 => data.push(data[3].clone()),
                5 => {
                    data[3]["payload"]["content"].as_array_mut().unwrap().pop();
                }
                _ => data.push(data[0].clone()),
            }
            write(&path, &data);
            let proof =
                NativeHistory::from_native_thread(&native(&path), "runtime", "connection").unwrap();
            assert!(proof.recover(&owner(), 1).is_err(), "case {failure}");
        }
    }
    #[test]
    fn missing_corrupt_or_relative_history_never_uses_local_image_path() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("missing.jsonl");
        let proof =
            NativeHistory::from_native_thread(&native(&path), "runtime", "connection").unwrap();
        assert!(proof.recover(&owner(), 1).is_err());
        for bytes in [
            b"not json".as_slice(),
            b"{\"type\":\"session_meta\"".as_slice(),
        ] {
            fs::write(&path, bytes).unwrap();
            assert!(proof.recover(&owner(), 1).is_err());
        }
        write(&path, &records());
        fs::OpenOptions::new()
            .append(true)
            .open(&path)
            .unwrap()
            .write_all(b"{broken\n")
            .unwrap();
        assert!(proof.recover(&owner(), 1).is_err());
        assert!(
            NativeHistory::from_native_thread(
                &native(Path::new("native.jsonl")),
                "runtime",
                "connection"
            )
            .is_err()
        );
    }
    #[test]
    fn duplicate_native_identity_and_external_image_are_not_recovery_targets() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("native.jsonl");
        write(&path, &records());
        let mut thread = native(&path);
        let duplicate = thread["turns"][0]["items"][0].clone();
        thread["turns"][0]["items"]
            .as_array_mut()
            .unwrap()
            .push(duplicate);
        assert!(NativeHistory::from_native_thread(&thread, "runtime", "connection").is_err());
        let mut thread = native(&path);
        thread["turns"][0]["items"][0]["content"][1] =
            json!({"type":"image","url":"https://external.invalid/image.png"});
        let proof = NativeHistory::from_native_thread(&thread, "runtime", "connection").unwrap();
        assert!(proof.recover(&owner(), 1).is_err());
    }
    #[test]
    fn recovery_enforces_scan_record_selected_value_and_identifier_bounds() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("native.jsonl");
        write(&path, &records());
        let mut proof =
            NativeHistory::from_native_thread(&native(&path), "runtime", "connection").unwrap();
        proof.scan_bytes = 16;
        assert!(proof.recover(&owner(), 1).is_err());
        proof.scan_bytes = MAX_SCAN_BYTES;
        proof.record_limit = 2;
        assert!(proof.recover(&owner(), 1).is_err());
        proof.record_limit = MAX_RECORDS;
        proof.selected_bytes = 32;
        assert!(proof.recover(&owner(), 1).is_err());
        proof.selected_bytes = MAX_SELECTED_BYTES;
        let mut data = records();
        data[2]["payload"]["id"] = json!("x".repeat(9000));
        write(&path, &data);
        assert!(proof.recover(&owner(), 1).is_err());
        let mut data = records();
        data[3]["payload"]["content"] =
            json!((0..MAX_PARTS + 1).map(|_| image()).collect::<Vec<_>>());
        write(&path, &data);
        assert!(proof.recover(&owner(), 1).is_err());
    }
    #[test]
    fn large_unrelated_payload_is_streamed_and_duplicate_fields_fail_closed() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("native.jsonl");
        let mut data = records();
        data[2]["payload"]["content"][0]["image_url"] = json!("x".repeat(2 * 1024 * 1024));
        write(&path, &data);
        let proof =
            NativeHistory::from_native_thread(&native(&path), "runtime", "connection").unwrap();
        assert!(proof.recover(&owner(), 1).unwrap() == image());
        fs::write(
            &path,
            b"{\"type\":\"session_meta\",\"payload\":{\"id\":\"thread\",\"id\":\"foreign\"}}\n",
        )
        .unwrap();
        assert!(proof.recover(&owner(), 1).is_err());
    }
    #[cfg(unix)]
    #[test]
    fn linked_history_files_and_parent_directories_are_rejected() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("native.jsonl");
        write(&path, &records());
        let link = root.path().join("link.jsonl");
        std::os::unix::fs::symlink(&path, &link).unwrap();
        let proof =
            NativeHistory::from_native_thread(&native(&link), "runtime", "connection").unwrap();
        assert!(proof.recover(&owner(), 1).is_err());
        let folder = root.path().join("folder");
        std::os::unix::fs::symlink(root.path(), &folder).unwrap();
        let proof = NativeHistory::from_native_thread(
            &native(&folder.join("native.jsonl")),
            "runtime",
            "connection",
        )
        .unwrap();
        assert!(proof.recover(&owner(), 1).is_err());
    }
    #[cfg(windows)]
    #[test]
    fn cached_history_handle_allows_archive_rename_and_keeps_native_file_identity() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("sessions/native.jsonl");
        fs::create_dir(path.parent().unwrap()).unwrap();
        write(&path, &records());
        let proof =
            NativeHistory::from_native_thread(&native(&path), "runtime", "connection").unwrap();
        assert!(proof.recover(&owner(), 1).unwrap() == image());
        let archived = root.path().join("archived_sessions/native.jsonl");
        fs::create_dir(archived.parent().unwrap()).unwrap();
        fs::rename(&path, &archived)
            .expect("Cached history must not prevent a native archive rename");
        let mut foreign = records();
        foreign[0]["payload"]["id"] = json!("foreign");
        write(&path, &foreign);
        assert!(proof.clone().recover(&owner(), 3).unwrap() == second_image());
        assert_eq!(
            proof.scan_builds.load(std::sync::atomic::Ordering::Relaxed),
            1
        );
    }
    #[test]
    #[ignore = "Explicit local evidence probe; no user body or image bytes are printed"]
    fn real_native_history_has_eight_recoverable_user_images() {
        let path = Path::new(
            "C:/Users/82206/.codex/sessions/2026/11/05/rollout-2026-11-05T18-51-41-01a1ab55-f97e-7733-95de-9e755978cd18.jsonl",
        );
        let thread = json!({"id":"01a1ab55-f97e-7733-95de-9e755978cd18","path":path,"turns":[{
        "id":"01a1ab56-0274-7570-944d-3c02774e48b9","items":[{
            "id":"msg_01a1ab56-09b9-7fc1-a9c0-7be67c02ba2b","type":"userMessage",
            "content":(0..8).map(|_| json!({"type":"localImage","path":"C:/missing.png"})).collect::<Vec<_>>()
        }]}]});
        let proof = NativeHistory::from_native_thread(&thread, "runtime", "connection").unwrap();
        let mut selected = owner();
        selected.thread_id = thread["id"].as_str().unwrap().into();
        selected.turn_id = thread["turns"][0]["id"].as_str().unwrap().into();
        selected.item_id = thread["turns"][0]["items"][0]["id"]
            .as_str()
            .unwrap()
            .into();
        for slot in 0..8 {
            let recovered = proof.recover(&selected, slot).unwrap();
            assert!(
                recovered["image_url"]
                    .as_str()
                    .is_some_and(|s| s.starts_with("data:image/"))
            );
        }
        let root = tempfile::tempdir().unwrap();
        let store = super::super::MediaStore::new(root.path()).unwrap();
        let view = store.project_with_history(
            &json!({"thread":thread}),
            "runtime",
            "connection",
            &selected.thread_id,
            &proof,
        );
        for part in view["thread"]["turns"][0]["items"][0]["content"]
            .as_array()
            .unwrap()
        {
            assert_eq!(part["status"], "recoverable");
            let chunk = store
                .read(
                    &selected,
                    part["mediaId"].as_str().unwrap(),
                    0,
                    super::super::implementation::MEDIA_CHUNK_BYTES,
                )
                .unwrap();
            assert_eq!(chunk["status"], "ready");
        }
    }
}
