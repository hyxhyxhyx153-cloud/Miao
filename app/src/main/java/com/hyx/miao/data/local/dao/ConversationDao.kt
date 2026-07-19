package com.hyx.miao.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hyx.miao.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Upsert
    suspend fun upsert(entity: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("DELETE FROM conversations WHERE id NOT IN (:remoteIds)")
    suspend fun deleteMissing(remoteIds: List<String>)

    @Query("UPDATE conversations SET isPinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET lastMessagePreview = :preview, lastMessageAt = :ts, updatedAt = :ts WHERE id = :id")
    suspend fun updateLastMessage(id: String, preview: String, ts: Long)

    @Query("UPDATE conversations SET unreadCount = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markRead(id: String, updatedAt: Long = System.currentTimeMillis())
}
