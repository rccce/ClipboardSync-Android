package com.siw.clipboardsync.monitor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.xposed.ClipboardHookReceiver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for Xposed/LSPosed framework clipboard hooks.
 * Provides system-level clipboard monitoring through Xposed module integration.
 * 
 * Supports:
 * - Traditional Xposed framework
 * - LSPosed (modern Xposed implementation)
 * - EdXposed
 * - Clipboard Whitelist module integration
 */
@Singleton
class XposedHookManager @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "XposedHookManager"
        
        // Xposed framework class names
        private const val XPOSED_BRIDGE_CLASS = "de.robv.android.xposed.XposedBridge"
        private const val LSPOSED_BRIDGE_CLASS = "org.lsposed.lspd.core.Bridge"
        private const val LSPOSED_API_CLASS = "io.github.libxposed.api.XposedInterface"
        private const val EDXPOSED_BRIDGE_CLASS = "com.elderdrivers.riru.edxp.core.EdxpImpl"
        private const val CLIPBOARD_SERVICE_CLASS = "android.content.ClipboardManager"
        
        // Clipboard Whitelist module package
        private const val CLIPBOARD_WHITELIST_PACKAGE = "io.github.tehcneko.clipboardwhitelist"
        
        // Other Xposed-related packages
        private val XPOSED_MANAGER_PACKAGES = arrayOf(
            "de.robv.android.xposed.installer",
            "org.lsposed.manager",
            "com.solohsu.android.edxp.manager",
            "org.meowcat.edxposed.manager"
        )
    }
    
    private var isHooked = false
    private var clipboardCallback: ((ClipboardContent) -> Unit)? = null
    private var xposedFrameworkType: XposedFrameworkType = XposedFrameworkType.NONE
    private var clipboardHookReceiver: ClipboardHookReceiver? = null
    private var isReceiverRegistered = false
    
    enum class XposedFrameworkType {
        NONE,
        XPOSED,
        LSPOSED,
        EDXPOSED
    }
    
    /**
     * Checks if Xposed/LSPosed framework is available AND ACTIVE for clipboard monitoring.
     * 
     * For clipboard sync, the Xposed module hooks the SYSTEM FRAMEWORK (not our app),
     * so we need to verify:
     * 1. The framework is installed
     * 2. The framework is actually running (module enabled, device rebooted after enabling)
     * 
     * This prevents the app from thinking Xposed is available when:
     * - LSPosed is installed but module is disabled
     * - Module was just enabled but device hasn't rebooted
     * - Xposed framework was disabled in settings
     */
    fun isAvailable(): Boolean {
        return try {
            val frameworkType = detectXposedFramework()
            if (frameworkType == XposedFrameworkType.NONE) {
                Log.d(TAG, "No Xposed framework detected")
                return false
            }
            
            // CRITICAL: Check if Xposed is actually running, not just installed
            // This is the key fix - we need to verify the framework is active
            val isActive = isXposedFrameworkActive()
            if (!isActive) {
                Log.w(TAG, "Xposed framework installed ($frameworkType) but NOT ACTIVE")
                Log.w(TAG, "Module may be disabled or device needs reboot after enabling")
                return false
            }
            
            Log.i(TAG, "Xposed framework available and active: $frameworkType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Xposed framework availability", e)
            false
        }
    }
    
    /**
     * Checks if Xposed framework is actually active and running.
     * This verifies that the module is enabled and the device has been rebooted.
     * 
     * Detection methods:
     * 1. Check if we're running in Xposed environment (our app is hooked)
     * 2. Check for Xposed system properties
     * 3. Check for recent clipboard broadcasts from Xposed module
     */
    private fun isXposedFrameworkActive(): Boolean {
        // Method 1: Check if our app is running in Xposed environment
        if (isRunningInXposedEnvironment()) {
            Log.d(TAG, "Xposed framework active: running in Xposed environment")
            return true
        }
        
        // Method 2: Check system properties that indicate Xposed is running
        try {
            val xposedBridgeVersion = System.getProperty("xposed.bridge.version")
            if (xposedBridgeVersion != null) {
                Log.d(TAG, "Xposed framework active: bridge version $xposedBridgeVersion")
                return true
            }
        } catch (e: Exception) {
            // Property not available
        }
        
        // Method 3: Check for LSPosed-specific indicators
        try {
            // LSPosed sets this property when active
            val lsposedVersion = System.getProperty("lsposed.version")
            if (lsposedVersion != null) {
                Log.d(TAG, "LSPosed framework active: version $lsposedVersion")
                return true
            }
        } catch (e: Exception) {
            // Property not available
        }
        
        // Method 4: Check SharedPreferences for recent Xposed broadcast
        // StaticClipboardReceiver saves timestamp when it receives broadcasts
        try {
            val prefs = context.getSharedPreferences("xposed_status", Context.MODE_PRIVATE)
            val lastBroadcastTime = prefs.getLong("last_xposed_broadcast", 0)
            val timeSinceLastBroadcast = System.currentTimeMillis() - lastBroadcastTime
            // If we received a broadcast in the last 24 hours, Xposed is likely active
            if (lastBroadcastTime > 0 && timeSinceLastBroadcast < 24 * 60 * 60 * 1000) {
                Log.d(TAG, "Xposed framework active: received broadcast ${timeSinceLastBroadcast / 1000}s ago")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Xposed broadcast history", e)
        }
        
        Log.d(TAG, "Xposed framework not active: no evidence of running framework")
        return false
    }
    
    /**
     * Hooks the clipboard service to monitor clipboard changes.
     * 
     * Note: For LSPosed/EdXposed, the actual clipboard monitoring is done by:
     * 1. The Xposed module (ClipboardHookModule) hooks System Framework
     * 2. The module sends broadcasts when clipboard changes
     * 3. StaticClipboardReceiver (registered in AndroidManifest) receives broadcasts
     * 
     * This method just marks the hook as active - no dynamic receiver registration needed.
     * 
     * @param callback function to call when clipboard changes (not used for LSPosed)
     */
    fun hookClipboardService(callback: (ClipboardContent) -> Unit) {
        try {
            if (!isAvailable()) {
                throw ClipboardMonitorException(
                    ClipboardError.XposedFrameworkError(
                        IllegalStateException("Xposed/LSPosed framework not available")
                    )
                )
            }
            
            this.clipboardCallback = callback
            xposedFrameworkType = detectXposedFramework()
            
            // For LSPosed/EdXposed, we don't need to register a dynamic receiver
            // because StaticClipboardReceiver (in AndroidManifest) handles broadcasts
            // from the Xposed module. This prevents duplicate processing.
            
            Log.i(TAG, "Xposed clipboard hook activated using ${xposedFrameworkType.name}")
            Log.i(TAG, "Clipboard changes will be received via StaticClipboardReceiver")
            
            isHooked = true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook clipboard service", e)
            throw if (e is ClipboardMonitorException) e else ClipboardMonitorException(
                ClipboardError.XposedFrameworkError(e)
            )
        }
    }
    
    /**
     * Registers the broadcast receiver for receiving clipboard changes from Xposed module.
     */
    private fun registerClipboardHookReceiver() {
        if (isReceiverRegistered) {
            Log.d(TAG, "Clipboard hook receiver already registered")
            return
        }
        
        try {
            clipboardHookReceiver = ClipboardHookReceiver().apply {
                setClipboardListener { content ->
                    Log.i(TAG, "========================================")
                    Log.i(TAG, "Received clipboard content from Xposed hook!")
                    Log.i(TAG, "Type: ${content.type}")
                    Log.i(TAG, "Size: ${content.size} bytes")
                    Log.i(TAG, "Source: ${content.source}")
                    Log.i(TAG, "========================================")
                    clipboardCallback?.invoke(content)
                }
            }
            
            // Use the new register method that handles all Android versions
            val registered = ClipboardHookReceiver.register(context, clipboardHookReceiver!!)
            
            if (registered) {
                isReceiverRegistered = true
                Log.i(TAG, "Clipboard hook receiver registered successfully")
                Log.i(TAG, "Waiting for broadcasts from Xposed module...")
                Log.i(TAG, "Make sure the module is enabled in LSPosed Manager with 'System Framework' scope")
            } else {
                throw Exception("Failed to register clipboard hook receiver")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register clipboard hook receiver", e)
            throw e
        }
    }
    
    /**
     * Unregisters the broadcast receiver.
     */
    private fun unregisterClipboardHookReceiver() {
        if (!isReceiverRegistered || clipboardHookReceiver == null) {
            return
        }
        
        try {
            context.unregisterReceiver(clipboardHookReceiver)
            clipboardHookReceiver = null
            isReceiverRegistered = false
            Log.i(TAG, "Clipboard hook receiver unregistered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering clipboard hook receiver", e)
        }
    }
    
    /**
     * Unhooks the clipboard service and cleans up resources.
     */
    fun unhookClipboardService() {
        try {
            if (isHooked) {
                isHooked = false
                clipboardCallback = null
                Log.i(TAG, "Clipboard service unhooked successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unhooking clipboard service", e)
        }
    }
    
    /**
     * Detects which Xposed framework is available.
     */
    private fun detectXposedFramework(): XposedFrameworkType {
        return try {
            // Method 1: Check for LSPosed manager app first (may be hidden)
            if (isPackageInstalled("org.lsposed.manager")) {
                Log.d(TAG, "LSPosed manager app detected")
                return XposedFrameworkType.LSPOSED
            }
            
            // Method 2: Check for LSPosed module directories (with root)
            val lsposedPaths = arrayOf(
                "/data/adb/lspd",
                "/data/adb/modules/zygisk_lsposed",
                "/data/adb/modules/riru_lsposed"
            )
            for (path in lsposedPaths) {
                // First try without root
                if (java.io.File(path).exists()) {
                    Log.d(TAG, "LSPosed path detected (direct): $path")
                    return XposedFrameworkType.LSPOSED
                }
                // Try with root command using ls
                if (checkPathExistsWithRoot(path)) {
                    Log.d(TAG, "LSPosed path detected (via root): $path")
                    return XposedFrameworkType.LSPOSED
                }
            }
            
            // Method 3: Check LSPosed config database (via root)
            if (checkPathExistsWithRoot("/data/adb/lspd/config/modules_config.db")) {
                Log.d(TAG, "LSPosed config database detected")
                return XposedFrameworkType.LSPOSED
            }
            
            // Method 4: Check for EdXposed manager
            if (isPackageInstalled("org.meowcat.edxposed.manager") || 
                isPackageInstalled("com.solohsu.android.edxp.manager")) {
                Log.d(TAG, "EdXposed manager detected")
                return XposedFrameworkType.EDXPOSED
            }
            
            // Method 5: Check for traditional Xposed installer
            if (isPackageInstalled("de.robv.android.xposed.installer")) {
                Log.d(TAG, "Traditional Xposed installer detected")
                return XposedFrameworkType.XPOSED
            }
            
            // Method 6: Check for Xposed/LSPosed classes (only works if we're hooked)
            try {
                Class.forName("io.github.libxposed.api.XposedInterface")
                Log.d(TAG, "LSPosed API class detected")
                return XposedFrameworkType.LSPOSED
            } catch (e: ClassNotFoundException) {
                // Not loaded
            }
            
            try {
                Class.forName("de.robv.android.xposed.XposedBridge")
                Log.d(TAG, "XposedBridge class detected")
                return XposedFrameworkType.XPOSED
            } catch (e: ClassNotFoundException) {
                // Not loaded
            }
            
            // Method 7: Check system properties
            try {
                val xposedVersion = System.getProperty("xposed.version")
                if (xposedVersion != null) {
                    Log.d(TAG, "Xposed version property detected: $xposedVersion")
                    return XposedFrameworkType.XPOSED
                }
            } catch (e: Exception) {
                // Property not available
            }
            
            Log.d(TAG, "No Xposed framework detected")
            XposedFrameworkType.NONE
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting Xposed framework", e)
            XposedFrameworkType.NONE
        }
    }
    
    /**
     * Check if a path exists using root command
     */
    private fun checkPathExistsWithRoot(path: String): Boolean {
        return try {
            // Use ls command which is more reliable than test
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls $path"))
            val exitCode = process.waitFor()
            process.destroy()
            exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "Error checking path with root: $path", e)
            false
        }
    }
    
    /**
     * Checks if any Xposed manager app is installed.
     */
    private fun isAnyXposedManagerInstalled(): Boolean {
        return XPOSED_MANAGER_PACKAGES.any { packageName ->
            isPackageInstalled(packageName)
        }
    }
    
    /**
     * Checks if the Clipboard Whitelist module is installed.
     * This module allows background clipboard access on Android 10+.
     */
    fun isClipboardWhitelistInstalled(): Boolean {
        return isPackageInstalled(CLIPBOARD_WHITELIST_PACKAGE)
    }
    
    /**
     * Checks if our app is whitelisted by the Clipboard Whitelist module.
     * Note: This is a best-effort check as we can't directly query the module's database.
     */
    fun isAppWhitelisted(): Boolean {
        // If the module is installed and we can access clipboard in background,
        // we're likely whitelisted
        return isClipboardWhitelistInstalled() && canAccessClipboardInBackground()
    }
    
    /**
     * Tests if we can access clipboard content in background.
     */
    private fun canAccessClipboardInBackground(): Boolean {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            // Try to access clipboard - this may fail on Android 10+ without whitelist
            val clip = clipboardManager.primaryClip
            clip != null || clipboardManager.hasPrimaryClip()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot access clipboard in background", e)
            false
        }
    }
    
    /**
     * Helper method to check if a package is installed.
     */
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * Hooks clipboard service using traditional Xposed framework.
     */
    private fun hookWithXposed() {
        try {
            // Note: This is a simplified implementation. In a real Xposed module,
            // this would be done in the handleLoadPackage method of an IXposedHookLoadPackage
            
            val xposedBridge = Class.forName(XPOSED_BRIDGE_CLASS)
            val hookMethodMethod = xposedBridge.getMethod(
                "hookMethod",
                java.lang.reflect.Method::class.java,
                Class.forName("de.robv.android.xposed.XC_MethodHook")
            )
            
            // Hook ClipboardManager methods
            hookClipboardManagerMethods(hookMethodMethod)
            
        } catch (e: Exception) {
            throw ClipboardMonitorException(
                ClipboardError.XposedFrameworkError(e)
            )
        }
    }
    
    /**
     * Hooks clipboard service using LSPosed framework.
     */
    private fun hookWithLSPosed() {
        try {
            // LSPosed uses the same API as traditional Xposed but with better compatibility
            // The actual hooking is done by the Xposed module (ClipboardHookModule)
            // This method sets up the receiver to listen for broadcasts from the module
            
            Log.i(TAG, "Setting up LSPosed clipboard hook via broadcast receiver")
            // The broadcast receiver is already registered in hookClipboardService()
            // The Xposed module will send broadcasts when clipboard changes
            
        } catch (e: Exception) {
            throw ClipboardMonitorException(
                ClipboardError.XposedFrameworkError(e)
            )
        }
    }
    
    /**
     * Hooks clipboard service using EdXposed framework.
     */
    private fun hookWithEdXposed() {
        try {
            // EdXposed is similar to LSPosed, uses broadcast mechanism
            Log.i(TAG, "Setting up EdXposed clipboard hook via broadcast receiver")
            // The broadcast receiver is already registered in hookClipboardService()
            
        } catch (e: Exception) {
            throw ClipboardMonitorException(
                ClipboardError.XposedFrameworkError(e)
            )
        }
    }
    
    /**
     * Hooks ClipboardManager methods to intercept clipboard operations.
     */
    private fun hookClipboardManagerMethods(hookMethod: java.lang.reflect.Method) {
        try {
            val clipboardManagerClass = Class.forName(CLIPBOARD_SERVICE_CLASS)
            
            // Hook setPrimaryClip method
            val setPrimaryClipMethod = clipboardManagerClass.getMethod(
                "setPrimaryClip",
                Class.forName("android.content.ClipData")
            )
            
            // Create method hook (simplified - real implementation would be more complex)
            val methodHook = createMethodHook()
            hookMethod.invoke(null, setPrimaryClipMethod, methodHook)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error hooking ClipboardManager methods", e)
            throw e
        }
    }
    
    /**
     * Creates a method hook for clipboard operations.
     * Note: This is a simplified mock implementation.
     */
    private fun createMethodHook(): Any {
        // In a real Xposed module, this would return an XC_MethodHook instance
        // For this implementation, we'll return a mock object
        return object {
            fun beforeHookedMethod(param: Any) {
                // Called before the original method
                Log.d(TAG, "Clipboard operation intercepted (before)")
            }
            
            fun afterHookedMethod(param: Any) {
                // Called after the original method
                Log.d(TAG, "Clipboard operation intercepted (after)")
                
                // Extract clipboard content and notify callback
                try {
                    val clipData = extractClipDataFromParam(param)
                    clipData?.let { content ->
                        clipboardCallback?.invoke(content)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing hooked clipboard operation", e)
                }
            }
        }
    }
    
    /**
     * Extracts ClipboardContent from the hooked method parameters.
     * This is a mock implementation - real implementation would extract actual ClipData.
     */
    private fun extractClipDataFromParam(param: Any): ClipboardContent? {
        return try {
            // Mock implementation - in reality, this would extract data from ClipData
            ClipboardContent(
                type = ClipboardContent.ContentType.TEXT,
                data = "Mock clipboard content".toByteArray(),
                mimeType = "text/plain",
                timestamp = System.currentTimeMillis(),
                source = "xposed_hook",
                size = "Mock clipboard content".length.toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting clipboard content from hook", e)
            null
        }
    }
    
    /**
     * Unhooks from traditional Xposed framework.
     */
    private fun unhookFromXposed() {
        try {
            // The broadcast receiver cleanup is handled in unhookClipboardService()
            Log.i(TAG, "Unhooking from Xposed framework")
        } catch (e: Exception) {
            Log.e(TAG, "Error unhooking from Xposed", e)
        }
    }
    
    /**
     * Unhooks from LSPosed framework.
     */
    private fun unhookFromLSPosed() {
        try {
            // The broadcast receiver cleanup is handled in unhookClipboardService()
            Log.i(TAG, "Unhooking from LSPosed framework")
        } catch (e: Exception) {
            Log.e(TAG, "Error unhooking from LSPosed", e)
        }
    }
    
    /**
     * Unhooks from EdXposed framework.
     */
    private fun unhookFromEdXposed() {
        try {
            // The broadcast receiver cleanup is handled in unhookClipboardService()
            Log.i(TAG, "Unhooking from EdXposed framework")
        } catch (e: Exception) {
            Log.e(TAG, "Error unhooking from EdXposed", e)
        }
    }
    
    /**
     * Gets information about Xposed framework capabilities.
     */
    fun getXposedCapabilities(): Map<String, Any> {
        return try {
            val frameworkType = detectXposedFramework()
            mapOf(
                "available" to (frameworkType != XposedFrameworkType.NONE),
                "frameworkType" to frameworkType.name,
                "isHooked" to isHooked,
                "supportedMethods" to listOf("setPrimaryClip", "getPrimaryClip"),
                "clipboardWhitelistInstalled" to isClipboardWhitelistInstalled(),
                "appWhitelisted" to isAppWhitelisted(),
                "xposedManagerInstalled" to isAnyXposedManagerInstalled(),
                "runningInXposedEnvironment" to isRunningInXposedEnvironment()
            )
        } catch (e: Exception) {
            mapOf(
                "available" to false,
                "error" to (e.message ?: "Unknown error")
            )
        }
    }
    
    /**
     * Checks if the current process is running in an Xposed environment.
     * This verifies that our app is actually being hooked by Xposed/LSPosed.
     * 
     * Multiple detection methods are used:
     * 1. Check for Xposed bridge version system properties
     * 2. Check if XposedBridge class is loaded in our process
     * 3. Check for LSPosed API class
     * 4. Check for our custom hook indicator (set by ClipboardHookModule)
     */
    fun isRunningInXposedEnvironment(): Boolean {
        return try {
            // Method 1: Check system properties set by Xposed
            val xposedBridgeVersion = System.getProperty("xposed.bridge.version")
            if (xposedBridgeVersion != null) {
                Log.d(TAG, "Xposed bridge version detected: $xposedBridgeVersion")
                return true
            }
            
            val lsposedBridgeVersion = System.getProperty("lsposed.bridge.version")
            if (lsposedBridgeVersion != null) {
                Log.d(TAG, "LSPosed bridge version detected: $lsposedBridgeVersion")
                return true
            }
            
            // Method 2: Check if XposedBridge class is loaded in our process
            // This only works if our app is actually being hooked
            try {
                val xposedBridgeClass = Class.forName("de.robv.android.xposed.XposedBridge")
                // If we can load this class, we're in an Xposed environment
                Log.d(TAG, "XposedBridge class found in our process")
                return true
            } catch (e: ClassNotFoundException) {
                // Class not found - not hooked by traditional Xposed
            }
            
            // Method 3: Check for LSPosed API class
            try {
                val lsposedApiClass = Class.forName("io.github.libxposed.api.XposedInterface")
                Log.d(TAG, "LSPosed API class found in our process")
                return true
            } catch (e: ClassNotFoundException) {
                // Class not found - not hooked by LSPosed
            }
            
            // Method 4: Check for our custom hook indicator
            // Our ClipboardHookModule sets this when it hooks our app
            try {
                val hookIndicator = System.getProperty("clipboard.sync.hooked")
                if (hookIndicator == "true") {
                    Log.d(TAG, "Custom hook indicator found")
                    return true
                }
            } catch (e: Exception) {
                // Property not available
            }
            
            // Method 5: Check stack trace for Xposed-related classes
            // This is a fallback method - but we need to exclude our own classes
            try {
                val stackTrace = Thread.currentThread().stackTrace
                for (element in stackTrace) {
                    val className = element.className
                    // Skip our own classes that contain "xposed" in the name
                    if (className.startsWith("com.siw.clipboardsync")) {
                        continue
                    }
                    if (className.contains("xposed", ignoreCase = true) ||
                        className.contains("lsposed", ignoreCase = true) ||
                        className.contains("edxposed", ignoreCase = true)) {
                        Log.d(TAG, "Xposed-related class found in stack trace: $className")
                        return true
                    }
                }
            } catch (e: Exception) {
                // Stack trace check failed
            }
            
            Log.d(TAG, "No evidence of Xposed hook in our process")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Xposed environment", e)
            false
        }
    }
}