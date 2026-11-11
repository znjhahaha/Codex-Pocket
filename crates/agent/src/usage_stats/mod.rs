//! Read-only CC Switch statistics. Host wiring intentionally lives outside this module.
mod aggregate;
mod decimal;
mod performance;
mod reader;
pub mod service;
mod types;
use std::path::{Path, PathBuf};
pub use types::*;
pub fn default_db_path() -> Option<PathBuf> {
    std::env::var_os("USERPROFILE").map(|p| PathBuf::from(p).join(".cc-switch/cc-switch.db"))
}
/// Trusted local diagnostic file only; never accept a remote path.
pub(crate) fn read_projection(path: &Path, limit: u64) -> Option<Vec<u8>> {
    reader::read_projection(path, limit)
}
pub const MAX_WINDOW_SECONDS: i64 = 32 * 86400;
pub const MAX_BUCKET_SECONDS: i64 = 27 * 3600;
pub const MAX_DAILY_BUCKETS: usize = 31;
pub const MAX_BUCKETS: usize = 744;
pub const MAX_ROWS: usize = 100_000;
pub const MAX_GROUPS: usize = 10_000;
fn valid(q: &UsageQuery) -> bool {
    let page = |p: Page| (1..=200).contains(&p.limit) && p.offset <= 10000;
    let filter = |v: &Option<String>| {
        v.as_ref()
            .is_none_or(|s| !s.is_empty() && s.len() <= 256 && !s.contains('\0'))
    };
    q.start_at >= 0
        && q.end_at > q.start_at
        && q.end_at <= 253402300799
        && q.end_at - q.start_at <= MAX_WINDOW_SECONDS
        && (2..=MAX_BUCKETS + 1).contains(&q.bucket_edges.len())
        && q.bucket_edges.first() == Some(&q.start_at)
        && q.bucket_edges.last() == Some(&q.end_at)
        && q.bucket_edges.windows(2).all(|w| {
            w[1] > w[0]
                && w[1]
                    .checked_sub(w[0])
                    .is_some_and(|n| n <= MAX_BUCKET_SECONDS)
        })
        && (q
            .bucket_edges
            .windows(2)
            .all(|w| w[1].checked_sub(w[0]).is_some_and(|n| n <= 3600))
            || q.bucket_edges.len() - 1 <= MAX_DAILY_BUCKETS)
        && matches!(
            q.filters.app_type.as_str(),
            "codex" | "claude" | "gemini" | "grokbuild"
        )
        && (q.filters.data_source != DataSource::CodexSession || q.filters.app_type == "codex")
        && filter(&q.filters.provider_id)
        && filter(&q.filters.model)
        && page(q.provider_page)
        && page(q.model_page)
}
fn error(code: &str, message: &str) -> UsageError {
    UsageError {
        code: code.into(),
        message: message.into(),
    }
}
/// Blocking, bounded, read-only snapshot. The trusted host supplies the physical path.
pub fn query(path: &Path, q: &UsageQuery) -> UsageResponse {
    let mut response = UsageResponse {
        availability: Availability::Unavailable,
        coverage: Coverage {
            scope: "retained_detail_only".into(),
            zero_cost_interpretation: "stored_zero_free_status_unknown".into(),
            ..Coverage::default()
        },
        source: SourceInfo {
            kind: "cc_switch".into(),
            table: "proxy_request_logs".into(),
            app_type: q.filters.app_type.chars().take(256).collect(),
            data_source: q.filters.data_source,
            schema_version: None,
        },
        errors: vec![],
        data: None,
    };
    if !valid(q) {
        response.availability = Availability::Rejected;
        response.errors.push(error(
            "invalid_query",
            "The time window, UTC buckets, filters, or page exceed the supported bounds.",
        ));
        return response;
    }
    match reader::read(path, q, &mut response.source.schema_version) {
        Err(f) => {
            let (availability, code, message) = f.details();
            response.availability = availability;
            response.errors.push(error(code, message));
        }
        Ok(r) => {
            let c = &r.data.totals.costs.total;
            response.availability = Availability::Ready;
            response.coverage = Coverage {
                scope: "retained_detail_only".into(),
                complete: false,
                matched_rows: r.data.totals.requests,
                first_observed_at: r.first,
                last_observed_at: r.last,
                rollups_present: r.rollups,
                rollups_included: false,
                cost_known_rows: c.known_rows,
                cost_missing_rows: c.missing_rows,
                cost_invalid_rows: c.invalid_rows,
                zero_cost_rows: c.zero_rows,
                token_invalid_rows: r.data.totals.tokens.invalid_rows,
                confirmed_free_rows: None,
                zero_cost_interpretation: "stored_zero_free_status_unknown".into(),
            };
            response.errors.push(error("incomplete_coverage","Only retained request details are counted; pruning, missing requests, and historical unpriced zero values cannot be ruled out."));
            if c.missing_rows > 0 {
                response.errors.push(error(
                    "missing_cost",
                    "Some stored total costs are missing.",
                ));
            }
            if c.invalid_rows > 0 {
                response.errors.push(error(
                    "invalid_cost",
                    "Some stored total costs are invalid and excluded from the known sum.",
                ));
            }
            if r.data.totals.tokens.invalid_rows > 0 {
                response.errors.push(error(
                    "invalid_tokens",
                    "Some token records are invalid or their cache values exceed input.",
                ));
            }
            response.data = Some(r.data);
        }
    }
    response
}
#[cfg(test)]
mod tests;
