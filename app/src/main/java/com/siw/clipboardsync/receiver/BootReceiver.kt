package com.siw.clipboardsync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                // Check if user is logged in using SharedPreferences
                val prefs = context.getSharedPreferences("clipboard_sync_tokens", Context.MODE_PRIVATE)
                val hasToken = prefs.getString("access_token", null) != null
                
                if (hasToken) {
                    com.siw.clipboardsync.service.ClipboardMonitorService.startService(context)
                }
            }
        }
    }
}