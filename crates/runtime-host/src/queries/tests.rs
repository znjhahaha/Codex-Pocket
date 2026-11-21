use super::*;

fn request(id: &str) -> Value {
    json!({"id":id,"method":"account/read","params":{"refreshToken":false}})
}

#[test]
fn receipt_capacity_read_classification_is_explicit_and_parameter_sensitive() {
    for (method, params) in [
        ("account/read", json!({"refreshToken":false})),
        ("config/read", json!({"includeLayers":false})),
        ("model/list", json!({"limit":100})),
        ("thread/list", json!({"limit":20,"cursor":null})),
        ("thread/loaded/list", json!({})),
        ("thread/read", json!({"threadId":"t","includeTurns":true})),
        (
            "thread/turns/list",
            json!({"threadId":"t","itemsView":"full"}),
        ),
        ("thread/items/list", json!({"threadId":"t","turnId":"u"})),
    ] {
        assert!(
            read_only(&json!({"method":method,"params":params})),
            "{method}"
        );
    }
    for (method, params) in [
        ("account/read", json!({"refreshToken":true})),
        ("account/read", json!({})),
        ("account/read", json!({"refreshToken":"false"})),
        (
            "account/read",
            json!({"refreshToken":false,"newOption":true}),
        ),
        ("thread/read", json!({"threadId":"t","forceResume":true})),
        ("model/list", Value::Null),
        ("thread/start", json!({})),
        ("thread/resume", json!({})),
        ("turn/start", json!({})),
        ("account/login/start", json!({})),
        ("config/value/write", json!({})),
        ("new/read", json!({})),
    ] {
        assert!(
            !read_only(&json!({"method":method,"params":params})),
            "{method}"
        );
    }
}

#[test]
fn receipt_capacity_read_cache_recycles_completed_queries_with_bounded_bytes() {
    let mut queries = Queries::default();
    for n in 0..4200 {
        let req = request(&format!("poll-{n}"));
        let admitted = queries.reserve(&req, "generation").unwrap();
        assert!(!admitted.durable);
        let reply = json!({"id":admitted.native["id"],"result":{"account":{"type":"apiKey"}}});
        let projected = queries.observe(reply);
        assert_eq!(projected["id"], req["id"]);
        assert!(queries.entries.len() <= MAX_QUERIES);
    }
    assert!(queries.receipt("s:poll-0").is_none());
    assert_eq!(queries.receipt("s:poll-4199").unwrap().state, "completed");
    let req = request("large-result");
    let admitted = queries.reserve(&req, "generation").unwrap();
    queries.observe(json!({"id":admitted.native["id"],"result":"x".repeat(MAX_REPLY_BYTES)}));
    assert_eq!(
        queries.receipt("s:large-result").unwrap().response.unwrap()["error"]["code"],
        "result_evicted"
    );
    let used: usize = queries
        .entries
        .values()
        .filter_map(|q| q.reply.as_ref())
        .map(|r| serde_json::to_vec(r).unwrap().len())
        .sum();
    assert!(used <= MAX_REPLY_BYTES);
}

#[test]
fn receipt_capacity_pending_reads_are_not_evicted_or_reissued() {
    let mut queries = Queries::default();
    for n in 0..MAX_QUERIES {
        queries
            .reserve(&request(&format!("pending-{n}")), "generation")
            .unwrap();
    }
    assert_eq!(
        queries
            .reserve(&request("overflow"), "generation")
            .err()
            .unwrap()
            .to_string(),
        "read_capacity"
    );
    let duplicate = queries
        .reserve(&request("pending-0"), "generation")
        .unwrap();
    assert_eq!(duplicate.receipt.unwrap().state, "unknown");
    let mut changed = request("pending-0");
    changed["method"] = json!("model/list");
    changed["params"] = json!({});
    assert!(queries.reserve(&changed, "generation").is_err());
}

#[test]
fn receipt_capacity_late_read_reply_cannot_answer_a_reused_id() {
    let mut queries = Queries::default();
    let original = queries.reserve(&request("reused"), "generation").unwrap();
    queries.observe(json!({"id":original.native["id"],"result":{}}));
    for n in 0..MAX_QUERIES {
        let prepared = queries
            .reserve(&request(&format!("next-{n}")), "generation")
            .unwrap();
        queries.observe(json!({"id":prepared.native["id"],"result":{}}));
    }
    let current = queries.reserve(&request("reused"), "generation").unwrap();
    assert_ne!(current.native["id"], original.native["id"]);
    let late = queries.observe(json!({"id":original.native["id"],"result":{"old":true}}));
    assert_ne!(late["id"], "reused");
    assert_eq!(queries.receipt("s:reused").unwrap().state, "unknown");
}
