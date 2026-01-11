package com.siw.clipboardsync.monitor.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
            return IntentFilter(ACTION_CLIPBOARD_CHANGED).apply {
                // Add priority to ensure we receive the broadcast
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            }
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
        
        /**
         * Registers the receiver with the given context.
         * Handles different Android versions appropriately.
         */
        fun register(context: Context, receiver: ClipboardHookReceiver): Boolean {
            return try {
                val filter = createIntentFilter()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ requires explicit export flag
                    context.registerReceiver(
                        receiver,
                        filter,
                        Context.RECEIVER_EXPORTED
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8+ 
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                
                Log.i(TAG, "ClipboardHookReceiver registered successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register ClipboardHookReceiver", e)
                false
            }
        }
    }
    
    private var clipboardListener: ((ClipboardContent) -> Unit)? = null
    
    /**
     * Sets the listener for clipboard changes.
     */
    fun setClipboardListener(listener: (ClipboardContent) -> Unit) {
        this.clipboardListener = listener
        Log.d(TAG, "Clipboard listener set")
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called with action: ${intent.action}")
        
        if (intent.action != ACTION_CLIPBOARD_CHANGED) {
            Log.w(TAG, "Unexpected action: ${intent.action}")
            return
        }
        
        try {
            val content = intent.getStringExtra(EXTRA_CONTENT)
            val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "text/plain"
            val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
            val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE) ?: "unknown"
            
            Log.i(TAG, "========================================")
            Log.i(TAG, "Received clipboard change from Xposed hook!")
            Log.i(TAG, "Source: $sourcePackage")
            Log.i(TAG, "MimeType: $mimeType")
            Log.i(TAG, "Timestamp: $timestamp")
            Log.i(TAG, "Content length: ${content?.length ?: 0}")
            Log.i(TAG, "Content preview: ${content?.take(100) ?: "null"}...")
            Log.i(TAG, "========================================")
            
            if (content.isNullOrEmpty()) {
                Log.w(TAG, "Received empty clipboard content from Xposed hook")
                return
            }
            
            val clipboardContent = ClipboardContent(
                type = determineContentType(mimeType),
                data = content.toByteArray(),
                mimeType = mimeType,
                timestamp = timestamp,
                source = "xposed_hook:$sourcePackage",
                size = content.length.toLong()
            )
            
            if (clipboardListener != null) {
                Log.d(TAG, "Invoking clipboard listener")
                clipboardListener?.invoke(clipboardContent)
            } else {
                Log.w(TAG, "No clipboard listener set!")
            }
            
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
