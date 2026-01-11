package com.siw.clipboardsync.monitor

import com.siw.clipboardsync.monitor.model.MonitoringMethod

/**
 * Core interface for clipboard monitoring implementations.
 * Provides a unified API for different monitoring strategies.
 */
interface ClipboardMonitor {
    /**
     * Starts clipboard monitoring.
     * @throws ClipboardMonitorException if monitoring cannot be started
     */
    suspend fun startMonitoring()
    
    /**
     * Stops clipboard monitoring and releases resources.
     */
    suspend fun stopMonitoring()
    
    /**
     * Checks if clipboard monitoring is currently active.
     * @return true if monitoring is active, false otherwise
     */
    fun isMonitoring(): Boolean
    
    /**
     * Gets the current monitoring method being used.
     * @return the monitoring method
     */
    fun getMonitoringMethod(): MonitoringMethod
    
    /**
     * Sets the clipboard listener to receive clipboard change events.
     * @param listener the clipboard listener
     */
    fun setClipboardListener(listener: ClipboardListener)
}