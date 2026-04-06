package com.siw.clipboardsync.websocket

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.siw.clipboardsync.data.model.ClipboardItem
import com.siw.clipboardsync.data.model.ClipboardUpdateMessage
import com.siw.clipboardsync.data.model.WebSocketMessage
import com.siw.clipboardsync.data.model.ClipboardSyncData
import com.siw.clipboardsync.data.model.DeviceStatusData
import com.siw.clipboardsync.data.model.ErrorData
import com.siw.clipboardsync.data.model.SuccessData
import com.siw.clipboardsync.utils.TimeUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSocketClient @Inject constructor(
    private val gson: Gson
) {
    
    companion object {
        private const val TAG = "WebSocketClient"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val HEARTBEAT_INTERVAL_MS = 25000L // Reduced to stay within typical NAT timeout
        private const val MAX_RECONNECT_ATTEMPTS = Int.MAX_VALUE // Keep trying to reconnect
        private const val ENABLE_HEARTBEAT = true // Enable heartbeat to keep connection alive
    }
    
    private var webSocketClient: WebSocketClient? = null
    private var accessToken: String? = null
    private var userId: String? = null
    private var deviceId: String? = null
    private var isConnecting = false
    private var reconnectAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    
    // 新增：获取最新token的提供器
    private var tokenProvider: (suspend () -> String?)? = null
    
    // Message flows - with replay buffer to ensure messages aren't lost
    private val _clipboardUpdates = MutableSharedFlow<ClipboardItem>(
        replay = 1, // Keep the last message for new collectors
        extraBufferCapacity = 10 // Allow buffering of multiple messages
    )
    val clipboardUpdates: SharedFlow<ClipboardItem> = _clipboardUpdates.asSharedFlow()
    
    private val _connectionStatus = MutableSharedFlow<ConnectionStatus>(
        replay = 1, // Keep the last status for new collectors
        extraBufferCapacity = 5 // Allow buffering of status changes
    )
    val connectionStatus: SharedFlow<ConnectionStatus> = _connectionStatus.asSharedFlow()
    
    init {
        // Set initial status
        _connectionStatus.tryEmit(ConnectionStatus.DISCONNECTED)
    }
    
    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()
    
    enum class ConnectionStatus {
        CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING, FAILED
    }
    
    // 提供设置tokenProvider的方法
    fun setTokenProvider(provider: suspend () -> String?) {
        this.tokenProvider = provider
    }
    
    /**
     * Connect to WebSocket server
     */
    fun connect(accessToken: String, userId: String, deviceId: String, baseUrl: String = "wss://clip.blueer.de") {
        if (isConnecting || isConnected()) {
            Log.d(TAG, "Already connecting or connected")
            return
        }
        
        this.accessToken = accessToken
        this.userId = userId
        this.deviceId = deviceId
        
        try {
            isConnecting = true
            Log.d(TAG, "Emitting CONNECTING status")
            val connectingEmitResult = _connectionStatus.tryEmit(ConnectionStatus.CONNECTING)
            Log.d(TAG, "CONNECTING status emit result: $connectingEmitResult")
            
            val effectiveToken = accessToken
            val wsUrl = "$baseUrl/ws/sync?token=$effectiveToken"
            Log.d(TAG, "Connecting to WebSocket: $wsUrl")
            val uri = URI.create(wsUrl)
            
            webSocketClient = object : org.java_websocket.client.WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake?) {
                    Log.d(TAG, "WebSocket connected successfully")
                    Log.d(TAG, "Server handshake: ${handshake?.httpStatus} ${handshake?.httpStatusMessage}")
                    isConnecting = false
                    reconnectAttempts = 0
                    Log.d(TAG, "Emitting CONNECTED status")
                    val emitResult = _connectionStatus.tryEmit(ConnectionStatus.CONNECTED)
                    Log.d(TAG, "CONNECTED status emit result: $emitResult")
                    
                    // Wait a bit before starting ping to let connection stabilize
                    if (ENABLE_HEARTBEAT) {
                        scope.launch {
                            delay(2000)
                            if (isConnected()) {
                                startPingTimer()
                            }
                        }
                    } else {
                        Log.d(TAG, "Ping disabled for testing - connection should stay open")
                    }
                    
                    // Send initial device status
                    scope.launch {
                        delay(1000) // Wait a bit for connection to stabilize
                        if (isConnected()) {
                            sendDeviceStatus()
                        }
                    }
                }
                
                override fun onMessage(message: String?) {
                    Log.d(TAG, "Received message: $message")
                    message?.let { handleMessage(it) }
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "WebSocket closed: code=$code, reason='$reason', remote=$remote")
                    isConnecting = false
                    stopPingTimer() // Stop ping when connection closes
                    _connectionStatus.tryEmit(ConnectionStatus.DISCONNECTED)
                    
                    // Handle different close codes
                    when (code) {
                        1000 -> Log.d(TAG, "Normal closure")
                        1001 -> Log.d(TAG, "Going away")
                        1006 -> Log.w(TAG, "Abnormal closure - possible auth or network issue")
                        4001 -> Log.e(TAG, "Authentication failed")
                        else -> Log.w(TAG, "Unexpected close code: $code")
                    }
                    
                    // Auto-reconnect if not intentionally closed and not auth failure
                    if (remote && code != 4001 && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        scheduleReconnect()
                    }
                }
                
                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket error", ex)
                    isConnecting = false
                    _connectionStatus.tryEmit(ConnectionStatus.FAILED)
                    _errors.tryEmit(ex?.message ?: "WebSocket connection error")
                    
                    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        scheduleReconnect()
                    }
                }
            }
            
            webSocketClient?.connect()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection", e)
            isConnecting = false
            _connectionStatus.tryEmit(ConnectionStatus.FAILED)
            _errors.tryEmit("Failed to connect: ${e.message}")
        }
    }
    
    /**
     * Disconnect from WebSocket server
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting WebSocket")
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS // Prevent auto-reconnect
        stopPingTimer()
        webSocketClient?.close()
        webSocketClient = null
        _connectionStatus.tryEmit(ConnectionStatus.DISCONNECTED)
    }
    
    /**
     * Check if WebSocket is connected
     */
    fun isConnected(): Boolean {
        return webSocketClient?.isOpen == true
    }
    
    /**
     * Send clipboard sync to server (updated to match API specification)
     */
    fun sendClipboardSync(clipboardItem: ClipboardItem) {
        if (!isConnected()) {
            Log.w(TAG, "WebSocket not connected, cannot send clipboard sync")
            return
        }
        
        try {
            val syncData = ClipboardSyncData(
                contentType = clipboardItem.contentType,
                content = clipboardItem.content,
                source = "websocket",
                fileName = clipboardItem.fileName,
                fileSize = clipboardItem.fileSize,
                fileUrl = clipboardItem.fileUrl
            )
            
            val wsMessage = WebSocketMessage(
                type = "clipboard_sync",
                userId = userId,
                deviceId = deviceId,
                data = syncData,
                timestamp = TimeUtils.getCurrentIsoTimestamp()
            )
            
            val jsonMessage = gson.toJson(wsMessage)
            webSocketClient?.send(jsonMessage)
            
            Log.d(TAG, "Sent clipboard sync via WebSocket: ${clipboardItem.content}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send clipboard sync", e)
            _errors.tryEmit("Failed to send sync: ${e.message}")
        }
    }
    
    /**
     * Send clipboard update to server (legacy method for backward compatibility)
     */
    @Deprecated("Use sendClipboardSync instead")
    fun sendClipboardUpdate(clipboardItem: ClipboardItem) {
        sendClipboardSync(clipboardItem)
    }
    
    /**
     * Send ping to keep connection alive (updated to match API specification)
     */
    private fun sendPing() {
        if (!isConnected()) return
        
        try {
            val ping = WebSocketMessage(
                type = "ping",
                userId = null, // API shows null for ping messages
                deviceId = deviceId,
                data = null,
                timestamp = TimeUtils.getCurrentIsoTimestamp()
            )
            
            val jsonMessage = gson.toJson(ping)
            Log.d(TAG, "Sending ping: $jsonMessage")
            webSocketClient?.send(jsonMessage)
            
            Log.d(TAG, "Sent ping successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ping", e)
        }
    }
    
    /**
     * Send device status update
     */
    fun sendDeviceStatus(isOnline: Boolean = true, osVersion: String? = null, appVersion: String? = null) {
        if (!isConnected()) return
        
        try {
            val statusData = DeviceStatusData(
                isOnline = isOnline,
                lastActive = TimeUtils.getCurrentIsoTimestamp(),
                osVersion = osVersion ?: android.os.Build.VERSION.RELEASE,
                appVersion = appVersion ?: "1.0.0"
            )
            
            val statusMessage = WebSocketMessage(
                type = "device_status",
                userId = userId,
                deviceId = deviceId,
                data = statusData,
                timestamp = TimeUtils.getCurrentIsoTimestamp()
            )
            
            val jsonMessage = gson.toJson(statusMessage)
            webSocketClient?.send(jsonMessage)
            
            Log.d(TAG, "Sent device status update")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send device status", e)
        }
    }
    
    /**
     * Handle incoming WebSocket messages
     */
    private fun handleMessage(message: String) {
        try {
            Log.d(TAG, "Received WebSocket message: $message")
            
            val wsMessage = gson.fromJson(message, WebSocketMessage::class.java)
            
            when (wsMessage.type) {
                "clipboard_sync" -> {
                    handleClipboardSync(wsMessage)
                }
                "device_status" -> {
                    handleDeviceStatus(wsMessage)
                }
                "ping" -> {
                    Log.d(TAG, "Received ping from server")
                    // Server shouldn't send ping, but handle gracefully
                }
                "pong" -> {
                    Log.d(TAG, "Received pong response from server")
                    // Connection is alive, no action needed
                }
                "error" -> {
                    handleError(wsMessage)
                }
                "success" -> {
                    handleSuccess(wsMessage)
                }
                // Legacy support
                "clipboard_update" -> {
                    Log.w(TAG, "Received legacy clipboard_update message, treating as clipboard_sync")
                    handleClipboardUpdate(wsMessage)
                }
                "heartbeat" -> {
                    Log.w(TAG, "Received legacy heartbeat message, treating as pong")
                }
                else -> {
                    Log.w(TAG, "Unknown message type: ${wsMessage.type}")
                }
            }
            
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Failed to parse WebSocket message", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling WebSocket message", e)
        }
    }
    
    /**
     * Handle clipboard update from server
     */
    private fun handleClipboardUpdate(wsMessage: WebSocketMessage) {
        try {
            val updateJson = gson.toJsonTree(wsMessage.data)
            val updateMessage = gson.fromJson(updateJson, ClipboardUpdateMessage::class.java)
            
            // Don't process updates from our own device
            if (updateMessage.clipboardItem.deviceId == deviceId) {
                Log.d(TAG, "Ignoring clipboard update from own device")
                return
            }
            
            Log.d(TAG, "Received clipboard update from device: ${updateMessage.clipboardItem.deviceName}")
            _clipboardUpdates.tryEmit(updateMessage.clipboardItem)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle clipboard update", e)
        }
    }
    
    /**
     * Handle clipboard sync message from server (updated to match API specification)
     */
    private fun handleClipboardSync(wsMessage: WebSocketMessage) {
        try {
            // Don't process updates from our own device
            if (wsMessage.deviceId == deviceId) {
                Log.d(TAG, "Ignoring clipboard sync from own device")
                return
            }
            
            // Parse the clipboard sync data
            val dataJson = gson.toJsonTree(wsMessage.data)
            val syncData = gson.fromJson(dataJson, ClipboardSyncData::class.java)
            
            // Create a ClipboardItem from the sync message
            val clipboardItem = ClipboardItem(
                id = syncData.itemId ?: "sync-${System.currentTimeMillis()}", // Use item_id if available
                content = syncData.content,
                contentType = syncData.contentType,
                fileUrl = syncData.fileUrl,
                fileName = syncData.fileName,
                fileSize = syncData.fileSize,
                deviceId = wsMessage.deviceId ?: "unknown",
                deviceName = "Remote Device", // We don't have device name in sync message
                createdAt = wsMessage.timestamp ?: TimeUtils.getCurrentIsoTimestamp(),
                hash = syncData.checksum ?: ""
            )
            
            Log.d(TAG, "Received clipboard sync: '${syncData.content}' (${syncData.contentType}) from device: ${wsMessage.deviceId}")
            Log.d(TAG, "Emitting clipboard item to flow: $clipboardItem")
            val emitResult = _clipboardUpdates.tryEmit(clipboardItem)
            Log.d(TAG, "Flow emit result: $emitResult")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle clipboard sync", e)
        }
    }
    
    /**
     * Handle device status updates
     */
    private fun handleDeviceStatus(wsMessage: WebSocketMessage) {
        try {
            Log.d(TAG, "Received device status update from device ${wsMessage.deviceId}: ${wsMessage.data}")
            
            val dataJson = gson.toJsonTree(wsMessage.data)
            val statusData = gson.fromJson(dataJson, DeviceStatusData::class.java)
            
            Log.d(TAG, "Device ${wsMessage.deviceId} is ${if (statusData.isOnline) "online" else "offline"}")
            // Handle device online/offline status if needed for UI updates
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle device status", e)
        }
    }
    
    /**
     * Handle error messages from server
     */
    private fun handleError(wsMessage: WebSocketMessage) {
        try {
            val dataJson = gson.toJsonTree(wsMessage.data)
            val errorData = gson.fromJson(dataJson, ErrorData::class.java)
            
            Log.e(TAG, "Received error from server: ${errorData.message} (${errorData.code})")
            if (errorData.detail != null) {
                Log.e(TAG, "Error detail: ${errorData.detail}")
            }
            
            _errors.tryEmit("Server error: ${errorData.message}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle error message", e)
            _errors.tryEmit("Unknown server error")
        }
    }
    
    /**
     * Handle success messages from server
     */
    private fun handleSuccess(wsMessage: WebSocketMessage) {
        try {
            val dataJson = gson.toJsonTree(wsMessage.data)
            val successData = gson.fromJson(dataJson, SuccessData::class.java)
            
            Log.d(TAG, "Received success from server: ${successData.message} (${successData.code})")
            
            // Handle specific success codes if needed
            when (successData.code) {
                "clipboard_saved" -> {
                    Log.d(TAG, "Clipboard content saved successfully")
                }
                else -> {
                    Log.d(TAG, "Operation completed successfully: ${successData.code}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle success message", e)
        }
    }
    
    /**
     * Schedule reconnection attempt with exponential backoff
     */
    private fun scheduleReconnect() {
        reconnectAttempts++
        _connectionStatus.tryEmit(ConnectionStatus.RECONNECTING)
        
        // Exponential backoff: 5s, 10s, 20s, 40s, max 60s
        val backoffDelay = minOf(RECONNECT_DELAY_MS * (1L shl minOf(reconnectAttempts - 1, 4)), 60000L)
        
        Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempts in ${backoffDelay}ms")
        
        scope.launch {
            delay(backoffDelay)
            
            // 在重连前尝试获取最新token
            val latestToken = try {
                tokenProvider?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get latest token for reconnect", e)
                null
            }
            if (latestToken != null) {
                accessToken = latestToken
            }
            
            if (accessToken != null && userId != null && deviceId != null) {
                connect(accessToken!!, userId!!, deviceId!!)
            } else {
                Log.w(TAG, "Cannot reconnect - missing credentials, will retry later")
                // Schedule another attempt even if credentials are missing
                scheduleReconnect()
            }
        }
    }
    
    /**
     * Start ping timer
     */
    private fun startPingTimer() {
        // Cancel any existing ping job
        stopPingTimer()
        
        heartbeatJob = scope.launch {
            Log.d(TAG, "Starting ping timer with ${HEARTBEAT_INTERVAL_MS}ms interval")
            while (isConnected()) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (isConnected()) {
                    sendPing()
                }
            }
            Log.d(TAG, "Ping timer stopped")
        }
    }
    
    /**
     * Stop ping timer
     */
    private fun stopPingTimer() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}