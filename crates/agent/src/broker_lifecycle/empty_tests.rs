use super::*;
use pocket_core::admission::{self,Gate,GatePhase,Installation,Registration,RegistrationPhase};

struct Fixture { _temp:tempfile::TempDir, root:PathBuf,state:PathBuf,exe:PathBuf,plan:Value,op:String,runtime:String }
impl Fixture {
    fn new() -> Self {
        let temp=tempfile::tempdir().unwrap();let root=dunce::canonicalize(temp.path()).unwrap();
        // Receipt directories use admission's explicit TokenUser owner, including under elevation.
        admission::private_dir(&root.join("state/desktop/updates")).unwrap();
        for dir in [root.clone(),root.join("runtimes"),root.join("bin")]{crate::storage::private_directory(&dir).unwrap();}
        let files:Vec<_>=["pocket-agent.exe","windows-install.ps1","windows-common.ps1"].into_iter().map(|name|json!({"path":name,"bytes":7,"sha256":hex::encode(sha2::Sha256::digest(b"fixture"))})).collect();
        let bytes=serde_json::to_vec(&json!({"files":files})).unwrap();let generation=hex::encode(sha2::Sha256::digest(&bytes));
        let bin=root.join("bin").join(&generation);crate::storage::private_directory(&bin).unwrap();std::fs::write(bin.join("windows-manifest.json"),bytes).unwrap();
        for name in ["pocket-agent.exe","windows-install.ps1","windows-common.ps1"]{std::fs::write(bin.join(name),b"fixture").unwrap();}
        std::fs::write(root.join("pocket-launcher.exe"),b"fixture").unwrap();let state=root.join("state");
        let write=|p:PathBuf,v:Value|crate::storage::atomic_write(&p,&serde_json::to_vec(&v).unwrap()).unwrap();
        write(root.join("desktop-install.json"),json!({"version":3,"singleEntry":true,"installRoot":root,"stateDirectory":state,"bridge":root.join("pocket-launcher.exe"),"launcherHash":admission::file_sha256(&root.join("pocket-launcher.exe")).unwrap(),"generation":generation,"releaseHash":generation}));
        write(state.join("config.json"),json!({"runtimeDirectories":[root.join("runtimes")],"codexHomes":[]}));
        write(state.join("commands.json"),json!({"entries":[]}));write(state.join("ownership.json"),json!([]));
        let runtime=uuid::Uuid::new_v4().to_string();let op=uuid::Uuid::new_v4().to_string();
        let ident=|pid:u32,birth:u64,path:PathBuf|json!({"pid":pid,"createdAt":birth,"executable":path});
        let desktop=ident(u32::MAX-2,1,root.join("package/Codex.exe"));let bridge=ident(u32::MAX-1,2,bin.join("pocket-bridge.exe"));let native=ident(u32::MAX,3,root.join("package/codex.exe"));
        let image=|id:Value|json!({"identity":id,"sha256":"a".repeat(64)});
        let plan=json!({"desktop":image(desktop.clone()),"bridge":image(bridge.clone()),"native":image(native.clone()),"children":[],"additional":[],"runtimeId":runtime,"descriptor":root.join("runtimes").join(format!("{runtime}.json")),"ancestry":[{"child":bridge,"parent":desktop},{"child":native,"parent":bridge}]});
        write(state.join("desktop/native-update.json"),json!({"schema":1,"operationId":op,"installRoot":root,"stateDirectory":state,"phase":"succeeded","fromGeneration":generation,"toGeneration":generation,"original":null,"stopping":null,"verified":plan}));
        let receipt=state.join("desktop/updates").join(format!("native-release-{op}.json"));
        let guard=Installation::open(&root).unwrap().update().unwrap();
        let gate=Gate{schema:1,operation_id:op.clone(),phase:GatePhase::Closing,allowed_generation:None,release_file:receipt.clone()};guard.set_gate(&gate).unwrap();
        let gate=Gate{phase:GatePhase::Starting,allowed_generation:Some(generation),..gate};guard.set_gate(&gate).unwrap();
        write(receipt,json!({"schema":1,"operationId":op,"released":true}));guard.set_gate(&Gate{phase:GatePhase::Released,..gate}).unwrap();drop(guard);
        Self {_temp:temp,root,state,exe:bin.join("pocket-agent.exe"),plan,op,runtime}
    }
    fn proof(&self)->Result<EmptyProof>{empty_proof(&self.state,&self.exe)}
    fn save_plan(&self,plan:Value){let path=self.state.join("desktop/native-update.json");let mut j=empty_read(&path).unwrap();j["verified"]=plan;crate::storage::atomic_write(&path,&serde_json::to_vec(&j).unwrap()).unwrap();}
}
use sha2::Digest;
#[test]
#[ignore = "requires explicitly marked retired Windows consumer fixture"]
fn actual_retained_empty_inventory() {
    let root=PathBuf::from(std::env::var_os("POCKET_EMPTY_NATIVE_FIXTURE").expect("fixture root"));
    assert_eq!(std::fs::read(root.join(".fixture-owner")).unwrap(),b"native-consumer-combined");
    let install=root.join("install");let state=install.join("isolated state");let record=empty_read(&install.join("desktop-install.json")).unwrap();
    let exe=install.join("bin").join(record["generation"].as_str().unwrap()).join("pocket-agent.exe");
    let proof=empty_proof(&state,&exe).unwrap();assert!(!proof.exited.is_empty());
    let proof=empty_inventory(proof,&state,&exe).unwrap();
    assert!(proof.ledger["entries"].as_array().unwrap().iter().all(|e|matches!(serde_json::from_value::<Status>(e["status"].clone()),Ok(Status::Accepted{..}|Status::Rejected{..}))));
    println!("full retained OS/native exit, exact released operation, registrations and ledger verified");
}
#[test]
fn full_exit_proof_is_required_even_with_zero_runtimes() {
    let f=Fixture::new();assert!(f.proof().is_ok());
    f.save_plan(Value::Null);assert!(f.proof().is_err());
    let mut plan=f.plan.clone();plan["ancestry"]=json!([]);f.save_plan(plan);assert!(f.proof().is_err());
    let mut plan=f.plan.clone();plan["native"]["identity"]["pid"]=json!(std::process::id());plan["native"]["identity"]["createdAt"]=json!(NativeProcess::capture(std::process::id()).unwrap().created_at);f.save_plan(plan);assert!(f.proof().is_err());
}
#[test]
fn uncertain_starting_and_changed_registration_never_authorize_empty() {
    let f=Fixture::new();let native=empty_identity(&f.plan["native"]["identity"]).unwrap();let bridge=empty_identity(&f.plan["bridge"]["identity"]).unwrap();
    let registration=Registration{schema:1,runtime_id:f.runtime.clone(),install_root:f.root.clone(),generation:f.exe.parent().unwrap().file_name().unwrap().to_str().unwrap().into(),bridge,bridge_executable:empty_path(&f.plan["bridge"]["identity"]["executable"]).unwrap(),descriptor:empty_path(&f.plan["descriptor"]).unwrap(),operation_id:Some(f.op.clone()),release_file:Some(f.state.join("desktop/updates").join(format!("native-release-{}.json",f.op))),phase:RegistrationPhase::Starting,native:None};
    let path=f.root.join("control/native-startups").join(format!("{}.json",f.runtime));crate::storage::private_directory(path.parent().unwrap()).unwrap();
    let write=|r:&Registration|crate::storage::atomic_write(&path,&serde_json::to_vec(r).unwrap()).unwrap();
    write(&registration);let original=std::fs::read(&path).unwrap();assert!(f.proof().is_err());assert_eq!(std::fs::read(&path).unwrap(),original);
    let mut registered=registration;registered.phase=RegistrationPhase::Registered;registered.native=Some(native);write(&registered);assert!(f.proof().is_ok());
    registered.bridge.created_at+=1;write(&registered);assert!(f.proof().is_err());
}
#[test]
fn wrong_root_operation_and_generation_do_not_borrow_receipt() {
    let f=Fixture::new();assert!(empty_proof(&f.root,&f.exe).is_err());
    let path=f.state.join("desktop/native-update.json");let mut journal=empty_read(&path).unwrap();journal["operationId"]=json!(uuid::Uuid::new_v4().to_string());crate::storage::atomic_write(&path,&serde_json::to_vec(&journal).unwrap()).unwrap();assert!(f.proof().is_err());
}
#[test]
fn released_old_generation_needs_exact_full_updater_pointer_and_handoff() {
    let f=Fixture::new();let old=empty_read(&f.root.join("desktop-install.json")).unwrap();
    let mut manifest=empty_read(&f.exe.parent().unwrap().join("windows-manifest.json")).unwrap();manifest["fixtureGeneration"]=json!("C");
    let bytes=serde_json::to_vec(&manifest).unwrap();let generation=hex::encode(sha2::Sha256::digest(&bytes));let bin=f.root.join("bin").join(&generation);crate::storage::private_directory(&bin).unwrap();
    std::fs::write(bin.join("windows-manifest.json"),bytes).unwrap();for name in ["pocket-agent.exe","windows-common.ps1","windows-install.ps1"]{std::fs::write(bin.join(name),b"fixture").unwrap();}
    let exe=bin.join("pocket-agent.exe");let mut candidate=old.clone();candidate["generation"]=json!(generation);candidate["releaseHash"]=json!(generation);
    let write=|p:PathBuf,v:Value|crate::storage::atomic_write(&p,&serde_json::to_vec(&v).unwrap()).unwrap();write(f.root.join("desktop-install.json"),candidate.clone());
    assert!(empty_proof(&f.state,&exe).is_err());
    let op=uuid::Uuid::new_v4().to_string();let status=json!({"phase":"succeeded","generation":generation,"handoffOperationId":op,"candidateRecordHash":hex::encode(sha2::Sha256::digest(serde_json::to_vec(&candidate).unwrap())),"previousRecordHash":hex::encode(sha2::Sha256::digest(serde_json::to_vec(&old).unwrap())),"previousRecord":old});
    write(f.state.join("desktop/updates/status.json"),status.clone());
    let mut handoff=json!({"phase":"succeeded","operationId":op,"fromGeneration":old["generation"],"toGeneration":generation,"installRoot":f.root,"stateDirectory":f.state});write(f.state.join("desktop/upgrade.json"),handoff.clone());
    assert!(empty_proof(&f.state,&exe).is_ok());handoff["operationId"]=json!(uuid::Uuid::new_v4().to_string());write(f.state.join("desktop/upgrade.json"),handoff);assert!(empty_proof(&f.state,&exe).is_err());
    let mut status=status;status["previousRecordHash"]=json!("a".repeat(64));write(f.state.join("desktop/updates/status.json"),status);assert!(empty_proof(&f.state,&exe).is_err());
}
#[tokio::test]
async fn empty_inventory_preserves_exact_unknown_tombstones_and_rejects_pending_writes() {
    let f=Fixture::new();let mut proof=f.proof().unwrap();let (_dir,b)=super::super::tests::fixture().await;let mut state=b.state.lock().await;
    proof.ledger=serde_json::from_str(&state.ledger.snapshot_json()).unwrap();proof.ownership=json!([]);assert!(empty_broker_verified(&proof,&state,&b));
    let key=CommandKey{caller_id:"fixture".into(),command_id:"unknown".into()};state.ledger.begin(key.clone(),b"request",crate::storage::now()).unwrap();state.ledger.finish(&key,Status::Unknown,crate::storage::now()).unwrap();
    assert!(!empty_broker_verified(&proof,&state,&b),"a stale proof cannot hide the new tombstone");
    proof.ledger=serde_json::from_str(&state.ledger.snapshot_json()).unwrap();
    assert!(empty_broker_verified(&proof,&state,&b),"the exact Unknown receipt can travel in a checkpoint");
    assert_eq!(proof.ledger["entries"][0]["status"],"Unknown");
    assert_eq!(serde_json::from_str::<Value>(&state.ledger.snapshot_json()).unwrap(),proof.ledger);
    state.ownership_persist_failed=true;assert!(!empty_broker_verified(&proof,&state,&b));state.ownership_persist_failed=false;
    state.ledger.begin(CommandKey{caller_id:"fixture".into(),command_id:"submitted".into()},b"request",crate::storage::now()).unwrap();
    proof.ledger=serde_json::from_str(&state.ledger.snapshot_json()).unwrap();
    assert!(!empty_broker_verified(&proof,&state,&b),"a real in-flight write still blocks the proof");
}
#[tokio::test]
async fn retired_ownership_must_match_the_proved_tree_and_persisted_snapshot() {
    let f=Fixture::new();let mut proof=f.proof().unwrap();let (_dir,b)=super::super::tests::fixture().await;let mut state=b.state.lock().await;
    proof.ledger=serde_json::from_str(&state.ledger.snapshot_json()).unwrap();
    let claim=OwnershipClaim{auxiliary:false,native_process:Some(proof.exited[&f.runtime].native.clone()),advertisement:RuntimeAdvertisement{id:RuntimeId{computer_id:b.config.computer_id.clone(),runtime_id:f.runtime.clone()},source:RuntimeSource::Desktop,config_store_id:"fixture-store".into(),online:false,loaded_native_thread_ids:BTreeSet::new()}};
    state.ownership.insert(f.runtime.clone(),claim);
    proof.ownership=serde_json::to_value(b.ownership_values(&state).values().collect::<Vec<_>>()).unwrap();assert!(empty_broker_verified(&proof,&state,&b));
    state.ownership.get_mut(&f.runtime).unwrap().native_process.as_mut().unwrap().created_at+=1;
    assert!(!empty_broker_verified(&proof,&state,&b));
    proof.ownership=serde_json::to_value(b.ownership_values(&state).values().collect::<Vec<_>>()).unwrap();assert!(!empty_broker_verified(&proof,&state,&b));
}
#[tokio::test]
async fn empty_ledger_does_not_hide_an_unconfirmed_question_receipt() {
    let f=Fixture::new();let mut proof=f.proof().unwrap();let (_dir,b)=super::super::tests::fixture().await;let mut state=b.state.lock().await;
    proof.ledger=serde_json::from_str(&state.ledger.snapshot_json()).unwrap();proof.ownership=json!([]);assert!(empty_broker_verified(&proof,&state,&b));
    state.questions.reserve(CommandKey{caller_id:"fixture".into(),command_id:"orphan-question".into()},&f.runtime,"fixture-store",
        &json!({"id":"question","method":"item/tool/requestUserInput","params":{"threadId":"thread","turnId":"turn","itemId":"item","questions":[{"id":"q","isSecret":true}]}}),&json!({"answers":{"q":{"answers":["private"]}}})).unwrap();
    assert!(!empty_broker_verified(&proof,&state,&b));
}
#[tokio::test]
async fn concurrent_empty_reads_share_only_the_inflight_fenced_scan() {
    use futures_util::FutureExt;
    let f=Fixture::new();let proof=Arc::new(f.proof().unwrap());
    let calls=Arc::new(std::sync::atomic::AtomicU32::new(0));let ready=Arc::new(tokio::sync::Notify::new());
    let sample={let calls=calls.clone();let ready=ready.clone();async move {
        calls.fetch_add(1,std::sync::atomic::Ordering::SeqCst);ready.notified().await;Some(proof)
    }.boxed().shared()};
    let flight=Arc::new(EmptyFlight{sample});empty_flights().lock().unwrap().insert(f.state.clone(),Arc::downgrade(&flight));
    let (a,b,())=tokio::join!(empty_sample(f.state.clone()),empty_sample(f.state.clone()),async {tokio::task::yield_now().await;ready.notify_one();});
    assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst),1);assert!(Arc::ptr_eq(a.as_ref().unwrap(),b.as_ref().unwrap()));
    assert!(Installation::open(&f.root).unwrap().update().is_err());
    drop(flight);drop(a);drop(b);
    assert!(Installation::open(&f.root).unwrap().update().is_ok(),"a completed status sample kept admission locked");
    assert!(empty_sample(f.state.clone()).await.is_none(),"a later observation reused a completed true proof");
}
#[tokio::test]
async fn helper_owner_health_does_not_wait_for_empty_inventory_or_claim_idle() {
    use futures_util::FutureExt;
    let f=Fixture::new();let (_dir,b)=super::super::tests::fixture().await;
    let _lock=crate::local::try_lock(&b.state_dir).unwrap().unwrap();
    let _server=crate::local::Server::start(&b.state_dir,b.clone()).await.unwrap();
    let sample=std::future::pending::<Option<Arc<EmptyProof>>>().boxed().shared();
    let flight=Arc::new(EmptyFlight{sample});
    empty_flights().lock().unwrap().insert(b.state_dir.clone(),Arc::downgrade(&flight));
    let status=b.lifecycle_status();tokio::pin!(status);
    assert!(tokio::time::timeout(Duration::from_millis(50),&mut status).await.is_err(),"normal lifecycle must still await its proof");
    let health=tokio::time::timeout(Duration::from_secs(1),crate::local::execute(&b.state_dir,"helper/health",None)).await.unwrap()
        .expect("helper identity must be readable during an unresolved OS scan");
    assert_eq!(health["computerId"],b.config.computer_id);
    assert!(health.get("lifecycle").is_none(),"identity health must never claim native idle");
    assert!(tokio::time::timeout(Duration::from_millis(50),&mut status).await.is_err());
    assert!(crate::local::execute_with_params(&b.state_dir,"helper/health",None,json!({"force":true})).await.is_err());
    drop(flight);drop(f);
}
