package com.siw.clipboardsync.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Utility class for LSPosed detection and module status checking
 */
object LSPosedUtils {
    
    private const val TAG = "LSPosedUtils"
    
    /**
     * Check if LSPosed framework is installed and active
     * This method is more conservative and only returns true for reliable indicators
     */
    suspend fun isLSPosedActive(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "=== LSPosed Detection Debug ===")
            
            // Primary method: Check for core LSPosed daemon paths (most reliable)
            val coreLSPosedPaths = arrayOf(
                "/data/adb/lspd",
                "/data/adb/modules/zygisk_lsposed",
                "/data/adb/modules/riru_lsposed"
            )
            
            var corePathFound = false
            for (path in coreLSPosedPaths) {
                val exists = File(path).exists()
                Log.d(TAG, "Checking core LSPosed path: $path - exists: $exists")
                if (exists) {
                    Log.i(TAG, "LSPosed core detected at: $path")
                    corePathFound = true
                    break // One core path is enough
                }
            }
            
            // Secondary method: Check for LSPosed-specific properties (not generic Xposed)
            val lsposedSpecificProps = arrayOf(
                "ro.lsposed.version", 
                "ro.lsposed.api_version"
            )
            
            var lsposedPropFound = false
            for (prop in lsposedSpecificProps) {
                val value = getSystemProperty(prop)
                Log.d(TAG, "LSPosed property $prop = $value")
                if (!value.isNullOrBlank() && value != "0") {
                    Log.i(TAG, "LSPosed-specific property found: $prop = $value")
                    lsposedPropFound = true
                    break
                }
            }
            
            // Tertiary method: Check for LSPosed manager process (if accessible)
            val managerRunning = isLSPosedManagerRunning()
            Log.d(TAG, "LSPosed Manager running: $managerRunning")
            
            // LSPosed is considered active if we find core paths OR specific properties
            val result = corePathFound || lsposedPropFound || managerRunning
            Log.i(TAG, "=== LSPosed Detection Result: $result ===")
            Log.i(TAG, "Core paths found: $corePathFound, LSPosed props: $lsposedPropFound, Manager running: $managerRunning")
            
            return@withContext result
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking LSPosed status", e)
            false
        }
    }
    
    /**
     * Check if our Xposed module is active in LSPosed
     * This method prioritizes actual hook functionality over configuration files
     */
    suspend fun isModuleActive(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "=== Module Detection Debug ===")
            
            // Primary method: Check if hook is actually working (most reliable)
            val hookWorking = isHookWorking()
            Log.d(TAG, "Hook working test: $hookWorking")
            
            // Secondary method: Check if we're in Xposed environment (runtime check)
            val inXposedEnv = isRunningInXposedEnvironment()
            Log.d(TAG, "Running in Xposed environment: $inXposedEnv")
            
            // Tertiary method: Check module configuration files (less reliable due to permissions)
            val moduleConfigured = isModuleConfigured()
            Log.d(TAG, "Module configured in LSPosed: $moduleConfigured")
            
            // Module is considered active if hooks are working OR we're in Xposed environment
            // Configuration file check is supplementary
            val result = hookWorking || (inXposedEnv && moduleConfigured)
            
            Log.i(TAG, "=== Module Detection Result: $result ===")
            Log.i(TAG, "Hook working: $hookWorking, Xposed env: $inXposedEnv, Configured: $moduleConfigured")
            
            return@withContext result
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking module status", e)
            false
        }
    }
    
    /**
     * Get LSPosed version if available
     */
    suspend fun getLSPosedVersion(): String? = withContext(Dispatchers.IO) {
        try {
            // Try to read version from LSPosed files
            val versionFiles = arrayOf(
                "/data/adb/lspd/version",
                "/system/framework/lspd/version"
            )
            
            for (file in versionFiles) {
                val versionFile = File(file)
                if (versionFile.exists()) {
                    return@withContext versionFile.readText().trim()
                }
            }
            
            // Try system property
            getSystemProperty("ro.lsposed.version")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting LSPosed version", e)
            null
        }
    }
    
    /**
     * Get recommended LSPosed configuration for our module
     */
    fun getRecommendedConfiguration(): LSPosedConfiguration {
        return LSPosedConfiguration(
            targetScope = "System Framework (android)",
            targetPackages = listOf("android"),
            requiredPermissions = listOf(
                "android.permission.WRITE_SECURE_SETTINGS",
                "android.permission.INTERACT_ACROSS_USERS"
            ),
            hookPoints = listOf(
                "com.android.server.clipboard.ClipboardService.setPrimaryClip"
            ),
            description = "Enable this module for System Framework (android) to hook clipboard operations"
        )
    }
    
    /**
     * Check if we're running in Xposed environment
     */
    private fun isRunningInXposedEnvironment(): Boolean {
        return try {
            // Check for Xposed-specific system properties
            val xposedProps = arrayOf(
                "xposed.bridge.version",
                "ro.xposed.framework.version"
            )
            
            for (prop in xposedProps) {
                val value = getSystemProperty(prop)
                if (!value.isNullOrBlank()) {
                    Log.d(TAG, "Xposed environment property found: $prop = $value")
                    return true
                }
            }
            
            // Check for Xposed environment variable
            val xposedEnv = System.getenv("XPOSED_ENVIRONMENT")
            if (!xposedEnv.isNullOrBlank()) {
                Log.d(TAG, "Xposed environment variable found: $xposedEnv")
                return true
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Xposed environment", e)
            false
        }
    }
    
    /**
     * Test if the hook is actually working by checking for our marker
     */
    private suspend fun isHookWorking(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Create a test file that our hook should create when active
            val testMarkerFile = File("/data/data/com.siw.clipboardsync/hook_test_marker")
            
            Log.d(TAG, "Checking hook marker at: ${testMarkerFile.absolutePath}")
            Log.d(TAG, "Hook marker exists: ${testMarkerFile.exists()}")
            
            // If the marker exists and is recent (within last 24 hours), hook is working
            if (testMarkerFile.exists()) {
                val lastModified = testMarkerFile.lastModified()
                val now = System.currentTimeMillis()
                val hoursSinceModified = (now - lastModified) / (1000 * 60 * 60)
                
                Log.d(TAG, "Hook marker last modified: $hoursSinceModified hours ago")
                
                if (hoursSinceModified < 24) {
                    Log.i(TAG, "Hook test marker found and recent")
                    return@withContext true
                }
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error testing hook effectiveness", e)
            false
        }
    }
    
    /**
     * Check if LSPosed Manager is running (process-based detection)
     */
    private fun isLSPosedManagerRunning(): Boolean {
        return try {
            // Check for LSPosed manager processes
            val process = Runtime.getRuntime().exec("ps -A")
            val reader = process.inputStream.bufferedReader()
            val output = reader.readText()
            process.waitFor()
            
            val lsposedProcesses = arrayOf(
                "lsposed",
                "lspd",
                "org.lsposed.manager"
            )
            
            for (processName in lsposedProcesses) {
                if (output.contains(processName)) {
                    Log.d(TAG, "LSPosed process found: $processName")
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking LSPosed manager process", e)
            false
        }
    }
    
    /**
     * Check if module is configured in LSPosed (configuration files)
     */
    private fun isModuleConfigured(): Boolean {
        return try {
            // Check multiple possible module list locations
            val moduleListPaths = arrayOf(
                "/data/adb/lspd/config/modules.list",
                "/data/adb/lspd/modules.list", 
                "/data/data/org.lsposed.manager/conf/modules.list",
                "/data/data/io.github.lsposed.manager/conf/modules.list"
            )
            
            for (path in moduleListPaths) {
                val file = File(path)
                Log.d(TAG, "Checking module list: $path - exists: ${file.exists()}")
                if (file.exists()) {
                    try {
                        val content = file.readText()
                        if (content.contains("com.siw.clipboardsync")) {
                            Log.i(TAG, "Module found in LSPosed modules list at: $path")
                            return true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read module list at $path: ${e.message}")
                    }
                }
            }
            
            // Check system properties as backup
            val moduleProps = arrayOf(
                "lsposed.module.com.siw.clipboardsync.active",
                "xposed.module.com.siw.clipboardsync.active"
            )
            
            for (prop in moduleProps) {
                val value = getSystemProperty(prop)
                if (value == "true" || value == "1") {
                    Log.i(TAG, "Module active via property: $prop = $value")
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking module configuration", e)
            false
        }
    }
    
    /**
     * Check if Xposed Bridge is available
     */
    private fun isXposedBridgeAvailable(): Boolean {
        return try {
            // Check for Xposed environment variable
            val xposedEnv = System.getenv("XPOSED_ENVIRONMENT")
            if (!xposedEnv.isNullOrBlank()) {
                Log.d(TAG, "Xposed environment found: $xposedEnv")
                return true
            }
            
            // Try to find XposedBridge class
            try {
                Class.forName("de.robv.android.xposed.XposedBridge")
                Log.d(TAG, "XposedBridge class found")
                return true
            } catch (e: ClassNotFoundException) {
                Log.d(TAG, "XposedBridge class not found")
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Xposed Bridge", e)
            false
        }
    }
    
    /**
     * Check if LSPosed API is available (runtime check)
     */
    private fun isLSPosedAPIAvailable(): Boolean {
        return try {
            // Try to access LSPosed API classes
            Class.forName("de.robv.android.xposed.XposedBridge")
            Class.forName("de.robv.android.xposed.XposedHelpers")
            true
        } catch (e: ClassNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get system property value
     */
    private fun getSystemProperty(key: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val result = process.inputStream.bufferedReader().readLine()
            process.waitFor()
            result?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Data class for LSPosed configuration recommendations
     */
    data class LSPosedConfiguration(
        val targetScope: String,
        val targetPackages: List<String>,
        val requiredPermissions: List<String>,
        val hookPoints: List<String>,
        val description: String
    )
}