use crate::{Error, Result, SERVER_NAME, storage::Directory};
use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256};
use rustls::{
    ClientConfig, RootCertStore, ServerConfig, SignatureScheme,
    client::{
        WebPkiServerVerifier,
        danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    },
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime},
};
use sha2::{Digest, Sha256};
use std::{path::Path, sync::Arc, time::Duration};
use subtle::ConstantTimeEq;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio_rustls::{TlsAcceptor, server::TlsStream};

const CERTIFICATE: &str = "certificate.der";
const PRIVATE_KEY: &str = "private-key.der";
const MAX_IDENTITY_FILE: u64 = 64 * 1024;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

/// Persistent P-256 identity. Intentionally does not implement Debug or Clone.
pub struct ServerIdentity {
    certificate: Vec<u8>,
    key: PrivatePkcs8KeyDer<'static>,
    config: Arc<ServerConfig>,
}

impl ServerIdentity {
    /// Creates only a wholly absent identity. Existing insecure, partial, expired,
    /// or corrupt identities fail closed and are never repaired or replaced.
    /// The directory's parent must already exist. The directory and all files
    /// must be owned by the current user and private; symlinks are rejected.
    pub fn load_or_create(directory: impl AsRef<Path>) -> Result<Self> {
        let directory = Directory::open(directory.as_ref())?;
        let lock = directory.lock()?;
        fs2::FileExt::lock_exclusive(&lock)?;
        let certificate = directory.read(CERTIFICATE, MAX_IDENTITY_FILE)?;
        let key = directory.read(PRIVATE_KEY, MAX_IDENTITY_FILE)?;
        let (certificate, key) = match (certificate, key) {
            (Some(cert), Some(key)) => (cert, key),
            (None, None) => {
                let key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256)
                    .map_err(|_| Error::InvalidIdentity)?;
                let mut params = CertificateParams::new(vec![SERVER_NAME.to_owned()])
                    .map_err(|_| Error::InvalidIdentity)?;
                let now = time::OffsetDateTime::now_utc();
                params.not_before = now - time::Duration::minutes(5);
                params.not_after = now + time::Duration::days(365 * 5);
                let cert = params
                    .self_signed(&key)
                    .map_err(|_| Error::InvalidIdentity)?
                    .der()
                    .to_vec();
                let key = key.serialize_der();
                // Exclusive create, ACL/mode applied before either secret write.
                // An interrupted pair stays partial and will fail on next load.
                directory.write_new(PRIVATE_KEY, &key)?;
                directory.write_new(CERTIFICATE, &cert)?;
                directory.sync()?;
                (cert, key)
            }
            _ => return Err(Error::InvalidIdentity),
        };
        Self::validate(certificate, key)
    }

    fn validate(certificate: Vec<u8>, key: Vec<u8>) -> Result<Self> {
        use x509_parser::prelude::*;
        let (remaining, parsed) =
            X509Certificate::from_der(&certificate).map_err(|_| Error::InvalidIdentity)?;
        if !remaining.is_empty() || parsed.subject() != parsed.issuer() {
            return Err(Error::InvalidIdentity);
        }
        // Trust anchors do not need their own signature verified by WebPKI.
        // Identity loading explicitly checks the self-signature as well.
        parsed
            .verify_signature(None)
            .map_err(|_| Error::InvalidIdentity)?;
        let key = PrivatePkcs8KeyDer::from(key);
        KeyPair::from_pkcs8_der_and_sign_algo(&key, &PKCS_ECDSA_P256_SHA256)
            .map_err(|_| Error::InvalidIdentity)?;
        let certificate_der = CertificateDer::from(certificate.clone());
        let verifier = standard_verifier(&certificate)?;
        verifier
            .verify_server_cert(
                &certificate_der,
                &[],
                &ServerName::try_from(SERVER_NAME).map_err(|_| Error::InvalidIdentity)?,
                &[],
                UnixTime::now(),
            )
            .map_err(|_| Error::InvalidIdentity)?;
        let config =
            ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_safe_default_protocol_versions()
                .map_err(|_| Error::TlsConfiguration)?
                .with_no_client_auth()
                .with_single_cert(vec![certificate_der], key.clone_key().into())
                .map_err(|_| Error::InvalidIdentity)?;
        Ok(Self {
            certificate,
            key,
            config: Arc::new(config),
        })
    }

    pub fn certificate_der(&self) -> &[u8] {
        &self.certificate
    }
    pub fn certificate_sha256(&self) -> String {
        hex::encode(Sha256::digest(&self.certificate))
    }
    pub fn private_key_der(&self) -> PrivateKeyDer<'static> {
        self.key.clone_key().into()
    }

    /// Shared standard Rustls server configuration, supporting TLS 1.2 and 1.3.
    pub fn server_config(&self) -> Arc<ServerConfig> {
        Arc::clone(&self.config)
    }

    pub async fn accept_tls<S>(&self, stream: S) -> Result<TlsStream<S>>
    where
        S: AsyncRead + AsyncWrite + Unpin,
    {
        tokio::time::timeout(
            HANDSHAKE_TIMEOUT,
            TlsAcceptor::from(self.config.clone()).accept(stream),
        )
        .await
        .map_err(|_| Error::HandshakeTimeout)?
        .map_err(|_| Error::TlsHandshake)
    }
}

fn standard_verifier(certificate: &[u8]) -> Result<Arc<WebPkiServerVerifier>> {
    let mut roots = RootCertStore::empty();
    roots
        .add(CertificateDer::from(certificate.to_vec()))
        .map_err(|_| Error::InvalidIdentity)?;
    WebPkiServerVerifier::builder_with_provider(
        Arc::new(roots),
        Arc::new(rustls::crypto::ring::default_provider()),
    )
    .build()
    .map_err(|_| Error::TlsConfiguration)
}

/// A client verifier requiring WebPKI chain, validity, name, and handshake
/// signature checks as well as the exact provisioned leaf DER SHA256 pin.
/// `certificate` is the self-signed trust anchor supplied during pairing.
pub fn pinned_server_verifier(
    certificate: &[u8],
    expected_sha256: &str,
) -> Result<Arc<dyn ServerCertVerifier>> {
    let mut pin = [0; 32];
    if expected_sha256.len() != 64
        || !expected_sha256
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err(Error::InvalidPin);
    }
    hex::decode_to_slice(expected_sha256, &mut pin).map_err(|_| Error::InvalidPin)?;
    Ok(Arc::new(PinnedVerifier {
        standard: standard_verifier(certificate)?,
        pin,
    }))
}

/// Build an integration-test/native Rust client with strict pinning. Connect
/// using `ServerName::try_from(SERVER_NAME)`; a different name is still checked.
pub fn pinned_client_config(certificate: &[u8], expected_sha256: &str) -> Result<ClientConfig> {
    Ok(
        ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()
            .map_err(|_| Error::TlsConfiguration)?
            .dangerous()
            .with_custom_certificate_verifier(pinned_server_verifier(certificate, expected_sha256)?)
            .with_no_client_auth(),
    )
}

// Debug is required by Rustls; deliberately excludes both pin and certificate.
struct PinnedVerifier {
    standard: Arc<WebPkiServerVerifier>,
    pin: [u8; 32],
}
impl std::fmt::Debug for PinnedVerifier {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("PinnedVerifier")
    }
}
impl ServerCertVerifier for PinnedVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        server_name: &ServerName<'_>,
        ocsp_response: &[u8],
        now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, rustls::Error> {
        let verified = self.standard.verify_server_cert(
            end_entity,
            intermediates,
            server_name,
            ocsp_response,
            now,
        )?;
        let actual = Sha256::digest(end_entity.as_ref());
        if !bool::from(actual.as_slice().ct_eq(&self.pin)) {
            return Err(rustls::Error::InvalidCertificate(
                rustls::CertificateError::ApplicationVerificationFailure,
            ));
        }
        Ok(verified)
    }
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        self.standard.verify_tls12_signature(message, cert, dss)
    }
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        self.standard.verify_tls13_signature(message, cert, dss)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.standard.supported_verify_schemes()
    }
}
