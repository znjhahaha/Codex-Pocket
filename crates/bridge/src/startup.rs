//! Admission barrier inherited by a desktop launched during an update.
use anyhow::{Result, ensure};
use serde::Deserialize;
use std::{fs::File, io::Read, path::{Component, Path, PathBuf}};

pub(crate) struct StartupBarrier {
    pub operation: String,
    release_file: Option<PathBuf>,
}
impl StartupBarrier {
    pub fn new(operation: String, release_file: Option<PathBuf>) -> Result<Self> {
        ensure!(uuid::Uuid::parse_str(&operation).is_ok_and(|v|v.to_string()==operation), "Invalid startup update operation");
        if let Some(path) = &release_file { validate_name(path)?; }
        Ok(Self { operation, release_file })
    }
    pub fn released(&self) -> bool {
        self.release_file.as_ref().is_some_and(|path|read_release(path,&self.operation).unwrap_or(false))
    }
    pub fn requires_receipt(&self) -> bool {
        self.release_file.is_some()
    }
}

fn validate_name(path: &Path) -> Result<()> {
    ensure!(path.is_absolute() && !path.components().any(|c|matches!(c,Component::ParentDir|Component::CurDir)),"Startup release requires an absolute physical path");
    #[cfg(windows)] {
        use std::path::Prefix;
        ensure!(matches!(path.components().next(),Some(Component::Prefix(p)) if matches!(p.kind(),Prefix::Disk(_)|Prefix::VerbatimDisk(_))),"Startup release requires a local drive");
    }
    Ok(())
}
#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct Release { schema: u32, operation_id: String, released: bool }

fn read_release(path: &Path, operation: &str) -> Result<bool> {
    validate_name(path)?;
    for ancestor in path.ancestors() {
        let metadata = std::fs::symlink_metadata(ancestor)?;
        ensure!(!metadata.file_type().is_symlink(),"Startup release cannot use links");
        #[cfg(windows)] {
            use std::os::windows::fs::MetadataExt;
            ensure!(metadata.file_attributes() & 0x400 == 0,"Startup release cannot use reparse points");
        }
    }
    let file = File::open(path)?;
    ensure!(file.metadata()?.is_file(),"Startup release must be a file");
    verify_handle(path,&file)?;
    let mut bytes=Vec::new();file.take(4097).read_to_end(&mut bytes)?;
    ensure!(bytes.len()<=4096,"Startup release exceeds limit");
    let release:Release=serde_json::from_slice(&bytes)?;
    Ok(release.schema==1 && release.operation_id==operation && release.released)
}
fn verify_handle(path:&Path,file:&File)->Result<()> {
    #[cfg(not(windows))] let _=(path,file);
    #[cfg(windows)] unsafe {
        use std::os::windows::{ffi::OsStrExt,io::AsRawHandle};
        use windows_sys::Win32::Storage::FileSystem::{GetFinalPathNameByHandleW,GetLongPathNameW};
        let mut buffer=vec![0u16;32768];
        let count=GetFinalPathNameByHandleW(file.as_raw_handle(),buffer.as_mut_ptr(),buffer.len() as u32,0);
        ensure!(count>0 && (count as usize)<buffer.len(),"Startup release handle path unavailable");
        let actual=String::from_utf16(&buffer[..count as usize])?;
        let name:Vec<_>=path.as_os_str().encode_wide().chain(Some(0)).collect();
        let count=GetLongPathNameW(name.as_ptr(),buffer.as_mut_ptr(),buffer.len() as u32);
        ensure!(count>0 && (count as usize)<buffer.len(),"Startup release physical path unavailable");
        let expected=String::from_utf16(&buffer[..count as usize])?.replace('/',"\\");
        let normalize=|v:&str|v.strip_prefix(r"\\?\").unwrap_or(v).to_lowercase();
        let actual=normalize(&actual);
        ensure!(actual==normalize(&expected) && !(actual.contains("\\packages\\")&&actual.contains("\\localcache\\")),"Startup release path redirected");
    }
    Ok(())
}
