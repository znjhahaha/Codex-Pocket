use super::Settings;
use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct NativeMapping {
    pub host_generation: String,
    pub thread_id: String,
    pub turn_id: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Role {
    Coordinator,
    Execution,
}

/// The Host must validate model availability and project authorization. The
/// coordinator registers only the finite Assistant tools and is read-only.
#[derive(Clone, Debug)]
pub struct StartRequest {
    pub operation_id: String,
    pub host_generation: String,
    pub role: Role,
    pub thread_id: Option<String>,
    pub prompt: String,
    pub directory: Option<PathBuf>,
    pub writes: bool,
    pub settings: Settings,
    pub instructions: String,
}

#[derive(Clone, Debug)]
pub enum ReadRequest {
    Sessions { limit: usize },
    Thread { thread_id: String, limit: usize },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SessionView {
    pub thread_id: String,
    pub summary: String,
}

#[derive(Clone, Debug)]
pub struct StatusRequest {
    pub operation_id: String,
    pub mapping: Option<NativeMapping>,
    pub host_generation: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum QuestionKind {
    Input,
    Approval,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct UserQuestion {
    pub request_id: String,
    pub kind: QuestionKind,
    pub text: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum ExecutionStatus {
    Unknown,
    /// Only trustworthy receipt/process evidence may produce this variant.
    NotDispatched,
    Running(NativeMapping),
    Waiting(NativeMapping, UserQuestion),
    Completed(NativeMapping, String),
    Failed(NativeMapping, String),
    Interrupted(NativeMapping),
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum HumanResponse {
    Answer(String),
    Approval { approved: bool },
}

#[derive(Clone, Debug)]
pub struct ResponseRequest {
    pub operation_id: String,
    pub command_id: String,
    pub mapping: NativeMapping,
    pub request_id: String,
    pub response: HumanResponse,
}

#[derive(Clone, Debug)]
pub struct ToolResponse {
    pub scope: NativeMapping,
    pub call_id: String,
    pub result: serde_json::Value,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AppendOutcome {
    Unsupported,
    /// Exact durable evidence proves rejection or no native dispatch.
    Rejected,
    Delivered,
    Uncertain,
}

/// Implement on a serialized worker. An error from any mutating operation is
/// ambiguous, not proof that the Host rejected or never accepted the request.
/// `status` resolves persisted operation IDs after a lost native start receipt.
pub trait Executor {
    fn needs_tool_upgrade(&self, _thread: &str) -> Result<bool> {
        Ok(false)
    }
    /// Repeated calls only reconcile the stable append receipt; never resend an uncertain effect.
    fn append(
        &mut self,
        _id: &str,
        _mapping: &NativeMapping,
        _message: &str,
    ) -> Result<AppendOutcome> {
        Ok(AppendOutcome::Unsupported)
    }
    /// Receipt-only reconciliation, safe while the product write gate is closed.
    fn append_status(
        &mut self,
        _id: &str,
        _mapping: &NativeMapping,
        _message: &str,
    ) -> Result<AppendOutcome> {
        Ok(AppendOutcome::Unsupported)
    }
    /// Only positive native ownership, current policy, and a stopped prior turn allow reuse.
    fn can_resume(
        &mut self,
        _thread: &str,
        _role: Role,
        _directory: Option<&Path>,
        _writes: bool,
    ) -> Result<bool> {
        Ok(false)
    }
    fn start(&mut self, request: StartRequest) -> Result<NativeMapping>;
    fn resume(&mut self, request: StartRequest) -> Result<NativeMapping>;
    fn read(&mut self, request: ReadRequest) -> Result<Vec<SessionView>>;
    fn status(&mut self, request: StatusRequest) -> Result<ExecutionStatus>;
    /// Pure local target/encoding validation, before saving a human answer intent.
    fn validate_response(&mut self, request: &ResponseRequest) -> Result<()>;
    fn validate_tools(&mut self, response: &ToolResponse) -> Result<()>;
    fn respond(&mut self, request: ResponseRequest) -> Result<()>;
    fn interrupt(&mut self, mapping: NativeMapping) -> Result<()>;
    fn tools(&mut self, response: ToolResponse) -> Result<()>;
}
