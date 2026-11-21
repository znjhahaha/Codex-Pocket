//! Scope evidence for independent work. A retained unknown receipt never grants
//! authority, and an old record without scope cannot prove a write independent.
use crate::recovery::{CommandRecord, RunRecord, Store};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Scope {
    pub thread: Option<String>,
    pub root: PathBuf,
    pub writes: bool,
}
fn physical(path: &str) -> Option<PathBuf> {
    let path = Path::new(path);
    if !path.is_absolute() {
        return None;
    }
    let path = std::fs::canonicalize(path).ok()?;
    path.is_dir().then_some(path)
}
fn root_key(path: &Path) -> String {
    let s = path.to_string_lossy().replace('/', "\\");
    if cfg!(windows) { s.to_lowercase() } else { s }
}
fn overlaps(a: &Path, b: &Path) -> bool {
    let (a, b) = (root_key(a), root_key(b));
    a == b || a.starts_with(&(b.clone() + "\\")) || b.starts_with(&(a + "\\"))
}
fn id(value: &Value) -> Option<&str> {
    value
        .as_str()
        .filter(|s| !s.is_empty() && s.len() <= 256 && !s.contains('\0'))
}
fn pending_effect(record: &CommandRecord) -> bool {
    !record.completed
        || (record.method.as_deref() == Some("turn/start")
            && record.reply.as_ref().is_some_and(|r| {
                r.get("error").is_none() && id(&r["result"]["turn"]["id"]).is_none()
            }))
}

impl Store {
    pub(crate) fn scope_before_answer(&mut self, request: &str, response: &Value) {
        let Some(pending) = self.state.pending.get(request) else {
            return;
        };
        if !matches!(
            pending.request["method"].as_str(),
            Some(
                "item/commandExecution/requestApproval"
                    | "item/fileChange/requestApproval"
                    | "item/permissions/requestApproval"
            )
        ) {
            return;
        }
        let result = &response["result"];
        let declined = matches!(result["decision"].as_str(), Some("decline" | "cancel"))
            || result["permissions"]
                .as_object()
                .is_some_and(|p| p.is_empty());
        if declined {
            return;
        }
        let Some(thread) = pending.request["params"]["threadId"]
            .as_str()
            .map(str::to_owned)
        else {
            return;
        };
        // User approval may exceed the original sandbox; do not retain a false
        // read-only/disjoint writer proof after granting it.
        self.threads.remove(&thread);
        for c in self
            .state
            .commands
            .values_mut()
            .chain(self.state.answers.values_mut())
        {
            if c.scope
                .as_ref()
                .is_some_and(|s| s.thread.as_ref() == Some(&thread))
            {
                c.scope = None;
            }
        }
    }
    pub(crate) fn request_scope(&self, value: &Value) -> Option<Scope> {
        let p = value["params"].as_object()?;
        let method = value["method"].as_str()?;
        if !matches!(
            method,
            "thread/start" | "thread/resume" | "turn/start" | "turn/interrupt"
        ) {
            return None;
        }
        let allowed: &[&str] = match method {
            "thread/start" | "thread/resume" => &[
                "threadId",
                "model",
                "cwd",
                "sandbox",
                "approvalPolicy",
                "approvalsReviewer",
                "baseInstructions",
                "runtimeWorkspaceRoots",
                "ephemeral",
                "allowProviderModelFallback",
                "dynamicTools",
            ],
            "turn/start" => &[
                "threadId",
                "model",
                "cwd",
                "input",
                "approvalPolicy",
                "approvalsReviewer",
                "sandboxPolicy",
                "runtimeWorkspaceRoots",
                "clientUserMessageId",
            ],
            _ => &["threadId", "turnId"],
        };
        if p.keys().any(|k| !allowed.contains(&k.as_str())) {
            return None;
        }
        let thread = if method == "thread/start" {
            if p.contains_key("threadId") {
                return None;
            }
            None
        } else {
            Some(id(p.get("threadId")?)?.to_owned())
        };
        if method == "turn/interrupt" {
            let scope = self.threads.get(thread.as_ref()?)?;
            return self
                .state
                .runs
                .values()
                .any(|r| {
                    r.generation == self.state.generation
                        && Some(&r.thread_id) == thread.as_ref()
                        && r.turn_id == p.get("turnId").and_then(id).unwrap_or("")
                        && r.state == "active"
                })
                .then(|| scope.clone());
        }
        if p.get("approvalPolicy")? != "on-request" || p.get("approvalsReviewer")? != "user" {
            return None;
        }
        let root = physical(p.get("cwd")?.as_str()?)?;
        let roots = p.get("runtimeWorkspaceRoots")?.as_array()?;
        if roots.len() != 1 || physical(roots[0].as_str()?)? != root {
            return None;
        }
        let writes = if method == "turn/start" {
            let policy = p.get("sandboxPolicy")?.as_object()?;
            match policy.get("type")?.as_str()? {
                "readOnly"
                    if policy.len() == 2
                        && policy.get("networkAccess") == Some(&Value::Bool(false)) =>
                {
                    false
                }
                "workspaceWrite"
                    if policy.len() == 5
                        && policy.get("networkAccess") == Some(&Value::Bool(false))
                        && policy.get("excludeSlashTmp") == Some(&Value::Bool(true))
                        && policy.get("excludeTmpdirEnvVar") == Some(&Value::Bool(true)) =>
                {
                    let roots = policy.get("writableRoots")?.as_array()?;
                    if roots.len() != 1 || physical(roots[0].as_str()?)? != root {
                        return None;
                    }
                    true
                }
                _ => return None,
            }
        } else {
            match p.get("sandbox")?.as_str()? {
                "read-only" => false,
                "workspace-write" => true,
                _ => return None,
            }
        };
        let scope = Scope {
            thread,
            root,
            writes,
        };
        if let Some(thread) = &scope.thread {
            if self.threads.get(thread) != Some(&scope) {
                return None;
            }
        }
        Some(scope)
    }

    /// Same-generation native success is the sole source of thread ownership.
    /// A read/list result or a phone-supplied ID never registers a fresh thread.
    pub(crate) fn observe_scope(&mut self, value: &Value) -> anyhow::Result<()> {
        if value.get("method").is_some() || value.get("error").is_some() {
            return Ok(());
        }
        let Some(key) = crate::protocol::key(&value["id"]) else {
            return Ok(());
        };
        let Some(record) = self.state.commands.get_mut(&key) else {
            return Ok(());
        };
        if record.generation != self.state.generation || record.completed {
            return Ok(());
        }
        if let Some(scope) = record.scope.as_mut().filter(|s| s.thread.is_none()) {
            if let Some(thread) = id(&value["result"]["thread"]["id"]) {
                if self.threads.contains_key(thread)
                    || self.state.runs.values().any(|r| r.thread_id == thread)
                {
                    return Ok(());
                }
                let result = &value["result"];
                if result["approvalPolicy"] != "on-request"
                    || result["approvalsReviewer"] != "user"
                    || result["sandbox"]["type"]
                        != if scope.writes {
                            "workspaceWrite"
                        } else {
                            "readOnly"
                        }
                    || result["cwd"].as_str().and_then(physical).as_ref() != Some(&scope.root)
                {
                    return Ok(());
                }
                scope.thread = Some(thread.into());
                self.threads.insert(thread.into(), scope.clone());
            }
        }
        if record.method.as_deref() == Some("turn/start") {
            if let (Some(scope), Some(turn)) = (&record.scope, id(&value["result"]["turn"]["id"])) {
                if let Some(thread) = &scope.thread {
                    let run = RunRecord {
                        generation: self.state.generation.clone(),
                        thread_id: thread.clone(),
                        turn_id: turn.into(),
                        state: if matches!(
                            value["result"]["turn"]["status"].as_str(),
                            Some("completed" | "interrupted" | "failed")
                        ) {
                            "completed"
                        } else {
                            "active"
                        }
                        .into(),
                    };
                    self.state.observe_run(run, false)?;
                }
            }
        }
        Ok(())
    }

    pub(crate) fn admits_independent(&self, value: &Value) -> bool {
        let Some(scope) = self.request_scope(value) else {
            return false;
        };
        if value["method"] == "turn/interrupt" {
            return true;
        }
        if let Some(thread) = &scope.thread {
            if self.state.runs.values().any(|r| {
                &r.thread_id == thread
                    && r.state != "completed"
                    && !self.resources_ended(&r.generation)
            }) || self.state.pending.values().any(|p| {
                p.request["params"]["threadId"] == thread.as_str()
                    && !self.resources_ended(&p.generation)
            }) || self
                .state
                .commands
                .values()
                .chain(self.state.answers.values())
                .any(|c| {
                    !self.resources_ended(&c.generation)
                        && pending_effect(c)
                        && c.scope
                            .as_ref()
                            .is_some_and(|s| s.thread.as_ref() == Some(thread))
                })
            {
                return false;
            }
        }
        if !scope.writes {
            return true;
        }
        // Every unresolved effect must have verified scope before declaring a
        // new writer independent. Legacy unscoped tombstones remain untouched.
        let conflicts = |other: Option<&Scope>| -> bool {
            other.is_none_or(|s| {
                physical(&s.root.to_string_lossy()).as_ref() != Some(&s.root)
                    || (s.writes && overlaps(&s.root, &scope.root))
            })
        };
        if self
            .state
            .commands
            .values()
            .chain(self.state.answers.values())
            .any(|c| {
                !self.resources_ended(&c.generation)
                    && pending_effect(c)
                    && conflicts(c.scope.as_ref())
            })
        {
            return false;
        }
        if self.state.pending.values().any(|p| {
            if self.resources_ended(&p.generation) {
                return false;
            }
            if matches!(
                p.request["method"].as_str(),
                Some(
                    "item/commandExecution/requestApproval"
                        | "item/fileChange/requestApproval"
                        | "item/permissions/requestApproval"
                )
            ) {
                return true;
            }
            let thread = p.request["params"]["threadId"].as_str();
            conflicts(thread.and_then(|t| self.threads.get(t)))
        }) {
            return false;
        }
        !self
            .state
            .runs
            .values()
            .filter(|r| r.state != "completed" && !self.resources_ended(&r.generation))
            .any(|r| {
                let scope = self.threads.get(&r.thread_id).or_else(|| {
                    self.state
                        .commands
                        .values()
                        .filter_map(|c| c.scope.as_ref())
                        .find(|s| s.thread.as_deref() == Some(&r.thread_id))
                });
                conflicts(scope)
            })
    }
    fn resources_ended(&self, generation: &str) -> bool {
        generation != self.state.generation && self.ended_generations.contains(generation)
    }
}
