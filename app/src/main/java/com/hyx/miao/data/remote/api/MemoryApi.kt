package com.hyx.miao.data.remote.api

import com.hyx.miao.data.remote.dto.CreateMemoryRequest
import com.hyx.miao.data.remote.dto.MemoryResponse
import com.hyx.miao.data.remote.dto.UpdateMemoryRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MemoryApi {
    @GET("memories")
    suspend fun getAll(): List<MemoryResponse>

    @GET("memories/semantic-search")
    suspend fun semanticSearch(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20,
    ): List<MemoryResponse>

    @POST("memories")
    suspend fun create(@Body req: CreateMemoryRequest): MemoryResponse

    @PATCH("memories/{id}")
    suspend fun update(@Path("id") id: String, @Body req: UpdateMemoryRequest): MemoryResponse

    @DELETE("memories/{id}")
    suspend fun delete(@Path("id") id: String)
}
