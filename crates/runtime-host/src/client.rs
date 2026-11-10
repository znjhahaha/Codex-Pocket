//! Compatibility paths for the agent-independent IPC client.
pub use pocket_runtime_ipc::client::{Discovery, HostClient, load_descriptor};
#[cfg(feature = "server")]
pub(crate) use pocket_runtime_ipc::support::{normalized_home, read_private};
