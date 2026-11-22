use super::super::tests::{fixture, peer};
use super::*;
use crate::error::code;
use crate::native_usage::tests::{count, event, meta, turn};
use serde_json::json;

fn around(now: i64) -> Value {
    let start = now - now.rem_euclid(3600) - 3600;
    json!({"start_at":start,"end_at":start+3*3600,"bucket_edges":[start,start+3600,start+7200,start+3*3600]})
}
fn totals(v: &Value) -> (u64, u64) {
    let totals = &v["result"]["data"]["totals"];
    (
        totals["requests"].as_u64().unwrap(),
        totals["tokens"]["total"].as_u64().unwrap(),
    )
}
fn params(method: &str) -> Value {
    if method == "usage/v2/sources" {
        json!({})
    } else {
        json!({"source":"native_codex"})
    }
}
const METHODS: [&str; 3] = ["usage/v2/sources", "usage/v2/read", "usage/v2/requests/read"];

#[tokio::test]
async fn hello_announces_usage_v2_next_to_the_unchanged_v1_markers() {
    let (_dir, b) = fixture().await;
    let hello = b.hello().await;
    let capabilities = hello["capabilities"].as_array().unwrap();
    for marker in [
        "usage.v2",
        "usage/v2/sources",
        "usage/v2/read",
        "usage/v2/requests/read",
        "usage.v1",
        "usage/sources",
        "usage/read",
        "usage/dashboard/read",
        "usage/requests/read",
        "usage/refresh",
    ] {
        assert!(capabilities.contains(&json!(marker)), "{marker}");
    }
    assert_eq!(crate::native_usage::CAPABILITY, "usage.v2");
}

#[tokio::test]
async fn native_events_and_history_feed_only_the_native_source() {
    let (dir, b) = fixture().await;
    let mut native = peer(&b, "a", "desktop").await;
    let mut events = b.subscribe();
    let now = crate::storage::now() as i64;
    let query = around(now);
    let read = |source: &'static str, method: &'static str| {
        let (b, query) = (b.clone(), query.clone());
        async move {
            b.request(
                "phone",
                method,
                json!({"source":source,"query":query,"refresh":true}),
            )
            .await
            .unwrap()
        }
    };
    // Delivery to phones is unchanged; the ledger only listens.
    for (total, last) in [
        ((100, 0, 10), (100, 0, 10)),
        ((250, 100, 30), (150, 100, 20)),
        ((250, 100, 30), (150, 100, 20)),
    ] {
        native.send(event("t", "u1", total, last)).await;
        loop {
            let delivered = events.recv().await.unwrap();
            if delivered["params"]["event"]["method"] == "thread/tokenUsage/updated" {
                break;
            }
        }
    }
    b.native_usage.settle().await;
    let live = read("native_codex", "usage/v2/read").await;
    assert_eq!(live["schema"], 2);
    assert_eq!(live["source"], "native_codex");
    assert_eq!(totals(&live), (2, 280));
    assert!(live["result"]["data"]["totals"]["costs"].is_null());
    assert!(
        live["result"]["errors"]
            .as_array()
            .unwrap()
            .iter()
            .any(|e| e["code"] == "cost_not_provided")
    );
    // The runtime's own rollout holds the same two requests and one the agent missed.
    let day = dir.path().join("sessions/2027/01/15");
    std::fs::create_dir_all(&day).unwrap();
    let lines = [
        meta("t", json!({})),
        turn("u1", "model-x"),
        count(now - 30, (100, 0, 10), (100, 0, 10)),
        count(now - 20, (250, 100, 30), (150, 100, 20)),
        count(now - 10, (300, 100, 40), (50, 0, 10)),
    ];
    std::fs::write(
        day.join("rollout-2027-01-15T08-00-00-t.jsonl"),
        lines.iter().map(|l| format!("{l}\n")).collect::<String>(),
    )
    .unwrap();
    read("native_codex", "usage/v2/read").await;
    b.native_usage.settle().await;
    let merged = read("native_codex", "usage/v2/read").await;
    assert_eq!(totals(&merged), (3, 340));
    assert_eq!(
        merged["result"]["coverage"]["history_import"]["state"],
        "complete"
    );
    assert_eq!(
        merged["result"]["data"]["model_groups"]["items"][0]["model"],
        "model-x"
    );
    assert_eq!(
        merged["result"]["data"]["session_groups"]["items"][0]["session_id"],
        "t"
    );
    // A reconnecting runtime replays its last value.
    native
        .send(event("t", "u9", (300, 100, 40), (50, 0, 10)))
        .await;
    events.recv().await.unwrap();
    b.native_usage.settle().await;
    assert_eq!(
        totals(&read("native_codex", "usage/v2/read").await),
        (3, 340)
    );
    let requests = read("native_codex", "usage/v2/requests/read").await;
    let items = requests["result"]["data"]["requests"]["items"]
        .as_array()
        .unwrap();
    assert_eq!(items.len(), 3);
    assert!(items[0]["cost_usd"].is_null() && items[0]["status_code"].is_null());
    let sources = b
        .request("phone", "usage/v2/sources", json!({}))
        .await
        .unwrap();
    assert_eq!(sources["schema"], 2);
    assert_eq!(sources["sources"][0]["id"], "native_codex");
    assert_eq!(sources["sources"][0]["coverage"]["sessions"], 1);
    assert_eq!(
        sources["sources"][0]["coverage"]["history_import"]["complete"],
        true
    );
    assert_eq!(sources["sources"][1]["id"], "cc_switch");
    // CC Switch through v2 is the v1 dashboard result, and sees none of the above.
    let path = dir.path().join("test-cc-switch.db");
    rusqlite::Connection::open(&path).unwrap().execute_batch(&format!("PRAGMA user_version=27;
        CREATE TABLE proxy_request_logs(request_id TEXT PRIMARY KEY,provider_id TEXT,app_type TEXT,model TEXT,
            input_tokens INTEGER,output_tokens INTEGER,cache_read_tokens INTEGER,cache_creation_tokens INTEGER,input_token_semantics INTEGER,
            input_cost_usd TEXT,output_cost_usd TEXT,cache_read_cost_usd TEXT,cache_creation_cost_usd TEXT,total_cost_usd TEXT,
            status_code INTEGER,created_at INTEGER,data_source TEXT);
        INSERT INTO proxy_request_logs VALUES('a','p','codex','m',100,5,10,20,2,NULL,NULL,NULL,NULL,'0.1',200,{now},'proxy');")).unwrap();
    let v2 = read("cc_switch", "usage/v2/read").await;
    let v1 = b
        .usage_with_service(
            "phone",
            "usage/dashboard/read",
            &json!({"query":query}),
            &UsageService::test_service(Some(path)),
        )
        .await
        .unwrap();
    assert_eq!(v1["schema"], 1);
    assert_eq!(v2["schema"], 2);
    assert_eq!(v2["source"], "cc_switch");
    assert_eq!(v2["result"], v1["result"]);
    assert_eq!(v2["query"], v1["query"]);
    assert_eq!(totals(&v2), (1, 135));
}

#[tokio::test]
async fn v2_shares_paired_read_authorization_and_parameter_validation() {
    let (dir, b) = fixture().await;
    let service = UsageService::test_service(Some(dir.path().join("missing.db")));
    for method in METHODS {
        for result in [
            b.request("unknown", method, params(method)).await,
            b.usage_v2_request("unknown", method, &params(method)).await,
            b.usage_v2_with_service("unknown", method, &params(method), &service)
                .await,
        ] {
            assert_eq!(code(&result.unwrap_err()), Code::Revoked);
        }
        let private = json!({"source":"native_codex","path":"C:/private"});
        assert_eq!(
            code(&b.request("phone", method, private).await.unwrap_err()),
            Code::InvalidParams
        );
        assert!(b.request("phone", method, params(method)).await.is_ok());
    }
    let filters = |source: &str, status: &str| {
        json!({"source":"native_codex","query":{"start_at":0,"end_at":30,"bucket_edges":[0,30],
            "filters":{"app_type":"codex","data_source":source,"provider_id":null,"model":null,"status":status}}})
    };
    for bad in [
        json!({}),
        json!({"source":"all"}),
        json!({"source":"codex_session"}),
        json!({"source":"native_codex","source_id":"cc_switch"}),
        json!({"source":"native_codex","query":{"start_at":0}}),
        filters("codex_session", "all"),
        filters("proxy", "error"),
    ] {
        for method in &METHODS[1..] {
            assert_eq!(
                code(&b.request("phone", method, bad.clone()).await.unwrap_err()),
                Code::InvalidParams,
                "{bad}"
            );
        }
    }
    assert!(
        b.request("phone", "usage/v2/read", filters("proxy", "all"))
            .await
            .is_ok()
    );
    assert_eq!(
        code(
            &b.usage_v2_with_service("phone", "usage/v2/write", &json!({}), &service)
                .await
                .unwrap_err()
        ),
        Code::Unsupported
    );
    // A response that completes after revocation is suppressed.
    for method in METHODS {
        b.state
            .lock()
            .await
            .devices
            .add("late".into(), "Late".into(), "secret", crate::storage::now())
            .unwrap();
        let _permits = b.native_usage.hold_slots_for_test().await;
        // Bypass the cache so the read has to wait for a worker slot.
        let mut request = params(method);
        request["refresh"] = json!(true);
        let read = b.usage_v2_with_service("late", method, &request, &service);
        let revoke = async {
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            b.state.lock().await.devices.devices.remove("late");
        };
        let (result, _) = tokio::join!(read, revoke);
        assert_eq!(code(&result.unwrap_err()), Code::Revoked);
    }
}
