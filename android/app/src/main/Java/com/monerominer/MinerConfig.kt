package com.monerominer

import java.io.Serializable

data class MinerConfig(
    val poolHost: String = "pool.supportxmr.com",
    val poolPort: Int = 3333,
    val wallet: String = "",
    val worker: String = "android_miner",
    val password: String = "x",
    val threads: Int = 2,
    val useSSL: Boolean = false,
    val hugePages: Boolean = false,
    val numaAware: Boolean = false
) : Serializable
