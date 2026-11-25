#!/usr/bin/env bash
# Build frozen Pocket sources on Windows/Git Bash with the existing Zig route.
# Usage: build-phone-runtime.sh SOURCE_ROOT TARGET_DIR LOG_DIR
set -euo pipefail
source_root=$(cygpath -u "$1")
target_dir=$(cygpath -w "$2")
log_dir=$(cygpath -u "$3")
mkdir -p "$log_dir"
export PATH="/e/pocket-build/linux-tools/bin:$PATH"
export CARGO_ZIGBUILD_ZIG_PATH='E:\pocket-build\linux-python\ziglang\zig.exe'
export CARGO_ZIGBUILD_CACHE_DIR='E:\pocket-build\zigbuild-cache'
export ZIG_GLOBAL_CACHE_DIR='E:\pocket-build\zig-cache'
export ZIG_LOCAL_CACHE_DIR='E:\pocket-build\zig-cache-local'
export CARGO_TARGET_DIR="$target_dir"
cd "$source_root"
rustc +1.93.1 --version
cargo +1.93.1 --version
cargo-zigbuild --version
"$CARGO_ZIGBUILD_ZIG_PATH" version
for target in aarch64-unknown-linux-musl x86_64-unknown-linux-musl; do
  cargo +1.93.1 zigbuild --release --locked --target "$target" -p pocket-agent --bin pocket-agent \
    > "$log_dir/$target-agent.log" 2>&1
  printf '%s agent complete\n' "$target"
  cargo +1.93.1 zigbuild --release --locked --target "$target" --manifest-path crates/runtime-host/Cargo.toml --bin pocket-runtime-host \
    > "$log_dir/$target-host.log" 2>&1
  printf '%s host complete\n' "$target"
done
