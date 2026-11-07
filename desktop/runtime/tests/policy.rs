use pocket_desktop_runtime::policy::*;

#[test]
fn restarts_back_off_and_cap() {
    assert_eq!([0,1,2,3,4,9,50].map(restart_delay), [1,2,4,8,16,30,30]);
}
#[test]
fn explicit_stop_survives_recovery_and_existing_child_is_not_duplicated() {
    assert!(!should_run(false, false));
    assert!(!should_run(false, true));
    assert!(!should_run(true, true));
    assert!(should_run(true, false));
}
#[test]
fn export_removes_credentials_and_user_paths() {
    let original = "Connected token=abc-secret Authorization: Bearer hello-secret C:\\Users\\Alice\\.codex\\auth.json pairingToken=pair-secret wss://host/path?token=url-secret";
    let clean = redact(original);
    for secret in ["abc-secret", "hello-secret", "Alice", "pair-secret", "url-secret"] { assert!(!clean.contains(secret)); }
    assert!(clean.contains("Connected"));
}
