//! Per authenticated Pocket session signaling. No broadcast, public room or second identity.
use crate::{broker::Broker, error::{Code, classify}};
use anyhow::{Result, anyhow};
use pocket_p2p::{DataChannelControl, DataChannelStream, Signal};
use pocket_tunnel::ServerIdentity;
use serde::Deserialize;
use serde_json::{Value,json};
use std::{sync::{Arc, atomic::{AtomicBool, AtomicU8, Ordering}}, time::{Duration,Instant}};
use tokio::sync::{mpsc, Semaphore};
use uuid::Uuid;

pub const CAPABILITY: &str = "p2p.v1";
pub const DEFAULT_STUN: &str = "stun:stun.cloudflare.com:3478";
static PEERS: Semaphore = Semaphore::const_new(8);

#[derive(Clone)]
pub struct Binding { pub device: String, pub id: String, pub generation: u64, pub phase: Arc<AtomicU8> }
impl Binding {
    pub fn matches(&self, device:&str, p:&Value)->bool {
        device==self.device && p["p2pNegotiationId"]==self.id && p["p2pNetworkGeneration"].as_u64()==Some(self.generation)
    }
    pub fn authenticated(&self) { self.phase.store(2,Ordering::Release); }
}
#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct Start { negotiation_id:String, network_generation:u64, use_stun:bool }
#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct Envelope { negotiation_id:String, network_generation:u64, #[serde(default)] signals:Vec<RemoteSignal> }
#[derive(Deserialize)]
#[serde(tag="kind",rename_all="camelCase",deny_unknown_fields)]
enum RemoteSignal { Description { sdp:String, #[serde(rename="type")] kind:String }, Candidate { candidate:String, mid:String } }
fn invalid()->anyhow::Error { classify(anyhow!("Invalid P2P envelope"),Code::InvalidParams) }
fn unavailable()->anyhow::Error { classify(anyhow!("P2P candidate unavailable"),Code::Unsupported) }
fn uuid(value:&str)->bool { Uuid::parse_str(value).is_ok_and(|id|id.to_string()==value) }

struct Peer {
    id:String, generation:u64, use_stun:bool, expires:Instant,
    control:DataChannelControl, signals:mpsc::Receiver<Signal>, phase:Arc<AtomicU8>,
    overflow:Arc<AtomicBool>, incoming_candidates:usize, incoming_bytes:usize,
}
impl Peer {
    fn ensure_live(&self)->Result<()> {
        if self.expires<=Instant::now() || self.overflow.load(Ordering::Acquire) || self.phase.load(Ordering::Acquire)>=3 {
            self.control.close(false);return Err(unavailable());
        }
        Ok(())
    }
}
impl Drop for Peer {
    fn drop(&mut self) {
        // The accepted peer owns its stream. Closing the old relay must not tear it down.
        if self.phase.load(Ordering::Acquire)!=2 { self.control.close(false); }
    }
}
pub struct SessionP2p { peer:Option<Peer>, last_start:Option<Instant> }
impl Default for SessionP2p { fn default()->Self { Self{peer:None,last_start:None} } }
impl SessionP2p {
    pub async fn request(&mut self, method:&str, params:Value, device:&str,
        broker:Arc<Broker>, identity:Arc<ServerIdentity>) -> Result<Value> {
        if !broker.device_exists(device).await || !broker.connection.remote_enabled() { return Err(unavailable()); }
        if method=="p2p/start" {
            let p:Start=serde_json::from_value(params).map_err(|_|invalid())?;
            if !uuid(&p.negotiation_id) || p.network_generation==0 || p.network_generation>i64::MAX as u64 { return Err(invalid()); }
            if let Some(peer)=&self.peer {
                if peer.id==p.negotiation_id {
                    if peer.generation!=p.network_generation || peer.use_stun!=p.use_stun { return Err(invalid()); }
                    peer.ensure_live()?;
                    return Ok(Self::receipt(peer));
                }
            }
            if self.last_start.is_some_and(|t|t.elapsed()<Duration::from_secs(5)) { return Err(classify(anyhow!("P2P cooldown"),Code::OperationInProgress)); }
            let permit=PEERS.try_acquire().map_err(|_|unavailable())?;
            let (tx,rx)=mpsc::channel(256);
            let overflow=Arc::new(AtomicBool::new(false)); let failed=overflow.clone();
            let generation=p.network_generation;
            let (stream,control)=tokio::task::spawn_blocking(move || DataChannelStream::new_with_stun(generation,None,
                if p.use_stun{Some(DEFAULT_STUN)}else{None},move |signal|{if tx.try_send(signal).is_err(){failed.store(true,Ordering::Release);}}))
                .await.map_err(|_|unavailable())?.map_err(|_|unavailable())?;
            let phase=Arc::new(AtomicU8::new(0));
            let binding=Binding{device:device.into(),id:p.negotiation_id.clone(),generation,phase:phase.clone()};
            let peer=Peer{id:p.negotiation_id,generation,use_stun:p.use_stun,expires:Instant::now()+Duration::from_secs(90),
                control:control.clone(),signals:rx,phase:phase.clone(),overflow:overflow.clone(),incoming_candidates:0,incoming_bytes:0};
            control.start(true).map_err(|_|unavailable())?;
            let reply=Self::receipt(&peer);
            self.peer=Some(peer);self.last_start=Some(Instant::now());
            tokio::spawn(async move {
                let _permit=permit;
                let accepted=tokio::time::timeout(Duration::from_secs(25),async {
                    control.opened().await?;
                    if overflow.load(Ordering::Acquire) {return Err(anyhow!("Signaling overflow"));}
                    phase.store(1,Ordering::Release);
                    Ok(identity.accept_tls(stream).await?)
                }).await;
                if let Ok(Ok(tls))=accepted {
                    // Binding is checked along with the original device secret at hello.
                    let _=crate::session::serve_bound(tls,broker,binding).await;
                }
                phase.store(3,Ordering::Release);control.close(false);
            });
            return Ok(reply);
        }
        let p:Envelope=serde_json::from_value(params).map_err(|_|invalid())?;
        let peer=self.peer.as_mut().ok_or_else(unavailable)?;
        if p.negotiation_id!=peer.id || p.network_generation!=peer.generation {return Err(invalid());}
        if method=="p2p/cancel" {
            if !p.signals.is_empty(){return Err(invalid());}
            peer.control.close(false); self.peer=None;return Ok(json!({"cancelled":true}));
        }
        peer.ensure_live()?;
        match method {
            "p2p/signal"=>{
                if p.signals.is_empty() || p.signals.len()>32 {return Err(invalid());}
                for signal in p.signals {
                    let (bytes,candidates)=match &signal {
                        RemoteSignal::Description{sdp,kind} if kind=="answer" && !sdp.is_empty() && sdp.len()<=65536 => (sdp.len(),0),
                        RemoteSignal::Candidate{candidate,mid} if !candidate.is_empty() && candidate.len()<=4096 && !mid.is_empty() && mid.len()<=256 =>(candidate.len()+mid.len(),1),
                        _=>return Err(invalid()),
                    };
                    peer.incoming_bytes+=bytes;peer.incoming_candidates+=candidates;
                    if peer.incoming_bytes>256*1024 || peer.incoming_candidates>128 {peer.control.close(false);return Err(invalid());}
                    match signal {
                        RemoteSignal::Description{sdp,kind}=>peer.control.remote_description(peer.generation,&sdp,&kind),
                        RemoteSignal::Candidate{candidate,mid}=>peer.control.remote_candidate(peer.generation,&candidate,&mid),
                    }.map_err(|_|invalid())?;
                }
                Ok(Self::receipt(peer))
            },
            "p2p/poll"=>{
                if !p.signals.is_empty(){return Err(invalid());}
                let mut signals=Vec::new();
                if let Ok(Some(signal))=tokio::time::timeout(Duration::from_millis(250),peer.signals.recv()).await {signals.push(wire(signal));}
                while signals.len()<32 {match peer.signals.try_recv(){Ok(signal)=>signals.push(wire(signal)),Err(_)=>break}}
                let mut result=Self::receipt(peer);result["signals"]=json!(signals);Ok(result)
            }, _=>Err(unavailable())
        }
    }
    fn receipt(peer:&Peer)->Value {
        let phase=match peer.phase.load(Ordering::Acquire){0=>"channel_connecting",1=>"tls_authenticating",2=>"device_authenticated",_=>"ended"};
        json!({"negotiationId":peer.id,"networkGeneration":peer.generation,"phase":phase,"expiresInMs":peer.expires.saturating_duration_since(Instant::now()).as_millis(),"stun":if peer.use_stun{Some(DEFAULT_STUN)}else{None}})
    }
}
fn wire(signal:Signal)->Value {match signal {
    Signal::Description{sdp,kind,..}=>json!({"kind":"description","sdp":sdp,"type":kind}),
    Signal::Candidate{candidate,mid,..}=>json!({"kind":"candidate","candidate":candidate,"mid":mid}),
}}

#[cfg(test)] mod tests {
    use super::*;
    async fn fixture() -> (tempfile::TempDir, Arc<Broker>, Arc<ServerIdentity>) {
        let root=tempfile::tempdir().unwrap();
        let state=root.path().join("agent");crate::storage::private_directory(&state).unwrap();
        let mut devices=crate::auth::DeviceStore::default();
        devices.add("phone".into(),"Fixture".into(),"fixture-secret",1).unwrap();
        crate::storage::atomic_write(&state.join("devices.json"),&serde_json::to_vec(&devices).unwrap()).unwrap();
        let config=crate::config::Config::create("wss://fixture.invalid/pocket".into()).unwrap();
        let (broker,_)=Broker::open(config,state).unwrap();
        let identity=Arc::new(ServerIdentity::load_or_create(root.path().join("identity")).unwrap());
        (root,broker,identity)
    }
    async fn retry_unavailable(ended:bool) {
        assert!(pocket_p2p::available(),"native adapter required for this explicit fixture");
        let (_root,broker,identity)=fixture().await;
        let mut session=SessionP2p::default();
        let id=Uuid::new_v4().to_string();
        let start=json!({"negotiationId":id,"networkGeneration":1,"useStun":false});
        let first=session.request("p2p/start",start.clone(),"phone",broker.clone(),identity.clone()).await.unwrap();
        assert_eq!(first["negotiationId"],id);
        // Live retries are idempotent and do not acquire a second peer or hit cooldown.
        assert_eq!(session.request("p2p/start",start.clone(),"phone",broker.clone(),identity.clone()).await.unwrap()["negotiationId"],id);
        let peer=session.peer.as_mut().unwrap();
        if ended { peer.control.close(false);peer.phase.store(3,Ordering::Release); }
        else { peer.expires=Instant::now()-Duration::from_secs(1); }
        let retry=session.request("p2p/start",start,"phone",broker,identity).await;
        assert!(retry.is_err(),"A stale or ended negotiation must not return a usable start receipt: {retry:?}");
        assert_eq!(crate::error::code(&retry.unwrap_err()),Code::Unsupported);
    }
    #[tokio::test]
    #[ignore="requires native adapter; temporary state, no public STUN or model"]
    async fn expired_start_retry_cannot_bypass_negotiation_expiry() { retry_unavailable(false).await; }
    #[tokio::test]
    #[ignore="requires native adapter; temporary state, no public STUN or model"]
    async fn ended_start_retry_cannot_return_success_receipt() { retry_unavailable(true).await; }
    #[test] fn binding_requires_device_negotiation_and_network_generation() {
        let b=Binding{device:"one".into(),id:Uuid::new_v4().to_string(),generation:3,phase:Arc::new(AtomicU8::new(1))};
        let p=json!({"p2pNegotiationId":b.id,"p2pNetworkGeneration":3});
        assert!(b.matches("one",&p));assert!(!b.matches("two",&p));assert!(!b.matches("one",&json!({"p2pNegotiationId":b.id,"p2pNetworkGeneration":2})));
        b.authenticated();assert_eq!(b.phase.load(Ordering::Acquire),2);
    }
    #[test] fn no_credentials_or_arbitrary_url_in_start() {
        let p=json!({"negotiationId":Uuid::new_v4().to_string(),"networkGeneration":1,"useStun":true});
        assert!(serde_json::from_value::<Start>(p.clone()).is_ok());
        let mut invalid=p;invalid["url"]=json!("http://private");assert!(serde_json::from_value::<Start>(invalid).is_err());
        assert!(serde_json::from_value::<RemoteSignal>(json!({"kind":"description","sdp":"x","type":"answer","deviceSecret":"secret"})).is_err());
    }
}
