// FILE: MoneroRandomXMiner/android/app/src/main/java/com/monerominer/MinerConfig.kt

package com.monerominer

import java.io.Serializable

data class SubaddressConfig(
    val enabled: Boolean = false,
    val miningSubaddress: String = "",
    val label: String = "Mining Rig 1",
    val rotateSubaddress: Boolean = false,
    val rotationIntervalDays: Int = 30,
    val additionalRigs: Map<String, String> = emptyMap()
) : Serializable

data class MinerConfig(
    val poolHost: String = "pool.supportxmr.com",
    val poolPort: Int = 3333,
    val wallet: String = "",
    val worker: String = "android_miner",
    val password: String = "x",
    val threads: Int = 2,
    val useSSL: Boolean = false,
    val hugePages: Boolean = false,   // Not supported on Android
    val numaAware: Boolean = false,    // Not applicable on mobile
    val initThreads: Int = 2,
    val scratchpadSize: Int = 0,
    val subaddress: SubaddressConfig = SubaddressConfig()
) : Serializable {
    
    /**
     * Returns the active mining address based on subaddress configuration
     */
    fun getActiveMiningAddress(rigName: String = ""): String {
        return if (subaddress.enabled) {
            when {
                // If a specific rig name is provided and exists in additional rigs
                rigName.isNotEmpty() && subaddress.additionalRigs.containsKey(rigName) ->
                    subaddress.additionalRigs[rigName] ?: wallet
                
                // Use the mining subaddress if enabled
                subaddress.miningSubaddress.isNotEmpty() ->
                    subaddress.miningSubaddress
                
                // Fallback to main wallet
                else -> wallet
            }
        } else {
            // Subaddress disabled, use main wallet
            wallet
        }
    }
    
    /**
     * Get all configured rig names
     */
    fun getRigNames(): List<String> {
        return listOf("default") + subaddress.additionalRigs.keys.toList()
    }
    
    /**
     * Get worker name for a specific rig
     */
    fun getWorkerName(rigName: String = ""): String {
        return if (rigName.isNotEmpty() && rigName != "default") {
            "${worker}_${rigName}"
        } else {
            worker
        }
    }
}
