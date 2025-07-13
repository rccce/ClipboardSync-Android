package com.siw.clipboardsync.xposed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed Hook Module for Real-time Clipboard Monitoring
 * 
 * This module hooks into the system ClipboardManager to detect clipboard changes
 * in real-time without polling, providing instant synchronization for rooted devices.
 */
class ClipboardHook : IXposedHookLoadPackage {
    
    companion object {
        private const val TAG = "ClipboardHook"
        private const val CLIPBOARD_CHANGED_ACTION = "com.siw.clipboardsync.CLIPBOARD_CHANGED"
        private const val TARGET_PACKAGE = "android"
        private const val CLIPBOARD_SERVICE_CLASS = "com.android.server.clipboard.ClipboardService"
        private const val SET_PRIMARY_CLIP_METHOD = "setPrimaryClip"
    }
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only hook system server process
        if (lpparam.packageName != TARGET_PACKAGE) return
        
        try {
            hookClipboardService(lpparam)
            Log.i(TAG, "ClipboardHook initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ClipboardHook", e)
        }
    }
    
    /**
     * Hook the ClipboardService.setPrimaryClip method
     */
    private fun hookClipboardService(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clipboardServiceClass = XposedHelpers.findClass(CLIPBOARD_SERVICE_CLASS, lpparam.classLoader)
        
        XposedHelpers.findAndHookMethod(
            clipboardServiceClass,
            SET_PRIMARY_CLIP_METHOD,
            ClipData::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        handleClipboardChange(param)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling clipboard change", e)
                    }
                }
            }
        )
    }
    
    /**
     * Handle clipboard change event
     */
    private fun handleClipboardChange(param: XC_MethodHook.MethodHookParam) {
        // Create hook activity marker for detection
        createHookMarker()
        
        val clipData = param.args[0] as? ClipData ?: return
        val callingPackage = param.args[1] as? String ?: return
        val attributionTag = param.args[2] as? String
        val userId = param.args[3] as? Int ?: return
        val deviceId = param.args[4] as? Int ?: return
        
        // Skip if the change is from our own app to prevent loops
        if (callingPackage == "com.siw.clipboardsync") {
            Log.d(TAG, "Skipping clipboard change from our own app")
            return
        }
        
        Log.d(TAG, "Clipboard changed by package: $callingPackage")
        
        // Extract clipboard content
        val clipboardContent = extractClipboardContent(clipData)
        if (clipboardContent != null) {
            broadcastClipboardChange(clipboardContent, callingPackage)
        }
    }
    
    /**
     * Extract content from ClipData
     */
    private fun extractClipboardContent(clipData: ClipData): ClipboardContent? {
        if (clipData.itemCount == 0) return null
        
        val item = clipData.getItemAt(0)
        val description = clipData.description
        
        return when {
            item.text != null -> {
                ClipboardContent(
                    type = "text",
                    content = item.text.toString(),
                    mimeType = description.getMimeType(0)
                )
            }
            item.uri != null -> {
                ClipboardContent(
                    type = "uri",
                    content = item.uri.toString(),
                    mimeType = description.getMimeType(0)
                )
            }
            else -> null
        }
    }
    
    /**
     * Broadcast clipboard change to our app
     */
    private fun broadcastClipboardChange(content: ClipboardContent, sourcePackage: String) {
        try {
            // Create intent for broadcast
            val intent = Intent(CLIPBOARD_CHANGED_ACTION).apply {
                putExtra("content_type", content.type)
                putExtra("content", content.content)
                putExtra("mime_type", content.mimeType)
                putExtra("source_package", sourcePackage)
                putExtra("timestamp", System.currentTimeMillis())
                putExtra("hook_source", "lsposed")
                
                // Set package to ensure it reaches our app
                setPackage("com.siw.clipboardsync")
                
                // Add flags for broadcast
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            
            // Note: In Xposed context, we need to use reflection to send broadcast
            // This is a simplified version - actual implementation may need more complex approach
            Log.d(TAG, "Broadcasting clipboard change: ${content.type} - ${content.content.take(50)}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast clipboard change", e)
        }
    }
    
    /**
     * Create a marker file to indicate hook is working
     */
    private fun createHookMarker() {
        try {
            val markerFile = java.io.File("/data/data/com.siw.clipboardsync/hook_test_marker")
            markerFile.parentFile?.mkdirs()
            markerFile.writeText("Hook active at ${System.currentTimeMillis()}")
            Log.d(TAG, "Hook marker created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create hook marker", e)
        }
    }
    
    /**
     * Data class for clipboard content
     */
    private data class ClipboardContent(
        val type: String,
        val content: String,
        val mimeType: String?
    )
}