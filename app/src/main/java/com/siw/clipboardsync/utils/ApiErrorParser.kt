package com.siw.clipboardsync.utils

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * API 错误响应解析工具
 * 用于解析后端返回的 JSON 格式错误信息
 */
object ApiErrorParser {
    
    private const val TAG = "ApiErrorParser"
    private val gson = Gson()
    
    /**
     * 通用错误响应结构
     */
    data class ApiErrorResponse(
        @SerializedName("success")
        val success: Boolean = false,
        
        @SerializedName("error")
        val error: String? = null,
        
        @SerializedName("message")
        val message: String? = null
    )
    
    /**
     * 从 Retrofit Response 中解析错误信息
     * @param response Retrofit 响应对象
     * @param defaultMessage 默认错误信息
     * @return 解析后的错误信息
     */
    fun <T> parseError(response: Response<T>, defaultMessage: String = "请求失败"): String {
        // 首先尝试从 errorBody 解析
        val errorBody = response.errorBody()
        if (errorBody != null) {
            val errorMessage = parseErrorBody(errorBody)
            if (!errorMessage.isNullOrEmpty()) {
                return errorMessage
            }
        }
        
        // 如果 errorBody 解析失败，返回 HTTP 状态码相关信息
        return when (response.code()) {
            400 -> "请求参数错误"
            401 -> "未授权，请重新登录"
            403 -> "没有权限执行此操作"
            404 -> "请求的资源不存在"
            413 -> "文件过大"
            500 -> "服务器内部错误"
            502 -> "网关错误"
            503 -> "服务暂时不可用"
            else -> "$defaultMessage (${response.code()})"
        }
    }
    
    /**
     * 从 ResponseBody 解析错误信息
     * @param errorBody 错误响应体
     * @return 解析后的错误信息，如果解析失败返回 null
     */
    fun parseErrorBody(errorBody: ResponseBody?): String? {
        if (errorBody == null) return null
        
        return try {
            val errorString = errorBody.string()
            parseErrorString(errorString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read error body", e)
            null
        }
    }
    
    /**
     * 从错误字符串解析错误信息
     * @param errorString 错误响应字符串
     * @return 解析后的错误信息
     */
    fun parseErrorString(errorString: String?): String? {
        if (errorString.isNullOrEmpty()) return null
        
        return try {
            val errorResponse = gson.fromJson(errorString, ApiErrorResponse::class.java)
            // 优先返回 error 字段，其次是 message 字段
            errorResponse?.error ?: errorResponse?.message
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse error JSON, returning raw string", e)
            // 如果 JSON 解析失败，返回原始字符串（限制长度）
            errorString.takeIf { it.length < 500 }
        }
    }
    
    /**
     * 创建带有服务器错误信息的 Exception
     * @param response Retrofit 响应对象
     * @param defaultMessage 默认错误信息
     * @return Exception 对象
     */
    fun <T> createException(response: Response<T>, defaultMessage: String = "请求失败"): Exception {
        return Exception(parseError(response, defaultMessage))
    }
}
