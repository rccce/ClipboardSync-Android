package com.siw.clipboardsync.monitor

import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.siw.clipboardsync.monitor.model.*
import com.siw.clipboardsync.utils.TimeUtils
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Intelligent polling-based clipboard monitor that adapts polling frequency
 * based on user activity, battery state, and CPU usage optimization.
 * Designed to stay under 1% average CPU usage while providing responsive monitoring.
 * 
 * Requirements: 6.2, 6.3, 6.4, 6.5, 9.1, 9.2
 */
class PollingClipboardMonitor(
    private val context: Context,
    private val timingOptimizer: TimingOptimizer
) : ClipboardMonitor {

    companion object {
        private const val TAG = "PollingClipboardMonitor"
    }

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
    private val activeUserInterval = 300L   // 300ms when user is actively using clipboard
    
    // Activity tracking
    private val lastUserActivity = AtomicLong(System.currentTimeMillis())
    private val lastClipboardContent = AtomicReference<String?>(null)
    private val consecutiveNoChanges = AtomicLong(0)
    private val lastClipboardChangeTime = AtomicLong(0)
    private val recentClipboardChanges = AtomicLong(0) // Changes in last minute
    
    // User activity state
    private val isUserActive = AtomicBoolean(false)
    private val screenOnTime = AtomicLong(System.currentTimeMillis())
    private val isScreenOn = AtomicBoolean(true)
    
    // Performance tracking
    private val cpuUsageTracker = CpuUsageTracker()
    private val batteryOptimizer = BatteryOptimizer(context)
    
    // Doze mode tracking
    private val isInDozeMode = AtomicBoolean(false)
    
    override suspend fun startMonitoring() {
        if (_isMonitoring.value) {
            return
        }
        
        _isMonitoring.value = true
        
        // Initialize with current clipboard content
        lastClipboardContent.set(getCurrentClipboardText())
        lastUserActivity.set(System.currentTimeMillis())
        consecutiveNoChanges.set(0)
        recentClipboardChanges.set(0)
        
        // Check initial doze mode state
        updateDozeMode()
        
        monitoringJob = CoroutineScope(Dispatchers.Default).launch {
            startPollingLoop()
        }
        
        Log.i(TAG, "Polling monitor started with adaptive intervals")
    }
    
    override suspend fun stopMonitoring() {
        _isMonitoring.value = false
        monitoringJob?.cancel()
        monitoringJob = null
        listener = null
        Log.i(TAG, "Polling monitor stopped")
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
                
                // Update doze mode state periodically
                updateDozeMode()
                
                // Check for clipboard changes
                val currentContent = getCurrentClipboardText()
                val previousContent = lastClipboardContent.get()
                
                if (currentContent != previousContent && currentContent != null) {
                    handleClipboardChange(currentContent)
                    lastClipboardContent.set(currentContent)
                    lastUserActivity.set(System.currentTimeMillis())
                    consecutiveNoChanges.set(0)
                    
                    // Track recent changes for activity detection
                    val now = System.currentTimeMillis()
                    lastClipboardChangeTime.set(now)
                    recentClipboardChanges.incrementAndGet()
                    
                    // Decay recent changes count after 1 minute
                    CoroutineScope(Dispatchers.Default).launch {
                        delay(60_000)
                        recentClipboardChanges.decrementAndGet()
                    }
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
                Log.e(TAG, "Polling cycle failed", e)
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
        val now = System.currentTimeMillis()
        val timeSinceLastActivity = now - lastUserActivity.get()
        val timeSinceLastChange = now - lastClipboardChangeTime.get()
        val noChangeCount = consecutiveNoChanges.get()
        val recentChanges = recentClipboardChanges.get()
        
        // Check if user is actively using clipboard (multiple changes recently)
        val isActiveClipboardUser = recentChanges >= 3 && timeSinceLastChange < 30_000
        
        // Base interval calculation
        var interval = when {
            // Very active clipboard usage - poll frequently
            isActiveClipboardUser -> activeUserInterval
            // Recent activity - use base interval
            timeSinceLastActivity < 30_000 -> basePollingInterval
            // Default base
            else -> basePollingInterval
        }
        
        // Increase interval based on inactivity (only if not active user)
        if (!isActiveClipboardUser) {
            when {
                timeSinceLastActivity > 600_000 -> interval *= 10 // 10+ minutes inactive
                timeSinceLastActivity > 300_000 -> interval *= 8  // 5+ minutes inactive
                timeSinceLastActivity > 120_000 -> interval *= 4  // 2+ minutes inactive
                timeSinceLastActivity > 60_000 -> interval *= 2   // 1+ minute inactive
            }
        }
        
        // Increase interval based on consecutive no-changes
        when {
            noChangeCount > 200 -> interval *= 4
            noChangeCount > 100 -> interval *= 3
            noChangeCount > 50 -> interval *= 2
            noChangeCount > 20 -> interval = (interval * 1.5).toLong()
        }
        
        // Apply battery optimization
        interval = batteryOptimizer.adjustIntervalForBattery(interval)
        
        // Apply power save mode adjustments
        if (powerManager.isPowerSaveMode) {
            interval = timingOptimizer.adjustForPowerMode(interval)
        }
        
        // Apply doze mode adjustments
        if (isInDozeMode.get()) {
            interval = adjustForDozeMode(interval)
        }
        
        // Screen off optimization
        if (!isScreenOn.get()) {
            interval = (interval * 2).coerceAtMost(maxPollingInterval)
        }
        
        // Ensure within bounds
        val finalInterval = interval.coerceIn(minPollingInterval, maxPollingInterval)
        
        Log.v(TAG, "Calculated interval: ${finalInterval}ms (activity=${timeSinceLastActivity}ms, changes=$noChangeCount, recentChanges=$recentChanges)")
        
        return finalInterval
    }
    
    /**
     * Adjusts interval for doze mode.
     * In doze mode, we significantly reduce polling to save battery.
     */
    private fun adjustForDozeMode(interval: Long): Long {
        // In doze mode, multiply interval by 5 but cap at max
        return (interval * 5).coerceAtMost(maxPollingInterval)
    }
    
    /**
     * Updates doze mode state.
     */
    private fun updateDozeMode() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val inDoze = powerManager.isDeviceIdleMode
                if (inDoze != isInDozeMode.get()) {
                    isInDozeMode.set(inDoze)
                    Log.d(TAG, "Doze mode changed: $inDoze")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check doze mode", e)
        }
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
        isUserActive.set(true)
        
        // Reset active state after 30 seconds of no activity
        CoroutineScope(Dispatchers.Default).launch {
            delay(30_000)
            if (System.currentTimeMillis() - lastUserActivity.get() >= 30_000) {
                isUserActive.set(false)
            }
        }
    }
    
    /**
     * Notifies the monitor about screen state changes.
     */
    fun notifyScreenState(isOn: Boolean) {
        isScreenOn.set(isOn)
        if (isOn) {
            screenOnTime.set(System.currentTimeMillis())
            // Reset activity when screen turns on
            notifyUserActivity()
        }
        Log.d(TAG, "Screen state changed: isOn=$isOn")
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
            batteryOptimizationActive = batteryOptimizer.isOptimizationActive(),
            isInDozeMode = isInDozeMode.get(),
            recentClipboardChanges = recentClipboardChanges.get(),
            isScreenOn = isScreenOn.get()
        )
    }
}