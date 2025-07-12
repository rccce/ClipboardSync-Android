package com.siw.clipboardsync.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.*

object DeviceUtils {
    
    fun generateDeviceId(context: Context): String {
        // Check if we already have a stored device ID
        val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        val existingId = prefs.getString("device_id", null)
        
        if (existingId != null) {
            return existingId
        }
        
        // Generate a new UUID-based device ID
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        
        val deviceId = if (androidId != null && androidId != "9774d56d682e549c" && androidId.isNotEmpty()) {
            // Create a UUID from Android ID to ensure proper format
            UUID.nameUUIDFromBytes(androidId.toByteArray()).toString()
        } else {
            // Fallback: create a UUID based on device characteristics
            val deviceInfo = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.DEVICE}-${Build.BOARD}"
            UUID.nameUUIDFromBytes(deviceInfo.toByteArray()).toString()
        }
        
        // Store the generated device ID for consistency
        prefs.edit().putString("device_id", deviceId).apply()
        
        return deviceId
    }
    
    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            model.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        } else {
            "${manufacturer.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} $model"
        }
    }
    
    fun getDeviceType(): String = "android"
    
    fun getOsVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    
    fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0"
        }
    }
    
    fun generateContentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}