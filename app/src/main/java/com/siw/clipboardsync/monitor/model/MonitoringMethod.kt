package com.siw.clipboardsync.monitor.model

/**
 * Enumeration of available clipboard monitoring methods.
 * Listed in priority order (highest priority first).
 * 
 * Based on real-world testing:
 * - XPOSED_HOOKS: Full background sync capability
 * - SHIZUKU: Full background sync capability  
 * - FOREGROUND_SYNC: Sync only when app comes to foreground (for non-root/non-shizuku devices)
 * 
 * Note: The following methods were removed due to testing showing they don't work for background sync:
 * - SYSTEM_HOOKS: Requires switching to app to sync
 * - READ_LOGS: Cannot achieve background clipboard sync
 * - ACCESSIBILITY_SERVICE: Cannot achieve background clipboard sync (kept only for keep-alive)
 * - POLLING_FALLBACK: Removed, replaced by FOREGROUND_SYNC
 */
enum class MonitoringMethod {
    /** Xposed/LSPosed framework hooks - highest priority, full background sync */
    XPOSED_HOOKS,
    
    /** Shizuku-based monitoring - high priority, full background sync without root */
    SHIZUKU,
    
    /** Foreground sync - sync clipboard when app comes to foreground (fallback for non-root devices) */
    FOREGROUND_SYNC
}