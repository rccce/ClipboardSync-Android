package com.siw.clipboardsync.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.siw.clipboardsync.data.model.FileValidationResult
import com.siw.clipboardsync.manager.SystemConfigManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件验证器
 * 负责验证文件大小和类型是否符合系统配置
 */
@Singleton
class FileValidator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemConfigManager: SystemConfigManager
) {
    companion object {
        private const val TAG = "FileValidator"
    }
    
    /**
     * 验证文件是否符合系统配置
     * @param uri 文件URI
     * @return 验证结果
     */
    suspend fun validateFile(uri: Uri): FileValidationResult {
        Log.d(TAG, "Validating file: $uri")
        
        // 获取文件信息
        val fileInfo = getFileInfo(uri)
        if (fileInfo == null) {
            Log.e(TAG, "File not found or cannot be read: $uri")
            return FileValidationResult.FileNotFound(uri.toString())
        }
        
        val (fileName, fileSize, mimeType) = fileInfo
        Log.d(TAG, "File info: name=$fileName, size=$fileSize, mimeType=$mimeType")
        
        // 验证文件大小
        val maxFileSize = systemConfigManager.getMaxFileSize()
        if (fileSize > maxFileSize) {
            Log.w(TAG, "File too large: $fileSize > $maxFileSize")
            return FileValidationResult.FileTooLarge(maxFileSize, fileSize)
        }
        
        // 验证文件类型
        val allowedTypes = systemConfigManager.getAllowedFileTypes()
        if (!isTypeAllowed(mimeType, allowedTypes)) {
            Log.w(TAG, "File type not allowed: $mimeType, allowed: $allowedTypes")
            return FileValidationResult.TypeNotAllowed(mimeType, allowedTypes)
        }
        
        Log.d(TAG, "File validation passed")
        return FileValidationResult.Valid
    }
    
    /**
     * 同步验证文件（使用缓存的配置）
     */
    fun validateFileSync(uri: Uri): FileValidationResult {
        Log.d(TAG, "Sync validating file: $uri")
        
        val fileInfo = getFileInfo(uri)
        if (fileInfo == null) {
            return FileValidationResult.FileNotFound(uri.toString())
        }
        
        val (_, fileSize, mimeType) = fileInfo
        
        // 使用缓存的配置
        val maxFileSize = systemConfigManager.getCachedMaxFileSize()
        if (fileSize > maxFileSize) {
            return FileValidationResult.FileTooLarge(maxFileSize, fileSize)
        }
        
        val allowedTypes = systemConfigManager.getCachedAllowedFileTypes()
        if (!isTypeAllowed(mimeType, allowedTypes)) {
            return FileValidationResult.TypeNotAllowed(mimeType, allowedTypes)
        }
        
        return FileValidationResult.Valid
    }
    
    /**
     * 验证文件大小
     */
    fun validateFileSize(fileSize: Long, maxSize: Long): FileValidationResult {
        return if (fileSize > maxSize) {
            FileValidationResult.FileTooLarge(maxSize, fileSize)
        } else {
            FileValidationResult.Valid
        }
    }
    
    /**
     * 验证文件类型
     */
    fun validateFileType(mimeType: String, allowedTypes: List<String>): FileValidationResult {
        return if (!isTypeAllowed(mimeType, allowedTypes)) {
            FileValidationResult.TypeNotAllowed(mimeType, allowedTypes)
        } else {
            FileValidationResult.Valid
        }
    }
    
    /**
     * 获取文件信息
     * @return Triple(fileName, fileSize, mimeType) 或 null
     */
    fun getFileInfo(uri: Uri): Triple<String, Long, String>? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    
                    val fileName = if (nameIndex >= 0) cursor.getString(nameIndex) else "unknown"
                    val fileSize = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    
                    // 获取MIME类型
                    val mimeType = context.contentResolver.getType(uri) 
                        ?: MimeTypeMapping.getMimeTypeFromFileName(fileName)
                    
                    Triple(fileName, fileSize, mimeType)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file info", e)
            null
        }
    }
    
    /**
     * 检查MIME类型是否在允许列表中
     */
    private fun isTypeAllowed(mimeType: String, allowedTypes: List<String>): Boolean {
        if (allowedTypes.isEmpty()) return true // 空列表表示允许所有类型
        
        // 精确匹配
        if (allowedTypes.contains(mimeType)) return true
        
        // 通配符匹配 (如 "image/*")
        val category = mimeType.substringBefore("/")
        if (allowedTypes.contains("$category/*")) return true
        
        return false
    }
}
