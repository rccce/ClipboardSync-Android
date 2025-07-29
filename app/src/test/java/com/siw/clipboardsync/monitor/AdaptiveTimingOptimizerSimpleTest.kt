package com.siw.clipboardsync.monitor

import android.content.Context
import android.os.PowerManager
import com.siw.clipboardsync.monitor.model.TimingConfig
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class AdaptiveTimingOptimizerSimpleTest {
    
    @Test
    fun `TimingConfig default values are correct`() {
        val config = TimingConfig()
        
        assertEquals(100L, config.readDelayMs)
        assertEquals(50L, config.writeDelayMs)
        assertEquals(200L, config.debounceWindowMs)
        assertEquals(1000L, config.retryDelayMs)
        assertEquals(3, config.maxRetries)
        assertEquals(1.5f, config.powerModeMultiplier, 0.01f)
    }
    
    @Test
    fun `exponential backoff calculation works correctly`() {
        val config = TimingConfig()
        
        // Test exponential backoff: 1000, 2000, 4000, 8000
        assertEquals(1000L, config.calculateRetryDelay(1))
        assertEquals(2000L, config.calculateRetryDelay(2))
        assertEquals(4000L, config.calculateRetryDelay(3))
        assertEquals(8000L, config.calculateRetryDelay(4))
    }
    
    @Test
    fun `debounce window calculation works correctly`() {
        val currentTime = System.currentTimeMillis()
        val config = TimingConfig()
        
        // Create a mock context and power manager
        val mockContext = mock(Context::class.java)
        val mockPowerManager = mock(PowerManager::class.java)
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        `when`(mockPowerManager.isPowerSaveMode).thenReturn(false)
        
        val optimizer = AdaptiveTimingOptimizer(mockContext, config)
        
        // Test debouncing within window
        val recentTimestamp = currentTime - 100L // 100ms ago, within 200ms window
        assertTrue("Should debounce recent changes", optimizer.shouldDebounce(recentTimestamp))
        
        // Test not debouncing outside window
        val oldTimestamp = currentTime - 300L // 300ms ago, outside 200ms window
        assertFalse("Should not debounce old changes", optimizer.shouldDebounce(oldTimestamp))
    }
    
    @Test
    fun `power mode adjustment works correctly`() {
        val config = TimingConfig()
        
        // Test normal power mode
        val normalConfig = config.adjustForPowerMode(false)
        assertEquals(config.readDelayMs, normalConfig.readDelayMs)
        assertEquals(config.writeDelayMs, normalConfig.writeDelayMs)
        
        // Test low power mode
        val lowPowerConfig = config.adjustForPowerMode(true)
        assertEquals((config.readDelayMs * 1.5f).toLong(), lowPowerConfig.readDelayMs)
        assertEquals((config.writeDelayMs * 1.5f).toLong(), lowPowerConfig.writeDelayMs)
    }
    
    @Test
    fun `failure tracking works correctly`() {
        val mockContext = mock(Context::class.java)
        val mockPowerManager = mock(PowerManager::class.java)
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        `when`(mockPowerManager.isPowerSaveMode).thenReturn(false)
        
        val optimizer = AdaptiveTimingOptimizer(mockContext)
        
        // Initial state
        assertEquals(0, optimizer.getConsecutiveFailures())
        
        // Record failures
        optimizer.recordFailure()
        assertEquals(1, optimizer.getConsecutiveFailures())
        
        optimizer.recordFailure()
        assertEquals(2, optimizer.getConsecutiveFailures())
        
        // Record success should reset
        optimizer.recordSuccess()
        assertEquals(0, optimizer.getConsecutiveFailures())
    }
    
    @Test
    fun `timestamp tracking works correctly`() {
        val mockContext = mock(Context::class.java)
        val mockPowerManager = mock(PowerManager::class.java)
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        `when`(mockPowerManager.isPowerSaveMode).thenReturn(false)
        
        val optimizer = AdaptiveTimingOptimizer(mockContext)
        
        // Initial state
        assertEquals(0L, optimizer.getLastChangeTimestamp())
        
        // Update with specific timestamp
        val testTimestamp = 12345L
        optimizer.updateLastChangeTimestamp(testTimestamp)
        assertEquals(testTimestamp, optimizer.getLastChangeTimestamp())
        
        // Update with current time
        val timeBefore = System.currentTimeMillis()
        optimizer.updateLastChangeTimestamp()
        val timeAfter = System.currentTimeMillis()
        val storedTime = optimizer.getLastChangeTimestamp()
        
        assertTrue("Stored time should be current", 
            storedTime >= timeBefore && storedTime <= timeAfter)
    }
    
    @Test
    fun `reset clears all state`() {
        val mockContext = mock(Context::class.java)
        val mockPowerManager = mock(PowerManager::class.java)
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        `when`(mockPowerManager.isPowerSaveMode).thenReturn(false)
        
        val optimizer = AdaptiveTimingOptimizer(mockContext)
        
        // Set some state
        optimizer.updateLastChangeTimestamp(12345L)
        optimizer.recordFailure()
        optimizer.recordFailure()
        
        // Verify state is set
        assertEquals(12345L, optimizer.getLastChangeTimestamp())
        assertEquals(2, optimizer.getConsecutiveFailures())
        
        // Reset and verify cleared
        optimizer.reset()
        assertEquals(0L, optimizer.getLastChangeTimestamp())
        assertEquals(0, optimizer.getConsecutiveFailures())
    }
}