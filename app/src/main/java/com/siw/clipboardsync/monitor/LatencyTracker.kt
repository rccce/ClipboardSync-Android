package com.siw.clipboardsync.monitor

import android.util.Log
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks detection latency for clipboard monitoring methods.
 * Provides latency measurement infrastructure for verifying detection performance.
 * 
 * Requirements: 2.3, 6.6
 */
@Singleton
class LatencyTracker @Inject constructor() {
    
    companion object {
        private const val TAG = "LatencyTracker"
        private const val MAX_SAMPLES = 100
        
        // Target latencies by method (in milliseconds)
        const val TARGET_LATENCY_ROOT = 100L      // Root methods should detect within 100ms
        const val TARGET_LATENCY_XPOSED = 100L    // Xposed should detect within 100ms
        const val TARGET_LATENCY_READ_LOGS = 500L // READ_LOGS within 500ms
        const val TARGET_LATENCY_ACCESSIBILITY = 500L // Accessibility within 500ms
        const val TARGET_LATENCY_POLLING = 1000L  // Polling within 1 second
    }
    
    // Latency samples per monitoring method
    private val latencySamples = ConcurrentHashMap<MonitoringMethod, ConcurrentLinkedQueue<LatencySample>>()
    
    // Pending clipboard changes awaiting detection
    private val pendingChanges = ConcurrentHashMap<String, Long>() // contentHash -> timestamp
    
    // Statistics
    private val totalDetections = AtomicLong(0)
    private val missedDetections = AtomicLong(0)
    
    /**
     * Records when a clipboard change is initiated (for latency measurement).
     * Call this when clipboard content is set programmatically for testing.
     */
    fun recordClipboardChangeInitiated(contentHash: String) {
        pendingChanges[contentHash] = System.currentTimeMillis()
        Log.d(TAG, "Recorded clipboard change initiated: $contentHash")
    }
    
    /**
     * Records when a clipboard change is detected.
     * Calculates latency if the change was previously recorded.
     */
    fun recordClipboardChangeDetected(
        contentHash: String,
        method: MonitoringMethod,
        detectionTimestamp: Long = System.currentTimeMillis()
    ) {
        val initiatedTimestamp = pendingChanges.remove(contentHash)
        
        if (initiatedTimestamp != null) {
            val latency = detectionTimestamp - initiatedTimestamp
            recordLatencySample(method, latency, contentHash)
            Log.d(TAG, "Recorded detection latency: ${latency}ms for method ${method.name}")
        } else {
            // External clipboard change - record with unknown initiation time
            Log.d(TAG, "Detected external clipboard change via ${method.name}")
        }
        
        totalDetections.incrementAndGet()
    }
    
    /**
     * Records a latency sample for a monitoring method.
     */
    private fun recordLatencySample(method: MonitoringMethod, latencyMs: Long, contentHash: String) {
        val samples = latencySamples.getOrPut(method) { ConcurrentLinkedQueue() }
        
        samples.offer(LatencySample(
            latencyMs = latencyMs,
            timestamp = System.currentTimeMillis(),
            contentHash = contentHash
        ))
        
        // Keep only recent samples
        while (samples.size > MAX_SAMPLES) {
            samples.poll()
        }
    }
    
    /**
     * Records a missed detection (timeout).
     */
    fun recordMissedDetection(contentHash: String, method: MonitoringMethod) {
        pendingChanges.remove(contentHash)
        missedDetections.incrementAndGet()
        Log.w(TAG, "Missed detection for $contentHash via ${method.name}")
    }
    
    /**
     * Gets latency statistics for a monitoring method.
     */
    fun getLatencyStats(method: MonitoringMethod): LatencyStats {
        val samples = latencySamples[method]?.toList() ?: emptyList()
        
        if (samples.isEmpty()) {
            return LatencyStats(
                method = method,
                sampleCount = 0,
                averageLatencyMs = 0.0,
                minLatencyMs = 0L,
                maxLatencyMs = 0L,
                p50LatencyMs = 0L,
                p95LatencyMs = 0L,
                p99LatencyMs = 0L,
                targetLatencyMs = getTargetLatency(method),
                meetsTarget = true
            )
        }
        
        val latencies = samples.map { it.latencyMs }.sorted()
        val targetLatency = getTargetLatency(method)
        val avgLatency = latencies.average()
        
        return LatencyStats(
            method = method,
            sampleCount = samples.size,
            averageLatencyMs = avgLatency,
            minLatencyMs = latencies.first(),
            maxLatencyMs = latencies.last(),
            p50LatencyMs = percentile(latencies, 50),
            p95LatencyMs = percentile(latencies, 95),
            p99LatencyMs = percentile(latencies, 99),
            targetLatencyMs = targetLatency,
            meetsTarget = avgLatency <= targetLatency
        )
    }
    
    /**
     * Gets latency statistics for all methods.
     */
    fun getAllLatencyStats(): Map<MonitoringMethod, LatencyStats> {
        return MonitoringMethod.values().associateWith { getLatencyStats(it) }
    }
    
    /**
     * Gets the target latency for a monitoring method.
     */
    fun getTargetLatency(method: MonitoringMethod): Long {
        return when (method) {
            MonitoringMethod.SYSTEM_HOOKS -> TARGET_LATENCY_ROOT
            MonitoringMethod.XPOSED_HOOKS -> TARGET_LATENCY_XPOSED
            MonitoringMethod.READ_LOGS -> TARGET_LATENCY_READ_LOGS
            MonitoringMethod.ACCESSIBILITY_SERVICE -> TARGET_LATENCY_ACCESSIBILITY
            MonitoringMethod.FOREGROUND_SERVICE -> TARGET_LATENCY_POLLING
            MonitoringMethod.POLLING_FALLBACK -> TARGET_LATENCY_POLLING
        }
    }
    
    /**
     * Checks if a method meets its latency target.
     */
    fun meetsLatencyTarget(method: MonitoringMethod): Boolean {
        return getLatencyStats(method).meetsTarget
    }
    
    /**
     * Gets overall detection statistics.
     */
    fun getOverallStats(): OverallLatencyStats {
        val allStats = getAllLatencyStats()
        val methodsWithData = allStats.filter { it.value.sampleCount > 0 }
        
        return OverallLatencyStats(
            totalDetections = totalDetections.get(),
            missedDetections = missedDetections.get(),
            detectionRate = if (totalDetections.get() > 0) {
                (totalDetections.get() - missedDetections.get()).toDouble() / totalDetections.get()
            } else 1.0,
            methodStats = allStats,
            bestMethod = methodsWithData.minByOrNull { it.value.averageLatencyMs }?.key,
            allMethodsMeetTarget = methodsWithData.all { it.value.meetsTarget }
        )
    }
    
    /**
     * Clears all latency data.
     */
    fun clearData() {
        latencySamples.clear()
        pendingChanges.clear()
        totalDetections.set(0)
        missedDetections.set(0)
        Log.d(TAG, "Latency data cleared")
    }
    
    /**
     * Calculates percentile from sorted list.
     */
    private fun percentile(sortedValues: List<Long>, percentile: Int): Long {
        if (sortedValues.isEmpty()) return 0L
        val index = (percentile / 100.0 * (sortedValues.size - 1)).toInt()
        return sortedValues[index.coerceIn(0, sortedValues.lastIndex)]
    }
}

/**
 * A single latency measurement sample.
 */
data class LatencySample(
    val latencyMs: Long,
    val timestamp: Long,
    val contentHash: String
)

/**
 * Latency statistics for a monitoring method.
 */
data class LatencyStats(
    val method: MonitoringMethod,
    val sampleCount: Int,
    val averageLatencyMs: Double,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val p99LatencyMs: Long,
    val targetLatencyMs: Long,
    val meetsTarget: Boolean
) {
    fun getSummary(): String {
        return buildString {
            appendLine("Method: ${method.name}")
            appendLine("Samples: $sampleCount")
            appendLine("Average: ${"%.1f".format(averageLatencyMs)}ms")
            appendLine("Min/Max: ${minLatencyMs}ms / ${maxLatencyMs}ms")
            appendLine("P50/P95/P99: ${p50LatencyMs}ms / ${p95LatencyMs}ms / ${p99LatencyMs}ms")
            appendLine("Target: ${targetLatencyMs}ms (${if (meetsTarget) "✓ Met" else "✗ Not Met"})")
        }
    }
}

/**
 * Overall latency statistics across all methods.
 */
data class OverallLatencyStats(
    val totalDetections: Long,
    val missedDetections: Long,
    val detectionRate: Double,
    val methodStats: Map<MonitoringMethod, LatencyStats>,
    val bestMethod: MonitoringMethod?,
    val allMethodsMeetTarget: Boolean
)
