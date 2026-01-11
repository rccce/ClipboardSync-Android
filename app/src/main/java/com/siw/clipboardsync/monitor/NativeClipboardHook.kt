package com.siw.clipboardsync.monitor

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI wrapper for native clipboard system calls and hooks.
 * Provides direct access to system-level clipboard monitoring through native code.
 */
class NativeClipboardHook {
    
    companion object {
        private const val TAG = "NativeClipboardHook"
        private const val NATIVE_LIBRARY_NAME = "clipboardhook"
        
        private val isLibraryLoaded = AtomicBoolean(false)
        
        init {
            loadNativeLibrary()
        }
        
        private fun loadNativeLibrary() {
            try {
                System.loadLibrary(NATIVE_LIBRARY_NAME)
                isLibraryLoaded.set(true)
                Log.d(TAG, "Native clipboard hook library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native clipboard hook library", e)
                isLibraryLoaded.set(false)
            }
        }
    }
    
    private var isHookActive = AtomicBoolean(false)
    private var clipboardCallback: ((String, Long) -> Unit)? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    /**
     * Check if native library is available and loaded
     */
    fun isNativeLibraryAvailable(): Boolean {
        return isLibraryLoaded.get()
    }
    
    /**
     * Initialize native clipboard hooks
     * @return true if initialization successful, false otherwise
     */
    fun initializeHooks(): Boolean {
        if (!isNativeLibraryAvailable()) {
            Log.w(TAG, "Native library not available, cannot initialize hooks")
            return false
        }
        
        return try {
            val result = nativeInitializeClipboardHooks()
            if (result == 0) {
                Log.d(TAG, "Native clipboard hooks initialized successfully")
                true
            } else {
                Log.e(TAG, "Failed to initialize native clipboard hooks, error code: $result")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during native hook initialization", e)
            false
        }
    }
    
    /**
     * Register callback for clipboard change events
     */
    fun registerClipboardCallback(callback: (content: String, timestamp: Long) -> Unit) {
        this.clipboardCallback = callback
        
        if (!isNativeLibraryAvailable()) {
            Log.w(TAG, "Native library not available, cannot register callback")
            return
        }
        
        try {
            nativeRegisterClipboardCallback()
            Log.d(TAG, "Native clipboard callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register native clipboard callback", e)
        }
    }
    
    /**
     * Start native clipboard monitoring
     */
    fun startMonitoring(): Boolean {
        if (!isNativeLibraryAvailable()) {
            Log.w(TAG, "Native library not available, cannot start monitoring")
            return false
        }
        
        if (isHookActive.get()) {
            Log.w(TAG, "Native clipboard monitoring already active")
            return true
        }
        
        return try {
            val result = nativeStartClipboardMonitoring()
            if (result == 0) {
                isHookActive.set(true)
                Log.d(TAG, "Native clipboard monitoring started")
                true
            } else {
                Log.e(TAG, "Failed to start native clipboard monitoring, error code: $result")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during native monitoring start", e)
            false
        }
    }
    
    /**
     * Stop native clipboard monitoring
     */
    fun stopMonitoring(): Boolean {
        if (!isNativeLibraryAvailable()) {
            return true // Consider it stopped if library not available
        }
        
        if (!isHookActive.get()) {
            return true // Already stopped
        }
        
        return try {
            val result = nativeStopClipboardMonitoring()
            isHookActive.set(false)
            Log.d(TAG, "Native clipboard monitoring stopped")
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Exception during native monitoring stop", e)
            isHookActive.set(false)
            false
        }
    }
    
    /**
     * Check if native monitoring is currently active
     */
    fun isMonitoringActive(): Boolean {
        return isHookActive.get()
    }
    
    /**
     * Get current clipboard content directly from system
     */
    fun getCurrentClipboardContent(): String? {
        if (!isNativeLibraryAvailable()) {
            return null
        }
        
        return try {
            nativeGetClipboardContent()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get clipboard content from native", e)
            null
        }
    }
    
    /**
     * Set clipboard content directly at system level
     */
    fun setClipboardContent(content: String): Boolean {
        if (!isNativeLibraryAvailable()) {
            return false
        }
        
        return try {
            val result = nativeSetClipboardContent(content)
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set clipboard content via native", e)
            false
        }
    }
    
    /**
     * Cleanup native resources
     */
    fun cleanup() {
        if (!isNativeLibraryAvailable()) {
            return
        }
        
        try {
            stopMonitoring()
            nativeCleanup()
            clipboardCallback = null
            Log.d(TAG, "Native clipboard hook cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during native cleanup", e)
        }
    }
    
    // JNI callback method called from native code
    @Suppress("unused")
    private fun onClipboardChanged(content: String, timestamp: Long) {
        coroutineScope.launch {
            try {
                clipboardCallback?.invoke(content, timestamp)
            } catch (e: Exception) {
                Log.e(TAG, "Exception in clipboard callback", e)
            }
        }
    }
    
    // JNI callback for native errors
    @Suppress("unused")
    private fun onNativeError(errorCode: Int, errorMessage: String) {
        Log.e(TAG, "Native error occurred: code=$errorCode, message=$errorMessage")
        
        // Handle critical errors by stopping monitoring
        if (errorCode < 0) {
            isHookActive.set(false)
        }
    }
    
    // Native method declarations
    private external fun nativeInitializeClipboardHooks(): Int
    private external fun nativeRegisterClipboardCallback(): Int
    private external fun nativeStartClipboardMonitoring(): Int
    private external fun nativeStopClipboardMonitoring(): Int
    private external fun nativeGetClipboardContent(): String?
    private external fun nativeSetClipboardContent(content: String): Int
    private external fun nativeCleanup(): Int
}