package com.siw.clipboardsync.data.local

import android.content.Context
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
        return !accessToken.isNullOrEmpty() && !refreshToken.isNullOrEmpty()
    }
}