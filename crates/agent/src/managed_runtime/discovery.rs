//! Passive first-install discovery. Never runs a wrapper, CLI, login or model.
use super::{SelectedProfile, installation};
use anyhow::Result;
use std::{
    collections::BTreeSet,
    io::Read,
    path::{Path, PathBuf},
};

#[derive(Debug)]
pub enum Unavailable {
    MissingConfig,
    MissingCli,
}
impl std::fmt::Display for Unavailable {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::MissingConfig => "Native Codex configuration home unavailable",
            Self::MissingCli => "Native Codex executable unavailable",
        })
    }
}
impl std::error::Error for Unavailable {}

/// Saved authority failures are separate from first-install/PATH discovery.
#[derive(Debug,Clone,Copy,PartialEq,Eq)]
pub(crate) enum RestoreFailure {
    ProfileRead, ProfileInvalid, ConfigHome, NativeMissing, NativeInvalid,
    PackageQuery, PackageMissing, PackageAmbiguous, PackageIdentity,
    OwnershipUnconfirmed, ProfileSave, RuntimeStart, ProfileMigrationFenced,
}
impl std::fmt::Display for RestoreFailure {
    fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result {
        f.write_str(match self {
            Self::ProfileRead=>"Saved assistant profile cannot be read",
            Self::ProfileInvalid=>"Saved assistant profile is invalid",
            Self::ConfigHome=>"Saved Codex configuration home is unavailable or redirected",
            Self::NativeMissing=>"Saved native CLI is missing and is not an eligible official MSIX path",
            Self::NativeInvalid=>"Selected native CLI is invalid or redirected",
            Self::PackageQuery=>"Registered Codex MSIX package query failed",
            Self::PackageMissing=>"No registered replacement Codex MSIX package is available",
            Self::PackageAmbiguous=>"Multiple registered Codex MSIX packages match the saved identity",
            Self::PackageIdentity=>"Replacement MSIX package identity or installation root differs",
            Self::OwnershipUnconfirmed=>"Original Host/native exit cannot be proven; startup ownership is uncertain, inspect original identities before retrying",
            Self::ProfileSave=>"Migrated assistant profile could not be saved",
            Self::RuntimeStart=>"Assistant Runtime Host could not be restored",
            Self::ProfileMigrationFenced=>"Protected assistant profile cannot be migrated or replaced before resolving the predecessor checkpoint",
        })
    }
}
impl std::error::Error for RestoreFailure {}

#[derive(Debug,Clone)]
pub(crate) struct MsixPackage {
    pub(crate) full_name:String,
    pub(crate) location:PathBuf,
}
const CODEX_FAMILY:&str="OpenAI.Codex_2p2nqsd0c76g0";

pub(crate) fn restore_saved_profile(home:&Path,binary:&Path)->Result<SelectedProfile> {
    restore_saved_profile_with(home,binary,registered_codex_packages)
}
pub(crate) fn restore_saved_profile_with(
    home:&Path,binary:&Path,packages:impl FnOnce()->Result<Vec<MsixPackage>>,
)->Result<SelectedProfile> {
    use anyhow::Context;
    // A missing executable must never cause account/config-home discovery.
    let physical_home=installation::physical(home,true).context(RestoreFailure::ConfigHome)?;
    codex_cli_adapter::Discovery::for_home(&physical_home).context(RestoreFailure::ConfigHome)?;
    match std::fs::symlink_metadata(binary) {
        Ok(_)=>SelectedProfile::new(home,binary).context(RestoreFailure::NativeInvalid),
        Err(e) if e.kind()==std::io::ErrorKind::NotFound=>{
            let replacement=replacement_binary_with(binary,packages)?;
            SelectedProfile::new(home,replacement).context(RestoreFailure::NativeInvalid)
        },
        Err(e)=>Err(anyhow::Error::from(e).context(RestoreFailure::NativeInvalid)),
    }
}

pub(crate) fn replacement_binary(old:&Path)->Result<PathBuf> {
    replacement_binary_with(old,registered_codex_packages)
}

#[derive(Debug,PartialEq,Eq)]
struct PackageIdentity { version:[u16;4], architecture:String }
fn package_identity(full_name:&str)->Option<PackageIdentity> {
    let fields:Vec<_>=full_name.split('_').collect();
    if fields.len()!=5||fields[0]!="OpenAI.Codex"||fields[3]!=""||fields[4]!="2p2nqsd0c76g0"||
        !matches!(fields[2],"x64"|"arm64"|"x86") {return None}
    let version:Vec<_>=fields[1].split('.').map(str::parse::<u16>).collect::<std::result::Result<_,_>>().ok()?;
    Some(PackageIdentity{version:version.try_into().ok()?,architecture:fields[2].into()})
}
fn same_path(a:&Path,b:&Path)->bool {
    #[cfg(windows)] {a.to_string_lossy().replace('/',"\\").eq_ignore_ascii_case(&b.to_string_lossy().replace('/',"\\"))}
    #[cfg(not(windows))] {a==b}
}

pub(crate) fn replacement_binary_with(old:&Path,packages:impl FnOnce()->Result<Vec<MsixPackage>>)->Result<PathBuf> {
    use anyhow::{Context,ensure};
    ensure!(old.is_absolute()&&!old.components().any(|c|matches!(c,std::path::Component::ParentDir)),RestoreFailure::NativeMissing);
    #[cfg(windows)] {
        use std::path::{Component,Prefix};
        ensure!(matches!(old.components().next(),Some(Component::Prefix(p)) if matches!(p.kind(),Prefix::Disk(_))),RestoreFailure::NativeMissing);
    }
    // Only a definitively missing original executable is eligible. An access
    // error, dangling link, invalid existing custom binary or wrapper is not.
    match std::fs::symlink_metadata(old) {
        Err(e) if e.kind()==std::io::ErrorKind::NotFound=>{},
        Err(e)=>return Err(anyhow::Error::from(e).context(RestoreFailure::NativeInvalid)),
        Ok(_)=>return Err(RestoreFailure::NativeInvalid.into()),
    }
    let package_dir=old.parent().and_then(Path::parent).and_then(Path::parent).ok_or(RestoreFailure::NativeMissing)?;
    let windows_apps=package_dir.parent().ok_or(RestoreFailure::NativeMissing)?;
    let identity=package_dir.file_name().and_then(|s|s.to_str()).and_then(package_identity).ok_or(RestoreFailure::NativeMissing)?;
    ensure!(same_path(old,&package_dir.join("app/resources/codex.exe"))&&
        windows_apps.file_name().is_some_and(|s|s.to_string_lossy().eq_ignore_ascii_case("WindowsApps")),RestoreFailure::NativeMissing);
    // Validate every existing ancestor before consulting OS package authority.
    // canonicalize alone would resolve a reparse point and erase its evidence.
    for ancestor in old.ancestors() {
        match std::fs::symlink_metadata(ancestor) {
            Ok(m)=>{
                ensure!(!m.is_symlink(),RestoreFailure::NativeInvalid);
                #[cfg(windows)] {use std::os::windows::fs::MetadataExt;ensure!(m.file_attributes()&0x400==0,RestoreFailure::NativeInvalid);}
            },
            Err(e) if e.kind()==std::io::ErrorKind::NotFound=>{},
            Err(e)=>return Err(anyhow::Error::from(e).context(RestoreFailure::NativeInvalid)),
        }
    }
    let packages=packages().map_err(|e|if e.downcast_ref::<RestoreFailure>().is_some(){e}else{e.context(RestoreFailure::PackageQuery)})?;
    let mut matches=Vec::new();
    for package in packages {
        let current=package_identity(&package.full_name).ok_or(RestoreFailure::PackageIdentity)?;
        ensure!(package.location.file_name().and_then(|s|s.to_str())==Some(package.full_name.as_str())&&
            current.architecture==identity.architecture&&current.version>identity.version&&
            package.location.parent().is_some_and(|p|same_path(p,windows_apps)),RestoreFailure::PackageIdentity);
        matches.push(package);
    }
    ensure!(!matches.is_empty(),RestoreFailure::PackageMissing);
    ensure!(matches.len()==1,RestoreFailure::PackageAmbiguous);
    let binary=matches.pop().unwrap().location.join("app/resources/codex.exe");
    let binary=installation::physical(&binary,false).context(RestoreFailure::NativeInvalid)?;
    ensure!(is_native(&binary),RestoreFailure::NativeInvalid);
    Ok(binary)
}

/// Same authority as Get-AppxPackage -Name OpenAI.Codex, queried directly for
/// this user's registered package family. No shell, wrapper or CLI execution.
fn registered_codex_packages()->Result<Vec<MsixPackage>> {
    #[cfg(windows)] {
        use std::{os::windows::ffi::{OsStrExt,OsStringExt},ptr};
        #[link(name="kernel32")]
        unsafe extern "system" {
            fn GetPackagesByPackageFamily(family:*const u16,count:*mut u32,names:*mut *mut u16,length:*mut u32,buffer:*mut u16)->i32;
            fn GetPackagePathByFullName(name:*const u16,length:*mut u32,path:*mut u16)->i32;
        }
        let family:Vec<u16>=std::ffi::OsStr::new(CODEX_FAMILY).encode_wide().chain(Some(0)).collect();
        let(mut count,mut length)=(0,0);
        let code=unsafe{GetPackagesByPackageFamily(family.as_ptr(),&mut count,ptr::null_mut(),&mut length,ptr::null_mut())};
        if code==0&&count==0{return Ok(Vec::new())}
        if code!=122{return Err(anyhow::Error::from(std::io::Error::from_raw_os_error(code)).context(RestoreFailure::PackageQuery))}
        anyhow::ensure!(count>0&&count<=64&&length>0&&length<=32768,RestoreFailure::PackageQuery);
        let mut names=vec![ptr::null_mut();count as usize];let mut buffer=vec![0u16;length as usize];
        let code=unsafe{GetPackagesByPackageFamily(family.as_ptr(),&mut count,names.as_mut_ptr(),&mut length,buffer.as_mut_ptr())};
        if code!=0{return Err(anyhow::Error::from(std::io::Error::from_raw_os_error(code)).context(RestoreFailure::PackageQuery))}
        anyhow::ensure!(count as usize<=names.len(),RestoreFailure::PackageQuery);
        let mut result=Vec::new();
        for name in names.into_iter().take(count as usize) {
            let offset=(name as usize).checked_sub(buffer.as_ptr() as usize).ok_or(RestoreFailure::PackageQuery)?/2;
            let tail=buffer.get(offset..).ok_or(RestoreFailure::PackageQuery)?;
            let n=tail.iter().position(|c|*c==0).ok_or(RestoreFailure::PackageQuery)?;
            let full_name=String::from_utf16(&tail[..n]).map_err(|_|RestoreFailure::PackageQuery)?;
            // Resource packages cannot be the native desktop app-server.
            if package_identity(&full_name).is_none(){continue}
            let mut path=vec![0u16;32768];let mut size=path.len() as u32;
            let code=unsafe{GetPackagePathByFullName(name,&mut size,path.as_mut_ptr())};
            if code!=0{return Err(anyhow::Error::from(std::io::Error::from_raw_os_error(code)).context(RestoreFailure::PackageQuery))}
            anyhow::ensure!(size>0&&size as usize<=path.len()&&path[size as usize-1]==0,RestoreFailure::PackageQuery);
            result.push(MsixPackage{full_name,location:PathBuf::from(std::ffi::OsString::from_wide(&path[..size as usize-1]))});
        }
        Ok(result)
    }
    #[cfg(not(windows))] {Ok(Vec::new())}
}

/// Use the adapter's native CODEX_HOME -> OS home/.codex precedence verbatim.
/// In particular, an invalid explicit home never falls back to another account.
pub fn from_environment() -> Result<SelectedProfile> {
    let home = codex_cli_adapter::Discovery::from_environment()
        .map_err(|_| Unavailable::MissingConfig)?
        .ok_or(Unavailable::MissingConfig)?;
    let mut explicit = Vec::new();
    if let Some(path) = std::env::var_os("CODEX_CLI_PATH").filter(|p| !p.is_empty()) {
        explicit.push(PathBuf::from(path));
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(bin) = exe.parent() {
            explicit.push(bin.join("pocket-original.json"));
        }
    }
    explicit.push(home.codex_home().join("pocket-original.json"));
    let mut roots = Vec::new();
    if let Some(path) = std::env::var_os("PATH") {
        roots.extend(std::env::split_paths(&path));
    }
    if let Some(appdata) = std::env::var_os("APPDATA") {
        roots.push(PathBuf::from(appdata).join("npm"));
    }
    if let Some(local) = std::env::var_os("LOCALAPPDATA") {
        let local = PathBuf::from(local);
        explicit.push(local.join("CodexPocket/versions/current/pocket-original.json"));
        roots.push(local.join("Programs/Codex"));
        roots.push(local.join("Volta/bin"));
        volta_roots(&local.join("Volta"), &mut roots);
    }
    if let Some(volta) = std::env::var_os("VOLTA_HOME") {
        let volta = PathBuf::from(volta);
        roots.push(volta.join("bin"));
        volta_roots(&volta, &mut roots);
    }
    // Derive install directories from the same OS home as the native adapter.
    // CODEX_HOME can belong to a separate profile; it is not the OS home.
    if let Ok(Some(default)) = default_home() {
        roots.push(default.join(".local/bin"));
        roots.push(default.join(".npm-global/bin"));
        volta_roots(&default.join(".volta"), &mut roots);
    }
    select(home.codex_home(), &explicit, &roots)
}

fn default_home() -> Result<Option<PathBuf>> {
    // No environment mutation or process probes; these are install locations only.
    #[cfg(windows)]
    let home = std::env::var_os("USERPROFILE");
    #[cfg(not(windows))]
    let home = std::env::var_os("HOME");
    Ok(home.filter(|p| !p.is_empty()).map(PathBuf::from))
}

fn volta_roots(volta: &Path, roots: &mut Vec<PathBuf>) {
    let packages = volta.join("tools/image/packages/@openai/codex");
    // Bounded filesystem enumeration, never execute Volta/npm to resolve a shim.
    if let Ok(entries) = std::fs::read_dir(packages) {
        let mut versions = entries
            .flatten()
            .take(128)
            .map(|e| e.path())
            .collect::<Vec<_>>();
        versions.sort();
        for version in versions.into_iter().rev() {
            roots.push(version.join("bin"));
            roots.push(version);
        }
    }
}

fn select(home: &Path, explicit: &[PathBuf], roots: &[PathBuf]) -> Result<SelectedProfile> {
    let mut seen = BTreeSet::new();
    for path in explicit {
        if let Some(binary) = resolve(path, &mut seen) {
            return SelectedProfile::new(home, binary);
        }
    }
    for root in roots.iter().filter(|p| p.is_absolute()) {
        for name in [
            "codex.exe",
            "codex",
            "codex.cmd",
            "codex.ps1",
            "app/resources/codex.exe",
            "resources/codex.exe",
        ] {
            if let Some(binary) = resolve(&root.join(name), &mut seen) {
                return SelectedProfile::new(home, binary);
            }
        }
        for package in [
            root.join("node_modules/@openai/codex"),
            root.join("lib/node_modules/@openai/codex"),
            root.join("../lib/node_modules/@openai/codex"),
        ] {
            if let Some(binary) = npm_native(&package, &mut seen) {
                return SelectedProfile::new(home, binary);
            }
        }
    }
    Err(Unavailable::MissingCli.into())
}

fn resolve(path: &Path, seen: &mut BTreeSet<PathBuf>) -> Option<PathBuf> {
    resolve_inner(path, seen, 0)
}

fn resolve_inner(path: &Path, seen: &mut BTreeSet<PathBuf>, depth: usize) -> Option<PathBuf> {
    if !path.is_absolute() || seen.len() >= 4096 || depth > 8 {
        return None;
    }
    let path = dunce::canonicalize(path).ok()?;
    if !seen.insert(path.clone()) || !path.is_file() {
        return None;
    }
    let name = path.file_name()?.to_str()?.to_ascii_lowercase();
    let parent = path.parent()?;
    if name.ends_with(".json") {
        let bytes = crate::storage::read_physical(&path, 16384).ok()?;
        let value: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
        let original = value["binary"]
            .as_str()
            .or_else(|| value["originalModule"].as_str())?;
        return resolve_inner(Path::new(original), seen, depth + 1);
    }
    // Pocket's desktop bridge sidecar is the authority behind the wrapper.
    // A missing/recursive sidecar cannot make a Pocket executable native.
    let sidecar = parent.join("pocket-original.json");
    if name.contains("pocket") {
        return resolve_inner(&sidecar, seen, depth + 1);
    }
    if sidecar.is_file() {
        let bytes = crate::storage::read_physical(&sidecar, 16384).ok()?;
        let value: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
        let original = Path::new(value["binary"].as_str()?);
        if !original.is_absolute() {
            return None;
        }
        // The bridge and native CLI may be siblings. Its sidecar can identify
        // this actual native image rather than redirecting it back to itself.
        if dunce::canonicalize(original).ok()?.as_path() != path {
            return resolve_inner(&sidecar, seen, depth + 1);
        }
    }
    if name == "codex.js" {
        return npm_native(parent.parent()?, seen);
    }
    if matches!(name.as_str(), "codex" | "codex.cmd" | "codex.ps1") && !is_native(&path) {
        // Known npm/Pocket wrappers resolve via package layout or the saved module.
        // Script contents are data only: no arbitrary shell parsing/execution.
        if let Some(binary) = resolve(&parent.join(".codex2-cli/runtime.json"), seen) {
            return Some(binary);
        }
        return npm_native(&parent.join("node_modules/@openai/codex"), seen);
    }
    if name == "codex.exe" && parent.file_name().is_some_and(|v| v == "bin") {
        // Volta's PE shim is native code, but is not the Codex app-server.
        if let Some(volta) = parent
            .parent()
            .filter(|p| p.join("tools/image/packages").is_dir())
        {
            let mut roots = Vec::new();
            volta_roots(volta, &mut roots);
            for root in roots {
                if let Some(binary) = npm_native(&root.join("node_modules/@openai/codex"), seen) {
                    return Some(binary);
                }
            }
            return None;
        }
    }
    if name.starts_with("pocket-")
        || std::env::current_exe()
            .ok()
            .and_then(|p| dunce::canonicalize(p).ok())
            .as_ref()
            == Some(&path)
    {
        return None;
    }
    is_native(&path)
        .then(|| installation::physical(&path, false).ok())
        .flatten()
}

fn npm_native(package: &Path, seen: &mut BTreeSet<PathBuf>) -> Option<PathBuf> {
    #[cfg(all(windows, target_arch = "x86_64"))]
    let (target, optional) = ("x86_64-pc-windows-msvc", "codex-win32-x64");
    #[cfg(all(windows, target_arch = "aarch64"))]
    let (target, optional) = ("aarch64-pc-windows-msvc", "codex-win32-arm64");
    #[cfg(all(target_os = "linux", target_arch = "x86_64"))]
    let (target, optional) = ("x86_64-unknown-linux-musl", "codex-linux-x64");
    #[cfg(all(target_os = "linux", target_arch = "aarch64"))]
    let (target, optional) = ("aarch64-unknown-linux-musl", "codex-linux-arm64");
    #[cfg(all(target_os = "macos", target_arch = "x86_64"))]
    let (target, optional) = ("x86_64-apple-darwin", "codex-darwin-x64");
    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    let (target, optional) = ("aarch64-apple-darwin", "codex-darwin-arm64");
    let file = if cfg!(windows) { "codex.exe" } else { "codex" };
    for root in [
        package.to_path_buf(),
        package.join("node_modules/@openai").join(optional),
        package.parent()?.join(optional),
    ] {
        if let Some(binary) = resolve(
            &root.join("vendor").join(target).join("codex").join(file),
            seen,
        ) {
            return Some(binary);
        }
    }
    None
}

fn is_native(path: &Path) -> bool {
    let mut magic = [0; 4];
    let Ok(mut file) = std::fs::File::open(path) else {
        return false;
    };
    if file.read_exact(&mut magic).is_err() {
        return false;
    }
    #[cfg(windows)]
    {
        use std::io::{Seek, SeekFrom};
        if !magic.starts_with(b"MZ") {
            return false;
        }
        let mut offset = [0; 4];
        if file.seek(SeekFrom::Start(60)).is_err() || file.read_exact(&mut offset).is_err() {
            return false;
        }
        let offset = u32::from_le_bytes(offset) as u64;
        if !(64..=1024 * 1024).contains(&offset) || file.seek(SeekFrom::Start(offset)).is_err() {
            return false;
        }
        file.read_exact(&mut magic).is_ok() && magic == *b"PE\0\0"
    }
    #[cfg(not(windows))]
    {
        magic == *b"\x7fELF"
            || matches!(
                magic,
                [0xcf, 0xfa, 0xed, 0xfe] | [0xce, 0xfa, 0xed, 0xfe] | [0xca, 0xfe, 0xba, 0xbe]
            )
    }
}

#[cfg(test)]
#[path = "tests/discovery.rs"]
mod tests;
