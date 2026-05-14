package com.monerominer

import android.content.Context
import android.content.SharedPreferences

data class PoolConfig(
    val host: String = "pool.supportxmr.com",
    val sslPorts: List<Int> = listOf(443, 3333),
    val tcpPorts: List<Int> = listOf(3333, 5555, 4444)
)

data class PerformanceConfig(
    val threads: Int = 2,
    val maxCpuUsage: Int = 75
)

data class MinerConfig(
    val pool: PoolConfig = PoolConfig(),
    val worker: String = "android_miner",
    val password: String = "x",
    val performance: PerformanceConfig = PerformanceConfig(),
    val subaddressEnabled: Boolean = false,
    val walletPrimary: String = ""
)

object ConfigManager {
    private const val PREFS_NAME = "miner_config"

    fun saveConfig(context: Context, config: MinerConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("pool_host", config.pool.host)
            putString("worker", config.worker)
            putString("password", config.password)
            putInt("threads", config.performance.threads)
            putInt("max_cpu", config.performance.maxCpuUsage)
            putBoolean("subaddress_enabled", config.subaddressEnabled)
            putString("wallet_primary", config.walletPrimary)
            apply()
        }
    }

    fun getConfig(context: Context): MinerConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return MinerConfig(
            pool = PoolConfig(
                host = prefs.getString("pool_host", "pool.supportxmr.com") ?: "pool.supportxmr.com"
            ),
            worker = prefs.getString("worker", "android_miner") ?: "android_miner",
            password = prefs.getString("password", "x") ?: "x",
            performance = PerformanceConfig(
                threads = prefs.getInt("threads", 2),
                maxCpuUsage = prefs.getInt("max_cpu", 75)
            ),
            subaddressEnabled = prefs.getBoolean("subaddress_enabled", false),
            walletPrimary = prefs.getString("wallet_primary", "") ?: ""
        )
    }
}
