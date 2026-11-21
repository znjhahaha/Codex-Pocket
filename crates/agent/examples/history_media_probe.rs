//! Read-only acceptance of native viewed-image projection. Emits no text or bytes.
use anyhow::{Context, Result, ensure};
use pocket_agent::{history_catalog, media::{MediaOwner, MediaStore, NativeHistory}};
use serde_json::json;
use std::path::PathBuf;
fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    ensure!(args.len() == 4, "Pass CODEX_HOME THREAD IMAGE_FILENAME ISOLATED_STATE");
    let home=PathBuf::from(&args[0]);
    let page=history_catalog::read(&home,&args[1],None,20)?;
    let mut native=page["thread"].clone();native["turns"]=page["data"].clone();
    let mut roots=vec![std::env::temp_dir(),home.join("generated_images")];
    if let Some(cwd)=native["cwd"].as_str(){roots.push(PathBuf::from(cwd));}
    let proof=NativeHistory::from_native_thread(&native,"history-probe","persisted")?
        .with_image_roots(roots.into_iter().filter_map(|p|pocket_agent::workspace::native_image_root(&p).ok()).collect());
    let store=MediaStore::new(&args[3])?;
    let projected=store.project_with_history(&json!({"thread":native}),"history-probe","persisted",&args[1],&proof);
    let mut found=0;
    for turn in projected["thread"]["turns"].as_array().context("No turns")? {
        for item in turn["items"].as_array().context("No items")? {
            if item["type"]!="imageView" || !item["path"].as_str().is_some_and(|p|p.ends_with(&args[2])){continue}
            let block=&item["toolOutputBlocks"][0];ensure!(block["status"]=="ready","Viewed image is not readable: {}",block["status"]);
            let owner=MediaOwner{runtime_id:"history-probe".into(),connection_id:"persisted".into(),thread_id:args[1].clone(),turn_id:turn["id"].as_str().context("Turn missing")?.into(),item_id:item["id"].as_str().context("Item missing")?.into()};
            let mut bytes=Vec::new();
            loop {
                let chunk=store.read(&owner,block["mediaId"].as_str().context("No media")?,bytes.len() as u64,65536)?;
                ensure!(chunk["status"]=="ready","Image chunk unavailable");
                use base64::Engine;
                bytes.extend(base64::engine::general_purpose::STANDARD.decode(chunk["data"].as_str().context("No bytes")?)?);
                if chunk["eof"]==true {break}
            }
            ensure!(bytes==std::fs::read(item["path"].as_str().unwrap())?,"Image differs from native source");
            println!("{}",json!({"status":"ready","bytes":bytes.len(),"mime":block["mimeType"],"sameAsNative":true}));found+=1;
        }
    }
    ensure!(found>0,"Target viewed image absent from current page");
    Ok(())
}
