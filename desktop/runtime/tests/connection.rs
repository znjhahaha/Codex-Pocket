use pocket_desktop_runtime::connection::{self,OperationError};
use pocket_desktop_runtime::storage::Desired;
use serde_json::json;

#[test]
fn connected_requires_current_supervisor_and_authoritative_helper(){
    let supervisor=json!({"phase":"running","updatedAt":100});
    let helper=json!({"state":"connected","connected":true,"updatedAt":100});
    let desired=Desired{enabled:true,changed_at:90};
    assert_eq!(connection::phase(&desired,true,&supervisor,&helper,100),"connected");
    assert_eq!(connection::phase(&desired,true,&supervisor,&json!({"state":"connected","connected":true,"updatedAt":30}),100),"recovering");
    assert_eq!(connection::phase(&desired,false,&supervisor,&helper,100),"failed");
    assert_eq!(connection::phase(&Desired::default(),true,&supervisor,&helper,100),"stopped");
    assert_eq!(connection::phase(&desired,true,&json!({"updatedAt":20}),&helper,100),"starting");
}

#[test]
fn startup_without_a_supervisor_times_out_at_thirty_seconds(){
    let desired=Desired{enabled:true,changed_at:100};
    for elapsed in [0,29] {
        assert_eq!(connection::phase(&desired,true,&json!(null),&json!(null),100+elapsed),"starting");
    }
    for elapsed in [30,31,86_400] {
        assert_eq!(connection::phase(&desired,true,&json!(null),&json!(null),100+elapsed),"failed");
    }
}

#[test]
fn lost_supervisor_heartbeat_recovers_without_reentering_startup(){
    let desired=Desired{enabled:true,changed_at:100};
    let supervisor=json!({"phase":"running","updatedAt":105});
    let helper=|now|json!({"state":"connected","connected":true,"updatedAt":now});
    assert_eq!(connection::phase(&desired,true,&supervisor,&helper(110),110),"connected");
    // The heartbeat is stale within the initial 30-second window and long afterwards.
    for now in [116,200,100_000] {
        assert_eq!(connection::phase(&desired,true,&supervisor,&helper(now),now),"recovering");
    }
    assert_eq!(connection::phase(&desired,true,&json!({"phase":"running","updatedAt":201}),&helper(201),201),"connected");
}

#[test]
fn a_previous_supervisor_record_does_not_extend_a_new_start_deadline(){
    let desired=Desired{enabled:true,changed_at:100};
    let supervisor=json!({"phase":"running","updatedAt":50});
    assert_eq!(connection::phase(&desired,true,&supervisor,&json!(null),129),"starting");
    assert_eq!(connection::phase(&desired,true,&supervisor,&json!(null),130),"failed");
}

#[test]
fn missing_future_or_ancient_enable_times_do_not_stay_starting(){
    for changed_at in [0,200] {
        assert_eq!(connection::phase(&Desired{enabled:true,changed_at},true,&json!(null),&json!(null),100),"failed");
    }
    assert_eq!(connection::phase(&Desired{enabled:true,changed_at:1},true,&json!(null),&json!(null),100_000_000),"failed");
}
#[test]
fn errors_have_safe_stage_code_and_unique_operation_identity(){
    let first=OperationError::new("pairing_payload","pairing_payload_invalid",false);
    let second=OperationError::new("pairing_payload","pairing_payload_invalid",false);
    let value=serde_json::to_value(&first).unwrap();
    assert_eq!(value["stage"],"pairing_payload");
    assert_eq!(value["code"],"pairing_payload_invalid");
    assert_ne!(first.operation_id,second.operation_id);
    assert!(value["message"].as_str().unwrap().contains("配对"));
}

#[test]
fn loopback_gateway_alone_is_not_a_phone_route(){
    let report=json!({"available":true,"routes":[],"routeStatus":[{"id":"gateway","listenerReady":true}]});
    let result=connection::route_readiness(&report,&json!(null),100);
    assert_eq!(result.configured,Some(0));
    assert!(result.ready_ids.is_empty());
    let helper=json!({"updatedAt":100,"state":"ready","routes":[],"usableRoutes":0});
    assert_eq!(connection::phase(&Desired{enabled:true,changed_at:90},true,&json!({"phase":"running","updatedAt":100}),&helper,100),"setup_required");
}

#[test]
fn current_route_policy_must_have_a_ready_route_before_reporting_connected(){
    let desired=Desired{enabled:true,changed_at:90};
    let supervisor=json!({"phase":"running","updatedAt":100});
    let helper=json!({"updatedAt":100,"state":"connected","connected":true,"usableRoutes":0,"routes":[{"kind":"lan"}]});
    assert_ne!(connection::phase(&desired,true,&supervisor,&helper,100),"connected");
    let ready=json!({"updatedAt":100,"state":"ready","connected":false,"usableRoutes":1,"routes":[{"kind":"lan"}]});
    assert_eq!(connection::phase(&desired,true,&supervisor,&ready,100),"ready");
}

#[test]
fn usable_routes_require_matching_live_evidence_and_policy(){
    let mut report=json!({"policy":"automatic","routes":[
        {"id":"lan","kind":"lan","url":"tls://192.168.1.2:43821"},
        {"id":"relay","kind":"relay","url":"wss://example.test/pocket/v1/tunnel/local/phone"}],
        "routeStatus":[{"id":"lan","kind":"lan","phase":"listening","listenerReady":true,"endpoint":"tls://192.168.1.2:43821"},
        {"id":"relay","kind":"relay","phase":"connecting","outerConnected":false,"endpoint":"wss://example.test/pocket/v1/tunnel/local/phone"}]});
    assert_eq!(connection::route_readiness(&report,&json!(null),100).ready_ids,vec!["lan"]);
    report["policy"]=json!("public");
    assert!(connection::route_readiness(&report,&json!(null),100).ready_ids.is_empty());
    report["routeStatus"][1]["phase"]=json!("connected");
    report["routeStatus"][1]["outerConnected"]=json!(true);
    assert_eq!(connection::route_readiness(&report,&json!(null),100).ready_ids,vec!["relay"]);
    report["routeStatus"][1]["endpoint"]=json!("wss://old.example/pocket/v1/tunnel/local/phone");
    assert!(connection::route_readiness(&report,&json!(null),100).ready_ids.is_empty());
    report["routeStatus"][1]["endpoint"]=report["routes"][1]["url"].clone();
    report["routes"][1]["expiresAt"]=json!(100);
    assert!(connection::route_readiness(&report,&json!(null),100).ready_ids.is_empty());
    report["policy"]=json!("automatic");
    report["routeStatus"][0]["endpoint"]=json!("tls://192.168.1.3:43821");
    assert!(connection::route_readiness(&report,&json!(null),100).ready_ids.is_empty());
}

#[test]
fn cloudflare_needs_current_tunnel_and_matching_public_address(){
    let report=json!({"routes":[{"id":"cf","kind":"cloudflare","url":"wss://current.example/v1/tunnel/local/phone"}],
        "routeStatus":[{"id":"gateway","listenerReady":true,"phase":"ready"}]});
    let mut tunnel=json!({"enabled":true,"phase":"starting","updatedAt":100,"publicUrl":"wss://current.example"});
    assert!(connection::route_readiness(&report,&tunnel,100).ready_ids.is_empty());
    tunnel["phase"]=json!("tunnel_connected");
    assert_eq!(connection::route_readiness(&report,&tunnel,100).ready_ids,vec!["cf"]);
    assert!(connection::route_readiness(&report,&tunnel,111).ready_ids.is_empty());
    tunnel["publicUrl"]=json!("wss://previous.example");
    assert!(connection::route_readiness(&report,&tunnel,100).ready_ids.is_empty());
}

#[test]
fn pairing_distinguishes_missing_setup_from_unready_routes_and_helper(){
    let desired=Desired{enabled:true,changed_at:90};
    let supervisor=json!({"phase":"running","updatedAt":100});
    let mut helper=json!({"state":"ready","updatedAt":100,"routes":[],"routeStatus":[]});
    let check=|h:&serde_json::Value|connection::ensure_pairable(&desired,&supervisor,h,&json!(null),100).unwrap_err().code;
    assert_eq!(check(&helper),"setup_required");
    helper["routes"]=json!([{"id":"lan","kind":"lan","url":"tls://192.168.1.2:43821"}]);
    assert_eq!(check(&helper),"route_not_ready");
    helper["routeStatus"]=json!([{"id":"lan","kind":"lan","phase":"listening","listenerReady":true,"endpoint":"tls://192.168.1.2:43821"}]);
    assert!(connection::ensure_pairable(&desired,&supervisor,&helper,&json!(null),100).is_ok());
    helper["routes"][0]["url"]=json!("tls://192.168.1.2:43821/");
    assert!(connection::ensure_pairable(&desired,&supervisor,&helper,&json!(null),100).is_ok());
    helper["updatedAt"]=json!(50);
    assert_eq!(check(&helper),"helper_unavailable");
}

#[test]
fn relay_setup_preserves_other_routes_and_derives_local_identity(){
    let original=json!({"policy":"lan","lan":{"bind":"192.168.1.2:43821","mdns":true},"gateway":{"bind":"127.0.0.1:17892"},"routes":[{"id":"old","kind":"relay","url":"wss://old.example/v1/tunnel/local/phone","priority":100}]});
    let next=connection::relay_preferences(&original," wss://relay.example/pocket/ ","local").unwrap();
    assert_eq!(next["lan"],original["lan"]);
    assert_eq!(next["gateway"],original["gateway"]);
    assert_eq!(next["routes"][0],original["routes"][0]);
    assert_eq!(next["policy"],"automatic");
    assert_eq!(next["routes"][1]["url"],"wss://relay.example/pocket/v1/tunnel/local/phone");
    assert_eq!(connection::relay_preferences(&next,"wss://relay.example/pocket","local").unwrap(),next);
    assert!(connection::relay_preferences(&original,"wss://relay.example","local").is_ok());
    for invalid in ["https://relay.example", "wss://user:secret@relay.example", "wss://relay.example?token=secret", "wss://relay.example/v1/tunnel/foreign/phone", "wss://relay.example/#token", "wss://relay.example/\npath"] {
        assert_eq!(connection::relay_preferences(&original,invalid,"local").unwrap_err().code,"invalid_relay_url");
    }
    assert!(connection::relay_preferences(&original,"wss://relay.example","../foreign").is_err());
}
