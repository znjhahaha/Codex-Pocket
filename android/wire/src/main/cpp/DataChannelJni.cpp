#include <jni.h>
#include "pocket_p2p.h"
#include <algorithm>
#include <array>
#include <memory>
#include <mutex>
#include <unordered_map>
namespace {
struct Context { JavaVM *vm=nullptr; jobject object=nullptr; jmethodID event=nullptr; };
std::mutex mutex;
std::unordered_map<uint64_t,std::shared_ptr<Context>> contexts;
class Utf {
    JNIEnv *env;jstring value;
public:
    const char *bytes;
    Utf(JNIEnv *e,jstring s):env(e),value(s),bytes(s?e->GetStringUTFChars(s,nullptr):nullptr){}
    ~Utf(){if(bytes)env->ReleaseStringUTFChars(value,bytes);}
};
void event(void *user,uint64_t handle,uint64_t gen,int kind,const char *a,const char *b) {
    auto &ctx=*static_cast<Context*>(user);JNIEnv *env=nullptr;bool attached=false;
    if(ctx.vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_EDETACHED){
#ifdef __ANDROID__
        if(ctx.vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return;
#else
        if(ctx.vm->AttachCurrentThread(reinterpret_cast<void**>(&env),nullptr)!=JNI_OK)return;
#endif
        attached=true;
    }
    if(!env)return;
    if(env->PushLocalFrame(4)==JNI_OK){
        jstring first=env->NewStringUTF(a?a:"");jstring second=env->NewStringUTF(b?b:"");
        if(first && second && !env->ExceptionCheck())
            env->CallVoidMethod(ctx.object,ctx.event,jlong(handle),jlong(gen),jint(kind),first,second);
        if(env->ExceptionCheck()){env->ExceptionClear();p2p_close(handle,0);}
        env->PopLocalFrame(nullptr);
    }else{env->ExceptionClear();p2p_close(handle,0);}
    if(attached)ctx.vm->DetachCurrentThread();
}
}
extern "C" {
JNIEXPORT jlong JNICALL Java_dev_codexpocket_wire_DataChannelTransport_nativeCreate(JNIEnv *env,jobject self,jlong gen,jstring bind,jstring stun){
    try{
        auto ctx=std::make_shared<Context>();
        if(env->GetJavaVM(&ctx->vm)!=JNI_OK)return 0;
        jclass cls=env->GetObjectClass(self);
        ctx->event=env->GetMethodID(cls,"onNativeEvent","(JJILjava/lang/String;Ljava/lang/String;)V");env->DeleteLocalRef(cls);
        if(!ctx->event)return 0;
        ctx->object=env->NewGlobalRef(self);if(!ctx->object)return 0;
        Utf addr(env,bind);
        if(bind && !addr.bytes){env->DeleteGlobalRef(ctx->object);return 0;}
        Utf server(env,stun);
        if(stun && !server.bytes){env->DeleteGlobalRef(ctx->object);return 0;}
        uint64_t h=p2p_create_config(uint64_t(gen),addr.bytes,server.bytes,event,ctx.get());
        if(!h){env->DeleteGlobalRef(ctx->object);return 0;}
        try{std::lock_guard<std::mutex> lock(mutex);contexts[h]=ctx;}
        catch(...){p2p_destroy(h);env->DeleteGlobalRef(ctx->object);return 0;}
        return jlong(h);
    }catch(...){return 0;}
}
JNIEXPORT jint JNICALL Java_dev_codexpocket_wire_DataChannelTransport_nativeStart(JNIEnv*,jobject,jlong h,jlong g,jboolean offer){return p2p_start(h,g,offer?1:0);}
JNIEXPORT jint JNICALL Java_dev_codexpocket_wire_DataChannelTransport_nativeDescription(JNIEnv *env,jobject,jlong h,jlong g,jstring s,jstring t){
    if(!s || !t || env->GetStringUTFLength(s)>65536 || env->GetStringUTFLength(t)>256)return P2P_INVALID;
    Utf sdp(env,s),type(env,t);if(!sdp.bytes || !type.bytes)return P2P_INVALID;return p2p_remote_description(h,g,sdp.bytes,type.bytes);
}
JNIEXPORT jint JNICALL Java_dev_codexpocket_wire_DataChannelTransport_nativeCandidate(JNIEnv *env,jobject,jlong h,jlong g,jstring c,jstring m){
    if(!c || !m || env->GetStringUTFLength(c)>4096 || env->GetStringUTFLength(m)>256)return P2P_INVALID;
    Utf candidate(env,c),mid(env,m);if(!candidate.bytes || !mid.bytes)return P2P_INVALID;return p2p_remote_candidate(h,g,candidate.bytes,mid.bytes);
}
JNIEXPORT jint JNICALL Java_dev_codexpocket_wire_DataChannelTransport_nativeWrite(JNIEnv *env,jobject,jlong h,jbyteArray data){
    if(!data)return P2P_INVALID;jsize n=env->GetArrayLength(data);if(n>65536)return P2P_INVALID;
    jbyte *bytes=env->GetByteArrayElements(data,nullptr);if(!bytes)return P2P_FAILED;
    int result=p2p_write(h,reinterpret_cast<uint8_t*>(bytes),n);env->ReleaseByteArrayElements(data,bytes,JNI_ABORT);return result;
}
JNIEXPORT jint JNICALL Java_dev_codexpocket_wire_DataChannelTransport_nativeRead(JNIEnv *env,jobject,jlong h,jbyteArray data){
    if(!data)return P2P_INVALID;
    std::array<uint8_t,P2P_CHUNK> block{};int n=std::min(int(env->GetArrayLength(data)),P2P_CHUNK);
    int result=p2p_read(h,block.data(),n);
    if(result>0)env->SetByteArrayRegion(data,0,result,reinterpret_cast<jbyte*>(block.data()));
    return result;
}
JNIEXPORT void JNICALL Java_dev_codexpocket_wire_DataChannelTransport_nativeClose(JNIEnv*,jobject,jlong h,jboolean graceful){p2p_close(h,graceful?1:0);}
JNIEXPORT void JNICALL Java_dev_codexpocket_wire_DataChannelTransport_nativeDestroy(JNIEnv *env,jobject,jlong h){
    std::shared_ptr<Context> ctx;
    {std::lock_guard<std::mutex> lock(mutex);auto i=contexts.find(h);if(i==contexts.end())return;ctx=i->second;contexts.erase(i);}
    p2p_destroy(h);env->DeleteGlobalRef(ctx->object);
}
}
