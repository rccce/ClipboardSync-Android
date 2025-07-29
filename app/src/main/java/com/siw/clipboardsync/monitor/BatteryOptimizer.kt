package com.siw.clipboardsync.monitor

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
/**
 * Optimizes polling intervals based on battery state and power management settings
 * to preserve battery life while maintaining reasonable responsiveness.
 */
class BatteryOptimizer(
    private val context: Context
) {
    
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    // Battery level thresholds for optimization
    private val criticalBatteryLevel = 15
    private val lowBatteryLevel = 30
    private val mediumBatteryLevel = 50
    
    /**
     * Adjusts polling interval based on current battery state
     */
    fun adjustIntervalForBattery(baseInterval: Long): Long {
        val batteryLevel = getBatteryLevel()
        val isCharging = isCharging()
        val isPowerSaveMode = powerManager.isPowerSaveMode
        
        var multiplier = 1.0
        
        // Apply battery level adjustments
        when {
            batteryLevel <= criticalBatteryLevel -> multiplier *= 4.0  // Very aggressive saving
            batteryLevel <= lowBatteryLevel -> multiplier *= 2.5       // Aggressive saving
            batteryLevel <= mediumBatteryLevel -> multiplier *= 1.5    // Moderate saving
        }
        
        // Reduce optimization if charging
        if (isCharging) {
            multiplier = (multiplier + 1.0) / 2.0  // Average with no optimization
        }
        
        // Apply power save mode
        if (isPowerSaveMode) {
            multiplier *= 2.0
        }
        
        return (baseInterval * multiplier).toLong()
    }
    
    /**
     * Determines if battery optimization should be active
     */
    fun isOptimizationActive(): Boolean {
        val batteryLevel = getBatteryLevel()
        val isPowerSaveMode = powerManager.isPowerSaveMode
        val isCharging = isCharging()
        
        return (batteryLevel <= mediumBatteryLevel && !isCharging) || isPowerSaveMode
    }
    
    /**
     * Gets current battery level percentage
     */
    private fun getBatteryLevel(): Int {
        return try {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            100 // Assume full battery if unable to read
        }
    }
    
    /**
     * Checks if device is currently charging
     */
    private fun isCharging(): Boolean {
        return try {
            val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            status == BatteryManager.BATTERY_STATUS_CHARGING || 
            status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Gets battery optimization recommendations
     */
    fun getBatteryOptimizationInfo(): BatteryOptimizationInfo {
        val batteryLevel = getBatteryLevel()
        val isCharging = isCharging()
        val isPowerSaveMode = powerManager.isPowerSaveMode
        
        val recommendedMultiplier = when {
            batteryLevel <= criticalBatteryLevel -> 4.0
            batteryLevel <= lowBatteryLevel -> 2.5
            batteryLevel <= mediumBatteryLevel -> 1.5
            else -> 1.0
        }
        
        return BatteryOptimizationInfo(
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSaveMode,
            recommendedMultiplier = recommendedMultiplier,
            optimizationActive = isOptimizationActive()
        )
    }
    
    data class BatteryOptimizationInfo(
        val batteryLevel: Int,
        val isCharging: Boolean,
        val isPowerSaveMode: Boolean,
        val recommendedMultiplier: Double,
        val optimizationActive: Boolean
    )
}