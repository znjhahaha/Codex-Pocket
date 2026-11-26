# Release workflows

Push a version tag to build a full release draft. The tag must match the versions
in `Cargo.toml` and `desktop/package.json`. Review the resulting assets before
publishing the draft.

An already published release is immutable. The **Release draft** workflow checks
its metadata and exits without rebuilding or replacing its assets. This also
supports component releases uploaded and published outside that workflow.
To inspect an existing release with the current workflow, run **Release draft**
from `main` and supply its version tag. A new or draft release still runs all
packaging checks against the selected tag's source.

The full draft includes the signed ARM64 APK, Windows installer and update package,
source archive, and relay image archive. The Android package retains the on-demand
phone environment: CI stages only the bootstrap, licenses and download metadata
from the pinned RC55 APK. The metadata still points to the original RC55 environment
assets; the environment's native binaries keep their actual version. Releasing the
app does not rebuild or relabel those binaries.

A green routing check for an already published version does not validate packaging.
Full CD acceptance requires the `windows_android`, `relay`, and `draft` jobs to
succeed for a new tag, followed by actual relay publication after the draft is
published. The existing host checks run before signing and packaging; no phone or
emulator is started by these workflows.

**Publish approved relay** runs after publication. It publishes the versioned
relay archive only when both `codex-pocket-<version>-relay-amd64.tar.gz` and
`relay-SHA256SUMS.txt` are present and uploaded. A release without either asset
has no relay component; a release with only one of them fails. The workflow also
accepts a published tag through manual dispatch from `main`. Stable image tags
are updated only for non-prereleases.

## Signing configuration

The GitHub `release` environment stores the original signing identities:

- Secrets: `POCKET_SIGNING_KEY_BASE64`, `POCKET_SIGNING_PASSWORD`,
  `POCKET_UPDATE_PRIVATE_KEY_PEM`.
- Variables: `POCKET_EXPECTED_SIGNER`, `POCKET_UPDATE_TRUST_JSON`. Repository-level
  variables may provide these values when the environment does not override them.

The Windows private key must match the public key in the update trust policy.
The workflow derives the asset URL from the selected version tag; packaging sets
the update feed to that release's `windows-update.json` while retaining the
approved public key and allowed hosts. No per-version asset-base variable is
needed.

Run `python -m unittest discover -s scripts -p test_release_state.py` to check
release routing. The ordinary **Checks** workflow runs these tests alongside the
relay build and retains the existing Android and Windows checks.
