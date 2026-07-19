package com.hyx.miao.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val contentType: String = "text",
    val mediaUrls: String? = null,
    val emojiId: String? = null,
    val emotion: String? = null,
    val actionText: String? = null,
    val source: String = "app",
    val isError: Boolean = false,
    val isRecalled: Boolean = false,
    val clientId: String? = null,
    val replyToClientId: String? = null,
    val serverId: String? = null,
    val isSynced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
