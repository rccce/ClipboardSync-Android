package com.siw.clipboardsync.utils

import android.os.Build
import java.text.SimpleDateFormat
import java.util.*

object TimeUtils {
    
    // Use SimpleDateFormat for compatibility with API level 24+
    private val ISO_FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    
    /**
     * Get current timestamp in ISO 8601 format matching API specification
     * Format: "2025-07-10T15:35:41+08:00"
     */
    fun getCurrentIsoTimestamp(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use modern API for Android 8.0+
            getCurrentIsoTimestampModern()
        } else {
            // Use legacy API for older versions
            ISO_FORMATTER.format(Date())
        }
    }
    
    /**
     * Convert milliseconds to ISO 8601 format
     */
    fun millisecondsToIso(millis: Long): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            millisecondsToIsoModern(millis)
        } else {
            ISO_FORMATTER.format(Date(millis))
        }
    }
    
    /**
     * Parse ISO 8601 timestamp to milliseconds
     */
    fun isoToMilliseconds(isoTimestamp: String): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                isoToMillisecondsModern(isoTimestamp)
            } else {
                ISO_FORMATTER.parse(isoTimestamp)?.time ?: System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis() // Fallback to current time
        }
    }
    
    // Modern implementations for Android 8.0+
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun getCurrentIsoTimestampModern(): String {
        return java.time.ZonedDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
        )
    }
    
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun millisecondsToIsoModern(millis: Long): String {
        return java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
    }
    
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun isoToMillisecondsModern(isoTimestamp: String): Long {
        return try {
            java.time.ZonedDateTime.parse(
                isoTimestamp, 
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
            ).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.Instant.parse(isoTimestamp).toEpochMilli()
            } catch (e2: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}