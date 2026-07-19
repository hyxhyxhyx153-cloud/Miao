package com.hyx.miao.data.remote

import com.google.gson.Gson
import com.hyx.miao.data.local.TokenStore
import com.hyx.miao.data.remote.dto.RefreshRequest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
    private val endpointProvider: ApiEndpointProvider,
) : Interceptor {
    private val refreshLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val accessToken = tokenStore.accessToken
        val request = if (accessToken != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            chain.request()
        }

        val response = chain.proceed(request)

        if (response.code != 401 || tokenStore.refreshToken == null) return response

        val tokenForRetry = synchronized(refreshLock) {
            val latestToken = tokenStore.accessToken
            if (!latestToken.isNullOrBlank() && latestToken != accessToken) {
                latestToken
            } else {
                tryRefresh()?.first
            }
        } ?: return response

        response.close()
        return chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", "Bearer $tokenForRetry")
                .build()
        )
    }

    private fun tryRefresh(): Pair<String, String>? {
        val refreshToken = tokenStore.refreshToken ?: return null
        return try {
            val gson = Gson()
            val body = gson.toJson(RefreshRequest(refreshToken)).toRequestBody("application/json".toMediaType())
            val refreshRequest = Request.Builder()
                .url(endpointProvider.baseUrl.resolve("auth/refresh") ?: return null)
                .post(body)
                .build()
            val client = OkHttpClient()
            client.newCall(refreshRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: return null
                    val auth = gson.fromJson(json, com.hyx.miao.data.remote.dto.AuthResponse::class.java)
                    tokenStore.accessToken = auth.accessToken
                    tokenStore.refreshToken = auth.refreshToken
                    Pair(auth.accessToken, auth.refreshToken)
                } else {
                    if (response.code == 401) tokenStore.clear()
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
