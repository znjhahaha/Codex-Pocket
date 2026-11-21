"""Sign independently built Linux host binaries for the Android SSH installer."""
import argparse, base64, hashlib, json, pathlib, shutil
from cryptography.hazmat.primitives import serialization

parser = argparse.ArgumentParser()
parser.add_argument('--bin', type=pathlib.Path, required=True)
parser.add_argument('--host-bin', type=pathlib.Path, required=True)
parser.add_argument('--output', type=pathlib.Path, required=True)
parser.add_argument('--version', required=True)
parser.add_argument('--architecture', choices=['x86_64', 'aarch64'], required=True)
parser.add_argument('--signing-key', type=pathlib.Path, required=True)
args = parser.parse_args()
if args.output.exists():
    raise SystemExit('Output already exists; preserve previous runtime bundle')
files = {'pocket-agent': args.bin / 'pocket-agent', 'pocket-runtime-host': args.host_bin / 'pocket-runtime-host'}
entries = []
for name, source in files.items():
    data = source.read_bytes()
    if not data.startswith(b'\x7fELF'):
        raise SystemExit(f'{name} is not a Linux ELF binary')
    machine = int.from_bytes(data[18:20], 'little')
    if machine != {'x86_64': 62, 'aarch64': 183}[args.architecture]:
        raise SystemExit(f'{name} architecture mismatch')
    entries.append({'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
manifest = json.dumps({'schema': 1, 'product': 'Codex Pocket', 'version': args.version,
    'architecture': args.architecture, 'files': entries}, ensure_ascii=False, separators=(',', ':')).encode()
key = serialization.load_pem_private_key(args.signing_key.read_bytes(), password=None)
expected = base64.b64decode('BYQSPxB4SIYDiqclmPaERfItHjDK4FP63vncq8usSgQ=')
if key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw) != expected:
    raise SystemExit('Signing identity does not match the pinned app trust')
signature = base64.b64encode(key.sign(manifest))
args.output.mkdir(parents=True)
for name, source in files.items():
    shutil.copy2(source, args.output / name)
(args.output / 'runtime-manifest.json').write_bytes(manifest)
(args.output / 'runtime-manifest.sig').write_bytes(signature)
print(f'Signed {args.architecture} runtime: {args.output}')
