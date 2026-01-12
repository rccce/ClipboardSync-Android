package com.siw.clipboardsync.monitor.processor

import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError

/**
 * Base interface for clipboard content processors.
 * Each processor handles a specific type of clipboard content.
 */
interface ClipboardContentProcessor {
    
    /**
     * The content type this processor handles.
     */
    val supportedType: ClipboardContent.ContentType
    
    /**
     * Maximum size limit for content this processor can handle (in bytes).
     * This is the default limit, actual limit may come from system config.
     */
    val maxSizeLimit: Long
    
    /**
     * Priority of this processor (higher values = higher priority).
     */
    val priority: Int
    
    /**
     * Updates the max size limit from system configuration.
     * @param newLimit The new size limit in bytes
     */
    fun updateMaxSizeLimit(newLimit: Long)
    
    /**
     * Gets the current effective max size limit.
     * @return The current max size limit in bytes
     */
    fun getEffectiveMaxSizeLimit(): Long
    
    /**
     * Checks if this processor can handle the given content.
     * @param content The clipboard content to check
     * @return true if this processor can handle the content
     */
    fun canProcess(content: ClipboardContent): Boolean
    
    /**
     * Processes the clipboard content asynchronously.
     * @param content The clipboard content to process
     * @return ProcessingResult containing the processed content or error
     */
    suspend fun process(content: ClipboardContent): ProcessingResult
    
    /**
     * Validates the content before processing.
     * @param content The clipboard content to validate
     * @return ValidationResult indicating if content is valid
     */
    fun validate(content: ClipboardContent): ValidationResult
    
    /**
     * Detects the content type from raw data.
     * @param data Raw clipboard data
     * @param mimeType MIME type hint
     * @return Detected content type or null if not supported
     */
    fun detectContentType(data: ByteArray, mimeType: String?): ClipboardContent.ContentType?
}

/**
 * Result of content processing operation.
 * 
 * Requirements: 8.4, 8.5
 */
sealed class ProcessingResult {
    data class Success(
        val processedContent: ClipboardContent,
        val metadata: Map<String, Any> = emptyMap()
    ) : ProcessingResult()
    
    data class Failure(
        val error: ClipboardError,
        val originalContent: ClipboardContent
    ) : ProcessingResult()
    
    data class SizeExceeded(
        val actualSize: Long,
        val maxSize: Long,
        val originalContent: ClipboardContent
    ) : ProcessingResult()
    
    /**
     * Content was deduplicated (same content recently processed).
     * Requirements: 8.4
     */
    data class Deduplicated(
        val originalContent: ClipboardContent
    ) : ProcessingResult()
    
    /**
     * Content was debounced (too soon after last processing).
     * Requirements: 8.5
     */
    data class Debounced(
        val originalContent: ClipboardContent
    ) : ProcessingResult()
}

/**
 * Statistics for deduplication tracking.
 */
data class DeduplicationStats(
    val cacheSize: Int,
    val maxCacheSize: Int,
    val deduplicationWindowMs: Long,
    val debounceWindowMs: Long
)

/**
 * Result of content validation.
 */
sealed class ValidationResult {
    object Valid : ValidationResult()
    
    data class Invalid(
        val reason: String,
        val error: ClipboardError? = null
    ) : ValidationResult()
    
    data class SizeExceeded(
        val actualSize: Long,
        val maxSize: Long
    ) : ValidationResult()
}

/**
 * Content type detection and prioritization utility.
 */
object ContentTypeDetector {
    
    /**
     * Detects content type from MIME type and data.
     * @param data Raw clipboard data
     * @param mimeType MIME type
     * @return Detected content type
     */
    fun detectType(data: ByteArray, mimeType: String?): ClipboardContent.ContentType {
        return when {
            mimeType?.startsWith("text/") == true -> ClipboardContent.ContentType.TEXT
            mimeType?.startsWith("image/") == true -> ClipboardContent.ContentType.IMAGE
            mimeType?.startsWith("application/") == true -> ClipboardContent.ContentType.FILE
            mimeType == "text/uri-list" -> ClipboardContent.ContentType.URI
            mimeType == "text/html" -> ClipboardContent.ContentType.HTML
            isTextData(data) -> ClipboardContent.ContentType.TEXT
            isImageData(data) -> ClipboardContent.ContentType.IMAGE
            else -> ClipboardContent.ContentType.UNKNOWN
        }
    }
    
    /**
     * Gets processor priority for content type.
     * @param type Content type
     * @return Priority value (higher = more priority)
     */
    fun getPriority(type: ClipboardContent.ContentType): Int {
        return when (type) {
            ClipboardContent.ContentType.TEXT -> 100
            ClipboardContent.ContentType.HTML -> 90
            ClipboardContent.ContentType.URI -> 80
            ClipboardContent.ContentType.IMAGE -> 70
            ClipboardContent.ContentType.FILE -> 60
            ClipboardContent.ContentType.UNKNOWN -> 10
        }
    }
    
    private fun isTextData(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        
        // Check if data contains mostly printable ASCII characters
        val printableCount = data.count { byte ->
            val char = byte.toInt() and 0xFF
            char in 32..126 || char in listOf(9, 10, 13) // printable + tab, LF, CR
        }
        
        return printableCount.toDouble() / data.size > 0.8
    }
    
    private fun isImageData(data: ByteArray): Boolean {
        if (data.size < 4) return false
        
        // Check for common image file signatures
        return when {
            // PNG signature
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && 
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> true
            
            // JPEG signature
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> true
            
            // GIF signature
            data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && 
            data[2] == 0x46.toByte() -> true
            
            // BMP signature
            data[0] == 0x42.toByte() && data[1] == 0x4D.toByte() -> true
            
            else -> false
        }
    }
}