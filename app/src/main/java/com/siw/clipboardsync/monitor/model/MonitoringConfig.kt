package com.siw.clipboardsync.monitor.model

/**
 * Configuration class for clipboard monitoring preferences and settings.
 */
data class MonitoringConfig(
    val enableAdvancedMonitoring: Boolean = true,
    val preferredMethod: MonitoringMethod? = null,
    val enableSystemHooks: Boolean = true,
    val enableXposedHooks: Boolean = true,
    val enableAccessibilityService: Boolean = true,
    val enableForegroundService: Boolean = true,
    val enablePollingFallback: Boolean = true,
    val autoFallback: Boolean = true,
    val fallbackRetryDelayMs: Long = 2000L,
    val maxFallbackAttempts: Int = 3,
    val enableNotifications: Boolean = true,
    val enableErrorRecovery: Boolean = true,
    val migrationFromLegacyPolling: Boolean = true
) {
    
    /**
     * Get enabled monitoring methods based on configuration
     */
    fun getEnabledMethods(): List<MonitoringMethod> {
        val enabledMethods = mutableListOf<MonitoringMethod>()
        
        if (enableSystemHooks) {
            enabledMethods.add(MonitoringMethod.SYSTEM_HOOKS)
        }
        if (enableXposedHooks) {
            enabledMethods.add(MonitoringMethod.XPOSED_HOOKS)
        }
        if (enableAccessibilityService) {
            enabledMethods.add(MonitoringMethod.ACCESSIBILITY_SERVICE)
        }
        if (enableForegroundService) {
            enabledMethods.add(MonitoringMethod.FOREGROUND_SERVICE)
        }
        if (enablePollingFallback) {
            enabledMethods.add(MonitoringMethod.POLLING_FALLBACK)
        }
        
        return enabledMethods
    }
    
    /**
     * Check if a specific monitoring method is enabled
     */
    fun isMethodEnabled(method: MonitoringMethod): Boolean {
        return when (method) {
            MonitoringMethod.SYSTEM_HOOKS -> enableSystemHooks
            MonitoringMethod.XPOSED_HOOKS -> enableXposedHooks
            MonitoringMethod.ACCESSIBILITY_SERVICE -> enableAccessibilityService
            MonitoringMethod.FOREGROUND_SERVICE -> enableForegroundService
            MonitoringMethod.POLLING_FALLBACK -> enablePollingFallback
        }
    }
    
    companion object {
        /**
         * Default configuration for most devices
         */
        fun default(): MonitoringConfig = MonitoringConfig()
        
        /**
         * Conservative configuration for devices with limited capabilities
         */
        fun conservative(): MonitoringConfig = MonitoringConfig(
            enableSystemHooks = false,
            enableXposedHooks = false,
            preferredMethod = MonitoringMethod.ACCESSIBILITY_SERVICE,
            maxFallbackAttempts = 2
        )
        
        /**
         * Aggressive configuration for rooted devices
         */
        fun aggressive(): MonitoringConfig = MonitoringConfig(
            preferredMethod = MonitoringMethod.SYSTEM_HOOKS,
            fallbackRetryDelayMs = 1000L,
            maxFallbackAttempts = 5
        )
        
        /**
         * Legacy polling only configuration
         */
        fun legacyOnly(): MonitoringConfig = MonitoringConfig(
            enableAdvancedMonitoring = false,
            enableSystemHooks = false,
            enableXposedHooks = false,
            enableAccessibilityService = false,
            enableForegroundService = false,
            enablePollingFallback = true,
            migrationFromLegacyPolling = false
        )
    }
}