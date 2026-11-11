//! Compile with run-tests.ps1 until the host declares usage_stats in lib.rs.
// Only storage::config_store (unused by statistics) needs this pure hash helper.
// Keep the physical-path verifier itself compiled from the actual storage source.
pub mod auth {
    pub fn hash(value: &str) -> String {
        use sha2::Digest;
        hex::encode(sha2::Sha256::digest(value.as_bytes()))
    }
}
#[path = "../../tests/usage_backend/broker_contract.rs"]
mod broker;
#[path = "../error.rs"]
#[allow(dead_code)]
mod error;
#[path = "../storage.rs"]
pub mod storage;
#[path = "../upstream_versions.rs"]
mod upstream_versions;
#[path = "mod.rs"]
mod usage_stats;
