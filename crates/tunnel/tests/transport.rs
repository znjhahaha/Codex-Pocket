use pocket_tunnel::{
    MAX_JSON_FRAME, SERVER_NAME, ServerIdentity, pinned_client_config, read_json, write_json,
};
use rustls::{
    ClientConfig, ServerConfig,
    pki_types::{CertificateDer, PrivatePkcs8KeyDer, ServerName, UnixTime},
};
use serde_json::{Value, json};
use std::{sync::Arc, time::Duration};
use tokio::io::duplex;
use tokio_rustls::{TlsAcceptor, TlsConnector};

fn identity() -> (tempfile::TempDir, ServerIdentity) {
    let dir = tempfile::tempdir().unwrap();
    let id = ServerIdentity::load_or_create(dir.path().join("identity")).unwrap();
    (dir, id)
}

#[tokio::test]
async fn clean_eof_and_truncation_are_distinct() {
    assert!(
        read_json::<_, Value>(&mut &b""[..])
            .await
            .unwrap()
            .is_none()
    );
    for bytes in [&b"\0"[..], &b"\0\0\0"[..], &b"\0\0\0\x04{}"[..]] {
        assert!(read_json::<_, Value>(&mut &bytes[..]).await.is_err());
    }
}

#[tokio::test]
async fn rejects_bad_frames_without_payload_in_errors() {
    for payload in [
        b"".as_slice(),
        b"[]",
        b"null",
        b"42",
        b"\"secret\"",
        b"{secret",
        b"{}{}",
        b"{\"secret\":\"\xff\"}",
    ] {
        let mut bytes = (payload.len() as u32).to_be_bytes().to_vec();
        bytes.extend_from_slice(payload);
        let err = read_json::<_, Value>(&mut bytes.as_slice())
            .await
            .unwrap_err();
        assert!(!err.to_string().contains("secret"));
    }
    let bytes = (MAX_JSON_FRAME as u32 + 1).to_be_bytes();
    assert!(read_json::<_, Value>(&mut bytes.as_slice()).await.is_err());
}

#[tokio::test]
async fn serializes_only_objects_before_writing_any_bytes() {
    for value in [
        json!([]),
        json!(null),
        json!(1),
        json!("secret"),
        json!({"secret": "x".repeat(MAX_JSON_FRAME)}),
    ] {
        let mut bytes = Vec::new();
        let err = write_json(&mut bytes, &value).await.unwrap_err();
        assert!(bytes.is_empty());
        assert!(!err.to_string().contains("secret"));
    }
    let message = json!({"method":"hello", "params":{}});
    let mut bytes = Vec::new();
    write_json(&mut bytes, &message).await.unwrap();
    assert_eq!(&bytes[..4], &(bytes.len() as u32 - 4).to_be_bytes());
    assert_eq!(
        read_json::<_, Value>(&mut bytes.as_slice()).await.unwrap(),
        Some(message)
    );
}

#[tokio::test]
async fn write_json_flushes_buffered_transport_before_returning() {
    let mut writer = tokio::io::BufWriter::new(Vec::new());
    write_json(&mut writer, &json!({"id":"buffered","result":true}))
        .await
        .unwrap();
    assert!(
        !writer.get_ref().is_empty(),
        "complete JSON frame must reach underlying transport"
    );
    let value: Value = read_json(&mut writer.get_ref().as_slice())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(value["id"], "buffered");
}

#[tokio::test]
async fn accepts_exact_frame_limit_and_consecutive_objects() {
    let message = json!({"v":"x".repeat(MAX_JSON_FRAME - 8)});
    let mut bytes = Vec::new();
    write_json(&mut bytes, &message).await.unwrap();
    assert_eq!(bytes.len(), MAX_JSON_FRAME + 4);
    write_json(&mut bytes, &json!({})).await.unwrap();
    let mut input = bytes.as_slice();
    assert_eq!(
        read_json::<_, Value>(&mut input).await.unwrap(),
        Some(message)
    );
    assert_eq!(
        read_json::<_, Value>(&mut input).await.unwrap(),
        Some(json!({}))
    );
}

#[test]
fn identity_is_persistent_and_has_exact_der_pin() {
    use sha2::{Digest, Sha256};
    let (dir, id) = identity();
    let config: Arc<ServerConfig> = id.server_config();
    assert!(Arc::ptr_eq(&config, &id.server_config()));
    let loaded = ServerIdentity::load_or_create(dir.path().join("identity")).unwrap();
    assert_eq!(id.certificate_der(), loaded.certificate_der());
    assert_eq!(
        id.private_key_der().secret_der(),
        loaded.private_key_der().secret_der()
    );
    assert_eq!(
        id.certificate_sha256(),
        hex::encode(Sha256::digest(id.certificate_der()))
    );
    assert_eq!(SERVER_NAME, "pocket.local");
}

#[test]
fn rejects_partial_corrupt_mismatched_and_invalid_certificate_without_replacement() {
    let (dir, id) = identity();
    let path = dir.path().join("identity");
    let cert_path = path.join("certificate.der");
    let key_path = path.join("private-key.der");
    let cert = std::fs::read(&cert_path).unwrap();
    let key = std::fs::read(&key_path).unwrap();
    std::fs::remove_file(&cert_path).unwrap();
    assert!(ServerIdentity::load_or_create(&path).is_err());
    assert!(!cert_path.exists());
    std::fs::write(&cert_path, b"corrupt").unwrap();
    assert!(ServerIdentity::load_or_create(&path).is_err());
    assert_eq!(std::fs::read(&cert_path).unwrap(), b"corrupt");
    std::fs::write(&cert_path, &cert).unwrap();
    let (_, other) = identity();
    std::fs::write(&key_path, other.private_key_der().secret_der()).unwrap();
    assert!(ServerIdentity::load_or_create(&path).is_err());
    std::fs::write(&key_path, &key).unwrap();
    for (name, expired, future, damaged) in [
        ("wrong.local", false, false, false),
        (SERVER_NAME, true, false, false),
        (SERVER_NAME, false, true, false),
        (SERVER_NAME, false, false, true),
    ] {
        let (mut der, key) = certificate(name, expired, future);
        if damaged {
            *der.last_mut().unwrap() ^= 1;
        }
        std::fs::write(&cert_path, der).unwrap();
        std::fs::write(&key_path, key).unwrap();
        assert!(ServerIdentity::load_or_create(&path).is_err());
    }
    assert_eq!(id.certificate_der(), cert);
}

fn certificate(name: &str, expired: bool, future: bool) -> (Vec<u8>, Vec<u8>) {
    let key = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).unwrap();
    let mut params = rcgen::CertificateParams::new(vec![name.to_owned()]).unwrap();
    if expired {
        params.not_before = rcgen::date_time_ymd(2000, 1, 1);
        params.not_after = rcgen::date_time_ymd(2001, 1, 1);
    }
    if future {
        params.not_before = rcgen::date_time_ymd(2090, 1, 1);
        params.not_after = rcgen::date_time_ymd(2091, 1, 1);
    }
    (
        params.self_signed(&key).unwrap().der().to_vec(),
        key.serialize_der(),
    )
}

async fn round_trip(version: &'static rustls::SupportedProtocolVersion) {
    let (_dir, id) = identity();
    let config = pinned_client_config(id.certificate_der(), &id.certificate_sha256()).unwrap();
    // Limit only the client; the identity server must negotiate both supported versions.
    let verifier =
        pocket_tunnel::pinned_server_verifier(id.certificate_der(), &id.certificate_sha256())
            .unwrap();
    let mut client =
        ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_protocol_versions(&[version])
            .unwrap()
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
    // Exercise the exported pinned helper separately with the default protocol set.
    if version.version == rustls::ProtocolVersion::TLSv1_3 {
        client = config;
    }
    let (a, b) = duplex(2048);
    let server = async {
        let mut tls = id.accept_tls(a).await.unwrap();
        assert_eq!(tls.get_ref().1.protocol_version(), Some(version.version));
        let value = read_json::<_, Value>(&mut tls).await.unwrap().unwrap();
        write_json(&mut tls, &value).await.unwrap();
    };
    let client = async {
        let mut tls = TlsConnector::from(Arc::new(client))
            .connect(ServerName::try_from(SERVER_NAME).unwrap(), b)
            .await
            .unwrap();
        let message = json!({"method":"hello", "params":{"protocol":1}});
        write_json(&mut tls, &message).await.unwrap();
        assert_eq!(
            read_json::<_, Value>(&mut tls).await.unwrap(),
            Some(message)
        );
    };
    tokio::time::timeout(Duration::from_secs(5), async {
        tokio::join!(server, client);
    })
    .await
    .unwrap();
}

#[tokio::test]
async fn real_duplex_tls12_round_trip() {
    round_trip(&rustls::version::TLS12).await;
}
#[tokio::test]
async fn real_duplex_tls13_round_trip() {
    round_trip(&rustls::version::TLS13).await;
}

#[tokio::test]
async fn pinned_client_rejects_wrong_pin_name_and_validity() {
    use sha2::{Digest, Sha256};
    for (name, expired, future, wrong_pin) in [
        (SERVER_NAME, false, false, true),
        ("wrong.local", false, false, false),
        (SERVER_NAME, true, false, false),
        (SERVER_NAME, false, true, false),
    ] {
        let (cert, key) = certificate(name, expired, future);
        let pin = if wrong_pin {
            "00".repeat(32)
        } else {
            hex::encode(Sha256::digest(&cert))
        };
        let client = pinned_client_config(&cert, &pin).unwrap();
        let server =
            ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_safe_default_protocol_versions()
                .unwrap()
                .with_no_client_auth()
                .with_single_cert(
                    vec![CertificateDer::from(cert)],
                    PrivatePkcs8KeyDer::from(key).into(),
                )
                .unwrap();
        let (a, b) = duplex(4096);
        let (server_result, client_result) = tokio::join!(
            TlsAcceptor::from(Arc::new(server)).accept(a),
            TlsConnector::from(Arc::new(client))
                .connect(ServerName::try_from(SERVER_NAME).unwrap(), b)
        );
        assert!(client_result.is_err());
        assert!(server_result.is_err());
    }
}

#[test]
fn standard_verifier_checks_the_presented_certificate() {
    let (_dir, id) = identity();
    // Test the same verifier used by the helper through its public constructor.
    let verifier =
        pocket_tunnel::pinned_server_verifier(id.certificate_der(), &id.certificate_sha256())
            .unwrap();
    let cert = CertificateDer::from(id.certificate_der().to_vec());
    let name = ServerName::try_from(SERVER_NAME).unwrap();
    assert!(
        verifier
            .verify_server_cert(&cert, &[], &name, &[], UnixTime::now())
            .is_ok()
    );
    // A different certificate must not be accepted by the trusted pinned verifier.
    let (_, other) = identity();
    assert!(
        verifier
            .verify_server_cert(
                &CertificateDer::from(other.certificate_der().to_vec()),
                &[],
                &name,
                &[],
                UnixTime::now()
            )
            .is_err()
    );
}

#[derive(Debug)]
struct WrongSigningKey(Arc<rustls::sign::CertifiedKey>);
impl rustls::server::ResolvesServerCert for WrongSigningKey {
    fn resolve(
        &self,
        _: rustls::server::ClientHello<'_>,
    ) -> Option<Arc<rustls::sign::CertifiedKey>> {
        Some(self.0.clone())
    }
}

#[tokio::test]
async fn rejects_forged_tls12_and_tls13_handshake_signatures() {
    let (_dir, id) = identity();
    let (_other_dir, other) = identity();
    let wrong_key =
        rustls::crypto::ring::sign::any_supported_type(&other.private_key_der()).unwrap();
    let certified = Arc::new(rustls::sign::CertifiedKey::new(
        vec![CertificateDer::from(id.certificate_der().to_vec())],
        wrong_key,
    ));
    for version in [&rustls::version::TLS12, &rustls::version::TLS13] {
        let config =
            ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_protocol_versions(&[version])
                .unwrap()
                .with_no_client_auth()
                .with_cert_resolver(Arc::new(WrongSigningKey(certified.clone())));
        let client = pinned_client_config(id.certificate_der(), &id.certificate_sha256()).unwrap();
        let (a, b) = duplex(4096);
        let (_, result) = tokio::join!(
            TlsAcceptor::from(Arc::new(config)).accept(a),
            TlsConnector::from(Arc::new(client))
                .connect(ServerName::try_from(SERVER_NAME).unwrap(), b)
        );
        let error = result.expect_err("forged handshake signature was accepted");
        assert!(
            error.to_string().contains("BadSignature"),
            "unexpected TLS failure: {error}"
        );
    }
}

#[tokio::test]
async fn invalid_utf8_is_rejected_even_when_target_ignores_values() {
    let payload = b"{\"ignored\":\"\xff\"}";
    let mut frame = (payload.len() as u32).to_be_bytes().to_vec();
    frame.extend_from_slice(payload);
    assert!(
        read_json::<_, serde::de::IgnoredAny>(&mut frame.as_slice())
            .await
            .is_err()
    );
}

#[tokio::test]
async fn idle_tls_handshake_is_bounded() {
    let (_dir, id) = identity();
    let (a, _b) = duplex(64);
    let start = std::time::Instant::now();
    let error = id
        .accept_tls(a)
        .await
        .expect_err("idle handshake succeeded");
    assert!(matches!(error, pocket_tunnel::Error::HandshakeTimeout));
    assert!(start.elapsed() < Duration::from_secs(15));
}

#[test]
fn rejects_hardlinked_identity_files() {
    let (dir, _) = identity();
    let path = dir.path().join("identity");
    std::fs::hard_link(path.join("private-key.der"), dir.path().join("key-alias")).unwrap();
    assert!(ServerIdentity::load_or_create(&path).is_err());
}

#[test]
fn concurrent_creators_never_replace_or_disagree() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("identity");
    let results: Vec<_> = std::thread::scope(|scope| {
        let handles: Vec<_> = (0..8)
            .map(|_| scope.spawn(|| ServerIdentity::load_or_create(&path)))
            .collect();
        handles.into_iter().map(|h| h.join().unwrap()).collect()
    });
    let pins: Vec<_> = results
        .into_iter()
        .filter_map(Result::ok)
        .map(|id| id.certificate_sha256())
        .collect();
    assert!(!pins.is_empty());
    assert!(pins.iter().all(|pin| pin == &pins[0]));
}

#[cfg(unix)]
#[test]
fn unix_permissions_and_symlinks_are_rejected() {
    use std::os::unix::fs::{PermissionsExt, symlink};
    let (dir, _) = identity();
    let path = dir.path().join("identity");
    assert_eq!(
        std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
        0o700
    );
    for name in ["certificate.der", "private-key.der", "identity.lock"] {
        assert_eq!(
            std::fs::metadata(path.join(name))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
    }
    std::fs::set_permissions(
        path.join("private-key.der"),
        std::fs::Permissions::from_mode(0o644),
    )
    .unwrap();
    assert!(ServerIdentity::load_or_create(&path).is_err());
    symlink(&path, dir.path().join("alias")).unwrap();
    assert!(ServerIdentity::load_or_create(dir.path().join("alias")).is_err());
}

#[cfg(windows)]
#[test]
fn windows_acl_is_private_before_and_after_reload() {
    use std::process::Command;
    let (dir, _) = identity();
    let path = dir.path().join("identity");
    // PowerShell inspects security descriptors independently of the library.
    for item in [
        path.clone(),
        path.join("certificate.der"),
        path.join("private-key.der"),
        path.join("identity.lock"),
    ] {
        let output = Command::new("powershell.exe").env("POCKET_IDENTITY_TEST_PATH", &item).args(["-NoProfile", "-NonInteractive", "-Command", "$ErrorActionPreference='Stop'; $p=$env:POCKET_IDENTITY_TEST_PATH; if([System.IO.Directory]::Exists($p)){$a=[System.IO.Directory]::GetAccessControl($p)}else{$a=[System.IO.File]::GetAccessControl($p)}; if(!$a.AreAccessRulesProtected){exit 2}; if(@($a.Access).Count -ne 1){exit 3}; if($a.Access[0].IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -ne [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value){exit 4}"]).output().unwrap();
        assert!(
            output.status.success(),
            "ACL validation failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
    assert!(ServerIdentity::load_or_create(&path).is_ok());
    let output = Command::new("powershell.exe").env("POCKET_IDENTITY_TEST_PATH", path.join("private-key.der")).args(["-NoProfile", "-NonInteractive", "-Command", "$ErrorActionPreference='Stop'; $p=$env:POCKET_IDENTITY_TEST_PATH; $a=[System.IO.File]::GetAccessControl($p); $a.SetAccessRuleProtection($false,$true); [System.IO.File]::SetAccessControl($p,$a)"]).output().unwrap();
    assert!(output.status.success());
    assert!(ServerIdentity::load_or_create(&path).is_err());
}
