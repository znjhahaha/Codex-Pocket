use anyhow::{bail,Context,Result};
use std::{io::Read,process::{Command,Stdio},thread,time::{Duration,Instant}};
pub fn hidden(command:&mut Command){#[cfg(windows)]{use std::os::windows::process::CommandExt;command.creation_flags(0x08000000);}}
pub fn run_bounded(mut command:Command,seconds:u64)->Result<Vec<u8>>{
    // PowerShell 7's inherited PSModulePath can make Windows PowerShell load an
    // incompatible Security module. Let 5.1 construct its own standard paths.
    command.env_remove("PSModulePath");
    hidden(&mut command);command.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null());
    let mut child=command.spawn().context("Could not start local helper")?;
    let stdout=child.stdout.take().context("Missing output pipe")?;
    let reader=thread::spawn(move||{let mut bytes=Vec::new();stdout.take(1024*1024+1).read_to_end(&mut bytes).map(|_|bytes)});
    let deadline=Instant::now()+Duration::from_secs(seconds);
    let status=loop{
        if let Some(s)=child.try_wait()?{break s}
        if Instant::now()>=deadline{let _=child.kill();let _=child.wait();let _=reader.join();bail!("Local helper timed out")}
        thread::sleep(Duration::from_millis(40));
    };
    let bytes=reader.join().map_err(|_|anyhow::anyhow!("Output reader failed"))??;
    if !status.success(){bail!("Local helper returned an error (sensitive output omitted)")}
    if bytes.len()>1024*1024{bail!("Local helper output exceeds limit")}
    Ok(bytes)
}
pub fn agent(binary:&std::path::Path,state:&std::path::Path,verb:&str,extra:&[&str])->Result<serde_json::Value>{
    if !["status","devices","pair","revoke"].contains(&verb){bail!("Unsupported helper command")}
    crate::storage::plain(binary)?;crate::storage::plain(state)?;
    let mut c=Command::new(binary);c.arg(verb).arg("--state").arg(state).arg("--json").args(extra);
    Ok(serde_json::from_slice(&run_bounded(c,12)?).context("Helper response is not valid JSON")?)
}
#[cfg(windows)]
pub struct ChildJob(windows_sys::Win32::Foundation::HANDLE);
#[cfg(windows)]
impl ChildJob{
    pub fn new()->Result<Self>{unsafe{use windows_sys::Win32::System::JobObjects::*;let handle=CreateJobObjectW(std::ptr::null(),std::ptr::null());if handle.is_null(){bail!("Cannot create helper job")};let job=Self(handle);let mut info:JOBOBJECT_EXTENDED_LIMIT_INFORMATION=std::mem::zeroed();info.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;if SetInformationJobObject(handle,JobObjectExtendedLimitInformation,&info as *const _ as _,std::mem::size_of_val(&info) as u32)==0{bail!("Cannot protect helper lifetime")};Ok(job)}}
    pub fn assign(&self,child:&std::process::Child)->Result<()>{use std::os::windows::io::AsRawHandle;unsafe{if windows_sys::Win32::System::JobObjects::AssignProcessToJobObject(self.0,child.as_raw_handle())==0{bail!("Cannot attach owned helper to job")}};Ok(())}
}
#[cfg(windows)]impl Drop for ChildJob{fn drop(&mut self){unsafe{windows_sys::Win32::Foundation::CloseHandle(self.0);}}}
