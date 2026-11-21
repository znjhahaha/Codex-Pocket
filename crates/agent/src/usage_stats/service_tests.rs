use super::*;
use crate::usage_stats::tests::{Fixture, q};
use serde_json::json;

#[test]
fn remote_read_defaults_and_path_rejection_are_explicit() {
    let query = prepare_read(&json!({}), 1_800_000_031).unwrap();
    assert_eq!((query.end_at + 8 * 3600).rem_euclid(86400), 0);
    assert_eq!(query.start_at, query.end_at - 7 * 86400);
    assert_eq!(query.bucket_edges.len(), 8);
    assert_eq!(query.filters.app_type, "codex");
    assert_eq!(query.filters.data_source, DataSource::Proxy);
    for p in [
        json!(null),
        json!({"path":"C:/secret"}),
        json!({"source_id":"other"}),
        json!({"query":{"path":"x"}}),
        json!({"query":null}),
    ] {
        assert!(prepare_read(&p, 1_800_000_000).is_err());
    }
    let query = prepare_read(&json!({"query":q()}), 1_800_000_000).unwrap();
    assert_eq!(query.end_at, 30);
    assert!(validate_sources(&json!({})));
    assert!(!validate_sources(&json!({"filters":{}})));
}

#[tokio::test]
async fn sources_probe_schema_even_if_selected_filter_is_empty() {
    let f = Fixture::new();
    let service = UsageService::with_path(Some(f.path.clone()));
    let sources = service.sources().await;
    assert_eq!(sources["sources"][0]["availability"], "ready");
    assert_eq!(sources["sources"][0]["schema_version"], 24);
    let read = service.read(q()).await;
    assert_eq!(read["result"]["coverage"]["matched_rows"], 0);
    assert_eq!(read["result"]["availability"], "ready");
    f.conn.execute_batch("PRAGMA user_version=23").unwrap();
    let service = UsageService::with_path(Some(f.path.clone()));
    assert_eq!(
        service.sources().await["sources"][0]["availability"],
        "unsupported_schema"
    );
}

#[tokio::test]
async fn exact_query_cache_uses_stale_only_for_missing_or_locked() {
    let f = Fixture::new();
    f.row("a", 1, "codex", None, 2, Some("0.1"));
    let service = UsageService::with_path(Some(f.path.clone()));
    let first = service.read(q()).await;
    assert_eq!(first["result"]["data"]["totals"]["requests"], 1);
    f.row("b", 2, "codex", None, 2, Some("0.2"));
    assert_eq!(
        service.read(q()).await["result"]["data"]["totals"]["requests"],
        1
    );
    service.age_cache_for_test(61);
    f.conn.execute_batch("BEGIN EXCLUSIVE").unwrap();
    let stale = service.read(q()).await;
    assert_eq!(stale["cache"]["state"], "stale");
    assert_eq!(stale["cache"]["stale_reason"], "database_locked");
    assert_eq!(stale["result"]["availability"], "locked");
    assert_eq!(stale["result"]["data"]["totals"]["requests"], 1);
    let mut other = q();
    other.filters.model = Some("unrelated".into());
    assert!(service.read(other).await["result"]["data"].is_null());
    f.conn
        .execute_batch("ROLLBACK; PRAGMA user_version=23")
        .unwrap();
    let bad = service.read(q()).await;
    assert_eq!(bad["result"]["availability"], "unsupported_schema");
    assert!(bad["result"]["data"].is_null());
    f.conn.execute_batch("PRAGMA user_version=24").unwrap();
    drop(f.conn);
    std::fs::remove_file(&f.path).unwrap();
    assert_eq!(service.read(q()).await["cache"]["state"], "stale");
    service.age_cache_for_test(301);
    let expired = service.read(q()).await;
    assert_eq!(expired["cache"]["state"], "unavailable");
    assert!(expired["result"]["data"].is_null());
}

#[tokio::test]
async fn sources_stale_and_cache_entry_byte_bounds_are_enforced() {
    let f = Fixture::new();
    let service = UsageService::with_path(Some(f.path.clone()));
    assert_eq!(
        service.sources().await["sources"][0]["availability"],
        "ready"
    );
    for i in 0..20 {
        let mut query = q();
        query.filters.model = Some(format!("model-{i}"));
        service.read(query).await;
    }
    assert!(service.cache.lock().unwrap().entries.len() <= 16);
    assert!(service.cache.lock().unwrap().bytes <= 8 * 1024 * 1024);
    service.sources().await;
    service.age_cache_for_test(61);
    drop(f.conn);
    std::fs::remove_file(&f.path).unwrap();
    let stale = service.sources().await;
    assert_eq!(stale["cache"]["state"], "stale");
    assert_eq!(stale["sources"][0]["availability"], "missing");
    assert_eq!(stale["sources"][0]["schema_version"], 24);
}

#[tokio::test]
async fn exhausted_blocking_slots_return_bounded_busy() {
    let f = Fixture::new();
    let service = UsageService::with_path(Some(f.path.clone()));
    let permits = service.slots.clone().acquire_many_owned(2).await.unwrap();
    let start = std::time::Instant::now();
    let busy = service.read(q()).await;
    assert_eq!(busy["result"]["errors"][0]["code"], "backend_busy");
    assert!(start.elapsed() < std::time::Duration::from_secs(1));
    drop(permits);
    assert_eq!(service.read(q()).await["result"]["availability"], "ready");
}

#[test]
fn oversized_cache_values_are_not_retained_and_replacements_keep_correct_bytes() {
    let mut cache = Cache::default();
    cache.insert("a".into(), json!({"ready":true}), Instant::now(), 1);
    cache.insert(
        "a".into(),
        json!({"ready":true,"rows":2}),
        Instant::now(),
        2,
    );
    assert_eq!(cache.entries.len(), 1);
    assert_eq!(
        cache.bytes,
        cache.entries.iter().map(|e| e.bytes).sum::<usize>()
    );
    cache.insert(
        "large".into(),
        json!("x".repeat(MAX_BYTES)),
        Instant::now(),
        3,
    );
    assert_eq!(cache.entries.len(), 1);
    assert!(cache.bytes < MAX_BYTES);
    assert_eq!(cache.get("a").unwrap().sampled_at, 2);
}

#[tokio::test]
async fn identical_cold_reads_and_explicit_refreshes_share_one_snapshot() {
    use std::sync::atomic::Ordering;
    let f = Fixture::new();
    f.row("a", 1, "codex", None, 2, Some("0.1"));
    let service = UsageService::with_path(Some(f.path.clone()));
    for refresh in [false, true] {
        if refresh {
            f.row("b", 2, "codex", None, 2, Some("0.2"));
        }
        let reads = service.reads.load(Ordering::SeqCst);
        let permits = service.hold_slots_for_test().await;
        let (a, b, c, d, ()) = tokio::join!(
            service.dashboard_with_refresh(q(), refresh),
            service.dashboard_with_refresh(q(), refresh),
            service.dashboard(q()),
            service.dashboard_with_refresh(q(), refresh),
            async {
                tokio::task::yield_now().await;
                drop(permits);
            },
        );
        assert_eq!(a, b);
        assert_eq!(a, c);
        assert_eq!(a, d);
        assert_eq!(
            a["result"]["data"]["totals"]["requests"],
            if refresh { 2 } else { 1 }
        );
        assert_eq!(service.reads.load(Ordering::SeqCst) - reads, 1);
        assert!(service.in_flight.lock().unwrap().is_empty());
    }
}

#[tokio::test]
async fn identical_failed_refreshes_coalesce_and_keep_scoped_snapshot_metadata() {
    use std::sync::atomic::Ordering;
    let f = Fixture::new();
    f.row("a", 1, "codex", None, 2, Some("0.1"));
    f.row("import", 1, "codex", Some("codex_session"), 2, Some("90"));
    let service = UsageService::with_path(Some(f.path.clone()));
    let initial = service.dashboard(q()).await;
    f.conn.execute_batch("BEGIN EXCLUSIVE").unwrap();
    let reads = service.reads.load(Ordering::SeqCst);
    let (a, b) = tokio::join!(
        service.dashboard_with_refresh(q(), true),
        service.dashboard_with_refresh(q(), true),
    );
    assert_eq!(a, b);
    assert_eq!(service.reads.load(Ordering::SeqCst) - reads, 1);
    assert_eq!(a["cache"]["state"], "stale");
    assert_eq!(a["cache"]["stale_reason"], "database_locked");
    assert_eq!(a["cache"]["sampled_at"], initial["cache"]["sampled_at"]);
    for field in ["data", "source", "coverage"] {
        assert_eq!(a["result"][field], initial["result"][field]);
    }
    let mut other = q();
    other.filters.data_source = DataSource::CodexSession;
    assert!(service.dashboard_with_refresh(other, true).await["result"]["data"].is_null());
    f.conn.execute_batch("ROLLBACK").unwrap();
    f.row("b", 2, "codex", None, 2, Some("0.2"));
    let fresh = service.dashboard_with_refresh(q(), true).await;
    assert_eq!(fresh["cache"]["state"], "fresh");
    assert_eq!(fresh["result"]["data"]["totals"]["requests"], 2);
    assert_eq!(
        fresh["result"]["data"]["totals"]["costs"]["total"]["known_usd"],
        "0.3"
    );
}

#[tokio::test]
async fn canceled_waiter_does_not_cancel_or_orphan_a_shared_read() {
    use std::sync::atomic::Ordering;
    let f = Fixture::new();
    f.row("a", 1, "codex", None, 2, Some("0.1"));
    let service = UsageService::with_path(Some(f.path.clone()));
    let permits = service.hold_slots_for_test().await;
    let other = service.clone();
    let waiter = tokio::spawn(async move { other.dashboard(q()).await });
    tokio::time::timeout(Duration::from_secs(1), async {
        while service.in_flight.lock().unwrap().is_empty() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    waiter.abort();
    assert!(waiter.await.unwrap_err().is_cancelled());
    let (value, ()) = tokio::join!(service.dashboard_with_refresh(q(), true), async {
        tokio::task::yield_now().await;
        drop(permits);
    });
    assert_eq!(value["result"]["availability"], "ready");
    assert_eq!(service.reads.load(Ordering::SeqCst), 1);
    assert!(service.in_flight.lock().unwrap().is_empty());
}

#[tokio::test]
async fn failed_schema_refresh_does_not_replace_the_last_valid_snapshot() {
    let f = Fixture::new();
    f.row("a", 1, "codex", None, 2, Some("0.1"));
    let service = UsageService::with_path(Some(f.path.clone()));
    let initial = service.dashboard(q()).await;
    f.conn.execute_batch("ALTER TABLE proxy_request_logs RENAME COLUMN input_token_semantics TO changed_semantics").unwrap();
    let failed = service.dashboard_with_refresh(q(), true).await;
    assert_eq!(failed["result"]["availability"], "unsupported_schema");
    assert!(failed["result"]["data"].is_null());
    assert!(failed["cache"]["sampled_at"].is_null());
    let cache = service.cache.lock().unwrap();
    assert_eq!(cache.entries.len(), 1);
    assert_eq!(cache.entries[0].value["data"], initial["result"]["data"]);
    assert_eq!(
        json!(cache.entries[0].sampled_at),
        initial["cache"]["sampled_at"]
    );
}

#[tokio::test]
async fn request_page_refresh_preserves_shape_on_lock_and_worker_failure() {
    let f = Fixture::new();
    f.row("a", 1, "codex", None, 2, Some("0.1"));
    let service = UsageService::with_path(Some(f.path.clone()));
    let first = service.requests(q()).await;
    f.conn.execute_batch("BEGIN EXCLUSIVE").unwrap();
    let stale = service.requests_with_refresh(q(), true).await;
    assert_eq!(stale["cache"]["state"], "stale");
    assert_eq!(stale["result"]["data"], first["result"]["data"]);
    assert!(stale["result"].get("coverage").is_none());
    f.conn.execute_batch("ROLLBACK").unwrap();
    let _permits = service.hold_slots_for_test().await;
    let failed = service.requests_with_refresh(q(), true).await;
    assert_eq!(failed["result"]["errors"][0]["code"], "backend_busy");
    assert!(failed["result"].get("coverage").is_none());
    assert_eq!(service.cache.lock().unwrap().entries.len(), 1);
}
