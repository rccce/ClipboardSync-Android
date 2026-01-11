package com.siw.clipboardsync.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.siw.clipboardsync.MainActivity
import com.siw.clipboardsync.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Persistent foreground service for clipboard monitoring.
 * Provides continuous clipboard access through a foreground service with notification,
 * ensuring monitoring continues even when the app is backgrounded.
 */
@AndroidEntryPoint
class ForegroundClipboardService : Service(), DefaultLifecycleObserver {
    
    companion object {
        private const val TAG = "ForegroundClipboardService"
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "foreground_clipboard_channel"
        
        // Service actions
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_RESTART_SERVICE = "RESTART_SERVICE"
        
        // Auto-restart configuration
        private const val RESTART_DELAY_MS = 5000L // 5 seconds
        private const val MAX_RESTART_ATTEMPTS = 3
        
        /**
         * Starts the foreground clipboard service.
         */
        fun startService(context: Context) {
            val intent = Intent(context, ForegroundClipboardService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stops the foreground clipboard service.
         */
        fun stopService(context: Context) {
            val intent = Intent(context, ForegroundClipboardService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            context.startService(intent)
        }
        
        /**
         * Restarts the foreground clipboard service.
         */
        fun restartService(context: Context) {
            val intent = Intent(context, ForegroundClipboardService::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isServiceRunning = AtomicBoolean(false)
    private val binder = ForegroundClipboardBinder()
    private var restartAttempts = 0
    private var isAppInForeground = false
    
    // Service lifecycle callbacks
    private val serviceCallbacks = mutableSetOf<ServiceCallback>()
    
    /**
     * Interface for service lifecycle callbacks.
     */
    interface ServiceCallback {
        fun onServiceStarted()
        fun onServiceStopped()
        fun onServiceError(error: Throwable)
    }
    
    /**
     * Binder class for service communication.
     */
    inner class ForegroundClipboardBinder : Binder() {
        fun getService(): ForegroundClipboardService = this@ForegroundClipboardService
    }
    
    override fun onCreate() {
        super<Service>.onCreate()
        Log.d(TAG, "ForegroundClipboardService created")
        
        // Register for app lifecycle changes
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Create notification channel
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoringService()
            ACTION_STOP_MONITORING -> stopMonitoringService()
            ACTION_RESTART_SERVICE -> restartMonitoringService()
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                startMonitoringService() // Default to start
            }
        }
        
        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder = binder
    
    override fun onDestroy() {
        super<Service>.onDestroy()
        Log.d(TAG, "ForegroundClipboardService destroyed")
        
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        serviceScope.cancel()
        isServiceRunning.set(false)
        
        // Notify callbacks
        serviceCallbacks.forEach { callback ->
            try {
                callback.onServiceStopped()
            } catch (e: Exception) {
                Log.e(TAG, "Error in service callback", e)
            }
        }
    }
    
    // Lifecycle observer methods
    override fun onStart(owner: LifecycleOwner) {
        isAppInForeground = true
        updateNotification("Monitoring clipboard (app active)")
        Log.d(TAG, "App moved to foreground")
    }
    
    override fun onStop(owner: LifecycleOwner) {
        isAppInForeground = false
        updateNotification("Monitoring clipboard in background")
        Log.d(TAG, "App moved to background")
    }
    
    /**
     * Starts the monitoring service.
     */
    private fun startMonitoringService() {
        if (isServiceRunning.get()) {
            Log.d(TAG, "Service already running")
            return
        }
        
        try {
            // Start foreground service with notification
            val notification = createNotification("Starting clipboard monitoring...")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            
            isServiceRunning.set(true)
            restartAttempts = 0 // Reset restart attempts on successful start
            
            // Update notification to show active monitoring
            updateNotification(getMonitoringStatusText())
            
            Log.i(TAG, "Foreground clipboard service started")
            
            // Notify callbacks
            serviceCallbacks.forEach { callback ->
                try {
                    callback.onServiceStarted()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in service callback", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            isServiceRunning.set(false)
            
            // Notify callbacks of error
            serviceCallbacks.forEach { callback ->
                try {
                    callback.onServiceError(e)
                } catch (ex: Exception) {
                    Log.e(TAG, "Error in service error callback", ex)
                }
            }
            
            // Attempt auto-restart if within limits
            attemptAutoRestart(e)
        }
    }
    
    /**
     * Stops the monitoring service.
     */
    private fun stopMonitoringService() {
        if (!isServiceRunning.get()) {
            Log.d(TAG, "Service not running")
            return
        }
        
        try {
            isServiceRunning.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            
            Log.i(TAG, "Foreground clipboard service stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service", e)
        }
    }
    
    /**
     * Restarts the monitoring service.
     */
    private fun restartMonitoringService() {
        Log.i(TAG, "Restarting foreground clipboard service")
        
        serviceScope.launch {
            try {
                if (isServiceRunning.get()) {
                    stopMonitoringService()
                    delay(1000) // Brief delay before restart
                }
                startMonitoringService()
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting service", e)
                attemptAutoRestart(e)
            }
        }
    }
    
    /**
     * Attempts to auto-restart the service after a failure.
     */
    private fun attemptAutoRestart(error: Throwable) {
        if (restartAttempts >= MAX_RESTART_ATTEMPTS) {
            Log.e(TAG, "Max restart attempts exceeded, giving up")
            updateNotification("Service failed - manual restart required")
            return
        }
        
        restartAttempts++
        Log.w(TAG, "Attempting auto-restart (attempt $restartAttempts/$MAX_RESTART_ATTEMPTS)")
        
        serviceScope.launch {
            try {
                delay(RESTART_DELAY_MS)
                if (!isServiceRunning.get()) {
                    startMonitoringService()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-restart failed", e)
                attemptAutoRestart(e)
            }
        }
    }
    
    /**
     * Gets the current monitoring status text for the notification.
     */
    private fun getMonitoringStatusText(): String {
        return when {
            isAppInForeground -> "Monitoring clipboard (app active)"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> 
                "Monitoring clipboard in background (Android 10+)"
            else -> "Monitoring clipboard in background"
        }
    }
    
    /**
     * Creates the notification channel for the foreground service.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Clipboard Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent clipboard monitoring service"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Creates a notification for the foreground service.
     */
    private fun createNotification(contentText: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, ForegroundClipboardService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val restartIntent = Intent(this, ForegroundClipboardService::class.java).apply {
            action = ACTION_RESTART_SERVICE
        }
        val restartPendingIntent = PendingIntent.getService(
            this, 2, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipboardSync - Foreground Monitor")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_clipboard_sync)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .addAction(android.R.drawable.ic_menu_rotate, "Restart", restartPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    /**
     * Updates the notification with new content text.
     */
    private fun updateNotification(contentText: String) {
        if (!isServiceRunning.get()) return
        
        try {
            val notification = createNotification(contentText)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }
    
    /**
     * Registers a service callback.
     */
    fun registerCallback(callback: ServiceCallback) {
        serviceCallbacks.add(callback)
    }
    
    /**
     * Unregisters a service callback.
     */
    fun unregisterCallback(callback: ServiceCallback) {
        serviceCallbacks.remove(callback)
    }
    
    /**
     * Checks if the service is currently running.
     */
    fun isRunning(): Boolean = isServiceRunning.get()
    
    /**
     * Gets the current restart attempt count.
     */
    fun getRestartAttempts(): Int = restartAttempts
}