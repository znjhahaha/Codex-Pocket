//! Read-only proof for independent managed Hosts. No native request, replay,
//! reconciliation, shutdown or recovery-record writer is available here.
use super::{private_json, ProcessIdentity};
use crate::{installation, storage};
use anyhow::{ensure, Context, Result};
use pocket_core::{directory::{RuntimeAdvertisement, RuntimeSource}, native_process::NativeProcess};
use pocket_runtime_ipc::{Descriptor, Health, HostClient};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{collections::{BTreeMap, BTreeSet}, io::Read, path::{Path, PathBuf}, time::Duration};

#[cfg(test)]
#[path = "restart_preservation_tests.rs"]
mod tests;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
struct Startup {
    schema: u32,
    home_key: String,
    config_home: PathBuf,
    native_binary: PathBuf,
    installation_generation: String,
    reservation: String,
    phase: String,
    host_process: NativeProcess,
    descriptor_generation: String,
}

#[derive(Deserialize)]
struct Claim {
    #[serde(flatten)]
    advertisement: RuntimeAdvertisement,
    #[serde(default)]
    auxiliary: bool,
    native_process: NativeProcess,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
pub(super) struct HostProof {
    runtime: String,
    generation: String,
    descriptor: PathBuf,
    home: PathBuf,
    host: ProcessIdentity,
    native: ProcessIdentity,
    startup: Startup,
    // Only record identities; no native bodies or descriptor credentials.
    retained: BTreeMap<String, String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Proof {
    hosts: Vec<HostProof>,
    #[serde(default)]
    external: Vec<ExternalProof>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
struct ExternalProof {
    runtime: String,
    source: String,
    role: String,
    store: String,
    native: ProcessIdentity,
}

fn external_role(source: &str, role: &str) -> bool {
    matches!((source,role),("cli","cliDaemon")|("desktop","desktop")|("desktop","desktopFollower"))
}

fn external_proofs(h: &Value, ownership: &Value) -> Result<Vec<ExternalProof>> {
    let claims: Vec<Claim> = serde_json::from_value(ownership.clone())?;
    let mut result=Vec::new();
    for field in ["runtimes","auxiliaryRuntimes"] {
        for r in h[field].as_array().context("preservation_runtime_inventory_missing")? {
            let source=r["source"].as_str().unwrap_or("");
            let role=r["role"]["kind"].as_str().unwrap_or("");
            if !external_role(source,role) {continue;}
            let id=r["id"].as_str().context("preservation_runtime_unknown")?;
            let claim=claims.iter().find(|c|c.advertisement.id.runtime_id==id).context("preservation_unclaimed_runtime")?;
            let native=ProcessIdentity::capture(claim.native_process.pid)?;
            ensure!(native_identity(&native)==claim.native_process,"preservation_external_process_changed");
            result.push(ExternalProof {runtime:id.into(),source:source.into(),role:role.into(),store:claim.advertisement.config_store_id.clone(),native});
        }
    }
    result.sort_by(|a,b|a.runtime.cmp(&b.runtime));
    Ok(result)
}

fn native_identity(p: &ProcessIdentity) -> NativeProcess {
    NativeProcess {pid:p.pid, created_at:p.created_at}
}

fn verify_owner_images(root: &Path, owners: [&ProcessIdentity;2]) -> Result<()> {
    let bin=owners[0].executable.parent().context("preservation_owner_image_missing")?;
    let generation=installation::generation(bin)?;
    ensure!(bin==root.join("bin").join(generation), "preservation_owner_image_changed");
    for (owner,name) in owners.into_iter().zip(["pocket-supervisor.exe","pocket-agent.exe"]) {
        ensure!(owner.executable==crate::launcher::verified_exe(bin,name)?, "preservation_owner_image_changed");
    }
    Ok(())
}

fn inventory(h: &Value, ownership: &Value, hosts: &[HostProof], external: &[ExternalProof], owners: [&ProcessIdentity; 2]) -> Result<()> {
    let computer = h["computerId"].as_str().filter(|s|!s.is_empty()).context("preservation_computer_missing")?;
    let claims: Vec<Claim> = serde_json::from_value(ownership.clone())?;
    let mut by_id = BTreeMap::new();
    let mut processes = BTreeSet::new();
    ensure!(!hosts.is_empty() || !external.is_empty(), "preservation_inventory_empty");
    for host in hosts {
        ensure!(by_id.insert(host.runtime.as_str(),host).is_none(), "preservation_duplicate_host");
        for p in [&host.host,&host.native] {
            ensure!(p.pid!=0 && p.created_at!=0 && owners.iter().all(|o|o.pid!=p.pid)
                && processes.insert(p.pid), "preservation_owner_not_independent");
        }
    }
    let mut external_by_id=BTreeMap::new();
    let mut external_processes=BTreeMap::new();
    for e in external {
        ensure!(external_role(&e.source,&e.role) && !by_id.contains_key(e.runtime.as_str())
            && external_by_id.insert(e.runtime.as_str(),e).is_none()
            && e.native.pid!=0 && e.native.created_at!=0
            && owners.iter().all(|o|o.pid!=e.native.pid) && !processes.contains(&e.native.pid),
            "preservation_external_owner_invalid");
        if let Some(previous)=external_processes.insert(e.native.pid,&e.native) {
            ensure!(previous==&e.native,"preservation_external_process_changed");
        }
    }
    let mut claimed = BTreeMap::new();
    for claim in &claims {
        let a=&claim.advertisement;
        ensure!(a.id.computer_id==computer && !claim.auxiliary
            && claimed.insert(a.id.runtime_id.as_str(),claim).is_none(), "preservation_claim_changed");
        if let Some(host)=by_id.get(a.id.runtime_id.as_str()) {
            let home=pocket_runtime_ipc::support::normalized_home(&host.home)?;
            let store=format!("{:x}",Sha256::digest(home.strip_prefix(r"\\?\").unwrap_or(&home).as_bytes()));
            ensure!(a.source==RuntimeSource::Cli && a.config_store_id==store
                && claim.native_process==native_identity(&host.native), "preservation_claim_changed");
        } else {
            let e=external_by_id.get(a.id.runtime_id.as_str()).context("preservation_unverified_claim")?;
            let source=match a.source {RuntimeSource::Cli=>"cli",RuntimeSource::Desktop=>"desktop"};
            ensure!(source==e.source && a.config_store_id==e.store
                && claim.native_process==native_identity(&e.native),"preservation_external_claim_changed");
        }
    }
    ensure!(claimed.len()==hosts.len()+external.len(), "preservation_unclaimed_host");
    let mut registered = BTreeSet::new();
    for field in ["runtimes","auxiliaryRuntimes"] {
        for r in h[field].as_array().context("preservation_runtime_inventory_missing")? {
            let id=r["id"].as_str().context("preservation_runtime_unknown")?;
            ensure!(registered.insert(id),"preservation_runtime_changed");
            if let Some(host)=by_id.get(id) {
                ensure!(r["role"]["kind"]=="managedHost" && r["role"]["generation"]==host.generation,
                    "preservation_runtime_changed");
            } else {
                let e=external_by_id.get(id).context("preservation_runtime_unknown")?;
                // Connection/inventory snapshots may change during a desktop
                // reconnect. They cannot change the captured native lifetime:
                // Pocket will not stop that process or release its claims.
                ensure!(r["source"]==e.source && r["role"]["kind"]==e.role,
                    "preservation_external_runtime_changed");
            }
        }
    }
    // An authenticated independent Host remains authoritative when an old
    // broker could not recreate its adapter after a package update. Its durable
    // claim, descriptor, process identities and recovery records were verified
    // above. An offline native process is also retained, with its uncertain
    // ownership unchanged; disconnect does not prevent restarting only Pocket.
    ensure!(external.iter().all(|e|registered.contains(e.runtime.as_str()))
        && registered.len()<=hosts.len()+external.len(),"preservation_runtime_inventory_missing");
    // Optional discovery failures confer no ownership. Every registered runtime
    // and retained claim above is still bound to its authenticated live Host.
    Ok(())
}

fn host_image(root: &Path, generation: &str) -> Result<PathBuf> {
    ensure!(generation.len()==64 && generation.bytes().all(|b|b.is_ascii_hexdigit()), "preservation_installation_invalid");
    let bin=root.join("bin").join(generation);
    ensure!(installation::generation(&bin)?==generation, "preservation_installation_changed");
    let manifest:Value=storage::read(&bin.join("windows-manifest.json"))?;
    let entries=manifest["files"].as_array().context("preservation_inventory_missing")?;
    let mut matches=entries.iter().filter(|e|e["path"]=="pocket-runtime-host.exe");
    let entry=matches.next().context("preservation_host_image_missing")?;
    ensure!(matches.next().is_none(), "preservation_host_image_duplicate");
    let path=bin.join("pocket-runtime-host.exe");
    let mut file=storage::open_physical(&path)?;
    ensure!(entry["bytes"].as_u64()==Some(file.metadata()?.len()), "preservation_host_image_changed");
    let mut hash=Sha256::new();let mut buffer=[0;65536];
    loop {let n=file.read(&mut buffer)?;if n==0 {break} hash.update(&buffer[..n]);}
    ensure!(entry["sha256"]==format!("{:x}",hash.finalize()), "preservation_host_image_changed");
    Ok(path)
}

fn retained_records(value: &Value, d: &Descriptor) -> Result<BTreeMap<String,String>> {
    ensure!(value["schema"]==2 && value["runtime_id"]==d.runtime_id && value["generation"]==d.generation
        && value["reconciliation_required"].is_boolean(), "preservation_recovery_changed");
    let mut retained=BTreeMap::new();
    for namespace in ["commands","answers"] {
        for (key,record) in value[namespace].as_object().context("preservation_recovery_missing")? {
            let generation=record["generation"].as_str().filter(|g|!g.is_empty()).context("preservation_recovery_invalid")?;
            ensure!(record["completed"].is_boolean(), "preservation_recovery_invalid");
            retained.insert(format!("{namespace}:{key}"),generation.to_owned());
        }
    }
    Ok(retained)
}

fn prepare_host(root: &Path, directory: &Path) -> Result<(HostProof, Descriptor)> {
    let startup:Startup=serde_json::from_value(private_json(&directory.join("startup.json"))?)?;
    let home=startup.config_home.clone();
    let normalized=pocket_runtime_ipc::support::normalized_home(&home)?;
    let home_key=format!("{:x}",Sha256::digest(normalized.as_bytes()));
    ensure!(startup.schema==1 && startup.phase=="ready" && startup.home_key==home_key
        && directory.file_name().and_then(|v|v.to_str())==Some(home_key.as_str())
        && uuid::Uuid::parse_str(&startup.reservation).is_ok(),
        "preservation_startup_unverified");
    let path=directory.join("descriptor.json");
    let d=pocket_runtime_ipc::load_descriptor(&path,&home)?;
    ensure!(Some(&startup.host_process)==d.host_process.as_ref()
        && startup.descriptor_generation==d.generation, "preservation_descriptor_changed");
    let host=ProcessIdentity::capture(d.pid)?;let native=ProcessIdentity::capture(d.native_pid)?;
    ensure!(Some(native_identity(&host))==d.host_process && Some(native_identity(&native))==d.native_process
        && host.executable==host_image(root,&startup.installation_generation)?
        && startup.native_binary==native.executable,
        "preservation_process_changed");
    let retained=retained_records(&private_json(&directory.join("recovery.json"))?,&d)?;
    Ok((HostProof{runtime:d.runtime_id.clone(),generation:d.generation.clone(),descriptor:path,home,host,native,startup,retained},d))
}

fn healthy(d: &Descriptor, h: &Health) -> Result<()> {
    ensure!(h.protocol==pocket_runtime_ipc::PROTOCOL && h.initialized && h.runtime_id==d.runtime_id
        && h.generation==d.generation && h.pid==d.pid && h.native_pid==d.native_pid
        && h.host_process==d.host_process && h.native_process==d.native_process
        && h.capabilities.trusted_broker_only && !h.capabilities.phone_native_rpc && h.capabilities.generation_fencing
        && h.capabilities.survives_broker_disconnect && h.capabilities.persisted_recovery
        && h.pending_requests==0, "preservation_host_unverified");
    // Active turns and historical reconciliation are owned by the independent Host.
    Ok(())
}

fn registry_paths(state: &Path) -> Result<Vec<PathBuf>> {
    let registry=state.join("managed-runtimes");storage::unredirected(&registry)?;
    // An installation used only for native desktop/CLI control may not have
    // created an assistant Host yet. Its external process proofs stand alone.
    if !registry.try_exists()? {return Ok(Vec::new())}
    let mut paths=std::fs::read_dir(&registry)?.map(|e|e.map(|e|e.path())).collect::<std::io::Result<Vec<_>>>()?;
    paths.sort();
    Ok(paths)
}
pub(super) fn prove(root: &Path, state: &Path, h: &Value, owners: [&ProcessIdentity;2]) -> Result<Proof> {
    verify_owner_images(root,owners)?;
    // The verified Pocket supervisor launches the agent without a kill-on-close
    // job (supervisor.rs); its ChildJob is assigned only to the network helper
    // (connectivity/mod.rs). Registry and Host launches use kill_on_drop(false).
    // With distinct live identities and the authenticated survival capability,
    // terminating these two owners cannot close a Pocket-owned native job.
    // Membership in an unrelated inherited scheduling job is not exit evidence.
    let mut prepared=Vec::new();
    for path in registry_paths(state)? {
        storage::unredirected(&path)?;
        ensure!(path.is_dir(), "preservation_registry_unknown");
        prepared.push(prepare_host(root,&path)?);
    }
    let ownership=private_json(&state.join("ownership.json"))?;
    let proof=Proof{hosts:prepared.iter().map(|(p,_)|p.clone()).collect(),external:external_proofs(h,&ownership)?};
    inventory(h,&ownership,&proof.hosts,&proof.external,owners)?;
    let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    runtime.block_on(async {
        tokio::time::timeout(Duration::from_secs(15),async {
            for (p,d) in &prepared {
                let mut client=HostClient::attach(d).await?;
                healthy(d,&client.health().await?)?;
                // Check the persisted binding again after the authenticated exchange.
                ensure!(serde_json::to_value(pocket_runtime_ipc::load_descriptor(&p.descriptor,&p.home)?)?==serde_json::to_value(d)?
                    && ProcessIdentity::capture(p.host.pid)?==p.host && ProcessIdentity::capture(p.native.pid)?==p.native,
                    "preservation_owner_replaced");
            }
            Ok::<_,anyhow::Error>(())
        }).await?
    })?;
    Ok(proof)
}

impl Proof {
    pub(super) fn require_retained(&self, current: &Self) -> Result<()> {
        ensure!(self.hosts.len()==current.hosts.len() && self.external==current.external, "preservation_inventory_changed");
        for (old,new) in self.hosts.iter().zip(&current.hosts) {
            let mut identity=old.clone();identity.retained=new.retained.clone();
            ensure!(identity==*new && old.retained.iter().all(|(k,v)|new.retained.get(k)==Some(v)),
                "preservation_retained_identity_changed");
        }
        Ok(())
    }
    pub(super) fn verify_inventory(&self, state: &Path, h: &Value, owners: [&ProcessIdentity;2]) -> Result<()> {
        ensure!(registry_paths(state)?.iter().map(|p|p.as_path()).eq(self.hosts.iter().map(|h|h.descriptor.parent().unwrap())),
            "preservation_inventory_changed");
        inventory(h,&private_json(&state.join("ownership.json"))?,&self.hosts,&self.external,owners)?;
        for e in &self.external {
            ensure!(ProcessIdentity::capture(e.native.pid)?==e.native,"preservation_external_process_changed");
        }
        for p in &self.hosts {
            let d=pocket_runtime_ipc::load_descriptor(&p.descriptor,&p.home)?;
            let directory=p.descriptor.parent().context("preservation_descriptor_changed")?;
            let retained=retained_records(&private_json(&directory.join("recovery.json"))?,&d)?;
            ensure!(d.runtime_id==p.runtime && d.generation==p.generation
                && d.host_process==Some(native_identity(&p.host)) && d.native_process==Some(native_identity(&p.native))
                && serde_json::from_value::<Startup>(private_json(&directory.join("startup.json"))?)?==p.startup
                && p.retained.iter().all(|(k,v)|retained.get(k)==Some(v))
                && ProcessIdentity::capture(p.host.pid)?==p.host && ProcessIdentity::capture(p.native.pid)?==p.native,
                "preservation_owner_replaced");
        }
        Ok(())
    }
}
