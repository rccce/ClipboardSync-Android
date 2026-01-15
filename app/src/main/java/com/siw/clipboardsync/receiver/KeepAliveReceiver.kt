package com.siw.clipboardsync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.siw.clipboardsync.service.ClipboardAccessibilityService
import com.siw.clipboardsync.service.ClipboardMonitorService
import com.siw.clipboardsync.service.KeepAliveActivity
import com.siw.clipboardsync.service.KeepAliveManager

/**
 * Broadcast receiver for keep-alive alarms and various system events.
 * Handles multiple triggers to ensure the app stays alive.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "KeepAliveReceiver"
        
        const val ACTION_KEEP_ALIVE = "com.siw.clipboardsync.ACTION_KEEP_ALIVE"
        const val ACTION_RESTART_SERVICE = "com.siw.clipboardsync.ACTION_RESTART_SERVICE"
        
        // Disable 1-pixel activity - it can cause system issues on some ROMs
        private const val USE_1PX_ACTIVITY = false
        
        // Throttle to prevent rapid-fire broadcasts
        private var lastHandleTime = 0L
        private const val THROTTLE_MS = 5000L // 5 seconds minimum between handles
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")
        
        when (intent.action) {
            ACTION_KEEP_ALIVE,
            ACTION_RESTART_SERVICE,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_POWER_CONNECTED,
            "android.net.conn.CONNECTIVITY_CHANGE",
            "android.net.wifi.STATE_CHANGE" -> {
                handleKeepAlive(context)
            }
            
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Screen ON - stopping 1px activity, checking services")
                // Stop 1-pixel activity
                if (USE_1PX_ACTIVITY) {
                    KeepAliveActivity.stop()
                }
                handleKeepAlive(context)
            }
            
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen OFF - starting 1px activity for keep-alive")
                handleKeepAlive(context)
                
                // Start 1-pixel activity for aggressive keep-alive
                if (USE_1PX_ACTIVITY && isUserLoggedIn(context)) {
                    KeepAliveActivity.start(context)
                }
            }
        }
    }
    
    private fun handleKeepAlive(context: Context) {
        // Throttle to prevent rapid-fire handling
        val now = System.currentTimeMillis()
        if (now - lastHandleTime < THROTTLE_MS) {
            Log.d(TAG, "Throttled - skipping keep-alive check")
            return
        }
        lastHandleTime = now
        
        try {
            if (!isUserLoggedIn(context)) {
                Log.d(TAG, "User not logged in, skipping keep-alive")
                return
            }
            
            // Ensure foreground service is running
            val keepAliveManager = KeepAliveManager.getInstance(context)
            keepAliveManager.ensureForegroundService()
            
            Log.d(TAG, "Keep-alive check completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error in keep-alive handler", e)
        }
    }
    
    private fun isUserLoggedIn(context: Context): Boolean {
        val prefs = context.getSharedPreferences("clipboard_sync_tokens", Context.MODE_PRIVATE)
        return prefs.getString("access_token", null) != null
    }
}
