//! Agent-owned ledger of native Codex token usage, fed by live notifications and a
//! bounded incremental rollout import. Independent of CC Switch; never summed with it.
mod import;
mod ledger;
mod query;
pub mod service;
use serde_json::Value;

pub const SOURCE: &str = "native_codex";
pub const CAPABILITY: &str = "usage.v2";
pub(crate) const MAX_THREADS: i64 = 200_000;
pub(crate) const MAX_EVENTS: i64 = 8_000_000;
const MAX_AT: i64 = 253_402_300_799;

/// Native semantics (verified on 0.167-0.171 rollouts): `input` already contains the
/// cached and cache-write portions, `output` contains reasoning, total = input + output.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct Usage {
    pub input: u64,
    pub cached: u64,
    pub cache_write: u64,
    pub output: u64,
    pub reasoning: u64,
}
const LIVE: [&str; 6] = [
    "inputTokens",
    "cachedInputTokens",
    "cacheWriteInputTokens",
    "outputTokens",
    "reasoningOutputTokens",
    "totalTokens",
];
const HISTORY: [&str; 6] = [
    "input_tokens",
    "cached_input_tokens",
    "cache_write_input_tokens",
    "output_tokens",
    "reasoning_output_tokens",
    "total_tokens",
];
impl Usage {
    fn parse(v: &Value, keys: &[&str; 6]) -> Option<Self> {
        let field = |key: &str, required: bool| match v.get(key) {
            Some(n) => n.as_u64().filter(|n| *n <= i64::MAX as u64),
            None => (!required).then_some(0),
        };
        let usage = Self {
            input: field(keys[0], true)?,
            cached: field(keys[1], false)?,
            cache_write: field(keys[2], false)?,
            output: field(keys[3], true)?,
            reasoning: field(keys[4], false)?,
        };
        // The reported total must be a number but is never trusted over input + output.
        field(keys[5], false)?;
        usage
            .input
            .checked_add(usage.output)
            .filter(|n| *n <= i64::MAX as u64)?;
        Some(usage)
    }
    /// A breakdown that cannot be split into fresh/cache/output is counted as a
    /// request with invalid tokens, like CC Switch rows whose cache exceeds input.
    pub(crate) fn anomaly(&self) -> bool {
        self.cached
            .checked_add(self.cache_write)
            .is_none_or(|n| n > self.input)
            || self.reasoning > self.output
    }
}
pub(crate) fn identity(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 128
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b':'))
}
pub(crate) fn label(v: &Value) -> Option<String> {
    v.as_str()
        .filter(|s| !s.is_empty() && s.len() <= 128 && !s.chars().any(char::is_control))
        .map(str::to_owned)
}
/// One sighting of a thread's cumulative counter. Numbers and identifiers only.
#[derive(Debug, Clone)]
pub(crate) struct Observation {
    pub thread: String,
    pub turn: Option<String>,
    pub cum: Usage,
    pub last: Usage,
    pub at: i64,
    pub model: Option<String>,
    pub provider: Option<String>,
    pub parent: Option<String>,
    pub history: bool,
}
impl Observation {
    fn checked(self) -> Option<Self> {
        (self.last.input <= self.cum.input
            && self.last.output <= self.cum.output
            && (1..=MAX_AT).contains(&self.at))
        .then_some(self)
    }
    /// `thread/tokenUsage/updated` params.
    pub(crate) fn live(params: &Value, at: i64) -> Option<Self> {
        let usage = &params["tokenUsage"];
        Self {
            thread: params["threadId"].as_str().filter(|s| identity(s))?.into(),
            turn: params["turnId"]
                .as_str()
                .filter(|s| identity(s))
                .map(str::to_owned),
            cum: Usage::parse(&usage["total"], &LIVE)?,
            last: Usage::parse(&usage["last"], &LIVE)?,
            at,
            model: None,
            provider: None,
            parent: None,
            history: false,
        }
        .checked()
    }
    /// Rollout `token_count` info; the caller supplies the file's identity context.
    pub(crate) fn history(info: &Value, timestamp: &Value, thread: &str) -> Option<Self> {
        Self {
            thread: thread.into(),
            turn: None,
            cum: Usage::parse(&info["total_token_usage"], &HISTORY)?,
            last: Usage::parse(&info["last_token_usage"], &HISTORY)?,
            at: i64::try_from(crate::context_usage::timestamp_ms(timestamp)? / 1000).ok()?,
            model: None,
            provider: None,
            parent: None,
            history: true,
        }
        .checked()
    }
}
#[cfg(test)]
pub(crate) mod tests;
