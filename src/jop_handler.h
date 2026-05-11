#pragma once

#include <string>
#include <vector>
#include <cstdint>
#include <chrono>
#include <mutex>
#include <atomic>
#include <functional>
#include <memory>

namespace monero {

struct MiningJob {
    std::string job_id;
    std::string blob;
    std::string target;
    std::string seed_hash;
    uint64_t height{0};
    uint32_t nonce_offset{39};  // Standard Monero nonce position
    size_t blob_min_size{76};   // Minimum valid blob size
    std::chrono::steady_clock::time_point received_time;
    uint32_t job_counter{0};
    
    // Validation
    bool is_valid() const {
        return !blob.empty() && !target.empty() && !job_id.empty();
    }
    
    // Job age in seconds
    double age_seconds() const {
        auto now = std::chrono::steady_clock::now();
        return std::chrono::duration<double>(now - received_time).count();
    }
};

struct ShareResult {
    bool accepted{false};
    std::string job_id;
    std::string nonce;
    std::string hash;
    std::string error_message;
    std::chrono::steady_clock::time_point submit_time;
    uint64_t share_difficulty{0};
};

struct PoolConfig {
    std::string host;
    uint16_t port{3333};
    std::string wallet;
    std::string worker{"worker1"};
    std::string password{"x"};
    bool use_ssl{false};
    bool use_nicehash{false};
    uint32_t retry_interval{5};     // Seconds between reconnection attempts
    uint32_t reconnect_max{10};     // Max reconnection attempts
    uint32_t timeout{30};           // Socket timeout in seconds
};

struct MiningStats {
    std::atomic<uint64_t> total_hashes{0};
    std::atomic<uint64_t> accepted_shares{0};
    std::atomic<uint64_t> rejected_shares{0};
    std::atomic<uint64_t> stale_shares{0};
    std::atomic<double> current_hashrate{0.0};
    std::atomic<double> average_hashrate{0.0};
    std::chrono::steady_clock::time_point start_time;
    std::chrono::steady_clock::time_point last_share_time;
    
    void reset() {
        total_hashes.store(0);
        accepted_shares.store(0);
        rejected_shares.store(0);
        stale_shares.store(0);
        current_hashrate.store(0.0);
        average_hashrate.store(0.0);
        start_time = std::chrono::steady_clock::now();
        last_share_time = start_time;
    }
    
    double uptime_seconds() const {
        auto now = std::chrono::steady_clock::now();
        return std::chrono::duration<double>(now - start_time).count();
    }
};

// Target handling utilities
class TargetHelper {
public:
    static uint64_t compact_to_uint64(const std::string& target_hex);
    static std::string uint64_to_compact(uint64_t target);
    static bool hash_meets_target(const uint8_t* hash, uint64_t target);
    static double difficulty_from_target(uint64_t target);
    
    // Monero-specific target conversion
    static uint64_t from_hex_target(const std::string& hex_target);
    static std::string to_hex_target(uint64_t target);
};

// Blob manipulation utilities
class BlobHelper {
public:
    static std::vector<uint8_t> hex_to_bytes(const std::string& hex);
    static std::string bytes_to_hex(const std::vector<uint8_t>& bytes);
    static std::string bytes_to_hex(const uint8_t* bytes, size_t length);
    
    static void insert_nonce(std::vector<uint8_t>& blob, uint32_t nonce, uint32_t offset = 39);
    static uint32_t extract_nonce(const std::vector<uint8_t>& blob, uint32_t offset = 39);
    
    static void insert_extra_nonce(std::vector<uint8_t>& blob, const std::vector<uint8_t>& extra_nonce);
    static std::vector<uint8_t> extract_extra_nonce(const std::vector<uint8_t>& blob);
    
    static bool validate_blob(const std::vector<uint8_t>& blob);
    static size_t get_nonce_offset(const std::vector<uint8_t>& blob);
};

// Job validation and management
class JobManager {
public:
    using JobCallback = std::function<void(const MiningJob&)>;
    using ShareCallback = std::function<void(const ShareResult&)>;
    
    JobManager();
    ~JobManager();
    
    void set_job_callback(JobCallback callback);
    void set_share_callback(ShareCallback callback);
    
    void new_job(const MiningJob& job);
    MiningJob get_current_job() const;
    bool has_job() const;
    bool is_job_stale(const std::string& job_id) const;
    
    void record_share(const ShareResult& result);
    MiningStats get_stats() const;
    void reset_stats();
    
    // Job history for stale detection
    void set_job_history_size(size_t size);
    bool was_job_processed(const std::string& job_id) const;
    
private:
    mutable std::mutex m_mutex;
    MiningJob m_current_job;
    std::vector<std::string> m_recent_job_ids;
    size_t m_max_job_history{10};
    
    JobCallback m_job_callback;
    ShareCallback m_share_callback;
    MiningStats m_stats;
};

// Worker management for multiple threads
class WorkerPool {
public:
    struct WorkerConfig {
        uint32_t thread_id;
        uint32_t cpu_affinity;
        bool use_numa;
        uint32_t numa_node;
    };
    
    WorkerPool(uint32_t num_workers);
    ~WorkerPool();
    
    void configure_workers(bool numa_aware, bool set_affinity);
    std::vector<WorkerConfig> get_worker_configs() const;
    
private:
    uint32_t m_num_workers;
    std::vector<WorkerConfig> m_worker_configs;
    
    void detect_numa_topology();
    uint32_t get_optimal_thread_count() const;
};

// Mining difficulty management
class DifficultyManager {
public:
    DifficultyManager(double initial_difficulty = 1000.0);
    
    void update_target(double pool_difficulty);
    uint64_t get_current_target() const;
    double get_current_difficulty() const;
    
    void set_fixed_difficulty(double difficulty);
    void enable_auto_difficulty(bool enable);
    
private:
    std::mutex m_mutex;
    double m_current_difficulty;
    uint64_t m_current_target;
    bool m_auto_difficulty{true};
    
    uint64_t difficulty_to_target(double difficulty);
};

} // namespace monero
