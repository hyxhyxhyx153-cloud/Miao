package com.hyx.miao.data.remote.api

import com.hyx.miao.data.remote.dto.CreatePersonaRequest
import com.hyx.miao.data.remote.dto.PersonaResponse
import com.hyx.miao.data.remote.dto.UpdatePersonaRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface PersonaApi {
    @GET("personas")
    suspend fun getAll(): List<PersonaResponse>

    @POST("personas")
    suspend fun create(@Body req: CreatePersonaRequest): PersonaResponse

    @PATCH("personas/{id}")
    suspend fun update(@Path("id") id: String, @Body req: UpdatePersonaRequest): PersonaResponse

    @DELETE("personas/{id}")
    suspend fun delete(@Path("id") id: String)
}
