package com.siw.clipboardsync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.siw.clipboardsync.service.ClipboardMonitorService
import com.siw.clipboardsync.service.KeepAliveManager

/**
 * Boot receiver for auto-starting the clipboard sync service.
 * Handles multiple boot and restart scenarios to ensure reliable startup.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received boot broadcast: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON", // HTC快速启动
            "com.htc.intent.action.QUICKBOOT_POWERON", // HTC
            "android.intent.action.REBOOT", // 重启
            "miui.intent.action.BOOT_COMPLETED" -> { // MIUI
                handleBoot(context)
            }
        }
    }
    
    private fun handleBoot(context: Context) {
        try {
            // Check if user is logged in using SharedPreferences
            val prefs = context.getSharedPreferences("clipboard_sync_tokens", Context.MODE_PRIVATE)
            val hasToken = prefs.getString("access_token", null) != null
            
            if (hasToken) {
                Log.i(TAG, "User is logged in, starting services")
                
                // Start the foreground service
                ClipboardMonitorService.startService(context)
                
                // Initialize keep-alive mechanisms
                val keepAliveManager = KeepAliveManager.getInstance(context)
                keepAliveManager.startKeepAlive()
                
                Log.i(TAG, "Services started successfully after boot")
            } else {
                Log.d(TAG, "User not logged in, skipping service start")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling boot", e)
        }
    }
}