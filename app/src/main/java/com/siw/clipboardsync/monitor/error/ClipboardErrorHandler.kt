package com.siw.clipboardsync.monitor.error

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.monitor.ClipboardMonitor
import com.siw.clipboardsync.monitor.AccessibilityClipboardMonitor
import com.siw.clipboardsync.monitor.ForegroundServiceClipboardMonitor
import com.siw.clipboardsync.monitor.PollingClipboardMonitor
import com.siw.clipboardsync.monitor.SystemLevelClipboardMonitor
import com.siw.clipboardsync.service.RootDetectionService
import com.siw.clipboardsync.utils.AccessibilityPermissionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardErrorHandler @Inject constructor(
    private val context: Context,
    private val rootDetectionService: RootDetectionService,
    private val accessibilityPermissionManager: AccessibilityPermissionManager,
    private val notificationManager: ErrorNotificationManager
) {
    private val fallbackChain = listOf(
        MonitoringMethod.SYSTEM_HOOKS,
        MonitoringMethod.XPOSED_HOOKS,
        MonitoringMethod.ACCESSIBILITY_SERVICE,
        MonitoringMethod.FOREGROUND_SERVICE,
        MonitoringMethod.POLLING_FALLBACK
    )
    
    private val _currentMethod = MutableStateFlow<MonitoringMethod?>(null)
    val currentMethod: StateFlow<MonitoringMethod?> = _currentMethod.asStateFlow()
    
    private val _errorState = MutableStateFlow<ClipboardError?>(null)
    val errorState: StateFlow<ClipboardError?> = _errorState.asStateFlow()
    
    private val _isRecovering = MutableStateFlow(false)
    val isRecovering: StateFlow<Boolean> = _isRecovering.asStateFlow()
    
    private var recoveryJob: Job? = null
    private var currentMonitor: ClipboardMonitor? = null
    
    companion object {
        private const val RECOVERY_TIMEOUT_MS = 5000L
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val RECOVERY_DELAY_MS = 1000L
    }
    
    suspend fun handleError(
        error: ClipboardError,
        currentMethod: MonitoringMethod,
        currentMonitor: ClipboardMonitor
    ): ClipboardMonitor? {
        _errorState.value = error
        this.currentMonitor = currentMonitor
        
        return when (error) {
            is ClipboardError.RootAccessLost -> handleRootAccessLost(currentMethod)
            is ClipboardError.PermissionDenied -> handlePermissionDenied(error)
            is ClipboardError.SystemHookFailed -> handleSystemHookFailed(currentMethod)
            is ClipboardError.ServiceDisconnected -> handleServiceDisconnected(currentMethod)
            is ClipboardError.ContentTooLarge -> handleContentTooLarge(error)
            is ClipboardError.AccessibilityServiceUnavailable -> handleAccessibilityServiceUnavailable()
            is ClipboardError.ForegroundServiceFailed -> handleForegroundServiceFailed(currentMethod)
            is ClipboardError.NativeLibraryError -> handleNativeLibraryError(error, currentMethod)
            is ClipboardError.XposedFrameworkError -> handleXposedFrameworkError(currentMethod)
            is ClipboardError.UnsupportedContentType -> handleUnsupportedContentType(error)
            is ClipboardError.TimingOptimizationFailed -> handleTimingOptimizationFailed(error)
            is ClipboardError.MaxRetriesExceeded -> handleMaxRetriesExceeded(error, currentMethod)
            is ClipboardError.PollingError -> handlePollingError(error, currentMethod)
            is ClipboardError.NoMonitoringMethodAvailable -> handleNoMonitoringMethodAvailable(error)
            is ClipboardError.MonitoringMethodUnavailable -> handleMonitoringMethodUnavailable(error, currentMethod)
            is ClipboardError.MonitoringMethodFailed -> handleMonitoringMethodFailed(error, currentMethod)
            is ClipboardError.AllMonitoringMethodsFailed -> handleAllMonitoringMethodsFailed(error)
            is ClipboardError.UnknownError -> handleUnknownError(error, currentMethod)
        }
    }
    
    private suspend fun handleRootAccessLost(currentMethod: MonitoringMethod): ClipboardMonitor? {
        notificationManager.showRootAccessLostNotification()
        return switchToNonRootMethod()
    }
    
    private suspend fun handlePermissionDenied(error: ClipboardError.PermissionDenied): ClipboardMonitor? {
        return when (error.permission) {
            "android.permission.BIND_ACCESSIBILITY_SERVICE" -> {
                notificationManager.showAccessibilityPermissionNeeded()
                requestAccessibilityPermission()
                null
            }
            "android.permission.POST_NOTIFICATIONS" -> {
                notificationManager.showNotificationPermissionNeeded()
                requestNotificationPermission()
                null
            }
            "android.permission.FOREGROUND_SERVICE" -> {
                notificationManager.showForegroundServicePermissionNeeded()
                null
            }
            else -> {
                notificationManager.showUnknownErrorNotification("Permission denied: ${error.permission}")
                null
            }
        }
    }
    
    private suspend fun handleSystemHookFailed(currentMethod: MonitoringMethod): ClipboardMonitor? {
        notificationManager.showSystemHookFailedNotification()
        return tryNextFallback(currentMethod)
    }
    
    private suspend fun handleServiceDisconnected(currentMethod: MonitoringMethod): ClipboardMonitor? {
        return attemptServiceReconnection(currentMethod)
    }
    
    private suspend fun handleContentTooLarge(error: ClipboardError.ContentTooLarge): ClipboardMonitor? {
        notificationManager.showContentTooLargeNotification(error.actualSize, error.maxSize)
        return currentMonitor // Continue with current monitor
    }
    
    private suspend fun handleAccessibilityServiceUnavailable(): ClipboardMonitor? {
        notificationManager.showAccessibilityPermissionNeeded()
        requestAccessibilityPermission()
        return null
    }
    
    private suspend fun handleForegroundServiceFailed(currentMethod: MonitoringMethod): ClipboardMonitor? {
        notificationManager.showForegroundServicePermissionNeeded()
        return tryNextFallback(currentMethod)
    }
    
    private suspend fun handleNativeLibraryError(
        error: ClipboardError.NativeLibraryError,
        currentMethod: MonitoringMethod
    ): ClipboardMonitor? {
        notificationManager.showUnknownErrorNotification("Native library error: ${error.libraryName}")
        return tryNextFallback(currentMethod)
    }
    
    private suspend fun handleXposedFrameworkError(currentMethod: MonitoringMethod): ClipboardMonitor? {
        notificationManager.showSystemHookFailedNotification()
        return tryNextFallback(currentMethod)
    }
    
    private suspend fun handleUnsupportedContentType(error: ClipboardError.UnsupportedContentType): ClipboardMonitor? {
        notificationManager.showUnknownErrorNotification("Unsupported content type: ${error.contentType}")
        return currentMonitor // Continue with current monitor
    }
    
    private suspend fun handleTimingOptimizationFailed(error: ClipboardError.TimingOptimizationFailed): ClipboardMonitor? {
        // Log the timing failure but continue with current monitor
        return currentMonitor
    }
    
    private suspend fun handleMaxRetriesExceeded(
        error: ClipboardError.MaxRetriesExceeded,
        currentMethod: MonitoringMethod
    ): ClipboardMonitor? {
        notificationManager.showRecoveryFailedNotification()
        return tryNextFallback(currentMethod)
    }
    
    private suspend fun handlePollingError(
        error: ClipboardError.PollingError,
        currentMethod: MonitoringMethod
    ): ClipboardMonitor? {
        return attemptServiceReconnection(currentMethod)
    }
    
    private suspend fun handleNoMonitoringMethodAvailable(error: ClipboardError.NoMonitoringMethodAvailable): ClipboardMonitor? {
        notificationManager.showUnknownErrorNotification("No monitoring method available on this device")
        return null
    }
    
    private suspend fun handleMonitoringMethodUnavailable(
        error: ClipboardError.MonitoringMethodUnavailable,
        currentMethod: MonitoringMethod
    ): ClipboardMonitor? {
        notificationManager.showUnknownErrorNotification("Monitoring method unavailable: ${error.methodName}")
        return tryNextFallback(currentMethod)
    }
    
    private suspend fun handleMonitoringMethodFailed(
        error: ClipboardError.MonitoringMethodFailed,
        currentMethod: MonitoringMethod
    ): ClipboardMonitor? {
        notificationManager.showUnknownErrorNotification("Monitoring method failed: ${error.methodName}")
        return tryNextFallback(currentMethod)
    }
    
    private suspend fun handleAllMonitoringMethodsFailed(error: ClipboardError.AllMonitoringMethodsFailed): ClipboardMonitor? {
        notificationManager.showRecoveryFailedNotification()
        return null
    }
    
    private suspend fun handleUnknownError(
        error: ClipboardError.UnknownError,
        currentMethod: MonitoringMethod
    ): ClipboardMonitor? {
        notificationManager.showUnknownErrorNotification(error.throwable.message ?: "Unknown error")
        return tryNextFallback(currentMethod)
    }
    
    private suspend fun switchToNonRootMethod(): ClipboardMonitor? {
        val nonRootMethods = fallbackChain.filter { 
            it != MonitoringMethod.SYSTEM_HOOKS && it != MonitoringMethod.XPOSED_HOOKS 
        }
        return tryMethodChain(nonRootMethods)
    }
    
    private suspend fun tryNextFallback(currentMethod: MonitoringMethod): ClipboardMonitor? {
        val currentIndex = fallbackChain.indexOf(currentMethod)
        if (currentIndex == -1 || currentIndex >= fallbackChain.size - 1) {
            // No more fallbacks available
            notificationManager.showNoFallbackAvailableNotification()
            return null
        }
        
        val remainingMethods = fallbackChain.drop(currentIndex + 1)
        return tryMethodChain(remainingMethods)
    }
    
    private suspend fun tryMethodChain(methods: List<MonitoringMethod>): ClipboardMonitor? {
        for (method in methods) {
            if (isMethodAvailable(method)) {
                val monitor = createMonitorForMethod(method)
                if (monitor != null) {
                    try {
                        monitor.startMonitoring()
                        _currentMethod.value = method
                        _errorState.value = null
                        notificationManager.showRecoverySuccessNotification(method)
                        return monitor
                    } catch (e: Exception) {
                        // Continue to next method
                        continue
                    }
                }
            }
        }
        return null
    }
    
    private suspend fun attemptServiceReconnection(currentMethod: MonitoringMethod): ClipboardMonitor? {
        if (_isRecovering.value) {
            return null // Already attempting recovery
        }
        
        _isRecovering.value = true
        recoveryJob?.cancel()
        
        recoveryJob = CoroutineScope(Dispatchers.IO).launch {
            var attempts = 0
            while (attempts < MAX_RECOVERY_ATTEMPTS && isActive) {
                attempts++
                
                try {
                    delay(RECOVERY_DELAY_MS)
                    
                    // Try to restart current monitor
                    currentMonitor?.let { monitor ->
                        monitor.stopMonitoring()
                        delay(500) // Brief pause before restart
                        monitor.startMonitoring()
                        
                        if (monitor.isMonitoring()) {
                            _errorState.value = null
                            _isRecovering.value = false
                            notificationManager.showRecoverySuccessNotification(currentMethod)
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    // Continue with next attempt
                }
                
                if (attempts >= MAX_RECOVERY_ATTEMPTS) {
                    // Try fallback method
                    val fallbackMonitor = tryNextFallback(currentMethod)
                    if (fallbackMonitor != null) {
                        currentMonitor = fallbackMonitor
                    } else {
                        notificationManager.showRecoveryFailedNotification()
                    }
                }
            }
            
            _isRecovering.value = false
        }
        
        // Wait for recovery with timeout
        withTimeoutOrNull(RECOVERY_TIMEOUT_MS) {
            recoveryJob?.join()
        }
        
        return currentMonitor
    }
    
    private suspend fun isMethodAvailable(method: MonitoringMethod): Boolean {
        return when (method) {
            MonitoringMethod.SYSTEM_HOOKS -> {
                rootDetectionService.isRooted() && 
                rootDetectionService.getRootCapabilities().hasSystemHooks
            }
            MonitoringMethod.XPOSED_HOOKS -> {
                rootDetectionService.isRooted() && 
                rootDetectionService.getRootCapabilities().hasXposedFramework
            }
            MonitoringMethod.ACCESSIBILITY_SERVICE -> {
                accessibilityPermissionManager.isAccessibilityServiceEnabled()
            }
            MonitoringMethod.FOREGROUND_SERVICE -> true
            MonitoringMethod.POLLING_FALLBACK -> true
        }
    }
    
    private fun createMonitorForMethod(method: MonitoringMethod): ClipboardMonitor? {
        return when (method) {
            MonitoringMethod.SYSTEM_HOOKS,
            MonitoringMethod.XPOSED_HOOKS -> {
                // These would be created by dependency injection in real implementation
                null // SystemLevelClipboardMonitor()
            }
            MonitoringMethod.ACCESSIBILITY_SERVICE -> {
                null // AccessibilityClipboardMonitor()
            }
            MonitoringMethod.FOREGROUND_SERVICE -> {
                null // ForegroundServiceClipboardMonitor()
            }
            MonitoringMethod.POLLING_FALLBACK -> {
                null // PollingClipboardMonitor()
            }
        }
    }
    
    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    private fun requestNotificationPermission() {
        // This would typically be handled by the UI layer
        notificationManager.requestNotificationPermission()
    }
    
    fun clearError() {
        _errorState.value = null
    }
    
    fun cancelRecovery() {
        recoveryJob?.cancel()
        _isRecovering.value = false
    }
}