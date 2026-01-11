package com.siw.clipboardsync.monitor

import com.siw.clipboardsync.monitor.model.ClipboardError

/**
 * Exception thrown by clipboard monitoring operations.
 */
class ClipboardMonitorException(
    val clipboardError: ClipboardError
) : Exception(clipboardError.message, clipboardError.cause) {
    
    /**
     * Gets the error code associated with this exception.
     */
    val errorCode: String
        get() = clipboardError.errorCode
    
    /**
     * Checks if this error is recoverable.
     */
    val isRecoverable: Boolean
        get() = clipboardError.isRecoverable
    
    /**
     * Gets a user-friendly error message.
     */
    fun getUserFriendlyMessage(): String {
        return clipboardError.getUserFriendlyMessage()
    }
}