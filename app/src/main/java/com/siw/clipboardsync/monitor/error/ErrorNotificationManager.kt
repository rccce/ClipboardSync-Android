package com.siw.clipboardsync.monitor.error

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.siw.clipboardsync.MainActivity
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ErrorNotificationManager @Inject constructor(
    private val context: Context
) {
    private val notificationManager = NotificationManagerCompat.from(context)
    
    companion object {
        private const val CHANNEL_ID_ERROR = "clipboard_error_channel"
        private const val CHANNEL_ID_RECOVERY = "clipboard_recovery_channel"
        private const val NOTIFICATION_ID_ERROR = 1001
        private const val NOTIFICATION_ID_RECOVERY = 1002
        private const val NOTIFICATION_ID_PERMISSION = 1003
    }
    
    init {
        createNotificationChannels()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val errorChannel = NotificationChannel(
                CHANNEL_ID_ERROR,
                "Clipboard Monitoring Errors",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for clipboard monitoring errors and issues"
                enableVibration(true)
            }
            
            val recoveryChannel = NotificationChannel(
                CHANNEL_ID_RECOVERY,
                "Clipboard Recovery",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for clipboard monitoring recovery status"
            }
            
            val systemNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            systemNotificationManager.createNotificationChannel(errorChannel)
            systemNotificationManager.createNotificationChannel(recoveryChannel)
        }
    }
    
    fun showRootAccessLostNotification() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Root Access Lost")
            .setContentText("Clipboard monitoring switched to alternative method")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Root access was lost. Clipboard monitoring has automatically switched to accessibility service mode. Some features may be limited."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_preferences,
                "Open Settings",
                pendingIntent
            )
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }
    
    fun showAccessibilityPermissionNeeded() {
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val settingsPendingIntent = PendingIntent.getActivity(
            context, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Accessibility Permission Required")
            .setContentText("Enable accessibility service for clipboard monitoring")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("ClipboardSync needs accessibility service permission to monitor clipboard changes. Tap to open accessibility settings."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(settingsPendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_preferences,
                "Open Accessibility Settings",
                settingsPendingIntent
            )
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_PERMISSION, notification)
    }
    
    fun showNotificationPermissionNeeded() {
        val settingsIntent = Intent().apply {
            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val settingsPendingIntent = PendingIntent.getActivity(
            context, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Notification Permission Required")
            .setContentText("Enable notifications for clipboard monitoring status")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("ClipboardSync needs notification permission to show monitoring status and error alerts. Tap to open notification settings."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(settingsPendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_preferences,
                "Open Notification Settings",
                settingsPendingIntent
            )
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_PERMISSION, notification)
    }
    
    fun showForegroundServicePermissionNeeded() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Foreground Service Permission Required")
            .setContentText("Background clipboard monitoring requires foreground service permission")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("To monitor clipboard changes in the background, ClipboardSync needs foreground service permission. This ensures reliable clipboard synchronization."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_PERMISSION, notification)
    }
    
    fun showSystemHookFailedNotification() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("System Hook Failed")
            .setContentText("Switched to alternative monitoring method")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("System-level clipboard hooks failed to initialize. Clipboard monitoring has switched to an alternative method automatically."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }
    
    fun showContentTooLargeNotification(contentSize: Long, maxSize: Long) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val contentSizeMB = contentSize / (1024 * 1024)
        val maxSizeMB = maxSize / (1024 * 1024)
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Clipboard Content Too Large")
            .setContentText("Content size: ${contentSizeMB}MB, limit: ${maxSizeMB}MB")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("The clipboard content (${contentSizeMB}MB) exceeds the maximum size limit (${maxSizeMB}MB) and cannot be synchronized. Consider copying smaller content."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }
    
    fun showUnknownErrorNotification(errorMessage: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Clipboard Monitoring Error")
            .setContentText("An unexpected error occurred")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Clipboard monitoring encountered an error: $errorMessage. Attempting to recover automatically."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }
    
    fun showNoFallbackAvailableNotification() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Clipboard Monitoring Unavailable")
            .setContentText("No monitoring methods available")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("All clipboard monitoring methods have failed. Please check permissions and device settings, then restart the app."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_revert,
                "Open App",
                pendingIntent
            )
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }
    
    fun showRecoverySuccessNotification(method: MonitoringMethod) {
        val methodName = when (method) {
            MonitoringMethod.SYSTEM_HOOKS -> "System Hooks"
            MonitoringMethod.XPOSED_HOOKS -> "Xposed Framework"
            MonitoringMethod.READ_LOGS -> "READ_LOGS Permission"
            MonitoringMethod.SHIZUKU -> "Shizuku"
            MonitoringMethod.ACCESSIBILITY_SERVICE -> "Accessibility Service"
            MonitoringMethod.FOREGROUND_SERVICE -> "Foreground Service"
            MonitoringMethod.POLLING_FALLBACK -> "Polling Fallback"
        }
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_RECOVERY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Clipboard Monitoring Recovered")
            .setContentText("Now using: $methodName")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Clipboard monitoring has been successfully restored using $methodName. Synchronization is now active."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_RECOVERY, notification)
    }
    
    /**
     * Shows notification for persistent failures.
     * Requirements: 10.3
     */
    fun showPersistentFailureNotification(
        errorCount: Int,
        lastError: com.siw.clipboardsync.monitor.model.ClipboardError,
        lastMethod: MonitoringMethod
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val methodName = when (lastMethod) {
            MonitoringMethod.SYSTEM_HOOKS -> "System Hooks"
            MonitoringMethod.XPOSED_HOOKS -> "Xposed Framework"
            MonitoringMethod.READ_LOGS -> "READ_LOGS Permission"
            MonitoringMethod.SHIZUKU -> "Shizuku"
            MonitoringMethod.ACCESSIBILITY_SERVICE -> "Accessibility Service"
            MonitoringMethod.FOREGROUND_SERVICE -> "Foreground Service"
            MonitoringMethod.POLLING_FALLBACK -> "Polling Fallback"
        }
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Persistent Clipboard Monitoring Issues")
            .setContentText("$errorCount consecutive errors detected")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Clipboard monitoring has encountered $errorCount consecutive errors using $methodName. Last error: ${lastError.getUserFriendlyMessage()}. Please check your device settings or restart the app."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_revert,
                "Open App",
                pendingIntent
            )
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }
    
    fun showRecoveryFailedNotification() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Recovery Failed")
            .setContentText("Unable to restore clipboard monitoring")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Failed to recover clipboard monitoring after multiple attempts. Please manually restart the service or check app permissions."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_revert,
                "Open App",
                pendingIntent
            )
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }
    
    fun requestNotificationPermission() {
        // This would typically trigger a permission request dialog
        // Implementation depends on the UI framework being used
    }
    
    fun clearAllNotifications() {
        notificationManager.cancel(NOTIFICATION_ID_ERROR)
        notificationManager.cancel(NOTIFICATION_ID_RECOVERY)
        notificationManager.cancel(NOTIFICATION_ID_PERMISSION)
    }
}