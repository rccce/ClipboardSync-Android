package com.siw.clipboardsync.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
 */
@Singleton
class RootDetectionService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
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
            "/data/local/su"
        )
        
        private val ROOT_APP_PACKAGES = arrayOf(
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.koushikdutta.rommanager",
            "com.koushikdutta.rommanager.license",
            "com.dimonvideo.luckypatcher",
            "com.chelpus.lackypatch",
            "com.ramdroid.appquarantine",
            "com.ramdroid.appquarantinepro",
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu"
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
        
        RootCapabilities(
            hasSystemHooks = checkSystemHookAccess(),
            hasXposedFramework = checkXposedFramework(),
            hasNativeAccess = checkNativeLibraryAccess(),
            rootMethod = rootMethod,
            suBinaryPath = suBinaryPath,
            isRootAccessible = isRootAccessible
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
                        Log.d("RootDetectionService", "Su access test succeeded with command: $command")
                        process.destroy()
                        return@withContext true
                    }
                    process.destroy()
                } catch (e: Exception) {
                    Log.w("RootDetectionService", "Su access test failed for command: $command", e)
                }
            }
            
            Log.w("RootDetectionService", "All su access tests failed")
            false
        } catch (e: Exception) {
            Log.e("RootDetectionService", "Exception during su access test", e)
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
                Log.d("RootDetectionService", "No su access available")
                return@withContext false
            }
            
            // For KernelSU with restricted shell access, we'll use a different approach
            // If we can get root with 'su -c id', we'll consider system hooks available
            val rootProcess = Runtime.getRuntime().exec("su -c 'id'")
            val exitCode = rootProcess.waitFor()
            val output = rootProcess.inputStream.bufferedReader().readText().trim()
            rootProcess.destroy()
            
            if (exitCode == 0 && output.contains("uid=0(root)")) {
                Log.d("RootDetectionService", "Root access confirmed with uid=0, enabling system hooks")
                return@withContext true
            }
            
            // If we get here, we have some form of root but not full system access
            Log.d("RootDetectionService", "Root available but restricted shell access detected")
            false
            
        } catch (e: Exception) {
            Log.e("RootDetectionService", "Error checking system hook access", e)
            false
        }
    }
    
    /**
     * Detects if Xposed or LSPosed framework is available
     */
    private fun checkXposedFramework(): Boolean {
        return try {
            // Check for Xposed framework
            val xposedBridge = Class.forName("de.robv.android.xposed.XposedBridge")
            xposedBridge != null
        } catch (e: ClassNotFoundException) {
            // Check for LSPosed
            try {
                val lsposedBridge = Class.forName("io.github.libxposed.api.XposedInterface")
                lsposedBridge != null
            } catch (e2: ClassNotFoundException) {
                false
            }
        } catch (e: Exception) {
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
                Log.d("RootDetectionService", "No su access for native library check")
                return@withContext false
            }
            
            // For KernelSU with restricted shell access, we'll use a different approach
            // If we can get root with 'su -c id', we'll consider native access available
            val rootProcess = Runtime.getRuntime().exec("su -c 'id'")
            val exitCode = rootProcess.waitFor()
            val output = rootProcess.inputStream.bufferedReader().readText().trim()
            rootProcess.destroy()
            
            if (exitCode == 0 && output.contains("uid=0(root)")) {
                Log.d("RootDetectionService", "Root access confirmed with uid=0, enabling native library access")
                return@withContext true
            }
            
            // If we get here, we have some form of root but not full system access
            Log.d("RootDetectionService", "Root available but restricted shell access detected for native libraries")
            false
            
        } catch (e: Exception) {
            Log.e("RootDetectionService", "Error checking native library access", e)
            false
        }
    }
    
    /**
     * Attempts to detect the specific root method used
     */
    private fun detectRootMethod(): RootCapabilities.RootMethod {
        return when {
            isPackageInstalled("com.topjohnwu.magisk") -> RootCapabilities.RootMethod.MAGISK
            isPackageInstalled("me.weishu.kernelsu") || checkKernelSU() -> RootCapabilities.RootMethod.KERNELSU
            isPackageInstalled("eu.chainfire.supersu") -> RootCapabilities.RootMethod.SUPERSU
            isPackageInstalled("com.kingroot.kinguser") -> RootCapabilities.RootMethod.KINGROOT
            checkSuBinary() || checkBuildTags() -> RootCapabilities.RootMethod.OTHER
            else -> RootCapabilities.RootMethod.NONE
        }
    }
    
    /**
     * Checks for KernelSU specific indicators
     */
    private fun checkKernelSU(): Boolean {
        return try {
            // Check for KernelSU directory
            val ksuDir = File("/data/adb/ksu")
            if (ksuDir.exists() && ksuDir.isDirectory) {
                Log.d("RootDetectionService", "KernelSU directory found: /data/adb/ksu")
                return true
            }
            
            // Check for KernelSU binary
            val ksuBinary = File("/data/adb/ksu/bin/ksud")
            if (ksuBinary.exists() && ksuBinary.canExecute()) {
                Log.d("RootDetectionService", "KernelSU binary found: /data/adb/ksu/bin/ksud")
                return true
            }
            
            // Check for KernelSU rc file
            val ksuRc = File("/data/adb/ksu/.ksurc")
            if (ksuRc.exists()) {
                Log.d("RootDetectionService", "KernelSU rc file found: /data/adb/ksu/.ksurc")
                return true
            }
            
            false
        } catch (e: Exception) {
            Log.w("RootDetectionService", "Error checking KernelSU indicators", e)
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
            Log.i("RootDetectionService", "Explicitly requesting root access...")
            
            // Try multiple approaches to request root access
            val commands = arrayOf(
                "su -c 'echo \"Root access granted successfully\"'",
                "su -c 'id'",
                "su -c 'whoami'"
            )
            
            var accessGranted = false
            
            for ((index, command) in commands.withIndex()) {
                try {
                    Log.d("RootDetectionService", "Attempting root command ${index + 1}: $command")
                    val process = Runtime.getRuntime().exec(command)
                    val exitCode = process.waitFor()
                    
                    if (exitCode == 0) {
                        // Read the output to confirm
                        val output = process.inputStream.bufferedReader().readText().trim()
                        Log.i("RootDetectionService", "Root command succeeded with output: $output")
                        accessGranted = true
                        break
                    } else {
                        Log.w("RootDetectionService", "Root command failed with exit code: $exitCode")
                    }
                    
                    process.destroy()
                } catch (e: Exception) {
                    Log.e("RootDetectionService", "Exception executing root command: $command", e)
                }
            }
            
            if (accessGranted) {
                Log.i("RootDetectionService", "Root access successfully granted!")
            } else {
                Log.w("RootDetectionService", "Root access was not granted or failed")
            }
            
            accessGranted
            
        } catch (e: Exception) {
            Log.e("RootDetectionService", "Error requesting root access", e)
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