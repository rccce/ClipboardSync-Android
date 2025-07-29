package com.siw.clipboardsync.monitor

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class CpuUsageTrackerTest {

    private lateinit var cpuTracker: CpuUsageTracker

    @Before
    fun setup() {
        cpuTracker = CpuUsageTracker()
    }

    @Test
    fun `should track cycle times accurately`() {
        // Given
        val cycleTimes = listOf(10L, 15L, 20L, 12L, 18L)

        // When
        cycleTimes.forEach { cpuTracker.recordCycle(it) }
        val stats = cpuTracker.getCpuStats()

        // Then
        assertEquals(cycleTimes.size.toLong(), stats.totalCycles)
        assertEquals(cycleTimes.size, stats.recentSamples)
        assertEquals(cycleTimes.average(), stats.averageCycleTimeMs, 0.1)
        assertEquals(cycleTimes.maxOrNull()?.toDouble(), stats.maxCycleTimeMs)
        assertEquals(cycleTimes.minOrNull()?.toDouble(), stats.minCycleTimeMs)
    }

    @Test
    fun `should calculate CPU usage percentage correctly`() {
        // Given - simulate consistent cycle times
        repeat(10) {
            cpuTracker.recordCycle(5L) // 5ms cycles
        }

        // When
        val cpuUsage = cpuTracker.getAverageCpuUsage()

        // Then - should calculate reasonable CPU usage
        assertTrue("CPU usage should be positive", cpuUsage >= 0.0)
        assertTrue("CPU usage should be reasonable", cpuUsage <= 10.0) // Should be well under 10%
    }

    @Test
    fun `should adjust interval when CPU usage is high`() {
        // Given - simulate high CPU usage cycles
        repeat(20) {
            cpuTracker.recordCycle(100L) // 100ms cycles (very high)
        }

        val baseInterval = 1000L

        // When
        val adjustedInterval = cpuTracker.adjustIntervalForCpuBudget(baseInterval)
        val cpuUsage = cpuTracker.getAverageCpuUsage()

        // Then
        assertTrue("CPU usage should be detected as high", cpuUsage > 1.0)
        assertTrue("Interval should be increased for high CPU usage", 
                  adjustedInterval >= baseInterval)
    }

    @Test
    fun `should not adjust interval when CPU usage is acceptable`() {
        // Given - simulate low CPU usage cycles
        repeat(20) {
            cpuTracker.recordCycle(2L) // 2ms cycles (very low)
        }

        val baseInterval = 1000L

        // When
        val adjustedInterval = cpuTracker.adjustIntervalForCpuBudget(baseInterval)
        val cpuUsage = cpuTracker.getAverageCpuUsage()

        // Then
        assertTrue("CPU usage should be low", cpuUsage <= 1.0)
        assertEquals("Interval should not be adjusted for low CPU usage", 
                    baseInterval, adjustedInterval)
    }

    @Test
    fun `should maintain limited sample size`() {
        // Given - record more cycles than max sample size
        val maxSamples = 100
        val totalCycles = 150

        // When
        repeat(totalCycles) { cycle ->
            cpuTracker.recordCycle(cycle.toLong())
        }

        val stats = cpuTracker.getCpuStats()

        // Then
        assertEquals("Should track all cycles", totalCycles.toLong(), stats.totalCycles)
        assertTrue("Should limit recent samples", stats.recentSamples <= maxSamples)
    }

    @Test
    fun `should handle empty state gracefully`() {
        // When - no cycles recorded
        val cpuUsage = cpuTracker.getAverageCpuUsage()
        val stats = cpuTracker.getCpuStats()
        val adjustedInterval = cpuTracker.adjustIntervalForCpuBudget(1000L)

        // Then
        assertEquals("CPU usage should be zero when no cycles", 0.0, cpuUsage, 0.1)
        assertEquals("Total cycles should be zero", 0L, stats.totalCycles)
        assertEquals("Recent samples should be zero", 0, stats.recentSamples)
        assertEquals("Interval should not be adjusted", 1000L, adjustedInterval)
    }

    @Test
    fun `should provide accurate statistics`() {
        // Given
        val cycleTimes = listOf(5L, 10L, 15L, 8L, 12L, 20L, 6L, 9L, 11L, 14L)
        cycleTimes.forEach { cpuTracker.recordCycle(it) }

        // When
        val stats = cpuTracker.getCpuStats()

        // Then
        assertEquals(cycleTimes.size.toLong(), stats.totalCycles)
        assertEquals(cycleTimes.size, stats.recentSamples)
        assertEquals(cycleTimes.average(), stats.averageCycleTimeMs, 0.1)
        assertEquals(20.0, stats.maxCycleTimeMs, 0.1)
        assertEquals(5.0, stats.minCycleTimeMs, 0.1)
        assertTrue("Average usage should be calculated", stats.averageUsagePercent >= 0.0)
    }

    @Test
    fun `should handle extreme values gracefully`() {
        // Given - extreme cycle times
        cpuTracker.recordCycle(0L)     // Minimum
        cpuTracker.recordCycle(1000L)  // Very high
        cpuTracker.recordCycle(1L)     // Very low

        // When
        val stats = cpuTracker.getCpuStats()
        val cpuUsage = cpuTracker.getAverageCpuUsage()

        // Then - should handle without errors
        assertEquals(3L, stats.totalCycles)
        assertEquals(3, stats.recentSamples)
        assertTrue("Should handle extreme values", cpuUsage >= 0.0)
        assertEquals(1000.0, stats.maxCycleTimeMs, 0.1)
        assertEquals(0.0, stats.minCycleTimeMs, 0.1)
    }
}