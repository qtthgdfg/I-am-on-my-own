package com.monerominer

import android.app.Application
import android.os.StrictMode
import android.util.Log

class MinerApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize crash logger FIRST
        CrashLogger.init(this)
        CrashLogger.log("App started - onCreate")
        
        // Enable strict mode in debug builds
        try {
            if (BuildConfig.DEBUG) {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectNetwork()
                        .penaltyLog()
                        .build()
                )
                
                StrictMode.setVmPolicy(
                    StrictMode.VmPolicy.Builder()
                        .detectLeakedSqlLiteObjects()
                        .detectLeakedClosableObjects()
                        .penaltyLog()
                        .build()
                )
                CrashLogger.log("StrictMode enabled for debug")
            }
        } catch (e: Exception) {
            Log.e("MinerApp", "StrictMode failed: ${e.message}")
            CrashLogger.log("StrictMode error: ${e.message}")
        }
        
        // Load native library
        try {
            System.loadLibrary("monerominer")
            Log.d("MinerApp", "Native library loaded")
            CrashLogger.log("✅ Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("MinerApp", "Library not loaded: ${e.message}")
            CrashLogger.log("⚠️ Native library failed: ${e.message}")
        }
        
        CrashLogger.log("App initialization complete")
    }
}
