"""Stage the statically linked Pocket agent and runtime host as Android native libraries.

An Android package installs executables only as lib*.so, so the agent, its
runtime host and the manifest that binds them are staged under those names.
Nothing is compiled or executed here, and existing files are never replaced.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import shutil
import struct
from pathlib import Path

spec = importlib.util.spec_from_file_location("fetch_official", Path(__file__).with_name("fetch_official.py"))
official = importlib.util.module_from_spec(spec)
spec.loader.exec_module(official)

FILES = {"libpocket-agent.so": "pocket-agent", "libpocket-runtime-host.so": "pocket-runtime-host"}
MANIFEST = "libpocket-runtime-manifest.so"


def stage(target_dir: Path, output: Path, version: str, abis: list[str], lock: dict = official.LOCK) -> dict:
    if not version or len(version) > 96 or not all(c.isalnum() or c in "._-" for c in version):
        raise ValueError("Version must be 1-96 letters, digits, dots, dashes or underscores")
    unknown = [abi for abi in abis if abi not in lock["abis"]]
    if unknown or not abis:
        raise ValueError(f"Not a supported ABI: {', '.join(unknown) or 'none selected'}")
    plan = {}
    for abi in abis:
        target = lock["abis"][abi]
        directory = output / "jniLibs" / abi
        taken = [name for name in (*FILES, MANIFEST) if (directory / name).exists()]
        if taken:
            raise ValueError(f"Already staged and preserved: {directory / taken[0]}")
        entries = []
        for packaged, built in FILES.items():
            source = target_dir / target["triple"] / "release" / built
            official.inspect_static_elf(source, target["machine"], target["require_16k_pages"])
            data = source.read_bytes()
            entries.append((packaged, source, {"path": packaged, "bytes": len(data),
                                               "sha256": hashlib.sha256(data).hexdigest()}))
        plan[abi] = (directory, entries)
    # Every ABI is validated before anything is written.
    result = {}
    for abi, (directory, entries) in plan.items():
        directory.mkdir(parents=True, exist_ok=True)
        for packaged, source, _ in entries:
            shutil.copy2(source, directory / packaged)
        manifest = {"schema": 1, "product": "Codex Pocket", "version": version,
                    "architecture": lock["abis"][abi]["triple"].split("-")[0],
                    "files": [entry for _, _, entry in entries]}
        (directory / MANIFEST).write_bytes(json.dumps(manifest, separators=(",", ":")).encode())
        result[abi] = manifest
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-dir", type=Path, default=Path("E:/pocket-build/pocket-android-target"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--abis", default="arm64-v8a,x86_64")
    args = parser.parse_args()
    try:
        stage(args.target_dir, args.output, args.version, args.abis.split(","))
    except (ValueError, OSError, KeyError, struct.error) as error:
        parser.exit(1, f"Agent not staged: {error}\n")
    print(f"Agent staged at {args.output}")
