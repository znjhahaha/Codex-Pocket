use super::Settings;
use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

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

/// Implement on a serialized worker. An error from any mutating operation is
/// ambiguous, not proof that the Host rejected or never accepted the request.
/// `status` resolves persisted operation IDs after a lost native start receipt.
pub trait Executor {
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
