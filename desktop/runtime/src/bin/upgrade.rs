#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use pocket_desktop_runtime::supervisor::upgrade::{self, Phase};
fn main() {
    match run() {
        Ok(status) => std::process::exit(match status.phase {
            Phase::Succeeded => 0, Phase::WaitingRestart => 2, Phase::RolledBack => 3, _ => 1,
        }),
        Err(_) => { eprintln!("Upgrade coordinator failed; inspect the durable upgrade status."); std::process::exit(1); }
    }
}
fn run() -> anyhow::Result<upgrade::UpgradeStatus> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    anyhow::ensure!(args.len() == 6 && args[0] == "--install-root" && args[2] == "--state" && args[4] == "--operation-id", "Invalid coordinator arguments");
    let id = args[5].to_str().ok_or_else(|| anyhow::anyhow!("Invalid operation ID"))?;
    upgrade::run(std::path::Path::new(&args[1]), std::path::Path::new(&args[3]), id)
}
