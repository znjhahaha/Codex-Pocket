//! Private Runtime Host IPC. Brokers depend on this crate with default features disabled.
pub mod client;
pub mod protocol;
pub use protocol::{Descriptor, read_frame, write_frame};
#[cfg(feature = "server")]
mod recovery;
#[cfg(feature = "server")]
mod server;
#[cfg(feature = "server")]
pub use server::{HostConfig, run};
