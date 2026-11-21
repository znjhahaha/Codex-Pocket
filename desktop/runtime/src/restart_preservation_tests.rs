use super::*;
#[test]
fn authenticated_retained_host_can_be_absent_from_broker_adapters() {
    let home=tempfile::tempdir().unwrap();let(mut h,claims,host)=fixture(home.path());
    h["runtimes"]=json!([]);
    inventory(&h,&claims,&[host],&[],[&process(1),&process(2)]).unwrap();
}
use serde_json::json;

fn process(pid: u32) -> ProcessIdentity {
    ProcessIdentity { pid, created_at: pid as u64 + 100, executable: PathBuf::from("fixture.exe") }
}
fn fixture(home: &Path) -> (Value, Value, HostProof) {
    let normalized=pocket_runtime_ipc::support::normalized_home(home).unwrap();
    let store=format!("{:x}",Sha256::digest(normalized.strip_prefix(r"\\?\").unwrap_or(&normalized).as_bytes()));
    let host=HostProof {runtime:"managed".into(),generation:"run".into(),descriptor:home.join("descriptor.json"),home:home.into(),
        host:process(3),native:process(4),startup:Startup {schema:1,home_key:"home".into(),config_home:home.into(),
            native_binary:PathBuf::from("fixture.exe"),installation_generation:"a".repeat(64),reservation:"reservation".into(),
            phase:"ready".into(),host_process:native_identity(&process(3)),descriptor_generation:"run".into()},
        retained:BTreeMap::from([("commands:s:unknown".into(),"prior-generation".into())])};
    let h=json!({"computerId":"computer","runtimes":[{"id":"managed","role":{"kind":"managedHost","generation":"run",
        "reconciliationRequired":true},"online":true}],"auxiliaryRuntimes":[],"discoveryErrors":[]});
    let claims=json!([{"id":{"computer_id":"computer","runtime_id":"managed"},"source":"Cli",
        "config_store_id":store,"online":true,"loaded_native_thread_ids":["thread"],"native_process":native_identity(&host.native)}]);
    (h,claims,host)
}

#[test]
fn complete_inventory_allows_independent_reconciling_host_and_offline_claim() {
    let home=tempfile::tempdir().unwrap();let (mut h,mut claims,host)=fixture(home.path());
    inventory(&h,&claims,&[host.clone()],&[],[&process(1),&process(2)]).unwrap();
    claims[0]["online"]=json!(false);
    h["discoveryErrors"]=json!([{"source":"desktop","code":"unavailable"},{"source":"cli","code":"unavailable"}]);
    inventory(&h,&claims,&[host],&[],[&process(1),&process(2)]).unwrap();
}

#[test]
fn independently_authenticated_host_survives_lost_broker_subscription() {
    let home=tempfile::tempdir().unwrap();let (mut h,mut claims,host)=fixture(home.path());
    h["runtimes"][0]["online"]=json!(false);
    h["runtimes"][0]["inventoryReady"]=json!(false);
    claims[0]["online"]=json!(false);
    inventory(&h,&claims,&[host],&[],[&process(1),&process(2)]).unwrap();
}

#[test]
fn mixed_inventory_retains_native_desktop_and_cli_processes() {
    let home=tempfile::tempdir().unwrap();let (mut h,mut claims,host)=fixture(home.path());
    let mut external=Vec::new();
    for (id,source,role,pid) in [("cli","Cli","cliDaemon",5),("desktop","Desktop","desktopFollower",6)] {
        h["runtimes"].as_array_mut().unwrap().push(json!({"id":id,"source":source.to_lowercase(),"role":{"kind":role},"online":true,"inventoryReady":true}));
        claims.as_array_mut().unwrap().push(json!({"id":{"computer_id":"computer","runtime_id":id},"source":source,
            "config_store_id":"external-store","online":true,"loaded_native_thread_ids":[id],"native_process":native_identity(&process(pid))}));
        external.push(ExternalProof {runtime:id.into(),source:source.to_lowercase(),role:role.into(),store:"external-store".into(),native:process(pid)});
    }
    inventory(&h,&claims,&[host.clone()],&external,[&process(1),&process(2)]).unwrap();
    // The old desktop's native process can survive after its proxy exits.
    // Updating Pocket must preserve it, even though its session claims remain
    // uncertain. This does not grant any controller permission to execute.
    let mut disconnected=h.clone();let mut retained=claims.clone();
    disconnected["runtimes"][1]["online"]=json!(false);
    disconnected["runtimes"][1]["inventoryReady"]=json!(false);
    retained[1]["online"]=json!(false);
    inventory(&disconnected,&retained,&[host.clone()],&external,[&process(1),&process(2)]).unwrap();
    // Disconnect between reading live health and durable ownership is normal.
    // Preservation depends on process identity, not synchronized transport flags.
    inventory(&disconnected,&claims,&[host.clone()],&external,[&process(1),&process(2)]).unwrap();
    // Ordinary users need not initialize the assistant before updating Pocket.
    let mut external_only=disconnected.clone();
    let mut external_claims=retained.clone();
    external_only["runtimes"].as_array_mut().unwrap().remove(0);
    external_claims.as_array_mut().unwrap().remove(0);
    inventory(&external_only,&external_claims,&[],&external,[&process(1),&process(2)]).unwrap();
    assert!(inventory(&external_only,&external_claims,&[],&[],[&process(1),&process(2)]).is_err());
    for fault in ["missing-claim","pid-reused","role-changed","source-changed","owned-by-pocket","missing-proof"] {
        let mut changed=h.clone();let mut claims=claims.clone();let mut external=external.clone();
        match fault {
            "missing-claim"=>{claims.as_array_mut().unwrap().pop();},
            "pid-reused"=>claims[1]["native_process"]["createdAt"]=json!(999),
            "role-changed"=>changed["runtimes"][1]["role"]["kind"]=json!("unknown"),
            "source-changed"=>changed["runtimes"][1]["source"]=json!("desktop"),
            "owned-by-pocket"=>external[0].native=process(2),
            "missing-proof"=>{external.pop();},
            _=>unreachable!(),
        }
        assert!(inventory(&changed,&claims,&[host.clone()],&external,[&process(1),&process(2)]).is_err(),"{fault}");
    }
}

#[test]
fn no_managed_host_directory_is_a_valid_empty_registry() {
    let state=tempfile::tempdir().unwrap();
    assert!(registry_paths(state.path()).unwrap().is_empty());
}

#[test]
fn inventory_rejects_unknown_missing_or_replaced_owners() {
    let home=tempfile::tempdir().unwrap();let (h,claims,host)=fixture(home.path());
    for fault in ["extra-runtime","auxiliary-runtime","missing-inventory","replaced-generation",
        "wrong-role","extra-claim","missing-claim","missing-native","reused-native-pid",
        "foreign-computer","foreign-store","auxiliary-claim","duplicate-claim","duplicate-host","shared-process","pocket-owner"] {
        let mut h=h.clone();let mut claims=claims.clone();let mut hosts=vec![host.clone()];
        match fault {
            "extra-runtime"=>h["runtimes"].as_array_mut().unwrap().push(json!({"id":"unknown"})),
            "auxiliary-runtime"=>h["auxiliaryRuntimes"]=json!([{"id":"unproved-auxiliary"}]),
            "missing-inventory"=>{h.as_object_mut().unwrap().remove("auxiliaryRuntimes");},
            "replaced-generation"=>h["runtimes"][0]["role"]["generation"]=json!("replacement"),
            "offline-runtime"=>h["runtimes"][0]["online"]=json!(false),
            "wrong-role"=>h["runtimes"][0]["role"]["kind"]=json!("cliDaemon"),
            "extra-claim"=>{let mut extra=claims[0].clone();extra["id"]["runtime_id"]=json!("unknown");claims.as_array_mut().unwrap().push(extra);},
            "missing-claim"=>claims=json!([]),
            "missing-native"=>claims[0]["native_process"]=Value::Null,
            "reused-native-pid"=>claims[0]["native_process"]["createdAt"]=json!(999),
            "foreign-computer"=>claims[0]["id"]["computer_id"]=json!("other"),
            "foreign-store"=>claims[0]["config_store_id"]=json!("other"),
            "auxiliary-claim"=>claims[0]["auxiliary"]=json!(true),
            "duplicate-claim"=>{let duplicate=claims[0].clone();claims.as_array_mut().unwrap().push(duplicate);},
            "duplicate-host"=>hosts.push(host.clone()),
            "shared-process"=>hosts[0].native=hosts[0].host.clone(),
            "pocket-owner"=>hosts[0].host=process(1),
            _=>unreachable!(),
        }
        assert!(inventory(&h,&claims,&hosts,&[],[&process(1),&process(2)]).is_err(),"accepted {fault}");
    }
}

fn descriptor_and_health() -> (Descriptor,Health) {
    let host=native_identity(&process(3));let native=native_identity(&process(4));
    let d=Descriptor {protocol:2,runtime_id:"managed".into(),generation:uuid::Uuid::new_v4().to_string(),pid:3,native_pid:4,
        host_process:Some(host.clone()),native_process:Some(native.clone()),port:1,token:"a".repeat(64),
        config_home:std::env::current_dir().unwrap(),source:"cli".into()};
    let h=Health {protocol:2,runtime_id:d.runtime_id.clone(),generation:d.generation.clone(),pid:3,native_pid:4,
        host_process:Some(host),native_process:Some(native),initialized:true,capabilities:Default::default(),active_turns:1,
        pending_requests:0,reconciliation_required:true,scoped_admission:0};
    (d,h)
}

#[test]
fn active_native_work_and_historical_reconciliation_require_survival_capability() {
    let (d,h)=descriptor_and_health();healthy(&d,&h).unwrap();
    for fault in ["generation","pid","created","missing-native","not-initialized","pending-question","no-survival","no-recovery","untrusted"] {
        let mut h=h.clone();
        match fault {
            "generation"=>h.generation="replaced".into(),
            "pid"=>h.pid+=1,
            "created"=>h.native_process.as_mut().unwrap().created_at+=1,
            "missing-native"=>h.native_process=None,
            "not-initialized"=>h.initialized=false,
            "pending-question"=>h.pending_requests=1,
            "no-survival"=>h.capabilities.survives_broker_disconnect=false,
            "no-recovery"=>h.capabilities.persisted_recovery=false,
            "untrusted"=>h.capabilities.trusted_broker_only=false,
            _=>unreachable!(),
        }
        assert!(healthy(&d,&h).is_err(),"accepted {fault}");
    }
}

#[test]
fn crash_resume_retains_host_generation_creation_and_recovery_identity() {
    let home=tempfile::tempdir().unwrap();let (_,_,host)=fixture(home.path());
    let proof=Proof{hosts:vec![host],external:vec![]};
    let saved=serde_json::to_vec(&proof).unwrap();
    assert!(!String::from_utf8_lossy(&saved).contains("token"));
    let loaded:Proof=serde_json::from_slice(&saved).unwrap();loaded.require_retained(&proof).unwrap();
    for fault in ["removed-host","generation","host-created","native-created","unknown-deleted","unknown-replaced","startup-replaced"] {
        let mut changed=proof.clone();
        match fault {
            "removed-host"=>changed.hosts.clear(),
            "generation"=>changed.hosts[0].generation="new".into(),
            "host-created"=>changed.hosts[0].host.created_at+=1,
            "native-created"=>changed.hosts[0].native.created_at+=1,
            "unknown-deleted"=>changed.hosts[0].retained.clear(),
            "unknown-replaced"=>{*changed.hosts[0].retained.values_mut().next().unwrap()="replacement".into();},
            "startup-replaced"=>changed.hosts[0].startup.reservation="replacement".into(),
            _=>unreachable!(),
        }
        assert!(loaded.require_retained(&changed).is_err(),"accepted {fault}");
    }
    let mut added=proof.clone();added.hosts[0].retained.insert("commands:s:new".into(),"run".into());
    loaded.require_retained(&added).unwrap();
}

#[test]
fn recovery_requires_original_record_identities_and_explicit_completion_state() {
    let (d,_)=descriptor_and_health();
    let value=json!({"schema":2,"runtime_id":d.runtime_id,"generation":d.generation,"reconciliation_required":true,
        "commands":{"s:unknown":{"generation":"old","completed":false}},"answers":{}});
    assert_eq!(retained_records(&value,&d).unwrap().len(),1);
    for field in ["schema","generation","reconciliation_required","commands","answers"] {
        let mut bad=value.clone();bad[field]=Value::Null;assert!(retained_records(&bad,&d).is_err(),"{field}");
    }
    let mut bad=value.clone();bad["commands"]["s:unknown"]["completed"]=Value::Null;
    assert!(retained_records(&bad,&d).is_err());
}

#[cfg(windows)]
#[test]
fn authenticated_transport_uses_only_host_status_and_rejects_replaced_identity() {
    use pocket_runtime_ipc::{read_frame,write_frame};
    let rt=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
    rt.block_on(async {
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (mut d,mut h)=descriptor_and_health();
        let native=pocket_core::native_process::NativeProcess::capture(std::process::id()).unwrap();
        d.pid=native.pid; d.native_pid=native.pid;d.host_process=Some(native.clone());d.native_process=Some(native.clone());
        d.port=listener.local_addr().unwrap().port();
        h.pid=d.pid;h.native_pid=d.native_pid;h.host_process=d.host_process.clone();h.native_process=d.native_process.clone();
        let auth=d.clone();
        let server=tokio::spawn(async move {
            let (socket,_)=listener.accept().await.unwrap();let (read,mut write)=socket.into_split();
            let mut read=tokio::io::BufReader::new(read);
            let hello=read_frame(&mut read).await.unwrap().unwrap();
            assert!(hello["token"]==auth.token && hello["generation"]==auth.generation);
            for _ in 0..2 {
                let request=read_frame(&mut read).await.unwrap().unwrap();
                assert_eq!(request["method"],"host/status");
                write_frame(&mut write,&json!({"id":request["id"],"result":h})).await.unwrap();
            }
        });
        let mut client=HostClient::attach(&d).await.unwrap();healthy(&d,&client.health().await.unwrap()).unwrap();
        server.await.unwrap();
        d.host_process.as_mut().unwrap().created_at+=1;
        assert!(HostClient::attach(&d).await.is_err());
    });
}

#[cfg(windows)]
#[test]
#[ignore = "explicit read-only production Host proof; never fences or stops a process"]
fn live_preservation_read_only() -> Result<()> {
    let root=PathBuf::from(std::env::var_os("POCKET_QA_PRESERVATION_ROOT").context("select installation root explicitly")?);
    let (bin,state)=crate::launcher::resolve_release(&root)?;
    let generation=crate::installation::generation(&bin)?;
    let h=crate::native_update::authenticated_helper(&root,&state,&generation)?;
    println!("discovery_diagnostics={}",h["discoveryErrors"]);
    let supervisor:Value=storage::read(&storage::control(&state).join("supervisor.json"))?;
    let sup=ProcessIdentity::capture(super::super::pid(&supervisor["pid"])?)?;
    let agent=ProcessIdentity::capture(super::super::pid(&h["helperRuntime"]["pid"])?)?;
    ensure!(supervisor["childPid"]==agent.pid,"probe_owner_changed");
    let unknowns=super::super::persisted_unknowns(&state)?;
    let proof=prove(&root,&state,&h,[&sup,&agent])?;
    let h=crate::native_update::authenticated_helper(&root,&state,&generation)?;
    proof.verify_inventory(&state,&h,[&sup,&agent])?;
    ensure!(unknowns==super::super::persisted_unknowns(&state)?,"probe_unknowns_changed");
    println!("preservation_read_only=passed hosts={} registered={} claims={} pocket_unknown={} writes_unchanged=true",
        proof.hosts.len(),h["runtimes"].as_array().map_or(0,Vec::len),
        private_json(&state.join("ownership.json"))?.as_array().map_or(0,Vec::len),unknowns.len());
    for p in &proof.hosts {
        let recovery=private_json(&p.descriptor.parent().unwrap().join("recovery.json"))?;
        let unknown=|field:&str|recovery[field].as_object().map_or(0,|m|m.values().filter(|v|v["completed"]==false).count());
        println!("runtime={} generation={} host_pid={} host_created={} native_pid={} native_created={} pocket_owner_lifetime=verified_launch_contract reconciliation={} unknown_commands={} unknown_answers={}",
            p.runtime,p.generation,p.host.pid,p.host.created_at,p.native.pid,p.native.created_at,
            recovery["reconciliation_required"],unknown("commands"),unknown("answers"));
    }
    Ok(())
}
