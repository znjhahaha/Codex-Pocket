#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
fn main(){match pocket_desktop_runtime::launcher::launch(){Ok(code)=>std::process::exit(code),Err(_)=>{eprintln!("Codex Pocket launcher could not validate the installation or locate the current Codex runtime. Open connection diagnosis.");std::process::exit(1)}}}
