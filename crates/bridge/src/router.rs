use serde_json::{Value, json};
use std::collections::{HashMap, HashSet, VecDeque};

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum Client {
    Desktop,
    Remote(String),
}

#[derive(Debug, Clone)]
pub enum Route {
    Runtime(Value),
    Client(Client, Value),
    Broadcast(Value),
    Drop,
}

struct Pending {
    client: Client,
    original_id: Value,
    method: String,
    requested_thread: Option<String>,
}
struct ServerPending {
    id: Value,
    desktop_id: String,
    remote_id: String,
    shareable: bool,
    thread: String,
    method: String,
    questions: HashSet<String>,
    event: Value,
}
struct Completed {
    native_id: String,
    desktop_id: String,
    desktop_notified: bool,
    remote_id: String,
    thread: String,
    winner: Option<(Client, Value)>,
}
pub struct Router {
    pub capabilities: crate::capabilities::Capabilities,
    epoch: String,
    next: u64,
    pending: HashMap<String, Pending>,
    server: HashMap<String, ServerPending>,
    completed: VecDeque<Completed>,
    verified_history: HashMap<Client, VecDeque<String>>,
    pub loaded: HashSet<String>,
    pub initialized: bool,
    pub native_version: Option<String>,
}

impl Default for Router {
    fn default() -> Self {
        Self {
            capabilities: Default::default(),
            epoch: uuid::Uuid::new_v4().to_string(),
            next: 0,
            pending: HashMap::new(),
            server: HashMap::new(),
            completed: VecDeque::new(),
            verified_history: HashMap::new(),
            loaded: HashSet::new(),
            initialized: false,
            native_version: None,
        }
    }
}

impl Router {
    pub fn from_client(&mut self, client: Client, mut value: Value) -> Vec<Route> {
        if let Some(method) = value
            .get("method")
            .and_then(Value::as_str)
            .map(str::to_owned)
        {
            if matches!(client, Client::Remote(_)) && method == "pocket/approvals/list" {
                if value["params"].as_object().is_none_or(|p| !p.is_empty()) {
                    return Self::reject(client, &value, "Pending snapshot takes no parameters");
                }
                let mut data: Vec<_> = self
                    .server
                    .values()
                    .filter(|p| p.shareable)
                    .map(|p| {
                        let mut event = p.event.clone();
                        event["id"] = json!(p.remote_id);
                        event
                    })
                    .collect();
                data.sort_by(|a, b| a["id"].as_str().cmp(&b["id"].as_str()));
                return vec![Route::Client(
                    client,
                    json!({"id":value["id"],"result":{"data":data}}),
                )];
            }
            if client != Client::Desktop {
                let permitted = matches!(
                    method.as_str(),
                    "thread/list"
                        | "thread/read"
                        | "thread/loaded/list"
                        | "thread/queue/list"
                        | "model/list"
                        | "collaborationMode/list"
                        | "turn/start"
                        | "turn/steer"
                        | "turn/interrupt"
                        | "thread/name/set"
                        | "thread/archive"
                        | "thread/unarchive"
                        | "thread/start"
                        | "thread/turns/list"
                        | "thread/items/list"
                        | "thread/resume"
                        | "thread/queue/add"
                        | "thread/queue/update"
                        | "thread/queue/delete"
                        | "thread/queue/reorder"
                        | "thread/queue/start"
                        | "thread/fork"
                        | "thread/goal/get"
                        | "thread/goal/set"
                        | "thread/goal/clear"
                        | "thread/compact/start"
                        | "thread/settings/update"
                        | "skills/list"
                        | "plugin/list"
                );
                if !permitted {
                    return Self::reject(
                        client,
                        &value,
                        "Operation requires the original desktop client",
                    );
                }
                if crate::capabilities::advanced(&method)
                    && (!self.capabilities.supports(&method, &value["params"])
                        || !safe_advanced(&method, &value["params"]))
                {
                    return Self::reject(
                        client,
                        &value,
                        "Native schema does not support this operation",
                    );
                }
                if method == "thread/resume"
                    && !value["params"].as_object().is_some_and(|p| {
                        p.keys()
                            .all(|k| matches!(k.as_str(), "threadId" | "excludeTurns"))
                            && p.get("excludeTurns").is_none_or(Value::is_boolean)
                    })
                {
                    return Self::reject(
                        client,
                        &value,
                        "Remote resume accepts only threadId and boolean excludeTurns",
                    );
                }
                let management = matches!(
                    method.as_str(),
                    "thread/name/set" | "thread/archive" | "thread/unarchive"
                );
                if management
                    && !value["params"].as_object().is_some_and(|params| {
                        params.keys().all(|key| {
                            key == "threadId" || (method == "thread/name/set" && key == "name")
                        }) && (method != "thread/name/set"
                            || params.get("name").is_some_and(Value::is_string))
                    })
                {
                    return Self::reject(
                        client,
                        &value,
                        "History management accepts only its native ID and metadata fields",
                    );
                }
                let fork = method == "thread/fork";
                let advanced_write = crate::capabilities::advanced(&method)
                    && !matches!(
                        method.as_str(),
                        "thread/goal/get" | "skills/list" | "plugin/list"
                    );
                if method.starts_with("turn/")
                    || method == "thread/resume"
                    || management
                    || advanced_write
                {
                    let thread = value["params"]["threadId"].as_str().unwrap_or_default();
                    if thread.is_empty() || !self.loaded.contains(thread) {
                        // The broker checks ownership across runtimes. This local
                        // bridge additionally requires a native read on this very
                        // connection before it can resume inactive history.
                        let verified = (method == "thread/resume" || management || fork)
                            && self
                                .verified_history
                                .get_mut(&client)
                                .and_then(|ids| {
                                    ids.iter()
                                        .position(|id| id == thread)
                                        .and_then(|i| ids.remove(i))
                                })
                                .is_some();
                        if !verified {
                            return Self::reject(
                                client,
                                &value,
                                "Thread is not owned or verified by this runtime",
                            );
                        }
                    }
                }
            }
            if let Some(id) = value.get("id").cloned() {
                let Some(next) = self.next.checked_add(1) else {
                    return Self::reject(client, &value, "Routing ID space exhausted");
                };
                self.next = next;
                let proxy_id = format!("pocket:{}:{}", self.epoch, self.next);
                self.pending.insert(
                    proxy_id.clone(),
                    Pending {
                        client,
                        original_id: id,
                        requested_thread: if method == "thread/read"
                            && value["params"]["includeTurns"] == false
                        {
                            value["params"]["threadId"].as_str().map(str::to_owned)
                        } else {
                            None
                        },
                        method,
                    },
                );
                value["id"] = json!(proxy_id);
            }
            return vec![Route::Runtime(value)];
        }
        if let Some(id) = value.get("id") {
            let key = self
                .server
                .iter()
                .find(|(_, p)| {
                    if client == Client::Desktop {
                        id.as_str() == Some(&p.desktop_id)
                    } else {
                        p.shareable && id.as_str() == Some(&p.remote_id)
                    }
                })
                .map(|(k, _)| k.clone());
            if let Some(key) = key {
                if !self
                    .server
                    .get(&key)
                    .is_some_and(|p| Self::valid_response(p, &client, &value))
                {
                    return vec![Route::Drop];
                }
                if let Some(p) = self.server.remove(&key) {
                    let winning_response = value.get("result").cloned();
                    // Keep a bounded translation for the native resolution that
                    // follows a response. This never grants permission to answer.
                    self.completed.push_back(Completed {
                        native_id: key,
                        desktop_id: p.desktop_id.clone(),
                        desktop_notified: p.shareable && client != Client::Desktop,
                        remote_id: p.remote_id.clone(),
                        thread: p.thread.clone(),
                        winner: if p.shareable && matches!(client, Client::Remote(_)) {
                            winning_response
                                .clone()
                                .map(|response| (client.clone(), response))
                        } else {
                            None
                        },
                    });
                    if self.completed.len() > 1024 {
                        self.completed.pop_front();
                    }
                    value["id"] = p.id.clone();
                    let mut routes = vec![Route::Runtime(value)];
                    if p.shareable {
                        // A successful pipe write establishes submission only.
                        // Keep the winner until native resolves this request.
                        if matches!(client, Client::Remote(_))
                            && let Some(response) = winning_response
                        {
                            routes.push(Route::Client(client.clone(),json!({"method":"pocket/approvalSubmitted","params":{"requestId":p.remote_id,"threadId":p.thread,"winner":"thisConnection","response":response}})));
                        }
                        if client != Client::Desktop {
                            routes.push(Route::Client(Client::Desktop,json!({"method":"serverRequest/resolved","params":{"requestId":p.desktop_id,"threadId":p.thread}})));
                        }
                        routes.push(Route::Broadcast(json!({"method":"pocket/approvalResolved","params":{"requestId":p.remote_id,"threadId":p.thread}})));
                    }
                    return routes;
                }
            }
        }
        vec![Route::Drop]
    }
    pub fn from_runtime(&mut self, mut value: Value) -> Vec<Route> {
        if let Some(method) = value
            .get("method")
            .and_then(Value::as_str)
            .map(str::to_owned)
        {
            if let Some(id) = value.get("id").cloned() {
                let shareable = matches!(
                    method.as_str(),
                    "item/commandExecution/requestApproval"
                        | "item/fileChange/requestApproval"
                        | "item/tool/requestUserInput"
                );
                let token = format!("approval:{}", uuid::Uuid::new_v4());
                let desktop_id = format!("server:{}", uuid::Uuid::new_v4());
                let questions = value["params"]["questions"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter_map(|q| q["id"].as_str().map(str::to_owned))
                    .collect();
                let native_key = id.to_string();
                self.completed.retain(|p| p.native_id != native_key);
                self.server.insert(
                    native_key,
                    ServerPending {
                        id,
                        desktop_id: desktop_id.clone(),
                        remote_id: token.clone(),
                        shareable,
                        thread: value["params"]["threadId"]
                            .as_str()
                            .unwrap_or_default()
                            .into(),
                        method,
                        questions,
                        event: value.clone(),
                    },
                );
                value["id"] = json!(desktop_id);
                let mut routes = vec![Route::Client(Client::Desktop, value.clone())];
                if shareable {
                    value["id"] = json!(token);
                    routes.push(Route::Broadcast(value));
                }
                return routes;
            }
            if method == "thread/started"
                && let Some(id) = value["params"]["thread"]["id"].as_str()
            {
                self.loaded.insert(id.into());
            }
            if matches!(method.as_str(), "thread/closed" | "thread/unloaded")
                && let Some(id) = value["params"]["threadId"].as_str()
            {
                self.loaded.remove(id);
                for history in self.verified_history.values_mut() {
                    history.retain(|thread| thread != id);
                }
            }
            if method == "serverRequest/resolved" {
                let resolved_key = value["params"]["requestId"].to_string();
                let thread = value["params"]["threadId"].as_str();
                if self
                    .server
                    .get(&resolved_key)
                    .is_some_and(|p| thread == Some(p.thread.as_str()))
                {
                    let p = self
                        .server
                        .remove(&resolved_key)
                        .expect("matched pending request");
                    value["params"]["requestId"] = json!(p.desktop_id);
                    return vec![
                        Route::Client(Client::Desktop, value),
                        Route::Broadcast(
                            json!({"method":"pocket/approvalResolved","params":{"requestId":p.remote_id,"threadId":p.thread}}),
                        ),
                    ];
                }
                if let Some(index) = self
                    .completed
                    .iter()
                    .position(|p| p.native_id == resolved_key && thread == Some(p.thread.as_str()))
                {
                    let p = self.completed.remove(index).expect("located completion");
                    let mut routes = Vec::new();
                    if let Some((client, response)) = p.winner {
                        // Resolution also covers native cancellation. This is
                        // request closure, never evidence the response was applied.
                        routes.push(Route::Client(client,json!({"method":"pocket/approvalResolved","params":{"requestId":p.remote_id,"threadId":p.thread,"winner":"thisConnection","response":response,"nativeResolved":true}})));
                    }
                    if !p.desktop_notified {
                        value["params"]["requestId"] = json!(p.desktop_id);
                        routes.push(Route::Client(Client::Desktop, value));
                    }
                    return routes;
                }
                // Duplicate/unknown resolution IDs cannot identify a desktop alias.
                return vec![Route::Drop];
            }
            return vec![
                Route::Client(Client::Desktop, value.clone()),
                Route::Broadcast(value),
            ];
        }
        if let Some(id) = value.get("id").and_then(Value::as_str)
            && let Some(p) = self.pending.remove(id)
        {
            if value.get("error").is_none() {
                if p.method == "thread/read"
                    && matches!(p.client, Client::Remote(_))
                    && let Some(thread) = p.requested_thread.as_deref()
                    && !thread.is_empty()
                    && thread.len() <= 128
                    && value["result"]["thread"]["id"].as_str() == Some(thread)
                {
                    let history = self.verified_history.entry(p.client.clone()).or_default();
                    history.retain(|id| id != thread);
                    if history.len() == 64 {
                        history.pop_front();
                    }
                    history.push_back(thread.to_owned());
                }
                if p.method == "initialize" {
                    self.initialized = true;
                    self.native_version = value["result"]["version"]
                        .as_str()
                        .or_else(|| value["result"]["userAgent"].as_str())
                        .map(str::to_owned);
                }
                if matches!(
                    p.method.as_str(),
                    "thread/start" | "thread/resume" | "thread/fork"
                ) && let Some(id) = value["result"]["thread"]["id"].as_str()
                {
                    self.loaded.insert(id.into());
                }
            }
            value["id"] = p.original_id;
            return vec![Route::Client(p.client, value)];
        }
        vec![Route::Drop]
    }
    fn reject(client: Client, value: &Value, message: &str) -> Vec<Route> {
        vec![Route::Client(
            client,
            json!({"id":value["id"],"error":{"code":-32001,"message":message}}),
        )]
    }
    fn valid_response(p: &ServerPending, client: &Client, value: &Value) -> bool {
        if value.get("method").is_some() {
            return false;
        }
        if *client == Client::Desktop {
            return matches!(
                (value.get("result"), value.get("error")),
                (Some(_), None) | (None, Some(_))
            );
        }
        match (value.get("result"), value.get("error")) {
            (None, Some(error)) => error["code"].as_i64().is_some() && error["message"].is_string(),
            (Some(result), None) => match p.method.as_str() {
                "item/commandExecution/requestApproval" | "item/fileChange/requestApproval" => {
                    matches!(
                        result["decision"].as_str(),
                        Some("accept" | "acceptForSession" | "decline" | "cancel")
                    )
                }
                "item/tool/requestUserInput" => {
                    result["answers"].as_object().is_some_and(|answers| {
                        answers.len() == p.questions.len()
                            && p.questions.iter().all(|q| {
                                answers.get(q).is_some_and(|a| {
                                    a["answers"]
                                        .as_array()
                                        .is_some_and(|v| v.iter().all(Value::is_string))
                                })
                            })
                    })
                }
                _ => false,
            },
            _ => false,
        }
    }
    pub fn pending_approvals(&self) -> usize {
        self.server.values().filter(|p| p.shareable).count()
    }
    pub fn disconnect(&mut self, client: &Client) {
        self.pending.retain(|_, p| &p.client != client);
        self.verified_history.remove(client);
        for completed in &mut self.completed {
            if completed
                .winner
                .as_ref()
                .is_some_and(|(winner, _)| winner == client)
            {
                completed.winner = None;
            }
        }
    }
}
fn safe_advanced(method: &str, params: &Value) -> bool {
    let allowed: &[&str] = match method {
        "thread/queue/add" => &["threadId", "clientUserMessageId", "input"],
        "thread/queue/update" => &["threadId", "queuedSubmissionId", "input"],
        "thread/queue/delete" | "thread/queue/start" => &["threadId", "queuedSubmissionId"],
        "thread/queue/reorder" => &["threadId", "queuedSubmissionIds"],
        "thread/fork" => &["threadId", "attachToSource", "beforeTurnId", "excludeTurns"],
        "thread/goal/get" | "thread/compact/start" => &["threadId"],
        "thread/goal/set" => &["threadId", "objective", "status", "tokenBudget", "origin"],
        "thread/goal/clear" => &["threadId", "origin"],
        "thread/settings/update" => &["threadId", "serviceTier"],
        "skills/list" => &["cwds"],
        "plugin/list" => &["cwds", "marketplaceKinds", "forceRefetch"],
        _ => return false,
    };
    params
        .as_object()
        .is_some_and(|p| p.keys().all(|k| allowed.contains(&k.as_str())))
        && (!matches!(method, "thread/goal/set" | "thread/goal/clear")
            || params["origin"] == "user")
        && (method != "thread/settings/update"
            || (params.get("serviceTier").is_some()
                && (params["serviceTier"].is_null() || params["serviceTier"] == "fast")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn runtime(routes: Vec<Route>) -> Value {
        match routes.into_iter().next().unwrap() {
            Route::Runtime(v) => v,
            _ => panic!("expected runtime"),
        }
    }
    #[test]
    fn concurrent_request_ids_never_collide_and_are_restored() {
        let mut r = Router::default();
        let a = runtime(r.from_client(Client::Desktop, json!({"id":1,"method":"thread/list"})));
        let b = runtime(r.from_client(
            Client::Remote("phone".into()),
            json!({"id":1,"method":"thread/read"}),
        ));
        assert_ne!(a["id"], b["id"]);
        let answer = r.from_runtime(json!({"id":b["id"],"result":{"ok":true}}));
        assert!(
            matches!(&answer[0],Route::Client(Client::Remote(x),v) if x=="phone" && v["id"]==1)
        );
    }
    #[test]
    fn first_approval_wins_even_if_other_client_responds_late() {
        let mut r = Router::default();
        let issued=r.from_runtime(json!({"id":17,"method":"item/commandExecution/requestApproval","params":{"threadId":"t","turnId":"u","itemId":"i"}}));
        let Route::Client(Client::Desktop, desktop) = &issued[0] else {
            panic!()
        };
        let Route::Broadcast(approval) = &issued[1] else {
            panic!("expected remote approval")
        };
        let a = r.from_client(
            Client::Remote("phone".into()),
            json!({"id":approval["id"],"result":{"decision":"accept"}}),
        );
        let b = r.from_client(
            Client::Desktop,
            json!({"id":desktop["id"],"result":{"decision":"decline"}}),
        );
        assert_eq!(
            a.iter().filter(|x| matches!(x, Route::Runtime(_))).count(),
            1
        );
        assert_eq!(
            b.iter().filter(|x| matches!(x, Route::Runtime(_))).count(),
            0
        );
    }
    #[test]
    fn dynamic_tools_are_routed_only_to_desktop() {
        let mut r = Router::default();
        let v = json!({"id":"d1","method":"item/tool/call","params":{"tool":"open_file"}});
        assert!(matches!(
            &r.from_runtime(v)[0],
            Route::Client(Client::Desktop, _)
        ));
        let stolen = r.from_client(
            Client::Remote("phone".into()),
            json!({"id":"d1","result":{"success":true}}),
        );
        assert!(!stolen.iter().any(|x| matches!(x, Route::Runtime(_))));
    }
    #[test]
    fn inactive_resume_requires_exact_native_history_on_same_connection() {
        let mut r = Router::default();
        let phone = Client::Remote("phone".into());
        let resume = json!({"id":2,"method":"thread/resume","params":{"threadId":"history"}});
        assert!(
            !r.from_client(phone.clone(), resume.clone())
                .iter()
                .any(|x| matches!(x, Route::Runtime(_)))
        );
        let read = r.from_client(phone.clone(), json!({"id":1,"method":"thread/read","params":{"threadId":"history","includeTurns":false}}));
        let Route::Runtime(request) = &read[0] else {
            panic!()
        };
        r.from_runtime(json!({"id":request["id"],"result":{"thread":{"id":"history"}}}));
        assert!(
            !r.from_client(Client::Remote("other".into()), resume.clone())
                .iter()
                .any(|x| matches!(x, Route::Runtime(_)))
        );
        assert!(
            !r.from_client(
                phone.clone(),
                json!({"id":3,"method":"turn/start","params":{"threadId":"history"}})
            )
            .iter()
            .any(|x| matches!(x, Route::Runtime(_)))
        );
        assert!(
            r.from_client(phone.clone(), resume.clone())
                .iter()
                .any(|x| matches!(x, Route::Runtime(_)))
        );
        assert!(
            !r.from_client(phone, resume)
                .iter()
                .any(|x| matches!(x, Route::Runtime(_)))
        );
    }
    #[test]
    fn mismatched_history_and_disconnected_proofs_do_not_authorize_resume() {
        let mut r = Router::default();
        let phone = Client::Remote("phone".into());
        let resume = json!({"id":2,"method":"thread/resume","params":{"threadId":"history"}});
        for returned in ["other", "history"] {
            let read = r.from_client(phone.clone(), json!({"id":1,"method":"thread/read","params":{"threadId":"history","includeTurns":false}}));
            let Route::Runtime(request) = &read[0] else {
                panic!()
            };
            r.from_runtime(json!({"id":request["id"],"result":{"thread":{"id":returned}}}));
            if returned == "history" {
                r.disconnect(&phone);
            }
            assert!(
                !r.from_client(phone.clone(), resume.clone())
                    .iter()
                    .any(|x| matches!(x, Route::Runtime(_)))
            );
        }
    }
    #[test]
    fn remote_cannot_reinitialize_or_reconfigure_native_client() {
        let mut r = Router::default();
        for method in [
            "initialize",
            "config/value/write",
            "account/logout",
            "command/exec",
            "thread/resume",
        ] {
            let routes = r.from_client(
                Client::Remote("phone".into()),
                json!({"id":3,"method":method,"params":{}}),
            );
            assert!(
                !routes.iter().any(|x| matches!(x, Route::Runtime(_))),
                "{method}"
            );
        }
    }
    #[test]
    fn old_phone_approval_cannot_approve_reused_native_id() {
        let mut r = Router::default();
        let request =
            json!({"id":3,"method":"item/fileChange/requestApproval","params":{"threadId":"t"}});
        let events = r.from_runtime(request.clone());
        let Route::Client(Client::Desktop, desktop) = &events[0] else {
            panic!()
        };
        let Route::Broadcast(old) = &events[1] else {
            panic!()
        };
        assert!(
            r.from_client(
                Client::Desktop,
                json!({"id":desktop["id"],"result":{"decision":"decline"}})
            )
            .iter()
            .any(|e| matches!(e, Route::Runtime(_)))
        );
        r.from_runtime(request);
        let stale = r.from_client(
            Client::Remote("phone".into()),
            json!({"id":old["id"],"result":{"decision":"accept"}}),
        );
        assert!(!stale.iter().any(|e| matches!(e, Route::Runtime(_))));
        assert_eq!(r.pending_approvals(), 1);
    }
    #[test]
    fn canceled_native_request_invalidates_phone_button() {
        let mut r = Router::default();
        let events=r.from_runtime(json!({"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"t"}}));
        let Route::Broadcast(old) = &events[1] else {
            panic!()
        };
        r.from_runtime(
            json!({"method":"serverRequest/resolved","params":{"threadId":"t","requestId":7}}),
        );
        let stale = r.from_client(
            Client::Remote("phone".into()),
            json!({"id":old["id"],"result":{"decision":"accept"}}),
        );
        assert!(!stale.iter().any(|e| matches!(e, Route::Runtime(_))));
    }
    #[test]
    fn cannot_send_to_thread_owned_by_another_runtime() {
        let mut r = Router::default();
        let denied = r.from_client(
            Client::Remote("phone".into()),
            json!({"id":1,"method":"turn/start","params":{"threadId":"foreign","input":[]}}),
        );
        assert!(!denied.iter().any(|e| matches!(e, Route::Runtime(_))));
        r.from_runtime(json!({"method":"thread/started","params":{"thread":{"id":"ours"}}}));
        assert!(
            r.from_client(
                Client::Remote("phone".into()),
                json!({"id":2,"method":"turn/start","params":{"threadId":"ours","input":[]}})
            )
            .iter()
            .any(|e| matches!(e, Route::Runtime(_)))
        );
    }
}
