package com.siw.clipboardsync.monitor.model

/**
 * Configuration class for clipboard monitoring preferences and settings.
 * 
 * Simplified monitoring model (mutually exclusive):
 * - XPOSED_HOOKS: Full background sync (highest priority)
 * - SHIZUKU: Full background sync without root
 * - FOREGROUND_SYNC: Sync when app comes to foreground (fallback)
 */
data class MonitoringConfig(
    val enableAdvancedMonitoring: Boolean = true,
    val preferredMethod: MonitoringMethod? = null,
    val enableXposedHooks: Boolean = true,
    val enableShizuku: Boolean = true,
    val enableForegroundSync: Boolean = true,
    val autoFallback: Boolean = true,
    val fallbackRetryDelayMs: Long = 2000L,
    val maxFallbackAttempts: Int = 3,
    val enableNotifications: Boolean = true,
    val enableErrorRecovery: Boolean = true
) {
    
    /**
     * Get enabled monitoring methods based on configuration
     */
    fun getEnabledMethods(): List<MonitoringMethod> {
        val enabledMethods = mutableListOf<MonitoringMethod>()
        
        if (enableXposedHooks) {
            enabledMethods.add(MonitoringMethod.XPOSED_HOOKS)
        }
        if (enableShizuku) {
            enabledMethods.add(MonitoringMethod.SHIZUKU)
        }
        if (enableForegroundSync) {
            enabledMethods.add(MonitoringMethod.FOREGROUND_SYNC)
        }
        
        return enabledMethods
    }
    
    /**
     * Check if a specific monitoring method is enabled
     */
    fun isMethodEnabled(method: MonitoringMethod): Boolean {
        return when (method) {
            MonitoringMethod.XPOSED_HOOKS -> enableXposedHooks
            MonitoringMethod.SHIZUKU -> enableShizuku
            MonitoringMethod.FOREGROUND_SYNC -> enableForegroundSync
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
            enableXposedHooks = false,
            preferredMethod = MonitoringMethod.FOREGROUND_SYNC,
            maxFallbackAttempts = 2
        )
        
        /**
         * Aggressive configuration for rooted devices with Xposed
         */
        fun aggressive(): MonitoringConfig = MonitoringConfig(
            preferredMethod = MonitoringMethod.XPOSED_HOOKS,
            fallbackRetryDelayMs = 1000L,
            maxFallbackAttempts = 5
        )
        
        /**
         * Foreground sync only configuration (for non-root/non-shizuku devices)
         */
        fun foregroundOnly(): MonitoringConfig = MonitoringConfig(
            enableAdvancedMonitoring = false,
            enableXposedHooks = false,
            enableShizuku = false,
            enableForegroundSync = true
        )
    }
}