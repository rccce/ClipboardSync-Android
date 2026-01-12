package com.siw.clipboardsync.data.model

import com.google.gson.annotations.SerializedName

data class Device(
    @SerializedName("id")
    val id: String,
    @SerializedName("user_id")
    val userId: String,
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("device_type")
    val deviceType: String,
    @SerializedName("os_version")
    val osVersion: String,
    @SerializedName("app_version")
    val appVersion: String,
    @SerializedName("is_online")
    val isOnline: Boolean,
    @SerializedName("last_active")
    val lastActive: String,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("updated_at")
    val updatedAt: String
)

data class DeviceResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("data")
    val data: List<Device>?,
    @SerializedName("message")
    val message: String? = null,
    @SerializedName("error")
    val error: String? = null
)

data class DeviceRegistrationRequest(
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("device_type")
    val deviceType: String = "android",
    @SerializedName("os_version")
    val osVersion: String,
    @SerializedName("app_version")
    val appVersion: String
)

data class DeviceRegistrationResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("data")
    val data: Device?,
    @SerializedName("message")
    val message: String? = null,
    @SerializedName("error")
    val error: String? = null
)