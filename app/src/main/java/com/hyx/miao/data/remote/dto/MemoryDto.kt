package com.hyx.miao.data.remote.dto

import com.google.gson.annotations.SerializedName

data class MemoryResponse(
    @field:SerializedName("id") val id: String,
    @field:SerializedName("content") val content: String,
    @field:SerializedName("summary") val summary: String,
    @field:SerializedName("source") val source: String,
    @field:SerializedName("isActive") val isActive: Boolean,
    @field:SerializedName("createdAt") val createdAt: Long,
    @field:SerializedName("updatedAt") val updatedAt: Long,
)

data class CreateMemoryRequest(
    @field:SerializedName("content") val content: String,
)

data class UpdateMemoryRequest(
    @field:SerializedName("content") val content: String,
)
