package com.siw.clipboardsync.monitor

import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.siw.clipboardsync.monitor.model.*
import com.siw.clipboardsync.utils.TimeUtils
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
/**
 * Intelligent polling-based clipboard monitor that adapts polling frequency
 * based on user activity, battery state, and CPU usage optimization.
 * Designed to stay under 1% average CPU usage while providing responsive monitoring.
 */
class PollingClipboardMonitor(
    private val context: Context,
    private val timingOptimizer: TimingOptimizer
) : ClipboardMonitor {

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    
    private var monitoringJob: Job? = null
    private var listener: ClipboardListener? = null
    
    private val _isMonitoring = MutableStateFlow(false)
    
    // Adaptive polling configuration
    private val basePollingInterval = 1000L // 1 second base interval
    private val minPollingInterval = 500L   // Minimum 500ms for responsiveness
    private val maxPollingInterval = 10000L // Maximum 10 seconds for battery saving
    
    // Activity tracking
    private val lastUserActivity = AtomicLong(System.currentTimeMillis())
    private val lastClipboardContent = AtomicReference<String?>(null)
    private val consecutiveNoChanges = AtomicLong(0)
    
    // Performance tracking
    private val cpuUsageTracker = CpuUsageTracker()
    private val batteryOptimizer = BatteryOptimizer(context)
    
    override suspend fun startMonitoring() {
        if (_isMonitoring.value) {
            return
        }
        
        _isMonitoring.value = true
        
        // Initialize with current clipboard content
        lastClipboardContent.set(getCurrentClipboardText())
        lastUserActivity.set(System.currentTimeMillis())
        consecutiveNoChanges.set(0)
        
        monitoringJob = CoroutineScope(Dispatchers.Default).launch {
            startPollingLoop()
        }
    }
    
    override suspend fun stopMonitoring() {
        _isMonitoring.value = false
        monitoringJob?.cancel()
        monitoringJob = null
        listener = null
    }
    
    override fun isMonitoring(): Boolean {
        return _isMonitoring.value
    }
    
    override fun getMonitoringMethod(): MonitoringMethod {
        return MonitoringMethod.POLLING_FALLBACK
    }
    
    override fun setClipboardListener(listener: ClipboardListener) {
        this.listener = listener
    }
    
    private suspend fun startPollingLoop() {
        while (_isMonitoring.value && !Thread.currentThread().isInterrupted) {
            try {
                val startTime = System.nanoTime()
                
                // Check for clipboard changes
                val currentContent = getCurrentClipboardText()
                val previousContent = lastClipboardContent.get()
                
                if (currentContent != previousContent && currentContent != null) {
                    handleClipboardChange(currentContent)
                    lastClipboardContent.set(currentContent)
                    lastUserActivity.set(System.currentTimeMillis())
                    consecutiveNoChanges.set(0)
                } else {
                    consecutiveNoChanges.incrementAndGet()
                }
                
                // Track CPU usage for this polling cycle
                val endTime = System.nanoTime()
                val cycleTimeMs = (endTime - startTime) / 1_000_000
                cpuUsageTracker.recordCycle(cycleTimeMs)
                
                // Calculate next polling interval
                val nextInterval = calculateAdaptiveInterval()
                
                // Ensure we don't exceed CPU budget
                val adjustedInterval = cpuUsageTracker.adjustIntervalForCpuBudget(nextInterval)
                
                delay(adjustedInterval)
                
            } catch (e: Exception) {
                listener?.onMonitoringError(
                    ClipboardError.PollingError("Polling cycle failed", e)
                )
                
                // Back off on errors to prevent spam
                delay(basePollingInterval * 2)
            }
        }
    }
    
    private suspend fun handleClipboardChange(content: String) {
        val clipboardContent = ClipboardContent(
            type = ClipboardContent.ContentType.TEXT,
            data = content.toByteArray(),
            mimeType = "text/plain",
            timestamp = System.currentTimeMillis(),
            source = "polling_monitor",
            size = content.length.toLong()
        )
        
        // Apply timing optimization
        val readDelay = timingOptimizer.getOptimalReadDelay()
        if (readDelay > 0) {
            delay(readDelay)
        }
        
        // Check debouncing
        if (!timingOptimizer.shouldDebounce(clipboardContent.timestamp)) {
            listener?.onClipboardChanged(clipboardContent, clipboardContent.timestamp)
        }
    }
    
    private fun calculateAdaptiveInterval(): Long {
        val timeSinceLastActivity = System.currentTimeMillis() - lastUserActivity.get()
        val noChangeCount = consecutiveNoChanges.get()
        
        // Base interval calculation
        var interval = basePollingInterval
        
        // Increase interval based on inactivity
        when {
            timeSinceLastActivity > 300_000 -> interval *= 8  // 5+ minutes inactive
            timeSinceLastActivity > 120_000 -> interval *= 4  // 2+ minutes inactive
            timeSinceLastActivity > 60_000 -> interval *= 2   // 1+ minute inactive
        }
        
        // Increase interval based on consecutive no-changes
        when {
            noChangeCount > 100 -> interval *= 3
            noChangeCount > 50 -> interval *= 2
            noChangeCount > 20 -> interval = (interval * 1.5).toLong()
        }
        
        // Apply battery optimization
        interval = batteryOptimizer.adjustIntervalForBattery(interval)
        
        // Apply power mode adjustments
        if (powerManager.isPowerSaveMode) {
            interval = timingOptimizer.adjustForPowerMode(interval)
        }
        
        // Ensure within bounds
        return interval.coerceIn(minPollingInterval, maxPollingInterval)
    }
    
    private fun getCurrentClipboardText(): String? {
        return try {
            if (!clipboardManager.hasPrimaryClip()) {
                return null
            }
            
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                item?.text?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Updates user activity timestamp - called by external activity detectors
     */
    fun notifyUserActivity() {
        lastUserActivity.set(System.currentTimeMillis())
    }
    
    /**
     * Gets current polling statistics for monitoring
     */
    fun getPollingStats(): PollingStats {
        return PollingStats(
            currentInterval = calculateAdaptiveInterval(),
            averageCpuUsage = cpuUsageTracker.getAverageCpuUsage(),
            consecutiveNoChanges = consecutiveNoChanges.get(),
            timeSinceLastActivity = System.currentTimeMillis() - lastUserActivity.get(),
            batteryOptimizationActive = batteryOptimizer.isOptimizationActive()
        )
    }
}