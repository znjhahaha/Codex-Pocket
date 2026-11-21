//! UI replacement is graceful and separately scoped from task execution.
use anyhow::{ensure, Context, Result};
use std::{io::{Read, Write}, path::{Path, PathBuf}, process::{Child, Stdio}, time::{Duration, Instant}};
use crate::{launcher, supervisor::maintenance::{self, ProcessIdentity, Presence}};

pub fn selected(root: &Path, state: &Path, bin: &Path) -> Result<PathBuf> {
    match crate::local_update::dashboard_binary(root, state, bin)? {
        Some(candidate) => Ok(candidate),
        None => launcher::verified_exe(bin, "pocket-desktop.exe"),
    }
}

fn replacement(root: &Path, state: &Path, current: &Path) -> Result<Option<PathBuf>> {
    let (bin, installed) = launcher::resolve_release(root)?;
    ensure!(installed == state, "UI state changed");
    if crate::local_update::is_staged_dashboard(state, current, &bin) { return Ok(None); }
    if let Some(next) = crate::local_update::dashboard_binary(root, state, &bin)? {
        return Ok((next != current).then_some(next));
    }
    if current == bin.join("pocket-desktop.exe") { return Ok(None); }
    Ok(Some(launcher::verified_exe(&bin, "pocket-desktop.exe")?))
}

#[cfg(windows)]
mod windows_handoff;

/// An ordinary open focuses the selected version. A setup hook uses `existing_only`
/// so it also migrates a hidden tray window without creating one on a silent install.
pub fn open(root: &Path, state: &Path, existing_only: bool) -> Result<()> {
    let (bin, installed) = launcher::resolve_release(root)?;
    ensure!(installed == state, "UI state changed");
    let target = selected(root, state, &bin);
    let (exe, staging_error) = match target {
        Ok(exe) => (exe, None),
        Err(e) => (launcher::verified_exe(&bin, "pocket-desktop.exe")?, Some(e)),
    };
    #[cfg(windows)] windows_handoff::open(root, state, &exe, existing_only)?;
    #[cfg(not(windows))] if !existing_only { spawn(root, state, &exe, true)?; }
    if let Some(e) = staging_error { return Err(e); }
    Ok(())
}

/// A user-requested update also restarts a candidate dashboard already showing
/// the new version, after the backend (or rollback) has been authenticated.
pub(crate) fn restart_after_update(root: &Path, state: &Path) -> Result<()> {
    let (bin,installed)=launcher::resolve_release(root)?;
    ensure!(installed==state,"UI state changed");
    let exe=launcher::verified_exe(&bin,"pocket-desktop.exe")?;
    #[cfg(windows)] windows_handoff::restart(root,state,&exe)?;
    #[cfg(not(windows))] spawn(root,state,&exe,true)?;
    Ok(())
}

fn spawn(root: &Path, state: &Path, exe: &Path, visible: bool) -> Result<Child> {
    let mut command = std::process::Command::new(exe);
    command.current_dir(root).arg("--state").arg(state);
    if !visible { command.arg("--background"); }
    crate::process::hidden(&mut command);
    Ok(command.spawn()?)
}

fn old_ui(root: &Path, pid: u32) -> Result<ProcessIdentity> {
    let owner = ProcessIdentity::capture(pid)?;
    let parent = owner.executable.parent().context("UI directory missing")?;
    ensure!(parent.parent() == Some(root.join("bin").as_path()), "UI is outside installation");
    ensure!(parent.file_name().and_then(|p| p.to_str()) == Some(crate::installation::generation(parent)?.as_str()), "UI manifest changed");
    ensure!(launcher::verified_exe(parent, "pocket-desktop.exe")? == owner.executable, "UI identity mismatch");
    Ok(owner)
}

fn confirm_waiter(mut child: Child, timeout: Duration) -> Result<()> {
    let stdout = child.stdout.take().context("UI waiter pipe missing")?;
    let (send, receive) = std::sync::mpsc::sync_channel(1);
    std::thread::spawn(move || {
        let mut ready = [0; 6];
        let result = stdout.take(6).read_exact(&mut ready).map(|_| ready == *b"ready\n");
        let _ = send.send(result);
    });
    if matches!(receive.recv_timeout(timeout), Ok(Ok(true))) { return Ok(()) }
    // Only our newly spawned waiter is stopped. The old UI remains usable.
    let _ = child.kill();
    let _ = child.wait();
    anyhow::bail!("UI restart waiter did not acknowledge")
}

/// The UI may exit only after its waiter has captured the exact old identity.
pub fn request(root: &Path, state: &Path, visible: bool) -> Result<bool> {
    let Some(next) = replacement(root, state, &std::env::current_exe()?)? else { return Ok(false) };
    let old = old_ui(root, std::process::id())?;
    let mut command = std::process::Command::new(launcher::verified_exe(next.parent().unwrap(), "pocket-upgrade.exe")?);
    command.arg("--ui-restart").arg(root).arg(state).arg(old.pid.to_string()).arg(old.created_at.to_string())
        .arg(if visible { "visible" } else { "background" })
        .stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null());
    #[cfg(windows)] {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000 | 0x01000000);
    }
    confirm_waiter(command.spawn()?, Duration::from_secs(10))?;
    Ok(true)
}

fn wait_for_exit(owner: &ProcessIdentity, timeout: Duration, probe: impl Fn(&ProcessIdentity) -> Presence) -> Result<()> {
    let deadline = Instant::now() + timeout;
    loop {
        match probe(owner) {
            Presence::Gone => return Ok(()),
            Presence::Unknown => anyhow::bail!("UI exit could not be verified"),
            Presence::Alive => ensure!(Instant::now() < deadline, "UI did not exit"),
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

pub fn resume(root: &Path, state: &Path, pid: u32, created: u64, visible: bool) -> Result<()> {
    let owner = old_ui(root, pid)?;
    ensure!(owner.created_at == created, "UI PID was reused");
    ensure!(replacement(root, state, &owner.executable)?.is_some(), "UI no longer needs replacement");
    // Parent blocks on this acknowledgement. Capture cannot race parent exit.
    std::io::stdout().write_all(b"ready\n")?;
    std::io::stdout().flush()?;
    wait_for_exit(&owner, Duration::from_secs(30), maintenance::presence)?;
    // Open diagnostics even if the backend goes offline after acknowledgement.
    // A rollback may have changed the generation while the old UI exited.
    let (bin, installed) = launcher::resolve_release(root)?;
    ensure!(installed == state, "UI state changed after exit");
    spawn(root, state, &selected(root, state, &bin)?, visible)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage;
    use serde_json::json;
    use sha2::{Digest, Sha256};

    #[test]
    fn current_ui_poll_needs_neither_backend_nor_executable_hash() {
        let dir = crate::test_support::tempdir();
        let root = dir.path(); let state = root.join("state");
        let manifest = b"{\"version\":\"1.0.0\",\"files\":[]}";
        let generation = format!("{:x}", Sha256::digest(manifest));
        let bin = root.join("bin").join(&generation);
        storage::private_dir(&bin).unwrap(); storage::private_dir(&state).unwrap();
        std::fs::write(bin.join("windows-manifest.json"), manifest).unwrap();
        storage::atomic(&root.join("desktop-install.json"), &json!({"version":3,"installRoot":root,"bridge":root.join("pocket-launcher.exe"),"stateDirectory":state,"generation":generation,"releaseHash":generation})).unwrap();
        assert_eq!(replacement(root, &state, &bin.join("pocket-desktop.exe")).unwrap(), None);
    }

    #[test]
    fn restart_requires_verified_exit_and_retains_captured_identity() {
        let owner = ProcessIdentity { pid: 17, created_at: 31, executable: PathBuf::from("old-ui") };
        assert!(wait_for_exit(&owner, Duration::ZERO, |_| Presence::Alive).is_err());
        assert!(wait_for_exit(&owner, Duration::ZERO, |_| Presence::Unknown).is_err());
        wait_for_exit(&owner, Duration::ZERO, |expected| { assert_eq!(expected, &owner); Presence::Gone }).unwrap();
    }

    #[cfg(windows)]
    #[test]
    fn actual_waiter_must_acknowledge_before_old_ui_can_exit() {
        fn spawn(script: &str) -> Child {
            let mut command = std::process::Command::new(storage::powershell());
            command.args(["-NoProfile", "-NonInteractive", "-Command", script])
                .stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null());
            crate::process::hidden(&mut command); command.spawn().unwrap()
        }
        confirm_waiter(spawn("[Console]::Write([string]::Concat('ready',[char]10))"), Duration::from_secs(10)).unwrap();
        assert!(confirm_waiter(spawn("exit 1"), Duration::from_secs(10)).is_err());
        assert!(confirm_waiter(spawn("Start-Sleep -Seconds 30"), Duration::from_millis(50)).is_err());
    }
}
