package com.siw.clipboardsync.performance

import android.content.Context
import android.os.Build
import com.siw.clipboardsync.monitor.*
import com.siw.clipboardsync.monitor.model.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis
import org.junit.Assert.*

/**
 * Performance benchmark tests for CPU and battery usage validation.
 * Tests monitoring implementations against performance requirements.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class PerformanceBenchmarkSuite {
    
    private lateinit var context: Context
    private lateinit var cpuTracker: CpuUsageTracker
    private lateinit var batteryOptimizer: BatteryOptimizer
    
    private val testScope = TestScope()
    
    @Before
    fun setup() {
        context = mockk(relaxed = true)
        cpuTracker = CpuUsageTracker()
        batteryOptimizer = BatteryOptimizer(context)
    }
    
    @After
    fun tearDown() {
        testScope.cancel()
        cpuTracker.stopTracking()
    }
    
    @Test
    fun `benchmark system level monitoring CPU usage`() = testScope.runTest {
        // Given: System-level monitor
        val nativeHookManager = mockk<NativeHookManager>(relaxed = true)
        val timingOptimizer = mockk<TimingOptimizer>(relaxed = true)
        
        every { nativeHookManager.isAvailable() } returns true
        every { timingOptimizer.getOptimalReadDelay() } returns 100L
        
        val monitor = SystemLevelClipboardMonitor(
            context = context,
            nativeHookManager = nativeHookManager,
            xposedHookManager = mockk(relaxed = true),
            timingOptimizer = timingOptimizer
        )
        
        // When: Start monitoring and measure CPU usage
        cpuTracker.startTracking()
        monitor.setListener(mockk(relaxed = true))
        monitor.startMonitoring()
        
        // Simulate monitoring activity for 5 seconds
        delay(5000)
        
        val avgCpuUsage = cpuTracker.getAverageUsage()
        monitor.stopMonitoring()
        cpuTracker.stopTracking()
        
        // Then: CPU usage should be under 1% average (Requirement 4.4)
        assertTrue(avgCpuUsage < 1.0, "System-level monitoring CPU usage: $avgCpuUsage% exceeds 1% limit")
    }
    
    @Test
    fun `benchmark accessibility service monitoring CPU usage`() = testScope.runTest {
        // Given: Accessibility monitor
        val timingOptimizer = AdaptiveTimingOptimizer(context)
        val monitor = AccessibilityClipboardMonitor(context, timingOptimizer)
        
        // When: Start monitoring and measure CPU usage
        cpuTracker.startTracking()
        monitor.setListener(mockk(relaxed = true))
        monitor.startMonitoring()
        
        // Simulate monitoring activity
        delay(5000)
        
        val avgCpuUsage = cpuTracker.getAverageUsage()
        monitor.stopMonitoring()
        cpuTracker.stopTracking()
        
        // Then: CPU usage should be reasonable for accessibility service
        assertTrue(avgCpuUsage < 2.0, "Accessibility monitoring CPU usage: $avgCpuUsage% exceeds 2% limit")
    }
    
    @Test
    fun `benchmark polling monitor CPU usage with adaptive intervals`() = testScope.runTest {
        // Given: Polling monitor with adaptive timing
        val timingOptimizer = AdaptiveTimingOptimizer(context)
        val monitor = PollingClipboardMonitor(context, timingOptimizer)
        
        // When: Start monitoring and measure CPU usage
        cpuTracker.startTracking()
        monitor.setListener(mockk(relaxed = true))
        monitor.startMonitoring()
        
        // Simulate monitoring activity
        delay(10000) // Longer test for polling
        
        val avgCpuUsage = cpuTracker.getAverageUsage()
        monitor.stopMonitoring()
        cpuTracker.stopTracking()
        
        // Then: CPU usage should be under 1% even with polling (Requirement 4.4)
        assertTrue(avgCpuUsage < 1.0, "Polling monitoring CPU usage: $avgCpuUsage% exceeds 1% limit")
    }
    
    @Test
    fun `benchmark clipboard read operation timing`() = testScope.runTest {
        // Given: Various clipboard content sizes
        val contentSizes = listOf(100, 1000, 10000, 100000) // bytes
        val timingOptimizer = AdaptiveTimingOptimizer(context)
        
        contentSizes.forEach { size ->
            val content = ClipboardContent(
                type = ClipboardContent.ContentType.TEXT,
                data = ByteArray(size) { 'A'.code.toByte() },
                mimeType = "text/plain",
                source = "test",
                size = size.toLong()
            )
            
            // When: Process clipboard content
            val processingTime = measureTimeMillis {
                val processor = TextProcessor()
                processor.process(content)
            }
            
            // Then: Should complete within 100ms (Requirement 2.1)
            assertTrue(
                processingTime <= 100,
                "Clipboard read for ${size}B took ${processingTime}ms, exceeds 100ms limit"
            )
        }
    }
    
    @Test
    fun `benchmark clipboard write operation timing`() = testScope.runTest {
        // Given: Various clipboard content for writing
        val contentSizes = listOf(100, 1000, 10000, 50000) // bytes
        
        contentSizes.forEach { size ->
            val content = "A".repeat(size)
            
            // When: Write to clipboard (simulated)
            val writeTime = measureTimeMillis {
                // Simulate clipboard write operation
                val clipboardManager = mockk<android.content.ClipboardManager>(relaxed = true)
                val clipData = android.content.ClipData.newPlainText("test", content)
                every { clipboardManager.setPrimaryClip(clipData) } just Runs
                clipboardManager.setPrimaryClip(clipData)
            }
            
            // Then: Should complete within 50ms (Requirement 2.2)
            assertTrue(
                writeTime <= 50,
                "Clipboard write for ${size}B took ${writeTime}ms, exceeds 50ms limit"
            )
        }
    }
    
    @Test
    fun `benchmark debouncing performance`() = testScope.runTest {
        // Given: Rapid clipboard changes
        val timingOptimizer = AdaptiveTimingOptimizer(context)
        val changeCount = 100
        val changes = mutableListOf<Long>()
        
        // When: Simulate rapid changes
        val totalTime = measureTimeMillis {
            repeat(changeCount) {
                val timestamp = System.currentTimeMillis()
                changes.add(timestamp)
                
                // Test debouncing decision
                val shouldDebounce = timingOptimizer.shouldDebounce(timestamp - 50)
                
                if (!shouldDebounce) {
                    // Simulate processing
                    delay(1)
                }
                
                delay(10) // 10ms between changes
            }
        }
        
        // Then: Should handle rapid changes efficiently
        val avgTimePerChange = totalTime.toDouble() / changeCount
        assertTrue(
            avgTimePerChange <= 200.0,
            "Average time per change: ${avgTimePerChange}ms exceeds 200ms debounce window"
        )
    }
    
    @Test
    fun `benchmark memory usage during monitoring`() = testScope.runTest {
        // Given: Monitor with memory tracking
        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()
        
        val monitor = PollingClipboardMonitor(
            context = context,
            timingOptimizer = AdaptiveTimingOptimizer(context)
        )
        
        // When: Start monitoring and simulate activity
        monitor.setListener(mockk(relaxed = true))
        monitor.startMonitoring()
        
        // Simulate clipboard changes
        repeat(1000) {
            val content = ClipboardContent(
                type = ClipboardContent.ContentType.TEXT,
                data = "test content $it".toByteArray(),
                mimeType = "text/plain",
                source = "test",
                size = 20L
            )
            // Simulate processing
            delay(1)
        }
        
        monitor.stopMonitoring()
        
        // Force garbage collection
        System.gc()
        delay(100)
        
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryIncrease = finalMemory - initialMemory
        
        // Then: Memory increase should be reasonable (less than 10MB)
        assertTrue(
            memoryIncrease < 10 * 1024 * 1024,
            "Memory increase: ${memoryIncrease / 1024 / 1024}MB exceeds 10MB limit"
        )
    }
    
    @Test
    fun `benchmark battery optimization effectiveness`() = testScope.runTest {
        // Given: Battery optimizer with different power modes
        val normalModeDelay = 100L
        val lowPowerModeDelay = batteryOptimizer.adjustTimingForPowerMode(normalModeDelay, true)
        val batteryOptimizedDelay = batteryOptimizer.adjustTimingForPowerMode(normalModeDelay, false)
        
        // When: Compare timing adjustments
        val powerSavings = ((lowPowerModeDelay - normalModeDelay).toDouble() / normalModeDelay) * 100
        
        // Then: Should provide meaningful power savings (Requirement 2.4)
        assertTrue(
            powerSavings >= 50.0,
            "Power mode adjustment only provides ${powerSavings}% savings, expected at least 50%"
        )
        
        // And: Normal mode should not increase delay
        assertTrue(
            batteryOptimizedDelay <= normalModeDelay,
            "Battery optimized delay ${batteryOptimizedDelay}ms exceeds normal delay ${normalModeDelay}ms"
        )
    }
    
    @Test
    fun `benchmark concurrent monitoring performance`() = testScope.runTest {
        // Given: Multiple monitors running concurrently
        val monitors = listOf(
            PollingClipboardMonitor(context, AdaptiveTimingOptimizer(context)),
            AccessibilityClipboardMonitor(context, AdaptiveTimingOptimizer(context)),
            ForegroundServiceClipboardMonitor(context, AdaptiveTimingOptimizer(context))
        )
        
        // When: Start all monitors concurrently
        cpuTracker.startTracking()
        
        val jobs = monitors.map { monitor ->
            async {
                monitor.setListener(mockk(relaxed = true))
                monitor.startMonitoring()
                delay(3000)
                monitor.stopMonitoring()
            }
        }
        
        jobs.awaitAll()
        
        val avgCpuUsage = cpuTracker.getAverageUsage()
        cpuTracker.stopTracking()
        
        // Then: Combined CPU usage should still be reasonable
        assertTrue(
            avgCpuUsage < 3.0,
            "Concurrent monitoring CPU usage: ${avgCpuUsage}% exceeds 3% limit"
        )
    }
    
    @Test
    fun `benchmark error recovery performance`() = testScope.runTest {
        // Given: Error handler with fallback chain
        val errorHandler = ClipboardErrorHandler()
        val errors = listOf(
            ClipboardError.RootAccessLost,
            ClipboardError.PermissionDenied,
            ClipboardError.SystemHookFailed,
            ClipboardError.ServiceDisconnected
        )
        
        // When: Handle various errors and measure recovery time
        errors.forEach { error ->
            val recoveryTime = measureTimeMillis {
                val result = errorHandler.handleError(error, MonitoringMethod.SYSTEM_HOOKS)
                assertNotNull(result.suggestedFallback)
            }
            
            // Then: Error recovery should be fast (under 5 seconds per Requirement 6.5)
            assertTrue(
                recoveryTime <= 5000,
                "Error recovery for ${error::class.simpleName} took ${recoveryTime}ms, exceeds 5000ms limit"
            )
        }
    }
    
    @Test
    fun `benchmark strategy selection performance`() = testScope.runTest {
        // Given: Strategy factory with multiple strategies
        val strategyFactory = MonitoringStrategyFactory(
            context = context,
            rootDetectionService = mockk(relaxed = true)
        )
        
        // When: Get available strategies multiple times
        val selectionTime = measureTimeMillis {
            repeat(100) {
                val strategies = strategyFactory.getAvailableStrategies()
                assertTrue(strategies.isNotEmpty())
            }
        }
        
        // Then: Strategy selection should be fast
        val avgSelectionTime = selectionTime.toDouble() / 100
        assertTrue(
            avgSelectionTime <= 10.0,
            "Average strategy selection time: ${avgSelectionTime}ms exceeds 10ms limit"
        )
    }
    
    @Test
    fun `benchmark large content processing performance`() = testScope.runTest {
        // Given: Large clipboard content (up to 10MB per Requirement 5.4)
        val largeSizes = listOf(1024 * 1024, 5 * 1024 * 1024, 10 * 1024 * 1024) // 1MB, 5MB, 10MB
        
        largeSizes.forEach { size ->
            val content = ClipboardContent(
                type = ClipboardContent.ContentType.TEXT,
                data = ByteArray(size) { (it % 256).toByte() },
                mimeType = "text/plain",
                source = "test",
                size = size.toLong()
            )
            
            // When: Process large content
            val processingTime = measureTimeMillis {
                val processor = TextProcessor()
                val result = processor.process(content)
                assertNotNull(result)
            }
            
            // Then: Should handle large content efficiently
            val processingRate = size.toDouble() / processingTime // bytes per ms
            assertTrue(
                processingRate >= 1000.0, // At least 1MB/s
                "Large content processing rate: ${processingRate}B/ms is too slow for ${size}B content"
            )
        }
    }
}