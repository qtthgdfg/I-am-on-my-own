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
                val config = intent.getSerializableExtra(EXTRA_CONFIG) as MinerConfig
                startMining(config)
            }
            ACTION_STOP -> stopMining()
            ACTION_PAUSE -> pauseMining()
            ACTION_RESUME -> resumeMining()
        }
        
        return START_STICKY
    }

    fun startMining(config: MinerConfig) {
        if (minerRunning) return
        
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(config))
        
        scope.launch {
            try {
                val success = nativeStartMining(config, MinerCallback())
                minerRunning = success
                
                if (success) {
                    broadcastStatus(STATUS_MINING)
                    startStatsUpdate()
                } else {
                    broadcastStatus(STATUS_ERROR, "Failed to start miner")
                    stopSelf()
                }
            } catch (e: Exception) {
                broadcastStatus(STATUS_ERROR, e.message ?: "Unknown error")
                stopMining()
            }
        }
    }

    fun stopMining() {
        nativeStopMining()
        minerRunning = false
        releaseWakeLock()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun pauseMining() {
        nativeStopMining()
        minerRunning = false
        broadcastStatus(STATUS_PAUSED)
    }

    fun resumeMining() {
        val config = ConfigManager.getConfig(this)
        if (config != null) {
            startMining(config)
        }
    }

    fun isMining(): Boolean = minerRunning

    fun getStats(): String = nativeGetStats()

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MoneroMiner::WakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes timeout
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
                description = "Shows mining status and hashrate"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(config: MinerConfig): Notification {
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
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Monero Mining Active")
            .setContentText("Mining with ${config.threads} threads")
            .setSmallIcon(R.drawable.ic_mining)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startStatsUpdate() {
        scope.launch {
            while (isActive && minerRunning) {
                delay(5000) // Update every 5 seconds
                val stats = getStats()
                broadcastStatus(STATUS_STATS, stats)
            }
        }
    }

    private fun broadcastStatus(status: String, data: String = "") {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_DATA, data)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopMining()
        super.onDestroy()
    }

    // Native methods
    private external fun nativeStartMining(
        config: MinerConfig,
        callback: MinerCallback
    ): Boolean

    private external fun nativeStopMining()
    private external fun nativeGetStats(): String

    inner class MinerCallback {
        fun onShareFound(jobId: String, nonce: String, hash: String) {
            scope.launch {
                broadcastStatus(
                    STATUS_SHARE_FOUND,
                    "Job: $jobId, Nonce: $nonce, Hash: $hash"
                )
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
        
        const val STATUS_MINING = "mining"
        const val STATUS_PAUSED = "paused"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"
        const val STATUS_SHARE_FOUND = "share_found"
        const val STATUS_STATS = "stats"
    }
}
