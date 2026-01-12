package com.siw.clipboardsync.monitor.processor

import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * Processor for handling text clipboard content.
 * Handles plain text, HTML, and other text-based content types.
 */
class TextProcessor : ClipboardContentProcessor {
    
    override val supportedType: ClipboardContent.ContentType = ClipboardContent.ContentType.TEXT
    override val maxSizeLimit: Long = DEFAULT_MAX_SIZE // Default, can be overridden by system config
    override val priority: Int = ContentTypeDetector.getPriority(supportedType)
    
    // Dynamic size limit from system config
    @Volatile
    private var configuredMaxSize: Long = DEFAULT_MAX_SIZE
    
    companion object {
        private const val DEFAULT_MAX_SIZE = 1024 * 1024L // 1MB default for text content
        private const val MAX_TEXT_LENGTH = 500_000 // 500K characters
        private const val ENCODING_UTF8 = "UTF-8"
        private const val ENCODING_UTF16 = "UTF-16"
        private const val ENCODING_ASCII = "ASCII"
    }
    
    override fun updateMaxSizeLimit(newLimit: Long) {
        if (newLimit > 0) {
            configuredMaxSize = newLimit
        }
    }
    
    override fun getEffectiveMaxSizeLimit(): Long = configuredMaxSize
    
    override fun canProcess(content: ClipboardContent): Boolean {
        return content.type == ClipboardContent.ContentType.TEXT ||
               content.type == ClipboardContent.ContentType.HTML ||
               (content.type == ClipboardContent.ContentType.UNKNOWN && isTextContent(content))
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
                
                val processedContent = processTextContent(content)
                val metadata = extractTextMetadata(processedContent)
                
                ProcessingResult.Success(processedContent, metadata)
                
            } catch (e: Exception) {
                ProcessingResult.Failure(
                    ClipboardError.UnknownError(e),
                    content
                )
            }
        }
    }
    
    override fun validate(content: ClipboardContent): ValidationResult {
        // Check size limits using effective (configured) max size
        val effectiveMaxSize = getEffectiveMaxSizeLimit()
        if (content.size > effectiveMaxSize) {
            return ValidationResult.SizeExceeded(content.size, effectiveMaxSize)
        }
        
        // Check if content is actually text
        if (!isTextContent(content)) {
            return ValidationResult.Invalid(
                "Content is not valid text",
                ClipboardError.UnsupportedContentType(content.type.name, content.mimeType)
            )
        }
        
        // Check text length after decoding
        try {
            val textContent = String(content.data, StandardCharsets.UTF_8)
            if (textContent.length > MAX_TEXT_LENGTH) {
                return ValidationResult.SizeExceeded(
                    textContent.length.toLong(),
                    MAX_TEXT_LENGTH.toLong()
                )
            }
        } catch (e: Exception) {
            return ValidationResult.Invalid(
                "Failed to decode text content: ${e.message}",
                ClipboardError.UnknownError(e)
            )
        }
        
        return ValidationResult.Valid
    }
    
    override fun detectContentType(data: ByteArray, mimeType: String?): ClipboardContent.ContentType? {
        return when {
            mimeType == "text/html" -> ClipboardContent.ContentType.HTML
            mimeType?.startsWith("text/") == true -> ClipboardContent.ContentType.TEXT
            isTextData(data) -> ClipboardContent.ContentType.TEXT
            else -> null
        }
    }
    
    private fun processTextContent(content: ClipboardContent): ClipboardContent {
        val textData = String(content.data, StandardCharsets.UTF_8)
        
        // Normalize line endings
        val normalizedText = textData.replace("\r\n", "\n").replace("\r", "\n")
        
        // Trim excessive whitespace but preserve intentional formatting
        val processedText = normalizedText.trim()
        
        // Detect and set appropriate content type
        val detectedType = when {
            content.mimeType == "text/html" || isHtmlContent(processedText) -> 
                ClipboardContent.ContentType.HTML
            else -> ClipboardContent.ContentType.TEXT
        }
        
        val processedData = processedText.toByteArray(StandardCharsets.UTF_8)
        
        return content.copy(
            type = detectedType,
            data = processedData,
            size = processedData.size.toLong(),
            metadata = content.metadata + mapOf(
                "processed" to "true",
                "encoding" to ENCODING_UTF8,
                "line_count" to processedText.lines().size.toString(),
                "char_count" to processedText.length.toString(),
                "word_count" to countWords(processedText).toString()
            )
        )
    }
    
    private fun extractTextMetadata(content: ClipboardContent): Map<String, Any> {
        val textData = String(content.data, StandardCharsets.UTF_8)
        
        return mapOf(
            "text_length" to textData.length,
            "line_count" to textData.lines().size,
            "word_count" to countWords(textData),
            "is_html" to isHtmlContent(textData),
            "is_url" to isUrlContent(textData),
            "encoding" to detectEncoding(content.data),
            "has_special_chars" to hasSpecialCharacters(textData)
        )
    }
    
    private fun isTextContent(content: ClipboardContent): Boolean {
        return when (content.type) {
            ClipboardContent.ContentType.TEXT,
            ClipboardContent.ContentType.HTML -> true
            ClipboardContent.ContentType.UNKNOWN -> isTextData(content.data)
            else -> false
        }
    }
    
    private fun isTextData(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        
        // Try to decode as UTF-8 and check for valid text
        return try {
            val text = String(data, StandardCharsets.UTF_8)
            // Check if decoded text contains mostly printable characters
            val printableCount = text.count { char ->
                char.isLetterOrDigit() || char.isWhitespace() || char in "!@#$%^&*()_+-=[]{}|;':\",./<>?"
            }
            printableCount.toDouble() / text.length > 0.8
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isHtmlContent(text: String): Boolean {
        val htmlTags = listOf("<html", "<body", "<div", "<p", "<span", "<a", "<img", "<br", "<hr")
        val lowerText = text.lowercase()
        return htmlTags.any { tag -> lowerText.contains(tag) }
    }
    
    private fun isUrlContent(text: String): Boolean {
        val trimmedText = text.trim()
        return trimmedText.startsWith("http://") || 
               trimmedText.startsWith("https://") ||
               trimmedText.startsWith("ftp://") ||
               (trimmedText.contains(".") && !trimmedText.contains(" ") && trimmedText.length < 200)
    }
    
    private fun countWords(text: String): Int {
        return text.trim().split("\\s+".toRegex()).count { it.isNotEmpty() }
    }
    
    private fun detectEncoding(data: ByteArray): String {
        return try {
            // Try UTF-8 first
            val utf8Text = String(data, StandardCharsets.UTF_8)
            if (utf8Text.contains('\uFFFD')) {
                // Contains replacement characters, try UTF-16
                val utf16Text = String(data, StandardCharsets.UTF_16)
                if (!utf16Text.contains('\uFFFD')) {
                    return ENCODING_UTF16
                }
                // Try ASCII
                val asciiText = String(data, StandardCharsets.US_ASCII)
                if (!asciiText.contains('\uFFFD')) {
                    return ENCODING_ASCII
                }
            }
            ENCODING_UTF8
        } catch (e: Exception) {
            ENCODING_UTF8
        }
    }
    
    private fun hasSpecialCharacters(text: String): Boolean {
        return text.any { char ->
            !char.isLetterOrDigit() && !char.isWhitespace() && char !in ".,!?;:()[]{}\"'-"
        }
    }
}