package com.monerominer

import android.content.Context
import android.content.SharedPreferences

object ConfigManager {
    
    private const val PREF_NAME = "miner_config"
    
    fun saveConfig(context: Context, config: MinerConfig) {
        getPrefs(context).edit().apply {
            putString("pool_host", config.poolHost)
            putInt("pool_port", config.poolPort)
            putString("wallet", config.wallet)
            putString("worker", config.worker)
            putString("password", config.password)
            putInt("threads", config.threads)
            putBoolean("use_ssl", config.useSSL)
            apply()
        }
    }
    
    fun getConfig(context: Context): MinerConfig {
        val prefs = getPrefs(context)
        return MinerConfig(
            poolHost = prefs.getString("pool_host", "pool.supportxmr.com") ?: "pool.supportxmr.com",
            poolPort = prefs.getInt("pool_port", 3333),
            wallet = prefs.getString("wallet", "") ?: "",
            worker = prefs.getString("worker", "android_miner") ?: "android_miner",
            password = prefs.getString("password", "x") ?: "x",
            threads = prefs.getInt("threads", 2),
            useSSL = prefs.getBoolean("use_ssl", false)
        )
    }
    
    fun clearConfig(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
}
