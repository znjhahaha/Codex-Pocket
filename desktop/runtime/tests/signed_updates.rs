use base64::{engine::general_purpose::STANDARD, Engine};
use pocket_desktop_runtime::updates::{stage_with, verify_offer, TrustPolicy};
use ring::signature::{Ed25519KeyPair, KeyPair};
use serde_json::json;
use sha2::{Digest, Sha256};
#[path = "../test_support/mod.rs"]
mod test_support;

fn offer() -> (TrustPolicy, Vec<u8>) {
    let key = Ed25519KeyPair::from_seed_unchecked(&[19; 32]).unwrap();
    let trust = TrustPolicy {
        schema: 1,
        feed: "https://updates.example.test/stable.json".into(),
        public_key: STANDARD.encode(key.public_key().as_ref()),
        allowed_hosts: vec!["updates.example.test".into()],
    };
    let files: Vec<_> = [
        "pocket-supervisor.exe",
        "pocket-agent.exe",
        "pocket-bridge.exe",
        "pocket-desktop.exe",
        "pocket-upgrade.exe",
    ]
    .iter()
    .map(|n| json!({"path":n,"bytes":4,"sha256":format!("{:x}",Sha256::digest(b"test"))}))
    .collect();
    let manifest = serde_json::to_vec(&json!({"version":"1.2.3","files":files})).unwrap();
    let payload=serde_json::to_vec(&json!({"schema":1,"version":"1.2.3","publishedAt":90,"expiresAt":200,"windowsManifest":STANDARD.encode(&manifest),"files":files.iter().map(|f|json!({"path":f["path"],"url":format!("https://updates.example.test/{}",f["path"].as_str().unwrap())})).collect::<Vec<_>>()})).unwrap();
    let signed=serde_json::to_vec(&json!({"payload":STANDARD.encode(&payload),"signature":STANDARD.encode(key.sign(&payload).as_ref())})).unwrap();
    (trust, signed)
}
#[test]
fn staging_promotes_only_verified_inventory_and_preserves_existing_generation() {
    let root = test_support::tempdir();
    let (trust, signed) = offer();
    let verified = verify_offer(&trust, &signed, "1.2.2", 100).unwrap();
    let before = root.path().join("desktop-install.json");
    std::fs::write(&before, b"original install record").unwrap();
    let bin = stage_with(root.path(), &verified, |_, path| {
        std::fs::write(path, b"test")?;
        Ok(())
    })
    .unwrap();
    assert_eq!(
        bin.file_name().unwrap().to_str().unwrap(),
        verified.generation
    );
    assert_eq!(std::fs::read(&before).unwrap(), b"original install record");
    assert_eq!(
        std::fs::read(bin.join("windows-manifest.json")).unwrap(),
        verified.manifest
    );
    let again = stage_with(root.path(), &verified, |_, path| {
        std::fs::write(path, b"test")?;
        Ok(())
    })
    .unwrap();
    assert_eq!(again, bin);
    std::fs::write(bin.join("pocket-agent.exe"), b"evil").unwrap();
    assert!(stage_with(root.path(), &verified, |_, path| {
        std::fs::write(path, b"test")?;
        Ok(())
    })
    .is_err());
}
#[test]
fn interrupted_or_corrupt_download_never_promotes_or_changes_installation() {
    let root = test_support::tempdir();
    let (trust, signed) = offer();
    let verified = verify_offer(&trust, &signed, "1.2.2", 100).unwrap();
    for payload in [b"evil".as_slice(), b"short".as_slice()] {
        assert!(stage_with(root.path(), &verified, |_, path| {
            std::fs::write(path, payload)?;
            Ok(())
        })
        .is_err());
        assert!(!root.path().join("bin").join(&verified.generation).exists());
    }
    assert!(stage_with(root.path(), &verified, |_, _| anyhow::bail!(
        "fixture connection interrupted"
    ))
    .is_err());
    assert!(!root.path().join("desktop-install.json").exists());
}
#[test]
fn signed_offer_binds_exact_manifest_and_rejects_tampering_expiry_downgrade_and_foreign_urls() {
    let (trust, bytes) = offer();
    let verified = verify_offer(&trust, &bytes, "1.2.2", 100).unwrap();
    assert_eq!(verified.version, "1.2.3");
    assert_eq!(verified.files.len(), 5);
    assert!(verify_offer(&trust, &bytes, "1.2.3", 100).is_err());
    assert!(verify_offer(&trust, &bytes, "1.2.2", 201).is_err());
    let mut envelope: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    envelope["payload"] = json!(STANDARD.encode(b"tampered"));
    assert!(verify_offer(
        &trust,
        &serde_json::to_vec(&envelope).unwrap(),
        "1.2.2",
        100
    )
    .is_err());
    let mut wrong = trust.clone();
    wrong.allowed_hosts = vec!["other.test".into()];
    assert!(verify_offer(&wrong, &bytes, "1.2.2", 100).is_err());
}
