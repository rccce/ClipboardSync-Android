package com.siw.clipboardsync.data.model

import com.google.gson.annotations.SerializedName

data class ClipboardItem(
    @SerializedName("id")
    val id: String,
    @SerializedName("content")
    val content: String,
    @SerializedName("content_type")
    val contentType: String, // "text", "image", "file"
    @SerializedName("file_url")
    val fileUrl: String? = null,
    @SerializedName("file_name")
    val fileName: String? = null,
    @SerializedName("file_size")
    val fileSize: Long? = null,
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("hash")
    val hash: String
)

data class ClipboardSyncRequest(
    @SerializedName("content")
    val content: String,
    @SerializedName("content_type")
    val contentType: String,
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("hash")
    val hash: String
)

data class ClipboardSyncResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("data")
    val data: ClipboardItem?,
    @SerializedName("message")
    val message: String? = null
)

data class ClipboardHistoryResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("data")
    val data: List<ClipboardItem>?,
    @SerializedName("message")
    val message: String? = null
)

data class ClipboardLatestResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("data")
    val data: ClipboardItem?,
    @SerializedName("message")
    val message: String? = null
)

// WebSocket message types - Updated to match API specification
data class WebSocketMessage(
    @SerializedName("type")
    val type: String, // "clipboard_sync", "ping", "pong", "device_status", "error", "success"
    @SerializedName("user_id")
    val userId: String? = null,
    @SerializedName("device_id")
    val deviceId: String? = null,
    @SerializedName("data")
    val data: Any? = null,
    @SerializedName("timestamp")
    val timestamp: String? = null // ISO 8601 format: "2025-07-10T15:35:41+08:00"
)

// Clipboard sync data structure matching API specification
data class ClipboardSyncData(
    @SerializedName("content_type")
    val contentType: String, // "text", "image", "file"
    @SerializedName("content")
    val content: String,
    @SerializedName("source")
    val source: String = "websocket",
    @SerializedName("item_id")
    val itemId: String? = null, // Only present in server -> client messages
    @SerializedName("file_name")
    val fileName: String? = null,
    @SerializedName("file_size")
    val fileSize: Long? = null,
    @SerializedName("file_url")
    val fileUrl: String? = null,
    @SerializedName("mime_type")
    val mimeType: String? = null,
    @SerializedName("checksum")
    val checksum: String? = null
)

// Device status data structure
data class DeviceStatusData(
    @SerializedName("is_online")
    val isOnline: Boolean,
    @SerializedName("last_active")
    val lastActive: String,
    @SerializedName("os_version")
    val osVersion: String? = null,
    @SerializedName("app_version")
    val appVersion: String? = null
)

// Error message data structure
data class ErrorData(
    @SerializedName("code")
    val code: Int,
    @SerializedName("message")
    val message: String,
    @SerializedName("detail")
    val detail: String? = null
)

// Success message data structure
data class SuccessData(
    @SerializedName("code")
    val code: String,
    @SerializedName("message")
    val message: String,
    @SerializedName("data")
    val data: Any? = null
)

// Legacy support - keeping for backward compatibility during transition
@Deprecated("Use ClipboardSyncData instead")
data class ClipboardUpdateMessage(
    @SerializedName("clipboard_item")
    val clipboardItem: ClipboardItem,
    @SerializedName("action")
    val action: String = "sync"
)