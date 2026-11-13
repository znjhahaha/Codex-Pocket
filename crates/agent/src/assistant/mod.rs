//! Executable local assistant coordinator, task service and civil-time scheduler,
//! independent of native RPC and the broker. `AssistantService` owns a serialized
//! worker and delegates all native effects to the narrow `Executor` boundary.
//!
//! Open one store in the computer's private state directory using its existing
//! stable computer ID. SQLite serializes claims across handles. Use `apply` for
//! user commands with a stable command ID; replay returns the original receipt.
//! The clock is supplied as Unix milliseconds so scheduling is deterministic.
//!
//! A successful `claim_next` reserves one execution and its directory before any
//! external effect. Only that newly returned lease authorizes dispatch. Never
//! dispatch a lease reconstructed from a snapshot. On ambiguity use `Unknown`;
//! after proving the previous executor has stopped use `recover_after_restart`.
//! Both retain the reservation until `reconcile` records a known result. User
//! takeover also fences old leases; the host must interrupt its executor, then
//! reconcile it. Releasing takeover does not resume paused tasks automatically.
//! The host must coordinate dispatch with takeover and revalidate workspace
//! paths before each external effect; a database reservation is not an OS lock.
//!
//! This synchronous API belongs on a dedicated worker or `spawn_blocking`. The
//! host owns filesystem permissions, executor cancellation and reconciliation
//! evidence. The Store itself does not invoke models. The Service dispatches
//! entrusted turns through Executor; it never infers memories or writes projects.

mod store;
pub use store::AssistantStore;
mod executor;
mod schedule;
mod service;
mod settings;
pub use executor::*;
pub use schedule::*;
pub use service::*;
pub use settings::*;

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

pub const MAX_CONCURRENT_TASKS: usize = 3;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    Queued,
    Running,
    WaitingUser,
    Paused,
    Done,
    Failed,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Task {
    pub id: String,
    pub assistant_id: String,
    pub prompt: String,
    pub directory: PathBuf,
    pub writes: bool,
    pub not_before_ms: i64,
    pub status: TaskStatus,
    pub active_run_id: Option<String>,
    pub reconciliation_required: bool,
    /// Last known result, failure explanation or question for the user.
    pub detail: Option<String>,
    #[serde(default)]
    pub assignment: Option<TaskAssignment>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Memory {
    pub id: String,
    pub text: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Command {
    CreateAssignedTask {
        prompt: String,
        directory: PathBuf,
        writes: bool,
        assignment: TaskAssignment,
    },
    CreateTask {
        prompt: String,
        directory: PathBuf,
        writes: bool,
        not_before_ms: i64,
    },
    PauseTask {
        task_id: String,
    },
    ResumeTask {
        task_id: String,
    },
    /// Retains logical task identity and its dependency/group contract. The
    /// prior native execution must already be confirmed stopped.
    ReassignTask {
        task_id: String,
        member_id: String,
        message: String,
    },
    TakeOver,
    ReleaseTakeover,
    /// Only an explicit user instruction may create a memory.
    Remember {
        text: String,
    },
    EditMemory {
        memory_id: String,
        text: String,
    },
    DeleteMemory {
        memory_id: String,
    },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "value", rename_all = "snake_case")]
pub enum CommandResult {
    Task(Task),
    /// Identity only: read current text using `memories`. Receipts must not keep
    /// obsolete memory contents after the user edits or deletes the memory.
    Memory(String),
    MemoryDeleted(String),
    Takeover(bool),
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CommandReceipt {
    pub command_id: String,
    pub result: CommandResult,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RunLease {
    pub task: Task,
    pub run_id: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "detail", rename_all = "snake_case")]
pub enum RunOutcome {
    Done(String),
    Failed(String),
    WaitingUser(String),
    Unknown,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "outcome", rename_all = "snake_case")]
pub enum Reconciliation {
    Resolved(RunOutcome),
    /// The host has evidence that the reserved execution never dispatched.
    /// Leaves the task paused; another user command is needed to requeue it.
    NotDispatched,
    /// Host proved that the actual native turn stopped after interruption.
    /// Leaves the task paused and releases its reservation without claiming
    /// that the execution never ran.
    Stopped,
}
