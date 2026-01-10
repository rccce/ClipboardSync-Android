package com.siw.clipboardsync.monitor

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clipboard monitor that uses READ_LOGS permission to detect clipboard changes
 * by monitoring system logs for clipboard-related events.
 * 
 * This approach works on non-rooted devices where READ_LOGS permission has been
 * granted via ADB: `adb shell pm grant com.siw.clipboardsync android.permission.READ_LOGS`
 * 
 * Requirements: 4.1, 4.2, 4.5
 */
@Singleton
class LogcatClipboardMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) : ClipboardMonitor {
    
    companion object {
        private const val TAG = "LogcatClipboardMonitor"
        
        // Logcat filter patterns for clipboard events
        private val CLIPBOARD_LOG_PATTERNS = listOf(
            "ClipboardService",
            "setPrimaryClip",
            "ClipData",
            "clipboard"
        )
        
        // Debounce time to prevent duplicate notifications
        private const val DEBOUNCE_MS = 500L
    }
    
    private var logcatProcess: Process? = null
    private var monitoringJob: Job? = null
    private var clipboardListener: ClipboardListener? = null
    private var isMonitoringActive = false
    private var lastNotificationTime = 0L
    private var lastClipboardContent: String? = null
    
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Checks if READ_LOGS permission is granted.
     * This permission must be granted via ADB as it's a signature-level permission.
     */
    fun isReadLogsPermissionGranted(): Boolean {
        return try {
            val result = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_LOGS
            ) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "READ_LOGS permission granted: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error checking READ_LOGS permission", e)
            false
        }
    }
    
    /**
     * Tests if we can actually read logcat output.
     * Even with permission granted, some devices may restrict access.
     */
    suspend fun canReadLogs(): Boolean = withContext(Dispatchers.IO) {
        if (!isReadLogsPermissionGranted()) {
            return@withContext false
        }
        
        return@withContext try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1"))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            process.destroy()
            
            exitCode == 0 && output.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error testing logcat access", e)
            false
        }
    }
    
    /**
     * Returns instructions for granting READ_LOGS permission via ADB.
     */
    fun getPermissionInstructions(): String {
        return """
            |To enable logcat-based clipboard monitoring, grant READ_LOGS permission via ADB:
            |
            |1. Enable USB debugging on your device
            |2. Connect your device to a computer with ADB installed
            |3. Run the following command:
            |   adb shell pm grant ${context.packageName} android.permission.READ_LOGS
            |
            |Note: This permission persists across app updates but may be revoked on factory reset.
        """.trimMargin()
    }
    
    override suspend fun startMonitoring() {
        if (isMonitoringActive) {
            Log.w(TAG, "Logcat monitoring already active")
            return
        }
        
        if (!isReadLogsPermissionGranted()) {
            Log.e(TAG, "READ_LOGS permission not granted, cannot start monitoring")
            return
        }
        
        Log.i(TAG, "Starting logcat clipboard monitoring")
        isMonitoringActive = true
        
        monitoringJob = coroutineScope.launch {
            startLogcatMonitoring()
        }
    }
    
    override suspend fun stopMonitoring() {
        Log.i(TAG, "Stopping logcat clipboard monitoring")
        isMonitoringActive = false
        
        monitoringJob?.cancel()
        monitoringJob = null
        
        logcatProcess?.destroy()
        logcatProcess = null
    }
    
    override fun isMonitoring(): Boolean = isMonitoringActive
    
    override fun getMonitoringMethod(): MonitoringMethod = MonitoringMethod.READ_LOGS
    
    override fun setClipboardListener(listener: ClipboardListener) {
        this.clipboardListener = listener
    }

    
    /**
     * Starts monitoring logcat for clipboard-related events.
     */
    private suspend fun startLogcatMonitoring() = withContext(Dispatchers.IO) {
        try {
            // Clear existing logcat buffer to start fresh
            try {
                Runtime.getRuntime().exec("logcat -c").waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear logcat buffer", e)
            }
            
            // Start logcat process with clipboard-related filters
            val logcatCommand = arrayOf(
                "logcat",
                "-v", "time",
                "ClipboardService:V",
                "ClipboardManager:V",
                "*:S"
            )
            
            val process = Runtime.getRuntime().exec(logcatCommand)
            logcatProcess = process
            
            Log.i(TAG, "Logcat process started, monitoring for clipboard events")
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            while (isMonitoringActive && isActive) {
                try {
                    val line = reader.readLine()
                    if (line != null) {
                        processLogLine(line)
                    } else {
                        // End of stream, process may have died
                        Log.w(TAG, "Logcat stream ended unexpectedly")
                        delay(1000)
                        
                        // Try to restart logcat
                        if (isMonitoringActive) {
                            Log.i(TAG, "Attempting to restart logcat monitoring")
                            process.destroy()
                            val newProcess = Runtime.getRuntime().exec(logcatCommand)
                            logcatProcess = newProcess
                        }
                    }
                } catch (e: Exception) {
                    if (isMonitoringActive) {
                        Log.e(TAG, "Error reading logcat", e)
                        delay(1000)
                    }
                }
            }
            
            reader.close()
            process.destroy()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in logcat monitoring", e)
        }
    }
    
    /**
     * Processes a single log line to detect clipboard events.
     */
    private suspend fun processLogLine(line: String) {
        // Check if this line indicates a clipboard change
        val isClipboardEvent = CLIPBOARD_LOG_PATTERNS.any { pattern ->
            line.contains(pattern, ignoreCase = true)
        }
        
        if (!isClipboardEvent) {
            return
        }
        
        // Check for specific clipboard change indicators
        val isClipboardChange = line.contains("setPrimaryClip", ignoreCase = true) ||
                line.contains("ClipData", ignoreCase = true) ||
                (line.contains("clipboard", ignoreCase = true) && line.contains("set", ignoreCase = true))
        
        if (isClipboardChange) {
            Log.d(TAG, "Clipboard event detected in logs: ${line.take(100)}")
            
            // Apply debounce
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastNotificationTime < DEBOUNCE_MS) {
                Log.d(TAG, "Debouncing clipboard event")
                return
            }
            lastNotificationTime = currentTime
            
            // Read and notify clipboard content
            readAndNotifyClipboard()
        }
    }
    
    /**
     * Reads the current clipboard content and notifies the listener.
     */
    private suspend fun readAndNotifyClipboard() {
        try {
            // Read clipboard on main thread (required by Android)
            val content = withContext(Dispatchers.Main) {
                try {
                    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboardManager.primaryClip
                    
                    if (clip != null && clip.itemCount > 0) {
                        val item = clip.getItemAt(0)
                        item.text?.toString() ?: item.coerceToText(context)?.toString()
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading clipboard on main thread", e)
                    null
                }
            }
            
            if (content != null && content != lastClipboardContent && content.isNotEmpty()) {
                Log.i(TAG, "Clipboard content changed: ${content.take(50)}...")
                lastClipboardContent = content
                
                // Notify listener
                clipboardListener?.onClipboardChanged(
                    ClipboardContent(
                        type = ClipboardContent.ContentType.TEXT,
                        data = content.toByteArray(),
                        mimeType = "text/plain",
                        timestamp = System.currentTimeMillis(),
                        source = "logcat_monitor",
                        size = content.length.toLong()
                    ),
                    System.currentTimeMillis()
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reading and notifying clipboard", e)
        }
    }
    
    /**
     * Gets the current status of the logcat monitor.
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "isMonitoring" to isMonitoringActive,
            "hasPermission" to isReadLogsPermissionGranted(),
            "logcatProcessAlive" to (logcatProcess?.isAlive ?: false),
            "lastNotificationTime" to lastNotificationTime,
            "lastClipboardContent" to (lastClipboardContent?.take(50) ?: "none")
        )
    }
}
