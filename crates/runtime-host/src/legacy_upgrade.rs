//! Explicit local upgrade of an idle legacy Host whose cold tombstones keep its
//! shutdown RPC fenced. This never targets the native process or clears recovery.
use anyhow::{Context, Result, ensure};
use fs2::FileExt;
use pocket_core::native_process::NativeProcess;
use pocket_runtime_ipc::{Descriptor, Health, HostClient};
use serde_json::{Value, json};
use std::{io::Write, path::Path, time::Duration};

fn validate(
    d: &Descriptor,
    h: &Health,
    disk: &Value,
    recovery: &Value,
    loaded: &Value,
) -> Result<()> {
    ensure!(
        h.scoped_admission == 0
            && h.reconciliation_required
            && h.active_turns == 0
            && h.pending_requests == 0,
        "Legacy Host is not fenced and idle"
    );
    ensure!(
        h.runtime_id == d.runtime_id
            && h.generation == d.generation
            && h.pid == d.pid
            && h.native_pid == d.native_pid
            && h.host_process == d.host_process
            && h.native_process == d.native_process
            && h.initialized
            && h.capabilities.survives_broker_disconnect
            && !h.capabilities.survives_host_exit,
        "Legacy Host identity or lifetime contract changed"
    );
    ensure!(
        disk["schema"] == 2
            && disk["runtime_id"] == d.runtime_id
            && disk["generation"] == d.generation
            && disk["reconciliation_required"] == true
            && recovery["generation"] == d.generation
            && recovery["reconciliationRequired"] == true,
        "Legacy recovery identity changed"
    );
    let commands = disk["commands"]
        .as_object()
        .context("Command inventory missing")?;
    let answers = disk["answers"]
        .as_object()
        .context("Answer inventory missing")?;
    let runs = disk["runs"].as_object().context("Run inventory missing")?;
    let pending = disk["pending"]
        .as_object()
        .context("Pending inventory missing")?;
    ensure!(
        commands.len() + answers.len() <= pocket_runtime_ipc::MAX_RECOVERY_RECORDS
            && pending.is_empty()
            && runs.values().all(|r| r["state"] == "completed"),
        "Legacy run or question is not terminal"
    );
    let mut unknown = 0;
    for (id, record) in commands.iter().chain(answers) {
        ensure!(
            (id.starts_with("s:") || id.starts_with("n:"))
                && id.len() <= 258
                && record.as_object().is_some_and(|r| r.len() == 2)
                && record["generation"]
                    .as_str()
                    .is_some_and(|g| uuid::Uuid::parse_str(g).is_ok()),
            "Invalid legacy receipt"
        );
        let completed = record["completed"]
            .as_bool()
            .context("Receipt state missing")?;
        if !completed {
            unknown += 1;
            ensure!(
                record["generation"] != d.generation,
                "Current generation has an unconfirmed effect"
            );
        }
    }
    ensure!(
        unknown > 0
            && recovery["commandCount"].as_u64() == Some(commands.len() as u64)
            && recovery["answerCount"].as_u64() == Some(answers.len() as u64)
            && recovery["unconfirmedWrites"].as_u64() == Some(unknown)
            && recovery["unknownOperations"].as_u64() == Some(unknown)
            && recovery["pendingRequests"]
                .as_array()
                .is_some_and(Vec::is_empty)
            && recovery["runs"].as_array().is_some_and(
                |r| r.len() == runs.len() && r.iter().all(|v| runs.values().any(|e| e == v))
            ),
        "Legacy recovery inventory disagrees"
    );
    ensure!(
        loaded["data"].as_array().is_some_and(Vec::is_empty)
            && loaded.get("nextCursor") == Some(&Value::Null),
        "Native loaded inventory is not verified empty"
    );
    Ok(())
}

fn read_json(path: &Path, limit: u64) -> Result<Value> {
    serde_json::from_slice(&crate::client::read_private(path, limit)?)
        .map_err(|_| anyhow::anyhow!("Invalid legacy upgrade evidence"))
}
fn descriptor_same(a: &Descriptor, b: &Descriptor) -> bool {
    a.runtime_id == b.runtime_id
        && a.generation == b.generation
        && a.host_process == b.host_process
        && a.native_process == b.native_process
        && a.token == b.token
        && a.port == b.port
        && a.config_home == b.config_home
}
fn archived_inventory_matches(previous: &Value, current: &Value, generation: &str) -> bool {
    if ["answers", "runs", "pending"]
        .iter()
        .any(|key| previous[key] != current[key])
    {
        return false;
    }
    let (Some(before), Some(after)) = (
        previous["commands"].as_object(),
        current["commands"].as_object(),
    ) else {
        return false;
    };
    before
        .iter()
        .all(|(id, record)| after.get(id) == Some(record))
        && after.iter().all(|(id, record)| {
            before.contains_key(id)
                || (id
                    .strip_prefix("s:legacy-upgrade-read:")
                    .is_some_and(|suffix| uuid::Uuid::parse_str(suffix).is_ok())
                    && *record == json!({"generation":generation,"completed":true}))
        })
}

/// `retire=false` authenticates and reads only. The caller supplies the expected
/// generation from its reviewed installation evidence; there is no automatic retry.
pub async fn legacy_upgrade(
    state: &Path,
    descriptor: &Path,
    home: &Path,
    expected: &str,
    retire: bool,
) -> Result<Value> {
    ensure!(
        uuid::Uuid::parse_str(expected).is_ok(),
        "Expected Host generation required"
    );
    ensure!(
        descriptor
            .file_name()
            .is_some_and(|n| n == "descriptor.json")
            && std::fs::canonicalize(descriptor.parent().context("Descriptor parent missing")?)?
                == std::fs::canonicalize(state)?,
        "Descriptor is outside managed registry"
    );
    let lock = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(state.join("ensure.lock"))?;
    pocket_agent::storage::verify_physical_file(&state.join("ensure.lock"), &lock)?;
    lock.try_lock_exclusive()
        .context("Managed launcher is busy")?;
    let d = pocket_runtime_ipc::load_descriptor(descriptor, home)?;
    ensure!(
        d.generation == expected && d.pid != std::process::id(),
        "Expected Host identity changed"
    );
    let owner = read_json(&state.join("startup.json"), 16384)?;
    ensure!(
        owner["schema"] == 1
            && owner["phase"] == "ready"
            && owner["descriptorGeneration"] == d.generation
            && serde_json::from_value::<NativeProcess>(owner["hostProcess"].clone())?
                == *d.host_process.as_ref().context("Host identity missing")?
            && crate::client::normalized_home(Path::new(
                owner["configHome"].as_str().context("Owner home missing")?
            ))? == crate::client::normalized_home(home)?,
        "Startup owner does not match Host"
    );
    let host = ProcessGuard::open(
        d.host_process.as_ref().context("Host identity missing")?,
        retire,
    )?;
    let native = ProcessGuard::open(
        d.native_process
            .as_ref()
            .context("Native identity missing")?,
        false,
    )?;
    let host_path = host.binary()?;
    let installed = pocket_agent::managed_runtime::Installation::new(
        host_path.parent().context("Host installation missing")?,
        owner["installationGeneration"]
            .as_str()
            .context("Owner installation missing")?,
    )?;
    ensure!(
        std::fs::canonicalize(installed.verify()?)? == std::fs::canonicalize(&host_path)?
            && std::fs::canonicalize(native.binary()?)?
                == std::fs::canonicalize(
                    owner["nativeBinary"]
                        .as_str()
                        .context("Native binary missing")?
                )?,
        "Running binaries do not match managed ownership"
    );
    let mut client = HostClient::attach(&d).await?;
    let first = client.health().await?;
    let first_disk = read_json(&state.join("recovery.json"), 32 * 1024 * 1024)?;
    let read_id = format!("legacy-upgrade-read:{}", uuid::Uuid::new_v4());
    let loaded = client
        .request(json!(read_id), "thread/loaded/list", json!({"limit":100}))
        .await?;
    ensure!(
        loaded.get("error").is_none(),
        "Native inventory read failed"
    );
    let recovery = client.recovery().await?;
    let disk = read_json(&state.join("recovery.json"), 32 * 1024 * 1024)?;
    ensure!(
        disk["schema"] == 2,
        "Legacy upgrade requires payload-free schema 2 evidence"
    );
    let parsed =
        crate::recovery::Store::open(&state.join("recovery.json"), &d.runtime_id, &d.generation)?;
    let disk_snapshot = parsed.state.snapshot();
    for key in [
        "generation",
        "commandCount",
        "answerCount",
        "unconfirmedWrites",
        "unknownOperations",
        "runs",
        "pendingRequests",
    ] {
        ensure!(
            disk_snapshot[key] == recovery[key],
            "Disk and authenticated recovery differ"
        );
    }
    // Read events may advance sequence, but must not change effect inventory.
    // Older Hosts journal even read requests. Allow only our one completed,
    // correlated inventory query to be added; all original entries must match.
    let mut after_commands = disk["commands"].clone();
    if let Some(read) = after_commands
        .as_object_mut()
        .context("Command inventory missing")?
        .remove(&format!("s:{read_id}"))
    {
        ensure!(
            read == json!({"generation":d.generation,"completed":true}),
            "Inventory query is unconfirmed"
        );
    }
    ensure!(
        first_disk["commands"] == after_commands,
        "Legacy command inventory changed while checking"
    );
    for key in ["answers", "runs", "pending"] {
        ensure!(
            first_disk[key] == disk[key],
            "Legacy effect inventory changed while checking"
        );
    }
    validate(&d, &first, &disk, &recovery, &loaded["result"])?;
    let final_health = client.health().await?;
    validate(
        &d,
        &final_health,
        &disk,
        &client.recovery().await?,
        &loaded["result"],
    )?;
    ensure!(
        descriptor_same(&d, &pocket_runtime_ipc::load_descriptor(descriptor, home)?)
            && owner == read_json(&state.join("startup.json"), 16384)?,
        "Legacy owner changed while checking"
    );
    let proof = json!({"schema":1,"runtimeId":d.runtime_id,"generation":d.generation,
        "hostProcess":d.host_process,"nativeProcess":d.native_process,"startup":owner,"recovery":disk});
    if retire {
        let path = state.join(format!("legacy-upgrade-{}.json", d.generation));
        match std::fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&path)
        {
            Ok(mut file) => {
                file.write_all(&serde_json::to_vec(&proof)?)?;
                file.sync_all()?;
            }
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {
                let previous = read_json(&path, 32 * 1024 * 1024)?;
                for key in [
                    "schema",
                    "runtimeId",
                    "generation",
                    "hostProcess",
                    "nativeProcess",
                    "startup",
                ] {
                    ensure!(
                        previous[key] == proof[key],
                        "Existing upgrade proof belongs to another owner"
                    );
                }
                ensure!(
                    archived_inventory_matches(
                        &previous["recovery"],
                        &proof["recovery"],
                        &d.generation
                    ),
                    "Archived legacy receipts changed"
                );
            }
            Err(e) => return Err(e.into()),
        }
        let final_disk = read_json(&state.join("recovery.json"), 32 * 1024 * 1024)?;
        for key in ["commands", "answers", "runs", "pending"] {
            ensure!(
                final_disk[key] == disk[key],
                "Recovery changed before Host retirement"
            );
        }
        validate(
            &d,
            &client.health().await?,
            &final_disk,
            &client.recovery().await?,
            &loaded["result"],
        )?;
        ensure!(
            descriptor_same(&d, &pocket_runtime_ipc::load_descriptor(descriptor, home)?),
            "Host owner changed before retirement"
        );
        // The legacy reconciliation fence prevents fresh non-read dispatch.
        // Current-generation incomplete receipts and all pending input were
        // rejected above. Only the authenticated Pocket Host is terminated.
        host.terminate()?;
        tokio::time::timeout(Duration::from_secs(15), async {
            while !host.ended()? || !native.ended()? {
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            Ok::<_, anyhow::Error>(())
        })
        .await
        .context("Native has not exited naturally; do not launch a replacement")??;
    }
    Ok(
        json!({"ready":true,"retired":retire,"runtimeId":d.runtime_id,"generation":d.generation,
        "hostPid":d.pid,"nativePid":d.native_pid,"unknownPreserved":recovery["unconfirmedWrites"],"loadedThreads":0}),
    )
}

#[cfg(windows)]
struct ProcessGuard {
    handle: std::os::windows::io::OwnedHandle,
    identity: NativeProcess,
}
#[cfg(windows)]
impl ProcessGuard {
    fn open(expected: &NativeProcess, terminate: bool) -> Result<Self> {
        use std::os::windows::io::FromRawHandle;
        let h =
            unsafe { win::OpenProcess(0x1000 | 0x100000 | u32::from(terminate), 0, expected.pid) };
        ensure!(!h.is_null(), "Cannot guard original process");
        let guard = Self {
            handle: unsafe { std::os::windows::io::OwnedHandle::from_raw_handle(h) },
            identity: expected.clone(),
        };
        guard.verify()?;
        Ok(guard)
    }
    fn verify(&self) -> Result<()> {
        use std::os::windows::io::AsRawHandle;
        let mut created = 0u64;
        let mut exit = 0u64;
        let mut kernel = 0u64;
        let mut user = 0u64;
        ensure!(
            unsafe {
                win::GetProcessTimes(
                    self.handle.as_raw_handle(),
                    &mut created,
                    &mut exit,
                    &mut kernel,
                    &mut user,
                )
            } != 0
                && created == self.identity.created_at
                && created != 0
                && !self.ended()?,
            "Process creation identity changed"
        );
        Ok(())
    }
    fn binary(&self) -> Result<std::path::PathBuf> {
        use std::os::windows::{ffi::OsStringExt, io::AsRawHandle};
        self.verify()?;
        let mut buf = vec![0u16; 32768];
        let mut len = buf.len() as u32;
        ensure!(
            unsafe {
                win::QueryFullProcessImageNameW(
                    self.handle.as_raw_handle(),
                    0,
                    buf.as_mut_ptr(),
                    &mut len,
                )
            } != 0,
            "Process image unavailable"
        );
        Ok(std::ffi::OsString::from_wide(&buf[..len as usize]).into())
    }
    fn ended(&self) -> Result<bool> {
        use std::os::windows::io::AsRawHandle;
        match unsafe { win::WaitForSingleObject(self.handle.as_raw_handle(), 0) } {
            0 => Ok(true),
            258 => Ok(false),
            _ => anyhow::bail!("Process lifetime unavailable"),
        }
    }
    fn terminate(&self) -> Result<()> {
        use std::os::windows::io::AsRawHandle;
        self.verify()?;
        ensure!(
            unsafe { win::TerminateProcess(self.handle.as_raw_handle(), 0) } != 0,
            "Legacy Host retirement failed"
        );
        Ok(())
    }
}
#[cfg(windows)]
mod win {
    use std::ffi::c_void;
    #[link(name = "kernel32")]
    unsafe extern "system" {
        pub fn OpenProcess(access: u32, inherit: i32, pid: u32) -> *mut c_void;
        pub fn GetProcessTimes(
            h: *mut c_void,
            created: *mut u64,
            exit: *mut u64,
            kernel: *mut u64,
            user: *mut u64,
        ) -> i32;
        pub fn QueryFullProcessImageNameW(
            h: *mut c_void,
            flags: u32,
            buffer: *mut u16,
            len: *mut u32,
        ) -> i32;
        pub fn WaitForSingleObject(h: *mut c_void, millis: u32) -> u32;
        pub fn TerminateProcess(h: *mut c_void, code: u32) -> i32;
    }
}

#[cfg(test)]
mod tests;
