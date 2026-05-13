package com.monerominer

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class CrashLogger private constructor() {
    
    companion object {
        private var instance: CrashLogger? = null
        
        fun init(context: Context) {
            if (instance == null) {
                instance = CrashLogger(context)
            }
        }
        
        fun log(message: String) {
            instance?.writeToFile(message)
        }
        
        fun logException(e: Throwable) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            instance?.writeToFile(sw.toString())
        }
        
        fun getLogFile(): File? {
            return instance?.logFile
        }
    }
    
    private val logFile: File
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    
    constructor(context: Context) {
        val logDir = context.getExternalFilesDir("logs") ?: context.filesDir
        if (!logDir.exists()) logDir.mkdirs()
        
        logFile = File(logDir, "crash_log.txt")
        
        // Write header
        writeToFile("========================================")
        writeToFile("Monero Miner - Crash Log")
        writeToFile("Started: ${dateFormat.format(Date())}")
        writeToFile("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        writeToFile("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        writeToFile("CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
        
        try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            writeToFile("App Version: ${pInfo.versionName} (${pInfo.versionCode})")
        } catch (e: PackageManager.NameNotFoundException) {}
        
        writeToFile("========================================")
        
        // Set global exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logException(throwable)
            writeToFile("CRASH in thread: ${thread.name}")
            writeToFile("========================================")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
    
    private fun writeToFile(message: String) {
        try {
            val timestamp = dateFormat.format(Date())
            FileWriter(logFile, true).use { writer ->
                writer.write("$timestamp: $message\n")
                writer.flush()
            }
        } catch (e: Exception) {
            android.util.Log.e("CrashLogger", "Failed to write log: ${e.message}")
        }
    }
}
