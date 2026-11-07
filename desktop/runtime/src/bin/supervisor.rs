#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
fn main(){if run().is_err(){std::process::exit(1)}}
fn run()->anyhow::Result<()>{
    let args:Vec<_>=std::env::args_os().skip(1).collect();
    let option=|key:&str|args.iter().position(|a|a==key).and_then(|i|args.get(i+1)).map(std::path::PathBuf::from);
    let state=option("--state").ok_or_else(||anyhow::anyhow!("--state required"))?;
    let agent=option("--agent").unwrap_or(std::env::current_exe()?.with_file_name("pocket-agent.exe"));
    pocket_desktop_runtime::supervisor::run(&state,&agent,option("--owner-directory"))
}
