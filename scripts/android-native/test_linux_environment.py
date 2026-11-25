import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile
from linux_environment import Image, safe_path, archive_members


class LinuxImageTests(unittest.TestCase):
    def test_paths_cannot_escape_image(self):
        for path in ('../escape', '/absolute', 'a/../../escape', 'a\\escape', 'C:/escape'):
            with self.assertRaises(ValueError, msg=path): safe_path(path)

    def test_tar_links_are_metadata_and_hardlinks_are_materialized(self):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w') as archive:
            entry = tarfile.TarInfo('usr/bin/tool'); entry.size = 2; entry.mode = 0o755
            archive.addfile(entry, io.BytesIO(b'OK'))
            alias = tarfile.TarInfo('bin'); alias.type = tarfile.SYMTYPE; alias.linkname = '/usr/bin'
            archive.addfile(alias)
            alias = tarfile.TarInfo('usr/bin/copy'); alias.type = tarfile.LNKTYPE; alias.linkname = 'usr/bin/tool'
            archive.addfile(alias)
        image = Image(); image.add_tar(data.getvalue())
        with tempfile.TemporaryDirectory() as tmp:
            manifest = image.write(Path(tmp), 'x86_64', 'test', {})
            self.assertIn({'path':'bin','target':'usr/bin'}, manifest['links'])
            self.assertIn('usr/bin/tool', manifest['executables'])
            with zipfile.ZipFile(Path(tmp)/'rootfs.zip') as archive:
                self.assertEqual(b'OK', archive.read('usr/bin/copy'))
                self.assertNotIn('bin', archive.namelist())
            self.assertEqual(4, manifest['unpackedBytes'])

    def test_untrusted_tar_link_cannot_escape_root(self):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w') as archive:
            link = tarfile.TarInfo('bin/bad'); link.type=tarfile.SYMTYPE; link.linkname='../../outside'
            archive.addfile(link)
        with self.assertRaises(ValueError): Image().add_tar(data.getvalue())

    def test_files_under_linked_parent_are_not_written_twice(self):
        image=Image(); image.links['bin']='usr/bin'; image.files['bin/tool']=(b'OK',0o755)
        with tempfile.TemporaryDirectory() as tmp:
            image.write(Path(tmp),'x86_64','test',{})
            with zipfile.ZipFile(Path(tmp)/'rootfs.zip') as archive:
                self.assertEqual(['usr/bin/tool'],archive.namelist())


if __name__ == '__main__': unittest.main()
