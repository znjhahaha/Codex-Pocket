//! Verify a locally signed candidate with the production updater's exact parser.
use anyhow::{Context, Result, ensure};
use pocket_desktop_runtime::updates::{TrustPolicy, verify_offer};

fn main() -> Result<()> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    ensure!(args.len() == 3, "Usage: verify_update_offer TRUST OFFER CURRENT_VERSION");
    let policy: TrustPolicy = serde_json::from_slice(&std::fs::read(&args[0])?)?;
    let version = args[2].to_str().context("Invalid current version")?;
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_secs();
    let verified = verify_offer(&policy, &std::fs::read(&args[1])?, version, now)?;
    println!("Verified version={}, generation={}, files={}", verified.version, verified.generation, verified.files.len());
    Ok(())
}
