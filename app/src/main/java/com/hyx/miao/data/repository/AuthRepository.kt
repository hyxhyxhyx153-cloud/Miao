package com.hyx.miao.data.repository

import com.hyx.miao.data.local.TokenStore
import com.hyx.miao.data.remote.api.AuthApi
import com.hyx.miao.data.remote.api.QuotaResponse
import com.hyx.miao.data.remote.api.UserApi
import com.hyx.miao.data.remote.dto.LoginRequest
import com.hyx.miao.data.remote.dto.ForgotPasswordRequest
import com.hyx.miao.data.remote.dto.ForgotPasswordResponse
import com.hyx.miao.data.remote.dto.RegisterRequest
import com.hyx.miao.data.remote.dto.ResetPasswordRequest
import com.hyx.miao.data.remote.dto.UserDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: AuthApi,
    private val userApi: UserApi,
    private val tokenStore: TokenStore,
) {
    var currentUser: UserDto? = null
        private set

    val isLoggedIn: Boolean get() = tokenStore.accessToken != null

    suspend fun login(email: String, password: String, rememberMe: Boolean): Result<UserDto> = runCatching {
        val response = api.login(LoginRequest(email, password))
        tokenStore.saveSession(response.accessToken, response.refreshToken, rememberMe)
        currentUser = response.user
        response.user
    }

    suspend fun register(username: String, email: String, password: String): Result<UserDto> = runCatching {
        val response = api.register(RegisterRequest(username, email, password))
        tokenStore.saveSession(response.accessToken, response.refreshToken, rememberMe = true)
        currentUser = response.user
        response.user
    }

    suspend fun logout() {
        runCatching { api.logout() }
        tokenStore.clear()
        currentUser = null
    }

    fun updateQuota(quotaUsed: Int, dailyQuota: Int) {
        currentUser = currentUser?.copy(quotaUsed = quotaUsed, dailyQuota = dailyQuota)
    }

    suspend fun refreshQuota(): Result<QuotaResponse> = runCatching {
        userApi.getQuota().also { quota ->
            updateQuota(quota.quotaUsed, quota.dailyQuota)
        }
    }

    fun updateCurrentUser(user: UserDto) {
        currentUser = user
    }

    suspend fun forgotPassword(email: String): Result<ForgotPasswordResponse> = runCatching {
        api.forgotPassword(ForgotPasswordRequest(email.trim().lowercase()))
    }

    suspend fun resetPassword(token: String, password: String): Result<Unit> = runCatching {
        api.resetPassword(ResetPasswordRequest(token.trim(), password))
        Unit
    }
}
