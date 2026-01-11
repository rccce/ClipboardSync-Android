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
    private var currentMethod: MonitoringMethod = MonitoringMethod.XPOSED_HOOKS
    
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
            
            // Check if we have any system-level access method available
            val hasNativeHooks = nativeHookManager.isAvailable()
            val hasXposedHooks = xposedHookManager.isAvailable()
            val hasSystemHooks = rootCapabilities.hasSystemHooks
            
            Log.d(TAG, "Native hook manager available: $hasNativeHooks")
            Log.d(TAG, "Xposed hook manager available: $hasXposedHooks")
            
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
                rootCapabilities.hasSystemHooks && hasNativeHooks -> {
                    startNativeHookMonitoring()
                }
                rootCapabilities.hasXposedFramework && hasXposedHooks -> {
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
                MonitoringMethod.XPOSED_HOOKS -> {
                    // Try native hooks first, then Xposed cleanup
                    nativeHookManager.cleanup()
                    xposedHookManager.unhookClipboardService()
                }
                MonitoringMethod.SHIZUKU -> {
                    // Shizuku cleanup handled elsewhere
                    Log.d(TAG, "Shizuku cleanup not needed in SystemLevelClipboardMonitor")
                }
                MonitoringMethod.FOREGROUND_SYNC -> {
                    // Foreground sync cleanup handled elsewhere
                    Log.d(TAG, "Foreground sync cleanup not needed in SystemLevelClipboardMonitor")
                }
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
     * 
     * NOTE: When Xposed mode is active, we should NOT start native hook monitoring
     * because StaticClipboardReceiver already handles Xposed broadcasts.
     * Starting both would cause duplicate clipboard events.
     */
    private suspend fun startNativeHookMonitoring(): Boolean {
        return try {
            // Check if Xposed framework is available - if so, skip native monitoring
            // because StaticClipboardReceiver handles Xposed broadcasts
            val rootCapabilities = rootDetectionService.getRootCapabilities()
            if (rootCapabilities.hasXposedFramework) {
                Log.i(TAG, "Xposed framework detected - skipping native hook monitoring")
                Log.i(TAG, "StaticClipboardReceiver will handle Xposed broadcasts instead")
                // Return true because Xposed mode is active and working via StaticClipboardReceiver
                currentMethod = MonitoringMethod.XPOSED_HOOKS
                return true
            }
            
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
            
            currentMethod = MonitoringMethod.XPOSED_HOOKS
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
     * 
     * NOTE: For Xposed/LSPosed, the actual clipboard monitoring is done by:
     * 1. The Xposed module hooks System Framework
     * 2. The module sends broadcasts when clipboard changes
     * 3. StaticClipboardReceiver (registered in AndroidManifest) receives broadcasts
     * 
     * This method just marks the hook as active - StaticClipboardReceiver handles the actual events.
     */
    private suspend fun startXposedHookMonitoring(): Boolean {
        return try {
            // For Xposed mode, we don't need to register a callback here
            // because StaticClipboardReceiver handles all Xposed broadcasts
            // and delegates to ClipboardSyncManager for deduplication and syncing
            xposedHookManager.hookClipboardService { content ->
                // This callback is not used for LSPosed/EdXposed
                // StaticClipboardReceiver handles broadcasts directly
                Log.d(TAG, "Xposed callback received (should not happen with LSPosed)")
            }
            currentMethod = MonitoringMethod.XPOSED_HOOKS
            Log.i(TAG, "Xposed hook monitoring activated - StaticClipboardReceiver will handle broadcasts")
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
            
            // Get last change timestamp for debounce check
            val lastTimestamp = if (timingOptimizer is AdaptiveTimingOptimizer) {
                timingOptimizer.getLastChangeTimestamp()
            } else {
                0L
            }
            
            // Apply debouncing to prevent rapid-fire events
            // Pass the last timestamp, not current time
            if (timingOptimizer.shouldDebounce(lastTimestamp)) {
                Log.d(TAG, "Clipboard change debounced (last change was ${currentTime - lastTimestamp}ms ago)")
                return
            }
            
            // Update timing optimizer state BEFORE processing to prevent duplicates
            if (timingOptimizer is AdaptiveTimingOptimizer) {
                timingOptimizer.updateLastChangeTimestamp(currentTime)
            }
            
            // Apply optimal read delay
            val readDelay = timingOptimizer.getOptimalReadDelay()
            if (readDelay > 0) {
                delay(readDelay)
            }
            
            // Record success
            if (timingOptimizer is AdaptiveTimingOptimizer) {
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
        val hasNativeHooks = nativeHookManager.isAvailable()
        val hasXposedHooks = xposedHookManager.isAvailable()
        return (rootCapabilities.hasSystemHooks && hasNativeHooks) ||
                (rootCapabilities.hasXposedFramework && hasXposedHooks)
    }
    
    /**
     * Gets detailed information about available system-level monitoring capabilities.
     */
    suspend fun getSystemCapabilities(): Map<String, Boolean> {
        val rootCapabilities = rootDetectionService.getRootCapabilities()
        val hasNativeHooks = nativeHookManager.isAvailable()
        val hasXposedHooks = xposedHookManager.isAvailable()
        return mapOf(
            "hasRoot" to rootDetectionService.isRooted(),
            "hasSystemHooks" to rootCapabilities.hasSystemHooks,
            "hasXposedFramework" to rootCapabilities.hasXposedFramework,
            "nativeHooksAvailable" to hasNativeHooks,
            "xposedHooksAvailable" to hasXposedHooks
        )
    }
}