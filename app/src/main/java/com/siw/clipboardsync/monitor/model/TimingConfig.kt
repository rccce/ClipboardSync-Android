package com.siw.clipboardsync.monitor.model

/**
 * Configuration class for clipboard monitoring timing parameters.
 */
data class TimingConfig(
    val readDelayMs: Long = 100L,
    val writeDelayMs: Long = 50L,
    val debounceWindowMs: Long = 200L,
    val retryDelayMs: Long = 1000L,
    val maxRetries: Int = 3,
    val powerModeMultiplier: Float = 1.5f
) {
    /**
     * Adjusts timing configuration for power saving mode.
     * @param isLowPower true if device is in low power mode
     * @return adjusted timing configuration
     */
    fun adjustForPowerMode(isLowPower: Boolean): TimingConfig {
        return if (isLowPower) {
            copy(
                readDelayMs = (readDelayMs * powerModeMultiplier).toLong(),
                writeDelayMs = (writeDelayMs * powerModeMultiplier).toLong(),
                debounceWindowMs = (debounceWindowMs * powerModeMultiplier).toLong()
            )
        } else {
            this
        }
    }
    
    /**
     * Calculates exponential backoff delay for retry attempts.
     * @param attemptNumber the current attempt number (starting from 1)
     * @return delay in milliseconds
     */
    fun calculateRetryDelay(attemptNumber: Int): Long {
        if (attemptNumber <= 0) return 0L
        if (attemptNumber > maxRetries) return retryDelayMs * (1L shl maxRetries)
        
        return retryDelayMs * (1L shl (attemptNumber - 1))
    }
    
    /**
     * Validates the timing configuration parameters.
     * @return true if configuration is valid, false otherwise
     */
    fun isValid(): Boolean {
        return readDelayMs >= 0 &&
                writeDelayMs >= 0 &&
                debounceWindowMs >= 0 &&
                retryDelayMs > 0 &&
                maxRetries >= 0 &&
                powerModeMultiplier > 0
    }
}