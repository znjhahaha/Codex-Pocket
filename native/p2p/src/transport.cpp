#include "pocket_p2p.h"
#include <rtc/rtc.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {
using Clock=std::chrono::steady_clock;
struct Peer;
std::mutex registry_mutex;
std::unordered_map<uint64_t,std::shared_ptr<Peer>> peers;
std::unordered_map<int,std::weak_ptr<Peer>> rtc_peers;
uint64_t next_handle=0;
std::shared_ptr<Peer> get(uint64_t h) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto i=peers.find(h);return i==peers.end()?nullptr:i->second;
}
std::shared_ptr<Peer> from_rtc(int id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto i=rtc_peers.find(id);return i==rtc_peers.end()?nullptr:i->second.lock();
}
void register_rtc(int id,const std::shared_ptr<Peer> &p){std::lock_guard<std::mutex> lock(registry_mutex);rtc_peers[id]=p;}
void unregister_rtc(int id){std::lock_guard<std::mutex> lock(registry_mutex);rtc_peers.erase(id);}
bool valid_string(const char *s,size_t limit) {
    if(!s)return false;
    for(size_t i=0;i<=limit;++i)if(s[i]=='\0')return i>0;
    return false;
}
struct Item { int kind; std::string a,b; int channel=-1; };
struct Peer:std::enable_shared_from_this<Peer> {
    uint64_t handle,generation;
    std::string bind,stun;
    p2p_callback callback;void *user;
    std::mutex mutex;std::condition_variable cv;std::thread worker;
    int pc=-1,dc=-1,state=0,end=0;bool ready=false,initialized=false,started=false,offerer=false;
    bool closing=false,graceful=false,blocked=false,readable=false,writable=false,opened=false,destroying=false,destroyed=false;
    size_t tx_bytes=0,rx_bytes=0,buffered=0,tx_offset=0,rx_offset=0;
    size_t peak_tx=0,peak_rx=0,max_message=0,signal_bytes=0,command_bytes=0;
    size_t remote_signal_bytes=0,remote_candidates=0;bool remote_description_seen=false;
    std::deque<std::vector<uint8_t>> tx,rx;
    std::deque<Item> commands,signals;
    Clock::time_point close_deadline;
    Peer(uint64_t h,uint64_t g,const char *addr,const char *stun_url,p2p_callback cb,void *u):
        handle(h),generation(g),bind(addr?addr:""),stun(stun_url?stun_url:""),callback(cb),user(u){}
    void fail() noexcept {
        std::lock_guard<std::mutex> lock(mutex);
        if(state==P2P_END || state==P2P_ERROR)return;
        end=P2P_ERROR;closing=true;graceful=false;cv.notify_all();
    }
    void remote_end() noexcept {
        std::lock_guard<std::mutex> lock(mutex);
        if(state==P2P_END || state==P2P_ERROR)return;
        if(!end)end=P2P_END;
        closing=true;graceful=false;cv.notify_all();
    }
    void signal(int kind,const char *a,const char *b) {
        try {
            if(!valid_string(a,kind==P2P_DESCRIPTION?65536:4096) || !valid_string(b,256)){fail();return;}
            Item item{kind,a,b};size_t n=item.a.size()+item.b.size();
            std::lock_guard<std::mutex> lock(mutex);
            if(closing)return;
            if(signals.size()>=256 || signal_bytes+n>P2P_HIGH){end=P2P_ERROR;closing=true;graceful=false;}
            else {signals.push_back(std::move(item));signal_bytes+=n;}
            cv.notify_all();
        }catch(...){fail();}
    }
    static void description(int id,const char *s,const char *t,void*){if(auto p=from_rtc(id))p->signal(P2P_DESCRIPTION,s,t);}
    static void candidate(int id,const char *c,const char *m,void*){if(auto p=from_rtc(id))p->signal(P2P_CANDIDATE,c,m);}
    static void state_change(int id,rtcState s,void*) {
        if(auto p=from_rtc(id)){
            if(s==RTC_FAILED || s==RTC_DISCONNECTED)p->fail();
            else if(s==RTC_CLOSED)p->remote_end();
        }
    }
    static void closed(int id,void*){if(auto p=from_rtc(id))p->remote_end();}
    static void error(int id,const char*,void*){if(auto p=from_rtc(id))p->fail();}
    static void low(int id,void*){if(auto p=from_rtc(id))p->cv.notify_all();}
    static void open(int id,void*) {
        if(auto p=from_rtc(id)){
            std::lock_guard<std::mutex> lock(p->mutex);
            if(!p->closing && p->state!=P2P_OPEN){p->state=P2P_OPEN;p->opened=true;p->writable=true;p->cv.notify_all();}
        }
    }
    static void message(int id,const char *data,int n,void*) {
        if(auto p=from_rtc(id))try {
            std::lock_guard<std::mutex> lock(p->mutex);
            if(p->closing)return;
            if(n<=0 || n>P2P_CHUNK || p->rx.size()>=1024 || p->rx_bytes+size_t(n)>P2P_HIGH){p->end=P2P_ERROR;p->closing=true;p->graceful=false;}
            else {
                p->rx.emplace_back(data,data+n);p->rx_bytes+=size_t(n);
                p->peak_rx=std::max(p->peak_rx,p->rx_bytes);p->readable=true;
            }
            p->cv.notify_all();
        }catch(...){p->fail();}
    }
    static void channel(int id,int ch,void*) {
        if(auto p=from_rtc(id))try {
            std::unique_lock<std::mutex> lock(p->mutex);
            if(p->closing || p->commands.size()>=256){lock.unlock();rtcDeleteDataChannel(ch);p->fail();return;}
            p->commands.push_back(Item{8,"","",ch});p->cv.notify_all();
        }catch(...){rtcDeleteDataChannel(ch);p->fail();}
        else rtcDeleteDataChannel(ch);
    }
    bool install(int ch) {
        char protocol[64]{},label[64]{};rtcReliability reliability{};
        if(dc>=0 || rtcGetDataChannelProtocol(ch,protocol,sizeof(protocol))<0 ||
           rtcGetDataChannelLabel(ch,label,sizeof(label))<0 ||
           std::strcmp(protocol,P2P_PROTOCOL) || std::strcmp(label,"pocket-tls") ||
           rtcGetDataChannelReliability(ch,&reliability)<0 || reliability.unreliable || reliability.unordered){
            rtcDeleteDataChannel(ch);fail();return false;
        }
        dc=ch;register_rtc(ch,shared_from_this());
        if(rtcSetClosedCallback(ch,closed)<0 || rtcSetErrorCallback(ch,error)<0 ||
           rtcSetBufferedAmountLowThreshold(ch,P2P_LOW)<0 || rtcSetBufferedAmountLowCallback(ch,low)<0 ||
           rtcSetOpenCallback(ch,open)<0 || rtcSetMessageCallback(ch,message)<0){fail();return false;}
        if(rtcIsOpen(ch))open(ch,nullptr);
        return true;
    }
    bool initialize() {
        rtcConfiguration config{};
        const char *server=stun.c_str();
        if(!stun.empty()){config.iceServers=&server;config.iceServersCount=1;}
        config.bindAddress=bind.empty()?nullptr:bind.c_str();config.maxMessageSize=P2P_CHUNK;
        config.disableAutoNegotiation=true; // controller owns start and offer/answer role
        pc=rtcCreatePeerConnection(&config);if(pc<0)return false;
        register_rtc(pc,shared_from_this());
        return rtcSetLocalDescriptionCallback(pc,description)>=0 && rtcSetLocalCandidateCallback(pc,candidate)>=0 &&
            rtcSetStateChangeCallback(pc,state_change)>=0 && rtcSetDataChannelCallback(pc,channel)>=0;
    }
    void notify(int event,const char *a="",const char *b="") {
        if(callback)callback(user,handle,generation,event,a,b);
    }
    void execute(Item &item) {
        int r=0;
        if(item.kind==9){
            if(item.channel){
                rtcDataChannelInit init{};init.protocol=P2P_PROTOCOL;
                int ch=rtcCreateDataChannelEx(pc,"pocket-tls",&init);
                if(ch<0 || !install(ch)){fail();return;}
                r=rtcSetLocalDescription(pc,"offer");
            }
        }else if(item.kind==P2P_DESCRIPTION){
            r=rtcSetRemoteDescription(pc,item.a.c_str(),item.b.c_str());
            if(r>=0 && item.b=="offer")r=rtcSetLocalDescription(pc,"answer");
        }else if(item.kind==P2P_CANDIDATE)r=rtcAddRemoteCandidate(pc,item.a.c_str(),item.b.c_str());
        else if(item.kind==8)install(item.channel);
        if(r<0)fail();
    }
    void update_buffer() {
        if(dc<0)return;
        int n=rtcGetBufferedAmount(dc);if(n<0){fail();return;}
        std::lock_guard<std::mutex> lock(mutex);size_t previous=tx_bytes+buffered;buffered=size_t(n);
        if(blocked && tx_bytes+buffered<=P2P_LOW && tx.size()<=256){blocked=false;writable=true;}
        if(previous>0 && tx_bytes+buffered==0)writable=true; // flush/shutdown waiter
    }
    void send_one() {
        int maximum=rtcMaxMessageSize(dc);if(maximum<=0){fail();return;}
        std::vector<uint8_t> bytes;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if(tx.empty() || state!=P2P_OPEN || (closing && !graceful))return;
            size_t n=std::min(size_t(std::min(P2P_CHUNK,maximum)),tx.front().size()-tx_offset);
            bytes.assign(tx.front().begin()+tx_offset,tx.front().begin()+tx_offset+n);
        }
        // Front bytes stay charged during the RTC call: concurrent admission cannot double spend.
        if(rtcSendMessage(dc,reinterpret_cast<const char*>(bytes.data()),int(bytes.size()))<0){fail();return;}
        int amount=rtcGetBufferedAmount(dc);if(amount<0){fail();return;}
        {
            std::lock_guard<std::mutex> lock(mutex);
            tx_offset+=bytes.size();tx_bytes-=bytes.size();buffered=size_t(amount);
            max_message=std::max(max_message,bytes.size());
            if(tx_offset==tx.front().size()){tx.pop_front();tx_offset=0;}
            if(blocked && tx_bytes+buffered<=P2P_LOW && tx.size()<=256){blocked=false;writable=true;}
            if(!tx_bytes && !buffered)writable=true;
        }
    }
    void cleanup() {
        // Remove callback lookup before closing. In-flight callbacks own shared_ptr and only mutate
        // this peer's state; none can call the application once its worker has joined.
        unregister_rtc(pc);unregister_rtc(dc);
        if(dc>=0){rtcDeleteDataChannel(dc);dc=-1;}
        if(pc>=0){rtcDeletePeerConnection(pc);pc=-1;}
        std::deque<Item> left;
        {std::lock_guard<std::mutex> lock(mutex);left.swap(commands);}
        for(auto &i:left)if(i.kind==8)rtcDeleteDataChannel(i.channel);
    }
    void run() noexcept {
        bool ok=false;try{ok=initialize();}catch(...){}
        {std::lock_guard<std::mutex> lock(mutex);initialized=ok;ready=true;cv.notify_all();}
        if(!ok){cleanup();return;}
        try {
            for(;;){
                update_buffer();
                Item command{},signal{};bool has_command=false,has_signal=false,do_read=false,do_write=false,do_open=false,send=false;
                int terminal=0;
                {
                    std::unique_lock<std::mutex> lock(mutex);
                    if(closing && graceful && Clock::now()>=close_deadline){graceful=false;end=P2P_ERROR;}
                    if(closing && (!graceful || (!tx_bytes && !buffered))){
                        terminal=end?end:P2P_END;state=terminal;tx.clear();tx_bytes=0;buffered=0;
                        if(terminal==P2P_ERROR){rx.clear();rx_bytes=0;}
                        signals.clear();signal_bytes=0;
                    }else{
                        if(!commands.empty()){command=std::move(commands.front());commands.pop_front();command_bytes-=command.a.size()+command.b.size();has_command=true;}
                        if(!signals.empty()){signal=std::move(signals.front());signals.pop_front();signal_bytes-=signal.a.size()+signal.b.size();has_signal=true;}
                        do_read=readable;readable=false;do_write=writable;writable=false;do_open=opened;opened=false;
                        send=state==P2P_OPEN && !tx.empty();
                        if(!has_command && !has_signal && !do_read && !do_write && !do_open && !send){
                            if(buffered || closing)cv.wait_for(lock,std::chrono::milliseconds(10));else cv.wait(lock);
                            continue;
                        }
                    }
                }
                if(terminal){cleanup();notify(terminal);break;}
                if(has_command)execute(command);
                if(has_signal)notify(signal.kind,signal.a.c_str(),signal.b.c_str());
                if(do_open)notify(P2P_OPEN);
                if(do_read)notify(P2P_READABLE);
                if(do_write)notify(P2P_WRITABLE);
                if(send)send_one();
            }
        }catch(...){
            {std::lock_guard<std::mutex> lock(mutex);state=P2P_ERROR;closing=true;tx.clear();rx.clear();tx_bytes=rx_bytes=buffered=0;}
            cleanup();try{notify(P2P_ERROR);}catch(...){}
        }
    }
};
int queue_command(uint64_t h,uint64_t g,int kind,const char *a,const char *b,int channel=-1){
    auto p=get(h);if(!p)return P2P_CLOSED;
    if(g!=p->generation)return P2P_STALE;
    if(kind!=9 && (!valid_string(a,kind==P2P_DESCRIPTION?65536:4096) || !valid_string(b,256)))return P2P_INVALID;
    try{
        Item item{kind,a?a:"",b?b:"",channel};
        std::lock_guard<std::mutex> lock(p->mutex);
        if(p->closing)return P2P_CLOSED;
        if(kind==9){if(p->started)return P2P_INVALID;}
        else if(!p->started)return P2P_INVALID;
        if(kind==P2P_DESCRIPTION && ((item.b!="offer" && item.b!="answer") || (p->offerer!=(item.b=="answer"))))return P2P_INVALID;
        size_t n=item.a.size()+item.b.size();
        if(p->commands.size()>=256 || p->command_bytes+n>P2P_HIGH)return P2P_AGAIN;
        // A handle represents one negotiation generation. Cap cumulative remote signaling too:
        // libdatachannel can otherwise retain unlimited candidates received before remote SDP.
        if(kind==P2P_DESCRIPTION && p->remote_description_seen)return P2P_INVALID;
        if(kind==P2P_CANDIDATE && p->remote_candidates>=256)return P2P_INVALID;
        if(p->remote_signal_bytes+n>P2P_HIGH)return P2P_INVALID;
        p->commands.push_back(std::move(item));p->command_bytes+=n;
        if(kind==9){p->started=true;p->offerer=channel!=0;}
        if(kind==P2P_DESCRIPTION)p->remote_description_seen=true;
        if(kind==P2P_CANDIDATE)++p->remote_candidates;
        p->remote_signal_bytes+=n;p->cv.notify_all();return 0;
    }catch(...){p->fail();return P2P_FAILED;}
}
}
extern "C" {
uint64_t p2p_create(uint64_t gen,const char *bind,p2p_callback cb,void *user){
    return p2p_create_config(gen,bind,nullptr,cb,user);
}
uint64_t p2p_create_config(uint64_t gen,const char *bind,const char *stun,p2p_callback cb,void *user){
    if(!gen || (bind && !valid_string(bind,128)))return 0;
    if(stun){
        if(!valid_string(stun,256) || std::strncmp(stun,"stun:",5))return 0;
        std::string s(stun+5);
        if(s.empty() || s.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-:[]")!=std::string::npos)return 0;
    }
    std::shared_ptr<Peer> p;
    try{
        {std::lock_guard<std::mutex> lock(registry_mutex);uint64_t h=++next_handle;p=std::make_shared<Peer>(h,gen,bind,stun,cb,user);peers[h]=p;}
        p->worker=std::thread([p]{p->run();});
        std::unique_lock<std::mutex> lock(p->mutex);p->cv.wait(lock,[&]{return p->ready;});
        bool ok=p->initialized;lock.unlock();if(!ok){p2p_destroy(p->handle);return 0;}return p->handle;
    }catch(...){if(p)p2p_destroy(p->handle);return 0;}
}
int p2p_start(uint64_t h,uint64_t g,int offerer){if(offerer!=0 && offerer!=1)return P2P_INVALID;return queue_command(h,g,9,nullptr,nullptr,offerer);}
int p2p_remote_description(uint64_t h,uint64_t g,const char *s,const char *t){return queue_command(h,g,P2P_DESCRIPTION,s,t);}
int p2p_remote_candidate(uint64_t h,uint64_t g,const char *c,const char *m){return queue_command(h,g,P2P_CANDIDATE,c,m);}
int p2p_write(uint64_t h,const uint8_t *data,int n){
    if(n<0 || n>65536 || (!data && n))return P2P_INVALID;
    auto p=get(h);if(!p)return P2P_CLOSED;
    try{
        std::lock_guard<std::mutex> lock(p->mutex);
        if(p->end==P2P_ERROR || p->state==P2P_ERROR)return P2P_FAILED;
        if(p->closing)return P2P_CLOSED;if(!n)return 0;
        if(p->blocked || p->tx.size()>=1024 || p->tx_bytes+p->buffered+size_t(n)>P2P_HIGH){p->blocked=true;return P2P_AGAIN;}
        p->tx.emplace_back(data,data+n);p->tx_bytes+=size_t(n);p->peak_tx=std::max(p->peak_tx,p->tx_bytes+p->buffered);
        p->cv.notify_all();return n;
    }catch(...){p->fail();return P2P_FAILED;}
}
int p2p_read(uint64_t h,uint8_t *data,int n){
    if(n<0 || (!data && n))return P2P_INVALID;auto p=get(h);if(!p)return P2P_CLOSED;
    std::lock_guard<std::mutex> lock(p->mutex);
    if(p->end==P2P_ERROR || p->state==P2P_ERROR)return P2P_FAILED;if(!n)return 0;
    if(p->rx.empty())return p->state==P2P_END?0:P2P_AGAIN;
    size_t copied=0;
    while(!p->rx.empty() && copied<size_t(n)){
        size_t count=std::min(size_t(n)-copied,p->rx.front().size()-p->rx_offset);
        std::memcpy(data+copied,p->rx.front().data()+p->rx_offset,count);copied+=count;p->rx_offset+=count;p->rx_bytes-=count;
        if(p->rx_offset==p->rx.front().size()){p->rx.pop_front();p->rx_offset=0;}
    }
    return int(copied);
}
int p2p_flush(uint64_t h){auto p=get(h);if(!p)return P2P_CLOSED;std::lock_guard<std::mutex> lock(p->mutex);
    if(p->end==P2P_ERROR || p->state==P2P_ERROR)return P2P_FAILED;
    if(p->closing && !p->graceful && p->state!=P2P_END)return P2P_CLOSED;
    return !p->tx_bytes && !p->buffered?0:P2P_AGAIN;}
int p2p_status(uint64_t h){auto p=get(h);if(!p)return P2P_END;std::lock_guard<std::mutex> lock(p->mutex);return p->state;}
int p2p_close(uint64_t h,int graceful){auto p=get(h);if(!p)return P2P_CLOSED;std::lock_guard<std::mutex> lock(p->mutex);
    if(!p->closing){p->closing=true;p->graceful=graceful!=0;p->close_deadline=Clock::now()+std::chrono::seconds(5);}
    else if(!graceful)p->graceful=false;
    p->cv.notify_all();return 0;}
void p2p_destroy(uint64_t h){
    auto p=get(h);if(!p)return;
    // Application contract excludes destroy on callback. Preserve safety if violated: request
    // abort and leave ownership with the controller, which must destroy from another thread.
    if(p->worker.get_id()==std::this_thread::get_id()){p2p_close(h,0);return;}
    {
        std::unique_lock<std::mutex> lock(p->mutex);
        if(p->destroying){p->cv.wait(lock,[&]{return p->destroyed;});return;}
        p->destroying=true;p->closing=true;p->graceful=false;p->cv.notify_all();
    }
    if(p->worker.joinable())p->worker.join();
    {std::lock_guard<std::mutex> lock(registry_mutex);peers.erase(h);}
    {std::lock_guard<std::mutex> lock(p->mutex);p->destroyed=true;p->cv.notify_all();}
}
int p2p_get_stats(uint64_t h,p2p_stats *s){auto p=get(h);if(!p)return P2P_CLOSED;if(!s)return P2P_INVALID;
    std::lock_guard<std::mutex> lock(p->mutex);*s={p->tx_bytes+p->buffered,p->rx_bytes,p->peak_tx,p->peak_rx,p->max_message};return 0;}
}
