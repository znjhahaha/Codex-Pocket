//! Native-history edit proof. No file rollback or generation occurs here.
use anyhow::{Result, bail};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::HashMap;

pub fn inspect(thread: &Value, thread_id: &str, turn_id: &str, item_id: &str) -> Result<Value> {
    if thread["id"] != thread_id
        || thread.pointer("/status/type").and_then(Value::as_str) != Some("idle")
    {
        bail!("thread_not_idle_or_identity_mismatch");
    }
    let turns = thread["turns"]
        .as_array()
        .ok_or_else(|| anyhow::anyhow!("history_incomplete"))?;
    if turns
        .iter()
        .any(|t| t["status"] == "inProgress" || t.get("itemsView").is_some_and(|v| v != "full"))
    {
        bail!("history_incomplete_or_busy");
    }
    let index = turns
        .iter()
        .position(|t| t["id"] == turn_id)
        .ok_or_else(|| anyhow::anyhow!("turn_not_found"))?;
    if turns.iter().filter(|t| t["id"] == turn_id).count() != 1 {
        bail!("ambiguous_turn");
    }
    let item = turns[index]["items"]
        .as_array()
        .and_then(|i| i.first())
        .ok_or_else(|| anyhow::anyhow!("message_not_found"))?;
    if item["type"] != "userMessage" || item["id"] != item_id {
        bail!("only_first_user_message_is_editable");
    }
    let input = item["content"]
        .as_array()
        .ok_or_else(|| anyhow::anyhow!("input_unavailable"))?;
    if input.is_empty() || input.len() > 16 || input.iter().any(|i| !valid_input(i)) {
        bail!("input_preservation_unsupported");
    }
    let text = input
        .iter()
        .filter(|v| v["type"] == "text")
        .filter_map(|v| v["text"].as_str())
        .collect::<Vec<_>>()
        .join("\n");
    Ok(json!({"version":version(thread),"prefix":turns[..index],"input":input,"text":text}))
}
fn valid_input(input: &Value) -> bool {
    let fields: &[&str] = match input["type"].as_str() {
        Some("text") => &["type", "text", "text_elements"],
        Some("localImage") => &["type", "path", "detail"],
        Some("image") => &["type", "url", "fileId", "detail"],
        Some("skill" | "mention") => &["type", "name", "path"],
        _ => return false,
    };
    input
        .as_object()
        .is_some_and(|o| o.keys().all(|k| fields.contains(&k.as_str())))
        && input.get("detail").is_none_or(|v| {
            v.is_null() || matches!(v.as_str(), Some("auto" | "low" | "high" | "original"))
        })
        && match input["type"].as_str() {
            Some("text") => input["text"]
                .as_str()
                .is_some_and(|s| s.len() <= 1024 * 1024),
            Some("image") => {
                (input.get("url").is_some() ^ input.get("fileId").is_some())
                    && (input["fileId"].as_str().is_some_and(valid_id)
                        || input["url"].as_str().is_some_and(|s| {
                            s.len() <= 16 * 1024 * 1024
                                && (s.starts_with("data:image/") || s.starts_with("https://"))
                        }))
            }
            Some("localImage") => input["path"].as_str().is_some_and(valid_id),
            _ => ["name", "path"]
                .iter()
                .all(|k| input[*k].as_str().is_some_and(valid_id)),
        }
}
pub fn version(thread: &Value) -> String {
    format!("edit-{:x}",Sha256::digest(serde_json::to_vec(&json!({"id":thread["id"],"status":thread["status"],"updatedAt":thread["updatedAt"],"turns":thread["turns"]})).unwrap()))
}
pub fn verify_reverted(thread: &Value, thread_id: &str, prefix: &Value) -> Result<()> {
    if thread["id"] != thread_id || thread["status"]["type"] != "idle" || &thread["turns"] != prefix
    {
        bail!("revert_history_not_verified");
    }
    Ok(())
}
pub fn valid_id(s: &str) -> bool {
    !s.is_empty() && s.len() <= 4096 && !s.chars().any(char::is_control)
}
pub fn validate(p: &Value, write: bool) -> bool {
    let fields = if write {
        &[
            "commandId",
            "runtimeId",
            "threadId",
            "turnId",
            "itemId",
            "version",
            "editToken",
        ][..]
    } else {
        &["runtimeId", "threadId", "turnId", "itemId"][..]
    };
    p.as_object().is_some_and(|o| {
        o.len() == fields.len()
            && o.keys().all(|k| fields.contains(&k.as_str()))
            && fields.iter().all(|k| p[*k].as_str().is_some_and(valid_id))
    })
}
#[derive(Clone)]
pub struct Prepared {
    pub device: String,
    pub runtime: String,
    pub generation: String,
    pub thread: String,
    pub turn: String,
    pub item: String,
    pub proof: Value,
    pub expires: u64,
    pub reverted_version: Option<String>,
    pub consumed: bool,
    pub revert_dispatched: bool,
    pub sent_turn: Option<String>,
}
#[derive(Default)]
pub struct Edits {
    tokens: HashMap<String, Prepared>,
}
impl Edits {
    pub fn prepare(&mut self, p: Prepared, now: u64) -> String {
        self.tokens.retain(|_, p| p.expires > now);
        if self.tokens.len() >= 128 {
            self.tokens.clear();
        }
        let token = uuid::Uuid::new_v4().to_string();
        self.tokens.insert(token.clone(), p);
        token
    }
    pub fn get(
        &self,
        token: &str,
        device: &str,
        runtime: &str,
        generation: &str,
        thread: &str,
        now: u64,
    ) -> Result<Prepared> {
        self.tokens
            .get(token)
            .filter(|p| {
                p.device == device
                    && p.runtime == runtime
                    && p.generation == generation
                    && p.thread == thread
                    && p.expires > now
                    && !p.consumed
            })
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("edit_token_expired_or_wrong_identity"))
    }
    pub fn reverted(&mut self, token: &str, version: String) {
        if let Some(p) = self.tokens.get_mut(token) {
            p.reverted_version = Some(version);
        }
    }
    pub fn consume(&mut self, token: &str) {
        if let Some(p) = self.tokens.get_mut(token) {
            p.consumed = true;
        }
    }
    pub fn dispatched(&mut self, token: &str) {
        if let Some(p) = self.tokens.get_mut(token) {
            p.revert_dispatched = true;
        }
    }
    pub fn sent(&mut self, token: &str, turn: Option<String>) {
        if let Some(p) = self.tokens.get_mut(token) {
            p.sent_turn = turn;
        }
    }
    pub fn status_proof(
        &self,
        token: &str,
        device: &str,
        runtime: &str,
        generation: &str,
        thread: &str,
        now: u64,
    ) -> Option<Prepared> {
        self.tokens
            .get(token)
            .filter(|p| {
                p.device == device
                    && p.runtime == runtime
                    && p.generation == generation
                    && p.thread == thread
                    && p.expires > now
            })
            .cloned()
    }
}
pub fn displayed_input(input: &Value, token: &str) -> Value {
    json!(input.as_array().into_iter().flatten().enumerate().map(|(i,v)|if v["type"]=="text" {json!({"type":"text","text":v["text"]})} else {json!({"type":"retained","kind":v["type"],"id":format!("{token}:{i}"),"name":v.get("name")})}).collect::<Vec<_>>())
}
pub fn edited_input(original: &Value, input: &Value) -> Result<Value> {
    let text = input
        .as_array()
        .filter(|a| a.len() == 1)
        .and_then(|a| a.first())
        .filter(|v| v["type"] == "text" && v.as_object().is_some_and(|o| o.len() == 2))
        .and_then(|v| v["text"].as_str())
        .filter(|s| s.len() <= 1024 * 1024)
        .ok_or_else(|| anyhow::anyhow!("prepared_input_accepts_one_text_only"))?;
    let mut result = Vec::new();
    let mut replaced = false;
    for v in original
        .as_array()
        .ok_or_else(|| anyhow::anyhow!("input_unavailable"))?
    {
        if v["type"] == "text" {
            if !replaced {
                result.push(json!({"type":"text","text":text}));
                replaced = true;
            }
        } else {
            result.push(v.clone());
        }
    }
    if !replaced {
        result.insert(0, json!({"type":"text","text":text}));
    }
    if result.len() > 16 {
        bail!("too_many_retained_inputs");
    }
    Ok(json!(result))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn history() -> Value {
        json!({"id":"t","updatedAt":2,"status":{"type":"idle"},"turns":[
            {"id":"old","status":"completed","items":[{"id":"old-user","type":"userMessage","content":[{"type":"text","text":"old"}]}]},
            {"id":"u","status":"completed","items":[{"id":"i","type":"userMessage","content":[{"type":"text","text":"edit me"},{"type":"localImage","path":"C:/fixture.png"}]},{"id":"a","type":"agentMessage","text":"reply"}]}
        ]})
    }
    #[test]
    fn v054_edit_proof_requires_idle_first_user_and_full_history() {
        let h = history();
        let proof = inspect(&h, "t", "u", "i").unwrap();
        assert_eq!(proof["text"], "edit me");
        assert_eq!(proof["prefix"].as_array().unwrap().len(), 1);
        assert_eq!(proof["input"].as_array().unwrap().len(), 2);
        for (key, value) in [
            ("status", json!({"type":"active"})),
            ("id", json!("foreign")),
        ] {
            let mut bad = h.clone();
            bad[key] = value;
            assert!(inspect(&bad, "t", "u", "i").is_err());
        }
        assert!(inspect(&h, "t", "u", "a").is_err());
        let mut partial = h;
        partial["turns"][1]["itemsView"] = json!("summary");
        assert!(inspect(&partial, "t", "u", "i").is_err());
    }
    #[test]
    fn v054_revert_proof_and_retained_inputs_reject_changed_history_or_injection() {
        let h = history();
        let proof = inspect(&h, "t", "u", "i").unwrap();
        let mut reverted = h.clone();
        reverted["turns"] = proof["prefix"].clone();
        assert!(verify_reverted(&reverted, "t", &proof["prefix"]).is_ok());
        assert!(verify_reverted(&h, "t", &proof["prefix"]).is_err());
        let restored =
            edited_input(&proof["input"], &json!([{"type":"text","text":"new"}])).unwrap();
        assert_eq!(restored[1], proof["input"][1]);
        assert_eq!(restored[0]["text"], "new");
        assert!(
            edited_input(
                &proof["input"],
                &json!([{"type":"text","text":"new"},{"type":"localImage","path":"C:/private"}])
            )
            .is_err()
        );
        assert!(
            !displayed_input(&proof["input"], "token")
                .to_string()
                .contains("C:/fixture")
        );
    }
    #[test]
    fn v054_retains_native_image_file_id_and_detail_without_phone_paths() {
        let mut h = history();
        h["turns"][1]["items"][0]["content"] = json!([{"type":"text","text":"edit"},{"type":"image","fileId":"native-file","detail":"original"},{"type":"localImage","path":"C:/fixture.png","detail":null}]);
        let proof = inspect(&h, "t", "u", "i").unwrap();
        let retained =
            edited_input(&proof["input"], &json!([{"type":"text","text":"new"}])).unwrap();
        assert_eq!(retained[1], proof["input"][1]);
        assert_eq!(retained[2], proof["input"][2]);
        assert!(
            !displayed_input(&proof["input"], "token")
                .to_string()
                .contains("C:/fixture")
        );
    }
}
