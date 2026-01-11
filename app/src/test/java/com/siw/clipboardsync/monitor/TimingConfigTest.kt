package com.siw.clipboardsync.monitor

import com.siw.clipboardsync.monitor.model.TimingConfig
import org.junit.Assert.*
import org.junit.Test

class TimingConfigTest {
    
    @Test
    fun `default TimingConfig has expected values`() {
        val config = TimingConfig()
        
        assertEquals(100L, config.readDelayMs)
        assertEquals(50L, config.writeDelayMs)
        assertEquals(200L, config.debounceWindowMs)
        assertEquals(1000L, config.retryDelayMs)
        assertEquals(3, config.maxRetries)
        assertEquals(1.5f, config.powerModeMultiplier, 0.01f)
    }
    
    @Test
    fun `adjustForPowerMode increases delays when in low power mode`() {
        val config = TimingConfig()
        val adjustedConfig = config.adjustForPowerMode(true)
        
        assertEquals((100L * 1.5f).toLong(), adjustedConfig.readDelayMs)
        assertEquals((50L * 1.5f).toLong(), adjustedConfig.writeDelayMs)
        assertEquals((200L * 1.5f).toLong(), adjustedConfig.debounceWindowMs)
    }
    
    @Test
    fun `adjustForPowerMode keeps same delays when not in low power mode`() {
        val config = TimingConfig()
        val adjustedConfig = config.adjustForPowerMode(false)
        
        assertEquals(config.readDelayMs, adjustedConfig.readDelayMs)
        assertEquals(config.writeDelayMs, adjustedConfig.writeDelayMs)
        assertEquals(config.debounceWindowMs, adjustedConfig.debounceWindowMs)
    }
    
    @Test
    fun `calculateRetryDelay implements exponential backoff`() {
        val config = TimingConfig()
        
        assertEquals(1000L, config.calculateRetryDelay(1))
        assertEquals(2000L, config.calculateRetryDelay(2))
        assertEquals(4000L, config.calculateRetryDelay(3))
    }
    
    @Test
    fun `calculateRetryDelay returns zero for invalid attempt`() {
        val config = TimingConfig()
        
        assertEquals(0L, config.calculateRetryDelay(0))
        assertEquals(0L, config.calculateRetryDelay(-1))
    }
    
    @Test
    fun `isValid returns true for valid configuration`() {
        val config = TimingConfig()
        assertTrue(config.isValid())
    }
    
    @Test
    fun `isValid returns false for invalid configuration`() {
        val invalidConfig = TimingConfig(
            readDelayMs = -1L,
            writeDelayMs = 50L,
            debounceWindowMs = 200L,
            retryDelayMs = 1000L,
            maxRetries = 3,
            powerModeMultiplier = 1.5f
        )
        
        assertFalse(invalidConfig.isValid())
    }
}