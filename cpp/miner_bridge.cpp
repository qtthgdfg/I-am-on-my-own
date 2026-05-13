#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <atomic>
#include <mutex>
#include "randomx_miner.h"
#include "stratum_client.h"

#define TAG "MinerBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::unique_ptr<RandomXMiner> g_miner;
static std::atomic<bool> g_mining{false};
static std::atomic<uint64_t> g_hashrate{0};
static std::atomic<uint64_t> g_accepted{0};
static std::atomic<uint64_t> g_rejected{0};
static std::mutex g_mutex;

static void statsCallback(uint64_t hashes, uint64_t accepted, uint64_t rejected) {
    g_hashrate.store(hashes);
    g_accepted.store(accepted);
    g_rejected.store(rejected);
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("=== Monero Miner Native Library v1.0 ===");
    LOGI("RandomX mining engine initialized");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_monerominer_MainActivity_nativeStartMining(
    JNIEnv* env, jobject thiz, jstring host, jint port,
    jstring wallet, jstring worker, jint threads) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (g_mining.load()) {
        LOGE("Miner already running");
        return JNI_FALSE;
    }
    
    try {
        const char* h = env->GetStringUTFChars(host, nullptr);
        const char* w = env->GetStringUTFChars(wallet, nullptr);
        const char* wk = env->GetStringUTFChars(worker, nullptr);
        
        LOGI("Starting miner:");
        LOGI("  Pool: %s:%d", h, port);
        LOGI("  Wallet: %.8s...", w);
        LOGI("  Worker: %s", wk);
        LOGI("  Threads: %d", threads);
        
        MinerConfig config;
        config.pool_host = std::string(h);
        config.pool_port = port;
        config.wallet = std::string(w);
        config.worker = std::string(wk);
        config.password = "x";
        config.use_ssl = false;
        config.num_threads = threads;
        config.init_threads = 2;
        config.huge_pages = false;
        config.numa_aware = false;
        config.scratchpad_size = 0;
        
        env->ReleaseStringUTFChars(host, h);
        env->ReleaseStringUTFChars(wallet, w);
        env->ReleaseStringUTFChars(worker, wk);
        
        g_miner = std::make_unique<RandomXMiner>(config);
        
        if (!g_miner->initialize()) {
            LOGE("Failed to initialize miner");
            return JNI_FALSE;
        }
        
        g_miner->start_mining();
        g_mining.store(true);
        
        LOGI("Miner started successfully");
        return JNI_TRUE;
        
    } catch (const std::exception& e) {
        LOGE("Exception: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_monerominer_MainActivity_nativeStopMining(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (g_miner) {
        g_miner->stop_mining();
        g_miner.reset();
    }
    g_mining.store(false);
    g_hashrate.store(0);
    LOGI("Miner stopped");
}

JNIEXPORT jstring JNICALL
Java_com_monerominer_MainActivity_nativeGetStats(JNIEnv* env, jobject thiz) {
    char stats[256];
    
    if (g_mining.load() && g_miner) {
        double hr = g_miner->get_hashrate();
        snprintf(stats, sizeof(stats),
            "{\"hashrate\":%.0f,\"accepted\":%lu,\"rejected\":%lu}",
            hr, g_accepted.load(), g_rejected.load());
    } else {
        snprintf(stats, sizeof(stats),
            "{\"hashrate\":0,\"accepted\":0,\"rejected\":0}");
    }
    
    return env->NewStringUTF(stats);
}

} // extern "C"
