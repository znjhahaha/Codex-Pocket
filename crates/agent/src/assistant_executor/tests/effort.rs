use super::*;

fn catalog() -> Value {
    json!({"data":[
        {"id":"work","model":"work","hidden":false,"isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"high"},{"reasoningEffort":"max"}],"defaultReasoningEffort":"high"},
        {"id":"chief","model":"chief","hidden":false,"supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"high"}]},
        {"id":"override","model":"override","hidden":false,"supportedReasoningEfforts":[{"reasoningEffort":"high"}]}
    ],"nextCursor":null})
}

#[test]
fn effort_role_selection_reaches_exact_native_turn_and_legacy_hash_stays_stable() {
    let mut f = Fixture::new();
    f.executor.runtime.models = catalog();
    let settings:Settings=serde_json::from_value(json!({"personality":"Helpful","tone":"Brief","timezone":"UTC","notifications":crate::assistant::NotificationSettings::default(),
        "model":"work","coordinator_model":"chief","effort":"high","coordinator_effort":"low"})).unwrap();
    for (id, role, override_model, model, effort) in [
        ("chief", Role::Coordinator, None, "chief", "low"),
        ("work", Role::Execution, None, "work", "high"),
        (
            "override",
            Role::Execution,
            Some("override"),
            "override",
            "high",
        ),
    ] {
        let mut req = f.req(id, role);
        req.settings = settings.for_role(role, override_model);
        let mapping = f.executor.start(req.clone()).unwrap();
        let calls = f
            .executor
            .runtime
            .calls
            .iter()
            .filter(|(_, method, _)| method == "turn/start")
            .collect::<Vec<_>>();
        let params = &calls.last().unwrap().2;
        assert_eq!(params["model"], model);
        assert_eq!(params["effort"], effort);
        assert_eq!(params["threadId"], mapping.thread_id);
        assert_eq!(params["clientUserMessageId"], id);
        let native_schema = schema("v2/TurnStartParams");
        assert!(conforms(params, &native_schema, &native_schema));
        assert_eq!(f.executor.start(req).unwrap(), mapping);
    }
    assert_eq!(
        f.executor
            .runtime
            .calls
            .iter()
            .filter(|(_, method, _)| method == "turn/start")
            .count(),
        3
    );
    let req = f.req("legacy", Role::Coordinator);
    let serialized = serde_json::to_value(&req.settings).unwrap();
    assert!(serialized.get("effort").is_none());
    assert!(serialized.get("coordinator_effort").is_none());
    #[derive(serde::Serialize)]
    struct LegacySettings<'a> {
        personality: &'a str,
        tone: &'a str,
        model: &'a Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        coordinator_model: &'a Option<String>,
        timezone: &'a str,
        notifications: &'a crate::assistant::NotificationSettings,
    }
    let legacy = LegacySettings {
        personality: &req.settings.personality,
        tone: &req.settings.tone,
        model: &req.settings.model,
        coordinator_model: &req.settings.coordinator_model,
        timezone: &req.settings.timezone,
        notifications: &req.settings.notifications,
    };
    assert_eq!(
        request_hash(&req).unwrap(),
        hash(&(
            &req.operation_id,
            &req.host_generation,
            req.role,
            &req.thread_id,
            &req.prompt,
            &req.directory,
            req.writes,
            &legacy,
            &req.instructions
        ))
        .unwrap()
    );
}

#[test]
fn effort_unsupported_or_unpublished_rejects_before_native_effect_and_stays_terminal() {
    let mut f = Fixture::new();
    f.executor.runtime.models = catalog();
    let mut req = f.req("unsupported", Role::Coordinator);
    req.settings=serde_json::from_value(json!({"personality":"Helpful","tone":"Brief","timezone":"UTC","notifications":crate::assistant::NotificationSettings::default(),"model":"chief","effort":"max"})).unwrap();
    assert!(f.executor.start(req.clone()).is_err());
    assert!(
        f.executor
            .runtime
            .calls
            .iter()
            .all(|(_, m, _)| !matches!(m.as_str(), "thread/start" | "turn/start"))
    );
    assert_eq!(
        generation_status(&mut f, "unsupported", "host-1").unwrap(),
        ExecutionStatus::NotDispatched
    );
    f = generation_reopen(f, "host-1");
    f.executor.runtime.models["data"][1]["supportedReasoningEfforts"] =
        json!([{"reasoningEffort":"max"}]);
    assert!(
        f.executor.start(req.clone()).is_err(),
        "Do not reopen a terminal rejected operation"
    );
    req.operation_id = "fresh-operation".into();
    assert!(f.executor.start(req).is_ok());
    let mut req = f.req("private-unpublished", Role::Execution);
    req.settings.model = Some("private".into());
    req.settings = serde_json::from_value({
        let mut value = serde_json::to_value(req.settings).unwrap();
        value["effort"] = json!("high");
        value
    })
    .unwrap();
    f.executor.runtime.configured_model = Some("private".into());
    assert!(
        f.executor.start(req).is_err(),
        "Config selection alone cannot prove effort support"
    );
}

#[test]
fn effort_inherited_profile_model_is_verified_and_bound_without_replacing_custom_provider() {
    let mut f = Fixture::new();
    f.executor.runtime.models = catalog();
    f.executor.runtime.configured_model = Some("chief".into());
    let mut req = f.req("inherited-effort", Role::Coordinator);
    req.settings = serde_json::from_value({
        let mut value = serde_json::to_value(req.settings).unwrap();
        value["effort"] = json!("low");
        value
    })
    .unwrap();
    f.executor.start(req).unwrap();
    let params = &f
        .executor
        .runtime
        .calls
        .iter()
        .find(|(_, m, _)| m == "turn/start")
        .unwrap()
        .2;
    assert_eq!(params["model"], "chief");
    assert_eq!(params["effort"], "low");
}
