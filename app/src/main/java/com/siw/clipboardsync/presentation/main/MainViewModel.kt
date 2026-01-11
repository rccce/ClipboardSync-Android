package com.siw.clipboardsync.presentation.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siw.clipboardsync.data.repository.AuthRepository
import com.siw.clipboardsync.data.repository.ClipboardRepository
import com.siw.clipboardsync.data.model.ClipboardItem
import com.siw.clipboardsync.manager.ServiceManager
import com.siw.clipboardsync.manager.ClipboardSyncManager
import com.siw.clipboardsync.monitor.ClipboardMonitorManager
import com.siw.clipboardsync.monitor.model.MonitoringMethod
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
    private val monitorManager: ClipboardMonitorManager,
    @ApplicationContext private val context: Context
) : ViewModel(), androidx.lifecycle.DefaultLifecycleObserver {
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    // Track last synced content hash to prevent duplicate syncs
    private var lastSyncedContentHash: String? = null
    
    init {
        loadInitialData()
        checkServiceStatus()
        // Initialize ClipboardSyncManager first, then start observing
        initializeAndObserveClipboardSync()
        // Initialize and observe advanced monitoring
        initializeAndObserveMonitoring()
        autoStartClipboardSync()
        // Auto-start WebSocket connection when app starts
        autoStartWebSocketConnection()
        // Register for lifecycle events
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
    
    override fun onCleared() {
        super.onCleared()
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }
    
    // Called when app comes to foreground
    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
        super<androidx.lifecycle.DefaultLifecycleObserver>.onStart(owner)
        android.util.Log.d("MainViewModel", "App came to foreground, refreshing clipboard")
        refreshCurrentClipboard()
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
                
                // Initialize lastSyncedContentHash with current clipboard to avoid
                // syncing the same content on first app open
                if (!currentClipboard.isNullOrBlank()) {
                    lastSyncedContentHash = com.siw.clipboardsync.utils.DeviceUtils.generateContentHash(currentClipboard)
                    android.util.Log.d("MainViewModel", "Initialized lastSyncedContentHash with current clipboard")
                }
                
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
    
    /**
     * Perform manual clipboard sync.
     * Called when user clicks "立即同步" button in notification.
     * This reads the current clipboard and syncs it to the server.
     * Manual sync always syncs regardless of whether content has changed.
     */
    fun performManualSync() {
        viewModelScope.launch {
            try {
                android.util.Log.d("MainViewModel", "Performing manual sync...")
                
                // Wait a moment to ensure the app has fully gained focus
                // Android 10+ requires the app to be in focus to read clipboard
                kotlinx.coroutines.delay(300)
                
                // Get current clipboard content
                val currentClipboard = ClipboardUtils.getCurrentClipboardText(context)
                if (currentClipboard.isNullOrBlank()) {
                    android.util.Log.d("MainViewModel", "No clipboard content to sync")
                    return@launch
                }
                
                // Check if content should be synced
                if (!ClipboardUtils.shouldSyncContent(currentClipboard)) {
                    android.util.Log.d("MainViewModel", "Content filtered out from sync")
                    return@launch
                }
                
                // Sync the clipboard content (manual sync always syncs)
                val result = clipboardSyncManager.syncLocalClipboard(currentClipboard, "text")
                if (result.isSuccess) {
                    android.util.Log.d("MainViewModel", "Manual sync completed successfully")
                    // Update last synced hash
                    lastSyncedContentHash = com.siw.clipboardsync.utils.DeviceUtils.generateContentHash(currentClipboard)
                    _uiState.value = _uiState.value.copy(currentClipboard = currentClipboard)
                    // Refresh history to show the new item
                    refreshClipboardHistory()
                } else {
                    android.util.Log.w("MainViewModel", "Manual sync failed: ${result.exceptionOrNull()?.message}")
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "同步失败: ${result.exceptionOrNull()?.message}"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error during manual sync", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "同步失败: ${e.message}"
                )
            }
        }
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
    
    /**
     * Refresh current clipboard content displayed in UI.
     * Called when app comes to foreground.
     */
    private fun refreshCurrentClipboard() {
        viewModelScope.launch {
            try {
                // Small delay to ensure clipboard access is available
                kotlinx.coroutines.delay(100)
                val currentClipboard = ClipboardUtils.getCurrentClipboardText(context)
                _uiState.value = _uiState.value.copy(currentClipboard = currentClipboard)
                android.util.Log.d("MainViewModel", "Current clipboard refreshed: ${currentClipboard?.take(30)}...")
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Failed to refresh clipboard: ${e.message}")
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
        
        // Check if advanced monitoring should be auto-started after permission changes
        viewModelScope.launch {
            if (_uiState.value.hasRequiredPermissions && !_uiState.value.isAdvancedMonitoring) {
                autoStartAdvancedMonitoring()
            }
        }
    }
    
    fun refreshServiceStatus() {
        viewModelScope.launch {
            android.util.Log.d("MainViewModel", "Refreshing service status...")
            val isRunning = serviceManager.isClipboardServiceRunning()
            val hasPermissions = serviceManager.hasRequiredPermissions()
            
            android.util.Log.d("MainViewModel", "Service running: $isRunning, Has permissions: $hasPermissions")
            
            _uiState.value = _uiState.value.copy(
                isServiceRunning = isRunning,
                hasRequiredPermissions = hasPermissions,
                missingPermissions = serviceManager.getMissingPermissions()
            )
            
            // If service is not running but we think it should be, try to restart
            if (!isRunning && _uiState.value.syncStatus != ClipboardSyncManager.SyncStatus.DISCONNECTED) {
                android.util.Log.d("MainViewModel", "Service not running but sync status is not DISCONNECTED, updating status")
                // The ClipboardSyncManager should handle this, but let's make sure UI is consistent
            }
        }
    }
    
    private fun initializeAndObserveClipboardSync() {
        // Initialize ClipboardSyncManager and start observing
        viewModelScope.launch {
            android.util.Log.d("MainViewModel", "Initializing ClipboardSyncManager...")
            clipboardSyncManager.initialize()
            android.util.Log.d("MainViewModel", "ClipboardSyncManager initialized, starting observation...")
        }
        observeClipboardSync()
    }
    
    private fun observeClipboardSync() {
        // Observe sync status
        viewModelScope.launch {
            clipboardSyncManager.syncStatus.collect { status ->
                android.util.Log.d("MainViewModel", "=== UI Status Update ===")
                android.util.Log.d("MainViewModel", "Received sync status: $status")
                android.util.Log.d("MainViewModel", "Current UI status: ${_uiState.value.syncStatus}")
                _uiState.value = _uiState.value.copy(
                    syncStatus = status
                )
                android.util.Log.d("MainViewModel", "Updated UI status to: ${_uiState.value.syncStatus}")
                android.util.Log.d("MainViewModel", "=== UI Update Complete ===")
            }
        }
        
        // Observe last synced item - separate coroutine
        viewModelScope.launch {
            clipboardSyncManager.lastSyncedItem.collect { item ->
                item?.let {
                    // Auto-update clipboard if enabled - no need to show notification
                    if (_uiState.value.autoUpdateClipboard) {
                        ClipboardUtils.setTextToClipboard(context, it.content)
                        _uiState.value = _uiState.value.copy(
                            currentClipboard = it.content,
                            lastIncomingUpdate = null // Don't show notification when auto-apply is enabled
                        )
                    } else {
                        // Only show notification when auto-apply is disabled
                        _uiState.value = _uiState.value.copy(
                            lastIncomingUpdate = it,
                            incomingUpdateTimestamp = System.currentTimeMillis()
                        )
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
                
                // After starting the service, also check if we should auto-start advanced monitoring
                // Wait a moment for service to initialize properly
                kotlinx.coroutines.delay(500)
                if (!_uiState.value.isAdvancedMonitoring) {
                    autoStartAdvancedMonitoring()
                }
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
            // Soft disconnect WebSocket but keep manager ready for reconnection
            viewModelScope.launch {
                clipboardSyncManager.softDisconnect()
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
    
    private fun initializeAndObserveMonitoring() {
        // Initialize monitoring manager and start observing
        viewModelScope.launch {
            try {
                monitorManager.initialize()
                android.util.Log.d("MainViewModel", "MonitorManager initialized")
                
                // Auto-start advanced monitoring after initialization
                autoStartAdvancedMonitoring()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to initialize MonitorManager", e)
            }
        }
        observeMonitoring()
    }
    
    private fun observeMonitoring() {
        // Observe monitoring status
        viewModelScope.launch {
            monitorManager.isMonitoring.collect { isMonitoring ->
                android.util.Log.d("MainViewModel", "Monitoring status changed: $isMonitoring")
                _uiState.value = _uiState.value.copy(isAdvancedMonitoring = isMonitoring)
            }
        }
        
        // Observe current monitoring method from ClipboardMonitorManager
        viewModelScope.launch {
            monitorManager.currentMethod.collect { method ->
                android.util.Log.d("MainViewModel", "Monitoring method changed (from monitorManager): ${method?.name}")
                _uiState.value = _uiState.value.copy(currentMonitoringMethod = method)
            }
        }
        
        // Also observe from ClipboardSyncManager to ensure we catch all updates
        viewModelScope.launch {
            clipboardSyncManager.monitoringMethod.collect { method ->
                android.util.Log.d("MainViewModel", "Monitoring method changed (from syncManager): ${method?.name}")
                // Only update if different from current state
                if (method != _uiState.value.currentMonitoringMethod) {
                    _uiState.value = _uiState.value.copy(currentMonitoringMethod = method)
                }
            }
        }
    }
    
    fun startAdvancedMonitoring() {
        viewModelScope.launch {
            try {
                if (!_uiState.value.hasRequiredPermissions) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Required permissions not granted. Please grant accessibility permission."
                    )
                    return@launch
                }
                
                if (_uiState.value.isAdvancedMonitoring) {
                    android.util.Log.d("MainViewModel", "Advanced monitoring already active")
                    return@launch
                }
                
                android.util.Log.d("MainViewModel", "Starting advanced monitoring manually...")
                clipboardSyncManager.enableAdvancedMonitoring()
                android.util.Log.d("MainViewModel", "Advanced monitoring started successfully")
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to start advanced monitoring", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to start advanced monitoring: ${e.message}"
                )
            }
        }
    }
    
    fun stopAdvancedMonitoring() {
        viewModelScope.launch {
            try {
                android.util.Log.d("MainViewModel", "Stopping advanced monitoring...")
                monitorManager.stopMonitoring()
                android.util.Log.d("MainViewModel", "Advanced monitoring stopped successfully")
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to stop advanced monitoring", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to stop advanced monitoring: ${e.message}"
                )
            }
        }
    }
    
    private fun autoStartAdvancedMonitoring() {
        viewModelScope.launch {
            try {
                // Auto-start advanced monitoring if permissions are available
                if (_uiState.value.hasRequiredPermissions && !_uiState.value.isAdvancedMonitoring) {
                    android.util.Log.d("MainViewModel", "Auto-starting advanced monitoring...")
                    clipboardSyncManager.enableAdvancedMonitoring()
                    android.util.Log.d("MainViewModel", "Advanced monitoring auto-started successfully")
                } else if (!_uiState.value.hasRequiredPermissions) {
                    android.util.Log.d("MainViewModel", "Cannot auto-start advanced monitoring: missing permissions")
                } else {
                    android.util.Log.d("MainViewModel", "Advanced monitoring already active")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to auto-start advanced monitoring", e)
                // Don't show error to user for auto-start failure - they can manually start it
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
    val autoUpdateClipboard: Boolean = true,
    // Advanced monitoring state
    val isAdvancedMonitoring: Boolean = false,
    val currentMonitoringMethod: MonitoringMethod? = null
)