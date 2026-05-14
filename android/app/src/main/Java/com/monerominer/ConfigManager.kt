package com.monerominer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class SubaddressConfig(
    val enabled: Boolean = false,
    val miningSubaddress: String = "",
    val label: String = "Mining Rig 1",
    val rotateSubaddress: Boolean = false,
    val rotationIntervalDays: Int = 30,
    val additionalRigs: Map<String, String> = emptyMap()
)

data class MinerConfig(
    val poolHost: String = "pool.supportxmr.com",
    val poolPort: Int = 3333,
    val wallet: String = "",
    val worker: String = "android_miner",
    val threads: Int = 2,
    val password: String = "x",
    val subaddress: SubaddressConfig = SubaddressConfig()
) {
    fun getRigNames(): List<String> {
        return listOf("default") + subaddress.additionalRigs.keys.toList()
    }
    
    fun getWorkerName(rigName: String): String {
        return if (rigName == "default") worker else "${worker}_$rigName"
    }
}

object ConfigManager {
    private const val PREFS_NAME = "miner_config"
    private const val KEY_CONFIG = "full_config"
    private val gson = Gson()

    fun saveConfig(context: Context, config: MinerConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = gson.toJson(config)
        prefs.edit().apply {
            putString(KEY_CONFIG, jsonString)
            apply()
        }
    }

    fun getConfig(context: Context): MinerConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_CONFIG, null)
        return if (jsonString != null) {
            try {
                gson.fromJson(jsonString, MinerConfig::class.java)
            } catch (e: Exception) {
                MinerConfig()
            }
        } else {
            MinerConfig()
        }
    }

    fun importConfigFromJson(context: Context, jsonString: String) {
        try {
            val config = gson.fromJson(jsonString, MinerConfig::class.java)
            saveConfig(context, config)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
