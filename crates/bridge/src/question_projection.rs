//! Desktop's userInputResponse is a presentation item, not an app-server receipt.
//! Keep its request alias and repair it only from an exact native result. The
//! 26.1120 desktop reducer closes a server request with empty answers and does not
//! copy dynamicToolCall output to that presentation item.
use serde_json::{Value, json};
use std::collections::{HashSet, VecDeque};

#[derive(Default)]
pub(crate) struct QuestionProjection { requests: VecDeque<Request> }
#[derive(Default)]
pub(crate) struct Observation {
    pub desktop: Vec<Value>,
    pub native: Vec<Value>,
    pub matched_raw: bool,
}
struct Request {
    native_id: Value, desktop_id: String, thread: String, turn: String, item: String,
    questions: Vec<Value>, closed: bool, result: Option<Value>, emitted: bool,
}
impl QuestionProjection {
    pub fn requested(&mut self, event: &Value, desktop_id: &str) {
        if event["method"] != "item/tool/requestUserInput" { return; }
        let p = &event["params"];
        let id = |key| p[key].as_str().filter(|s| !s.is_empty() && s.len() <= 512);
        let (Some(thread), Some(turn), Some(item), Some(questions)) =
            (id("threadId"), id("turnId"), id("itemId"), p["questions"].as_array()) else { return; };
        let mut ids = HashSet::new();
        if questions.is_empty() || questions.len() > 128 || questions.iter().any(|q|
            q["id"].as_str().is_none_or(|s| s.is_empty() || !ids.insert(s)) || !q["question"].is_string()) { return; }
        // A reused native ID may not bind a new request to old answer evidence.
        self.requests.retain(|r| r.native_id != event["id"] && !(r.thread == thread && r.turn == turn && r.item == item));
        if self.requests.len() == 1024 { self.requests.pop_front(); }
        self.requests.push_back(Request { native_id: event["id"].clone(), desktop_id: desktop_id.into(),
            thread: thread.into(), turn: turn.into(), item: item.into(), questions: questions.clone(),
            closed: false, result: None, emitted: false });
    }
    pub fn resolved(&mut self, event: &Value) -> Vec<Value> {
        let mut output = Vec::new();
        for r in &mut self.requests {
            if event["params"]["threadId"] == r.thread && event["params"]["requestId"] == r.native_id {
                r.closed = true;
                if let Some(value) = r.notification() { output.push(value); }
            }
        }
        output
    }
    pub fn observe(&mut self, event: &Value) -> Observation {
        let mut output = Observation::default();
        if !matches!(event["method"].as_str(), Some("rawResponseItem/completed" | "item/completed")) { return output; }
        let p = &event["params"];
        let item = &p["item"];
        for r in &mut self.requests {
            if p["threadId"] != r.thread || p["turnId"] != r.turn { continue; }
            let result = if event["method"] == "rawResponseItem/completed" {
                if item["type"] != "function_call_output" || item["call_id"] != r.item { continue; }
                output.matched_raw = true;
                if r.result.is_some() { continue; }
                parse(&item["output"])
            } else {
                if item["type"] != "dynamicToolCall" || item["id"] != r.item || item["status"] != "completed"
                    || item["success"] == false || item["tool"].as_str().is_none_or(|s| s.rsplit('.').next() != Some("request_user_input")) { continue; }
                result_in_item(item)
            };
            if r.result.is_some() { continue; }
            if let Some(result) = result.and_then(|value| r.answers(&value)) {
                r.result = Some(result);
                if event["method"] == "rawResponseItem/completed" { output.native.push(r.native_result()); }
                if let Some(value) = r.notification() { output.desktop.push(value); }
            }
        }
        output
    }
}
fn parse(value: &Value) -> Option<Value> {
    let value = if let Some(s) = value.as_str() {
        if s.len() > 16 * 1024 * 1024 { return None; }
        serde_json::from_str(s).ok()?
    } else { value.clone() };
    value.get("answers")?.is_object().then_some(value)
}
fn result_in_item(item: &Value) -> Option<Value> {
    for field in ["contentItems", "toolOutputBlocks"] {
        let results: Vec<_> = item[field].as_array().into_iter().flatten()
            .filter(|p| matches!(p["type"].as_str(), Some("inputText" | "text")))
            .filter_map(|p| parse(&p["text"])).collect();
        if !results.is_empty() { return (results.len() == 1).then(|| results[0].clone()); }
    }
    None
}
impl Request {
    fn native_result(&self) -> Value {
        let answers: serde_json::Map<_,_> = self.result.as_ref().unwrap().as_object().unwrap().iter()
            .map(|(id,values)|(id.clone(),json!({"answers":values}))).collect();
        json!({"method":"item/completed","params":{"threadId":self.thread,"turnId":self.turn,
            "item":{"id":self.item,"callId":self.item,"type":"dynamicToolCall","tool":"request_user_input",
                "arguments":{"questions":self.questions},"status":"completed","success":true,
                "contentItems":[{"type":"inputText","text":json!({"answers":answers}).to_string()}],
                "pocketNativeQuestionResult":true}}})
    }
    fn answers(&self, result: &Value) -> Option<Value> {
        let input = result["answers"].as_object()?;
        if input.is_empty() { return Some(json!({})); }
        if input.len() != self.questions.len() { return None; }
        let mut answers = serde_json::Map::new();
        for q in &self.questions {
            let id = q["id"].as_str()?;
            let values = input.get(id)?["answers"].as_array()?;
            if values.len() > 32 || values.iter().any(|v| v.as_str().is_none_or(|s| s.len() > 65536)) { return None; }
            // Neither retained bridge state nor the ordinary desktop cache receives secret values.
            answers.insert(id.into(), if q["isSecret"] == true { json!(["敏感回答已隐藏"]) } else { json!(values) });
        }
        Some(Value::Object(answers))
    }
    fn notification(&mut self) -> Option<Value> {
        if !self.closed || self.emitted { return None; }
        let answers = self.result.as_ref()?;
        self.emitted = true;
        let questions: Vec<_> = self.questions.iter().map(|q| json!({"id":q["id"],"header":q["header"],
            "question":q["question"],"options":q["options"].as_array().cloned().unwrap_or_default()})).collect();
        Some(json!({"method":"item/completed","params":{"threadId":self.thread,"turnId":self.turn,
            "item":{"id":format!("user-input-response-{}",self.desktop_id),"type":"userInputResponse",
                "requestId":self.desktop_id,"turnId":self.turn,"questions":questions,"answers":answers,"completed":true}}}))
    }
}
