# Phone Linux native components and build inputs

`linux.lock.json` identifies every downloaded binary/source input by URL and
SHA-256. `linux_environment.py` builds the rootfs and stages the native libraries.
The accompanying `sources/` directory contains the locked source archives, the
complete Termux recipe/build-system snapshot, this guide, the lock, and Pocket's
packaging script. Keep this directory with the distributed APK/release assets.
The APK includes the notices under `assets/phone-linux/<abi>/licenses/`.

## Native libraries

| Installed name | Input | Source and notice |
| --- | --- | --- |
| `libproot.so`, `libproot-loader.so` | Termux `proot` 5.1.107.102, aarch64/x86_64 | `termux/proot` tag `v5.1.107.102`; upstream `COPYING` (GPL version 2 or later) |
| `libtalloc.so` | Termux `libtalloc` 2.5.0, aarch64/x86_64 | Samba `talloc-2.5.0.tar.gz`; library `talloc.c` and `LICENSE` specify LGPL version 3 or later; LGPL and incorporated GPL v3 full texts are included |
| `libandroid-shmem.so` | Termux `libandroid-shmem` 0.7, aarch64/x86_64 | `termux/libandroid-shmem` tag `v0.7`; upstream BSD 3-Clause `LICENSE` |

Termux labels its talloc package `GPL-3.0`; the library itself explicitly uses
LGPL-3.0-or-later. Both the complete source archive and the original recipe are
retained, including notices for bundled build/support code.

Upstream locations:

- https://github.com/termux/proot/tree/v5.1.107.102
- https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz
- https://github.com/termux/libandroid-shmem/tree/v0.7
- https://github.com/termux/termux-packages/tree/81b4480b4b1433a925d0ec7ae236f424b3cb5f05
- https://www.gnu.org/licenses/gpl-3.0.txt

The pinned Termux snapshot includes `packages/proot`, `packages/libtalloc`,
`packages/libandroid-shmem`, all package patches and the supporting build scripts.
Its recipes select the same source versions and SHA-256 values as this package.
It is a captured corresponding build recipe, not an attestation of which Termux
build machine originally produced the downloaded `.deb` files.

## Rebuilding the native inputs

Extract the Termux build-system archive on its supported Linux build host and
follow its README/build-package.sh prerequisites. With the source versions still
pinned to the recipes above, build for both target architectures:

```sh
./build-package.sh -a aarch64 libandroid-shmem
./build-package.sh -a aarch64 libtalloc
./build-package.sh -a aarch64 proot
./build-package.sh -a x86_64 libandroid-shmem
./build-package.sh -a x86_64 libtalloc
./build-package.sh -a x86_64 proot
```

These recipes set the Android/Termux prefix, compiler, linker and dependency
environment. PRoot builds from `src` with `PROOT_WITH_LIBANDROID_SHMEM=true`,
`ARG_MAX=131072`, the pinned `VERSION`, and an unbundled loader in
`$TERMUX_PREFIX/libexec/proot`. Talloc uses its recorded cross answers with
`--disable-rpath --disable-python --cross-compile`. The complete scripts, rather
than a shortened substitute, accompany this release.

Pocket consumes the reviewed binary `.deb` inputs locked in `linux.lock.json`.
It copies PRoot, its loader, talloc and shmem from the Debian data archives. Android
installs executables from `lib*.so` entries, so PRoot is named `libproot.so` and
its loader `libproot-loader.so`. Pocket changes the single ELF dynamic-name string
`libtalloc.so.2\0` to `libtalloc.so\0\0\0` in PRoot's dependency entry and talloc's
SONAME. The byte length stays unchanged. No other binary patch is applied.
`native_libraries()` in the delivered script performs and validates this change.
Replacing/rebuilding the libraries requires restaging and rebuilding the APK.

```sh
python linux_environment.py --abi all --cache <input-cache> --output <new-stage>
# Notices/source delivery for an already built, identical rootfs:
python linux_environment.py --abi all --cache <input-cache> --output <stage> --licenses-only
```

## Rootfs and Pocket executables

Ubuntu, Python and Node retain their upstream notices in their original rootfs
paths (`usr/share/doc`, `opt/python`, `opt/node`). The binary input URLs and package
versions are also recorded in each ABI's `licenses/source-inputs.json`.

Pocket agent/runtime-host are built from a frozen copy of the current worktree
with the checked-in Cargo.lock files, Rust 1.93.1, cargo-zigbuild 0.21.7 and Zig
0.14.1, targeting `aarch64-unknown-linux-musl` and `x86_64-unknown-linux-musl`.
`build-phone-runtime.sh` records the exact build entry points;
`package_agent.py` validates static ELF machine and ARM64 16 KiB alignment and
writes `libpocket-runtime-manifest.so` with the two binaries' version and hashes.
The final package provenance identifies the frozen source snapshot and logs.

`libcodex.so` and `libcodex-code-mode-host.so` are the unmodified official OpenAI
Codex 0.171.0 static releases from `official.lock.json`. Both are required: models
using Code Mode execute tools through the companion host. `fetch_official.py`
stages both by default. PRoot binds the packaged host to the sibling filename
`codex-code-mode-host` expected by the Linux Codex executable. Neither binary is
rebuilt as part of the Pocket build. Preserve their
official digest/signature verification and Apache-2.0 notice/source reference.
No dsh-Go source code is included in these components or packaging scripts.

## Download the phone environment on first use

`package_on_demand.py` splits an existing staged runtime into a small APK input
tree and one complete environment archive per ABI:

```sh
python package_on_demand.py --source <full-stage> --output <apk-stage> \
  --downloads <release-assets> --revision <immutable-revision> \
  --asset-base https://github.com/znjhahaha/Codex-Pocket/releases/download/<tag>
```

Pass `<apk-stage>` as Gradle's `pocketOnDeviceRuntime`. The APK retains the native
PRoot bootstrap and notices, plus `manifest.json` and `download.json` under
`assets/phone-linux/<abi>`. The signed APK pins the download's size and digest.
The ZIP contains `rootfs/` and `runtime/`; the latter keeps the original Codex,
Code Mode, Pocket agent/host and runtime manifest bytes. Upload both ABI archives
at their declared URLs before publishing the APK. Ship the corresponding source
archives beside the release, as for the bundled variant.

Only the explicit phone-runtime preparation downloads this payload. Remote
computer connections retain their existing startup path. The installed Linux
home and user project directories are kept when the APK is updated.
