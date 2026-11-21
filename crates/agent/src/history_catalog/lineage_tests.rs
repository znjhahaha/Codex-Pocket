use super::*;
use std::path::PathBuf;

const THREAD: &str = "00000000-0000-0000-0000-000000000001";
const CHILD: &str = "00000000-0000-0000-0000-000000000002";

struct Fixture {
    home: tempfile::TempDir,
    history: Connection,
    current: PathBuf,
}

impl Fixture {
    fn new() -> Self {
        let home = super::tests::fixture();
        let sessions = home.path().join("sessions/2026/11/19");
        std::fs::create_dir_all(&sessions).unwrap();
        let original = sessions.join(format!("rollout-2026-11-19T00-00-00-{THREAD}.jsonl"));
        let current = sessions.join(format!(
            "rollout-2026-11-19T00-01-00-{THREAD}_{CHILD}.jsonl"
        ));
        std::fs::write(
            &original,
            json!({"type":"session_meta","payload":{"id":THREAD,"history_mode":"paginated"}})
                .to_string()
                + "\n",
        )
        .unwrap();
        std::fs::write(&current, json!({"type":"session_meta","payload":{"id":THREAD,"history_mode":"paginated","history_base":{"thread_id":THREAD,"end_ordinal_exclusive":10,"end_byte_offset":100}}}).to_string()+"\n").unwrap();
        let state = Connection::open(home.path().join("state_5.sqlite")).unwrap();
        state.execute("INSERT INTO threads VALUES(?1,'vscode','custom','Project chat','preview','D:/project',100,0,?2,'paginated','hello')", params![THREAD,current.to_str().unwrap()]).unwrap();
        let history = Connection::open(home.path().join("thread_history_1.sqlite")).unwrap();
        let f = Self {
            home,
            history,
            current,
        };
        f.turn(THREAD, "old", 1, "completed", "old answer");
        f.turn(THREAD, "reverted", 10, "failed", "reverted answer");
        f.turn(CHILD, "new", 11, "inProgress", "current answer");
        f
    }
    fn turn(&self, rollout: &str, turn: &str, ordinal: i64, status: &str, text: &str) {
        self.history
            .execute(
                "INSERT INTO thread_turns VALUES(?1,?2,?3,?4,NULL,1,NULL,NULL)",
                params![rollout, turn, ordinal, status],
            )
            .unwrap();
        self.history
            .execute(
                "INSERT INTO thread_items VALUES(?1,?2,?3,?4,?5)",
                params![
                    rollout,
                    turn,
                    format!("{turn}-item"),
                    ordinal,
                    json!({"id":format!("{turn}-item"),"type":"agentMessage","text":text})
                        .to_string()
                ],
            )
            .unwrap();
    }
}

#[test]
fn current_rollout_id_and_bounded_parent_history_replace_the_old_thread_projection() {
    let f = Fixture::new();
    let before = std::fs::read(f.home.path().join("thread_history_1.sqlite")).unwrap();
    let first = read(f.home.path(), THREAD, None, 1).unwrap();
    assert_eq!(first["data"][0]["id"], "new");
    assert_eq!(first["data"][0]["status"], "inProgress");
    assert_eq!(first["data"][0]["items"][0]["text"], "current answer");
    assert_eq!(first["thread"]["id"], THREAD);
    let older = read(f.home.path(), THREAD, first["nextCursor"].as_str(), 1).unwrap();
    assert_eq!(older["data"][0]["id"], "old");
    assert!(older["nextCursor"].is_null());
    assert_eq!(
        std::fs::read(f.home.path().join("thread_history_1.sqlite")).unwrap(),
        before
    );
}

#[test]
fn refreshing_the_current_rollout_reads_appended_items_without_resume() {
    let f = Fixture::new();
    let first = read(f.home.path(), THREAD, None, 1).unwrap();
    assert_eq!(first["data"][0]["id"], "new");
    f.history
        .execute(
            "INSERT INTO thread_items VALUES(?1,'new','next-item',12,?2)",
            params![
                CHILD,
                json!({"id":"next-item","type":"agentMessage","text":"new progress"}).to_string()
            ],
        )
        .unwrap();
    let latest = read(f.home.path(), THREAD, None, 1).unwrap();
    assert_eq!(latest["data"][0]["items"].as_array().unwrap().len(), 2);
    assert_eq!(latest["data"][0]["items"][1]["text"], "new progress");
}

#[test]
fn resumed_turn_uses_latest_status_and_keeps_only_inherited_items_before_the_cutoff() {
    let f = Fixture::new();
    f.turn(THREAD, "new", 5, "interrupted", "inherited question");
    f.history.execute("UPDATE thread_items SET item_id='inherited',item_json=?1 WHERE thread_id=?2 AND turn_id='new'",params![json!({"id":"inherited","type":"userMessage","content":[{"type":"text","text":"inherited question"}]}).to_string(),THREAD]).unwrap();
    f.history
        .execute(
            "INSERT INTO thread_items VALUES(?1,'new','reverted-item',10,?2)",
            params![
                THREAD,
                json!({"id":"reverted-item","type":"agentMessage","text":"discarded branch"})
                    .to_string()
            ],
        )
        .unwrap();
    let page = read(f.home.path(), THREAD, None, 10).unwrap();
    assert_eq!(page["data"].as_array().unwrap().len(), 2);
    assert_eq!(page["data"][0]["id"], "new");
    assert_eq!(page["data"][0]["status"], "inProgress");
    let items = page["data"][0]["items"].as_array().unwrap();
    assert_eq!(items.len(), 2);
    assert_eq!(items[0]["id"], "inherited");
    assert_eq!(items[1]["id"], "new-item");
    assert!(!page.to_string().contains("discarded branch"));
    let first = read(f.home.path(), THREAD, None, 1).unwrap();
    let older = read(f.home.path(), THREAD, first["nextCursor"].as_str(), 1).unwrap();
    assert_eq!(
        older["data"][0]["id"], "old",
        "the resumed parent's turn must not reappear on the next page"
    );
}

#[test]
fn archived_ancestors_remain_readable_after_the_selected_rollout_moves() {
    let f = Fixture::new();
    let archive = f.home.path().join("archived_sessions");
    std::fs::create_dir(&archive).unwrap();
    let original = f
        .current
        .parent()
        .unwrap()
        .join(format!("rollout-2026-11-19T00-00-00-{THREAD}.jsonl"));
    std::fs::rename(&original, archive.join(original.file_name().unwrap())).unwrap();
    let page = read(f.home.path(), THREAD, None, 10).unwrap();
    assert_eq!(page["data"].as_array().unwrap().len(), 2);
    assert_eq!(page["data"][1]["id"], "old");
}

#[test]
fn attached_thread_history_does_not_expose_inherited_parent_context() {
    let f = Fixture::new();
    std::fs::write(&f.current,json!({"type":"session_meta","payload":{"id":THREAD,"history_mode":"paginated","attached_thread":true,"forked_from_id":THREAD,"forked_from_ordinal_exclusive":10,"history_base":{"thread_id":THREAD,"end_ordinal_exclusive":10,"end_byte_offset":100}}}).to_string()+"\n").unwrap();
    let page = read(f.home.path(), THREAD, None, 10).unwrap();
    assert_eq!(page["data"].as_array().unwrap().len(), 1);
    assert_eq!(page["data"][0]["id"], "new");
}

#[test]
fn missing_selected_child_cannot_silently_return_the_original_rollout() {
    let f = Fixture::new();
    std::fs::remove_file(&f.current).unwrap();
    assert!(read(f.home.path(), THREAD, None, 1).is_err());
}

#[test]
fn persisted_thread_status_uses_the_latest_turn_without_inventing_runtime_readiness() {
    let f = Fixture::new();
    let first = read(f.home.path(), THREAD, None, 1).unwrap();
    assert_eq!(first["thread"]["status"]["type"], "active");
    assert_eq!(first["data"][0]["startedAt"], 1);
    assert!(first["data"][0]["completedAt"].is_null());
    let older = read(f.home.path(), THREAD, first["nextCursor"].as_str(), 1).unwrap();
    assert_eq!(
        older["thread"]["status"]["type"], "active",
        "paging into completed history must not hide the latest running turn"
    );
    for status in ["completed", "interrupted", "failed", "futureStatus"] {
        f.history
            .execute(
                "UPDATE thread_turns SET status=?1 WHERE thread_id=?2",
                params![status, CHILD],
            )
            .unwrap();
        let page = read(f.home.path(), THREAD, None, 1).unwrap();
        assert_eq!(page["data"][0]["status"], status);
        assert_eq!(
            page["thread"]["status"]["type"], "unknown",
            "a saved terminal turn does not establish current runtime state"
        );
    }
}

#[test]
fn a_selected_rollout_with_the_wrong_owner_cannot_borrow_another_threads_history() {
    let f = Fixture::new();
    std::fs::write(
        &f.current,
        json!({"type":"session_meta","payload":{"id":CHILD,"history_mode":"paginated"}})
            .to_string()
            + "\n",
    )
    .unwrap();
    assert!(read(f.home.path(), THREAD, None, 1).is_err());
}

#[test]
fn lineage_cycles_cannot_fall_back_to_old_logical_thread_rows() {
    let f = Fixture::new();
    std::fs::write(&f.current,json!({"type":"session_meta","payload":{"id":THREAD,"history_mode":"paginated","history_base":{"thread_id":CHILD,"end_ordinal_exclusive":10,"end_byte_offset":100}}}).to_string()+"\n").unwrap();
    assert!(read(f.home.path(), THREAD, None, 1).is_err());
}
