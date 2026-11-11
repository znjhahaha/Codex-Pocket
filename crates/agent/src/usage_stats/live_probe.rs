//! Explicit local-only, current-user read-only acceptance probe. Never an RPC entry.
#![allow(dead_code)]
pub mod auth {
    pub fn hash(value: &str) -> String {
        use sha2::Digest;
        hex::encode(sha2::Sha256::digest(value.as_bytes()))
    }
}
#[path = "../storage.rs"]
pub mod storage;
#[path = "mod.rs"]
mod usage_stats;
use serde_json::json;
#[tokio::main]
async fn main() {
    // Metadata only: do not hash/dump the database or read forbidden column contents.
    let path = usage_stats::default_db_path().expect("Current user home is required");
    let metadata = || {
        std::fs::metadata(&path)
            .ok()
            .map(|m| (m.len(), m.modified().ok()))
    };
    let before = metadata();
    let service = usage_stats::service::current_user_service();
    let sources = service.sources().await;
    let query = usage_stats::service::prepare_read(&json!({}), storage::now() as i64).unwrap();
    let read = service.read(query.clone()).await;
    let result = &read["result"];
    let after = metadata();
    let output = json!({"verified_at":storage::now(),"mode":"SQLITE_OPEN_READ_ONLY/query_only","schema_version":sources["sources"][0]["schema_version"],"source_availability":sources["sources"][0]["availability"],"availability":result["availability"],"source_errors":sources["sources"][0]["errors"],"errors":result["errors"],"window":{"start_at":query.start_at,"end_at":query.end_at,"days":7,"app_type":"codex","data_source":"proxy"},"coverage":result["coverage"],"totals":result["data"]["totals"],"trend_bucket_count":result["data"]["trend"].as_array().map(Vec::len),"provider_group_count":result["data"]["provider_groups"]["total_groups"],"model_group_count":result["data"]["model_groups"]["total_groups"],"main_file_metadata_unchanged":before==after});
    println!("{}", serde_json::to_string_pretty(&output).unwrap());
    if sources["sources"][0]["availability"] != "ready"
        || sources["sources"][0]["schema_version"] != 24
        || result["availability"] != "ready"
    {
        std::process::exit(2);
    }
}
