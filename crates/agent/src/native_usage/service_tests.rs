use super::*;
use crate::native_usage::service::{NativeUsage, Source, prepare_read, validate_sources};
use crate::usage_stats::{DataSource, StatusFilter, service::UsageService};

struct Bench {
    dir: tempfile::TempDir,
    home: PathBuf,
    ledger: PathBuf,
    service: NativeUsage,
    cc: UsageService,
}
fn bench(pass_bytes: u64) -> Bench {
    let dir = tempfile::tempdir().unwrap();
    let home = dir.path().join("codex");
    std::fs::create_dir_all(home.join("sessions")).unwrap();
    let ledger = dir.path().join("usage-ledger.sqlite");
    let service = NativeUsage::with_budget(
        ledger.clone(),
        [("store".to_owned(), home.clone())],
        pass_bytes,
        Duration::from_millis(40),
    );
    // A synthetic CC Switch database with one proxied request and one imported Codex session row.
    let cc_path = dir.path().join("cc-switch.db");
    rusqlite::Connection::open(&cc_path)
        .unwrap()
        .execute_batch(&format!(
            "PRAGMA user_version=27;
            CREATE TABLE proxy_request_logs(request_id TEXT PRIMARY KEY,provider_id TEXT,app_type TEXT,model TEXT,
                input_tokens INTEGER,output_tokens INTEGER,cache_read_tokens INTEGER,cache_creation_tokens INTEGER,input_token_semantics INTEGER,
                input_cost_usd TEXT,output_cost_usd TEXT,cache_read_cost_usd TEXT,cache_creation_cost_usd TEXT,total_cost_usd TEXT,
                status_code INTEGER,created_at INTEGER,data_source TEXT,session_id TEXT);
            INSERT INTO proxy_request_logs VALUES('proxied','p','codex','m',100,5,10,20,2,NULL,NULL,NULL,NULL,'0.1',200,{},'proxy',NULL);
            INSERT INTO proxy_request_logs VALUES('imported','p','codex','m',1000,50,0,0,2,NULL,NULL,NULL,NULL,'0.5',200,{},'codex_session','t1');",
            T0 + 1,
            T0 + 2
        ))
        .unwrap();
    Bench {
        cc: UsageService::test_service(Some(cc_path)),
        dir,
        home,
        ledger,
        service,
    }
}
fn around_now() -> UsageQuery {
    let now = crate::storage::now() as i64;
    let start = now - now.rem_euclid(3600) - 3600;
    UsageQuery {
        start_at: start,
        end_at: start + 3 * 3600,
        bucket_edges: (0..=3).map(|h| start + h * 3600).collect(),
        ..window()
    }
}
fn totals(v: &Value) -> (u64, u64) {
    let totals = &v["result"]["data"]["totals"];
    (
        totals["requests"].as_u64().unwrap(),
        totals["tokens"]["total"].as_u64().unwrap(),
    )
}
fn codes(v: &Value) -> Vec<&str> {
    v.as_array()
        .unwrap()
        .iter()
        .map(|e| e["code"].as_str().unwrap())
        .collect()
}

#[tokio::test]
async fn live_events_reach_reads_and_cached_results_follow_the_ledger() {
    let b = bench(u64::MAX);
    let query = around_now();
    let read = |refresh| b.service.read(&b.cc, Source::Native, query.clone(), refresh);
    let empty = read(false).await;
    assert_eq!(empty["schema"], 2);
    assert_eq!(empty["source"], "native_codex");
    assert_eq!(empty["result"]["availability"], "ready");
    assert_eq!(totals(&empty), (0, 0));
    assert_eq!(empty["cache"]["state"], "fresh");
    assert_eq!(empty["cache"]["ttl_seconds"], 60);
    assert_eq!(empty["query"], serde_json::to_value(&query).unwrap());
    // Unrelated and malformed notifications cost nothing and store nothing.
    b.service.live("store", &b.home, &json!({"method":"item/completed","params":{"threadId":"t"}}));
    b.service.live("store", &b.home, &json!({"method":"thread/tokenUsage/updated","params":{"threadId":"t","tokenUsage":{"total":{"inputTokens":-1}}}}));
    b.service.live("store", &b.home, &event("t", "u1", (100, 0, 10), (100, 0, 10)));
    b.service.settle().await;
    // No refresh flag: the ledger changed, so the cached empty result is not served.
    let one = read(false).await;
    assert_eq!(totals(&one), (1, 110));
    let item = &one["result"]["data"]["recent_requests"]["items"][0];
    assert_eq!((item["session_id"].clone(), item["turn_id"].clone(), item["observed_by"].clone(), item["model"].clone()), (json!("t"), json!("u1"), json!("live"), json!("unknown")));
    // Unchanged ledger: served from cache with the original sample time.
    let cached = read(false).await;
    assert_eq!(cached["result"], one["result"]);
    assert_eq!(cached["cache"]["sampled_at"], one["cache"]["sampled_at"]);
    // Replays, then a real continuation.
    for _ in 0..3 {
        b.service.live("store", &b.home, &event("t", "u1", (100, 0, 10), (100, 0, 10)));
    }
    b.service.settle().await;
    assert_eq!(totals(&read(false).await), (1, 110));
    b.service.live("store", &b.home, &event("t", "u2", (250, 100, 30), (150, 100, 20)));
    b.service.settle().await;
    assert_eq!(totals(&read(false).await), (2, 280));
    assert_eq!(totals(&read(true).await), (2, 280));
    let sources = b.service.sources(&b.cc, false).await;
    let native = &sources["sources"][0];
    assert_eq!(native["coverage"]["live_rejected"], 1);
    assert_eq!(native["coverage"]["sessions"], 1);
    // A second service on the same ledger (agent restart) sees the same totals.
    let restarted = NativeUsage::new(b.ledger.clone(), [("store".to_owned(), b.home.clone())]);
    assert_eq!(totals(&restarted.read(&b.cc, Source::Native, query.clone(), false).await), (2, 280));
    restarted.live("store", &b.home, &event("t", "u2", (250, 100, 30), (150, 100, 20)));
    restarted.settle().await;
    assert_eq!(totals(&restarted.read(&b.cc, Source::Native, query.clone(), true).await), (2, 280));
}

#[tokio::test]
async fn reads_answer_promptly_while_history_import_catches_up() {
    let b = bench(4096);
    let mut lines = vec![meta("big", json!({})), turn("u", "m")];
    for i in 1..=300u64 {
        lines.push(count(T0 + i as i64, (10 * i, 0, i), (10, 0, 1)));
    }
    write(&b.home, "big", &lines);
    let started = std::time::Instant::now();
    let first = b.service.read(&b.cc, Source::Native, window(), false).await;
    assert!(started.elapsed() < Duration::from_secs(3));
    assert_eq!(first["result"]["availability"], "ready");
    let import = &first["result"]["coverage"]["history_import"];
    assert_eq!((import["state"].clone(), import["complete"].clone(), import["files_pending"].clone()), (json!("catching_up"), json!(false), json!(1)));
    assert!(import["bytes_pending"].as_u64().unwrap() > 0);
    assert!(codes(&first["result"]["errors"]).contains(&"history_import_incomplete"));
    assert!(totals(&first).0 < 300);
    let sources = b.service.sources(&b.cc, false).await;
    assert_eq!(sources["sources"][0]["coverage"]["history_import"]["complete"], false);
    assert!(codes(&sources["sources"][0]["errors"]).contains(&"history_import_incomplete"));
    b.service.settle().await;
    let done = b.service.read(&b.cc, Source::Native, window(), false).await;
    assert_eq!(totals(&done), (300, 3300));
    let import = &done["result"]["coverage"]["history_import"];
    assert_eq!((import["state"].clone(), import["complete"].clone(), import["files"].clone(), import["files_pending"].clone()), (json!("complete"), json!(true), json!(1), json!(0)));
    assert_eq!(codes(&done["result"]["errors"]), ["cost_not_provided", "incomplete_coverage"]);
    let requests = b.service.requests(&b.cc, Source::Native, window(), false).await;
    assert_eq!(requests["schema"], 2);
    assert!(requests["result"].get("coverage").is_none());
    assert_eq!(requests["result"]["data"]["requests"]["items"].as_array().unwrap().len(), 50);
    assert_eq!(requests["result"]["data"]["requests"]["has_more"], true);
    let sources = b.service.sources(&b.cc, true).await;
    let native = &sources["sources"][0];
    assert_eq!((native["id"].clone(), native["kind"].clone(), native["availability"].clone(), native["schema_version"].clone()), (json!("native_codex"), json!("native_codex"), json!("ready"), json!(1)));
    assert_eq!(native["apps"], json!(["codex"]));
    assert_eq!(native["capabilities"], json!({"cost":false,"performance":false,"status":false,"providers":true,"models":true,"sessions":true,"requests":true}));
    assert_eq!(native["coverage"]["first_observed_at"], T0 + 1);
    assert_eq!(native["coverage"]["last_observed_at"], T0 + 300);
    assert_eq!(native["coverage"]["sessions"], 1);
    assert_eq!(native["coverage"]["history_import"]["complete"], true);
    assert_eq!(codes(&native["errors"]), ["cost_not_provided", "incomplete_coverage"]);
}

#[tokio::test]
async fn cc_switch_through_v2_is_the_v1_result_and_never_mixes_with_native() {
    let b = bench(u64::MAX);
    let mut imported = window();
    imported.filters.data_source = DataSource::CodexSession;
    for query in [window(), imported.clone()] {
        let v1 = b.cc.dashboard(query.clone()).await;
        let v2 = b.service.read(&b.cc, Source::CcSwitch, query.clone(), false).await;
        assert_eq!((v2["schema"].clone(), v2["source"].clone()), (json!(2), json!("cc_switch")));
        assert_eq!(v2["result"], v1["result"]);
        assert_eq!(v2["query"], v1["query"]);
        assert_eq!(v2["result"]["data"]["totals"]["requests"], 1);
        assert_eq!(v2.as_object().unwrap().len(), 5);
        let v1 = b.cc.requests(query.clone()).await;
        let v2 = b.service.requests(&b.cc, Source::CcSwitch, query, false).await;
        assert_eq!(v2["result"], v1["result"]);
    }
    // CC Switch's imported Codex session never shows up as native usage.
    let native = b.service.read(&b.cc, Source::Native, window(), false).await;
    assert_eq!(totals(&native), (0, 0));
    write(&b.home, "t1", &[meta("t1", json!({})), turn("u1", "m1"), count(T0 + 10, (100, 0, 10), (100, 0, 10))]);
    b.service.read(&b.cc, Source::Native, window(), true).await;
    b.service.settle().await;
    let native = b.service.read(&b.cc, Source::Native, window(), true).await;
    assert_eq!(totals(&native), (1, 110));
    assert!(native["result"]["data"]["totals"]["costs"].is_null());
    // ...and native usage never adds to either CC Switch view of the same session.
    let cc = b.service.read(&b.cc, Source::CcSwitch, imported, true).await;
    assert_eq!(cc["result"]["data"]["totals"]["requests"], 1);
    assert_eq!(cc["result"]["data"]["totals"]["tokens"]["total"], 1050);
    assert_eq!(cc["result"]["data"]["totals"]["costs"]["total"]["known_usd"], "0.5");
    assert_eq!(cc["result"]["source"]["data_source"], "codex_session");
    // The source list keeps every v1 field of the CC Switch entry.
    let v1 = b.cc.sources().await;
    let v2 = b.service.sources(&b.cc, false).await;
    assert_eq!(v2["schema"], 2);
    let listed: Vec<_> = v2["sources"].as_array().unwrap().iter().map(|s| s["id"].clone()).collect();
    assert_eq!(listed, [json!("native_codex"), json!("cc_switch")]);
    for (key, value) in v1["sources"][0].as_object().unwrap() {
        assert_eq!(&v2["sources"][1][key], value, "{key}");
    }
    assert_eq!(v2["sources"][1]["capabilities"], json!({"cost":true,"performance":true,"status":true,"providers":true,"models":true,"sessions":true,"requests":true}));
    assert_eq!(v2["cache"]["ttl_seconds"], v1["cache"]["ttl_seconds"]);
    drop(b.dir);
}

#[test]
fn v2_reads_require_a_known_source_and_reject_filters_native_usage_lacks() {
    let now = 1_800_000_031;
    let q = serde_json::to_value(window()).unwrap();
    let (source, query, refresh) = prepare_read(&json!({"source":"native_codex"}), now).unwrap();
    assert_eq!((source, refresh, query.bucket_edges.len()), (Source::Native, false, 8));
    let (source, query, refresh) = prepare_read(&json!({"source":"cc_switch","source_id":"cc_switch","query":q,"refresh":true}), now).unwrap();
    assert_eq!((source, refresh, query.start_at), (Source::CcSwitch, true, T0));
    assert!(prepare_read(&json!({"source":"native_codex","source_id":"native_codex","query":q}), now).is_ok());
    for bad in [
        json!({}),
        json!({"query":q}),
        json!({"source":"all","query":q}),
        json!({"source":"combined"}),
        json!({"source":"codex_session"}),
        json!({"source":null}),
        json!({"source":["native_codex"]}),
        json!({"source":"native_codex","source_id":"cc_switch"}),
        json!({"source":"cc_switch","source_id":"native_codex"}),
        json!({"source":"native_codex","path":"C:/secret"}),
        json!({"source":"native_codex","refresh":"yes"}),
        json!({"source":"native_codex","query":{"start_at":0}}),
        json!(null),
    ] {
        assert!(prepare_read(&bad, now).is_err(), "{bad}");
    }
    let edit = |edit: &dyn Fn(&mut UsageQuery)| {
        let mut query = window();
        edit(&mut query);
        serde_json::to_value(query).unwrap()
    };
    let inapplicable = [
        edit(&|q| q.filters.data_source = DataSource::CodexSession),
        edit(&|q| q.filters.status = StatusFilter::Success),
        edit(&|q| q.filters.status = StatusFilter::Error),
        edit(&|q| q.filters.app_type = "claude".into()),
        edit(&|q| {
            q.request_page.cursor = Some(RequestCursor {
                created_at: T0 + 5,
                request_id: "not-a-ledger-id".into(),
            })
        }),
    ];
    for query in inapplicable {
        // The same query stays valid for CC Switch, where those dimensions exist.
        assert!(prepare_read(&json!({"source":"cc_switch","query":query}), now).is_ok());
        assert!(prepare_read(&json!({"source":"native_codex","query":query}), now).is_err(), "{query}");
    }
    let applicable = edit(&|q| {
        q.filters.model = Some("m".into());
        q.filters.session_id = Some("t".into());
        q.filters.provider_id = Some("custom".into());
        q.request_page.cursor = Some(RequestCursor {
            created_at: T0 + 5,
            request_id: "42".into(),
        });
    });
    assert!(prepare_read(&json!({"source":"native_codex","query":applicable}), now).is_ok());
    assert!(validate_sources(&json!({"refresh":true})) && !validate_sources(&json!({"source":"x"})));
}

#[tokio::test]
async fn unsupported_or_busy_ledger_is_reported_without_touching_it() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("usage-ledger.sqlite");
    rusqlite::Connection::open(&path)
        .unwrap()
        .execute_batch("CREATE TABLE events(future TEXT); PRAGMA user_version=9;")
        .unwrap();
    let before = std::fs::read(&path).unwrap();
    let cc = UsageService::test_service(Some(dir.path().join("missing.db")));
    let service = NativeUsage::new(path.clone(), []);
    service.live("store", dir.path(), &event("t", "u", (100, 0, 10), (100, 0, 10)));
    service.settle().await;
    let read = service.read(&cc, Source::Native, window(), false).await;
    assert_eq!(read["result"]["availability"], "unsupported_schema");
    assert_eq!(codes(&read["result"]["errors"]), ["unsupported_schema"]);
    assert!(read["result"]["data"].is_null());
    assert_eq!(read["result"]["coverage"]["scope"], "native_ledger");
    assert_eq!(read["result"]["capabilities"]["cost"], false);
    assert_eq!((read["cache"]["state"].clone(), read["cache"]["sampled_at"].clone()), (json!("unavailable"), Value::Null));
    let requests = service.requests(&cc, Source::Native, window(), false).await;
    assert_eq!(requests["result"]["availability"], "unsupported_schema");
    assert!(requests["result"]["data"].is_null());
    let sources = service.sources(&cc, false).await;
    let native = &sources["sources"][0];
    assert_eq!((native["availability"].clone(), native["schema_version"].clone()), (json!("unsupported_schema"), Value::Null));
    assert_eq!(native["coverage"]["live_dropped"], 1);
    assert_eq!(sources["sources"][1]["availability"], "missing");
    assert_eq!(std::fs::read(&path).unwrap(), before);
    // Both blocking slots taken: the read fails fast instead of queueing.
    let b = bench(u64::MAX);
    let permits = b.service.hold_slots_for_test().await;
    let busy = b.service.read(&b.cc, Source::Native, window(), false).await;
    assert_eq!(busy["result"]["availability"], "unavailable");
    assert_eq!(codes(&busy["result"]["errors"]), ["backend_busy"]);
    drop(permits);
    let ready = b.service.read(&b.cc, Source::Native, window(), false).await;
    assert_eq!(ready["result"]["availability"], "ready");
}
