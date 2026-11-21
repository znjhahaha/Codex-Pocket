//! Explicit local read-only acceptance. Emits counts, never conversation text.
use anyhow::Result;
use serde_json::json;
fn main() -> Result<()> {
    let home = std::env::args_os()
        .nth(1)
        .map(std::path::PathBuf::from)
        .ok_or_else(|| anyhow::anyhow!("Pass the selected Codex home"))?;
    if let Some(thread) = std::env::args().nth(2) {
        let limit = std::env::args()
            .nth(3)
            .map(|v| v.parse::<usize>())
            .transpose()?
            .unwrap_or(20);
        let pages = std::env::args()
            .nth(4)
            .map(|v| v.parse::<usize>())
            .transpose()?
            .unwrap_or(1)
            .clamp(1, 100);
        let mut cursor = None;
        for page_index in 0..pages {
            let started = std::time::Instant::now();
            match pocket_agent::history_catalog::read(&home, &thread, cursor.as_deref(), limit) {
                Ok(page) => {
                    let turns = page["data"].as_array().unwrap();
                    let items: usize = turns
                        .iter()
                        .map(|t| t["items"].as_array().map_or(0, Vec::len))
                        .sum();
                    let mut types = std::collections::BTreeMap::<String, usize>::new();
                    let mut statuses = std::collections::BTreeMap::<String, usize>::new();
                    let newest = turns.first();
                    for turn in turns {
                        *statuses
                            .entry(turn["status"].as_str().unwrap_or("missing").into())
                            .or_default() += 1;
                        for item in turn["items"].as_array().into_iter().flatten() {
                            *types
                                .entry(item["type"].as_str().unwrap_or("missing").into())
                                .or_default() += 1;
                        }
                    }
                    println!(
                        "{}",
                        json!({"ok":true,"page":page_index,"thread":thread,"source":page["thread"]["source"],"historyMode":page["thread"]["historyMode"],"historyState":page["historyState"],"threadStatus":page["thread"]["status"],"turns":turns.len(),"items":items,"itemTypes":types,"turnStatuses":statuses,"newestTurn":newest.map(|t|&t["id"]),"newestStartedAt":newest.map(|t|&t["startedAt"]),"newestItems":newest.and_then(|t|t["items"].as_array()).map(Vec::len),"hasOlder":page["nextCursor"].is_string(),"bytes":serde_json::to_vec(&page)?.len(),"elapsedMs":started.elapsed().as_millis()})
                    );
                    cursor = page["nextCursor"].as_str().map(str::to_owned);
                    if cursor.is_none() {
                        break;
                    }
                }
                Err(error) => {
                    println!(
                        "{}",
                        json!({"ok":false,"thread":thread,"error":format!("{error:#}"),"elapsedMs":started.elapsed().as_millis()})
                    );
                    std::process::exit(1);
                }
            }
        }
        return Ok(());
    }
    let (mut threads, mut turns, mut items, mut empty) = (0, 0, 0, 0);
    for archived in [false, true] {
        let mut p = json!({"limit":100,"source":"cli","archived":archived});
        loop {
            let page = pocket_agent::history_catalog::list(&home, &p)?;
            for row in page["data"].as_array().unwrap() {
                threads += 1;
                let id = row["id"].as_str().unwrap();
                let mut cursor = None;
                loop {
                    let read =
                        pocket_agent::history_catalog::read(&home, id, cursor.as_deref(), 100)?;
                    empty += usize::from(read["historyState"] == "empty");
                    for turn in read["data"].as_array().unwrap() {
                        turns += 1;
                        items += turn["items"].as_array().unwrap().len();
                    }
                    cursor = read["nextCursor"].as_str().map(str::to_owned);
                    if cursor.is_none() {
                        break;
                    }
                }
            }
            if page["nextCursor"].is_null() {
                break;
            }
            p["cursor"] = page["nextCursor"].clone();
        }
    }
    println!(
        "{}",
        json!({"threads":threads,"turns":turns,"items":items,"empty":empty})
    );
    Ok(())
}
