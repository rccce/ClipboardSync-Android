package com.siw.clipboardsync.monitor.processor

import android.content.Context
import android.util.Log
import com.siw.clipboardsync.manager.SystemConfigManager
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Central manager for clipboard content processing.
 * Handles content type detection, processor selection, processing coordination,
 * deduplication, and debouncing.
 * 
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5
 */
class ClipboardProcessorManager(
    private val context: Context,
    private val systemConfigManager: SystemConfigManager? = null
) {
    
    private val processors: List<ClipboardContentProcessor> = listOf(
        TextProcessor(),
        ImageProcessor(),
        FileProcessor(context)
    ).sortedByDescending { it.priority }
    
    private val notificationHandler = ProcessingNotificationHandler(context)
    
    // Deduplication tracking
    private val contentHashCache = ConcurrentHashMap<String, Long>() // hash -> timestamp
    private val maxCacheSize = 100
    private val deduplicationWindowMs = 5000L // 5 seconds
    
    // Debounce tracking
    private val lastProcessedTimestamp = AtomicLong(0)
    private val debounceWindowMs = 200L // 200ms debounce window
    
    companion object {
        private const val TAG = "ClipboardProcessorManager"
        private const val DEFAULT_MAX_SIZE = 10 * 1024 * 1024L // 10MB default limit (will be overridden by system config)
    }
    
    /**
     * Updates all processors with the max file size from system config.
     * Should be called when config is loaded or refreshed.
     */
    suspend fun updateProcessorLimits() {
        systemConfigManager?.let { configManager ->
            try {
                val maxFileSize = configManager.getMaxFileSize()
                Log.d(TAG, "Updating processor limits with maxFileSize: $maxFileSize bytes")
                processors.forEach { processor ->
                    processor.updateMaxSizeLimit(maxFileSize)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update processor limits from config", e)
            }
        }
    }
    
    /**
     * Updates processor limits synchronously using cached config.
     */
    fun updateProcessorLimitsSync() {
        systemConfigManager?.let { configManager ->
            val maxFileSize = configManager.getCachedMaxFileSize()
            Log.d(TAG, "Updating processor limits (sync) with maxFileSize: $maxFileSize bytes")
            processors.forEach { processor ->
                processor.updateMaxSizeLimit(maxFileSize)
            }
        }
    }
    
    /**
     * Processes clipboard content using the most appropriate processor.
     * Includes deduplication and debouncing.
     * @param content The clipboard content to process
     * @return ProcessingResult containing the processed content or error
     */
    suspend fun processContent(content: ClipboardContent): ProcessingResult {
        // Update processor limits before processing
        updateProcessorLimits()
        
        return withContext(Dispatchers.IO) {
            try {
                // Check debounce
                if (shouldDebounce(content.timestamp)) {
                    Log.d(TAG, "Content debounced - too soon after last processing")
                    return@withContext ProcessingResult.Debounced(content)
                }
                
                // Check deduplication
                if (isDuplicate(content)) {
                    Log.d(TAG, "Content deduplicated - same content recently processed")
                    return@withContext ProcessingResult.Deduplicated(content)
                }
                
                // Update last processed timestamp
                lastProcessedTimestamp.set(content.timestamp)
                
                // Detect content type if unknown
                val detectedContent = if (content.type == ClipboardContent.ContentType.UNKNOWN) {
                    detectAndUpdateContentType(content)
                } else {
                    content
                }
                
                // Find the best processor for this content
                val processor = findBestProcessor(detectedContent)
                if (processor == null) {
                    val error = ClipboardError.UnsupportedContentType(
                        detectedContent.type.name,
                        detectedContent.mimeType
                    )
                    notificationHandler.showUnsupportedContentNotification(detectedContent)
                    return@withContext ProcessingResult.Failure(error, detectedContent)
                }
                
                // Process the content
                val result = processor.process(detectedContent)
                
                // Record content hash for deduplication
                recordContentHash(content)
                
                // Handle the result and show appropriate notifications
                handleProcessingResult(result, processor)
                
                result
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing content", e)
                val error = ClipboardError.UnknownError(e)
                ProcessingResult.Failure(error, content)
            }
        }
    }
    
    /**
     * Handles processing result and shows appropriate notifications.
     */
    private suspend fun handleProcessingResult(
        result: ProcessingResult,
        processor: ClipboardContentProcessor
    ) {
        when (result) {
            is ProcessingResult.Success -> {
                // Show success notification if content was significantly processed
                val originalSize = result.processedContent.metadata["original_size"]?.toString()?.toLongOrNull()
                val currentSize = result.processedContent.size
                
                if (originalSize != null && originalSize > currentSize * 1.5) {
                    notificationHandler.showCompressionSuccessNotification(
                        originalSize, currentSize, processor.supportedType
                    )
                }
            }
            
            is ProcessingResult.SizeExceeded -> {
                notificationHandler.showSizeExceededNotification(
                    result.actualSize,
                    result.maxSize,
                    processor.supportedType
                )
            }
            
            is ProcessingResult.Failure -> {
                when (result.error) {
                    is ClipboardError.ContentTooLarge -> {
                        notificationHandler.showSizeExceededNotification(
                            result.error.actualSize,
                            result.error.maxSize,
                            processor.supportedType
                        )
                    }
                    is ClipboardError.PermissionDenied -> {
                        notificationHandler.showPermissionRequiredNotification(
                            result.error.permission
                        )
                    }
                    is ClipboardError.UnsupportedContentType -> {
                        notificationHandler.showUnsupportedContentNotification(
                            result.originalContent
                        )
                    }
                    else -> {
                        notificationHandler.showProcessingErrorNotification(
                            result.error,
                            processor.supportedType
                        )
                    }
                }
            }
            
            is ProcessingResult.Debounced,
            is ProcessingResult.Deduplicated -> {
                // No notification needed for debounced/deduplicated content
            }
        }
    }
    
    /**
     * Checks if content should be debounced (too soon after last processing).
     * Requirements: 8.5
     */
    private fun shouldDebounce(timestamp: Long): Boolean {
        val lastTimestamp = lastProcessedTimestamp.get()
        if (lastTimestamp == 0L) return false
        
        val timeSinceLastProcess = timestamp - lastTimestamp
        return timeSinceLastProcess < debounceWindowMs
    }
    
    /**
     * Checks if content is a duplicate of recently processed content.
     * Requirements: 8.4
     */
    private fun isDuplicate(content: ClipboardContent): Boolean {
        val hash = computeContentHash(content)
        val cachedTimestamp = contentHashCache[hash]
        
        if (cachedTimestamp == null) return false
        
        val timeSinceLastSeen = content.timestamp - cachedTimestamp
        return timeSinceLastSeen < deduplicationWindowMs
    }
    
    /**
     * Records content hash for deduplication tracking.
     */
    private fun recordContentHash(content: ClipboardContent) {
        val hash = computeContentHash(content)
        contentHashCache[hash] = content.timestamp
        
        // Clean up old entries if cache is too large
        if (contentHashCache.size > maxCacheSize) {
            cleanupHashCache()
        }
    }
    
    /**
     * Computes a hash of the content for deduplication.
     */
    private fun computeContentHash(content: ClipboardContent): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(content.data)
            digest.update(content.mimeType.toByteArray())
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback to simple hash
            "${content.data.contentHashCode()}_${content.mimeType}"
        }
    }
    
    /**
     * Cleans up old entries from the hash cache.
     */
    private fun cleanupHashCache() {
        val now = System.currentTimeMillis()
        val expiredThreshold = now - deduplicationWindowMs * 2
        
        contentHashCache.entries.removeIf { entry ->
            entry.value < expiredThreshold
        }
    }
    
    /**
     * Clears the deduplication cache.
     */
    fun clearDeduplicationCache() {
        contentHashCache.clear()
        lastProcessedTimestamp.set(0)
    }
    
    /**
     * Gets deduplication statistics.
     */
    fun getDeduplicationStats(): DeduplicationStats {
        return DeduplicationStats(
            cacheSize = contentHashCache.size,
            maxCacheSize = maxCacheSize,
            deduplicationWindowMs = deduplicationWindowMs,
            debounceWindowMs = debounceWindowMs
        )
    }
    
    /**
     * Validates content without processing it.
     * @param content The clipboard content to validate
     * @return ValidationResult indicating if content is valid
     */
    fun validateContent(content: ClipboardContent): ValidationResult {
        val processor = findBestProcessor(content)
        return processor?.validate(content) ?: ValidationResult.Invalid(
            "No suitable processor found for content type: ${content.type}"
        )
    }
    
    /**
     * Gets processing capabilities for a content type.
     * @param contentType The content type to check
     * @return ProcessingCapabilities describing what can be processed
     */
    fun getProcessingCapabilities(contentType: ClipboardContent.ContentType): ProcessingCapabilities {
        val processor = processors.find { it.supportedType == contentType }
        return if (processor != null) {
            ProcessingCapabilities(
                isSupported = true,
                maxSizeLimit = processor.maxSizeLimit,
                supportedMimeTypes = getSupportedMimeTypes(contentType),
                processingFeatures = getProcessingFeatures(contentType)
            )
        } else {
            ProcessingCapabilities(
                isSupported = false,
                maxSizeLimit = 0L,
                supportedMimeTypes = emptyList(),
                processingFeatures = emptyList()
            )
        }
    }
    
    /**
     * Gets all supported content types.
     * @return List of supported content types
     */
    fun getSupportedContentTypes(): List<ClipboardContent.ContentType> {
        return processors.map { it.supportedType }.distinct()
    }
    
    private fun detectAndUpdateContentType(content: ClipboardContent): ClipboardContent {
        // Try each processor to detect content type
        for (processor in processors) {
            val detectedType = processor.detectContentType(content.data, content.mimeType)
            if (detectedType != null) {
                return content.copy(type = detectedType)
            }
        }
        
        // Fallback to general content type detection
        val detectedType = ContentTypeDetector.detectType(content.data, content.mimeType)
        return content.copy(type = detectedType)
    }
    
    private fun findBestProcessor(content: ClipboardContent): ClipboardContentProcessor? {
        // First, try to find a processor that explicitly supports this content
        val exactMatch = processors.find { it.canProcess(content) }
        if (exactMatch != null) {
            return exactMatch
        }
        
        // If no exact match, try processors by priority
        return processors.find { processor ->
            processor.supportedType == content.type ||
            (content.type == ClipboardContent.ContentType.UNKNOWN && 
             processor.detectContentType(content.data, content.mimeType) != null)
        }
    }
    
    private fun getSupportedMimeTypes(contentType: ClipboardContent.ContentType): List<String> {
        return when (contentType) {
            ClipboardContent.ContentType.TEXT -> listOf(
                "text/plain", "text/html", "text/rtf"
            )
            ClipboardContent.ContentType.HTML -> listOf(
                "text/html", "application/xhtml+xml"
            )
            ClipboardContent.ContentType.IMAGE -> listOf(
                "image/png", "image/jpeg", "image/gif", "image/bmp", "image/webp"
            )
            ClipboardContent.ContentType.FILE -> listOf(
                "application/octet-stream", "application/pdf", "application/zip"
            )
            ClipboardContent.ContentType.URI -> listOf(
                "text/uri-list", "text/x-moz-url"
            )
            ClipboardContent.ContentType.UNKNOWN -> emptyList()
        }
    }
    
    private fun getProcessingFeatures(contentType: ClipboardContent.ContentType): List<String> {
        return when (contentType) {
            ClipboardContent.ContentType.TEXT -> listOf(
                "Text normalization", "Encoding detection", "HTML detection", "Word counting"
            )
            ClipboardContent.ContentType.HTML -> listOf(
                "HTML validation", "Text extraction", "Link extraction"
            )
            ClipboardContent.ContentType.IMAGE -> listOf(
                "Image compression", "Format conversion", "Thumbnail generation", "Metadata extraction"
            )
            ClipboardContent.ContentType.FILE -> listOf(
                "URI resolution", "File validation", "Metadata extraction", "Access checking"
            )
            ClipboardContent.ContentType.URI -> listOf(
                "URI validation", "Scheme detection", "Accessibility checking"
            )
            ClipboardContent.ContentType.UNKNOWN -> emptyList()
        }
    }
}

/**
 * Describes the processing capabilities for a content type.
 */
data class ProcessingCapabilities(
    val isSupported: Boolean,
    val maxSizeLimit: Long,
    val supportedMimeTypes: List<String>,
    val processingFeatures: List<String>
)

/**
 * Handles user notifications for processing events.
 */
private class ProcessingNotificationHandler(private val context: Context) {
    
    fun showCompressionSuccessNotification(
        originalSize: Long,
        compressedSize: Long,
        contentType: ClipboardContent.ContentType
    ) {
        val compressionRatio = String.format("%.1f", originalSize.toDouble() / compressedSize)
        val message = when (contentType) {
            ClipboardContent.ContentType.IMAGE -> 
                "Image compressed ${compressionRatio}x (${formatSize(originalSize)} → ${formatSize(compressedSize)})"
            else -> 
                "Content compressed ${compressionRatio}x (${formatSize(originalSize)} → ${formatSize(compressedSize)})"
        }
        
        // TODO: Implement actual notification display
        // This would typically use NotificationManager to show a notification
        println("SUCCESS: $message")
    }
    
    fun showSizeExceededNotification(
        actualSize: Long,
        maxSize: Long,
        contentType: ClipboardContent.ContentType
    ) {
        val message = when (contentType) {
            ClipboardContent.ContentType.IMAGE -> 
                "Image too large: ${formatSize(actualSize)} exceeds ${formatSize(maxSize)} limit"
            ClipboardContent.ContentType.FILE -> 
                "File too large: ${formatSize(actualSize)} exceeds ${formatSize(maxSize)} limit"
            else -> 
                "Content too large: ${formatSize(actualSize)} exceeds ${formatSize(maxSize)} limit"
        }
        
        // TODO: Implement actual notification display
        println("ERROR: $message")
    }
    
    fun showPermissionRequiredNotification(permission: String) {
        val message = "Permission required: $permission. Please grant permission to continue."
        
        // TODO: Implement actual notification display with action to open settings
        println("PERMISSION: $message")
    }
    
    fun showUnsupportedContentNotification(content: ClipboardContent) {
        val message = "Unsupported content type: ${content.type.name} (${content.mimeType})"
        
        // TODO: Implement actual notification display
        println("UNSUPPORTED: $message")
    }
    
    fun showProcessingErrorNotification(
        error: ClipboardError,
        contentType: ClipboardContent.ContentType
    ) {
        val message = "Failed to process ${contentType.name.lowercase()}: ${error.getUserFriendlyMessage()}"
        
        // TODO: Implement actual notification display
        println("ERROR: $message")
    }
    
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}