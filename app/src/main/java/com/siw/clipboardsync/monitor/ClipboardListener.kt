package com.siw.clipboardsync.monitor

import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError

/**
 * Interface for receiving clipboard change events and monitoring errors.
 */
interface ClipboardListener {
    /**
     * Called when clipboard content changes.
     * @param content the new clipboard content
     * @param timestamp the timestamp when the change occurred
     */
    suspend fun onClipboardChanged(content: ClipboardContent, timestamp: Long)
    
    /**
     * Called when a monitoring error occurs.
     * @param error the clipboard error that occurred
     */
    suspend fun onMonitoringError(error: ClipboardError)
}