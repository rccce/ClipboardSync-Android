package com.siw.clipboardsync.monitor.xposed

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserHandle
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
 * 
 * IMPORTANT: This module must be enabled in LSPosed Manager with scope set to "System Framework" (android)
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
        
        // Store context for broadcasting
        @Volatile
        private var systemContext: Context? = null
    }
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only hook the system server
        if (lpparam.packageName != TARGET_PACKAGE) {
            return
        }
        
        XposedBridge.log("$TAG: ========================================")
        XposedBridge.log("$TAG: ClipboardHookEntry loaded in system server!")
        XposedBridge.log("$TAG: Package: ${lpparam.packageName}")
        XposedBridge.log("$TAG: Process: ${lpparam.processName}")
        XposedBridge.log("$TAG: ========================================")
        
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
                
                // Hook constructor to get context
                hookConstructor(clipboardServiceClass)
                
                // Hook setPrimaryClip methods
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
    
    private fun hookConstructor(clipboardServiceClass: Class<*>) {
        try {
            // Hook all constructors to capture context
            val constructors = clipboardServiceClass.declaredConstructors
            for (constructor in constructors) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            // Try to get context from constructor args
                            for (arg in param.args) {
                                if (arg is Context) {
                                    systemContext = arg
                                    XposedBridge.log("$TAG: Got system context from constructor arg")
                                    return
                                }
                            }
                            
                            // Try to get context from field
                            val context = getContextFromService(param.thisObject)
                            if (context != null) {
                                systemContext = context
                                XposedBridge.log("$TAG: Got system context from service field")
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: Error getting context from constructor: ${e.message}")
                        }
                    }
                })
            }
            XposedBridge.log("$TAG: Hooked ${constructors.size} constructors")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error hooking constructors: ${e.message}")
        }
    }
    
    private fun hookSetPrimaryClip(clipboardServiceClass: Class<*>) {
        // Hook all methods that might be called when clipboard changes
        // Different Android versions and OEMs use different method names
        val methodNames = arrayOf(
            "setPrimaryClip",
            "setPrimaryClipInternal", 
            "setPrimaryClipAsPackage",
            "checkAndSetPrimaryClip"
        )
        
        var hookedCount = 0
        
        for (methodName in methodNames) {
            val methods = clipboardServiceClass.declaredMethods.filter { it.name == methodName }
            
            for (method in methods) {
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                XposedBridge.log("$TAG: >>> Method ${method.name} called (before)!")
                                handleClipboardChange(param)
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG: Error in beforeHookedMethod for ${method.name}: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                    })
                    hookedCount++
                    XposedBridge.log("$TAG: Hooked method: ${method.name} with ${method.parameterTypes.size} params")
                    XposedBridge.log("$TAG:   Param types: ${method.parameterTypes.map { it.simpleName }.joinToString(", ")}")
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: Failed to hook method ${method.name}: ${e.message}")
                }
            }
        }
        
        // Also try to hook the inner class ClipboardImpl
        try {
            val innerClasses = clipboardServiceClass.declaredClasses
            for (innerClass in innerClasses) {
                if (innerClass.simpleName.contains("ClipboardImpl") || innerClass.simpleName.contains("Impl")) {
                    XposedBridge.log("$TAG: Found inner class: ${innerClass.name}")
                    
                    for (methodName in methodNames) {
                        val methods = innerClass.declaredMethods.filter { it.name == methodName }
                        for (method in methods) {
                            try {
                                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        try {
                                            XposedBridge.log("$TAG: >>> Inner class method ${method.name} called (before)!")
                                            handleClipboardChange(param)
                                        } catch (e: Throwable) {
                                            XposedBridge.log("$TAG: Error in inner class hook: ${e.message}")
                                        }
                                    }
                                })
                                hookedCount++
                                XposedBridge.log("$TAG: Hooked inner class method: ${innerClass.simpleName}.${method.name}")
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG: Failed to hook inner class method: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error hooking inner classes: ${e.message}")
        }
        
        XposedBridge.log("$TAG: Total methods hooked: $hookedCount")
    }
    
    private fun handleClipboardChange(param: XC_MethodHook.MethodHookParam) {
        // Log all arguments for debugging
        XposedBridge.log("$TAG: handleClipboardChange - args count: ${param.args?.size ?: 0}")
        param.args?.forEachIndexed { index, arg ->
            XposedBridge.log("$TAG:   arg[$index]: ${arg?.javaClass?.name ?: "null"} = ${describeArg(arg)}")
        }
        
        // Find ClipData in arguments - try multiple approaches
        var clipData: ClipData? = null
        var callingPackage: String? = null
        
        for (arg in param.args ?: emptyArray()) {
            when {
                arg is ClipData -> {
                    clipData = arg
                    XposedBridge.log("$TAG: Found ClipData directly in args")
                }
                arg is String -> if (callingPackage == null) callingPackage = arg
                arg != null -> {
                    // Try to extract ClipData from wrapper objects
                    val extracted = tryExtractClipData(arg)
                    if (extracted != null) {
                        clipData = extracted
                        XposedBridge.log("$TAG: Extracted ClipData from ${arg.javaClass.name}")
                    }
                }
            }
        }
        
        // If still no ClipData, try to get from the service's mClipboard field
        if (clipData == null) {
            clipData = tryGetClipDataFromService(param.thisObject)
            if (clipData != null) {
                XposedBridge.log("$TAG: Got ClipData from service field")
            }
        }
        
        if (clipData == null || clipData.itemCount == 0) {
            XposedBridge.log("$TAG: No clip data or empty clip after all attempts")
            return
        }
        
        // Get context for coerceToText
        val context = systemContext ?: getContextFromService(param.thisObject)
        
        val item = clipData.getItemAt(0)
        val text = item.text?.toString() ?: context?.let { item.coerceToText(it)?.toString() }
        
        if (text.isNullOrEmpty()) {
            XposedBridge.log("$TAG: Empty text content")
            return
        }
        
        val mimeType = clipData.description?.getMimeType(0) ?: "text/plain"
        val timestamp = System.currentTimeMillis()
        val sourcePackage = callingPackage ?: "unknown"
        
        XposedBridge.log("$TAG: *** Clipboard changed by $sourcePackage ***")
        XposedBridge.log("$TAG: Content preview: ${text.take(100)}...")
        XposedBridge.log("$TAG: MimeType: $mimeType")
        
        broadcastClipboardChange(param.thisObject, text, mimeType, timestamp, sourcePackage)
    }
    
    private fun describeArg(arg: Any?): String {
        return when {
            arg == null -> "null"
            arg is String -> "\"${arg.take(50)}${if (arg.length > 50) "..." else ""}\""
            arg is ClipData -> "ClipData(items=${arg.itemCount})"
            arg is Number -> arg.toString()
            arg is Boolean -> arg.toString()
            else -> {
                // Try to get some useful info about the object
                try {
                    val fields = arg.javaClass.declaredFields.take(3).map { it.name }
                    "${arg.javaClass.simpleName}(fields: ${fields.joinToString(", ")})"
                } catch (e: Throwable) {
                    arg.javaClass.simpleName
                }
            }
        }
    }
    
    private fun tryExtractClipData(obj: Any): ClipData? {
        try {
            // Try common field names that might contain ClipData
            val fieldNames = arrayOf("clip", "mClip", "clipData", "mClipData", "primaryClip", "mPrimaryClip")
            
            for (fieldName in fieldNames) {
                try {
                    val field = obj.javaClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val value = field.get(obj)
                    if (value is ClipData) {
                        return value
                    }
                } catch (e: NoSuchFieldException) {
                    // Try next field
                }
            }
            
            // Try getter methods
            val methodNames = arrayOf("getClip", "getClipData", "getPrimaryClip")
            for (methodName in methodNames) {
                try {
                    val method = obj.javaClass.getDeclaredMethod(methodName)
                    method.isAccessible = true
                    val value = method.invoke(obj)
                    if (value is ClipData) {
                        return value
                    }
                } catch (e: NoSuchMethodException) {
                    // Try next method
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error extracting ClipData: ${e.message}")
        }
        return null
    }
    
    private fun tryGetClipDataFromService(service: Any): ClipData? {
        try {
            // For inner class, get the outer class instance first
            val outerThis = try {
                val outerField = service.javaClass.getDeclaredField("this\$0")
                outerField.isAccessible = true
                outerField.get(service)
            } catch (e: NoSuchFieldException) {
                service // Not an inner class, use service directly
            }
            
            // Try to get clipboard data from various fields
            val fieldNames = arrayOf(
                "mPrimaryClip", "mClipboard", "mClip", 
                "primaryClip", "clipboard", "clip"
            )
            
            for (fieldName in fieldNames) {
                try {
                    var clazz: Class<*>? = outerThis.javaClass
                    while (clazz != null) {
                        try {
                            val field = clazz.getDeclaredField(fieldName)
                            field.isAccessible = true
                            val value = field.get(outerThis)
                            if (value is ClipData) {
                                return value
                            }
                            // If it's a map or sparse array, try to get current user's clip
                            if (value != null) {
                                val extracted = tryExtractClipData(value)
                                if (extracted != null) return extracted
                            }
                        } catch (e: NoSuchFieldException) {
                            // Try parent class
                        }
                        clazz = clazz.superclass
                    }
                } catch (e: Throwable) {
                    // Continue to next field
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error getting ClipData from service: ${e.message}")
        }
        return null
    }
    
    private fun getContextFromService(service: Any): Context? {
        return try {
            // Try common field names
            val fieldNames = arrayOf("mContext", "context", "mSystemContext")
            for (fieldName in fieldNames) {
                try {
                    val field = service.javaClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val ctx = field.get(service) as? Context
                    if (ctx != null) return ctx
                } catch (e: NoSuchFieldException) {
                    // Try next field
                }
            }
            
            // Try to get from superclass
            var clazz: Class<*>? = service.javaClass.superclass
            while (clazz != null) {
                for (fieldName in fieldNames) {
                    try {
                        val field = clazz.getDeclaredField(fieldName)
                        field.isAccessible = true
                        val ctx = field.get(service) as? Context
                        if (ctx != null) return ctx
                    } catch (e: NoSuchFieldException) {
                        // Try next
                    }
                }
                clazz = clazz.superclass
            }
            null
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error getting context: ${e.message}")
            null
        }
    }
    
    private fun broadcastClipboardChange(
        service: Any,
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
                // Add explicit component for Android 14+
                setClassName(APP_PACKAGE, "com.siw.clipboardsync.monitor.xposed.StaticClipboardReceiver")
            }
            
            // Method 1: Use context.sendBroadcast directly (preferred)
            val context = systemContext ?: getContextFromService(service)
            if (context != null) {
                // Try sending to current user first (user 0)
                try {
                    val userCurrent = XposedHelpers.getStaticObjectField(UserHandle::class.java, "CURRENT") as UserHandle
                    context.sendBroadcastAsUser(intent, userCurrent)
                    XposedBridge.log("$TAG: Broadcast sent via context.sendBroadcastAsUser(CURRENT)")
                    return
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: sendBroadcastAsUser(CURRENT) failed: ${e.message}")
                }
                
                // Try with user 0 explicitly
                try {
                    val user0 = android.os.UserHandle.getUserHandleForUid(0)
                    context.sendBroadcastAsUser(intent, user0)
                    XposedBridge.log("$TAG: Broadcast sent via context.sendBroadcastAsUser(user0)")
                    return
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: sendBroadcastAsUser(user0) failed: ${e.message}")
                }
                
                try {
                    context.sendBroadcast(intent)
                    XposedBridge.log("$TAG: Broadcast sent via context.sendBroadcast")
                    return
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: sendBroadcast failed: ${e.message}")
                }
            }
            
            // Method 2: Use ActivityManager.getService() (Android 8+)
            try {
                val amClass = XposedHelpers.findClass("android.app.ActivityManager", null)
                val iamInterface = XposedHelpers.callStaticMethod(amClass, "getService")
                
                if (iamInterface != null) {
                    // Try broadcastIntentWithFeature (Android 11+)
                    try {
                        XposedHelpers.callMethod(
                            iamInterface,
                            "broadcastIntentWithFeature",
                            null, // caller
                            null, // callingFeatureId
                            intent,
                            null, // resolvedType
                            null, // resultTo
                            0, // resultCode
                            null, // resultData
                            null, // resultExtras
                            null, // requiredPermissions
                            null, // excludedPermissions
                            null, // excludedPackages
                            -1, // appOp
                            null, // bOptions
                            false, // serialized
                            false, // sticky
                            0 // userId (0 = current user)
                        )
                        XposedBridge.log("$TAG: Broadcast sent via broadcastIntentWithFeature")
                        return
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: broadcastIntentWithFeature failed: ${e.message}")
                    }
                    
                    // Try broadcastIntent (older signature)
                    try {
                        XposedHelpers.callMethod(
                            iamInterface,
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
                        XposedBridge.log("$TAG: Broadcast sent via broadcastIntent")
                        return
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: broadcastIntent failed: ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: ActivityManager.getService() failed: ${e.message}")
            }
            
            // Method 3: Use ActivityManagerNative (legacy, Android < 8)
            try {
                val amnClass = XposedHelpers.findClass("android.app.ActivityManagerNative", null)
                val am = XposedHelpers.callStaticMethod(amnClass, "getDefault")
                
                if (am != null) {
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
                    XposedBridge.log("$TAG: Broadcast sent via ActivityManagerNative")
                    return
                }
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: ActivityManagerNative failed: ${e.message}")
            }
            
            XposedBridge.log("$TAG: All broadcast methods failed!")
            
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error broadcasting clipboard change: ${e.message}")
            e.printStackTrace()
        }
    }
}
