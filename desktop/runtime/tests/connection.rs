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
