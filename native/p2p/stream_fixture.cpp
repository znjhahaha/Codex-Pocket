#include "pocket_p2p.h"
#include <atomic>
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>
using namespace std::chrono_literals;
static void check(bool value, const char *why) { if (!value) throw std::runtime_error(why); }
struct Endpoint {
    uint64_t handle=0, other=0, generation=19;
    std::thread::id caller;
    std::atomic<bool> callback_ok{true}, signal_ok{true};
    std::atomic<int> terminal{0};
    static void event(void *ctx, uint64_t, uint64_t gen, int ev, const char *a, const char *b) {
        auto &p=*static_cast<Endpoint*>(ctx);
        if (std::this_thread::get_id()==p.caller || gen!=p.generation) p.callback_ok=false;
        if (ev==P2P_DESCRIPTION && p2p_remote_description(p.other,gen,a,b)!=0) p.signal_ok=false;
        if (ev==P2P_CANDIDATE && p2p_remote_candidate(p.other,gen,a,b)!=0) p.signal_ok=false;
        if (ev==P2P_END || ev==P2P_ERROR) ++p.terminal;
    }
    ~Endpoint(){p2p_destroy(handle);}
};
template<class F> static void wait(F f,const char *why) {
    auto until=std::chrono::steady_clock::now()+15s;
    while(!f()){check(std::chrono::steady_clock::now()<until,why);std::this_thread::sleep_for(1ms);}
}
static void transfer(Endpoint &a,Endpoint &b,size_t total) {
    size_t sent=0,got=0; std::vector<uint8_t> out(65536),in(997);
    auto until=std::chrono::steady_clock::now()+20s;
    while(got<total){
        check(std::chrono::steady_clock::now()<until,"transfer timeout");
        if(sent<total && sent-got<262144){
            int n=int(std::min(out.size(),total-sent));
            for(int i=0;i<n;++i)out[i]=uint8_t((sent+i)%251);
            int r=p2p_write(a.handle,out.data(),n);
            check(r==n || r==P2P_AGAIN,"write failed");if(r>0)sent+=size_t(r);
        }
        int r=p2p_read(b.handle,in.data(),int(in.size()));
        check(r>0 || r==P2P_AGAIN,"read failed");
        for(int i=0;i<std::max(r,0);++i)check(in[i]==uint8_t((got+i)%251),"stream order mismatch");
        if(r>0)got+=size_t(r);else std::this_thread::sleep_for(1ms);
        p2p_stats st{};check(p2p_get_stats(a.handle,&st)==0,"stats");
        check(st.tx_bytes<=P2P_HIGH && st.peak_tx<=P2P_HIGH,"TX bound");
    }
    wait([&]{return p2p_flush(a.handle)==0;},"flush timeout");
}
int main(){try{
    Endpoint a,b; a.caller=b.caller=std::this_thread::get_id();
    a.handle=p2p_create(a.generation,"127.0.0.1",Endpoint::event,&a);
    b.handle=p2p_create(b.generation,"127.0.0.1",Endpoint::event,&b);
    check(a.handle && b.handle,"create");a.other=b.handle;b.other=a.handle;
    check(p2p_start(a.handle,18,1)==P2P_STALE,"stale start");
    check(p2p_remote_candidate(a.handle,18,"invalid","0")==P2P_STALE,"stale candidate");
    std::vector<uint8_t> block(65536,7);
    for(int i=0;i<16;++i)check(p2p_write(a.handle,block.data(),65536)==65536,"preopen queue");
    check(p2p_write(a.handle,block.data(),1)==P2P_AGAIN,"high watermark");
    p2p_close(a.handle,0);check(p2p_write(a.handle,block.data(),1)==P2P_CLOSED,"close writer wake");
    wait([&]{return a.terminal==1;},"close callback");
    p2p_destroy(a.handle);
    a.handle=p2p_create(a.generation,"127.0.0.1",Endpoint::event,&a);b.other=a.handle;a.terminal=0;
    // Fill before negotiation so the high watermark is deterministic, then prove an actual
    // low-water resume on the real transport while the other peer consumes stream bytes.
    for(int k=0;k<16;++k){
        for(size_t i=0;i<block.size();++i)block[i]=uint8_t((size_t(k)*block.size()+i)%251);
        check(p2p_write(a.handle,block.data(),int(block.size()))==int(block.size()),"resume fill");
    }
    uint8_t tail=uint8_t(P2P_HIGH%251);
    check(p2p_write(a.handle,&tail,1)==P2P_AGAIN,"resume blocked");
    check(p2p_start(b.handle,b.generation,0)==0,"answerer start");
    check(p2p_start(a.handle,a.generation,1)==0,"offerer start");
    wait([&]{return p2p_status(a.handle)==P2P_OPEN && p2p_status(b.handle)==P2P_OPEN;},"open");
    size_t resumed_received=0;bool resumed=false;std::vector<uint8_t> small(997);
    wait([&]{
        if(!resumed){int r=p2p_write(a.handle,&tail,1);check(r==1 || r==P2P_AGAIN,"resume write");
            if(r==1){p2p_stats st{};p2p_get_stats(a.handle,&st);check(st.tx_bytes<=P2P_LOW+1,"low watermark");resumed=true;}}
        int r=p2p_read(b.handle,small.data(),int(small.size()));check(r>0 || r==P2P_AGAIN,"resume read");
        for(int i=0;i<std::max(r,0);++i)check(small[i]==uint8_t((resumed_received+i)%251),"resume stream");
        if(r>0)resumed_received+=size_t(r);
        return resumed && resumed_received==P2P_HIGH+1;
    },"low-water resume timeout");
    transfer(a,b,2*P2P_HIGH+733);transfer(b,a,2*P2P_HIGH+271);
    check(a.callback_ok && b.callback_ok && a.signal_ok && b.signal_ok,"callbacks/signals");
    p2p_stats sa{},sb{};p2p_get_stats(a.handle,&sa);p2p_get_stats(b.handle,&sb);
    check(sa.max_message<=P2P_CHUNK && sb.max_message<=P2P_CHUNK,"RTC message limit");
    p2p_close(a.handle,1);wait([&]{return p2p_status(a.handle)==P2P_END;},"graceful close");
    wait([&]{return p2p_status(b.handle)==P2P_END;},"remote close");
    uint8_t byte;check(p2p_read(b.handle,&byte,1)==0,"EOF");
    Endpoint c,d;c.caller=d.caller=std::this_thread::get_id();
    c.handle=p2p_create(c.generation,"127.0.0.1",Endpoint::event,&c);
    d.handle=p2p_create(d.generation,"127.0.0.1",Endpoint::event,&d);c.other=d.handle;d.other=c.handle;
    check(c.handle && d.handle,"overflow create");
    check(p2p_start(d.handle,d.generation,0)==0 && p2p_start(c.handle,c.generation,1)==0,"overflow start");
    wait([&]{return p2p_status(c.handle)==P2P_OPEN && p2p_status(d.handle)==P2P_OPEN;},"overflow open");
    size_t pushed=0;
    wait([&]{
        if(pushed<2*P2P_HIGH){int r=p2p_write(c.handle,block.data(),int(block.size()));
            if(r>0)pushed+=size_t(r);else check(r==P2P_AGAIN || p2p_status(d.handle)==P2P_ERROR,"overflow send");}
        return p2p_status(d.handle)==P2P_ERROR;
    },"receive overflow timeout");
    p2p_stats sd{};p2p_get_stats(d.handle,&sd);check(sd.peak_rx<=P2P_HIGH,"RX bound");
    check(p2p_read(d.handle,&byte,1)==P2P_FAILED,"overflow wakes reader");
    uint64_t gone=c.handle;std::thread destroyer([&]{p2p_destroy(gone);});p2p_destroy(gone);destroyer.join();c.handle=0;
    check(p2p_write(gone,&byte,1)==P2P_CLOSED,"late handle");
    std::cout<<"{\"fixture\":\"local_two_peers_byte_stream\",\"success\":true,\"bytesForward\":"<<2*P2P_HIGH+733
        <<",\"bytesReverse\":"<<2*P2P_HIGH+271<<",\"peakTx\":"<<std::max(sa.peak_tx,sb.peak_tx)
        <<",\"maxMessage\":"<<std::max(sa.max_message,sb.max_message)<<",\"highLowResume\":true,\"rxOverflowFailsClosed\":true,\"phoneE2EVerified\":false,\"pocketTlsVerified\":false}\n";
    return 0;
}catch(const std::exception &e){std::cerr<<e.what()<<'\n';return 1;}}
