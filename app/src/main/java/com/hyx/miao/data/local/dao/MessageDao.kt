package com.hyx.miao.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hyx.miao.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    fun observeByConversation(convId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt ASC LIMIT :limit OFFSET :offset")
    suspend fun getPage(convId: String, limit: Int, offset: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE clientId = :clientId LIMIT 1")
    suspend fun getByClientId(clientId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND role = 'user' AND createdAt <= :createdAt ORDER BY createdAt DESC LIMIT 1")
    suspend fun getPreviousUserMessage(conversationId: String, createdAt: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun deleteByConversation(convId: String)

    @Query("DELETE FROM messages WHERE conversationId = :convId AND role = 'assistant' AND isSynced = 0 AND isError = 0")
    suspend fun deleteTemporaryAssistantMessages(convId: String)

    @Query("DELETE FROM messages WHERE conversationId = :convId AND role = 'assistant' AND isSynced = 0 AND isError = 1 AND replyToClientId IN (:clientIds)")
    suspend fun deleteAnsweredErrorMessages(convId: String, clientIds: List<String>)

    @Query("UPDATE messages SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE messages SET isSynced = 1, serverId = :serverId WHERE id = :id")
    suspend fun markSynced(id: String, serverId: String)

    @Query("SELECT * FROM messages WHERE isSynced = 0")
    suspend fun getUnsynced(): List<MessageEntity>
}
