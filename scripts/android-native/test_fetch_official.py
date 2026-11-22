"""Exercise the official-release gate with synthetic signed fixtures; nothing is downloaded."""
import base64
import datetime
import hashlib
import importlib.util
import io
import json
import struct
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

spec = importlib.util.spec_from_file_location("fetch_official", Path(__file__).with_name("fetch_official.py"))
official = importlib.util.module_from_spec(spec)
sys.dont_write_bytecode = True
spec.loader.exec_module(official)

SIGNER = "https://github.com/example/codex/.github/workflows/release.yml@refs/tags/v1"
KEY = ec.generate_private_key(ec.SECP256R1())


def elf(machine=183, alignment=65536, interpreter=False, flags=5):
    headers = [struct.pack("<IIQQQQQQ", 1, flags, 0, 0x400000, 0x400000, 256, 256, alignment)]
    if interpreter:
        headers.append(struct.pack("<IIQQQQQQ", 3, 4, 200, 0, 0, 8, 8, 1))
    head = b"\x7fELF\x02\x01\x01" + bytes(9) + struct.pack("<HHIQQQIHHHHHH", 2, machine, 1, 0x400100, 64, 0, 0,
                                                             64, 56, len(headers), 64, 0, 0)
    return (head + b"".join(headers)).ljust(256, b"\0")


def certificate(signer=SIGNER, issuer=official.GITHUB_ACTIONS_ISSUER):
    name = x509.Name([x509.NameAttribute(NameOID.ORGANIZATION_NAME, "sigstore.dev")])
    now = datetime.datetime.now(datetime.timezone.utc)
    return (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(KEY.public_key())
            .serial_number(1).not_valid_before(now).not_valid_after(now + datetime.timedelta(minutes=10))
            .add_extension(x509.SubjectAlternativeName([x509.UniformResourceIdentifier(signer)]), critical=True)
            .add_extension(x509.UnrecognizedExtension(official.FULCIO_OIDC_ISSUER, issuer), critical=False)
            .sign(KEY, hashes.SHA256()))


class OfficialGate(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="official-gate-")
        self.root = Path(self.scratch.name)
        self.cache = self.root / "cache"
        self.cache.mkdir()
        self.lock = {"codex": {"repository": "https://invalid.example/codex", "tag": "v1", "version": "1"},
                     "signer": SIGNER,
                     "abis": {"arm64-v8a": {"triple": "aarch64-unknown-linux-musl", "machine": 183,
                                            "require_16k_pages": True}},
                     "components": {"codex": {"packaged": "libcodex.so", "assets": {}}}}
        self.put(elf())

    def tearDown(self):
        self.scratch.cleanup()

    def put(self, binary, cert=None, signed=None, member="codex-aarch64-unknown-linux-musl", extra=None):
        name = "codex-aarch64-unknown-linux-musl"
        packed = io.BytesIO()
        with tarfile.open(fileobj=packed, mode="w:gz") as tar:
            for entry, data in [(member, binary)] + ([extra] if extra else []):
                info = tarfile.TarInfo(entry)
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        (self.cache / f"{name}.tar.gz").write_bytes(packed.getvalue())
        pem = (cert or certificate()).public_bytes(serialization.Encoding.PEM)
        signature = KEY.sign(binary if signed is None else signed, ec.ECDSA(hashes.SHA256()))
        (self.cache / f"{name}.sigstore").write_text(json.dumps({
            "cert": base64.b64encode(pem).decode(), "base64Signature": base64.b64encode(signature).decode()}))
        self.lock["components"]["codex"]["assets"]["arm64-v8a"] = {
            "archive_sha256": hashlib.sha256(packed.getvalue()).hexdigest(), "archive_bytes": len(packed.getvalue()),
            "binary_sha256": hashlib.sha256(binary).hexdigest(), "binary_bytes": len(binary)}

    def stage(self):
        return official.stage(self.lock, self.cache, self.root / "out", ["codex"], ["arm64-v8a"], offline=True)

    def refused(self, text):
        with self.assertRaisesRegex(ValueError, text):
            self.stage()
        self.assertFalse((self.root / "out").exists())

    def test_verified_release_is_staged_under_its_packaged_name(self):
        manifest = self.stage()
        staged = self.root / "out/jniLibs/arm64-v8a/libcodex.so"
        self.assertEqual(staged.read_bytes(), elf())
        entry = manifest["abis"]["arm64-v8a"]["files"]["libcodex.so"]
        self.assertTrue(entry["static"] and entry["supports_16k_pages"])
        self.assertEqual(entry["signer"], SIGNER)
        with self.assertRaisesRegex(ValueError, "existing output is preserved"):
            self.stage()
        self.assertEqual(staged.read_bytes(), elf())

    def test_changed_archive_or_executable_is_refused(self):
        self.lock["components"]["codex"]["assets"]["arm64-v8a"]["archive_sha256"] = "0" * 64
        self.refused("Archive differs")
        self.put(elf())
        self.lock["components"]["codex"]["assets"]["arm64-v8a"]["binary_sha256"] = "0" * 64
        self.refused("Executable differs")

    def test_other_signer_issuer_or_signature_is_refused(self):
        self.put(elf(), cert=certificate(signer=SIGNER + "x"))
        self.refused("unexpected workflow")
        self.put(elf(), cert=certificate(issuer=b"https://accounts.example"))
        self.refused("not issued to GitHub Actions")
        self.put(elf(), signed=b"another file")
        self.refused("Signature does not match")

    def test_archive_with_other_contents_is_refused(self):
        self.put(elf(), member="../codex-aarch64-unknown-linux-musl")
        self.refused("exactly the pinned executable")
        self.put(elf(), extra=("extra", b"x"))
        self.refused("exactly the pinned executable")

    def test_executable_android_cannot_start_is_refused(self):
        for binary, text in ((elf(interpreter=True), "dynamic loader"), (elf(machine=62), "Wrong ELF"),
                             (elf(alignment=4096), "16-KiB"), (elf(flags=7), "Writable executable")):
            self.put(binary)
            self.refused(text)

    def test_missing_file_is_not_downloaded_offline(self):
        (self.cache / "codex-aarch64-unknown-linux-musl.sigstore").unlink()
        self.refused("downloads are disabled")


if __name__ == "__main__":
    unittest.main()
