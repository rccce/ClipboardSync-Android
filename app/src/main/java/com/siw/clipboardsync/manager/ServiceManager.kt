package com.siw.clipboardsync.manager

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceManager @Inject constructor(
    private val context: Context
) {
    
    /**
     * Check if clipboard monitor service is running
     */
    fun isClipboardServiceRunning(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
        
        return runningServices.any { serviceInfo ->
            serviceInfo.service.className == "com.siw.clipboardsync.service.ClipboardMonitorService"
        }
    }
    
    /**
     * Start clipboard monitoring service
     */
    fun startClipboardService(): Boolean {
        return if (hasRequiredPermissions()) {
            com.siw.clipboardsync.service.ClipboardMonitorService.startService(context)
            true
        } else {
            false
        }
    }
    
    /**
     * Stop clipboard monitoring service
     */
    fun stopClipboardService() {
        com.siw.clipboardsync.service.ClipboardMonitorService.stopService(context)
    }
    
    /**
     * Check if all required permissions are granted
     */
    fun hasRequiredPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Get list of required permissions based on Android version
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        
        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        return permissions
    }
    
    /**
     * Get list of missing permissions
     */
    fun getMissingPermissions(): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if we can request permissions (not permanently denied)
     */
    fun canRequestPermissions(): Boolean {
        // For now, always return true. In a real app, you'd check if permissions were permanently denied
        return true
    }
}