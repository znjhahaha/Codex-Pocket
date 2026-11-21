//! Read-only projection check for a captured native page; caches only in the supplied isolated directory.
use anyhow::{Context, Result, ensure};
use pocket_agent::media::{MediaOwner, MediaStore, NativeHistory};
use serde_json::{Value, json};
use std::path::PathBuf;

fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    ensure!(args.len() == 2, "Pass native-page.json and isolated-state-directory");
    let native: Value = serde_json::from_slice(&std::fs::read(&args[0])?)?;
    let mut roots = vec![std::env::temp_dir()];
    if let Some(cwd) = native["cwd"].as_str() { roots.push(PathBuf::from(cwd)); }
    let proof = NativeHistory::from_native_thread(&native, "native-image-probe", "probe")?
        .with_image_roots(roots.into_iter().map(|p| pocket_agent::workspace::native_image_root(&p)).collect::<Result<_>>()?);
    let store = MediaStore::new(&args[1])?;
    let view = store.project_with_history(&json!({"thread":native}), "native-image-probe", "probe", "", &proof);
    let mut failed = 0;
    for turn in view["thread"]["turns"].as_array().context("Missing turns")? {
        for item in turn["items"].as_array().context("Missing items")? {
            if item["type"] != "userMessage" { continue; }
            for image in item["content"].as_array().context("Missing content")?.iter().filter(|p| p["type"] == "image") {
                let owner = MediaOwner { runtime_id:"native-image-probe".into(), connection_id:"probe".into(),
                    thread_id:view["thread"]["id"].as_str().context("Missing thread")?.into(),
                    turn_id:turn["id"].as_str().context("Missing turn")?.into(),
                    item_id:item["id"].as_str().context("Missing item")?.into() };
                let read = if let Some(id) = image["mediaId"].as_str().filter(|id| !id.is_empty()) {
                    store.read(&owner, id, 0, 65536)?
                } else { image.clone() };
                if read["status"] != "ready" { failed += 1; }
                println!("{}", json!({"item":item["id"],"part":image["contentPart"],"status":read["status"],
                    "size":read["size"],"message":read["message"]}));
            }
        }
    }
    ensure!(failed == 0, "{failed} images unavailable");
    Ok(())
}
