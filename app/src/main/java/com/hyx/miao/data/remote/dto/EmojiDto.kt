package com.hyx.miao.data.remote.dto

import com.google.gson.annotations.SerializedName

data class EmojiResponse(
    @field:SerializedName("id") val id: String,
    @field:SerializedName("filename") val filename: String,
    @field:SerializedName("emotionTag") val emotionTag: String,
    @field:SerializedName("description") val description: String,
    @field:SerializedName("sceneKeywords") val sceneKeywords: List<String>,
    @field:SerializedName("url") val url: String,
    @field:SerializedName("thumbUrl") val thumbUrl: String,
    @field:SerializedName("sendCount") val sendCount: Int = 0,
    @field:SerializedName("isActive") val isActive: Boolean = true,
)

data class EmojiSyncResponse(
    @field:SerializedName("emojis") val emojis: List<EmojiResponse>,
    @field:SerializedName("hasMore") val hasMore: Boolean,
)
