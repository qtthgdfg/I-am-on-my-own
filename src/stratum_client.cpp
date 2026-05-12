#include "stratum_client.h"
#include <iostream>
#include <sstream>
#include <cstring>
#include <unistd.h>
#include <chrono>

StratumClient::StratumClient(const std::string& pool_host,
                             uint16_t pool_port,
                             const std::string& wallet,
                             const std::string& password,
                             const std::string& worker,
                             bool use_ssl)
    : m_pool_host(pool_host)
    , m_pool_port(pool_port)
    , m_wallet(wallet)
    , m_password(password)
    , m_worker(worker)
    , m_use_ssl(use_ssl) {
}

StratumClient::~StratumClient() {
    stop();
    cleanup_ssl();
}

bool StratumClient::resolve_host() {
    struct addrinfo hints{}, *result;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    
    std::string port_str = std::to_string(m_pool_port);
    int ret = getaddrinfo(m_pool_host.c_str(), port_str.c_str(), &hints, &result);
    if (ret != 0) {
        std::cerr << "DNS resolution failed" << std::endl;
        return false;
    }
    
    memcpy(&m_server_addr, result->ai_addr, sizeof(m_server_addr));
    freeaddrinfo(result);
    return true;
}

bool StratumClient::connect() {
    if (m_connected.load()) return true;
    if (!resolve_host()) return false;
    
    m_socket_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (m_socket_fd < 0) return false;
    
    int opt = 1;
    setsockopt(m_socket_fd, SOL_SOCKET, SO_KEEPALIVE, &opt, sizeof(opt));
    
    struct timeval timeout{30, 0};
    setsockopt(m_socket_fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(m_socket_fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    
    if (::connect(m_socket_fd, (struct sockaddr*)&m_server_addr, sizeof(m_server_addr)) < 0) {
        close(m_socket_fd);
        m_socket_fd = -1;
        return false;
    }
    
    if (m_use_ssl) {
        SSL_library_init();
        SSL_load_error_strings();
        OpenSSL_add_all_algorithms();
        
        m_ssl_ctx = SSL_CTX_new(TLS_client_method());
        if (!m_ssl_ctx) return false;
        
        m_ssl = SSL_new(m_ssl_ctx);
        SSL_set_fd(m_ssl, m_socket_fd);
        
        if (SSL_connect(m_ssl) <= 0) return false;
    }
    
    m_connected.store(true);
    return true;
}

bool StratumClient::login() {
    std::string login_str = "{\"id\":1,\"method\":\"login\",\"params\":{\"login\":\"" + m_wallet + "\",\"pass\":\"" + m_password + "\",\"agent\":\"CPPMiner/1.0\"}}\n";
    send_message(login_str);
    return true;
}

void StratumClient::send_message(const std::string& message) {
    std::lock_guard<std::mutex> lock(m_send_mutex);
    
    if (m_use_ssl && m_ssl) {
        SSL_write(m_ssl, message.c_str(), message.length());
    } else {
        send(m_socket_fd, message.c_str(), message.length(), MSG_NOSIGNAL);
    }
}

void StratumClient::start_receive_loop() {
    m_running.store(true);
    m_receive_thread = std::thread([this]() {
        char recv_buffer[65536];
        
        while (m_running.load()) {
            int bytes_received;
            if (m_use_ssl && m_ssl) {
                bytes_received = SSL_read(m_ssl, recv_buffer, sizeof(recv_buffer) - 1);
            } else {
                bytes_received = recv(m_socket_fd, recv_buffer, sizeof(recv_buffer) - 1, 0);
            }
            
            if (bytes_received <= 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
                m_connected.store(false);
                break;
            }
            
            recv_buffer[bytes_received] = '\0';
            m_buffer += std::string(recv_buffer, bytes_received);
            
            size_t pos;
            while ((pos = m_buffer.find('\n')) != std::string::npos) {
                std::string line = m_buffer.substr(0, pos);
                m_buffer.erase(0, pos + 1);
                if (!line.empty()) {
                    handle_message(line);
                }
            }
        }
    });
}

void StratumClient::handle_message(const std::string& message) {
    // Simple JSON parsing
    if (message.find("\"method\"") != std::string::npos && message.find("\"job\"") != std::string::npos) {
        std::lock_guard<std::mutex> lock(m_job_mutex);
        m_current_job.job_id = "job";
        m_current_job.blob = "";
        m_current_job.target = "";
        m_current_job.seed_hash = "";
        m_current_job.height = 0;
        m_current_job.received_time = std::chrono::steady_clock::now();
        
        if (m_job_callback) {
            m_job_callback(m_current_job);
        }
    }
}

bool StratumClient::submit_share(const std::string& job_id,
                                 const std::string& nonce,
                                 const std::string& result) {
    std::string submit_str = "{\"id\":" + std::to_string(m_message_id++) + 
        ",\"method\":\"submit\",\"params\":{\"id\":\"" + m_worker + 
        "\",\"job_id\":\"" + job_id + 
        "\",\"nonce\":\"" + nonce + 
        "\",\"result\":\"" + result + "\"}}\n";
    send_message(submit_str);
    return true;
}

void StratumClient::stop() {
    m_running.store(false);
    if (m_receive_thread.joinable()) {
        m_receive_thread.join();
    }
    if (m_socket_fd >= 0) {
        close(m_socket_fd);
        m_socket_fd = -1;
    }
    m_connected.store(false);
}

void StratumClient::cleanup_ssl() {
    if (m_ssl) {
        SSL_shutdown(m_ssl);
        SSL_free(m_ssl);
        m_ssl = nullptr;
    }
    if (m_ssl_ctx) {
        SSL_CTX_free(m_ssl_ctx);
        m_ssl_ctx = nullptr;
    }
}

void StratumClient::set_job_callback(JobCallback callback) {
    m_job_callback = std::move(callback);
}

void StratumClient::set_share_callback(ShareCallback callback) {
    m_share_callback = std::move(callback);
}

MiningJob StratumClient::get_current_job() const {
    std::lock_guard<std::mutex> lock(m_job_mutex);
    return m_current_job;
}
