// Local C API transport probe. No model, public STUN, TURN, relay or Pocket state.
// ICE/DTLS success here does not prove Pocket TLS identity or Android interoperability.
#include <rtc/rtc.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <iostream>
#include <thread>

using Clock = std::chrono::steady_clock;
struct Peer {
    int pc = -1;
    std::atomic<int> channel{-1};
    Peer *other = nullptr;
    bool echo = false;
    std::atomic<bool> failed{false};
    std::atomic<size_t> received{0};
};
static void description(int, const char *sdp, const char *type, void *ptr) {
    auto *p = static_cast<Peer *>(ptr);
    if (rtcSetRemoteDescription(p->other->pc, sdp, type) < 0) p->failed = true;
}
static void candidate(int, const char *cand, const char *mid, void *ptr) {
    auto *p = static_cast<Peer *>(ptr);
    if (rtcAddRemoteCandidate(p->other->pc, cand, mid) < 0) p->failed = true;
}
static void message(int dc, const char *bytes, int size, void *ptr) {
    auto *p = static_cast<Peer *>(ptr);
    if (size <= 0 || size > 16384) { p->failed = true; return; }
    const auto offset = p->received.load();
    for (int i = 0; i < size; ++i) {
        if (static_cast<unsigned char>(bytes[i]) != ((offset + i) % 251)) { p->failed = true; return; }
    }
    if (p->echo && rtcSendMessage(dc, bytes, size) < 0) { p->failed = true; return; }
    p->received += size;
}
static void channel(int, int dc, void *ptr) {
    auto *p = static_cast<Peer *>(ptr);
    rtcSetUserPointer(dc, p);
    if (rtcSetMessageCallback(dc, message) < 0) p->failed = true;
    p->channel = dc;
}
template<class F> static bool wait(Peer &a, Peer &b, F done, int seconds = 15) {
    const auto deadline = Clock::now() + std::chrono::seconds(seconds);
    while (!done()) {
        if (a.failed || b.failed || Clock::now() >= deadline) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return !a.failed && !b.failed;
}
int main() {
    rtcInitLogger(RTC_LOG_NONE, nullptr);
    Peer a, b; a.other = &b; b.other = &a; b.echo = true;
    rtcConfiguration config{};
    config.bindAddress = "127.0.0.1";
    config.maxMessageSize = 16384;
    const auto start = Clock::now();
    a.pc = rtcCreatePeerConnection(&config); b.pc = rtcCreatePeerConnection(&config);
    if (a.pc < 0 || b.pc < 0) { std::cerr << "peer_create_failed\n"; return 1; }
    for (auto *p : {&a, &b}) {
        rtcSetUserPointer(p->pc, p);
        if (rtcSetLocalDescriptionCallback(p->pc, description) < 0 ||
            rtcSetLocalCandidateCallback(p->pc, candidate) < 0 ||
            rtcSetDataChannelCallback(p->pc, channel) < 0) p->failed = true;
    }
    rtcDataChannelInit init{}; // reliable and ordered; no retransmission/time limit
    init.protocol = "pocket.transport.probe.v1";
    const int dc = rtcCreateDataChannelEx(a.pc, "fixture-only", &init);
    if (dc < 0) a.failed = true; else channel(a.pc, dc, &a);
    bool ok = wait(a,b,[&]{ return a.channel >= 0 && b.channel >= 0 && rtcIsOpen(a.channel) && rtcIsOpen(b.channel); });
    const auto connected = Clock::now();
    size_t transferred = 0; int max_buffered = 0;
    if (ok) {
        const int maximum = std::min(16384, rtcMaxMessageSize(dc));
        rtcReliability reliability{};
        ok = maximum > 0 && rtcGetDataChannelReliability(dc, &reliability) == 0 && !reliability.unordered && !reliability.unreliable;
        std::array<char,16384> block{};
        constexpr size_t total = 1024 * 1024;
        while (ok && transferred < total) {
            int length = static_cast<int>(std::min(static_cast<size_t>(maximum), total - transferred));
            for (int i = 0; i < length; ++i) block[i] = static_cast<char>((transferred + i) % 251);
            const int amount = rtcGetBufferedAmount(dc);
            if (amount < 0 || amount > 262144) { ok = false; break; }
            max_buffered = std::max(max_buffered, amount);
            if (rtcSendMessage(dc,block.data(),length) < 0) { ok = false; break; }
            transferred += length;
            // One bounded outstanding block makes the fixture's reverse path bounded too.
            ok = wait(a,b,[&]{ return a.received == transferred && b.received == transferred; });
        }
    }
    const auto finish = Clock::now();
    rtcClosePeerConnection(a.pc); rtcClosePeerConnection(b.pc);
    rtcDeletePeerConnection(a.pc); rtcDeletePeerConnection(b.pc); rtcCleanup();
    std::cout << "{\"fixture\":\"windows_loopback_datachannel\",\"success\":" << (ok?"true":"false")
        << ",\"bytesEachDirection\":" << transferred << ",\"connectMs\":"
        << std::chrono::duration_cast<std::chrono::milliseconds>(connected-start).count()
        << ",\"transferMs\":" << std::chrono::duration_cast<std::chrono::milliseconds>(finish-connected).count()
        << ",\"maxObservedSendBuffer\":" << max_buffered
        << ",\"phoneE2EVerified\":false,\"pocketTlsVerified\":false}\n";
    return ok ? 0 : 1;
}
