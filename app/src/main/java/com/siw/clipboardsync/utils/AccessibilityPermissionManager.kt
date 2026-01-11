package com.siw.clipboardsync.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.siw.clipboardsync.service.ClipboardAccessibilityService

/**
 * Utility class for managing accessibility service permissions and settings.
 * Provides methods to check, request, and guide users through enabling accessibility service.
 */
class AccessibilityPermissionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AccessibilityPermissionManager"
    }
    
    /**
     * Checks if the accessibility service is enabled for this app.
     * @return true if enabled, false otherwise
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = try {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            Log.w(TAG, "Accessibility setting not found", e)
            0
        }
        
        Log.d(TAG, "Accessibility enabled system-wide: ${accessibilityEnabled == 1}")
        
        if (accessibilityEnabled != 1) {
            Log.d(TAG, "Accessibility services are disabled system-wide")
            return false
        }
        
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        
        Log.d(TAG, "Enabled accessibility services: $enabledServices")
        
        if (enabledServices.isNullOrEmpty()) {
            Log.d(TAG, "No accessibility services are enabled")
            return false
        }
        
        val serviceName = "${context.packageName}/${ClipboardAccessibilityService::class.java.name}"
        Log.d(TAG, "Looking for service: $serviceName")
        
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            Log.d(TAG, "Checking component: $componentName")
            if (componentName.equals(serviceName, ignoreCase = true)) {
                Log.d(TAG, "ClipboardAccessibilityService is enabled")
                return true
            }
        }
        
        Log.d(TAG, "ClipboardAccessibilityService is not in enabled services list")
        return false
    }
    
    /**
     * Creates an intent to open accessibility settings.
     * @return Intent to open accessibility settings
     */
    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
    
    /**
     * Creates an intent to open the specific accessibility service settings.
     * This will directly navigate to the ClipboardSync accessibility service page.
     * @return Intent to open specific service settings, or general settings if not available
     */
    fun createServiceSpecificSettingsIntent(): Intent {
        return try {
            // Try to open the specific service settings
            Intent("android.settings.ACCESSIBILITY_SETTINGS_FOR_SUW").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("component_name", "${context.packageName}/${ClipboardAccessibilityService::class.java.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create service-specific intent, falling back to general settings", e)
            createAccessibilitySettingsIntent()
        }
    }
    
    /**
     * Gets user-friendly instructions for enabling the accessibility service.
     * @return step-by-step instructions
     */
    fun getEnableInstructions(): String {
        return """
            To enable clipboard monitoring:
            
            1. Open Settings → Accessibility
            2. Find "ClipboardSync" in the Downloaded apps section
            3. Tap on "ClipboardSync"
            4. Turn on the "Use ClipboardSync" toggle
            5. Tap "Allow" when prompted for permissions
            6. Return to ClipboardSync app
            
            This allows ClipboardSync to monitor clipboard changes in the background.
        """.trimIndent()
    }
    
    /**
     * Gets a short description of why accessibility service is needed.
     * @return explanation text
     */
    fun getPermissionRationale(): String {
        return "ClipboardSync needs accessibility service permission to monitor clipboard changes " +
                "on Android 10+ devices where background clipboard access is restricted. " +
                "This service only monitors clipboard content and does not access other app data."
    }
    
    /**
     * Checks if the accessibility service is currently running and connected.
     * @return true if service is running and connected, false otherwise
     */
    fun isServiceRunning(): Boolean {
        return ClipboardAccessibilityService.isServiceRunning()
    }
    
    /**
     * Checks if the accessibility service is enabled and running.
     * Note: The accessibility service is now only used for keep-alive, not clipboard monitoring.
     * @return true if service is enabled and running, false otherwise
     */
    fun isServiceActiveAndRunning(): Boolean {
        return isAccessibilityServiceEnabled() && isServiceRunning()
    }
    
    /**
     * Gets the current status of the accessibility service.
     * Note: The accessibility service is now only used for keep-alive, not clipboard monitoring.
     * @return AccessibilityServiceStatus indicating current state
     */
    fun getServiceStatus(): AccessibilityServiceStatus {
        return when {
            !isAccessibilityServiceEnabled() -> AccessibilityServiceStatus.NOT_ENABLED
            !isServiceRunning() -> AccessibilityServiceStatus.ENABLED_BUT_NOT_RUNNING
            else -> AccessibilityServiceStatus.ACTIVE_FOR_KEEP_ALIVE
        }
    }
    
    /**
     * Attempts to guide the user through enabling the accessibility service.
     * @param onResult callback with the result of the permission request
     */
    fun requestAccessibilityPermission(onResult: (Boolean) -> Unit) {
        if (isAccessibilityServiceEnabled()) {
            onResult(true)
            return
        }
        
        try {
            val intent = createServiceSpecificSettingsIntent()
            context.startActivity(intent)
            
            // Note: We can't directly detect when the user enables the service
            // The calling code should check the status after the user returns
            Log.d(TAG, "Opened accessibility settings for user to enable service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
            onResult(false)
        }
    }
    
    /**
     * Enumeration of possible accessibility service states.
     * Note: The accessibility service is now only used for keep-alive, not clipboard monitoring.
     */
    enum class AccessibilityServiceStatus {
        NOT_ENABLED,
        ENABLED_BUT_NOT_RUNNING,
        ACTIVE_FOR_KEEP_ALIVE
    }
}