package com.siw.clipboardsync.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.monitor.model.MonitoringStrategy
import com.siw.clipboardsync.service.RootDetectionService
import com.siw.clipboardsync.utils.AccessibilityPermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating and evaluating clipboard monitoring strategies.
 * Selects the optimal monitoring method based on device capabilities,
 * permissions, and system constraints.
 */
@Singleton
class MonitoringStrategyFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootDetectionService: RootDetectionService
) {
    
    companion object {
        private const val TAG = "MonitoringStrategyFactory"
        
        // Priority values for different monitoring methods (higher = better)
        private const val SYSTEM_HOOKS_PRIORITY = 100
        private const val SHIZUKU_PRIORITY = 95  // High priority - provides background clipboard access without root on Android 10+
        private const val XPOSED_HOOKS_PRIORITY = 90
        private const val READ_LOGS_PRIORITY = 85
        private const val ACCESSIBILITY_SERVICE_PRIORITY = 70
        private const val FOREGROUND_SERVICE_PRIORITY = 50
        private const val POLLING_FALLBACK_PRIORITY = 10
        
        // SharedPreferences keys for state persistence
        private const val PREFS_NAME = "monitoring_strategy_prefs"
        private const val KEY_LAST_STRATEGY = "last_successful_strategy"
        private const val KEY_LAST_STRATEGY_TIME = "last_strategy_timestamp"
    }
    
    private val accessibilityPermissionManager = AccessibilityPermissionManager(context)
    
    /**
     * Creates all available monitoring strategies based on current device capabilities.
     * @return list of monitoring strategies sorted by priority (highest first)
     */
    suspend fun createAvailableStrategies(): List<MonitoringStrategy> {
        Log.d(TAG, "Evaluating available monitoring strategies")
        
        val strategies = mutableListOf<MonitoringStrategy>()
        val rootCapabilities = rootDetectionService.getRootCapabilities()
        
        // System-level hooks (highest priority for rooted devices)
        // NOTE: On Android 10+, even with root, system hooks may not work in background
        // due to clipboard access restrictions. We need to verify native hooks are actually available.
        Log.d(TAG, "Root capabilities check: hasSystemHooks=${rootCapabilities.hasSystemHooks}, hasRootAccess=${rootCapabilities.hasRootAccess}, rootMethod=${rootCapabilities.rootMethod}")
        
        // On Android 10+, system hooks are less reliable due to background restrictions
        // Only mark as available if we have actual native hook support (not just root)
        val systemHooksActuallyAvailable = rootCapabilities.hasSystemHooks && 
            rootCapabilities.hasNativeAccess && 
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q // System hooks work better on Android 9 and below
        
        if (systemHooksActuallyAvailable) {
            strategies.add(
                MonitoringStrategy(
                    method = MonitoringMethod.SYSTEM_HOOKS,
                    priority = SYSTEM_HOOKS_PRIORITY,
                    isAvailable = true,
                    capabilities = setOf(
                        MonitoringStrategy.Capability.BACKGROUND_ACCESS,
                        MonitoringStrategy.Capability.REAL_TIME_EVENTS,
                        MonitoringStrategy.Capability.LOW_LATENCY,
                        MonitoringStrategy.Capability.ALL_CONTENT_TYPES,
                        MonitoringStrategy.Capability.SYSTEM_LEVEL_ACCESS
                    )
                )
            )
            Log.d(TAG, "System hooks strategy available (Android < 10 with native access)")
        } else if (rootCapabilities.hasSystemHooks) {
            // On Android 10+, system hooks have limited background access
            // Add with lower priority so accessibility service is preferred
            strategies.add(
                MonitoringStrategy(
                    method = MonitoringMethod.SYSTEM_HOOKS,
                    priority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        FOREGROUND_SERVICE_PRIORITY - 5 // Lower than accessibility on Android 10+
                    } else {
                        SYSTEM_HOOKS_PRIORITY
                    },
                    isAvailable = rootCapabilities.hasNativeAccess,
                    capabilities = setOf(
                        MonitoringStrategy.Capability.REAL_TIME_EVENTS,
                        MonitoringStrategy.Capability.LOW_LATENCY,
                        MonitoringStrategy.Capability.ALL_CONTENT_TYPES,
                        MonitoringStrategy.Capability.SYSTEM_LEVEL_ACCESS
                    )
                )
            )
            Log.d(TAG, "System hooks strategy available with reduced priority (Android 10+ background restrictions)")
        } else {
            Log.d(TAG, "System hooks strategy NOT available - hasSystemHooks=false")
        }
        
        // Xposed/LSPosed hooks
        if (rootCapabilities.hasXposedFramework) {
            strategies.add(
                MonitoringStrategy(
                    method = MonitoringMethod.XPOSED_HOOKS,
                    priority = XPOSED_HOOKS_PRIORITY,
                    isAvailable = true,
                    capabilities = setOf(
                        MonitoringStrategy.Capability.BACKGROUND_ACCESS,
                        MonitoringStrategy.Capability.REAL_TIME_EVENTS,
                        MonitoringStrategy.Capability.LOW_LATENCY,
                        MonitoringStrategy.Capability.ALL_CONTENT_TYPES,
                        MonitoringStrategy.Capability.SYSTEM_LEVEL_ACCESS
                    )
                )
            )
            Log.d(TAG, "Xposed hooks strategy available")
        }
        
        // Shizuku - provides ADB-level permissions without root
        // Excellent for Android 10+ background clipboard access
        val shizukuAvailable = isShizukuAvailableAndPermitted()
        if (shizukuAvailable) {
            strategies.add(
                MonitoringStrategy(
                    method = MonitoringMethod.SHIZUKU,
                    priority = SHIZUKU_PRIORITY,
                    isAvailable = true,
                    capabilities = setOf(
                        MonitoringStrategy.Capability.BACKGROUND_ACCESS,
                        MonitoringStrategy.Capability.REAL_TIME_EVENTS,
                        MonitoringStrategy.Capability.ALL_CONTENT_TYPES
                    )
                )
            )
            Log.d(TAG, "Shizuku strategy available (running and permitted)")
        } else {
            // Check if Shizuku is installed but not running or not permitted
            val shizukuInstalled = isShizukuInstalled()
            val shizukuRunning = isShizukuRunning()
            Log.d(TAG, "Shizuku strategy NOT available - installed=$shizukuInstalled, running=$shizukuRunning, permitted=${shizukuRunning && isShizukuPermitted()}")
            
            // Add as unavailable option so users know it exists
            strategies.add(
                MonitoringStrategy(
                    method = MonitoringMethod.SHIZUKU,
                    priority = SHIZUKU_PRIORITY,
                    isAvailable = false,
                    capabilities = setOf(
                        MonitoringStrategy.Capability.BACKGROUND_ACCESS,
                        MonitoringStrategy.Capability.REAL_TIME_EVENTS,
                        MonitoringStrategy.Capability.ALL_CONTENT_TYPES
                    )
                )
            )
        }
        
        // READ_LOGS permission-based monitoring
        val readLogsAvailable = isReadLogsPermissionGranted()
        if (readLogsAvailable) {
            strategies.add(
                MonitoringStrategy(
                    method = MonitoringMethod.READ_LOGS,
                    priority = READ_LOGS_PRIORITY,
                    isAvailable = true,
                    capabilities = setOf(
                        MonitoringStrategy.Capability.BACKGROUND_ACCESS,
                        MonitoringStrategy.Capability.REAL_TIME_EVENTS,
                        MonitoringStrategy.Capability.ALL_CONTENT_TYPES
                    )
                )
            )
            Log.d(TAG, "READ_LOGS strategy available")
        } else {
            // Add as unavailable option so users know it exists
            strategies.add(
                MonitoringStrategy(
                    method = MonitoringMethod.READ_LOGS,
                    priority = READ_LOGS_PRIORITY,
                    isAvailable = false,
                    capabilities = setOf(
                        MonitoringStrategy.Capability.BACKGROUND_ACCESS,
                        MonitoringStrategy.Capability.REAL_TIME_EVENTS,
                        MonitoringStrategy.Capability.ALL_CONTENT_TYPES
                    )
                )
            )
            Log.d(TAG, "READ_LOGS strategy NOT available - permission not granted")
        }
        
        // Accessibility service (good for Android 10+ non-root devices)
        // On Android 10+, this is the MOST RELIABLE method for background clipboard monitoring
        val accessibilityAvailable = accessibilityPermissionManager.isAccessibilityServiceEnabled()
        val accessibilityPriority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Android 10+, accessibility service is the best option for background monitoring
            // Give it higher priority than system hooks which don't work well in background
            SYSTEM_HOOKS_PRIORITY + 5 // Higher than system hooks on Android 10+
        } else {
            ACCESSIBILITY_SERVICE_PRIORITY
        }
        strategies.add(
            MonitoringStrategy(
                method = MonitoringMethod.ACCESSIBILITY_SERVICE,
                priority = accessibilityPriority,
                isAvailable = accessibilityAvailable,
                capabilities = setOf(
                    MonitoringStrategy.Capability.BACKGROUND_ACCESS,
                    MonitoringStrategy.Capability.REAL_TIME_EVENTS,
                    MonitoringStrategy.Capability.ALL_CONTENT_TYPES
                )
            )
        )
        Log.d(TAG, "Accessibility service strategy available: $accessibilityAvailable, priority: $accessibilityPriority (Android ${Build.VERSION.SDK_INT})")
        
        // Foreground service (reliable but requires persistent notification)
        strategies.add(
            MonitoringStrategy(
                method = MonitoringMethod.FOREGROUND_SERVICE,
                priority = FOREGROUND_SERVICE_PRIORITY,
                isAvailable = true, // Always available
                capabilities = setOf(
                    MonitoringStrategy.Capability.BACKGROUND_ACCESS,
                    MonitoringStrategy.Capability.ALL_CONTENT_TYPES
                )
            )
        )
        Log.d(TAG, "Foreground service strategy available")
        
        // Polling fallback (always available as last resort)
        strategies.add(
            MonitoringStrategy(
                method = MonitoringMethod.POLLING_FALLBACK,
                priority = POLLING_FALLBACK_PRIORITY,
                isAvailable = true,
                capabilities = setOf(
                    MonitoringStrategy.Capability.ALL_CONTENT_TYPES
                )
            )
        )
        Log.d(TAG, "Polling fallback strategy available")
        
        // Sort by priority (highest first) and filter available strategies
        val sortedStrategies = strategies
            .sortedByDescending { it.priority }
            .also { sorted ->
                Log.d(TAG, "Available strategies in priority order:")
                sorted.forEach { strategy ->
                    Log.d(TAG, "  ${strategy.method.name}: priority=${strategy.priority}, available=${strategy.isAvailable}")
                }
            }
        
        return sortedStrategies
    }
    
    /**
     * Selects the optimal monitoring strategy based on device capabilities and requirements.
     * @param requiredCapabilities optional set of required capabilities
     * @return the best available monitoring strategy, or null if none meet requirements
     */
    suspend fun selectOptimalStrategy(
        requiredCapabilities: Set<MonitoringStrategy.Capability> = emptySet()
    ): MonitoringStrategy? {
        val availableStrategies = createAvailableStrategies()
        
        // Filter strategies that meet requirements and are available
        val suitableStrategies = availableStrategies.filter { strategy ->
            strategy.isAvailable && requiredCapabilities.all { capability ->
                strategy.hasCapability(capability)
            }
        }
        
        val selectedStrategy = suitableStrategies.firstOrNull()
        
        if (selectedStrategy != null) {
            Log.i(TAG, "Selected optimal strategy: ${selectedStrategy.method.name} (priority: ${selectedStrategy.priority})")
        } else {
            Log.w(TAG, "No suitable strategy found for requirements: $requiredCapabilities")
        }
        
        return selectedStrategy
    }
    
    /**
     * Creates a fallback chain of monitoring strategies.
     * @return ordered list of strategies to try in sequence
     */
    suspend fun createFallbackChain(): List<MonitoringStrategy> {
        val allStrategies = createAvailableStrategies()
        
        // Return all available strategies in priority order
        val fallbackChain = allStrategies.filter { it.isAvailable }
        
        Log.d(TAG, "Created fallback chain with ${fallbackChain.size} strategies:")
        fallbackChain.forEachIndexed { index, strategy ->
            Log.d(TAG, "  ${index + 1}. ${strategy.method.name}")
        }
        
        return fallbackChain
    }
    
    /**
     * Executes the fallback chain with exponential backoff retry logic.
     * Tries each strategy in order, with retries on failure.
     * 
     * @param onStrategySelected callback when a strategy is selected and ready
     * @param onStrategyFailed callback when a strategy fails (before trying next)
     * @param maxRetries maximum retries per strategy (default 3)
     * @param initialDelayMs initial delay between retries in milliseconds (default 1000)
     * @return the successfully activated strategy, or null if all failed
     */
    suspend fun executeFallbackChain(
        onStrategySelected: suspend (MonitoringStrategy) -> Boolean,
        onStrategyFailed: suspend (MonitoringStrategy, Exception, Int) -> Unit = { _, _, _ -> },
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000
    ): MonitoringStrategy? {
        val fallbackChain = createFallbackChain()
        
        for (strategy in fallbackChain) {
            var lastException: Exception? = null
            
            for (attempt in 1..maxRetries) {
                try {
                    Log.d(TAG, "Attempting strategy ${strategy.method.name} (attempt $attempt/$maxRetries)")
                    
                    val success = onStrategySelected(strategy)
                    if (success) {
                        Log.i(TAG, "Strategy ${strategy.method.name} activated successfully on attempt $attempt")
                        saveLastSuccessfulStrategy(strategy.method)
                        return strategy
                    } else {
                        throw IllegalStateException("Strategy activation returned false")
                    }
                    
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Strategy ${strategy.method.name} failed on attempt $attempt: ${e.message}")
                    
                    onStrategyFailed(strategy, e, attempt)
                    
                    if (attempt < maxRetries) {
                        // Exponential backoff: delay = initialDelay * 2^(attempt-1)
                        val delayMs = initialDelayMs * (1 shl (attempt - 1))
                        Log.d(TAG, "Waiting ${delayMs}ms before retry...")
                        kotlinx.coroutines.delay(delayMs)
                    }
                }
            }
            
            Log.w(TAG, "Strategy ${strategy.method.name} exhausted all retries, moving to next strategy")
        }
        
        Log.e(TAG, "All strategies in fallback chain failed")
        return null
    }
    
    /**
     * Saves the last successfully used strategy for state persistence.
     */
    private fun saveLastSuccessfulStrategy(method: MonitoringMethod) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LAST_STRATEGY, method.name)
                .putLong(KEY_LAST_STRATEGY_TIME, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Saved last successful strategy: ${method.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save last successful strategy", e)
        }
    }
    
    /**
     * Gets the last successfully used strategy from persistence.
     * @return the last successful method, or null if none saved
     */
    fun getLastSuccessfulStrategy(): MonitoringMethod? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val methodName = prefs.getString(KEY_LAST_STRATEGY, null)
            methodName?.let { MonitoringMethod.valueOf(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last successful strategy", e)
            null
        }
    }
    
    /**
     * Creates a prioritized fallback chain that starts with the last successful strategy.
     * This improves startup time by trying the known-working method first.
     * 
     * @return ordered list of strategies with last successful strategy first (if still available)
     */
    suspend fun createOptimizedFallbackChain(): List<MonitoringStrategy> {
        val standardChain = createFallbackChain()
        val lastSuccessful = getLastSuccessfulStrategy()
        
        if (lastSuccessful == null) {
            return standardChain
        }
        
        // Find the last successful strategy in the chain
        val lastSuccessfulStrategy = standardChain.find { it.method == lastSuccessful }
        
        if (lastSuccessfulStrategy == null || !lastSuccessfulStrategy.isAvailable) {
            Log.d(TAG, "Last successful strategy $lastSuccessful is no longer available")
            return standardChain
        }
        
        // Move the last successful strategy to the front
        val optimizedChain = mutableListOf(lastSuccessfulStrategy)
        optimizedChain.addAll(standardChain.filter { it.method != lastSuccessful })
        
        Log.d(TAG, "Created optimized fallback chain starting with last successful: ${lastSuccessful.name}")
        return optimizedChain
    }
    
    /**
     * Evaluates if a specific monitoring method is available on this device.
     * @param method the monitoring method to check
     * @return true if the method is available, false otherwise
     */
    suspend fun isMethodAvailable(method: MonitoringMethod): Boolean {
        return when (method) {
            MonitoringMethod.SYSTEM_HOOKS -> {
                val capabilities = rootDetectionService.getRootCapabilities()
                capabilities.hasSystemHooks
            }
            MonitoringMethod.XPOSED_HOOKS -> {
                val capabilities = rootDetectionService.getRootCapabilities()
                capabilities.hasXposedFramework
            }
            MonitoringMethod.READ_LOGS -> {
                isReadLogsPermissionGranted()
            }
            MonitoringMethod.SHIZUKU -> {
                try {
                    rikka.shizuku.Shizuku.pingBinder()
                } catch (e: Exception) {
                    false
                }
            }
            MonitoringMethod.ACCESSIBILITY_SERVICE -> {
                accessibilityPermissionManager.isAccessibilityServiceEnabled()
            }
            MonitoringMethod.FOREGROUND_SERVICE -> true // Always available
            MonitoringMethod.POLLING_FALLBACK -> true // Always available
        }
    }
    
    /**
     * Checks if Shizuku is installed on the device.
     */
    private fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * Checks if Shizuku service is running.
     */
    private fun isShizukuRunning(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Checks if we have Shizuku permission.
     */
    private fun isShizukuPermitted(): Boolean {
        return try {
            rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Checks if Shizuku is available and we have permission to use it.
     */
    private fun isShizukuAvailableAndPermitted(): Boolean {
        return try {
            val running = rikka.shizuku.Shizuku.pingBinder()
            if (!running) {
                Log.d(TAG, "Shizuku is not running")
                return false
            }
            val permitted = rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Shizuku running=$running, permitted=$permitted")
            permitted
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Shizuku availability: ${e.message}")
            false
        }
    }
    
    /**
     * Checks if READ_LOGS permission is granted.
     */
    private fun isReadLogsPermissionGranted(): Boolean {
        return try {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_LOGS
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Error checking READ_LOGS permission", e)
            false
        }
    }
    
    /**
     * Gets detailed information about device capabilities for monitoring.
     * @return map of capability information
     */
    suspend fun getDeviceCapabilities(): Map<String, Any> {
        val rootCapabilities = rootDetectionService.getRootCapabilities()
        
        return mapOf(
            "android_version" to Build.VERSION.SDK_INT,
            "android_release" to Build.VERSION.RELEASE,
            "is_rooted" to rootDetectionService.isRooted(),
            "has_system_hooks" to rootCapabilities.hasSystemHooks,
            "has_xposed_framework" to rootCapabilities.hasXposedFramework,
            "has_native_access" to rootCapabilities.hasNativeAccess,
            "root_method" to rootCapabilities.rootMethod.name,
            "has_read_logs_permission" to isReadLogsPermissionGranted(),
            "accessibility_service_enabled" to accessibilityPermissionManager.isAccessibilityServiceEnabled(),
            "accessibility_service_running" to accessibilityPermissionManager.isServiceActiveAndMonitoring(),
            "device_manufacturer" to Build.MANUFACTURER,
            "device_model" to Build.MODEL
        )
    }
}