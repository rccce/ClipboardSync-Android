package com.siw.clipboardsync.service

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Window
import android.view.WindowManager

/**
 * 1-pixel transparent activity for keep-alive on some OEM ROMs.
 * 
 * This activity is used as a last resort keep-alive mechanism:
 * - Creates a 1x1 pixel transparent window
 * - Keeps the app process at higher priority
 * - Auto-finishes when screen turns on
 * 
 * This technique works on some aggressive OEM ROMs (MIUI, EMUI, etc.)
 * where other keep-alive methods fail.
 * 
 * Usage:
 * - Start when screen turns off
 * - Finish when screen turns on
 */
class KeepAliveActivity : Activity() {
    
    companion object {
        private const val TAG = "KeepAliveActivity"
        
        @Volatile
        private var instance: KeepAliveActivity? = null
        
        fun isRunning(): Boolean = instance != null
        
        /**
         * Start the keep-alive activity
         */
        fun start(context: Context) {
            if (instance != null) {
                Log.d(TAG, "KeepAliveActivity already running")
                return
            }
            
            try {
                val intent = Intent(context, KeepAliveActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                }
                context.startActivity(intent)
                Log.d(TAG, "KeepAliveActivity started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start KeepAliveActivity", e)
            }
        }
        
        /**
         * Stop the keep-alive activity
         */
        fun stop() {
            instance?.finish()
        }
    }
    
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen ON - finishing KeepAliveActivity")
                    finish()
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        
        Log.d(TAG, "KeepAliveActivity onCreate")
        
        // Configure window for 1-pixel transparent activity
        configureWindow()
        
        // Register screen on receiver to auto-finish
        registerScreenReceiver()
        
        // Ensure main service is running
        ensureServiceRunning()
    }
    
    override fun onDestroy() {
        Log.d(TAG, "KeepAliveActivity onDestroy")
        unregisterScreenReceiver()
        instance = null
        super.onDestroy()
    }
    
    /**
     * Configure window to be 1x1 pixel and transparent
     */
    private fun configureWindow() {
        try {
            // Remove title bar
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            
            window.apply {
                // Set window to be 1x1 pixel
                setGravity(Gravity.START or Gravity.TOP)
                
                val params = attributes
                params.x = 0
                params.y = 0
                params.width = 1
                params.height = 1
                params.alpha = 0f // Fully transparent
                attributes = params
                
                // Additional flags
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
            }
            
            Log.d(TAG, "Window configured as 1x1 transparent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure window", e)
        }
    }
    
    /**
     * Register screen on receiver
     */
    private fun registerScreenReceiver() {
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen receiver", e)
        }
    }
    
    /**
     * Unregister screen receiver
     */
    private fun unregisterScreenReceiver() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering screen receiver", e)
        }
    }
    
    /**
     * Ensure main service is running
     */
    private fun ensureServiceRunning() {
        try {
            ClipboardMonitorService.startService(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure service running", e)
        }
    }
    
    override fun onBackPressed() {
        // Prevent back press from closing the activity
        // It will be closed when screen turns on
    }
}
