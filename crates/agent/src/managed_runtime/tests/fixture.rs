use crate::managed_runtime::{Controller, Installation, Registry, SelectedProfile};
use pocket_runtime_ipc::Descriptor;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    path::{Path, PathBuf},
    sync::OnceLock,
    time::Duration,
};

pub(super) fn native_fixture() -> &'static Path {
    static BINARY: OnceLock<PathBuf> = OnceLock::new();
    BINARY.get_or_init(|| {
        let target = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../target/managed-runtime-fixtures")
            .join(std::process::id().to_string());
        std::fs::create_dir_all(&target).unwrap();
        let binary = target.join("fake-native.exe");
        let output = std::process::Command::new("rustc")
            .arg("--edition=2024")
            .arg(
                PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                    .join("src/managed_runtime/tests/fake_native.rs"),
            )
            .arg("-o")
            .arg(&binary)
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        binary
    })
}
pub(super) struct Fixture {
    pub(super) dir: tempfile::TempDir,
    pub(super) profile: SelectedProfile,
    pub(super) controller: Controller,
    pub(super) registry: Registry,
    pub(super) bin: PathBuf,
    pub(super) generation: String,
}
impl Drop for Fixture {
    fn drop(&mut self) {
        // Only fixture-private sentinel files: no process signals/kill commands.
        for home in [self.dir.path().join("home"), self.dir.path().join("other")] {
            if home.exists() {
                let _ = std::fs::write(home.join("fixture-stop"), b"stop fake peer");
            }
        }
        if let Ok(entries) = std::fs::read_dir(self.dir.path().join("registry")) {
            for entry in entries.flatten() {
                if let Ok(d) = pocket_runtime_ipc::load_descriptor(
                    &entry.path().join("descriptor.json"),
                    self.profile.config_home(),
                ) {
                    let until = std::time::Instant::now() + Duration::from_secs(2);
                    while !d.host_process.as_ref().is_some_and(|p| p.terminated())
                        && std::time::Instant::now() < until
                    {
                        std::thread::sleep(Duration::from_millis(10));
                    }
                }
            }
        }
    }
}
impl Fixture {
    pub(super) fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let home = dir.path().join("home");
        std::fs::create_dir(&home).unwrap();
        let bin = dir.path().join("bin");
        std::fs::create_dir(&bin).unwrap();
        let source = std::env::var_os("MANAGED_RUNTIME_TEST_HOST")
            .expect("build isolated runtime host and set MANAGED_RUNTIME_TEST_HOST");
        let bytes = std::fs::read(source).unwrap();
        std::fs::write(bin.join("pocket-runtime-host.exe"), &bytes).unwrap();
        let manifest = serde_json::to_vec(&json!({"schema":1,"product":"Codex Pocket","version":"0.6.3","files":[{"path":"pocket-runtime-host.exe","bytes":bytes.len(),"sha256":format!("{:x}",Sha256::digest(&bytes))}]})).unwrap();
        std::fs::write(bin.join("windows-manifest.json"), &manifest).unwrap();
        let generation = format!("{:x}", Sha256::digest(&manifest));
        let installation = Installation::new(&bin, generation.clone()).unwrap();
        let registry = Registry::new(dir.path().join("registry")).unwrap();
        let profile = SelectedProfile::new(&home, native_fixture()).unwrap();
        let controller = Controller::new(installation, registry.clone());
        Self {
            dir,
            profile,
            controller,
            registry,
            bin,
            generation,
        }
    }
    pub(super) fn state(&self) -> PathBuf {
        self.registry.profile_directory(&self.profile).unwrap()
    }
}
pub(super) async fn ended(d: &Descriptor) {
    tokio::time::timeout(Duration::from_secs(5), async {
        while !d.host_process.as_ref().unwrap().terminated()
            || !d.native_process.as_ref().unwrap().terminated()
        {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
}
pub(super) fn read_json(path: &Path) -> Value {
    serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap()
}
