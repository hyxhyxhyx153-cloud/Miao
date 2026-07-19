package com.hyx.miao.data.remote.api

import retrofit2.http.GET
import retrofit2.http.Query

data class AppVersionResponse(
    val latestVersion: String? = null,
    val latestVersionCode: Int = 0,
    val downloadUrl: String? = null,
    val forceUpdate: Boolean = false,
    val releaseNotes: String? = null,
    val minSupportedVersionCode: Int? = null,
)

data class AppAnnouncement(
    val id: String = "",
    val title: String? = null,
    val content: String? = null,
    val type: String? = null,
    val isPinned: Boolean = false,
    val startsAt: String? = null,
)

data class AppLegalResponse(
    val privacyPolicy: String? = null,
    val userAgreement: String? = null,
    val version: String? = null,
)

interface AppApi {
    @GET("app/version")
    suspend fun getVersion(@Query("versionCode") versionCode: Int): AppVersionResponse

    @GET("app/announcements")
    suspend fun getAnnouncements(): List<AppAnnouncement>

    @GET("app/legal")
    suspend fun getLegalDocuments(): AppLegalResponse
}
