pub mod policy;
pub mod storage;
pub mod process;
pub mod supervisor;
pub mod launcher;
pub mod pairing;
pub mod connection;
pub mod installation;
pub mod diagnostics;
pub mod desktop_presence;
pub mod updates;
pub mod package_update;
pub mod manager_update;
pub mod ui_update;
pub mod local_update;
pub mod update_worker;
pub mod native_entry;
pub mod native_update;
pub use pocket_network as network;

pub mod connectivity;
#[cfg(test)]
#[path = "../test_support/mod.rs"]
mod test_support;
pub mod update_task;
