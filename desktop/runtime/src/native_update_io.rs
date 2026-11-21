use super::*;

fn helper_recovery_command(j:&Journal,h:&upgrade::UpgradeStatus)->Result<std::process::Command> {
    ensure!(!released(j)?,"native_admission_already_released");
    ensure!(h.install_root==j.install_root&&h.state_directory==j.state_directory
        &&h.from_generation==j.to_generation&&h.to_generation==j.from_generation
        &&upgrade::local::canonical_uuid(&h.operation_id),"rollback_handoff_changed");
    let (bin,state)=crate::launcher::resolve_release(&j.install_root)?;
    ensure!(state==j.state_directory&&crate::installation::generation(&bin)?==h.to_generation,"rollback_installation_changed");
    upgrade::local::verify_generation_inventory(&bin)?;
    let mut c=std::process::Command::new(crate::launcher::verified_exe(&bin,"pocket-upgrade.exe")?);
    c.arg("--install-root").arg(&j.install_root).arg("--state").arg(&j.state_directory)
        .arg("--operation-id").arg(&h.operation_id)
        .env_remove("POCKET_UPDATE_OPERATION").env_remove("POCKET_UPDATE_RELEASE_FILE")
        .stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null());
    crate::process::hidden(&mut c);
    #[cfg(windows)] {use std::os::windows::process::CommandExt;c.creation_flags(0x08000000|0x01000000);}
    Ok(c)
}
pub(super) fn dispatch_helper_recovery(j:&Journal,h:&upgrade::UpgradeStatus)->Result<()> {
    // A live coordinator retains both upgrade locks, so a duplicate recovery
    // process cannot take ownership or replay its release. A dead coordinator
    // is recovered by the same target image and existing durable protocol.
    helper_recovery_command(j,h)?.spawn()?;Ok(())
}
use sha2::{Digest, Sha256};
use std::{
    io::{BufRead, BufReader, Read, Write},
    net::TcpStream,
    process::Command,
};
#[path = "native_admission.rs"]
pub(super) mod admission;

pub(super) fn image_ok(image: &Image) -> Result<()> {
    ensure!(
        ProcessIdentity::capture(image.identity.pid)? == image.identity,
        "native_identity_changed"
    );
    storage::unredirected(&image.identity.executable)?;
    let mut f = storage::open_physical(&image.identity.executable)?;
    let mut hash = Sha256::new();
    let mut bytes = [0; 65536];
    loop {
        let n = f.read(&mut bytes)?;
        if n == 0 {
            break;
        }
        hash.update(&bytes[..n]);
    }
    ensure!(
        format!("{:x}", hash.finalize()) == image.sha256,
        "native_image_changed"
    );
    Ok(())
}
pub(super) fn helper(root: &Path, state: &Path, generation: &str) -> Result<Value> {
    let h = crate::process::local_control(state, "status", json!({}))?;
    let r = &h["helperRuntime"];
    let p = ProcessIdentity::capture(
        r["pid"]
            .as_u64()
            .and_then(|n| u32::try_from(n).ok())
            .context("helper_pid_missing")?,
    )?;
    ensure!(
        r["generation"] == generation
            && r["controlProtocol"] == 1
            && r["processIdentity"]["createdAt"] == p.created_at
            && p.executable == root.join("bin").join(generation).join("pocket-agent.exe"),
        "helper_identity_changed"
    );
    crate::launcher::verified_exe(p.executable.parent().unwrap(), "pocket-agent.exe")?;
    ensure!(
        h["computerId"] == storage::read::<Value>(&state.join("config.json"))?["computerId"],
        "helper_computer_changed"
    );
    Ok(h)
}
fn script_command(
    root: &Path,
    state: &Path,
    tools_generation: &str,
    mode: &str,
) -> Result<Command> {
    let (_, s) = crate::launcher::resolve_release(root)?;
    ensure!(s == state, "state_changed");
    let bin = root.join("bin").join(tools_generation);
    ensure!(
        crate::installation::generation(&bin)? == tools_generation,
        "tool_generation_changed"
    );
    let path = crate::launcher::verified_exe(&bin, "windows-install.ps1")?;
    crate::launcher::verified_exe(&bin, "windows-common.ps1")?;
    let mut c = Command::new(storage::powershell());
    c.args([
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
    ])
    .arg(path)
    .arg("-NativeUpdateMode")
    .arg(mode)
    .arg("-InstallRoot")
    .arg(root)
    .arg("-StateDirectory")
    .arg(state);
    c.env_remove("POCKET_UPDATE_OPERATION")
        .env_remove("POCKET_UPDATE_RELEASE_FILE");
    Ok(c)
}
#[cfg(test)]
pub(super) fn test_launch_command(j:&Journal)->Result<Command>{script_command(&j.install_root,&j.state_directory,&j.to_generation,"Launch")}
pub(super) fn launch(j: &Journal) -> Result<()> {
    admission::allow_start(j)?;
    let mut c = script_command(
        &j.install_root,
        &j.state_directory,
        &j.to_generation,
        "Launch",
    )?;
    startup_environment(&mut c, j)?;
    // Descendants may inherit the launcher's standard handles. Do not wait on
    // a captured stdout pipe that could remain open for the desktop's lifetime.
    c.env_remove("PSModulePath");
    crate::process::hidden(&mut c);
    c.stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null());
    let mut child = c.spawn().context("native_launch_failed")?;
    let deadline = Instant::now() + Duration::from_secs(45);
    loop {
        if let Some(status) = child.try_wait()? {
            ensure!(status.success(), "native_launch_failed");
            return Ok(());
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            anyhow::bail!("native_launch_timeout")
        }
        std::thread::sleep(Duration::from_millis(40));
    }
}
pub(super) fn startup_environment(c: &mut Command, j: &Journal) -> Result<()> {
    ensure!(
        upgrade::local::canonical_uuid(&j.operation_id),
        "Invalid startup operation"
    );
    c.env("POCKET_UPDATE_OPERATION", &j.operation_id);
    c.env("POCKET_UPDATE_RELEASE_FILE", release_path(j)?);
    c.env("POCKET_INSTALL_ROOT", &j.install_root);
    storage::unredirected(&release_path(j)?)?;
    ensure!(
        !released(j)?,
        "Cannot launch an unverified native with released admission"
    );
    Ok(())
}
pub(super) fn script(
    root: &Path,
    state: &Path,
    tools_generation: &str,
    mode: &str,
) -> Result<Value> {
    serde_json::from_slice(&crate::process::run_bounded(
        script_command(root, state, tools_generation, mode)?,
        45,
    )?)
    .context("native_inventory_unavailable")
}
pub(super) fn inventory(root: &Path, state: &Path, tools_generation: &str) -> Result<Option<Plan>> {
    let v = script(root, state, tools_generation, "Inventory")?;
    let plan=inventory_projection(root,&v)?;
    if let Some(p)=&plan { for i in images(p) { image_ok(i)?; } }
    Ok(plan)
}
fn inventory_projection(root: &Path, v: &Value) -> Result<Option<Plan>> {
    let rows = v["group"].as_array().context("native_group_missing")?;
    ensure!(rows.len() <= 128, "native_tree_limit");
    if rows.is_empty() {
        return Ok(None);
    }
    let select = |name: &str| -> Result<Image> {
        let matches: Vec<_> = rows.iter().filter(|r| r["name"] == name).collect();
        ensure!(matches.len() == 1, "native_tree_ambiguous");
        image(matches[0])
    };
    let desktop = select("associated-desktop")?;
    verify_ancestry(rows, &desktop)?;
    let mut bridges:Vec<_>=rows.iter().filter(|r| r["name"]=="pocket-bridge.exe").collect();
    bridges.sort_by_key(|r|r["pid"].as_u64());
    ensure!(!bridges.is_empty()&&bridges.len()<=16,"native_tree_limit");
    let natives:Vec<_>=rows.iter().filter(|r|r["name"]=="associated-native").collect();
    ensure!(natives.len()==bridges.len(),"native_connection_incomplete");
    let descriptors = v["descriptors"]
        .as_array()
        .context("native_descriptor_missing")?;
    ensure!(descriptors.len() == bridges.len(), "native_descriptor_ambiguous");
    let mut descriptor_values=Vec::new();
    for raw in descriptors {
    let descriptor = PathBuf::from(raw.as_str().context("native_descriptor_missing")?);
    ensure!(
        descriptor.parent() == Some(root.join("runtimes").as_path()),
        "native_descriptor_scope"
    );
    let d: Value = storage::read(&descriptor)?;
    descriptor_values.push((descriptor,d));
    }
    let mut connection_rows=Vec::new();
    for row in bridges {
    let bridge=image(row)?;
    let matches:Vec<_>=natives.iter().filter(|r|r["parentPid"]==bridge.identity.pid).collect();
    ensure!(matches.len()==1,"native_connection_ambiguous");
    let native=image(matches[0])?;
    let matches:Vec<_>=descriptor_values.iter().filter(|(_,d)|d["pid"]==bridge.identity.pid).collect();
    ensure!(matches.len()==1,"native_descriptor_ambiguous");
    let (descriptor,d)=matches[0];
    ensure!(
        d["pid"] == bridge.identity.pid
            && d["protocol"] == 1
            && d["source"] == "desktop"
            && d["binary"].as_str().map(Path::new) == Some(native.identity.executable.as_path()),
        "native_descriptor_changed"
    );
    connection_rows.push(Connection{bridge,native,descriptor:descriptor.clone(),runtime_id:d["runtimeId"].as_str().context("runtime_id_missing")?.into()});
    }
    let mut children = Vec::new();
    for r in rows {
        if r["name"] == "desktop-child" || r["name"] == "pocket-launcher.exe" || r["name"] == "console-host" {
            children.push(image(r)?);
        }
    }
    children.sort_by_key(|i| i.identity.pid);
    let primary=connection_rows.remove(0);
    let ancestry=rows.iter().filter(|r|r["pid"]!=desktop.identity.pid).map(|r|->Result<ParentLink>{Ok(ParentLink{child:image(r)?.identity,parent:serde_json::from_value(r["parentIdentity"].clone())?})}).collect::<Result<Vec<_>>>()?;
    let plan = Plan {
        additional: connection_rows,
        ancestry,
        desktop,
        bridge:primary.bridge,
        native:primary.native,
        children,
        descriptor:primary.descriptor,
        runtime_id:primary.runtime_id,
    };
    Ok(Some(plan))
}
fn image(v: &Value) -> Result<Image> {
    Ok(Image {
        identity: ProcessIdentity {
            pid: v["pid"]
                .as_u64()
                .and_then(|n| u32::try_from(n).ok())
                .context("pid_missing")?,
            created_at: v["createdAt"].as_u64().context("creation_missing")?,
            executable: v["executable"].as_str().context("image_missing")?.into(),
        },
        sha256: v["sha256"].as_str().context("digest_missing")?.into(),
    })
}
pub(super) fn images(p: &Plan) -> Vec<&Image> {
    let mut out = vec![&p.desktop];
    out.extend(&p.children);
    out.extend([&p.native, &p.bridge]);
    for c in &p.additional { out.extend([&c.native,&c.bridge]); }
    out
}

fn verify_ancestry(rows: &[Value], desktop: &Image) -> Result<()> {
    let mut members = std::collections::HashMap::new();
    for row in rows {
        ensure!(
            [
                "associated-desktop",
                "pocket-bridge.exe",
                "associated-native",
                "desktop-child",
                "pocket-launcher.exe",
                "console-host"
            ]
            .iter()
            .any(|name| row["name"] == *name),
            "native_unfenced_descendant"
        );
        let member = image(row)?;
        ensure!(
            members.insert(member.identity.pid, member).is_none(),
            "native_tree_ambiguous"
        );
    }
    ensure!(
        members.get(&desktop.identity.pid) == Some(desktop),
        "native_root_changed"
    );
    for row in rows {
        let child = image(row)?;
        if child == *desktop {
            continue;
        }
        let parent: ProcessIdentity = serde_json::from_value(row["parentIdentity"].clone())
            .context("native_parent_identity_missing")?;
        ensure!(
            row["parentPid"].as_u64() == Some(u64::from(parent.pid))
                && members
                    .get(&parent.pid)
                    .is_some_and(|p| p.identity == parent)
                && child.identity.created_at > parent.created_at,
            "native_parent_identity_unverified"
        );
        // Every non-root member has a strictly older parent in this finite set,
        // so all chains terminate at the selected root, never a PID-only cycle.
    }
    Ok(())
}

#[cfg(test)]
#[path = "native_update_io_tests.rs"]
mod tests;

pub(super) fn rpc(plan: &Plan, method: &str, params: Value) -> Result<Value> {
    ensure!(
        [
            "pocket/status",
            "pocket/managementStatus",
            "pocket/maintenance/quiesce",
            "pocket/maintenance/resume",
            "thread/loaded/list",
            "thread/read"
        ]
        .contains(&method),
        "native_method_rejected"
    );
    ensure!(
        ProcessIdentity::capture(plan.bridge.identity.pid)? == plan.bridge.identity,
        "native_bridge_changed"
    );
    let d: Value = storage::read(&plan.descriptor)?;
    ensure!(
        d["protocol"] == 1
            && d["pid"] == plan.bridge.identity.pid
            && d["runtimeId"] == plan.runtime_id,
        "native_descriptor_changed"
    );
    let port = d["port"]
        .as_u64()
        .filter(|n| *n > 0 && *n <= 65535)
        .context("native_port_invalid")? as u16;
    ensure!(
        listener_owner(port)? == plan.bridge.identity.pid,
        "native_listener_changed"
    );
    let token = d["token"]
        .as_str()
        .filter(|s| !s.is_empty() && s.len() <= 256)
        .context("native_auth_missing")?;
    let mut stream =
        TcpStream::connect_timeout(&([127, 0, 0, 1], port).into(), Duration::from_secs(2))?;
    stream.set_read_timeout(Some(Duration::from_secs(2)))?;
    stream.set_write_timeout(Some(Duration::from_secs(2)))?;
    writeln!(stream, "{}", json!({"token":token}))?;
    let id = uuid::Uuid::new_v4().to_string();
    writeln!(
        stream,
        "{}",
        json!({"id":id,"method":method,"params":params})
    )?;
    let mut reader = BufReader::new(stream);
    let deadline = Instant::now() + Duration::from_secs(3);
    for _ in 0..32 {
        ensure!(Instant::now() < deadline, "native_rpc_timeout");
        let mut line = Vec::new();
        Read::by_ref(&mut reader)
            .take(1024 * 1024 + 1)
            .read_until(b'\n', &mut line)?;
        ensure!(
            !line.is_empty() && line.len() <= 1024 * 1024 && line.last() == Some(&b'\n'),
            "native_frame_invalid"
        );
        let v: Value = serde_json::from_slice(&line)?;
        if v["id"] != id {
            continue;
        }
        ensure!(v.get("error").is_none(), "native_rpc_rejected");
        ensure!(
            ProcessIdentity::capture(plan.bridge.identity.pid)? == plan.bridge.identity
                && listener_owner(port)? == plan.bridge.identity.pid,
            "native_rpc_owner_changed"
        );
        return v.get("result").cloned().context("native_result_missing");
    }
    anyhow::bail!("native_frame_limit")
}
pub(super) fn read_ready(plan: &Plan) -> Result<Vec<String>> {
    let mut ids=Vec::new();
    for connection in connections(plan) { ids.extend(read_ready_one(&connection)?); }
    ids.sort();ids.dedup();Ok(ids)
}
fn read_ready_one(plan: &Plan) -> Result<Vec<String>> {
    let s = rpc(plan, "pocket/status", json!({}))?;
    ensure!(
        s["runtimeId"] == plan.runtime_id
            && s["initialized"] == true
            && s["schemaState"] == "ready"
            && s["nativeProcess"]["pid"] == plan.native.identity.pid
            && s["nativeProcess"]["createdAt"] == plan.native.identity.created_at,
        "native_readiness_unverified"
    );
    let list = rpc(plan, "thread/loaded/list", json!({}))?;
    ensure!(list["nextCursor"].is_null(), "native_inventory_paginated");
    let ids: Vec<String> = serde_json::from_value(list["data"].clone())?;
    ensure!(ids.len() <= 128, "native_inventory_limit");
    for id in &ids {
        let read = rpc(
            plan,
            "thread/read",
            json!({"threadId":id,"includeTurns":false}),
        )?;
        ensure!(read["thread"]["id"] == *id, "native_read_identity_changed");
    }
    ensure!(
        rpc(plan, "thread/loaded/list", json!({}))? == list,
        "native_inventory_changed"
    );
    Ok(ids)
}

#[cfg(windows)]
fn listener_owner(port: u16) -> Result<u32> {
    #[link(name = "iphlpapi")]
    unsafe extern "system" {
        fn GetExtendedTcpTable(
            table: *mut std::ffi::c_void,
            size: *mut u32,
            order: i32,
            family: u32,
            class: u32,
            reserved: u32,
        ) -> u32;
    }
    unsafe {
        let mut size = 0;
        let first = GetExtendedTcpTable(std::ptr::null_mut(), &mut size, 0, 2, 3, 0);
        ensure!(first == 0 || first == 122, "listener_probe_failed");
        ensure!(size <= 16 * 1024 * 1024, "listener_table_limit");
        let mut table = vec![0u32; (size as usize).div_ceil(4)];
        ensure!(
            GetExtendedTcpTable(table.as_mut_ptr().cast(), &mut size, 0, 2, 3, 0) == 0,
            "listener_probe_failed"
        );
        let count = table[0] as usize;
        ensure!(1 + count * 6 <= table.len(), "listener_table_invalid");
        let rows: Vec<_> = table[1..1 + count * 6]
            .chunks(6)
            .filter(|r| r[0] == 2 && r[1] == 0x0100007f && u16::from_be(r[2] as u16) == port)
            .collect();
        ensure!(rows.len() == 1, "listener_owner_ambiguous");
        Ok(rows[0][5])
    }
}
#[cfg(not(windows))]
fn listener_owner(_: u16) -> Result<u32> {
    anyhow::bail!("Windows required")
}

#[cfg(windows)]
pub(super) struct Batch(Vec<(windows_sys::Win32::Foundation::HANDLE,bool)>, Option<pocket_core::admission::UpdateGuard>);
#[cfg(windows)]
fn open_admission(plan: &Plan) -> Result<pocket_core::admission::UpdateGuard> {
    let root=plan.descriptor.parent().and_then(Path::parent).context("native_descriptor_scope")?;
    let (_,state)=crate::launcher::resolve_release(root)?;
    let j=read_status(&state)?.context("native_admission_journal_missing")?;
    admission::close(&j,plan)
}
#[cfg(windows)]
fn record_stop_deferral(plan: &Plan, reason:&str) {
    let _ = (|| -> Result<()> {
        let root = plan
            .descriptor
            .parent()
            .and_then(Path::parent)
            .context("native_descriptor_scope")?;
        ensure!(
            plan.descriptor.parent() == Some(root.join("runtimes").as_path()),
            "native_descriptor_scope"
        );
        let (_, state) = crate::launcher::resolve_release(root)?;
        let journal: Option<Value> = storage::read(&path(&state)).ok();
        let operation = journal
            .as_ref()
            .filter(|v| {
                v["installRoot"].as_str().map(Path::new) == Some(root)
                    && v["stateDirectory"].as_str().map(Path::new) == Some(state.as_path())
            })
            .and_then(|v| v["operationId"].as_str())
            .filter(|op| upgrade::local::canonical_uuid(op));
        storage::atomic(
            &storage::control(&state).join("native-stop-deferred.json"),
            &json!({
                "schema":1, "reason":reason, "phase":"before_process_handles",
                "observedAt":storage::now(), "operationId":operation,
                "installRoot":root, "desktop":plan.desktop.identity,
                "runtimeId":plan.runtime_id
            }),
        )
    })();
}
#[cfg(windows)]
impl Batch {
    pub fn open(plan: &Plan) -> Result<Self> {
        let guard=match open_admission(plan) {
            Ok(guard)=>guard,
            Err(error)=>{record_stop_deferral(plan,&error.to_string());return Err(error);}
        };
        let mut batch=Self::open_exact(plan)?;
        batch.1=Some(guard);
        Ok(batch)
    }
    // Cleanup for processes explicitly spawned by the opt-in test harness.
    // This bypass is absent from production binaries and never accepts a
    // production install or a process image outside the marked fixture root.
    #[cfg(test)]
    pub(super) fn open_fixture(plan: &Plan) -> Result<Self> {
        let root = plan
            .descriptor
            .parent()
            .and_then(Path::parent)
            .context("fixture root missing")?;
        ensure!(
            std::fs::read(root.join(".fixture-owner"))? == b"native-consumer-test",
            "fixture owner missing"
        );
        for i in images(plan) {
            let system=storage::powershell().parent().and_then(Path::parent).and_then(Path::parent).map(|p|p.join("conhost.exe"));
            let console=system.as_ref().is_some_and(|p|p.as_os_str().to_string_lossy().eq_ignore_ascii_case(&i.identity.executable.as_os_str().to_string_lossy()))
                &&plan.children.contains(&i)
                &&plan.ancestry.iter().any(|edge|edge.child==i.identity&&edge.parent.executable.starts_with(root)
                    &&images(plan).iter().any(|p|p.identity==edge.parent));
            ensure!(
                i.identity.executable.starts_with(root)||console,
                "fixture image outside root: {} (system {:?}, bound console {})",i.identity.executable.display(),system,console
            );
        }
        Self::open_exact(plan)
    }
    fn open_exact(plan: &Plan) -> Result<Self> {
        use windows_sys::Win32::System::Threading::*;
        let mut batch = Self(vec![],None);
        for i in images(plan) {
            image_ok(i)?;
            let system=storage::powershell().parent().and_then(Path::parent).and_then(Path::parent).map(|p|p.join("conhost.exe"));
            let console=plan.children.contains(&i)&&system.as_ref().is_some_and(|p|p.as_os_str().to_string_lossy().eq_ignore_ascii_case(&i.identity.executable.as_os_str().to_string_lossy()));
            let h = unsafe {
                OpenProcess(
                    (if console {0}else{PROCESS_TERMINATE}) | PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                    0,
                    i.identity.pid,
                )
            };
            ensure!(!h.is_null(), "native_handle_denied");
            batch.0.push((h,console));
            let mut c = unsafe { std::mem::zeroed() };
            let mut e = c;
            let mut k = c;
            let mut u = c;
            ensure!(
                unsafe { GetProcessTimes(h, &mut c, &mut e, &mut k, &mut u) } != 0
                    && (u64::from(c.dwHighDateTime) << 32 | u64::from(c.dwLowDateTime))
                        == i.identity.created_at,
                "native_handle_reused"
            );
            ensure!(
                ProcessIdentity::capture(i.identity.pid)? == i.identity,
                "native_identity_changed"
            );
        }
        Ok(batch)
    }
    pub fn stop(&self, deadline: Instant) -> Result<()> {
        use windows_sys::Win32::{Foundation::*, System::Threading::*};
        for (h,console) in &self.0 {
            ensure!(Instant::now() < deadline, "native_stop_deadline");
            // Console hosts are OS-owned support processes. They exit naturally
            // after the proved idle owners; still require exact-handle exit.
            if *console {continue}
            unsafe {
                if WaitForSingleObject(*h, 0) == WAIT_OBJECT_0 {
                    continue;
                }
                ensure!(TerminateProcess(*h, 0) != 0 || WaitForSingleObject(*h,500)==WAIT_OBJECT_0, "native_stop_failed");
            }
        }
        for (h,_) in &self.0 {
            ensure!(Instant::now() < deadline, "native_stop_deadline");
            ensure!(
                unsafe { WaitForSingleObject(*h, 500) } == WAIT_OBJECT_0,
                "native_exit_unverified"
            );
        }
        Ok(())
    }
}
#[cfg(windows)]
impl Drop for Batch {
    fn drop(&mut self) {
        for (h,_) in &self.0 {
            unsafe {
                windows_sys::Win32::Foundation::CloseHandle(*h);
            }
        }
    }
}
#[cfg(not(windows))]
pub(super) struct Batch;
#[cfg(not(windows))]
impl Batch {
    pub fn open(_: &Plan) -> Result<Self> {
        anyhow::bail!("Windows required")
    }
    pub fn stop(&self, _: Instant) -> Result<()> {
        anyhow::bail!("Windows required")
    }
}
