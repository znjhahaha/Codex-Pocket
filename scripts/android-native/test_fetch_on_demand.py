"""Pinned APK staging fixtures; no Android build, device or external download."""
import copy
import hashlib
import io
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

import fetch_on_demand as fetch


class FetchOnDemandTests(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.root = Path(self.scratch.name)
        self.apk = self.root / 'retained.apk'
        self.output = self.root / 'stage with spaces'
        self.lock = copy.deepcopy(fetch.LOCK)
        self.metadata = 'assets/phone-linux/arm64-v8a/'
        self.entries = {f'lib/arm64-v8a/{name}': b'\x7fELF\x02\x01' + bytes(12) + struct.pack('<H', 183)
                        for name in fetch.BOOTSTRAP}
        self.entries[self.metadata + 'manifest.json'] = json.dumps({
            'schema': 1, 'abi': 'arm64-v8a',
            **{key: self.lock['environment'][key] for key in
               ('revision', 'rootfsRevision', 'archiveBytes', 'archiveSha256')},
            'entryCount': 10, 'unpackedBytes': 1000,
        }).encode()
        self.entries[self.metadata + 'download.json'] = json.dumps({
            'schema': 1, 'url': self.lock['environment']['url']}).encode()
        for name in self.lock['licenses']:
            self.entries[self.metadata + 'licenses/' + name] = b'original license'
        self.entries['classes.dex'] = b'application bytecode'
        self.entries['lib/arm64-v8a/libpocket-p2p.so'] = b'build separately'
        self.entries['assets/phone-linux/x86_64/manifest.json'] = b'other ABI'
        self.pack()

    def tearDown(self):
        self.scratch.cleanup()

    def pack(self, duplicate=None, symlink=None):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.apk, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                for name, data in self.entries.items():
                    info = zipfile.ZipInfo(name)
                    if name == symlink:
                        info.create_system = 3
                        info.external_attr = (stat.S_IFLNK | 0o777) << 16
                    archive.writestr(info, data)
                if duplicate:
                    archive.writestr(duplicate, self.entries[duplicate])
        self.lock['apk']['bytes'] = self.apk.stat().st_size
        self.lock['apk']['sha256'] = hashlib.sha256(self.apk.read_bytes()).hexdigest()

    def stage(self):
        return fetch.stage(self.lock, self.output, self.apk)

    def refused(self, reason):
        with self.assertRaisesRegex(ValueError, reason):
            self.stage()
        self.assertFalse(self.output.exists())

    def test_only_bootstrap_and_original_metadata_are_staged(self):
        self.assertEqual(self.output.resolve(), self.stage())
        expected = {f'jniLibs/arm64-v8a/{name}' for name in fetch.BOOTSTRAP}
        expected |= {name for name in self.entries if name.startswith(self.metadata)}
        self.assertEqual(expected, {p.relative_to(self.output).as_posix() for p in self.output.rglob('*') if p.is_file()})
        for name in self.entries:
            if name.startswith(self.metadata):
                self.assertEqual(self.entries[name], (self.output / name).read_bytes())
        self.assertEqual('0.7.1-rc.20261125.53', self.lock['environment']['nativeVersion'])
        with self.assertRaisesRegex(ValueError, 'existing output'):
            self.stage()

    def test_rejects_changed_source_before_creating_output(self):
        self.lock['apk']['sha256'] = '0' * 64
        self.refused('pinned APK')
        self.pack()
        self.lock['apk']['bytes'] += 1
        self.refused('pinned APK')

    def test_rejects_missing_duplicate_and_symbolic_bootstrap(self):
        name = 'lib/arm64-v8a/libproot.so'
        self.pack(duplicate=name)
        self.refused('duplicate')
        self.pack(symlink=name)
        self.refused('regular')
        del self.entries[name]
        self.pack()
        self.refused('missing')

    def test_rejects_unsafe_paths_and_wrong_elf_abi(self):
        self.entries[self.metadata + 'licenses/../escape'] = b'escape'
        self.pack()
        self.refused('path')
        del self.entries[self.metadata + 'licenses/../escape']
        self.entries['lib/arm64-v8a/libproot.so'] = b'\x7fELF\x02\x01' + bytes(12) + struct.pack('<H', 62)
        self.pack()
        self.refused('ELF')

    def test_rejects_bundled_environment_or_runtime(self):
        for name in (self.metadata + 'rootfs.zip', 'lib/arm64-v8a/libcodex.so'):
            with self.subTest(name=name):
                self.entries[name] = b'not part of bootstrap'
                self.pack()
                self.refused('bundled|Unexpected')
                del self.entries[name]

    def test_rejects_missing_license_and_changed_environment_identity(self):
        name = self.metadata + 'licenses/Codex-LICENSE.txt'
        del self.entries[name]
        self.pack()
        self.refused('missing')
        self.entries[name] = b'license'
        manifest = json.loads(self.entries[self.metadata + 'manifest.json'])
        manifest['revision'] = 'renamed-rc56'
        self.entries[self.metadata + 'manifest.json'] = json.dumps(manifest).encode()
        self.pack()
        self.refused('environment')

    def test_download_uses_pinned_url_and_verifies_exact_bytes(self):
        response = io.BytesIO(self.apk.read_bytes())
        with patch.object(fetch, 'open_download', return_value=response) as download:
            self.assertEqual(self.output.resolve(), fetch.stage(self.lock, self.output))
            download.assert_called_once_with(self.lock['apk']['url'])

    def test_failed_or_oversized_download_leaves_no_stage(self):
        for data in (b'truncated', self.apk.read_bytes() + b'extra'):
            with self.subTest(size=len(data)):
                with patch.object(fetch, 'open_download', return_value=io.BytesIO(data)):
                    with self.assertRaisesRegex(ValueError, 'pinned APK'):
                        fetch.stage(self.lock, self.output)
                self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
