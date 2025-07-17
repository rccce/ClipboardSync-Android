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
import com.siw.clipboardsync.utils.RootUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Enhanced clipboard monitoring service with rooted device optimizations
 * 
 * Features:
 * - Adaptive monitoring based on device capabilities
 * - LSPosed hook integration for real-time monitoring
 * - Root-based optimizations for better background access
 * - Intelligent fallback strategies
 */
@AndroidEntryPoint
class EnhancedClipboardMonitorService : Service(), DefaultLifecycleObserver {
    
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
    
    // Enhanced monitoring state
    private var clipboardCapabilities: RootUtils.ClipboardCapabilities? = null
    private var rootMonitoringProcess: Process? = null
    private var monitoringJob: Job? = null
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "enhanced_clipboard_sync_channel"
        const val ACTION_START_MONITORING = "START_ENHANCED_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_ENHANCED_MONITORING"
        const val ACTION_MANUAL_SYNC = "MANUAL_SYNC"
        const val ACTION_REFRESH_CAPABILITIES = "REFRESH_CAPABILITIES"
        
        private const val TAG = "EnhancedClipboardMonitor"
        
        fun startService(context: Context) {
            val intent = Intent(context, EnhancedClipboardMonitorService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, EnhancedClipboardMonitorService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            context.stopService(intent)
        }
        
        fun refreshCapabilities(context: Context) {
            val intent = Intent(context, EnhancedClipboardMonitorService::class.java).apply {
                action = ACTION_REFRESH_CAPABILITIES
            }
            context.startService(intent)
        }
    }
    
    override fun onCreate() {
        super<Service>.onCreate()
        
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        deviceId = DeviceUtils.generateDeviceId(this)
        
        // Register lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Initialize capabilities detection
        serviceScope.launch {
            detectAndConfigureCapabilities()
        }
        
        Log.d(TAG, "Enhanced clipboard monitor service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startEnhancedMonitoring()
            ACTION_STOP_MONITORING -> stopEnhancedMonitoring()
            ACTION_MANUAL_SYNC -> performManualSync()
            ACTION_REFRESH_CAPABILITIES -> refreshCapabilities()
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super<Service>.onDestroy()
        cleanup()
        Log.d(TAG, "Enhanced clipboard monitor service destroyed")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStart(owner: LifecycleOwner) {
        isAppInForeground = true
        Log.d(TAG, "App moved to foreground - optimizing monitoring")
        optimizeForForeground()
    }
    
    override fun onStop(owner: LifecycleOwner) {
        isAppInForeground = false
        Log.d(TAG, "App moved to background - optimizing monitoring")
        optimizeForBackground()
    }
    
    /**
     * Detect device capabilities and configure monitoring accordingly
     */
    private suspend fun detectAndConfigureCapabilities() {
        try {
            clipboardCapabilities = RootUtils.getClipboardCapabilities()
            val caps = clipboardCapabilities!!
            
            Log.i(TAG, "=== DEVICE CAPABILITIES DETECTED ===")
            Log.i(TAG, "Root Access: ${caps.isRooted}")
            Log.i(TAG, "Can Bypass Android 10+ Restrictions: ${caps.canBypassAndroid10Restrictions}")
            Log.i(TAG, "Optimization Level: ${caps.getOptimizationLevel()}")
            Log.i(TAG, "Description: ${caps.getDescription()}")
            Log.i(TAG, "Recommended Polling Interval: ${caps.recommendedPollingInterval}ms")
            Log.i(TAG, "=====================================")
            
            // Configure monitoring based on capabilities
            configureMonitoringStrategy(caps)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting capabilities", e)
            // Fallback to standard monitoring
            clipboardCapabilities = RootUtils.ClipboardCapabilities(
                isRooted = false,
                canBypassAndroid10Restrictions = false,
                recommendedPollingInterval = 5000L
            )
        }
    }
    
    /**
     * Configure monitoring strategy based on device capabilities
     */
    private suspend fun configureMonitoringStrategy(capabilities: RootUtils.ClipboardCapabilities) {
        when (capabilities.getOptimizationLevel()) {
            RootUtils.OptimizationLevel.HIGH -> {
                Log.i(TAG, "Configuring HIGH optimization (Root Access)")
                setupRootOptimizedMonitoring()
            }
            RootUtils.OptimizationLevel.STANDARD -> {
                Log.i(TAG, "Configuring STANDARD optimization (Non-rooted)")
                setupStandardMonitoring()
            }
        }
    }
    
    
    /**
     * Setup root-optimized monitoring (good performance)
     */
    private fun setupRootOptimizedMonitoring() {
        serviceScope.launch {
            try {
                // Try to start root-based clipboard monitoring
                rootMonitoringProcess = RootUtils.startRootClipboardMonitoring { content ->
                    serviceScope.launch {
                        handleClipboardChange(content)
                    }
                }
                
                if (rootMonitoringProcess != null) {
                    Log.i(TAG, "Root-optimized monitoring configured successfully")
                } else {
                    Log.w(TAG, "Root monitoring failed, falling back to standard")
                    setupStandardMonitoring()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup root monitoring, falling back", e)
                setupStandardMonitoring()
            }
        }
    }
    
    /**
     * Setup standard polling-based monitoring (fallback)
     */
    private fun setupStandardMonitoring() {
        Log.i(TAG, "Standard polling monitoring configured")
        // This will use the existing polling logic with adaptive intervals
    }
    
    /**
     * Start enhanced monitoring
     */
    private fun startEnhancedMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Enhanced monitoring already active")
            return
        }
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing enhanced monitoring..."))
        
        serviceScope.launch {
            try {
                // Initialize clipboard sync manager
                clipboardSyncManager.initialize()
                
                // Initialize clipboard state
                initializeClipboardState()
                
                isMonitoring = true
                
                // Start appropriate monitoring based on capabilities
                val caps = clipboardCapabilities
                Log.i(TAG, "Starting adaptive polling monitoring")
                startAdaptivePolling()
                if (caps?.isRooted == true) {
                    updateNotification("Enhanced monitoring active (Root)")
                } else {
                    updateNotification("Standard monitoring active")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting enhanced monitoring", e)
                updateNotification("Enhanced monitoring failed")
            }
        }
    }
    
    /**
     * Stop enhanced monitoring
     */
    private fun stopEnhancedMonitoring() {
        Log.i(TAG, "Stopping enhanced monitoring")
        isMonitoring = false
        
        // Cancel monitoring job
        monitoringJob?.cancel()
        monitoringJob = null
        
        
        // Cleanup root monitoring
        rootMonitoringProcess?.destroy()
        rootMonitoringProcess = null
        
        // Cleanup sync manager
        clipboardSyncManager.cleanup()
        
        stopForeground(true)
        stopSelf()
    }
    
    /**
     * Start adaptive polling (fallback when real-time monitoring is not available)
     */
    private fun startAdaptivePolling() {
        monitoringJob = serviceScope.launch {
            while (isMonitoring) {
                try {
                    val success = checkClipboardChanges()
                    
                    // Use adaptive interval based on capabilities and app state
                    val interval = getAdaptivePollingInterval()
                    delay(interval)
                    
                } catch (e: CancellationException) {
                    Log.d(TAG, "Monitoring cancelled")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in adaptive polling loop", e)
                    delay(5000) // Wait before retrying
                }
            }
        }
    }
    
    /**
     * Get adaptive polling interval based on device capabilities and app state
     */
    private fun getAdaptivePollingInterval(): Long {
        val caps = clipboardCapabilities
        return when {
            // Foreground with root access
            isAppInForeground && caps?.isRooted == true -> 500L
            
            // Background with root access
            !isAppInForeground && caps?.isRooted == true -> 1000L
            
            // Foreground without root
            isAppInForeground -> 1000L
            
            // Background without root (Android 10+ restrictions)
            else -> caps?.recommendedPollingInterval ?: 5000L
        }
    }
    
    /**
     * Check for clipboard changes (used in polling mode)
     */
    private suspend fun checkClipboardChanges(): Boolean {
        return try {
            val clipData = if (clipboardCapabilities?.canBypassAndroid10Restrictions == true) {
                // Use root access to bypass Android 10+ restrictions
                getClipboardWithRootAccess()
            } else {
                // Standard clipboard access
                clipboardManager.primaryClip
            }
            
            clipData?.let { data ->
                val content = ClipboardUtils.extractTextContent(data)
                if (content != null) {
                    val contentHash = DeviceUtils.generateContentHash(content)
                    if (contentHash != lastClipboardHash) {
                        handleClipboardChange(content)
                        return true
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking clipboard changes", e)
            false
        }
    }
    
    /**
     * Get clipboard content using root access (bypasses Android 10+ restrictions)
     */
    private suspend fun getClipboardWithRootAccess(): android.content.ClipData? {
        return try {
            // Use root command to get clipboard content
            val clipText = RootUtils.executeRootCommand("service call clipboard 2 s16 com.android.shell")
            if (!clipText.isNullOrBlank()) {
                // Parse the service call output and create ClipData
                // This is a simplified implementation
                android.content.ClipData.newPlainText("root_clipboard", clipText)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting clipboard with root access", e)
            null
        }
    }
    
    /**
     * Handle clipboard content change
     */
    private suspend fun handleClipboardChange(content: String) {
        try {
            if (!ClipboardUtils.shouldSyncContent(content)) return
            
            val contentHash = DeviceUtils.generateContentHash(content)
            if (contentHash == lastClipboardHash) return
            
            lastClipboardContent = content
            lastClipboardHash = contentHash
            
            Log.d(TAG, "Clipboard changed: ${content.take(50)}...")
            
            // Sync to cloud
            val result = clipboardSyncManager.syncLocalClipboard(content, "text")
            if (result.isSuccess) {
                updateNotification("Clipboard synced")
            } else {
                updateNotification("Sync failed")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard change", e)
        }
    }
    
    /**
     * Initialize clipboard state
     */
    private fun initializeClipboardState() {
        try {
            val clipData = clipboardManager.primaryClip
            clipData?.let {
                val content = ClipboardUtils.extractTextContent(it)
                content?.let { text ->
                    lastClipboardContent = text
                    lastClipboardHash = DeviceUtils.generateContentHash(text)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize clipboard state", e)
        }
    }
    
    /**
     * Perform manual sync
     */
    private fun performManualSync() {
        serviceScope.launch {
            try {
                val clipData = clipboardManager.primaryClip
                clipData?.let {
                    val content = ClipboardUtils.extractTextContent(it)
                    if (content != null && ClipboardUtils.shouldSyncContent(content)) {
                        handleClipboardChange(content)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in manual sync", e)
            }
        }
    }
    
    /**
     * Refresh capabilities
     */
    private fun refreshCapabilities() {
        serviceScope.launch {
            detectAndConfigureCapabilities()
            updateNotification("Capabilities refreshed")
        }
    }
    
    /**
     * Optimize monitoring for foreground
     */
    private fun optimizeForForeground() {
        // Restart monitoring with foreground optimizations
        if (isMonitoring) {
            monitoringJob?.cancel()
            startAdaptivePolling()
        }
    }
    
    /**
     * Optimize monitoring for background
     */
    private fun optimizeForBackground() {
        // Restart monitoring with background optimizations
        if (isMonitoring) {
            monitoringJob?.cancel()
            startAdaptivePolling()
        }
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        isMonitoring = false
        
        monitoringJob?.cancel()
        rootMonitoringProcess?.destroy()
        clipboardSyncManager.cleanup()
        serviceScope.cancel()
        
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }
    
    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Enhanced Clipboard Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Enhanced clipboard synchronization service with root optimizations"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create notification
     */
    private fun createNotification(contentText: String): Notification {
        val mainIntent = Intent(this, com.siw.clipboardsync.MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, EnhancedClipboardMonitorService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val refreshIntent = Intent(this, EnhancedClipboardMonitorService::class.java).apply {
            action = ACTION_REFRESH_CAPABILITIES
        }
        val refreshPendingIntent = PendingIntent.getService(
            this, 1, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Enhanced ClipboardSync")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_clipboard_sync)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .addAction(android.R.drawable.ic_menu_rotate, "Refresh", refreshPendingIntent)
        
        // Add capability info to notification
        clipboardCapabilities?.let { caps ->
            builder.setSubText(caps.getDescription())
        }
        
        return builder.build()
    }
    
    /**
     * Update notification
     */
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}