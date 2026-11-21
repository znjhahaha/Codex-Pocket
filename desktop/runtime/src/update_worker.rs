//! Automatic maintenance belongs to the supervisor, independently of the UI.
use anyhow::{Context, Result, ensure};
use std::{path::{Path,PathBuf}, time::Duration, sync::{Arc,atomic::{AtomicBool,Ordering},mpsc},thread};

pub struct Worker {
    permitted: Arc<AtomicBool>,
    stopped: Arc<AtomicBool>,
    wake: mpsc::SyncSender<()>,
    thread: Option<thread::JoinHandle<()>>,
}
fn installed_root(agent:&Path,state:&Path)->Result<PathBuf> {
    crate::storage::unredirected(agent)?;
    let bin=agent.parent().context("Missing agent generation")?;
    crate::installation::generation(bin)?;
    let bins=bin.parent().context("Missing installation bin")?;
    ensure!(bins.file_name().is_some_and(|n|n=="bin"),"Not an installed generation");
    let root=bins.parent().context("Missing installation root")?;
    let (_,installed_state)=crate::launcher::resolve_release(root)?;
    ensure!(installed_state==state,"Update worker state differs from installation");
    Ok(root.to_owned())
}
impl Worker {
    /// The caller already holds both supervisor owner locks. A second daemon
    /// lock excludes worker duplication, including across a supervisor restart.
    pub fn start(agent:&Path,state:&Path)->Result<Self> {
        let root=installed_root(agent,state)?;let owned_state=state.to_owned();
        Self::with_tick(state,Duration::from_secs(60),move||{
            let _=crate::updates::tick(&root,&owned_state);
        })
    }
    fn with_tick(state:&Path,interval:Duration,mut tick:impl FnMut()+Send+'static)->Result<Self> {
        let directory=state.join("desktop/updates");crate::storage::private_dir(&directory)?;
        let owner=crate::storage::Lock::acquire(&directory.join("daemon.lock"))?;
        let permitted=Arc::new(AtomicBool::new(false));let stopped=Arc::new(AtomicBool::new(false));
        let (wake,receiver)=mpsc::sync_channel(1);
        let permission=permitted.clone();let stop=stopped.clone();
        let handle=thread::Builder::new().name("pocket-auto-update".into()).spawn(move||{
            let _owner=owner;
            loop {
                match receiver.recv_timeout(interval){Err(mpsc::RecvTimeoutError::Disconnected)=>break,_=>{}}
                if stop.load(Ordering::Acquire){break}
                if permission.load(Ordering::Acquire){tick()}
            }
        })?;
        Ok(Self{permitted,stopped,wake,thread:Some(handle)})
    }
    pub fn permit(&self,allowed:bool){
        let prior=self.permitted.swap(allowed,Ordering::AcqRel);
        if prior!=allowed{let _=self.wake.try_send(());}
    }
}
impl Drop for Worker {
    fn drop(&mut self){
        self.stopped.store(true,Ordering::Release);let _=self.wake.try_send(());
        // Production is process-scoped: never delay supervisor owner release
        // waiting on a network transfer. Process exit closes this worker's lock;
        // immutable staging and the existing journals recover partial work.
        #[cfg(test)] if let Some(handle)=self.thread.take(){let _=handle.join();}
        #[cfg(not(test))] let _=self.thread.take();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;

    #[test]
    fn supervisor_worker_dispatches_without_ui_and_pauses_during_maintenance() {
        let root=crate::test_support::tempdir();
        let state=root.path().join("state");crate::storage::private_dir(&state).unwrap();
        let (tx,rx)=mpsc::channel();
        let worker=Worker::with_tick(&state,Duration::from_millis(30),move||{tx.send(()).unwrap();}).unwrap();
        assert!(rx.recv_timeout(Duration::from_millis(90)).is_err());
        worker.permit(true);
        rx.recv_timeout(Duration::from_secs(2)).unwrap();
        worker.permit(false);
        std::thread::sleep(Duration::from_millis(80));
        while rx.try_recv().is_ok(){}
        assert!(rx.recv_timeout(Duration::from_millis(90)).is_err());
        worker.permit(true);
        rx.recv_timeout(Duration::from_secs(2)).unwrap();
        drop(worker);
    }

    #[test]
    fn supervisor_worker_owner_lock_excludes_a_second_worker_and_releases_on_exit() {
        let root=crate::test_support::tempdir();
        let state=root.path().join("state");crate::storage::private_dir(&state).unwrap();
        let worker=Worker::with_tick(&state,Duration::from_secs(1),||{}).unwrap();
        assert!(Worker::with_tick(&state,Duration::from_secs(1),||{}).is_err());
        drop(worker);
        Worker::with_tick(&state,Duration::from_secs(1),||{}).unwrap();
    }

    #[test]
    fn development_binary_cannot_select_a_global_installation() {
        let root=crate::test_support::tempdir();
        let agent=root.path().join("debug/pocket-agent.exe");
        assert!(installed_root(&agent,&root.path().join("state")).is_err());
    }
}
