//! Local operation probe. Without --start this only reads installed state.
use pocket_desktop_runtime::{launcher,process,storage};
use serde_json::{json,Value};
use std::process::Command;

fn main() {
    let mut stage="installation";
    let result=(||->anyhow::Result<Value>{
        let root=storage::local_app_data()?.join("CodexPocket");
        let (bin,state)=launcher::resolve_release(&root)?;
        stage="helper_status";
        let before=process::agent(&bin.join("pocket-agent.exe"),&state,"status",&[])?;
        let previous=storage::desired(&state);
        if std::env::args().any(|v|v=="--start") {
            stage="desired_state";
            storage::set_desired(&state,true)?;
            stage="startup_integrity";
            launcher::verified_exe(&bin,"windows-common.ps1")?;
            let script=launcher::verified_exe(&bin,"windows-startup.ps1")?;
            stage="startup_request";
            let mut command=Command::new(storage::powershell());
            command.args(["-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File"]).arg(script).args(["-Action","Start"]);
            let _:Value=serde_json::from_slice(&process::run_bounded(command,15)?)?;
        }
        let mut pairing_validated=false;
        if std::env::args().any(|v|v=="--pair") {
            stage="pair_generation";
            let response=process::agent(&bin.join("pocket-agent.exe"),&state,"pair",&[])?;
            stage="pairing_payload";
            let payload=pocket_desktop_runtime::pairing::read_payload(&state,&response,storage::now())?;
            pairing_validated=payload["v"]==1;
        }
        stage="complete";
        Ok(json!({"phase":stage,"previousDesired":previous,"state":before["state"],"connected":before["connected"],"pairingValidated":pairing_validated}))
    })();
    match result {
        Ok(value)=>println!("{value}"),
        Err(error)=>{println!("{}",json!({"phase":stage,"error":error.to_string()}));std::process::exit(1)}
    }
}
