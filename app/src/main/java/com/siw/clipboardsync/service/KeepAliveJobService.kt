package com.siw.clipboardsync.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.util.Log

/**
 * JobService for periodic keep-alive checks.
 * This service is scheduled by JobScheduler and survives reboots.
 */
class KeepAliveJobService : JobService() {
    
    companion object {
        private const val TAG = "KeepAliveJobService"
    }
    
    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "Keep-alive job started")
        
        try {
            // Check if user is logged in
            val prefs = getSharedPreferences("clipboard_sync_tokens", Context.MODE_PRIVATE)
            val hasToken = prefs.getString("access_token", null) != null
            
            if (hasToken) {
                // Ensure foreground service is running
                val keepAliveManager = KeepAliveManager.getInstance(applicationContext)
                keepAliveManager.ensureForegroundService()
                
                Log.i(TAG, "Foreground service check completed")
            } else {
                Log.d(TAG, "User not logged in, skipping service start")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in keep-alive job", e)
        }
        
        // Job completed synchronously
        return false
    }
    
    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Keep-alive job stopped")
        // Return true to reschedule the job
        return true
    }
}
