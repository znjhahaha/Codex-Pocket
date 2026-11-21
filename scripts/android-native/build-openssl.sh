#!/usr/bin/env bash
# Called by build_openssl.py under the existing MSYS environment.
set -euo pipefail
export PATH="/usr/bin:$PATH"
[[ $# == 6 ]] || { echo 'Expected SOURCE NDK WORK OUTPUT TARGET JOBS' >&2; exit 2; }
source_root=$(cygpath -u "$1")
ndk=$(cygpath -u "$2")
work=$(cygpath -u "$3")
output=$(cygpath -u "$4")
target=$5
jobs=$6
case "$target" in
  aarch64-linux-android) openssl_target=android-arm64 ;;
  x86_64-linux-android) openssl_target=android-x86_64 ;;
  *) echo "Unsupported Android target: $target" >&2; exit 2 ;;
esac
export ANDROID_NDK_ROOT="$ndk"
export PATH="$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin:/usr/bin:$PATH"
mkdir -p "$work" "$output/include/openssl" "$output/lib"
cd "$work"
perl "$source_root/Configure" "$openssl_target" -D__ANDROID_API__=28 \
  no-shared no-module no-tests no-comp no-zlib no-zlib-dynamic no-ssl3 \
  --libdir=lib --prefix=/ --openssldir=/system/etc/security
# This host has MSYS bash but no /bin/sh alias. Pass the shell explicitly,
# including to recursive make, without modifying the MSYS installation.
/usr/bin/make SHELL=/usr/bin/bash -j"$jobs" build_libs
cp "$source_root"/include/openssl/*.h "$output/include/openssl/"
cp include/openssl/*.h "$output/include/openssl/"
cp libssl.a libcrypto.a "$output/lib/"
