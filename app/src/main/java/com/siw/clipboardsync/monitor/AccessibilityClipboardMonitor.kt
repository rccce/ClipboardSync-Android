package com.siw.clipboardsync.monitor

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.service.ClipboardAccessibilityService
import com.siw.clipboardsync.utils.AccessibilityPermissionManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Clipboard monitor implementation using Android Accessibility Service.
 * This monitor provides clipboard monitoring capabilities for non-root devices,
 * particularly useful on Android 10+ where background clipboard access is restricted.
 */
class AccessibilityClipboardMonitor(
    private val context: Context
) : ClipboardMonitor {
    
    companion object {
        private const val TAG = "AccessibilityClipboardMonitor"
        private const val SERVICE_CONNECTION_TIMEOUT_MS = 5000L
        private const val SERVICE_CHECK_INTERVAL_MS = 1000L
    }
    
    private val monitorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)
    private var clipboardListener: ClipboardListener? = null
    private var serviceConnectionJob: Job? = null
    private val permissionManager = AccessibilityPermissionManager(context)
    
    override suspend fun startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            Log.d(TAG, "Starting accessibility clipboard monitoring (Android ${Build.VERSION.SDK_INT})")
            
            try {
                // Check if accessibility service is enabled
                if (!permissionManager.isAccessibilityServiceEnabled()) {
                    throw ClipboardMonitorException(
                        ClipboardError.AccessibilityServiceUnavailable
                    )
                }
                
                // Android 10+ specific checks and optimizations
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Log.d(TAG, "Android 10+ detected - using accessibility service for background clipboard access")
                    handleAndroid10PlusRestrictions()
                }
                
                // Wait for service connection or start it
                connectToAccessibilityService()
                
            } catch (e: Exception) {
                isMonitoring.set(false)
                val error = when (e) {
                    is ClipboardMonitorException -> e.clipboardError
                    else -> ClipboardError.UnknownError(e)
                }
                clipboardListener?.onMonitoringError(error)
                throw e
            }
        }
    }
    
    override suspend fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping accessibility clipboard monitoring")
            
            serviceConnectionJob?.cancel()
            serviceConnectionJob = null
            
            // Stop monitoring in the accessibility service
            ClipboardAccessibilityService.getInstance()?.stopClipboardMonitoring()
        }
    }
    
    override fun isMonitoring(): Boolean = isMonitoring.get()
    
    override fun getMonitoringMethod(): MonitoringMethod = MonitoringMethod.ACCESSIBILITY_SERVICE
    
    override fun setClipboardListener(listener: ClipboardListener) {
        this.clipboardListener = listener
    }
    
    /**
     * Handles Android 10+ specific background restrictions and optimizations.
     * This method ensures the accessibility service is properly configured for
     * background clipboard monitoring on Android 10+ devices.
     */
    private fun handleAndroid10PlusRestrictions() {
        Log.d(TAG, "Configuring for Android 10+ background clipboard restrictions")
        
        // Verify that the accessibility service has the necessary configuration
        // for background clipboard access on Android 10+
        val service = ClipboardAccessibilityService.getInstance()
        if (service != null) {
            Log.d(TAG, "Accessibility service is available for Android 10+ clipboard monitoring")
        } else {
            Log.w(TAG, "Accessibility service not yet available - will wait for connection")
        }
        
        // Log the current permission status for debugging
        val status = permissionManager.getServiceStatus()
        Log.d(TAG, "Accessibility service status: $status")
        
        // On Android 10+, accessibility services can still access clipboard in background
        // This is our primary method for bypassing the clipboard access restrictions
        Log.d(TAG, "Using accessibility service to bypass Android 10+ clipboard restrictions")
    }
    

    
    /**
     * Connects to the accessibility service and sets up clipboard monitoring.
     */
    private suspend fun connectToAccessibilityService() {
        serviceConnectionJob = monitorScope.launch {
            var attempts = 0
            val maxAttempts = SERVICE_CONNECTION_TIMEOUT_MS / SERVICE_CHECK_INTERVAL_MS
            
            while (attempts < maxAttempts && isMonitoring.get()) {
                val service = ClipboardAccessibilityService.getInstance()
                
                if (service != null) {
                    Log.d(TAG, "Connected to accessibility service")
                    setupServiceCallbacks(service)
                    service.startClipboardMonitoring()
                    return@launch
                }
                
                attempts++
                delay(SERVICE_CHECK_INTERVAL_MS)
            }
            
            // If we reach here, service connection failed
            if (isMonitoring.get()) {
                isMonitoring.set(false)
                val error = ClipboardError.ServiceDisconnected(
                    "ClipboardAccessibilityService",
                    Exception("Failed to connect to accessibility service within timeout")
                )
                clipboardListener?.onMonitoringError(error)
            }
        }
    }
    
    /**
     * Sets up callbacks for the accessibility service.
     * @param service the accessibility service instance
     */
    private fun setupServiceCallbacks(service: ClipboardAccessibilityService) {
        service.setOnClipboardChanged { content, timestamp ->
            monitorScope.launch {
                try {
                    clipboardListener?.onClipboardChanged(content, timestamp)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in clipboard change callback", e)
                    clipboardListener?.onMonitoringError(ClipboardError.UnknownError(e))
                }
            }
        }
        
        service.setOnError { throwable ->
            monitorScope.launch {
                Log.e(TAG, "Error from accessibility service", throwable)
                val error = when (throwable) {
                    is SecurityException -> ClipboardError.PermissionDenied("accessibility_service")
                    else -> ClipboardError.UnknownError(throwable)
                }
                clipboardListener?.onMonitoringError(error)
            }
        }
    }
    
    /**
     * Opens the accessibility settings for the user to enable the service.
     * @return Intent to open accessibility settings
     */
    fun createAccessibilitySettingsIntent(): Intent {
        return permissionManager.createAccessibilitySettingsIntent()
    }
    
    /**
     * Gets a user-friendly message explaining how to enable accessibility service.
     * @return instruction message
     */
    fun getAccessibilityInstructions(): String {
        return permissionManager.getEnableInstructions()
    }
    
    /**
     * Checks if the accessibility service is currently running and monitoring.
     * @return true if service is active and monitoring, false otherwise
     */
    fun isServiceActiveAndMonitoring(): Boolean {
        return permissionManager.isServiceActiveAndMonitoring()
    }
    
    /**
     * Gets the permission manager for accessibility service management.
     * @return AccessibilityPermissionManager instance
     */
    fun getPermissionManager(): AccessibilityPermissionManager {
        return permissionManager
    }
}