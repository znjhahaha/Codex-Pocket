#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use pocket_desktop_runtime::supervisor::upgrade::{self, Phase};
fn main() {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.first().is_some_and(|v|v=="--recover-local-update") {
        let result=(|| -> anyhow::Result<()> {
            anyhow::ensure!(args.len()==4,"Usage: --recover-local-update ROOT STATE OPERATION");
            pocket_desktop_runtime::local_update::recover_cancelled_handoff(
                std::path::Path::new(&args[1]),std::path::Path::new(&args[2]),
                args[3].to_str().ok_or_else(||anyhow::anyhow!("Invalid operation identity"))?)
        })();
        if result.is_err() {eprintln!("Local update recovery could not complete; inspect the durable update status.");}
        std::process::exit(if result.is_ok(){0}else{1});
    }
    if args.first().is_some_and(|v|v=="--local-update") {
        let result=(|| -> anyhow::Result<()> {
            anyhow::ensure!(args.len()==2||args.len()==4,"Invalid local coordinator arguments");
            if args.len()==2 {
                let exe=std::env::current_exe()?;
                let bin=exe.parent().ok_or_else(||anyhow::anyhow!("Missing generation"))?;
                pocket_desktop_runtime::installation::generation(bin)?;
                let bins=bin.parent().filter(|p|p.file_name().is_some_and(|n|n=="bin")).ok_or_else(||anyhow::anyhow!("Invalid installation directory"))?;
                let root=bins.parent().ok_or_else(||anyhow::anyhow!("Missing installation root"))?;
                let (_,state)=pocket_desktop_runtime::launcher::resolve_release(root)?;
                return pocket_desktop_runtime::local_update::run(root,&state,args[1].to_str().ok_or_else(||anyhow::anyhow!("Invalid operation identity"))?)
            }
            pocket_desktop_runtime::local_update::run(std::path::Path::new(&args[1]),std::path::Path::new(&args[2]),
                args[3].to_str().ok_or_else(||anyhow::anyhow!("Invalid operation identity"))?)
        })();std::process::exit(if result.is_ok(){0}else{1});
    }
    if args.first().is_some_and(|v|v=="--ui-restart") {
        let result=(|| -> anyhow::Result<()> {
            anyhow::ensure!(args.len()==6&&(args[5]=="visible"||args[5]=="background"),"Invalid UI restart arguments");
            let number=|n:usize|args[n].to_str().ok_or_else(||anyhow::anyhow!("Invalid process identity"));
            pocket_desktop_runtime::ui_update::resume(std::path::Path::new(&args[1]),std::path::Path::new(&args[2]),number(3)?.parse()?,number(4)?.parse()?,args[5]=="visible")
        })();std::process::exit(if result.is_ok(){0}else{1});
    }
    if args.first().is_some_and(|v|v=="--manager-update") {
        let result=(|| -> anyhow::Result<()> {
            anyhow::ensure!(args.len()==5&&args[1]=="--install-root"&&args[3]=="--state","Invalid manager update arguments");
            pocket_desktop_runtime::manager_update::apply(std::path::Path::new(&args[2]),std::path::Path::new(&args[4]))
        })();
        std::process::exit(if result.is_ok(){0}else{1});
    }
    if args.first().is_some_and(|v| v == "--verify-local-source") {
        let result=(|| -> anyhow::Result<()> {
            anyhow::ensure!(args.len()==7 && args[1]=="--source" && args[3]=="--offer" && args[5]=="--trusted-key","Invalid local verification arguments");
            let key=args[6].to_str().ok_or_else(||anyhow::anyhow!("Invalid local trust anchor"))?;
            pocket_desktop_runtime::updates::verify_local_candidate(
                std::path::Path::new(&args[2]),&std::fs::read(&args[4])?,key,"0.0.0",pocket_desktop_runtime::storage::now()
            )?;
            Ok(())
        })();
        std::process::exit(if result.is_ok(){0}else{1});
    }
    if args.first().is_some_and(|v| v == "--local-install") {
        let result = (|| -> anyhow::Result<()> {
            anyhow::ensure!(
                (args.len() == 9 || (args.len() == 11 && args[9] == "--trusted-key"))
                    && args[1] == "--install-root"
                    && args[3] == "--state"
                    && args[5] == "--source"
                    && args[7] == "--offer",
                "Invalid local install arguments"
            );
            let root = std::path::Path::new(&args[2]);
            let state = std::path::Path::new(&args[4]);
            let key = args
                .get(10)
                .and_then(|v| v.to_str())
                .filter(|s| !s.is_empty());
            pocket_desktop_runtime::updates::stage_local(
                root,
                state,
                std::path::Path::new(&args[6]),
                std::path::Path::new(&args[8]),
                key,
            )?;
            pocket_desktop_runtime::updates::apply_local(root, state)?;
            Ok(())
        })();
        std::process::exit(if result.is_ok() { 0 } else { 1 });
    }
    if args.first().is_some_and(|v| v == "--native-restart") {
        let result = (|| -> anyhow::Result<()> {
            anyhow::ensure!(
                args.len() == 7
                    && args[1] == "--install-root"
                    && args[3] == "--state"
                    && args[5] == "--operation-id",
                "Invalid native coordinator arguments"
            );
            pocket_desktop_runtime::native_update::run(
                std::path::Path::new(&args[2]),
                std::path::Path::new(&args[4]),
                args[6]
                    .to_str()
                    .ok_or_else(|| anyhow::anyhow!("Invalid operation"))?,
            )
        })();
        std::process::exit(if result.is_ok() { 0 } else { 1 });
    }
    match run() {
        Ok(status) => std::process::exit(match status.phase {
            Phase::Succeeded => 0,
            Phase::WaitingRestart => 2,
            Phase::RolledBack => 3,
            _ => 1,
        }),
        Err(_) => {
            eprintln!("Upgrade coordinator failed; inspect the durable upgrade status.");
            std::process::exit(1);
        }
    }
}
fn run() -> anyhow::Result<upgrade::UpgradeStatus> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    anyhow::ensure!(
        args.len() == 6
            && args[0] == "--install-root"
            && args[2] == "--state"
            && args[4] == "--operation-id",
        "Invalid coordinator arguments"
    );
    let id = args[5]
        .to_str()
        .ok_or_else(|| anyhow::anyhow!("Invalid operation ID"))?;
    let root = std::path::Path::new(&args[1]);
    let state = std::path::Path::new(&args[3]);
    let status = upgrade::run(root, state, id)?;
    pocket_desktop_runtime::updates::finish_handoff(root, state, &status)?;
    Ok(status)
}
