//! Bounded read-only access to roots proven by a selected native thread.
use anyhow::{Context, Result, bail};
use serde_json::{Value, json};
use std::{
    fs::{self, File, OpenOptions},
    io::Read,
    path::{Component, Path, PathBuf},
};
pub const MAX_READ: usize = 256 * 1024;
pub const MAX_ENTRIES: usize = 256;
pub const MAX_IMAGE: u64 = 24 * 1024 * 1024;
pub const IMAGE_CHUNK: usize = 192 * 1024;
pub struct Workspace {
    roots: Vec<PathBuf>,
}
impl Workspace {
    pub fn new(roots: Vec<PathBuf>) -> Result<Self> {
        if roots.is_empty() || roots.len() > 32 {
            bail!("Invalid workspace roots");
        }
        let mut canonical = Vec::new();
        for root in roots {
            lexical(&root.to_string_lossy())?;
            if !root.is_absolute() {
                bail!("Native root must be absolute");
            }
            let _held = guard(&root)?;
            if !root.is_dir() {
                bail!("Native root is not a directory");
            }
            let root = dunce::canonicalize(root)?;
            if !canonical.contains(&root) {
                canonical.push(root);
            }
        }
        Ok(Self { roots: canonical })
    }
    pub fn roots(&self) -> &[PathBuf] {
        &self.roots
    }
    fn resolve(&self, path: Option<&str>) -> Result<(PathBuf, Vec<File>)> {
        let path = path.unwrap_or("");
        if !path.is_empty() {
            lexical(path)?;
        }
        let raw = Path::new(path);
        let joined = if raw.is_absolute() {
            raw.to_path_buf()
        } else {
            self.roots[0].join(raw)
        };
        if !self.roots.iter().any(|r| joined.starts_with(r)) {
            bail!("Outside workspace");
        }
        let held = guard(&joined)?;
        let canonical = dunce::canonicalize(&joined)?;
        if !self.roots.iter().any(|r| canonical.starts_with(r)) {
            bail!("Outside workspace");
        }
        Ok((canonical, held))
    }
    pub fn list(&self, path: Option<&str>) -> Result<Value> {
        let (path, _held) = self.resolve(path)?;
        let mut entries = Vec::new();
        let mut truncated = false;
        for (scanned, entry) in fs::read_dir(&path)?.enumerate() {
            if scanned >= 4096 || entries.len() >= MAX_ENTRIES {
                truncated = true;
                break;
            }
            let Ok(entry) = entry else { continue };
            let name = entry.file_name().to_string_lossy().into_owned();
            if lexical(&name).is_err() {
                continue;
            }
            let Ok(m) = fs::symlink_metadata(entry.path()) else {
                continue;
            };
            if linked(&m) || !(m.is_dir() || m.is_file()) {
                continue;
            }
            entries.push(json!({"name":name,"path":entry.path(),"kind":if m.is_dir(){"directory"}else{"file"},"size":m.len()}));
        }
        entries.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        Ok(json!({"path":path,"roots":self.roots,"entries":entries,"truncated":truncated}))
    }
    /// Pasted desktop attachments and generated images live outside the project (temp,
    /// CODEX_HOME/generated_images). Only `image` may use these extra roots.
    pub fn with_image_roots(mut self, extra: Vec<PathBuf>) -> Self {
        for root in extra {
            if lexical(&root.to_string_lossy()).is_err()
                || !root.is_absolute()
                || guard(&root).is_err()
                || !root.is_dir()
            {
                continue;
            }
            if let Ok(root) = dunce::canonicalize(root)
                && !self.roots.contains(&root)
                && self.roots.len() < 32
            {
                self.roots.push(root);
            }
        }
        self
    }
    /// One bounded base64 chunk of a regular image file, verified by extension and magic bytes.
    pub fn image(&self, path: &str, offset: u64) -> Result<Value> {
        let ext = Path::new(path)
            .extension()
            .and_then(|e| e.to_str())
            .map(str::to_ascii_lowercase);
        if !matches!(
            ext.as_deref(),
            Some("png" | "jpg" | "jpeg" | "gif" | "webp" | "bmp")
        ) {
            bail!("Not an image file");
        }
        let (path, _held) = self.resolve(Some(path))?;
        let mut file = open_shared(&path)?;
        let metadata = file.metadata()?;
        if !metadata.is_file() || linked(&metadata) {
            bail!("Only regular files can be read");
        }
        let size = metadata.len();
        if size == 0 || size > MAX_IMAGE {
            bail!("Image size not supported");
        }
        if offset >= size {
            bail!("Offset past end of image");
        }
        let mut head = [0u8; 12];
        let read = file.read(&mut head)?;
        let mime = image_mime(&head[..read]).context("Not an image file")?;
        use std::io::{Seek, SeekFrom};
        file.seek(SeekFrom::Start(offset))?;
        let mut bytes = Vec::with_capacity(IMAGE_CHUNK);
        file.take(IMAGE_CHUNK as u64).read_to_end(&mut bytes)?;
        use base64::Engine;
        let end = offset + bytes.len() as u64;
        Ok(
            json!({"path":path,"mime":mime,"size":size,"offset":offset,"eof":end >= size,
            "data":base64::engine::general_purpose::STANDARD.encode(&bytes)}),
        )
    }
    pub fn read(&self, path: &str) -> Result<Value> {
        let (path, _held) = self.resolve(Some(path))?;
        let file = open_shared(&path)?;
        let metadata = file.metadata()?;
        if !metadata.is_file() || linked(&metadata) {
            bail!("Only regular files can be read");
        }
        let mut bytes = Vec::new();
        file.take((MAX_READ + 1) as u64).read_to_end(&mut bytes)?;
        let truncated = bytes.len() > MAX_READ;
        bytes.truncate(MAX_READ);
        if bytes.contains(&0) {
            bail!("Binary file preview unavailable");
        }
        let text = match std::str::from_utf8(&bytes) {
            Ok(s) => s.to_owned(),
            Err(e) if truncated && e.error_len().is_none() => {
                std::str::from_utf8(&bytes[..e.valid_up_to()])?.to_owned()
            }
            Err(_) => bail!("File is not UTF-8 text"),
        };
        Ok(json!({"path":path,"text":text,"truncated":truncated}))
    }
}
fn open_shared(path: &Path) -> Result<File> {
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        options.share_mode(1 | 2).custom_flags(0x00200000);
    }
    #[cfg(target_os = "linux")]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(0x20000 | 0x800);
    }
    Ok(options.open(path)?)
}
fn image_mime(head: &[u8]) -> Option<&'static str> {
    match head {
        [0x89, b'P', b'N', b'G', ..] => Some("image/png"),
        [0xFF, 0xD8, 0xFF, ..] => Some("image/jpeg"),
        [b'G', b'I', b'F', b'8', ..] => Some("image/gif"),
        [
            b'R',
            b'I',
            b'F',
            b'F',
            _,
            _,
            _,
            _,
            b'W',
            b'E',
            b'B',
            b'P',
            ..,
        ] => Some("image/webp"),
        [b'B', b'M', ..] => Some("image/bmp"),
        _ => None,
    }
}
fn lexical(path: &str) -> Result<()> {
    if path.len() > 4096
        || path.chars().any(char::is_control)
        || path.starts_with("\\\\")
        || path.starts_with("//")
    {
        bail!("Invalid workspace path");
    }
    for (i, part) in path.split(['/', '\\']).enumerate() {
        if i == 0
            && part.len() == 2
            && part.as_bytes()[0].is_ascii_alphabetic()
            && part.ends_with(':')
        {
            if path
                .as_bytes()
                .get(2)
                .is_none_or(|b| !matches!(b, b'/' | b'\\'))
            {
                bail!("Drive-relative path denied");
            }
            continue;
        }
        let base = part.split('.').next().unwrap_or("").to_ascii_uppercase();
        if part == ".."
            || part.contains(':')
            || part.ends_with(' ')
            || (part.ends_with('.') && part != ".")
            || matches!(
                base.as_str(),
                "CON" | "PRN" | "AUX" | "NUL" | "CONIN$" | "CONOUT$"
            )
            || ((base.starts_with("COM") || base.starts_with("LPT"))
                && base.len() == 4
                && base.as_bytes()[3].is_ascii_digit())
        {
            bail!("Unsafe workspace component");
        }
    }
    Ok(())
}
fn linked(m: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        if m.file_attributes() & 0x400 != 0 {
            return true;
        }
    }
    m.is_symlink()
}
/// On Windows hold every component without delete sharing: no checked directory
/// can be renamed/replaced with a junction while the final read is in progress.
fn guard(path: &Path) -> Result<Vec<File>> {
    let mut held = Vec::new();
    let mut current = PathBuf::new();
    for part in path.components() {
        if matches!(part, Component::ParentDir) {
            bail!("Parent traversal denied");
        }
        current.push(part);
        if matches!(part, Component::Prefix(_)) {
            continue;
        }
        let metadata = fs::symlink_metadata(&current)?;
        if linked(&metadata) {
            bail!("Workspace links are not allowed");
        }
        #[cfg(windows)]
        {
            use std::os::windows::fs::OpenOptionsExt;
            let file = OpenOptions::new()
                .read(true)
                .share_mode(1 | 2)
                .custom_flags(0x00200000 | 0x02000000)
                .open(&current)?;
            if linked(&file.metadata()?) {
                bail!("Workspace reparse point denied");
            }
            held.push(file);
        }
    }
    path.file_name()
        .or_else(|| path.components().next().map(|c| c.as_os_str()))
        .context("Empty workspace path")?;
    Ok(held)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn v02_workspace_bounds_text_and_listing() {
        let temp = tempfile::tempdir().unwrap();
        std::fs::write(temp.path().join("hello.txt"), "hello").unwrap();
        std::fs::write(temp.path().join("long.txt"), "a".repeat(MAX_READ + 1)).unwrap();
        std::fs::write(temp.path().join("binary"), [0u8, 255]).unwrap();
        let w = Workspace::new(vec![temp.path().into()]).unwrap();
        assert_eq!(w.read("hello.txt").unwrap()["text"], "hello");
        let big = w.read("long.txt").unwrap();
        assert_eq!(big["text"].as_str().unwrap().len(), MAX_READ);
        assert_eq!(big["truncated"], true);
        assert!(w.read("binary").is_err());
        assert_eq!(
            w.list(None).unwrap()["entries"].as_array().unwrap().len(),
            3
        );
    }
    #[test]
    fn v04_image_chunks_require_magic_and_allowed_roots() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("root");
        let images = temp.path().join("images");
        std::fs::create_dir(&root).unwrap();
        std::fs::create_dir(&images).unwrap();
        let mut png = vec![0x89, b'P', b'N', b'G', 13, 10, 26, 10];
        png.extend(std::iter::repeat_n(7u8, IMAGE_CHUNK + 10));
        std::fs::write(images.join("shot.png"), &png).unwrap();
        std::fs::write(images.join("fake.png"), "not an image").unwrap();
        std::fs::write(images.join("note.txt"), "text").unwrap();
        let plain = Workspace::new(vec![root.clone()]).unwrap();
        let shot = images.join("shot.png");
        assert!(plain.image(shot.to_str().unwrap(), 0).is_err());
        let w = Workspace::new(vec![root])
            .unwrap()
            .with_image_roots(vec![images.clone(), temp.path().join("missing")]);
        let first = w.image(shot.to_str().unwrap(), 0).unwrap();
        assert_eq!(first["mime"], "image/png");
        assert_eq!(first["eof"], false);
        let next = w.image(shot.to_str().unwrap(), IMAGE_CHUNK as u64).unwrap();
        assert_eq!(next["eof"], true);
        use base64::Engine;
        let tail = base64::engine::general_purpose::STANDARD
            .decode(next["data"].as_str().unwrap())
            .unwrap();
        assert_eq!(tail.len(), png.len() - IMAGE_CHUNK);
        assert!(
            w.image(images.join("fake.png").to_str().unwrap(), 0)
                .is_err()
        );
        assert!(
            w.image(images.join("note.txt").to_str().unwrap(), 0)
                .is_err()
        );
        assert!(w.image(shot.to_str().unwrap(), png.len() as u64).is_err());
    }
    #[test]
    fn v02_workspace_rejects_escape_device_ads_and_links() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("root");
        std::fs::create_dir(&root).unwrap();
        std::fs::write(temp.path().join("secret"), "private").unwrap();
        let w = Workspace::new(vec![root.clone()]).unwrap();
        for p in [
            "../secret",
            "..\\secret",
            "C:secret",
            "file:stream",
            "\\\\server\\share",
            "\\\\?\\C:\\private",
            "NUL",
            temp.path().join("secret").to_str().unwrap(),
        ] {
            assert!(w.read(p).is_err(), "{p}");
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            let status = std::process::Command::new("cmd.exe")
                .args(["/c", "mklink", "/J"])
                .arg(root.join("link"))
                .arg(temp.path())
                .creation_flags(0x08000000)
                .stdout(std::process::Stdio::null())
                .status()
                .unwrap();
            assert!(status.success());
        }
        #[cfg(unix)]
        std::os::unix::fs::symlink(temp.path(), root.join("link")).unwrap();
        assert!(w.read("link/secret").is_err());
        assert!(Workspace::new(vec![root.join("link")]).is_err());
        assert!(
            w.list(None).unwrap()["entries"]
                .as_array()
                .unwrap()
                .is_empty()
        );
    }
}
