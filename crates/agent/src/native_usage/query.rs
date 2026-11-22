//! Bounded read-only aggregation over the ledger, shaped like the CC Switch dashboard
//! result. Whatever native data cannot prove (cost, status, timing) is null, never zero.
use super::ledger::{Failure, SCHEMA};
use crate::usage_stats::{MAX_GROUPS, MAX_ROWS, Page, UsageQuery};
use rusqlite::{Connection, OptionalExtension, Row, params};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    time::{Duration, Instant},
};

/// A request is one counted cumulative point that moved the thread's counter.
const PREDICATE: &str = "e.at>=?1 AND e.at<?2 AND (e.input>0 OR e.output>0) AND (?3 IS NULL OR COALESCE(e.provider,'unknown')=?3) AND (?4 IS NULL OR COALESCE(e.model,'unknown')=?4) AND (?5 IS NULL OR e.thread=(SELECT id FROM threads WHERE thread_id=?5))";

pub(crate) fn capabilities() -> Value {
    json!({"cost":false,"performance":false,"status":false,"providers":true,"models":true,"sessions":true,"requests":true})
}
pub(crate) fn source(version: Option<i64>) -> Value {
    json!({"kind":super::SOURCE,"table":"native_usage_events","app_type":"codex","data_source":null,"schema_version":version})
}
pub(crate) fn notices() -> Vec<Value> {
    vec![
        json!({"code":"cost_not_provided","message":"Native Codex usage carries no cost evidence; cost amounts are not provided."}),
        json!({"code":"incomplete_coverage","message":"Only usage the agent observed live or found in retained native history is counted."}),
    ]
}
#[derive(Default)]
struct Acc {
    requests: u64,
    tokens: [u64; 6],
    invalid: u64,
}
impl Acc {
    /// Columns: input, cached, cache_write, output, reasoning (all deltas).
    fn add(&mut self, row: &Row<'_>, at: usize, anomaly: bool) -> Result<(), Failure> {
        self.requests += 1;
        let split = tokens(row, at).filter(|_| !anomaly);
        let Some(values) = split else {
            self.invalid += 1;
            return Ok(());
        };
        for (total, value) in self.tokens.iter_mut().zip(values) {
            *total = total.checked_add(value).ok_or(Failure::Overflow)?;
        }
        Ok(())
    }
    fn json(&self) -> Value {
        let [fresh, read, write, output, total, reasoning] = self.tokens;
        json!({"requests":self.requests,"success":null,"errors":null,
            "tokens":{"fresh_input":fresh,"cache_read":read,"cache_write":write,"output":output,"total":total,"invalid_rows":self.invalid,"reasoning_output":reasoning},
            "costs":null,"performance":null})
    }
}
/// Same card meaning as the CC Switch reader gives Codex rows: fresh input excludes
/// both cache portions, and total = fresh + cache read + cache write + output.
fn tokens(row: &Row<'_>, at: usize) -> Option<[u64; 6]> {
    let n = |i: usize| {
        row.get::<_, i64>(at + i)
            .ok()
            .and_then(|v| u64::try_from(v).ok())
    };
    let (input, cached, write, output, reasoning) = (n(0)?, n(1)?, n(2)?, n(3)?, n(4)?);
    let fresh = input.checked_sub(cached)?.checked_sub(write)?;
    (reasoning <= output).then_some([
        fresh,
        cached,
        write,
        output,
        input.checked_add(output)?,
        reasoning,
    ])
}
fn page(items: Vec<Value>, p: Page) -> Value {
    let total = items.len();
    json!({"items":items.into_iter().skip(p.offset).take(p.limit).collect::<Vec<_>>(),"offset":p.offset,"limit":p.limit,"total_groups":total,"has_more":p.offset.saturating_add(p.limit)<total})
}
fn group<'a>(map: &'a mut BTreeMap<String, Acc>, key: &str) -> Result<&'a mut Acc, Failure> {
    if !map.contains_key(key) {
        if map.len() >= MAX_GROUPS {
            return Err(Failure::Limit);
        }
        map.insert(key.to_owned(), Acc::default());
    }
    Ok(map.get_mut(key).unwrap())
}
fn text(row: &Row<'_>, at: usize) -> Result<String, Failure> {
    Ok(row.get::<_, String>(at)?.chars().take(256).collect())
}
fn request_page(conn: &Connection, q: &UsageQuery) -> Result<Value, Failure> {
    let cursor = q.request_page.cursor.as_ref();
    let after = match cursor {
        Some(c) => Some(c.request_id.parse::<i64>().map_err(|_| Failure::Limit)?),
        None => None,
    };
    let mut stmt = conn.prepare(&format!(
        "SELECT e.id,e.at,COALESCE(e.provider,'unknown'),COALESCE(e.model,'unknown'),t.thread_id,e.turn_id,e.source,e.anomaly,e.input,e.cached,e.cache_write,e.output,e.reasoning FROM events e JOIN threads t ON t.id=e.thread WHERE e.state=1 AND {PREDICATE} AND (?6 IS NULL OR e.at<?6 OR (e.at=?6 AND e.id<?7)) ORDER BY e.at DESC,e.id DESC LIMIT ?8"
    ))?;
    let mut rows = stmt.query(params![
        q.start_at,
        q.end_at,
        q.filters.provider_id,
        q.filters.model,
        q.filters.session_id,
        cursor.map(|c| c.created_at),
        after,
        q.request_page.limit as i64 + 1
    ])?;
    let mut items = Vec::with_capacity(q.request_page.limit + 1);
    while let Some(row) = rows.next()? {
        let split = tokens(row, 8).filter(|_| !row.get::<_, bool>(7).unwrap_or(true));
        items.push(json!({
            "request_id":row.get::<_, i64>(0)?.to_string(),"created_at":row.get::<_, i64>(1)?,
            "provider_id":text(row, 2)?,"provider_name":null,"model":text(row, 3)?,
            "session_id":text(row, 4)?,"session_title":null,"turn_id":row.get::<_, Option<String>>(5)?,
            "observed_by":if row.get::<_, bool>(6)? {"history"} else {"live"},
            "status_code":null,
            "tokens":split.map(|[fresh, read, write, output, total, reasoning]| json!({"fresh_input":fresh,"cache_read":read,"cache_write":write,"output":output,"total":total,"invalid_rows":0,"reasoning_output":reasoning})),
            "cost_usd":null,"ttft_ms":null,"latency_ms":null,"tokens_per_second":null}));
    }
    let has_more = items.len() > q.request_page.limit;
    items.truncate(q.request_page.limit);
    let next = items
        .last()
        .filter(|_| has_more)
        .map(|r| json!({"created_at":r["created_at"],"request_id":r["request_id"]}));
    Ok(json!({"items":items,"limit":q.request_page.limit,"has_more":has_more,"next_cursor":next}))
}
fn snapshot(conn: &Connection) -> Result<rusqlite::Transaction<'_>, Failure> {
    let tx = conn.unchecked_transaction()?;
    if tx.query_row("PRAGMA user_version", [], |r| r.get::<_, i64>(0))? != SCHEMA {
        return Err(Failure::Schema);
    }
    Ok(tx)
}
pub(crate) fn requests(conn: &Connection, q: &UsageQuery) -> Result<Value, Failure> {
    let tx = snapshot(conn)?;
    Ok(
        json!({"availability":"ready","source":source(Some(SCHEMA)),"capabilities":capabilities(),"errors":notices(),"data":{"requests":request_page(&tx, q)?}}),
    )
}
pub(crate) fn dashboard(conn: &Connection, q: &UsageQuery) -> Result<Value, Failure> {
    let deadline = Instant::now() + Duration::from_secs(5);
    let tx = snapshot(conn)?;
    let filters = params![
        q.start_at,
        q.end_at,
        q.filters.provider_id,
        q.filters.model,
        q.filters.session_id
    ];
    let mut totals = Acc::default();
    let mut buckets: Vec<Acc> = (1..q.bucket_edges.len()).map(|_| Acc::default()).collect();
    let (mut providers, mut models) = (BTreeMap::new(), BTreeMap::new());
    let mut threads: BTreeMap<i64, Acc> = BTreeMap::new();
    let (mut first, mut last) = (None::<i64>, None::<i64>);
    {
        let mut stmt = tx.prepare(&format!(
            "SELECT e.at,e.thread,COALESCE(e.provider,'unknown'),COALESCE(e.model,'unknown'),e.anomaly,e.input,e.cached,e.cache_write,e.output,e.reasoning FROM events e WHERE e.state=1 AND {PREDICATE} LIMIT {}",
            MAX_ROWS + 1
        ))?;
        let mut rows = stmt.query(filters)?;
        while let Some(row) = rows.next()? {
            if totals.requests >= MAX_ROWS as u64
                || (totals.requests % 1024 == 0 && Instant::now() >= deadline)
            {
                return Err(Failure::Limit);
            }
            let at: i64 = row.get(0)?;
            let anomaly: bool = row.get(4)?;
            totals.add(row, 5, anomaly)?;
            buckets[q.bucket_edges.partition_point(|edge| *edge <= at) - 1].add(row, 5, anomaly)?;
            group(&mut providers, row.get_ref(2)?.as_str().unwrap_or("unknown"))?
                .add(row, 5, anomaly)?;
            group(&mut models, row.get_ref(3)?.as_str().unwrap_or("unknown"))?
                .add(row, 5, anomaly)?;
            let thread: i64 = row.get(1)?;
            if !threads.contains_key(&thread) && threads.len() >= MAX_GROUPS {
                return Err(Failure::Limit);
            }
            threads.entry(thread).or_default().add(row, 5, anomaly)?;
            first = Some(first.map_or(at, |v| v.min(at)));
            last = Some(last.map_or(at, |v| v.max(at)));
        }
    }
    let mut sessions = BTreeMap::new();
    {
        let mut stmt = tx.prepare("SELECT thread_id FROM threads WHERE id=?1")?;
        for (id, acc) in threads {
            if let Some(name) = stmt
                .query_row([id], |r| r.get::<_, String>(0))
                .optional()?
            {
                sessions.insert(name, acc);
            }
        }
    }
    // Stored but not counted: baselines of resumed/forked threads and gaps.
    let (mut unanchored, mut inherited) = (0u64, 0u64);
    {
        let mut stmt = tx.prepare(&format!(
            "SELECT e.state,COUNT(*) FROM events e WHERE e.state!=1 AND {PREDICATE} GROUP BY e.state"
        ))?;
        let mut rows = stmt.query(filters)?;
        while let Some(row) = rows.next()? {
            let count = row.get::<_, i64>(1)?.max(0) as u64;
            if row.get::<_, i64>(0)? == super::ledger::INHERITED {
                inherited = count;
            } else {
                unanchored = count;
            }
        }
    }
    let recent = request_page(&tx, q)?;
    let trend: Vec<Value> = buckets
        .iter()
        .enumerate()
        .map(|(i, acc)| json!({"start_at":q.bucket_edges[i],"end_at":q.bucket_edges[i + 1],"totals":acc.json()}))
        .collect();
    let mut errors = notices();
    if totals.invalid > 0 {
        errors.push(json!({"code":"invalid_tokens","message":"Some token records are invalid or their cache values exceed input."}));
    }
    Ok(json!({
        "availability":"ready",
        "coverage":{"scope":"native_ledger","complete":false,"matched_rows":totals.requests,"first_observed_at":first,"last_observed_at":last,
            "rollups_present":false,"rollups_included":false,"cost_known_rows":0,"cost_missing_rows":totals.requests,"cost_invalid_rows":0,"zero_cost_rows":0,
            "token_invalid_rows":totals.invalid,"confirmed_free_rows":null,"zero_cost_interpretation":"cost_not_provided",
            "sessions":sessions.len(),"unanchored_rows":unanchored,"inherited_rows":inherited},
        "source":source(Some(SCHEMA)),"capabilities":capabilities(),"errors":errors,
        "data":{"totals":totals.json(),"trend":trend,
            "provider_groups":page(providers.iter().map(|(id, acc)| json!({"provider_id":id,"app_type":"codex","name":null,"totals":acc.json()})).collect(), q.provider_page),
            "model_groups":page(models.iter().map(|(model, acc)| json!({"model":model,"totals":acc.json()})).collect(), q.model_page),
            "session_groups":page(sessions.iter().map(|(id, acc)| json!({"session_id":id,"title":null,"totals":acc.json()})).collect(), q.session_page),
            "recent_requests":recent}
    }))
}
/// Ledger-wide coverage for the source list; independent of any statistical filter.
pub(crate) fn overview(conn: &Connection) -> Result<Value, Failure> {
    let tx = snapshot(conn)?;
    let edge = |order: &str| {
        tx.query_row(
            &format!("SELECT at FROM events WHERE state=1 AND (input>0 OR output>0) ORDER BY at {order} LIMIT 1"),
            [],
            |r| r.get::<_, i64>(0),
        )
        .optional()
    };
    let counter = |key: &str| {
        tx.query_row("SELECT value FROM meta WHERE key=?1", [key], |r| {
            r.get::<_, i64>(0)
        })
        .optional()
        .map(|v| v.unwrap_or(0))
    };
    Ok(json!({
        "first_observed_at":edge("ASC")?,"last_observed_at":edge("DESC")?,
        "sessions":tx.query_row("SELECT COUNT(*) FROM threads", [], |r| r.get::<_, i64>(0))?,
        "invalid_records":counter("invalid_records")?,"rejected_records":counter("rejected_full")?,"rewound_files":counter("rewound_files")?
    }))
}
