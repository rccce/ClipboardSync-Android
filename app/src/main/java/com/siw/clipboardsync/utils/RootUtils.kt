package com.siw.clipboardsync.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Utility class for detecting and managing root access capabilities
 */
object RootUtils {
    
    private const val TAG = "RootUtils"
    
    /**
     * Check if device has root access
     */
    suspend fun isRooted(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Method 1: Check for su binary
            if (checkSuBinary()) return@withContext true
            
            // Method 2: Check for common root paths
            if (checkRootPaths()) return@withContext true
            
            // Method 3: Try to execute su command
            if (testSuCommand()) return@withContext true
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking root access", e)
            false
        }
    }
    
    
    /**
     * Get enhanced clipboard monitoring capabilities based on root status
     */
    suspend fun getClipboardCapabilities(): ClipboardCapabilities = withContext(Dispatchers.IO) {
        val isRooted = isRooted()
        
        ClipboardCapabilities(
            isRooted = isRooted,
            canBypassAndroid10Restrictions = isRooted,
            recommendedPollingInterval = if (isRooted) 1000L else 5000L // Faster polling for rooted devices
        )
    }
    
    /**
     * Execute shell command with root privileges
     */
    suspend fun executeRootCommand(command: String): String? = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Log.d(TAG, "Root command executed successfully: $command")
                output
            } else {
                Log.w(TAG, "Root command failed with exit code $exitCode: $command")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing root command: $command", e)
            null
        }
    }
    
    /**
     * Monitor clipboard using root shell commands (fallback for when LSPosed is not available)
     */
    suspend fun startRootClipboardMonitoring(onClipboardChange: (String) -> Unit): Process? = withContext(Dispatchers.IO) {
        try {
            if (!isRooted()) {
                Log.w(TAG, "Cannot start root clipboard monitoring: device not rooted")
                return@withContext null
            }
            
            // Start a persistent shell process to monitor clipboard
            val command = """
                while true; do
                    current_clip=${'$'}(service call clipboard 2 s16 com.android.shell | grep -o '".*"' | sed 's/"//g')
                    if [ "${'$'}current_clip" != "${'$'}last_clip" ]; then
                        echo "CLIPBOARD_CHANGED:${'$'}current_clip"
                        last_clip="${'$'}current_clip"
                    fi
                    sleep 1
                done
            """.trimIndent()
            
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            
            // Start reading output in background
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            // Note: This would need to be handled in a separate coroutine
            // to continuously read the output and call onClipboardChange
            
            Log.d(TAG, "Started root clipboard monitoring")
            process
        } catch (e: Exception) {
            Log.e(TAG, "Error starting root clipboard monitoring", e)
            null
        }
    }
    
    private fun checkSuBinary(): Boolean {
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        
        return suPaths.any { File(it).exists() }
    }
    
    private fun checkRootPaths(): Boolean {
        val rootPaths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/which",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        
        return rootPaths.any { File(it).exists() }
    }
    
    private fun testSuCommand(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
    
    private fun getSystemProperty(key: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()
            process.waitFor()
            result?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Data class representing clipboard monitoring capabilities
     */
    data class ClipboardCapabilities(
        val isRooted: Boolean,
        val canBypassAndroid10Restrictions: Boolean,
        val recommendedPollingInterval: Long
    ) {
        fun getDescription(): String = if (isRooted) {
            "Enhanced (Root Access)"
        } else {
            "Standard (Non-rooted)"
        }
        
        fun getOptimizationLevel(): OptimizationLevel = if (isRooted) {
            OptimizationLevel.HIGH
        } else {
            OptimizationLevel.STANDARD
        }
    }
    
    enum class OptimizationLevel {
        STANDARD,   // Non-rooted devices
        HIGH        // Rooted devices
    }
}