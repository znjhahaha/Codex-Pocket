use super::*;
use std::collections::BTreeMap;

/// Legacy desktop and CLI rename events are appended here. A complete EOF JSON
/// record is valid even before its final newline has been written.
pub(super) fn read(home: &Path) -> Result<BTreeMap<String, String>> {
    let path = home.join("session_index.jsonl");
    if !path.exists() { return Ok(BTreeMap::new()); }
    let bytes = crate::storage::read_physical(&path, 16 * 1024 * 1024)?;
    let mut names = BTreeMap::new();
    for line in bytes.split_inclusive(|b| *b == b'\n') {
        let Ok(record) = serde_json::from_slice::<Value>(line) else { continue };
        let (Some(id), Some(name)) = (record["id"].as_str(), record["thread_name"].as_str()) else { continue };
        let name = name.trim();
        if !id.is_empty() && id.len() <= 128 && !name.is_empty() && name.chars().count() <= 512 && !name.chars().any(char::is_control) {
            names.insert(id.to_owned(), name.to_owned());
        }
    }
    Ok(names)
}

pub(super) fn apply(meta: &mut Value, names: &BTreeMap<String, String>, sqlite_name: bool) {
    if sqlite_name && meta["historyMode"] == "paginated" { return; }
    if let Some(name) = meta["id"].as_str().and_then(|id| names.get(id)) {
        meta["name"] = json!(name);
    }
}
