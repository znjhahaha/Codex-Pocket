#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use anyhow::{ensure, Context, Result};
use pocket_desktop_runtime::{launcher, local_update, process, storage, updates};
use serde_json::Value;
use std::{cell::Cell, path::Path, process::Command};

fn main() {
    #[cfg(windows)]
    if std::env::args_os().nth(1).is_some_and(|arg| arg == "--finish-setup-stage") {
        // The worker lives outside Velopack's manager directory. Setup closes
        // every process under that directory again after the install hook.
        let source=std::env::args_os().nth(2).map(std::path::PathBuf::from);
        let result = storage::local_app_data().and_then(|dir| {
            let root=dir.join("CodexPocket");
            finish_deferred_stage(&root,source.as_deref())?;
            pocket_desktop_runtime::update_task::setup(&std::env::current_exe()?,&root,env!("CARGO_PKG_VERSION"),source.as_deref(),true)
        });
        if let Err(error)=&result {eprintln!("Pocket deferred installation: {error:#}");}
        std::process::exit(if result.is_ok() { 0 } else { 1 });
    }
    let explicitly_install = std::env::args_os().nth(1).is_some_and(|arg| arg == "--install-payload");
    let first_run = Cell::new(false);
    let app = velopack::VelopackApp::build().set_auto_apply_on_startup(false);
    #[cfg(windows)]
    let app = app.on_after_install_fast_callback(|_| stage_hook())
        .on_after_update_fast_callback(|_| stage_hook());
    app.on_first_run(|_| first_run.set(true))
        .run();
    if let Err(error) = run(explicitly_install || first_run.get()) {
        eprintln!("Pocket manager: {error:#}");
        show_failure();
        std::process::exit(1);
    }
}

#[cfg(windows)]
fn stage_hook() {
    if let Err(error) = stage_existing_from_setup() {
        eprintln!("Pocket setup staging failed: {error:#}");
        std::process::exit(1);
    }
}

fn show_failure() {
    #[cfg(windows)] {
        use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONERROR};
        let (title, text) = ("Codex Pocket · 操作未完成", "打开或安装 Pocket 时出现问题。请从开始菜单打开 Pocket 查看更新状态，或重新运行下载的安装包。");
        let wide = |s: &str| s.encode_utf16().chain(Some(0)).collect::<Vec<_>>();
        unsafe { MessageBoxW(std::ptr::null_mut(), wide(text).as_ptr(), wide(title).as_ptr(), MB_ICONERROR); }
    }
}

fn existing_entry(
    install: bool,
    open: impl FnOnce() -> Result<()>,
    stage: impl FnOnce() -> Result<bool>,
) -> Result<bool> {
    let staged = if install { stage() } else { Ok(false) };
    let opened = open();
    let staged = staged?;
    opened?;
    Ok(staged)
}

fn resume_explicit_update(status: Option<&updates::UpdateStatus>, dispatch: impl FnOnce() -> Result<()>) {
    if status.is_some_and(local_update::resumable_update) {
        let _ = dispatch();
    }
}

fn open_current(root: &Path) -> Result<()> {
    let (_, state) = launcher::resolve_release(root)?;
    // Fast Velopack hooks can run in a job that forbids independent child
    // processes. Retry the same transaction from this ordinary app launch.
    let status = updates::read_status(&state).ok();
    resume_explicit_update(status.as_ref(), || local_update::resume_from_manager(root, &state));
    pocket_desktop_runtime::ui_update::open(root, &state, false)
}

fn install_candidate(root: &Path, installed: bool) -> Result<bool> {
    let exe = std::env::current_exe()?;
    let manager = exe.parent().context("Missing manager directory")?;
    install_from_manager(root,installed,manager)
}
fn install_from_manager(root:&Path,installed:bool,manager:&Path)->Result<bool> {
    let source = manager.join("payload");
    let offer = manager.join("pocket-install-offer.json");
    storage::unredirected(&source)?;
    let trust: Value = storage::read(&source.join("windows-update-trust.json"))?;
    let manifest:Value=storage::read(&source.join("windows-manifest.json"))?;
    ensure!(manifest["version"]==env!("CARGO_PKG_VERSION"),"Setup payload changed before activation");
    let key = trust["publicKey"].as_str().context("Setup has no pinned update key")?;
    if installed {
        let (bin, state) = launcher::resolve_release(root)?;
        let version = |dir: &Path| -> Result<semver::Version> {
            let manifest: Value = storage::read(&dir.join("windows-manifest.json"))?;
            Ok(semver::Version::parse(manifest["version"].as_str().context("Missing release version")?)?)
        };
        if version(&source)? <= version(&bin)? { return Ok(false); }
        updates::stage_local(root, &state, &source, &offer, Some(key))?;
        let staged = root.join("bin").join(pocket_desktop_runtime::installation::generation(&source)?);
        let migration=migrate_logon(root, &staged);
        local_update::dispatch(root, &state)?;
        migration?;
        return Ok(true);
    }
    updates::verify_local_candidate(&source, &std::fs::read(offer)?, key, "0.0.0", storage::now())?;
    let script = launcher::verified_exe(&source, "windows-install.ps1")?;
    let mut command = Command::new(storage::powershell());
    command.args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File"])
        .arg(script).arg("-SourceDirectory").arg(&source).args(["-Initialize", "-Apply"]);
    process::hidden(&mut command);
    command.env_remove("PSModulePath");
    let code = command.status()?.code();
    // The verified installer returns 2 only AFTER helper identity and routes
    // pass, when optional Windows integration still needs attention.
    ensure!(matches!(code, Some(0 | 2)), "Initial Pocket activation failed: {code:?}");
    // Fresh installs already have this version of the stable launcher.
    Ok(false)
}

fn migrate_logon(root: &Path, source: &Path) -> Result<()> {
    launcher::verified_exe(source,"windows-common.ps1")?;
    let script=launcher::verified_exe(source,"windows-startup.ps1")?;
    let mut c=Command::new(storage::powershell());
    c.args(["-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File"]).arg(script)
        .args(["-Action","MigrateManager","-InstallRoot"]).arg(root);
    process::run_bounded(c,15).context("Cannot migrate Pocket login entry")?;
    Ok(())
}

// A Velopack fast hook has a 30 second deadline. A previous installer may be
// finishing a fence. Retain the exact new manager's request across hook exit.
#[cfg(windows)]
fn deferred_stage_path(root: &Path) -> std::path::PathBuf {
    root.join("control").join(format!("setup-stage-{}.json",env!("CARGO_PKG_VERSION")))
}
#[cfg(windows)]
fn finish_deferred_stage(root: &Path, manager:Option<&Path>) -> Result<()> {
    let path=deferred_stage_path(root);
    storage::unredirected(&path)?;
    if !path.try_exists()? {return Ok(())}
    // Both the hook's child and the normal manager launch may arrive here.
    let lock=storage::Lock::acquire(&path.with_extension("lock"));
    let _guard=match lock {
        Ok(guard)=>guard,
        Err(e) if local_update::lock_contended(&e)=>return Ok(()),
        Err(e)=>return Err(e),
    };
    if !path.try_exists()? {return Ok(())}
    let staged=if let Some(manager)=manager {install_from_manager(root,true,manager)?} else {install_candidate(root,true)?};
    if staged {
        let (_,state)=launcher::resolve_release(root)?;
        local_update::resume_from_manager(root,&state)?;
    } else {
        // Reinstalling the current version must also recover a stopped Pocket
        // background. The normal launcher owns single-instance startup.
        open_current(root)?;
    }
    std::fs::remove_file(path)?;
    Ok(())
}
#[cfg(windows)]
fn stage_existing_from_setup() -> Result<()> {
    let root = storage::local_app_data()?.join("CodexPocket");
    if root.join("desktop-install.json").try_exists()? {
        // Every overwrite installation uses the package-free worker. Payload
        // copying and verification cannot fit a fast hook's deadline on all PCs.
            storage::private_dir(&root.join("control"))?;
            storage::atomic(&deferred_stage_path(&root),&serde_json::json!({"version":env!("CARGO_PKG_VERSION")}))?;
            let current=std::env::current_exe()?;
            let manager=current.parent().context("Missing setup directory")?;
            let worker=root.join("control").join(format!("setup-worker-{}.exe",env!("CARGO_PKG_VERSION")));
            storage::unredirected(&worker)?;
            // Copy atomically before dispatch; never run from Velopack's
            // mutable directory or overwrite a worker that may already be alive.
            if !worker.try_exists()? {
                let mut staged=tempfile::NamedTempFile::new_in(worker.parent().unwrap())?;
                std::io::copy(&mut storage::open_physical(&current)?,&mut staged)?;
                staged.as_file().sync_all()?;
                staged.persist_noclobber(&worker)?;
            }
            ensure!(std::fs::read(&worker)?==std::fs::read(&current)?,"Deferred setup worker differs from installer");
            return pocket_desktop_runtime::update_task::setup(&worker,&root,env!("CARGO_PKG_VERSION"),Some(manager),false)
    }
    Ok(())
}

fn run(install: bool) -> Result<()> {
    let root = storage::local_app_data()?.join("CodexPocket");
    if root.join("desktop-install.json").try_exists()? {
        #[cfg(windows)]
        if deferred_stage_path(&root).try_exists()? {
            finish_deferred_stage(&root,None)?;
            return open_current(&root);
        }
        existing_entry(install, || open_current(&root), || install_candidate(&root, true))?;
    } else {
        // A first launch after silent installation also needs to initialize
        // the product. Never do this if an installation record already exists.
        install_candidate(&root, false)?;
        open_current(&root)?;
    }
    Ok(())
}
#[cfg(test)]
mod entry_tests {
    use super::*;

    #[test]
    fn opening_manager_can_resume_explicit_recovery_after_coordinator_exit() {
        let mut status = updates::UpdateStatus { explicit_install: true, ..Default::default() };
        let mut dispatches = 0;
        for phase in ["staged", "waiting_idle", "switching", "cold_starting", "rolled_back", "succeeded"] {
            status.phase = phase.into();
            resume_explicit_update(Some(&status), || { dispatches += 1; Ok(()) });
        }
        assert_eq!(dispatches, 4);
        status.explicit_install = false;
        status.phase = "cold_starting".into();
        resume_explicit_update(Some(&status), || panic!("automatic update is not owned by setup"));
    }

    #[test]
    fn an_existing_installation_opens_without_touching_the_candidate() {
        let root = tempfile::tempdir().unwrap();
        let root = root.path();
        let bin = root.join("bin");
        let state = root.join("agent");
        let mut actions = Vec::new();
        let outcome = existing_entry(false, || { actions.push("open-current"); Ok(()) }, || {
            panic!("opening Pocket must not read or stage the installer payload")
        }).unwrap();
        assert_eq!(actions, ["open-current"]);
        assert!(!outcome);
        assert!(!bin.exists());
        assert!(!state.exists());
    }

    #[test]
    fn installer_selects_new_generation_before_opening_window() {
        let staged = Cell::new(false);
        existing_entry(true, || { assert!(staged.get(), "opened old window"); Ok(()) },
            || { staged.set(true); Ok(true) }).unwrap();
    }

    #[test]
    fn an_explicit_installer_attempt_does_not_own_the_product_window() {
        let opened = std::cell::Cell::new(false);
        let result = existing_entry(true, || { opened.set(true); Ok(()) }, || {
            anyhow::bail!("candidate staging failed")
        });
        assert!(opened.get());
        assert_eq!(result.unwrap_err().to_string(), "candidate staging failed");
    }
}
