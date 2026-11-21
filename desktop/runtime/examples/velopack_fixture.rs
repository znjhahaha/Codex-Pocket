//! Packaging/update harness entry only. It cannot launch Pocket or write data.
fn main(){velopack::VelopackApp::build().set_auto_apply_on_startup(false).run();println!("Isolated Velopack packaging fixture");}
