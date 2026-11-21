use base64::{engine::general_purpose::STANDARD, Engine};
use pocket_desktop_runtime::{
    storage,
    updates::{stage_local, verify_local_candidate, TrustPolicy},
};
use ring::signature::{Ed25519KeyPair, KeyPair};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
#[path = "../test_support/mod.rs"]
mod test_support;
fn hash(b: &[u8]) -> String {
    format!("{:x}", Sha256::digest(b))
}
fn fixture() -> (
    tempfile::TempDir,
    std::path::PathBuf,
    std::path::PathBuf,
    std::path::PathBuf,
    String,
) {
    fixture_version("0.7.1")
}
fn fixture_version(version:&str) -> (tempfile::TempDir,std::path::PathBuf,std::path::PathBuf,std::path::PathBuf,String) {
    let dir = test_support::tempdir();
    let root = dir.path().join("install");
    let state = root.join("state");
    let source = dir.path().join("signed-source");
    for p in [&root, &state, &source] {
        storage::private_dir(p).unwrap();
    }
    let key = Ed25519KeyPair::from_seed_unchecked(&[51; 32]).unwrap();
    let public = STANDARD.encode(key.public_key().as_ref());
    let trust = TrustPolicy {
        schema: 1,
        feed: "https://fixture.invalid/feed.json".into(),
        public_key: public.clone(),
        allowed_hosts: vec!["fixture.invalid".into()],
    };
    let mut files = vec![];
    for name in [
        "pocket-desktop.exe",
        "pocket-agent.exe",
        "pocket-supervisor.exe",
        "pocket-bridge.exe",
        "pocket-upgrade.exe",
        "windows-update-trust.json",
    ] {
        let bytes = if name == "windows-update-trust.json" {
            serde_json::to_vec(&trust).unwrap()
        } else {
            b"never execute fixture".to_vec()
        };
        std::fs::write(source.join(name), &bytes).unwrap();
        files.push(json!({"path":name,"bytes":bytes.len(),"sha256":hash(&bytes)}));
    }
    let manifest = serde_json::to_vec(&json!({"version":version,"files":files})).unwrap();
    std::fs::write(source.join("windows-manifest.json"), &manifest).unwrap();
    let now = storage::now();
    let payload=serde_json::to_vec(&json!({"schema":1,"version":version,"publishedAt":now-1,"expiresAt":now+3600,"windowsManifest":STANDARD.encode(&manifest),"files":files.iter().map(|f|json!({"path":f["path"],"url":format!("https://fixture.invalid/{}",f["path"].as_str().unwrap())})).collect::<Vec<_>>()})).unwrap();
    storage::atomic(&source.join("offer.json"),&json!({"payload":STANDARD.encode(&payload),"signature":STANDARD.encode(key.sign(&payload).as_ref())})).unwrap();
    let old = serde_json::to_vec(&json!({"version":"0.6.6","files":[]})).unwrap();
    let generation = hash(&old);
    let bin = root.join("bin").join(&generation);
    storage::private_dir(&bin).unwrap();
    std::fs::write(bin.join("windows-manifest.json"), old).unwrap();
    storage::atomic(&root.join("desktop-install.json"),&json!({"version":3,"installRoot":root,"bridge":root.join("pocket-launcher.exe"),"stateDirectory":state,"generation":generation,"releaseHash":generation})).unwrap();
    storage::atomic(
        &state.join("config.json"),
        &json!({"computerId":"fixture","keep":"paired"}),
    )
    .unwrap();
    (dir, root, state, source, public)
}
#[test]
fn prerelease_requires_explicit_installer_and_never_enters_automatic_feed() {
    let (_d,r,s,src,key)=fixture_version("0.7.1-rc.20261117.1");
    let bytes=std::fs::read(src.join("offer.json")).unwrap();
    let trust:TrustPolicy=storage::read(&src.join("windows-update-trust.json")).unwrap();
    assert!(pocket_desktop_runtime::updates::verify_offer(&trust,&bytes,"0.7.0",storage::now()).is_err());
    verify_local_candidate(&src,&bytes,&key,"0.7.0",storage::now()).unwrap();
    stage_local(&r,&s,&src,&src.join("offer.json"),Some(&key)).unwrap();
}
#[test]
fn first_key_requires_an_independent_local_anchor_and_preserves_existing_state() {
    let (_d, r, s, src, key) = fixture();
    let before = std::fs::read(r.join("desktop-install.json")).unwrap();
    let config = std::fs::read(s.join("config.json")).unwrap();
    assert!(stage_local(&r, &s, &src, &src.join("offer.json"), None).is_err());
    assert!(stage_local(
        &r,
        &s,
        &src,
        &src.join("offer.json"),
        Some(&STANDARD.encode([9; 32]))
    )
    .is_err());
    assert!(!s.join("desktop/updates/bootstrap-trust.json").exists());
    stage_local(&r, &s, &src, &src.join("offer.json"), Some(&key)).unwrap();
    assert!(s.join("desktop/updates/bootstrap-trust.json").is_file());
    assert_eq!(
        std::fs::read(r.join("desktop-install.json")).unwrap(),
        before
    );
    assert_eq!(std::fs::read(s.join("config.json")).unwrap(), config);
}
#[test]
fn signed_manifest_binds_trust_policy_and_tampered_payload_never_provisions() {
    let (_d, r, s, src, key) = fixture();
    let bytes = std::fs::read(src.join("offer.json")).unwrap();
    verify_local_candidate(&src, &bytes, &key, "0.6.6", storage::now()).unwrap();
    verify_local_candidate(&src, &bytes, &key, "0.6.6", storage::now()+90*86400).unwrap();
    let mut policy: Value =
        serde_json::from_slice(&std::fs::read(src.join("windows-update-trust.json")).unwrap())
            .unwrap();
    policy["feed"] = json!("https://fixture.invalid/attacker");
    storage::atomic(&src.join("windows-update-trust.json"), &policy).unwrap();
    assert!(stage_local(&r, &s, &src, &src.join("offer.json"), Some(&key)).is_err());
    assert!(!s.join("desktop/updates/bootstrap-trust.json").exists());
}
#[test]
fn corrupt_existing_bootstrap_cannot_be_replaced_by_a_new_explicit_key() {
    let (_d, r, s, src, key) = fixture();
    storage::private_dir(&s.join("desktop/updates")).unwrap();
    storage::atomic(
        &s.join("desktop/updates/bootstrap-trust.json"),
        &json!({"schema":99}),
    )
    .unwrap();
    assert!(stage_local(&r, &s, &src, &src.join("offer.json"), Some(&key)).is_err());
}

#[test]
fn explicit_installer_reserves_restart_route_before_the_old_worker_can_hot_switch() {
    let (_d,root,state,source,key)=fixture();
    stage_local(&root,&state,&source,&source.join("offer.json"),Some(&key)).unwrap();
    let pending=pocket_desktop_runtime::updates::read_status(&state).unwrap();
    assert_eq!(pending.phase,"waiting_cold_start");
    assert!(pending.explicit_install);
    assert!(!pocket_desktop_runtime::updates::switch_if_idle(&root,&state,&json!({})).unwrap());
    assert_eq!(pocket_desktop_runtime::updates::read_status(&state).unwrap().phase,"waiting_cold_start");
}

#[test]
fn reapplying_the_same_signed_installer_keeps_the_waiting_transaction() {
    let (_d, root, state, source, key) = fixture();
    let offer = source.join("offer.json");
    stage_local(&root, &state, &source, &offer, Some(&key)).unwrap();
    pocket_desktop_runtime::updates::switch_if_idle(&root, &state, &json!({})).unwrap();
    let pending = pocket_desktop_runtime::updates::read_status(&state).unwrap();
    assert_eq!(pending.phase, "waiting_cold_start");
    stage_local(&root, &state, &source, &offer, Some(&key)).unwrap();
    let retried = pocket_desktop_runtime::updates::read_status(&state).unwrap();
    assert_eq!(retried.operation_id, pending.operation_id);
    assert_eq!(retried.phase, "waiting_cold_start");
}

#[test]
fn restart_click_is_operation_bound_idempotent_and_keeps_network_trust_rules() {
    use pocket_desktop_runtime::{local_update,updates};
    let (_d,root,state,source,key)=fixture();
    stage_local(&root,&state,&source,&source.join("offer.json"),Some(&key)).unwrap();
    updates::set_automatic(&state,false).unwrap();
    let mut status=updates::read_status(&state).unwrap();
    status.explicit_install=false;
    let dir=state.join("desktop/updates");
    storage::atomic(&dir.join("status.json"),&status).unwrap();
    let _coordinator=storage::Lock::acquire(&dir.join(format!("local-{}.lock",status.operation_id))).unwrap();
    assert!(local_update::request_restart(&root,&state,&uuid::Uuid::new_v4().to_string()).is_err());
    assert!(!dir.join("restart-request.json").exists());
    local_update::request_restart(&root,&state,&status.operation_id).unwrap();
    let request=std::fs::read(dir.join("restart-request.json")).unwrap();
    local_update::request_restart(&root,&state,&status.operation_id).unwrap();
    assert_eq!(std::fs::read(dir.join("restart-request.json")).unwrap(),request);
    assert_eq!(updates::public_status(&state)["restartRequested"],true);
    assert!(!updates::read_status(&state).unwrap().explicit_install);
    assert!(!updates::settings(&state).unwrap().automatic);
}

#[test]
fn restart_click_rejects_expired_network_offer_before_recording_intent() {
    use pocket_desktop_runtime::{local_update,updates};
    let (_d,root,state,source,key)=fixture();
    stage_local(&root,&state,&source,&source.join("offer.json"),Some(&key)).unwrap();
    let dir=state.join("desktop/updates");
    let mut status=updates::read_status(&state).unwrap();
    status.explicit_install=false;
    storage::atomic(&dir.join("status.json"),&status).unwrap();
    let mut envelope:Value=storage::read(&dir.join("offer.json")).unwrap();
    let mut payload:Value=serde_json::from_slice(&STANDARD.decode(envelope["payload"].as_str().unwrap()).unwrap()).unwrap();
    payload["publishedAt"]=json!(storage::now()-100);
    payload["expiresAt"]=json!(storage::now()-1);
    let bytes=serde_json::to_vec(&payload).unwrap();
    let signing=Ed25519KeyPair::from_seed_unchecked(&[51;32]).unwrap();
    envelope["payload"]=json!(STANDARD.encode(&bytes));
    envelope["signature"]=json!(STANDARD.encode(signing.sign(&bytes).as_ref()));
    storage::atomic(&dir.join("offer.json"),&envelope).unwrap();
    assert!(local_update::request_restart(&root,&state,&status.operation_id).is_err());
    assert!(!dir.join("restart-request.json").exists());
}
