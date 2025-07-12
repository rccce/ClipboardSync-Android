package com.siw.clipboardsync.service

import android.app.*
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.siw.clipboardsync.R
import com.siw.clipboardsync.data.repository.ClipboardRepository
import com.siw.clipboardsync.manager.ClipboardSyncManager
import com.siw.clipboardsync.utils.ClipboardUtils
import com.siw.clipboardsync.utils.DeviceUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class ClipboardMonitorService : Service() {
    
    @Inject
    lateinit var clipboardRepository: ClipboardRepository
    
    @Inject
    lateinit var clipboardSyncManager: ClipboardSyncManager
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private lateinit var clipboardManager: ClipboardManager
    private var lastClipboardContent: String? = null
    private var lastClipboardHash: String? = null
    private var isMonitoring = false
    private var deviceId: String? = null
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "clipboard_sync_channel"
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        
        fun startService(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            context.startService(intent)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        deviceId = DeviceUtils.generateDeviceId(this)
        createNotificationChannel()
        
        // Initialize clipboard sync manager
        Log.d("ClipboardMonitorService", "About to initialize ClipboardSyncManager")
        serviceScope.launch {
            try {
                Log.d("ClipboardMonitorService", "Calling clipboardSyncManager.initialize()")
                clipboardSyncManager.initialize()
                Log.d("ClipboardMonitorService", "ClipboardSyncManager initialization completed")
            } catch (e: Exception) {
                Log.e("ClipboardMonitorService", "Failed to initialize sync manager", e)
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        clipboardSyncManager.cleanup()
        serviceScope.cancel()
    }
    
    private fun startMonitoring() {
        if (isMonitoring) return
        
        isMonitoring = true
        startForeground(NOTIFICATION_ID, createNotification("Monitoring clipboard..."))
        
        // Initialize with current clipboard content to avoid initial sync
        initializeClipboardState()
        
        // Start monitoring loop
        serviceScope.launch {
            monitorClipboard()
        }
    }
    
    private fun stopMonitoring() {
        isMonitoring = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun initializeClipboardState() {
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val content = ClipboardUtils.extractTextContent(clipData)
                if (content != null) {
                    lastClipboardContent = content
                    lastClipboardHash = DeviceUtils.generateContentHash(content)
                }
            }
        } catch (e: Exception) {
            // Ignore initialization errors
        }
    }
    
    private suspend fun monitorClipboard() {
        while (isMonitoring) {
            try {
                checkClipboardChanges()
                delay(1000) // Check every second
            } catch (e: Exception) {
                // Log error but continue monitoring
                delay(5000) // Wait longer on error
            }
        }
    }
    
    private suspend fun checkClipboardChanges() {
        try {
            val clipData = clipboardManager.primaryClip ?: return
            if (clipData.itemCount == 0) return
            
            val content = ClipboardUtils.extractTextContent(clipData)
            if (content.isNullOrBlank()) return
            
            val contentHash = DeviceUtils.generateContentHash(content)
            
            // Check if content has changed
            if (contentHash != lastClipboardHash) {
                lastClipboardContent = content
                lastClipboardHash = contentHash
                
                // Sync to cloud
                syncClipboardToCloud(content, "text")
                updateNotification("Syncing clipboard...")
            }
        } catch (e: Exception) {
            // Handle clipboard access errors
        }
    }
    
    private suspend fun syncClipboardToCloud(content: String, contentType: String) {
        try {
            // Use the new ClipboardSyncManager for both HTTP and WebSocket sync
            val result = clipboardSyncManager.syncLocalClipboard(content, contentType)
            if (result.isSuccess) {
                updateNotification("Clipboard synced")
                // Reset notification after a delay
                delay(2000)
                updateNotification("Monitoring clipboard...")
            } else {
                updateNotification("Sync failed - retrying...")
            }
        } catch (e: Exception) {
            updateNotification("Sync error - retrying...")
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clipboard Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Clipboard synchronization service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, ClipboardMonitorService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipboardSync")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_clipboard_sync)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .build()
    }
    
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}