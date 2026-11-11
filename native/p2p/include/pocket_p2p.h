#ifndef POCKET_P2P_H
#define POCKET_P2P_H
#include <stdint.h>
#ifdef _WIN32
#ifdef POCKET_P2P_BUILD
#define P2P_API __declspec(dllexport)
#else
#define P2P_API __declspec(dllimport)
#endif
#else
#define P2P_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif
#define P2P_PROTOCOL "pocket.tls.bytes.v1"
#define P2P_HIGH 1048576
#define P2P_LOW 262144
#define P2P_CHUNK 16384
enum { P2P_AGAIN=-1, P2P_CLOSED=-2, P2P_FAILED=-3, P2P_STALE=-4, P2P_INVALID=-5 };
enum { P2P_OPEN=1, P2P_READABLE=2, P2P_WRITABLE=3, P2P_END=4, P2P_ERROR=5, P2P_DESCRIPTION=6, P2P_CANDIDATE=7 };
/* Strings live only through callback return. All callbacks on a peer worker, never caller/main.
 * Do not block a callback, throw across ABI, or call destroy from it. read/write/close are safe.
 * Authenticate remote signaling and validate active generation BEFORE calling remote_*.
 * No STUN/TURN configuration in this step. NULL bind means host ICE; fixture uses 127.0.0.1.
 * Handles are process local. create does not negotiate; call start explicitly once.
 * One remote SDP and <=256 candidates/1 MiB cumulative remote signaling per generation.
 * A new negotiation requires a new handle. TX/RX also cap message entries at 1024.
 */
typedef void (*p2p_callback)(void*, uint64_t handle, uint64_t generation, int event, const char *first, const char *second);
P2P_API uint64_t p2p_create(uint64_t generation, const char *bind_address, p2p_callback cb, void *user);
/* Optional single STUN URL; nullptr disables discovery. TURN/credentials are not accepted. */
P2P_API uint64_t p2p_create_config(uint64_t generation, const char *bind_address, const char *stun_url, p2p_callback cb, void *user);
P2P_API int p2p_start(uint64_t handle, uint64_t generation, int offerer);
P2P_API int p2p_remote_description(uint64_t handle, uint64_t generation, const char *sdp, const char *type);
P2P_API int p2p_remote_candidate(uint64_t handle, uint64_t generation, const char *candidate, const char *mid);
/* write atomically copies 1..65536 bytes or accepts none (AGAIN). Thread-safe, nonblocking.
 * read copies stream bytes, independent of DataChannel boundaries; 0 means clean EOF.
 * error/abort terminates reads, even if data remains. flush=0 means local RTC buffer drained,
 * not remote delivery or TLS/device authentication. status=OPEN/END/ERROR or 0 connecting.
 */
P2P_API int p2p_write(uint64_t handle, const uint8_t *bytes, int length);
P2P_API int p2p_read(uint64_t handle, uint8_t *bytes, int capacity);
P2P_API int p2p_flush(uint64_t handle);
P2P_API int p2p_status(uint64_t handle);
P2P_API int p2p_close(uint64_t handle, int graceful);
/* No callbacks after destroy returns; may join a worker. Never calls global rtcCleanup. */
P2P_API void p2p_destroy(uint64_t handle);
typedef struct { uint64_t tx_bytes, rx_bytes, peak_tx, peak_rx, max_message; } p2p_stats;
P2P_API int p2p_get_stats(uint64_t handle, p2p_stats *stats);
#ifdef __cplusplus
}
#endif
#endif
