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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.siw.clipboardsync.R
import com.siw.clipboardsync.data.repository.ClipboardRepository
import com.siw.clipboardsync.manager.ClipboardSyncManager
import com.siw.clipboardsync.utils.ClipboardUtils
import com.siw.clipboardsync.utils.DeviceUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class ClipboardMonitorService : Service(), DefaultLifecycleObserver {
    
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
    private var isAppInForeground = false
    private var backgroundAccessDeniedCount = 0
    private var isShowingBackgroundLimitationNotification = false
    
    // Advanced monitoring integration
    private var useAdvancedMonitoring = true
    private var advancedMonitoringEnabled = false
    private var fallbackToPolling = false
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "clipboard_sync_channel"
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_MANUAL_SYNC = "MANUAL_SYNC"
        
        private const val TAG = "ClipboardMonitorService"
        
        // Adaptive polling intervals based on Android version and app state
        private const val FOREGROUND_POLL_INTERVAL = 500L // 0.5 seconds when app is active
        private const val BACKGROUND_POLL_INTERVAL_OLD = 1000L // 1 second for Android 9 and below
        private const val BACKGROUND_POLL_INTERVAL_NEW = 5000L // 5 seconds for Android 10+
        
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
        
        fun manualSync(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java).apply {
                action = ACTION_MANUAL_SYNC
            }
            context.startService(intent)
        }
    }
    
    override fun onCreate() {
        super<Service>.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        deviceId = DeviceUtils.generateDeviceId(this)
        createNotificationChannel()
        
        // Register for app lifecycle changes
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Initialize clipboard sync manager
        Log.d(TAG, "Initializing ClipboardSyncManager for Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        serviceScope.launch {
            try {
                clipboardSyncManager.initialize()
                Log.d(TAG, "ClipboardSyncManager initialization completed")
                
                // Check if advanced monitoring is available and enable it
                initializeAdvancedMonitoring()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize sync manager", e)
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            ACTION_MANUAL_SYNC -> performManualSync()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder? = null
    
    override fun onDestroy() {
        super<Service>.onDestroy()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        clipboardSyncManager.cleanup()
        serviceScope.cancel()
    }
    
    // Lifecycle observer methods
    override fun onStart(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStart(owner)
        isAppInForeground = true
        backgroundAccessDeniedCount = 0 // Reset counter when app comes to foreground
        
        // Reset to normal notification when app comes to foreground
        if (isShowingBackgroundLimitationNotification) {
            isShowingBackgroundLimitationNotification = false
            updateNotification(getInitialNotificationText())
        }
        
        Log.d(TAG, "App moved to foreground - enabling aggressive clipboard monitoring")
    }
    
    override fun onStop(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStop(owner)
        isAppInForeground = false
        Log.d(TAG, "App moved to background - switching to conservative monitoring")
    }
    
    private fun startMonitoring() {
        if (isMonitoring) return
        
        isMonitoring = true
        val notificationText = getInitialNotificationText()
        startForeground(NOTIFICATION_ID, createNotification(notificationText))
        
        // Initialize with current clipboard content to avoid initial sync
        initializeClipboardState()
        
        // Start adaptive monitoring
        serviceScope.launch {
            adaptiveClipboardMonitoring()
        }
        
        Log.i(TAG, "Started clipboard monitoring with Android ${Build.VERSION.RELEASE} optimizations")
    }
    
    private fun stopMonitoring() {
        isMonitoring = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Stopped clipboard monitoring")
    }
    
    private fun performManualSync() {
        Log.d(TAG, "Manual sync requested")
        serviceScope.launch {
            try {
                val clipData = clipboardManager.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val content = ClipboardUtils.extractTextContent(clipData)
                    if (content != null && ClipboardUtils.shouldSyncContent(content)) {
                        syncClipboardToCloud(content, "text", isManual = true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Manual sync failed", e)
                updateNotification("Manual sync failed - ${e.message}")
            }
        }
    }
    
    private fun getInitialNotificationText(): String {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                "Monitoring clipboard (Android 10+ limitations apply)"
            }
            else -> {
                "Monitoring clipboard..."
            }
        }
    }
    
    private fun initializeClipboardState() {
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val content = ClipboardUtils.extractTextContent(clipData)
                if (content != null) {
                    lastClipboardContent = content
                    lastClipboardHash = DeviceUtils.generateContentHash(content)
                    Log.d(TAG, "Initialized with current clipboard content")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize clipboard state", e)
        }
    }
    
    private suspend fun adaptiveClipboardMonitoring() {
        Log.d(TAG, "Starting adaptive clipboard monitoring")
        
        // If advanced monitoring is enabled, we don't need to poll
        if (advancedMonitoringEnabled && !fallbackToPolling) {
            Log.i(TAG, "Advanced monitoring is active, skipping polling loop")
            
            // Just monitor the advanced monitoring status and fallback if needed
            while (isMonitoring) {
                try {
                    // Check if advanced monitoring is still active
                    if (!clipboardSyncManager.isMonitoringActive.value) {
                        Log.w(TAG, "Advanced monitoring became inactive, falling back to polling")
                        fallbackToPolling = true
                        break
                    }
                    
                    delay(5000) // Check every 5 seconds
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring advanced monitoring status", e)
                    fallbackToPolling = true
                    break
                }
            }
        }
        
        // Use legacy polling if advanced monitoring is not available or failed
        if (shouldUseLegacyPolling()) {
            Log.i(TAG, "Using legacy polling for clipboard monitoring")
            
            while (isMonitoring) {
                try {
                    val pollInterval = calculatePollInterval()
                    val success = checkClipboardChanges()
                    
                    if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        handleBackgroundAccessDenied()
                    }
                    
                    delay(pollInterval)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in clipboard monitoring loop", e)
                    delay(5000) // Wait longer on error
                }
            }
        }
    }
    
    private fun calculatePollInterval(): Long {
        return when {
            isAppInForeground -> {
                FOREGROUND_POLL_INTERVAL
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10+ has background restrictions
                BACKGROUND_POLL_INTERVAL_NEW
            }
            else -> {
                // Android 9 and below - more frequent polling is safe
                BACKGROUND_POLL_INTERVAL_OLD
            }
        }
    }
    
    private suspend fun checkClipboardChanges(): Boolean {
        return try {
            // Check for Android 10+ background restrictions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isAppInForeground) {
                // Attempt clipboard access but expect it might fail
                val clipData = clipboardManager.primaryClip
                if (clipData == null) {
                    // This is expected behavior on Android 10+ in background
                    Log.v(TAG, "Clipboard access restricted in background (Android 10+)")
                    return false
                }
            }
            
            val clipData = clipboardManager.primaryClip ?: return true
            if (clipData.itemCount == 0) return true
            
            val content = ClipboardUtils.extractTextContent(clipData)
            if (content.isNullOrBlank()) return true
            
            val contentHash = DeviceUtils.generateContentHash(content)
            
            // Check if content has changed
            if (contentHash != lastClipboardHash) {
                lastClipboardContent = content
                lastClipboardHash = contentHash
                
                Log.d(TAG, "Clipboard content changed: ${content.take(50)}...")
                
                // Sync to cloud
                syncClipboardToCloud(content, "text")
                updateNotification("Syncing clipboard...")
            }
            
            true // Success
            
        } catch (e: SecurityException) {
            Log.w(TAG, "Clipboard access denied: ${e.message}")
            false // Access denied
        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard changes", e)
            false // Other error
        }
    }
    
    private fun handleBackgroundAccessDenied() {
        backgroundAccessDeniedCount++
        
        when {
            backgroundAccessDeniedCount == 1 -> {
                Log.i(TAG, "Background clipboard access restricted - this is expected on Android 10+")
                updateNotification("Limited background access (Android 10+)")
            }
            backgroundAccessDeniedCount >= 5 && !isShowingBackgroundLimitationNotification -> {
                Log.i(TAG, "Consistent background access denial - updating notification with manual sync option")
                isShowingBackgroundLimitationNotification = true
                updateNotificationForBackgroundLimitation()
                backgroundAccessDeniedCount = 0 // Reset counter
            }
        }
    }
    
    private fun updateNotificationForBackgroundLimitation() {
        // Update the existing foreground notification instead of creating a new one
        updateNotification("Background access limited - tap Manual Sync when needed")
    }
    
    private fun createManualSyncPendingIntent(): PendingIntent {
        val intent = Intent(this, ClipboardMonitorService::class.java).apply {
            action = ACTION_MANUAL_SYNC
        }
        return PendingIntent.getService(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private suspend fun syncClipboardToCloud(content: String, contentType: String, isManual: Boolean = false) {
        try {
            if (!ClipboardUtils.shouldSyncContent(content)) {
                Log.d(TAG, "Content filtered out from sync")
                return
            }
            
            val result = clipboardSyncManager.syncLocalClipboard(content, contentType)
            if (result.isSuccess) {
                val message = if (isManual) "Manual sync completed" else "Clipboard synced"
                updateNotification(message)
                Log.d(TAG, "Successfully synced clipboard content")
                
                // Reset notification after a delay
                delay(2000)
                updateNotification(getInitialNotificationText())
            } else {
                val errorMessage = if (isManual) "Manual sync failed" else "Sync failed - retrying..."
                updateNotification(errorMessage)
                Log.w(TAG, "Failed to sync clipboard: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            val errorMessage = if (isManual) "Manual sync error" else "Sync error - retrying..."
            updateNotification(errorMessage)
            Log.e(TAG, "Error syncing clipboard to cloud", e)
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
                enableVibration(false)
                setSound(null, null)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(contentText: String): Notification {
        val mainIntent = Intent(this, com.siw.clipboardsync.MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, ClipboardMonitorService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipboardSync")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_clipboard_sync)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
        
        // Always add manual sync action for Android 10+ or when showing background limitation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || isShowingBackgroundLimitationNotification) {
            builder.addAction(android.R.drawable.ic_menu_rotate, "Manual Sync", createManualSyncPendingIntent())
        }
        
        return builder.build()
    }
    
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    // Advanced Monitoring Integration
    
    /**
     * Initialize advanced monitoring system
     */
    private suspend fun initializeAdvancedMonitoring() {
        try {
            if (!useAdvancedMonitoring) {
                Log.d(TAG, "Advanced monitoring disabled, using legacy polling")
                return
            }
            
            Log.d(TAG, "Checking advanced monitoring availability")
            
            if (clipboardSyncManager.isAdvancedMonitoringAvailable()) {
                Log.i(TAG, "Advanced monitoring available, attempting to enable")
                
                val success = clipboardSyncManager.enableAdvancedMonitoring()
                if (success) {
                    advancedMonitoringEnabled = true
                    fallbackToPolling = false
                    Log.i(TAG, "Advanced monitoring enabled successfully")
                    
                    // Update notification to reflect advanced monitoring
                    updateNotification("Advanced monitoring active")
                    
                    // Observe monitoring method changes
                    serviceScope.launch {
                        clipboardSyncManager.monitoringMethod.collect { method ->
                            method?.let {
                                Log.d(TAG, "Advanced monitoring method: ${it.name}")
                                updateNotificationForAdvancedMonitoring(it.name)
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to enable advanced monitoring, falling back to polling")
                    fallbackToPolling = true
                }
            } else {
                Log.i(TAG, "Advanced monitoring not available on this device, using polling")
                fallbackToPolling = true
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing advanced monitoring", e)
            fallbackToPolling = true
        }
    }
    
    /**
     * Update notification to show advanced monitoring status
     */
    private fun updateNotificationForAdvancedMonitoring(methodName: String) {
        val notificationText = when (methodName) {
            "SYSTEM_HOOKS" -> "System-level monitoring active"
            "XPOSED_HOOKS" -> "Xposed framework monitoring active"
            "ACCESSIBILITY_SERVICE" -> "Accessibility monitoring active"
            "FOREGROUND_SERVICE" -> "Foreground service monitoring active"
            "POLLING_FALLBACK" -> "Polling fallback monitoring active"
            else -> "Advanced monitoring active"
        }
        
        serviceScope.launch {
            delay(1000) // Brief delay to avoid notification spam
            updateNotification(notificationText)
        }
    }
    
    /**
     * Check if we should use legacy polling instead of advanced monitoring
     */
    private fun shouldUseLegacyPolling(): Boolean {
        return !useAdvancedMonitoring || !advancedMonitoringEnabled || fallbackToPolling
    }
}