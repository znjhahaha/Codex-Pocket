"""Select work for an immutable release without rebuilding published artifacts."""
import argparse
import json
import os
import re
import subprocess
from pathlib import Path


def release_plan(tag, release, mode):
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", tag):
        raise ValueError("Select an immutable version tag.")
    if release is not None and release["tag_name"] != tag:
        raise ValueError("Release tag does not match the requested tag.")
    result = {"tag": tag, "version": tag[1:]}
    if mode == "draft":
        result["build"] = str(release is None or release["draft"]).lower()
        result["reason"] = "Build release draft" if result["build"] == "true" else "Already published; existing artifacts are retained"
        return result
    if release is None or release["draft"]:
        raise ValueError("Relay publication requires a published release.")
    assets = {asset["name"]: asset for asset in release["assets"]}
    required = (f"codex-pocket-{tag[1:]}-relay-amd64.tar.gz", "relay-SHA256SUMS.txt")
    present = [name in assets for name in required]
    if any(present) and not all(present):
        raise ValueError("Relay archive and checksum must both be present.")
    if all(present) and any(assets[name].get("state") != "uploaded" or assets[name].get("size", 0) <= 0 for name in required):
        raise ValueError("Relay assets have not finished uploading.")
    result.update(publish=str(all(present)).lower(), prerelease=str(release["prerelease"]).lower(),
                  reason="Publish reviewed relay archive" if all(present) else "This release contains no relay component")
    return result


def read_release(repository, tag):
    response = subprocess.run(["gh", "api", f"repos/{repository}/releases/tags/{tag}"], capture_output=True, text=True, encoding="utf-8")
    if response.returncode == 0:
        return json.loads(response.stdout)
    try:
        missing = str(json.loads(response.stdout).get("status")) == "404"
    except (ValueError, AttributeError):
        missing = False
    if missing:
        return None
    raise RuntimeError(f"Cannot read release metadata: {response.stderr.strip()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("draft", "relay"))
    args = parser.parse_args()
    tag = os.environ["RELEASE_TAG"]
    # Validate before using the tag in an API path.
    release_plan(tag, None, "draft")
    plan = release_plan(tag, read_release(os.environ["GH_REPO"], tag), args.mode)
    if output := os.environ.get("GITHUB_OUTPUT"):
        with Path(output).open("a", encoding="utf-8") as stream:
            for key, value in plan.items():
                stream.write(f"{key}={value}\n")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a", encoding="utf-8") as stream:
            stream.write(f"{tag}: {plan['reason']}.\n")
    print(json.dumps(plan))
