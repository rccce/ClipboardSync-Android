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
    val isRootAccessible: Boolean = false
) {
    enum class RootMethod {
        NONE,
        MAGISK,
        KERNELSU,
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
}