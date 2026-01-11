package com.siw.clipboardsync.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service for app keep-alive and auto-start functionality.
 * 
 * IMPORTANT: This service does NOT provide clipboard monitoring/sync.
 * Based on real-world testing, accessibility services cannot reliably
 * achieve background clipboard sync on modern Android versions.
 * 
 * This service is used ONLY for:
 * 1. Keep-alive: Prevents the app from being killed by the system
 * 2. Auto-start: Helps the app restart after device reboot
 * 3. Background persistence: Maintains app presence in background
 * 
 * For actual clipboard sync, use:
 * - XPOSED_HOOKS (if Xposed/LSPosed is hooking this app)
 * - SHIZUKU (if Shizuku is available and permitted)
 * - FOREGROUND_SYNC (fallback - sync when app comes to foreground)
 */
class ClipboardAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "AccessibilityKeepAlive"
        
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
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Accessibility keep-alive service created (Android ${Build.VERSION.SDK_INT})")
        instance = this
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility keep-alive service destroyed")
        instance = null
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility keep-alive service connected")
        
        // Configure minimal accessibility service info
        // We don't need to listen to any events - just need the service running
        val info = AccessibilityServiceInfo().apply {
            // Minimal event types - we don't actually process events
            eventTypes = 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = 0
            notificationTimeout = 0
            // Don't monitor any packages
            packageNames = null
        }
        serviceInfo = info
        
        Log.i(TAG, "Accessibility service configured for keep-alive only (no clipboard monitoring)")
        
        // Ensure the main clipboard monitoring service is running
        ensureClipboardMonitorServiceRunning()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't process any events - this service is only for keep-alive
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }
    
    /**
     * Ensures the main clipboard monitor service is running.
     * This helps with auto-restart after the accessibility service reconnects.
     */
    private fun ensureClipboardMonitorServiceRunning() {
        try {
            Log.d(TAG, "Ensuring ClipboardMonitorService is running")
            ClipboardMonitorService.startService(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ClipboardMonitorService", e)
        }
    }
    
    /**
     * Gets the current service status.
     * @return status string for debugging
     */
    fun getServiceStatus(): String {
        return "Accessibility keep-alive service running (Android ${Build.VERSION.SDK_INT})"
    }
}
