//! Start update work through the current user's Task Scheduler service. A
//! Velopack hook is in a short-lived job; its children cannot own an update.
use anyhow::{ensure, Result};
use std::{path::Path, process::Command};

fn invoke(name:&str, executable:&Path, arguments:&str, directory:&Path, remove:bool)->Result<()> {
    ensure!(name.starts_with("CodexPocketUpdate-") || name.starts_with("CodexPocketSetup-"),"Invalid update task");
    ensure!(name.bytes().all(|b|b.is_ascii_alphanumeric()||b"-._".contains(&b)),"Invalid update task");
    crate::storage::plain(executable)?;crate::storage::plain(directory)?;
    let mut command=Command::new(crate::storage::powershell());
    // Environment values remain data. Never interpolate a path into script code.
    command.args(["-NoProfile","-NonInteractive","-Command",include_str!("update_task.ps1")])
        .env("POCKET_TASK_NAME",name).env("POCKET_TASK_EXE",executable)
        .env("POCKET_TASK_ARGS",arguments).env("POCKET_TASK_DIRECTORY",directory)
        .env("POCKET_TASK_REMOVE",if remove {"1"} else {"0"});
    crate::process::run_bounded(command,20)?;
    Ok(())
}

pub(crate) fn coordinator(executable:&Path,root:&Path,operation:&str,remove:bool)->Result<()> {
    ensure!(crate::supervisor::upgrade::local::canonical_uuid(operation),"Invalid update operation");
    invoke(&format!("CodexPocketUpdate-{operation}"),executable,&format!("--local-update {operation}"),root,remove)
}

pub fn setup(executable:&Path,root:&Path,version:&str,source:Option<&Path>,remove:bool)->Result<()> {
    semver::Version::parse(version)?;
    let arguments=if let Some(source)=source {
        crate::storage::plain(source)?;
        let value=source.to_str().ok_or_else(||anyhow::anyhow!("Invalid setup source"))?;
        ensure!(!value.contains(['"','\r','\n'])&&!value.ends_with('\\'),"Invalid setup source");
        format!("--finish-setup-stage \"{value}\"")
    } else {"--finish-setup-stage".into()};
    invoke(&format!("CodexPocketSetup-{version}"),executable,&arguments,root,remove)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_task_identity_fails_before_os_actions() {
        assert!(coordinator(Path::new("x"),Path::new("x"),"invalid;id",false).is_err());
        assert!(setup(Path::new("x"),Path::new("x"),"1.0;command",None,false).is_err());
    }
    #[cfg(windows)]
    #[test]
    fn dispatch_child() {
        let Some(root)=std::env::var_os("POCKET_TASK_TEST_ROOT") else {return};
        let root=std::path::PathBuf::from(root);
        while !root.join("go").exists(){std::thread::sleep(std::time::Duration::from_millis(10));}
        coordinator(&root.join("fixture.exe"),&root,&std::env::var("POCKET_TASK_TEST_OPERATION").unwrap(),false).unwrap();
        std::fs::write(root.join("dispatched"),b"ready").unwrap();
        std::thread::sleep(std::time::Duration::from_secs(30));
    }
    #[cfg(windows)]
    #[test]
    #[ignore="real current-user Task Scheduler; isolated fixture only"]
    fn task_survives_an_installer_job_without_breakaway_permission() {
        use std::{fs,time::{Duration,Instant}};
        let root=tempfile::Builder::new().prefix(".update-dispatch-test-").tempdir_in(std::env::current_dir().unwrap()).unwrap();
        let source=root.path().join("fixture.rs");
        fs::write(&source,r#"fn main(){let root=std::env::current_dir().unwrap();std::fs::write(root.join("started"),std::process::id().to_string()).unwrap();std::thread::sleep(std::time::Duration::from_secs(4));std::fs::write(root.join("survived"),b"done").unwrap();}"#).unwrap();
        let executable=root.path().join("fixture.exe");
        assert!(Command::new("rustc").arg(source).arg("-o").arg(&executable).status().unwrap().success());
        let operation=uuid::Uuid::new_v4().to_string();
        let mut cmd=Command::new(std::env::current_exe().unwrap());
        cmd.args(["--exact","update_task::tests::dispatch_child","--nocapture"])
            .env("POCKET_TASK_TEST_ROOT",root.path()).env("POCKET_TASK_TEST_OPERATION",&operation);
        crate::process::hidden(&mut cmd);
        let mut child=cmd.spawn().unwrap();
        let job=crate::process::ChildJob::new().unwrap();job.assign(&child).unwrap();
        fs::write(root.path().join("go"),b"go").unwrap();
        let until=Instant::now()+Duration::from_secs(25);
        while !root.path().join("dispatched").exists() && child.try_wait().unwrap().is_none() && Instant::now()<until {std::thread::sleep(Duration::from_millis(50));}
        let dispatched=root.path().join("dispatched").exists();
        drop(job);let _=child.wait();
        let until=Instant::now()+Duration::from_secs(8);
        while !root.path().join("survived").exists() && Instant::now()<until {std::thread::sleep(Duration::from_millis(50));}
        coordinator(&executable,root.path(),&operation,true).unwrap();
        assert!(dispatched,"scheduler dispatch failed inside the restricted installer job");
        assert!(root.path().join("survived").exists(),"installer exit terminated its update worker");
    }
}
