package com.siw.clipboardsync.monitor

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.utils.DeviceUtils
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Clipboard monitor that syncs clipboard content when the app comes to foreground.
 * 
 * This is the fallback monitor for devices without Xposed or Shizuku.
 * It does NOT provide background clipboard sync - it only syncs when:
 * 1. The app comes to foreground
 * 2. The user manually triggers a sync
 * 
 * This is intentional based on real-world testing showing that other methods
 * (System Hooks, READ_LOGS, Accessibility Service, Polling) cannot reliably
 * achieve background clipboard sync on modern Android versions.
 */
class ForegroundSyncClipboardMonitor(
    private val context: Context
) : ClipboardMonitor, DefaultLifecycleObserver {
    
    companion object {
        private const val TAG = "ForegroundSyncMonitor"
        private const val SYNC_DEBOUNCE_MS = 500L
    }
    
    private val monitorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)
    private var clipboardListener: ClipboardListener? = null
    private var clipboardManager: ClipboardManager? = null
    private var lastClipboardHash: String? = null
    private var lastSyncTime = 0L
    
    override suspend fun startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            Log.i(TAG, "Starting foreground sync clipboard monitoring")
            
            clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            
            // Register for app lifecycle changes
            withContext(Dispatchers.Main) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(this@ForegroundSyncClipboardMonitor)
            }
            
            // Initialize with current clipboard content
            initializeClipboardState()
            
            Log.i(TAG, "Foreground sync monitoring started - will sync when app comes to foreground")
        }
    }
    
    override suspend fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping foreground sync clipboard monitoring")
            
            withContext(Dispatchers.Main) {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(this@ForegroundSyncClipboardMonitor)
            }
            
            monitorScope.cancel()
        }
    }
    
    override fun isMonitoring(): Boolean = isMonitoring.get()
    
    override fun getMonitoringMethod(): MonitoringMethod = MonitoringMethod.FOREGROUND_SYNC
    
    override fun setClipboardListener(listener: ClipboardListener) {
        this.clipboardListener = listener
    }
    
    // Lifecycle observer - sync when app comes to foreground
    // DISABLED: Foreground sync is now handled by ClipboardMonitorService for reliability
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        // Do nothing - ClipboardMonitorService handles foreground sync
        Log.d(TAG, "App came to foreground - sync handled by ClipboardMonitorService")
    }
    
    /**
     * Initialize clipboard state to avoid syncing existing content on first launch.
     */
    private fun initializeClipboardState() {
        try {
            val clipData = clipboardManager?.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val content = extractTextContent(clipData)
                if (content != null) {
                    lastClipboardHash = DeviceUtils.generateContentHash(content)
                    Log.d(TAG, "Initialized with current clipboard hash")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize clipboard state", e)
        }
    }
    
    /**
     * Check clipboard for changes and sync if changed.
     * Called when app comes to foreground.
     */
    private suspend fun checkAndSyncClipboard() {
        try {
            val currentTime = System.currentTimeMillis()
            
            // Debounce rapid checks
            if (currentTime - lastSyncTime < SYNC_DEBOUNCE_MS) {
                return
            }
            
            val clipData = clipboardManager?.primaryClip
            if (clipData == null || clipData.itemCount == 0) {
                Log.d(TAG, "No clipboard data available")
                return
            }
            
            val content = extractTextContent(clipData)
            if (content.isNullOrBlank()) {
                return
            }
            
            val contentHash = DeviceUtils.generateContentHash(content)
            
            // Check if content has changed
            if (contentHash != lastClipboardHash) {
                lastClipboardHash = contentHash
                lastSyncTime = currentTime
                
                Log.i(TAG, "Clipboard content changed, syncing: ${content.take(50)}...")
                
                // Notify listener
                val clipboardContent = ClipboardContent(
                    type = ClipboardContent.ContentType.TEXT,
                    data = content.toByteArray(Charsets.UTF_8),
                    mimeType = "text/plain",
                    timestamp = currentTime,
                    source = "foreground_sync",
                    size = content.length.toLong(),
                    metadata = mapOf(
                        "method" to "foreground_sync",
                        "android_version" to Build.VERSION.SDK_INT.toString()
                    )
                )
                
                clipboardListener?.onClipboardChanged(clipboardContent, currentTime)
            } else {
                Log.d(TAG, "Clipboard content unchanged")
            }
            
        } catch (e: SecurityException) {
            Log.w(TAG, "Clipboard access denied: ${e.message}")
            clipboardListener?.onMonitoringError(ClipboardError.PermissionDenied("clipboard"))
        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard", e)
            clipboardListener?.onMonitoringError(ClipboardError.UnknownError(e))
        }
    }
    
    /**
     * Manually trigger a clipboard sync.
     * Can be called from UI when user wants to force sync.
     */
    suspend fun manualSync() {
        Log.d(TAG, "Manual sync requested")
        lastClipboardHash = null // Force sync by clearing hash
        checkAndSyncClipboard()
    }
    
    /**
     * Extract text content from ClipData.
     */
    private fun extractTextContent(clipData: android.content.ClipData): String? {
        return try {
            val item = clipData.getItemAt(0)
            item.text?.toString() 
                ?: item.coerceToText(context)?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract clipboard content", e)
            null
        }
    }
}
