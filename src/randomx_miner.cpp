#include "randomx_miner.h"
#include <iostream>
#include <iomanip>
#include <sstream>
#include <cstring>
#include <algorithm>
#include <random>

RandomXMiner::RandomXMiner(const MinerConfig& config)
    : m_config(config) {
    
    m_stratum = std::make_unique<StratumClient>(
        config.pool_host,
        config.pool_port,
        config.wallet,
        config.password,
        config.worker,
        config.use_ssl
    );
}

RandomXMiner::~RandomXMiner() {
    stop_mining();
    
    for (auto* vm : m_rx_vms) {
        if (vm) randomx_destroy_vm(vm);
    }
    if (m_rx_dataset) randomx_release_dataset(m_rx_dataset);
    if (m_rx_cache) randomx_release_cache(m_rx_cache);
}

bool RandomXMiner::initialize() {
    // Connect to the Stratum pool
    if (!m_stratum->connect()) {
        std::cerr << "Failed to connect to pool" << std::endl;
        return false;
    }
    
    // Login to the pool
    if (!m_stratum->login()) {
        std::cerr << "Failed to login to pool" << std::endl;
        return false;
    }
    
    // Set up job callback
    m_stratum->set_job_callback([this](const MiningJob& job) {
        this->on_new_job(job);
    });
    
    // Set up share callback
    m_stratum->set_share_callback([this](bool accepted, const std::string& response) {
        this->on_share_result(accepted, response);
    });
    
    // Start receiving jobs from the pool
    m_stratum->start_receive_loop();
    
    std::cout << "Successfully connected and logged in to pool" << std::endl;
    return true;
}

bool RandomXMiner::initialize_randomx(const std::string& seed_hash) {
    if (seed_hash == m_current_seed_hash) return true;
    
    std::cout << "Initializing RandomX for seed: " << seed_hash.substr(0, 16) << "..." << std::endl;
    
    for (auto* vm : m_rx_vms) {
        if (vm) randomx_destroy_vm(vm);
    }
    m_rx_vms.clear();
    
    if (m_rx_dataset) {
        randomx_release_dataset(m_rx_dataset);
        m_rx_dataset = nullptr;
    }
    if (m_rx_cache) {
        randomx_release_cache(m_rx_cache);
        m_rx_cache = nullptr;
    }
    
    // Parse seed
    std::vector<uint8_t> seed_bytes;
    for (size_t i = 0; i < seed_hash.length(); i += 2) {
        std::string byte_str = seed_hash.substr(i, 2);
        seed_bytes.push_back(static_cast<uint8_t>(strtol(byte_str.c_str(), nullptr, 16)));
    }
    
    // Allocate cache with default flags
    randomx_flags flags = RANDOMX_FLAG_DEFAULT;
    
    m_rx_cache = randomx_alloc_cache(flags);
    if (!m_rx_cache) {
        std::cerr << "Failed to allocate RandomX cache" << std::endl;
        return false;
    }
    
    randomx_init_cache(m_rx_cache, seed_bytes.data(), seed_bytes.size());
    
    m_rx_dataset = randomx_alloc_dataset(flags);
    if (!m_rx_dataset) {
        std::cerr << "Failed to allocate RandomX dataset" << std::endl;
        return false;
    }
    
    // Fixed: randomx_init_dataset takes 4 args, not 5
    uint32_t num_dataset_items = randomx_dataset_item_count();
    randomx_init_dataset(m_rx_dataset, m_rx_cache, 0, num_dataset_items);
    
    // Create VM for each mining thread
    for (uint32_t i = 0; i < m_config.num_threads; ++i) {
        randomx_vm* vm = randomx_create_vm(flags, m_rx_cache, m_rx_dataset);
        
        if (!vm) {
            std::cerr << "Failed to create RandomX VM for thread " << i << std::endl;
            return false;
        }
        
        m_rx_vms.push_back(vm);
    }
    
    m_current_seed_hash = seed_hash;
    std::cout << "RandomX initialized successfully" << std::endl;
    return true;
}

std::vector<uint8_t> RandomXMiner::construct_mining_blob(const std::string& blob_hex, uint32_t nonce) {
    std::vector<uint8_t> blob;
    blob.reserve(blob_hex.length() / 2);
    
    for (size_t i = 0; i < blob_hex.length(); i += 2) {
        std::string byte_str = blob_hex.substr(i, 2);
        blob.push_back(static_cast<uint8_t>(strtol(byte_str.c_str(), nullptr, 16)));
    }
    
    if (blob.size() < 76) {
        throw std::runtime_error("Invalid mining blob size");
    }
    
    uint32_t nonce_offset = 39;
    blob[nonce_offset] = nonce & 0xFF;
    blob[nonce_offset + 1] = (nonce >> 8) & 0xFF;
    blob[nonce_offset + 2] = (nonce >> 16) & 0xFF;
    blob[nonce_offset + 3] = (nonce >> 24) & 0xFF;
    
    return blob;
}

uint64_t RandomXMiner::target_to_uint64(const std::string& target_hex) {
    std::vector<uint8_t> target_bytes;
    for (size_t i = 0; i < target_hex.length() && i < 16; i += 2) {
        std::string byte_str = target_hex.substr(i, 2);
        target_bytes.push_back(static_cast<uint8_t>(strtol(byte_str.c_str(), nullptr, 16)));
    }
    
    while (target_bytes.size() < 8) {
        target_bytes.push_back(0);
    }
    
    uint64_t target = 0;
    for (int i = 7; i >= 0; --i) {
        target = (target << 8) | target_bytes[i];
    }
    
    return target;
}

bool RandomXMiner::hash_meets_target(const uint8_t* hash, uint64_t target) {
    uint64_t hash_value = 0;
    for (int i = 7; i >= 0; --i) {
        hash_value = (hash_value << 8) | hash[i];
    }
    return hash_value < target;
}

void RandomXMiner::mining_thread(uint32_t thread_id) {
    // Check if RandomX is initialized before using VM
    if (thread_id >= m_rx_vms.size()) {
        std::cerr << "Error: Invalid thread_id " << thread_id << ", RandomX not initialized" << std::endl;
        return;
    }
    
    randomx_vm* vm = m_rx_vms[thread_id];
    if (!vm) {
        std::cerr << "Error: VM for thread " << thread_id << " is null" << std::endl;
        return;
    }
    
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<uint32_t> nonce_dist(0, 0xFFFFFFFF);
    
    uint64_t local_hash_count = 0;
    uint32_t nonce = nonce_dist(gen);
    
    std::cout << "Mining thread " << thread_id << " started" << std::endl;
    
    while (m_running.load()) {
        MiningJob job;
        {
            std::lock_guard<std::mutex> lock(m_job_mutex);
            job = m_current_job;
        }
        
        if (job.blob.empty()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }
        
        if (job.seed_hash != m_current_seed_hash) {
            initialize_randomx(job.seed_hash);
            if (thread_id >= m_rx_vms.size()) {
                std::cerr << "Error: RandomX initialization failed" << std::endl;
                break;
            }
            vm = m_rx_vms[thread_id];
        }
        
        uint64_t target = target_to_uint64(job.target);
        
        uint32_t job_nonce = nonce;
        const uint32_t BATCH_SIZE = 5000;
        
        for (uint32_t i = 0; i < BATCH_SIZE && !m_new_job.load(); ++i) {
            std::vector<uint8_t> mining_blob = construct_mining_blob(job.blob, job_nonce);
            
            uint8_t hash[RANDOMX_HASH_SIZE];
            randomx_calculate_hash(vm, mining_blob.data(), mining_blob.size(), hash);
            
            local_hash_count++;
            
            if (hash_meets_target(hash, target)) {
                std::stringstream nonce_ss;
                nonce_ss << std::hex << std::setw(8) << std::setfill('0') << job_nonce;
                
                std::stringstream hash_ss;
                for (int j = 0; j < RANDOMX_HASH_SIZE; ++j) {
                    hash_ss << std::hex << std::setw(2) << std::setfill('0') 
                           << static_cast<int>(hash[j]);
                }
                
                std::cout << "Thread " << thread_id << " found solution! Nonce: " 
                         << nonce_ss.str() << std::endl;
                
                m_stratum->submit_share(job.job_id, nonce_ss.str(), hash_ss.str());
                job_nonce = nonce_dist(gen);
            }
            
            job_nonce++;
        }
        
        nonce = job_nonce;
        m_total_hashes.fetch_add(local_hash_count);
        local_hash_count = 0;
        m_new_job.store(false);
    }
    
    std::cout << "Mining thread " << thread_id << " stopped" << std::endl;
}

void RandomXMiner::on_new_job(const MiningJob& job) {
    std::lock_guard<std::mutex> lock(m_job_mutex);
    m_current_job = job;
    m_new_job.store(true);
}

void RandomXMiner::on_share_result(bool accepted, const std::string& response) {
    if (accepted) {
        m_accepted_shares.fetch_add(1);
    } else {
        m_rejected_shares.fetch_add(1);
    }
}

void RandomXMiner::start_mining() {
    m_running.store(true);
    m_start_time = std::chrono::steady_clock::now();
    
    for (uint32_t i = 0; i < m_config.num_threads; ++i) {
        m_mining_threads.emplace_back(&RandomXMiner::mining_thread, this, i);
    }
}

void RandomXMiner::stop_mining() {
    m_running.store(false);
    
    for (auto& thread : m_mining_threads) {
        if (thread.joinable()) {
            thread.join();
        }
    }
    m_mining_threads.clear();
}

double RandomXMiner::get_hashrate() const {
    auto elapsed = std::chrono::steady_clock::now() - m_start_time;
    double seconds = std::chrono::duration<double>(elapsed).count();
    return seconds > 0 ? m_total_hashes.load() / seconds : 0.0;
}
