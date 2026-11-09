//! Bounded, host-key-verified deployment of one Pocket-owned Compose project.
use anyhow::{Result,Context,bail};
use base64::Engine;
use serde::{Serialize,Deserialize};
use serde_json::{Value,json};
use sha2::{Digest,Sha256};
use std::{net::{TcpStream,ToSocketAddrs},time::Duration,path::{Path,PathBuf},io::{Read,Write}};
use crate::storage;

#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
pub struct SshTarget {
    pub host:String,
    #[serde(default="ssh_port")]pub port:u16,
    pub username:String,
    pub expected_fingerprint:Option<String>,
    #[serde(default)]pub password:Option<String>,
    #[serde(default)]pub private_key_path:Option<PathBuf>,
    #[serde(default)]pub key_passphrase:Option<String>,
}
fn ssh_port()->u16{22}
#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
pub struct RelayDeployRequest{
    pub target:SshTarget,pub hostname:String,
    #[serde(default)]pub install_dependencies:bool,
}
#[derive(Clone,Debug,Serialize,Deserialize)]
#[serde(rename_all="camelCase")]
pub struct HostInspection {pub fingerprint:String,pub host:String,pub port:u16,pub trusted:bool}
#[derive(Clone,Debug,Serialize,Deserialize)]
#[serde(rename_all="camelCase")]
pub struct DeploymentReceipt{
    pub operation_id:String,pub project:String,pub host:String,pub fingerprint:String,pub hostname:String,pub phase:String,pub image:String,pub updated_at:u64,
}
fn validate_target(t:&SshTarget)->Result<()> {
    if t.port==0||t.host.is_empty()||t.host.len()>253||t.host.chars().any(|c|c.is_whitespace()||c.is_control()||"/\\@'\";".contains(c))||t.username.is_empty()||t.username.len()>64||!t.username.bytes().all(|c|c.is_ascii_alphanumeric()||b"_-".contains(&c)){bail!("Invalid SSH destination")}
    if t.password.as_ref().is_some_and(|s|s.len()>16384)||t.key_passphrase.as_ref().is_some_and(|s|s.len()>16384){bail!("SSH credential exceeds limit")}
    Ok(())
}
fn connect(t:&SshTarget)->Result<(ssh2::Session,String)> {
    validate_target(t)?;
    let addr=(t.host.as_str(),t.port).to_socket_addrs()?.next().context("Server address unavailable")?;
    let tcp=TcpStream::connect_timeout(&addr,Duration::from_secs(8)).map_err(|_|anyhow::anyhow!("SSH server could not be reached"))?;
    tcp.set_read_timeout(Some(Duration::from_secs(20)))?;tcp.set_write_timeout(Some(Duration::from_secs(20)))?;
    let mut session=ssh2::Session::new()?;session.set_tcp_stream(tcp);session.set_timeout(20000);session.handshake().map_err(|_|anyhow::anyhow!("SSH handshake failed"))?;
    let(key,_)=session.host_key().context("SSH host key unavailable")?;
    let fingerprint=format!("SHA256:{}",base64::engine::general_purpose::STANDARD_NO_PAD.encode(Sha256::digest(key)));
    Ok((session,fingerprint))
}
pub fn inspect_host(target:SshTarget)->Result<HostInspection>{
    let(_,fingerprint)=connect(&target)?;
    Ok(HostInspection{trusted:target.expected_fingerprint.as_deref()==Some(&fingerprint),fingerprint,host:target.host,port:target.port})
}
fn authenticated(t:&SshTarget)->Result<(ssh2::Session,String)>{
    let(s,f)=connect(t)?;
    if t.expected_fingerprint.as_deref()!=Some(&f){bail!("SSH host fingerprint has not been confirmed or has changed")}
    if let Some(path)=&t.private_key_path{
        storage::plain(path)?;
        s.userauth_pubkey_file(&t.username,None,path,t.key_passphrase.as_deref()).map_err(|_|anyhow::anyhow!("SSH key authentication failed"))?;
    }else if let Some(password)=&t.password{
        s.userauth_password(&t.username,password).map_err(|_|anyhow::anyhow!("SSH password authentication failed"))?;
    }else{
        let mut agent=s.agent().map_err(|_|anyhow::anyhow!("SSH agent unavailable"))?;
        agent.connect().map_err(|_|anyhow::anyhow!("SSH agent unavailable"))?;agent.list_identities()?;
        for identity in agent.identities()?.iter().take(16){if agent.userauth(&t.username,identity).is_ok(){break;}}
    }
    if !s.authenticated(){bail!("SSH authentication did not complete")};Ok((s,f))
}
fn run(session:&ssh2::Session,script:&str)->Result<String>{
    run_bounded(session,script,25)
}
fn run_bounded(session:&ssh2::Session,script:&str,seconds:u32)->Result<String>{
    if script.len()>64*1024{bail!("Deployment script exceeds limit")}
    session.set_timeout((seconds+5)*1000);
    let mut channel=session.channel_session().map_err(|_|anyhow::anyhow!("SSH channel unavailable"))?;
    channel.handle_extended_data(ssh2::ExtendedData::Merge)?;
    channel.exec(&format!("timeout {seconds} sh -s")).map_err(|_|anyhow::anyhow!("SSH execution unavailable"))?;
    channel.write_all(script.as_bytes())?;channel.send_eof()?;
    let mut bytes=Vec::new();(&mut channel).take(256*1024+1).read_to_end(&mut bytes).map_err(|_|anyhow::anyhow!("Remote step timed out; inspect recorded deployment before retrying"))?;
    if bytes.len()>256*1024{bail!("Remote diagnostics exceeded limit")}
    channel.wait_close()?;
    if channel.exit_status()?!=0 {
        // Only our fixed error markers are returned; arbitrary remote output never reaches UI logs.
        let output=String::from_utf8_lossy(&bytes);
        let code=output.lines().find(|l|l.starts_with("POCKET_ERROR:")).filter(|s|s.len()<160).unwrap_or("POCKET_ERROR:remote_step_failed");
        bail!("{}",code)
    }
    String::from_utf8(bytes).map_err(|_|anyhow::anyhow!("Remote diagnostics encoding invalid"))
}
fn project(state:&Path)->Result<String>{
    let config:Value=storage::read(&state.join("config.json"))?;
    let id=config["computerId"].as_str().context("Computer identity missing")?;uuid::Uuid::parse_str(id)?;
    Ok(format!("pocket-{}",id.replace('-',"")))
}
fn config(state:&Path)->Result<Value>{
    let c:Value=storage::read(&state.join("config.json"))?;
    let get=|name:&str|c[name].as_str().filter(|s|!s.is_empty()&&s.len()<=4096).ok_or_else(||anyhow::anyhow!("Local pairing configuration incomplete"));
    let room=get("room")?;
    if !room.bytes().all(|b|b.is_ascii_alphanumeric()||b"_-".contains(&b)){bail!("Invalid local room identity")}
    Ok(json!({"bind":"0.0.0.0:8080","queue_capacity":64,"rooms":[{"id":room,"helper_token_sha256":format!("{:x}",Sha256::digest(get("helperToken")?.as_bytes())),"phone_token_sha256":format!("{:x}",Sha256::digest(get("phoneToken")?.as_bytes()))}]}))
}
fn bundle(project:&str,hostname:&str,image:&str,relay:&Value)->Result<(String,String,String)>{
    if !super::valid_hostname(hostname)||!project.starts_with("pocket-")||!project.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'-'){bail!("Invalid deployment identity")}
    let compose=format!("name: {project}\nservices:\n  relay:\n    image: {image}\n    restart: unless-stopped\n    init: true\n    read_only: true\n    cap_drop: [ALL]\n    security_opt: [no-new-privileges:true]\n    pids_limit: 64\n    mem_limit: 128m\n    cpus: 0.50\n    volumes:\n      - ./relay.json:/run/pocket/relay.json:ro\n    networks: [pocket]\n    logging:\n      driver: json-file\n      options: {{max-size: 1m, max-file: '2'}}\n  https:\n    image: caddy:2.10.2-alpine\n    restart: unless-stopped\n    ports: ['80:80', '443:443']\n    volumes:\n      - ./Caddyfile:/etc/caddy/Caddyfile:ro\n      - certs:/data\n      - caddy-config:/config\n    networks: [pocket]\nnetworks:\n  pocket:\nvolumes:\n  certs:\n  caddy-config:\n");
    let caddy=format!("{hostname} {{\n    reverse_proxy relay:8080\n}}\n");
    Ok((compose,caddy,serde_json::to_string_pretty(relay)?))
}
pub fn export_bundle(state:&Path,hostname:&str,destination:&Path)->Result<PathBuf>{
    storage::plain(destination)?;if destination.exists(){bail!("Choose a new deployment folder")}
    let project=project(state)?;let image=format!("ghcr.io/znjhahaha/codex-pocket/relay:v{}",env!("CARGO_PKG_VERSION"));
    let(compose,caddy,relay)=bundle(&project,hostname,&image,&config(state)?)?;
    storage::private_dir(destination)?;
    for(name,text)in[("compose.yaml",compose),("Caddyfile",caddy),("relay.json",relay)]{std::fs::write(destination.join(name),text)?;}
    Ok(destination.into())
}
pub fn preflight(state:&Path,target:SshTarget)->Result<Value>{
    let(s,f)=authenticated(&target)?;
    let output=run(&s,"set -eu\n. /etc/os-release\ncase \"$ID\" in ubuntu|debian) ;; *) echo POCKET_ERROR:unsupported_os; exit 1;; esac\ncommand -v docker >/dev/null 2>&1 && echo docker=yes || echo docker=no\nif [ \"$(id -u)\" = 0 ] || sudo -n true 2>/dev/null; then echo privilege=yes; else echo privilege=no; fi\nif ss -ltnH '( sport = :80 or sport = :443 )' 2>/dev/null | grep -q .; then echo ports=occupied; else echo ports=free; fi\n")?;
    let p=project(state)?;
    let owned=run(&s,&format!("set -eu\nelevate='' ; [ \"$(id -u)\" = 0 ] || elevate='sudo -n'\n$elevate sh -c 'test -f /opt/codex-pocket/{p}/.pocket-owner && test \"$(cat /opt/codex-pocket/{p}/.pocket-owner)\" = {p} && echo owned=yes' || true\n"))?;
    Ok(json!({"fingerprint":f,"dockerInstalled":output.lines().any(|l|l=="docker=yes"),"canElevate":output.lines().any(|l|l=="privilege=yes"),"portsFree":output.lines().any(|l|l=="ports=free"),"ownProject":owned.lines().any(|l|l=="owned=yes")}))
}
pub fn deploy(state:&Path,request:RelayDeployRequest)->Result<DeploymentReceipt>{
    let project=project(state)?;let folder=state.join("desktop/relay-deployments");storage::private_dir(&folder)?;
    let _lock=storage::Lock::acquire(&folder.join("deploy.lock"))?;
    let(s,f)=authenticated(&request.target)?;
    let image=format!("ghcr.io/znjhahaha/codex-pocket/relay:v{}",env!("CARGO_PKG_VERSION"));
    let(compose,caddy,relay)=bundle(&project,&request.hostname,&image,&config(state)?)?;
    let mut receipt=DeploymentReceipt{operation_id:uuid::Uuid::new_v4().to_string(),project:project.clone(),host:request.target.host.clone(),fingerprint:f,hostname:request.hostname,phase:"checking".into(),image,updated_at:storage::now()};
    let receipt_path=folder.join(format!("{}.json",receipt.operation_id));storage::atomic(&receipt_path,&receipt)?;
    let b64=|s:&str|base64::engine::general_purpose::STANDARD.encode(s.as_bytes());
    let dependency=if request.install_dependencies {"apt-get update >/dev/null && apt-get install -y docker.io docker-compose-v2 >/dev/null || { echo POCKET_ERROR:docker_install_failed; exit 1; }"}else{"echo POCKET_ERROR:docker_missing; exit 1"};
    // All variable shell fragments are either validated identifiers or base64; passwords are only SSH auth.
    let script=format!(r#"set -eu
if [ "$(id -u)" != 0 ]; then
  sudo -n true 2>/dev/null || {{ echo POCKET_ERROR:passwordless_sudo_required; exit 1; }}
fi
elevate='' ; [ "$(id -u)" = 0 ] || elevate='sudo -n'
$elevate sh -s <<'POCKET_SCRIPT'
set -eu
umask 077
. /etc/os-release
case "$ID" in ubuntu|debian) ;; *) echo POCKET_ERROR:unsupported_os; exit 1;; esac
if ! command -v docker >/dev/null 2>&1; then {dependency}; fi
docker compose version >/dev/null 2>&1 || {{ echo POCKET_ERROR:compose_missing; exit 1; }}
base='/opt/codex-pocket/{project}'
if [ -L /opt/codex-pocket ] || [ -L "$base" ]; then echo POCKET_ERROR:unsafe_managed_path; exit 1; fi
if [ -e "$base" ]; then
  [ -f "$base/.pocket-owner" ] && [ "$(cat "$base/.pocket-owner")" = '{project}' ] || {{ echo POCKET_ERROR:foreign_directory; exit 1; }}
else
  if ss -ltnH '( sport = :80 or sport = :443 )' | grep -q .; then echo POCKET_ERROR:ports_in_use; exit 1; fi
  mkdir -p "$base"
  printf '%s' '{project}' > "$base/.pocket-owner"
fi
cd "$base"
for f in .pocket-owner .pocket-operation compose.yaml Caddyfile relay.json compose.previous.yaml Caddyfile.previous relay.previous.json compose.next.yaml Caddyfile.next relay.next.json; do
  if [ -L "$f" ]; then echo POCKET_ERROR:unsafe_managed_path; exit 1; fi
done
printf '%s' '{operation}' > .pocket-operation
printf '%s' '{compose_b64}' | base64 -d > compose.next.yaml
printf '%s' '{caddy_b64}' | base64 -d > Caddyfile.next
printf '%s' '{relay_b64}' | base64 -d > relay.next.json
chmod 644 relay.next.json
docker compose -f compose.next.yaml config --quiet
docker compose -f compose.next.yaml pull >/dev/null
relay_digest=$(docker image inspect --format '{{{{index .RepoDigests 0}}}}' '{image}')
case "$relay_digest" in ghcr.io/znjhahaha/codex-pocket/relay@sha256:*) ;; *) echo POCKET_ERROR:image_digest_missing; exit 1;; esac
sed -i "s|image: {image}|image: $relay_digest|" compose.next.yaml
if [ -f compose.yaml ]; then cp compose.yaml compose.previous.yaml; cp Caddyfile Caddyfile.previous; cp relay.json relay.previous.json; fi
mv compose.next.yaml compose.yaml
mv Caddyfile.next Caddyfile
mv relay.next.json relay.json
docker compose up -d --wait --wait-timeout 90 >/dev/null
echo POCKET_DEPLOYED
POCKET_SCRIPT
"#,operation=receipt.operation_id,image=receipt.image,compose_b64=b64(&compose),caddy_b64=b64(&caddy),relay_b64=b64(&relay));
    receipt.phase="deploying".into();storage::atomic(&receipt_path,&receipt)?;
    let result=run_bounded(&s,&script,300);
    receipt.phase=if result.is_ok(){"deployed_pending_handshake"}else{"needs_review"}.into();receipt.updated_at=storage::now();storage::atomic(&receipt_path,&receipt)?;
    result?;Ok(receipt)
}
pub fn rollback(state:&Path,target:SshTarget,operation_id:&str)->Result<DeploymentReceipt>{
    uuid::Uuid::parse_str(operation_id)?;
    let folder=state.join("desktop/relay-deployments");
    let _lock=storage::Lock::acquire(&folder.join("deploy.lock"))?;
    let path=state.join("desktop/relay-deployments").join(format!("{operation_id}.json"));
    let mut receipt:DeploymentReceipt=storage::read(&path)?;
    let(s,f)=authenticated(&target)?;
    if receipt.host!=target.host||receipt.fingerprint!=f||receipt.project!=project(state)?{bail!("Deployment identity changed")}
    let script=format!("set -eu\nelevate='' ; [ \"$(id -u)\" = 0 ] || elevate='sudo -n'\n$elevate sh -s <<'POCKET_SCRIPT'\nset -eu\ncd '/opt/codex-pocket/{}'\n[ \"$(cat .pocket-owner)\" = '{}' ] || exit 1\n[ -f compose.previous.yaml ] && [ -f relay.previous.json ] && [ -f Caddyfile.previous ] || {{ echo POCKET_ERROR:no_previous_version; exit 1; }}\ncp compose.previous.yaml compose.yaml\ncp relay.previous.json relay.json\ncp Caddyfile.previous Caddyfile\ndocker compose up -d --wait --wait-timeout 90 >/dev/null\nPOCKET_SCRIPT\n",receipt.project,receipt.project);
    let marker=format!("[ \"$(cat .pocket-operation)\" = '{}' ] || {{ echo POCKET_ERROR:newer_deployment_exists; exit 1; }}\n",receipt.operation_id);
    let script=script.replace("[ -f compose.previous.yaml ]",&(marker+"[ -f compose.previous.yaml ]"));
    run_bounded(&s,&script,120)?;receipt.phase="rolled_back_pending_handshake".into();receipt.updated_at=storage::now();storage::atomic(&path,&receipt)?;Ok(receipt)
}

#[cfg(test)]mod tests{
    use super::*;
    #[test]fn destination_does_not_accept_shell_fragments(){let mut t:SshTarget=serde_json::from_value(json!({"host":"example.org","username":"root","expectedFingerprint":null})).unwrap();assert!(validate_target(&t).is_ok());t.host="example.org; reboot".into();assert!(validate_target(&t).is_err());}
    #[test]fn export_bundle_contains_hashes_not_pairing_secrets(){let r=tempfile::tempdir().unwrap();let s=r.path().join("state");storage::private_dir(&s).unwrap();storage::atomic(&s.join("config.json"),&json!({"computerId":"12345678-1234-4234-8234-123456789abc","room":"room","helperToken":"helper-PRIVATE","phoneToken":"phone-PRIVATE"})).unwrap();let d=r.path().join("export");export_bundle(&s,"relay.example.org",&d).unwrap();let text=std::fs::read_to_string(d.join("relay.json")).unwrap();assert!(!text.contains("PRIVATE"));let c=std::fs::read_to_string(d.join("compose.yaml")).unwrap();assert!(c.contains("read_only: true"));assert!(c.contains("ghcr.io/znjhahaha/codex-pocket/relay:v"));}
}
