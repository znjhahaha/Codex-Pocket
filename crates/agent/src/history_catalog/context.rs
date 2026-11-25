use anyhow::{Result, ensure};
use serde_json::{Value, json};
use std::{collections::{BTreeMap, BTreeSet, VecDeque}, path::Path};

#[derive(Clone, Copy, PartialEq, Eq)]
enum Source { Environment, Authored, Other }

fn environment_text(item: &Value) -> Option<String> {
    let parts = item["content"].as_array()?;
    if parts.is_empty() || parts.iter().any(|part| !matches!(part["type"].as_str(), Some("text" | "input_text"))) { return None; }
    let text = parts.iter().map(|part| part["text"].as_str()).collect::<Option<Vec<_>>>()?.join("\n");
    (text.trim().starts_with("<environment_context>") && text.trim().ends_with("</environment_context>")).then_some(text)
}

/// These fields are emitted by the native harness, not parsed from message text.
fn source(item: &Value) -> Option<Source> {
    let metadata = &item["internal_chat_message_metadata_passthrough"];
    let kinds = metadata["content_item_kinds"].as_array();
    let sources = metadata["content_item_metadata"].as_array();
    if kinds.is_some_and(|parts| parts.iter().any(|p| p.as_str().is_some_and(|s| s.starts_with("user."))))
        || sources.is_some_and(|parts| parts.iter().any(|p| p["provenance"]["type"] == "user")) {
        return Some(Source::Authored);
    }
    let count = item["content"].as_array()?.len();
    let environment = kinds.is_some_and(|parts| parts.len() == count && parts.iter().all(|p| p == "environments.environment_context"));
    let harness = sources.is_some_and(|parts| parts.len() == count && parts.iter().all(|p|
        (p["provenance"]["type"] == "harness" && p["provenance"]["hook"] != true)
        || (p["provenance"].is_null() && p["harness_injected"] == true && p["source_tool_namespace"].is_null())));
    if environment && sources.is_none() || harness { Some(Source::Environment) }
    else if kinds.is_some() || sources.is_some() { Some(Source::Other) }
    else { None }
}

struct LiveSource { runtime: String, connection: String, thread: String, turn: String, text: String, source: Source }

/// Raw response provenance precedes lifecycle items, including before the first
/// rollout is materialized. Keep only bounded, connection/turn-scoped candidates.
#[derive(Default)]
pub(crate) struct LiveContexts(VecDeque<LiveSource>);
impl LiveContexts {
    /// Returns true only when a candidate needs the persisted provenance lookup.
    pub(crate) fn project(&mut self, runtime: &str, connection: &str, event: &mut Value) -> bool {
        let p = &event["params"];
        let (Some(thread), Some(turn)) = (p["threadId"].as_str(), p["turnId"].as_str()) else { return false; };
        if event["method"] == "rawResponseItem/completed" && p["item"]["type"] == "message" && p["item"]["role"] == "user" {
            if let Some(text) = environment_text(&p["item"]).filter(|t| t.len() <= 65536) {
                self.0.retain(|s| !(s.runtime == runtime && s.connection == connection && s.thread == thread && s.turn == turn && s.text == text));
                self.0.push_back(LiveSource { runtime: runtime.into(), connection: connection.into(), thread: thread.into(), turn: turn.into(), text, source: source(&p["item"]).unwrap_or(Source::Other) });
                while self.0.len() > 128 { self.0.pop_front(); }
            }
            return false;
        }
        if !matches!(event["method"].as_str(), Some("item/started" | "item/completed")) { return false; }
        let Some(text) = text(&p["item"]) else { return false; };
        let found = self.0.iter().rev().find(|s| s.runtime == runtime && s.connection == connection && s.thread == thread && s.turn == turn && s.text == text);
        if let Some(found) = found {
            set_source(&mut event["params"]["item"], found.source == Source::Environment);
            false
        } else { true }
    }
}

fn set_source(item: &mut Value, hidden: bool) {
    if hidden { item["pocketContextSource"] = json!("runtime-environment"); }
    else if item["pocketContextSource"] == "runtime-environment" { item.as_object_mut().unwrap().remove("pocketContextSource"); }
}

fn text(item: &Value) -> Option<String> {
    if item["type"] != "userMessage" { return None; }
    environment_text(item)
}
fn visit(value: &mut Value, f: &mut impl FnMut(&mut Value)) {
    if value["type"] == "userMessage" { f(value); return; }
    if let Some(rows) = value.as_array_mut() { for row in rows { visit(row, f); } }
    else if let Some(object) = value.as_object_mut() {
        for name in ["thread", "turns", "items", "data", "params", "item"] {
            if let Some(child) = object.get_mut(name) { visit(child, f); }
        }
    }
}
/// Mark only response-item context with no matching authored user event. The
/// source stays on disk, unchanged, and arbitrary user text remains displayable.
pub(super) fn mark(home: &Path, thread: &str, result: &mut Value) -> Result<()> {
    let mut targets = BTreeSet::new();
    visit(result, &mut |item| { if let Some(t) = text(item) { targets.insert(t); } });
    if targets.is_empty() { return Ok(()); }
    let path = super::rollout_path(home, thread)?;
    let bytes = crate::storage::read_physical(&path, 64 * 1024 * 1024)?;
    let mut lines = bytes.split(|c| *c == b'\n');
    let header: Value = serde_json::from_slice(lines.next().unwrap_or_default())?;
    ensure!(header["type"] == "session_meta" && header["payload"]["id"] == thread, "Context source identity mismatch");
    let mut authored = BTreeSet::new();
    let mut generated = BTreeSet::new();
    let mut exact = BTreeMap::new();
    let live = matches!(result["method"].as_str(), Some("item/started" | "item/completed"));
    let live_turn = result["params"]["turnId"].as_str();
    for line in lines {
        let Ok(record) = serde_json::from_slice::<Value>(line) else { continue; };
        let payload = &record["payload"];
        if record["type"] == "event_msg" && payload["type"] == "user_message" {
            if let Some(t) = payload["message"].as_str() { authored.insert(t.to_owned()); }
        } else if record["type"] == "response_item" && payload["type"] == "message" && payload["role"] == "user" {
            // A resumed thread may deliver a new user item before its rollout
            // write. Identical text in an older turn is not its provenance.
            if live && (live_turn.is_none() || payload["internal_chat_message_metadata_passthrough"]["turn_id"].as_str() != live_turn) { continue; }
            let Some(text) = environment_text(payload).filter(|t| targets.contains(t)) else { continue; };
            let provenance = source(payload);
            if let (Some(id), Some(provenance)) = (payload["id"].as_str(), provenance) { exact.insert(id.to_owned(), provenance); }
            match provenance {
                Some(Source::Environment) => { generated.insert(text); }
                Some(Source::Authored | Source::Other) => { authored.insert(text); }
                None if !live => { generated.insert(text); }
                None => {}
            }
        }
    }
    visit(result, &mut |item| {
        if let Some(text) = text(item) {
            let hidden = item["id"].as_str().and_then(|id| exact.get(id)).map(|s| *s == Source::Environment)
                .unwrap_or_else(|| generated.contains(&text) && !authored.contains(&text));
            set_source(item, hidden);
        }
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(source: &str) -> tempfile::TempDir {
        let home = tempfile::tempdir().unwrap();
        let sessions = home.path().join("sessions");
        std::fs::create_dir(&sessions).unwrap();
        let path = sessions.join("t.jsonl");
        std::fs::write(&path, source).unwrap();
        let db = rusqlite::Connection::open(home.path().join("state_5.sqlite")).unwrap();
        db.execute_batch("CREATE TABLE threads(id TEXT,source TEXT,title TEXT,preview TEXT,cwd TEXT,updated_at INTEGER,archived INTEGER,rollout_path TEXT)").unwrap();
        db.execute("INSERT INTO threads VALUES('t','cli','','','/project',1,0,?1)", [path.to_str().unwrap()]).unwrap();
        home
    }

    #[test]
    fn internal_context_live_envelopes_use_provenance_before_legacy_user_event() {
        let generated = "<environment_context><cwd>/project</cwd></environment_context>";
        let authored = "<environment_context>I typed these tags</environment_context>";
        let source = [json!({"type":"session_meta","payload":{"id":"t"}}),
            json!({"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":generated}],
                "internal_chat_message_metadata_passthrough":{"turn_id":"u","content_item_kinds":["environments.environment_context"],"content_item_metadata":[{"provenance":{"type":"harness"}}]}}}),
            json!({"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":authored}],
                "internal_chat_message_metadata_passthrough":{"turn_id":"u","content_item_kinds":["user.text"],"content_item_metadata":[{"provenance":{"type":"user"}}]}}})]
            .iter().map(Value::to_string).collect::<Vec<_>>().join("\n") + "\n";
        let home = fixture(&source);
        for method in ["item/started", "item/completed"] {
            for (text, hidden) in [(generated, true), (authored, false)] {
                let mut event = json!({"method":method,"params":{"threadId":"t","turnId":"u","item":{"id":"i","type":"userMessage","content":[{"type":"text","text":text}]}}});
                mark(home.path(), "t", &mut event).unwrap();
                assert_eq!(event["params"]["item"]["pocketContextSource"] == "runtime-environment", hidden, "{method} {text}");
                let mut reopened = json!({"thread":{"turns":[{"id":"u","items":[{"id":"i","type":"userMessage","content":[{"type":"text","text":text}]}]}]}});
                mark(home.path(), "t", &mut reopened).unwrap();
                assert_eq!(reopened["thread"]["turns"][0]["items"][0]["pocketContextSource"] == "runtime-environment", hidden);
            }
        }
        assert_eq!(std::fs::read_to_string(home.path().join("sessions/t.jsonl")).unwrap(), source);
    }

    #[test]
    fn internal_context_live_without_source_or_with_user_media_stays_visible() {
        let text = "<environment_context>user text</environment_context>";
        let source = [json!({"type":"session_meta","payload":{"id":"t"}}),
            json!({"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":text}]}})]
            .iter().map(Value::to_string).collect::<Vec<_>>().join("\n") + "\n";
        let home = fixture(&source);
        let mut event = json!({"method":"item/started","params":{"threadId":"t","turnId":"u","item":{"id":"i","type":"userMessage","content":[{"type":"text","text":text}]}}});
        mark(home.path(), "t", &mut event).unwrap();
        assert!(event["params"]["item"]["pocketContextSource"].is_null());
        let mut with_media = json!({"thread":{"turns":[{"items":[{"type":"userMessage","content":[{"type":"text","text":text},{"type":"image","url":"data:image/png;base64,eA=="}]}]}]}});
        mark(home.path(), "t", &mut with_media).unwrap();
        assert!(with_media["thread"]["turns"][0]["items"][0]["pocketContextSource"].is_null());
    }

    #[test]
    fn internal_context_live_never_inherits_identical_text_from_an_older_turn() {
        let text = "<environment_context><cwd>/project</cwd></environment_context>";
        let source = [json!({"type":"session_meta","payload":{"id":"t"}}),
            json!({"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":text}],
                "internal_chat_message_metadata_passthrough":{"turn_id":"old-turn","content_item_kinds":["environments.environment_context"],
                    "content_item_metadata":[{"provenance":{"type":"harness"}}]}}})]
            .iter().map(Value::to_string).collect::<Vec<_>>().join("\n") + "\n";
        let home = fixture(&source);
        let mut event = json!({"method":"item/started","params":{"threadId":"t","turnId":"new-turn","item":{"id":"new-user","type":"userMessage","content":[{"type":"text","text":text}]}}});
        mark(home.path(), "t", &mut event).unwrap();
        assert!(event["params"]["item"]["pocketContextSource"].is_null());
    }
}
