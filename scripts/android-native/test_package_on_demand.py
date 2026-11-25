import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from linux_environment import Image
from package_on_demand import stage, BOOTSTRAP, RUNTIME


class OnDemandPackageTests(unittest.TestCase):
    def fixture(self, source):
        image = Image()
        image.files['usr/bin/bash'] = (b'fixture shell', 0o755)
        image.links['bin'] = 'usr/bin'
        image.write(source / 'assets/phone-linux/x86_64', 'x86_64', 'fixture', {})
        native = source / 'jniLibs/x86_64'
        native.mkdir(parents=True)
        for name in BOOTSTRAP + RUNTIME:
            data = b'\x7fELF\x02\x01' + b'\x00' * 12 + struct.pack('<H', 62)
            (native / name).write_bytes(data if name != 'libpocket-runtime-manifest.so' else b'{"schema":1}')

    def test_complete_environment_is_downloaded_and_only_bootstrap_is_bundled(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root / 'source')
            result = stage(root / 'source', root / 'apk', root / 'downloads', 'rc55', 'https://example.com/releases/download/v55', ['x86_64'])
            manifest = json.loads((root / 'apk/assets/phone-linux/x86_64/manifest.json').read_text())
            self.assertEqual(set(BOOTSTRAP), {p.name for p in (root / 'apk/jniLibs/x86_64').iterdir()})
            self.assertFalse((root / 'apk/assets/phone-linux/x86_64/rootfs.zip').exists())
            self.assertEqual([{'path': 'rootfs/bin', 'target': 'usr/bin'}], manifest['links'])
            self.assertIn('runtime/libcodex.so', manifest['executables'])
            with zipfile.ZipFile(result[0]['archive']) as archive:
                self.assertEqual({'rootfs/usr/bin/bash'} | {'runtime/' + name for name in RUNTIME}, set(archive.namelist()))
                self.assertEqual(b'{"schema":1}', archive.read('runtime/libpocket-runtime-manifest.so'))
            self.assertEqual(6, manifest['entryCount'])

    def test_rejects_wrong_abi_before_writing_any_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root / 'source')
            (root / 'source/jniLibs/x86_64/libcodex.so').write_bytes(b'wrong architecture')
            with self.assertRaises(ValueError):
                stage(root / 'source', root / 'apk', root / 'downloads', 'rc55', 'https://example.com/v55', ['x86_64'])
            self.assertFalse((root / 'apk').exists())

    def test_rejects_untrusted_download_base(self):
        for base in ('http://example.com/v55', 'https://secret@example.com/v55', 'https://example.com/v55?token=secret'):
            with self.assertRaises(ValueError):
                stage(Path('unused'), Path('unused'), Path('unused'), 'rc55', base, ['x86_64'])


if __name__ == '__main__':
    unittest.main()
