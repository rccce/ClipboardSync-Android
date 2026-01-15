package com.siw.clipboardsync

import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.siw.clipboardsync.receiver.KeepAliveReceiver
import com.siw.clipboardsync.service.KeepAliveManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ClipboardSyncApplication : Application() {
    
    companion object {
        private const val TAG = "ClipboardSyncApp"
        
        @Volatile
        private var instance: ClipboardSyncApplication? = null
        
        fun getInstance(): ClipboardSyncApplication? = instance
    }
    
    private var keepAliveReceiver: KeepAliveReceiver? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        Log.i(TAG, "Application onCreate - Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        
        // Register dynamic broadcast receivers for keep-alive
        registerKeepAliveReceivers()
        
        // Initialize keep-alive if user is logged in
        initializeKeepAliveIfLoggedIn()
    }
    
    override fun onTerminate() {
        super.onTerminate()
        unregisterKeepAliveReceivers()
    }
    
    /**
     * Register broadcast receivers that need to be registered dynamically
     */
    private fun registerKeepAliveReceivers() {
        try {
            keepAliveReceiver = KeepAliveReceiver()
            
            val filter = IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
                addAction(android.content.Intent.ACTION_POWER_CONNECTED)
                addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
                // Network changes (deprecated but still works)
                @Suppress("DEPRECATION")
                addAction("android.net.conn.CONNECTIVITY_CHANGE")
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(keepAliveReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(keepAliveReceiver, filter)
            }
            
            Log.d(TAG, "Keep-alive receivers registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register keep-alive receivers", e)
        }
    }
    
    /**
     * Unregister broadcast receivers
     */
    private fun unregisterKeepAliveReceivers() {
        try {
            keepAliveReceiver?.let {
                unregisterReceiver(it)
            }
            keepAliveReceiver = null
            Log.d(TAG, "Keep-alive receivers unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receivers", e)
        }
    }
    
    /**
     * Initialize keep-alive mechanisms if user is logged in
     */
    private fun initializeKeepAliveIfLoggedIn() {
        try {
            val prefs = getSharedPreferences("clipboard_sync_tokens", Context.MODE_PRIVATE)
            val hasToken = prefs.getString("access_token", null) != null
            
            if (hasToken) {
                Log.i(TAG, "User is logged in, initializing keep-alive")
                val keepAliveManager = KeepAliveManager.getInstance(this)
                keepAliveManager.startKeepAlive()
            } else {
                Log.d(TAG, "User not logged in, skipping keep-alive initialization")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing keep-alive", e)
        }
    }
    
    /**
     * Called when user logs in - start keep-alive
     */
    fun onUserLoggedIn() {
        Log.i(TAG, "User logged in, starting keep-alive")
        val keepAliveManager = KeepAliveManager.getInstance(this)
        keepAliveManager.startKeepAlive()
    }
    
    /**
     * Called when user logs out - stop keep-alive
     */
    fun onUserLoggedOut() {
        Log.i(TAG, "User logged out, stopping keep-alive")
        val keepAliveManager = KeepAliveManager.getInstance(this)
        keepAliveManager.stopKeepAlive()
    }
}