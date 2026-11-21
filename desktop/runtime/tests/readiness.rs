use pocket_desktop_runtime::diagnostics::{idle_for_update, readiness};
use serde_json::json;

#[test]
fn a_transport_and_schema_do_not_prove_native_readiness() {
    let mut h = json!({"protocol":1,"updatedAt":100,"acceptingWrites":true,"runtimes":[{"online":true,"source":"desktop","initialized":false,"schemaState":"ready","inventoryReady":true}]});
    assert_eq!(readiness(&h, 100)["ready"], false);
    h["runtimes"][0]["initialized"] = json!(true);
    assert_eq!(readiness(&h, 100)["ready"], true);
    assert_eq!(readiness(&h, 111)["ready"], false);
    h["runtimes"][0]["role"] = json!({"kind":"auxiliary"});
    assert_eq!(readiness(&h, 100)["ready"], false);
}

#[test]
fn idle_requires_complete_current_authoritative_evidence() {
    let mut h = json!({"protocol":1,"updatedAt":100,"acceptingWrites":true,"lifecycle":{"observedAt":100,"idle":true,"pendingWrites":0,"activeTurns":0,"pendingQuestions":0,"unknownOperations":0,"nativeTasksPreserved":true}});
    assert!(idle_for_update(&h, 100));
    for field in [
        "pendingWrites",
        "activeTurns",
        "pendingQuestions",
        "unknownOperations",
    ] {
        h["lifecycle"][field] = json!(1);
        assert!(!idle_for_update(&h, 100));
        h["lifecycle"][field] = json!(0);
    }
    assert!(!idle_for_update(&h, 111));
    h["lifecycle"]["nativeTasksPreserved"] = json!(false);
    assert!(!idle_for_update(&h, 100));
    h["lifecycle"]["nativeTasksPreserved"] = json!(true);
    h["lifecycle"]["observedAt"] = json!(99);
    assert!(!idle_for_update(&h, 100));
    h["lifecycle"]["observedAt"] = json!(100);
    h["lifecycle"]
        .as_object_mut()
        .unwrap()
        .remove("activeTurns");
    assert!(!idle_for_update(&h, 100));
    assert!(!idle_for_update(
        &json!({"protocol":1,"updatedAt":100,"acceptingWrites":true}),
        100
    ));
}
