package com.hyx.miao.data.remote.api

import com.hyx.miao.data.remote.dto.ConversationResponse
import com.hyx.miao.data.remote.dto.CreateConversationRequest
import com.hyx.miao.data.remote.dto.MessageResponse
import com.hyx.miao.data.remote.dto.SyncMessagesRequest
import com.hyx.miao.data.remote.dto.SyncMessagesResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ConversationApi {
    @GET("conversations")
    suspend fun getAll(@Query("search") search: String = ""): List<ConversationResponse>

    @POST("conversations")
    suspend fun create(@Body req: CreateConversationRequest): ConversationResponse

    @DELETE("conversations/{id}")
    suspend fun delete(@Path("id") id: String)

    @PATCH("conversations/{id}")
    suspend fun update(@Path("id") id: String, @Body body: Map<String, @JvmSuppressWildcards Any?>): ConversationResponse

    @GET("conversations/{id}/messages")
    suspend fun getMessages(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): List<MessageResponse>

    @POST("conversations/{id}/messages/sync")
    suspend fun syncMessages(
        @Path("id") id: String,
        @Body body: SyncMessagesRequest,
    ): SyncMessagesResponse

    @POST("conversations/{id}/messages/{messageId}/recall")
    suspend fun recallMessage(
        @Path("id") id: String,
        @Path("messageId") messageId: String,
    ): MessageResponse
}
