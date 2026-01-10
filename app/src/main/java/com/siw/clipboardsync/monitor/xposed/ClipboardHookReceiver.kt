package com.siw.clipboardsync.monitor.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.siw.clipboardsync.monitor.model.ClipboardContent

/**
 * Broadcast receiver for receiving clipboard change events from Xposed module.
 * 
 * The Xposed module hooks ClipboardManager.setPrimaryClip() and broadcasts
 * clipboard changes to this receiver.
 * 
 * Requirements: 3.2, 3.3
 */
class ClipboardHookReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ClipboardHookReceiver"
        
        // Action for clipboard change broadcast
        const val ACTION_CLIPBOARD_CHANGED = "com.siw.clipboardsync.CLIPBOARD_CHANGED"
        
        // Extra keys
        const val EXTRA_CONTENT = "clipboard_content"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
        
        /**
         * Creates an IntentFilter for clipboard change broadcasts.
         */
        fun createIntentFilter(): IntentFilter {
            return IntentFilter(ACTION_CLIPBOARD_CHANGED)
        }
        
        /**
         * Creates an Intent to broadcast clipboard change.
         * This is called from the Xposed module.
         */
        fun createBroadcastIntent(
            content: String,
            mimeType: String,
            timestamp: Long,
            sourcePackage: String
        ): Intent {
            return Intent(ACTION_CLIPBOARD_CHANGED).apply {
                putExtra(EXTRA_CONTENT, content)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_TIMESTAMP, timestamp)
                putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
            }
        }
    }
    
    private var clipboardListener: ((ClipboardContent) -> Unit)? = null
    
    /**
     * Sets the listener for clipboard changes.
     */
    fun setClipboardListener(listener: (ClipboardContent) -> Unit) {
        this.clipboardListener = listener
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLIPBOARD_CHANGED) {
            return
        }
        
        try {
            val content = intent.getStringExtra(EXTRA_CONTENT)
            val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "text/plain"
            val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
            val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE) ?: "unknown"
            
            if (content.isNullOrEmpty()) {
                Log.w(TAG, "Received empty clipboard content from Xposed hook")
                return
            }
            
            Log.i(TAG, "Received clipboard change from Xposed hook: ${content.take(50)}...")
            
            val clipboardContent = ClipboardContent(
                type = determineContentType(mimeType),
                data = content.toByteArray(),
                mimeType = mimeType,
                timestamp = timestamp,
                source = "xposed_hook:$sourcePackage",
                size = content.length.toLong()
            )
            
            clipboardListener?.invoke(clipboardContent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing clipboard change broadcast", e)
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
