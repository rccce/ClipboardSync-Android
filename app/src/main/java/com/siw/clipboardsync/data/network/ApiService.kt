package com.siw.clipboardsync.data.network

import com.siw.clipboardsync.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    
    // Health check
    @GET("health")
    suspend fun healthCheck(): Response<Map<String, String>>
    
    // Authentication endpoints
    @POST("api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>
    
    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>
    
    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): Response<AuthResponse>
    
    // Device management
    @POST("api/v1/devices")
    suspend fun registerDevice(@Body request: DeviceRegistrationRequest): Response<DeviceRegistrationResponse>
    
    @GET("api/v1/devices")
    suspend fun getDevices(): Response<DeviceResponse>
    
    @DELETE("api/v1/devices/{deviceId}")
    suspend fun removeDevice(@Path("deviceId") deviceId: String): Response<Map<String, Any>>
    
    // Clipboard sync
    @POST("api/v1/clipboard/sync")
    suspend fun syncClipboard(@Body request: ClipboardSyncRequest): Response<ClipboardSyncResponse>
    
    @GET("api/v1/clipboard/latest")
    suspend fun getLatestClipboard(): Response<ClipboardLatestResponse>
    
    @GET("api/v1/clipboard/history")
    suspend fun getClipboardHistory(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<ClipboardHistoryResponse>
    
    @DELETE("api/v1/clipboard/{itemId}")
    suspend fun deleteClipboardItem(@Path("itemId") itemId: String): Response<Map<String, Any>>
}