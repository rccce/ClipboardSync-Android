package com.siw.clipboardsync.monitor.model

/**
 * Represents a clipboard monitoring strategy with its capabilities and availability.
 */
data class MonitoringStrategy(
    val method: MonitoringMethod,
    val priority: Int,
    val isAvailable: Boolean,
    val capabilities: Set<Capability>
) {
    
    /**
     * Enumeration of monitoring capabilities.
     */
    enum class Capability {
        BACKGROUND_ACCESS,
        REAL_TIME_EVENTS,
        LOW_LATENCY,
        ALL_CONTENT_TYPES,
        SYSTEM_LEVEL_ACCESS
    }
    
    /**
     * Checks if this strategy has a specific capability.
     * @param capability the capability to check
     * @return true if the strategy has the capability, false otherwise
     */
    fun hasCapability(capability: Capability): Boolean {
        return capabilities.contains(capability)
    }
    
    /**
     * Checks if this strategy is better than another strategy.
     * A strategy is considered better if it has higher priority and is available.
     * @param other the other strategy to compare with
     * @return true if this strategy is better, false otherwise
     */
    fun isBetterThan(other: MonitoringStrategy): Boolean {
        if (!isAvailable) return false
        if (!other.isAvailable) return true
        return priority > other.priority
    }
}