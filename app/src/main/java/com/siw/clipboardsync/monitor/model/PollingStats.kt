package com.siw.clipboardsync.monitor.model

/**
 * Statistics and metrics for polling-based clipboard monitoring.
 */
data class PollingStats(
    val currentInterval: Long,
    val averageCpuUsage: Double,
    val consecutiveNoChanges: Long,
    val timeSinceLastActivity: Long,
    val batteryOptimizationActive: Boolean
) {
    /**
     * Gets a human-readable summary of polling performance
     */
    fun getSummary(): String {
        return buildString {
            appendLine("Polling Interval: ${currentInterval}ms")
            appendLine("CPU Usage: ${"%.2f".format(averageCpuUsage)}%")
            appendLine("No Changes: $consecutiveNoChanges cycles")
            appendLine("Last Activity: ${timeSinceLastActivity / 1000}s ago")
            appendLine("Battery Optimization: ${if (batteryOptimizationActive) "Active" else "Inactive"}")
        }
    }
    
    /**
     * Determines if polling is performing efficiently
     */
    fun isPerformingWell(): Boolean {
        return averageCpuUsage <= 1.0 && currentInterval >= 500L
    }
    
    /**
     * Gets performance rating from 1-5 (5 being best)
     */
    fun getPerformanceRating(): Int {
        return when {
            averageCpuUsage > 2.0 -> 1 // Poor - too much CPU
            averageCpuUsage > 1.5 -> 2 // Below average
            averageCpuUsage > 1.0 -> 3 // Average
            averageCpuUsage > 0.5 -> 4 // Good
            else -> 5 // Excellent
        }
    }
}