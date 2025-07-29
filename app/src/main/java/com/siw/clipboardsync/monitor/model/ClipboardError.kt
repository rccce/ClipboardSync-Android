package com.siw.clipboardsync.monitor.model

/**
 * Sealed class hierarchy for comprehensive clipboard monitoring error handling.
 */
sealed class ClipboardError(
    val message: String,
    val cause: Throwable? = null,
    val errorCode: String,
    val isRecoverable: Boolean = true
) {
    /**
     * Root access was lost during monitoring.
     */
    object RootAccessLost : ClipboardError(
        message = "Root access was lost during clipboard monitoring",
        errorCode = "ROOT_ACCESS_LOST",
        isRecoverable = true
    )
    
    /**
     * Required permissions were denied.
     */
    data class PermissionDenied(
        val permission: String
    ) : ClipboardError(
        message = "Required permission denied: $permission",
        errorCode = "PERMISSION_DENIED",
        isRecoverable = true
    )
    
    /**
     * System-level hooks failed to initialize or operate.
     */
    data class SystemHookFailed(
        val hookType: String,
        val throwable: Throwable? = null
    ) : ClipboardError(
        message = "System-level hook failed: $hookType",
        cause = throwable,
        errorCode = "SYSTEM_HOOK_FAILED",
        isRecoverable = true
    )
    
    /**
     * Monitoring service was disconnected unexpectedly.
     */
    data class ServiceDisconnected(
        val serviceName: String,
        val throwable: Throwable? = null
    ) : ClipboardError(
        message = "Monitoring service disconnected: $serviceName",
        cause = throwable,
        errorCode = "SERVICE_DISCONNECTED",
        isRecoverable = true
    )
    
    /**
     * Clipboard content exceeds the maximum allowed size.
     */
    data class ContentTooLarge(
        val actualSize: Long,
        val maxSize: Long
    ) : ClipboardError(
        message = "Clipboard content too large: ${actualSize}B exceeds limit of ${maxSize}B",
        errorCode = "CONTENT_TOO_LARGE",
        isRecoverable = false
    )
    
    /**
     * Accessibility service is not enabled or available.
     */
    object AccessibilityServiceUnavailable : ClipboardError(
        message = "Accessibility service is not enabled or available",
        errorCode = "ACCESSIBILITY_SERVICE_UNAVAILABLE",
        isRecoverable = true
    )
    
    /**
     * Foreground service failed to start or maintain.
     */
    data class ForegroundServiceFailed(
        val throwable: Throwable? = null
    ) : ClipboardError(
        message = "Foreground service failed to start or maintain",
        cause = throwable,
        errorCode = "FOREGROUND_SERVICE_FAILED",
        isRecoverable = true
    )
    
    /**
     * Native library failed to load or execute.
     */
    data class NativeLibraryError(
        val libraryName: String,
        val throwable: Throwable? = null
    ) : ClipboardError(
        message = "Native library error: $libraryName",
        cause = throwable,
        errorCode = "NATIVE_LIBRARY_ERROR",
        isRecoverable = false
    )
    
    /**
     * Xposed/LSPosed framework is not available or failed.
     */
    data class XposedFrameworkError(
        val throwable: Throwable? = null
    ) : ClipboardError(
        message = "Xposed/LSPosed framework error",
        cause = throwable,
        errorCode = "XPOSED_FRAMEWORK_ERROR",
        isRecoverable = true
    )
    
    /**
     * Clipboard content type is not supported.
     */
    data class UnsupportedContentType(
        val contentType: String,
        val mimeType: String
    ) : ClipboardError(
        message = "Unsupported clipboard content type: $contentType ($mimeType)",
        errorCode = "UNSUPPORTED_CONTENT_TYPE",
        isRecoverable = false
    )
    
    /**
     * Timing optimization failed or timed out.
     */
    data class TimingOptimizationFailed(
        val operation: String,
        val throwable: Throwable? = null
    ) : ClipboardError(
        message = "Timing optimization failed for operation: $operation",
        cause = throwable,
        errorCode = "TIMING_OPTIMIZATION_FAILED",
        isRecoverable = true
    )
    
    /**
     * Maximum retry attempts exceeded.
     */
    data class MaxRetriesExceeded(
        val operation: String,
        val attempts: Int
    ) : ClipboardError(
        message = "Maximum retry attempts exceeded for operation: $operation (attempts: $attempts)",
        errorCode = "MAX_RETRIES_EXCEEDED",
        isRecoverable = false
    )
    
    /**
     * Polling-based monitoring encountered an error.
     */
    data class PollingError(
        val operation: String,
        val throwable: Throwable? = null
    ) : ClipboardError(
        message = "Polling operation failed: $operation",
        cause = throwable,
        errorCode = "POLLING_ERROR",
        isRecoverable = true
    )
    
    /**
     * No monitoring method is available on this device.
     */
    object NoMonitoringMethodAvailable : ClipboardError(
        message = "No clipboard monitoring method is available on this device",
        errorCode = "NO_MONITORING_METHOD_AVAILABLE",
        isRecoverable = false
    )
    
    /**
     * Specific monitoring method is not available.
     */
    data class MonitoringMethodUnavailable(
        val methodName: String
    ) : ClipboardError(
        message = "Monitoring method not available: $methodName",
        errorCode = "MONITORING_METHOD_UNAVAILABLE",
        isRecoverable = true
    )
    
    /**
     * Monitoring method failed to start or operate.
     */
    data class MonitoringMethodFailed(
        val methodName: String,
        val throwable: Throwable? = null
    ) : ClipboardError(
        message = "Monitoring method failed: $methodName",
        cause = throwable,
        errorCode = "MONITORING_METHOD_FAILED",
        isRecoverable = true
    )
    
    /**
     * All available monitoring methods have failed.
     */
    object AllMonitoringMethodsFailed : ClipboardError(
        message = "All available monitoring methods have failed",
        errorCode = "ALL_MONITORING_METHODS_FAILED",
        isRecoverable = false
    )
    
    /**
     * Unknown or unexpected error occurred.
     */
    data class UnknownError(
        val throwable: Throwable
    ) : ClipboardError(
        message = "Unknown error occurred: ${throwable.message}",
        cause = throwable,
        errorCode = "UNKNOWN_ERROR",
        isRecoverable = true
    )
    
    /**
     * Gets a user-friendly error message for display.
     * @return user-friendly error message
     */
    fun getUserFriendlyMessage(): String {
        return when (this) {
            is RootAccessLost -> "Root access was lost. Switching to alternative monitoring method."
            is PermissionDenied -> "Permission required: $permission. Please grant the permission to continue."
            is SystemHookFailed -> "System-level monitoring failed. Trying alternative method."
            is ServiceDisconnected -> "Monitoring service disconnected. Attempting to reconnect."
            is ContentTooLarge -> "Clipboard content is too large to sync (${actualSize / 1024}KB)."
            is AccessibilityServiceUnavailable -> "Please enable accessibility service for clipboard monitoring."
            is ForegroundServiceFailed -> "Background monitoring service failed. Please restart the app."
            is NativeLibraryError -> "System library error. This device may not be supported."
            is XposedFrameworkError -> "Xposed framework error. Switching to alternative method."
            is UnsupportedContentType -> "This type of clipboard content is not supported."
            is TimingOptimizationFailed -> "Clipboard timing optimization failed. Using default settings."
            is MaxRetriesExceeded -> "Operation failed after multiple attempts: $operation."
            is PollingError -> "Polling monitoring encountered an error: $operation."
            is NoMonitoringMethodAvailable -> "No clipboard monitoring method is available on this device."
            is MonitoringMethodUnavailable -> "Monitoring method '$methodName' is not available."
            is MonitoringMethodFailed -> "Monitoring method '$methodName' failed to start."
            is AllMonitoringMethodsFailed -> "All clipboard monitoring methods have failed. Please check device compatibility."
            is UnknownError -> "An unexpected error occurred. Please try again."
        }
    }
}