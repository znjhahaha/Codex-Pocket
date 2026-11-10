//! Durable evidence for user-input delivery. Generic request resolution is not an answer.
//! Only native item results with the original store/thread/turn/item can confirm it.
use anyhow::{Result, bail, ensure};
use pocket_core::ledger::CommandKey;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{collections::BTreeSet, path::Path};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase { Dispatching, Forwarded, AwaitingConfirmation, Confirmed, AnsweredElsewhere, Cancelled }
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Receipt {
    pub command: CommandKey,
    pub runtime: String,
    pub store: String,
    pub thread: String,
    pub turn: String,
    pub item: String,
    pub request: Value,
    pub question_ids: BTreeSet<String>,
    // Secret answers are neither stored nor hashed: low-entropy secrets must not be guessable.
    pub expected: Option<String>,
    pub phase: Phase,
}
#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Receipts { records: Vec<Receipt> }
impl Receipts {
    pub fn open(state: &Path) -> Result<Self> {
        let path = state.join("question-receipts.json");
        if !path.try_exists()? { return Ok(Self::default()); }
        let bytes = crate::storage::read_physical(&path, 16 * 1024 * 1024)?;
        let value: Self = serde_json::from_slice(&bytes)?;
        ensure!(value.records.len() <= 50_000, "Question receipt limit");
        let mut commands=BTreeSet::new();
        for r in &value.records {
            ensure!(commands.insert(r.command.clone()),"Duplicate question receipt");
            ensure!([&r.runtime,&r.store,&r.thread,&r.turn,&r.item,&r.command.caller_id,&r.command.command_id].into_iter().all(|v|valid_id(v)),"Invalid receipt identity");
            ensure!(!r.question_ids.is_empty()&&r.question_ids.len()<=128&&r.question_ids.iter().all(|v|valid_id(v)),"Invalid receipt questions");
            ensure!(r.expected.as_ref().is_none_or(|v|v.len()==64&&v.bytes().all(|b|b.is_ascii_hexdigit())),"Invalid answer fingerprint");
        }
        Ok(value)
    }
    pub fn save(&self, state: &Path) -> Result<()> {
        let bytes=serde_json::to_vec(self)?;
        ensure!(bytes.len()<=16*1024*1024,"Question receipt limit");
        crate::storage::atomic_write(&state.join("question-receipts.json"), &bytes)
    }
    pub fn reserve(&mut self, command: CommandKey, runtime: &str, store: &str, event: &Value, response: &Value) -> Result<()> {
        if event["method"] != "item/tool/requestUserInput" { return Ok(()); }
        let p = &event["params"];
        let string = |name| p[name].as_str().filter(|s| valid_id(s)).map(str::to_owned);
        let (Some(thread), Some(turn), Some(item)) = (string("threadId"), string("turnId"), string("itemId")) else {
            // Legacy requests remain protected by the ledger but cannot be matched to history.
            return Ok(());
        };
        ensure!(self.records.len() < 50_000, "Question receipt capacity");
        ensure!(!self.records.iter().any(|r| r.command == command), "Duplicate receipt reservation");
        let questions = p["questions"].as_array().ok_or_else(|| anyhow::anyhow!("Missing questions"))?;
        let ids = questions.iter().filter_map(|q|q["id"].as_str().map(str::to_owned)).collect::<BTreeSet<_>>();
        ensure!(ids.len() == questions.len() && !ids.is_empty() && ids.len()<=128 && ids.iter().all(|v|valid_id(v)), "Invalid question identifiers");
        let secret = questions.iter().any(|q| q["isSecret"] == true);
        let expected = if secret { None } else { Some(answer_digest(response, &ids)?) };
        self.records.push(Receipt { command, runtime:runtime.into(), store:store.into(), thread, turn, item,
            request:event["id"].clone(), question_ids:ids, expected, phase:Phase::Dispatching });
        Ok(())
    }
    pub fn forwarded(&mut self, key: &CommandKey) {
        if let Some(r)=self.records.iter_mut().find(|r|r.command==*key && r.phase==Phase::Dispatching) { r.phase=Phase::Forwarded; }
    }
    pub fn unresolved_request(&self, store:&str, event:&Value)->bool {
        let p=&event["params"];
        event["method"]=="item/tool/requestUserInput" && self.records.iter().any(|r|
            !terminal(r.phase)&&r.store==store&&p["threadId"]==r.thread&&p["turnId"]==r.turn&&p["itemId"]==r.item)
    }
    pub fn unresolved(&mut self, key: &CommandKey) {
        if let Some(r)=self.records.iter_mut().find(|r|r.command==*key && !terminal(r.phase)) {r.phase=Phase::AwaitingConfirmation;}
    }
    pub fn status(&self, key: &CommandKey) -> Option<Value> {
        self.records.iter().find(|r|r.command==*key).map(public)
    }
    pub fn terminal_receipts(&self) -> Vec<(CommandKey, Phase)> {
        self.records.iter().filter(|r|terminal(r.phase)).map(|r|(r.command.clone(),r.phase)).collect()
    }
    pub fn list(&self, caller: &str, thread: Option<&str>) -> Vec<Value> {
        self.records.iter().filter(|r|r.command.caller_id==caller && thread.is_none_or(|t| t==r.thread))
            .rev().take(256).map(public).collect()
    }
    pub fn observe(&mut self, store: &str, value: &Value) -> Vec<(CommandKey, Phase)> {
        let mut changed=Vec::new();
        if value["method"]=="item/completed" {
            self.item(store, value["params"]["threadId"].as_str().unwrap_or(""), value["params"]["turnId"].as_str().unwrap_or(""), &value["params"]["item"], &mut changed);
        }
        if let Some(thread)=value.get("thread") {
            let id=thread["id"].as_str().unwrap_or("");
            for turn in thread["turns"].as_array().into_iter().flatten() {
                for item in turn["items"].as_array().into_iter().flatten() {
                    self.item(store,id,turn["id"].as_str().unwrap_or(""),item,&mut changed);
                }
            }
        }
        changed
    }
    fn item(&mut self, store:&str, thread:&str, turn:&str, item:&Value, changes:&mut Vec<(CommandKey,Phase)>) {
        if item["type"]!="dynamicToolCall" || item["status"]!="completed" || item["success"]==false ||
            item["tool"].as_str().is_none_or(|name|name.rsplit('.').next()!=Some("request_user_input")) { return; }
        let answers:Vec<Value>=item["contentItems"].as_array().into_iter().flatten().filter_map(|p| {
            // The broker's bounded media projection normalizes native inputText
            // to text before delivering this authenticated item to the ledger.
            if p["type"]!="inputText" && p["type"]!="text" {return None;}
            let text=p["text"].as_str()?;
            // Use the existing native frame ceiling. Four permitted 64KiB
            // answers already exceed the old 256KiB limit once JSON is added.
            if text.len()>16*1024*1024 {return None;}
            let value:Value=serde_json::from_str(text).ok()?;
            value["answers"].is_object().then_some(value)
        }).collect();
        if answers.len()!=1 {return;}
        for r in self.records.iter_mut().filter(|r| !terminal(r.phase) && r.store==store && r.thread==thread && r.turn==turn && item["id"]==r.item) {
            let result=&answers[0];
            r.phase=if result["answers"].as_object().is_some_and(|a| a.is_empty()) {Phase::Cancelled}
                else if let Ok(actual)=answer_digest(result,&r.question_ids) {
                    match &r.expected {Some(expected) if *expected==actual => Phase::Confirmed,Some(_)=>Phase::AnsweredElsewhere,None=>Phase::AwaitingConfirmation}
                } else {continue;};
            changes.push((r.command.clone(),r.phase));
        }
    }
}
fn terminal(phase:Phase)->bool {matches!(phase,Phase::Confirmed|Phase::AnsweredElsewhere|Phase::Cancelled)}
fn valid_id(value:&str)->bool {!value.is_empty()&&value.len()<=512&&!value.chars().any(char::is_control)}
fn public(r:&Receipt)->Value {json!({"commandId":r.command.command_id,"runtimeId":r.runtime,"threadId":r.thread,"turnId":r.turn,"itemId":r.item,"requestId":r.request,"phase":r.phase})}
fn answer_digest(response:&Value, ids:&BTreeSet<String>)->Result<String> {
    let answers=response["answers"].as_object().ok_or_else(||anyhow::anyhow!("Missing answers"))?;
    ensure!(answers.len()==ids.len(),"Answer identifiers differ");
    let mut canonical=serde_json::Map::new();
    for id in ids {
        let value=answers.get(id).and_then(|a|a["answers"].as_array()).ok_or_else(||anyhow::anyhow!("Invalid answer"))?;
        if value.len()>32 || value.iter().any(|s|s.as_str().is_none_or(|s|s.len()>65536)){bail!("Invalid answer text")}
        canonical.insert(id.clone(),json!({"answers":value}));
    }
    Ok(format!("{:x}",Sha256::digest(serde_json::to_vec(&canonical)?)))
}

#[cfg(test)] mod tests {
    use super::*;
    fn key()->CommandKey {CommandKey{caller_id:"phone".into(),command_id:"command".into()}}
    fn event(secret:bool)->Value {json!({"id":"request","method":"item/tool/requestUserInput","params":{"threadId":"thread","turnId":"turn","itemId":"item","questions":[{"id":"q","isSecret":secret}]}})}
    fn answer(text:&str)->Value {json!({"answers":{"q":{"answers":[text]}}})}
    fn result(text:&str)->Value {json!({"method":"item/completed","params":{"threadId":"thread","turnId":"turn","item":{"id":"item","type":"dynamicToolCall","tool":"functions.request_user_input","status":"completed","success":true,"contentItems":[{"type":"inputText","text":answer(text).to_string()}]}}})}
    #[test] fn resolution_never_proves_answer_and_exact_native_item_does() {
        let mut r=Receipts::default();r.reserve(key(),"runtime","store",&event(false),&answer("chosen")).unwrap();r.forwarded(&key());
        assert!(r.observe("store",&json!({"method":"serverRequest/resolved","params":{"requestId":"request","threadId":"thread"}})).is_empty());
        assert!(r.observe("other-store",&result("chosen")).is_empty());
        let mut wrong=result("chosen");wrong["params"]["turnId"]=json!("other-turn");assert!(r.observe("store",&wrong).is_empty());
        assert_eq!(r.observe("store",&result("chosen")),vec![(key(),Phase::Confirmed)]);
        assert!(r.observe("store",&result("chosen")).is_empty());
    }
    #[test] fn restart_reconciles_receipt_without_storing_answer_text() {
        let dir=tempfile::tempdir().unwrap();let mut r=Receipts::default();r.reserve(key(),"old-runtime","store",&event(false),&answer("private-choice-marker")).unwrap();r.save(dir.path()).unwrap();
        let bytes=std::fs::read_to_string(dir.path().join("question-receipts.json")).unwrap();assert!(!bytes.contains("private-choice-marker"));
        let mut restored=Receipts::open(dir.path()).unwrap();assert_eq!(restored.observe("store",&result("private-choice-marker")),vec![(key(),Phase::Confirmed)]);
        assert!(restored.list("other-device",None).is_empty());
    }
    #[test] fn secret_results_are_not_hashed_or_claimed_as_our_answer() {
        let mut r=Receipts::default();r.reserve(key(),"runtime","store",&event(true),&answer("secret")).unwrap();
        assert!(r.records[0].expected.is_none());r.observe("store",&result("secret"));assert_eq!(r.records[0].phase,Phase::AwaitingConfirmation);
    }
    #[test] fn different_authoritative_answer_is_handled_elsewhere() {
        let mut r=Receipts::default();r.reserve(key(),"runtime","store",&event(false),&answer("ours")).unwrap();
        assert_eq!(r.observe("store",&result("theirs")),vec![(key(),Phase::AnsweredElsewhere)]);
    }
    #[test] fn permitted_multi_question_answers_are_confirmed_beyond_old_parse_limit() {
        let mut questions=Vec::new();let mut answers=serde_json::Map::new();
        for id in ["a","b","c","d"] {
            questions.push(json!({"id":id,"isSecret":false}));
            answers.insert(id.into(),json!({"answers":["x".repeat(65536)]}));
        }
        let mut input=event(false);input["params"]["questions"]=json!(questions);
        let answer=json!({"answers":answers});
        let mut observed=result("");observed["params"]["item"]["contentItems"][0]["text"]=json!(answer.to_string());
        let mut receipts=Receipts::default();receipts.reserve(key(),"runtime","store",&input,&answer).unwrap();
        assert_eq!(receipts.observe("store",&observed),vec![(key(),Phase::Confirmed)]);
    }
}
