package com.siw.clipboardsync.monitor.migration

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.siw.clipboardsync.monitor.ClipboardMonitorManager
import com.siw.clipboardsync.monitor.model.MonitoringConfig
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages migration from legacy polling-based monitoring to advanced monitoring system.
 */
@Singleton
class MonitoringMigrationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clipboardMonitorManager: ClipboardMonitorManager
) {
    
    companion object {
        private const val TAG = "MonitoringMigration"
        private const val PREFS_NAME = "clipboard_monitoring_migration"
        private const val KEY_MIGRATION_COMPLETED = "migration_completed"
        private const val KEY_MIGRATION_VERSION = "migration_version"
        private const val KEY_LEGACY_POLLING_ENABLED = "legacy_polling_enabled"
        private const val KEY_PREFERRED_METHOD = "preferred_method"
        private const val KEY_MIGRATION_TIMESTAMP = "migration_timestamp"
        
        private const val CURRENT_MIGRATION_VERSION = 1
        private const val MIGRATION_RETRY_DELAY_MS = 3000L
        private const val MAX_MIGRATION_ATTEMPTS = 3
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Check if migration from legacy polling has been completed
     */
    fun isMigrationCompleted(): Boolean {
        return prefs.getBoolean(KEY_MIGRATION_COMPLETED, false) &&
                prefs.getInt(KEY_MIGRATION_VERSION, 0) >= CURRENT_MIGRATION_VERSION
    }
    
    /**
     * Check if legacy polling was previously enabled
     */
    fun wasLegacyPollingEnabled(): Boolean {
        return prefs.getBoolean(KEY_LEGACY_POLLING_ENABLED, true) // Default to true for existing installations
    }
    
    /**
     * Perform migration from legacy polling to advanced monitoring
     */
    suspend fun performMigration(): MigrationResult {
        if (isMigrationCompleted()) {
            Log.d(TAG, "Migration already completed")
            return MigrationResult.AlreadyCompleted
        }
        
        Log.i(TAG, "Starting migration from legacy polling to advanced monitoring")
        
        var attempts = 0
        var lastError: Exception? = null
        
        while (attempts < MAX_MIGRATION_ATTEMPTS) {
            attempts++
            
            try {
                Log.d(TAG, "Migration attempt $attempts of $MAX_MIGRATION_ATTEMPTS")
                
                // Step 1: Detect current monitoring state
                val currentState = detectCurrentMonitoringState()
                Log.d(TAG, "Detected current monitoring state: $currentState")
                
                // Step 2: Prepare advanced monitoring
                val preparationResult = prepareAdvancedMonitoring()
                if (!preparationResult) {
                    throw Exception("Failed to prepare advanced monitoring system")
                }
                
                // Step 3: Migrate configuration
                val config = migrateConfiguration(currentState)
                Log.d(TAG, "Migrated configuration: enabled methods = ${config.getEnabledMethods().size}")
                
                // Step 4: Test advanced monitoring
                val testResult = testAdvancedMonitoring()
                if (!testResult) {
                    Log.w(TAG, "Advanced monitoring test failed, keeping legacy polling as fallback")
                    markMigrationCompleted(preferLegacyPolling = true)
                    return MigrationResult.CompletedWithFallback
                }
                
                // Step 5: Complete migration
                markMigrationCompleted(preferLegacyPolling = false)
                
                Log.i(TAG, "Migration completed successfully after $attempts attempts")
                return MigrationResult.Success
                
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Migration attempt $attempts failed: ${e.message}")
                
                if (attempts < MAX_MIGRATION_ATTEMPTS) {
                    Log.d(TAG, "Retrying migration in ${MIGRATION_RETRY_DELAY_MS}ms")
                    delay(MIGRATION_RETRY_DELAY_MS)
                }
            }
        }
        
        Log.e(TAG, "Migration failed after $MAX_MIGRATION_ATTEMPTS attempts", lastError)
        
        // Fallback to legacy polling
        markMigrationCompleted(preferLegacyPolling = true)
        return MigrationResult.FailedWithFallback(lastError)
    }
    
    /**
     * Detect the current monitoring state before migration
     */
    private fun detectCurrentMonitoringState(): LegacyMonitoringState {
        // Check if any advanced monitoring was previously attempted
        val hasAdvancedMonitoring = clipboardMonitorManager.getMonitoringStatus().availableStrategies.isNotEmpty()
        
        // Check legacy polling preferences (this would come from existing SharedPreferences)
        val legacyPollingEnabled = prefs.getBoolean("clipboard_monitoring_enabled", true)
        val pollingInterval = prefs.getLong("polling_interval", 1000L)
        
        return LegacyMonitoringState(
            legacyPollingEnabled = legacyPollingEnabled,
            pollingInterval = pollingInterval,
            hasAdvancedMonitoring = hasAdvancedMonitoring
        )
    }
    
    /**
     * Prepare the advanced monitoring system for migration
     */
    private suspend fun prepareAdvancedMonitoring(): Boolean {
        return try {
            clipboardMonitorManager.initialize()
            Log.d(TAG, "Advanced monitoring system prepared successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare advanced monitoring system", e)
            false
        }
    }
    
    /**
     * Migrate configuration from legacy to advanced monitoring
     */
    private fun migrateConfiguration(currentState: LegacyMonitoringState): MonitoringConfig {
        val configBuilder = MonitoringConfig.default()
        
        // If legacy polling was disabled, be more conservative
        if (!currentState.legacyPollingEnabled) {
            return MonitoringConfig.conservative()
        }
        
        // If polling interval was very frequent, user prefers responsive monitoring
        if (currentState.pollingInterval <= 500L) {
            return MonitoringConfig.aggressive()
        }
        
        // Store migrated preferences
        prefs.edit()
            .putBoolean(KEY_LEGACY_POLLING_ENABLED, currentState.legacyPollingEnabled)
            .putLong("legacy_polling_interval", currentState.pollingInterval)
            .apply()
        
        return configBuilder
    }
    
    /**
     * Test advanced monitoring functionality
     */
    private suspend fun testAdvancedMonitoring(): Boolean {
        return try {
            val status = clipboardMonitorManager.getMonitoringStatus()
            val hasAvailableStrategies = status.availableStrategies.isNotEmpty()
            
            if (!hasAvailableStrategies) {
                Log.w(TAG, "No advanced monitoring strategies available")
                return false
            }
            
            Log.d(TAG, "Advanced monitoring test passed: ${status.availableStrategies.size} strategies available")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Advanced monitoring test failed", e)
            false
        }
    }
    
    /**
     * Mark migration as completed
     */
    private fun markMigrationCompleted(preferLegacyPolling: Boolean) {
        prefs.edit()
            .putBoolean(KEY_MIGRATION_COMPLETED, true)
            .putInt(KEY_MIGRATION_VERSION, CURRENT_MIGRATION_VERSION)
            .putBoolean("prefer_legacy_polling", preferLegacyPolling)
            .putLong(KEY_MIGRATION_TIMESTAMP, System.currentTimeMillis())
            .apply()
        
        Log.i(TAG, "Migration marked as completed (prefer legacy: $preferLegacyPolling)")
    }
    
    /**
     * Get the preferred monitoring method after migration
     */
    fun getPreferredMethodAfterMigration(): MonitoringMethod? {
        if (!isMigrationCompleted()) {
            return null
        }
        
        val preferLegacy = prefs.getBoolean("prefer_legacy_polling", false)
        if (preferLegacy) {
            return MonitoringMethod.POLLING_FALLBACK
        }
        
        // Return the stored preferred method or null for auto-selection
        val methodName = prefs.getString(KEY_PREFERRED_METHOD, null)
        return methodName?.let { 
            try {
                MonitoringMethod.valueOf(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
    
    /**
     * Check if legacy polling should be preferred after migration
     */
    fun shouldPreferLegacyPolling(): Boolean {
        return prefs.getBoolean("prefer_legacy_polling", false)
    }
    
    /**
     * Reset migration state (for testing or troubleshooting)
     */
    fun resetMigration() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Migration state reset")
    }
    
    /**
     * Data class representing the legacy monitoring state
     */
    private data class LegacyMonitoringState(
        val legacyPollingEnabled: Boolean,
        val pollingInterval: Long,
        val hasAdvancedMonitoring: Boolean
    )
    
    /**
     * Migration result sealed class
     */
    sealed class MigrationResult {
        object Success : MigrationResult()
        object AlreadyCompleted : MigrationResult()
        object CompletedWithFallback : MigrationResult()
        data class FailedWithFallback(val error: Exception?) : MigrationResult()
    }
}