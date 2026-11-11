#![allow(dead_code, unused_imports)]
extern crate pocket_agent;
pub use pocket_agent::{capabilities, handoff, managed_runtime, settings, storage};
#[path = "../assistant/mod.rs"]
pub mod assistant;
#[path = "../assistant_executor.rs"]
pub mod assistant_executor;
#[path = "../assistant_product.rs"]
mod assistant_product;
