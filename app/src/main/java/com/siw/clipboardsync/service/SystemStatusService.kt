package com.siw.clipboardsync.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.siw.clipboardsync.data.model.*
import com.siw.clipboardsync.manager.ClipboardSyncManager
import com.siw.clipboardsync.manager.ServiceManager
import com.siw.clipboardsync.monitor.ClipboardMonitorManager
// import com.siw.clipboardsync.monitor.IMEClipboardManager
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for collecting comprehensive system status information
 */
@Singleton
class SystemStatusService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootDetectionService: RootDetectionService,
    private val serviceManager: ServiceManager,
    private val clipboardSyncManager: ClipboardSyncManager,
    private val monitorManager: ClipboardMonitorManager,
    // private val imeManager: IMEClipboardManager
) {
    
    companion object {
        private const val TAG = "SystemStatusService"
    }
    
    /**
     * Collect comprehensive system status
     */
    suspend fun getSystemStatus(): SystemStatus = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Collecting system status...")
            
            val rootStatus = getRootStatus()
            val monitoringStatus = getMonitoringStatus()
            val connectionStatus = getConnectionStatus()
            val batteryStatus = getBatteryOptimizationStatus()
            val permissionStatus = getPermissionStatus()
            val deviceInfo = getDeviceInfo()
            
            SystemStatus(
                rootStatus = rootStatus,
                monitoringStatus = monitoringStatus,
                connectionStatus = connectionStatus,
                batteryOptimizationStatus = batteryStatus,
                permissionStatus = permissionStatus,
                deviceInfo = deviceInfo
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting system status", e)
            // Return default status on error
            SystemStatus(
                rootStatus = RootStatus(false, com.siw.clipboardsync.monitor.model.RootCapabilities.RootMethod.NONE, false, false, false, false, null, false),
                monitoringStatus = MonitoringStatus(false, null, false, emptyList(), null),
                connectionStatus = ConnectionStatus(ClipboardSyncManager.SyncStatus.DISCONNECTED, false, null, null),
                batteryOptimizationStatus = BatteryOptimizationStatus(false, false, false, false, BatteryOptimizationStatus.PowerSaveMode.UNKNOWN),
                permissionStatus = PermissionStatus(false, emptyList(), false, false, false),
                deviceInfo = getDeviceInfo() // Device info should always work
            )
        }
    }
    
    /**
     * Get root access status
     */
    private suspend fun getRootStatus(): RootStatus {
        return try {
            val capabilities = rootDetectionService.getRootCapabilities()
            val isRooted = rootDetectionService.isRooted()
            
            RootStatus(
                isRooted = isRooted,
                rootMethod = capabilities.rootMethod,
                isRootAccessible = capabilities.isRootAccessible,
                hasSystemHooks = capabilities.hasSystemHooks,
                hasXposedFramework = capabilities.hasXposedFramework,
                hasNativeAccess = capabilities.hasNativeAccess,
                suBinaryPath = capabilities.suBinaryPath,
                canUseSystemLevelMonitoring = capabilities.canUseSystemLevelMonitoring
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting root status", e)
            RootStatus(false, com.siw.clipboardsync.monitor.model.RootCapabilities.RootMethod.NONE, false, false, false, false, null, false)
        }
    }
    
    /**
     * Get clipboard monitoring status
     */
    private suspend fun getMonitoringStatus(): MonitoringStatus {
        return try {
            val isMonitoring = monitorManager.isMonitoring.value
            val currentMethod = monitorManager.currentMethod.value
            val monitoringStatus = monitorManager.getMonitoringStatus()
            val imeStatus = getIMEStatus()
            
            MonitoringStatus(
                isMonitoring = isMonitoring,
                currentMethod = currentMethod,
                isAdvancedMonitoring = isMonitoring,
                availableMethods = monitoringStatus.availableStrategies.map { it.method },
                imeStatus = imeStatus
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting monitoring status", e)
            MonitoringStatus(false, null, false, emptyList(), null)
        }
    }
    
    /**
     * Get IME service status
     */
    private fun getIMEStatus(): IMEStatus {
        return try {
            // TODO: Implement IME status when IMEClipboardManager is available
            IMEStatus(
                imeEnabled = false,
                imeSelected = false,
                imeRunning = false,
                canMonitorClipboard = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IME status", e)
            IMEStatus(false, false, false, false)
        }
    }
    
    /**
     * Get WebSocket connection status
     */
    private suspend fun getConnectionStatus(): ConnectionStatus {
        return try {
            val syncStatus = clipboardSyncManager.syncStatus.value
            val isServiceRunning = serviceManager.isClipboardServiceRunning()
            
            ConnectionStatus(
                syncStatus = syncStatus,
                isServiceRunning = isServiceRunning,
                lastSyncTime = null, // TODO: Implement last sync time tracking
                connectionLatency = null // TODO: Implement latency measurement
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connection status", e)
            ConnectionStatus(ClipboardSyncManager.SyncStatus.DISCONNECTED, false, null, null)
        }
    }
    
    /**
     * Get battery optimization status
     */
    private suspend fun getBatteryOptimizationStatus(): BatteryOptimizationStatus = withContext(Dispatchers.Main) {
        return@withContext try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            
            val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true // No battery optimization on older versions
            }
            
            val canRequestIgnoreBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = android.net.Uri.parse("package:${context.packageName}")
                    intent.resolveActivity(context.packageManager) != null
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
            
            val powerSaveMode = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> {
                    when {
                        powerManager.isPowerSaveMode -> BatteryOptimizationStatus.PowerSaveMode.POWER_SAVE
                        else -> BatteryOptimizationStatus.PowerSaveMode.NORMAL
                    }
                }
                else -> BatteryOptimizationStatus.PowerSaveMode.UNKNOWN
            }
            
            BatteryOptimizationStatus(
                isBatteryOptimizationDisabled = false, // TODO: Check system-wide battery optimization
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                isInDozeWhitelist = isIgnoringBatteryOptimizations, // Same as ignoring battery optimizations
                canRequestIgnoreBatteryOptimizations = canRequestIgnoreBatteryOptimizations,
                powerSaveMode = powerSaveMode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting battery optimization status", e)
            BatteryOptimizationStatus(false, false, false, false, BatteryOptimizationStatus.PowerSaveMode.UNKNOWN)
        }
    }
    
    /**
     * Get app permissions status
     */
    private suspend fun getPermissionStatus(): PermissionStatus {
        return try {
            val hasRequiredPermissions = serviceManager.hasRequiredPermissions()
            val missingPermissions = serviceManager.getMissingPermissions()
            
            val hasAccessibilityPermission = isAccessibilityServiceEnabled()
            val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true // No notification permission required on older versions
            }
            val hasBootPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_BOOT_COMPLETED) == PackageManager.PERMISSION_GRANTED
            
            PermissionStatus(
                hasRequiredPermissions = hasRequiredPermissions,
                missingPermissions = missingPermissions,
                hasAccessibilityPermission = hasAccessibilityPermission,
                hasNotificationPermission = hasNotificationPermission,
                hasBootPermission = hasBootPermission
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting permission status", e)
            PermissionStatus(false, emptyList(), false, false, false)
        }
    }
    
    /**
     * Check if accessibility service is enabled
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0
            )
            
            if (accessibilityEnabled == 1) {
                val services = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                
                val serviceName = "${context.packageName}/.service.ClipboardAccessibilityService"
                services.contains(serviceName)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service", e)
            false
        }
    }
    
    /**
     * Get device information
     */
    private suspend fun getDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        return@withContext try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            val kernelVersion = try {
                BufferedReader(FileReader("/proc/version")).use { reader ->
                    reader.readLine()?.split(" ")?.take(3)?.joinToString(" ")
                }
            } catch (e: Exception) {
                null
            }
            
            DeviceInfo(
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                deviceModel = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                buildType = Build.TYPE,
                kernelVersion = kernelVersion,
                totalMemory = memoryInfo.totalMem,
                availableMemory = memoryInfo.availMem
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device info", e)
            DeviceInfo(
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                deviceModel = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                buildType = Build.TYPE,
                kernelVersion = null,
                totalMemory = 0L,
                availableMemory = 0L
            )
        }
    }
    
    /**
     * Request to ignore battery optimizations
     */
    suspend fun requestIgnoreBatteryOptimizations(): Boolean = withContext(Dispatchers.Main) {
        return@withContext try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                
                if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    
                    context.startActivity(intent)
                    true
                } else {
                    true // Already ignoring battery optimizations
                }
            } else {
                true // No battery optimization on older versions
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting ignore battery optimizations", e)
            false
        }
    }
    
    /**
     * Open accessibility settings
     */
    fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening accessibility settings", e)
        }
    }
}