"""Split a staged phone runtime into an APK bootstrap and pinned HTTPS downloads."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
from urllib.parse import urlsplit
import zipfile

from linux_environment import safe_path, link_target

BOOTSTRAP = ('libproot.so', 'libproot-loader.so', 'libtalloc.so', 'libandroid-shmem.so')
RUNTIME = ('libcodex.so', 'libcodex-code-mode-host.so', 'libpocket-agent.so',
           'libpocket-runtime-host.so', 'libpocket-runtime-manifest.so')
MACHINES = {'arm64-v8a': 183, 'x86_64': 62}


def stage(source: Path, output: Path, downloads: Path, revision: str, asset_base: str,
          abis: list[str]) -> list[dict]:
    if not revision or len(revision) > 96 or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-' for c in revision):
        raise ValueError('Invalid environment revision')
    address = urlsplit(asset_base)
    if address.scheme != 'https' or not address.hostname or address.username or address.password or address.query or address.fragment:
        raise ValueError('Use an immutable HTTPS release asset base')
    if not abis or any(abi not in MACHINES for abi in abis) or len(set(abis)) != len(abis):
        raise ValueError('Unsupported or duplicate ABI')
    plan = []
    for abi in abis:
        assets = source / 'assets' / 'phone-linux' / abi
        native = source / 'jniLibs' / abi
        manifest = json.loads((assets / 'manifest.json').read_text(encoding='utf-8'))
        if manifest['schema'] != 1 or manifest['abi'] != abi:
            raise ValueError('Source manifest ABI differs')
        for name in BOOTSTRAP + RUNTIME:
            path = native / name
            if not path.is_file() or path.is_symlink():
                raise ValueError(f'Missing regular native input: {path}')
            if name != 'libpocket-runtime-manifest.so':
                with path.open('rb') as file:
                    header = file.read(20)
                if len(header) != 20 or header[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', header, 18)[0] != MACHINES[abi]:
                    raise ValueError(f'ELF ABI differs: {path}')
        destination = output / 'assets' / 'phone-linux' / abi
        archive = downloads / f'phone-environment-{revision}-{abi}.zip'
        if destination.exists() or (output / 'jniLibs' / abi).exists() or archive.exists():
            raise ValueError(f'Output already exists: {abi}')
        plan.append((abi, assets, native, manifest, destination, archive))
    results = []
    downloads.mkdir(parents=True, exist_ok=True)
    for abi, assets, native, original, destination, archive in plan:
        print(f'Packaging {abi}', flush=True)
        pending = archive.with_suffix('.zip.part')
        names = set()
        unpacked = 0
        original_size = 0
        try:
            with zipfile.ZipFile(pending, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as target:
                with zipfile.ZipFile(assets / 'rootfs.zip') as rootfs:
                    for entry in rootfs.infolist():
                        relative = safe_path(entry.filename)
                        if not relative or entry.is_dir() or relative != entry.filename or relative in names:
                            raise ValueError('Unexpected rootfs entry')
                        names.add(relative)
                        info = zipfile.ZipInfo('rootfs/' + relative, date_time=(2026, 1, 1, 0, 0, 0))
                        info.compress_type = zipfile.ZIP_DEFLATED
                        info.external_attr = entry.external_attr
                        with rootfs.open(entry) as source_file, target.open(info, 'w', force_zip64=True) as sink:
                            shutil.copyfileobj(source_file, sink, 1024 * 1024)
                        original_size += entry.file_size
                        unpacked += entry.file_size
                if len(names) != original['entryCount'] or original_size != original['unpackedBytes']:
                    raise ValueError('Rootfs inventory differs from its manifest')
                for name in RUNTIME:
                    file = native / name
                    target.write(file, 'runtime/' + name)
                    unpacked += file.stat().st_size
            executables = ['rootfs/' + safe_path(path) for path in original['executables']]
            executables += ['runtime/' + name for name in RUNTIME if name != 'libpocket-runtime-manifest.so']
            links = [{'path': 'rootfs/' + safe_path(row['path']),
                      'target': link_target(row['path'], row['target'])} for row in original['links']]
            with pending.open('rb') as file:
                digest = hashlib.file_digest(file, 'sha256').hexdigest()
            manifest = {**original, 'revision': revision, 'rootfsRevision': original['revision'],
                        'archiveBytes': pending.stat().st_size, 'archiveSha256': digest,
                        'unpackedBytes': unpacked, 'entryCount': len(names) + len(RUNTIME),
                        'executables': executables, 'links': links}
            destination.mkdir(parents=True)
            (destination / 'manifest.json').write_text(json.dumps(manifest, separators=(',', ':')) + '\n', encoding='utf-8')
            (destination / 'download.json').write_text(json.dumps({'schema': 1, 'url': asset_base.rstrip('/') + '/' + archive.name}, separators=(',', ':')) + '\n', encoding='utf-8')
            if (assets / 'licenses').is_dir():
                shutil.copytree(assets / 'licenses', destination / 'licenses')
            native_output = output / 'jniLibs' / abi
            native_output.mkdir(parents=True)
            for name in BOOTSTRAP:
                shutil.copy2(native / name, native_output / name)
            pending.replace(archive)
            results.append({'abi': abi, 'archive': str(archive), 'bytes': manifest['archiveBytes'],
                            'bootstrapBytes': sum((native_output / name).stat().st_size for name in BOOTSTRAP)})
        except Exception:
            pending.unlink(missing_ok=True)
            raise
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--downloads', type=Path, required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--asset-base', required=True)
    parser.add_argument('--abis', default='arm64-v8a,x86_64')
    args = parser.parse_args()
    result = stage(args.source, args.output, args.downloads, args.revision, args.asset_base, args.abis.split(','))
    print(json.dumps(result, ensure_ascii=False, indent=2))
