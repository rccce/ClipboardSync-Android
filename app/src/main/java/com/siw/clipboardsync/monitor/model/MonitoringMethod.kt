package com.siw.clipboardsync.monitor.model

/**
 * Enumeration of available clipboard monitoring methods.
 * Listed in priority order (highest priority first).
 */
enum class MonitoringMethod {
    /** Root-based native system hooks - highest priority, lowest latency */
    SYSTEM_HOOKS,
    
    /** Xposed/LSPosed framework hooks - high priority, real-time events */
    XPOSED_HOOKS,
    
    /** READ_LOGS permission-based logcat monitoring - medium-high priority */
    READ_LOGS,
    
    /** Shizuku-based monitoring - medium-high priority, works without root */
    SHIZUKU,
    
    /** Accessibility service-based monitoring - medium priority */
    ACCESSIBILITY_SERVICE,
    
    /** Foreground service with polling - medium-low priority */
    FOREGROUND_SERVICE,
    
    /** Adaptive polling fallback - lowest priority */
    POLLING_FALLBACK
}