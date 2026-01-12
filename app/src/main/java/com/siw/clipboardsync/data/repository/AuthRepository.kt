package com.siw.clipboardsync.data.repository

import com.siw.clipboardsync.data.local.TokenManager
import com.siw.clipboardsync.data.model.*
import com.siw.clipboardsync.data.network.ApiService
import com.siw.clipboardsync.data.network.AuthInterceptor
import com.siw.clipboardsync.utils.DeviceUtils
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
    private val authInterceptor: AuthInterceptor
) {
    
    val isLoggedIn: Flow<Boolean> = tokenManager.isLoggedIn
    
    init {
        // 设置TokenManager和AuthInterceptor的刷新回调
        tokenManager.setAuthRepository { refreshToken() }
        authInterceptor.setAuthRepository { refreshToken() }
    }
    
    suspend fun register(email: String, password: String): Result<AuthData> {
        return try {
            val request = RegisterRequest(email, password)
            val response = apiService.register(request)
            
            if (response.isSuccessful && response.body()?.success == true) {
                val authData = response.body()!!.data!!
                
                // Save tokens and user info
                tokenManager.saveTokens(authData.accessToken, authData.refreshToken)
                tokenManager.saveUserInfo(authData.user.id, authData.user.email)
                
                Result.success(authData)
            } else {
                // 优先从 response body 获取错误信息
                var errorMessage = response.body()?.error ?: response.body()?.message
                
                // 如果 body 为空，尝试从 errorBody 解析
                if (errorMessage == null) {
                    errorMessage = parseErrorBody(response.errorBody()?.string())
                }
                
                Result.failure(Exception(errorMessage ?: "Registration failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun login(email: String, password: String, deviceId: String, deviceName: String): Result<AuthData> {
        return try {
            val request = LoginRequest(
                email = email,
                password = password,
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = DeviceUtils.getDeviceType()
            )
            val response = apiService.login(request)
            
            if (response.isSuccessful && response.body()?.success == true) {
                val authData = response.body()!!.data!!
                
                // Save tokens, user info, and device ID
                tokenManager.saveTokens(authData.accessToken, authData.refreshToken)
                tokenManager.saveUserInfo(authData.user.id, authData.user.email)
                tokenManager.saveDeviceId(deviceId)
                
                Result.success(authData)
            } else {
                // 优先从 response body 获取错误信息
                var errorMessage = response.body()?.error ?: response.body()?.message
                
                // 如果 body 为空，尝试从 errorBody 解析
                if (errorMessage == null) {
                    errorMessage = parseErrorBody(response.errorBody()?.string())
                }
                
                Result.failure(Exception(errorMessage ?: "Login failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 解析错误响应体，提取错误信息
     */
    private fun parseErrorBody(errorBody: String?): String? {
        if (errorBody.isNullOrEmpty()) return null
        
        return try {
            // 尝试解析 JSON 格式的错误响应
            val gson = com.google.gson.Gson()
            val errorResponse = gson.fromJson(errorBody, ErrorResponse::class.java)
            errorResponse?.error ?: errorResponse?.message
        } catch (e: Exception) {
            // 如果解析失败，直接返回原始字符串（可能是纯文本错误）
            errorBody.takeIf { it.length < 200 }
        }
    }
    
    suspend fun refreshToken(): Result<AuthData> {
        return try {
            val oldRefreshToken = tokenManager.getRefreshToken()
                ?: return Result.failure(Exception("No refresh token available"))
            
            val request = RefreshTokenRequest(oldRefreshToken)
            val response = apiService.refreshToken(request)
            
            if (response.isSuccessful && response.body()?.success == true) {
                val refreshData = response.body()!!.data!!
                val newAccess = refreshData.accessToken
                val newRefresh = refreshData.refreshToken ?: oldRefreshToken
                
                // Save new tokens (keep old refresh if server didn't return one)
                tokenManager.saveTokens(newAccess, newRefresh)
                
                // Build AuthData compatible object for callers
                val user = getCurrentUser() ?: User(id = "", email = "")
                val authData = AuthData(accessToken = newAccess, refreshToken = newRefresh, user = user)
                
                Result.success(authData)
            } else {
                // Refresh failed, clear tokens
                tokenManager.clearTokens()
                Result.failure(Exception("Token refresh failed"))
            }
        } catch (e: Exception) {
            tokenManager.clearTokens()
            Result.failure(e)
        }
    }
    
    // 新增：返回确保有效的access token，需要时自动刷新
    suspend fun getValidAccessToken(): String? {
        val token = tokenManager.getAccessToken()
        if (token == null) return null
        val needsRefresh = tokenManager.needsRefresh()
        return if (needsRefresh) {
            val result = refreshToken()
            if (result.isSuccess) {
                tokenManager.getAccessToken()
            } else null
        } else token
    }
    
    suspend fun logout() {
        tokenManager.clearTokens()
    }
    
    suspend fun getCurrentUser(): User? {
        val userId = tokenManager.getUserId()
        val userEmail = tokenManager.getUserEmail()
        
        return if (userId != null && userEmail != null) {
            User(id = userId, email = userEmail)
        } else {
            null
        }
    }
    
    suspend fun getDeviceId(): String? {
        return tokenManager.getDeviceId()
    }
    
    suspend fun getAccessToken(): String? {
        return tokenManager.getAccessToken()
    }
}