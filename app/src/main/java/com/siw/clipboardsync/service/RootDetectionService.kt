package com.siw.clipboardsync.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.siw.clipboardsync.monitor.model.RootCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for detecting root access and assessing available root capabilities
 * on the current Android device.
 * 
 * Supports detection of:
 * - Magisk (traditional and Zygisk)
 * - KernelSU (including version detection)
 * - APatch
 * - SuperSU
 * - KingRoot
 * - Other root methods
 */
@Singleton
class RootDetectionService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "RootDetectionService"
        
        // Standard su binary paths
        private val SU_BINARY_PATHS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/vendor/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            // KernelSU specific paths
            "/data/adb/ksu/bin/su",
            "/data/adb/ksud",
            // APatch specific paths
            "/data/adb/ap/bin/su",
            "/data/adb/apd"
        )
        
        // KernelSU specific paths
        private val KERNELSU_PATHS = arrayOf(
            "/data/adb/ksu",
            "/data/adb/ksu/bin/ksud",
            "/data/adb/ksu/bin/su",
            "/data/adb/ksu/.ksurc",
            "/data/adb/ksu/modules"
        )
        
        // APatch specific paths
        private val APATCH_PATHS = arrayOf(
            "/data/adb/ap",
            "/data/adb/apd",
            "/data/adb/ap/bin/su",
            "/data/adb/ap/modules"
        )
        
        // Magisk specific paths
        private val MAGISK_PATHS = arrayOf(
            "/data/adb/magisk",
            "/sbin/.magisk",
            "/data/adb/magisk.db",
            "/data/adb/modules"
        )
        
        private val ROOT_APP_PACKAGES = arrayOf(
            // Magisk
            "com.topjohnwu.magisk",
            // KernelSU managers
            "me.weishu.kernelsu",
            "me.bmax.apatch",
            // SuperSU
            "eu.chainfire.supersu",
            // Other root apps
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.koushikdutta.rommanager",
            "com.koushikdutta.rommanager.license",
            "com.dimonvideo.luckypatcher",
            "com.chelpus.lackypatch",
            "com.ramdroid.appquarantine",
            "com.ramdroid.appquarantinepro",
            "com.kingroot.kinguser"
        )
        
        private val ROOT_BUILD_TAGS = arrayOf(
            "test-keys"
        )
    }
    
    /**
     * Checks if the device is rooted using multiple detection methods
     */
    suspend fun isRooted(): Boolean = withContext(Dispatchers.IO) {
        checkSuBinary() || checkRootApps() || checkBuildTags()
    }
    
    /**
     * Comprehensive assessment of available root capabilities
     */
    suspend fun getRootCapabilities(): RootCapabilities = withContext(Dispatchers.IO) {
        val suBinaryPath = findSuBinary()
        val isRootAccessible = suBinaryPath != null && testSuAccess()
        val rootMethod = detectRootMethod()
        
        // KernelSU specific detection
        val kernelSuInfo = detectKernelSuDetails()
        
        // APatch specific detection
        val aPatchInfo = detectAPatchDetails()
        
        // Test specific capabilities
        val canExecuteRootCommands = isRootAccessible && testRootCommandExecution()
        val canAccessClipboardService = isRootAccessible && testClipboardServiceAccess()
        val canReadSystemLogs = checkReadLogsCapability()
        val hasReadLogsPermission = checkReadLogsPermission()
        
        RootCapabilities(
            hasSystemHooks = checkSystemHookAccess(),
            hasXposedFramework = checkXposedFramework(),
            hasNativeAccess = checkNativeLibraryAccess(),
            rootMethod = rootMethod,
            suBinaryPath = suBinaryPath,
            isRootAccessible = isRootAccessible,
            // KernelSU specific
            kernelSuVersion = kernelSuInfo["version"] as? String,
            kernelSuModuleCount = kernelSuInfo["moduleCount"] as? Int ?: 0,
            hasKernelSuManager = isPackageInstalled("me.weishu.kernelsu"),
            // APatch specific
            hasAPatch = aPatchInfo["detected"] as? Boolean ?: false,
            aPatchVersion = aPatchInfo["version"] as? String,
            // Detailed capabilities
            canExecuteRootCommands = canExecuteRootCommands,
            canAccessClipboardService = canAccessClipboardService,
            canReadSystemLogs = canReadSystemLogs,
            hasReadLogsPermission = hasReadLogsPermission
        )
    }
    
    /**
     * Checks for the presence of su binary in common locations
     */
    fun checkSuBinary(): Boolean {
        return SU_BINARY_PATHS.any { path ->
            try {
                val file = File(path)
                file.exists() && file.canExecute()
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Checks for installed root management applications
     */
    fun checkRootApps(): Boolean {
        return ROOT_APP_PACKAGES.any { packageName ->
            try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
    
    /**
     * Checks build tags that indicate a rooted or custom ROM
     */
    fun checkBuildTags(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && ROOT_BUILD_TAGS.any { tag ->
            buildTags.contains(tag)
        }
    }
    
    /**
     * Finds the path to su binary if available
     */
    private fun findSuBinary(): String? {
        return SU_BINARY_PATHS.find { path ->
            try {
                val file = File(path)
                file.exists() && file.canExecute()
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Tests if su access is actually functional
     */
    private suspend fun testSuAccess(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            // Use the same approach as requestRootAccess() which we know works
            val commands = arrayOf(
                "su -c 'echo test'",
                "su -c 'id'",
                "su -c 'whoami'"
            )
            
            for (command in commands) {
                try {
                    val process = Runtime.getRuntime().exec(command)
                    val exitCode = process.waitFor()
                    if (exitCode == 0) {
                        Log.d(TAG, "Su access test succeeded with command: $command")
                        process.destroy()
                        return@withContext true
                    }
                    process.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Su access test failed for command: $command", e)
                }
            }
            
            Log.w(TAG, "All su access tests failed")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception during su access test", e)
            false
        }
    }
    
    /**
     * Checks if system-level hooks can be installed
     */
    private suspend fun checkSystemHookAccess(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            // First check if we have root access at all
            if (!testSuAccess()) {
                Log.d(TAG, "No su access available")
                return@withContext false
            }
            
            // For KernelSU with restricted shell access, we'll use a different approach
            // If we can get root with 'su -c id', we'll consider system hooks available
            val rootProcess = Runtime.getRuntime().exec("su -c 'id'")
            val exitCode = rootProcess.waitFor()
            val output = rootProcess.inputStream.bufferedReader().readText().trim()
            rootProcess.destroy()
            
            if (exitCode == 0 && output.contains("uid=0(root)")) {
                Log.d(TAG, "Root access confirmed with uid=0, enabling system hooks")
                return@withContext true
            }
            
            // If we get here, we have some form of root but not full system access
            Log.d(TAG, "Root available but restricted shell access detected")
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking system hook access", e)
            false
        }
    }
    
    /**
     * Detects if Xposed or LSPosed framework is available AND our app is actually hooked.
     * 
     * IMPORTANT: Just detecting that LSPosed is installed is NOT enough for clipboard monitoring.
     * We need to verify that our app is actually being hooked by the Xposed module.
     * 
     * This method returns true ONLY if:
     * 1. Xposed/LSPosed framework is installed AND
     * 2. Our app is actually running in an Xposed environment (being hooked)
     */
    private fun checkXposedFramework(): Boolean {
        // First, check if we're actually running in an Xposed environment
        // This is the most reliable indicator that our app is being hooked
        
        // Method 1: Check system properties set by Xposed when hooking our app
        try {
            val xposedBridgeVersion = System.getProperty("xposed.bridge.version")
            if (xposedBridgeVersion != null) {
                Log.d(TAG, "Xposed bridge version detected: $xposedBridgeVersion - app is hooked")
                return true
            }
            
            val lsposedBridgeVersion = System.getProperty("lsposed.bridge.version")
            if (lsposedBridgeVersion != null) {
                Log.d(TAG, "LSPosed bridge version detected: $lsposedBridgeVersion - app is hooked")
                return true
            }
        } catch (e: Exception) {
            // Properties not available
        }
        
        // Method 2: Check if XposedBridge class is loaded in our process
        // This only works if our app is actually being hooked
        try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            Log.d(TAG, "XposedBridge class detected in our process - app is hooked")
            return true
        } catch (e: ClassNotFoundException) {
            // Not loaded - our app is not being hooked by traditional Xposed
        }
        
        // Method 3: Check for LSPosed API class
        try {
            Class.forName("io.github.libxposed.api.XposedInterface")
            Log.d(TAG, "LSPosed API class detected in our process - app is hooked")
            return true
        } catch (e: ClassNotFoundException) {
            // Not loaded - our app is not being hooked by LSPosed
        }
        
        // Method 4: Check for our custom hook indicator
        try {
            val hookIndicator = System.getProperty("clipboard.sync.hooked")
            if (hookIndicator == "true") {
                Log.d(TAG, "Custom hook indicator found - app is hooked")
                return true
            }
        } catch (e: Exception) {
            // Property not available
        }
        
        // If we reach here, Xposed framework might be installed but our app is NOT being hooked
        // Log this for debugging purposes
        val frameworkInstalled = isXposedFrameworkInstalled()
        if (frameworkInstalled) {
            Log.w(TAG, "Xposed/LSPosed framework is INSTALLED but our app is NOT being hooked. " +
                    "Make sure the clipboard hook module is enabled in LSPosed Manager " +
                    "and our app (com.siw.clipboardsync) is in the module's scope.")
        } else {
            Log.d(TAG, "No Xposed/LSPosed framework detected")
        }
        
        return false
    }
    
    /**
     * Checks if Xposed/LSPosed framework is installed (but not necessarily hooking our app).
     * This is a separate check from checkXposedFramework() which verifies actual hooking.
     */
    private fun isXposedFrameworkInstalled(): Boolean {
        // Check for LSPosed manager app
        if (isPackageInstalled("org.lsposed.manager")) {
            return true
        }
        
        // Check for EdXposed manager
        if (isPackageInstalled("org.meowcat.edxposed.manager") || 
            isPackageInstalled("com.solohsu.android.edxp.manager")) {
            return true
        }
        
        // Check for traditional Xposed installer
        if (isPackageInstalled("de.robv.android.xposed.installer")) {
            return true
        }
        
        // Check for LSPosed module directories (with root)
        val lsposedPaths = arrayOf(
            "/data/adb/lspd",
            "/data/adb/modules/zygisk_lsposed",
            "/data/adb/modules/riru_lsposed"
        )
        for (path in lsposedPaths) {
            if (java.io.File(path).exists() || checkPathExistsWithRoot(path)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * Check if a path exists using root command
     */
    private fun checkPathExistsWithRoot(path: String): Boolean {
        return try {
            // Use ls command which is more reliable than test
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls $path"))
            val exitCode = process.waitFor()
            process.destroy()
            exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "Error checking path with root: $path", e)
            false
        }
    }
    
    /**
     * Checks if native library access is available for JNI hooks
     */
    private suspend fun checkNativeLibraryAccess(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            // First check if we have root access at all
            if (!testSuAccess()) {
                Log.d(TAG, "No su access for native library check")
                return@withContext false
            }
            
            // For KernelSU with restricted shell access, we'll use a different approach
            // If we can get root with 'su -c id', we'll consider native access available
            val rootProcess = Runtime.getRuntime().exec("su -c 'id'")
            val exitCode = rootProcess.waitFor()
            val output = rootProcess.inputStream.bufferedReader().readText().trim()
            rootProcess.destroy()
            
            if (exitCode == 0 && output.contains("uid=0(root)")) {
                Log.d(TAG, "Root access confirmed with uid=0, enabling native library access")
                return@withContext true
            }
            
            // If we get here, we have some form of root but not full system access
            Log.d(TAG, "Root available but restricted shell access detected for native libraries")
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking native library access", e)
            false
        }
    }
    
    /**
     * Attempts to detect the specific root method used
     */
    private fun detectRootMethod(): RootCapabilities.RootMethod {
        return when {
            // Check KernelSU first (more specific)
            isPackageInstalled("me.weishu.kernelsu") || checkKernelSU() -> RootCapabilities.RootMethod.KERNELSU
            // Check APatch
            isPackageInstalled("me.bmax.apatch") || checkAPatch() -> RootCapabilities.RootMethod.APATCH
            // Check Magisk
            isPackageInstalled("com.topjohnwu.magisk") || checkMagisk() -> RootCapabilities.RootMethod.MAGISK
            // Check SuperSU
            isPackageInstalled("eu.chainfire.supersu") -> RootCapabilities.RootMethod.SUPERSU
            // Check KingRoot
            isPackageInstalled("com.kingroot.kinguser") -> RootCapabilities.RootMethod.KINGROOT
            // Generic root detection
            checkSuBinary() || checkBuildTags() -> RootCapabilities.RootMethod.OTHER
            else -> RootCapabilities.RootMethod.NONE
        }
    }
    
    /**
     * Checks for KernelSU specific indicators
     */
    private fun checkKernelSU(): Boolean {
        return try {
            // Check for KernelSU paths
            for (path in KERNELSU_PATHS) {
                val file = File(path)
                if (file.exists()) {
                    Log.d(TAG, "KernelSU indicator found: $path")
                    return true
                }
            }
            
            // Try to detect via kernel version string
            try {
                val kernelVersion = System.getProperty("os.version") ?: ""
                if (kernelVersion.contains("ksu", ignoreCase = true) || 
                    kernelVersion.contains("kernelsu", ignoreCase = true)) {
                    Log.d(TAG, "KernelSU detected in kernel version: $kernelVersion")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking kernel version for KernelSU", e)
            }
            
            // Try to execute ksud command
            try {
                val process = Runtime.getRuntime().exec("ksud --version")
                val exitCode = process.waitFor()
                process.destroy()
                if (exitCode == 0) {
                    Log.d(TAG, "KernelSU detected via ksud command")
                    return true
                }
            } catch (e: Exception) {
                // ksud not available
            }
            
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking KernelSU indicators", e)
            false
        }
    }
    
    /**
     * Detects detailed KernelSU information
     */
    private fun detectKernelSuDetails(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        
        try {
            // Check if KernelSU is present
            if (!checkKernelSU() && !isPackageInstalled("me.weishu.kernelsu")) {
                return result
            }
            
            // Try to get KernelSU version
            try {
                val process = Runtime.getRuntime().exec("su -v")
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    val output = process.inputStream.bufferedReader().readText().trim()
                    if (output.contains("KernelSU", ignoreCase = true) || output.contains("ksu", ignoreCase = true)) {
                        // Extract version number
                        val versionMatch = Regex("(\\d+\\.\\d+\\.?\\d*)").find(output)
                        result["version"] = versionMatch?.value ?: output
                    }
                }
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error getting KernelSU version", e)
            }
            
            // Count installed modules
            try {
                val modulesDir = File("/data/adb/ksu/modules")
                if (modulesDir.exists() && modulesDir.isDirectory) {
                    val moduleCount = modulesDir.listFiles()?.count { it.isDirectory } ?: 0
                    result["moduleCount"] = moduleCount
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error counting KernelSU modules", e)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting KernelSU details", e)
        }
        
        return result
    }
    
    /**
     * Checks for APatch specific indicators
     */
    private fun checkAPatch(): Boolean {
        return try {
            // Check for APatch paths
            for (path in APATCH_PATHS) {
                val file = File(path)
                if (file.exists()) {
                    Log.d(TAG, "APatch indicator found: $path")
                    return true
                }
            }
            
            // Try to execute apd command
            try {
                val process = Runtime.getRuntime().exec("apd --version")
                val exitCode = process.waitFor()
                process.destroy()
                if (exitCode == 0) {
                    Log.d(TAG, "APatch detected via apd command")
                    return true
                }
            } catch (e: Exception) {
                // apd not available
            }
            
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking APatch indicators", e)
            false
        }
    }
    
    /**
     * Detects detailed APatch information
     */
    private fun detectAPatchDetails(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        
        try {
            val detected = checkAPatch() || isPackageInstalled("me.bmax.apatch")
            result["detected"] = detected
            
            if (!detected) {
                return result
            }
            
            // Try to get APatch version
            try {
                val process = Runtime.getRuntime().exec("apd --version")
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    val output = process.inputStream.bufferedReader().readText().trim()
                    val versionMatch = Regex("(\\d+\\.\\d+\\.?\\d*)").find(output)
                    result["version"] = versionMatch?.value ?: output
                }
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error getting APatch version", e)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting APatch details", e)
        }
        
        return result
    }
    
    /**
     * Checks for Magisk specific indicators
     */
    private fun checkMagisk(): Boolean {
        return try {
            // Check for Magisk paths
            for (path in MAGISK_PATHS) {
                val file = File(path)
                if (file.exists()) {
                    Log.d(TAG, "Magisk indicator found: $path")
                    return true
                }
            }
            
            // Try to execute magisk command
            try {
                val process = Runtime.getRuntime().exec("magisk -v")
                val exitCode = process.waitFor()
                process.destroy()
                if (exitCode == 0) {
                    Log.d(TAG, "Magisk detected via magisk command")
                    return true
                }
            } catch (e: Exception) {
                // magisk not available
            }
            
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Magisk indicators", e)
            false
        }
    }
    
    /**
     * Tests if root commands can be executed successfully
     */
    private suspend fun testRootCommandExecution(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val process = Runtime.getRuntime().exec("su -c 'echo root_test'")
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.destroy()
            exitCode == 0 && output.contains("root_test")
        } catch (e: Exception) {
            Log.w(TAG, "Root command execution test failed", e)
            false
        }
    }
    
    /**
     * Tests if clipboard service can be accessed via root
     */
    private suspend fun testClipboardServiceAccess(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            // Try service call clipboard
            val process = Runtime.getRuntime().exec("su -c 'service call clipboard 1'")
            val exitCode = process.waitFor()
            process.destroy()
            
            if (exitCode == 0) {
                Log.d(TAG, "Clipboard service access via root confirmed")
                return@withContext true
            }
            
            // Try dumpsys clipboard
            val process2 = Runtime.getRuntime().exec("su -c 'dumpsys clipboard'")
            val exitCode2 = process2.waitFor()
            process2.destroy()
            
            exitCode2 == 0
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard service access test failed", e)
            false
        }
    }
    
    /**
     * Checks if READ_LOGS permission is granted
     */
    private fun checkReadLogsPermission(): Boolean {
        return try {
            ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.READ_LOGS
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Error checking READ_LOGS permission", e)
            false
        }
    }
    
    /**
     * Checks if we can actually read system logs
     */
    private suspend fun checkReadLogsCapability(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            // First check if permission is granted
            if (!checkReadLogsPermission()) {
                return@withContext false
            }
            
            // Try to read logcat
            val process = Runtime.getRuntime().exec("logcat -d -t 1")
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            process.destroy()
            
            exitCode == 0 && output.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Read logs capability test failed", e)
            false
        }
    }
    
    /**
     * Helper method to check if a package is installed
     */
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * Explicitly requests root access from the user.
     * This will trigger the root permission dialog if not already granted.
     * @return true if root access was granted, false otherwise
     */
    suspend fun requestRootAccess(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.i(TAG, "Explicitly requesting root access...")
            
            // Try multiple approaches to request root access
            val commands = arrayOf(
                "su -c 'echo \"Root access granted successfully\"'",
                "su -c 'id'",
                "su -c 'whoami'"
            )
            
            var accessGranted = false
            
            for ((index, command) in commands.withIndex()) {
                try {
                    Log.d(TAG, "Attempting root command ${index + 1}: $command")
                    val process = Runtime.getRuntime().exec(command)
                    val exitCode = process.waitFor()
                    
                    if (exitCode == 0) {
                        // Read the output to confirm
                        val output = process.inputStream.bufferedReader().readText().trim()
                        Log.i(TAG, "Root command succeeded with output: $output")
                        accessGranted = true
                        break
                    } else {
                        Log.w(TAG, "Root command failed with exit code: $exitCode")
                    }
                    
                    process.destroy()
                } catch (e: Exception) {
                    Log.e(TAG, "Exception executing root command: $command", e)
                }
            }
            
            if (accessGranted) {
                Log.i(TAG, "Root access successfully granted!")
            } else {
                Log.w(TAG, "Root access was not granted or failed")
            }
            
            accessGranted
            
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting root access", e)
            false
        }
    }
    
    /**
     * Tests root access with detailed logging for debugging
     */
    suspend fun testRootAccessDetailed(): Map<String, Any> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Any>()
        
        try {
            // Test basic root detection
            results["deviceRooted"] = isRooted()
            results["suBinaryExists"] = checkSuBinary()
            results["rootAppsInstalled"] = checkRootApps()
            results["buildTagsIndicate"] = checkBuildTags()
            
            // Test actual su access
            val suTestResult = try {
                val process = Runtime.getRuntime().exec("su -c 'echo test'")
                val exitCode = process.waitFor()
                val output = if (exitCode == 0) {
                    process.inputStream.bufferedReader().readText().trim()
                } else {
                    process.errorStream.bufferedReader().readText().trim()
                }
                process.destroy()
                
                mapOf(
                    "exitCode" to exitCode,
                    "output" to output,
                    "success" to (exitCode == 0)
                )
            } catch (e: Exception) {
                mapOf(
                    "exitCode" to -1,
                    "output" to e.message,
                    "success" to false,
                    "exception" to e.javaClass.simpleName
                )
            }
            
            results["suAccessTest"] = suTestResult
            
            // Get full capabilities
            val capabilities = getRootCapabilities()
            results["capabilities"] = mapOf(
                "hasSystemHooks" to capabilities.hasSystemHooks,
                "hasXposedFramework" to capabilities.hasXposedFramework,
                "hasNativeAccess" to capabilities.hasNativeAccess,
                "rootMethod" to capabilities.rootMethod.name,
                "suBinaryPath" to capabilities.suBinaryPath,
                "isRootAccessible" to capabilities.isRootAccessible,
                "hasRootAccess" to capabilities.hasRootAccess,
                "canUseSystemLevelMonitoring" to capabilities.canUseSystemLevelMonitoring
            )
            
        } catch (e: Exception) {
            results["error"] = e.message ?: "Unknown error"
            results["exception"] = e.javaClass.simpleName
        }
        
        return@withContext results
    }
}