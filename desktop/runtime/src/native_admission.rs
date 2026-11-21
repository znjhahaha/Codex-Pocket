//! The exclusive startup lock bridges OS inventory and exact-handle stop.
//! Its durable gate remains closed if any process or coordinator crashes.
use super::*;
use pocket_core::{admission::{Gate,GatePhase,Installation,Registration,RegistrationPhase,UpdateGuard},native_process::NativeProcess};

fn binding(j:&Journal)->Result<()> {
    ensure!(upgrade::local::canonical_uuid(&j.operation_id),"native_operation_invalid");
    let (_,state)=crate::launcher::resolve_release(&j.install_root)?;
    ensure!(state==j.state_directory,"native_admission_state_changed");
    ensure!(read_status(&state)?.as_ref()==Some(j),"native_admission_journal_changed");
    Ok(())
}
fn gate(j:&Journal,phase:GatePhase,generation:Option<String>)->Result<Gate> {
    Ok(Gate{schema:1,operation_id:j.operation_id.clone(),phase,allowed_generation:generation,release_file:release_path(j)?})
}
fn same_operation(j:&Journal,g:&Gate)->Result<()> {
    ensure!(g.operation_id==j.operation_id&&g.release_file==release_path(j)?,"native_admission_operation_changed");
    Ok(())
}
fn identity(p:&Image)->NativeProcess {NativeProcess{pid:p.identity.pid,created_at:p.identity.created_at}}

fn known_gone(j:&Journal,r:&Registration)->bool {
    if r.phase!=RegistrationPhase::Registered{return false}
    [&j.original,&j.stopping,&j.verified].into_iter().flatten().any(|p| {
        if !connections(p).iter().any(|c|r.bridge==identity(&c.bridge)&&r.native.as_ref()==Some(&identity(&c.native))) {return false}
        if originals_gone(p){return true}
        if p.ancestry.is_empty(){return false}
        let mut subtree=vec![r.bridge.clone()];
        loop {
            let before=subtree.len();
            for edge in &p.ancestry {
                let parent=NativeProcess{pid:edge.parent.pid,created_at:edge.parent.created_at};
                let child=NativeProcess{pid:edge.child.pid,created_at:edge.child.created_at};
                if subtree.contains(&parent)&&!subtree.contains(&child){subtree.push(child);}
            }
            if before==subtree.len(){break}
        }
        r.native.as_ref().is_some_and(|n|subtree.contains(n))&&subtree.iter().all(NativeProcess::terminated)
    })
}
fn prune_known_gone(j:&Journal,guard:&UpdateGuard)->Result<()> {
    // Completed operations retain the last fully verified tree. A later helper
    // switch may find its normally exited Desktop's records left by a crash.
    // A dead Bridge alone, especially a Starting record, never permits pruning.
    let mut prior=None;
    if let Some(g)=guard.gate()? {
        if g.operation_id!=j.operation_id&&pocket_core::admission::read_release(&g.release_file,&g.operation_id)? {
            let mut count=0;
            for entry in std::fs::read_dir(storage::control(&j.state_directory))? {
                let path=entry?.path();
                let name=path.file_name().and_then(|n|n.to_str()).unwrap_or("");
                if !name.starts_with("native-update-")||!name.ends_with(".json"){continue}
                count+=1;ensure!(count<=256,"native_admission_archive_limit");
                let saved:Journal=storage::read(&path)?;
                if saved.operation_id!=g.operation_id{continue}
                ensure!(saved.schema==1&&saved.install_root==j.install_root&&saved.state_directory==j.state_directory
                    &&release_path(&saved)?==g.release_file&&released(&saved)?
                    &&["succeeded","rolled_back","resuming","rollback_resuming"].contains(&saved.phase.as_str()),"native_admission_archive_unverified");
                ensure!(prior.is_none(),"native_admission_archive_ambiguous");prior=Some(saved);
            }
        }
    }
    for r in guard.registrations()? {
        if known_gone(j,&r)||prior.as_ref().is_some_and(|saved|known_gone(saved,&r)) {guard.remove_registration(&r.runtime_id)?;}
    }
    Ok(())
}
fn matches_connection(root:&Path,c:&Plan,r:&Registration)->bool {
    r.phase==RegistrationPhase::Registered&&r.install_root==root&&r.runtime_id==c.runtime_id
        && r.bridge==identity(&c.bridge)&&r.bridge_executable==c.bridge.identity.executable
        && r.native.as_ref()==Some(&identity(&c.native))&&r.descriptor==c.descriptor
        && generation(c).ok().as_deref()==Some(&r.generation)
}
pub(in crate::native_update) fn verify_registered(j:&Journal,p:&Plan,guard:&UpdateGuard)->Result<()> {
    let records=guard.registrations()?;
    let all=connections(p);
    ensure!(records.len()==all.len(),"native_admission_registry_incomplete");
    for c in all {
        let matches:Vec<_>=records.iter().filter(|r|matches_connection(&j.install_root,&c,r)).collect();
        ensure!(matches.len()==1,"native_admission_registration_unverified");
        let r=matches[0];
        let d:Value=storage::read(&c.descriptor)?;
        let m=rpc(&c,"pocket/managementStatus",json!({}))?;
        let expected=serde_json::to_value(r)?;
        ensure!(d["startupAdmissionProtocol"]==1&&d["startupAdmission"]==expected
            &&m["startupAdmissionProtocol"]==1&&m["startupAdmission"]==expected,
            "native_installation_admission_unavailable");
    }
    Ok(())
}
pub(in crate::native_update) fn close(j:&Journal,p:&Plan)->Result<UpdateGuard> {
    binding(j)?;
    ensure!(!released(j)?,"native_admission_already_released");
    let guard=Installation::open(&j.install_root)?.update()?;
    prune_known_gone(j,&guard)?;
    // The shared lock spans pre-spawn registration and management publication.
    // Thus this scan happens after every successful in-flight startup joined.
    ensure!(inventory(&j.install_root,&j.state_directory,&j.to_generation)?.as_ref()==Some(p),"native_tree_changed_under_admission");
    verify_registered(j,p,&guard)?;
    // Do not poison an unmanaged/legacy installation with a gate it cannot
    // participate in. Exclusive already excludes every supported startup.
    guard.set_gate(&gate(j,GatePhase::Closing,None)?)?;
    Ok(guard)
}
pub(in crate::native_update) fn allow_start(j:&Journal)->Result<()> {
    binding(j)?;
    ensure!(!released(j)?,"native_admission_already_released");
    let guard=Installation::open(&j.install_root)?.update()?;
    let g=guard.gate()?.context("native_admission_gate_missing")?;
    same_operation(j,&g)?;
    ensure!(matches!(g.phase,GatePhase::Closing|GatePhase::Starting),"native_admission_close_required");
    guard.set_gate(&gate(j,GatePhase::Closing,None)?)?;
    for p in [&j.original,&j.stopping].into_iter().flatten(){ensure!(originals_gone(p),"native_predecessor_still_alive");}
    ensure!(inventory(&j.install_root,&j.state_directory,&j.to_generation)?.is_none(),"native_unexpected_tree_before_launch");
    prune_known_gone(j,&guard)?;
    ensure!(guard.registrations()?.is_empty(),"native_startup_recovery_unresolved");
    let (bin,_)=crate::launcher::resolve_release(&j.install_root)?;
    guard.set_gate(&gate(j,GatePhase::Starting,Some(crate::installation::generation(&bin)?))?)?;
    Ok(())
}
pub(in crate::native_update) fn resume_guard(j:&Journal)->Result<UpdateGuard> {
    binding(j)?;
    let guard=Installation::open(&j.install_root)?.update()?;
    let g=guard.gate()?.context("native_admission_gate_missing")?;
    same_operation(j,&g)?;
    ensure!(matches!(g.phase,GatePhase::Starting|GatePhase::Released),"native_admission_resume_phase");
    let expected=if j.phase=="rollback_resuming"{&j.from_generation}else{&j.to_generation};
    ensure!(g.allowed_generation.as_ref()==Some(expected),"native_admission_generation_changed");
    prune_known_gone(j,&guard)?;
    Ok(guard)
}
pub(in crate::native_update) fn finish(j:&Journal,guard:&UpdateGuard)->Result<()> {
    binding(j)?;
    ensure!(released(j)?,"native_admission_release_missing");
    let g=guard.gate()?.context("native_admission_gate_missing")?;
    same_operation(j,&g)?;
    guard.set_gate(&Gate{phase:GatePhase::Released,..g})
}
pub(in crate::native_update) fn finish_released(j:&Journal,guard:&UpdateGuard)->Result<bool> {
    binding(j)?;
    ensure!(released(j)?,"native_admission_release_missing");
    let g=guard.gate()?.context("native_admission_gate_missing")?;
    same_operation(j,&g)?;
    let expected=if j.phase=="rollback_resuming" {&j.from_generation} else {&j.to_generation};
    ensure!(["resuming","rollback_resuming"].contains(&j.phase.as_str())
        &&matches!(g.phase,GatePhase::Starting|GatePhase::Released)
        &&g.allowed_generation.as_ref()==Some(expected),"native_admission_completion_binding_invalid");
    let verified=j.verified.as_ref().context("verified_native_missing")?;
    ensure!(connections(verified).iter().all(|c|generation(c).ok().as_ref()==Some(expected)),"native_admission_verified_generation_changed");
    if g.phase!=GatePhase::Released {
        use crate::supervisor::maintenance::{presence,Presence};
        let gone=|p:&ProcessIdentity|p.pid!=0&&p.created_at!=0&&matches!(presence(p),Presence::Gone);
        // Absence of an inventory/descriptor cannot substitute for the whole
        // original durable tree, including every recorded ancestry identity.
        if !io::images(verified).iter().all(|image|gone(&image.identity))
            ||!verified.ancestry.iter().all(|edge|gone(&edge.parent)&&gone(&edge.child)) {
            return Ok(false);
        }
    }
    // This never inventories/adopts a replacement, issues RPC, or launches or
    // stops a process. It does not touch uncertain Starting registrations.
    finish(j,guard)?;
    Ok(true)
}
// A previously closed desktop remains closed. A completed old gate must still
// advance to the newly committed generation, or the next manual launch fails.
pub(in crate::native_update) fn finish_empty(j:&Journal)->Result<()> {
    binding(j)?;
    let guard=Installation::open(&j.install_root)?.update()?;
    if guard.gate()?.is_none(){
        ensure!(guard.registrations()?.is_empty(),"native_startup_recovery_unresolved");
        return Ok(())
    }
    let existing=guard.gate()?.unwrap();
    if existing.operation_id==j.operation_id&&released(j)? {
        ensure!(existing.allowed_generation.as_ref()==Some(&j.to_generation),"native_admission_generation_changed");
        // Receipt publication is irreversible even if a late Desktop has begun
        // work. Recovery only finishes the gate, without sampling or stopping it.
        return finish(j,&guard);
    }
    ensure!(inventory(&j.install_root,&j.state_directory,&j.to_generation)?.is_none(),"native_tree_appeared");
    prune_known_gone(j,&guard)?;
    ensure!(guard.registrations()?.is_empty(),"native_startup_recovery_unresolved");
    guard.set_gate(&gate(j,GatePhase::Closing,None)?)?;
    guard.set_gate(&gate(j,GatePhase::Starting,Some(j.to_generation.clone()))?)?;
    let p=release_path(j)?;storage::private_dir(p.parent().context("release parent")?)?;
    storage::atomic(&p,&ReleaseReceipt{schema:1,operation_id:j.operation_id.clone(),released:true})?;
    finish(j,&guard)
}

pub(in crate::native_update) fn finish_current(j:&Journal,p:&Plan)->Result<()> {
    binding(j)?;
    let guard=Installation::open(&j.install_root)?.update()?;
    if let Some(g)=guard.gate()? {
        ensure!(g.phase==GatePhase::Released&&g.allowed_generation.as_ref()==Some(&j.to_generation)
            &&pocket_core::admission::read_release(&g.release_file,&g.operation_id)?,"native_current_admission_unverified");
        prune_known_gone(j,&guard)?;verify_registered(j,p,&guard)?;
    }
    ensure!(inventory(&j.install_root,&j.state_directory,&j.to_generation)?.as_ref()==Some(p),"native_tree_changed_under_admission");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn empty_without_prior_gate_rejects_uncertain_startup_registration() {
        use sha2::{Digest, Sha256};
        let root = crate::test_support::tempdir();
        let mut j = super::super::super::recovery_tests::journal(root.path());
        let manifest = b"{}";
        j.to_generation = format!("{:x}", Sha256::digest(manifest));
        let bin = root.path().join("bin").join(&j.to_generation);
        storage::private_dir(&bin).unwrap();
        std::fs::write(bin.join("windows-manifest.json"), manifest).unwrap();
        let record = json!({"version":3,"installRoot":root.path(),"stateDirectory":j.state_directory,
            "bridge":root.path().join("pocket-launcher.exe"),"generation":j.to_generation,"releaseHash":j.to_generation});
        storage::atomic(&root.path().join("desktop-install.json"), &record).unwrap();
        save(&mut j, "empty_resuming").unwrap();
        let installation = Installation::open(root.path()).unwrap();
        let id = uuid::Uuid::new_v4().to_string();
        let r = Registration { schema:1, runtime_id:id.clone(), install_root:root.path().into(),
            generation:j.from_generation.clone(), bridge:NativeProcess {pid:u32::MAX,created_at:1},
            bridge_executable:root.path().join("bin").join(&j.from_generation).join("pocket-bridge.exe"),
            descriptor:root.path().join("runtimes").join(format!("{id}.json")),
            operation_id:None, release_file:None, phase:RegistrationPhase::Starting, native:None };
        let path = root.path().join("control/native-startups").join(format!("{id}.json"));
        storage::atomic(&path, &r).unwrap();
        let bytes = std::fs::read(&path).unwrap();
        assert_eq!(installation.update().unwrap().registrations().unwrap(), vec![r]);
        assert!(finish_empty(&j).is_err(), "an abandoned pre-spawn registration is not an empty installation");
        assert_eq!(std::fs::read(&path).unwrap(), bytes);
        assert!(installation.update().unwrap().gate().unwrap().is_none());
        assert!(!released(&j).unwrap());
        std::fs::remove_file(path).unwrap();
        finish_empty(&j).unwrap();
        assert!(installation.update().unwrap().gate().unwrap().is_none());
    }

    #[test]
    fn incomplete_spawn_is_never_cleanup_evidence_even_if_bridge_is_gone() {
        let d=crate::test_support::tempdir();let j=super::super::super::recovery_tests::journal(d.path());
        let r=Registration{schema:1,runtime_id:uuid::Uuid::new_v4().to_string(),install_root:d.path().into(),generation:"a".repeat(64),bridge:NativeProcess{pid:u32::MAX,created_at:1},bridge_executable:d.path().join("bridge.exe"),descriptor:d.path().join("runtimes/test.json"),operation_id:None,release_file:None,phase:RegistrationPhase::Starting,native:None};
        assert!(!known_gone(&j,&r));
    }
}
