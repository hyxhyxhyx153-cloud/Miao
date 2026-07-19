package com.hyx.miao.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hyx.miao.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<MemoryEntity>

    @Upsert
    suspend fun upsert(entity: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM memories WHERE id NOT IN (:remoteIds)")
    suspend fun deleteNotIn(remoteIds: List<String>)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
