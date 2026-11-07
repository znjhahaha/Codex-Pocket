use pocket_bridge::router::{Client, Route, Router};
use serde_json::{Value, json};

fn phone() -> Client {
    Client::Remote("test-phone".into())
}
fn sent(routes: &[Route]) -> bool {
    routes.iter().any(|r| matches!(r, Route::Runtime(_)))
}
fn request() -> Value {
    json!({"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread","turnId":"turn","itemId":"item"}})
}
#[test]
fn winning_arbitration_and_native_cancellation_are_only_submission_and_resolution_receipts() {
    for desktop_wins in [false, true] {
        let mut router = Router::default();
        let issued = router.from_runtime(request());
        let winner = if desktop_wins {
            Client::Desktop
        } else {
            phone()
        };
        let id = if desktop_wins {
            desktop_id(&issued)
        } else {
            remote_id(&issued)
        };
        let result = json!({"decision":if desktop_wins {"accept"}else{"decline"}});
        if !desktop_wins {
            router.from_runtime(json!({"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"turn","status":"interrupted"}}}));
        }
        let routes = router.from_client(winner, json!({"id":id,"result":result}));
        assert!(matches!(&routes[0],Route::Runtime(v) if v["result"]==result));
        let confirmation = routes.iter().find_map(|route| match route {
            Route::Client(client, event)
                if *client == phone() && event["params"]["winner"] == "thisConnection" =>
            {
                Some(event)
            }
            _ => None,
        });
        if desktop_wins {
            assert!(confirmation.is_none());
        } else {
            let confirmation =
                confirmation.expect("the winning connection needs an arbitration receipt");
            assert_eq!(confirmation["method"], "pocket/approvalSubmitted");
            assert_ne!(confirmation["params"]["nativeResolved"], true);
            assert_eq!(confirmation["params"]["requestId"], remote_id(&issued));
            assert_eq!(confirmation["params"]["response"], result);
        }
        assert!(
            routes
                .iter()
                .filter_map(|r| match r {
                    Route::Broadcast(v) => Some(v),
                    _ => None,
                })
                .all(|v| v["params"]["winner"].is_null())
        );
        let loser = if desktop_wins {
            phone()
        } else {
            Client::Desktop
        };
        let id = if desktop_wins {
            remote_id(&issued)
        } else {
            desktop_id(&issued)
        };
        assert!(!sent(&router.from_client(
            loser,
            json!({"id":id,"result":{"decision":"accept"}})
        )));
        // A native turn transition can clear this request without applying the
        // phone response. This notification carries no response-application proof.
        let native = router.from_runtime(
            json!({"method":"serverRequest/resolved","params":{"requestId":7,"threadId":"thread"}}),
        );
        let confirmation = native.iter().find_map(|route| match route {
            Route::Client(client, event)
                if *client == phone() && event["params"]["winner"] == "thisConnection" =>
            {
                Some(event)
            }
            _ => None,
        });
        if desktop_wins {
            assert!(confirmation.is_none());
        } else {
            let confirmation = confirmation
                .expect("the winning connection receives request closure, not acceptance");
            assert_eq!(confirmation["method"], "pocket/approvalResolved");
            assert_eq!(confirmation["params"]["requestId"], remote_id(&issued));
            assert_eq!(confirmation["params"]["response"], result);
            assert_eq!(confirmation["params"]["nativeResolved"], true);
        }
    }
}

#[test]
fn approval_native_resolution_receipt_requires_original_thread_and_connected_winner() {
    for disconnect in [false, true] {
        let mut router = Router::default();
        let issued = router.from_runtime(request());
        router.from_client(
            phone(),
            json!({"id":remote_id(&issued),"result":{"decision":"decline"}}),
        );
        let wrong = router.from_runtime(
            json!({"method":"serverRequest/resolved","params":{"requestId":7,"threadId":"other"}}),
        );
        assert!(!wrong.iter().any(
            |r| matches!(r,Route::Client(c,v) if *c==phone() && v["params"]["nativeResolved"]==true)
        ));
        if disconnect {
            router.disconnect(&phone());
        }
        let resolved = router.from_runtime(
            json!({"method":"serverRequest/resolved","params":{"requestId":7,"threadId":"thread"}}),
        );
        assert_eq!(resolved.iter().any(|r|matches!(r,Route::Client(c,v) if *c==phone() && v["params"]["nativeResolved"]==true)),!disconnect);
        let duplicate = router.from_runtime(
            json!({"method":"serverRequest/resolved","params":{"requestId":7,"threadId":"thread"}}),
        );
        assert!(!duplicate.iter().any(
            |r| matches!(r,Route::Client(c,v) if *c==phone() && v["params"]["nativeResolved"]==true)
        ));
    }
}
#[test]
fn reconnected_helper_can_snapshot_shareable_pending_requests() {
    let mut router = Router::default();
    let issued = router.from_runtime(request());
    router.from_runtime(
        json!({"id":"private","method":"item/tool/call","params":{"tool":"private-tool"}}),
    );
    router.disconnect(&phone());
    let next = Client::Remote("replacement".into());
    let routes = router.from_client(
        next.clone(),
        json!({"id":"snapshot","method":"pocket/approvals/list","params":{}}),
    );
    assert!(!sent(&routes));
    let Route::Client(client, response) = &routes[0] else {
        panic!("snapshot must stay on the authenticated connection")
    };
    assert_eq!(*client, next);
    let entries = response["result"]["data"]
        .as_array()
        .expect("pending requests must be recoverable");
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0]["id"], remote_id(&issued));
    assert_eq!(entries[0]["params"], request()["params"]);
    let answered = router.from_client(
        next,
        json!({"id":entries[0]["id"],"result":{"decision":"decline"}}),
    );
    assert!(matches!(&answered[0],Route::Runtime(v) if v["id"]==7));
}
#[test]
fn inactive_management_requires_exact_safe_one_use_history_proof() {
    for method in ["thread/name/set", "thread/archive", "thread/unarchive"] {
        let mut router = Router::default();
        let mut params = json!({"threadId":"history"});
        if method == "thread/name/set" {
            params["name"] = json!("new name");
        }
        let request = json!({"id":2,"method":method,"params":params});
        assert!(
            !sent(&router.from_client(phone(), request.clone())),
            "inactive management needs native proof"
        );
        history_read(
            &mut router,
            phone(),
            "history",
            json!(false),
            json!({"thread":{"id":"history"}}),
        );
        let mut altered = request.clone();
        altered["params"]["sandboxPolicy"] = json!({"type":"dangerFullAccess"});
        assert!(!sent(&router.from_client(phone(), altered)));
        let mut foreign = request.clone();
        foreign["params"]["threadId"] = json!("other");
        assert!(!sent(&router.from_client(phone(), foreign)));
        let routes = router.from_client(phone(), request.clone());
        assert!(matches!(&routes[0],Route::Runtime(value) if value["params"]==params));
        assert!(!sent(&router.from_client(phone(), request)));
        assert!(!router.loaded.contains("history"));
    }
}
fn desktop_id(routes: &[Route]) -> Value {
    routes
        .iter()
        .find_map(|r| match r {
            Route::Client(Client::Desktop, v) => Some(v["id"].clone()),
            _ => None,
        })
        .unwrap()
}
fn remote_id(routes: &[Route]) -> Value {
    routes
        .iter()
        .find_map(|r| match r {
            Route::Broadcast(v) => Some(v["id"].clone()),
            _ => None,
        })
        .unwrap()
}

#[test]
fn stale_desktop_response_cannot_answer_reused_native_id() {
    let mut r = Router::default();
    let first = r.from_runtime(request());
    r.from_client(
        phone(),
        json!({"id":remote_id(&first),"result":{"decision":"decline"}}),
    );
    let second = r.from_runtime(request());
    assert_ne!(desktop_id(&first), desktop_id(&second));
    assert!(!sent(&r.from_client(
        Client::Desktop,
        json!({"id":desktop_id(&first),"result":{"decision":"accept"}})
    )));
    assert_eq!(r.pending_approvals(), 1);
    let accepted = r.from_client(
        Client::Desktop,
        json!({"id":desktop_id(&second),"result":{"decision":"decline"}}),
    );
    assert!(matches!(&accepted[0], Route::Runtime(v) if v["id"]==7));
}

#[test]
fn malformed_approval_does_not_consume_first_valid_response() {
    for body in [
        json!({}),
        json!({"result":null}),
        json!({"result":{"decision":"bogus"}}),
        json!({"result":{"decision":"accept"},"error":{"code":1,"message":"bad"}}),
        json!({"error":{"code":"bad","message":1}}),
    ] {
        let mut r = Router::default();
        let issued = r.from_runtime(request());
        let mut response = body;
        response["id"] = remote_id(&issued);
        assert!(
            !sent(&r.from_client(phone(), response.clone())),
            "{response}"
        );
        assert_eq!(r.pending_approvals(), 1);
        assert!(sent(&r.from_client(
            Client::Desktop,
            json!({"id":desktop_id(&issued),"result":{"decision":"decline"}})
        )));
    }
}

#[test]
fn user_input_requires_answer_arrays_for_original_questions() {
    let mut r = Router::default();
    let issued=r.from_runtime(json!({"id":"input","method":"item/tool/requestUserInput","params":{"threadId":"thread","questions":[{"id":"q1"}]}}));
    let token = remote_id(&issued);
    for answers in [
        json!({"q1":{"answers":"text"}}),
        json!({"other":{"answers":["text"]}}),
        json!({}),
    ] {
        assert!(!sent(&r.from_client(
            phone(),
            json!({"id":token,"result":{"answers":answers}})
        )));
        assert_eq!(r.pending_approvals(), 1);
    }
    assert!(sent(&r.from_client(
        phone(),
        json!({"id":token,"result":{"answers":{"q1":{"answers":["text"]}}}})
    )));
}

#[test]
fn native_resolution_uses_desktop_alias_and_invalidates_both_clients() {
    let mut r = Router::default();
    let issued = r.from_runtime(request());
    let resolved = r.from_runtime(
        json!({"method":"serverRequest/resolved","params":{"requestId":7,"threadId":"thread"}}),
    );
    assert!(
        matches!(&resolved[0], Route::Client(Client::Desktop,v) if v["params"]["requestId"]==desktop_id(&issued))
    );
    assert_ne!(desktop_id(&issued), json!(7));
    assert!(!sent(&r.from_client(
        Client::Desktop,
        json!({"id":desktop_id(&issued),"result":{"decision":"accept"}})
    )));
    assert!(!sent(&r.from_client(
        phone(),
        json!({"id":remote_id(&issued),"result":{"decision":"accept"}})
    )));
}

#[test]
fn original_desktop_tool_callback_round_trips_native_id_and_payload() {
    let mut r = Router::default();
    let input = json!({"id":"native-tool","method":"item/tool/call","params":{"threadId":"thread","turnId":"turn","itemId":"item","tool":"open_file"}});
    let issued = r.from_runtime(input.clone());
    assert_eq!(issued.len(), 1);
    let Route::Client(Client::Desktop, forwarded) = &issued[0] else {
        panic!()
    };
    assert_eq!(forwarded["params"], input["params"]);
    assert!(!sent(&r.from_client(
        phone(),
        json!({"id":desktop_id(&issued),"result":{"success":true}})
    )));
    let result = json!({"success":true,"contentItems":[{"type":"inputText","text":"ok"}]});
    let response = r.from_client(
        Client::Desktop,
        json!({"id":desktop_id(&issued),"result":result}),
    );
    assert!(
        matches!(&response[0], Route::Runtime(v) if v["id"]=="native-tool" && v["result"]==result)
    );
}

#[test]
fn original_desktop_error_reply_passthrough_accepts_native_error_shape() {
    let mut r = Router::default();
    let issued = r.from_runtime(json!({
        "id":"native-tool",
        "method":"item/tool/call",
        "params":{"threadId":"thread","turnId":"turn","itemId":"item"}
    }));
    let response = r.from_client(
        Client::Desktop,
        json!({"id":desktop_id(&issued),"error":{"code":"tool_failed","message":"native error"}}),
    );
    assert!(
        matches!(&response[0], Route::Runtime(v) if v["id"]=="native-tool" && v["error"]["code"]=="tool_failed")
    );
}

#[test]
fn requested_methods_and_safe_owned_resume_are_forwarded() {
    let mut r = Router::default();
    r.from_runtime(json!({"method":"thread/started","params":{"thread":{"id":"thread"}}}));
    for method in [
        "collaborationMode/list",
        "thread/unarchive",
        "thread/turns/list",
        "thread/items/list",
        "thread/resume",
    ] {
        let params = if method == "thread/resume" {
            json!({"threadId":"thread","excludeTurns":true})
        } else {
            json!({"threadId":"thread"})
        };
        let routes = r.from_client(phone(), json!({"id":1,"method":method,"params":params}));
        assert!(sent(&routes), "{method}");
    }
    assert!(!sent(&r.from_client(
        phone(),
        json!({"id":2,"method":"thread/resume","params":{"threadId":"foreign"}})
    )));
}

#[test]
fn remote_resume_rejects_overrides_and_malformed_safe_fields() {
    let mut r = Router::default();
    r.from_runtime(json!({"method":"thread/started","params":{"thread":{"id":"thread"}}}));
    for params in [
        json!({"threadId":"thread","approvalPolicy":"never"}),
        json!({"threadId":"thread","sandbox":"danger-full-access"}),
        json!({"threadId":"thread","cwd":"elsewhere"}),
        json!({"threadId":"thread","excludeTurns":"yes"}),
        json!({"threadId":"thread","config":{}}),
    ] {
        assert!(!sent(&r.from_client(
            phone(),
            json!({"id":1,"method":"thread/resume","params":params})
        )));
    }
    let params = json!({"threadId":"thread","cwd":"original-desktop-choice"});
    let desktop = r.from_client(
        Client::Desktop,
        json!({"id":2,"method":"thread/resume","params":params}),
    );
    assert!(matches!(&desktop[0], Route::Runtime(v) if v["params"]==params));
}

#[test]
fn desktop_winner_still_receives_native_resolution_with_its_alias() {
    let mut r = Router::default();
    let issued = r.from_runtime(request());
    r.from_client(
        Client::Desktop,
        json!({"id":desktop_id(&issued),"result":{"decision":"accept"}}),
    );
    let resolved = r.from_runtime(
        json!({"method":"serverRequest/resolved","params":{"requestId":7,"threadId":"thread"}}),
    );
    assert!(resolved.iter().any(|route|matches!(route,Route::Client(Client::Desktop,v) if v["params"]["requestId"]==desktop_id(&issued))));
}

#[test]
fn native_version_keeps_user_agent_fallback_and_explicit_version() {
    for (result, expected) in [
        (json!({"userAgent":"codex/0.164.0"}), "codex/0.164.0"),
        (
            json!({"userAgent":"codex/0.164.0","version":"0.164.0"}),
            "0.164.0",
        ),
    ] {
        let mut r = Router::default();
        let routed = r.from_client(Client::Desktop, json!({"id":1,"method":"initialize"}));
        let Route::Runtime(v) = &routed[0] else {
            panic!()
        };
        r.from_runtime(json!({"id":v["id"],"result":result}));
        assert_eq!(r.native_version.as_deref(), Some(expected));
    }
}

fn history_read(r: &mut Router, client: Client, thread: &str, include: Value, result: Value) {
    let routes = r.from_client(
        client,
        json!({"id":1,"method":"thread/read","params":{"threadId":thread,"includeTurns":include}}),
    );
    let Route::Runtime(v) = &routes[0] else {
        panic!("read rejected")
    };
    r.from_runtime(json!({"id":v["id"],"result":result}));
}
fn historical_resume(r: &mut Router, client: Client) -> Vec<Route> {
    r.from_client(client,json!({"id":2,"method":"thread/resume","params":{"threadId":"history","excludeTurns":true}}))
}
#[test]
fn native_history_proof_allows_one_safe_resume_for_the_same_peer_only() {
    let mut r = Router::default();
    history_read(
        &mut r,
        phone(),
        "history",
        json!(false),
        json!({"thread":{"id":"history"}}),
    );
    assert!(!sent(&historical_resume(
        &mut r,
        Client::Remote("other".into())
    )));
    let routes = historical_resume(&mut r, phone());
    assert!(
        matches!(&routes[0],Route::Runtime(v) if v["params"]==json!({"threadId":"history","excludeTurns":true}))
    );
    assert!(!sent(&historical_resume(&mut r, phone())));
}
#[test]
fn invalid_or_foreign_history_reads_never_grant_resume() {
    for (include, result) in [
        (json!(true), json!({"thread":{"id":"history"}})),
        (json!(false), json!({"thread":{"id":"other"}})),
        (json!(false), json!({})),
    ] {
        let mut r = Router::default();
        history_read(&mut r, phone(), "history", include, result);
        assert!(!sent(&historical_resume(&mut r, phone())));
    }
    let mut r = Router::default();
    history_read(
        &mut r,
        Client::Desktop,
        "history",
        json!(false),
        json!({"thread":{"id":"history"}}),
    );
    assert!(!sent(&historical_resume(&mut r, phone())));
}
#[test]
fn disconnect_and_unload_invalidate_history_proof() {
    for disconnect in [true, false] {
        let mut r = Router::default();
        history_read(
            &mut r,
            phone(),
            "history",
            json!(false),
            json!({"thread":{"id":"history"}}),
        );
        if disconnect {
            r.disconnect(&phone());
        } else {
            r.from_runtime(json!({"method":"thread/unloaded","params":{"threadId":"history"}}));
        }
        assert!(!sent(&historical_resume(&mut r, phone())));
    }
}
#[test]
fn v02_advanced_remote_routes_keep_native_identity_and_deny_overrides() {
    let mut router = pocket_bridge::router::Router::default();
    router.capabilities = pocket_bridge::capabilities::Capabilities::from_schema(
        &serde_json::from_str(include_str!("fixtures/ClientRequest.json")).unwrap(),
    );
    router.loaded.insert("source".into());
    let client = pocket_bridge::router::Client::Remote("phone".into());
    for (method, params) in [
        (
            "thread/queue/add",
            serde_json::json!({"threadId":"source","clientUserMessageId":"m","input":[]}),
        ),
        (
            "thread/queue/update",
            serde_json::json!({"threadId":"source","queuedSubmissionId":"q","input":[]}),
        ),
        (
            "thread/queue/delete",
            serde_json::json!({"threadId":"source","queuedSubmissionId":"q"}),
        ),
        (
            "thread/queue/reorder",
            serde_json::json!({"threadId":"source","queuedSubmissionIds":["q2","q"]}),
        ),
        (
            "thread/queue/start",
            serde_json::json!({"threadId":"source"}),
        ),
        ("thread/goal/get", serde_json::json!({"threadId":"source"})),
        (
            "thread/goal/set",
            serde_json::json!({"threadId":"source","origin":"user","objective":"work"}),
        ),
        (
            "thread/goal/clear",
            serde_json::json!({"threadId":"source","origin":"user"}),
        ),
        (
            "thread/compact/start",
            serde_json::json!({"threadId":"source"}),
        ),
        (
            "thread/settings/update",
            serde_json::json!({"threadId":"source","serviceTier":"fast"}),
        ),
        (
            "thread/fork",
            serde_json::json!({"threadId":"source","attachToSource":true,"beforeTurnId":"turn"}),
        ),
    ] {
        let routes = router.from_client(
            client.clone(),
            serde_json::json!({"id":"mobile","method":method,"params":params}),
        );
        let pocket_bridge::router::Route::Runtime(sent) = &routes[0] else {
            panic!("{method} was not forwarded")
        };
        assert_eq!(sent["params"], params);
        let reply = router.from_runtime(
            serde_json::json!({"id":sent["id"],"result":{"thread":{"id":"new-fork"}}}),
        );
        let pocket_bridge::router::Route::Client(_, returned) = &reply[0] else {
            panic!("reply lost")
        };
        assert_eq!(returned["id"], "mobile");
        if method == "thread/fork" {
            assert!(router.loaded.contains("new-fork"));
        }
    }
    for (method, params) in [
        (
            "thread/fork",
            serde_json::json!({"threadId":"source","path":"C:/private"}),
        ),
        (
            "thread/settings/update",
            serde_json::json!({"threadId":"source","cwd":"C:/private"}),
        ),
    ] {
        assert!(
            !router
                .from_client(
                    client.clone(),
                    serde_json::json!({"id":1,"method":method,"params":params})
                )
                .iter()
                .any(|r| matches!(r, pocket_bridge::router::Route::Runtime(_)))
        );
    }
}
