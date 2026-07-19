package com.hyx.miao.data.remote.dto

import com.google.gson.annotations.SerializedName

data class PersonaResponse(
    @field:SerializedName("id")           val id: String,
    @field:SerializedName("name")         val name: String,
    @field:SerializedName("description")  val description: String?,
    @field:SerializedName("systemPrompt") val systemPrompt: String?,
    @field:SerializedName("referenceImageUrls") val referenceImageUrls: List<String> = emptyList(),
    @field:SerializedName("isBuiltin")    val isBuiltin: Boolean,
    @field:SerializedName("sourcePersonaId") val sourcePersonaId: String? = null,
    @field:SerializedName("createdAt")    val createdAt: String?,
    @field:SerializedName("updatedAt")    val updatedAt: String?,
)

data class CreatePersonaRequest(
    @field:SerializedName("name")         val name: String,
    @field:SerializedName("description")  val description: String?,
    @field:SerializedName("systemPrompt") val systemPrompt: String?,
    @field:SerializedName("referenceImageUrls") val referenceImageUrls: List<String> = emptyList(),
)

data class UpdatePersonaRequest(
    @field:SerializedName("name")         val name: String?,
    @field:SerializedName("description")  val description: String?,
    @field:SerializedName("systemPrompt") val systemPrompt: String?,
    @field:SerializedName("referenceImageUrls") val referenceImageUrls: List<String>? = null,
)
