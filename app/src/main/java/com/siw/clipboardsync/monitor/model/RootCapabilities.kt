package com.siw.clipboardsync.monitor.model

/**
 * Data class representing the root capabilities and system-level access levels
 * available on the current device.
 */
data class RootCapabilities(
    val hasSystemHooks: Boolean = false,
    val hasXposedFramework: Boolean = false,
    val hasNativeAccess: Boolean = false,
    val rootMethod: RootMethod = RootMethod.NONE,
    val suBinaryPath: String? = null,
    val isRootAccessible: Boolean = false,
    // KernelSU specific fields
    val kernelSuVersion: String? = null,
    val kernelSuModuleCount: Int = 0,
    val hasKernelSuManager: Boolean = false,
    // APatch specific fields
    val hasAPatch: Boolean = false,
    val aPatchVersion: String? = null,
    // Detailed capability flags
    val canExecuteRootCommands: Boolean = false,
    val canAccessClipboardService: Boolean = false
) {
    enum class RootMethod {
        NONE,
        MAGISK,
        KERNELSU,
        APATCH,
        SUPERSU,
        KINGROOT,
        OTHER
    }
    
    /**
     * Returns true if any form of root access is available
     */
    val hasRootAccess: Boolean
        get() = isRootAccessible && rootMethod != RootMethod.NONE
    
    /**
     * Returns true if system-level clipboard monitoring is possible
     */
    val canUseSystemLevelMonitoring: Boolean
        get() = hasRootAccess && (hasSystemHooks || hasNativeAccess)
    
    /**
     * Returns true if this is a KernelSU-based root
     */
    val isKernelSuBased: Boolean
        get() = rootMethod == RootMethod.KERNELSU || hasKernelSuManager
    
    /**
     * Returns true if this is an APatch-based root
     */
    val isAPatchBased: Boolean
        get() = rootMethod == RootMethod.APATCH || hasAPatch
    
    /**
     * Returns a human-readable description of the root method
     */
    val rootMethodDescription: String
        get() = when (rootMethod) {
            RootMethod.NONE -> "No root access"
            RootMethod.MAGISK -> "Magisk"
            RootMethod.KERNELSU -> kernelSuVersion?.let { "KernelSU v$it" } ?: "KernelSU"
            RootMethod.APATCH -> aPatchVersion?.let { "APatch v$it" } ?: "APatch"
            RootMethod.SUPERSU -> "SuperSU"
            RootMethod.KINGROOT -> "KingRoot"
            RootMethod.OTHER -> "Other root method"
        }
    
    /**
     * Returns detailed capability information as a map
     */
    fun toDetailedMap(): Map<String, Any?> = mapOf(
        "hasSystemHooks" to hasSystemHooks,
        "hasXposedFramework" to hasXposedFramework,
        "hasNativeAccess" to hasNativeAccess,
        "rootMethod" to rootMethod.name,
        "rootMethodDescription" to rootMethodDescription,
        "suBinaryPath" to suBinaryPath,
        "isRootAccessible" to isRootAccessible,
        "hasRootAccess" to hasRootAccess,
        "canUseSystemLevelMonitoring" to canUseSystemLevelMonitoring,
        "kernelSuVersion" to kernelSuVersion,
        "kernelSuModuleCount" to kernelSuModuleCount,
        "hasKernelSuManager" to hasKernelSuManager,
        "hasAPatch" to hasAPatch,
        "aPatchVersion" to aPatchVersion,
        "canExecuteRootCommands" to canExecuteRootCommands,
        "canAccessClipboardService" to canAccessClipboardService
    )
}