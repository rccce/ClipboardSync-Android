package com.siw.clipboardsync.monitor.xposed

import android.content.ClipData
import android.content.Intent
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed module entry point for clipboard hooking.
 * This class is loaded by LSPosed/Xposed framework when the system starts.
 * 
 * It hooks ClipboardService.setPrimaryClip() to intercept clipboard changes
 * and broadcasts them to our app.
 */
class ClipboardHookEntry : IXposedHookLoadPackage {
    
    companion object {
        private const val TAG = "ClipboardHook"
        private const val TARGET_PACKAGE = "android"
        private const val APP_PACKAGE = "com.siw.clipboardsync"
        private const val ACTION_CLIPBOARD_CHANGED = "com.siw.clipboardsync.CLIPBOARD_CHANGED"
        
        // ClipboardService class names for different Android versions
        private val CLIPBOARD_SERVICE_CLASSES = arrayOf(
            "com.android.server.clipboard.ClipboardService",
            "com.android.server.ClipboardService"
        )
    }
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only hook the system server
        if (lpparam.packageName != TARGET_PACKAGE) {
            return
        }
        
        XposedBridge.log("$TAG: Hooking clipboard service in system server")
        
        try {
            hookClipboardService(lpparam.classLoader)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook clipboard service: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun hookClipboardService(classLoader: ClassLoader) {
        var hooked = false
        
        for (className in CLIPBOARD_SERVICE_CLASSES) {
            try {
                val clipboardServiceClass = XposedHelpers.findClass(className, classLoader)
                hookSetPrimaryClip(clipboardServiceClass)
                hooked = true
                XposedBridge.log("$TAG: Successfully hooked $className")
                break
            } catch (e: ClassNotFoundException) {
                XposedBridge.log("$TAG: Class not found: $className")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: Error hooking $className: ${e.message}")
            }
        }
        
        if (!hooked) {
            XposedBridge.log("$TAG: Failed to hook any clipboard service class")
        }
    }
    
    private fun hookSetPrimaryClip(clipboardServiceClass: Class<*>) {
        // Hook setPrimaryClip method - signature varies by Android version
        // Android 10+: setPrimaryClip(ClipData, String, String, int, int)
        // Older: setPrimaryClip(ClipData, String, int)
        
        val methods = clipboardServiceClass.declaredMethods.filter { 
            it.name == "setPrimaryClip" || it.name == "setPrimaryClipInternal"
        }
        
        for (method in methods) {
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            handleClipboardChange(param)
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: Error in afterHookedMethod: ${e.message}")
                        }
                    }
                })
                XposedBridge.log("$TAG: Hooked method: ${method.name}")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: Failed to hook method ${method.name}: ${e.message}")
            }
        }
    }
    
    private fun handleClipboardChange(param: XC_MethodHook.MethodHookParam) {
        // Find ClipData in arguments
        var clipData: ClipData? = null
        var callingPackage: String? = null
        
        for (arg in param.args) {
            when (arg) {
                is ClipData -> clipData = arg
                is String -> if (callingPackage == null) callingPackage = arg
            }
        }
        
        if (clipData == null || clipData.itemCount == 0) {
            return
        }
        
        val item = clipData.getItemAt(0)
        val text = item.text?.toString() ?: item.coerceToText(getContext(param))?.toString()
        
        if (text.isNullOrEmpty()) {
            return
        }
        
        val mimeType = clipData.description?.getMimeType(0) ?: "text/plain"
        val timestamp = System.currentTimeMillis()
        val sourcePackage = callingPackage ?: "unknown"
        
        XposedBridge.log("$TAG: Clipboard changed by $sourcePackage: ${text.take(50)}...")
        
        broadcastClipboardChange(text, mimeType, timestamp, sourcePackage)
    }
    
    private fun getContext(param: XC_MethodHook.MethodHookParam): android.content.Context? {
        return try {
            val contextField = param.thisObject.javaClass.getDeclaredField("mContext")
            contextField.isAccessible = true
            contextField.get(param.thisObject) as? android.content.Context
        } catch (e: Throwable) {
            null
        }
    }
    
    private fun broadcastClipboardChange(
        content: String,
        mimeType: String,
        timestamp: Long,
        sourcePackage: String
    ) {
        try {
            val intent = Intent(ACTION_CLIPBOARD_CHANGED).apply {
                putExtra("clipboard_content", content)
                putExtra("mime_type", mimeType)
                putExtra("timestamp", timestamp)
                putExtra("source_package", sourcePackage)
                setPackage(APP_PACKAGE)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            
            // Get ActivityManagerService to send broadcast
            val activityManagerClass = XposedHelpers.findClass(
                "android.app.ActivityManagerNative",
                null
            )
            val am = XposedHelpers.callStaticMethod(activityManagerClass, "getDefault")
            
            // broadcastIntent with appropriate parameters
            try {
                // Try Android 10+ signature
                XposedHelpers.callMethod(
                    am,
                    "broadcastIntent",
                    null, // caller
                    intent,
                    null, // resolvedType
                    null, // resultTo
                    0, // resultCode
                    null, // resultData
                    null, // resultExtras
                    null, // requiredPermissions
                    -1, // appOp
                    null, // bOptions
                    false, // serialized
                    false, // sticky
                    0 // userId
                )
            } catch (e: Throwable) {
                // Try older signature
                try {
                    XposedHelpers.callMethod(
                        am,
                        "broadcastIntent",
                        null,
                        intent,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        false,
                        false,
                        0
                    )
                } catch (e2: Throwable) {
                    XposedBridge.log("$TAG: Failed to broadcast: ${e2.message}")
                }
            }
            
            XposedBridge.log("$TAG: Broadcast sent to $APP_PACKAGE")
            
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error broadcasting clipboard change: ${e.message}")
        }
    }
}
