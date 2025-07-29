package com.siw.clipboardsync.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.utils.ClipboardUtils
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accessibility service for monitoring clipboard changes on non-root devices.
 * This service provides clipboard monitoring capabilities on Android 10+ devices
 * where background clipboard access is restricted.
 */
class ClipboardAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "ClipboardAccessibilityService"
        private const val CLIPBOARD_CHANGE_DEBOUNCE_MS = 200L
        
        // Static reference to the service instance
        @Volatile
        private var instance: ClipboardAccessibilityService? = null
        
        /**
         * Gets the current service instance if available.
         * @return the service instance or null if not running
         */
        fun getInstance(): ClipboardAccessibilityService? = instance
        
        /**
         * Checks if the accessibility service is currently running.
         * @return true if service is running, false otherwise
         */
        fun isServiceRunning(): Boolean = instance != null
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)
    private var clipboardManager: ClipboardManager? = null
    private var lastClipboardContent: String? = null
    private var lastChangeTime = 0L
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    
    // Callback for clipboard changes
    private var onClipboardChanged: ((ClipboardContent, Long) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ClipboardAccessibilityService created")
        instance = this
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ClipboardAccessibilityService destroyed")
        stopClipboardMonitoring()
        serviceScope.cancel()
        instance = null
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        
        // Configure accessibility service info
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                   AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info
        
        // Start clipboard monitoring automatically when service connects
        startClipboardMonitoring()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We primarily use clipboard manager listener, but this can be used
        // for additional context or fallback monitoring
        event?.let {
            if (isMonitoring.get()) {
                // Could potentially detect clipboard-related UI changes here
                // For now, we rely on ClipboardManager.OnPrimaryClipChangedListener
            }
        }
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        stopClipboardMonitoring()
    }
    
    /**
     * Starts clipboard monitoring using accessibility service context.
     */
    fun startClipboardMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            Log.d(TAG, "Starting clipboard monitoring via accessibility service")
            
            clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
                handleClipboardChange()
            }
            
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
            
            // Send initial clipboard content if available
            handleClipboardChange()
        }
    }
    
    /**
     * Stops clipboard monitoring and cleans up resources.
     */
    fun stopClipboardMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping clipboard monitoring")
            
            clipboardListener?.let { listener ->
                clipboardManager?.removePrimaryClipChangedListener(listener)
            }
            clipboardListener = null
        }
    }
    
    /**
     * Sets the callback for clipboard change events.
     * @param callback function to call when clipboard changes
     */
    fun setOnClipboardChanged(callback: (ClipboardContent, Long) -> Unit) {
        onClipboardChanged = callback
    }
    
    /**
     * Sets the callback for error events.
     * @param callback function to call when errors occur
     */
    fun setOnError(callback: (Throwable) -> Unit) {
        onError = callback
    }
    
    /**
     * Handles clipboard change events with debouncing and content extraction.
     */
    private fun handleClipboardChange() {
        serviceScope.launch {
            try {
                val currentTime = System.currentTimeMillis()
                
                // Debounce rapid clipboard changes
                if (currentTime - lastChangeTime < CLIPBOARD_CHANGE_DEBOUNCE_MS) {
                    return@launch
                }
                lastChangeTime = currentTime
                
                val clipData = clipboardManager?.primaryClip
                if (clipData == null || clipData.itemCount == 0) {
                    return@launch
                }
                
                val clipItem = clipData.getItemAt(0)
                val content = extractClipboardContent(clipItem, currentTime)
                
                // Check if content actually changed
                val contentText = when (content.type) {
                    ClipboardContent.ContentType.TEXT -> String(content.data)
                    else -> content.id
                }
                
                if (contentText != lastClipboardContent) {
                    lastClipboardContent = contentText
                    Log.d(TAG, "Clipboard changed: ${content.type}, size: ${content.size}")
                    onClipboardChanged?.invoke(content, currentTime)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling clipboard change", e)
                onError?.invoke(e)
            }
        }
    }
    
    /**
     * Extracts clipboard content from ClipData.Item.
     * @param clipItem the clipboard item
     * @param timestamp the timestamp of the change
     * @return ClipboardContent object
     */
    private fun extractClipboardContent(
        clipItem: android.content.ClipData.Item,
        timestamp: Long
    ): ClipboardContent {
        return when {
            clipItem.text != null -> {
                val text = clipItem.text.toString()
                ClipboardContent(
                    type = ClipboardContent.ContentType.TEXT,
                    data = text.toByteArray(Charsets.UTF_8),
                    mimeType = "text/plain",
                    timestamp = timestamp,
                    source = "accessibility_service",
                    size = text.length.toLong(),
                    metadata = mapOf(
                        "method" to "accessibility_service",
                        "android_version" to Build.VERSION.SDK_INT.toString()
                    )
                )
            }
            
            clipItem.htmlText != null -> {
                val htmlText = clipItem.htmlText
                ClipboardContent(
                    type = ClipboardContent.ContentType.HTML,
                    data = htmlText.toByteArray(Charsets.UTF_8),
                    mimeType = "text/html",
                    timestamp = timestamp,
                    source = "accessibility_service",
                    size = htmlText.length.toLong(),
                    metadata = mapOf(
                        "method" to "accessibility_service",
                        "android_version" to Build.VERSION.SDK_INT.toString()
                    )
                )
            }
            
            clipItem.uri != null -> {
                val uri = clipItem.uri
                val uriString = uri.toString()
                ClipboardContent(
                    type = ClipboardContent.ContentType.URI,
                    data = uriString.toByteArray(Charsets.UTF_8),
                    mimeType = "text/uri-list",
                    timestamp = timestamp,
                    source = "accessibility_service",
                    size = uriString.length.toLong(),
                    metadata = mapOf(
                        "method" to "accessibility_service",
                        "uri_scheme" to (uri.scheme ?: "unknown"),
                        "android_version" to Build.VERSION.SDK_INT.toString()
                    )
                )
            }
            
            else -> {
                // Fallback for unknown content
                val fallbackText = clipItem.coerceToText(this@ClipboardAccessibilityService).toString()
                ClipboardContent(
                    type = ClipboardContent.ContentType.UNKNOWN,
                    data = fallbackText.toByteArray(Charsets.UTF_8),
                    mimeType = "text/plain",
                    timestamp = timestamp,
                    source = "accessibility_service",
                    size = fallbackText.length.toLong(),
                    metadata = mapOf(
                        "method" to "accessibility_service",
                        "fallback" to "true",
                        "android_version" to Build.VERSION.SDK_INT.toString()
                    )
                )
            }
        }
    }
    
    /**
     * Gets the current monitoring status.
     * @return true if monitoring is active, false otherwise
     */
    fun isMonitoring(): Boolean = isMonitoring.get()
}