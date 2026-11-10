fn main() {
    println!("cargo:rerun-if-env-changed=P2P_NATIVE_DIR");
    let dir = std::env::var("P2P_NATIVE_DIR")
        .expect("Build native/p2p separately; set P2P_NATIVE_DIR to its Release directory");
    println!("cargo:rustc-link-search=native={dir}");
    println!("cargo:rustc-link-lib=dylib=pocket-p2p");
}
