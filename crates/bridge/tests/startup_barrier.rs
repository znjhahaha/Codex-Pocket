use pocket_bridge::router::{Client, Route, Router};
use serde_json::{Value, json};

fn call(r: &mut Router, method: &str, params: Value) -> Value {
    r.from_client(Client::Remote("updater".into()), json!({"id":1,"method":method,"params":params}))
        .into_iter().find_map(|v| match v { Route::Client(_, v) => Some(v), _ => None }).unwrap()
}
fn writes_blocked(r: &mut Router) {
    for client in [Client::Desktop, Client::Remote("phone".into())] {
        for method in ["turn/start", "thread/resume", "thread/start", "future/write", "pocket/futureWrite"] {
            assert!(!r.from_client(client.clone(), json!({"id":"write","method":method,"params":{}}))
                .iter().any(|v| matches!(v, Route::Runtime(_))), "{method}");
        }
    }
}
fn initialize(r: &mut Router) {
    let id = r.from_client(Client::Desktop, json!({"id":1,"method":"initialize","params":{"clientInfo":{"name":"codex_desktop"}}}))
        .into_iter().find_map(|v| match v { Route::Runtime(v) => Some(v["id"].clone()), _ => None }).unwrap();
    r.from_runtime(json!({"id":id,"result":{"userAgent":"Codex Desktop/test"}}));
    r.from_client(Client::Desktop, json!({"method":"initialized"}));
    assert!(r.initialized);
}

#[test]
fn startup_blocks_all_writes_before_initialization_and_survives_disconnect() {
    let op = uuid::Uuid::new_v4().to_string();
    let mut r = Router::with_startup_operation(op.clone(), None).unwrap();
    writes_blocked(&mut r);
    let status = call(&mut r,"pocket/managementStatus",json!({}));
    assert_eq!(status["result"]["startupBarrierProtocol"], 1);
    assert_eq!(status["result"]["maintenance"]["startupFenced"], true);
    assert_eq!(status["result"]["maintenance"]["operationId"], op);
    assert_eq!(status["result"]["busy"], false);
    assert!(call(&mut r,"pocket/maintenance/resume",json!({"operationId":op})).get("error").is_some());
    initialize(&mut r);
    r.disconnect(&Client::Remote("updater".into()));
    writes_blocked(&mut r);
    let other = uuid::Uuid::new_v4().to_string();
    assert!(call(&mut r,"pocket/maintenance/resume",json!({"operationId":other})).get("error").is_some());
    assert_eq!(call(&mut r,"pocket/maintenance/quiesce",json!({"operationId":op}))["result"]["startupFenced"], true);
    assert_eq!(call(&mut r,"pocket/maintenance/resume",json!({"operationId":op}))["result"]["quiesced"], false);
    assert!(r.from_client(Client::Desktop,json!({"id":2,"method":"thread/start","params":{}})).iter().any(|v|matches!(v,Route::Runtime(_))));
}

#[test]
fn durable_release_is_exact_bounded_and_applies_to_later_bridge_children() {
    let root = std::env::temp_dir().join(format!("pocket-startup-{}",uuid::Uuid::new_v4()));
    std::fs::create_dir(&root).unwrap();
    let path = root.join("release.json");
    let op = uuid::Uuid::new_v4().to_string();
    let mut r = Router::with_startup_operation(op.clone(),Some(path.clone())).unwrap();
    for bytes in ["{".to_string(), " ".repeat(8192), json!({"schema":1,"operationId":uuid::Uuid::new_v4().to_string(),"released":true}).to_string(), json!({"schema":1,"operationId":op,"released":false}).to_string()] {
        std::fs::write(&path,bytes).unwrap();
        writes_blocked(&mut r);
    }
    std::fs::write(&path,json!({"schema":1,"operationId":op,"released":true}).to_string()).unwrap();
    r.refresh_startup();
    assert!(r.maintenance_status().is_null());
    let later = Router::with_startup_operation(op,Some(path.clone())).unwrap();
    assert!(later.maintenance_status().is_null());
    std::fs::remove_file(path).unwrap();std::fs::remove_dir(root).unwrap();
}

#[test]
fn startup_rejects_invalid_operation_and_relative_receipt() {
    assert!(Router::with_startup_operation("not-an-operation".into(),None).is_err());
    assert!(Router::with_startup_operation(uuid::Uuid::new_v4().to_string(),Some("relative.json".into())).is_err());
}

#[test]
fn persistent_startup_cannot_resume_by_rpc_before_receipt() {
    let root=tempfile::tempdir().unwrap();let op=uuid::Uuid::new_v4().to_string();let release=root.path().join("release.json");
    let mut r=Router::with_startup_operation(op.clone(),Some(release.clone())).unwrap();
    initialize(&mut r);
    let response=call(&mut r,"pocket/maintenance/resume",json!({"operationId":op}));
    assert!(response.get("error").is_some(),"knowing the operation ID must not bypass the durable release decision");
    writes_blocked(&mut r);
    std::fs::write(&release,json!({"schema":1,"operationId":op,"released":true}).to_string()).unwrap();
    r.refresh_startup();
    assert!(r.maintenance_status().is_null());
}

#[test]
fn unmanaged_router_does_not_advertise_installation_admission() {
    let mut r=Router::default();
    let status=call(&mut r,"pocket/managementStatus",json!({}));
    assert_eq!(status["result"]["startupAdmissionProtocol"],0);
    assert!(status["result"]["startupAdmission"].is_null());
}
