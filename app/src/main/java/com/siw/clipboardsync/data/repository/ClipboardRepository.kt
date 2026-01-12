package com.siw.clipboardsync.data.repository

import com.siw.clipboardsync.data.model.ClipboardItem
import com.siw.clipboardsync.data.model.ClipboardSyncRequest
import com.siw.clipboardsync.data.network.ApiService
import com.siw.clipboardsync.utils.ApiErrorParser
import com.siw.clipboardsync.utils.DeviceUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardRepository @Inject constructor(
    private val apiService: ApiService
) {
    
    suspend fun syncClipboard(
        content: String,
        contentType: String,
        deviceId: String
    ): Result<ClipboardItem> {
        return try {
            val hash = DeviceUtils.generateContentHash(content)
            val request = ClipboardSyncRequest(
                content = content,
                contentType = contentType,
                deviceId = deviceId,
                hash = hash
            )
            val response = apiService.syncClipboard(request)
            
            if (response.isSuccessful && response.body()?.success == true) {
                val clipboardItem = response.body()!!.data!!
                Result.success(clipboardItem)
            } else {
                val errorMessage = if (response.isSuccessful) {
                    response.body()?.error ?: response.body()?.message ?: "同步失败"
                } else {
                    ApiErrorParser.parseError(response, "同步失败")
                }
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getLatestClipboard(): Result<ClipboardItem?> {
        return try {
            val response = apiService.getLatestClipboard()
            
            if (response.isSuccessful && response.body()?.success == true) {
                val clipboardItem = response.body()!!.data
                Result.success(clipboardItem)
            } else {
                val errorMessage = if (response.isSuccessful) {
                    response.body()?.error ?: response.body()?.message ?: "获取最新剪贴板失败"
                } else {
                    ApiErrorParser.parseError(response, "获取最新剪贴板失败")
                }
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getClipboardHistory(limit: Int = 50, offset: Int = 0): Result<List<ClipboardItem>> {
        return try {
            val response = apiService.getClipboardHistory(limit, offset)
            
            if (response.isSuccessful && response.body()?.success == true) {
                val history = response.body()!!.data ?: emptyList()
                Result.success(history)
            } else {
                val errorMessage = if (response.isSuccessful) {
                    response.body()?.error ?: response.body()?.message ?: "获取剪贴板历史失败"
                } else {
                    ApiErrorParser.parseError(response, "获取剪贴板历史失败")
                }
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun deleteClipboardItem(itemId: String): Result<Unit> {
        return try {
            val response = apiService.deleteClipboardItem(itemId)
            
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val errorMessage = ApiErrorParser.parseError(response, "删除剪贴板项失败")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}