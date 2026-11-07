# Quota helper RPC (0.5)

Authenticated paired sessions only. Unknown fields are rejected. These are helper methods,
not `codex/request` passthroughs. Native account/config data never goes to a phone.

`quota/sources {"runtimeId":"..."}` returns:

```json
{"runtimeId":"...","status":"unmatched","selectedSourceId":null,"sources":[{"id":"opaque UUID","label":"Provider label","kind":"cc_switch","status":"unmatched","associated":false,"autoRefreshSeconds":120,"manualCooldownSeconds":15}]}
```

`kind`: `official` or `cc_switch`. Top status: `ok`, `unmatched`, `unavailable`, `unconfigured`.
Only a unique verifiable provider association selects a source. Unmatched known sources
can still be explicitly selected. IDs are host-generated and must be rediscovered after
helper restart, native reconnection (official), or provider settings changes.

`quota/read {"runtimeId":"...","sourceId":"opaque UUID","refresh":false}` returns:

```json
{"runtimeId":"...","sourceId":"...","label":"Provider label","status":"ok","items":[{"label":"Wallet","used":null,"total":null,"remaining":12.5,"unit":"USD","usedPercent":null,"resetsAt":null}],"updatedAt":1794000000000,"stale":false,"retryAfterSeconds":60}
```

`refresh` is optional (default false). True requests manual refresh with a host-enforced
15 second cooldown. False is demand-driven automatic polling: at least 60 seconds, or
CC Switch `autoQueryInterval` minutes if larger. Configured 0 disables automatic reads.
There is no unattended background polling when phones stop asking. The first automatic
read queries unless disabled. Cooldown replies use the cache; overlapping reads coalesce.

Result status: `ok`, `unknown`, `unconfigured`, `auto_disabled`, `invalid`, `unavailable`, `script_error`,
`timeout`, `http_error`, `limit_exceeded`. No raw error messages or HTTP bodies.
`updatedAt` and `resetsAt` are Unix milliseconds, nullable. Third-party numeric reset
timestamps below 1e12 are interpreted as Unix seconds and multiplied by 1000. Missing amounts are null;
remaining-only balance never invents a percentage. A failure retains last good items and
timestamp, sets stale=true and returns its fixed status. With no successful result the
items are empty and updatedAt=null. Expired successful data also carries stale=true.

`quota/updated` notification params have exactly the quota/read result shape. Supported
native `account/rateLimits/updated` notifications update the official cache without HTTP.
`account/updated` clears the prior account cache and invalidates older in-flight results.
Both quota helper methods are advertised in hello and each runtime capabilities array.
Unknown runtime/source/invalid fields use normal fixed `not_found`/`invalid_params` errors;
unauthenticated/revoked clients use the existing session authorization errors.

## Bridge integration

Bridge `router.rs` permits helper remote `account/rateLimits/read {}` and `config/read {}`
with capability gating and empty params only. Its existing generic notification broadcast
already carries `account/rateLimits/updated`. The agent mobile allowlist still denies raw
account/config access. Missing capabilities, omitted/nonobject params and any nonempty
params are rejected, including cwd/includeLayers/accountId. Desktop API routing is preserved.

Deployment note: an already-running bridge keeps its existing code and discovered capability
snapshot. Updating/restarting the helper alone does not install these routes into that bridge.
Until a new bridge instance starts through the normal desktop lifecycle, official quota reads
and config-based association may remain unavailable; known CC Switch sources can still be
listed as unmatched and explicitly selected. Preserve the currently running Codex process;
do not terminate it merely to activate quota support. Report observed capabilities and quota
status, and verify official reads after a normal bridge/runtime restart. An unavailable source
or read failure must never be presented as successful live quota acceptance.

Validation: `cargo test --workspace --locked --quiet` passed 249 tests;
`cargo clippy --workspace --all-targets --locked -- -D warnings` passed. Changed Rust sources
passed `rustfmt --check` (edition 2024, skip_children=true), and `git diff --check` passed.
These checks used synthetic/local fixtures, including bridge method rejection, paired-session
continuity, JS resource limits, request-secret redaction, WAL reads and the four-query budget.
No live quota endpoint or inference request was exercised by this implementation task.


## Execution bounds

Read-only `~/.cc-switch/cc-switch.db`, enabled JavaScript Codex providers, no migration or
CC Switch writes. Disabled/missing scripts on a proven current provider yield an explicit
`unconfigured` card and never execute HTTP. VM: 16 MiB heap, 256 KiB stack, 250 ms per phase, 64 KiB script/output.
No filesystem/process/network JS bindings. Request placeholders are substituted by Rust
AFTER VM evaluation, preventing credential text from becoming executable JavaScript.
Only normalized fields are returned; configured credentials, header values, query values and
body string values (including common encodings) are redacted from text output.
HTTP: HTTPS only, public addresses pinned after DNS, no ambient proxy, no redirects,
32 headers, 64 KiB request body, 1 MiB response, connect timeout 3 s, configured timeout
clamped 2–15 s, whole read 20 s, at most four concurrent reads. GET/POST from an existing
local script only; phone input can never supply endpoints, scripts or credentials.
