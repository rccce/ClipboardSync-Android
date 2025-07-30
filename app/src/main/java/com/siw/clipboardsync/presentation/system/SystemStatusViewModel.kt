package com.siw.clipboardsync.presentation.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siw.clipboardsync.data.model.SystemStatus
import com.siw.clipboardsync.manager.ClipboardSyncManager
import com.siw.clipboardsync.monitor.ClipboardMonitorManager
import com.siw.clipboardsync.service.SystemStatusService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SystemStatusViewModel @Inject constructor(
    private val systemStatusService: SystemStatusService,
    private val clipboardSyncManager: ClipboardSyncManager,
    private val monitorManager: ClipboardMonitorManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SystemStatusUiState())
    val uiState: StateFlow<SystemStatusUiState> = _uiState.asStateFlow()
    
    /**
     * Refresh system status information
     */
    fun refreshSystemStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            
            try {
                val systemStatus = systemStatusService.getSystemStatus()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    systemStatus = systemStatus
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load system status: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Start clipboard monitoring
     */
    fun startMonitoring() {
        viewModelScope.launch {
            try {
                // Initialize and start monitoring
                monitorManager.initialize()
                monitorManager.startMonitoring()
                
                // Refresh status to show updated monitoring state
                refreshSystemStatus()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to start monitoring: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Stop clipboard monitoring
     */
    fun stopMonitoring() {
        viewModelScope.launch {
            try {
                monitorManager.stopMonitoring()
                
                // Refresh status to show updated monitoring state
                refreshSystemStatus()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to stop monitoring: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Connect WebSocket
     */
    fun connectWebSocket() {
        viewModelScope.launch {
            try {
                clipboardSyncManager.reconnectIfNeeded()
                
                // Refresh status to show updated connection state
                refreshSystemStatus()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to connect WebSocket: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Disconnect WebSocket
     */
    fun disconnectWebSocket() {
        viewModelScope.launch {
            try {
                clipboardSyncManager.softDisconnect()
                
                // Refresh status to show updated connection state
                refreshSystemStatus()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to disconnect WebSocket: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Request battery optimization exemption
     */
    fun requestBatteryOptimization() {
        viewModelScope.launch {
            try {
                val success = systemStatusService.requestIgnoreBatteryOptimizations()
                if (success) {
                    // Refresh status after a short delay to allow settings to update
                    kotlinx.coroutines.delay(1000)
                    refreshSystemStatus()
                } else {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Failed to request battery optimization exemption"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Error requesting battery optimization: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Open accessibility settings
     */
    fun openAccessibilitySettings() {
        try {
            systemStatusService.openAccessibilitySettings()
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Failed to open accessibility settings: ${e.message}"
            )
        }
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

/**
 * UI state for system status screen
 */
data class SystemStatusUiState(
    val isLoading: Boolean = false,
    val systemStatus: SystemStatus? = null,
    val errorMessage: String? = null
)