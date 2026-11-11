use pocket_bridge::router::{Client, Route, Router};
use serde_json::json;

#[test]
fn native_initialize_reply_is_not_ready_before_desktop_initialized_notification() {
    let mut r = Router::default();
    let init = r.from_client(
        Client::Desktop,
        json!({"id":1,"method":"initialize","params":{}}),
    );
    let Route::Runtime(request) = &init[0] else {
        panic!("initialize must reach native");
    };
    r.from_runtime(json!({"id":request["id"],"result":{"version":"fake"}}));
    assert!(
        !r.initialized,
        "native handshake is incomplete until initialized is forwarded"
    );
    let ack = r.from_client(Client::Desktop, json!({"method":"initialized"}));
    assert!(matches!(&ack[0], Route::Runtime(v) if v["method"] == "initialized"));
    assert!(r.initialized);
}

#[test]
fn failed_initialize_and_remote_ack_cannot_mark_native_ready() {
    let mut r = Router::default();
    r.from_client(Client::Desktop, json!({"method":"initialized"}));
    assert!(!r.initialized);
    let init = r.from_client(
        Client::Desktop,
        json!({"id":1,"method":"initialize","params":{}}),
    );
    let Route::Runtime(request) = &init[0] else {
        panic!();
    };
    r.from_runtime(
        json!({"id":request["id"],"error":{"code":-32600,"message":"synthetic failure"}}),
    );
    r.from_client(Client::Desktop, json!({"method":"initialized"}));
    assert!(!r.initialized);
    let init = r.from_client(
        Client::Desktop,
        json!({"id":2,"method":"initialize","params":{}}),
    );
    let Route::Runtime(request) = &init[0] else {
        panic!();
    };
    r.from_runtime(json!({"id":request["id"],"result":{"version":"fake"}}));
    r.from_client(
        Client::Remote("phone".into()),
        json!({"method":"initialized"}),
    );
    assert!(!r.initialized);
}
