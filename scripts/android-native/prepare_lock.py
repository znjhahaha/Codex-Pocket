"""Synchronize only local package versions with the pinned release manifest.

The official release tag has workspace.version=0.171.0 but retains 0.0.0 for
174 local lockfile entries. Remote dependency pins and checksums stay byte-exact.
"""
import re
import sys
import tomllib
from pathlib import Path

workspace = Path(sys.argv[1])
version = tomllib.loads((workspace / "Cargo.toml").read_text())["workspace"]["package"]["version"]
if version != "0.171.0":
    raise SystemExit(f"Unexpected pinned workspace version: {version}")
path = workspace / "Cargo.lock"
text = path.read_text()
chunks = text.split("[[package]]")
count = 0
for index, chunk in enumerate(chunks[1:], 1):
    item = tomllib.loads("[[package]]" + chunk)["package"][0]
    if "source" not in item and item["version"] == "0.0.0":
        chunks[index], changed = re.subn(r'^version = "0\.0\.0"$', f'version = "{version}"', chunk, count=1, flags=re.M)
        if changed != 1:
            raise SystemExit(f"Unexpected local lockfile entry: {item['name']}")
        count += 1
if count:
    path.write_text("[[package]]".join(chunks), newline="\n")
print(f"Aligned {count} local lockfile package versions; external pins unchanged.")
