package com.hyx.miao.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "emojis")
data class EmojiEntity(
    @PrimaryKey val id: String,
    val filename: String,
    val emotionTag: String,
    val description: String,
    val sceneKeywords: String,
    val url: String,
    val thumbUrl: String,
    val sendCount: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)
