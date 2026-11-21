//! Velopack owns package parsing. Execution generations stay outside its
//! installation directory so updating the manager cannot terminate native work.
use anyhow::{Context,Result,ensure};
use std::{path::{Path,PathBuf},io::Read,collections::BTreeSet};
use sha2::{Digest,Sha256};
use crate::{storage,updates::VerifiedOffer,supervisor::upgrade};

pub fn stage(root:&Path,offer:&VerifiedOffer,package:&Path)->Result<PathBuf>{
    let expected=offer.package.as_ref().context("Signed package identity missing")?;
    storage::unredirected(package)?;
    let mut file=std::fs::File::open(package)?;ensure!(file.metadata()?.len()==expected.bytes,"Package length differs");
    let mut digest=Sha256::new();let mut buffer=[0u8;65536];loop{let n=file.read(&mut buffer)?;if n==0{break}digest.update(&buffer[..n]);}
    ensure!(format!("{:x}",digest.finalize())==expected.sha256,"Package signature digest differs");
    let parent=root.join("updates");storage::private_dir(&parent)?;
    let temp=tempfile::Builder::new().prefix("package-").tempdir_in(&parent)?;
    let mut bundle=velopack::bundle::load_bundle_from_file(package)?;
    let manifest=bundle.read_manifest()?;
    ensure!(manifest.id=="CodexPocketManager"&&manifest.version.to_string()==offer.version,"Package product/version mismatch");
    let mut found=BTreeSet::new();
    for name in bundle.get_file_names()? {
        let normalized=name.replace('\\',"/");
        if let Some(app_relative)=normalized.strip_prefix("lib/app/") {
            // The pinned pack title creates this exact manager-only filename.
            // It is covered by the package signature and is never extracted.
            if app_relative=="Codex Pocket_ExecutionStub.exe" {continue}
            let path=app_relative.trim_end_matches('/');
            if path.is_empty(){continue}
            ensure!(super::updates::safe_path(path)&&!path.ends_with(".__symlink"),"Invalid package payload path");
            if app_relative.ends_with('/') {continue}
            // Velopack adds these manager bootstrap files during pack. They
            // remain covered by the outer package signature, outside runtime.
            if matches!(app_relative,"CodexPocketManager_ExecutionStub.exe"|"Squirrel.exe"|"pocket-setup.exe"|"pocket-install-offer.json"|"sq.version"){continue}
            let relative=app_relative.strip_prefix("payload/").unwrap_or(app_relative);
            ensure!(found.insert(relative.to_ascii_lowercase()),"Duplicate package file");
            ensure!(relative=="windows-manifest.json"||offer.files.iter().any(|f|f.path==relative),"Unlisted package file");
            if let Some(parent)=temp.path().join(relative).parent(){std::fs::create_dir_all(parent)?;}
            bundle.extract_zip_predicate_to_path(|n|n==name,temp.path().join(relative))?;
        }
    }
    ensure!(found.len()==offer.files.len()+1,"Incomplete package payload");
    ensure!(std::fs::read(temp.path().join("windows-manifest.json"))?==offer.manifest,"Package inventory differs");
    upgrade::local::verify_generation_inventory(temp.path())?;
    let bin=root.join("bin");storage::private_dir(&bin)?;let destination=bin.join(&offer.generation);
    storage::unredirected(&destination)?;
    if destination.exists(){upgrade::local::verify_generation_inventory(&destination)?;ensure!(crate::installation::generation(&destination)?==offer.generation,"Existing generation differs");}
    else{std::fs::rename(temp.path(),&destination)?;}
    Ok(destination)
}
