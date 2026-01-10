package com.siw.clipboardsync.presentation.monitoring

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siw.clipboardsync.monitor.ClipboardMonitorManager
import com.siw.clipboardsync.monitor.CpuUsageTracker
import com.siw.clipboardsync.monitor.model.ClipboardError
import com.siw.clipboardsync.monitor.model.MonitoringConfig
import com.siw.clipboardsync.monitor.model.MonitoringMethod
import com.siw.clipboardsync.service.RootDetectionService
import com.siw.clipboardsync.utils.AccessibilityPermissionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MonitoringSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monitorManager: ClipboardMonitorManager,
    private val rootDetectionService: RootDetectionService,
    private val accessibilityPermissionManager: AccessibilityPermissionManager,
    private val cpuUsageTracker: CpuUsageTracker
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MonitoringSettingsUiState())
    val uiState: StateFlow<MonitoringSettingsUiState> = _uiState.asStateFlow()
    
    private var monitoringConfig = MonitoringConfig.default()
    
    init {
        loadInitialState()
        observeMonitoringStatus()
    }
    
    private fun loadInitialState() {
        viewModelScope.launch {
            try {
                // Initialize monitor manager if needed
                monitorManager.initialize()
                
                // Get current monitoring status
                val status = monitorManager.getMonitoringStatus()
                
                // Determine available methods based on device capabilities
                val availableMethods = determineAvailableMethods()
                
                // Load current configuration
                val enabledMethods = MonitoringMethod.values().associateWith { method ->
                    monitoringConfig.isMethodEnabled(method) && availableMethods.contains(method)
                }
                
                _uiState.value = _uiState.value.copy(
                    isMonitoring = status.isMonitoring,
                    currentMethod = status.currentMethod,
                    availableMethods = availableMethods,
                    enabledMethods = enabledMethods,
                    preferredMethod = monitoringConfig.preferredMethod,
                    autoFallback = monitoringConfig.autoFallback,
                    enableNotifications = monitoringConfig.enableNotifications,
                    enableErrorRecovery = monitoringConfig.enableErrorRecovery,
                    isLoading = false
                )
                
                // Load performance metrics
                refreshMetrics()
                
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    lastError = e.message ?: "Failed to load monitoring settings"
                )
            }
        }
    }
    
    private fun observeMonitoringStatus() {
        viewModelScope.launch {
            monitorManager.isMonitoring.collect { isMonitoring ->
                _uiState.value = _uiState.value.copy(isMonitoring = isMonitoring)
            }
        }
        
        viewModelScope.launch {
            monitorManager.currentMethod.collect { method ->
                _uiState.value = _uiState.value.copy(currentMethod = method)
            }
        }
    }
    
    private suspend fun determineAvailableMethods(): List<MonitoringMethod> {
        val availableMethods = mutableListOf<MonitoringMethod>()
        
        // Check root-based methods
        if (rootDetectionService.isRooted()) {
            val capabilities = rootDetectionService.getRootCapabilities()
            if (capabilities.hasSystemHooks) {
                availableMethods.add(MonitoringMethod.SYSTEM_HOOKS)
            }
            if (capabilities.hasXposedFramework) {
                availableMethods.add(MonitoringMethod.XPOSED_HOOKS)
            }
        }
        
        // Check accessibility service
        availableMethods.add(MonitoringMethod.ACCESSIBILITY_SERVICE)
        
        // Foreground service is always available
        availableMethods.add(MonitoringMethod.FOREGROUND_SERVICE)
        
        // Polling fallback is always available
        availableMethods.add(MonitoringMethod.POLLING_FALLBACK)
        
        return availableMethods
    }
    
    fun toggleMonitoring() {
        viewModelScope.launch {
            try {
                if (_uiState.value.isMonitoring) {
                    monitorManager.stopMonitoring()
                } else {
                    monitorManager.startMonitoring()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    lastError = e.message ?: "Failed to toggle monitoring"
                )
            }
        }
    }
    
    fun setPreferredMethod(method: MonitoringMethod?) {
        monitoringConfig = monitoringConfig.copy(preferredMethod = method)
        _uiState.value = _uiState.value.copy(preferredMethod = method)
        
        // If monitoring is active and method is different, switch to new method
        if (_uiState.value.isMonitoring && method != null && method != _uiState.value.currentMethod) {
            viewModelScope.launch {
                try {
                    monitorManager.switchMonitoringMethod(method)
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        lastError = e.message ?: "Failed to switch monitoring method"
                    )
                }
            }
        }
    }
    
    fun toggleMethod(method: MonitoringMethod, enabled: Boolean) {
        val updatedEnabledMethods = _uiState.value.enabledMethods.toMutableMap()
        updatedEnabledMethods[method] = enabled
        
        _uiState.value = _uiState.value.copy(enabledMethods = updatedEnabledMethods)
        
        // Update configuration
        monitoringConfig = when (method) {
            MonitoringMethod.SYSTEM_HOOKS -> monitoringConfig.copy(enableSystemHooks = enabled)
            MonitoringMethod.XPOSED_HOOKS -> monitoringConfig.copy(enableXposedHooks = enabled)
            MonitoringMethod.READ_LOGS -> monitoringConfig // READ_LOGS doesn't have a config toggle
            MonitoringMethod.ACCESSIBILITY_SERVICE -> monitoringConfig.copy(enableAccessibilityService = enabled)
            MonitoringMethod.FOREGROUND_SERVICE -> monitoringConfig.copy(enableForegroundService = enabled)
            MonitoringMethod.POLLING_FALLBACK -> monitoringConfig.copy(enablePollingFallback = enabled)
        }
    }
    
    fun toggleAutoFallback(enabled: Boolean) {
        monitoringConfig = monitoringConfig.copy(autoFallback = enabled)
        _uiState.value = _uiState.value.copy(autoFallback = enabled)
    }
    
    fun toggleNotifications(enabled: Boolean) {
        monitoringConfig = monitoringConfig.copy(enableNotifications = enabled)
        _uiState.value = _uiState.value.copy(enableNotifications = enabled)
    }
    
    fun toggleErrorRecovery(enabled: Boolean) {
        monitoringConfig = monitoringConfig.copy(enableErrorRecovery = enabled)
        _uiState.value = _uiState.value.copy(enableErrorRecovery = enabled)
    }
    
    fun refreshMetrics() {
        viewModelScope.launch {
            try {
                // Get CPU usage statistics
                val cpuStats = cpuUsageTracker.getCpuStats()
                
                // Get memory usage (simplified - in real implementation would use proper memory tracking)
                val runtime = Runtime.getRuntime()
                val memoryUsage = runtime.totalMemory() - runtime.freeMemory()
                
                // Determine battery impact based on current method and CPU usage
                val batteryImpact = determineBatteryImpact(
                    _uiState.value.currentMethod,
                    cpuStats.averageUsagePercent
                )
                
                _uiState.value = _uiState.value.copy(
                    cpuUsage = cpuStats.averageUsagePercent,
                    memoryUsage = memoryUsage,
                    batteryImpact = batteryImpact
                )
                
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    lastError = e.message ?: "Failed to refresh performance metrics"
                )
            }
        }
    }
    
    private fun determineBatteryImpact(method: MonitoringMethod?, cpuUsage: Double): String {
        return when {
            method == null -> "Unknown"
            method == MonitoringMethod.POLLING_FALLBACK && cpuUsage > 2.0 -> "High"
            method == MonitoringMethod.POLLING_FALLBACK -> "Medium"
            method == MonitoringMethod.FOREGROUND_SERVICE -> "Medium"
            cpuUsage > 1.0 -> "Medium"
            else -> "Low"
        }
    }
    
    fun runDiagnostics() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isRunningDiagnostics = true)
                
                val diagnostics = mutableListOf<String>()
                
                // Check root access
                val isRooted = rootDetectionService.isRooted()
                diagnostics.add("Root access: ${if (isRooted) "Available" else "Not available"}")
                
                if (isRooted) {
                    val capabilities = rootDetectionService.getRootCapabilities()
                    diagnostics.add("System hooks: ${if (capabilities.hasSystemHooks) "Available" else "Not available"}")
                    diagnostics.add("Xposed framework: ${if (capabilities.hasXposedFramework) "Available" else "Not available"}")
                }
                
                // Check accessibility permission
                val hasAccessibility = accessibilityPermissionManager.isAccessibilityServiceEnabled()
                diagnostics.add("Accessibility permission: ${if (hasAccessibility) "Granted" else "Not granted"}")
                
                // Check current monitoring status
                val status = monitorManager.getMonitoringStatus()
                diagnostics.add("Current monitoring: ${if (status.isMonitoring) "Active" else "Inactive"}")
                diagnostics.add("Current method: ${status.currentMethod?.name ?: "None"}")
                diagnostics.add("Available strategies: ${status.availableStrategies.size}")
                
                // Performance check
                val cpuStats = cpuUsageTracker.getCpuStats()
                diagnostics.add("CPU usage: ${String.format("%.2f", cpuStats.averageUsagePercent)}%")
                diagnostics.add("Total monitoring cycles: ${cpuStats.totalCycles}")
                
                _uiState.value = _uiState.value.copy(
                    isRunningDiagnostics = false,
                    diagnosticsResult = diagnostics.joinToString("\n")
                )
                
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRunningDiagnostics = false,
                    lastError = e.message ?: "Failed to run diagnostics"
                )
            }
        }
    }
    
    fun clearErrors() {
        _uiState.value = _uiState.value.copy(
            lastError = null,
            diagnosticsResult = null
        )
    }
}

data class MonitoringSettingsUiState(
    val isLoading: Boolean = true,
    val isMonitoring: Boolean = false,
    val currentMethod: MonitoringMethod? = null,
    val availableMethods: List<MonitoringMethod> = emptyList(),
    val enabledMethods: Map<MonitoringMethod, Boolean> = emptyMap(),
    val preferredMethod: MonitoringMethod? = null,
    val autoFallback: Boolean = true,
    val enableNotifications: Boolean = true,
    val enableErrorRecovery: Boolean = true,
    val cpuUsage: Double = 0.0,
    val memoryUsage: Long = 0L,
    val batteryImpact: String = "Unknown",
    val lastError: String? = null,
    val isRunningDiagnostics: Boolean = false,
    val diagnosticsResult: String? = null
)