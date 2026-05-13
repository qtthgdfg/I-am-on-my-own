package com.monerominer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var hashrateText: TextView
    private lateinit var logText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var walletInput: EditText
    private lateinit var poolHostInput: EditText
    private lateinit var threadsInput: EditText
    private var isMining = false
    private val prefs by lazy { getSharedPreferences("miner_config", MODE_PRIVATE) }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val status = intent?.getStringExtra("status") ?: return
                val data = intent?.getStringExtra("data") ?: ""
                
                when (status) {
                    "mining" -> {
                        isMining = true
                        statusText.text = "Status: Mining Active"
                        startButton.isEnabled = false
                        stopButton.isEnabled = true
                    }
                    "stopped" -> {
                        isMining = false
                        statusText.text = "Status: Stopped"
                        startButton.isEnabled = true
                        stopButton.isEnabled = false
                    }
                    "stats" -> {
                        try {
                            val h = data.split("\"hashrate\":")[1].split(",")[0].trim().toDoubleOrNull() ?: 0.0
                            hashrateText.text = String.format("%.0f H/s", h)
                        } catch (e: Exception) {
                            CrashLogger.log("Stats parse error: ${e.message}")
                        }
                    }
                    "share_found" -> appendLog("Share found! $data")
                    "error" -> appendLog("Error: $data")
                    "connecting" -> appendLog("Connecting: $data")
                }
            } catch (e: Exception) {
                CrashLogger.log("BroadcastReceiver error: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        CrashLogger.log("MainActivity onCreate started")
        
        try {
            // Create layout in code
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 40, 32, 32)
                setBackgroundColor(0xFF1A1A2E.toInt())
            }
            
            val scrollView = ScrollView(this)
            val innerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            
            // Title
            innerLayout.addView(TextView(this).apply {
                text = "Monero RandomX Miner"
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 0, 0, 24)
            })
            
            // Status
            statusText = TextView(this).apply {
                text = "Status: Ready"
                textSize = 16f
                setTextColor(0xFF00C853.toInt())
                setPadding(0, 0, 0, 8)
            }
            innerLayout.addView(statusText)
            
            // Hashrate
            hashrateText = TextView(this).apply {
                text = "0 H/s"
                textSize = 14f
                setTextColor(0xFFB0B0B0.toInt())
                setPadding(0, 0, 0, 24)
            }
            innerLayout.addView(hashrateText)
            
            // Pool Host
            innerLayout.addView(TextView(this).apply {
                text = "Pool Host"
                textSize = 12f
                setTextColor(0xFF8899AA.toInt())
            })
            poolHostInput = EditText(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF16213E.toInt())
                setPadding(16, 12, 16, 12)
                minHeight = 44
                setText(prefs.getString("pool_host", "pool.supportxmr.com"))
            }
            innerLayout.addView(poolHostInput)
            innerLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 8) })
            
            // Threads
            innerLayout.addView(TextView(this).apply {
                text = "Threads"
                textSize = 12f
                setTextColor(0xFF8899AA.toInt())
            })
            threadsInput = EditText(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF16213E.toInt())
                setPadding(16, 12, 16, 12)
                minHeight = 44
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(prefs.getString("threads", "2"))
            }
            innerLayout.addView(threadsInput)
            innerLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 8) })
            
            // Wallet
            innerLayout.addView(TextView(this).apply {
                text = "Wallet Address"
                textSize = 12f
                setTextColor(0xFF8899AA.toInt())
            })
            walletInput = EditText(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF16213E.toInt())
                setPadding(16, 12, 16, 12)
                minHeight = 44
                hint = "4..."
                setHintTextColor(0xFF556677.toInt())
                setText(prefs.getString("wallet", ""))
            }
            innerLayout.addView(walletInput)
            innerLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 16) })
            
            // Buttons
            val buttonLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            
            startButton = Button(this).apply {
                text = "START"
                setBackgroundColor(0xFF00C853.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener { startMining() }
            }
            buttonLayout.addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
            buttonLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8, 0) })
            
            stopButton = Button(this).apply {
                text = "STOP"
                setBackgroundColor(0xFFE94560.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                isEnabled = false
                setOnClickListener { stopMining() }
            }
            buttonLayout.addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f))
            
            innerLayout.addView(buttonLayout)
            innerLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 16) })
            
            // Log
            logText = TextView(this).apply {
                text = "Ready to mine...\n"
                textSize = 11f
                setTextColor(0xFF8899AA.toInt())
                setBackgroundColor(0xFF0F3460.toInt())
                setPadding(12, 12, 12, 12)
                minHeight = 200
            }
            innerLayout.addView(logText)
            
            // View Log Button
            innerLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 12) })
            innerLayout.addView(Button(this).apply {
                text = "VIEW CRASH LOG"
                setBackgroundColor(0xFFFF9800.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    val logFile = CrashLogger.getLogFile()
                    if (logFile != null && logFile.exists()) {
                        val logContent = logFile.readText()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, logContent)
                            putExtra(Intent.EXTRA_SUBJECT, "Monero Miner Crash Log")
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share Log"))
                        CrashLogger.log("User viewed crash log")
                    } else {
                        Toast.makeText(this@MainActivity, "No log file found", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            
            scrollView.addView(innerLayout)
            layout.addView(scrollView)
            setContentView(layout)
            
            // Register receiver
            val filter = IntentFilter("com.monerominer.STATUS_UPDATE")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(statusReceiver, filter)
            }
            
            // Request notification permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        1001
                    )
                }
            }
            
            CrashLogger.log("MainActivity onCreate completed successfully")
        } catch (e: Exception) {
            CrashLogger.log("MainActivity onCreate CRASHED: ${e.message}")
            CrashLogger.logException(e)
            Toast.makeText(this, "App error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun startMining() {
        try {
            CrashLogger.log("User pressed START")
            
            val wallet = walletInput.text.toString().trim()
            if (wallet.isEmpty()) {
                Toast.makeText(this, "Enter wallet address", Toast.LENGTH_SHORT).show()
                CrashLogger.log("Start failed: empty wallet")
                return
            }
            
            CrashLogger.log("Wallet: ${wallet.take(6)}...${wallet.takeLast(4)}")
            
            // Save config
            prefs.edit().apply {
                putString("pool_host", poolHostInput.text.toString().trim())
                putString("wallet", wallet)
                putString("threads", threadsInput.text.toString().trim())
                apply()
            }
            
            val threads = threadsInput.text.toString().toIntOrNull() ?: 2
            val poolHost = poolHostInput.text.toString().trim()
            
            CrashLogger.log("Pool: $poolHost, Threads: $threads")
            
            val config = MinerConfig(
                pool = PoolConfig(host = poolHost),
                worker = "android_miner",
                performance = PerformanceConfig(threads = threads)
            )
            
            val intent = Intent(this, MinerService::class.java).apply {
                action = "com.monerominer.START"
                putExtra("config", config)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            statusText.text = "Status: Mining Active"
            startButton.isEnabled = false
            stopButton.isEnabled = true
            
            appendLog("Starting miner...")
            appendLog("Pool: ${config.pool.host}")
            appendLog("Threads: ${config.performance.threads}")
            
            CrashLogger.log("Mining started successfully")
        } catch (e: Exception) {
            CrashLogger.log("Start mining CRASHED: ${e.message}")
            CrashLogger.logException(e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun stopMining() {
        try {
            CrashLogger.log("User pressed STOP")
            val intent = Intent(this, MinerService::class.java).apply {
                action = "com.monerominer.STOP"
            }
            startService(intent)
            statusText.text = "Status: Stopped"
            startButton.isEnabled = true
            stopButton.isEnabled = false
            appendLog("Miner stopped")
            CrashLogger.log("Mining stopped")
        } catch (e: Exception) {
            CrashLogger.log("Stop mining error: ${e.message}")
        }
    }
    
    private fun appendLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        runOnUiThread {
            logText.text = "${logText.text}[$timestamp] $message\n"
        }
    }
    
    override fun onDestroy() {
        CrashLogger.log("MainActivity onDestroy")
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {
            CrashLogger.log("Unregister receiver error: ${e.message}")
        }
        super.onDestroy()
    }
}
