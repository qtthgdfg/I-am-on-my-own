// FILE: MoneroRandomXMiner/android/app/src/main/java/com/monerominer/ConfigManager.kt

package com.monerominer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object ConfigManager {
    
    private const val PREF_NAME = "miner_config"
    
    // Subaddress preference keys
    private const val KEY_SUBADDRESS_ENABLED = "subaddress_enabled"
    private const val KEY_MINING_SUBADDRESS = "mining_subaddress"
    private const val KEY_SUBADDRESS_LABEL = "subaddress_label"
    private const val KEY_ROTATE_SUBADDRESS = "rotate_subaddress"
    private const val KEY_ROTATION_INTERVAL = "rotation_interval_days"
    private const val KEY_ADDITIONAL_RIGS = "additional_rigs_json"
    
    fun saveConfig(context: Context, config: MinerConfig) {
        getPrefs(context).edit().apply {
            putString("pool_host", config.poolHost)
            putInt("pool_port", config.poolPort)
            putString("wallet", config.wallet)
            putString("worker", config.worker)
            putString("password", config.password)
            putInt("threads", config.threads)
            putBoolean("use_ssl", config.useSSL)
            putInt("init_threads", config.initThreads)
            putInt("scratchpad_size", config.scratchpadSize)
            
            // Save subaddress config
            putBoolean(KEY_SUBADDRESS_ENABLED, config.subaddress.enabled)
            putString(KEY_MINING_SUBADDRESS, config.subaddress.miningSubaddress)
            putString(KEY_SUBADDRESS_LABEL, config.subaddress.label)
            putBoolean(KEY_ROTATE_SUBADDRESS, config.subaddress.rotateSubaddress)
            putInt(KEY_ROTATION_INTERVAL, config.subaddress.rotationIntervalDays)
            
            // Save additional rigs as JSON
            val rigsJson = JSONObject(config.subaddress.additionalRigs).toString()
            putString(KEY_ADDITIONAL_RIGS, rigsJson)
            
            apply()
        }
    }
    
    fun getConfig(context: Context): MinerConfig {
        val prefs = getPrefs(context)
        
        // Parse additional rigs from JSON
        val additionalRigs = try {
            val rigsJson = prefs.getString(KEY_ADDITIONAL_RIGS, "{}") ?: "{}"
            val jsonObject = JSONObject(rigsJson)
            val rigsMap = mutableMapOf<String, String>()
            
            jsonObject.keys().forEach { key ->
                rigsMap[key] = jsonObject.getString(key)
            }
            rigsMap
        } catch (e: Exception) {
            emptyMap()
        }
        
        return MinerConfig(
            poolHost = prefs.getString("pool_host", "pool.supportxmr.com") ?: "pool.supportxmr.com",
            poolPort = prefs.getInt("pool_port", 3333),
            wallet = prefs.getString("wallet", "") ?: "",
            worker = prefs.getString("worker", "android_miner") ?: "android_miner",
            password = prefs.getString("password", "x") ?: "x",
            threads = prefs.getInt("threads", 2),
            useSSL = prefs.getBoolean("use_ssl", false),
            initThreads = prefs.getInt("init_threads", 2),
            scratchpadSize = prefs.getInt("scratchpad_size", 0),
            subaddress = SubaddressConfig(
                enabled = prefs.getBoolean(KEY_SUBADDRESS_ENABLED, false),
                miningSubaddress = prefs.getString(KEY_MINING_SUBADDRESS, "") ?: "",
                label = prefs.getString(KEY_SUBADDRESS_LABEL, "Mining Rig 1") ?: "Mining Rig 1",
                rotateSubaddress = prefs.getBoolean(KEY_ROTATE_SUBADDRESS, false),
                rotationIntervalDays = prefs.getInt(KEY_ROTATION_INTERVAL, 30),
                additionalRigs = additionalRigs
            )
        )
    }
    
    /**
     * Save the default config with subaddresses from the provided data
     */
    fun importConfigFromJson(context: Context, jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val config = MinerConfig(
                poolHost = json.optString("pool_host", "pool.supportxmr.com"),
                poolPort = json.optInt("pool_port", 3333),
                wallet = json.optString("wallet", ""),
                worker = json.optString("worker", "android_miner"),
                password = json.optString("password", "x"),
                threads = json.optInt("threads", 2),
                useSSL = json.optBoolean("use_ssl", false),
                initThreads = json.optInt("init_threads", 2),
                scratchpadSize = json.optInt("scratchpad_size", 0)
            )
            
            // Parse subaddress if present
            if (json.has("subaddress")) {
                val subJson = json.getJSONObject("subaddress")
                val additionalRigs = mutableMapOf<String, String>()
                
                if (subJson.has("additional_rigs")) {
                    val rigsJson = subJson.getJSONObject("additional_rigs")
                    rigsJson.keys().forEach { key ->
                        additionalRigs[key] = rigsJson.getString(key)
                    }
                }
                
                val subConfig = SubaddressConfig(
                    enabled = subJson.optBoolean("enabled", false),
                    miningSubaddress = subJson.optString("mining_subaddress", ""),
                    label = subJson.optString("label", "Mining Rig 1"),
                    rotateSubaddress = subJson.optBoolean("rotate_subaddress", false),
                    rotationIntervalDays = subJson.optInt("rotation_interval_days", 30),
                    additionalRigs = additionalRigs
                )
                
                saveConfig(context, config.copy(subaddress = subConfig))
            } else {
                saveConfig(context, config)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun clearConfig(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
}
