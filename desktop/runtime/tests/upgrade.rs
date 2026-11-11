use pocket_desktop_runtime::{launcher, storage, supervisor::upgrade::*};
use serde_json::json;
use sha2::{Digest, Sha256};
use std::{fs, path::Path};
#[path = "../test_support/mod.rs"]
mod test_support;

fn generation(root: &Path, payload: &str) -> String {
    let bytes = payload.as_bytes();
    let files: Vec<_> = ["pocket-supervisor.exe", "pocket-agent.exe", "pocket-bridge.exe", "pocket-upgrade.exe"]
        .iter().map(|name| json!({"path": name, "sha256": format!("{:x}", Sha256::digest(bytes)), "bytes": bytes.len()})).collect();
    let manifest = serde_json::to_vec(&json!({"version":"0.6.3", "files":files})).unwrap();
    let hash = format!("{:x}", Sha256::digest(&manifest));
    let bin = root.join("bin").join(&hash);
    fs::create_dir_all(&bin).unwrap();
    fs::write(bin.join("windows-manifest.json"), manifest).unwrap();
    for name in [
        "pocket-supervisor.exe",
        "pocket-agent.exe",
        "pocket-bridge.exe",
        "pocket-upgrade.exe",
    ] {
        fs::write(bin.join(name), bytes).unwrap();
    }
    hash
}
fn fixture() -> (tempfile::TempDir, String, String) {
    let root = test_support::tempdir();
    let old = generation(root.path(), "old");
    let new = generation(root.path(), "new");
    let state = root.path().join("state");
    fs::create_dir(&state).unwrap();
    storage::atomic(&state.join("config.json"), &json!({"preserve":true})).unwrap();
    storage::atomic(&root.path().join("desktop-install.json"), &json!({"version":3,"installRoot":root.path(),"bridge":root.path().join("pocket-launcher.exe"),"generation":new,"releaseHash":new,"stateDirectory":state})).unwrap();
    (root, old, new)
}

#[derive(Default)]
struct Backend {
    unsupported: bool,
    bad_checkpoint: bool,
    bad_routes: bool,
    bad_rollback: bool,
    bad_generation: bool,
    bad_protocol: bool,
    bad_readback: bool,
    unverified_component: bool,
    start_failed: bool,
    release_failed: bool,
    unfenced: bool,
    native_not_preserved: bool,
    pending: bool,
    final_checkpoint: bool,
    calls: Vec<&'static str>,
}
impl HandoffBackend for Backend {
    fn supports_handoff(&mut self, _: &UpgradeStatus) -> anyhow::Result<bool> {
        Ok(!self.unsupported)
    }
    fn quiesce(&mut self, s: &UpgradeStatus) -> anyhow::Result<Checkpoint> {
        self.calls.push("save");
        Ok(Checkpoint {
            operation_id: s.operation_id.clone(),
            generation: s.from_generation.clone(),
            saved_sha256: "a".repeat(64),
            reread_sha256: if self.bad_checkpoint {
                "b".repeat(64)
            } else {
                "a".repeat(64)
            },
            pending_writes: if self.pending { 3 } else { 0 },
            writes_fenced: !self.unfenced,
            native_tasks_preserved: !self.native_not_preserved,
        })
    }
    fn release_old(&mut self, _: &UpgradeStatus) -> anyhow::Result<()> {
        self.calls.push("release");
        if self.release_failed {
            anyhow::bail!("private fixture failure")
        };
        Ok(())
    }
    fn released_checkpoint(&mut self, s: &UpgradeStatus) -> anyhow::Result<Option<Checkpoint>> {
        if !self.final_checkpoint {
            return Ok(None);
        }
        let mut c = self.quiesce(s)?;
        c.saved_sha256 = "c".repeat(64);
        c.reread_sha256 = c.saved_sha256.clone();
        Ok(Some(c))
    }
    fn start_new(&mut self, _: &UpgradeStatus) -> anyhow::Result<()> {
        self.calls.push("start");
        if self.start_failed {
            anyhow::bail!("private fixture failure")
        };
        Ok(())
    }
    fn verify_new(&mut self, s: &UpgradeStatus) -> anyhow::Result<Verification> {
        self.calls.push("verify");
        let mut v = verified(s, &s.to_generation, !self.bad_routes);
        if self.bad_generation {
            v.generation = s.from_generation.clone();
        }
        if self.bad_protocol {
            v.control_protocol = 99;
        }
        if self.bad_readback {
            v.checkpoint_sha256 = Some("b".repeat(64));
        }
        if self.unverified_component {
            v.components_verified = false;
        }
        Ok(v)
    }
    fn rollback(&mut self, s: &UpgradeStatus) -> anyhow::Result<Verification> {
        self.calls.push("rollback");
        Ok(verified(s, &s.from_generation, !self.bad_rollback))
    }
}
fn verified(s: &UpgradeStatus, g: &str, routes: bool) -> Verification {
    Verification {
        operation_id: s.operation_id.clone(),
        generation: g.into(),
        control_protocol: 1,
        routes_read: routes,
        checkpoint_sha256: s.checkpoint.as_ref().map(|c| c.saved_sha256.clone()),
        native_tasks_preserved: true,
        components_verified: true,
    }
}

#[test]
fn unsupported_old_components_wait_without_releasing_or_changing_installation() {
    let (root, old, _) = fixture();
    let state = root.path().join("state");
    let original = fs::read(root.path().join("desktop-install.json")).unwrap();
    let mut c = Coordinator::request(root.path(), &state, &old).unwrap();
    let mut b = Backend {
        unsupported: true,
        ..Default::default()
    };
    assert_eq!(c.execute(&mut b).unwrap().phase, Phase::WaitingRestart);
    assert!(b.calls.is_empty());
    assert_eq!(
        fs::read(root.path().join("desktop-install.json")).unwrap(),
        original
    );
    assert_eq!(
        read_status(&state).unwrap().unwrap().phase,
        Phase::WaitingRestart
    );
}
#[test]
fn same_version_different_generation_requires_saved_reread_before_release() {
    let (root, old, new) = fixture();
    let mut c = Coordinator::request(root.path(), &root.path().join("state"), &old).unwrap();
    let mut b = Backend::default();
    let result = c.execute(&mut b).unwrap();
    assert_eq!(result.phase, Phase::Succeeded);
    assert_eq!(result.to_generation, new);
    assert_eq!(b.calls, ["save", "release", "start", "verify"]);
}
#[test]
fn final_release_checkpoint_replaces_ready_hash_and_bridge_remains_pending_restart() {
    let (root, old, _) = fixture();
    let mut c = Coordinator::request(root.path(), &root.path().join("state"), &old).unwrap();
    let s = c
        .execute(&mut Backend {
            final_checkpoint: true,
            ..Default::default()
        })
        .unwrap();
    assert_eq!(s.phase, Phase::Succeeded);
    assert_eq!(s.checkpoint.unwrap().saved_sha256, "c".repeat(64));
    assert!(s.bridge_restart_required);
}
#[test]
fn checkpoint_mismatch_never_releases_old_background() {
    let (root, old, _) = fixture();
    let mut c = Coordinator::request(root.path(), &root.path().join("state"), &old).unwrap();
    let mut b = Backend {
        bad_checkpoint: true,
        ..Default::default()
    };
    assert_ne!(c.execute(&mut b).unwrap().phase, Phase::Succeeded);
    assert!(!b.calls.contains(&"release"));
    assert!(b.calls.contains(&"rollback"));
}
#[test]
fn pending_writes_never_release_old_background() {
    let (root, old, _) = fixture();
    let mut c = Coordinator::request(root.path(), &root.path().join("state"), &old).unwrap();
    let mut b = Backend {
        pending: true,
        ..Default::default()
    };
    assert_eq!(
        c.execute(&mut b).unwrap().failure,
        Some(Failure::CheckpointMismatch)
    );
    assert_eq!(b.calls, ["save", "rollback"]);
}
#[test]
fn unfenced_writes_or_unpreserved_native_tasks_never_release_old_background() {
    for unfenced in [false, true] {
        let (root, old, _) = fixture();
        let mut c = Coordinator::request(root.path(), &root.path().join("state"), &old).unwrap();
        let mut b = Backend {
            unfenced,
            native_not_preserved: !unfenced,
            ..Default::default()
        };
        assert_eq!(
            c.execute(&mut b).unwrap().failure,
            Some(Failure::CheckpointMismatch)
        );
        assert_eq!(b.calls, ["save", "rollback"]);
    }
}
#[test]
fn failed_routes_read_rolls_back_and_unverified_rollback_is_failure() {
    for bad_rollback in [false, true] {
        let (root, old, _) = fixture();
        let mut c = Coordinator::request(root.path(), &root.path().join("state"), &old).unwrap();
        let mut b = Backend {
            bad_routes: true,
            bad_rollback,
            ..Default::default()
        };
        assert_eq!(
            c.execute(&mut b).unwrap().phase,
            if bad_rollback {
                Phase::Failed
            } else {
                Phase::RolledBack
            }
        );
        assert_eq!(b.calls, ["save", "release", "start", "verify", "rollback"]);
    }
}
#[test]
fn durable_request_and_both_locks_survive_coordinator_reopen() {
    let (root, old, _) = fixture();
    let state = root.path().join("state");
    let c = Coordinator::request(root.path(), &state, &old).unwrap();
    let id = c.status().operation_id.clone();
    assert!(Coordinator::open(root.path(), &state).is_err());
    drop(c);
    let mut reopened = Coordinator::open(root.path(), &state).unwrap();
    assert_eq!(reopened.status().operation_id, id);
    assert_eq!(
        reopened.execute(&mut Backend::default()).unwrap().phase,
        Phase::Succeeded
    );
}
#[test]
fn immutable_directory_must_equal_manifest_digest_even_if_record_hash_matches() {
    let (root, old, _) = fixture();
    let v: serde_json::Value = storage::read(&root.path().join("desktop-install.json")).unwrap();
    let mut v = v;
    v["generation"] = json!(old);
    storage::atomic(&root.path().join("desktop-install.json"), &v).unwrap();
    // A record pointing to a different directory must not validate by releaseHash alone.
    let bytes = fs::read(
        root.path()
            .join("bin")
            .join(v["releaseHash"].as_str().unwrap())
            .join("windows-manifest.json"),
    )
    .unwrap();
    fs::write(
        root.path()
            .join("bin")
            .join(v["generation"].as_str().unwrap())
            .join("windows-manifest.json"),
        bytes,
    )
    .unwrap();
    assert!(launcher::resolve_release(root.path()).is_err());
}

#[test]
fn candidate_generation_protocol_readback_and_all_components_are_required() {
    for failure in 0..4 {
        let (root, old, _) = fixture();
        let mut c = Coordinator::request(root.path(), &root.path().join("state"), &old).unwrap();
        let mut b = Backend {
            bad_generation: failure == 0,
            bad_protocol: failure == 1,
            bad_readback: failure == 2,
            unverified_component: failure == 3,
            ..Default::default()
        };
        assert_eq!(c.execute(&mut b).unwrap().phase, Phase::RolledBack);
        assert!(b.calls.contains(&"rollback"));
    }
}
#[test]
fn release_and_start_failures_are_sanitized_and_rolled_back() {
    for release_failed in [false, true] {
        let (root, old, _) = fixture();
        let state = root.path().join("state");
        let mut c = Coordinator::request(root.path(), &state, &old).unwrap();
        let mut b = Backend {
            release_failed,
            start_failed: !release_failed,
            ..Default::default()
        };
        let status = c.execute(&mut b).unwrap();
        assert_eq!(status.phase, Phase::RolledBack);
        assert_eq!(
            status.failure,
            Some(if release_failed {
                Failure::ReleaseFailed
            } else {
                Failure::StartFailed
            })
        );
        assert!(
            !fs::read_to_string(storage::control(&state).join("upgrade.json"))
                .unwrap()
                .contains("private fixture failure")
        );
    }
}
#[test]
fn every_interrupted_intent_recovers_by_rollback_without_replaying_release_or_start() {
    for phase in [
        Phase::Quiescing,
        Phase::Checkpointed,
        Phase::Releasing,
        Phase::Starting,
        Phase::Verifying,
        Phase::RollingBack,
    ] {
        let (root, old, _) = fixture();
        let state = root.path().join("state");
        let c = Coordinator::request(root.path(), &state, &old).unwrap();
        let mut status = c.status().clone();
        drop(c);
        status.phase = phase;
        if phase != Phase::Quiescing {
            status.checkpoint = Some(Backend::default().quiesce(&status).unwrap());
        }
        storage::atomic(&storage::control(&state).join("upgrade.json"), &status).unwrap();
        let mut c = Coordinator::open(root.path(), &state).unwrap();
        let mut b = Backend::default();
        assert_eq!(c.execute(&mut b).unwrap().phase, Phase::RolledBack);
        assert_eq!(b.calls, ["rollback"]);
    }
}
#[test]
fn state_and_installation_locks_are_both_held_and_failed_journals_not_overwritten() {
    let (root, old, _) = fixture();
    let state = root.path().join("state");
    let mut c = Coordinator::request(root.path(), &state, &old).unwrap();
    assert!(storage::Lock::acquire(&root.path().join("control/upgrade.lock")).is_err());
    assert!(storage::Lock::acquire(&storage::control(&state).join("upgrade.lock")).is_err());
    c.execute(&mut Backend {
        bad_routes: true,
        bad_rollback: true,
        ..Default::default()
    })
    .unwrap();
    drop(c);
    let before = fs::read(storage::control(&state).join("upgrade.json")).unwrap();
    assert!(Coordinator::request(root.path(), &state, &old).is_err());
    assert_eq!(
        fs::read(storage::control(&state).join("upgrade.json")).unwrap(),
        before
    );
}
#[test]
fn shipped_backend_waits_and_terminal_reopen_has_no_side_effects() {
    let (root, old, _) = fixture();
    let state = root.path().join("state");
    let mut c = Coordinator::request(root.path(), &state, &old).unwrap();
    let id = c.status().operation_id.clone();
    assert_eq!(
        c.execute(&mut LocalBackend).unwrap().phase,
        Phase::WaitingRestart
    );
    drop(c);
    assert_eq!(
        run(root.path(), &state, &id).unwrap().phase,
        Phase::WaitingRestart
    );
    assert!(run(root.path(), &state, "wrong operation").is_err());
}

#[test]
fn checkpoint_or_installation_change_cannot_release_a_background() {
    let (root, old, _) = fixture();
    let state = root.path().join("state");
    let mut c = Coordinator::request(root.path(), &state, &old).unwrap();
    fs::write(root.path().join("desktop-install.json"), "{}").unwrap();
    let mut b = Backend::default();
    assert_eq!(
        c.execute(&mut b).unwrap().failure,
        Some(Failure::InstallationChanged)
    );
    assert!(b.calls.is_empty());
}
#[test]
fn failed_atomic_checkpoint_persistence_never_calls_release() {
    struct DiskFailure {
        inner: Backend,
        path: std::path::PathBuf,
    }
    impl HandoffBackend for DiskFailure {
        fn supports_handoff(&mut self, s: &UpgradeStatus) -> anyhow::Result<bool> {
            self.inner.supports_handoff(s)
        }
        fn quiesce(&mut self, s: &UpgradeStatus) -> anyhow::Result<Checkpoint> {
            let result = self.inner.quiesce(s)?;
            // Simulate failed replace after prepare, before release intent.
            fs::remove_file(&self.path).unwrap();
            fs::create_dir(&self.path).unwrap();
            Ok(result)
        }
        fn release_old(&mut self, s: &UpgradeStatus) -> anyhow::Result<()> {
            self.inner.release_old(s)
        }
        fn start_new(&mut self, s: &UpgradeStatus) -> anyhow::Result<()> {
            self.inner.start_new(s)
        }
        fn verify_new(&mut self, s: &UpgradeStatus) -> anyhow::Result<Verification> {
            self.inner.verify_new(s)
        }
        fn rollback(&mut self, s: &UpgradeStatus) -> anyhow::Result<Verification> {
            self.inner.rollback(s)
        }
    }
    let (root, old, _) = fixture();
    let state = root.path().join("state");
    let mut c = Coordinator::request(root.path(), &state, &old).unwrap();
    let mut b = DiskFailure {
        inner: Backend::default(),
        path: storage::control(&state).join("upgrade.json"),
    };
    assert!(c.execute(&mut b).is_err());
    assert_eq!(b.inner.calls, ["save"]);
    assert_eq!(
        c.status().phase,
        Phase::Quiescing,
        "failed persistence must not advance in-memory phase"
    );
}

#[test]
fn invalid_durable_success_and_malformed_journals_are_never_accepted() {
    let (root, old, _) = fixture();
    let state = root.path().join("state");
    let c = Coordinator::request(root.path(), &state, &old).unwrap();
    let mut s = c.status().clone();
    drop(c);
    s.phase = Phase::Succeeded;
    storage::atomic(&storage::control(&state).join("upgrade.json"), &s).unwrap();
    assert!(read_status(&state).is_err());
    assert!(Coordinator::open(root.path(), &state).is_err());
    fs::write(storage::control(&state).join("upgrade.json"), "{truncated").unwrap();
    assert!(read_status(&state).is_err());
    assert!(Coordinator::request(root.path(), &state, &old).is_err());
}
