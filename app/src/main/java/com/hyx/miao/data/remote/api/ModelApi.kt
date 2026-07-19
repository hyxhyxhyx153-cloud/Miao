package com.hyx.miao.data.remote.api

import retrofit2.http.GET
import retrofit2.http.Query

data class ModelDto(
    val modelId: String,
    val provider: String,
    val displayName: String,
    val supportsVision: Boolean,
    val description: String,
    val isEnabled: Boolean,
)

interface ModelApi {
    @GET("models")
    suspend fun getModels(
        @Query("includeDisabled") includeDisabled: Boolean = false,
    ): List<ModelDto>
}
