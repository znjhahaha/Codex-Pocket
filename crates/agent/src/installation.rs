//! Running helper identity, derived from the executing binary's manifest.
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
pub fn current() -> Value {
    static STAMP: std::sync::OnceLock<Value> = std::sync::OnceLock::new();
    STAMP.get_or_init(|| {
        let generation = std::env::current_exe().ok().and_then(|p| p.parent().map(std::path::Path::to_owned))
            .and_then(|p| std::fs::read(p.join("windows-manifest.json")).ok())
            .filter(|bytes| bytes.len() <= 1024 * 1024)
            .map(|bytes| format!("{:x}", Sha256::digest(bytes)));
        let executable_sha256=std::env::current_exe().ok().and_then(|p|std::fs::read(p).ok())
            .map(|bytes|format!("{:x}",Sha256::digest(bytes)));
        json!({"version":env!("CARGO_PKG_VERSION"),"generation":generation,"pid":std::process::id(),
            "processIdentity":pocket_core::native_process::NativeProcess::capture(std::process::id()).ok(),
            "executableSha256":executable_sha256,"controlProtocol":1})
    }).clone()
}
