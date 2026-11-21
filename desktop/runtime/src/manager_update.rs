//! Only the short-lived manager is installed under Velopack. Running task hosts,
//! authenticated handoff coordinators and user data remain outside that root.
use anyhow::{Context,Result,ensure};
use serde_json::{Value,json};
use std::path::{Path,PathBuf};
use crate::{storage,updates,launcher};

fn manager_root()->Result<PathBuf>{Ok(storage::local_app_data()?.join("CodexPocketManager"))}
fn cached(state:&Path,generation:&str)->PathBuf{updates::directory(state).join(format!("{generation}-full.nupkg"))}
fn journal(state:&Path)->PathBuf{updates::directory(state).join("manager.json")}

pub fn retain_package(state:&Path,offer:&updates::VerifiedOffer,source:&Path)->Result<()> {
    let target=cached(state,&offer.generation);storage::unredirected(&target)?;
    let mut temp=tempfile::NamedTempFile::new_in(updates::directory(state))?;
    std::io::copy(&mut std::fs::File::open(source)?,&mut temp)?;temp.as_file().sync_all()?;
    temp.persist(&target).map_err(|e|e.error)?;
    storage::atomic(&journal(state),&json!({"phase":"staged","version":offer.version,"generation":offer.generation,"updatedAt":storage::now()}))
}

/// A restart after updater dispatch is reconciled from its installed manifest.
/// A failed manager update does not roll back a backend already accepting work.
pub fn reconcile(root:&Path,state:&Path)->Result<()> {
    if let Err(error)=reconcile_inner(root,state) {
        if journal(state).exists() {
            let mut s=storage::read::<Value>(&journal(state)).ok().filter(Value::is_object).unwrap_or_else(||json!({}));
            s["phase"]=json!("failed");s["reason"]=json!("manager_state_unreadable");s["updatedAt"]=json!(storage::now());
            storage::atomic(&journal(state),&s)?;
        }else{return Err(error)}
    }
    Ok(())
}
fn reconcile_inner(root:&Path,state:&Path)->Result<()> {
    if !journal(state).is_file(){return Ok(())}
    let mut status:Value=storage::read(&journal(state))?;
    ensure!(status.is_object(),"Manager journal is not an object");
    if status["phase"]=="applied"||status["phase"]=="failed"||status["phase"]=="migration_required"{return Ok(())}
    let installation=manager_root()?;
    if !installation.join("Update.exe").is_file(){status["phase"]=json!("migration_required");storage::atomic(&journal(state),&status)?;return Ok(())}
    storage::unredirected(&installation)?;
    let config=velopack::locator::create_config_from_root_dir(&installation);
    let manifest=config.load_manifest()?;
    ensure!(manifest.id=="CodexPocketManager","Manager identity differs");
    if manifest.version.to_string()==status["version"].as_str().unwrap_or("") {
        status["phase"]=json!("applied");return storage::atomic(&journal(state),&status)
    }
    if status["phase"]=="applying" {
        if storage::now().saturating_sub(status["updatedAt"].as_u64().unwrap_or(0))>180 {
            status["phase"]=json!("failed");status["reason"]=json!("manager_version_unconfirmed");storage::atomic(&journal(state),&status)?;
        }
        return Ok(())
    }
    let update=updates::read_status(state)?;
    if !matches!(update.phase.as_str(),"succeeded"|"current")||update.generation.as_deref()!=status["generation"].as_str(){return Ok(())}
    let (bin,installed)=launcher::resolve_release(root)?;ensure!(installed==state,"Manager update state mismatch");
    let mut cmd=std::process::Command::new(launcher::verified_exe(&bin,"pocket-upgrade.exe")?);
    cmd.arg("--manager-update").arg("--install-root").arg(root).arg("--state").arg(state);
    crate::process::hidden(&mut cmd);
    status["phase"]=json!("applying");status["updatedAt"]=json!(storage::now());storage::atomic(&journal(state),&status)?;
    if cmd.spawn().is_err(){status["phase"]=json!("failed");status["reason"]=json!("manager_dispatch_failed");storage::atomic(&journal(state),&status)?;}
    Ok(())
}

pub fn apply(root:&Path,state:&Path)->Result<()> {
    let dir=updates::directory(state);let _lock=storage::Lock::acquire(&dir.join("manager.lock"))?;
    let update=updates::read_status(state)?;ensure!(matches!(update.phase.as_str(),"succeeded"|"current"),"Backend not verified");
    let (bin,installed)=launcher::resolve_release(root)?;ensure!(installed==state,"Installation changed");
    let policy=updates::trust(&bin,state)?;
    let bytes=std::fs::read(dir.join("offer.json"))?;
    let offer=updates::verify_offer(&policy,&bytes,"0.0.0",storage::now())?;
    ensure!(update.generation.as_deref()==Some(&offer.generation)&&crate::installation::generation(&bin)?==offer.generation,"Running update differs");
    let package=cached(state,&offer.generation);
    // Rechecks the complete signed package and inventory immediately before use.
    crate::package_update::stage(root,&offer,&package)?;
    let installation=manager_root()?;storage::unredirected(&installation)?;
    ensure!(!root.starts_with(&installation)&&!state.starts_with(&installation),"Runtime must remain outside manager installation");
    let config=velopack::locator::create_config_from_root_dir(&installation);
    let updater=velopack::UpdateManager::new(velopack::sources::FileSource::new(&dir),None,Some(config.clone()))?;
    ensure!(updater.get_app_id()=="CodexPocketManager","Manager identity differs");
    let version=semver::Version::parse(&offer.version)?;
    ensure!(updater.get_current_version()<version,"Manager is not older than candidate");
    storage::private_dir(&config.PackagesDir)?;
    let name=format!("CodexPocketManager-{}-full.nupkg",offer.version);
    let dest=config.PackagesDir.join(&name);storage::unredirected(&dest)?;
    std::fs::copy(&package,&dest)?;
    let identity=offer.package.context("Package missing")?;
    let asset=velopack::VelopackAsset{PackageId:"CodexPocketManager".into(),Version:offer.version,Type:"Full".into(),FileName:name,SHA256:identity.sha256,Size:identity.bytes,..Default::default()};
    updater.wait_exit_then_apply_updates(&asset,true,false,Vec::<String>::new())?;
    Ok(())
}

pub fn public_status(state:&Path)->Value {
    storage::read::<Value>(&journal(state)).map(|s|json!({"phase":s["phase"],"version":s["version"],"reason":s["reason"]})).unwrap_or_else(|_|json!({"phase":"legacy"}))
}
pub fn retry(state:&Path)->Result<()> {
    if !journal(state).exists(){return Ok(())}
    let mut s:Value=storage::read(&journal(state))?;
    if s["phase"]=="failed"||s["phase"]=="migration_required" {
        s["phase"]=json!("staged");s["reason"]=Value::Null;s["updatedAt"]=json!(storage::now());storage::atomic(&journal(state),&s)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_manager_journal_does_not_disable_backend_lifecycle() {
        let root=crate::test_support::tempdir();let state=root.path().join("state");
        storage::private_dir(&updates::directory(&state)).unwrap();
        for bytes in [b"broken".as_slice(),b"[]",b"null",b"17"] {
            std::fs::write(journal(&state),bytes).unwrap();
            reconcile(root.path(),&state).unwrap();
            assert_eq!(public_status(&state)["phase"],"failed");
            assert_eq!(public_status(&state)["reason"],"manager_state_unreadable");
        }
    }
}
