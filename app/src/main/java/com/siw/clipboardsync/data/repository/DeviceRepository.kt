package com.siw.clipboardsync.data.repository

import com.siw.clipboardsync.data.model.Device
import com.siw.clipboardsync.data.model.DeviceRegistrationRequest
import com.siw.clipboardsync.data.network.ApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepository @Inject constructor(
    private val apiService: ApiService
) {
    
    suspend fun registerDevice(deviceId: String, deviceName: String, osVersion: String, appVersion: String): Result<Device> {
        return try {
            val request = DeviceRegistrationRequest(
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = "android",
                osVersion = osVersion,
                appVersion = appVersion
            )
            val response = apiService.registerDevice(request)
            
            if (response.isSuccessful && response.body()?.success == true) {
                val device = response.body()!!.data!!
                Result.success(device)
            } else {
                val errorMessage = response.body()?.message ?: "Device registration failed"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getDevices(): Result<List<Device>> {
        return try {
            val response = apiService.getDevices()
            
            if (response.isSuccessful && response.body()?.success == true) {
                val devices = response.body()!!.data ?: emptyList()
                Result.success(devices)
            } else {
                val errorMessage = response.body()?.message ?: "Failed to get devices"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun removeDevice(deviceId: String): Result<Unit> {
        return try {
            val response = apiService.removeDevice(deviceId)
            
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to remove device"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}