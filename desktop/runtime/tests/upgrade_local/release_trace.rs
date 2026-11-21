//! Opt-in diagnostics for copied executables in a marked private fixture.
//! Delegate every effect to Windows; log no RPC parameters, replies or tokens.
use super::*;

struct TracedWindows {
    inner: Windows,
    log: std::fs::File,
}
impl TracedWindows {
    fn event<T>(&mut self, call: &str, detail: Value, result: &Result<T>) {
        let failure = result.as_ref().err().map(|error| {
            let known = [
                "Maintenance acknowledgement unavailable",
                "Maintenance submission unavailable",
                "Process exit identity unavailable",
                "Process has not exited gracefully",
                "State path is redirected by the launching process",
                "Another instance already owns this state or user",
                "Unexpected maintenance capability owner",
                "Maintenance capability ACL is not private",
            ];
            json!({
                "io":error.downcast_ref::<std::io::Error>().map(|e|json!({"kind":format!("{:?}",e.kind()),"rawOsError":e.raw_os_error()})),
                "redirectedPath":error.is::<storage::RedirectedPath>(),
                "directoryPolicy":error.downcast_ref::<storage::DirectoryPolicyError>().map(|e|format!("{e:?}")),
                "json":error.is::<serde_json::Error>(),
                "known":error.chain().flat_map(|e|known.iter().copied().filter(move |message|e.to_string()==*message)).collect::<Vec<_>>()
            })
        });
        let line = json!({"at":storage::now(),"call":call,"detail":detail,"ok":result.is_ok(),"failure":failure});
        writeln!(self.log, "{line}").unwrap();
        self.log.flush().unwrap();
    }
}
impl Adapter for TracedWindows {
    fn capture(&mut self, pid: u32) -> Result<ProcessIdentity> { self.inner.capture(pid) }
    fn presence(&mut self, p: &ProcessIdentity) -> maintenance::Presence { self.inner.presence(p) }
    fn tick(&mut self) { self.inner.tick(); }
    fn read(&mut self, path: &Path, limit: u64) -> Result<Vec<u8>> {
        let result = self.inner.read(path, limit);
        if result.is_err() { self.event("read", json!({"file":path.file_name()}), &result); }
        result
    }
    fn rpc(&mut self, state: &Path, method: &str, params: Value) -> Result<Value> {
        let result = self.inner.rpc(state, method, params);
        self.event("rpc", json!({"method":method}), &result);
        result
    }
    fn spawn(&mut self, s: &UpgradeStatus, generation: &str, cp: &CheckpointRef, remote: bool) -> Result<u32> {
        self.inner.spawn(s, generation, cp, remote)
    }
    fn submit(&mut self, state: &Path, request: &Request) -> Result<()> {
        let result = self.inner.submit(state, request);
        self.event("submit", json!({"action":request.action}), &result);
        result
    }
    fn ack(&mut self, state: &Path) -> Result<Option<Journal>> {
        let result = self.inner.ack(state);
        self.event("ack", json!({"phase":result.as_ref().ok().and_then(|j|j.as_ref().map(|j|j.phase))}), &result);
        result
    }
    fn save(&mut self, s: &UpgradeStatus, durable: &Durable) -> Result<()> {
        let result = self.inner.save(s, durable);
        self.event("save", json!({"stage":durable.stage}), &result);
        result
    }
    fn locks_free(&mut self, s: &UpgradeStatus) -> Result<()> {
        let result = self.inner.locks_free(s);
        self.event("locks_free", json!({}), &result);
        result
    }
}

struct TracedBackend;
impl HandoffBackend for TracedBackend {
    fn supports_handoff(&mut self, s: &UpgradeStatus) -> Result<bool> { LocalBackend.supports_handoff(s) }
    fn quiesce(&mut self, s: &UpgradeStatus) -> Result<Checkpoint> { LocalBackend.quiesce(s) }
    fn released_checkpoint(&mut self, s: &UpgradeStatus) -> Result<Option<Checkpoint>> { LocalBackend.released_checkpoint(s) }
    fn start_new(&mut self, s: &UpgradeStatus) -> Result<()> { LocalBackend.start_new(s) }
    fn verify_new(&mut self, s: &UpgradeStatus) -> Result<Verification> { LocalBackend.verify_new(s) }
    fn rollback(&mut self, s: &UpgradeStatus) -> Result<Verification> { LocalBackend.rollback(s) }
    fn release_old(&mut self, s: &UpgradeStatus) -> Result<()> {
        let log = std::fs::OpenOptions::new().create(true).append(true).open(s.install_root.join("release-trace.jsonl"))?;
        let mut adapter = TracedWindows { inner: Windows, log };
        let mut durable = load(&mut adapter, s)?;
        let result = stopped(&mut adapter, s, &mut durable);
        adapter.event("stopped", json!({"stage":durable.stage}), &result);
        result
    }
}

#[test]
#[ignore = "requires a marked isolated fixture and a copied test EXE named pocket-upgrade.exe"]
fn actual_release_trace_coordinator() {
    let root = PathBuf::from(std::env::var_os("POCKET_TRACE_FIXTURE").expect("explicit fixture root"));
    storage::unredirected(&root).unwrap();
    assert_eq!(std::fs::read(root.join(".fixture-owner")).unwrap(), b"handoff-chain-test");
    let state = root.join("中文 state");
    let mut coordinator = crate::supervisor::upgrade::Coordinator::open(&root, &state).unwrap();
    assert_eq!(coordinator.status().install_root, root);
    assert_eq!(coordinator.status().operation_id, std::env::var("POCKET_TRACE_OPERATION").unwrap());
    let result = coordinator.execute(&mut TracedBackend).unwrap();
    assert_eq!(result.phase, crate::supervisor::upgrade::Phase::Succeeded, "inspect private release-trace.jsonl; failure {:?}", result.failure);
}
