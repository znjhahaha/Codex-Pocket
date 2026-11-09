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
    include_turns: bool,
    settings: Option<crate::settings::Observation>,
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
    verified_cwds: HashMap<Client, VecDeque<String>>,
    verified_profiles: HashMap<Client, std::collections::BTreeSet<String>>,
    extension_keys: HashMap<Client, HashMap<String, String>>,
    skill_paths: HashMap<Client, HashSet<String>>,
    edit_turns: HashMap<Client, HashMap<String, HashSet<String>>>,
    active: HashSet<String>,
    pub loaded: HashSet<String>,
    pub initialized: bool,
    pub native_version: Option<String>,
    settings: crate::settings::SettingsCache,
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
            verified_cwds: HashMap::new(),
            verified_profiles: HashMap::new(),
            extension_keys: HashMap::new(),
            skill_paths: HashMap::new(),
            edit_turns: HashMap::new(),
            active: HashSet::new(),
            loaded: HashSet::new(),
            initialized: false,
            native_version: None,
            settings: Default::default(),
        }
    }
}

impl Router {
    fn management_busy(&self) -> bool {
        !self.active.is_empty()
            || !self.server.is_empty()
            || self.pending.values().any(|p| {
                matches!(
                    p.method.as_str(),
                    "turn/start" | "turn/steer" | "thread/compact/start" | "mcpServer/tool/call"
                )
            })
    }
    pub fn from_client(&mut self, client: Client, mut value: Value) -> Vec<Route> {
        if let Some(method) = value
            .get("method")
            .and_then(Value::as_str)
            .map(str::to_owned)
        {
            if matches!(client, Client::Remote(_)) && method == "pocket/managementStatus" {
                if value["params"].as_object().is_none_or(|p| !p.is_empty()) {
                    return Self::reject(client, &value, "Management status takes no parameters");
                }
                return vec![Route::Client(
                    client,
                    json!({"id":value["id"],"result":{"protocol":1,"initialized":self.initialized,"busy":self.management_busy()}}),
                )];
            }
            if matches!(client, Client::Remote(_)) && method == "pocket/threadSettings" {
                if !value["params"].as_object().is_some_and(|p| {
                    p.len() == 1
                        && p.get("threadId")
                            .and_then(Value::as_str)
                            .is_some_and(crate::settings::valid_thread)
                }) {
                    return Self::reject(
                        client,
                        &value,
                        "Settings read accepts only a native threadId",
                    );
                }
                return vec![Route::Client(
                    client,
                    json!({"id":value["id"],"result":self.settings.read(value["params"]["threadId"].as_str().unwrap())}),
                )];
            }
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
                let restricted = matches!(
                    method.as_str(),
                    "config/value/write" | "skills/config/write" | "config/mcpServer/reload"
                );
                if restricted {
                    let p = &value["params"];
                    let safe = if !self.capabilities.supports(&method, p) || self.management_busy()
                    {
                        false
                    } else {
                        match method.as_str() {
                            "config/value/write" => {
                                p.as_object().is_some_and(|o| {
                                    o.len() == 4
                                        && o.keys().all(|k| {
                                            ["keyPath", "value", "mergeStrategy", "expectedVersion"]
                                                .contains(&k.as_str())
                                        })
                                }) && p["value"].is_boolean()
                                    && p["mergeStrategy"] == "replace"
                                    && p["keyPath"].as_str().is_some_and(|key| {
                                        self.extension_keys
                                            .get(&client)
                                            .and_then(|m| m.get(key))
                                            .is_some_and(|v| p["expectedVersion"] == *v)
                                    })
                            }
                            "skills/config/write" => {
                                p.as_object().is_some_and(|o| {
                                    o.len() == 2 && o.keys().all(|k| k == "path" || k == "enabled")
                                }) && p["enabled"].is_boolean()
                                    && p["path"].as_str().is_some_and(|path| {
                                        self.skill_paths
                                            .get(&client)
                                            .is_some_and(|paths| paths.contains(path))
                                    })
                            }
                            _ => p.is_null(),
                        }
                    };
                    if !safe {
                        return Self::reject(
                            client,
                            &value,
                            "Management requires idle runtime and known native configuration",
                        );
                    }
                    if method == "config/value/write" {
                        if let Some(m) = self.extension_keys.get_mut(&client) {
                            m.remove(p["keyPath"].as_str().unwrap());
                        }
                    }
                    if method == "skills/config/write" {
                        if let Some(m) = self.skill_paths.get_mut(&client) {
                            m.remove(p["path"].as_str().unwrap());
                        }
                    }
                }
                let quota_read =
                    matches!(method.as_str(), "account/rateLimits/read" | "config/read");
                if quota_read
                    && (!(value["params"].as_object().is_some_and(|p| p.is_empty())
                        || (method == "config/read"
                            && value["params"].as_object().is_some_and(|p| p.len() == 1)
                            && value["params"]["includeLayers"] == true)
                        || (method == "config/read"
                            && value["params"].as_object().is_some_and(|p| p.len() == 2)
                            && value["params"]["includeLayers"] == false
                            && value["params"]["cwd"].as_str().is_some_and(|cwd| {
                                self.verified_cwds
                                    .get(&client)
                                    .is_some_and(|cwds| cwds.iter().any(|v| v == cwd))
                            })))
                        || !self.capabilities.supports(&method, &value["params"]))
                {
                    return Self::reject(
                        client,
                        &value,
                        "Read requires native capability evidence and empty or verified read-only parameters",
                    );
                }
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
                        | "plugin/installed"
                        | "app/installed"
                        | "mcpServerStatus/list"
                        | "thread/revert"
                );
                if !permitted && !quota_read && !restricted {
                    return Self::reject(
                        client,
                        &value,
                        "Operation requires the original desktop client",
                    );
                }
                if crate::capabilities::advanced(&method)
                    && (!self.capabilities.supports(&method, &value["params"])
                        || !safe_advanced(&method, &value["params"])
                        || (value["params"]["approvalsReviewer"] == "auto_review"
                            && !self
                                .capabilities
                                .0
                                .get(&method)
                                .is_some_and(|f| f.contains("$autoReview"))))
                {
                    return Self::reject(
                        client,
                        &value,
                        "Native schema does not support this operation",
                    );
                }
                if matches!(method.as_str(), "plugin/installed" | "mcpServerStatus/list")
                    && (value["params"].as_object().is_none_or(|p| !p.is_empty())
                        || !self.capabilities.supports(&method, &value["params"]))
                {
                    return Self::reject(
                        client,
                        &value,
                        "Inventory accepts no arbitrary selectors",
                    );
                }
                if method == "app/installed"
                    && (value["params"].as_object().is_none_or(|p| p.len() != 1 || !p.contains_key("forceRefresh"))
                        || value["params"]["forceRefresh"] != false
                        || !self.capabilities.supports(&method, &value["params"]))
                {
                    return Self::reject(client, &value, "App inventory requires an evidenced cached read without selectors");
                }
                if method == "thread/revert" {
                    let thread = value["params"]["threadId"].as_str().unwrap_or_default();
                    let turn = value["params"]["beforeTurnId"].as_str().unwrap_or_default();
                    let proof = self
                        .edit_turns
                        .get_mut(&client)
                        .and_then(|m| m.get_mut(thread))
                        .is_some_and(|turns| turns.remove(turn));
                    if !proof
                        || self.active.contains(thread)
                        || self.server.values().any(|p| p.thread == thread)
                    {
                        return Self::reject(
                            client,
                            &value,
                            "Revert requires an idle first-user-turn read on this connection",
                        );
                    }
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
                if method == "thread/settings/update"
                    && let Some(id) = value["params"]["permissions"].as_str()
                    && !self
                        .verified_profiles
                        .get_mut(&client)
                        .is_some_and(|ids| ids.remove(id))
                {
                    return Self::reject(
                        client,
                        &value,
                        "Permission profile requires verified native config",
                    );
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
                        settings: self.settings.prepare(&method, &value["params"]),
                        requested_thread: if method == "thread/read" {
                            value["params"]["threadId"].as_str().map(str::to_owned)
                        } else {
                            None
                        },
                        include_turns: value["params"]["includeTurns"] == true,
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
            if method == "turn/started"
                || (method == "thread/status/changed"
                    && value["params"]["status"]["type"] == "active")
            {
                if let Some(thread) = value["params"]["threadId"].as_str() {
                    self.active.insert(thread.into());
                    for m in self.edit_turns.values_mut() {
                        m.remove(thread);
                    }
                }
            }
            if method == "turn/completed"
                || (method == "thread/status/changed"
                    && value["params"]["status"]["type"] == "idle")
            {
                if let Some(thread) = value["params"]["threadId"].as_str() {
                    self.active.remove(thread);
                }
            }
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
            let settings_event = self.settings.event(&value);
            if method == "thread/settings/updated" {
                let mut routes = vec![Route::Client(Client::Desktop, value)];
                if let Some(event) = settings_event {
                    routes.push(Route::Broadcast(event));
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
            let settings_event = if value.get("error").is_none() && value.get("result").is_some() {
                p.settings
                    .and_then(|settings| self.settings.accepted(settings, &value["result"]))
            } else {
                None
            };
            if value.get("error").is_none() {
                if p.method == "thread/read"
                    && matches!(p.client, Client::Remote(_))
                    && let Some(thread) = p.requested_thread.as_deref()
                    && !thread.is_empty()
                    && thread.len() <= 128
                    && value["result"]["thread"]["id"].as_str() == Some(thread)
                {
                    // Loading transcript contents must not grant a separate acquire/resume
                    // capability. Handoff performs its own metadata-only identity proof.
                    if !p.include_turns {
                        let history = self.verified_history.entry(p.client.clone()).or_default();
                        history.retain(|id| id != thread);
                        if history.len() == 64 {
                            history.pop_front();
                        }
                        history.push_back(thread.to_owned());
                    }
                    if let Some(cwd) = value["result"]["thread"]["cwd"]
                        .as_str()
                        .filter(|cwd| crate::settings::valid_thread(cwd))
                    {
                        let cwds = self.verified_cwds.entry(p.client.clone()).or_default();
                        cwds.retain(|v| v != cwd);
                        if cwds.len() == 64 {
                            cwds.pop_front();
                        }
                        cwds.push_back(cwd.to_owned());
                    }
                }
                if p.method == "config/read" && matches!(p.client, Client::Remote(_)) {
                    self.extension_keys
                        .insert(p.client.clone(), known_extension_keys(&value["result"]));
                    self.verified_profiles.insert(
                        p.client.clone(),
                        crate::permission_policy::configured_profiles(&value["result"]),
                    );
                }
                if p.method == "skills/list" && matches!(p.client, Client::Remote(_)) {
                    self.skill_paths.insert(
                        p.client.clone(),
                        value["result"]["data"]
                            .as_array()
                            .into_iter()
                            .flatten()
                            .flat_map(|d| d["skills"].as_array().into_iter().flatten())
                            .filter_map(|s| {
                                s["path"]
                                    .as_str()
                                    .filter(|s| crate::settings::valid_thread(s))
                                    .map(str::to_owned)
                            })
                            .take(4096)
                            .collect(),
                    );
                }
                if p.method == "thread/read" && matches!(p.client, Client::Remote(_)) {
                    if let Some(thread) = p
                        .requested_thread
                        .as_deref()
                        .filter(|t| value["result"]["thread"]["id"] == *t)
                    {
                        let native = &value["result"]["thread"];
                        if native["status"]["type"] == "active" {
                            self.active.insert(thread.into());
                        } else if native["status"]["type"] == "idle" {
                            self.active.remove(thread);
                        }
                        if p.include_turns {
                            let turns = native["turns"]
                                .as_array()
                                .into_iter()
                                .flatten()
                                .filter(|t| {
                                    native["status"]["type"] == "idle"
                                        && t["status"] != "inProgress"
                                        && t.get("itemsView").is_none_or(|v| v == "full")
                                        && t["items"]
                                            .as_array()
                                            .and_then(|i| i.first())
                                            .is_some_and(|i| i["type"] == "userMessage")
                                })
                                .filter_map(|t| t["id"].as_str().map(str::to_owned))
                                .collect();
                            self.edit_turns
                                .entry(p.client.clone())
                                .or_default()
                                .insert(thread.into(), turns);
                        }
                    }
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
            let mut routes = vec![Route::Client(p.client, value)];
            if let Some(event) = settings_event {
                routes.push(Route::Broadcast(event));
            }
            return routes;
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
        self.verified_cwds.remove(client);
        self.verified_profiles.remove(client);
        self.extension_keys.remove(client);
        self.skill_paths.remove(client);
        self.edit_turns.remove(client);
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
fn known_extension_keys(read: &Value) -> HashMap<String, String> {
    let mut known = HashMap::new();
    for layer in read["layers"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|l| l["name"]["type"] == "user" && l["disabledReason"].is_null())
    {
        let Some(version) = layer["version"]
            .as_str()
            .filter(|v| crate::settings::valid_thread(v))
        else {
            continue;
        };
        for table in ["mcp_servers", "plugins"] {
            for (key, entry) in layer["config"][table].as_object().into_iter().flatten() {
                if key.len() <= 128
                    && !key.is_empty()
                    && key
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
                    && entry.is_object()
                    && (entry["enabled"].is_boolean()
                        || (table == "mcp_servers" && entry.get("enabled").is_none()))
                {
                    known.insert(format!("{table}.{key}.enabled"), version.into());
                }
            }
        }
    }
    known
}
fn safe_advanced(method: &str, params: &Value) -> bool {
    if method == "thread/settings/update" && crate::permission_policy::valid_update(params) {
        return true;
    }
    let allowed: &[&str] = match method {
        "thread/revert" => &["threadId", "beforeTurnId"],
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
    #[test]
    fn v054_app_inventory_requires_exact_cached_read_and_native_evidence() {
        let mut router = Router::default();
        let helper = Client::Remote("helper".into());
        let request = json!({"id":1,"method":"app/installed","params":{"forceRefresh":false}});
        assert!(!router.from_client(helper.clone(), request.clone()).iter().any(|r| matches!(r, Route::Runtime(_))));
        router.capabilities.0.insert("app/installed".into(), ["forceRefresh", "threadId"].into_iter().map(str::to_owned).collect());
        assert!(router.from_client(helper.clone(), request).iter().any(|r| matches!(r, Route::Runtime(v) if v["params"] == json!({"forceRefresh":false}))));
        for params in [Value::Null, json!({}), json!({"forceRefresh":true}), json!({"forceRefresh":null}), json!({"forceRefresh":"false"}), json!({"forceRefresh":false,"threadId":"unverified"}), json!({"forceRefresh":false,"threadId":null}), json!({"forceRefresh":false,"path":"C:/private"})] {
            assert!(!router.from_client(helper.clone(), json!({"id":2,"method":"app/installed","params":params})).iter().any(|r| matches!(r, Route::Runtime(_))));
        }
        assert!(!router.from_client(helper, json!({"id":3,"method":"app/installed"})).iter().any(|r| matches!(r, Route::Runtime(_))));
    }
    #[test]
    fn v054_reload_refuses_pending_generation_before_started_event() {
        let mut r = Router::default();
        r.loaded.insert("t".into());
        r.capabilities
            .0
            .insert("config/mcpServer/reload".into(), Default::default());
        assert!(
            r.from_client(
                Client::Desktop,
                json!({"id":1,"method":"turn/start","params":{"threadId":"t","input":[]}})
            )
            .iter()
            .any(|v| matches!(v, Route::Runtime(_)))
        );
        assert!(
            !r.from_client(
                Client::Remote("helper".into()),
                json!({"id":2,"method":"config/mcpServer/reload","params":null})
            )
            .iter()
            .any(|v| matches!(v, Route::Runtime(_)))
        );
    }
    #[test]
    fn v054_boolean_config_requires_known_version_and_idle_runtime() {
        let mut r = Router::default();
        let helper = Client::Remote("helper".into());
        for (method, fields) in [
            ("config/read", vec!["includeLayers"]),
            (
                "config/value/write",
                vec!["keyPath", "value", "mergeStrategy", "expectedVersion"],
            ),
            ("config/mcpServer/reload", vec![]),
        ] {
            r.capabilities.0.insert(
                method.into(),
                fields.into_iter().map(str::to_owned).collect(),
            );
        }
        let write = json!({"id":2,"method":"config/value/write","params":{"keyPath":"mcp_servers.known.enabled","value":false,"mergeStrategy":"replace","expectedVersion":"v1"}});
        assert!(
            !r.from_client(helper.clone(), write.clone())
                .iter()
                .any(|v| matches!(v, Route::Runtime(_)))
        );
        let routes = r.from_client(
            helper.clone(),
            json!({"id":1,"method":"config/read","params":{"includeLayers":true}}),
        );
        let Route::Runtime(sent) = &routes[0] else {
            panic!("read")
        };
        r.from_runtime(json!({"id":sent["id"],"result":{"layers":[{"name":{"type":"user","file":"C:/private"},"version":"v1","config":{"mcp_servers":{"known":{"enabled":true,"env":{"TOKEN":"secret"}}}}}]}}));
        for (key, value) in [
            ("keyPath", json!("model_provider")),
            ("keyPath", json!("mcp_servers.new.enabled")),
            ("expectedVersion", json!("old")),
            ("value", json!({"url":"private"})),
            ("filePath", json!("C:/private")),
        ] {
            let mut bad = write.clone();
            bad["params"][key] = value;
            assert!(
                !r.from_client(helper.clone(), bad)
                    .iter()
                    .any(|v| matches!(v, Route::Runtime(_)))
            );
        }
        r.from_runtime(
            json!({"method":"turn/started","params":{"threadId":"t","turn":{"id":"u"}}}),
        );
        assert!(
            !r.from_client(helper.clone(), write.clone())
                .iter()
                .any(|v| matches!(v, Route::Runtime(_)))
        );
        assert!(
            !r.from_client(
                helper.clone(),
                json!({"id":3,"method":"config/mcpServer/reload","params":null})
            )
            .iter()
            .any(|v| matches!(v, Route::Runtime(_)))
        );
        r.from_runtime(json!({"method":"turn/completed","params":{"threadId":"t"}}));
        assert!(
            r.from_client(helper.clone(), write.clone())
                .iter()
                .any(|v| matches!(v, Route::Runtime(_)))
        );
        assert!(
            !r.from_client(helper, write)
                .iter()
                .any(|v| matches!(v, Route::Runtime(_)))
        );
    }
    #[test]
    fn v054_revert_requires_this_clients_first_user_turn_proof() {
        let mut r = Router::default();
        let helper = Client::Remote("helper".into());
        r.loaded.insert("t".into());
        r.capabilities.0.insert(
            "thread/revert".into(),
            ["threadId", "beforeTurnId"]
                .into_iter()
                .map(str::to_owned)
                .collect(),
        );
        let revert =
            json!({"id":2,"method":"thread/revert","params":{"threadId":"t","beforeTurnId":"u"}});
        assert!(
            !r.from_client(helper.clone(), revert.clone())
                .iter()
                .any(|v| matches!(v, Route::Runtime(_)))
        );
        let routes = r.from_client(
            helper.clone(),
            json!({"id":1,"method":"thread/read","params":{"threadId":"t","includeTurns":true}}),
        );
        let Route::Runtime(sent) = &routes[0] else {
            panic!("read")
        };
        r.from_runtime(json!({"id":sent["id"],"result":{"thread":{"id":"t","status":{"type":"idle"},"turns":[{"id":"u","status":"completed","items":[{"type":"userMessage","id":"i"}]}]}}}));
        assert!(
            !r.from_client(Client::Remote("other".into()), revert.clone())
                .iter()
                .any(|v| matches!(v, Route::Runtime(_)))
        );
        assert!(
            r.from_client(helper.clone(), revert.clone())
                .iter()
                .any(|v| matches!(v, Route::Runtime(_)))
        );
        assert!(
            !r.from_client(helper, revert)
                .iter()
                .any(|v| matches!(v, Route::Runtime(_)))
        );
    }
    #[test]
    fn v052_presets_and_profiles_require_exact_schema_and_config_evidence() {
        let mut r = Router::default();
        let helper = Client::Remote("helper".into());
        r.loaded.insert("t".into());
        r.capabilities.0.insert(
            "thread/settings/update".into(),
            [
                "threadId",
                "approvalPolicy",
                "approvalsReviewer",
                "sandboxPolicy",
                "permissions",
            ]
            .into_iter()
            .map(str::to_owned)
            .collect(),
        );
        let update = |params| json!({"id":1,"method":"thread/settings/update","params":params});
        assert!(
            !r.from_client(
                helper.clone(),
                update(crate::permission_policy::preset("t", "auto").unwrap())
            )
            .iter()
            .any(|r| matches!(r, Route::Runtime(_)))
        );
        r.capabilities
            .0
            .get_mut("thread/settings/update")
            .unwrap()
            .insert("$autoReview".into());
        for id in ["request", "auto", "full"] {
            assert!(
                r.from_client(
                    helper.clone(),
                    update(crate::permission_policy::preset("t", id).unwrap())
                )
                .iter()
                .any(|r| matches!(r, Route::Runtime(_)))
            );
        }
        let named = update(json!({"threadId":"t","permissions":"existing"}));
        assert!(
            !r.from_client(helper.clone(), named.clone())
                .iter()
                .any(|r| matches!(r, Route::Runtime(_)))
        );
        r.capabilities
            .0
            .insert("config/read".into(), Default::default());
        let routes = r.from_client(
            helper.clone(),
            json!({"id":2,"method":"config/read","params":{}}),
        );
        let Route::Runtime(sent) = &routes[0] else {
            panic!("config");
        };
        r.from_runtime(json!({"id":sent["id"],"result":{"config":{"permissions":{"existing":{"extends":":workspace"},"bad":{"apiKey":"secret"}}}}}));
        assert!(
            r.from_client(helper.clone(), named.clone())
                .iter()
                .any(|r| matches!(r, Route::Runtime(_)))
        );
        assert!(
            !r.from_client(helper.clone(), named)
                .iter()
                .any(|r| matches!(r, Route::Runtime(_)))
        );
        assert!(
            !r.from_client(helper, update(json!({"threadId":"t","permissions":"bad"})))
                .iter()
                .any(|r| matches!(r, Route::Runtime(_)))
        );
    }
    #[test]
    fn v052_config_cwd_requires_native_proof_on_this_connection() {
        let mut r = Router::default();
        r.capabilities.0.insert(
            "config/read".into(),
            ["cwd", "includeLayers"]
                .into_iter()
                .map(str::to_owned)
                .collect(),
        );
        let helper = Client::Remote("helper".into());
        let read = json!({"id":1,"method":"config/read","params":{"cwd":"C:/verified","includeLayers":false}});
        assert!(
            !r.from_client(helper.clone(), read.clone())
                .iter()
                .any(|r| matches!(r, Route::Runtime(_)))
        );
        let routes = r.from_client(
            helper.clone(),
            json!({"id":2,"method":"thread/read","params":{"threadId":"t","includeTurns":false}}),
        );
        let Route::Runtime(sent) = &routes[0] else {
            panic!("read");
        };
        r.from_runtime(json!({"id":sent["id"],"result":{"thread":{"id":"t","cwd":"C:/verified"}}}));
        assert!(
            r.from_client(helper.clone(), read.clone())
                .iter()
                .any(|r| matches!(r, Route::Runtime(_)))
        );
        assert!(
            !r.from_client(Client::Remote("other".into()), read.clone())
                .iter()
                .any(|r| matches!(r, Route::Runtime(_)))
        );
        for params in [
            json!({"cwd":"C:/different","includeLayers":false}),
            json!({"cwd":"C:/verified","includeLayers":true}),
            json!({"cwd":"C:/verified","includeLayers":false,"profile":"x"}),
        ] {
            assert!(
                !r.from_client(
                    helper.clone(),
                    json!({"id":3,"method":"config/read","params":params})
                )
                .iter()
                .any(|r| matches!(r, Route::Runtime(_)))
            );
        }
        r.disconnect(&helper);
        assert!(
            !r.from_client(helper, read)
                .iter()
                .any(|r| matches!(r, Route::Runtime(_)))
        );
    }
    fn read_settings(r: &mut Router, thread: &str) -> Value {
        let routes = r.from_client(
            Client::Remote("helper".into()),
            json!({"id":"read","method":"pocket/threadSettings","params":{"threadId":thread}}),
        );
        let Route::Client(_, value) = &routes[0] else {
            panic!("local settings read")
        };
        value["result"].clone()
    }
    #[test]
    fn settings_event_uses_thread_settings_and_filters_config() {
        let mut r = Router::default();
        let native = json!({"method":"thread/settings/updated","params":{"threadId":"t","threadSettings":{"model":"gpt-test","effort":null,"serviceTier":"fast","collaborationMode":{"mode":"plan","settings":{"model":"gpt-test","reasoning_effort":"high","developer_instructions":"private"}},"cwd":"C:/private","permissions":"all","approvalPolicy":"never","config":{"apiKey":"secret"}}}});
        let routes = r.from_runtime(native.clone());
        assert!(matches!(&routes[0], Route::Client(Client::Desktop, v) if v == &native));
        let expected = json!({"approvalPolicy":"never","model":"gpt-test","effort":null,"serviceTier":"fast","collaborationMode":{"mode":"plan","settings":{"model":"gpt-test","reasoning_effort":"high"}}});
        assert!(
            matches!(&routes[1], Route::Broadcast(v) if v["params"]["threadSettings"] == expected)
        );
        assert_eq!(
            read_settings(&mut r, "t"),
            json!({"threadId":"t","settings":expected,"known":true})
        );
        assert_eq!(read_settings(&mut r, "other")["known"], false);
        r.disconnect(&Client::Remote("helper".into()));
        assert_eq!(read_settings(&mut r, "t")["settings"], expected);
    }
    #[test]
    fn settings_only_accepted_requests_apply_and_late_results_cannot_overwrite() {
        let mut r = Router::default();
        let request = |model: &str| json!({"id":1,"method":"turn/start","params":{"threadId":"t","model":model,"input":[],"permissions":"all"}});
        let rejected = runtime(r.from_client(Client::Desktop, request("rejected")));
        r.from_runtime(json!({"id":rejected["id"],"error":{"code":-1,"message":"no"}}));
        assert_eq!(read_settings(&mut r, "t")["known"], false);
        let old = runtime(r.from_client(Client::Desktop, request("old")));
        let new = runtime(r.from_client(Client::Desktop, request("new")));
        assert_eq!(read_settings(&mut r, "t")["known"], false);
        r.from_runtime(json!({"id":new["id"],"result":{"turn":{"id":"turn"}}}));
        r.from_runtime(json!({"id":old["id"],"result":{"turn":{"id":"turn"}}}));
        assert_eq!(
            read_settings(&mut r, "t")["settings"],
            json!({"model":"new"})
        );
        let read = runtime(r.from_client(
            Client::Desktop,
            json!({"id":2,"method":"thread/read","params":{"threadId":"t","includeTurns":false}}),
        ));
        r.from_runtime(json!({"id":read["id"],"result":{"thread":{"id":"other","model":"wrong"}}}));
        assert_eq!(
            read_settings(&mut r, "t")["settings"],
            json!({"model":"new"})
        );
        assert_eq!(read_settings(&mut r, "other")["known"], false);
    }
    #[test]
    fn settings_metadata_does_not_infer_plan_or_default_effort() {
        let mut r = Router::default();
        let read = runtime(r.from_client(
            Client::Desktop,
            json!({"id":2,"method":"thread/read","params":{"threadId":"t","includeTurns":false}}),
        ));
        r.from_runtime(json!({"id":read["id"],"result":{"thread":{"id":"t","model":"persisted","reasoningEffort":null,"turns":[{"model":"ignore-content"}]}}}));
        assert_eq!(
            read_settings(&mut r, "t")["settings"],
            json!({"model":"persisted","effort":null})
        );
        let request = runtime(r.from_client(Client::Desktop, json!({"id":3,"method":"turn/start","params":{"threadId":"t","model":null,"effort":null,"collaborationMode":null,"input":[]}})));
        r.from_runtime(json!({"id":request["id"],"result":{}}));
        assert_eq!(
            read_settings(&mut r, "t")["settings"],
            json!({"model":"persisted","effort":null})
        );
    }
    #[test]
    fn settings_read_is_local_unknown_and_strictly_scoped() {
        let mut r = Router::default();
        let phone = Client::Remote("helper".into());
        let routes = r.from_client(
            phone.clone(),
            json!({"id":1,"method":"pocket/threadSettings","params":{"threadId":"t"}}),
        );
        assert!(
            matches!(&routes[..], [Route::Client(_, v)] if v["result"] == json!({"threadId":"t","settings":{},"known":false}))
        );
        for params in [
            json!({}),
            json!({"threadId":"t","permissions":"all"}),
            json!({"threadId":"t","method":"config/read"}),
        ] {
            assert!(
                matches!(&r.from_client(phone.clone(), json!({"id":2,"method":"pocket/threadSettings","params":params}))[..], [Route::Client(_,v)] if v.get("error").is_some())
            );
        }
    }
    use super::*;
    use serde_json::json;
    fn runtime(routes: Vec<Route>) -> Value {
        match routes.into_iter().next().unwrap() {
            Route::Runtime(v) => v,
            _ => panic!("expected runtime"),
        }
    }
    #[test]
    fn helper_quota_reads_require_empty_params_and_actual_capabilities() {
        for method in ["account/rateLimits/read", "config/read"] {
            let mut router = Router::default();
            let helper = Client::Remote("authenticated-helper".into());
            let request = json!({"id":"original","method":method,"params":{}});
            assert!(
                matches!(&router.from_client(helper.clone(),request.clone())[..],[Route::Client(_,v)] if v.get("error").is_some())
            );
            router.capabilities.0.insert(
                method.into(),
                ["cwd".into(), "includeLayers".into(), "accountId".into()]
                    .into_iter()
                    .collect(),
            );
            let routes = router.from_client(helper.clone(), request);
            let [Route::Runtime(sent)] = &routes[..] else {
                panic!("capability-gated read must be routed")
            };
            assert_eq!(sent["method"], method);
            assert_eq!(sent["params"], json!({}));
            let routed = router.from_runtime(json!({"id":sent["id"],"result":{"fixture":true}}));
            assert!(
                matches!(&routed[..],[Route::Client(c,v)] if c==&helper && v["id"]=="original" && v["result"]["fixture"]==true)
            );
            for params in [
                Value::Null,
                json!([]),
                json!("bad"),
                json!({"cwd":"C:/secret"}),
                json!({"includeLayers":true}),
                json!({"accountId":"other"}),
                json!({"apiKey":"secret"}),
            ] {
                if method == "config/read" && params == json!({"includeLayers":true}) {
                    assert!(
                        router
                            .from_client(
                                helper.clone(),
                                json!({"id":"management-read","method":method,"params":params})
                            )
                            .iter()
                            .any(|r| matches!(r, Route::Runtime(_)))
                    );
                    continue;
                }
                let routes = router.from_client(
                    helper.clone(),
                    json!({"id":"bad","method":method,"params":params}),
                );
                assert!(matches!(&routes[..],[Route::Client(_,v)] if v.get("error").is_some()));
            }
            let missing =
                router.from_client(helper.clone(), json!({"id":"missing","method":method}));
            assert!(matches!(&missing[..],[Route::Client(_,v)] if v.get("error").is_some()));
            router.capabilities.0.remove(method);
            assert!(
                !router
                    .from_client(helper, json!({"id":"retired","method":method,"params":{}}))
                    .iter()
                    .any(|r| matches!(r, Route::Runtime(_)))
            );
            // Desktop keeps its native API and does not inherit the helper-only restriction.
            assert!(
                router
                    .from_client(
                        Client::Desktop,
                        json!({"id":"desktop","method":method,"params":{"includeLayers":true}})
                    )
                    .iter()
                    .any(|r| matches!(r, Route::Runtime(_)))
            );
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
