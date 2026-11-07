# Pocket wire v1

Single-user product, no OpenAI/ChatGPT login. Native provider configuration and credentials remain on Windows. All application JSON below travels ONLY inside inner TLS between Android and Windows. The relay sees only TLS records and routing metadata. Version/capability differences must remain explicit.

## Outer opaque relay
- WSS URLs: `/v1/tunnel/{room}/helper` and `/v1/tunnel/{room}/phone`.
- `Authorization: Bearer <role token>` header. Never query-string credentials.
- Relay config has room ID, SHA256 hex of helper token, SHA256 hex of phone token. Distinct 256-bit random tokens. Same phone routing token may be used by paired phones; inner device credentials independently revoke access.
- One helper connection per room, up to 8 phones. Reject duplicate helper, do not evict it. Phone with no helper is rejected/offline.
- Phone sends/receives binary TLS byte chunks (at most 65536 bytes). Relay sends helper binary envelope: first byte 1=connect,2=disconnect,3=data; next 16 bytes UUID channel ID; remaining bytes=data for type3. Helper sends type3 data or type2 disconnect envelopes. Relay assigns channel IDs; phone cannot choose or inject a different ID.
- Channels are byte streams; chunk boundaries have no TLS/application significance. Bounded queues; slow peer disconnects, never drops bytes silently. No payload logs or disk spool.
- GET `/healthz` constant health JSON, no room identifiers.

## Inner TLS
- Windows Rustls server with generated self-signed P-256 certificate for `pocket.local`, TLS1.2/1.3.
- Android SSLEngine client pins SHA256 of exact certificate DER supplied in QR, checks certificate validity; use peer name pocket.local. No permissive TrustManager, no plaintext fallback.
- After TLS handshake: each JSON message has a 4-byte unsigned big-endian length prefix then UTF-8 bytes (max 16MiB).
- Requests `{id: string, method: string, params: object}`; replies `{id, result}` or `{id,error:{code:string,message:string}}`; notifications `{method,params}`.
- JSON messages serialized, no batching. Unique command ID for mutating operations. Unknown outcomes never automatically replay.

## Pairing QR
`{"v":1,"relayUrl":"wss://.../pocket","room":"...","phoneToken":"...","certificateSha256":"hex","pairingToken":"...","expiresAt":unixSeconds,"computerName":"..."}`
Base relay URL includes any Caddy path prefix. One-time pairingToken expires after 5 minutes, consumed atomically by Windows. QR bearer only confers pairing during that window; never log payload.
After TLS: `pair {pairingToken,deviceId,deviceName}` returns `{deviceId,deviceSecret,computerId,computerName}`. Device secret 256-bit random, stored hashed on Windows; Android stores under Keystore encryption.
Reconnect: `hello {protocol:1,deviceId,deviceSecret}` returns `{protocol:1,computerId,computerName,capabilities:[...],runtimes:[...]}`. Every other method requires hello. Revoked devices rejected and active connection closed.

## Application methods
- `runtimes/list {}` -> `{data:[{id,source:"desktop"|"cli",version,configStoreId,online,loadedThreadIds:[]}]}`.
- `threads/list {cursor?:object,limit?:int,search?:string,archived?:bool}` -> `{data:[{id,runtimeId,source,name,preview,cwd,updatedAt,status,conflict}],nextCursor:object|null}`. id is the native thread ID. Deduplicate by computer/config store/native ID. Cache friendly; offline histories remain displayable.
- `codex/request {runtimeId,method,params,commandId?:string}` -> native RPC result, with a Pocket snapshot watermark where applicable. Mutating methods require commandId. Supported native surface: thread/start, thread/read, thread/resume (helper validates ownership before native resume), thread/list, thread/loaded/list, thread/name/set, thread/archive, thread/unarchive, thread/turns/list, thread/items/list, thread/queue/list, model/list, turn/start, turn/steer, turn/interrupt. Capability negotiation may restrict per runtime. Queue pages come from the native queue; they are not inferred from mobile drafts. Do NOT offer configuration/auth write methods, arbitrary command/exec, or delete.
- `approvals/respond {runtimeId,requestId,response,commandId}` -> `{status:"submitted"|"accepted"|"unknown"}`. Request ID is opaque JSON, including bridge generation token. Scope to original runtime and pending request. Native resolution invalidates all other views.
- `commands/status {commandId}` -> `{status:"submitted"|"accepted"|"unknown"|"rejected",result?:any}` or not_found.
- `sync {cursors:{runtimeId:{epoch,sequence}}}` -> `{events:[{runtimeId,cursor,event}],snapshotRequired:[runtimeId]}`. New connection with no cursor gets snapshotRequired for current runtimes.
- `devices/list {}` -> `{data:[{id,name,createdAt}]}`; `devices/revoke {deviceId,commandId}`.
- `attachment/upload {name,mime,base64,commandId}` -> `{attachmentId}`. Max 8MiB decoded; JPEG/PNG/WebP only, inspect file signature. Native input `{"type":"pocketImage","attachmentId":"..."}` is replaced on Windows with native localImage using server-created path. Never accept arbitrary phone-provided file paths. Attachments scoped to paired device, time bounded.

## Notifications
- `codex/event {runtimeId,cursor:{epoch,sequence},event:<unchanged native RPC notification/server request>}`.
- `runtimes/changed {data:[Runtime]}`.
- `connection/state {state:"online"|"offline"|"reconnecting",message?:string}` is local mobile UI state, not native data.

Native approval methods: item/commandExecution/requestApproval, item/fileChange/requestApproval, item/tool/requestUserInput. Render unknown native requests as desktop-required; never default accept. Requests with command/file decisions use native schemas; user-input answers preserve question IDs. Native `serverRequest/resolved` or bridge `pocket/approvalResolved` closes them. Closing an obsolete card does not imply the phone's response was accepted. CLI transport may rely on the native daemon's multi-client arbitration.

Native timeline: thread/read includeTurns for initial supported versions; paginate with native turn/item APIs when exposed. Persist native turn/item IDs; append agentMessage/delta, commandExecution/outputDelta, finalized item/completed replaces accumulated partial. Do not force scroll if user scrolled upward. Thread drafts/pins/UI expansion/scroll are local, keyed by computer+configStore+native thread.

## Ownership and approval recovery

Disconnected engines keep their known ownership claims in durable `ownership.json`. A new helper/runtime cannot take over a live but unreachable owner. On Windows, the authenticated native engine PID and process creation FILETIME establish process identity. Confirmed termination or PID reuse can release its claim; missing descriptors, missing identity, access denial and query failure cannot. Other platforms retain uncertainty. A complete current-generation native inventory can reconcile claims. Failed persistence blocks ownership-dependent dispatch.

Inactive resume and rename/archive/unarchive require an exact-ID native `thread/read`, a fresh ownership check and a one-use bridge proof. Proofs are scoped to the helper connection and cannot authorize permission, sandbox or configuration overrides. Duplicate live owners remain a conflict.

Desktop bridge arbitration records the original request ID, thread ID, winning connection and exact response. A local `pocket/approvalSubmitted` receipt only proves submission. The broker retains the submitted command after closing its card; transport loss or helper restart before confirmed completion leaves it Unknown. The later native `serverRequest/resolved` can also mean cancellation, so it supplies UI-closure evidence only. Current native versions have no positive acknowledgement that an approval response was applied; desktop and CLI outcomes remain Unknown until independently inspected. Winning arbitration prevents a second effective decision but does not itself prove native application. Internal winner, response, process identity and completion fields are not exposed to phones. Generic CLI resolution lacks winner evidence and therefore does not terminally accept a phone decision.

On helper reconnect, authenticated `pocket/approvals/list` recovers still-open shareable requests. The broker assigns fresh opaque mobile tokens; old tokens and stale connection events cannot act on new approvals. Desktop-only tool callbacks remain on the desktop.

## Error and command reconciliation

| Code | Client treatment |
|---|---|
| `unauthorized`, `revoked`, `expired`, `protocol_mismatch` | Authentication/pairing/protocol refusal; reconnect or pair as appropriate. |
| `unsupported`, `invalid_params`, `busy`, `rejected` | Definite refusal before dispatch or explicit native rejection. Show the fixed safe message. |
| `unknown` | Dispatch/completion is uncertain. Preserve the journal and draft; never replay automatically. |
| `not_found` from commands/status | No retained record. This is not evidence that a previous command never ran. |

Unknown native errors remain conservative. Diagnostics and prior rejected-result text are redacted from public errors. Duplicate accepted command IDs return the persisted result; submitted/unknown IDs cannot execute again. New-thread operations without a native ID also have a local journal entry and a global recovery notice. Manual reconciliation binds the inspected command set and chat generation, refreshes authoritative native history/directory/devices as applicable, and records inspection without replaying the original action.

While any `unknown` command remains in the journal (`uncertainSend`), mutating chat actions stay disabled with the reason "先确认上一条消息的发送结果" until the user runs 核对原生历史.

## Answer directives

Native agent messages may contain `:codex-<name>{key="value" ...}` or `::codex-<name>{...}` directives. The phone strips them outside fenced code. `codex-file-citation` entries become file chips (deduplicated by path); other directive names are dropped from display. Directives inside code fences are shown verbatim.
