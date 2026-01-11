package com.siw.clipboardsync.monitor.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.siw.clipboardsync.manager.ClipboardSyncManager
import com.siw.clipboardsync.monitor.model.ClipboardContent
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Static broadcast receiver for receiving clipboard changes from Xposed module.
 * This receiver is registered in AndroidManifest.xml and will receive broadcasts
 * even when the app is in the background or killed.
 * 
 * This is the primary mechanism for receiving clipboard changes from the Xposed hook.
 * It delegates to ClipboardSyncManager to handle deduplication and syncing.
 */
class StaticClipboardReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "StaticClipboardReceiver"
        const val ACTION_CLIPBOARD_CHANGED = "com.siw.clipboardsync.CLIPBOARD_CHANGED"
        
        // Deduplication: track last received content to filter duplicate broadcasts
        // Xposed module may send the same broadcast twice
        private var lastContentHash: Int = 0
        private var lastTimestamp: Long = 0L
        private const val DUPLICATE_THRESHOLD_MS = 1000L // 1 second threshold
    }
    
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StaticClipboardReceiverEntryPoint {
        fun clipboardSyncManager(): ClipboardSyncManager
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "========================================")
        Log.i(TAG, "StaticClipboardReceiver.onReceive called!")
        Log.i(TAG, "Action: ${intent.action}")
        Log.i(TAG, "========================================")
        
        if (intent.action != ACTION_CLIPBOARD_CHANGED) {
            Log.w(TAG, "Unexpected action: ${intent.action}")
            return
        }
        
        try {
            val content = intent.getStringExtra("clipboard_content")
            val mimeType = intent.getStringExtra("mime_type") ?: "text/plain"
            val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
            val sourcePackage = intent.getStringExtra("source_package") ?: "unknown"
            
            Log.i(TAG, "Received clipboard change from Xposed hook!")
            Log.i(TAG, "Source package: $sourcePackage")
            Log.i(TAG, "MimeType: $mimeType")
            Log.i(TAG, "Content length: ${content?.length ?: 0}")
            Log.i(TAG, "Content preview: ${content?.take(100) ?: "null"}...")
            
            if (content.isNullOrEmpty()) {
                Log.w(TAG, "Empty clipboard content, ignoring")
                return
            }
            
            // Deduplication check: filter duplicate broadcasts from Xposed module
            val contentHash = content.hashCode()
            val currentTime = System.currentTimeMillis()
            
            synchronized(Companion) {
                if (contentHash == lastContentHash && 
                    currentTime - lastTimestamp < DUPLICATE_THRESHOLD_MS) {
                    Log.d(TAG, "Duplicate broadcast detected within ${DUPLICATE_THRESHOLD_MS}ms, ignoring")
                    return
                }
                
                // Update tracking
                lastContentHash = contentHash
                lastTimestamp = currentTime
            }
            
            // Record that we received an Xposed broadcast - used to verify Xposed is active
            try {
                val prefs = context.getSharedPreferences("xposed_status", Context.MODE_PRIVATE)
                prefs.edit().putLong("last_xposed_broadcast", currentTime).apply()
                Log.d(TAG, "Recorded Xposed broadcast timestamp for activity verification")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record Xposed broadcast timestamp", e)
            }
            
            // Get ClipboardSyncManager via Hilt EntryPoint
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                StaticClipboardReceiverEntryPoint::class.java
            )
            val clipboardSyncManager = entryPoint.clipboardSyncManager()
            
            // Use pending result to keep receiver alive during async operation
            val pendingResult = goAsync()
            
            scope.launch {
                try {
                    // Create ClipboardContent and delegate to ClipboardSyncManager
                    // This ensures deduplication logic is applied
                    val clipboardContent = ClipboardContent(
                        type = determineContentType(mimeType),
                        data = content.toByteArray(),
                        mimeType = mimeType,
                        timestamp = timestamp,
                        source = "xposed_static:$sourcePackage",
                        size = content.length.toLong()
                    )
                    
                    // Delegate to ClipboardSyncManager's onClipboardChanged
                    // This will handle deduplication and syncing
                    Log.d(TAG, "Delegating to ClipboardSyncManager for sync...")
                    clipboardSyncManager.onClipboardChanged(clipboardContent, timestamp)
                    Log.i(TAG, "Clipboard change delegated to ClipboardSyncManager")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error delegating clipboard content to ClipboardSyncManager", e)
                } finally {
                    pendingResult.finish()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing clipboard broadcast", e)
        }
    }
    
    /**
     * Determines the content type from MIME type.
     */
    private fun determineContentType(mimeType: String): ClipboardContent.ContentType {
        return when {
            mimeType.startsWith("text/") -> ClipboardContent.ContentType.TEXT
            mimeType.startsWith("image/") -> ClipboardContent.ContentType.IMAGE
            mimeType.startsWith("application/") -> ClipboardContent.ContentType.FILE
            else -> ClipboardContent.ContentType.UNKNOWN
        }
    }
}
