package com.siw.clipboardsync.monitor.model

/**
 * Statistics and metrics for polling-based clipboard monitoring.
 * 
 * Requirements: 6.2, 6.3, 6.4, 6.5, 9.1
 */
data class PollingStats(
    val currentInterval: Long,
    val averageCpuUsage: Double,
    val consecutiveNoChanges: Long,
    val timeSinceLastActivity: Long,
    val batteryOptimizationActive: Boolean,
    val isInDozeMode: Boolean = false,
    val recentClipboardChanges: Long = 0,
    val isScreenOn: Boolean = true
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
            appendLine("Doze Mode: ${if (isInDozeMode) "Active" else "Inactive"}")
            appendLine("Recent Changes: $recentClipboardChanges (last minute)")
            appendLine("Screen: ${if (isScreenOn) "On" else "Off"}")
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
    
    /**
     * Determines if the monitor is in power-saving mode
     */
    fun isInPowerSavingMode(): Boolean {
        return batteryOptimizationActive || isInDozeMode || !isScreenOn
    }
    
    /**
     * Gets the activity level description
     */
    fun getActivityLevel(): String {
        return when {
            recentClipboardChanges >= 5 -> "Very Active"
            recentClipboardChanges >= 3 -> "Active"
            recentClipboardChanges >= 1 -> "Moderate"
            timeSinceLastActivity < 60_000 -> "Recent"
            timeSinceLastActivity < 300_000 -> "Idle"
            else -> "Inactive"
        }
    }
}