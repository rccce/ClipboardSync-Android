package com.siw.clipboardsync.data.local

import android.util.Base64
import org.json.JSONObject
import java.util.*

/**
 * JWT Token解析器
 * 用于解析JWT Token的payload并提取关键信息
 */
class JWTParser {
    
    data class TokenInfo(
        val userId: String,
        val deviceId: String?,
        val expirationTime: Date,
        val issuedAt: Date
    )
    
    companion object {
        /**
         * 解析JWT Token获取Token信息
         * @param token JWT Token字符串
         * @return TokenInfo对象，解析失败返回null
         */
        fun parseToken(token: String): TokenInfo? {
            return try {
                val parts = token.split(".")
                if (parts.size != 3) return null
                
                val payload = parts[1]
                val decodedBytes = Base64.decode(addPadding(payload), Base64.URL_SAFE or Base64.NO_WRAP)
                val json = JSONObject(String(decodedBytes))
                
                TokenInfo(
                    userId = json.optString("user_id", ""),
                    deviceId = json.optString("device_id").takeIf { it.isNotEmpty() },
                    expirationTime = Date(json.getLong("exp") * 1000),
                    issuedAt = Date(json.optLong("iat", System.currentTimeMillis() / 1000) * 1000)
                )
            } catch (e: Exception) {
                android.util.Log.e("JWTParser", "Failed to parse token", e)
                null
            }
        }
        
        /**
         * 检查Token是否已过期
         * @param token JWT Token字符串
         * @return true如果Token已过期或无效
         */
        fun isTokenExpired(token: String): Boolean {
            val tokenInfo = parseToken(token) ?: return true
            return Date() >= tokenInfo.expirationTime
        }
        
        /**
         * 检查Token是否即将过期
         * @param token JWT Token字符串
         * @param minutesBefore 提前多少分钟算作即将过期，默认5分钟
         * @return true如果Token即将过期
         */
        fun isTokenNearExpiry(token: String, minutesBefore: Int = 5): Boolean {
            val tokenInfo = parseToken(token) ?: return true
            val expiryTime = tokenInfo.expirationTime.time
            val currentTime = System.currentTimeMillis()
            val timeUntilExpiry = expiryTime - currentTime
            return timeUntilExpiry > 0 && timeUntilExpiry <= minutesBefore * 60 * 1000
        }
        
        /**
         * 获取Token剩余有效时间（毫秒）
         * @param token JWT Token字符串
         * @return 剩余时间毫秒数，过期或无效返回0
         */
        fun getTimeUntilExpiry(token: String): Long {
            val tokenInfo = parseToken(token) ?: return 0
            val timeUntilExpiry = tokenInfo.expirationTime.time - System.currentTimeMillis()
            return maxOf(0, timeUntilExpiry)
        }
        
        /**
         * 为Base64字符串添加必要的填充
         */
        private fun addPadding(base64: String): String {
            val remainder = base64.length % 4
            return if (remainder != 0) {
                base64 + "=".repeat(4 - remainder)
            } else base64
        }
    }
}

/**
 * Token状态枚举
 */
enum class TokenStatus {
    VALID,      // Token有效
    NEAR_EXPIRY, // Token即将过期（5分钟内）
    EXPIRED,    // Token已过期
    INVALID;    // Token无效或格式错误
    
    companion object {
        /**
         * 根据Token字符串确定Token状态
         */
        fun from(token: String?): TokenStatus {
            if (token == null || token.isEmpty()) return INVALID
            return when {
                JWTParser.isTokenExpired(token) -> EXPIRED
                JWTParser.isTokenNearExpiry(token) -> NEAR_EXPIRY
                else -> VALID
            }
        }
    }
} 