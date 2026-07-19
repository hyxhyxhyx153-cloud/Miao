package com.hyx.miao.data.remote.api

import com.hyx.miao.data.remote.dto.UserDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

data class QuotaResponse(
    val dailyQuota: Int,
    val quotaUsed: Int,
    val quotaExhaustedMessage: String? = null,
)

data class UserSettingsResponse(
    val settings: Map<String, @JvmSuppressWildcards Any?>,
    val updatedAt: String?,
)

interface UserApi {
    @GET("user/profile")
    suspend fun getProfile(): UserDto

    @PATCH("user/profile")
    suspend fun updateProfile(@Body body: Map<String, @JvmSuppressWildcards Any?>): UserDto

    @GET("user/quota")
    suspend fun getQuota(): QuotaResponse

    @GET("user/settings")
    suspend fun getSettings(): UserSettingsResponse

    @PUT("user/settings")
    suspend fun updateSettings(@Body body: Map<String, @JvmSuppressWildcards Any>): UserSettingsResponse

    @POST("user/change-password")
    suspend fun changePassword(@Body body: Map<String, String>)

}
