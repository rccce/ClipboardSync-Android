package com.siw.clipboardsync.data.model

import com.siw.clipboardsync.manager.ClipboardSyncManager
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.monitor.model.RootCapabilities

/**
 * Comprehensive system status information
 */
data class SystemStatus(
    val rootStatus: RootStatus,
    val monitoringStatus: MonitoringStatus,
    val connectionStatus: ConnectionStatus,
    val batteryOptimizationStatus: BatteryOptimizationStatus,
    val permissionStatus: PermissionStatus,
    val deviceInfo: DeviceInfo
)

/**
 * Root access and capabilities status
 */
data class RootStatus(
    val isRooted: Boolean,
    val rootMethod: RootCapabilities.RootMethod,
    val isRootAccessible: Boolean,
    val hasSystemHooks: Boolean,
    val hasXposedFramework: Boolean,
    val hasNativeAccess: Boolean,
    val suBinaryPath: String?,
    val canUseSystemLevelMonitoring: Boolean
) {
    val statusText: String
        get() = when {
            !isRooted -> "Not Rooted"
            !isRootAccessible -> "Root Detected (Not Accessible)"
            canUseSystemLevelMonitoring -> "Root Available (System Access)"
            else -> "Root Available (Limited Access)"
        }
    
    val statusColor: SystemStatusColor
        get() = when {
            canUseSystemLevelMonitoring -> SystemStatusColor.SUCCESS
            isRootAccessible -> SystemStatusColor.WARNING
            isRooted -> SystemStatusColor.WARNING
            else -> SystemStatusColor.NEUTRAL
        }
}

/**
 * Clipboard monitoring status
 */
data class MonitoringStatus(
    val isMonitoring: Boolean,
    val currentMethod: MonitoringMethod?,
    val isAdvancedMonitoring: Boolean,
    val availableMethods: List<MonitoringMethod>
) {
    val statusText: String
        get() = when {
            !isMonitoring -> "Not Monitoring"
            // currentMethod == MonitoringMethod.IME_SERVICE -> "IME Service Active"
            currentMethod == MonitoringMethod.SYSTEM_HOOKS -> "System Hooks Active"
            currentMethod == MonitoringMethod.ACCESSIBILITY_SERVICE -> "Accessibility Service Active"
            currentMethod == MonitoringMethod.FOREGROUND_SERVICE -> "Foreground Service Active"
            currentMethod == MonitoringMethod.POLLING_FALLBACK -> "Polling Fallback Active"
            else -> "Unknown Method"
        }
    
    val statusColor: SystemStatusColor
        get() = when {
            !isMonitoring -> SystemStatusColor.ERROR
            // currentMethod == MonitoringMethod.IME_SERVICE -> SystemStatusColor.SUCCESS
            currentMethod == MonitoringMethod.SYSTEM_HOOKS -> SystemStatusColor.SUCCESS
            currentMethod == MonitoringMethod.ACCESSIBILITY_SERVICE -> SystemStatusColor.WARNING
            currentMethod == MonitoringMethod.FOREGROUND_SERVICE -> SystemStatusColor.WARNING
            currentMethod == MonitoringMethod.POLLING_FALLBACK -> SystemStatusColor.ERROR
            else -> SystemStatusColor.NEUTRAL
        }
}



/**
 * WebSocket and network connection status
 */
data class ConnectionStatus(
    val syncStatus: ClipboardSyncManager.SyncStatus,
    val isServiceRunning: Boolean,
    val lastSyncTime: Long?,
    val connectionLatency: Long?
) {
    val statusText: String
        get() = when (syncStatus) {
            ClipboardSyncManager.SyncStatus.CONNECTED -> "Connected"
            ClipboardSyncManager.SyncStatus.CONNECTING -> "Connecting..."
            ClipboardSyncManager.SyncStatus.SYNCING -> "Syncing..."
            ClipboardSyncManager.SyncStatus.ERROR -> "Error"
            ClipboardSyncManager.SyncStatus.DISCONNECTED -> "Disconnected"
        }
    
    val statusColor: SystemStatusColor
        get() = when (syncStatus) {
            ClipboardSyncManager.SyncStatus.CONNECTED -> SystemStatusColor.SUCCESS
            ClipboardSyncManager.SyncStatus.CONNECTING -> SystemStatusColor.WARNING
            ClipboardSyncManager.SyncStatus.SYNCING -> SystemStatusColor.WARNING
            ClipboardSyncManager.SyncStatus.ERROR -> SystemStatusColor.ERROR
            ClipboardSyncManager.SyncStatus.DISCONNECTED -> SystemStatusColor.ERROR
        }
}

/**
 * Battery optimization and power management status
 */
data class BatteryOptimizationStatus(
    val isBatteryOptimizationDisabled: Boolean,
    val isIgnoringBatteryOptimizations: Boolean,
    val isInDozeWhitelist: Boolean,
    val canRequestIgnoreBatteryOptimizations: Boolean,
    val powerSaveMode: PowerSaveMode
) {
    enum class PowerSaveMode {
        NORMAL, POWER_SAVE, ULTRA_POWER_SAVE, UNKNOWN
    }
    
    val statusText: String
        get() = when {
            isIgnoringBatteryOptimizations -> "Battery Optimization Disabled"
            isBatteryOptimizationDisabled -> "System Battery Optimization Disabled"
            powerSaveMode == PowerSaveMode.POWER_SAVE -> "Power Save Mode Active"
            powerSaveMode == PowerSaveMode.ULTRA_POWER_SAVE -> "Ultra Power Save Mode Active"
            else -> "Battery Optimization Enabled"
        }
    
    val statusColor: SystemStatusColor
        get() = when {
            isIgnoringBatteryOptimizations -> SystemStatusColor.SUCCESS
            isBatteryOptimizationDisabled -> SystemStatusColor.SUCCESS
            powerSaveMode == PowerSaveMode.POWER_SAVE -> SystemStatusColor.WARNING
            powerSaveMode == PowerSaveMode.ULTRA_POWER_SAVE -> SystemStatusColor.ERROR
            else -> SystemStatusColor.WARNING
        }
}

/**
 * App permissions status
 */
data class PermissionStatus(
    val hasRequiredPermissions: Boolean,
    val missingPermissions: List<String>,
    val hasAccessibilityPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val hasBootPermission: Boolean
) {
    val statusText: String
        get() = when {
            hasRequiredPermissions -> "All Permissions Granted"
            missingPermissions.size == 1 -> "1 Permission Missing"
            else -> "${missingPermissions.size} Permissions Missing"
        }
    
    val statusColor: SystemStatusColor
        get() = when {
            hasRequiredPermissions -> SystemStatusColor.SUCCESS
            missingPermissions.size <= 2 -> SystemStatusColor.WARNING
            else -> SystemStatusColor.ERROR
        }
}

/**
 * Device information
 */
data class DeviceInfo(
    val androidVersion: String,
    val apiLevel: Int,
    val deviceModel: String,
    val manufacturer: String,
    val buildType: String,
    val kernelVersion: String?,
    val totalMemory: Long,
    val availableMemory: Long
) {
    val memoryUsagePercentage: Float
        get() = if (totalMemory > 0) {
            ((totalMemory - availableMemory).toFloat() / totalMemory.toFloat()) * 100f
        } else 0f
}

/**
 * System status color indicators
 */
enum class SystemStatusColor {
    SUCCESS,    // Green - Everything working well
    WARNING,    // Yellow/Orange - Working but with limitations
    ERROR,      // Red - Not working or critical issue
    NEUTRAL     // Gray - Unknown or not applicable
}