//! Desired tunnel configuration is written by the UI; only the supervisor spawns children.
pub mod cloudflare;
pub mod secrets;
pub mod ssh_deploy;
use anyhow::{Result,Context,bail};
use serde::{Deserialize,Serialize};
use std::{path::PathBuf,process::{Child,Command,Stdio},io::{BufRead,BufReader},sync::mpsc,time::{Duration,Instant}};
use sha2::{Digest,Sha256};
use crate::{storage,process};

pub const CLOUDFLARED_VERSION:&str="2026.10.7";
pub const CLOUDFLARED_SHA256:&str="2493dbc168923bb3e59e5e7716f71deced92992eea9acd6e9d74f84ac7751343";

#[derive(Clone,Debug,Serialize,Deserialize,PartialEq,Eq)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
pub struct ConnectivityRequest {
    pub mode:String,
    pub enabled:bool,
    pub gateway_url:String,
    #[serde(default)] pub hostname:Option<String>,
}
impl Default for ConnectivityRequest {
    fn default()->Self{Self{mode:"off".into(),enabled:false,gateway_url:"http://127.0.0.1:17892".into(),hostname:None}}
}
#[derive(Clone,Debug,Serialize,Deserialize)]
#[serde(rename_all="camelCase")]
pub struct ConnectivityStatus {
    pub phase:String,pub mode:String,pub enabled:bool,pub hostname:Option<String>,pub public_url:Option<String>,
    pub last_error:Option<String>,pub operation_id:String,pub updated_at:u64,
}
impl Default for ConnectivityStatus {
    fn default()->Self{Self{phase:"stopped".into(),mode:"off".into(),enabled:false,hostname:None,public_url:None,last_error:None,operation_id:String::new(),updated_at:0}}
}
pub fn gateway(value:&str)->Result<url::Url>{
    let u=url::Url::parse(value)?;
    if u.scheme()!="http" || !matches!(u.host_str(),Some("127.0.0.1"|"[::1]"|"::1")) || u.port().is_none()
        || !u.username().is_empty() || u.password().is_some() || u.query().is_some() || u.fragment().is_some() || !matches!(u.path(),""|"/") {bail!("Tunnel target must be a dedicated loopback HTTP port")}
    Ok(u)
}
pub fn valid_hostname(host:&str)->bool {
    host.len()<=253&&host.split('.').count()>=2&&host.split('.').all(|p|!p.is_empty()&&p.len()<=63&&!p.starts_with('-')&&!p.ends_with('-')&&p.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'-'))
}
fn public_hostname(line:&str)->Option<String>{
    line.split_whitespace().find_map(|part|{
        let p=part.trim_matches(|c:char|!c.is_ascii_alphanumeric()&&c!=':'&&c!='/'&&c!='.'&&c!='-');
        let u=url::Url::parse(p).ok()?;let h=u.host_str()?;
        (u.scheme()=="https"&&h.ends_with(".trycloudflare.com")&&valid_hostname(h)).then(||h.into())
    })
}

pub struct ConnectivityManager {
    root:PathBuf,tools:PathBuf,child:Option<Child>,events:Option<mpsc::Receiver<String>>,current:Option<ConnectivityRequest>,
    status:ConnectivityStatus,started:Option<Instant>,retry_after:Option<Instant>,
    #[cfg(windows)] job:Option<process::ChildJob>,
}
impl ConnectivityManager {
    pub fn new(state_dir:PathBuf,tools_dir:PathBuf)->Result<Self>{
        storage::private_dir(&state_dir)?;storage::plain(&tools_dir)?;
        Ok(Self{root:state_dir,tools:tools_dir,child:None,events:None,current:None,status:ConnectivityStatus::default(),started:None,retry_after:None,#[cfg(windows)]job:None})
    }
    pub fn status(&self)->ConnectivityStatus {
        storage::read(&self.root.join("status.json")).unwrap_or_else(|_|self.status.clone())
    }
    pub fn configure(&self,request:ConnectivityRequest)->Result<ConnectivityStatus>{
        gateway(&request.gateway_url)?;
        if !matches!(request.mode.as_str(),"off"|"quick"|"fixed"){bail!("Unknown tunnel mode")}
        if request.mode=="fixed"&&(request.hostname.as_deref().is_none_or(|h|!valid_hostname(h))||!self.root.join("tunnel-token.secret").is_file()){bail!("Configure a fixed tunnel first")}
        let _lock=storage::Lock::acquire(&self.root.join("configure.lock"))?;
        storage::atomic(&self.root.join("desired.json"),&request)?;
        let status=ConnectivityStatus{phase:if request.enabled{"starting"}else{"stopping"}.into(),mode:request.mode,enabled:request.enabled,hostname:request.hostname,operation_id:uuid::Uuid::new_v4().to_string(),updated_at:storage::now(),..Default::default()};
        storage::atomic(&self.root.join("status.json"),&status)?;Ok(status)
    }
    pub fn reconcile(&mut self)->Result<()> {
        let desired:ConnectivityRequest=match storage::read(&self.root.join("desired.json")){Ok(v)=>v,Err(_) if !self.root.join("desired.json").exists()=>Default::default(),Err(e)=>return Err(e)};
        if self.current.as_ref()!=Some(&desired){self.stop_owned();self.retry_after=None;self.current=Some(desired.clone());}
        if !desired.enabled||desired.mode=="off"{
            self.status=ConnectivityStatus{mode:desired.mode,updated_at:storage::now(),..Default::default()};return self.persist();
        }
        if let Some(child)=&mut self.child{
            if child.try_wait()?.is_some(){self.child=None;self.status.phase="failed".into();self.status.last_error=Some("隧道进程退出，将自动重试".into());self.retry_after=Some(Instant::now()+Duration::from_secs(10));}
        }
        if self.child.is_none()&&self.retry_after.is_none_or(|t|Instant::now()>=t){
            if let Err(_)=self.start(&desired){self.status.phase="failed".into();self.status.last_error=Some("隧道启动失败，请检查组件完整性、网关和网络".into());self.retry_after=Some(Instant::now()+Duration::from_secs(15));}
        }
        if let Some(events)=&self.events{
            for event in events.try_iter().take(32){
                if let Some(host)=event.strip_prefix("hostname:"){self.status.hostname=Some(host.into());self.status.public_url=Some(format!("wss://{host}"));}
                if event=="registered"{self.status.phase="tunnel_connected".into();self.status.last_error=None;}
            }
        }
        if self.status.phase=="starting"&&self.started.is_some_and(|t|t.elapsed()>Duration::from_secs(45)){
            self.status.phase="waiting_network".into();self.status.last_error=Some("等待 Cloudflare 建立连接，可检查网络或切换中转".into());
        }
        self.status.updated_at=storage::now();self.persist()
    }
    fn persist(&self)->Result<()>{storage::atomic(&self.root.join("status.json"),&self.status)}
    fn start(&mut self,desired:&ConnectivityRequest)->Result<()> {
        gateway(&desired.gateway_url)?;
        let binary=self.tools.join("cloudflared.exe");storage::unredirected(&binary)?;
        if format!("{:x}",Sha256::digest(std::fs::read(&binary)?))!=CLOUDFLARED_SHA256{bail!("Bundled tunnel checksum mismatch")}
        let mut command=Command::new(binary);process::hidden(&mut command);
        command.current_dir(&self.root).arg("tunnel").arg("--no-autoupdate").arg("--loglevel").arg("info");
        if desired.mode=="quick"{command.arg("--url").arg(&desired.gateway_url);}else{
            command.arg("run").env("TUNNEL_TOKEN",secrets::load(&self.root,"tunnel-token")?);
        }
        command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped());
        let mut child=command.spawn().context("Could not launch tunnel component")?;
        #[cfg(windows)] {
            match process::ChildJob::new().and_then(|job|job.assign(&child).map(|_|job)) {
                Ok(job)=>self.job=Some(job),
                Err(error)=>{let _=child.kill();let _=child.wait();return Err(error)}
            }
        }
        let stderr=child.stderr.take().context("Tunnel status pipe missing")?;
        let(tx,rx)=mpsc::sync_channel(16);
        std::thread::spawn(move||{
            let mut reader=BufReader::new(stderr);let mut buffer=Vec::new();
            loop{buffer.clear();use std::io::Read;let count=(&mut reader).take(8193).read_until(b'\n',&mut buffer);if count.ok().filter(|n|*n>0).is_none(){break;}if buffer.len()>8192{continue;}
                let text=String::from_utf8_lossy(&buffer);
                if let Some(h)=public_hostname(&text){let _=tx.try_send(format!("hostname:{h}"));}
                if text.contains("Registered tunnel connection"){let _=tx.try_send("registered".into());}
            }
        });
        self.child=Some(child);self.events=Some(rx);self.started=Some(Instant::now());
        self.status=ConnectivityStatus{phase:"starting".into(),mode:desired.mode.clone(),enabled:true,hostname:desired.hostname.clone(),public_url:desired.hostname.as_ref().map(|h|format!("wss://{h}")),last_error:None,operation_id:uuid::Uuid::new_v4().to_string(),updated_at:storage::now()};Ok(())
    }
    fn stop_owned(&mut self){if let Some(mut child)=self.child.take(){let _=child.kill();let _=child.wait();}#[cfg(windows)]{self.job=None;}self.events=None;self.started=None;}
}
impl Drop for ConnectivityManager{fn drop(&mut self){self.stop_owned();}}

#[cfg(test)] mod tests{
    use super::*;
    #[test] fn tunnel_gateway_is_loopback_only(){assert!(gateway("http://127.0.0.1:17892").is_ok());for bad in ["http://example.org:80","http://127.0.0.1:80/secret","http://user:pass@127.0.0.1:80","https://127.0.0.1:80"]{assert!(gateway(bad).is_err());}}
    #[test] fn log_parser_accepts_only_public_quick_hostname(){assert_eq!(public_hostname("INF | https://gentle-name.trycloudflare.com |"),Some("gentle-name.trycloudflare.com".into()));assert!(public_hostname("https://trycloudflare.com.evil.test/token").is_none());}
    #[test] fn configured_start_is_not_reported_as_online(){let d=tempfile::tempdir().unwrap();let tools=tempfile::tempdir().unwrap();let manager=ConnectivityManager::new(d.path().into(),tools.path().into()).unwrap();let s=manager.configure(ConnectivityRequest{mode:"quick".into(),enabled:true,..Default::default()}).unwrap();assert_eq!(s.phase,"starting");assert!(s.public_url.is_none());}
}
