pub fn home() -> tempfile::TempDir {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../.local/cli-test-temp");
    std::fs::create_dir_all(&root).unwrap();
    tempfile::tempdir_in(dunce::canonicalize(root).unwrap()).unwrap()
}
