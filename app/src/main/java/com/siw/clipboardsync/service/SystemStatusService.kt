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
import com.siw.clipboardsync.monitor.ShizukuClipboardMonitor
// import com.siw.clipboardsync.monitor.IMEClipboardManager
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
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
                monitoringStatus = MonitoringStatus(false, null, false, emptyList()),
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
            MonitoringStatus(
                isMonitoring = isMonitoring,
                currentMethod = currentMethod,
                isAdvancedMonitoring = isMonitoring,
                availableMethods = monitoringStatus.availableStrategies.map { it.method }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting monitoring status", e)
            MonitoringStatus(false, null, false, emptyList())
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
            
            val shizukuStatus = getShizukuStatus()
            
            PermissionStatus(
                hasRequiredPermissions = hasRequiredPermissions,
                missingPermissions = missingPermissions,
                hasAccessibilityPermission = hasAccessibilityPermission,
                hasNotificationPermission = hasNotificationPermission,
                hasBootPermission = hasBootPermission,
                shizukuStatus = shizukuStatus
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting permission status", e)
            PermissionStatus(false, emptyList(), false, false, false)
        }
    }
    
    /**
     * Get Shizuku status
     */
    private fun getShizukuStatus(): ShizukuStatus {
        return try {
            // First check if Shizuku binder is available (works even without Shizuku Manager app)
            val isRunning = try {
                Shizuku.pingBinder()
            } catch (e: Exception) {
                false
            }
            
            // Check if Shizuku Manager app is installed
            val isInstalled = isShizukuInstalled()
            
            val hasPermission = if (isRunning) {
                try {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) {
                    false
                }
            } else false
            
            val version = if (isRunning) {
                try {
                    Shizuku.getVersion()
                } catch (e: Exception) {
                    -1
                }
            } else -1
            
            ShizukuStatus(
                isInstalled = isInstalled || isRunning,  // Consider "installed" if running via ADB
                isRunning = isRunning,
                hasPermission = hasPermission,
                version = version
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Shizuku status", e)
            ShizukuStatus()
        }
    }
    
    /**
     * Check if Shizuku app is installed
     */
    private fun isShizukuInstalled(): Boolean {
        // Check for Shizuku Manager app (main package)
        val shizukuPackages = listOf(
            "moe.shizuku.privileged.api",  // Shizuku API (may be present when service is running)
            "moe.shizuku.manager"           // Shizuku Manager app
        )
        
        for (packageName in shizukuPackages) {
            try {
                context.packageManager.getPackageInfo(packageName, 0)
                return true
            } catch (e: PackageManager.NameNotFoundException) {
                // Try next package
            }
        }
        
        // Also check if Shizuku binder is available (service might be running via ADB)
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Request Shizuku permission
     */
    fun requestShizukuPermission(): Boolean {
        return try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(ShizukuClipboardMonitor.SHIZUKU_PERMISSION_REQUEST_CODE)
                    true
                } else {
                    true // Already has permission
                }
            } else {
                Log.w(TAG, "Shizuku is not running")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting Shizuku permission", e)
            false
        }
    }
    
    /**
     * Open Shizuku app or Play Store if not installed
     */
    fun openShizukuApp() {
        try {
            if (isShizukuInstalled()) {
                // Open Shizuku app
                val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
            } else {
                // Open Play Store to install Shizuku
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("market://details?id=moe.shizuku.privileged.api")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Play Store not available, open browser
                    val browserIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(browserIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Shizuku app", e)
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