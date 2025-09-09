package com.siw.clipboardsync.data.model

import com.google.gson.annotations.SerializedName

data class RefreshTokenData(
    @SerializedName("access_token")
    val accessToken: String,
    @SerializedName("refresh_token")
    val refreshToken: String? = null
)

data class RefreshResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("data")
    val data: RefreshTokenData?,
    @SerializedName("message")
    val message: String? = null
) 