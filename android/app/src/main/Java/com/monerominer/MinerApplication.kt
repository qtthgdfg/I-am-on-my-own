package com.monerominer

import android.app.Application
import android.util.Log

class MinerApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize crash logger
        try {
            CrashLogger.init(this)
            CrashLogger.log("App started")
        } catch (e: Exception) {
            Log.e("MinerApp", "CrashLogger failed: ${e.message}")
        }
        
        // DO NOT load library here
        // Library will be loaded by MinerService when mining starts
    }
}
