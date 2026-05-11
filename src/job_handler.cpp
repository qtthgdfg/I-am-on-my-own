#include "job_handler.h"
#include <algorithm>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <cmath>

#ifdef __linux__
#include <numa.h>
#include <unistd.h>
#include <hwloc.h>
#endif

namespace monero {

// ==================== TargetHelper Implementation ====================

uint64_t TargetHelper::compact_to_uint64(const std::string& target_hex) {
    std::vector<uint8_t> target_bytes;
    
    // Convert hex to bytes
    for (size_t i = 0; i < target_hex.length(); i += 2) {
        if (i + 1 < target_hex.length()) {
            std::string byte_str = target_hex.substr(i, 2);
            target_bytes.push_back(
                static_cast<uint8_t>(strtol(byte_str.c_str(), nullptr, 16))
            );
        }
    }
    
    // Pad to 8 bytes for uint64 conversion
    while (target_bytes.size() < 8) {
        target_bytes.push_back(0);
    }
    
    // Monero targets are little-endian
    uint64_t target = 0;
    for (int i = 7; i >= 0; --i) {
        target = (target << 8) | target_bytes[i];
    }
    
    return target;
}

std::string TargetHelper::uint64_to_compact(uint64_t target) {
    std::stringstream ss;
    ss << std::hex << std::setw(16) << std::setfill('0') << target;
    return ss.str();
}

bool TargetHelper::hash_meets_target(const uint8_t* hash, uint64_t target) {
    // Compare hash as little-endian uint64
    uint64_t hash_value = 0;
    for (int i = 7; i >= 0; --i) {
        hash_value = (hash_value << 8) | hash[i];
    }
    return hash_value < target;
}

double TargetHelper::difficulty_from_target(uint64_t target) {
    // Monero difficulty calculation
    // Difficulty = MAX_TARGET / current_target
    constexpr uint64_t MAX_TARGET = 0xFFFFFFFFFFFFFFFF;
    if (target == 0) return 0.0;
    return static_cast<double>(MAX_TARGET) / static_cast<double>(target);
}

uint64_t TargetHelper::from_hex_target(const std::string& hex_target) {
    return compact_to_uint64(hex_target);
}

std::string TargetHelper::to_hex_target(uint64_t target) {
    return uint64_to_compact(target);
}

// ==================== BlobHelper Implementation ====================

std::vector<uint8_t> BlobHelper::hex_to_bytes(const std::string& hex) {
    std::vector<uint8_t> bytes;
    bytes.reserve(hex.length() / 2);
    
    for (size_t i = 0; i < hex.length(); i += 2) {
        if (i + 1 < hex.length()) {
            std::string byte_str = hex.substr(i, 2);
            bytes.push_back(
                static_cast<uint8_t>(strtol(byte_str.c_str(), nullptr, 16))
            );
        }
    }
    
    return bytes;
}

std::string BlobHelper::bytes_to_hex(const std::vector<uint8_t>& bytes) {
    return bytes_to_hex(bytes.data(), bytes.size());
}

std::string BlobHelper::bytes_to_hex(const uint8_t* bytes, size_t length) {
    std::stringstream ss;
    ss << std::hex << std::setfill('0');
    
    for (size_t i = 0; i < length; ++i) {
        ss << std::setw(2) << static_cast<int>(bytes[i]);
    }
    
    return ss.str();
}

void BlobHelper::insert_nonce(std::vector<uint8_t>& blob, uint32_t nonce, uint32_t offset) {
    if (blob.size() < offset + 4) {
        throw std::runtime_error("Blob too small for nonce insertion");
    }
    
    // Insert nonce in little-endian format
    blob[offset] = nonce & 0xFF;
    blob[offset + 1] = (nonce >> 8) & 0xFF;
    blob[offset + 2] = (nonce >> 16) & 0xFF;
    blob[offset + 3] = (nonce >> 24) & 0xFF;
}

uint32_t BlobHelper::extract_nonce(const std::vector<uint8_t>& blob, uint32_t offset) {
    if (blob.size() < offset + 4) {
        throw std::runtime_error("Blob too small for nonce extraction");
    }
    
    // Extract nonce from little-endian format
    uint32_t nonce = blob[offset];
    nonce |= static_cast<uint32_t>(blob[offset + 1]) << 8;
    nonce |= static_cast<uint32_t>(blob[offset + 2]) << 16;
    nonce |= static_cast<uint32_t>(blob[offset + 3]) << 24;
    
    return nonce;
}

void BlobHelper::insert_extra_nonce(std::vector<uint8_t>& blob, const std::vector<uint8_t>& extra_nonce) {
    // Extra nonce is typically inserted after the nonce field
    const uint32_t nonce_end = 43;  // After standard nonce position
    blob.insert(blob.begin() + nonce_end, extra_nonce.begin(), extra_nonce.end());
}

std::vector<uint8_t> BlobHelper::extract_extra_nonce(const std::vector<uint8_t>& blob) {
    // Extract extra nonce if present
    const uint32_t nonce_end = 43;
    if (blob.size() > nonce_end) {
        return std::vector<uint8_t>(blob.begin() + nonce_end, blob.end());
    }
    return {};
}

bool BlobHelper::validate_blob(const std::vector<uint8_t>& blob) {
    // Minimum blob size check (76 bytes for Monero)
    if (blob.size() < 76) {
        return false;
    }
    
    // Check for valid nonce offset
    if (blob.size() < 43) {  // 39 + 4 bytes for nonce
        return false;
    }
    
    // Additional validation could include:
    // - Version byte check
    // - Transaction format validation
    // - Block header structure verification
    
    return true;
}

size_t BlobHelper::get_nonce_offset(const std::vector<uint8_t>& blob) {
    // Standard Monero nonce offset is 39
    // But some pools might use different positions
    return 39;
}

// ==================== JobManager Implementation ====================

JobManager::JobManager() {
    m_stats.reset();
}

JobManager::~JobManager() {}

void JobManager::set_job_callback(JobCallback callback) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_job_callback = std::move(callback);
}

void JobManager::set_share_callback(ShareCallback callback) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_share_callback = std::move(callback);
}

void JobManager::new_job(const MiningJob& job) {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    // Store job
    m_current_job = job;
    m_current_job.received_time = std::chrono::steady_clock::now();
    
    // Track job history for stale detection
    m_recent_job_ids.push_back(job.job_id);
    if (m_recent_job_ids.size() > m_max_job_history) {
        m_recent_job_ids.erase(m_recent_job_ids.begin());
    }
    
    // Notify callback
    if (m_job_callback) {
        m_job_callback(job);
    }
}

MiningJob JobManager::get_current_job() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_current_job;
}

bool JobManager::has_job() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_current_job.is_valid();
}

bool JobManager::is_job_stale(const std::string& job_id) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    // Job is not current
    if (job_id != m_current_job.job_id) {
        return true;
    }
    
    // Job is too old (more than 60 seconds)
    if (m_current_job.age_seconds() > 60.0) {
        return true;
    }
    
    return false;
}

void JobManager::record_share(const ShareResult& result) {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    if (result.accepted) {
        m_stats.accepted_shares.fetch_add(1);
    } else {
        m_stats.rejected_shares.fetch_add(1);
    }
    
    m_stats.last_share_time = std::chrono::steady_clock::now();
    
    if (m_share_callback) {
        m_share_callback(result);
    }
}

MiningStats JobManager::get_stats() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_stats;
}

void JobManager::reset_stats() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_stats.reset();
}

void JobManager::set_job_history_size(size_t size) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_max_job_history = size;
    while (m_recent_job_ids.size() > m_max_job_history) {
        m_recent_job_ids.erase(m_recent_job_ids.begin());
    }
}

bool JobManager::was_job_processed(const std::string& job_id) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return std::find(m_recent_job_ids.begin(), m_recent_job_ids.end(), job_id) 
           != m_recent_job_ids.end();
}

// ==================== WorkerPool Implementation ====================

WorkerPool::WorkerPool(uint32_t num_workers) : m_num_workers(num_workers) {
    detect_numa_topology();
}

WorkerPool::~WorkerPool() {}

void WorkerPool::configure_workers(bool numa_aware, bool set_affinity) {
    m_worker_configs.clear();
    m_worker_configs.resize(m_num_workers);
    
    for (uint32_t i = 0; i < m_num_workers; ++i) {
        WorkerConfig config;
        config.thread_id = i;
        config.cpu_affinity = i % std::thread::hardware_concurrency();
        config.use_numa = numa_aware;
        config.numa_node = numa_aware ? (i % 2) : 0;
        
        m_worker_configs[i] = config;
    }
}

std::vector<WorkerPool::WorkerConfig> WorkerPool::get_worker_configs() const {
    return m_worker_configs;
}

void WorkerPool::detect_numa_topology() {
#ifdef __linux__
    if (numa_available() == 0) {
        int num_nodes = numa_num_configured_nodes();
        if (num_nodes > 1) {
            // Multi-NUMA system detected
            // Adjust worker distribution accordingly
        }
    }
#elif defined(_WIN32)
    // Windows NUMA detection would go here
#endif
}

uint32_t WorkerPool::get_optimal_thread_count() const {
    uint32_t hw_threads = std::thread::hardware_concurrency();
    
#ifdef __linux__
    // On Linux with NUMA, consider using only one node's threads
    if (numa_available() == 0) {
        int num_nodes = numa_num_configured_nodes();
        if (num_nodes > 1) {
            // Use threads from first NUMA node
            hw_threads = numa_num_configured_cpus() / num_nodes;
        }
    }
#endif
    
    // Don't use all threads to keep system responsive
    return std::max(1u, hw_threads > 2 ? hw_threads - 2 : hw_threads);
}

// ==================== DifficultyManager Implementation ====================

DifficultyManager::DifficultyManager(double initial_difficulty) 
    : m_current_difficulty(initial_difficulty) {
    m_current_target = difficulty_to_target(initial_difficulty);
}

void DifficultyManager::update_target(double pool_difficulty) {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    if (!m_auto_difficulty) return;
    
    m_current_difficulty = pool_difficulty;
    m_current_target = difficulty_to_target(pool_difficulty);
}

uint64_t DifficultyManager::get_current_target() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_current_target;
}

double DifficultyManager::get_current_difficulty() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_current_difficulty;
}

void DifficultyManager::set_fixed_difficulty(double difficulty) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_current_difficulty = difficulty;
    m_current_target = difficulty_to_target(difficulty);
    m_auto_difficulty = false;
}

void DifficultyManager::enable_auto_difficulty(bool enable) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_auto_difficulty = enable;
}

uint64_t DifficultyManager::difficulty_to_target(double difficulty) {
    // Convert difficulty to target
    // Max target for Monero
    constexpr uint64_t MAX_TARGET = 0xFFFFFFFFFFFFFFFF;
    
    if (difficulty <= 0.0) {
        return MAX_TARGET;
    }
    
    return static_cast<uint64_t>(static_cast<double>(MAX_TARGET) / difficulty);
}

} // namespace monero
