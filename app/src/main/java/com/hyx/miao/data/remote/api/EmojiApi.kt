package com.hyx.miao.data.remote.api

import com.hyx.miao.data.remote.dto.EmojiSyncResponse
import com.hyx.miao.data.remote.dto.UploadUrlRequest
import com.hyx.miao.data.remote.dto.UploadUrlResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Multipart
import retrofit2.http.Part
import okhttp3.MultipartBody

interface EmojiApi {
    @GET("emojis/sync")
    suspend fun sync(@Query("since") since: Long = 0L): EmojiSyncResponse

    @GET("emojis/emotions")
    suspend fun getEmotions(): List<String>
}

interface MediaApi {
    @POST("media/upload-url")
    suspend fun getUploadUrl(@Body req: UploadUrlRequest): UploadUrlResponse

    @Multipart
    @POST("media/upload")
    suspend fun upload(@Part file: MultipartBody.Part): Map<String, String>
}
