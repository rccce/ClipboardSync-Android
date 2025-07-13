package com.siw.clipboardsync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.siw.clipboardsync.manager.ClipboardSyncManager
import com.siw.clipboardsync.utils.ClipboardUtils
import com.siw.clipboardsync.utils.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Broadcast receiver for handling clipboard changes from LSPosed hook
 * This provides real-time clipboard monitoring for rooted devices
 */
class ClipboardHookReceiver : BroadcastReceiver() {
    
    private var clipboardSyncManager: ClipboardSyncManager? = null
    
    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "ClipboardHookReceiver"
        const val ACTION_CLIPBOARD_CHANGED = "com.siw.clipboardsync.CLIPBOARD_CHANGED"
        
        /**
         * Create intent filter for clipboard hook broadcasts
         */
        fun createIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(ACTION_CLIPBOARD_CHANGED)
            }
        }
        
        /**
         * Register receiver for clipboard hook events
         */
        fun register(context: Context, receiver: ClipboardHookReceiver, syncManager: ClipboardSyncManager) {
            try {
                receiver.clipboardSyncManager = syncManager
                context.registerReceiver(receiver, createIntentFilter())
                Log.d(TAG, "ClipboardHookReceiver registered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register ClipboardHookReceiver", e)
            }
        }
        
        /**
         * Unregister receiver
         */
        fun unregister(context: Context, receiver: ClipboardHookReceiver) {
            try {
                context.unregisterReceiver(receiver)
                receiver.clipboardSyncManager = null
                Log.d(TAG, "ClipboardHookReceiver unregistered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister ClipboardHookReceiver", e)
            }
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLIPBOARD_CHANGED) return
        
        try {
            handleClipboardChange(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard change from hook", e)
        }
    }
    
    /**
     * Handle clipboard change event from LSPosed hook
     */
    private fun handleClipboardChange(intent: Intent) {
        val contentType = intent.getStringExtra("content_type") ?: return
        val content = intent.getStringExtra("content") ?: return
        val mimeType = intent.getStringExtra("mime_type")
        val sourcePackage = intent.getStringExtra("source_package")
        val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
        val hookSource = intent.getStringExtra("hook_source")
        
        Log.d(TAG, "=== CLIPBOARD HOOK EVENT ===")
        Log.d(TAG, "Content Type: $contentType")
        Log.d(TAG, "Content: ${content.take(100)}...")
        Log.d(TAG, "MIME Type: $mimeType")
        Log.d(TAG, "Source Package: $sourcePackage")
        Log.d(TAG, "Hook Source: $hookSource")
        Log.d(TAG, "Timestamp: $timestamp")
        
        // Skip if content is from our own app to prevent loops
        if (sourcePackage == "com.siw.clipboardsync") {
            Log.d(TAG, "Skipping clipboard change from our own app")
            return
        }
        
        // Validate content before syncing
        if (!ClipboardUtils.shouldSyncContent(content)) {
            Log.d(TAG, "Content filtered out by sync policy")
            return
        }
        
        // Process the clipboard change
        receiverScope.launch {
            try {
                syncClipboardContent(content, contentType, sourcePackage)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing clipboard content from hook", e)
            }
        }
    }
    
    /**
     * Sync clipboard content to cloud
     */
    private suspend fun syncClipboardContent(content: String, contentType: String, sourcePackage: String?) {
        val syncManager = clipboardSyncManager
        if (syncManager == null) {
            Log.w(TAG, "ClipboardSyncManager not available, skipping sync")
            return
        }
        
        try {
            Log.d(TAG, "Syncing clipboard content from hook: $contentType")
            
            // Sanitize content
            val sanitizedContent = ClipboardUtils.sanitizeContent(content)
            
            // Sync to cloud via ClipboardSyncManager
            val result = syncManager.syncLocalClipboard(sanitizedContent, contentType)
            
            if (result.isSuccess) {
                Log.d(TAG, "Successfully synced clipboard content from hook")
                
                // Log sync statistics
                logSyncStats(sanitizedContent, contentType, sourcePackage, true)
            } else {
                Log.w(TAG, "Failed to sync clipboard content from hook: ${result.exceptionOrNull()?.message}")
                logSyncStats(sanitizedContent, contentType, sourcePackage, false)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncClipboardContent", e)
            logSyncStats(content, contentType, sourcePackage, false)
        }
    }
    
    /**
     * Log sync statistics for monitoring and debugging
     */
    private fun logSyncStats(content: String, contentType: String, sourcePackage: String?, success: Boolean) {
        val stats = mapOf(
            "content_length" to content.length,
            "content_type" to contentType,
            "source_package" to (sourcePackage ?: "unknown"),
            "sync_success" to success,
            "sync_method" to "lsposed_hook",
            "timestamp" to System.currentTimeMillis()
        )
        
        Log.d(TAG, "Sync Stats: $stats")
        
        // TODO: Send to analytics/monitoring service if needed
    }
    
    /**
     * Check if the receiver is properly configured
     */
    fun isConfigured(): Boolean {
        return clipboardSyncManager != null
    }
}