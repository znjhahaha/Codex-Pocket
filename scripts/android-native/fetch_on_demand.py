"""Reuse a pinned release APK's phone bootstrap; print the new Gradle staging path.

The environment stays at its original release URL and revision, including its
original native version. No phone Linux compilation or environment download.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import struct
import tempfile
import urllib.request
import zipfile

from package_on_demand import BOOTSTRAP, MACHINES, RUNTIME

LOCK = json.loads(Path(__file__).with_name('on-demand.lock.json').read_text(encoding='utf-8'))
MAX_ENTRY_BYTES = 16 * 1024 * 1024
MAX_STAGE_BYTES = 32 * 1024 * 1024


def open_download(url: str):
    request = urllib.request.Request(url, headers={'User-Agent': 'Codex-Pocket-release-bootstrap'})
    return urllib.request.urlopen(request, timeout=60)


def verify_apk(apk, pin: dict) -> None:
    apk.seek(0, 2)
    size = apk.tell()
    apk.seek(0)
    if size != pin['bytes'] or hashlib.file_digest(apk, 'sha256').hexdigest() != pin['sha256']:
        raise ValueError('Source differs from the pinned APK')
    apk.seek(0)


def download(pin: dict, destination: Path) -> None:
    with open_download(pin['url']) as response, destination.open('xb') as output:
        size = 0
        while block := response.read(1024 * 1024):
            size += len(block)
            if size > pin['bytes']:
                raise ValueError('Download exceeds the pinned APK size')
            output.write(block)


def checked_entries(archive: zipfile.ZipFile, lock: dict) -> dict[str, zipfile.ZipInfo]:
    abi = lock['abi']
    metadata = f'assets/phone-linux/{abi}/'
    wanted = {f'lib/{abi}/{name}': f'jniLibs/{abi}/{name}' for name in BOOTSTRAP}
    wanted.update({metadata + name: metadata + name for name in ('manifest.json', 'download.json')})
    wanted.update({metadata + 'licenses/' + name: metadata + 'licenses/' + name for name in lock['licenses']})
    found = {}
    seen = set()
    total = 0
    for entry in archive.infolist():
        name = entry.filename
        path = PurePosixPath(name)
        if '\\' in name or ':' in name or name.startswith('/') or '..' in path.parts or \
                path.as_posix() != name.rstrip('/'):
            raise ValueError(f'Unsafe APK entry path: {name}')
        if name in seen:
            raise ValueError(f'APK has duplicate entry: {name}')
        seen.add(name)
        if name in {f'lib/{abi}/{runtime}' for runtime in RUNTIME}:
            raise ValueError(f'APK contains bundled downloaded runtime: {name}')
        if entry.is_dir():
            continue
        if name.startswith(metadata) and name not in wanted:
            raise ValueError(f'Unexpected phone metadata: {name}')
        if name not in wanted:
            continue
        kind = stat.S_IFMT(entry.external_attr >> 16)
        if kind not in (0, stat.S_IFREG) or entry.flag_bits & 1:
            raise ValueError(f'APK entry must be a regular unencrypted file: {name}')
        total += entry.file_size
        if not 0 < entry.file_size <= MAX_ENTRY_BYTES or total > MAX_STAGE_BYTES:
            raise ValueError(f'Unexpected bootstrap or metadata size: {name}')
        found[wanted[name]] = entry
    missing = set(wanted.values()) - set(found)
    if missing:
        raise ValueError(f'APK staging entries missing: {sorted(missing)}')
    manifest = json.loads(archive.read(found[metadata + 'manifest.json']))
    locator = json.loads(archive.read(found[metadata + 'download.json']))
    environment = lock['environment']
    if manifest.get('schema') != 1 or manifest.get('abi') != abi or \
            any(manifest.get(key) != environment[key] for key in
                ('revision', 'rootfsRevision', 'archiveBytes', 'archiveSha256')) or \
            locator.get('schema') != 1 or locator.get('url') != environment['url']:
        raise ValueError('APK environment metadata differs from the pinned environment')
    for name in BOOTSTRAP:
        with archive.open(found[f'jniLibs/{abi}/{name}']) as stream:
            header = stream.read(20)
        if len(header) != 20 or header[:6] != b'\x7fELF\x02\x01' or \
                struct.unpack_from('<H', header, 18)[0] != MACHINES[abi]:
            raise ValueError(f'Bootstrap ELF ABI differs: {name}')
    return found


def stage(lock: dict, output: Path, apk: Path | None = None, abi: str = 'arm64-v8a') -> Path:
    output = output.absolute()
    if output.exists() or output.is_symlink():
        raise ValueError(f'Choose a new staging directory; existing output is preserved: {output}')
    if lock['schema'] != 1 or abi != lock['abi'] or abi not in MACHINES:
        raise ValueError('ABI is not in the reviewed release pin')
    pin = lock['apk']
    expected_url = f"{lock['repository']}/releases/download/{lock['tag']}/{pin['name']}"
    if not expected_url.startswith('https://github.com/') or pin['url'] != expected_url or \
            not re.fullmatch(r'[a-f0-9]{64}', pin['sha256']) or not 0 < pin['bytes'] <= 256 * 1024 * 1024:
        raise ValueError('Invalid pinned APK source')
    output.parent.mkdir(parents=True, exist_ok=True)
    # The temporary directory is ours alone. Failures never expose a partial stage.
    with tempfile.TemporaryDirectory(prefix='.phone-bootstrap-', dir=output.parent) as temporary:
        scratch = Path(temporary)
        source = apk if apk is not None else scratch / 'release.apk'
        if apk is None:
            download(pin, source)
        with source.open('rb') as stream:
            verify_apk(stream, pin)
            with zipfile.ZipFile(stream) as archive:
                entries = checked_entries(archive, lock)
                staged = scratch / 'staging'
                for relative, entry in entries.items():
                    target = staged / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(entry) as incoming, target.open('xb') as outgoing:
                        shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
                # Reserve the destination exclusively, including if another process made it.
                output.mkdir()
                try:
                    for child in staged.iterdir():
                        child.rename(output / child.name)
                except Exception:
                    shutil.rmtree(output)
                    raise
    return output.resolve()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--abi', default='arm64-v8a', choices=[LOCK['abi']])
    parser.add_argument('--apk', type=Path, help='Use a retained APK with the same reviewed digest; do not download')
    args = parser.parse_args()
    try:
        result = stage(LOCK, args.output, args.apk, args.abi)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Phone bootstrap not staged: {error}\n')
    print(result)
