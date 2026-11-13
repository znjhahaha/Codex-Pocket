//! Supplemental display history from a native-proven rollout. Never emits requests or events.
use crate::media::history_media::question_records;
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::Path,
};

struct Call {
    turn: String,
    id: String,
    aliases: BTreeSet<String>,
    arguments: Value,
    answer: Option<Value>,
    verified: Option<Value>,
    invalid: bool,
}
fn identity(value: &Value) -> Option<&str> {
    value
        .as_str()
        .filter(|id| !id.is_empty() && id.len() <= 512 && !id.chars().any(char::is_control))
}
fn object(value: &Value) -> Option<Value> {
    if let Some(text) = value.as_str() {
        if text.len() > 128 * 1024 {
            return None;
        }
        serde_json::from_str(text).ok()
    } else {
        Some(value.clone())
    }
}
fn arguments(value: &Value) -> Option<Value> {
    let value = object(value)?;
    let questions = value["questions"].as_array()?;
    if questions.is_empty() || questions.len() > 16 {
        return None;
    }
    let mut ids = BTreeSet::new();
    let mut clean = Vec::new();
    for question in questions {
        let id = identity(&question["id"])?;
        if !ids.insert(id) {
            return None;
        }
        let text = question["question"]
            .as_str()
            .filter(|text| text.len() <= 8192)?;
        let mut q = json!({"id":id,"question":text,"isSecret":question["isSecret"] == true});
        if let Some(header) = question["header"]
            .as_str()
            .filter(|header| header.len() <= 512)
        {
            q["header"] = json!(header);
        }
        if let Some(is_other) = question["isOther"].as_bool() {
            q["isOther"] = json!(is_other);
        }
        if let Some(options) = question["options"].as_array() {
            if options.len() > 32 {
                return None;
            }
            q["options"] = Value::Array(
                options
                    .iter()
                    .map(|option| {
                        let mut option_clean = json!({});
                        for key in ["label", "description"] {
                            if let Some(text) =
                                option[key].as_str().filter(|text| text.len() <= 8192)
                            {
                                option_clean[key] = json!(text);
                            }
                        }
                        option_clean
                    })
                    .collect(),
            );
        }
        clean.push(q);
    }
    Some(json!({"questions":clean}))
}
fn answers(value: &Value, args: &Value) -> Option<Value> {
    let value = object(value)?;
    let source = value["answers"].as_object()?;
    let questions = args["questions"].as_array()?;
    if source.len() != questions.len() {
        return None;
    }
    let mut clean = serde_json::Map::new();
    for question in questions {
        let id = question["id"].as_str()?;
        let answer = source.get(id)?["answers"].as_array()?;
        if answer.is_empty()
            || answer.len() > 32
            || answer
                .iter()
                .any(|text| text.as_str().is_none_or(|text| text.len() > 8192))
        {
            return None;
        }
        clean.insert(id.into(), json!({"answers":if question["isSecret"] == true { json!(["[redacted]"]) } else { json!(answer) }}));
    }
    Some(json!({"answers":clean}))
}
fn turn<'a>(payload: &'a Value, active: Option<&'a str>) -> Option<&'a str> {
    let direct = payload.get("turn_id");
    let metadata = payload.pointer("/internal_chat_message_metadata_passthrough/turn_id");
    if let (Some(a), Some(b)) = (direct, metadata) {
        if a != b {
            return None;
        }
    }
    match direct.or(metadata) {
        Some(id) => identity(id),
        None => active,
    }
}
fn retained_answers(payload: &Value, args: &Value) -> Option<Value> {
    if payload.get("answers").is_some() {
        return answers(payload, args);
    }
    // Retained prompt formatting/order alone cannot map an answer to a question ID.
    let mut mapped = serde_json::Map::new();
    for entry in payload["questions"].as_array()? {
        let id = identity(&entry["id"])?;
        if mapped.contains_key(id) {
            return None;
        }
        mapped.insert(id.into(), json!({"answers":[entry["answer"].as_str()?]}));
    }
    answers(&json!({"answers":mapped}), args)
}
fn completed(path: &Path, thread: &str) -> Option<Vec<Call>> {
    let records = question_records(path, thread).ok()?;
    let mut calls: BTreeMap<String, Call> = BTreeMap::new();
    let mut active = None;
    for record in records {
        let payload = &record["payload"];
        if record["type"] == "turn_context"
            || record["type"] == "event_msg" && payload["type"] == "task_started"
        {
            active = identity(&payload["turn_id"]).map(str::to_owned);
            continue;
        }
        if record["type"] == "event_msg" && payload["type"] == "task_complete" {
            active = None;
            continue;
        }
        let Some(id) = identity(&payload["call_id"]) else {
            continue;
        };
        if payload["type"] == "function_call" {
            if let Some(existing) = calls.get_mut(id) {
                existing.invalid = true;
                continue;
            }
            let Some(turn) = turn(payload, active.as_deref()) else {
                continue;
            };
            let Some(arguments) = arguments(&payload["arguments"]) else {
                continue;
            };
            let mut aliases = BTreeSet::from([id.to_owned()]);
            if let Some(native_id) = identity(&payload["id"]) {
                aliases.insert(native_id.into());
            }
            calls.insert(
                id.into(),
                Call {
                    turn: turn.into(),
                    id: id.into(),
                    aliases,
                    arguments,
                    answer: None,
                    verified: None,
                    invalid: false,
                },
            );
        } else if let Some(call) = calls.get_mut(id) {
            if turn(payload, active.as_deref()) != Some(call.turn.as_str()) {
                call.invalid = true;
                continue;
            }
            if payload["type"] == "function_call_output" {
                let answer = answers(&payload["output"], &call.arguments);
                if answer.is_none()
                    || call
                        .answer
                        .as_ref()
                        .is_some_and(|previous| Some(previous) != answer.as_ref())
                {
                    call.invalid = true;
                }
                call.answer = answer;
            } else if payload["type"] == "verified_answer" {
                let answer = retained_answers(payload, &call.arguments);
                if let Some(answer) = answer {
                    if call
                        .verified
                        .as_ref()
                        .is_some_and(|previous| previous != &answer)
                    {
                        call.invalid = true;
                    }
                    call.verified = Some(answer);
                }
            }
        }
    }
    Some(
        calls
            .into_values()
            .filter(|call| {
                !call.invalid
                    && (call.answer.is_some() || call.verified.is_some())
                    && !matches!((&call.answer,&call.verified),(Some(a),Some(b)) if a != b)
            })
            .collect(),
    )
}
fn question_item(item: &Value) -> bool {
    item["type"] == "dynamicToolCall"
        && item["tool"].as_str().is_some_and(|tool| {
            matches!(tool, "request_user_input" | "functions.request_user_input")
        })
}
fn aliases(item: &Value) -> BTreeSet<String> {
    ["id", "callId", "call_id"]
        .into_iter()
        .filter_map(|key| identity(&item[key]).map(str::to_owned))
        .collect()
}
fn sanitize(item: &mut Value, proven: Option<&Call>) {
    let args = proven
        .map(|call| call.arguments.clone())
        .or_else(|| arguments(&item["arguments"]));
    let Some(args) = args else {
        // Display reconstruction has tighter limits than native questions. A
        // failure to parse cannot prove that the corresponding answer is public.
        for key in ["content", "result", "output", "toolOutputBlocks"] {
            item.as_object_mut().unwrap().remove(key);
        }
        item["contentItems"] =
            json!([{"type":"inputText","text":"[answer withheld: question metadata unavailable]"}]);
        return;
    };
    if !args["questions"]
        .as_array()
        .unwrap()
        .iter()
        .any(|q| q["isSecret"] == true)
    {
        return;
    }
    let answer = item["contentItems"]
        .as_array()
        .and_then(|parts| parts.iter().find_map(|part| answers(&part["text"], &args)))
        .unwrap_or_else(|| json!({"answers":{}}));
    for key in ["content", "result", "output", "toolOutputBlocks"] {
        item.as_object_mut().unwrap().remove(key);
    }
    item["arguments"] = args;
    item["contentItems"] = json!([{"type":"inputText","text":answer.to_string()}]);
}
/// Redaction applies to every native history response, including resume and fork.
/// It does not require or establish a rollout proof and never restores missing items.
pub(super) fn sanitize_history(value: &mut Value) {
    fn visit(value: &mut Value, depth: usize) {
        if depth > 64 {
            // Do not forward an uninspected deep subtree containing possible secret output.
            *value = Value::Null;
            return;
        }
        if question_item(value) {
            sanitize(value, None);
            return;
        }
        match value {
            Value::Array(values) => values.iter_mut().for_each(|value| visit(value, depth + 1)),
            Value::Object(values) => values
                .values_mut()
                .for_each(|value| visit(value, depth + 1)),
            _ => {}
        }
    }
    visit(value, 0);
}
pub(super) fn project(
    native: &mut Value,
    path: Option<&Path>,
    thread: &str,
    method: &str,
    requested_turn: &str,
    known: &BTreeSet<String>,
    canonical: &mut BTreeMap<String, BTreeSet<String>>,
) {
    let calls = path
        .and_then(|path| completed(path, thread))
        .unwrap_or_default();
    let terminal_page = method != "thread/items/list" || native["nextCursor"].is_null();
    let mut project_turn = |id: &str, items: &mut Vec<Value>, append: bool| {
        if !canonical.contains_key(id) && canonical.len() >= 4096 {
            canonical.pop_first();
        }
        let seen = canonical.entry(id.into()).or_default();
        for item in items.iter_mut().filter(|item| question_item(item)) {
            let identities = aliases(item);
            let call = calls
                .iter()
                .find(|call| call.turn == id && !call.aliases.is_disjoint(&identities));
            if seen.len() < 1024 {
                seen.extend(identities);
            }
            sanitize(item, call);
        }
        if !append || seen.len() >= 1024 {
            return;
        }
        for call in calls
            .iter()
            .filter(|call| call.turn == id && call.aliases.is_disjoint(seen))
        {
            let answer = call.answer.as_ref().or(call.verified.as_ref()).unwrap();
            items.push(json!({"type":"dynamicToolCall","id":call.id,"callId":call.id,"tool":"functions.request_user_input",
                "arguments":call.arguments,"status":"completed","success":true,"contentItems":[{"type":"inputText","text":answer.to_string()}],"pocketHistorySupplement":true}));
        }
    };
    if method == "thread/items/list" {
        if let Some(items) = native["data"].as_array_mut() {
            project_turn(
                requested_turn,
                items,
                terminal_page && known.contains(requested_turn),
            );
        }
    } else {
        let turns = if method == "thread/turns/list" {
            native.get_mut("data")
        } else {
            native.pointer_mut("/thread/turns")
        };
        if let Some(turns) = turns.and_then(Value::as_array_mut) {
            let mut unique = BTreeSet::new();
            let reliable = turns
                .iter()
                .all(|turn| identity(&turn["id"]).is_some_and(|id| unique.insert(id.to_owned())));
            for turn in turns {
                let Some(id) = identity(&turn["id"]).map(str::to_owned) else {
                    continue;
                };
                if let Some(items) = turn["items"].as_array_mut() {
                    project_turn(&id, items, reliable);
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs, io::Write};
    fn records(secret: bool) -> Vec<Value> {
        vec![
            json!({"type":"session_meta","payload":{"id":"thread"}}),
            json!({"type":"turn_context","payload":{"turn_id":"turn"}}),
            json!({"type":"response_item","payload":{"type":"function_call","id":"fc-id","call_id":"call-id","name":"functions.request_user_input","arguments":json!({"questions":[{"id":"q","question":"Choose","isSecret":secret}]}).to_string(),"internal_chat_message_metadata_passthrough":{"turn_id":"turn"}}}),
            json!({"type":"response_item","payload":{"type":"function_call_output","call_id":"call-id","output":json!({"answers":{"q":{"answers":["SECRET-SENTINEL"]}}}).to_string(),"internal_chat_message_metadata_passthrough":{"turn_id":"turn"}}}),
        ]
    }
    fn write(path: &Path, records: &[Value]) {
        let mut file = fs::File::create(path).unwrap();
        for record in records {
            serde_json::to_writer(&mut file, record).unwrap();
            file.write_all(b"\n").unwrap();
        }
    }
    fn view(path: &Path) -> Value {
        let mut view = json!({"thread":{"id":"thread","turns":[{"id":"turn","items":[]}]}});
        project(
            &mut view,
            Some(path),
            "thread",
            "thread/read",
            "",
            &BTreeSet::new(),
            &mut BTreeMap::new(),
        );
        view
    }
    #[test]
    fn unparseable_question_metadata_cannot_bypass_secret_redaction() {
        for args in [
            json!({"questions":(0..17).map(|i| json!({"id":format!("q{i}"),"question":"Choose","isSecret":i==0})).collect::<Vec<_>>()}),
            json!({"questions":[{"id":"q","question":"x".repeat(8193),"isSecret":true}]}),
            json!("invalid question arguments"),
        ] {
            let mut item = json!({"type":"dynamicToolCall","tool":"request_user_input","arguments":args,
                "contentItems":[{"type":"inputText","text":"SECRET-SENTINEL"}],
                "toolOutputBlocks":[{"text":"SECRET-SENTINEL"}],"result":"SECRET-SENTINEL"});
            sanitize_history(&mut item);
            assert!(!item.to_string().contains("SECRET-SENTINEL"));
        }
    }
    #[test]
    fn secret_answers_are_redacted_in_supplement_and_canonical_items() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("rollout.jsonl");
        write(&path, &records(true));
        let supplement = view(&path);
        assert_eq!(
            supplement["thread"]["turns"][0]["items"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
        assert!(!supplement.to_string().contains("SECRET-SENTINEL"));
        let mut canonical = supplement;
        let item = &mut canonical["thread"]["turns"][0]["items"][0];
        item["contentItems"][0]["text"] =
            json!(json!({"answers":{"q":{"answers":["SECRET-SENTINEL"]}}}).to_string());
        item["toolOutputBlocks"] = json!([{"type":"text","text":"SECRET-SENTINEL"}]);
        project(
            &mut canonical,
            Some(&path),
            "thread",
            "thread/read",
            "",
            &BTreeSet::new(),
            &mut BTreeMap::new(),
        );
        assert!(!canonical.to_string().contains("SECRET-SENTINEL"));
    }
    #[test]
    fn foreign_session_mismatched_turn_duplicate_call_and_pending_call_are_omitted() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("rollout.jsonl");
        for mode in 0..5 {
            let mut data = records(false);
            match mode {
                0 => data[0]["payload"]["id"] = json!("foreign"),
                1 => {
                    data[3]["payload"]["internal_chat_message_metadata_passthrough"]["turn_id"] =
                        json!("other-turn")
                }
                2 => data.insert(3, data[2].clone()),
                3 => {
                    data.pop();
                }
                _ => {
                    data.remove(1);
                    data[1]["payload"]
                        .as_object_mut()
                        .unwrap()
                        .remove("internal_chat_message_metadata_passthrough");
                }
            }
            write(&path, &data);
            assert!(
                view(&path)["thread"]["turns"][0]["items"]
                    .as_array()
                    .unwrap()
                    .is_empty(),
                "mode {mode}"
            );
        }
    }
    #[test]
    fn canonical_on_earlier_item_page_suppresses_supplement_on_final_page() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("rollout.jsonl");
        write(&path, &records(false));
        let mut canonical = BTreeMap::new();
        let known = BTreeSet::from(["turn".into()]);
        let mut first = json!({"data":[{"type":"dynamicToolCall","id":"native-id","callId":"call-id","tool":"request_user_input"}],"nextCursor":"next"});
        project(
            &mut first,
            Some(&path),
            "thread",
            "thread/items/list",
            "turn",
            &known,
            &mut canonical,
        );
        let mut last = json!({"data":[],"nextCursor":null});
        project(
            &mut last,
            Some(&path),
            "thread",
            "thread/items/list",
            "turn",
            &known,
            &mut canonical,
        );
        assert!(last["data"].as_array().unwrap().is_empty());
        let mut unknown = json!({"data":[]});
        project(
            &mut unknown,
            Some(&path),
            "thread",
            "thread/items/list",
            "unproven",
            &known,
            &mut canonical,
        );
        assert!(unknown["data"].as_array().unwrap().is_empty());
    }
    #[test]
    fn only_id_keyed_verified_answers_can_supply_retained_only_history() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("rollout.jsonl");
        let mut data = records(false);
        data.pop();
        data.push(json!({"type":"retained_context","payload":{"type":"verified_answer","call_id":"call-id","turn_id":"turn","questions":[{"id":"q","answer":"retained"}]}}));
        write(&path, &data);
        assert_eq!(
            view(&path)["thread"]["turns"][0]["items"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
        data[3]["payload"]["questions"][0]
            .as_object_mut()
            .unwrap()
            .remove("id");
        write(&path, &data);
        assert!(
            view(&path)["thread"]["turns"][0]["items"]
                .as_array()
                .unwrap()
                .is_empty()
        );
    }
    #[test]
    fn incomplete_tail_and_large_unrelated_records_do_not_lose_question_evidence() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("rollout.jsonl");
        let mut data = records(false);
        data.insert(2,json!({"type":"response_item","payload":{"type":"message","role":"assistant","content":"z".repeat(1024*1024)}}));
        write(&path, &data);
        fs::OpenOptions::new()
            .append(true)
            .open(&path)
            .unwrap()
            .write_all(b"{\"type\":")
            .unwrap();
        assert_eq!(
            view(&path)["thread"]["turns"][0]["items"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
    }
    #[test]
    #[ignore = "Explicit read-only local rollout probe; prints no questions or answers"]
    fn real_rollout_projects_four_completed_question_cards() {
        let path = Path::new(
            "C:/Users/82206/.codex/sessions/2026/11/11/rollout-2026-11-11T17-49-02-01a1ca02-c5db-74a0-a1d5-cf3ef5eb0473.jsonl",
        );
        let calls = completed(path, "01a1ca02-c5db-74a0-a1d5-cf3ef5eb0473")
            .expect("Question evidence unavailable");
        assert_eq!(calls.len(), 4);
        assert!(calls.iter().all(|call| call.answer.is_some()));
    }
    #[test]
    #[ignore = "Explicit read-only original-call probe; prints no prompts or answer contents"]
    fn real_rollout_original_three_call_answer_pairs_are_projected() {
        let path = Path::new(
            "C:/Users/82206/.codex/sessions/2026/11/11/rollout-2026-11-11T17-49-02-01a1ca02-c5db-74a0-a1d5-cf3ef5eb0473.jsonl",
        );
        let thread = "01a1ca02-c5db-74a0-a1d5-cf3ef5eb0473";
        let original = BTreeSet::from([
            "call_aovSj23g8TFVCA0QuahDnNH0",
            "call_31b7ehuvMkqVxzlTcrRGwFni",
            "call_9LGTeqVbguTP1243fhmM7DWS",
        ]);
        let calls = completed(path, thread).expect("Original question evidence unavailable");
        let selected: Vec<_> = calls
            .iter()
            .filter(|call| original.contains(call.id.as_str()))
            .collect();
        assert_eq!(selected.len(), 3);
        assert!(selected.iter().all(|call| call.answer.is_some()));
        let evidence = question_records(path, thread).unwrap();
        let verified = evidence
            .iter()
            .filter(|record| {
                record["type"] == "retained_context"
                    && record["payload"]["type"] == "verified_answer"
                    && record["payload"]["call_id"]
                        .as_str()
                        .is_some_and(|id| original.contains(id))
            })
            .count();
        assert_eq!(verified, 3);
        let turns: BTreeSet<_> = selected.iter().map(|call| call.turn.clone()).collect();
        let mut native = json!({"thread":{"id":thread,"turns":turns.iter().map(|id|json!({"id":id,"items":[]})).collect::<Vec<_>>()}});
        project(
            &mut native,
            Some(path),
            thread,
            "thread/read",
            "",
            &turns,
            &mut BTreeMap::new(),
        );
        let mut projected = 0;
        let mut individual_answers = 0;
        for turn in native["thread"]["turns"].as_array().unwrap() {
            for card in turn["items"].as_array().unwrap() {
                let Some(call) = selected.iter().find(|call| card["callId"] == call.id) else {
                    continue;
                };
                assert!(turn["id"] == call.turn && card["status"] == "completed");
                let answer: Value =
                    serde_json::from_str(card["contentItems"][0]["text"].as_str().unwrap())
                        .unwrap();
                assert!(
                    Some(&answer) == call.answer.as_ref(),
                    "Original answer projection changed"
                );
                individual_answers += answer["answers"].as_object().unwrap().len();
                projected += 1;
            }
        }
        assert_eq!(projected, 3);
        assert_eq!(individual_answers, 4);
    }
}
