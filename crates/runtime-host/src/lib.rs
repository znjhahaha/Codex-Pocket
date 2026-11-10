//! Private Runtime Host server and compatibility reexports.
//! Brokers should depend directly on the agent-independent `pocket-runtime-ipc`.
pub mod client;
pub mod protocol;
pub use protocol::{Descriptor, read_frame, write_frame};
#[cfg(feature = "server")]
mod recovery;
#[cfg(feature = "server")]
mod server;
#[cfg(feature = "server")]
pub use server::{HostConfig, run};
