package com.siw.clipboardsync.monitor

import android.content.Context
import android.util.Log
import com.siw.clipboardsync.monitor.error.ClipboardErrorHandler
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringConfig
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.monitor.model.MonitoringStrategy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central coordinator for clipboard monitoring operations.
 * Manages strategy selection, fallback chain execution, and state preservation
 * across different monitoring methods.
 */
@Singleton
class ClipboardMonitorManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val strategyFactory: MonitoringStrategyFactory,
    private val errorHandler: ClipboardErrorHandler,
    private val systemLevelMonitor: SystemLevelClipboardMonitor,
    private val accessibilityMonitor: AccessibilityClipboardMonitor,
    private val foregroundServiceMonitor: ForegroundServiceClipboardMonitor,
    private val pollingMonitor: PollingClipboardMonitor,
    private val monitoringConfig: MonitoringConfig
) : ClipboardListener {
    
    companion object {
        private const val TAG = "ClipboardMonitorManager"
        private const val FALLBACK_RETRY_DELAY_MS = 2000L
        private const val MAX_FALLBACK_ATTEMPTS = 3
    }
    
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // State management
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()
    
    private val _currentMethod = MutableStateFlow<MonitoringMethod?>(null)
    val currentMethod: StateFlow<MonitoringMethod?> = _currentMethod.asStateFlow()
    
    private val _currentStrategy = MutableStateFlow<MonitoringStrategy?>(null)
    val currentStrategy: StateFlow<MonitoringStrategy?> = _currentStrategy.asStateFlow()
    
    // Active monitor and listener management
    private val currentMonitor = AtomicReference<ClipboardMonitor?>(null)
    private val externalListener = AtomicReference<ClipboardListener?>(null)
    private val isInitialized = AtomicBoolean(false)
    
    // Fallback chain management
    private var fallbackChain: List<MonitoringStrategy> = emptyList()
    private var currentFallbackIndex = 0
    private var fallbackJob: Job? = null
    
    /**
     * Initializes the monitor manager and prepares monitoring strategies.
     */
    suspend fun initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            Log.d(TAG, "Initializing clipboard monitor manager")
            
            try {
                // Create fallback chain based on device capabilities
                fallbackChain = strategyFactory.createFallbackChain()
                currentFallbackIndex = 0
                
                Log.i(TAG, "Monitor manager initialized with ${fallbackChain.size} available strategies")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize monitor manager", e)
                isInitialized.set(false)
                throw e
            }
        }
    }
    
    /**
     * Starts clipboard monitoring using the optimal available strategy.
     * @param listener the clipboard listener to receive events
     */
    suspend fun startMonitoring(listener: ClipboardListener? = null) {
        if (!isInitialized.get()) {
            initialize()
        }
        
        if (_isMonitoring.value) {
            Log.w(TAG, "Monitoring is already active")
            return
        }
        
        listener?.let { externalListener.set(it) }
        
        Log.i(TAG, "Starting clipboard monitoring")
        
        try {
            // Select optimal strategy
            val optimalStrategy = strategyFactory.selectOptimalStrategy()
            if (optimalStrategy == null) {
                throw ClipboardMonitorException(
                    ClipboardError.NoMonitoringMethodAvailable
                )
            }
            
            // Start monitoring with the optimal strategy
            val success = startMonitoringWithStrategy(optimalStrategy)
            if (!success) {
                // If optimal strategy fails, try fallback chain
                startFallbackChain()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring", e)
            val error = when (e) {
                is ClipboardMonitorException -> e.clipboardError
                else -> ClipboardError.UnknownError(e)
            }
            externalListener.get()?.onMonitoringError(error)
            throw e
        }
    }
    
    /**
     * Stops clipboard monitoring and releases resources.
     */
    suspend fun stopMonitoring() {
        if (!_isMonitoring.value) {
            Log.w(TAG, "Monitoring is not active")
            return
        }
        
        Log.i(TAG, "Stopping clipboard monitoring")
        
        try {
            // Cancel fallback job if running
            fallbackJob?.cancel()
            fallbackJob = null
            
            // Stop current monitor
            val monitor = currentMonitor.get()
            if (monitor != null) {
                monitor.stopMonitoring()
                Log.d(TAG, "Stopped monitor: ${monitor.getMonitoringMethod().name}")
            }
            
            // Clear state
            currentMonitor.set(null)
            _currentMethod.value = null
            _currentStrategy.value = null
            _isMonitoring.value = false
            
            Log.i(TAG, "Clipboard monitoring stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping monitoring", e)
            externalListener.get()?.onMonitoringError(ClipboardError.UnknownError(e))
        }
    }


    
    /**
     * Switches to a different monitoring method while preserving state.
     * @param targetMethod the monitoring method to switch to
     */
    suspend fun switchMonitoringMethod(targetMethod: MonitoringMethod) {
        Log.i(TAG, "Switching monitoring method to: ${targetMethod.name}")
        
        val wasMonitoring = _isMonitoring.value
        val preservedListener = externalListener.get()
        
        try {
            // Stop current monitoring
            if (wasMonitoring) {
                stopMonitoring()
            }
            
            // Find strategy for target method
            val targetStrategy = fallbackChain.find { it.method == targetMethod }
            if (targetStrategy == null || !targetStrategy.isAvailable) {
                throw ClipboardMonitorException(
                    ClipboardError.MonitoringMethodUnavailable(targetMethod.name)
                )
            }
            
            // Start with new method
            val success = startMonitoringWithStrategy(targetStrategy)
            if (!success) {
                throw ClipboardMonitorException(
                    ClipboardError.MonitoringMethodFailed(targetMethod.name)
                )
            }
            
            Log.i(TAG, "Successfully switched to monitoring method: ${targetMethod.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch monitoring method", e)
            
            // Try to restore previous monitoring if it was active
            if (wasMonitoring && preservedListener != null) {
                try {
                    startMonitoring(preservedListener)
                } catch (restoreException: Exception) {
                    Log.e(TAG, "Failed to restore previous monitoring", restoreException)
                }
            }
            
            val error = when (e) {
                is ClipboardMonitorException -> e.clipboardError
                else -> ClipboardError.UnknownError(e)
            }
            externalListener.get()?.onMonitoringError(error)
            throw e
        }
    }
    
    /**
     * Gets the current monitoring status and method information.
     */
    fun getMonitoringStatus(): MonitoringStatus {
        return MonitoringStatus(
            isMonitoring = _isMonitoring.value,
            currentMethod = _currentMethod.value,
            currentStrategy = _currentStrategy.value,
            availableStrategies = fallbackChain,
            fallbackIndex = currentFallbackIndex
        )
    }
    
    /**
     * Starts monitoring with a specific strategy.
     * @param strategy the monitoring strategy to use
     * @return true if monitoring started successfully, false otherwise
     */
    private suspend fun startMonitoringWithStrategy(strategy: MonitoringStrategy): Boolean {
        Log.d(TAG, "Starting monitoring with strategy: ${strategy.method.name}")
        
        try {
            val monitor = createMonitorForStrategy(strategy)
            monitor.setClipboardListener(this) // Use manager as intermediate listener
            
            monitor.startMonitoring()
            
            // Update state
            currentMonitor.set(monitor)
            _currentMethod.value = strategy.method
            _currentStrategy.value = strategy
            _isMonitoring.value = true
            
            Log.i(TAG, "Successfully started monitoring with: ${strategy.method.name}")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring with strategy: ${strategy.method.name}", e)
            
            // Handle the error through error handler
            val error = when (e) {
                is ClipboardMonitorException -> e.clipboardError
                else -> ClipboardError.UnknownError(e)
            }
            
            managerScope.launch {
                val monitor = createMonitorForStrategy(strategy)
                errorHandler.handleError(error, strategy.method, monitor)
            }
            
            return false
        }
    }
    
    /**
     * Starts the fallback chain when the optimal strategy fails.
     */
    private fun startFallbackChain() {
        Log.i(TAG, "Starting fallback chain execution")
        
        fallbackJob = managerScope.launch {
            var attempts = 0
            
            while (attempts < MAX_FALLBACK_ATTEMPTS && !_isMonitoring.value) {
                for (i in currentFallbackIndex until fallbackChain.size) {
                    val strategy = fallbackChain[i]
                    
                    if (!strategy.isAvailable) {
                        Log.d(TAG, "Skipping unavailable strategy: ${strategy.method.name}")
                        continue
                    }
                    
                    Log.d(TAG, "Trying fallback strategy: ${strategy.method.name}")
                    
                    val success = startMonitoringWithStrategy(strategy)
                    if (success) {
                        currentFallbackIndex = i
                        Log.i(TAG, "Fallback chain succeeded with: ${strategy.method.name}")
                        return@launch
                    }
                    
                    // Wait before trying next strategy
                    delay(FALLBACK_RETRY_DELAY_MS)
                }
                
                attempts++
                currentFallbackIndex = 0 // Reset for next attempt
                
                if (attempts < MAX_FALLBACK_ATTEMPTS) {
                    Log.w(TAG, "Fallback chain attempt $attempts failed, retrying...")
                    delay(FALLBACK_RETRY_DELAY_MS * attempts) // Exponential backoff
                }
            }
            
            // All fallback attempts failed
            Log.e(TAG, "All fallback strategies failed after $attempts attempts")
            val error = ClipboardError.AllMonitoringMethodsFailed
            externalListener.get()?.onMonitoringError(error)
        }
    }
    
    /**
     * Creates a monitor instance for the given strategy.
     */
    private fun createMonitorForStrategy(strategy: MonitoringStrategy): ClipboardMonitor {
        return when (strategy.method) {
            MonitoringMethod.SYSTEM_HOOKS,
            MonitoringMethod.XPOSED_HOOKS -> systemLevelMonitor
            MonitoringMethod.ACCESSIBILITY_SERVICE -> accessibilityMonitor
            MonitoringMethod.FOREGROUND_SERVICE -> foregroundServiceMonitor
            MonitoringMethod.POLLING_FALLBACK -> pollingMonitor
        }
    }
    
    // ClipboardListener implementation - acts as intermediate listener
    override suspend fun onClipboardChanged(content: ClipboardContent, timestamp: Long) {
        Log.v(TAG, "Clipboard changed: ${content.type} (${content.size} bytes)")
        externalListener.get()?.onClipboardChanged(content, timestamp)
    }
    
    override suspend fun onMonitoringError(error: ClipboardError) {
        Log.w(TAG, "Monitoring error: ${error.javaClass.simpleName}")
        
        // Handle error through error handler
        val currentMethodValue = _currentMethod.value
        val currentMonitorValue = currentMonitor.get()
        if (currentMethodValue != null && currentMonitorValue != null) {
            managerScope.launch {
                errorHandler.handleError(error, currentMethodValue, currentMonitorValue)
            }
        }
        
        // Forward error to external listener
        externalListener.get()?.onMonitoringError(error)
    }
    
    /**
     * Data class representing the current monitoring status.
     */
    data class MonitoringStatus(
        val isMonitoring: Boolean,
        val currentMethod: MonitoringMethod?,
        val currentStrategy: MonitoringStrategy?,
        val availableStrategies: List<MonitoringStrategy>,
        val fallbackIndex: Int
    )
}