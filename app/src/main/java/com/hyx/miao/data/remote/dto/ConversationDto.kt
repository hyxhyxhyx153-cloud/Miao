package com.hyx.miao.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ConversationResponse(
    @field:SerializedName("id") val id: String,
    @field:SerializedName("title") val title: String?,
    @field:SerializedName("modelProvider") val modelProvider: String,
    @field:SerializedName("modelId") val modelId: String,
    @field:SerializedName("personaId") val personaId: String?,
    @field:SerializedName("userAvatarUrl") val userAvatarUrl: String? = null,
    @field:SerializedName("aiAvatarUrl") val aiAvatarUrl: String? = null,
    @field:SerializedName("temperature") val temperature: Float = 0.8f,
    @field:SerializedName("maxTokens") val maxTokens: Int = 4096,
    @field:SerializedName("contextTurns") val contextTurns: Int = 20,
    @field:SerializedName("isPinned") val isPinned: Boolean = false,
    @field:SerializedName("unreadCount") val unreadCount: Int = 0,
    @field:SerializedName("isWechat") val isWechat: Boolean = false,
    @field:SerializedName("lastMessagePreview") val lastMessagePreview: String = "",
    @field:SerializedName("lastMessageAt") val lastMessageAt: String?,
    @field:SerializedName("createdAt") val createdAt: String?,
)

data class CreateConversationRequest(
    @field:SerializedName("modelProvider") val modelProvider: String,
    @field:SerializedName("modelId") val modelId: String,
    @field:SerializedName("personaId") val personaId: String?,
    @field:SerializedName("title") val title: String?,
    @field:SerializedName("temperature") val temperature: Float = 0.8f,
    @field:SerializedName("maxTokens") val maxTokens: Int = 4096,
    @field:SerializedName("contextTurns") val contextTurns: Int = 20,
)

data class MessageResponse(
    @field:SerializedName("id") val id: String,
    @field:SerializedName("conversationId") val conversationId: String,
    @field:SerializedName("role") val role: String,
    @field:SerializedName("content") val content: String?,
    @field:SerializedName("contentType") val contentType: String = "text",
    @field:SerializedName("mediaUrls") val mediaUrls: List<String>?,
    @field:SerializedName("emojiId") val emojiId: String?,
    @field:SerializedName("emotion") val emotion: String?,
    @field:SerializedName("actionText") val actionText: String?,
    @field:SerializedName("source") val source: String?,
    @field:SerializedName("isError") val isError: Boolean = false,
    @field:SerializedName("isRecalled") val isRecalled: Boolean = false,
    @field:SerializedName("clientId") val clientId: String? = null,
    @field:SerializedName("replyToClientId") val replyToClientId: String? = null,
    @field:SerializedName("createdAt") val createdAt: String?,
)

data class SyncMessageRequest(
    @field:SerializedName("clientId") val clientId: String,
    @field:SerializedName("content") val content: String,
    @field:SerializedName("contentType") val contentType: String,
    @field:SerializedName("mediaUrls") val mediaUrls: List<String>?,
    @field:SerializedName("emojiId") val emojiId: String? = null,
)

data class SyncMessagesRequest(
    @field:SerializedName("messages") val messages: List<SyncMessageRequest>,
)

data class SyncedMessage(
    @field:SerializedName("clientId") val clientId: String,
    @field:SerializedName("serverId") val serverId: String,
)

data class SyncMessagesResponse(
    @field:SerializedName("synced") val synced: List<SyncedMessage>,
)
