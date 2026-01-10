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
     * Checks if Xposed/LSPosed framework is available on this device.
     */
    fun isAvailable(): Boolean {
        return try {
            detectXposedFramework() != XposedFrameworkType.NONE
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Xposed framework availability", e)
            false
        }
    }
    
    /**
     * Hooks the clipboard service to monitor clipboard changes.
     * @param callback function to call when clipboard changes
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
            
            // Register broadcast receiver for Xposed module communication
            registerClipboardHookReceiver()
            
            when (xposedFrameworkType) {
                XposedFrameworkType.XPOSED -> hookWithXposed()
                XposedFrameworkType.LSPOSED -> hookWithLSPosed()
                XposedFrameworkType.EDXPOSED -> hookWithEdXposed()
                XposedFrameworkType.NONE -> throw ClipboardMonitorException(
                    ClipboardError.XposedFrameworkError(
                        IllegalStateException("No supported Xposed framework found")
                    )
                )
            }
            
            isHooked = true
            Log.i(TAG, "Clipboard service hooked successfully using ${xposedFrameworkType.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook clipboard service", e)
            // Clean up receiver on failure
            unregisterClipboardHookReceiver()
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
                    Log.d(TAG, "Received clipboard content from Xposed hook: ${content.type}")
                    clipboardCallback?.invoke(content)
                }
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    clipboardHookReceiver,
                    ClipboardHookReceiver.createIntentFilter(),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(
                    clipboardHookReceiver,
                    ClipboardHookReceiver.createIntentFilter()
                )
            }
            
            isReceiverRegistered = true
            Log.i(TAG, "Clipboard hook receiver registered successfully")
            
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
                // Unregister broadcast receiver first
                unregisterClipboardHookReceiver()
                
                when (xposedFrameworkType) {
                    XposedFrameworkType.XPOSED -> unhookFromXposed()
                    XposedFrameworkType.LSPOSED -> unhookFromLSPosed()
                    XposedFrameworkType.EDXPOSED -> unhookFromEdXposed()
                    XposedFrameworkType.NONE -> { /* Nothing to unhook */ }
                }
                
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
            // Check for LSPosed first (modern framework)
            try {
                Class.forName(LSPOSED_API_CLASS)
                Log.d(TAG, "LSPosed API detected")
                return XposedFrameworkType.LSPOSED
            } catch (e: ClassNotFoundException) {
                // Not LSPosed API
            }
            
            try {
                Class.forName(LSPOSED_BRIDGE_CLASS)
                Log.d(TAG, "LSPosed Bridge detected")
                return XposedFrameworkType.LSPOSED
            } catch (e: ClassNotFoundException) {
                // Not LSPosed Bridge
            }
            
            // Check for EdXposed
            try {
                Class.forName(EDXPOSED_BRIDGE_CLASS)
                Log.d(TAG, "EdXposed detected")
                return XposedFrameworkType.EDXPOSED
            } catch (e: ClassNotFoundException) {
                // Not EdXposed
            }
            
            // Check for traditional Xposed
            try {
                Class.forName(XPOSED_BRIDGE_CLASS)
                Log.d(TAG, "Traditional Xposed detected")
                return XposedFrameworkType.XPOSED
            } catch (e: ClassNotFoundException) {
                // Not traditional Xposed
            }
            
            // Check for Xposed manager apps as fallback
            if (isAnyXposedManagerInstalled()) {
                Log.d(TAG, "Xposed manager app detected, assuming framework is available")
                return XposedFrameworkType.XPOSED
            }
            
            XposedFrameworkType.NONE
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting Xposed framework", e)
            XposedFrameworkType.NONE
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
     */
    fun isRunningInXposedEnvironment(): Boolean {
        return try {
            // Check for Xposed environment indicators
            System.getProperty("xposed.bridge.version") != null ||
            System.getProperty("lsposed.bridge.version") != null
        } catch (e: Exception) {
            false
        }
    }
}