package com.siw.clipboardsync.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Simplified accessibility service for app keep-alive.
 * 
 * This service provides basic keep-alive by being an accessibility service
 * (which are harder for the system to kill), without aggressive monitoring
 * that could cause system performance issues.
 */
class ClipboardAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "AccessibilityKeepAlive"
        
        @Volatile
        private var instance: ClipboardAccessibilityService? = null
        
        fun getInstance(): ClipboardAccessibilityService? = instance
        
        fun isServiceRunning(): Boolean = instance != null
        
        fun triggerHealthCheck() {
            instance?.performHealthCheck()
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Accessibility service created")
        instance = this
    }
    
    override fun onDestroy() {
        Log.w(TAG, "Accessibility service destroyed")
        instance = null
        super.onDestroy()
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        
        // Minimal configuration - just enough to stay alive
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 1000
            packageNames = null
        }
        
        // Ensure main service is running
        ensureClipboardMonitorServiceRunning()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Minimal processing - just log occasionally
        // Don't do heavy work here to avoid system issues
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }
    
    private fun performHealthCheck() {
        try {
            Log.d(TAG, "Health check triggered")
            ensureClipboardMonitorServiceRunning()
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
        }
    }
    
    private fun ensureClipboardMonitorServiceRunning() {
        try {
            val prefs = getSharedPreferences("clipboard_sync_tokens", Context.MODE_PRIVATE)
            val hasToken = prefs.getString("access_token", null) != null
            
            if (hasToken) {
                ClipboardMonitorService.startService(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ClipboardMonitorService", e)
        }
    }
    
    fun getServiceStatus(): String {
        return "Accessibility service running (Android ${Build.VERSION.SDK_INT})"
    }
}
