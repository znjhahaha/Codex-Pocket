//! Installation-wide native startup admission. A durable gate outlives its lock owner.
use crate::native_process::NativeProcess;
use anyhow::{Context, Result, ensure};
use fs2::FileExt;
use serde::{Deserialize, Serialize};
use std::{
    cell::RefCell,
    fs::{self, File, OpenOptions},
    path::{Path, PathBuf},
};
#[path = "admission_fs.rs"]
mod physical;
pub use physical::{private_dir, read_json, validate_path};

pub const PROTOCOL: u32 = 1;
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum GatePhase {
    Closing,
    Starting,
    Released,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Gate {
    pub schema: u32,
    pub operation_id: String,
    pub phase: GatePhase,
    pub allowed_generation: Option<String>,
    pub release_file: PathBuf,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StartupContext {
    pub operation_id: String,
    pub release_file: PathBuf,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RegistrationPhase {
    Starting,
    Registered,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Registration {
    pub schema: u32,
    pub runtime_id: String,
    pub install_root: PathBuf,
    pub generation: String,
    pub bridge: NativeProcess,
    pub bridge_executable: PathBuf,
    pub descriptor: PathBuf,
    pub operation_id: Option<String>,
    pub release_file: Option<PathBuf>,
    pub phase: RegistrationPhase,
    pub native: Option<NativeProcess>,
}
fn uuid(value: &str) -> bool {
    uuid::Uuid::parse_str(value).is_ok_and(|v| v.to_string() == value)
}
pub fn valid_generation(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|v| v.is_ascii_digit() || (b'a'..=b'f').contains(&v))
}
pub fn file_sha256(path: &Path) -> Result<String> {
    use sha2::{Digest, Sha256};
    use std::io::Read;
    let mut file = physical::open_physical(path)?;
    let mut hash = Sha256::new();
    let mut bytes = [0u8; 65536];
    loop {
        let n = file.read(&mut bytes)?;
        if n == 0 {
            break;
        }
        hash.update(&bytes[..n]);
    }
    Ok(format!("{:x}", hash.finalize()))
}
impl Gate {
    fn validate(&self) -> Result<()> {
        ensure!(
            self.schema == PROTOCOL && uuid(&self.operation_id),
            "admission_gate_invalid"
        );
        ensure!(
            match self.phase {
                GatePhase::Closing => self.allowed_generation.is_none(),
                _ => self
                    .allowed_generation
                    .as_deref()
                    .is_some_and(valid_generation),
            },
            "admission_generation_invalid"
        );
        validate_path(&self.release_file)?;
        Ok(())
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Release {
    schema: u32,
    operation_id: String,
    released: bool,
}
pub fn read_release(path: &Path, operation: &str) -> Result<bool> {
    ensure!(uuid(operation), "admission_operation_invalid");
    validate_path(path)?;
    if !path.try_exists()? {
        return Ok(false);
    }
    let r: Release = read_json(path, 4096)?;
    ensure!(
        r.schema == 1 && r.operation_id == operation && r.released,
        "admission_receipt_invalid"
    );
    Ok(true)
}

#[derive(Clone, Debug)]
pub struct Installation {
    root: PathBuf,
    control: PathBuf,
}
struct HeldLock(File);
impl Drop for HeldLock {
    fn drop(&mut self) {
        let _ = FileExt::unlock(&self.0);
    }
}
pub struct StartupGuard {
    installation: Installation,
    _lock: HeldLock,
    generation: String,
    context: Option<StartupContext>,
    owned_registrations: RefCell<Vec<Registration>>,
}
pub struct UpdateGuard {
    installation: Installation,
    _lock: HeldLock,
}
impl Installation {
    pub fn open(root: &Path) -> Result<Self> {
        validate_path(root)?;
        ensure!(root.is_dir(), "admission_root_missing");
        let control = root.join("control");
        physical::private_dir(&control)?;
        physical::private_dir(&control.join("native-startups"))?;
        Ok(Self {
            root: root.into(),
            control,
        })
    }
    pub fn root(&self) -> &Path {
        &self.root
    }
    fn lock(&self, exclusive: bool) -> Result<HeldLock> {
        let p = self.control.join("native-admission.lock");
        validate_path(&p)?;
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true).truncate(false);
        #[cfg(windows)]
        {
            use std::os::windows::fs::OpenOptionsExt;
            // Do not allow lock-file replacement while any participant holds it.
            options.share_mode(1 | 2);
        }
        let file = options.open(&p)?;
        physical::verify_file(&p, &file)?;
        if exclusive {
            FileExt::try_lock_exclusive(&file)
        } else {
            FileExt::try_lock_shared(&file)
        }
        .context("admission_startup_in_progress")?;
        Ok(HeldLock(file))
    }
    fn gate(&self) -> Result<Option<Gate>> {
        let p = self.control.join("native-admission.json");
        validate_path(&p)?;
        if !p.try_exists()? {
            return Ok(None);
        }
        let gate: Gate = read_json(&p, 8192)?;
        gate.validate()?;
        Ok(Some(gate))
    }
    pub fn startup(
        &self,
        generation: &str,
        context: Option<&StartupContext>,
    ) -> Result<StartupGuard> {
        ensure!(valid_generation(generation), "admission_generation_invalid");
        if let Some(c) = context {
            ensure!(uuid(&c.operation_id), "admission_operation_invalid");
            validate_path(&c.release_file)?;
        }
        let lock = self.lock(false)?;
        match self.gate()? {
            None => ensure!(context.is_none(), "admission_update_gate_missing"),
            Some(g) => match g.phase {
                GatePhase::Closing => anyhow::bail!("admission_closed"),
                GatePhase::Starting => {
                    ensure!(
                        context.is_some_and(|c| c.operation_id == g.operation_id
                            && c.release_file == g.release_file)
                            && g.allowed_generation.as_deref() == Some(generation),
                        "admission_candidate_unapproved"
                    );
                    ensure!(
                        !read_release(&g.release_file, &g.operation_id)?,
                        "admission_release_phase_invalid"
                    );
                }
                GatePhase::Released => {
                    ensure!(
                        read_release(&g.release_file, &g.operation_id)?,
                        "admission_release_missing"
                    );
                    ensure!(
                        g.allowed_generation.as_deref() == Some(generation),
                        "admission_generation_changed"
                    );
                    ensure!(
                        context.is_none_or(|c| c.operation_id == g.operation_id
                            && c.release_file == g.release_file),
                        "admission_operation_changed"
                    );
                }
            },
        }
        Ok(StartupGuard {
            installation: self.clone(),
            _lock: lock,
            generation: generation.into(),
            context: context.cloned(),
            owned_registrations: RefCell::new(Vec::new()),
        })
    }
    pub fn update(&self) -> Result<UpdateGuard> {
        Ok(UpdateGuard {
            installation: self.clone(),
            _lock: self.lock(true)?,
        })
    }
    fn record_path(&self, id: &str) -> Result<PathBuf> {
        ensure!(uuid(id), "admission_registration_invalid");
        Ok(self
            .control
            .join("native-startups")
            .join(format!("{id}.json")))
    }
    pub fn unregister(&self, runtime_id: &str) -> Result<()> {
        let _lock = self.lock(false)?;
        let p = self.record_path(runtime_id)?;
        validate_path(&p)?;
        fs::remove_file(p)?;
        Ok(())
    }
}
impl Registration {
    fn validate(&self, root: &Path) -> Result<()> {
        ensure!(
            self.schema == 1
                && uuid(&self.runtime_id)
                && self.install_root == root
                && valid_generation(&self.generation)
                && self.bridge.pid > 0
                && self.bridge.created_at > 0,
            "admission_registration_invalid"
        );
        ensure!(
            self.bridge_executable
                == root
                    .join("bin")
                    .join(&self.generation)
                    .join("pocket-bridge.exe")
                && self.descriptor
                    == root
                        .join("runtimes")
                        .join(format!("{}.json", self.runtime_id)),
            "admission_registration_scope"
        );
        ensure!(
            match (&self.operation_id, &self.release_file) {
                (None, None) => true,
                (Some(op), Some(path)) => uuid(op) && validate_path(path).is_ok(),
                _ => false,
            },
            "admission_registration_operation_invalid"
        );
        ensure!(
            match self.phase {
                RegistrationPhase::Starting => self.native.is_none(),
                RegistrationPhase::Registered => self
                    .native
                    .as_ref()
                    .is_some_and(|n| n.pid > 0 && n.created_at > 0),
            },
            "admission_registration_phase_invalid"
        );
        Ok(())
    }
}
impl StartupGuard {
    pub fn register(&self, r: &Registration) -> Result<()> {
        r.validate(self.installation.root())?;
        ensure!(
            r.phase == RegistrationPhase::Starting
                && r.generation == self.generation
                && r.operation_id == self.context.as_ref().map(|c| c.operation_id.clone())
                && r.release_file == self.context.as_ref().map(|c| c.release_file.clone()),
            "admission_registration_mismatch"
        );
        let p = self.installation.record_path(&r.runtime_id)?;
        ensure!(!p.try_exists()?, "admission_registration_exists");
        physical::atomic(&p, r)?;
        self.owned_registrations.borrow_mut().push(r.clone());
        Ok(())
    }
    /// Cancel only after a definite spawn failure proves that no child was created.
    /// Retain this shared guard; Drop and dead-PID observations never cancel records.
    pub fn cancel_starting(&self, r: &Registration) -> Result<()> {
        r.validate(self.installation.root())?;
        ensure!(
            r.phase == RegistrationPhase::Starting
                && r.native.is_none()
                && self.owned_registrations.borrow().contains(r),
            "admission_cancellation_unowned"
        );
        let p = self.installation.record_path(&r.runtime_id)?;
        let current: Registration = read_json(&p, 16384)?;
        ensure!(current == *r, "admission_cancellation_changed");
        fs::remove_file(p)?;
        self.owned_registrations.borrow_mut().retain(|owned| owned != r);
        Ok(())
    }
    pub fn registered(&self, r: &Registration) -> Result<()> {
        r.validate(self.installation.root())?;
        let p = self.installation.record_path(&r.runtime_id)?;
        let mut before: Registration = read_json(&p, 16384)?;
        ensure!(
            before.phase == RegistrationPhase::Starting,
            "admission_already_registered"
        );
        before.phase = RegistrationPhase::Registered;
        before.native = r.native.clone();
        ensure!(&before == r, "admission_registration_changed");
        let descriptor: serde_json::Value = read_json(&r.descriptor, 32768)?;
        ensure!(
            descriptor["pid"] == r.bridge.pid
                && descriptor["runtimeId"] == r.runtime_id
                && descriptor["startupAdmissionProtocol"] == PROTOCOL
                && descriptor["startupAdmission"] == serde_json::to_value(r)?,
            "admission_descriptor_missing"
        );
        physical::atomic(&p, r)
    }
}
impl UpdateGuard {
    pub fn gate(&self) -> Result<Option<Gate>> {
        self.installation.gate()
    }
    pub fn set_gate(&self, next: &Gate) -> Result<()> {
        next.validate()?;
        physical::private_dir(
            next.release_file
                .parent()
                .context("admission_release_parent")?,
        )?;
        match self.gate()? {
            None => ensure!(next.phase == GatePhase::Closing, "admission_close_required"),
            Some(old) => {
                let released = read_release(&old.release_file, &old.operation_id)?;
                if next.operation_id != old.operation_id {
                    ensure!(
                        released && next.phase == GatePhase::Closing,
                        "admission_operation_in_progress"
                    );
                } else {
                    ensure!(
                        next.release_file == old.release_file,
                        "admission_receipt_changed"
                    );
                    if released || old.phase == GatePhase::Released {
                        ensure!(
                            released
                                && next.phase == GatePhase::Released
                                && next.allowed_generation == old.allowed_generation,
                            "admission_release_irreversible"
                        );
                    } else {
                        ensure!(
                            next.phase != GatePhase::Released,
                            "admission_release_missing"
                        );
                        ensure!(
                            old.phase != GatePhase::Starting
                                || next.phase != GatePhase::Starting
                                || next.allowed_generation == old.allowed_generation,
                            "admission_close_required"
                        );
                    }
                }
            }
        }
        if next.phase == GatePhase::Released {
            ensure!(
                read_release(&next.release_file, &next.operation_id)?,
                "admission_release_missing"
            );
        }
        physical::atomic(
            &self.installation.control.join("native-admission.json"),
            next,
        )
    }
    pub fn registrations(&self) -> Result<Vec<Registration>> {
        let mut records = Vec::new();
        for entry in fs::read_dir(self.installation.control.join("native-startups"))? {
            let entry = entry?;
            let path = entry.path();
            // Atomic writer temporary files contain no runnable registration.
            if path.extension().and_then(|s| s.to_str()) != Some("json") {
                continue;
            }
            ensure!(records.len() < 256, "admission_registration_limit");
            let r: Registration = read_json(&path, 16384)?;
            r.validate(self.installation.root())?;
            ensure!(
                self.installation.record_path(&r.runtime_id)? == path,
                "admission_registration_name"
            );
            records.push(r);
        }
        Ok(records)
    }
    pub fn remove_registration(&self, runtime_id: &str) -> Result<()> {
        let p = self.installation.record_path(runtime_id)?;
        validate_path(&p)?;
        fs::remove_file(p)?;
        Ok(())
    }
}

#[cfg(test)]
#[path = "admission_tests.rs"]
mod tests;
