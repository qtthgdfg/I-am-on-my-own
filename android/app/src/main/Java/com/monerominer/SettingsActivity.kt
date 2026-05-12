package com.monerominer

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var prefs: SharedPreferences
    private lateinit var poolHostInput: EditText
    private lateinit var poolPortInput: EditText
    private lateinit var walletInput: EditText
    private lateinit var workerInput: EditText
    private lateinit var threadsSeekBar: SeekBar
    private lateinit var threadsText: TextView
    private lateinit var saveButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        prefs = getSharedPreferences("miner_config", MODE_PRIVATE)
        initViews()
        loadSettings()
        setupListeners()
    }
    
    private fun initViews() {
        poolHostInput = findViewById(R.id.pool_host_input)
        poolPortInput = findViewById(R.id.pool_port_input)
        walletInput = findViewById(R.id.wallet_input)
        workerInput = findViewById(R.id.worker_input)
        threadsSeekBar = findViewById(R.id.threads_seekbar)
        threadsText = findViewById(R.id.threads_text)
        saveButton = findViewById(R.id.save_button)
        
        threadsSeekBar.max = Runtime.getRuntime().availableProcessors()
    }
    
    private fun loadSettings() {
        val config = ConfigManager.getConfig(this)
        poolHostInput.setText(config.poolHost)
        poolPortInput.setText(config.poolPort.toString())
        walletInput.setText(config.wallet)
        workerInput.setText(config.worker)
        threadsSeekBar.progress = config.threads
        threadsText.text = "Threads: ${config.threads}"
    }
    
    private fun setupListeners() {
        threadsSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                threadsText.text = "Threads: ${if (progress == 0) 1 else progress}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        saveButton.setOnClickListener {
            saveSettings()
        }
    }
    
    private fun saveSettings() {
        val config = MinerConfig(
            poolHost = poolHostInput.text.toString().trim().ifEmpty { "pool.supportxmr.com" },
            poolPort = poolPortInput.text.toString().toIntOrNull() ?: 3333,
            wallet = walletInput.text.toString().trim(),
            worker = workerInput.text.toString().trim().ifEmpty { "android_miner" },
            password = "x",
            threads = if (threadsSeekBar.progress == 0) 1 else threadsSeekBar.progress,
            useSSL = false
        )
        ConfigManager.saveConfig(this, config)
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
