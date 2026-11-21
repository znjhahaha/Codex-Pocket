use super::*;
use std::sync::{Arc, Mutex, MutexGuard};

/// Service owns one clone; main retains another for event projection. Calls
/// remain serialized. Do not acquire this lock from the native snapshot-ack path.
pub struct SharedHostExecutor<R: RuntimeIo> {
    inner: Arc<Mutex<HostExecutor<R>>>,
}
impl<R: RuntimeIo> Clone for SharedHostExecutor<R> {
    fn clone(&self) -> Self {
        Self {
            inner: self.inner.clone(),
        }
    }
}
impl<R: RuntimeIo> SharedHostExecutor<R> {
    pub fn new(executor: HostExecutor<R>) -> Self {
        Self {
            inner: Arc::new(Mutex::new(executor)),
        }
    }
    fn lock(&self) -> Result<MutexGuard<'_, HostExecutor<R>>> {
        self.inner
            .lock()
            .map_err(|_| anyhow::anyhow!("HostExecutor worker poisoned"))
    }
    pub fn ingest(&self, generation: &str, event: RuntimeEvent) -> Result<EventProjection> {
        self.lock()?.ingest(generation, event)
    }
    pub fn poll_events(&self, limit: usize) -> Result<EventProjection> {
        self.lock()?.poll_events(limit)
    }
    pub fn pending_requests(&self) -> Result<Vec<PendingRequest>> {
        Ok(self.lock()?.pending_requests())
    }
    pub fn reconcile_answer_receipts(&self) -> Result<()> {
        self.lock()?.reconcile_answer_receipts()
    }
    pub fn rejection_recovery_available(&self, p: &PendingRequest) -> Result<bool> {
        Ok(self.lock()?.rejection_recovery_available(p))
    }
    pub fn reject_retained_tool(&self, scope: NativeMapping, handle: &str) -> Result<()> {
        self.lock()?.reject_retained_tool(scope, handle)
    }
    pub fn operation_records(&self) -> Result<Vec<OperationRecord>> {
        Ok(self.lock()?.operation_records())
    }
    pub fn streaming_text(&self) -> Result<Vec<StreamObservation>> { Ok(self.lock()?.streaming_text()) }
    pub fn cursor(&self) -> Result<u64> { Ok(self.lock()?.cursor()) }
    pub fn reconcile_terminal_generation(&self,operations:&[crate::assistant::Operation])->Result<bool> {
        self.lock()?.reconcile_terminal_generation(operations)
    }
    pub fn adopt_scoped_generation(&self)->Result<bool> {self.lock()?.adopt_scoped_generation()}
    pub fn released_generations(&self)->Result<std::collections::BTreeSet<String>> {self.lock()?.released_generations()}
    pub fn current_generation_ready(&self,generation:&str)->Result<bool> {
        Ok(self.lock()?.current_generation_ready(generation))
    }
    pub fn reject_tool(&self, scope: NativeMapping, call_id: &str, reason: &str) -> Result<()> {
        self.lock()?.reject_tool(scope, call_id, reason)
    }
    pub fn has_tool_answer_attempt(&self, scope: &NativeMapping, call_id: &str) -> Result<bool> {
        self.lock()?.has_tool_answer_attempt(scope, call_id)
    }
    pub fn authorize_directory(&self, root: &Path, writable: bool) -> Result<()> {
        self.lock()?.authorize_directory(root, writable)
    }
    pub fn replace_directory_grants(&self, grants: Vec<DirectoryGrant>) -> Result<()> {
        self.lock()?.replace_directory_grants(grants)
    }
}
impl<R: RuntimeIo> Executor for SharedHostExecutor<R> {
    fn append_inputs(&mut self,id:&str,mapping:&NativeMapping,message:&str,inputs:&[AssistantInput])->Result<AppendOutcome> {
        self.lock()?.append_inputs(id,mapping,message,inputs)
    }
    fn append_inputs_status(&mut self,id:&str,mapping:&NativeMapping,message:&str,inputs:&[AssistantInput])->Result<AppendOutcome> {
        self.lock()?.append_inputs_status(id,mapping,message,inputs)
    }
    fn validate_directory(&mut self, directory: &Path, writes: bool) -> Result<()> {
        self.lock()?.validate_directory(directory, writes)
    }
    fn reconcile_tool_answer(&mut self, scope: &NativeMapping, call_id: &str) -> Result<bool> {
        self.lock()?.reconcile_tool_answer(scope, call_id)
    }
    fn needs_tool_upgrade(&self,thread:&str)->Result<bool> {self.lock()?.needs_tool_upgrade(thread)}
    fn append(
        &mut self,
        id: &str,
        mapping: &NativeMapping,
        message: &str,
    ) -> Result<AppendOutcome> {
        self.lock()?.append(id, mapping, message)
    }
    fn append_status(
        &mut self,
        id: &str,
        mapping: &NativeMapping,
        message: &str,
    ) -> Result<AppendOutcome> {
        self.lock()?.append_status(id, mapping, message)
    }
    fn can_resume(
        &mut self,
        thread: &str,
        role: Role,
        directory: Option<&Path>,
        writes: bool,
    ) -> Result<bool> {
        self.lock()?.can_resume(thread, role, directory, writes)
    }
    fn start(&mut self, r: StartRequest) -> Result<NativeMapping> {
        self.lock()?.start(r)
    }
    fn resume(&mut self, r: StartRequest) -> Result<NativeMapping> {
        self.lock()?.resume(r)
    }
    fn read(&mut self, r: ReadRequest) -> Result<Vec<SessionView>> {
        self.lock()?.read(r)
    }
    fn status(&mut self, r: StatusRequest) -> Result<ExecutionStatus> {
        self.lock()?.status(r)
    }
    fn validate_response(&mut self, r: &ResponseRequest) -> Result<()> {
        self.lock()?.validate_response(r)
    }
    fn validate_tools(&mut self, r: &ToolResponse) -> Result<()> {
        self.lock()?.validate_tools(r)
    }
    fn respond(&mut self, r: ResponseRequest) -> Result<()> {
        self.lock()?.respond(r)
    }
    fn interrupt(&mut self, r: NativeMapping) -> Result<()> {
        self.lock()?.interrupt(r)
    }
    fn tools(&mut self, r: ToolResponse) -> Result<()> {
        self.lock()?.tools(r)
    }
}
