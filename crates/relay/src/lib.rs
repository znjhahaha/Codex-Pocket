//! Opaque WebSocket relay for Pocket wire v1.
mod config;
mod envelope;
mod server;
mod state;

pub use config::{ConfigError, MAX_PHONES, MAX_ROOMS, RelayConfig, RoomConfig};
pub use envelope::{ENVELOPE_HEADER, Envelope, EnvelopeError, MAX_CHUNK};
pub use server::router;
pub use state::RelayState;
