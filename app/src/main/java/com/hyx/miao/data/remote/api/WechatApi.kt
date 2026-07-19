package com.hyx.miao.data.remote.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST

data class QrCodeResponse(val qrcode_url: String, val qrcode: String)
data class QrStatusResponse(val data: QrStatusData?)
data class QrStatusData(val status: String, val credentials: QrCredentials? = null)
data class QrCredentials(val bot_token: String?, val baseurl: String?)
data class QrStatusRequest(
    val qrcode: String,
    // Empty string explicitly selects the built-in default. Keeping this field
    // non-null also lets the server distinguish new clients from old clients.
    val personaId: String,
)
data class BindStatusResponse(
    val bound: Boolean,
    val binding: BindingInfo? = null,
    @field:SerializedName("server_time") val serverTime: String? = null,
)
data class BindingInfo(
    val id: String?,
    val ilink_user_id: String?,
    val worker_status: String?,
    val created_at: String?,
    @field:SerializedName("persona_id") val personaId: String? = null,
    @field:SerializedName("persona_name") val personaName: String? = null,
    @field:SerializedName("connection_status") val connectionStatus: String? = null,
    @field:SerializedName("heartbeat_age_seconds") val heartbeatAgeSeconds: Int? = null,
    @field:SerializedName("last_message_age_seconds") val lastMessageAgeSeconds: Int? = null,
    @field:SerializedName("last_delivery_age_seconds") val lastDeliveryAgeSeconds: Int? = null,
    @field:SerializedName("pending_delivery_count") val pendingDeliveryCount: Int = 0,
    @field:SerializedName("failed_delivery_count") val failedDeliveryCount: Int = 0,
    @field:SerializedName("consecutive_failures") val consecutiveFailures: Int = 0,
    @field:SerializedName("last_error") val lastError: String? = null,
)
data class WechatPersonaRequest(val personaId: String)
data class WechatPersonaResponse(
    @field:SerializedName("persona_id") val personaId: String?,
    @field:SerializedName("persona_name") val personaName: String,
)

interface WechatApi {
    @POST("wechat/qrcode")
    suspend fun getQrCode(@Body body: Map<String, String> = emptyMap()): QrCodeResponse

    @POST("wechat/qrcode/status")
    suspend fun getStatus(@Body body: QrStatusRequest): QrStatusResponse

    @GET("wechat/status")
    suspend fun getBindStatus(): BindStatusResponse

    @POST("wechat/unbind")
    suspend fun unbind(@Body body: Map<String, String> = emptyMap())

    @POST("wechat/restart")
    suspend fun restart(@Body body: Map<String, String> = emptyMap())

    @PATCH("wechat/persona")
    suspend fun updatePersona(@Body body: WechatPersonaRequest): WechatPersonaResponse
}
