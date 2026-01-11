package com.siw.clipboardsync.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.utils.ClipboardUtils
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accessibility service for monitoring clipboard changes on non-root devices.
 * This service provides clipboard monitoring capabilities on Android 10+ devices
 * where background clipboard access is restricted.
 * 
 * Key features:
 * - Background clipboard monitoring without root
 * - Automatic sync to server when clipboard changes
 * - Works even when app is in background
 * 
 * Note: On Android 10+, clipboard access is restricted. This service uses
 * multiple strategies to detect clipboard changes:
 * 1. Listen for text selection change events (copy action detection)
 * 2. Monitor window focus changes to read clipboard when app gains focus
 * 3. Use ClipboardManager listener when app is in foreground
 */
class ClipboardAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "ClipboardAccessibility"
        private const val CLIPBOARD_CHANGE_DEBOUNCE_MS = 500L
        private const val CLIPBOARD_CHECK_INTERVAL_MS = 2000L
        
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
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Track detected copy actions
    private var lastCopyDetectedTime = 0L
    private var pendingClipboardCheck = false
    
    // Periodic clipboard check for cases where listener doesn't fire
    private var clipboardCheckRunnable: Runnable? = null
    
    // Callback for clipboard changes
    private var onClipboardChanged: ((ClipboardContent, Long) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ClipboardAccessibilityService created (Android ${Build.VERSION.SDK_INT})")
        instance = this
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ClipboardAccessibilityService destroyed")
        stopClipboardMonitoring()
        serviceScope.cancel()
        instance = null
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected - Android ${Build.VERSION.SDK_INT}")
        
        // Configure accessibility service info for clipboard monitoring
        val info = AccessibilityServiceInfo().apply {
            // Listen for events that might indicate clipboard changes
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_ANNOUNCEMENT
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                   AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
            // Monitor all packages
            packageNames = null
        }
        serviceInfo = info
        
        Log.i(TAG, "Accessibility service configured, starting clipboard monitoring")
        
        // Start clipboard monitoring automatically when service connects
        startClipboardMonitoring()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isMonitoring.get()) return
        
        val eventType = event.eventType
        val packageName = event.packageName?.toString() ?: "unknown"
        
        when (eventType) {
            // Text selection changed - might indicate copy action
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                Log.d(TAG, "Text selection changed in $packageName")
                // User might be selecting text to copy
                scheduleClipboardCheck(200)
            }
            
            // Long click - often used to trigger copy menu
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                Log.d(TAG, "Long click detected in $packageName")
                scheduleClipboardCheck(500)
            }
            
            // Window state changed - app switch, might be able to read clipboard
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString() ?: ""
                Log.d(TAG, "Window state changed: $className in $packageName")
                
                // Check if this is our app gaining focus
                if (packageName == "com.siw.clipboardsync") {
                    Log.d(TAG, "Our app gained focus, checking clipboard")
                    handleClipboardChange()
                } else {
                    // Other app, schedule a check
                    scheduleClipboardCheck(300)
                }
            }
            
            // Announcement - some apps announce "Copied" or similar
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                val text = event.text?.joinToString(" ") ?: ""
                Log.d(TAG, "Announcement: $text in $packageName")
                if (text.contains("复制", ignoreCase = true) || 
                    text.contains("copy", ignoreCase = true) ||
                    text.contains("copied", ignoreCase = true)) {
                    Log.i(TAG, "Copy announcement detected!")
                    scheduleClipboardCheck(100)
                }
            }
            
            // View clicked - might be clicking copy button
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val contentDesc = event.contentDescription?.toString() ?: ""
                val text = event.text?.joinToString(" ") ?: ""
                
                // Check if this looks like a copy action
                if (contentDesc.contains("复制", ignoreCase = true) ||
                    contentDesc.contains("copy", ignoreCase = true) ||
                    text.contains("复制", ignoreCase = true) ||
                    text.contains("copy", ignoreCase = true)) {
                    Log.i(TAG, "Copy button clicked detected!")
                    scheduleClipboardCheck(100)
                }
            }
        }
    }
    
    /**
     * Schedule a clipboard check with debouncing.
     */
    private fun scheduleClipboardCheck(delayMs: Long) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCopyDetectedTime < CLIPBOARD_CHANGE_DEBOUNCE_MS) {
            return
        }
        lastCopyDetectedTime = currentTime
        
        mainHandler.removeCallbacksAndMessages("clipboard_check")
        mainHandler.postDelayed({
            checkClipboardChange()
        }, delayMs)
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }
    
    /**
     * Starts clipboard monitoring using accessibility service context.
     */
    fun startClipboardMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            Log.i(TAG, "Starting clipboard monitoring via accessibility service")
            
            // Method 1: Use ClipboardManager listener (works when app is in foreground)
            clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
                Log.d(TAG, "ClipboardManager listener triggered")
                handleClipboardChange()
            }
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
            
            // Method 2: Periodic clipboard check as backup (will fail in background on Android 10+)
            // But we keep it for when the app is in foreground
            startPeriodicClipboardCheck()
            
            // Try to read initial clipboard content
            handleClipboardChange()
            
            Log.i(TAG, "Clipboard monitoring started - listening for copy events")
        }
    }
    
    /**
     * Starts periodic clipboard checking as a backup mechanism.
     * Note: This will fail on Android 10+ when app is in background,
     * but works when app is in foreground.
     */
    private fun startPeriodicClipboardCheck() {
        clipboardCheckRunnable = object : Runnable {
            override fun run() {
                if (isMonitoring.get()) {
                    checkClipboardChange()
                    mainHandler.postDelayed(this, CLIPBOARD_CHECK_INTERVAL_MS)
                }
            }
        }
        mainHandler.postDelayed(clipboardCheckRunnable!!, CLIPBOARD_CHECK_INTERVAL_MS)
    }
    
    /**
     * Checks if clipboard content has changed.
     * Note: On Android 10+, this will fail when app is not in focus.
     * We catch the exception silently and rely on event-based detection.
     */
    private fun checkClipboardChange() {
        try {
            val clipData = clipboardManager?.primaryClip
            if (clipData == null || clipData.itemCount == 0) {
                return
            }
            
            val clipItem = clipData.getItemAt(0)
            val text = clipItem.text?.toString() 
                ?: clipItem.coerceToText(this)?.toString()
                ?: return
            
            if (text != lastClipboardContent && text.isNotEmpty()) {
                Log.i(TAG, "Clipboard change detected: ${text.take(50)}...")
                lastClipboardContent = text
                notifyClipboardChanged(text)
            }
        } catch (e: SecurityException) {
            // Expected on Android 10+ when app is not in focus
            // Silently ignore - we'll detect changes through events
        } catch (e: Exception) {
            Log.w(TAG, "Error checking clipboard: ${e.message}")
        }
    }
    
    /**
     * Stops clipboard monitoring and cleans up resources.
     */
    fun stopClipboardMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping clipboard monitoring")
            
            clipboardListener?.let { listener ->
                clipboardManager?.removePrimaryClipChangedListener(listener)
            }
            clipboardListener = null
            
            clipboardCheckRunnable?.let { runnable ->
                mainHandler.removeCallbacks(runnable)
            }
            clipboardCheckRunnable = null
            
            mainHandler.removeCallbacksAndMessages(null)
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
     * Notifies listeners of clipboard content change.
     */
    private fun notifyClipboardChanged(text: String) {
        val timestamp = System.currentTimeMillis()
        val content = ClipboardContent(
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
        
        Log.i(TAG, "Notifying clipboard change: ${text.take(50)}...")
        onClipboardChanged?.invoke(content, timestamp)
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
                    Log.d(TAG, "No clipboard data available")
                    return@launch
                }
                
                val clipItem = clipData.getItemAt(0)
                val content = extractClipboardContent(clipItem, currentTime)
                
                // Check if content actually changed
                val contentText = when (content.type) {
                    ClipboardContent.ContentType.TEXT,
                    ClipboardContent.ContentType.HTML -> String(content.data)
                    else -> content.id
                }
                
                if (contentText != lastClipboardContent && contentText.isNotEmpty()) {
                    lastClipboardContent = contentText
                    Log.i(TAG, "Clipboard changed: ${content.type}, size: ${content.size} bytes")
                    Log.d(TAG, "Content preview: ${contentText.take(100)}...")
                    onClipboardChanged?.invoke(content, currentTime)
                }
                
            } catch (e: SecurityException) {
                // Clipboard access denied - expected on Android 10+ in background
                Log.d(TAG, "Clipboard access denied (expected on Android 10+ in background)")
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