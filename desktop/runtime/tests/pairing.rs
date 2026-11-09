use pocket_desktop_runtime::{pairing,storage};
use serde_json::json;

#[test]
fn accepts_the_actual_helper_svg_contract_and_reads_only_private_json(){
    let temp=test_support::tempdir();let state=temp.path();
    let payload=json!({"v":1,"expiresAt":200,"pairingToken":"fixture-only"});
    storage::atomic(&state.join("pairing-qr.json"),&payload).unwrap();
    let response=json!({"qrPath":state.join("pairing-qr.svg"),"pngPath":state.join("pairing-qr.png"),"expiresAt":200});
    assert_eq!(pairing::read_payload(state,&response,100).unwrap(),payload);
}

#[test]
fn rejects_foreign_paths_and_expired_or_mismatched_payload(){
    let temp=test_support::tempdir();let state=temp.path();
    storage::atomic(&state.join("pairing-qr.json"),&json!({"v":1,"expiresAt":200})).unwrap();
    let response=json!({"qrPath":state.join("other.svg"),"expiresAt":200});
    assert!(pairing::read_payload(state,&response,100).is_err());
    let response=json!({"qrPath":state.join("pairing-qr.svg"),"expiresAt":200});
    assert!(pairing::read_payload(state,&response,201).is_err());
    storage::atomic(&state.join("pairing-qr.json"),&json!({"v":1,"expiresAt":300})).unwrap();
    assert!(pairing::read_payload(state,&response,100).is_err());
}
#[path = "../test_support/mod.rs"]
mod test_support;
