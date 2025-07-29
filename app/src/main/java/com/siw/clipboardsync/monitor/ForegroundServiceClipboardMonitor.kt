package com.siw.clipboardsync.monitor

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.service.ForegroundClipboardService
import com.siw.clipboardsync.utils.ClipboardUtils
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clipboard monitor implementation using a persistent foreground service.
 * This monitor provides reliable background clipboard access through a foreground service
 * with notification, ensuring continuous monitoring even when the app is backgrounded.
 */
@Singleton
class ForegroundServiceClipboardMonitor @Inject constructor(
    private val context: Context,
    private val clipboardManager: ClipboardManager
) : ClipboardMonitor {
    
    companion object {
        private const val TAG = "ForegroundServiceMonitor"
        private const val MONITORING_INTERVAL_MS = 1000L // 1 second polling interval
        private const val DEBOUNCE_WINDOW_MS = 200L // 200ms debounce window
    }
    
    private var clipboardListener: ClipboardListener? = null
    private val isMonitoring = AtomicBoolean(false)
    private var monitoringJob: Job? = null
    private var lastClipboardContent: String? = null
    private var lastChangeTimestamp: Long = 0L
    
    override suspend fun startMonitoring() {
        if (isMonitoring.get()) {
            Log.d(TAG, "Monitoring already active")
            return
        }
        
        try {
            // Start the foreground service
            val serviceIntent = Intent(context, ForegroundClipboardService::class.java).apply {
                action = ForegroundClipboardService.ACTION_START_MONITORING
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            // Initialize current clipboard state
            initializeClipboardState()
            
            // Start monitoring loop
            startMonitoringLoop()
            
            isMonitoring.set(true)
            Log.i(TAG, "Foreground service clipboard monitoring started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service monitoring", e)
            val error = ClipboardError.ForegroundServiceFailed(e)
            clipboardListener?.onMonitoringError(error)
            throw ClipboardMonitorException(error)
        }
    }
    
    override suspend fun stopMonitoring() {
        if (!isMonitoring.get()) {
            Log.d(TAG, "Monitoring not active")
            return
        }
        
        try {
            // Stop monitoring loop
            monitoringJob?.cancel()
            monitoringJob = null
            
            // Stop the foreground service
            val serviceIntent = Intent(context, ForegroundClipboardService::class.java).apply {
                action = ForegroundClipboardService.ACTION_STOP_MONITORING
            }
            context.startService(serviceIntent)
            
            isMonitoring.set(false)
            Log.i(TAG, "Foreground service clipboard monitoring stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service monitoring", e)
            clipboardListener?.onMonitoringError(
                ClipboardError.ForegroundServiceFailed(e)
            )
        }
    }
    
    override fun isMonitoring(): Boolean = isMonitoring.get()
    
    override fun getMonitoringMethod(): MonitoringMethod = MonitoringMethod.FOREGROUND_SERVICE
    
    override fun setClipboardListener(listener: ClipboardListener) {
        this.clipboardListener = listener
    }
    
    /**
     * Initializes the clipboard state to avoid triggering on existing content.
     */
    private fun initializeClipboardState() {
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val content = ClipboardUtils.extractTextContent(clipData)
                if (content != null) {
                    lastClipboardContent = content
                    lastChangeTimestamp = System.currentTimeMillis()
                    Log.d(TAG, "Initialized with current clipboard content")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize clipboard state", e)
        }
    }
    
    /**
     * Starts the monitoring loop that checks for clipboard changes.
     */
    private fun startMonitoringLoop() {
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isMonitoring.get()) {
                try {
                    checkClipboardChanges()
                    delay(MONITORING_INTERVAL_MS)
                } catch (e: CancellationException) {
                    Log.d(TAG, "Monitoring loop cancelled")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in monitoring loop", e)
                    clipboardListener?.onMonitoringError(
                        ClipboardError.UnknownError(e)
                    )
                    // Continue monitoring despite errors
                    delay(MONITORING_INTERVAL_MS * 2) // Wait longer on error
                }
            }
        }
    }
    
    /**
     * Checks for clipboard changes and notifies the listener if content has changed.
     */
    private suspend fun checkClipboardChanges() {
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData == null || clipData.itemCount == 0) {
                return
            }
            
            val content = ClipboardUtils.extractTextContent(clipData)
            if (content.isNullOrBlank()) {
                return
            }
            
            val currentTime = System.currentTimeMillis()
            
            // Check if content has changed
            if (content != lastClipboardContent) {
                // Apply debouncing to avoid rapid-fire events
                if (shouldDebounce(currentTime)) {
                    Log.v(TAG, "Clipboard change debounced")
                    return
                }
                
                lastClipboardContent = content
                lastChangeTimestamp = currentTime
                
                Log.d(TAG, "Clipboard content changed: ${content.take(50)}...")
                
                // Create ClipboardContent object
                val clipboardContent = ClipboardContent(
                    type = ClipboardContent.ContentType.TEXT,
                    data = content.toByteArray(Charsets.UTF_8),
                    mimeType = "text/plain",
                    timestamp = currentTime,
                    source = "foreground_service",
                    size = content.length.toLong(),
                    metadata = mapOf(
                        "monitoring_method" to "foreground_service",
                        "android_version" to Build.VERSION.RELEASE
                    )
                )
                
                // Notify listener
                clipboardListener?.onClipboardChanged(clipboardContent, currentTime)
            }
            
        } catch (e: SecurityException) {
            Log.w(TAG, "Clipboard access denied: ${e.message}")
            clipboardListener?.onMonitoringError(
                ClipboardError.PermissionDenied("android.permission.READ_CLIPBOARD")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard changes", e)
            clipboardListener?.onMonitoringError(
                ClipboardError.UnknownError(e)
            )
        }
    }
    
    /**
     * Determines if the clipboard change should be debounced.
     * @param currentTime the current timestamp
     * @return true if the change should be debounced, false otherwise
     */
    private fun shouldDebounce(currentTime: Long): Boolean {
        return (currentTime - lastChangeTimestamp) < DEBOUNCE_WINDOW_MS
    }
}