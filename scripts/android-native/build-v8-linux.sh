#!/usr/bin/env bash
# Build the retained sandbox-enabled V8 from the exact OpenAI fork on Linux x86-64.
# Requires an existing Linux Rust/NDK/Clang/GN/Ninja setup; never installs packages.
set -euo pipefail
if [[ $# -ne 4 ]]; then
  echo "Usage: $0 V8_SOURCE LINUX_NDK CLANG21_OR_NEWER_ROOT ARTIFACT_OUTPUT" >&2
  exit 2
fi
source_root=$(realpath "$1")
ndk=$(realpath "$2")
clang_root=$(realpath "$3")
output=$(realpath -m "$4")
scripts=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
revision=90a65f887a0c0f048f3784afbcb320b373c8a2e0
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'V8 Android source builds require Linux x86-64.' >&2; exit 2; }
[[ $(git -C "$source_root" rev-parse HEAD) == "$revision" ]] || { echo 'V8 source revision differs from the pinned fork.' >&2; exit 2; }
grep -q 'Pkg.Revision = 28.2.13676358' "$ndk/source.properties"
for tool in cargo rustup python3 ninja gn; do command -v "$tool" >/dev/null; done
[[ -x "$clang_root/bin/clang" && -d "$clang_root/lib" ]]
clang_version=$("$clang_root/bin/clang" -dumpversion)
python3 -c 'import sys; v=tuple(map(int,sys.argv[1].split(".")[:2])); assert v >= (21,1), "V8 requires Clang 21.1+"' "$clang_version"
# Upstream build.rs otherwise shallow-clones moving HEADs. Pin these Android
# dependencies to the revisions in this V8 submodule's DEPS instead.
python3 - "$source_root" "$scripts/inputs.lock.json" <<'PY'
import json, pathlib, re, subprocess, sys
root = pathlib.Path(sys.argv[1])
pins = json.loads(pathlib.Path(sys.argv[2]).read_text())["v8"]["android_dependencies"]
deps = (root / "v8/DEPS").read_text()
for name, pin in pins.items():
    expected = re.search(r"'third_party/" + name + r"':\s*\{\s*'url':[^\n]*'@'\s*\+\s*'([0-9a-f]{40})'", deps)
    if not expected or expected.group(1) != pin["commit"]:
        raise SystemExit(f"Android dependency pin differs from V8 DEPS: {name}")
    path = root / "third_party" / name
    if not path.exists():
        subprocess.run(["git", "init", str(path)], check=True)
    if not (path / ".git").exists():
        raise SystemExit(f"Expected a Git dependency checkout: {path}")
    head = subprocess.run(["git", "-C", str(path), "rev-parse", "HEAD"], capture_output=True, text=True)
    if head.returncode == 0 and head.stdout.strip() != pin["commit"]:
        raise SystemExit(f"Existing dependency differs from pin; left intact: {path}")
    if head.returncode != 0:
        subprocess.run(["git", "-C", str(path), "fetch", "--depth=1", pin["repository"], pin["commit"]], check=True)
        subprocess.run(["git", "-C", str(path), "checkout", "--detach", "FETCH_HEAD"], check=True)
PY
for patch in \
  patches/termux/v8/0001-unset-BINDGEN_EXTRA_CLANG_ARGS-in-v8_s-bindgen.patch \
  patches/termux/v8/0002-install-sysroot.patch \
  patches/v8-custom-ndk.patch; do
  if git -C "$source_root" apply --reverse --check "$scripts/$patch" 2>/dev/null; then continue; fi
  git -C "$source_root" apply --check "$scripts/$patch"
  git -C "$source_root" apply "$scripts/$patch"
done
if [[ -e "$source_root/third_party/android_ndk" || -L "$source_root/third_party/android_ndk" ]]; then
  [[ $(realpath "$source_root/third_party/android_ndk") == "$ndk" ]] || { echo 'Existing V8 NDK path differs; source left intact.' >&2; exit 2; }
else
  ln -s "$ndk" "$source_root/third_party/android_ndk"
fi
export CARGO_HOME=${CARGO_HOME:-"$source_root/.cargo-home"}
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-"$source_root/target-android"}
export V8_FROM_SOURCE=1
export CLANG_BASE_PATH="$clang_root"
export LIBCLANG_PATH="$clang_root/lib"
export GN=$(command -v gn)
export NINJA=$(command -v ninja)
unset DOCS_RS DENO_TRYBUILD RUSTY_V8_SKIP_DOWNLOAD
unset RUSTY_V8_ARCHIVE RUSTY_V8_SRC_BINDING_PATH
unset EXTRA_GN_ARGS
# GN_ARGS participates in Cargo's rerun-if-env-changed contract.
export GN_ARGS="android_ndk_api_level=28 default_min_sdk_version=28 android_ndk_root=\"$ndk\" android_ndk_version=\"r28c\""
cd "$source_root"
for target in aarch64-linux-android x86_64-linux-android; do
  rustup target list --installed --toolchain 1.99.1 | grep -qx "$target"
  triple=${target//-/_}
  export "CC_${triple}=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${target}28-clang"
  export "CXX_${triple}=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${target}28-clang++"
  export "AR_${triple}=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
  export "CARGO_TARGET_${triple^^}_LINKER=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${target}28-clang"
  export BINDGEN_EXTRA_CLANG_ARGS="--target=${target}28 --sysroot=$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
  cargo +1.99.1 build --locked --release --lib --features v8_enable_sandbox --target "$target" --jobs "${JOBS:-4}"
  gn_out="$CARGO_TARGET_DIR/$target/release/gn_out"
  [[ -s "$gn_out/obj/librusty_v8.a" && -s "$gn_out/src_binding.rs" ]]
  mkdir -p "$output/$target"
  cp "$gn_out/obj/librusty_v8.a" "$output/$target/librusty_v8.a"
  cp "$gn_out/src_binding.rs" "$output/$target/src_binding.rs"
  printf '{"schema":1,"revision":"%s","target":"%s","features":["v8_enable_sandbox"],"ndk_version":"28.2.13676358","min_sdk":28}\n' \
    "$revision" "$target" > "$output/$target/v8-build.json"
done
