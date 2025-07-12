package com.siw.clipboardsync.manager

import android.content.Context
import android.util.Log
import com.siw.clipboardsync.data.model.ClipboardItem
import com.siw.clipboardsync.data.repository.AuthRepository
import com.siw.clipboardsync.data.repository.ClipboardRepository
import com.siw.clipboardsync.utils.ClipboardUtils
import com.siw.clipboardsync.utils.DeviceUtils
import com.siw.clipboardsync.websocket.WebSocketClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webSocketClient: WebSocketClient,
    private val clipboardRepository: ClipboardRepository,
    private val authRepository: AuthRepository
) {
    
    companion object {
        private const val TAG = "ClipboardSyncManager"
        private const val SYNC_DEBOUNCE_MS = 1000L
    }
    
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false
    private var currentDeviceId: String? = null
    private var connectionJob: Job? = null
    
    // Deduplication mechanism to prevent sync loops
    private val recentlySyncedContent = mutableSetOf<String>()
    private val syncedContentTimestamps = mutableMapOf<String, Long>()
    private val SYNC_DEDUPLICATION_WINDOW_MS = 5000L // 5 seconds
    
    // State flows
    private val _syncStatus = MutableStateFlow(SyncStatus.DISCONNECTED)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
    
    private val _lastSyncedItem = MutableStateFlow<ClipboardItem?>(null)
    val lastSyncedItem: StateFlow<ClipboardItem?> = _lastSyncedItem.asStateFlow()
    
    enum class SyncStatus {
        DISCONNECTED, CONNECTING, CONNECTED, SYNCING, ERROR
    }
    
    /**
     * Check if manager is in a usable state
     */
    private fun isUsable(): Boolean {
        return isInitialized && scope.isActive
    }
    
    /**
     * Initialize the sync manager
     */
    suspend fun initialize() {
        if (isUsable()) {
            Log.d(TAG, "ClipboardSyncManager already initialized and usable")
            return
        }
        
        try {
            // Always recreate scope to ensure it's fresh
            if (isInitialized || !scope.isActive) {
                Log.d(TAG, "Recreating scope for fresh initialization")
                scope.cancel() // Cancel any existing scope
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            }
            
            // Start observing connection status immediately
            startObservingConnectionStatus()
            
            // Get current device ID - use the one from auth repository (matches JWT)
            currentDeviceId = authRepository.getDeviceId() ?: DeviceUtils.generateDeviceId(context)
            Log.d(TAG, "Using device ID from auth: $currentDeviceId")
            
            // Start WebSocket connection if user is logged in
            val accessToken = getAccessToken()
            Log.d(TAG, "Access token retrieved: ${if (accessToken != null) "OK" else "MISSING"}")
            Log.d(TAG, "Device ID: $currentDeviceId")
            
            if (accessToken != null && currentDeviceId != null) {
                // Start observing WebSocket updates before initiating connection
                observeWebSocketUpdates()
                startWebSocketConnection(accessToken, currentDeviceId!!)
                Log.d(TAG, "WebSocket connection started automatically on initialization")
            } else {
                Log.w(TAG, "Cannot start WebSocket - missing token or device ID")
            }
            
            isInitialized = true
            Log.d(TAG, "ClipboardSyncManager initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ClipboardSyncManager", e)
            _syncStatus.value = SyncStatus.ERROR
        }
    }
    
    /**
     * Start observing WebSocket connection status
     */
    private fun startObservingConnectionStatus() {
        // Cancel existing observer first
        connectionJob?.cancel()
        
        connectionJob = scope.launch {
            Log.d(TAG, "Starting to observe WebSocket connection status")
            try {
                webSocketClient.connectionStatus.collect { status ->
                    // Check if we're still active before processing
                    if (!isActive) {
                        Log.d(TAG, "Observer cancelled, stopping status collection")
                        return@collect
                    }
                    
                    Log.d(TAG, "=== WebSocket Status Update ===")
                    Log.d(TAG, "WebSocket status changed to: $status")
                    Log.d(TAG, "Current sync status: ${_syncStatus.value}")
                    val newSyncStatus = when (status) {
                        WebSocketClient.ConnectionStatus.CONNECTING -> SyncStatus.CONNECTING
                        WebSocketClient.ConnectionStatus.CONNECTED -> SyncStatus.CONNECTED
                        WebSocketClient.ConnectionStatus.DISCONNECTED -> SyncStatus.DISCONNECTED
                        WebSocketClient.ConnectionStatus.RECONNECTING -> SyncStatus.CONNECTING
                        WebSocketClient.ConnectionStatus.FAILED -> SyncStatus.ERROR
                    }
                    Log.d(TAG, "Mapping to sync status: $newSyncStatus")
                    _syncStatus.value = newSyncStatus
                    Log.d(TAG, "Sync status updated to: ${_syncStatus.value}")
                    Log.d(TAG, "=== Status Update Complete ===")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Connection status observer cancelled normally")
                // Don't log cancellation as error - it's expected during cleanup
            } catch (e: Exception) {
                Log.e(TAG, "Error in connection status observer", e)
            }
        }
    }
    
    /**
     * Start WebSocket connection
     */
    private fun startWebSocketConnection(accessToken: String, deviceId: String) {
        scope.launch {
            try {
                Log.d(TAG, "Starting WebSocket connection for device: $deviceId")
                // Don't manually set CONNECTING status here - let the WebSocket observer handle it
                // _syncStatus.value = SyncStatus.CONNECTING
                
                // Get user ID from auth repository
                val currentUser = authRepository.getCurrentUser()
                if (currentUser == null) {
                    Log.e(TAG, "Cannot start WebSocket connection: User not available")
                    _syncStatus.value = SyncStatus.ERROR
                    return@launch
                }
                
                // Connect to WebSocket - the connection status observer will handle status updates
                webSocketClient.connect(accessToken, currentUser.id, deviceId)
                Log.d(TAG, "WebSocket connect() called, waiting for status updates from observer")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WebSocket connection", e)
                _syncStatus.value = SyncStatus.ERROR
            }
        }
    }
    
    /**
     * Observe incoming clipboard updates from WebSocket
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeWebSocketUpdates() {
        Log.d(TAG, "Setting up WebSocket updates observer")
        scope.launch {
            try {
                Log.d(TAG, "Starting to collect clipboard updates from WebSocket flow")
                webSocketClient.clipboardUpdates
                    .debounce(SYNC_DEBOUNCE_MS) // Prevent rapid updates
                    .collect { clipboardItem ->
                        // Check if we're still active before processing
                        if (!isActive) {
                            Log.d(TAG, "WebSocket updates observer cancelled, stopping collection")
                            return@collect
                        }
                        
                        Log.d(TAG, "Received clipboard item from flow: $clipboardItem")
                        handleIncomingClipboardUpdate(clipboardItem)
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "WebSocket updates observer cancelled normally")
                // Don't log cancellation as error - it's expected during cleanup
            } catch (e: Exception) {
                Log.e(TAG, "Error in WebSocket updates observer", e)
            }
        }
    }
    
    /**
     * Handle incoming clipboard update from other devices
     */
    private suspend fun handleIncomingClipboardUpdate(clipboardItem: ClipboardItem) {
        try {
            Log.d(TAG, "=== INCOMING CLIPBOARD UPDATE ===")
            Log.d(TAG, "From device: ${clipboardItem.deviceName} (${clipboardItem.deviceId})")
            Log.d(TAG, "Current device: $currentDeviceId")
            Log.d(TAG, "Content: '${clipboardItem.content}'")
            Log.d(TAG, "Content type: ${clipboardItem.contentType}")
            
            // Don't update if it's from our own device
            if (clipboardItem.deviceId == currentDeviceId) {
                Log.d(TAG, "Skipping - update from own device")
                return
            }
            
            _syncStatus.value = SyncStatus.SYNCING
            
            // Update local clipboard
            when (clipboardItem.contentType) {
                "text" -> {
                    Log.d(TAG, "Setting clipboard content: '${clipboardItem.content}'")
                    ClipboardUtils.setTextToClipboard(context, clipboardItem.content)
                    Log.d(TAG, "Successfully updated local clipboard with text content")
                    
                    // Verify the clipboard was updated
                    val currentClipboard = ClipboardUtils.getCurrentClipboardText(context)
                    Log.d(TAG, "Verification - Current clipboard: '$currentClipboard'")
                }
                "image" -> {
                    Log.d(TAG, "Image content sync not yet implemented")
                }
                "file" -> {
                    Log.d(TAG, "File content sync not yet implemented")
                }
            }
            
            // Mark this content as recently synced to prevent upload loop
            markContentAsSynced(clipboardItem.content)
            
            _lastSyncedItem.value = clipboardItem
            _syncStatus.value = SyncStatus.CONNECTED
            Log.d(TAG, "=== CLIPBOARD UPDATE COMPLETE ===")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming clipboard update", e)
            _syncStatus.value = SyncStatus.ERROR
        }
    }
    
    /**
     * Mark content as recently synced to prevent upload loops
     */
    private fun markContentAsSynced(content: String) {
        val contentHash = content.hashCode().toString()
        val currentTime = System.currentTimeMillis()
        
        synchronized(recentlySyncedContent) {
            recentlySyncedContent.add(contentHash)
            syncedContentTimestamps[contentHash] = currentTime
            
            // Clean up old entries
            cleanupOldSyncedContent(currentTime)
        }
        
        Log.d(TAG, "Marked content as recently synced: ${content.take(50)}...")
    }
    
    /**
     * Check if content was recently synced from another device
     */
    private fun isRecentlySynced(content: String): Boolean {
        val contentHash = content.hashCode().toString()
        val currentTime = System.currentTimeMillis()
        
        synchronized(recentlySyncedContent) {
            cleanupOldSyncedContent(currentTime)
            
            val isRecent = recentlySyncedContent.contains(contentHash)
            if (isRecent) {
                Log.d(TAG, "Content was recently synced, skipping upload: ${content.take(50)}...")
            }
            return isRecent
        }
    }
    
    /**
     * Clean up old synced content entries
     */
    private fun cleanupOldSyncedContent(currentTime: Long) {
        val expiredHashes = syncedContentTimestamps.filter { (_, timestamp) ->
            currentTime - timestamp > SYNC_DEDUPLICATION_WINDOW_MS
        }.keys
        
        expiredHashes.forEach { hash ->
            recentlySyncedContent.remove(hash)
            syncedContentTimestamps.remove(hash)
        }
    }
    
    /**
     * Sync local clipboard content to other devices
     */
    suspend fun syncLocalClipboard(content: String, contentType: String = "text"): Result<ClipboardItem> {
        return try {
            if (currentDeviceId == null) {
                return Result.failure(Exception("Device ID not available"))
            }
            
            // Check if this content was recently synced from another device
            if (isRecentlySynced(content)) {
                Log.d(TAG, "Skipping sync - content was recently received from another device")
                return Result.failure(Exception("Content recently synced from another device"))
            }
            
            _syncStatus.value = SyncStatus.SYNCING
            
            // Smart sync strategy: WebSocket-first, HTTP fallback
            if (webSocketClient.isConnected()) {
                Log.d(TAG, "WebSocket connected - using WebSocket-only sync")
                syncViaWebSocketOnly(content, contentType)
            } else {
                Log.d(TAG, "WebSocket disconnected - using HTTP sync")
                syncViaHttpOnly(content, contentType)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync local clipboard", e)
            _syncStatus.value = SyncStatus.ERROR
            Result.failure(e)
        }
    }
    
    /**
     * Sync via WebSocket only (when connected)
     */
    private suspend fun syncViaWebSocketOnly(content: String, contentType: String): Result<ClipboardItem> {
        return try {
            // Create a temporary ClipboardItem for WebSocket sync
            val clipboardItem = ClipboardItem(
                id = "temp-${System.currentTimeMillis()}", // Temporary ID
                content = content,
                contentType = contentType,
                deviceId = currentDeviceId!!,
                deviceName = android.os.Build.MODEL,
                createdAt = com.siw.clipboardsync.utils.TimeUtils.getCurrentIsoTimestamp(),
                hash = com.siw.clipboardsync.utils.DeviceUtils.generateContentHash(content),
                fileUrl = null,
                fileName = null,
                fileSize = null
            )
            
            // Send via WebSocket only
            webSocketClient.sendClipboardSync(clipboardItem)
            Log.d(TAG, "Sent clipboard sync via WebSocket only")
            
            _lastSyncedItem.value = clipboardItem
            _syncStatus.value = SyncStatus.CONNECTED
            
            Result.success(clipboardItem)
            
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket sync failed, falling back to HTTP", e)
            // Fallback to HTTP if WebSocket fails
            syncViaHttpOnly(content, contentType)
        }
    }
    
    /**
     * Sync via HTTP only (when WebSocket disconnected or failed)
     */
    private suspend fun syncViaHttpOnly(content: String, contentType: String): Result<ClipboardItem> {
        return try {
            // Use HTTP API only
            val result = clipboardRepository.syncClipboard(content, contentType, currentDeviceId!!)
            
            if (result.isSuccess) {
                val clipboardItem = result.getOrNull()!!
                Log.d(TAG, "Sent clipboard sync via HTTP only")
                
                _lastSyncedItem.value = clipboardItem
                _syncStatus.value = SyncStatus.CONNECTED
                
                Result.success(clipboardItem)
            } else {
                _syncStatus.value = SyncStatus.ERROR
                result
            }
            
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.ERROR
            Result.failure(e)
        }
    }
    
    /**
     * Check if sync is available
     */
    fun isSyncAvailable(): Boolean {
        return _syncStatus.value in listOf(SyncStatus.CONNECTED, SyncStatus.SYNCING)
    }
    
    /**
     * Reconnect WebSocket if needed
     */
    suspend fun reconnectIfNeeded() {
        Log.d(TAG, "Reconnect requested - current status: ${_syncStatus.value}, usable: ${isUsable()}")
        
        // If manager is not usable, reinitialize first
        if (!isUsable()) {
            Log.d(TAG, "Manager not usable, reinitializing...")
            initialize()
        }
        
        if (_syncStatus.value == SyncStatus.DISCONNECTED || _syncStatus.value == SyncStatus.ERROR) {
            val accessToken = getAccessToken()
            if (accessToken != null && currentDeviceId != null) {
                Log.d(TAG, "Starting WebSocket connection for reconnect")
                startWebSocketConnection(accessToken, currentDeviceId!!)
            } else {
                Log.w(TAG, "Cannot reconnect - missing token or device ID")
            }
        } else {
            Log.d(TAG, "Reconnect not needed - already connected or connecting")
        }
    }
    
    /**
     * Disconnect and cleanup
     */
    fun disconnect() {
        webSocketClient.disconnect()
        _syncStatus.value = SyncStatus.DISCONNECTED
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up ClipboardSyncManager")
        
        // Cancel jobs first to stop observers
        connectionJob?.cancel()
        connectionJob = null
        
        // Then disconnect WebSocket
        disconnect()
        
        // Clean up WebSocket client
        webSocketClient.cleanup()
        
        // Cancel scope last
        scope.cancel()
        
        // Reset state
        isInitialized = false
        
        Log.d(TAG, "ClipboardSyncManager cleanup complete")
    }
    
    /**
     * Soft disconnect - disconnect WebSocket but keep manager ready for reconnection
     */
    fun softDisconnect() {
        Log.d(TAG, "Soft disconnect - keeping manager initialized")
        webSocketClient.disconnect()
        _syncStatus.value = SyncStatus.DISCONNECTED
    }
    
    /**
     * Get access token from auth repository
     */
    private suspend fun getAccessToken(): String? {
        return try {
            authRepository.getAccessToken()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get access token", e)
            null
        }
    }
}