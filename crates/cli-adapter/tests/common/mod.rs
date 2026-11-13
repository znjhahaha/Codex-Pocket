pub fn home() -> tempfile::TempDir {
    // AF_UNIX addresses are limited to 108 bytes on Windows. A checkout under
    // the desktop app's managed worktree directory can already exceed that
    // limit before the socket suffix is appended.
    tempfile::Builder::new()
        .prefix("pcli-")
        .tempdir()
        .unwrap()
}
