"""Fetch only the pinned third-party revisions into the ignored build directory."""
import json
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[2]
destination = root / '.local' / 'p2p'
packages = json.loads((Path(__file__).parent / 'dependencies.lock.json').read_text())['packages']
def git(path, *args):
    return subprocess.check_output(['git', '-C', str(path), *args], text=True).strip()

for package in packages:
    path = (destination / package['path']).resolve()
    if not path.is_relative_to(destination.resolve()):
        raise RuntimeError('Invalid dependency path')
    path.mkdir(parents=True, exist_ok=True)
    if not (path / '.git').exists():
        if any(path.iterdir()):
            raise RuntimeError(f'Refusing to replace nonempty dependency: {package["path"]}')
        git(path, 'init', '--quiet')
        git(path, 'fetch', '--quiet', '--depth=1', package['url'], package['commit'])
        git(path, 'checkout', '--quiet', '--detach', 'FETCH_HEAD')
    if git(path, 'rev-parse', 'HEAD') != package['commit'] or git(path, 'status', '--porcelain', '--untracked-files=no'):
        raise RuntimeError(f'Pinned source changed: {package["path"]}')
print(f'Validated {len(packages)} pinned source revisions')
