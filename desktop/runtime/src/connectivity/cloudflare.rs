use super::{ConnectivityRequest,gateway,secrets,valid_hostname};
use crate::storage;
use anyhow::{Result,bail,Context};
use serde::{Deserialize,Serialize};
use serde_json::{Value,json};
use std::{path::Path,time::Duration,io::Read};

const API:&str="https://api.cloudflare.com/client/v4";
#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
pub struct FixedTunnelRequest{
    pub api_token:String,pub account_id:String,pub zone_id:String,pub hostname:String,pub gateway_url:String,
}
#[derive(Clone,Debug,Serialize,Deserialize)]
#[serde(rename_all="camelCase")]
pub struct TunnelReceipt{
    pub operation_id:String,pub account_id:String,pub zone_id:String,pub hostname:String,pub tunnel_id:Option<String>,pub dns_id:Option<String>,pub phase:String,
}
struct Api{client:reqwest::blocking::Client,token:String,base:String}
impl Api{
    fn new(token:&str)->Result<Self>{Self::with_base(token,API)}
    fn with_base(token:&str,base:&str)->Result<Self>{
        if token.is_empty()||token.len()>16384||!token.bytes().all(|b|(33..127).contains(&b)){bail!("Invalid Cloudflare authorization")}
        Ok(Self{client:reqwest::blocking::Client::builder().timeout(Duration::from_secs(20)).redirect(reqwest::redirect::Policy::none()).build()?,token:token.into(),base:base.into()})
    }
    fn request(&self,method:reqwest::Method,path:&str,body:Option<Value>)->Result<Value>{
        let mut request=self.client.request(method,format!("{}{path}",self.base)).bearer_auth(&self.token);
        if let Some(body)=body{request=request.json(&body);}
        let response=request.send().map_err(|_|anyhow::anyhow!("Cloudflare request did not return; verify deployment state before retrying"))?;
        let status=response.status();let mut bytes=Vec::new();response.take(1024*1024+1).read_to_end(&mut bytes).map_err(|_|anyhow::anyhow!("Cloudflare response could not be read"))?;
        if bytes.len()>1024*1024{bail!("Cloudflare response exceeded limit")}
        if !status.is_success(){bail!("Cloudflare request failed (HTTP {}); check authorization and resource state",status.as_u16())}
        let value:Value=serde_json::from_slice(&bytes).map_err(|_|anyhow::anyhow!("Cloudflare response was not valid JSON"))?;
        if value["success"]!=true{bail!("Cloudflare rejected this configuration")}
        Ok(value["result"].clone())
    }
    fn get(&self,path:&str)->Result<Value>{self.request(reqwest::Method::GET,path,None)}
}
fn id(value:&str)->Result<&str>{if value.len()!=32||!value.bytes().all(|c|c.is_ascii_hexdigit()){bail!("Invalid Cloudflare resource ID")};Ok(value)}
fn rows(value:Value)->Result<Value>{let items=value.as_array().context("Cloudflare directory is not a list")?;Ok(Value::Array(items.iter().map(|v|json!({"id":v["id"],"name":v["name"],"status":v["status"]})).collect()))}
pub fn cloudflare_accounts(token:&str)->Result<Value>{rows(Api::new(token)?.get("/accounts?per_page=50")?)}
pub fn cloudflare_zones(token:&str,account_id:&str)->Result<Value>{id(account_id)?;rows(Api::new(token)?.get(&format!("/zones?account.id={account_id}&per_page=50"))?)}

/// API token is consumed only by the local command, never returned in the receipt.
pub fn setup_fixed(state:&Path,mut request:FixedTunnelRequest)->Result<TunnelReceipt>{
    storage::private_dir(state)?;let _lock=storage::Lock::acquire(&state.join("cloudflare-setup.lock"))?;
    id(&request.account_id)?;id(&request.zone_id)?;gateway(&request.gateway_url)?;
    if !valid_hostname(&request.hostname){bail!("Invalid tunnel hostname")}
    request.hostname=request.hostname.to_ascii_lowercase();
    let receipt_path=state.join("fixed-tunnel.json");
    if receipt_path.exists(){
        let old:TunnelReceipt=storage::read(&receipt_path)?;
        if old.phase!="removed"{bail!("An existing tunnel setup is recorded; inspect or remove it before creating another")}
        uuid::Uuid::parse_str(&old.operation_id)?;
        storage::atomic(&state.join(format!("removed-{}.json",old.operation_id)),&old)?;
    }
    let api=Api::new(&request.api_token)?;
    let zone=api.get(&format!("/zones/{}",request.zone_id))?;
    let domain=zone["name"].as_str().context("Zone name missing")?;
    if zone["account"]["id"]!=request.account_id||!request.hostname.to_ascii_lowercase().ends_with(&format!(".{}",domain.to_ascii_lowercase())){bail!("Choose a subdomain in the selected Cloudflare zone")}
    let existing=api.get(&format!("/zones/{}/dns_records?name={}&per_page=100",request.zone_id,request.hostname))?;
    if !existing.as_array().is_some_and(Vec::is_empty){bail!("The hostname already has a DNS record; choose an unused subdomain")}
    let mut receipt=TunnelReceipt{operation_id:uuid::Uuid::new_v4().to_string(),account_id:request.account_id.clone(),zone_id:request.zone_id.clone(),hostname:request.hostname.clone(),tunnel_id:None,dns_id:None,phase:"creating_tunnel".into()};
    storage::atomic(&receipt_path,&receipt)?;
    let result=(||->Result<()>{
        let tunnel=api.request(reqwest::Method::POST,&format!("/accounts/{}/cfd_tunnel",request.account_id),Some(json!({"name":format!("codex-pocket-{}",receipt.operation_id),"config_src":"cloudflare"})))?;
        let tunnel_id=tunnel["id"].as_str().filter(|s|uuid::Uuid::parse_str(s).is_ok()).context("Tunnel identity missing")?.to_owned();
        receipt.tunnel_id=Some(tunnel_id.clone());receipt.phase="configuring_route".into();storage::atomic(&receipt_path,&receipt)?;
        let token=if let Some(token)=tunnel["token"].as_str(){token.to_owned()}else{api.get(&format!("/accounts/{}/cfd_tunnel/{tunnel_id}/token",request.account_id))?.as_str().context("Tunnel credential missing")?.to_owned()};
        secrets::save(state,"tunnel-token",&token)?;
        api.request(reqwest::Method::PUT,&format!("/accounts/{}/cfd_tunnel/{tunnel_id}/configurations",request.account_id),Some(json!({"config":{"ingress":[{"hostname":request.hostname,"service":request.gateway_url},{"service":"http_status:404"}]}})))?;
        receipt.phase="creating_dns".into();storage::atomic(&receipt_path,&receipt)?;
        let dns=api.request(reqwest::Method::POST,&format!("/zones/{}/dns_records",request.zone_id),Some(json!({"type":"CNAME","name":request.hostname,"content":format!("{tunnel_id}.cfargotunnel.com"),"proxied":true,"comment":format!("Codex Pocket {}",receipt.operation_id)})))?;
        let dns_id=dns["id"].as_str().context("DNS identity missing")?;id(dns_id)?;receipt.dns_id=Some(dns_id.into());
        receipt.phase="configured".into();storage::atomic(&receipt_path,&receipt)?;
        secrets::save(state,"cloudflare-api",&request.api_token)?;
        storage::atomic(&state.join("desired.json"),&ConnectivityRequest{mode:"fixed".into(),enabled:true,gateway_url:request.gateway_url,hostname:Some(request.hostname)})?;
        Ok(())
    })();
    if result.is_err(){receipt.phase="needs_review".into();let _=storage::atomic(&receipt_path,&receipt);}
    result?;Ok(receipt)
}

/// Explicit rollback only touches identities recorded by this setup, verifying DNS still points here.
pub fn rollback_fixed(state:&Path,api_token:Option<String>)->Result<TunnelReceipt>{
    let _lock=storage::Lock::acquire(&state.join("cloudflare-setup.lock"))?;
    let path=state.join("fixed-tunnel.json");let mut receipt:TunnelReceipt=storage::read(&path)?;
    if receipt.phase=="removed"{return Ok(receipt)}
    id(&receipt.account_id)?;id(&receipt.zone_id)?;
    let mut desired:ConnectivityRequest=storage::read(&state.join("desired.json")).unwrap_or_default();
    if desired.enabled&&desired.mode=="fixed" {
        desired.enabled=false;storage::atomic(&state.join("desired.json"),&desired)?;
        // The supervisor owns the child; never delete a tunnel while its connector is still live.
        bail!("Fixed tunnel is stopping; wait for it to stop before removing its resources")
    }
    let status:super::ConnectivityStatus=storage::read(&state.join("status.json")).unwrap_or_default();
    if status.mode=="fixed"&&status.phase!="stopped"{bail!("Wait for the fixed tunnel process to stop before removing resources")}
    let token=api_token.unwrap_or(secrets::load(state,"cloudflare-api").unwrap_or_default());let api=Api::new(&token)?;
    let tunnel=receipt.tunnel_id.clone().context("Creation outcome is unknown; inspect Cloudflare using the setup ID")?;
    uuid::Uuid::parse_str(&tunnel)?;
    if let Some(dns)=&receipt.dns_id{
        id(dns)?;let live=api.get(&format!("/zones/{}/dns_records/{dns}",receipt.zone_id))?;
        if live["name"]!=receipt.hostname||live["content"]!=format!("{tunnel}.cfargotunnel.com"){bail!("DNS changed outside Pocket; rollback stopped")}
        api.request(reqwest::Method::DELETE,&format!("/zones/{}/dns_records/{dns}",receipt.zone_id),None)?;
        receipt.dns_id=None;receipt.phase="removing_tunnel".into();storage::atomic(&path,&receipt)?;
    }
    api.request(reqwest::Method::DELETE,&format!("/accounts/{}/cfd_tunnel/{tunnel}",receipt.account_id),None)?;
    receipt.phase="removed".into();receipt.tunnel_id=None;storage::atomic(&path,&receipt)?;
    if desired.mode=="fixed"{desired.enabled=false;storage::atomic(&state.join("desired.json"),&desired)?;}
    for name in ["tunnel-token.secret","cloudflare-api.secret"] {let file=state.join(name);if file.exists(){storage::plain(&file)?;std::fs::remove_file(file)?;}}
    Ok(receipt)
}

#[cfg(test)]mod tests{
    use super::*;
    #[test]fn ids_and_hostnames_cannot_inject_api_paths(){assert!(id("abc/../secrets").is_err());assert!(!valid_hostname("demo.example.com\nAuthorization"));assert!(id(&"a".repeat(32)).is_ok());}
    #[test]fn receipt_never_serializes_credentials(){let r=TunnelReceipt{operation_id:"op".into(),account_id:"account".into(),zone_id:"zone".into(),hostname:"demo.example.org".into(),tunnel_id:None,dns_id:None,phase:"starting".into()};let json=serde_json::to_string(&r).unwrap();assert!(!json.contains("token"));}
    #[test]fn cf_error_body_and_credentials_are_not_returned(){
        let server=std::net::TcpListener::bind("127.0.0.1:0").unwrap();let addr=server.local_addr().unwrap();
        let t=std::thread::spawn(move||{use std::io::{Read,Write};let(mut s,_)=server.accept().unwrap();let mut b=[0u8;2048];let _=s.read(&mut b);let body="SECRET upstream body";write!(s,"HTTP/1.1 403 Forbidden\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",body.len(),body).unwrap();});
        let api=Api::with_base("fixture-secret",&format!("http://{addr}")).unwrap();let e=api.get("/test").unwrap_err().to_string();assert!(!e.contains("SECRET"));assert!(!e.contains("fixture-secret"));assert!(e.contains("403"));t.join().unwrap();
    }
}
