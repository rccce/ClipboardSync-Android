package com.siw.clipboardsync.monitor

import android.content.Context
import android.os.PowerManager
import com.siw.clipboardsync.monitor.model.TimingConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive implementation of TimingOptimizer that adjusts timing based on device state,
 * power mode, and system performance characteristics.
 */
@Singleton
class AdaptiveTimingOptimizer @Inject constructor(
    private val context: Context,
    private val timingConfig: TimingConfig = TimingConfig()
) : TimingOptimizer {
    
    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    
    private var lastChangeTimestamp: Long = 0L
    private var consecutiveFailures: Int = 0
    
    /**
     * Gets the optimal delay for reading clipboard content.
     * Considers power mode and system state for adaptive timing.
     */
    override fun getOptimalReadDelay(): Long {
        val baseDelay = timingConfig.readDelayMs
        return adjustForPowerMode(baseDelay)
    }
    
    /**
     * Gets the optimal delay for writing clipboard content.
     * Optimized for minimal user-perceived latency.
     */
    override fun getOptimalWriteDelay(): Long {
        val baseDelay = timingConfig.writeDelayMs
        return adjustForPowerMode(baseDelay)
    }
    
    /**
     * Determines if a clipboard change should be debounced based on the 200ms window.
     * Prevents rapid-fire clipboard events from overwhelming the system.
     */
    override fun shouldDebounce(lastChangeTimestamp: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = currentTime - lastChangeTimestamp
        
        return timeSinceLastChange < timingConfig.debounceWindowMs
    }
    
    /**
     * Adjusts timing based on current power mode.
     * Increases delays when device is in power saving mode.
     */
    override fun adjustForPowerMode(baseDelay: Long): Long {
        val isLowPowerMode = isDeviceInLowPowerMode()
        val adjustedConfig = timingConfig.adjustForPowerMode(isLowPowerMode)
        
        return when {
            baseDelay == timingConfig.readDelayMs -> adjustedConfig.readDelayMs
            baseDelay == timingConfig.writeDelayMs -> adjustedConfig.writeDelayMs
            baseDelay == timingConfig.debounceWindowMs -> adjustedConfig.debounceWindowMs
            else -> if (isLowPowerMode) {
                (baseDelay * timingConfig.powerModeMultiplier).toLong()
            } else {
                baseDelay
            }
        }
    }
    
    /**
     * Gets the retry delay for failed operations with exponential backoff.
     * Implements exponential backoff up to maxRetries attempts.
     */
    override fun getRetryDelay(attemptNumber: Int): Long {
        return timingConfig.calculateRetryDelay(attemptNumber)
    }
    
    /**
     * Records a successful operation, resetting failure count.
     */
    fun recordSuccess() {
        consecutiveFailures = 0
    }
    
    /**
     * Records a failed operation, incrementing failure count.
     */
    fun recordFailure() {
        consecutiveFailures++
    }
    
    /**
     * Gets the current consecutive failure count.
     */
    fun getConsecutiveFailures(): Int = consecutiveFailures
    
    /**
     * Updates the timestamp of the last clipboard change.
     * Used internally for debouncing calculations.
     */
    fun updateLastChangeTimestamp(timestamp: Long = System.currentTimeMillis()) {
        lastChangeTimestamp = timestamp
    }
    
    /**
     * Gets the timestamp of the last clipboard change.
     */
    fun getLastChangeTimestamp(): Long = lastChangeTimestamp
    
    /**
     * Determines if the device is currently in low power mode.
     */
    private fun isDeviceInLowPowerMode(): Boolean {
        return try {
            powerManager.isPowerSaveMode
        } catch (e: Exception) {
            // Fallback to false if unable to determine power mode
            false
        }
    }
    
    /**
     * Calculates adaptive delay based on system performance.
     * Can be extended to consider CPU usage, memory pressure, etc.
     */
    fun getAdaptiveDelay(baseDelay: Long, performanceFactor: Float = 1.0f): Long {
        val powerAdjustedDelay = adjustForPowerMode(baseDelay)
        return (powerAdjustedDelay * performanceFactor).toLong()
    }
    
    /**
     * Resets all internal state.
     */
    fun reset() {
        lastChangeTimestamp = 0L
        consecutiveFailures = 0
    }
}