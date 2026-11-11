use super::*;

pub(super) fn thread_params(
    req: &StartRequest,
    directory: &Path,
    model: &str,
    resume: bool,
) -> Value {
    let mut v = json!({"model":model,"cwd":directory,"sandbox":if req.writes{"workspace-write"}else{"read-only"},"approvalPolicy":"on-request","approvalsReviewer":"user","baseInstructions":req.instructions,"runtimeWorkspaceRoots":[directory]});
    if resume {
        v["threadId"] = json!(req.thread_id);
    } else {
        v["ephemeral"] = json!(false);
        v["allowProviderModelFallback"] = json!(false);
        if req.role == Role::Coordinator {
            v["dynamicTools"] = json!(dynamic_tools());
        }
    }
    v
}
pub(super) fn turn_params(
    req: &StartRequest,
    thread: &str,
    directory: &Path,
    model: &str,
) -> Value {
    let sandbox = if req.writes {
        json!({"type":"workspaceWrite","writableRoots":[directory],"networkAccess":false,"excludeSlashTmp":true,"excludeTmpdirEnvVar":true})
    } else {
        json!({"type":"readOnly","networkAccess":false})
    };
    json!({"threadId":thread,"model":model,"cwd":directory,"input":[{"type":"text","text":req.prompt,"text_elements":[]}],"approvalPolicy":"on-request","approvalsReviewer":"user","sandboxPolicy":sandbox,"runtimeWorkspaceRoots":[directory],"clientUserMessageId":req.operation_id})
}
pub fn dynamic_tools() -> Vec<Value> {
    let string = json!({"type":"string","minLength":1,"maxLength":65536});
    let id = json!({"type":"string","minLength":1,"maxLength":256});
    let specs = [
        (
            "assistant_list_sessions",
            "Observe existing sessions without mutating them.",
            json!({"limit":{"type":"integer","minimum":1,"maximum":100}}),
            vec!["limit"],
        ),
        (
            "assistant_progress",
            "Read entrusted task's native progress.",
            json!({"task_id":id}),
            vec!["task_id"],
        ),
        (
            "assistant_create_task",
            "Create an independent entrusted task in an explicitly authorized directory.",
            json!({"prompt":string,"directory":{"type":"string","minLength":1,"maxLength":32768},"writes":{"type":"boolean"}}),
            vec!["prompt", "directory", "writes"],
        ),
        (
            "assistant_pause_task",
            "Pause only an entrusted assistant task.",
            json!({"task_id":id}),
            vec!["task_id"],
        ),
        (
            "assistant_request_input",
            "Wait for explicit human input; never answer approvals.",
            json!({"request_id":id,"text":string}),
            vec!["request_id", "text"],
        ),
        (
            "assistant_summarize",
            "Save a summary without starting another coordinator turn.",
            json!({"text":string}),
            vec!["text"],
        ),
    ];
    specs.into_iter().map(|(name,description,properties,required)|json!({"type":"function","name":name,"description":description,"inputSchema":{"type":"object","properties":properties,"required":required,"additionalProperties":false}})).collect()
}
pub(super) fn decode_tool(name: &str, args: &Value) -> Result<ToolCall> {
    let fields: &[&str] = match name {
        "assistant_list_sessions" => &["limit"],
        "assistant_progress" | "assistant_pause_task" => &["task_id"],
        "assistant_create_task" => &["prompt", "directory", "writes"],
        "assistant_request_input" => &["request_id", "text"],
        "assistant_summarize" => &["text"],
        _ => anyhow::bail!("Unknown assistant tool"),
    };
    let object = args.as_object().context("Tool arguments must be object")?;
    ensure!(
        object.len() == fields.len() && object.keys().all(|k| fields.contains(&k.as_str())),
        "Unknown/missing tool argument"
    );
    let get = |key: &str| {
        max_string(
            &args[key],
            if key == "text" || key == "prompt" {
                65536
            } else if key == "directory" {
                32768
            } else {
                256
            },
        )
    };
    Ok(match name {
        "assistant_list_sessions" => {
            let n = args["limit"].as_u64().context("Invalid session limit")?;
            ensure!((1..=100).contains(&n), "Invalid session limit");
            ToolCall::ListSessions { limit: n as usize }
        }
        "assistant_progress" => ToolCall::Progress {
            task_id: get("task_id")?,
        },
        "assistant_pause_task" => ToolCall::PauseTask {
            task_id: get("task_id")?,
        },
        "assistant_create_task" => ToolCall::CreateTask {
            prompt: get("prompt")?,
            directory: PathBuf::from(get("directory")?),
            writes: args["writes"].as_bool().context("Invalid writes")?,
        },
        "assistant_request_input" => ToolCall::RequestInput {
            request_id: get("request_id")?,
            text: get("text")?,
        },
        "assistant_summarize" => ToolCall::Summarize { text: get("text")? },
        _ => unreachable!(),
    })
}
fn max_string(v: &Value, max: usize) -> Result<String> {
    let s = v.as_str().context("Expected text")?;
    bounded_text(s, max)?;
    Ok(s.into())
}
pub(super) fn tool_response(value: &Value, success: bool) -> Result<Value> {
    let text = serde_json::to_string(value)?;
    bounded_text(&text, 65536)?;
    Ok(json!({"contentItems":[{"type":"inputText","text":text}],"success":success}))
}
pub(super) fn session_view(thread: &Value) -> Result<SessionView> {
    let thread_id = max_string(&thread["id"], 256)?;
    let summary = thread["name"]
        .as_str()
        .or_else(|| thread["preview"].as_str())
        .unwrap_or("Native session")
        .to_owned();
    bounded_text(&summary, 65536)?;
    Ok(SessionView { thread_id, summary })
}
pub(super) fn turn_text(turn: &Value) -> Result<String> {
    let mut text = Vec::new();
    for item in turn["items"].as_array().into_iter().flatten().take(100) {
        if item["type"] == "agentMessage" {
            if let Some(s) = item["text"].as_str() {
                text.push(s.to_owned());
            }
        }
    }
    let result = text.join("\n");
    ensure!(result.len() <= 65536, "Native summary too large");
    Ok(result)
}
pub(super) fn turn_summaries(response: &Value) -> Result<String> {
    let turns = response["data"].as_array().context("Invalid turns page")?;
    let mut text = Vec::new();
    for turn in turns.iter().take(100) {
        let s = turn_text(turn)?;
        if !s.is_empty() {
            text.push(s);
        }
    }
    let result = text.join("\n");
    ensure!(result.len() <= 65536, "Native summary too large");
    Ok(result)
}
pub(super) fn human_response(p: &PendingRequest, answer: &HumanResponse) -> Result<Value> {
    match (p.method.as_str(), answer) {
        (
            "item/commandExecution/requestApproval" | "item/fileChange/requestApproval",
            HumanResponse::Approval { approved },
        ) => {
            let decision = if *approved { "accept" } else { "decline" };
            if let Some(available) = p.params["availableDecisions"].as_array() {
                ensure!(
                    available.iter().any(|d| d == decision),
                    "Requested decision not offered by native"
                );
            }
            Ok(json!({"decision":decision}))
        }
        ("item/permissions/requestApproval", HumanResponse::Approval { approved }) => {
            let permissions = if *approved {
                p.params["permissions"].clone()
            } else {
                json!({})
            };
            ensure!(permissions.is_object(), "Invalid requested permissions");
            Ok(json!({"permissions":permissions,"scope":"turn"}))
        }
        ("item/tool/requestUserInput", HumanResponse::Answer(text)) => {
            bounded_text(text, 65536)?;
            let questions = p.params["questions"]
                .as_array()
                .context("Native questions missing")?;
            ensure!(
                !questions.is_empty() && questions.len() <= 32,
                "Invalid native question count"
            );
            let ids = questions
                .iter()
                .map(|q| max_string(&q["id"], 256))
                .collect::<Result<Vec<_>>>()?;
            let mut answers = serde_json::Map::new();
            if ids.len() == 1 {
                answers.insert(ids[0].clone(), json!({"answers":[text]}));
            } else {
                let supplied: Value = serde_json::from_str(text).context(
                    "Multiple questions require explicit JSON answers keyed by native question id",
                )?;
                let object = supplied.as_object().context("Answers must be object")?;
                ensure!(
                    object.len() == ids.len() && object.keys().all(|k| ids.contains(k)),
                    "Answers do not match native questions"
                );
                for id in ids {
                    let values = object[&id]
                        .as_array()
                        .context("Each answer must be string array")?;
                    ensure!(
                        !values.is_empty() && values.len() <= 32,
                        "Invalid answer list"
                    );
                    for v in values {
                        max_string(v, 65536)?;
                    }
                    answers.insert(id, json!({"answers":values}));
                }
            }
            Ok(json!({"answers":answers}))
        }
        _ => anyhow::bail!("Human response kind does not match native question"),
    }
}
