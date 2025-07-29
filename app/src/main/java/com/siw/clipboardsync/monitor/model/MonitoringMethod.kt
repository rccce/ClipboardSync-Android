package com.siw.clipboardsync.monitor.model

/**
 * Enumeration of available clipboard monitoring methods.
 */
enum class MonitoringMethod {
    SYSTEM_HOOKS,
    XPOSED_HOOKS,
    ACCESSIBILITY_SERVICE,
    FOREGROUND_SERVICE,
    POLLING_FALLBACK
}