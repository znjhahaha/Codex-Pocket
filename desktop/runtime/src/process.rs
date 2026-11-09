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
    if !["status","devices","pair","revoke","routes/read","routes/configure"].contains(&verb){bail!("Unsupported helper command")}
    crate::storage::plain(binary)?;crate::storage::plain(state)?;
    let mut c=Command::new(binary);if let Some(action)=verb.strip_prefix("routes/"){c.arg("routes").arg(action);}else{c.arg(verb);}
    c.arg("--state").arg(state).arg("--json").args(extra);
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

/// Current-user authenticated broker control. Never dispatches a native model request.
pub fn local_control(state:&std::path::Path,method:&str,params:serde_json::Value)->Result<serde_json::Value>{
    use std::io::Write;
    if !["session/preferences/read","session/preferences/update","runtime/ensure"].contains(&method){bail!("Unsupported desktop control method")}
    let control:serde_json::Value=crate::storage::read(&state.join("control.json"))?;
    if control["protocol"]!=1{bail!("Unknown local control protocol")}
    let port=control["port"].as_u64().filter(|p|*p>0&&*p<=65535).context("Invalid control port")? as u16;
    let token=control["token"].as_str().filter(|t|!t.is_empty()&&t.len()<=256).context("Missing local authentication")?;
    let mut socket=std::net::TcpStream::connect_timeout(&std::net::SocketAddr::from(([127,0,0,1],port)),Duration::from_secs(2))?;
    socket.set_read_timeout(Some(Duration::from_secs(12)))?;socket.set_write_timeout(Some(Duration::from_secs(2)))?;
    let bytes=serde_json::to_vec(&serde_json::json!({"token":token,"method":method,"params":params}))?;
    if bytes.len()>65536{bail!("Control request exceeds limit")}
    socket.write_all(&(bytes.len() as u32).to_be_bytes())?;socket.write_all(&bytes)?;
    let mut size=[0u8;4];socket.read_exact(&mut size)?;let size=u32::from_be_bytes(size) as usize;
    if size>1024*1024{bail!("Control response exceeds limit")}
    let mut bytes=vec![0;size];socket.read_exact(&mut bytes)?;
    let reply:serde_json::Value=serde_json::from_slice(&bytes)?;
    if reply.get("error").is_some(){bail!("Local control rejected operation; refresh state before retrying")}
    reply.get("result").cloned().context("Invalid local control response")
}

#[cfg(test)]mod control_tests{
 use super::*;use serde_json::json;use std::io::Write;
 #[test]fn local_preferences_use_authenticated_bounded_broker_protocol(){
  let state=tempfile::tempdir().unwrap();let listener=std::net::TcpListener::bind("127.0.0.1:0").unwrap();
  crate::storage::atomic(&state.path().join("control.json"),&json!({"protocol":1,"port":listener.local_addr().unwrap().port(),"token":"fixture-secret"})).unwrap();
  let worker=thread::spawn(move||{let(mut socket,_)=listener.accept().unwrap();let mut n=[0;4];socket.read_exact(&mut n).unwrap();let mut bytes=vec![0;u32::from_be_bytes(n) as usize];socket.read_exact(&mut bytes).unwrap();let request:serde_json::Value=serde_json::from_slice(&bytes).unwrap();assert_eq!(request["token"],"fixture-secret");assert_eq!(request["method"],"session/preferences/update");assert_eq!(request["params"]["knownRevision"],3);let reply=serde_json::to_vec(&json!({"result":{"defaultMode":"desktop","revision":4}})).unwrap();socket.write_all(&(reply.len() as u32).to_be_bytes()).unwrap();socket.write_all(&reply).unwrap();});
  let result=local_control(state.path(),"session/preferences/update",json!({"mode":"desktop","knownRevision":3,"commandId":"fixture-operation"})).unwrap();assert_eq!(result["revision"],4);worker.join().unwrap();
  assert!(local_control(state.path(),"turn/start",json!({})).is_err());
 }
}
