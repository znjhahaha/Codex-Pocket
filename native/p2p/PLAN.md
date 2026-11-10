# P2P first adapter step

Approved scope: native/p2p, independent crates/p2p workspace, new DataChannel JNI cpp and Kotlin transport. Existing README/probe remain usable. No root Cargo/Gradle/wire edits, public networking, production changes, new services, staging or commits.

- [x] Native shared C ABI: one worker per peer, generation tagged offer/answer/candidate, fixed pocket.tls.bytes.v1 reliable ordered channel; pull byte stream, bounded byte queues and terminal wakeups. Callback lifetime uses a handle registry, no unowned RTC user pointer.
- [x] Real two-peer fixture: loopback ICE only, bidirectional multi-message stream and partial reads, 1 MiB admission/256 KiB resume, invalid generation, close/error, callback threads and teardown. Keep the old probe target.
- [x] Rust independent workspace: bounded native queues with race-free read/write/flush/shutdown wakers, signal callback, offline tests on real peers; no changes to root Cargo.
- [x] JNI and Kotlin BinaryTransport: native buffer ownership and exception handling; listener callbacks off main; atomic send acceptance (false means no bytes accepted), bounded receive drain; no production connection/Gradle changes.
- [x] Build Windows fixture and Rust offline in separate directories; cross-compile Android JNI and compile/run Kotlin JNI on host if tools are cached. Report compile/runtime gaps and manual wiring in ADAPTER.md.

Queue admission counts queued application bytes plus RTC buffered bytes. Receive overflow fails the connection rather than discarding ciphertext. Each RTC send <= min(16384, rtcMaxMessageSize). Close permits bounded drain but does not claim remote acknowledgment. Upper layer verifies authenticated signaling and current negotiation generation before invoking the adapter. The adapter does not implement or replace Pocket TLS certificate or device authentication.
