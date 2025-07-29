package com.siw.clipboardsync.monitor.processor

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets

/**
 * Processor for handling file and URI clipboard content.
 * Handles file URI resolution, access validation, and metadata extraction.
 */
class FileProcessor(private val context: Context) : ClipboardContentProcessor {
    
    override val supportedType: ClipboardContent.ContentType = ClipboardContent.ContentType.FILE
    override val maxSizeLimit: Long = 100 * 1024 * 1024 // 100MB for files
    override val priority: Int = ContentTypeDetector.getPriority(supportedType)
    
    companion object {
        private const val MAX_FILE_COUNT = 50 // Maximum number of files in a selection
        private const val MAX_PATH_LENGTH = 4096 // Maximum file path length
        
        // Supported URI schemes
        private val SUPPORTED_SCHEMES = setOf(
            "file", "content", "android.resource", "http", "https", "ftp"
        )
        
        // File extensions that should be treated as files
        private val FILE_EXTENSIONS = setOf(
            "txt", "doc", "docx", "pdf", "xls", "xlsx", "ppt", "pptx",
            "zip", "rar", "7z", "tar", "gz", "apk", "exe", "dmg",
            "mp3", "mp4", "avi", "mov", "wav", "flac", "mkv"
        )
    }
    
    override fun canProcess(content: ClipboardContent): Boolean {
        return content.type == ClipboardContent.ContentType.FILE ||
               content.type == ClipboardContent.ContentType.URI ||
               (content.type == ClipboardContent.ContentType.UNKNOWN && isFileContent(content))
    }
    
    override suspend fun process(content: ClipboardContent): ProcessingResult {
        return withContext(Dispatchers.IO) {
            try {
                val validationResult = validate(content)
                if (validationResult !is ValidationResult.Valid) {
                    return@withContext when (validationResult) {
                        is ValidationResult.SizeExceeded -> ProcessingResult.SizeExceeded(
                            validationResult.actualSize,
                            validationResult.maxSize,
                            content
                        )
                        is ValidationResult.Invalid -> ProcessingResult.Failure(
                            validationResult.error ?: ClipboardError.UnsupportedContentType(
                                content.type.name,
                                content.mimeType
                            ),
                            content
                        )
                        else -> ProcessingResult.Failure(
                            ClipboardError.UnknownError(Exception("Validation failed")),
                            content
                        )
                    }
                }
                
                val processedContent = processFileContent(content)
                val metadata = extractFileMetadata(processedContent)
                
                ProcessingResult.Success(processedContent, metadata)
                
            } catch (e: SecurityException) {
                ProcessingResult.Failure(
                    ClipboardError.PermissionDenied("File access permission required"),
                    content
                )
            } catch (e: FileNotFoundException) {
                ProcessingResult.Failure(
                    ClipboardError.UnknownError(e),
                    content
                )
            } catch (e: Exception) {
                ProcessingResult.Failure(
                    ClipboardError.UnknownError(e),
                    content
                )
            }
        }
    }
    
    override fun validate(content: ClipboardContent): ValidationResult {
        // Check size limits
        if (content.size > maxSizeLimit) {
            return ValidationResult.SizeExceeded(content.size, maxSizeLimit)
        }
        
        // Check if content represents valid file/URI data
        if (!isFileContent(content)) {
            return ValidationResult.Invalid(
                "Content is not a valid file or URI",
                ClipboardError.UnsupportedContentType(content.type.name, content.mimeType)
            )
        }
        
        // Parse and validate URIs
        try {
            val uriList = parseUriList(content)
            
            if (uriList.isEmpty()) {
                return ValidationResult.Invalid(
                    "No valid URIs found in content",
                    ClipboardError.UnsupportedContentType(content.type.name, content.mimeType)
                )
            }
            
            if (uriList.size > MAX_FILE_COUNT) {
                return ValidationResult.SizeExceeded(
                    uriList.size.toLong(),
                    MAX_FILE_COUNT.toLong()
                )
            }
            
            // Validate each URI
            for (uriString in uriList) {
                if (uriString.length > MAX_PATH_LENGTH) {
                    return ValidationResult.Invalid(
                        "File path too long: ${uriString.length} > $MAX_PATH_LENGTH"
                    )
                }
                
                val validationError = validateUri(uriString)
                if (validationError != null) {
                    return ValidationResult.Invalid(validationError)
                }
            }
            
        } catch (e: Exception) {
            return ValidationResult.Invalid(
                "Failed to parse URI content: ${e.message}",
                ClipboardError.UnknownError(e)
            )
        }
        
        return ValidationResult.Valid
    }
    
    override fun detectContentType(data: ByteArray, mimeType: String?): ClipboardContent.ContentType? {
        return when {
            mimeType == "text/uri-list" -> ClipboardContent.ContentType.URI
            mimeType?.startsWith("application/") == true -> ClipboardContent.ContentType.FILE
            isUriListData(data) -> ClipboardContent.ContentType.URI
            isFilePathData(data) -> ClipboardContent.ContentType.FILE
            else -> null
        }
    }
    
    private suspend fun processFileContent(content: ClipboardContent): ClipboardContent {
        return withContext(Dispatchers.IO) {
            val uriList = parseUriList(content)
            val processedUris = mutableListOf<ProcessedUri>()
            
            for (uriString in uriList) {
                try {
                    val processedUri = processUri(uriString)
                    processedUris.add(processedUri)
                } catch (e: Exception) {
                    // Log error but continue with other URIs
                    processedUris.add(ProcessedUri(
                        originalUri = uriString,
                        resolvedPath = null,
                        fileName = extractFileName(uriString),
                        fileSize = null,
                        mimeType = null,
                        isAccessible = false,
                        error = e.message
                    ))
                }
            }
            
            // Determine the most appropriate content type
            val detectedType = when {
                processedUris.all { it.isWebUrl() } -> ClipboardContent.ContentType.URI
                processedUris.any { it.isLocalFile() } -> ClipboardContent.ContentType.FILE
                else -> ClipboardContent.ContentType.URI
            }
            
            // Create processed data with URI information
            val processedData = createProcessedUriData(processedUris)
            
            content.copy(
                type = detectedType,
                data = processedData,
                size = processedData.size.toLong(),
                metadata = content.metadata + mapOf(
                    "processed" to "true",
                    "uri_count" to processedUris.size.toString(),
                    "accessible_count" to processedUris.count { it.isAccessible }.toString(),
                    "local_file_count" to processedUris.count { it.isLocalFile() }.toString(),
                    "web_url_count" to processedUris.count { it.isWebUrl() }.toString(),
                    "total_size" to processedUris.mapNotNull { it.fileSize }.sum().toString()
                )
            )
        }
    }
    
    private fun processUri(uriString: String): ProcessedUri {
        val uri = Uri.parse(uriString)
        
        return when (uri.scheme?.lowercase()) {
            "file" -> processFileUri(uri)
            "content" -> processContentUri(uri)
            "http", "https", "ftp" -> processWebUri(uri)
            else -> ProcessedUri(
                originalUri = uriString,
                resolvedPath = null,
                fileName = extractFileName(uriString),
                fileSize = null,
                mimeType = null,
                isAccessible = false,
                error = "Unsupported URI scheme: ${uri.scheme}"
            )
        }
    }
    
    private fun processFileUri(uri: Uri): ProcessedUri {
        return try {
            val file = File(uri.path ?: throw IllegalArgumentException("Invalid file path"))
            
            ProcessedUri(
                originalUri = uri.toString(),
                resolvedPath = file.absolutePath,
                fileName = file.name,
                fileSize = if (file.exists()) file.length() else null,
                mimeType = getMimeTypeFromExtension(file.extension),
                isAccessible = file.exists() && file.canRead(),
                error = if (!file.exists()) "File does not exist" else null
            )
        } catch (e: Exception) {
            ProcessedUri(
                originalUri = uri.toString(),
                resolvedPath = null,
                fileName = extractFileName(uri.toString()),
                fileSize = null,
                mimeType = null,
                isAccessible = false,
                error = e.message
            )
        }
    }
    
    private fun processContentUri(uri: Uri): ProcessedUri {
        return try {
            val fileName = getContentUriFileName(uri)
            val fileSize = getContentUriSize(uri)
            val mimeType = context.contentResolver.getType(uri)
            
            ProcessedUri(
                originalUri = uri.toString(),
                resolvedPath = null, // Content URIs don't have direct file paths
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
                isAccessible = isContentUriAccessible(uri),
                error = null
            )
        } catch (e: Exception) {
            ProcessedUri(
                originalUri = uri.toString(),
                resolvedPath = null,
                fileName = extractFileName(uri.toString()),
                fileSize = null,
                mimeType = null,
                isAccessible = false,
                error = e.message
            )
        }
    }
    
    private fun processWebUri(uri: Uri): ProcessedUri {
        val fileName = uri.lastPathSegment ?: "web_resource"
        
        return ProcessedUri(
            originalUri = uri.toString(),
            resolvedPath = null,
            fileName = fileName,
            fileSize = null,
            mimeType = null,
            isAccessible = true, // Assume web URIs are accessible
            error = null
        )
    }
    
    private fun parseUriList(content: ClipboardContent): List<String> {
        val dataString = String(content.data, StandardCharsets.UTF_8)
        
        return when {
            content.mimeType == "text/uri-list" -> {
                dataString.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
            }
            dataString.contains("\n") -> {
                dataString.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && isValidUri(it) }
            }
            else -> {
                if (isValidUri(dataString.trim())) {
                    listOf(dataString.trim())
                } else {
                    emptyList()
                }
            }
        }
    }
    
    private fun extractFileMetadata(content: ClipboardContent): Map<String, Any> {
        val metadata = mutableMapOf<String, Any>()
        
        try {
            val uriList = parseUriList(content)
            val processedUris = uriList.map { processUri(it) }
            
            metadata["total_files"] = processedUris.size
            metadata["accessible_files"] = processedUris.count { it.isAccessible }
            metadata["total_size_bytes"] = processedUris.mapNotNull { it.fileSize }.sum()
            metadata["file_types"] = processedUris.mapNotNull { it.mimeType }.distinct()
            metadata["has_local_files"] = processedUris.any { it.isLocalFile() }
            metadata["has_web_urls"] = processedUris.any { it.isWebUrl() }
            metadata["has_content_uris"] = processedUris.any { it.originalUri.startsWith("content://") }
            
            val extensions = processedUris.mapNotNull { uri ->
                uri.fileName?.substringAfterLast(".", "")?.takeIf { it.isNotEmpty() }
            }.distinct()
            metadata["file_extensions"] = extensions
            
        } catch (e: Exception) {
            metadata["error"] = e.message ?: "Unknown error"
        }
        
        return metadata
    }
    
    private fun isFileContent(content: ClipboardContent): Boolean {
        return when (content.type) {
            ClipboardContent.ContentType.FILE,
            ClipboardContent.ContentType.URI -> true
            ClipboardContent.ContentType.UNKNOWN -> {
                isUriListData(content.data) || isFilePathData(content.data)
            }
            else -> false
        }
    }
    
    private fun isUriListData(data: ByteArray): Boolean {
        val text = String(data, StandardCharsets.UTF_8)
        return text.lines().any { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#") && isValidUri(trimmed)
        }
    }
    
    private fun isFilePathData(data: ByteArray): Boolean {
        val text = String(data, StandardCharsets.UTF_8).trim()
        return isValidUri(text) || isValidFilePath(text)
    }
    
    private fun isValidUri(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            uri.scheme != null && uri.scheme in SUPPORTED_SCHEMES
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isValidFilePath(path: String): Boolean {
        return try {
            val file = File(path)
            file.isAbsolute || FILE_EXTENSIONS.any { ext ->
                path.lowercase().endsWith(".$ext")
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun validateUri(uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            when {
                uri.scheme == null -> "URI missing scheme"
                uri.scheme !in SUPPORTED_SCHEMES -> "Unsupported URI scheme: ${uri.scheme}"
                uri.scheme == "file" && uri.path.isNullOrEmpty() -> "File URI missing path"
                else -> null
            }
        } catch (e: Exception) {
            "Invalid URI format: ${e.message}"
        }
    }
    
    // Helper methods for content URI handling
    private fun getContentUriFileName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (e: Exception) {
            null
        } ?: extractFileName(uri.toString())
    }
    
    private fun getContentUriSize(uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getLong(sizeIndex)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun isContentUriAccessible(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    private fun extractFileName(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            uri.lastPathSegment ?: "unknown_file"
        } catch (e: Exception) {
            "unknown_file"
        }
    }
    
    private fun getMimeTypeFromExtension(extension: String): String? {
        return when (extension.lowercase()) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "zip" -> "application/zip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> null
        }
    }
    
    private fun createProcessedUriData(processedUris: List<ProcessedUri>): ByteArray {
        val result = processedUris.joinToString("\n") { it.originalUri }
        return result.toByteArray(StandardCharsets.UTF_8)
    }
    
    /**
     * Data class representing a processed URI with metadata.
     */
    private data class ProcessedUri(
        val originalUri: String,
        val resolvedPath: String?,
        val fileName: String?,
        val fileSize: Long?,
        val mimeType: String?,
        val isAccessible: Boolean,
        val error: String?
    ) {
        fun isLocalFile(): Boolean = originalUri.startsWith("file://") || resolvedPath != null
        fun isWebUrl(): Boolean = originalUri.startsWith("http://") || originalUri.startsWith("https://")
    }
}