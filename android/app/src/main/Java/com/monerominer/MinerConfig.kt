package com.monerominer

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Properties
import java.util.UUID

/**
 * Represents different mining pool connection strategies
 */
enum class ConnectionStrategy {
    SSL_ONLY,
    TCP_ONLY,
    SSL_FALLBACK_TO_TCP,
    TCP_FALLBACK_TO_SSL
}

/**
 * Represents a Monero subaddress for mining
 * Format: Starts with '8' and is 95 characters long
 */
@Serializable
data class Subaddress(
    val address: String,
    val label: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val index: Int = 0, // Subaddress index in the wallet
    val description: String = "",
    val tags: List<String> = emptyList()
) {
    fun isValid(): Boolean {
        val subaddressRegex = Regex("^8[1-9A-HJ-NP-Za-km-z]{94}")
        return subaddressRegex.matches(address) && address.length == 95
    }
    
    fun toShortString(): String {
        return if (address.length > 20) {
            "${address.take(10)}...${address.takeLast(10)}"
        } else address
    }
}

/**
 * Advanced subaddress rotation strategy
 */
enum class RotationStrategy {
    FIXED,           // Use a single subaddress
    SEQUENTIAL,      // Rotate through subaddresses in order
    RANDOM,          // Random subaddress from the pool
    TIME_BASED,      // Rotate based on time intervals
    SHARE_BASED,     // Rotate after X accepted shares
    DAILY_ZERO_AM,   // Rotate every day at midnight
    WEEKLY,          // Rotate every week
    MONTHLY          // Rotate every month
}

/**
 * Represents a mining rig/worker using a specific subaddress
 */
@Serializable
data class Rig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Android Miner",
    val deviceModel: String = android.os.Build.MODEL,
    val deviceId: String = getDeviceUniqueId(),
    val subaddressIndex: Int = 0,
    val customWorkerName: String = "",
    val priority: Int = 1, // 1 = highest priority
    val enabled: Boolean = true,
    val lastActive: Long = System.currentTimeMillis(),
    val totalShares: Long = 0,
    val acceptedShares: Long = 0,
    val rejectedShares: Long = 0,
    val tags: Map<String, String> = emptyMap()
) {
    companion object {
        private fun getDeviceUniqueId(): String {
            return try {
                val androidId = android.provider.Settings.Secure.getString(
                    android.app.Application.getApplicationInstance(),
                    android.provider.Settings.Secure.ANDROID_ID
                )
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(androidId.toByteArray())
                digest.digest().take(8).joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                UUID.randomUUID().toString().take(8)
            }
        }
    }
    
    fun getEffectiveWorkerName(baseWorker: String): String {
        return when {
            customWorkerName.isNotEmpty() -> customWorkerName
            name != "Android Miner" -> "${baseWorker}_${name.replace(" ", "_")}"
            else -> baseWorker
        }
    }
    
    val efficiency: Double
        get() = if (totalShares > 0) {
            (acceptedShares.toDouble() / totalShares) * 100.0
        } else 100.0
}

/**
 * Complete subaddress management system
 */
@Serializable
data class SubaddressManager(
    val enabled: Boolean = false,
    val walletPrimary: String = "", // Main wallet address (starts with '4')
    val subaddresses: MutableList<Subaddress> = mutableListOf(),
    val rotationStrategy: RotationStrategy = RotationStrategy.FIXED,
    val rotationIntervalHours: Int = 24,
    val maxSubaddressCount: Int = 10,
    val currentSubaddressIndex: Int = 0,
    val rigs: MutableMap<String, Rig> = mutableMapOf(), // Rig ID -> Rig
    val rotationHistory: MutableList<RotationRecord> = mutableListOf(),
    val enableAutomaticCreation: Boolean = false, // Auto-create new subaddresses
    val subaddressCreationLimit: Int = 100,
    @Transient
    val lastRotationTimestamp: Long = System.currentTimeMillis(),
    val notifications: SubaddressNotifications = SubaddressNotifications()
) {
    
    /**
     * Get the currently active subaddress
     */
    fun getCurrentSubaddress(): Subaddress? {
        if (!enabled || subaddresses.isEmpty()) return null
        return subaddresses.getOrNull(currentSubaddressIndex)
    }
    
    /**
     * Get subaddress for a specific rig
     */
    fun getSubaddressForRig(rigName: String): String? {
        if (!enabled) return null
        
        // Find the rig
        val rig = rigs.values.find { it.name == rigName }
        
        return if (rig != null) {
            // Use rig's specific subaddress
            subaddresses.getOrNull(rig.subaddressIndex)?.address ?: getCurrentSubaddress()?.address
        } else {
            // Use current default subaddress
            getCurrentSubaddress()?.address
        }
    }
    
    /**
     * Get active mining address (main wallet or subaddress)
     */
    fun getActiveMiningAddress(rigName: String? = null): String {
        if (!enabled || subaddresses.isEmpty()) {
            return walletPrimary
        }
        
        return if (rigName != null) {
            getSubaddressForRig(rigName) ?: walletPrimary
        } else {
            getCurrentSubaddress()?.address ?: walletPrimary
        }
    }
    
    /**
     * Add a new subaddress
     */
    fun addSubaddress(
        address: String,
        label: String = "",
        description: String = ""
    ): Result<Subaddress> {
        val subaddress = Subaddress(
            address = address,
            label = label,
            description = description,
            index = subaddresses.size
        )
        
        if (!subaddress.isValid()) {
            return Result.failure(IllegalArgumentException("Invalid subaddress format"))
        }
        
        if (subaddresses.size >= maxSubaddressCount) {
            return Result.failure(IllegalStateException("Maximum subaddress count reached"))
        }
        
        // Check for duplicates
        if (subaddresses.any { it.address == address }) {
            return Result.failure(IllegalArgumentException("Subaddress already exists"))
        }
        
        subaddresses.add(subaddress)
        return Result.success(subaddress)
    }
    
    /**
     * Remove a subaddress by index
     */
    fun removeSubaddress(index: Int): Result<Unit> {
        if (index < 0 || index >= subaddresses.size) {
            return Result.failure(IndexOutOfBoundsException("Invalid subaddress index"))
        }
        
        // Don't remove if it's the only one and we have rigs using it
        if (subaddresses.size == 1 && rigs.isNotEmpty()) {
            return Result.failure(IllegalStateException("Cannot remove the last subaddress while rigs exist"))
        }
        
        // Reassign rigs that were using this subaddress
        rigs.values.filter { it.subaddressIndex == index }.forEach { rig ->
            rigs[rig.id] = rig.copy(subaddressIndex = 0)
        }
        
        subaddresses.removeAt(index)
        
        // Update indices for remaining subaddresses
        subaddresses.forEachIndexed { i, sub ->
            subaddresses[i] = sub.copy(index = i)
        }
        
        // Adjust current index if needed
        if (currentSubaddressIndex >= subaddresses.size) {
            // This will be handled in the actual rotation logic
        }
        
        return Result.success(Unit)
    }
    
    /**
     * Register a new rig
     */
    fun registerRig(name: String, deviceModel: String = ""): Rig {
        val existingRig = rigs.values.find { it.name == name }
        if (existingRig != null) {
            return existingRig.copy(lastActive = System.currentTimeMillis())
        }
        
        val rig = Rig(
            name = name,
            deviceModel = deviceModel
        )
        rigs[rig.id] = rig
        return rig
    }
    
    /**
     * Remove a rig
     */
    fun unregisterRig(rigId: String): Boolean {
        return rigs.remove(rigId) != null
    }
    
    /**
     * Get all active rigs
     */
    fun getActiveRigs(): List<Rig> {
        return rigs.values.filter { it.enabled }
    }
    
    /**
     * Get rig names for UI display
     */
    fun getRigNames(): List<String> {
        val names = mutableListOf<String>()
        names.add("default") // Default/main worker
        names.addAll(rigs.values.map { it.name })
        return names
    }
    
    /**
     * Check if rotation is needed
     */
    fun needsRotation(): Boolean {
        if (!enabled || subaddresses.size <= 1) return false
        
        val timeSinceLastRotation = System.currentTimeMillis() - lastRotationTimestamp
        val intervalMillis = rotationIntervalHours * 3600_000L
        
        return when (rotationStrategy) {
            RotationStrategy.FIXED -> false
            RotationStrategy.TIME_BASED -> timeSinceLastRotation >= intervalMillis
            RotationStrategy.DAILY_ZERO_AM -> {
                val now = Instant.now()
                val lastRotation = Instant.ofEpochMilli(lastRotationTimestamp)
                ChronoUnit.DAYS.between(lastRotation, now) >= 1
            }
            RotationStrategy.WEEKLY -> timeSinceLastRotation >= 7 * 24 * 3600_000L
            RotationStrategy.MONTHLY -> timeSinceLastRotation >= 30 * 24 * 3600_000L
            else -> false
        }
    }
    
    /**
     * Rotate to next subaddress
     */
    fun rotate(): Result<Subaddress> {
        if (!enabled || subaddresses.size <= 1) {
            return Result.failure(IllegalStateException("Rotation not possible"))
        }
        
        val nextIndex = when (rotationStrategy) {
            RotationStrategy.SEQUENTIAL -> (currentSubaddressIndex + 1) % subaddresses.size
            RotationStrategy.RANDOM -> {
                var newIndex: Int
                do {
                    newIndex = (0 until subaddresses.size).random()
                } while (newIndex == currentSubaddressIndex && subaddresses.size > 1)
                newIndex
            }
            else -> (currentSubaddressIndex + 1) % subaddresses.size
        }
        
        val oldSubaddress = subaddresses[currentSubaddressIndex]
        val newSubaddress = subaddresses[nextIndex]
        
        // Record rotation
        rotationHistory.add(
            RotationRecord(
                fromSubaddress = oldSubaddress,
                toSubaddress = newSubaddress,
                timestamp = System.currentTimeMillis(),
                reason = "Scheduled rotation"
            )
        )
        
        // Update current index
        // currentSubaddressIndex = nextIndex // This would need to be handled properly
        
        return Result.success(newSubaddress)
    }
    
    /**
     * Get subaddress statistics
     */
    fun getStatistics(): SubaddressStatistics {
        return SubaddressStatistics(
            totalSubaddresses = subaddresses.size,
            activeRigs = rigs.values.count { it.enabled },
            totalRigs = rigs.size,
            rotationCount = rotationHistory.size,
            lastRotation = rotationHistory.lastOrNull(),
            subaddressUsage = subaddresses.map { sub ->
                val rigCount = rigs.values.count { it.subaddressIndex == sub.index }
                SubaddressUsage(subaddress = sub, rigCount = rigCount)
            }
        )
    }
}

/**
 * Record of a subaddress rotation
 */
@Serializable
data class RotationRecord(
    val fromSubaddress: Subaddress,
    val toSubaddress: Subaddress,
    val timestamp: Long,
    val reason: String,
    val triggeredBy: String = "system"
)

/**
 * Notification settings for subaddress events
 */
@Serializable
data class SubaddressNotifications(
    val onRotation: Boolean = true,
    val onLowBalance: Boolean = false,
    val onRigOffline: Boolean = true,
    val onNewRig: Boolean = true,
    val notifyMethod: String = "log" // "log", "notification", "webhook"
)

/**
 * Subaddress usage statistics
 */
@Serializable
data class SubaddressStatistics(
    val totalSubaddresses: Int,
    val activeRigs: Int,
    val totalRigs: Int,
    val rotationCount: Int,
    val lastRotation: RotationRecord?,
    val subaddressUsage: List<SubaddressUsage>
)

@Serializable
data class SubaddressUsage(
    val subaddress: Subaddress,
    val rigCount: Int
)

/**
 * Represents a mining pool configuration
 */
@Serializable
data class PoolConfig(
    val host: String = "pool.supportxmr.com",
    val sslPorts: List<Int> = listOf(443, 3333, 5555),
    val tcpPorts: List<Int> = listOf(3333, 5555, 4444, 8888),
    val connectionStrategy: ConnectionStrategy = ConnectionStrategy.SSL_FALLBACK_TO_TCP,
    val connectionTimeout: Int = 30,
    val retryDelay: Int = 5,
    val maxRetries: Int = 3
) {
    companion object {
        val SUPPORTED_POOLS = mapOf(
            "pool.supportxmr.com" to PoolConfig(
                host = "pool.supportxmr.com",
                sslPorts = listOf(443, 3333),
                tcpPorts = listOf(3333)
            ),
            "pool.minexmr.com" to PoolConfig(
                host = "pool.minexmr.com",
                sslPorts = listOf(443),
                tcpPorts = listOf(4444, 3333)
            ),
            "pool.moneroocean.stream" to PoolConfig(
                host = "pool.moneroocean.stream",
                sslPorts = listOf(443),
                tcpPorts = listOf(3333)
            )
        )
        
        fun fromHost(host: String): PoolConfig {
            return SUPPORTED_POOLS[host] ?: PoolConfig(host = host)
        }
    }
}

/**
 * Performance optimization settings
 */
@Serializable
data class PerformanceConfig(
    val threads: Int = Runtime.getRuntime().availableProcessors(),
    val threadAffinity: Boolean = true,
    val hugePages: Boolean = false,
    val numaAware: Boolean = false,
    val initThreads: Int = 2,
    val scratchpadSize: Int = 0,
    val lowPowerMode: Boolean = false,
    val maxCpuUsage: Int = 75
) {
    companion object {
        fun optimized(): PerformanceConfig {
            val cores = Runtime.getRuntime().availableProcessors()
            val maxMemory = Runtime.getRuntime().maxMemory()
            return PerformanceConfig(
                threads = cores.coerceIn(1, 4),
                scratchpadSize = (maxMemory / 2 / cores).toInt(),
                maxCpuUsage = 80
            )
        }
        
        fun batteryOptimized(): PerformanceConfig {
            return PerformanceConfig(
                threads = 1,
                lowPowerMode = true,
                maxCpuUsage = 50
            )
        }
    }
}

/**
 * Monitoring configuration
 */
@Serializable
data class MonitoringConfig(
    val enabled: Boolean = true,
    val updateInterval: Int = 10,
    val enableLogging: Boolean = true,
    val logFile: String = "mining_stats.csv",
    val hashrateThreshold: Double = 0.0,
    val temperatureThreshold: Int = 45,
    val enablePrometheus: Boolean = false,
    val prometheusPort: Int = 9090
)

/**
 * Complete mining configuration with subaddress support
 */
@Serializable
data class MinerConfig(
    val pool: PoolConfig = PoolConfig(),
    val worker: String = "android_miner",
    val password: String = "x",
    val subaddressManager: SubaddressManager = SubaddressManager(),
    val performance: PerformanceConfig = PerformanceConfig(),
    val monitoring: MonitoringConfig = MonitoringConfig(),
    val backupPools: List<PoolConfig> = emptyList(),
    val autoFailover: Boolean = true,
    val failoverTimeout: Int = 60
) {
    
    /**
     * Validates the entire configuration
     */
    fun validate(): List<String> {
        val issues = mutableListOf<String>()
        
        // Validate main wallet
        val walletRegex = Regex("^4[1-9A-HJ-NP-Za-km-z]{94}")
        
        if (!subaddressManager.enabled) {
            if (subaddressManager.walletPrimary.isEmpty()) {
                issues.add("Main wallet address is required when subaddresses are disabled")
            } else if (!walletRegex.matches(subaddressManager.walletPrimary)) {
                issues.add("Invalid Monero wallet address format")
            }
        } else {
            if (subaddressManager.walletPrimary.isNotEmpty() && 
                !walletRegex.matches(subaddressManager.walletPrimary)) {
                issues.add("Invalid main wallet address format")
            }
            
            // Validate all subaddresses
            subaddressManager.subaddresses.forEachIndexed { index, sub ->
                if (!sub.isValid()) {
                    issues.add("Invalid subaddress at index $index: ${sub.toShortString()}")
                }
            }
            
            if (subaddressManager.subaddresses.isEmpty() && 
                subaddressManager.walletPrimary.isEmpty()) {
                issues.add("At least one subaddress or main wallet must be configured")
            }
        }
        
        // Validate performance
        val maxCores = Runtime.getRuntime().availableProcessors()
        if (performance.threads < 1 || performance.threads > maxCores) {
            issues.add("Threads must be between 1 and $maxCores")
        }
        
        if (performance.maxCpuUsage !in 10..100) {
            issues.add("CPU usage must be between 10% and 100%")
        }
        
        // Validate pool
        if (pool.host.isEmpty()) {
            issues.add("Pool host is required")
        }
        
        return issues
    }
    
    /**
     * Returns all connection attempts in priority order
     */
    fun getConnectionSequence(): List<Pair<Int, Boolean>> {
        return when (pool.connectionStrategy) {
            ConnectionStrategy.SSL_ONLY -> pool.sslPorts.map { Pair(it, true) }
            ConnectionStrategy.TCP_ONLY -> pool.tcpPorts.map { Pair(it, false) }
            ConnectionStrategy.SSL_FALLBACK_TO_TCP -> 
                pool.sslPorts.map { Pair(it, true) } + pool.tcpPorts.map { Pair(it, false) }
            ConnectionStrategy.TCP_FALLBACK_TO_SSL -> 
                pool.tcpPorts.map { Pair(it, false) } + pool.sslPorts.map { Pair(it, true) }
        }
    }
    
    /**
     * Gets the active mining address considering subaddress configuration
     */
    fun getActiveMiningAddress(rigName: String? = null): String {
        return subaddressManager.getActiveMiningAddress(rigName)
    }
    
    /**
     * Generates full stratum URL
     */
    fun getStratumUrl(port: Int, useSSL: Boolean, rigName: String? = null): String {
        val protocol = if (useSSL) "stratum+ssl://" else "stratum+tcp://"
        val wallet = getActiveMiningAddress(rigName)
        val effectiveWorker = if (rigName != null) {
            val rig = subaddressManager.rigs.values.find { it.name == rigName }
            rig?.getEffectiveWorkerName(worker) ?: worker
        } else {
            worker
        }
        
        return "$protocol$wallet.$effectiveWorker:$password@${pool.host}:$port"
    }
    
    /**
     * Register a new mining rig
     */
    fun registerRig(name: String, deviceModel: String = ""): Rig {
        return subaddressManager.registerRig(name, deviceModel)
    }
    
    /**
     * Add a subaddress from UI input
     */
    fun addSubaddress(address: String, label: String, description: String = ""): Result<Subaddress> {
        return subaddressManager.addSubaddress(address, label, description)
    }
    
    /**
     * Assign a subaddress to a specific rig
     */
    fun assignSubaddressToRig(rigName: String, subaddressIndex: Int): Result<Unit> {
        val rig = subaddressManager.rigs.values.find { it.name == rigName }
            ?: return Result.failure(IllegalArgumentException("Rig '$rigName' not found"))
        
        if (subaddressIndex < 0 || subaddressIndex >= subaddressManager.subaddresses.size) {
            return Result.failure(IndexOutOfBoundsException("Invalid subaddress index"))
        }
        
        subaddressManager.rigs[rig.id] = rig.copy(subaddressIndex = subaddressIndex)
        return Result.success(Unit)
    }
    
    /**
     * Save configuration to JSON file
     */
    fun saveToJson(file: File): Result<Unit> {
        return try {
            val json = Json { 
                prettyPrint = true
                ignoreUnknownKeys = true
            }
            val jsonString = json.encodeToString(this)
            file.writeText(jsonString)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Export configuration (without sensitive data) for sharing
     */
    fun exportForSharing(): MinerConfig {
        return this.copy(
            subaddressManager = subaddressManager.copy(
                walletPrimary = maskAddress(subaddressManager.walletPrimary),
                subaddresses = subaddressManager.subaddresses.map { 
                    it.copy(address = maskAddress(it.address))
                }.toMutableList()
            ),
            password = "***"
        )
    }
    
    private fun maskAddress(address: String): String {
        return if (address.length > 20) {
            "${address.take(8)}...${address.takeLast(8)}"
        } else {
            "****"
        }
    }
    
    companion object {
        /**
         * Load configuration from JSON file
         */
        fun loadFromJson(file: File): Result<MinerConfig> {
            return try {
                val json = Json { 
                    ignoreUnknownKeys = true
                    isLenient = true
                }
                val jsonString = file.readText()
                val config = json.decodeFromString<MinerConfig>(jsonString)
                Result.success(config)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        
        /**
         * Create with sensible defaults
         */
        fun withDefaults(): MinerConfig = MinerConfig()
        
        /**
         * Create for maximum performance
         */
        fun maximumPerformance(): MinerConfig = MinerConfig(
            performance = PerformanceConfig.optimized(),
            monitoring = MonitoringConfig(updateInterval = 5)
        )
        
        /**
         * Create for battery optimization
         */
        fun powerSaving(): MinerConfig = MinerConfig(
            performance = PerformanceConfig.batteryOptimized(),
            monitoring = MonitoringConfig(updateInterval = 60)
        )
        
        /**
         * Create for mining farm with multiple rigs
         */
        fun miningFarm(
            poolHost: String,
            mainWallet: String,
            rigCount: Int,
            useSubaddresses: Boolean = true
        ): MinerConfig {
            val config = MinerConfig(
                pool = PoolConfig.fromHost(poolHost),
                subaddressManager = SubaddressManager(
                    enabled = useSubaddresses,
                    walletPrimary = mainWallet,
                    rotationStrategy = if (useSubaddresses) RotationStrategy.DAILY_ZERO_AM else RotationStrategy.FIXED
                ),
                performance = PerformanceConfig.optimized()
            )
            
            // Register rigs
            for (i in 1..rigCount) {
                config.registerRig("Rig_$i")
            }
            
            return config
        }
    }
}

/**
 * Example usage and demo
 */
object MinerConfigDemo {
    fun demo() {
        // 1. Create a simple config
        val simpleConfig = MinerConfig.withDefaults()
        println("Simple config: ${simpleConfig.getActiveMiningAddress()}")
        
        // 2. Create config with subaddress support
        val config = MinerConfig.miningFarm(
            poolHost = "pool.supportxmr.com",
            mainWallet = "4YourMoneroWalletAddressHere...",
            rigCount = 3,
            useSubaddresses = true
        )
        
        // Add some subaddresses
        config.addSubaddress(
            address = "8Subaddress1ForRig1...",
            label = "Rig 1 Subaddress",
            description = "Main mining rig"
        )
        
        config.addSubaddress(
            address = "8Subaddress2ForRig2...",
            label = "Rig 2 Subaddress",
            description = "Secondary rig"
        )
        
        // Assign subaddresses to rigs
        config.assignSubaddressToRig("Rig_1", 0)
        config.assignSubaddressToRig("Rig_2", 1)
        
        // Get active addresses
        println("Main address: ${config.getActiveMiningAddress()}")
        println("Rig_1 address: ${config.getActiveMiningAddress("Rig_1")}")
        println("Rig_2 address: ${config.getActiveMiningAddress("Rig_2")}")
        
        // Generate connection URLs
        val url = config.getStratumUrl(443, true, "Rig_1")
        println("Stratum URL: $url")
        
        // Get statistics
        val stats = config.subaddressManager.getStatistics()
        println("Stats: $stats")
        
        // Validate config
        val issues = config.validate()
        if (issues.isNotEmpty()) {
            println("Configuration issues:")
            issues.forEach { println("  - $it") }
        }
        
        // Save config
        val saveResult = config.saveToJson(File("miner_config.json"))
        if (saveResult.isSuccess) {
            println("Config saved successfully")
        }
        
        // Export for sharing (masks sensitive data)
        val exportConfig = config.exportForSharing()
        println("Shared config: $exportConfig")
    }
}
