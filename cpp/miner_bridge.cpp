#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <atomic>
#include <chrono>

#define TAG "MinerBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::atomic<bool> mining{false};
static std::atomic<uint64_t> hashCount{0};
static std::atomic<uint64_t> acceptedShares{0};
static std::atomic<uint64_t> rejectedShares{0};
static std::thread minerThread;

void miningLoop(int threads) {
    while (mining.load()) {
        // Real mining would call RandomX here
        hashCount.fetch_add(threads * 500);
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("Native library loaded");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_monerominer_MainActivity_nativeStartMining(
    JNIEnv* env, jobject thiz, jstring host, jint port,
    jstring wallet, jstring worker, jint threads) {
    
    if (mining.load()) return JNI_FALSE;
    
    const char* h = env->GetStringUTFChars(host, nullptr);
    LOGI("Starting miner on %s:%d with %d threads", h, port, threads);
    env->ReleaseStringUTFChars(host, h);
    
    hashCount = 0;
    acceptedShares = 0;
    rejectedShares = 0;
    mining = true;
    
    minerThread = std::thread(miningLoop, threads);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_monerominer_MainActivity_nativeStopMining(JNIEnv* env, jobject thiz) {
    mining = false;
    if (minerThread.joinable()) minerThread.join();
    LOGI("Mining stopped");
}

JNIEXPORT jstring JNICALL
Java_com_monerominer_MainActivity_nativeGetStats(JNIEnv* env, jobject thiz) {
    char stats[128];
    snprintf(stats, sizeof(stats),
        "{\"hashrate\":%lu,\"accepted\":%lu,\"rejected\":%lu}",
        hashCount.load(), acceptedShares.load(), rejectedShares.load());
    return env->NewStringUTF(stats);
}

} // extern "C"
