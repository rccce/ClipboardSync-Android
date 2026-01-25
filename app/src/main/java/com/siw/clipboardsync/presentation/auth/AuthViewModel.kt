package com.siw.clipboardsync.presentation.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siw.clipboardsync.data.repository.AuthRepository
import com.siw.clipboardsync.data.repository.DeviceRepository
import com.siw.clipboardsync.utils.DeviceUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
    
    val isLoggedIn = authRepository.isLoggedIn
    
    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            
            try {
                val deviceId = DeviceUtils.generateDeviceId(context)
                val deviceName = DeviceUtils.getDeviceName()
                val osVersion = DeviceUtils.getOsVersion()
                val appVersion = DeviceUtils.getAppVersion(context)
                
                val result = authRepository.login(email, password, deviceId, deviceName, osVersion, appVersion)
                
                if (result.isSuccess) {
                    // Register device after successful login to ensure device info is up-to-date
                    deviceRepository.registerDevice(deviceId, deviceName, osVersion, appVersion)
                    _uiState.value = _uiState.value.copy(isLoading = false, isSuccess = true)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Login failed"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "An error occurred"
                )
            }
        }
    }
    
    fun register(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            
            try {
                val result = authRepository.register(email, password)
                
                if (result.isSuccess) {
                    // After successful registration, register device
                    val deviceId = DeviceUtils.generateDeviceId(context)
                    val deviceName = DeviceUtils.getDeviceName()
                    val osVersion = DeviceUtils.getOsVersion()
                    val appVersion = DeviceUtils.getAppVersion(context)
                    deviceRepository.registerDevice(deviceId, deviceName, osVersion, appVersion)
                    
                    _uiState.value = _uiState.value.copy(isLoading = false, isSuccess = true)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Registration failed"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "An error occurred"
                )
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
    
    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(isSuccess = false)
    }
}

data class AuthUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
)