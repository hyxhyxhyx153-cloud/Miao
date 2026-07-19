package com.hyx.miao.data.repository

import com.hyx.miao.data.local.dao.MemoryDao
import com.hyx.miao.data.local.entity.MemoryEntity
import com.hyx.miao.data.remote.api.MemoryApi
import com.hyx.miao.data.remote.dto.CreateMemoryRequest
import com.hyx.miao.data.remote.dto.UpdateMemoryRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val api: MemoryApi,
    private val dao: MemoryDao,
) {
    fun observeAll() = dao.observeAll()

    suspend fun sync() = runCatching {
        val remote = api.getAll()
        remote.forEach { dto ->
            dao.upsert(MemoryEntity(
                id = dto.id,
                content = dto.content,
                summary = dto.summary,
                source = dto.source,
                isActive = dto.isActive,
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt,
            ))
        }
        val ids = remote.map { it.id }
        if (ids.isEmpty()) dao.deleteAll() else dao.deleteNotIn(ids)
    }

    suspend fun semanticSearch(query: String): Result<List<MemoryEntity>> = runCatching {
        api.semanticSearch(query.trim()).map { dto ->
            MemoryEntity(
                id = dto.id,
                content = dto.content,
                summary = dto.summary,
                source = dto.source,
                isActive = dto.isActive,
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt,
            )
        }
    }

    suspend fun add(content: String): Result<MemoryEntity> = runCatching {
        val dto = api.create(CreateMemoryRequest(content))
        val entity = MemoryEntity(
            id = dto.id,
            content = dto.content,
            summary = dto.summary,
            source = dto.source,
            createdAt = dto.createdAt,
            updatedAt = dto.updatedAt,
        )
        dao.upsert(entity)
        entity
    }

    suspend fun update(id: String, content: String): Result<Unit> = runCatching {
        val dto = api.update(id, UpdateMemoryRequest(content))
        dao.upsert(MemoryEntity(
            id = dto.id,
            content = dto.content,
            summary = dto.summary,
            source = dto.source,
            createdAt = dto.createdAt,
            updatedAt = dto.updatedAt,
        ))
    }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        api.delete(id)
        dao.delete(id)
    }
}
