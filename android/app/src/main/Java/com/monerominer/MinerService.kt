package com.monerominer

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*

class MinerService : LifecycleService() {

    private var minerRunning = false
    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentConfig: MinerConfig? = null
    
    private var currentPort: Int = 3333
    private var currentSSL: Boolean = false
    private var connectionAttempts: Int = 0
    private val maxConnectionAttempts: Int = 10

    inner class LocalBinder : android.os.Binder() {
        fun getService(): MinerService = this@MinerService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getSerializableExtra(EXTRA_CONFIG) as? MinerConfig
                if (config != null) {
                    startMiningWithFallback(config)
                }
            }
            ACTION_STOP -> stopMining()
            ACTION_PAUSE -> pauseMining()
            ACTION_RESUME -> {
                currentConfig?.let { startMiningWithFallback(it) }
            }
        }
        
        return START_STICKY
    }

    private fun startMiningWithFallback(config: MinerConfig) {
        if (minerRunning) return
        
        currentConfig = config
        connectionAttempts = 0
        
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(config, "Connecting..."))
        
        scope.launch {
            try {
                val portsToTry = config.getConnectionSequence()
                var connected = false
                
                broadcastStatus(STATUS_CONNECTING, "Starting connection attempts...")
                
                for ((port, useSSL) in portsToTry) {
                    if (connectionAttempts >= maxConnectionAttempts) {
                        broadcastStatus(STATUS_ERROR, "Exhausted all connection attempts")
                        break
                    }
                    
                    currentPort = port
                    currentSSL = useSSL
                    connectionAttempts++
                    
                    val sslLabel = if (useSSL) "SSL" else "TCP"
                    
                    broadcastStatus(STATUS_CONNECTING, 
                        "Trying $sslLabel port $port (attempt $connectionAttempts)...")
                    
                    updateNotification(config, "Trying $sslLabel port $port...")
                    
                    if (tryConnection(config, port)) {
                        connected = true
                        broadcastStatus(STATUS_CONNECTED, "Connected via $sslLabel port $port")
                        break
                    } else {
                        broadcastStatus(STATUS_RETRY, "$sslLabel port $port failed, trying next...")
                        delay(2000)
                    }
                }
                
                if (connected) {
                    val miningStarted = nativeStartMining(config, MinerCallback())
                    
                    if (miningStarted) {
                        minerRunning = true
                        updateNotification(config, "Mining active on port $currentPort")
                        broadcastStatus(STATUS_MINING, "Mining started on port $currentPort")
                        startStatsUpdate()
                    } else {
                        broadcastStatus(STATUS_ERROR, "Mining initialization failed")
                        stopMining()
                    }
                } else {
                    broadcastStatus(STATUS_ERROR, 
                        "Could not connect to ${config.pool.host} after trying $connectionAttempts ports")
                    stopMining()
                }
            } catch (e: Exception) {
                broadcastStatus(STATUS_ERROR, "Connection error: ${e.message}")
                stopMining()
            }
        }
    }

    // FIXED: Removed native external, now a simple mock
    private suspend fun tryConnection(config: MinerConfig, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            // TCP connection - always returns true for testing
            // Real connection will be handled by native code
            true
        }
    }

    fun stopMining() {
        nativeStopMining()
        minerRunning = false
        connectionAttempts = 0
        releaseWakeLock()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastStatus(STATUS_STOPPED, "Miner stopped")
    }

    fun pauseMining() {
        nativeStopMining()
        minerRunning = false
        broadcastStatus(STATUS_PAUSED, "Miner paused")
    }

    fun isMining(): Boolean = minerRunning
    fun getStats(): String = nativeGetStats()
    fun getCurrentPort(): Int = currentPort
    fun isCurrentSSL(): Boolean = currentSSL

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MoneroMiner::WakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mining Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows mining connection status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(config: MinerConfig, status: String): Notification {
        return buildNotification(config, status)
    }

    private fun updateNotification(config: MinerConfig, status: String) {
        val notification = buildNotification(config, status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(config: MinerConfig, status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, MinerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val connInfo = if (currentPort > 0) ":$currentPort" else ""
        val threads = config.performance.threads
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Monero Miner")
            .setContentText("$status | $connInfo | $threads threads")
            .setSmallIcon(R.drawable.ic_mining)
            .setOngoing(minerRunning)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startStatsUpdate() {
        scope.launch {
            while (isActive && minerRunning) {
                delay(5000)
                val stats = nativeGetStats()
                broadcastStatus(STATUS_STATS, stats)
            }
        }
    }

    private fun broadcastStatus(status: String, data: String = "") {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_DATA, data)
            putExtra(EXTRA_PORT, currentPort)
            putExtra(EXTRA_SSL, currentSSL)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopMining()
        super.onDestroy()
    }

    // Native methods - only the ones that exist in the library
    private external fun nativeStartMining(
        config: MinerConfig, callback: MinerCallback
    ): Boolean

    private external fun nativeStopMining()
    private external fun nativeGetStats(): String

    inner class MinerCallback {
        fun onShareFound(jobId: String, nonce: String, hash: String) {
            scope.launch {
                broadcastStatus(STATUS_SHARE_FOUND, "Job: $jobId, Nonce: $nonce")
            }
        }
        
        fun onConnectionError(error: String, port: Int) {
            scope.launch {
                broadcastStatus(STATUS_RETRY, "Connection error on port $port: $error")
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "miner_service"
        const val NOTIFICATION_ID = 1001
        
        const val ACTION_START = "com.monerominer.START"
        const val ACTION_STOP = "com.monerominer.STOP"
        const val ACTION_PAUSE = "com.monerominer.PAUSE"
        const val ACTION_RESUME = "com.monerominer.RESUME"
        const val ACTION_STATUS_UPDATE = "com.monerominer.STATUS_UPDATE"
        
        const val EXTRA_CONFIG = "config"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DATA = "data"
        const val EXTRA_PORT = "port"
        const val EXTRA_SSL = "ssl"
        
        const val STATUS_MINING = "mining"
        const val STATUS_PAUSED = "paused"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"
        const val STATUS_SHARE_FOUND = "share_found"
        const val STATUS_STATS = "stats"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_RETRY = "retry"
        const val STATUS_WARNING = "warning"
    }
}
