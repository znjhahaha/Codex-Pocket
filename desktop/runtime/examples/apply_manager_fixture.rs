//! Exercises the same Velopack Rust apply API on a private portable fixture.
use anyhow::{Result,ensure};
fn main()->Result<()> {
    let a:Vec<_>=std::env::args().skip(1).collect();ensure!(a.len()==2,"portable-root full-package required");
    let root=std::path::Path::new(&a[0]);let package=std::path::Path::new(&a[1]);
    ensure!(root.is_absolute()&&root.join(".portable").is_file()&&root.to_string_lossy().contains("implementation-20261117"),"Only isolated marked fixture allowed");
    let config=velopack::locator::create_config_from_root_dir(root);
    std::fs::create_dir_all(&config.PackagesDir)?;
    let name=package.file_name().unwrap().to_string_lossy().to_string();std::fs::copy(package,config.PackagesDir.join(&name))?;
    let mut bundle=velopack::bundle::load_bundle_from_file(package)?;let version=bundle.read_manifest()?.version.to_string();
    let manager=velopack::UpdateManager::new(velopack::sources::FileSource::new(&config.PackagesDir),None,Some(config))?;
    let asset=velopack::VelopackAsset{PackageId:"CodexPocketManager".into(),Version:version,FileName:name,Type:"Full".into(),..Default::default()};
    manager.wait_exit_then_apply_updates(&asset,true,false,Vec::<String>::new())?;
    println!("Fixture manager update dispatched through Rust API");Ok(())
}
