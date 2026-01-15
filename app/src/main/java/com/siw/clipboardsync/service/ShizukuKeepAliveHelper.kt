package com.siw.clipboardsync.service

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helper class for keeping the app alive using Shizuku's elevated permissions.
 * 
 * This class uses Shizuku to:
 * 1. Add app to device idle whitelist
 * 2. Set app standby bucket to ACTIVE
 * 3. Disable battery optimization for the app
 * 4. Prevent the app from being killed by system
 */
class ShizukuKeepAliveHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "ShizukuKeepAliveHelper"
        private const val APP_PACKAGE = "com.siw.clipboardsync"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isInitialized = AtomicBoolean(false)
    
    /**
     * Initialize keep-alive using Shizuku
     */
    suspend fun initialize(): Boolean {
        if (!isShizukuAvailable()) {
            Log.w(TAG, "Shizuku not available")
            return false
        }
        
        if (!hasShizukuPermission()) {
            Log.w(TAG, "Shizuku permission not granted")
            return false
        }
        
        return try {
            // Add to device idle whitelist
            addToDeviceIdleWhitelist()
            
            // Set standby bucket to ACTIVE
            setStandbyBucketActive()
            
            // Disable battery optimization
            disableBatteryOptimization()
            
            // Set process importance
            setProcessImportance()
            
            isInitialized.set(true)
            Log.i(TAG, "Shizuku keep-alive initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Shizuku keep-alive", e)
            false
        }
    }
    
    /**
     * Add app to device idle (Doze) whitelist
     */
    private fun addToDeviceIdleWhitelist() {
        try {
            // Use shell command via Shizuku
            executeShellCommand("dumpsys deviceidle whitelist +$APP_PACKAGE")
            Log.i(TAG, "Added to device idle whitelist")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add to device idle whitelist", e)
        }
    }
    
    /**
     * Set app standby bucket to ACTIVE (10)
     */
    private fun setStandbyBucketActive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                executeShellCommand("am set-standby-bucket $APP_PACKAGE active")
                Log.i(TAG, "Set standby bucket to ACTIVE")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set standby bucket", e)
        }
    }
    
    /**
     * Disable battery optimization for the app
     */
    private fun disableBatteryOptimization() {
        try {
            // Different commands for different Android versions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                executeShellCommand("cmd appops set $APP_PACKAGE RUN_IN_BACKGROUND allow")
                executeShellCommand("cmd appops set $APP_PACKAGE RUN_ANY_IN_BACKGROUND allow")
            }
            Log.i(TAG, "Disabled battery optimization")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable battery optimization", e)
        }
    }
    
    /**
     * Set process importance to foreground
     */
    private fun setProcessImportance() {
        try {
            // Get our process ID
            val pid = android.os.Process.myPid()
            
            // Set OOM adj to foreground level
            executeShellCommand("echo -17 > /proc/$pid/oom_score_adj")
            
            Log.i(TAG, "Set process importance")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set process importance", e)
        }
    }
    
    /**
     * Execute a shell command via Shizuku
     */
    private fun executeShellCommand(command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val result = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            
            if (error.isNotEmpty()) {
                Log.w(TAG, "Shell command error: $error")
            }
            
            Log.d(TAG, "Executed: $command -> $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute shell command: $command", e)
            null
        }
    }
    
    /**
     * Check if Shizuku is available
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if we have Shizuku permission
     */
    fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Periodically refresh keep-alive settings
     */
    fun startPeriodicRefresh() {
        scope.launch {
            while (isActive) {
                delay(30 * 60 * 1000L) // Every 30 minutes
                
                if (isShizukuAvailable() && hasShizukuPermission()) {
                    try {
                        addToDeviceIdleWhitelist()
                        setStandbyBucketActive()
                    } catch (e: Exception) {
                        Log.w(TAG, "Periodic refresh failed", e)
                    }
                }
            }
        }
    }
    
    /**
     * Stop periodic refresh
     */
    fun stopPeriodicRefresh() {
        scope.coroutineContext.cancelChildren()
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopPeriodicRefresh()
        scope.cancel()
    }
}
