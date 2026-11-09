// Disposable state follows the same physical-path contract as an installation.
// Windows runners can expose TEMP through an 8.3 alias; do not relax the
// production MSIX redirection guard just to accommodate that test spelling.
pub fn tempdir() -> tempfile::TempDir {
    let canonical = std::fs::canonicalize(std::env::temp_dir()).unwrap();
    #[cfg(windows)]
    let canonical = {
        let text = canonical.to_str().unwrap();
        std::path::PathBuf::from(text.strip_prefix(r"\\?\").unwrap_or(text))
    };
    tempfile::tempdir_in(canonical).unwrap()
}
