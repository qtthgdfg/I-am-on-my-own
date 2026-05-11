#pragma once

#include "randomx.h"
#include <vector>
#include <memory>
#include <stdexcept>
#include <cstring>

class RandomXWrapper {
public:
    RandomXWrapper() = default;
    ~RandomXWrapper() {
        destroy();
    }
    
    bool initialize(const std::vector<uint8_t>& seed, 
                   uint32_t num_threads = 4,
                   bool huge_pages = true,
                   bool numa_aware = false) {
        destroy();
        
        // Set RandomX flags
        randomx_flags flags = RANDOMX_FLAG_DEFAULT;
        if (huge_pages) flags = static_cast<randomx_flags>(flags | RANDOMX_FLAG_HUGE_PAGES);
        if (numa_aware) flags = static_cast<randomx_flags>(flags | RANDOMX_FLAG_NUMA);
        
        // Allocate and initialize cache
        m_cache = randomx_alloc_cache(flags);
        if (!m_cache) {
            throw std::runtime_error("Failed to allocate RandomX cache");
        }
        
        randomx_init_cache(m_cache, seed.data(), seed.size());
        
        // Allocate and initialize dataset
        m_dataset = randomx_alloc_dataset(flags);
        if (!m_dataset) {
            throw std::runtime_error("Failed to allocate RandomX dataset");
        }
        
        uint32_t num_items = randomx_dataset_item_count();
        randomx_init_dataset(m_dataset, m_cache, 0, num_items, num_threads);
        
        // Create VMs for each mining thread
        for (uint32_t i = 0; i < num_threads; ++i) {
            randomx_vm* vm = randomx_create_vm(
                static_cast<randomx_flags>(flags | RANDOMX_FLAG_JIT),
                m_cache,
                m_dataset
            );
            
            if (!vm) {
                throw std::runtime_error("Failed to create RandomX VM for thread " + 
                                       std::to_string(i));
            }
            
            m_vms.push_back(vm);
        }
        
        return true;
    }
    
    void hash(uint32_t thread_id, const void* input, size_t input_size, void* output) {
        if (thread_id >= m_vms.size()) {
            throw std::out_of_range("Invalid thread ID");
        }
        
        randomx_calculate_hash(m_vms[thread_id], input, input_size, output);
    }
    
    void hash_first(const void* input, size_t input_size, void* output) {
        if (m_vms.empty()) {
            throw std::runtime_error("No VMs available");
        }
        
        randomx_calculate_hash(m_vms[0], input, input_size, output);
    }
    
    size_t num_vms() const {
        return m_vms.size();
    }
    
private:
    void destroy() {
        for (auto* vm : m_vms) {
            if (vm) randomx_destroy_vm(vm);
        }
        m_vms.clear();
        
        if (m_dataset) {
            randomx_release_dataset(m_dataset);
            m_dataset = nullptr;
        }
        
        if (m_cache) {
            randomx_release_cache(m_cache);
            m_cache = nullptr;
        }
    }
    
    randomx_cache* m_cache{nullptr};
    randomx_dataset* m_dataset{nullptr};
    std::vector<randomx_vm*> m_vms;
};
