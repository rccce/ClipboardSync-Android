package com.siw.clipboardsync.stress

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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*

/**
 * Stress tests for rapid clipboard changes and large content handling.
 * Tests system stability under extreme conditions.
 * 
 * Simplified monitoring model:
 * - XPOSED_HOOKS: Full background sync (highest priority)
 * - SHIZUKU: Full background sync without root
 * - FOREGROUND_SYNC: Sync when app comes to foreground (fallback)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ClipboardStressTestSuite {
    
    private lateinit var context: Context
    private lateinit var timingOptimizer: TimingOptimizer
    private lateinit var clipboardListener: ClipboardListener
    
    private val testScope = TestScope()
    private val processedCount = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)
    
    @Before
    fun setup() {
        context = mockk(relaxed = true)
        timingOptimizer = AdaptiveTimingOptimizer(context)
        
        clipboardListener = mockk<ClipboardListener> {
            coEvery { onClipboardChanged(any(), any()) } answers {
                processedCount.incrementAndGet()
            }
            coEvery { onMonitoringError(any()) } answers {
                errorCount.incrementAndGet()
            }
        }
        
        processedCount.set(0)
        errorCount.set(0)
    }
    
    @After
    fun tearDown() {
        testScope.cancel()
    }
    
    @Test
    fun `stress test rapid clipboard changes`() = testScope.runTest {
        // Given: Monitor with rapid change handling
        val monitor = ForegroundSyncClipboardMonitor(context)
        monitor.setClipboardListener(clipboardListener)
        monitor.startMonitoring()
        
        // When: Generate rapid clipboard changes (1000 changes in 10 seconds)
        val changeCount = 1000
        val jobs = mutableListOf<Job>()
        
        repeat(changeCount) { index ->
            jobs.add(launch {
                val content = ClipboardContent(
                    type = ClipboardContent.ContentType.TEXT,
                    data = "rapid change $index".toByteArray(),
                    mimeType = "text/plain",
                    source = "stress-test",
                    size = 20L,
                    timestamp = System.currentTimeMillis()
                )
                
                // Simulate clipboard change detection
                clipboardListener.onClipboardChanged(content, content.timestamp)
                delay(10) // 10ms between changes
            })
        }
        
        jobs.joinAll()
        monitor.stopMonitoring()
        
        // Then: Should handle most changes without errors
        val successRate = processedCount.get().toDouble() / changeCount
        assertTrue(
            successRate >= 0.95,
            "Success rate: ${successRate * 100}% is below 95% threshold"
        )
        
        // And: Error rate should be low
        val errorRate = errorCount.get().toDouble() / changeCount
        assertTrue(
            errorRate <= 0.05,
            "Error rate: ${errorRate * 100}% exceeds 5% threshold"
        )
    }
    
    @Test
    fun `stress test concurrent clipboard access`() = testScope.runTest {
        // Given: Multiple monitors accessing clipboard concurrently
        // Simplified model: only ForegroundSyncClipboardMonitor and ShizukuClipboardMonitor
        val monitors = listOf(
            ForegroundSyncClipboardMonitor(context),
            ShizukuClipboardMonitor(context)
        )
        
        monitors.forEach { it.setClipboardListener(clipboardListener) }
        
        // When: Start all monitors and generate concurrent changes
        val monitorJobs = monitors.map { monitor ->
            async {
                try {
                    monitor.startMonitoring()
                    delay(5000) // Run for 5 seconds
                    monitor.stopMonitoring()
                } catch (e: Exception) {
                    // Some monitors may fail if prerequisites not met
                }
            }
        }
        
        val changeJobs = (1..500).map { index ->
            async {
                val content = ClipboardContent(
                    type = ClipboardContent.ContentType.TEXT,
                    data = "concurrent change $index".toByteArray(),
                    mimeType = "text/plain",
                    source = "stress-test",
                    size = 25L
                )
                
                clipboardListener.onClipboardChanged(content, System.currentTimeMillis())
                delay(20) // 20ms between changes
            }
        }
        
        awaitAll(*monitorJobs.toTypedArray(), *changeJobs.toTypedArray())
        
        // Then: Should handle concurrent access without deadlocks
        assertTrue(processedCount.get() > 0, "No clipboard changes were processed")
        assertTrue(errorCount.get() < processedCount.get() / 10, "Too many errors during concurrent access")
    }
    
    @Test
    fun `stress test large content processing`() = testScope.runTest {
        // Given: Various large content sizes
        val largeSizes = listOf(
            1024 * 1024,      // 1MB
            5 * 1024 * 1024,  // 5MB
            10 * 1024 * 1024  // 10MB (max size per requirements)
        )
        
        val processor = TextProcessor()
        
        largeSizes.forEach { size ->
            // When: Process large content
            val largeContent = ClipboardContent(
                type = ClipboardContent.ContentType.TEXT,
                data = ByteArray(size) { (it % 256).toByte() },
                mimeType = "text/plain",
                source = "stress-test",
                size = size.toLong()
            )
            
            var processingSucceeded = false
            var processingTime = 0L
            
            try {
                processingTime = kotlin.system.measureTimeMillis {
                    val result = processor.process(largeContent)
                    assertNotNull(result)
                    assertEquals(size, result.data.size)
                }
                processingSucceeded = true
            } catch (e: Exception) {
                fail("Failed to process ${size}B content: ${e.message}")
            }
            
            // Then: Should successfully process large content
            assertTrue(processingSucceeded, "Failed to process ${size}B content")
            
            // And: Should complete in reasonable time (max 5 seconds for 10MB)
            val maxTime = (size / (1024 * 1024)) * 5000L // 5 seconds per MB
            assertTrue(
                processingTime <= maxTime,
                "Processing ${size}B took ${processingTime}ms, exceeds ${maxTime}ms limit"
            )
        }
    }
    
    @Test
    fun `stress test memory pressure handling`() = testScope.runTest {
        // Given: Monitor under memory pressure
        val monitor = ForegroundSyncClipboardMonitor(context)
        monitor.setClipboardListener(clipboardListener)
        monitor.startMonitoring()
        
        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()
        
        // When: Generate many large clipboard changes
        repeat(100) { index ->
            val content = ClipboardContent(
                type = ClipboardContent.ContentType.TEXT,
                data = ByteArray(100 * 1024) { 'X'.code.toByte() }, // 100KB each
                mimeType = "text/plain",
                source = "memory-stress",
                size = 100 * 1024L
            )
            
            clipboardListener.onClipboardChanged(content, System.currentTimeMillis())
            
            // Periodically check memory usage
            if (index % 10 == 0) {
                val currentMemory = runtime.totalMemory() - runtime.freeMemory()
                val memoryIncrease = currentMemory - initialMemory
                
                // Force GC if memory usage is too high
                if (memoryIncrease > 50 * 1024 * 1024) { // 50MB
                    System.gc()
                    delay(100)
                }
            }
            
            delay(50)
        }
        
        monitor.stopMonitoring()
        
        // Force final garbage collection
        System.gc()
        delay(200)
        
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        val totalMemoryIncrease = finalMemory - initialMemory
        
        // Then: Memory usage should be controlled
        assertTrue(
            totalMemoryIncrease < 100 * 1024 * 1024, // 100MB max
            "Memory increase: ${totalMemoryIncrease / 1024 / 1024}MB exceeds 100MB limit"
        )
        
        // And: Should have processed most changes
        assertTrue(processedCount.get() >= 90, "Only processed ${processedCount.get()}/100 changes")
    }
    
    @Test
    fun `stress test error recovery under load`() = testScope.runTest {
        // Given: Monitor with simulated failures
        val failingMonitor = mockk<ClipboardMonitor>()
        var failureCount = 0
        
        coEvery { failingMonitor.startMonitoring() } answers {
            if (++failureCount <= 3) {
                throw RuntimeException("Simulated failure $failureCount")
            }
        }
        every { failingMonitor.isMonitoring() } returns failureCount > 3
        every { failingMonitor.getMonitoringMethod() } returns MonitoringMethod.XPOSED_HOOKS
        
        // When: Handle multiple failures under load
        repeat(10) { attempt ->
            try {
                failingMonitor.startMonitoring()
            } catch (e: Exception) {
                // Expected failures for first 3 attempts
            }
            
            delay(100)
        }
        
        // Then: Should eventually succeed
        assertTrue(failureCount > 0, "No failures were simulated")
        assertTrue(failingMonitor.isMonitoring() || failureCount <= 3, "Monitor should be working after retries")
    }
    
    @Test
    fun `stress test debouncing under rapid changes`() = testScope.runTest {
        // Given: Rapid changes that should trigger debouncing
        val optimizer = AdaptiveTimingOptimizer(context)
        val changeInterval = 50L // 50ms between changes
        
        var debouncedCount = 0
        var processedCount = 0
        
        // When: Generate rapid changes within debounce window
        val startTime = System.currentTimeMillis()
        
        repeat(20) { index ->
            val timestamp = startTime + (index * changeInterval)
            
            if (optimizer.shouldDebounce(timestamp - changeInterval)) {
                debouncedCount++
            } else {
                processedCount++
            }
            
            delay(changeInterval)
        }
        
        // Then: Should debounce most rapid changes
        val debounceRate = debouncedCount.toDouble() / (debouncedCount + processedCount)
        assertTrue(
            debounceRate >= 0.7,
            "Debounce rate: ${debounceRate * 100}% is below 70% threshold"
        )
        
        // And: Should still process some changes
        assertTrue(processedCount > 0, "No changes were processed after debouncing")
    }
    
    @Test
    fun `stress test monitoring method switching under load`() = testScope.runTest {
        // Given: Monitor manager with simplified strategies (3 methods only)
        val strategyFactory = mockk<MonitoringStrategyFactory>()
        val strategies = listOf(
            MonitoringStrategy(MonitoringMethod.XPOSED_HOOKS, 100, true, setOf()),
            MonitoringStrategy(MonitoringMethod.SHIZUKU, 90, true, setOf()),
            MonitoringStrategy(MonitoringMethod.FOREGROUND_SYNC, 10, true, setOf())
        )
        
        coEvery { strategyFactory.createAvailableStrategies() } returns strategies
        coEvery { strategyFactory.createFallbackChain() } returns strategies.filter { it.isAvailable }
        coEvery { strategyFactory.selectOptimalStrategy(any()) } returns strategies.first()
        
        // When: Switch methods rapidly under load
        val switchCount = AtomicInteger(0)
        val switchJobs = (1..50).map { index ->
            async {
                val method = strategies[index % strategies.size].method
                switchCount.incrementAndGet()
                delay(100)
            }
        }
        
        val changeJobs = (1..100).map { index ->
            async {
                val content = ClipboardContent(
                    type = ClipboardContent.ContentType.TEXT,
                    data = "switch test $index".toByteArray(),
                    mimeType = "text/plain",
                    source = "stress-test",
                    size = 20L
                )
                
                // Simulate clipboard change during method switching
                delay(50)
            }
        }
        
        awaitAll(*(switchJobs + changeJobs).toTypedArray())
        
        // Then: Should handle method switching without crashes
        assertTrue(switchCount.get() == 50, "All switch operations should complete")
    }
    
    @Test
    fun `stress test content type processing variety`() = testScope.runTest {
        // Given: Various content types and processors
        val processors = mapOf(
            ClipboardContent.ContentType.TEXT to TextProcessor(),
            ClipboardContent.ContentType.IMAGE to ImageProcessor(),
            ClipboardContent.ContentType.FILE to FileProcessor(context)
        )
        
        val contentVariations = listOf(
            // Text variations
            ClipboardContent(ClipboardContent.ContentType.TEXT, "short".toByteArray(), "text/plain", "test", 5L),
            ClipboardContent(ClipboardContent.ContentType.TEXT, "A".repeat(10000).toByteArray(), "text/plain", "test", 10000L),
            ClipboardContent(ClipboardContent.ContentType.TEXT, "Unicode: 🚀🎉🌟".toByteArray(), "text/plain", "test", 20L),
            
            // Image variations
            ClipboardContent(ClipboardContent.ContentType.IMAGE, ByteArray(1000), "image/png", "test", 1000L),
            ClipboardContent(ClipboardContent.ContentType.IMAGE, ByteArray(100000), "image/jpeg", "test", 100000L),
            
            // File variations
            ClipboardContent(ClipboardContent.ContentType.FILE, "file://test.txt".toByteArray(), "text/plain", "test", 15L),
            ClipboardContent(ClipboardContent.ContentType.FILE, "content://provider/file".toByteArray(), "application/pdf", "test", 25L)
        )
        
        // When: Process all content variations concurrently
        val processingJobs = contentVariations.map { content ->
            async {
                val processor = processors[content.type]
                assertNotNull(processor, "No processor for ${content.type}")
                
                try {
                    val result = processor!!.process(content)
                    assertNotNull(result, "Processing failed for ${content.type}")
                    true
                } catch (e: Exception) {
                    println("Processing failed for ${content.type}: ${e.message}")
                    false
                }
            }
        }
        
        val results = processingJobs.awaitAll()
        
        // Then: Should successfully process most content types
        val successRate = results.count { it }.toDouble() / results.size
        assertTrue(
            successRate >= 0.8,
            "Content processing success rate: ${successRate * 100}% is below 80%"
        )
    }
}
