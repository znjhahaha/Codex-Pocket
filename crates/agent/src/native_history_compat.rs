//! Narrow compatibility for observed native history contracts. Schema presence
//! alone is not evidence that a new persistence mode can serve its history.
use crate::{capabilities::Capabilities, error::{Code, classify}, native::{Rpc, RpcFailure}};
use anyhow::{Result, ensure};
use serde_json::{Value, json};

pub fn prepare_creation(params: &mut Value, capabilities: &Capabilities) {
    // Only newly created Pocket threads; never migrate an existing native ID.
    if capabilities.supports("thread/start", &json!({"historyMode":"legacy"})) {
        params["historyMode"] = json!("legacy");
    }
}

fn unmaterialized(error: &RpcFailure, method: &str, params: &Value) -> bool {
    let RpcFailure::Native(error) = error else { return false; };
    let Some(thread) = params["threadId"].as_str().filter(|id| crate::settings::valid_thread(id)) else { return false; };
    let operation = match method {
        "thread/read" if params["includeTurns"] == true => "includeTurns",
        "thread/turns/list" if params["cursor"].is_null() => "thread/turns/list",
        _ => return false,
    };
    error["code"] == -32600 && error["message"] == format!(
        "thread {thread} is not materialized yet; {operation} is unavailable before first user message")
}

pub async fn call(rpc: &Rpc, method: &str, params: Value, before: &Value) -> Result<Value> {
    match rpc.call(method, params.clone()).await {
        Ok(result) => Ok(result),
        Err(error) if unmaterialized(&error, method, &params) => {
            let thread = &params["threadId"];
            let mut metadata = rpc.call("thread/read",json!({"threadId":thread,"includeTurns":false})).await?;
            let value = &metadata["thread"];
            ensure!(value["id"] == *thread && value["status"]["type"] == "idle" &&
                value["historyMode"] == "legacy" && value["preview"] == "" &&
                value["turns"].as_array().is_some_and(Vec::is_empty), "Empty history proof changed");
            ensure!(before["epoch"].is_string() && metadata["pocketCursor"]["epoch"] == before["epoch"], "History generation changed");
            // The error proves emptiness at the first read, not at the later
            // metadata reply. Retain the earlier fence so concurrent events replay.
            if method == "thread/turns/list" {
                metadata = json!({"data":[],"nextCursor":null});
            }
            metadata["pocketCursor"] = before.clone();
            metadata["pocketHistoryState"] = json!("awaiting_first_message");
            Ok(metadata)
        }
        Err(RpcFailure::Native(error)) if matches!(method,"thread/read"|"thread/turns/list") &&
            error["code"] == -32601 && error["message"] == "list_turns is not supported yet" => {
            Err(classify(anyhow::anyhow!("Native history backend cannot list this thread"),Code::NativeHistoryUnavailable))
        }
        Err(error) => Err(error.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_exact_native_empty_thread_evidence_can_degrade() {
        let params=json!({"threadId":"fresh","includeTurns":true});
        let error=RpcFailure::Native(json!({"code":-32600,"message":"thread fresh is not materialized yet; includeTurns is unavailable before first user message"}));
        assert!(unmaterialized(&error,"thread/read",&params));
        assert!(!unmaterialized(&error,"thread/read",&json!({"threadId":"other","includeTurns":true})));
        assert!(!unmaterialized(&error,"thread/read",&json!({"threadId":"fresh","includeTurns":false})));
        assert!(!unmaterialized(&RpcFailure::Timeout,"thread/read",&params));
        assert!(!unmaterialized(&RpcFailure::Native(json!({"code":-32600,"message":"not found"})),"thread/read",&params));
    }
}
