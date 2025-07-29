package com.siw.clipboardsync.monitor

import android.content.Context
import android.util.Log
import com.siw.clipboardsync.monitor.model.ClipboardContent
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for native system-level clipboard hooks.
 * Provides direct access to system clipboard callbacks through JNI.
 * Updated to use the new NativeClipboardHook implementation.
 */
@Singleton
class NativeHookManager @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "NativeHookManager"
    }
    
    private var nativeClipboardHook: NativeClipboardHook? = null
    private var isInitialized = false
    private var clipboardCallback: ((String) -> Unit)? = null
    
    // KernelSU alternative monitoring
    private var kernelSuMonitoringJob: kotlinx.coroutines.Job? = null
    private var lastClipboardContent: String? = null
    private val coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    
    /**
     * Checks if native hooks are available on this device.
     */
    fun isAvailable(): Boolean {
        return try {
            // First try the standard native library approach
            val hook = getNativeHook()
            val libraryAvailable = hook.isNativeLibraryAvailable()
            Log.d(TAG, "Native library availability: $libraryAvailable")
            
            if (libraryAvailable) {
                return true
            }
            
            // For KernelSU devices where native library loading fails due to security restrictions,
            // we'll use a different approach - check if we have root access for system-level operations
            val rootProcess = Runtime.getRuntime().exec("su -c 'id'")
            val exitCode = rootProcess.waitFor()
            val output = rootProcess.inputStream.bufferedReader().readText().trim()
            rootProcess.destroy()
            
            val rootAvailable = exitCode == 0 && output.contains("uid=0(root)")
            Log.d(TAG, "Native hooks availability via root access: $rootAvailable")
            
            if (rootAvailable) {
                Log.i(TAG, "Native library not available, but root access detected - enabling alternative system hooks")
                return true
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking native hook availability", e)
            false
        }
    }
    
    /**
     * Initialize the native hook system
     */
    suspend fun initialize(): Boolean {
        return try {
            val hook = getNativeHook()
            
            // Try native library initialization first
            if (hook.isNativeLibraryAvailable()) {
                val result = hook.initializeHooks()
                if (result) {
                    isInitialized = true
                    Log.i(TAG, "Native hooks initialized successfully via native library")
                    return true
                } else {
                    Log.e(TAG, "Failed to initialize native hooks via library")
                }
            } else {
                Log.w(TAG, "Native library not available")
            }
            
            // For KernelSU devices, use alternative initialization
            val rootProcess = Runtime.getRuntime().exec("su -c 'id'")
            val exitCode = rootProcess.waitFor()
            val output = rootProcess.inputStream.bufferedReader().readText().trim()
            rootProcess.destroy()
            
            if (exitCode == 0 && output.contains("uid=0(root)")) {
                isInitialized = true
                Log.i(TAG, "Alternative system hooks initialized successfully for KernelSU device")
                return true
            }
            
            Log.e(TAG, "Failed to initialize any hook system")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception during hook initialization", e)
            false
        }
    }
    
    /**
     * Registers a callback for clipboard change events.
     * @param callback function to call when clipboard changes
     */
    fun registerClipboardCallback(callback: (String) -> Unit) {
        try {
            if (!isAvailable()) {
                Log.w(TAG, "Native hooks not available, cannot register callback")
                return
            }
            
            this.clipboardCallback = callback
            
            val hook = getNativeHook()
            
            // Try native library callback first
            if (hook.isNativeLibraryAvailable()) {
                hook.registerClipboardCallback { content, timestamp ->
                    try {
                        clipboardCallback?.invoke(content)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in clipboard callback", e)
                    }
                }
                Log.i(TAG, "Native clipboard callback registered successfully via library")
            } else {
                // For KernelSU devices, we'll use Android ClipboardManager with polling
                Log.i(TAG, "Native clipboard callback registered successfully for KernelSU device")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register native clipboard callback", e)
        }
    }
    
    /**
     * Start native clipboard monitoring
     */
    suspend fun startMonitoring(): Boolean {
        return try {
            if (!isInitialized) {
                Log.w(TAG, "Native hooks not initialized")
                return false
            }
            
            val hook = getNativeHook()
            
            // Try native library monitoring first
            if (hook.isNativeLibraryAvailable()) {
                val result = hook.startMonitoring()
                if (result) {
                    Log.i(TAG, "Native clipboard monitoring started via native library")
                    return true
                } else {
                    Log.e(TAG, "Failed to start native clipboard monitoring via library")
                }
            }
            
            // For KernelSU devices, start actual clipboard monitoring using Android ClipboardManager
            startKernelSuClipboardMonitoring()
            Log.i(TAG, "KernelSU clipboard monitoring started successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting native monitoring", e)
            false
        }
    }
    
    /**
     * Start KernelSU-compatible clipboard monitoring using root-based approach
     */
    private fun startKernelSuClipboardMonitoring() {
        // Cancel any existing monitoring job
        kernelSuMonitoringJob?.cancel()
        
        try {
            // Get initial clipboard content using root access
            lastClipboardContent = getRootClipboardContent()
            Log.d(TAG, "KernelSU clipboard monitoring started, initial content: ${lastClipboardContent?.take(50)}")
            
            // Start root-based clipboard monitoring that works in background
            startRootBasedClipboardMonitoring()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting KernelSU clipboard monitoring", e)
        }
    }
    
    /**
     * Get clipboard content using root access to bypass Android restrictions
     */
    private fun getRootClipboardContent(): String? {
        return try {
            // Try multiple approaches to get clipboard content with root access
            
            // Approach 1: Try to read clipboard through system service
            try {
                val process = Runtime.getRuntime().exec("su -c 'service call clipboard 2'")
                val exitCode = process.waitFor()
                
                if (exitCode == 0) {
                    val output = process.inputStream.bufferedReader().readText()
                    val parsed = parseClipboardServiceOutput(output)
                    if (parsed != null && parsed.isNotEmpty()) {
                        return parsed
                    }
                }
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Service call approach failed", e)
            }
            
            // Approach 2: Try to access clipboard through dumpsys
            try {
                val process = Runtime.getRuntime().exec("su -c 'dumpsys clipboard'")
                val exitCode = process.waitFor()
                
                if (exitCode == 0) {
                    val output = process.inputStream.bufferedReader().readText()
                    val parsed = parseClipboardDumpsysOutput(output)
                    if (parsed != null && parsed.isNotEmpty()) {
                        return parsed
                    }
                }
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Dumpsys approach failed", e)
            }
            
            // Approach 3: Fallback to regular clipboard access (may not work in background)
            try {
                val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                return clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
            } catch (e: Exception) {
                Log.w(TAG, "Regular clipboard access failed", e)
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting root clipboard content", e)
            null
        }
    }
    
    /**
     * Parse clipboard dumpsys output to extract text content
     */
    private fun parseClipboardDumpsysOutput(output: String): String? {
        return try {
            // Look for clipboard content in dumpsys output
            val lines = output.split("\n")
            for (line in lines) {
                if (line.contains("ClipData") || line.contains("text/plain")) {
                    // Try to extract text content
                    val textMatch = Regex("\"([^\"]+)\"").find(line)
                    if (textMatch != null) {
                        return textMatch.groupValues[1]
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing dumpsys output", e)
            null
        }
    }
    
    /**
     * Parse clipboard service output to extract text content
     */
    private fun parseClipboardServiceOutput(output: String): String? {
        return try {
            // The service call output contains clipboard data in a specific format
            // We need to extract the text content from it
            val lines = output.split("\n")
            for (line in lines) {
                if (line.contains("'") && line.length > 10) {
                    // Extract text between quotes
                    val startIndex = line.indexOf("'")
                    val endIndex = line.lastIndexOf("'")
                    if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                        return line.substring(startIndex + 1, endIndex)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing clipboard service output", e)
            null
        }
    }
    
    /**
     * Start root-based clipboard monitoring that works in background
     */
    private fun startRootBasedClipboardMonitoring() {
        kernelSuMonitoringJob = coroutineScope.launch {
            var pollCount = 0
            Log.i(TAG, "Starting KernelSU root-based clipboard monitoring")
            
            // Monitor clipboard using root access
            while (isActive) {
                try {
                    pollCount++
                    val currentContent = getRootClipboardContent()
                    
                    // Log every 20th poll to show it's working
                    if (pollCount % 20 == 0) {
                        Log.d(TAG, "KernelSU root monitoring (poll #$pollCount), current: ${currentContent?.take(30)}")
                    }
                    
                    if (currentContent != null && currentContent != lastClipboardContent && currentContent.isNotEmpty()) {
                        Log.i(TAG, "KernelSU detected clipboard change via root: ${currentContent.take(50)}...")
                        Log.i(TAG, "Previous content was: ${lastClipboardContent?.take(50)}")
                        lastClipboardContent = currentContent
                        
                        // Notify callback
                        Log.i(TAG, "Invoking clipboard callback for KernelSU root change")
                        clipboardCallback?.invoke(currentContent)
                    }
                    
                    // Poll every 1 second for root-based monitoring
                    kotlinx.coroutines.delay(1000)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in KernelSU root clipboard monitoring", e)
                    kotlinx.coroutines.delay(2000) // Wait longer on error
                }
            }
            
            Log.w(TAG, "KernelSU root clipboard monitoring loop ended")
        }
    }
    
    /**
     * Fallback polling method for KernelSU clipboard monitoring
     */
    private fun startPollingFallback(clipboardManager: android.content.ClipboardManager) {
        kernelSuMonitoringJob = coroutineScope.launch {
            var pollCount = 0
            Log.i(TAG, "Starting KernelSU polling fallback")
            
            // Poll clipboard for changes
            while (isActive) {
                try {
                    pollCount++
                    val currentContent = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
                    
                    // Log every 10th poll to show it's working
                    if (pollCount % 10 == 0) {
                        Log.d(TAG, "KernelSU polling clipboard (poll #$pollCount), current: ${currentContent?.take(30)}")
                    }
                    
                    if (currentContent != null && currentContent != lastClipboardContent) {
                        Log.i(TAG, "KernelSU detected clipboard change via polling: ${currentContent.take(50)}...")
                        Log.i(TAG, "Previous content was: ${lastClipboardContent?.take(50)}")
                        lastClipboardContent = currentContent
                        
                        // Notify callback
                        Log.i(TAG, "Invoking clipboard callback for KernelSU change")
                        clipboardCallback?.invoke(currentContent)
                    }
                    
                    // Poll every 500ms for responsive detection
                    kotlinx.coroutines.delay(500)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in KernelSU clipboard polling", e)
                    kotlinx.coroutines.delay(1000) // Wait longer on error
                }
            }
            
            Log.w(TAG, "KernelSU clipboard polling loop ended")
        }
    }
    
    /**
     * Stop native clipboard monitoring
     */
    suspend fun stopMonitoring(): Boolean {
        return try {
            // Stop KernelSU monitoring job if running
            kernelSuMonitoringJob?.cancel()
            kernelSuMonitoringJob = null
            
            val hook = getNativeHook()
            val result = hook.stopMonitoring()
            if (result) {
                Log.i(TAG, "Native clipboard monitoring stopped")
            } else {
                Log.e(TAG, "Failed to stop native clipboard monitoring")
            }
            
            Log.i(TAG, "KernelSU clipboard monitoring stopped")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping native monitoring", e)
            false
        }
    }
    
    /**
     * Check if native monitoring is currently active
     */
    fun isMonitoring(): Boolean {
        return try {
            val hook = getNativeHook()
            hook.isMonitoringActive()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking monitoring status", e)
            false
        }
    }
    
    /**
     * Get current clipboard content directly from system
     */
    fun getCurrentClipboardContent(): String? {
        return try {
            val hook = getNativeHook()
            hook.getCurrentClipboardContent()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting clipboard content", e)
            null
        }
    }
    
    /**
     * Set clipboard content directly at system level
     */
    fun setClipboardContent(content: String): Boolean {
        return try {
            val hook = getNativeHook()
            hook.setClipboardContent(content)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting clipboard content", e)
            false
        }
    }
    
    /**
     * Cleanup native resources
     */
    fun cleanup() {
        try {
            nativeClipboardHook?.cleanup()
            nativeClipboardHook = null
            isInitialized = false
            clipboardCallback = null
            Log.i(TAG, "Native hook manager cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    /**
     * Get or create the native clipboard hook instance
     */
    private fun getNativeHook(): NativeClipboardHook {
        if (nativeClipboardHook == null) {
            nativeClipboardHook = NativeClipboardHook()
        }
        return nativeClipboardHook!!
    }
    
    /**
     * Gets information about native hook capabilities.
     */
    fun getNativeCapabilities(): Map<String, Any> {
        return try {
            val hook = getNativeHook()
            mapOf(
                "available" to hook.isNativeLibraryAvailable(),
                "initialized" to isInitialized,
                "monitoring" to hook.isMonitoringActive(),
                "libraryName" to "clipboardhook"
            )
        } catch (e: Exception) {
            mapOf(
                "available" to false,
                "error" to (e.message ?: "Unknown error")
            )
        }
    }
}