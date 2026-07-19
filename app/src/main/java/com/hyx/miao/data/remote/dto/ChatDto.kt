package com.hyx.miao.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    @field:SerializedName("content") val content: String,
    @field:SerializedName("mediaUrls") val mediaUrls: List<String>? = null,
    @field:SerializedName("contextClear") val contextClear: Boolean = false,
    @field:SerializedName("clientId") val clientId: String? = null,
    @field:SerializedName("emojiId") val emojiId: String? = null,
)

data class UploadUrlRequest(
    @field:SerializedName("filename") val filename: String,
    @field:SerializedName("contentType") val contentType: String,
    @field:SerializedName("size") val size: Long,
)

data class UploadUrlResponse(
    @field:SerializedName("upload_url") val uploadUrl: String,
    @field:SerializedName("file_url") val fileUrl: String?,
    @field:SerializedName("method") val method: String = "PUT",
)

sealed class SseEvent {
    data object ThinkingStart : SseEvent()
    data class Memory(val count: Int, val items: List<MemoryItem>) : SseEvent()
    data class MessageAck(val clientId: String, val messageId: String) : SseEvent()
    data class SystemNotice(val message: String) : SseEvent()
    data class Delta(val content: String) : SseEvent()
    data class Error(val message: String) : SseEvent()
    data class ImageGenerationStart(val message: String) : SseEvent()
    data class ImageGenerationProgress(val message: String) : SseEvent()
    data class ImageGenerationError(val message: String) : SseEvent()
    data class ImageGenerated(val message: MessageResponse) : SseEvent()
    data class Quota(val quotaUsed: Int, val dailyQuota: Int) : SseEvent()
    data class Done(
        val emotion: String?,
        val action: String?,
        val emojiId: String?,
        val inputTokens: Int,
        val outputTokens: Int,
    ) : SseEvent()
    data class Emoji(val id: String, val url: String) : SseEvent()
}

data class MemoryItem(
    @field:SerializedName("id") val id: String,
    @field:SerializedName("summary") val summary: String,
)
