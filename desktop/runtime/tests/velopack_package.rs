use pocket_desktop_runtime::{package_update,updates::{VerifiedOffer,Download,PackageDownload}};
use serde_json::json;
use sha2::{Digest,Sha256};
use std::{io::Write,path::Path};
#[path="../test_support/mod.rs"] mod test_support;
fn hash(b:&[u8])->String{format!("{:x}",Sha256::digest(b))}
fn package(path:&Path,extra:Option<&str>)->VerifiedOffer{
    let names=["pocket-agent.exe","pocket-supervisor.exe","pocket-bridge.exe","pocket-desktop.exe","pocket-upgrade.exe"];
    let files:Vec<_>=names.iter().map(|n|Download{path:n.to_string(),url:format!("https://updates.test/{n}"),bytes:4,sha256:hash(b"test")}).collect();
    let manifest=serde_json::to_vec(&json!({"version":"1.2.3","files":files.iter().map(|f|json!({"path":f.path,"bytes":f.bytes,"sha256":f.sha256})).collect::<Vec<_>>()})).unwrap();
    let mut zip=zip::ZipWriter::new(std::fs::File::create(path).unwrap());let o=zip::write::SimpleFileOptions::default();
    zip.start_file("CodexPocketManager.nuspec",o).unwrap();zip.write_all(br#"<?xml version="1.0"?><package><metadata><id>CodexPocketManager</id><version>1.2.3</version><title>Pocket</title><authors>Pocket</authors><description>Pocket</description><mainExe>pocket-launcher.exe</mainExe></metadata></package>"#).unwrap();
    for n in names{zip.start_file(format!("lib/app/{n}"),o).unwrap();zip.write_all(b"test").unwrap();}
    zip.start_file("lib/app/windows-manifest.json",o).unwrap();zip.write_all(&manifest).unwrap();
    if let Some(n)=extra{zip.start_file(format!("lib/app/{n}"),o).unwrap();zip.write_all(b"test").unwrap();}
    zip.finish().unwrap();let bytes=std::fs::read(path).unwrap();
    VerifiedOffer{version:"1.2.3".into(),generation:hash(&manifest),manifest,files,package:Some(PackageDownload{url:"https://updates.test/full.nupkg".into(),bytes:bytes.len()as u64,sha256:hash(&bytes)})}
}
#[test] fn verified_package_stages_without_changing_running_installation(){
    let root=test_support::tempdir();let path=root.path().join("full.nupkg");let offer=package(&path,None);
    let record=root.path().join("desktop-install.json");std::fs::write(&record,b"previous").unwrap();
    let staged=package_update::stage(root.path(),&offer,&path).unwrap();
    assert_eq!(std::fs::read(staged.join("pocket-agent.exe")).unwrap(),b"test");
    assert_eq!(std::fs::read(record).unwrap(),b"previous");
    std::fs::write(&path,b"tampered").unwrap();assert!(package_update::stage(root.path(),&offer,&path).is_err());
}
#[test] fn package_rejects_extra_and_escaping_payload_before_promotion(){
    for name in ["unlisted.dll","../escape.dll","link.__symlink","../Codex Pocket_ExecutionStub.exe","payload/Codex Pocket_ExecutionStub.exe"]{
        let root=test_support::tempdir();let path=root.path().join("full.nupkg");let offer=package(&path,Some(name));
        assert!(package_update::stage(root.path(),&offer,&path).is_err());assert!(!root.path().join("bin").join(offer.generation).exists());
    }
}
#[test] fn package_accepts_velopack_product_title_stub_without_promoting_it(){
    let root=test_support::tempdir();let path=root.path().join("full.nupkg");
    let offer=package(&path,Some("Codex Pocket_ExecutionStub.exe"));
    let staged=package_update::stage(root.path(),&offer,&path).unwrap();
    assert_eq!(std::fs::read(staged.join("pocket-agent.exe")).unwrap(),b"test");
    assert!(!staged.join("Codex Pocket_ExecutionStub.exe").exists());
}
