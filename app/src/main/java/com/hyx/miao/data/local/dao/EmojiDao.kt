package com.hyx.miao.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hyx.miao.data.local.entity.EmojiEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EmojiDao {

    @Query("SELECT * FROM emojis WHERE emotionTag = :tag AND isActive = 1")
    suspend fun getByEmotion(tag: String): List<EmojiEntity>

    @Query("SELECT * FROM emojis WHERE isActive = 1")
    suspend fun getAll(): List<EmojiEntity>

    @Query("SELECT * FROM emojis WHERE isActive = 1 ORDER BY sendCount DESC, createdAt DESC")
    fun observeAll(): Flow<List<EmojiEntity>>

    @Query("SELECT DISTINCT emotionTag FROM emojis WHERE isActive = 1")
    suspend fun getEmotions(): List<String>

    @Upsert
    suspend fun upsert(entity: EmojiEntity)

    @Upsert
    suspend fun upsertAll(entities: List<EmojiEntity>)

    @Query("UPDATE emojis SET sendCount = sendCount + 1 WHERE id = :id")
    suspend fun incrementSendCount(id: String)
}
