package com.siw.clipboardsync.data.local

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "clipboard_sync_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    private val _isLoggedIn = MutableStateFlow(hasValidTokens())
    val isLoggedIn: Flow<Boolean> = _isLoggedIn.asStateFlow()
    
    // Token自动刷新相关
    private val handler = Handler(Looper.getMainLooper())
    private var refreshTimer: Runnable? = null
    private var isRefreshing = false
    private val checkInterval = 10 * 60 * 1000L // 10分钟检查间隔
    
    // AuthRepository引用（将在后续注入）
    private var authRepository: (suspend () -> Result<*>)? = null
    
    companion object {
        private const val ACCESS_TOKEN_KEY = "access_token"
        private const val REFRESH_TOKEN_KEY = "refresh_token"
        private const val USER_ID_KEY = "user_id"
        private const val USER_EMAIL_KEY = "user_email"
        private const val DEVICE_ID_KEY = "device_id"
    }
    
    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        sharedPreferences.edit()
            .putString(ACCESS_TOKEN_KEY, accessToken)
            .putString(REFRESH_TOKEN_KEY, refreshToken)
            .apply()
        _isLoggedIn.value = true
        
        // 启动Token管理
        startTokenManagement()
    }
    
    suspend fun getAccessToken(): String? {
        return sharedPreferences.getString(ACCESS_TOKEN_KEY, null)
    }
    
    suspend fun getRefreshToken(): String? {
        return sharedPreferences.getString(REFRESH_TOKEN_KEY, null)
    }
    
    suspend fun saveUserInfo(userId: String, email: String) {
        sharedPreferences.edit()
            .putString(USER_ID_KEY, userId)
            .putString(USER_EMAIL_KEY, email)
            .apply()
    }
    
    suspend fun getUserId(): String? {
        return sharedPreferences.getString(USER_ID_KEY, null)
    }
    
    suspend fun getUserEmail(): String? {
        return sharedPreferences.getString(USER_EMAIL_KEY, null)
    }
    
    suspend fun saveDeviceId(deviceId: String) {
        sharedPreferences.edit()
            .putString(DEVICE_ID_KEY, deviceId)
            .apply()
    }
    
    suspend fun getDeviceId(): String? {
        return sharedPreferences.getString(DEVICE_ID_KEY, null)
    }
    
    suspend fun clearTokens() {
        stopTokenManagement()
        sharedPreferences.edit()
            .remove(ACCESS_TOKEN_KEY)
            .remove(REFRESH_TOKEN_KEY)
            .remove(USER_ID_KEY)
            .remove(USER_EMAIL_KEY)
            .apply()
        _isLoggedIn.value = false
    }
    
    private fun hasValidTokens(): Boolean {
        val accessToken = sharedPreferences.getString(ACCESS_TOKEN_KEY, null)
        val refreshToken = sharedPreferences.getString(REFRESH_TOKEN_KEY, null)
        if (accessToken.isNullOrEmpty() || refreshToken.isNullOrEmpty()) {
            return false
        }
        
        // 检查Token是否过期
        return when (TokenStatus.from(accessToken)) {
            TokenStatus.EXPIRED, TokenStatus.INVALID -> false
            else -> true
        }
    }
    
    /**
     * 设置AuthRepository引用用于Token刷新
     */
    fun setAuthRepository(refreshTokenFunc: suspend () -> Result<*>) {
        this.authRepository = refreshTokenFunc
    }
    
    /**
     * 启动Token自动管理
     */
    fun startTokenManagement() {
        stopTokenManagement()
        checkAndRefreshIfNeeded()
        scheduleNextCheck()
        Log.d("TokenManager", "🔄 Token管理器已启动")
    }
    
    /**
     * 停止Token自动管理
     */
    fun stopTokenManagement() {
        refreshTimer?.let { handler.removeCallbacks(it) }
        refreshTimer = null
        Log.d("TokenManager", "⏹️ Token管理器已停止")
    }
    
    /**
     * 调度下次检查
     */
    private fun scheduleNextCheck() {
        refreshTimer = Runnable {
            checkAndRefreshIfNeeded()
            scheduleNextCheck()
        }
        handler.postDelayed(refreshTimer!!, checkInterval)
    }
    
    /**
     * 检查并刷新Token如果需要
     */
    private fun checkAndRefreshIfNeeded() {
        val accessToken = sharedPreferences.getString(ACCESS_TOKEN_KEY, null)
        val refreshToken = sharedPreferences.getString(REFRESH_TOKEN_KEY, null)
        
        if (accessToken == null || refreshToken == null) {
            Log.w("TokenManager", "⚠️ Token缺失，需要重新登录")
            logout()
            return
        }
        
        if (isRefreshing) {
            Log.d("TokenManager", "🔄 Token刷新进行中，跳过检查")
            return
        }
        
        when (TokenStatus.from(accessToken)) {
            TokenStatus.NEAR_EXPIRY -> {
                Log.w("TokenManager", "⚠️ Token即将过期，开始预刷新")
                performTokenRefresh()
            }
            TokenStatus.EXPIRED -> {
                Log.e("TokenManager", "❌ Token已过期，强制刷新")
                performTokenRefresh()
            }
            TokenStatus.VALID -> {
                Log.d("TokenManager", "✅ Token有效")
            }
            TokenStatus.INVALID -> {
                Log.e("TokenManager", "❌ Token无效，需要重新登录")
                logout()
            }
        }
    }
    
    /**
     * 执行Token刷新
     */
    private fun performTokenRefresh() {
        if (isRefreshing) return
        isRefreshing = true
        
        Log.d("TokenManager", "🔄 开始刷新Token...")
        
        // 由于我们在Handler中，实际刷新主要由AuthInterceptor处理
        // 这里只是标记需要刷新，避免复杂的协程处理
        if (authRepository != null) {
            Log.d("TokenManager", "📝 标记Token需要刷新，将由401拦截器处理")
        } else {
            Log.e("TokenManager", "❌ AuthRepository未设置，无法刷新Token")
            logout()
        }
        
        isRefreshing = false
    }
    
    /**
     * 登出处理
     */
    private fun logout() {
        Log.w("TokenManager", "🚪 执行自动登出")
        // 清除Token并通知登出
        handler.post {
            stopTokenManagement()
            sharedPreferences.edit()
                .remove(ACCESS_TOKEN_KEY)
                .remove(REFRESH_TOKEN_KEY)
                .remove(USER_ID_KEY)
                .remove(USER_EMAIL_KEY)
                .apply()
            _isLoggedIn.value = false
        }
    }
    
    /**
     * 获取Token状态
     */
    fun getTokenStatus(): TokenStatus {
        val accessToken = sharedPreferences.getString(ACCESS_TOKEN_KEY, null)
        return TokenStatus.from(accessToken)
    }
    
    /**
     * 检查是否需要刷新Token
     */
    fun needsRefresh(): Boolean {
        val accessToken = sharedPreferences.getString(ACCESS_TOKEN_KEY, null) ?: return true
        val status = TokenStatus.from(accessToken)
        return status == TokenStatus.NEAR_EXPIRY || status == TokenStatus.EXPIRED
    }
}