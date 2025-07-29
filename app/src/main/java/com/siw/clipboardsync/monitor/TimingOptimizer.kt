package com.siw.clipboardsync.monitor

/**
 * Interface for optimizing timing of clipboard operations.
 * Provides adaptive timing calculations based on device state and power mode.
 */
interface TimingOptimizer {
    /**
     * Gets the optimal delay for reading clipboard content.
     * @return delay in milliseconds
     */
    fun getOptimalReadDelay(): Long
    
    /**
     * Gets the optimal delay for writing clipboard content.
     * @return delay in milliseconds
     */
    fun getOptimalWriteDelay(): Long
    
    /**
     * Determines if a clipboard change should be debounced.
     * @param lastChangeTimestamp timestamp of the last clipboard change
     * @return true if the change should be debounced, false otherwise
     */
    fun shouldDebounce(lastChangeTimestamp: Long): Boolean
    
    /**
     * Adjusts timing based on current power mode.
     * @param baseDelay the base delay to adjust
     * @return adjusted delay in milliseconds
     */
    fun adjustForPowerMode(baseDelay: Long): Long
    
    /**
     * Gets the retry delay for failed operations with exponential backoff.
     * @param attemptNumber the current attempt number (starting from 1)
     * @return delay in milliseconds before next retry
     */
    fun getRetryDelay(attemptNumber: Int): Long
}