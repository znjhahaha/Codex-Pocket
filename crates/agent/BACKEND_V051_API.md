# 0.5.1 backend contract

- `thread/context/read` accepts exactly `{runtimeId,threadId}`. Returns `{runtimeId,threadId,status,source,updatedAt,turnId,usage}`. Status: `ok|stale|empty|unsupported|failed`; source: `native_event|native_snapshot|history|null`; updatedAt: epoch milliseconds or null. Usage is `{last,total,modelContextWindow}`; breakdowns only contain nonnegative numeric token fields. No path, body or generic config returned.
- `thread/permissions/read` accepts exactly `{runtimeId,threadId}`. Returns `{runtimeId,threadId,status,revision,currentPresetId,presets,pending?}`. Status: `ready|unknown|unsupported`; revision is an opaque string. Presets contain `{id,label,description,enabled,reason?}`. IDs: `request`, `auto`, `full`, or opaque existing custom profile IDs. Missing current selection is null, never a guessed default.
- `thread/permissions/update` accepts exactly `{runtimeId,threadId,presetId,expectedRevision,commandId}`. Returns the same permissions envelope on ACK. Changes are for subsequent turns. Existing ledger rules apply: unknown outcomes are never replayed. Invalid revision is rejected before dispatch.
- `threads/list` adds optional `isSubagent`, `parentThreadId`, `nativeSource` (normalized class string). Native identities are preserved; no name/preview inference.
- Auto review and named profiles require native schema evidence. Custom entries require an existing valid native config profile. Unsupported configurations are not invented. Fast remains independently gated.

Reader lifetime: subscriptions are shared per runtime, not owned per phone. No safe global unsubscribe is exposed. Closing a child reader should cancel local observation only; no native unsubscribe, unload or task stop.

## Integration evidence and boundaries

- Transport callers use the existing `commandId` argument; transport puts it into broker request params as for other mutations. The UI does not need to duplicate it in its own body.
- Permission success is `ready` with a string revision. Unknown current settings remain `unknown`; native/read transport errors use the existing RPC error envelope. No default permission is guessed.
- Compaction and verified same-process reconnect preserve numeric usage as `stale` until fresh native usage arrives. History timestamps use the original UTC rollout timestamp in milliseconds; unavailable timestamps remain null.
- Existing custom profiles are offered only if native update/config-read support and a recognized effective permissions definition are present. No arbitrary config, raw script, credential, or phone-selected filesystem path is accepted.
- Final checks: `cargo test -p pocket-agent -p pocket-bridge --lib --tests --quiet`: 175 passed (agent 125 unit + 5 integration; bridge 27 unit + 18 integration). Strict Clippy for these two packages passed; diff whitespace check passed.
- No real model requests, real permission mutations, service deployment, Git staging/commit/reset, Android edits, version bumps or root documentation edits were performed in this slice. Device and live native permission acceptance remain with the parent task.
