package com.siw.clipboardsync.monitor.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.siw.clipboardsync.data.repository.AuthRepository
import com.siw.clipboardsync.data.repository.ClipboardRepository
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
 */
class StaticClipboardReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "StaticClipboardReceiver"
        const val ACTION_CLIPBOARD_CHANGED = "com.siw.clipboardsync.CLIPBOARD_CHANGED"
    }
    
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StaticClipboardReceiverEntryPoint {
        fun clipboardRepository(): ClipboardRepository
        fun authRepository(): AuthRepository
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
            
            // Get dependencies via Hilt EntryPoint
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                StaticClipboardReceiverEntryPoint::class.java
            )
            val clipboardRepository = entryPoint.clipboardRepository()
            val authRepository = entryPoint.authRepository()
            
            // Use pending result to keep receiver alive during async operation
            val pendingResult = goAsync()
            
            scope.launch {
                try {
                    // Get device ID
                    val deviceId = authRepository.getDeviceId()
                    if (deviceId.isNullOrEmpty()) {
                        Log.w(TAG, "No device ID found, cannot sync clipboard")
                        return@launch
                    }
                    
                    // Determine content type
                    val contentType = when {
                        mimeType.startsWith("text/") -> "text"
                        mimeType.startsWith("image/") -> "image"
                        else -> "text"
                    }
                    
                    // Sync to server
                    Log.d(TAG, "Syncing clipboard content to server...")
                    val result = clipboardRepository.syncClipboard(
                        content = content,
                        contentType = contentType,
                        deviceId = deviceId
                    )
                    
                    result.fold(
                        onSuccess = { item ->
                            Log.i(TAG, "Clipboard synced successfully! ID: ${item.id}")
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to sync clipboard: ${error.message}")
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing clipboard content", e)
                } finally {
                    pendingResult.finish()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing clipboard broadcast", e)
        }
    }
}
