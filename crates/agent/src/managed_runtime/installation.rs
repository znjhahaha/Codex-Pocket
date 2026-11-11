use anyhow::{Context, Result, ensure};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeSet,
    fs::File,
    io::Read,
    path::{Path, PathBuf},
};

pub const HOST_FILENAME: &str = "pocket-runtime-host.exe";
/// Expected generation comes from independently verified local installed metadata.
/// The launcher verifies the manifest bytes and the executable on every ensure.
#[derive(Clone, Debug)]
pub struct Installation {
    bin: PathBuf,
    generation: String,
}
#[derive(Deserialize)]
struct Manifest {
    schema: u32,
    product: String,
    version: String,
    files: Vec<Entry>,
}
#[derive(Deserialize)]
struct Entry {
    path: String,
    bytes: u64,
    sha256: String,
}
impl Installation {
    pub fn new(bin: impl AsRef<Path>, verified_generation: impl Into<String>) -> Result<Self> {
        let bin = physical(bin.as_ref(), true)?;
        let generation = verified_generation.into();
        ensure!(
            valid_digest(&generation),
            "Verified installation generation must be a SHA256"
        );
        let result = Self { bin, generation };
        result.verify()?;
        Ok(result)
    }
    pub fn generation(&self) -> &str {
        &self.generation
    }
    pub fn verify(&self) -> Result<PathBuf> {
        physical(&self.bin, true)?;
        let manifest = self.bin.join("windows-manifest.json");
        physical(&manifest, false)?;
        let bytes = crate::storage::read_physical(&manifest, 1024 * 1024)?;
        ensure!(
            format!("{:x}", Sha256::digest(&bytes)) == self.generation,
            "Installed manifest generation changed; verify or repair the local installation"
        );
        let manifest: Manifest = serde_json::from_slice(&bytes)
            .map_err(|_| anyhow::anyhow!("Invalid installation manifest"))?;
        ensure!(
            manifest.schema == 1
                && manifest.product == "Codex Pocket"
                && !manifest.version.is_empty(),
            "Invalid installation manifest identity"
        );
        let mut names = BTreeSet::new();
        for file in &manifest.files {
            ensure!(
                names.insert(&file.path),
                "Duplicate installation manifest entry"
            );
            ensure!(
                !file.path.contains(['/', '\\', ':'])
                    && file.path != "."
                    && file.path != ".."
                    && valid_digest(&file.sha256),
                "Invalid installation manifest file entry"
            );
        }
        let host = manifest
            .files
            .iter()
            .find(|f| f.path == HOST_FILENAME)
            .context("Installation lacks pocket-runtime-host.exe; repair the installed package")?;
        let path = self.bin.join(HOST_FILENAME);
        physical(&path, false)?;
        let mut file = File::open(&path)?;
        crate::storage::verify_physical_file(&path, &file)?;
        ensure!(
            file.metadata()?.is_file() && host.bytes > 0 && file.metadata()?.len() == host.bytes,
            "Runtime Host size mismatch; repair installation"
        );
        let mut hash = Sha256::new();
        let mut buffer = [0u8; 65536];
        loop {
            let count = file.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            hash.update(&buffer[..count]);
        }
        ensure!(
            format!("{:x}", hash.finalize()) == host.sha256.to_lowercase(),
            "Runtime Host SHA256 mismatch; repair installation"
        );
        Ok(path)
    }
}
fn valid_digest(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|b| b.is_ascii_hexdigit())
}

pub(super) fn physical(path: &Path, directory: bool) -> Result<PathBuf> {
    ensure!(
        path.is_absolute(),
        "An absolute local physical path is required"
    );
    for ancestor in path.ancestors() {
        let metadata =
            std::fs::symlink_metadata(ancestor).context("Physical path must already exist")?;
        ensure!(
            !metadata.is_symlink(),
            "Selected path uses a symbolic link; select its physical directory"
        );
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            ensure!(
                metadata.file_attributes() & 0x400 == 0,
                "Selected path uses a reparse point; select its physical directory"
            );
        }
    }
    let result = dunce::canonicalize(path)?;
    ensure!(
        if directory {
            result.is_dir()
        } else {
            result.is_file()
        },
        "Selected path has the wrong file type"
    );
    let name = result.to_string_lossy().replace('/', "\\").to_lowercase();
    ensure!(
        !(name.contains("\\packages\\") && name.contains("\\localcache\\")),
        "Selected path is redirected package storage"
    );
    if !directory {
        let file = File::open(&result)?;
        crate::storage::verify_physical_file(&result, &file)?;
    }
    Ok(result)
}

pub(super) fn binary_for_process(
    expected: &pocket_core::native_process::NativeProcess,
) -> Result<PathBuf> {
    ensure!(
        expected.pid != 0
            && expected.created_at != 0
            && pocket_core::native_process::NativeProcess::capture(expected.pid)? == *expected
            && !expected.terminated(),
        "Selected native process identity changed"
    );
    #[cfg(windows)]
    {
        use std::{ffi::c_void, os::windows::ffi::OsStringExt};
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn OpenProcess(access: u32, inherit: i32, pid: u32) -> *mut c_void;
            fn QueryFullProcessImageNameW(
                handle: *mut c_void,
                flags: u32,
                buffer: *mut u16,
                size: *mut u32,
            ) -> i32;
            fn CloseHandle(handle: *mut c_void) -> i32;
        }
        let handle = unsafe { OpenProcess(0x1000, 0, expected.pid) };
        ensure!(
            !handle.is_null(),
            "Selected native binary identity unavailable"
        );
        let mut buffer = vec![0u16; 32768];
        let mut size = buffer.len() as u32;
        let ok = unsafe { QueryFullProcessImageNameW(handle, 0, buffer.as_mut_ptr(), &mut size) };
        unsafe { CloseHandle(handle) };
        ensure!(
            ok != 0 && size > 0 && (size as usize) < buffer.len(),
            "Selected native binary path unavailable"
        );
        ensure!(
            pocket_core::native_process::NativeProcess::capture(expected.pid)? == *expected
                && !expected.terminated(),
            "Selected native process replaced during discovery"
        );
        return physical(
            &PathBuf::from(std::ffi::OsString::from_wide(&buffer[..size as usize])),
            false,
        );
    }
    #[cfg(not(windows))]
    anyhow::bail!("Selected native process image discovery unsupported on this platform")
}
