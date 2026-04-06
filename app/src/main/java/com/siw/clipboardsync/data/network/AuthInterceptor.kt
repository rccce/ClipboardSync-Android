package com.siw.clipboardsync.data.network

import android.util.Log
import com.siw.clipboardsync.data.local.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager
) : Interceptor {
    
    // AuthRepository引用，将通过setter注入
    private var authRepository: (suspend () -> Result<*>)? = null
    
    // 防止并发刷新的标志
    @Volatile
    private var isRefreshing = false
    
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        
        // Skip auth for login, register, refresh, and health check endpoints
        val url = request.url.toString()
        if (url.contains("/auth/login") || 
            url.contains("/auth/register") || 
            url.contains("/auth/refresh") ||
            url.contains("/health") ||
            url.contains("r2.cloudflarestorage.com")) {  // Skip presigned R2 URLs
            return chain.proceed(request)
        }
        
        // Add authorization header
        val token = runBlocking { tokenManager.getAccessToken() }
        if (token != null) {
            request = request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        
        var response = chain.proceed(request)
        
        // Handle 401 Unauthorized
        if ((response.code == 401 || response.code == 403) && token != null) {
            Log.d("AuthInterceptor", "🔄 收到401错误，尝试刷新Token并重试")
            
            response.close()
            
            // 使用现有的AuthRepository刷新Token
            val refreshFunction = authRepository
            if (refreshFunction != null && !isRefreshing) {
                synchronized(this) {
                    if (!isRefreshing) {
                        isRefreshing = true
                        try {
                            val result = runBlocking { refreshFunction() }
                            if (result.isSuccess) {
                                // Token已经被AuthRepository.refreshToken()更新到TokenManager
                                val newToken = runBlocking { tokenManager.getAccessToken() }
                                if (newToken != null) {
                                    // 重试原始请求
                                    request = request.newBuilder()
                                        .header("Authorization", "Bearer $newToken")
                                        .build()
                                    response = chain.proceed(request)
                                    
                                    Log.d("AuthInterceptor", "✅ Token刷新成功，请求重试完成")
                                } else {
                                    Log.e("AuthInterceptor", "❌ 刷新后获取不到新Token")
                                    runBlocking { tokenManager.clearTokens() }
                                }
                            } else {
                                Log.e("AuthInterceptor", "❌ Token刷新失败: ${result.exceptionOrNull()?.message}")
                                runBlocking { tokenManager.clearTokens() }
                            }
                        } catch (e: Exception) {
                            Log.e("AuthInterceptor", "❌ Token刷新异常", e)
                            runBlocking { tokenManager.clearTokens() }
                        } finally {
                            isRefreshing = false
                        }
                    }
                }
            } else {
                Log.w("AuthInterceptor", "⚠️ AuthRepository未设置或正在刷新中，执行登出")
                runBlocking { tokenManager.clearTokens() }
            }
        }
        
        return response
    }
    
    /**
     * 设置AuthRepository引用（避免循环依赖）
     */
    fun setAuthRepository(refreshFunction: suspend () -> Result<*>) {
        this.authRepository = refreshFunction
    }
}