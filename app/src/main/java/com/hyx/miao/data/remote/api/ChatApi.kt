package com.hyx.miao.data.remote.api

import com.hyx.miao.data.remote.dto.ChatRequest
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming

interface ChatApi {
    @Streaming
    @POST("conversations/{id}/chat")
    suspend fun chat(
        @Path("id") id: String,
        @Body req: ChatRequest,
    ): ResponseBody

    @DELETE("conversations/{id}/messages/{msgId}")
    suspend fun deleteMessage(@Path("id") id: String, @Path("msgId") msgId: String)

    @Streaming
    @POST("conversations/{id}/messages/{msgId}/regenerate")
    suspend fun regenerate(@Path("id") id: String, @Path("msgId") msgId: String): ResponseBody

    @POST("conversations/{id}/messages/{msgId}/memory")
    suspend fun addToMemory(@Path("id") id: String, @Path("msgId") msgId: String)
}
