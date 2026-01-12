package com.siw.clipboardsync.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.siw.clipboardsync.data.model.ClipboardItem
import com.siw.clipboardsync.data.model.FileUploadResponse
import com.siw.clipboardsync.data.model.FileSyncRequest
import com.siw.clipboardsync.data.network.ApiService
import com.siw.clipboardsync.utils.ApiErrorParser
import com.siw.clipboardsync.utils.ChecksumUtils
import com.siw.clipboardsync.utils.MimeTypeMapping
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件数据仓库
 * 负责文件上传、下载和同步操作
 */
@Singleton
class FileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService
) {
    companion object {
        private const val TAG = "FileRepository"
        private const val MAX_RETRY_COUNT = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
    }
    
    /**
     * 上传文件
     * @param uri 文件URI
     * @param onProgress 进度回调 (0.0 - 1.0)
     * @return 上传结果
     */
    suspend fun uploadFile(
        uri: Uri,
        onProgress: ((Float) -> Unit)? = null
    ): Result<FileUploadResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting file upload: $uri")
            
            // 读取文件信息
            val fileInfo = getFileInfo(uri)
            if (fileInfo == null) {
                return@withContext Result.failure(Exception("Cannot read file: $uri"))
            }
            
            val (fileName, fileSize, mimeType) = fileInfo
            Log.d(TAG, "File info: name=$fileName, size=$fileSize, mimeType=$mimeType")
            
            // 读取文件内容
            val fileData = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (fileData == null) {
                return@withContext Result.failure(Exception("Cannot read file content"))
            }
            
            onProgress?.invoke(0.3f) // 读取完成
            
            // 创建MultipartBody
            val requestBody = fileData.toRequestBody(mimeType.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", fileName, requestBody)
            
            onProgress?.invoke(0.5f) // 准备上传
            
            // 上传文件
            val response = apiService.uploadFile(filePart)
            
            onProgress?.invoke(0.9f) // 上传完成
            
            if (response.isSuccessful) {
                val apiResponse = response.body()
                if (apiResponse != null && apiResponse.success && apiResponse.data != null) {
                    val data = apiResponse.data
                    Log.d(TAG, "File uploaded successfully: ${data.fileUrl}")
                    onProgress?.invoke(1.0f)
                    // 转换为 FileUploadResponse
                    val uploadResponse = FileUploadResponse(
                        fileUrl = data.fileUrl,
                        fileName = data.fileName,
                        fileSize = data.fileSize,
                        userId = data.userId
                    )
                    Result.success(uploadResponse)
                } else {
                    Log.e(TAG, "Upload response invalid: success=${apiResponse?.success}, data=${apiResponse?.data}")
                    Result.failure(Exception(apiResponse?.error ?: "上传响应无效"))
                }
            } else {
                val errorMessage = ApiErrorParser.parseError(response, "文件上传失败")
                Log.e(TAG, "Upload failed: ${response.code()} - $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file", e)
            Result.failure(e)
        }
    }
    
    /**
     * 下载文件
     * @param fileUrl 文件URL
     * @param fileName 保存的文件名
     * @param onProgress 进度回调 (0.0 - 1.0)
     * @return 下载后的文件URI
     */
    suspend fun downloadFile(
        fileUrl: String,
        fileName: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        var retryDelay = INITIAL_RETRY_DELAY_MS
        
        repeat(MAX_RETRY_COUNT) { attempt ->
            try {
                Log.d(TAG, "Downloading file (attempt ${attempt + 1}): $fileUrl")
                
                val response = apiService.downloadFile(fileUrl)
                
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        val contentLength = body.contentLength()
                        val inputStream = body.byteStream()
                        
                        // 保存到Downloads目录
                        val uri = saveToDownloads(fileName, inputStream, contentLength, onProgress)
                        
                        if (uri != null) {
                            Log.d(TAG, "File downloaded successfully: $uri")
                            return@withContext Result.success(uri)
                        } else {
                            lastException = Exception("Failed to save file")
                        }
                    } else {
                        lastException = Exception("Empty response body")
                    }
                } else {
                    lastException = Exception(ApiErrorParser.parseError(response, "文件下载失败"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download attempt ${attempt + 1} failed", e)
                lastException = e
            }
            
            // 指数退避
            if (attempt < MAX_RETRY_COUNT - 1) {
                kotlinx.coroutines.delay(retryDelay)
                retryDelay *= 2
            }
        }
        
        Result.failure(lastException ?: Exception("Download failed after $MAX_RETRY_COUNT attempts"))
    }
    
    /**
     * 下载文件并验证校验和
     */
    suspend fun downloadFileWithChecksum(
        fileUrl: String,
        fileName: String,
        expectedChecksum: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val downloadResult = downloadFile(fileUrl, fileName, onProgress)
        
        if (downloadResult.isFailure) {
            return@withContext downloadResult
        }
        
        val uri = downloadResult.getOrNull()!!
        
        // 验证校验和
        if (!ChecksumUtils.verifyChecksum(context, uri, expectedChecksum)) {
            // 删除损坏的文件
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete corrupted file", e)
            }
            return@withContext Result.failure(Exception("Checksum verification failed"))
        }
        
        Result.success(uri)
    }
    
    /**
     * 同步文件到剪贴板
     */
    suspend fun syncFileToClipboard(
        fileName: String,
        fileSize: Long,
        fileUrl: String,
        mimeType: String,
        checksum: String
    ): Result<ClipboardItem> = withContext(Dispatchers.IO) {
        try {
            val request = FileSyncRequest(
                contentType = "file",
                content = fileName,
                fileName = fileName,
                fileSize = fileSize,
                fileUrl = fileUrl,
                mimeType = mimeType,
                checksum = checksum
            )
            
            val response = apiService.syncFileClipboard(request)
            
            if (response.isSuccessful) {
                val syncResponse = response.body()
                if (syncResponse?.success == true && syncResponse.data != null) {
                    Log.d(TAG, "File synced to clipboard: ${syncResponse.data.id}")
                    Result.success(syncResponse.data)
                } else {
                    Result.failure(Exception(syncResponse?.error ?: syncResponse?.message ?: "同步失败"))
                }
            } else {
                val errorMessage = ApiErrorParser.parseError(response, "文件同步失败")
                Log.e(TAG, "Sync failed: ${response.code()} - $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing file to clipboard", e)
            Result.failure(e)
        }
    }
    
    /**
     * 保存文件到Downloads目录
     */
    private fun saveToDownloads(
        fileName: String,
        inputStream: java.io.InputStream,
        contentLength: Long,
        onProgress: ((Float) -> Unit)?
    ): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用MediaStore
                saveToDownloadsMediaStore(fileName, inputStream, contentLength, onProgress)
            } else {
                // Android 9及以下使用传统方式
                saveToDownloadsLegacy(fileName, inputStream, contentLength, onProgress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file to downloads", e)
            null
        }
    }
    
    private fun saveToDownloadsMediaStore(
        fileName: String,
        inputStream: java.io.InputStream,
        contentLength: Long,
        onProgress: ((Float) -> Unit)?
    ): Uri? {
        val mimeType = MimeTypeMapping.getMimeTypeFromFileName(fileName)
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null
        
        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    
                    if (contentLength > 0) {
                        val progress = totalBytesRead.toFloat() / contentLength
                        onProgress?.invoke(progress.coerceIn(0f, 1f))
                    }
                }
            }
            
            // 标记文件为完成
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
    
    @Suppress("DEPRECATION")
    private fun saveToDownloadsLegacy(
        fileName: String,
        inputStream: java.io.InputStream,
        contentLength: Long,
        onProgress: ((Float) -> Unit)?
    ): Uri? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, getUniqueFileName(downloadsDir, fileName))
        
        FileOutputStream(file).use { outputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                
                if (contentLength > 0) {
                    val progress = totalBytesRead.toFloat() / contentLength
                    onProgress?.invoke(progress.coerceIn(0f, 1f))
                }
            }
        }
        
        return Uri.fromFile(file)
    }
    
    /**
     * 获取唯一文件名（处理重名）
     */
    private fun getUniqueFileName(directory: File, fileName: String): String {
        var file = File(directory, fileName)
        if (!file.exists()) return fileName
        
        val nameWithoutExtension = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        var counter = 1
        
        while (file.exists()) {
            val newName = if (extension.isNotEmpty()) {
                "${nameWithoutExtension}_$counter.$extension"
            } else {
                "${nameWithoutExtension}_$counter"
            }
            file = File(directory, newName)
            counter++
        }
        
        return file.name
    }
    
    /**
     * 获取文件信息
     */
    private fun getFileInfo(uri: Uri): Triple<String, Long, String>? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    
                    val fileName = if (nameIndex >= 0) cursor.getString(nameIndex) else "unknown"
                    val fileSize = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
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
}
