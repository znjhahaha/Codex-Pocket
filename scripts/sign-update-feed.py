"""Sign a verified Pocket Windows inventory for the desktop updater.

Requires cryptography. The existing private key is read from a local PEM file;
this command never generates/replaces a signing identity or publishes assets.
"""
import argparse
import base64
import hashlib
import json
import time
from pathlib import Path
from urllib.parse import urlparse, quote
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def sign(release: Path, key_file: Path, trust_file: Path, asset_base: str, package: Path | None = None) -> bytes:
    trust = json.loads(trust_file.read_text(encoding="utf-8-sig"))
    origin = urlparse(asset_base)
    if origin.scheme != "https" or origin.hostname not in trust["allowedHosts"] or origin.username or origin.password or origin.query or origin.fragment:
        raise ValueError("Asset base is not a trusted HTTPS release URL")
    key = serialization.load_pem_private_key(key_file.read_bytes(), password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise ValueError("An Ed25519 key is required")
    public = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    if base64.b64encode(public).decode() != trust["publicKey"]:
        raise ValueError("Private key does not match packaged update trust")
    windows = (release / "windows").resolve()
    manifest = (windows / "windows-manifest.json").read_bytes()
    inventory = json.loads(manifest)
    locations = []
    for entry in inventory["files"]:
        relative = Path(entry["path"])
        path = (windows / relative).resolve()
        if relative.is_absolute() or not path.is_relative_to(windows) or path.is_symlink():
            raise ValueError("Invalid inventory path")
        data = path.read_bytes()
        if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError("Release inventory does not match current files")
        locations.append({"path": entry["path"], "url": asset_base.rstrip("/") + "/" + quote(entry["path"], safe="")})
    now = int(time.time())
    offer = {"schema": 1, "version": inventory["version"], "publishedAt": now,
        "expiresAt": now + 30 * 86400, "windowsManifest": base64.b64encode(manifest).decode(),
        "files": locations}
    if package is not None:
        if not package.name.endswith("-full.nupkg") or package.is_symlink():
            raise ValueError("A regular Velopack full package is required")
        data = package.read_bytes()
        offer["package"] = {"url": asset_base.rstrip("/") + "/" + quote(package.name, safe=""),
            "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    payload = json.dumps(offer, separators=(",", ":"), ensure_ascii=False).encode()
    signature = key.sign(payload)
    key.public_key().verify(signature, payload)
    return json.dumps({"payload": base64.b64encode(payload).decode(), "signature": base64.b64encode(signature).decode()}, indent=2).encode()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", required=True, type=Path)
    parser.add_argument("--key", required=True, type=Path)
    parser.add_argument("--trust", required=True, type=Path)
    parser.add_argument("--asset-base", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--package", type=Path)
    args = parser.parse_args()
    # Exclusive creation preserves earlier signed offers and their expiry evidence.
    result = sign(args.release, args.key, args.trust, args.asset_base, args.package)
    with args.output.open("xb") as output:
        output.write(result)
    print("Signed and verified Windows update inventory; no assets were published.")
