# Third-party notices

The Android glass effects use Backdrop 1.0.0 by Kyant, from
https://github.com/Kyant0/AndroidLiquidGlass, distributed under Apache License 2.0.
License: https://www.apache.org/licenses/LICENSE-2.0 .

Pocket Outline in-app icons and the Pocket launcher artwork were drawn for this project.
DSH Go and the user's zfapk project informed design and engineering research;
their branding, artwork and application source are not bundled.

Other dependencies retain their licenses in Gradle/npm/Cargo package metadata.

## Cloudflare Tunnel

The Windows delivery bundles unmodified cloudflared 2026.10.7 under Apache License 2.0.
Its original license is distributed as `windows/cloudflared-LICENSE.txt`.
The pinned upstream download and SHA-256 are recorded in `deploy/cloudflared-lock.json`.
Source: https://github.com/cloudflare/cloudflared .

BaoCode's chat minimap informed the expanded navigation's interaction and semantic color design
(https://github.com/baocode-dev/baocode). The Compose implementation is independently written;
BaoCode source and assets are not bundled.

## Inter

Inter by Rasmus Andersson, SIL Open Font License 1.1. Bundled static instances generated from the official Google Fonts Inter variable font (wght 400/500/600/700, opsz 16). License: `android/app/src/main/assets/inter-OFL.txt`. Source: https://github.com/google/fonts/tree/main/ofl/inter .
# MiSans

Codex Pocket 0.5.4 uses MiSans Regular, Medium, Semibold and Bold, unmodified, for its Chinese and Latin UI and reading text.
Copyright belongs to Xiaomi. These fonts retain their original metadata. They are bundled as part of this application, not offered as a separate font distribution.
The official MiSans Font Intellectual Property License Agreement is included as `android/app/src/main/assets/misans-license.pdf` (original) and `misans-license.txt` (text extraction).

Official font archive: https://hyperos.mi.com/font-download/MiSans.zip

Official license: https://hyperos.mi.com/font-download/MiSans字体知识产权许可协议.pdf

## Experimental DataChannel fixture

The source-only prototype in `native/p2p/` pins libdatachannel and libjuice
(MPL-2.0), usrsctp (BSD-3-Clause), plog (MIT), and Mbed TLS with its framework
(Apache-2.0). The precise revisions and source digests are recorded in
`native/p2p/dependencies.lock.json`; license texts are in `native/p2p/licenses/`.
These native libraries are not integrated into the delivered APK or Windows app.
