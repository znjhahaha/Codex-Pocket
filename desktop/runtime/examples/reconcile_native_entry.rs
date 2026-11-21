fn main()->anyhow::Result<()> {
    let root=std::path::PathBuf::from(std::env::args_os().nth(1).ok_or_else(||anyhow::anyhow!("Explicit installation root required"))?);
    pocket_desktop_runtime::native_entry::reconcile(&root)?;
    println!("native_entry_verified");
    Ok(())
}
