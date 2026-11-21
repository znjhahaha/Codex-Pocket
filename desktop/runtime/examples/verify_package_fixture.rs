//! Parse a generated package into a new, isolated directory; no process changes.
use anyhow::{Result,ensure};
use sha2::{Digest,Sha256};
use pocket_desktop_runtime::{updates::{VerifiedOffer,Download,PackageDownload},package_update};
fn main()->Result<()> {
    let a:Vec<_>=std::env::args().skip(1).collect();ensure!(a.len()==3,"manifest package new-root required");
    let manifest=std::fs::read(&a[0])?;let v:serde_json::Value=serde_json::from_slice(&manifest)?;let bytes=std::fs::read(&a[1])?;
    let hash=|b:&[u8]|format!("{:x}",Sha256::digest(b));
    let offer=VerifiedOffer{version:v["version"].as_str().unwrap().into(),generation:hash(&manifest),manifest:manifest.clone(),files:v["files"].as_array().unwrap().iter().map(|f|Download{path:f["path"].as_str().unwrap().into(),bytes:f["bytes"].as_u64().unwrap(),sha256:f["sha256"].as_str().unwrap().into(),url:"https://fixture.invalid/".into()}).collect(),package:Some(PackageDownload{url:"https://fixture.invalid/full.nupkg".into(),bytes:bytes.len()as u64,sha256:hash(&bytes)})};
    let root=std::path::Path::new(&a[2]);ensure!(!root.exists(),"Fresh fixture root required");
    println!("Verified generated package: {}",package_update::stage(root,&offer,std::path::Path::new(&a[1]))?.display());Ok(())
}
