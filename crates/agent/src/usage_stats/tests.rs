use super::*;
use rusqlite::{Connection, params};
use tempfile::TempDir;
pub(super) struct Fixture {
    pub(super) conn: Connection,
    pub(super) path: PathBuf,
    _dir: TempDir,
}
impl Fixture {
    pub(super) fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("cc-switch.db");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch("PRAGMA user_version=24;
          CREATE TABLE proxy_request_logs (request_id TEXT PRIMARY KEY, provider_id TEXT, app_type TEXT, model TEXT, request_model TEXT, pricing_model TEXT,
            input_tokens INTEGER, output_tokens INTEGER, cache_read_tokens INTEGER, cache_creation_tokens INTEGER, input_token_semantics INTEGER,
            input_cost_usd TEXT, output_cost_usd TEXT, cache_read_cost_usd TEXT, cache_creation_cost_usd TEXT, total_cost_usd TEXT,
            latency_ms INTEGER, first_token_ms INTEGER, duration_ms INTEGER, status_code INTEGER, session_id TEXT, provider_type TEXT,
            is_streaming INTEGER, cost_multiplier TEXT, created_at INTEGER, data_source TEXT, error_message TEXT);
          CREATE TABLE providers (id TEXT, app_type TEXT, name TEXT, settings_config TEXT, meta TEXT, PRIMARY KEY(id,app_type));
          INSERT INTO providers VALUES ('p1','codex','Fixture Provider','DO_NOT_PROJECT_SETTINGS','DO_NOT_PROJECT_META');
          INSERT INTO providers VALUES ('p1','claude','Other App',NULL,NULL);
          CREATE TABLE usage_daily_rollups (date TEXT, total_cost_usd TEXT);
          INSERT INTO usage_daily_rollups VALUES ('1970-01-01','9999');").unwrap();
        Self {
            _dir: dir,
            path,
            conn,
        }
    }
    pub(super) fn row(
        &self,
        id: &str,
        at: i64,
        app: &str,
        source: Option<&str>,
        semantics: i64,
        cost: Option<&str>,
    ) {
        self.conn.execute("INSERT INTO proxy_request_logs(request_id,provider_id,app_type,model,input_tokens,output_tokens,cache_read_tokens,cache_creation_tokens,input_token_semantics,total_cost_usd,input_cost_usd,output_cost_usd,cache_read_cost_usd,cache_creation_cost_usd,status_code,created_at,data_source,error_message,cost_multiplier) VALUES (?1,'p1',?2,'model-a',100,5,10,20,?3,?4,'0.01','0','0','0',200,?5,?6,'DO_NOT_PROJECT_ERROR','3')",params![id,app,semantics,cost,at,source]).unwrap();
    }
    fn query(&self, q: &UsageQuery) -> UsageResponse {
        query(&self.path, q)
    }
}
pub(super) fn q() -> UsageQuery {
    UsageQuery {
        start_at: 0,
        end_at: 30,
        bucket_edges: vec![0, 10, 20, 30],
        filters: Filters::default(),
        provider_page: Page::default(),
        model_page: Page::default(),
    }
}
fn ready(r: UsageResponse) -> UsageData {
    assert_eq!(r.availability, Availability::Ready, "{:?}", r.errors);
    r.data.unwrap()
}

#[test]
fn successful_measured_performance_has_explicit_sample_coverage() {
    let f = Fixture::new();
    for id in ["a", "b", "missing", "short", "bad", "error"] {
        f.row(id, 1, "codex", Some("proxy"), 2, Some("0"));
    }
    f.conn.execute_batch("UPDATE proxy_request_logs SET latency_ms=2000,first_token_ms=1000,is_streaming=1,output_tokens=100;
        UPDATE proxy_request_logs SET latency_ms=4000,first_token_ms=1000,output_tokens=300 WHERE request_id='b';
        UPDATE proxy_request_logs SET first_token_ms=NULL WHERE request_id='missing';
        UPDATE proxy_request_logs SET output_tokens=5 WHERE request_id='short';
        UPDATE proxy_request_logs SET first_token_ms=3000 WHERE request_id='bad';
        UPDATE proxy_request_logs SET status_code=500 WHERE request_id='error';
        UPDATE proxy_request_logs SET duration_ms=999999;").unwrap();
    let r = serde_json::to_value(f.query(&q())).unwrap();
    let p = &r["data"]["totals"]["performance"];
    assert_eq!(p["success_rows"], 5);
    assert_eq!(p["ttft"]["sample_rows"], 3);
    assert_eq!(p["ttft"]["average_ms"], 1000.0);
    assert_eq!(p["ttft"]["missing_rows"], 1);
    assert_eq!(p["ttft"]["invalid_rows"], 1);
    assert_eq!(p["duration"]["sample_rows"], 5);
    assert_eq!(p["duration"]["average_ms"], 2400.0);
    assert_eq!(p["output_speed"]["sample_rows"], 2);
    assert_eq!(p["output_speed"]["sum_output_tokens"], 400);
    assert_eq!(p["output_speed"]["sum_generation_ms"], 4000);
    assert_eq!(p["output_speed"]["tokens_per_second"], 100.0);
    assert_eq!(p["output_speed"]["excluded_rows"], 1);
    for section in [
        &r["data"]["trend"][0],
        &r["data"]["provider_groups"]["items"][0],
        &r["data"]["model_groups"]["items"][0],
    ] {
        assert_eq!(section["totals"]["performance"], *p);
    }
    assert!(r["data"]["trend"][1]["totals"]["performance"]["ttft"]["average_ms"].is_null());
}

#[test]
fn imported_session_timing_is_excluded_and_stored_zero_is_not_confirmed_free() {
    let f = Fixture::new();
    f.row("session", 1, "codex", Some("codex_session"), 2, Some("0"));
    f.conn.execute_batch("UPDATE proxy_request_logs SET latency_ms=1000,first_token_ms=0,is_streaming=1,output_tokens=1000").unwrap();
    let mut query = q();
    query.filters.data_source = DataSource::CodexSession;
    let r = serde_json::to_value(f.query(&query)).unwrap();
    assert_eq!(r["coverage"]["zero_cost_rows"], 1);
    assert_eq!(
        r["coverage"]["zero_cost_interpretation"],
        "stored_zero_free_status_unknown"
    );
    assert!(r["coverage"]["confirmed_free_rows"].is_null());
    for metric in ["ttft", "duration", "output_speed"] {
        assert_eq!(r["data"]["totals"]["performance"][metric]["sample_rows"], 0);
        assert_eq!(
            r["data"]["totals"]["performance"][metric]["excluded_rows"],
            1
        );
    }
}

#[test]
fn timing_rejects_negative_real_zero_duration_and_nonstreaming_samples() {
    let f = Fixture::new();
    for id in ["negative", "real", "zero", "nonstream", "zero_ttft"] {
        f.row(id, 1, "codex", None, 2, Some("0"));
    }
    f.conn.execute_batch("UPDATE proxy_request_logs SET latency_ms=1000,first_token_ms=0,is_streaming=1,output_tokens=100;
        UPDATE proxy_request_logs SET latency_ms=-1 WHERE request_id='negative';
        UPDATE proxy_request_logs SET latency_ms=1.5 WHERE request_id='real';
        UPDATE proxy_request_logs SET latency_ms=0 WHERE request_id='zero';
        UPDATE proxy_request_logs SET is_streaming=0 WHERE request_id='nonstream';").unwrap();
    let r = serde_json::to_value(f.query(&q())).unwrap();
    let p = &r["data"]["totals"]["performance"];
    assert_eq!(p["duration"]["invalid_rows"], 3);
    assert_eq!(p["duration"]["sample_rows"], 2);
    assert_eq!(p["ttft"]["invalid_rows"], 3);
    assert_eq!(p["ttft"]["excluded_rows"], 1);
    assert_eq!(p["ttft"]["average_ms"], 0.0);
    assert_eq!(p["output_speed"]["tokens_per_second"], 100.0);
    for metric in ["ttft", "duration", "output_speed"] {
        let m = &p[metric];
        let sum: u64 = [
            "sample_rows",
            "missing_rows",
            "invalid_rows",
            "excluded_rows",
        ]
        .iter()
        .map(|k| m[*k].as_u64().unwrap())
        .sum();
        assert_eq!(sum, 5);
    }
}

#[test]
fn invalid_streaming_flag_and_performance_overflow_are_not_silently_repaired() {
    let f = Fixture::new();
    f.row("invalid", 1, "codex", None, 2, Some("0"));
    f.conn.execute_batch("UPDATE proxy_request_logs SET latency_ms=1000,first_token_ms=0,is_streaming=1.5,output_tokens=100").unwrap();
    let r = serde_json::to_value(f.query(&q())).unwrap();
    assert_eq!(
        r["data"]["totals"]["performance"]["ttft"]["invalid_rows"],
        1
    );
    assert_eq!(
        r["data"]["totals"]["performance"]["ttft"]["missing_rows"],
        0
    );
    for id in ["large1", "large2", "large3"] {
        f.row(id, 1, "codex", None, 2, Some("0"));
    }
    f.conn.execute_batch("UPDATE proxy_request_logs SET latency_ms=9223372036854775807,first_token_ms=0,is_streaming=1,output_tokens=100").unwrap();
    let response = f.query(&q());
    assert_eq!(response.availability, Availability::Rejected);
    assert_eq!(response.errors[0].code, "arithmetic_overflow");
    assert!(response.data.is_none());
}

#[test]
fn defaults_isolate_sources_and_do_not_add_rollups() {
    let f = Fixture::new();
    f.row("proxy", 1, "codex", Some("proxy"), 1, Some("0.1"));
    f.row("null", 2, "codex", None, 0, Some("0.2"));
    f.row("session", 1, "codex", Some("codex_session"), 1, Some("9"));
    f.row("claude", 1, "claude", Some("proxy"), 2, Some("8"));
    let r = f.query(&q());
    assert!(!r.coverage.complete);
    assert!(r.coverage.rollups_present);
    assert!(!r.coverage.rollups_included);
    assert_eq!(r.coverage.matched_rows, 2);
    let serialized = serde_json::to_string(&r).unwrap();
    for marker in [
        "DO_NOT_PROJECT",
        "settings_config",
        "error_message",
        "session_id",
    ] {
        assert!(!serialized.contains(marker));
    }
    let d = ready(r);
    assert_eq!(d.totals.costs.total.known_usd, "0.3");
    assert_eq!(d.totals.requests, 2);
    assert_eq!(
        d.provider_groups.items[0].name.as_deref(),
        Some("Fixture Provider")
    );
    let mut query = q();
    query.filters.data_source = DataSource::CodexSession;
    assert_eq!(ready(f.query(&query)).totals.requests, 1);
}

#[test]
fn statistical_projection_does_not_require_or_select_sensitive_columns() {
    let f = Fixture::new();
    f.row("safe", 1, "codex", None, 2, Some("0.1"));
    f.conn
        .execute_batch(
            "ALTER TABLE proxy_request_logs DROP COLUMN error_message;
        ALTER TABLE proxy_request_logs DROP COLUMN session_id;
        ALTER TABLE proxy_request_logs DROP COLUMN provider_type;
        ALTER TABLE proxy_request_logs DROP COLUMN request_model;
        ALTER TABLE proxy_request_logs DROP COLUMN pricing_model;
        ALTER TABLE proxy_request_logs DROP COLUMN cost_multiplier;
        ALTER TABLE providers DROP COLUMN settings_config;
        ALTER TABLE providers DROP COLUMN meta;",
        )
        .unwrap();
    let response = f.query(&q());
    assert_eq!(response.source.schema_version, Some(24));
    let data = ready(response);
    assert_eq!(data.totals.requests, 1);
    assert_eq!(data.totals.costs.total.known_usd, "0.1");
    assert_eq!(
        data.provider_groups.items[0].name.as_deref(),
        Some("Fixture Provider")
    );
}
#[test]
fn normalizes_legacy_total_fresh_and_claude_without_double_cache() {
    let f = Fixture::new();
    for (id, s) in [("legacy", 0), ("total", 1), ("fresh", 2)] {
        f.row(id, 1, "codex", Some("proxy"), s, Some("0"));
    }
    let t = ready(f.query(&q())).totals.tokens;
    assert_eq!(
        (
            t.fresh_input,
            t.cache_read,
            t.cache_write,
            t.output,
            t.total
        ),
        (260, 30, 60, 15, 365)
    );
    f.row("claude", 1, "claude", Some("proxy"), 1, Some("0"));
    let mut query = q();
    query.filters.app_type = "claude".into();
    assert_eq!(ready(f.query(&query)).totals.tokens.total, 135);
}
#[test]
fn costs_are_exact_and_missing_invalid_zero_are_distinct() {
    let f = Fixture::new();
    for (id, c) in [
        ("a", Some("0.1")),
        ("b", Some("0.2")),
        ("c", Some("0.0000000000000000000000000001")),
        ("d", None),
        ("e", Some("0")),
        ("f", Some("bad")),
        ("g", Some("")),
        ("h", Some("-1")),
    ] {
        f.row(id, 1, "codex", Some("proxy"), 2, c);
    }
    let r = f.query(&q());
    assert_eq!(r.coverage.cost_missing_rows, 2);
    assert_eq!(r.coverage.cost_invalid_rows, 2);
    let c = ready(r).totals.costs.total;
    assert_eq!(c.known_usd, "0.3000000000000000000000000001");
    assert_eq!(
        (c.known_rows, c.missing_rows, c.invalid_rows, c.zero_rows),
        (4, 2, 2, 1)
    );
}
#[test]
fn trend_is_half_open_and_zero_filled() {
    let f = Fixture::new();
    for (id, at) in [("before", 0), ("edge", 10), ("end", 30)] {
        f.row(id, at, "codex", Some("proxy"), 2, Some("1"));
    }
    let d = ready(f.query(&q()));
    assert_eq!(d.totals.requests, 2);
    assert_eq!(
        d.trend
            .iter()
            .map(|b| b.totals.requests)
            .collect::<Vec<_>>(),
        [1, 1, 0]
    );
    assert_eq!((d.trend[1].start_at, d.trend[1].end_at), (10, 20));
}
#[test]
fn independent_group_pages_are_stable_and_filters_are_bound() {
    let f = Fixture::new();
    for id in ["z", "a", "m"] {
        f.row(id, 1, "codex", Some("proxy"), 2, Some("1"));
        f.conn.execute("UPDATE proxy_request_logs SET provider_id=?1,model=?1,status_code=CASE WHEN ?1='m' THEN 500 ELSE 200 END WHERE request_id=?1",[id]).unwrap();
    }
    let mut query = q();
    query.provider_page = Page {
        offset: 1,
        limit: 1,
    };
    query.model_page = Page {
        offset: 0,
        limit: 2,
    };
    let d = ready(f.query(&query));
    assert_eq!(d.provider_groups.total_groups, 3);
    assert_eq!(d.provider_groups.items[0].provider_id, "m");
    assert!(d.provider_groups.has_more);
    assert_eq!(d.model_groups.items[0].model, "a");
    query.filters.status = StatusFilter::Error;
    assert_eq!(ready(f.query(&query)).totals.requests, 1);
    query.filters.provider_id = Some("' OR 1=1 --".into());
    assert_eq!(ready(f.query(&query)).totals.requests, 0);
    query.filters.provider_id = None;
    query.filters.model = Some("m".into());
    assert_eq!(ready(f.query(&query)).totals.requests, 1);
}
#[test]
fn rejects_invalid_window_buckets_pages_and_source_before_open() {
    let missing = Path::new("Z:/no-such-usage-fixture.db");
    for kind in 0..9 {
        let mut query = q();
        match kind {
            0 => query.end_at = 0,
            1 => query.end_at = 32 * 86400 + 1,
            2 => query.bucket_edges = vec![0, 20, 10, 30],
            3 => query.bucket_edges = vec![1, 30],
            4 => query.provider_page.limit = 201,
            5 => query.model_page.offset = 10001,
            6 => query.start_at = -1,
            7 => query.filters.app_type = "unknown".into(),
            _ => {
                query.filters.app_type = "claude".into();
                query.filters.data_source = DataSource::CodexSession;
            }
        };
        let r = query_fn(missing, &query);
        assert_eq!(r.availability, Availability::Rejected);
        assert_eq!(r.errors[0].code, "invalid_query");
        assert!(r.data.is_none());
    }
}
fn query_fn(p: &Path, q: &UsageQuery) -> UsageResponse {
    query(p, q)
}
#[test]
fn missing_empty_and_unsupported_are_honest() {
    let f = Fixture::new();
    let r = query(&f._dir.path().join("missing.db"), &q());
    assert_eq!(r.availability, Availability::Missing);
    assert!(r.data.is_none());
    assert_eq!(ready(f.query(&q())).totals.requests, 0);
    f.conn.execute_batch("PRAGMA user_version=25").unwrap();
    assert_eq!(f.query(&q()).availability, Availability::UnsupportedSchema);
    f.conn.execute_batch("PRAGMA user_version=24; ALTER TABLE proxy_request_logs RENAME COLUMN input_token_semantics TO wrong").unwrap();
    assert_eq!(f.query(&q()).availability, Availability::UnsupportedSchema);
}
#[test]
fn live_wal_is_visible_and_main_database_is_unchanged() {
    let f = Fixture::new();
    f.conn
        .execute_batch("PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0")
        .unwrap();
    let before = std::fs::read(&f.path).unwrap();
    f.row("wal", 1, "codex", Some("proxy"), 2, Some("0.5"));
    assert_eq!(ready(f.query(&q())).totals.requests, 1);
    assert_eq!(std::fs::read(&f.path).unwrap(), before);
}
#[test]
fn locked_returns_sanitized_explicit_status() {
    let f = Fixture::new();
    f.conn.execute_batch("BEGIN EXCLUSIVE").unwrap();
    let r = f.query(&q());
    assert_eq!(r.availability, Availability::Locked);
    assert_eq!(r.errors[0].code, "database_locked");
    assert!(r.data.is_none());
}
#[test]
fn invalid_token_rows_are_counted_without_poisoning_valid_tokens() {
    let f = Fixture::new();
    f.row("valid", 1, "codex", Some("proxy"), 2, Some("0"));
    f.row("negative", 1, "codex", Some("proxy"), 2, Some("0"));
    f.row("unknown", 1, "codex", Some("proxy"), 99, Some("0"));
    f.conn
        .execute(
            "UPDATE proxy_request_logs SET input_tokens=-1 WHERE request_id='negative'",
            [],
        )
        .unwrap();
    let d = ready(f.query(&q()));
    assert_eq!(d.totals.requests, 3);
    assert_eq!(d.totals.tokens.total, 135);
    assert_eq!(d.totals.tokens.invalid_rows, 2);
}
#[test]
fn rejects_directory_relative_and_unc_paths() {
    let f = Fixture::new();
    assert_eq!(
        query(f._dir.path(), &q()).availability,
        Availability::InvalidPath
    );
    assert_eq!(
        query(Path::new("relative.db"), &q()).availability,
        Availability::InvalidPath
    );
    #[cfg(windows)]
    {
        assert_eq!(
            query(Path::new(r"\\server\share\cc-switch.db"), &q()).availability,
            Availability::InvalidPath
        );
    }
}

#[test]
fn readonly_connection_refuses_writes_even_if_query_only_is_disabled() {
    let f = Fixture::new();
    let conn = reader::connect(&f.path).unwrap();
    assert_eq!(
        conn.query_row("PRAGMA query_only", [], |r| r.get::<_, i64>(0))
            .unwrap(),
        1
    );
    assert!(conn.execute("CREATE TABLE forbidden_write(x)", []).is_err());
    conn.execute_batch("PRAGMA query_only=OFF").unwrap();
    assert!(conn.execute("CREATE TABLE forbidden_write(x)", []).is_err());
}
#[test]
fn arithmetic_overflow_never_returns_a_partial_aggregate() {
    let f = Fixture::new();
    f.row(
        "a",
        1,
        "codex",
        Some("proxy"),
        2,
        Some("170141183460469231731687303715884105727"),
    );
    f.row("b", 1, "codex", Some("proxy"), 2, Some("1"));
    let r = f.query(&q());
    assert_eq!(r.availability, Availability::Rejected);
    assert_eq!(r.errors[0].code, "arithmetic_overflow");
    assert!(r.data.is_none());
}
#[test]
fn large_unmatched_scan_is_interrupted_by_the_vm_budget() {
    let f = Fixture::new();
    f.conn.execute_batch("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<600000) INSERT INTO proxy_request_logs(request_id,app_type,created_at,data_source,provider_id) SELECT 'budget-'||x,'codex',1,'proxy','absent' FROM n").unwrap();
    let mut query = q();
    query.filters.provider_id = Some("not-present".into());
    let r = f.query(&query);
    assert_eq!(r.availability, Availability::Rejected);
    assert_eq!(r.errors[0].code, "query_limit");
    assert!(r.data.is_none());
}
#[test]
fn row_and_group_limits_are_explicit() {
    let f = Fixture::new();
    f.conn.execute_batch("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<100001) INSERT INTO proxy_request_logs(request_id,provider_id,app_type,model,input_tokens,output_tokens,cache_read_tokens,cache_creation_tokens,input_token_semantics,total_cost_usd,status_code,created_at,data_source) SELECT 'limit-'||x,'p1','codex','m',0,0,0,0,2,'0',200,1,'proxy' FROM n").unwrap();
    let r = f.query(&q());
    assert_eq!(r.availability, Availability::Rejected);
    assert_eq!(r.errors[0].code, "query_limit");
    assert!(r.data.is_none());
    f.conn.execute_batch("DELETE FROM proxy_request_logs WHERE CAST(SUBSTR(request_id,7) AS INTEGER)>10001; UPDATE proxy_request_logs SET model=request_id").unwrap();
    assert_eq!(f.query(&q()).errors[0].code, "query_limit");
}
#[test]
fn missing_provider_table_is_optional_and_duplicates_are_rejected() {
    let f = Fixture::new();
    f.row("a", 1, "codex", Some("proxy"), 2, Some("0"));
    f.conn.execute_batch("DROP TABLE providers").unwrap();
    let d = ready(f.query(&q()));
    assert!(d.provider_groups.items[0].name.is_none());
    f.conn.execute_batch("CREATE TABLE providers (id TEXT, app_type TEXT, name TEXT); INSERT INTO providers VALUES ('p1','codex','a'),('p1','codex','b')").unwrap();
    assert_eq!(f.query(&q()).availability, Availability::UnsupportedSchema);
}
#[test]
fn unknown_field_payloads_and_extreme_buckets_are_rejected() {
    assert!(serde_json::from_value::<UsageQuery>(serde_json::json!({"start_at":0,"end_at":30,"bucket_edges":[0,30],"data_sources":["proxy","codex_session"]})).is_err());
    let f = Fixture::new();
    let mut query = q();
    query.bucket_edges = vec![0, i64::MIN, i64::MAX, 30];
    assert_eq!(f.query(&query).availability, Availability::Rejected);
    query = q();
    query.end_at = 31 * 86400;
    query.bucket_edges = (0..=31).map(|v| v * 86400).collect();
    assert_eq!(ready(f.query(&query)).trend.len(), 31);
    query.end_at += 1;
    query.bucket_edges.push(query.end_at);
    assert_eq!(f.query(&query).availability, Availability::Rejected);
}
#[test]
fn default_path_only_resolves_a_name_without_claiming_availability() {
    if let Some(path) = default_db_path() {
        assert!(path.ends_with(".cc-switch/cc-switch.db"));
    }
}

#[test]
fn caller_dst_buckets_allow_25_hours_with_fixed_upper_bounds() {
    let f = Fixture::new();
    let mut query = q();
    query.end_at = 25 * 3600;
    query.bucket_edges = vec![0, query.end_at];
    assert_eq!(ready(f.query(&query)).trend[0].end_at, 25 * 3600);
    query.end_at = 27 * 3600;
    query.bucket_edges = vec![0, query.end_at];
    assert_eq!(ready(f.query(&query)).trend.len(), 1);
    query.end_at += 1;
    query.bucket_edges[1] = query.end_at;
    assert_eq!(f.query(&query).availability, Availability::Rejected);
    query = q();
    query.bucket_edges = (0..=31).map(|n| n * 86400).collect();
    query.bucket_edges[1] += 3600;
    query.end_at = 31 * 86400;
    assert_eq!(ready(f.query(&query)).trend.len(), 31);
    // 31 buckets spanning exactly 32 days, each shorter than the fixed 27h bound.
    query.end_at = 32 * 86400;
    query.bucket_edges = (0..=31).map(|n| n * query.end_at / 31).collect();
    assert_eq!(ready(f.query(&query)).trend.len(), 31);
    query.end_at += 1;
    query.bucket_edges[31] = query.end_at;
    assert_eq!(f.query(&query).availability, Availability::Rejected);
    query = q();
    query.end_at = 744 * 3600;
    query.bucket_edges = (0..=744).map(|n| n * 3600).collect();
    assert_eq!(ready(f.query(&query)).trend.len(), 744);
    query.end_at += 3600;
    query.bucket_edges.push(query.end_at);
    assert_eq!(f.query(&query).availability, Availability::Rejected);
}
