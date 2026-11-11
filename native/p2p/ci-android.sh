#!/usr/bin/env bash
set -Eeuo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
reports="$root/.local/p2p/ci-reports/android"
mkdir -p "$reports"
exec > >(tee "$reports/build.log") 2>&1
started="$(date -u +%FT%TZ)"
phase=prepare
# Remove only the previous per-ABI summaries; a failed new run must not reuse them.
rm -f -- "$reports/arm64-v8a.json" "$reports/x86_64.json"

finish() {
  result=$?
  trap - EXIT
  python3 - "$reports" "$result" "$phase" "$started" <<'PY'
import datetime, json, pathlib, sys
reports, code, phase, started = sys.argv[1:]
reports = pathlib.Path(reports)
libraries = []
for abi in ('arm64-v8a', 'x86_64'):
    manifest = reports / f'{abi}.json'
    if manifest.exists():
        libraries.extend(json.loads(manifest.read_text()))
report = dict(status='passed' if code == '0' else 'failed', exitCode=int(code),
              phase=phase, startedUtc=started,
              finishedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
              ndk='28.2.13676358', cmake='3.22.1', androidApi=26,
              deviceExecution=False, libraries=libraries)
(reports / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
lines = ['# Android P2P native builds', '', f'Result: {report["status"]}; exit: {code}; last phase: {phase}.',
         'NDK 28.2.13676358; CMake 3.22.1; API 26; Release; strip --strip-unneeded.',
         'Sources: sources.sha256. Locked upstream commits and declared tree digests: dependencies.lock.json.',
         'Cross-compilation and ELF inspection only; no device, emulator, or model calls.', '',
         '| ABI | Library | Stripped bytes | SHA256 |', '|---|---|---:|---|']
for item in libraries:
    lines.append(f'| {item["abi"]} | {item["library"]} | {item["bytes"]} | {item["sha256"]} |')
(reports / 'report.md').write_text('\n'.join(lines) + '\n')
PY
  exit "$result"
}
trap finish EXIT

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" ]]; then echo 'ANDROID_SDK_ROOT or ANDROID_HOME is required'; exit 1; fi
ndk="$sdk/ndk/28.2.13676358"
cmake="$sdk/cmake/3.22.1/bin/cmake"
ninja="$sdk/cmake/3.22.1/bin/ninja"
llvm="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
jobs="${P2P_JOBS:-2}"
budget="${P2P_BUILD_TIMEOUT_SECONDS:-600}"
[[ "$jobs" =~ ^[1-8]$ ]] || { echo 'P2P_JOBS must be 1..8'; exit 1; }
[[ "$budget" =~ ^[0-9]{2,4}$ ]] && ((10#$budget >= 60 && 10#$budget <= 1200)) || { echo 'Build deadline must be 60..1200 seconds'; exit 1; }
for tool in "$cmake" "$ninja" "$llvm/llvm-strip" "$llvm/llvm-readelf"; do
  [[ -x "$tool" ]] || { echo "Missing pinned tool: $tool"; exit 1; }
done

python3 - "$reports" <<'PY'
import hashlib, pathlib, shutil, sys
root = pathlib.Path.cwd()
reports = pathlib.Path(sys.argv[1])
paths = {root / p for p in ('native/p2p/CMakeLists.txt', 'native/p2p/dependencies.lock.json',
         'native/p2p/prepare.py', 'native/p2p/ci-android.sh', 'native/p2p/build-adapter.ps1',
         'android/wire/src/main/cpp/DataChannelJni.cpp', 'scripts/build-p2p-product.ps1',
         '.github/workflows/checks.yml')}
for directory in ('native/p2p/src', 'native/p2p/include'):
    paths.update(p for p in (root / directory).rglob('*') if p.is_file())
(reports / 'sources.sha256').write_text(''.join(
    f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(root).as_posix()}\n'
    for p in sorted(paths)))
shutil.copyfile(root / 'native/p2p/dependencies.lock.json', reports / 'dependencies.lock.json')
PY
timeout --kill-after=15s 300s python3 native/p2p/prepare.py
"$cmake" --version
cat "$ndk/source.properties"

for abi in arm64-v8a x86_64; do
  build="$root/.local/p2p/ci-android-$abi"
  output="$root/.local/p2p/product-libs/$abi"
  phase="configure-$abi"
  timeout --kill-after=15s 300s "$cmake" -S native/p2p -B "$build" -G Ninja \
    "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" \
    "-DCMAKE_MAKE_PROGRAM=$ninja" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release "-DPOCKET_P2P_DEPS=$root/.local/p2p"
  phase="build-$abi"
  timeout --kill-after=15s "${budget}s" "$cmake" --build "$build" \
    --target pocket-p2p pocket-p2p-jni --parallel "$jobs"
  phase="inspect-$abi"
  mkdir -p "$output"
  for library in libpocket-p2p.so libpocket-p2p-jni.so libdatachannel.so; do
    source="$build/$library"
    [[ "$library" != libdatachannel.so ]] || source="$build/libdatachannel/$library"
    cp -- "$source" "$output/$library"
    timeout --kill-after=5s 30s "$llvm/llvm-strip" --strip-unneeded "$output/$library"
    "$llvm/llvm-readelf" --file-header --dynamic --dyn-syms "$output/$library" > "$reports/$abi-$library.elf.txt"
  done
  python3 - "$abi" "$output" "$reports" <<'PY'
import hashlib, pathlib, re, sys, json
abi, output, reports = sys.argv[1:]
output, reports = pathlib.Path(output), pathlib.Path(reports)
machine = 'AArch64' if abi == 'arm64-v8a' else 'Advanced Micro Devices X86-64'
exports = {'libpocket-p2p.so': ('p2p_create', 'p2p_create_config', 'p2p_start', 'p2p_remote_description',
           'p2p_remote_candidate', 'p2p_read', 'p2p_write', 'p2p_flush', 'p2p_status', 'p2p_close', 'p2p_destroy', 'p2p_get_stats'),
           'libpocket-p2p-jni.so': tuple('Java_dev_codexpocket_wire_DataChannelTransport_native' + method
                                        for method in ('Create', 'Start', 'Description', 'Candidate', 'Read', 'Write', 'Close', 'Destroy')),
           'libdatachannel.so': ('rtcCreatePeerConnection',)}
items = []
for library, required in exports.items():
    path = output / library
    text = (reports / f'{abi}-{library}.elf.txt').read_text()
    if not re.search(r'Class:\s+ELF64', text) or not re.search(r'Machine:\s+' + re.escape(machine), text):
        raise RuntimeError(f'Wrong ELF ABI: {abi}/{library}')
    for symbol in required:
        if not re.search(r'\bGLOBAL\s+DEFAULT\s+(?!UND\b)\S+\s+' + re.escape(symbol) + r'(?:\s|$)', text):
            raise RuntimeError(f'Missing defined dynamic export: {library}/{symbol}')
    needed = re.findall(r'NEEDED[^\n]*\[([^\]]+)\]', text)
    missing = set(needed) - set(exports) - {'libc.so', 'libm.so', 'libdl.so', 'liblog.so'}
    if missing:
        raise RuntimeError(f'Unpackaged runtime dependency: {library}/{sorted(missing)}')
    items.append(dict(abi=abi, library=library, bytes=path.stat().st_size,
                      sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
for library, dependency in (('libpocket-p2p.so', 'libdatachannel.so'), ('libpocket-p2p-jni.so', 'libpocket-p2p.so')):
    text = (reports / f'{abi}-{library}.elf.txt').read_text()
    if not re.search(r'NEEDED[^\n]*\[' + re.escape(dependency) + r'\]', text):
        raise RuntimeError(f'Missing runtime dependency: {library}/{dependency}')
(reports / f'{abi}.json').write_text(json.dumps(items, indent=2) + '\n')
print(f'{abi}: 3 stripped libraries; ELF ABI, JNI/C exports and runtime dependencies verified')
PY
done
phase=complete
