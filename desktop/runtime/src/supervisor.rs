use crate::{storage::{self,Lock},process,policy};
use anyhow::{Context,Result};
use serde::{Serialize,Deserialize};
use std::{path::{Path,PathBuf},process::{Command,Child,Stdio},time::{Instant,Duration},thread};
#[derive(Serialize,Deserialize,Clone,Debug,Default)]
#[serde(rename_all="camelCase")]
pub struct Status{pub phase:String,pub pid:u32,pub child_pid:Option<u32>,pub updated_at:u64,pub failures:u32,pub last_exit:Option<i32>}
pub fn run(state:&Path,agent:&Path,owner_directory:Option<PathBuf>)->Result<()> {
    storage::plain(state)?;storage::plain(agent)?;
    for name in ["config.json","agent.lock","control.json","desktop/desired.json","desktop/supervisor.json","desktop/supervisor.lock"] { storage::unredirected(&state.join(name))?; }
    if !state.join("config.json").is_file(){anyhow::bail!("Existing initialized state required")}
    let control=storage::control(state);storage::private_dir(&control)?;
    let owner=owner_directory.unwrap_or(storage::local_app_data()?.join("CodexPocket/control"));storage::private_dir(&owner)?;
    let _user=Lock::acquire(&owner.join("supervisor.lock"))?;
    let _state=Lock::acquire(&control.join("supervisor.lock"))?;
    #[cfg(windows)]let job=process::ChildJob::new()?;
    let mut child:Option<Child>=None;
    let mut status=Status{phase:"stopped".into(),pid:std::process::id(),..Default::default()};
    let mut retry=Instant::now();let mut started=Instant::now();
    loop{
        let enabled=storage::desired(state);
        if let Some(c)=child.as_mut(){
            if !enabled {c.kill().context("Cannot stop owned helper")?;let _=c.wait();child=None;status.phase="stopped".into();status.failures=0;retry=Instant::now();}
            else if let Some(exit)=c.try_wait()?{status.last_exit=exit.code();child=None;if started.elapsed()>Duration::from_secs(60){status.failures=0};retry=Instant::now()+Duration::from_secs(policy::restart_delay(status.failures));status.failures=status.failures.saturating_add(1);status.phase="restarting".into();}
        }
        if policy::should_run(enabled,child.is_some())&&Instant::now()>=retry{
            let mut c=Command::new(agent);c.arg("run").arg("--state").arg(state).env("LOCALAPPDATA",storage::local_app_data()?).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());process::hidden(&mut c);
            match c.spawn(){Ok(mut owned)=>{
                #[cfg(windows)]if let Err(e)=job.assign(&owned){let _=owned.kill();let _=owned.wait();return Err(e)}
                started=Instant::now();child=Some(owned);status.phase="running".into();
            },Err(_)=>{retry=Instant::now()+Duration::from_secs(policy::restart_delay(status.failures));status.failures=status.failures.saturating_add(1);status.phase="restarting".into();}}
        }
        if !enabled {status.phase="stopped".into();}
        status.child_pid=child.as_ref().map(Child::id);status.updated_at=storage::now();
        storage::atomic(&control.join("supervisor.json"),&status)?;
        thread::sleep(Duration::from_millis(500));
    }
}
