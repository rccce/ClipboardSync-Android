package com.siw.clipboardsync.monitor

import android.content.Context
import android.util.Log
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.service.RootDetectionService
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System-level clipboard monitor implementation for rooted devices.
 * Uses native hooks and Xposed framework integration for direct system clipboard access.
 */
@Singleton
class SystemLevelClipboardMonitor @Inject constructor(
    private val context: Context,
    private val rootDetectionService: RootDetectionService,
    private val timingOptimizer: TimingOptimizer,
    private val nativeHookManager: NativeHookManager,
    private val xposedHookManager: XposedHookManager
) : ClipboardMonitor {
    
    companion object {
        private const val TAG = "SystemLevelClipboardMonitor"
    }
    
    private var isMonitoring = false
    private var clipboardListener: ClipboardListener? = null
    private var monitoringJob: Job? = null
    private var currentMethod: MonitoringMethod = MonitoringMethod.SYSTEM_HOOKS
    
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override suspend fun startMonitoring() {
        if (isMonitoring) {
            Log.w(TAG, "Monitoring is already active")
            return
        }
        
        try {
            val rootCapabilities = rootDetectionService.getRootCapabilities()
            Log.d(TAG, "=== SystemLevelClipboardMonitor startMonitoring ===")
            Log.d(TAG, "Root capabilities: hasSystemHooks=${rootCapabilities.hasSystemHooks}, hasXposedFramework=${rootCapabilities.hasXposedFramework}")
            Log.d(TAG, "Native hook manager available: ${nativeHookManager.isAvailable()}")
            Log.d(TAG, "Xposed hook manager available: ${xposedHookManager.isAvailable()}")
            
            // Check if we have any system-level access method available
            val hasNativeHooks = nativeHookManager.isAvailable()
            val hasXposedHooks = xposedHookManager.isAvailable()
            val hasSystemHooks = rootCapabilities.hasSystemHooks
            
            Log.d(TAG, "System-level access check: nativeHooks=$hasNativeHooks, xposedHooks=$hasXposedHooks, systemHooks=$hasSystemHooks")
            
            if (!hasSystemHooks && !rootCapabilities.hasXposedFramework && !hasNativeHooks) {
                Log.e(TAG, "No system-level hooks available - cannot start system-level monitoring")
                throw ClipboardMonitorException(
                    ClipboardError.SystemHookFailed(
                        hookType = "No system-level hooks available",
                        throwable = IllegalStateException("Device does not support system-level monitoring")
                    )
                )
            }
            
            // Try native hooks first, then Xposed as fallback
            val success = when {
                rootCapabilities.hasSystemHooks && nativeHookManager.isAvailable() -> {
                    startNativeHookMonitoring()
                }
                rootCapabilities.hasXposedFramework && xposedHookManager.isAvailable() -> {
                    startXposedHookMonitoring()
                }
                else -> false
            }
            
            if (!success) {
                throw ClipboardMonitorException(
                    ClipboardError.SystemHookFailed(
                        hookType = "All system-level hooks failed",
                        throwable = IllegalStateException("Unable to initialize any system-level monitoring method")
                    )
                )
            }
            
            isMonitoring = true
            Log.i(TAG, "System-level clipboard monitoring started using ${currentMethod.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start system-level monitoring", e)
            val error = when (e) {
                is ClipboardMonitorException -> e.clipboardError
                else -> ClipboardError.SystemHookFailed("System monitoring initialization", e)
            }
            clipboardListener?.onMonitoringError(error)
            throw ClipboardMonitorException(error)
        }
    }
    
    override suspend fun stopMonitoring() {
        if (!isMonitoring) {
            Log.w(TAG, "Monitoring is not active")
            return
        }
        
        try {
            monitoringJob?.cancel()
            monitoringJob = null
            
            when (currentMethod) {
                MonitoringMethod.SYSTEM_HOOKS -> nativeHookManager.cleanup()
                MonitoringMethod.XPOSED_HOOKS -> xposedHookManager.unhookClipboardService()
                else -> Log.w(TAG, "Unknown monitoring method: $currentMethod")
            }
            
            isMonitoring = false
            Log.i(TAG, "System-level clipboard monitoring stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping system-level monitoring", e)
            val error = ClipboardError.SystemHookFailed("System monitoring cleanup", e)
            clipboardListener?.onMonitoringError(error)
        }
    }
    
    override fun isMonitoring(): Boolean = isMonitoring
    
    override fun getMonitoringMethod(): MonitoringMethod = currentMethod
    
    override fun setClipboardListener(listener: ClipboardListener) {
        this.clipboardListener = listener
    }
    
    /**
     * Starts native hook-based monitoring.
     */
    private suspend fun startNativeHookMonitoring(): Boolean {
        return try {
            // Initialize native hooks first
            if (!nativeHookManager.initialize()) {
                Log.e(TAG, "Failed to initialize native hooks")
                return false
            }
            
            nativeHookManager.registerClipboardCallback { contentString ->
                coroutineScope.launch {
                    // Convert string content to ClipboardContent
                    val clipboardContent = ClipboardContent(
                        type = ClipboardContent.ContentType.TEXT,
                        data = contentString.toByteArray(),
                        mimeType = "text/plain",
                        timestamp = System.currentTimeMillis(),
                        source = "system_native",
                        size = contentString.length.toLong()
                    )
                    handleClipboardChange(clipboardContent)
                }
            }
            
            // Start native monitoring
            if (!nativeHookManager.startMonitoring()) {
                Log.e(TAG, "Failed to start native monitoring")
                return false
            }
            
            currentMethod = MonitoringMethod.SYSTEM_HOOKS
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start native hook monitoring", e)
            clipboardListener?.onMonitoringError(
                ClipboardError.SystemHookFailed("Native hooks", e)
            )
            false
        }
    }
    
    /**
     * Starts Xposed framework-based monitoring.
     */
    private suspend fun startXposedHookMonitoring(): Boolean {
        return try {
            xposedHookManager.hookClipboardService { content ->
                coroutineScope.launch {
                    handleClipboardChange(content)
                }
            }
            currentMethod = MonitoringMethod.XPOSED_HOOKS
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Xposed hook monitoring", e)
            clipboardListener?.onMonitoringError(
                ClipboardError.XposedFrameworkError(e)
            )
            false
        }
    }
    
    /**
     * Handles clipboard change events with optimal timing integration.
     */
    private suspend fun handleClipboardChange(content: ClipboardContent) {
        try {
            val currentTime = System.currentTimeMillis()
            
            // Apply debouncing to prevent rapid-fire events
            if (timingOptimizer.shouldDebounce(currentTime)) {
                Log.d(TAG, "Clipboard change debounced")
                return
            }
            
            // Apply optimal read delay
            val readDelay = timingOptimizer.getOptimalReadDelay()
            if (readDelay > 0) {
                delay(readDelay)
            }
            
            // Update timing optimizer state
            if (timingOptimizer is AdaptiveTimingOptimizer) {
                timingOptimizer.updateLastChangeTimestamp(currentTime)
                timingOptimizer.recordSuccess()
            }
            
            // Notify listener of clipboard change
            clipboardListener?.onClipboardChanged(content, currentTime)
            
            Log.d(TAG, "Clipboard change handled: ${content.type} (${content.size} bytes)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard change", e)
            
            // Record failure for timing optimization
            if (timingOptimizer is AdaptiveTimingOptimizer) {
                timingOptimizer.recordFailure()
            }
            
            val error = ClipboardError.TimingOptimizationFailed("handleClipboardChange", e)
            clipboardListener?.onMonitoringError(error)
        }
    }
    
    /**
     * Performs retry logic with exponential backoff for failed operations.
     */
    private suspend fun retryOperation(
        operation: String,
        maxAttempts: Int = 3,
        block: suspend () -> Unit
    ) {
        var attempt = 1
        var lastException: Exception? = null
        
        while (attempt <= maxAttempts) {
            try {
                block()
                return // Success
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Operation '$operation' failed on attempt $attempt", e)
                
                if (attempt < maxAttempts) {
                    val retryDelay = timingOptimizer.getRetryDelay(attempt)
                    Log.d(TAG, "Retrying operation '$operation' in ${retryDelay}ms")
                    delay(retryDelay)
                }
                attempt++
            }
        }
        
        // All attempts failed
        val error = ClipboardError.MaxRetriesExceeded(operation, maxAttempts)
        clipboardListener?.onMonitoringError(error)
        throw ClipboardMonitorException(error)
    }
    
    /**
     * Checks if system-level monitoring is available on this device.
     */
    suspend fun isSystemLevelMonitoringAvailable(): Boolean {
        val rootCapabilities = rootDetectionService.getRootCapabilities()
        return (rootCapabilities.hasSystemHooks && nativeHookManager.isAvailable()) ||
                (rootCapabilities.hasXposedFramework && xposedHookManager.isAvailable())
    }
    
    /**
     * Gets detailed information about available system-level monitoring capabilities.
     */
    suspend fun getSystemCapabilities(): Map<String, Boolean> {
        val rootCapabilities = rootDetectionService.getRootCapabilities()
        return mapOf(
            "hasRoot" to rootDetectionService.isRooted(),
            "hasSystemHooks" to rootCapabilities.hasSystemHooks,
            "hasXposedFramework" to rootCapabilities.hasXposedFramework,
            "nativeHooksAvailable" to nativeHookManager.isAvailable(),
            "xposedHooksAvailable" to xposedHookManager.isAvailable()
        )
    }
}