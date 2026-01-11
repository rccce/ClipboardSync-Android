package com.siw.clipboardsync.monitor

import android.content.Context
import android.os.PowerManager
import com.siw.clipboardsync.monitor.model.TimingConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/**
 * Integration test demonstrating how TimingOptimizer would be used in practice
 * for clipboard monitoring operations.
 */
class TimingOptimizerIntegrationTest {
    
    @Test
    fun `clipboard read operation with optimal timing`() = runTest {
        // Setup
        val mockContext = mock(Context::class.java)
        val mockPowerManager = mock(PowerManager::class.java)
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        `when`(mockPowerManager.isPowerSaveMode).thenReturn(false)
        
        val optimizer = AdaptiveTimingOptimizer(mockContext)
        
        // Simulate clipboard read operation
        val startTime = System.currentTimeMillis()
        val optimalDelay = optimizer.getOptimalReadDelay()
        delay(optimalDelay)
        val endTime = System.currentTimeMillis()
        
        // Verify timing is within expected range
        val actualDelay = endTime - startTime
        assertTrue("Read delay should be close to optimal", 
            actualDelay >= optimalDelay && actualDelay <= optimalDelay + 50)
    }
    
    @Test
    fun `clipboard write operation with optimal timing`() = runTest {
        // Setup
        val mockContext = mock(Context::class.java)
        val mockPowerManager = mock(PowerManager::class.java)
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        `when`(mockPowerManager.isPowerSaveMode).thenReturn(false)
        
        val optimizer = AdaptiveTimingOptimizer(mockContext)
        
        // Simulate clipboard write operation
        val startTime = System.currentTimeMillis()
        val optimalDelay = optimizer.getOptimalWriteDelay()
        delay(optimalDelay)
        val endTime = System.currentTimeMillis()
        
        // Verify timing is within expected range
        val actualDelay = endTime - startTime
        assertTrue("Write delay should be close to optimal", 
            actualDelay >= optimalDelay && actualDelay <= optimalDelay + 50)
    }
    
    @Test
    fun `debouncing prevents rapid clipboard changes`() = runTest {
        // Setup
        val mockContext = mock(Context::class.java)
        val mockPowerManager = mock(PowerManager::class.java)
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        `when`(mockPowerManager.isPowerSaveMode).thenReturn(false)
        
        val optimizer = AdaptiveTimingOptimizer(mockContext)
        
        // Simulate rapid clipboard changes
        val firstChangeTime = System.currentTimeMillis()
        optimizer.updateLastChangeTimestamp(firstChangeTime)
        
        // Immediate second change should be debounced
        val shouldDebounceImmediate = optimizer.shouldDebounce(firstChangeTime)
        assertTrue("Immediate changes should be debounced", shouldDebounceImmediate)
        
        // Wait for debounce window to pass
        delay(250) // Wait longer than 200ms debounce window
        
        val shouldDebounceAfterWait = optimizer.shouldDebounce(firstChangeTime)
        assertFalse("Changes after debounce window should not be debounced", shouldDebounceAfterWait)
    }
    
    @Test
    fun `retry mechanism with exponential backoff`() = runTest {
        // Setup
        val mockContext = mock(Context::class.java)
        val mockPowerManager = mock(PowerManager::class.java)
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        `when`(mockPowerManager.isPowerSaveMode).thenReturn(false)
        
        val optimizer = AdaptiveTimingOptimizer(mockContext)
        
        // Simulate failed operations with retry
        var attempt = 1
        var success = false
        
        while (attempt <= 3 && !success) {
            try {
                // Record the failure
                optimizer.recordFailure()
                
                // Get retry delay
                val retryDelay = optimizer.getRetryDelay(attempt)
                
                // Verify exponential backoff
                val expectedDelay = 1000L * (1L shl (attempt - 1))
                assertEquals("Retry delay should follow exponential backoff", 
                    expectedDelay, retryDelay)
                
                // Simulate waiting for retry
                delay(retryDelay)
                
                // Simulate operation (would succeed on attempt 3 in real scenario)
                if (attempt == 3) {
                    success = true
                    optimizer.recordSuccess()
                }
                
                attempt++
            } catch (e: Exception) {
                // Handle operation failure
                break
            }
        }
        
        assertTrue("Should eventually succeed", success)
        assertEquals("Should have reset failure count", 0, optimizer.getConsecutiveFailures())
    }
    
    @Test
    fun `power mode affects timing calculations`() {
        // Setup for normal power mode
        val mockContext = mock(Context::class.java)
        val mockPowerManager = mock(PowerManager::class.java)
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        
        val optimizer = AdaptiveTimingOptimizer(mockContext)
        
        // Test normal power mode
        `when`(mockPowerManager.isPowerSaveMode).thenReturn(false)
        val normalReadDelay = optimizer.getOptimalReadDelay()
        val normalWriteDelay = optimizer.getOptimalWriteDelay()
        
        // Test low power mode
        `when`(mockPowerManager.isPowerSaveMode).thenReturn(true)
        val lowPowerReadDelay = optimizer.getOptimalReadDelay()
        val lowPowerWriteDelay = optimizer.getOptimalWriteDelay()
        
        // Verify power mode increases delays
        assertTrue("Low power mode should increase read delay", 
            lowPowerReadDelay > normalReadDelay)
        assertTrue("Low power mode should increase write delay", 
            lowPowerWriteDelay > normalWriteDelay)
        
        // Verify the multiplier is applied correctly
        val expectedReadDelay = (normalReadDelay * 1.5f).toLong()
        val expectedWriteDelay = (normalWriteDelay * 1.5f).toLong()
        assertEquals("Read delay should be multiplied by power mode factor", 
            expectedReadDelay, lowPowerReadDelay)
        assertEquals("Write delay should be multiplied by power mode factor", 
            expectedWriteDelay, lowPowerWriteDelay)
    }
    
    @Test
    fun `adaptive delay considers performance factors`() {
        // Setup
        val mockContext = mock(Context::class.java)
        val mockPowerManager = mock(PowerManager::class.java)
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        `when`(mockPowerManager.isPowerSaveMode).thenReturn(false)
        
        val optimizer = AdaptiveTimingOptimizer(mockContext)
        
        // Test with different performance factors
        val baseDelay = 100L
        val normalPerformance = optimizer.getAdaptiveDelay(baseDelay, 1.0f)
        val slowPerformance = optimizer.getAdaptiveDelay(baseDelay, 2.0f)
        val fastPerformance = optimizer.getAdaptiveDelay(baseDelay, 0.5f)
        
        assertEquals("Normal performance should not change delay", baseDelay, normalPerformance)
        assertEquals("Slow performance should double delay", baseDelay * 2, slowPerformance)
        assertEquals("Fast performance should halve delay", baseDelay / 2, fastPerformance)
    }
}