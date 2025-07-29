package com.siw.clipboardsync.monitor.model

import java.util.UUID

/**
 * Represents clipboard content with metadata and type information.
 */
data class ClipboardContent(
    val id: String = UUID.randomUUID().toString(),
    val type: ContentType,
    val data: ByteArray,
    val mimeType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val size: Long,
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * Enumeration of supported clipboard content types.
     */
    enum class ContentType {
        TEXT,
        IMAGE,
        FILE,
        URI,
        HTML,
        UNKNOWN
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as ClipboardContent
        
        if (id != other.id) return false
        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false
        if (mimeType != other.mimeType) return false
        if (timestamp != other.timestamp) return false
        if (source != other.source) return false
        if (size != other.size) return false
        if (metadata != other.metadata) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}