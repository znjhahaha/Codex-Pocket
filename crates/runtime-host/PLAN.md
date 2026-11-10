# Runtime Host attachment and recovery

Scope: only this standalone crate; no staging, commits, agent edits, root manifest edits, or real model runs.

1. Preserve the existing lifetime test and add failing fixture assertions for capabilities, process identity, generation fencing, and durable recovery.
2. Separate shared protocol/client code from the optional server dependency on agent storage. A broker uses `default-features = false` and communicates over authenticated loopback IPC.
3. Persist bounded event, command, run, native-request, and answer receipts atomically. Reserve before native writes; uncertain operations are never replayed. Recovered runs require reconciliation after a host generation changes.
4. Verify discovery using the private descriptor, configuration home, protocol, generation, host PID identity, and native PID creation identity. Discovery never launches a process; unreachable ownership never authorizes a duplicate launch.
5. Test fixture disconnect/reattach, stale identity/generation, pending question answer deduplication, limits, corrupt snapshots, and recovery after fixture termination. Verify client-only dependency graph and builds; document concrete broker API and remaining production gates.
