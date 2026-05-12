package com.monerominer

import android.app.Application
import android.os.StrictMode
import android.util.Log

class MinerApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
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
            }
        } catch (e: Exception) {
            Log.e("MinerApp", "StrictMode failed: ${e.message}")
        }
        
        // Load native library
        try {
            System.loadLibrary("monerominer")
            Log.d("MinerApp", "Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("MinerApp", "Library not loaded: ${e.message}")
        }
    }
}
