//! Agent-independent, trusted local Runtime Host IPC.
//!
//! Brokers should depend on this package directly. It has no host/agent dependency,
//! server feature, process launcher, journal writer, or authority to reconcile
//! unknown dispatches. Discovery/attachment verify existing ownership only.
//! Never expose the client, native RPC methods or descriptor token to a phone.
pub mod client;
pub mod protocol;

pub use client::{Discovery, HostClient, load_descriptor};
pub use protocol::{
    Capabilities, Descriptor, Health, PROTOCOL, Receipt, Replay, ReplayEvent, read_frame,
    write_frame,
};

/// Implementation support shared with the host; not a broker authorization API.
#[doc(hidden)]
pub mod support {
    pub use crate::client::{normalized_home, read_private};
    pub use crate::protocol::{MAX_FRAME, key};
}
