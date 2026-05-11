package com.monerominer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MinerUIState(
    val isMining: Boolean = false,
    val status: String = "Stopped",
    val hashrate: Double = 0.0,
    val hashrateFormatted: String = "0 H/s",
    val acceptedShares: Int = 0,
    val rejectedShares: Int = 0,
    val uptime: Long = 0L,
    val uptimeFormatted: String = "00:00:00",
    val poolHost: String = "",
    val poolPort: Int = 3333,
    val wallet: String = "",
    val worker: String = "android_miner",
    val threads: Int = 2,
    val errors: List<String> = emptyList(),
    val logs: List<String> = emptyList()
)

class MinerViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow(MinerUIState())
    val uiState: StateFlow<MinerUIState> = _uiState.asStateFlow()
    
    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage
    
    private var serviceBound = false
    private var minerService: MinerService? = null
    
    init {
        loadSavedConfig()
    }
    
    fun loadSavedConfig() {
        val config = ConfigManager.getConfig(getApplication())
        _uiState.value = _uiState.value.copy(
            poolHost = config.poolHost,
            poolPort = config.poolPort,
            wallet = config.wallet,
            worker = config.worker,
            threads = config.threads
        )
    }
    
    fun updatePoolHost(host: String) {
        _uiState.value = _uiState.value.copy(poolHost = host)
    }
    
    fun updatePoolPort(port: Int) {
        _uiState.value = _uiState.value.copy(poolPort = port)
    }
    
    fun updateWallet(wallet: String) {
        _uiState.value = _uiState.value.copy(wallet = wallet)
    }
    
    fun updateWorker(worker: String) {
        _uiState.value = _uiState.value.copy(worker = worker)
    }
    
    fun updateThreads(threads: Int) {
        val cpuCount = Runtime.getRuntime().availableProcessors()
        if (threads > cpuCount) {
            _toastMessage.value = "Threads exceed CPU cores ($cpuCount)"
            return
        }
        if (threads < 1) {
            _toastMessage.value = "Minimum 1 thread required"
            return
        }
        _uiState.value = _uiState.value.copy(threads = threads)
    }
    
    fun validateConfig(): Boolean {
        val state = _uiState.value
        
        if (state.wallet.isEmpty()) {
            _toastMessage.value = "Wallet address is required"
            return false
        }
        
        if (state.wallet.length < 95) {
            _toastMessage.value = "Invalid wallet address length"
            return false
        }
        
        if (state.poolHost.isEmpty()) {
            _toastMessage.value = "Pool host is required"
            return false
        }
        
        if (state.poolPort !in 1..65535) {
            _toastMessage.value = "Invalid pool port"
            return false
        }
        
        return true
    }
    
    fun startMining() {
        if (!validateConfig()) return
        
        val config = MinerConfig(
            poolHost = _uiState.value.poolHost,
            poolPort = _uiState.value.poolPort,
            wallet = _uiState.value.wallet,
            worker = _uiState.value.worker.ifEmpty { "android_miner" },
            password = "x",
            threads = _uiState.value.threads,
            useSSL = false
        )
        
        // Save config for next time
        ConfigManager.saveConfig(getApplication(), config)
        
        _uiState.value = _uiState.value.copy(
            isMining = true,
            status = "Starting...",
            errors = emptyList()
        )
        
        addLog("Starting miner...")
        addLog("Pool: ${config.poolHost}:${config.poolPort}")
        addLog("Threads: ${config.threads}")
        
        // Start service via intent
        val context = getApplication<Application>()
        val intent = android.content.Intent(context, MinerService::class.java).apply {
            action = MinerService.ACTION_START
            putExtra(MinerService.EXTRA_CONFIG, config)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        
        // Start monitoring loop
        startMonitoring()
    }
    
    fun stopMining() {
        val intent = android.content.Intent(
            getApplication(), 
            MinerService::class.java
        ).apply {
            action = MinerService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
        
        _uiState.value = _uiState.value.copy(
            isMining = false,
            status = "Stopped",
            hashrate = 0.0,
            hashrateFormatted = "0 H/s",
            acceptedShares = 0,
            rejectedShares = 0,
            uptime = 0L,
            uptimeFormatted = "00:00:00"
        )
        
        addLog("Miner stopped")
    }
    
    fun pauseMining() {
        val intent = android.content.Intent(
            getApplication(), 
            MinerService::class.java
        ).apply {
            action = MinerService.ACTION_PAUSE
        }
        getApplication<Application>().startService(intent)
        
        _uiState.value = _uiState.value.copy(
            isMining = false,
            status = "Paused"
        )
        
        addLog("Miner paused")
    }
    
    fun resumeMining() {
        val config = ConfigManager.getConfig(getApplication())
        val intent = android.content.Intent(
            getApplication(), 
            MinerService::class.java
        ).apply {
            action = MinerService.ACTION_RESUME
            putExtra(MinerService.EXTRA_CONFIG, config)
        }
        getApplication<Application>().startService(intent)
        
        _uiState.value = _uiState.value.copy(
            isMining = true,
            status = "Resuming..."
        )
        
        addLog("Miner resuming...")
        startMonitoring()
    }
    
    private fun startMonitoring() {
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            var totalHashes = 0L
            
            while (_uiState.value.isMining) {
                delay(1000) // Update every second
                
                val uptimeMillis = System.currentTimeMillis() - startTime
                val uptimeSeconds = uptimeMillis / 1000
                
                _uiState.value = _uiState.value.copy(
                    status = "Mining",
                    uptime = uptimeSeconds,
                    uptimeFormatted = formatUptime(uptimeSeconds)
                )
                
                // In a real implementation, these would come from the service
                // For now, simulate some stats
                totalHashes += (_uiState.value.threads * 500L) // Simulated
                
                val simulatedHashrate = _uiState.value.threads * 500.0
                _uiState.value = _uiState.value.copy(
                    hashrate = simulatedHashrate,
                    hashrateFormatted = formatHashrate(simulatedHashrate),
                    acceptedShares = (uptimeSeconds / 60).toInt().coerceAtMost(5),
                    rejectedShares = if (uptimeSeconds > 300) 1 else 0
                )
            }
        }
    }
    
    fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat(
            "HH:mm:ss", 
            java.util.Locale.getDefault()
        ).format(java.util.Date())
        
        val logEntry = "[$timestamp] $message"
        val currentLogs = _uiState.value.logs.toMutableList()
        currentLogs.add(logEntry)
        
        // Keep only last 500 lines
        if (currentLogs.size > 500) {
            currentLogs.removeAt(0)
        }
        
        _uiState.value = _uiState.value.copy(logs = currentLogs)
    }
    
    fun clearLogs() {
        _uiState.value = _uiState.value.copy(logs = emptyList())
    }
    
    fun onShareFound(jobId: String, nonce: String, hash: String) {
        addLog("🎯 Share found! Job: $jobId")
        val current = _uiState.value.acceptedShares
        _uiState.value = _uiState.value.copy(acceptedShares = current + 1)
    }
    
    fun onError(error: String) {
        addLog("❌ Error: $error")
        val currentErrors = _uiState.value.errors.toMutableList()
        currentErrors.add(error)
        _uiState.value = _uiState.value.copy(errors = currentErrors)
    }
    
    fun clearErrors() {
        _uiState.value = _uiState.value.copy(errors = emptyList())
    }
    
    private fun formatHashrate(hashesPerSecond: Double): String {
        return when {
            hashesPerSecond >= 1_000_000_000 -> 
                String.format("%.2f GH/s", hashesPerSecond / 1_000_000_000)
            hashesPerSecond >= 1_000_000 -> 
                String.format("%.2f MH/s", hashesPerSecond / 1_000_000)
            hashesPerSecond >= 1_000 -> 
                String.format("%.2f KH/s", hashesPerSecond / 1_000)
            else -> 
                String.format("%.0f H/s", hashesPerSecond)
        }
    }
    
    private fun formatUptime(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
    
    fun getOptimalThreadCount(): Int {
        val cpuCount = Runtime.getRuntime().availableProcessors()
        // Leave 1 core for system on devices with > 4 cores
        return if (cpuCount > 4) cpuCount - 1 else cpuCount
    }
    
    fun fetchPoolStats() {
        viewModelScope.launch {
            try {
                addLog("Fetching pool stats...")
                // In a real implementation, make API call to mining pool
                delay(1000)
                addLog("Pool connection: OK")
            } catch (e: Exception) {
                onError("Failed to fetch pool stats: ${e.message}")
            }
        }
    }
}
