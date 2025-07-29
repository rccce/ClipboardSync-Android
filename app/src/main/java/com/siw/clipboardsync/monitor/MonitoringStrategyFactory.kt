package com.siw.clipboardsync.monitor

import android.content.Context
import android.os.Build
import android.util.Log
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
        private const val XPOSED_HOOKS_PRIORITY = 90
        private const val ACCESSIBILITY_SERVICE_PRIORITY = 70
        private const val FOREGROUND_SERVICE_PRIORITY = 50
        private const val POLLING_FALLBACK_PRIORITY = 10
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
        Log.d(TAG, "Root capabilities check: hasSystemHooks=${rootCapabilities.hasSystemHooks}, hasRootAccess=${rootCapabilities.hasRootAccess}, rootMethod=${rootCapabilities.rootMethod}")
        if (rootCapabilities.hasSystemHooks) {
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
            Log.d(TAG, "System hooks strategy available")
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
        
        // Accessibility service (good for Android 10+ non-root devices)
        val accessibilityAvailable = accessibilityPermissionManager.isAccessibilityServiceEnabled()
        strategies.add(
            MonitoringStrategy(
                method = MonitoringMethod.ACCESSIBILITY_SERVICE,
                priority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ACCESSIBILITY_SERVICE_PRIORITY + 10 // Higher priority on Android 10+
                } else {
                    ACCESSIBILITY_SERVICE_PRIORITY
                },
                isAvailable = accessibilityAvailable,
                capabilities = setOf(
                    MonitoringStrategy.Capability.BACKGROUND_ACCESS,
                    MonitoringStrategy.Capability.REAL_TIME_EVENTS,
                    MonitoringStrategy.Capability.ALL_CONTENT_TYPES
                )
            )
        )
        Log.d(TAG, "Accessibility service strategy available: $accessibilityAvailable")
        
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
            MonitoringMethod.ACCESSIBILITY_SERVICE -> {
                accessibilityPermissionManager.isAccessibilityServiceEnabled()
            }
            MonitoringMethod.FOREGROUND_SERVICE -> true // Always available
            MonitoringMethod.POLLING_FALLBACK -> true // Always available
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
            "accessibility_service_enabled" to accessibilityPermissionManager.isAccessibilityServiceEnabled(),
            "accessibility_service_running" to accessibilityPermissionManager.isServiceActiveAndMonitoring(),
            "device_manufacturer" to Build.MANUFACTURER,
            "device_model" to Build.MODEL
        )
    }
}