//! Fixture signer only. The deterministic test key has no production authority.
use anyhow::{ensure, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use ring::signature::{Ed25519KeyPair, KeyPair};
use serde_json::{json, Value};
use std::path::Path;
fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    ensure!(
        args.len() == 3,
        "Usage: sign_update_fixture <windows-manifest.json> <output-directory> <https-base-url>"
    );
    let manifest = std::fs::read(&args[0])?;
    let inventory: Value = serde_json::from_slice(&manifest)?;
    let base = url::Url::parse(&args[2])?;
    ensure!(
        base.scheme() == "https" && base.username().is_empty() && base.password().is_none(),
        "HTTPS fixture origin required"
    );
    let key = Ed25519KeyPair::from_seed_unchecked(&[19; 32])
        .map_err(|_| anyhow::anyhow!("Fixture key construction failed"))?;
    let host = base.host_str().context("Missing fixture host")?;
    let now = pocket_desktop_runtime::storage::now();
    let files=inventory["files"].as_array().context("Missing inventory")?.iter().map(|f|Ok(json!({"path":f["path"],"url":base.join(f["path"].as_str().context("Missing path")?)?.as_str()}))).collect::<Result<Vec<_>>>()?;
    let payload = serde_json::to_vec(
        &json!({"schema":1,"version":inventory["version"],"publishedAt":now,"expiresAt":now+86400,"windowsManifest":STANDARD.encode(&manifest),"files":files}),
    )?;
    let trust = json!({"schema":1,"feed":base.join("stable.json")?.as_str(),"publicKey":STANDARD.encode(key.public_key().as_ref()),"allowedHosts":[host]});
    let signed = json!({"payload":STANDARD.encode(&payload),"signature":STANDARD.encode(key.sign(&payload).as_ref())});
    let out = Path::new(&args[1]);
    std::fs::create_dir_all(out)?;
    std::fs::write(
        out.join("windows-update-trust.fixture.json"),
        serde_json::to_vec_pretty(&trust)?,
    )?;
    std::fs::write(
        out.join("stable.fixture.json"),
        serde_json::to_vec_pretty(&signed)?,
    )?;
    println!(
        "Wrote fixture-only trust and signed offer. No installed state or release version changed."
    );
    Ok(())
}
