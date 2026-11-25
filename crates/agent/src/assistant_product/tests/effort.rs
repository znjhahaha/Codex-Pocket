use super::*;

fn catalog() -> Value {
    json!({"data":[
        {"id":"work","model":"work","hidden":false,"isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"high"},{"reasoningEffort":"max"}]},
        {"id":"chief","model":"chief","hidden":false,"supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"high"}]}
    ],"nextCursor":null})
}

#[tokio::test]
async fn effort_settings_restart_and_exact_role_turns_keep_independent_inheritance() {
    let mut f = Fixture::new(false).await;
    f.io.0.lock().unwrap().models = Some(catalog());
    let patch = json!({"patch":{"model":"work","coordinator_model":"chief","effort":"high","coordinator_effort":"low"}});
    f.call(
        "assistant/settings/update",
        patch.clone(),
        "role-efforts",
        false,
    )
    .await
    .unwrap();
    f.call("assistant/settings/update", patch, "role-efforts", false)
        .await
        .unwrap();
    assert_eq!(f.io.count("turn/start"), 0);
    f.stop().await;
    f.start().await;
    let saved = f.read().await;
    assert_eq!(saved["settings"]["effort"], "high");
    assert_eq!(saved["settings"]["coordinator_effort"], "low");
    let s = f.send("effort-first").await;
    let cm = Fixture::mapping(&Fixture::op(&s, "Coordinator"));
    f.io.tool(json!(101), &cm, "effort-dispatch", "assistant_dispatch_session",
        json!({"title":"Work","prompt":"Read the project","directory":f.project.path(),"writes":false}));
    let s = f.read().await;
    let em = Fixture::mapping(&Fixture::op(&s, "Execution"));
    {
        let state = f.io.0.lock().unwrap();
        for (mapping, model, effort) in [(&cm, "chief", "low"), (&em, "work", "high")] {
            let params = &state
                .calls
                .iter()
                .find(|(_, method, p)| method == "turn/start" && p["threadId"] == mapping.thread_id)
                .unwrap()
                .2;
            assert_eq!(params["model"], model);
            assert_eq!(params["effort"], effort);
        }
    }
    f.call(
        "assistant/settings/update",
        json!({"patch":{"coordinator_effort":null}}),
        "inherit-effort",
        false,
    )
    .await
    .unwrap();
    f.io.complete(&cm, "Delegated");
    f.read().await;
    f.send("effort-second").await;
    {
        let state = f.io.0.lock().unwrap();
        let params = &state
            .calls
            .iter()
            .rev()
            .find(|(_, method, p)| method == "turn/start" && p["threadId"] == cm.thread_id)
            .unwrap()
            .2;
        assert_eq!(params["model"], "chief");
        assert_eq!(params["effort"], "high");
        assert_eq!(
            state
                .calls
                .iter()
                .filter(|(_, m, p)| m == "turn/start" && p["threadId"] == em.thread_id)
                .count(),
            1
        );
    }
    f.call(
        "assistant/settings/update",
        json!({"patch":{"effort":null}}),
        "default-effort",
        false,
    )
    .await
    .unwrap();
    let snapshot = f.read().await;
    let active = snapshot["operations"].as_array().unwrap().iter()
        .find(|op| op["id"] == snapshot["activeCoordinatorOperationId"]).unwrap();
    let cm = Fixture::mapping(active);
    f.io.complete(&cm, "Updated");
    f.read().await;
    f.send("effort-third").await;
    {
        let state = f.io.0.lock().unwrap();
        let params = &state
            .calls
            .iter()
            .rev()
            .find(|(_, method, p)| method == "turn/start" && p["threadId"] == cm.thread_id)
            .unwrap()
            .2;
        assert_eq!(params["model"], "chief");
        assert_eq!(params.get("effort"), Some(&Value::Null));
    }
    f.stop().await;
}

#[tokio::test]
async fn effort_settings_reject_unsupported_model_changes_and_keep_preferences_available() {
    let mut f = Fixture::new(false).await;
    f.io.0.lock().unwrap().models = Some(catalog());
    f.call(
        "assistant/settings/update",
        json!({"patch":{"model":"work","effort":"max"}}),
        "initial",
        false,
    )
    .await
    .unwrap();
    let patch = json!({"patch":{"coordinator_model":"chief"}});
    assert!(
        f.call(
            "assistant/settings/update",
            patch.clone(),
            "rejected",
            false
        )
        .await
        .is_err()
    );
    assert!(f.read().await["settings"]["coordinator_model"].is_null());
    f.io.0.lock().unwrap().models.as_mut().unwrap()["data"][1]["supportedReasoningEfforts"] =
        json!([{"reasoningEffort":"max"}]);
    f.call("assistant/settings/update", patch, "fresh-command", false)
        .await
        .unwrap();
    // A preference change needs no model capability lookup.
    f.io.0.lock().unwrap().models = Some(json!({"data":[],"nextCursor":null}));
    let reads = f.io.count("model/list");
    f.call(
        "assistant/settings/update",
        json!({"patch":{"tone":"brief"}}),
        "preference",
        false,
    )
    .await
    .unwrap();
    assert_eq!(f.io.count("model/list"), reads);
    let mut settings = f.read().await["settings"].clone();
    settings["effort"] = json!("high");
    assert!(
        f.call(
            "assistant/settings/update",
            json!({"settings":settings}),
            "full-invalid",
            false
        )
        .await
        .is_err()
    );
    assert_eq!(f.io.count("turn/start"), 0);
    f.stop().await;
}

#[tokio::test]
async fn effort_pending_settings_validate_and_clear_durably_before_lazy_dispatch() {
    let root = tempfile::tempdir().unwrap();
    let io = Fake::default();
    io.0.lock().unwrap().models = Some(catalog());
    io.0.lock().unwrap().configured_model = Some("chief".into());
    let settings = Settings {
        effort: Some("low".into()),
        ..Settings::default()
    };
    retained::save_pending_settings(
        root.path(),
        "assistant:profile:",
        Some(json!({"configStoreId":"profile","commandId":"pending-effort","settings":settings})),
    )
    .unwrap();
    let actor = Actor::start_with_io(
        root.path().into(),
        "computer".into(),
        "profile".into(),
        io.clone(),
        None,
        Arc::new(|| 1_000_000),
    )
    .await
    .unwrap();
    let saved = actor
        .request("assistant/read", json!({}), "read".into(), None)
        .await
        .unwrap();
    assert_eq!(saved["settings"]["effort"], "low");
    assert!(
        retained::pending_settings(root.path(), "assistant:profile:")
            .unwrap()
            .is_none()
    );
    assert_eq!(io.count("turn/start"), 0);
    actor.park_for_runtime_transition().await.unwrap();
    actor.stop_parked_worker().await.unwrap();
    // A legacy pending payload has no effort fields and therefore clears both.
    retained::save_pending_settings(root.path(), "assistant:profile:", Some(json!({"configStoreId":"profile","commandId":"pending-clear","settings":Settings::default()}))).unwrap();
    let actor = Actor::start_with_io(
        root.path().into(),
        "computer".into(),
        "profile".into(),
        io.clone(),
        None,
        Arc::new(|| 1_000_001),
    )
    .await
    .unwrap();
    let saved = actor
        .request("assistant/read", json!({}), "read".into(), None)
        .await
        .unwrap();
    assert!(saved["settings"]["effort"].is_null());
    assert!(saved["settings"]["coordinator_effort"].is_null());
    actor.park_for_runtime_transition().await.unwrap();
    actor.stop_parked_worker().await.unwrap();
}
