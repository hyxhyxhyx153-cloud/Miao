package com.hyx.miao.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelProvider: String,
    val modelId: String,
    val personaId: String?,
    val userAvatarUrl: String? = null,
    val aiAvatarUrl: String? = null,
    val temperature: Float = 0.8f,
    val maxTokens: Int = 4096,
    val contextTurns: Int = 20,
    val isPinned: Boolean = false,
    val unreadCount: Int = 0,
    val isWechat: Boolean = false,
    val lastMessagePreview: String = "",
    val lastMessageAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
