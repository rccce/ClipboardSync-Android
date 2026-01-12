package com.siw.clipboardsync.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.siw.clipboardsync.data.model.SystemConfigResponse
import com.siw.clipboardsync.data.network.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统配置管理器
 * 负责获取和缓存后端系统配置，包括文件大小限制和允许的文件类型
 */
@Singleton
class SystemConfigManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService
) {
    companion object {
        private const val TAG = "SystemConfigManager"
        private const val PREFS_NAME = "system_config_prefs"
        private const val KEY_CONFIG = "cached_config"
        private const val KEY_LAST_FETCH = "last_fetch_time"
        private const val CACHE_DURATION_MS = 30 * 60 * 1000L // 30分钟
        
        // 默认配置值 - 服务器配置优先，这只是备用默认值
        private const val DEFAULT_MAX_FILE_SIZE = 10L * 1024 * 1024 // 10MB default
        private val DEFAULT_ALLOWED_FILE_TYPES = listOf(
            // 图片类型
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/svg+xml",
            // 文本类型
            "text/plain", "text/markdown", "text/html", "text/css", "text/csv", "text/xml",
            // 文档类型
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            // 代码/配置文件
            "application/json", "application/javascript", "application/x-yaml",
            // 压缩文件
            "application/zip", "application/x-rar-compressed", "application/x-7z-compressed", "application/gzip",
            // 音频类型
            "audio/mpeg", "audio/wav", "audio/ogg", "audio/flac", "audio/aac",
            // 视频类型
            "video/mp4", "video/webm", "video/x-msvideo", "video/quicktime",
            // 安装包
            "application/vnd.android.package-archive"
        )
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val gson = Gson()
    private val mutex = Mutex()
    
    @Volatile
    private var cachedConfig: SystemConfigResponse? = null
    
    /**
     * 获取系统配置
     * 优先返回缓存，如果缓存过期则从服务器获取
     */
    suspend fun getConfig(): SystemConfigResponse {
        return mutex.withLock {
            // 检查内存缓存
            cachedConfig?.let { config ->
                if (!isCacheExpired()) {
                    Log.d(TAG, "Using cached config: maxFileSize=${config.maxFileSize} bytes (${config.maxFileSize / 1024 / 1024}MB)")
                    return@withLock config
                }
            }
            
            // 尝试从服务器获取
            try {
                val config = fetchConfigFromServer()
                if (config != null) {
                    Log.d(TAG, "Fetched config from server: maxFileSize=${config.maxFileSize} bytes (${config.maxFileSize / 1024 / 1024}MB)")
                    cacheConfig(config)
                    return@withLock config
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch config from server", e)
            }
            
            // 尝试从本地缓存加载
            loadCachedConfig()?.let { config ->
                Log.d(TAG, "Using local cached config: maxFileSize=${config.maxFileSize} bytes (${config.maxFileSize / 1024 / 1024}MB)")
                cachedConfig = config
                return@withLock config
            }
            
            // 返回默认配置
            Log.w(TAG, "Using default config: maxFileSize=$DEFAULT_MAX_FILE_SIZE bytes (${DEFAULT_MAX_FILE_SIZE / 1024 / 1024}MB)")
            getDefaultConfig()
        }
    }
    
    /**
     * 强制刷新配置
     */
    suspend fun refreshConfig(): Result<SystemConfigResponse> {
        return try {
            val config = fetchConfigFromServer()
            if (config != null) {
                mutex.withLock {
                    cacheConfig(config)
                }
                Result.success(config)
            } else {
                Result.failure(Exception("Failed to fetch config"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh config", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取最大文件大小限制（字节）
     */
    suspend fun getMaxFileSize(): Long = getConfig().maxFileSize
    
    /**
     * 获取允许的文件类型列表
     */
    suspend fun getAllowedFileTypes(): List<String> = getConfig().allowedFileTypes
    
    /**
     * 检查文件类型是否允许
     */
    suspend fun isFileTypeAllowed(mimeType: String): Boolean {
        val allowedTypes = getAllowedFileTypes()
        if (allowedTypes.isEmpty()) return true // 空列表表示允许所有类型
        
        // 精确匹配
        if (allowedTypes.contains(mimeType)) return true
        
        // 通配符匹配 (如 "image/*")
        val category = mimeType.substringBefore("/")
        if (allowedTypes.contains("$category/*")) return true
        
        return false
    }
    
    /**
     * 检查文件大小是否在限制内
     */
    suspend fun isFileSizeAllowed(fileSize: Long): Boolean = fileSize <= getMaxFileSize()
    
    /**
     * 获取缓存的配置（同步方法，用于快速访问）
     */
    fun getCachedConfig(): SystemConfigResponse? = cachedConfig ?: loadCachedConfig()
    
    /**
     * 获取缓存的最大文件大小（同步方法）
     */
    fun getCachedMaxFileSize(): Long {
        val config = getCachedConfig()
        val maxSize = config?.maxFileSize ?: DEFAULT_MAX_FILE_SIZE
        Log.d(TAG, "getCachedMaxFileSize: ${maxSize} bytes (${maxSize / 1024 / 1024}MB), hasConfig=${config != null}")
        return maxSize
    }
    
    /**
     * 获取缓存的允许文件类型（同步方法）
     */
    fun getCachedAllowedFileTypes(): List<String> = 
        getCachedConfig()?.allowedFileTypes ?: DEFAULT_ALLOWED_FILE_TYPES
    
    private suspend fun fetchConfigFromServer(): SystemConfigResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getSystemConfig()
                if (response.isSuccessful) {
                    val apiResponse = response.body()
                    if (apiResponse?.success == true && apiResponse.data != null) {
                        Log.d(TAG, "Successfully fetched config from server: maxFileSize=${apiResponse.data.maxFileSize} bytes (${apiResponse.data.maxFileSize / 1024 / 1024}MB)")
                        apiResponse.data
                    } else {
                        Log.e(TAG, "Server returned unsuccessful response: success=${apiResponse?.success}, error=${apiResponse?.error}")
                        null
                    }
                } else {
                    Log.e(TAG, "Server returned HTTP error: ${response.code()} - ${response.message()}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error fetching config", e)
                null
            }
        }
    }
    
    private fun cacheConfig(config: SystemConfigResponse) {
        cachedConfig = config
        prefs.edit()
            .putString(KEY_CONFIG, gson.toJson(config))
            .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Config cached: maxFileSize=${config.maxFileSize}, allowedTypes=${config.allowedFileTypes}")
    }
    
    private fun loadCachedConfig(): SystemConfigResponse? {
        val json = prefs.getString(KEY_CONFIG, null) ?: return null
        return try {
            gson.fromJson(json, SystemConfigResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cached config", e)
            null
        }
    }
    
    private fun isCacheExpired(): Boolean {
        val lastFetch = prefs.getLong(KEY_LAST_FETCH, 0)
        return System.currentTimeMillis() - lastFetch > CACHE_DURATION_MS
    }
    
    private fun getDefaultConfig(): SystemConfigResponse {
        Log.w(TAG, "Using default config")
        return SystemConfigResponse(
            maxFileSize = DEFAULT_MAX_FILE_SIZE,
            allowedFileTypes = DEFAULT_ALLOWED_FILE_TYPES
        )
    }
}
