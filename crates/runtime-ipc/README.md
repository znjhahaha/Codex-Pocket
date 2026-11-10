# Pocket Runtime IPC

Agent-independent trusted local Runtime Host client, wire protocol and descriptor validation, extracted from `pocket-runtime-host`. The package has **no dependency on `pocket-agent` or `pocket-runtime-host`, including optional, development and platform dependencies**, and no server feature. It uses `pocket-core` for the existing process creation identity type. It does not launch processes, write recovery records or resolve unknown operations.

This is an integration foundation, not product acceptance. Protocol remains **2**; host recovery schema remains **2**. JSON field names, frame limits, error behavior, operation IDs and process identity checks are unchanged.

## Consumer API

```toml
[dependencies]
pocket-runtime-ipc = { path = "../runtime-ipc" }
```

Use the package directly from the trusted broker/agent. No feature switch is needed to remove agent dependencies:

```rust,ignore
use pocket_runtime_ipc::{Discovery, HostClient, load_descriptor};

// Paths must be absolute, existing configuration must be the intended profile.
match HostClient::discover(&descriptor_path, &config_home).await? {
    Discovery::Missing => { /* No descriptor; discovery itself never launches. */ }
    Discovery::Unavailable { .. } => { /* Ownership remains uncertain; do not launch a duplicate. */ }
    Discovery::Attached(mut client) => {
        // Subscribe first, then replay; keep draining this bounded receiver.
        let mut events = client.subscribe();
        let health = client.health().await?;
        let replay = client.replay(&health.generation, saved_sequence).await?;
        if replay.snapshot_required || health.reconciliation_required {
            // Read actual native session state through the product's trusted
            // reconciliation path. That product path is not implemented here.
        }
        // Persist an authorized opaque operation ID in the broker's own ledger
        // before client.request(id, native_method, native_params).await.
    }
}
```

`HostClient` has a single mutable command stream. A broker can own it in one task and forward authorized work to that task, while subscribers drain events separately. Cancellation, timeout and disconnect make a dispatch uncertain; consult its existing receipt and recovery evidence. Never allocate a new ID merely to retry an unknown dispatch. A cold completed receipt may have no response body.

| Method / type | Contract |
| --- | --- |
| `load_descriptor(path, config_home)` | Read-only descriptor parsing, protocol/config/path validation; does not prove liveness or alter ACLs |
| `HostClient::discover(path, config_home)` | Missing, attached, or unavailable existing host; no process launcher |
| `HostClient::attach(&Descriptor)` | Validates descriptor, both PID/creation identities, loopback authentication and health; possession of a constructed descriptor does not grant product authorization |
| `runtime_id()` / `generation()` | Current attachment identity |
| `subscribe()` | Bounded broadcast receiver; lag/disconnect requires replay or a trusted snapshot |
| `health()` -> `Health` | Confirms protocol, generation, capabilities and live creation identities |
| `replay(generation, after)` -> `Replay` | Memory event window, cursor and snapshot-required indication; never reissues native work |
| `request(id, method, params)` -> JSON reply | Forward one already-authorized native command; preserve opaque durable operation IDs |
| `receipt(id)` -> `Receipt` | Original generation and submitted/completed/unknown/missing marker; completed is not business success |
| `respond(request_id, response)` -> `Receipt` | Current-generation live pending request only, exactly one result/error member |
| `recovery()` -> JSON evidence | Retained run/pending metadata with original generations; not executable requests |
| `shutdown_idle()` | Explicit host idle shutdown request, subject to server busy checks |
| `read_frame` / `write_frame` | Newline JSON framing, 4 MiB including newline, fixed parse errors; read in a dedicated reader task |

Public types and functions are exported at the crate root and in `client` / `protocol`. The old `pocket_runtime_host::client::*`, `pocket_runtime_host::protocol::*` and host root `Descriptor` / frame functions reexport the same definitions, not copies. `support` is hidden implementation support for shared frame/ID bounds and read-only physical-path checks; it is not an authorization, journal mutation or reconciliation interface.

## Trust and recovery boundaries

Only an already-authorized local broker may use this API. Do not expose descriptor tokens or arbitrary native methods/configuration to phones. Debug and parse errors redact descriptor contents. Discovery preserves symlink/reparse checks, physical opened-file identity and explicit Windows `Packages/.../LocalCache/...` rejection. Windows ACL provisioning remains the host/server's responsibility; descriptor loading never changes ACLs. Connection loss is not native termination evidence.

There is deliberately **no reconciliation mutation API**. A caller's claim that a run finished, a timeout or a disconnected transport cannot prove the outcome of an old command/answer reservation. The future product path must obtain actual native evidence and bind it to the original runtime, generation, operation and native thread/turn/request before resolving each item. It must preserve the non-replay rule and atomically update only proven records. `reconciliationRequired` remains visible meanwhile.

## Root/agent integration handoff

The next owner must add the direct dependency to agent/NativeConnection, route authorized requests and subscriptions, and integrate the host into root builds and installation generations. Both scoped packages currently have standalone `[workspace]` declarations so they build without root manifest edits. If adding them as members of the root workspace, remove/adjust those declarations and deliberately select the root lockfile policy; do not simply add members and leave nested workspace roots. Host's optional server-to-agent storage edge still exists, so agent must consume **IPC directly**, not depend on the host package with features disabled.

## Deterministic verification

From the repository root:

```powershell
cargo test --offline --manifest-path crates/runtime-ipc/Cargo.toml --target-dir crates/runtime-ipc/target
cargo check --offline --manifest-path crates/runtime-ipc/Cargo.toml --target-dir crates/runtime-ipc/target --all-features --all-targets
cargo test --offline --manifest-path crates/runtime-host/Cargo.toml --target-dir crates/runtime-host/target
cargo test --offline --manifest-path crates/runtime-host/Cargo.toml --target-dir crates/runtime-host/target --no-default-features --test ipc_compat --test package_graph -- --nocapture
```

The `package_graph` test runs offline Cargo metadata, verifies resolved IPC reachability under the all-feature host (with the agent server edge genuinely active), checks every declared transitive path edge irrespective of optional/dev/target activation, and checks standalone IPC and host client-only graphs. Tests use synthetic descriptors, memory framing and the host's own fake app-server. They do not generate model output or control existing native/model processes. Native end-to-end recovery, root feature integration, installation, ACL attacks and product acceptance remain separate gates.
