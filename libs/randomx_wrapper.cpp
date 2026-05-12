// FILE: MoneroRandomXMiner/libs/randomx_wrapper.cpp

#include "randomx_wrapper.h"
#include <cstring>
#include <stdexcept>
#include <algorithm>

#ifdef ANDROID
#include <android/log.h>
#define LOG_TAG "RandomXWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) 
#define LOGE(...) 
#endif

// ============================================================
// Constructor & Destructor
// ============================================================

RandomXWrapper::RandomXWrapper() 
    : m_cache(nullptr)
    , m_dataset(nullptr) {
    LOGI("RandomXWrapper created");
}

RandomXWrapper::~RandomXWrapper() {
    destroy();
    LOGI("RandomXWrapper destroyed");
}

// ============================================================
// Move Constructor
// ============================================================
RandomXWrapper::RandomXWrapper(RandomXWrapper&& other) noexcept
    : m_cache(other.m_cache)
    , m_dataset(other.m_dataset)
    , m_vms(std::move(other.m_vms)) {
    // Nullify source to prevent double-free
    other.m_cache = nullptr;
    other.m_dataset = nullptr;
    LOGI("RandomXWrapper moved (constructor)");
}

// ============================================================
// Move Assignment Operator
// ============================================================
RandomXWrapper& RandomXWrapper::operator=(RandomXWrapper&& other) noexcept {
    if (this != &other) {
        // Destroy current resources
        destroy();
        
        // Transfer ownership
        m_cache = other.m_cache;
        m_dataset = other.m_dataset;
        m_vms = std::move(other.m_vms);
        
        // Nullify source
        other.m_cache = nullptr;
        other.m_dataset = nullptr;
    }
    LOGI("RandomXWrapper moved (assignment)");
    return *this;
}

// ============================================================
// Initialize
// ============================================================

bool RandomXWrapper::initialize(const std::vector<uint8_t>& seed, 
                               uint32_t num_threads,
                               bool huge_pages,
                               bool numa_aware) {
    // Destroy any existing resources
    destroy();
    
    LOGI("Initializing RandomX with %u threads", num_threads);
    
    // Set RandomX flags based on platform capabilities
    randomx_flags flags = RANDOMX_FLAG_DEFAULT;
    
#ifdef ANDROID
    // Android doesn't support huge pages
    huge_pages = false;
    numa_aware = false;
    LOGI("Android detected - huge pages and NUMA disabled");
#else
    if (huge_pages) {
        flags = static_cast<randomx_flags>(flags | RANDOMX_FLAG_HUGE_PAGES);
        LOGI("Huge pages enabled");
    }
    if (numa_aware) {
        flags = static_cast<randomx_flags>(flags | RANDOMX_FLAG_NUMA);
        LOGI("NUMA awareness enabled");
    }
#endif
    
    // On ARM64, enable hardware AES if available
#ifdef __aarch64__
    flags = static_cast<randomx_flags>(flags | RANDOMX_FLAG_HARD_AES);
    LOGI("ARM64 hardware AES enabled");
#endif
    
    // Allocate RandomX cache
    m_cache = randomx_alloc_cache(flags);
    if (!m_cache) {
        LOGE("Failed to allocate RandomX cache");
        throw std::runtime_error("Failed to allocate RandomX cache");
    }
    LOGI("RandomX cache allocated");
    
    // Initialize cache with seed hash
    randomx_init_cache(m_cache, seed.data(), seed.size());
    LOGI("RandomX cache initialized with seed");
    
    // Allocate dataset
    m_dataset = randomx_alloc_dataset(flags);
    if (!m_dataset) {
        LOGE("Failed to allocate RandomX dataset");
        randomx_release_cache(m_cache);
        m_cache = nullptr;
        throw std::runtime_error("Failed to allocate RandomX dataset");
    }
    LOGI("RandomX dataset allocated");
    
    // Initialize dataset
    uint32_t num_items = randomx_dataset_item_count();
    LOGI("Initializing dataset with %u items using %u threads", num_items, num_threads);
    
    randomx_init_dataset(m_dataset, m_cache, 0, num_items, num_threads);
    LOGI("RandomX dataset initialized");
    
    // Create VMs for each mining thread
    m_vms.reserve(num_threads);
    
    // On Android, adjust JIT based on architecture
    randomx_flags vm_flags = static_cast<randomx_flags>(flags | RANDOMX_FLAG_JIT);
    
#ifdef __aarch64__
    // ARM64 JIT is well supported
    LOGI("Using ARM64 JIT compiler");
#elif defined(__arm__)
    // ARM32 JIT may have issues, fallback to interpreted mode if needed
    LOGI("Using ARM32 JIT compiler");
#endif
    
    for (uint32_t i = 0; i < num_threads; ++i) {
        randomx_vm* vm = randomx_create_vm(vm_flags, m_cache, m_dataset);
        
        if (!vm) {
            // If JIT failed, try without JIT
            if (vm_flags & RANDOMX_FLAG_JIT) {
                LOGI("JIT VM creation failed for thread %u, trying interpreted mode", i);
                vm_flags = static_cast<randomx_flags>(vm_flags & ~RANDOMX_FLAG_JIT);
                vm = randomx_create_vm(vm_flags, m_cache, m_dataset);
            }
            
            if (!vm) {
                LOGE("Failed to create RandomX VM for thread %u", i);
                throw std::runtime_error(
                    "Failed to create RandomX VM for thread " + std::to_string(i));
            }
        }
        
        m_vms.push_back(vm);
        LOGI("RandomX VM created for thread %u", i);
    }
    
    LOGI("RandomX initialization complete with %zu VMs", m_vms.size());
    return true;
}

// ============================================================
// Hash Functions
// ============================================================

void RandomXWrapper::hash(uint32_t thread_id, 
                         const void* input, 
                         size_t input_size, 
                         void* output) {
    if (thread_id >= m_vms.size()) {
        throw std::out_of_range(
            "Invalid thread ID: " + std::to_string(thread_id) + 
            " (max: " + std::to_string(m_vms.size() - 1) + ")");
    }
    
    if (!input || !output) {
        throw std::invalid_argument("Input or output buffer is null");
    }
    
    if (input_size == 0) {
        throw std::invalid_argument("Input size is zero");
    }
    
    randomx_vm* vm = m_vms[thread_id];
    if (!vm) {
        throw std::runtime_error("VM for thread " + std::to_string(thread_id) + " is null");
    }
    
    randomx_calculate_hash(vm, input, input_size, output);
}

void RandomXWrapper::hash_first(const void* input, 
                               size_t input_size, 
                               void* output) {
    if (m_vms.empty()) {
        throw std::runtime_error("No VMs available");
    }
    
    hash(0, input, input_size, output);
}

// ============================================================
// Utility Functions
// ============================================================

size_t RandomXWrapper::num_vms() const {
    return m_vms.size();
}

bool RandomXWrapper::is_initialized() const {
    return m_cache != nullptr && 
           m_dataset != nullptr && 
           !m_vms.empty();
}

// ============================================================
// Destroy
// ============================================================

void RandomXWrapper::destroy() {
    LOGI("Destroying RandomX resources...");
    
    // Destroy all VMs
    for (size_t i = 0; i < m_vms.size(); ++i) {
        if (m_vms[i]) {
            randomx_destroy_vm(m_vms[i]);
            LOGI("VM %zu destroyed", i);
        }
    }
    m_vms.clear();
    
    // Release dataset
    if (m_dataset) {
        randomx_release_dataset(m_dataset);
        m_dataset = nullptr;
        LOGI("Dataset released");
    }
    
    // Release cache
    if (m_cache) {
        randomx_release_cache(m_cache);
        m_cache = nullptr;
        LOGI("Cache released");
    }
    
    LOGI("RandomX resources destroyed");
}

// ============================================================
// Android-specific optimizations
// ============================================================
#ifdef ANDROID

bool RandomXWrapper::initialize_android(const std::vector<uint8_t>& seed,
                                        uint32_t num_threads,
                                        int battery_level,
                                        float temperature) {
    // Adjust thread count based on battery and temperature
    uint32_t adjusted_threads = num_threads;
    
    if (battery_level < 20) {
        adjusted_threads = std::min(adjusted_threads, 1u);
        LOGI("Low battery (%d%%), limiting to 1 thread", battery_level);
    } else if (battery_level < 40) {
        adjusted_threads = std::min(adjusted_threads, 2u);
        LOGI("Medium battery (%d%%), limiting to 2 threads", battery_level);
    }
    
    if (temperature > 55.0f) {
        LOGI("High temperature (%.1f°C), stopping mining", temperature);
        return false;
    } else if (temperature > 45.0f) {
        adjusted_threads = std::min(adjusted_threads, 1u);
        LOGI("Warm temperature (%.1f°C), limiting to 1 thread", temperature);
    } else if (temperature > 40.0f) {
        adjusted_threads = std::min(adjusted_threads, 2u);
        LOGI("Elevated temperature (%.1f°C), limiting to 2 threads", temperature);
    }
    
    LOGI("Adjusted thread count: %u (from %u)", adjusted_threads, num_threads);
    
    // Initialize with adjusted settings
    return initialize(seed, adjusted_threads, false, false);
}

#endif // ANDROID
