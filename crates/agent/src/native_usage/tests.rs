use super::{
    import,
    ledger::{self, Applied, Failure, Ledger, Outcome},
    query, *,
};
use crate::usage_stats::{Filters, Page, RequestCursor, RequestPage, UsageQuery};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    time::Duration,
};

pub(crate) const T0: i64 = 1_800_000_000;
pub(crate) fn stamp(at: i64) -> String {
    chrono::DateTime::from_timestamp(at, 0)
        .unwrap()
        .format("%Y-%m-%dT%H:%M:%S%.3fZ")
        .to_string()
}
pub(crate) fn window() -> UsageQuery {
    UsageQuery {
        start_at: T0,
        end_at: T0 + 86400,
        bucket_edges: (0..=24).map(|h| T0 + h * 3600).collect(),
        filters: Filters::default(),
        provider_page: Page::default(),
        model_page: Page::default(),
        session_page: Page::default(),
        request_page: RequestPage::default(),
    }
}
type Tokens = (u64, u64, u64);
fn camel((input, cached, output): Tokens) -> Value {
    json!({"inputTokens":input,"cachedInputTokens":cached,"outputTokens":output,"reasoningOutputTokens":0,"totalTokens":input+output})
}
fn snake((input, cached, output): Tokens) -> Value {
    json!({"input_tokens":input,"cached_input_tokens":cached,"cache_write_input_tokens":0,"output_tokens":output,"reasoning_output_tokens":0,"total_tokens":input+output})
}
pub(crate) fn event(thread: &str, turn: &str, total: Tokens, last: Tokens) -> Value {
    json!({"method":"thread/tokenUsage/updated","params":{"threadId":thread,"turnId":turn,"tokenUsage":{"total":camel(total),"last":camel(last),"modelContextWindow":1000}}})
}
fn live(thread: &str, turn: &str, total: Tokens, last: Tokens, at: i64) -> Observation {
    Observation::live(&event(thread, turn, total, last)["params"], at).unwrap()
}
// Synthetic rollout records in the shape of 0.167-0.171 files; the text fields
// stand in for content that must never reach the ledger.
pub(crate) fn meta(id: &str, extra: Value) -> Value {
    let mut payload = json!({"id":id,"session_id":id,"timestamp":stamp(T0),"cwd":"C:/Users/SECRET/project","model_provider":"custom","base_instructions":{"text":"SECRET INSTRUCTIONS"},"history_mode":"paginated"});
    for (key, value) in extra.as_object().into_iter().flatten() {
        payload[key] = value.clone();
    }
    json!({"timestamp":stamp(T0),"type":"session_meta","payload":payload})
}
pub(crate) fn turn(id: &str, model: &str) -> Value {
    json!({"timestamp":stamp(T0),"type":"turn_context","payload":{"turn_id":id,"cwd":"C:/Users/SECRET/project","model":model,"summary":"SECRET SUMMARY"}})
}
pub(crate) fn count(at: i64, total: Tokens, last: Tokens) -> Value {
    json!({"timestamp":stamp(at),"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":snake(total),"last_token_usage":snake(last),"model_context_window":1000},"rate_limits":null}})
}
fn message(text: &str) -> Value {
    json!({"timestamp":stamp(T0),"type":"response_item","payload":{"type":"message","content":[{"type":"output_text","text":text}]}})
}
fn text(lines: &[Value]) -> String {
    lines.iter().map(|l| format!("{l}\n")).collect()
}
pub(crate) fn write(home: &Path, name: &str, lines: &[Value]) -> PathBuf {
    let dir = home.join("sessions/2027/01/15");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(format!("rollout-2027-01-15T08-00-00-{name}.jsonl"));
    std::fs::write(&path, text(lines)).unwrap();
    path
}
struct Fixture {
    dir: tempfile::TempDir,
    ledger: Ledger,
    path: PathBuf,
    home: PathBuf,
}
impl Fixture {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let home = dir.path().join("codex");
        std::fs::create_dir_all(home.join("sessions")).unwrap();
        let path = dir.path().join("usage-ledger.sqlite");
        Self {
            ledger: Ledger::open(&path).unwrap(),
            dir,
            path,
            home,
        }
    }
    fn import(&self) -> import::Pass {
        self.import_within(u64::MAX)
    }
    fn import_within(&self, bytes: u64) -> import::Pass {
        let homes = [("store".to_owned(), self.home.clone())];
        import::pass(&self.ledger, &homes, bytes, Duration::from_secs(60)).unwrap()
    }
    fn read(&self, q: &UsageQuery) -> Value {
        query::dashboard(&ledger::reader(&self.path).unwrap(), q).unwrap()
    }
    /// (requests, total tokens) of the default window.
    fn totals(&self) -> (u64, u64) {
        let totals = self.read(&window())["data"]["totals"].clone();
        (
            totals["requests"].as_u64().unwrap(),
            totals["tokens"]["total"].as_u64().unwrap(),
        )
    }
    fn sessions(&self) -> BTreeMap<String, u64> {
        self.read(&window())["data"]["session_groups"]["items"]
            .as_array()
            .unwrap()
            .iter()
            .map(|g| {
                (
                    g["session_id"].as_str().unwrap().to_owned(),
                    g["totals"]["tokens"]["total"].as_u64().unwrap(),
                )
            })
            .collect()
    }
    /// Everything but request ids, which follow insertion order.
    fn summary(&self) -> Value {
        let mut data = self.read(&window())["data"].take();
        data["recent_requests"].take();
        data
    }
    fn overview(&self) -> Value {
        query::overview(&ledger::reader(&self.path).unwrap()).unwrap()
    }
    /// (thread, cum_input, state, segment, at, turn, model, source) ordered by insertion.
    #[allow(clippy::type_complexity)]
    fn rows(&self) -> Vec<(String, i64, i64, i64, i64, Option<String>, Option<String>, i64)> {
        let conn = ledger::reader(&self.path).unwrap();
        let mut stmt = conn.prepare("SELECT t.thread_id,e.cum_input,e.state,e.segment,e.at,e.turn_id,e.model,e.source FROM events e JOIN threads t ON t.id=e.thread ORDER BY e.id").unwrap();
        stmt.query_map([], |r| {
            Ok((
                r.get(0)?,
                r.get(1)?,
                r.get(2)?,
                r.get(3)?,
                r.get(4)?,
                r.get(5)?,
                r.get(6)?,
                r.get(7)?,
            ))
        })
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap()
    }
    fn one(&self, o: Observation) -> Applied {
        self.ledger
            .write(|tx, caps| ledger::observe(tx, &o, caps, &mut Outcome::default()))
            .unwrap()
    }
}

#[test]
fn live_replay_and_repeated_cumulative_values_count_once() {
    let f = Fixture::new();
    let first = live("t", "u1", (100, 0, 10), (100, 0, 10), T0 + 1);
    let second = live("t", "u1", (250, 100, 30), (150, 100, 20), T0 + 2);
    assert_eq!(f.one(first.clone()), Applied::Counted);
    assert_eq!(f.one(second.clone()), Applied::Counted);
    let before = f.read(&window());
    let tokens = &before["data"]["totals"]["tokens"];
    // Native input already contains the cached part; the cards show them apart.
    assert_eq!(tokens["fresh_input"], 150);
    assert_eq!(tokens["cache_read"], 100);
    assert_eq!(tokens["cache_write"], 0);
    assert_eq!(tokens["output"], 30);
    assert_eq!(tokens["total"], 280);
    assert_eq!(before["data"]["totals"]["requests"], 2);
    // The same cumulative value is re-sent on every status change.
    for _ in 0..5 {
        assert_eq!(f.one(second.clone()), Applied::Replay);
    }
    let outcome = f
        .ledger
        .apply(&[first.clone(), second.clone(), first, second])
        .unwrap();
    assert_eq!((outcome.replayed, outcome.counted, outcome.changed), (4, 0, false));
    // An older cumulative value that continues from nothing the thread holds is a stray.
    assert_eq!(
        f.one(live("t", "u9", (180, 0, 15), (50, 0, 3), T0 + 3)),
        Applied::Unanchored
    );
    assert_eq!(
        f.one(live("t", "u9", (0, 0, 0), (0, 0, 0), T0 + 3)),
        Applied::Ignored
    );
    let after = f.read(&window());
    assert_eq!(after["data"], before["data"]);
    assert_eq!(after["coverage"]["unanchored_rows"], 1);
}

#[test]
fn cumulative_reset_starts_a_new_segment_without_bogus_deltas() {
    let f = Fixture::new();
    f.one(live("t", "u1", (100, 0, 10), (100, 0, 10), T0 + 1));
    f.one(live("t", "u1", (250, 0, 30), (150, 0, 20), T0 + 2));
    assert_eq!(f.totals(), (2, 280));
    // The counter restarts from zero: only the new request is added.
    assert_eq!(
        f.one(live("t", "u2", (40, 0, 4), (40, 0, 4), T0 + 3)),
        Applied::Counted
    );
    assert_eq!(
        f.one(live("t", "u2", (90, 0, 9), (50, 0, 5), T0 + 4)),
        Applied::Counted
    );
    assert_eq!(f.totals(), (4, 280 + 44 + 55));
    // The old segment's last value shows up again: a replay, not a second reset.
    assert_eq!(
        f.one(live("t", "u1", (250, 0, 30), (150, 0, 20), T0 + 5)),
        Applied::Replay
    );
    // A revert continues from an earlier point of the thread that already has a successor.
    assert_eq!(
        f.one(live("t", "u3", (130, 0, 13), (30, 0, 3), T0 + 6)),
        Applied::Counted
    );
    assert_eq!(f.totals(), (5, 379 + 33));
    // Lower, new, and continuing from nothing known: neither a replay nor a reset.
    assert_eq!(
        f.one(live("t", "u4", (70, 0, 7), (5, 0, 1), T0 + 7)),
        Applied::Unanchored
    );
    assert_eq!(f.totals(), (5, 412));
    let segments: Vec<_> = f.rows().iter().map(|r| (r.1, r.3)).collect();
    assert_eq!(
        segments,
        [(100, 0), (250, 0), (40, 1), (90, 1), (130, 2), (70, 3)]
    );
    let conn = ledger::reader(&f.path).unwrap();
    let negative: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM events WHERE input<0 OR output<0 OR prev_input<0 OR prev_output<0",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(negative, 0);
}

fn session(id: &str) -> Vec<Value> {
    vec![
        meta(id, json!({})),
        message("SECRET PROMPT"),
        turn("u1", "m1"),
        count(T0 + 10, (100, 0, 10), (100, 0, 10)),
        count(T0 + 11, (100, 0, 10), (100, 0, 10)),
        turn("u2", "m1"),
        count(T0 + 20, (250, 100, 30), (150, 100, 20)),
    ]
}
#[test]
fn history_and_live_sightings_of_the_same_turns_count_once_in_either_order() {
    // Live first: the import adds nothing and supplies the original time and model.
    let f = Fixture::new();
    f.one(live("t1", "live", (100, 0, 10), (100, 0, 10), T0 + 500));
    f.one(live("t1", "live", (250, 100, 30), (150, 100, 20), T0 + 501));
    assert_eq!(f.totals(), (2, 280));
    write(&f.home, "t1", &session("t1"));
    f.import();
    assert_eq!(f.totals(), (2, 280));
    let expected = vec![
        ("t1".to_owned(), 100, 1, 0, T0 + 10, Some("u1".to_owned()), Some("m1".to_owned()), 1),
        ("t1".to_owned(), 250, 1, 0, T0 + 20, Some("u2".to_owned()), Some("m1".to_owned()), 1),
    ];
    assert_eq!(f.rows(), expected);
    // History first: later live replays change nothing at all.
    let g = Fixture::new();
    write(&g.home, "t1", &session("t1"));
    g.import();
    assert_eq!(g.totals(), (2, 280));
    assert_eq!(
        g.one(live("t1", "live", (100, 0, 10), (100, 0, 10), T0 + 500)),
        Applied::Replay
    );
    assert_eq!(
        g.one(live("t1", "live", (250, 100, 30), (150, 100, 20), T0 + 501)),
        Applied::Replay
    );
    assert_eq!(g.rows(), expected);
    assert_eq!(g.read(&window())["data"], f.read(&window())["data"]);
    // A live sample that skipped a request waits for history instead of guessing.
    let h = Fixture::new();
    assert_eq!(
        h.one(live("t1", "live", (250, 100, 30), (150, 100, 20), T0 + 501)),
        Applied::Unanchored
    );
    assert_eq!(h.totals(), (0, 0));
    write(&h.home, "t1", &session("t1"));
    h.import();
    assert_eq!(h.rows().len(), 2);
    assert_eq!(h.summary(), f.summary());
}

#[test]
fn a_point_first_seen_without_a_delta_takes_the_real_request_later() {
    let f = Fixture::new();
    // The agent meets a compacted thread mid-life: the counter is known, the request is not.
    assert_eq!(
        f.one(live("t1", "live", (250, 100, 30), (0, 0, 0), T0 + 500)),
        Applied::Unanchored
    );
    assert_eq!(
        f.one(live("t1", "live", (300, 100, 40), (50, 0, 10), T0 + 501)),
        Applied::Counted
    );
    assert_eq!(f.totals(), (1, 60));
    write(&f.home, "t1", &session("t1"));
    f.import();
    assert_eq!(f.totals(), (3, 340));
    let base = f.rows().into_iter().find(|r| r.1 == 250).unwrap();
    assert_eq!((base.2, base.4, base.5.as_deref(), base.7), (1, T0 + 20, Some("u2"), 1));
    // The estimate form of the same point stays a replay afterwards.
    assert_eq!(
        f.one(live("t1", "live", (250, 100, 30), (0, 0, 0), T0 + 600)),
        Applied::Replay
    );
    assert_eq!(f.totals(), (3, 340));
}

#[test]
fn reimport_restart_truncation_and_replacement_never_double_count() {
    let mut f = Fixture::new();
    let path = write(&f.home, "t1", &session("t1"));
    let size = std::fs::metadata(&path).unwrap().len();
    assert_eq!(f.import().read_bytes, size);
    assert_eq!(f.totals(), (2, 280));
    // Unchanged files are not opened again.
    let again = f.import();
    assert_eq!((again.read_bytes, again.files, again.pending_files), (0, 1, 0));
    // Appended bytes are read from the remembered offset; a partial line waits.
    let mut lines = session("t1");
    lines.push(count(T0 + 30, (300, 100, 40), (50, 0, 10)));
    let appended = text(&lines);
    let partial = appended.len() - 20;
    std::fs::write(&path, &appended.as_bytes()[..partial]).unwrap();
    assert_eq!(f.import().read_bytes, partial as u64 - size);
    assert_eq!(f.totals(), (2, 280));
    std::fs::write(&path, &appended).unwrap();
    assert_eq!(f.import().read_bytes, appended.len() as u64 - size);
    assert_eq!(f.totals(), (3, 340));
    // Agent restart: progress and counted points are durable.
    let reopened = Ledger::open(&f.path).unwrap();
    f.ledger = reopened;
    assert_eq!(f.import().read_bytes, 0);
    assert_eq!(f.totals(), (3, 340));
    // Lost progress re-reads from offset zero and still adds nothing.
    rusqlite::Connection::open(&f.path)
        .unwrap()
        .execute("DELETE FROM files", [])
        .unwrap();
    assert_eq!(f.import().read_bytes, appended.len() as u64);
    assert_eq!(f.totals(), (3, 340));
    // Truncation: restart at zero; what was counted stays counted.
    std::fs::write(&path, text(&session("t1"))).unwrap();
    assert_eq!(f.import().read_bytes, size);
    assert_eq!(f.totals(), (3, 340));
    assert_eq!(f.overview()["rewound_files"], 1);
    // Replacement under the same name by a longer, different session.
    let mut other = vec![meta("t2", json!({})), turn("x1", "m2")];
    other.extend((1..=12).map(|i| count(T0 + 40 + i, (10 * i as u64, 0, i as u64), (10, 0, 1))));
    std::fs::write(&path, text(&other)).unwrap();
    assert_eq!(f.import().read_bytes, text(&other).len() as u64);
    assert_eq!(f.totals(), (15, 340 + 132));
    assert_eq!(f.overview()["rewound_files"], 2);
    assert_eq!(f.sessions(), BTreeMap::from([("t1".into(), 340), ("t2".into(), 132)]));
    assert_eq!(f.import().read_bytes, 0);
}

#[test]
fn import_is_incremental_within_a_byte_budget() {
    let f = Fixture::new();
    let mut lines = vec![meta("big", json!({})), turn("u", "m")];
    for i in 1..=400u64 {
        lines.push(message(&"SECRET FILLER ".repeat(8)));
        lines.push(count(T0 + i as i64, (10 * i, 0, i), (10, 0, 1)));
    }
    let path = write(&f.home, "big", &lines);
    write(&f.home, "t1", &session("t1"));
    let size = std::fs::metadata(&path).unwrap().len();
    let mut passes = 0;
    loop {
        let pass = f.import_within(16 * 1024);
        passes += 1;
        assert!(pass.read_bytes <= 16 * 1024);
        if pass.pending_files == 0 {
            break;
        }
        assert!(pass.pending_bytes > 0 && pass.pending_bytes < size + 4096);
        assert!(passes < 100);
    }
    assert!(passes as u64 >= size / (16 * 1024));
    assert_eq!(f.totals(), (402, 4400 + 280));
}

fn lineage() -> (Vec<Value>, Vec<Value>, Vec<Value>) {
    let parent = vec![
        meta("t5", json!({})),
        turn("p1", "m1"),
        count(T0 + 10, (100, 0, 10), (100, 0, 10)),
        count(T0 + 20, (250, 0, 30), (150, 0, 20)),
        turn("p2", "m1"),
        count(T0 + 30, (400, 0, 50), (150, 0, 20)),
    ];
    // Real resumed/reverted rollouts keep the thread id, point at their parent with
    // history_base, copy nothing, and begin with either the base point again...
    let base = json!({"history_base":{"thread_id":"00000000-0000-7000-8000-000000000001","end_ordinal_exclusive":7,"end_byte_offset":900}});
    let reverted = vec![
        meta("t5", base.clone()),
        turn("b1", "m1"),
        count(T0 + 100, (250, 0, 30), (150, 0, 20)),
        count(T0 + 110, (330, 0, 40), (80, 0, 10)),
    ];
    // ...or directly with the first new request on top of it.
    let resumed = vec![
        meta("t5", base),
        turn("c1", "m1"),
        count(T0 + 200, (430, 0, 52), (100, 0, 12)),
    ];
    (parent, reverted, resumed)
}
#[test]
fn resumed_and_reverted_segments_inherit_their_base_without_recounting_it() {
    let (parent, reverted, resumed) = lineage();
    let all = 110 + 170 + 170 + 90 + 112;
    let f = Fixture::new();
    write(&f.home, "t5", &parent);
    write(&f.home, "t5_b", &reverted);
    write(&f.home, "t5_c", &resumed);
    f.import();
    assert_eq!(f.totals(), (5, all));
    assert_eq!(f.sessions(), BTreeMap::from([("t5".into(), all)]));
    // Children before their parent: the inherited base is an uncounted anchor.
    let g = Fixture::new();
    write(&g.home, "t5_b", &reverted);
    write(&g.home, "t5_c", &resumed);
    g.import();
    assert_eq!(g.totals(), (2, 90 + 112));
    assert_eq!(g.read(&window())["coverage"]["unanchored_rows"], 1);
    write(&g.home, "t5", &parent);
    g.import();
    assert_eq!(g.totals(), (5, all));
    assert_eq!(g.summary(), f.summary());
    // The base request keeps its original time and turn, not the replay's.
    let base = g.rows().into_iter().find(|r| r.1 == 250).unwrap();
    assert_eq!((base.2, base.4, base.5.as_deref()), (1, T0 + 20, Some("p1")));
    // Re-importing everything from scratch on top changes nothing.
    rusqlite::Connection::open(&g.path)
        .unwrap()
        .execute("DELETE FROM files", [])
        .unwrap();
    g.import();
    assert_eq!(g.totals(), (5, all));
}

#[test]
fn fork_children_never_recount_usage_inherited_from_their_parent() {
    let parent = vec![
        meta("t6", json!({})),
        turn("p1", "parent-model"),
        count(T0 + 10, (100, 0, 10), (100, 0, 10)),
        count(T0 + 20, (250, 0, 30), (150, 0, 20)),
    ];
    let fork = json!({"forked_from_id":"t6"});
    // Inherited cumulative, base point re-emitted under the child's id.
    let replayed = vec![
        meta("f1", fork.clone()),
        turn("a", "m"),
        count(T0 + 300, (250, 0, 30), (150, 0, 20)),
        count(T0 + 310, (300, 0, 35), (50, 0, 5)),
    ];
    // Inherited cumulative, first record already a new request: it cannot be told
    // from a replay without the parent, so it is the uncounted baseline.
    let continued = vec![
        meta("f2", fork.clone()),
        turn("a", "m"),
        count(T0 + 320, (300, 0, 35), (50, 0, 5)),
        count(T0 + 330, (350, 0, 40), (50, 0, 5)),
    ];
    // A child whose file repeats its parent's token records verbatim.
    let copied = vec![
        meta("f3", fork.clone()),
        turn("a", "m"),
        count(T0 + 400, (100, 0, 10), (100, 0, 10)),
        count(T0 + 400, (250, 0, 30), (150, 0, 20)),
        count(T0 + 410, (290, 0, 34), (40, 0, 4)),
    ];
    // Observed shape of a forked subagent: the parent's header and context are
    // copied, its token records are not, and the child's counter starts at zero.
    let subagent = vec![
        meta("sub", json!({"forked_from_id":"t6","parent_thread_id":"t6"})),
        meta("t6", json!({})),
        turn("p1", "parent-model"),
        turn("s1", "sub-model"),
        count(T0 + 500, (80, 0, 8), (80, 0, 8)),
        count(T0 + 510, (200, 0, 20), (120, 0, 12)),
    ];
    let expected = BTreeMap::from([
        ("t6".to_owned(), 280),
        ("f1".to_owned(), 55),
        ("f2".to_owned(), 55),
        ("f3".to_owned(), 44),
        ("sub".to_owned(), 220),
    ]);
    let children = |home: &Path| {
        write(home, "f1", &replayed);
        write(home, "f2", &continued);
        write(home, "f3", &copied);
        write(home, "sub", &subagent);
    };
    // Parent known first.
    let f = Fixture::new();
    write(&f.home, "t6", &parent);
    f.import();
    children(&f.home);
    f.import();
    assert_eq!(f.sessions(), expected);
    assert_eq!(f.totals(), (7, 654));
    // Children first, then the parent: the same final state.
    let g = Fixture::new();
    children(&g.home);
    g.import();
    assert_eq!(g.sessions()["f1"], 55);
    assert_eq!(g.sessions()["f2"], 55);
    assert_eq!(g.sessions()["sub"], 220);
    write(&g.home, "t6", &parent);
    g.import();
    assert_eq!(g.sessions(), expected);
    assert_eq!(g.summary(), f.summary());
    // The subagent's usage is attributed to the subagent and its own model.
    let models = g.read(&window())["data"]["model_groups"]["items"].clone();
    let sub = models
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["model"] == "sub-model")
        .unwrap();
    assert_eq!(sub["totals"]["tokens"]["total"], 220);
    // A live event for the child that repeats the parent's point is inherited too.
    assert_eq!(
        g.one(live("f1", "x", (100, 0, 10), (100, 0, 10), T0 + 900)),
        Applied::Inherited
    );
    assert_eq!(g.sessions(), expected);
}

fn point(thread: &str, cum: (u64, u64), last: (u64, u64), at: i64, model: &str, provider: &str) -> Observation {
    let usage = |(input, output): (u64, u64)| Usage {
        input,
        output,
        ..Usage::default()
    };
    Observation {
        thread: thread.into(),
        turn: Some("turn".into()),
        cum: usage(cum),
        last: usage(last),
        at,
        model: Some(model.into()),
        provider: Some(provider.into()),
        parent: None,
        history: true,
    }
}
fn populated() -> Fixture {
    let f = Fixture::new();
    f.ledger
        .apply(&[
            point("a", (10, 1), (10, 1), T0, "m1", "p1"),
            point("a", (30, 3), (20, 2), T0 + 3600, "m1", "p1"),
            point("b", (100, 10), (100, 10), T0 + 7205, "m2", "p2"),
            point("b", (150, 15), (50, 5), T0 + 86400, "m2", "p2"),
            point("c", (5, 1), (5, 1), T0 - 1, "m1", "p1"),
            point("c", (9, 2), (4, 1), T0 + 10, "m1", "p1"),
        ])
        .unwrap();
    f
}
#[test]
fn window_buckets_filters_and_paging_follow_the_dashboard_contract() {
    let f = populated();
    let all = f.read(&window());
    assert_eq!(all["availability"], "ready");
    // Half-open window: the start second is in, the end second is out.
    assert_eq!(all["data"]["totals"]["requests"], 4);
    assert_eq!(all["data"]["totals"]["tokens"]["total"], 11 + 22 + 110 + 5);
    assert_eq!(all["coverage"]["matched_rows"], 4);
    assert_eq!(all["coverage"]["first_observed_at"], T0);
    assert_eq!(all["coverage"]["last_observed_at"], T0 + 7205);
    assert_eq!(all["coverage"]["sessions"], 3);
    let trend = all["data"]["trend"].as_array().unwrap();
    assert_eq!(trend.len(), 24);
    let bucket = |i: usize| {
        (
            trend[i]["totals"]["requests"].as_u64().unwrap(),
            trend[i]["totals"]["tokens"]["total"].as_u64().unwrap(),
        )
    };
    assert_eq!((bucket(0), bucket(1), bucket(2), bucket(3)), ((2, 16), (1, 22), (1, 110), (0, 0)));
    assert_eq!((trend[1]["start_at"].clone(), trend[1]["end_at"].clone()), (json!(T0 + 3600), json!(T0 + 7200)));
    let ids = |v: &Value, key: &str| -> Vec<String> {
        v["items"]
            .as_array()
            .unwrap()
            .iter()
            .map(|g| g[key].as_str().unwrap().to_owned())
            .collect()
    };
    assert_eq!(ids(&all["data"]["provider_groups"], "provider_id"), ["p1", "p2"]);
    assert_eq!(ids(&all["data"]["model_groups"], "model"), ["m1", "m2"]);
    assert_eq!(ids(&all["data"]["session_groups"], "session_id"), ["a", "b", "c"]);
    assert_eq!(all["data"]["provider_groups"]["items"][0]["app_type"], "codex");
    assert!(all["data"]["provider_groups"]["items"][0]["name"].is_null());
    assert!(all["data"]["session_groups"]["items"][0]["title"].is_null());
    let filtered = |edit: &dyn Fn(&mut UsageQuery)| {
        let mut q = window();
        edit(&mut q);
        let v = f.read(&q);
        (
            v["data"]["totals"]["requests"].as_u64().unwrap(),
            v["data"]["totals"]["tokens"]["total"].as_u64().unwrap(),
            v["data"]["recent_requests"]["items"].as_array().unwrap().len(),
        )
    };
    assert_eq!(filtered(&|q| q.filters.model = Some("m1".into())), (3, 38, 3));
    assert_eq!(filtered(&|q| q.filters.session_id = Some("b".into())), (1, 110, 1));
    assert_eq!(filtered(&|q| q.filters.provider_id = Some("p2".into())), (1, 110, 1));
    assert_eq!(filtered(&|q| q.filters.model = Some("absent".into())), (0, 0, 0));
    assert_eq!(
        filtered(&|q| {
            q.start_at = T0 + 3600;
            q.bucket_edges = vec![T0 + 3600, T0 + 7200, T0 + 86400, T0 + 86401];
            q.end_at = T0 + 86401;
        }),
        (3, 22 + 110 + 55, 3)
    );
    let mut q = window();
    q.session_page = Page { offset: 1, limit: 1 };
    let paged = f.read(&q);
    let sessions = &paged["data"]["session_groups"];
    assert_eq!(ids(sessions, "session_id"), ["b"]);
    assert_eq!((sessions["offset"].clone(), sessions["limit"].clone(), sessions["total_groups"].clone(), sessions["has_more"].clone()), (json!(1), json!(1), json!(3), json!(true)));
    // Request pages: newest first, stable cursor, no duplicates.
    let mut q = window();
    q.request_page.limit = 3;
    let conn = ledger::reader(&f.path).unwrap();
    let first = query::requests(&conn, &q).unwrap()["data"]["requests"].clone();
    assert_eq!(ids(&first, "session_id"), ["b", "a", "c"]);
    assert_eq!(first["has_more"], true);
    assert_eq!(first["next_cursor"]["created_at"], T0 + 10);
    assert_eq!(
        first["items"].as_array().unwrap()[..],
        all["data"]["recent_requests"]["items"].as_array().unwrap()[..3]
    );
    q.request_page.cursor = Some(RequestCursor {
        created_at: T0 + 10,
        request_id: first["next_cursor"]["request_id"].as_str().unwrap().into(),
    });
    let second = query::requests(&conn, &q).unwrap()["data"]["requests"].clone();
    assert_eq!(ids(&second, "session_id"), ["a"]);
    assert_eq!(second["items"][0]["created_at"], T0);
    assert_eq!((second["has_more"].clone(), second["next_cursor"].clone()), (json!(false), Value::Null));
}

#[test]
fn native_results_never_invent_cost_status_or_timing() {
    let f = populated();
    let v = f.read(&window());
    assert_eq!(
        v["capabilities"],
        json!({"cost":false,"performance":false,"status":false,"providers":true,"models":true,"sessions":true,"requests":true})
    );
    assert!(v["errors"].as_array().unwrap().iter().any(|e| e["code"] == "cost_not_provided"));
    assert_eq!(v["source"], json!({"kind":"native_codex","table":"native_usage_events","app_type":"codex","data_source":null,"schema_version":1}));
    assert_eq!(v["coverage"]["zero_cost_interpretation"], "cost_not_provided");
    assert_eq!(v["coverage"]["cost_known_rows"], 0);
    assert_eq!(v["coverage"]["cost_missing_rows"], 4);
    let mut totals = vec![v["data"]["totals"].clone()];
    for key in ["provider_groups", "model_groups", "session_groups"] {
        totals.extend(v["data"][key]["items"].as_array().unwrap().iter().map(|g| g["totals"].clone()));
    }
    totals.extend(v["data"]["trend"].as_array().unwrap().iter().map(|b| b["totals"].clone()));
    assert_eq!(totals.len(), 1 + 2 + 2 + 3 + 24);
    for t in totals {
        for key in ["costs", "performance", "success", "errors"] {
            assert!(t.as_object().unwrap().contains_key(key) && t[key].is_null(), "{key}");
        }
    }
    for r in v["data"]["recent_requests"]["items"].as_array().unwrap() {
        for key in ["status_code", "cost_usd", "ttft_ms", "latency_ms", "tokens_per_second", "provider_name", "session_title"] {
            assert!(r.as_object().unwrap().contains_key(key) && r[key].is_null(), "{key}");
        }
        assert_eq!(r["observed_by"], "history");
        assert_eq!(r["turn_id"], "turn");
    }
    // No amount, known or zero, appears anywhere in a native result.
    let text = v.to_string();
    assert!(!text.contains("usd\":\"") && !text.contains("known_usd") && !text.contains("average_ms"));
}

fn raw(at: &str, total: Value, last: Value) -> Value {
    json!({"timestamp":at,"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":total,"last_token_usage":last}}})
}
#[test]
fn malformed_oversized_and_absurd_records_are_skipped_and_counted() {
    for bad in [
        json!({"inputTokens":-1,"outputTokens":1}),
        json!({"inputTokens":9223372036854775808u64,"outputTokens":1}),
        json!({"inputTokens":1e30,"outputTokens":1}),
        json!({"inputTokens":"7","outputTokens":1}),
        json!({"inputTokens":1.5,"outputTokens":1}),
        json!({"outputTokens":1}),
        json!({"inputTokens":7,"outputTokens":1,"cachedInputTokens":null}),
        json!({"inputTokens":7,"outputTokens":1,"totalTokens":-8}),
        json!({"inputTokens":i64::MAX,"outputTokens":1}),
        json!(null),
    ] {
        let params = json!({"threadId":"t","tokenUsage":{"total":bad,"last":{"inputTokens":0,"outputTokens":0}}});
        assert!(Observation::live(&params, T0).is_none(), "{params}");
    }
    let good = json!({"inputTokens":7,"outputTokens":1});
    for (thread, last, at) in [
        ("../../x", good.clone(), T0),
        ("", good.clone(), T0),
        (&*"x".repeat(129), good.clone(), T0),
        ("t", json!({"inputTokens":8,"outputTokens":1}), T0),
        ("t", good.clone(), 0),
        ("t", good.clone(), i64::MAX),
    ] {
        let params = json!({"threadId":thread,"tokenUsage":{"total":good,"last":last}});
        assert!(Observation::live(&params, at).is_none(), "{params}");
    }
    assert!(Observation::live(&json!({"threadId":"t","turnId":"bad turn\n","tokenUsage":{"total":good,"last":good}}), T0).unwrap().turn.is_none());

    let f = Fixture::new();
    let ok = snake((100, 0, 10));
    let mut lines = text(&[
        meta("t", json!({})),
        turn("u", "m"),
        raw(&stamp(T0 + 1), json!({"input_tokens":-5,"output_tokens":1}), ok.clone()),
        raw(&stamp(T0 + 1), json!({"input_tokens":1e30,"output_tokens":1}), ok.clone()),
        raw(&stamp(T0 + 1), json!({"input_tokens":u64::MAX,"output_tokens":1}), ok.clone()),
        raw("2027-02-30T00:00:00.000Z", ok.clone(), ok.clone()),
        raw("yesterday", ok.clone(), ok.clone()),
        raw(&stamp(T0 + 1), snake((50, 0, 5)), ok.clone()),
        json!({"timestamp":stamp(T0),"type":"event_msg","payload":{"type":"token_count","info":null}}),
        message("this response_item only mentions token_count and turn_context"),
    ]);
    lines.push_str("{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{\n");
    lines.push_str(&format!("{}\n", message(&"token_count SECRET ".repeat(70_000))));
    lines.push_str(&text(&[raw(&stamp(T0 + 2), ok.clone(), ok.clone())]));
    // Cache larger than input: a request whose tokens cannot be split.
    lines.push_str(&text(&[count(T0 + 3, (200, 0, 20), (100, 500, 10))]));
    let dir = f.home.join("sessions/2027/01/15");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("rollout-good.jsonl"), &lines).unwrap();
    std::fs::write(dir.join("rollout-not-json.jsonl"), "SECRET not json\n").unwrap();
    std::fs::write(dir.join("rollout-no-header.jsonl"), text(&[turn("u", "m"), count(T0 + 5, (9, 0, 9), (9, 0, 9))])).unwrap();
    std::fs::write(dir.join("rollout-bad-id.jsonl"), text(&[meta("../../x", json!({})), count(T0 + 5, (9, 0, 9), (9, 0, 9))])).unwrap();
    std::fs::write(dir.join("rollout-huge-header.jsonl"), text(&[meta("h", json!({"base_instructions":"x".repeat(MAX_HEADER)})), count(T0 + 5, (9, 0, 9), (9, 0, 9))])).unwrap();
    std::fs::write(dir.join("notes.jsonl"), text(&session("ignored-name"))).unwrap();
    let pass = f.import();
    assert_eq!((pass.files, pass.skipped_files, pass.pending_files), (5, 4, 0));
    let v = f.read(&window());
    assert_eq!(v["data"]["totals"]["requests"], 2);
    assert_eq!(v["data"]["totals"]["tokens"]["total"], 110);
    assert_eq!(v["data"]["totals"]["tokens"]["invalid_rows"], 1);
    assert_eq!(v["coverage"]["token_invalid_rows"], 1);
    assert!(v["errors"].as_array().unwrap().iter().any(|e| e["code"] == "invalid_tokens"));
    assert!(v["data"]["recent_requests"]["items"].as_array().unwrap().iter().any(|r| r["tokens"].is_null()));
    assert_eq!(f.overview()["invalid_records"], 7);
    // Rejected files are not retried until they change.
    let again = f.import();
    assert_eq!((again.read_bytes, again.skipped_files), (0, 4));
    // Numbers, identifiers and digests only: no text or project path was stored.
    drop(f.ledger);
    for entry in std::fs::read_dir(f.dir.path()).unwrap().flatten() {
        if entry.path().is_file() {
            let bytes = std::fs::read(entry.path()).unwrap();
            for needle in ["SECRET", "project", "Users", "rollout-"] {
                assert!(!bytes.windows(needle.len()).any(|w| w == needle.as_bytes()), "{needle}");
            }
        }
    }
}
const MAX_HEADER: usize = 1024 * 1024 + 16;

#[test]
fn ledger_capacity_rejects_new_sessions_and_points_without_failing() {
    let dir = tempfile::tempdir().unwrap();
    let ledger = Ledger::open_with(&dir.path().join("l.sqlite"), (2, 4)).unwrap();
    let outcome = ledger
        .apply(&[
            live("a", "u", (10, 0, 1), (10, 0, 1), T0),
            live("b", "u", (10, 0, 1), (10, 0, 1), T0),
            live("c", "u", (10, 0, 1), (10, 0, 1), T0),
            live("a", "u", (20, 0, 2), (10, 0, 1), T0),
            live("a", "u", (30, 0, 3), (10, 0, 1), T0),
            live("a", "u", (40, 0, 4), (10, 0, 1), T0),
            live("a", "u", (10, 0, 1), (10, 0, 1), T0),
        ])
        .unwrap();
    assert_eq!((outcome.counted, outcome.rejected, outcome.replayed), (4, 2, 1));
    let conn = ledger::reader(&dir.path().join("l.sqlite")).unwrap();
    assert_eq!(query::overview(&conn).unwrap()["rejected_records"], 2);
    let v = query::dashboard(&conn, &window()).unwrap();
    assert_eq!(v["data"]["totals"]["requests"], 4);
}

#[test]
fn newer_foreign_or_corrupt_ledger_is_reported_and_left_untouched() {
    let dir = tempfile::tempdir().unwrap();
    let newer = dir.path().join("newer.sqlite");
    rusqlite::Connection::open(&newer)
        .unwrap()
        .execute_batch("CREATE TABLE events(future TEXT); INSERT INTO events VALUES('kept'); PRAGMA user_version=2;")
        .unwrap();
    let foreign = dir.path().join("foreign.sqlite");
    rusqlite::Connection::open(&foreign)
        .unwrap()
        .execute_batch("CREATE TABLE other(x); INSERT INTO other VALUES(1);")
        .unwrap();
    let corrupt = dir.path().join("corrupt.sqlite");
    std::fs::write(&corrupt, vec![7u8; 8192]).unwrap();
    for (path, expected) in [
        (&newer, Failure::Schema),
        (&foreign, Failure::Schema),
        (&corrupt, Failure::Corrupt),
    ] {
        let before = std::fs::read(path).unwrap();
        assert_eq!(Ledger::open(path).err(), Some(expected));
        assert_eq!(ledger::reader(path).err(), Some(expected));
        assert_eq!(std::fs::read(path).unwrap(), before);
    }
    assert_eq!(Failure::Schema.details().0, "unsupported_schema");
    assert_eq!(ledger::reader(&dir.path().join("directory-not-file")).err(), Some(Failure::Unavailable));
    std::fs::create_dir(dir.path().join("directory")).unwrap();
    assert_eq!(Ledger::open(&dir.path().join("directory")).err(), Some(Failure::Path));
}

#[test]
fn links_inside_the_store_are_never_followed() {
    let f = Fixture::new();
    let outside = f.dir.path().join("outside");
    std::fs::create_dir_all(&outside).unwrap();
    std::fs::write(outside.join("rollout-linked.jsonl"), text(&session("linked"))).unwrap();
    let day = f.home.join("sessions/2027/01");
    std::fs::create_dir_all(&day).unwrap();
    #[cfg(windows)]
    let made = std::os::windows::fs::symlink_dir(&outside, day.join("16")).is_ok()
        && std::os::windows::fs::symlink_file(outside.join("rollout-linked.jsonl"), day.join("rollout-file.jsonl")).is_ok();
    #[cfg(unix)]
    let made = std::os::unix::fs::symlink(&outside, day.join("16")).is_ok()
        && std::os::unix::fs::symlink(outside.join("rollout-linked.jsonl"), day.join("rollout-file.jsonl")).is_ok();
    if !made {
        // Creating links needs a privilege this account may lack.
        return;
    }
    write(&f.home, "t1", &session("t1"));
    let pass = f.import();
    assert_eq!(pass.files, 1);
    assert_eq!(f.sessions(), BTreeMap::from([("t1".into(), 280)]));
}
#[path = "service_tests.rs"]
mod service_tests;
