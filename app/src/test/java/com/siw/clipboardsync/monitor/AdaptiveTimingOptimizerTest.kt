package com.siw.clipboardsync.monitor

import android.content.Context
import android.os.PowerManager
import com.siw.clipboardsync.monitor.model.TimingConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdaptiveTimingOptimizerTest {
    
    private lateinit var context: Context
    private lateinit var powerManager: PowerManager
    private lateinit var timingConfig: TimingConfig
    private lateinit var optimizer: AdaptiveTimingOptimizer
    
    @Before
    fun setUp() {
        context = mockk()
        powerManager = mockk()
        timingConfig = TimingConfig()
        
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { powerManager.isPowerSaveMode } returns false
        
        optimizer = AdaptiveTimingOptimizer(context, timingConfig)
    }
    
    @Test
    fun `getOptimalReadDelay returns configured read delay in normal power mode`() {
        // Given: Normal power mode
        every { powerManager.isPowerSaveMode } returns false
        
        // When: Getting optimal read delay
        val delay = optimizer.getOptimalReadDelay()
        
        // Then: Should return base read delay
        assertEquals(timingConfig.readDelayMs, delay)
    }
    
    @Test
    fun `getOptimalReadDelay returns adjusted delay in low power mode`() {
        // Given: Low power mode enabled
        every { powerManager.isPowerSaveMode } returns true
        
        // When: Getting optimal read delay
        val delay = optimizer.getOptimalReadDelay()
        
        // Then: Should return adjusted delay
        val expectedDelay = (timingConfig.readDelayMs * timingConfig.powerModeMultiplier).toLong()
        assertEquals(expectedDelay, delay)
    }
    
    @Test
    fun `getOptimalWriteDelay returns configured write delay in normal power mode`() {
        // Given: Normal power mode
        every { powerManager.isPowerSaveMode } returns false
        
        // When: Getting optimal write delay
        val delay = optimizer.getOptimalWriteDelay()
        
        // Then: Should return base write delay
        assertEquals(timingConfig.writeDelayMs, delay)
    }
    
    @Test
    fun `getOptimalWriteDelay returns adjusted delay in low power mode`() {
        // Given: Low power mode enabled
        every { powerManager.isPowerSaveMode } returns true
        
        // When: Getting optimal write delay
        val delay = optimizer.getOptimalWriteDelay()
        
        // Then: Should return adjusted delay
        val expectedDelay = (timingConfig.writeDelayMs * timingConfig.powerModeMultiplier).toLong()
        assertEquals(expectedDelay, delay)
    }
    
    @Test
    fun `shouldDebounce returns true when within debounce window`() {
        // Given: Recent timestamp within debounce window
        val currentTime = System.currentTimeMillis()
        val recentTimestamp = currentTime - (timingConfig.debounceWindowMs / 2)
        
        // When: Checking if should debounce
        val shouldDebounce = optimizer.shouldDebounce(recentTimestamp)
        
        // Then: Should return true
        assertTrue(shouldDebounce)
    }
    
    @Test
    fun `shouldDebounce returns false when outside debounce window`() {
        // Given: Old timestamp outside debounce window
        val currentTime = System.currentTimeMillis()
        val oldTimestamp = currentTime - (timingConfig.debounceWindowMs * 2)
        
        // When: Checking if should debounce
        val shouldDebounce = optimizer.shouldDebounce(oldTimestamp)
        
        // Then: Should return false
        assertFalse(shouldDebounce)
    }
    
    @Test
    fun `shouldDebounce returns false for exact debounce window boundary`() {
        // Given: Timestamp exactly at debounce window boundary
        val currentTime = System.currentTimeMillis()
        val boundaryTimestamp = currentTime - timingConfig.debounceWindowMs
        
        // When: Checking if should debounce
        val shouldDebounce = optimizer.shouldDebounce(boundaryTimestamp)
        
        // Then: Should return false (not within window)
        assertFalse(shouldDebounce)
    }
    
    @Test
    fun `adjustForPowerMode returns same delay in normal power mode`() {
        // Given: Normal power mode and base delay
        every { powerManager.isPowerSaveMode } returns false
        val baseDelay = 100L
        
        // When: Adjusting for power mode
        val adjustedDelay = optimizer.adjustForPowerMode(baseDelay)
        
        // Then: Should return same delay
        assertEquals(baseDelay, adjustedDelay)
    }
    
    @Test
    fun `adjustForPowerMode returns increased delay in low power mode`() {
        // Given: Low power mode and base delay
        every { powerManager.isPowerSaveMode } returns true
        val baseDelay = 100L
        
        // When: Adjusting for power mode
        val adjustedDelay = optimizer.adjustForPowerMode(baseDelay)
        
        // Then: Should return increased delay
        val expectedDelay = (baseDelay * timingConfig.powerModeMultiplier).toLong()
        assertEquals(expectedDelay, adjustedDelay)
    }
    
    @Test
    fun `getRetryDelay implements exponential backoff`() {
        // Test exponential backoff for multiple attempts
        val attempt1Delay = optimizer.getRetryDelay(1)
        val attempt2Delay = optimizer.getRetryDelay(2)
        val attempt3Delay = optimizer.getRetryDelay(3)
        
        // Verify exponential progression
        assertEquals(timingConfig.retryDelayMs, attempt1Delay)
        assertEquals(timingConfig.retryDelayMs * 2, attempt2Delay)
        assertEquals(timingConfig.retryDelayMs * 4, attempt3Delay)
    }
    
    @Test
    fun `getRetryDelay returns zero for invalid attempt number`() {
        // Given: Invalid attempt number
        val invalidAttempt = 0
        
        // When: Getting retry delay
        val delay = optimizer.getRetryDelay(invalidAttempt)
        
        // Then: Should return zero
        assertEquals(0L, delay)
    }
    
    @Test
    fun `getRetryDelay caps at maximum retries`() {
        // Given: Attempt number exceeding max retries
        val excessiveAttempt = timingConfig.maxRetries + 2
        
        // When: Getting retry delay
        val delay = optimizer.getRetryDelay(excessiveAttempt)
        
        // Then: Should return delay capped at max retries
        val expectedDelay = timingConfig.retryDelayMs * (1L shl timingConfig.maxRetries)
        assertEquals(expectedDelay, delay)
    }
    
    @Test
    fun `recordSuccess resets consecutive failures`() {
        // Given: Some consecutive failures
        optimizer.recordFailure()
        optimizer.recordFailure()
        assertEquals(2, optimizer.getConsecutiveFailures())
        
        // When: Recording success
        optimizer.recordSuccess()
        
        // Then: Consecutive failures should be reset
        assertEquals(0, optimizer.getConsecutiveFailures())
    }
    
    @Test
    fun `recordFailure increments consecutive failures`() {
        // Given: Initial state
        assertEquals(0, optimizer.getConsecutiveFailures())
        
        // When: Recording failures
        optimizer.recordFailure()
        optimizer.recordFailure()
        
        // Then: Consecutive failures should increment
        assertEquals(2, optimizer.getConsecutiveFailures())
    }
    
    @Test
    fun `updateLastChangeTimestamp updates internal timestamp`() {
        // Given: Specific timestamp
        val testTimestamp = 12345L
        
        // When: Updating last change timestamp
        optimizer.updateLastChangeTimestamp(testTimestamp)
        
        // Then: Should store the timestamp
        assertEquals(testTimestamp, optimizer.getLastChangeTimestamp())
    }
    
    @Test
    fun `updateLastChangeTimestamp uses current time when no parameter provided`() {
        // Given: Current time before update
        val timeBefore = System.currentTimeMillis()
        
        // When: Updating without parameter
        optimizer.updateLastChangeTimestamp()
        
        // Then: Should use current time
        val timeAfter = System.currentTimeMillis()
        val storedTime = optimizer.getLastChangeTimestamp()
        
        assertTrue("Stored time should be between before and after", 
            storedTime >= timeBefore && storedTime <= timeAfter)
    }
    
    @Test
    fun `getAdaptiveDelay applies performance factor`() {
        // Given: Base delay and performance factor
        every { powerManager.isPowerSaveMode } returns false
        val baseDelay = 100L
        val performanceFactor = 2.0f
        
        // When: Getting adaptive delay
        val adaptiveDelay = optimizer.getAdaptiveDelay(baseDelay, performanceFactor)
        
        // Then: Should apply performance factor
        assertEquals((baseDelay * performanceFactor).toLong(), adaptiveDelay)
    }
    
    @Test
    fun `getAdaptiveDelay combines power mode and performance factor`() {
        // Given: Low power mode, base delay, and performance factor
        every { powerManager.isPowerSaveMode } returns true
        val baseDelay = 100L
        val performanceFactor = 1.5f
        
        // When: Getting adaptive delay
        val adaptiveDelay = optimizer.getAdaptiveDelay(baseDelay, performanceFactor)
        
        // Then: Should apply both power mode and performance factor
        val powerAdjustedDelay = (baseDelay * timingConfig.powerModeMultiplier).toLong()
        val expectedDelay = (powerAdjustedDelay * performanceFactor).toLong()
        assertEquals(expectedDelay, adaptiveDelay)
    }
    
    @Test
    fun `reset clears all internal state`() {
        // Given: Some internal state
        optimizer.updateLastChangeTimestamp(12345L)
        optimizer.recordFailure()
        optimizer.recordFailure()
        
        // When: Resetting
        optimizer.reset()
        
        // Then: All state should be cleared
        assertEquals(0L, optimizer.getLastChangeTimestamp())
        assertEquals(0, optimizer.getConsecutiveFailures())
    }
    
    @Test
    fun `power manager exception handling returns false for low power mode`() {
        // Given: PowerManager that throws exception
        every { powerManager.isPowerSaveMode } throws RuntimeException("Test exception")
        
        // When: Getting optimal delay (which checks power mode)
        val delay = optimizer.getOptimalReadDelay()
        
        // Then: Should handle exception gracefully and return normal delay
        assertEquals(timingConfig.readDelayMs, delay)
    }
    
    @Test
    fun `custom timing config is respected`() {
        // Given: Custom timing configuration
        val customConfig = TimingConfig(
            readDelayMs = 200L,
            writeDelayMs = 75L,
            debounceWindowMs = 300L,
            retryDelayMs = 2000L,
            maxRetries = 5,
            powerModeMultiplier = 2.0f
        )
        val customOptimizer = AdaptiveTimingOptimizer(context, customConfig)
        every { powerManager.isPowerSaveMode } returns false
        
        // When: Getting delays
        val readDelay = customOptimizer.getOptimalReadDelay()
        val writeDelay = customOptimizer.getOptimalWriteDelay()
        val retryDelay = customOptimizer.getRetryDelay(1)
        
        // Then: Should use custom configuration
        assertEquals(customConfig.readDelayMs, readDelay)
        assertEquals(customConfig.writeDelayMs, writeDelay)
        assertEquals(customConfig.retryDelayMs, retryDelay)
    }
}