//! Host-validated immutable file deliveries. Only completed assistant operations
//! can name artifacts, and reads accept retained artifact IDs, never phone paths.
use super::*;
use base64::{Engine, engine::general_purpose::STANDARD};
const MAX_FILE: u64 = 24 * 1024 * 1024;

pub(super) fn initialize(db:&Connection)->Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS product_artifacts(id TEXT PRIMARY KEY, data TEXT NOT NULL)")?;
    Ok(())
}

pub(super) fn source_path(uri:&str)->Option<PathBuf> {
    let raw=uri.strip_prefix("sandbox:").unwrap_or(uri);
    if raw.contains('\0') || raw.starts_with("\\\\") || raw.starts_with("//") {return None;}
    let raw=if cfg!(windows) && raw.starts_with('/') && raw.as_bytes().get(2)==Some(&b':') {&raw[1..]} else {raw};
    let path=PathBuf::from(raw);
    (path.is_absolute() && !path.components().any(|c|matches!(c,std::path::Component::ParentDir))).then_some(path)
}

fn mime(path:&Path)->&'static str {
    match path.extension().and_then(|x|x.to_str()).unwrap_or("").to_ascii_lowercase().as_str() {
        "png"=>"image/png", "jpg"|"jpeg"=>"image/jpeg", "webp"=>"image/webp",
        "pdf"=>"application/pdf", "txt"|"md"=>"text/plain", "csv"=>"text/csv",
        "docx"=>"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx"=>"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx"=>"application/vnd.openxmlformats-officedocument.presentationml.presentation",
        _=>"application/octet-stream",
    }
}

impl<R:RuntimeIo+Clone> Worker<R> {
    pub(super) fn capture_artifacts(&mut self)->Result<()> {
        let projections=self.service.artifacts();
        let operations=self.service.operations();
        let tasks=self.service.tasks()?;
        for mut artifact in projections.into_iter().filter(|a|a["kind"]=="file") {
            let id=artifact["id"].as_str().context("Missing artifact identity")?.to_owned();
            let exists:bool=self.messages.query_row("SELECT EXISTS(SELECT 1 FROM product_artifacts WHERE id=?1)",[&id],|r|r.get(0))?;
            if exists {continue;}
            let capture=(||->Result<()> {
                let op=operations.iter().find(|o|Some(o.id.as_str())==artifact["operationId"].as_str()).context("Unowned result")?;
                let path=source_path(artifact["uri"].as_str().context("Missing source")?).context("Unsupported artifact path")?;
                let actual=dunce::canonicalize(&path)?;
                let roots=if let Some(task)=tasks.iter().find(|t|Some(&t.id)==op.task_id.as_ref()) {vec![task.directory.clone()]} else {op.authorized_directories.clone()};
                ensure!(roots.iter().any(|root|dunce::canonicalize(root).is_ok_and(|r|within(&actual,&r))),"Artifact is outside task scope");
                // Reject reparse indirection instead of silently trusting a new
                // path target. Compare metadata around the bounded handle read.
                let before=std::fs::metadata(&path)?;
                ensure!(before.is_file() && before.len()<=MAX_FILE,"Artifact size/type unsupported");
                let bytes=crate::storage::read_physical(&path,MAX_FILE)?;
                let after=std::fs::metadata(&path)?;
                ensure!(before.len()==after.len() && before.modified()?==after.modified()? && bytes.len() as u64==after.len(),"Artifact changed during capture");
                let hash=format!("{:x}",Sha256::digest(&bytes));
                let folder=self.workspace.parent().context("Workspace parent missing")?.join("artifacts");
                crate::storage::private_directory(&folder)?;
                let target=folder.join(&hash);
                if !target.try_exists()? {crate::storage::atomic_write(&target,&bytes)?;}
                artifact["availability"]=json!("available");artifact["sha256"]=json!(hash);
                artifact["sizeBytes"]=json!(bytes.len());artifact["mimeType"]=json!(mime(&path));
                artifact["capturedAtMs"]=json!((self.clock)());
                Ok(())
            })();
            if capture.is_err() {
                artifact["availability"]=json!(if artifact["uri"].as_str().and_then(source_path).is_some_and(|p|!p.exists()) {"missing"} else {"unavailable"});
                artifact["unavailableReason"]=json!("原文件缺失、已变化或不在任务授权范围；未获取文件内容");
            }
            // Each operation captures once: later overwrites cannot replace an
            // earlier result, including artifacts first found missing.
            self.messages.execute("INSERT OR IGNORE INTO product_artifacts(id,data) VALUES(?1,?2)",params![id,serde_json::to_string(&artifact)?])?;
        }
        Ok(())
    }

    pub(super) fn artifact_context(&self,id:&str,version:&str,task:&str)->Result<String> {
        let artifact=self.service.artifacts().into_iter().find(|a|a["id"]==id).context("Artifact not found")?;
        ensure!(artifact["version"]==version && artifact["taskId"]==task,"Artifact version/task mismatch");
        if artifact["kind"]=="file" {
            let raw:String=self.messages.query_row("SELECT data FROM product_artifacts WHERE id=?1",[id],|r|r.get(0)).optional()?.context("Artifact has no retained snapshot")?;
            let saved:Value=serde_json::from_str(&raw)?;
            ensure!(saved["availability"]=="available","Artifact snapshot unavailable");
            let hash=text(&saved,"sha256")?;
            ensure!(hash.len()==64 && hash.bytes().all(|c|c.is_ascii_hexdigit()),"Invalid artifact hash");
            let path=self.workspace.parent().context("Workspace parent missing")?.join("artifacts").join(hash);
            let bytes=crate::storage::read_physical(&path,MAX_FILE)?;
            ensure!(format!("{:x}",Sha256::digest(&bytes))==hash,"Artifact content changed");
            Ok(format!("Selected immutable artifact {id}, version {version}, sha256 {hash}. Read-only source snapshot: {}. Use this snapshot, not a newer file at the original path. This reference grants no additional write permission.",path.display()))
        } else if artifact["kind"]=="text" {
            Ok(format!("Selected result {id}, version {version}:\n{}",artifact["content"].as_str().unwrap_or_default()))
        } else {
            Ok(format!("Selected link {id}, version {version}: {}",artifact["uri"].as_str().unwrap_or_default()))
        }
    }

    // Called after artifact_context has verified the selected task/version and
    // immutable snapshot. Native image input consumes that exact version.
    pub(super) fn artifact_image_input(&self,id:&str)->Result<Option<crate::assistant::AssistantInput>> {
        let raw:Option<String>=self.messages.query_row("SELECT data FROM product_artifacts WHERE id=?1",[id],|r|r.get(0)).optional()?;
        let Some(raw)=raw else {return Ok(None);};let saved:Value=serde_json::from_str(&raw)?;
        if !matches!(saved["mimeType"].as_str(),Some("image/png"|"image/jpeg"|"image/webp")) {return Ok(None);}
        let hash=text(&saved,"sha256")?;
        let id=uuid::Uuid::parse_str(&hash[..32])?.to_string();
        let root=self.workspace.parent().context("Workspace parent missing")?;
        let bytes=crate::storage::read_physical(&root.join("artifacts").join(hash),8*1024*1024)?;
        let extension=match saved["mimeType"].as_str() {Some("image/png")=>"png",Some("image/jpeg")=>"jpg",_=>"webp"};
        let folder=root.join("input-images");crate::storage::private_directory(&folder)?;
        let path=folder.join(format!("{id}.{extension}"));
        if path.try_exists()? {ensure!(crate::storage::read_physical(&path,8*1024*1024)?==bytes,"Retained artifact image changed");}
        else {crate::storage::atomic_write(&path,&bytes)?;}
        let input=crate::assistant::AssistantInput::Image{id,name:text(&saved,"name")?.into(),path};
        input.validate()?;Ok(Some(input))
    }

    pub(super) fn artifact_read(&self,p:&Value)->Result<Value> {
        let id=text(p,"artifactId")?;
        ensure!(id.len()<=512,"Artifact ID too long");
        let offset=p["offset"].as_u64().unwrap_or(0);
        let raw:String=self.messages.query_row("SELECT data FROM product_artifacts WHERE id=?1",[id],|r|r.get(0)).optional()?.context("Artifact not found")?;
        let a:Value=serde_json::from_str(&raw)?;
        ensure!(a["availability"]=="available","Artifact content unavailable");
        let hash=text(&a,"sha256")?;
        ensure!(hash.len()==64 && hash.bytes().all(|c|c.is_ascii_hexdigit()),"Invalid retained artifact hash");
        use std::io::{Read,Seek,SeekFrom};
        let path=self.workspace.parent().context("Workspace parent missing")?.join("artifacts").join(hash);
        let mut file=std::fs::File::open(&path)?;
        crate::storage::verify_physical_file(&path,&file)?;
        let size=file.metadata()?.len();
        ensure!(size<=MAX_FILE && a["sizeBytes"].as_u64()==Some(size),"Artifact size changed");
        ensure!(offset<=size,"Artifact offset out of range");
        file.seek(SeekFrom::Start(offset))?;
        let mut chunk=vec![0u8;(size-offset).min(192*1024) as usize];
        file.read_exact(&mut chunk)?;
        Ok(json!({"artifactId":id,"version":a["version"],"sha256":hash,"mimeType":a["mimeType"],"name":a["name"],"sizeBytes":size,"offset":offset,"data":STANDARD.encode(&chunk),"eof":offset+chunk.len() as u64==size}))
    }
}

pub(super) fn project(db:&Connection,values:&mut Value)->Result<()> {
    let rows=db.prepare("SELECT data FROM product_artifacts")?.query_map([],|r|r.get::<_,String>(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
    if let Some(artifacts)=values["artifacts"].as_array_mut() {
        for raw in rows {
            let saved:Value=serde_json::from_str(&raw)?;
            if let Some(slot)=artifacts.iter_mut().find(|a|a["id"]==saved["id"]) {*slot=saved;}
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn product_artifact_path_rejects_network_relative_and_traversal() {
        for path in ["https://example.test/a", "../secret", "\\\\server\\share\\x", "//server/share/x", "C:/report/../secret"] {
            assert!(source_path(path).is_none(),"{path}");
        }
    }
}
