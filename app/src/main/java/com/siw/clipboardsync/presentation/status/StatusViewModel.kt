package com.siw.clipboardsync.presentation.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
                
                _statusState.value = StatusState(
                    isLoading = false,
                    capabilities = capabilities,
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
                
                android.util.Log.d("StatusViewModel", "Force check results:")
                android.util.Log.d("StatusViewModel", "- Root Access: ${capabilities.isRooted}")
                android.util.Log.d("StatusViewModel", "- Capabilities: ${capabilities.getDescription()}")
                android.util.Log.d("StatusViewModel", "- Polling Interval: ${capabilities.recommendedPollingInterval}ms")
                
                _statusState.value = StatusState(
                    isLoading = false,
                    capabilities = capabilities,
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
        val lastRefresh: Long = 0L,
        val error: String? = null
    )
}