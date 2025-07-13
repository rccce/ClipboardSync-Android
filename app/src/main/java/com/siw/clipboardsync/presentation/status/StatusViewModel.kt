package com.siw.clipboardsync.presentation.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siw.clipboardsync.utils.LSPosedUtils
import com.siw.clipboardsync.utils.RootUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatusViewModel @Inject constructor() : ViewModel() {
    
    private val _statusState = MutableStateFlow(StatusState())
    val statusState: StateFlow<StatusState> = _statusState.asStateFlow()
    
    init {
        refreshStatus()
    }
    
    fun refreshStatus() {
        viewModelScope.launch {
            _statusState.value = _statusState.value.copy(isLoading = true)
            
            try {
                // Get device capabilities
                val capabilities = RootUtils.getClipboardCapabilities()
                
                // Get LSPosed version if available
                val lsposedVersion = if (capabilities.hasLSPosed) {
                    LSPosedUtils.getLSPosedVersion()
                } else null
                
                // Get LSPosed configuration
                val lsposedConfig = LSPosedUtils.getRecommendedConfiguration()
                
                _statusState.value = StatusState(
                    isLoading = false,
                    capabilities = capabilities,
                    lsposedVersion = lsposedVersion,
                    lsposedConfig = lsposedConfig,
                    lastRefresh = System.currentTimeMillis()
                )
                
            } catch (e: Exception) {
                _statusState.value = _statusState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }
    
    fun forceRefreshWithDebug() {
        viewModelScope.launch {
            _statusState.value = _statusState.value.copy(isLoading = true)
            
            try {
                // Force debug logging
                android.util.Log.d("StatusViewModel", "=== FORCE REFRESH WITH DEBUG ===")
                
                // Get device capabilities with debug
                val capabilities = RootUtils.getClipboardCapabilities()
                
                // Force LSPosed detection with debug
                val lsposedActive = LSPosedUtils.isLSPosedActive()
                val moduleActive = LSPosedUtils.isModuleActive()
                
                android.util.Log.d("StatusViewModel", "Force check results:")
                android.util.Log.d("StatusViewModel", "- LSPosed Active: $lsposedActive")
                android.util.Log.d("StatusViewModel", "- Module Active: $moduleActive")
                android.util.Log.d("StatusViewModel", "- Capabilities: ${capabilities.getDescription()}")
                
                // Get LSPosed version if available
                val lsposedVersion = if (capabilities.hasLSPosed) {
                    LSPosedUtils.getLSPosedVersion()
                } else null
                
                // Get LSPosed configuration
                val lsposedConfig = LSPosedUtils.getRecommendedConfiguration()
                
                _statusState.value = StatusState(
                    isLoading = false,
                    capabilities = capabilities,
                    lsposedVersion = lsposedVersion,
                    lsposedConfig = lsposedConfig,
                    lastRefresh = System.currentTimeMillis()
                )
                
            } catch (e: Exception) {
                android.util.Log.e("StatusViewModel", "Error in force refresh", e)
                _statusState.value = _statusState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }
    
    data class StatusState(
        val isLoading: Boolean = false,
        val capabilities: RootUtils.ClipboardCapabilities? = null,
        val lsposedVersion: String? = null,
        val lsposedConfig: LSPosedUtils.LSPosedConfiguration? = null,
        val lastRefresh: Long = 0L,
        val error: String? = null
    )
}