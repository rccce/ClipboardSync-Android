package com.siw.clipboardsync.monitor

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import kotlinx.coroutines.*
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Clipboard monitor implementation using Shizuku.
 * Shizuku provides ADB-level permissions without root, allowing
 * background clipboard access on Android 10+ devices.
 * 
 * Requirements:
 * - Shizuku app must be installed and running
 * - User must grant permission to this app in Shizuku
 */
class ShizukuClipboardMonitor(
    private val context: Context
) : ClipboardMonitor {
    
    companion object {
        private const val TAG = "ShizukuClipboardMonitor"
        private const val CLIPBOARD_CHECK_INTERVAL_MS = 1000L
        private const val CLIPBOARD_CHANGE_DEBOUNCE_MS = 300L
        
        // Shizuku permission request code
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }
    
    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)
    private var clipboardListener: ClipboardListener? = null
    private var lastClipboardContent: String? = null
    private var lastChangeTime = 0L
    private var monitoringJob: Job? = null
    
    // Cached reflection objects
    private var iClipboardClass: Class<*>? = null
    private var getPrimaryClipMethod: Method? = null
    private var clipboardInterface: Any? = null
    
    // Shizuku permission listener
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Shizuku permission granted")
                monitorScope.launch {
                    try {
                        startMonitoringInternal()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start monitoring after permission grant", e)
                    }
                }
            } else {
                Log.w(TAG, "Shizuku permission denied")
                monitorScope.launch {
                    clipboardListener?.onMonitoringError(
                        ClipboardError.PermissionDenied("shizuku")
                    )
                }
            }
        }
    }
    
    // Shizuku binder received listener
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        if (isMonitoring.get()) {
            monitorScope.launch {
                try {
                    startMonitoringInternal()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start monitoring after binder received", e)
                }
            }
        }
    }
    
    // Shizuku binder dead listener
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        monitoringJob?.cancel()
        monitorScope.launch {
            clipboardListener?.onMonitoringError(
                ClipboardError.ServiceDisconnected("Shizuku", Exception("Shizuku service died"))
            )
        }
    }
    
    init {
        // Register Shizuku listeners
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Shizuku listeners: ${e.message}")
        }
    }
    
    override suspend fun startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            Log.d(TAG, "Starting Shizuku clipboard monitoring")
            
            try {
                // Check if Shizuku is available
                if (!isShizukuAvailable()) {
                    throw ClipboardMonitorException(
                        ClipboardError.ShizukuUnavailable
                    )
                }
                
                // Check if we have permission
                if (!hasShizukuPermission()) {
                    Log.i(TAG, "Requesting Shizuku permission")
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                    return
                }
                
                startMonitoringInternal()
                
            } catch (e: Exception) {
                isMonitoring.set(false)
                val error = when (e) {
                    is ClipboardMonitorException -> e.clipboardError
                    else -> ClipboardError.UnknownError(e)
                }
                clipboardListener?.onMonitoringError(error)
                throw e
            }
        }
    }
    
    private suspend fun startMonitoringInternal() {
        Log.i(TAG, "Starting Shizuku clipboard monitoring internal")
        
        // Initialize reflection
        initializeReflection()
        
        monitoringJob = monitorScope.launch {
            while (isActive && isMonitoring.get()) {
                try {
                    checkClipboardWithShizuku()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking clipboard", e)
                }
                delay(CLIPBOARD_CHECK_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Initialize reflection for accessing hidden clipboard API.
     */
    private fun initializeReflection() {
        try {
            // Enable hidden API bypass for Android 9+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("")
                Log.d(TAG, "Hidden API bypass enabled")
            }
            
            // Get clipboard service binder
            val clipboardBinder = SystemServiceHelper.getSystemService("clipboard")
            if (clipboardBinder == null) {
                Log.e(TAG, "Failed to get clipboard service binder")
                return
            }
            
            // Wrap with Shizuku
            val wrappedBinder = ShizukuBinderWrapper(clipboardBinder)
            
            // Get IClipboard$Stub class and asInterface method
            val stubClass = Class.forName("android.content.IClipboard\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            clipboardInterface = asInterfaceMethod.invoke(null, wrappedBinder)
            
            if (clipboardInterface == null) {
                Log.e(TAG, "Failed to get clipboard interface via asInterface")
                return
            }
            
            // Get the actual class of the clipboard interface (proxy)
            val clipboardClass = clipboardInterface!!.javaClass
            Log.d(TAG, "Clipboard interface class: ${clipboardClass.name}")
            
            // Log all methods that contain "Clip" in the name
            Log.d(TAG, "Methods containing 'Clip' or 'Primary' in clipboard interface:")
            clipboardClass.methods.forEach { method ->
                if (method.name.contains("Clip", ignoreCase = true) || method.name.contains("Primary", ignoreCase = true)) {
                    Log.d(TAG, "  ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
                }
            }
            
            // Try to find getPrimaryClip method on the actual interface class
            getPrimaryClipMethod = findGetPrimaryClipMethod(clipboardClass)
            
            if (getPrimaryClipMethod != null) {
                Log.i(TAG, "Reflection initialized successfully with method: getPrimaryClip(${getPrimaryClipMethod?.parameterTypes?.joinToString { it.simpleName }})")
            } else {
                Log.e(TAG, "Failed to find compatible getPrimaryClip method")
                
                // Log all methods for debugging
                Log.d(TAG, "All methods in clipboard interface (${clipboardClass.methods.size} total):")
                clipboardClass.methods.forEach { method ->
                    Log.d(TAG, "  ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize reflection: ${e.message}", e)
        }
    }
    
    /**
     * Find the correct getPrimaryClip method signature for the current Android version.
     */
    private fun findGetPrimaryClipMethod(clipboardClass: Class<*>): Method? {
        // List of method signatures to try, in order of preference
        val signatures = listOf(
            // Android 12+ (SDK 31+) with deviceId
            arrayOf(String::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            // Android 10-11 (SDK 29-30) without deviceId
            arrayOf(String::class.java, String::class.java, Int::class.javaPrimitiveType),
            // Android 9 and below
            arrayOf(String::class.java, Int::class.javaPrimitiveType),
            // Some ROMs may have different signatures
            arrayOf(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
        )
        
        for (signature in signatures) {
            try {
                val method = clipboardClass.getMethod("getPrimaryClip", *signature)
                Log.d(TAG, "Found getPrimaryClip method with signature: ${signature.joinToString { it?.simpleName ?: "null" }}")
                return method
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "getPrimaryClip not found with signature: ${signature.joinToString { it?.simpleName ?: "null" }}")
            }
        }
        
        // Last resort: try to find any getPrimaryClip method
        return try {
            val methods = clipboardClass.methods.filter { it.name == "getPrimaryClip" }
            Log.d(TAG, "Found ${methods.size} getPrimaryClip methods via filter")
            methods.forEach { method ->
                Log.d(TAG, "  getPrimaryClip(${method.parameterTypes.joinToString { it.simpleName }})")
            }
            if (methods.isNotEmpty()) {
                val method = methods.first()
                Log.d(TAG, "Using first available getPrimaryClip method: ${method.parameterTypes.joinToString { it.simpleName }}")
                method
            } else {
                // Try declared methods
                val declaredMethods = clipboardClass.declaredMethods.filter { it.name == "getPrimaryClip" }
                Log.d(TAG, "Found ${declaredMethods.size} getPrimaryClip declared methods")
                if (declaredMethods.isNotEmpty()) {
                    val method = declaredMethods.first()
                    method.isAccessible = true
                    Log.d(TAG, "Using first declared getPrimaryClip method: ${method.parameterTypes.joinToString { it.simpleName }}")
                    method
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding getPrimaryClip method", e)
            null
        }
    }
    
    override suspend fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping Shizuku clipboard monitoring")
            monitoringJob?.cancel()
            monitoringJob = null
        }
    }
    
    override fun isMonitoring(): Boolean = isMonitoring.get()
    
    override fun getMonitoringMethod(): MonitoringMethod = MonitoringMethod.SHIZUKU
    
    override fun setClipboardListener(listener: ClipboardListener) {
        this.clipboardListener = listener
    }

    /**
     * Checks clipboard content using Shizuku's elevated permissions.
     */
    private suspend fun checkClipboardWithShizuku() {
        try {
            val currentTime = System.currentTimeMillis()
            
            // Debounce
            if (currentTime - lastChangeTime < CLIPBOARD_CHANGE_DEBOUNCE_MS) {
                return
            }
            
            // Get clipboard content using Shizuku
            val clipboardText = getClipboardTextViaShizuku()
            
            if (clipboardText != null && clipboardText != lastClipboardContent && clipboardText.isNotEmpty()) {
                lastClipboardContent = clipboardText
                lastChangeTime = currentTime
                
                Log.i(TAG, "Clipboard changed via Shizuku: ${clipboardText.take(50)}...")
                
                val content = ClipboardContent(
                    type = ClipboardContent.ContentType.TEXT,
                    data = clipboardText.toByteArray(Charsets.UTF_8),
                    mimeType = "text/plain",
                    timestamp = currentTime,
                    source = "shizuku",
                    size = clipboardText.length.toLong(),
                    metadata = mapOf(
                        "method" to "shizuku",
                        "android_version" to Build.VERSION.SDK_INT.toString()
                    )
                )
                
                // Notify listener
                val listener = clipboardListener
                if (listener != null) {
                    Log.d(TAG, "Notifying clipboard listener of change")
                    withContext(Dispatchers.Main) {
                        listener.onClipboardChanged(content, currentTime)
                    }
                    Log.d(TAG, "Clipboard listener notified successfully")
                } else {
                    Log.w(TAG, "No clipboard listener set, cannot notify of clipboard change!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard via Shizuku", e)
        }
    }
    
    /**
     * Gets clipboard text using Shizuku's system service access via reflection.
     */
    private fun getClipboardTextViaShizuku(): String? {
        return try {
            if (clipboardInterface == null || getPrimaryClipMethod == null) {
                initializeReflection()
                if (clipboardInterface == null || getPrimaryClipMethod == null) {
                    Log.w(TAG, "Reflection not initialized, cannot get clipboard")
                    return null
                }
            }
            
            // Call getPrimaryClip via reflection based on parameter count
            val paramCount = getPrimaryClipMethod?.parameterCount ?: 0
            val paramTypes = getPrimaryClipMethod?.parameterTypes ?: emptyArray()
            
            // Log.d(TAG, "Calling getPrimaryClip with $paramCount parameters")
            
            // Use shell package name since Shizuku runs as UID 2000 (shell)
            // The system checks if the calling UID matches the package name
            val callingPackage = "com.android.shell"
            
            val clipData: ClipData? = when (paramCount) {
                4 -> {
                    // Android 12+ with deviceId: (String, String, int, int)
                    getPrimaryClipMethod?.invoke(
                        clipboardInterface,
                        callingPackage,
                        null,  // attributionTag - use null for shell
                        0,  // userId
                        0   // deviceId
                    ) as? ClipData
                }
                3 -> {
                    // Check if it's (String, String, int) or (String, int, int)
                    if (paramTypes.getOrNull(1) == String::class.java) {
                        // Android 10-11: (String, String, int)
                        getPrimaryClipMethod?.invoke(
                            clipboardInterface,
                            callingPackage,
                            null,  // attributionTag
                            0   // userId
                        ) as? ClipData
                    } else {
                        // Some ROMs: (String, int, int)
                        getPrimaryClipMethod?.invoke(
                            clipboardInterface,
                            callingPackage,
                            0,  // userId
                            0   // deviceId
                        ) as? ClipData
                    }
                }
                2 -> {
                    // Android 9 and below: (String, int)
                    getPrimaryClipMethod?.invoke(
                        clipboardInterface,
                        callingPackage,
                        0   // userId
                    ) as? ClipData
                }
                else -> {
                    Log.w(TAG, "Unexpected parameter count: $paramCount")
                    null
                }
            }
            
            if (clipData == null || clipData.itemCount == 0) {
                return null
            }
            
            val item = clipData.getItemAt(0)
            item.text?.toString() ?: item.coerceToText(context)?.toString()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting clipboard via Shizuku reflection: ${e.message}", e)
            // Reset reflection objects to retry initialization
            clipboardInterface = null
            getPrimaryClipMethod = null
            null
        }
    }
    
    /**
     * Checks if Shizuku is available and running.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku not available: ${e.message}")
            false
        }
    }
    
    /**
     * Checks if we have Shizuku permission.
     */
    fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) {
                return false
            }
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Shizuku permission: ${e.message}")
            false
        }
    }
    
    /**
     * Requests Shizuku permission.
     */
    fun requestShizukuPermission() {
        if (isShizukuAvailable()) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }
    
    /**
     * Gets the Shizuku version if available.
     */
    fun getShizukuVersion(): Int {
        return try {
            if (isShizukuAvailable()) {
                Shizuku.getVersion()
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up Shizuku listeners: ${e.message}")
        }
        monitorScope.cancel()
    }
}
