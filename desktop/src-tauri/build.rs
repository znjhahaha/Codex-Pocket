fn main() {
    // A plain cargo release build otherwise uses devUrl instead of the bundled
    // UI, producing an installable window with no page when Vite is absent.
    if std::env::var("PROFILE").as_deref() == Ok("release")
        && std::env::var_os("CARGO_FEATURE_CUSTOM_PROTOCOL").is_none()
    {
        panic!("Pocket release requires embedded UI assets: build with --features pocket-desktop/custom-protocol");
    }
    tauri_build::build()
}
