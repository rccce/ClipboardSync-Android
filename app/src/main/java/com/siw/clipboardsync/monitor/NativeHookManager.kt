package com.siw.clipboardsync.monitor

import android.content.Context
import android.util.Log
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.parser.ClipboardServiceParser
import com.siw.clipboardsync.service.RootDetectionService
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for native system-level clipboard hooks.
 * Provides direct access to system clipboard callbacks through JNI.
 * Updated to use the new NativeClipboardHook implementation.
 */
@Singleton
class NativeHookManager @Inject constructor(
    private val context: Context,
    private val rootDetectionService: RootDetectionService
) {
    
    companion object {
        private const val TAG = "NativeHookManager"
        private const val DEFAULT_POLL_INTERVAL_MS = 1000L
        private const val ERROR_POLL_INTERVAL_MS = 2000L
        private const val MAX_CONSECUTIVE_ERRORS = 5
    }
    
    private var nativeClipboardHook: NativeClipboardHook? = null
    private var isInitialized = false
    private var clipboardCallback: ((String) -> Unit)? = null
    
    // KernelSU alternative monitoring
    private var kernelSuMonitoringJob: kotlinx.coroutines.Job? = null
    private var lastClipboardContent: String? = null
    private val coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    
    // Error tracking
    private var consecutiveErrors = 0
    private var lastSuccessfulMethod: String? = null
    
    /**
     * Checks if native hooks are available on this device.
     */
    suspend fun isAvailable(): Boolean {
        return try {
            // First try the standard native library approach
            val hook = getNativeHook()
            val libraryAvailable = hook.isNativeLibraryAvailable()
            Log.d(TAG, "Native library availability: $libraryAvailable")
            
            if (libraryAvailable) {
                return true
            }
            
            // Use RootDetectionService to check for root capabilities
            val rootCapabilities = rootDetectionService.getRootCapabilities()
            val hasNativeAccess = rootCapabilities.hasNativeAccess
            
            Log.d(TAG, "Native hooks availability via root access: $hasNativeAccess")
            Log.d(TAG, "Root method: ${rootCapabilities.rootMethod}, Root accessible: ${rootCapabilities.isRootAccessible}")
            
            if (hasNativeAccess) {
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
            
            // Use RootDetectionService to check for alternative initialization
            val rootCapabilities = rootDetectionService.getRootCapabilities()
            if (rootCapabilities.hasNativeAccess) {
                isInitialized = true
                Log.i(TAG, "Alternative system hooks initialized successfully for ${rootCapabilities.rootMethod} device")
                return true
            }
            
            Log.e(TAG, "Failed to initialize any hook system - no native access available")
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
    suspend fun registerClipboardCallback(callback: (String) -> Unit) {
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
            
            // Approach 1: Try to read clipboard through service call (most reliable)
            try {
                val process = Runtime.getRuntime().exec("su -c 'service call clipboard 2 s16 com.android.shell'")
                val exitCode = process.waitFor()
                
                if (exitCode == 0) {
                    val output = process.inputStream.bufferedReader().readText()
                    val parseResult = ClipboardServiceParser.parseServiceCallOutput(output)
                    if (parseResult.success && !parseResult.content.isNullOrEmpty()) {
                        Log.d(TAG, "Got clipboard via service call (${parseResult.parseMethod}): ${parseResult.content.take(50)}")
                        process.destroy()
                        return parseResult.content
                    }
                }
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Service call approach failed", e)
            }
            
            // Approach 2: Try alternative service call format
            try {
                val process = Runtime.getRuntime().exec("su -c 'service call clipboard 2'")
                val exitCode = process.waitFor()
                
                if (exitCode == 0) {
                    val output = process.inputStream.bufferedReader().readText()
                    val parseResult = ClipboardServiceParser.parseServiceCallOutput(output)
                    if (parseResult.success && !parseResult.content.isNullOrEmpty()) {
                        Log.d(TAG, "Got clipboard via service call alt (${parseResult.parseMethod}): ${parseResult.content.take(50)}")
                        process.destroy()
                        return parseResult.content
                    }
                }
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Service call alt approach failed", e)
            }
            
            // Approach 3: Try to access clipboard through dumpsys
            try {
                val process = Runtime.getRuntime().exec("su -c 'dumpsys clipboard'")
                val exitCode = process.waitFor()
                
                if (exitCode == 0) {
                    val output = process.inputStream.bufferedReader().readText()
                    val parseResult = ClipboardServiceParser.parseDumpsysOutput(output)
                    if (parseResult.success && !parseResult.content.isNullOrEmpty()) {
                        Log.d(TAG, "Got clipboard via dumpsys (${parseResult.parseMethod}): ${parseResult.content.take(50)}")
                        process.destroy()
                        return parseResult.content
                    }
                }
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Dumpsys approach failed", e)
            }
            
            // Approach 4: Try content provider query
            try {
                val process = Runtime.getRuntime().exec("su -c 'content query --uri content://clipboard/text'")
                val exitCode = process.waitFor()
                
                if (exitCode == 0) {
                    val output = process.inputStream.bufferedReader().readText()
                    val parseResult = ClipboardServiceParser.parseContentQueryOutput(output)
                    if (parseResult.success && !parseResult.content.isNullOrEmpty()) {
                        Log.d(TAG, "Got clipboard via content query (${parseResult.parseMethod}): ${parseResult.content.take(50)}")
                        process.destroy()
                        return parseResult.content
                    }
                }
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Content query approach failed", e)
            }
            
            // Approach 5: Fallback to regular clipboard access (may not work in background)
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
     * @deprecated Use ClipboardServiceParser.parseDumpsysOutput instead
     */
    @Deprecated("Use ClipboardServiceParser.parseDumpsysOutput instead", ReplaceWith("ClipboardServiceParser.parseDumpsysOutput(output)"))
    private fun parseClipboardDumpsysOutput(output: String): String? {
        return ClipboardServiceParser.parseDumpsysOutput(output).content
    }
    
    /**
     * Parse clipboard service output to extract text content
     * @deprecated Use ClipboardServiceParser.parseServiceCallOutput instead
     */
    @Deprecated("Use ClipboardServiceParser.parseServiceCallOutput instead", ReplaceWith("ClipboardServiceParser.parseServiceCallOutput(output)"))
    private fun parseClipboardServiceOutput(output: String): String? {
        return ClipboardServiceParser.parseServiceCallOutput(output).content
    }
    
    /**
     * Start root-based clipboard monitoring that works in background
     */
    private fun startRootBasedClipboardMonitoring() {
        kernelSuMonitoringJob = coroutineScope.launch {
            var pollCount = 0
            consecutiveErrors = 0
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
                        consecutiveErrors = 0 // Reset error count on success
                        
                        // Notify callback
                        Log.i(TAG, "Invoking clipboard callback for KernelSU root change")
                        clipboardCallback?.invoke(currentContent)
                    } else if (currentContent != null) {
                        consecutiveErrors = 0 // Reset error count on successful read (even if no change)
                    }
                    
                    // Poll every 1 second for root-based monitoring
                    kotlinx.coroutines.delay(DEFAULT_POLL_INTERVAL_MS)
                    
                } catch (e: Exception) {
                    consecutiveErrors++
                    Log.e(TAG, "Error in KernelSU root clipboard monitoring (error #$consecutiveErrors)", e)
                    
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        Log.e(TAG, "Too many consecutive errors, stopping root-based monitoring")
                        break
                    }
                    
                    kotlinx.coroutines.delay(ERROR_POLL_INTERVAL_MS) // Wait longer on error
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
                "libraryName" to "clipboardhook",
                "consecutiveErrors" to consecutiveErrors,
                "lastSuccessfulMethod" to (lastSuccessfulMethod ?: "none"),
                "kernelSuMonitoringActive" to (kernelSuMonitoringJob?.isActive ?: false)
            )
        } catch (e: Exception) {
            mapOf(
                "available" to false,
                "error" to (e.message ?: "Unknown error")
            )
        }
    }
    
    /**
     * Tests clipboard access using all available methods and returns detailed results.
     * Useful for debugging and verifying root-based clipboard access.
     */
    suspend fun testClipboardAccess(): Map<String, Any> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Any>()
        
        // Test service call with package name
        try {
            val process1 = Runtime.getRuntime().exec("su -c 'service call clipboard 2 s16 com.android.shell'")
            val exitCode1 = process1.waitFor()
            val output1 = process1.inputStream.bufferedReader().readText()
            process1.destroy()
            
            val parseResult1 = ClipboardServiceParser.parseServiceCallOutput(output1)
            results["serviceCallWithPackage"] = mapOf(
                "exitCode" to exitCode1,
                "success" to parseResult1.success,
                "content" to (parseResult1.content?.take(100) ?: "null"),
                "parseMethod" to (parseResult1.parseMethod ?: "none"),
                "error" to (parseResult1.error ?: "none")
            )
        } catch (e: Exception) {
            results["serviceCallWithPackage"] = mapOf("error" to e.message)
        }
        
        // Test service call without package name
        try {
            val process2 = Runtime.getRuntime().exec("su -c 'service call clipboard 2'")
            val exitCode2 = process2.waitFor()
            val output2 = process2.inputStream.bufferedReader().readText()
            process2.destroy()
            
            val parseResult2 = ClipboardServiceParser.parseServiceCallOutput(output2)
            results["serviceCallSimple"] = mapOf(
                "exitCode" to exitCode2,
                "success" to parseResult2.success,
                "content" to (parseResult2.content?.take(100) ?: "null"),
                "parseMethod" to (parseResult2.parseMethod ?: "none"),
                "error" to (parseResult2.error ?: "none")
            )
        } catch (e: Exception) {
            results["serviceCallSimple"] = mapOf("error" to e.message)
        }
        
        // Test dumpsys
        try {
            val process3 = Runtime.getRuntime().exec("su -c 'dumpsys clipboard'")
            val exitCode3 = process3.waitFor()
            val output3 = process3.inputStream.bufferedReader().readText()
            process3.destroy()
            
            val parseResult3 = ClipboardServiceParser.parseDumpsysOutput(output3)
            results["dumpsys"] = mapOf(
                "exitCode" to exitCode3,
                "success" to parseResult3.success,
                "content" to (parseResult3.content?.take(100) ?: "null"),
                "parseMethod" to (parseResult3.parseMethod ?: "none"),
                "error" to (parseResult3.error ?: "none")
            )
        } catch (e: Exception) {
            results["dumpsys"] = mapOf("error" to e.message)
        }
        
        // Test content query
        try {
            val process4 = Runtime.getRuntime().exec("su -c 'content query --uri content://clipboard/text'")
            val exitCode4 = process4.waitFor()
            val output4 = process4.inputStream.bufferedReader().readText()
            process4.destroy()
            
            val parseResult4 = ClipboardServiceParser.parseContentQueryOutput(output4)
            results["contentQuery"] = mapOf(
                "exitCode" to exitCode4,
                "success" to parseResult4.success,
                "content" to (parseResult4.content?.take(100) ?: "null"),
                "parseMethod" to (parseResult4.parseMethod ?: "none"),
                "error" to (parseResult4.error ?: "none")
            )
        } catch (e: Exception) {
            results["contentQuery"] = mapOf("error" to e.message)
        }
        
        // Test regular clipboard access
        try {
            val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val content = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
            results["regularAccess"] = mapOf(
                "success" to (content != null),
                "content" to (content?.take(100) ?: "null")
            )
        } catch (e: Exception) {
            results["regularAccess"] = mapOf("error" to e.message)
        }
        
        return@withContext results
    }
}