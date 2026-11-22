//! Live frames for the phone: the coordinator's words as they arrive, one line about what
//! each job is doing, and the moment a question opens or closes. Frames are lossy and carry
//! no authority. The snapshot stays the truth: a phone that sees a new epoch or a gap in
//! the sequence reads it again.
use super::*;
use tokio::sync::broadcast;

/// A job's line changes at most this often while its status stays the same.
const STAGE_INTERVAL_MS: i64 = 1000;
/// The coordinator's text is sent in batches no closer together than this.
const DELTA_INTERVAL_MS: i64 = 50;

/// What the stream needs to know about one operation, read from the service each pass.
pub(super) struct OperationView {
    pub id: String,
    pub coordinator: bool,
    /// The job's id for a dispatched job, otherwise the task's own.
    pub task_id: Option<String>,
    pub thread_id: Option<String>,
    pub status: &'static str,
    /// The native request the user has to answer: its original id and kind.
    pub question: Option<(String, &'static str)>,
}

#[derive(Clone, PartialEq)]
struct JobLine {
    status: &'static str,
    kind: &'static str,
    label: String,
    subagents: u32,
}

pub(super) struct Stream {
    sender: broadcast::Sender<Value>,
    epoch: String,
    sequence: u64,
    primed: bool,
    /// Coordinator text already sent, and when.
    sent_text: BTreeMap<String, (String, i64)>,
    latest_text: BTreeMap<String, String>,
    /// Coordinator turns seen running, so each one's end is announced exactly once.
    coordinators: BTreeSet<String>,
    sent_lines: BTreeMap<String, (JobLine, i64)>,
    latest_stages: BTreeMap<String, StageObservation>,
    attention: BTreeSet<(String, String)>,
}

impl Stream {
    pub fn new(sender: broadcast::Sender<Value>) -> Self {
        Self {
            sender,
            // A restarted worker counts from one again; the epoch tells the phone so.
            epoch: uuid::Uuid::new_v4().to_string(),
            sequence: 0,
            primed: false,
            sent_text: BTreeMap::new(),
            latest_text: BTreeMap::new(),
            coordinators: BTreeSet::new(),
            sent_lines: BTreeMap::new(),
            latest_stages: BTreeMap::new(),
            attention: BTreeSet::new(),
        }
    }
    /// The coordinator's accumulated text. A job's own text is never passed here.
    pub fn text(&mut self, operation: &str, text: String) {
        self.latest_text.insert(operation.into(), text);
    }
    pub fn stage(&mut self, stage: StageObservation) {
        self.latest_stages.insert(stage.operation_id.clone(), stage);
    }
    /// An operation whose last frame may still need a successor, even if it has just ended.
    pub fn tracks(&self, operation: &str) -> bool {
        self.latest_text.contains_key(operation)
            || self.coordinators.contains(operation)
            || self.sent_lines.contains_key(operation)
            || self.attention.iter().any(|(op, _)| op == operation)
    }
    /// True while text is arriving that the phone has not been sent yet.
    pub fn text_pending(&self) -> bool {
        self.latest_text
            .iter()
            .any(|(op, text)| self.sent_text.get(op).is_none_or(|(sent, _)| sent != text))
    }

    /// Compare what was last sent with what is true now and send the difference.
    pub fn flush(&mut self, runtime: &str, generation: &str, operations: &[OperationView], now: i64) -> Vec<Value> {
        let mut frames = vec![];
        let find = |id: &str| operations.iter().find(|o| o.id == id);
        // An outcome still being verified has not ended: closing it here would resend its text.
        let live = |status: &str| matches!(status, "running" | "waiting_approval" | "waiting_input" | "stop_requested" | "needs_verification");

        // What already existed when this worker started is the snapshot's to tell.
        if !self.primed {
            self.primed = true;
            for o in operations.iter().filter(|o| !o.coordinator) {
                let line = JobLine { status: o.status, kind: "", label: String::new(), subagents: 0 };
                self.sent_lines.insert(o.id.clone(), (line, now));
            }
            self.attention = operations
                .iter()
                .filter_map(|o| o.question.as_ref().map(|(request, _)| (o.id.clone(), request.clone())))
                .collect();
        }

        let coordinators: BTreeSet<&str> = operations.iter().filter(|o| o.coordinator).map(|o| o.id.as_str()).collect();
        self.latest_text.retain(|op, _| coordinators.contains(op.as_str()));
        self.coordinators.extend(operations.iter().filter(|o| o.coordinator && live(o.status)).map(|o| o.id.clone()));
        for op in self.coordinators.clone() {
            let Some(view) = find(&op) else {
                self.coordinators.remove(&op);
                continue;
            };
            let text = self.latest_text.get(&op).cloned().unwrap_or_default();
            let (sent, at) = self.sent_text.get(&op).cloned().unwrap_or_default();
            let finished = !live(view.status);
            if text != sent && (finished || sent.is_empty() || now - at >= DELTA_INTERVAL_MS) {
                // Native text only grows; anything else is sent whole and replaces the bubble.
                let (delta, reset) = match text.strip_prefix(sent.as_str()) {
                    Some(suffix) => (suffix, false),
                    None => (text.as_str(), true),
                };
                frames.push(json!({"kind":"coordinator_delta","operationId":op,"threadId":view.thread_id,
                    "delta":delta,"reset":reset}));
                self.sent_text.insert(op.clone(), (text.clone(), now));
            }
            if finished {
                frames.push(json!({"kind":"coordinator_done","operationId":op,"threadId":view.thread_id,"status":view.status}));
                self.latest_text.remove(&op);
                self.sent_text.remove(&op);
                self.coordinators.remove(&op);
            }
        }

        for o in operations.iter().filter(|o| !o.coordinator) {
            let previous = self.sent_lines.get(&o.id).cloned();
            let stage = self.latest_stages.get(&o.id);
            let line = JobLine {
                status: o.status,
                kind: stage.map_or(previous.as_ref().map_or("", |(line, _)| line.kind), |s| s.kind),
                label: stage.map_or_else(|| previous.as_ref().map_or(String::new(), |(line, _)| line.label.clone()), |s| s.label.clone()),
                subagents: stage.map_or(previous.as_ref().map_or(0, |(line, _)| line.subagents), |s| s.active_subagents),
            };
            let due = match &previous {
                None => live(line.status),
                Some((sent, _)) if sent.status != line.status => true,
                // The first thing a job does is shown at once; only later changes are paced.
                Some((sent, _)) if sent.kind.is_empty() && !line.kind.is_empty() => true,
                Some((sent, at)) => *sent != line && now - at >= STAGE_INTERVAL_MS,
            };
            if due {
                frames.push(json!({"kind":"job_stage","operationId":o.id,"taskId":o.task_id,"threadId":o.thread_id,
                    "status":line.status,"stage":line.kind,"stageLabel":line.label,"activeSubagents":line.subagents}));
                self.sent_lines.insert(o.id.clone(), (line, now));
            }
            if !live(o.status) {
                // Its last status has been sent; a finished operation is not followed further.
                self.latest_stages.remove(&o.id);
                self.sent_lines.remove(&o.id);
            }
        }

        let open: BTreeSet<(String, String)> = operations
            .iter()
            .filter_map(|o| o.question.as_ref().map(|(request, _)| (o.id.clone(), request.clone())))
            .collect();
        for (op, request) in open.difference(&self.attention) {
            let view = find(op);
            // The request is named, never restated: the phone shows the original from the snapshot.
            frames.push(json!({"kind":"attention_opened","operationId":op,"requestId":request,
                "taskId":view.and_then(|o| o.task_id.clone()),"threadId":view.and_then(|o| o.thread_id.clone()),
                "questionKind":view.and_then(|o| o.question.as_ref().map(|(_, kind)| *kind))}));
        }
        for (op, request) in self.attention.difference(&open) {
            let view = find(op);
            frames.push(json!({"kind":"attention_resolved","operationId":op,"requestId":request,
                "taskId":view.and_then(|o| o.task_id.clone()),"threadId":view.and_then(|o| o.thread_id.clone())}));
        }
        self.attention = open;

        for frame in &mut frames {
            self.sequence += 1;
            frame["runtimeId"] = json!(runtime);
            frame["generation"] = json!(generation);
            frame["epoch"] = json!(self.epoch);
            frame["sequence"] = json!(self.sequence);
            // Nobody listening is normal: no phone is connected.
            let _ = self.sender.send(frame.clone());
        }
        frames
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn job(status: &'static str) -> OperationView {
        OperationView {
            id: "job-op".into(),
            coordinator: false,
            task_id: Some("job-1".into()),
            thread_id: Some("thread-2".into()),
            status,
            question: None,
        }
    }
    fn stage(kind: &'static str, label: &str, subagents: u32) -> StageObservation {
        StageObservation {
            operation_id: "job-op".into(),
            kind,
            label: label.into(),
            active_subagents: subagents,
        }
    }

    #[test]
    fn job_stage_events_throttle_and_track_active_subagent_count() {
        let (sender, mut phone) = broadcast::channel(64);
        let mut stream = Stream::new(sender);
        assert!(stream.flush("r", "g", &[], 0).is_empty());

        // A job appearing is news at once.
        let frames = stream.flush("r", "g", &[job("running")], 1_000);
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0]["kind"], "job_stage");
        assert_eq!(frames[0]["taskId"], "job-1");
        assert_eq!(frames[0]["status"], "running");
        assert_eq!(frames[0]["sequence"], 1);

        // Lines changing quickly under the same status collapse to the newest, once a second.
        // Its first step is shown at once.
        stream.stage(stage("command", "cargo build", 0));
        let frames = stream.flush("r", "g", &[job("running")], 1_050);
        assert_eq!((frames.len(), frames[0]["stageLabel"].as_str()), (1, Some("cargo build")));
        stream.stage(stage("command", "cargo test", 0));
        assert!(stream.flush("r", "g", &[job("running")], 1_200).is_empty());
        stream.stage(stage("subagent", "spawnAgent", 2));
        assert!(stream.flush("r", "g", &[job("running")], 2_000).is_empty());
        let frames = stream.flush("r", "g", &[job("running")], 2_050);
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0]["stage"], "subagent");
        assert_eq!(frames[0]["stageLabel"], "spawnAgent");
        assert_eq!(frames[0]["activeSubagents"], 2);
        assert!(stream.flush("r", "g", &[job("running")], 5_000).is_empty(), "nothing changed");

        // A change of status never waits, and keeps the line it interrupts.
        stream.stage(stage("subagent", "wait", 1));
        let frames = stream.flush("r", "g", &[job("waiting_approval")], 5_100);
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0]["status"], "waiting_approval");
        assert_eq!(frames[0]["activeSubagents"], 1);
        let frames = stream.flush("r", "g", &[job("completed")], 5_150);
        assert_eq!(frames[0]["status"], "completed");
        assert_eq!(frames[0]["sequence"], 5);
        assert!(stream.flush("r", "g", &[job("completed")], 9_000).is_empty());

        // The phone received exactly these, in order.
        let received: Vec<u64> = std::iter::from_fn(|| phone.try_recv().ok())
            .map(|frame| frame["sequence"].as_u64().unwrap())
            .collect();
        assert_eq!(received, vec![1, 2, 3, 4, 5]);
    }

    #[test]
    fn coordinator_text_is_sent_as_ordered_deltas_and_closed_once() {
        let (sender, _phone) = broadcast::channel(64);
        let mut stream = Stream::new(sender);
        let lead = |status: &'static str| OperationView {
            id: "lead".into(),
            coordinator: true,
            task_id: None,
            thread_id: Some("thread-1".into()),
            status,
            question: None,
        };
        stream.flush("r", "g", &[lead("running")], 0);
        stream.text("lead", "已".into());
        assert!(stream.text_pending());
        let first = stream.flush("r", "g", &[lead("running")], 10);
        assert_eq!((first[0]["kind"].as_str(), first[0]["delta"].as_str(), first[0]["reset"].as_bool()), (Some("coordinator_delta"), Some("已"), Some(false)));
        assert!(!stream.text_pending());
        // Inside the batching window nothing is sent; afterwards only what is new.
        stream.text("lead", "已安排".into());
        assert!(stream.flush("r", "g", &[lead("running")], 40).is_empty());
        stream.text("lead", "已安排，稍后汇报".into());
        let second = stream.flush("r", "g", &[lead("running")], 60);
        assert_eq!(second[0]["delta"], "安排，稍后汇报");
        // Text that is not a continuation replaces what the phone holds.
        stream.text("lead", "改写后的全文".into());
        let third = stream.flush("r", "g", &[lead("running")], 200);
        assert_eq!((third[0]["delta"].as_str(), third[0]["reset"].as_bool()), (Some("改写后的全文"), Some(true)));
        // An unverified outcome is not an end: nothing closes and nothing is resent when it resumes.
        assert!(stream.flush("r", "g", &[lead("needs_verification")], 205).is_empty());
        assert!(stream.flush("r", "g", &[lead("running")], 206).is_empty());
        // The end flushes the rest without waiting, then closes exactly once.
        stream.text("lead", "改写后的全文。".into());
        let last = stream.flush("r", "g", &[lead("completed")], 210);
        assert_eq!(last.iter().map(|f| f["kind"].as_str().unwrap()).collect::<Vec<_>>(), ["coordinator_delta", "coordinator_done"]);
        assert_eq!(last[0]["delta"], "。");
        assert_eq!(last[1]["status"], "completed");
        assert!(stream.flush("r", "g", &[lead("completed")], 300).is_empty());
        // Text for an operation that is not the coordinator's is dropped, never sent.
        stream.text("job-op", "job transcript".into());
        assert!(stream.flush("r", "g", &[job("running")], 400).iter().all(|f| f["kind"] != "coordinator_delta"));
        assert!(!stream.text_pending());
    }
}
