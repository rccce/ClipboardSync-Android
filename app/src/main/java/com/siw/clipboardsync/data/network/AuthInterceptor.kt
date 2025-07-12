package com.siw.clipboardsync.data.network

import com.siw.clipboardsync.data.local.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager
) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // Skip auth for login, register, and health check endpoints
        val url = originalRequest.url.toString()
        if (url.contains("/auth/login") || 
            url.contains("/auth/register") || 
            url.contains("/health")) {
            return chain.proceed(originalRequest)
        }
        
        // Add authorization header
        val token = runBlocking { tokenManager.getAccessToken() }
        val authenticatedRequest = if (token != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }
        
        return chain.proceed(authenticatedRequest)
    }
}