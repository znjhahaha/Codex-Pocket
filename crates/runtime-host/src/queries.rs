//! Generation-local read receipts. These RPCs cannot authorize a native effect.
use crate::{
    protocol::{Receipt, key},
    recovery::digest,
};
use anyhow::{Result, ensure};
use serde_json::{Value, json};
use std::collections::{BTreeMap, VecDeque};

pub(crate) const NATIVE_QUERY_PREFIX: &str = "pocket-host-query:";
const MAX_QUERIES: usize = 128;
const MAX_REPLY_BYTES: usize = 2 * 1024 * 1024;

/// Fail closed for new methods/parameters. In particular, an account refresh is
/// not a query, nor are thread start/resume, login, or configuration writes.
pub(crate) fn read_only(value: &Value) -> bool {
    let Some(params) = value["params"].as_object() else {
        return false;
    };
    let allowed: &[&str] = match value["method"].as_str() {
        Some("account/read") if params.get("refreshToken") == Some(&json!(false)) => {
            &["refreshToken"]
        }
        Some("config/read") => &["includeLayers", "cwd"],
        Some("model/list") => &["cursor", "limit", "includeHidden"],
        Some("thread/list") => &[
            "cursor",
            "limit",
            "sortKey",
            "sortDirection",
            "modelProviders",
            "sourceKinds",
            "archived",
            "cwd",
            "searchTerm",
        ],
        Some("thread/loaded/list") => &["cursor", "limit"],
        Some("thread/read") => &["threadId", "includeTurns"],
        Some("thread/turns/list") => &["threadId", "cursor", "limit", "sortDirection", "itemsView"],
        Some("thread/items/list") => &["threadId", "turnId", "cursor", "limit", "sortDirection"],
        _ => return false,
    };
    params.keys().all(|key| allowed.contains(&key.as_str()))
}

pub(crate) struct PreparedRequest {
    pub native: Value,
    pub receipt: Option<Receipt>,
    pub durable: bool,
}

struct Query {
    id: Value,
    native_id: Value,
    generation: String,
    digest: [u8; 32],
    reply: Option<Value>,
}
impl Query {
    fn receipt(&self) -> Receipt {
        Receipt {
            generation: self.generation.clone(),
            state: if self.reply.is_some() {
                "completed"
            } else {
                "unknown"
            }
            .into(),
            response: self.reply.clone(),
        }
    }
}

#[derive(Default)]
pub(crate) struct Queries {
    entries: BTreeMap<String, Query>,
    order: VecDeque<String>,
}
impl Queries {
    pub fn receipt(&self, key: &str) -> Option<Receipt> {
        self.entries.get(key).map(Query::receipt)
    }

    pub fn reserve(&mut self, value: &Value, generation: &str) -> Result<PreparedRequest> {
        ensure!(read_only(value), "conflict");
        let id = key(&value["id"]).expect("validated request ID");
        let fingerprint = digest(value)?;
        if let Some(previous) = self.entries.get(&id) {
            ensure!(previous.digest == fingerprint, "conflict");
            return Ok(PreparedRequest {
                native: value.clone(),
                receipt: Some(previous.receipt()),
                durable: false,
            });
        }
        if self.entries.len() == MAX_QUERIES {
            // Never discard an outstanding query or its reply correlation.
            if let Some(index) = self
                .order
                .iter()
                .position(|id| self.entries[id].reply.is_some())
            {
                let old = self.order.remove(index).unwrap();
                self.entries.remove(&old);
            }
        }
        ensure!(self.entries.len() < MAX_QUERIES, "read_capacity");
        let native_id = json!(format!("{NATIVE_QUERY_PREFIX}{}", uuid::Uuid::new_v4()));
        let mut native = value.clone();
        native["id"] = native_id.clone();
        self.entries.insert(
            id.clone(),
            Query {
                id: value["id"].clone(),
                native_id,
                generation: generation.into(),
                digest: fingerprint,
                reply: None,
            },
        );
        self.order.push_back(id);
        Ok(PreparedRequest {
            native,
            receipt: None,
            durable: false,
        })
    }

    pub fn observe(&mut self, mut value: Value) -> Value {
        if value.get("method").is_some() {
            return value;
        }
        let Some(id) = self
            .entries
            .iter()
            .find(|(_, query)| query.native_id == value["id"] && query.reply.is_none())
            .map(|(id, _)| id.clone())
        else {
            return value;
        };
        let used: usize = self
            .entries
            .values()
            .filter_map(|q| q.reply.as_ref())
            .map(|r| serde_json::to_vec(r).unwrap().len())
            .sum();
        let query = self.entries.get_mut(&id).unwrap();
        value["id"] = query.id.clone();
        query.reply = Some(
            if used + serde_json::to_vec(&value).unwrap().len()
                <= MAX_REPLY_BYTES - MAX_QUERIES * 512
            {
                value.clone()
            } else {
                json!({"id":query.id,"error":{"code":"result_evicted"}})
            },
        );
        value
    }
}

#[cfg(test)]
mod tests;
