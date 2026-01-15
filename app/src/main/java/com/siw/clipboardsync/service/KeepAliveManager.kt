package com.siw.clipboardsync.service

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.siw.clipboardsync.receiver.KeepAliveReceiver
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simplified keep-alive manager.
 * Uses only JobScheduler and AlarmManager for periodic checks.
 * Removed aggressive mechanisms that could cause system issues.
 */
class KeepAliveManager(private val context: Context) {
    
    companion object {
        private const val TAG = "KeepAliveManager"
        
        const val WATCHDOG_JOB_ID = 10002
        const val ALARM_KEEP_ALIVE_REQUEST = 20001
        
        // Longer intervals to reduce system load
        private const val JOB_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
        private const val ALARM_INTERVAL_MS = 20 * 60 * 1000L // 20 minutes
        
        @Volatile
        private var instance: KeepAliveManager? = null
        
        fun getInstance(context: Context): KeepAliveManager {
            return instance ?: synchronized(this) {
                instance ?: KeepAliveManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val isRunning = AtomicBoolean(false)
    
    private val powerManager by lazy { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val alarmManager by lazy { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }
    private val jobScheduler by lazy { context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler }
    private val activityManager by lazy { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    
    fun startKeepAlive() {
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Starting keep-alive")
            ensureForegroundService()
            scheduleWatchdogJob()
            scheduleAlarmWakeUp()
        }
    }
    
    fun stopKeepAlive() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping keep-alive")
            jobScheduler.cancel(WATCHDOG_JOB_ID)
            cancelAlarms()
        }
    }
    
    fun ensureForegroundService() {
        try {
            if (!isServiceRunning(ClipboardMonitorService::class.java)) {
                Log.i(TAG, "Starting foreground service")
                ClipboardMonitorService.startService(context)
            } else {
                Log.d(TAG, "Foreground service is already running")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure foreground service", e)
        }
    }
    
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val services = activityManager.getRunningServices(Int.MAX_VALUE)
            services.any { it.service.className == serviceClass.name }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun scheduleWatchdogJob() {
        try {
            val componentName = ComponentName(context, KeepAliveJobService::class.java)
            
            val jobInfo = JobInfo.Builder(WATCHDOG_JOB_ID, componentName)
                .setPeriodic(JOB_INTERVAL_MS)
                .setPersisted(true)
                .build()
            
            val result = jobScheduler.schedule(jobInfo)
            Log.i(TAG, "Watchdog job scheduled: ${result == JobScheduler.RESULT_SUCCESS}")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling watchdog job", e)
        }
    }
    
    private fun scheduleAlarmWakeUp() {
        try {
            val intent = Intent(context, KeepAliveReceiver::class.java).apply {
                action = KeepAliveReceiver.ACTION_KEEP_ALIVE
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_KEEP_ALIVE_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerTime = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            
            Log.i(TAG, "Alarm scheduled for ${ALARM_INTERVAL_MS / 1000 / 60} minutes")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling alarm", e)
        }
    }
    
    private fun cancelAlarms() {
        try {
            val intent = Intent(context, KeepAliveReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_KEEP_ALIVE_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling alarms", e)
        }
    }
    
    fun checkAccessibilityService(): Boolean {
        return ClipboardAccessibilityService.isServiceRunning()
    }
    
    fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val serviceName = "${context.packageName}/${ClipboardAccessibilityService::class.java.canonicalName}"
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.contains(serviceName)
        } catch (e: Exception) {
            false
        }
    }
    
    fun openAccessibilitySettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
        }
    }
    
    fun requestIgnoreBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request battery optimization exemption", e)
        }
    }
    
    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }
}
