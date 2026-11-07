use super::*;
use serde_json::json;
#[test]
fn remaining_only_has_no_invented_percentage() {
    let items = normalize(&json!({"remaining":12.0,"unit":"USD"})).unwrap();
    assert_eq!(items.len(), 1);
    assert_eq!(items[0]["remaining"], 12.0);
    assert!(items[0]["usedPercent"].is_null());
    assert!(items[0]["total"].is_null());
}
#[test]
fn rejects_malformed_and_invalid_usage() {
    assert!(normalize(&json!({"used":"secret"})).is_err());
    assert!(normalize(&json!({"isValid":false,"remaining":99})).is_err());
}

#[test]
fn sandbox_bounds_time_memory_and_host_access() {
    for code in [
        "({",
        "({request:process.env,extractor:r=>r})",
        "({request:require('fs'),extractor:r=>r})",
        "({request:fetch('https://example.org'),extractor:r=>r})",
        "({request:{x:new Array(30000000).fill('x')},extractor:r=>r})",
    ] {
        assert!(sandbox::evaluate(code, None).is_err());
    }
    let start = Instant::now();
    assert_eq!(
        sandbox::evaluate("while(true){}", None),
        Err(Failure::Timeout)
    );
    assert!(start.elapsed() < Duration::from_secs(3));
    assert!(
        sandbox::evaluate(
            "({request:{url:'x'},extractor:()=>{while(true){}}})",
            Some("{}")
        )
        .is_err()
    );
}
#[test]
fn schema_templates_and_extractor_array_work() {
    let code = r#"({request:{url:'{{baseUrl}}/quota',method:'GET',headers:{Authorization:'Bearer {{apiKey}}'}},extractor:r=>[{planName:'Wallet',remaining:r.balance,unit:'USD'},{total:100,used:25,unit:'requests'}]})"#;
    let request = sandbox::evaluate(code, None).unwrap();
    assert_eq!(request["headers"]["Authorization"], "Bearer {{apiKey}}");
    let result = sandbox::evaluate(code, Some(r#"{"balance":12.5}"#)).unwrap();
    let items = normalize(&result).unwrap();
    assert!(items[0]["usedPercent"].is_null());
    assert_eq!(items[1]["usedPercent"], 25.0);
    assert_eq!(items[1]["remaining"], 75.0);
}
#[test]
fn strict_rpc_fields_and_unix_millisecond_times() {
    assert!(validate("quota/sources", &json!({"runtimeId":"r"})).is_ok());
    for bad in [
        json!({"runtimeId":"r","url":"https://x"}),
        json!({"runtimeId":"r","script":"code"}),
    ] {
        assert!(validate("quota/sources", &bad).is_err());
    }
    assert!(
        validate(
            "quota/read",
            &json!({"runtimeId":"r","sourceId":uuid::Uuid::new_v4(),"refresh":true})
        )
        .is_ok()
    );
    assert!(
        validate(
            "quota/read",
            &json!({"runtimeId":"r","sourceId":"provider-db-id"})
        )
        .is_err()
    );
    let items=official(&json!({"rateLimits":{"primary":{"usedPercent":30,"resetsAt":1794000000},"credits":{"balance":"12.5"}}})).unwrap();
    assert_eq!(items[0]["resetsAt"], 1794000000000_u64);
    assert!(items[1]["usedPercent"].is_null());
    assert!(now_ms() > 1_000_000_000_000);
}
fn fixture_db(path: &std::path::Path) {
    let db = rusqlite::Connection::open(path).unwrap();
    db.execute_batch("CREATE TABLE providers(id TEXT, app_type TEXT, name TEXT, meta TEXT, settings_config TEXT);").unwrap();
    let meta = json!({"usage_script":{"enabled":true,"language":"javascript","code":"({request:{url:'{{baseUrl}}/quota',method:'GET'},extractor:r=>r})","accessToken":"synthetic-private-token","autoQueryInterval":2}});
    let settings = json!({"auth":{"OPENAI_API_KEY":"synthetic-key"},"config":"model_provider='provider-id'\n[model_providers.provider-id]\nbase_url='https://quota.example/v1'"});
    for (id, app, enabled) in [
        ("provider-id", "codex", true),
        ("disabled", "codex", false),
        ("other-app", "claude", true),
    ] {
        let mut m = meta.clone();
        m["usage_script"]["enabled"] = json!(enabled);
        db.execute(
            "INSERT INTO providers VALUES(?1,?2,?3,?4,?5)",
            rusqlite::params![
                id,
                app,
                "Test Provider",
                m.to_string(),
                if id == "disabled" {
                    "{}".to_owned()
                } else {
                    settings.to_string()
                }
            ],
        )
        .unwrap();
    }
}
#[test]
fn database_is_readonly_filters_enabled_codex_and_does_not_expose_secrets() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("cc-switch.db");
    fixture_db(&path);
    let before = std::fs::read(&path).unwrap();
    let loaded = sources::load(&path).unwrap();
    assert_eq!(loaded.len(), 2);
    assert_eq!(loaded[0].interval, 120);
    assert_eq!(loaded[0].identity.as_ref().unwrap().0, "provider-id");
    assert_eq!(before, std::fs::read(&path).unwrap());
    assert_eq!(std::fs::read_dir(temp.path()).unwrap().count(), 1);
    assert!(
        sources::load(&temp.path().join("absent.db"))
            .unwrap()
            .is_empty()
    );
    assert!(!temp.path().join("absent.db").exists());
}
async fn mock_runtime() -> (
    crate::native::Connected,
    crate::native::test_peer::Peer,
    tempfile::TempDir,
) {
    let temp = tempfile::tempdir().unwrap();
    let (tx, _rx) = tokio::sync::mpsc::channel(8);
    let (mut c, p) = crate::native::test_peer::connect("r", "cli", temp.path(), tx).await;
    c.capabilities = Capabilities(std::collections::BTreeMap::from([(
        "account/rateLimits/read".into(),
        Default::default(),
    )]));
    (c, p, temp)
}
#[tokio::test]
async fn single_flight_cooldowns_stale_and_updates() {
    let (c, mut peer, _temp) = mock_runtime().await;
    let source = Arc::new(Source {
        id: uuid::Uuid::new_v4().to_string(),
        label: "Codex account".into(),
        configured: true,
        kind: "official",
        interval: 60,
        provider: None,
        rpc: Some(c.rpc),
        cache: Mutex::default(),
        flight: AsyncMutex::new(()),
    });
    let permits = Arc::new(Semaphore::new(4));
    let run = |s: Arc<Source>, p: Arc<Semaphore>| {
        tokio::spawn(async move { s.read("r", true, &p).await })
    };
    let a = run(source.clone(), permits.clone());
    let b = run(source.clone(), permits.clone());
    let req = peer.recv().await;
    assert_eq!(req["method"], "account/rateLimits/read");
    peer.reply(&req, json!({"rateLimits":{"primary":{"usedPercent":25}}}))
        .await;
    let first = a.await.unwrap();
    assert_eq!(first, b.await.unwrap());
    assert_eq!(first["status"], "ok");
    assert!(first["updatedAt"].as_u64().unwrap() > 1_000_000_000_000);
    assert!(
        tokio::time::timeout(Duration::from_millis(50), peer.recv())
            .await
            .is_err()
    );
    source.cache.lock().unwrap().last_attempt = Some(Instant::now() - Duration::from_secs(16));
    let auto = source.read("r", false, &permits).await;
    assert!(auto["retryAfterSeconds"].as_u64().unwrap() > 0);
    let retry = run(source.clone(), permits.clone());
    let req = peer.recv().await;
    peer.send(json!({"id":req["id"],"error":{"message":"secret-native-error"}}))
        .await;
    let stale = retry.await.unwrap();
    assert_eq!(stale["status"], "unavailable");
    assert_eq!(stale["stale"], true);
    assert_eq!(stale["items"], first["items"]);
    assert_eq!(stale["updatedAt"], first["updatedAt"]);
    assert!(!stale.to_string().contains("secret-native"));
}
#[tokio::test]
async fn catalog_opaque_ids_association_and_generation_are_isolated() {
    let (mut c, mut peer, temp) = mock_runtime().await;
    let path = temp.path().join("cc-switch.db");
    fixture_db(&path);
    c.capabilities
        .0
        .insert("config/read".into(), Default::default());
    let q = Arc::new(Quota::new(path));
    let task = tokio::spawn({
        let q = q.clone();
        let rpc = c.rpc.clone();
        let caps = c.capabilities.clone();
        async move { q.sources("r", "g", rpc, caps).await }
    });
    let req = peer.recv().await;
    assert_eq!(req["method"], "config/read");
    peer.reply(&req,json!({"config":{"model_provider":"provider-id","model_providers":{"provider-id":{"base_url":"https://quota.example/v1"}}}})).await;
    let listed = task.await.unwrap();
    let sources = listed["sources"].as_array().unwrap();
    assert_eq!(sources.len(), 2);
    let selected = listed["selectedSourceId"].as_str().unwrap();
    assert!(uuid::Uuid::parse_str(selected).is_ok());
    assert!(!listed.to_string().contains("synthetic-private-token"));
    assert!(!listed.to_string().contains("synthetic-key"));
    assert!(!listed.to_string().contains("quota.example"));
    assert!(q.read("other", "g", selected, false).await.is_err());
    assert!(
        q.read("r", "new-generation", selected, false)
            .await
            .is_err()
    );
    let updated = q
        .update(
            "r",
            "g",
            &json!({"rateLimits":{"primary":{"usedPercent":42}}}),
        )
        .unwrap();
    assert_eq!(updated["items"][0]["usedPercent"], 42.0);
    let cleared = q.account_changed("r", "g").unwrap();
    assert!(cleared["items"].as_array().unwrap().is_empty());
    assert!(cleared["updatedAt"].is_null());
    assert_eq!(cleared["status"], "unknown");
    assert!(
        q.update(
            "r",
            "wrong-generation",
            &json!({"rateLimits":{"primary":{"usedPercent":0}}})
        )
        .is_none()
    );
}

#[tokio::test(start_paused = true)]
async fn native_timeout_retains_last_good_and_does_not_disconnect_rpc() {
    let (c, mut peer, _temp) = mock_runtime().await;
    let source = Arc::new(Source {
        id: uuid::Uuid::new_v4().to_string(),
        label: "Official".into(),
        configured: true,
        kind: "official",
        interval: 60,
        provider: None,
        rpc: Some(c.rpc.clone()),
        cache: Mutex::new(Cache {
            items: vec![json!({"remaining":5})],
            updated: Some(now_ms()),
            ..Default::default()
        }),
        flight: AsyncMutex::new(()),
    });
    let task = tokio::spawn({
        let s = source.clone();
        async move { s.read("r", true, &Semaphore::new(1)).await }
    });
    let _ = peer.recv().await;
    tokio::time::advance(Duration::from_secs(21)).await;
    let response = task.await.unwrap();
    assert_eq!(response["status"], "timeout");
    assert_eq!(response["stale"], true);
    assert_eq!(response["items"][0]["remaining"], 5);
    assert!(c.rpc.online());
}
#[tokio::test]
async fn ambiguous_providers_and_model_nicknames_never_autoselect() {
    let (mut c, mut peer, temp) = mock_runtime().await;
    let path = temp.path().join("cc-switch.db");
    fixture_db(&path);
    let db = rusqlite::Connection::open(&path).unwrap();
    db.execute("INSERT INTO providers SELECT 'second-account', app_type,name,meta,settings_config FROM providers WHERE id='provider-id'",[]).unwrap();
    drop(db);
    assert!(
        sources::native_identity(&json!({"config":{"model":"provider-id/model-nickname"}}))
            .is_none()
    );
    c.capabilities
        .0
        .insert("config/read".into(), Default::default());
    let q = Arc::new(Quota::new(path));
    let task = tokio::spawn({
        let q = q.clone();
        async move { q.sources("r", "g", c.rpc, c.capabilities).await }
    });
    let request = peer.recv().await;
    peer.reply(&request,json!({"config":{"model_provider":"provider-id","model_providers":{"provider-id":{"base_url":"https://quota.example/v1"}}}})).await;
    let result = task.await.unwrap();
    assert!(result["selectedSourceId"].is_null());
    assert_eq!(result["status"], "unmatched");
    assert!(
        result["sources"]
            .as_array()
            .unwrap()
            .iter()
            .all(|s| s["associated"] == false)
    );
}

#[test]
fn third_party_reset_times_accept_seconds_or_milliseconds() {
    for n in [1794000000_u64, 1794000000000_u64] {
        let result = normalize(&json!({"remaining":5,"resetsAt":n})).unwrap();
        assert_eq!(result[0]["resetsAt"], 1794000000000_u64);
    }
}
#[tokio::test]
async fn current_provider_without_enabled_script_is_explicitly_unconfigured() {
    let (mut c, mut peer, temp) = mock_runtime().await;
    let path = temp.path().join("cc-switch.db");
    fixture_db(&path);
    let db = rusqlite::Connection::open(&path).unwrap();
    db.execute("UPDATE providers SET meta='{}' WHERE id='provider-id'", [])
        .unwrap();
    drop(db);
    c.capabilities
        .0
        .insert("config/read".into(), Default::default());
    let q = Arc::new(Quota::new(path));
    let task = tokio::spawn({
        let q = q.clone();
        async move { q.sources("r", "g", c.rpc, c.capabilities).await }
    });
    let req = peer.recv().await;
    peer.reply(&req,json!({"config":{"model_provider":"provider-id","model_providers":{"provider-id":{"base_url":"https://quota.example/v1"}}}})).await;
    let list = task.await.unwrap();
    assert_eq!(list["status"], "unconfigured");
    let id = list["selectedSourceId"].as_str().unwrap();
    let read = q.read("r", "g", id, true).await.unwrap();
    assert_eq!(read["status"], "unconfigured");
    assert!(read["items"].as_array().unwrap().is_empty());
    assert!(
        tokio::time::timeout(Duration::from_millis(50), peer.recv())
            .await
            .is_err()
    );
}
#[tokio::test]
async fn database_failure_retains_previous_source_and_last_good() {
    let (c, _peer, temp) = mock_runtime().await;
    let path = temp.path().join("cc-switch.db");
    fixture_db(&path);
    let q = Quota::new(path.clone());
    let list = q
        .sources("r", "g", c.rpc.clone(), c.capabilities.clone())
        .await;
    let id = list["sources"]
        .as_array()
        .unwrap()
        .iter()
        .find(|s| s["kind"] == "cc_switch")
        .unwrap()["id"]
        .as_str()
        .unwrap()
        .to_owned();
    {
        let mut catalog = q.catalogs.lock().await;
        let c = catalog.get_mut("r:g").unwrap();
        c.at = Instant::now() - Duration::from_secs(61);
        let s = &c.sources.iter().find(|(s, _)| s.id == id).unwrap().0;
        let mut cache = s.cache.lock().unwrap();
        cache.items = vec![json!({"remaining":7})];
        cache.updated = Some(now_ms());
    }
    std::fs::write(&path, b"malformed synthetic database").unwrap();
    let listed = q.sources("r", "g", c.rpc, c.capabilities).await;
    assert_eq!(listed["status"], "unavailable");
    let result = q.read("r", "g", &id, true).await.unwrap();
    assert_eq!(result["stale"], true);
    assert_eq!(result["status"], "unavailable");
    assert_eq!(result["items"][0]["remaining"], 7);
}

#[tokio::test]
async fn disabled_automatic_query_and_long_configured_interval_are_honored() {
    let source = Source {
        id: uuid::Uuid::new_v4().to_string(),
        label: "Fixture".into(),
        configured: true,
        kind: "cc_switch",
        interval: 0,
        provider: None,
        rpc: None,
        cache: Mutex::default(),
        flight: AsyncMutex::new(()),
    };
    let p = Semaphore::new(1);
    assert_eq!(source.read("r", false, &p).await["status"], "auto_disabled");
    assert!(source.cache.lock().unwrap().last_attempt.is_none());
    assert_eq!(source.read("r", true, &p).await["status"], "unavailable");
    assert!(
        source.read("r", true, &p).await["retryAfterSeconds"]
            .as_u64()
            .unwrap()
            > 0
    );
    let mut source = source;
    source.interval = 120;
    source.cache.lock().unwrap().last_attempt = Some(Instant::now() - Duration::from_secs(80));
    let result = source.read("r", false, &p).await;
    assert!(result["retryAfterSeconds"].as_u64().unwrap() >= 39);
}

#[test]
fn wal_database_read_sees_committed_state_without_changing_database_or_wal() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("cc-switch.db");
    fixture_db(&path);
    let db = rusqlite::Connection::open(&path).unwrap();
    db.pragma_update(None, "journal_mode", "WAL").unwrap();
    db.execute(
        "UPDATE providers SET name='WAL label' WHERE id='provider-id'",
        [],
    )
    .unwrap();
    let wal = path.with_extension("db-wal");
    let before_db = std::fs::read(&path).unwrap();
    let before_wal = std::fs::read(&wal).unwrap();
    assert_eq!(sources::load(&path).unwrap()[0].label, "WAL label");
    assert_eq!(std::fs::read(&path).unwrap(), before_db);
    assert_eq!(std::fs::read(&wal).unwrap(), before_wal);
}
#[tokio::test]
async fn global_query_budget_prevents_a_fifth_concurrent_native_fetch() {
    let permits = Arc::new(Semaphore::new(4));
    let mut peers = vec![];
    let mut tasks = vec![];
    let mut temps = vec![];
    let mut requests = vec![];
    for i in 0..5 {
        let (c, mut peer, temp) = mock_runtime().await;
        temps.push(temp);
        let source = Source {
            id: uuid::Uuid::new_v4().to_string(),
            label: "Official".into(),
            configured: true,
            kind: "official",
            interval: 60,
            provider: None,
            rpc: Some(c.rpc),
            cache: Mutex::default(),
            flight: AsyncMutex::new(()),
        };
        let permits = permits.clone();
        tasks.push(tokio::spawn(async move {
            source.read("r", true, &permits).await
        }));
        if i < 4 {
            requests.push(peer.recv().await);
        } else {
            assert!(
                tokio::time::timeout(Duration::from_millis(50), peer.recv())
                    .await
                    .is_err()
            );
        }
        peers.push(peer);
    }
    peers[0]
        .reply(
            &requests[0],
            json!({"rateLimits":{"primary":{"usedPercent":1}}}),
        )
        .await;
    let fifth = peers[4].recv().await;
    for i in 1..4 {
        peers[i]
            .reply(
                &requests[i],
                json!({"rateLimits":{"primary":{"usedPercent":1}}}),
            )
            .await;
    }
    peers[4]
        .reply(&fifth, json!({"rateLimits":{"primary":{"usedPercent":1}}}))
        .await;
    for task in tasks {
        assert_eq!(task.await.unwrap()["status"], "ok");
    }
}

#[test]
fn oversized_records_fail_closed_instead_of_changing_source_association() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("cc-switch.db");
    fixture_db(&path);
    let db = rusqlite::Connection::open(&path).unwrap();
    db.execute(
        "UPDATE providers SET settings_config=?1 WHERE id='disabled'",
        ["x".repeat(262145)],
    )
    .unwrap();
    drop(db);
    assert!(matches!(sources::load(&path), Err(Failure::Limit)));
    assert!(matches!(
        sources::load(std::path::Path::new(".cc-switch/cc-switch.db")),
        Err(Failure::Unavailable)
    ));
}
#[test]
fn sandbox_script_and_serialized_output_limits_are_enforced() {
    assert_eq!(
        sandbox::evaluate(&" ".repeat(sandbox::MAX_SCRIPT + 1), None),
        Err(Failure::Limit)
    );
    assert_eq!(
        sandbox::evaluate("({request:{body:'x'.repeat(65537)},extractor:r=>r})", None),
        Err(Failure::Limit)
    );
    assert!(sandbox::evaluate("({request:std.open('/secret','r'),extractor:r=>r})", None).is_err());
}
