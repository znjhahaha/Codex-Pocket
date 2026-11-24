use super::*;
use rusqlite::{Connection, params};

fn saved_thread(home: &std::path::Path) {
    let sessions = home.join("sessions");
    std::fs::create_dir_all(&sessions).unwrap();
    let rollout = sessions.join("saved.jsonl");
    let records = [
        json!({"type":"session_meta","payload":{"id":"saved"}}),
        json!({"type":"turn_context","payload":{"thread_id":"saved","model":"previous/model","effort":"high","approval_policy":"never","approvals_reviewer":"user","sandbox_policy":{"type":"danger-full-access"}}}),
        json!({"type":"event_msg","payload":{"type":"thread_settings_applied","thread_id":"saved","thread_settings":{"model":"actual/model","effort":"low","approval_policy":"on-request","approvals_reviewer":"user","sandbox_policy":{"type":"read-only"}}}}),
        json!({"type":"event_msg","payload":{"type":"thread_settings_applied","thread_id":"foreign","thread_settings":{"model":"wrong/model","approval_policy":"never"}}}),
    ];
    std::fs::write(&rollout, records.iter().map(|v|format!("{v}\n")).collect::<String>()).unwrap();
    let db = Connection::open(home.join("state_5.sqlite")).unwrap();
    db.execute_batch("CREATE TABLE threads(id TEXT PRIMARY KEY,source TEXT,title TEXT,preview TEXT,cwd TEXT,updated_at INTEGER,archived INTEGER,rollout_path TEXT,model TEXT,reasoning_effort TEXT,sandbox_policy TEXT,approval_mode TEXT);").unwrap();
    db.execute("INSERT INTO threads VALUES('saved','vscode','Saved','','D:/project',1,0,?1,'index/model','medium','{\"type\":\"danger-full-access\"}','never')",params![rollout.to_str().unwrap()]).unwrap();
}

#[tokio::test]
async fn unopened_settings_are_readable_without_becoming_current_authority() {
    let (dir,b) = tests::fixture().await;
    saved_thread(dir.path());
    let store = storage::config_store(dir.path()).unwrap();
    let response = b.request("phone","threads/settings/read",json!({"configStoreId":store,"threadId":"saved"})).await.unwrap();
    assert_eq!(response["savedSettings"]["model"],"actual/model");
    assert_eq!(response["savedSettings"]["effort"],"low");
    assert_eq!(response["savedSettings"]["approvalPolicy"],"on-request");
    assert_eq!(response["savedSettings"]["sandboxPolicy"]["type"],"readOnly");
    assert_eq!(response["savedKnown"],true);
    assert_eq!(response["known"],false);
    assert_eq!(response["settings"],json!({}));
    assert_eq!(response["savedPermissions"]["status"],"saved");
    assert!(response["savedPermissions"]["revision"].is_null());
    assert!(response["savedPermissions"]["presets"].as_array().unwrap().iter().all(|p|p["enabled"]==false));
    assert!(b.state.lock().await.runtimes.is_empty());
    assert_eq!(response["configStoreId"],store);
    assert_eq!(response["threadId"],"saved");
}

#[tokio::test]
async fn unopened_catalog_uses_native_profile_reader_without_loading_thread() {
    let (dir,b) = tests::fixture().await;
    saved_thread(dir.path());
    let store = storage::config_store(dir.path()).unwrap();
    let mut native = tests::peer(&b,"catalog","cli").await;
    let serving = async {
        let request = native.recv().await;
        assert_eq!(request["method"],"model/list");
        assert_eq!(request["params"],json!({"limit":1,"cursor":"page2"}));
        native.reply(&request,json!({"data":[{"id":"actual/model","model":"actual/model"}],"nextCursor":"page3"})).await;
    };
    let (result,served) = tokio::join!(
        b.request("phone","threads/catalog/read",json!({"configStoreId":store,"threadId":"saved","method":"model/list","params":{"limit":1,"cursor":"page2"}})),
        tokio::time::timeout(Duration::from_secs(2),serving));
    let result = result.unwrap();
    assert!(served.is_ok());
    assert_eq!(result["data"][0]["model"],"actual/model");
    assert_eq!(result["nextCursor"],"page3");
    assert!(b.state.lock().await.runtimes["catalog"].loaded.is_empty());
    native.assert_idle().await;
}

#[tokio::test]
async fn unopened_reads_advertise_their_own_broker_capabilities() {
    let (_,b) = tests::fixture().await;
    let hello = b.hello().await;
    for method in ["threads/settings/read","threads/catalog/read"] {
        assert!(hello["capabilities"].as_array().unwrap().contains(&json!(method)),"{method}");
    }
}

#[tokio::test]
async fn unopened_reads_reject_foreign_identity_and_catalog_mutations() {
    let (dir,b) = tests::fixture().await;
    saved_thread(dir.path());
    let store = storage::config_store(dir.path()).unwrap();
    let mut native = tests::peer(&b,"catalog","cli").await;
    for request in [
        json!({"computerId":"foreign","configStoreId":store,"threadId":"saved"}),
        json!({"configStoreId":"foreign","threadId":"saved"}),
        json!({"configStoreId":store,"threadId":"missing"}),
        json!({"configStoreId":store,"threadId":"saved","model":"override"}),
    ] {
        assert!(b.request("phone","threads/settings/read",request).await.is_err());
    }
    for (method,params) in [
        ("thread/resume",json!({"threadId":"saved"})),
        ("turn/start",json!({"threadId":"saved","input":[]})),
        ("model/list",json!({"cwd":"D:/another"})),
    ] {
        assert!(b.request("phone","threads/catalog/read",json!({"configStoreId":store,"threadId":"saved","method":method,"params":params})).await.is_err());
    }
    native.assert_idle().await;
}

#[tokio::test]
async fn unopened_settings_reject_mismatched_rollout_and_ignore_unfinished_append() {
    let (dir,b) = tests::fixture().await;
    saved_thread(dir.path());
    let store=storage::config_store(dir.path()).unwrap();
    let query=json!({"configStoreId":store,"threadId":"saved"});
    let path=dir.path().join("sessions/saved.jsonl");
    let original=std::fs::read_to_string(&path).unwrap();
    std::fs::write(&path,format!("{original}{{\"type\":\"event_msg\"")).unwrap();
    let value=b.request("phone","threads/settings/read",query.clone()).await.unwrap();
    assert_eq!(value["savedSettings"]["model"],"actual/model");
    std::fs::write(&path,format!("{}\n",json!({"type":"session_meta","payload":{"id":"foreign"}}))).unwrap();
    assert!(b.request("phone","threads/settings/read",query).await.is_err());
}

#[tokio::test]
async fn unopened_settings_do_not_fabricate_permissions_from_missing_fields() {
    let (dir,b) = tests::fixture().await;
    saved_thread(dir.path());
    std::fs::write(dir.path().join("sessions/saved.jsonl"),format!("{}\n{}\n",
        json!({"type":"session_meta","payload":{"id":"saved"}}),
        json!({"type":"event_msg","payload":{"type":"thread_settings_applied","thread_settings":{"model":"snapshot/model","collaboration_mode":{"mode":"default","settings":{"model":"snapshot/model","reasoning_effort":"xhigh","developer_instructions":"private"}}}}}))).unwrap();
    let store = storage::config_store(dir.path()).unwrap();
    let result = b.request("phone","threads/settings/read",json!({"configStoreId":store,"threadId":"saved"})).await.unwrap();
    assert_eq!(result["savedSettings"]["model"],"snapshot/model");
    assert_eq!(result["savedSettings"]["effort"],"xhigh");
    assert!(result["savedSettings"]["sandboxPolicy"].is_null());
    assert!(result["savedSettings"]["approvalPolicy"].is_null());
    assert_eq!(result["savedPermissions"]["status"],"unknown");
    assert!(!result.to_string().contains("private"));
}

#[tokio::test]
async fn unopened_catalog_rejects_a_replaced_native_connection() {
    let (dir,b) = tests::fixture().await;
    saved_thread(dir.path());
    let store = storage::config_store(dir.path()).unwrap();
    let mut native = tests::peer(&b,"catalog","cli").await;
    let serving = async {
        let request = native.recv().await;
        assert_eq!(request["method"],"model/list");
        b.state.lock().await.runtimes.get_mut("catalog").unwrap().connection_id = "replacement".into();
        native.reply(&request,json!({"data":[],"nextCursor":null})).await;
    };
    let (result,_) = tokio::join!(
        b.request("phone","threads/catalog/read",json!({"configStoreId":store,"threadId":"saved","method":"model/list","params":{}})),
        tokio::time::timeout(Duration::from_secs(2),serving));
    assert!(result.is_err());
    native.assert_idle().await;
}

#[tokio::test]
#[ignore = "explicit read-only local profile probe; no discovery, resume, turn, UI or permission writes"]
async fn unopened_live_read_without_desktop_attachment() {
    let home = PathBuf::from(std::env::var_os("POCKET_QA_NATIVE_HOME").expect("explicit profile"));
    let thread = std::env::var("POCKET_QA_THREAD").expect("explicit thread");
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::create("wss://example.invalid/pocket".into()).unwrap();
    config.codex_homes = vec![home.clone()];
    config.runtime_directories = vec![];
    let (b,_) = Broker::open(config,dir.path().into()).unwrap();
    b.state.lock().await.devices.add("probe".into(),"Read-only probe".into(),"unused".into(),storage::now()).unwrap();
    let store = storage::config_store(&home).unwrap();
    let identity = json!({"configStoreId":store,"threadId":thread});
    let rollout_bytes=crate::history_catalog::rollout_path(&home,&thread).ok()
        .and_then(|p|std::fs::metadata(p).ok()).map(|m|m.len());
    let started=std::time::Instant::now();
    let settings = b.request("probe","threads/settings/read",identity.clone()).await.unwrap();
    let saved_read_ms=started.elapsed().as_secs_f64()*1000.0;
    assert_eq!(settings["known"],false);
    assert_eq!(settings["savedKnown"],true);
    let mut query = identity;
    query["method"] = json!("model/list"); query["params"] = json!({"limit":100});
    let started=std::time::Instant::now();
    let models = b.request("probe","threads/catalog/read",query.clone()).await.unwrap();
    let first_catalog_ms=started.elapsed().as_secs_f64()*1000.0;
    assert!(!models["data"].as_array().unwrap().is_empty());
    let started=std::time::Instant::now();
    let again = b.request("probe","threads/catalog/read",query).await.unwrap();
    let repeat_catalog_ms=started.elapsed().as_secs_f64()*1000.0;
    assert_eq!(models["data"],again["data"]);
    assert!(b.state.lock().await.runtimes.is_empty());
    println!("{}",json!({"savedReadMs":saved_read_ms,"rolloutBytes":rollout_bytes,
        "firstCatalogMs":first_catalog_ms,"repeatCatalogMs":repeat_catalog_ms,
        "modelCount":models["data"].as_array().unwrap().len(),"savedKnown":settings["savedKnown"],
        "currentKnown":settings["known"],"savedPermissionStatus":settings["savedPermissions"]["status"],
        "firstCached":models["catalogCached"],"repeatCached":again["catalogCached"],"runtimeCount":0}));
}
