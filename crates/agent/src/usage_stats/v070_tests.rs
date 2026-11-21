use super::tests::{Fixture, q};
use super::*;
use serde_json::{Value, json};

#[test]
fn cache_anomalies_do_not_contribute_to_usage_aggregates() {
    use super::{
        aggregate::{Aggregate, Cost, Record, normalize},
        decimal::Decimal,
        performance::{Measurements, Observation},
    };

    fn record(app: &str, semantics: i64, read: u64, write: u64, status: i64, cost: &str) -> Record {
        let (tokens, token_anomaly) = normalize(app, semantics, 100, read, write, 5);
        Record {
            at: 1,
            provider_id: "provider".into(),
            model: "model".into(),
            session_id: Some("session".into()),
            session_title: None,
            status,
            tokens,
            token_anomaly,
            costs: [
                Cost::Missing,
                Cost::Missing,
                Cost::Missing,
                Cost::Missing,
                Cost::Known(Decimal::parse(cost).unwrap()),
            ],
            measurements: Measurements {
                ttft: Observation::Excluded,
                duration: Observation::Excluded,
                speed: Observation::Excluded,
            },
        }
    }

    for app in ["codex", "gemini", "grokbuild"] {
        for (semantics, read, write) in [(0, 101, 20), (1, 101, 0), (1, 80, 21)] {
            for include_valid in [false, true] {
                let mut query = q();
                query.filters.app_type = app.into();
                let mut aggregate = Aggregate::new(&query);
                if include_valid {
                    aggregate
                        .push(record(app, 1, 10, 20, 200, "0.25"), &query)
                        .unwrap();
                }
                let anomalous = record(app, semantics, read, write, 500, "1.25");
                assert!(anomalous.token_anomaly);
                aggregate.push(anomalous, &query).unwrap();
                let data = aggregate.finish(
                    &query,
                    Default::default(),
                    RequestDetailsPage {
                        items: vec![],
                        limit: 50,
                        has_more: false,
                        next_cursor: None,
                    },
                );
                let expected_tokens = if include_valid {
                    json!({"fresh_input":70,"cache_read":10,"cache_write":20,"output":5,"total":105,"invalid_rows":1})
                } else {
                    json!({"fresh_input":0,"cache_read":0,"cache_write":0,"output":0,"total":0,"invalid_rows":1})
                };
                for totals in [
                    &data.totals,
                    &data.trend[0].totals,
                    &data.provider_groups.items[0].totals,
                    &data.model_groups.items[0].totals,
                    &data.session_groups.items[0].totals,
                ] {
                    assert_eq!(
                        serde_json::to_value(&totals.tokens).unwrap(),
                        expected_tokens,
                        "{app}, semantics={semantics}, include_valid={include_valid}"
                    );
                    assert_eq!(totals.requests, 1 + u64::from(include_valid));
                    assert_eq!(totals.success, u64::from(include_valid));
                    assert_eq!(totals.errors, 1);
                    assert_eq!(totals.costs.total.known_rows, totals.requests);
                    assert_eq!(
                        totals.costs.total.known_usd,
                        if include_valid { "1.5" } else { "1.25" }
                    );
                    assert_eq!(totals.performance.success_rows, u64::from(include_valid));
                }
            }
        }
    }
}

#[test]
fn schema_24_25_26_accept_additive_columns_and_session_identity() {
    for version in [24, 25, 26] {
        let f = Fixture::new();
        f.conn.execute_batch(&format!("PRAGMA user_version={version}; ALTER TABLE proxy_request_logs ADD COLUMN future_stat TEXT;
            ALTER TABLE proxy_request_logs ADD COLUMN session_title TEXT;")).unwrap();
        f.row("a", 1, "codex", Some("proxy"), 1, Some("0.1"));
        f.conn.execute_batch("UPDATE proxy_request_logs SET session_id='session-123456789',session_title='A reliable title'").unwrap();
        let result = serde_json::to_value(query(&f.path, &q())).unwrap();
        assert_eq!(result["availability"], "ready", "{result}");
        assert_eq!(
            result["data"]["session_groups"]["items"][0]["session_id"],
            "session-123456789"
        );
        assert_eq!(
            result["data"]["session_groups"]["items"][0]["title"],
            "A reliable title"
        );
        assert_eq!(
            result["data"]["recent_requests"]["items"][0]["request_id"],
            "a"
        );
    }
}

#[test]
fn source_isolation_and_unknown_request_metrics_remain_null() {
    let f = Fixture::new();
    f.row("proxy", 1, "codex", None, 1, None);
    f.row("import", 1, "codex", Some("codex_session"), 2, Some("90"));
    let value = serde_json::to_value(query(&f.path, &q())).unwrap();
    assert_eq!(value["data"]["totals"]["requests"], 1);
    let requests = value["data"]["recent_requests"]["items"]
        .as_array()
        .expect("request page");
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0]["request_id"], "proxy");
    for field in [
        "session_id",
        "session_title",
        "cost_usd",
        "ttft_ms",
        "latency_ms",
        "tokens_per_second",
    ] {
        assert!(requests[0][field].is_null(), "{field}");
    }
    assert_eq!(value["data"]["session_groups"]["total_groups"], 0);
}

#[test]
fn sixteen_myriad_rows_aggregate_without_materializing_request_history() {
    let f = Fixture::new();
    f.conn.execute_batch("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<160000)
        INSERT INTO proxy_request_logs(request_id,provider_id,app_type,model,input_tokens,output_tokens,cache_read_tokens,cache_creation_tokens,input_token_semantics,total_cost_usd,status_code,created_at,data_source,session_id)
        SELECT 'load-'||x,'p1','codex','m',100,5,10,20,1,'0.0001',200,1,'proxy','session-'||(x%100) FROM n").unwrap();
    let start = std::time::Instant::now();
    let v: Value = serde_json::to_value(query(&f.path, &q())).unwrap();
    assert_eq!(v["availability"], "ready", "{v}");
    assert_eq!(v["data"]["totals"]["requests"], 160000);
    assert_eq!(v["data"]["totals"]["tokens"]["total"], 16800000);
    assert_eq!(v["data"]["totals"]["costs"]["total"]["known_usd"], "16");
    assert_eq!(v["data"]["session_groups"]["total_groups"], 100);
    assert_eq!(
        v["data"]["recent_requests"]["items"]
            .as_array()
            .unwrap()
            .len(),
        50
    );
    assert!(serde_json::to_vec(&v).unwrap().len() < 300000);
    eprintln!("160000-row fixture elapsed: {:?}", start.elapsed());
}

#[test]
fn default_is_seven_shanghai_natural_days() {
    let query = service::prepare_read(&json!({}), 1_800_000_031).unwrap();
    assert_eq!((query.start_at + 8 * 3600).rem_euclid(86400), 0);
    assert_eq!((query.end_at + 8 * 3600).rem_euclid(86400), 0);
    assert_eq!(query.end_at - query.start_at, 7 * 86400);
}

#[test]
fn dashboard_cost_value_is_null_when_no_stored_cost_is_available() {
    let f = Fixture::new();
    f.row("missing", 1, "codex", None, 2, None);
    let v = serde_json::to_value(query(&f.path, &q())).unwrap();
    let amount = &v["data"]["totals"]["costs"]["total"];
    assert!(
        amount.get("value_usd").is_some(),
        "availability must be explicit, not an absent field"
    );
    assert!(amount["value_usd"].is_null());
    f.row("stored-zero", 2, "codex", None, 2, Some("0"));
    let v = serde_json::to_value(query(&f.path, &q())).unwrap();
    assert_eq!(v["data"]["totals"]["costs"]["total"]["value_usd"], "0");
}

#[test]
fn request_page_bounds_readonly_wal_and_no_private_projection() {
    let f = Fixture::new();
    f.conn
        .execute_batch("PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0")
        .unwrap();
    let before = std::fs::read(&f.path).unwrap();
    f.row("wal", 1, "codex", None, 99, None);
    let v = query_requests(&f.path, &q());
    assert_eq!(v["data"]["requests"]["items"][0]["request_id"], "wal");
    assert!(v["data"]["requests"]["items"][0]["tokens"].is_null());
    let text = v.to_string();
    for forbidden in [
        "DO_NOT_PROJECT",
        "settings_config",
        "error_message",
        "cost_multiplier",
    ] {
        assert!(!text.contains(forbidden));
    }
    assert_eq!(std::fs::read(&f.path).unwrap(), before);
    let mut query = q();
    query.request_page.limit = 201;
    assert_eq!(query_requests(&f.path, &query)["availability"], "rejected");
    query.request_page.limit = 1;
    query.request_page.cursor = Some(RequestCursor {
        created_at: 30,
        request_id: "a".into(),
    });
    assert_eq!(query_requests(&f.path, &query)["availability"], "rejected");
}

#[tokio::test]
async fn legacy_read_remains_strict_and_dashboard_and_request_caches_are_separate() {
    let f = Fixture::new();
    f.row("a", 1, "codex", None, 2, None);
    let s = service::UsageService::test_service(Some(f.path.clone()));
    let legacy = s.read(q()).await;
    assert!(legacy["query"].get("request_page").is_none());
    assert!(legacy["query"]["filters"].get("session_id").is_none());
    assert!(legacy["result"]["data"].get("recent_requests").is_none());
    assert!(
        legacy["result"]["data"]["totals"]["costs"]["total"]
            .get("value_usd")
            .is_none()
    );
    assert!(
        s.dashboard(q()).await["result"]["data"]
            .get("recent_requests")
            .is_some()
    );
    let requests = s.requests(q()).await;
    assert_eq!(
        requests["result"]["data"]["requests"]["items"][0]["request_id"],
        "a"
    );
    assert!(requests["result"]["data"].get("totals").is_none());
}

#[test]
fn request_keyset_paging_has_no_duplicates_at_equal_timestamps() {
    let f = Fixture::new();
    for id in ["a", "b", "c", "d", "e"] {
        f.row(id, 1, "codex", None, 1, None);
    }
    let mut query = q();
    query.request_page.limit = 2;
    let mut ids = vec![];
    loop {
        let page = query_requests(&f.path, &query);
        assert_eq!(page["availability"], "ready", "{page}");
        ids.extend(
            page["data"]["requests"]["items"]
                .as_array()
                .unwrap()
                .iter()
                .map(|r| r["request_id"].as_str().unwrap().to_owned()),
        );
        let cursor = &page["data"]["requests"]["next_cursor"];
        if cursor.is_null() {
            break;
        }
        query.request_page.cursor = Some(serde_json::from_value(cursor.clone()).unwrap());
    }
    assert_eq!(ids, ["e", "d", "c", "b", "a"]);
    query.filters.session_id = Some("' OR 1=1 --".into());
    assert!(
        query_requests(&f.path, &query)["data"]["requests"]["items"]
            .as_array()
            .unwrap()
            .is_empty()
    );
}

#[test]
fn optional_session_and_timing_columns_do_not_fabricate_observations() {
    let f = Fixture::new();
    f.row("a", 1, "codex", None, 2, None);
    f.conn.execute_batch("PRAGMA user_version=25; ALTER TABLE proxy_request_logs DROP COLUMN session_id;
        ALTER TABLE proxy_request_logs DROP COLUMN latency_ms; ALTER TABLE proxy_request_logs DROP COLUMN first_token_ms;
        ALTER TABLE proxy_request_logs DROP COLUMN is_streaming").unwrap();
    let v = serde_json::to_value(query(&f.path, &q())).unwrap();
    assert_eq!(v["availability"], "ready");
    assert!(v["data"]["totals"]["performance"]["ttft"]["average_ms"].is_null());
    assert_eq!(v["data"]["session_groups"]["total_groups"], 0);
}
