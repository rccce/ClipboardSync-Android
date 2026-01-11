package com.siw.clipboardsync.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Optimizes polling intervals based on battery state and power management settings
 * to preserve battery life while maintaining reasonable responsiveness.
 * 
 * Requirements: 9.2, 9.3, 9.5
 */
class BatteryOptimizer(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "BatteryOptimizer"
    }
    
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    // Battery level thresholds for optimization
    private val criticalBatteryLevel = 15
    private val lowBatteryLevel = 30
    private val mediumBatteryLevel = 50
    
    // Wake lock for critical operations
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockTimeout = 10_000L // 10 seconds max
    
    /**
     * Adjusts polling interval based on current battery state.
     * Requirements: 9.2
     */
    fun adjustIntervalForBattery(baseInterval: Long): Long {
        val batteryLevel = getBatteryLevel()
        val isCharging = isCharging()
        val isPowerSaveMode = powerManager.isPowerSaveMode
        val isDozeMode = isInDozeMode()
        
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
        
        // Apply doze mode (most aggressive)
        if (isDozeMode) {
            multiplier *= 3.0
        }
        
        val adjustedInterval = (baseInterval * multiplier).toLong()
        Log.v(TAG, "Adjusted interval: ${baseInterval}ms -> ${adjustedInterval}ms (battery=$batteryLevel%, charging=$isCharging, powerSave=$isPowerSaveMode, doze=$isDozeMode)")
        
        return adjustedInterval
    }
    
    /**
     * Determines if battery optimization should be active.
     */
    fun isOptimizationActive(): Boolean {
        val batteryLevel = getBatteryLevel()
        val isPowerSaveMode = powerManager.isPowerSaveMode
        val isCharging = isCharging()
        
        return (batteryLevel <= mediumBatteryLevel && !isCharging) || isPowerSaveMode
    }
    
    /**
     * Checks if device is in doze mode.
     * Requirements: 9.5
     */
    fun isInDozeMode(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isDeviceIdleMode
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check doze mode", e)
            false
        }
    }
    
    /**
     * Acquires a partial wake lock for critical clipboard operations.
     * Should be released as soon as possible.
     * Requirements: 9.3
     */
    fun acquireWakeLock(tag: String = "ClipboardSync"): Boolean {
        return try {
            if (wakeLock?.isHeld == true) {
                Log.d(TAG, "Wake lock already held")
                return true
            }
            
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ClipboardSync:$tag"
            ).apply {
                acquire(wakeLockTimeout)
            }
            
            Log.d(TAG, "Wake lock acquired for $tag (timeout: ${wakeLockTimeout}ms)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
            false
        }
    }
    
    /**
     * Releases the wake lock if held.
     * Requirements: 9.3
     */
    fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "Wake lock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }
    
    /**
     * Checks if wake lock is currently held.
     */
    fun isWakeLockHeld(): Boolean {
        return wakeLock?.isHeld == true
    }
    
    /**
     * Gets current battery level percentage.
     */
    fun getBatteryLevel(): Int {
        return try {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            // Fallback to intent-based method
            try {
                val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    (level * 100) / scale
                } else {
                    100 // Assume full battery if unable to read
                }
            } catch (e2: Exception) {
                100
            }
        }
    }
    
    /**
     * Checks if device is currently charging.
     */
    fun isCharging(): Boolean {
        return try {
            val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            status == BatteryManager.BATTERY_STATUS_CHARGING || 
            status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            // Fallback to intent-based method
            try {
                val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                status == BatteryManager.BATTERY_STATUS_CHARGING || 
                status == BatteryManager.BATTERY_STATUS_FULL
            } catch (e2: Exception) {
                false
            }
        }
    }
    
    /**
     * Gets the charging type (USB, AC, Wireless, etc.).
     */
    fun getChargingType(): ChargingType {
        return try {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            
            when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> ChargingType.AC
                BatteryManager.BATTERY_PLUGGED_USB -> ChargingType.USB
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargingType.WIRELESS
                else -> ChargingType.NONE
            }
        } catch (e: Exception) {
            ChargingType.NONE
        }
    }
    
    /**
     * Gets battery optimization recommendations.
     */
    fun getBatteryOptimizationInfo(): BatteryOptimizationInfo {
        val batteryLevel = getBatteryLevel()
        val isCharging = isCharging()
        val isPowerSaveMode = powerManager.isPowerSaveMode
        val isDozeMode = isInDozeMode()
        
        val recommendedMultiplier = when {
            isDozeMode -> 12.0
            batteryLevel <= criticalBatteryLevel -> 4.0
            batteryLevel <= lowBatteryLevel -> 2.5
            batteryLevel <= mediumBatteryLevel -> 1.5
            else -> 1.0
        }
        
        return BatteryOptimizationInfo(
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            chargingType = getChargingType(),
            isPowerSaveMode = isPowerSaveMode,
            isDozeMode = isDozeMode,
            recommendedMultiplier = recommendedMultiplier,
            optimizationActive = isOptimizationActive(),
            wakeLockHeld = isWakeLockHeld()
        )
    }
    
    enum class ChargingType {
        NONE, USB, AC, WIRELESS
    }
    
    data class BatteryOptimizationInfo(
        val batteryLevel: Int,
        val isCharging: Boolean,
        val chargingType: ChargingType,
        val isPowerSaveMode: Boolean,
        val isDozeMode: Boolean,
        val recommendedMultiplier: Double,
        val optimizationActive: Boolean,
        val wakeLockHeld: Boolean
    )
}