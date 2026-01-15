package com.siw.clipboardsync.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import com.siw.clipboardsync.manager.FileSyncManager
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.utils.ClipboardUtils
import com.siw.clipboardsync.utils.DeviceUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Foreground service for clipboard synchronization.
 * 
 * Simplified monitoring model based on real-world testing:
 * - XPOSED_HOOKS: Full background sync (highest priority)
 * - SHIZUKU: Full background sync without root
 * - FOREGROUND_SYNC: Sync when app comes to foreground (fallback)
 * 
 * Only ONE method is active at a time (mutually exclusive).
 * No polling is used - background sync only works with Xposed or Shizuku.
 */
@AndroidEntryPoint
class ClipboardMonitorService : Service(), DefaultLifecycleObserver {
    
    @Inject
    lateinit var clipboardRepository: ClipboardRepository
    
    @Inject
    lateinit var clipboardSyncManager: ClipboardSyncManager
    
    @Inject
    lateinit var fileSyncManager: FileSyncManager
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private lateinit var clipboardManager: ClipboardManager
    
    // Network connectivity monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastClipboardHash: String? = null
    private var isMonitoring = false
    private var deviceId: String? = null
    private var isAppInForeground = false
    
    // Monitoring state
    private var currentMonitoringMethod: MonitoringMethod? = null
    private var initializationJob: Job? = null
    private var isInitialized = false
    
    // Keep-alive helpers
    private var keepAliveManager: KeepAliveManager? = null
    private var shizukuKeepAliveHelper: ShizukuKeepAliveHelper? = null
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "clipboard_sync_channel"
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_MANUAL_SYNC = "MANUAL_SYNC"
        
        private const val TAG = "ClipboardMonitorService"
        
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
        
        // Setup network connectivity monitoring for auto-reconnect
        setupNetworkMonitoring()
        
        // Initialize keep-alive mechanisms
        initializeKeepAlive()
        
        // Initialize clipboard sync manager
        Log.d(TAG, "Initializing ClipboardSyncManager for Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        initializationJob = serviceScope.launch {
            try {
                clipboardSyncManager.initialize()
                Log.d(TAG, "ClipboardSyncManager initialization completed")
                
                // Initialize monitoring based on device capabilities
                initializeMonitoring()
                isInitialized = true
                Log.d(TAG, "Service initialization completed, method=$currentMonitoringMethod")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize sync manager", e)
                isInitialized = true
                currentMonitoringMethod = MonitoringMethod.FOREGROUND_SYNC
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
        
        // Cleanup network monitoring
        cleanupNetworkMonitoring()
        
        // Cleanup keep-alive
        cleanupKeepAlive()
        
        clipboardSyncManager.cleanup()
        serviceScope.cancel()
    }
    
    /**
     * Initialize keep-alive mechanisms based on available capabilities
     */
    private fun initializeKeepAlive() {
        try {
            Log.d(TAG, "Initializing keep-alive mechanisms")
            
            // Initialize KeepAliveManager
            keepAliveManager = KeepAliveManager.getInstance(this)
            keepAliveManager?.startKeepAlive()
            
            // Initialize Shizuku keep-alive if available
            serviceScope.launch {
                try {
                    shizukuKeepAliveHelper = ShizukuKeepAliveHelper(this@ClipboardMonitorService)
                    if (shizukuKeepAliveHelper?.isShizukuAvailable() == true &&
                        shizukuKeepAliveHelper?.hasShizukuPermission() == true) {
                        
                        val success = shizukuKeepAliveHelper?.initialize() ?: false
                        if (success) {
                            shizukuKeepAliveHelper?.startPeriodicRefresh()
                            Log.i(TAG, "Shizuku keep-alive initialized")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Shizuku keep-alive not available", e)
                }
            }
            
            Log.d(TAG, "Keep-alive mechanisms initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize keep-alive", e)
        }
    }
    
    /**
     * Cleanup keep-alive resources
     */
    private fun cleanupKeepAlive() {
        try {
            shizukuKeepAliveHelper?.cleanup()
            shizukuKeepAliveHelper = null
            // Note: Don't stop KeepAliveManager here as it should continue running
            Log.d(TAG, "Keep-alive cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up keep-alive", e)
        }
    }
    
    /**
     * Setup network connectivity monitoring to auto-reconnect WebSocket when network becomes available
     */
    private fun setupNetworkMonitoring() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network became available, triggering WebSocket reconnect")
                    serviceScope.launch {
                        delay(1000) // Small delay to let network stabilize
                        clipboardSyncManager.reconnectIfNeeded()
                        // Update notification after reconnect attempt
                        delay(500)
                        updateNotificationForMethod(currentMonitoringMethod)
                    }
                }
                
                override fun onLost(network: Network) {
                    Log.w(TAG, "Network lost")
                    updateNotification("网络断开，等待重连...")
                }
                
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    Log.d(TAG, "Network capabilities changed: hasInternet=$hasInternet, isValidated=$isValidated")
                    
                    if (hasInternet && isValidated) {
                        serviceScope.launch {
                            clipboardSyncManager.reconnectIfNeeded()
                            // Update notification after reconnect
                            delay(500)
                            updateNotificationForMethod(currentMonitoringMethod)
                        }
                    }
                }
            }
            
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.d(TAG, "Network monitoring setup completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup network monitoring", e)
        }
    }
    
    /**
     * Cleanup network monitoring resources
     */
    private fun cleanupNetworkMonitoring() {
        try {
            networkCallback?.let { callback ->
                connectivityManager?.unregisterNetworkCallback(callback)
            }
            networkCallback = null
            connectivityManager = null
            Log.d(TAG, "Network monitoring cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up network monitoring", e)
        }
    }
    
    // Track if we've done the first foreground initialization
    private var hasInitializedInForeground = false
    
    // Lifecycle observer methods - for FOREGROUND_SYNC mode
    override fun onStart(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStart(owner)
        val wasInBackground = !isAppInForeground
        isAppInForeground = true
        Log.d(TAG, "App moved to foreground, wasInBackground=$wasInBackground, method=$currentMonitoringMethod")
        
        // If using FOREGROUND_SYNC mode, sync clipboard now
        if (currentMonitoringMethod == MonitoringMethod.FOREGROUND_SYNC) {
            Log.d(TAG, "FOREGROUND_SYNC mode - scheduling clipboard check")
            serviceScope.launch {
                // Wait for app to gain focus - Android 10+ requires focus to read clipboard
                delay(200)
                
                if (isAppInForeground) {
                    // First time we come to foreground, just initialize the hash without syncing
                    // This prevents syncing old clipboard content that was there before app started
                    if (!hasInitializedInForeground) {
                        Log.d(TAG, "First foreground access - initializing clipboard hash without sync")
                        initializeClipboardState()
                        hasInitializedInForeground = true
                    } else {
                        Log.d(TAG, "Checking clipboard for changes")
                        checkAndSyncClipboard()
                    }
                }
            }
        }
    }
    
    override fun onStop(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStop(owner)
        isAppInForeground = false
        Log.d(TAG, "App moved to background")
    }
    
    private fun startMonitoring() {
        if (isMonitoring) return
        
        isMonitoring = true
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        
        // Initialize clipboard state
        initializeClipboardState()
        
        // Start monitoring loop
        serviceScope.launch {
            monitoringLoop()
        }
        
        Log.i(TAG, "Started clipboard monitoring service")
    }
    
    private fun stopMonitoring() {
        isMonitoring = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Stopped clipboard monitoring")
    }
    
    private fun performManualSync() {
        Log.d(TAG, "Manual sync requested from notification")
        serviceScope.launch {
            try {
                // On Android 10+, we cannot read clipboard in background
                // We need to bring the app to foreground first
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isAppInForeground) {
                    Log.d(TAG, "Android 10+ detected, bringing app to foreground for clipboard access")
                    
                    // Launch the main activity to get foreground access
                    val intent = Intent(this@ClipboardMonitorService, com.siw.clipboardsync.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("action", "manual_sync")
                    }
                    startActivity(intent)
                    
                    // The actual sync will be triggered by MainActivity via MainViewModel.performManualSync()
                    // So we don't need to do anything else here
                    Log.d(TAG, "Activity launched for manual sync, sync will be handled by MainViewModel")
                    return@launch
                }
                
                // If already in foreground, perform manual sync directly
                // Manual sync forces sync regardless of hash
                forceCheckAndSyncClipboard()
                
            } catch (e: Exception) {
                Log.e(TAG, "Manual sync failed", e)
                updateNotification("同步失败: ${e.message?.take(30)}")
                delay(2000)
                updateNotificationForMethod(currentMonitoringMethod)
            }
        }
    }
    
    /**
     * Force sync clipboard content regardless of hash.
     * Used for manual sync requests.
     */
    private suspend fun forceCheckAndSyncClipboard() {
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData == null || clipData.itemCount == 0) {
                Log.d(TAG, "No clipboard data for manual sync")
                updateNotification("剪贴板为空")
                delay(2000)
                updateNotificationForMethod(currentMonitoringMethod)
                return
            }
            
            val content = ClipboardUtils.extractTextContent(clipData)
            if (content.isNullOrBlank()) {
                Log.d(TAG, "No text content in clipboard")
                updateNotification("剪贴板无文本内容")
                delay(2000)
                updateNotificationForMethod(currentMonitoringMethod)
                return
            }
            
            Log.i(TAG, "Manual sync - syncing: ${content.take(50)}...")
            
            if (ClipboardUtils.shouldSyncContent(content)) {
                val result = clipboardSyncManager.syncLocalClipboard(content, "text")
                if (result.isSuccess) {
                    // Update hash after successful sync
                    lastClipboardHash = DeviceUtils.generateContentHash(content)
                    Log.d(TAG, "Manual sync completed successfully")
                    updateNotification("已同步")
                    delay(2000)
                    updateNotificationForMethod(currentMonitoringMethod)
                } else {
                    Log.w(TAG, "Manual sync failed: ${result.exceptionOrNull()?.message}")
                    updateNotification("同步失败")
                    delay(2000)
                    updateNotificationForMethod(currentMonitoringMethod)
                }
            } else {
                Log.d(TAG, "Content filtered out from sync")
                updateNotification("内容被过滤")
                delay(2000)
                updateNotificationForMethod(currentMonitoringMethod)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Clipboard access denied: ${e.message}")
            updateNotification("无法访问剪贴板")
            delay(2000)
            updateNotificationForMethod(currentMonitoringMethod)
        } catch (e: Exception) {
            Log.e(TAG, "Error in manual sync", e)
            updateNotification("同步失败: ${e.message?.take(20)}")
            delay(2000)
            updateNotificationForMethod(currentMonitoringMethod)
        }
    }
    
    private fun initializeClipboardState() {
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val content = ClipboardUtils.extractTextContent(clipData)
                if (content != null) {
                    lastClipboardHash = DeviceUtils.generateContentHash(content)
                    Log.d(TAG, "Initialized with current clipboard hash")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize clipboard state", e)
        }
    }

    
    /**
     * Initialize monitoring based on device capabilities.
     * Only ONE method will be active (mutually exclusive).
     */
    private suspend fun initializeMonitoring() {
        Log.d(TAG, "Determining optimal monitoring method...")
        
        if (clipboardSyncManager.isAdvancedMonitoringAvailable()) {
            val success = clipboardSyncManager.enableAdvancedMonitoring()
            if (success) {
                // Get the actual method being used
                val status = clipboardSyncManager.getAdvancedMonitoringStatus()
                currentMonitoringMethod = status.currentMethod
                Log.i(TAG, "Advanced monitoring enabled: $currentMonitoringMethod")
                updateNotificationForMethod(currentMonitoringMethod)
                
                // Observe method changes
                serviceScope.launch {
                    clipboardSyncManager.monitoringMethod.collect { method ->
                        if (method != null && method != currentMonitoringMethod) {
                            currentMonitoringMethod = method
                            Log.d(TAG, "Monitoring method changed to: $method")
                            updateNotificationForMethod(method)
                        }
                    }
                }
                return
            }
        }
        
        // Fallback to FOREGROUND_SYNC
        currentMonitoringMethod = MonitoringMethod.FOREGROUND_SYNC
        Log.i(TAG, "Using FOREGROUND_SYNC mode (sync on app focus)")
        updateNotificationForMethod(MonitoringMethod.FOREGROUND_SYNC)
    }
    
    /**
     * Main monitoring loop.
     * For FOREGROUND_SYNC mode, just keeps the service alive.
     * For XPOSED/SHIZUKU modes, the actual monitoring is handled by those systems.
     */
    private suspend fun monitoringLoop() {
        // Wait for initialization
        initializationJob?.join()
        
        Log.d(TAG, "Monitoring loop started, method=$currentMonitoringMethod")
        
        while (isMonitoring) {
            try {
                when (currentMonitoringMethod) {
                    MonitoringMethod.XPOSED_HOOKS, MonitoringMethod.SHIZUKU -> {
                        // Background sync is handled by the monitoring system
                        // Just check if it's still active
                        if (!clipboardSyncManager.isMonitoringActive.value) {
                            Log.w(TAG, "Advanced monitoring became inactive, switching to FOREGROUND_SYNC")
                            currentMonitoringMethod = MonitoringMethod.FOREGROUND_SYNC
                            updateNotificationForMethod(MonitoringMethod.FOREGROUND_SYNC)
                        }
                        delay(5000) // Check every 5 seconds
                    }
                    
                    MonitoringMethod.FOREGROUND_SYNC -> {
                        // No background sync - just keep service alive
                        // Sync happens in onStart() when app comes to foreground
                        delay(60000) // Just keep alive, check every minute
                    }
                    
                    else -> {
                        delay(5000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in monitoring loop", e)
                delay(5000)
            }
        }
    }
    
    /**
     * Check clipboard and sync if changed.
     * Used for FOREGROUND_SYNC mode and manual sync.
     * Supports both text and file URI content.
     */
    private suspend fun checkAndSyncClipboard() {
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData == null || clipData.itemCount == 0) {
                Log.d(TAG, "No clipboard data")
                return
            }
            
            // Check content type
            val contentType = ClipboardUtils.getContentType(clipData)
            Log.d(TAG, "Clipboard content type: $contentType")
            
            when (contentType) {
                "file" -> {
                    // Handle file URI
                    val uri = ClipboardUtils.extractUriContent(clipData)
                    if (uri != null) {
                        handleFileClipboard(uri)
                    } else {
                        Log.d(TAG, "No URI found in file clipboard")
                    }
                }
                "image" -> {
                    // Handle image URI
                    val uri = ClipboardUtils.extractUriContent(clipData)
                    if (uri != null) {
                        handleFileClipboard(uri)
                    } else {
                        Log.d(TAG, "No URI found in image clipboard")
                    }
                }
                else -> {
                    // Handle text content
                    val content = ClipboardUtils.extractTextContent(clipData)
                    if (content.isNullOrBlank()) {
                        return
                    }
                    
                    val contentHash = DeviceUtils.generateContentHash(content)
                    
                    if (contentHash != lastClipboardHash) {
                        lastClipboardHash = contentHash
                        Log.i(TAG, "Clipboard changed, syncing: ${content.take(50)}...")
                        
                        if (ClipboardUtils.shouldSyncContent(content)) {
                            val result = clipboardSyncManager.syncLocalClipboard(content, "text")
                            if (result.isSuccess) {
                                Log.d(TAG, "Clipboard synced successfully")
                                updateNotification("已同步")
                                delay(2000)
                                updateNotificationForMethod(currentMonitoringMethod)
                            } else {
                                Log.w(TAG, "Sync failed: ${result.exceptionOrNull()?.message}")
                            }
                        }
                    } else {
                        Log.d(TAG, "Clipboard unchanged")
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Clipboard access denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard", e)
        }
    }
    
    /**
     * Handle file URI from clipboard - upload and sync
     */
    private suspend fun handleFileClipboard(uri: android.net.Uri) {
        try {
            val uriHash = uri.toString().hashCode().toString()
            
            // Check if already synced
            if (uriHash == lastClipboardHash) {
                Log.d(TAG, "File URI unchanged, skipping")
                return
            }
            
            lastClipboardHash = uriHash
            Log.i(TAG, "File detected in clipboard: $uri")
            updateNotification("检测到文件，正在上传...")
            
            // Use FileSyncManager to upload
            val result = fileSyncManager.uploadAndSyncFile(uri)
            
            if (result.isSuccess) {
                val item = result.getOrNull()
                Log.d(TAG, "File synced successfully: ${item?.fileName}")
                updateNotification("文件已同步: ${item?.fileName ?: "unknown"}")
                delay(3000)
                updateNotificationForMethod(currentMonitoringMethod)
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                Log.w(TAG, "File sync failed: $error")
                updateNotification("文件同步失败: ${error.take(30)}")
                delay(3000)
                updateNotificationForMethod(currentMonitoringMethod)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling file clipboard", e)
            updateNotification("文件处理失败")
            delay(2000)
            updateNotificationForMethod(currentMonitoringMethod)
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
        val mainIntent = Intent(this, com.siw.clipboardsync.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
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
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
        
        // Note: "立即同步" button removed - manual sync from notification doesn't work reliably on Android 10+
        // Users can sync by switching to the app instead
        
        return builder.build()
    }
    
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun updateNotificationForMethod(method: MonitoringMethod?) {
        val text = when (method) {
            MonitoringMethod.XPOSED_HOOKS -> "Xposed监控 (后台同步)"
            MonitoringMethod.SHIZUKU -> "Shizuku监控 (后台同步)"
            MonitoringMethod.FOREGROUND_SYNC -> "前台同步模式 (切换到app时同步)"
            else -> "监控中..."
        }
        updateNotification(text)
    }
}
