package com.siw.clipboardsync.monitor

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Tracks CPU usage for polling operations and adjusts intervals to maintain
 * target CPU usage below 1% average.
 */
class CpuUsageTracker {
    
    private val targetCpuUsagePercent = 1.0 // Target 1% CPU usage
    private val maxSampleSize = 100
    private val cycleTimes = ConcurrentLinkedQueue<Long>()
    private val totalCycles = AtomicLong(0)
    
    /**
     * Records the time taken for a polling cycle
     */
    fun recordCycle(cycleTimeMs: Long) {
        cycleTimes.offer(cycleTimeMs)
        totalCycles.incrementAndGet()
        
        // Keep only recent samples
        while (cycleTimes.size > maxSampleSize) {
            cycleTimes.poll()
        }
    }
    
    /**
     * Calculates average CPU usage percentage based on recent cycles
     */
    fun getAverageCpuUsage(): Double {
        if (cycleTimes.isEmpty()) return 0.0
        
        val samples = cycleTimes.toList()
        val averageCycleTime = samples.average()
        
        // Estimate CPU usage: (active_time / total_time) * 100
        // Assuming polling interval is roughly the total time between cycles
        val estimatedInterval = max(averageCycleTime * 10, 500.0) // Rough estimate
        return (averageCycleTime / estimatedInterval) * 100.0
    }
    
    /**
     * Adjusts polling interval to maintain target CPU usage
     */
    fun adjustIntervalForCpuBudget(proposedInterval: Long): Long {
        val currentCpuUsage = getAverageCpuUsage()
        
        if (currentCpuUsage <= targetCpuUsagePercent) {
            return proposedInterval
        }
        
        // If CPU usage is too high, increase interval proportionally
        val adjustmentFactor = currentCpuUsage / targetCpuUsagePercent
        val adjustedInterval = (proposedInterval * adjustmentFactor).toLong()
        
        return max(adjustedInterval, proposedInterval)
    }
    
    /**
     * Gets detailed CPU usage statistics
     */
    fun getCpuStats(): CpuStats {
        val samples = cycleTimes.toList()
        return CpuStats(
            averageUsagePercent = getAverageCpuUsage(),
            totalCycles = totalCycles.get(),
            recentSamples = samples.size,
            averageCycleTimeMs = if (samples.isNotEmpty()) samples.average() else 0.0,
            maxCycleTimeMs = samples.maxOrNull()?.toDouble() ?: 0.0,
            minCycleTimeMs = samples.minOrNull()?.toDouble() ?: 0.0
        )
    }
    
    data class CpuStats(
        val averageUsagePercent: Double,
        val totalCycles: Long,
        val recentSamples: Int,
        val averageCycleTimeMs: Double,
        val maxCycleTimeMs: Double,
        val minCycleTimeMs: Double
    )
}