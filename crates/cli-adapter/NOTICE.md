This adapter includes code adapted from OpenAI Codex, tag rust-v0.164.0,
licensed under Apache License 2.0:

- src/windows_peer.rs: codex-rs/uds/src/windows_peer.rs (unchanged logic).
- src/windows_socket_validation.rs: codex-rs/uds/src/windows_socket_validation.rs
  (unchanged logic).
- src/windows_security.rs: read-only token helpers from
  codex-rs/uds/src/windows_security.rs, with directory creation omitted.
- src/local.rs: Windows stream wrapper and Unix protected rendezvous validation
  adapted from codex-rs/uds and codex-rs/app-server-transport.

The version-pinned inputs are in research/upstream. This library adds passive
metadata discovery, canonical store identity validation, bounded JSON-RPC I/O,
and adapter-specific tests. Native config/auth and daemon lifecycle remain owned
by the original CLI.
