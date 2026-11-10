//! Compatibility paths; all public wire types are owned by `pocket-runtime-ipc`.
pub use pocket_runtime_ipc::protocol::{
    Capabilities, Descriptor, Health, PROTOCOL, Receipt, Replay, ReplayEvent, read_frame,
    write_frame,
};
#[cfg(feature = "server")]
pub(crate) use pocket_runtime_ipc::support::{MAX_FRAME, key};
