"""Build the offline Android Linux environment from locked upstream artifacts.

No root privileges or Linux host are needed. Archives are never extracted onto
the build host: regular files go into a ZIP, with links and executable modes in
the manifest consumed by PhoneLinuxEnvironment. Downloaded scripts are not run.
"""
from __future__ import annotations
import argparse
import concurrent.futures
import hashlib
import io
import json
import posixpath
from pathlib import Path, PurePosixPath
import shutil
import struct
import tarfile
import urllib.request
import zipfile

LOCK = Path(__file__).with_name('linux.lock.json')


def digest(path: Path) -> str:
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def safe_path(value: str) -> str:
    while value.startswith('./'): value = value[2:]
    if not value or value == '.': return ''
    if value.startswith('/') or '\\' in value or (len(value)>1 and value[1]==':') or '\x00' in value:
        raise ValueError(f'Unsafe archive path: {value!r}')
    parts = PurePosixPath(value).parts
    if '..' in parts: raise ValueError(f'Unsafe archive path: {value!r}')
    return str(PurePosixPath(value))


def link_target(path: str, target: str) -> str:
    if '\\' in target or (len(target)>1 and target[1]==':') or '\x00' in target: raise ValueError('Invalid link')
    absolute = posixpath.normpath(target.lstrip('/') if target.startswith('/') else posixpath.join(posixpath.dirname(path), target))
    safe_path(absolute)
    return posixpath.relpath(absolute, posixpath.dirname(path) or '.')


def archive_members(data: bytes):
    if data[:4] == b'\x28\xb5\x2f\xfd':
        import zstandard
        data = zstandard.ZstdDecompressor().stream_reader(io.BytesIO(data)).read()
    return tarfile.open(fileobj=io.BytesIO(data), mode='r:*')


def deb_data(data: bytes) -> bytes:
    if not data.startswith(b'!<arch>\n'): raise ValueError('Not a Debian archive')
    at = 8
    while at + 60 <= len(data):
        header = data[at:at+60]; length = int(header[48:58]); name = header[:16].decode().strip().rstrip('/')
        if header[58:60] != b'`\n' or length < 0 or at+60+length > len(data): raise ValueError('Invalid ar member')
        if name.startswith('data.tar'): return data[at+60:at+60+length]
        at += 60 + length + length % 2
    raise ValueError('Debian archive has no data')


class Image:
    def __init__(self):
        self.files: dict[str, tuple[bytes,int]] = {}
        self.links: dict[str,str] = {}
        self.hardlinks: dict[str,str] = {}

    def add_tar(self, data: bytes, prefix: str = '', strip: int = 0):
        with archive_members(data) as archive:
            for member in archive:
                path = safe_path(member.name)
                parts = path.split('/')[strip:]
                if not path or not parts: continue
                path = safe_path(posixpath.join(prefix, *parts))
                if member.isfile():
                    stream = archive.extractfile(member)
                    if stream is None: raise ValueError('Missing member bytes')
                    self.files[path] = (stream.read(), member.mode & 0o777)
                    self.links.pop(path, None); self.hardlinks.pop(path, None)
                elif member.issym():
                    self.links[path] = link_target(path, member.linkname)
                    self.files.pop(path, None)
                elif member.islnk():
                    target = safe_path(member.linkname)
                    self.hardlinks[path] = safe_path(posixpath.join(prefix, *target.split('/')[strip:]))
                elif not (member.isdir() or member.ischr() or member.isblk() or member.isfifo()):
                    raise ValueError(f'Unsupported member {path}')

    def canonical(self, path: str) -> str:
        seen = set()
        while True:
            if path in seen: raise ValueError('Cyclic archive link')
            seen.add(path); parts = path.split('/'); replaced = False
            for count in range(1, len(parts)):
                parent = '/'.join(parts[:count])
                if parent in self.links:
                    path = safe_path(posixpath.normpath(posixpath.join(posixpath.dirname(parent), self.links[parent], *parts[count:])))
                    replaced = True; break
            if not replaced: return path

    def write(self, directory: Path, abi: str, revision: str, tools: dict) -> dict:
        for _ in range(len(self.hardlinks)+1):
            if not self.hardlinks: break
            for path,target in list(self.hardlinks.items()):
                if target in self.files:
                    self.files[path] = self.files[target]; del self.hardlinks[path]
        if self.hardlinks: raise ValueError(f'Unresolved hardlink: {next(iter(self.hardlinks))}')
        files = {self.canonical(path):value for path,value in self.files.items()}
        links = {self.canonical(path):link_target(self.canonical(path), target) for path,target in self.links.items()}
        if set(files) & set(links): raise ValueError('File/link collision')
        directory.mkdir(parents=True,exist_ok=True)
        archive = directory/'rootfs.zip'
        with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as output:
            for path,(data,mode) in sorted(files.items()):
                info = zipfile.ZipInfo(path, date_time=(2026,1,1,0,0,0)); info.compress_type=zipfile.ZIP_DEFLATED
                info.external_attr = (0o100000 | mode) << 16
                output.writestr(info,data)
        manifest = {'schema':1,'revision':revision,'abi':abi,'archiveBytes':archive.stat().st_size,'archiveSha256':digest(archive),
                    'unpackedBytes':sum(len(value[0]) for value in files.values()),'entryCount':len(files),
                    'executables':[path for path,(_,mode) in sorted(files.items()) if mode & 0o111],
                    'links':[{'path':path,'target':target} for path,target in sorted(links.items())],'tools':tools}
        (directory/'manifest.json').write_text(json.dumps(manifest,separators=(',',':'))+'\n')
        return manifest


def fetch(source: dict, cache: Path) -> Path:
    path=cache/source['name']; cache.mkdir(parents=True,exist_ok=True)
    if path.is_file():
        if digest(path)==source['sha256']: return path
        raise ValueError(f'Cached input failed integrity: {path}')
    urls=[source['url']]
    if 'archive.ubuntu.com/ubuntu/' in urls[0]: urls.append(urls[0].replace('archive.ubuntu.com/ubuntu/','mirrors.tuna.tsinghua.edu.cn/ubuntu/'))
    if 'ports.ubuntu.com/ubuntu-ports/' in urls[0]: urls.append(urls[0].replace('ports.ubuntu.com/ubuntu-ports/','mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/'))
    if 'cdimage.ubuntu.com/' in urls[0]: urls.append(urls[0].replace('cdimage.ubuntu.com/','mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/'))
    if 'nodejs.org/dist/' in urls[0]: urls.append(urls[0].replace('nodejs.org/dist/','npmmirror.com/mirrors/node/'))
    if 'github.com/astral-sh/python-build-standalone/releases/download/' in urls[0]: urls.append(urls[0].replace('github.com/astral-sh/python-build-standalone/releases/download/','registry.npmmirror.com/-/binary/python-build-standalone/'))
    if source.get('kind')=='deb': urls.append('https://launchpad.net/ubuntu/+archive/primary/+files/'+source['name'])
    part=path.with_name(path.name+'.part'); error=None
    for url in urls:
        try:
            print('Downloading',source['name'],flush=True)
            request=urllib.request.Request(url,headers={'User-Agent':'CodexPocket-runtime-builder'})
            with urllib.request.urlopen(request,timeout=60) as response, part.open('wb') as output:
                shutil.copyfileobj(response,output,1024*1024)
            if digest(part)!=source['sha256']: raise ValueError(f'Input integrity failed: {source["name"]}')
            part.replace(path); return path
        except (OSError,urllib.error.URLError) as exception: error=exception
    raise RuntimeError(f'Download failed: {source["name"]}: {error}')


def native_libraries(sources: list[dict], cache: Path, destination: Path, machine: int):
    native=Image()
    for source in sources:
        if source['kind'] in ('proot','talloc','shmem'): native.add_tar(deb_data((cache/source['name']).read_bytes()))
    prefix='data/data/com.termux/files/usr/'
    mapping={'libproot.so':'bin/proot','libproot-loader.so':'libexec/proot/loader',
             'libtalloc.so':'lib/libtalloc.so.2.5.0','libandroid-shmem.so':'lib/libandroid-shmem.so'}
    destination.mkdir(parents=True,exist_ok=True)
    for name,source in mapping.items():
        data=native.files[prefix+source][0]
        if data[:4]!=b'\x7fELF' or data[4:6]!=b'\x02\x01' or struct.unpack_from('<H',data,18)[0]!=machine:
            raise ValueError(f'Native ABI mismatch: {name}')
        if name in ('libproot.so','libtalloc.so'):
            old=b'libtalloc.so.2\x00'; new=b'libtalloc.so\x00\x00\x00'
            if data.count(old)!=1: raise ValueError('Unexpected talloc dynamic-name layout')
            data=data.replace(old,new)
        (destination/name).write_bytes(data)
    return native


def stage_licenses(abi: str, lock: dict, cache: Path, output: Path):
    """Ship full notices plus the source/recipe archives beside the APK input tree."""
    texts = {}
    for source in lock.get('sourceArchives', []):
        path = cache/source['name']
        members = source.get('licenseFiles', {})
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                for name, member in members.items(): texts[safe_path(name)] = archive.read(member)
        else:
            with archive_members(path.read_bytes()) as archive:
                for name, member in members.items():
                    stream = archive.extractfile(member)
                    if stream is None: raise ValueError(f'Missing license: {member}')
                    texts[safe_path(name)] = stream.read()
    for source in lock.get('licenseInputs', []): texts[safe_path(source['name'])] = (cache/source['name']).read_bytes()
    for name, data in texts.items():
        if not data: raise ValueError(f'Empty license: {name}')
    notices = output/'assets'/'phone-linux'/abi/'licenses'
    notices.mkdir(parents=True, exist_ok=True)
    for name, data in texts.items(): (notices/name).write_bytes(data)
    metadata = {key: lock.get(key, []) for key in ('sourceArchives', 'licenseInputs')}
    metadata['sources'] = lock['abis'][abi]['sources']
    (notices/'source-inputs.json').write_text(json.dumps(metadata, indent=2)+'\n', encoding='utf-8')
    sources = output/'sources'; sources.mkdir(parents=True, exist_ok=True)
    for source in lock.get('sourceArchives', []) + lock.get('licenseInputs', []):
        shutil.copy2(cache/source['name'], sources/source['name'])
    # Deliver our transformation script and build instructions with the originals.
    for name in ('LINUX-SOURCES.md', 'linux_environment.py', 'linux.lock.json', 'build-phone-runtime.sh', 'package_agent.py', 'fetch_official.py', 'official.lock.json'):
        source = Path(__file__).with_name(name)
        if source.exists(): shutil.copy2(source, sources/name)
    guide = Path(__file__).with_name('LINUX-SOURCES.md')
    if guide.exists(): shutil.copy2(guide, notices/'BUILD-INPUTS.md')


def build(abi: str, lock: dict, cache: Path, output: Path):
    selected=lock['abis'][abi]; sources=selected['sources']
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        jobs=[pool.submit(fetch,source,cache) for source in sources+lock.get('sourceArchives',[])+lock.get('licenseInputs',[])]
        failures=[]
        for job in concurrent.futures.as_completed(jobs):
            try: job.result()
            except Exception as error: failures.append(str(error)); print(str(error),flush=True)
        if failures: raise RuntimeError('\n'.join(failures))
    image=Image()
    for source in sources:
        data=(cache/source['name']).read_bytes(); kind=source['kind']
        if kind=='ubuntu': image.add_tar(data)
        elif kind=='node': image.add_tar(data,'opt/node',1)
        elif kind=='python': image.add_tar(data,'opt/python',1)
        elif kind=='deb': image.add_tar(deb_data(data))
    for name,target in {'node':'../../opt/node/bin/node','npm':'../../opt/node/lib/node_modules/npm/bin/npm-cli.js',
                        'npx':'../../opt/node/lib/node_modules/npm/bin/npx-cli.js',
                        'python3':'../../opt/python/bin/python3','python':'../../opt/python/bin/python3',
                        'pip3':'../../opt/python/bin/pip3'}.items(): image.links['usr/bin/'+name]=target
    # Avoid distribution service-management assumptions; networking goes through
    # Pocket's Android-resolving loopback proxy, with its exported trust roots.
    image.files['etc/resolv.conf']=(b'nameserver 1.1.1.1\nnameserver 8.8.8.8\n',0o644); image.links.pop('etc/resolv.conf',None)
    image.files['etc/hosts']=(b'127.0.0.1 localhost\n::1 localhost\n',0o644)
    image.files['etc/profile.d/pocket.sh']=(b'export PATH=/opt/python/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n',0o644)
    native=native_libraries(sources,cache,output/'jniLibs'/abi,selected['machine'])
    notices=output/'assets'/'phone-linux'/abi/'licenses'; notices.mkdir(parents=True,exist_ok=True)
    stage_licenses(abi,lock,cache,output)
    for path,(data,_) in native.files.items():
        if path.endswith('/copyright'): (notices/(path.split('/')[-2]+'-copyright.txt')).write_bytes(data)
    # Distribution/Python/Node notices remain inside their original rootfs paths.
    manifest=image.write(output/'assets'/'phone-linux'/abi,abi,lock['revision'],lock['tools'])
    print(json.dumps({'abi':abi,'archiveBytes':manifest['archiveBytes'],'unpackedBytes':manifest['unpackedBytes'],'output':str(output)}),flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abi',choices=['x86_64','arm64-v8a','all'],default='all')
    parser.add_argument('--cache',type=Path,default=Path('E:/pocket-build/phone-linux/cache'))
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--licenses-only',action='store_true',help='Add locked source notices without rebuilding an existing rootfs')
    args=parser.parse_args(); lock=json.loads(LOCK.read_text())
    if args.licenses_only:
        for source in lock.get('sourceArchives',[])+lock.get('licenseInputs',[]): fetch(source,args.cache)
    for abi in (list(lock['abis']) if args.abi=='all' else [args.abi]):
        if args.licenses_only:
            stage_licenses(abi,lock,args.cache,args.output)
            continue
        if (args.output/'assets'/'phone-linux'/abi/'manifest.json').exists(): raise ValueError('Existing completed bundle is preserved; choose new output')
        build(abi,lock,args.cache,args.output)


if __name__=='__main__': main()
