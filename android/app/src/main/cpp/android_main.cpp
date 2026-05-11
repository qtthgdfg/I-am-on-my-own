#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <atomic>
#include "randomx_miner.h"
#include "stratum_client.h"

#define LOG_TAG "MoneroMiner"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<RandomXMiner> g_miner;
static std::atomic<bool> g_mining{false};
static JavaVM* g_jvm = nullptr;
static jclass g_callback_class = nullptr;
static jobject g_callback_object = nullptr;

// Prepare mining config from Java
MinerConfig createMinerConfig(JNIEnv* env, jobject config_obj) {
    MinerConfig config;
    
    jclass cls = env->GetObjectClass(config_obj);
    
    // Get pool_host
    jfieldID host_fid = env->GetFieldID(cls, "poolHost", "Ljava/lang/String;");
    jstring jhost = (jstring)env->GetObjectField(config_obj, host_fid);
    const char* host = env->GetStringUTFChars(jhost, nullptr);
    config.pool_host = std::string(host);
    env->ReleaseStringUTFChars(jhost, host);
    
    // Get pool_port
    jfieldID port_fid = env->GetFieldID(cls, "poolPort", "I");
    config.pool_port = env->GetIntField(config_obj, port_fid);
    
    // Get wallet
    jfieldID wallet_fid = env->GetFieldID(cls, "wallet", "Ljava/lang/String;");
    jstring jwallet = (jstring)env->GetObjectField(config_obj, wallet_fid);
    const char* wallet = env->GetStringUTFChars(jwallet, nullptr);
    config.wallet = std::string(wallet);
    env->ReleaseStringUTFChars(jwallet, wallet);
    
    // Get worker name
    jfieldID worker_fid = env->GetFieldID(cls, "worker", "Ljava/lang/String;");
    jstring jworker = (jstring)env->GetObjectField(config_obj, worker_fid);
    const char* worker = env->GetStringUTFChars(jworker, nullptr);
    config.worker = std::string(worker);
    env->ReleaseStringUTFChars(jworker, worker);
    
    // Get password
    jfieldID pass_fid = env->GetFieldID(cls, "password", "Ljava/lang/String;");
    jstring jpass = (jstring)env->GetObjectField(config_obj, pass_fid);
    const char* pass = env->GetStringUTFChars(jpass, nullptr);
    config.password = std::string(pass);
    env->ReleaseStringUTFChars(jpass, pass);
    
    // Get threads
    jfieldID threads_fid = env->GetFieldID(cls, "threads", "I");
    config.num_threads = env->GetIntField(config_obj, threads_fid);
    
    // Default values for Android
    config.use_ssl = false;
    config.huge_pages = false;
    config.numa_aware = false;
    config.init_threads = std::min(2u, config.num_threads);
    config.scratchpad_size = 0;
    
    return config;
}

// Callback to Java when share is found
void shareFoundCallback(const std::string& job_id, 
                        const std::string& nonce, 
                        const std::string& hash) {
    JNIEnv* env;
    bool attached = false;
    
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    
    jmethodID callback = env->GetMethodID(g_callback_class, 
        "onShareFound", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    
    jstring jjob_id = env->NewStringUTF(job_id.c_str());
    jstring jnonce = env->NewStringUTF(nonce.c_str());
    jstring jhash = env->NewStringUTF(hash.c_str());
    
    env->CallVoidMethod(g_callback_object, callback, jjob_id, jnonce, jhash);
    
    env->DeleteLocalRef(jjob_id);
    env->DeleteLocalRef(jnonce);
    env->DeleteLocalRef(jhash);
    
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_monerominer_MinerService_nativeStartMining(
    JNIEnv* env,
    jobject thiz,
    jobject config_obj,
    jobject callback_obj) {
    
    if (g_mining.load()) {
        LOGE("Miner already running");
        return JNI_FALSE;
    }
    
    try {
        MinerConfig config = createMinerConfig(env, config_obj);
        
        // Save callback reference
        g_callback_class = (jclass)env->NewGlobalRef(
            env->GetObjectClass(callback_obj));
        g_callback_object = env->NewGlobalRef(callback_obj);
        
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
        LOGE("Exception starting miner: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_monerominer_MinerService_nativeStopMining(JNIEnv* env, jobject thiz) {
    if (g_miner) {
        g_miner->stop_mining();
        g_mining.store(false);
    }
    
    if (g_callback_object) {
        env->DeleteGlobalRef(g_callback_object);
        g_callback_object = nullptr;
    }
    if (g_callback_class) {
        env->DeleteGlobalRef(g_callback_class);
        g_callback_class = nullptr;
    }
    
    LOGI("Miner stopped");
}

JNIEXPORT jstring JNICALL
Java_com_monerominer_MinerService_nativeGetStats(JNIEnv* env, jobject thiz) {
    if (!g_miner || !g_mining.load()) {
        return env->NewStringUTF("{\"status\": \"stopped\"}");
    }
    
    double hashrate = g_miner->get_hashrate();
    char stats[256];
    snprintf(stats, sizeof(stats), 
        "{\"status\": \"running\", \"hashrate\": %.2f}", hashrate);
    
    return env->NewStringUTF(stats);
}

} // extern "C"
