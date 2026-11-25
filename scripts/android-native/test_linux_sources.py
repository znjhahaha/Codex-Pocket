import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

from linux_environment import stage_licenses


class LinuxSourceTests(unittest.TestCase):
    def test_every_component_license_and_source_is_delivered(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root); cache = root/'cache'; cache.mkdir()
            with zipfile.ZipFile(cache/'proot.zip', 'w') as archive:
                archive.writestr('proot/COPYING', 'PRoot license')
            with tarfile.open(cache/'talloc.tar.gz', 'w:gz') as archive:
                member = tarfile.TarInfo('talloc/LICENSE'); data = b'Talloc license'; member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            (cache/'GPL.txt').write_text('GPL full text')
            lock = {'abis': {'x86_64': {'sources': []}}, 'sourceArchives': [
                {'name': 'proot.zip', 'licenseFiles': {'proot-COPYING.txt': 'proot/COPYING'}},
                {'name': 'talloc.tar.gz', 'licenseFiles': {'talloc-LICENSE.txt': 'talloc/LICENSE'}}],
                'licenseInputs': [{'name': 'GPL.txt'}]}
            output = root/'out'
            stage_licenses('x86_64', lock, cache, output)
            notices = output/'assets/phone-linux/x86_64/licenses'
            self.assertEqual('PRoot license', (notices/'proot-COPYING.txt').read_text())
            self.assertEqual('Talloc license', (notices/'talloc-LICENSE.txt').read_text())
            self.assertEqual('GPL full text', (notices/'GPL.txt').read_text())
            self.assertEqual((cache/'talloc.tar.gz').read_bytes(), (output/'sources/talloc.tar.gz').read_bytes())
            self.assertEqual(2, len(json.loads((notices/'source-inputs.json').read_text())['sourceArchives']))

    def test_missing_license_is_a_packaging_error(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            with zipfile.ZipFile(root/'proot.zip', 'w') as archive:
                archive.writestr('wrong', 'no license')
            lock = {'abis': {'x86_64': {'sources': []}}, 'sourceArchives': [
                {'name': 'proot.zip', 'licenseFiles': {'proot.txt': 'proot/COPYING'}}]}
            with self.assertRaises((ValueError, KeyError)):
                stage_licenses('x86_64', lock, root, root/'out')


if __name__ == '__main__': unittest.main()
