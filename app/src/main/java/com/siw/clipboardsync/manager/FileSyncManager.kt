package com.siw.clipboardsync.manager

import android.content.Context
import android.net.Uri
import android.util.Log
import com.siw.clipboardsync.data.model.ClipboardItem
import com.siw.clipboardsync.data.model.FileTransferState
import com.siw.clipboardsync.data.model.FileUploadResponse
import com.siw.clipboardsync.data.model.FileValidationResult
import com.siw.clipboardsync.data.repository.FileRepository
import com.siw.clipboardsync.utils.ChecksumUtils
import com.siw.clipboardsync.utils.ClipboardUtils
import com.siw.clipboardsync.utils.FileValidator
import com.siw.clipboardsync.utils.MimeTypeMapping
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件同步管理器
 * 负责文件的验证、上传、下载和同步
 */
@Singleton
class FileSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemConfigManager: SystemConfigManager,
    private val fileRepository: FileRepository,
    private val fileValidator: FileValidator
) {
    companion object {
        private const val TAG = "FileSyncManager"
    }
    
    // 文件传输状态
    private val _transferState = MutableStateFlow<FileTransferState>(FileTransferState.Idle)
    val transferState: StateFlow<FileTransferState> = _transferState.asStateFlow()
    
    /**
     * 上传文件并同步到剪贴板
     * @param uri 文件URI
     * @return 上传结果
     */
    suspend fun uploadAndSyncFile(uri: Uri): Result<ClipboardItem> = withContext(Dispatchers.IO) {
        try {
            // 获取文件信息
            val fileInfo = fileValidator.getFileInfo(uri)
            if (fileInfo == null) {
                _transferState.value = FileTransferState.Error("unknown", "无法读取文件")
                return@withContext Result.failure(Exception("Cannot read file"))
            }
            
            val (fileName, fileSize, mimeType) = fileInfo
            Log.d(TAG, "Uploading file: $fileName, size: $fileSize, type: $mimeType")
            
            // 验证文件
            _transferState.value = FileTransferState.Validating(fileName)
            val validationResult = fileValidator.validateFile(uri)
            
            when (validationResult) {
                is FileValidationResult.Valid -> {
                    Log.d(TAG, "File validation passed")
                }
                is FileValidationResult.FileTooLarge -> {
                    val error = "文件过大: ${formatFileSize(validationResult.actualSize)} > ${formatFileSize(validationResult.maxSize)}"
                    Log.w(TAG, error)
                    _transferState.value = FileTransferState.Error(fileName, error)
                    return@withContext Result.failure(Exception(error))
                }
                is FileValidationResult.TypeNotAllowed -> {
                    val error = "不支持的文件类型: ${validationResult.mimeType}"
                    Log.w(TAG, error)
                    _transferState.value = FileTransferState.Error(fileName, error)
                    return@withContext Result.failure(Exception(error))
                }
                is FileValidationResult.FileNotFound -> {
                    val error = "文件不存在"
                    Log.w(TAG, error)
                    _transferState.value = FileTransferState.Error(fileName, error)
                    return@withContext Result.failure(Exception(error))
                }
                is FileValidationResult.ReadError -> {
                    val error = "读取文件失败: ${validationResult.error}"
                    Log.w(TAG, error)
                    _transferState.value = FileTransferState.Error(fileName, error)
                    return@withContext Result.failure(Exception(error))
                }
            }
            
            // 计算校验和
            val checksum = ChecksumUtils.calculateChecksum(context, uri)
            if (checksum == null) {
                _transferState.value = FileTransferState.Error(fileName, "计算校验和失败")
                return@withContext Result.failure(Exception("Failed to calculate checksum"))
            }
            
            // 上传文件
            _transferState.value = FileTransferState.Uploading(fileName, 0f)
            val uploadResult = fileRepository.uploadFile(uri) { progress ->
                _transferState.value = FileTransferState.Uploading(fileName, progress)
            }
            
            if (uploadResult.isFailure) {
                val error = uploadResult.exceptionOrNull()?.message ?: "上传失败"
                _transferState.value = FileTransferState.Error(fileName, error)
                return@withContext Result.failure(uploadResult.exceptionOrNull() ?: Exception(error))
            }
            
            val uploadResponse = uploadResult.getOrNull()!!
            Log.d(TAG, "File uploaded: ${uploadResponse.fileUrl}")
            
            // 同步到剪贴板
            val syncResult = fileRepository.syncFileToClipboard(
                fileName = fileName,
                fileSize = fileSize,
                fileUrl = uploadResponse.fileUrl,
                mimeType = mimeType,
                checksum = checksum
            )
            
            if (syncResult.isSuccess) {
                _transferState.value = FileTransferState.Success(fileName)
                Log.d(TAG, "File synced to clipboard successfully")
            } else {
                val error = syncResult.exceptionOrNull()?.message ?: "同步失败"
                _transferState.value = FileTransferState.Error(fileName, error)
            }
            
            syncResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading and syncing file", e)
            _transferState.value = FileTransferState.Error("unknown", e.message ?: "未知错误")
            Result.failure(e)
        }
    }
    
    /**
     * 下载文件
     * @param clipboardItem 包含文件信息的剪贴板项
     * @return 下载后的文件URI
     */
    suspend fun downloadFile(clipboardItem: ClipboardItem): Result<Uri> = withContext(Dispatchers.IO) {
        val fileName = clipboardItem.fileName ?: "downloaded_file"
        val downloadUrl = resolveDownloadUrl(clipboardItem)
        
        if (downloadUrl == null) {
            _transferState.value = FileTransferState.Error(fileName, "文件下载地址为空")
            return@withContext Result.failure(Exception("Download URL is empty"))
        }
        
        try {
            Log.d(TAG, "Downloading file: $fileName from $downloadUrl")
            
            // 检查文件大小是否在限制内
            val fileSize = clipboardItem.fileSize ?: 0L
            val maxFileSize = systemConfigManager.getMaxFileSize()
            
            if (fileSize > maxFileSize) {
                val error = "文件过大: ${formatFileSize(fileSize)} > ${formatFileSize(maxFileSize)}"
                Log.w(TAG, error)
                _transferState.value = FileTransferState.Error(fileName, error)
                return@withContext Result.failure(Exception(error))
            }
            
            // 下载文件
            _transferState.value = FileTransferState.Downloading(fileName, 0f)
            
            val checksum = clipboardItem.checksum
            val downloadResult = if (!checksum.isNullOrEmpty()) {
                fileRepository.downloadFileWithChecksum(downloadUrl, fileName, checksum) { progress ->
                    _transferState.value = FileTransferState.Downloading(fileName, progress)
                }
            } else {
                fileRepository.downloadFile(downloadUrl, fileName) { progress ->
                    _transferState.value = FileTransferState.Downloading(fileName, progress)
                }
            }
            
            if (downloadResult.isSuccess) {
                _transferState.value = FileTransferState.Success(fileName)
                Log.d(TAG, "File downloaded successfully: ${downloadResult.getOrNull()}")
            } else {
                val error = downloadResult.exceptionOrNull()?.message ?: "下载失败"
                _transferState.value = FileTransferState.Error(fileName, error)
            }
            
            downloadResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading file", e)
            _transferState.value = FileTransferState.Error(fileName, e.message ?: "未知错误")
            Result.failure(e)
        }
    }

    private fun resolveDownloadUrl(clipboardItem: ClipboardItem): String? {
        val rawFileUrl = clipboardItem.fileUrl?.trim().orEmpty()

        // Presigned URLs (complete https URLs with auth params) can be used directly
        if (rawFileUrl.startsWith("https://")) {
            Log.d(TAG, "Using presigned URL directly: ${rawFileUrl.take(80)}...")
            return rawFileUrl
        }

        // For non-presigned URLs (object keys, relative paths), use backend proxy
        // Backend proxy handles authentication and avoids direct R2 access
        return clipboardItem.id.takeIf { it.isNotBlank() }?.let { id ->
            Log.d(TAG, "Using backend proxy for download: api/v1/files/$id")
            "api/v1/files/$id"
        }
    }
    
    /**
     * 处理接收到的文件同步消息
     * 根据配置决定是否自动下载
     */
    suspend fun handleReceivedFileSync(clipboardItem: ClipboardItem): Result<Uri?> {
        val fileName = clipboardItem.fileName ?: "unknown"
        val fileSize = clipboardItem.fileSize ?: 0L
        val mimeType = clipboardItem.mimeType ?: "application/octet-stream"
        
        Log.d(TAG, "Handling received file sync: $fileName, size: $fileSize, type: $mimeType")
        
        // 检查文件是否符合配置限制
        val maxFileSize = systemConfigManager.getMaxFileSize()
        val allowedTypes = systemConfigManager.getAllowedFileTypes()
        
        val sizeAllowed = fileSize <= maxFileSize
        val typeAllowed = allowedTypes.isEmpty() || 
                          allowedTypes.contains(mimeType) ||
                          allowedTypes.contains("${mimeType.substringBefore("/")}/*")
        
        return if (sizeAllowed && typeAllowed) {
            // 自动下载
            Log.d(TAG, "File meets limits, auto-downloading")
            downloadFile(clipboardItem).map { it }
        } else {
            // 不自动下载，仅记录
            if (!sizeAllowed) {
                Log.d(TAG, "File exceeds size limit, skipping auto-download")
            }
            if (!typeAllowed) {
                Log.d(TAG, "File type not allowed, skipping auto-download")
            }
            Result.success(null)
        }
    }
    
    /**
     * 验证文件
     */
    suspend fun validateFile(uri: Uri): FileValidationResult {
        return fileValidator.validateFile(uri)
    }
    
    /**
     * 检查文件是否应该自动同步
     */
    suspend fun shouldAutoSync(uri: Uri): Boolean {
        val validationResult = fileValidator.validateFile(uri)
        return validationResult is FileValidationResult.Valid
    }
    
    /**
     * 重置传输状态
     */
    fun resetTransferState() {
        _transferState.value = FileTransferState.Idle
    }
    
    /**
     * 格式化文件大小
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }
}
