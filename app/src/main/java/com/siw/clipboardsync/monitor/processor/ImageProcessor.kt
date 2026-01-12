package com.siw.clipboardsync.monitor.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.siw.clipboardsync.monitor.model.ClipboardContent
import com.siw.clipboardsync.monitor.model.ClipboardError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Processor for handling image clipboard content with async processing.
 * Handles image compression, format conversion, and size optimization.
 */
class ImageProcessor : ClipboardContentProcessor {
    
    override val supportedType: ClipboardContent.ContentType = ClipboardContent.ContentType.IMAGE
    override val maxSizeLimit: Long = DEFAULT_MAX_SIZE // Default, can be overridden by system config
    override val priority: Int = ContentTypeDetector.getPriority(supportedType)
    
    // Dynamic size limit from system config
    @Volatile
    private var configuredMaxSize: Long = DEFAULT_MAX_SIZE
    
    companion object {
        private const val DEFAULT_MAX_SIZE = 10 * 1024 * 1024L // 10MB default for images (will be overridden by system config)
        private const val MAX_IMAGE_DIMENSION = 4096 // Max width/height in pixels
        private const val COMPRESSION_QUALITY = 85 // JPEG compression quality
        private const val THUMBNAIL_SIZE = 256 // Thumbnail dimension
        private const val MAX_COMPRESSED_SIZE = 5 * 1024 * 1024 // 5MB after compression
        
        // Image format signatures
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        private val GIF_SIGNATURE = byteArrayOf(0x47, 0x49, 0x46)
        private val BMP_SIGNATURE = byteArrayOf(0x42, 0x4D)
        private val WEBP_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
    }
    
    override fun updateMaxSizeLimit(newLimit: Long) {
        if (newLimit > 0) {
            configuredMaxSize = newLimit
        }
    }
    
    override fun getEffectiveMaxSizeLimit(): Long = configuredMaxSize
    
    override fun canProcess(content: ClipboardContent): Boolean {
        return content.type == ClipboardContent.ContentType.IMAGE ||
               (content.type == ClipboardContent.ContentType.UNKNOWN && isImageContent(content))
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
                
                val processedContent = processImageContent(content)
                val metadata = extractImageMetadata(processedContent)
                
                ProcessingResult.Success(processedContent, metadata)
                
            } catch (e: OutOfMemoryError) {
                ProcessingResult.Failure(
                    ClipboardError.ContentTooLarge(content.size, getEffectiveMaxSizeLimit()),
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
        // Check size limits using effective (configured) max size
        val effectiveMaxSize = getEffectiveMaxSizeLimit()
        if (content.size > effectiveMaxSize) {
            return ValidationResult.SizeExceeded(content.size, effectiveMaxSize)
        }
        
        // Check if content is actually an image
        if (!isImageContent(content)) {
            return ValidationResult.Invalid(
                "Content is not a valid image",
                ClipboardError.UnsupportedContentType(content.type.name, content.mimeType)
            )
        }
        
        // Try to decode image to validate format
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(content.data, 0, content.data.size, options)
            
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return ValidationResult.Invalid(
                    "Invalid image dimensions",
                    ClipboardError.UnsupportedContentType(content.type.name, content.mimeType)
                )
            }
            
            // Check image dimensions
            if (options.outWidth > MAX_IMAGE_DIMENSION || options.outHeight > MAX_IMAGE_DIMENSION) {
                return ValidationResult.SizeExceeded(
                    (options.outWidth * options.outHeight).toLong(),
                    (MAX_IMAGE_DIMENSION * MAX_IMAGE_DIMENSION).toLong()
                )
            }
            
        } catch (e: Exception) {
            return ValidationResult.Invalid(
                "Failed to decode image: ${e.message}",
                ClipboardError.UnknownError(e)
            )
        }
        
        return ValidationResult.Valid
    }
    
    override fun detectContentType(data: ByteArray, mimeType: String?): ClipboardContent.ContentType? {
        return when {
            mimeType?.startsWith("image/") == true -> ClipboardContent.ContentType.IMAGE
            isImageData(data) -> ClipboardContent.ContentType.IMAGE
            else -> null
        }
    }
    
    private suspend fun processImageContent(content: ClipboardContent): ClipboardContent {
        return withContext(Dispatchers.IO) {
            // Decode image to get dimensions and format info
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(content.data, 0, content.data.size, options)
            
            val originalWidth = options.outWidth
            val originalHeight = options.outHeight
            val originalFormat = detectImageFormat(content.data)
            
            // Determine if compression/resizing is needed
            val needsProcessing = content.size > MAX_COMPRESSED_SIZE ||
                                originalWidth > MAX_IMAGE_DIMENSION ||
                                originalHeight > MAX_IMAGE_DIMENSION
            
            val processedData = if (needsProcessing) {
                compressAndResizeImage(content.data, originalWidth, originalHeight)
            } else {
                content.data
            }
            
            // Generate thumbnail
            val thumbnailData = generateThumbnail(content.data)
            
            content.copy(
                type = ClipboardContent.ContentType.IMAGE,
                data = processedData,
                size = processedData.size.toLong(),
                metadata = content.metadata + mapOf(
                    "processed" to needsProcessing.toString(),
                    "original_width" to originalWidth.toString(),
                    "original_height" to originalHeight.toString(),
                    "original_format" to originalFormat,
                    "original_size" to content.size.toString(),
                    "compressed_size" to processedData.size.toString(),
                    "compression_ratio" to String.format("%.2f", content.size.toDouble() / processedData.size),
                    "has_thumbnail" to (thumbnailData != null).toString(),
                    "thumbnail_size" to (thumbnailData?.size ?: 0).toString()
                )
            )
        }
    }
    
    private fun compressAndResizeImage(data: ByteArray, originalWidth: Int, originalHeight: Int): ByteArray {
        // Calculate scale factor to fit within max dimensions
        val scaleFactor = minOf(
            MAX_IMAGE_DIMENSION.toFloat() / originalWidth,
            MAX_IMAGE_DIMENSION.toFloat() / originalHeight,
            1.0f
        )
        
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(originalWidth, originalHeight, scaleFactor)
            inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
        }
        
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options)
            ?: throw IOException("Failed to decode image for compression")
        
        // Further resize if needed
        val finalBitmap = if (scaleFactor < 1.0f) {
            val newWidth = (originalWidth * scaleFactor).toInt()
            val newHeight = (originalHeight * scaleFactor).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true).also {
                if (it != bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }
        
        // Compress to JPEG
        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, outputStream)
        finalBitmap.recycle()
        
        return outputStream.toByteArray()
    }
    
    private fun generateThumbnail(data: ByteArray): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            
            val scaleFactor = minOf(
                THUMBNAIL_SIZE.toFloat() / options.outWidth,
                THUMBNAIL_SIZE.toFloat() / options.outHeight
            )
            
            options.inJustDecodeBounds = false
            options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, scaleFactor)
            options.inPreferredConfig = Bitmap.Config.RGB_565
            
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options)
            if (bitmap != null) {
                val thumbnailBitmap = Bitmap.createScaledBitmap(
                    bitmap, 
                    THUMBNAIL_SIZE, 
                    THUMBNAIL_SIZE, 
                    true
                )
                bitmap.recycle()
                
                val outputStream = ByteArrayOutputStream()
                thumbnailBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                thumbnailBitmap.recycle()
                
                outputStream.toByteArray()
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun calculateInSampleSize(width: Int, height: Int, scaleFactor: Float): Int {
        var inSampleSize = 1
        if (scaleFactor < 1.0f) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while ((halfHeight / inSampleSize) >= (height * scaleFactor) &&
                   (halfWidth / inSampleSize) >= (width * scaleFactor)) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
    
    private fun extractImageMetadata(content: ClipboardContent): Map<String, Any> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(content.data, 0, content.data.size, options)
        
        return mapOf(
            "width" to options.outWidth,
            "height" to options.outHeight,
            "format" to detectImageFormat(content.data),
            "size_bytes" to content.size,
            "size_kb" to (content.size / 1024),
            "size_mb" to String.format("%.2f", content.size / (1024.0 * 1024.0)),
            "aspect_ratio" to String.format("%.2f", options.outWidth.toDouble() / options.outHeight),
            "pixel_count" to (options.outWidth * options.outHeight),
            "color_depth" to when (options.inPreferredConfig) {
                Bitmap.Config.RGB_565 -> 16
                Bitmap.Config.ARGB_8888 -> 32
                else -> 24
            }
        )
    }
    
    private fun isImageContent(content: ClipboardContent): Boolean {
        return content.type == ClipboardContent.ContentType.IMAGE || isImageData(content.data)
    }
    
    private fun isImageData(data: ByteArray): Boolean {
        if (data.size < 4) return false
        
        return when {
            data.startsWith(PNG_SIGNATURE) -> true
            data.startsWith(JPEG_SIGNATURE) -> true
            data.startsWith(GIF_SIGNATURE) -> true
            data.startsWith(BMP_SIGNATURE) -> true
            data.size >= 12 && data.sliceArray(0..3).contentEquals(WEBP_SIGNATURE) -> true
            else -> false
        }
    }
    
    private fun detectImageFormat(data: ByteArray): String {
        return when {
            data.startsWith(PNG_SIGNATURE) -> "PNG"
            data.startsWith(JPEG_SIGNATURE) -> "JPEG"
            data.startsWith(GIF_SIGNATURE) -> "GIF"
            data.startsWith(BMP_SIGNATURE) -> "BMP"
            data.size >= 12 && data.sliceArray(0..3).contentEquals(WEBP_SIGNATURE) -> "WEBP"
            else -> "UNKNOWN"
        }
    }
    
    private fun ByteArray.startsWith(signature: ByteArray): Boolean {
        if (this.size < signature.size) return false
        return this.sliceArray(0 until signature.size).contentEquals(signature)
    }
}