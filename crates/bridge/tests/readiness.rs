use pocket_bridge::router::{Client, Route, Router};
use serde_json::{Value, json};

fn request(router: &mut Router, method: &str) -> Value {
    router.from_client(Client::Desktop, json!({"id":1,"method":method,"params":{}}))
        .into_iter().find_map(|r| match r { Route::Runtime(v) => Some(v["id"].clone()), _ => None }).unwrap()
}

fn management(router: &mut Router) -> Value {
    router.from_client(Client::Remote("management".into()), json!({"id":"status","method":"pocket/managementStatus","params":{}}))
        .into_iter().find_map(|r| match r { Route::Client(_, v) => Some(v["result"].clone()), _ => None }).unwrap()
}

#[test]
fn desktop_mutations_and_future_methods_block_idle_until_native_reply() {
    for method in ["config/value/write", "account/login/start", "skills/config/write", "thread/name/set", "future/effect"] {
        let mut router = Router::default();
        let id = request(&mut router, method);
        assert_eq!(management(&mut router)["pendingWrites"], 1, "{method}");
        assert_eq!(management(&mut router)["busy"], true);
        router.disconnect(&Client::Desktop);
        assert_eq!(management(&mut router)["pendingWrites"], 1, "disconnect cannot cancel dispatch");
        router.from_runtime(json!({"id":id,"result":{}}));
        assert_eq!(management(&mut router)["busy"], false);
    }
}

#[test]
fn answered_native_question_stays_pending_until_native_resolution() {
    let mut router = Router::default();
    let routes = router.from_runtime(json!({"id":9,"method":"item/commandExecution/requestApproval","params":{"threadId":"t"}}));
    let alias = routes.into_iter().find_map(|r| match r { Route::Client(Client::Desktop,v) => Some(v["id"].clone()), _ => None }).unwrap();
    router.from_client(Client::Desktop, json!({"id":alias,"result":{"decision":"accept"}}));
    assert_eq!(management(&mut router)["pendingWrites"], 1);
    router.from_runtime(json!({"method":"serverRequest/resolved","params":{"threadId":"t","requestId":9}}));
    assert_eq!(management(&mut router)["pendingWrites"], 0);
}

#[test]
fn malformed_mutation_result_keeps_unknown_activity() {
    let mut router = Router::default();
    let id = request(&mut router, "config/value/write");
    router.from_runtime(json!({"id":id}));
    assert_eq!(management(&mut router)["unknownOperations"], 1);
    assert_eq!(management(&mut router)["busy"], true);
}

#[test]
fn maintenance_barrier_fences_desktop_and_remote_and_is_operation_bound() {
    let mut router = Router::default();
    initialize(&mut router);
    router.from_client(Client::Desktop, json!({"method":"initialized"}));
    let operation = uuid::Uuid::new_v4().to_string();
    let call = |method: &str, id: &str| json!({"id":"q","method":method,"params":{"operationId":id}});
    let reply = router.from_client(Client::Remote("updater".into()), call("pocket/maintenance/quiesce",&operation));
    assert!(matches!(&reply[0], Route::Client(_,v) if v["result"]["quiesced"] == true));
    for client in [Client::Desktop, Client::Remote("phone".into())] {
        let denied = router.from_client(client, json!({"id":"write","method":"turn/start","params":{"threadId":"t"}}));
        assert!(denied.iter().all(|r| !matches!(r,Route::Runtime(_))));
    }
    let other = uuid::Uuid::new_v4().to_string();
    let denied = router.from_client(Client::Remote("updater".into()), call("pocket/maintenance/resume",&other));
    assert!(matches!(&denied[0],Route::Client(_,v) if v.get("error").is_some()));
    router.disconnect(&Client::Remote("updater".into()));
    assert_eq!(management(&mut router)["maintenance"]["operationId"], operation);
    router.from_client(Client::Remote("successor".into()),call("pocket/maintenance/resume",&operation));
    assert!(management(&mut router)["maintenance"].is_null());
    assert!(router.from_client(Client::Desktop,json!({"id":"new","method":"turn/start","params":{}})).iter().any(|r|matches!(r,Route::Runtime(_))));
}

#[test]
fn maintenance_never_quiesces_a_pending_desktop_mutation() {
    let mut router = Router::default();
    initialize(&mut router);
    router.from_client(Client::Desktop, json!({"method":"initialized"}));
    request(&mut router,"config/value/write");
    let reply = router.from_client(Client::Remote("updater".into()),json!({"id":1,"method":"pocket/maintenance/quiesce","params":{"operationId":uuid::Uuid::new_v4().to_string()}}));
    assert!(matches!(&reply[0],Route::Client(_,v) if v.get("error").is_some()));
    assert!(management(&mut router)["maintenance"].is_null());
}
fn initialize(router: &mut Router) {
    let id = request(router, "initialize");
    router.from_runtime(json!({"id":id,"result":{"userAgent":"Codex Desktop/0.167.0"}}));
    assert!(!router.initialized, "initialize response alone is not readiness proof");
}

#[test]
fn subsequent_native_rpc_proves_readiness_without_notification() {
    let mut router = Router::default();
    initialize(&mut router);
    let id = request(&mut router, "model/list");
    router.from_runtime(json!({"id":id,"result":{"data":[]}}));
    assert!(router.initialized);
}

#[test]
fn late_reply_to_pre_initialize_request_cannot_prove_readiness() {
    let mut router = Router::default();
    let earlier = request(&mut router, "model/list");
    initialize(&mut router);
    router.from_runtime(json!({"id":earlier,"result":{"data":[]}}));
    assert!(!router.initialized);
}

#[test]
fn failed_malformed_and_unmatched_responses_cannot_prove_readiness() {
    let mut router = Router::default();
    initialize(&mut router);
    let id = request(&mut router, "model/list");
    router.from_runtime(json!({"id":id,"error":{"code":-1,"message":"not ready"}}));
    let id = request(&mut router, "model/list");
    router.from_runtime(json!({"id":id}));
    router.from_runtime(json!({"id":"unmatched","result":{}}));
    assert!(!router.initialized);
}

#[test]
fn malformed_initialize_cannot_be_completed_by_notification_or_rpc() {
    let mut router = Router::default();
    let id = request(&mut router, "initialize");
    router.from_runtime(json!({"id":id}));
    router.from_client(Client::Desktop, json!({"method":"initialized"}));
    let id = request(&mut router, "model/list");
    router.from_runtime(json!({"id":id,"result":{"data":[]}}));
    assert!(!router.initialized);
}

#[test]
fn explicit_initialized_notification_remains_supported() {
    let mut router = Router::default();
    initialize(&mut router);
    router.from_client(Client::Desktop, json!({"method":"initialized"}));
    assert!(router.initialized);
}

#[test]
fn browser_identity_is_kept_out_of_conversation_writes() {
    for (name, agent) in [("codex-browser-use", "native/1"), ("", "codex-browser-use/0.167.0")] {
        let mut router = Router::default();
        let routes = router.from_client(Client::Desktop, json!({"id":1,"method":"initialize","params":{"clientInfo":{"name":name}}}));
        let id = match &routes[0] { Route::Runtime(v) => v["id"].clone(), _ => panic!() };
        router.from_runtime(json!({"id":id,"result":{"userAgent":agent}}));
        let id = request(&mut router, "model/list");
        router.from_runtime(json!({"id":id,"result":{"data":[]}}));
        assert_eq!(router.runtime_role, "auxiliary");
        assert!(router.initialized);
        let routes = router.from_client(Client::Remote("phone".into()), json!({"id":2,"method":"thread/start","params":{}}));
        assert!(matches!(&routes[0], Route::Client(_, v) if v.get("error").is_some()));
    }
}

#[test]
fn remote_requests_cannot_complete_desktop_initialization() {
    let mut router = Router::default();
    initialize(&mut router);
    router.capabilities.0.insert("model/list".into(), Default::default());
    for route in router.from_client(Client::Remote("phone".into()), json!({"id":1,"method":"model/list","params":{}})) {
        if let Route::Runtime(v) = route { router.from_runtime(json!({"id":v["id"],"result":{"data":[]}})); }
    }
    assert!(!router.initialized);
}
