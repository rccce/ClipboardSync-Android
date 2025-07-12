package com.siw.clipboardsync.presentation.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siw.clipboardsync.data.repository.AuthRepository
import com.siw.clipboardsync.data.repository.ClipboardRepository
import com.siw.clipboardsync.data.model.ClipboardItem
import com.siw.clipboardsync.manager.ServiceManager
import com.siw.clipboardsync.manager.ClipboardSyncManager
import com.siw.clipboardsync.utils.ClipboardUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val clipboardRepository: ClipboardRepository,
    private val serviceManager: ServiceManager,
    private val clipboardSyncManager: ClipboardSyncManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    init {
        loadInitialData()
        checkServiceStatus()
        observeClipboardSync()
        autoStartClipboardSync()
        // Auto-start WebSocket connection when app starts
        autoStartWebSocketConnection()
    }
    
    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                // Load clipboard history
                val historyResult = clipboardRepository.getClipboardHistory(limit = 20)
                if (historyResult.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        clipboardHistory = historyResult.getOrNull() ?: emptyList()
                    )
                }
                
                // Get current clipboard content
                val currentClipboard = ClipboardUtils.getCurrentClipboardText(context)
                _uiState.value = _uiState.value.copy(
                    currentClipboard = currentClipboard,
                    isLoading = false
                )
                
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message
                )
            }
        }
    }
    
    private fun checkServiceStatus() {
        viewModelScope.launch {
            val isRunning = serviceManager.isClipboardServiceRunning()
            val hasPermissions = serviceManager.hasRequiredPermissions()
            
            _uiState.value = _uiState.value.copy(
                isServiceRunning = isRunning,
                hasRequiredPermissions = hasPermissions,
                missingPermissions = serviceManager.getMissingPermissions()
            )
        }
    }
    
    fun startClipboardService() {
        viewModelScope.launch {
            val success = serviceManager.startClipboardService()
            if (success) {
                _uiState.value = _uiState.value.copy(isServiceRunning = true)
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to start service. Check permissions."
                )
            }
        }
    }
    
    fun stopClipboardService() {
        serviceManager.stopClipboardService()
        _uiState.value = _uiState.value.copy(isServiceRunning = false)
    }
    
    fun refreshClipboardHistory() {
        viewModelScope.launch {
            try {
                val result = clipboardRepository.getClipboardHistory(limit = 20)
                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        clipboardHistory = result.getOrNull() ?: emptyList()
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = e.message
                )
            }
        }
    }
    
    fun copyToClipboard(content: String) {
        ClipboardUtils.setTextToClipboard(context, content)
        _uiState.value = _uiState.value.copy(currentClipboard = content)
    }
    
    fun deleteClipboardItem(itemId: String) {
        viewModelScope.launch {
            try {
                val result = clipboardRepository.deleteClipboardItem(itemId)
                if (result.isSuccess) {
                    // Remove from local list
                    val updatedHistory = _uiState.value.clipboardHistory.filter { it.id != itemId }
                    _uiState.value = _uiState.value.copy(clipboardHistory = updatedHistory)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = e.message
                )
            }
        }
    }
    
    fun logout() {
        viewModelScope.launch {
            stopClipboardService()
            authRepository.logout()
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
    
    fun checkPermissions() {
        checkServiceStatus()
    }
    
    private fun observeClipboardSync() {
        viewModelScope.launch {
            // Observe sync status
            clipboardSyncManager.syncStatus.collect { status ->
                _uiState.value = _uiState.value.copy(
                    syncStatus = status
                )
            }
        }
        
        viewModelScope.launch {
            // Observe last synced item
            clipboardSyncManager.lastSyncedItem.collect { item ->
                item?.let {
                    _uiState.value = _uiState.value.copy(
                        lastIncomingUpdate = it,
                        incomingUpdateTimestamp = System.currentTimeMillis()
                    )
                    
                    // Auto-update clipboard if enabled
                    if (_uiState.value.autoUpdateClipboard) {
                        ClipboardUtils.setTextToClipboard(context, it.content)
                        _uiState.value = _uiState.value.copy(currentClipboard = it.content)
                    }
                    
                    // Refresh history to include new item
                    refreshClipboardHistory()
                }
            }
        }
    }
    
    fun toggleAutoUpdateClipboard() {
        _uiState.value = _uiState.value.copy(
            autoUpdateClipboard = !_uiState.value.autoUpdateClipboard
        )
    }
    
    fun applyIncomingUpdate() {
        _uiState.value.lastIncomingUpdate?.let { item ->
            ClipboardUtils.setTextToClipboard(context, item.content)
            _uiState.value = _uiState.value.copy(
                currentClipboard = item.content,
                lastIncomingUpdate = null
            )
        }
    }
    
    fun dismissIncomingUpdate() {
        _uiState.value = _uiState.value.copy(lastIncomingUpdate = null)
    }
    
    private fun autoStartClipboardSync() {
        viewModelScope.launch {
            // Auto-start clipboard sync if permissions are available
            // The service should start automatically when user is logged in
            if (uiState.value.hasRequiredPermissions && !uiState.value.isServiceRunning) {
                startClipboardService()
            }
        }
    }
    
    fun toggleClipboardSync() {
        if (_uiState.value.isServiceRunning) {
            stopClipboardService()
        } else {
            startClipboardService()
        }
    }
    
    fun toggleWebSocketConnection() {
        // Single toggle for WebSocket connection control
        // This replaces the need for separate start/stop controls
        if (_uiState.value.syncStatus == ClipboardSyncManager.SyncStatus.CONNECTED || 
            _uiState.value.syncStatus == ClipboardSyncManager.SyncStatus.CONNECTING) {
            // Disconnect WebSocket but keep service running for local clipboard monitoring
            viewModelScope.launch {
                clipboardSyncManager.disconnect()
            }
        } else {
            // Connect WebSocket - service should already be running
            if (!_uiState.value.isServiceRunning) {
                startClipboardService() // Ensure service is running first
            }
            viewModelScope.launch {
                clipboardSyncManager.reconnectIfNeeded()
            }
        }
    }
    
    private fun autoStartWebSocketConnection() {
        viewModelScope.launch {
            // Auto-start WebSocket connection if permissions are available and service is running
            // This ensures the connection is open by default
            if (_uiState.value.hasRequiredPermissions && _uiState.value.isServiceRunning) {
                clipboardSyncManager.reconnectIfNeeded()
            }
        }
    }
}

data class MainUiState(
    val isLoading: Boolean = false,
    val isServiceRunning: Boolean = false,
    val hasRequiredPermissions: Boolean = true,
    val missingPermissions: List<String> = emptyList(),
    val currentClipboard: String? = null,
    val clipboardHistory: List<ClipboardItem> = emptyList(),
    val errorMessage: String? = null,
    // Sync-related state
    val syncStatus: ClipboardSyncManager.SyncStatus = ClipboardSyncManager.SyncStatus.DISCONNECTED,
    val lastIncomingUpdate: ClipboardItem? = null,
    val incomingUpdateTimestamp: Long = 0L,
    val autoUpdateClipboard: Boolean = true
)