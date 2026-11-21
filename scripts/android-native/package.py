"""Validate both genuine build outputs, then stage executable ELF files for jniLibs.

No binary execution, stripping, installation, or APK mutation happens here.
"""
from __future__ import annotations

import argparse
import json
import shutil
import struct
from pathlib import Path

PAGE = 16384
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}
INPUTS = json.loads(Path(__file__).with_name("inputs.lock.json").read_text())


def inspect_elf(path: Path, machine: int, executable: bool) -> dict:
    data = path.read_bytes()
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError(f"Not a little-endian ELF64 file: {path}")
    header = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    kind, actual_machine, _, entry, phoff = header[:5]
    phsize, count = header[8:10]
    if kind != 3 or actual_machine != machine or phsize != 56:
        raise ValueError(f"Wrong ELF type, machine, or program headers: {path}")
    if phoff + phsize * count > len(data):
        raise ValueError(f"Truncated ELF program headers: {path}")
    headers = [struct.unpack_from("<IIQQQQQQ", data, phoff + i * phsize) for i in range(count)]
    loads = [p for p in headers if p[0] == 1]
    if not loads:
        raise ValueError(f"Missing load segments: {path}")
    for _, flags, offset, address, _, size, memory, alignment in loads:
        if size > memory or offset + size > len(data):
            raise ValueError(f"Invalid load segment: {path}")
        if alignment < PAGE or offset % PAGE != address % PAGE:
            raise ValueError(f"Not 16-KiB aligned: {path}")
        if flags & 3 == 3:
            raise ValueError(f"Writable executable load segment: {path}")
    for relro in (p for p in headers if p[0] == 0x6474E552):
        end = relro[3] + relro[6]
        rounded = (end + PAGE - 1) // PAGE * PAGE
        if any(p[1] & 2 and p[3] + p[6] > end and p[3] < rounded for p in loads):
            raise ValueError(f"RELRO shares a 16-KiB page with writable data: {path}")
    interpreters = [data[p[2]:p[2] + p[5]].rstrip(b"\0").decode() for p in headers if p[0] == 3]
    if executable and (not entry or interpreters != ["/system/bin/linker64"]):
        raise ValueError(f"Not an Android PIE executable: {path}")
    if not executable and interpreters:
        raise ValueError(f"Expected a shared dependency, found an executable: {path}")
    dynamic = {}
    needed = []
    for segment in (p for p in headers if p[0] == 2):
        for position in range(segment[2], segment[2] + segment[5], 16):
            tag, value = struct.unpack_from("<qQ", data, position)
            if tag == 0:
                break
            if tag == 1:
                needed.append(value)
            dynamic[tag] = value
    dependencies = []
    if needed:
        strings = dynamic[5]
        segment = next(p for p in loads if p[3] <= strings < p[3] + p[5])
        base = segment[2] + strings - segment[3]
        for offset in needed:
            start = base + offset
            name = data[start:data.index(b"\0", start)].decode("utf-8")
            if Path(name).name != name or "/" in name or "\\" in name:
                raise ValueError(f"Non-portable dynamic dependency {name}: {path}")
            dependencies.append(name)
    return {"machine": actual_machine, "elf_type": "ET_DYN", "interpreter": interpreters[0] if interpreters else None,
            "load_alignment": min(p[7] for p in loads), "needed": sorted(set(dependencies)), "bytes": len(data)}


def stage(target_dir: Path, output: Path, profile: str, library_dir: Path | None) -> dict:
    if output.exists():
        raise ValueError(f"Choose a new staging directory; existing output is preserved: {output}")
    plan = []
    for abi, target in INPUTS["targets"].items():
        receipt_path = target_dir / "android-native-builds" / f"{abi}-{profile}.json"
        receipt = json.loads(receipt_path.read_text(encoding="utf-8-sig"))
        for key, expected in {"codex_commit": INPUTS["codex"]["commit"], "target": target["rust"],
                              "ndk_version": INPUTS["ndk_version"], "min_sdk": INPUTS["min_sdk"],
                              "rust_toolchain": INPUTS["rust_toolchain"], "source_patches": INPUTS["source_patches"],
                              "profile": profile, "component": "all", "status": "built"}.items():
            if receipt.get(key) != expected:
                raise ValueError(f"Build receipt {key} does not match pinned input: {receipt_path}")
        files = {}
        for binary, packaged in INPUTS["binaries"].items():
            source = target_dir / target["rust"] / profile / binary
            files[packaged] = (source, inspect_elf(source, target["machine"], True))
        pending = [dep for _, info in files.values() for dep in info["needed"] if dep not in SYSTEM_LIBS]
        while pending:
            dependency = pending.pop()
            if dependency in files:
                continue
            if library_dir is None:
                raise ValueError(f"{abi} requires {dependency}; provide --library-dir with per-ABI dependencies")
            source = library_dir / abi / dependency
            info = inspect_elf(source, target["machine"], False)
            files[dependency] = (source, info)
            pending.extend(dep for dep in info["needed"] if dep not in SYSTEM_LIBS)
        plan.append((abi, receipt, files))
    # Validate the entire four-binary set and dependency closure before creating output.
    manifest = {"schema": 1, "codex": INPUTS["codex"], "v8": INPUTS["v8"],
                "min_sdk": INPUTS["min_sdk"], "target_sdk": INPUTS["target_sdk"],
                "launch": {"program": "libcodex-app-server.so", "arguments": ["--listen", "stdio://"],
                           "helper": "libcodex-code-mode-host.so", "location": "applicationInfo.nativeLibraryDir"},
                "abis": {}}
    output.mkdir(parents=True)
    for abi, receipt, files in plan:
        directory = output / "jniLibs" / abi
        directory.mkdir(parents=True)
        manifest["abis"][abi] = {"build": receipt, "files": {}}
        for name, (source, info) in files.items():
            shutil.copy2(source, directory / name)
            manifest["abis"][abi]["files"][name] = info
    (output / "runtime-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-dir", type=Path, default=Path("E:/pocket-build/codex-target"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", choices=["debug", "release"], default="release")
    parser.add_argument("--library-dir", type=Path)
    args = parser.parse_args()
    try:
        stage(args.target_dir, args.output, args.profile, args.library_dir)
    except (ValueError, OSError, KeyError, StopIteration, struct.error) as error:
        parser.exit(1, f"Native package not staged: {error}\n")
    print(f"Validated native runtime staged at {args.output}")
