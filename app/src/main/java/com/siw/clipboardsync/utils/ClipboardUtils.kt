package com.siw.clipboardsync.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Base64

object ClipboardUtils {
    
    private const val TAG = "ClipboardUtils"
    
    /**
     * Extract text content from ClipData
     */
    fun extractTextContent(clipData: ClipData): String? {
        return try {
            if (clipData.itemCount > 0) {
                val item = clipData.getItemAt(0)
                item.text?.toString()
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text content", e)
            null
        }
    }
    
    /**
     * Extract URI content from ClipData (for images/files)
     */
    fun extractUriContent(clipData: ClipData): Uri? {
        return try {
            if (clipData.itemCount > 0) {
                val item = clipData.getItemAt(0)
                item.uri
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting URI content", e)
            null
        }
    }
    
    /**
     * Determine the content type of clipboard data
     */
    fun getContentType(clipData: ClipData): String {
        return try {
            val description = clipData.description
            when {
                description.hasMimeType("text/plain") -> "text"
                description.hasMimeType("text/html") -> "text"
                description.hasMimeType("image/*") -> "image"
                description.getMimeType(0).startsWith("image/") -> "image"
                else -> {
                    // Check if it has URI (could be file)
                    val item = clipData.getItemAt(0)
                    if (item.uri != null) "file" else "text"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error determining content type", e)
            "text" // Default to text
        }
    }
    
    /**
     * Set text content to clipboard
     */
    fun setTextToClipboard(context: Context, text: String, label: String = "ClipboardSync") {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText(label, text)
            clipboardManager.setPrimaryClip(clipData)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting text to clipboard", e)
        }
    }
    
    /**
     * Set URI content to clipboard
     */
    fun setUriToClipboard(context: Context, uri: Uri, label: String = "ClipboardSync") {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newUri(context.contentResolver, label, uri)
            clipboardManager.setPrimaryClip(clipData)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting URI to clipboard", e)
        }
    }
    
    /**
     * Check if clipboard has content
     */
    fun hasClipboardContent(context: Context): Boolean {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.hasPrimaryClip() && 
            clipboardManager.primaryClip != null && 
            clipboardManager.primaryClip!!.itemCount > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard content", e)
            false
        }
    }
    
    /**
     * Get current clipboard content as string (for display purposes)
     */
    fun getCurrentClipboardText(context: Context): String? {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                extractTextContent(clipData)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current clipboard text", e)
            null
        }
    }
    
    /**
     * Convert bitmap to base64 string for transmission
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Base64.getEncoder().encodeToString(byteArray)
        } else {
            android.util.Base64.encodeToString(byteArray, android.util.Base64.DEFAULT)
        }
    }
    
    /**
     * Check if content should be synced (filters out empty/invalid content)
     */
    fun shouldSyncContent(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        if (content.length < 2) return false // Too short
        if (content.length > 1000000) return false // Too long (1MB limit for text)
        
        // Filter out common system clipboard content that shouldn't be synced
        val trimmed = content.trim()
        if (trimmed.matches(Regex("^[\\s\\n\\r\\t]*$"))) return false // Only whitespace
        
        return true
    }
    
    /**
     * Sanitize content for safe transmission
     */
    fun sanitizeContent(content: String): String {
        return content.trim()
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "") // Remove control characters
            .take(1000000) // Limit length
    }
}