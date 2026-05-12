package com.monerominer

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.Serializable
import java.util.UUID

/**
 * Represents a Monero subaddress for mining
 */
data class Subaddress(
    val address: String = "",
    val label: String = "",
    val isActive: Boolean = true,
    val index: Int = 0,
    val description: String = ""
) : Serializable {
    
    fun isValid(): Boolean {
        return address.isNotEmpty() && address.length == 95 && address.startsWith("8")
    }
    
    fun toShortString(): String {
        return if (address.length > 20) {
            "${address.take(10)}...${address.takeLast(10)}"
        } else address
    }
}

/**
 * Subaddress rotation strategy
 */
enum class RotationStrategy {
    FIXED,           // Use a single subaddress
    SEQUENTIAL,      // Rotate through subaddresses in order
    RANDOM,          // Random subaddress from the pool
    TIME_BASED,      // Rotate based on time intervals
    DAILY,           // Rotate every day
    WEEKLY,          // Rotate every week
    MONTHLY          // Rotate every month
}

/**
 * Mining pool connection strategy
 */
enum class ConnectionStrategy {
    SSL_ONLY,
    TCP_ONLY,
    SSL_FALLBACK_TO_TCP,
    TCP_FALLBACK_TO_SSL
}

/**
 * Represents a mining rig/worker
 */
data class Rig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Android Miner",
    val deviceModel: String = Build.MODEL,
    val subaddressIndex: Int = 0,
    val customWorkerName: String = "",
    val enabled: Boolean = true
) : Serializable {
    
    fun getEffectiveWorkerName(baseWorker: String): String {
        return when {
            customWorkerName.isNotEmpty() -> customWorkerName
            name != "Android Miner" -> "${baseWorker}_${name.replace(" ", "_")}"
            else -> baseWorker
        }
    }
}

/**
 * Record of a subaddress rotation
 */
data class RotationRecord(
    val fromSubaddress: Subaddress,
    val toSubaddress: Subaddress,
    val timestamp: Long,
    val reason: String
) : Serializable

/**
 * Complete subaddress management system
 */
data class SubaddressManager(
    val enabled: Boolean = false,
    val walletPrimary: String = "",
    val subaddresses: MutableList<Subaddress> = mutableListOf(),
    val rotationStrategy: RotationStrategy = RotationStrategy.FIXED,
    val rotationIntervalHours: Int = 24,
    val maxSubaddressCount: Int = 10,
    val currentSubaddressIndex: Int = 0,
    val rigs: MutableMap<String, Rig> = mutableMapOf(),
    val rotationHistory: MutableList<RotationRecord> = mutableListOf()
) : Serializable {
    
    fun getCurrentSubaddress(): Subaddress? {
        if (!enabled || subaddresses.isEmpty()) return null
        return subaddresses.getOrNull(currentSubaddressIndex)
    }
    
    fun getSubaddressForRig(rigName: String): String? {
        if (!enabled) return null
        val rig = rigs.values.find { it.name == rigName }
        return if (rig != null) {
            subaddresses.getOrNull(rig.subaddressIndex)?.address ?: getCurrentSubaddress()?.address
        } else {
            getCurrentSubaddress()?.address
        }
    }
    
    fun getActiveMiningAddress(rigName: String? = null): String {
        if (!enabled || subaddresses.isEmpty()) return walletPrimary
        return if (rigName != null) getSubaddressForRig(rigName) ?: walletPrimary
        else getCurrentSubaddress()?.address ?: walletPrimary
    }
    
    fun addSubaddress(address: String, label: String, description: String = ""): Result<Subaddress> {
        val subaddress = Subaddress(address = address, label = label, description = description, index = subaddresses.size)
        if (!subaddress.isValid()) return Result.failure(IllegalArgumentException("Invalid subaddress format"))
        if (subaddresses.size >= maxSubaddressCount) return Result.failure(IllegalStateException("Max subaddress count reached"))
        if (subaddresses.any { it.address == address }) return Result.failure(IllegalArgumentException("Subaddress already exists"))
        subaddresses.add(subaddress)
        return Result.success(subaddress)
    }
    
    fun removeSubaddress(index: Int): Result<Unit> {
        if (index < 0 || index >= subaddresses.size) return Result.failure(IndexOutOfBoundsException())
        if (subaddresses.size == 1 && rigs.isNotEmpty()) return Result.failure(IllegalStateException("Cannot remove last subaddress"))
        subaddresses.removeAt(index)
        return Result.success(Unit)
    }
    
    fun registerRig(name: String, deviceModel: String = ""): Rig {
        val existingRig = rigs.values.find { it.name == name }
        if (existingRig != null) return existingRig
        val rig = Rig(name = name, deviceModel = deviceModel)
        rigs[rig.id] = rig
        return rig
    }
    
    fun unregisterRig(rigId: String): Boolean = rigs.remove(rigId) != null
    
    fun getActiveRigs(): List<Rig> = rigs.values.filter { it.enabled }
    
    fun getRigNames(): List<String> {
        val names = mutableListOf("default")
        names.addAll(rigs.values.map { it.name })
        return names
    }
    
    fun needsRotation(): Boolean {
        if (!enabled || subaddresses.size <= 1) return false
        if (rotationStrategy == RotationStrategy.FIXED) return false
        
        val lastRotation = rotationHistory.lastOrNull()?.timestamp ?: System.currentTimeMillis()
        val timeSinceLastRotation = System.currentTimeMillis() - lastRotation
        
        return when (rotationStrategy) {
            RotationStrategy.TIME_BASED -> timeSinceLastRotation >= rotationIntervalHours * 3600_000L
            RotationStrategy.DAILY -> timeSinceLastRotation >= 24 * 3600_000L
            RotationStrategy.WEEKLY -> timeSinceLastRotation >= 7 * 24 * 3600_000L
            RotationStrategy.MONTHLY -> timeSinceLastRotation >= 30L * 24 * 3600_000L
            else -> false
        }
    }
    
    fun rotate(): Result<Subaddress> {
        if (!enabled || subaddresses.size <= 1) return Result.failure(IllegalStateException("Rotation not possible"))
        
        val nextIndex = when (rotationStrategy) {
            RotationStrategy.SEQUENTIAL -> (currentSubaddressIndex + 1) % subaddresses.size
            RotationStrategy.RANDOM -> {
                var newIndex: Int
                do { newIndex = (0 until subaddresses.size).random() }
                while (newIndex == currentSubaddressIndex && subaddresses.size > 1)
                newIndex
            }
            else -> (currentSubaddressIndex + 1) % subaddresses.size
        }
        
        val oldSubaddress = subaddresses[currentSubaddressIndex]
        val newSubaddress = subaddresses[nextIndex]
        
        rotationHistory.add(RotationRecord(
            fromSubaddress = oldSubaddress,
            toSubaddress = newSubaddress,
            timestamp = System.currentTimeMillis(),
            reason = "Scheduled rotation (${rotationStrategy.name})"
        ))
        
        return Result.success(newSubaddress)
    }
}

/**
 * Pool configuration
 */
data class PoolConfig(
    val host: String = "pool.supportxmr.com",
    val sslPorts: List<Int> = listOf(443, 3333, 5555),
    val tcpPorts: List<Int> = listOf(3333, 5555, 4444, 8888),
    val connectionStrategy: ConnectionStrategy = ConnectionStrategy.TCP_ONLY
) : Serializable {
    
    fun getConnectionSequence(): List<Pair<Int, Boolean>> {
        return when (connectionStrategy) {
            ConnectionStrategy.SSL_ONLY -> sslPorts.map { Pair(it, true) }
            ConnectionStrategy.TCP_ONLY -> tcpPorts.map { Pair(it, false) }
            ConnectionStrategy.SSL_FALLBACK_TO_TCP ->
                sslPorts.map { Pair(it, true) } + tcpPorts.map { Pair(it, false) }
            ConnectionStrategy.TCP_FALLBACK_TO_SSL ->
                tcpPorts.map { Pair(it, false) } + sslPorts.map { Pair(it, true) }
        }
    }
    
    companion object {
        fun fromHost(host: String): PoolConfig {
            return when (host) {
                "pool.supportxmr.com" -> PoolConfig(host = host, sslPorts = listOf(443, 3333), tcpPorts = listOf(3333))
                "pool.minexmr.com" -> PoolConfig(host = host, sslPorts = listOf(443), tcpPorts = listOf(4444, 3333))
                else -> PoolConfig(host = host)
            }
        }
    }
}

/**
 * Performance settings
 */
data class PerformanceConfig(
    val threads: Int = 2,
    val maxCpuUsage: Int = 75
) : Serializable

/**
 * Complete miner configuration with subaddress and rotation support
 */
data class MinerConfig(
    val pool: PoolConfig = PoolConfig(),
    val worker: String = "android_miner",
    val password: String = "x",
    val performance: PerformanceConfig = PerformanceConfig(),
    val subaddressManager: SubaddressManager = SubaddressManager()
) : Serializable {
    
    fun getActiveMiningAddress(rigName: String? = null): String {
        return subaddressManager.getActiveMiningAddress(rigName)
    }
    
    fun getConnectionSequence(): List<Pair<Int, Boolean>> = pool.getConnectionSequence()
    
    fun getStratumUrl(port: Int, useSSL: Boolean, rigName: String? = null): String {
        val protocol = if (useSSL) "stratum+ssl://" else "stratum+tcp://"
        val wallet = getActiveMiningAddress(rigName)
        return "$protocol$wallet.$worker@${pool.host}:$port"
    }
    
    fun validate(): List<String> {
        val issues = mutableListOf<String>()
        if (!subaddressManager.enabled && subaddressManager.walletPrimary.isEmpty()) {
            issues.add("Wallet address is required")
        }
        if (subaddressManager.enabled && subaddressManager.subaddresses.isEmpty() && subaddressManager.walletPrimary.isEmpty()) {
            issues.add("At least one subaddress or main wallet must be configured")
        }
        if (pool.host.isEmpty()) issues.add("Pool host is required")
        if (performance.threads < 1) issues.add("Threads must be at least 1")
        return issues
    }
    
    companion object {
        fun withDefaults(): MinerConfig = MinerConfig()
        
        fun miningFarm(poolHost: String, mainWallet: String, rigCount: Int, useSubaddresses: Boolean = true): MinerConfig {
            val config = MinerConfig(
                pool = PoolConfig.fromHost(poolHost),
                subaddressManager = SubaddressManager(
                    enabled = useSubaddresses,
                    walletPrimary = mainWallet,
                    rotationStrategy = if (useSubaddresses) RotationStrategy.DAILY else RotationStrategy.FIXED
                ),
                performance = PerformanceConfig(threads = 2)
            )
            for (i in 1..rigCount) {
                config.subaddressManager.registerRig("Rig_$i")
            }
            return config
        }
        
        fun powerSaving(): MinerConfig = MinerConfig(performance = PerformanceConfig(threads = 1, maxCpuUsage = 50))
    }
}

/**
 * Configuration manager using SharedPreferences
 */
object ConfigManager {
    private const val PREF_NAME = "miner_config"
    
    fun saveConfig(context: Context, config: MinerConfig) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("pool_host", config.pool.host)
            putString("worker", config.worker)
            putString("password", config.password)
            putInt("threads", config.performance.threads)
            putInt("max_cpu", config.performance.maxCpuUsage)
            putBoolean("subaddress_enabled", config.subaddressManager.enabled)
            putString("wallet_primary", config.subaddressManager.walletPrimary)
            putString("rotation_strategy", config.subaddressManager.rotationStrategy.name)
            putInt("rotation_interval", config.subaddressManager.rotationIntervalHours)
            putInt("current_subaddress_index", config.subaddressManager.currentSubaddressIndex)
            apply()
        }
    }
    
    fun getConfig(context: Context): MinerConfig {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val poolHost = prefs.getString("pool_host", "pool.supportxmr.com") ?: "pool.supportxmr.com"
        val rotationStrategyName = prefs.getString("rotation_strategy", "FIXED") ?: "FIXED"
        
        return MinerConfig(
            pool = PoolConfig.fromHost(poolHost),
            worker = prefs.getString("worker", "android_miner") ?: "android_miner",
            password = prefs.getString("password", "x") ?: "x",
            performance = PerformanceConfig(
                threads = prefs.getInt("threads", 2),
                maxCpuUsage = prefs.getInt("max_cpu", 75)
            ),
            subaddressManager = SubaddressManager(
                enabled = prefs.getBoolean("subaddress_enabled", false),
                walletPrimary = prefs.getString("wallet_primary", "") ?: "",
                rotationStrategy = try { RotationStrategy.valueOf(rotationStrategyName) } catch (e: Exception) { RotationStrategy.FIXED },
                rotationIntervalHours = prefs.getInt("rotation_interval", 24),
                currentSubaddressIndex = prefs.getInt("current_subaddress_index", 0)
            )
        )
    }
}
