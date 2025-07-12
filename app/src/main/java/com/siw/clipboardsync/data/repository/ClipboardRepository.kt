package com.siw.clipboardsync.data.repository

import com.siw.clipboardsync.data.model.ClipboardItem
import com.siw.clipboardsync.data.model.ClipboardSyncRequest
import com.siw.clipboardsync.data.network.ApiService
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
                val errorMessage = response.body()?.message ?: "Sync failed"
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
                val errorMessage = response.body()?.message ?: "Failed to get latest clipboard"
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
                val errorMessage = response.body()?.message ?: "Failed to get clipboard history"
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
                Result.failure(Exception("Failed to delete clipboard item"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}