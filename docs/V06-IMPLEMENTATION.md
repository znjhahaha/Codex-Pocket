# Codex Pocket 0.6 implementation ledger

Approved scope: desktop-follow ownership, safe CLI handoff, scoped operation states,
Fast capability feedback, historical images, first-run Windows installer and tray,
LAN/public route selection, Cloudflare tunnels, owned SSH relay deployment, CI and
plain-language documentation with real screenshots.

## Invariants

- Preserve existing dirty work, pairing identity, native thread IDs, drafts and old deliveries.
- No model-generation/counting/long-output acceptance calls.
- User reauthorized MuMu/ADB testing on 2026-11-09. Keep device evidence separate from host-only checks and new-backend acceptance.
- Never terminate an active native Codex task or remove native ownership locks.
- Unknown mutation results are reconciled, never silently resent.
- Secrets stay out of Git, diagnostics, screenshots and distributed artifacts.
- Default is desktop follow; CLI requires an explicit choice. No silent CLI fallback.
- Release target is 0.6.0 / Android versionCode 10; original Android signing identity.
- Commit/push/CI are authorized after checks; publication stops at a Release draft.

## Work packages

| Area | Status | Evidence |
| --- | --- | --- |
| Ownership and desktop handoff | implemented, live bridge acceptance pending | Durable access phases, subscription release checks, local fixtures |
| Historical user image recovery | implemented, phone/backend acceptance pending | Bounded native-history reads, media chunks and Rust regressions |
| Android controls and scoped state | implemented, MuMu functional checks passed | 796 host tests; existing-history browsing, 50 navigation returns, 30 recorded reading stages; performance and new-backend live acceptance remain open |
| LAN and public transports | implemented, live network switching pending | Pinned local TCP/TLS, route failover and cancellation fixtures |
| Windows onboarding, tray, installer | implemented, real install/login pending | Runtime and installer fixtures, NSIS Setup build |
| Cloudflare and SSH deployment | implemented, public integration pending | Locked cloudflared, DPAPI and bounded owned deployment helpers; no live deployment this round |
| CI, release and README | pushed; Windows, Android and relay CI passed; draft packaging next | `be30bff`, GitHub Checks run `42570430157`; original signing configured; tag-triggered publication stops at draft |

This file is an implementation ledger, not an acceptance claim. Live server setup,
Windows login, phone hardware and 24-hour stability require separate evidence.
