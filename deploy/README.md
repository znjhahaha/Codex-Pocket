# Pocket opaque relay deployment template

This template supports a self-hosted Linux relay with Docker Compose and HTTPS.
Production host addresses, authentication configuration and deployment receipts
are kept outside source control. The 0.4 history/backpressure acceptance scope is
summarized in [the public status document](../docs/STATUS.md).

## First installation

Build the Windows delivery, then initialize private state once on the computer
that will own the sessions. Use your HTTPS relay hostname in place of the example:

```powershell
$pocketState = Join-Path $env:LOCALAPPDATA 'CodexPocket\agent'
.\windows\pocket-agent.exe prepare-state --state $pocketState
.\windows\pocket-agent.exe init --state $pocketState --relay 'wss://relay.example.com/pocket'
```

Run these commands from the delivery directory. `init` refuses to replace an
existing configuration; upgrades keep the original state. It generates
`relay-config.json` in the protected state directory. Transfer that configuration
privately to `deploy/relay.json` on the relay host. It contains matching room and
role-token hashes, so there is no need to copy the helper's private keys or raw
tokens to the server. `deploy/relay.json` is Git-ignored.

After the relay and HTTPS proxy are ready, follow
[the Windows guide](../docs/windows-user-guide.md) to install the helper and pair
the phone. The source repository does not include access to a shared relay.

## Container and HTTPS setup

Build from the project root with `docker compose -f deploy/compose.yaml build`.
The Dockerfile uses Rust 1.93.1, builds only `pocket-relay`, and requires the root
Cargo.lock. Its Dockerfile-specific ignore file excludes the local research,
native runtime probes, identity directories, and worker logs from the context.
The complete workspace crate sources remain available for Cargo manifest
resolution. No TLS identity or provider credential belongs in this image.

Alternatively, create `deploy/relay.json` from `relay.example.json` when managing
the corresponding helper configuration yourself. Replace every placeholder.
Use two distinct cryptographically random 256-bit role tokens. Store only their
lowercase SHA256 hex digests in this file. The tokens themselves belong only in
the helper's protected configuration and the pairing QR. Do not print tokens,
QR contents, request headers, or payloads in logs. The example deliberately
does not contain valid hashes, and the relay refuses to start with it unchanged.

The config must be readable by container uid 65532 (for example host owner
65532, mode 0400), while its parent directory prevents unrelated host users from
reading it. Keep backups private. Use `bind: "0.0.0.0:8080"` in the container;
the host publication remains `127.0.0.1:18080`. The relay binary alone defaults
to host loopback `127.0.0.1:8080` when bind is omitted. It accepts one config
path argument or `POCKET_RELAY_CONFIG`. Invalid configuration produces only a
fixed error message, never echoed JSON, token hashes, paths, or room names.

Use the Caddy handler example inside the existing HTTPS site. A QR relay URL
with `/pocket` prefix maps to `/pocket/v1/tunnel/{room}/{role}` externally and
`/v1/tunnel/{room}/{role}` at the relay. Caddy carries outer TLS and WebSocket
upgrades; the relay never terminates the independent inner TLS. If Caddy runs
in Docker, connect Caddy and the relay to an existing private Docker network,
remove `ports`, and use `reverse_proxy pocket-relay:8080`. The supplied compose
uses host Caddy plus a private internal relay network; choose the arrangement
that matches the parent server. Do not publish relay port 8080 on all host
interfaces. Do not enable access/request-body logging on this route unless the
parent first verifies Authorization, query strings, and identifiers are redacted.
The application itself has no request or payload logger and never spools data.

`GET /healthz` returns exactly `{"status":"ok"}`. It indicates the HTTP
listener is running, not that a helper or phone is online. Compose checks it
through localhost inside the container. Container limits are 128 MiB RAM,
0.5 CPU, 64 PIDs, two Tokio workers, a read-only filesystem, no capabilities,
and no privilege escalation. Default one-room outbound queues retain at most
about 4.5 MiB of payload (9 peers x 8 entries x 65553 bytes), plus in-flight
messages, socket buffers, and process overhead. Raise the memory limit if
configuring many rooms or larger queues: 32 rooms with 32-slot queues can
require roughly 576 MiB of queued payload alone. No slow peer causes silent
byte loss: transient queue saturation waits for up to five seconds without
holding the room lock. Persistently stalled peers are disconnected and affected
TLS sessions must reconnect; socket reads and writes drain independently.

Authentication uses only one `Authorization: Bearer ...` header; any query
string is rejected. Config supports 1..32 rooms, 1..32 queue slots per peer,
one helper and eight phones per room. Socket writes time out after five
seconds; idle peers are probed every 20 seconds and disconnected after 60
seconds without inbound data/control frames. SIGTERM/Ctrl+C cancels all rooms.

Do not automatically retry application mutations after a transport disconnect:
their outcome may be unknown. Native command IDs and outcome reconciliation
are implemented by the parent helper, above this byte transport.
