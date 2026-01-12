package com.siw.clipboardsync.data.model

import com.google.gson.annotations.SerializedName

/**
 * 系统配置 API 响应包装
 */
data class SystemConfigApiResponse(
    @SerializedName("success")
    val success: Boolean = false,
    
    @SerializedName("data")
    val data: SystemConfigResponse? = null,
    
    @SerializedName("error")
    val error: String? = null
)

/**
 * 系统配置响应
 */
data class SystemConfigResponse(
    @SerializedName("site_name")
    val siteName: String = "ClipboardSync",
    
    @SerializedName("site_desc")
    val siteDesc: String = "",
    
    @SerializedName("admin_email")
    val adminEmail: String = "",
    
    @SerializedName("max_file_size")
    val maxFileSize: Long = 10 * 1024 * 1024, // 默认10MB (服务器配置优先)
    
    @SerializedName("file_retention_days")
    val fileRetentionDays: Int = 30,
    
    @SerializedName("allowed_file_types")
    val allowedFileTypes: List<String> = listOf(
        // 图片类型
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/svg+xml",
        // 文本类型
        "text/plain", "text/markdown", "text/html", "text/css", "text/csv", "text/xml",
        // 文档类型
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        // 代码/配置文件
        "application/json", "application/javascript", "application/x-yaml",
        // 压缩文件
        "application/zip", "application/x-rar-compressed", "application/x-7z-compressed", "application/gzip",
        // 音频类型
        "audio/mpeg", "audio/wav", "audio/ogg", "audio/flac", "audio/aac",
        // 视频类型
        "video/mp4", "video/webm", "video/x-msvideo", "video/quicktime",
        // 安装包
        "application/vnd.android.package-archive"
    ),
    
    @SerializedName("enable_registration")
    val enableRegistration: Boolean = true,
    
    @SerializedName("enable_two_factor")
    val enableTwoFactor: Boolean = false,
    
    @SerializedName("max_devices_per_user")
    val maxDevicesPerUser: Int = 10,
    
    @SerializedName("sync_history_retention")
    val syncHistoryRetention: Int = 100,
    
    @SerializedName("enable_email_notification")
    val enableEmailNotification: Boolean = false,
    
    @SerializedName("enable_webhook_notification")
    val enableWebhookNotification: Boolean = false
)

/**
 * 文件上传API响应包装
 */
data class FileUploadApiResponse(
    @SerializedName("success")
    val success: Boolean,
    
    @SerializedName("data")
    val data: FileUploadData?,
    
    @SerializedName("error")
    val error: String? = null
)

/**
 * 文件上传响应数据
 */
data class FileUploadData(
    @SerializedName("file_url")
    val fileUrl: String,
    
    @SerializedName("file_name")
    val fileName: String,
    
    @SerializedName("file_size")
    val fileSize: Long,
    
    @SerializedName("user_id")
    val userId: String? = null
)

/**
 * 文件上传响应 (兼容旧代码)
 */
data class FileUploadResponse(
    @SerializedName("file_url")
    val fileUrl: String,
    
    @SerializedName("file_name")
    val fileName: String,
    
    @SerializedName("file_size")
    val fileSize: Long,
    
    @SerializedName("user_id")
    val userId: String? = null
)

/**
 * 文件同步请求
 */
data class FileSyncRequest(
    @SerializedName("content_type")
    val contentType: String = "file",
    
    @SerializedName("content")
    val content: String, // 文件名
    
    @SerializedName("file_name")
    val fileName: String,
    
    @SerializedName("file_size")
    val fileSize: Long,
    
    @SerializedName("file_url")
    val fileUrl: String,
    
    @SerializedName("mime_type")
    val mimeType: String,
    
    @SerializedName("checksum")
    val checksum: String
)

/**
 * 文件验证结果
 */
sealed class FileValidationResult {
    object Valid : FileValidationResult()
    data class FileTooLarge(val maxSize: Long, val actualSize: Long) : FileValidationResult()
    data class TypeNotAllowed(val mimeType: String, val allowedTypes: List<String>) : FileValidationResult()
    data class FileNotFound(val uri: String) : FileValidationResult()
    data class ReadError(val error: String) : FileValidationResult()
}

/**
 * 文件传输状态
 */
sealed class FileTransferState {
    object Idle : FileTransferState()
    data class Validating(val fileName: String) : FileTransferState()
    data class Uploading(val fileName: String, val progress: Float) : FileTransferState()
    data class Downloading(val fileName: String, val progress: Float) : FileTransferState()
    data class Success(val fileName: String) : FileTransferState()
    data class Error(val fileName: String, val error: String) : FileTransferState()
}
