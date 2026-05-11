package com.monerominer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var hashrateText: TextView
    private lateinit var sharesText: TextView
    private lateinit var logText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    
    private lateinit var poolHostInput: TextInputEditText
    private lateinit var poolPortInput: TextInputEditText
    private lateinit var threadsInput: TextInputEditText
    private lateinit var walletInput: TextInputEditText
    private lateinit var workerInput: TextInputEditText

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(MinerService.EXTRA_STATUS)) {
                MinerService.STATUS_MINING -> updateUI(true)
                MinerService.STATUS_STOPPED -> updateUI(false)
                MinerService.STATUS_PAUSED -> {
                    statusText.text = getString(R.string.status_paused)
                    enableControls(true)
                }
                MinerService.STATUS_ERROR -> {
                    val error = intent.getStringExtra(MinerService.EXTRA_DATA)
                    statusText.text = "Error: $error"
                    enableControls(true)
                }
                MinerService.STATUS_STATS -> {
                    val stats = intent.getStringExtra(MinerService.EXTRA_DATA)
                    parseStats(stats)
                }
                MinerService.STATUS_SHARE_FOUND -> {
                    val data = intent.getStringExtra(MinerService.EXTRA_DATA)
                    appendLog("🎯 Share found! $data")
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            appendLog("✅ Notification permission granted")
        } else {
            appendLog("⚠️ Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initializeViews()
        setupButtons()
        loadSavedConfig()
        
        registerReceiver(statusReceiver, IntentFilter(MinerService.ACTION_STATUS_UPDATE))
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.status_text)
        hashrateText = findViewById(R.id.hashrate_text)
        sharesText = findViewById(R.id.shares_text)
        logText = findViewById(R.id.log_text).apply {
            movementMethod = ScrollingMovementMethod()
        }
        
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        
        poolHostInput = findViewById(R.id.pool_host_input)
        poolPortInput = findViewById(R.id.pool_port_input)
        threadsInput = findViewById(R.id.threads_input)
        walletInput = findViewById(R.id.wallet_input)
        workerInput = findViewById(R.id.worker_input)
    }

    private fun setupButtons() {
        startButton.setOnClickListener {
            if (validateInputs()) {
                startMining()
            }
        }
        
        stopButton.setOnClickListener {
            stopMining()
        }
    }

    private fun validateInputs(): Boolean {
        val wallet = walletInput.text.toString().trim()
        
        if (wallet.isEmpty()) {
            Toast.makeText(this, "Wallet address is required", Toast.LENGTH_LONG).show()
            return false
        }
        
        if (wallet.length < 95) {
            Toast.makeText(this, "Invalid wallet address", Toast.LENGTH_LONG).show()
            return false
        }
        
        val threads = threadsInput.text.toString().toIntOrNull() ?: 1
        val cpuCount = Runtime.getRuntime().availableProcessors()
        
        if (threads > cpuCount) {
            Toast.makeText(this, 
                "Threads exceed CPU cores ($cpuCount)", 
                Toast.LENGTH_LONG).show()
            return false
        }
        
        return true
    }

    private fun startMining() {
        saveConfig()
        
        val config = MinerConfig(
            poolHost = poolHostInput.text.toString().trim(),
            poolPort = poolPortInput.text.toString().toIntOrNull() ?: 3333,
            wallet = walletInput.text.toString().trim(),
            worker = workerInput.text.toString().trim().ifEmpty { "android_miner" },
            password = "x",
            threads = threadsInput.text.toString().toIntOrNull() ?: 2,
            useSSL = false
        )
        
        val intent = Intent(this, MinerService::class.java).apply {
            action = MinerService.ACTION_START
            putExtra(MinerService.EXTRA_CONFIG, config)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        enableControls(false)
        statusText.text = getString(R.string.status_mining)
        appendLog("🚀 Starting Monero RandomX miner...")
        appendLog("Pool: ${config.poolHost}:${config.poolPort}")
        appendLog("Threads: ${config.threads}")
    }

    private fun stopMining() {
        val intent = Intent(this, MinerService::class.java).apply {
            action = MinerService.ACTION_STOP
        }
        startService(intent)
        
        updateUI(false)
        appendLog("⏹️ Miner stopped")
    }

    private fun updateUI(mining: Boolean) {
        statusText.text = if (mining) getString(R.string.status_mining) 
                         else getString(R.string.status_stopped)
        enableControls(!mining)
    }

    private fun enableControls(enabled: Boolean) {
        startButton.isEnabled = enabled
        stopButton.isEnabled = !enabled
        
        listOf(poolHostInput, poolPortInput, threadsInput, 
               walletInput, workerInput).forEach {
            it.isEnabled = enabled
        }
    }

    private fun parseStats(stats: String) {
        try {
            // Simple JSON parsing without Gson
            if (stats.contains("\"hashrate\"")) {
                val hashrate = stats.split("\"hashrate\":")[1]
                    .split(",")[0].trim().toDoubleOrNull() ?: 0.0
                
                hashrateText.text = when {
                    hashrate >= 1_000_000 -> String.format("%.2f MH/s", hashrate / 1_000_000)
                    hashrate >= 1_000 -> String.format("%.2f KH/s", hashrate / 1_000)
                    else -> String.format("%.0f H/s", hashrate)
                }
            }
        } catch (e: Exception) {
            hashrateText.text = "0 H/s"
        }
    }

    private fun appendLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        
        runOnUiThread {
            logText.append("[$timestamp] $message\n")
            
            // Auto-scroll to bottom
            val scrollView = logText.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun saveConfig() {
        getSharedPreferences("miner_config", MODE_PRIVATE).edit().apply {
            putString("pool_host", poolHostInput.text.toString().trim())
            putString("pool_port", poolPortInput.text.toString().trim())
            putString("wallet", walletInput.text.toString().trim())
            putString("worker", workerInput.text.toString().trim())
            putString("threads", threadsInput.text.toString().trim())
            apply()
        }
    }

    private fun loadSavedConfig() {
        getSharedPreferences("miner_config", MODE_PRIVATE).apply {
            poolHostInput.setText(getString("pool_host", "pool.supportxmr.com"))
            poolPortInput.setText(getString("pool_port", "3333"))
            walletInput.setText(getString("wallet", ""))
            workerInput.setText(getString("worker", "android_miner"))
            threadsInput.setText(getString("threads", "2"))
        }
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
