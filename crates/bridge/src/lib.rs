//! Transparent routing between an original Codex runtime and its clients.
pub mod capabilities;
pub mod discovery;
pub mod router;
pub mod settings;
pub mod permission_policy;
mod startup;
pub mod admission;
pub mod native_lifetime;
#[cfg(windows)]
pub mod registry;
#[cfg(windows)]
mod descriptor;
