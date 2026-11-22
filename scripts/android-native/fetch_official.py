"""Fetch OpenAI's published static Linux Codex executables and stage them for jniLibs.

Nothing is compiled, patched, stripped, or executed here. Every file must match
the digests pinned in official.lock.json and carry the release workflow's
signature before any output is created.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import shutil
import struct
import tarfile
import urllib.request
from pathlib import Path

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils

PAGE = 16384
FULCIO_OIDC_ISSUER = x509.ObjectIdentifier("1.3.6.1.4.1.57264.1.1")
GITHUB_ACTIONS_ISSUER = b"https://token.actions.githubusercontent.com"
LOCK = json.loads(Path(__file__).with_name("official.lock.json").read_text())


def sha256(path: Path) -> tuple[str, bytes, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while block := stream.read(1 << 20):
            digest.update(block)
            size += len(block)
    return digest.hexdigest(), digest.digest(), size


def verify_signature(bundle_path: Path, digest: bytes, signer: str) -> None:
    """Checks the signature and the signing workflow named by the certificate.

    The certificate chain and transparency-log entry are not checked; the pinned
    digests are what bind a file to this repository's reviewed inputs.
    """
    if not 0 < bundle_path.stat().st_size <= 65536:
        raise ValueError(f"Unexpected signature bundle size: {bundle_path}")
    bundle = json.loads(bundle_path.read_text())
    certificate = x509.load_pem_x509_certificate(base64.b64decode(bundle["cert"]))
    names = certificate.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    if names.get_values_for_type(x509.UniformResourceIdentifier) != [signer]:
        raise ValueError(f"Signed by an unexpected workflow: {bundle_path}")
    if GITHUB_ACTIONS_ISSUER not in certificate.extensions.get_extension_for_oid(FULCIO_OIDC_ISSUER).value.value:
        raise ValueError(f"Signing identity was not issued to GitHub Actions: {bundle_path}")
    key = certificate.public_key()
    if not isinstance(key, ec.EllipticCurvePublicKey):
        raise ValueError(f"Unexpected signing key type: {bundle_path}")
    try:
        key.verify(base64.b64decode(bundle["base64Signature"]), digest, ec.ECDSA(utils.Prehashed(hashes.SHA256())))
    except InvalidSignature:
        raise ValueError(f"Signature does not match the executable: {bundle_path}") from None


def inspect_static_elf(path: Path, machine: int, require_16k: bool) -> dict:
    with path.open("rb") as stream:
        head = stream.read(64)
        if len(head) < 64 or head[:7] != b"\x7fELF\x02\x01\x01":
            raise ValueError(f"Not a little-endian ELF64 file: {path}")
        fields = struct.unpack_from("<HHIQQQIHHHHHH", head, 16)
        kind, actual_machine, _, entry, phoff = fields[:5]
        phsize, count = fields[8:10]
        if kind not in (2, 3) or actual_machine != machine or phsize != 56 or not entry or not 0 < count <= 64:
            raise ValueError(f"Wrong ELF type, machine, or program headers: {path}")
        stream.seek(phoff)
        table = stream.read(phsize * count)
        size = stream.seek(0, 2)
    if len(table) != phsize * count:
        raise ValueError(f"Truncated ELF program headers: {path}")
    headers = [struct.unpack_from("<IIQQQQQQ", table, i * phsize) for i in range(count)]
    loads = [p for p in headers if p[0] == 1]
    if not loads:
        raise ValueError(f"Missing load segments: {path}")
    if any(p[0] == 3 for p in headers):
        raise ValueError(f"Needs a dynamic loader that Android does not provide: {path}")
    for _, flags, offset, address, _, file_size, memory, _ in loads:
        if file_size > memory or offset + file_size > size:
            raise ValueError(f"Invalid load segment: {path}")
        if flags & 3 == 3:
            raise ValueError(f"Writable executable load segment: {path}")
    alignment = min(p[7] for p in loads)
    supports_16k = alignment >= PAGE and all(p[2] % PAGE == p[3] % PAGE for p in loads)
    if require_16k and not supports_16k:
        raise ValueError(f"Not 16-KiB aligned: {path}")
    return {"machine": actual_machine, "elf_type": "ET_EXEC" if kind == 2 else "ET_DYN", "static": True,
            "load_alignment": alignment, "supports_16k_pages": supports_16k, "bytes": size}


def download(url: str, destination: Path, limit: int) -> None:
    partial = destination.with_name(destination.name + ".part")
    with urllib.request.urlopen(url, timeout=60) as response, partial.open("wb") as output:
        received = 0
        while block := response.read(1 << 20):
            received += len(block)
            if received > limit:
                raise ValueError(f"Download is larger than the pinned size: {url}")
            output.write(block)
    partial.replace(destination)


def extract(archive: Path, member_name: str, destination: Path, expected_bytes: int) -> None:
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        if len(members) != 1 or members[0].name != member_name or not members[0].isfile() \
                or members[0].size != expected_bytes:
            raise ValueError(f"Archive does not hold exactly the pinned executable: {archive}")
        source = tar.extractfile(members[0])
        partial = destination.with_name(destination.name + ".part")
        with partial.open("wb") as output:
            shutil.copyfileobj(source, output, 1 << 20)
        partial.replace(destination)


def obtain(lock: dict, component: str, abi: str, cache: Path, offline: bool) -> tuple[Path, dict]:
    target = lock["abis"][abi]
    pin = lock["components"][component]["assets"][abi]
    name = f"{component}-{target['triple']}"
    base = f"{lock['codex']['repository']}/releases/download/{lock['codex']['tag']}/"
    archive, bundle, binary = cache / f"{name}.tar.gz", cache / f"{name}.sigstore", cache / name
    for path, limit in ((archive, pin["archive_bytes"]), (bundle, 65536)):
        if path.exists():
            continue
        if offline:
            raise ValueError(f"Not in the cache and downloads are disabled: {path}")
        download(base + path.name, path, limit)
    archive_hash, _, archive_bytes = sha256(archive)
    if (archive_hash, archive_bytes) != (pin["archive_sha256"], pin["archive_bytes"]):
        raise ValueError(f"Archive differs from the pinned release asset: {archive}")
    extract(archive, name, binary, pin["binary_bytes"])
    binary_hash, binary_digest, binary_bytes = sha256(binary)
    if (binary_hash, binary_bytes) != (pin["binary_sha256"], pin["binary_bytes"]):
        raise ValueError(f"Executable differs from the pinned release asset: {binary}")
    verify_signature(bundle, binary_digest, lock["signer"])
    info = inspect_static_elf(binary, target["machine"], target["require_16k_pages"])
    info.update(asset=archive.name, sha256=binary_hash, signer=lock["signer"])
    return binary, info


def stage(lock: dict, cache: Path, output: Path, components: list[str], abis: list[str], offline: bool) -> dict:
    if output.exists():
        raise ValueError(f"Choose a new staging directory; existing output is preserved: {output}")
    unknown = [c for c in components if c not in lock["components"]] + [a for a in abis if a not in lock["abis"]]
    if unknown or not components or not abis:
        raise ValueError(f"Not a pinned component or ABI: {', '.join(unknown) or 'none selected'}")
    cache.mkdir(parents=True, exist_ok=True)
    # Validate every selected file before creating output.
    plan = [(abi, component, *obtain(lock, component, abi, cache, offline)) for abi in abis for component in components]
    manifest = {"schema": 1, "source": "official-release", "codex": lock["codex"],
                "location": "applicationInfo.nativeLibraryDir", "abis": {abi: {"files": {}} for abi in abis}}
    output.mkdir(parents=True)
    for abi, component, binary, info in plan:
        directory = output / "jniLibs" / abi
        directory.mkdir(parents=True, exist_ok=True)
        packaged = lock["components"][component]["packaged"]
        shutil.copy2(binary, directory / packaged)
        manifest["abis"][abi]["files"][packaged] = {"component": component, **info}
    (output / "official-runtime-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path("E:/pocket-build/official"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--components", default="codex")
    parser.add_argument("--abis", default="arm64-v8a,x86_64")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    try:
        stage(LOCK, args.cache, args.output, args.components.split(","), args.abis.split(","), args.offline)
    except (ValueError, OSError, KeyError, struct.error, tarfile.TarError, x509.ExtensionNotFound) as error:
        parser.exit(1, f"Official runtime not staged: {error}\n")
    print(f"Verified official runtime staged at {args.output}")
